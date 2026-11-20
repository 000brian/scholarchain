# D17 deployment report, deploy-engineer

(Saved in substance by the orchestrator from deploy-engineer's final message.)

STATUS: COMPLETE
OWNER: deploy-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e, verified), section 13 (:275-285), deviation 1 (:293); session 3, step D17. Records and live state verified read-only; no script run.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: read-only git (status --porcelain empty; HEAD b39e1ed5b7855158b87e979624bb9c43b7a2fcc8; log of releases/ and approvals.md); ls, grep, jq, shasum on release, package, publish, run and smoke records and approvals.md; Read on plan *.record.json and *.summary.txt; 14 aws-api calls (2 denied), listed below. Process slips (logged by the orchestrator as unplanned deviations): one Bash line began with `cd` into evidence/runs (changed nothing); one `ls … | grep` filtered file names under infra/terraform/environments/course/plans/ (names only, no file contents; standing rule: no Bash grep there).
TEST RESULTS: n/a (no sbt); smoke results below.
VALIDATION RESULTS: all checks pass except alarms (`unverified`, access denied).
ASSUMPTIONS: all four release records under releases/course/ are Phase 14 (git log: runtime-off attempts 3, 4, 5 and D11 runtime-on); attempts 1-2 have no release record (failed applies; attempt 2 plan 6d4a629c in approvals.md:9); worker at desired 0 is what Terraform sets (release record agrees).
RISKS: alarm states unknown; G-27 open (rag-ingest idempotency pass and retrieval smoke never ran); D15 record shows "ok: 3 claim(s), 2 evidence link(s)" and cannot show abstention (G-28); D13f ran while the edges load was still running (planned: the orchestrator's code pre-check showed rag-ingest is independent of Neptune).
MANUAL ACTION REQUIRED: run the alarm CLI line (Unverified items) and give the names and states.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: D18 archive (human), then the human gate.

## Release records (Phase 14)
All us-east-1, environment course, verified true; every plan record has the same account fingerprint, and the live account matches the plan record's fingerprint (recomputed with the plan script's salted scheme, scripts/lib/identity.sh:292).

| Record | Kind | Commit | Plan sha256 (approved line) | Build | Revisions |
|---|---|---|---|---|---|
| 20261118T184244Z-39af6e4d0c51-9e3a.json | runtime-off attempt 3 | 39af6e4 | a865a94d… (approvals.md:17) | e78bb10 | all :1 at 0/0 |
| 20261118T212004Z-b925bc4ac306-8f27.json | runtime-off attempt 4 | b925bc4 | 5c77365f… (:29) | e78bb10 | all :1 at 0/0 |
| 20261119T054925Z-cf28e28596c1-d63f.json | runtime-off attempt 5 | cf28e28 | 619576314f4b…8975 (:37) | f159535 | all :1 at 0/0 |
| 20261119T193246Z-167196849087-d539.json | D11 runtime-on | 1671968 | 5656145ec55e…4199 (:45) | f159535 | query/evidence/graph :2 at 1/1, worker :2 at 0/0 (previous :1) |

- 5656145e (runtime-on): plan commit ba345f8; overrides runtime_enabled=true, image_tag=f159535…, graph_run_id run:v1:f2625d504b12749389cf4f5b1a26aed4; create 10, update 8, delete 0, replace 5; IAM, network, stateful actions none; no stateful delete or replace. Graph run = the export run of every D13 load.
- 61957631 (runtime-off attempt 5): plan commit 20e79f3; update 1, all else 0; no stateful actions.

## Image digests (three-way)
All equal sha256:79297e420fed6e5c1b98196dab7aa8386ec3ae30b24c66c7e37ab1ef696e59eb:
- publish record evidence/packages/f159535…/publish/20261119T004327Z-f159535e498a-3af4.json: succeeded, exit 0, ecr.outcome pushed, tag f159535e498ad1a4504dad1fac0a3fb6ceee14e6;
- ECR scholarchain-course-services, tag f159535… (only tag), pushed 2026-11-19T00:44:04Z;
- the `app` container of all 3 running tasks.
Tag = build commit. package-record.json: build_commit f159535…, linux/arm64, config sha256 297b9814… (= load records).

## Revisions (live vs D11 record)
| Service | Live | Desired/running/pending | Deployments | Match |
|---|---|---|---|---|
| query | scholarchain-course-query:2 | 1/1/0 | 1 PRIMARY, COMPLETED, 0 failed | yes |
| evidence | scholarchain-course-evidence:2 | 1/1/0 | 1 PRIMARY, COMPLETED, 0 failed | yes |
| graph | scholarchain-course-graph:2 | 1/1/0 | 1 PRIMARY, COMPLETED, 0 failed | yes |
| worker | scholarchain-course-worker:2 | 0/0/0 | 1 PRIMARY, COMPLETED | yes |
All ACTIVE; the three tasks started 19:31Z on Nov 19 (D11), no restarts since.

## Health and smoke
- ALB target health: 3 target groups (query, evidence, graph; /healthz, port 8080), 1 target each, all healthy. ECS container health UNKNOWN (no container health check defined).
- D14 smoke 20261120T150558Z-b39e1ed5b785-6ccd: succeeded, exit 0, head b39e1ed, build f159535, tree_dirty false; healthz 200, readyz 200, 401 UNAUTHENTICATED, query 200 (2 claims, 3 evidence links, run:v1:e2a42da7…), evidence 200 (evidence:v1:770b2563…), replay 200 (Idempotent-Replayed true), conflict 409.
- D15 …150727Z-…-67d3: succeeded, exit 0, 7 checks ok, "3 claim(s), 2 evidence link(s)"; abstention not visible in the record (G-28 from the orchestrator's explain read).
- api_host field not copied (G-29).

## Alarms
unverified: cloudwatch:DescribeAlarms denied for the read-only user.

## Leftovers
EMR list-clusters --active: none. ops tasks RUNNING: none (only the 3 service tasks). Neptune: 1 instance, db.t4g.medium, available.

## Run records (Phase 14)
Every *.started.json has a final record (no orphaned runs). upload-input 2 ok, 2 failed (exit 5, before G-07); hadoop 2 failed then 20261118T212320Z and 20261119T005309Z ok; er 20261119T020635Z ok; spark 034306Z failed (6), then 055149Z (graph) and 062721Z (export used by all loads) ok. D13a 224120Z failed at run-task (5), 224826Z ok. D13b 20261120T005004Z ok. D13c 010712Z ok; 011009Z refused (duplicate-start guard). D13d nodes 5b12432c: 2 polls in progress, LOAD_COMPLETED 014325Z. D13e edges 6c625089: 11 polls in progress; 041128Z INT (5), 042149Z one-ops-task (4), 042215Z INT wait-task (5); LOAD_COMPLETED 20261120T144658Z and 144821Z (exit 0). D13f 042327Z-…-880d timed-out (7), wait-task, 04:23:27Z-06:23:52Z, task cc856e65…, no program_lines; 251,094-document count and step-cap stop not in this record; logs:FilterLogEvents denied, so the count is the orchestrator's (from DynamoDB metrics and the human's masked log read), unverified by deploy-engineer.

## Rollback actions
None. Services stable on :2.

## Spec gate items
| Item | Result | Evidence |
|---|---|---|
| Approved plan checksum = release record | pass | approvals.md:45, release d539, plan record ba345f8 |
| Clean tree, commit recorded | pass | release head 1671968; plan tree_clean true; D14 tree_dirty false |
| Account and Region | pass | fingerprint recomputed; us-east-1 |
| Digests three-way | pass | above |
| Revisions = release record | pass | above |
| Stabilization | pass | rollout COMPLETED, running = desired, 0 failed |
| Smoke test | pass | D14 7/7 |
| Alarms | unverified | access denied |
| No leftovers | pass | no active EMR, no running ops task |
| Rollback | none needed | |

## aws-api calls (14, shapes only)
1 ecs describe-services; 2 ecs list-tasks RUNNING; 3 ecr describe-images (tag BUILD); 4 cloudwatch describe-alarms DENIED; 5 emr list-clusters --active; 6 neptune describe-db-instances; 7 ecs describe-tasks (3); 8 ecs list-tasks --family ops RUNNING; 9-10 elbv2 describe-target-groups; 11-13 elbv2 describe-target-health; 14 logs filter-log-events DENIED.

## Unverified: CLI line for the human
`aws cloudwatch describe-alarms --region us-east-1 --query "MetricAlarms[].[AlarmName,StateValue,StateValue=='ALARM' && StateReason || '']" --output table`
