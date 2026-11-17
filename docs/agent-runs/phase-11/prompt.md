# Phase 11 work order: adversarial testing and full repository review

This phase runs before the first deploy: it attacks the code before the cloud bill does. It runs overnight while I am mostly asleep. A stop that needs me costs hours, so everything that can be decided here is decided here.

**Where things are written:**
- While the run is going, every report is written to the session scratchpad (decision 12).
- The final home of the evidence is docs/agent-runs/phase-11/. I copy the files there myself with `cp -n` in the morning.
- No agent and no orchestrator writes anything under `docs/agent-runs/`.

## How the run ends (phase result)

The orchestrator's final summary starts with exactly one of these lines:
- `PHASE RESULT: COMPLETE`: every step ran; the closing `sbt check` passed; every P1 item is done; no critical or major finding is open.
- `PHASE RESULT: COMPLETE_WITH_OPEN_ITEMS`: every step ran and the closing `sbt check` passed, but at least one P1 item, critical or major finding, or step is open. Each open item is listed.
- `PHASE RESULT: GATE_NOT_MET`: the closing `sbt check` did not pass after step 4c.
- `PHASE RESULT: BLOCKED`: the run stopped for me (decision 11).

None of these is my approval. Only my human gate accepts the phase.

## Standing rules (CLAUDE.md "Operating rules for agent runs" and "Definition of done" apply; repeated here; Phase 11 exceptions are marked NEW)

- **Routing:** hub and spoke. Agents never call each other; every handoff goes through the orchestrator. An agent never edits another owner's file, even to fix an obvious mistake; it reports it. The orchestrator never does specialist work inline and makes no technical decision that belongs to an owner.
- **Tests (amendment-02 definition of done):**
  - Before reporting COMPLETE, an agent runs the targeted tests of every project it changed: the suites it added or changed and their direct users.
  - Core needs both builds. A `prompts/**` change counts as `rag`; a `tests/**` change counts as `contractTests`.
  - Put everything you need at one verification point into **one** sbt invocation, with one quoted argument per project. Example: `sbt -batch "core/testOnly A B" "core2_13/testOnly A B" "rag/testOnly C"`. Don't start sbt again for each suite.
  - One full `sbt check` runs at the end of each wave. Only the wave closer named in decision 2 runs it. Never repeat a passing `sbt check` without a source change.
  - NEW, step 0 only: build-engineer compiles only; the wave-1 closer's full check covers its `build.sbt` change. If a stop hook still asks build-engineer for `sbt check`, it runs it once.
- **Test evidence** (NEW; amendment-02 known gap 1: the subagent stop hook is not enforced in real runs). Every source-changing agent ends its report with this block, filled from the output it saw (no full logs):
  ```
  TEST EVIDENCE
  - command: <the exact sbt command line>
  - finished: <local time>
  - per suite: <suite> run=<n> passed=<n> failed=<n> skipped=<n>   (one line per suite named in the command)
  - result line: <the sbt result line, verbatim>
  - expected-failure flips: <T-ID: MUnit said the test was expected to fail but passed>  (or "none")
  - source changed after this run: no
  ```
  - Every suite named in the command must show `run` above 0. "No tests to run" means a wrong suite name and does not count.
  - The orchestrator checks each block. Then it runs one read-only check per changed project: `grep -l -E 'failures="[1-9]|errors="[1-9]' modules/<m>/target/test-reports/TEST-*.xml` (for core, both `target/jvm-3/test-reports` and `target/jvm-2.13/test-reports`). Output must be empty.
    - It does not run this check on `tests/contracts/target/test-reports`, where flipped `.fail` tests are expected to fail.
  - If the block is missing, incomplete, or the grep shows a failing report, the orchestrator sends the agent back once: "Run your targeted tests and give the TEST EVIDENCE block."
  - A second miss is recorded as a process finding, and the run goes on; the closer's full check still covers the code.
- **One sbt at a time (strict):** only one sbt process runs at any moment, and the orchestrator never runs two source-changing agents at once. NEW: read-only agents (code-reviewer, and test-engineer in step 1a, which writes only its report) may run at the same time as one source-changing agent.
- **No watchers:** never start `tail -f`, `tail -F`, `watch`, `less +F`, `sbt ~…` or any command that does not end by itself (the guard blocks them). A step ends with no background job left.
- **Orchestrator turn rule:**
  - The orchestrator never ends its turn while an agent is still running.
  - It waits by checking the agent status tool, if it has one. Otherwise it runs `sleep 240` with the Bash tool (timeout 300000) and checks again, until every running agent has returned.
  - When it must stop for me, it first does decision 12 steps 2–3 for the reports that exist. Its message then starts with the line `STATUS: BLOCKED-ON human`, asks exactly one question, and gives its recommended answer.
- **No memory or settings writes** during the run (the guard blocks them). A note worth keeping goes in the report.
- **Commands** (I may be asleep; an approval prompt stalls the run):
  - Use only:
    - `sbt -batch …`;
    - read-only git (`git status`, `git diff`, `git log`, `git show`, `git ls-files`, `git rev-parse`, `git blame`);
    - `grep`, `head`, `tail -n`, `ls`, `wc`, `shasum`, `cut`, `sort`, `uniq`, `sed -n`;
    - plus the wait pattern below (`for`, `seq`, `sleep <seconds>`, `echo`, `;`, `&&`, `|`).
  - Read files with the Read tool (not `cat`). Find files with the Glob tool (not `find`).
  - No python, java, curl, installs, docker, aws, terraform, `bash -n`, `awk`, `od`, `cmp`.
  - Keep these words off command lines: rm, delete, drop, truncate, destroy, apply, force, reset, printenv, secret, credential, curl. (Test source files may contain them as data; command lines may not.)
  - Create and change files only with the Write and Edit tools, including report files in the scratchpad. Never `sed -i`, a heredoc into a file, or shell redirection into the repository.
  - Never `cd`. The only shell write targets allowed are sbt logs, under your session scratchpad, written as a literal absolute path starting `/private/tmp/claude-501/`.
  - If a command asks for approval, do not retry variants. Skip it if it is optional; otherwise finish with `STATUS: BLOCKED-ON` and the evidence.
- **Long sbt runs** (anything that may pass 10 minutes, such as `sbt check` or the fake benchmark). The Bash tool stops a call after at most 10 minutes, so:
  1. Start the run with the Bash tool's background option. Example: `sbt -batch check > /private/tmp/claude-501/<scratchpad>/check.log 2>&1; echo "EXIT=$?" >> /private/tmp/claude-501/<scratchpad>/check.log`
  2. Wait with a bounded loop, as one Bash call with timeout 600000. Example: `for i in $(seq 1 36); do grep -q '^EXIT=' /private/tmp/claude-501/<scratchpad>/check.log && break; sleep 15; done; tail -n 40 /private/tmp/claude-501/<scratchpad>/check.log`
  3. Repeat step 2 until an `EXIT=` line appears, at most 5 times (about 45 minutes). If it never appears, report FAILED with the last 40 log lines.
  4. Read results with `grep -n -E '\[error\]|Failed|Passed|Total|EXIT=' <log>`, not by reading the whole log.
  5. `<scratchpad>` and `<log>` in these examples are placeholders. Always type the full absolute path: in zsh, a literal `<` is a redirection and the command fails. Give each run its own log name (for example `step1b-check.log`, `step4-check.log`, `step4c-check.log`, `rag-fake-benchmark.log`), so an older log is never overwritten and stays as evidence.

  - On 2026-11-16 a probe in this repository ran this exact pattern in auto mode with no approval prompt, from the main session and from a subagent. Short targeted runs (under 10 minutes) may run in the foreground with timeout 600000.
  - The Bash tool may say that you will be notified when a background command ends and should not poll. In this phase, poll anyway with the bounded loop. A subagent that ends its turn to wait has ended its work; the run then stalls (Phase 10).
- **No AWS, no external network.** No call to AWS, Voyage, Anthropic or Bedrock. No new library. Tests may open HTTP connections only to a server they start themselves on `127.0.0.1`, port 0.
- **Live tests:** every test that needs live AWS or a live external API is tagged `RequiresAws`. Write and compile it; never run it. Never run `sbt awsTest` or `--llm anthropic`.
- **Secrets:** no key, token, account ID or bucket name in code, config, tests, logs, responses or reports. Code reads a key only from an environment variable whose NAME comes from configuration. Tests use fakes and placeholders.
- **Model text:** model output that fails validation is stored only as its sha256, length and error. No prompt asks for reasoning.
- **Scope:** build only what your work order lists. No refactoring beyond what a fix needs. Anything else goes on a "proposed later" list in the report.
- **Repair limits:** a repair round is defined in decision 8. Only critical and major findings, and the P1 items of decision 8, get one. The owner adds a test that fails without each fix. New minor findings are recorded, not repaired.
- **Interface handoff:** every source-changing agent ends its report with `INTERFACE HANDOFF`: files changed; public types and signatures created or changed; test fixtures others can use (class and package); thread-safety; known limits.
- **Report contract:** the 14 standard fields, in order (STATUS … NEXT GATE), then the agent-specific sections, then TEST EVIDENCE. Keep it bounded: no raw logs, no unchanged source. An agent never reports a command as successful unless it saw the successful output.
- **Write your report as you go:**
  - Create your report file in the scratchpad (decision 12) early.
  - Add each finding and each finished item to it as soon as it exists; don't rebuild everything at the end.
  - Your final message returns the same text.

## Human architect decisions

### 1. Environment and what "done" means

- Everything runs on this laptop with in-memory stores, fakes and stub SDK clients. No AWS resource exists yet (Terraform is Phase 12). Do not create any cloud resource by any means.
- The spec's "bounded AWS integration tests under the approved profile": there is no approved profile in this phase. Every live AWS test is `RequiresAws`, compiled and not run; they run in Phase 14. The Neptune timeout, DynamoDB conditional conflict and S3 Vectors throttling categories are tested with stub SDK clients.
- After the phase, I run one live benchmark myself (attempt-07; decision 12). Agents make that command work; they never run it.
- Starting point (from my bundle, not re-counted):
  - HEAD `2582a50`, clean tree;
  - `.claude/.last-test-run` written 2026-11-16T12:10:31Z with `module_tests=1877`, `contract_tests=336`;
  - two contract tests are expected failures from Phase 5 (F8, F9); Phase 10 reported 3 skipped module tests.

  Report what you actually find. If a count differs, say so and don't force it.
- The orchestrator runs `shasum -a 256 docs/agent-runs/phase-11/prompt.md` once at the start, records the hash in its summary, and puts it in every work order. Agents do not recompute it.
- **Work orders are compact.** Each work order starts with a contract of at most 15 lines:
  - task;
  - write territory;
  - the IDs it must handle;
  - input files with paths (and symbols or line hints where known);
  - which sections of this prompt to read (always "Standing rules", plus the decisions named);
  - done-when;
  - the report file path.

  After that come only the pasted pieces the agent cannot read from disk (for example earlier agents' INTERFACE HANDOFFs). Do not paste this whole prompt, old reports or logs. Give paths instead; agents read the source themselves.

### 2. Steps, dependencies and wave closers

| Step | Who | What | Starts after | Runs sbt | Wave closer |
|---|---|---|---|---|---|
| 0 | build-engineer | decision 3 | start | yes (compile only) | no |
| 1a | test-engineer (read-only) | decision 4: inventory and Spark case sheet | start (at the same time as step 0) | no | no |
| R-A, R-B, R-C | code-reviewer ×3 | decision 6: slices A–C | start | no | – |
| R-D | code-reviewer | decision 6: slice D | step 0 | no | – |
| 1b | test-engineer | decision 5: adversarial tests | steps 0 and 1a | yes | **yes, wave 1** |
| 2 | orchestrator | decision 7: triage | **barrier:** steps 1b, R-A, R-B, R-C and R-D all returned | no | – |
| 3a–3g | owners, one after another: hadoop, entity-resolution, spark, domain, storage, rag, services | decision 8 | step 2, then the previous owner | yes (targeted only) | no |
| 4 | test-engineer | decision 9 | step 3g (or the last owner that ran) | yes | **yes, wave 2** |
| 5 | code-reviewer (1 or 2 slices) | decision 10 | step 4 | no | – |
| 6 | orchestrator | decision 12: summary and evidence | step 5 | no | – |

- **What can run at once:** at most one sbt-running agent at a time. Step 1a and the four reviewers run no commands besides read-only ones, so they may overlap steps 0 and 1b. At most five agents run at once (1a plus four reviewers, during step 0).
- **Why the owners run in this order:**
  - hadoop, entity-resolution and spark don't depend on the others.
  - domain goes right before storage, rag and services, which use its QueryAudit v2.
  - rag goes before services, whose golden examples follow rag's output.
- An owner with nothing to do in step 3 is skipped.
- **Owners run targeted tests only.** The full check of the repair wave is test-engineer's in step 4, because only test-engineer may remove the expected-failure marks in `tests/**`.
- **Steps 4b and 4c:** if step 4's full `sbt check` fails, the orchestrator copies the failing test names and their `[error]` log lines into the work order of the owner of the failing code, and lists them in its summary.
  - **Trigger:** any failed or errored test, or any compile or format error, in the step-4 check, except a test whose `.fail` mark test-engineer kept on purpose.
  - **4b:** that owner gets one regression round (targeted tests). A failure in test-engineer's own new tests goes back to test-engineer within step 4.
  - **4c:** test-engineer runs the full check once more.
  - If step 4c still fails, the phase result is `GATE_NOT_MET` (decision 11).

### 3. Step 0: build (build-engineer)

- One change: `contractTests` also gets the test fixtures of rag, storage and services. In `.dependsOn(coreScala3, hadoop, entityResolution, storage, rag, services)`, make storage, rag and services each `% "compile->compile;test->test"`. Nothing else changes; no library is added.
- Run `sbt -batch "contractTests/Test/compile"` and observe the output. Do not run `sbt check` (phase exception). Report the exact `git diff build.sbt`.
- Check for test-resource paths that would now exist twice on the contractTests test classpath (the same relative path under two `src/test/resources` trees). Compare these Glob listings:
  - `tests/contracts/src/test/resources/**`
  - `modules/rag/src/test/resources/**`
  - `modules/storage/src/test/resources/**`
  - `modules/services/src/test/resources/**`

  The bundle file list showed none.
  - If one exists, change nothing beyond the dependsOn line, list the paths, and finish with `STATUS: BLOCKED-ON` naming the owners of those paths. The orchestrator records it, and test-engineer works without the new fixtures (decision 5, fallback).

### 4. Step 1a: inventory and Spark case sheet (test-engineer, read-only, no sbt)

Write only your report file, `test-inventory.md` (decision 12). Read, Grep and Glob only.

1. **Gap list:** for each of the 21 spec categories, list the existing tests that cover it (suite names; module and contract suites), with a mark: covered, partly covered or not covered.
   - Hadoop and ER already have adversarial suites under `tests/contracts`.
   - For each partly or not covered category, name the tests you will add in step 1b, as a numbered plan in this priority order:
     - runtime and cross-module categories: LLM malformed JSON and fabricated IDs, unsupported claims, prompt injection, Neptune timeout, DynamoDB conditional conflict, S3 Vectors throttling, replay and idempotency, API auth, malformed bodies and timeouts, logging redaction, configuration omissions, duplicate vector keys and stale model versions, missing metadata;
     - then cross-record contract tests (deferred from Phase 3: IDs, windows and provenance that must agree across modules);
     - then pipeline gaps marked "not covered".
2. **Production-behavior inventory**, used for the gate item "every production behavior has a relevant test":
   - one row per public entry point: each MapReduce job, ER run step, Spark job stage, storage port and adapter, rag stage, HTTP route, config section;
   - for each, the test suite(s) that exercise it, or `NONE`;
   - keep it compact: one line per row.
3. **Spark case sheet** (contractTests cannot reach `sparkAnalytics`, Scala 2.13), a compact machine-readable table in your report.
   - **Simple cases** (expected values computed by hand, each with a one-line derivation): an empty graph, a single researcher, a disconnected pair, a star, a path, a 4-clique, and two triangles joined by one bridge edge.
     - Give the input papers or edges with years, and the window.
     - Expected per researcher: P, C, T, A, M, W and coreness.
     - Expected components and sizes, and shortest-path hops for named pairs.
     - Cross-community degree, communities connected and participation coefficient, under a community assignment you state.
   - **Identity and team cases:** a self-pair created by identity resolution; the same pair on several papers; teams of 1, 2, 3 and 30 authors (pairs n(n−1)/2); an edge whose endpoint is unresolved or a CONFLICT researcher (Phase 6 F3).
   - **Community cases, kept separate:** expected outcomes only as "same community / different community", and only where the structure makes the answer unique (for example two 5-cliques joined by one edge). Label propagation with a self vote and ID-order ties may differ elsewhere; leave those out.
   - Use no computer reference implementation; the values are hand-derived so they stay independent of the code under test.

### 5. Step 1b: adversarial tests (test-engineer; the spec work order is quoted below)

- **Write territory:** `tests/**` only.
  - In this step, add new files only; do not edit tracked test files. That way reviewer D's baseline (decision 6) stays valid.
  - The module test trees belong to their module owners.
- Follow your step-1a plan in its priority order. Add pipeline tests only for gaps marked "not covered". Don't duplicate behavior that existing tests already cover.
- **Fixtures:**
  - After step 0 you may use the rag, storage and services test fixtures (for example `ServiceTestKit.harness`, `RuntimeServiceFixtures`, `FlakyStores`, `ServiceStoreFixtures`, `ConditionalDynamoStub`, `StubSqsClient`, `ManualClock`). Their INTERFACE HANDOFF sections are in `docs/agent-runs/phase-10/agent-report-rag.md`, `-storage.md` and `-service.md`, and `repair-01..03.md` (read-only).
  - For the Neptune timeout and S3 Vectors throttling categories, write your own small stub SDK clients in `tests/**`.
  - Fallback if step 0 is BLOCKED-ON: write the fakes you need yourself in `tests/**`, and list the tests you could not write.
- **Deterministic only.** No timing races, no real sleeps for ordering; use `ManualClock`, `GatedModel` and similar. Every test must fail when the behavior it covers is broken; a non-null check is not a test.
- **A test that exposes a real defect:**
  - Keep it, mark it MUnit `.fail`, and give it an ID in its name: `test("T-07 <what breaks>".fail) { … }`, with IDs T-01, T-02, … in the order found.
  - Next to it, add a one-line comment naming the owning agent.
  - The test must assert the specific wrong value, not just "something fails". Report, per T-ID, the failure message you observed, so a `.fail` test that fails for an unrelated reason (for example a setup error) is visible.
  - In the report, per T-ID: proposed severity (critical, major, minor), owning agent, file:line of the production code, the failing input, the observed result and the expected result.
- **Close (wave-1 closer):**
  1. Run your suites in one targeted invocation.
  2. Run the full `sbt check`.
  3. Report exact counts from the `sbt check` output: module and contract tests run, failed and skipped; and the `.fail` marks by ID.
  4. If `sbt check` fails because of your own tests, fix them and rerun once. If it fails in production code you did not touch, report FAILED with the failing tests; the orchestrator records it, and step 2 goes on.
- **Report:** `test-report.md`, with:
  - the coverage table by requirement (the 21 categories and the spec's "Required test inventory" list → suites);
  - the step-1a behavior inventory, updated with your new tests;
  - failures by owning agent;
  - residual risks.

### 6. Steps R-A to R-D: full-repository review (code-reviewer, four read-only slices)

One reviewer cannot hold the whole repository (about 44.6k lines of main code). Four instances review in parallel; each reviews its slice in the 12 spec areas in order. The slices are balanced by risk and size (main-code line counts from my bundle):

| Slice | Files | Main lines | Report | IDs |
|---|---|---|---|---|
| A | `modules/hadoop/**`, `modules/entity-resolution/**`, `datasets/**` | about 13.8k | review-report-a.md | A-01, … |
| B | `modules/spark/**`, `modules/storage/**` | about 13.8k | review-report-b.md | B-01, … |
| C | `modules/rag/**`, `prompts/**`, `modules/services/**` | about 12.2k | review-report-c.md | C-01, … |
| D | `modules/core/**`, plus `tests/**`, `build.sbt`, `project/**`, `docker/**`, `.gitignore`, `README.md`, `docs/**` (not `docs/agent-runs/**`); for areas 3, 4, 11 and 12 also `CLAUDE.md`, `docs/agents.md`, `.claude/**`, `.mcp.json`; and the interface seams below | about 4.7k plus tests | review-report-d.md | D-01, … |

- **Baselines:**
  - Production code does not change during these reviews (steps 0–1b change only `build.sbt` and new files under `tests/**`).
  - R-D starts after step 0. The orchestrator pastes `git diff build.sbt` and the output of `git ls-files tests`, taken before step 1b. R-D reviews those tracked test files and the new `build.sbt`. test-engineer's new test files are judged in step 5, not here.
- **Read order, risk first.** Within your slice, read the highest-risk code first and in full:
  - A: blocking (`BlockingJob`, `FallbackJobs`), pair expansion (`PairExpansion`, `PairJobs`), the DBLP scanner and parser, `ErRun`, `Decide`, `Clustering`, `CannotLinks`;
  - B: Spark `Pipeline`, `GraphBuild`, `Algorithms`, `Paths`, `JsonlExport`, `Output`; storage `RuntimeReaders`, `JobStore`, `IdempotencyStore`, `AwsSupport`, `NeptuneAdapters`, `DynamoDbKeyValueStore`, `VectorIngestPipeline`, `RetryingStores`;
  - C: `QueryWorkflow`, `ClaimVerification`, `Schemas`, `Synthesis`, `Stages`, `EvidenceBuilder`, `ResearcherResolver`, `Llm`, `RuntimeService`; services `QueryService`, `Worker`, `HttpAdapter`, `Auth`, `Support`, `ReadServices`;
  - D: `Validator`, `StableIds`, `ConfigLoader`, `Records`, `Contract`.

  Then the rest of the slice as time allows.
- **Interface seams (slice D).** Read both sides' public signatures and the code paths that cross. Flag only what is wrong at the seam:
  - core ↔ rag and storage (QueryAudit, AnswerResponse, EvidenceBundle);
  - storage ↔ rag (RuntimeStores, readers, graph templates);
  - rag ↔ services (RuntimeService, WorkflowResult, prompt versions in golden examples);
  - hadoop output ↔ spark input (file formats, provenance);
  - entity-resolution output ↔ spark identity (CONFLICT, unresolved mentions);
  - `build.sbt` ↔ test visibility (which fixtures reach which tests).
- **Coverage (required):** list each file as `read in full`, `read in part` (which parts) or `not read`. A file that was only searched or listed is `not read`. The verdict covers only what was read.
- **Severity:**
  - critical = wrong results, data loss, or a security exposure reachable in normal use;
  - major = a spec requirement is broken, or a defect that will hit Phases 12–16;
  - minor = an edge case or cleanup;
  - advisory = a note.
- Verify every candidate against the files. Each finding gives: severity, file:line, a concrete failure input, the impact, the owning agent from `docs/agents.md`, and (if you can tell) the root cause shared with another of your findings.
- **Known items: do not re-report** these, unless you find one is worse than recorded (then report it with its ID). To check whether something is known, grep `docs/agent-runs/` for the ID.
  - every item in decision 8;
  - Phase 5 M1–M4, m1, F8/F9 (expected failures);
  - Phase 6 F2, F5, F7, F8, F10;
  - Phase 10 S-5, U-7, U-11, U-12, R-1 (kept; documented in Phase 17);
  - A9 (packaging, Phase 13);
  - the accepted risks in `docs/agent-runs/amendment-02/human-gate.md`.
- End with `VERDICT: APPROVE | REQUEST_CHANGES` for your slice.

### 7. Step 2: triage (orchestrator, no agent)

- **Barrier:** start only when steps 1b and R-A to R-D have all returned (or FAILED; record which).
- **Merge** all T-IDs and A/B/C/D findings into one table: ID, severity, owner, file:line, status.
- **Duplicates:** put two IDs on one row only when they cite the same file:line, or a reporter says they are the same. Otherwise keep separate rows and give both to the owner, who decides whether they share a root cause. When a finding is a planned item of decision 8, attach it to that item.
- **Classify:**
  - **Repair now:** every critical and major finding whose owner runs in step 3.
  - **Record only:** minor and advisory findings, unless decision 8 lists the item. Also record findings owned by dataset-engineer, infra-engineer, documentation-engineer or factory-engineer (Phase 12 or 17), and any constitutional change.
  - **Stop for me** only for a critical finding whose owner does not run in step 3.
- **Each step-3 work order** (compact contract, decision 1) holds:
  - that owner's planned items;
  - its repair-now IDs with file:line;
  - the T-IDs and contract suites that target it;
  - the earlier owners' INTERFACE HANDOFFs;
  - its report path.

### 8. Step 3: repairs and planned items, one owner at a time (targeted tests only)

**Rules for every owner:**
- **One repair round = this owner's one pass** over all its IDs and planned items.
  - Where one fix covers several IDs, say so and give the proof for each ID separately.
  - Where you find two IDs share a root cause, fix the cause once and report both.
- **Priorities and evidence:**
  - **P1** must be done.
  - **P2** is done only if it is small; otherwise it goes on "proposed later" with one line on why.
  - Do newly found critical and major defects first, then P1 planned items, then P2.
- Each fix adds a module test that fails without the fix; state how you checked that it fails without it.
- **T-ID flips:** when a T-ID targets you, include its contract suite in your targeted invocation (`"contractTests/testOnly <suite>"`). After your fix, MUnit reports that the `.fail` test was expected to fail but passed. That is the proof of your fix: list it under "expected-failure flips". Every other test in those suites must pass. Do not edit `tests/**`.
- **Read the source of each old finding** before fixing it (path given in your work order; read-only).
- **Report files:** repair-01-hadoop.md, repair-02-er.md, repair-03-spark.md, repair-04-domain.md, repair-05-storage.md, repair-06-rag.md, repair-07-services.md (only owners that ran).
- **If an earlier owner FAILED:** the orchestrator tells later owners exactly which of its P1 items are missing. Items that depend on them are skipped and reported as "not done: depends on <ID>, which is open":
  - storage's QueryAudit item and rag's QueryAudit population need domain;
  - services' explain item needs domain and rag;
  - the Spark suite needs the step-1a case sheet.

**3a. hadoop-engineer** (`modules/hadoop/**`)
- P1 **Phase 4 finding 2** (`docs/agent-runs/phase-04/review-report.md`, Q1 and the findings table): reused outputs are not checked against the code version, so a rerun after a code change can reuse old results.
  - Your part of the review's routing: on reuse, read the earlier run's `_job.json`, and refuse to reuse it unless its code version (`scholarchain.code.version`) and its inputs match the current run.
  - The other parts are not in this phase: a build-embedded identity (build-engineer) and scripts that pass the git commit (infra-engineer, Phase 13).
  - **Expected flip:** advisory 13 of that review says `tests/contracts/.../JobLevelRerunSuite.scala` (about lines 95–117) passes only while the gap exists. After your fix it fails. That is expected: run it in your targeted invocation and list it under "expected-failure flips" with its failure message. test-engineer inverts it in step 4. Do not edit `tests/**`.
- P1 **Phase 5 m5, run-1 review** (`docs/agent-runs/phase-05/review-report.md`, the m5 row that names `scholarchain-hadoop-defaults.xml:263-269`):
  - The default blocking key families are only `dblp-base-name`, so the `initials-surname` family that er-v1 reasons about produces no candidates unless the run command overrides it. Both real runs passed `-D scholarchain.blocking.keyFamilies=dblp-base-name,initials-surname` by hand.
  - Make that the default in `modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml`, with a test.
  - The run-2 review's other m5 (ER input re-hashing) was already verified fixed; it is not part of this item.

**3b. entity-resolution-engineer** (`modules/entity-resolution/**`)
- No planned items. It runs only if wave 1 gives it critical or major findings; otherwise step 3b is skipped.

**3c. spark-engineer** (`modules/spark/**`)
- P1 **Known-graph adversarial suite:** encode the step-1a case sheet (`test-inventory.md` in the scratchpad) in a new suite under `modules/spark/src/test`, together with Phase 6 F3.
  - Where the module's result differs from the sheet: if the sheet is right, it is a defect. Fix it if critical or major; otherwise mark the case `.fail` with ID `SP-nn` and report it.
  - If the sheet is wrong, say why; step 5 rules on it.
- P1 **Phase 6 F6** (`Output.scala`, about lines 269–296):
  - The reuse check lists only data files. `listData` skips `_`-prefixed paths, so `_run_report.json` and `_load_manifests/*.json` are neither hashed nor verified.
  - Hash them into the run manifest, and refuse reuse when they differ. Also refuse reuse when the recorded `--code-version` differs from the current one.
  - The code version stays a run argument. Supplying the git commit is the scripts' job in Phase 13; record it as "proposed later".
- P1 **k-core cap 50** in `modules/spark/conf/phase06.json` (your territory; this changes a setting only).
- P1 **Phase 6 F9, part 1** (`GraphAnalyticsJob.scala`, about lines 56–62): the EMR entry point sets `spark.sql.autoBroadcastJoinThreshold=-1`, as the local runner already does. Add a test.
- P1 **Phase 6 F9, part 2:** `Pipeline` uses `java.io.File` throughout (output root, listing, manifests, checkpoint directory), so it cannot run against S3.
  - Move these to the Hadoop FileSystem API, so `s3://` paths work on EMR. Test with `file://` paths only.
  - Do this item **last** in your pass; it is the largest.
  - If you cannot finish it with your targeted tests green, put your own F9-part-2 edits back (with Edit), so the module compiles and passes as before. Then report the item open, with what remains. Never leave a half-done refactor.
- Sources: `docs/agent-runs/phase-06/review-report.md` (F6, F9) and `human-gate.md`.

**3d. domain-engineer** (`modules/core/**`)
- P1 **U-6:** the `service.jobQueueUrl` error names the field and the rule, never the value (`ConfigLoader.scala`, about line 829).
- P1 **QueryAudit v2:** a new schema version of the audit record for the spec's explain list.
  - Fields: query-template IDs used, indexes queried, metadata filter keys, per-task model-call counters (logical calls, HTTP attempts, retried attempts, invalid outputs, truncated outputs, dropped bound numbers, fallbacks), and cache hits.
  - Add a field only if rag can fill it from data it already has; list any spec item you leave out, and why.
  - **Additive, within the existing `QueryAudit` type:**
    - new fields are optional with defaults at the end;
    - it writes schema version 2, and v1 records still decode;
    - `Contract.encode` and `Contract.decode[QueryAudit]` handle both.
    - Existing constructors and callers in storage, rag and services must still compile unchanged. storage's `QueryAuditWriter` (`RuntimeReaders.scala`, about lines 437–492) encodes and decodes only through `Contract`, so it needs no change.
  - A frozen v2 wire example; tests in both builds.
- P2 **U-8:** `service.apiKeyEnvVar` and `service.readOnlyApiKeyEnvVar` must differ from `llm.apiKeyEnvVar` and from the embedding key variable.
- P2 **Phase 9 domain "proposed later" item 1** (`docs/agent-runs/phase-09/agent-report-domain.md`): a validator rule that a `semantic_context` EvidenceItem carries no `metric`.
- **Not in this phase:** item 2 of that list (a `partially_supported` claim must carry an uncertainty note). rag's current answers would break that rule, so it is a contract change for rag and domain together. Record it as "proposed later".

**3e. storage-engineer** (`modules/storage/**`)
- P1 **QueryAudit v2 round trip:** a storage test that a v2 record (from domain's frozen example) is appended and read back unchanged through `QueryAuditWriter`, and a stored v1 record still reads. Change `QueryAuditWriter` itself only if that test fails.
- P2 **Phase 7 m3** (`ingest/VectorIngestPipeline.scala`, about lines 419–443; `docs/agent-runs/phase-07/review-report.md`): two writers racing on one vector key with different text leave the vector and the registry out of sync, recorded as `registry-conflict` but never healed.
- P2 **r1 and r2:** error-mapping test gaps, from `docs/agent-runs/phase-07/review-report-02.md`. If you cannot find them there, record "not found" and move on.
- P2 **The typed GraphRow decoder** (Phase 9 "proposed later").

**3f. rag-engineer** (`modules/rag/**`, `prompts/**`). The live-run items come from `modules/rag/target/phase-10-real/attempt-06-anthropic/` (read-only).

- P1 **L-1, bound numbers (decided: diagnose plus both fixes).**
  - **Diagnose first, with tests.** Write format-variant tests for both `AnswerClaimsSchema.appearsInText` and `ClaimVerification` (`NumberRe`, `literalMatches`). Cover: `1,234` and `1 234` for 1234; `95%` for 0.95; `4.6`, `4.57`, `4.5714` and `5` for 4.5714; `one`, `a single`, `first`; `#1`; `2015-2025` and `2015–2025`; and a value that occurs only as digits inside a stable ID. Put the current pass/fail table in the report before changing anything.
  - **Accept the same safe formats in both places.** The validator and the verifier must agree. At least: integers with standard 3-digit comma groups (`1,234` is 1234). A value must not match digits inside stable IDs (`appearsInText` today does match them; this is L-4). Number words and percentages are not accepted; the prompt forbids them.
  - **New prompt set v3:** `prompts/runtime/answer-synthesis-v3.md` and `prompts/schemas/answer-claims-v3.schema.json`; the planner stays `query-planner-v2`. All v1 and v2 files stay byte-for-byte unchanged and pinned.
    - In v3, a bound number whose value does not appear in the claim text is dropped from `numbers` and counted, not a rejection of the whole answer. The verifier still checks every number in the text against the evidence.
    - The v3 prompt states the number format with one short example: digits exactly as in FACTS, no number words, no percent signs.
    - `Prompts.Current` becomes v3; `--prompts v2` replays Phase 10.
    - No new config file unless a limit changes; if one changes, add `-v3.json` copies and keep the v2 files unchanged.
  - **Retry hint:** the structured-retry message adds a fixed hint about the number format (fixed text, never model text).
  - **Failure classes:** every dropped bound number and every remaining number error is counted by a fixed class computed in code (for example `words`, `separator`, `percent`, `too-few-decimals`, `absent`), per attempt and in `modelCalls`. Never store the model text.
- P1 **L-2, planted wrong year (decided: test and verifier).**
  - **The planted test:**
    - The planted year must not be any year of the cited items (window, edge or work years). Start from year − 9 and step down until it is outside that set.
    - Replace the matched year itself (with word boundaries), not the first occurrence of its digits anywhere (today `replaceFirst(y, …)`).
    - Add one record per planted case to the run report: question ID, claim ID, kind, original literal, planted literal, resulting status.
  - **The verifier checks years by their role.**
    - In a claim that cites coauthor edges or works, a year in "from X to Y", "since X", "until Y" or "in X" must equal a first or last year of a cited edge, or the year of a cited work.
    - A window year counts only when the text states the window as a range (`2015-2025`, `2015–2025`, or "between 2015 and 2025") equal to the window of a cited item, or to the context window of a cited match-decision item.
  - **Tests:**
    - q08 claim-2 with the planted text "active from 2015 to 2025" (cited edge: first year 2024, last year 2025, window 2015–2025) must not be verified.
    - The unchanged q08 claim-2 must stay verified.
    - The template claims ("In 2015-2025, … from 2022 to 2025") must stay verified.
    - The evidence is in `q-08.json`; rebuild the item offline, no API.
- P1 **L-3:** q08 claim-1 was removed as conflicting because it named the question's window years while citing only the identity record, which has no window. The role rule above must accept the window range there; add a test.
- P1 **U-10:** when an attempt ends `truncated at max_tokens`, the retry asks for fewer claims (at most 8, fixed text) instead of resending the same request. Test with a stub model.
- P1 **U-3 (decided: hash the prompt versions):** the query fingerprint includes the prompt versions of the prompt set; `QueryWorkflow.CodeVersion` becomes `rag-runtime/3`. Test: the same request under v2 and v3 gives two different query run IDs. No config key.
- P1 **QueryAudit v2 population:** fill every v2 field domain added.
- P1 **Fake benchmark:** after your targeted tests, run once, with the long-run pattern:
  - `sbt -batch -J-Xmx12g "rag/runMain org.scholarchain.rag.runtime.BenchmarkRunner --config modules/rag/src/test/resources/config/rag-runtime-local-v2.json --export modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b --neptune-csv modules/storage/target/phase-07-real/attempt-01/neptune --questions modules/rag/src/test/resources/benchmark/benchmark-v1.json --out modules/rag/target/phase-11-real/attempt-01-fake --llm fake"`
  - Targets: graph values 110/110, paths 4/4, expected abstentions 3/3 with none unexpected, every planted error caught, injection passes. Report the claim status counts next to Phase 10's fake run (173 / 2 / 0 / 0). Report any miss as a finding; don't tune.
  - If the run fails to start, report it and carry on; don't retry variants.
- P2 **U-5:** `RuntimeService.local` must close the model and the embedder on its Left path.
- P2 **U-13:** the two doc comments.
- P2 **n-1..n-4** (`docs/agent-runs/phase-09/review-report-02.md`, all owned by rag). In this order:
  - n-3: tests for a failed ResearcherById read and for more than 250 researchers;
  - n-2: key-shaped tokens that are not cited keys hide a number;
  - n-1: the summary sanitizer misses single, all-caps and spelled-out names and numbers;
  - n-4: a redundant note.
- **Expected side effect:** the services golden examples pin the v2 prompt hashes, the fake output and the query ID. They fail until step 3g regenerates them. List the failing golden test names in your handoff; don't count them as your failures, and don't touch `modules/services/**`.
- **At the end, print for me** the exact one-line live command for attempt-07 (decision 12).

**3g. service-engineer** (`modules/services/**`)
- P1 **Golden examples:** regenerate them after the rag changes with `sbt -batch "services/Test/runMain org.scholarchain.services.GoldenExampleWriter /private/tmp/claude-501/<scratchpad>/golden"`. Freeze them with the Write tool, check with `shasum` that the frozen files equal the generated ones, and list in the report what changed and why. (Use the full absolute scratchpad path, not `<scratchpad>`.) If a frozen file's hash still differs after one rewrite, report that file open; do not hand-edit it to match.
- P1 **Explain API on QueryAudit v2:** show the new fields; keep `scoreComponents: []` and its note. Update the frozen `explain.json`.
- P1 **S-4:** log lines never carry downstream text (store or SDK messages). Log a fixed cause code and the exception class name only. Test: a store failure whose message holds a host name and a table name never shows them in any log line.
- P2 **S-3:** if the final `succeed` write returns ConditionFailed or Left, re-read the job and retry `succeed` once on the fresh version when it is still running under this request's lease; otherwise return 502.
- P2 **S-6, S-7, S-8, S-9, S-10** (from `docs/agent-runs/phase-10/review-report-02.md`).
- **R-1 is not changed:** "after a 502, use a new key" goes in the Phase 17 API docs.

### 9. Step 4: rerun and close (test-engineer)

1. For each T-ID whose owner reports a flip, remove its `.fail` mark. If hadoop-engineer fixed Phase 4 finding 2, invert `JobLevelRerunSuite` (about lines 95–117): it must now assert that the reuse is refused (Phase 4 advisory 13).
2. Run, in one targeted invocation:
   - the unmarked tests;
   - the adversarial suites that cover the repaired areas;
   - the suites the work order names.
3. A test that now passes means its finding is fixed. A test that fails gets its mark back: its finding is still open (record the output).
4. Keep the marks of findings that were not repaired.
5. If a decided behavior change (decision 8) breaks one of your own new tests, update that test to the decided behavior and say so. Any other break is a finding.
6. Run the full `sbt check` as the closer of the repair wave.
7. Report:
   - the exact module and contract counts (run, failed, skipped), and the skipped counts compared with the start;
   - the `.fail` marks left, by ID;
   - for each T-ID: fixed, still open, or not repaired.

**Report:** `test-report-02.md`. Steps 4b and 4c are in decision 2.

### 10. Step 5: re-check (code-reviewer, read-only)

- Re-check:
  - the repaired findings;
  - **every P1 item of decision 8** (this new code has had no review yet);
  - the `SP-nn` cases and any case-sheet disputes from step 3c;
  - test-engineer's new test files: do they fail when the behavior is broken?
- The orchestrator pastes `git status --short --untracked-files=all` and `git diff --stat` into the work order.
- If there are more than 20 items, split them into two slices that run at the same time.
- Rule on each item: RESOLVED, NOT RESOLVED or PARTIAL, with file:line.
- Nothing is repaired in this step.
  - NOT RESOLVED and PARTIAL items stay open: no second round.
  - New critical and major findings are listed for me; new minor findings are recorded.
- **Report:** `review-report-02.md` (and `review-report-03.md` for a second slice).

### 11. Stops and failures (decided in advance)

**A failed step is not a failed phase.** The run goes on, and the final summary lists each item:
- a finding still open after its one round;
- a P2 item left undone;
- an agent's FAILED status, whatever depends on it being skipped as decision 8 says;
- step 0 BLOCKED-ON (test-engineer uses the fallback in decision 5);
- BLOCKED-ON factory-engineer for an unowned path;
- a second missing test-evidence block;
- a reviewer slice that FAILED (its slice is marked "not reviewed").

**Stop for me** (`STATUS: BLOCKED-ON human`, one question, your recommended answer; reports saved first) only when:
- a command needs approval and the current step cannot go on without it;
- a critical finding's owner does not run in step 3;
- a constitutional change seems needed for the phase to finish.

**Step 4c fails:** do steps 5 and 6 anyway, with phase result `GATE_NOT_MET`, so that I have all the evidence in the morning.

No agent is started twice for the same step, except the one send-back for missing test evidence, step 4b, and the fix-and-rerun of test-engineer's own tests.

### 12. Evidence, summary and the live re-run

1. **Scratchpad:**
   - The orchestrator gives every agent the absolute path of its own session scratchpad (`/private/tmp/claude-501/…/scratchpad`) and the report file name, inside a `phase11-reports/` folder.
   - Each agent creates its report there with the Write tool early, updates it as it works (Edit), and returns the same text.
   - Reports are on disk from the moment they are written, so they survive a lost session. The folder lives until I reboot.
2. **Final summary.** When all steps are done (or before a stop for me), the orchestrator prints the final summary:
   - the phase-result line;
   - the finding table (ID, severity, owner, status: fixed, open, recorded or not reviewed);
   - each decision-8 item with its status;
   - the closer's exact counts, skipped counts and `.fail` marks left;
   - the test-evidence check results;
   - every approval prompt or BLOCKED-ON seen;
   - the attempt-07 command from rag.

   Then it writes the summary to `phase11-reports/orchestrator-summary.md`. (The 2026-11-16 probe showed that main-session and subagent Write and Edit into the scratchpad ask for no approval.)
3. **Hashes and copy commands:** the orchestrator runs `shasum -a 256` on each file in `phase11-reports/` and prints, first, the line `mkdir -p docs/agent-runs/phase-11` (the folder may not exist yet, and `cp` fails without it), then one `cp -n <file> docs/agent-runs/phase-11/<name>` command per file, each on one line with the full absolute path, with its sha256. I run these commands myself; the orchestrator does not. Do not commit.
4. **The summary keeps these claims separate, never merged into one "done":**
   - `sbt check` green, and which tests are expected failures (by ID);
   - each P1 item done, or not;
   - each critical and major finding resolved per step 5, or not;
   - `RequiresAws` tests compiled, not run;
   - the gate item "every production behavior has a relevant test": rows of the behavior inventory with `NONE`;
   - my approval: pending.
5. **File names:**
   - test-inventory.md, agent-report-build.md, test-report.md;
   - review-report-a.md … review-report-d.md;
   - repair-01-hadoop.md … repair-07-services.md (only owners that ran), repair-08-<owner>.md, repair-09-<owner>.md … (step 4b, one file per owner, if any);
   - test-report-02.md, review-report-02.md (and -03.md);
   - orchestrator-summary.md.
6. **Attempt-07 (me, after the gate):** rag prints the exact one-line command, in the same form as the Phase 10 command:
   - config `rag-runtime-anthropic-v2.json` (or the `-v3.json` copy if a limit changed);
   - `--llm anthropic`;
   - `--out modules/rag/target/phase-11-real/attempt-07-anthropic`;
   - the default prompt set (v3).

### 13. Expected inputs (from earlier reports, not re-counted)

- Phase 7 export (read-only): `modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b/`.
- Neptune CSV (read-only): `modules/storage/target/phase-07-real/attempt-01/neptune/`.
- Phase 10 live run (read-only): `modules/rag/target/phase-10-real/attempt-06-anthropic/`.
- In Phase 10, `sbt check` took about 13–17 minutes; the local fake benchmark took about 97 s plus about 115 s of loading with `-J-Xmx12g`.

Report what you actually find. If a count differs, report it; don't force it.

## Spec work orders, Phase 11 (verbatim)

Use this work order for testing:

```text
Use the test-engineer agent on the complete ScholarChain repository produced through Phase 10.

Add adversarial tests without modifying production code.

Required categories:
- Malformed and truncated DBLP XML.
- Unicode normalization and confusable characters.
- Duplicate and conflicting DOI records.
- Common author names and reordered author lists.
- Very large blocking groups.
- Coauthor edges with unresolved or conflicting researcher endpoints.
- Empty and disconnected graphs.
- Distinct-degree, component, shortest-path, bridge, and k-core known-graph cases.
- Large-team pair expansion and common-name researcher-resolution cases.
- Missing topic, institution, abstract, or researcher-profile metadata.
- Duplicate vector keys and stale model versions.
- Prompt injection in title, abstract, and full-text chunks.
- LLM malformed JSON and fabricated identifiers.
- Unsupported answer claims.
- Neptune timeout.
- DynamoDB conditional-write conflict.
- S3 Vectors throttling or transient failure.
- Replayed workflow and idempotency.
- API authentication, authorization, malformed bodies, and timeouts.
- Logging redaction.
- Configuration omissions.

Run all tests that do not require live AWS. Run bounded AWS integration tests only under the approved profile.
Leave any production defect exposed by a failing test. Report it to the owning agent instead of changing production code.
Report exact test counts, failures, coverage by requirement, and residual risks.
```

Use this work order for review:

```text
Use the code-reviewer agent to perform an adversarial read-only review of the complete repository and the full current diff.

Review in this order:
1. Correctness and data loss.
2. Contract and schema drift.
3. Artifact ownership violations.
4. Security, secrets, IAM assumptions, and injection risks.
5. Hadoop and Spark distribution mistakes.
6. Entity-resolution over-merging and unreported uncertainty.
7. Storage idempotency and provenance.
8. RAG grounding and unsupported claims.
9. API behavior and failure handling.
10. Tests that would pass even if the behavior were broken.
11. Documentation claims not supported by implementation.
12. Cost and cleanup risks.

Verify every candidate finding against the actual files before reporting it.
For each finding provide severity, file and line, concrete failure input, expected impact, and owning agent.
Return APPROVE or REQUEST_CHANGES. Do not edit any file.
```

Repair loop:

```text
1. Orchestrator groups findings by owner.
2. Each owning agent receives only its findings plus necessary context.
3. Agent fixes its owned artifacts and runs its gate.
4. Test engineer reruns relevant adversarial tests.
5. Code reviewer rereads the complete diff.
6. Repeat until APPROVE or human escalation.
```

Gate:

- No critical or major verified finding remains.
- Every production behavior has a relevant test.
- All tests pass under their declared profiles.
- No reviewer wrote production code.
- Repair records identify owners and rerun evidence.

Phase 11 notes that apply to the verbatim text above:
- **"Add adversarial tests without modifying production code":** test-engineer writes only `tests/**` (decisions 4 and 5). Spark known-graph cases are designed by test-engineer (the step-1a case sheet, with hand-derived values) and encoded by spark-engineer inside `modules/spark/src/test`, because contractTests cannot reach the Scala 2.13 Spark module. This is a recorded deviation: the Spark tests are written by the module owner.
- **"Leave any production defect exposed by a failing test":** the test stays and is marked MUnit `.fail` with its T-ID, so `sbt check` stays green while the defect is open (decision 5). test-engineer removes the mark after the owner's repair (decision 9).
- **"Run bounded AWS integration tests only under the approved profile":** there is no approved profile in this phase (decision 1). Every live test is `RequiresAws`, compiled and not run.
- **"Full current diff":** empty at the start (the tree is clean). Wave 1 reviews the repository; step 5 reviews this phase's changes: the repairs, the P1 items and the new tests (decisions 6 and 10).
- **Repair loop step 3, "runs its gate":** targeted tests per owner; the wave's full `sbt check` is run by test-engineer in step 4 (decision 2).
- **Repair loop step 5, "rereads the complete diff":** step 5 rereads the repaired findings, every P1 item and the new tests, with the diff list provided.
- **Repair loop step 6, "Repeat until APPROVE or human escalation":** one repair round per owner pass (decision 8). Anything still open is escalated to me in the final summary (decision 11), never treated as approved.
- **Gate, "No critical or major verified finding remains":** decided at my gate from the step-5 rulings and the open list.
- **Gate, "Every production behavior has a relevant test":** measured as the behavior inventory (decision 4): one row per public entry point, with its tests or `NONE`. Every `NONE` row is listed in the summary.
- **Gate, "All tests pass under their declared profiles":** `sbt check` green, with the expected failures listed by ID; `RequiresAws` compiled, not run.
- **Gate, "No reviewer wrote production code":** code-reviewer has only Read, Grep and Glob.
- **Gate, "Repair records identify owners and rerun evidence":** `repair-0N-<owner>.md` with TEST EVIDENCE blocks, and test-report-02.md.
