STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 16, session 2, step 9 repair round, repair-01-rag (docs/agent-runs/phase-16/prompt.md, sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8; decision 15). Findings P16-A-09 (major) and P16-A-10 (minor, report text only), from review-report-a2.md.
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/PathAbsenceSuite.scala (untracked, created in step 4; 3 tests added)
COMMANDS RUN (all on the list except where noted):
- `shasum -a 256 docs/agent-runs/phase-16/prompt.md` (matches the work order)
- `grep -n` / `grep -rn` / `grep -rln` with literal words over the scratchpad report, modules/rag, modules/services/src/test, tests/contracts/src/test (read-only); `head -n`, `tail -n`, `ls -l`, `ls -lT`, `git status --short`, `git diff --stat`, `git ls-files`
- Not on the list, disclosed: `cd /Users/brian/Developer/repos/scholarchain && …` as a prefix of my Bash calls (read-only effect; same pattern as P16-A-13). No `sed`, `cat`, `find`, `sort`, `uniq`.
- `sbt -batch "rag/testOnly …PathAbsenceSuite …ClaimVerificationSuite …GroundingSuite …QueryWorkflowSuite …AbstentionCodesSuite …BenchmarkRunnerSuite"` (three times: fix, temporary revert, restored)
- `sbt -batch "rag/testOnly org.scholarchain.rag.*"`
- `sbt -batch "services/testOnly org.scholarchain.services.QueryApiSuite org.scholarchain.services.ReadRoutesSuite" "contractTests/testOnly org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite org.scholarchain.contracts.runtime.LlmOutputAdversarialSuite org.scholarchain.contracts.runtime.NeptuneTimeoutStubSuite org.scholarchain.contracts.runtime.PromptInjectionCrossModuleSuite"`
TEST RESULTS:
- With the fix: rag 6 suites Passed 87/87 (PathAbsenceSuite 19, ClaimVerificationSuite 15, GroundingSuite 12, AbstentionCodesSuite 8, QueryWorkflowSuite 25, BenchmarkRunnerSuite 8).
- Temporary revert (old negation-anywhere + path-word-anywhere rule restored inside `absenceSpans`, returning the whole text as one span, which reproduces the old behavior exactly): PathAbsenceSuite Failed 3 of 19, exactly the three new P16-A-09 tests (PathAbsenceSuite.scala:161 reviewer input 1; :176 reviewer input 2, status `Verified` instead of `Conflicting`; :198). Revert removed; `grep -rn "TEMPREVERT\|return if" ClaimVerification.scala` returned no match (exit 1).
- Restored: rag 6 suites Passed 87/87; `rag/testOnly org.scholarchain.rag.*` Passed 296, Failed 0, Ignored 2; services QueryApiSuite 11 + ReadRoutesSuite 13 Passed 24/24; contractTests runtime suites Passed 23/23 (LlmOutputAdversarial 8, UnsupportedClaimEndToEnd 7, NeptuneTimeoutStub 4, PromptInjectionCrossModule 4).
VALIDATION RESULTS: L2 intended behavior unchanged: every pre-existing PathAbsenceSuite L2 test passes (correct absence vs complete search verified; deeper than searched -> AbsenceBeyondDepth; unbounded -> AbsenceNoDepth; time-limit and resource-limit searches -> AbsenceBoundedSearch; honest "unknown" claims kept). Groundedness evaluation (group 4) runs on frozen Phase 10/15 data, not rerun here (not in this work order).
ASSUMPTIONS:
- A positive hop count N against a cited complete `path=none` item whose `maxDepth >= N` (and no cited found path) is a contradiction, so it is `conflicting` with the existing reason HopsConflict (repair direction "positive-hops check against path=none"); with `maxDepth < N`, or a bounded search, it stays HopsNoPath (uncovered), as before.
- No new reason text was added (HopsConflict reused), so `Reasons.all` is unchanged.
RISKS:
- Known grounding gap (fails closed): an absence depth fronted into its own comma clause before the negation ("Within 6 hops, no path connects A and B.") lies outside the absence clause, so "6 hops" is read as a positive hop count and the claim is `conflicting` (removed, never wrongly kept). The system's own sentences (Synthesis.scala:623, Stages.scala:666) put the depth after the negation and are recognized (tested).
- Absence phrasing outside the governed patterns (for example "there exists none of the paths", "neither … nor" without a path verb) is no longer read as absence; its hop counts are then checked as positive hops, again the fail-closed direction.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: build-engineer's full `sbt check` and `packageCheck` as the round's last repairer (modules/rag changed); step 10 re-check.

## Findings

| ID | Severity | Claim | Where |
|---|---|---|---|
| P16-A-09 | major | RESOLVED. Absence is detected only when the negation governs the path phrase (`AbsencePhraseRe`: "no/without [≤2 modifiers] path/connection/route/chain", "not/never/isn't/doesn't/… [≤2 fillers] connected/linked/reachable/connect/path", "not within N hops", "not within depth N"), and only inside that phrase's clause (`AbsenceClauseSep`: `, ; :`, a sentence end after a 2+ character word, contrastive conjunctions, or "and" that opens a new clause; never the "and" between two names). Stated absence depths are taken only inside absence clauses; every hop count outside them goes through the found-path hop check, plus a new positive-hops check against complete `path=none` (HopsConflict when `maxDepth >= N`). Only in-clause hop/depth numbers are blanked from the free numbers. Reviewer input 1 ("…not a direct coauthor of Bob Jones; they are connected within 3 hops." and the "Although they never coauthored…" variant) -> `conflicting`, HopsConflict, not kept. Reviewer input 2 (found 2-hop path + complete path=none, "connected by 3 hops in 2015-2025, not within 6 hops in 2021-2023.") -> `conflicting`, HopsConflict; with "2 hops" -> `verified`. | ClaimVerification.scala:140-153 (`AbsencePhraseRe`), :154-161 (`AbsenceClauseSep`), :164-183 (`absenceSpans`, `statesAbsence`), :357-375 (absence clauses, in-clause stated depths), :381-391 (hop check outside absence clauses, positive hops vs path=none at :388), :412-416 (in-clause blanking only); scaladoc :40-46. Tests: PathAbsenceSuite.scala:145-205 (:154, :169, :186) |
| P16-A-10 | minor | RESOLVED (report correction, no code). The load-explain.csv column list in agent-report-rag.md:118 omitted columns. The real header (experiments/raw/p15-d-query-load/20261120T235521Z-ff00cfa278c8-ca0c/load-explain.csv:1, read with `head -n 1`) is: `level, question, query_id, start_epoch_s, http_status, curl_exit, time_total_s, error_code, latency_ms, planner_ms, plannerFallback_ms, resolver_ms, metrics_ms, paths_ms, community_ms, semantic_ms, evidence_ms, identity_ms, synthesis_ms, synthesisFallback_ms, verification_ms, input_tokens, output_tokens, abstained, other_stage_keys`. `start_epoch_s`, `curl_exit`, `time_total_s` and `error_code` sit between `query_id` and `latency_ms`; X2's scorer may use `error_code` and `curl_exit`. | this report; agent-report-rag.md:118 (the report text itself is not edited) |

fix status: fixed in code, tested offline, not validated live

## Prompt and model versions

No prompt, schema, model ID, limit, threshold or default changed. `prompts/**` untouched.

## Evaluation metrics

None rerun (the group 4 groundedness evaluation uses frozen data and is run by the human). Offline verifier tests only, as listed above.

## Known grounding gaps

See RISKS (fronted depth clause; ungoverned absence phrasings). Both fail closed.

## Proposed later

- An absence-clause rule for a fronted depth ("Within 6 hops, no path …"), so that a correct claim in that word order is verified instead of removed. Why later: needs a sentence-level parse, outside one repair round; current behavior is safe.

## INTERFACE HANDOFF

- Files changed: ClaimVerification.scala, PathAbsenceSuite.scala (paths above).
- Public signatures: unchanged. `ClaimVerification.verify`, `verifyClaim`, `Reasons` (and `Reasons.all`), `VerifiedClaim` unchanged. `private[runtime] def statesAbsence(text: String): Boolean` keeps its signature; new `private[runtime] def absenceSpans(text: String): List[(Int, Int)]` (package-private, not a declared interface). The private `statedDepths` helper is gone.
- Behavior change visible to consumers: claims with a negation that does not govern a path phrase are no longer treated as absence claims; a positive hop count against a cited complete `path=none` item with `maxDepth >= N` is now `conflicting` (HopsConflict) instead of `partially_supported` (HopsNoPath).
- Declared interface changed: no (no INTERFACE HANDOFF item of step 4 changed: abstention codes, the report-field table, reason texts all unchanged). No consumer re-check needed.
- Fixtures: none new (uses RuntimeFixtures W1/W1W).
- Thread-safety: pure functions over immutable inputs; compiled regexes are immutable vals.
- Limits: regex quantifiers bounded ({0,2} fillers); no new limits.

## VALIDATION EVIDENCE

- `sbt -batch "rag/testOnly <6 runtime suites>"` (restored, 22:17:26): Passed: Total 87, Failed 0. PathAbsenceSuite report timestamp 2026-11-21T22:17:36 (rerun in the wildcard run), tests=19 failures=0 errors=0.
- Temporary revert run (22:16:49): Failed: Total 19, Failed 3 (the three P16-A-09 tests); revert removed, grep confirms.
- `sbt -batch "rag/testOnly org.scholarchain.rag.*"` (22:17:38): Passed: Total 296, Failed 0, Ignored 2.
- `sbt -batch "services/testOnly QueryApiSuite ReadRoutesSuite" "contractTests/testOnly <4 runtime suites>"` (22:17:51, 22:17:55): Passed 24/24 and 23/23.
- Last source edit: ClaimVerification.scala 22:17:04 (restore), PathAbsenceSuite.scala 22:16:00; all passing runs above started after it.
files changed after these runs: none
