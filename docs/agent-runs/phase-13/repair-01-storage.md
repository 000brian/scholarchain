STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), step 9 repair round 1, findings C-02 and C-04 (review-report-b.md); step-9 closer
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/test/scala/org/scholarchain/storage/load/StorageLoadMainLocalSuite.scala
(Both files are untracked since step 3; this round edited only these two.)
COMMANDS RUN:
- sbt -batch "storage/testOnly org.scholarchain.storage.load.StorageLoadMainLocalSuite" (before the fix: 3 failed of 13, as intended)
- sbt -batch "storage/testOnly org.scholarchain.storage.load.StorageLoadMainLocalSuite org.scholarchain.storage.load.StorageLoadMainArgsSuite org.scholarchain.storage.load.S3ExportSourceSuite org.scholarchain.storage.ExportSourceSuite" (after the fix)
- sbt check (output in scratchpad/sbt-check-repair01.log)
- sbt -batch packageCheck (output in scratchpad/packagecheck-repair01.log)
- read-only: git status, ls, grep, tail -n, head -n, shasum. One `cat /dev/null` slipped into a read-only progress check; it read nothing and wrote nothing, but `cat` is on the work order's banned list.
TEST RESULTS:
- Before the fix: StorageLoadMainLocalSuite 3 failed of 13 (the 3 new tests).
- After the fix: Passed: Total 38, Failed 0, Errors 0 (LocalSuite 13, ArgsSuite 9, S3ExportSourceSuite 10, ExportSourceSuite 6).
- sbt check: [success], "Updated .claude/.last-test-run (module_tests=2065, contract_tests=398)".
VALIDATION RESULTS: packageCheck: ok
ASSUMPTIONS:
- Resuming the edges start after a registered nodes start is part of the C-04 fix. Without it, a retry could only be refused and the edges load would have no tool path.
- Existing registry entries from step-3 code (a v1 entry with both IDs and no loadState) are treated as started and refused, as before.
RISKS:
- C-02: `dynamodb-load` now reads every export file twice (verify, then load). That doubles S3 GET bytes and read time for this subcommand (bounded export only; storage retries are Phase 14).
- C-02: a file swapped between verifyAll and the load read is still caught at end of file (VerifiedLines). It can only fail the run after writes, which is the old window; it needs a concurrent writer on a versioned key, which the layout does not do.
- C-04: if the process dies between the nodes StartLoaderJob and the v2 registry write, retries are refused (claim without a nodes ID). The operator then checks Neptune's load list per the runbook. This is deliberate: it never starts nodes twice.
- `run-report.json` gets the `store` section (writesPerTable) only on success, as before. The C-02 test reads the same counter through the injected stores.
MANUAL ACTION REQUIRED: none. Optional: the runbook could add the "claim without nodes load ID" case (documentation-engineer / infra-engineer scripts/README.md).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator evidence check (head -n 5 .claude/.last-test-run: module_tests=2065, contract_tests=398), then step 10 review of StorageLoadMain.scala and StorageLoadMainLocalSuite.scala

## Findings

- C-02 (major): fixed.
  - StorageLoadMain.scala:407: `if (verifyContent) src.verifyAll(manifest)` in `openExport`, after checkPresence and before any loader or store write.
  - :434: `dynamodb-load` passes `verifyContent = true`.
  - :468: `neptune-csv-build` keeps `false`; it builds locally and uploads only after the whole build. The review confirms it is not affected.
  - :417: the report's `input.checks` text names the checks that ran.
  - Test: StorageLoadMainLocalSuite.scala:423 "dynamodb-load: a same-size tampered export makes 0 writes in every table (C-02)". It changes one byte in the canonical_researcher part file, keeping the size. It asserts exit 1, a status that contains sha256, and `writesPerTable` keys equal to every configured table, all 0.
  - Fails without the fix: observed before the fix: "writes before verification: Map(example-artifact-registry -> 0, example-entity-link-cache -> 5, example-evidence-bundle -> 1, example-query-session -> 0, example-researcher-metric-cache -> 4, example-run-ledger -> 1)".
- C-04 (minor): fixed.
  - Registry protocol (append-only versions, conditional put of n+1):
    - :631: v1 `loadState=starting` claim before the nodes start.
    - :634: v2 `nodes-started` with nodesLoadId, right after the nodes start.
    - :657: v3 `started` with both IDs.
  - Retry behavior:
    - :645: a retry after v2 starts the edges load only, depending on the registered nodes ID.
    - :650: a claim without a nodes ID is refused (exit 2).
    - Both IDs present: refused, "already started", as before.
  - Any registry Conflict fails with the IDs this run started.
  - Tests:
    - :182 "neptune-bulk-start: an edges start failure, then a retry starts edges only (C-04)": run 1 exits 1 with only nodes recorded. Retry exits 0 with jobs [nodes, edges] and edges depending on local-load-0001, `resumed` true. A third start is refused with 2 jobs.
    - :216 "neptune-bulk-start: a failed nodes start is never retried blindly (C-04)": nodes start fails, retry is refused with "load ID was not registered", 0 jobs.
  - Fails without the fix: observed before the fix. :182 obtained List("nodes/", "nodes/", "edges/"). :216 retry exit 0 instead of 2.

## INTERFACE HANDOFF

- Class: org.scholarchain.storage.load.StorageLoadMain. `Options` (StorageLoadMain.scala:67-85), subcommand names, exit codes and print lines are unchanged. Example: `StorageLoadMain neptune-bulk-start --config C --stores aws --graph-run s3://{data-bucket}/graph/dataset=D/snapshot=S/run=HEX --loader-role-arn ARN --out O --code-version SHA`.
- New public vals: `StorageLoadMain.StateStarting = "starting"`, `StateNodesStarted = "nodes-started"`, `StateStarted = "started"` (the `loadState` attribute of `neptune-load` ArtifactRegistry entries).
- Run report:
  - `bulkLoad` gains `resumed` (bool) and `registryVersion` (number).
  - `bulkLoad.registry` is always "registered" on success; a conflict is now a failure status.
  - `input.checks` text differs for dynamodb-load.
- New print line on resume: `[storage-load] resuming: nodes load ID is registered (registry version N); starting the edges load only`.
- Registry: up to 3 versions per graph run (was 1). No script reads the registry (checked with grep over scripts/ and tests/).
- Thread-safety: unchanged (one invocation, one writer; the conditional appends make concurrent starters fail rather than double-start after v1).
- Limits: dynamodb-load reads the export twice. No fixture changes.

VALIDATION EVIDENCE
- targeted: sbt -batch "storage/testOnly org.scholarchain.storage.load.StorageLoadMainLocalSuite org.scholarchain.storage.load.StorageLoadMainArgsSuite org.scholarchain.storage.load.S3ExportSourceSuite org.scholarchain.storage.ExportSourceSuite" -> Passed: Total 38, Failed 0, Errors 0, Passed 38; 0 ignored
- full: sbt check -> "Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=2065, contract_tests=398)"
- package: sbt -batch packageCheck -> "packageCheck: ok"
- finished: after the last command above
- files changed after these runs: no
