# Review report for repair G-21

(Saved in substance by the orchestrator from code-reviewer's final message.)

VERDICT: APPROVE

No critical, major or minor findings (next free ID stays G-22). The diff matches the approved change: six spark-submit options in both Spark steps, Terraform only; README D8/D9 rows correct. Whether 6g suffices is unproven until the EMR rerun.

## Checks
1. Rendered Args (sfn.tf:131-139, :322, :349): `spark_submit_runtime_jsonata` joins each constant as '<value>' and is inserted after 'client', before '--class' in both steps. Graph step: ['spark-submit','--deploy-mode','client','--driver-memory','6g','--conf','spark.sql.shuffle.partitions=32', …, '--conf','spark.sql.ui.retainedExecutions=50','--class','org.scholarchain.spark.GraphAnalyticsJob', jar, job args]. Values contain no quote or special characters; fixed in Terraform, not from execution input (no injection); `{%` is not a Terraform directive. Job args unchanged. Hadoop step (:247), cluster_instances/cluster_common (:70-118), CreateHadoopCluster, CreateSparkCluster, CopySparkJobConfig byte-identical. check.sh gate (c') (:716-722) unaffected.
2. Values exactly as approved (sfn.tf:132-137). --driver-memory sets the client-mode driver JVM heap, overrides spark-defaults; job code sets only broadcast thresholds (GraphAnalyticsJob.scala:58-61, :80). All five --conf keys standard in Spark 4.1. Run ID (Pipeline.scala:282-284) excludes shuffle partitions (JobConfig.scala:42-44); outputs repartitioned by cfg.outputPartitions (:362); ordering-sensitive operations use total orders (Analytics.scala:57-67, Paths.scala:50-62, :82-95, sort_array around collect_list in GraphBuild.scala and Summaries.scala, JsonlExport.scala:466-467). Phase 6 ran with 32 (LocalRealRun.scala:24, :37) and also spark.default.parallelism=32 (:38), not set here: affects RDD shuffle partitioning only (note, not a finding).
3. Expected plan: locals used only at sfn.tf:139, :322, :349, feeding aws_sfn_state_machine.pipeline.definition (:567-571); no IAM document references them. 1 in-place update of module.workflow.aws_sfn_state_machine.pipeline; 0 add/replace/destroy (reading, no plan seen).
4. README:82-83: no identifiers; graph/runs-2 passes resolve_location (run-bounded-pipeline.sh:662-677); export-source-run under runs-2 passes :1516-1517 and graph_uri_prefix (sfn.tf:145-152); EMR role covers graph/ (identity/emr.tf:19-20); Output.scala:402-406 citation correct.
5. Build commit stays f159535e (identity.sh:36, :73-84); jar/config commit check (:717-725, :1511-1513) still matches. sfn.tf is a plan input (identity.sh:47-50): new plan, sign, deploy.sh needed.

## Residual risks
6g on the m5.xlarge primary (16 GiB, variables.tf:86-90, shared with YARN RM, NameNode, history server; plus non-heap) untested; root cause unproven (no heap dump), next options C1 checkpoint or larger primary; JsonlExport gets the same options harmlessly; leftovers under graph/runs until approved cleanup.sh; same run hex under the new root is expected.

STATUS: COMPLETE
OWNER: code-reviewer
NEXT GATE: plan.sh (runtime-off), confirm exactly 1 in-place update of module.workflow.aws_sfn_state_machine.pipeline, sign, deploy.sh, D8 rerun with --out-root s3://{data-bucket}/graph/runs-2.

VERDICT: APPROVE
