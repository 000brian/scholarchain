STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), session 2, step 10 re-check (decision 11), read-only
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not run (reviewer has no Bash). The storage repair's sbt results (38/38 targeted; sbt check module_tests=2065, contract_tests=398) are the repairer's claims. I did not see that output. Below I judge by reading whether each new test targets its defect.
VALIDATION RESULTS: no script was run. Every ruling comes from reading the code. The check.sh probe expectations (check.sh:833-855) were compared against the changed scripts and still match (see "Probe contracts").
ASSUMPTIONS:
- A1. Terraform's bucket_prefix suffix is 18 digits plus 8 hex (provider PrefixedUniqueId), so `SC_DATA_BUCKET_PATTERN` (identity.sh:230) matches it. I know this from provider behavior; it is not checked against documentation in this run.
- A2. The Neptune bulk loader's handling of a second edges load with the same relationship IDs is unverified. C-06's impact is stated without relying on it.
RISKS:
- A gitignored legacy Phase 12 plan text under infra/terraform/environments/course/plans/ (old name `<commit>.plan.txt`) still holds the unmasked account ID and vector bucket name. It predates the I-21 masking. It is not tracked, and archive-evidence.sh never copies `.plan.txt`. I do not repeat the values here.
- After the I-21 change, archive-evidence.sh `--local-only` no longer learns the account ID from plan records (I-25).
MANUAL ACTION REQUIRED: the human should not share or archive the legacy Phase 12 `.plan.txt` in the gitignored plans/ folder (it is unmasked). Removing it is the human's decision.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the orchestrator's step 11 final checks (fmt, validate, shellcheck, jq, git status); the summary lists I-19 and I-23 as open plus the new minors; then the human gate.

VERDICT: REQUEST_CHANGES

Why: all seven repaired findings are RESOLVED, and this re-check found no new critical or major finding (four new minors). Taken alone, the step-9 repair slice would be APPROVE. Rule 5, however, allows APPROVE only when no critical or major verified finding remains, and I-19 (major, factory-engineer or a human scope decision) is still open. I did not re-rule it, as the work order says. No second repair round follows, so the open items go to the summary and the human gate.

## Rulings

| ID | Sev | Owner | Ruling | Evidence (file:line) | Test fails without the fix? |
|---|---|---|---|---|---|
| C-02 | major | storage-engineer | RESOLVED | StorageLoadMain.scala:407: `if (verifyContent) src.verifyAll(manifest)` runs inside openExport. That is after checkPresence and before any loader is built or any store is written. :434 dynamodb-load passes `verifyContent = true`; :468 neptune-csv-build keeps `false`, which is correct because it builds locally and uploads only after the full build. verifyAll (ExportSource.scala:238-260) reads every file in full and throws on a byte-count or sha256 mismatch. RunLedger is not touched either, because the DynamoLoader is constructed after openExport. | Yes. StorageLoadMainLocalSuite.scala:423-448 flips one byte of the same size in canonical_researcher and asserts exit 1, "sha256" in the status, `writesPerTable` keys == `cfg.tableNames`, and every count 0. The counter is `kvStore.writeCount` (LoadStores.scala:51-52). The pre-fix code wrote to EntityLinkCache, EvidenceBundle and RunLedger, so the assertion targets exactly the defect. |
| C-04 | minor | storage-engineer | RESOLVED (for the stated impact: a second nodes load) | StorageLoadMain.scala:601-657: v1 `starting` claim (:631) before the nodes start, v2 `nodes-started` with nodesLoadId (:634) right after, v3 `started` with both IDs (:657). A retry at v2 starts edges only (:641-647). A claim without a nodes ID is refused (:648-651). Both IDs present is refused (:635-640). A registry Conflict is now a failure (:618-624). The nodes start can no longer run twice. The edges analogue is new finding C-06. | Yes. :182-214 fails the edges start once and asserts the retry's recorded jobs == `List("nodes/","edges/")`, `resumed` true, and a third start refused with 2 jobs; pre-fix code gives `nodes/, nodes/, edges/`. :216-236 fails the nodes start and asserts the retry is Refused with "load ID was not registered" and 0 jobs; pre-fix code gives exit 0. |
| I-18 | major | infra-engineer | RESOLVED | smoke-test.sh:232-233 sets `BODY` and `HEADERS` in the parent shell in check_prerequisites, after TMP_DIR exists. The load-time `BODY=""`/`HEADERS=""` (:320-321) run before main, so they do not overwrite this. request() (:329-358) no longer assigns them; it returns 5 if either is empty and only writes the files. Every reader in the parent (:409, :426, :456, :474-487, :505, :520-521, body_error :380) reads the same paths. check_key still runs before check_prerequisites (:545-546), so the no-key probe still exits 3 before any network call. | No automated test for scripts. By trace: before the fix the parent's `BODY` was "" and `jq ... ""` failed, exit 6 at healthz. The fix removes that path. |
| I-20 | minor | infra-engineer | RESOLVED | run-experiment.sh:281-297 checks local prerequisites only (git, jq, cut, grep, timeout, sha). :300-306 checks terraform and aws, and main calls it at :753, after check_approval (:752) and before read_outputs (:754). check_example (:333-338) dies 4 "refusing: the manifest is an example". Usage :154-159 and README.md:23 say the same. | The check.sh probe (check.sh:847) is the test. Its regex and exit code are unchanged and are now reachable without `aws`. |
| I-21 | minor | infra-engineer | RESOLVED | Terraform output is masked line by line: plan.sh:321-323, deploy.sh:455-456, cleanup.sh:412-414 and :821-822 all pipe `2>&1 \| mask_stream \| tee`. pipefail keeps terraform's 0, 2 or 124, and the `\|\| rc=$?` cases are unchanged. Patterns are registered once the account is known (plan.sh:292-294, deploy.sh:343-344, cleanup.sh:353-354). Records are version 3 with `account_sha256` and `account_masked` and no `account_id` (plan.sh:418-427, cleanup.sh:437-445), and are checked by check_no_identifiers (plan.sh:431, cleanup.sh:452). | No automated test for scripts. Statically traced. |
| I-22 | minor | infra-engineer | RESOLVED | rollback.sh:114-115 adds INT→5 and TERM→7 traps. :183-192 refuses outside the root (no `cd`). :243-244 registers the account and both buckets. :343-367 names the record `ATTEMPT_ID-rollback.json` and writes it through write_record_atomic, which refuses an existing name and runs jq plus check_no_identifiers. Its codes map to 4/6/5. The ATTEMPT ID is taken before any AWS call (:374). README.md:49 is updated. | No automated test for scripts. Traced. |
| I-24 | minor | infra-engineer | RESOLVED | run-bounded-pipeline.sh:1118 `conf_sha="$(check_artifact_commit …)" \|\| exit "$?"` keeps die 4: the subshell's exit status propagates and the parent's ERR trap is not reached. :1116 and :877 are plain calls, not substitutions. I checked every other `$(…)` call of a dying function (resolve_location at :874-876, :992, :1108-1112, :1121, :1123, :1203, :1208); all have `\|\| exit "$?"`. deploy.sh:400-412 calls describe-images directly; ImageNotFoundException or RepositoryNotFoundException gives exit 4, timeout gives 7, anything else is masked and gives 5. | No automated test. Traced. RepositoryNotFoundException is unverified against aws-docs (the repairer says so); in practice the repository comes from the Terraform output, so that branch rarely runs. |

## Requested consistency checks

- **Version-3 record readers and writers (account_sha256 / account_masked).** Consistent.
  - Writers: plan.sh:418-427, cleanup.sh:437-445.
  - Readers: deploy.sh:292-300 (falls back to legacy `account_id` and fingerprints it, never printing it; refuses 4 when neither field is present, before any cloud call); cleanup.sh:509-518 (same); archive-evidence.sh:383, :400, :408-409 (`account_masked`, or legacy `account_id` masked).
  - find_plan_record (identity.sh:152-175) reads only `kind` and `plan_sha256`.
  - No other reader in scripts/, tests/ or check.sh (Grep for account_id, account_sha256, account_masked).
  - The fingerprint is domain-separated (identity.sh:267-281), so it differs from Terraform's `account_suffix = substr(sha256(account_id),0,8)` (foundation/main.tf:19).
- **Does mask_stream hide output a human needs to verify a plan?** No.
  - Only the caller's own account ID and this environment's two bucket names or shapes are replaced, so a foreign account in a policy or ARN stays visible.
  - The caller account is shown as ********NNNN (plan.sh:295, :362).
  - Replacements of the buckets and other stateful resources are flagged separately in the summary ("ATTENTION stateful delete or replace", plan.sh:398-400), which is not masked except for the account.
  - Risk accepted by the repairer (R2): unrelated text shaped like a bucket name is also masked; this is display only.
- **Probe contracts** (check.sh:833-855) still match:
  - every usage text starts with `Usage: scripts/NAME.sh`, and `--help` exits 0 before the root check in all ten;
  - deploy wrong sha gives `ERROR: deploy.sh step=plan-lookup exit=4` (or clean-tree exit=4);
  - run-experiment example gives 4 with "refusing: the manifest is an example", without aws;
  - smoke-test without the key gives 3 with the message naming SCHOLARCHAIN_SERVICE_API_KEY (:205);
  - cleanup `--list` without an archive gives 4 "…--evidence-archive…" (:250);
  - archive `--local-only` layout is unchanged.
- **Marker lines.** RUN-RECORD (smoke-test.sh:284, run-bounded-pipeline.sh:753), RELEASE-RECORD (deploy.sh:649), ROLLBACK-RECORD (rollback.sh:368), PLAN-SHA256 (plan.sh:446) and CLEANUP-PLAN-SHA256 (cleanup.sh:454) are present in the expected form. `# contract:` lines exist at run-bounded-pipeline.sh:1044, :1213, :1216, :1221, :1224, :1226 and :1230. I cannot diff them against the pre-repair text without git, but their content matches StorageLoadMain.Options (StorageLoadMain.scala:71-89), which is unchanged.

## New findings

| ID | Severity | Interface | Owner | File:line | Failure input | Impact |
|---|---|---|---|---|---|---|
| C-06 | minor | no | storage-engineer | modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala:654-657 (edges start with no claim before it; v3 is written only after it) | `neptune-bulk-start` where the edges StartLoaderJob succeeds but the v3 `register` throws a non-Conflict error (DynamoDB throttling or a network error), then a retry. | The retry finds v2 (`nodes-started`, no edgesLoadId) and starts a second edges load of the same graph run (:641-654). This is the edges version of C-04, which the repair closed only for nodes: the claim-before-start pattern used for nodes (:631) is not applied to edges. No test covers a registry failure after the edges start; :182 covers only an edges start failure. Neptune's outcome for a duplicate edges load is unverified (A2), but at least a duplicate job runs and the registry never shows which edges load ID counts. Fix: write an `edges-starting` version before the edges start and refuse a retry from it, as for nodes. Add a test with a KV store that fails the v3 write. |
| C-07 | minor | no | storage-engineer (code); runbook text: infra-engineer (scripts/README.md) or documentation-engineer | StorageLoadMain.scala:648-651 (refusal text "Check Neptune's load list (runbook) before any new start"); test :216-236 | First `neptune-bulk-start` whose nodes StartLoaderJob fails for certain, for example Neptune rejects it because the loader role is not yet associated (a common first-run error). | v1 `starting` stays as the latest version, and every later start of that graph run exits 2. No runbook exists (Grep for "load list", "nodes-started" and "loadState" finds only code and old phase reports), and the registry is append-only with no tool to move it on. The graph run ID is derived from the export sha256 and the config fingerprint, so rebuilding the CSVs gives the same locked run. The only ways out are an ad-hoc DynamoDB write (not an allowed implementation path) or a new export or config. The repairer chose this (RISKS: "deliberate"), but the error message points to a document that does not exist. Fix: record `nodes-failed` when the start call returned a definite client error, and allow a retry from that state; or ship the runbook plus a subcommand that appends a reset version after human confirmation. |
| I-25 | minor | no | infra-engineer | scripts/archive-evidence.sh:383, :387, :538-548 (with plan.sh:418-427 and cleanup.sh:437-445) | `bash scripts/archive-evidence.sh --local-only` when every plan record is version 3 (made after the I-21 repair). | `LOCAL_ACCOUNT` is learned only from legacy `account_id`, so it stays empty and no identifier is registered. check_no_identifiers then returns 0 trivially (identity.sh:391 `continue`), and MANIFEST.json and the archive record state `byte_copies_hold_identifiers: false` without any check having run. Before I-21 this check worked because every plan record held the ID. This is an unsupported claim in an evidence artifact, not a leak. Fix: write `null` or "not checked: account unknown" when nothing is registered, or take the account from `sts get-caller-identity` when credentials exist. |
| I-26 | minor | no | infra-engineer | scripts/plan.sh:406-432; scripts/cleanup.sh:430-453 | A plan whose `.plan.txt` still contains the account ID (for example a pattern miss) makes plan.sh exit 6 at :431-432. | The `.record.json` is already written with `plan_sha256`, and the summary printed at :404 already showed the sha256. So a plan that failed the identifier check stays findable by deploy.sh (find_plan_record) and can still be signed and applied. The identifier problem is in the text copy, not the plan, so the harm is small. But "exit 6" does not make the plan unusable, and the usage text (:95-99) does not say so. Fix: run check_no_identifiers on `.plan.txt` before writing the record (or write the record through write_record_atomic after the check), or state in the usage text that exit 6 leaves a usable plan. |

## Coverage

Files read in full:
- modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala
- modules/storage/src/test/scala/org/scholarchain/storage/load/StorageLoadMainLocalSuite.scala
- scripts/lib/identity.sh
- scripts/plan.sh
- scripts/deploy.sh
- scripts/cleanup.sh
- scripts/archive-evidence.sh
- scripts/smoke-test.sh
- scripts/run-experiment.sh
- scripts/rollback.sh
- scripts/run-bounded-pipeline.sh (in three parts, 1-1354)
- scripts/README.md
- the reports: repair-01-storage.md, repair-01-infra.md, and the finding rows of review-report-b.md (C-01..C-05) and review-report-c.md (I-18..I-24)
- work order: standing rules (:59-117), decisions 9-11 (:310-459)

Files read in part:
- scripts/check.sh: gate 9, probes (:726-867), plus a Grep for probe and expect lines
- modules/storage/.../source/ExportSource.scala: :206-263 (manifestWithSha256, checkPresence, verifyAll)
- modules/storage/.../dynamo/LedgerAndRegistry.scala: ArtifactRegistry, :101-129
- modules/storage/.../load/LoadStores.scala: Grep hits for writesPerTable
- modules/storage/.../StorageConfig.scala: tableNames
- infra/terraform: Grep for bucket_prefix and the vectors bucket name pattern
- .gitignore: the plans entry

Listed files not read: none. repair-01-storage.md lists only the two storage files.

Not re-ruled, as the work order says: I-19 (major, factory-engineer, open), I-23 (minor, needs a human rule amendment, open), and minors B-01..B-04 and C-05 (recorded only).
