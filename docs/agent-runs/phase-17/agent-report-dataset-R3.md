STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: ScholarChain Phase 17, session 3, repair round R3, N4 (instructor-approved in writing; human-authorized). Add the missing dataset-record fields found by the TA autograder (V2 DATA-02, DATA-05): Crossref schema version, snapshot date, and observed size in the manifest; a schema version in the OpenAlex and Crossref schema notes; fuller field types in the ORCID schema notes. Values come only from committed records or committed sample files. No network, no script execution, datasets/** only.
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/datasets/dataset-manifest.yaml (+41 lines, additions only)
- /Users/brian/Developer/repos/scholarchain/datasets/schemas/crossref.md (+11 lines)
- /Users/brian/Developer/repos/scholarchain/datasets/schemas/openalex.md (+11 lines)
- /Users/brian/Developer/repos/scholarchain/datasets/schemas/orcid.md (+45 lines)
COMMANDS RUN (all read-only except the sbt test run):
- wc -c, wc -l, ls -l, shasum -a 256 on datasets/samples/** (sizes, line counts, sha256)
- python3 -I one-off readers (script in the session scratchpad, not in the repo) that walk the JSON of datasets/samples/orcid/records.jsonl, datasets/samples/crossref/works.jsonl and datasets/samples/openalex/works.jsonl to list JSON types, counts, and the indexed/updated_date values
- ruby -ryaml: parsed the edited manifest; checked that every "unknown" value has a non-empty *_unknown_reason (same rule as validate_datasets.py walk_unknowns)
- git log / git diff --stat (read-only) on datasets/**, to confirm the field lists have not changed since 6788952
- grep over datasets/**, docs/agent-runs/**, experiments/evaluation/raw/** (read-only), modules/**, tests/** (to find manifest readers)
- sbt -batch "hadoop/testOnly org.scholarchain.hadoop.DblpParsingSuite org.scholarchain.hadoop.NormalizerSuite"
TEST RESULTS:
- sbt -batch "hadoop/testOnly org.scholarchain.hadoop.DblpParsingSuite org.scholarchain.hadoop.NormalizerSuite": exit 0. DblpParsingSuite 11 total, 0 failed. NormalizerSuite 6 total, 0 failed. "Passed: Total 17, Failed 0, Errors 0, Passed 17".
  - Why these suites: no sbt test parses the manifest's content. modules/hadoop/src/test/scala/org/scholarchain/hadoop/TestSupport.scala:16-17 and tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/AdversarialSupport.scala:68 use datasets/dataset-manifest.yaml only to find the repository root. The two suites use TestSupport.repoRoot and read the committed samples (DBLP; OpenAlex, Crossref and ORCID through the normalisers). The samples were not changed.
  - Caveat: the hadoop tree has uncommitted changes from another agent (BlockingJob, FallbackJobs, and others; see the git status). The suites ran against that working tree. I did not touch those files.
  - The contractTests adversarial suites were not run. Their only manifest dependency is the same root-marker lookup, which the run above already exercises.
VALIDATION RESULTS:
- The repository's dataset validator is datasets/scripts/validate_datasets.py, with datasets/scripts/test_datasets.py and the wrapper datasets/scripts/validate.sh (manifest validation_command). It is the only validator that reads the manifest's content. I did NOT run it because the work order forbids running anything under datasets/scripts/** in this run. It needs to be run by an approved step: `bash datasets/scripts/validate.sh`.
- Static compatibility check against validate_datasets.py (read, not run):
  - every new "unknown" has a non-empty *_unknown_reason (checked with ruby, see above);
  - no new email, credential, placeholder, or s3:// literal;
  - DATASET_REQUIRED and the sample checks are unchanged;
  - the samples, their sha256 and their bytes are unchanged.
- The manifest parses as YAML (Ruby Psych, the fallback parser dslib.load_yaml uses when PyYAML is missing; PyYAML is not installed here).
ASSUMPTIONS:
- "unknown" plus *_unknown_reason, the manifest's existing convention that the validator enforces, is how the manifest writes "not recorded". Every such reason starts with "not recorded:". The schema notes say "not recorded" in words.
- A projection schema version is a ScholarChain definition (version 1 = the committed field selection), not a source fact. So it is defined here, labelled as ScholarChain-defined, and tied to commit 6788952. It is not a claim about Crossref's or OpenAlex's own schema.
- manifest_version stays 1. The new keys are additive and optional, the same precedent as Phase 5's subset_rule.measurement (git diff 6788952 2297990). No reader depends on them; manifest_version is read only as a required key by validate_datasets.py:46.
- The Crossref snapshot_date is the API retrieval date of the committed sample, not a Crossref release date. snapshot.id stays unknown with its original reason.
RISKS:
- validate_datasets.py / test_datasets.py were not executed in this run, so the manifest gate is checked statically, not by observed validator output.
- The manifest cites itself by key path plus "line 729 at commit cdd36c8", and it cites reports/final-report.md:613. These line references go stale if those files are edited.
- The ORCID types are those observed in a single record. Types not seen (for example a non-null credit-name, or a non-null external-id-normalized-error) are explicitly not claimed.
MANUAL ACTION REQUIRED:
- Run the dataset validator in an approved step: `bash datasets/scripts/validate.sh` (expect "RESULT: PASS (0 error(s))").
- Route the documentation updates listed under "Docs that now need updating" to documentation-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: dataset validator run (approved step), then code-reviewer review of the datasets/** diff, then documentation-engineer updates the N4 known-limitation text.

## N4

Finding (autograder V2 DATA-02 and DATA-05, met -> partial; docs/agent-runs/phase-17/autograder-response.md:317-321):
- The Crossref manifest entry lacked a schema version, a real snapshot date, and an observed size.
- The OpenAlex and Crossref schema notes had no schema version.
- The ORCID notes gave few field types.

Why it is a genuine defect: the role contract requires every dataset to be explicit and versioned, with sizes recorded. For Crossref, the manifest held only `snapshot.id: unknown` and estimates. The snapshot date existed in committed records (the sample attribution, LICENSES.md, the pipeline experiment log) but was never surfaced as a field. The observed sample size was enforced by the validator, but only under samples[], not on the dataset record. Neither schema note stated any version, even one marked unknown. The ORCID notes listed kept fields without types, although the committed sample shows them. A reader of the dataset record could not tell which data state or schema the enrichment inputs had, or that these values were not recorded.

Fields added (manifest line numbers are after the edit):

Manifest, Crossref entry (datasets/dataset-manifest.yaml):
1. snapshot.snapshot_date: "2026-11-12" (:553). Kind: API retrieval date of the committed sample, not a Crossref release (:554). Sources (:555-559):
   - samples[crossref].attribution "retrieval 2026-11-12" (:729 at cdd36c8; now :770)
   - datasets/LICENSES.md:22
   - docs/agent-runs/phase-03/agent-report-dataset.md:3 and :38
   - experiments/evaluation/raw/repro/e5-pipeline-20261122T1715Z-79b5583/hadoop-pipeline.log:143 (crossref snapshot=2026-11-12)
2. snapshot.snapshot_date_consistency (:560): the latest `indexed` timestamp in the sample is 2026-11-04T01:01:03Z, which is before 2026-11-12. Read from the sample file.
3. snapshot.bounded_corpus_retrieval_date: unknown (:561). Reason (:562): not recorded, because the full subset fetch has not run. Sources: docs/agent-runs/phase-05/human-gate.md:93 and reports/final-report.md:613.
4. size_estimate.observed (:570-585), kind: measurement, measured_on 2026-11-24:
   - sample_bytes 9617 (wc -c and ls -l)
   - sample_records 4 (wc -l)
   - sample_sha256 6f806e5874353226c57d62366790fb2b8221e5bf2c4ce9539c226d80034a5a40 (shasum -a 256)
   - each equals the samples[] entry that the validator enforces
   - sample_note: why 9,617 differs from the 9,347-byte estimate basis (sorted-key re-serialisation, make_samples.py jsonl())
5. size_estimate.observed.subset_bytes: unknown. Reason: not recorded, because the subset fetch has not run.
6. size_estimate.observed.public_data_file_bytes: unknown. Reason: not recorded as a measurement. The 208 GB figure is the blog's statement, and no committed record shows a download (docs/agent-runs/phase-03/agent-report-dataset.md:92).
7. schema.projection_schema_version: 1 (:601). Definition (:602): ScholarChain-defined, version 1 = work_fields_selected plus the shapes in crossref.md as committed in 6788952; `git diff --stat 6788952 HEAD -- datasets/` shows no change to them.
8. schema.source_schema_version: unknown (:603). Reason (:604): not recorded. The sample keeps message.items only (datasets/scripts/make_samples.py:205-207), and no committed record names a Crossref API or metadata schema version.
9. Comment at :17-20 explains why manifest_version stays 1.

datasets/schemas/crossref.md, new section "Schema version" (:12-21):
- projection schema version 1 (ScholarChain-defined, same definition as the manifest)
- Crossref source schema version: not recorded, with the reason
- data date 2026-11-12 (sample retrieval), pointing to manifest snapshot.snapshot_date

datasets/schemas/openalex.md, new section "Schema version" (:11-20):
- projection schema version 1 (ScholarChain-defined; work and author field lists as committed in 6788952)
- OpenAlex source schema version: not recorded. Committed records identify OpenAlex data only by date (snapshot.id 2026-10-14, dated release-note entries); the sample keeps no `meta` envelope.
- data state of the sample: API retrieval 2026-11-12 (manifest samples[].attribution). The latest `updated_date` in works.jsonl is 2026-11-10T11:39:43.021295. All 17 authorships in the 4 sample works carry `raw_orcid` (fields read from the sample file).

datasets/schemas/orcid.md, new section "Field types (committed sample projection)" (:50-93):
- A table of 26 projected paths with their observed JSON types, read from datasets/samples/orcid/records.jsonl: 1 record, 2 groups, 5 work summaries, 15 external-id entries (5 on groups, 10 on summaries).
- Observed enumerations:
  - external-id-type: doi, eid, source-work-id, isbn, issn
  - relationship: self, part-of
  - work type: conference-paper, other
- publication-date parts are {value: string}, zero-padded month.
- Types never observed are not claimed: name.credit-name and external-id-normalized-error are null only.
- States which API `{value}` wrappers the projection unwraps (make_samples.py:150-151; orcid-path at :172). Schema version: ORCID message schema v3.0, already in the manifest format.api and in the sample `_projection` text.

Tests with exact counts: sbt hadoop/testOnly DblpParsingSuite (11/11 passed) and NormalizerSuite (6/6 passed). Total 17, failed 0, errors 0, exit 0. The dataset validator (datasets/scripts/validate_datasets.py, test_datasets.py) was not run because it is forbidden in this run. Static checks are listed under VALIDATION RESULTS.

Files: the four listed under FILES MODIFIED.

## Docs that now need updating (documentation-engineer; not edited by me)

- /Users/brian/Developer/repos/scholarchain/reports/final-report.md:615: the N4 known-limitation bullet now misstates the dataset records. Its line references are also stale: manifest :561-563, :540-542 and :548-551 moved, and orcid.md:50-53 is now the new types section. It should say what was added and what stays not recorded: the Crossref source schema version, the bounded-corpus retrieval date, the subset bytes, and the OpenAlex source schema version.
- /Users/brian/Developer/repos/scholarchain/docs/agent-runs/phase-17/autograder-response.md:317-321 (N4 answer): human-owned. The orchestrator or human updates it with the human's approval.
- /Users/brian/Developer/repos/scholarchain/README.md:442 and /Users/brian/Developer/repos/scholarchain/docs/demo-script.md:107: these describe the manifest as having "snapshots and checksums" per entry. They are still accurate. Optionally mention the Crossref snapshot date (sample retrieval) and the projection schema versions.

## Dataset table (changed entries only)

| Source | License | Snapshot | Checksum | Size | Subset |
|---|---|---|---|---|---|
| Crossref | unknown SPDX (unchanged; "no copyright asserted" statement) | id unknown (unchanged); snapshot_date 2026-11-12 (sample API retrieval); bounded corpus retrieval: not recorded | sample sha256 6f806e58...5a40; published checksums unknown (unchanged) | observed sample 9,617 bytes, 4 records; subset bytes not recorded; public file 208 GB (blog statement, not measured) | dblp-subset-v1 (unchanged) |
| OpenAlex | CC0-1.0 (unchanged) | 2026-10-14 snapshot; sample API retrieval 2026-11-12 (unchanged) | unchanged | unchanged | unchanged |
| ORCID | CC0-1.0 file / ToS API (unchanged) | 2026 file (unchanged) | unchanged | unchanged | unchanged |

## Unresolved dataset questions

- The Crossref and OpenAlex source schema versions are not recorded anywhere committed. Recording them would need a new retrieval that keeps the response envelope, which needs network approval in a later phase.
- The Crossref bounded-corpus fetch has not run, so no observed subset size exists.
- The existing open questions in the manifest (:755-765 before the edit, now :796-806) are unchanged.

## Proposed later (out of scope)

- Mirror projection_schema_version into the manifest's OpenAlex (and DBLP and ORCID) schema blocks, for symmetry.
- Have validate_datasets.py check that size_estimate.observed.sample_bytes and sample_sha256 equal the samples[] entry (a datasets/scripts change, out of scope here).

## INTERFACE HANDOFF

- Files changed: datasets/dataset-manifest.yaml, datasets/schemas/crossref.md, datasets/schemas/openalex.md, datasets/schemas/orcid.md. No Scala, build, script or sample file changed.
- Public interface: new optional manifest keys under datasets[id=crossref]:
  - snapshot.{snapshot_date, snapshot_date_kind, snapshot_date_sources[], snapshot_date_consistency, bounded_corpus_retrieval_date (+_unknown_reason)}
  - size_estimate.observed.{kind, measured_on, sample_file, sample_bytes, sample_records, sample_sha256 (+*_source), sample_note, subset_bytes (+_unknown_reason), public_data_file_bytes (+_unknown_reason)}
  - schema.{projection_schema_version (integer 1), projection_schema_version_definition, source_schema_version (+_unknown_reason)}
  - manifest_version unchanged (1).
- Fixtures: none added. The samples (sha256 and bytes) are unchanged, so the hadoop and contract fixtures that read datasets/samples/** are unaffected.
- Thread-safety: not applicable (data files).
- Limits: the validator was not executed in this run. The ORCID types come from one record.
