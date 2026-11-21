# D18 deployment check, deploy-engineer (Phase 15, decision 14)

STATUS: COMPLETE
OWNER: deploy-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified), decision 14 (:395-397) and the Standing rules (:59-93); session 3, step D18 (launched at D16). This was a read-only check. I ran no script and changed nothing.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- Read-only git: `git status --porcelain` printed nothing at the start and at the end; `git rev-parse HEAD` gave d872d120d404271f4371c1aed49959534617766a both times; `git log` on releases/course and on the two release commits.
- `shasum -a 256` on prompt.md; `grep`/`ls`/`head`/`tail -n`/`cut`/`wc` on the work order, approvals.md, the plan file names, the Terraform module `*.tf` files (modules only, never environments/course) and F's raw folder.
- Read tool on: both release records, the publish record, the L-01 plan record and plan summary, approvals.md:58-65, the Phase 14 deploy report, and the observability and autoscaling alarm `.tf` files.
- 24 aws-api calls. 1 was denied. They are listed below.
TEST RESULTS: n/a. No sbt, and no smoke test (decision 14 is read-only). The latest smoke runs are D5 and D6, which the orchestrator ran and checked.
VALIDATION RESULTS: Everything I could read passes. The live state matches release record 8ce8. Two items stay `unverified` because the read-only user is denied access: alarm states (cloudwatch:DescribeAlarms) and the state machine revision ID (states:DescribeStateMachine). CloudTrail and execution history still show that the L-01 definition update took effect.
ASSUMPTIONS: The 3 SUCCEEDED executions that started after the L-01 update are D7a attempt 2 (`p15-a-hadoop`, about 81 min), D11a and D11b (about 33-37 min each). I matched these on timing only, not on execution names. The 1 FAILED execution at 21:59:18Z, which lasted under 1 s, is D7a attempt 1 (the L-01 cause).
RISKS: Alarm states are not seen by me (human line below). The worker-scale-in alarm sits in ALARM whenever both queues are empty; that is its normal resting state, not a fault. The ECS container health shows UNKNOWN because no container health check is defined (same as Phase 14); ALB health is the health signal.
MANUAL ACTION REQUIRED: Run the alarm line below and compare it with the classification table. Optional: the state machine revision line.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: D18 orchestrator review of this report (stop before D19 only if the human's alarm output shows an alarm classified critical below), then D19 runtime-off and cleanup-mode plan.

## Release record (current: releases/course/20261120T225449Z-70876eb27b26-8ce8.json, L-01 state machine update)
- Commit/head 70876eb27b26. Plan commit e94df52ca8fb, plan id 20261120T223953Z-e94df52ca8fb-35bf. Build 684636ef997ee041912e2b57d1c597ded8e9778d. us-east-1, environment course, runtime_enabled true, verified true, applied 2026-11-20T22:54:49Z.
- The plan sha256 235b56f540bdc237ae511230736bbe184e420123609098ca809bac27b067de6d matches approvals.md:65 (`APPROVED-PLAN-SHA256`), the plan record and the plan summary.
- Plan summary: create 0, update 1, delete 0, replace 0, no-op 185. No IAM or resource-policy change, no network change, no stateful action. Tree clean true. Account shown only as the masked fingerprint (not copied).
- The previous revisions in this record equal the current ones (all :3), because L-01 changed only the state machine.
- The D4 release (releases/course/20261120T213452Z-94ec6b01a4ca-44da.json) set the image. Its plan sha256 934d78c3…4480 matches approvals.md:9. It moved query, evidence, graph and worker from :2 to :3 with build 684636ef. Phase 14 rollback target: f159535e at :2.
- Cost bound on record: approvals.md:1, $9 across AWS, Anthropic and Voyage.

## Services (cluster scholarchain-course-runtime, live vs record 8ce8)
| Service | Live task definition | Desired/running/pending | Deployments | Match |
|---|---|---|---|---|
| query | scholarchain-course-query:3 | 1/1/0 | 1 PRIMARY, COMPLETED, 0 failed | yes |
| evidence | scholarchain-course-evidence:3 | 1/1/0 | 1 PRIMARY, COMPLETED, 0 failed | yes |
| graph | scholarchain-course-graph:3 | 1/1/0 | 1 PRIMARY, COMPLETED, 0 failed | yes |
| worker | scholarchain-course-worker:3 | 0/0/0 | 1 PRIMARY, COMPLETED, 0 failed (last update 23:24:56Z, the scale-in after C) | yes |
- All 4 services are ACTIVE. Running tasks in the cluster: exactly 3, one per ALB service, each started about 21:32:49-21:32:55Z (the D4 rollout) with no restart since.

## Image digests (three-way)
All equal sha256:abb0be8094004494217b593dc22f760e27edc7b2d831911db7b89608bde364a3:
- Publish record evidence/packages/684636ef997ee041912e2b57d1c597ded8e9778d/publish/20261120T211540Z-85dacb447057-0e15.json: succeeded, exit 0, tree_dirty false, ecr.outcome pushed.
- ECR repository scholarchain-course-services, tag 684636ef… (its only tag), pushed 2026-11-20T21:15:49Z.
- The `app` container of all 3 running tasks (query, evidence, graph), each RUNNING.
- The worker:3 task definition's image tag is 684636ef… (it has no running task).

## Worker environment variable (names only)
The worker:3 task definition (ACTIVE, single container `app`) contains the name SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL. It has 18 environment names in total. No value was printed: the query selected names only.

## Health
- ALB target health: 3 target groups (evidence, graph, query prefixes), each on /healthz port 8080, each with 1 target in state `healthy`.
- ECS container health: UNKNOWN (no container health check defined, unchanged from Phase 14).
- Neptune: 1 instance, db.t4g.medium, available.

## Leftovers and state machine
- Active EMR clusters: 0.
- Running `scholarchain-course-ops` tasks: 0.
- State machine scholarchain-course-pipeline: 0 RUNNING executions.
- The latest 6 executions: 3 SUCCEEDED, started at 22:56:37Z, 22:58:56Z and 22:59:19Z and ended 23:31-00:17Z. Before those, 1 FAILED at 21:59:18Z (under 1 s), and 2 SUCCEEDED from Nov 19 (Phase 14).
- CloudTrail UpdateStateMachine events (time and name only):
  - 2026-11-20T22:54:39Z: L-01. This is 10 s before release 8ce8's applied time, and every later execution succeeded.
  - 2026-11-20T21:32:02Z: D4.
  - 2026-11-19T05:49:21Z: Phase 14.
- So the current definition is the L-01 one: updated, and no execution is running.
- The revision ID itself is `unverified` (DescribeStateMachine denied). Optional human line: `aws stepfunctions describe-state-machine --region us-east-1 --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-1 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn" --output text)" --query '[name,status,revisionId]' --output text`

## Queues (counts only)
- Embedding DLQ: 1 visible, 0 in flight.
- Embedding queue: 0 / 0.
- Jobs queue: 0 / 0.
- Jobs DLQ: 0 / 0.

## Active alarms
I could not read alarm state (ViewOnlyAccess has no cloudwatch:DescribeAlarms, Phase 14 D17), so I did not spend a call on it. CLI line for the human (it prints names, states and state times only, with no ARN and no reason text):

`aws cloudwatch describe-alarms --region us-east-1 --query "MetricAlarms[].[AlarmName,StateValue,StateUpdatedTimestamp]" --output table`

Classification of every alarm defined in Terraform (modules/observability/alarms.tf, modules/runtime/autoscaling.tf). The human should check each row against the line's output:
| Alarm | Expected state now | If in ALARM |
|---|---|---|
| scholarchain-course-embedding-dlq-not-empty | ALARM | Explained by experiment F. The poison message is kept in the DLQ by design. F's samples show OK before F and ALARM at 22:44:50Z (incident-F.json:5, "baseline + 1"), and the live embedding DLQ count is 1. Not critical. |
| scholarchain-course-jobs-dlq-not-empty | OK (jobs DLQ is 0) | Critical. Unexplained, stop before D19. |
| scholarchain-course-alb-5xx | OK | Critical if its state time is after the last experiment (D14). If its state time falls inside a D13/D14 load window, check against that run's records before deciding. |
| scholarchain-course-query-no-healthy-target, -evidence-no-healthy-target, -graph-no-healthy-target | OK (all 3 targets healthy at check time) | Critical. Stop before D19. |
| scholarchain-course-neptune-cpu-high | OK | Critical if its state time is current. If it is only during D13/D14, the run explains it. |
| scholarchain-course-worker-scale-in | ALARM (both queues empty) | Expected. This is the scale-in trigger's resting state with empty queues (treat_missing_data breaching). Not critical. |
| scholarchain-course-worker-scale-out, -worker-scale-out-embedding | OK (both queues 0 visible) | Critical if current, because there would be visible work with no worker. Live counts are 0, so this is not expected. |
| Any alarm name not in this table | — | Unclassified: stop and report it. |

## Phase 14 deploy items re-checked for the new image (684636ef)
| Item | Result | Evidence |
|---|---|---|
| Approved plan checksum = release record | pass | approvals.md:65 = 8ce8 = plan record; approvals.md:9 = 44da |
| Clean tree, commit recorded | pass | plan tree_clean true; publish tree_dirty false; my tree clean at d872d12 |
| Account and Region | pass (record level) | us-east-1 in all records; masked account fingerprint in the plan record. I did not recompute the salted fingerprint (no sts by rule). |
| Digests three-way | pass | above |
| Revisions = release record | pass | all :3; 1/1 x3, worker 0/0 |
| Stabilization | pass | every rollout COMPLETED, running = desired, 0 failed |
| ALB target health | pass | 3/3 healthy |
| Smoke test | not re-run (read-only order) | D5/D6 records are the orchestrator's |
| Worker env var by name | pass | present |
| Alarms | unverified | access denied; human line above |
| No leftovers | pass | 0 EMR, 0 ops tasks, 0 running executions |
| Rollback | none needed | |

## Rollback actions
None. The services are stable on :3.

## aws-api calls (24 of 25; shapes only)
1. ecs list-services
2. ecs list-tasks RUNNING
3. ecs list-tasks family ops RUNNING
4. ecr describe-images (tag)
5. emr list-clusters --active
6. stepfunctions list-state-machines
7. ecs describe-task-definition worker:3 (names query)
8. elbv2 describe-target-groups (names)
9. ecs describe-services (4)
10. ecs describe-tasks (3)
11. elbv2 describe-target-groups (identifiers)
12. stepfunctions describe-state-machine: DENIED
13. stepfunctions list-executions RUNNING
14. stepfunctions list-executions (6)
15-17. elbv2 describe-target-health x3
18. cloudtrail lookup-events UpdateStateMachine
19. sqs list-queues
20-23. sqs get-queue-attributes x4
24. neptune describe-db-instances

## VALIDATION EVIDENCE
- Work order sha256 3d806922…a3e0 = expected.
- HEAD d872d120d404271f4371c1aed49959534617766a. `git status --porcelain` was empty at the start and at the end.
- Release 8ce8: plan_sha256 235b56f5…de6d. Release 44da: 934d78c3…4480. Both match their approval lines.
- Digest abb0be80…64a3 in publish record = ECR = 3 running tasks.
- query, evidence, graph at :3 1/1/0; worker :3 0/0/0. All deployments COMPLETED, 0 failed.
- Worker env name SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL present.
- ALB targets 3/3 healthy.
- 0 active EMR clusters, 0 ops tasks, 0 RUNNING executions.
- UpdateStateMachine at 22:54:39Z; 3 executions after it, all SUCCEEDED.
- Embedding DLQ 1, other queues 0.
- Alarms and state machine revision ID unverified (access denied).
- 24 aws-api calls, 1 denied. No ARN, account ID, bucket name, queue URL or endpoint host is copied in this report.
- Finished: after the last command above.
