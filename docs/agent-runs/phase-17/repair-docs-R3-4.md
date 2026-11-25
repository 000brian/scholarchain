STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: ScholarChain Phase 17, session 3, repair round R3: repair of the citation re-check (review-report-R3-citations.md), both findings fixed in one pass, Markdown only, docs/hadoop-job-contracts.md only
FILES CREATED: none in the repository (scratchpad: diff-contracts-R3-5.txt, this report)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md (5 lines: 129, 143, 160, 173, 284)
COMMANDS RUN:
- grep -nE '^([^`]|`[^`:]*`)*`:[0-9]' docs/hadoop-job-contracts.md -> exit 0, one line found (107; see the scan below). grep -c '`:[0-9]' -> 112 lines contain a bare citation.
- git -C <repo> diff -U1 -- docs/hadoop-job-contracts.md > scratchpad/phase17-reports/diff-contracts-R3-5.txt -> exit 0, 237 lines. The diff is against HEAD, so it also contains this file's earlier uncommitted R3 changes, not only this pass.
- grep on the saved diff -> all 5 new lines are present.
TEST RESULTS: not applicable (Markdown only; no sbt, per the work order)
VALIDATION RESULTS: I checked every new line number by reading the files. In modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml: maxBlockSize is at 280-302, the fallback keys (families, secondaryKeys, fallback.maxBlockSize, fallback.reducers) at 303-349, fallback.reducers at 345-349, policyVersion at 355-361, blocking.reducers at 362-366 and candidates.maxPairsPerBlock at 378-392. In tests/contracts/.../OversizeBlockAdversarialSuite.scala, lines 575-579 are the scaladoc and line 580 is test("largestReduceGroup stays within the cap and does not depend on the map-task layout"). I added no links, so no link check was needed.
ASSUMPTIONS: I wrote policyVersion plus reducers (355-361 and 362-366) as one range, `:355-366`, at line 129, because the two properties are next to each other.
RISKS: The line numbers will drift if defaults.xml or the contract suite change before the commit.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: focused code-reviewer re-check of docs/hadoop-job-contracts.md lines 129, 143, 160, 173 and 284

Edits (doc line: old text -> new text)

| Doc line | Finding | Old | New |
|---|---|---|---|
| 284 | 1 (major) | which the test at `:578` also checks | which the test at `contracts/OversizeBlockAdversarialSuite.scala:580` also checks |
| 129 (J05p Configuration) | 2 | `defaults.xml:280-302` | `defaults.xml:280-302`, `:355-366`, `:378-392` |
| 143 (J05b Configuration) | 2 | `defaults.xml:303-349` | `defaults.xml:303-349`, `:355-361` |
| 160 (J05bp Configuration) | 2 | `defaults.xml:303-349` | `defaults.xml:303-349`, `:355-361`, `:378-392` |
| 173 (J05c Configuration) | 2 | `defaults.xml:345-349` | `defaults.xml:345-349`, `:355-361` |

Ambiguous bare-citation scan (reported only; nothing changed)

- Line 107 (J05 "Partition and grouping", Contract cell): "The scaladoc at `:321-331` and `:476-482`" names no file in its own cell. BlockingJob.scala is named only later, in the Source cell of the same row. Both numbers are correct for BlockingJob.scala (the earlier review verified them), so this is ambiguity, not an error. A possible fix is to write `hadoop/blocking/BlockingJob.scala:321-331`. I left it unchanged because it is not clearly wrong.
- I also read lines 32-112, 120-174 and 251-307 by hand. Every other bare `:N` follows a file named earlier in the same cell or bullet and refers to that file (for example lines 70, 112, 138, 200, 280, 281 and 303). I found no other ambiguous case.

Unverified claims: none added in this pass.
