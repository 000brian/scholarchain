STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), session 2, step 9 repair round 1: I-18, I-20, I-21, I-22, I-24 from review-report-c.md. I-19 and I-23 were not repaired, as the work order says.
FILES CREATED: none
FILES MODIFIED (all under scripts/**):
- /Users/brian/Developer/repos/scholarchain/scripts/smoke-test.sh (I-18)
- /Users/brian/Developer/repos/scholarchain/scripts/run-experiment.sh (I-20)
- /Users/brian/Developer/repos/scholarchain/scripts/lib/identity.sh (I-21 helper functions)
- /Users/brian/Developer/repos/scholarchain/scripts/plan.sh (I-21)
- /Users/brian/Developer/repos/scholarchain/scripts/deploy.sh (I-21, I-24)
- /Users/brian/Developer/repos/scholarchain/scripts/cleanup.sh (I-21)
- /Users/brian/Developer/repos/scholarchain/scripts/archive-evidence.sh (I-21: it reads the changed plan-record field)
- /Users/brian/Developer/repos/scholarchain/scripts/rollback.sh (I-22)
- /Users/brian/Developer/repos/scholarchain/scripts/run-bounded-pipeline.sh (I-24)
- /Users/brian/Developer/repos/scholarchain/scripts/README.md (I-20, I-21, I-22 texts)
COMMANDS RUN:
- shasum -a 256 on the work order; wc -l; ls
- grep -n (read-only) on scripts/*.sh, scripts/README.md, the work order and infra/terraform files. The Grep tool was not available in this session.
- shellcheck on the ten scripts and the helper (literal paths)
- terraform fmt -recursive -check infra/terraform
- terraform -chdir=infra/terraform/environments/course validate
- git diff --stat -- scripts; git status --short scripts
- Off-list command: one Bash call started with `cd /Users/brian/Developer/repos/scholarchain && grep -n ...` (read-only grep for line numbers). `cd` is not on the command list. It is reported here for the decision 15 summary.
- No script was run. No bash, bash -n, docker, aws or curl.
TEST RESULTS: No test framework exists for scripts, and no script was run. Each fix below has a before/after trace from reading the code.
VALIDATION RESULTS: shellcheck exit 0 (clean); fmt check exit 0; validate "Success! The configuration is valid."
ASSUMPTIONS:
- A1. name_prefix is "scholarchain-course": project "scholarchain" and environment "course" in infra/terraform/environments/course/main.tf:9-10; foundation/main.tf:14 builds the prefix from them. The bucket name patterns depend on this:
  - data: `bucket_prefix = "${name_prefix}-data-"` (data-lake/main.tf:31), so Terraform adds a suffix of 14 or more digits;
  - vector: `${name_prefix}-vectors-${account_suffix}` (vectors/main.tf:16), where the suffix is 8 hex characters (foundation/main.tf:19).
  The prefix is held as `readonly NAME_PREFIX` in plan.sh, deploy.sh and cleanup.sh. It is a prefix, not a bucket name.
- A2. That Terraform's generated suffix starts with at least 14 digits is from provider behavior (timestamp-based unique ID). It is unverified against documentation in this run.
RISKS:
- R1. account_sha256 is a fingerprint, not a secret. The account ID has 10^12 values, so someone holding a record can recover it offline. It meets the "persisted: no account ID" wording and keeps the ID out of casual view. Plan records are gitignored, and the archive copies only account_masked and the fingerprint.
- R2. Pattern masking can mask too much: any text shaped like `scholarchain-course-data-<14+ digits>` is shown as <data-bucket>. Nothing in the scripts parses Terraform's text output, so only the display changes.
- R3. Older plan and cleanup records (version 2, field account_id) are still accepted: deploy.sh:290-298 and cleanup.sh:505-516 fingerprint the legacy field without printing it. When such a record exists, archive-evidence.sh still learns the account from it for masking (unchanged).
- R4. rollback.sh writes its record only after a verified rollback, as deploy.sh does. There is no started or failed record, because releases/ is tracked. This differs from the evidence/ record rule in decision 9 and is the same approach deploy.sh already uses.
MANUAL ACTION REQUIRED: none from this repair. The human runs check.sh, plan.sh and the other scripts as decision 13 says. Plans made after this change carry account_sha256.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 10 re-check by code-reviewer of I-18, I-20, I-21, I-22 and I-24, and review of the ten changed files; the orchestrator then re-runs fmt, validate and shellcheck.

## Per finding

### I-18 (major), smoke-test.sh: RESOLVED by the fix
- Fix:
  - smoke-test.sh:228-233: check_prerequisites sets `BODY="${TMP_DIR}/body.json"` and `HEADERS="${TMP_DIR}/headers.txt"` in the parent shell, right after TMP_DIR is created.
  - smoke-test.sh:318-334: request() no longer assigns BODY or HEADERS. It returns 5 if they are empty, writes the two files, and prints the status.
- Before: `bash scripts/smoke-test.sh --question 'Q'` against a healthy API.
  1. check_health calls `status="$(get_with_retry /healthz ...)"`, and that calls `$(request ...)`.
  2. Both assignments happened in subshells, so the parent's BODY stayed "".
  3. `jq -r '.status // empty' ""` failed, and the script exited 6 at step healthz.
- After: BODY and HEADERS are set by the parent before the first request and inherited by every subshell. curl writes `--output "${BODY}"` and `--dump-header "${HEADERS}"` to those same paths, and the parent reads them. The call sites need no change:
  - check_health :406-414
  - check_ready :420-431
  - check_auth_error :452-463
  - check_query :471-488
  - check_evidence :502-511
  - check_idempotency :517-539 (HEADERS at :521)
  - get_with_retry :362-377
  - body_error :379-381
- Contracts kept:
  - check_key is still called before check_prerequisites (main :544-547). The check.sh probe still gives exit 3 before any network call.
  - The RUN-RECORD and SMOKE lines are unchanged.

### I-20 (minor), run-experiment.sh: RESOLVED by the fix
- Fix:
  - run-experiment.sh:281-298: check_local_prerequisites (git, jq, cut, grep, timeout or gtimeout, shasum or sha256sum).
  - run-experiment.sh:300-307: check_cloud_prerequisites (terraform, aws).
  - main :745-754 runs the cloud check after check_approval and before read_outputs.
- Before: check.sh's probe `run-experiment.sh --manifest experiments/manifests/example-not-approved.json --manifest-sha256 REAL` on a machine with no `aws` died 3 at step prerequisites (old line 279), before refuse-example.
- After: the local prerequisites pass, and the refusals run in their documented order. check_example (:333-338) dies 4 with "refusing: the manifest is an example". `aws` is never looked up.
- Usage "Requirements" and scripts/README.md:23 now say this. The step marker name changed from `prerequisites` to `prerequisites-local` / `prerequisites-cloud`, the same names deploy.sh uses. check.sh matches only the exit code and the refusal text (check.sh:847-848), so no probe contract changes.

### I-21 (minor), deploy.sh, plan.sh, cleanup.sh: RESOLVED by the fix
- Helper (scripts/lib/identity.sh):
  - :41-47 adds the SC_DATA_BUCKET_PATTERN, SC_VECTOR_BUCKET_PATTERN and SC_MASK_OUT variables.
  - :217-236 register_bucket_patterns NAME_PREFIX.
  - :238-259 sc_mask_patterns: a bounded loop, no subshell.
  - :261-282 account_fingerprint ID: sha256 of "scholarchain-account-v1:ID". It is domain-separated, so it is not the plain sha256 whose first 8 hex characters form the vector bucket's suffix.
  - mask_text :296-311 and mask_stream :313-331 also apply the patterns. With no pattern registered they behave as before, so the other scripts are unchanged.
- plan.sh:
  - read_account :287-296 registers the account and the bucket patterns and computes ACCOUNT_FP.
  - :320-323 the plan is now piped `2>&1 | mask_stream | tee .plan.txt`, so the terminal and the saved copy are both masked. pipefail still returns terraform's 0, 2 or 124.
  - Record :415-429: `account_id` is replaced by `account_sha256` plus `account_masked`; version 2 becomes 3.
  - :430-432 check_no_identifiers on the record, the summary and .plan.txt (exit 6).
  - Usage and exit-code text updated.
- deploy.sh:
  - find_plan :289-300 reads account_sha256 (legacy account_id is fingerprinted) and refuses (4) a record with neither. This is a local check before any cloud call.
  - check_account :340-349 registers the patterns and compares fingerprints.
  - apply_plan :453-456 pipes `| mask_stream | tee .apply.txt`.
  - verify_deployment :495-502 registers the real bucket names from the outputs, warns if .apply.txt still holds an identifier, and the release record check now covers both buckets.
  - Usage steps 2, 6 and 7 updated.
- cleanup.sh:
  - read_outputs :354-355 registers the patterns and ACCOUNT_FP.
  - :411-414 removal plan and :819-822 apply go through `| mask_stream | tee`.
  - Record :436-453 holds account_sha256 and account_masked (version 3) and is checked with check_no_identifiers on the record and .plan.txt (exit 6 in --list).
  - check_cleanup_account :502-518 compares fingerprints, with the legacy fallback.
  - Usage masking paragraph and exit 6 text updated.
- archive-evidence.sh:397-409 (a reader of the changed field) writes `account` from `account_masked`, or masks the legacy `account_id`, and copies `account_sha256`.
- Before: the first apply printed `Outputs:` with data_bucket_name and ARNs containing the account ID to the terminal and into .apply.txt, and plan records stored `account_id`.
- After:
  - every Terraform output line goes through mask_stream once the account is known. Account → <account>; the known bucket names, or any text matching the patterns (including buckets created during this apply) → <data-bucket> / <vector-bucket>.
  - Records hold only the fingerprint and the masked form.
- Contract change, stated: plan and cleanup record field `account_id` becomes `account_sha256` + `account_masked` (version 3). Every reader is updated: deploy.sh, cleanup.sh and archive-evidence.sh. find_plan_record (identity.sh:152) reads only kind and plan_sha256 and is unchanged.

### I-22 (minor), rollback.sh: RESOLVED by the fix
- Fix:
  - :112-115 `trap 'exit 5' INT`, `trap 'exit 7' TERM`.
  - :117-121 pre-load mask_text fallback; the helper replaces it.
  - :182-192 enter_repo_root refuses (4) outside the root and sources the helper. The `cd` is removed from main.
  - :240-245 the account is checked and register_identifiers gets the account and both bucket outputs.
  - :339-369 write_record names the file `releases/course/ATTEMPT_ID-rollback.json`, adds an `attempt_id` field (version 2), uses sc_utc_now, and writes through `write_record_atomic`. That function refuses an existing name, runs jq empty and check_no_identifiers, and links atomically. Its codes are mapped 4 / 6 / 5.
  - main :371-381 takes the attempt ID before any AWS call and prints `ATTEMPT:`.
- Before:
  - `bash /path/scripts/rollback.sh ...` from a subfolder did `cd` and ran.
  - A TERM from `timeout` exited 143.
  - The record name came from the time, was written with `>`, and was not checked for identifiers.
- After:
  - a subfolder gives exit 4 "refusing: run from the repository root";
  - TERM gives exit 7;
  - the record is named by attempt ID, written atomically, never overwritten, and checked.
- Contract change, stated: the rollback record name changes from `TIME-rollback.json` to `ATTEMPT_ID-rollback.json`. No script reads it, and scripts/README.md:49 is updated. `ROLLBACK-RECORD:` is unchanged; `ATTEMPT:` is new.

### I-24 (minor), run-bounded-pipeline.sh and deploy.sh: RESOLVED by the fix
- run-bounded-pipeline.sh:1117-1118: `conf_sha="$(check_artifact_commit ...)" || exit "$?"`.
  - Before: a Spark config in S3 with the wrong `scholarchain-commit` hit `die 4` in the substitution, and the parent's ERR trap turned it into exit 5.
  - After: exit 4. The other line in that stage that calls check_artifact_commit (:1116) is not a substitution. I checked every other `="$(...)"` in the file, and no other one calls a function that dies.
- deploy.sh:397-412: check_image calls `aws ecr describe-images` itself, with a timeout and stderr captured.
  - Before: a runtime-on plan whose image tag is not in ECR gave exit 5.
  - After: ImageNotFoundException or RepositoryNotFoundException gives exit 4 "refusing: image tag ... not found in ECR repository ... (run scripts/package.sh --publish first)". A timeout gives 7. Other errors are masked and give 5.
  - The AWS CLI error names come from package.sh:645, which already uses ImageNotFoundException. RepositoryNotFoundException is unverified against aws-docs in this run.

## Status lines of the changed scripts
- rollback.sh header: "static: shellcheck clean; structure reviewed: repo-root refusal, INT/TERM traps, attempt-ID record through scripts/lib/identity.sh; runtime: untested; human execution required."
- smoke-test.sh, run-experiment.sh, plan.sh, deploy.sh, cleanup.sh, archive-evidence.sh, run-bounded-pipeline.sh, lib/identity.sh: "static: shellcheck clean; runtime: untested; human execution required" (unchanged; still true).

## Call table changes (rows added or changed)
- deploy.sh:401-403 `aws ecr describe-images --repository-name --image-ids imageTag=` uses the same options as before. Error names: ImageNotFoundException (as package.sh:645), RepositoryNotFoundException (unverified).
- jq paths into plan records: `.account_sha256`, `.account_masked` (plan.sh:425-427, cleanup.sh:445), read by deploy.sh:292, cleanup.sh:508 and archive-evidence.sh:400 and :409. Legacy `.account_id` is still read (deploy.sh:295, cleanup.sh:511, archive-evidence.sh:383).
- rollback.sh:243-244 reads Terraform outputs `data_bucket_name` and `vector_bucket_name` (outputs.tf:101, :116).

## Proposed later
- run-bounded-pipeline.sh `list_keys` inside a process substitution (runs_under :843): a die there loses its exit code. This is not a finding and is out of scope.

INTERFACE HANDOFF
- Files changed: the ten listed under FILES MODIFIED.
- New helper functions: `register_bucket_patterns NAME_PREFIX` (status 0 or 2), `account_fingerprint ACCOUNT_ID` (prints 64 hex; status 2 or 3), `sc_mask_patterns TEXT` (sets SC_MASK_OUT; internal). mask_text and mask_stream keep their signatures.
- Record fields: plan and cleanup records are version 3, with account_sha256 and account_masked and no account_id. The rollback record is version 2, with attempt_id and the name ATTEMPT_ID-rollback.json.
- Probe contracts unchanged: deploy wrong-sha exit 4 at plan-lookup; run-experiment example exit 4 "refusing: the manifest is an example" (now without aws); smoke-test no-key exit 3; cleanup --list without archive exit 4; every --help exit 0. RUN-RECORD, RELEASE-RECORD, ROLLBACK-RECORD, PLAN-SHA256 and CLEANUP-PLAN-SHA256 lines are unchanged. No `# contract:` line was changed.
- Thread-safety: not applicable (single-process shell). Limits: sc_mask_patterns replaces at most 64 distinct matches per pattern per line.

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> exit 0; no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -> "Success! The configuration is valid."
- shellcheck: shellcheck scripts/archive-evidence.sh scripts/check.sh scripts/cleanup.sh scripts/deploy.sh scripts/package.sh scripts/plan.sh scripts/rollback.sh scripts/run-bounded-pipeline.sh scripts/run-experiment.sh scripts/smoke-test.sh scripts/lib/identity.sh (absolute paths) -> exit 0; clean
- jq: not applicable (no JSON file changed)
- finished: after the last command above
- files changed after these runs: no
