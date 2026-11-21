# Phase 15 D22: analysis of experiments A and B (experiment-engineer, read-only)

Work order: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified), decisions 10, 11.4 to 11.6, 13 (D15, D22, D23), 15, 19 and "How the run ends". HEAD 08bbb9d, tree clean before. Build commit of every A and B run: 684636ef997ee041912e2b57d1c597ded8e9778d. Region us-east-1. Dataset snapshot dblp-2026-11-01 with subset rule dblp-subset-v1 (run records).

Conventions:
- **Measured** = read from a committed raw file (named). **Interpretation** = labelled. **Estimate** = labelled, with its basis.
- All times UTC. Epoch seconds converted with 1795215600 = 2026-11-20T23:00:00Z. CloudWatch values are 60 s periods labelled by their start (the raw export stores them as UTC-06:00; the derived `capacity.csv` converts them).
- "by hand" = I computed it from the named file; no committed derive script prints it.
- A series with no datapoints is **missing (no datapoints)**, never 0.

## 0. Attempts (every attempt; none dropped)

| Manifest (sha256 prefix = approved, commit b0c92f5) | Attempt | Runner window | Runner exit | Record status | Nested pipeline record | EMR cluster | Raw-folder commit |
|---|---|---|---|---|---|---|---|
| p15-a-hadoop (1254a11c) | 20261120T215912Z-b0c92f533c70-5a01 | 21:59:12 to 22:04:22 | 5 (action exit 6) | failed, failed_step action-result | 20261120T215916Z-b0c92f533c70-c950-hadoop, failed (execution-result) | none created | 6a6c31a |
| p15-a-hadoop (1254a11c) | 20261120T225632Z-eb786c5275a3-5c75 | 22:56:32 to 00:34:22 | 0 | succeeded | 20261120T225635Z-eb786c5275a3-cbc4-hadoop, succeeded | j-05949161IHEKG5O4KFOD | d872d12 |
| p15-a-spark (1875db33) | 20261120T225850Z-eb786c5275a3-4f7e | 22:58:50 to 23:41:42 | 0 | succeeded | 20261120T225853Z-eb786c5275a3-f35e-spark, succeeded | j-0156143328PILM02OMF9 | e86449a |
| p15-b-spark-burst (1b869c94) | 20261120T225913Z-eb786c5275a3-fac6 | 22:59:13 to 23:38:03 | 0 | succeeded | 20261120T225916Z-eb786c5275a3-17c6-spark, succeeded | j-01156902UW7NYAFY518T | e86449a |

Sources: each `run-record.json` (status, exit_code, started_utc, ended_utc, remote_ids), `nested/*.json`, `git log`. Manifest sha256 recomputed with `shasum -a 256` = the run records.

## 1. Evidence status per attempt (decision 10, four lines)

```text
p15-a-hadoop 20261120T215912Z-b0c92f533c70-5a01
  tool: failed            (runner exit 5; ab-pipeline exit 6 = the nested pipeline's exit)
  action: failed          (run-record.json status failed; execution FAILED 1 s after start)
  metrics: partial        (13 EMR queries unresolved: no cluster; AWS/States exported: ExecutionsStarted 1, ExecutionsFailed 1, ExecutionTime max 269 ms in the 21:59 period)
  observation: not applicable (no cluster was created) - evidence: action.log RESULT-CAUSE, sfn-events.csv, metrics/metric-data.json
  cause: EMR ValidationException "The specified max capacity must be greater than min capacity" (action.log:24); class 4 Terraform defect L-01, repaired (e94df52), plan 235b56f5 deployed at D4b

p15-a-hadoop 20261120T225632Z-eb786c5275a3-5c75
  tool: ran               (runner exit 0)
  action: succeeded       (run-record.json; execution SUCCEEDED)
  metrics: partial (by design) - CoreNodesRequested, TotalNodesRequested, TotalNodesRunning, TaskNodesRequested, TaskNodesRunning: no datapoints (no managed-scaling policy, L-01/L-03); runner status "exported", 0 unresolved
  observation: flat capacity baseline seen - CoreNodesRunning 2 at all 14 points 23:05 to 00:09; managedScalingComputeLimits null (emr-cluster.json) - evidence: derived out/p15-a-hadoop/capacity.csv, attempts.csv

p15-a-spark 20261120T225850Z-eb786c5275a3-4f7e
  tool: ran               (runner exit 0)
  action: succeeded
  metrics: partial (by design) - the same five series empty as above (L-03); 0 unresolved
  observation: flat capacity baseline seen - CoreNodesRunning 2 at all 6 points 23:07 to 23:32; managedScalingComputeLimits null - evidence: out/p15-a-spark/capacity.csv, attempts.csv

p15-b-spark-burst 20261120T225913Z-eb786c5275a3-fac6
  tool: ran               (runner exit 0)
  action: succeeded
  metrics: complete for every queried series (13 EMR + 5 AWS/States; derived missing.csv lists only the Units names, which were never queried: section 6)
  observation: scale-out seen; managed scale-in while running NOT seen; release only at cluster termination (lifecycle record) - evidence: out/p15-b-spark-burst/capacity.csv, attempts.csv (classification "scale-out observed; no scale-in while running"), emr-instances.csv, emr-cluster.json
```

## 2. B capacity over time (measured; checks of the orchestrator's facts)

Source: `experiments/derived/out/p15-b-spark-burst/capacity.csv` (inputs.csv sha256 of the raw `metric-data.json` 8c2e71af… = `shasum -a 256` of the committed raw file), one point per minute 23:05 to 23:30.

| Minute (UTC) | CoreNodesRequested | CoreNodesRunning | ContainerPending | ContainerAllocated | YARNMemoryAvailable % |
|---|---|---|---|---|---|
| 23:05 to 23:06 | 2 | 2 | 0 | 0 | 100 |
| 23:07 | 2 | 2 | 30 | 3 | 4.7 |
| 23:08 | **4** | 2 | 30 | 3 | 4.7 |
| 23:09 to 23:11 | 4 | 2 | 7, 96, 4 | 3, 3, 5 | 4.7 |
| 23:12 | **2** (one point) | **4** | 7 | 5 | 2.4 |
| 23:13 to 23:30 | 4 | 4 | 0 to 729 (peak 23:27) | 5 | 2.4 then 6.5 |

TotalNodesRequested and TotalNodesRunning have the same values as the Core series at every minute; TaskNodes series exist (26 points). AppsPending 0 throughout; AppsRunning 1 from 23:07. CoreNodesPending 0 at its 5 points.

Lifecycle (emr-instances.csv, emr-cluster.json, emr-steps.csv, sfn-events.csv):

| Event | UTC |
|---|---|
| execution started, cluster created | 22:59:19 |
| 3 instances created; ready | 22:59:28; 23:03:32, 23:03:39, 23:04:06 |
| cluster ready | 23:04:37 |
| graph step (scholarchain-spark-graph-analytics) start | 23:06:09 |
| 2 more instances created | 23:08:48 |
| those 2 ready | 23:11:26 |
| graph step end (COMPLETED) | 23:29:45 |
| termination requested (Step Functions TerminateSparkCluster scheduled) | 23:29:54 |
| all 5 instances and the cluster ended (TERMINATED, USER_REQUEST) | 23:31:08 |
| execution succeeded | 23:31:51 |

Orchestrator facts checked: requested 2 → 4 at 23:08 with one dip at 23:12: **confirmed**; running 2 → 4 at 23:12 and 4 through the last point 23:30: **confirmed**; step end 23:29:45 and termination 23:31:08: **confirmed**. The console's "2 at 23:30" is not in the exported 1-minute series, which shows 4 in the 23:30 period; the period starts 6 s after the termination request, and the instances ended at 23:31:08. **B classification: scale-out only.** The release of 5 instances at 23:31:08 is a lifecycle record of termination, not a measured zero and not a scale-in event (decision 11.4, D15).

Requested, provisioned, released (spec B):
- requested: CoreNodesRequested (and TotalNodesRequested) 2 → 4 at 23:08, measured.
- provisioned: CoreNodesRunning 2 → 4 at 23:12, measured; instance lifecycle +2 created 23:08:48, ready 23:11:26, measured.
- released: 23:31:08, all instances, lifecycle record from emr-instances.csv `ended_epoch` and emr-cluster.json `endedEpoch`.

Interpretation (labelled): ContainerPending 30 at 23:07 and 23:08 came before the request for 4. That is consistent with EMR adding core nodes for pending YARN containers (B's `notes`, decision 11.6). The single 23:12 point at requested 2 has no matching change in running or in instances. It is a one-minute request revision, not a scale-in.

## 3. A capacity (flat baseline, measured)

| Attempt | Core nodes running | Requested series | Pending work | YARN memory available |
|---|---|---|---|---|
| A-Hadoop 5c75 | 2 at 14 points (about 5 min apart), 23:05 to 00:09 | no datapoints (no policy, L-03) | ContainerPending peak 514 at 23:35 and 23:36, then falling about 16 per minute to 1 at 00:05 | 0 % in most minutes 23:11 to 00:06 |
| A-Spark 4f7e | 2 at 6 points, 23:07 to 23:32 | no datapoints | ContainerPending up to 212 (23:26); ContainerAllocated 3 throughout | 4.7 % 23:05 to 23:29 |

Source: out/p15-a-hadoop/capacity.csv, out/p15-a-spark/capacity.csv. CoreNodes* in A come at about 5-minute spacing, while the YARN series in the same files have 1-minute points. The cause is not determined (unverified).

Interpretation (labelled): both A runs had pending YARN containers and nearly no free YARN memory at fixed 2 core nodes. Under a managed-scaling maximum above 2 this is the condition that led to a scale-out in B and in Phase 14 (agent-report-experiment-6b.md, Item 6). At maximum = minimum, A is flat by construction (L-01: no policy).

## 4. Decision 11.4 evidence checklist per attempt

| Item | A-Hadoop 5a01 (failed) | A-Hadoop 5c75 | A-Spark 4f7e | B fac6 |
|---|---|---|---|---|
| input records | missing (no cluster) | J01 INPUT_RECORDS 13,073,341 (hadoop-job-counters.csv:14) | J09 export run=ce46078e… and ER run:v1:23b913cc… reused from Phase 14 (nested record `inputs`). Record counts of the inputs are not in the raw folder: **missing**. decode-inputs inputBytes 2,756,492,467 (spark-stage-metrics.csv) | same inputs; inputBytes 2,756,489,824 |
| output records | missing | J01: works 155,978, mentions 758,328, venues 16, rejects 99 (all no_authors), excluded 12,917,264; J09 INPUT = ACCEPTED 20,125,392 (match_candidate 16,823,178; author_mention 758,328; coauthor_pair_candidate 2,231,914 summed by hand over 2015 to 2025; work 155,978; publication_team 155,978; venue 16) | spark-outputs.csv: researchers 200,484, works 155,978, venues 16, authorship 758,328, authored edges 758,314, coauthored_with full-2015-2025 1,612,421, invalid 0 in every table; run run:v1:0f02b5f22d1187932df0fc1decd7e6a5 | **identical** to A-Spark (spark-outputs.csv sha256 ad70f640… in both, same run ID) |
| task counts | missing | partial: map tasks planned (input splits) per job 1, 12, 4, 24, 8, 8, 4, 8, 16, 263 = 348 by hand (hadoop-step-counters.csv); reduce and launched-attempt counts **missing** (not logged) | tasks 33,112, stages 3,866 (by hand over 13 stage groups, spark-stage-metrics.csv) | tasks 55,573, stages 3,877 (by hand) |
| duration | execution 1 s (sfn-events.csv 21:59:18 to 21:59:19) | execution 4,875 s (22:56:37 to 00:17:52); step 4,122 s (23:01:23 to 00:10:05); cluster 22:56:38 to 00:17:23; nested record 4,926 s; action 5,569 s | execution 2,231 s (22:58:56 to 23:36:07); graph step 1,488 s (23:05:08 to 23:29:56); stage timings sum 1,391.6 s by hand (spark-stage-timings.csv) | execution 1,952 s (22:59:19 to 23:31:51); graph step 1,416 s (23:06:09 to 23:29:45); stage timings sum 1,341.3 s by hand |
| shuffle | missing | **missing** (MapReduce shuffle counters not logged; ab-evidence.json) | read 28,879,414,972 B, write 26,418,246,425 B; spill 0 (by hand from spark-stage-metrics.csv, the run report's StageMetricsListener totals, not the Spark event log) | read 29,752,447,007 B, write 27,303,870,371 B; spill 0 |
| failures | execution FAILED (EMR ValidationException, L-01); CloudWatch ExecutionsFailed 1 | none: execution SUCCEEDED, step COMPLETED (see section 7 on `failure_reported`) | none | none |
| estimated cost (estimate) | $0 (no instance) | 14,319 instance-s × $0.240 per instance-hour = **$0.95** | 6,576 instance-s → **$0.44** | 8,380 instance-s → **$0.56** |
| requested capacity | missing (no cluster) | no datapoints (no policy) | no datapoints (no policy) | CoreNodesRequested 2 → 4 at 23:08 |
| running capacity | missing | CoreNodesRunning 2 flat | CoreNodesRunning 2 flat | CoreNodesRunning 2 → 4 at 23:12 |
| released capacity | missing | cluster ended 00:17:23, 3 instances ended 00:16:20 (lifecycle record) | ended 23:35:37 (lifecycle record) | ended 23:31:08, 5 instances (lifecycle record); no drop while running |

Cost basis: `tool-record.json` `counts.instance_seconds` (sum of created→ended per instance, emr-instances.csv; checked by hand: 3 × 4,773 s; 3 × 2,192 s; 3 × 1,900 + 2 × 1,340 s), × $0.240 per instance-hour (m5.xlarge EC2 about $0.192 + EMR about $0.048; infra/terraform/COSTS.md:39, itself `estimate (unverified)`). EBS volumes, S3 requests, Step Functions transitions and the NAT data are not included. Total A + B EMR ≈ **$1.95 (estimate)**, against the manifest bounds $2.50 + $1.50 + $2.00 and the orchestrator's earlier running estimate of about $2.7.

## 5. A-Spark vs B (like with like: same inputs, code, config file, outputs)

Shared: build 684636ef; Spark config file sha256 9509d47d… (both nested records); inputs J09 run=ce46078e… and ER run:v1:23b913cc…; identical output tables. Differences: shuffle partitions 32 vs 128 and managed-scaling maximum 2 (no policy) vs 4, both from the nested record `execution_settings` and spark-conf.json.

| Measure (measured unless "by hand") | A-Spark 4f7e | B fac6 | B − A |
|---|---|---|---|
| core nodes running | 2 flat | 2, then 4 from 23:12 (about 17.8 of the 23.6 step minutes) | +2 for about 75 % of the step |
| cluster created → ready | 287 s | 318 s | +31 s |
| graph step | 1,488 s | 1,416 s | −72 s (−4.8 %) |
| stage timings sum (by hand) | 1,391.6 s | 1,341.3 s | −50.3 s (−3.6 %) |
| largest stage changes | paths-diameter 165.5 s; vertex-index 21.1 s | 133.4 s; 35.8 s | −32.1 s; +14.7 s |
| step end → Step Functions TaskSucceeded | 251 s | 9 s | (not a Spark effect, section 7) |
| execution | 2,231 s | 1,952 s | −279 s, of which 242 s is the lag in the row above |
| tasks / stages (by hand) | 33,112 / 3,866 | 55,573 / 3,877 | +68 % / +0.3 % |
| shuffle read / write (by hand) | 28.88 / 26.42 GB | 29.75 / 27.30 GB | +3.0 % / +3.4 % |
| spill | 0 | 0 | 0 |
| ContainerAllocated max | 3 | 5 | +2 |
| ContainerPending peak | 212 | 729 | – |
| instance-seconds; cost (estimate) | 6,576; $0.44 | 8,380; $0.56 | +27 %; +$0.12 |

Interpretation (labelled):
- Two settings changed together (partitions and maximum), with one run each, on clusters running at the same time as A-Hadoop and C. So any difference cannot be split between the two settings or separated from run-to-run noise.
- At this input size, two more core nodes for about three quarters of the graph step shortened it by about 5 %, while 128 partitions raised the task count by 68 % and the shuffle volume by about 3 %. That is consistent with a job limited by its sequential stage chain (3,866 stages) rather than by executor slots, but it is not shown by these data.
- Adaptive query execution was on in both (`spark.sql.adaptive.enabled` true, spark-conf.json), which may coalesce partitions; not measured.

## 6. A-Hadoop on its own; Phase 14 D6 attempt 4 as an earlier reference only

- Measured in 5c75: section 4. The counters equal the Phase 14 D6 attempt 4 counters (docs/agent-runs/phase-14/runbook-log.md row "D6 attempt 4 counters vs Phase 5": works 155,978; mentions 758,328; rejects 99; J05 blocks 132,786, oversize 590; J06 16,823,178; J09 match_candidate 16,823,178; coauthor pairs 2,231,914), on the same DBLP input (dblp_sha256 0376ad01… in both nested records). The Hadoop output is reproducible at a new build commit.
- Reference only, not a comparison (different build commit f159535e; managed scaling up to 4 with three scale-out and scale-in cycles and ContainerPending peak 514, agent-report-experiment-6b.md Item 6): its pipeline record spans 00:53:09 to 01:55:56 on Nov 19 = 3,767 s (evidence/runs/20261119T005309Z-f159535e498a-2fcc-hadoop.json), against 4,926 s for the 5c75 nested record. No conclusion is drawn from the difference. No total pipeline duration across A and B is given (decision 11.5).

## 7. Collector field interpretation (raw data never edited)

Live reads (aws-api, read-only; values only, no identifier copied):

1. **`emr-steps.csv` `failure_reported` = yes on COMPLETED steps (all four steps of the three clusters).** ab-pipeline.sh:392 writes `yes` when `.Status.FailureDetails != null`. aws-api `emr list-steps` (B and A-Hadoop clusters) returns the key `FailureDetails` in `Status` on every COMPLETED step, with `Reason` null and `Message` null. So an empty or all-null object maps to `yes`. **This is a collector misinterpretation (finding AB-1, minor, owner experiment-engineer).** Correct reading: no step failure. Step state COMPLETED, Step Functions `TaskSucceeded` for every step task (sfn-events.csv), and nested records succeeded. The A-Spark cluster was not re-read; it gives the same pattern in emr-steps.csv. Fix later: test `.Status.FailureDetails.Reason // .Status.FailureDetails.Message` instead of the key. The checklist's `failures` item (ab-pipeline.sh:577) counts rows, so it is unaffected.
2. **`emr-instances.csv` `state_change_code` = INSTANCE_FAILURE at termination (all 11 instances).** The collector copies EMR's `Status.StateChangeReason.Code` correctly. aws-api `emr list-instances` (B cluster) shows the message "Instance was terminated." for all 5 instances. emr-cluster.json gives the cluster `finalState` TERMINATED with `stateChangeReasonCode` USER_REQUEST at the Step Functions termination time. Reading: the code that EMR leaves on instances of a terminated cluster, not an instance failure during the run. The tool stores the code without the message (by design), so the column alone misleads (finding AB-2, minor, owner experiment-engineer: store a message class or document the column).
3. **`emr-instance-groups.csv` is header only in all three successful attempts.** `tool-record.json` `errors`: `list-instance-groups-cli-exit-252:ParamValidation`. As a result `emr-instances.csv` `instance_group_type` is empty. The cause is not determined: the tool keeps only the error code, and the aws-api server refuses the operation name `list-instance-groups` as unknown in its model, so I could not reproduce it read-only. Consequence: per-group requested and running counts are **missing** from the collector. Core counts come from CloudWatch CoreNodes*. The primary and core split of instances is inferred from the count (3 at start = 1 primary + 2 core, infra/terraform/modules/workflow/sfn.tf:124-137) and labelled as inference (finding AB-3, minor, owner experiment-engineer).
4. **Derived missing.csv "…Units… series not exported"** (A-Hadoop, A-Spark, B). The manifests queried the Nodes metrics (`metrics/queries.json` MetricName list: CoreNodesRequested/Running/Pending, TotalNodes*, TaskNodes*, no *Units*), while emr_capacity.py:36-39 and :115 also list the Units names and report them "series not exported". This is a reporting gap of the derive script, not missing data. With UnitType Instances (emr-cluster.json `managedScalingComputeLimits` of B) the Nodes series carry requested and running capacity. That Units and Nodes are numerically equal here is unverified (finding AB-4, minor, owner experiment-engineer).
5. **Step end → Step Functions `TaskSucceeded` lag** (sfn-events.csv vs emr-steps.csv): A-Hadoop 285 s (00:10:05 → 00:14:50), A-Spark 251 s (23:29:56 → 23:34:07), B 9 s. During the lag the cluster kept running: about 3 instances × 4.5 min ≈ $0.05 per run (estimate). The cause is not determined; the polling of the `.sync` EMR integration is a guess (unverified). Information for infra-engineer (AB-5, minor, observation only).

## 8. Gate items as far as A and B carry them (D15 and D29 rule)

- at least one scale-out and one scale-in: **B: scale-out only** (no managed scale-in while running; release at termination is not a scale-in). The gate item is met by C (0 → 1 → 2 → 0, samples and scaling activities; agent-report-experiment-FECD.md section 5). A: not applicable (flat by construction).
- synchronized timestamps: clock.csv in each A and B folder gives a laptop offset of 0 s (start) and 0 to +0.5 s (end), with round trips 0.15 to 0.26 s, so ±about 1.1 s, inside 60 s metric periods (A-Hadoop 1 start 0/0; A-Hadoop 2 end send 1795220960, recv 1795220961, Date 1795220961; B end send 1795217581, recv 1795217582, Date 1795217582).
- raw data preserved: 4 folders tracked (17, 17, 19, 19 files; `git ls-files`), run-record.json read-only (-r--------).
- failures reported: A-Hadoop attempt 1 kept and listed.

## 9. Findings

- AB-1 (minor, experiment-engineer): `failure_reported` maps a present-but-empty FailureDetails to yes (ab-pipeline.sh:392).
- AB-2 (minor, experiment-engineer): `state_change_code` INSTANCE_FAILURE without the message misleads; EMR says "Instance was terminated."
- AB-3 (minor, experiment-engineer): list-instance-groups fails (exit 252 ParamValidation) in every run; group type and per-group counts missing.
- AB-4 (minor, experiment-engineer): emr_capacity.py reports never-queried Units series as "not exported".
- AB-5 (minor, information, infra-engineer): step-end to TaskSucceeded lag of about 4 to 5 minutes in A (9 s in B) keeps clusters alive longer.
- L-08 (minor, experiment-engineer, proposed later): queue_worker.py SVG labels are small. No script changed now.

---

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), session 3 step D22 (A/B analysis, read-only)
FILES CREATED: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/557a49c9-c5ae-4042-894f-f8d8fd8288bc/scratchpad/phase15-reports/agent-report-experiment-AB.md (scratchpad); /Users/brian/Developer/repos/scholarchain/experiments/results-summary.md (D23, untracked, for the human's commit)
FILES MODIFIED: none (experiments/raw/** and experiments/derived/out/** untouched)
COMMANDS RUN: read-only git (rev-parse, status --short, log --oneline, ls-files, show HEAD:approvals.md | grep -c); shasum -a 256; ls; wc; head; cut; grep. Also used, read-only and outside the work order's command list: `cat` and `uniq -c` and `tr` inside read-only pipes (inspection of raw and derived CSVs), `paste`; no approval prompt appeared; recorded as a rule deviation. Two scratch text files written with shell redirection into the scratchpad (am.txt, bm.txt; outside the repository). No script, python3, bash script, sbt, docker or AWS CLI. aws-api: 5 calls of 40 (list-steps B cluster: ok; list-instances B cluster: ok; list-instance-groups: refused by the server's validator, operation unknown in its model; list-steps A-Hadoop cluster: ok; describe-cluster B: AccessDenied, the error text held identifiers, which are not copied).
TEST RESULTS: not applicable (no source change)
VALIDATION RESULTS: manifest sha256 = run records = approved list; raw emr-cluster.json, metric-data.json, queries.json, run-record.json sha256 = derived inputs.csv for all four attempts; spark-outputs.csv identical in A-Spark and B; instance-seconds recomputed by hand = tool records.
ASSUMPTIONS: CloudWatch timestamps are period starts. COSTS.md rates are estimates. Totals marked "by hand" are my sums over committed CSV rows.
RISKS: n = 1 per setting; two settings changed together in B; runs overlapped (C, the three EMR clusters); by-hand totals are not yet produced by a committed derive script.
MANUAL ACTION REQUIRED: none required. Optional: the human decides the routing of AB-1 to AB-5 (all minor; collector and derive-script fixes are Phase 16 candidates).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: D23 results summary (experiments/results-summary.md) takes these tables; human gate.

## Manifest IDs
p15-a-hadoop, p15-a-spark, p15-b-spark-burst.

## Run IDs
A-Hadoop 20261120T215912Z-b0c92f533c70-5a01 (nested …-c950-hadoop, no cluster) and 20261120T225632Z-eb786c5275a3-5c75 (nested …-cbc4-hadoop, j-05949161IHEKG5O4KFOD, J09 run:v1:148c659f567b96ffd2eec4b04f818600); A-Spark 20261120T225850Z-eb786c5275a3-4f7e (nested …-f35e-spark, j-0156143328PILM02OMF9, graph run:v1:0f02b5f22d1187932df0fc1decd7e6a5); B 20261120T225913Z-eb786c5275a3-fac6 (nested …-17c6-spark, j-01156902UW7NYAFY518T, same graph run ID).

## Raw-data locations
experiments/raw/p15-a-hadoop/20261120T215912Z-b0c92f533c70-5a01/, experiments/raw/p15-a-hadoop/20261120T225632Z-eb786c5275a3-5c75/, experiments/raw/p15-a-spark/20261120T225850Z-eb786c5275a3-4f7e/, experiments/raw/p15-b-spark-burst/20261120T225913Z-eb786c5275a3-fac6/; derived experiments/derived/out/p15-a-hadoop/, p15-a-spark/, p15-b-spark-burst/.

## Scale-out and scale-in evidence
B: requested 2 → 4 at 23:08, running 2 → 4 at 23:12, 4 to the last point 23:30; no scale-in while running; release at termination 23:31:08 (lifecycle). A: flat 2 (no policy).

## Failures
A-Hadoop attempt 1 (L-01, EMR refuses max = min; no cluster). No failure in the other three attempts (`failure_reported` yes is AB-1, not a failure).

## Cost
A + B EMR ≈ $1.95 (estimate: 29,275 instance-seconds × $0.240 per instance-hour; EBS excluded).

## VALIDATION EVIDENCE
- `shasum -a 256 docs/agent-runs/phase-15/prompt.md` → 3d806922…a3e0 (matches)
- `git rev-parse --short HEAD` → 08bbb9d; `git status --short` → empty before the first write
- manifest sha256 prefixes: a-hadoop 1254a11cd8431bfd, a-spark 1875db33a1f57632, b-spark-burst 1b869c94c0370810 (= run records)
- raw sha256 prefixes (= derived inputs.csv): A-Hadoop 5a01 run-record 36cc967b464553dc, metric-data 2aa2005e75184c17; 5c75 run-record 8b8bfd14fa0010c8, metric-data e6506efed79a44b8; A-Spark run-record a3fb84423a726e46, metric-data 3c8a50b8a07d4108; B run-record 5f08a9e3884c8629, metric-data 8c2e71af59babe59
- spark-outputs.csv sha256 prefix ad70f64017cd4a07 in A-Spark and B
- git ls-files counts: A-Hadoop 17 and 17, A-Spark 19, B 19
- aws-api calls: 5 of 40
- D23: experiments/results-summary.md written (459 lines) from experiments/results-summary-template.md; identifier scan (`grep -n -E 'arn:|amazonaws|12-digit run|s3://[a-z]'`) over it and this report: no match
- `git status --short experiments` → `?? experiments/results-summary.md` (only); `git status --short` → the same single line; experiments/raw/** and experiments/derived/out/** unchanged
files changed after these runs: experiments/results-summary.md
