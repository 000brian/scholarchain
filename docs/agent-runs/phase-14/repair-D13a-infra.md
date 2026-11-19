# Repair G-24, infra-engineer report

(Saved in substance by the orchestrator from infra-engineer's final message.)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), session 3, D13a, repair G-24 (class 2, human go)
FILES CREATED: none
FILES MODIFIED: scripts/run-bounded-pipeline.sh (+8/-2, stage_load :1657-1664)
COMMANDS RUN: grep (scripts/, infra/terraform/modules/ only); jq --version (jq-1.8.2); jq -n examples with literal values; shellcheck scripts/run-bounded-pipeline.sh; shasum -a 256; git diff --stat. No script, AWS CLI or aws-api MCP call.
TEST RESULTS: reproduced: `jq -n -c '{c: $ARGS.positional}' --args Main dynamodb-load --config x` -> "jq: Unknown option --config", status 2 (= masked log). New construction on literal words (Main dynamodb-load --config /app/c.json --wait-seconds 30 -- -n '') -> {"containerOverrides":[{"name":"ops","command":[all 9 words in order]}]}.
VALIDATION RESULTS: shellcheck exit 0; sha256 after 40cf0d293c35d858972996df0fb4eac062f2b068272f755e32aa12cdad3bbf54. Orchestrator re-ran shellcheck (exit 0) and shasum: match; git diff = report.
ASSUMPTIONS: the human's admin profile runs every script (scripts/README.md:64).
RISKS: jq 1.6 not available to test; fix uses only --arg, --argjson and `. + [$w]` (jq 1.5+).
MANUAL ACTION REQUIRED: commit; rerun D13a. No plan needed.
NEXT GATE: code-reviewer; human reruns D13a.

## Root cause
run-bounded-pipeline.sh:1657-1658 (old) `--args "${OPS_CMD[@]}"`: jq keeps parsing options after --args, so OPS_CMD's --config (build_ops_command :1608) was a jq option; jq exit 2. run-task never ran. line=1645 is the stage_load() line of the ERR trap.

## Diff
One --arg per word into command_json (same pattern as logical_cmd :1637), then `jq -n --arg n OPS_NAME --argjson c command_json '{containerOverrides: [{name: $n, command: $c}]}'`. All five loaders incl. optional --wait-seconds; bash 3.2 safe; OPS_CMD never empty (check_args :486-508). No other --args, --jsonargs or $ARGS use in scripts/**. README unchanged.

## jq floor
README:13 "jq 1.6 or later (tested target 1.8.2)"; `--args --` needs 1.7.1, so no end-of-options marker used.

## AWS CLI checks (all OK, no change)
- ecs run-task (:1667-1669), https://docs.aws.amazon.com/cli/latest/reference/ecs/run-task.html: FARGATE launch type, count 1 (max 10); awsvpcConfiguration shape (subnets <=16, 1 SG <=5, DISABLED); overrides shape, 8192-char limit (ours ~1 KB); --started-by "scholarchain-pipeline" (:51) valid (<=128); --propagate-tags TASK_DEFINITION valid (SERVICE errors on run-task), ops task def tagged (modules/runtime/main.tf:244 + default_tags); propagation needs ecs:TagResource and iam:PassRole: admin profile has them; deployment role ecs:* (identity/deployment.tf:72), PassRole to ecs-tasks (:216-230); FARGATE/awsvpc task def (main.tf:204-205) so network config required and supplied; container "ops", entryPoint java -cp JAR, command starts with the main class.
- ecs describe-task-definition (:1568), .../ecs/describe-task-definition.html: OK.
- ecs list-tasks --cluster --family --desired-status RUNNING (:1582), .../ecs/list-tasks.html: OK (CLI auto-paginates).
- ecs describe-tasks (:1688), .../ecs/describe-tasks.html: OK; a not-yet-visible task reads UNKNOWN and polling continues.
- logs get-log-events (:1720-1721), .../logs/get-log-events.html: --limit 200, --no-start-from-head, --no-paginate correct; stream PREFIX/NAME/TASK_ID matches awslogs prefix "ops" (main.tf:239); missing stream -> ResourceNotFoundException -> aws_read 5 without retry (:598), function warns and returns 0.
- No S3 or Neptune CLI call in the load stage.

No Terraform, IAM, stateful or cost change.

## INTERFACE HANDOFF
scripts/run-bounded-pipeline.sh stage_load only; CLI, outputs, markers, exit codes unchanged; overrides JSON shape unchanged; jq >= 1.6, bash >= 3.2; overrides < 8192 chars.
