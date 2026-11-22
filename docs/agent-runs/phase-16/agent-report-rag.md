# agent-report-rag (Phase 16, session 2, step 4, rag-engineer)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, checked with shasum), HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4 (checked) plus uncommitted session 2 work. Scope: decision 8 items 1 to 6, all tier 1: L2; the path-abstention code for L1's class; the G-28 root-cause section; the answer-report interface table for X2; an assessment of step D's f2; decision 2 status lines for n-1, n-2, n-4, C-06, C-07, C-08, C-10, C-11, R3-04, R3-05, R3-08. Wave closer: no.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/PathAbsenceSuite.scala (16 tests)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala (L2 absence check, 3 new fixed reasons)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Abstentions.scala (new code `path-query-bounded`)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala (`pathContextCodes` follows the stop reason)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/RuntimeModel.scala (`PathAbsentFact.stoppedBy`, `stop`, `stopPhrase`)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Stages.scala (passes `GraphResult.stoppedBy`; the note names the stop reason)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/EvidenceBuilder.scala (`stoppedBy` pair on incomplete path=none items only)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Synthesis.scala (the template fallback names the stop reason)
- prompts/**: none. No prompt text, schema, limit, threshold or default changed.
COMMANDS RUN:
- sbt, one process at a time: (1) `sbt -batch "rag/testOnly org.scholarchain.rag.runtime.PathAbsenceSuite org.scholarchain.rag.runtime.AbstentionCodesSuite org.scholarchain.rag.runtime.GroundingSuite org.scholarchain.rag.runtime.QueryWorkflowSuite"`: 1 failure in my new test (a stated depth was also scored as a free number), then fixed; (2) the same command: pass; (3) `sbt -batch "rag/testOnly org.scholarchain.rag.*" "services/testOnly org.scholarchain.services.*" "contractTests/testOnly org.scholarchain.contracts.runtime.*"`: pass.
- Read-only: shasum, git rev-parse, git status --short, git diff (--stat), git ls-files, ls, ls -lT, wc, head, tail -n, cut, grep -n/-rn/-c with literal words. Read tool on every file cited.
- **Commands not on the work order's list (disclosed, all read-only, nothing written):** every Bash call began with `cd /Users/brian/Developer/repos/scholarchain &&` (`cd` is on the never list). One grep line had a no-op `sed -n 1,0p` piped in by mistake; it printed nothing. No script, no Docker, no emulator, no AWS, no aws-api, no aws-docs call, no model call, no `sbt check`.
TEST RESULTS (all observed):
- Targeted (run 2): GroundingSuite 12/12, PathAbsenceSuite 16/16, AbstentionCodesSuite 8/8, QueryWorkflowSuite 25/25; "Passed: Total 61, Failed 0, Errors 0, Passed 61".
- Every rag suite: "Passed: Total 293, Failed 0, Errors 0, Passed 293, Ignored 2" (the 2 ignored are tagged suites).
- Direct users: services.* "Passed: Total 120, Failed 0, Errors 0, Passed 120" (ReadRoutesSuite uses `AbstentionCodes`); contractTests runtime.* "Passed: Total 43, Failed 0, Errors 0, Passed 43" (NeptuneTimeoutStubSuite asserts the "whether a path exists is unknown" note). The known contractTests `.fail` tests F8 and F9 are in the er package and were not run.
VALIDATION RESULTS: no retrieval or groundedness evaluation run: the work order forbids live and model runs, and no export exists. The groundedness effect is shown by the verifier unit tests and workflow tests in PathAbsenceSuite (see "Evaluation metrics").
ASSUMPTIONS:
- A1. Decision 8.1 says "a bounded search never supports an absence claim", so it is applied strictly. An incomplete search never supports an absence claim, even "no path within depthSearched hops", which is literally true for the depths that did finish. This fails closed.
- A2. An absence claim that names no depth ("No path connects A and B") and cites a complete search is flagged (`partially_supported`, reason `AbsenceNoDepth`), not removed: the search was bounded, so the claim states more than the evidence. A depth beyond `maxDepth` is removed (`unsupported`, reason `AbsenceBeyondDepth`).
- A3. "Allowlisted details like today's `path-query-failed`" is read as a fixed short text in `AbstentionCodes.Table`. Today `path-query-failed` has no per-error detail field. A per-class detail would need a field in the stored QueryAudit, which is core (domain-engineer). Listed under "proposed later".
RISKS:
- R1. The absence-statement detector is lexical: a negation word (no, not, none, never, neither, nor, without, isn't, aren't, doesn't, don't) together with a path word, after uncertainty phrases are blanked ("did not complete", "could not be determined", "not known" and similar). It runs only when a claim cites a `path=none` item, so other claims cannot change. An honest uncertainty claim that uses another negation ("no conclusion about a path") is removed: a false removal, the safe direction. An uncertainty phrase never hides a second negation (tested).
- R2. Incomplete path=none evidence items now carry `stoppedBy=…` in their snippet. Their content hash changes. Their evidence ID does not: the subjects are unchanged (EvidenceBuilder.scala:315-321). Complete items are byte-for-byte as before (tested).
- R3. Model-facing change: on incomplete searches only, the FACTS row (built from the snippet) shows `stoppedBy`. No prompt text changed. Not validated live.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1b (code-reviewer) reviews step 4. Step 7 (service-engineer): the new code `path-query-bounded` reaches the explain view through `AbstentionCodes.reasons` (Models.scala:310) with no services change; add it to any explain contract resource that lists codes. X2 (experiment-engineer) reads the answer-report field table below.

## INTERFACE CHECK (storage step 3 and step D handoffs; read in the producer's files)

- `GraphStop`: GraphPorts.scala:227; `TimeLimit` ("time-limit") :234; `ResourceLimit` ("resource-limit") :240; `Codes` :243.
- `GraphResult.stoppedBy: Option[GraphStop] = None`: GraphPorts.scala:270. The doc at :259-262 says None on an incomplete result reads as the time limit; rag follows this (RuntimeModel.scala:387-388).
- `GraphReadError` (code, message): GraphPorts.scala:284-286. Cases: `Invalid` :291, `Transient` :296, `ResourceLimit` :302 (graph-resource-limit), `Timeout` :307, `Permanent` :312, `Unclassified` :315. `Codes` :318-325.
- `GraphReadPort.runClassified`: GraphPorts.scala:339-340 (default `run(q).left.map(GraphReadError.Unclassified(_))`). `run` is unchanged at :338.
- L1 path behavior: NeptuneAdapters.scala:348-396 (`pathSearch`).
  - A depth that ends in `Left(ResourceLimit)` returns `stopped(searched, GraphStop.ResourceLimit)` (:378-379). That is a Right with `complete = false`, `depthSearched = Some(searched)` and `stoppedBy = Some(why)` (:353-363).
  - A Timeout returns `GraphStop.TimeLimit` (:377).
  - NeptuneGraphRead overrides `runClassified` (:285).
- InMemoryGraph: `runClassified` gives Invalid only (InMemoryGraph.scala:178-179). A time stop gives `Some(GraphStop.TimeLimit)` (:284-292). A complete search with no path gives `complete = true, Some(d)` (:293-294).
- Step D (agent-report-storage-D.md): G-28 section 3 (:92-133), statement at :133; f2 at :83 and :167.
- **R-1a P16-A-08 (orchestrator note; consumer side, owner rag-engineer): not applicable to this step.**
  - Step 4 never calls `runClassified`. `grep -rn runClassified modules/rag/src modules/services/src tests/contracts/src` finds nothing.
  - L1 reaches rag only as `GraphResult.stoppedBy` on a Right, which `QueryTrace.wrap`'s `run` forwards unchanged (Stages.scala:108-114).
  - So no class is lost today. If a later change calls `runClassified` through the traced stores, the decorator must override it too. This is on "proposed later".
  - Line note: the orchestrator cited GraphPorts.scala:310-311; the default is at :339-340 in the current file. Same code, different line numbers.
Result: every name exists as handed over. No mismatch, no BLOCKED-ON.

## Items (decision 8) and fix status

| Item | Tier | Status | Where | Fix status | Test that fails without it |
|---|---|---|---|---|---|
| 8.1 L2: an absence claim citing path=none is checked against completeness and searched depth; a bounded search never supports it | 1 | done | ClaimVerification.scala:129-151 (detector, depths), :314-347 (check; hop check skipped for absence claims at :341), :367-373 (stated depths are not free numbers), reasons :91-95, :117-119 | fixed in code, tested offline, not validated live | PathAbsenceSuite "L2: a correct absence claim in hops …" (old code: HopsNoPath, partially_supported); "… stopped at the time limit never supports …" and "… resource limit (L1) never supports …" (old code: "within depth 6" verified via maxDepth 6); "deeper than the completed search"; "uncertainty phrase does not hide …"; "honest … unknown claim stays kept"; found-path hop claims unchanged |
| 8.2 path-abstention code for L1's class | 1 | done | Abstentions.scala:44-50, :80 (`path-query-bounded`); QueryWorkflow.scala:788-800 (`pathContextCodes`); RuntimeModel.scala:365-395 (`stoppedBy`, `stop`, `stopPhrase`); Stages.scala:652-654, :668; EvidenceBuilder.scala:334; Synthesis.scala:629 | fixed in code, tested offline, not validated live | PathAbsenceSuite "L1 code: …" (the code did not exist), "the context code follows the stop reason", "L1 workflow: a resource-limited path search abstains with path-query-bounded" (old code: path-query-timeout and the "time limit" note), "the evidence item records why …" |
| 8.3 G-28 root-cause section | 1 | done | below | – | – |
| 8.4 answer-report interface table | 1 | done | below | – | – |
| 8.5 targeted tests | 1 | done | TEST RESULTS | – | – |
| 8.6 INTERFACE HANDOFF | 1 | done | below | – | – |
| step D f2 (resolver `.toOption`) | – | assessed; not fixed; proposed later | below | – | – |

"Fails without the change" is argued from the old code paths named above, not observed by reverting. The one observed failure during development was in my own first version (a stated depth of 4 scored as NumberConflict). It was fixed at ClaimVerification.scala:367-373.

## G-28 root cause (decision 8.3)

What the code now records for each of 3r's causes (agent-report-rag-3r.md table; step D section 3):

| 3r # | Cause | What the code records now (Phase 15 codes plus Phase 16) |
|---|---|---|
| 1 | No path within depth 6 (complete) | PathAbsentFact(complete=true) → evidence item `found=false, complete=true, maxDepth, depthSearched`; context code `no-path-within-depth` when the answer abstains. **L2 (this step):** a correct absence claim within maxDepth is now `verified`, no longer `HopsNoPath`. This removes the route 3r named by which cause 1 could end in an abstention (`no-verified-claim`). |
| 2 | Time budget used up | `complete=false`, `stoppedBy=time-limit` in the evidence item, the note "stopped at the time limit after depth N; whether a path exists is unknown", and code `path-query-timeout`. An absence claim on it is never kept (L2). |
| 3 | Non-timeout path query failure | Store Left → `path-query-failed` (Stages.scala:660). **Storage L1:** `MemoryLimitExceededException` and `QueryLimitExceededException` on a depth are now a bounded result. rag records `stoppedBy=resource-limit`, the note "stopped at a graph store resource limit after depth N; whether a path exists is unknown", and the new code `path-query-bounded`. Other 500 classes (`FailureByQueryException`, `InternalFailure`) and transient errors still give `path-query-failed`. The audit holds no per-class detail (see A3). |
| 4 | Name not resolved | `researcher-not-resolved` (unchanged; excluded for D6). |
| 4b | Identity uncertainty | Notes only, never an abstention (unchanged). |
| 5 | Path found, claims removed | `no-verified-claim` with the path item cited (unchanged; excluded). |
| 6 | Path rows malformed | `path-result-invalid` (unchanged; excluded for D6). |
| 7 | Data drift (labels from the Phase 7 graph) | Not recorded by the runtime: no label-versus-graph check at query time. Visible only through retrieval eval's `labels-not-in-graph` (Phase 15 E). |

What the frozen evidence supports:
- **D6 recorded `path-query-failed`** (phase-15 runbook-log.md row 55, per step D E1). That excludes causes 1, 2, 4, 5 and 6 for the D6 re-run (step D E3).
- **The FECD Neptune error at 23:51** (D-query metrics `m33_0`, 1 server error, and `m30_0`, 7 requests, in the only Neptune traffic between 23:50 and 23:54; step D E2). It shows that one Neptune-counted server error happened in D6's run. So cause 3 is narrowed to a server-side 500 class, at concurrency about 1 (step D E4), not FECD-1's concurrency mechanism.
- The paths stage took 4,868 ms, against 233 to 460 ms for the D-query path rows (step D row 3-sub). That suggests a deep, heavy variable-length query, which fits a memory limit. The class itself was dropped by the code at the time (step D f1).
- **Cause 7, data drift, is still the likely underlying reason:** the benchmark's 2-hop path comes from the Phase 7 graph (benchmark-v1.json:8; 3r R2). The search went past depth 1 in the Phase 14 graph, which drives it to the heavier depths.
- **Does L1 plus this step change q13's outcome?**
  - If the class was a resource limit, a re-run would now record `path-query-bounded` and an honest "unknown" note instead of `path-query-failed`. It would still give no hop count, and any model absence claim would be removed (L2). So L1 changes the recorded reason and evidence, not the substantive answer.
  - If the class was `FailureByQueryException` or `InternalFailure`, nothing changes.
  - Not validated live: no environment exists.

**Final statement: cause narrowed to:** in the D6 run, one Neptune-counted server-side failure (HTTP 500 class) of a single path-search query at concurrency about 1. It is most consistent with a resource limit on a deeper variable-length path query, reached because the benchmark's expected 2-hop path (Phase 7 graph) is likely absent from the Phase 14 window graph (cause 7). Causes 1, 2, 4, 5 and 6 are excluded for D6; FECD-1's concurrency mechanism is unlikely. The exact Neptune error class is not decidable from the frozen evidence. A future live run is decidable: storage records the class (`GraphReadError`, `GraphStop`), and rag records `path-query-bounded` against `path-query-failed`.

## Answer-report interface (decision 8.4, for X2's `answers` scorer)

Sources (sha256 by `shasum -a 256`):
- A = docs/agent-runs/phase-10/run-report-attempt-06-anthropic.json (41cf57e6af1f4266e2faad77401b9f4a9228e844537073ea8a179bd25708f5b2). Code `rag-runtime/2` (:288), prompts `query-planner-v2`/`answer-synthesis-v2` (:277-280), model claude-haiku-5-5 (:286), local stores built from the Phase 7 export (manifest :44), fake embeddings (:4).
- D = experiments/raw/p15-d-query-load/20261120T235521Z-ff00cfa278c8-ca0c/load-explain.csv (53273fe7d01893e1b138d3712d69b15bccd7c0d98ec87cb442e1ce0759cf1e6c). Header at :1, 40 data rows (:2-:41). Phase 15 code, Phase 14 graph run.
- C = docs/agent-runs/phase-15/dynamodb-sample/run-ledger-clip01-query.json (f818418e8c75e0e2c76e3fe698e34701db9f3d0566e1d6339ebd4408edf4bb97). 4 items: 2 attempts, each with a `claim` marker and a `query-audit` record. QueryAudit v3 JSON strings at :64 (attempt 1) and :131 (attempt 2). Prompts `query-planner-v2`/`answer-synthesis-v3`, model claude-haiku-5-5, graph run run:v1:f2625d504b12749389cf4f5b1a26aed4 (inside :64).

| Field the scorer wants | A attempt-06 | D D-query explains | C clip 01 audit |
|---|---|---|---|
| Granularity | **aggregated only**. Per question only the step duration `question-qNN` (:8-37) | per query (one row per explain read) | per attempt (2 attempts of 1 question) |
| Claims (texts) | not available | not available | not available (only `answerHash`, :33-34 and inside :64) |
| Each claim's verification status | not available per claim. Aggregate `results.answers.claimCounts` (:384-389): verified 111, partially_supported 19, unsupported 1, conflicting 5 (sum 136) | not available (no verification-count column in the header :1; the CSV writer load-tests/query-load.sh:960 writes none). Only `verification_ms` | per answer, not per claim: `verificationCounts` in the audit (:64 attempt 1: verified 2, partially_supported 0, unsupported 1, conflicting 0; :131 attempt 2: verified 2, others 0) |
| Evidence IDs | not available | not available | `evidenceIds` (3 IDs) and `bundleId` (inside :64 and :131) |
| Numbers and identifiers of claims | not available | not available | not available (only `resolvedResearcherIds`, `plan.steps[].researcherIds`, `queryTemplateIds`) |
| Abstained flag | not per question in the file. **Derivable**: `abstentionsExpected` 3 and `expectedAbstentionsHappened` 3 (:390-391), `unexpectedAbstentions` [] and `missedAbstentions` [] (:392-393), `workflowErrors` 0 (:292). By BenchmarkRunner.scala:810-826, every question's abstained flag therefore equals benchmark-v1's `abstain` label (true only for q27 :5443, q28 :5462, q29 :5556) | `abstained` column (header :1). 0 of 40 rows true (grep count of `,true,` = 0) | `abstained` false (:48-49, :115-116, and inside the records) |
| Abstention codes | not available (codes began in Phase 15; this is rag-runtime/2) | not available (no column) | `abstentionCodes` [] in both records (end of :64 and :131): empty because not abstained |
| Answer-key values | aggregated only: `deterministicGraphRetrieval` expectedValues 110, exactMatches 110, `misses` [] (:323-327). Per path, without question IDs: `pathsAgainstSparkEvidence.details` (:328-361; 4 entries, e.g. :333-339 expectedHops 5, gotHops 5, matched). **These compare the answer key with the evidence bundle's items, not with the claims** (BenchmarkRunner.scala:425-474: "not in the evidence bundle"). So they are retrieval agreement, not claim accuracy | not available | not available |
| Answer texts | not available | not available | not available (hash only) |
| Planted-error catch (verifier test, internal) | aggregated `plantedErrors` (:394-436): 85/86 caught; wrong-window-year 22/23 (1 `verified`, :427-433) | – | – |
| Other per-row fields | – | `level, question, query_id, http_status, latency_ms, planner_ms … verification_ms, input_tokens, output_tokens, abstained, other_stage_keys` (:1). Example :2: q05, 200, latency 9645, synthesis 7268, abstained false | `planSource` llm, `stageMillis`, tokens, `promptVersions`, `modelId`, `latencyMillis`. FECD-3: explain shows the latest attempt only; C holds both attempts |

Example (A, aggregated claim statuses, :384-389): `"claimCounts": {"verified": 111, "partially_supported": 19, "unsupported": 1, "conflicting": 5}`. This gives a verifier-reported support rate (verified ÷ all claims) of 111/136 and a conflicting rate of 5/136: kind (a), pooled over 30 answers. No per-answer values exist, so no bootstrap over answers: `no dependence-aware interval`.

**What the scorer reports `not available`:**
- per-claim status, texts, numbers and IDs for all three sources;
- per-claim answer-key agreement for A;
- any claim-type breakdown (coauthor-edge, graph-path, supporting-publication, identifier, number) for all three sources (no claim or evidence type is stored per claim);
- abstention codes for A and D;
- verification counts for D.

**"Contradicted" status: yes, named `conflicting`.**
- core Enums.scala:327-328 (`case object Conflicting … "conflicting"`, "A number or name contradicts its cited evidence").
- Enums.scala:311-312: renamed from `contradicted` in Phase 9.
- The verifier sets it at ClaimVerification.scala:475 (`if (cs.nonEmpty) VerificationStatus.Conflicting`); rule text in its doc at :53-54.
- In A it appears as `claimCounts.conflicting` (:388); in C as `verificationCounts.conflicting`.

## Step D f2 assessment (resolver `.toOption`)

- Code: ResearcherResolver.scala:186-208 (`context`). A failed `ResearcherById` gives no node, so `identityOutcome` is "unknown" and `minIdentityConfidence` is absent. A failed `CoauthorNeighbors` gives `top` = Nil. Same pattern in `IdentityCheck.run` (:279-286) and `CommunityRanking.displayName` (Stages.scala:450-456).
- **Is it a defect?** Partly. A store failure is presented as identity outcome "unknown": the resolver note says "Identity outcome is unknown, so facts about this researcher are uncertain" (:118), and claims are flagged uncertain. Top coauthors are dropped silently. It fails toward uncertainty, never toward a made-up fact, and the answer flags the researcher. What is lost is the observability of the graph failure, which is what made step D's attribution of the 23:51 error undecidable (D section 3, row 3 caveat).
- **Scope:** it is not part of L1 (path search), L2 (verifier) or the G-28 code path (path stage). A fix changes the resolver's notes and IdentityFact contents for every graph error on every name-resolved question, which is wider than decision 8. **Not fixed; proposed later** (below). No test was written for it.

## Decision 2 status lines (rag-engineer; checked against the working tree after this step)

- n-1: still present (Synthesis.scala:380-386 `sanitize` relies on RulePlanner.nameRuns, which needs at least 2 capitalized tokens (RulePlanner.scala:207-208), single-space adjacency (:218) and excludes all-caps tokens (:220); a one-token or all-caps name and spelled-out numbers pass; still shown under the "not verified" SummaryLabel, Synthesis.scala:317, QueryWorkflow.scala:405)
- n-2: still present (ClaimVerification.scala:305-311: an uncited dblp-key-shaped slash token is blanked by `DblpKeyRe.matches`, so a number inside it is not checked)
- n-4: still present (QueryWorkflow.scala:208 passes `resolution.uncertain.keySet`, which covers resolved researchers only (ResearcherResolver.scala:79-83); a conflict-flagged ambiguous candidate is described in the resolver note and listed again by IdentityCheck.notes, ResearcherResolver.scala:307-309; redundant only)
- C-06: still present (EvidenceBuilder.scala:242 metric item subjects omit rank; :124-127 the first item per evidence ID wins; GroundingSuite.scala:36 documents the shared ID)
- C-07: still present (Stages.scala:382-384, :439-445 and the semantic path at :975: `droppedIncompatibleModel` and `droppedMalformedMetadata` are not noted in the answer; they are counted only in the retrieval eval, RetrievalModes.scala:129-130, RetrievalEval.scala:202-203)
- C-08: still present (EvidenceBuilder.scala:92-93 `FactRow.render` and Synthesis.scala:171 `"FACTS" -> g.map(_.render)`: FACTS rows are not passed through `Prompts.asData`; only QUESTION, notes and semantic excerpts are, Synthesis.scala:168-175)
- C-10: still present (Schemas.scala:191 bounds `researcherNames` only; QueryWorkflow.scala:184 resolves them with no check that each name occurs in the question)
- C-11: still present (Stages.scala:591, :596-615: a precomputed Spark path is used whatever `s.maxDepth` is, with no note; only the graph search at :617 uses maxDepth)
- R3-04: still present (QueryWorkflow.scala:600-617 puts the model's `unsupportedConstraints[].text` into notes and abstention texts; Synthesis.scala:80 puts the validation error text (it can hold a rejected model number, e.g. `limit: 999`) into the fallback reason, shown at QueryWorkflow.scala:369-372)
- R3-05: still present (RuntimeService.scala:386-387: caller-supplied `options.clients` join `built` only after the LLM, embedder and prompt steps; a Left before that (e.g. :350 fake LLM refused, :376 prompts) runs `cleanup()` (:409) without them, contrary to the doc at :323-325)
- R3-08: still present (NumberFormats.scala:76-87 `withoutNonNumbers` does not blank a venue "(n)", while the verifier does for cited venues, ClaimVerification.scala:298-303)

## Proposed later (one line each)

- f2: record an allowlisted "graph context unavailable" note or counter when a resolver, IdentityCheck or displayName graph read fails, instead of folding it into identity outcome "unknown" (ResearcherResolver.scala:186-208, :279-286; Stages.scala:450-456). This makes graph failures in name-resolved answers attributable.
- P16-A-08: if rag ever calls `runClassified`, make `QueryTrace.wrap` (Stages.scala:108-114) forward it, with a test. Not needed now: there are no callers.
- Per-class detail for `path-query-failed` (transient, permanent, unclassified): needs a QueryAudit field (core, domain-engineer) or more allowlisted codes. It would make the next G-28-type failure attributable from the audit alone.

## Runtime lines

- ClaimVerification absence check, `path-query-bounded`, stoppedBy recording (QueryWorkflow and RuntimeService paths): runtime: untested (unit- and workflow-tested offline in Test with local stores; no live run).

## Prompt and model versions

- No prompt or schema changed. Prompt sets and model IDs are unchanged (`query-planner-v2`, `answer-synthesis-v3` as in C; model IDs come from configuration). `QueryWorkflow.CodeVersion` stays `rag-runtime/3`: the run-ID inputs (question, plan, prompts, configuration, graph run) are unchanged. The verifier and evidence changes alter answers only for path=none claims and incomplete searches.

## Evaluation metrics

- Groundedness, offline: PathAbsenceSuite. A correct complete-search absence claim is now `verified` (before: `partially_supported` with HopsNoPath). Absence claims on time-limited or resource-limited searches are now never kept (before: `verified` when the stated depth equalled maxDepth). The adversarial mixed phrase is removed; honest "unknown" claims stay `verified`. Found-path hop checks are unchanged. Planted-error and benchmark rates were not re-run: no model call, no export.

## Known grounding gaps

- The absence detector is lexical (R1).
- Absence claims citing no path=none item are not covered: they fall to the existing checks.
- C-11 (precomputed path ignores requested depth) and C-08 (FACTS rows not data-marked) remain open, as listed.

## INTERFACE HANDOFF

Files changed: the 7 main files and 1 test file listed above (all modules/rag; nothing in prompts/).

Codes (for service-engineer's explain contract resources and X2):
- **New abstention code `path-query-bounded`** (`AbstentionCodes.PathQueryBounded`, Abstentions.scala:50). Fixed text: "the path search stopped at a graph store resource limit; whether a path exists is unknown" (:80).
  - It is a context code, added when an abstained answer's shortest-path step stopped at `GraphStop.ResourceLimit`.
  - Valid for QueryAudit v3 (`QueryAudit.isValidAbstentionCode`, tested). It is included in `AbstentionCodes.All`, `Table` and `reasons`, so the explain view's `abstentionReasons` (services Models.scala:310) shows it with no services code change.
  - Order: `forAudit` keeps the first 8 distinct codes; `path-query-bounded` sits right after `path-query-timeout` in `All`.
- Unchanged codes: `path-query-failed` (store Left: transient, permanent, unclassified, single-shot errors), `path-query-timeout` (GraphStop.TimeLimit, or None on an incomplete result), `no-path-within-depth` (complete search).

Public signatures:
- `PathAbsentFact(…, priority: Int, stoppedBy: Option[GraphStop] = None)` (RuntimeModel.scala:368-395), with `def stop: Option[GraphStop]` (None when complete) and `def stopPhrase: String`. Existing 9-argument calls compile.
- `QueryWorkflow.pathContextCodes(facts: List[Fact]): List[String]`: signature unchanged, behavior extended.
- `ClaimVerification.Reasons.AbsenceBoundedSearch`, `AbsenceBeyondDepth`, `AbsenceNoDepth` (fixed text, no digits, in `Reasons.all`); `ClaimVerification.statesAbsence(text)` is `private[runtime]`.
- Evidence: an incomplete `graph_path` `path=none` item carries the snippet pair `stoppedBy=time-limit|resource-limit` (EvidenceBuilder.scala:334); complete items are unchanged.

Report-field table: section "Answer-report interface" above (authoritative for X2's `answers` subcommand).

Fixtures: none on disk. Tests use RuntimeFixtures' local world, stub graph ports returning `GraphResult(…, complete = false, Some(2), stoppedBy)`, and SplitModel drafts.

Thread-safety: all changes are pure functions or immutable data; no new shared state.

Limits: no limit, threshold, default, prompt or bound changed. The absence check runs regexes over one claim's text, which is bounded by the claims schema.

Fix status (every code change of this step): fixed in code, tested offline, not validated live.

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-16/prompt.md` → c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8; `git rev-parse HEAD` → 9da45ab616a764e449f74f25feca85ed77d6dfe4.
- `sbt -batch "rag/testOnly org.scholarchain.rag.runtime.PathAbsenceSuite org.scholarchain.rag.runtime.AbstentionCodesSuite org.scholarchain.rag.runtime.GroundingSuite org.scholarchain.rag.runtime.QueryWorkflowSuite"` (second run) → "Passed: Total 61, Failed 0, Errors 0, Passed 61".
- `sbt -batch "rag/testOnly org.scholarchain.rag.*" "services/testOnly org.scholarchain.services.*" "contractTests/testOnly org.scholarchain.contracts.runtime.*"` → rag "Passed: Total 293, Failed 0, Errors 0, Passed 293, Ignored 2"; services "Passed: Total 120, Failed 0, Errors 0, Passed 120"; contracts "Passed: Total 43, Failed 0, Errors 0, Passed 43".
- `ls -lT`: newest source change ClaimVerification.scala 20:43:15. Test reports are newer: TEST-…PathAbsenceSuite.xml 20:43:44, TEST-…ReadRoutesSuite.xml 20:43:51, TEST-…NeptuneTimeoutStubSuite.xml 20:43:56.
- `git status --short modules/rag prompts` → 7 modified files under modules/rag/src/main and 1 new test file; nothing in prompts/. No file outside my territory touched (no `sbt fmt` run; the format hook formatted only my files).
files changed after these runs: none
