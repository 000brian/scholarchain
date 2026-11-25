VERDICT: REQUEST_CHANGES

One major finding is open. docs/architecture.md, which is not one of the 8 edited files, still says the blocking cap is applied in the reducer. That is the B1 defect the R3 docs now call fixed. Everything else checks out apart from six minor findings. The edited docs describe B1 the way the code works, with partitioner and grouping set at BlockingJob.scala:564-565 and not as the stale scaladoc says. The B3 numbers match my Part B section 3 and the unverified ones are labelled. The N4 text is correct. Manifest and code citations are correct apart from finding 2, anchors resolve, and the R3 section of the response matches the reports and the runbook-log rows 120-137.

## Findings

| # | Severity | file:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| 1 | major | docs/architecture.md:204 (also README.md:443, minor tail) | A reader or the V2 judge reads the Hadoop bullet: "two known limitations (the blocking cap is applied in the reducer; teams over the hard limit are rejected) are in docs/hadoop-job-contracts.md". | This is false for the R3 code. J05p runs first (BlockingChain.scala:58-79), the J05 mapper re-keys listed keys (BlockingJob.scala:558-560, :599-604), and the reducer-side cap is only a guard (REDUCER_CAPPED_BLOCKS, 0 in the pipeline). It contradicts hadoop-job-contracts.md 13.1, service-limits.md:120 and final-report.md:618 on the exact check HADOOP-04, and it lacks the status line. README.md:443 still says "known limitations of blocking". Fix: one-line edits in B1 scope. | documentation-engineer |
| 2 | minor | docs/hadoop-job-contracts.md:187 (J06 tests) and :240 (J09 tests) | Follow `test/FallbackBlockingSuite.scala:433` and `:764`. | FallbackBlockingSuite changed in R3 and these citations were not moved. :433 is now inside the J05c test (:387) and :764 inside the J05c-rejects test (:736). The J06/J09 test is now at :450 ("J06 reads J05 and J05b blocks … J09 exports them all") and the volume-rule test at :784. The J05b/J05c rows were moved by the same offset, so this is a miss. It also contradicts the status claim at :5 ("Every item cites … in the R3 working tree"). | documentation-engineer |
| 3 | minor | README.md:276, docs/runbook.md:85, docs/demo-script.md:117 (also final-report.md:5, :611) | Check each code-change mention for the exact status line. | These replay notes name the R3 blocking change but do not carry "fixed in code, tested locally, not validated live on AWS; the recorded experiment results were produced by the earlier code." The content is not false: they say the replay predates R3. But the work order asks for the line on each code-change mention. So the response's R3-Docs claim (autograder-response.md:520, "Every code change is described with the status line") is too broad. | documentation-engineer (docs); orchestrator (response wording) |
| 4 | minor | docs/hadoop-job-contracts.md:138 (J05b Map output row) | Read the code: FallbackJobs.scala:233 counts FALLBACK_KEYS_EMITTED before the still-oversize check at :235. | The row ties FALLBACK_KEYS_EMITTED to the not-listed case only. The counter counts every fallback key value, listed ones included. That matches :275 ("keep their meaning") but not this row. | documentation-engineer |
| 5 | minor | reports/final-report.md:5 and :615 | Compare with the diff. | :5 says R3 "updated section 7 and three entries of section 23.1". R3 also changed section 3 (:99 citation, :101 new snapshot_date sentence) and a fourth 23.1 entry (:611). :615 calls review findings 4 and 5 "two minor citation findings", but finding 5 is an unsupported cause note, not a citation. | documentation-engineer |
| 6 | minor | autograder-response.md:279 (B3 Answer target), :273 | Follow "docs/hadoop-job-contracts.md:216-227 (section 13.2)". | Section 13.2 is now at :286-307 and the file has 307 lines (:273 says 227). B1's Update (:267) marks its old answer as superseded; B3's Update (:281) does not say the line targets moved. | orchestrator (draft of a human-owned record) |

## Verified (evidence read)

**B1 against the code**
- The J05 partitioner and grouping (BlockingSort :363-396, classes :399-472, set at :564-565) are described correctly: 2, 3 or 4 group fields; partition prefixes `<family>\t<key>` and `<family>\t<key>\t<mention>`.
- Also correct: the map key kinds (:588-616), the reduce cases (:648-744) and the `largestReduceGroup` exclusion of markers (:650-659; FallbackJobs :290-292).
- The stale scaladoc at :321-331 and :476-480 is named as open review finding 1.

**Status line.** The exact line appears at hadoop-job-contracts.md:5, :246, :253, and in sections 5.1 and 6.1. It also appears at data-lineage.md:90, service-limits.md:120 and final-report.md:169 and :618.

**Line citations checked in the working tree**
- BlockSizeJobs.scala: every 5.1 and 6.1 row.
- FallbackJobs.scala: :106-122, :124-136, :138-245, :270-358, :384-426.
- BlockingChain.scala: :27-41, :49-118, :58-116, :69, :92.
- Pipeline.scala: :45-46, :50-61, :64-84, :88-92, :129-145, :146-153, :161-165, :173-208.
- Counters.scala: :47 and :75-102. Settings.scala: :222-225. PairJobs.scala: :69-80 and :96-138.
- defaults.xml: :141-142, :188-193, :208-212, :269-302, :280-302, :303-349, :336-343, :345-349, :350-366, :370-415, :378-392, :418-428.
- `setCombinerClass` is called only at BlockSizeJobs.scala:199 and :269.
- Test lines and counts: OversizeBlockStrategySuite has 10 tests, OversizeBlockAdversarialSuite 18, and JobLevelTeamScaleSuite :148 matches its description.

**B3.** Every number matches review section 3: 20 bytes means a 3-character name (no "XY"); about 52,000 and about 30,000 authors (35 bytes labelled as assumed); 1,351,974,000, 449,985,000 and 12,497,500 pairs; 693 bytes labelled as unrecorded; 937 and 312 GB; 0.9 to 2.8 TB over three written stages and 1.25 to 3.75 TB with the shuffle, with equal record size labelled as assumed; shards of 100,000; largest team 137. Option A is worded as an implicit clique. The spec quotes at :1155, :2245, :2718 and :2729 match.

**N4.** The manifest ranges (:37-50, :358-446, :447-521, :522-621, :549-562, :570-583, :598-604, :622-709) and dtd_sha256 at :410 are correct. crossref.md:12-21, openalex.md:11-20 and :19, and orcid.md:50-93 (26 rows) match. final-report.md:613 is still the enrichment bullet that the manifest cites at :562.

**Links and scope.** The anchors #51…, #61…, #131-…-fixed and #132… resolve (GitHub slugs). No non-record doc still uses the old 13.1 anchor. I found no claim that is not in the code, tests or reports, and no scope creep.

**Response R3 section.**
- Conditions, classification and decision rows: match runbook-log rows 120 and 130. The excluded set "A7-A17" equals "A7-A13, A14, A15/X3, A16, A17".
- R3-B1: test counts (9/9 with 2 failing under the mutation, 10/10, 17/17, 4/4, 52/52) match.
- R3-B3: content matches.
- R3-N4: DblpParsingSuite 11/11 and NormalizerSuite 6/6 match agent-report-dataset-R3.md:18. manifest_version is 1.
- R3-Tests: "23 against 5", "16 primary, 19 fallback", 66 pairs, and 151 suites / 2367 tests / 5 skipped / 418 of 418 all match.
- The Update lines under B1, B3 and N4 and the Third-run B3 bullet are consistent with these.

**Identifiers.** Added diff lines and the whole response draft were grepped for twelve-digit runs, runs of zeros, arn:aws, amazonaws, s3://, AKIA, IPv4, sqs. and N/N ratios: none found. The tutorial's sample application name is absent from the response draft and the 8 edited files.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: ScholarChain Phase 17, session 3, repair round R3, final review of the documentation (8 files) and of the response text "Repair round R3" plus the Update lines (read-only)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run by the reviewer. As reported: sbt check exit 0, hadoop 17 suites / 124 tests, contract tests 418 of 418 (/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/test-report-R3-2.md). The docs change no source.
VALIDATION RESULTS: Every new or changed claim in /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-docs-R3.txt was checked against modules/hadoop/**, tests/contracts/**, datasets/** and the R3 reports. The response R3 section was checked against the reports and runbook-log rows 120-137. Identifier scans are clean. One major finding (1) and six minor findings (2-6).
ASSUMPTIONS: The working tree is the state under review. Old line numbers of FallbackBlockingSuite were inferred from the offsets the docs agent itself applied to the J05b/J05c rows (no git for the reviewer). I read the course specification under docs/agent-runs/phase-17/ only to check quotes.
RISKS: Line citations into BlockingJob.scala shift when the scaladoc fix (Part B finding 1) lands. The B3 volumes still rest on the unrecorded 693-byte figure (labelled). datasets/scripts/validate.sh has still not run.
MANUAL ACTION REQUIRED: Route finding 1 (major) and, if the human wants, findings 2-5 to documentation-engineer in one repair pass. Finding 3's response wording and finding 6 go to the orchestrator for the human-owned response draft. Fill "R3-Review" with this verdict.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer repairs finding 1 (plus any minors the human picks), then a focused code-reviewer re-check of those lines. After APPROVE: identifier scans, then the human's commits, as separate steps.
