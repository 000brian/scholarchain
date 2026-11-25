VERDICT: APPROVE

Both findings from my earlier review (/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/review-report-R3-citations.md) are fixed, and I checked each fix against the files. Nothing else in the doc changed. No critical or major finding remains.

Verification (doc: /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md)

- :284 (the major finding) is fixed. The line now reads "…which the test at `contracts/OversizeBlockAdversarialSuite.scala:580` also checks". In /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/OversizeBlockAdversarialSuite.scala, lines 575-579 are the scaladoc and line 580 is `test("largestReduceGroup stays within the cap and does not depend on the map-task layout")`. Because the citation names its file, it no longer points at BlockingJob.scala by mistake.
- :129 (J05p) is fixed. It cites `defaults.xml:280-302`, `:355-366`, `:378-392`. In /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml, 280-302 is maxBlockSize, 355-361 is policyVersion, 362-366 is blocking.reducers and 378-392 is candidates.maxPairsPerBlock. All four keys the row names are now covered. The merged range 355-366 holds exactly those two adjacent properties.
- :143 (J05b) is fixed. 303-349 covers fallback.families, secondaryKeys, fallback.maxBlockSize and fallback.reducers, and 355-361 covers policyVersion.
- :160 (J05bp) is fixed. It cites 303-349, 355-361 (policyVersion) and 378-392 (maxPairsPerBlock).
- :173 (J05c) is fixed. 345-349 is fallback.reducers and 355-361 is policyVersion.
- No other changes. diff-contracts-R3-5.txt and diff-contracts-R3-4.txt are both 238 lines, with the same hunk headers and the same removed lines. The added lines differ at only five places: diff line 77 (doc :129), 97 (:143), 114 (:160), 133 (:173) and 218 (:284). These match the five edits in the repair report. Every other line in the two diffs is byte-for-byte the same.

Bare citation at :107 (ambiguous, does not block). The Contract cell says "The scaladoc at `:321-331` and `:476-482`" but names no file in that cell. The file, `hadoop/blocking/BlockingJob.scala`, is named only in the Source cell of the same row. Both ranges are correct for that file. The BlockingPartitioner and BlockingGroupingComparator scaladoc is at BlockingJob.scala:324 (inside 321-331) and :476-477 (inside 476-482). Doc line 284 cites the same two ranges with the full file path. No claim is false and a reader can find the file in the same row, so this is optional polish (write `hadoop/blocking/BlockingJob.scala:321-331`), not a finding.

Findings

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| none | none | none | none | none |

Optional (not a finding): /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md:107. A bare `:321-331`, `:476-482` with no file named in its own cell. Both are correct for BlockingJob.scala. Owner if fixed: documentation-engineer.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: ScholarChain Phase 17, session 3, repair round R3, focused read-only re-check of the repair for review-report-R3-citations.md (repair-docs-R3-4.md): doc lines 284, 129, 143, 160, 173; diff comparison R3-5 vs R3-4; the :107 ambiguity
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (Markdown-only repair; reviewer fence)
VALIDATION RESULTS: Five changed citations checked against OversizeBlockAdversarialSuite.scala:575-583 and scholarchain-hadoop-defaults.xml:276-395. All are correct. Diff R3-5 compared with R3-4: only the five intended lines differ. I re-checked the BlockingJob.scala scaladoc positions (lines 324, 476-477) cited at :107 and :284, and both are inside the cited ranges. Identifiers in the changed lines: none (they hold only repository-relative paths and line numbers).
ASSUMPTIONS: A bare `:N` refers to the last file named before it in the same cell or bullet, which is the doc's own convention. Lines I did not change or re-read in this pass keep the verification from the previous review, as the identical diffs support. I read no forbidden path.
RISKS: The line numbers will drift if defaults.xml, the contract suite or BlockingJob.scala change before the commit.
MANUAL ACTION REQUIRED: none. Optionally, the human may ask for the :107 file-name polish.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: identifier scans and the human's commits, as separate steps.
