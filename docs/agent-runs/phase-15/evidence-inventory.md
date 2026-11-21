# Phase 15 evidence inventory before cleanup (D25 step 1)

Written by the orchestrator, session 3 sitting 3, at HEAD 65172ab, before `archive-evidence.sh` and `cleanup.sh --list`. Logical names only (no account, ARN, bucket, URL or host).

## 1. Per experiment and attempt (all committed under `experiments/raw/`, commit in brackets)

| Experiment / attempt | Run record | Raw event data | CloudWatch metric export | Execution / scaling records | Derived inputs (`experiments/derived/out/…`, 08bbb9d) | Diagnostic evidence |
|---|---|---|---|---|---|---|
| A-Hadoop 1 `20261120T215912Z-b0c92f533c70-5a01` (failed, no cluster) [6a6c31a] | run-record.json, run-record.started.json, nested/ | action.log, tool-record.json, ab-evidence.json, sfn-events.csv | metrics/ (partial: EMR series unresolved, no cluster) | sfn-events.csv; emr-* files empty (no cluster) | p15-a-hadoop/attempts.csv, missing.csv | ValidationException recorded in runbook log D7a (L-01) |
| A-Hadoop 2 `20261120T225632Z-eb786c5275a3-5c75` [d872d12] | run-record.json, nested/ | hadoop-job-counters.csv, hadoop-step-counters.csv, ab-evidence.json | metrics/metric-data.json | sfn-events.csv, emr-cluster.json, emr-steps.csv, emr-instances.csv (instance-groups empty: AB-3) | p15-a-hadoop/capacity.csv + SVG | AB report |
| A-Spark `20261120T225850Z-eb786c5275a3-4f7e` [e86449a] | run-record.json, nested/ | spark-outputs.csv, spark-stage-timings.csv, spark-stage-metrics.csv, spark-conf.json | metrics/metric-data.json | sfn-events.csv, emr-* | p15-a-spark/* | AB report |
| B `20261120T225913Z-eb786c5275a3-fac6` [e86449a] | run-record.json, nested/ | as A-Spark | metrics/metric-data.json (CoreNodes requested/running) | sfn-events.csv, emr-instances.csv (2 added 23:08:48Z) | p15-b-spark-burst/* | AB report |
| C `20261120T225730Z-eb786c5275a3-ab26` [e86449a] | run-record.json | sends.csv, queue-samples.csv, worker-outcomes.csv, selected-keys.txt, sample-check.csv, settle.csv | metrics/metric-data.json | service-samples.csv, scaling-activities.csv, alarm-samples.csv | p15-c-embedding-backlog/* (ff00cfa, re-run identical 08bbb9d) | FECD report |
| D-query `20261120T235521Z-ff00cfa278c8-ca0c` [abe4764] | run-record.json | load-requests.csv, load-levels.csv, load-explain.csv, load-clock.csv, load-tool-record.json | metrics/metric-data.json | service-samples.csv | p15-d-query-load/* | FECD report |
| D-read 1 `20261121T001529Z-abe4764a8293-b3f0` [ee3bce8] | run-record.json | load-requests.csv, load-levels.csv, id-check.csv, load-clock.csv | metrics/metric-data.json | service-samples.csv | p15-d-read-load/* | FECD-1 (FECD report) |
| D-read 2 `20261121T002254Z-abe4764a8293-db0d` (failed: key not exported) [ee3bce8] | run-record.json | none (nothing sent; documented absence) | none (documented absence) | service-samples.csv | p15-d-read-load/tasks-…db0d.svg | FECD-4 |
| E `20261120T221402Z-6a6c31a06d60-b6c7` [2cc5804] | run-record.json, nested/ | retrieval-eval-report.json (byte copy of the S3 report), retrieval-modes.csv | metrics/metric-data.json | – (ops task, no scaling) | p15-e-retrieval-modes/* | – |
| F `20261120T223908Z-e94df52ca8fb-b4c0` [70876eb] | run-record.json | events.csv, queue-samples.csv, alarm-samples.csv | metrics/metric-data.json | service-samples.csv | p15-f-poison-replay/* | incident-F.json |

Other committed evidence: `experiments/manifests/*.json` (8, signed b0c92f5), `experiments/results-summary.md` (65172ab), reports under `docs/agent-runs/phase-15/` (FECD, AB, deploy, repairs, reviews, runbook log), `releases/course/` (3 Phase 15 release records: D4, D4b, D20). Local gitignored records (go into the archive): `evidence/runs/` (Phase 15 run records incl. D5, D6, D7 probe and full pass, clip 07 demo run), `evidence/packages/684636ef…/` (package and publish), plan records and summaries `plans/*.record.json|summary.txt` (D3 8820, D3b 35bf, D19 f91a).

## 2. What the cleanup destroys that holds evidence, and where each has a copy

| Destroyed by cleanup | Evidence it holds | Copy |
|---|---|---|
| Data bucket `reports/` | run reports written by jobs | archive `reports/` (archive-evidence S3 mode; cleanup refuses unless every key is in the archive) |
| Data bucket `rag/ingest-reports/` | D7 probe and full-pass ingest reports (G-27 parts 3–4: skip ratio, written 0, retrieval smoke), clip 07 demo run, Phase 14 ingest reports | **only in AWS today** → local copy step before the archive (section 3); fields also in runbook-log rows D7 probe / D7 full (committed) |
| Data bucket `rag/retrieval-eval/` | E report | byte copy in E raw folder (committed); local copy step (section 3) |
| Data bucket curated/graph/EMR logs | Hadoop `_job.json`, Spark manifests, step logs | fields extracted into A/B raw folders (committed); data itself is reproducible pipeline output, not grading evidence |
| DynamoDB RunLedger (query audits, ingest summaries) | explain records, clip 08 items | D-query load-explain.csv (committed), demo recordings (clips 03, 08), runbook log (D6 q13 reason) |
| CloudWatch log groups | worker per-delivery lines (F, C), service logs | F events.csv and C worker-outcomes.csv (committed) |
| CloudWatch dashboard | elasticity view | demo recording clip 12; metric exports in raw folders. Metric data itself is not removed (expires on AWS's schedule) |
| ECS/Step Functions/EMR consoles | events, execution histories | recordings clips 10, 11, 13; sfn-events.csv, scaling-activities.csv (committed) |
| S3 Vectors | indexes | none needed (reproducible; search results in E report and D7 smoke fields) |

## 3. Gap found (stop item per D25.1) and its resolution

`rag/ingest-reports/` and `rag/retrieval-eval/` exist only in the data bucket and are not under `reports/`, so `archive-evidence.sh` does not copy them. Resolution before the archive: the human copies both prefixes (read-only download) into `~/scholarchain-evidence-p15/s3-copies/` (outside the repository, next to the archive copies of D25.4), and the orchestrator records their file count and sha256 list in the runbook log. Status: done. 5 files, 232 KB: `rag/retrieval-eval/20261120T221406Z-6a6c31a06d60-2968/report.json` (E) and 4 ingest reports under `rag/ingest-reports/run=6301f6f046a30f08f173ce204b70ce6e/` (sha256 names 8c3f3eaa…, e6c3d05a… (D7 probe), e0cc74b2…, 86629cdd… (D7 full pass); the other two are the clip 07 demo run and a further run of the same ingest run ID (check which)). Orchestrator scan (counts only): no `s3://` bucket, ARN, AWS host, bucket name or URL; one 12-digit run = fractional digits of a float (`docsPerSecondLocalFakeNotRepresentative`), not an identifier. Committed under `docs/agent-runs/phase-15/s3-copies/` with `s3-copies.sha256` (human), and kept locally.

## 3b. Added during D27 (read-only, before the DynamoDB tables were removed)

`~/scholarchain-evidence-p15/dynamodb-sample/`: RunLedger items of clip 01's query run `run:v1:4f74439a72646af75c238901a8e07863` (4 items: 2 attempt reservations, 2 query audits) and 25-item samples of EvidenceBundle, ResearcherMetricCache and EntityLinkCache. Orchestrator scan: no identifiers. A sample of the Spark graph export in S3 was not copied (the bucket was already being emptied); the export's record counts are in the A/B raw folders (`spark-outputs.csv`).

## 3c. After the removal (D27 attempt 2, D28)

The removal plan record is committed under `docs/agent-runs/phase-15/cleanup/` (identifier scan clean). The saved plan, its text and the masked run output (`.tfplan`, `.plan.txt`, `.apply.txt`) stay local next to the Terraform configuration and are not committed: the run output names AWS service hosts. The run's figures are in the runbook log: 175 resources removed, 6,620,629 object versions emptied, 9 ECR images; leftover check exit 6 from tagging-API records of removed resources (L-15); direct per-service counts all 0 except the 20 kept task definitions (clip 21 part 5). If committed, the DynamoDB samples (3b) sit under `docs/agent-runs/phase-15/dynamodb-sample/` with `dynamodb-sample.sha256`.

## 4. Not evidence, kept by design

Hand-made IAM user and budget (Phase 17); ACTIVE task-definition revisions (expected leftovers, I-15); the local Terraform state.
