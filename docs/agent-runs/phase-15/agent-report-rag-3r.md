STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified with shasum), step 3r (read-only): decision 7.5 G-28 diagnosis, decision 7.4 E design with counts, decision 11 explain stage fields. HEAD 2cbfbc72c704c40085e2876479d510b90062a12a (verified with git rev-parse).
FILES CREATED: none in the repository. Only this report (scratchpad).
FILES MODIFIED: none.
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-15/prompt.md; git rev-parse HEAD; ls of docs/agent-runs/phase-14, phase-15, modules/rag sources, evidence/runs (D14 and D15 records only); wc -l.
- grep -n (literal words) over the phase-14 docs, modules/rag, modules/storage, modules/services, config/course.json, scripts/smoke-test.sh, infra/terraform/modules (*.tf only), benchmark-v1.json; head -n / tail -n / cut on benchmark-v1.json.
- Read tool on the files cited below; aws-docs (3 calls: Neptune parameters search, openCypher compliance page, ExecuteOpenCypherQuery errors page).
- mkdir -p of the scratchpad report folder (outside the repository; it already existed).
- Rule slips, disclosed: two Bash lines contained a no-op `sed -n ... /dev/null`, one pipeline contained `tr -s` (it failed before running anything useful: `head -0` error), and one pipeline used `uniq -c`; all read-only, nothing written. These four commands are on the work order's "never" list; I note them so the orchestrator can record it. No sbt, no scripts, no bash/python scripts, no AWS CLI, no aws-api.
TEST RESULTS: none run (read-only step; sbt forbidden for 3r).
VALIDATION RESULTS: diagnosis is from code with file:line and from the D15 record; the live checks it needs are listed under G-28 DIAGNOSIS "Read-only checks that decide it". Counts in E DESIGN were taken from benchmark-v1.json with grep line numbers (listed).
ASSUMPTIONS:
- A1. The explain read recorded in phase-14 runbook-log.md row D15 lists the complete `queryTemplateIds` (researcher_by_id, coauthor_neighbors, path_between_depth_6). The exclusions below of "path found" depend on it.
- A2. The smoke record's started_utc/ended_utc (evidence/runs/20261120T150727Z-b39e1ed5b785-67d3-smoke-test.json:10-11, 17 s for all 7 checks) bound the q13 query's duration.
- A3. "Target researcher ID" for E is read as an answer target (a researcher the expected answer must name that the question does not name). The alternative reading and its counts are given; the orchestrator picks.
RISKS:
- R1. Interface: the stored audit has no field for a reason. QueryAudit is a core record (modules/core/src/main/scala/org/scholarchain/core/Records.scala:712-740, owner domain-engineer); its only related field is `abstained: Boolean` (:732). Decision 7.5 "reason in the stored audit" therefore needs a core change before or with step 3 (see G-28 DIAGNOSIS, "Where to record"). Step 1 is already COMPLETE without it. interface: yes (routing needed: repair-00-domain-engineer, or an explicit decision to keep the reason out of the audit).
- R2. Benchmark labels come from source run run:v1:51b50d578d5b609842c1c21b8266a00b (benchmark-v1.json:8), not from the Phase 14 graph run run:v1:f2625d504b12749389cf4f5b1a26aed4 (new DBLP snapshot, er-v2). Researcher IDs derive from the cluster's anchor mention (modules/core/src/main/scala/org/scholarchain/core/StableIds.scala:90-91), so some label IDs may be absent or renamed in the loaded graph, and rankings may differ. This affects both q13 (a 2-hop path in the Phase 7 graph is not proof of one in the Phase 14 graph) and every E score.
- R3. Graph-only E scores are partly circular: the labels were generated from the same kind of export tables (rankings, coauthor edges) that graph-only reads (benchmark-v1.json:5, :11-17).
MANUAL ACTION REQUIRED: none for this step. Optional, human, read-only and no LLM call: the two GET reads under G-28 DIAGNOSIS "Read-only checks that decide it" (runtime is on; key through a curl config on standard input as in D15).
BLOCKED-ON OWNER: none (R1 is a routing need for the orchestrator, not a block of 3r).
BLOCKED-ON EVIDENCE: none.
NEXT GATE: orchestrator: decide R1 (route the QueryAudit field to domain-engineer before step 3, or not) and the E universe reading (A3); then step 3 (rag-engineer) implements decision 7.4/7.5 on this design.

## G-28 DIAGNOSIS

Observed (phase-14 runbook-log.md row D15; evidence/runs/20261120T150727Z-b39e1ed5b785-67d3-smoke-test.json): query 200, abstained true, 3 claims, 2 evidence links (links = distinct evidence IDs cited by claims, modules/services/src/main/scala/org/scholarchain/services/QueryService.scala:84-91), first link type match_decision; explain templates researcher_by_id, coauthor_neighbors, path_between_depth_6; whole smoke run 15:07:27 to 15:07:44 UTC (17 s for 7 checks).

How q13 runs: rule or LLM plan `shortest_path` (RulePlanner.scala:125); depth `d.maxPathDepth.getOrElse(runtime.maxPathDepth)` = 6 (QueryWorkflow.scala:587; config/course.json:87); step executable only with exactly 2 resolved researchers and no unresolved name (QueryWorkflow.scala:610); PathFinder.shortestPath: precomputed Spark path via the path vector key (Stages.scala:505-528, 535), else `GraphQuery.PathBetween` (Stages.scala:561); storage compiles depths 1..6 (NeptuneModel.scala:142-151, template text :66-71, window label as a literal constant, which Neptune VLP requires: "only supporting constant values for property equality filters", aws-docs feature-opencypher-compliance.html) and runs iterative deepening with a 20,000 ms total budget (NeptuneAdapters.scala:174-214; GraphPorts.scala:101-108; config/course.json:89). Abstention rule: `abstained = no kept claim citing a non-match_decision item || abstainAll non-empty` (QueryWorkflow.scala:370-374).

Causes the code can produce, each with its code path and what the D15 facts say:

| # | Cause | Code path | Evidence it leaves | Verdict from D15 |
|---|---|---|---|---|
| 1 | No path within depth 6 (search complete) | NeptuneAdapters.scala:184-185 (`complete = true`, all depths empty) -> Stages.scala:580-598 `PathAbsentFact(complete=true)` -> :604-607 note, no abstention -> EvidenceBuilder.scala:311-336 graph_path item `path=none` -> Synthesis.scala:620-626 claim. Abstains only if that claim is not kept (ClaimVerification.scala:21-22, :412-420) | bundle = 2 match_decision + 1 graph_path(found=false, complete=true); `stageMillis.paths` well under 20,000 | POSSIBLE. Needs the path claim removed as unsupported (it would then cite nothing, QueryWorkflow.scala:325) so the 2 links are both match_decision. |
| 2 | Path query bounded: time budget used up | NeptuneAdapters.scala:187-188 (remaining <= 0) or :193/:197 (ApiCallTimeout, ApiCallAttemptTimeout, Neptune TimeLimitExceeded; isTimeout :221-227) -> `complete = false` -> Stages.scala:608-609 note | graph_path(found=false, complete=false, depthSearched); `stageMillis.paths` >= 20,000 | EXCLUDED: needs >= 20 s inside the path stage, but the whole smoke run took 17 s. Neptune's own limit is 120,000 ms by default (aws-docs neptune/latest/userguide/parameters.html, `neptune_query_timeout`); not set in infra/terraform/modules (*.tf grep; environments/course not searched by rule). |
| 3 | Path query failed with a non-timeout error (Neptune MemoryLimitExceededException 500, QueryLimitExceededException 500, QueryLimitException 400, MalformedQuery, throttling, I/O) | NeptuneAdapters.scala:194, :198 -> Left -> Stages.scala:603 abstention `StageFailures.PathSearch` "the path search failed (graph store failure)" (Stages.scala:59) -> QueryWorkflow.scala:213, :379 | bundle = only the 2 match_decision items; reason text names "graph store failure"; error class discarded by design (C-02) | MOST CONSISTENT with D15: exactly 2 cited items, first match_decision, no path item to cite. Not proven. |
| 4 | Identity: a name not resolved to exactly one researcher | ResearcherResolver.scala:147-184 -> QueryWorkflow.scala:602-622 step not executable, abstention "shortest_path was not run because a researcher name could not be resolved..." (:615-617) | path template never runs | EXCLUDED: path_between_depth_6 ran, which needs both names resolved (QueryWorkflow.scala:610). |
| 4b | Identity uncertainty (outcome not `accepted`) | ResearcherResolver.scala:78-83, :264-297; QueryWorkflow.scala:284-297 | only notes and per-claim uncertainty; never an abstention | NOT A CAUSE in the code. The match_decision link is the normal per-name IdentityFact (ResearcherResolver.scala:103-104 -> EvidenceBuilder.scala:353-378), present in every name-resolved answer; it is not evidence of identity trouble. coauthor_neighbors came from the resolver's candidate context (ResearcherResolver.scala:193-208), not from the path. |
| 5 | Path found, claims removed by verification | Stages.scala:611-627 then edge per hop `CoauthorEdgeBetween` (Stages.scala:615-618, :466-467) | template `coauthor_edge_between` in queryTemplateIds | EXCLUDED (A1): coauthor_edge_between is absent from the D15 templates (template IDs GraphPorts.scala:128-156). |
| 6 | Path rows returned but malformed or out of order | Stages.scala:612-613 "the stored path does not connect the two researchers" | no edge lookups, bundle = 2 items | UNLIKELY (Neptune `id(n)` values are the researcher IDs that ResearcherById matched), not excluded. |
| 7 | Other: data drift | benchmark label from the Phase 7 source run (benchmark-v1.json:8, :687-690); Phase 14 graph = new snapshot and er-v2 | depth 1 and 2 empty in the Phase 14 graph | LIKELY UNDERLYING CAUSE of 1 or 3: causes 1 and 3 both need depth 2 to return no row, which means the 2-hop path through a shared neighbour is not in the Phase 14 window graph (different clusters, IDs or edges), or the depth-2 call itself failed. |

Code defect provable by a test without the live graph: none proven to be q13's cause. So, per decision 7.5, no fix in step 3 beyond recording the reason; session 3 (D6) reads it. Two latent issues found, both testable offline, both reported not fixed:
- L1 (owner storage-engineer, NeptuneAdapters.scala:193-194, :221-227): Neptune resource-limit errors documented for ExecuteOpenCypherQuery (MemoryLimitExceededException, QueryLimitExceededException; aws-docs neptune/latest/data-api/API_ExecuteOpenCypherQuery.html "Errors") are not treated as a bound, so the answer says "graph store failure" instead of "whether a path exists is unknown". A fake client throwing either shows it. Changing it changes answer behavior; proposed later (Phase 16) unless D6 shows `path-query-failed` with that class.
- L2 (owner rag-engineer, ClaimVerification.scala:282-290): a hop phrase such as "within 6 hops" is checked only against `hops` fields of graph_path items, and a `path=none` item has none (EvidenceBuilder.scala:324-334), so a correct absence claim citing it gets HopsNoPath; with nothing else checked it becomes unsupported. This is how cause 1 could end in an abstention. Proposed later unless D6 shows `no-path-within-depth` together with `no-verified-claim`.

Read-only checks that decide it now (human, no LLM call, no code; query ID from the D15 record):
- GET /v1/explain/run:v1:ec7dc746c609f930c22a3a0dc3972500 filtered by jq to `.evidenceIds|length`, `.resolvedResearcherIds`, `.stageMillis.paths`, `.latencyMillis`, `.verificationCounts`, `.planSource`. 2 items -> cause 3 or 6; 3 items -> cause 1; resolvedResearcherIds unequal to benchmark-v1.json:682-683 -> identity drift (cause 7). Fields exist today: Models.scala:233-260, ReadServices.scala:84-95. Audit retention for that ID: unverified.
- GET /v1/evidence/ID for a graph_path item, if one is listed: found, complete, depthSearched. For the match_decision item evidence:v1:9ce97848a46b05fb17d43390392ab115: whether topCoauthorIds are present (EvidenceBuilder.scala:370-375), which shows that window edges exist for that researcher.
- The POST /v1/query body already carried `abstentionReason` prose (QueryWorkflow.scala:375-386, :437-438; Models.scala:63), but smoke-test.sh keeps only counts (scripts/smoke-test.sh:480-488), and a sync result cannot be read again by query ID (Routes.scala:32-70). Proposed later (infra-engineer): the smoke record keeps the reason code.

Where to record the reason:
- Answer: exists today (`AnswerResponse.abstentionReason`, prose from fixed texts plus names from evidence). Keep it.
- Stored audit: needs a core field (R1). Proposal for domain-engineer: QueryAudit schema version 3, additive like v2 (Records.scala:686-698, :743-745): `abstentionCodes: List[String]`, distinct, each matching `QueryAudit.NamePattern` (Records.scala:748), empty when `abstained` is false, non-empty when it is true. Codes only, no text, which keeps the audit's "fixed vocabularies and counts only, never text" rule (QueryWorkflow.scala:497).
- rag: an `AbstentionCode` allowlist with one fixed short text per code and no names. Every abstention site emits a code next to its existing text: StageOutput's abstentions become (code, text) or gain a parallel code list; `WorkflowResult` gains `abstentionCodes`.
- Explain (service-engineer, decision 8.2): `abstentionReasons: [{code, text}]` built from the audit codes and rag's table; empty list when not abstained. Field names are agreed in step 3's INTERFACE HANDOFF.
- Fail closed: an abstention without a code gets `unclassified` and a test asserts that no reachable site produces it.

Proposed fixed code set (each code -> source site):

| Code | Site |
|---|---|
| unsupported-venue-filter | QueryWorkflow.scala:574-575 |
| unsupported-institution-filter | QueryWorkflow.scala:576-577 |
| window-not-configured | QueryWorkflow.scala:578-579 |
| researcher-not-resolved | QueryWorkflow.scala:615-617 (ambiguous, unknown or failed lookup; ResearcherResolver.scala:121-142) |
| path-needs-two-researchers | QueryWorkflow.scala:618-619; Stages.scala:630 |
| researcher-name-missing | QueryWorkflow.scala:620 |
| window-not-loaded | Stages.scala:426-435 |
| path-same-researcher | Stages.scala:531-532 |
| path-query-failed | Stages.scala:603 (optional allowlisted detail from the storage error's leading class name: memory-limit, query-limit, throttled, malformed-query, access-denied, other; never the message) |
| path-result-invalid | Stages.scala:612-613 |
| no-path-within-depth | context code, added when the answer abstains and a shortest_path step produced PathAbsentFact(complete=true) (Stages.scala:604-607) |
| path-query-timeout | context code, the same with complete=false (Stages.scala:608-609) |
| edge-lookup-failed | Stages.scala:636 |
| neighbor-lookup-failed | Stages.scala:664 |
| collaboration-needs-researchers | Stages.scala:692 |
| metrics-read-failed | Stages.scala:199, :275, :302, :727, :762 |
| metrics-missing | Stages.scala:200 |
| topic-candidates-failed | Stages.scala:340 |
| topic-metrics-failed | Stages.scala:384 |
| semantic-needs-topic-or-name | Stages.scala:915 |
| no-verified-claim | QueryWorkflow.scala:380-381 |
| no-substantive-evidence | QueryWorkflow.scala:383 (also: only match_decision claims kept) |
| unclassified | fallback, should never appear |

`identity-uncertain` (the work order's example) is not produced: no code path abstains on it (row 4b). `researcher-not-resolved` is the identity abstention.

D6 note: the q13 re-run likely gets the same query run ID if CodeVersion `rag-runtime/3` (QueryWorkflow.scala:728), prompts and config fingerprint are unchanged. Explain then shows the latest audit (ReadServices.scala:93-95, attemptCount 2), and a different bundle content gives a store-conflict note, not a failure (QueryWorkflow.scala:349-353).

## E DESIGN (with counts)

benchmark-v1 has 30 questions (q01-q30; `grep -c '"id": "q'` = 30) plus a separate `semanticQueries` list of 164 key-lookup queries (benchmark-v1.json:5660; 50 researcher_profile, 50 community_summary, 64 collaboration_path). The `semanticQueries` list is not questions and stays outside the universe, which is never widened. Graded labels: none. There is no grade or relevance field (grep), so nDCG is not computed. Deriving grades from rank or metric values would be adding labels.

Unit: canonical researcher ID. Group IDs (`groupId`, which are researcher-ID shaped, benchmark-v1.json:713 etc.) and ambiguous `candidateIds` (:5464-5542) are never targets.

Recommended reading (A3: answer targets, the researchers the expected answer must name beyond those named in the question):

| Q | Category | Targets (count) | Source lines | By-design-weak |
|---|---|---|---|---|
| q01 | ranking distinct_coauthors | 10 ranking IDs | :33-105, limit :113-114 | vector-only |
| q02 | ranking | 5 | :131-163, :171-172 | vector-only |
| q03 | ranking | 10 | :189-261, :269-270 | vector-only |
| q07 | direct collaborators | 10 edge `b` IDs | :385-457 | vector-only (suggested) |
| q08 | direct collaborators | 10 edge `b` IDs | :483-555 | vector-only (suggested) |
| q19 | bridge ranking | 10 | :880-952, :960-961 | vector-only |
| q20 | bridge ranking | 5 | :978-1010, :1018-1019 | vector-only |
| q21 | bridge ranking | 10 | :1036-1108 | vector-only |
| q25 | topic ranking (WSDM) | 10 = top `limit` of the labelled pool (poolSize 1045) by the benchmark's own rule (:13, :1282, :2332-2334) | | graph-only (its topic filter uses semantic candidates, Stages.scala:339) |
| q26 | topic ranking (SIGIR) | 10 = top `limit` of pool 3066 (:2356, :5427-5429) | | graph-only |
| q30 | ranking (with injection) | 10 | :5574-5646, :5654-5655 | vector-only |

- In the universe: 11 questions, 100 labels (10,5,10,10,10,10,5,10,10,10,10). Fewer than 15, so E is labelled **exploratory** in every table.
- q25 and q26: the top-`limit` derivation applies the benchmark's stated rule to its labelled pool and adds no label. Alternative: the whole pool counts as relevant (recall@30 at most 30/1045 and 30/3066). Orchestrator picks; my recommendation is top-limit.
- not-comparable: 19 questions. q04, q05, q06 (number and work); q09 (yes/no edge between the two named subjects); q10, q11 (path labels; alternative: count the 4 and 1 intermediate IDs, giving 13 questions, still exploratory); q12 (no-path number); q13 (hop count only, no intermediate named, :687-690); q14, q15 (component size); q16, q17, q18 (community numbers); q22, q23, q24 (temporal numbers); q27 (institution, abstain, no ID); q28 (ambiguous, abstain, 76 candidates); q29 (unknown, abstain).
- Alternative reading (any researcher ID labelled, named subjects included): 27 in the universe (q01-q26, q30), not-comparable 3 (q27, q28, q29). Labels per question: q01 10, q02 5, q03 10, q04 1, q05 1, q06 1, q07 11, q08 11, q09 2, q10 6, q11 3, q12 2, q13 2, q14 1, q15 1, q16 1, q17 1, q18 1, q19 10, q20 5, q21 10, q22 1, q23 1, q24 1, q25 10, q26 10, q30 10. Not exploratory by count, but graph-only finds named subjects through the deterministic resolver by construction. Under this reading the "labelled only with works, paths or numbers" clause excludes no answerable question, so I read the decision as meaning answer targets.

Rules for step 3 (from decision 7.4, made concrete):
- K = 1, 5, 10, 30. recall@K is reported with its ceiling min(K, |T|)/|T| (with |T| = 10, recall@1 is at most 0.1). MRR is 1/rank of the first target, 0 if none.
- Graph-only lists are as long as the workflow makes them (plan limit 10 by default, QueryWorkflow.scala:586, 742). Record each mode's list length per question; at K = 30, graph-only is capped by that length (state it, do not pad).
- Several vector hits of one researcher collapse to one entry at the best rank. Fusion is RRF k = 60 over the union, labelled `fusion-exp-v1`, "experiment only; the production workflow has no fused ranking".
- Every mode keeps the same 11 questions in the denominator. Errors and empty results score 0 and are counted under `errors` or `empty`.
- Drift check (R2): for each label ID, record whether the loaded graph has it (ResearcherById), as `labels-not-in-graph` per question. Missing labels stay in the denominator and are never removed.

## EXPLAIN STAGE FIELDS

GET /v1/explain/{queryId} returns `ExplainView` (modules/services/src/main/scala/org/scholarchain/services/Models.scala:233-260, built at :275-303 from the latest QueryAudit, ReadServices.scala:84-95). Its latency fields are `stageMillis: Map[String, Long]` (:247, from QueryAudit.stageMillis) and `latencyMillis` (:248). `stageMillis` keys, written by `timed` in QueryWorkflow.scala:164-167:

| Key | Lines | What it times |
|---|---|---|
| planner | :176 | LLM planner call with its retries and validation, or the rule fallback |
| plannerFallback | :177 | only when the planner fell back: time inside the fallback |
| resolver | :183 | DynamoDB entity links, plus Neptune researcher_by_id and coauthor_neighbors per candidate, plus the metric cache |
| metrics | :186-188 | DynamoDB metric cache and rankings, Neptune work_by_id; for topic rankings also Voyage plus S3 Vectors (Stages.scala:339) |
| paths | :190 | S3 Vectors getVectors (precomputed path key), DynamoDB EvidenceBundle, Neptune path and edge templates |
| community | :191-193 | DynamoDB and Neptune |
| semantic | :194 | Voyage query embedding and S3 Vectors query |
| evidence | :197 | evidence building (CPU) |
| identity | :201 | Neptune researcher_by_id (at most 250) |
| synthesis | :214-226 | LLM synthesis call with retries and validation, or the template fallback |
| synthesisFallback | :227 | only when synthesis fell back |
| verification | :228-230 | deterministic claim verification |

- `audit` is never in the map: the map is captured inside the audit stage (:452) before `timed("audit")` adds it (:231, :166). `latencyMillis` (:481) is total wall time up to the audit build, without the bundle write and audit append.
- Against D's list:
  - planner: present (`planner`, including the LLM).
  - graph: missing as one stage; Neptune time is spread over resolver, metrics, paths, community and identity, mixed with DynamoDB and S3 Vectors.
  - vector: partial (`semantic`; Voyage and S3 Vectors are not separate, and vector reads also happen inside metrics and paths).
  - LLM: partial (`synthesis` and `planner` include non-LLM work; no pure model-call latency).
  - verification: present.
  - retries: missing. `modelCalls` gives per-task counts only (logicalCalls, invalidOutputs, truncatedOutputs, droppedBoundNumbers, fallbacks; QueryWorkflow.scala:734-741; Models.scala:256); HTTP attempts and retries are per process (Models.scala:272-273).
  - Neptune latency separate from the graph stage: missing.
- Keys are always present for a successful query (each `timed` runs even with 0 steps). The two *Fallback keys appear only on fallback. JSON key order is not guaranteed (ListMap to Map).

## VALIDATION EVIDENCE
- shasum -a 256 docs/agent-runs/phase-15/prompt.md -> 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (matches the work order).
- git rev-parse HEAD -> 2cbfbc72c704c40085e2876479d510b90062a12a.
- grep -c '"id": "q' benchmark-v1.json -> 30; '"kind": "ranking"' -> 60 entries at lines 33-261 (q01-q03), 880-1108 (q19-q21), 5574-5646 (q30); '"b": "researcher' -> 21 (q07 10, q08 10, q09 1); no grade or relevance field.
- evidence/runs/20261120T150727Z-b39e1ed5b785-67d3-smoke-test.json read: started 15:07:27Z, ended 15:07:44Z, query 200 "3 claim(s), 2 evidence link(s)".
- No sbt, no scripts, no repository writes; finished: after the last command above.
files changed after these runs: none
