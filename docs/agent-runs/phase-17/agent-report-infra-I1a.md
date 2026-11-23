# agent-report-infra-I1a

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, verified with shasum at start), step I1a, decision 7 items 1 and 6 (tier 1 only). HEAD 50ba087ecd33178eaa8ccc69286b9de85e8a8146.
FILES CREATED:
- scripts/release-manifest.sh (sha256 09f6106277d549ba251d4781d845b89042aee9f1045c02dfe45ab1179b5d54c2, 1050 lines, mode 0644)
FILES MODIFIED:
- scripts/check.sh (gate 9: new function probe_release_manifest, called last in gate_probes; --help gate text; header status line) (sha256 a892d0652af2fd104ce686fca33333df381824bd5bdad06e6664b3f885cbe1a0)
- scripts/README.md (exit-code line names exit 8 of release-manifest.sh; new section "Release manifest (Phase 17, decision 7 item 1)") (sha256 403d3085ae255213a0298b80329c563a07c80bbcee5f964b4f88b02e70091590)
- No file under infra/terraform/**, config/** or outside scripts/** was changed.
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-17/prompt.md (matched the work-order checksum)
- read-only: ls, wc, head -n 5 .claude/.last-test-run, git ls-files, git log -1 (commit-prefix check), git status --short, git diff --stat, git check-ignore -v, grep over scripts/, docs/agent-runs/, datasets/, experiments/, releases/, infra/terraform/modules (*.tf only; never over infra/terraform/environments/course/, whose files were read with the Read tool: variables.tf, course.tfvars; main.tf by grep -n on that one named file)
- shellcheck --version; shellcheck -x -P SCRIPTDIR scripts/release-manifest.sh scripts/check.sh
- terraform version; terraform fmt -recursive -check infra/terraform; terraform -chdir=infra/terraform/environments/course validate
- aws-docs MCP: search_documentation, read_documentation, read_sections (documentation only; treated as untrusted data)
- Not run: any script (no --help either), bash, python, sbt, aws-api MCP, aws CLI, terraform init/plan.
- One Bash grep (recursive from the repository root, looking for the graph run ID) was denied by the auto-mode classifier; I did not retry it in that form and used narrower paths (experiments, config, scripts) instead.
TEST RESULTS: No sbt run (scripts only; standing rule). The new script and the new probes have not been executed (`runtime: untested`); E4 (check.sh in the fresh clone) runs the probes first, E5 dry-runs the success path, E10 is the real run.
VALIDATION RESULTS: shellcheck 0.11.0 clean on scripts/release-manifest.sh and scripts/check.sh (exit 0). terraform fmt -recursive -check infra/terraform exit 0. terraform validate: "Success! The configuration is valid." (exit 0). check.sh gate 8 patterns simulated with grep on the new script: no `.NAME.value` jq path, no `$(out NAME)`, no org.scholarchain class name, no `# contract:` marker (so gate 8 is unaffected).
ASSUMPTIONS:
- The release candidate is HEAD of the working tree where the script runs; the clone's HEAD must equal it (decision 7.1).
- "Spark graph run" = every `run:v1:HEX32` named in a committed experiments/raw/*/*/run-record.json (today the Phase 14 run, named in the `dataset_snapshot` field of the D-query, D-read and E records); the manifest says "named in" with the paths.
- Local-only evidence archives are found from committed `EVIDENCE-ARCHIVE-SHA256: HEX64` lines (today docs/agent-runs/phase-15/approvals.md:78) and written as "local only" with that line as source, never as a repository path.
- Signed experiment manifests are the experiments/manifests/*.json whose HEAD sha256 equals a committed `APPROVED-MANIFEST-SHA256:` line; a signed checksum without a matching manifest is listed with path null.
- Dataset snapshots are read line by line from the fixed layout of datasets/dataset-manifest.yaml (no YAML library): snapshot id/doi/publication_date/published/cutoff, file md5 entries, and checksum keys ending in md5 or sha256 with a hex value plus `published` and `algorithm`, each with its line number.
- Date checked for every limits row: 2026-11-22 (the session's date).
RISKS:
- runtime: untested. The jq programs, the YAML line reader, the run-folder grouping and the rename path have never executed. The E5 dry run (`--out-dir /tmp/p17-manifest-dryrun-STAMP`) is the first execution of the success path; a failure there is a defect for infra-engineer (repair round), not an expected result.
- Identifier check false positive: any alphanumeric token of exactly 12 digits that is not a placeholder or a commit prefix of this repository stops the run with exit 8 (by design, fail closed). Nothing known today produces one in the manifest (no byte counts of 12 digits are copied), but only the E5 dry run proves it.
- Jar checksums depend on the build: `package.sh` (E5) runs `sbt clean` and rebuilds the four jars, so their sha256 change. E11's recomputation must hash the jars as they are after E10; nothing may rebuild them between E10 and E11.
- A jar whose build info says buildDirty=true, or a buildCommit other than the build commit, is refused (exit 6), by design.
- The probes need `git`, `unzip` and `jq` (check.sh already requires git and jq; `unzip` is not in check.sh's prerequisite list, so without unzip the marker/clone/jar probes would fail on the tools check (exit 3 with another message) and gate 9 would report them failed. macOS and most Linux images have unzip.)
- Limits findings (Terraform frozen, report only): (1) Fargate On-Demand vCPU quota default is 6 per Region; at full scale the services need 6 vCPU and the ops task 1 more (7 vCPU, estimate from task_sizes and max counts), so full scale with an ops task exceeds the documented default; the account's applied quota was not checked (no aws-api call). (2) Fargate ARM64 is not supported in us-east-1 AZ ID use1-az3; the network picks the first two AZ names (modules/network/main.tf:22) and does not exclude that AZ ID. Phase 14/15 tasks ran, so this did not block the course run, but it is a documented limitation for a new account.
MANUAL ACTION REQUIRED:
- None for the executable bit: every script in scripts/ is mode 0644 and is run as `bash scripts/NAME.sh`; release-manifest.sh follows that (no chmod needed). If the human prefers it executable, `chmod +x scripts/release-manifest.sh` is harmless.
- Human runs: E4 (check.sh probes), E5 dry run, E10 real run (decision 18).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none (the stop hook did not block this agent at the time of writing; rag-engineer's in-progress files are modules/rag/** and modules/rag test files listed by git status, none of them mine)
NEXT GATE: R-1 (code-reviewer reviews scripts/release-manifest.sh, the check.sh probe block and the README section); W2, W3, W5 consume the INTERFACE HANDOFF below; human E4, E5, E10.

## Mandatory jars (decision 7.1)

| Jar | Path in the clone (glob, exactly one match) | Built by check.sh? | Otherwise build in the clone with |
|---|---|---|---|
| hadoop | modules/hadoop/target/scala-*/scholarchain-hadoop-assembly-*.jar | yes: gate 7 runs `sbt -batch packageCheck`, which depends on hadoop/assembly | `sbt -batch packageCheck` (all four) or `sbt -batch packageJobs` |
| entity-resolution | modules/entity-resolution/target/scala-*/scholarchain-entity-resolution-assembly-*.jar | yes (packageCheck depends on entityResolution/assembly) | `sbt -batch packageCheck` or `sbt -batch packageJobs` |
| spark-analytics | modules/spark/target/scala-*/scholarchain-spark-analytics-assembly-*.jar | yes (packageCheck depends on sparkAnalytics/assembly) | `sbt -batch packageCheck` or `sbt -batch packageJobs` |
| services | modules/services/target/scala-*/scholarchain-services-assembly-*.jar | yes (packageCheck depends on services/assembly) | `sbt -batch packageCheck`; `packageJobs` does NOT build it (build.sbt:712-715: hadoop, entityResolution, sparkAnalytics only), so use `sbt -batch services/assembly` |

Sources: project/PackageCheck.scala:191-212 (Inputs and run: hadoop, er, spark, services), build.sbt:581-596 (packageCheck task inputs), build.sbt:712-715 (packageJobs alias), scripts/package.sh:433-440 (paths). None is optional. `sbt clean check` (E3) alone does not assemble jars; check.sh at E4 does (gate 7, packageCheck after sbt check).

## Verified limits and Region availability, infra side (decision 7 item 6)

Counts: 21 rows; verified 17; unverified 4; not checked 0. Requirement status under decision 2: deviation (unplanned) (4 unverified rows, reasons in the rows). All URLs read through the aws-docs MCP server on 2026-11-22.

| # | Fact relied on | Value used (file:line) | Documented value or availability | URL | Checked | Status |
|---|---|---|---|---|---|---|
| 1 | EMR release emr-7.14.0 (Hadoop MapReduce) exists | `emr-7.14.0` (infra/terraform/environments/course/variables.tf:77; wired main.tf:86; modules/emr/variables.tf:57) | Release 7.14.0 published, initial release Sep 8, 2026, standard support to Sep 8, 2028; includes Hadoop 3.4.2-amzn-2 | https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-7140-release.html | 2026-11-22 | verified |
| 2 | emr-7.14.0 available in us-east-1 | Region us-east-1 (infra/terraform/environments/course/course.tfvars:6) | The release guide says new releases roll out to Regions over several days; no per-Region list is published | https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-whatsnew.html | 2026-11-22 | unverified (no page states us-east-1; runtime evidence only: the Phase 15 p15-a-hadoop runs under experiments/raw/p15-a-hadoop/) |
| 3 | EMR Spark release emr-spark-8.1.0 exists | `emr-spark-8.1.0` (variables.tf:83; main.tf:87; modules/emr/variables.tf:68) | "AWS runtime for Apache Spark (emr-spark-8.1.0)", LTS, initial release Sep 8, 2026; Spark 4.1.1, Scala 2.13.17 | https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark810-release.html | 2026-11-22 | verified |
| 4 | emr-spark-8.1.0 available in us-east-1 | Region us-east-1 (course.tfvars:6) | No per-Region availability list found (searched the Release Guide; only the EMR on EKS labels list Regions) | https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark810-release.html | 2026-11-22 | unverified (runtime evidence only: Phase 14 D8/D9 and experiments/raw/p15-a-spark/) |
| 5 | EMR managed scaling with unit type Instances, minimum 2, maximum 4 core instances (max On-Demand and max core = maximum) | variables.tf:119 (min 2), variables.tf:125 (max 4); modules/emr/main.tf:66-71 (unit_type "Instances") | ComputeLimits: UnitType one of InstanceFleetUnits, Instances, VCPU; MinimumCapacityUnits and MaximumCapacityUnits are required integers that bound core and task nodes; MaximumCoreCapacityUnits and MaximumOnDemandCapacityUnits optional | https://docs.aws.amazon.com/emr/latest/APIReference/API_ComputeLimits.html | 2026-11-22 | verified |
| 6 | One EMR cluster stays within the account's On-Demand Standard vCPU quota (emr_vcpu_budget 32, "quota 64 shared"; primary + 4 core m5.xlarge = 20 vCPU) | variables.tf:131 (32), :101 and :107 (4 vCPU each), :89 and :95 (m5.xlarge) | The page describes per-Region vCPU quotas for On-Demand instance families, adjustable per account; the applied value is account-specific (Service Quotas), not a documented constant | https://docs.aws.amazon.com/ec2/latest/instancetypes/ec2-instance-quotas.html | 2026-11-22 | unverified (account value needs Service Quotas; no aws-api call allowed in this phase) |
| 7 | EMR idle auto-termination 900 s | variables.tf:137; modules/emr/main.tf:73; modules/workflow/sfn.tf:158 | IdleTimeout minimum 60 s, maximum 604,800 s (7 days) | https://docs.aws.amazon.com/emr/latest/APIReference/API_AutoTerminationPolicy.html | 2026-11-22 | verified |
| 8 | Fargate Linux ARM64 in us-east-1 | modules/runtime/main.tf:161 and :227 (`cpu_architecture = "ARM64"`) | ARM64 on Fargate needs Linux and platform 1.4.0 or later; the only Region exception listed is US East (N. Virginia) AZ ID `use1-az3` | https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-arm64.html | 2026-11-22 | verified (Region supported; note: modules/network/main.tf:22 picks the first two AZ names and does not exclude use1-az3; see RISKS) |
| 9 | Fargate task sizes per role: query and worker 1024 CPU / 2048 MiB; evidence and graph 512 / 1024; ops 1024 / 4096 | modules/runtime/variables.tf:80-84 | Valid Linux combinations: 512 CPU with 1-4 GB; 1024 CPU with 2-8 GB | https://docs.aws.amazon.com/AmazonECS/latest/developerguide/fargate-tasks-services.html | 2026-11-22 | verified |
| 10 | ECS service maximums: query, evidence, graph at most 2 each; worker at most 2; minimum 1 (ALB services) or 0 (worker) when the runtime is on, 0/0 when off | variables.tf:241 (service max 2), :247 (worker max 2); modules/runtime/autoscaling.tf:32-36, :49-50 | Tasks per service (desired count): 5,000, not adjustable | https://docs.aws.amazon.com/general/latest/gr/ecs-service.html | 2026-11-22 | verified |
| 11 | Fargate On-Demand vCPU quota covers the runtime at full scale | sum at maximum (estimate from modules/runtime/variables.tf:80-84 and variables.tf:241, :247): 2x1 + 2x0.5 + 2x0.5 + 2x1 = 6 vCPU, plus one ops task (1 vCPU, one at a time) = 7 vCPU | "Fargate On-Demand vCPU resource count", default 6 per Region, adjustable; new accounts may start lower and rise with usage | https://docs.aws.amazon.com/general/latest/gr/ecs-service.html | 2026-11-22 | verified (documented default; 7 vCPU at full scale with an ops task exceeds it; the account's applied quota was not checked; see RISKS) |
| 12 | Neptune engine 1.4.7.1 in us-east-1 | variables.tf:161; modules/neptune/main.tf:59 and :84; modules/neptune/variables.tf:35 (1.4.x.y only) | "As of 2026-09-18, AWS deploys engine version 1.4.7.1 to all Regions"; openCypher Neptune-9.0.20190305-1.0 | https://docs.aws.amazon.com/neptune/latest/userguide/engine-releases-1.4.7.1.html | 2026-11-22 | verified |
| 13 | Neptune instance class db.t4g.medium is offered | variables.tf:167; modules/neptune/main.tf:85; modules/neptune/variables.tf:43 | t3 and t4g are offered only in medium size (t3.medium, t4g.medium), free-trial eligible, not for production | https://docs.aws.amazon.com/neptune/latest/userguide/instance-types.html | 2026-11-22 | verified |
| 14 | db.t4g.medium available in us-east-1 | course.tfvars:6 | The instance-types page does not list Regions per class | https://docs.aws.amazon.com/neptune/latest/userguide/instance-types.html | 2026-11-22 | unverified (no Region list found; runtime evidence only: the Phase 14/15 Neptune cluster) |
| 15 | API Gateway HTTP API default stage throttle: burst 10, rate 5 rps | variables.tf:253 (burst 10), :259 (rate 5); modules/runtime/ingress.tf:121 (protocol HTTP), :152-155 | Route-level limits can't exceed the account-level limits; account quota across HTTP, REST and WebSocket APIs: 10,000 rps with a 5,000-request bucket (us-east-1 is not in the 2,500 rps list) | https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-throttling.html and https://docs.aws.amazon.com/apigateway/latest/developerguide/limits.html | 2026-11-22 | verified |
| 16 | SQS maxReceiveCount: job queue 5 (service.jobMaxAttempts 3 + 2), embedding queue 5 | variables.tf:271 (3); config/course.json:100 (jobMaxAttempts 3); modules/workflow/main.tf:17, :23, :28, :54; modules/workflow/variables.tf:141 (5) | Maximum receives: valid range 1 to 1,000 | https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-configure-dead-letter-queue.html | 2026-11-22 | verified |
| 17 | SQS visibility timeouts: job queue 150 s (lease 120 s + 30 s margin), embedding queue 300 s | variables.tf:265 (120); config/course.json:101 (jobLeaseMillis 120000); modules/workflow/variables.tf:112 (30), :135 (300); modules/workflow/main.tf:15, :47 | VisibilityTimeout: integer 0 to 43,200 s (12 hours), default 30 | https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_SetQueueAttributes.html | 2026-11-22 | verified |
| 18 | SQS retention: job 86,400 s, embedding 345,600 s, both DLQs 1,209,600 s | modules/workflow/variables.tf:129, :152; modules/workflow/main.tf:27, :37 | MessageRetentionPeriod: 60 to 1,209,600 s (14 days), default 345,600 | https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_SetQueueAttributes.html | 2026-11-22 | verified |
| 19 | S3 Vectors available in us-east-1 | course.tfvars:6; variables.tf:11 (us-east-1 only); modules/vectors/main.tf:28, :38 | US East (N. Virginia) us-east-1 is listed (endpoint s3vectors.us-east-1.api.aws) | https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-regions-quotas.html | 2026-11-22 | verified |
| 20 | Budget `scholarchain-25` ($25) with email alerts at $10, $15 and $20 (hand-made, outside Terraform, never managed or imported) | docs/agent-runs/phase-12/prompt.md:114; docs/agent-runs/instructor-approvals.md:39; docs/agent-runs/phase-13/prompt.md:140 (not in infra/terraform/** or config/**) | A budget can have up to ten notifications; each needs at least one subscriber; up to 10 email subscribers per notification | https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-budgets-budget-notification.html | 2026-11-22 | verified (three notifications fit; the live budget itself was not inspected: no aws-api call) |
| 21 | CloudWatch log retention 7 days on every log group | variables.tf:294; modules/observability/main.tf:33 | retentionInDays possible values include 7 (1, 3, 5, 7, 14, 30, ...) | https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutRetentionPolicy.html | 2026-11-22 | verified |

Not in the table (not checked, outside I1a's list, proposed later): EMR m5.xlarge availability per AZ; the HTTP API 30 s integration timeout (modules/runtime/ingress.tf:134-135; the only page found was a Solutions guide, not the API Gateway quota page).

## INTERFACE HANDOFF

Files changed: scripts/release-manifest.sh (new), scripts/check.sh (gate 9 probes, help text, header line), scripts/README.md (exit-code line, new "Release manifest" section). Thread-safety: single-run, no shared state; not safe to run twice at once into the same --out-dir (the second would refuse when RC_SHA/ exists, or race on rename; the post-rename check catches the race with exit 5).

Command line (run from the repository root, zsh one line):
`bash scripts/release-manifest.sh --clone /tmp/p17-clone-STAMP --marker /tmp/p17-E3-marker.txt --out-dir releases/manifest`
Dry run (E5): same with `--out-dir /tmp/p17-manifest-dryrun-STAMP`. `--help` prints the full contract (first line `Usage: scripts/release-manifest.sh --clone PATH --marker PATH --out-dir PATH`).

Options (all required): `--clone PATH` (top folder of the fresh clone; not this tree; HEAD equal to this HEAD), `--marker PATH` (saved copy of E3's `.claude/.last-test-run`: `timestamp=`, `module_tests=`, `contract_tests=`), `--out-dir PATH` (inside this repository only `releases/manifest`; outside, any folder whose parent exists; created if missing). `--help`/`-h`.

Order of checks: options (2) -> repository root (4) -> tools git, jq, unzip, cut, grep, mktemp, shasum or sha256sum (3) -> --marker exists (3) -> --clone is the top folder of a Git working tree (3), not this tree (4) -> clone HEAD equals this HEAD (3) -> exactly one of each mandatory jar (3 missing, 6 more than one) -> this tree clean (4) -> --out-dir accepted and OUT_DIR/RC_SHA absent (4) -> read inputs (6 when a mandatory input is missing or malformed) -> identifier check on both documents in a private temporary folder (8) -> write into OUT_DIR/.incomplete-RC_SHA-XXXXXX -> validate (6) -> identifier check again (8) -> HEAD unchanged (4) -> rename to OUT_DIR/RC_SHA (5 on failure).

Exit codes: 0 written; 2 usage; 3 missing prerequisite (tool, --marker file, --clone not a Git working tree, clone HEAD differs, mandatory jar missing, --out-dir parent missing); 4 refused (not the repository root, --clone is this tree, dirty tree, --out-dir not accepted, RC_SHA/ exists, HEAD moved); 5 command failed or INT; 6 check failed (malformed marker, input missing at HEAD, more than one jar per pattern, jar build info, validation); 7 TERM; 8 an identifier would be written.

Output paths: `OUT_DIR/RC_SHA/release-manifest.json` and `OUT_DIR/RC_SHA/release-manifest.md` (RC_SHA = full 40-hex HEAD; folder 0755, files 0644). On failure after the temporary folder exists: `OUT_DIR/.incomplete-RC_SHA-XXXXXX/` is left, printed as `INCOMPLETE-FOLDER: PATH` (never commit; remove; run again). Private temporary folder `${TMPDIR:-/tmp}/scholarchain-release-manifest.XXXXXX` is removed on exit.

Printed lines: `STEP: release-manifest.sh NAME` (args, repo-root, prerequisites, marker, clone, jars, clean-tree, out-dir, collect-identity, collect-approval-lines, collect-release-records, collect-experiment-manifests, collect-datasets, collect-run-ids, collect-prompts, collect-models, collect-tests, collect-jars, collect-key-artifacts, assemble, render-markdown, identifier-check, write, validate, publish, summary, done); `INFO:`; `IDENTIFIER-CHECK: DOC line N holds a KIND` (never the value); four `SUMMARY: release-manifest ...` lines (rc, build, tag; release_records, with_committed_approval, signed_plans, signed_experiment_manifests; datasets, spark_graph_runs, experiment_attempts, evaluation_runs, emulator_runs, local_only; prompts, jars, jars_not_present=0, key_artifacts, module_tests, contract_tests); `MANIFEST-FOLDER: PATH`; `RESULT: release-manifest.sh ok|failed exit=N step=NAME`.

JSON fields (release-manifest.json, kind `scholarchain-release-manifest`, version 1), each with its source:
- `generated_utc`; `generator` {script, script_sha256_at_head}
- `release_candidate` {commit, source "git rev-parse HEAD (this working tree, clean)"}
- `build_commit` {commit, source: scripts/lib/identity.sh build_commit rule}
- `planned_tag` {name "v1.0-submission", source}
- `clone` {folder_name (basename only), head, head_equals_release_candidate}
- `release_records[]` {source (releases/course/FILE.json), attempt_id, environment, region, commit, plan_commit, build_commit, plan_id, plan_sha256, applied_utc, runtime_enabled, image {repository, tag, digest}, services {ROLE: {task_definition, previous_task_definition, image_tag, digest_check}}, approval_line_committed (bool), approval_lines[] {path, line}}
- `signed_plans[]` {plan_sha256, signed_in {path, line}}
- `signed_experiment_manifests[]` {path (or null), sha256, signed_in[] {path, line}, note (only when path null)}
- `datasets` {source "datasets/dataset-manifest.yaml", source_sha256, entries[] {dataset, snapshot[] {key, value, line}, checksums[] {key, value, line}, file_md5[] {file, md5, line}}}
- `run_ids` {spark_graph_runs[] {run_id, named_in[]}, experiment_attempts[] {path, manifest_id, attempt_id, run_record_status}, evaluation_runs[] {path, kind, run_id}, emulator_runs[] {path, run_id}, local_only[] {kind "evidence archive", location "local only ...", sha256, sha256_source {path, line}}, source}
- `prompts[]` {path, file, sha256}
- `models` {source "config/course.json", embedding_model, answer_model, embedding_provider, embedding_dimensions, answer_provider: each {field (jq path, e.g. ".models.embeddingModelId"), id}}
- `tests` {label "E3 (sbt clean check in the fresh clone)", tested_sha, tested_sha_source, marker_timestamp, module_tests, contract_tests, source {option "--marker", file_name, sha256}}
- `jars[]` {name, module, path_in_clone, sha256, bytes, status "present", build_info {buildCommit, buildDirty false, module}}; `jar_source`
- `key_artifacts[]` {path, sha256}; `key_artifacts_source` (dataset manifest, config/course.json, project/PackageCheck.scala, signed experiment manifests, experiments/evaluation/suite-v1.json, experiments/evaluation/evaluate.py, every frozen-files-sha256-E12.txt, every tracked *.sha256 under docs/agent-runs/ and experiments/, the approval files holding EVIDENCE-ARCHIVE-SHA256 lines, releases/course/*.json)
- `rule_line` (verbatim the decision 7.1 sentence, also printed as its own line in the Markdown); `identifier_check`
Markdown (release-manifest.md): the same content as tables under the headings "Rule for commits after the release candidate", "Release records (releases/course/)", "Signed plan checksums", "Signed experiment manifests", "Dataset snapshots and checksums (...)", "Run IDs (committed paths at HEAD)", "Prompt versions", "Models (config/course.json)", "Test counts", "Mandatory jars (in the clone)", "Key committed artifacts".

check.sh change (gate 9, function probe_release_manifest, run last in gate_probes): probes `help-release-manifest` (0), `release-manifest-no-options` (2), `release-manifest-unknown-option` (2), `release-manifest-missing-marker` (3), `release-manifest-clone-not-git` (3), `release-manifest-clone-head-differs` (3), `release-manifest-jar-missing` (3), in-process `release-manifest-wrote-nothing` (the --out-dir folder under the private temporary folder must not exist after the refusals); fixture failure is recorded as `release-manifest-fixtures` failed. Fixtures: an empty folder, `git init` plus one empty commit (with -c user.name/user.email/commit.gpgsign=false), and `git clone -q --shared --no-checkout` of the checkout with `update-ref --no-deref HEAD` to this HEAD; all under check.sh's private temporary folder; nothing in the repository is touched. Probe records go to the existing `probes` array of the check record.

Limits table: section above (21 rows; 17 verified, 4 unverified, 0 not checked); W2 copies it into docs/service-limits.md with the three counts at the top (decision 2).

## Proposed later (not built)

- Add `unzip` to check.sh's prerequisite list (one line) so a missing unzip fails early with its name instead of as probe failures.
- Infrastructure items from the limits check (Terraform is frozen): exclude AZ ID use1-az3 for Fargate ARM64 subnets (data source by zone ID), and a plan-time check that the runtime's maximum vCPU (services at max plus one ops task) fits the account's Fargate vCPU quota.

## VALIDATION EVIDENCE

- `shellcheck --version`: version 0.11.0.
- `shellcheck -x -P SCRIPTDIR scripts/release-manifest.sh scripts/check.sh`: no output, exit 0 (last run after the final edit of both files).
- `terraform version`: Terraform v1.17.1.
- `terraform fmt -recursive -check infra/terraform`: no output, exit 0.
- `terraform -chdir=infra/terraform/environments/course validate`: "Success! The configuration is valid.", exit 0.
- `git status --short` (mine): ` M scripts/README.md`, ` M scripts/check.sh`, `?? scripts/release-manifest.sh`; every other listed change belongs to other agents (modules/rag/**, experiments/**, docs/**).
- sha256 after the last edit: scripts/release-manifest.sh 09f6106277d549ba251d4781d845b89042aee9f1045c02dfe45ab1179b5d54c2; scripts/check.sh a892d0652af2fd104ce686fca33333df381824bd5bdad06e6664b3f885cbe1a0; scripts/README.md 403d3085ae255213a0298b80329c563a07c80bbcee5f964b4f88b02e70091590.
files changed after these runs: none
