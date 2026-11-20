# Phase 14, session 3: orchestrator summary

Work order: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e). Step-by-step evidence: runbook-log.md. Deployment report: agent-report-deploy.md.

## Phase result

Deployed and smoke-tested on the loaded bounded graph; the runtime stays on for Phase 15. Two major findings stay open for Phase 15 (G-27, G-28), and the $8 cost bound was passed (about $10.04).

- Build f159535e498ad1a4504dad1fac0a3fb6ceee14e6, image sha256:79297e420fed6e5c1b98196dab7aa8386ec3ae30b24c66c7e37ab1ef696e59eb (three-way match: publish record, ECR, running tasks).
- D11 runtime-on release 20261119T193246Z-167196849087-d539 (plan 5656145e…4199, approvals.md:45): query, evidence, graph :2 at 1/1; worker :2 at 0/0. No rollback.
- Pipeline: Spark export run:v1:f2625d504b12749389cf4f5b1a26aed4 (D9) -> loads:
  - D13a DynamoDB: 763,577 items, 0 conflicts or rejects.
  - D13b Neptune CSV: graph run run:v1:9e520a1f0059ac71c05a21d525df4bf0, 0 rejects, ids match for all 16 types.
  - D13c-e Neptune bulk load: nodes 14,290,698 records, edges 64,423,404 records, both LOAD_COMPLETED, 0 errors. The registry start-once guard refused a duplicate start (observed at runtime).
  - D13f rag-ingest: all 251,094 documents embedded and written (3.61 h), then stopped by the 60-minute step cap: idempotency pass and retrieval smoke not run (G-27).
- D14 smoke (spec gate) 20261120T150558Z-b39e1ed5b785-6ccd: 7/7 (healthz, readyz, 401, query with 2 claims and 3 evidence links, evidence, replay, 409). Gates "Services are healthy and ready" and "Smoke query returns an evidence-backed response on loaded data" met.
- D15 Neptune path check 20261120T150727Z-b39e1ed5b785-67d3: script 7/7, the live path template path_between_depth_6 ran, but the answer abstained (G-28; a finding, not a gate failure, prompt-s3.md:268).
- D16 live suites: moved to phase-15 (human decision, prompt-s3.md:269).
- D17 deploy-engineer: all checks pass; alarms read by the human: no critical alarm (5xx and DLQ OK; the three target-tracking AlarmLow alarms are the idle scale-in signal at minimum capacity).
- D18 archive 20261120T153504Z-b39e1ed5b785-7bd5: 117 files, tar sha256 8d1e2be4511dcfc6790f42ce87c5b88e80a1d1e7310c4b4e302aabf76afb9c8f, identifier check ran (byte_copies_hold_identifiers false; I-25 runtime-tested).
- D19: runtime on; no active EMR cluster, no running ops task.

## Repairs in session 3

- G-24 (class 2, infra-engineer, code-reviewer APPROVE, human commit b39e1ed): stage_load builds the run-task overrides with one jq --arg per word (jq read OPS_CMD's --config as its own option). Worked at runtime from D13a attempt 2 on.
- G-17 (fixed in session 2) held: defaults in force. The D6 attempt 3 export (39.3 M candidates, run=cfc11015…) is left unused in S3.

## Open findings

Major, Phase 15:
- **G-27** (rag-engineer with storage-engineer): full-scale rag-ingest cannot finish as designed. Single-writer throughput about 19 documents/s (per-document S3 and DynamoDB calls; Voyage used about 0.1 M of the configured 1 M tokens/min). Every full ingest exceeds MaxStepMillis 60 min (RagIngestJob.scala:391), which is checked after the step, so the data is written and then run 2 and retrieval-smoke are skipped. The script's 7200 s load wait (run-bounded-pipeline.sh:37) also ends before the task (D13f exit 7, timed-out). The session 2 estimate (24-32 min) counted Voyage time only. Consequence: the Phase 7 line "AWS vector load of at least 10,000 vectors, real search, real speed and cost" has the load (251,094 vectors) and the real speed, but no live vector search: D14 and D15 make no semantic retrieval call (agent-report-rag.md:132).
- **G-28** (rag-engineer with storage-engineer): q13 (Tommaso Green to Yiannis Demiris, 2015-2025) abstains on the full graph after researcher_by_id, coauthor_neighbors and path_between_depth_6 ran (Phase 10 local: answered by graph-path-search). Cause unknown: no path within depth 6, a bounded or timed-out path query on db.t4g.medium, or identity uncertainty (evidence type match_decision). The explain response carries no abstention reason.

Minor, proposed later:
- G-22: terraform output api_url ends in "/"; document `${U%/}` (scripts strip it, smoke-test.sh:309).
- G-23 (service-engineer): /readyz graph check flaps on a cold call under the 2000 ms probe deadline; the failure reason is never logged.
- G-25 (infra-engineer): program lines cut at 2000 characters (run-bounded-pipeline.sh:1725), so long load reports (e.g. graphRun) are lost from the run record.
- G-26 (infra-engineer): StorageLoadMain exit 3 ("load not finished") is recorded as a failure (exit 6) instead of a distinct "not finished" result.
- G-29 (infra-engineer): smoke-test.sh prints the API Gateway host and records it as api_host (smoke-test.sh:249); the archive identifier check does not register it (archive-evidence.sh:475), so it is in the uncommitted tar.
- Note: no Neptune CPU alarm exists (the bulk load ran at 89-97% CPU for 3.5 h).
- Earlier minors still open (session 1-2 records): G-03 to G-06, G-08 to G-11, G-13, G-15, G-16, G-18, G-19, G-20, Spark C1 and C2.

Next free ID: G-30.

## Leftovers in AWS (kept, not deleted)

- D6 attempt 3 export (run=cfc11015…, 39.3 M candidates) and the D6 attempt 3 Hadoop runs: unused.
- D8 attempt 1 partial output under graph/runs/.
- The runtime (ECS services, ALB, NAT, Neptune) stays on for Phase 15 (D19, decided).
- Later-phase item (human): clean up the AWS environment right after Phase 15's experiments and the demo recording (instructor: stop paid resources when experiments are complete); Phases 16 and 17 work from frozen data.

## Costs (D20, human-read)

| Provider | Amount |
|---|---|
| AWS (Nov month to date, Cost Explorer, lagging): EC2 compute $3.02, EC2 other $2.51, ELB $1.15, ECS $0.96, S3 $0.74, others $1.54 | $9.92 |
| Anthropic | about $0.12 |
| Voyage | $0 (inside the free tokens) |
| Total | about $10.04 |

Planned-bound overrun: the Phase 14 bound was $8. Drivers: EMR runs (including the failed D8 and the G-17 re-run), NAT and ALB hours, Fargate (including the 3.7 h ingest task), and Neptune I/O beyond the free 10 M (read 2,016,749 + write 13,796,502 = 15.8 M since Nov 17, about 5.8 M billable, about $1.16 at $0.20 per million, mostly the bulk load). Human budget decisions: no re-runs; D13f run 2 and retrieval smoke skipped (G-27); D16 to phase-15.

## Deviations

Planned (prompt-s3.md:291-298): deploy-engineer did not execute scripts (1); admin profile for every script (2); first bounded pipeline in Phase 14 (3); no rollback on the first runtime-on deploy (4); three runtime-off plans (5); runtime stays on (6).

Unplanned (all read-only, none changed a file or AWS):
- spark-engineer (session 2): sort, uniq and cd in Bash.
- code-reviewer (earlier): Grep lines over plan.txt files.
- Orchestrator: a Bash grep including infra/terraform/environments/course/*.tf (blocked before running); one `cd evidence/runs`; one grep piped into `sort -u`; aws-api calls with wrong cluster or service names (errors, redone).
- deploy-engineer (D17): one `cd` into evidence/runs; one `ls … | grep` over file names under infra/terraform/environments/course/plans/ (names only).
- D13d attempt 1: a mangled terminal line re-ran D13c (refused by the start-once guard, no effect).
- D13f ran while the edges load was still running: planned after a code check (one-ops-task counts ECS tasks only; rag-ingest never calls Neptune).
