# dblp XML: schema notes

Owner: dataset-engineer. Checked 2026-11-12.

| Item | Value |
|---|---|
| Release | `dblp-2026-11-01.xml.gz` (DOI 10.4230/dblp.xml.2026-11-01) |
| DTD | `https://dblp.org/xml/dblp.dtd`, sha256 `2d864498...1ef6`, 13,134 bytes |
| Change log | https://dblp.org/xml/CHANGES.txt |
| Field documentation | https://dblp.org/faq/16154937 (linked from the DTD header; not fetched) |
| License | CC0 1.0 (secondary ODC-BY 1.0); see `datasets/LICENSES.md` |

## Physical format

* The file is a single-member gzip, so random access is impossible. A bounded
  prefix fetched with an HTTP Range request decompresses only up to the cut
  point.
* The XML declaration says `encoding="ISO-8859-1"`. The first 16 MiB contained
  no raw bytes of `0x80` or higher. Non-ASCII text is carried by DTD named
  entities (65 general entities in the DTD, for example `&eacute;` and
  `&uuml;`) and by numeric character references (for example `&#1057;`).
* The DOCTYPE is `<!DOCTYPE dblp SYSTEM "dblp-2023-06-28.dtd">`. That file name
  is not published (HTTP 404 on 2026-11-12). Resolve the entity locally to
  `dblp.dtd`. Without the DTD, named entities are undefined and parsing fails.
* Records are written back to back, often several on one line, like
  `</incollection><incollection ...>`. Line-based tools miscount records.
* Records with the same key prefix were contiguous in the observed prefix.
  Each of these blocks appeared once: `conf/dac/`, `conf/igarss/`,
  `conf/wcnc/`, `conf/sigir/`. The order of the blocks is not alphabetical.
* Decompressed size is an estimate of about 5.49 GB, from a 4.92:1 ratio on
  the first 16 MiB.

## Logical structure (from dblp.dtd)

The root is `<dblp>`. Record elements are `article`, `inproceedings`,
`proceedings`, `book`, `incollection`, `phdthesis`, `mastersthesis`, `www`,
and `data`. Each record has the field set `author|editor|title|booktitle|pages|year|address|journal|volume|number|month|url|ee|cdrom|cite|publisher|note|crossref|isbn|series|school|chapter|publnr|stream|rel`.

### Fields ScholarChain uses

| XML | Meaning | Notes |
|---|---|---|
| `@key` | dblp record key | Source record ID; see identifiers-and-linkage.md |
| `@mdate` | last modification date (YYYY-MM-DD) | Use for change detection between releases |
| `@publtype` | NMTOKENS, for example `informal` or `encyclopedia` | The subset excludes the `informal` token |
| `author` (repeated, ordered) | author name label | Optional `@orcid`; the order is the author order |
| `author/@orcid` | ORCID iD (bare) | Partial coverage; validate the check digit |
| `editor` | proceedings editors | Not an author mention; not used for edges |
| `title` | title | May contain `sub`, `sup`, `i`, `tt` or `ref` markup (DTD `titlecontents`) |
| `year` | 4-digit year | The subset window filter |
| `booktitle` / `journal` | venue label | The key prefix is the subset criterion, not this label |
| `ee` (repeated) | electronic edition URLs | The DOI comes from doi.org or dx.doi.org URLs; other `ee` values (such as Wikidata) are ignored |
| `crossref` | key of the parent `proceedings` record | Venue and edition linkage |
| `url` | dblp table-of-contents path | Not an identifier |

## Records in `datasets/samples/dblp/dblp-sample.xml` (real, verbatim)

| Key | Why it is included | In subset-v1? |
|---|---|---|
| `conf/sigir/Sousa0J23` | homonym `Ricardo Campos 0001`, entities (`Al&iacute;pio`), 3 ORCIDs, DOI | yes |
| `conf/sigir/HofstatterC0Z23` | `&auml;`, homonym, 4 ORCIDs, Wikidata `ee` | yes |
| `conf/sigir/0003SM23` | homonym `Rafael Ferreira 0003`, `&atilde;` | yes |
| `conf/sigir/LernerFGBB0L22` | 7 authors, only 2 ORCIDs, `Jos&eacute; G. Moreno 0001` | yes |
| `conf/sigir/KemmererGMAE14` | 2014, workshop (`ERD@SIGIR`) under `conf/sigir/` | no (year) |
| `conf/sigir/2023` | `proceedings` with editors, ISBN, volume DOI | no (element) |
| `conf/dac/ShenZWLDWCG23` | 8 authors, 3 homonym numbers, uppercase DOI | no (venue) |
| `reference/vision/X14bd` | `incollection` with zero authors | no (element) |
| `dblpnote/neverpublished` | `article` with `publtype="informal"` and no year | no (key prefix) |

The sample file declares only the entities it uses in an internal DTD subset,
so it parses standalone. The real dump uses the external DTD instead.

## Synthetic edge cases (`dblp-synthetic-edge-cases.xml`)

These records are hand-written and labelled SYNTHETIC. They cover:

* a duplicate author on one record
* a non-Latin-1 numeric character reference in a name
* an invalid ORCID check digit
* two spellings of one DOI
* the year boundaries 2015 and 2025, and a 2026 record outside the window
* an `informal` record inside the window
* a percent-encoded DOI URL

Expected results are recorded in the manifest and asserted by the tests.
