# review-report-a1 (Phase 16, session 2, R-1a: steps D, 1, 2, 3)

VERDICT: APPROVE

I found nothing critical or major. There are eight minor findings, listed below.

Main conclusions:
- **Safety contract.** `EmulatorSafety.check` matches `scripts/emulator/lib.sh` `emu_safety_check` and decision 6.6 rule by rule. The comparison table is below.
- **FECD-1 retry and L1.** Both are bounded, stay inside the existing 20 s budget and are tested with concurrent callers.
- **Production clients.** `AwsSdkClients.build` and `AwsSupport.scala` are unchanged.
- **Step D citations.** I checked 11 against the files and every one held.

## Findings

| ID | Severity | interface | Owner | File:line | Failure scenario / impact |
|---|---|---|---|---|---|
| P16-A-01 | minor | no | storage-engineer (orchestrator records the expected scan hit) | modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorWiringSuite.scala:340, :393 (also :675 `sqs.us-east-1.amazonaws.com`) | The test uses AWS's published example key `AKIAIOSFODNN7EXAMPLE`. That is what decision 6.6 asks for ("real-looking keys"), and it is not a secret. But decision 20's staged-diff scan (`grep -nE 'AKIA\|ASIA\|…'`) will print these lines, and they are not on the list of expected matches. If the human is not told beforehand, the commit stops on them. Fix: the orchestrator adds them to the expected-match list before giving the commit line (no code change needed). |
| P16-A-02 | minor | no | build-engineer | build.sbt:708 (documented at :12-13) | `addCommandAlias("emulatorTest", "all storage/EmulatorTest/test services/EmulatorTest/test")`: `all` runs both forked JVMs at the same time against one emulator. E14 says "one after the other, never together" (decision 17). State isolation by run tag and separate queues limit the harm. Still, anyone who follows the build comment and runs `sbt emulatorTest` breaks the runbook rule, and both suites can hit the emulator at once. Fix: run the two in sequence (`; storage/EmulatorTest/test ; services/EmulatorTest/test`) or document that the alias must not be used at E14. |
| P16-A-03 | minor | no | storage-engineer | modules/storage/src/main/scala/org/scholarchain/storage/local/InMemoryGraph.scala:284-292 | New behavior: an `InMemoryGraph` path search that times out now returns `stoppedBy = Some(GraphStop.TimeLimit)`. No test covers it: the `stoppedBy` assertions in GraphRetrySuite (:294-371) use only `NeptuneGraphRead`. Reverting this line would leave every test green, which breaks the "a test that fails without it" rule. |
| P16-A-04 | minor | no | storage-engineer | modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorClients.scala:61-108 | Decision 6.6 says the `StaticCredentialsProvider` goes "through `AwsSdkClients.build`'s existing `credentials` parameter". `forEmulator` builds its own four SDK builders instead. That is needed, because `build` has no `endpointOverride` or path-style option. The behavior meets the intent: static credentials only, and the production builder is untouched (the diff shows `build` at AwsStorageEnvironment.scala:59-118 unchanged, and EmulatorWiringSuite.scala:420-462 tests it). But step 3's report does not declare this as a deviation. Record it as an unplanned deviation in the s2 summary. |
| P16-A-05 | minor | no | storage-engineer | EmulatorWiringSuite.scala:266-299 | The "identical to lib.sh" test compares the unset list, endpoint, port, rule names and order, and the `${!v+x}` line. It does not compare:<br>- the results-dir set test (`${SCHOLARCHAIN_EMULATOR_RESULTS_DIR+x}`, lib.sh:140);<br>- the `/*` absolute-path rules (lib.sh:107-113, :133-139);<br>- the credentials and config path suffixes (lib.sh:115, :123).<br>So if lib.sh:140 later changed to `${…:+x}` (empty counts as unset), this test would still pass while the two contracts differ. Today they are identical (verified by reading; table below). |
| P16-A-06 | minor | no | storage-engineer | scratchpad agent-report-storage-D.md:31 (M1); D-read load-requests.csv:1051-1052, :1057 | M1 says "Every later request in that level was a breaker rejection". In fact 1,130 HTTP 429 `throttled` rows are mixed into the levels, and the (f) list never names this class. The qualified sentence at :35 ("every neighbors request that passed the gateway got 503") is correct. Impact: a reader can miss that the 554 errors are graph-route 5xx out of 2,251 rows. The diagnosis of causes is not affected. |
| P16-A-07 | minor | no | storage-engineer, build-engineer (process; the orchestrator lists them) | agent-report-storage-D.md:8; agent-report-storage.md:28; agent-report-build.md:18 | Commands outside the work order's list were used and disclosed:<br>- step D: `cd`, `tr`, `for` loops;<br>- step 3: `cd`, two `sed` calls, `unzip -l`, and one line `python3 - …; perl -0pi …` that the guard blocked whole;<br>- step 1: `ps`.<br>All were read-only or blocked. They belong in the s2 summary's "commands used that are not on the list". |
| P16-A-08 | minor | yes (consumer side) | rag-engineer (R-1b to check) | modules/storage/src/main/scala/org/scholarchain/storage/ports/GraphPorts.scala:310-311; modules/rag/src/main/scala/org/scholarchain/rag/runtime/Stages.scala:108-114 | `GraphReadPort.runClassified` defaults to `run(q).left.map(Unclassified(_))`. rag's `RuntimeTrace.wrap` overrides only `run`. So any consumer that calls `runClassified` through the traced stores gets `graph-unclassified` for every failure, and the transient and resource-limit classes promised in step 3's handoff are silently lost. L1 for `PathBetween` is not affected (it is a `Right` with `stoppedBy`, which passes through `run`). Single-shot template classes are affected. If rag or services use `runClassified`, the decorator must override it. |

## Checklist (decision 15)

**Decision 5 (step 1, except 5.3)**
- 5.1 L-09: done.
  - The class is now listed (project/PackageCheck.scala:27-36).
  - The script contract is mirrored in project/BuildSelfTest.scala:44-59. The source mapping is at :61-73. Both run in markTestRun stage 1 (build.sbt:686-692 region).
  - The class is named at scripts/run-bounded-pipeline.sh:1833 (line moved by I1 from the cited :1831; immaterial). check.sh gate 8's grep is at scripts/check.sh:687.
  - Gate 8 does not check RetrievalEvalJob's options (the `# contract: RetrievalEvalJob.Required` form, run-bounded-pipeline.sh:1832/1834). build-engineer reports this correctly as proposed later.
- 5.2 EmulatorTest:
  - Tag and configuration: build.sbt:115, :117-118.
  - Include/exclude arguments: :146-149.
  - Test excludes both tags: :175. AwsTest excludes RequiresEmulator: diff hunk at the awsTestSettings block.
  - Forked group and JVM options: :130-158. The trust store comes from `user.home` (:131-134). No environment variable is read and there is no JAVA_TOOL_OPTIONS (grep of build.sbt: only comments).
  - Configured on storage and services: :409/:414, :453/:462. Alias: :708 (P16-A-02).
  - EmulatorTest is not part of markTestRun.
  - buildPolicyCheck checks all of this (BuildSelfTest.scala:244-313).
  - Exclusion under Test is proven by observation: storage's `storage/testOnly org.scholarchain.storage.*` reports both tagged suites "Ignored" and runs no beforeAll. That run happened after the suite existed. A beforeAll without the environment would have thrown.
- 5.4 B-02: seven jar cases with exact expected problem lines (BuildSelfTest.scala:126-174). Done.
- 5.5 and 5.6: build-engineer reports exit 0 for the check and packageCheck. I cannot observe either (no Bash). The handoff is complete.

**Decision 6 (steps D, 3)**
- 6.1 Step D:
  - Causes (a) to (f) are each given with a code path, data for and against, whether testable offline and whether decidable.
  - The G-28 restatement and the L1 effect on q13 are reasoned, and the evidence supports them.
  - Spot checks (11), all confirmed:
    1. AwsStorageEnvironment.scala:126-129 "Graph reads are not retried" at HEAD (diff context).
    2. NeptuneAdapters.scala:170 `catch { case e: StoreException => Left(e.getMessage) }` at HEAD (diff, counted).
    3. config/course.json:89 (20000), :98 (4), :103-104 (3, 30000).
    4. Support.scala:78 `acquire` and :102 `recordFailure`, both synchronized.
    5. ReadServices.scala:204 `case Some(_) => ApiError.downstream`.
    6. ServiceServer.scala:56 `maxConcurrentQueries + 8`.
    7. AwsSupport.scala:60-62 maxAttempts.
    8. load-requests.csv: 28 `DOWNSTREAM_FAILURE` and 526 `CIRCUIT_OPEN` (Grep counts).
    9. metric-data.json `m26_0` lines 810/812/813 = 0.04999 / 0.1168 / 0.2999 per second, which is 3 / 7 / 18 per minute.
    10. load-requests.csv:1053 is the first L3 request: 503 at epoch 416.
    11. load-requests.csv:1055 is the first L3 502 at epoch 417.
  - One wording issue: P16-A-06.
- 6.2 FECD-1:
  - The retry covers only Transient and ResourceLimit (NeptuneAdapters.scala `isRetryable`). At most 3 retries are allowed by `require`; the default is 2.
  - Delays use equal jitter: 100-200 ms, then 200-400 ms.
  - A retry starts only if at least 250 ms of the budget remain after the delay, and its API timeout is the budget left. The first attempt is unchanged.
  - Counters are atomic. `graphReadReport` is kept apart from `retryReport`.
  - Tests:
    - 8 concurrent callers held together by a latch: GraphRetrySuite.scala:140-183.
    - 6 concurrent callers with a persistent 500: :185-216.
    - 4xx and timeouts are never retried: :218-237.
    - Budget with a fake clock: :239-271.
    - Path-depth retry: :273-297.
  - Production wiring: AwsStorageEnvironment.scala:172-178, tested at :113-128.
  - No limit or threshold was raised.
- 6.3 L1:
  - Resource limits on `PathBetween` give `Right(complete=false, depthSearched=last, stoppedBy=ResourceLimit)`. On a single-shot template they give `GraphReadError.ResourceLimit`.
  - Tested for each exception (GraphRetrySuite.scala:301-351). A non-limit 500 stays a failure (:353-358).
  - Interface: yes, additive. rag and services compile unchanged, per the observed runs in the step 3 report.
- 6.4 L-10: masking is in FailureMasking.scala:12-75 and both tagged suites mix it in. Tested at EmulatorWiringSuite.scala:672-725. Queue names are not masked; their hosts and accounts are.
- 6.5 D-02, D-03 and the throttled StartLoaderJob:
  - D-02 refusal: StorageLoadMain.scala:739-744.
  - Throttle retry under the same claim: :638-690 region. At most 2 retries (2 s, then 4 s). The last throttle records `nodes-failed`.
  - Tests show exactly one load per stage and no extra registry version (StorageLoadMainLocalSuite diff :1449-1533). The bulk-loader classification is tested in NeptuneBulkLoaderStartSuite (diff :1343-1362).
  - The cleanup helper is dropped and proposed later, with reason.
- 6.6 Emulator wiring: see the safety table and the items below.
- 6.7 and 6.8: reported. Handoff complete.

**Decision 9 (step 2)**
- N-1:
  - `FrozenVersions` refusal: Policy.scala:96-97, :128.
  - ErMain always uses `Policy.current` (ErMain.scala:83). There is no other way to apply the er-v1 file.
  - Tested at Phase16RepairSuite.scala:33-51.
- N-2: renamed, with a `@deprecated` forwarder (Policy.scala:189-196). The source scan test is at Phase16RepairSuite.scala:61-72. The 13 users in tests/contracts are named for test-engineer.
- M3:
  - Harness.scala:718-735: a flagged interval prints `(unreliable; zero-event bound [..])` in the weighted row and on the bootstrap line.
  - Tested at Phase16RepairSuite.scala:155-174, including the F1 bound.
- M4:
  - IntervalReliability.scala:247-251: "design-determined" only when every stratum is fixed. A partly fixed rate keeps the flag, which is the conservative direction.
  - Markdown: Harness.scala:772-778.
  - Tested at :182-236.
- F8 and F9: both named, with file:line. ER observed both as `.fail` tests that now fail, which means their assertions pass.
- Unplanned partial M2 fix (EvalReport.scala:148-158): correct, narrow and tested (Phase16RepairSuite.scala:241-261). ER declares it.
- G-18:
  - `evaluatedWith` is at Policy.scala:136-163.
  - The record sits outside `config`, so fingerprints do not change. Tested at :272-296.
  - Spark's manifest reader (Pipeline.scala:167-205) does not reject unknown keys, so the new field is safe.
- Group 1 reduced plan: the reasoning is sound. GoldLabels.scala:107-117 rejects any gold pair that is not in the queue. The Phase 5 queue and key and the er-v1 decisions are gone, so `--compare-decisions` cannot run. No label was invented.

**Cross-cutting items**
- No production limit, threshold, prompt or default changed to improve a number: checked, none.
  - ER thresholds are unchanged (er-v1.json and er-v2.json sha256 unchanged).
  - The only new production behaviors are the prescribed bounded retries.
- Safety contract checked before any client: EmulatorAdaptersEmulatorSuite.scala:74-107 runs, in order, the contract, the credentials read, the config load, the writer, and only then the clients. Every refusal is covered offline (EmulatorWiringSuite.scala:132-299).
- Emulator clients:
  - explicit loopback `endpointOverride`, `DISABLE_HOST_PREFIX_INJECTION` true, `forcePathStyle` on S3, `StaticCredentialsProvider` only (EmulatorClients.scala:44-108);
  - `Target` has a private constructor, plus a `require` on the endpoint;
  - tested: requests go to 127.0.0.1:9297, S3 path-style, signed with the emulator file's key, and system-property keys are ignored (EmulatorWiringSuite.scala:331-418).
  - Production builder unchanged (P16-A-04).
  - No JAVA_TOOL_OPTIONS, `DefaultCredentialsProvider` or `AWS_ENDPOINT_URL` use under modules/storage/src (Grep).
- RequiresEmulator is excluded from Test (build.sbt:175) and AwsTest (awsTestSettings).
- Outcome classes:
  - `Failing` contains only implemented-failed and infrastructure-failure (EmulatorResults.scala:33).
  - A demonstration needs every line to be implemented-passed (:332-333; suite :528-537).
  - not-implemented never counts as passed.
- Demonstration line: `demonstration re-embed: demonstrated | not demonstrated (REASON)` (suite :533-537). `VectorReembedder` catches store errors (VectorReembedder.scala:198-311), so the line is always written once the local ingest succeeds.
- Per-run IDs: every S3 key, DynamoDB pk, vector key, SQS item id and reembed taskId carries `runTag`. Assertions filter to own items (suite :149, :164, :205, :239, :308, :319, :495-497).
- `config/emulator.json` loader test: EmulatorWiringSuite.scala:644-668, in Test, no request.
- L-09 test: BuildSelfTest. B-02: done. N-1, N-2, M3, M4, G-18 and partial M2: done.
- L-10: done. D-02, D-03: done. The throttle path never starts a second load (tests assert the attempts and the registry states).
- Secrets and identifiers:
  - no twelve-zero account in the slice (Grep);
  - placeholders `123456789012` and `210987654321` only;
  - one AKIA example key: P16-A-01.
- Every behavior change has a failing-without test: yes, except P16-A-03.

## Safety contract: Scala (EmulatorSafety.scala:97-134) against lib.sh (:96-150) and decision 6.6

| Rule | lib.sh | Scala | Equal |
|---|---|---|---|
| endpoint | `${SCHOLARCHAIN_EMULATOR_ENDPOINT:-} != https://127.0.0.1:9297` (:99) | `get(EndpointVar) != Endpoint` (unset reads as "") | yes |
| aws-endpoint-url | `${AWS_ENDPOINT_URL:-} != $SCHOLARCHAIN_EMULATOR_ENDPOINT` (:103) | `get(AWS_ENDPOINT_URL) != get(endpoint)` | yes |
| credentials-file (HOME) | `case ${HOME:-} in /*` (:107-113) | `absolute(home)` = startsWith("/") | yes |
| credentials-file (path) | `!= $(emu_dir)/emulator_credentials` (:115) | `!= s"$home/.aws/emulator/9297/emulator_credentials"` | yes |
| credentials-file-exists | `[ ! -f ]` (follows symlinks) (:119) | `Files.isRegularFile` (follows symlinks) | yes |
| config-file | `!= $(emu_dir)/config` (:123) | same string | yes |
| unset:NAME (8, same order) | `${!v+x}`: an empty value counts as set (:127-131) | `env.contains`: an empty value counts as set | yes (bash also refuses unexported shell variables, which can never reach the JVM, so it is stricter in a harmless way) |
| config-file-path | `case ${…:-} in /*` (:133-139) | `absolute(get(…))` | yes |
| results-dir-path | applies when `mode=required` or the variable is set, even empty (`+x`) (:140-148) | `(resultsDirRequired \|\| env.contains) && !absolute` | yes |
| Decision 6.6 | exact endpoint and port, AWS_ENDPOINT_URL equal, exact credentials and config paths with the file existing, 8 variables unset, absolute config and results paths, before any client | all present; suite-only rules (credentials-file-read, config-file-load, results-file-exists) come after the contract and before any client | yes |

## Files

**Read in full**
- All 10 untracked files:
  - modules/entity-resolution/src/test/scala/org/scholarchain/er/Phase16RepairSuite.scala
  - modules/storage/src/test/scala/org/scholarchain/storage/FailureMasking.scala
  - modules/storage/src/test/scala/org/scholarchain/storage/GraphRetrySuite.scala
  - modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorAdaptersEmulatorSuite.scala
  - modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorClients.scala
  - modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorResults.scala
  - modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorSafety.scala
  - modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorWiringSuite.scala
  - modules/storage/src/test/scala/org/scholarchain/storage/emulator/RecordingPorts.scala
  - project/BuildSelfTest.scala
- diff-R-1a.txt (all 1,571 lines), files-R-1a.txt and files-R-1a-status.txt.
- The four producer reports: agent-report-storage-D.md, agent-report-build.md, agent-report-er.md, agent-report-storage.md.
- docs/agent-runs/phase-16/prompt.md (all 475 lines).
- scripts/emulator/lib.sh and modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsSupport.scala.

**Read through their complete diff hunks plus surrounding context, not in full**
- build.sbt: hunks, plus :225-274, plus Grep of aliases and configurations.
- project/PackageCheck.scala: hunk.
- ER main sources:
  - EvalReport.scala: hunk;
  - Harness.scala: :680-799;
  - IntervalReliability.scala: :100-299;
  - Policy.scala: hunks;
  - Report.scala: hunks.
- ER test files: DecisionSuite, EvalRepairRound2Suite, GivenNameGuardSuite, GuardPathSuite, LlmSuite, PolicySuite, PriorSpellingSuite, RunSuite (all hunks).
- Storage main sources:
  - AwsStorageEnvironment.scala: :50-268;
  - NeptuneAdapters.scala: hunks plus :370-414;
  - StorageLoadMain.scala: hunks plus Grep of `startError`;
  - InMemoryGraph.scala and GraphPorts.scala: hunks.
- Storage test files: AwsAdaptersAwsSuite, NeptuneBulkLoaderStartSuite, StorageLoadMainLocalSuite (hunks).

**Context files read for verification**
- GoldLabels.scala:95-124, VectorReembedder.scala:175-384, SqsWorkQueue.scala:15-104.
- Spark Pipeline.scala:145-204, rag Stages.scala:95-144, config/course.json:84-107.
- D-read load-requests.csv (lines 1-2 and 1050-1057, plus Grep counts) and metric-data.json :778-819.
- Phase 14 Grep for D-02/D-03.

**Not reviewed**
- docker/emulator/Dockerfile: excluded by the work order (R-2 reviewed it).
- No slice file is missing.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, as given; I did not recompute it because I have no Bash), R-1a, decisions 4, 5, 6, 9, 15, 16; slice: steps D, 1 (build.sbt, project/**), 2, 3
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run by me. The producers' observed results are taken as their claims: build-engineer's sbt check 2195+399 passed and packageCheck ok; ER 147/147 and the contract ER suites 229 passed + 2 expected `.fail`; storage 319 passed + 2 ignored, rag 175, services 120, contracts runtime 43.
VALIDATION RESULTS: safety contract identical (table above); 11 step D citations confirmed; Grep found no twelve-zero account, no JAVA_TOOL_OPTIONS, no `DefaultCredentialsProvider` and no `AWS_ENDPOINT_URL` use in the slice.
ASSUMPTIONS: sbt 1.x runs a `Tests.SubProcess` group forked whatever the value of `EmulatorTest / fork` (from memory of sbt's `allTestGroupsTask`; not verified at runtime). Since I cannot run anything, the "fails without the change" claims are checked by reading the code (for example, new parameters that would not compile without the change), not by running.
RISKS:
- `sbt check` stays red until step 6 removes the F8 and F9 `.fail` marks (ER risk 1). Step 7's full check depends on it.
- Emulator behavior, the forked JVM's trust store and SQS dead-letter timing are untested at runtime until E14.
- P16-A-08 concerns step 4 and step 7 consumers.
MANUAL ACTION REQUIRED: none from this review. The orchestrator adds the AKIA example-key lines (P16-A-01) to the expected scan matches before the session 2 commit line.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 8 triage (all findings minor; none needs a repair round under decision 15's rule); R-1b re-checks P16-A-08 against rag's and services' use of `runClassified`.
