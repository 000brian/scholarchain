# agent-report-experiment-6b (Phase 15, session 2, step 6b)

interface: no (the fixed interfaces of decisions 7.2 and 9.3 are built as written; no producer mismatch)

**Decision 11.6 at the top: Phase 14 DID show EMR managed scaling.** Every Phase 14 cluster at managed-scaling maximum 4 went from 2 to 4 core nodes (requested and running), including D8 attempt 2 and D9 at 32 partitions; the "no scaling even at 200 partitions" case does NOT apply. Managed scale-in while running was seen in D6 attempt 4 (Hadoop) and D9 (Spark export), not in either D8 Spark run. Details in "Item 6".

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, checked with shasum at start), step 6b: decision 11 items 4-8 except E; exit-code table and D0a/D9a notes into experiments/README.md.
FILES CREATED:
- experiments/collectors/f-poison-replay.sh (NEW EXECUTABLE)
- experiments/collectors/c-embedding-backlog.sh (NEW EXECUTABLE)
- experiments/collectors/d-load.sh (NEW EXECUTABLE)
- experiments/collectors/ab-pipeline.sh (NEW EXECUTABLE)
- experiments/collectors/lib/collector-common.sh (sourced library, NOT executable)
- experiments/manifests/p15-a-hadoop.json
- experiments/manifests/p15-a-spark.json
- experiments/manifests/p15-b-spark-burst.json
- experiments/manifests/p15-c-embedding-backlog.json
- experiments/manifests/p15-d-query-load.json
- experiments/manifests/p15-d-read-load.json
- experiments/manifests/p15-f-poison-replay.json
FILES MODIFIED:
- experiments/README.md (layout rows; Phase 15 runner note; sections "Exit codes", "Collector contract", "Session 3 checks before the experiments")
COMMANDS RUN: shasum -a 256 (work order, final files); wc, ls, head, tail -n, cut, grep -n (literal words, read-only) over repository files; read-only git (git status --short, git -C ... status); shellcheck -x on the four collectors, the library and load-tests/query-load.sh; jq empty on the seven manifests and the schema. aws-api (read-only profile): 11 calls (see "aws-api calls"). No sbt, no script, no bash/python3, no aws CLI, no curl, no docker. Deviation to report: one Bash call began with a harmless variable assignment `cd_no=1;` / `cd_unused=1;` / `cat_no=1;` / `sed_no=1;` (no cd/cat/sed executed; leftover habit, no effect).
TEST RESULTS: not applicable (no Scala; no tool is run by an agent in this phase). Every new tool: runtime: untested.
VALIDATION RESULTS: shellcheck rc=0 on all six shell files; jq empty rc=0 on all eight JSON files; runner v1 checks verified by reading (below). Details in VALIDATION EVIDENCE.
ASSUMPTIONS:
- A1. The installed AWS CLI v2 has the `s3vectors` commands with `list-vectors --max-items/--page-size` and `get-vectors --keys ... --return-metadata --no-return-data` (unverified; D9a's preflights of C and F exercise both).
- A2. The worker's per-delivery log line carries the task ID and outcome under `taskId` and `outcome` (JSON object, or `key=value` / `"key":"value"` inside a text line) in streams `worker/...` of log group `log_group_names.ecs`. Names were fixed AFTER this step; they are ONE constant set, lib/collector-common.sh:530-532. FLAGGED FOR 6c.
- A3. Application Auto Scaling activity causes name the alarm or policy (`...-worker-scale-out[-embedding]` / `...-worker-out`, `...-worker-scale-in` / `...-worker-in`); otherwise direction is `unknown` (lib :485-511).
- A4. The Hadoop driver's step logs (syslog or stderr) contain JobSubmitter "number of splits:N" lines; otherwise task counts are `missing`.
- A5. The handler accepts a vector whose stored `embeddingModel` and `embeddingDimensions` equal config/course.json `models.embeddingModelId` and `embedding.dimensions` (storage VectorReembedder.scala:349-352); C and F refuse (exit 3, nothing sent) otherwise.
RISKS:
- R1. Worker log format (A2): if 6c finds other names, only lib :530-532 change (a tool change: no manifest change). Unreadable logs make F part 2's replay `unverified` and C's worker-outcomes `missing`.
- R2. Worker log reads use no CloudWatch filter pattern (the term-matching rules for hyphenated task IDs are not certain); F reads at most 2,000 events per poll from its send time minus 60 s, C at most N x 5 + 2,000 events within 300 s. A busier worker stream than that leaves lines unread (recorded as an error).
- R3. AWS CLI v2 timestamps are parsed in jq from ISO 8601 with any zone offset or epoch numbers (lib :70-85); an unexpected format gives an empty cell (missing), never 0.
- R4. The D manifests name the Neptune cluster by its literal DBClusterIdentifier from list-metrics (`scholarchain-course-graph-` plus a generated suffix): a name, not a host, and not equal to any registered identifier; R-2 may ask for a placeholder instead (would need infra-engineer; proposed later).
- R5. C's "two-task scaling uncertain": no N <= 20,000 outlasts the second scale-out under the high rate estimate; N = 10,000 is provisional (6c sets it).
- R6. F's poison limit assumes the release-at-once rule (decision 7.2); without it the poison is reported "not seen within 960 s".
- R7. 6a's derive scripts do not read several new raw files (settle.csv, sends.csv, sample-check.csv, worker-outcomes.csv, clock.csv of C/F/A/B, emr-instances.csv, emr-instance-groups.csv, emr-steps.csv, sfn-events.csv, hadoop-*.csv, spark-*.csv, ab-evidence.json); they stay raw evidence for the D16/D22 analysis (proposed later: derive coverage). emr_capacity.py prefers TotalUnits*/CoreUnits* (not published for these instance-group clusters) and falls back to CoreNodes*, which the manifests export.
- R8. The F DLQ check reads the DLQ with `receive-message --visibility-timeout 0` (never deletes); it raises the DLQ messages' receive counts (the DLQ has no redrive, so nothing moves).
- R9. ab-pipeline.sh waits up to 900 s for the cluster end and up to 600 s per missing step log; a long cluster end only delays the extraction.
- R10. AWS/ECS `LiveTaskCount` exists in this account (seen for query, evidence, graph) although Container Insights is off; it is exported as a supporting series only; task counts stay the collectors' describe-services samples.
MANUAL ACTION REQUIRED:
- chmod +x before the session 2 commit (decision 16 step 2): experiments/collectors/f-poison-replay.sh experiments/collectors/c-embedding-backlog.sh experiments/collectors/d-load.sh experiments/collectors/ab-pipeline.sh (and load-tests/query-load.sh from 6a). Not lib/collector-common.sh.
- D9: sign the seven manifests after 6c's final C values (p15-c-embedding-backlog changes in 6c).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 (code-reviewer: 5a, 6a, 6b, with the compatibility matrix); 6c (worker log names from step 4's INTERFACE HANDOFF, C's final N and wait limit, the E collector and manifest); D0a and D9a in session 3.

## INTERFACE CHECK (producers read at their files; every name used exists as handed over)

runner, scripts/run-experiment.sh (infra 5a):
- action invocation `./${path}` with resolved args: run-experiment.sh:657, :666-682 (invoke_action); env SCHOLARCHAIN_EXPERIMENT_RUN_DIR, _ATTEMPT_ID, _MANIFEST_ID :676-678; stdin /dev/null :679; output masked into action.log :679-680. OK
- `{attempt-id}` resolution :566-571; refusal of any other `{...}` except `s3://{data-bucket}` :484-497. OK (manifests use only these two)
- `--check` appends `--preflight` LAST, RUN_DIR temporary outside the repo, 600 s :911-951 (:944), PREFLIGHT_TIMEOUT :30. OK
- nested records: `^RUN-RECORD: ` lines, path under evidence/runs/, `.run_ids.execution_name`, `.run_ids.emr_cluster_ids[]` :719-752; `EXECUTION: name=` :745-750. OK
- metric placeholders `{state-machine-arn}`, `{emr-cluster-id}`, `{alb-dimension}`, `{alb-target-group-ROLE}` :784-804, :888-909. OK
- export for every ended action, 300 s settle :808-816, :818-882, :1068-1071; exit codes :219-235, finish_action :706-717, finalize :983-1034 (exit 8 :1010, :1019). OK
- record fields the derive scripts read (6a): `.status`, `.exit_code`, `.action.window_epoch.start|end`, `.metrics.unresolved` in base_record :596-630; run-record.started.json :587; metrics/queries.json and metric-data.json :820, :873; re-export folder `ATTEMPT-metrics-UTCSTAMP` :1056. OK (6a's raw names and columns unchanged)
pipeline, scripts/run-bounded-pipeline.sh (infra 5a, decision 9.3 fixed interface):
- `--emr-max-units` :431 (check :477-490), `--spark-shuffle-partitions` :432, `--hadoop-output` :409, `--out-root` :420, `--hadoop-extra-arg` :411 (DTD as two extra args, usage :133-138), `--wait-timeout` :404 (hadoop/spark 60..44000 :588), `--snapshot` :403, `--hadoop-export` and `--identity-assignments` (usage :192-195). OK
- record: RUN-RECORD line :1187; record_json :1148-1164; `inputs.execution_input` :1222; `run_ids.emr_cluster_ids` :1212; `run_ids.hadoop_runs` :1362; `run_ids.graph_runs_under_out_root` :1616; ER check require_er_succeeded :1558-1570; dirty-tree refusal (usage :239). OK
Terraform outputs (5a and existing; infra/terraform/environments/course/outputs.tf): api_url :17, ecs_cluster_name :37, ecs_service_names :42, state_machine_arn :79, job_queue_url :84, embedding_queue_url :89, queue_names :94, dlq_urls :99 (new), alb_arn_suffix :104 (new), target_group_arn_suffixes :109 (new), data_bucket_name :116, emr_log_uri :126 (value `s3://BUCKET/logs/emr/`, modules/data-lake/outputs.tf:19-22), vector_bucket_name :131, dynamodb_table_names :136 (keys = logical names, modules/dynamodb/outputs.tf:4-7), log_group_names :173 (key `ecs`). OK
Names: alarms `scholarchain-course-worker-scale-out`, `-worker-scale-out-embedding`, `-worker-scale-in` (modules/runtime/autoscaling.tf:122, :144, :164), `scholarchain-course-embedding-dlq-not-empty` (modules/observability/alarms.tf:70); ECS cluster `${name_prefix}-runtime` (runtime/main.tf:125), services named by role (runtime/main.tf:264); queue names `${name_prefix}-${key}` and `-dlq` (workflow/main.tf:36, :46); queue settings embedding_visibility_seconds 300, embedding_max_receive_count 5 (workflow/variables.tf:132-142). OK
storage (re-embed metadata for C's 20-key sample and F's key): RequiredKeys modules/storage/.../ingest/VectorReembedder.scala:399-415; + researcherId in the researcher-profile index :338-340; model/dimension check :349-352; TaskIdPattern :396; completion record key TableSpecs.scala:263-265 with Hashing.canonicalEncoding modules/core/.../Hashing.scala:38-44, attributes pk/sk KeyValueStore.scala:45-46, table QuerySession (storage report "Completion record (QS6)"). OK
6a (own): raw file names and headers service-samples.csv, queue-samples.csv (roles embedding, embedding-dlq, job, job-dlq: queue_worker.py:33), scaling-activities.csv, alarm-samples.csv, events.csv (failure_timeline.py:33-34), incident-F.json, emr-cluster.json (fixture layout); query-load.sh options and files (load-tests/query-load.sh:81-153, out dir from SCHOLARCHAIN_EXPERIMENT_RUN_DIR :391). OK
Fixed interface embedding-task-v1 (decision 7.2, built as written): exactly `schema`, `taskId`, `index`, `vectorKey`: f-poison-replay.sh:273-276, c-embedding-backlog.sh:331. rag has not written the handler yet (checked: no `embedding-task-v1` under modules/rag/src/main).

## Status per item

- 11.4 f-poison-replay.sh: done. experiments/collectors/f-poison-replay.sh: limits from queue settings :174-192; baseline :242-268; poison + valid sent back to back :605-608; part 1 :296-336; part 2 (worker log, completion record, duplicate only after processed) :346-448; part 3 API :450-484; recovery :486-516; incident-F.json :518-545; preflight :547-567.
- 11.4 c-embedding-backlog.sh: done. settle (30 s, 15 min, reasons, exit 3) :178-242; baseline incl. DLQ :244-262; selection (first N keys, exactly N unique, exit 3) :264-282; 20-key metadata sample (exit 3) :284-322; send 10 per call :324-386; 15 s samples and wait limit :388-420 (exit 7 :546); scaling activities :529; worker outcome counts :422-447; observed sequence said plainly :449-473; --count / --wait-limit-seconds parsed and bounded :107-135.
- 11.4 d-load.sh: done. d-load.sh: args passed to query-load.sh :63-81, :132; 15 s samples of query, evidence, graph :85-87, :130; exit pass-through :149; preflight :113-127.
- 11.4 ab-pipeline.sh (with --preflight): done. ab-pipeline.sh: args unchanged :103-152, :249; preflight :194-237; nested record by output root :260-287; Step Functions history and termination request :289-323; EMR cluster, groups, instances, steps, managed-scaling limits :325-417; Hadoop _job.json counters :419-443; step-log splits :445-499; Spark run report and manifest :501-549; evidence checklist :551-614.
- 11.5 manifests: done, seven files (per-manifest lines below); E is 6c.
- 11.6 Will B scale?: done (below; B's `notes`).
- 11.7 compatibility matrix: done (below).
- 11.8 validation: done (VALIDATION EVIDENCE).
- experiments/README.md exit-code table with the 5a corrections and notes: done, experiments/README.md:113-127; collector contract :129-136; D0a/D9a notes :138-141.

## aws-api calls (11 of 30; names, list-metrics checks, Phase 14 EMR series only; no ARN, account ID, bucket name, queue URL or host copied)

1 list-metrics AWS/ElasticMapReduce for j-07168191925RA7C2EGJU (metric names); 2 get-metric-data for the four Phase 14 clusters; 3-5 list-metrics AWS/SQS, AWS/ECS, AWS/ApplicationELB (names and dimension names); 6-9 list-metrics AWS/DynamoDB, AWS/Neptune, AWS/States, AWS/ApiGateway; 10 ECS ClusterName value; 11 DynamoDB SuccessfulRequestLatency Operation values.

## Item 6 (Will B scale?), measured from Phase 14 CloudWatch (get-metric-data, Maximum, 60 s, 2026-11-19 00:45-07:05 UTC)

Only the `*Nodes*` managed-scaling series exist for these instance-group clusters (CoreNodesRequested, CoreNodesRunning, CoreNodesPending, TotalNodes*, TaskNodes*); `TotalUnitsRequested`, `TotalUnitsRunning`, `CoreUnits*` are not published (list-metrics), so the manifests use the Nodes series.
- D6 attempt 4 Hadoop, j-01672061XGBN8B8KJPEL (max 4): CoreNodesRequested 2 -> 4 -> 2 -> 4 -> 2 -> 4 -> 2 over 56 points; CoreNodesRunning 2 -> 3 -> 4 -> 2 -> 4 -> 2 -> 4 -> 2 (scale-out and managed scale-in while running, three times); ContainerPending peak 514.
- D8 attempt 1 Spark, 200 default partitions, j-01235291JKPRGVYUG1IA (failed run): requested 4 from minute 3, running 4 from minute 7 to the end of the series (24 points); ContainerPending peak 454; no drop while running.
- D8 attempt 2 Spark, 32 partitions, j-07168191925RA7C2EGJU: requested 4 from minute 5 (one point back at 2), running 4 from minute 8 to the end (26 points); ContainerPending peak 230; no drop while running.
- D9 Spark export, 32 partitions, j-07896861PI3CBWFUSGA: requested 2 -> 4 -> 2 -> 4 -> 3 -> 2 -> 4 -> 2; running 2 -> 4 -> 3 -> 4 -> 3 -> 4 -> 3 (17 points; drops 4 -> 3 while running).
Reading: requested and running rose above 2 in all four. B (maximum 4, 128 partitions) is therefore expected to request and run more than 2 core nodes; a managed scale-in while running is possible (seen in D6a4 and D9) but not seen in either D8 Spark run. Expectation, not a promise; B is observational and never re-run for not scaling. A at maximum 2 is flat by construction. B's `notes` state the EMR condition (YARN pending containers from Spark dynamic allocation; EMR managed-scaling page cited by infra 5a) and the reporting rule ("no scale-out observed" with the series; release at termination is a lifecycle record, not a scale-in).

## Per-manifest cost line and metric query status

| Manifest | costBoundUsd | Basis (all estimate) | maxDurationSeconds |
|---|---|---|---|
| p15-a-hadoop | 2.5 | expected about $1.20 (about 1.6 h x 3 m5.xlarge x $0.24, COSTS.md:39); remote upper bound about $3.10 | 18,600 |
| p15-a-spark | 1.5 | expected about $0.65 (about 50 min x 3 instances); upper bound about $3.10 | 17,400 |
| p15-b-spark-burst | 2 | expected about $0.80 (about 40 min x up to 5 instances); upper bound about $5.10 (above the $5 runner cap: D11b attended stop rule) | 17,400 |
| p15-c-embedding-backlog | 0.5 | central about $0.10, low-rate upper bound about $0.20 (Voyage about 0.9 M tokens free, PutVectors, DynamoDB, SQS, worker up to 2 tasks about 1.4 h); PROVISIONAL N 10,000, wait 5,000 s | 7,500 |
| p15-d-query-load | 1 | upper bound 240 x $0.0023 = $0.55, expected $0.15-0.20 (6a) | 2,400 |
| p15-d-read-load | 0.2 | about $0.05-0.10 (Neptune I/O assumption, 6a) | 2,400 |
| p15-f-poison-replay | 0.1 | under $0.05 (one LLM query, two embeddings, one worker task 20-30 min) | 4,800 |

Metric query status (each manifest's `notes` repeats it):
- seen in account: AWS/ElasticMapReduce CoreNodesRequested, CoreNodesRunning, CoreNodesPending, TotalNodesRequested, TotalNodesRunning, TaskNodesRequested, TaskNodesRunning, ContainerPending, ContainerPendingRatio, ContainerAllocated, AppsPending, AppsRunning, YARNMemoryAvailablePercentage (JobFlowId); AWS/States ExecutionsStarted/Succeeded/Failed/TimedOut, ExecutionTime (StateMachineArn); AWS/SQS Visible, NotVisible, AgeOfOldest, Sent, Received, Deleted, EmptyReceives for scholarchain-course-embedding, -embedding-dlq, -jobs, -jobs-dlq; AWS/ApplicationELB TargetResponseTime, RequestCount, HTTPCode_Target_5XX_Count, HTTPCode_Target_4XX_Count (LoadBalancer, TargetGroup); AWS/ECS CPUUtilization, MemoryUtilization, LiveTaskCount (ClusterName scholarchain-course-runtime, ServiceName query/evidence/graph; the worker's series not listed yet: never ran in the retention window); AWS/DynamoDB ConsumedRead/WriteCapacityUnits, SuccessfulRequestLatency (TableName, Operation GetItem/PutItem/Query as listed); AWS/Neptune CPUUtilization, OpenCypherRequestsPerSec, TotalRequestsPerSec, MainRequestQueuePendingRequests, TotalServerErrorsPerSec (DBClusterIdentifier).
- docs only (cited, not re-read in 6b; this agent has no aws-docs tool): AWS/ApplicationELB HTTPCode_ELB_5XX_Count (LoadBalancer; same metric as the alb-5xx alarm, observability/alarms.tf:108); AWS/DynamoDB ThrottledRequests (TableName, Operation) and ReadThrottleEvents (TableName): published only on throttling.
- dropped: EMR TotalUnitsRequested, TotalUnitsRunning, CoreUnits* (not published for instance-group clusters); AWS/ApiGateway (ApiId is the first label of the API host; no runner placeholder).
- placeholders: `{emr-cluster-id}` (A, B), `{state-machine-arn}` (A, B), `{alb-dimension}` and `{alb-target-group-query|evidence|graph}` (D; resolvable only after D4 creates the new outputs).

## Runner enforced checks, by reading, for each of the seven manifests (experiments/README.md rule table; run-experiment.sh check_v1 :426-479, check_placeholders :484-497)

format "scholarchain.experiment-manifest": checked; formatVersion 1 (number): checked; manifestId pattern and file name = manifestId.json: checked; example false (boolean): checked; experiment A/B/C/D/F: checked; description non-empty: checked; region us-east-1: checked; datasetSnapshot non-empty (names dblp-2026-11-01 and dblp-subset-v1): checked; costBoundUsd number > 0 and <= 5 (2.5, 1.5, 2, 0.5, 1, 0.2, 0.1): checked; maxDurationSeconds integer > 0: checked; resourceLimits object with terraformSource: checked; action.kind "command": checked; action.args array of strings: checked; action.path under experiments/collectors/, no "..": checked; metrics array: checked; placeholders in args only `{attempt-id}` (A and B out-roots) and `s3://{data-bucket}/`: checked; no field outside the schema: checked; approval line: pending D9 (human); tracked and executable action: pending the human's chmod and commit.

## Argument contract with the runner, read end to end (11.8)

The runner passes `action.args` with `{attempt-id}` replaced and nothing else changed (:566-571), runs `./experiments/collectors/NAME.sh` from the repository root (:657, :679) with the three SCHOLARCHAIN_EXPERIMENT_* variables (:676-678) and stdin /dev/null; `--check` appends `--preflight` last with a temporary RUN_DIR (:944). Each collector accepts `--preflight` anywhere and writes nothing then (lib cc_prepare_out :255-260; cc_file/cc_row/cc_json_file return early). Nested `RUN-RECORD:` lines: only ab-pipeline.sh starts a script that prints them, and it does not wrap that output (ab-pipeline.sh:249), so the runner's grep `^RUN-RECORD: ` (:744) sees them; d-load.sh's child prints none (query-load.sh). Exit codes: collectors exit 0, 2, 3, 5 or 7 (ab and d pass the child's code through); the runner maps 0 -> 0 (or 6 on partial metrics), 124/137 (its own timeout) -> 7, anything else -> 5 (:706-717), then exports metrics for every ended action (:1068-1071) and moves the folder (exit 8 if an identifier is found, :1016-1019). Each collector's own deadline is below its manifest's maxDurationSeconds, so the runner's TERM is the backstop; on TERM the lib hook gives a child up to 20-25 s (lib :140-152, :513-521; d-load.sh:92-94; ab-pipeline.sh:240-242), inside the runner's --kill-after 30 s.

## Compatibility matrix (R-2 checks it)

| Path | Implemented at | First executable test |
|---|---|---|
| runner -> collector arguments | run-experiment.sh:566-571 (resolve), :657 and :666-682 (invoke); collectors' parse_args (f :121-157, c :107-135, d :63-81, ab :103-152) | D9a (`--check` preflight), then D10 (F) |
| runner -> collector environment | run-experiment.sh:676-678; lib cc_init :211-252 (attempt ID), cc_prepare_out :255-286 (RUN_DIR, gitignored or outside the repo) | D9a, D10 |
| runner `--check` -> collector `--preflight` | run-experiment.sh:944 (last argument); lib :218-220 (detect), :257-260 (no writes); each tool's preflight (f :547, c :475, d :113, ab :194) | D9a |
| collector -> nested pipeline arguments | ab-pipeline.sh:103-152 (all arguments minus --preflight, unchanged), :249; pipeline options run-bounded-pipeline.sh:403-432 | D7a (p15-a-hadoop; ab-pipeline preflight at D9a) |
| nested pipeline -> record paths and run IDs | pipeline RUN-RECORD :1187 to the inherited stdout -> runner collect_nested :719-752 ({emr-cluster-id}); ab-pipeline find_record :260-287 (by output root, safe when D11a and D11b overlap) | D7a |
| collector -> load generator | d-load.sh:132 (query-load.sh with the same RUN_DIR); query-load.sh:391 | D9a (preflight :120), D13 |
| exit-code translation | collectors (f: 0/3/5/7; c :541, :546; d :149; ab :637); runner finish_action :706-717, finalize :983-1004; README table :113-127 | D9a (preflight exits), D10 |
| started, failed, timed-out, finished attempt folders | runner start_run :576-592, on_signal :259-265, finalize :983-1034; collector tool-record.json on every exit after the start (lib cc_on_exit :154-180, cc_write_tool_record :698-722) | first live run (D7a or D10) |
| metrics export for failed actions | run-experiment.sh:687-703 (no stop on failure), :808-816, :818-882, :1068-1071 | the first failed live run (none planned); D7a exercises the path |
| clean tree before and after preflight | collectors: temporary folder outside the repo (lib :240-243), no file in preflight; runner preflight folder outside the repo :928-933; query-load preflight mktemp | D9a (`git status --short` after) |
| masking in records and errors | lib cc_aws_raw :334-355 (error code only), cc_api_send :623-648 (key on stdin, URL never printed), URLs and bucket in variables only; no raw CLI output stored; runner mask_stream :679-680 and identifier check :1016-1019 (exit 8) | D9a output, D10 folder check |
| placeholders in action.args | runner :484-497; manifests use only `{attempt-id}` and `s3://{data-bucket}/` | D9a (`--check` runs the refusal and shows the substitution) |

## New executable files (the human sets the bit before the commit)

experiments/collectors/f-poison-replay.sh, experiments/collectors/c-embedding-backlog.sh, experiments/collectors/d-load.sh, experiments/collectors/ab-pipeline.sh (plus load-tests/query-load.sh from 6a). experiments/collectors/lib/collector-common.sh is sourced: no bit.

## Proposed later (not built)

- Derive coverage for the new raw files (R7), including clock.csv of C, F, A and B for the clock offset.
- A runner placeholder for the Neptune cluster identifier (R4), if R-2 or the human prefers it to the literal name.
- Reading reduce-task and shuffle counters for Hadoop would need the driver to log them (waitForCompletion(true) or counters in _job.json): hadoop-engineer, not this phase.

## INTERFACE HANDOFF

Files changed: as listed under FILES CREATED / FILES MODIFIED.

Common (lib/collector-common.sh, sourced from the collector's own folder):
- Environment read: SCHOLARCHAIN_EXPERIMENT_RUN_DIR (output folder; must be gitignored if inside the repo), SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID (task IDs, idempotency key), SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID (recorded), SCHOLARCHAIN_SERVICE_API_KEY (F part 3 and D; via curl config on stdin), TMPDIR.
- Tools required: git, jq, aws, terraform (output only), curl, awk, sort, cut, grep, date, shasum or sha256sum, timeout or gtimeout (exit 3 if missing); ab-pipeline also uses gunzip.
- Terraform outputs read (never printed): queue_names (prefix check), ecs_cluster_name, ecs_service_names, embedding_queue_url, job_queue_url, dlq_urls, vector_bucket_name, dynamodb_table_names.QuerySession, log_group_names.ecs, api_url, data_bucket_name, state_machine_arn, emr_log_uri.
- Worker log constants (FLAGGED FOR 6c): CC_WORKER_LOG_TASK_FIELD="taskId", CC_WORKER_LOG_OUTCOME_FIELD="outcome", CC_WORKER_LOG_STREAM_PREFIX="worker/" (lib :530-532); parser CC_WORKER_LOG_JQ (:539-548) prints "EPOCH TASK OUTCOME".
- tool-record.json: kind scholarchain-collector-tool-record, version 1: tool, tool_path, tool_sha256, arguments, attempt_id, manifest_id, region, start/end epoch, status (completed | prerequisite-not-met | wait-limit | failed | interrupted), exit_code, exit_meaning, limits, baseline, selection, counts, observation, evidence, errors (codes only), files (name, sha256).
- clock.csv (C, F, A/B): phase,local_send_epoch_s,local_recv_epoch_s,http_status,curl_exit,time_total_s,http_date,http_date_epoch_s (same columns as load-clock.csv; Date header of a public regional AWS endpoint).
- Exit codes: 0 ran, 2 usage, 3 prerequisite not met (nothing sent), 5 tool or AWS failure, 7 wait limit or TERM; d-load and ab-pipeline pass their child's code.

f-poison-replay.sh [--index NAME (researcher-profile-v1)] [--question-id qNN (q05)] [--preflight]
- Messages (embedding-task-v1, exactly schema/taskId/index/vectorKey): poison taskId `p15-f-poison-ATTEMPT`, index `p15-f-unknown-index`; valid `p15-f-replay-ATTEMPT` for the first key of --index; API Idempotency-Key `p15f-ATTEMPT-idem`.
- Files: events.csv (epoch_s,part,source,event,task_id,outcome,code; outcomes sent, dlq-count-baseline-plus-1, dlq-received, processed, replayed, unverified, not-seen variants; source worker-log or completion-record for processed/replayed; replayed only from worker-log), queue-samples.csv, service-samples.csv (worker), alarm-samples.csv (DLQ alarm and three worker alarms), clock.csv, incident-F.json (incidentId, startUtc, endUtc, detectionSource, detectedAtUtc, userVisibleImpact, traceAndRunIds, timeline, rootCause, recoveryAction, dataIntegrityAssessment, followUpOwner, preventiveTestOrControl, observation), tool-record.json.
- Limits: part 1 = 480 + (maxReceiveCount + 1) x 60 + 120 s (960 s at 5); part 2 waits 720 s each; recovery 600 s; DLQ alarm 900 s after the DLQ arrival; tool deadline 4,200 s.

c-embedding-backlog.sh --count N (1..20000) --wait-limit-seconds S (60..14400) [--index NAME] [--preflight]
- Task IDs `p15-c-ATTEMPT-I` (I = 0..N-1); selection: first N keys of --index in list-vectors order; 20-key metadata sample.
- Files: settle.csv (epoch_s,embedding_visible,embedding_in_flight,job_visible,job_in_flight,worker_desired,worker_running,worker_pending,scaling_in_progress,met,unmet_reasons), queue-samples.csv, service-samples.csv, alarm-samples.csv, scaling-activities.csv (start_epoch_s,end_epoch_s,service,direction,status_code), sends.csv (epoch_s,batch,entries,successful,failed,failed_codes), sample-check.csv (ordinal,eligible,missing_fields,model_check), selected-keys.txt (one key per line), worker-outcomes.csv (outcome,lines,distinct_task_ids,source), clock.csv, tool-record.json (observation.sequence: scale_out_0_to_1_epoch_s, reached_2_epoch_s, back_to_0_epoch_s, max_desired, said_plainly).

d-load.sh --mode query|read [query-load.sh options] [--preflight]: every argument except --preflight goes to load-tests/query-load.sh unchanged; files service-samples.csv (query, evidence, graph), tool-record.json, plus query-load.sh's files.

ab-pipeline.sh PIPELINE-ARGUMENTS... [--preflight]: only --stage hadoop (needs its own --hadoop-output) or spark (needs --out-root, --hadoop-export, --identity-assignments); S3 arguments in logical form; files emr-cluster.json ({clusters:[{clusterId, createdEpoch, readyEpoch, terminationRequestedEpoch, terminationRequestedSource, endedEpoch, finalState, stateChangeReasonCode, releaseLabel, managedScalingComputeLimits}]}), emr-instance-groups.csv, emr-instances.csv, emr-steps.csv, sfn-events.csv, hadoop-job-counters.csv, hadoop-step-counters.csv, spark-stage-metrics.csv, spark-stage-timings.csv, spark-outputs.csv, spark-conf.json, ab-evidence.json, clock.csv, tool-record.json.

Thread-safety: one process per collector plus one background sampler subshell (appends single CSV lines; errors to a private file merged at the end). Limits: every loop bounded by a deadline (and the sampler by a count); every AWS call under GNU timeout (120 s, reads retried 3 times; sends never retried as a whole).

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-15/prompt.md` -> 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (matches the work order)
- `shellcheck -x` experiments/collectors/f-poison-replay.sh, c-embedding-backlog.sh, d-load.sh, ab-pipeline.sh, lib/collector-common.sh, load-tests/query-load.sh -> rc=0, no output (after the last edit of each)
- `jq empty` on experiments/manifests/p15-a-hadoop.json, p15-a-spark.json, p15-b-spark-burst.json, p15-c-embedding-backlog.json, p15-d-query-load.json, p15-d-read-load.json, p15-f-poison-replay.json, experiments/manifest-schema-v1.json -> rc=0
- `git status --short experiments load-tests` -> ` M experiments/README.md`, `?? experiments/collectors/`, `?? experiments/derived/`, `?? experiments/manifests/p15-*.json` (7), `?? experiments/results-summary-template.md`, `?? load-tests/` (nothing outside my territory written by me)
- sha256 prefixes (final): ab-pipeline.sh 725cebaef1968513, c-embedding-backlog.sh 392cbf837a7d9440, d-load.sh 8932309cdf2344fc, f-poison-replay.sh 5631a0c673c7cbe9, lib/collector-common.sh 9e4879917be89b82, p15-a-hadoop.json 1254a11cd8431bfd, p15-a-spark.json 1875db33a1f57632, p15-b-spark-burst.json 1b869c94c0370810, p15-c-embedding-backlog.json daccf0c66dd8411d, p15-d-query-load.json 2af43f86a39c31b0, p15-d-read-load.json d851716dfbcd909f, p15-f-poison-replay.json f079543cccde90c8, experiments/README.md a0ee69758daebbe5
- No tool, script, sbt, aws CLI or python run; runtime of every collector: untested
- finished: after the last command above
files changed after these runs: none
