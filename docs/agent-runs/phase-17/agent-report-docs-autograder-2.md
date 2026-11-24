STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17, session 3, E12 autograder follow-up, docs minors F7 and F8 (human-approved small repair pass, Markdown only)
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md (line 47 only; F7)
- /Users/brian/Developer/repos/scholarchain/reports/final-report.md (line 199 only; F8)
COMMANDS RUN (all read-only, all observed):
- `ls` / `sed -n 355,360p infra/terraform/modules/workflow/sfn.tf` -> file exists; line 358 is the `Args` line passing only `-D scholarchain.code.version=…` before `--output`.
- `sed -n 1436,1441p scripts/run-bounded-pipeline.sh` (read, not run) -> lines 1439-1440 are the run-record note "the Hadoop jobs use their built-in defaults ... (-D scholarchain.code.version only)".
- `sed -n 19,21p .../hadoop/pipeline/ScholarChainHadoop.scala` -> "generic `-D` options are only read before the first application option".
- `grep -n` for headings in reports/final-report.md -> `### 23.1 System and scope limitations` at line 606 (exactly 1 match); TOC uses GitHub-style anchors (`#1-problem-and-motivation`).
- `git diff -U1 -- docs/hadoop-job-contracts.md reports/final-report.md > .../scratchpad/phase17-reports/diff-autograder-docs-2.txt` -> exit 0, 15 lines.
- `git status --short` -> only the pre-existing modified/untracked set; no new file touched.
TEST RESULTS: not applicable (Markdown only; no sbt run, none needed).
VALIDATION RESULTS:
- Anchor check: `[section 23.1](#231-system-and-scope-limitations)` targets `### 23.1 System and scope limitations` (reports/final-report.md:606); GitHub slug drops the dot -> `231-system-and-scope-limitations`. Heading present, count 1.
- F7 cited files exist: infra/terraform/modules/workflow/sfn.tf, modules/hadoop/src/main/scala/org/scholarchain/hadoop/pipeline/ScholarChainHadoop.scala, scripts/run-bounded-pipeline.sh.
ASSUMPTIONS:
- The work order named the sfn.tf source as `infra/terraform/environments/course/sfn.tf:358`. I did not read that folder. The existing line 47 already cited `infra/terraform/modules/workflow/sfn.tf:358`, and I confirmed (read-only) that this file's line 358 is the EMR step `Args` with only the code-version `-D`. I kept the verified `modules/workflow` path rather than the work order's path. If the reviewer meant a different file, route back.
- Cross-reference style: the report uses plain "(section N)" in prose and GitHub anchors in its TOC; I combined both as a link labelled "section 23.1".
RISKS:
- docs/hadoop-job-contracts.md is untracked, so `git diff` does not include it; the saved diff file contains only the reports/final-report.md hunks (the F8 change plus my earlier 23.1 hunk). The F7 change is quoted below instead.
MANUAL ACTION REQUIRED: none (human commits as usual).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused re-check of F7 and F8.

## Fixes (exact locations)

- F7: /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md:47 — "so the recorded EMR runs used the default" now followed by: "(inferred from those two lines and from the bounded-pipeline run record note "built-in defaults (-D scholarchain.code.version only)", `scripts/run-bounded-pipeline.sh:1439-1440`; no EMR output listing was checked)". "Those two lines" are the already-cited `infra/terraform/modules/workflow/sfn.tf:358` and `hadoop/pipeline/ScholarChainHadoop.scala:19-21` in the same cell.
- F8: /Users/brian/Developer/repos/scholarchain/reports/final-report.md:199 — "(the reviewer checked every collect; each is small and bounded; one, `sizeBuckets`, grows sublinearly with the input, see [section 23.1](#231-system-and-scope-limitations))".

## Replayed commands and outcomes
None replayed (no commands documented or changed in this pass).

## Link-check results
One new intra-document anchor, resolved (see VALIDATION RESULTS). No new external or file links.

## Unverified claims
- The F7 statement stays an inference by design; no EMR output listing was checked (now stated in the text).

## Saved diff
/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-autograder-docs-2.txt (final-report.md hunks only; hadoop-job-contracts.md is untracked).

## INTERFACE HANDOFF
- Files changed: docs/hadoop-job-contracts.md (line 47), reports/final-report.md (line 199). Markdown only.
- Public signatures: none. Fixtures: none. Thread-safety: not applicable.
- Limits: no other line, file, code, script, Terraform or test touched; earlier uncommitted pass left as it was.
