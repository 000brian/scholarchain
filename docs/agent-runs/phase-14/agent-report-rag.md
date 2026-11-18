# Phase 14 session 2, step 2: rag-engineer (read-only)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt.md (sha256 b1f951683cc708c297d3e74d6b21332f5218324e4db0086d33307ec8bf8b88a2, checked), decision 8 items 1-5, HEAD c4fbf62. Read: decision 8, Standing rules, Report contract, decision 18 inputs (rag, services, storage ports, scripts/smoke-test.sh, scripts/README.md, config/course.json, Phase 7, 9, 10, 13 rag evidence, dataset manifest).
FILES CREATED: none in the repository. Only this report (scratchpad).
FILES MODIFIED: none.
COMMANDS RUN: shasum -a 256 docs/agent-runs/phase-14/prompt.md (matches); git rev-parse --short HEAD (c4fbf62); git ls-files, git log (read-only); ls; wc -l; grep -n/-rn on literal repository paths (this session has no Grep tool; the Bash grep fallback was used, no guard-list word in any pattern); Read tool for file contents; aws-docs search_documentation once (S3 Vectors price; returned no price page). No sbt, no script, no AWS, no network other than aws-docs.
TEST RESULTS: not run: read-only step.
VALIDATION RESULTS: no file changed. Every statement below cites file:line or is marked unverified. Retrieval and groundedness evaluation: not re-run (no change). The last live groundedness evidence is Phase 10's Anthropic benchmark run: docs/agent-runs/phase-10/run-report-attempt-06-anthropic.json:301-444.
ASSUMPTIONS:
- The Phase 14 pipeline (EMR Hadoop, local ER with decision time 2026-11-14 and seed 20261114, EMR Spark) on dblp-2026-11-01 with dblp-subset-v1 reproduces the Phase 6/7 graph that benchmark-v1 was generated from. Researcher, work and evidence IDs, counts and values are expected to match only if that holds (unverified until D8/D9). Names and the shape of the answers do not depend on it.
- The Phase 10 live run (local stores, same model `claude-haiku-5-5`, same llm settings as config/course.json:71-82) is a fair basis for per-query model cost and latency.
RISKS:
- R1 (smoke latency): the sync deadline is 25 s (config/course.json:97; QueryService.scala:332-344 returns WORKFLOW_TIMEOUT after it), and smoke-test.sh then fails with exit 6. In Phase 10 the live synthesis stage had median 10.9 s, p95 26.4 s, max 28.2 s (run-report-attempt-06-anthropic.json:503-509). The two questions chosen took 5.7 s and 10.1 s locally (run-report :12, :20). A WORKFLOW_TIMEOUT is a re-run (new attempt), not a defect.
- R2 (Voyage rate limits): the ingest time estimate assumes the configured 300 RPM and 1,000,000 TPM (config/course.json:68-69). The real limits of this Voyage account are unverified. If they are much lower, the ingest step exceeds RagIngestJob's 60-minute step cap (RagIngestJob.scala:391) and stops.
- R3 (S3 request charges): 251,094 ordinary S3 text-object PUTs are not in any recorded price table (also flagged in Phase 7: docs/agent-runs/phase-07/agent-report-rag.md:311). At the commonly published S3 Standard rate ($0.005 per 1,000 PUT, unverified) that is about $1.26, the largest AWS line of the ingest.
- R4: /readyz is not a "data loaded" signal: it passes on empty stores (item 3). D12 should expect 200, and only the D14 query proves the loads.
- R5: the Bedrock tests in both live suites are expected to fail (this account's Bedrock on-demand quota is 0: LiveLlmAwsSuite.scala:10-11; docs/agent-runs/phase-07/prompt.md:201). See item 4.
MANUAL ACTION REQUIRED: none for this step. For D13f, D14, D15, D16 the human uses the commands and estimates below.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 5 (infra-engineer) copies the two smoke questions into scripts/README.md (decision 9 item 6 v); session 3 D12-D16 use items 2-5.

## Item 1: smoke questions (status: done)

Both come verbatim from modules/rag/src/test/resources/benchmark/benchmark-v1.json. Neither contains a single quote.

Why the subset contains them (applies to A and B): benchmark-v1 was generated only from the Phase 7 export tables (benchmark-v1.json:5-9: export manifest sha256 f6ebe62d...3021, source run run:v1:51b50d578d5b609842c1c21b8266a00b). That is the Phase 7 full export (docs/agent-runs/phase-07/agent-report-rag.md:83, same sha256 and run). Its records carry snapshotId dblp-2026-11-01 (docs/agent-runs/phase-07/agent-report-spark.md:171), 16 venues and windows inside 2015-2025 (agent-report-spark.md:109, :140-144), which is the dblp-subset-v1 rule (datasets/dataset-manifest.yaml:33-46: 16 venue key prefixes, 2015 <= year <= 2025). The Phase 10 live run on the same export resolved 18 of 18 named researchers and matched 4 of 4 paths (docs/agent-runs/phase-10/run-report-attempt-06-anthropic.json:301-361).

### A (researcher question, spec gate): benchmark q05

Text (exact):

    Which paper of Wanli Ouyang had the most coauthors on one paper between 2015 and 2025?

zsh form: `bash scripts/smoke-test.sh --question 'Which paper of Wanli Ouyang had the most coauthors on one paper between 2015 and 2025?'`

- Source: benchmark-v1.json:306-335 (category max_team_size, identity resolved).
- Path through the code: planner -> max_team_size (RulePlanner.scala:130; "coauthors on one paper" is a team phrase at RulePlanner.scala:288-294; the LLM planner gives the same op); metrics from DynamoDB ResearcherMetricCache and works through Neptune WorkById (Stages.scala:168 comment). It needs the D13a DynamoDB load and the D13c-e Neptune load. It does not need the vectors (no semantic step for this op, RulePlanner.scala:124-142).
- Phase 10 live latency 5.7 s (run-report-attempt-06-anthropic.json:12).
- A correct answer contains: researcher Wanli Ouyang (researcher:v1:fe1177235d9a724607da7e9700d133c4) resolved to exactly one researcher; window full-2015-2025 (2015-2025); metric max_coauthors_on_one_paper = 19; the work "Lumina-Next : Making Lumina-T2X Stronger and Faster with Next-DiT." (work:v1:daa83d005f1e6313587b1f3d3c65c243, year 2024, team size 20); at least one claim with status verified citing an evidence ID that GET /v1/evidence/ID returns (smoke steps 6-7, smoke-test.sh:61-68).
- Why not q04 (Philip S. Yu): he is identity-conflict flagged (docs/agent-runs/phase-09/review-report.md:36, :107), so the answer carries an uncertainty note. q04 also took 17.4 s live (run-report :11).
- Fallback A if q05 fails on identity: q18 `How many other communities does Suiyi Zhao connect to in 2015-2025?` (benchmark-v1.json:827-864; communities_connected 22, cross_community_degree 460, community_size 819; 5.1 s live, run-report :25).

### B (collaboration path, Neptune path template): benchmark q13

Text (exact):

    Find the shortest path between Tommaso Green and Yiannis Demiris in the coauthorship graph for 2015-2025.

zsh form: `bash scripts/smoke-test.sh --question 'Find the shortest path between Tommaso Green and Yiannis Demiris in the coauthorship graph for 2015-2025.'`

- Source: benchmark-v1.json:672-691 (category shortest_path_computed: "computed pair: shared neighbour and no direct edge", selection rule benchmark-v1.json:15). This pair has no precomputed Spark path, so the workflow always takes the Neptune template.
- Path through the code: shortest_path (RulePlanner.scala:125) -> PathFinder.shortestPath: `precomputed` finds no Spark path (Stages.scala:505-528), so it runs `GraphQuery.PathBetween` (Stages.scala:560-579, origin "graph-path-search"), which storage compiles to one openCypher query per depth 1..maxDepth (NeptuneModel.scala:142-151) and runs by iterative deepening within runtime.graphTimeoutMillis 20,000 ms (NeptuneAdapters.scala:86-90, :131-149; config/course.json:89). Each hop then gets its edge from `CoauthorEdgeBetween` (Stages.scala:460-501). That is the Phase 9 live Neptune path check.
- Why not q10 or q11: they match precomputed Spark paths, found through the collaboration-path vector key (Stages.scala:505-528). After rag-ingest (D13f) they are answered from the EvidenceBundle table, not from the Neptune path template (Phase 10: origin precomputed-spark-path, run-report :337, :345). q13 was origin graph-path-search in Phase 10 (run-report :357) and works before and after rag-ingest.
- Phase 10 live latency 10.1 s (run-report :20). Neptune timing on the real cluster: unverified.
- A correct answer contains: Tommaso Green (researcher:v1:002a3ae503bdb566079cc2fab5b0d63e) and Yiannis Demiris (researcher:v1:0574994137251083fe63869197dcac40), both resolved; window full-2015-2025; a path of exactly 2 hops (benchmark-v1.json:687-690) through one shared coauthor (the benchmark does not name that researcher); no direct edge between the two; per hop a COAUTHORED_WITH edge fact (edge ID, joint publication count, first and last collaboration year, up to 5 supporting works), source `graph:` plus the path template ID; at least one verified claim with an evidence link.
- A failure here is a finding, not a gate failure (decision 12 D15).

## Item 2: vector ingest estimate (status: done; ESTIMATE, not a measurement)

Basis: the Phase 7 local ingest of the same full export (docs/agent-runs/phase-07/agent-report-rag.md:202-228).

| what | value | source |
|---|---|---|
| researcher-profile-v1 | 64,090 documents | phase-07 agent-report-rag.md:206 |
| collaboration-community-v1 | 30,962 | :207 |
| paper-abstract-v1 | 155,978 | :208 |
| collaboration-path-v1 | 64 | :209 |
| topic-institution-summary-v1 | 0 | :210 |
| total embedded | 251,094 documents (0 chunked; max text 1,197 chars) | :211; :159 |
| text total | 94,928,925 characters | :228 |

- Voyage tokens: about 23.7M at 4 characters per token, 31.6M at 3 (the client's rate-limit estimate uses about 3, agent-report-rag.md:181). Run 2 of the default `--runs 2` embeds nothing (skip ratio 1.0, :219-222). The retrieval smoke after the runs adds 25 samples x 4 non-empty indexes = 100 one-text query requests of about 8 tokens (RagIngestJob.scala:528-542, default 25 at :156); negligible.
- Voyage requests: 251,094 / batchSize 64 (config/course.json:64) = about 3,924 document requests, plus about 100 query requests.
- Voyage price used: voyage-4 $0.06 per million tokens, first 200M tokens free. Source: docs/agent-runs/phase-07/prompt.md:200 ("Voyage pricing and model pages", captured by the human in Phase 7; not re-checked now: unverified for today). Voyage spent so far: $0 (phase-14 prompt.md:116).
  - Expected charge: $0 (inside the free 200M tokens).
  - Without the free allowance: $1.42 to $1.90.
- Time: token-bound by the configured 1,000,000 TPM: about 24 to 32 minutes; requests at 300 RPM need about 13 minutes. Under the 60-minute step cap (RagIngestJob.scala:391) only if the account's real Voyage limits are at least the configured ones (R2, unverified).
- S3 Vectors write volume: 251,094 vectors x 1,024 float32 = 1,028,481,024 bytes of vector data (about 1.03 GB), about 1.2 GB with metadata (Phase 7 estimate about 4.8 KB per vector, agent-report-rag.md:309). PutVectors calls: at least 503 (500 vectors per call, phase-07 prompt.md:192). Phase 7's local run made 981 put calls (agent-report-rag.md:222); the AWS count depends on the pipeline's flush size: unverified.
  - Price: $0.20 per GB uploaded, $0.06 per GB-month (phase-07 prompt.md:196; infra/terraform/COSTS.md:24, "estimate (unverified)"). Upload about $0.21 to $0.24; storage about $0.07 per month.
- Other AWS writes of the ingest (run 1):
  - ordinary S3 under the `rag/` prefix: 251,094 text objects, 94,952,071 bytes (agent-report-rag.md:227), plus the run report copy (docs/agent-runs/phase-13/agent-report-rag.md:44). PUT requests at $0.005 per 1,000 (unverified): about $1.26 (R3).
  - DynamoDB ArtifactRegistry: 251,094 items, about $0.16 to $0.31 (agent-report-rag.md:310; COSTS.md:25); RunLedger: a few items.
  - Run 2 reads only (skip check), a few cents (unverified).
- Total estimate for D13f: Voyage $0 (worst $1.90); AWS about $1.6 to $1.8 (S3 PUT $1.26 unverified, S3 Vectors $0.24, DynamoDB $0.31, ops task and NAT a few cents). Worst case about $3.7. The human approves against this (D13f).

## Item 3: readiness on empty stores (status: done)

Answer: yes, `/readyz` returns 200 `ready` when the DynamoDB tables, Neptune and the vector indexes exist but are empty, as long as each store answers within the deadline.

- The checks are only reads by a fixed probe key that never exists: `metrics` = ResearcherMetricCache get of researcher `researcher:v1:000...0` in the default window, `graph` = Neptune `ResearcherById` of the same ID (ServiceServer.scala:147-153; ServiceLimits.scala:104), and `jobs` = job store get of `job-readiness-probe` for the query, worker and all roles (ServiceServer.scala:154-157; ServiceLimits.scala:107).
- A check passes when its read returns Right (HealthService.scala:79). A missing item is Right(None): metrics MetricCacheReader.get (RuntimeReaders.scala:75-88), jobs JobStore.get (JobStore.scala:107-114). An empty openCypher result is Right with no rows (NeptuneAdapters.scala:117-129).
- No check reads the vector indexes, S3 or the EvidenceBundle table, and none asks for data.
- So the result is the same before and after the loads; readiness proves reachability and IAM, not loaded data. D12 should expect 200, not 503.
- When it can be 503: a read that fails or takes longer than the 2,000 ms total deadline (HealthService.scala:77-96; ServiceLimits.scala:14), for example a cold first Neptune or DynamoDB call (TLS, credentials). The result is cached for 5 s (ServiceLimits.scala:19), so a re-try after 5 s probes again. Cold-call timing: unverified.
- ALB target health uses `/healthz`, not `/readyz` (infra/terraform/modules/runtime/ingress.tf:37-40), so readiness does not gate traffic. API Gateway routes `/readyz` to the query role (ingress.tf:83).
- Read-only, not changed: modules/services (service-engineer).

## Item 4: live-suite commands (status: done)

Both are `RequiresAws` and run only in the `AwsTest` configuration (build.sbt:93-107; `Test` excludes the tag at build.sbt:122). AwsTest never writes `.claude/.last-test-run` (build.sbt:97). Run from the repository root. The prompts are read from `prompts/runtime/` on disk (LiveLlmAwsSuite.scala:20).

### LiveLlmAwsSuite (modules/rag/src/test/scala/org/scholarchain/rag/runtime/LiveLlmAwsSuite.scala)

One line (zsh; the key is typed, never shown):

    read -rs "SCHOLARCHAIN_ANTHROPIC_API_KEY?Anthropic key: "; export SCHOLARCHAIN_ANTHROPIC_API_KEY; sbt -batch "rag/AwsTest/testOnly org.scholarchain.rag.runtime.LiveLlmAwsSuite"

- Environment variables (names only): `SCHOLARCHAIN_ANTHROPIC_API_KEY` (the `llm.apiKeyEnvVar` of rag-runtime-anthropic-v1.json:98). For the Bedrock test: AWS credentials from the SDK default chain (for example `AWS_PROFILE`), Region us-east-1 from the test config (rag-runtime-anthropic-v1.json:5).
- Test 1, Anthropic `claude-haiku-5-5` planner draft (LiveLlmAwsSuite.scala:22-40): 1 request expected, at most 3 (asserted at :32). Input about 1,400 tokens and output about 190 per request (Phase 10 planner average: 41,205 / 30 and 5,575 / 30, run-report-attempt-06-anthropic.json:561-562). Price: $0.10 / $0.50 per million input / output tokens (docs/agent-runs/phase-10/prompt.md:312, "Anthropic pricing"; not re-checked: unverified for today). About $0.0002; worst case (3 requests, 2,048 output tokens each, rag-runtime-anthropic-v1.json:90) under $0.004.
- Test 2, Bedrock `anthropic.claude-haiku-5-5` (:42-64): at most 3 requests. Expected to FAIL: this account's Bedrock on-demand quota is 0 (:11). A refused call costs nothing (unverified).
- Writes: nothing in AWS, nothing at Anthropic beyond the request. Locally only sbt's own target output.

### LiveProvidersAwsSuite (modules/rag/src/test/scala/org/scholarchain/rag/LiveProvidersAwsSuite.scala)

One line:

    read -rs "SCHOLARCHAIN_VOYAGE_API_KEY?Voyage key: "; export SCHOLARCHAIN_VOYAGE_API_KEY; sbt -batch "rag/AwsTest/testOnly org.scholarchain.rag.LiveProvidersAwsSuite"

- Environment variables (names only): `SCHOLARCHAIN_VOYAGE_API_KEY` (rag-voyage-example-v1.json:80). For the Titan test: AWS credentials from the default chain, Region us-east-1 (rag-bedrock-example-v1.json:5).
- Test 1, Voyage voyage-4 expected neighbours (:174-201): 1 document request (8 synthetic texts) plus 8 one-text query requests = 9 requests expected; asserted at most 2 document and 13 query requests, retries included (:199). About 2,000 tokens (:19-21). $0 (free allowance; $0.00012 at list price, phase-07 prompt.md:200). Vectors go to storage's in-memory index (LocalStorageEnvironment, :187): nothing is written to AWS. 120 s timeout per test (:29).
- Test 2, Bedrock Titan V2 probe (:203-214): 1 InvokeModel request (embedder retries throttling up to maxRetries 5, rag-bedrock-example-v1.json:82). Expected to FAIL (quota 0, phase-07 prompt.md:201). Writes nothing.
- Optional: running only the non-Bedrock test by name needs MUnit's test-name filter after `--`. Its exact syntax was not checked: unverified. Running the whole suite is the recommended form; the Bedrock failure is expected and is reported, not repaired.

## Item 5: smoke cost (status: done; estimate)

Model calls per `smoke-test.sh` run (scripts/smoke-test.sh:50-74, :440-539):
- healthz, readyz, the 401 check: no workflow, no model call (:56-60).
- query: one sync workflow = 1 planner call (prompt query-planner, Synthesis.scala:24-94) + 1 synthesis call (Synthesis.scala:153-283). Each repeats at most once on invalid output (`llm.maxStructuredRetries` 1, config/course.json:80; loops at Synthesis.scala:60 and :224). Each logical call does at most 3 HTTP retries on transient errors (config/course.json:76). So 2 logical calls expected, 4 at most, 16 HTTP requests at the very most.
- Voyage: 0 query embeddings for A and B (no semantic_context step for these ops, RulePlanner.scala:124-142; SemanticContextStage runs only for semantic_context or topic rankings, Stages.scala:905-910). At most 1 short query (a few tokens) if the LLM planner adds a semantic step: $0.
- idempotency-replay: served from the stored job, no model call (smoke-test.sh:69-71). idempotency-conflict: 409 before any workflow (:72-74).
- Estimate per run: Phase 10 live average per question was 2.53 model calls, 5,484 input and 3,505 output tokens (76 calls, 164,511 and 105,161 tokens over 30 questions, run-report-attempt-06-anthropic.json:535-540). At $0.10 / $0.50 per million (phase-10 prompt.md:312, unverified today): about $0.0023 per run. Worst case (4 calls, about 12,000 input and 4,096 output tokens each, config/course.json:74, :81): about $0.013.
- D14 plus D15: about $0.005 expected, under $0.03 worst case. A WORKFLOW_TIMEOUT re-run (R1) costs the same again. AWS: about 8 API Gateway requests and a few DynamoDB items (one job, one idempotency record, both expiring, smoke-test.sh:97-98): under $0.01.

## Prompt and model versions
- Unchanged: prompt set Current v3 (query-planner-v2, answer-synthesis-v3; docs/agent-runs/phase-13/agent-report-rag.md:58). Model `claude-haiku-5-5` (config/course.json:17), embeddings `voyage/voyage-4/1024/document/emb-v1`.

## Evaluation metrics
- Not re-run (read-only step). Last live figures: Phase 10 (run-report-attempt-06-anthropic.json:301-444): identity 30/30, deterministic values 110/110, paths 4/4, planted errors caught 85/86.

## Known grounding gaps
- None new. Phase 10's planted wrong-window-year miss (1 of 23, run-report :427-433) stays as recorded.

## Proposed later
- A readiness check that says whether data is loaded (for example the presence of the graph run's marker item) would make `/readyz` a load signal (service-engineer and storage-engineer; not in this phase's scope).

VALIDATION EVIDENCE
- targeted: not run: read-only step
- full: not run: read-only step
- package: not run: read-only step
- finished: after the last command above
- files changed after these runs: no
