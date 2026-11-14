## Summary

`sbt check` **fails**. Four of my 63 adversarial tests fail, and each one exposes a production defect in `modules/hadoop`. All 779 module tests pass.

There is also a blocker: the `contractTests` project cannot load Hadoop classes. That stops any job-level black-box test, so these requirements were **not tested** here:
- the counter identity (INPUT = ACCEPTED + EXCLUDED + REJECTED);
- reject files actually landing on disk;
- determinism across output roots;
- run ID and snapshot in output paths;
- config overrides through a Hadoop `Configuration`;
- J05 `maxBlockSize` and J06 `maxPairsPerBlock` enforcement in the reducer and mapper.

The tests drive the parts of the pipeline that need no Hadoop classes: the scanner, entity expansion, the StAX parser, the record builder, `TeamAssembler`, `PairIndex`, `PairExpansion`, `PairConsolidateJob.consolidate`, `BlockingKeys`, `CandidateFeatures`, `ExactLinker` and `Disposition`. For J01, a helper (`MapperModel`) chains these in the order `DblpExtractMapper.map` documents.

## Failures by owning agent (all `hadoop-engineer`, `modules/hadoop`)

| # | Exact test name | Expected | Actual | Code |
|---|---|---|---|---|
| F1 | `XmlBoundaryAdversarialSuite.truncated-xml: cut inside the final record's start tag name is still reported` | A file that ends inside the last record's start tag (`<a` … `<article`) yields a non-Ok (Truncated) unit, so the loss is counted and rejected | No unit at all. The record disappears without a counter or reject at 7 cut offsets: `List(1095, 1096, 1097, 1098, 1099, 1100, 1101)` | `io/DblpRecordScanner.scala`, `scan()`: when `c < 0` after a partial name, it returns `None` with no Truncated unit |
| F2 | `XmlBoundaryAdversarialSuite.malformed-xml: end tag with whitespace before '>' is a well-formed record boundary` | `</article >` is valid XML (XML 1.0 [42] `ETag ::= '</' Name S? '>'`); expected two Ok units, W1 and W2 | One unit, `(0,"journals/pvldb/W1")`. It runs on to W2's `</article>`, then fails parsing, so both records are lost | `DblpRecordScanner.capture()` matches only the exact bytes `</name>` |
| F3 | `XmlBoundaryAdversarialSuite.malformed-xml: a record-like tag inside an XML comment does not swallow the next record` | `<!-- <article key=".../Gone"> -->` followed by a real record: the real record `journals/pvldb/Real1` is accepted | Accepted list is `Nil`. The tag inside the comment starts a "record" that swallows the real one, and parsing fails | The scanner does not skip `<!-- … -->` (or CDATA) |
| F4 | `UnicodeAdversarialSuite.unicode-nfc-nfd: canonically equivalent names share a dblp-base-name blocking key` | `BlockingKeys.keys` gives the same key for NFC `José García` and NFD `José García` | NFD key is `List("José García")`, different from the NFC key `List("José García")`. The two spellings never share a block, so J06 never compares them | `blocking/BlockingJob.scala`, `BlockingKeys.keys`: no Unicode normalization. Its doc says key normalization must come from core; if hadoop-engineer decides NFC folding belongs in a core contract, the orchestrator should route F4 to `domain-engineer` (`DblpAuthorName`) |

No failure points at `domain-engineer` code.

## Per-test results (63 tests: 59 passed, 4 failed)

**XmlBoundaryAdversarialSuite (14 tests: 11 passed, 3 failed)**

| Test | Case | Result |
|---|---|---|
| xml-boundary: single reader finds exactly the five corpus records, all Ok | malformed/truncated XML (baseline) | PASS |
| xml-boundary: two readers at every split point read each record exactly once | record cut at a split boundary, all 1333 split points | PASS |
| xml-boundary: three readers over a grid of split pairs read each record exactly once | split boundaries, 3 readers | PASS |
| xml-boundary: split points exactly at a record's '<' and just after it | split boundary | PASS |
| xml-boundary: every corpus record maps to the expected outcome (accepted or excluded) | entities, markup, `>` inside an attribute value | PASS |
| truncated-xml: unclosed final record yields one Truncated unit after the Ok records | unclosed final record | PASS |
| truncated-xml: cut anywhere after the final start tag name yields a Truncated unit | truncated XML | PASS |
| truncated-xml: cut inside the final record's start tag name is still reported | truncated XML | **FAIL (F1)** |
| truncated-xml: truncated final record spanning a split end is reported by one reader | truncated record and split | PASS |
| malformed-xml: unclosed middle record never yields an accepted record | malformed XML | PASS |
| malformed-xml: mismatched, unescaped and garbage content is rejected as malformed_xml | malformed XML (mismatched tag, `AT&T`, raw `<`, duplicate attribute, `&#1;`, `&#xD800;`) | PASS |
| malformed-xml: end tag with whitespace before '>' is a well-formed record boundary | malformed/edge XML | **FAIL (F2)** |
| malformed-xml: a record-like tag inside an XML comment does not swallow the next record | malformed/edge XML | **FAIL (F3)** |
| oversize-record: a record above maxRecordBytes is Oversize with a bounded preview | oversize record | PASS |

**UnicodeAdversarialSuite (14 tests: 13 passed, 1 failed)**

| Test | Case | Result |
|---|---|---|
| unicode-nfd: decomposed author names are preserved verbatim (no lossy rewrite) | NFD | PASS |
| unicode-nfc-nfd: canonically equivalent names share a dblp-base-name blocking key | NFC/NFD | **FAIL (F4)** |
| unicode-nfc-nfd: homonym suffix still splits off a decomposed name | NFD | PASS |
| confusables: Cyrillic and Latin look-alikes stay distinct names, never a self pair | confusable names | PASS |
| entities: declared, predefined, decimal and hex references decode to the right text | XML entities | PASS |
| entities: undeclared named entity rejects the record with its name | undeclared entities | PASS |
| entities: nested and self-recursive declarations are not expanded exponentially | entities, billion-laughs shape | PASS |
| entities: external DTD declarations are not fetched (XXE) | entities, XXE | PASS |
| non-bmp: supplementary characters by reference and raw UTF-8 survive intact | non-BMP | PASS |
| non-bmp: pair canonicalisation uses code point order, not UTF-16 order | non-BMP ordering | PASS |
| non-bmp: a lone surrogate in raw UTF-8 bytes is rejected as invalid encoding | non-BMP, bad encoding | PASS |
| bom: UTF-8 byte order mark before the XML declaration is tolerated | BOM | PASS |
| bom: BOM without an XML declaration defaults to UTF-8 | BOM | PASS |
| unicode-whitespace: NBSP, NEL and ideographic space collapse to one space in names | Unicode whitespace | PASS |

**RecordFieldAdversarialSuite (15 tests: all passed)**

| Test | Case | Result |
|---|---|---|
| missing-key: absent, empty and blank key attributes are rejected as missing_key | missing key | PASS |
| missing-key: structurally invalid keys are rejected as invalid_key | missing/invalid key | PASS |
| missing-title: absent, empty, blank and markup-only titles are rejected | missing title | PASS |
| missing-title: two different titles are a conflicting_field rejection | title conflict | PASS |
| missing-year: absent year is excluded (year), malformed year is rejected (invalid_year) | missing year (incl. fullwidth and Arabic-Indic digits) | PASS |
| missing-year: whitespace around a valid year is tolerated; two years conflict | year | PASS |
| missing-authors: no author, empty author and blank author are rejected | missing authors | PASS |
| missing-authors: an editor-only record has no authors and is rejected | missing authors | PASS |
| duplicate-key: identical copies of a dblp key collapse into one team | duplicate dblp key, identical content | PASS |
| duplicate-key: conflicting copies of a dblp key are rejected, not merged | duplicate dblp key, conflicting content | PASS |
| duplicate-key: mentions that disagree with the work's author order are rejected | duplicate/mismatched mentions | PASS |
| duplicate-doi: two spellings of one DOI in a record give one DOI, no conflict | duplicate DOI | PASS |
| duplicate-doi: two different DOIs in a record leave doi empty with a conflict issue | DOI conflict | PASS |
| duplicate-doi: one DOI on two dblp works is a conflict and produces no links | duplicate DOI across works | PASS |
| duplicate-doi: a DOI with no dblp work is unmatched, not linked | DOI unmatched | PASS |

**ScaleAndProvenanceAdversarialSuite (20 tests: all passed)**

| Test | Case | Result |
|---|---|---|
| giant-team: oversize flag flips exactly above the configured threshold | team above threshold | PASS |
| giant-team: a team above the hard limit is rejected with team_exceeds_hard_limit | team above hard limit | PASS |
| giant-team: changing the limits changes the outcome for the same input (config driven) | thresholds from config | PASS |
| giant-team: coauthor context is capped by maxCoauthorContext and marked truncated | giant team | PASS |
| pair-count: n*(n-1)/2 for small, boundary and int-overflowing team sizes | n(n-1)/2 | PASS |
| pair-sharding: shards cover every (i<j) pair exactly once for any shard size | sharding | PASS |
| pair-sharding: unrank is exact at row boundaries and the last index of a giant team | sharding, n = 100000 | PASS |
| giant-team: a 120-author team expands to 7140 distinct pairs regardless of shard size | giant team, sharding, determinism | PASS |
| pair-record: every pair carries work ID, year, team size, positions and provenance | pair record contents | PASS |
| pair-record: reordered author list keeps positions attached to the right names | positions | PASS |
| pair-record: homonyms on one work are a self pair and consolidate positions A,B,A | positions, consolidation | PASS |
| determinism: expansion is identical across runs; run context changes only provenance | determinism | PASS |
| common-name: homonym-numbered variants share one dblp-base-name block key | common names | PASS |
| common-name: unsupported blocking family fails loudly instead of emitting nothing | common names | PASS |
| common-name: a hot block at maxBlockSize defaults exceeds maxPairsPerBlock in J06 | hot key vs J06 maxPairsPerBlock (arithmetic on the defaults only) | PASS |
| common-name: J06 features are symmetric and the candidate is canonical | J06 MatchCandidate | PASS |
| config: every threshold key has a documented positive default in the defaults resource | thresholds from config | PASS |
| config: scanner maxRecordBytes is a parameter, not a constant | thresholds from config | PASS |
| config: subset year window comes from the rule, not from code | thresholds from config | PASS |
| rejects: disposition records are versioned, carry the reason, run, snapshot and hash | versioned rejects with a reason | PASS |

## Requirement-to-test coverage

| Requirement | Covered | Gap |
|---|---|---|
| Malformed and truncated XML, split boundary, unclosed final record | Yes, 14 tests | F1, F2, F3 failing |
| Unicode: NFC/NFD, confusables, entities, undeclared entities, non-BMP, BOM | Yes, 14 tests | F4 failing |
| Missing key, title, year, authors | Yes, 8 tests | none |
| Duplicate dblp keys (identical and conflicting), duplicate DOIs | Yes, 7 tests | The J01 reducer's duplicate/conflict counters need Hadoop |
| Common names: hot key above maxBlockSize, J06 maxPairsPerBlock | Partial | The reducer and mapper enforcement is not run (Hadoop-only) |
| Giant teams: threshold, hard limit, n(n-1)/2, sharding | Yes, 8 tests | The J07 reducer overflow path (`mentionCount > hardLimit + 1`) needs Hadoop |
| Counters add up | No | Hadoop-only |
| Rejects in versioned outputs with a reason | Partial (the `Disposition` record) | Files on disk not checked |
| Deterministic across runs and output roots | Partial (pure functions) | Output roots need Hadoop |
| Output paths contain run ID and snapshot | No | `JobRunner.outputDir` needs Hadoop `Path` |
| Thresholds from configuration | Partial (defaults resource and the parameters) | Override through a Hadoop `Configuration` not tested |
| Every pair carries work ID, year, positions, provenance | Yes | none |

```text
STATUS: FAILED
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-04/prompt.md, human decision 7: adversarial tests against the Hadoop MapReduce pipeline (no production edits, no AWS, no network)
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/AdversarialSupport.scala
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/XmlBoundaryAdversarialSuite.scala
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/UnicodeAdversarialSuite.scala
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/RecordFieldAdversarialSuite.scala
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/ScaleAndProvenanceAdversarialSuite.scala
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/resources/hadoop-adversarial/split-corpus.xml (synthetic, 5 records)
  /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/resources/hadoop-adversarial/bom-utf8.xml (synthetic: UTF-8 BOM, non-BMP, NFD)
FILES MODIFIED: none (nothing outside tests/**; no production code, build, or docs/agent-runs)
COMMANDS RUN:
  sbt -batch "export contractTests/Test/fullClasspath" "show contractTests/Test/fork"  -> only modules/hadoop/target/.../classes on the path, no hadoop-client jars; fork=false
  sbt -batch contractTests/Test/compile with a temporary probe using org.apache.hadoop.conf.Configuration -> "[E008] Not Found Error: value apache is not a member of org" (probe deleted afterwards)
  sbt -batch contractTests/test (first attempt) -> "Bad symbolic reference ... refers to Writable/T in package org.apache.hadoop.io which is not available" (RawRecord extends Writable; my tests now mirror its status constants)
  sbt -batch contractTests/test -> "Failed: Total 63, Failed 4, Errors 0, Passed 59"
  sbt -batch check -> exit 1, 504 s
TEST RESULTS:
  My tests: 63 run, 59 passed, 4 failed, 0 skipped
  sbt check totals: Module tests suites=24 executed=779 passed=779 failed=0 errors=0 skipped=0; Contract tests (no AWS) suites=4 executed=63 passed=59 failed=4 errors=0 skipped=0; overall 842 executed, 838 passed, 4 failed
  Observed: "[error] Contract tests (no AWS) failed; .claude/.last-test-run was not updated."
VALIDATION RESULTS: format check, ledger check and Test/compile in every project passed inside sbt check (it reached the test stages); the gate failed only at contractTestsNoAws.
ASSUMPTIONS:
  - MapperModel mirrors the documented DblpExtractMapper.map order (scan, decode, expand, parse, build). If the mapper drifts from it, these tests no longer model J01 exactly.
  - Split readers are modelled as a stream positioned at the split start with startPos = split start, end = split end. DblpXmlInputFormat itself was not exercised.
  - The NFC/NFD expectation (F4) applies to blocking keys only; source names are expected to stay verbatim, which the tests confirm.
  - The status codes are mirrored from RawRecord (Ok=0, Oversize=1, Truncated=2, DecompressionLimit=3, InputError=4, PreviewBytes=4096). They need updating if those values change.
RISKS:
  - Requirements needing a real job run (counter identity, reject files on disk, output-root determinism, run-ID and snapshot paths, Configuration overrides, J05/J06 enforcement in reducer and mapper, the J01 duplicate reducer) are covered only by hadoop-engineer's own module tests, not independently.
  - Residual risks not asserted: zero-width and format characters (U+FEFF, U+200B) inside names are kept as distinct names; a declared entity that references another named entity is rejected (correct for dblp, which uses only numeric references).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: build-engineer (for the job-level black-box tests only)
BLOCKED-ON EVIDENCE:
  - build.sbt:190-192: hadoopClientApi and hadoopClientRuntime are % Provided in the hadoop project; Provided does not pass to contractTests (build.sbt:318-325, .dependsOn(coreScala3, hadoop, ...)).
  - `export contractTests/Test/fullClasspath` lists no org.apache.hadoop jars, and `show contractTests/Test/fork` gives false, so hadoopJava17Options (build.sbt:155) are not applied.
  - Compile probe output: "[E008] Not Found Error: ... value apache is not a member of org".
  - Requested change: give contractTests Test-scope hadoop-client-api and hadoop-client-runtime, plus forkedTests(hadoopJava17Options).
NEXT GATE:
  1. Route F1, F2 and F3 (DblpRecordScanner) and F4 (BlockingKeys NFC folding, or domain-engineer if a core normalization contract is chosen) to hadoop-engineer.
  2. Route the classpath and fork wiring for contractTests to build-engineer.
  3. Rerun test-engineer fresh to add the job-level tests and rerun sbt check; then code-reviewer.
```
