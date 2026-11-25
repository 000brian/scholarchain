VERDICT: REQUEST_CHANGES

I found three major findings and two minor ones. The ratio rewrites, the README note, the N4 dataset entry and the removal of the sample application's name all hold.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| 1 | major | reports/final-report.md:621 (heading and the last sentence before the scope note) | The heading says "Shortest paths ... cover the full window only". The last sentence says "So no window other than the full one has path evidence or a diameter estimate." The code contradicts this. The runtime path stage runs a live bounded shortest-path search for any configured window: `stores.graph.run(GraphQuery.PathBetween(a.id, b.id, s.windowLabel, s.maxDepth))` at modules/rag/src/main/scala/org/scholarchain/rag/runtime/Stages.scala:617. It returns a `PathFact` for that window with origin "graph-path-search" (Stages.scala:625, :684). `PathBetween` takes a `windowLabel` (modules/storage/src/main/scala/org/scholarchain/storage/ports/GraphPorts.scala:157-164). Both Neptune (NeptuneModel.scala:142-146) and InMemoryGraph (InMemoryGraph.scala:253-255) apply it per window. | The final report states a capability gap that does not exist: path answers in non-full windows work at runtime. What is really full-window only is the Spark output: the precomputed `path_evidence` rows (Pipeline.scala:815-843, Paths.scala:39) and the effective-diameter estimate (Paths.scala:292-298). Both of those cited facts are correct. Fix: scope the heading and the sentence to "Spark-precomputed path evidence and the diameter estimate", and say that runtime path questions in other windows use the bounded live search. The draft's N5 (autograder-response.md:327) cites this entry and should follow the new wording. | documentation-engineer (final-report); orchestrator for the draft N5 wording |
| 2 | major | R/autograder-response.md:15 | "The guard runs on every tool call in the session." In fact the PreToolUse guard matcher is `"Bash\|Read\|Grep\|Glob\|Write\|Edit\|MultiEdit\|NotebookEdit"` (.claude/settings.json:211). The draft itself says (:13) that the built-in general-purpose subagent has Claude Code's full default tool set. That set includes tools this matcher does not cover (for example WebFetch, WebSearch and MCP tools). | The disclosure is the point of N1, and this sentence claims more coverage than the configuration gives. Fix: "The guard runs on every Bash, Read, Grep, Glob, Write, Edit, MultiEdit and NotebookEdit call (.claude/settings.json:211); other tools in its default set are not matched by the guard." If the record does not show which tools it used, say so. The stop-hook part of the same bullet is correct: s38-unknown-agent-not-exempt at docs/agent-runs/amendment-02/repair-01.md:802, also .claude/hook-tests/stop-hook-selftest.sh:267. Runbook-log row "E12 built-in subagent disclosure" (:108) says only that "the hooks ... still applied", which is true for the matched tools. | orchestrator (prepares the human-owned docs/agent-runs/phase-17 text) |
| 3 | major | R/autograder-response.md:246, :265, :271, :277 | These line citations went stale when this pass inserted lines at final-report.md:615 and README.md:90-91. A16 cites "reports/final-report.md:619"; the sizeBuckets entry is now at :620 (:619 is the teams entry). B1 cites ":617"; that line is now "Graph analytics", and the J05 blocking entry is at :618. B3 cites ":618"; the teams entry is now at :619. B2 cites "README.md:441 (D06 row)"; the hadoop-job-contracts link is now at README.md:443. | Each cited location now points to a different entry. This record is frozen once committed. Fix: update the four numbers. No repository document outside docs/agent-runs/ cites final-report:6NN, so the drift is in the draft only. | orchestrator |
| 4 | minor | R/autograder-response.md:297, :302 | "V2 rows timed 22:56 to 23:01 UTC, V3 rows 23:04 UTC" and "Unchanged: ... V2 HADOOP-04 partial (B1) and HADOOP-PAIRS-WRONG minor (B3)" are not in runbook-log row "E12 autograder second run (human)" (:107). That row gives only the folder names 20261124-165549 and 20261124-170408 and states only "V1 unchanged". | The claims are probably true but not supported by the log. Fix: add the row times and the unchanged B1/B3 results to row :107, or cite the folder names instead. | orchestrator |
| 5 | minor | README.md:112, :114, :278, :280-282, :321, :327; docs/runbook.md:184, :189; docs/demo-script.md:159; reports/final-report.md:612 | Each quotes a runbook-log row title such as "(README:110)" or "(README:319)". The two-line insert at README.md:90-91 moved those commands to :112, :278, :280, :281, :282 and :321. | These are row titles, so they still identify the log rows. Read as line numbers, though, they now point two lines early. Observation; no repair round needed. | documentation-engineer |

## Checks that passed

1. **Docs diff.**
   - The diff matches the files: README.md:90, docs/evaluation.md:566 and :754, reports/final-report.md:615 and :621.
   - The git status snapshot shows only these three files modified and nothing untracked.
   - On lines 566 and 754 no N/M ratio remains ("10 of 10"; "Agreement 20 of 20"). No number or meaning changed. The header "k / n" at :560 and the other rows are unchanged, as the draft states.
   - The N4 entry (final-report:615) is accurate against its sources:
     - dataset-manifest.yaml:540-542 has `id: unknown` with the live-REST reason.
     - :548-551 holds two estimates plus the blog statement.
     - :561-563 holds only the notes file and the selected fields.
     - No schema-version key exists anywhere in datasets/** (only `manifest_version: 1` at :16).
     - openalex.md and crossref.md state no schema version.
     - orcid.md:50-53 holds the wrapper and publication-date shape notes.
   - The N5 entry's cited facts are correct:
     - the window loop at Pipeline.scala:609;
     - paths and diameter on `cfg.fullWindow` at :815-843;
     - `fullWindow` defined at JobConfig.scala:61-65;
     - Paths.scala:39 (object comment), :130 (`shortestPaths` call), :164 (definition) and :292-298 (diameter BFS);
     - `Paths.run` is called only at Pipeline.scala:819.

     Only the generalisation in finding 1 is wrong.
   - The links resolve: docs/agents.md#artifact-ownership, ../datasets/schemas/{openalex,crossref,orcid}.md, and section 23.1 at final-report:606.
2. **Draft (autograder-response.md).**
   - The sample application's name does not appear anywhere in R/ (case-insensitive search). In the repository it remains only in the two course-specification copies and the committed docs/agent-runs/phase-17/autograder-response.md, which the draft replaces.
   - The disclosure matches row :108: one built-in general-purpose subagent, used once, full default tool set, instructed read-only, scratchpad-only writes, code-reviewer reviews (REQUEST_CHANGES, then APPROVE), no other use per the orchestrator's transcript check and the human's check.
   - The renamed mentions in A2, A8, A9 and A10 are consistent.
   - The C1 update and N1-N5 match row :107, except for the gaps in finding 4.
   - The N1 location README.md:90 is correct.
3. **Runbook log.**
   - The amended title of row :97 names the subagent and points to row :108.
   - The disclosure row matches the draft.
   - Rows :107, :109 and :110 exist and match the work order.
4. **Identifiers.** I searched the draft and the docs diff for key prefixes, ARNs, AWS hosts, queue URLs, s3:// paths, twelve-digit runs and IPv4 addresses. The only hits are check names and the unchanged `sizeBuckets` context line. The new log rows contain none.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17 session 3 E12, second TA-autograder run follow-up, focused read-only review
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (no tests run; review only)
VALIDATION RESULTS: 3 major and 2 minor findings, each verified against the files cited above
ASSUMPTIONS: The git status snapshot from the session start (only README.md, docs/evaluation.md and reports/final-report.md modified) still holds; I cannot run git diff. Claims about the subagent's transcript (wrote nothing in the repository, used once) are taken from runbook-log row :108; I did not read transcripts.
RISKS: If finding 1 is committed as is, the final report contradicts runtime behaviour that a grader can check (windowed path answers). If finding 2 is committed, the disclosure overstates how far the guard covers the subagent.
MANUAL ACTION REQUIRED: The human decides the routing: finding 1 to documentation-engineer; findings 2, 3 and 4 are orchestrator draft and log text for the human-owned record.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Repair round for findings 1-3 (one pass per owner), then a focused code-reviewer re-check. After APPROVE, the identifier scans and the human commit, as separate steps.
