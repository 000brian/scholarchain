# Phase 13 repair round 2, infra-engineer (I-19, I-23)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7, verified with shasum), repair round 2: I-19, I-23 (review-report-c.md)
FILES CREATED: none
FILES MODIFIED: scripts/check.sh, scripts/lib/identity.sh, scripts/plan.sh, scripts/deploy.sh, scripts/cleanup.sh, scripts/README.md
COMMANDS RUN: shasum -a 256 (work order); grep -rn (Terraform file reads, script references; Grep tool not available in this session); ls; shellcheck -x -P SCRIPTDIR (changed scripts + helper, then the full gate-4 set); terraform fmt -recursive -check infra/terraform; terraform -chdir=infra/terraform/environments/course validate; git status --porcelain (read-only). No script was run, no bash, no sbt.
TEST RESULTS: not applicable (no sbt source changed; scripts are checked only with shellcheck)
VALIDATION RESULTS: shellcheck exit 0 (clean) on all 17 gate-4 files; fmt check exit 0, no files listed; validate "Success! The configuration is valid."
ASSUMPTIONS: the new gate-4 scope and the extended plan-validity rule are the human's decisions in the repair authorization (no further amendment text needed from me); the R-3 finding text for the hook false positive (verify-tests-ran.sh:182, SC1087) is taken as reported, not re-derived.
RISKS: SC_PLAN_INPUT_PATHS is maintained by hand; a future file()/templatefile() read outside infra/terraform that is not added to it reopens I-23 (comment at scripts/lib/identity.sh:36-50 and scripts/README.md:31 say so). check.sh gate 8 does not check this list automatically (proposed later below). Runtime of all changed scripts: untested (human execution required).
MANUAL ACTION REQUIRED: the human runs `bash scripts/check.sh` in a fresh clone (decision 13.3) to confirm gate 4 now passes; the orchestrator re-runs shellcheck, fmt check and validate (decision 10).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator evidence check (decision 10), then the step-10 review of the changed scripts, then the human gate.

## Item 1: I-19 (check.sh gate 4 scope)

- Fix: scripts/check.sh:467-483 (`gate_shell`): file loop now `scripts/*.sh scripts/lib/*.sh datasets/scripts/*.sh infra/terraform/modules/emr/files/bootstrap.sh`, with a scope comment at :469-472. Usage text: scripts/check.sh:70-74 (gate 4 line names the four sets and says .claude/** is covered by gates 1 and 3). scripts/README.md:34-36 new section "check.sh gate 4 (shellcheck) scope".
- Before: gate 4 also shellchecked .claude/hooks/*.sh and .claude/hook-tests/*.sh, so it failed on .claude/hooks/verify-tests-ran.sh:182 (SC1087) and check.sh exited 6 on today's tree.
- After: gate 4 shellchecks only the repository's own scripts; the same 17 files pass shellcheck today (exit 0). Gates 1 and 3 (validate-agent-files.sh, bash -n on every hook and self-test, both self-tests) are unchanged. Gate name, markers, record fields, exit codes unchanged.
- Phase-17 factory-engineer item: `PHASE-17 factory-engineer: make .claude/hooks/verify-tests-ran.sh shellcheck-clean (line 182, SC1087, false positive per R-3, plus the other warnings R-3/the orchestrator saw) or add a justified "# shellcheck disable=SC1087" there; then decide whether check.sh gate 4 should shellcheck .claude/hooks/*.sh and .claude/hook-tests/*.sh again (a scripts/** change for infra-engineer).` I did not edit .claude/**.

## Item 2: I-23 (plan-validity inputs)

### Grep results

Commands: `grep -rn -E 'file\(|templatefile\(|filebase64\(|filesha256\(|filemd5\(|fileexists\(|fileset\(|filebase64sha256\(' infra/terraform --include='*.tf'`; `grep -rln -E 'file\(|templatefile\(|filebase64\(' infra/terraform` (all file types); `grep -rn -E 'path\.(root|module|cwd)|source_file|source_dir|"\.\./|archive_file' infra/terraform --include='*.tf'`.

| Hit | Path it resolves to | Inside infra/terraform |
|---|---|---|
| infra/terraform/environments/course/main.tf:134 `file("${path.root}/../../../../modules/storage/src/main/resources/s3vectors/index-spec-v1.json")` (path.root = infra/terraform/environments/course) | modules/storage/src/main/resources/s3vectors/index-spec-v1.json | no -> added |
| infra/terraform/modules/emr/main.tf:21 `filemd5("${path.module}/files/bootstrap.sh")` (and :20 `source =` of the same file) | infra/terraform/modules/emr/files/bootstrap.sh | yes |
| infra/terraform/modules/vectors/main.tf:3 (comment mentioning `file()`) | none | n/a |
| infra/terraform/README.md (documentation text only) | none | n/a |
| `templatefile(`, `filebase64(`: no hits | n/a | n/a |
| module `source = "../../modules/..."` (environments/course/main.tf:24-220) | infra/terraform/modules/* | yes |

### Fix

- scripts/lib/identity.sh:36-50: new `SC_PLAN_INPUT_PATHS=(infra/terraform modules/storage/src/main/resources/s3vectors/index-spec-v1.json)` with a comment (what it is, why each outside entry is there, keep it complete). Header rule text :21-25.
- scripts/lib/identity.sh:128-163 `plan_usable`: `git diff --quiet PC HEAD -- "${SC_PLAN_INPUT_PATHS[@]}"`; on a change it lists the changed paths (`git diff --name-only`, standard error) and gives the reason "a plan input (...) changed between plan commit PC and HEAD; make a new plan". Status codes unchanged (0 / 4 / 5).
- Usage texts: scripts/plan.sh:65-75, scripts/deploy.sh:51-58 and :94-95, scripts/cleanup.sh:83-88; info lines scripts/deploy.sh:331, scripts/cleanup.sh:496 now name the plan inputs. scripts/README.md:31 rule text.
- Before: a commit after the signed plan that changed index-spec-v1.json kept the plan usable, so deploy.sh could apply indexes from the old spec.
- After: such a commit makes plan_usable return 4 (deploy.sh and cleanup.sh --remove refuse, exit 4, as for any infra/terraform change). Commits touching neither path still keep the plan usable. If the spec file is moved or removed, git diff shows it, so the plan is refused (fail closed).
- check.sh gates 8 and 9 stay consistent: gate 8 reads outputs.tf, PackageCheck and option constants (not touched); the gate-9 probes (help, deploy-wrong-checksum exit 4 `^ERROR: deploy\.sh .*exit=4`, run-experiment-example, smoke-no-key, cleanup-no-archive, archive-local-only, archive-layout) do not depend on the changed text. No probe exit code or message, RUN-RECORD/PLAN-SHA256 line or record field changed.

## Status lines (changed scripts)

- scripts/check.sh: static: shellcheck clean; runtime: untested; human execution required.
- scripts/lib/identity.sh: static: shellcheck clean; runtime: untested; human execution required.
- scripts/plan.sh: static: shellcheck clean; runtime: untested; human execution required.
- scripts/deploy.sh: static: shellcheck clean; runtime: untested; human execution required.
- scripts/cleanup.sh: static: shellcheck clean; runtime: untested; human execution required.

## Proposed later

- check.sh gate 8: grep infra/terraform for file()/templatefile()/filebase64() reads that resolve outside infra/terraform and fail when one is missing from SC_PLAN_INPUT_PATHS (makes I-23 self-checking; not in this repair's two items).

## INTERFACE HANDOFF

- Files changed: scripts/check.sh, scripts/lib/identity.sh, scripts/plan.sh, scripts/deploy.sh, scripts/cleanup.sh, scripts/README.md.
- Public signatures: new variable `SC_PLAN_INPUT_PATHS` (bash array, scripts/lib/identity.sh:47). `plan_usable PLAN_COMMIT` unchanged in signature and statuses (0 usable, 4 refused, 5 git failed); its refusal reason text changed and it now lists changed paths on standard error. `gate_shell` file set reduced (gate name, marker, statuses unchanged).
- Fixtures: none. Thread-safety: not applicable (sourced shell). Limits: SC_PLAN_INPUT_PATHS is a hand-kept list.

## VALIDATION EVIDENCE

```
VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -> "Success! The configuration is valid."
- shellcheck: shellcheck -x -P SCRIPTDIR scripts/archive-evidence.sh scripts/check.sh scripts/cleanup.sh scripts/deploy.sh scripts/package.sh scripts/plan.sh scripts/rollback.sh scripts/run-bounded-pipeline.sh scripts/run-experiment.sh scripts/smoke-test.sh scripts/lib/identity.sh datasets/scripts/download_dblp.sh datasets/scripts/download_openalex_snapshot.sh datasets/scripts/download_orcid_public_data_file.sh datasets/scripts/lib.sh datasets/scripts/validate.sh infra/terraform/modules/emr/files/bootstrap.sh -> 0; clean
- jq: not applicable (no JSON changed)
- finished: after the last command above
- files changed after these runs: no
```
