# Repair: autograder docs, code-reviewer finding 1 (major), Phase 17 session 3 E12

## Citations verified (read-only)

- `modules/rag/src/main/scala/org/scholarchain/rag/runtime/Stages.scala:617`: `stores.graph.run(GraphQuery.PathBetween(a.id, b.id, s.windowLabel, s.maxDepth))`, in the `case None` branch (no stored evidence item); `:625` origin `"graph-path-search"`; `:684` `PathFact(refs, s.windowLabel, window, origin, ...)`. Confirmed.
- `modules/storage/src/main/scala/org/scholarchain/storage/ports/GraphPorts.scala:157-164`: `PathBetween(from, to, windowLabel: String, maxDepth: Int)`. Confirmed.
- `modules/storage/src/main/scala/org/scholarchain/storage/neptune/NeptuneModel.scala:142-146`: `pathAtDepth(k, w, ...)` uses the window `w`. Confirmed.
- `modules/storage/src/main/scala/org/scholarchain/storage/local/InMemoryGraph.scala:253-255`: `shortestPath` reads `q.windowLabel`. Confirmed.
- `modules/spark/src/main/scala/org/scholarchain/spark/Paths.scala:39` ("full window only") and `:292-298` (effective-diameter batched BFS). Confirmed.

## Change

One bullet, `reports/final-report.md:621` (still one line; nothing else changed).

Heading, new wording:

> **Spark-precomputed path evidence and the effective-diameter estimate cover the full window only (autograder follow-up; documented in Phase 17 step E12, no code change).**

Sentence, old: "So no window other than the full one has path evidence or a diameter estimate."

Sentence, new:

> So no window other than the full one has Spark-precomputed path evidence or an effective-diameter estimate. Runtime path questions in other windows use the bounded live path search instead: the runtime path stage runs `GraphQuery.PathBetween` with the question's window and depth cap (`modules/rag/src/main/scala/org/scholarchain/rag/runtime/Stages.scala:617`), and its answer is labelled with origin `graph-path-search`.

The rest of the bullet ("Computing them per window is a spark-engineer code change, out of scope after the release candidate.") is unchanged. Minor finding 5 (stale "(README:NNN)" row titles) left untouched as ordered.

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17 session 3 E12, repair round for code-reviewer finding 1 (major), reports/final-report.md:621
FILES CREATED: none in the repository (scratchpad: diff-autograder-docs-4.txt, this report)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/reports/final-report.md (line 621 only)
COMMANDS RUN: read-only grep/head/tail -n/ls over the cited sources; `git diff -U1 -- README.md docs/evaluation.md reports/final-report.md > .../phase17-reports/diff-autograder-docs-4.txt` (exit 0, 35 lines); `git diff --stat`; `grep -n` and `wc -l` on the report (755 lines, edited bullet at 621)
TEST RESULTS: not applicable (Markdown only; no sbt per work order)
VALIDATION RESULTS: all seven cited locations read and match the finding; new wording located at line 621; diff stat shows only the three already-modified docs files
ASSUMPTIONS: "bounded" refers to the `maxDepth` cap passed to PathBetween (and the stores' time/resource limits noted at Stages.scala:653-654)
RISKS: none identified; no new links added, so no link-check delta
MANUAL ACTION REQUIRED: human copies this report with `cp -n` if wanted as phase evidence
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused re-check of reports/final-report.md:621

## INTERFACE HANDOFF

- Files changed: reports/final-report.md (line 621, one bullet).
- Public signatures: none (documentation only).
- Fixtures: none.
- Thread-safety: not applicable.
- Limits: no code change; per-window Spark path evidence remains a spark-engineer item on the proposed-later list.

## Replayed commands and outcomes

None replayed (no command documented or changed in this edit).

## Link-check results

No links added or changed; the existing links in the bullet are unchanged.

## Unverified claims

None.
