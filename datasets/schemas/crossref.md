# Crossref: schema notes

Owner: dataset-engineer. Checked 2026-11-12.

| Item | Value |
|---|---|
| API | `https://api.crossref.org` (no sign-up) |
| Pools | public pool: 5 req/s, concurrency 1. Polite pool (`mailto` parameter or agent header): 10 req/s, concurrency 3. Plus (API key): 150 req/s. |
| Bulk | 2026 public data file (208 GB, nearly 180M records, JSONL in tar) via Academic Torrents (DOI 10.13003/nggf-vt1j) or the Requester Pays bucket `api-snapshots-reqpays-crossref`. Not scripted: Requester Pays needs AWS credentials. |
| Metadata reuse | "almost none of the metadata is subject to copyright, and you may use it for any purpose"; abstracts may be copyrighted. No SPDX identifier is published. |

## Schema version

Added in Phase 17 repair round R3 (N4, autograder checks DATA-02 and DATA-05).
Nothing was fetched; each value names its source.

| Item | Value | Source |
|---|---|---|
| ScholarChain projection schema version | `1` | Defined by ScholarChain, not by Crossref. Version 1 is the field list below and in `schema.work_fields_selected` of `datasets/dataset-manifest.yaml`, as committed in 6788952 (Phase 3) and unchanged until this note. A change to the selected fields bumps it. Mirrored in the manifest as `schema.projection_schema_version`. |
| Crossref source schema version | not recorded | The sample keeps only the selected item fields, not the response envelope (`datasets/scripts/make_samples.py:205-207`). No committed record names a Crossref REST API or metadata schema version. Manifest: `schema.source_schema_version: unknown` with its reason. |
| Data date | 2026-11-12 (API retrieval date of the sample) | Manifest `snapshot.snapshot_date` and its sources; not a Crossref release. |

## Access used by ScholarChain

* Batch request: `GET /works?filter=doi:D1,doi:D2,...&select=<fields>&rows=N`.
  Multiple `doi:` filters are OR-ed; 4 of 4 returned on 2026-11-12.
* DOIs containing `,` use the singleton form `GET /works/{doi}`.
* `fetch_enrichment.py` paces requests at 1 per second. It sends contact
  details only if `SCHOLARCHAIN_CONTACT_MAILTO` is set.
* `select=source` is rejected on this route (HTTP 400 observed), so it is not
  selected.

## Fields selected (`schema.work_fields_selected`)

| Field | Shape | Use |
|---|---|---|
| `DOI` | bare DOI, publisher case | `doi` after normalisation |
| `title` | list of strings | display |
| `author[]` | `{given, family, sequence, affiliation[{name}], ORCID?, authenticated-orcid?, role[]}` | name and ORCID evidence |
| `container-title` | list | venue label |
| `event` | `{name, acronym, location, sponsor[]}` for proceedings | venue evidence |
| `published`, `issued` | `{date-parts: [[Y, M, D]]}` | dates |
| `type` | for example `proceedings-article` | |
| `publisher`, `prefix`, `member`, `URL`, `ISBN` | | |
| `indexed`, `deposited`, `created` | timestamps | change detection |
| `license[]` | the publication's content license (for example the ACM copyright policy or CC BY 4.0) | **Not** the metadata license. Do not interpret it as such. |

`abstract` and `reference` are never selected. Abstracts may be under publisher
or author copyright, and references are out of scope.

## Sample file

`datasets/samples/crossref/works.jsonl` holds 4 real works with the same DOIs
as the OpenAlex sample. Values are unmodified and keys are sorted.

Notable real cases:

* `10.1145/3477495.3531753` has no author ORCIDs.
* That same work spells a family name `Lovón Melgarejo`, while dblp has
  `Lovón-Melgarejo`.
* `authenticated-orcid` is `false` on all deposited ORCIDs in the sample.
