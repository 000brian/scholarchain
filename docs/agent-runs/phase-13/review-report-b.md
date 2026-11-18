STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), session 2, review R-2 (steps 2-5). Report file: review-report-b.md
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not run (I have no Bash). Writer claims are taken from the reports and not re-verified: storage 232 passed, 1 ignored; rag 231 passed, 2 ignored; spark targeted 20 passed; wave closer `sbt check` gave module_tests=2062, contract_tests=398, then `packageCheck: ok`. The orchestrator checks these against `.claude/.last-test-run`.
VALIDATION RESULTS: `jq empty` on the experiments JSON was not run by me. I read both files and they look like valid JSON. The orchestrator re-runs `jq empty` under decision 10.
ASSUMPTIONS:
- The R-1 files (build.sbt, project/*.scala, docker/services/Dockerfile, .gitignore) have not changed since R-1, as the work order says. I read only build.sbt:210-252 to confirm that the build-identity resource is a Compile resource generator for assembled projects.
- I read the IAM state under infra/terraform as it stood during this review. Step 6 is running at the same time and may change it.
RISKS:
- Off-list commands that the writers reported themselves: rag-engineer moved the rag resources folder to the scratchpad with `mv`; experiment-engineer ran `sed -n`. spark-engineer ran a repository-wide `sbt fmt`, and its report says formatting passed over storage and rag sources. I cannot tell from files whether that changed bytes in another owner's files, so this is unverified, not a finding.
- modules/storage/src/test/scala/org/scholarchain/storage/load/BuildIdentitySuite.scala:65,70 uses the 12-digit account `210987654321`, not the documented placeholder `123456789012`. The human's commit scan (`[0-9]{12}`) will flag it, and only the human can confirm it is not a real account.
- Spark and EMRFS's own INFO logs and executor stack traces can name the bucket. That is outside the job's code (spark report RISKS).
- `--loader-role-arn` contains the account ID. The run report masks it (StorageLoadMain.scala:551), but step 7b must keep it out of persisted script records (ECS overrides, run records).
- Phase 7 m3: AWS mode refuses `--parallelism` > 1 within one process. Two `ops` tasks at once are prevented only by the step 7b one-task rule.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Triage (step 8). C-01 is major with interface: yes, so it must be resolved before step 7a (decision 4).
- Once step 6 finishes, check whether its JsonlExport `exportRoot` and IAM put exports under `graph/` (for example `graph/exports/<attempt>`).
- If they do, C-01 is resolved by step 6, and R-3 confirms it.
- If they do not, route C-01 to infra-engineer before step 7a.
- C-02 (major, interface: no) goes to storage-engineer in step 9.

VERDICT: REQUEST_CHANGES

## Requirement checklist (slice: decisions 6, 7, 7a, 7b, 7c)

**Decision 6 (experiment-engineer)**
- README, what a manifest is: done. experiments/README.md:18-22.
- README, approval line `APPROVED-MANIFEST-SHA256: HEX64` in a committed human-gate.md or approvals.md under docs/agent-runs: done. README.md:24-36.
- README, how the runner works (refusal order as in 9.7, the three guarantees, both action kinds, metrics export): done. README.md:38-58.
- README, the enforced-yes/no table: done. README.md:60-89. It marks as enforced every rule decision 6 lists, plus type checks on the other required fields and a `..` rule for `action.path`. That is stricter than required and consistent.
- README, the limits text verbatim, with the 5 USD hard cap: done. README.md:93 matches decision 6 word for word.
- README, raw-data location, immutability, failed runs kept: done. README.md:97-110.
- Schema is draft 2020-12, with every required field, region const `us-east-1` and `costBoundUsd` maximum 5: done. manifest-schema-v1.json:2, :7-21, :50-53, :59-64.
- Example manifest (`example: true`, experiment A, small limits): done. example-not-approved.json:5, :6, :10-17.
- Open contract gap: C-03.

**Decision 7, common rules**
- Option constant, exact-contents test, unknown option refused:
  - storage: done. StorageLoadMain.scala:67-85; StorageLoadMainArgsSuite.scala:15-41 and :78-84.
  - rag: done. RagIngestJob.scala:90-91; RagIngestJobSuite "decision 7" tests.
  - spark: done. JsonlExport.scala:234 and :237; JsonlExportArgsSuite.scala:17-20 and :37-52.
- `--code-version` plus build identity recorded:
  - storage: done. StorageLoadMain.scala:310-311.
  - rag: done. `codeVersion`, `jobCodeVersion` and `build` in the report (RagIngestJob.scala, report object). `CodeVersion` is unchanged and remains the run-ID input; a test proves the run ID ignores `--code-version`.
  - spark: done. `_export_report.json` gets `codeVersion` and `buildIdentity`.
- Masking (no bucket or account printed or written):
  - storage: done. Masking.scala:9-33, applied at StorageLoadMain.scala:326-329 and :369-372; tested with assertMasked in StorageLoadMainLocalSuite.
  - rag: done. Masked report, copy record and printed lines; RagIngestJobSuite "no bucket name, account ID or key" test.
  - spark: done. `show`, `maskText`, and main (:744-760) rethrows a masked message with no cause. The main wrapper itself is untested: C-05.

**Decision 7a (storage-engineer)**
- `S3ExportSource` through `ObjectStore`, with the same checks as `LocalDirectorySource` (manifest, size, sha256): done.
  - Code: ExportSource.scala:193-260 (shared checks), :285-318, :323-334.
  - Tests: S3ExportSourceSuite.scala:57 (missing manifest), :65 (sha256), :88 (size), :104 (missing file).
- `StorageLoadMain`, exact class name, four subcommands: done. StorageLoadMain.scala:61-85 and :411-658.
- Store names from the config file plus `SCHOLARCHAIN_CFG_*`: done. StorageLoadMain.scala:260-270; StorageLoadMainLocalSuite.scala:339-384.
- Local mode: done. LoadStores.scala:38-57.
- One writer on AWS (Phase 7 m3): done. StorageLoadMain.scala:204-208; StorageLoadMainArgsSuite.scala:103-125.
- IAM needs for step 6 reported: `s3:PutObject` on `graph/*`; no BatchWriteItem needed; a conditional grant for the export prefix. See C-01.

**Decision 7b (rag-engineer)**
- `--export s3://` through `ExportSources`, local path still works: done. RagIngestJob.scala:425-434.
- Report copy to `rag/` (put-if-absent, content-addressed): done. RagIngestJob.scala:600-667. Current IAM `RagWrite` (runtime/iam.tf:256-260) covers it.
- Disk bound: reported as met (15 GiB needed against the 30 GiB ops ephemeral storage).
- R3-06:
  - Prompts are loaded from the build-mapped classpath path: Prompts.scala:135-179.
  - The copies are removed; `modules/rag/src/main/resources` no longer exists.
  - Byte-equality and clear-failure tests: PromptResourcesSuite.scala:30-85.
  - `prompts/runtime` holds exactly the 5 IDs in `Prompts.PromptIds`.

**Decision 7c (spark-engineer)**
- `JsonlExport` output through the Hadoop FileSystem, with scan and sha256 through the FileSystem: done. JsonlExport.scala:418-444 and :501-521.
- Manifest written last and create-new: done. RunFs.scala:58-62; JsonlExport.scala report then manifest at the end of `run`.
- An existing target is refused: done. JsonlExportSuite: existing empty run directory test, and a mockstore second-export test.
- Nothing written on a refusal: done. Codec and schema checks now run before `mkdirs`, and a test covers 5 cases.
- `file://` URI and plain path give identical bytes and manifests: done. JsonlExportSuite "decision 7c" test.
- FileSystem-only proof through the `mockstore://` scheme: done.

## Findings

| ID | Severity | Interface | Owner | File:line | Failure input | Impact |
|---|---|---|---|---|---|---|
| C-01 | major | yes (export layout and IAM need consumed by steps 6, 7b) | infra-engineer | Spark handoff example `--out-root s3://{data-bucket}/exports/ATTEMPT` (agent-report-spark.md:68-69, IAM list :72) conflicts with storage and rag's assumed layout `s3://{data-bucket}/graph/exports/run=HEX` (agent-report-storage.md:31, :80-82; agent-report-rag.md:29, :75). Current grants cover only `graph/` for both roles: EMR writes only curated and graph (infra/terraform/modules/identity/emr.tf:20); `ops` reads only graph and rag (infra/terraform/modules/runtime/iam.tf:241-252). | Step 6 or 7b wires the EMR export step and the loader calls from the spark example: `exportRoot = s3://{data-bucket}/exports/<attempt>`. | The EMR export step gets AccessDenied on PutObject, and `dynamodb-load`, `neptune-csv-build` and `RagIngestJob` get AccessDenied (403, not "missing file") reading the export. This shows up only at the first Phase 14 run and needs a new plan and apply. The Scala code accepts any prefix; the defect is the undecided layout across three handoffs. Fix: pin exports under `graph/` (for example `graph/exports/ATTEMPT`, already covered by both roles, so no IAM widening), or grant the chosen prefix to both roles. R-3 checks that the state machine `exportRoot`, the 7b loader calls and IAM agree. |
| C-02 | major | no | storage-engineer | modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala:393-394 and :418-423 (`openExport` calls only `checkPresence`; the existing `ExportSource.verifyAll`, ExportSource.scala:238, is never called before writes). Writes happen while reading: DynamoLoader.scala:274-282 and :297-310. | `dynamodb-load --stores aws --export s3://…/run=HEX` where one listed part file (for example `canonical_researcher/part-00000.jsonl`) was replaced by different bytes of the same size. Its lines still decode, so `checkPresence` passes. | `VerifiedLines` checks sha256 only at end of file, after `upsertIfChanged` has already been submitted for every line read. The run exits 1, but EntityLinkCache and EvidenceBundle keep items built from bytes that failed the manifest sha256: unverified data in serving tables, against the provenance invariant. The test at StorageLoadMainLocalSuite.scala:304-323 checks only the exit code and status, not that no item was written. Fix: call `src.verifyAll(manifest)` in `dynamodb-load` before the first write. Add a test that a tampered export makes 0 writes (`writesPerTable` all 0). rag (renders the whole corpus before ingest) and `neptune-csv-build` (builds locally before upload) are not affected. |
| C-03 | minor | yes (entry-point option values consumed by 7a, 7b) | infra-engineer | experiments/README.md:20 (manifests hold only logical `s3://{data-bucket}/KEY`) vs README.md:55 (pipeline args passed "unchanged"); example-not-approved.json:24 (`--dblp s3://{data-bucket}/raw/…`); scripts/run-bounded-pipeline.sh:289 (`require_uri … raw` expects a real URI); metric placeholders `{emr-cluster-id}` and `{state-machine-arn}` (example :35-56). | A real experiment-A manifest built like the example. | No step resolves `{data-bucket}` or the dimension placeholders, so a compliant manifest fails in run-bounded-pipeline.sh, or a working one must break the no-bucket-name rule. Decision 9.6 and 9.7 do not say which script resolves them (experiment report R1, R2). Fix: 7a or 7b defines the resolution (run-experiment.sh or run-bounded-pipeline.sh resolves the logical form from `terraform output`), and the README is aligned afterwards. |
| C-04 | minor | no | storage-engineer | StorageLoadMain.scala:595-607 | `neptune-bulk-start` where the `nodes` StartLoaderJob succeeds but the `edges` start (or the registry write) throws. | The `nodes` load ID is registered only after both starts. A retry finds no registry entry and starts a second `nodes` load of the same graph run. The load ID is printed (:596) but not persisted in the registry, and no test covers a failure after the first start. Fix: register the `nodes` load ID right after its start (or register a "starting" marker first), and test the edges-start failure. |
| C-05 | minor | no | spark-engineer | modules/spark/src/main/scala/org/scholarchain/spark/JsonlExport.scala:744-760 | A regression that rethrows the raw exception from `main` (message with `s3://real-bucket/…` and a cause chain). | No test runs `main`'s catch and rethrow (only `show` and `maskText` are tested), so the "no bucket in the step log" rule could break without a failing test. Fix: move the wrapper into a session-free function and test that its message is masked and its cause is null. |

No finding on:
- Secrets and identifiers: no real key, bucket, ARN or IP. Fixtures use `example-*` and `123456789012`; see RISKS for `210987654321`.
- Ownership: every changed file is in its owner's territory.
- One writer on AWS for the loaders.
- R3-06: one source of the runtime prompts.
- JsonlExport refusal-before-write.
- Test strength of the option-constant tests.

## Coverage

Read in full:
- docs/agent-runs/phase-13/prompt.md
- scratchpad/phase13-reports/diff-R-2.txt (all 2249 lines)
- agent-report-experiment.md, agent-report-storage.md, agent-report-rag.md, agent-report-spark.md
- experiments/README.md, experiments/manifest-schema-v1.json, experiments/manifests/example-not-approved.json
- modules/storage/src/main/scala/org/scholarchain/storage/load/{BuildIdentity,GraphRun,LoadStores,Masking,StorageLoadMain}.scala
- modules/storage/src/test/scala/org/scholarchain/storage/load/{BuildIdentitySuite,LoadFixtures,S3ExportSourceSuite,StorageLoadMainArgsSuite,StorageLoadMainLocalSuite}.scala
- modules/storage/src/main/scala/org/scholarchain/storage/source/ExportSource.scala
- modules/storage/src/main/scala/org/scholarchain/storage/aws/AwsStorageEnvironment.scala
- modules/rag/src/main/scala/org/scholarchain/rag/runtime/Prompts.scala
- modules/rag/src/test/scala/org/scholarchain/rag/runtime/PromptResourcesSuite.scala
- modules/spark/src/main/scala/org/scholarchain/spark/{BuildIdentity,RunFs}.scala
- modules/spark/src/test/scala/org/scholarchain/spark/{JsonlExportArgsSuite,MockStoreFileSystem}.scala
- modules/spark/src/test/scala/org/scholarchain/spark/JsonlExportSuite.scala (lines 1-232 directly, the rest through the diff, which shows every added line)
- modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala (lines 1-60, 186-315 and 420-678 directly; the rest through the complete diff hunks)
- modules/rag/src/test/scala/org/scholarchain/rag/RagIngestJobSuite.scala (lines 1-280 directly; the rest through the diff, which holds all of lines 277-665)
- modules/spark/src/main/scala/org/scholarchain/spark/JsonlExport.scala (lines 1-230 and 325-594 directly; 230-325 and 594-end through the complete diff hunks)
- The five deleted prompt copies (through the diff), with `prompts/runtime/` listed (5 files) and `modules/rag/src/main/resources` confirmed absent.

Read in part as supporting evidence (not in the slice):
- infra/terraform/modules/runtime/iam.tf:225-308
- infra/terraform/modules/identity/emr.tf:19-73
- config/course.json
- build.sbt:210-252
- DynamoLoader.scala:180-310 (grep and parts)
- scripts/run-bounded-pipeline.sh (grep)
- core StableIds RunId and AwsSettings (grep)

In-slice files not read: none.
