STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: ScholarChain Phase 17, session 3, repair round R3, documentation update (instructor condition 2: each change documented with its rationale and autograder finding). B1 (V2 HADOOP-04, fixed in code), B3 (V2 HADOOP-PAIRS-WRONG, deliberate bound, no code change), N4 (V2 DATA-02, DATA-05, dataset records). Markdown only, Edit tool only.
FILES CREATED: none in the repository. Scratchpad: diff-docs-R3.txt (380 lines) and this report.
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/docs/hadoop-job-contracts.md
- /Users/brian/Developer/repos/scholarchain/docs/data-lineage.md
- /Users/brian/Developer/repos/scholarchain/docs/service-limits.md
- /Users/brian/Developer/repos/scholarchain/docs/runbook.md
- /Users/brian/Developer/repos/scholarchain/docs/threat-model.md
- /Users/brian/Developer/repos/scholarchain/docs/demo-script.md
- /Users/brian/Developer/repos/scholarchain/README.md
- /Users/brian/Developer/repos/scholarchain/reports/final-report.md
COMMANDS RUN (all read-only; no sbt, no AWS, no network, nothing under scripts/**, datasets/scripts/**, experiments/**, load-tests/**, .claude/hook-tests/**):
- ls, wc -l, grep, git status, git diff (stat, -U0, -U1), git show HEAD:<file> (to map old line numbers of Pipeline.scala and the manifest), tail -n.
- A read-only `for` loop with `[ -e ]` over the link targets of the added lines (link check).
- `git diff -U1 -- README.md docs reports > R/diff-docs-R3.txt` (the one redirect, into the scratchpad, as the work order asks).
- Deviation from the work order's shell list, reported: several commands started with `cd <repo>;` and I used `sed -n` (print only) to read old and current line ranges, and `sed` once in a pipe to strip link syntax for the link check. None wrote a file or edited anything; I switched to `git -C` and absolute paths once I noticed.
TEST RESULTS: none run (documentation only; sbt is not allowed in this work order). Totals quoted in the docs come from R/test-report-R3-2.md and R/repair-hadoop-R3.md, not from my own runs.
VALIDATION RESULTS:
- Link check on every link in an added line (25 links over 8 files): every target file exists.
- Anchor check: the new and kept anchors match the headings (GitHub slugs): #51-j05p-block-size-pre-pass, #61-j05bp-fallback-block-size-pre-pass, #131-j05-oversize-blocks-the-cap-is-enforced-before-the-reduce-step-check-hadoop-04-fixed, #132-teams-over-the-hard-limit-are-rejected-check-hadoop-pairs-wrong (unchanged), #5-j05-blocking, data-lineage #3-hadoop-jobs-j01-to-j09. The only remaining uses of the old 13.1 anchor are inside docs/agent-runs/phase-17/agent-report-docs-autograder.md (lines 36, 84; human-owned historical record, code spans, not edited).
- Identifier scan of the added lines (twelve-digit runs, twelve zeros, arn:aws, s3://, amazonaws, IPv4, AKIA, sqs.) and an N/N ratio scan: no match.
- Every code citation was read in the current working tree (BlockingJob.scala, BlockSizeJobs.scala, FallbackJobs.scala, BlockingChain.scala, Pipeline.scala, Counters.scala, Settings.scala diff, defaults.xml, the test files' test lines). The spec lines course-specification.md:1155, :2245, :2718, :2729 were read and match the quotes.
- reports/final-report.md keeps 755 lines and README.md 457: every edit before line 613 is in place, so the manifest's citation of reports/final-report.md:613 (dataset-manifest.yaml:562) still points at the enrichment bullet.
ASSUMPTIONS:
- The J05 partitioner and grouping are those the code sets (BlockingJob.scala:564-565), not the stale scaladoc (:321-331, :476-480), per the work order and review finding 1.
- I kept the 13.2 heading and its anchor (still accurate), and the section 13 title, so inbound links stay valid; 13.1 was retitled because its old title is now false.
- J05p and J05bp are documented as subsections 5.1 and 6.1 so that section numbers and anchors 6 to 13 do not move.
- The R3 session reports are cited by file name ("session reports that the human copies into docs/agent-runs/phase-17/"), as earlier docs cite repair-01-rag.md; they are not committed yet.
- The B3 human decision under instructor condition 4 is taken from the work order; I found no committed record of it.
- Manifest line citations shifted by the N4 edit (+4 after line 16, +41 after the Crossref entry) were updated by that offset and spot-checked (dtd_sha256 now :410; entry starts 358, 447, 522, 622; samples at 711).
RISKS:
- The test totals and the "same records with and without the pre-pass" statement rest on agent reports in the scratchpad, not on a committed record or my own run.
- The B3 volume figures rest on the unrecorded 693-byte local measurement and an assumed 35-byte element; the docs label them as estimates.
- The stale scaladoc in BlockingJob.scala remains until hadoop-engineer fixes it; the docs name it as an open minor item.
- docs/agent-runs/phase-17/autograder-response.md:317-321 (N4) and the old 13.1 anchor in agent-report-docs-autograder.md are human-owned and now describe the pre-R3 state.
MANUAL ACTION REQUIRED:
- Copy the R3 session reports cited in the docs (agent-report-hadoop-R3.md, repair-hadoop-R3.md, review-report-R3-partB.md, test-report-R3-2.md, agent-report-dataset-R3.md) into docs/agent-runs/phase-17/ with cp -n, so the citations resolve.
- Run `bash datasets/scripts/validate.sh` in an approved step (the final report states it has not run).
- Decide whether docs/agent-runs/phase-17/autograder-response.md needs a human-approved update for B1, B3 and N4.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the documentation diff (R/diff-docs-R3.txt), then human gate.

## Changed file:line list (new-side line numbers)

- README.md:276 - Local Hadoop pipeline replay cell: the E5.13 replay ran the pre-R3 code; the R3 chain was not replayed with this command; LocalPipelineRunnerSuite's synthetic full run passed in the R3 sbt check.
- docs/hadoop-job-contracts.md:5 - status line: first written at d8b49fa, updated at R3; B1 with the exact fixed-in-code phrase; B3 no code change.
- docs/hadoop-job-contracts.md:21, :23 - contents entries for 5.1 and 6.1.
- docs/hadoop-job-contracts.md:32 - run order with J05p and J05bp; BlockingChain.scala:49-118, Pipeline.scala:146-153, :114-220, :50-61.
- docs/hadoop-job-contracts.md:44 - partitioner row: J05 uses BlockingPartitioner and BlockingGroupingComparator; J05c TwoFieldPrefixPartitioner.
- docs/hadoop-job-contracts.md:46 - combiner row: J05p and J05bp set CountSumCombiner (only two setCombinerClass calls).
- docs/hadoop-job-contracts.md:51 - scope row: Pipeline.scala:88-92, :129-130.
- docs/hadoop-job-contracts.md:91-92 - J04 rows: Pipeline.scala:130, :134-141.
- docs/hadoop-job-contracts.md:104-112 - J05 table rewritten: oversizeKeys input, three map-output key kinds, BlockingPartitioner and grouping (stale scaladoc named), reduce per group kind and REDUCER_CAPPED_BLOCKS guard, stats, classes, configuration (defaults.xml:269-302, :350-366, :378-392), tests (OversizeBlockStrategySuite, OversizeBlockAdversarialSuite).
- docs/hadoop-job-contracts.md:114-130 - new section 5.1 J05p: name, input, map output, combiner, partitioner, reduce, oversizeKeys/ contract (OversizeKey v1), reading side and refusals, classes, configuration, tests.
- docs/hadoop-job-contracts.md:136-144 - J05b table rewritten: J05bp list input, still-oversize markers in the mapper, default partitioner, reduce and counters, stats, line references, tests.
- docs/hadoop-job-contracts.md:146-161 - new section 6.1 J05bp.
- docs/hadoop-job-contracts.md:167-174 - J05c line references remapped (FallbackJobs.scala :384 onward, defaults.xml:345-349); tests added.
- docs/hadoop-job-contracts.md:181 - J06 Pipeline.scala:161-165.
- docs/hadoop-job-contracts.md:186 - J06 defaults.xml:370-415.
- docs/hadoop-job-contracts.md:200 - J07 tests: JobLevelTeamScaleSuite.scala:148 added.
- docs/hadoop-job-contracts.md:232-233 - J09 Pipeline.scala:130, :206, :173-194.
- docs/hadoop-job-contracts.md:239 - J09 defaults.xml:418-428.
- docs/hadoop-job-contracts.md:244-249 - section 13 intro: 13.1 fixed defect, 13.2 deliberate bound; sources.
- docs/hadoop-job-contracts.md:251-284 - section 13.1 rewritten as fixed: status phrase, the defect and spec quote (:2245), recorded size (earlier code), what changed, still-oversize policy, same records, counters table, largestReduceGroup bound (member values only; at most the effective cap with the pre-pass; repair history), refusals, costs, tests and recorded results, open minor items.
- docs/hadoop-job-contracts.md:288-289 - 13.2 status: deliberate safety bound, not a fixed defect, no code change.
- docs/hadoop-job-contracts.md:298-305 - 13.2 R3 analysis with the reviewer's corrected wording, and option A (implicit clique, not built).
- docs/hadoop-job-contracts.md:307 - 13.2 tests: JobLevelTeamScaleSuite.scala:148 described.
- docs/data-lineage.md:28-30 - mermaid: J05p pre-pass node; J05bp named in the blocking node.
- docs/data-lineage.md:62-65 - manifest line ranges shifted by the N4 edit; Crossref snapshot_date 2026-11-12 (sample retrieval).
- docs/data-lineage.md:71 - Pipeline.scala:64-84; BlockingChain.scala:27-41; link to job contracts.
- docs/data-lineage.md:77-82 - job table: J04 Pipeline.scala:129-145; new rows J05p and J05bp; J05, J05b, J05c inputs, outputs and line references.
- docs/data-lineage.md:86 - J09 Pipeline.scala:173-208.
- docs/data-lineage.md:90 - partitioning: StableHashPartitioner by default; J05 and J05c overrides; cap enforced before the reduce step since R3, with the exact phrase.
- docs/data-lineage.md:94 - Phase 14 recorded counts produced by the pre-R3 code.
- docs/service-limits.md:119-121 - pipeline limits: blocking cap no longer applied in the reducer (exact phrase); hard limit a deliberate bound with the 12,497,500 bound and estimates labelled.
- docs/runbook.md:85 - local pipeline replay note (as README:276).
- docs/runbook.md:96 - manifest dtd_sha256 citation :406 -> :410.
- docs/threat-model.md:45 - manifest dtd_sha256 citation :406 -> :410.
- docs/demo-script.md:117 - replay note: E5.13 ran pre-R3 code; not replayed with R3 code.
- reports/final-report.md:5 - status: R3 updated section 7 and three entries of 23.1.
- reports/final-report.md:99 - manifest citation :33-46 -> :37-50.
- reports/final-report.md:101 - Crossref snapshot_date 2026-11-12 (sample retrieval); manifest :443-668 -> :447-709.
- reports/final-report.md:169 - section 7: J05p and J05bp pre-passes, exact phrase.
- reports/final-report.md:611 - "Fixed in code, not validated live" list now includes the R3 Hadoop blocking change.
- reports/final-report.md:615 - N4 entry rewritten: what was added (Crossref snapshot date, observed sample size, projection schema version; schema-version sections; ORCID field types), what is still not recorded (Crossref and OpenAlex source schema versions, bounded-corpus retrieval date, subset bytes), validator not run, review findings 4 and 5.
- reports/final-report.md:618 - J05 entry becomes "Fixed:" with the exact phrase, what changed, the largestReduceGroup bound, tests and check results.
- reports/final-report.md:619 - B3 entry: deliberate safety bound, full analysis in the reviewer's corrected wording, option A, the new contract test.

## Replayed commands and outcomes

None. Every command the changed lines describe needs sbt (forbidden in this work order) or AWS. The docs say so where a replay predates R3 (README:276, runbook:85, demo-script:117).

## Link-check results

25 links in added lines, all targets present; 6 distinct anchors, all match a heading. No external URL was added.

## Unverified claims

- About 693 bytes per pair record (hadoop-engineer local measurement, no committed record) and every volume derived from it (937 GB, 312 GB, 0.9 to 2.8 TB, 1.25 to 3.75 TB); about 35 bytes per realistic author element (assumed). All are labelled as estimates or assumptions in the docs.
- Test totals (hadoop 52 of 52 and contract 30 of 30 targeted; sbt check exit 0, hadoop 124 tests, contract 418 of 418; OversizeBlockStrategySuite 10, OversizeBlockAdversarialSuite 18, FallbackBlockingSuite 17, LocalPipelineRunnerSuite 4) and the mutation result (2 of 9 failed): from the R3 reports; I counted the test( declarations in the suites, but did not run them.
- "Same records and policy counters with and without the pre-pass" and "er-v2 candidate set unchanged": from the hadoop and review reports and the named tests; not re-run.
- The human's B3 decision under instructor condition 4: from the work order; no committed record found.
- The R3 session reports cited by name are not yet under docs/agent-runs/phase-17/.
- The edited manifest has not been checked by datasets/scripts/validate.sh (stated in the final report).

## INTERFACE HANDOFF

- Files changed: the 8 Markdown files above; no code, test, configuration, script, dataset, constitutional or docs/agent-runs file.
- New anchors other documents may link: docs/hadoop-job-contracts.md#51-j05p-block-size-pre-pass, #61-j05bp-fallback-block-size-pre-pass, #131-j05-oversize-blocks-the-cap-is-enforced-before-the-reduce-step-check-hadoop-04-fixed. Removed anchor: #131-j05-applies-the-block-cap-in-the-reducer-check-hadoop-04 (no remaining link outside docs/agent-runs).
- Line-number citations into BlockingJob.scala, BlockSizeJobs.scala, FallbackJobs.scala, BlockingChain.scala, Pipeline.scala, defaults.xml and dataset-manifest.yaml are against the current working tree; any further code or manifest edit shifts them (for example the scaladoc fix of review finding 1 would shift BlockingJob.scala lines).
- Limits: no replay performed; the final report's line count is unchanged so the manifest's self-citation of reports/final-report.md:613 stays valid.
