## Phase 4 test-engineer report (rerun after the network interruption)

**Result: one real production defect, owned by hadoop-engineer.** The contract tests run 87, pass 86 and fail 1. `sbt check` fails (exit=1) because of that one test, and `.claude/.last-test-run` was not updated. All 785 module tests pass.

The inherited suites from the interrupted run were complete and compiled without changes. I edited no files in this rerun.

**The failing test:** `JobLevelRerunSuite` › "recovery: setting replaceIncomplete=true replaces the incomplete directory of a failed run" (`/Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/JobLevelRerunSuite.scala:157-163`).
- **Expected:** the operator reruns the failed default-config run with `scholarchain.output.replaceIncomplete=true`. The recovery run gets the same run ID (`rid`) and replaces the incomplete directory `dir`.
- **Actual:** a different run ID, `run:v1:efb5aca604ec78b9631e384e46ab9e6f`.
- **Cause:** `Settings.fingerprint` (`modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/Settings.scala:75-87`) hashes every `scholarchain.*` key except `scholarchain.run.*` and `scholarchain.path.*`. That includes `scholarchain.output.replaceIncomplete` (Settings.scala:92).
  - So turning the flag on changes the configuration fingerprint, which changes the run ID and the output directory.
  - The incomplete directory of the failed default run is never replaced. Every later default-config rerun keeps failing with the `JobFailed` that `JobRunner.scala:87-90` throws.
  - This contradicts the `JobRunner` scaladoc (JobRunner.scala:52-58) and the Settings documentation of that key.
- **Probable fix (hadoop-engineer's decision):** leave operational keys such as `output.replaceIncomplete` out of the fingerprint, for example by moving them under a key prefix that is excluded.

**Finding on the code version (that test passes and records the current behaviour).** This confirms the risk hadoop-engineer reported.
- `scholarchain.code.version` does feed the run ID. `JobRunner.runId` (JobRunner.scala:65-69) passes it to `RunId.of`, and it is also inside the config fingerprint. It is recorded in `_job.json`.
- It has no default: a run without it throws `SettingsError` before writing any output.
- But it is a string the caller supplies, not something derived from the built code.
- A directory holding `_SUCCESS` and `_job.json` is reused without checking its contents (JobRunner.scala:84-85).
- Evidence: the test "documented gap: same code.version string reuses a completed output without validation" overwrites the completed J01 works part file with `STALE OUTPUT FROM ANOTHER BUILD`. The identical rerun reports `reused=true` with the same run ID, leaves the stale bytes in place, and all downstream jobs are reused too.
- So a code change run with an unchanged `code.version` string silently reuses stale outputs. This is passing documentation of a design risk, not a failing test. I suggest a severity of medium. The owner is hadoop-engineer; drivers or infra-engineer scripts that set the version may also need to be involved.

A related observation: the fingerprint covers every `scholarchain.*` key. So a threshold that only J07 reads also gives J01 a new run ID and a full rerun. This is wasteful but not wrong, and is documented in the test "a changed threshold gives new run IDs…".

## Files inherited from the interrupted run
I read all of them, and each compiled and ran.
- **`JobHarness.scala`** (new): complete; no change.
- **`JobLevelBlockingSuite.scala`**: complete, 6 tests, all pass. It was suspected to be incomplete, but it was not; no change.
- **`JobLevelDblpAdversarialSuite.scala`** (6 tests), **`JobLevelTeamScaleSuite.scala`** (4) and **`JobLevelRerunSuite.scala`** (8): no change.
- **`resources/hadoop-adversarial/job-corpus.xml`** and **`resources/logback-test.xml`**: no change.
- **`AdversarialSupport.scala`**, **`ScaleAndProvenanceAdversarialSuite.scala`**, **`UnicodeAdversarialSuite.scala`** and **`XmlBoundaryAdversarialSuite.scala`**:
  - They now import the real `org.scholarchain.hadoop.io.RawRecord` (`RawRecord.Ok` / `RawRecord.Oversize`) instead of mirrored constants.
  - The test counts are intact: 14 + 14 + 15 + 20 = 63, all pass.
  - The `tests/` tree is untracked, so I have no git baseline to diff against. I checked the assertions by reading them and saw no weakening.

## Per-test table (all 87, from the sbt check run)
**XmlBoundaryAdversarialSuite (14/14 pass):**
- single reader finds exactly five records
- two readers at every split point
- three readers over a split grid
- split exactly at '<' and just after it
- each corpus record gets its expected outcome
- truncated: unclosed final record gives a Truncated unit
- truncated: cut anywhere after the start-tag name
- truncated: cut inside the start-tag name (F1)
- truncated record spanning a split end
- malformed: unclosed middle record never accepted
- malformed: mismatched, unescaped and garbage content gives malformed_xml
- `</article >` is a boundary (F2)
- record tag inside a comment (F3)
- oversize record has a bounded preview

**UnicodeAdversarialSuite (14/14 pass):**
- NFD names kept verbatim
- NFC and NFD share a blocking key (F4)
- homonym suffix split off an NFD name
- Cyrillic and Latin confusables stay distinct
- declared, predefined, decimal and hex entities
- undeclared entity rejected with its name
- nested entity expansion is bounded
- no XXE fetch
- non-BMP characters survive
- pair ordering by code point
- lone surrogate gives invalid encoding
- BOM before the declaration
- BOM without a declaration
- Unicode whitespace collapses

**RecordFieldAdversarialSuite (15/15 pass):**
- missing key gives missing_key
- invalid key gives invalid_key
- missing or blank title
- conflicting titles
- missing or malformed year
- whitespace year and two years
- missing or blank authors
- editor-only record
- identical duplicate key collapses
- conflicting duplicate key rejected
- mention and work order disagree
- duplicate DOI spellings
- two different DOIs
- one DOI on two works
- unmatched DOI

**ScaleAndProvenanceAdversarialSuite (20/20 pass):**
- oversize flag flips at the threshold
- hard limit gives team_exceeds_hard_limit
- limits are config-driven
- coauthor context cap
- n(n-1)/2 including int overflow
- shard coverage
- unrank at boundaries
- 120 authors give 7140 pairs
- pair record fields and provenance
- reordered authors keep their positions
- homonym self pair
- expansion determinism
- homonym-numbered names share a block key
- unsupported blocking family fails loudly
- hot block exceeds maxPairsPerBlock
- J06 symmetric and canonical
- every threshold has a documented default
- scanner maxRecordBytes is a parameter
- year window comes from the rule
- disposition records are versioned

**JobLevelDblpAdversarialSuite (6/6 pass):**
- J01 exact counters and the identity: INPUT=12, ACCEPTED=4, EXCLUDED=2, REJECTED=6, MISSING_KEY=1, per-reason counters
- the identity holds for J01, J07, J08a, J08b, J05 and J06; downstream REJECTED=0
- malformed, undeclared-entity, missing-key, no-title, no-authors and truncated records are in rejects/v1 with reason, run, snapshot, hash and location
- J01 reducer: duplicate=1, conflict=2, two reduce rejects, both versions kept
- paths contain `dblp/snapshot=<snap>/`, `<job>/` and `run=<hex>`; `_job.json` has the run ID, snapshot and codeVersion
- determinism: two output roots give the same run IDs and byte-identical part files and `_job.json`

**JobLevelBlockingSuite (6/6 pass):**
- default limits: one hot block of 12 (NFC, NFD and homonym) and 66 candidates; NFC/NFD exact-name feature is 1.0
- maxBlockSize=11: oversize listed, block not emitted, J06 input 0, new run ID
- maxBlockSize=12 boundary emits the block
- maxPairsPerBlock=65: skipped and counted as 66 pairs; with 66 the block is expanded
- pairsPerShard=10 with 4 reducers: 7 shards, more than one non-empty reducer part, same candidates as one shard
- J05/J06 byte-identical across two roots

**JobLevelTeamScaleSuite (4/4 pass):**
- 60-author team with oversize=50: counter 1, 1770 pairs, 19 shards, spread over reducers, every (i<j) pair exactly once, provenance, consolidation
- default threshold 100 does not flag the team; the override changes the run ID
- hard limit, assemble path (59): rejected with the detail "team of 60 > 59"
- hard limit, overflow path (10): rejected with the detail "60 mentions > 10"; no pairs from the giant team

**JobLevelRerunSuite (7/8 pass, 1 fail):**
- PASS: same inputs and config mean everything is reused, with the same run IDs, files and mtimes
- PASS: a changed threshold gives new run IDs and directories; old output is kept
- PASS: code.version feeds the run ID and is recorded in `_job.json`
- PASS: no code.version means `SettingsError` and no output
- PASS: documented gap: stale output reused under the same code.version
- PASS: incomplete directory with the default setting fails
- PASS: incomplete directory with replaceIncomplete=true set in the creating configuration is replaced
- **FAIL: recovery with replaceIncomplete=true (the defect described above)**

## Requirement-to-test coverage
| Requirement | Tests | State |
|---|---|---|
| INPUT = ACCEPTED + EXCLUDED + REJECTED per job | Dblp tests 1 and 2; TeamScale test 1; Blocking test 1 | pass |
| rejects/v1 with a reason for malformed, truncated and undeclared-entity input | Dblp test 3; ScaleAndProvenance "rejects" | pass |
| Determinism across output roots | Dblp test 6; Blocking test 6; ScaleAndProvenance determinism | pass |
| Output paths contain the run ID and snapshot | Dblp test 5 | pass |
| Oversize team threshold and hard limit through Configuration | TeamScale tests 1-4; ScaleAndProvenance giant-team tests | pass |
| J05 maxBlockSize | Blocking tests 2 and 3 | pass |
| J06 maxPairsPerBlock and pairsPerShard | Blocking tests 4 and 5 | pass |
| J01 duplicate and conflicting keys, with counters | Dblp test 4; RecordField duplicate-key tests | pass |
| J07 hard-limit overflow path | TeamScale test 4 | pass |
| Hot common-name block end to end through J05 and J06 | Blocking tests 1-5 | pass |
| Giant team n(n-1)/2 across shards and reducers, plus the oversize counter | TeamScale test 1 | pass |
| Rerun: same inputs mean reuse; changed config means a new run ID | Rerun tests 1 and 2 | pass |
| Does code.version participate in the run ID? | Rerun tests 3 and 4 (yes; required, no default) | pass |
| Code-version stale-reuse gap documented with evidence | Rerun test 5 | pass (finding) |
| Recovery from an incomplete directory | Rerun tests 6-8 | **1 FAIL (defect)** |
| Malformed or truncated XML, Unicode, missing fields, duplicate IDs (decision 7) | XmlBoundary, Unicode, RecordField | pass |

## Failures by owning agent
- **hadoop-engineer:** `JobLevelRerunSuite` "recovery: setting replaceIncomplete=true replaces the incomplete directory of a failed run" (the defect above).
- **hadoop-engineer:** the code-version reuse gap. This is a passing test that documents the behaviour; it needs a design decision rather than a test change.

STATUS: FAILED
OWNER: test-engineer
WORK ORDER: docs/agent-runs/phase-04/prompt.md (human decision 7); Phase 4 test-engineer rerun after the interruption
FILES CREATED: none in this rerun. For the whole Phase 4 contribution, all under /Users/brian/Developer/repos/scholarchain/tests/contracts/src/test/:
- scala/org/scholarchain/contracts/hadoop/AdversarialSupport.scala
- scala/org/scholarchain/contracts/hadoop/XmlBoundaryAdversarialSuite.scala
- scala/org/scholarchain/contracts/hadoop/UnicodeAdversarialSuite.scala
- scala/org/scholarchain/contracts/hadoop/RecordFieldAdversarialSuite.scala
- scala/org/scholarchain/contracts/hadoop/ScaleAndProvenanceAdversarialSuite.scala
- scala/org/scholarchain/contracts/hadoop/JobHarness.scala
- scala/org/scholarchain/contracts/hadoop/JobLevelDblpAdversarialSuite.scala
- scala/org/scholarchain/contracts/hadoop/JobLevelBlockingSuite.scala
- scala/org/scholarchain/contracts/hadoop/JobLevelTeamScaleSuite.scala
- scala/org/scholarchain/contracts/hadoop/JobLevelRerunSuite.scala
- resources/hadoop-adversarial/split-corpus.xml
- resources/hadoop-adversarial/bom-utf8.xml
- resources/hadoop-adversarial/job-corpus.xml
- resources/logback-test.xml
FILES MODIFIED: none in this rerun
COMMANDS RUN:
- `sbt -batch "contractTests/Test/compile"`: [success]
- `sbt -batch "contractTests/test"`: "Failed: Total 87, Failed 1, Errors 0, Passed 86"
- `sbt -batch check`: exit=1; "[error] Contract tests (no AWS) failed suite: org.scholarchain.contracts.hadoop.JobLevelRerunSuite"; ".claude/.last-test-run was not updated"; total time 1045 s
TEST RESULTS:
- My tests: 87 run, 86 passed, 1 failed, 0 skipped or ignored.
  - First run: 63/63 passed.
  - Job level: 24 run, 23 passed, 1 failed.
- Full sbt check: 872 tests run across all suites, 871 passed, 1 failed, 0 ignored.
  - Modules: 785/785 passed.
  - Contracts: 86/87 passed.
VALIDATION RESULTS: format check and compilation passed inside sbt check; the ledger check passed (scalafmt 3.11.5); the test stage failed as described above.
ASSUMPTIONS:
- The interrupted run's edits to the four older suites cannot be diffed, because `tests/` is untracked. Their integrity was checked by reading the assertions and by the 63/63 pass.
- The recovery behaviour expected by the failing test comes from the `JobRunner` scaledoc and the Settings documentation of `replaceIncomplete`.
RISKS:
- Stale output can be reused when `code.version` is not bumped.
- An incomplete output directory cannot be recovered through configuration and needs manual cleanup. Deleting it is a destructive operation that needs human approval.
- The job-level suites take about 9 minutes in total, which lengthens sbt check.
MANUAL ACTION REQUIRED: the human decides whether to route the recovery defect and the code-version design risk to hadoop-engineer.
BLOCKED-ON OWNER: none (the run ends FAILED; the defect owner is hadoop-engineer)
BLOCKED-ON EVIDENCE: JobLevelRerunSuite.scala:163; Settings.scala:75-92; JobRunner.scala:65-90
NEXT GATE: hadoop-engineer fixes the replaceIncomplete fingerprint defect, then test-engineer reruns sbt check, then code-reviewer reviews the full diff.
