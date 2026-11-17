# Phase 12 step 2b report (infra-engineer)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-12/prompt.md (sha256 5744ab73aa42946f1c556d1257a45031fd57868b75480de623879a9c73de7f56, re-checked with shasum at start), session 2, step 2b = decision 6 areas 9-13, root outputs.tf, config/course.json.
FILES CREATED (absolute paths under /Users/brian/Developer/repos/scholarchain/):
- infra/terraform/COSTS.md
- infra/terraform/modules/runtime/iam.tf, ingress.tf, autoscaling.tf
- infra/terraform/modules/workflow/sfn.tf
- infra/terraform/modules/observability/alarms.tf, dashboard.tf
- config/course.json
FILES MODIFIED:
- infra/terraform/modules/runtime/main.tf, variables.tf, outputs.tf (step-1 skeletons, now filled)
- infra/terraform/modules/workflow/main.tf, variables.tf, outputs.tf (skeletons, filled)
- infra/terraform/modules/observability/main.tf, variables.tf, outputs.tf (skeletons, filled)
- infra/terraform/environments/course/main.tf (runtime, workflow, observability blocks; vectors `data_principal_arns` hook; cleanup-mode locals feeding the DynamoDB and Neptune protection inputs), variables.tf (new root variables), course.tfvars (`runtime_enabled = false`, `cleanup_mode = false`), outputs.tf (root outputs)
- 2a-owned file edited: infra/terraform/README.md ONLY (layout rows for runtime/workflow/observability/COSTS.md; new sections "Runtime switch", "Pipeline state machine and EMR cluster termination", "Alarms", "Cleanup mode"). No 2a MODULE file (foundation, network, identity, data-lake, emr, dynamodb, neptune, vectors, STATE.md) was edited.
- Not touched: every versions.tf, .terraform.lock.hcl, scripts/**, anything outside infra/** and config/**.
COMMANDS RUN:
- `shasum -a 256 docs/agent-runs/phase-12/prompt.md` -> matches
- per area: `terraform fmt -recursive infra/terraform` (exit 0; it reformatted runtime/iam.tf once), `terraform fmt -recursive -check infra/terraform` (exit 0), `terraform -chdir=infra/terraform/environments/course validate` (with `-no-color` from the second run on; no approval prompt) -> one recoverable failure (missing arguments of `module "runtime"`, before its root wiring), then Success after every area
- `jq empty config/course.json` -> 0; `shellcheck infra/terraform/modules/emr/files/bootstrap.sh` -> 0 (unchanged file; evidence line)
- read-only: cat/sed -n/grep/ls/find/wc of repository inputs, `git status --short`, `git diff --stat`, grep scans for 12-digit numbers and dotted IPs, `date`
- MCP aws-docs: search_documentation x6, read_documentation x9 (Step Functions `connect-emr.html`, `cw-logs.html`; EMR `using-service-linked-roles(-cleanup).html`, `emr-auto-termination-policy.html`; Service Authorization Reference `list_amazonelasticmapreduce.html` (partial); VPC `nat-gateway-pricing.html`; Neptune `instance-types.html`). terraform MCP: not used (no doc-search tool exposed; validate was the arbiter). aws-api MCP: not used (not connected, as the work order says).
TEST RESULTS: none (no sbt in this phase).
VALIDATION RESULTS: fmt check exit 0; validate "Success! The configuration is valid."; shellcheck exit 0 (clean); `jq empty config/course.json` exit 0. See VALIDATION EVIDENCE at the end.
ASSUMPTIONS:
- A1. config values not fixed by any repository source were chosen and are open to review: `entityResolution.reviewThreshold` 0.3 (er-v2's reject threshold; matchThreshold 0.9 = er-v2 accept), `bridge.algorithm` "bridge-edges", `bridge.sampleSize` 100, `randomSeed` 7, `topK` 10 (example values; services do not read them), `graph.minEdgeWeight` 1.0 and the five windows (from `modules/spark/conf/phase06.json`), models `voyage-4` / `claude-haiku-5-5` (from rag's code and example configs), limits copied from the rag/services example configs except `syncDeadlineMillis` 25 000 (<= 25 000 rule), `neptune.queryTimeoutMillis` 20 000.
- A2. `objectStore.semanticContextPrefix` = `rag` (the 2a data-lake prefix); all three objectStore buckets and the four storage buckets are the one data-lake bucket (raw, curated, graph) or the vector bucket, set by the task definitions.
- A3. `storage.dynamoDbTable` (a legacy single-table key) is set to the RunLedger table.
- A4. Task sizes (1 vCPU/2 GiB query and worker, 0.5/1 evidence and graph, 1/4 ops) fit the JVM; unverified until Phase 14.
- A5. JSONata state machine syntax (Assign, Catch Assign with `$states.errorOutput`, JSONata `Error`/`Cause` in Fail states) follows the Step Functions guide; `terraform validate` does not check the definition; AWS checks it at create (Phase 14 apply).
RISKS:
- R1. `/healthz` and `/readyz` are routed through API Gateway to query (deviation, see area 10); they are unauthenticated and reveal store readiness only.
- R2. `GET /v1/evidence/*` and `/v1/explain/*` have no route deadline; a slow DynamoDB can exceed API Gateway's ~30 s (504 to the client). Routed to service-engineer as a note; no async path.
- R3. Fargate tasks pull the image and call Anthropic/Voyage through the single NAT gateway (no interface endpoints): NAT data charges and the single-AZ egress risk of 2a apply to the runtime.
- R4. The ops task cannot do useful work on AWS yet: RagIngestJob reads only a local export, and the storage loaders have no main class (phase-13 gaps for rag-engineer and storage-engineer).
- R5. `command-runner.jar`, the AWS CLI and `Applications [Spark]` on `emr-spark-8.1.0`, and `PutManagedScalingPolicy` being needed on create, are `unverified`. `TerminateJobFlows` on a cluster that `ActionOnFailure = TERMINATE_CLUSTER` already ended is assumed to succeed (else the execution fails with `ClusterCleanupFailed`, still loud and with the idle backstop).
- R6. The SNS alarm topic is not encrypted at rest (CloudWatch cannot publish to a topic encrypted with `alias/aws/sns`; no CMK by decision). It carries alarm metadata only.
- R7. Every price in COSTS.md is `estimate (unverified)`: no pricing page was reachable. Neptune free-trial terms beyond "750 instance hours for t3/t4g" are unconfirmed.
- R8. Spark half of the pipeline needs the Hadoop export run directory (`run=<id>`) of an earlier Hadoop run as input; one execution cannot chain them without it. Entity resolution is not an EMR step; JsonlExport is local-only (gap for spark-engineer).
- R9. When `runtime_enabled` turns on, the ECS services' desired count (ignored by Terraform after creation) is lifted to 1 by the scaling targets' new minimum; this Application Auto Scaling behavior is `unverified` (Phase 14 observes; if it does not happen, a one-time `desired_count` change is needed).
- R10. The application emits no custom CloudWatch metric (LLM usage, S3 Vectors client calls, embedding calls): spec observability gap, listed, not invented.
MANUAL ACTION REQUIRED (human):
- Before plan: set `owner` in `local.auto.tfvars`; confirm `expires` in course.tfvars; check every price in COSTS.md on its pricing page and the Neptune free-trial terms.
- After the first apply: enter the four secret values; subscribe an address to the SNS alarm topic; activate cost-allocation tags (README).
- Phase 14: second signed plan with `runtime_enabled = true`, `image_tag`, `graph_run_id` (after the image with `/opt/scholarchain/config/course.json` is pushed).
- After each manual pipeline stop: check for running clusters tagged `project=scholarchain` and an `emr-service-vpce` endpoint.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 2c (scripts; reads root outputs.tf and variables.tf, section INTERFACE HANDOFF below), then R-2 (code-reviewer over infra/**, config/**, scripts/**, COSTS.md).

## Area 9: OpenSearch (done)

- Not built. The line already exists in `infra/terraform/README.md` ("## OpenSearch: Not built: no hot or hybrid retrieval tier is implemented or evaluated (decision 6 area 9)"), written by step 2a. No new file.

## Area 10: runtime compute (done, one deviation)

Order note: area 10's IAM needs the queue ARNs, so the two SQS queues and their DLQs (area 11) were written first; the first validate after them failed only because the runtime variables were declared ahead of their root wiring (missing-argument errors in `module "runtime"`, a recoverable error, not init). After the runtime wiring: fmt check 0, validate Success.

Files: `modules/runtime/{main,iam,ingress,autoscaling,variables,outputs}.tf`; root `main.tf`, `variables.tf`, `course.tfvars`; `modules/observability/{main,variables,outputs}.tf` (log groups only at this point, because the task definitions and the API stage need them).

- ECR: `scholarchain-course-services`, `IMMUTABLE`, scan on push, AES256, `force_delete = false`, lifecycle keeps the 10 most recent images (`ecr_keep_images`). Tags are git commit SHAs (root `image_tag` validation: 40 lowercase hex).
- ECS cluster `scholarchain-course-runtime`, Container Insights disabled (paid).
- One image, four services `query`, `evidence`, `graph`, `worker`, Fargate ARM64 (`runtime_platform`), private app subnets, no public IP, each with its own 2a security group. Command: `--config /opt/scholarchain/config/course.json --role ROLE --stores aws --graph-run GRAPH_RUN`. Role `all` is never used.
- Task sizes (smallest that fits; JVM with the SDK clients, prompts and schemas loaded):
  - query 1 vCPU / 2 GiB: runs the query workflow with `maxConcurrentQueries` = 4 threads plus 12 HTTP handler threads and LLM/embedding clients.
  - worker 1 vCPU / 2 GiB: runs the same workflow (`workerConcurrency` = 2).
  - evidence 0.5 vCPU / 1 GiB and graph 0.5 vCPU / 1 GiB: DynamoDB/Neptune reads only, but they still build the whole AWS runtime (model and embedder clients); 0.25 vCPU / 0.5 GiB is too tight for a JVM at 75 % MaxRAMPercentage (estimate, unverified until Phase 14).
  - ops 1 vCPU / 4 GiB, ephemeral storage 30 GiB: RagIngestJob stops below 15 GB free disk at its `--out` root.
- Switch: root `runtime_enabled` (default false, `course.tfvars` false), `image_tag` and `graph_run_id` (`default = null`). While null the task definitions use `placeholder-runtime-disabled` and `run:v1:000...0` through `coalesce`. Checks: two cross-variable `validation` blocks in the root (each of `image_tag` and `graph_run_id` required when the switch is on, plus format checks) and a `lifecycle.precondition` on the ECS cluster. Switch off: desired counts 0, every scaling target min 0 / max 0, no scaling policy and no worker alarm.
- Autoscaling (choices):
  - query, evidence, graph: target tracking `ECSServiceAverageCPUUtilization`, target 60 %, scale-out cooldown 60 s, scale-in cooldown 180 s, min 1, max 2 (`runtime_service_max_count`, validated 1..4).
  - worker: step scaling from 0. Scale-out alarm `ApproximateNumberOfMessagesVisible` Maximum >= 1 over 1 x 60 s (missing = not breaching) -> +1 task (ChangeInCapacity, cooldown 120 s), max 2 (`runtime_worker_max_count`, 1..4). Scale-in alarm metric math `visible + inflight` (`ApproximateNumberOfMessagesNotVisible`) < 1 for 5 of 5 x 60 s (missing = breaching, SQS may stop publishing for an idle queue) -> ExactCapacity 0.
  - ECS services `ignore_changes = [desired_count]`: Application Auto Scaling owns the count after creation; when the switch turns on, the targets' min 1 lifts the ALB services to 1 (Application Auto Scaling behavior when min rises above the current count: `unverified` here, Phase 14 observes it).
- ops task definition `scholarchain-course-ops`: same image, no service, entry point `["java", "-cp", "/opt/scholarchain/app.jar"]`, command (main class and arguments) given at run-task time; own task role; Voyage key only.
- Ingress: API Gateway HTTP API `scholarchain-course-api`, `$default` route -> `HTTP_PROXY` integration over a VPC link (app subnets, `vpc_link` SG) -> internal ALB listener (HTTP 80). Integration timeout 30 000 ms, payload 1.0, headers passed unchanged (the API-key header included). `$default` stage, auto deploy, throttling burst 10 / rate 5 rps (`api_throttling_*`): one person's smoke tests and demo; access logs to `/scholarchain/course/apigateway/access` with request metadata only (request ID, time, method, path, route, status, length, latencies, integration status/error, source IP; no bodies, no headers).
- Internal ALB `scholarchain-course-alb`: `internal = true`, `alb` SG (ingress only from the VPC link SG, 2a), `drop_invalid_header_fields`, deletion protection off (cleanup path). Listener default action: fixed 404 JSON. Target groups (ip, HTTP 8080, deregistration delay 30 s > the 25 s sync deadline), health check `GET /healthz` 200, 15 s interval.
- Rules: priority 10 query `/v1/query`, `/v1/jobs/*`, `/healthz`, `/readyz`; 20 evidence `/v1/evidence/*`, `/v1/explain/*`; 30 graph `/v1/graph/*`; anything else 404.
- DEVIATION (open item for the human): `/healthz` and `/readyz` are also routed (to query). Reason: decision 6 says "anything else gets a fixed 404", but step 2c's smoke test must check health and readiness through the API Gateway URL; both routes are open (no key) on every role and return only status. Alternative if refused: remove the two paths and the smoke test checks health only via ECS/ALB target health.
- Service discovery: checked in `modules/services/**`: no HTTP client to another role; `Composition.aws` builds only the stores, the SQS job queue and the model/embedder clients; the roles share SQS and the stores. No Cloud Map; the ALB DNS name (output `alb_dns_name`) is the one internal address.
- stopTimeout (S-5): `ServiceServer.close()` stops the worker loop and the HTTP server, then shuts down its four pools (`http`, `query`, `worker`, `ready`) one after another, each waiting up to `syncDeadlineMillis` (25 000 ms) and then 1 000 ms after `shutdownNow`, then joins threads up to 2 s: worst case about 4 x 26 + 2 = 106 s. `stopTimeout` = 120 s (the Fargate maximum) on all four services and on ops.

### Routes per role (from `modules/services/src/main/scala/org/scholarchain/services/Routes.scala`)

| Role | Routes | Auth |
|---|---|---|
| all roles | `GET /healthz`, `GET /readyz` | open |
| query | `POST /v1/query` | full key |
| query | `GET /v1/jobs/{jobId}`, `GET /v1/jobs/{jobId}/result` | any key |
| evidence | `GET /v1/evidence/{evidenceId}`, `GET /v1/explain/{queryId}` | any key |
| graph | `GET /v1/graph/nodes/{nodeId}`, `GET /v1/graph/nodes/{nodeId}/neighbors` | any key |
| worker | none beyond health (it consumes the job queue; it gets no ALB traffic) | |

### Synchronous routes that can exceed the ~30 s API Gateway limit

- `POST /v1/query` (sync mode): bounded by `service.syncDeadlineMillis` = 25 000 ms in `config/course.json`; past it the service answers `WORKFLOW_TIMEOUT` itself, and the workflow keeps running. Covered by the async path: `mode` async returns 202 and the job is read with `/v1/jobs/{jobId}` and `/result`; a sync call with an `Idempotency-Key` also leaves a job record that can be followed. Covered.
- `GET /v1/graph/nodes/...`: graph searches are bounded by `runtime.graphTimeoutMillis` = 20 000 ms and `neptune.queryTimeoutMillis` = 20 000 ms (config). Covered by the bound, no async path needed.
- `GET /v1/evidence/{id}` and `GET /v1/explain/{id}`: DynamoDB reads (up to 100 windows, up to 1 000 audit events) with no route-level deadline; each SDK call has a 60 s API-call timeout and storage's bounded retry. Normally well under a second, but a degraded DynamoDB could exceed 30 s; API Gateway then returns 504 while the task finishes the read. Not covered by an async path. Reported for service-engineer (no service code changed).
- `GET /readyz`: 2 s deadline (`ServiceLimits.ReadinessDeadlineMillis`). Covered.

### Task roles: actions per role (derived from the code; `iam.tf`)

| Role | Actions and resources |
|---|---|
| execution (shared) | `ecr:GetAuthorizationToken` (`*`); `ecr:BatchCheckLayerAvailability`, `BatchGetImage`, `GetDownloadUrlForLayer` on the repository; `logs:CreateLogStream`, `PutLogEvents` on the ecs and ops log groups' streams; `secretsmanager:GetSecretValue` on the four secret containers |
| query | `neptune-db:connect`, `ReadDataViaQuery` on the cluster; `dynamodb:GetItem`, `Query` on ResearcherMetricCache, EntityLinkCache; `GetItem`, `Query`, `PutItem` on EvidenceBundle, RunLedger, QuerySession; `s3vectors:QueryVectors`, `GetVectors` on the five indexes; `s3:GetObject` on `rag/*`, `s3:ListBucket` with `s3:prefix` `rag`, `rag/*`; `sqs:SendMessage` on the job queue |
| worker | as query, but `sqs:ReceiveMessage`, `DeleteMessage`, `ChangeMessageVisibility` on the job queue instead of SendMessage |
| evidence | Neptune read (readiness probe); `GetItem`, `Query` on EvidenceBundle, RunLedger, ResearcherMetricCache |
| graph | Neptune read; `GetItem`, `Query` on ResearcherMetricCache (readiness probe) |
| ops | `s3:ListBucket` (prefixes graph, rag), `s3:GetObject` on `graph/*`, `rag/*`, `s3:PutObject` on `rag/*`; `GetItem`, `Query`, `PutItem` on all six tables; `s3vectors:PutVectors`, `GetVectors`, `ListVectors`, `QueryVectors` on the five indexes (QueryVectors: the ingest job's retrieval smoke samples); `neptune-db:connect`, `StartLoaderJob`, `GetLoaderJobStatus`; `sqs:SendMessage`, `ReceiveMessage`, `DeleteMessage`, `ChangeMessageVisibility` on the embedding queue only |

- Why every service role reads ResearcherMetricCache and Neptune: `ServiceServer` builds `/readyz` checks `metrics` (ResearcherMetricCache GetItem) and `graph` (Neptune) for every role; query and worker also check `jobs` (QuerySession).
- No Bedrock action: `config/course.json` uses `anthropic` and `voyage`.
- Neptune bulk load: `StartLoaderJob` passes the loader role ARN attached to the cluster (2a); whether the caller also needs `iam:PassRole` for it is `unverified` (not granted).
- Trust: `ecs-tasks.amazonaws.com` with `aws:SourceAccount` and `aws:SourceArn` `arn:aws:ecs:<region>:<account>:*`.
- The vectors bucket policy (2a hook) now allows the query, worker and ops roles (root `concat(module.runtime.vector_principal_arns, var.vectors_data_principal_arns)`); evidence and graph never call S3 Vectors.

### Environment-variable NAMES the task definitions set

- Plain values (from Terraform), all tasks: `SCHOLARCHAIN_CFG_AWS_ACCOUNT_ID`, `SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKET`, `SCHOLARCHAIN_CFG_STORAGE_CURATED_BUCKET`, `SCHOLARCHAIN_CFG_STORAGE_GRAPH_BUCKET`, `SCHOLARCHAIN_CFG_STORAGE_VECTOR_BUCKET`, `SCHOLARCHAIN_CFG_STORAGE_NEPTUNE_ENDPOINT`, `SCHOLARCHAIN_CFG_STORAGE_DYNAMO_DB_TABLE` (= RunLedger table), `SCHOLARCHAIN_CFG_OBJECT_STORE_SEMANTIC_CONTEXT_BUCKET`, `SCHOLARCHAIN_CFG_OBJECT_STORE_REPORTS_BUCKET`, `SCHOLARCHAIN_CFG_OBJECT_STORE_LOGS_BUCKET`, `SCHOLARCHAIN_CFG_DYNAMO_DB_RUN_LEDGER_TABLE`, `SCHOLARCHAIN_CFG_DYNAMO_DB_ENTITY_LINK_CACHE_TABLE`, `SCHOLARCHAIN_CFG_DYNAMO_DB_QUERY_SESSION_TABLE`, `SCHOLARCHAIN_CFG_DYNAMO_DB_EVIDENCE_BUNDLE_TABLE`, `SCHOLARCHAIN_CFG_DYNAMO_DB_ARTIFACT_REGISTRY_TABLE`, `SCHOLARCHAIN_CFG_DYNAMO_DB_RESEARCHER_METRIC_CACHE_TABLE`.
- Services only: `SCHOLARCHAIN_CFG_SERVICE_JOB_QUEUE_URL`.
- Secrets (Secrets Manager ARN, value injected by ECS): services get `SCHOLARCHAIN_VOYAGE_API_KEY`, `SCHOLARCHAIN_ANTHROPIC_API_KEY`, `SCHOLARCHAIN_SERVICE_API_KEY`, `SCHOLARCHAIN_SERVICE_READONLY_API_KEY` (Main refuses to start without both service keys; `RuntimeService.aws` builds the anthropic model and the voyage query embedder for every role). ops gets `SCHOLARCHAIN_VOYAGE_API_KEY` only.
- Each name was derived with `ConfigKey.envVarFor` rules (prefix `SCHOLARCHAIN_CFG_`, dots to `_`, camelCase to UPPER_SNAKE) from the key list in `ConfigLoader.scala`; a wrong name would stop the service at start ("unknown configuration variable").

### ops: main classes, arguments, `s3://` readiness (phase-13 gaps)

| Job | Main class (in the services jar) | Arguments | Reads `s3://` today? |
|---|---|---|---|
| rag embedding ingest | `org.scholarchain.rag.ingest.RagIngestJob` | `--config FILE --export RUN_DIR --out DIR [--runs 1..3] [--samples-per-index 1..500] --stores aws` | No: `--export` is read with `LocalDirectorySource` (a local `Path`), `--out` is local. Gap `phase-13` for rag-engineer (read the export through storage's `ObjectStoreSource`, which exists). |
| DynamoDB loader | none: `DynamoLoader` is a library class; the only runner is `LocalRealRun` in storage's TEST tree (not in the image) | n/a | Library can read S3 (`ObjectStoreSource`), but there is no production main class. Gap `phase-13` for storage-engineer. |
| Neptune CSV build + bulk load | none: `NeptuneCsvBuilder` writes local files; `NeptuneBulkLoader` (StartLoaderJob/GetLoaderJobStatus) has no main class | n/a | No entry point. Gap `phase-13` for storage-engineer. |
| embedding work queue | no code uses `SqsWorkQueue` for embedding work today (only the job queue) | n/a | The queue and its ops access exist for when it is used. |

Until those gaps close, the ops task can start only `RagIngestJob`, and only against a local export inside the task (not usable on Fargate without the S3 read).

## Area 11: workflow and messaging (done)

Files: `modules/workflow/{main,sfn,variables,outputs}.tf`; root `main.tf`, `variables.tf`. fmt check 0, validate Success.

- Queues (`main.tf`): `scholarchain-course-jobs` and `scholarchain-course-embedding`, each with its own DLQ (`-jobs-dlq`, `-embedding-dlq`, retention 14 days), SSE-SQS (`sqs_managed_sse_enabled`), DLQ redrive-allow policy `byQueue` to its own source only.
  - Job queue visibility timeout 150 s = `service.jobLeaseMillis` 120 000 ms / 1000 + 30 s margin (the same margin as `ServiceLimits.LocalVisibilityMarginMillis`), so a live lease is never redelivered; precondition checks visibility > lease. Retention 86 400 s (= `service.jobTtlSeconds`). Receive wait 0 (the client long-polls 1 s itself).
  - Job DLQ `maxReceiveCount` 5 = `service.jobMaxAttempts` 3 + 2.
  - Embedding queue: visibility 300 s (SqsWorkQueue also sends its own per receive), retention 4 days, `maxReceiveCount` 5.
  - Values come from root variables `job_lease_seconds` (120) and `job_max_attempts` (3) that mirror `config/course.json`; a change in one must be made in the other (noted in both descriptions).
  - Queue policies: only the actions `SqsWorkQueue` calls (SendMessageBatch = `sqs:SendMessage`, `ReceiveMessage`, `DeleteMessage`, `ChangeMessageVisibility`), only for the role that calls them, on that queue only: jobs: query -> SendMessage; worker -> Receive/Delete/ChangeVisibility. embedding: ops -> all four. Plus a Deny of non-TLS access.
- State machine (`sfn.tf`), `scholarchain-course-pipeline`, STANDARD, `QueryLanguage` JSONata, `TimeoutSeconds` from `pipeline_timeout_seconds` (default 14 400 s, validated 900..43 200).
  - Integrations confirmed in the Step Functions developer guide page `https://docs.aws.amazon.com/step-functions/latest/dg/connect-emr.html` (read 2026-11-17 with aws-docs): `arn:aws:states:::elasticmapreduce:createCluster.sync` (waits for WAITING; `Instances.KeepJobFlowAliveWhenNoSteps` must be true; `Steps` not allowed), `addStep.sync`, `terminateCluster.sync`; the page's examples use JSONata `Arguments` and `{% $ClusterId %}`. The page also says Step Functions does not end a cluster when an execution is stopped, and polls EMR every 60 s for 10 minutes, then every 300 s.
  - Flow: `ReadInput` (Assign `pipelineInput`) -> `HadoopRequested` -> `CreateHadoopCluster` (Assign `hadoopClusterId` from `$states.result.ClusterId`) -> `HadoopPipelineStep` -> `TerminateHadoopCluster` -> `SparkRequested` -> `CreateSparkCluster` (Assign `sparkClusterId`) -> `CopySparkJobConfig` -> `SparkGraphAnalytics` -> `TerminateSparkCluster` -> `PipelineSucceeded`. Either half is skipped when its input section is absent.
  - Failure: every state after a successful create catches `States.ALL` (Assign `failError`, `failCause`) -> `CleanupHadoopCluster` / `CleanupSparkCluster` (`terminateCluster.sync`, Retry 3 x from 30 s, backoff 2) -> `FailPipeline` (Error/Cause = the original). A failing terminate (normal or cleanup) -> `FailClusterCleanup` (Error `ClusterCleanupFailed`, cause names both errors and the idle backstop). A failing create -> `FailPipeline` (no cluster ID). Steps use `ActionOnFailure = TERMINATE_CLUSTER` as an extra guard.
  - createCluster arguments come from `module.emr.cluster_settings` (no value hard-coded): release label (Hadoop `emr-7.14.0` with `Applications [Hadoop]`; Spark `emr-spark-8.1.0` with `Applications [Spark]`), `LogUri`, service role, instance profile, bootstrap action, first data subnet, the three EMR security groups, instance groups (primary 1 x m5.xlarge, core 2 x m5.xlarge, ON_DEMAND), `StepConcurrencyLevel` 1, `ManagedScalingPolicy` (Instances, min 2, max 4, max On-Demand 4, max core 4), `AutoTerminationPolicy.IdleTimeout` 900 s, `Tags` = provider default tags (`data.aws_default_tags`) + `for-use-with-amazon-emr-managed-policies=true` + `managed-by=step-functions`.
  - Execution input (documented in the file header): `codeVersion`, `hadoop{jar, output, dblp, dblpSnapshot, extraArgs?}`, `spark{jar, config, hadoopExport, identityAssignments, outRoot}`.
  - Code version: Hadoop step `hadoop jar` of `org.scholarchain.hadoop.pipeline.ScholarChainHadoop` with args `pipeline -D scholarchain.code.version=<codeVersion> --output … --dblp … --dblp-snapshot … [extraArgs]` (`Settings.scala`: `scholarchain.code.version`; `ScholarChainHadoop`: generic `-D` options through ToolRunner). Spark step `spark-submit --deploy-mode client --class org.scholarchain.spark.GraphAnalyticsJob <jar> --hadoop-export … --identity-assignments … --out-root … --config /home/hadoop/scholarchain-spark-job-config.json --code-version <codeVersion>` (`GraphAnalyticsJob.parseArgs`).
  - `--config` of the Spark job is a file on the driver's local disk (GraphAnalyticsJob reads it with `java.io.File`), so a `command-runner.jar aws s3 cp <spark.config> /home/hadoop/...` step copies it first; client deploy mode keeps the driver on the primary node. `command-runner.jar` and the AWS CLI on `emr-spark-8.1.0` images: `unverified`. Spark proposed-later item "read --config through the Hadoop FS" (Phase 11) would remove this step.
  - Not in the state machine: `JsonlExport` (reads/writes `java.io.File` paths only, local; gap for spark-engineer, phase-13), entity resolution (not an EMR job; its `identity_assignment` output is an execution input), and the Hadoop export run directory (`run=<id>` is computed by the Hadoop job, so a Spark half needs the path of an earlier Hadoop run; `run-bounded-pipeline.sh` can run the halves as two executions).
- Role `scholarchain-course-pipeline-sfn-*`, trust `states.amazonaws.com` with `aws:SourceAccount` and `aws:SourceArn` `arn:aws:states:<region>:<account>:stateMachine:*`:
  - `elasticmapreduce:RunJobFlow` on `*` (no resource type in the Service Authorization Reference, read 2026-11-17), with conditions `aws:RequestTag/project = scholarchain` and `aws:RequestTag/for-use-with-amazon-emr-managed-policies = true` (the reference lists `aws:RequestTag/${TagKey}` for RunJobFlow and no dependent action, so tags on create need nothing more).
  - On `arn:aws:elasticmapreduce:<region>:<account>:cluster/*`: `DescribeCluster`, `TerminateJobFlows` (createCluster/terminateCluster), `AddJobFlowSteps`, `DescribeStep`, `CancelSteps` (addStep), `PutAutoTerminationPolicy`, `GetAutoTerminationPolicy`, `RemoveAutoTerminationPolicy` (EMR Management Guide, `emr-auto-termination-policy.html`, "Permissions to use auto-termination"), `PutManagedScalingPolicy` (`unverified` as needed on create: no page found that says so; added because the request carries a managed-scaling policy).
  - `iam:PassRole` on exactly the EMR service role and the EMR instance role.
  - `iam:CreateServiceLinkedRole` on `arn:aws:iam::<account>:role/aws-service-role/elasticmapreduce.amazonaws.com*/AWSServiceRoleForEMRCleanup*` with `iam:AWSServiceName` (statement from `using-service-linked-roles-cleanup.html`, account narrowed from `*`).
  - CloudWatch Logs delivery: `CreateLogDelivery`, `GetLogDelivery`, `UpdateLogDelivery`, `DeleteLogDelivery`, `ListLogDeliveries`, `PutResourcePolicy`, `DescribeResourcePolicies`, `DescribeLogGroups` on `*` (Step Functions guide `cw-logs.html`: these support no resource type); `CreateLogStream`, `PutLogEvents` on the state machine's log group only.
  - No EventBridge permission (the EMR integrations poll).
- Logging: log group `/aws/vendedlogs/states/scholarchain-course-pipeline`, level ALL, `include_execution_data = false` (inputs name S3 locations).

## Area 12: observability (done)

Files: `modules/observability/{main,alarms,dashboard,variables,outputs}.tf`; root `main.tf`. fmt check 0, validate Success.

- Log groups, retention 7 days (`log_retention_days`, validated to short values): `/scholarchain/course/ecs/services` (four services, stream prefix = role), `/scholarchain/course/ecs/ops`, `/aws/vendedlogs/states/scholarchain-course-pipeline`, `/scholarchain/course/emr` (EMR writes its logs to S3 `logs/emr/`; nothing writes this group by default; destination for a later CloudWatch agent), `/scholarchain/course/apigateway/access`.
- One dashboard `scholarchain-course-overview`: API Gateway Count/4xx/5xx and p90 Latency/IntegrationLatency; ALB RequestCount, ELB and target 5xx, TargetResponseTime; ECS CPU and memory per service (AWS/ECS default metrics); SQS visible and oldest age for the 4 queues; Neptune CPU and MainRequestQueuePendingRequests; DynamoDB consumed read/write per table; Step Functions executions started/succeeded/failed; EMR AppsRunning through a SEARCH expression (cluster IDs exist only during runs).
- Alarms (default metrics only, no Container Insights), all -> SNS topic `scholarchain-course-alarms` (no subscription in source; policy: CloudWatch of this account may publish; non-TLS denied):
  - `…-jobs-dlq-not-empty`, `…-embedding-dlq-not-empty`: DLQ `ApproximateNumberOfMessagesVisible` Maximum > 0 over 5 min.
  - `…-alb-5xx`: `FILL(HTTPCode_ELB_5XX_Count) + FILL(HTTPCode_Target_5XX_Count)` Sum > 0 over 5 min.
  - `…-{query,evidence,graph}-no-healthy-target`: `HealthyHostCount` Minimum < 1 for 3 x 1 min; only when `runtime_enabled` (missing data = breaching).
  - `…-neptune-cpu-high`: `CPUUtilization` Average > 80 % for 3 x 5 min.
- `/metrics` is replaced by CloudWatch. Gaps (the application emits no such metric; none invented): LLM usage (calls, tokens), S3 Vectors client metrics (calls, retries), embedding calls, per-query HTTP and retry counts (the in-process counters, e.g. `AwsStorageEnvironment.retryReport`, are not exported). EMR, ECS, SQS, Neptune, DynamoDB, API latency and errors are covered by AWS default metrics.

## Area 13: cost and safety (done)

Files: `infra/terraform/COSTS.md` (new), `README.md` (2a file, edited), root `main.tf`, `variables.tf`, `course.tfvars`. fmt check 0, validate Success.

- Bounded defaults: ECS max 2 per ALB service and 2 workers (validated 1..4), the runtime off by default, API throttling 10/5, EMR bounds from 2a, DynamoDB on demand, one Neptune instance, log retention 7 days, ECR keeps 10 images, pipeline timeout 4 h (validated <= 12 h).
- `course.tfvars`: no secret, no account ID, no bucket name; adds `runtime_enabled = false`, `cleanup_mode = false`.
- Deletion protection variables (2a): DynamoDB, Neptune; ALB `enable_deletion_protection = false` (ephemeral), ECR `force_delete = false`, data lake `force_destroy = false` (2a).
- Explicit cleanup mode: root variable `cleanup_mode` (default false). When true, root locals force `deletion_protection` off for the six tables and the Neptune cluster regardless of the per-resource variables; a validation requires `runtime_enabled = false` with it. It removes nothing and never enables `force_destroy`. The removal order is documented in README "Cleanup mode" (archive evidence -> runtime off -> no running cluster or `emr-service-vpce` -> cleanup_mode plan -> empty ECR, every bucket version, indexes if needed -> approved removal plan -> leftovers by tag; IAM user and budget are Phase 17 manual items).
- Every resource is tagged by the provider default tags; EMR clusters get them through `data.aws_default_tags` in the createCluster request.
- COSTS.md: table of every resource that bills while it exists and per use, with price, source and per-day value; idle about $1.80/day with the Neptune trial (about 13 days in the ~$24.80 left) or $3.90/day without it (about 6 days); runtime on at minimum counts about $3.75 / $5.85 per day (about 6 / 4 days); a pipeline run about $1.50; what to switch off after Phases 15 and 16. **Every price is `estimate (unverified)`**: the pricing pages are on `aws.amazon.com`, which the aws-docs server cannot read; the docs pages read (VPC NAT pricing, Neptune instance types, Secrets Manager intro) give no amounts. Neptune trial: "first 750 instance hours" for t3/t4g medium confirmed from the Neptune User Guide (`instance-types.html`, 2026-11-17); the 30-day window, 10M I/O and 1 GB storage are unconfirmed. Includes the EMR-created `emr-service-vpce` endpoint (2a RISK R2). Says plainly: idle is not free.

## Also in step 2b

### Root outputs (`environments/course/outputs.tf`)

`region`, `runtime_enabled`, `api_url`, `api_id`, `ecr_repository_url`, `ecr_repository_name`, `ecs_cluster_name`, `ecs_service_names` (map by role), `ecs_task_definition_families` (map incl. ops), `ops_task_definition_family`, `app_subnet_ids`, `ops_security_group_id`, `alb_dns_name`, `state_machine_arn`, `job_queue_url`, `embedding_queue_url`, `queue_names` (map), `data_bucket_name`, `data_prefixes` (map), `emr_log_uri`, `vector_bucket_name`, `dynamodb_table_names` (map of six), `neptune_endpoint`, `neptune_loader_role_arn`, `vpc_id`, `nat_gateway_id`, `app_key_secret_arns_by_env_var` (ARNs, not values), `deployment_role_arn`, `log_group_names`, `alarm_topic_arn`, `dashboard_name`. None holds a secret value.

### `config/course.json`

- Plain JSON, no comments, env-var NAMES only; `jq empty` exit 0. Keys of the current `ConfigLoader` (after Phase 11): `configVersion` 1, `environment` "course", `aws.region`, `storage.vectorIndex`, `entityResolution` (er-v2), `models`, `graph`, `bridge`, and the sections `objectStore` (six prefixes), `neptune`, `vectors`, `embedding` (voyage, `SCHOLARCHAIN_VOYAGE_API_KEY`), `llm` (anthropic, `SCHOLARCHAIN_ANTHROPIC_API_KEY`), `runtime`, `service` (`SCHOLARCHAIN_SERVICE_API_KEY`, `SCHOLARCHAIN_SERVICE_READONLY_API_KEY`).
- Left out (set by the task definitions from Terraform values; names listed in area 10): `aws.accountId`, `storage.rawBucket`, `storage.curatedBucket`, `storage.graphBucket`, `storage.vectorBucket`, `storage.neptuneEndpoint`, `storage.dynamoDbTable`, the three `objectStore.*Bucket`, the six `dynamoDb.*Table`, `service.jobQueueUrl`.
- `objectStore.*Prefix` = `raw`, `curated`, `graph`, `rag` (semantic context), `reports`, `logs`: the same strings as the data-lake prefixes the IAM policies use.
- `service.port` 8080 = Terraform `service_port`; `service.syncDeadlineMillis` 25 000 (<= 25 000); `service.jobLeaseMillis` 120 000 = root `job_lease_seconds` 120; `service.jobMaxAttempts` 3 = root `job_max_attempts` 3; `jobTtlSeconds` 86 400 = job queue retention.
- Checked by reading `ConfigValidator` (not by running it; no sbt in this phase): prefixes match the prefix rule and the six zones are distinct; jobLease > syncDeadline; jobTtl x 1000 > jobLease; service key names differ from each other and from the provider key names and are not reserved; graphTimeoutMillis <= 30 000; putBatchSize <= 500; reviewThreshold <= matchThreshold; defaultWindowLabel is a window label.
- The image holds only `app.jar` today: adding `/opt/scholarchain/config/course.json` to the image is a `phase-13` item for build-engineer.

## Plan summary and checksum

No plan in this step (decision 9: the human runs `scripts/plan.sh`). Checksum: none (no plan file). Inferred from the code, not from a plan; all creates, nothing changed or removed in 2a's set except that the S3 Vectors bucket policy (count 0 in 2a) is now created.

What step 2b's configuration would create with `runtime_enabled = false` (about 65 resources):
- runtime (41): ECR repository + lifecycle policy; ECS cluster; 5 task definitions (query, evidence, graph, worker, ops); 4 ECS services (desired 0); internal ALB, 3 target groups, 1 listener, 3 listener rules; API Gateway VPC link, HTTP API, integration, `$default` route, `$default` stage; 4 scaling targets (min 0, max 0); 6 IAM roles (execution, query, evidence, graph, worker, ops) and 6 inline policies.
- workflow (11): 2 queues, 2 DLQs, 2 redrive-allow policies, 2 queue policies, state machine role + inline policy, 1 state machine.
- observability (12): 5 log groups, SNS topic + topic policy, 4 alarms (2 DLQ, ALB 5xx, Neptune CPU), 1 dashboard.
- vectors (1): the vector bucket policy (2a resource, now count 1).
- With `runtime_enabled = true` (Phase 14) it adds: 3 target-tracking policies (Application Auto Scaling then creates about 6 CloudWatch alarms outside Terraform), 2 worker step policies, 2 worker alarms, 3 HealthyHostCount alarms; services change to desired 1 (via the scaling targets) and the task definitions get new revisions (image tag, graph run).

## IAM changes (step 2b)

- New roles: ECS execution role (shared), five task roles (query, evidence, graph, worker, ops), Step Functions pipeline role. All `name_prefix` `scholarchain-course-...` (within the deployment role's IAM scope, <= 38 characters). Runtime roles are separate from the deployment role. No AWS managed policy is attached (so `deployment_attachable_policy_arns` needs no addition).
- Resource policies: two SQS queue policies, one SNS topic policy, the S3 Vectors bucket policy (allow list: query, worker, ops roles).
- Per-role actions: area 10 table and area 11 role bullets.

### Every `*` in a step 2b policy, with its reason

| Where | Statement | `*` in | Reason |
|---|---|---|---|
| runtime/iam.tf | `EcrAuthToken` | Resource | `ecr:GetAuthorizationToken` has no resource type in the Service Authorization Reference; own statement. |
| workflow/sfn.tf | `CreateTaggedClusters` | Resource | `elasticmapreduce:RunJobFlow` has no resource type (reference read 2026-11-17); narrowed by `aws:RequestTag/project` and `aws:RequestTag/for-use-with-amazon-emr-managed-policies`. |
| workflow/sfn.tf | `LogDelivery` | Resource | CloudWatch Logs delivery actions support no resource type (Step Functions guide `cw-logs.html`). |
| workflow/main.tf | `DenyInsecureTransport` (Deny) | Principal, `sqs:*` action | A Deny for every principal without TLS. |
| observability/alarms.tf | `DenyInsecureTransport` (Deny) | Principal | A Deny for every principal without TLS. |

Pattern wildcards (not bare `*`): EMR `cluster/*` of this account and Region (no cluster ID exists before a run; the guide's dynamic-resource templates); `AWSServiceRoleForEMRCleanup*` path (EMR guide statement, account narrowed); log-group `:log-stream:*` and `:*`; data-lake `rag/*` and `graph/*` objects; `neptune-db:<region>:<account>:<cluster-resource-id>/*`; trust `aws:SourceArn` `arn:aws:ecs:<region>:<account>:*` and `arn:aws:states:<region>:<account>:stateMachine:*`.

## Stateful-resource actions (step 2b)

Creates only: 4 SQS queues (messages are transient; DLQ retention 14 days), 5 log groups (7-day retention), the ECR repository (`force_delete = false`). No replacement or removal. `cleanup_mode` turns DynamoDB and Neptune protection off only when the human sets it.

## Cost estimate

See `infra/terraform/COSTS.md` (all `estimate (unverified)`): step 2b adds the internal ALB (about $0.54/day, also while the runtime is off), alarms and the dashboard (cents), Fargate about $1.90/day when the runtime is on at minimum counts, API Gateway, SQS and Step Functions per use (about $0 at course traffic).

## Required environment variables by name

- Task definitions: the 17 `SCHOLARCHAIN_CFG_*` names and the four secret names listed in area 10.
- Human shell (step 2c smoke test): `SCHOLARCHAIN_SERVICE_API_KEY`.
- New root Terraform inputs (all with defaults): `runtime_enabled`, `image_tag`, `graph_run_id`, `runtime_service_max_count`, `runtime_worker_max_count`, `api_throttling_burst_limit`, `api_throttling_rate_limit`, `job_lease_seconds`, `job_max_attempts`, `cleanup_mode`, `pipeline_timeout_seconds`, `log_retention_days`. Changed description: `vectors_data_principal_arns` (now EXTRA principals).

## Items by status (for the orchestrator's requirement table)

- done: area 9; area 10 (except the deviation below); area 11; area 12; area 13; root outputs; config/course.json.
- deviation: `/healthz` and `/readyz` routed through the ALB to query (decision says anything else gets a fixed 404).
- phase-13: config file in the image (build-engineer); finishing the scripts and passing the git commit as `codeVersion` (step 2c/Phase 13; the state machine already takes it); the first `s3://` run (EMR jobs and loaders); loader gaps named by the work order as phase-13 gaps for their owners: RagIngestJob S3 export read (rag-engineer), DynamoDB loader and Neptune CSV/bulk-load main classes (storage-engineer).
- gaps routed to other owners (not repaired here): JsonlExport local-only paths (spark-engineer); evidence/explain without a route deadline (service-engineer); no custom CloudWatch metrics for LLM usage and S3 Vectors clients (rag-engineer/storage-engineer); no configuration key for the embedding queue URL (domain-engineer, if the embedding queue is ever used).

## Proposed later

- Tag conditions (`aws:ResourceTag/project`) on the Step Functions role's cluster actions: the reference lists the key for several cluster actions; not checked for every one in this step.
- Container `healthCheck` in the task definitions (needs a probe tool in the image) and `readonlyRootFilesystem` with a writable `/tmp` volume.
- Services publish custom metrics (CloudWatch embedded metric format) for LLM usage, S3 Vectors client calls and retries.
- A CloudWatch agent on EMR writing to `/scholarchain/course/emr`.
- A customer-managed KMS key for the SNS topic, logs and EMR disks (cost: monthly per key; carried over from 2a).
- Interface endpoints for ECR, Secrets Manager, CloudWatch Logs, STS (2a list; would take Fargate pulls off the NAT).

## INTERFACE HANDOFF (for step 2c)

- Files changed: see FILES CREATED / FILES MODIFIED.
- Root outputs for the scripts (`terraform -chdir=infra/terraform/environments/course output -json`): smoke-test.sh -> `api_url`; deploy.sh -> `ecs_cluster_name`, `ecs_service_names` (map by role), `ecs_task_definition_families`, `ecr_repository_url`, `ecr_repository_name`, `region`; rollback.sh -> `ecs_cluster_name`, `ecs_service_names`; run-bounded-pipeline.sh -> `state_machine_arn`, `data_bucket_name`, `data_prefixes`, `region` (execution input shape in `modules/workflow/sfn.tf` header and area 11); cleanup.sh -> `api_id`, `alb_dns_name`, `vpc_id`, `nat_gateway_id`, `ecs_cluster_name`, `ecr_repository_name`, `data_bucket_name`, `vector_bucket_name`, `dynamodb_table_names`, `neptune_endpoint`, `queue_names`, `log_group_names`, `alarm_topic_arn`, `dashboard_name`, `state_machine_arn`; ops run-task -> `ops_task_definition_family`, `app_subnet_ids`, `ops_security_group_id` (no public IP), container name `ops`, command = `<main class> <args>`.
- Root variables a script may pass: `runtime_enabled`, `image_tag` (40-hex git SHA), `graph_run_id` (`run:v1:<32 hex>`), `cleanup_mode`. `plan.sh` must pass `-var-file=course.tfvars`; with the runtime off no other value is needed except `owner` (local.auto.tfvars).
- ECS container names: `app` (four services), `ops` (ops task). Services are named `query`, `evidence`, `graph`, `worker` inside cluster `scholarchain-course-runtime`.
- Limits: ECS max 2 per service (1..4); API burst 10 / rate 5 rps; pipeline timeout 14 400 s; EMR bounds from 2a; S3 Vectors PutVectors <= 500, topK <= 30.
- Thread-safety: not applicable.

VALIDATION EVIDENCE
- fmt: terraform fmt -recursive -check infra/terraform -> 0, no files listed
- validate: terraform -chdir=infra/terraform/environments/course validate -no-color -> "Success! The configuration is valid."
- shellcheck: shellcheck infra/terraform/modules/emr/files/bootstrap.sh -> 0; clean (no script written in step 2b)
- jq: jq empty config/course.json -> 0
- finished: 2026-11-17 02:43:06 CST
- files changed after these runs: no
