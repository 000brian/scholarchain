STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 4 re-review (round 2) of the hadoop-engineer repair for findings 1 and 3-9 (docs/agent-runs/phase-04/prompt.md). Inputs: first review /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/183f6251-40b4-4bce-9aa1-4ee577720ebd/scratchpad/phase-04/review-report.md and repair report /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/183f6251-40b4-4bce-9aa1-4ee577720ebd/scratchpad/phase-04/repair-04.md
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run, because I have no Bash. I did not observe `sbt check` myself. I am relying on the orchestrator's account: exit 0, core 720/720, hadoop 79/79, contract 87/87, and `.last-test-run` showing module_tests=799, contract_tests=87. I read every new or changed proving test and judged each one against the code (details below).
VALIDATION RESULTS:
- I read all the repaired main sources: PathIds, JobRunner/JobSpec, PairJobs, PairExpansion, DblpEntities, DblpExtractJob, Settings, Disposition, RawRecord, DblpRecordScanner, ScholarChainInputFormats, DblpChain, Pipeline and the defaults XML.
- I read the new tests in DblpChainSuite, PairsAndTeamsSuite, DblpParsingSuite, DblpExtractJobSuite, ScannerSuite, SettingsSuite and PipelineSuite.
- Local run artifacts under /Users/brian/Developer/repos/scholarchain/modules/hadoop/target/local-bounded-run:
  - 40 of 40 consolidated pairs (33 real, 7 synthetic) carry `"jobName":"hadoop.j08b-pair-consolidate"`. None carry a J08a jobName.
  - All 20 `_job.json` files contain `inputRuns`.
- Call sites of the compatibility shims: they are used only from tests. Every job path uses the bounded or configured variant: DblpExtractJob.scala:182, PairJobs.scala:116 and :238, ScholarChainInputFormats.scala:70-77 and :99-105.
ASSUMPTIONS:
- The tracked diff (build.sbt, modules/core) is unchanged since review 1, and tests/** was not touched this round. I took both from the orchestrator. I cannot diff, but every file the repair report lists is under modules/hadoop/**, and the tests/** call sites still use the old signatures, which fits "not changed".
- The local-run artifacts are the output of the post-repair run. Their run IDs match the ones in the repair report.
RISKS: I could not observe runtime behaviour or the gate output myself. The deferred findings 2 and 10-14 remain open (see below). Finding 2 must still be closed before any cloud run.
MANUAL ACTION REQUIRED: The human decides whether to accept new findings N1-N5 as recorded risks. None of them blocks.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human gate for Phase 4. The orchestrator writes the verbatim reports to docs/agent-runs/phase-04/, including this one as review-report-02.md, after human approval of each write.

VERDICT: APPROVE

## Per-finding verification

| # | Status | Evidence (file:line) |
|---|---|---|
| 1 | resolved | **Fix.** PairJobs.scala:202-217: the `consolidate(instances, run)` overload re-stamps with `run.provenance(...)` and keeps sourceType, sourceRecordIds, contentHash, datasetId and snapshotId. The reducer uses it at PairJobs.scala:238. The scaladoc is updated at 143-153 and 184-201.<br>**Lineage.** JobRunner.scala:41-49 adds `inputRuns` with the invariant `inputRuns.isEmpty \|\| inputFingerprint == fingerprintRuns(inputRuns)`. JobRunner.scala:181 writes it sorted and deduplicated into `_job.json`. `fromRuns` (JobRunner.scala:296-297) builds the spec from `fingerprintRuns(runs)` and then `copy(inputRuns = runs)`, so the invariant holds by construction. The copy re-runs the constructor check. It is applied to J07, J08a and J08b (DblpChain.scala:41-64) and to J04, J05, J06 and J09 (Pipeline.scala:96-164). Raw-input jobs J01 and J02 record `[]`.<br>**Tests.** DblpChainSuite.scala:102-117 asserts the J08b runId and jobName on every consolidated pair, `assertNotEquals` against the J08a run, and source facts equal to those of the instance. This fails against the old `instances.head.copy`. DblpChainSuite.scala:124-143 checks the inputRuns chain. PairsAndTeamsSuite.scala:92-119 is the unit-level test. |
| 3 | resolved | **Fix.** DblpEntities.scala:100-131. The check `atLeast = sb.length + (m.start - last) + replacement.length` happens before each append, plus a final check at 127-128. It is a true lower bound: the rest of the input contributes at least 0. At most `maxChars` characters are ever built. The no-`&` fast path is also bounded. The key comes from configuration (DblpExtractJob.scala:105) and is documented in the defaults XML (lines 157-164). It is fingerprinted because it is a `scholarchain.*` key that is not operational. Reject reason `expanded_record_oversize` at Disposition.scala:96 and DblpExtractJob.scala:186-195. The preview is the unexpanded text.<br>**Tests.** DblpExtractJobSuite.scala:127-147: 100 references to a 1000-character entity, limit 50000, gives a reject, the counter and the accounting identity. The old unbounded expand would have accepted it. The same input under defaults is accepted. DblpParsingSuite.scala:43-64: the 300k × 1 MiB case stops below 4 Mi characters, plus exact-boundary cases (8 accepted, 7 refused). |
| 4 | resolved (one edge case, see N1) | DblpExtractJob.scala:55-61 hashes the raw fingerprint together with `fingerprintFiles(dtd)`. DblpChain.scala:38-39 applies it to both the computed fingerprint and an explicit one. The test at DblpChainSuite.scala:145-166 changes the DTD content at the same path and asserts a new run ID and `!reused`, for both the computed and the explicit fingerprint. It would have reused the old output before the fix. |
| 5 | resolved (claim weakened) | Settings.scala:118-131, defaults XML lines 20-25, and JobRunner.scala:73-74 and 258-263 now give the split-layout and input-location conditions. The test DblpChainSuite.scala:185 changes only the output root, which stays inside the new claim. |
| 6 | resolved | **Patterns.** PathIds.scala:10-49. `IdPattern [A-Za-z0-9._-]+` and `SegmentPattern [A-Za-z0-9._=+-]+`; both reject exactly `.` and `..`. `requireScope` splits with `-1`, so empty, leading-`/` and `//` segments are rejected.<br>**Do `+` and `=` reopen traversal or injection?** No. Neither character is a path separator. `/`, `\`, `:` (URI scheme) and the glob characters `* ? [ ] { }` all remain excluded. A segment such as `...` or `snapshot=..` is an ordinary file name, not a parent reference.<br>**Where it is applied.** The strict pattern covers user input: PipelineSpec (Pipeline.scala:33), SourceInput (18-19) and DblpChain (DblpChain.scala:37). JobSpec checks segments and scope in its constructor (JobRunner.scala:43-45), which runs before `fs.delete` (JobRunner.scala:111).<br>**Tests.** PipelineSuite.scala:142-170 includes the CLI case `--dblp-snapshot ../../x` and checks that nothing is created. DblpChainSuite.scala:168-183 does the same for DblpChain. |
| 7 | resolved | PairExpansion.scala:88-103: `IndexedTeam` makes one `toVector` copy. It is built once per shard at PairJobs.scala:113 and used at :116. The test at PairsAndTeamsSuite.scala:121-131 checks only the type and equivalence. It cannot detect a performance regression, which is acceptable for a performance finding. |
| 8 | resolved | Settings.scala:54-63: `previewChars.toLong * 4`. Above `Int.MaxValue - 8` it throws SettingsError, so there is no Int overflow (the multiplication is done in Long). The check runs at driver time (JobRunner.scala:99), and both input formats pass the configured value (ScholarChainInputFormats.scala:76 and :104).<br>Tests: DblpExtractJobSuite.scala:149-162 (preview of 6000 characters; it was 4096 before the fix), SettingsSuite.scala:37-44 (overflow case) and ScannerSuite.scala:71-85. See N5 for a cosmetic caveat. |
| 9 | resolved (documented, not prevented) | DblpRecordScanner.scala:22-33: the "exactly once" claim is now limited to start tags outside comments and CDATA, and phantom units are described accurately, including that they can be accepted. The test at ScannerSuite.scala:56-69 pins the current behaviour (whole-file read gives one unit; a split inside the comment gives a ghost). It is a characterization test by design. |

**Retained shims.**
- The shims are: the two-argument `expand`, the one-argument `consolidate`, `pairAt(team, ...)`, the auxiliary scanner constructors, and `RawRecord.PreviewBytes`.
- None of them is reachable from a job path. `pairAt(team)` delegates to the indexed variant, so it is not a separate code path.
- They do not weaken fixes 1, 3, 7 or 8 inside jobs.
- They do leave the contract-test model of the mapper out of date (N2), and they leave one unbounded public API in place (N3).

**Scope.**
- The repair is confined to modules/hadoop/**.
- None of the deferred findings was implemented:
  - JobRunner.load (JobRunner.scala:211-221) still does not verify a reused output (finding 2 is open).
  - `isSplitable` is unchanged (ScholarChainInputFormats.scala:60-61; finding 14).
  - There is no core text-key helper and no export of non-dblp works (findings 10 and 11).

**Re-check for new regressions.**
- Determinism: `_job.json` sorts inputRuns; reducers are unchanged; part files match across output roots in the test.
- Counters: the counter set is unchanged, and the report says values match the previous run key by key.
- Thresholds: the new key has a default in the XML, so the SettingsSuite key/description check covers it. The ×4 factor is a property of UTF-8, not a tunable threshold.
- Provenance: J08b now stamps its own run. J09 passes core records through unchanged and the artifacts confirm this.

## Deferred findings (accepted risks; descriptions still accurate)
- **2 (gate before the first cloud run):** a reused output is still not verified against codeVersion and the fingerprints recorded in `_job.json` (JobRunner.scala:103-104, 211-221). Owners: hadoop-engineer, build-engineer, infra-engineer, test-engineer.
- **10:** NFD canonicalization exists only in hadoop. Owner: domain-engineer, then hadoop-engineer.
- **11:** abstracts, authorships and affiliations are not in the J09 export. Owner: domain-engineer, then hadoop-engineer.
- **12:** MentionContext and NormalizedSource have no run stamp. The repair report also notes that `inputSha256` covers only the preview for oversize or truncated units (Disposition.scala:19-20 says "complete offending input"). Add this to 12. Owner: hadoop-engineer.
- **13:** JobLevelRerunSuite still encodes the finding-2 gap. Owner: test-engineer.
- **14:** gzip input cannot be split, so J01 runs in a single mapper. The phantom-record mitigation in finding 9 (whole-file reads) ties in with this. Owners: hadoop-engineer / infra-engineer.

## New findings

### Blocking
None.

### Non-blocking

| # | Severity | file:line | Evidence | Owner | Required change |
|---|---|---|---|---|---|
| N1 | minor | modules/hadoop/src/main/scala/org/scholarchain/hadoop/dblp/DblpExtractJob.scala:59; JobRunner.scala:271-272 | The DTD is fingerprinted through `fingerprintFiles`, which skips base names that start with `_` or `.`. With `scholarchain.dblp.dtdPath=/x/.dblp.dtd` or `/x/_dblp.dtd`, the DTD contributes nothing, so a content change keeps the run ID. The mapper still reads that file (DblpExtractJob.scala:122-131). This brings back finding 4 for those names. | hadoop-engineer | Hash the DTD file directly, without the hidden-file filter, or fail when the DTD fingerprint has no entries. Add a test with a `_`-prefixed DTD name. |
| N2 | advisory | tests/contracts/src/test/scala/org/scholarchain/contracts/hadoop/AdversarialSupport.scala:127; XmlBoundaryAdversarialSuite.scala:201 | The contract-test `MapperModel` models J01 with the unbounded two-argument `expand` and has no `expanded_record_oversize` branch. The preview assertion tests the auxiliary-constructor cap (`RawRecord.PreviewBytes`) rather than the configured job path. These adversarial suites therefore no longer match the real mapper. | test-engineer | Switch to `expand(text, entities, maxChars)` with the configured default, add the oversize branch, and use the configured scanner constructors. After that, hadoop-engineer can remove the shims. |
| N3 | advisory | modules/hadoop/src/main/scala/org/scholarchain/hadoop/dblp/DblpEntities.scala:82-89 | The two-argument `expand` remains a public API with no bound (it calls the bounded version with `Int.MaxValue`). Its `TooLarge` branch is effectively unreachable: the StringBuilder runs out of memory first. Only tests call it. | hadoop-engineer | Deprecate it now and remove it once N2 lands. |
| N4 | advisory | modules/hadoop/src/main/scala/org/scholarchain/hadoop/common/JobRunner.scala:46-49 | The invariant only works in one direction. A downstream spec built without `fromRuns` gets `inputRuns = []`, which looks the same as a raw-input job in `_job.json`. Lineage depends on every caller using `fromRuns`, which is true for all current callers. | hadoop-engineer | Optional: give JobSpec an explicit raw-versus-upstream input kind, or add a test that pins `inputRuns` for J04, J05, J06 and J09 (DblpChainSuite covers only J07, J08a and J08b). |
| N5 | advisory (pre-existing, cosmetic) | modules/hadoop/src/main/scala/org/scholarchain/hadoop/dblp/DblpExtractJob.scala:145 | J01 decodes previews of non-Ok units as ISO-8859-1 and then cuts them to `previewChars` Latin-1 characters. For multibyte UTF-8 content, the ×4 byte allowance therefore gives `previewChars` bytes (shown as mojibake), not `previewChars` real characters. This does not break finding 8 (no fixed cap remains), but the "at least previewChars characters" wording in Settings.scala:48-53 holds only at the scanner level. | hadoop-engineer | Optional: decode with the declared charset when possible, or reword the scaladoc. |
