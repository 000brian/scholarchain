# Phase 15 session 2: orchestrator working notes

## Start checks (decision 1)

- HEAD at start: 2cbfbc72c704c40085e2876479d510b90062a12a (the Phase 14 gate commit). `git diff --stat 2cbfbc72..HEAD`: empty.
- Tree: only `?? docs/agent-runs/phase-15/` (prompt.md). Expected.
- Prompt sha256: 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (docs/agent-runs/phase-15/prompt.md)
- MCP servers visible to the orchestrator (as deferred tools): aws-api (call_aws, suggest_aws_commands), aws-docs (search, read_documentation, read_sections, recommend), terraform (registry tools), easel, claude.ai Claude Docs. github: not visible (switched off, as expected).
- `terraform -chdir=infra/terraform/environments/course validate`: Success! The configuration is valid.

## Step log

- 11:45 launched 1 (domain), 5a (infra), 6a (experiment), 3r (rag, read-only). Scope: no reduction (estimate end ~17:45 < 18:30).
- Step 1: COMPLETE, interface: no. core and core2_13 config suites 64/64 each. Files: modules/core ConfigLoader.scala, ScholarChainConfig.scala, tests, valid-v1-service.json.
- Fixed-interface check after step 1 (literal grep): key `service.embeddingQueueUrl` (ConfigLoader.scala:240), env `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL` (test ServiceConfigSuite.scala:408) = decision 5 = 5a Terraform runtime/main.tf:68. Match.
- Note: the Grep tool is not available in this orchestrator session; literal `grep -rn` via Bash used instead (allowed fallback).
- Step 2 (storage) launched after step 1.
- Step 3r: COMPLETE (read-only). G-28: no code defect proven as cause; most consistent: Neptune non-timeout error -> "graph store failure" abstention; or no path within depth 6. Step 3 records reason only; D6 reads it. Hidden issues L1 (storage NeptuneAdapters.scala:221-227 memory/query-limit errors not classed as limit) and L2 (rag ClaimVerification.scala:282-290): reported, not fixed (decision 7.5: fix only a proven cause) -> proposed later (Phase 16 with a G-28 code fix).
- 3r interface: yes -> QueryAudit (core, Records.scala:712-740) has no reason field. Orchestrator decision: repair-00-domain adds an optional additive `abstentionCodes` (codes only) after step 2 ends (one sbt at a time), before step 3. Explain field `abstentionReasons: [{code,text}]` from a fixed rag table.
- E universe decision (orchestrator, from decision 7.4 text "labels at least one target researcher ID"): answer-target reading: 11 questions in universe (q01,q02,q03,q07,q08,q19,q20,q21,q25,q26,q30), 100 labels, 19 not-comparable, no graded labels (no nDCG), E labelled EXPLORATORY. The named-researcher reading (27) would widen the universe: rejected. q25/q26 targets = top 10 of labelled pool by the benchmark's own rule (assumption, for R-1).
- Step 5a: COMPLETE, 16 files under infra/ and scripts/, fmt/validate/shellcheck clean, 0 aws-api calls. Fixed-interface check after 5a (literal grep): --emr-max-units, --spark-shuffle-partitions (run-bounded-pipeline.sh:431-432), spark.shufflePartitions / cluster.managedScalingMaxUnits (sfn.tf:171-172), {attempt-id} and exit 8 in run-experiment.sh: match decision 9.3/9.6.
  - Beyond the work order (for R-2): runner now waits 5 min before metrics export (decision 10 text says it does; code did not), and refuses an untracked/non-executable collector before creating the run folder. Three new Terraform outputs (dlq_urls, alb_arn_suffix, target_group_arn_suffixes). Identifier list also covers ALB and Neptune hosts.
  - Risks: JSONata $error may be refused by Step Functions (D4a validate shows it); Ctrl-C stops runner but not action; ALB placeholders resolve only after D4; archive-evidence will flag Phase 14 smoke records (expected G-29 effect).
  - Exit-code table goes into experiments/README.md (experiment-engineer, 6b).
- Step 2: COMPLETE, interface: no. storage 265/265 (+1 RequiresAws ignored), rag users 44/44. Skip check via batched GetVectors (100 keys, metadata) instead of BatchGetItem/registry read (BatchGetItem not in role/boundary; cannot find latest registry version) -> for R-1: decision 6.1 alternative. Registry writes: bounded parallel conditional puts (16), not BatchWriteItem (conditions). Estimate: first pass ~31/36/50-54 min + 45 s render (Voyage tokens slowest); skip-only ~25-100 s + 45 s (estimate). Re-embed: VectorReembedder.reembed; completion record in QuerySession `reembed#sha256(taskId,textHash)`, 7-day expiresAt. 5b gap: worker lacks s3vectors:PutVectors; KMS for PutVectors unverified. Rag note: 7.1 Phase-14 skip test must seed state via the pipeline (skip reads index metadata).
- repair-00-domain launched (abstentionCodes on QueryAudit), before step 3.
- Step 6a: COMPLETE. load-tests/query-load.sh (query+read), questions-d.txt (25), read-ids-d.txt (29), derive scripts (common.py + 5), fixtures (10 attempts), template. chmod +x: load-tests/query-load.sh. E plots with K on x axis (planned exception, R-2). Unplanned deviation: python3 --version, sed -n, cd (read-only).
- 6b launched after 5a and 6a.
- repair-00-domain: COMPLETE, interface: yes. QueryAudit v3 `abstentionCodes: List[String]` (Records.scala:752); non-empty exactly when abstained; pattern kebab, <=64 chars, <=8 codes. core 342/342 x2; storage/rag/services Test/compile ok. Consumers: rag writes v3; services ReadRoutesSuite expects v3, explain shows codes.
- 12:16 clock; critical path estimate end ~17:00 < 18:30: fused mode kept. Step 3 (rag) launched.
- Step 6b: COMPLETE. 4 collectors + lib, 7 manifests, README exit table (experiments/README.md:113-127). Phase 14 EMR DID scale 2->4 core nodes (no 200-partition flag). C provisional N=10000, wait 5000 s, notes "two-task scaling uncertain". A/B metrics use CoreNodes*/TotalNodes* (TotalUnits* not published for instance groups). ApiGateway metrics dropped. Hadoop reduce tasks/shuffle will be `missing`. B remote upper bound ~$5.10 > runner $5 cap (R-2 rules). Neptune cluster literal identifier in D manifests (R-2 rules). aws-api 11/30. chmod +x: 4 collectors (+ query-load.sh). Unplanned: `cd_no=1`-style assignments only.
- R-2 launched (diff-R-2.txt, 1834 lines; diff-R-2-files.txt).
- Step 3: COMPLETE, interface: yes (QueryAudit v3 written, StageOutput.abstentions coded, --max-documents). rag 277/277, services Test/compile ok. MaxStepMillis 120 min. D7: --max-documents 5000 probe then full, --wait-timeout 1800 each; skip-only estimate 5/8/12 min. Handler ReembedHandler.fromConfig/handle; log line event=reembed-delivery, taskId, index, outcome, code, action, latencyMillis. One-task rate per loop 2.4/5.4/10 per s (estimate). RetrievalEvalJob options --config --out --code-version --attempt-id --graph-run [--modes --max-seconds 1200 --question-timeout-seconds 60]; exits 0/1/2. Explain field `abstentionReasons`. 5b: ops lacks neptune-db:ReadDataViaQuery; worker lacks s3vectors:PutVectors.
  - Unplanned deviation (for the gate): benchmark main resource is a derived eval set with a recorded sha256 and an equality test, not a byte copy (decision 7.4 asked for a copy whose sha256 equals the test file's). R-1 to rule.
  - Unplanned: Bash commands prefixed `cd REPO &&` (read-only).
- Fixed-interface check after step 3 (grep): embedding-task-v1 fields schema/taskId/index/vectorKey (ReembedHandler.scala:18-19) = 7.2 = f-poison-replay.sh:29 / c collector; log keys taskId/outcome (ReembedHandler.scala:320-322) = lib/collector-common.sh:530-531. Stream prefix `worker/` is a Terraform log setting: 6c checks against 5a/5b.
- Step 4 (service, wave closer) launched. explain.json is modules/services (service-owned).
- Step 4 attempt 1: BLOCKED-ON storage-engineer (valid interface block): SqsWorkQueue.receive parses {"id","payload"} (SqsWorkQueue.scala:72-76) -> embedding-task-v1 bodies throw; no services-reachable embedding-queue factory. 8.2 done (ExplainView.abstentionReasons, explain.json v3, ReadRoutesSuite); services 40/40 targeted. 8.1 not started. Report saved as agent-report-service.md (attempt 1; the re-run writes agent-report-service-2.md).
- repair-00-storage launched (raw-body embedding queue port).
- repair-00-storage: COMPLETE, interface: yes. ServiceStores.embeddingQueue (ServiceStores.scala:118), ReceiveQueue (WorkQueue.scala:19), SqsRawReceiveQueue (Receive :39, Delete :56, ChangeMessageVisibility :65). storage 25/25; rag/services/contractTests Test/compile ok.
- Step 4 re-run launched (agent-report-service-2.md), wave closer.
- R-2: REQUEST_CHANGES. J-01 major (experiment-engineer, f-poison-replay.sh part-2 deadline from replay send) -> repair round. Minors J-02..J-05 (experiment), J-06 (infra; note for D25). Rulings: 5a extras accepted; $error low risk (D4a validate); Neptune literal identifier allowed; K/level x axis acceptable -> J-04 recorded as unplanned deviation; B $5.10 upper vs $5 cap acceptable; CoreNodes* accepted. Saved verbatim review-report-b.md.
- Step 4 re-run: COMPLETE. EmbeddingWorker.scala; concurrency server option default 2 (1..8); close() shuts pools at once (60 s embed wait < 120 s stopTimeout); rag handler writes the log line. Targeted 17/17; FULL sbt check 2192/2192, contract 399; packageCheck ok; marker 2026-11-20T19:23:08Z. Unplanned: `cd REPO &&` prefixes.
- Final interface comparison at step 4 end: key/env (decision 5) = Terraform; message format (7.2) = collectors; log keys = collectors; explain field abstentionReasons (rag) = services Models.scala:259. Match.
- R-1 and 5b launched together (diff-R-1.txt 3039 lines).
- Step 5b: COMPLETE. --max-documents (run-bounded-pipeline.sh:611-620), --loader rag-retrieval-eval (:621-648; --graph-run-id required; wait >= job limit + 300), worker s3vectors:PutVectors (iam.tf:236-249), ops neptune-db:ReadDataViaQuery (iam.tf:332-341); KMS not needed (SSE-S3); README Phase 15 runbook (scripts/README.md:104-172), J-06 noted. Expected D3 plan adds in-place ops_task policy. aws-api 0. Unplanned: one `cd` prefix on shellcheck; one Bash grep incl. course/main.tf denied before running.
- R-1: REQUEST_CHANGES. H-01 major interface: yes (embedding URL only on worker -> config fingerprint -> query run ID differs between query role and worker; async ack queryId != stored). Minors H-02..H-07. Rulings: GetVectors skip accepted as deviation (H-07 note for D7); parallel conditional puts accepted; derived eval set accepted as deviation (record); v3 rule accepted; concurrency option accepted; E universe as implemented; fused experiment-only verified. Saved verbatim review-report-a.md.
- Triage plan (step 8, after R-3): H-01 -> fix (a) infra-engineer passes SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL to all four service roles (Main.scala builds the loop only for worker/all); service-engineer regression test. J-01 -> experiment-engineer. Minors recorded. H-01 has no remaining consumer step in session 2 (5b done; 6c does not use run IDs), so it goes in the step 9 round, not a repair-00.
- 6c launched (E collector + manifest; C final N; H-02 overrun in E wait).
- Step 6c: COMPLETE. e-retrieval-modes.sh (new, chmod +x needed), p15-e-retrieval-modes.json (EXPLORATORY; --max-seconds 1200, --wait-timeout 2400, maxDuration 3600; bound $0.20). C: N=19200, wait 5500 s, maxDuration 7700; one-task rate 4.8/10.8/20 per s (estimate); drain at low ~67 min; cost ~$0.12 central/$0.25 upper; "two-task scaling uncertain" kept. Planning: C ~50 min central, up to ~2 h (runtime-hour checkpoint before D12). E ECS series dropped (no service metric for ops task). aws-api 4. Eight manifests listed with sha256 in the 6c report.
- R-3 launched (diff-R-3.txt 661 lines).
- R-3: REQUEST_CHANGES. M-01 major interface: yes (vector bucket policy denies data plane to non-task principals; C/F laptop list-vectors/get-vectors refused). Minors M-02..M-08. Saved verbatim review-report-c.md.
- Step 8 triage at 13:59. Orchestrator decision M-01: option (b), no security change: keys selected from DynamoDB ArtifactRegistry (no resource policy; checked: only vectors/main.tf has a Deny); registry fields replace the vector-metadata sample; handler refusal counts as workload failure. Unplanned deviation from decision 11.4 mechanism (list-vectors/get-vectors) -> for the gate. Risk recorded for session 3: D17 AwsAdaptersAwsSuite (admin profile) will be refused on S3 Vectors by the same Deny (report, not repair).
  - Repair round: infra (H-01 fix a), experiment (J-01, M-01) in parallel; then service (H-01 regression test; full sbt check + packageCheck as last sbt-source repairer).
  - repair-01-infra: COMPLETE. H-01: single shared service_environment incl. embedding URL for all four roles (runtime/main.tf:56-70, :177-178); IAM unchanged; D3 plan counts unchanged (query/evidence/graph task defs carry the var). validate ok. Unplanned: `cd` prefix.
  - repair-01-experiment: COMPLETE. J-01 (f-poison-replay.sh:435-459 deadline from wait start, log read at least once). M-01: cc_registry_select (:711) / cc_registry_latest (:768) via ArtifactRegistry scan/query; no s3vectors calls left. New sha256: C 36352e8f…01ab8, F a39c7e45…fbaf. aws-api 3 (read-only profile cannot Scan: item shape from code; first live check D9a with the admin profile). Unplanned: `cd` prefixes.
  - repair-01-service launched (H-01 regression tests; full sbt check + packageCheck).
  - Minors recorded, not repaired (CLAUDE.md repair limits): H-02..H-07, J-02..J-06, M-02..M-08. Session 3 notes: M-02 run E's --check after D7 (ops slot busy during D7); M-03 README E example wait 1800 is too low (manifest uses 2400); M-06 README runbook table order: follow decision 13, not the table; J-06 at D25; H-07 D7 skip ratio measures index state.
- Unplanned deviation: 3r used sed (no-op), tr (failed), uniq -c, read-only.

## Items Phase 14 gate and summary send to Phase 15 (mapped)

| Item | Source | Decision |
|---|---|---|
| G-27 batched writes, sized limits, second pass, live vector search | gate 6, deferred; summary open findings | 6, 7.1; D7 |
| G-28 record abstention reason, diagnose | gate 6; summary | 7.5, 8.2; D6 |
| G-29 smoke-test host, archive identifier | gate minors; summary | 9.5; D5, D25 |
| RequiresAws suites (stub vectors) | gate 5, deferred | D8, D17 |
| embedding-queue setting | gate deferred | 5, 8, 9.1-9.2 |
| experiment manifests | gate deferred | 11; D9 |
| N-1 | gate deferred | phase-16 (decision 2) |
| U-11 | gate deferred | 6.4, 7.3; D12 |
| runtime-off / cleanup after experiments | gate 4, deferred | D19-D28 |
| Phase 7 AWS vector load: real search | gate items table | D7, D11 |
| run-experiment.sh never ran | gate risks | D10 (first runtime run D7a) |
| slow uplink | gate risks | D2 |
| no Neptune CPU alarm | gate 8 | limitation in results summary |
| G-22 api_url trailing slash | gate minors (Phase 17) | not this phase; tools strip `/` (6a) |
| G-23 readyz cold | Phase 16 | not this phase; tools retry readyz (6a) |
