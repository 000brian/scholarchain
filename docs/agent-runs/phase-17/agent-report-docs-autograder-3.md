STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17, session 3, E12, second TA-autograder run follow-up (instructor-approved): N1b, N2, N4, N5, one docs-only Markdown pass
FILES CREATED: none
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/README.md; /Users/brian/Developer/repos/scholarchain/docs/evaluation.md; /Users/brian/Developer/repos/scholarchain/reports/final-report.md
COMMANDS RUN: read-only grep, ls, awk (line ranges), wc, git ls-files, git diff --stat, git status --short; git diff -U1 -- README.md docs/evaluation.md reports/final-report.md > /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-autograder-docs-3.txt. Disclosure: one early read-only pipeline used `sed -n '1,200p'` to page grep output (no file write). The work order said not to use sed; it changed nothing, and I used no sed after that. No sbt, no network, no AWS, nothing under scripts/**, datasets/scripts/**, experiments/**, load-tests/** or .claude/hook-tests/**.
TEST RESULTS: not applicable (docs only; no source change)
VALIDATION RESULTS: link check passed. New links: docs/agents.md#artifact-ownership (the heading "## Artifact ownership" is at docs/agents.md:15); ../datasets/schemas/openalex.md, ../datasets/schemas/crossref.md and ../datasets/schemas/orcid.md (each exists and is tracked, git ls-files). git diff --stat: 3 files, 6 insertions, 2 deletions. git status: only the 3 files are modified.
ASSUMPTIONS: "Directly under" the agent catalog means between the table's last row and the existing "Who owns any path..." paragraph. N4 and N5 go in 23.1 as bullets. N4 sits after the provenance-gap bullet and N5 after the Spark-collect bullet, in the same "(autograder ...; documented in Phase 17 step E12, no ... change)" style. Section 23 has no table-of-contents entries per bullet, so no table of contents changed.
RISKS: In N2 the evaluation.md:566 table now mixes "10 of 10" with "k / n" ratios in other rows (562-565, 567), which were not to be touched. Its column header "k / n" (evaluation.md:560) is a table header and was left unchanged, as the order says (reported below).
MANUAL ACTION REQUIRED: human reviews the diff and copies this report with cp -n.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused check of the diff; human gate

## Fixes (exact new locations)

- N1b: README.md:90 ("**Built-in subagents.**" paragraph, directly under the agent catalog table, whose last row is README.md:88; table unchanged).
- N2: docs/evaluation.md:566 ("10 / 10" -> "10 of 10"); docs/evaluation.md:754 ("Agreement 20/20" -> "Agreement 20 of 20"; the "20 of 20" values already in words were unchanged). No other line changed.
- N4: reports/final-report.md:615 (23.1, "Dataset records are incomplete for the enrichment sources").
- N5: reports/final-report.md:621 (23.1, "Shortest paths and the diameter estimate cover the full window only").

## Ratios reported, not changed

- docs/evaluation.md:560, the table header "k / n". This is a header cell, and the order says to leave headers, and every line except 566 and 754, untouched.

## Read-only verification behind N4

- Crossref has no schema version: datasets/dataset-manifest.yaml:561-563 (the `schema` block holds only `notes` and `work_fields_selected`). The only version field in the manifest is `manifest_version: 1` (:16).
- Crossref has no real snapshot date: :540-542 (`snapshot.id: unknown`, with `id_unknown_reason`).
- Crossref has no observed size: :548-551 (two estimates and "208 GB compressed (Crossref blog 2026-03-17)").
- OpenAlex and Crossref schema notes have no schema version: `grep -n -i version` finds nothing in datasets/schemas/openalex.md (80 lines) or datasets/schemas/crossref.md (50 lines).
- ORCID notes give few types: datasets/schemas/orcid.md:26-41 list the kept fields by name only. Shape or type is given only for the `{ "value": ... }` wrapper and `publication-date` (:52-53).
- Not changed after the release candidate: this is the human decision named in the work order. I did not check it against a separate record.

## Read-only verification behind N5

- Window loop: modules/spark/src/main/scala/org/scholarchain/spark/Pipeline.scala:609 (`for (nw <- cfg.windows)`). The loop closes at :811.
- Paths and diameter on the full window only: Pipeline.scala:815-843 (stage 4 comment "(full window)", `val full = cfg.fullWindow`, `Paths.run(... full.window, full.label ...)`, path_evidence written for `full.label` only).
- `fullWindow`: modules/spark/src/main/scala/org/scholarchain/spark/JobConfig.scala:61-64.
- Paths.scala:39 (comment "full window only"), :130 (`shortestPaths` call), :164 (definition), :292-298 (batched BFS for the effective diameter).
- The list of per-window stages (edges, metrics, components, communities, k-core, bridges) comes from the existing final-report.md:197 summary and the Analytics.scala comments. I did not read the whole loop body line by line.

## Replayed commands and outcomes

None. No documented command was added or changed.

## Unverified claims

- The README note's statement "one general-purpose subagent ... for read-only research in Phase 17" comes from the work order (human-recorded decision). No repository record of it was checked.
- "Not changed after the release candidate (a human decision)" in N4 and N5 comes from the work order.

## INTERFACE HANDOFF

- Files changed: README.md (+2 lines), docs/evaluation.md (2 lines rewritten), reports/final-report.md (+2 lines).
- Public signatures, fixtures, thread-safety, limits: not applicable (documentation only).
- Diff: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-autograder-docs-3.txt
