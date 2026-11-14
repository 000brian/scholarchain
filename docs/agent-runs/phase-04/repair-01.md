STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 4 repair (docs/agent-runs/phase-04/prompt.md): fix the adversarial failures F1-F4 that test-engineer found in modules/hadoop.

All four failures are fixed inside modules/hadoop. Every test passes: all 63 adversarial contract tests and all 65 hadoop module tests. `sbt check` passed. Two fresh local pipeline runs gave byte-identical outputs. I edited nothing under tests/**, modules/core or docs/agent-runs/. I think every adversarial expectation is correct.

FILES CREATED: none

FILES MODIFIED (all under modules/hadoop; the module is still untracked):
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/io/DblpRecordScanner.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/candidates/CandidateFeatureJob.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/ScannerSuite.scala (4 new tests)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/LinkAndBlockSuite.scala (1 new test)
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/CandidateSuite.scala (1 new test)

The modules/core changes in git status were already there when I started.

COMMANDS RUN:
- `sbt -batch fmt`: success, 2 hadoop sources reformatted.
- `sbt -batch "hadoop/testOnly ...ScannerSuite ...LinkAndBlockSuite" "contractTests/testOnly org.scholarchain.contracts.hadoop.*"`: 18/18 module tests and 63/63 contract tests passed.
- Fail-without-fix check:
  - I temporarily put back the old scanner and an identity blocking key, then ran the new tests.
  - ScannerSuite: the 4 new tests failed (8 passed).
  - The new LinkAndBlockSuite and CandidateSuite canonical-equivalence tests failed.
  - Afterwards I restored the fixed sources from scratchpad copies and confirmed with `diff -q` that they were identical.
- `sbt -batch check`: exit 0, `[success] Total time: 505 s`, completed 4:53:18 PM. `.claude/.last-test-run` was refreshed at 16:53.
- `sbt -batch "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline"`, three times:
  - Run 1 reused every stale output (see RISKS), so it tested nothing.
  - Before run 2 I moved the old output directory to the scratchpad and kept it for comparison.
  - Before run 3 I moved run 2's output to the scratchpad, to check determinism.

TEST RESULTS:
- Hadoop module tests, all passing:

| Suite | Tests |
|---|---|
| ScannerSuite | 12 |
| LinkAndBlockSuite | 6 |
| CandidateSuite | 10 |
| DblpParsingSuite | 10 |
| DblpChainSuite | 4 |
| SettingsSuite | 3 |
| NormalizerSuite | 6 |
| PipelineSuite | 3 |
| DblpExtractJobSuite | 5 |
| PairsAndTeamsSuite | 6 |
| **Total** | **65** |

- Contract hadoop suites, all passing:

| Suite | Tests |
|---|---|
| XmlBoundaryAdversarialSuite | 14 |
| UnicodeAdversarialSuite | 14 |
| RecordFieldAdversarialSuite | 15 |
| ScaleAndProvenanceAdversarialSuite | 20 |
| **Total** | **63** |

- The core suites also all passed.

VALIDATION RESULTS:
- Determinism: fresh runs 2 and 3 produced byte-identical output trees (excluding `.crc` files, Hadoop's scratch `work/` directory and the timing report), `_job.json` included.
- Run 2 compared with the pre-repair baseline:
  - Every data file is byte-identical except the J05 `stats/` partitions. That covers J01-J04, J07-J09, J05 `blocks/` and J06 `candidates/`.
  - Accented block keys are now NFD, so they hash to different J05 reducers. Each reducer's `largestBlock` stat line therefore names a different subject:
    - real samples, part-r-00002: "Jiecao Chen" became "Jesús Lovón-Melgarejo"
    - synthetic: a new part-r-00001 for "Synthétique Bertrand"
  - The only counter that changed: synthetic J05 `scholarchain.output-records:stats` went from 3 to 4.
  - The `largestBlockingGroup` subject text is now in NFD form.
  - No other counter, and no maximum, changed.

ASSUMPTIONS:
- I used NFD (canonical decomposition) as the key form, not NFC. The adversarial test "homonym suffix still splits off a decomposed name" expects the key `List("José García")` written as an NFD literal (I checked the source bytes). The F4 test needs NFC and NFD inputs to produce equal keys. Only NFD satisfies both. Either form folds only canonical equivalence.
- Folding the key locally in hadoop is acceptable, so F4 is not BLOCKED-ON domain-engineer:
  - Unicode conformance clause C6 says canonically equivalent sequences must be treated as identical, so this is not a matching policy.
  - There is no NFKD, case folding, accent stripping or transliteration. Tests check that fullwidth, lowercase and unaccented variants stay distinct.
  - Source names stay verbatim in every record; only keys and comparisons are folded.
  - BlockingKeys already lives in hadoop, and the extra blocking families stay deferred to Phase 5.
- I left the version labels `blocking/1-provisional` and `candidate-features/1-provisional` as they are. Neither has been committed or released, and the contract tests reference `blocking/1-provisional`.
- A lone `<` (or any partial name that could begin a boundary element) at end of input is reported as Truncated, because a well-formed dblp file can never end there. A cut name that cannot begin a record (for example `</dbl`) yields no unit.

RISKS:
- **Stale reuse:** idempotent output reuse (JobRunner) is keyed on input and config fingerprints, not the code version. After a code change, a rerun silently reuses old outputs: run 1 above reported `"reused": true` for every job and exercised none of the fix. A cluster rerun after a code change needs a new output root or a config change. Including a code or build fingerprint in the run ID would fix this; the run ID is built with core `RunId.of`, so the orchestrator should decide routing.
- **Comment across a split (scanner limitation):** this only affects uncompressed, splittable input. If a comment containing record-like markup crosses an HDFS split boundary, the second reader cannot know it started inside a comment. It may claim the commented tag and swallow the next real record into a rejected unit. That loss is counted, not silent. Real dblp.xml has no comments in its body, and gzip input is read by a single reader. The scanner tests cover comments, PIs and CDATA at every split point of their corpora.
- **Unterminated comment at end of input:** a comment, CDATA section or PI left open at end of input outside a record ends the scan with no unit, because its content is not a record. Inside a record it yields Truncated.
- **Feature semantics changed:** `name_exact_match` and `coauthor_shared_count` now compare under canonical equivalence, with the same feature-set label. Outputs differ only for NFC/NFD-mixed input; the local samples have none, and J06 output was byte-identical.

MANUAL ACTION REQUIRED: none. The pre-repair pipeline output and fresh run 2 are in the scratchpad (/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/183f6251-40b4-4bce-9aa1-4ee577720ebd/scratchpad/local-bounded-run-before and .../local-bounded-run-after1). They are temporary and can be discarded.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer reruns the adversarial suites (now 63/63 locally), then code-reviewer reviews the full modules/hadoop diff.

## Failures

**F1. Truncated inside the final record's start-tag name**
- Root cause: in `DblpRecordScanner.scan()`, when input ended (`c < 0`) after `<` plus a partial name, the code returned None and emitted no unit. The record disappeared with no counter and no reject.
- Fix: end of input after `<` plus a partial name that is a prefix of a boundary element name now returns a `RawRecord.Truncated` unit. It carries the tag offset, the partial name as `element`, and the bytes `<partial` as payload. The mapper counts it as `truncated_record`.
- Proof: `ScannerSuite` "end of input inside the start tag name of a record is reported as truncated" (cuts 1-8 of `<article`; `</dbl` yields nothing). Contract test now passes.

**F2. End tag with whitespace before `>`**
- Root cause: `capture()` matched only the exact bytes `</name>`, so `</article >` never ended the record.
- Fix: the body is now a small state machine. A record ends at `</name S* >`, where S is space, tab, CR or LF (XML 1.0 [42] `ETag ::= '</' Name S? '>'`, [3] S). A longer name (`</articles>`) or other bytes after the name (`</article x>`) do not end it.
- Proof: `ScannerSuite` "end tag with whitespace before '>' ends the record (XML 1.0 [42] ETag)". Contract test now passes.

**F3. Record-like tag inside a comment**
- Root cause: the scanner did not recognise comments, CDATA sections or processing instructions.
- Fix: a new `skipSpecial` handles `<!-- ... -->` (XML [15]), `<![CDATA[ ... ]]>` ([18]-[21]) and `<? ... ?>` ([16]). It applies both between records (skipped) and inside a record body (bytes kept verbatim, but they can never end the record). Any other `<!` (DOCTYPE, ENTITY) and partial openers (`<!-x`, `<![CD`) fall through unchanged. End of input inside one of these within a record yields Truncated.
- Proof:
  - `ScannerSuite` "comments, CDATA sections and processing instructions never start or end a record".
  - `ScannerSuite` "record-boundary safety holds with comments, PIs and spaced end tags": two readers at every split point of the corpus give the same status, offset and bytes as one reader.
  - Contract test now passes. The existing split-point tests over both the real sample and split-corpus.xml still pass.

**F4. NFC/NFD blocking key, and the J06 features**
- Root cause: `BlockingKeys.keys` used the raw string from `DblpAuthorName.split`, so NFC and NFD spellings landed in different blocks. In J06, `name_exact_match` used raw `==` and `coauthor_shared_count` used raw set intersection, so they had the same inconsistency. `homonym_suffix_match` is not affected (it compares ASCII digits).
- Fix:
  - New `BlockingKeys.canonical(s) = Normalizer.normalize(s, NFD)`.
  - The `dblp-base-name` key is `canonical(split(name)._1)`.
  - J06 compares `canonical(a.name) == canonical(b.name)` and intersects the coauthor names after `canonical`.
  - Source names in WorkRecord, AuthorMention, MentionContext and BlockMember are untouched.
- Proof:
  - `LinkAndBlockSuite` "dblp-base-name blocking key is the same for canonically equivalent names" (also checks that fullwidth, lowercase and unaccented forms stay distinct).
  - `CandidateSuite` "features: names and coauthors compare under canonical equivalence (NFC = NFD)".
  - Contract test now passes, and the NFD homonym test still passes.

## Sample-run evidence (fresh runs 2 and 3, identical)

Global maxima are unchanged from the pre-repair baseline.

| Measure | Real samples | Synthetic |
|---|---|---|
| Maximum observed team size | 7 | 5 |
| Largest pair expansion | 21 | 10 |
| Largest blocking group | 1 (all singletons; SINGLETON_BLOCKS 17) | 4 ("Synthetic Author") |
| Match candidates | 0 | 6 |
| Largest identifier group | 9 | 5 |
| Pair instances generated / consolidated pairs | 33 / 33 | 11 / 7 |
| Links | 67 | 0 |

- Synthetic J05: BLOCKS_EMITTED 1, SINGLETON_BLOCKS 4.
- Candidate oversize blocks: 0 in both runs.

## Reject reasons

Nothing new was added. Scanner end-of-input in a start-tag name now flows into the existing `truncated_record` reason.
