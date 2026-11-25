STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: ScholarChain Phase 17, session 3, repair round R3, minor repair pass (code-reviewer Part B minor findings 4 and 5) in datasets/dataset-manifest.yaml only. No sbt, no interpreters, no network, no AWS.
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/datasets/dataset-manifest.yaml (two lines, 558 and 581; replaced in place)
COMMANDS RUN:
- head -n 3 and head -n 38 | tail -n 1 on docs/agent-runs/phase-03/agent-report-dataset.md (observed: :3 says "I checked every source against official pages on 2026-11-12"; :38 says "fetched the samples: 1 Crossref batch request, 2 ORCID public API requests, 2 OpenAlex batch requests", no date)
- grep -rn "9,347|9347" over datasets/ and docs/agent-runs/phase-03/ (observed: only manifest :568 and :581; no record of how 9,347 was measured)
- grep -n on the two edited lines; wc -l (805 lines before and after); git diff --stat -- datasets/ (manifest still +41 lines vs HEAD, i.e. no line count change from this repair); grep counts of bare `unknown` values (11) and `_unknown_reason` keys (14), unchanged by this repair
TEST RESULTS: none run (sbt not permitted this round; no Scala changed)
VALIDATION RESULTS: Static check by reading only (no interpreter allowed; datasets/scripts/** not run). Both edited lines keep their original indentation (8 spaces for the list item at :558 under snapshot_date_sources; 8 spaces for sample_note at :581 under size_estimate.observed), are single double-quoted scalars, and contain no inner double quote or backslash. No key added or removed; no `unknown` value or `*_unknown_reason` touched. `bash datasets/scripts/validate.sh` has NOT been run on the edited manifest.
ASSUMPTIONS: The date 2026-11-12 for the Crossref sample stays supported by datasets/LICENSES.md:22 and samples[].attribution (as the reviewer verified); line :556's own citation ("line 729 at commit cdd36c8") was left unchanged as instructed (nothing else).
RISKS: Manifest validator not yet run after R3 edits (static check only).
MANUAL ACTION REQUIRED: Run `bash datasets/scripts/validate.sh` in an approved step.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused check of manifest :558 and :581; dataset validation run.

## Exact changes

Finding 4, line 558 (before and after: 558)

OLD:
        - "docs/agent-runs/phase-03/agent-report-dataset.md:3 (sources checked and samples fetched on 2026-11-12; 1 Crossref batch request, :38)"
NEW:
        - "docs/agent-runs/phase-03/agent-report-dataset.md:3 (sources checked against official pages on 2026-11-12) and :38 (samples fetched with 1 Crossref batch request; no date on that line)"

Finding 5, line 581 (before and after: 581)

OLD:
        sample_note: "The file is the projected API items re-serialised with sorted keys (make_samples.py jsonl()), so its 9,617 bytes differ from the 9,347 bytes per 4 projected works used as the basis of subset_bytes_estimate."
NEW:
        sample_note: "The file holds 9,617 bytes (sample_bytes_source); subset_bytes_estimate uses 9,347 bytes per 4 projected works (this manifest, size_estimate.subset_bytes_estimate). The cause of the difference is not recorded: no committed record states how the 9,347-byte figure was measured."

Line numbers: no line shifted (805 lines before and after).

## Dataset table (unchanged by this repair)

| Source | License | Snapshot | Checksum | Size | Subset |
|---|---|---|---|---|---|
| Crossref sample datasets/samples/crossref/works.jsonl | metadata free to use (Crossref REST API page, checked 2026-11-12) | API retrieval 2026-11-12; release id unknown | sha256 6f806e58...5a40 (samples[]) | 9,617 bytes, 4 records | dblp-subset-v1 |

## Unresolved dataset questions
- How the 9,347-byte per-4-works basis of subset_bytes_estimate was measured is not recorded anywhere committed.

## INTERFACE HANDOFF
- Files changed: datasets/dataset-manifest.yaml (lines 558, 581; string values only).
- Public signatures: none; no YAML key added, removed or renamed; validator contract unchanged.
- Fixtures: none; samples, checksums and byte counts unchanged.
- Thread-safety: not applicable.
- Limits: static check only; validator not run.
