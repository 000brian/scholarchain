#!/usr/bin/env bash
# scripts/run-bounded-pipeline.sh - run ONE stage of the bounded ScholarChain
# pipeline (upload-input, hadoop, er, spark, load) and write one run record
# (Phase 13 work order, decision 9.6; spec work-order item 6). Owner:
# infra-engineer (author); executed by the human or by deploy-engineer
# (/run-pipeline) after the human approves the environment, the bounded
# inputs and the cost bound.
#
# Status (Phase 13): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="run-bounded-pipeline.sh"
readonly EXPECTED_REGION="us-east-1"
readonly TF_DIR="infra/terraform/environments/course"
readonly RECORD_DIR="evidence/runs"
readonly PACKAGES_DIR="evidence/packages"
readonly OUTPUT_TIMEOUT=120
readonly AWS_TIMEOUT=120
readonly LIST_TIMEOUT=600
readonly TRANSFER_TIMEOUT=1800
readonly MAX_TRIES=3
readonly DEFAULT_EXEC_WAIT=15000
readonly MAX_EXEC_WAIT=44000
readonly EXEC_POLL_SECONDS=60
readonly DEFAULT_LOAD_WAIT=7200
readonly MAX_LOAD_WAIT=14400
readonly LOAD_POLL_SECONDS=30
readonly DEFAULT_MAX_UPLOAD_MB=1024
readonly HARD_MAX_UPLOAD_MB=5000
readonly DEFAULT_MAX_DOWNLOAD_MB=2048
readonly HARD_MAX_DOWNLOAD_MB=20480
readonly DEFAULT_ER_HEAP_GB=6
readonly ER_TIMEOUT=14400
readonly ER_LLM_MODEL_ID_DEFAULT="none-always-abstain"
# The ops task reads the configuration file baked into the image
# (infra/terraform/modules/runtime/variables.tf, config_file_path default).
readonly OPS_CONFIG="/opt/scholarchain/config/course.json"
readonly SPARK_CONF_DEFAULT_NAME="phase06.json"
readonly STARTED_BY="scholarchain-pipeline"
readonly LOGICAL_DATA_PREFIX='s3://{data-bucket}/'

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
ATTEMPT_ID=""
RECORD_FILE=""
STARTED_WRITTEN="false"
FINAL_WRITTEN="false"
SIGNAL_NAME=""
HEAD_SHA=""
BUILD_SHA=""
STARTED_UTC=""

usage() {
  cat <<'EOF'
Usage: scripts/run-bounded-pipeline.sh --stage STAGE --snapshot ID [STAGE OPTIONS] [--wait-timeout SECONDS]
       scripts/run-bounded-pipeline.sh --help

Run it from the repository root (bash scripts/run-bounded-pipeline.sh); it
refuses elsewhere. One invocation runs ONE stage. A full bounded run is, in
order: upload-input, hadoop, er, spark, load (one load per loader).

S3 locations: every s3:// argument may be written in the logical form
s3://{data-bucket}/KEY (resolved from the Terraform output data_bucket_name)
or with the real bucket; either way it must lie in this environment's
data-lake bucket under the prefix named for that option (raw/, curated/,
graph/, artifacts/; the names come from the Terraform output data_prefixes).
Records and printed lines show only the logical form.

Common options:
  --stage upload-input|hadoop|er|spark|load
  --snapshot ID         dataset snapshot ID, [A-Za-z0-9][A-Za-z0-9._-]{0,99};
                        recorded by every stage; --dblp-snapshot is accepted
                        as the same option
  --wait-timeout SEC    how long THIS script waits for remote work (hadoop
                        and spark: default 15000, 60..44000; load: default
                        7200, 60..14400). On time-out the remote work keeps
                        running (never stopped by this script) and the exit
                        code is 7.

--stage upload-input (copies one bounded local file to the raw zone):
  --source NAME         source name, [a-z0-9][a-z0-9-]{0,63} (for example dblp)
  --file PATH           the local file (for example the DBLP subset)
  --max-upload-mb N     refuse a file above N MiB (default 1024, at most 5000)
  Key: s3://{data-bucket}/raw/SOURCE/SNAPSHOT/BASENAME. The one exception to
  attempt-ID keys: input keys are fixed by source and snapshot; a key that
  exists with the same sha256 is a no-op, with other bytes it is refused.

--stage hadoop (one Step Functions execution with the Hadoop half only):
  --dblp s3://{data-bucket}/raw/...            DBLP input (required)
  --hadoop-output s3://{data-bucket}/curated/...  job output root
                        (default s3://{data-bucket}/curated/hadoop; jobs are
                        run-addressed, so the same inputs and code reuse
                        completed runs)
  --hadoop-jar s3://{data-bucket}/artifacts/...  (default
                        s3://{data-bucket}/artifacts/BUILD_COMMIT/jars/hadoop.jar)
  --hadoop-extra-arg ARG  repeatable, appended as is (an s3:// part must be
                        in the data-lake bucket; {data-bucket} is resolved);
                        the DBLP DTD is passed as two of them, in order:
                        '--dblp-dtd' 's3://{data-bucket}/raw/dblp/SNAPSHOT/dblp.dtd'
                        (scripts/README.md "DBLP DTD"); recorded in
                        configuration.extra_args
  Recorded: execution name, EMR cluster IDs (execution history), the J09
  export runs (curated/.../j09-graph-export/run=HEX) under the output root.

--stage er (entity resolution on THIS machine; no AWS compute):
  --hadoop-export s3://{data-bucket}/curated/.../run=HEX  (required)
  --decision-time ISO-8601-UTC   (required; ErMain --decision-time)
  --seed INTEGER                 (required; ErMain --seed)
  --max-download-mb N   refuse an export above N MiB (default 2048, at most
                        20480); also refused when the free local disk is
                        less than three times the export size
  --er-heap-gb N        java -Xmx (default 6, 1..64)
  --llm-model-id ID     recorded model ID (default none-always-abstain;
                        the adjudicator is always-abstain, so no model runs)
  Downloads the export to a NEW folder ${TMPDIR:-/tmp}/scholarchain-er-ATTEMPT_ID/
  (kept until YOU remove it), checks every file against the export's own
  _job.json "outputs" list (path, length, sha256; falls back to the file
  count and bytes of the listing when there is no _job.json), runs
  java -Xmx<N>g -cp ENTITY_RESOLUTION_JAR ErMain with
  --adjudicator always-abstain, --llm-max-per-run 0 and --code-version
  BUILD_COMMIT, then uploads the output root to the NEW prefix
  s3://{data-bucket}/curated/er/ATTEMPT_ID/ (data files first, then
  _manifest.json, then _SUCCESS; never overwriting). The jar is the one
  scripts/package.sh built for the build commit
  (evidence/packages/BUILD_COMMIT/package-record.json; its sha256 and its
  recorded build identity, buildCommit = BUILD_COMMIT and buildDirty false,
  are checked). No resume: a failed or partial attempt keeps a "failed"
  record; a new attempt starts again from the download.

--stage spark (one execution with the Spark half):
  --hadoop-export s3://{data-bucket}/curated/.../run=HEX      (required)
  --identity-assignments s3://{data-bucket}/curated/er/ATTEMPT/run=HEX/decisions/identity_assignment
                        (required; only an ER output whose run record under
                        evidence/runs/ says "succeeded" is accepted)
  --out-root s3://{data-bucket}/graph/...     (required)
  --spark-jar s3://{data-bucket}/artifacts/...  (default
                        s3://{data-bucket}/artifacts/BUILD_COMMIT/jars/spark-analytics.jar)
  --spark-config s3://{data-bucket}/artifacts/...  (default
                        s3://{data-bucket}/artifacts/BUILD_COMMIT/spark-conf/phase06.json)
  --export-source-run s3://{data-bucket}/graph/.../run=HEX32  and
  --export-root s3://{data-bucket}/graph/PREFIX
                        both or neither: adds the JSON Lines export step
                        (state-machine fields spark.exportSourceRun and
                        spark.exportRoot); the export is written under
                        PREFIX/ATTEMPT_ID/run=HEX. Give the same Spark
                        inputs and out-root as the run being exported, so
                        the graph job reuses that verified run.
  Recorded: execution name, EMR cluster IDs, the graph runs
  (run=HEX/_run_manifest.json) under the out-root and the export run.

--stage load (one ops task on ECS Fargate; one at a time):
  --loader dynamodb-load|neptune-csv-build|neptune-bulk-start|neptune-bulk-status|rag-ingest
  --export s3://{data-bucket}/graph/.../run=HEX   (dynamodb-load,
                        neptune-csv-build, rag-ingest)
  --dataset-id ID       (neptune-csv-build; --snapshot is its snapshot ID)
  --graph-run s3://{data-bucket}/graph/dataset=.../snapshot=.../run=HEX
                        (neptune-bulk-start; the loader role ARN is read from
                        the Terraform output neptune_loader_role_arn and is
                        never recorded)
  --load-id ID          (neptune-bulk-status)
  --wait-seconds N      (neptune-bulk-status, 0..3600, passed on)
  Refuses unless the ops task definition's image tag is the build commit
  and no ops task of this family is running. Runs aws ecs run-task (Fargate,
  app subnets, ops security group, no public IP) with the command of
  StorageLoadMain SUBCOMMAND or RagIngestJob built from their option
  constants, waits for the task to stop, and records the task ID, the
  container exit code and the log stream (logical). A non-zero exit code is
  a failed stage.

Every stage: refuses a dirty tree (tracked changes or untracked files
outside .gitignore); the code version passed to every job is the BUILD
COMMIT (the newest commit that changed a build input, scripts/lib/identity.sh);
a jar in S3 is refused unless its object metadata scholarchain-commit is the
build commit. Record: evidence/runs/ATTEMPT_ID-STAGE.started.json before the
first action, then evidence/runs/ATTEMPT_ID-STAGE.json (succeeded, failed
with step and exit code, or timed-out; written by the exit trap, also on
INT and TERM); one line "RUN-RECORD: evidence/runs/ATTEMPT_ID-STAGE.json" is
printed. The record holds: stage, HEAD, build commit, dataset snapshot,
inputs, outputs, run IDs (run_ids.execution_name, run_ids.emr_cluster_ids,
Hadoop J09 runs, ER output root and run ID, Spark graph and export runs, ops
task ID), configuration file and its sha256, uploaded keys, start and end
UTC, result. Logical locations only; checked for the account ID and bucket
names before it is written.

Bounds: the state machine's TimeoutSeconds, the EMR managed-scaling maximum
and idle end bound the remote work (Terraform). Step Functions does not end
an EMR cluster when an execution is stopped by hand: check for clusters
tagged project=scholarchain afterwards. Retries: only reads and put-if-absent
uploads are retried; start-execution and run-task are never retried.

Requirements: bash, git, terraform (output only), aws (CLI v2), jq (1.6+),
shasum or sha256sum, timeout or gtimeout (GNU coreutils); for --stage er
also java 17 and df; AWS credentials allowed to do the stage (the human's
or the deploy profile).

Exit codes: 0 stage succeeded; 2 usage error; 3 missing prerequisite or
Terraform output; 4 refused by a safety check (not at the repository root,
dirty tree, location outside the bucket or prefix, jar or image not of the
build commit, size or disk bound, existing key with other bytes, ER output
without a succeeded record, an ops task already running); 5 a command
failed; 6 the remote or local job ended unsuccessfully (execution FAILED,
TIMED_OUT or ABORTED; non-zero container or ErMain exit; a download that
does not match its manifest); 7 timed out (remote work may still run).

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
  if [ "${STARTED_WRITTEN}" = "true" ] && [ "${FINAL_WRITTEN}" = "false" ]; then
    write_final_record "${rc}"
  fi
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
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap on_exit EXIT

# Until the helper is loaded, mask_text prints its argument unchanged; the
# helper's mask_text (scripts/lib/identity.sh) then replaces this definition.
mask_text() { printf '%s' "$*"; }

with_timeout() {
  local secs="$1"
  shift
  "${TIMEOUT_BIN}" --kill-after=30 "${secs}" "$@"
}

# ---- arguments -------------------------------------------------------------------

STAGE=""
SNAPSHOT=""
WAIT_TIMEOUT=""
GIVEN=" "
A_SOURCE=""
A_FILE=""
A_MAX_UPLOAD_MB="${DEFAULT_MAX_UPLOAD_MB}"
A_DBLP=""
A_HADOOP_OUTPUT=""
A_HADOOP_JAR=""
A_HADOOP_EXTRA=()
A_HADOOP_EXPORT=""
A_DECISION_TIME=""
A_SEED=""
A_MAX_DOWNLOAD_MB="${DEFAULT_MAX_DOWNLOAD_MB}"
A_ER_HEAP_GB="${DEFAULT_ER_HEAP_GB}"
A_LLM_MODEL_ID="${ER_LLM_MODEL_ID_DEFAULT}"
A_IDENTITY=""
A_OUT_ROOT=""
A_SPARK_JAR=""
A_SPARK_CONFIG=""
A_EXPORT_SOURCE_RUN=""
A_EXPORT_ROOT=""
A_LOADER=""
A_EXPORT=""
A_DATASET_ID=""
A_GRAPH_RUN=""
A_LOAD_ID=""
A_WAIT_SECONDS=""

need_value() {
  [ "$1" -ge 2 ] || die 2 "$2 needs a value"
}

mark_given() {
  case "${GIVEN}" in
    *" $1 "*)
      [ "$1" = "hadoop-extra-arg" ] || die 2 "--$1 is given twice"
      ;;
    *) GIVEN="${GIVEN}$1 " ;;
  esac
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --stage) need_value "$#" "$1"; mark_given stage; STAGE="$2"; shift 2 ;;
      --snapshot | --dblp-snapshot) need_value "$#" "$1"; mark_given snapshot; SNAPSHOT="$2"; shift 2 ;;
      --wait-timeout) need_value "$#" "$1"; mark_given wait-timeout; WAIT_TIMEOUT="$2"; shift 2 ;;
      --source) need_value "$#" "$1"; mark_given source; A_SOURCE="$2"; shift 2 ;;
      --file) need_value "$#" "$1"; mark_given file; A_FILE="$2"; shift 2 ;;
      --max-upload-mb) need_value "$#" "$1"; mark_given max-upload-mb; A_MAX_UPLOAD_MB="$2"; shift 2 ;;
      --dblp) need_value "$#" "$1"; mark_given dblp; A_DBLP="$2"; shift 2 ;;
      --hadoop-output) need_value "$#" "$1"; mark_given hadoop-output; A_HADOOP_OUTPUT="$2"; shift 2 ;;
      --hadoop-jar) need_value "$#" "$1"; mark_given hadoop-jar; A_HADOOP_JAR="$2"; shift 2 ;;
      --hadoop-extra-arg) need_value "$#" "$1"; mark_given hadoop-extra-arg; A_HADOOP_EXTRA+=("$2"); shift 2 ;;
      --hadoop-export) need_value "$#" "$1"; mark_given hadoop-export; A_HADOOP_EXPORT="$2"; shift 2 ;;
      --decision-time) need_value "$#" "$1"; mark_given decision-time; A_DECISION_TIME="$2"; shift 2 ;;
      --seed) need_value "$#" "$1"; mark_given seed; A_SEED="$2"; shift 2 ;;
      --max-download-mb) need_value "$#" "$1"; mark_given max-download-mb; A_MAX_DOWNLOAD_MB="$2"; shift 2 ;;
      --er-heap-gb) need_value "$#" "$1"; mark_given er-heap-gb; A_ER_HEAP_GB="$2"; shift 2 ;;
      --llm-model-id) need_value "$#" "$1"; mark_given llm-model-id; A_LLM_MODEL_ID="$2"; shift 2 ;;
      --identity-assignments) need_value "$#" "$1"; mark_given identity-assignments; A_IDENTITY="$2"; shift 2 ;;
      --out-root) need_value "$#" "$1"; mark_given out-root; A_OUT_ROOT="$2"; shift 2 ;;
      --spark-jar) need_value "$#" "$1"; mark_given spark-jar; A_SPARK_JAR="$2"; shift 2 ;;
      --spark-config) need_value "$#" "$1"; mark_given spark-config; A_SPARK_CONFIG="$2"; shift 2 ;;
      --export-source-run) need_value "$#" "$1"; mark_given export-source-run; A_EXPORT_SOURCE_RUN="$2"; shift 2 ;;
      --export-root) need_value "$#" "$1"; mark_given export-root; A_EXPORT_ROOT="$2"; shift 2 ;;
      --loader) need_value "$#" "$1"; mark_given loader; A_LOADER="$2"; shift 2 ;;
      --export) need_value "$#" "$1"; mark_given export; A_EXPORT="$2"; shift 2 ;;
      --dataset-id) need_value "$#" "$1"; mark_given dataset-id; A_DATASET_ID="$2"; shift 2 ;;
      --graph-run) need_value "$#" "$1"; mark_given graph-run; A_GRAPH_RUN="$2"; shift 2 ;;
      --load-id) need_value "$#" "$1"; mark_given load-id; A_LOAD_ID="$2"; shift 2 ;;
      --wait-seconds) need_value "$#" "$1"; mark_given wait-seconds; A_WAIT_SECONDS="$2"; shift 2 ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
}

# allow_only NAME...: refuses any given option outside the common ones and NAME...
allow_only() {
  local allowed=" stage snapshot wait-timeout $* " opt
  for opt in ${GIVEN}; do
    case "${allowed}" in
      *" ${opt} "*) ;;
      *) die 2 "--${opt} does not belong to --stage ${STAGE}" ;;
    esac
  done
}

require_given() {
  local opt
  for opt in "$@"; do
    case "${GIVEN}" in
      *" ${opt} "*) ;;
      *) die 2 "--stage ${STAGE} needs --${opt}" ;;
    esac
  done
}

is_given() {
  case "${GIVEN}" in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}

int_in() {
  # int_in VALUE LO HI
  [[ "$1" =~ ^[0-9]+$ ]] && [ "$1" -ge "$2" ] && [ "$1" -le "$3" ]
}

check_args() {
  [ -n "${STAGE}" ] || {
    usage >&2
    die 2 "--stage is required"
  }
  [ -n "${SNAPSHOT}" ] || die 2 "--snapshot is required"
  [[ "${SNAPSHOT}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$ ]] || die 2 "--snapshot must match [A-Za-z0-9][A-Za-z0-9._-]{0,99}"
  case "${STAGE}" in
    upload-input)
      allow_only source file max-upload-mb
      require_given source file
      [[ "${A_SOURCE}" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] || die 2 "--source must match [a-z0-9][a-z0-9-]{0,63}"
      int_in "${A_MAX_UPLOAD_MB}" 1 "${HARD_MAX_UPLOAD_MB}" || die 2 "--max-upload-mb must be 1..${HARD_MAX_UPLOAD_MB}"
      ;;
    hadoop)
      allow_only dblp hadoop-output hadoop-jar hadoop-extra-arg
      require_given dblp
      ;;
    er)
      allow_only hadoop-export decision-time seed max-download-mb er-heap-gb llm-model-id
      require_given hadoop-export decision-time seed
      [[ "${A_DECISION_TIME}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] ||
        die 2 "--decision-time must look like 2026-11-14T00:00:00Z"
      [[ "${A_SEED}" =~ ^[0-9]{1,18}$ ]] || die 2 "--seed must be a non-negative integer"
      int_in "${A_MAX_DOWNLOAD_MB}" 1 "${HARD_MAX_DOWNLOAD_MB}" || die 2 "--max-download-mb must be 1..${HARD_MAX_DOWNLOAD_MB}"
      int_in "${A_ER_HEAP_GB}" 1 64 || die 2 "--er-heap-gb must be 1..64"
      [[ "${A_LLM_MODEL_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$ ]] || die 2 "--llm-model-id has unexpected characters"
      ;;
    spark)
      allow_only hadoop-export identity-assignments out-root spark-jar spark-config export-source-run export-root
      require_given hadoop-export identity-assignments out-root
      if is_given export-source-run || is_given export-root; then
        require_given export-source-run export-root
      fi
      ;;
    load)
      allow_only loader export dataset-id graph-run load-id wait-seconds
      require_given loader
      case "${A_LOADER}" in
        dynamodb-load | rag-ingest)
          allow_only loader export
          require_given export
          ;;
        neptune-csv-build)
          allow_only loader export dataset-id
          require_given export dataset-id
          [[ "${A_DATASET_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._=-]{0,99}$ ]] || die 2 "--dataset-id has unexpected characters"
          ;;
        neptune-bulk-start)
          allow_only loader graph-run
          require_given graph-run
          ;;
        neptune-bulk-status)
          allow_only loader load-id wait-seconds
          require_given load-id
          [[ "${A_LOAD_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9-]{0,99}$ ]] || die 2 "--load-id has unexpected characters"
          if is_given wait-seconds; then
            int_in "${A_WAIT_SECONDS}" 0 3600 || die 2 "--wait-seconds must be 0..3600"
          fi
          ;;
        *) die 2 "--loader must be dynamodb-load, neptune-csv-build, neptune-bulk-start, neptune-bulk-status or rag-ingest" ;;
      esac
      ;;
    *) die 2 "--stage must be upload-input, hadoop, er, spark or load" ;;
  esac
  if [ -n "${WAIT_TIMEOUT}" ]; then
    case "${STAGE}" in
      hadoop | spark) int_in "${WAIT_TIMEOUT}" 60 "${MAX_EXEC_WAIT}" || die 2 "--wait-timeout must be 60..${MAX_EXEC_WAIT}" ;;
      load) int_in "${WAIT_TIMEOUT}" 60 "${MAX_LOAD_WAIT}" || die 2 "--wait-timeout must be 60..${MAX_LOAD_WAIT}" ;;
      *) die 2 "--wait-timeout does not belong to --stage ${STAGE}" ;;
    esac
  else
    case "${STAGE}" in
      load) WAIT_TIMEOUT="${DEFAULT_LOAD_WAIT}" ;;
      *) WAIT_TIMEOUT="${DEFAULT_EXEC_WAIT}" ;;
    esac
  fi
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

check_prerequisites() {
  step "prerequisites"
  local tool
  for tool in git terraform aws jq cut; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if [ "${STAGE}" = "er" ]; then
    for tool in java df awk find sort; do
      command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
    done
  fi
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  else
    die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 ||
    die 3 "neither shasum nor sha256sum found"
  aws --version 2>&1 | grep -q '^aws-cli/2' || die 3 "AWS CLI v2 is required"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-pipeline.XXXXXX")"
}

check_clean_tree() {
  step "clean-tree"
  HEAD_SHA="$(head_commit)" || die 3 "not a git repository with a commit"
  require_clean_tree || die 4 "refusing: the working tree is dirty; the code version must be a commit"
  BUILD_SHA="$(build_commit)" || die 3 "cannot determine the build commit"
  info "HEAD ${HEAD_SHA}; code version (build commit) ${BUILD_SHA}"
}

# ---- cloud helpers ---------------------------------------------------------------

aws_errfile() { printf '%s/aws.err' "${TMP_DIR}"; }

print_aws_error() {
  printf 'AWS-ERROR: %s\n' "$(mask_text "$(cat "$(aws_errfile)" 2>/dev/null)")" >&2
}

# aws_read SECONDS ARGS...: a read, retried up to MAX_TRIES times; a "not
# found" answer is final. Status 7 on time-out, 5 otherwise.
aws_read() {
  local secs="$1" try=1 rc out
  shift
  while :; do
    rc=0
    out="$(with_timeout "${secs}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 60 --no-cli-pager --output json "$@" 2>"$(aws_errfile)")" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      [ -n "${out}" ] || out='{}'
      printf '%s\n' "${out}"
      return 0
    fi
    if grep -qE 'NotFound|NoSuchKey|\(404\)' "$(aws_errfile)" 2>/dev/null; then
      return 5
    fi
    if [ "${try}" -ge "${MAX_TRIES}" ]; then
      [ "${rc}" -eq 124 ] && return 7
      return 5
    fi
    try=$((try + 1))
    sleep $((try * 5))
  done
}

# aws_once SECONDS ARGS...: a call that is NOT retried (it starts something).
aws_once() {
  local secs="$1" rc=0 out
  shift
  out="$(with_timeout "${secs}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
    --cli-read-timeout 60 --no-cli-pager --output json "$@" 2>"$(aws_errfile)")" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    print_aws_error
    [ "${rc}" -eq 124 ] && return 7
    return 5
  fi
  [ -n "${out}" ] || out='{}'
  printf '%s\n' "${out}"
}

OUTPUTS=""
DATA_BUCKET=""
P_RAW=""
P_CURATED=""
P_GRAPH=""
P_ARTIFACTS=""

# out NAME: a Terraform root output (the "$(out NAME)" form check.sh gate 8 reads).
out() { jq -r --arg k "$1" '.[$k].value // empty' <<<"${OUTPUTS}"; }

read_outputs() {
  step "terraform-outputs"
  local rc=0 vector ident
  OUTPUTS="$(with_timeout "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" output -json -no-color 2>/dev/null)" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform output failed (status ${rc})"
  [ -n "${OUTPUTS}" ] || OUTPUTS='{}'
  DATA_BUCKET="$(jq -r '.data_bucket_name.value // empty' <<<"${OUTPUTS}")"
  vector="$(jq -r '.vector_bucket_name.value // empty' <<<"${OUTPUTS}")"
  P_RAW="$(jq -r '.data_prefixes.value.raw // empty' <<<"${OUTPUTS}")"
  P_CURATED="$(jq -r '.data_prefixes.value.curated // empty' <<<"${OUTPUTS}")"
  P_GRAPH="$(jq -r '.data_prefixes.value.graph // empty' <<<"${OUTPUTS}")"
  P_ARTIFACTS="$(jq -r '.data_prefixes.value.artifacts // empty' <<<"${OUTPUTS}")"
  [ -n "${DATA_BUCKET}" ] && [ -n "${P_RAW}" ] && [ -n "${P_CURATED}" ] && [ -n "${P_GRAPH}" ] && [ -n "${P_ARTIFACTS}" ] ||
    die 3 "terraform output lacks data_bucket_name or data_prefixes (is the environment applied?)"
  ident="$(aws_read "${AWS_TIMEOUT}" sts get-caller-identity)" || {
    print_aws_error
    die 5 "aws sts get-caller-identity failed"
  }
  register_identifiers "$(jq -r '.Account // empty' <<<"${ident}")" "${DATA_BUCKET}" "${vector}" ||
    die 5 "could not read the account"
  info "account $(mask_account "$(jq -r '.Account // empty' <<<"${ident}")"), Region ${EXPECTED_REGION}, data-lake bucket <data-bucket> (from terraform output)"
}

# resolve_location VALUE OPTION PREFIX: prints the real s3:// URI of VALUE
# (logical {data-bucket} or real form), refusing anything outside
# s3://DATA_BUCKET/PREFIX/.
resolve_location() {
  local value="$1" option="$2" prefix="$3" real rc=0
  real="$(resolve_s3 "${value}")" || rc=$?
  [ "${rc}" -eq 0 ] || die 4 "refusing: ${option} has an unknown bucket placeholder"
  case "${real}" in
    "s3://${DATA_BUCKET}/${prefix}/"?*) ;;
    *) die 4 "refusing: ${option} must be under s3://{data-bucket}/${prefix}/ of this environment" ;;
  esac
  case "${real}" in
    *..* | *" "* | *"{"* | *"}"*) die 4 "refusing: ${option} must not contain '..', spaces or braces" ;;
  esac
  case "${real#s3://}" in
    *//*) die 4 "refusing: ${option} must not contain an empty path segment" ;;
  esac
  printf '%s\n' "${real%/}"
}

key_of() { printf '%s' "${1#"s3://${DATA_BUCKET}/"}"; }

# head_meta KEY FIELD: prints the object metadata FIELD (or "-" when the
# object has none), or nothing when the key does not exist.
head_meta() {
  local key="$1" field="$2" out rc=0
  out="$(aws_read "${AWS_TIMEOUT}" s3api head-object --bucket "${DATA_BUCKET}" --key "${key}")" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    if grep -qE '\(404\)|Not Found|NoSuchKey' "$(aws_errfile)" 2>/dev/null; then
      return 0
    fi
    print_aws_error
    return "${rc}"
  fi
  jq -r --arg f "${field}" '.Metadata[$f] // "-"' <<<"${out}"
}

# check_artifact_commit URI WHAT: refuses an artifact whose object metadata
# scholarchain-commit is not the build commit; prints its scholarchain-sha256.
check_artifact_commit() {
  local uri="$1" what="$2" key commit sha
  key="$(key_of "${uri}")"
  commit="$(head_meta "${key}" scholarchain-commit)" || die 5 "aws s3api head-object failed for $(logical_s3 "${uri}")"
  [ -n "${commit}" ] || die 4 "refusing: ${what} $(logical_s3 "${uri}") does not exist (run scripts/package.sh --publish)"
  [ "${commit}" = "${BUILD_SHA}" ] ||
    die 4 "refusing: ${what} $(logical_s3 "${uri}") has scholarchain-commit '${commit}', not the build commit ${BUILD_SHA}"
  sha="$(head_meta "${key}" scholarchain-sha256)" || die 5 "aws s3api head-object failed for $(logical_s3 "${uri}")"
  printf '%s\n' "${sha}"
}

UPLOADED_JSON="[]"

# put_once FILE KEY: uploads FILE to KEY put-if-absent with the metadata
# scholarchain-sha256, scholarchain-commit and scholarchain-attempt; a key
# that exists with the same sha256 is a no-op, with other bytes (or without
# the metadata) it is refused (exit 4). Only this upload is retried.
put_once() {
  local file="$1" key="$2" sha bytes meta outcome="" try=1 rc logical
  sha="$(sc_sha256 "${file}")"
  bytes="$(sc_bytes "${file}")"
  logical="$(logical_s3 "s3://${DATA_BUCKET}/${key}")"
  while :; do
    meta="$(head_meta "${key}" scholarchain-sha256)" || die 5 "aws s3api head-object failed for ${logical}"
    if [ -n "${meta}" ]; then
      [ "${meta}" = "${sha}" ] ||
        die 4 "refusing: ${logical} exists with other bytes (or without scholarchain-sha256 metadata); nothing is overwritten"
      outcome="${outcome:-already-present}"
      break
    fi
    rc=0
    with_timeout "${TRANSFER_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 300 --no-cli-pager --output json s3api put-object --bucket "${DATA_BUCKET}" \
      --key "${key}" --body "${file}" --if-none-match '*' --checksum-algorithm SHA256 \
      --metadata "scholarchain-sha256=${sha},scholarchain-commit=${BUILD_SHA},scholarchain-attempt=${ATTEMPT_ID}" \
      >/dev/null 2>"$(aws_errfile)" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      outcome="uploaded"
      continue # read the metadata back once
    fi
    [ "${try}" -lt "${MAX_TRIES}" ] || {
      print_aws_error
      [ "${rc}" -eq 124 ] && die 7 "upload of ${logical} timed out"
      die 5 "upload of ${logical} failed after ${MAX_TRIES} tries"
    }
    try=$((try + 1))
    warn "upload failed (status ${rc}); checking the key and retrying (${try}/${MAX_TRIES})"
    sleep $((try * 5))
  done
  UPLOADED_JSON="$(jq -c --arg u "${logical}" --arg s "${sha}" --argjson b "${bytes}" --arg o "${outcome}" \
    '. + [{location: $u, sha256: $s, bytes: $b, outcome: $o}]' <<<"${UPLOADED_JSON}")"
  info "${logical} ${outcome}"
}

# list_keys PREFIX_KEY: prints "KEY<TAB>SIZE" for every object under
# PREFIX_KEY/ (CLI pagination; directory markers skipped).
list_keys() {
  local prefix="$1" out
  out="$(aws_read "${LIST_TIMEOUT}" s3api list-objects-v2 --bucket "${DATA_BUCKET}" --prefix "${prefix%/}/")" || {
    print_aws_error
    die 5 "aws s3api list-objects-v2 failed for $(logical_s3 "s3://${DATA_BUCKET}/${prefix}")"
  }
  jq -r '.Contents[]? | select(.Key | endswith("/") | not) | "\(.Key)\t\(.Size)"' <<<"${out}"
}

# ---- records ---------------------------------------------------------------------

INPUTS_JSON="{}"
OUTPUTS_JSON="{}"
RUN_IDS_JSON='{"execution_name": null, "emr_cluster_ids": []}'
CONFIG_JSON='{"file": null, "sha256": null}'

add_json() {
  # add_json VARNAME KEY JSONVALUE
  local cur
  cur="${!1}"
  printf -v "$1" '%s' "$(jq -c --arg k "$2" --argjson v "$3" '. + {($k): $v}' <<<"${cur}")"
}

add_str() {
  # add_str VARNAME KEY STRING
  add_json "$1" "$2" "$(jq -n --arg s "$3" '$s')"
}

record_json() {
  jq -n --arg kind "scholarchain-pipeline-run-record" --arg status "$1" --arg attempt "${ATTEMPT_ID}" \
    --arg stage "${STAGE}" --arg head "${HEAD_SHA}" --arg build "${BUILD_SHA}" --arg snapshot "${SNAPSHOT}" \
    --arg region "${EXPECTED_REGION}" --arg started "${STARTED_UTC}" --arg ended "${2:-}" \
    --argjson exit_code "${3:-null}" --arg step "${CURRENT_STEP}" --arg signal "${SIGNAL_NAME}" \
    --argjson inputs "${INPUTS_JSON}" --argjson outputs "${OUTPUTS_JSON}" --argjson run_ids "${RUN_IDS_JSON}" \
    --argjson configuration "${CONFIG_JSON}" --argjson uploaded "${UPLOADED_JSON}" \
    '{kind: $kind, version: 1, status: $status, attempt_id: $attempt, stage: $stage,
      head: $head, build_commit: $build, code_version: $build, dataset_snapshot: $snapshot,
      region: $region, started_utc: $started, ended_utc: (if $ended == "" then null else $ended end),
      exit_code: $exit_code,
      failed_step: (if ($status | IN("started", "succeeded")) then null else $step end),
      signal: (if $signal == "" then null else $signal end),
      inputs: $inputs, outputs: $outputs, run_ids: $run_ids, configuration: $configuration,
      uploaded_keys: $uploaded,
      result: (if $status == "started" then null else $status end)}'
}

start_record() {
  step "record"
  ATTEMPT_ID="$(new_attempt_id)"
  STARTED_UTC="$(sc_utc_now)"
  RECORD_FILE="${RECORD_DIR}/${ATTEMPT_ID}-${STAGE}.json"
  git check-ignore -q -- "${RECORD_DIR}/x" || die 4 "refusing: ${RECORD_DIR}/ is not gitignored"
  record_json "started" | write_record_atomic "${RECORD_DIR}/${ATTEMPT_ID}-${STAGE}.started.json" ||
    die 5 "could not write the started record"
  STARTED_WRITTEN="true"
  printf 'ATTEMPT: %s\n' "${ATTEMPT_ID}"
}

write_final_record() {
  local rc="$1" status
  case "${rc}" in
    0) status="succeeded" ;;
    7) status="timed-out" ;;
    *) status="failed" ;;
  esac
  FINAL_WRITTEN="true"
  if record_json "${status}" "$(sc_utc_now)" "${rc}" | write_record_atomic "${RECORD_FILE}"; then
    printf 'RUN-RECORD: %s\n' "${RECORD_FILE}"
  else
    printf 'WARN: %s could not write the final record %s (the started record stays: an incomplete attempt)\n' \
      "${SCRIPT_NAME}" "${RECORD_FILE}" >&2
  fi
}

# ---- Step Functions ----------------------------------------------------------------

STATE_MACHINE_ARN=""
EXECUTION_ARN=""
EXECUTION_NAME=""

# collect_cluster_ids: EMR cluster IDs from the execution history (the
# createCluster.sync results and the parameters of later EMR tasks); best
# effort, a failure only warns.
collect_cluster_ids() {
  local hist ids
  hist="$(aws_read "${LIST_TIMEOUT}" stepfunctions get-execution-history --execution-arn "${EXECUTION_ARN}" \
    --max-items 2000)" || {
    warn "could not read the execution history; EMR cluster IDs not recorded"
    return 0
  }
  ids="$(jq -c '[.events[]? | (.taskSucceededEventDetails.output?, .taskScheduledEventDetails.parameters?)
      | strings | scan("j-[A-Z0-9]{6,}")] | unique' <<<"${hist}" 2>/dev/null)" || ids='[]'
  add_json RUN_IDS_JSON emr_cluster_ids "${ids}"
  info "EMR clusters of this execution: $(jq -r 'join(", ")' <<<"${ids}")"
}

# run_execution INPUT_FILE LOGICAL_INPUT_JSON: starts ONE execution, waits
# (bounded), records its name and cluster IDs.
run_execution() {
  local input_file="$1" logical_input="$2" started desc status now deadline
  STATE_MACHINE_ARN="$(out state_machine_arn)"
  [ -n "${STATE_MACHINE_ARN}" ] || die 3 "no state_machine_arn output (is the environment applied?)"
  add_json INPUTS_JSON execution_input "${logical_input}"

  step "start-execution"
  EXECUTION_NAME="${ATTEMPT_ID}-${STAGE}"
  started="$(aws_once "${AWS_TIMEOUT}" stepfunctions start-execution --state-machine-arn "${STATE_MACHINE_ARN}" \
    --name "${EXECUTION_NAME}" --input "file://${input_file}")" || die "$?" "start-execution failed (not retried)"
  EXECUTION_ARN="$(jq -r '.executionArn // empty' <<<"${started}")"
  [ -n "${EXECUTION_ARN}" ] || die 5 "start-execution returned no executionArn"
  add_str RUN_IDS_JSON execution_name "${EXECUTION_NAME}"
  printf 'EXECUTION: name=%s started=%s\n' "${EXECUTION_NAME}" "$(jq -r '.startDate' <<<"${started}")"

  step "wait-execution"
  deadline=$(($(date +%s) + WAIT_TIMEOUT))
  while :; do
    desc="$(aws_read "${AWS_TIMEOUT}" stepfunctions describe-execution --execution-arn "${EXECUTION_ARN}")" || {
      print_aws_error
      die 5 "describe-execution failed (the execution may still run: ${EXECUTION_NAME})"
    }
    status="$(jq -r '.status' <<<"${desc}")"
    now="$(date +%s)"
    printf 'POLL: name=%s status=%s elapsed_wait=%ss\n' "${EXECUTION_NAME}" "${status}" \
      "$((WAIT_TIMEOUT - (deadline - now)))"
    if [ "${status}" != "RUNNING" ] && [ "${status}" != "PENDING_REDRIVE" ]; then
      break
    fi
    if [ "${now}" -ge "${deadline}" ]; then
      collect_cluster_ids
      die 7 "waited ${WAIT_TIMEOUT}s; execution ${EXECUTION_NAME} is STILL RUNNING (not stopped by this script; its TimeoutSeconds and the EMR idle end bound it)"
    fi
    sleep "${EXEC_POLL_SECONDS}"
  done

  step "execution-result"
  collect_cluster_ids
  add_str OUTPUTS_JSON execution_status "${status}"
  printf 'RESULT-STATUS: %s\n' "${status}"
  if [ "${status}" != "SUCCEEDED" ]; then
    printf 'RESULT-ERROR: %s\n' "$(mask_text "$(jq -r '.error // "n/a"' <<<"${desc}")")" >&2
    printf 'RESULT-CAUSE: %s\n' "$(mask_text "$(jq -r '.cause // "n/a"' <<<"${desc}")")" >&2
    die 6 "execution ${EXECUTION_NAME} ended ${status}; check for clusters tagged project=scholarchain that are still running"
  fi
}

# runs_under ROOT_URI MARKER: prints a JSON array of the logical run paths
# ROOT/.../run=HEX that hold MARKER (for example _run_manifest.json).
runs_under() {
  local root="$1" marker="$2" key runs="[]" size
  while IFS=$'\t' read -r key size; do
    [ -n "${key}" ] || continue
    : "${size}"
    case "${key}" in
      */run=*/"${marker}")
        runs="$(jq -c --arg r "$(logical_s3 "s3://${DATA_BUCKET}/${key%/"${marker}"}")" '. + [$r] | unique' <<<"${runs}")"
        ;;
    esac
  done < <(list_keys "$(key_of "${root}")")
  printf '%s\n' "${runs}"
}

# ---- stage: upload-input -------------------------------------------------------------

stage_upload_input() {
  step "upload-input"
  [ -f "${A_FILE}" ] && [ ! -L "${A_FILE}" ] && [ -r "${A_FILE}" ] || die 4 "refusing: --file is not a readable regular file"
  local base bytes max key
  base="${A_FILE##*/}"
  [[ "${base}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$ ]] || die 4 "refusing: the file name must match [A-Za-z0-9][A-Za-z0-9._-]{0,199}"
  bytes="$(sc_bytes "${A_FILE}")"
  max=$((A_MAX_UPLOAD_MB * 1024 * 1024))
  [ "${bytes}" -le "${max}" ] || die 4 "refusing: ${base} has ${bytes} bytes, above --max-upload-mb ${A_MAX_UPLOAD_MB}"
  key="${P_RAW}/${A_SOURCE}/${SNAPSHOT}/${base}"
  add_json INPUTS_JSON file "$(jq -n --arg n "${base}" --argjson b "${bytes}" --arg s "$(sc_sha256 "${A_FILE}")" \
    --arg src "${A_SOURCE}" --argjson max "${A_MAX_UPLOAD_MB}" \
    '{name: $n, bytes: $b, sha256: $s, source: $src, max_upload_mb: $max}')"
  put_once "${A_FILE}" "${key}"
  add_str OUTPUTS_JSON input_location "$(logical_s3 "s3://${DATA_BUCKET}/${key}")"
  printf 'INPUT-LOCATION: %s\n' "$(logical_s3 "s3://${DATA_BUCKET}/${key}")"
}

# ---- stage: hadoop --------------------------------------------------------------------

stage_hadoop() {
  step "hadoop-inputs"
  local jar output dblp extra='[]' extra_logical='[]' a real_a logical_a dblp_sha input logical
  local def_jar="s3://${DATA_BUCKET}/${P_ARTIFACTS}/${BUILD_SHA}/jars/hadoop.jar"
  local def_output="s3://${DATA_BUCKET}/${P_CURATED}/hadoop"
  jar="$(resolve_location "${A_HADOOP_JAR:-${def_jar}}" --hadoop-jar "${P_ARTIFACTS}")" || exit "$?"
  output="$(resolve_location "${A_HADOOP_OUTPUT:-${def_output}}" --hadoop-output "${P_CURATED}")" || exit "$?"
  dblp="$(resolve_location "${A_DBLP}" --dblp "${P_RAW}")" || exit "$?"
  check_artifact_commit "${jar}" "the Hadoop jar" >/dev/null
  dblp_sha="$(head_meta "$(key_of "${dblp}")" scholarchain-sha256)" || die 5 "aws s3api head-object failed for --dblp"
  [ -n "${dblp_sha}" ] || die 4 "refusing: --dblp $(logical_s3 "${dblp}") does not exist (run --stage upload-input first)"
  for a in ${A_HADOOP_EXTRA[@]+"${A_HADOOP_EXTRA[@]}"}; do
    real_a="${a//"${LOGICAL_DATA_PREFIX}"/"s3://${DATA_BUCKET}/"}"
    case "${real_a}" in
      *"{"*"}"*) die 4 "refusing: --hadoop-extra-arg has an unknown placeholder" ;;
    esac
    if [[ "${real_a}" == *"s3://"* ]]; then
      local rest="${real_a#*s3://}"
      case "${rest}" in
        "${DATA_BUCKET}/${P_RAW}/"?* | "${DATA_BUCKET}/${P_CURATED}/"?*) ;;
        *) die 4 "refusing: an s3:// part of --hadoop-extra-arg is outside s3://{data-bucket}/${P_RAW}/ or ${P_CURATED}/" ;;
      esac
      case "${rest}" in
        *"s3://"* | *..*) die 4 "refusing: --hadoop-extra-arg may name one s3:// location without '..'" ;;
      esac
    fi
    logical_a="${real_a//"s3://${DATA_BUCKET}/"/"${LOGICAL_DATA_PREFIX}"}"
    extra="$(jq -c --arg a "${real_a}" '. + [$a]' <<<"${extra}")"
    extra_logical="$(jq -c --arg a "${logical_a}" '. + [$a]' <<<"${extra_logical}")"
  done

  input="${TMP_DIR}/input.json"
  jq -n --arg cv "${BUILD_SHA}" --arg jar "${jar}" --arg out "${output}" --arg dblp "${dblp}" \
    --arg snap "${SNAPSHOT}" --argjson extra "${extra}" \
    '{codeVersion: $cv, hadoop: ({jar: $jar, output: $out, dblp: $dblp, dblpSnapshot: $snap}
       + (if ($extra | length) > 0 then {extraArgs: $extra} else {} end))}' >"${input}"
  logical="$(jq -c --arg jar "$(logical_s3 "${jar}")" --arg out "$(logical_s3 "${output}")" \
    --arg dblp "$(logical_s3 "${dblp}")" --argjson extra "${extra_logical}" \
    '.hadoop.jar = $jar | .hadoop.output = $out | .hadoop.dblp = $dblp
     | if .hadoop.extraArgs then .hadoop.extraArgs = $extra else . end' "${input}")"
  add_str INPUTS_JSON dblp_sha256 "${dblp_sha}"
  # Phase 14 (R-1 note): the note names the driver options given with
  # --hadoop-extra-arg (for example --dblp-dtd), so it stays true.
  CONFIG_JSON="$(jq -n --argjson extra "${extra_logical}" '{file: null, sha256: null,
    note: (if ($extra | length) > 0
           then "the Hadoop jobs use their built-in defaults plus -D scholarchain.code.version and the driver options in extra_args (for example --dblp-dtd, which sets scholarchain.dblp.dtdPath)"
           else "the Hadoop jobs use their built-in defaults (-D scholarchain.code.version only)" end),
    extra_args: $extra}')"
  printf 'INPUT: %s\n' "${logical}"

  run_execution "${input}" "${logical}"

  step "hadoop-outputs"
  local runs
  runs="$(runs_under "${output}" "_job.json")"
  add_str OUTPUTS_JSON hadoop_output_root "$(logical_s3 "${output}")"
  add_json RUN_IDS_JSON hadoop_runs "${runs}"
  add_json RUN_IDS_JSON hadoop_j09_export_runs "$(jq -c '[.[] | select(test("/j09-graph-export/run=[0-9a-f]+$"))]' <<<"${runs}")"
  info "J09 export runs under the output root: $(jq -r '[.[] | select(test("/j09-graph-export/run="))] | join(", ")' <<<"${runs}")"
}

# ---- stage: er ------------------------------------------------------------------------

ER_JAR=""

check_er_jar() {
  step "er-jar"
  local rec="${PACKAGES_DIR}/${BUILD_SHA}/package-record.json" sha commit dirty
  [ -f "${rec}" ] || die 4 "refusing: ${rec} is missing (run scripts/package.sh for the build commit)"
  [ "$(jq -r '.build_commit // empty' "${rec}")" = "${BUILD_SHA}" ] || die 4 "refusing: ${rec} is for another build commit"
  ER_JAR="$(jq -r '.jars[]? | select(.module == "entityResolution") | .path' "${rec}")"
  sha="$(jq -r '.jars[]? | select(.module == "entityResolution") | .sha256' "${rec}")"
  commit="$(jq -r '.jars[]? | select(.module == "entityResolution") | .build_info.buildCommit' "${rec}")"
  dirty="$(jq -r '.jars[]? | select(.module == "entityResolution") | .build_info.buildDirty' "${rec}")"
  [ -n "${ER_JAR}" ] && [ -f "${ER_JAR}" ] || die 4 "refusing: the entity-resolution jar of ${rec} is missing (package again)"
  [ "$(sc_sha256 "${ER_JAR}")" = "${sha}" ] || die 4 "refusing: ${ER_JAR} changed since packaging (package again)"
  [ "${commit}" = "${BUILD_SHA}" ] && [ "${dirty}" = "false" ] ||
    die 4 "refusing: the entity-resolution jar's build identity is ${commit} dirty=${dirty}, not ${BUILD_SHA} clean"
  add_json INPUTS_JSON er_jar "$(jq -n --arg p "${ER_JAR}" --arg s "${sha}" --arg c "${commit}" \
    '{path: $p, sha256: $s, build_commit: $c, package_record: "evidence/packages/\($c)/package-record.json"}')"
  info "entity-resolution jar ${ER_JAR} (sha256 ${sha}, build ${commit})"
}

# verify_download DIR LISTING_COUNT LISTING_BYTES: checks the downloaded export
# against its _job.json outputs (path, length, sha256), else against the
# listing's file count and bytes.
verify_download() {
  local dir="$1" n_list="$2" bytes_list="$3" n_out i path len sha f got_len n_local
  if [ -f "${dir}/_job.json" ]; then
    jq empty "${dir}/_job.json" 2>/dev/null || die 6 "the export's _job.json is not valid JSON"
    n_out="$(jq '(.outputs // []) | length' "${dir}/_job.json")"
    [ "${n_out}" -gt 0 ] || die 6 "the export's _job.json lists no outputs"
    i=0
    while [ "${i}" -lt "${n_out}" ]; do
      path="$(jq -r --argjson i "${i}" '.outputs[$i].path' "${dir}/_job.json")"
      len="$(jq -r --argjson i "${i}" '.outputs[$i].length' "${dir}/_job.json")"
      sha="$(jq -r --argjson i "${i}" '.outputs[$i].sha256' "${dir}/_job.json")"
      case "${path}" in
        /* | *..*) die 6 "the export's _job.json names an unsafe path" ;;
      esac
      f="${dir}/${path}"
      [ -f "${f}" ] || die 6 "downloaded export lacks ${path} (listed in _job.json)"
      got_len="$(sc_bytes "${f}")"
      [ "${got_len}" = "${len%.*}" ] || die 6 "${path}: ${got_len} bytes, _job.json says ${len}"
      [ "$(sc_sha256 "${f}")" = "${sha}" ] || die 6 "${path}: sha256 differs from _job.json"
      i=$((i + 1))
    done
    # Hidden files (a path segment starting with "_" or ".") are not outputs
    # (JobRunner.outputManifest); paths are taken relative to the run folder.
    n_local="$(cd "${dir}" && find . -type f ! -path '*/_*' ! -path '*/.*' | wc -l | tr -d ' ')"
    [ "${n_local}" -eq "${n_out}" ] || die 6 "the export holds ${n_local} data files, _job.json lists ${n_out}"
    add_str OUTPUTS_JSON download_check "every file matches _job.json outputs (${n_out} files, length and sha256)"
  else
    n_local="$(find "${dir}" -type f | wc -l | tr -d ' ')"
    [ "${n_local}" -eq "${n_list}" ] || die 6 "downloaded ${n_local} files, the listing has ${n_list}"
    local total=0 b
    while IFS= read -r f; do
      b="$(sc_bytes "${f}")"
      total=$((total + b))
    done < <(find "${dir}" -type f)
    [ "${total}" -eq "${bytes_list}" ] || die 6 "downloaded ${total} bytes, the listing has ${bytes_list}"
    add_str OUTPUTS_JSON download_check "no _job.json: file count and bytes match the listing (${n_list} files)"
  fi
}

stage_er() {
  # check_er_jar ran in main, before any cloud call.
  step "er-export-listing"
  local export_uri export_key work input_dir out_root listing n=0 total=0 key size rel avail_kb need max
  export_uri="$(resolve_location "${A_HADOOP_EXPORT}" --hadoop-export "${P_CURATED}")" || exit "$?"
  [[ "${export_uri}" =~ /run=[0-9a-f]{8,64}$ ]] || die 4 "refusing: --hadoop-export must end in /run=HEX (a J09 export run)"
  export_key="$(key_of "${export_uri}")"
  add_str INPUTS_JSON hadoop_export "$(logical_s3 "${export_uri}")"
  listing="${TMP_DIR}/export-listing.tsv"
  list_keys "${export_key}" >"${listing}"
  while IFS=$'\t' read -r key size; do
    [ -n "${key}" ] || continue
    n=$((n + 1))
    total=$((total + size))
  done <"${listing}"
  [ "${n}" -gt 0 ] || die 4 "refusing: the export $(logical_s3 "${export_uri}") holds no object"
  max=$((A_MAX_DOWNLOAD_MB * 1024 * 1024))
  [ "${total}" -le "${max}" ] || die 4 "refusing: the export holds ${total} bytes, above --max-download-mb ${A_MAX_DOWNLOAD_MB}"
  local base_tmp="${TMPDIR:-/tmp}"
  base_tmp="${base_tmp%/}"
  avail_kb="$(df -Pk "${base_tmp}" | tail -n 1 | awk '{print $4}')"
  [[ "${avail_kb}" =~ ^[0-9]+$ ]] || die 5 "could not read the free disk space of ${base_tmp}"
  need=$((total * 3))
  [ $((avail_kb * 1024)) -ge "${need}" ] ||
    die 4 "refusing: ${base_tmp} has $((avail_kb * 1024)) bytes free, less than three times the export (${need})"
  add_json INPUTS_JSON hadoop_export_listing "$(jq -n --argjson n "${n}" --argjson b "${total}" '{files: $n, bytes: $b}')"
  info "export: ${n} objects, ${total} bytes; free disk $((avail_kb * 1024)) bytes"

  step "er-download"
  work="${base_tmp}/scholarchain-er-${ATTEMPT_ID}"
  [ ! -e "${work}" ] || die 4 "refusing: ${work} exists"
  input_dir="${work}/input/${export_uri##*/}"
  out_root="${work}/er-out"
  mkdir -p -- "${input_dir}"
  add_str OUTPUTS_JSON local_folder "${work}"
  printf 'LOCAL-FOLDER: %s (kept; remove it yourself when done)\n' "${work}"
  local rc
  while IFS=$'\t' read -r key size; do
    [ -n "${key}" ] || continue
    rel="${key#"${export_key}/"}"
    case "${rel}" in
      "" | /* | *..*) die 6 "unsafe object key under the export" ;;
    esac
    mkdir -p -- "$(dirname -- "${input_dir}/${rel}")"
    rc=0
    aws_read "${TRANSFER_TIMEOUT}" s3api get-object --bucket "${DATA_BUCKET}" --key "${key}" "${input_dir}/${rel}" >/dev/null || rc=$?
    [ "${rc}" -eq 0 ] || {
      print_aws_error
      [ "${rc}" -eq 7 ] && die 7 "download of an export object timed out"
      die 5 "download of an export object failed"
    }
  done <"${listing}"
  verify_download "${input_dir}" "${n}" "${total}"

  step "er-run"
  local cmd=() log="${work}/er.log" run_dir run_id
  cmd=(java "-Xmx${A_ER_HEAP_GB}g" -cp "${ER_JAR}" org.scholarchain.er.run.ErMain --input "${input_dir}" --output-root "${out_root}" --decision-time "${A_DECISION_TIME}" --seed "${A_SEED}" --code-version "${BUILD_SHA}" --llm-model-id "${A_LLM_MODEL_ID}" --llm-max-per-run 0 --adjudicator always-abstain) # contract: ErMain
  add_json INPUTS_JSON er_arguments "$(jq -n --arg d "${A_DECISION_TIME}" --arg s "${A_SEED}" --arg m "${A_LLM_MODEL_ID}" \
    --argjson h "${A_ER_HEAP_GB}" '{decision_time: $d, seed: $s, llm_model_id: $m, llm_max_per_run: 0,
      adjudicator: "always-abstain", heap_gb: $h}')"
  rc=0
  with_timeout "${ER_TIMEOUT}" "${cmd[@]}" </dev/null 2>&1 | tee "${log}" || rc="${PIPESTATUS[0]}"
  case "${rc}" in
    0) ;;
    124 | 137) die 7 "ErMain did not finish within ${ER_TIMEOUT}s" ;;
    *) die 6 "ErMain failed (status ${rc}); see ${log}" ;;
  esac
  run_id="$(grep -E '^\[er\] runId=' "${log}" | tail -n 1 | cut -d= -f2-)" || run_id=""
  run_dir="$(grep -E '^\[er\] runDir=' "${log}" | tail -n 1 | cut -d= -f2-)" || run_dir=""
  [ -n "${run_id}" ] && [ -n "${run_dir}" ] || die 6 "ErMain printed no runId or runDir"
  case "${run_dir}" in
    "${out_root}"/run=*) ;;
    *) die 6 "ErMain's runDir is not under the new output root" ;;
  esac
  [ -f "${run_dir}/_SUCCESS" ] && [ -f "${run_dir}/_manifest.json" ] || die 6 "the ER run lacks _SUCCESS or _manifest.json"
  [ -d "${run_dir}/decisions/identity_assignment" ] || die 6 "the ER run lacks decisions/identity_assignment"
  add_str RUN_IDS_JSON er_run_id "${run_id}"

  step "er-upload"
  local dest="${P_CURATED}/er/${ATTEMPT_ID}" f relf
  while IFS= read -r f; do
    relf="${f#"${out_root}/"}"
    case "${relf##*/}" in
      _SUCCESS | _manifest.json) continue ;;
    esac
    put_once "${f}" "${dest}/${relf}"
  done < <(find "${out_root}" -type f | LC_ALL=C sort)
  put_once "${run_dir}/_manifest.json" "${dest}/${run_dir#"${out_root}/"}/_manifest.json"
  put_once "${run_dir}/_SUCCESS" "${dest}/${run_dir#"${out_root}/"}/_SUCCESS"
  add_str OUTPUTS_JSON er_output_root "$(logical_s3 "s3://${DATA_BUCKET}/${dest}")"
  add_str OUTPUTS_JSON identity_assignments \
    "$(logical_s3 "s3://${DATA_BUCKET}/${dest}/${run_dir#"${out_root}/"}/decisions/identity_assignment")"
  add_str RUN_IDS_JSON er_output_root "$(logical_s3 "s3://${DATA_BUCKET}/${dest}")"
  CONFIG_JSON="$(jq -n '{file: null, sha256: null, note: "ER policy and thresholds are the code defaults of the jar (Policy.current); no threshold override"}')"
  printf 'IDENTITY-ASSIGNMENTS: %s\n' "$(logical_s3 "s3://${DATA_BUCKET}/${dest}/${run_dir#"${out_root}/"}/decisions/identity_assignment")"
}

# ---- stage: spark ---------------------------------------------------------------------

# require_er_succeeded LOGICAL: refuses unless a final ER run record under
# evidence/runs/ says "succeeded" for exactly these identity assignments.
require_er_succeeded() {
  local logical="$1" f found=""
  for f in "${RECORD_DIR}"/*-er.json; do
    [ -f "${f}" ] || continue
    if jq -e --arg l "${logical}" '.kind == "scholarchain-pipeline-run-record" and .stage == "er"
        and .status == "succeeded" and .outputs.identity_assignments == $l' "${f}" >/dev/null 2>&1; then
      found="${f}"
    fi
  done
  [ -n "${found}" ] || die 4 "refusing: no succeeded ER run record under ${RECORD_DIR}/ names --identity-assignments ${logical}"
  add_str INPUTS_JSON er_run_record "${found}"
  info "ER run record ${found} says succeeded"
}

stage_spark() {
  step "spark-inputs"
  local jar conf export_uri ids out_root src="" eroot="" conf_sha input logical
  local def_jar="s3://${DATA_BUCKET}/${P_ARTIFACTS}/${BUILD_SHA}/jars/spark-analytics.jar"
  local def_conf="s3://${DATA_BUCKET}/${P_ARTIFACTS}/${BUILD_SHA}/spark-conf/${SPARK_CONF_DEFAULT_NAME}"
  jar="$(resolve_location "${A_SPARK_JAR:-${def_jar}}" --spark-jar "${P_ARTIFACTS}")" || exit "$?"
  conf="$(resolve_location "${A_SPARK_CONFIG:-${def_conf}}" --spark-config "${P_ARTIFACTS}")" || exit "$?"
  export_uri="$(resolve_location "${A_HADOOP_EXPORT}" --hadoop-export "${P_CURATED}")" || exit "$?"
  ids="$(resolve_location "${A_IDENTITY}" --identity-assignments "${P_CURATED}")" || exit "$?"
  out_root="$(resolve_location "${A_OUT_ROOT}" --out-root "${P_GRAPH}")" || exit "$?"
  [[ "${ids}" =~ /run=[0-9a-f]+/decisions/identity_assignment$ ]] ||
    die 4 "refusing: --identity-assignments must end in /run=HEX/decisions/identity_assignment"
  require_er_succeeded "$(logical_s3 "${ids}")"
  check_artifact_commit "${jar}" "the Spark jar" >/dev/null
  # I-24: a refusal (die 4) inside the substitution keeps its own exit code.
  conf_sha="$(check_artifact_commit "${conf}" "the Spark job config")" || exit "$?"
  CONFIG_JSON="$(jq -n --arg f "$(logical_s3 "${conf}")" --arg s "${conf_sha}" '{file: $f, sha256: $s}')"
  if is_given export-source-run; then
    src="$(resolve_location "${A_EXPORT_SOURCE_RUN}" --export-source-run "${P_GRAPH}")" || exit "$?"
    [[ "${src}" =~ /run=[0-9a-f]{32}$ ]] || die 4 "refusing: --export-source-run must end in /run=<32 hex>"
    eroot="$(resolve_location "${A_EXPORT_ROOT}" --export-root "${P_GRAPH}")" || exit "$?"
    eroot="${eroot}/${ATTEMPT_ID}"
  fi

  input="${TMP_DIR}/input.json"
  jq -n --arg cv "${BUILD_SHA}" --arg jar "${jar}" --arg cfg "${conf}" --arg exp "${export_uri}" \
    --arg ids "${ids}" --arg out "${out_root}" --arg src "${src}" --arg eroot "${eroot}" \
    '{codeVersion: $cv, spark: ({jar: $jar, config: $cfg, hadoopExport: $exp,
       identityAssignments: $ids, outRoot: $out}
       + (if $eroot != "" then {exportSourceRun: $src, exportRoot: $eroot} else {} end))}' >"${input}"
  logical="$(jq -c --arg jar "$(logical_s3 "${jar}")" --arg cfg "$(logical_s3 "${conf}")" \
    --arg exp "$(logical_s3 "${export_uri}")" --arg ids "$(logical_s3 "${ids}")" \
    --arg out "$(logical_s3 "${out_root}")" --arg src "$(logical_s3 "${src}")" --arg eroot "$(logical_s3 "${eroot}")" \
    '.spark.jar = $jar | .spark.config = $cfg | .spark.hadoopExport = $exp
     | .spark.identityAssignments = $ids | .spark.outRoot = $out
     | if .spark.exportRoot then (.spark.exportSourceRun = $src | .spark.exportRoot = $eroot) else . end' "${input}")"
  printf 'INPUT: %s\n' "${logical}"

  run_execution "${input}" "${logical}"

  step "spark-outputs"
  add_str OUTPUTS_JSON graph_out_root "$(logical_s3 "${out_root}")"
  add_json RUN_IDS_JSON graph_runs_under_out_root "$(runs_under "${out_root}" "_run_manifest.json")"
  if [ -n "${eroot}" ]; then
    add_str OUTPUTS_JSON export_root "$(logical_s3 "${eroot}")"
    add_str RUN_IDS_JSON export_source_run "$(logical_s3 "${src}")"
    add_json RUN_IDS_JSON export_runs "$(runs_under "${eroot}" "_export_manifest.json")"
    info "export runs: $(jq -r '.export_runs | join(", ")' <<<"${RUN_IDS_JSON}")"
  fi
  info "graph runs under the out-root: $(jq -r '.graph_runs_under_out_root | join(", ")' <<<"${RUN_IDS_JSON}")"
}

# ---- stage: load ----------------------------------------------------------------------

CLUSTER=""
OPS_FAMILY=""
OPS_TD_REF=""
OPS_NAME=""
TASK_ARN=""
LOADER_ROLE_ARN=""

check_ops_task() {
  step "ops-task-definition"
  local repo td image
  CLUSTER="$(out ecs_cluster_name)"
  OPS_FAMILY="$(out ops_task_definition_family)"
  OPS_NAME="$(jq -r '.ops_container.value.name // empty' <<<"${OUTPUTS}")"
  repo="$(out ecr_repository_url)"
  [ -n "${CLUSTER}" ] && [ -n "${OPS_FAMILY}" ] && [ -n "${OPS_NAME}" ] && [ -n "${repo}" ] ||
    die 3 "terraform output lacks ecs_cluster_name, ops_task_definition_family, ops_container or ecr_repository_url"
  td="$(aws_read "${AWS_TIMEOUT}" ecs describe-task-definition --task-definition "${OPS_FAMILY}")" || {
    print_aws_error
    die 5 "aws ecs describe-task-definition failed"
  }
  OPS_TD_REF="$(jq -r '"\(.taskDefinition.family):\(.taskDefinition.revision)"' <<<"${td}")"
  image="$(jq -r --arg n "${OPS_NAME}" '.taskDefinition.containerDefinitions[]? | select(.name == $n) | .image' <<<"${td}")"
  [ "${image}" = "${repo}:${BUILD_SHA}" ] ||
    die 4 "refusing: the ops task definition ${OPS_TD_REF} runs image tag '${image##*:}', not the build commit ${BUILD_SHA} (apply a plan with --image-tag ${BUILD_SHA})"
  add_str INPUTS_JSON ops_task_definition "${OPS_TD_REF}"
  add_str INPUTS_JSON image_tag "${BUILD_SHA}"
  info "ops task definition ${OPS_TD_REF}, image tag = build commit"

  step "one-ops-task"
  local running
  running="$(aws_read "${AWS_TIMEOUT}" ecs list-tasks --cluster "${CLUSTER}" --family "${OPS_FAMILY}" --desired-status RUNNING)" || {
    print_aws_error
    die 5 "aws ecs list-tasks failed"
  }
  [ "$(jq '[.taskArns[]?] | length' <<<"${running}")" -eq 0 ] ||
    die 4 "refusing: an ops task of ${OPS_FAMILY} is already running (one loader at a time, Phase 7 m3)"
}

# The loader command, from the option constants (StorageLoadMain.Options,
# RagIngestJob.Required/Optional). Real locations go to the task only.
OPS_CMD=()

build_ops_command() {
  step "ops-command"
  local export_real="" graph_real="" out="/tmp/scholarchain-${A_LOADER}-${ATTEMPT_ID}"
  if [ -n "${A_EXPORT}" ]; then
    export_real="$(resolve_location "${A_EXPORT}" --export "${P_GRAPH}")" || exit "$?"
    [[ "${export_real}" =~ /run=[0-9a-f]{32}$ ]] || die 4 "refusing: --export must end in /run=<32 hex> (a JSON Lines export run)"
    add_str INPUTS_JSON export "$(logical_s3 "${export_real}")"
  fi
  if [ -n "${A_GRAPH_RUN}" ]; then
    graph_real="$(resolve_location "${A_GRAPH_RUN}" --graph-run "${P_GRAPH}")" || exit "$?"
    add_str INPUTS_JSON graph_run "$(logical_s3 "${graph_real}")"
  fi
  case "${A_LOADER}" in
    dynamodb-load)
      OPS_CMD=(org.scholarchain.storage.load.StorageLoadMain dynamodb-load --config "${OPS_CONFIG}" --stores aws --export "${export_real}" --out "${out}" --code-version "${BUILD_SHA}") # contract: StorageLoadMain dynamodb-load
      ;;
    neptune-csv-build)
      OPS_CMD=(org.scholarchain.storage.load.StorageLoadMain neptune-csv-build --config "${OPS_CONFIG}" --stores aws --export "${export_real}" --out "${out}" --code-version "${BUILD_SHA}" --dataset-id "${A_DATASET_ID}" --snapshot-id "${SNAPSHOT}") # contract: StorageLoadMain neptune-csv-build
      ;;
    neptune-bulk-start)
      LOADER_ROLE_ARN="$(out neptune_loader_role_arn)"
      [ -n "${LOADER_ROLE_ARN}" ] || die 3 "terraform output lacks neptune_loader_role_arn"
      OPS_CMD=(org.scholarchain.storage.load.StorageLoadMain neptune-bulk-start --config "${OPS_CONFIG}" --stores aws --graph-run "${graph_real}" --loader-role-arn "${LOADER_ROLE_ARN}" --out "${out}" --code-version "${BUILD_SHA}") # contract: StorageLoadMain neptune-bulk-start
      ;;
    neptune-bulk-status)
      OPS_CMD=(org.scholarchain.storage.load.StorageLoadMain neptune-bulk-status --config "${OPS_CONFIG}" --stores aws --load-id "${A_LOAD_ID}" --out "${out}" --code-version "${BUILD_SHA}") # contract: StorageLoadMain neptune-bulk-status
      if [ -n "${A_WAIT_SECONDS}" ]; then
        OPS_CMD+=(--wait-seconds "${A_WAIT_SECONDS}") # contract: StorageLoadMain neptune-bulk-status
      fi
      ;;
    rag-ingest)
      OPS_CMD=(org.scholarchain.rag.ingest.RagIngestJob --config "${OPS_CONFIG}" --export "${export_real}" --out "${out}" --code-version "${BUILD_SHA}" --stores aws) # contract: RagIngestJob
      ;;
  esac
  # The recorded command: logical locations; the loader role ARN (it holds
  # the account ID) is replaced by the name of its Terraform output.
  local a logical_cmd='[]' shown
  for a in "${OPS_CMD[@]}"; do
    if [ -n "${LOADER_ROLE_ARN}" ] && [ "${a}" = "${LOADER_ROLE_ARN}" ]; then
      shown="{terraform output neptune_loader_role_arn}"
    else
      shown="$(logical_s3 "${a}")"
    fi
    logical_cmd="$(jq -c --arg a "${shown}" '. + [$a]' <<<"${logical_cmd}")"
  done
  add_json INPUTS_JSON ops_command "${logical_cmd}"
  CONFIG_JSON="$(jq -n --arg f "${OPS_CONFIG}" --arg s "$(sc_sha256 config/course.json)" \
    '{file: $f, sha256: $s, source: "config/course.json at the build commit, baked into the image"}')"
  printf 'OPS-COMMAND: %s\n' "$(jq -r 'join(" ")' <<<"${logical_cmd}")"
}

stage_load() {
  check_ops_task
  build_ops_command

  step "run-task"
  local subnets sg overrides network started failures
  subnets="$(jq -c '.app_subnet_ids.value // []' <<<"${OUTPUTS}")"
  sg="$(out ops_security_group_id)"
  [ "$(jq 'length' <<<"${subnets}")" -gt 0 ] && [ -n "${sg}" ] ||
    die 3 "terraform output lacks app_subnet_ids or ops_security_group_id"
  overrides="${TMP_DIR}/overrides.json"
  network="${TMP_DIR}/network.json"
  jq -n --arg n "${OPS_NAME}" '{containerOverrides: [{name: $n, command: $ARGS.positional}]}' \
    --args "${OPS_CMD[@]}" >"${overrides}"
  jq -n --argjson s "${subnets}" --arg g "${sg}" \
    '{awsvpcConfiguration: {subnets: $s, securityGroups: [$g], assignPublicIp: "DISABLED"}}' >"${network}"
  started="$(aws_once "${AWS_TIMEOUT}" ecs run-task --cluster "${CLUSTER}" --task-definition "${OPS_TD_REF}" \
    --launch-type FARGATE --count 1 --network-configuration "file://${network}" \
    --overrides "file://${overrides}" --started-by "${STARTED_BY}" --propagate-tags TASK_DEFINITION)" ||
    die "$?" "aws ecs run-task failed (not retried)"
  failures="$(jq -c '[.failures[]? | {reason, detail}]' <<<"${started}")"
  [ "${failures}" = "[]" ] || die 5 "run-task failures: $(mask_text "${failures}")"
  TASK_ARN="$(jq -r '.tasks[0].taskArn // empty' <<<"${started}")"
  [ -n "${TASK_ARN}" ] || die 5 "run-task returned no task"
  local task_id stream group prefix
  task_id="${TASK_ARN##*/}"
  group="$(jq -r '.log_group_names.value.ops // empty' <<<"${OUTPUTS}")"
  prefix="$(jq -r '.ops_container.value.log_stream_prefix // empty' <<<"${OUTPUTS}")"
  stream="${prefix}/${OPS_NAME}/${task_id}"
  add_str RUN_IDS_JSON ops_task_id "${task_id}"
  add_json OUTPUTS_JSON log "$(jq -n --arg g "${group}" --arg s "${stream}" '{log_group: $g, log_stream: $s}')"
  printf 'OPS-TASK: id=%s log_group=%s log_stream=%s\n' "${task_id}" "${group}" "${stream}"

  step "wait-task"
  local deadline now desc last
  deadline=$(($(date +%s) + WAIT_TIMEOUT))
  while :; do
    desc="$(aws_read "${AWS_TIMEOUT}" ecs describe-tasks --cluster "${CLUSTER}" --tasks "${TASK_ARN}")" || {
      print_aws_error
      die 5 "aws ecs describe-tasks failed (the task may still run: ${task_id})"
    }
    last="$(jq -r '.tasks[0].lastStatus // "UNKNOWN"' <<<"${desc}")"
    now="$(date +%s)"
    printf 'POLL: task=%s lastStatus=%s elapsed_wait=%ss\n' "${task_id}" "${last}" "$((WAIT_TIMEOUT - (deadline - now)))"
    [ "${last}" != "STOPPED" ] || break
    if [ "${now}" -ge "${deadline}" ]; then
      die 7 "waited ${WAIT_TIMEOUT}s; ops task ${task_id} is STILL RUNNING (not stopped by this script)"
    fi
    sleep "${LOAD_POLL_SECONDS}"
  done

  step "task-result"
  local code stop_code reason
  code="$(jq -r --arg n "${OPS_NAME}" '.tasks[0].containers[]? | select(.name == $n) | .exitCode // empty' <<<"${desc}")"
  stop_code="$(jq -r '.tasks[0].stopCode // "n/a"' <<<"${desc}")"
  reason="$(mask_text "$(jq -r '.tasks[0].stoppedReason // "n/a"' <<<"${desc}")")"
  add_json OUTPUTS_JSON task "$(jq -n --arg c "${code}" --arg sc "${stop_code}" --arg r "${reason}" \
    '{container_exit_code: (if $c == "" then null else ($c | tonumber) end), stop_code: $sc, stopped_reason: $r}')"
  collect_program_lines "${group}" "${stream}"
  printf 'TASK-RESULT: exit_code=%s stop_code=%s reason=%s\n' "${code:-none}" "${stop_code}" "${reason}"
  [ "${code}" = "0" ] || die 6 "the ${A_LOADER} task ended with container exit code ${code:-none} (see the log stream)"
}

# collect_program_lines GROUP STREAM: the program's own summary lines
# ([storage-load] and [rag-ingest], already masked by the programs and masked
# again here) from the last 200 log events; best effort.
collect_program_lines() {
  local group="$1" stream="$2" ev lines
  [ -n "${group}" ] || return 0
  ev="$(aws_read "${AWS_TIMEOUT}" logs get-log-events --log-group-name "${group}" --log-stream-name "${stream}" \
    --limit 200 --no-start-from-head --no-paginate)" || {
    warn "could not read the task's log events (see the log stream yourself)"
    return 0
  }
  lines="$(jq -r '.events[]?.message' <<<"${ev}" | grep -E '^\[(storage-load|rag-ingest)\]' | tail -n 20 | cut -c 1-2000 || true)"
  if [ -n "${lines}" ]; then
    lines="$(mask_text "${lines}")"
    printf '%s\n' "${lines}"
    add_json OUTPUTS_JSON program_lines "$(jq -R -s -c 'split("\n") | map(select(length > 0))' <<<"${lines}")"
  fi
}

main() {
  parse_args "$@"
  check_args
  enter_repo_root
  check_prerequisites
  check_clean_tree
  if [ "${STAGE}" = "er" ]; then
    check_er_jar
  fi
  read_outputs
  start_record
  case "${STAGE}" in
    upload-input) stage_upload_input ;;
    hadoop) stage_hadoop ;;
    er) stage_er ;;
    spark) stage_spark ;;
    load) stage_load ;;
  esac
  step "done"
}

main "$@"
