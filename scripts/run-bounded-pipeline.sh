#!/usr/bin/env bash
# scripts/run-bounded-pipeline.sh - start ONE execution of the bounded
# pipeline state machine, wait for it with a timeout, and print the result.
# Owner: infra-engineer (author); executed by deploy-engineer (/run-pipeline)
# after the human approves the environment, the bounded inputs and the cost
# bound. Phase 13 completes it.
#
# Status (Phase 12): static: shellcheck clean; runtime: untested (Phase 13/14
# runs it); human execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="run-bounded-pipeline.sh"
readonly EXPECTED_REGION="us-east-1"
readonly TF_DIR="infra/terraform/environments/course"
readonly OUTPUT_TIMEOUT=120
readonly AWS_TIMEOUT=90
readonly DEFAULT_WAIT=15000
readonly MAX_WAIT=44000
readonly POLL_SECONDS=60

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
ACCOUNT_ID=""
DATA_BUCKET=""
REPO_ROOT=""

usage() {
  cat <<'EOF'
Usage: scripts/run-bounded-pipeline.sh [HADOOP OPTIONS] [SPARK OPTIONS] [--wait-timeout SECONDS]
       scripts/run-bounded-pipeline.sh --help

Hadoop half (all four required if any is given):
  --hadoop-jar s3://<data bucket>/artifacts/...      ScholarChainHadoop jar
  --hadoop-output s3://<data bucket>/curated/...     job output root
  --dblp s3://<data bucket>/raw/...                  DBLP XML input
  --dblp-snapshot ID                                 dataset snapshot ID
  --hadoop-extra-arg ARG                             repeatable; appended as is
Spark half (all five required if any is given):
  --spark-jar s3://<data bucket>/artifacts/...       GraphAnalyticsJob jar
  --spark-config s3://<data bucket>/artifacts/...    Spark job config JSON
  --hadoop-export s3://<data bucket>/curated/.../run=<id>
  --identity-assignments s3://<data bucket>/curated/...
  --out-root s3://<data bucket>/graph/...
Other:
  --wait-timeout SECONDS   how long this script waits (default 15000, max 44000)

What it does (its whole contract; Phase 13 completes it):
  1. Refuses a dirty working tree: the code version passed to every Hadoop
     and Spark step is the git commit (HEAD), so the jars must be built from it.
  2. Reads state_machine_arn and data_bucket_name from the Terraform outputs.
     Every s3:// argument must be in the environment's data-lake bucket under
     the prefix shown above (bounded inputs; nothing outside the bucket).
  3. Builds the execution input {codeVersion, hadoop{...}, spark{...}} (the
     shape documented in infra/terraform/modules/workflow/sfn.tf) and starts
     ONE execution (aws stepfunctions start-execution) with a unique name.
     At least one half must be given. A Spark half needs the run=<id> export
     of an earlier Hadoop run, so the halves usually run as two executions.
  4. Polls describe-execution every 60 s until the execution ends or the wait
     timeout passes, then prints status, error and cause (the account ID and
     bucket name are masked in what it prints).

Bounds: the state machine's own TimeoutSeconds (pipeline_timeout_seconds,
default 14400 s) ends the execution; each EMR cluster has managed-scaling
min/max and an idle auto-termination policy. If THIS script times out, the
execution keeps running: the script says so and exits 7; it never stops the
execution itself. Step Functions does not end an EMR cluster when an
execution is stopped by hand: check for clusters tagged project=scholarchain
afterwards (README, "Pipeline state machine").

It asks no question and writes nothing in the repository. Requirements:
bash, git, terraform (output only), aws (CLI v2), jq (1.6+), timeout or
gtimeout (GNU coreutils); AWS credentials allowed to start and describe
executions of the state machine.

Exit codes: 0 execution SUCCEEDED; 2 usage error; 3 missing prerequisite;
4 refused by a safety check; 5 terraform or AWS command failed; 6 execution
ended FAILED, TIMED_OUT or ABORTED (evidence printed); 7 this script's wait
timed out (execution still running).

Status: static: shellcheck clean; runtime: untested; human execution required.
EOF
}

step() {
  CURRENT_STEP="$1"
  printf 'STEP: %s %s\n' "${SCRIPT_NAME}" "$1"
}
info() { printf 'INFO: %s %s\n' "${SCRIPT_NAME}" "$*"; }
die() {
  local code="$1"
  shift
  printf 'ERROR: %s step=%s exit=%s: %s\n' "${SCRIPT_NAME}" "${CURRENT_STEP}" "${code}" "$*" >&2
  exit "${code}"
}
on_err() {
  local rc="$?"
  printf 'ERROR: %s step=%s line=%s exit=5: command failed with status %s\n' \
    "${SCRIPT_NAME}" "${CURRENT_STEP}" "$1" "${rc}" >&2
  exit 5
}
on_exit() {
  local rc="$?"
  if [ -n "${TMP_DIR}" ] && [ -d "${TMP_DIR}" ]; then
    rm -rf -- "${TMP_DIR}"
  fi
  if [ "${rc}" -eq 0 ]; then
    printf 'RESULT: %s ok\n' "${SCRIPT_NAME}"
  else
    printf 'RESULT: %s failed exit=%s step=%s\n' "${SCRIPT_NAME}" "${rc}" "${CURRENT_STEP}" >&2
  fi
}
trap 'on_err "${LINENO}"' ERR
trap on_exit EXIT

mask_text() {
  local s="$1"
  if [ -n "${ACCOUNT_ID}" ]; then
    s="${s//"${ACCOUNT_ID}"/<account>}"
  fi
  if [ -n "${DATA_BUCKET}" ]; then
    s="${s//"${DATA_BUCKET}"/<data-bucket>}"
  fi
  printf '%s' "${s}"
}

with_timeout() {
  local secs="$1"
  shift
  "${TIMEOUT_BIN}" --kill-after=30 "${secs}" "$@"
}

aws_json() {
  local rc=0 out errfile
  errfile="${TMP_DIR}/aws.err"
  out="$(with_timeout "${AWS_TIMEOUT}" aws --region "${EXPECTED_REGION}" \
    --cli-connect-timeout 10 --cli-read-timeout 60 --no-cli-pager --output json \
    "$@" 2>"${errfile}")" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    [ "${rc}" -eq 124 ] && die 7 "aws $1 $2 timed out after ${AWS_TIMEOUT}s"
    printf 'AWS-ERROR: %s\n' "$(mask_text "$(cat "${errfile}")")" >&2
    die 5 "aws $1 $2 failed (status ${rc})"
  fi
  printf '%s\n' "${out}"
}

H_JAR=""
H_OUTPUT=""
H_DBLP=""
H_SNAPSHOT=""
H_EXTRA=()
S_JAR=""
S_CONFIG=""
S_EXPORT=""
S_IDENTITY=""
S_OUTROOT=""
WAIT_TIMEOUT="${DEFAULT_WAIT}"

need_value() {
  [ "$1" -ge 2 ] || die 2 "$2 needs a value"
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --hadoop-jar) need_value "$#" "$1"; H_JAR="$2"; shift 2 ;;
      --hadoop-output) need_value "$#" "$1"; H_OUTPUT="$2"; shift 2 ;;
      --dblp) need_value "$#" "$1"; H_DBLP="$2"; shift 2 ;;
      --dblp-snapshot) need_value "$#" "$1"; H_SNAPSHOT="$2"; shift 2 ;;
      --hadoop-extra-arg) need_value "$#" "$1"; H_EXTRA+=("$2"); shift 2 ;;
      --spark-jar) need_value "$#" "$1"; S_JAR="$2"; shift 2 ;;
      --spark-config) need_value "$#" "$1"; S_CONFIG="$2"; shift 2 ;;
      --hadoop-export) need_value "$#" "$1"; S_EXPORT="$2"; shift 2 ;;
      --identity-assignments) need_value "$#" "$1"; S_IDENTITY="$2"; shift 2 ;;
      --out-root) need_value "$#" "$1"; S_OUTROOT="$2"; shift 2 ;;
      --wait-timeout)
        need_value "$#" "$1"
        [[ "$2" =~ ^[0-9]+$ ]] || die 2 "--wait-timeout must be a whole number of seconds"
        [ "$2" -ge 60 ] && [ "$2" -le "${MAX_WAIT}" ] || die 2 "--wait-timeout must be 60..${MAX_WAIT}"
        WAIT_TIMEOUT="$2"
        shift 2
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
}

HADOOP_GIVEN="false"
SPARK_GIVEN="false"

check_halves() {
  if [ -n "${H_JAR}${H_OUTPUT}${H_DBLP}${H_SNAPSHOT}" ] || [ "${#H_EXTRA[@]}" -gt 0 ]; then
    HADOOP_GIVEN="true"
    [ -n "${H_JAR}" ] && [ -n "${H_OUTPUT}" ] && [ -n "${H_DBLP}" ] && [ -n "${H_SNAPSHOT}" ] ||
      die 2 "the Hadoop half needs --hadoop-jar, --hadoop-output, --dblp and --dblp-snapshot"
    [[ "${H_SNAPSHOT}" =~ ^[A-Za-z0-9._:-]{1,128}$ ]] || die 2 "--dblp-snapshot has unexpected characters"
  fi
  if [ -n "${S_JAR}${S_CONFIG}${S_EXPORT}${S_IDENTITY}${S_OUTROOT}" ]; then
    SPARK_GIVEN="true"
    [ -n "${S_JAR}" ] && [ -n "${S_CONFIG}" ] && [ -n "${S_EXPORT}" ] && [ -n "${S_IDENTITY}" ] && [ -n "${S_OUTROOT}" ] ||
      die 2 "the Spark half needs --spark-jar, --spark-config, --hadoop-export, --identity-assignments and --out-root"
  fi
  [ "${HADOOP_GIVEN}" = "true" ] || [ "${SPARK_GIVEN}" = "true" ] || {
    usage >&2
    die 2 "give the Hadoop half, the Spark half, or both"
  }
}

check_prerequisites() {
  step "prerequisites"
  local tool
  for tool in git terraform aws jq; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  else
    die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  aws --version 2>&1 | grep -q '^aws-cli/2' || die 3 "AWS CLI v2 is required"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-pipeline.XXXXXX")"
}

CODE_VERSION=""

check_clean_tree() {
  step "clean-tree"
  CODE_VERSION="$(git rev-parse --verify HEAD)" || die 3 "not a git repository with a commit"
  local dirty
  dirty="$(git status --porcelain --untracked-files=normal)" || die 5 "git status failed"
  if [ -n "${dirty}" ]; then
    git status --short --untracked-files=normal >&2
    die 4 "refusing: the working tree is dirty; the code version must be a commit"
  fi
  info "code version ${CODE_VERSION}"
}

STATE_MACHINE_ARN=""

read_outputs() {
  step "terraform-outputs"
  local rc=0 outputs
  outputs="$(with_timeout "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" output -json -no-color)" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform output failed (status ${rc})"
  STATE_MACHINE_ARN="$(jq -r '.state_machine_arn.value // empty' <<<"${outputs}")"
  DATA_BUCKET="$(jq -r '.data_bucket_name.value // empty' <<<"${outputs}")"
  [ -n "${STATE_MACHINE_ARN}" ] || die 3 "no state_machine_arn output (is the environment applied?)"
  [ -n "${DATA_BUCKET}" ] || die 3 "no data_bucket_name output"
  local ident
  ident="$(aws_json sts get-caller-identity)" || exit "$?"
  ACCOUNT_ID="$(jq -r '.Account // empty' <<<"${ident}")"
  info "state machine $(mask_text "${STATE_MACHINE_ARN##*:}")"
}

# require_uri VALUE OPTION PREFIX: the URI must be s3://<data bucket>/<prefix>/<something>.
require_uri() {
  local value="$1" option="$2" prefix="$3"
  case "${value}" in
    "s3://${DATA_BUCKET}/${prefix}/"?*) ;;
    *) die 4 "refusing: ${option} must be under s3://<data-bucket>/${prefix}/ of this environment" ;;
  esac
  case "${value}" in
    *..* | *" "*) die 4 "refusing: ${option} must not contain '..' or spaces" ;;
  esac
}

INPUT_FILE=""

build_input() {
  step "execution-input"
  INPUT_FILE="${TMP_DIR}/input.json"
  local input
  input="$(jq -n --arg cv "${CODE_VERSION}" '{codeVersion: $cv}')"
  if [ "${HADOOP_GIVEN}" = "true" ]; then
    require_uri "${H_JAR}" --hadoop-jar artifacts
    require_uri "${H_OUTPUT}" --hadoop-output curated
    require_uri "${H_DBLP}" --dblp raw
    local extra='[]' a
    for a in ${H_EXTRA[@]+"${H_EXTRA[@]}"}; do
      extra="$(jq -c --arg a "${a}" '. + [$a]' <<<"${extra}")"
    done
    input="$(jq -c --arg jar "${H_JAR}" --arg out "${H_OUTPUT}" --arg dblp "${H_DBLP}" \
      --arg snap "${H_SNAPSHOT}" --argjson extra "${extra}" \
      '. + {hadoop: ({jar: $jar, output: $out, dblp: $dblp, dblpSnapshot: $snap}
              + (if ($extra | length) > 0 then {extraArgs: $extra} else {} end))}' <<<"${input}")"
  fi
  if [ "${SPARK_GIVEN}" = "true" ]; then
    require_uri "${S_JAR}" --spark-jar artifacts
    require_uri "${S_CONFIG}" --spark-config artifacts
    require_uri "${S_EXPORT}" --hadoop-export curated
    require_uri "${S_IDENTITY}" --identity-assignments curated
    require_uri "${S_OUTROOT}" --out-root graph
    input="$(jq -c --arg jar "${S_JAR}" --arg cfg "${S_CONFIG}" --arg exp "${S_EXPORT}" \
      --arg ids "${S_IDENTITY}" --arg out "${S_OUTROOT}" \
      '. + {spark: {jar: $jar, config: $cfg, hadoopExport: $exp,
                    identityAssignments: $ids, outRoot: $out}}' <<<"${input}")"
  fi
  printf '%s\n' "${input}" >"${INPUT_FILE}"
  printf 'INPUT: %s\n' "$(mask_text "$(jq -c . "${INPUT_FILE}")")"
}

EXECUTION_ARN=""
EXECUTION_NAME=""

start_execution() {
  step "start-execution"
  EXECUTION_NAME="sc-${CODE_VERSION:0:12}-$(date -u +%Y%m%dT%H%M%SZ)"
  local started
  started="$(aws_json stepfunctions start-execution --state-machine-arn "${STATE_MACHINE_ARN}" \
    --name "${EXECUTION_NAME}" --input "file://${INPUT_FILE}")" || exit "$?"
  EXECUTION_ARN="$(jq -r '.executionArn // empty' <<<"${started}")"
  [ -n "${EXECUTION_ARN}" ] || die 5 "start-execution returned no executionArn"
  printf 'EXECUTION: name=%s started=%s\n' "${EXECUTION_NAME}" "$(jq -r '.startDate' <<<"${started}")"
}

wait_execution() {
  step "wait"
  local deadline now desc status
  deadline=$(($(date +%s) + WAIT_TIMEOUT))
  while :; do
    desc="$(aws_json stepfunctions describe-execution --execution-arn "${EXECUTION_ARN}")" || exit "$?"
    status="$(jq -r '.status' <<<"${desc}")"
    now="$(date +%s)"
    printf 'POLL: name=%s status=%s elapsed_wait=%ss\n' "${EXECUTION_NAME}" "${status}" \
      "$((WAIT_TIMEOUT - (deadline - now)))"
    if [ "${status}" != "RUNNING" ]; then
      break
    fi
    if [ "${now}" -ge "${deadline}" ]; then
      die 7 "waited ${WAIT_TIMEOUT}s; execution ${EXECUTION_NAME} is STILL RUNNING (not stopped by this script; its own TimeoutSeconds and the EMR idle policy bound it)"
    fi
    sleep "${POLL_SECONDS}"
  done

  step "result"
  printf 'RESULT-STATUS: %s\n' "${status}"
  printf 'RESULT-STOP: %s\n' "$(jq -r '.stopDate // "n/a"' <<<"${desc}")"
  if [ "${status}" = "SUCCEEDED" ]; then
    printf 'RESULT-OUTPUT: %s\n' "$(mask_text "$(jq -r '.output // "{}"' <<<"${desc}")")"
    return 0
  fi
  printf 'RESULT-ERROR: %s\n' "$(mask_text "$(jq -r '.error // "n/a"' <<<"${desc}")")" >&2
  printf 'RESULT-CAUSE: %s\n' "$(mask_text "$(jq -r '.cause // "n/a"' <<<"${desc}")")" >&2
  die 6 "execution ${EXECUTION_NAME} ended ${status}; check for clusters tagged project=scholarchain that are still running"
}

main() {
  parse_args "$@"
  check_halves
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
  cd "${REPO_ROOT}"
  check_prerequisites
  check_clean_tree
  read_outputs
  build_input
  start_execution
  wait_execution
  step "done"
}

main "$@"
