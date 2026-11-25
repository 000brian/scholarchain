# ORCID: schema notes

Owner: dataset-engineer. Checked 2026-11-12.

## Sources

### Public Data File 2026

* DOI 10.23640/07243.34697766.v1, published 2026-10-22.
* Snapshot of public data as of 2026-10-13.
* License CC0, with community norms requested: attribution, accuracy, and
  respect for privacy.
* XML only. There is one summaries tarball (57.3 GB compressed; 919.19 GB
  uncompressed per the Figshare description, laid out as
  `summaries/<3-digit dir>/<iD>.xml`) and 11 activities tarballs.
* Per-file MD5 values come from the Figshare API.

### Public API v3.0

* `https://pub.orcid.org/v3.0/{iD}/person` and `/works` answered HTTP 200
  without credentials, with `Accept: application/json`, on 2026-11-12.
* Terms: "limited license to use any Registry data marked 'public' subject to
  these Terms of Service". ORCID may rate-limit.
* No numeric anonymous limits were published on the pages checked.

## Fields kept (privacy minimisation)

From `/person`:

* `name.path` (the iD)
* `name.given-names.value`
* `name.family-name.value`
* `name.credit-name.value`
* `name.visibility`

From `/works`: only the groups whose external IDs include a DOI linked to the
subset. For each such group, keep:

* `external-ids.external-id[]` (type, value, url, relationship, normalized)
* per `work-summary`: `put-code`, `type`, `title.title.value`,
  `publication-date`, `external-ids`, and `source.source-name.value`

Dropped, never stored:

* `biography`, `emails`, `addresses`, `keywords`, `researcher-urls`
* `external-identifiers` (other person IDs) and `other-names`
* employment, education, funding, and peer review
* every work group not linked to the subset

## Field types (committed sample projection)

Added in Phase 17 repair round R3 (N4, autograder checks DATA-02 and DATA-05).
Nothing was fetched. Types are JSON types read from
`datasets/samples/orcid/records.jsonl` (1 record, 2 work groups, 5
work summaries, 15 external-id entries: 5 on groups, 10 on summaries) on 2026-11-24 by walking every value.
"Observed" lists every type seen; a type not seen is not claimed. Schema
version: ORCID message schema v3.0 (manifest `format.api`; sample
`_projection` text).

The projection unwraps some API `{ "value": ... }` wrappers to plain values
(`val()` in `datasets/scripts/make_samples.py:150-151`): `name.given-names`,
`name.family-name`, `name.credit-name`, work-summary `title` (API
`title.title.value`) and `source-name` (API `source.source-name.value`). Other
wrappers are kept as the API returned them.

| Projected path | Observed JSON type | Note |
|---|---|---|
| `orcid-path` | string | bare iD, `0000-0003-3226-9189` (API `name.path`, make_samples.py:172) |
| `name` | object | |
| `name.given-names` | string | unwrapped |
| `name.family-name` | string | unwrapped |
| `name.credit-name` | null | unwrapped; only null observed (no public credit name); the non-null type is not observed |
| `name.visibility` | string | `public` |
| `works-group-projection` | array of object | DOI-linked groups only |
| `works-group-projection[].external-ids` | object | |
| `….external-ids.external-id` | array of object | |
| `….external-id[].external-id-type` | string | observed values `doi`, `eid`, `source-work-id`, `isbn`, `issn` |
| `….external-id[].external-id-value` | string | as asserted by the source (case varies) |
| `….external-id[].external-id-relationship` | string | observed values `self`, `part-of` |
| `….external-id[].external-id-url` | object or null | object is `{value: string}` |
| `….external-id[].external-id-normalized` | object | `{transient: boolean, value: string}` |
| `….external-id[].external-id-normalized-error` | null | only null observed |
| `works-group-projection[].work-summary` | array of object | |
| `….work-summary[].put-code` | integer | |
| `….work-summary[].type` | string | observed values `conference-paper`, `other` |
| `….work-summary[].title` | string | unwrapped |
| `….work-summary[].source-name` | string | unwrapped |
| `….work-summary[].publication-date` | object | |
| `….publication-date.year` | object | `{value: string}`, for example `"2023"` |
| `….publication-date.month` | object or null | `{value: string}`, zero-padded, for example `"07"` |
| `….publication-date.day` | object or null | `{value: string}`, for example `"19"` |
| `….work-summary[].external-ids` | object | same shape as the group `external-ids` |
| `_projection` | string | ScholarChain note naming the dropped sections |

## Shape notes

* The API JSON uses `{ "value": ... }` wrappers for most scalars.
  `publication-date` is `{year:{value}, month:{value}|null, day:{value}|null}`.
* `external-id-value` is the DOI as asserted by the source, so its case varies,
  for example `10.48550/arXiv.2301.04643`. Normalise it (see
  identifiers-and-linkage.md).
* A work can appear in several groups when sources disagree. In the sample, the
  VoR DOI group and the arXiv DOI group are separate, and each also carries
  `eid` or `source-work-id` IDs.
* The bulk file is XML in the same v3.0 message schema. Element names match the
  JSON keys, but the nesting of value wrappers differs. Bulk parsing, if ever
  approved, belongs to hadoop-engineer.

## Sample file

`datasets/samples/orcid/records.jsonl` holds one real record projection for
iD `0000-0003-3226-9189`. That iD appears on dblp `conf/sigir/Sousa0J23` and
in OpenAlex and Crossref. The projection contains the name and 2 DOI-linked
work groups.
