STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: ScholarChain Phase 3, dataset-engineer part (datasets/** only). Scope: the dataset manifest, bounded idempotent download and sample scripts, legal samples, schema notes and a validation command for DBLP, OpenAlex, ORCID and Crossref. I checked every source against official pages on 2026-11-12.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/datasets/dataset-manifest.yaml
- /Users/brian/Developer/repos/scholarchain/datasets/LICENSES.md
- /Users/brian/Developer/repos/scholarchain/datasets/schemas/identifiers-and-linkage.md
- /Users/brian/Developer/repos/scholarchain/datasets/schemas/dblp-xml.md
- /Users/brian/Developer/repos/scholarchain/datasets/schemas/openalex.md
- /Users/brian/Developer/repos/scholarchain/datasets/schemas/crossref.md
- /Users/brian/Developer/repos/scholarchain/datasets/schemas/orcid.md
- /Users/brian/Developer/repos/scholarchain/datasets/samples/selection.json
- /Users/brian/Developer/repos/scholarchain/datasets/samples/dblp/dblp-sample.xml (real, verbatim)
- /Users/brian/Developer/repos/scholarchain/datasets/samples/dblp/dblp-synthetic-edge-cases.xml (SYNTHETIC, labelled in the file and the manifest)
- /Users/brian/Developer/repos/scholarchain/datasets/samples/openalex/works.jsonl (real, field projection)
- /Users/brian/Developer/repos/scholarchain/datasets/samples/openalex/authors.jsonl (real, field projection)
- /Users/brian/Developer/repos/scholarchain/datasets/samples/crossref/works.jsonl (real, field projection, no abstracts)
- /Users/brian/Developer/repos/scholarchain/datasets/samples/orcid/records.jsonl (real, privacy-minimised projection)
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/lib.sh
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/download_dblp.sh
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/download_openalex_snapshot.sh
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/download_orcid_public_data_file.sh
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/fetch_enrichment.py
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/make_samples.py
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/dblp_subset_stats.py
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/estimate_subset_openalex.py
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/dslib.py
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/validate_datasets.py
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/test_datasets.py
- /Users/brian/Developer/repos/scholarchain/datasets/scripts/validate.sh

FILES MODIFIED: none outside datasets/**. `git status --porcelain` shows only `?? datasets/` plus the human's existing `?? docs/agent-runs/phase-03/`.

COMMANDS RUN:
- Read the official pages with WebFetch, curl HEAD/GET on official endpoints, and the Figshare API. Evidence URLs are recorded per dataset in the manifest.
- Fetched one bounded official sample: an HTTP Range of bytes 0-16777215 of dblp-2026-11-01.xml.gz (16 MiB of 1.1 GB). It went to the scratchpad; no full corpus was downloaded.
- Made about 80 OpenAlex API requests (counts and 100-work samples for the estimate), and fetched the samples: 1 Crossref batch request, 2 ORCID public API requests, 2 OpenAlex batch requests.
- `python3 datasets/scripts/make_samples.py dblp ...` and `make_samples.py enrichment ...`; on re-run every output was "unchanged".
- `python3 datasets/scripts/fetch_enrichment.py sample-set ...`; on re-run every request was "skip (cached, checksum ok)".
- `python3 datasets/scripts/dblp_subset_stats.py` on the 16 MiB prefix (calibration) and on the default prefix.
- `python3 datasets/scripts/estimate_subset_openalex.py` (live).
- `bash datasets/scripts/download_dblp.sh` twice: the second run skipped everything (idempotent). `download_openalex_snapshot.sh` and `download_orcid_public_data_file.sh` ran in their bounded defaults (metadata only). All output went to a scratchpad destination outside the repository.
- `bash datasets/scripts/validate.sh` (runs bash -n on every script, the validator, and the unit tests).

TEST RESULTS:
- `python3 datasets/scripts/test_datasets.py -v` (run via validate.sh): "Ran 30 tests ... OK". The tests cover:
  - DOI, ORCID, OpenAlex ID and dblp-key normalisation, including negative cases.
  - Subset expectations on the real and synthetic samples.
  - Refusal of external entities, and gzip detection by magic bytes.
  - Validator negatives: missing field, "unknown" without a reason, a literal bucket name, an estimate claiming to be verified, a checksum mismatch.
  - Script bounds: dblp prefix cap, refusal of a repository destination, ORCID data requires --full, OpenAlex parts cap, enrichment cap without --full, invalid IDs rejected.
- `sbt check` was not run. No Scala, sbt or test-tree files changed. The verify-tests-ran hook watches only build.sbt, project/, modules/, tests/ and prompts/.

VALIDATION RESULTS:
- `bash datasets/scripts/validate.sh` exit code 0.
- bash -n reported ok for download_dblp.sh, download_openalex_snapshot.sh, download_orcid_public_data_file.sh, lib.sh and validate.sh.
- validate_datasets.py: "RESULT: PASS (0 error(s))". Samples total 54,041 bytes in 7 files, under a 262,144-byte budget. Datasets: dblp, openalex, crossref, orcid.
- Pinned constants in the scripts match the manifest (this is checked by the validator).
- The DBLP sample regenerates byte-identically from the downloader's default 16 MiB prefix. That prefix's sha256 is 62d59ff6...c07c2f, equal to the recorded calibration input.

ASSUMPTIONS:
- The current https://dblp.org/xml/dblp.dtd (sha256 2d864498...1ef6) is equivalent to "dblp-2023-06-28.dtd", which the release DOCTYPE names. That dated file returns 404. The current DTD contains the 2023-06-28 `rel@sort` change, but equivalence is unverified.
- OpenAlex API content is under the same CC0 terms as the snapshot's LICENSE.txt; the API pages checked do not restate the license.
- `fetch_enrichment.py` batches up to 50 values per OpenAlex/Crossref filter. Batches of 4 (works) and 3 (authors) were tested; the documented OR limit was not found.
- Proposed field names (dblpKey, doi, orcid, openAlexWorkId and others) are input to domain-engineer, who decides the Scala shapes. The `{raw_bucket}` placeholder is resolved by the configuration contract (domain-engineer). Transfers to S3 are for infra-engineer scripts; datasets/** never touches AWS.

RISKS:
- The subset size and edge count are estimates, not measurements. 15 of the 16 dblp key prefixes are unverified until the full release is scanned.
- The key-prefix rule includes co-located workshops; this was observed for conf/sigir/. That inflates the counts relative to OpenAlex venue sources.
- OpenAlex author IDs are not stable across snapshots (merges and splits in 2026 release notes). Store the retrieval date with every ID.
- Limits are uncertain for full enrichment runs: the OpenAlex keyless daily USD budget and the anonymous ORCID rate limits are not published. A free OpenAlex key (env SCHOLARCHAIN_OPENALEX_API_KEY) may be needed.
- The ORCID sample was retrieved through the Public API, which is under the ToS limited license, not CC0. Crossref metadata has no SPDX license. Both are flagged for human review.
- A full dblp_subset_stats.py run is single-machine Python on about 5.5 GB of XML (estimated at about 10-20 minutes, not measured). It is a sizing tool only; the production parser belongs to hadoop-engineer.

MANUAL ACTION REQUIRED:
1. Ratify or revise subset rule dblp-subset-v1 (venues, 2015-2025 window, workshops included under key prefixes).
2. Approve the full DBLP download (`download_dblp.sh --full`, 1,116,142,729 bytes, MD5-verified). Then run `dblp_subset_stats.py --input dblp-2026-11-01.xml.gz --dtd dblp.dtd --per-prefix` and record the measured works and distinct_pairs in the manifest as kind: measurement.
3. Decide whether the ORCID API-projection sample and the Crossref projected sample are acceptable for redistribution in git (see unresolved questions).
4. Optionally provide SCHOLARCHAIN_OPENALEX_API_KEY and SCHOLARCHAIN_CONTACT_MAILTO in the shell for full enrichment runs. Neither is required, and neither is stored in the repository.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Manifest review by code-reviewer, focused on invented licenses, identifiers and sources; every fact has an evidence URL and checked_on date in the manifest. Then the domain-engineer work order, using datasets/schemas/identifiers-and-linkage.md and the manifest `identifiers` block as input. Human ratification of subset_rule dblp-subset-v1.

## Per-dataset table

| Dataset | Source URL verified (2026-11-12) | License | Snapshot | Size | Checksum | Status |
|---|---|---|---|---|---|---|
| DBLP (primary) | https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-11-01 ; https://dblp.org/xml/ ; https://dblp.org/xml/README.txt | CC0 1.0 (secondary ODC-BY 1.0). Verified on README.txt, the DTD header and the DROPS page. | Monthly release 2026-11-01, DOI 10.4230/dblp.xml.2026-11-01, URL https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-11-01.xml.gz | 1,116,142,729 bytes compressed (Content-Length, measured). Decompressed about 5.49 GB (estimate from the 4.92:1 ratio on the first 16 MiB). Total record count: unknown (needs the full download). | MD5 18ad5db59592406ec74289975c4b44cb (published, .md5 file matches). DTD sha256 2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6 (computed). | Verified, usable |
| OpenAlex | https://help.openalex.org/download-all-data/openalex-snapshot ; https://openalex.s3.amazonaws.com/LICENSE.txt ; https://openalex.s3.amazonaws.com/data/jsonl/manifest.json ; RELEASE_NOTES.txt ; the rate-limits page | CC0 1.0 (bucket LICENSE.txt) | Snapshot 2026-10-14 (manifest date; latest release note) | Works: 464,765,485 records, 675,298,563,638 bytes, 1,898 files. Authors: 101,066,311 records, 74,121,890,690 bytes. Values are from the published manifest. | None published; the manifest gives content_length and record_count per part, which the script verifies. API responses get a sha256 in the fetch index. | Verified, usable. The bounded path is API lookup by DOI; the snapshot is the reproducible fallback. |
| Crossref | https://www.crossref.org/documentation/retrieve-metadata/rest-api/ ; .../access-and-authentication/ ; .../bulk-downloads/ ; https://www.crossref.org/blog/2026-public-data-file-now-available/ | No SPDX license. Crossref states "almost none of the metadata is subject to copyright, and you may use it for any purpose"; abstracts may be copyrighted, so they are excluded. Recorded as spdx: unknown with a reason. | REST API, time-stamped per retrieval. Snapshot id: unknown, because the 2026 public data file's object key, release date and checksums are not published on the pages checked. | 2026 public data file: 208 GB compressed, nearly 180M records (blog 2026-03-17). | None published; a sha256 per API response is recorded. | Verified, usable through the API. The bulk file (torrent or Requester Pays, which needs AWS credentials) is not scripted. |
| ORCID | https://api.figshare.com/v2/articles/34697766 ; https://info.orcid.org/public-data-file-use-policy/ ; https://info.orcid.org/public-client-terms-of-service/ ; https://info.orcid.org/documentation/integration-guide/working-with-bulk-data/ ; pub.orcid.org returned HTTP 200 anonymously | Public Data File: CC0 1.0. Public API: ToS limited license for public data. | Public Data File 2026, DOI 10.23640/07243.34697766.v1, published 2026-10-22, data as of 2026-10-13 | 12 files, 290,958,772,512 bytes in total. The summaries file is 57,290,954,546 bytes (919.19 GB uncompressed per Figshare). | MD5 per file, supplied by Figshare (12 values pinned in the manifest and the script) | Verified, usable. The bounded path is the Public API for subset-linked iDs. |

Not used or not bypassed: the dblp search API and record pages (Anubis bot challenge) and the ORCID support article (Cloudflare challenge).

## Subset rule and estimate basis

The rule is dblp-subset-v1, status proposed:
- dblp `article` and `inproceedings` records
- 2015 <= year <= 2025
- excluding records whose publtype contains "informal"
- key prefixes: conf/sigir/, conf/kdd/, conf/www/, conf/cikm/, conf/wsdm/, conf/icde/, conf/sigmod/, journals/pvldb/, conf/mm/, conf/cvpr/, conf/iccv/, conf/aaai/, conf/ijcai/, conf/emnlp/, conf/acl/, conf/nips/

Enrichment is restricted to:
- OpenAlex and Crossref works whose normalised DOI is in the subset DOI set (from dblp `<ee>` doi.org links)
- OpenAlex authors on linked works
- ORCID iDs that are valid on subset dblp mentions or on linked OpenAlex/Crossref authorships

Estimate. Labelled `kind: estimate, verified: false` in the manifest; NOT a measurement.
1. **Measured on DBLP.** dblp_subset_stats.py on a 16 MiB Range prefix of the pinned release. The prefix holds complete, contiguous blocks for conf/sigir/, conf/dac/, conf/wcnc/ and conf/igarss/. For 2015-2024 it measured 3,521 / 2,422 / 5,468 / 19,688 works. Distinct pairs per pair instance were 0.683-0.849.
2. **Measured on the OpenAlex API (2026-11-12).** OpenAlex counts for the same 4 venues give DBLP/OpenAlex ratios of 0.966-1.098. For the 16 subset venues, 2015-2025, OpenAlex counts 127,818 works.
3. **Derived works.** About 123,534-140,371 DBLP works.
4. **Derived edges.** From 100-work OpenAlex samples per venue (seed 20261112): mean 4.74 authors and 11.74 pair instances per work, giving about 1.50M pair instances. Applying the distinct/instance ratio gives about 1.02M-1.27M distinct coauthor pairs, before cross-venue de-duplication, which lowers the true union by an unmeasured amount.

Reproduce with `estimate_subset_openalex.py`. Verify after the approved full download with `dblp_subset_stats.py`; that run must fail if any prefix has zero works.

The sizing edge definition is a pre-ER unordered pair of distinct entity-decoded, whitespace-collapsed dblp author names on one work, ordered lexicographically.

## Linkage keys and identifier formats

Full rules are in datasets/schemas/identifiers-and-linkage.md; the reference implementation is dslib.py.

- **dblpKey**: the verbatim `key` attribute, case-sensitive and opaque, e.g. `conf/sigir/Sousa0J23`. The stream prefix is the first two segments.
- **dblpAuthorName**: entity-decoded and whitespace-collapsed. It keeps the homonym suffix " NNNN" (exactly 4 digits), e.g. `Ricardo Campos 0001`.
- **doi**: a bare DOI with the resolver prefix stripped (https/http doi.org, dx.doi.org, `doi:`). Percent-decode once for URL inputs. Lowercase ASCII A-Z only; the DOI Handbook 2025 says DOIs are case-insensitive only for Basic Latin letters. Regex `^10\.[0-9]+(\.[0-9]+)*/\S+$`. Example `10.1109/dac56929.2023.10247992`.
- **orcid**: bare uppercase `0000-0003-3226-9189`. Regex `^\d{4}-\d{4}-\d{4}-\d{3}[\dX]$` plus the ISO 7064 11,2 check digit. The pattern is from the ORCID v3.0 XSD and the algorithm from ORCID-Source OrcidCheckDigitGenerator. Invalid iDs are rejected, never repaired.
- **OpenAlex IDs**: uppercase key without the `https://openalex.org/` prefix; W works, A authors, S sources, I institutions. Regex `^[WASI][1-9][0-9]*$`; OpenAlex documents them as case-insensitive.
- **Links**: dblp DOI connects to OpenAlex `doi`, Crossref `DOI`, and ORCID work external-id (type doi). dblp `author@orcid` connects to OpenAlex `authorships[].author.orcid`, Crossref `author[].ORCID`, and the ORCID `path`.
- **Real edge cases captured in the samples**:
  - an OpenAlex authorship with a null author id but an ORCID (W4384652670)
  - dblp has ORCIDs for 2 of 7 authors where OpenAlex has 7
  - an uppercase dblp DOI
  - an arXiv DOI in mixed case, and a version-of-record DOI and preprint DOI for one work in separate ORCID groups
  - a Crossref spelling "Lovón Melgarejo" versus dblp "Lovón-Melgarejo"

Privacy:
- **Kept**: names, ORCID iDs and affiliations on linked works only.
- **ORCID sample**: name and two DOI-linked work groups only. Biography, emails, addresses, keywords, researcher URLs, other person IDs, employment and education are dropped.
- **Crossref**: no abstracts or references.
- **OpenAlex**: no abstracts.

## Known unresolved dataset questions

1. Human ratification of dblp-subset-v1, including whether to include workshops under key prefixes.
2. 15 of 16 dblp venue prefixes are unverified, and the subset size and edge count are unmeasured. Both need the approved 1.1 GB download.
3. The ORCID repository sample comes from the Public API (ToS limited license). Accept it, or replace it with the identical record from the CC0 2026 Public Data File (needs the 57 GB summaries download)?
4. Crossref metadata has no SPDX license. Is Crossref's "almost none ... subject to copyright" statement acceptable for redistributing the projected sample?
5. The dblp release DOCTYPE names dblp-2023-06-28.dtd, which is not published; equivalence with dblp.dtd is assumed.
6. The OpenAlex keyless daily budget is unstated, and anonymous ORCID API rate limits are unpublished. The ORCID fetch volume for the subset is unmeasured.
7. Should DOI-less ML venues (for example ICML or ICLR, which have poor OpenAlex/DOI coverage and were therefore not selected) be added via arXiv links in a later rule version?
8. Is a dblp key stable across releases? This was not verified; use the release id together with the key as provenance.
