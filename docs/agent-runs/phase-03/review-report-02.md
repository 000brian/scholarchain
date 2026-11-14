STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: ScholarChain Phase 3 re-review after one repair round (repair-01 by domain-engineer for findings 1, 2 and 11; repair-02 by dataset-engineer for findings 3-8, 12 and 13). Read-only. The aim is a verified APPROVE or REQUEST_CHANGES verdict.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: I did not run anything; I cannot. The orchestrator observed:
- `bash datasets/scripts/validate.sh`: "Ran 59 tests ... OK", RESULT: PASS. The 59 matches the test methods I listed in test_datasets.py.
- `sbt -batch check`: suites=14, executed=638, passed=638, [success]. That is 319 core tests run under both Scala 3 and Scala 2.13.
- No contract tests ran, because tests/contracts is empty (finding 10, deferred).
VALIDATION RESULTS: I checked the following by reading the code itself, not the repair reports.
- Finding 1:
  - The ResearcherId formula is at StableIds.scala:90-111.
  - Ties cannot happen: (workId, position) uniquely identifies a mention, so the order (year, workId, position) has no ties and the result does not depend on input order (StableIdsSuite:189 checks cluster.reverse).
  - The validator binds researcherId to anchorMentionId (Validator.scala:180-183).
- Finding 2:
  - The foreign-ID fields are gone from WorkRecord, AuthorMention and Venue (Records.scala:64-91, 224-229). Grep finds no openAlexWorkId, openAlexAuthorId or openAlexSourceId anywhere in modules/**.
  - Every OpenAlex ID in the frozen wire files now appears only in:
    - ExternalIdLink.json (openalex/2026-11-12, source record W4366851189);
    - CanonicalResearcher.json, where each entry is a SourcedExternalId with its own dataset, snapshot and record;
    - Institution.json (openalex provenance).
- Finding 3: ds_fetch_prefix (lib.sh:115-135) passes `--max-filesize "$n"` and pipes the body through `head -c "$n"`. Disk writes are therefore capped at N bytes even when the server ignores Range.
  - PrefixByteCapTests (test_datasets.py:519-531) assert that fewer than 16 MiB of a 64 MiB body are sent and that no file is kept.
  - The old uncapped `curl -r -o` command would fail those tests.
- New OpenAlex source names: all 19 display_names in dataset-manifest.yaml:54-133 and :227-239 match the table in repair-02.md:131-151 exactly. Each has an evidence entry (url naming the id, checked_on 2026-11-12, observed).
  - validate_datasets.py:149-167 requires these fields.
  - estimate_subset_openalex.py:84-94 and :119-123 re-resolves each source and exits 3 on any id or name mismatch.
- Crossref evidence for prefix 10.5555 is at dataset-manifest.yaml:645-647.
- Spot-checks against the samples:
  - A5080716895, I182534213 and I4210166615 occur in datasets/samples/openalex/works.jsonl on the W4366851189 line.
  - No invented licenses or sources were introduced by the repairs.
ASSUMPTIONS:
- I did not fetch the live OpenAlex or Crossref responses. I checked the 19 display names and the Crossref prefix evidence for consistency (manifest vs report vs validator), not against the live APIs.
- I accept the new golden IDs (researcher, mention position 1, edge, link, evidence) on the strength of the Python cross-check that domain-engineer reports and of the passing golden tests. I cannot recompute SHA-256.
RISKS:
1. ResearcherId uniqueness rests on two rules that no single-record validator can check, and no test checks them yet (StableIds.scala:83-85):
   - every mention belongs to exactly one researcher;
   - the anchor is the earliest of the researcher's authorships.

   The anchor also reads AuthorshipRecord.year, and nothing checks that this equals WorkRecord.year. These checks belong in the tests/contracts work for test-engineer and in spark-engineer's output tests.
2. All 19 evidence entries carry the same templated `observed: "...type conference"` text. That includes journals/pvldb ("Proceedings of the VLDB Endowment"), which might plausibly be typed as a journal. The verification script does not check `type` (estimate_subset_openalex.py:87 selects only id and display_name), so I cannot confirm this text. It does not affect any ID or count.
3. uPickle silently ignores unknown JSON keys. A stale producer that still writes `openAlexWorkId` would have that key dropped rather than rejected.
4. docs/agent-runs/phase-03/agent-report-domain.md (human-owned) still describes the old name-based researcher anchor and "OpenAlex IDs carried only as attributes". The human should annotate it as superseded by repair-01.
5. The subset estimate is still unverified until the human approves the full dblp download.
MANUAL ACTION REQUIRED: The human should ratify or decide the following:
- the earliest-mention researcher anchor and its documented stability rules (StableIds.scala:61-86);
- the ExternalIdLink and SourcedExternalId design;
- the dblp prefix to OpenAlex source mapping, and rule dblp-subset-v1;
- finding 9 (JSON vs HOCON or YAML configuration);
- ORCID and Crossref sample redistribution;
- approval of the full dblp download.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE:
- Route finding 10 and Risk 1 to test-engineer: tests/contracts loading wire/v1, plus cross-record checks for mention partitioning, the anchor, and year consistency.
- Optionally route new findings N1 and N2 to domain-engineer and N3 to dataset-engineer. None of them blocks the phase.
- Then the human ratifies the phase.

VERDICT: APPROVE

No critical or major finding remains verified. Findings 1, 2 and 3 are resolved in code, and each has a test that would fail without the fix.

PER-FINDING STATUS

| # | Severity (orig.) | Owner | Status | Evidence |
|---|---|---|---|---|
| 1 | major, blocking | domain-engineer | resolved | See Finding 1 detail below. |
| 2 | major, blocking | domain-engineer | resolved | See Finding 2 detail below. Residual minor gaps are N1 and N2. |
| 3 | major, blocking | dataset-engineer | resolved | See Finding 3 detail below. |
| 4 | minor | dataset-engineer | resolved | See Finding 4 detail below. |
| 5 | minor | dataset-engineer | resolved | `--require-all-prefixes` exits 3 on any zero-work prefix or a truncated input (dblp_subset_stats.py:220-243). verification_plan uses the flag (dataset-manifest.yaml:257-264). Tests: test_datasets.py:179-207. |
| 6 | minor | dataset-engineer | resolved | See Finding 6 detail below. |
| 7 | minor | dataset-engineer | resolved | Before any path is used, the script checks the s3://openalex/ URL prefix, the key against an anchored regex, entity equality and numeric meta (download_openalex_snapshot.sh:84-89; lib.sh:152-157). A failure inside the piped while-loop exits 4 and propagates. Test: test_datasets.py:582-593 (`../../x`, absolute path, embedded newline, wrong entity). |
| 8 | minor | dataset-engineer | resolved | See Finding 8 detail below. A residual nit is N3. |
| 9 | decision | human | deferred (human decision) | ConfigLoader is unchanged, as instructed. |
| 10 | process | test-engineer | deferred (not routed this phase) | tests/contracts is still empty. The sbt check output confirms "No contract tests executed". |
| 11 | minor | domain-engineer | resolved | The Topic doc now distinguishes source keys from job-assigned keys (Records.scala:244-247). The fixture name is labelled "fixture topic name (not from a source dataset)" (wire/v1/Topic.json). |
| 12 | nit | dataset-engineer | resolved | Evidence for prefix 10.5555 (member 7822 "Test accounts") and the HTTP 404 results for both synthetic DOIs are at dataset-manifest.yaml:645-647. The validator requires evidence on synthetic samples (validate_datasets.py:208-212). |
| 13 | minor | dataset-engineer | resolved | See Finding 13 detail below. |

Finding 1 detail:
- The ID is digest("researcher/v1", "anchor-mention", anchorMentionId) (StableIds.scala:91).
- The anchor is the minimum by (year, workId code point, position) (StableIds.scala:94-107).
- CanonicalResearcher carries anchorMentionId (Records.scala:136-144), and the validator binds the two (Validator.scala:179-183).
- Because each mention belongs to only one researcher, anchors are unique per cluster. This holds even when two researchers share every dblp name.
- Tests:
  - StableIdsSuite.scala:167-179 (two "Wei Wang" clusters, and {m1,m2} vs {m3});
  - StableIdsSuite.scala:181-203 (order and ties);
  - StableIdsSuite.scala:205-210 (stability);
  - golden values at StableIdsSuite.scala:43-60;
  - ValidationSuite.scala:266-287.

Finding 2 detail:
- dblp records must carry dblp provenance (Validator.scala:96-101, 148, 163, 295).
- Each ExternalIdType lists the sources allowed to assert it, and OpenAlex keys may come only from OpenAlex (Enums.scala:173-205).
- ExternalIdLink has a single provenance, a LinkId bound to the dataset and snapshot, and no dblp source; it also checks that the subject kind fits the ID type (Records.scala:266-275; Validator.scala:332-365).
- SourcedExternalId carries the dataset, snapshot and record for each researcher ID (Records.scala:102-126; Validator.scala:167-205).
- Institution must have openalex provenance (Validator.scala:302-306).
- Frozen files:
  - WorkRecord, AuthorMention and Venue contain no foreign IDs;
  - ExternalIdLink.json and CanonicalResearcher.json:9-50 name openalex/2026-11-12/W4366851189 or dblp/2026-11-01/conf/sigir/Sousa0J23 for each ID;
  - the `orcid` in GraphNode.json:6-8 can be traced through sourceRecordIds to the researcher's SourcedExternalId.
- Tests: ValidationSuite.scala:290-424 and StableIdsSuite.scala:71-100.

Finding 3 detail:
- `curl -r 0-(N-1) --max-filesize N -o - | head -c N` (lib.sh:115-135).
- The fetch succeeds only on curl exit 0, HTTP 206, the exact Content-Range and exactly N bytes. Anything else removes `.part` and returns 4.
- download_dblp.sh:100 uses this helper.
- Tests: test_datasets.py:448-541. The local server cases cover a server that honours Range, one that ignores it and sends a Content-Length, and one that ignores it with no length; the test also checks that the script greps for the flag.

Finding 4 detail:
- All 19 sources have a display_name and evidence (dataset-manifest.yaml:54-133, 227-239).
- The mapping caveat is recorded at dataset-manifest.yaml:140-149.
- The estimate script checks id and name (estimate_subset_openalex.py:57-94, 119-123).
- The validator enforces it (validate_datasets.py:149-167).
- Names match repair-02.md:131-151 exactly.
- Tests: test_datasets.py:275-338.

Finding 6 detail:
- OpenAlex queries page until meta.count is reached, and Crossref queries until total-results is reached. An empty page before the end, or more than MAX_PAGES pages, counts as a failure (fetch_enrichment.py:231-277).
- Returned identifiers must belong to the requested batch (fetch_enrichment.py:280-321).
- HTTP, network and JSON errors are recorded as failures (fetch_enrichment.py:141-169), and the script then exits 1 (fetch_enrichment.py:373-377).
- Tests: test_datasets.py:368-445.

Finding 8 detail:
- The 16 MiB prefix is checked against the pinned sha256 62d59ff6..., on fresh fetches and on the skip path (download_dblp.sh:31, 91-103; lib.sh:139-147).
- The destination guard now covers the whole repository root (lib.sh:16-39). The Python cache guard uses realpath (dslib.py:262-266; fetch_enrichment.py:342-344).
- Tests: test_datasets.py:545-580.

Finding 13 detail:
- The ORCID regex uses ASCII `[0-9]` with fullmatch (dslib.py:146, 176).
- The check digit rejects non-ASCII digits (dslib.py:150-156).
- The homonym suffix is ASCII `[0-9]{4}` (dslib.py:215).
- Tests: test_datasets.py:93-107 and 120.

NEW FINDINGS (all non-blocking)

N1. Minor. Owner: domain-engineer. Files: modules/core/src/main/scala/org/scholarchain/core/Validator.scala:167-173 (sourcedExternalId) and :332-365 (externalIdLink, via Provenance :119-131).
- Problem: datasetId is checked only for its format (`[a-z0-9][a-z0-9-]*`), never against sourceType.
- Failure input: `SourcedExternalId(OpenAlexAuthor, "A5080716895", SourceType.OpenAlex, "crossref", "2026-11-12", "W4366851189")` validates clean. So does an ExternalIdLink with sourceType=openalex and datasetId="dblp", provided its LinkId is computed from those values.
- Impact: the finding-2 repair makes datasetId plus snapshotId the provenance anchor for each foreign ID, but a mislabelled dataset is accepted. A consumer that resolves the snapshot through datasetId reaches the wrong manifest dataset.
- Required change: tie datasetId to sourceType for source datasets (dblp→dblp, openalex→openalex, crossref→crossref, orcid→orcid, matching datasets/dataset-manifest.yaml), or document why it is free. Add a rejecting test.

N2. Minor. Owner: domain-engineer (contract text); cross-record check by test-engineer or spark-engineer. Files: Records.scala:295-304 (GraphNode), Validator.scala:405-411; frozen GraphNode.json:6-8.
- Problem: `properties` is a free-form string map. The `orcid` in the frozen example can be traced through sourceRecordIds to the researcher's SourcedExternalId, but no rule or doc requires this.
- Failure input: `GraphNode(label=researcher, properties={"openalexAuthorId":"A999"}, provenance=pipeline, sourceRecordIds=[researcherId])` validates even when that researcher has no such SourcedExternalId.
- Impact: a foreign identifier with no snapshot provenance can reach Neptune and RAG evidence. This is the same class of gap as original finding 2, but no consumer exists yet. repair-01.md:47 discloses the risk.
- Required change: state in the GraphNode scaladoc that external-identifier properties must be copied from a SourcedExternalId or ExternalIdLink of a record named in provenance.sourceRecordIds. Add that rule as a cross-record output invariant.

N3. Nit. Owner: dataset-engineer. File: datasets/scripts/lib.sh:30-35 (comment at :25-26).
- Problem: the "lexical" check does not normalise `..` or follow symlinks before `mkdir -p`.
- Failure input: run from `$HOME/Downloads` with `--dest ../Developer/repos/scholarchain/modules/x`.
  - The lexical test sees `$HOME/Downloads/../Developer/...` and lets it pass.
  - `mkdir -p` then creates `modules/x` inside the repository.
  - The second, resolved check refuses with exit 2.
- Impact: an empty stray directory is left in the repository. No data is written, but this contradicts the comment "so nothing is created in the repository".
- Required change: resolve the deepest existing ancestor (or use `realpath -m` or a Python normpath plus realpath) before any mkdir. Add a test case with a `..` path.
