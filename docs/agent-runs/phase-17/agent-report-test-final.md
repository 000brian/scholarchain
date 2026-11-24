# Agent report: test-engineer, Phase 17 session 3, step E9 (final test report)

The final gate items "sbt clean check succeeds from a clean checkout" and "test-engineer final test run" are both met. The runs were the human's, in a fresh clone of the release candidate (planned deviation 5). Every count the orchestrator passed matches the logs. I found no discrepancy in any count, exit code or gate. I found two gaps in the evidence and a few oddities in the E5 log (section 7).

## 1. What was tested and who ran it

- **Release-candidate SHA:** `b4d8f1229fc145e1492e1a8cbb8a44a368d026af` (commit "phase-17 session 3 E1: allow-list rule for absence claims, P17-A-01 resolved (rag); ...").
- **Who ran it (planned deviation 5, prompt.md:320):** the final test run was the human architect's, not an agent's. It was `sbt clean check` (E3) and `bash scripts/check.sh` (E4) in a fresh clone at `/private/tmp/p17-clone-20261123T1850Z`.
  - `sbt clean` never runs in the working tree, and a passing `sbt check` is never repeated without a source change. So test-engineer's part is to report those runs' exact counts and the T1 inventory.
  - I ran no sbt, no script and no test in this step.
- **The clone's HEAD is the release candidate.** My evidence, read-only:
  - E4 log line 4: `HEAD b4d8f1229fc145e1492e1a8cbb8a44a368d026af; build commit b4d8f1229fc145e1492e1a8cbb8a44a368d026af; dirty false`, logged at 19:28:28Z in the same clone.
  - The clone's `.git/HEAD` and `.git/refs/heads/main` both hold that SHA, and so does `packed-refs` for `origin/main`.
  - The clone's `origin` URL is a git/SSH-form remote, which fits a clone from the GitHub remote rather than the `--no-local` fallback. I did not print the host.

## 2. E3: `sbt clean check` in the fresh clone (final gate item 1)

Log `/tmp/p17-E03.log`, 10,857 lines. Start time from `/tmp/p17-E03-start.txt`: `2026-11-23T18:53:52Z`.

| Check | Log line | Observed text |
|---|---|---|
| `clean` ran first | 8 | `[success] Total time: 0 s, completed Nov 23, 2026, 12:54:13 PM` |
| Everything compiled from nothing | 4, 30-74 | 18 `[info] compiling` lines: the build definition plus main and test classes of `core` (jvm-3 and jvm-2.13), `sparkAnalytics`, `entityResolution`, `hadoop`, `storage`, `rag`, `services`, and `tests/contracts` |
| Module tests | 10185 | `[info] Module tests: suites=150 executed=2352 passed=2352 failed=0 errors=0 skipped=5` |
| Contract tests | 10851 | `[info] Contract tests (no AWS): suites=34 executed=399 passed=399 failed=0 errors=0 skipped=0` |
| Marker written | 10852 | `[info] Updated /private/tmp/p17-clone-20261123T1850Z/.claude/.last-test-run (module_tests=2352, contract_tests=399)` |
| sbt result | 10853 | `[success] Total time: 1303 s (0:21:43.0), completed Nov 23, 2026, 1:15:55 PM` |
| Exit code | 10854 | `exit=0` |
| Marker copy echoed into the log | 10855-10857 | `timestamp=2026-11-23T19:15:55.402093Z`, `module_tests=2352`, `contract_tests=399` |

Other checks on the E3 log:
- Lines starting `[error]`: **0**.
- `Test run ... finished:` lines: **184**, which equals 150 module suites plus 34 contract suites.
- No suite reported a failure (no `finished: N failed` line with N above 0, and no `==> X` line).
- The run printed 63 `[warn]` lines, none of them a failure:
  - three non-exhaustive-match or outer-reference warnings in the `KnownGraphSuite.scala` test file (lines 43-54);
  - 12 deprecation warnings for `Policy.erV1` in `tests/contracts/**` (lines 76-124);
  - two GC-pressure notices on a 1 GB heap (lines 1941 and 2120).

**The 5 skipped module tests** are whole suites that are compiled but not run (each shows `0 failed, 1 ignored, 0 total`):

| Line | Project | Suite |
|---|---|---|
| 1928 | services | `EmbeddingWorkerEmulatorSuite` (emulator) |
| 2004 | rag | `LiveProvidersAwsSuite` (RequiresAws) |
| 2153 | rag | `runtime.LiveLlmAwsSuite` (RequiresAws) |
| 2420 | storage | `emulator.EmulatorAdaptersEmulatorSuite` (emulator) |
| 2571 | storage | `AwsAdaptersAwsSuite` (RequiresAws) |

## 3. The saved E3 marker (the authoritative test evidence)

`/tmp/p17-E3-marker.txt`, read whole:

```
timestamp=2026-11-23T19:15:55.402093Z
module_tests=2352
contract_tests=399
```

- **Timestamp later than the E3 start:** 19:15:55Z is about 22 minutes after 18:53:52Z. It also matches the sbt completion time on E3 log line 10853 (1:15:55 PM local, which is UTC-6).
- **Both counts present:** yes, and they equal E3 log lines 10852 and 10855-10857.
- **Copied before E4 rewrote the clone's marker:** the copy's file time is 13:24 local, which is 19:24Z. E4's attempt ID is `20261123T192828Z`, so E4 started after the copy. I am assuming the shell's local zone is UTC-6, as the sbt times show.
- I did not use the clone's current marker.

## 4. E4: `bash scripts/check.sh --record-dir <main checkout>/evidence/checks` in the same clone

These counts come from E4's own log and are reported separately from E3. Log `/tmp/p17-E04.log`, 11,724 lines.

| Gate | GATE line | SUMMARY line | Status, exit, seconds |
|---|---|---|---|
| constitution | 8 (`validate-agent-files.sh: 72 checks passed, 0 failed.` at line 7) | 11711 | passed, 0, 1 |
| json | 234 | 11712 | passed, 0, 2 |
| hook-syntax | 713 | 11713 | passed, 0, 62 |
| shell | 716 | 11714 | passed, 0, 7 |
| datasets | 803 | 11715 | passed, 0, 4 |
| terraform | 834 (`Success! The configuration is valid.` at line 832) | 11716 | passed, 0, 26 |
| sbt | 11600 | 11717 | passed, 0, 1344 |
| script-contracts | 11679 | 11718 | passed, 0, 7 |
| probes | 11708 | 11719 | passed, 0, 24 |

Inside the E4 sbt gate:
- **Contract tests (line 11263):** `suites=34 executed=399 passed=399 failed=0 errors=0 skipped=0`.
- **Module tests (line 11570):** `suites=150 executed=2352 passed=2352 failed=0 errors=0 skipped=5`.
- **Marker and sbt result:**
  - line 11571: `Updated ... (module_tests=2352, contract_tests=399)`;
  - line 11572: `[success] Total time: 1318 s`;
  - line 11573: `test-report suites recorded: 184`.
- **packageCheck:** `packageCheck: ok` and `[success] Total time: 11 s` (line 11599). Four assembly jars were built (spark, hadoop, entity-resolution, services).
- **Incremental check:** E4's sbt gate compiled nothing (0 `[info] compiling` lines). That fits an unchanged tree after E3.

End of the E4 log:
- line 11720: `SUMMARY: module_tests=2352 contract_tests=399`;
- line 11723: `RESULT: check.sh ok`;
- line 11724: `exit=0`.

Lines starting `FAIL`: **0**. Lines starting `[error]`: **0**.

**Probes:** 26 `PROBE:` lines, all `passed`. Eight are release-manifest probes:
- `help-release-manifest`
- `release-manifest-no-options`
- `release-manifest-unknown-option`
- `release-manifest-missing-marker`
- `release-manifest-clone-not-git`
- `release-manifest-clone-head-differs`
- `release-manifest-jar-missing`
- `release-manifest-wrote-nothing`

## 5. E5: per-project targeted runs in the clone (optional, separate)

These are supplementary and are not part of the E3 or E4 counts. Log `/tmp/p17-E05.log`, 13,040 lines. Every `E5.NN exit=` line reads `exit=0`. I checked each number below with grep.

| Run | Log line | Observed |
|---|---|---|
| core | 69 | `Passed: Total 600, Failed 0, Errors 0, Passed 600` |
| core2_13 | 116 | `Passed: Total 600, Failed 0, Errors 0, Passed 600` |
| hadoop | 367 | Total 114, Failed 0, Errors 0 |
| entityResolution | 2108 | Total 147, Failed 0, Errors 0 |
| Spark `KnownGraphSuite` and `PipelineFixtureSuite` | 2187 | Total 43, Failed 0, Errors 0 |
| rag | 2643 | Total 360, Passed 360, Ignored 2 |
| services | 2714 | Total 137, Passed 137, Ignored 1 |
| contractTests | 10202 | Total 399, Failed 0, Errors 0 |
| services `GoldenExamplesSuite` | 10213 | Total 17, Failed 0, Errors 0 |
| storage | 10811 | Total 321, Passed 321, Ignored 2 |
| validator | 10814 and 11207 | `72 checks passed, 0 failed.` |
| guard self-test | 11205 and 11598 | `TOTAL: 369 passed, 0 failed.` |
| stop-hook self-test | 11678 | `TOTAL: 69 passed, 0 failed.` |
| evaluate.py fixtures | 12009-12323 | 8 `SUMMARY fixtures` lines (er 4/4, graph 3/3, retrieval 3/3, answers 5/5, ratings-sheet 3/3, performance 3/3, factory 3/3, reproduce 6/6); 30 of 30 `RESULT fixture ... pass` lines |
| packageCheck | 12386 and 12493 | `packageCheck: ok` |

- **Release-manifest dry run (E5.40):** it reports `rc=b4d8f12...`, `module_tests=2352 contract_tests=399`, `jars_not_present=0` and `RESULT: release-manifest.sh ok`.
- **Ignored tests:** E5 shows 2 in rag, 1 in services and 2 in storage, 5 in all. That matches E3's `skipped=5`.

## 6. The T1 inventory and how E3 relates to it

The source is `docs/agent-runs/phase-17/agent-report-test-inventory-2.md`, which is `STATUS: COMPLETE`. The earlier `agent-report-test-inventory.md` is a 4-line `IN PROGRESS` stub that `-2` replaces.

T1 maps all 16 categories of the spec's "Required test inventory" (course-specification.md:3092-3107) to named tests with file:line. Every category has at least one test rated STRONG.

| # | Category | Main suite named in T1 |
|---|---|---|
| 1 | Parser | `hadoop.DblpParsingSuite`, `ScannerSuite`, contracts `XmlBoundaryAdversarialSuite` |
| 2 | Stable ID | `core.StableIdsSuite`, `PairOrderingSuite` |
| 3 | Serialization | `core.WireContractSuite`, `services.GoldenExamplesSuite` |
| 4 | Hadoop mapper and reducer | `DblpChainSuite`, `PairsAndTeamsSuite`, `BlockingDeterminismSuite`, `DblpExtractJobSuite` |
| 5 | Entity feature and threshold | `hadoop.CandidateSuite`, `er.PolicySuite`, `er.DecisionSuite` |
| 6 | Small known-graph Spark | `spark.KnownGraphSuite`, `PipelineFixtureSuite`, `AlgorithmsSuite` |
| 7 | Storage contract | `DynamoSuite`, `NeptuneCsvSuite`, `GraphQuerySuite`, `EmulatorWiringSuite`, contracts `DynamoConditionalConflictSuite` |
| 8 | Vector metadata | `VectorMetadataSuite`, `VectorKeysSuite` |
| 9 | RAG structured output | `rag.runtime.PlannerAndSchemaSuite`, contracts `LlmOutputAdversarialSuite` |
| 10 | Prompt injection | contracts `PromptInjectionCrossModuleSuite` |
| 11 | Claim verification | `ClaimVerificationSuite`, `AbsenceRuleSuite`, `VerifierMonotonicSuite`, contracts `UnsupportedClaimEndToEndSuite` |
| 12 | API | `services.QueryApiSuite`, `HttpBehaviorSuite`, contracts `ApiAdversarialSuite` |
| 13 | Idempotency | `services.IdempotencySuite`, `JobLevelRerunSuite`, `ReplayIdempotencyCrossModuleSuite` |
| 14 | Failure and retry | `ObjectStoreSuite`, `S3VectorsThrottlingStubSuite`, `CapacityAndFailureSuite`, `NeptuneTimeoutStubSuite`, `WorkerSuite`, `RunRecoverySuite`, `RagIngestJobSuite`, `CompositionSuite` |
| 15 | Configuration | `ConfigLoaderSuite`, `ConfigRefusalSuite`, contracts `ConfigOmissionSuite`, `spark.JobConfigSuite` |
| 16 | End-to-end bounded fixture | `services.QueryApiSuite` "full query-to-evidence lifecycle with fakes" (the services end-to-end fixture suite); `LocalPipelineRunnerSuite`, `PipelineSuite`, contracts `FallbackPipelineEndToEndSuite`, the Spark fixture suites |

T1 also named:
- **Two weak tests:** P17-F-15 in `JsonlExportSuite.scala:197-208` (owner spark-engineer) and P17-F-16 in `PromptInjectionCrossModuleSuite.scala:42-57` (owner test-engineer).
- **One moderate gap:** `VerifierMonotonicSuite` prints the absence-claim count but does not assert it.
- **Six expected-failure (`.fail`) tests** that document open defects: rag T-02, and services T-03, T-04, T-05 and T-06 twice.

**How E3 relates to T1:**
- **Count change:** T1 read the marker at `module_tests=2343, contract_tests=399` (2026-11-23T01:13:43Z). E3 recorded 2352 and 399. The 9 extra module tests come from the session 3 E1 rag repair (`repair-E1-rag.md:23`: `executed=2352`). The contract count did not change.
- **Ignored suites:** the 5 `skipped` module tests in E3 are the RequiresAws and emulator suites T1 described as "never run by agents" (`*AwsSuite`, `EmulatorAdaptersEmulatorSuite`), plus `EmbeddingWorkerEmulatorSuite`. They compile but do not run. No contract suite is skipped.
- **The `.fail` tests:** the six `.fail` tests run in E3 and count as passed while their defects stay open. A reader counting 399 contract passes should know that.
- **T1 suites in E3:** every suite T1 names is in one of the 184 suites E3 executed, except the 5 skipped ones above.

## 7. Discrepancies and observations (reported, not smoothed over)

1. **Counts:** none of the counts, exits, gate statuses or seconds given in the inputs differ from the logs.
2. **Clone checks missing from the E3 log.** The log does not contain the clone-check lines (`rev-parse HEAD`, `rev-parse --show-toplevel`, clone source). It starts with sbt's welcome line. I support "clone HEAD = RC SHA" from E4 log line 4 (same clone, 13 minutes later, `dirty false`) and the clone's git refs. I could not check the `show-toplevel` output against any log. It rests on the orchestrator's record.
3. **E3 log length:** 10,857 lines, against "~10,860" in the inputs. That is within the approximation.
4. **E5 log oddities (supplementary only):**
   - `E5.14`, `E5.15` and `E5.41` each appear twice (lines 10815/11208, 11206/11599, 12547/12550). The validator and guard self-test ran twice; both runs pass.
   - `E5.08 exit=0` appears out of order, after `E5.13` (line 10813).
   - `E5.01` is an `sbt fmt` run that printed `scalafmt: Formatting` in the clone (lines 6-7).
   - E5's validator line `64 checks passed, 0 failed` (line 12557) is in the Phase 01 replay clone (`/tmp/p17-replay-...`, commit 684c610), not the release candidate. It is expected and is not the 72-check result.
   - E5's "regeneration equal" `git status --short` check leaves no output line when it passes, so I cannot confirm it from the log.

## 8. Final gate verdicts

- **"sbt clean check succeeds from a clean checkout": MET.**
  - The run was in a fresh clone at RC SHA `b4d8f12...`.
  - E3 log: `clean` [success] (line 8), full recompile (lines 4-74), module tests 2352/2352 with 5 skipped (line 10185), contract tests 399/399 (line 10851), `[success]` 1303 s (line 10853), `exit=0` (line 10854), 0 `[error]` lines.
  - The saved marker (`19:15:55.402093Z`, 2352, 399) postdates the E3 start.
  - Caveat: discrepancy 2.
- **"test-engineer final test run": MET as planned deviation 5.** The human ran E3 and E4 in the fresh clone, and test-engineer reports their exact counts here with the T1 inventory.
  - E4 `check.sh`: all 9 gates passed with exit 0 (lines 11711-11719), terraform validate succeeded (line 832), 26 of 26 probes passed, `RESULT: check.sh ok`, `exit=0` (lines 11723-11724).

## Requirement-to-test coverage table

See section 6. All 16 spec categories are covered by at least one STRONG test in T1, and every T1 suite outside the 5 RequiresAws/emulator suites executed and passed in E3.

## Failures by owning agent

No test failed in E3, E4 or E5. The open items are the tracked defects behind the six `.fail` tests (rag-engineer: T-02; service-engineer: T-03, T-04, T-05, T-06 twice) and T1's weak-test findings (P17-F-15 spark-engineer; P17-F-16 test-engineer). None of these is new in this step.

```text
STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md, session 3 step E9 (line 350), planned deviation 5 (line 320); report only
FILES CREATED: none (report returned as this message; the orchestrator saves it as agent-report-test-final.md)
FILES MODIFIED: none
COMMANDS RUN: read-only only: grep -n / grep -c over /tmp/p17-E03.log, /tmp/p17-E04.log, /tmp/p17-E05.log and docs/agent-runs/phase-17/*.md; head -n / tail -n of those logs, /tmp/p17-E3-marker.txt, /tmp/p17-E03-start.txt and the clone's .git/HEAD and refs; wc -l; ls. Read tool on prompt.md and the two T1 reports. No sbt, no script, no git, no network, no AWS.
TEST RESULTS: none run by me. E3 (human, fresh clone, RC b4d8f1229fc145e1492e1a8cbb8a44a368d026af): module 150 suites, 2352 executed, 2352 passed, 0 failed, 0 errors, 5 skipped; contract 34 suites, 399/399, 0 skipped; exit=0; 1303 s. Saved marker: timestamp=2026-11-23T19:15:55.402093Z, module_tests=2352, contract_tests=399. E4 (separate): 9/9 gates passed, module 2352/2352 (5 skipped), contract 399/399, 26/26 probes, exit=0. E5 (supplementary): all listed runs exit 0 with the counts in section 5.
VALIDATION RESULTS: every cited number checked by grep against its log line; the marker timestamp is after the E3 start; E3's 184 suite-finish lines equal 150+34; E3 and E5 both show 5 ignored suites
ASSUMPTIONS: local shell zone UTC-6 for file times; the clone's origin is the GitHub remote (SSH-form URL; host not printed); the 9-test rise from 2343 to 2352 is the E1 rag repair (repair-E1-rag.md:23)
RISKS: E3's log lacks the clone-check lines (rev-parse and show-toplevel rest on the orchestrator's record and E4 log line 4); six .fail tests count as passes while their defects stay open; VerifierMonotonicSuite's corpus has absence=0 (repair-E1-rag.md:127), so it does not exercise the absence rule; 5 RequiresAws/emulator suites compile but do not run, and their behavior is checked only by archived live and emulator evidence (planned deviation 2); GC-pressure warnings on a 1 GB test heap (E3 lines 1941, 2120); Policy.erV1 deprecation warnings in tests/contracts
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: E10 release manifest (uses /tmp/p17-E3-marker.txt), then E11 documentation fill-ins of the E3/E4 counts and code-reviewer's check of them
```
