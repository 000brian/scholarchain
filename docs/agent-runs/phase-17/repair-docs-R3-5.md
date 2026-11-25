# Repair docs R3-5: file name at docs/hadoop-job-contracts.md:107

Source: review-report-R3-citations-2.md, the ":107" paragraph (optional polish, human decision to apply).

## Change

One edit in /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md, line 107 (Contract cell of the "Partition and grouping" row). Line numbers are unchanged. The file is named in the same style as doc line 284. Nothing else changed.

Old text (fragment of line 107; the rest of the line is unchanged):

    The scaladoc at `:321-331` and `:476-482` names `BlockingPartitioner` and `BlockingGroupingComparator`

New text:

    The scaladoc at `hadoop/blocking/BlockingJob.scala:321-331` and `:476-482` names `BlockingPartitioner` and `BlockingGroupingComparator`

Full new line 107:

    | Partition and grouping | `BlockingPartitioner`: stable hash of `<family>\t<key>` for block members and size markers (the Phase 4 partition assignment) and of `<family>\t<key>\t<mention ID>` for re-keyed members, which spreads them over all reduce tasks. `BlockingGroupingComparator`: a reduce group is the first 2 tab-separated fields of a block member key, 3 of a size marker key, 4 of a re-keyed member key. So a block group holds at most the cap (only keys J05p found within it reach it), a re-keyed group holds the copies of one mention, and a marker group holds one value per map task that saw the key. The scaladoc at `hadoop/blocking/BlockingJob.scala:321-331` and `:476-482` names `BlockingPartitioner` and `BlockingGroupingComparator` (2-field block groups, 3-field marker groups, 4-field member groups); it named the Phase 4 `TwoFieldPrefixPartitioner` and `TwoFieldPrefixGroupingComparator` until the R3 minor repair pass fixed it (R3 review, minor finding 1, hadoop-engineer, comment only). The job sets the classes named here | `hadoop/blocking/BlockingJob.scala:363-385`, `:399-472`, `:564-565` |

## Replayed commands and outcomes

- `git -C /Users/brian/Developer/repos/scholarchain diff docs/hadoop-job-contracts.md > .../phase17-reports/diff-contracts-R3-6.txt`: exit 0; 282 lines; the new line 107 text appears once in it (grep -c = 1).
- `git -C ... diff --stat docs/hadoop-job-contracts.md`: 1 file, 130 insertions, 50 deletions (cumulative uncommitted diff of the phase, not of this edit alone; this edit changes one line).
- `grep -n "scaladoc at"`: only line 107 matches. `grep -c "BlockingJob.scala:321-331"`: 2 (lines 107 and 284). `wc -l`: 307 lines (unchanged line count).

## Link check

No link was added or changed; the edit adds only a code-formatted repository path. No link check needed.

## Unverified claims

None. The line ranges were verified by code-reviewer (BlockingJob.scala:324 and :476-477) and were not changed.

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17, session 3, repair round R3 (instructor-approved): name the file in the Contract cell at docs/hadoop-job-contracts.md:107
FILES CREATED: none in the repository (scratchpad: repair-docs-R3-5.md, diff-contracts-R3-6.txt)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md (line 107 only)
COMMANDS RUN: git -C <repo> diff docs/hadoop-job-contracts.md (redirected to scratchpad diff-contracts-R3-6.txt); git -C <repo> diff --stat docs/hadoop-job-contracts.md; grep -n/-c; wc -l
TEST RESULTS: none required (Markdown only; no sbt)
VALIDATION RESULTS: the new text is present at line 107 and in the saved diff; line count unchanged (307)
ASSUMPTIONS: `git -C <repo>` used instead of cd, matching the read-only git rule
RISKS: none
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: optional code-reviewer focused re-check of line 107; human ratification

INTERFACE HANDOFF
- Files changed: docs/hadoop-job-contracts.md (line 107)
- Public signatures: none
- Fixtures: none
- Thread-safety: not applicable
- Limits: documentation-only change; no source change
