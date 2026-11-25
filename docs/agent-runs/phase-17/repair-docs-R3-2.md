STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: ScholarChain Phase 17, session 3, repair round R3, minor repair pass (Markdown only): architecture.md:204 grammar; drop or flip "open" notes for Part B findings 1, 4, 5, 6; check quotes of BlockingJob.scala :476-482.
FILES CREATED: none in the repository (this report and diff-docs-R3-3.txt in the scratchpad)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/docs/architecture.md:204 - "are in" -> "is in" (subject "The contract of each job").
- /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md:107 (J05 "Partition and grouping" row) - the "scaladoc ... still names TwoFieldPrefixPartitioner and TwoFieldPrefixGroupingComparator; it is stale" sentence now says the scaladoc at `:321-331` and `:476-482` names BlockingPartitioner and BlockingGroupingComparator (2-field block, 3-field marker, 4-field member groups) and that it named the Phase 4 classes until the R3 minor repair pass fixed it (finding 1, hadoop-engineer, comment only). Citation `:476-480` widened to `:476-482` (the reflowed sentence spans 476-482 per repair-hadoop-R3-2.md).
- /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md:284 - "Open minor items" bullet renamed "Minor items fixed in the R3 minor repair pass"; finding 1 now fixed (citation `:476-482`, source repair-hadoop-R3-2.md); finding 6 now says the contract determinism test's byte comparison of `stats/` includes the `largestReduceGroup` line (test-engineer).
- /Users/brian/Developer/repos/scholarchain/reports/final-report.md:615 - last sentence: findings 4 and 5 no longer "routed to dataset-engineer"; now "dataset-engineer fixed both in the R3 minor repair pass (repair-dataset-R3.md)", and states what :558 and :581 now say (the date applies to the check of official pages at agent report line 3; line 38 cites the one Crossref batch request, no date; file 9,617 bytes vs estimate 9,347 bytes per 4 projected works, cause not recorded because no committed record states how 9,347 was measured). "validate.sh has not been run on the edited manifest" kept unchanged.
COMMANDS RUN:
- grep over README.md, docs/** (excluding agent-runs, incidents, agents.md), reports/** for scaladoc/stale/open/9,617/9,347/stats//finding/TwoFieldPrefix/:476 -> found architecture.md:204, hadoop-job-contracts.md:107 and :284, final-report.md:615; no other open note. README and the other docs carry no such note.
- grep -n on datasets/dataset-manifest.yaml:555-583 and BlockingJob.scala:320-331, :475-483 (read-only) to check the new wording against my text.
- grep on tests/contracts/.../OversizeBlockAdversarialSuite.scala for stats/largestReduceGroup (read-only).
- git -C ... diff -U1 -- README.md docs reports > .../phase17-reports/diff-docs-R3-3.txt (398 lines). A first attempt using a shell variable in the target path was blocked by the guard (non-literal path); retried with the literal path, success.
- Note: my first command included one `sed -n ... >/dev/null` (read-only, output discarded) before I applied the work order's "no sed" rule; it changed nothing. No further sed use.
TEST RESULTS: none (no sbt, per work order)
VALIDATION RESULTS:
- Task 3: no doc quotes BlockingJob.scala :476-482 word for word. The only references are the line citations at hadoop-job-contracts.md:107 and :284 (updated to :476-482). The new scaladoc (BlockingPartitioner, BlockingGroupingComparator, 2-field block / 3-field marker / 4-field member groups) matches the description already in hadoop-job-contracts.md:107.
- Link check: no link added or changed; only inline code citations changed.
- Post-edit grep for "still names", "still describes", "routed to dataset-engineer)" over README/docs/reports: no hits outside docs/agent-runs.
ASSUMPTIONS: The citations `review-report-R3-partB.md`, `repair-hadoop-R3-2.md` and `repair-dataset-R3.md` are scratchpad report names that the human copies into the phase evidence, as with the existing R3 citations.
RISKS:
- UNVERIFIED CLAIM (finding 6): hadoop-job-contracts.md:284 says the `stats/` byte comparison now includes the `largestReduceGroup` line. At the time of this pass the test file still excludes it (OversizeBlockAdversarialSuite.scala:527, scaladoc "`stats/` lines without the `largestReduceGroup` stat"). The wording follows the work order's statement that test-engineer is about to add it. If test-engineer's change does not land, that sentence must be reverted to the open note.
- validate.sh still not run on the edited manifest (unchanged statement kept in final-report.md:615).
MANUAL ACTION REQUIRED: Orchestrator confirms test-engineer's finding-6 change before accepting hadoop-job-contracts.md:284.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused check of the four edits; confirmation of the test-engineer finding-6 change.

## Replayed commands
None (no command documented or changed in this pass).

## Link-check results
No links added, removed or changed.

## Unverified claims
- hadoop-job-contracts.md:284, finding 6 sentence (see RISKS).
