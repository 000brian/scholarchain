# G-21 diagnosis (read-only): Spark driver OOM on the first EMR run of the graph job (D8 attempt 1)

(Saved in substance by the orchestrator from the final messages of infra-engineer and spark-engineer, run in parallel, read-only, no files changed by either.)

Work order: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), session 3, G-21 diagnosis.
Evidence: /tmp/p14-D8-stdout.txt, /tmp/p14-D8-stderr.txt (bucket and 12-digit numbers masked by the human's command).

## Agreed findings (both agents)
- Driver heap about 2 GiB (EMR default): driver BlockManager/MemoryStore capacity 1048.8 MiB (stderr :89, :163) = (heap - 300 MiB) x 0.6. Phase 6 local run used 6 GB.
- Nothing sets Spark runtime conf: the Spark cluster has no Configurations (sfn.tf:96-118, :260-267), the step is a fixed `spark-submit --deploy-mode client --class … <jar> <job args>` with no --driver-memory or --conf (sfn.tf:303; export step :330), bootstrap writes no Spark settings (modules/emr/files/bootstrap.sh:9-17). The job sets only broadcast thresholds (GraphAnalyticsJob.scala:58-61, 78-80) and only records shuffle partitions (Pipeline.scala:1031-1041).
- Shuffle partitions: Spark default 200 (stderr "with 200 output partitions" x30, e.g. :199448); Phase 6 used 32 (LocalRealRun.scala:24, 37).
- Executors 4 cores, 9486 MiB (stderr :29); YARN max container 12288 MB (:41). Instances (environments/course/variables.tf): primary m5.xlarge x1 (16 GiB, :86-90), core m5.xlarge x2 (:92-96, :110-114), managed scaling 2-4 core nodes (:116-126), vCPU budget 32 (:128-132).
- Checkpointing is configured and works on EMR: the job sets checkpointDir = <out-root>/_checkpoint/run=<hex> (Pipeline.scala:306, 308), on S3 via EMRFS; 311 "Done checkpointing RDD … new parent is RDD n" lines (stderr :3097 to :220435), lineage cut each time. The >10,000 stage IDs come from about 3,439 jobs (per-iteration checkpoint/count jobs of components, label propagation, k-core, BFS over 5 windows, writes, verifies, AQE query stages), not a growing iterative lineage.
- Failing stage: `integrity` (Pipeline.scala:919-933), after semantic-summaries (last job `parquet at Output.scala:136`, stderr :225604). Its single `out.write` with repartition(1, keys) (Output.scala:132; partitions = 1 at Pipeline.scala:930) over the union graph.violations +: windowViolations (Pipeline.scala:920). ShuffleMapStage 10409: 40 materialised parent shuffles, 3.7 MiB task binary (stderr :235735), 30,000 PROCESS_LOCAL tasks (sum of the union branches' partitions as AQE planned them). graph.violations (GraphBuild.scala:341-353, 11 branches with joins, window, exceptAll, self-join) is never checkpointed, so integrity re-plans the whole canonical build from staged Parquet (bounded).
- Driver retained state at defaults: spark.ui.retainedJobs/Stages 1000, retainedTasks 100000, spark.sql.ui.retainedExecutions 1000; map statuses for shuffles still referenced (IDs to 2655); the integrity AQE plan re-optimised about 137 times in 45 s; 30,000 task objects. Collected results all bounded. GC thrash within about 3 s of submission (task reports 2 s -> 7-15 s, listener events 1.8 s and 9.7 s, stderr :235973-:235984), 71 of 30,000 tasks done, then exit 137. Most likely: 2 GiB heap carrying 22 min of retained UI/SQL/map-output state plus the integrity plan and 30,000-task set. Not proven without a heap dump.

## Fixes (merged table)
| Fix | Changes | Effect | Risk | D6/D7 valid? |
|---|---|---|---|---|
| Driver memory (--driver-memory 6g-8g) in the Spark step args, sfn.tf:303 (and :330) | Terraform only (state machine in place): plan, sign, deploy.sh | heap 2 GiB -> 6-8 GiB (Phase 6: 6 GB) | m5.xlarge primary 16 GiB shared with YARN RM/NameNode: 6g fits, 8g tight (infra) | yes, build commit stays f159535e |
| spark.sql.shuffle.partitions 32-64 via --conf (or spark-defaults classification on CreateSparkCluster) | Terraform only | 3-6x fewer tasks per shuffle stage; smaller map statuses and listener state; integrity map stage shrinks | runtime knob, not in the run ID (Pipeline.scala:282-284, JobConfig.scala:42-44); Phase 6 outputs came from 32 | yes |
| UI retention (retainedJobs/Stages ~200, retainedTasks ~10000, sql.ui.retainedExecutions ~50) via --conf | Terraform only | bounds retained status state | less Spark UI history | yes |
| Generic allow-listed --spark-submit-arg pass-through (script + state machine) | Terraform + scripts | future tuning without a plan | larger security surface | yes |
| Checkpoint graph.violations / window violations (C1); repartition BFS visited before cp (C2, Algorithms.scala:285, partitions 106->263 over iterations, minor) | modules/spark -> new build commit, package/publish, new image and runtime plan before D9 (run-bounded-pipeline.sh:1574-1575) | root-cause hardening | medium; tests and review | yes as inputs (below) |
| Checkpoint dir on HDFS | code -> new build commit | speed only | not needed | – |
| Primary m5.2xlarge (32 GiB) | Terraform variables; also changes the Hadoop cluster (shared cluster_common, sfn.tf:82) | room for 8-12g driver, only with explicit driver memory | cost about +$0.29/h (remembered list price, unchecked); budget 8 + 4x4 = 24 <= 32 | yes |
| Script argument only | not possible: spark stage options are fixed (run-bounded-pipeline.sh:477), execution input carries no Spark conf (:1523-1527), --spark-config has no runtime fields and must carry the build commit (:717-725) | – | – | – |

## Reuse of D6/D7 at a new build commit (both agents, independently)
No check refuses inputs built at an older commit. Script: Hadoop export location-checked only (:1505, resolve_location :662-677); ER output: require_er_succeeded checks kind, stage, status, identity path (:1484-1496), path shape (:1508-1509), no build_commit; only the Spark jar and config must carry scholarchain-commit = current build commit (check_artifact_commit :717-725, called :1511, :1513). Job: checkErRun (Pipeline.scala:167-232) requires codeVersion non-blank only (:200); input codeVersions recorded (:987, :994). A Terraform-only fix leaves the build commit unchanged (infra/ and scripts/ are not build inputs, scripts/lib/identity.sh:36, :73-84).

## Required for any rerun
New --out-root (e.g. s3://{data-bucket}/graph/runs-2): same code version, inputs and config give the same run ID (Pipeline.scala:282-286); the failed run created run=<hex> without a manifest (:305), so a rerun into graph/runs is refused (Output.scala:402-406 "Use a new attempt root") and _staging/run=<hex> fails errorifexists (:339). Leftovers under graph/runs/{run=, _checkpoint/, _staging/} stay until an approved cleanup.sh.

## Recommendations
- infra-engineer: Terraform only: spark.driver.memory 6g (+ optional shuffle partitions, UI retention) via a spark-defaults classification on CreateSparkCluster, or in the step args; fallback primary m5.2xlarge.
- spark-engineer: one Terraform change to the Spark step args (sfn.tf:303): --driver-memory 8g --conf spark.sql.shuffle.partitions=64 + UI retention confs; C1, C2 proposed later; optional HeapDumpOnOutOfMemoryError.

## Unplanned deviations (read-only)
spark-engineer: one pipeline with sort and uniq on the stderr; several Bash calls began with `cd <repo> &&`. Nothing written.

files changed: none (both)
