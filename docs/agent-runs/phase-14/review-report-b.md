STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-14/prompt.md (sha256 b1f951683cc708c297d3e74d6b21332f5218324e4db0086d33307ec8bf8b88a2), session 2, review R-2 (step 5 = decision 9 items 0-8). HEAD c4fbf62 plus uncommitted changes. Diff: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2e79420f-8018-4674-a178-e411c1ae4532/scratchpad/phase14-reports/diff-R-2.txt
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not applicable. No Scala changed in this slice, and no script has a test harness. Each changed script keeps "runtime: untested", which is honest.
VALIDATION RESULTS: I did not re-run anything (no Bash in my tool fence). The orchestrator re-ran and reports: fmt -check exit 0, validate "Success!", shellcheck exit 0 on the four scripts. That matches infra's VALIDATION EVIDENCE.
ASSUMPTIONS: (1) Per the orchestrator, no R-1 file changed since R-1. I did not re-diff those files. (2) I have no aws-docs tool, so the CLI and CloudWatch documentation citations are judged from the code and my general knowledge of the AWS CLI. I did not re-read the cited pages.
RISKS: (1) The `aws ecs wait services-stable` waiter gives up after about 40 polls at 15 s (about 600 s, AWS CLI default), before deploy.sh's STABLE_TIMEOUT of 1200 s. A slow first start therefore shows up as exit 6 ("failed (status 255)"), not exit 7. This is old code (deploy.sh:544-551); the counts are now printed. (2) Widget 7: see point (2) below.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 8 triage (decision 11). There is no critical finding and no interface-yes major, so nothing is held.

VERDICT: APPROVE

## Files read

Read in full (every file in the slice):
- /Users/brian/Developer/repos/scholarchain/infra/terraform/modules/observability/dashboard.tf
- /Users/brian/Developer/repos/scholarchain/scripts/README.md
- /Users/brian/Developer/repos/scholarchain/scripts/archive-evidence.sh
- /Users/brian/Developer/repos/scholarchain/scripts/deploy.sh
- /Users/brian/Developer/repos/scholarchain/scripts/plan.sh
- /Users/brian/Developer/repos/scholarchain/scripts/run-bounded-pipeline.sh (all 1-1364, in three reads)
- Reports: agent-report-infra.md, agent-report-hadoop.md, agent-report-rag.md, review-report-a.md (scratchpad phase14-reports); diff-R-2.txt

Read in part, for context:
- prompt.md (decisions 1-17 and the standing rules, lines 1-345)
- scripts/lib/identity.sh:165-499 (find_plan_record, register_identifiers, masking, check_no_identifiers, write_record_atomic)
- infra/terraform/modules/runtime/main.tf:90-296
- infra/terraform/modules/identity/emr.tf:19-48
- observability/variables.tf (Grep: map(string) types)
- identity/app-keys.tf (Grep: `env-var` tag at :42)
- workflow/sfn.tf (Grep: :32, :228)
- datasets/dataset-manifest.yaml (Grep: :393, :404, :406)
- Grep over infra/terraform for every jsonencode, templatefile and flatten
- Glob of /Users/brian/scholarchain-data/dblp (local file names)

Slice files not read: none.

## Checklist (decision 11)

Decision 9, item by item:
- **Item 0, I-29: resolved.** dashboard.tf:19-26 builds `local.sqs` and `local.dynamo` with `concat()` of two `for` tuples. Each element is `[ns, metric, dimName, dimValue, {opts}]`, and the variables are `map(string)` (variables.tf:43-46, :59-62).
  - I read every widget's `metrics`; each is a list of rows:
    - w0: :36-40
    - w1: :50-53
    - w2: :63-68 (options last)
    - w3: :78, `concat(local.ecs_cpu, local.ecs_mem)`; both are `[for …]` of rows, :15-16
    - w4: :88 `local.sqs`
    - w5: :98-101
    - w6: :110 `local.dynamo`
    - w7: :120-125
  - The other bodies infra listed, each checked by reading:
    - emr.tf:32: flatten gives a flat list of strings for an IAM condition's `values`, which is the right shape.
    - runtime/main.tf:96: ECR lifecycle `rules` is a list of objects.
    - runtime/main.tf:153 and :223: container_definitions are a one-element list. command is a list of strings; portMappings, environment and secrets are lists of objects; logConfiguration is an object.
    - Not opened by me: ingress.tf:160, workflow/main.tf:52 and :138, sfn.tf:519. My Grep shows they are jsonencode of objects with no flatten. I accept infra's reading for the sfn definition.
  - The Grep list equals infra's list; there is no templatefile.
- **Point (2), widget 7 `yAxis` inside the expression object (dashboard.tf:124): not a finding.**
  - In the CloudWatch dashboard body format, the expression row is a rendering-properties object; `expression` and `id` are themselves listed among the rendering properties, next to `label`, `yAxis`, `color`, `period` and `visible`.
  - JSON exported from the CloudWatch console routinely carries `yAxis` in expression objects.
  - The attempt-2 error pointed at `/widgets/4/properties/metrics/0` "Should be array"; widget 7's row is already an array, so it cannot fail that way.
  - Residual risk (unverified by me, no docs tool): if PutDashboard refuses it, D0a fails with exit 5 before the release record, and the fix is one line plus a new plan. There is no data or state loss beyond another plan and sign cycle.
  - Severity if it happens: minor. It does not need a pre-emptive change; decision 9.0 says "change nothing else".
- **Item 1, I-25: resolved.**
  - archive-evidence.sh:53: the default is `"not checked: the check did not run"`.
  - :558-560: with an empty `SC_ACCOUNT_ID`, the record says `"not checked: account unknown"`, never false.
  - :562-571: false or true only after the check ran.
  - S3 mode (:471-478): the account comes from sts and is checked to be 12 digits. It is held only in `SC_ACCOUNT_ID`, a non-exported shell variable. The `ident` and `account` copies are cleared, and the INFO line no longer prints even the masked last four digits.
  - info, warn and die mask through mask_text (:144-150). Records go through write_record_atomic, which runs check_no_identifiers.
  - The JSON-string value goes in via `--argjson` (:328, :576) and is valid JSON. See E-03 for a remaining narrow case.
- **Item 2, I-26: resolved.**
  - plan.sh:339-340 checks `.plan.txt` before the sha256, the summary or the record exist.
  - :415-416 checks the summary before it is printed.
  - :422-447 sends the record through write_record_atomic (identity.sh:448-482). That writes `.partial`, runs `jq empty` and check_no_identifiers, then hard-links to the final name. On failure only `NAME.record.json.partial` stays.
  - find_plan_record globs `*.record.json` (identity.sh:176), which never matches `.record.json.partial`. So deploy.sh cannot find a plan that failed the check (deploy.sh:285-286, exit 4).
  - The usage text (:61-65, :100-104) says so.
  - The `[ rc -eq 6 ] && die` line under `set -e` is safe: a failing `&&` head does not trigger errexit or ERR.
- **Item 3, I-28: resolved.**
  - deploy.sh:595-599: for query, evidence and graph, `desired < 1 OR running < 1` sets `failed=1`, and :644 then exits 6 with no release record. The values come from describe-services after the wait (:558, :568-569).
  - Zero tasks no longer pass vacuously: the digest branch (:621-627) only flags `desired > 0` with no task, and the new check covers desired 0 and running 0.
  - Counts are printed:
    - per failing service, an EVIDENCE line with desired, running and pending (:596-597);
    - for every service, a VERIFY line with desired and running (:634-635);
    - for every service when the wait fails or times out, print_service_counts (:501-512, :548), which prints names, counts and family:revision only, no ARN.
  - The README remedy (README:122) uses `--service ROLE`. That is correct: the ECS service `name = each.key` (runtime/main.tf:252). `desired_count` is in `ignore_changes` (:290-292), so the "no drift" claim holds. deploy.sh refuses an already-applied plan (:459), which matches the README.
- **Item 4, AWS CLI options: no option changed.** By my reading, every option the scripts use is a valid AWS CLI v2 option (docs not re-read by me). The deploy.sh and archive-evidence.sh line citations match the files. The run-bounded-pipeline.sh line citations are stale: E-02.
- **Item 5, DTD wiring, case (b): done.**
  - README:80 and :106 pass `'--dblp-dtd'` and then `'s3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp.dtd'` as two `--hadoop-extra-arg` values. That is verbatim the hadoop report.
  - The script carries them through:
    - parse :330 appends the value;
    - :884-901 resolves `{data-bucket}` and checks the raw/ prefix;
    - sfn.tf:228 appends extraArgs after the application options;
    - EMR can read raw/ (emr.tf:19, :48).
  - The upload key `raw/dblp/dblp-2026-11-01/dblp.dtd` follows from run-bounded-pipeline.sh:862 and the local basename `dblp.dtd` (local Glob).
  - `--max-upload-mb 1100` is enough: limit 1,153,433,600 bytes against 1,116,142,729 (manifest :393; check at :860-861).
  - The R-1 note is resolved: the record's `configuration.note` and `configuration.extra_args` (logical form) are at :916-920.
- **Item 6, README: done.**
  - (i) D0a-D20 match decision 12 step by step: D0a, D2, D4, D5, D6-D9, D10 (Spark ID, not the CSV folder), D11, D12, D13a-f, D14, D15, D18 and D19.
  - (ii) First-apply KMS note: :110.
  - (iii) Console entry by the `env-var` tag, which exists at app-keys.tf:42. The `read -rs` line is zsh syntax: :114-116 and D14.
  - (iv) Rollback policy: :120 (see E-04).
  - (v) Smoke questions A (q05) and B (q13): verbatim from agent-report-rag.md:35 and :50, with no `'` inside.
  - Every command is zsh-safe: single-quoted logical `s3://{data-bucket}/` values, and `${SHA:0:12}` works in zsh. No identifiers.
- **Item 7: checked.** The only Terraform change is dashboard.tf (Grep and diff).
- **Item 8: checked.** Per the orchestrator's re-run (see VALIDATION RESULTS).

Resolution of carried findings:
- I-25: resolved. I-26: resolved. I-28: resolved. I-29: resolved (by reading).
- C-06, C-07 and B-04 belong to R-1 (resolved there).

Secrets and identifiers: checked, none.
- No account ID, ARN, bucket name, queue URL or endpoint host in the diff or the README.
- The DTD sha256 has no run of 12 digits, so the human's `[0-9]{12}` commit scan stays quiet.
- The remedy's `--query` prints serviceName and desiredCount only.

Scripts:
- `set -Eeuo pipefail` and umask are unchanged.
- Every new AWS call goes through with_timeout (deploy.sh:503).
- Exit codes are as documented: plan.sh 6 or 5; deploy.sh 6 or 7.
- The status lines are honest ("runtime: untested"). run-bounded-pipeline.sh:9 still says "Status (Phase 13)": cosmetic.
- No agent ran a script. Infra reports one process slip: its first terraform command was prefixed with `cd REPO &&`, which the work order forbids. The agent disclosed it; it is not a code finding, and the orchestrator should record it as a deviation.

Each behavior change has a test that fails without it: not applicable. There is no script test harness, and decision 9 asks for none.

Call-table rows changed or cited: none changed. 15 rows cited; see E-02 for the stale line numbers in run-bounded-pipeline.sh.

## Findings

| ID | Severity | Owner | Interface | File:line | Failure input | Impact / evidence |
|---|---|---|---|---|---|---|
| E-01 | minor | infra-engineer | no | scripts/deploy.sh:101-111 (usage "Phase 14 order") | A reader runs `deploy.sh --help` in session 3 | The text still says step 1 is "deploy.sh with the plan signed at the Phase 13 gate", and it omits the pipeline-before-runtime-on-plan order and the attempt-3 dashboard plan. scripts/README.md:57-101 is now the authoritative order, so the help text contradicts it. Infra listed this under "proposed later". Fix: replace the paragraph with a pointer to the README. |
| E-02 | minor | infra-engineer | no | agent-report-infra.md item 4 table, run-bounded-pipeline.sh rows (cites :582, :617, :688, :771-772, :792-793, :802, :1033, :1173, :1187, :1266-1268, :1287) | Anyone follows the citations | In the current file the calls are at :586, :621, :692, :775-776, :796-797, :806, :1043, :1183, :1197, :1276-1278 and :1297. That is 4 or 10 lines off, because the citations were taken before infra's own edits at :100-105 and :914-920. The deploy.sh and archive-evidence.sh citations are correct. This is a report-accuracy defect only; no code is wrong. |
| E-03 | minor | infra-engineer | no | scripts/archive-evidence.sh:551-553, :562-566 | `--local-only` while a legacy plan record holds `account_id` | Only the account is registered (`register_identifiers "${LOCAL_ACCOUNT}" "" ""`), so check_no_identifiers cannot look for bucket names. Yet `byte_copies_hold_identifiers` becomes `false`, which reads as "no identifiers" when bucket names were never checked. This is outside the Phase 14 runbook (D18 uses S3 mode, where both buckets are registered at :475) and outside I-25's literal wording ("account unknown"). Fix: in local-only mode record `"not checked: bucket names unknown"` or name the scope (for example `"account only"`). |
| E-04 | minor | infra-engineer | no | scripts/README.md:120 against infra/terraform/modules/runtime/main.tf:281-284 | The first runtime-on deployment's new tasks fail (bad image, failing health check) | `deployment_circuit_breaker { rollback = true }` makes ECS itself roll back to the last completed deployment, which is the runtime-off placeholder revision (desired 0, so it completed). The README says "never roll back to a placeholder" but does not warn that ECS will do it automatically. The operator will then see placeholder revisions and failing pulls in deploy.sh's exit 6 or 7 evidence and may misread them. deploy.sh does fail correctly: revision differs from latest, exit 6, no record. Fix: one README sentence saying the D11 failure evidence may show the placeholder revision because of the circuit breaker, and that fix-forward still applies. No Terraform change is needed in this phase. |

Non-findings checked:
- Widget 7 `yAxis` in the expression object: see point (2); judged low risk, minor if it happens.
- `concat()` of tuples with different option-object types is valid Terraform (validate passed). jsonencode keeps each row as an array.
- plan.sh maps write_record_atomic's status 4 to exit 5. This cannot happen in practice, because read_git_state (:283-286) already refused existing names.
- D12 "/readyz answers 200 on empty stores" matches the rag report item 3.
- The README remedy is a human-run CLI mutation. It is explicitly asked for by decision 9.3 and gated on the human's go.

No critical or major finding, so the verdict is APPROVE. E-01 to E-04 go to triage as minors.
