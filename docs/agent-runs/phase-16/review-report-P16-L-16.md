## P16-L-16 review: repair-E02-rag (rag-engineer)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

**P16-L-16 verdict: RESOLVED.** I found no new critical, major or minor finding.

### Check 1: Is the stated cause right? Yes, confirmed at HEAD 7951f94 (from the diff's minus lines) and in the current files.
- Before the fix, `RagIngestJob.execute` called `loadConfig(opts.config)`. `loadConfig` takes `env: Map[String,String] = sys.env` and applies the `SCHOLARCHAIN_CFG_*` filter at `modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala:333-347`.
- Before the fix, `RetrievalEvalJob.execute` called `RagIngestJob.loadConfig(opts.config)`.
- Before the fix, `BenchmarkRunner.run` called `loadConfig(opts.config)`, defined at `BenchmarkRunner.scala:95-109`.
- The test fixtures build their expected config with an empty environment, but the jobs read the shell. Once the shell overrides the graph bucket, the job refuses the export at `RagIngestJob.scala:472-473` ("--export must be in a bucket of the configured data-lake zones"). The eval report copy goes to a bucket the test does not read. The agent's line numbers are within a few lines of the current file.

### Check 2: Is production behaviour unchanged? Yes.
- `RagIngestJob.main` (`:352-360`), `RetrievalEvalJob.main` (`RetrievalEvalJob.scala:203-211`) and `BenchmarkRunner.main` (`BenchmarkRunner.scala:142-147`) still call `execute(opts)` / `run(opts)` with defaults.
- The new defaults are `configEnv: () => Map[String,String] = () => sys.env` (`RagIngestJob.scala:215`, `RetrievalEvalJob.scala:195`, `BenchmarkRunner.scala:155`).
- Each thunk is called once, at the same place as the old load: `RagIngestJob.scala:450`, `RetrievalEvalJob.scala:257`, `BenchmarkRunner.scala:184`.
- It feeds the same `loadConfig`, whose prefix filter (`env.filter(_._1.startsWith(ConfigLoader.EnvPrefix))`) is unchanged.
- The `loadConfig` defaults themselves are unchanged. Every new parameter was added last, with a default, so callers compile unchanged.

### Check 3: Does every rag test that reaches a job or config loader pass an explicit map? Yes.
I searched `modules/rag/src/test` for job calls (`RagIngestJob.run/execute/loadConfig/Hooks`, `RetrievalEvalJob.execute/Hooks`, `BenchmarkRunner.run/loadConfig`, `.main(`), for `sys.env`, `System.getenv`, `ConfigLoader.load` and `loadFromEnvironment`, and for unqualified calls.
- **RagIngestJobSuite:** every job call passes `Hermetic`, `NoConfigEnv` or `() => jobConfigEnv`: lines 40, 196, 226-230, 253, 359-362, 372, 390-411 and the `s3Fixture` call at 740-745. `storageConfigOf` passes an explicit map (default `Map.empty`, line 607).
- **RagIngestG27Suite:** 70-74 passes `NoConfigEnv`; 127 and 133 pass `Map.empty`.
- **RetrievalEvalSuite:** 260, 291, 305, 317 and 329 all pass `configEnv`.
- **BenchmarkRunnerSuite:** 68, 108, 113-117, 137-141, 170, 231-234 and 242 all pass explicit maps.
- **RuntimeRepairsSuite:** 498-507 already passed explicit maps.
- **RagFixtures.loadConfig:** defaults to `Map.empty`, and `RuntimeFixtures.config` and `storageConfig` use it.
- The only remaining `sys.env` uses are the two `Hooks().configEnv() == sys.env` checks.
- Nothing in rag main loads config any other way: `ConfigLoader` is used only inside the two `loadConfig` functions.
- `tests/**` has no reference to `RagIngestJob`, `RetrievalEvalJob`, `BenchmarkRunner` or `sys.env`.
- **Not config:** `BenchmarkRunner.run`'s `env = sys.env.get` (line 151) is still used by tests at 68, 108, 231 and 242. It only looks up the anthropic key variable by name in `preflight`, and those tests use the fake provider, so `SCHOLARCHAIN_CFG_*` values cannot affect them.

### Check 4: Do the new tests fail without the fix, and do they exercise the seam? Yes.
- Three tests depend on the seam:
  - the RagIngestJobSuite override test: the job must accept an export placed in the override graph bucket;
  - the RetrievalEvalSuite copy-bucket test: the copy must be in the override bucket and absent from the file's bucket, and the reverse with an empty map;
  - the BenchmarkRunnerSuite test: `SCHOLARCHAIN_CFG_NOT_A_KEY` in the given map must cause a failed status that names it.
- Each of the three fails if the call sites go back to `loadConfig(opts.config)`. That matches the reported revert result of 3 failed out of 48.
- "The job reads only the configuration environment it is given" and the two default-equals-process-environment tests would pass under the revert in an agent shell. That is acceptable: they are a mirror check and a guard on the default, and the agent did not claim they fail without the fix.

### Check 5: Are environment values ever printed? No.
- `RagIngestJobSuite.scala:570-573` and `RetrievalEvalSuite.scala:367-370` use `assert(<boolean>, "<constant string>")`. MUnit's `assert` renders only the clue and the source location, not the values.
- Other new clues render masked reports (`t.take(300)` at RagIngestJobSuite:253, `report.render` in the eval test) or statuses. Those hold only synthetic buckets or masked placeholders.

### Check 6: Secrets, identifiers, limits and prompts
- The only new identifier is `RagFixtures.OverrideBucket = "example-override-bucket"`. The other names (`example-graph-bucket`, `example-artifact-bucket`, `SCHOLARCHAIN_CFG_NOT_A_KEY`) are synthetic.
- There is no key, token or account ID.
- No threshold, limit, `MaxStepMillis`, prompt or `prompts/**` file changed.

### Other modules that load config from the process environment
The agent's list is accurate. None of these loads config from the process environment during `Test`, so none runs in `sbt check`:
- **AwsAdaptersAwsSuite:29:** `loadFromEnvironment(sys.env)` is a `private lazy val`. It is reached only from tests tagged `RequiresAws`, and from `maskedNames`, which runs only on failure.
- **EmulatorAdaptersEmulatorSuite:74-91 and EmbeddingWorkerEmulatorSuite:89-106:** the load is in `beforeAll`, and every test is tagged `RequiresEmulator`. `build.sbt:173-175` sets `Test --exclude-tags=RequiresAws,RequiresEmulator`.
- **Evidence that `beforeAll` does not run under `Test`:** `sbt check` passes in agent shells, which have no emulator variables, and in that case `beforeAll` would refuse and throw.
- **Production defaults with hermetic tests:**
  - `StorageLoadMain.Wiring(env = sys.env)` (`StorageLoadMain.scala:285`): every test goes through the `wiring(...)` helper (`StorageLoadMainLocalSuite.scala:57-63`, `env = Map.empty`), an explicit override map (715-716), `.copy(env = ...)` (747), or `Wiring(env = Map.empty)` (`StorageLoadMainArgsSuite.scala:147,152`).
  - `services Main.start(args, sys.env)` is called only from `main`; `CompositionSuite.scala:131-147` passes explicit maps.
- **Not in the agent's list:** `CompositionSuite.scala:425` (`sys.env.getOrElse("SCHOLARCHAIN_SERVICES_BASE_URL", ...)`). It is a `RequiresAws` live-smoke test that reads a URL, not config, so it does not matter here.
- `entity-resolution`, `hadoop`, `spark` and `core` main code read no process environment at all; ER `EvalMain.main` calls in tests are not affected.

### Gate evidence (accepted)
- `.claude/.last-test-run` reads `timestamp=2026-11-22T15:54:10.491673Z`, `module_tests=2306`, `contract_tests=399`. This matches the stated `sbt check` at 09:54.
- I accepted the agent's quoted output for `packageCheck: ok`. I did not read the log files.

### Observations (not findings)
- Hermeticity depends on test authors remembering to pass a map. A future rag test that calls `RagIngestJob.run(opts)`, `RetrievalEvalJob.execute(opts, stores)` or `BenchmarkRunner.run(opts)` with default hooks would read the shell again. The agent's "proposed later" fix fits this: a source-scan test, or removing the `execute` and `run` defaults so `main` passes `sys.env` explicitly. Owner: rag-engineer.
- Process note: the agent reports one read-only `sed -n` and one read-only `find -newer`, both against the work order's note. Neither wrote anything, and no code is affected.
- The human's terminal rerun has not happened yet. It is the only direct proof in a shell that exports `SCHOLARCHAIN_CFG_*`.

### New findings
None. The next ID, P16-L-17, is unused.

| severity | interface | file:line | failure input | impact | owner |
|---|---|---|---|---|---|
| (none) | | | | | |

### Files read
**Read in full:**
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/15acd480-beb2-4248-b3ae-3f168fefe386/scratchpad/phase16-reports/repair-E02-rag.md
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/15acd480-beb2-4248-b3ae-3f168fefe386/scratchpad/phase16-reports/diff-repair-E02-rag.txt
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala
- /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run

**Read in part:**
- `RetrievalEvalJob.scala` lines 180-289, plus Grep across the whole file.
- `BenchmarkRunner.scala` lines 85-194, plus Grep across the whole file.
- The five changed test files (`RagFixtures.scala`, `RagIngestG27Suite.scala`, `RagIngestJobSuite.scala`, `eval/RetrievalEvalSuite.scala`, `runtime/BenchmarkRunnerSuite.scala`): every changed hunk through the full diff, and every job, loader and `sys.env` call site through Grep with context. I did not read them line by line from start to finish.
- `RuntimeFixtures.scala`: Grep only.
- `AwsAdaptersAwsSuite.scala` lines 1-40; `EmulatorAdaptersEmulatorSuite.scala` lines 55-124; `EmbeddingWorkerEmulatorSuite.scala` lines 70-134.
- `StorageLoadMain.scala` lines 275-299; `CompositionSuite.scala` lines 405-432.
- `build.sbt` and `StorageLoadMainLocalSuite.scala` / `StorageLoadMainArgsSuite.scala`: Grep only.

**Not read:** the `e02-rag-check.log` and `e02-rag-package.log` files, and the work order `prompt.md`. I relied on the sha256 and scope given in the invocation.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), Phase 16 session 3, review of repair-E02-rag (P16-L-16); base HEAD 7951f94cca7da7d9bf30a5b22dce1639a156e330
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run (reviewer has no Bash). Accepted the agent's reported results: targeted 67/67 pass; revert 3 of 48 fail, exactly the three seam tests.
VALIDATION RESULTS: the marker file confirms sbt check at 2026-11-22T15:54:10Z (module_tests=2306, contract_tests=399). Code checks 1 to 6 verified as described above.
ASSUMPTIONS: MUnit skips `beforeAll` for suites whose tests are all excluded by tag. This is supported because sbt check passes in shells without emulator variables. MUnit's `assert(Boolean, clue)` does not render the operand values.
RISKS: hermeticity relies on convention (see Observations). The fix has not been confirmed yet in the human's terminal that exports SCHOLARCHAIN_CFG_*.
MANUAL ACTION REQUIRED: the human runs `sbt -batch "rag/testOnly org.scholarchain.rag.RagIngestJobSuite org.scholarchain.rag.eval.RetrievalEvalSuite"` in the terminal that exports the Phase 15 variables, as the repair report asks.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human's terminal rerun, then E2 fresh-clone check attempt 2, then the human phase gate.

VERDICT: APPROVE
