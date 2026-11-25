# OpenAlex: schema notes

Owner: dataset-engineer. Checked 2026-11-12.

| Item | Value |
|---|---|
| API | `https://api.openalex.org` |
| Snapshot | `s3://openalex/data/` (anonymous; HTTPS `https://openalex.s3.amazonaws.com/`); manifest date 2026-10-14 |
| License | CC0 1.0 (bucket `LICENSE.txt`) |

## Schema version

Added in Phase 17 repair round R3 (N4, autograder checks DATA-02 and DATA-05).
Nothing was fetched; each value names its source.

| Item | Value | Source |
|---|---|---|
| ScholarChain projection schema version | `1` | Defined by ScholarChain, not by OpenAlex. Version 1 is the work and author field lists below and in `schema.work_fields_selected` and `schema.author_fields_selected` of `datasets/dataset-manifest.yaml`, as committed in 6788952 (Phase 3) and unchanged until this note. A change to the selected fields bumps it. |
| OpenAlex source schema version | not recorded | No committed record names an OpenAlex schema version number. The committed records identify OpenAlex data by date only: the snapshot manifest date 2026-10-14 (manifest `snapshot.id`) and dated release-note entries (manifest `source.evidence`, `limitations`). The sample keeps only the selected fields, not the response `meta` envelope. |
| Data state of the sample | API retrieval 2026-11-12; latest `updated_date` in `datasets/samples/openalex/works.jsonl` is 2026-11-10T11:39:43.021295 | Manifest `samples[].attribution`; `updated_date` read from the sample file on 2026-11-24. All 4 sample works carry `authorships[].raw_orcid`, the field the release notes date to 2026-04-29 (table below). |

## Access used by ScholarChain

| Purpose | Request |
|---|---|
| Works for subset DOIs | `GET /works?filter=doi:D1\|D2\|...&select=<work fields>&per_page=N` |
| Authors on linked works | `GET /authors?filter=openalex:A1\|A2\|...&select=<author fields>&per_page=N` |
| Size estimate only | `GET /works?filter=primary_location.source.id:S...,publication_year:2015-2025&per_page=1` (`meta.count`) and `sample=100&seed=...` |

Request notes:

* Batches of 4 DOIs and 3 author IDs were verified on 2026-11-12. The batch
  size of up to 50 used by `fetch_enrichment.py` was not tested; the OpenAlex
  limit on OR values was not found on the pages checked.
* `per_page` has a maximum of 100 (API overview page).
* Observed `meta.cost_usd` was 0.0001 for a filtered list and 0.001 for a
  `search` request.
* DOIs containing `,` or `|` cannot go in a filter list. The script falls back
  to `GET /works/doi:<doi>` for them.

## Work fields selected (`schema.work_fields_selected`)

| Field | Shape | Use |
|---|---|---|
| `id` | `https://openalex.org/W...` | `openAlexWorkId` after normalisation |
| `doi` | `https://doi.org/...` or null | linkage to dblp, after normalisation |
| `ids` | `{doi, openalex, ...}` | alternative IDs |
| `title`, `display_name` | string | display |
| `publication_year`, `publication_date` | int, `YYYY-MM-DD` | time window |
| `type` | for example `article` | |
| `primary_location.source.{id, display_name, type}` | source (venue) | `openAlexSourceId` |
| `primary_location.raw_source_name`, `raw_type` | strings from the publisher | |
| `authorships[]` | ordered list | author mentions |
| `authorships[].author.{id, display_name, orcid}` | `id` may be **null** | `openAlexAuthorId`, `orcid` |
| `authorships[].author.observed_orcids` | list of ORCID URLs | additional ORCID evidence |
| `authorships[].raw_author_name` | string as printed | name evidence |
| `authorships[].raw_orcid` | field added 2026-04-29 per release notes | |
| `authorships[].author_position` | `first` / `middle` / `last` | |
| `authorships[].institutions[]`, `affiliations[]`, `raw_affiliation_strings`, `countries` | affiliation data | personal data; see the privacy section |
| `authorships[].is_corresponding` | bool | |
| `updated_date`, `created_date` | timestamps | provenance and change detection |

## Author fields selected

The selected fields are `id`, `orcid`, `display_name`,
`display_name_alternatives` (name variants, including mis-encoded ones such as
`Alipio M´rio Jorge`), `ids`, `works_count`, `cited_by_count`,
`last_known_institutions[]` (`id`, `display_name`, `ror`, `country_code`,
`type`, `lineage`), `updated_date`, and `created_date`.

## Snapshot layout (fallback path)

`data/jsonl/<entity>/updated_date=YYYY-MM-DD/part_NNNN.gz` holds gzip JSON
Lines, up to 400,000 records per part. `data/jsonl/manifest.json` lists for
each entity the `record_count`, `content_length`, and `files[] {url,
meta{content_length, record_count}}`. No checksums are published.

`download_openalex_snapshot.sh` verifies each part by its length and its line
count.

## Sample files

| File | Content |
|---|---|
| `datasets/samples/openalex/works.jsonl` | 4 real works linked to the dblp sample DOIs. Values are unmodified, keys sorted, and only the selected fields are present. |
| `datasets/samples/openalex/authors.jsonl` | 3 real authors on those works: Sousa, Campos, Jorge. |

Notable real cases in the sample:

* `W4384652670`: one authorship has a null author ID but an ORCID.
* `W4224919571`: OpenAlex has ORCIDs for 7 authors, while dblp has 2.
