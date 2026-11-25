Saved by the orchestrator from code-reviewer's reply. One redaction: in section 5, the name of Crossref's public data bucket (already committed in datasets/dataset-manifest.yaml) is replaced by "[Crossref's public bucket name]", because reports never carry bucket names. Nothing else changed.

VERDICT: APPROVE

No critical or major finding is open. B1 is correct against the specification. The tests would catch a regression. The N4 values have committed sources. Of the B3 numbers, most are verified and a few cannot be verified. Six minor findings are listed below. They can go into the documentation step or a later round.

## Findings

| # | Severity | file:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| 1 | minor | modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala:321-331 (BlockingSort scaladoc) and :476-480 (J05 scaladoc) | Someone reads the J05 contract scaladoc | The scaladoc still says J05 is "Partitioned by TwoFieldPrefixPartitioner and grouped by TwoFieldPrefixGroupingComparator", with "one reduce call per `<family>\t<key>`". The job now uses BlockingPartitioner and BlockingGroupingComparator (:564-565), with 4-field member groups and 3-field marker groups. The oversize-strategy paragraph at :494-510 is correct. This is stale contract text, and documentation-engineer may cite it. | hadoop-engineer |
| 2 | minor | R/agent-report-hadoop-R3.md B3 section ("minimal distinct `<author>XY</author>` elements are 20 bytes") | Byte count of the literal | `<author>XY</author>` is 19 bytes. Twenty bytes is right for a 3-character name (8+3+9). A 3-character name is also needed for about 52,000 distinct names; 2 ASCII characters give only a few thousand. The about-52,000 result stands (1,048,576 / 20 = 52,428). Report text only; the docs must not copy the "XY" form. | hadoop-engineer (report); documentation-engineer when writing B3 |
| 3 | minor | Same report, "about 693 bytes per pair record (6,930 bytes for 10 pair instances)" | Look for a committed record | No committed file holds this measurement. I grepped for 6930, 6,930 and "693 bytes" outside `target/`. The volume figures (937 GB, 312 GB, TB totals) inherit this unverified basis. | hadoop-engineer (report); documentation-engineer must label it as an unrecorded local measurement |
| 4 | minor | datasets/dataset-manifest.yaml:558 | Read docs/agent-runs/phase-03/agent-report-dataset.md:3 and :38 | The manifest says ":3 (sources checked and samples fetched on 2026-11-12…)". Line :3 dates only the source checks. Line :38 records the Crossref batch request with no date. The date 2026-11-12 is still fully supported by LICENSES.md:22 and samples[].attribution (now :770; :729 at cdd36c8 is correct). Only the citation over-reads. | dataset-engineer |
| 5 | minor | datasets/dataset-manifest.yaml:581 (sample_note) | Look for the origin of "9,347 bytes" | The note says the 9,617 vs 9,347 difference comes from "re-serialised with sorted keys (make_samples.py jsonl())". No committed record says how 9,347 was measured; it appears only at manifest :568. Sorting keys alone does not change length; `json.dumps` default separators (make_samples.py:143-144) would. The cause is an unsupported claim; only the two numbers are recorded. | dataset-engineer |
| 6 | minor | tests/contracts/.../OversizeBlockAdversarialSuite.scala, deterministic test (:533) | Layout change that alters only the `largestReduceGroup` subject text | test-engineer recorded this itself: the byte comparison of stats/ drops the `largestReduceGroup` line. The value is checked by the test at :578, and the module test OversizeBlockStrategySuite.scala:384-391 compares stats/ byte for byte. Low impact; listed for completeness. | test-engineer |

## 1. B1 (HADOOP-04) checks

- **Spec** (course-specification.md:2245). All four parts are present:
  - maximum block size: `BlockLimit`, defaults XML :281, :320
  - fallback keys: `FallbackKeys`
  - counters: Counters.scala:75-102
  - oversize policy: FallbackJobs.scala:106-122
- **No reduce group over the cap.**
  - J05p and J05bp: the combiner sums counts, and the reducer only sums (BlockSizeJobs.scala:87-150).
  - J05 mapper: diverts every value of a listed key to `#member…` keys, plus one `#size` marker per map task (BlockingJob.scala:599-614).
  - A block group therefore holds at most the cap. A member group holds the copies of one mention. A marker group holds one value per map task.
  - J05b: still-oversize values never leave the mapper (FallbackJobs.scala:235-239). The reducer buffers at most maxBlock (:286).
- **Sort, partitioner and grouping are consistent.**
  - Keys are Text, sorted as UTF-8 bytes. Each group prefix ends at a tab, and 0x09 never appears inside a multi-byte UTF-8 sequence. So keys with equal prefixes are contiguous.
  - BlockingPartitioner uses the same fields as the group, or fewer: family, key, mention for members; family, key for markers and blocks. Every key of a group therefore lands in one partition.
  - The raw compare skips the vint header (:435-439); the object compare uses getBytes and getLength (:446-455). They match.
  - The unit test at OversizeBlockStrategySuite.scala:442-484 checks raw against object comparison and the order against the sort.
- **Determinism.** Partitions depend only on the key. DivertedSizes markers are emitted in code-point order. MaxTracker breaks ties by code point (TaskIO.scala:145-156). The three-layout test and the many-maps test check this byte for byte.
- **No lost or duplicated candidates.**
  - Every value of a listed key is streamed by `rekeyedMember`, duplicate copies included, as the old path streamed them.
  - The summed `n` equals the old `n`.
  - The equality tests pass: module test :187-221 and contract test :331.
  - Only the spread of oversizeMembers over part files changes. J05b, J05bp and J05c do not depend on that order.
- **Still-oversize policy and J05c coverage.**
  - The marker sum equals the old group size, so the fallbackOversizeBlocks/ listing is unchanged.
  - Coverage and uncovered members are checked at module :249-284 and contract :308.
- **Pre-pass refusal.** OversizeKeySet.load fails on a malformed line, a wrong keyVersion, or a different effective limit (BlockSizeJobs.scala:58-74). Five contract tests (:620-666) cover this. A stale list from another input is not detected, but the reducer-side guard keeps the records correct (contract :666).
- **Counters add up.** Contract :378-431 checks each identity. Examples: keys counted = emitted + singletons + oversize; J05p found = J05 OVERSIZE_BLOCKS; J05bp found = STILL_OVERSIZE_BLOCKS; covered + uncovered = oversize members.
- **Run IDs and reuse.**
  - BlockingChain.scala:69 and :92 add the pre-pass run to `fromRuns`.
  - `scholarchain.path.oversizeKeys` is a path key and is not fingerprinted (Settings.scala:139-141, :225).
  - `Job.getInstance(conf)` copies the configuration (JobRunner.scala:134), so the path does not leak into later jobs.
  - Contract test :686 checks this wiring.
- **Distribution checks.** Reducer memory is bounded in every job. The combiner is associative and commutative, with Text in and Text out. Re-keyed members spread over all reducers. The oversize-key set per map task holds at most V/(cap+1) entries, which is about 29,850 for 6 million values at cap 200.
- **Does the repair test fail on the old code?** Yes, by reading; I did not run it.
  - OversizeBlockStrategySuite.scala:374-383 writes one input file per line, so there are 8 J05 map tasks, independent of split size.
  - W1 to W5 are in 5 of those files, so the `#size` group for "Wei Wang" gets 5 values.
  - The old reducer offered marker groups to `largestReduceGroup` (repair-hadoop-R3.md, Cause). So the stat would be 5 and `assert(g.value <= 3)` at :382 would fail, as would the stats/ byte comparison at :385-389.
  - The earlier "c" layout (3 files) gave only 3 markers, which is at the cap. That is why the original suite missed the defect.
  - The earlier mutation check (pre-pass ignored, 2 failures) shows the other tests are real.

## 2. N4 checks

Each check below was against committed files:

- **Snapshot date and retrieval.**
  - LICENSES.md:22: "Crossref REST API, 2026-11-12". Verified.
  - samples[] attribution, manifest :770 (:729 + 41 added lines): verified.
  - hadoop-pipeline.log:143 shows `crossref/snapshot=2026-11-12`: verified.
  - phase-05/human-gate.md:93 and final-report.md:613: quotes verified.
  - phase-03 report :92 says the bulk file is "not scripted": verified.
- **Crossref sample values.**
  - Latest `indexed` is 2026-11-04T01:01:03Z on DOI 10.1145/3539618.3591687 (line 2): verified.
  - bytes 9617, records 4 and sha256 equal the samples[] entry at :772-774: verified. I could not run wc or shasum myself.
- **make_samples.py references.** :205-207 (message.items only), :150-151 (`val()`), :172 (`orcid-path`) and :143-144 (`jsonl`, sort_keys): verified.
- **OpenAlex.**
  - Latest `updated_date` is 2026-11-10T11:39:43.021295: verified.
  - `raw_orcid` appears on all 17 authorships of the 4 works: verified (17 `raw_orcid` and 17 `author_position` occurrences).
- **ORCID types.**
  - Counts verified against records.jsonl: 2 groups, 5 summaries, 15 external-id entries (5 on groups, 10 on summaries).
  - Enumerations verified: doi, eid, source-work-id, isbn, issn; self, part-of; conference-paper, other.
  - Null-only fields verified: credit-name and external-id-normalized-error.
  - Nullable fields verified: month, day and url.
  - "Message schema v3.0" matches manifest :666.
- **"Not recorded" reasons.** They hold for the files I read. "Unchanged since 6788952" cannot be checked with read-only tools (no git).
- **YAML structure.** The new keys nest under the existing `snapshot`, `size_estimate` and `schema` blocks. Every new `unknown` has a non-empty `*_unknown_reason`, which `walk_unknowns` (validate_datasets.py:62-72) requires. No validator placeholder pattern is matched.
- **Identifiers.** None added. The ORCID iD in orcid.md:68 is a public research identifier that was already committed (orcid.md:112, manifest :781).

## 3. B3 numbers

1. `scholarchain.teams.hardLimit` default 5000. **Verified.**
   - defaults XML :189-190.
   - Read at PublicationTeamJob.scala:133.
   - Enforced at TeamAssembler.scala:75-76 and on the overflow path at PublicationTeamJob.scala:148-151 and :159-165.
2. `scholarchain.dblp.maxRecordBytes` 1,048,576. **Verified.**
   - defaults XML :141-142.
   - Read at ScholarChainInputFormats.scala:75.
   - Bounds the kept bytes at DblpRecordScanner.scala:243; a longer record becomes an oversize reject at :308.
3. Author element sizes and author counts. **Arithmetic verified; wording partly wrong.**
   - 20 bytes is correct for a 3-character name. The literal `<author>XY</author>` is 19 bytes (finding 2).
   - 1,048,576 / 20 = 52,428, so "about 52,000" holds.
   - 1,048,576 / 35 = 29,959, so "about 30,000" holds. The 35-byte realistic size is an assumption with no measurement.
4. Pair counts. **Verified.**
   - 52,000 × 51,999 / 2 = 1,351,974,000.
   - 30,000 × 29,999 / 2 = 449,985,000.
   - 5,000 × 4,999 / 2 = 12,497,500, which also matches the XML :192.
5. About 693 bytes per pair record. **Not verifiable.** No committed record (finding 3).
6. Volumes per pair stage. **Arithmetic verified on that basis.**
   - 693 × 1,351,974,000 ≈ 937 GB.
   - 693 × 449,985,000 ≈ 312 GB.
7. Total of 1 to 3.7 TB. **Approximately right, but the two ends count different stages.**
   - With four stages (J08a, J08b, J09, J08b shuffle) the range is 1.25 to 3.75 TB.
   - With the three written stages it is 0.94 to 2.81 TB.
   - Equal record size across J08b and J09 is assumed, not measured.
8. J08a shards of 100,000 pairs, streaming. **Verified.**
   - Default `pairsPerShard` 100000 (XML :208-209).
   - The mapper emits one PairShard per shard, carrying the full team (PairJobs.scala:73-80).
   - The reducer expands one shard with `foreachPair`, emitting as it goes (:111-137).
   - 1,351,974,000 / 100,000 rounds up to 13,520 shards. The about 7 to 13 GB shuffle estimate is consistent with that.
9. Teams above the limit are counted and written to rejects. **Verified.**
   - Reason `team_exceeds_hard_limit` (Disposition.scala:112).
   - Counted as OVERSIZE_AUTHOR_TEAMS (PublicationTeamJob.scala:169) and as a reject with the reason.
   - Contract test JobLevelTeamScaleSuite.scala:148-224 checks both paths, including the reject-reason counter.
10. Largest real team 137. **Verified.**
    - experiments/raw/p15-a-hadoop/…/hadoop-job-counters.csv:83 (`maxTeamSize` 137); `maxTeamPairs` 9316 = 137 × 136 / 2 at :82.
    - No team hit the hard limit: OVERSIZE_AUTHOR_TEAMS 15 equals the 15 `oversizeTeams` records (:76, :81).
11. "Option A would move the expansion to Spark." **Not verifiable as a code fact, and only partly accurate.**
    - Option A is a design proposal; no code implements it.
    - It moves the handling of large teams to Spark, which would derive per-researcher counts from membership and use a work-hub graph.
    - It avoids the n(n-1)/2 volume only if Spark keeps that implicit form. If Spark expanded the pairs, the same terabyte-scale volume would come back.
    - The docs should say "replaces materialized expansion with an implicit clique handled in Spark", not "moves the expansion".

## 4. Ownership

Every changed file is in its owner's territory:
- modules/hadoop/** by hadoop-engineer
- tests/contracts/** by test-engineer
- datasets/** by dataset-engineer

Nothing changed outside modules/hadoop/**, tests/** and datasets/**.

Process notes only: the dataset-engineer ran `python3 -I` and `ruby` readers from the scratchpad, and the hadoop-engineer ran `python3 --version` and `kill`. Neither wrote a repository file.

## 5. Secrets and identifiers

- No key, account value, bucket name or ARN appears in the changed Scala, test or schema files (grep for twelve-digit numbers, AKIA, arn:aws, s3://).
- In the manifest, the only twelve-digit matches are two dataset byte counts that were already committed (OpenAlex :479, ORCID :671). They are outside the new lines and are not account values.
- [Crossref's public bucket name] is Crossref's public bucket and was already committed.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17, session 3, repair round R3, Part B review (B1 HADOOP-04, R3 tests, N4 DATA-02/DATA-05, B3 numbers), read-only
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run by the reviewer. As reported and consistent with the code read: hadoop 52/52 targeted (OversizeBlockStrategySuite 10/10); contractTests 30/30 targeted (OversizeBlockAdversarialSuite 18/18); full sbt check exit 0, marker 2026-11-24 23:40:16 (R/test-report-R3-2.md).
VALIDATION RESULTS: B1 checked against course-specification.md:2245 and found correct (section 1). N4 citations checked file by file (section 2). B3 numbers checked against the code and the P15 record (section 3). Ownership and secrets are clean.
ASSUMPTIONS: The working tree is the diff under review (no git available to the reviewer). "Unchanged since 6788952" and the sample byte counts and sha256 values were not recomputed; they match the samples[] entries the validator enforces.
RISKS: The dataset validator (`bash datasets/scripts/validate.sh`) has not run on the edited manifest; it is checked only statically. The B3 volume figures rest on an unrecorded 693-byte measurement. Stale scaladoc in BlockingJob.scala (finding 1).
MANUAL ACTION REQUIRED: Run `bash datasets/scripts/validate.sh` in an approved step. Route minor findings 1 to 6 to their owners if the human wants them repaired; none blocks.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation-engineer updates the docs for B1, B3 and N4. It should use the corrected B3 wording in section 3: 20 bytes means a 3-character name; 693 bytes per pair is unrecorded; the TB range has inconsistent ends; option A is an implicit clique, not a moved expansion.
