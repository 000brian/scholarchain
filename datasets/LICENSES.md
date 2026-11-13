# Dataset license records

Owner: dataset-engineer. Checked 2026-11-12. The machine-readable source is
`datasets/dataset-manifest.yaml` (`datasets[].license`, `samples[]`).

| Dataset | License | Evidence (checked 2026-11-12) | Attribution |
|---|---|---|---|
| dblp | CC0 1.0 (secondary ODC-BY 1.0) | https://dblp.org/xml/README.txt ; https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-11-01 | Requested: "dblp computer science bibliography, https://dblp.org" |
| OpenAlex | CC0 1.0 | https://openalex.s3.amazonaws.com/LICENSE.txt | Not required; ScholarChain cites OpenAlex |
| Crossref | No license published for the metadata. Crossref states that "almost none of the metadata is subject to copyright, and you may use it for any purpose"; abstracts may be copyrighted. | https://www.crossref.org/documentation/retrieve-metadata/rest-api/ | Not required; ScholarChain cites Crossref |
| ORCID Public Data File 2026 | CC0 1.0 | https://api.figshare.com/v2/articles/34697766 ; https://info.orcid.org/public-data-file-use-policy/ | Requested: credit ORCID and link to the use policy |
| ORCID Public API | ORCID Public APIs Terms of Service (limited license for public data) | https://info.orcid.org/public-client-terms-of-service/ | As above |

## Repository samples

| File | Kind | License basis | Attribution |
|---|---|---|---|
| `samples/dblp/dblp-sample.xml` | real, verbatim | CC0 1.0 | dblp, release 2026-11-01 |
| `samples/dblp/dblp-synthetic-edge-cases.xml` | synthetic | CC0 1.0, ScholarChain contribution | ScholarChain; not real people or works |
| `samples/openalex/works.jsonl` | real, field projection | CC0 1.0 | OpenAlex, API 2026-11-12 |
| `samples/openalex/authors.jsonl` | real, field projection | CC0 1.0 | OpenAlex, API 2026-11-12 |
| `samples/crossref/works.jsonl` | real, field projection, no abstracts | Crossref statement (no copyright asserted on metadata) | Crossref REST API, 2026-11-12 |
| `samples/orcid/records.jsonl` | real, privacy-minimised projection | ORCID Public API ToS. The same public data is CC0 in the 2026 Public Data File. **Open question for human review.** | ORCID, Public API v3.0, 2026-11-12 |
| `samples/selection.json` | identifier list | CC0 1.0, ScholarChain contribution | ScholarChain |

The real samples contain personal data: author names, ORCID iDs, and
affiliation strings. This data is published publicly by its sources. Do not
enrich the samples with any further personal fields.
