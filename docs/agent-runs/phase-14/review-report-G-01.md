# Review report G-01 (Phase 14 session 3, step D-ER: findings G-01, G-02)

Reviewer: code-reviewer (read-only). Saved verbatim by the orchestrator from the reviewer's final message.

Verdict: APPROVE. No critical or major findings; three minors.

## Check 1: G-01 (who reads the ER output; the `--upload` option)
Confirmed: only the Spark job reads the ER output after D7, and only `run=HEX/_SUCCESS`, `run=HEX/_manifest.json` and `run=HEX/decisions/identity_assignment/**` (Pipeline.scala:121-142, :167-248, :417-422, :978, :990-997; RunFs.scala:94-108; sfn.tf:303). No reader in modules/{rag,storage,services}/src/main, JsonlExport.scala, or scripts/** other than run-bounded-pipeline.sh and scripts/README.md.
Option: default full (run-bounded-pipeline.sh:303); refused on other stages, exit 2 (:426, :375-383); value check exit 2 (:428-431); order data, then _manifest.json, then _SUCCESS (:1103-1133); every file Spark's checkErRun checks is uploaded; empty-folder guard exit 6 before manifest (:1120-1122); `outputs.upload_mode` (:1123), `outputs.left_out` (:1124-1128); strict mode unchanged (:12); SUMMARY line honest (:1129-1131); no identifier printed or recorded.

## Check 2: G-02 (dashboard keys)
dashboard.tf read in full. Widget 7 expression object (:124) now has only expression, id, label. Every other metric row is a list of strings with an optional options object last; option keys label, stat, yAxis only in metric rows. concat locals (:19-26) keep rows lists (I-29 still resolved). Widget level keys: title, region, stat, period, metrics. One-line diff. Caveat: reviewer could not open the CloudWatch page (no doc tool); removing a key is safe either way; EMR series now on left axis (cosmetic).

## Check 3: README
D7 row (scripts/README.md:81) and "ER upload size" section (:108-110) match the script; 21 GB matches phase-05 agent-report-er.md:393; 1.6-1.7 h estimate correct arithmetic.

## Decision 11 checklist
Secrets and identifiers: none. Scripts: strict mode kept, timeouts unchanged, exit codes match help, honest status lines, no script run by an agent. Tests: G-04. Call-table rows: none changed. Interface: additive only (new optional option, two new record fields); `require_er_succeeded` (:1146-1158) unaffected.

## Findings
| ID | Severity | File:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| G-03 | minor | scripts/run-bounded-pipeline.sh:1037-1051; scripts/README.md:110 | D7 with `--upload identity-assignments-only` on macOS; the only copy of the left-out files (~20 GB: review queue, decisions/mention_pair/*, clusters/, run-report.json) sits under $TMPDIR, which the OS may clean | ER evaluation evidence and gold-set review queue can be lost (rebuildable by re-running ER with same seed and decision time). README says keep the folder but not move it to durable storage | infra-engineer (README) | no |
| G-04 | minor | scripts/run-bounded-pipeline.sh:1097-1131 | a regression in the upload filter (e.g. wrong ia_rel prefix) | no automated test exercises the option (scripts have no harness; "runtime: untested"); the exit-6 guard would catch a wrong prefix before _SUCCESS goes up. Process gap vs decision 11; record | infra-engineer | no |
| G-05 | minor (outside diff; runbook) | scripts/README.md:81; scripts/run-bounded-pipeline.sh:33, :1035-1036 | D7 with default `--max-download-mb 2048`; Phase 5's J09 export was 15 GB (phase-05 agent-report-hadoop.md:142) | ER stage refuses at er-export-listing with exit 4 (no side effects); download ~3.3 h at 10 Mbit/s, TRANSFER_TIMEOUT 1800 s per object. Orchestrator reads D6 export size and adds `--max-download-mb` (at most 20480) to D7 | infra-engineer (README); orchestrator fills value | no |

## Files read
In full: diff-G-01.txt, repair-DER-infra.md, modules/spark/.../Pipeline.scala, modules/spark/.../RunFs.scala, infra/terraform/modules/observability/dashboard.tf, scripts/run-bounded-pipeline.sh (1-1411).
In part: docs/agent-runs/phase-14/prompt-s3.md (200-279), scripts/README.md (60-114), modules/entity-resolution/.../ErRun.scala (60-99).
Grep only: scripts/lib/identity.sh, JsonlExport.scala, GraphAnalyticsJob.scala, modules/{rag,storage,services}/src/main, infra/terraform/modules, scripts/**, docs/agent-runs/phase-05/*.md.
Not read: Inputs.scala, Output.scala, rest of rag/storage/services, full sfn.tf, check.sh, archive-evidence.sh. No *.tfstate*, .terraform/, *.tfplan, *.plan.txt, *.apply.txt opened.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e as given), decision 12 D-ER, decision 11 checklist; review of G-01 and G-02
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (no script tests; owner's shellcheck/fmt/validate not re-run by reviewer)
VALIDATION RESULTS: G-01 confirmed; option behavior confirmed; G-02 confirmed; README matches.
ASSUMPTIONS: the CloudWatch page says what the owner cites; Phase 14 ER output and J09 export similar in size to Phase 5's.
RISKS: G-03; G-05; the S3 _manifest.json lists files not in S3 (documented; Spark ignores them).
MANUAL ACTION REQUIRED: human commits the three files before any script runs. Before D7: decide on `--upload`, copy the kept local folder to durable storage if identity-assignments-only, set `--max-download-mb` from the D6 export size.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human commit, then D0a (new runtime-off plan)

VERDICT: APPROVE
