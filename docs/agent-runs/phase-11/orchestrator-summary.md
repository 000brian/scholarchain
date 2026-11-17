PHASE RESULT: COMPLETE_WITH_OPEN_ITEMS

# Phase 11 orchestrator summary

- Work order: docs/agent-runs/phase-11/prompt.md, sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1
- Start: HEAD 2582a50e1e77bb5b20570158d91b7502c1df64e2. The tree was clean except the untracked docs/agent-runs/phase-11/prompt.md. `.claude/.last-test-run` was 2026-11-16T12:10:31Z with module_tests=1877 and contract_tests=336, which matches the bundle.
- End: 78 tracked files changed (+3367 −700) plus 36 new files. Nothing is committed. Nothing was written under docs/agent-runs/.
- Why COMPLETE_WITH_OPEN_ITEMS: every step ran, the closing `sbt check` passed, and every P1 item is done. One new **major** finding is open: R3-01, from step 5 (rag-engineer). Decision 10 allows no second round, so it is escalated to the human here.

## Separate claims (decision 12.4)

1. **`sbt check` green.** Step 4 (repair-wave closer) gave EXIT=0, 939 s, finished 2026-11-16 22:49:02 local.
   - Module tests: 2001 run, 0 failed, 3 skipped. At the start it was 1877 run and 3 skipped; the skipped count is unchanged.
   - Contract tests: 398 run, 0 failed, 0 skipped. At the start: 336.
   - Marker: module_tests=2001, contract_tests=398.
   - Expected failures still marked `.fail` (8): T-02, T-03, T-04, T-05, T-06 (Content-Length), T-06 (chunked), Phase 5 F8 (repair-21), Phase 5 F9 (repair-29).
2. **P1 items:** all done (table below). Step 5 ruled every one RESOLVED.
3. **Critical and major findings:**
   - No critical finding at any point.
   - All 18 wave-1 major findings were ruled RESOLVED in step 5: A-01, B-01, B-02, C-01, C-02, C-03, C-04, C-05, D-01, D-02, T-01, T-08, T-10, plus U-6 and S-4 as P1 items.
   - The D-02 build-engineer part is not needed: step 5 found that the container now loads the prompts from the classpath.
   - **Open:** R3-01 (new major in step 5, rag-engineer).
4. **`RequiresAws` tests:** compiled, not run. The 3 skipped module tests are AwsAdaptersAwsSuite, LiveLlmAwsSuite and LiveProvidersAwsSuite. No AWS, Voyage, Anthropic or Bedrock call was made.
5. **Gate item "every production behavior has a relevant test":**
   - Step 1a had 4 NONE rows. Step 1b covered RetryingVectorIndex (S3VectorsThrottlingStubSuite).
   - The other three gained tests in step 3. This is my derivation from the repair reports; test-engineer did not re-inventory.
     - RetryingObjectStore: storage AwsErrorMappingSuite "B-01 an S3 I/O failure is retried by RetryingObjectStore".
     - GraphAnalyticsJob broadcast setting: spark PipelineFixtureSuite "F9 part 1".
     - Pipeline on a Hadoop FileSystem: PipelineFixtureSuite r2 through file:// URIs. s3:// is not exercised.
   - Remaining NONE rows: none. The s3:// path stays untested until Phase 13/14.
6. **Human approval:** pending. None of the above is approval.

## Findings table

Status values: fixed (step 5 RESOLVED), open, recorded (minor or advisory, not repaired), not reviewed.

| ID | Sev | Owner | Status |
|---|---|---|---|
| A-01 | major | entity-resolution-engineer | fixed |
| A-02, A-05 | minor | entity-resolution-engineer | recorded |
| A-03 | minor | hadoop-engineer | recorded |
| A-04 | advisory | dataset-engineer | recorded (Phase 12/17) |
| B-01 (+B-11) | major (+adv) | storage-engineer | fixed |
| B-02 | major | spark-engineer | fixed |
| B-03, B-04, B-05, B-06, B-07, B-09 | minor | storage-engineer | recorded |
| B-08, B-10 | minor | spark-engineer | recorded |
| B-12, B-13 | advisory | storage-engineer | recorded |
| C-01, C-04, C-05 | major | rag-engineer | fixed |
| C-02, C-03 | major | rag-engineer + service-engineer | fixed (both parts) |
| C-06, C-07, C-08, C-10, C-11 | minor | rag-engineer | recorded |
| C-09 | minor | service-engineer | recorded |
| C-12, C-13 | advisory | service-engineer | recorded |
| D-01 | major | rag-engineer | fixed |
| D-02 | major | rag-engineer (+ build-engineer) | fixed (classpath prompts; R3-06 is the residual test gap) |
| D-03 | minor | domain-engineer | fixed (same root cause as T-08/T-09) |
| D-04, D-05 | minor | domain-engineer | recorded (proposed later) |
| D-06 | advisory | domain-engineer | fixed (decided: always write) |
| D-07 | advisory | build-engineer | recorded |
| D-08 | minor | domain-engineer | fixed (with U-8) |
| D-09 | advisory | test-engineer | recorded |
| D-10 | advisory | documentation-engineer | recorded (Phase 17) |
| T-01, T-10 | major | rag-engineer | fixed (marks removed) |
| T-07 | minor | rag-engineer | fixed (side effect of the shared root cause; mark removed) |
| T-02 | minor | rag-engineer | recorded (`.fail` kept) |
| T-03, T-04, T-06 ×2 | minor | service-engineer | recorded (`.fail` kept) |
| T-05 | minor | rag-engineer + service-engineer | recorded (`.fail` kept) |
| T-08 | major | domain-engineer | fixed (mark removed) |
| T-09 | minor | domain-engineer | fixed (same fix; mark removed) |
| R2-01, R2-02 | minor | test-engineer | recorded (weak contract tests: WindowProvenanceCrossRecordSuite, ConfigOmissionSuite) |
| R2-03 | minor | entity-resolution-engineer | recorded. **Human decision:** ER behaviour changed under the same policy label er-v1 (er-v2 or a recorded amendment?) |
| **R3-01** | **major** | **rag-engineer** | **OPEN.** ClaimVerification.scala:349-353 treats any plain literal from 1900 to 2100 as a year. A correct ranking claim with a metric value in that range (e.g. W = 2034) becomes `conflicting` and is removed. Real values sit near the range (fake run: 1895, 3290). Likely to hit attempt-07 and Phases 14-16. |
| R3-02, R3-03 | minor | test-engineer | recorded |
| R3-04, R3-05, R3-08 | minor/adv | rag-engineer | recorded |
| R3-06 | advisory | rag-engineer (test) + build-engineer (assembly check) | recorded |
| R3-07 | advisory | test-engineer | recorded |

No reviewer slice failed, so nothing is "not reviewed". The coverage of each slice is partial and is listed in each review report.

## Decision-8 items

| Owner | Item | Pri | Status |
|---|---|---|---|
| hadoop | Phase 4 finding 2 (reuse verification) | P1 | done; RESOLVED |
| hadoop | Phase 5 m5 (default key families) | P1 | done; RESOLVED |
| spark | Known-graph suite (case sheet) | P1 | done; RESOLVED. No SP-nn cases. Sheet dispute T30 "no oversize flag": ruled the sheet was wrong for Spark; the check belongs in a Hadoop job-level test (later) |
| spark | Phase 6 F6 | P1 | done; RESOLVED |
| spark | k-core cap 50 | P1 | done; RESOLVED |
| spark | Phase 6 F9 part 1 | P1 | done; RESOLVED |
| spark | Phase 6 F9 part 2 | P1 | done (file:// only, not reverted); RESOLVED |
| domain | U-6 | P1 | done; RESOLVED |
| domain | QueryAudit v2 | P1 | done; RESOLVED. Left out: per-task HTTP attempts and retried attempts (rag counts them per process only) |
| domain | U-8 | P2 | done |
| domain | Phase 9 item 1 (semantic_context has no metric) | P2 | done |
| storage | QueryAudit v2 round trip | P1 | done; RESOLVED |
| storage | Phase 7 m3 | P2 | not done: not small (proposed later) |
| storage | r1, r2 | P2 | done |
| storage | typed GraphRow decoder | P2 | not done: unused unless rag switches to it (proposed later) |
| rag | L-1 (with L-4) | P1 | done; RESOLVED (R3-08 advisory residual) |
| rag | L-2 | P1 | done; RESOLVED (R3-01 is a side effect of the year rule) |
| rag | L-3, U-10, U-3, QueryAudit v2 population | P1 | done; RESOLVED |
| rag | Fake benchmark | P1 | run once. 110/110 values, 4/4 paths, 3/3 abstentions (none unexpected), 89/89 planted errors, injection passed, claims 173/2/0/0 (Phase 10: 173/2/0/0) |
| rag | U-5 | P2 | done |
| rag | U-13 | P2 | rag part done; the storage JobStore doc comment was reported, not edited |
| rag | n-3 | P2 | done (tests) |
| rag | n-1, n-2, n-4 | P2 | not done: beyond small after the verifier changes (proposed later) |
| services | Golden examples | P1 | done: 5 files changed; all 15 frozen files equal the generated sha256 |
| services | Explain on QueryAudit v2 | P1 | done; RESOLVED |
| services | S-4 | P1 | done; RESOLVED |
| services | S-3, S-6, S-7, S-9, S-10 | P2 | done |
| services | S-8 | P2 | not done: needs an accept-side design (proposed later) |
| services | R-1 | — | not changed (decision) |

## Test-evidence check

- Every source-changing agent returned a TEST EVIDENCE block. Every suite named ran more than 0 tests; storage's first run had a wrong rag suite name and was replaced by run 2, which counts.
- My read-only grep for `failures="[1-9]|errors="[1-9]` came back empty for every changed module: hadoop, entity-resolution, spark, core jvm-3, core jvm-2.13, storage, rag and services. tests/contracts was not grepped, as the rules say.
- No send-back was needed. There was no second miss.
- "Fails without the fix" was argued from the old code, not re-run against it, by: hadoop (F2), spark (B-02, F6, F9), domain (several items), storage (B-01) and rag/services (most items). The reason given: restoring old code needs a git command that writes. Step 5 checked these assertions against the old behaviour and ruled them sound. Observed pre-fix failures exist for A-01, k-core, B-01, T-08, T-09, U-6, S-4, Phase 4 F2, the golden examples and ReadRoutesSuite.

## Approval prompts and BLOCKED-ON seen

- **Approval prompts:** none.
- **Step 3a, hadoop-engineer: STATUS BLOCKED-ON test-engineer.** JobLevelBlockingSuite failed 6 of 7 tests under the decided m5 default. I routed it to step 4, where test-engineer pinned the suite to one family. It was not a stop.
- **Harness: the prescribed wait loop was blocked.** The run of `sleep 240` was refused ("use Monitor … or run_in_background; do not chain shorter sleeps"). I waited on agent-completion notifications and bounded until-loops over the report files instead.
- **Stop hook:** the main-session Stop hook blocked ending a turn twice after step 0's build.sbt change, until the step-1b `sbt check` refreshed the marker. Expected under decision 2; I did not run sbt myself.

## Process deviations reported by agents (no writes, no approval prompts)

- Steps 1a, 1b and 4 (test-engineer) had no Grep or Glob tools. They used read-only shell instead, including `cd <repo> && …`; step 1a also used one `find|xargs wc -l` and one `cat`.
- Step 3f (rag): one read-only `od -c`, which is on the "do not use" list.

## Attempt-07 command (printed by rag-engineer; the human runs it after the gate)

```
sbt -batch -J-Xmx12g "rag/runMain org.scholarchain.rag.runtime.BenchmarkRunner --config modules/rag/src/test/resources/config/rag-runtime-anthropic-v2.json --export modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b --neptune-csv modules/storage/target/phase-07-real/attempt-01/neptune --questions modules/rag/src/test/resources/benchmark/benchmark-v1.json --out modules/rag/target/phase-11-real/attempt-07-anthropic --llm anthropic"
```

Notes:
- The default prompt set is v3, and no limit changed, so the v2 config is the right one.
- The API key goes in the variable named by `llm.apiKeyEnvVar`.
- Run it in a shell with no `SCHOLARCHAIN_CFG_*` variables: D-01 now applies them.
- R3-01 may remove correct ranking claims whose values fall between 1900 and 2100.

## Proposed later (collected)

- **build-engineer:** map prompts/runtime as a rag resource directory, then remove the copies (R3-06); a build-embedded code identity (Phase 4 F2 part); D-07.
- **infra-engineer (Phase 13):** pass the git commit as the code version to hadoop and spark; an s3:// smoke run.
- **entity-resolution:** er-v2 or a recorded amendment (R2-03); a dedicated given-name guard rule; A-02 and A-05.
- **spark:** JsonlExport control-file check; read `--config` through the Hadoop FS; B-08; B-10.
- **storage:** m3; GraphRow decoder; B-13 fixed cause codes.
- **rag:** R3-01 (major, open); n-1, n-2, n-4; C-06; the remaining record-only C items; the workflow Left fixed texts.
- **domain:** Phase 9 item 2; D-04; D-05; per-query HTTP and retry counts.
- **services:** S-8; C-09; C-12; C-13; Main runtime-build exception handling.
- **test-engineer:** plan items 11, 12 (rest), 13, 15, 16 and 3 (rest); R2-01; R2-02; R3-02; R3-03; R3-07; a job-level test of the two-family blocking default; rerun-refusal tests for a same-length tamper and for a missing outputs list.

## Step log

- **Step 0, build-engineer: COMPLETE.** contractTests now depends on storage, rag and services with `compile->compile;test->test`. Test/compile passed. No duplicate test resources.
- **Step 1a, test-engineer: COMPLETE.** Wrote the inventory, the behavior map and the Spark case sheet.
- **R-A to R-D, code-reviewer: all COMPLETE, all REQUEST_CHANGES.**
- **Step 1b, test-engineer: COMPLETE.** 12 suites (62 tests) and 16 `.fail` marks. `sbt check` EXIT=0: 1877 module tests (3 skipped), 398 contract tests.
- **Step 2, triage:** 18 repair-now IDs. No critical finding, so no stop.
- **Steps 3a to 3g:** hadoop BLOCKED-ON test-engineer (routed to step 4); ER COMPLETE; spark COMPLETE; domain COMPLETE; storage COMPLETE; rag COMPLETE; services COMPLETE.
- **Step 4, test-engineer: COMPLETE.** `sbt check` EXIT=0: 2001 module tests, 398 contract tests. Steps 4b and 4c were not needed.
- **Step 5, code-reviewer, two slices:** slice 1 APPROVE (review-report-02.md); slice 2 REQUEST_CHANGES with R3-01 major (review-report-03.md).
- **Step 6:** this summary.
