# agent-report-infra-I1b

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, verified with shasum at start), step I1b, decision 7 items 2 to 5 (tier 2), plus the Phase 15 documentation minors routed to scripts/** by routing-p17.md (M-03, M-06, L-06 scripts part). HEAD 50ba087ecd33178eaa8ccc69286b9de85e8a8146. Producer: agent-report-infra-I1a.md (INTERFACE HANDOFF read).
FILES CREATED: none
FILES MODIFIED:
- scripts/emulator/env.sh (item 2)
- scripts/emulator/probes.sh (item 3, P16-B-03)
- scripts/deploy.sh (item 3, E-01; help text and header only)
- scripts/archive-evidence.sh (item 3, E-03)
- scripts/README.md (M-03, M-06, L-06 scripts part; env.sh lines of the emulator section)
- Not modified: scripts/check.sh and scripts/release-manifest.sh (sha256 unchanged from I1a's report: a892d065..., 09f61062...); I1a's README section "Release manifest" and the exit-code line (README:40) unchanged. No file under infra/terraform/**, config/**, .claude/** or outside scripts/**.
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-17/prompt.md (matched)
- read-only: grep over docs/agent-runs/phase-13..16 records, scripts/, experiments/manifests/, experiments/collectors/, infra/terraform/modules (never over infra/terraform/environments/course/; main.tf there read with the Read tool), .gitignore; git status --short; git diff --stat -- scripts/; git check-ignore -v (plans/ and local.auto.tfvars paths); ls; wc; shasum
- One Bash command was blocked by the guard (it named a Terraform state lock-file path inside a git check-ignore call); I did not retry it in that form and checked .gitignore lines instead.
- shellcheck --version; shellcheck -x -P SCRIPTDIR on the four changed scripts; shellcheck -x (and -S warning, -f gcc) read-only on the four hooks and two self-tests (item 4)
- terraform fmt -recursive -check infra/terraform; terraform -chdir=infra/terraform/environments/course validate
- Not run: any script (no --help either), bash, zsh, python, sbt, aws CLI, aws-api MCP, terraform init/plan.
TEST RESULTS: no sbt run (scripts only; standing rule). No script was executed: every change is `runtime: untested`.
VALIDATION RESULTS: shellcheck 0.11.0 clean on the four changed scripts (exit 0); terraform fmt -check exit 0; validate "Success! The configuration is valid." exit 0. Stop hook: not triggered at the time of writing (other agents' in-progress files: modules/rag/**, modules/storage/src/test/**, experiments/**, .gitignore, docs/**; none mine).
ASSUMPTIONS:
- L-06 (scripts part) is README:5's blanket "runtime: untested" line (source phase-15/runbook-log.md:113 "README status line 'runtime: untested' stale"); the emulator section's identical blanket line was corrected in the same pass.
- M-03's routed part is the README example only (routing-p17.md row M-03: scripts/README.md:195-196); the code rule in run-bounded-pipeline.sh (limit + 300) is unchanged (behavior change, not a documentation minor); the README now states the H-02 overrun and the collector's stricter rule.
- History lines in changed scripts cite runbook rows of earlier phases; per the Phase 17 rule the runtime line itself stays `runtime: untested` because the changed code never ran.
RISKS:
- env.sh: untested in zsh and bash (no shell may be run by an agent). The method uses only constructs both shells share (see INTERFACE HANDOFF); first execution is the human's next emulator run.
- E-03 changes the type of `byte_copies_hold_identifiers` in --local-only records from boolean false to a string when the account was known and nothing was found. No script or code in the repository reads that field (grep over scripts/, experiments/, docs/ outside agent-runs); a reader that expects a boolean would see a string (as it already could with "not checked: ...").
- plan.sh at E6 (item 5): a clean-tree run writes a usable plan record for a full re-create; it must never be signed.
MANUAL ACTION REQUIRED: none for executable bits (no new file; modes unchanged). Item 5's analysis is input for the human's E6 choice.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1 and F-1 (code-reviewer) review the four script diffs and the README diff; W5 consumes the INTERFACE HANDOFF (env.sh behavior, no check.sh change, item 5 analysis).

## Items

| Item | Status | Where |
|---|---|---|
| 7.2 env.sh clears SCHOLARCHAIN_CFG_* and prints a count | done | scripts/emulator/env.sh:80-106 (sweep), :8-13 and :22-31 (header), :55-58 (status line) |
| 7.3 P16-B-03 probes.sh unbound variable | done | scripts/emulator/probes.sh:198-202 (`[ -n "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR:-}" ]` before `-d`, the same guard as setup.sh:197); status :7-11 |
| 7.3 E-01 deploy.sh stale usage text | done | scripts/deploy.sh:113-117 (the "Phase 14 order" paragraph replaced by a pointer to scripts/README.md "Full reproduction, in order"); status :7-14 |
| 7.3 E-03 archive-evidence.sh --local-only wording | done | scripts/archive-evidence.sh:580-586 (string value instead of false), :54-58 (comment), :129-136 (help text); status :7-12 |
| M-03 README E example 1800 vs 2400 | done | scripts/README.md:166 (example with the signed manifest's args, `--wait-timeout 2400`), :167 (sizing rule: H-02 overrun 720 s, collector rule limit + 300 + 720 = 2220, exit 2) |
| M-06 README runbook table order | done | scripts/README.md:123 (intro no longer says "one at a time"), :125 ("Order in short"), :127-156 (rows reordered to decision 13's start order, new "When" column with every overlap; D9a split into part 1 before D7a and part 2) |
| L-06 scripts part, stale "runtime: untested" | done | scripts/README.md:5 (per-phase runtime facts with runbook-log sources; rollback.sh never ran; Phase 17 changes untested); :241 (emulator section status line) |
| 7.4 hook shellcheck evaluation | done (evaluation); no check.sh change | findings below; not only the known false alarm, so check.sh gate 4 unchanged |
| 7.5 read-only analysis of plan.sh and cleanup.sh --list | done | analysis below |

Runtime lines of every changed script (each script header):
- scripts/emulator/env.sh: `runtime: untested` (Phase 17 sweep never ran; the Phase 16 version was sourced once at E14 part 2, phase-16/runbook-log.md:58)
- scripts/emulator/probes.sh: `runtime: untested` (Phase 17 change never ran; Phase 16 version ran once, E14 part 4, runbook-log.md:61, exit line not pasted)
- scripts/deploy.sh: `runtime: untested` for the Phase 17 help-text change (earlier versions ran in Phases 14 and 15, e.g. phase-15/runbook-log.md:28 and :86, exit 0)
- scripts/archive-evidence.sh: `runtime: untested` for the E-03 change (earlier versions ran in Phases 13 to 15)
- scripts/README.md is not a script (its status line is item L-06).

## Item 4: hook shellcheck evaluation (read-only; `shellcheck -x`, version 0.11.0)

Command: `shellcheck -x .claude/hooks/format-scala.sh .claude/hooks/guard-dangerous.sh .claude/hooks/validate-agent-files.sh .claude/hooks/verify-tests-ran.sh .claude/hook-tests/guard-selftest.sh .claude/hook-tests/stop-hook-selftest.sh` -> exit 1, 33 findings (gcc format count). Default severity is what check.sh gate 4 uses (scripts/check.sh:504, no -S), so every finding would fail gate 4.

Severity warning or error (3):
- verify-tests-ran.sh:182:59 SC1087 (error): the known false alarm (`$t[\"']?` inside a regex string).
- guard-dangerous.sh:192:5 and :192:17 SC2088 (warning, x2): `'~/.claude'` and `'~/.claude/'*` are literal case patterns on purpose (the guard matches the text `~/.claude`, not the expanded path).

Severity info (30):
- format-scala.sh: none.
- guard-dangerous.sh (19): :165 SC2329 (is_constitutional never invoked); :192 SC2016 x4; :240 SC2016; :461, :511, :513, :533 SC1003; :589 and :936 SC2086 (`set -- $1`, deliberate word splitting); :639, :650, :1064, :1155 SC2016; :1080 and :1175 SC2020.
- validate-agent-files.sh (5): :136 SC2086; :147 SC2016; :181 SC2016 x2; :187 SC2012.
- guard-selftest.sh (6): :59, :145, :182, :186, :187, :245 SC2016 (literal test inputs).
- stop-hook-selftest.sh (1): :145 SC2016.

Decision (item 4 rule): the false alarm is not the only finding, so nothing changed: check.sh gate 4 still leaves the hooks out (README:36 unchanged). Every finding above goes to the final report's known limitations (constitutional files, factory-engineer). Most are intentional literal text; SC2329 at guard-dangerous.sh:165 (an unused function) is the only one that may point to dead code (not judged here). No file under .claude/** was edited.

## Item 5: read-only analysis for E6 (from the code only; nothing run)

Today's account (work order, "Where things stand"): Terraform resources removed by cleanup.sh on Nov 21; local Terraform state kept; IAM user scholarchain-readonly, budget scholarchain-25 and 20 ACTIVE task-definition revisions still present.

### `bash scripts/plan.sh` (no options)

Order (scripts/plan.sh:451-470): parse_args; enter_repo_root (refuses outside the root, 4); check_prerequisites (git terraform aws jq cut, timeout or gtimeout, shasum or sha256sum, AWS CLI v2, course.tfvars and .terraform.lock.hcl present, else 3; :238-257); read_git_state (:265-288); read_account (:290-301); terraform_init (:305-312); terraform_plan (:318-342); summarize_plan (:346-449).
- AWS and Terraform calls, all read-only:
  - `aws sts get-caller-identity` (:293), read.
  - `terraform init -input=false -lockfile=readonly` (:308): may contact the Terraform registry (providers are already installed by the human's init); no AWS call.
  - `terraform plan -input=false -lock-timeout=60s -detailed-exitcode -var-file=course.tfvars -out=plans/NAME.tfplan` (:326-328): plan mode never writes the state. With no managed resource left in the state, the refresh reads only data sources: aws_caller_identity, aws_partition, aws_region (infra/terraform/modules/foundation/main.tf:5-9), aws_availability_zones (modules/network/main.tf:10, ec2:DescribeAvailabilityZones), aws_default_tags and aws_iam_policy_document (computed locally). If any managed resource is still in the state, Terraform also reads it (Describe/Get), still read-only. The plan should list the whole environment as creates (Phase 13's first plan from an empty account was "175 create, nothing else", phase-13/human-gate.md:21; 175 resources were removed on Nov 21; the count at E6 is an estimate until run).
  - `terraform show -json` of the saved plan (:350), local.
  - Nothing applies; deploy.sh needs a committed `APPROVED-PLAN-SHA256:` line.
- Credentials: AWS_PROFILE or the default chain. The read-only profile scholarchain-readonly (ViewOnlyAccess) should be enough for sts and DescribeAvailabilityZones (unverified: no aws-api call allowed); the backend is local, so no S3 state access is needed.
- Writes: infra/terraform/environments/course/plans/PLAN_COMMIT-PLAN_ID.{tfplan,plan.txt,summary.txt,record.json} (or with `-dirty` on a dirty tree; :274-278), gitignored (.gitignore:74); .terraform/ (init, gitignored, .gitignore:59); Terraform's temporary local lock file next to the state during the plan (gitignored by the *.tfstate.* rule); a private temporary folder under TMPDIR, removed on exit. Nothing else in the repository.
- Prints: STEP/INFO lines, the account only as `********NNNN` (last four digits, mask_account) and the profile name (:300); the full plan output masked line by line (account as <account>, bucket names by the scholarchain-course patterns as <data-bucket>/<vector-bucket>; :326-328) and the summary (counts, IAM and resource-policy lines, network lines, stateful lines; :375-411); PLAN-SHA256, PLAN-COMMIT, PLAN-ID, PLAN-TREE-CLEAN (:461-464). Unmasked: resource attribute values such as tag values from local.auto.tfvars (for example the owner tag) and CIDRs; no secret value exists in Terraform.
- Masking: yes. check_no_identifiers stops with exit 6 and writes no record if .plan.txt or the summary still holds the account ID (:339-340, :415-416), and write_record_atomic checks the record (:444-447).
- Refusals it could hit: not at the root (4); a missing tool or AWS CLI v1 (3); no credentials or expired session: sts fails (5); Region of the plan not us-east-1 (4); a plan name that exists (4, practically impossible: new attempt ID). A dirty tree is not a refusal: the plan is named -dirty and marked review-only.
- safe and useful for E6: yes (all calls read-only, outputs gitignored and masked; it shows that the kept state holds nothing to keep and that the frozen Terraform still plans the full environment, which supports the "documented final state" item; run it on a clean tree with the read-only profile, and never sign the resulting plan, since a clean-tree record is usable by deploy.sh once signed).

### `bash scripts/cleanup.sh --evidence-archive PATH --list`

Order in list mode (scripts/cleanup.sh:967-982): parse_args; enter_repo_root; check_prerequisites (git terraform aws jq tar cut, timeout, shasum, AWS CLI v2; :287-304); check_archive (:324-335); check_clean_tree (:340-346); read_outputs (:354-372); then check_reports_archived, terraform_removal_plan, show_plan_json, list_targets, write_cleanup_record, which it never reaches today.
- check_archive: `tar -tf PATH` of the local archive (for example the Phase 15 archive in ~/scholarchain-evidence-p15/), local read only; prints the archive path, its entry count, its sha256 and its root folder name (:333-334). Refuses (4) a missing, empty or unlistable archive.
- check_clean_tree: refuses (4) a dirty tree.
- read_outputs: `terraform output -json` (:357) reads the local state only (no AWS call, no network). After the Nov 21 removal the state has no outputs, so `.vpc_id.value` is empty and it stops at :362-363 with exit 3: "no outputs in the local state (nothing deployed, or the state is missing: then clean up by tag, README)". This happens BEFORE `aws sts get-caller-identity` (:365) and before every listing.
- AWS calls: none (the sts call, the reports/ listing, terraform init and plan -destroy, the EMR, VPC-endpoint, ECR, S3, S3 Vectors and tagging-API listings are never reached). Terraform calls: only `terraform output -json`, read-only.
- Writes: only its private temporary folder (removed on exit); no plans/ file (terraform_removal_plan is not reached).
- Prints: STEP lines (evidence-archive, clean-tree, terraform-outputs), the archive INFO lines, HEAD and build commit, the ERROR line with exit 3 and `RESULT: cleanup.sh failed exit=3 step=terraform-outputs`. No account, masked or not (it stops before sts).
- safe and useful for E6: no (REASON: safe, since it makes no AWS call and writes nothing, but it refuses with exit 3 at terraform-outputs before any listing, so it gives no leftover inventory; the only fact it shows, that the local state has no outputs, E6's `terraform state list` count shows more directly; a live leftover listing would need the tagging-API read, which this mode reaches only when the state still has outputs).

## INTERFACE HANDOFF

Files changed: scripts/emulator/env.sh, scripts/emulator/probes.sh, scripts/deploy.sh, scripts/archive-evidence.sh, scripts/README.md. No public script option, exit code or output path changed. Thread-safety: not applicable (env.sh is sourced into one shell; the other changes are text or one guarded test).

env.sh behavior (W5): when sourced from the repository root it now, after unsetting the eight AWS credential variables and the four API-key variables, unsets every EXPORTED variable whose name matches `^SCHOLARCHAIN_CFG_[A-Za-z0-9_]+$` (any name, found at run time) and prints one line to standard output: `INFO: env.sh: cleared N exported SCHOLARCHAIN_CFG_* variable(s) (count only; no name or value is printed)`. Then it still unsets the fixed list of every name ConfigLoader knows (this also removes such names set but not exported; those are not counted). If awk is missing it prints `WARN: env.sh: awk not found; only the known SCHOLARCHAIN_CFG_* names below are cleared (count not available)` to standard error and clears only the fixed list. Return codes unchanged (0, 2 bash-executed, 4 not at root). The INFO line about the emulator endpoint follows as before. Method (bash 3.2 and zsh): one `awk 'BEGIN { for (k in ENVIRON) ... print k }'` process prints names only (values never leave awk; a value with a newline cannot fake a name); the names come back through a command substitution and a here-document into a `while IFS= read -r` loop, which runs in the current shell in both bash and zsh (no pipeline, no arrays, no word splitting, which zsh does not do by default); a `case` filter and `unset "$name"` with a counter `$((n + 1))` per successful unset; helper variables SC_EMU_CFG_COUNT, SC_EMU_CFG_NAMES, SC_EMU_CFG_NAME are unset afterwards. The header no longer says "It starts no program": it now names the one awk process. scripts/README.md emulator table step 8 shows the new first INFO line and says the Phase 16 hand step `unset -m 'SCHOLARCHAIN_CFG_*'` is no longer needed.

probes.sh (P16-B-03): with SCHOLARCHAIN_EMULATOR_RESULTS_DIR unset and an earlier safety rule failing, the script now prints "refused by the emulator safety contract: rule RULE (no AWS call made)" and exits 4 instead of aborting on an unbound variable.

deploy.sh (E-01): `--help` no longer lists the Phase 14 order; it points to scripts/README.md "Full reproduction, in order".

archive-evidence.sh (E-03): in --local-only mode with the account known from a legacy plan record and no identifier found, `byte_copies_hold_identifiers` (MANIFEST.json and NAME.json) is now the string "account only: no account ID found; bucket names not checked (--local-only)" instead of false; true when found; "not checked: account unknown" without an account (unchanged); S3 mode unchanged (true or false).

check.sh change: none (item 4's condition not met; gate 4 scope and README:36 unchanged).

Item 5 analysis: section above; plan.sh "safe and useful for E6: yes"; cleanup.sh --list "safe and useful for E6: no" (exit 3 before any listing).

Nothing I1a handed off was changed (release-manifest.sh, check.sh gate 9 probes, README "Release manifest" section, README:40 exit-code line). README:5 now mentions release-manifest.sh as `runtime: untested`, which matches I1a's status.

## Proposed later (not built)

- check.sh gate 4 for the hooks needs factory-engineer first (SC2088 and the info findings sit in constitutional files); a per-file exclusion list would have to name about ten codes, which item 4 does not allow.
- run-bounded-pipeline.sh's own `--wait-timeout` floor for rag-retrieval-eval (limit + 300) could adopt the collector's limit + 1020 rule (M-03's code part; a behavior change, outside this step).

## VALIDATION EVIDENCE

- `shellcheck --version`: version 0.11.0.
- `shellcheck -x -P SCRIPTDIR scripts/emulator/env.sh scripts/emulator/probes.sh scripts/deploy.sh scripts/archive-evidence.sh`: no output, exit 0 (after the last edit of every script).
- `shellcheck -x` on the four hooks and two self-tests (read-only, item 4): exit 1, 33 findings (3 at warning or error, listed above).
- `terraform fmt -recursive -check infra/terraform`: no output, exit 0.
- `terraform -chdir=infra/terraform/environments/course validate`: "Success! The configuration is valid.", exit 0.
- `git status --short` (mine): ` M scripts/README.md`, ` M scripts/archive-evidence.sh`, ` M scripts/deploy.sh`, ` M scripts/emulator/env.sh`, ` M scripts/emulator/probes.sh`; ` M scripts/check.sh` and `?? scripts/release-manifest.sh` are I1a's (sha256 unchanged); every other entry belongs to other agents.
- sha256 after the last edit: env.sh 36543675e1b46dce643eb9e721412f3e2ceb34b6fcbf8d54ca5a182b7838fff0; probes.sh fa0ad60e1f0d411535fe80aecf0ecb4b2f9d977752404564ad9beb56a86261ef; deploy.sh b1499e1c70683e989b2fdd13b3b4faa949d34e09541f913e3cd90e641a56f126; archive-evidence.sh 63aec0947e940868552181eb02ec8f8d7c17298005831c3621200daa511a2ea0; README.md 7af42e02709196e3e8460a7ce4ceb15d16024115ffb56f065294b4a14a3874b0; check.sh a892d0652af2fd104ce686fca33333df381824bd5bdad06e6664b3f885cbe1a0 (unchanged); release-manifest.sh 09f6106277d549ba251d4781d845b89042aee9f1045c02dfe45ab1179b5d54c2 (unchanged).
files changed after these runs: none
