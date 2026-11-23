# agent-report-docs-W4a (documentation-engineer, Phase 17 session 2, step W4a)

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, verified with shasum at start and end), step W4a, decisions 2, 12, 14, 17; reports/final-report.md sections 1 to 12, sections 13 to 25 as headings marked PENDING W4b
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/reports/final-report.md (319 lines, sha256 0a9dbb54a2bb55a07e7f3881eb75654d064a9081575a1f8740dc42d49ff06127)
  (scratchpad) phase17-reports/agent-report-docs-W4a.md (this report); scratchpad w4a-targets.txt, w4a-frags.txt (link-check working lists, not reports)
FILES MODIFIED: none in the repository (README.md and every docs/** file untouched)
COMMANDS RUN: read-only only: shasum, git status --short, git ls-files, git check-ignore -v, ls, ls -lT, wc, head, grep, cut, and Read/Write/Edit tools. No sbt, no script, no python, no terraform, no network, no AWS, no MCP call. Commands outside the Phase 17 list: see RISKS.
TEST RESULTS: none run (W steps never run sbt)
VALIDATION RESULTS: interface check against step 1 passed (below). Link check: 110 repository link occurrences, 50 unique targets, all exist, none ignored, 44 tracked + 6 created in Phase 17 (W1, W2, W3); 40 fragment links into 4 files (13 unique anchors) and 12 report-internal anchors, all match a heading. External links: 0 (one plain-text URL inside a quoted license attribution). Identifier scan: expected match only (Neptune engine version 1.4.7.1).
ASSUMPTIONS:
  - The deviations are placed as decision 14 allows: an overview table in section 1.1 (with the teardown quote and the public-repository note), Scala 2.13 in section 9, the model endpoint in section 12.4 with the Bedrock quota 0 reason from Phase 15 D8, the budget/account/dataset approvals in section 3 and 12.4; W4b extends teardown (14, 15, 23, 25) and budget (20).
  - The authorship categories are a dedicated subsection 6.1 ("sections 4 to 6 or a dedicated subsection").
  - instructor-approvals.md is summarised with the channel (Microsoft Teams) and quoted verbatim for the instructor's answer; the name in its "Received by" field is not repeated (a third person's name is not needed for the claim). The report states that the approval answered another student's question and that the architect applies it because his account had the same restriction (that file; Phase 9 gate).
  - Counts of human-authored files (21 tracked work-order files, 18 tracked human-gate.md files) are from `git ls-files` at the time of writing; Phase 17 records are not yet tracked.
  - The Phase 17 work order, its course-specification copy and agent-report-rag.md are named as plain-text paths (not links): they are not yet in Git.
RISKS:
  - Unplanned deviations (read-only, listed for the gate): (a) `cd <repo> &&` prefixes on the first three Bash calls (the Phase 17 list forbids `cd`); (b) `tr` and `awk` in one read-only link-check pipeline (forbidden by the list; no file written by them); (c) a `while read` loop over the target list. No other forbidden command.
  - Every L2 statement in section 12.2 follows step 1's handoff; ClaimVerification.scala changed at 18:48:17 (rag repair-01 in progress, 57870 -> 66179 bytes), so the cited line range 892-1268 is marked "as handed over at step 1". W5 reconciles (2 `PENDING W5` marks).
  - Section 3 states that the enrichment datasets reached only the manifest and the local bounded pipeline on real samples, never the AWS runs (Phase 5 gate deferral, never scheduled). This bears on the spec baseline item "DBLP plus at least three additional datasets ... in the manifest and actual bounded pipeline"; the report leaves the judgment to the human gate.
  - Section 10 reports the group 2 provenance-sample gap (ResearcherMetricCache and EntityLinkCache items lack dataset, source record IDs and schema version on 0 of 25) as a measured fact against the constitution's provenance invariant.
  - Record discrepancy repeated from W3: instructor-approvals.md has no entry for the "make the repository public at the deadline" request; section 1.1 says so.
MANUAL ACTION REQUIRED: none for W4a.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: W4b (sections 13 to 25); W5 reconciles section 12.2 with step 1's final handoff after repair-01-rag and the re-check; R-D3 reviews reports/final-report.md.
```

## INTERFACE CHECK (producer: step 1, agent-report-rag.md INTERFACE HANDOFF; file read at 17:43:24 state)

| Name used in the report | Exists as handed over? | Where checked (file:line at the time of the check) |
|---|---|---|
| `Reasons.AbsenceAmbiguous` (new, additive) | yes | ClaimVerification.scala:117, in `Reasons.all` :144 |
| `Reasons.AbsenceNoDepth` (now unbacked) | yes | :110 |
| `Reasons.AbsenceNotSearched` | yes | :112 |
| `Reasons.HopsConflict` | yes | :88 |
| `private[runtime] object AbsenceRule` (lists in one place) | yes | :892 (file 1268 lines) |
| Negations, ConnectionWords, AbsenceWords, FunctionWords, Allowlist, UnnamedWords, TimeQualifierRe | yes | :905, :912, :945, :1011, :1053, :1122, :1153 |
| `classify`, `isThirdName`, `inVocabulary` | yes | :1200, :1256, :1262 |
| eligibility conjunction (`established`) | yes | :650 (block :648-707) |
| four statuses unchanged | yes | handoff; docs/api.md section 9 (W1) |

No mismatch. The check was made against the file as last written by step 1 (mtime 17:43:24, 57870 bytes); at 18:48:17 rag-engineer's repair changed it (66179 bytes). Per the work order, the L2 statements are written from step 1's handoff and W5 reconciles them.

Other inputs read (linked, not changed): README.md, docs/architecture.md, docs/data-lineage.md, docs/api.md (section 9 heading), docs/final-state.md, docs/runbook.md (linked only), docs/evaluation.md (sections 0-4, 10), experiments/results-summary.md (0-1, 2.6-2.8), the W1, W2, W3 reports, review-report-code.md (P17-A-01 to A-03, for context only; no statement taken from it), routing-p17 not needed. docs/threat-model.md, docs/service-limits.md, docs/tool-versions.md: not needed for sections 1-12 (W4b sections 16 and 14 will use them).

## Section checklist (spec "Final report structure", course-specification.md:2860-2890)

| # | Spec section | Status | Main sources |
|---|---|---|---|
| 1 | Problem and motivation | done (+1.1 deviations: teardown quote, Scala, model endpoint, budget/account/dataset approvals, public-repo note) | spec "Overview and motivation"; CLAUDE.md; Phase 15 gate decision 6; instructor-approvals.md |
| 2 | Three research questions and required metrics | done | spec "Required final capability", "What must be measured"; Phase 6 gate |
| 3 | Dataset selection and licenses | done | dataset-manifest.yaml; LICENSES.md; Phase 3 and 5 gates; local-run-report.json; data-lineage 2 |
| 4 | Agentic software factory architecture | done | docs/agents.md; CLAUDE.md; Phase 1, amendment-01, amendment-02 gates |
| 5 | Authority and ownership design | done | docs/agents.md; group 6 totals.csv |
| 6 | Human gates and repair loops | done (+6.1 authorship, four categories with counts and examples) | CLAUDE.md; gates' "Manual changes"; group 6 totals.csv, counts-by-agent.csv, events.csv |
| 7 | Hadoop pipeline | done | data-lineage 3; Phase 4 and 14 gates; results-summary 2.6; evaluation 10.7 |
| 8 | Entity resolution method and evaluation | done | Phase 4, 5, 11, 13, 14 records; evaluation 10.1 and raw er files |
| 9 | Spark graph, metrics, paths, communities, bridges, temporal | done | Phase 2, 6 gates; data-lineage 5; results-summary 2.8; evaluation 10.2 |
| 10 | Storage architecture | done | architecture 4; Phase 7, 9, 12, 14 gates; data-lineage 6; evaluation 10.2 provenance |
| 11 | S3 Vectors and optional OpenSearch | done (OpenSearch: not implemented, stated) | index-spec-v1.json; config/course.json; Phase 7 gate; data-lineage 6; evaluation 10.3 |
| 12 | Runtime collaboration-analysis agents | done (12.2 L2 rule from step 1, PENDING W5; 12.4 model-endpoint deviation with verbatim approval and Phase 15 D8 reason) | architecture 3; Phase 9, 10, 16 gates; api.md 9; agent-report-rag.md; Phase 15 runbook-log D8 |
| 13-25 | | headings only, `PENDING W4b` | |

Technology reasons are arguments from the gates and work orders (MapReduce: record-at-a-time, shuffle-shaped pair expansion with the Phase 4 properties; Spark: iterative whole-graph algorithms, no driver collect; laptop ER: local files, small data, no EMR cost; Neptune: traversals through typed templates; DynamoDB: keyed lookups and conditional writes; S3: immutable addressable runs; S3 Vectors: durable semantic tier without an always-on cluster under the $25 ceiling, never a source of numbers; OpenSearch not built for cost; Anthropic/Voyage: Bedrock quota 0).

## Link-check results (decision 12 method)

Method: `grep -o` of every `](../…)` and `](#…)` in reports/final-report.md; for each repository target `ls -d`, `git check-ignore -v` (must print nothing), `git ls-files` (tracked, or created in Phase 17 by a step's FILES CREATED); fragments compared with the target's headings (`grep -n` of `^#` lines) by the GitHub rule.

| Target | ls | ignored | tracked |
|---|---|---|---|
| docs/evaluation.md, experiments/results-summary.md, docs/agents.md, docs/agent-runs/instructor-approvals.md, datasets/dataset-manifest.yaml, datasets/LICENSES.md, modules/storage/src/main/resources/s3vectors/index-spec-v1.json | ok | no | 1 each |
| docs/agent-runs/ (dir) | ok | no | 388 |
| datasets/samples/ (dir) | ok | no | 7 |
| .claude/agents/ (dir) | ok | no | 18 |
| docs/agent-runs/phase-01, 03, 04, 05, 06, 07, 09, 10, 11, 12, 14, 15, 16 human-gate.md; amendment-01 and amendment-02 human-gate.md; phase-02 prompt.md and human-gate.md; phase-13 prompt.md; phase-08/README.md; phase-05/gold/gold-labels.csv; phase-15/runbook-log.md | ok | no | 1 each |
| experiments/evaluation/raw/repro/e5-pipeline-20261122T1715Z-79b5583/local-run-report.json | ok | no | 1 |
| experiments/evaluation/raw/factory/20261122T170517Z-7e551c8003c3-d311/ totals.csv, counts-by-agent.csv, events.csv | ok | no | 1 each |
| experiments/evaluation/raw/er/20261122T170516Z-7e551c8003c3-91dd/ recomputed.csv, table.csv, confusion.csv, weighted-intervals.csv | ok | no | 1 each |
| experiments/evaluation/raw/graph/20261122T172641Z-7e551c8003c3-a0dc/ suite-summary.csv, suite-coverage.csv, invariants.csv, provenance.csv | ok | no | 1 each |
| README.md (W3); docs/architecture.md, docs/data-lineage.md, docs/api.md (W1); docs/final-state.md, docs/runbook.md (W2) | ok | no | 0 (created in Phase 17, in W1/W2/W3 FILES CREATED) |

Fragments (all match a heading):

| Link | Heading |
|---|---|
| architecture.md #1-build-time-workflow, #3-the-runtime-query-workflow, #4-why-each-technology-is-used, #6-what-was-not-built | `## 1. Build-time workflow` (:18), `## 3. The runtime query workflow` (:172), `## 4. Why each technology is used` (:198), `## 6. What was not built` (:230) |
| data-lineage.md #2-sources, #3-hadoop-jobs-j01-to-j09, #4-entity-resolution, #5-spark-graph-construction-and-analytics, #6-loaders-and-stores, #7-evidence-answer-claims-and-the-audit-record | :54, :67, :105, :118, :135, :153 (comma dropped) |
| evaluation.md #1-what-is-evaluated-and-how, #7-threats-to-validity, #10-results, #101-group-1-entity-resolution, #102-group-2-graph-correctness, #104-group-4-answer-quality, #106-group-6-agentic-software-factory, #107-group-7-reproducibility | :42, :223, :320, :324, :396, :502, :706, :764 |
| api.md #9-claim-verification-statuses-and-reasons | :256 |
| report-internal #1-problem-and-motivation … #12-runtime-collaboration-analysis-agents (12 links) | report :31 … :219 (section 9's commas dropped) |

External links: none as Markdown links. Plain text: `https://dblp.org` inside the DBLP attribution quoted from datasets/LICENSES.md. PENDING entries: 13 section headings `PENDING W4b` (13 to 25) plus the status paragraph; 2 `PENDING W5` marks (section 12.2). Plain-text future paths (not links): `docs/agent-runs/phase-17/course-specification.md`, `agent-report-rag.md` (to be copied into `docs/agent-runs/phase-17/`), the Phase 17 work order.

## Replayed commands and outcomes

None (planned deviation 1). The report contains no command to run; its "How to read" paragraph says so and points to README.md and docs/runbook.md, and states that names in backticks such as `sbt check` name gates, not instructions. No replay line is therefore needed in it.

## Unverified claims

- The Phase 6 laptop results table and path statistics are copied from the Phase 6 gate; agent-report-spark.md was not re-read.
- Phase 14 counts are taken from docs/data-lineage.md (W1) and the Phase 14 gate; the Phase 14 runbook log was read only for the "D7 ER" row.
- "The question was asked by another student" rests on instructor-approvals.md's wording ("My account has the same restriction ... I follow the terms of this answer exactly") and the Phase 9 gate ("the approval was given to another student with the same Bedrock restriction").
- The Phase 1 probe 1 wording (orchestrator refused to route the build.sbt edit to service-engineer) is from the gate's quoted response, which is truncated there.
- The ER conflict count differs between the Phase 5 gate checklist ("8 conflict") and the Phase 14 run report (459,149 pair decisions in conflict) while the Phase 14 gate says the run "matched Phase 5's accepted run exactly" on decisions, assignments and clusters; the report quotes the Phase 14 counts and the gate's statement only, and does not reconcile the two (possibly cluster-level vs pair-level conflicts; not checked).

## INTERFACE HANDOFF

File: reports/final-report.md (new; document only; no other owner's file changed).

Sections done: 1 (with 1.1), 2, 3, 4, 5, 6 (with 6.1 authorship), 7, 8, 9, 10, 11, 12 (12.1 to 12.4). Sections 13 to 25: `## N. Title` headings with the body `PENDING W4b`; the "Status of this draft" paragraph near the top must be updated by W4b/W5 when they finish.

Anchors: #1-problem-and-motivation, #11-instructor-approved-deviations, #2-the-three-collaboration-research-questions-and-required-metrics, #3-dataset-selection-and-licenses, #4-agentic-software-factory-architecture, #5-authority-and-ownership-design, #6-human-gates-and-repair-loops, #61-authorship-of-the-deliverables, #7-hadoop-pipeline, #8-entity-resolution-method-and-evaluation, #9-spark-coauthorship-graph-collaboration-metrics-paths-communities-bridge-analysis-and-temporal-analytics, #10-storage-architecture, #11-s3-vectors-and-optional-opensearch-design, #12-runtime-collaboration-analysis-agents, #121-the-workflow, #122-claim-verification, #123-what-the-runtime-cannot-do, #124-model-endpoints-anthropic-and-voyage-instead-of-bedrock-instructor-approved-deviation; pending: #13-microservice-architecture-and-api … #25-reproduction-and-cleanup-instructions (#23-limitations is the target for the README "Known limitations" link). Note: `#11-instructor-approved-deviations` (subsection 1.1) and `#11-s3-vectors-and-optional-opensearch-design` (section 11) both start with `11`; they differ in the rest of the slug.

L2 statements written (section 12.2, all from step 1's handoff; W5 reconciles with repair-01-rag and the re-check):

1. Recognition by predicate and negation scope from fixed lists (connection predicate under a negation, contractions in n't, curly apostrophes normalised; or an un-negated absence predicate); a negated absence predicate is a connection claim; lists in one place (ClaimVerification.scala:892-1268 as handed over at step 1).
2. Unclear scope, or a negation beside the pair with no listed predicate: ambiguous, at most `partially_supported`.
3. `verified` only as a conjunction of established conditions (unambiguous recognition, both resolved, no third name per capitalisation/allowlist/bundle name parts, depth and window matching a cited complete `path=none` search, no year qualifier or differing range, no found path between the pair anywhere on a cited or bundled path, closed vocabulary); nothing is verified merely because no blocker matched.
4. No stated depth against a depth-limited search: `unsupported` (P16-L-14); unnamed people: `unsupported` (P16-L-15).
5. New reason code `AbsenceAmbiguous` (quoted text); `AbsenceNoDepth` now unsupported; `AbsenceNotSearched` and `HopsConflict` in more cases.
6. Monotonic: no status rose; no fall in existing fixtures (verifier suites, golden examples, fake benchmark corpus with the golden question, 59 claims); 21 falls all in new test inputs; every P16-L-10..L-15 input no longer verified; rule off: 14 of 26 new tests fail; final targeted run 193 passed in 13 rag suites, golden examples 17 passed, no golden output changed.
7. Known live effect: the deterministic edge-absence sentence falls from verified to partially_supported; wording with neither a listed negation nor a listed absence predicate is still not recognised.
8. Section 12.2 also says (from the Phase 16 gate) that L2 was accepted as a deviation in Phase 16 with P16-L-10..L-15 open, and section 12.3's interpretation says the rule "fails closed on wording it recognises as unclear and can still miss wording it does not recognise at all" (consistent with P17-A-01; W5 checks it after the repair).

What W4b must continue:
- Sections 13 to 25 per decision 14: cost (20) with per-phase billed figures from the gates (Phase 14 about $10.04, Phase 15 $20.71, Phase 16 $21.46 project totals as recorded) and `PENDING E7`; failures (21) incl. A-Hadoop attempt 1 (L-01), D-read attempt 2, the D9a preflight, E2 attempt 1, the first applies, G-items, FECD-1 and the incident report; limitations (23) incl. every status-line item of decision 2, the not-measurable items of docs/evaluation.md, "fixed in code, not validated live", U-12, L-04, the enrichment gap of section 3, the provenance-sample gap of section 10, the open L2/P17 items; reproduction and cleanup (25) linking README, runbook, final state.
- Extend the teardown deviation in sections 14, 15, 23, 25 and the budget approval in section 20 (section 1.1 points there).
- Update the "Status of this draft" paragraph.

Thread-safety and limits: not applicable (document). Every statement describes the committed records at HEAD 50ba087 plus step 1's handed-over change; no live check was possible.

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-17/prompt.md` -> c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (start and end; matches the work order)
- interface check: `grep -n` of the handed-over names in ClaimVerification.scala at mtime 17:43:24 (57870 bytes) -> all present at the handed-over lines; `ls -lT` later -> mtime 18:48:17, 66179 bytes (rag repair in progress, not mine)
- link check: 110 link occurrences, 50 unique repository targets -> all `ls=ok`, `ignored=no`; 44 tracked, 6 untracked (README.md, docs/architecture.md, docs/data-lineage.md, docs/api.md, docs/final-state.md, docs/runbook.md; all in W1/W2/W3 FILES CREATED)
- fragment check: 40 fragment links (13 unique anchors in 4 files) and 12 report-internal anchors against `grep -n` of headings -> all match
- identifier scan of reports/final-report.md (AKIA…, ASIA…, sk-ant-, pa-…, arn:aws, amazonaws.com, execute-api, .elb., scholarchain-course, scholarchain-25, scholarchain-readonly, twelve zeros) -> no match (exit 1); twelve-or-more digit runs -> none (exit 1); IPv4 pattern -> 1.4.7.1 at line 195 (Neptune engine version, expected)
- `shasum -a 256 reports/final-report.md` -> 0a9dbb54a2bb55a07e7f3881eb75654d064a9081575a1f8740dc42d49ff06127 (319 lines; last write 18:50:09)
- `git status --short reports/ modules/rag/` -> my entry `?? reports/`; the modules/rag entries belong to rag-engineer
files changed after these runs: none
