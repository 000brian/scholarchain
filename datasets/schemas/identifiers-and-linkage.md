# Identifiers and linkage (input contract for domain-engineer)

Owner: dataset-engineer. Checked 2026-11-12. The reference implementation is
`datasets/scripts/dslib.py`. Tests are in `datasets/scripts/test_datasets.py`.
If this file and `dslib.py` disagree, that is a bug; report it to dataset-engineer.

Field names in the first column are the proposed names for the shared domain
records. Their Scala shape is decided by domain-engineer.

| Field | Canonical form | Regex (after normalisation) | Example | Sources |
|---|---|---|---|---|
| `dblpKey` | the `key` attribute, verbatim and case-sensitive | observed: `^[a-z]+(/[A-Za-z0-9_\-.'+]+)+$` (observed characters only; not a dblp guarantee) | `conf/sigir/Sousa0J23` | DBLP record element |
| `dblpStreamPrefix` | first two key segments plus `/` | n/a | `conf/sigir/` | derived from `dblpKey` |
| `dblpAuthorName` | entity-decoded `<author>` text with internal whitespace collapsed; homonym suffix kept | optional suffix `^(.*\S) ([0-9]{4})$` (ASCII digits only) | `Ricardo Campos 0001` | DBLP `<author>` |
| `doi` | bare DOI; resolver prefix removed; percent-escapes decoded once for URL inputs; ASCII `A-Z` lowercased only | `^10\.[0-9]+(\.[0-9]+)*/\S+$` | `10.1109/dac56929.2023.10247992` | DBLP `<ee>`, OpenAlex `doi`, Crossref `DOI`, ORCID `external-id-value` (type `doi`) |
| `orcid` | bare iD, uppercase `X`, no URI prefix; valid ISO 7064 11,2 check digit | `^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$` (ASCII digits only) | `0000-0003-3226-9189` | DBLP `author/@orcid`, OpenAlex `author.orcid`, Crossref `author[].ORCID`, ORCID `path` |
| `openAlexWorkId` | uppercase key without `https://openalex.org/` | `^W[1-9][0-9]*$` | `W4366851189` | OpenAlex `work.id` |
| `openAlexAuthorId` | same rule, prefix `A` | `^A[1-9][0-9]*$` | `A5089440969` | OpenAlex `author.id`, `authorships[].author.id` |
| `openAlexSourceId` | same rule, prefix `S` | `^S[1-9][0-9]*$` | `S4363608773` | OpenAlex `primary_location.source.id` |
| `openAlexInstitutionId` | same rule, prefix `I` | `^I[1-9][0-9]*$` | `I182534213` | OpenAlex `institutions[].id` |

## Normalisation rules and evidence

### DOI

1. Trim surrounding whitespace.
2. Remove one leading resolver prefix, case-insensitively: `https://doi.org/`,
   `http://doi.org/`, `https://dx.doi.org/`, `http://dx.doi.org/`, or `doi:`.
3. For the four URL forms only, percent-decode once (dblp `<ee>` values are
   URLs; the synthetic sample has `10.5555/scholarchain%2Dsynthetic%2D0005`).
4. Lowercase only ASCII `A`-`Z`. The DOI Handbook (2025 PDF,
   https://www.doi.org/doi-handbook/DOIHandbook_2025.pdf) says DOI names are
   case-insensitive only when testing for equivalence, and only with respect to
   the Basic Latin block (U+0041..U+005A vs U+0061..U+007A). Do not apply full
   Unicode lowercasing.
5. Reject values that do not match the regex. Rejected values are counted and
   never repaired.

From the dblp side, a DOI comes only from `<ee>` values that are doi.org or
dx.doi.org URLs. A record may carry several `<ee>` values (the sample has
Wikidata links), and two spellings can normalise to the same DOI.

Observed cases:

* Uppercase DOI in dblp: `https://doi.org/10.1109/DAC56929.2023.10247992`.
* An ORCID record with an arXiv DOI in mixed case: `10.48550/arXiv.2301.04643`.
  It normalises to `10.48550/arxiv.2301.04643`.
* One work, several DOIs: the version of record is `10.1145/3539618.3591892`
  and the preprint is `10.48550/arXiv.2301.04643`. ORCID groups them
  separately. Linking is by exact canonical DOI; merging preprint and version
  of record is an entity-resolution decision (entity-resolution-engineer).

### ORCID iD

1. Remove an `https://orcid.org/` or `http://orcid.org/` prefix
   (case-insensitive).
2. Uppercase the value.
3. Require `^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$` (ASCII digits only).
4. Require the ISO 7064 11,2 check digit. The algorithm is copied from
   ORCID-Source `OrcidCheckDigitGenerator.generateCheckDigit`: `total =
   (total + digit) * 2` over the first 15 digits, then `result = (12 - total
   % 11) % 11`, and 10 is written as `X`.

Evidence:

* ORCID v3.0 XSD `orcid-path` pattern `(\d{4}-){3,}\d{3}[\dX]`.
* https://raw.githubusercontent.com/ORCID/ORCID-Source/master/orcid-core/src/main/java/org/orcid/core/crypto/OrcidCheckDigitGenerator.java

The XSD pattern allows more than four groups. ScholarChain accepts exactly four
groups, which is every form observed.

The ORCID support article on iD structure sat behind a Cloudflare challenge on
2026-11-12 and was not read.

Invalid iDs, such as `0000-0000-0000-0000` in the synthetic sample, are
counted as `invalid_orcid_attrs` and dropped from linkage.

### OpenAlex IDs

The rule is to remove `https://openalex.org/` and uppercase the result.
OpenAlex documents IDs as case-insensitive and recommends key-only form
(https://help.openalex.org/how-to-use-the-api/get-single-entities).

OpenAlex author IDs are not stable across snapshots. The release notes report
profiles merged and split in 2026-04 and 2026-05. Store the snapshot or
retrieval date with every OpenAlex ID.

`authorships[].author.id` can be `null` while `author.orcid` is present. This
is observed for `W4384652670`.

### dblp keys and names

* A key is opaque. Use it verbatim as the source record ID. Keys are unique
  within a release. Whether a key is stable across releases was not verified.
* The homonym suffix is a space followed by exactly 4 digits
  (`Ricardo Campos 0001`, `Rafael Ferreira 0003`, `Zhe Lin 0007`). The suffix
  is dblp's own disambiguation, not ground truth. Keep it in the name string.
  `dslib.split_dblp_name` exposes base and number.
* Non-ASCII characters arrive as DTD named entities (`&eacute;`) or numeric
  references (`&#26446;`). Decode them with the dblp DTD.

## Cross-dataset linkage keys

| From | Key | To |
|---|---|---|
| dblp work | `doi` (from `<ee>`) | OpenAlex work `doi`, Crossref work `DOI`, ORCID work-group DOI |
| dblp author mention | `orcid` (`author/@orcid`) | OpenAlex `authorships[].author.orcid`, Crossref `author[].ORCID`, ORCID `path` |
| OpenAlex work | `openAlexAuthorId` | OpenAlex author record |
| OpenAlex work | `openAlexSourceId` | venue (used only for the subset size estimate) |

Provenance to carry with every linked fact: dataset id, snapshot id or
retrieval date, source record ID (the dblp key, OpenAlex ID, DOI or ORCID iD),
and the sha256 of the raw response or file.

## Undirected pair ordering used for sizing

The ordering is `dslib.canonical_pair(a, b)`: lexicographic by code point, so
`(min, max)`. It is the sizing convention for `dblp_subset_stats.py`. The
production pair canonicalisation is domain-engineer's decision.
