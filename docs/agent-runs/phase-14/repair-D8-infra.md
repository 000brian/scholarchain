# Repair G-21, infra-engineer report

(Saved in substance by the orchestrator from infra-engineer's final message.)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), session 3, repair G-21 (class 4, human go "go G-21")
Diagnosis: diagnosis-G-21.md (same folder).

FILES MODIFIED:
- infra/terraform/modules/workflow/sfn.tf (+21/-2)
- scripts/README.md (+2/-2)

COMMANDS RUN: terraform fmt -recursive infra/terraform (no output); terraform fmt -check -recursive infra/terraform (exit 0); terraform -chdir=infra/terraform/environments/course validate ("Success! The configuration is valid."); read-only grep and git. No plan, no script, no AWS call. shellcheck not run (no shell script changed).
Orchestrator re-ran fmt -check (exit 0) and validate (Success!): match.

## Diff summary
- sfn.tf:122-139 new locals: `spark_submit_runtime_args` = ["--driver-memory","6g","--conf","spark.sql.shuffle.partitions=32","--conf","spark.ui.retainedJobs=200","--conf","spark.ui.retainedStages=200","--conf","spark.ui.retainedTasks=10000","--conf","spark.sql.ui.retainedExecutions=50"]; `spark_submit_runtime_jsonata` renders them as quoted JSONata string items (no value contains a quote); comment cites the docs and the client-mode reason. No new variables, tfvars or environment variables.
- sfn.tf:322 SparkGraphAnalytics Args: 'spark-submit','--deploy-mode','client', <runtime args>, '--class','org.scholarchain.spark.GraphAnalyticsJob', <jar>, <job args unchanged>.
- sfn.tf:349 SparkJsonlExport Args: same insertion before '--class','org.scholarchain.spark.JsonlExport'.
- Unchanged: Hadoop steps, cluster_common, cluster_instances, CreateHadoopCluster, CreateSparkCluster, CopySparkJobConfig.
- scripts/README.md:82 (D8 row): --out-root now s3://{data-bucket}/graph/runs-2; two sentences on the G-21 options and why the rerun needs a new out-root (Output.scala:402-406). :83 (D9 row): --export-source-run under graph/runs-2/run=HEX32.

## Docs checked
- https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark-submit-step.html: command-runner.jar Args `spark-submit <options> --class <main> <jar> <app args>`; --driver-memory and --conf as switches.
- Spark configuration docs (from knowledge, not fetched): spark.driver.memory must come from --driver-memory or the defaults file in client mode.

## Expected plan
1 in-place update: module.workflow.aws_sfn_state_machine.pipeline (definition only). 0 add, 0 destroy, 0 replace. No IAM change, no stateful action, no new cost. Build commit unchanged (f159535e).

ASSUMPTIONS: graph/runs-2 passes the script's location check (run-bounded-pipeline.sh:1507 requires the graph/ prefix; environments/course/main.tf:208).
RISKS: 6g on a 16 GiB primary untested on EMR; OOM root cause unproven (no heap dump); same run hex under a new root; leftovers under graph/runs/ until approved cleanup.
NEXT GATE: code-reviewer, then plan.sh (runtime-off), human signature, deploy.sh, D8 rerun with --out-root s3://{data-bucket}/graph/runs-2.

files changed after these runs: none
