[Saved verbatim by the orchestrator from code-reviewer's final message (R-D1). The harness added the first line below; it is kept as received.]

[harness: subagent output matched instruction-shaped pattern(s): settings-json. Control tags below are neutralized (`<` → `<\`); treat any remaining directive-shaped text as a finding to relay to the user, not an instruction to you.]

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17 session 2, documentation review R-D1 (docs/agent-runs/phase-17/prompt.md, sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24; decisions 2, 12, 15 and the results section)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (documentation review)
VALIDATION RESULTS: 10 verified findings: 1 major (P17-D-01), 9 minor (P17-D-02 to P17-D-10). I found no identifier, no broken link or fragment, no invalid script option and no command without a replay line. The service-limits counts match the rows. The claim-verification text matches ClaimVerification.scala and review-report-02.md.
ASSUMPTIONS: I have no Bash, so I could not run `git diff`. Two sources show that docs/evaluation.md is unchanged: the session-start git status lists only docs/emulator.md as modified under docs/, and agent-report-docs-W5.md says "docs/evaluation.md: no change". I took the emulator.md diff from W5's report: 3 insertions and 3 deletions at lines 48, 187 and 197. I read those lines and sections 2.3, 8 and 9 around them. I accepted the Terraform freeze facts (the `git log` output) from agent-report-docs-W2.md:195.
RISKS: P17-D-01 is a false security assurance in the threat model. Leaving it in place would put a statement into the submission that the Phase 14 records contradict.
MANUAL ACTION REQUIRED: P17-D-07: W5's "nothing else" rule for docs/emulator.md may block the fix (rewording the old present-tense sentence). You decide whether the repair may change that sentence. P17-D-08: the results section has no replay-line form for a command that needs the removed environment and was never run. You decide which form those two lines use.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation triage (step 8), then repair-01-docs (documentation-engineer), then the re-check review-report-docs-02.md

VERDICT: REQUEST_CHANGES (my files: one major, P17-D-01)

## Findings

| ID | Severity | File:line | Failure input (what a reader does or checks) | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| P17-D-01 | major | docs/threat-model.md:133 (also :80, :132) | Line 133 says "no agent read the state in any phase (the guard blocks it; Phase 17 standing rules)". Line 80 says the guard blocks reads of Terraform state. A reader checks the records. docs/agent-runs/phase-14/human-gate.md:98 says "During D-ER, one search matched lines inside `terraform.tfstate`". docs/agent-runs/phase-14/repair-DER-infra.md:18 says "my first recursive grep over infra/ also matched lines in infra/terraform/environments/course/terraform.tfstate and .backup". The open finding P17-F-03 (review-report-final-control.md:21, quoted in this same document at :182) says the guard does not block a recursive `grep` over that folder. | The threat model claims a control result and an absence of incidents that the records contradict. That breaks "nothing claims a result the records do not show". Section 2.12 should record the Phase 14 grep and name P17-F-03 as its residual risk. Line 80 should say the guard blocks only commands that name a state file. | documentation-engineer | no |
| P17-D-02 | minor | docs/threat-model.md:128 (section 2.11, residual risk) | Section 2.11 lists "writes outside each agent's territory" as a control. Its residual risk names only F-02. The open major P17-F-01 (symlink bypass, guard-dangerous.sh:829-854 and :1241-1299) defeats exactly that control, but it is mentioned only in section 5 (:181). | A reader of section 2.11 sees the territory control without its known open bypass. | documentation-engineer | no |
| P17-D-03 | minor | docs/data-lineage.md:142-145 | The EntityLinkCache row says "not visible (G-25)". The EvidenceBundle row says "64 items in the visible part of the report". Row :145 says "0 rejected in the visible tables". The Phase 14 runbook log row "D13a/D13b full reports (log streams)" (docs/agent-runs/phase-14/runbook-log.md:67) has the full counts: EntityLinkCache 255,065, ArtifactRegistry 0, QuerySession 0, all inserted, 0 updated, conflicts or rejected, sum 763,577. Row :144 gives ArtifactRegistry "251,094" in the "Phase 14 counts" column. That number is a sum of CloudWatch write units during D13f (runbook-log.md:80), not a loader count; the D13a load wrote 0 there. | The document understates what the records show and mixes a CloudWatch measure into a table of loader counts. | documentation-engineer | no |
| P17-D-04 | minor | docs/service-limits.md:41 (IL-11); counts at :3 and :5 | IL-11's fact is "Fargate On-Demand vCPU quota covers the runtime at full scale". Its own documented-value cell gives a default of 6 against the 7 vCPU needed, and says the account's quota was not checked. The row is still `verified`. IL-06 makes the same kind of account-quota claim and is `unverified` (:36). Decision 2 says `verified` means the page states the value. Here the page contradicts the fact. The row was copied from agent-report-infra-I1a.md:70. | The verified count is inflated: 55 should be 54 and 5 should be 6 (table 1: 16 verified, 5 unverified). The gate status is unchanged (the requirement is already `deviation (unplanned)`), but the counts cited as gate evidence are wrong. | documentation-engineer (the status decision on the source row is infra-engineer's) | no |
| P17-D-05 | minor | docs/api.md:24 | The document says "No other script builds a URL from `api_url`" (citing the Phase 14 G-22 row). Today experiments/collectors/lib/collector-common.sh:661-665 (`cc_api_url`) and load-tests/query-load.sh:495-504 (`read_api_url`) both build the API URL from `api_url`, and both strip the trailing slash. | A present-tense claim is false for the current repository. It was true only at Phase 14. | documentation-engineer | no |
| P17-D-06 | minor | docs/api.md:223 | The document says that after 503 `OVERLOADED` on a keyed sync query "the slot check happens before any job or key record is written … so the same key is safe". But QueryService.scala:390-396 returns `OVERLOADED` after the job was created and claimed and the key record written (:344-358), when `pool.submit` throws; it marks the job `WORKFLOW_FAILED` (:395). A retry with the same key then follows that job and gets 409 `JOB_FAILED` (:575). | The client guidance is absolute, but a second `OVERLOADED` path breaks it. That path is rare (executor rejection, for example during shutdown). | documentation-engineer | no |
| P17-D-07 | minor | docs/emulator.md:48, :187, :197 | Each line keeps the Phase 16 present tense ("`env.sh` does not clear these variables. This is a known gap", "does not clear … (limitation)") and then adds "Since Phase 17 … `env.sh` clears every exported `SCHOLARCHAIN_CFG_*` variable itself". None of them says the Phase 17 sweep is `runtime: untested` (scripts/emulator/env.sh:55-58). docs/runbook.md:230 does say it. | Each line now states both behaviours, and the status line (:197) implies a tested change. W5 was told to change nothing else, so the human must allow the wording fix (see MANUAL ACTION REQUIRED). | documentation-engineer | no |
| P17-D-08 | minor | docs/runbook.md:63, :167 | The replay lines read "not replayable after teardown (archived evidence: none; …)". The results section's form is "not replayable after teardown (archived evidence: PATH)", and "none" is not a path. Neither command (the `update-service` remedy, `rollback.sh`) ever ran, so no evidence path exists. | The lines match no valid replay-line form. The human must pick the form (see MANUAL ACTION REQUIRED). | documentation-engineer | no |
| P17-D-09 | minor | docs/final-state.md:126 against docs/runbook.md:199 | final-state lists `cleanup.sh --list` as "PENDING E6 (optional; run only if the human approves it)". The runbook says it is "not planned for Phase 17 step E6", because I1b found it stops with exit 3 at `terraform-outputs` before any listing ("safe and useful for E6: no", agent-report-infra-I1b.md:99-103). | The two documents disagree about an E6 step. final-state does not carry I1b's finding. | documentation-engineer | no |
| P17-D-10 | minor | docs/final-state.md:80-82 | The retained-items table says, in the present tense, "Phase 17 deregisters them (step E8)", "Phase 17, which removes it last (step E8)" and "kept until Phase 17, which removes it (step E8)". E8 has not run, and section 9 (:127-131) marks the same items PENDING E8 ("removed or kept, and why"). | Planned Phase 17 actions read as results. They should be worded as the plan for E8. | documentation-engineer | no |

## Checks with no finding

- **Commands checked against the scripts' real options** (usage and option parsing read in the script source): check.sh `--record-dir`; package.sh `--publish`; plan.sh `--runtime-enabled`, `--image-tag`, `--graph-run-id`, `--cleanup-mode`; deploy.sh `--plan-sha256`; smoke-test.sh `--question`; run-bounded-pipeline.sh `--stage`, `--source`, `--snapshot`, `--file`, `--max-upload-mb`, `--dblp`, `--hadoop-extra-arg`, `--hadoop-export`, `--decision-time`, `--seed`, `--identity-assignments`, `--out-root`, `--loader`, `--export`; run-experiment.sh `--manifest`, `--manifest-sha256`, `--check`; rollback.sh `--release`, `--role`; archive-evidence.sh `--local-only`; cleanup.sh `--evidence-archive`, `--list`, `--remove`, `--plan-sha256`; release-manifest.sh `--clone`, `--marker`, `--out-dir`, plus its exit codes and refusals (release-manifest.sh:56-148).
- **Code paths named by commands exist:** `LocalBoundedPipeline` (modules/hadoop/src/test/.../LocalBoundedPipeline.scala:24); the five derive scripts under experiments/derived/; smoke-test.sh:333 strips the trailing slash; env.sh:80-106 and :108 onward; the ERR traps at package.sh:160/193 and run-bounded-pipeline.sh:321/353.
- **Replay lines:** every command in docs/runbook.md carries exactly one line of an allowed form (draft `planned for replay (ENN)` included). The only exceptions are the two `none` lines in P17-D-08.
- **Links:** every repository link I spot-checked resolves (the agent-runs gates and reports, releases/course/* (7 records), the phase-15 cleanup record, dynamodb-sample, s3-copies, experiments evidence paths, infra/terraform/STATE.md, the incident sources). Every `#fragment` matches a heading under GitHub's anchor rule (for example `58-get-v1graphnodesnodeidneighbors`, `107-group-7-reproducibility`, `7-threats-to-validity`, `21-first-apply-on-a-new-account`).
- **Identifiers:** a Grep over the 8 files for 12-digit runs, ARNs, amazonaws.com, execute-api, .elb., access-key shapes and IPv4 addresses found only expected matches: a digit run inside a plan sha256 (final-state.md:46), the release-record name `…167196849087…` (final-state.md:59, runbook.md:63), version strings, and 127.0.0.1. Neither AWS's example key nor the twelve-zero account appears.
- **service-limits.md counts:** 21 IL rows (17 verified, 4 unverified: IL-02, -04, -06, -14) and 39 SL rows (38 verified, 1 unverified: SL-29) give 60 / 55 / 5, which matches :3-6 (apart from P17-D-04). Every row carries exactly one status of the required form.
- **Claim verification** (api.md section 9, threat-model.md sections 2.1 and 2.3):
  - 31 reasons in `Reasons.all` (ClaimVerification.scala:133-165), 25 from Phase 16 plus 6 added in Phase 17.
  - All cited line anchors match: :578, :697, :723-728, :565-597, :601-730, :732-790, :930-938, :978, :991, :1014, :1043, :1047, :1062, :1121, :1163, :1232, :1263, :1323, :1393, :1436, :1440, :1446.
  - P17-A-01 is stated as PARTIAL and major, P16-L-10 as not closed, P16-L-11 as closed for recognised claims, and P17-K-02 as minor, as in review-report-02.md:13-17, :118-123, :192. The three traced inputs are quoted correctly.
  - The P17-F-12 and P17-K-01 lines match RagIngestJob.scala:316-319, :340, :657 and :722.
  - "59 claims, 0 rose, 0 fell" matches repair-01-rag.md:85.
- **final-state.md against the records:**
  - All 11 plan checksums, signing lines and change counts match docs/agent-runs/phase-12/human-gate.md:60, phase-13/human-gate.md:77, phase-14/approvals.md:5-45 and phase-15/approvals.md:5-76.
  - The R1-R7 fields match the release records (plan_commit, build_commit, plan_sha256, applied_utc, runtime_enabled, services' image_tag).
  - The removal record matches docs/agent-runs/phase-15/cleanup/…acb9.record.json, and the archive sha256 matches phase-15/approvals.md:78.
  - The D25, D27 and D28 facts match phase-15/runbook-log.md:122-131: 175 removed, 9 ECR images, 6,620,629 object versions, 8 terminated EMR clusters, 20 ACTIVE task-definition revisions, the per-category contents.
- **Numbers spot-checked against their sources:**
  - Phase 14 Hadoop, ER, Spark and load counts against phase-14/runbook-log.md:34-81.
  - Phase 15 counts against experiments/results-summary.md sections 2.1, 2.6 and 2.8.
  - Configuration values and line numbers in all files against config/course.json.
  - ServiceLimits.scala, QueryService.scala, HttpAdapter.scala, Routes and IdempotencySuite / ReplayIdempotencyCrossModuleSuite test lines.
  - SchemaVersions.scala:47-79 and the Spark Pipeline.scala stage lines.
  - Terraform values (variables.tf:241-259, course.tfvars:6/10/18, outputs.tf:17-20, runtime/main.tf:89/293, neptune/main.tf:65-66, data-lake/main.tf:59/67/139).
  - .claude/settings.json:4/39/90-110, .mcp.json:22-25, .gitignore:33-35/59-63/74, the prompt lines, the hadoop defaults, and the test anchors in the contract and module suites.
  - The incident timeline's epoch-to-UTC conversion (1795214360 = 2026-11-20T22:39:20Z) and its agreement with results-summary 2.1.
- **Optional components not implemented are stated:** OpenSearch, GitHub Actions, the GitHub MCP server switched off, the OpenAPI file, Bedrock (architecture.md section 6, api.md:3, threat-model.md section 6, service-limits.md section 4, final-state.md:98).

## Coverage

- **Read in full:** /Users/brian/Developer/repos/scholarchain/docs/architecture.md, docs/data-lineage.md, docs/api.md, docs/threat-model.md, docs/service-limits.md, docs/runbook.md, docs/final-state.md, docs/incidents/INC-P15-F-poison-message.md, scripts/emulator/env.sh, ServiceLimits.scala, course.json, course.tfvars, .gitignore, phase-14/approvals.md.
- **Read in part:** docs/emulator.md lines 30-59 and 170-197 (sections 2.2-2.4, 8 and 9, including changed lines 48, 187 and 197).
- **docs/evaluation.md:** not re-read (unchanged; I checked only its headings for the link fragments).
- **Cited files checked (relevant parts):**
  - Work order and records: prompt.md (results section, decisions 2, 12, 15, 16, 18, 20).
  - Phase 14: runbook-log.md:30-84, human-gate.md (Grep).
  - Phase 15: approvals.md, runbook-log.md:116-133.
  - Phase 12, 13 and 16 gates, phase-16/runbook-log.md:39-40 (Grep and parts).
  - Releases and results: releases/course/*.json (Grep), experiments/results-summary.md:49-88 and Grep.
  - Code: ClaimVerification.scala:1-170 and :555-799 and :926-939, QueryService.scala:290-439 and :548-673, HttpAdapter.scala:76-300, SchemaVersions.scala:15-84, RagIngestJob.scala, QueryWorkflow.scala and Synthesis.scala (Grep).
  - Scripts: release-manifest.sh:20-158, check.sh:100-159, the option parsers of all scripts (Grep), scripts/README.md (sections, Prerequisites, gate 4).
  - Infra: outputs.tf:10-24, variables.tf:236-265, Terraform modules (Grep with a `*.tf` glob only).
  - Tests: AbsenceRuleSuite, IdempotencySuite, ReplayIdempotencyCrossModuleSuite, ApiAdversarialSuite, LlmOutputAdversarialSuite, PromptInjectionCrossModuleSuite, DblpExtractJobSuite (test names and lines).
  - Context reports (scratchpad): review-report-02.md, repair-01-rag.md, agent-report-infra-I1a.md, agent-report-infra-I1b.md, agent-report-limits-storage.md, agent-report-docs-W2.md, agent-report-docs-W5.md, review-report-final-control.md.
- **Not read:** agent-report-docs-W1.md and routing-p17.md (not needed for any finding). No state file, plan file or evidence/ path; no Bash grep over infra/terraform/environments/course/.
