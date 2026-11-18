STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-14/prompt.md (sha256 b1f951683cc708c297d3e74d6b21332f5218324e4db0086d33307ec8bf8b88a2), session 2, review R-1 (steps 1, 3, 4: decisions 5, 7, 6). HEAD c4fbf62 plus uncommitted changes. Diff: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2e79420f-8018-4674-a178-e411c1ae4532/scratchpad/phase14-reports/diff-R-1.txt
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not run (reviewer has no Bash). The orchestrator says it checked `.claude/.last-test-run` module_tests=2075, contract_tests=398. That matches the "Updated … (module_tests=2075, contract_tests=398)" line in storage's report. I did not see the file myself.
VALIDATION RESULTS: every slice file read in full (list below). Callers, the registry implementation and the guard's secret list were checked by reading.
ASSUMPTIONS: (1) Hadoop's GenericOptionsParser stops at the first non-generic option (`--output`), so a trailing `-D` reaches `ScholarChainHadoop.parse`. Test 1 of ScholarChainHadoopSuite pins this; I did not re-check it in the Hadoop source. (2) The AWS doc pages storage cites for the definite-error list were not re-read by me (no aws-docs tool in this fence). The list's classes compile against the pinned SDK (tests passed per the report).
RISKS: (1) The secret rules now exist twice in .gitignore (:33-56 and :85-108). No check keeps them in step with each other or with `is_secret_path` (.claude/hooks/guard-dangerous.sh:175-185); today all three match exactly. (2) A 429 (TooManyRequestsException) or ThrottlingException from StartLoaderJob counts as an unknown outcome. That is the safe direction, but it locks the graph run until there is a new export or config. Storage already records this risk; it is not a finding. (3) The build report could not show `git check-ignore -v experiments/raw/x/.env` because the guard blocks `.env` paths. By reading, `.gitignore:85:.env` comes after `!experiments/**` (:83), so the file stays ignored. The human can confirm in a terminal.
MANUAL ACTION REQUIRED: optional. The human can run `git check-ignore -v experiments/raw/x/.env` in a terminal; expected output: `.gitignore:85:.env`.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 5 (infra-engineer). The Hadoop interface change (`--dblp-dtd`, unknown options refused) has no critical or major finding, so the interface gate does not hold step 5. After that, R-2.

VERDICT: APPROVE

## Files read

Read in full (every file in the slice):
- /Users/brian/Developer/repos/scholarchain/.gitignore
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pipeline/ScholarChainHadoop.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/ScholarChainHadoopSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/ports/GraphPorts.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/aws/NeptuneAdapters.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/load/StorageLoadMainLocalSuite.scala (Read: lines 1-165 and 370-643; lines 161-371 from the diff hunk, which shows them in full)
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/NeptuneBulkLoaderStartSuite.scala (a new file, so the diff contains all 123 lines)
- Agent reports: agent-report-build.md, agent-report-hadoop.md, agent-report-storage.md (scratchpad phase14-reports)

Read in part, for context:
- prompt.md:60-244
- infra/terraform/modules/workflow/sfn.tf (Grep: :24-32, :226-228)
- scripts/run-bounded-pipeline.sh :80-109, :290-330, :865-914
- scripts/README.md (Grep)
- modules/storage/.../load/LoadStores.scala (full)
- .../dynamo/LedgerAndRegistry.scala:95-130
- .../dynamo/ItemSupport.scala:112-118
- .../dynamo/TableSpecs.scala:255
- .../aws/AwsSupport.scala:72-140
- .../aws/AwsStorageEnvironment.scala:143-175
- .../neptune/BulkLoadRequests.scala (Grep)
- modules/hadoop/src/test/.../PipelineSuite.scala:140-171
- .claude/hooks/guard-dangerous.sh:165-194
- infra/terraform/modules/runtime/main.tf:30-59
- build.sbt (Grep for AwsTest)
- AwsAdaptersAwsSuite.scala (Grep for env)
- docs/agent-runs/phase-13/review-report-02.md:60-63

Slice files not read: none.

## Checklist (decision 11)

Decision 5 (B-04):
- Done, .gitignore:76-108. `!experiments/**` and `!releases/**` (:83-84) come after every non-secret rule. By reading, last match wins:
  - crash.log, run.log, a.plan, target/r.json under experiments/, and releases/course/x.log, match :83 or :84 last, so they are not ignored. For `target/`, the directory itself also matches `!experiments/**` last, so the parent-directory rule does not block re-inclusion.
  - experiments/raw/x/.env matches :85 last, so it is ignored.
  - evidence/runs/x.json matches :16 only (not under experiments/), so it is ignored.
  - plans/x.tfplan matches :62 and :74, so it is ignored.
- Orchestrator point (1), whole-repository effect of the repeated secret block: before this change the secret rules (:33-61) already came after `!*.example` (:30). The new end block also comes after `!*.tfvars.example` (:66) and `!…/course.tfvars` (:73). No secret pattern matches either name. The only `*.example` file is infra/terraform/environments/course/local.auto.tfvars.example; it matches no secret pattern, so it stays tracked. course.tfvars stays included. local.auto.tfvars stays ignored (:65). `!experiments/**` is anchored to the root, so `modules/**/experiments` is unaffected. Today experiments/ holds only manifest-schema-v1.json, manifests/example-not-approved.json and README.md; releases/ does not exist; there are no hidden files. Result: no tracked or currently present file changes state. Expected future change: OS and IDE noise under experiments/ and releases/ is no longer ignored (D-01).
- The secret list at :85-108 equals the list at :33-56 plus `*.tfstate` and `*.tfstate.*`, and matches `is_secret_path` (guard :179, :182).

Decision 7 (DTD), each item:
- Required, optional or unused: optional key with empty default (defaults.xml:152-162). It is needed for the release, which declares no internal entities. Checked.
- Without the DTD, records are rejected as `undefined_entity` (DblpExtractJob.scala:184). The control in ScholarChainHadoopSuite.scala:76-80 shows it.
- Path forms: any Hadoop FileSystem path (DblpExtractJob.scala:122). Checked.
- Phase 7 source and subset (input is the full .xml.gz, subset applied in J01): taken from hadoop's report. The subset defaults were checked at defaults.xml:102-133.
- Fix choice: (b), a code change, because (a) fails. The `-D` appended after the application options lands in `parse`, which takes `-D` as an unexpected argument (ScholarChainHadoop.scala:84-85). Test at ScholarChainHadoopSuite.scala:46-58. The new option is handled at ScholarChainHadoop.scala:29-37 and :72-74.
- Option equivalence: ScholarChainHadoopSuite.scala:60-81 shows the driver's `--dblp-dtd` run gives the same run IDs as setting `-D` directly (every job reused).
- Orchestrator point (2), every caller after "unknown options refused" (ScholarChainHadoop.scala:81):
  - sfn.tf:228 passes only `--output`, `--dblp`, `--dblp-snapshot` plus `extraArgs`. Its documented `extraArgs` (sfn.tf:32: `--dblp-fingerprint`, `--source`) are in `Single`/`Repeatable`.
  - run-bounded-pipeline.sh:880-904 adds nothing of its own, only the user's `--hadoop-extra-arg` values. `--dblp-dtd` and an `s3://{data-bucket}/raw/...` value pass its checks (:883-893). `need_value` accepts a value that starts with `--` (:297-299, :326).
  - Tests: PipelineSuite.scala:155-167 uses accepted options only. LocalPipelineRunner and LocalBoundedPipeline call `Pipeline.run` directly, not `parse`.
  - Nothing else in the repository calls the driver.
  - Result: checked, no caller broken.

Decision 6 (C-06, C-07), each item:
- Item 1, C-07: done.
  - Definite-refusal mapping at NeptuneAdapters.scala:61-72 and :106-120: class list AND 4xx, excluding 408 and 429.
  - `nodes-failed` with masked `startError` and `startHttpStatus`: StorageLoadMain.scala:644-681. A retry from it is accepted at :705-710.
  - Unknown outcome: the claim stays and the retry is refused (:718-719). The new text (:682-685) names the loader-list check and `neptune-bulk-status --load-id`, says a new export or config is the only other way, and has no "runbook".
  - The SDK makes one attempt (LoadStores.scala:78), and the bulk loader has no retry wrapper (AwsStorageEnvironment.scala:171-175), so a refusal can never be the second attempt of a request that already created a load.
- Item 2, C-06: done. `edges-starting` is written before the edges start (:722-724), a retry from it is refused (:716-717), `edges-failed` allows the retry (:711-715), and the final version follows the edges claim (:727).
- Orchestrator point (3), the state machine: I walked every state at :693-721.
  - None: nodes claim.
  - Any version with `edgesLoadId`: refused.
  - nodes-failed: new claim at version+1.
  - nodes-started or edges-failed with a nodes ID: edges only.
  - edges-starting: refused, naming the nodes ID.
  - starting, a state without a nodes ID, or an unknown state: refused.
  - Versions are zero-padded (TableSpecs.scala:255), so `latest` stays correct after 9 or more versions. Registration is a conditional `putIfAbsent` (ItemSupport.scala:114), so FailingRegistryKv's failure injection reaches the real path.
  - Edge cases: the BulkLoadIdNotFound loop is D-02; the untested "recording failed" branch is D-03.
- Item 3, tests: done, and by reading each one fails without the fix:
  - Definite nodes refusal, then a successful retry: the old code refuses the retry.
  - Unknown nodes outcome: the old text has "runbook" and no "outcome is unknown".
  - C-06 v3 write failure: the old code resumes from v2 and starts edges again, so the test sees Completed where it expects Refused.
  - Definite edges refusal: the old code writes no `edges-starting`/`edges-failed` states, so the states assertion fails.
  - Unknown edges outcome: the old code resumes and gives Completed.
  - NeptuneBulkLoaderStartSuite "definite": the old `AwsErrors.guard` throws StoreException.
  - The "unknown" suite test and ScholarChainHadoopSuite test 1 pass before and after the change. They are regression and characterization tests, which is acceptable.
- Item 4, no lock: recorded as an accepted risk. No code, checked.
- Item 5, retries: recorded as phase-16. No code, checked.
- Item 6, live-suite command: `storage/AwsTest/testOnly` exists (build.sbt:96-104). The variable names match runtime/main.tf:37-53 and AwsAdaptersAwsSuite (:15, :26, :137). No values. Checked.
- Item 7, gates: as stated in TEST RESULTS above.

Other checklist items:
- C-06 resolved: yes. C-07 resolved: yes. B-04 resolved: yes. I-25, I-26, I-28, I-29 and the call-table rows belong to R-2 (outside this slice).
- Secrets and identifiers: checked, none.
  - Fixtures use `example-graph-bucket` and `123456789012` (NeptuneBulkLoaderStartSuite.scala:65-68).
  - The registry stores only the class name and the HTTP status.
  - The service message `e.getMessage` reaches the report only through `masking(...)` (StorageLoadMain.scala:380-383). That is the same path as before (AwsSupport.scala:82).
- Scripts: no script is changed in this slice. No agent report claims to have run a script. The build agent's attempt to run `git check-ignore` on the `.env` path was blocked by the guard and not retried.
- Behavior change has a test that fails without it: yes for the main paths (above). Gap: D-03.

Note for R-2 (infra-engineer, not an R-1 finding): the record note at run-bounded-pipeline.sh:910, "the Hadoop jobs use their built-in defaults (-D scholarchain.code.version only)", becomes untrue once `--dblp-dtd` is passed. Hadoop's report already hands this over.

## Findings

| ID | Severity | Owner | Interface | File:line | Failure input | Impact |
|---|---|---|---|---|---|---|
| D-01 | minor | build-engineer | no | .gitignore:22 (`.DS_Store`), :19-21 (`.idea/`, `.vscode/`, `*.iml`), :2 (`target/`), :11 (`*.class`), all overridden by :83-84 | Finder opens experiments/raw/RUN/ on macOS (the human's platform) and writes experiments/raw/RUN/.DS_Store, or an IDE writes .idea/ there | The file shows as untracked and can be committed into the raw results experiment-engineer must keep immutable, or into releases/**. This follows decision 5's literal wording ("nothing under them is ignored"), so the human should decide. Possible fix: repeat the OS and IDE noise rules (`.DS_Store`, `.idea/`, `.vscode/`, `*.iml`) in the final block next to the secret rules. |
| D-02 | minor | storage-engineer | no | NeptuneAdapters.scala:108 (`BulkLoadIdNotFoundException` counts as definite); StorageLoadMain.scala:665 (message), :711-715 (retry from edges-failed reuses the registered `nodesLoadId`) | The edges StartLoaderJob gets 404 BulkLoadIdNotFoundException because Neptune no longer knows the registered nodes load (for example the cluster was replaced or its loader history is gone) | `edges-failed` is recorded and the message says "a new start of this graph run is allowed once the cause is fixed". Every retry sends the same missing dependency ID, gets the same 404, and appends two more versions. Nothing inside the graph run can fix it. The operator is sent in a loop instead of being told that a new export or config is needed. No double load can happen (safe direction). Fix: for a dependency-not-found refusal on edges, refuse with the "new graph run" text, or name that case in the message. |
| D-03 | minor | storage-engineer | no | StorageLoadMain.scala:666-669 | A definite refusal where writing `nodes-failed` or `edges-failed` itself throws (DynamoDB throttling) | The branch keeps the claim, so later starts are refused (safe), and it reports "recording … failed … a new start is refused". No test covers it: FailingRegistryKv can fail the `nodes-failed` write, but no test combines that with a definite refusal. If someone later changed the branch to rethrow or to record anyway, no test would catch it. The definition of done says every behavior change has a test. Fix: one test, `Map("nodes" -> definite)` plus `failRegistryStates = Set(StateNodesFailed)`, expecting Failed, then Refused, with states `[starting]`. |

Non-findings checked:
- The operator text reads "a edges start" (StorageLoadMain.scala:684). Cosmetic only.
- ScholarChainHadoopSuite test 1 passes before and after the change; it pins the EMR argument order that motivates option (b), which is acceptable.
- `getConf.set` of the DTD key before `Settings.withDefaults` takes effect (test :60-75 shows 2 accepted records).

No critical or major finding, so the verdict is APPROVE. The orchestrator should record D-01 to D-03 for triage (decision 11).
