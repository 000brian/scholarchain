STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: ScholarChain Phase 17, session 3, repair round R3, docs repair of review-report-R3-docs.md findings 1 to 5 (one pass, Markdown only, Edit tool)
FILES CREATED: none in the repository (this report and diff-docs-R3-2.txt in the session scratchpad)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/docs/architecture.md (new in this round)
- /Users/brian/Developer/repos/scholarchain/README.md
- /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md
- /Users/brian/Developer/repos/scholarchain/docs/runbook.md
- /Users/brian/Developer/repos/scholarchain/docs/demo-script.md
- /Users/brian/Developer/repos/scholarchain/reports/final-report.md
COMMANDS RUN (all read-only): grep, head, tail -n, cut, wc, ls, git -C ... diff / status; final diff saved with `git -C /Users/brian/Developer/repos/scholarchain diff -U1 -- README.md docs reports > .../phase17-reports/diff-docs-R3-2.txt` (exit 0, 398 lines).
TEST RESULTS: none run (Markdown only; no sbt).
VALIDATION RESULTS: anchor and link check on changed lines passed; identifier and ratio scan of added diff lines found nothing (details below).
ASSUMPTIONS: The R3 changes to final-report.md are the working-tree diff against HEAD cdd36c8: hunks at :5, :99, :101, :169, :611, :615 and :618-619.
RISKS: Line citations into FallbackJobs.scala and FallbackBlockingSuite.scala will move if those files change again.
MANUAL ACTION REQUIRED: Finding 3's response wording (autograder-response.md:520) and finding 6 are for the orchestrator (human-owned draft); not touched.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: focused code-reviewer re-check of the lines below, then identifier scans and the human's commits.

## Findings, with new file:line

1 (major)
- docs/architecture.md:204: replaced "two known limitations (the blocking cap is applied in the reducer; teams over the hard limit are rejected)". The new text says the two E12 items were handled in R3. B1 (HADOOP-04): counting pre-passes list oversize keys before J05 and J05b, and the reducer-side cap remains only as a guard. It carries the exact status line and links section 13.1. B3 (HADOOP-PAIRS-WRONG): a deliberate safety bound, no code change, with a link to section 13.2.
- README.md:443 (D06): "known limitations of blocking and the team hard limit" replaced with B1 (pre-passes J05p and J05bp, exact status line, link to 13.1) and B3 (deliberate safety bound, no code change, link to 13.2).
- Sweep: searched README.md, docs/** (excluding agent-runs, incidents and agents.md) and reports/** for "in the reducer", "reducer-side", "cap is applied", "known limitations of blocking", "blocking cap", "one reduce call", "oversize", "maxBlockSize", "known limitation", "5,127" and "R3". No other statement of the old reducer-side cap is current. The remaining hits describe the pre-R3 defect as history (hadoop-job-contracts.md:254, final-report.md:618 "no longer applies its cap only in the reducer", service-limits.md:120 "no longer applied in the reducer") or the guard (hadoop-job-contracts.md:273, :277).

2
- docs/hadoop-job-contracts.md:187 (J06 tests): `FallbackBlockingSuite.scala:433`, `:764` -> `:450`, `:784`.
- docs/hadoop-job-contracts.md:240 (J09 tests): `:433` -> `:450`.
- Verified by reading the file: :450 is test("J06 reads J05 and J05b blocks: same features for every family; J09 exports them all") and :784 is test("J06 volume rule: same homonym-numbered name pairs are skipped and counted when enabled").

3: the exact status line was added at:
- README.md:276
- docs/runbook.md:85
- docs/demo-script.md:117
- reports/final-report.md:5
- reports/final-report.md:611

Each keeps its note that the E5.13 replay ran the code before R3.

4
- docs/hadoop-job-contracts.md:138 (J05b Map output). The row now says FALLBACK_KEYS_EMITTED counts every fallback key value the mapper builds, per configured kind and value, whether or not its group is listed. It cites `hadoop/blocking/FallbackJobs.scala:233`, which comes before the list check at `:235` (read in the file: :233 `io.count(Counters.FallbackKeysEmitted)`, :235 `if (stillOversize.contains(key))`). The listed and not-listed cases follow unchanged.

5
- reports/final-report.md:5: now lists every R3 change in the report:
  - section 3: the subset-rule manifest range at :99, and the Crossref snapshot_date sentence with the new manifest range at :101;
  - section 7: :169;
  - four entries of section 23.1: the "Fixed in code, not validated live" list :611, dataset records :615, Hadoop blocking :618, teams over the hard limit :619;
  - plus the status line for the blocking change.
- reports/final-report.md:615: "two minor citation findings" is now "two minor findings on the manifest, a citation that reads more into its source than the source says and a cause note that no committed record supports". The wording was checked against review-report-R3-partB.md findings 4 (manifest :558 over-reads its citation) and 5 (sample_note cause unsupported).

## Link and anchor check (changed lines)
- Anchor `#131-j05-oversize-blocks-the-cap-is-enforced-before-the-reduce-step-check-hadoop-04-fixed`: heading present exactly once (hadoop-job-contracts.md:251); GitHub slug matches.
- Anchor `#132-teams-over-the-hard-limit-are-rejected-check-hadoop-pairs-wrong`: heading present exactly once (:286); slug matches.
- Relative targets: `hadoop-job-contracts.md` from docs/ and `docs/hadoop-job-contracts.md` from the root both exist (ls).

## Identifier scan
`git diff -U0` added lines were grepped for twelve-digit runs, arn:aws, amazonaws, s3://, AKIA, IPv4 and N/N ratios: no match. The name of the tutorial's sample application was not added.

## Replayed commands
None. This round changes wording only; no documented command changed.

## Unverified claims
None added.
