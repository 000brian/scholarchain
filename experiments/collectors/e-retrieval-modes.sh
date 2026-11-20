#!/usr/bin/env bash
# experiments/collectors/e-retrieval-modes.sh - experiment E (retrieval modes):
# runs the rag-retrieval-eval ops task through scripts/run-bounded-pipeline.sh
# with its arguments unchanged, then copies rag's report from S3 by its
# logical key into the run folder and extracts the per-mode table (Phase 15
# work order, decision 11, step 6c).
# Owner: experiment-engineer. A "command" action of scripts/run-experiment.sh;
# run by the human only, never by an agent.
#
# runtime: untested
# static: shellcheck (6c report); bash 3.2 constructs only.

set -Eeuo pipefail
umask 077

readonly TOOL_NAME="e-retrieval-modes.sh"
readonly TOOL_PATH="experiments/collectors/e-retrieval-modes.sh"
COLLECTOR_DIR="$(cd "$(dirname "$0")" && pwd -P)"
# shellcheck source=experiments/collectors/lib/collector-common.sh
. "${COLLECTOR_DIR}/lib/collector-common.sh"

readonly PIPELINE="scripts/run-bounded-pipeline.sh"
readonly RECORD_DIR="evidence/runs"
readonly LOGICAL_PREFIX="s3://{data-bucket}/"
readonly EVAL_LOADER="rag-retrieval-eval"
readonly REPORT_FORMAT="scholarchain.rag.retrieval-eval-report"
readonly REPORT_FILE="retrieval-eval-report.json"
readonly MODES_FILE="retrieval-modes.csv"
# RetrievalEvalJob.DefaultMaxSeconds (modules/rag/.../eval/RetrievalEvalJob.scala:54)
readonly JOB_DEFAULT_MAX_SECONDS=1200
# run-bounded-pipeline.sh refuses --wait-timeout below the job limit + 300 s
# (infra 5b handoff); this collector also asks for the known overrun: the
# total limit is checked only when a question starts, so a run can end about
# 12 minutes after --max-seconds (review finding H-02, RetrievalEval.scala:162-170).
readonly PIPELINE_WAIT_MARGIN=300
readonly EVAL_OVERRUN_ALLOWANCE=720
# The pipeline's own steps before run-task and after the task ends (Terraform
# outputs, image and one-task checks, log read, final record): the outer
# GNU timeout is --wait-timeout + this.
readonly PIPELINE_EXTRA_SECONDS=600
readonly CHILD_GRACE_SECONDS=25
readonly MAX_REPORT_BYTES=16777216
readonly MODE_NAMES="graph-only vector-only fused"

FWD=()
STAGE=""
LOADER=""
GRAPH_RUN_ID=""
MAX_SECONDS=""
QUESTION_TIMEOUT=""
WAIT_TIMEOUT=""
MODES=""
CHILD_PID=""
DATA_BUCKET=""
NESTED=""
NESTED_STARTED=""
STARTED_AT=""
PIPELINE_RC=0
REPORT_OK="false"

usage() {
  cat <<'EOF'
Usage: experiments/collectors/e-retrieval-modes.sh PIPELINE-ARGUMENTS... [--preflight]

Experiment E (Phase 15 decisions 7.4 and 11, step 6c). Run from the
repository root by scripts/run-experiment.sh. Every argument except a
trailing --preflight goes UNCHANGED to bash scripts/run-bounded-pipeline.sh
(its usage text is authoritative). Only this form is accepted:

  --stage load --snapshot ID --loader rag-retrieval-eval
  --graph-run-id run:v1:HEX32 [--modes LIST] [--max-seconds N]
  [--question-timeout-seconds N] --wait-timeout SECONDS

--wait-timeout is required here and must be at least (max-seconds, or the
job default 1200) + 300 (the pipeline's rule) + 720 (the known overrun of the
job's total limit, review finding H-02). The pipeline's output is not wrapped,
so its "RUN-RECORD:" and "EVAL-REPORT:" lines reach the runner as they are.

After the pipeline ends (whatever its exit code), from its final run record
under evidence/runs/ (the newest load record of this loader written after
the start), the report location outputs.retrieval_eval_report.location
(logical s3://{data-bucket}/rag/retrieval-eval/ATTEMPT/report.json) is
resolved with the bucket from terraform output data_bucket_name (never
printed) and copied into the run folder:

  retrieval-eval-report.json  rag's report, byte for byte (masked by the job:
                              no bucket name, no account ID)
  retrieval-modes.csv         one row per mode and K, extracted with jq:
                              mode,k,recall,mrr,ndcg,questions_scored,errors,
                              empty,abstentions,by_design_weak,latency_p50_ms,
                              latency_p95_ms,universe_size,not_comparable,
                              label,recall_ceiling,mode_questions,report_status
                              (mrr is per mode, repeated on each K row; ndcg is
                              empty: benchmark-v1 has no graded labels; a
                              missing value is an empty cell, never 0)
  clock.csv, tool-record.json

--preflight: read calls only: the arguments, the Terraform outputs, the
report prefix from config/course.json, the ops task definition's image tag
against the build commit, the number of running ops tasks, read access to the
report prefix; prints states only; starts nothing; writes nothing.

Exit codes: 0 the pipeline succeeded and the report was copied and
extracted; the pipeline's own non-zero exit otherwise (2, 3, 4, 5, 6, 7; the
report is still copied when it exists); 2 usage; 3 an ops task is already
running (nothing started); 5 the report could not be copied or read after a
successful pipeline (a tool failure; the report stays in S3); 7 the outer
time limit (the ops task may still run). Preflight: 0, 2 usage, 3 a
prerequisite not met, 5 a read failed.
EOF
}

parse_args() {
  local args=("$@") i=0 n="$#" a v
  while [ "${i}" -lt "${n}" ]; do
    a="${args[i]}"
    if [ "${a}" = "--preflight" ]; then
      i=$((i + 1))
      continue
    fi
    if [ "${a}" = "--help" ] || [ "${a}" = "-h" ]; then
      usage
      exit 0
    fi
    FWD+=("${a}")
    v=""
    if [ $((i + 1)) -lt "${n}" ]; then
      v="${args[i + 1]}"
    fi
    case "${a}" in
      --stage) STAGE="${v}" ;;
      --loader) LOADER="${v}" ;;
      --graph-run-id) GRAPH_RUN_ID="${v}" ;;
      --max-seconds) MAX_SECONDS="${v}" ;;
      --question-timeout-seconds) QUESTION_TIMEOUT="${v}" ;;
      --wait-timeout) WAIT_TIMEOUT="${v}" ;;
      --modes) MODES="${v}" ;;
    esac
    i=$((i + 1))
  done
  [ "${STAGE}" = "load" ] || cc_die 2 "--stage must be load"
  [ "${LOADER}" = "${EVAL_LOADER}" ] || cc_die 2 "--loader must be ${EVAL_LOADER}"
  local re_run='^run:v1:[0-9a-f]{32}$'
  [[ "${GRAPH_RUN_ID}" =~ ${re_run} ]] || cc_die 2 "--graph-run-id must be run:v1:<32 lowercase hex>"
  if [ -n "${MAX_SECONDS}" ]; then
    cc_is_uint "${MAX_SECONDS}" || cc_die 2 "--max-seconds must be a number"
  fi
  if [ -n "${QUESTION_TIMEOUT}" ]; then
    cc_is_uint "${QUESTION_TIMEOUT}" || cc_die 2 "--question-timeout-seconds must be a number"
  fi
  [ -n "${WAIT_TIMEOUT}" ] || cc_die 2 "--wait-timeout is required for E (the manifest states it)"
  cc_is_uint "${WAIT_TIMEOUT}" || cc_die 2 "--wait-timeout must be a number"
  local limit need
  limit="${MAX_SECONDS:-${JOB_DEFAULT_MAX_SECONDS}}"
  need=$((limit + PIPELINE_WAIT_MARGIN + EVAL_OVERRUN_ALLOWANCE))
  [ "${WAIT_TIMEOUT}" -ge "${need}" ] ||
    cc_die 2 "--wait-timeout ${WAIT_TIMEOUT} is below ${need} (max-seconds ${limit} + ${PIPELINE_WAIT_MARGIN} + overrun allowance ${EVAL_OVERRUN_ALLOWANCE}, H-02)"
}

# ---- Terraform names (kept in variables, never printed) --------------------------------------

CLUSTER=""
OPS_FAMILY=""
OPS_NAME=""
ECR_REPO=""
REPORT_PREFIX=""

read_names() {
  cc_tf_required DATA_BUCKET '.data_bucket_name.value' "data_bucket_name"
  cc_tf_required CLUSTER '.ecs_cluster_name.value' "ecs_cluster_name"
  cc_tf_required OPS_FAMILY '.ops_task_definition_family.value' "ops_task_definition_family"
  cc_tf_required OPS_NAME '.ops_container.value.name' "ops_container.name"
  cc_tf_required ECR_REPO '.ecr_repository_url.value' "ecr_repository_url"
  local p re_p='^[a-z0-9][a-z0-9-]{0,62}$'
  p="$(jq -r '.objectStore.semanticContextPrefix // empty' config/course.json 2>/dev/null || true)"
  [[ "${p}" =~ ${re_p} ]] || cc_die 3 "config/course.json objectStore.semanticContextPrefix is missing or unexpected"
  REPORT_PREFIX="${p}/retrieval-eval/"
}

# running_ops_tasks: prints the number of RUNNING ops tasks, or "x" when unreadable.
running_ops_tasks() {
  local out="${CC_TMP}/ops-tasks.json"
  # runs inside $(...): an error code is shown as "x" by the caller, not recorded here
  if ! cc_aws_read "${out}" ecs list-tasks --cluster "${CLUSTER}" --family "${OPS_FAMILY}" --desired-status RUNNING; then
    printf 'x\n'
    return 0
  fi
  jq -r 'if (.taskArns | type) == "array" then (.taskArns | length) else "x" end' "${out}" 2>/dev/null || printf 'x\n'
}

# ---- preflight --------------------------------------------------------------------------------------

preflight() {
  cc_step "preflight"
  local bad=0 build out image n
  build="$(git log -1 --format=%H -- build.sbt project modules prompts docker config .scalafmt.conf .jvmopts .sbtopts)"
  [ -n "${build}" ] || cc_die 3 "cannot determine the build commit"
  printf 'PREFLIGHT: arguments ok: loader %s, graph run %s, modes %s, max-seconds %s, question-timeout %s, wait-timeout %ss, outer limit %ss\n' \
    "${LOADER}" "${GRAPH_RUN_ID}" "${MODES:-default}" "${MAX_SECONDS:-default}" "${QUESTION_TIMEOUT:-default}" \
    "${WAIT_TIMEOUT}" "$((WAIT_TIMEOUT + PIPELINE_EXTRA_SECONDS))"
  printf 'PREFLIGHT: report folder (logical): %s%s<pipeline attempt>/report.json\n' "${LOGICAL_PREFIX}" "${REPORT_PREFIX}"
  out="${CC_TMP}/pf-td.json"
  if cc_aws_read "${out}" ecs describe-task-definition --task-definition "${OPS_FAMILY}"; then
    image="$(jq -r --arg n "${OPS_NAME}" '[.taskDefinition.containerDefinitions[]? | select(.name == $n) | .image] | .[0] // empty' "${out}")"
    if [ -n "${image}" ] && [ "${image}" = "${ECR_REPO}:${build}" ]; then
      printf 'PREFLIGHT: ops task definition image tag = build commit: yes\n'
    else
      printf 'PREFLIGHT: ops task definition image tag = build commit: NO (the pipeline would refuse with exit 4)\n'
      bad=$((bad + 1))
    fi
  else
    printf 'PREFLIGHT: ops task definition: unreadable (%s)\n' "${CC_LAST_AWS_ERROR}"
    bad=$((bad + 1))
  fi
  image=""
  n="$(running_ops_tasks)"
  if [ "${n}" = "0" ]; then
    printf 'PREFLIGHT: running ops tasks: 0 (ok)\n'
  else
    printf 'PREFLIGHT: running ops tasks: %s (one loader at a time: E would not start)\n' "${n}"
    bad=$((bad + 1))
  fi
  out="${CC_TMP}/pf-list.json"
  if cc_aws_read "${out}" s3api list-objects-v2 --bucket "${DATA_BUCKET}" --prefix "${REPORT_PREFIX}" --max-keys 1; then
    printf 'PREFLIGHT: report folder readable: yes (earlier objects: %s)\n' "$(jq -r '.KeyCount // "?"' "${out}")"
  else
    printf 'PREFLIGHT: report folder readable: NO (%s)\n' "${CC_LAST_AWS_ERROR}"
    bad=$((bad + 1))
  fi
  if [ "${bad}" -gt 0 ]; then
    cc_die 3 "${bad} preflight check(s) failed (listed above); nothing started"
  fi
  printf 'PREFLIGHT: ok (nothing started, nothing written)\n'
}

# ---- the run ----------------------------------------------------------------------------------------

cc_signal_hook() {
  cc_wait_child "${CHILD_PID}" "${CHILD_GRACE_SECONDS}"
}

run_pipeline() {
  cc_step "pipeline"
  local rc=0 limit
  limit=$((WAIT_TIMEOUT + PIPELINE_EXTRA_SECONDS))
  : >"${CC_TMP}/start-marker"
  STARTED_AT="$(cc_now)"
  # stdout and stderr are the runner's (not wrapped): RUN-RECORD lines intact.
  # --foreground keeps the pipeline in this process group, so the runner's TERM
  # reaches it; on the outer limit GNU timeout sends it TERM (its trap writes a
  # timed-out record), then KILL after 30 s.
  "${CC_TIMEOUT_BIN}" --foreground --kill-after=30 "${limit}" bash "${PIPELINE}" "${FWD[@]}" </dev/null &
  CHILD_PID="$!"
  wait "${CHILD_PID}" || rc=$?
  CHILD_PID=""
  if [ "${rc}" -eq 124 ] || [ "${rc}" -eq 137 ]; then
    cc_error "pipeline-outer-limit-${limit}s"
    cc_warn "the pipeline passed the outer limit of ${limit}s; the ops task may still run (check before any other ops loader)"
    rc=7
  fi
  PIPELINE_RC="${rc}"
  cc_info "the pipeline ended with exit ${rc}"
  if [ "${rc}" -eq 7 ]; then
    printf 'MAY-STILL-RUN: the rag-retrieval-eval ops task (see the nested record run_ids.ops_task_id)\n'
  fi
}

# find_record: the newest final load record of this loader written after the
# start (the pipeline allows one ops task at a time).
find_record() {
  cc_step "nested-record"
  local f
  for f in "${RECORD_DIR}"/*-load.json; do
    [ -f "${f}" ] || continue
    [ "${f}" -nt "${CC_TMP}/start-marker" ] || continue
    if jq -e --arg l "${EVAL_LOADER}" '.kind == "scholarchain-pipeline-run-record" and .stage == "load"
        and .inputs.loader == $l' "${f}" >/dev/null 2>&1; then
      if [ -z "${NESTED}" ] || [ "${f}" -nt "${NESTED}" ]; then
        NESTED="${f}"
      fi
    fi
  done
  if [ -z "${NESTED}" ]; then
    for f in "${RECORD_DIR}"/*-load.started.json; do
      [ -f "${f}" ] || continue
      [ "${f}" -nt "${CC_TMP}/start-marker" ] || continue
      NESTED_STARTED="${f}"
    done
    cc_error "nested-record-not-found"
    cc_warn "no final ${EVAL_LOADER} load record under ${RECORD_DIR}/ written after the start${NESTED_STARTED:+ (a started record exists: ${NESTED_STARTED})}"
    return 0
  fi
  cc_info "nested record ${NESTED} (status $(jq -r '.status // "?"' "${NESTED}"))"
}

# copy_report: resolves the logical location with the bucket (never printed),
# checks the size, downloads, checks the format and the attempt ID.
copy_report() {
  cc_step "report-copy"
  local loc attempt key copy_outcome out head bytes re_att='^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'
  if [ -z "${NESTED}" ]; then
    cc_set_str CC_EVIDENCE_JSON report_copy "missing: no final nested record"
    return 1
  fi
  attempt="$(jq -r '.attempt_id // empty' "${NESTED}")"
  loc="$(jq -r '.outputs.retrieval_eval_report.location // empty' "${NESTED}")"
  copy_outcome="$(jq -r '.outputs.retrieval_eval_report.copy_outcome // "null"' "${NESTED}")"
  cc_set_str CC_COUNTS_JSON nested_attempt_id "${attempt}"
  cc_set_str CC_COUNTS_JSON job_copy_outcome "${copy_outcome}"
  [[ "${attempt}" =~ ${re_att} ]] || {
    cc_error "nested-attempt-id-unexpected"
    cc_set_str CC_EVIDENCE_JSON report_copy "missing: the nested record has no usable attempt_id"
    return 1
  }
  key="${loc#"${LOGICAL_PREFIX}"}"
  if [ "${key}" = "${loc}" ] || [ "${key}" != "${REPORT_PREFIX}${attempt}/report.json" ]; then
    cc_error "report-location-unexpected"
    cc_set_str CC_EVIDENCE_JSON report_copy "missing: outputs.retrieval_eval_report.location is absent or not ${LOGICAL_PREFIX}${REPORT_PREFIX}${attempt}/report.json"
    return 1
  fi
  cc_set_str CC_COUNTS_JSON report_location "${loc}"
  printf 'REPORT: %s (job copy outcome: %s)\n' "${loc}" "${copy_outcome}"
  head="${CC_TMP}/head.json"
  if ! cc_aws_read "${head}" s3api head-object --bucket "${DATA_BUCKET}" --key "${key}"; then
    cc_error "report-head-${CC_LAST_AWS_ERROR}"
    cc_set_str CC_EVIDENCE_JSON report_copy "missing: head-object failed (${CC_LAST_AWS_ERROR})"
    return 1
  fi
  bytes="$(jq -r '.ContentLength // empty' "${head}")"
  if ! cc_is_uint "${bytes}" || [ "${bytes}" -gt "${MAX_REPORT_BYTES}" ] || [ "${bytes}" -eq 0 ]; then
    cc_error "report-size-unexpected"
    cc_set_str CC_EVIDENCE_JSON report_copy "missing: the report size is absent, 0 or above ${MAX_REPORT_BYTES} bytes"
    return 1
  fi
  out="${CC_TMP}/get.json"
  rm -f -- "${CC_TMP}/report.json"
  if ! cc_aws_read "${out}" s3api get-object --bucket "${DATA_BUCKET}" --key "${key}" "${CC_TMP}/report.json"; then
    cc_error "report-get-${CC_LAST_AWS_ERROR}"
    cc_set_str CC_EVIDENCE_JSON report_copy "missing: get-object failed (${CC_LAST_AWS_ERROR})"
    return 1
  fi
  if ! jq -e --arg f "${REPORT_FORMAT}" --arg a "${attempt}" \
    '.format == $f and .formatVersion == 1 and .attemptId == $a' "${CC_TMP}/report.json" >/dev/null 2>&1; then
    cc_error "report-format-unexpected"
    cc_set_str CC_EVIDENCE_JSON report_copy "invalid: not a ${REPORT_FORMAT} v1 report of attempt ${attempt}"
    return 1
  fi
  cp -- "${CC_TMP}/report.json" "${CC_OUT}/${REPORT_FILE}" || cc_die 5 "could not write ${REPORT_FILE}"
  CC_FILES+=("${REPORT_FILE}")
  cc_set_str CC_EVIDENCE_JSON report_copy "found: ${REPORT_FILE} (byte copy of the S3 object; ${bytes} bytes)"
  REPORT_OK="true"
}

# extract_modes: the per-mode table (one row per mode and K) and the counts.
extract_modes() {
  cc_step "extract"
  local rows="${CC_TMP}/modes.rows" line n=0
  cc_file "${MODES_FILE}" "mode,k,recall,mrr,ndcg,questions_scored,errors,empty,abstentions,by_design_weak,latency_p50_ms,latency_p95_ms,universe_size,not_comparable,label,recall_ceiling,mode_questions,report_status"
  if [ "${REPORT_OK}" != "true" ]; then
    cc_set_str CC_EVIDENCE_JSON modes_table "missing: no report"
    return 0
  fi
  jq -r --arg names "${MODE_NAMES}" '
    def num: if type == "number" then . else null end;
    . as $r
    | [($r.limits.ks // [])[] | num | select(. != null)] as $ks
    | ($names | split(" "))[] as $m
    | ($r.modes[$m] // null) as $s
    | select($s != null)
    | $ks[] as $k
    | ($k | tostring) as $ks1
    | [$m, $k, ($s.recallAt[$ks1] | num), ($s.mrr | num), ($s.ndcg | num), ($s.scored | num),
       ($s.errors | num), ($s.empty | num), ($s.abstentions | num), ($s.byDesignWeakQuestions | num),
       ($s.latencyMillis.median | num), ($s.latencyMillis.p95 | num),
       ($r.evalSet.questions | num), ($r.evalSet.notComparableCount | num),
       (if ($r.label | type) == "string" then $r.label else null end),
       ($s.recallCeilingAt[$ks1] | num), ($s.questions | num),
       (if ($r.status | type) == "string" then $r.status else null end)]
    | @csv' "${CC_OUT}/${REPORT_FILE}" >"${rows}" 2>/dev/null || {
    cc_error "modes-extract-failed"
    cc_set_str CC_EVIDENCE_JSON modes_table "missing: the report could not be read with jq"
    return 0
  }
  while IFS= read -r line; do
    if [ -n "${line}" ]; then
      cc_row "${MODES_FILE}" "${line}"
      n=$((n + 1))
    fi
  done <"${rows}"
  cc_set CC_COUNTS_JSON modes_rows "${n}"
  local summary
  summary="$(jq -c --arg names "${MODE_NAMES}" '
    def num: if type == "number" then . else null end;
    . as $r
    | {report_status: ($r.status // null), llm_calls: ($r.llmCalls // null), total_millis: ($r.totalMillis | num),
       exploratory: ($r.exploratory // null), label: ($r.label // null),
       universe_questions: ($r.evalSet.questions | num), labels: ($r.evalSet.labels | num),
       not_comparable: ($r.evalSet.notComparableCount | num),
       modes_requested: ($r.modesRequested // null),
       modes: ([($names | split(" "))[] as $m | ($r.modes[$m] // null) as $s | select($s != null)
                | {key: $m, value: {scored: ($s.scored | num), errors: ($s.errors | num), empty: ($s.empty | num),
                                    abstentions: ($s.abstentions | num), mrr: ($s.mrr | num),
                                    recall_at: ($s.recallAt // null)}}] | from_entries),
       modes_missing: [($names | split(" "))[] as $m | select(($r.modes[$m] // null) == null) | $m]}' \
    "${CC_OUT}/${REPORT_FILE}" 2>/dev/null || printf 'null')"
  cc_set CC_COUNTS_JSON report_summary "${summary}"
  if [ "${n}" -gt 0 ]; then
    cc_set_str CC_EVIDENCE_JSON modes_table "found: ${MODES_FILE} (${n} rows)"
  else
    cc_set_str CC_EVIDENCE_JSON modes_table "missing: the report has no mode summaries (status $(jq -r '.status // "?"' "${CC_OUT}/${REPORT_FILE}"))"
  fi
  printf 'MODES: rows=%s report_status=%s\n' "${n}" "$(jq -r '.status // "?"' "${CC_OUT}/${REPORT_FILE}")"
}

# ---- main -------------------------------------------------------------------------------------------

main() {
  cc_init "${TOOL_NAME}" "${TOOL_PATH}" "$@"
  parse_args "$@"
  cc_prepare_out "${REPORT_FILE}" "${MODES_FILE}" clock.csv
  cc_tf_outputs
  read_names
  CC_LIMITS_JSON="$(jq -n --arg mx "${MAX_SECONDS}" --arg qt "${QUESTION_TIMEOUT}" --argjson w "${WAIT_TIMEOUT}" \
    --argjson o "$((WAIT_TIMEOUT + PIPELINE_EXTRA_SECONDS))" --argjson ov "${EVAL_OVERRUN_ALLOWANCE}" \
    --argjson mb "${MAX_REPORT_BYTES}" --arg modes "${MODES}" --arg g "${GRAPH_RUN_ID}" \
    '{graph_run_id: $g, modes_arg: (if $modes == "" then null else $modes end),
      max_seconds_arg: (if $mx == "" then null else ($mx | tonumber) end),
      question_timeout_seconds_arg: (if $qt == "" then null else ($qt | tonumber) end),
      wait_timeout_s: $w, outer_limit_s: $o, overrun_allowance_s: $ov, max_report_bytes: $mb,
      remote_bounds: "the job total limit (--max-seconds, checked when a question starts) and per-call limit; one ops task at a time (run-bounded-pipeline.sh)"}')"
  if [ "${CC_PREFLIGHT}" = "true" ]; then
    preflight
    return 0
  fi
  cc_step "baseline"
  local before
  before="$(running_ops_tasks)"
  cc_set_str CC_BASELINE_JSON running_ops_tasks "${before}"
  if [ "${before}" = "x" ]; then
    cc_error "ecs-list-tasks-unreadable"
    cc_die 5 "the running ops tasks could not be read; nothing started"
  fi
  if [ "${before}" != "0" ]; then
    cc_set_str CC_OBS_JSON result "not run: running ops tasks = ${before} (one loader at a time)"
    cc_die 3 "running ops tasks = ${before}; nothing started (temporary unmet prerequisite: a later attempt is a new run)"
  fi
  cc_file clock.csv "phase,local_send_epoch_s,local_recv_epoch_s,http_status,curl_exit,time_total_s,http_date,http_date_epoch_s"
  cc_clock start
  run_pipeline
  find_record
  local copy_rc=0
  copy_report || copy_rc=$?
  extract_modes
  cc_clock end
  cc_set CC_COUNTS_JSON pipeline_exit "${PIPELINE_RC}"
  cc_set CC_COUNTS_JSON pipeline_started_epoch_s "${STARTED_AT}"
  if [ -n "${NESTED}" ]; then
    cc_set_str CC_COUNTS_JSON nested_run_record "${NESTED}"
    cc_set_str CC_COUNTS_JSON nested_status "$(jq -r '.status // "?"' "${NESTED}")"
  elif [ -n "${NESTED_STARTED}" ]; then
    cc_set_str CC_COUNTS_JSON nested_started_record "${NESTED_STARTED}"
  fi
  cc_set_str CC_EVIDENCE_JSON metrics "exported by the runner (AWS/Neptune, AWS/DynamoDB); the ops task has no AWS/ECS series (not a service; Container Insights off): its lifecycle is in the nested record"
  cc_set_str CC_OBS_JSON result "pipeline exit ${PIPELINE_RC}; report copy: ${REPORT_OK}; EXPLORATORY (11 questions in the universe); quality and latency per mode are in ${MODES_FILE}; no scaling evidence is expected from E"
  if [ "${PIPELINE_RC}" -ne 0 ]; then
    # the pipeline's exit code is the collector's (exit, not return: a
    # non-zero return would trip the ERR trap and become 5)
    exit "${PIPELINE_RC}"
  fi
  if [ "${copy_rc}" -ne 0 ]; then
    cc_die 5 "the pipeline succeeded but the report could not be copied or read (see tool-record.json evidence; the report stays in S3)"
  fi
  CC_STATUS="completed"
}

main "$@"
