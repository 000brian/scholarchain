#!/usr/bin/env bash
# scripts/run-experiment.sh - run ONE approved experiment manifest
# (experiments/manifest-schema-v1.json), record it, export its CloudWatch
# metrics, and keep the raw results (Phase 13 work order, decision 9.7; spec
# work-order item 7). Owner: infra-engineer (author). Run by the human only
# (Phase 15 standing rules); never by an agent.
#
# Status (Phase 13): static: shellcheck clean; runtime: untested; human
# execution required. Phase 15 decision 9.5 and 9.6 (identifier list,
# {attempt-id}, ALB placeholders, --check, metrics for failed and timed-out
# actions, exit 8, --export-metrics): runtime: untested.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="run-experiment.sh"
readonly EXPECTED_REGION="us-east-1"
readonly TF_DIR="infra/terraform/environments/course"
readonly COST_CAP_USD=5
readonly RUN_ROOT="evidence/experiment-runs"
readonly RAW_ROOT="experiments/raw"
readonly OUTPUT_TIMEOUT=120
readonly AWS_TIMEOUT=120
readonly METRICS_TIMEOUT=300
readonly METRIC_MARGIN_SECONDS=300
# Phase 15 decision 10: CloudWatch data arrive minutes late, so the runner
# waits this long after the action before it exports the window.
readonly METRICS_SETTLE_SECONDS=300
# Phase 15 decision 9.6(c): upper bound of the action's --preflight run.
readonly PREFLIGHT_TIMEOUT=600
readonly MAX_TRIES=3
readonly MAX_LISTED_CLUSTERS=20

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
MANIFEST=""
MANIFEST_SHA_ARG=""
MANIFEST_ID=""
CHECK_MODE="false"
EXPORT_OF=""
HEAD_SHA=""
BUILD_SHA=""
ATTEMPT_ID=""
RUN_DIR=""
RAW_DEST=""
STARTED_WRITTEN="false"
FINALIZED="false"
SIGNAL_NAME=""
STARTED_UTC=""
ACTION_START_EPOCH=""
ACTION_END_EPOCH=""
ACTION_RC="null"
ACTION_KIND=""
METRICS_STATUS="not-run"
STATE_MACHINE_ARN=""
ALB_SUFFIX=""
TG_SUFFIXES_JSON="{}"
RESOLVED_ARGS_JSON="null"
INVOKE_RC=0
EXECUTION_NAMES_JSON="[]"
CLUSTER_IDS_JSON="[]"
NESTED_JSON="[]"
METRIC_FILES_JSON="[]"
UNRESOLVED_JSON="[]"

usage() {
  cat <<'EOF'
Usage: scripts/run-experiment.sh --manifest PATH --manifest-sha256 HEX64
       scripts/run-experiment.sh --manifest PATH --manifest-sha256 HEX64 --check
       scripts/run-experiment.sh --manifest PATH --manifest-sha256 HEX64 --export-metrics ATTEMPT_ID
       scripts/run-experiment.sh --help

Run from the repository root: bash scripts/run-experiment.sh ...

It refuses (exit 4), checked in this order, the first failing check giving
the message, before any cloud call:
  1. a dirty tree (any tracked change or untracked file outside .gitignore);
  2. a manifest that is not tracked by git;
  3. a sha256 that does not equal the file's;
  4. "example": true ("refusing: the manifest is an example");
  5. a manifest that fails the v1 checks of experiments/README.md marked
     "enforced: yes" (format, formatVersion 1, manifestId pattern
     ^[a-z0-9][a-z0-9-]{0,63}$ and file name = manifestId.json, example
     boolean, experiment A..F, description, region us-east-1,
     datasetSnapshot, costBoundUsd > 0 and not above the hard per-run cap of
     5 USD, maxDurationSeconds integer > 0, resourceLimits object,
     action.kind pipeline|command, action.args array of strings, action.path
     under load-tests/ or experiments/collectors/ with no ".." segment,
     metrics array);
  6. (Phase 15) a placeholder in action.args other than {attempt-id}
     (anywhere) and {data-bucket} (only as s3://{data-bucket}/...);
  7. no approval line.

Three different guarantees:
  integrity  the committed file has exactly the given sha256 (checked);
  approval   a whole line "APPROVED-MANIFEST-SHA256: HEX64" (that sha256,
             nothing else on the line) appears in a TRACKED file named
             human-gate.md or approvals.md under docs/agent-runs/ at HEAD
             (git grep; checked). Agents cannot write there. Other files
             (reports, summaries, prompts) never count;
  caller     NOT checked: anyone with a shell on the laptop and AWS
             credentials can run it (accepted limit of a one-person
             project). "Execute only approved experiment manifests" means
             the first two checks, no more.

Then it:
  1. reads state_machine_arn, data_bucket_name, vector_bucket_name,
     alb_arn_suffix and target_group_arn_suffixes from terraform output and
     the account (aws sts get-caller-identity), only to resolve metric
     dimensions and to mask and check records. Phase 15 (G-29): it also
     registers the API host (from api_url), the job and embedding queue URLs,
     both DLQ URLs (dlq_urls), the ALB DNS name and the Neptune endpoint;
     none is ever printed or recorded (scripts/lib/identity.sh);
  2. creates evidence/experiment-runs/ATTEMPT_ID/ (gitignored, so the nested
     run-bounded-pipeline.sh still sees a clean tree) and writes
     run-record.started.json there;
  3. prints the cost bound and resource limits labelled "declared, not
     enforced": maxDurationSeconds bounds only this local runner; remote work
     it started (a Step Functions execution, an EMR cluster) keeps running and
     is bounded by the state machine's own timeout, the EMR managed-scaling
     maximum and the idle end, all set in Terraform; resourceLimits are
     recorded, not applied; costBoundUsd is a declaration (nothing measures
     spend during a run), refused above 5 USD;
  4. resolves action.args (Phase 15): every "{attempt-id}" is replaced by
     this run's attempt ID; nothing else changes (s3://{data-bucket}/...
     stays logical: run-bounded-pipeline.sh, or a collector, resolves it).
     The resolved arguments are recorded (action.resolved_args);
  5. runs the action under timeout maxDurationSeconds, its output masked and
     kept in action.log: kind "pipeline" runs
     bash scripts/run-bounded-pipeline.sh RESOLVED_ARGS; kind "command" runs
     the committed executable at action.path with RESOLVED_ARGS. The action
     gets SCHOLARCHAIN_EXPERIMENT_RUN_DIR (the run folder, for raw output),
     SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID and SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID
     in its environment;
  6. collects the nested run records: every "RUN-RECORD: PATH" line the
     action printed (PATH under evidence/runs/) is copied into nested/, and
     its remote IDs are read (jq .run_ids.execution_name and
     .run_ids.emr_cluster_ids[]); "EXECUTION: name=NAME" lines are read too;
  7. for EVERY action that ended (succeeded, failed or timed out; Phase 15
     decision 9.6(d)), waits 300 s for CloudWatch to catch up, then exports
     the manifest's metrics with aws cloudwatch get-metric-data for the
     action window plus 300 s on each side into metrics/metric-data.json
     (queries in logical form in metrics/queries.json). Only a run stopped by
     a signal (INT, TERM) exports nothing;
  8. writes run-record.json, checks every file of the run folder for the
     real account ID, bucket names and the identifiers of step 1 (grep -F),
     then moves the folder to experiments/raw/MANIFEST_ID/ATTEMPT_ID/ and
     makes it read-only. The human commits it (committed raw evidence). A run
     folder that holds an identifier is NOT moved (exit 8; it stays under
     evidence/).

--check (Phase 15 decision 9.6(c)): runs every refusal above in order, then
the read-only part: Terraform outputs and the account (identifier list),
each metric placeholder shown only as resolved or not, the {attempt-id}
substitution on a sample attempt ID, the action's executable bit and tracked
state, the environment variables it would pass; then runs the action in
PREFLIGHT form through the same code path as a real run: the resolved
action.args plus --preflight, with the same environment except that
SCHOLARCHAIN_EXPERIMENT_RUN_DIR points to a temporary folder outside the
repository (removed at exit), output masked like action.log, at most 600 s.
A "pipeline" action is not run in preflight form (run-bounded-pipeline.sh has
no --preflight; said so). It prints the cost bound and limits and exits 0
without creating a run folder, starting real work or exporting metrics.
The refusals keep their exit codes; a failed preflight is exit 5 (its exit
code printed), a preflight past 600 s exit 7.

--export-metrics ATTEMPT_ID (Phase 15 decision 9.6(f)): re-exports the
metrics of an earlier, committed attempt of the SAME approved manifest
(experiments/raw/MANIFEST_ID/ATTEMPT_ID/run-record.json, tracked, with the
same manifest sha256 and an action window) into a NEW folder
experiments/raw/MANIFEST_ID/ATTEMPT_ID-metrics-UTCSTAMP/ (the original
window and EMR cluster IDs; the record names the original attempt in
metrics_export_of). It never touches the original folder and runs no
action. Same refusals; exit 0, 6 or 8 as below.

Logical locations and placeholders (Phase 13 finding C-03; Phase 15 9.6(a)):
  - Who resolves what (one rule): {attempt-id} in action.args: this runner;
    {data-bucket} in an s3:// argument: run-bounded-pipeline.sh, which
    resolves it from terraform output data_bucket_name and records only the
    logical form (a "command" action that itself needs a bucket resolves it
    the same way and never prints it); {emr-cluster-id},
    {state-machine-arn}, {alb-dimension} and {alb-target-group-ROLE} in
    metrics: this runner, at export.
  - Metric dimension placeholders: {state-machine-arn} from terraform output
    state_machine_arn; {emr-cluster-id} from the nested run records
    (.run_ids.emr_cluster_ids[]; one query per cluster); {alb-dimension}
    from terraform output alb_arn_suffix (app/NAME/ID, the ALB's
    LoadBalancer dimension, never an ARN); {alb-target-group-ROLE} (ROLE
    query, evidence or graph) from terraform output
    target_group_arn_suffixes (targetgroup/NAME/ID). A metric whose
    placeholder cannot be resolved is not queried and is listed; the run is
    then succeeded-metrics-failed. Records and metrics/queries.json keep the
    placeholder text, never the ARN.

Run record (run-record.started.json first, then run-record.json; never
overwritten): HEAD, build commit, manifest path, id and sha256, start and end
UTC, Region, declared limits, action kind, arguments, resolved arguments and
exit code, nested run records, remote IDs (execution names, EMR cluster IDs),
metric file locations and status, unresolved metrics, metrics_export_of
(null for a run), status: succeeded, failed, timed-out or
succeeded-metrics-failed. A failed run keeps its folder and record. A run
killed so hard that no trap ran leaves only the started record under
evidence/experiment-runs/: an incomplete attempt, never a success.

On a local timeout it prints what may still be running remotely (the Step
Functions execution names the action reported, and running EMR clusters
tagged project=scholarchain from aws emr list-clusters --active and
describe-cluster), exports the window's metrics, and exits 7. It never stops
remote work itself.

Requirements: bash, git, jq (1.6+), shasum or sha256sum, timeout or
gtimeout (GNU coreutils), checked first; terraform (output only) and aws
(CLI v2), checked only after every refusal above passed, so the refusals
work on a machine without them; AWS credentials allowed to read Terraform
outputs' resources, CloudWatch metrics and EMR clusters, plus whatever the
action needs.

Exit codes:
  0  action succeeded and metrics exported (or none declared); --check: all
     checks and the preflight passed
  2  usage error
  3  missing prerequisite or Terraform output (the runner's own)
  4  refused by a safety check (list above, or the run or raw folder exists)
  5  the action failed (metrics still exported; status failed), a command of
     the runner failed, or (--check) the action's preflight failed
  6  the action succeeded but the metric export failed or was partial
     (succeeded-metrics-failed)
  7  the local time limit (maxDurationSeconds) stopped the action (metrics
     exported; status timed-out; remote work may still run), or a TERM
     signal stopped the runner (no export)
  8  (Phase 15) an identifier (account ID, bucket name, API host, queue or
     DLQ URL, ALB or Neptune host) was found in the run folder or its
     record; the folder stays under evidence/experiment-runs/ (never commit
     it)

Status: static: shellcheck clean; runtime: untested; human execution required.
EOF
}

step() {
  CURRENT_STEP="$1"
  printf 'STEP: %s %s\n' "${SCRIPT_NAME}" "$1"
}
info() { printf 'INFO: %s %s\n' "${SCRIPT_NAME}" "$(mask_text "$*")"; }
warn() { printf 'WARN: %s %s\n' "${SCRIPT_NAME}" "$(mask_text "$*")" >&2; }
die() {
  local code="$1"
  shift
  printf 'ERROR: %s step=%s exit=%s: %s\n' "${SCRIPT_NAME}" "${CURRENT_STEP}" "${code}" "$(mask_text "$*")" >&2
  exit "${code}"
}
on_err() {
  local rc="$?"
  printf 'ERROR: %s step=%s line=%s exit=5: command failed with status %s\n' \
    "${SCRIPT_NAME}" "${CURRENT_STEP}" "$1" "${rc}" >&2
  exit 5
}
on_signal() {
  SIGNAL_NAME="$1"
  if [ "$1" = "TERM" ]; then
    exit 7
  fi
  exit 5
}
on_exit() {
  local rc="$?"
  trap - ERR
  set +e
  if [ "${STARTED_WRITTEN}" = "true" ] && [ "${FINALIZED}" = "false" ]; then
    finalize "${rc}"
    rc="$?"
  fi
  if [ -n "${TMP_DIR}" ] && [ -d "${TMP_DIR}" ]; then
    rm -rf -- "${TMP_DIR}"
  fi
  if [ "${rc}" -eq 0 ]; then
    printf 'RESULT: %s ok\n' "${SCRIPT_NAME}"
  else
    printf 'RESULT: %s failed exit=%s step=%s\n' "${SCRIPT_NAME}" "${rc}" "${CURRENT_STEP}" >&2
  fi
  exit "${rc}"
}
trap 'on_err "${LINENO}"' ERR
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap on_exit EXIT

# Until the helper is loaded, mask_text prints its argument unchanged.
mask_text() { printf '%s' "$*"; }

with_timeout() {
  local secs="$1"
  shift
  "${TIMEOUT_BIN}" --kill-after=30 "${secs}" "$@"
}

# ---- arguments and location ------------------------------------------------------

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --manifest)
        [ "$#" -ge 2 ] || die 2 "--manifest needs a path"
        MANIFEST="$2"
        shift 2
        ;;
      --manifest-sha256)
        [ "$#" -ge 2 ] || die 2 "--manifest-sha256 needs a value"
        MANIFEST_SHA_ARG="$2"
        shift 2
        ;;
      --check)
        CHECK_MODE="true"
        shift
        ;;
      --export-metrics)
        [ "$#" -ge 2 ] || die 2 "--export-metrics needs an attempt ID"
        EXPORT_OF="$2"
        shift 2
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
  [ -n "${MANIFEST}" ] || {
    usage >&2
    die 2 "--manifest PATH is required"
  }
  [[ "${MANIFEST_SHA_ARG}" =~ ^[0-9a-f]{64}$ ]] || die 2 "--manifest-sha256 needs 64 lowercase hex"
  if [ "${CHECK_MODE}" = "true" ] && [ -n "${EXPORT_OF}" ]; then
    die 2 "--check and --export-metrics cannot be combined"
  fi
  if [ -n "${EXPORT_OF}" ]; then
    # new_attempt_id form (scripts/lib/identity.sh): UTC-HEAD12-RANDOM4.
    [[ "${EXPORT_OF}" =~ ^[0-9]{8}T[0-9]{6}Z-([0-9a-f]{12}|nohead)-[0-9a-f]{4}$ ]] ||
      die 2 "--export-metrics needs an attempt ID like 20261120T200000Z-0123456789ab-0f1e"
  fi
  while [ "${MANIFEST#./}" != "${MANIFEST}" ]; do
    MANIFEST="${MANIFEST#./}"
  done
  case "${MANIFEST}" in
    /* | *..*) die 2 "--manifest must be a path relative to the repository root without '..'" ;;
  esac
}

enter_repo_root() {
  local top here
  top="$(git rev-parse --show-toplevel 2>/dev/null)" ||
    die 4 "refusing: not inside a git checkout; run from the repository root (bash scripts/${SCRIPT_NAME})"
  here="$(pwd -P)"
  [ "${top}" = "${here}" ] || die 4 "refusing: run from the repository root (bash scripts/${SCRIPT_NAME})"
  [ -f scripts/lib/identity.sh ] || die 3 "missing scripts/lib/identity.sh"
  # shellcheck source=/dev/null
  . scripts/lib/identity.sh
}

# Local tools only: the refusals (dirty tree, untracked, checksum, example, v1,
# placeholders, approval) need no cloud tool, so a machine without the AWS CLI
# still gets exit 4 from them (I-20; check.sh's run-experiment-example probe).
check_local_prerequisites() {
  step "prerequisites-local"
  local tool
  for tool in git jq cut grep; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  else
    die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 ||
    die 3 "neither shasum nor sha256sum found"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-experiment.XXXXXX")"
}

# Cloud tools, checked only after every refusal passed (as deploy.sh does).
check_cloud_prerequisites() {
  step "prerequisites-cloud"
  local tool
  for tool in terraform aws; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
}

# ---- refusals, in the documented order (no cloud call) -----------------------------

check_clean_tree() {
  step "refuse-dirty-tree"
  require_clean_tree || die 4 "refusing: the working tree is dirty"
  HEAD_SHA="$(head_commit)" || die 3 "not a git repository with a commit"
  BUILD_SHA="$(build_commit)" || die 3 "cannot determine the build commit"
}

check_tracked() {
  step "refuse-untracked-manifest"
  [ -f "${MANIFEST}" ] && [ ! -L "${MANIFEST}" ] || die 4 "refusing: ${MANIFEST} is not a regular file"
  git ls-files --error-unmatch -- "${MANIFEST}" >/dev/null 2>&1 ||
    die 4 "refusing: ${MANIFEST} is not tracked by git (commit it first)"
}

check_checksum() {
  step "refuse-checksum"
  local actual
  actual="$(sc_sha256 "${MANIFEST}")"
  info "manifest sha256: ${actual}"
  [ "${actual}" = "${MANIFEST_SHA_ARG}" ] ||
    die 4 "refusing: the manifest's sha256 differs from --manifest-sha256"
}

check_example() {
  step "refuse-example"
  if jq -e '.example == true' "${MANIFEST}" >/dev/null 2>&1; then
    die 4 'refusing: the manifest is an example ("example": true); an example never runs'
  fi
}

check_v1() {
  step "refuse-v1-checks"
  local problems base
  problems="$(jq -r --argjson cap "${COST_CAP_USD}" '
    def isstr: type == "string";
    def nonempty: isstr and length > 0;
    [ (if .format == "scholarchain.experiment-manifest" then empty
       else "format must be \"scholarchain.experiment-manifest\"" end),
      (if (.formatVersion | type) == "number" and .formatVersion == 1 then empty
       else "formatVersion must be the number 1" end),
      (if (.manifestId | isstr) then
         (if (.manifestId | test("^[a-z0-9][a-z0-9-]{0,63}$")) then empty
          else "manifestId must match ^[a-z0-9][a-z0-9-]{0,63}$" end)
       else "manifestId must be a string" end),
      (if (.example | type) == "boolean" then empty else "example must be a boolean" end),
      (if (.experiment | isstr) and ([.experiment] | inside(["A","B","C","D","E","F"])) then empty
       else "experiment must be one of A..F" end),
      (if (.description | nonempty) then empty else "description must be a non-empty string" end),
      (if .region == "us-east-1" then empty else "region must be us-east-1" end),
      (if (.datasetSnapshot | nonempty) then empty else "datasetSnapshot must be a non-empty string" end),
      (if (.costBoundUsd | type) == "number" and .costBoundUsd > 0 and .costBoundUsd <= $cap then empty
       else "costBoundUsd must be a number > 0 and not above \($cap) USD" end),
      (if (.maxDurationSeconds | type) == "number" and .maxDurationSeconds > 0
          and (.maxDurationSeconds | floor) == .maxDurationSeconds then empty
       else "maxDurationSeconds must be an integer > 0" end),
      (if (.resourceLimits | type) == "object" then empty else "resourceLimits must be an object" end),
      (if (.action | type) == "object" and (.action.kind == "pipeline" or .action.kind == "command") then empty
       else "action.kind must be pipeline or command" end),
      (if (.action.args | type) == "array" and all(.action.args[]; type == "string") then empty
       else "action.args must be an array of strings" end),
      (if .action.kind != "command" then empty
       elif (.action.path | isstr)
            and ((.action.path | startswith("load-tests/")) or (.action.path | startswith("experiments/collectors/")))
            and ((.action.path | split("/") | index("..")) == null)
       then empty
       else "action.path must start with load-tests/ or experiments/collectors/ and have no .. segment" end),
      (if (.metrics | type) == "array" then empty else "metrics must be an array" end)
    ] | .[]' "${MANIFEST}" 2>/dev/null)" || die 4 "refusing: the manifest is not valid JSON (v1 checks)"
  MANIFEST_ID="$(jq -r '.manifestId // empty' "${MANIFEST}")"
  base="${MANIFEST##*/}"
  if [ -n "${MANIFEST_ID}" ] && [ "${base}" != "${MANIFEST_ID}.json" ]; then
    problems="${problems:+${problems}
}file name ${base} must be manifestId plus .json (${MANIFEST_ID}.json)"
  fi
  if [ -n "${problems}" ]; then
    local p
    while IFS= read -r p; do
      [ -n "${p}" ] && printf 'V1-CHECK: %s\n' "${p}" >&2
    done <<<"${problems}"
    die 4 "refusing: the manifest fails the v1 checks (listed above)"
  fi
  ACTION_KIND="$(jq -r '.action.kind' "${MANIFEST}")"
  info "v1 checks passed: ${MANIFEST_ID}, experiment $(jq -r '.experiment' "${MANIFEST}"), action ${ACTION_KIND}"
}

# Phase 15 decision 9.6(a): after {attempt-id} (anywhere) and s3://{data-bucket}
# (the logical bucket of an s3:// argument) are set aside, no "{...}" may be
# left in any action argument.
check_placeholders() {
  step "refuse-placeholders"
  local bad
  bad="$(jq -r '.action.args[]
      | select(gsub("\\{attempt-id\\}"; "") | gsub("s3://\\{data-bucket\\}"; "s3://") | test("\\{[^{}]*\\}"))' \
    "${MANIFEST}" 2>/dev/null)" || die 4 "refusing: action.args could not be read (placeholder check)"
  if [ -n "${bad}" ]; then
    local a
    while IFS= read -r a; do
      [ -n "${a}" ] && printf 'PLACEHOLDER-CHECK: unknown placeholder in action argument: %s\n' "${a}" >&2
    done <<<"${bad}"
    die 4 "refusing: action.args hold a placeholder other than {attempt-id} and s3://{data-bucket}/ (listed above)"
  fi
  info "action.args placeholders: only {attempt-id} and s3://{data-bucket}/"
}

check_approval() {
  step "refuse-no-approval"
  local hits
  hits="$(git grep -h -E "^APPROVED-MANIFEST-SHA256: ${MANIFEST_SHA_ARG}\$" HEAD -- \
    ':(glob)docs/agent-runs/**/human-gate.md' ':(glob)docs/agent-runs/**/approvals.md' 2>/dev/null || true)"
  [ -n "${hits}" ] ||
    die 4 "refusing: no line 'APPROVED-MANIFEST-SHA256: ${MANIFEST_SHA_ARG}' in a tracked human-gate.md or approvals.md under docs/agent-runs/ at HEAD"
  info "approval line found at HEAD (integrity and approval checked; the caller is not authenticated)"
}

# ---- cloud reads ---------------------------------------------------------------------

aws_errfile() { printf '%s/aws.err' "${TMP_DIR}"; }

# aws_read ARGS...: a read with timeouts, retried up to MAX_TRIES times.
aws_read() {
  local try=1 rc out
  while :; do
    rc=0
    out="$(with_timeout "${AWS_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 60 --no-cli-pager --output json "$@" 2>"$(aws_errfile)")" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      [ -n "${out}" ] || out='{}'
      printf '%s\n' "${out}"
      return 0
    fi
    if [ "${try}" -ge "${MAX_TRIES}" ]; then
      printf 'AWS-ERROR: %s\n' "$(mask_text "$(cat "$(aws_errfile)" 2>/dev/null)")" >&2
      [ "${rc}" -eq 124 ] && return 7
      return 5
    fi
    try=$((try + 1))
    sleep $((try * 5))
  done
}

read_outputs() {
  step "terraform-outputs"
  local rc=0 outputs data vector ident
  outputs="$(with_timeout "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" output -json -no-color 2>/dev/null)" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform output failed (status ${rc})"
  STATE_MACHINE_ARN="$(jq -r '.state_machine_arn.value // empty' <<<"${outputs}")"
  data="$(jq -r '.data_bucket_name.value // empty' <<<"${outputs}")"
  vector="$(jq -r '.vector_bucket_name.value // empty' <<<"${outputs}")"
  [ -n "${STATE_MACHINE_ARN}" ] && [ -n "${data}" ] ||
    die 3 "terraform output lacks state_machine_arn or data_bucket_name (nothing applied yet?)"
  # Phase 15 decision 9.6(b): ALB dimension values (ARN suffixes, no account).
  ALB_SUFFIX="$(jq -r '.alb_arn_suffix.value // empty' <<<"${outputs}")"
  TG_SUFFIXES_JSON="$(jq -c '.target_group_arn_suffixes.value // {} | if type == "object" then . else {} end' <<<"${outputs}")"
  ident="$(aws_read sts get-caller-identity)" || die 5 "aws sts get-caller-identity failed"
  register_identifiers "$(jq -r '.Account // empty' <<<"${ident}")" "${data}" "${vector}" ||
    die 5 "could not read the account"
  ident=""
  # Phase 15 decision 9.5 (G-29): API host, queue and DLQ URLs, ALB and
  # Neptune hosts: masked in action.log and every line, refused in the run
  # folder (exit 8). Read here, held in shell variables, never printed.
  register_output_identifiers "${outputs}" || die 5 "could not register the Terraform output identifiers"
  outputs=""
  info "Region ${EXPECTED_REGION}; state machine, buckets, API host, queue URLs and endpoint hosts read from terraform output (masked, not printed)"
}

# ---- {attempt-id} (Phase 15 decision 9.6(a)) ------------------------------------------

# resolve_args ID: sets RESOLVED_ARGS_JSON to action.args with every
# {attempt-id} replaced by ID; every other character unchanged.
resolve_args() {
  local id="$1"
  [[ "${id}" =~ ^[A-Za-z0-9-]+$ ]] || die 5 "internal: unexpected attempt ID form"
  RESOLVED_ARGS_JSON="$(jq -c --arg a "${id}" '[.action.args[] | gsub("\\{attempt-id\\}"; $a)]' "${MANIFEST}")" ||
    die 5 "could not resolve {attempt-id} in action.args"
}

# ---- run folder and records ----------------------------------------------------------

# start_run [ATTEMPT_ID]: a new attempt ID unless one is given (--export-metrics).
start_run() {
  step "start-run"
  ATTEMPT_ID="${1:-$(new_attempt_id)}"
  RUN_DIR="${RUN_ROOT}/${ATTEMPT_ID}"
  RAW_DEST="${RAW_ROOT}/${MANIFEST_ID}/${ATTEMPT_ID}"
  [ ! -e "${RUN_DIR}" ] || die 4 "refusing: ${RUN_DIR} exists"
  [ ! -e "${RAW_DEST}" ] || die 4 "refusing: ${RAW_DEST} exists"
  git check-ignore -q -- "${RUN_ROOT}/x" || die 4 "refusing: ${RUN_ROOT}/ is not gitignored"
  mkdir -p -- "${RUN_DIR}/metrics" "${RUN_DIR}/nested"
  cp -- "${MANIFEST}" "${RUN_DIR}/manifest.json"
  STARTED_UTC="$(sc_utc_now)"
  base_record "started" | write_record_atomic "${RUN_DIR}/run-record.started.json" ||
    die 5 "could not write the started record"
  STARTED_WRITTEN="true"
  printf 'ATTEMPT: %s\n' "${ATTEMPT_ID}"
  info "run folder ${RUN_DIR} (moved to ${RAW_DEST} at the end)"
}

# base_record STATUS [ENDED_UTC] [EXIT_CODE]: prints the run record (logical
# locations only).
base_record() {
  jq -n --arg kind "scholarchain-experiment-run-record" --arg status "$1" \
    --arg attempt "${ATTEMPT_ID}" --arg head "${HEAD_SHA}" --arg build "${BUILD_SHA}" \
    --arg manifest "${MANIFEST}" --arg mid "${MANIFEST_ID}" --arg msha "${MANIFEST_SHA_ARG}" \
    --arg region "${EXPECTED_REGION}" --arg started "${STARTED_UTC}" \
    --arg ended "${2:-}" --arg step "${CURRENT_STEP}" --arg signal "${SIGNAL_NAME}" \
    --argjson exit_code "${3:-null}" --argjson action_rc "${ACTION_RC}" \
    --arg raw "${RAW_DEST}" --arg metrics_status "${METRICS_STATUS}" \
    --arg astart "${ACTION_START_EPOCH}" --arg aend "${ACTION_END_EPOCH}" \
    --argjson executions "${EXECUTION_NAMES_JSON}" --argjson clusters "${CLUSTER_IDS_JSON}" \
    --argjson nested "${NESTED_JSON}" --argjson metric_files "${METRIC_FILES_JSON}" \
    --argjson unresolved "${UNRESOLVED_JSON}" --argjson resolved "${RESOLVED_ARGS_JSON}" \
    --arg export_of "${EXPORT_OF}" --slurpfile m "${MANIFEST}" \
    '{kind: $kind, version: 1, status: $status, attempt_id: $attempt,
      exit_code: $exit_code,
      failed_step: (if ($status | IN("started", "succeeded")) then null else $step end),
      signal: (if $signal == "" then null else $signal end),
      head: $head, build_commit: $build, region: $region,
      manifest: {path: $manifest, manifest_id: $mid, sha256: $msha, experiment: $m[0].experiment,
                 dataset_snapshot: $m[0].datasetSnapshot},
      declared_not_enforced: {costBoundUsd: $m[0].costBoundUsd, maxDurationSeconds: $m[0].maxDurationSeconds,
                              resourceLimits: $m[0].resourceLimits},
      started_utc: $started, ended_utc: (if $ended == "" then null else $ended end),
      metrics_export_of: (if $export_of == "" then null else $export_of end),
      action: {kind: $m[0].action.kind, path: ($m[0].action.path // null), args: $m[0].action.args,
               resolved_args: $resolved,
               run: ($export_of == ""),
               exit_code: $action_rc,
               window_epoch: {start: (if $astart == "" then null else ($astart | tonumber) end),
                              end: (if $aend == "" then null else ($aend | tonumber) end)}},
      nested_run_records: $nested,
      remote_ids: {execution_names: $executions, emr_cluster_ids: $clusters},
      metrics: {status: $metrics_status, files: $metric_files, unresolved: $unresolved},
      raw_results_location: $raw}'
}

print_declared() {
  step "declared-limits"
  printf 'DECLARED (not enforced): costBoundUsd=%s (hard cap %s USD; nothing measures spend)\n' \
    "$(jq -r '.costBoundUsd' "${MANIFEST}")" "${COST_CAP_USD}"
  printf 'DECLARED (not enforced): maxDurationSeconds=%s bounds only this local runner\n' \
    "$(jq -r '.maxDurationSeconds' "${MANIFEST}")"
  printf 'DECLARED (not enforced): resourceLimits=%s (the real caps are the Terraform values)\n' \
    "$(mask_text "$(jq -c '.resourceLimits' "${MANIFEST}")")"
}

# ---- action --------------------------------------------------------------------------

# action_command: prints nothing; sets ACTION_CMD to the command array of the
# action (pipeline or the committed executable), refusing (4) an untracked or
# non-executable command path.
ACTION_CMD=()
action_command() {
  local path
  if [ "${ACTION_KIND}" = "pipeline" ]; then
    [ -f scripts/run-bounded-pipeline.sh ] || die 3 "missing scripts/run-bounded-pipeline.sh"
    ACTION_CMD=(bash scripts/run-bounded-pipeline.sh)
  else
    path="$(jq -r '.action.path' "${MANIFEST}")"
    git ls-files --error-unmatch -- "${path}" >/dev/null 2>&1 || die 4 "refusing: ${path} is not tracked by git"
    [ -f "${path}" ] && [ -x "${path}" ] || die 4 "refusing: ${path} is not an executable file"
    ACTION_CMD=("./${path}")
  fi
}

# invoke_action RUN_DIR_ABS ATTEMPT MAX_SECONDS LOG_FILE [EXTRA_ARG...]: the ONE
# code path of a real run and of the --check preflight. Runs ACTION_CMD with
# RESOLVED_ARGS_JSON (then EXTRA_ARG...) under timeout, with the three
# SCHOLARCHAIN_EXPERIMENT_* variables, standard input from /dev/null, output
# masked, shown and kept in LOG_FILE. Sets INVOKE_RC (the action's status).
invoke_action() {
  local run_dir="$1" attempt="$2" max="$3" log="$4" a rc=0
  shift 4
  local args=()
  while IFS= read -r -d '' a; do
    args+=("${a}")
  done < <(jq -j '.[] | (., "\u0000")' <<<"${RESOLVED_ARGS_JSON}")
  if [ "$#" -gt 0 ]; then
    args+=("$@")
  fi
  SCHOLARCHAIN_EXPERIMENT_RUN_DIR="${run_dir}" \
    SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID="${attempt}" \
    SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID="${MANIFEST_ID}" \
    "${TIMEOUT_BIN}" --kill-after=30 "${max}" "${ACTION_CMD[@]}" ${args[@]+"${args[@]}"} 2>&1 </dev/null |
    mask_stream | tee "${log}" || rc="${PIPESTATUS[0]}"
  INVOKE_RC="${rc}"
}

# run_action: runs the action once and records how it ended. It does NOT stop
# the runner on a failure or a timeout: the metrics are exported for every
# action that ended (Phase 15 decision 9.6(d)); finish_action decides the exit.
run_action() {
  step "action"
  local max
  max="$(jq -r '.maxDurationSeconds' "${MANIFEST}")"
  resolve_args "${ATTEMPT_ID}"
  info "resolved action.args ({attempt-id} -> ${ATTEMPT_ID}): $(jq -c '.' <<<"${RESOLVED_ARGS_JSON}")"
  info "running the ${ACTION_KIND} action under timeout ${max}s; output masked into ${RUN_DIR}/action.log"
  ACTION_START_EPOCH="$(date +%s)"
  invoke_action "$(pwd -P)/${RUN_DIR}" "${ATTEMPT_ID}" "${max}" "${RUN_DIR}/action.log"
  ACTION_END_EPOCH="$(date +%s)"
  ACTION_RC="${INVOKE_RC}"
  info "action ended with status ${ACTION_RC} after $((ACTION_END_EPOCH - ACTION_START_EPOCH))s"
  collect_nested
  case "${ACTION_RC}" in
    124 | 137) report_remote_leftovers ;;
  esac
}

# finish_action: the exit after the metrics (the EXIT trap writes the record).
finish_action() {
  local max
  step "action-result"
  max="$(jq -r '.maxDurationSeconds' "${MANIFEST}")"
  case "${ACTION_RC}" in
    0) return 0 ;;
    124 | 137)
      die 7 "the action exceeded maxDurationSeconds=${max}s; remote work may still run (listed above; never stopped by this script); metrics: ${METRICS_STATUS}"
      ;;
    *) die 5 "the action failed (status ${ACTION_RC}); see ${RUN_DIR}/action.log; metrics: ${METRICS_STATUS}" ;;
  esac
}

collect_nested() {
  local line path base names ids n=0
  while IFS= read -r line; do
    path="${line#RUN-RECORD: }"
    path="${path%%[[:space:]]*}"
    case "${path}" in
      evidence/runs/*.json) ;;
      *)
        warn "ignoring a RUN-RECORD line outside evidence/runs/: ${path}"
        continue
        ;;
    esac
    [ -f "${path}" ] || {
      warn "nested run record ${path} not found"
      continue
    }
    base="${path##*/}"
    cp -- "${path}" "${RUN_DIR}/nested/${base}"
    n=$((n + 1))
    NESTED_JSON="$(jq -c --arg p "${path}" --arg c "nested/${base}" --arg s "$(sc_sha256 "${path}")" \
      '. + [{path: $p, copy: $c, sha256: $s}]' <<<"${NESTED_JSON}")"
    names="$(jq -c '[.run_ids.execution_name? // empty | strings]' "${path}" 2>/dev/null || printf '[]')"
    ids="$(jq -c '[.run_ids.emr_cluster_ids[]? | strings]' "${path}" 2>/dev/null || printf '[]')"
    EXECUTION_NAMES_JSON="$(jq -c --argjson a "${names}" '. + $a | unique' <<<"${EXECUTION_NAMES_JSON}")"
    CLUSTER_IDS_JSON="$(jq -c --argjson a "${ids}" '. + $a | unique' <<<"${CLUSTER_IDS_JSON}")"
  done < <(grep -E '^RUN-RECORD: ' "${RUN_DIR}/action.log" 2>/dev/null || true)
  while IFS= read -r line; do
    names="${line#EXECUTION: name=}"
    names="${names%%[[:space:]]*}"
    [ -n "${names}" ] || continue
    EXECUTION_NAMES_JSON="$(jq -c --arg n "${names}" '. + [$n] | unique' <<<"${EXECUTION_NAMES_JSON}")"
  done < <(grep -E '^EXECUTION: name=' "${RUN_DIR}/action.log" 2>/dev/null || true)
  info "nested run records: ${n}; execution names: $(jq -r 'join(", ")' <<<"${EXECUTION_NAMES_JSON}"); EMR clusters: $(jq -r 'join(", ")' <<<"${CLUSTER_IDS_JSON}")"
}

report_remote_leftovers() {
  step "remote-leftovers"
  printf 'MAY-STILL-RUN: Step Functions executions reported by the action: %s\n' \
    "$(jq -r 'if length == 0 then "none reported" else join(", ") end' <<<"${EXECUTION_NAMES_JSON}")"
  local clusters id desc count=0
  clusters="$(aws_read emr list-clusters --active)" || {
    warn "could not list EMR clusters; check for clusters tagged project=scholarchain by hand"
    return 0
  }
  for id in $(jq -r '.Clusters[]?.Id' <<<"${clusters}"); do
    count=$((count + 1))
    [ "${count}" -le "${MAX_LISTED_CLUSTERS}" ] || break
    desc="$(aws_read emr describe-cluster --cluster-id "${id}")" || continue
    if jq -e '[.Cluster.Tags[]? | select(.Key == "project" and .Value == "scholarchain")] | length > 0' \
      <<<"${desc}" >/dev/null 2>&1; then
      printf 'MAY-STILL-RUN: EMR cluster %s (%s, %s)\n' "${id}" "$(jq -r '.Cluster.Name' <<<"${desc}")" \
        "$(jq -r '.Cluster.Status.State' <<<"${desc}")"
    fi
  done
  printf 'MAY-STILL-RUN: the state machine timeout, the EMR managed-scaling maximum and the idle end bound them; this script stops nothing\n'
}

# ---- metrics -------------------------------------------------------------------------

# The jq program that turns the manifest's metrics into get-metric-data
# queries. $sm, $alb and $tg resolve {state-machine-arn}, {alb-dimension} and
# {alb-target-group-ROLE}; $clusters expands {emr-cluster-id} to one query per
# cluster. With $sm = "{state-machine-arn}", $alb = "" and $tg = {} it gives
# the logical form (placeholders kept).
# shellcheck disable=SC2016
readonly METRIC_QUERY_JQ='
  def resolve($v; $c):
    if $v == "{state-machine-arn}" then $sm
    elif $v == "{emr-cluster-id}" then ($c // $v)
    elif $v == "{alb-dimension}" then (if $alb == "" then $v else $alb end)
    elif ($v | test("^\\{alb-target-group-[a-z]+\\}$"))
      then ($tg[($v | ltrimstr("{alb-target-group-") | rtrimstr("}"))] // $v)
    else $v end;
  [ .metrics | to_entries[] | .key as $i | .value as $m
    | (if any($m.dimensions[]?; .value == "{emr-cluster-id}")
         then (if ($clusters | length) == 0 then [{c: null, j: 0}]
               else ($clusters | to_entries | map({c: .value, j: .key})) end)
         else [{c: null, j: 0}] end)[] as $cv
    | {Id: "m\($i)_\($cv.j)",
       Label: ("\($m.namespace) \($m.metricName) \($m.stat) "
               + ([$m.dimensions[]? | "\(.name)=\(if .value == "{emr-cluster-id}" then ($cv.c // .value) else .value end)"]
                  | join(","))),
       MetricStat: {Metric: {Namespace: $m.namespace, MetricName: $m.metricName,
                             Dimensions: [$m.dimensions[]? | {Name: .name, Value: resolve(.value; $cv.c)}]},
                    Period: $m.periodSeconds, Stat: $m.stat},
       ReturnData: true} ]'

# wait_for_metrics: CloudWatch publishes late (decision 10), so the runner
# waits METRICS_SETTLE_SECONDS after the action before the export.
wait_for_metrics() {
  local n
  n="$(jq '.metrics | length' "${MANIFEST}")"
  [ "${n}" -gt 0 ] || return 0
  step "metrics-settle"
  printf 'METRICS-WAIT: %ss for CloudWatch to publish the end of the window (Ctrl-C stops the runner; no export then)\n' \
    "${METRICS_SETTLE_SECONDS}"
  sleep "${METRICS_SETTLE_SECONDS}"
}

export_metrics() {
  step "metrics"
  local n start end queries="${TMP_DIR}/queries.json" logical="${RUN_DIR}/metrics/queries.json"
  local out="${TMP_DIR}/metric-data.json" rc=0 try=1
  n="$(jq '.metrics | length' "${MANIFEST}")"
  if [ "${n}" -eq 0 ]; then
    METRICS_STATUS="none-declared"
    info "the manifest declares no metrics"
    return 0
  fi
  start="$(sc_epoch_to_utc $((ACTION_START_EPOCH - METRIC_MARGIN_SECONDS)))"
  end="$(sc_epoch_to_utc $((ACTION_END_EPOCH + METRIC_MARGIN_SECONDS)))"
  jq -c --arg sm "${STATE_MACHINE_ARN}" --arg alb "${ALB_SUFFIX}" --argjson tg "${TG_SUFFIXES_JSON}" \
    --argjson clusters "${CLUSTER_IDS_JSON}" "${METRIC_QUERY_JQ}" "${MANIFEST}" >"${TMP_DIR}/all-queries.json" ||
    {
      METRICS_STATUS="failed"
      warn "could not build the metric queries from the manifest"
      return 0
    }
  jq -c --arg sm "{state-machine-arn}" --arg alb "" --argjson tg '{}' \
    --argjson clusters "${CLUSTER_IDS_JSON}" "${METRIC_QUERY_JQ}" "${MANIFEST}" >"${TMP_DIR}/logical-queries.json" ||
    true
  # Queries whose dimension values still hold a {placeholder} cannot be run.
  UNRESOLVED_JSON="$(jq -c '[.[] | select(any(.MetricStat.Metric.Dimensions[]?; .Value | test("^\\{.*\\}$"))) | .Label]' \
    "${TMP_DIR}/all-queries.json")"
  jq -c '[.[] | select(all(.MetricStat.Metric.Dimensions[]?; .Value | test("^\\{.*\\}$") | not))]' \
    "${TMP_DIR}/all-queries.json" >"${queries}"
  jq '.' "${TMP_DIR}/logical-queries.json" >"${logical}" 2>/dev/null || cp -- "${TMP_DIR}/logical-queries.json" "${logical}"
  METRIC_FILES_JSON="$(jq -c --arg f "${RAW_DEST}/metrics/queries.json" '. + [$f]' <<<"${METRIC_FILES_JSON}")"
  if [ "$(jq 'length' "${queries}")" -eq 0 ]; then
    METRICS_STATUS="failed"
    warn "no metric query could be resolved: $(jq -r 'join("; ")' <<<"${UNRESOLVED_JSON}")"
    return 0
  fi
  while :; do
    rc=0
    with_timeout "${METRICS_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 120 --no-cli-pager --output json cloudwatch get-metric-data \
      --metric-data-queries "file://${queries}" --start-time "${start}" --end-time "${end}" \
      --scan-by TimestampAscending >"${out}" 2>"$(aws_errfile)" || rc=$?
    [ "${rc}" -eq 0 ] && break
    if [ "${try}" -ge "${MAX_TRIES}" ]; then
      printf 'AWS-ERROR: %s\n' "$(mask_text "$(cat "$(aws_errfile)" 2>/dev/null)")" >&2
      METRICS_STATUS="failed"
      warn "aws cloudwatch get-metric-data failed (status ${rc})"
      return 0
    fi
    try=$((try + 1))
    sleep $((try * 5))
  done
  if ! check_no_identifiers "${out}"; then
    METRICS_STATUS="failed"
    warn "the metric export holds an identifier; it is not kept"
    return 0
  fi
  cp -- "${out}" "${RUN_DIR}/metrics/metric-data.json"
  METRIC_FILES_JSON="$(jq -c --arg f "${RAW_DEST}/metrics/metric-data.json" '. + [$f]' <<<"${METRIC_FILES_JSON}")"
  if [ "$(jq 'length' <<<"${UNRESOLVED_JSON}")" -gt 0 ]; then
    METRICS_STATUS="partial"
    warn "metrics not queried (unresolved placeholder): $(jq -r 'join("; ")' <<<"${UNRESOLVED_JSON}")"
  else
    METRICS_STATUS="exported"
  fi
  info "metrics exported for ${start} .. ${end}: $(jq '[.MetricDataResults[]?] | length' "${out}") series"
}

# ---- --check (Phase 15 decision 9.6(c)) --------------------------------------------------

# metric_placeholder_report: one line per distinct placeholder of the
# manifest's metrics: resolved now, resolved at export, or not resolvable.
metric_placeholder_report() {
  local p state role
  while IFS= read -r p; do
    [ -n "${p}" ] || continue
    case "${p}" in
      "{state-machine-arn}") [ -n "${STATE_MACHINE_ARN}" ] && state="resolved" || state="NOT resolved" ;;
      "{emr-cluster-id}") state="resolved at export from the nested run records (one query per cluster)" ;;
      "{alb-dimension}") [ -n "${ALB_SUFFIX}" ] && state="resolved" || state="NOT resolved (no terraform output alb_arn_suffix)" ;;
      "{alb-target-group-"*"}")
        role="${p#\{alb-target-group-}"
        role="${role%\}}"
        if jq -e --arg r "${role}" 'has($r)' <<<"${TG_SUFFIXES_JSON}" >/dev/null 2>&1; then
          state="resolved"
        else
          state="NOT resolved (no target group for role ${role})"
        fi
        ;;
      *) state="NOT resolved (unknown placeholder; the query would be listed as unresolved)" ;;
    esac
    printf 'CHECK: metric placeholder %s: %s\n' "${p}" "${state}"
  done < <(jq -r '[.metrics[]?.dimensions[]?.value | strings | select(test("^\\{.*\\}$"))] | unique | .[]' "${MANIFEST}")
}

run_check() {
  step "check"
  local sample max path pf_dir pf_log here
  print_declared
  metric_placeholder_report
  printf 'CHECK: metrics declared: %s\n' "$(jq '.metrics | length' "${MANIFEST}")"
  sample="$(new_attempt_id)"
  resolve_args "${sample}"
  printf 'CHECK: {attempt-id} substitution on the sample attempt ID %s (no run folder): %s\n' \
    "${sample}" "$(mask_text "$(jq -c '.' <<<"${RESOLVED_ARGS_JSON}")")"
  action_command
  if [ "${ACTION_KIND}" = "command" ]; then
    path="$(jq -r '.action.path' "${MANIFEST}")"
    printf 'CHECK: action %s: tracked by git, executable bit set\n' "${path}"
  else
    printf 'CHECK: action bash scripts/run-bounded-pipeline.sh (kind pipeline)\n'
  fi
  here="$(pwd -P)"
  pf_dir="${TMP_DIR}/preflight-run"
  case "${pf_dir}" in
    "${here}"/*) die 4 "refusing: the temporary folder ${pf_dir} lies inside the repository (set TMPDIR elsewhere)" ;;
  esac
  mkdir -p -- "${pf_dir}"
  pf_log="${TMP_DIR}/preflight.log"
  printf 'CHECK: environment passed: SCHOLARCHAIN_EXPERIMENT_RUN_DIR=<temporary folder outside the repository, removed at exit> SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID=%s SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID=%s\n' \
    "${sample}" "${MANIFEST_ID}"
  if [ "${ACTION_KIND}" = "pipeline" ]; then
    printf 'PREFLIGHT: skipped: kind pipeline (scripts/run-bounded-pipeline.sh has no --preflight); the first real run is the first test\n'
    return 0
  fi
  max="${PREFLIGHT_TIMEOUT}"
  step "preflight"
  printf 'PREFLIGHT: running the action with --preflight (at most %ss; read calls only, by the tool contract)\n' "${max}"
  invoke_action "${pf_dir}" "${sample}" "${max}" "${pf_log}" --preflight
  case "${INVOKE_RC}" in
    0) printf 'PREFLIGHT: ok exit=0\n' ;;
    124 | 137) die 7 "the action's preflight exceeded ${max}s" ;;
    *) die 5 "the action's preflight failed (exit ${INVOKE_RC}); output above" ;;
  esac
  printf 'CHECK: done: no run folder created, no real work started, no metrics exported\n'
}

# ---- --export-metrics (Phase 15 decision 9.6(f)) ------------------------------------------

# prepare_export: reads the original attempt's committed record; sets the
# window, the EMR cluster IDs and the execution names from it.
prepare_export() {
  step "export-source"
  local orig="${RAW_ROOT}/${MANIFEST_ID}/${EXPORT_OF}/run-record.json" sha s e
  [ -f "${orig}" ] || die 4 "refusing: ${orig} does not exist (commit the original attempt first)"
  git ls-files --error-unmatch -- "${orig}" >/dev/null 2>&1 ||
    die 4 "refusing: ${orig} is not tracked by git (commit the original attempt first)"
  sha="$(jq -r '.manifest.sha256 // empty' "${orig}")"
  [ "${sha}" = "${MANIFEST_SHA_ARG}" ] ||
    die 4 "refusing: the original attempt ran another manifest sha256; give that manifest and its sha256"
  s="$(jq -r '.action.window_epoch.start // empty' "${orig}")"
  e="$(jq -r '.action.window_epoch.end // empty' "${orig}")"
  [[ "${s}" =~ ^[0-9]+$ ]] && [[ "${e}" =~ ^[0-9]+$ ]] && [ "${e}" -ge "${s}" ] ||
    die 4 "refusing: the original record has no complete action window"
  ACTION_START_EPOCH="${s}"
  ACTION_END_EPOCH="${e}"
  CLUSTER_IDS_JSON="$(jq -c '[.remote_ids.emr_cluster_ids[]? | strings] | unique' "${orig}")"
  EXECUTION_NAMES_JSON="$(jq -c '[.remote_ids.execution_names[]? | strings] | unique' "${orig}")"
  RESOLVED_ARGS_JSON="$(jq -c '.action.resolved_args // null' "${orig}")"
  info "re-export of ${EXPORT_OF}: window ${s}..${e}; EMR clusters: $(jq -r 'join(", ")' <<<"${CLUSTER_IDS_JSON}")"
}

# ---- finalize (from the EXIT trap) ---------------------------------------------------

# finalize RC: writes run-record.json, checks the run folder for identifiers,
# moves it to experiments/raw/ and makes it read-only. Returns the final exit
# code through its status.
finalize() {
  local rc="$1" status final_rc wrc=0
  FINALIZED="true"
  case "${rc}" in
    0)
      if [ "${METRICS_STATUS}" = "exported" ] || [ "${METRICS_STATUS}" = "none-declared" ]; then
        status="succeeded"
        final_rc=0
      else
        status="succeeded-metrics-failed"
        final_rc=6
      fi
      ;;
    7)
      status="timed-out"
      final_rc=7
      ;;
    *)
      status="failed"
      final_rc="${rc}"
      ;;
  esac
  base_record "${status}" "$(sc_utc_now)" "${final_rc}" | write_record_atomic "${RUN_DIR}/run-record.json" || wrc=$?
  if [ "${wrc}" -ne 0 ]; then
    if [ -f "${RUN_DIR}/run-record.json.partial" ] && ! check_no_identifiers "${RUN_DIR}/run-record.json.partial"; then
      printf 'ERROR: %s the run record holds a real identifier; the folder stays at %s (do not commit it)\n' \
        "${SCRIPT_NAME}" "${RUN_DIR}" >&2
      return 8
    fi
    printf 'ERROR: %s could not write %s/run-record.json; the folder stays under %s\n' \
      "${SCRIPT_NAME}" "${RUN_DIR}" "${RUN_ROOT}" >&2
    return 5
  fi
  if ! check_no_identifiers "${RUN_DIR}"; then
    printf 'ERROR: %s the run folder holds a real identifier; it is NOT moved and stays at %s (do not commit it)\n' \
      "${SCRIPT_NAME}" "${RUN_DIR}" >&2
    return 8
  fi
  mkdir -p -- "${RAW_ROOT}/${MANIFEST_ID}" || return 5
  if [ -e "${RAW_DEST}" ]; then
    printf 'ERROR: %s %s exists; the run stays at %s\n' "${SCRIPT_NAME}" "${RAW_DEST}" "${RUN_DIR}" >&2
    return 4
  fi
  mv -- "${RUN_DIR}" "${RAW_DEST}" || {
    printf 'ERROR: %s could not move %s to %s\n' "${SCRIPT_NAME}" "${RUN_DIR}" "${RAW_DEST}" >&2
    return 5
  }
  chmod -R a-w -- "${RAW_DEST}" || printf 'WARN: %s could not make %s read-only\n' "${SCRIPT_NAME}" "${RAW_DEST}" >&2
  printf 'RUN-STATUS: %s\n' "${status}"
  printf 'RAW-RESULTS: %s (commit it; read-only)\n' "${RAW_DEST}"
  return "${final_rc}"
}

main() {
  parse_args "$@"
  enter_repo_root
  check_local_prerequisites
  check_clean_tree
  check_tracked
  check_checksum
  check_example
  check_v1
  check_placeholders
  check_approval
  check_cloud_prerequisites
  read_outputs
  if [ "${CHECK_MODE}" = "true" ]; then
    run_check
    step "done"
    return 0
  fi
  if [ -n "${EXPORT_OF}" ]; then
    prepare_export
    start_run "${EXPORT_OF}-metrics-$(date -u +%Y%m%dT%H%M%SZ)"
    print_declared
    export_metrics
    step "done"
    return 0
  fi
  # The action's tracked and executable checks refuse (4) BEFORE a run folder
  # exists, so a refusal never leaves a raw folder.
  step "action-command"
  action_command
  start_run
  print_declared
  run_action
  wait_for_metrics
  export_metrics
  finish_action
  step "done"
}

main "$@"
