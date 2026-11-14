STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: ScholarChain Phase 3, code review of the full phase diff (datasets/** by dataset-engineer, modules/core/** by domain-engineer). Read-only. Special attention to invented licenses, identifiers and sources.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: Not run by me. I relied on the orchestrator's observations: `bash datasets/scripts/validate.sh` printed "Ran 30 tests ... OK" (I counted 30 test methods in test_datasets.py, which matches), and `sbt -batch check` reported 580/580 passed (290 core tests × 2 Scala versions). tests/contracts is empty.
VALIDATION RESULTS: I checked the numbers and identifiers by hand. All of the following hold:
- Estimate arithmetic:
  - The OpenAlex per-venue sum is 127,818.
  - The ratios 2422/2506 = 0.966 and 5468/4979 = 1.098 give 123,534 to 140,371 works.
  - The weighted pair instances come to about 1,499,981.
  - The distinct/instance ratios check out (0.683 to 0.849), as does the 1,024,488 to 1,273,485 range.
- The 12 ORCID file sizes sum to 290,958,772,512, which matches the manifest.
- Decompression ratio: 82,509,824 / 16,777,216 = 4.92.
- ORCID check digits are valid for 0000-0003-3226-9189 and 0000-0001-8209-698X; 0000-0000-0000-0000 is correctly invalid.
- Synthetic sample: 7 distinct pairs, as the manifest says.
- dblp key initials match their author lists (Sousa0J23, HofstatterC0Z23, 0003SM23, LernerFGBB0L22, ShenZWLDWCG23).
- Weekdays and timestamps agree: "Tue, 03 Nov 2026" is a Tuesday; 2026-10-14 is the second Wednesday of October; the Crossref `indexed` timestamps match their date-times.
- The cross-sample facts the manifest cites are present in the samples:
  - W4384652670 has a null author id and an ORCID.
  - LernerFGBB0L22 has ORCIDs for 2 of 7 authors.
  - Crossref has "Lovón Melgarejo".
  - 10.1145/3477495.3531753 has no Crossref ORCIDs.
  - The arXiv DOI sits in a separate ORCID group.
  - S4363608773 is SIGIR in all 4 OpenAlex works.
- Every identifier in the frozen wire JSON appears in datasets/samples/**:
  - dblp keys conf/sigir/Sousa0J23 and conf/sigir/2023
  - DOI 10.1145/3539618.3591892
  - ORCID 0000-0003-3226-9189
  - OpenAlex IDs W4366851189, A5080716895, S4363608773, I182534213, I4210166615
  - country code PT
  - The only invented values are fixture-labelled run, code and config inputs, `example-answer-model`, and the Topic fixture (finding 11).
- Real and synthetic labelling is consistent across the manifest, LICENSES.md, file banners and file names. The validator enforces it.
- Every "unknown" value has a reason, and estimates are marked `kind: estimate, verified: false`.
- No Scala 3 `enum`, `given`, `using`, `extension` or `derives` in modules/core.
- No hardcoded buckets, Regions, accounts, model IDs, windows or thresholds in production code.
- All files are inside their owners' territories, and modules/core/target is gitignored.
ASSUMPTIONS:
- I could not fetch any external URL. Remote facts (DROPS MD5 and size, Figshare MD5s, OpenAlex manifest totals, license page wording) are checked only for internal consistency, not against the live sources.
- The golden SHA-256 IDs are accepted on the strength of the agent's reported independent Python cross-check, which I cannot run.
RISKS:
- The 19 OpenAlex source IDs that drive the subset estimate have no recorded evidence (finding 4).
- The subset size and edge count are unmeasured estimates until the human approves the full dblp download.
- The ORCID and Crossref sample redistribution questions are open for the human.
MANUAL ACTION REQUIRED: The human should decide on, or ratify:
- the config file format (finding 9);
- the researcher-identity policy, if the human chooses the policy route in finding 1;
- subset rule dblp-subset-v1;
- ORCID and Crossref sample redistribution.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Route blocking findings 1 and 2 to domain-engineer and finding 3 to dataset-engineer, then re-review. After that, test-engineer adds cross-module contract tests under tests/contracts, and the human ratifies.

VERDICT: REQUEST_CHANGES

FINDINGS

1. BLOCKING (major). Owner: domain-engineer. File: modules/core/src/main/scala/org/scholarchain/core/StableIds.scala:61-80 (with Validator.scala:159-162).
   - Problem: `ResearcherId = digest32("researcher/v1", "dblp-name", min(dblpNames))`. Two different researchers whose name sets share the same minimum dblp name get the same ID.
   - Failure input: entity resolution splits the unnumbered dblp label "Wei Wang" into two people, giving two clusters that both contain "Wei Wang". Both get researcher:v1:H("Wei Wang"). A cluster {"A", "B"} and a cluster {"A"} also collide.
   - Impact: the graph silently merges the two people (identity collapse) under a frozen v1 identity contract, and no single-record validator can detect it. The report's statement that "Which mentions form a cluster is entity-resolution policy" overstates entity resolution's freedom: entity resolution can never split a dblp label.
   - Required change, one of:
     - (a) Anchor the ID on something cluster-unique, for example the code-point-minimum MentionId of the cluster (mentions are partitioned among researchers).
     - (b) Keep the formula, but state in the contract and the report that entity resolution may never split a dblp label and that dblpNames sets must be disjoint across researchers, and get the human to ratify that explicitly as an entity-resolution policy constraint.
   - Either way, update the docs, golden values and frozen files.

2. BLOCKING (major). Owner: domain-engineer. Files: modules/core/src/main/scala/org/scholarchain/core/Records.scala:53-66 (WorkRecord.openAlexWorkId, line 62), :69-79 (AuthorMention.openAlexAuthorId, line 76), :82-92 (CanonicalResearcher openAlexAuthorIds/orcid/institutionIds); frozen examples wire/v1/WorkRecord.json:10,16-27, AuthorMention.json:8,9-21, CanonicalResearcher.json:9-15,16-30.
   - Problem: each record has a single Provenance (one datasetId and one snapshotId), yet it carries facts from another source.
   - Evidence: the frozen WorkRecord has `openAlexWorkId: "W4366851189"`, but its provenance is `sourceType: dblp`, `datasetId: dblp`, `snapshotId: 2026-11-01`, `jobName: hadoop.parse-dblp`. AuthorMention has the same problem with A5080716895. CanonicalResearcher lists A5080716895 in sourceRecordIds but records no OpenAlex snapshot or retrieval date.
   - Impact:
     - This breaks the CLAUDE.md provenance invariant (source dataset and snapshot for every transformation).
     - It contradicts the dataset contract (datasets/schemas/identifiers-and-linkage.md:84-86: OpenAlex author IDs are unstable across snapshots; "Store the snapshot or retrieval date with every OpenAlex ID").
     - The domain report's claim (agent-report-domain.md:201) that OpenAlex IDs are carried "only as attributes with snapshot provenance" is not supported by the code.
   - Required change: do one of the following, then update the fixtures, frozen files and report to match.
     - Move enrichment links out of dblp-sourced records into separately provenanced link records.
     - Or extend Provenance to carry per-source entries (datasetId, snapshotId, sourceRecordIds) and validate that each foreign ID has a matching source entry.

3. BLOCKING (major). Owner: dataset-engineer. File: datasets/scripts/download_dblp.sh:89-95.
   - Problem: the bounded default uses `curl -r 0-N` and checks for HTTP 206 and the expected Content-Range only after the transfer ends. curl writes the whole body when a server ignores Range and answers 200.
   - Failure input: DROPS or a redirect target or CDN stops honouring Range.
   - Impact: the default, flagless invocation downloads the full ~1.1 GB corpus before the check rejects it. That breaks the human decision that scripts never download a full corpus without an explicit flag.
   - Required change: also pass `--max-filesize "$prefix_bytes"`, which aborts when the announced length is larger. Add a test or dry-run assertion that the flag is present.

4. Non-blocking (minor). Owner: dataset-engineer. File: datasets/dataset-manifest.yaml:54-69, 152-155.
   - Problem: 19 OpenAlex source IDs (16 venues plus 3 calibration venues) have no evidence entry, no recorded OpenAlex display_name, and no record of the query used to map each dblp prefix to its source. The samples confirm only S4363608773 (SIGIR).
   - Impact: these IDs drive the whole subset estimate, and estimate_subset_openalex.py uses them unverified. A wrong mapping silently skews the counts. This is the largest set of unevidenced identifiers in the manifest.
   - Required change: record each source's display_name, the evidence URL and checked_on, and have the estimate script assert that the display name matches.

5. Non-blocking (minor). Owner: dataset-engineer. File: datasets/scripts/dblp_subset_stats.py:229-231.
   - Problem: the manifest (lines 70-74) and the report (line 116) say "the subset run must fail if prefixes_with_zero_works is non-empty", but the script returns 0 in that case.
   - Required change: make it exit non-zero (or add a `--require-all-prefixes` flag used by verification_plan).

6. Non-blocking (minor). Owner: dataset-engineer. File: datasets/scripts/fetch_enrichment.py:196-203, 218-225, 125-134, 276.
   - Problem 1: `per_page` and `rows` equal the batch size, and `meta.count` is never compared with the number of results. A DOI that maps to more than one OpenAlex work truncates the page silently, so linkage data is lost.
   - Problem 2: the manifest's Crossref verification "DOI of every returned item must be in the requested set" (dataset-manifest.yaml:423) is not implemented.
   - Problem 3: HTTP failures are printed, but the script still exits 0.
   - Required change: page until results reach meta.count (or fail), implement the DOI-membership check, and exit non-zero when any request fails.

7. Non-blocking (minor). Owner: dataset-engineer. File: datasets/scripts/download_openalex_snapshot.sh:80-82.
   - Problem: `key="${url#s3://openalex/}"` comes from the remotely fetched manifest.json and is used unchecked in `mkdir -p` and as the output path. A value such as `s3://openalex/../../x` writes outside `--dest`.
   - Required change: require that key matches `^data/jsonl/[a-z-]+/updated_date=[0-9-]+/part_[0-9]+\.gz$`.

8. Non-blocking (minor). Owner: dataset-engineer. Files: datasets/scripts/download_dblp.sh:82-97; datasets/scripts/lib.sh:19-31; datasets/scripts/fetch_enrichment.py:248-250.
   - Problem 1: the prefix download is verified only against its own freshly computed sha256, not against the pinned `prefix_sha256` 62d59ff6... (manifest:134). Reproducibility of the calibration input is therefore not enforced.
   - Problem 2: the destination guards refuse only `datasets/`, not the whole repository, although their messages say "inside the repository".
   - Required change: compare the prefix with the pinned sha256 when prefix_bytes is 16777216, and widen the guards to the repository root.

9. Non-blocking (decision). Owner: domain-engineer (decision by the human). File: modules/core/src/main/scala/org/scholarchain/core/config/ConfigLoader.scala:47-63, 118-120.
   - Problem: the work order asks for "versioned HOCON or YAML files". The loader accepts strict JSON only, parsed with uJSON. JSON is a syntactic subset of both, but HOCON and YAML features such as comments are rejected.
   - The agent flagged this for ratification rather than taking the pre-approved Typesafe Config route. The human must ratify it, or route build-engineer to add com.typesafe:config.

10. Non-blocking (process). Owner: test-engineer, via the orchestrator. Location: tests/contracts/** (empty).
    - Problem: the work order says "Freeze representative JSON wire examples in contract tests". The frozen examples and their tests live in modules/core/src/test, which is legitimate domain territory, but no cross-module contract test reads them yet.
    - Required change: add contract tests under tests/contracts that load modules/core's wire/v1 files.

11. Non-blocking (minor). Owner: domain-engineer. Files: modules/core/src/test/resources/wire/v1/Topic.json:3-4; Records.scala:188.
    - Problem 1: the Topic fixture uses an invented topic. The ID is labelled `fixture-topic-...`, but the name "Temporal information extraction" and `sourceType: pipeline` / `spark.topics` do not appear in the samples.
    - Problem 2: the record doc says topicId is "the source's key" from an enrichment source, which contradicts that pipeline provenance.
    - Required change: label the name as a fixture as well, and make the doc and the provenance consistent.

12. Non-blocking (nit). Owner: dataset-engineer. File: datasets/dataset-manifest.yaml:556.
    - Problem: the synthetic sample's provenance asserts that Crossref reports "prefix 10.5555 (member 7822 'Test accounts')" without an evidence URL or checked_on in the manifest. The URL is only in the XML comment (dblp-synthetic-edge-cases.xml:7-10).
    - Required change: add an evidence entry to the manifest.

13. Non-blocking (minor). Owner: dataset-engineer. File: datasets/scripts/dslib.py:143, 171-174.
    - Problem: `ORCID_RE` uses Python's Unicode `\d`, and `int(ch)` accepts non-ASCII decimal digits. The Scala implementation (ExternalIds.scala:103) rejects them, so the reference implementation and the Scala contract disagree.
    - domain-engineer already raised this (agent-report-domain.md:199).
    - Required change: use `[0-9]` in dslib.
