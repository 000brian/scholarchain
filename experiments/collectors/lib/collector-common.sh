# shellcheck shell=bash
# shellcheck disable=SC2034  # constants are used by the sourcing collectors
# experiments/collectors/lib/collector-common.sh - helpers shared by the
# Phase 15 collectors (work order decision 11, "Shared rules for every tool").
# Owner: experiment-engineer. SOURCED by the collectors, never executed (no
# executable bit needed). Bash 3.2: no associative arrays, no mapfile, no
# ${var,,}, no wait -n, no quoted pattern replacement.
#
# runtime: untested
#
# What every collector gets from here:
#   - strict exits: 0 ran (whatever the system answered), 2 usage, 3 a
#     prerequisite not met (after its bounded settle), 5 tool or AWS failure,
#     7 a wait limit or TERM (INT gives 5, like the runner);
#   - every AWS CLI call with --region us-east-1, --output json,
#     --cli-connect-timeout, --cli-read-timeout and GNU timeout around it;
#     a failed call keeps only its exit code and the AWS error code (the
#     word in "An error occurred (CODE)"), never the error text;
#   - Terraform outputs read once into a private temporary file (never
#     printed, never recorded); queue URLs, the API URL and bucket names stay
#     in shell variables;
#   - the output folder: $SCHOLARCHAIN_EXPERIMENT_RUN_DIR (set by
#     scripts/run-experiment.sh); in --preflight nothing is written there
#     (only the private temporary folder, outside the repository, removed at
#     exit);
#   - tool-record.json written on every exit after the start (status,
#     exit code, limits, baseline, counts, observation, evidence, errors,
#     sha256 of every file the tool wrote).

readonly CC_REGION="us-east-1"
readonly CC_TF_DIR="infra/terraform/environments/course"
# Terraform name_prefix of the course environment (queue, table, alarm, ECS
# cluster names); checked against terraform output queue_names at start.
readonly CC_NAME_PREFIX="scholarchain-course"
readonly CC_CONNECT_TIMEOUT=10
readonly CC_READ_TIMEOUT=60
readonly CC_CALL_TIMEOUT=120
readonly CC_OUTPUT_TIMEOUT=120
readonly CC_MAX_TRIES=3
readonly CC_SAMPLE_SECONDS=15
readonly CC_RECORD_KIND="scholarchain-collector-tool-record"
# A public regional AWS endpoint used only to read the HTTP Date header for the
# clock offset (decision 10); nothing of the answer but the Date header and
# curl's timers is kept, and the address is never written to a file.
readonly CC_CLOCK_ENDPOINT="https://sqs.us-east-1.amazonaws.com/"

CC_TOOL=""
CC_TOOL_PATH=""
CC_TOOL_SHA=""
CC_STEP="start"
CC_TIMEOUT_BIN=""
CC_TMP=""
CC_OUT=""
CC_PREFLIGHT="false"
CC_START_EPOCH=""
CC_STATUS="started"
CC_RECORD_WRITTEN="false"
CC_ATTEMPT="${SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID:-}"
CC_MANIFEST="${SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID:-}"
CC_ARGS_JSON="[]"
CC_ERRORS_JSON="[]"
CC_LIMITS_JSON="{}"
CC_BASELINE_JSON="{}"
CC_COUNTS_JSON="{}"
CC_OBS_JSON="{}"
CC_EVIDENCE_JSON="{}"
CC_SELECTION_JSON="null"
CC_TF_FILE=""
CC_LAST_AWS_ERROR=""
CC_BG_PIDS=()
CC_FILES=()

# jq helpers: AWS CLI v2 prints timestamps as ISO 8601 with a zone offset (or
# epoch numbers for some services); ts turns either into epoch seconds, or
# null when the field is missing or unreadable (never 0).
# shellcheck disable=SC2016
readonly CC_JQ_DEFS='
  def iso2epoch:
    (capture("^(?<d>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(\\.[0-9]+)?(?<z>Z|[+-][0-9]{2}:?[0-9]{2})?$") // null) as $m
    | if $m == null then null
      else (($m.d + "Z") | fromdateiso8601) as $b
        | (if ($m.z == null or $m.z == "Z") then 0
           else ($m.z | capture("^(?<s>[+-])(?<h>[0-9]{2}):?(?<mm>[0-9]{2})$")) as $o
             | (($o.h | tonumber) * 3600 + ($o.mm | tonumber) * 60) * (if $o.s == "+" then 1 else -1 end)
           end) as $off
        | $b - $off
      end;
  def ts: if type == "number" then floor elif type == "string" then iso2epoch else null end;
  def cell: if . == null then "" else tostring end;
'

# ---- messages and traps ---------------------------------------------------------------

cc_step() {
  CC_STEP="$1"
  printf 'STEP: %s %s\n' "${CC_TOOL}" "$1"
}
cc_info() { printf 'INFO: %s %s\n' "${CC_TOOL}" "$*"; }
cc_warn() { printf 'WARN: %s %s\n' "${CC_TOOL}" "$*" >&2; }
cc_die() {
  local code="$1"
  shift
  printf 'ERROR: %s step=%s exit=%s: %s\n' "${CC_TOOL}" "${CC_STEP}" "${code}" "$*" >&2
  exit "${code}"
}

cc_error() {
  CC_ERRORS_JSON="$(jq -c --arg e "$1" '. + [$e]' <<<"${CC_ERRORS_JSON}" 2>/dev/null || printf '%s' "${CC_ERRORS_JSON}")"
}

# cc_set VAR KEY JSONVALUE: VAR (a JSON object variable) gets KEY = JSONVALUE.
cc_set() {
  local cur
  cur="${!1}"
  printf -v "$1" '%s' "$(jq -c --arg k "$2" --argjson v "$3" '. + {($k): $v}' <<<"${cur}")"
}
# cc_set_str VAR KEY STRING
cc_set_str() {
  cc_set "$1" "$2" "$(jq -n --arg s "$3" '$s')"
}

cc_bg_stop() {
  local p
  for p in ${CC_BG_PIDS[@]+"${CC_BG_PIDS[@]}"}; do
    kill "${p}" 2>/dev/null || true
  done
  for p in ${CC_BG_PIDS[@]+"${CC_BG_PIDS[@]}"}; do
    wait "${p}" 2>/dev/null || true
  done
  CC_BG_PIDS=()
}

cc_on_err() {
  local rc="$?"
  printf 'ERROR: %s step=%s line=%s exit=5: command failed with status %s\n' \
    "${CC_TOOL}" "${CC_STEP}" "$1" "${rc}" >&2
  cc_error "command-failed-step-${CC_STEP}"
  exit 5
}
cc_on_signal() {
  cc_error "signal-$1"
  CC_STATUS="interrupted"
  # A collector that starts a child (query-load.sh, run-bounded-pipeline.sh)
  # defines cc_signal_hook to give the child a bounded moment to write its
  # own record (GNU timeout sends the signal to the whole process group).
  if declare -F cc_signal_hook >/dev/null 2>&1; then
    cc_signal_hook || true
  fi
  if [ "$1" = "TERM" ]; then
    exit 7
  fi
  exit 5
}
cc_on_exit() {
  local rc="$?"
  trap - ERR
  set +e
  cc_bg_stop
  if [ "${CC_PREFLIGHT}" = "false" ] && [ -n "${CC_OUT}" ] && [ "${CC_RECORD_WRITTEN}" = "false" ] &&
    [ -n "${CC_START_EPOCH}" ]; then
    if [ "${CC_STATUS}" = "started" ]; then
      case "${rc}" in
        0) CC_STATUS="completed" ;;
        3) CC_STATUS="prerequisite-not-met" ;;
        7) CC_STATUS="wait-limit" ;;
        *) CC_STATUS="failed" ;;
      esac
    fi
    cc_write_tool_record "${rc}"
  fi
  if [ -n "${CC_TMP}" ] && [ -d "${CC_TMP}" ]; then
    rm -rf -- "${CC_TMP}"
  fi
  if [ "${rc}" -eq 0 ]; then
    printf 'RESULT: %s ok\n' "${CC_TOOL}"
  else
    printf 'RESULT: %s failed exit=%s step=%s\n' "${CC_TOOL}" "${rc}" "${CC_STEP}" >&2
  fi
  exit "${rc}"
}

cc_install_traps() {
  trap 'cc_on_err "${LINENO}"' ERR
  trap 'cc_on_signal INT' INT
  trap 'cc_on_signal TERM' TERM
  trap cc_on_exit EXIT
}

# ---- small utilities ------------------------------------------------------------------

cc_now() { date -u +%s; }

cc_sha256() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "$1" | cut -d' ' -f1
  else
    sha256sum -- "$1" | cut -d' ' -f1
  fi
}

cc_is_uint() {
  local re='^[0-9]+$'
  [[ "$1" =~ ${re} ]]
}

# ---- start: arguments, prerequisites, folders -----------------------------------------------

# cc_init TOOL_NAME TOOL_PATH ARGS...: records the arguments; checks the
# location (repository root), the local tools and GNU timeout; makes the
# private temporary folder (outside the repository).
cc_init() {
  CC_TOOL="$1"
  CC_TOOL_PATH="$2"
  shift 2
  local a
  for a in "$@"; do
    CC_ARGS_JSON="$(jq -c --arg a "${a}" '. + [$a]' <<<"${CC_ARGS_JSON}")"
    if [ "${a}" = "--preflight" ]; then
      CC_PREFLIGHT="true"
    fi
  done
  cc_install_traps
  cc_step "prerequisites"
  local top here tool
  top="$(git rev-parse --show-toplevel 2>/dev/null)" || cc_die 2 "not inside a git checkout; run from the repository root"
  here="$(pwd -P)"
  [ "${top}" = "${here}" ] || cc_die 2 "run from the repository root (./${CC_TOOL_PATH})"
  for tool in git jq aws terraform curl awk sort cut grep date; do
    command -v "${tool}" >/dev/null 2>&1 || cc_die 3 "required tool not found: ${tool}"
  done
  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 ||
    cc_die 3 "neither shasum nor sha256sum found"
  if command -v timeout >/dev/null 2>&1; then
    CC_TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    CC_TIMEOUT_BIN="gtimeout"
  else
    cc_die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  CC_TMP="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-collector.XXXXXX")"
  case "$(cd "${CC_TMP}" && pwd -P)/" in
    "${here}/"*) cc_die 2 "the temporary folder lies inside the repository (set TMPDIR elsewhere)" ;;
  esac
  CC_TOOL_SHA="$(cc_sha256 "${CC_TOOL_PATH}")"
  local re_tag='^[A-Za-z0-9._-]{1,80}$'
  if ! [[ "${CC_ATTEMPT}" =~ ${re_tag} ]]; then
    CC_ATTEMPT="local-$(cc_now)-$$"
  fi
  cc_info "tool sha256 ${CC_TOOL_SHA}; attempt ${CC_ATTEMPT}; preflight ${CC_PREFLIGHT}"
}

# cc_prepare_out FILE...: the output folder of a real run, refusing (5) when
# one of the tool's output files exists already (raw files are never
# overwritten). In --preflight nothing is prepared: nothing is written.
cc_prepare_out() {
  cc_step "out-dir"
  if [ "${CC_PREFLIGHT}" = "true" ]; then
    cc_info "preflight: no output file is written (temporary files only, outside the repository, removed at exit)"
    return 0
  fi
  CC_OUT="${SCHOLARCHAIN_EXPERIMENT_RUN_DIR:-}"
  [ -n "${CC_OUT}" ] || cc_die 2 "SCHOLARCHAIN_EXPERIMENT_RUN_DIR is not set (run through scripts/run-experiment.sh)"
  [ -d "${CC_OUT}" ] && [ -w "${CC_OUT}" ] || cc_die 5 "the output folder does not exist or is not writable"
  local top abs f
  top="$(pwd -P)"
  abs="$(cd "${CC_OUT}" && pwd -P)"
  case "${abs}/" in
    "${top}/"*)
      git check-ignore -q -- "${abs}/x" ||
        cc_die 2 "the output folder is inside the repository but not gitignored (use evidence/experiment-runs/)"
      ;;
  esac
  CC_OUT="${abs}"
  for f in tool-record.json "$@"; do
    [ ! -e "${CC_OUT}/${f}" ] || cc_die 5 "${f} exists in the output folder; raw files are never overwritten"
  done
  CC_START_EPOCH="$(cc_now)"
}

# cc_file NAME HEADER: registers an output CSV and writes its header (real run).
cc_file() {
  [ "${CC_PREFLIGHT}" = "false" ] || return 0
  CC_FILES+=("$1")
  printf '%s\n' "$2" >"${CC_OUT}/$1" || cc_die 5 "could not write $1"
}
# cc_row NAME ROW: appends one CSV row (real run only).
cc_row() {
  [ "${CC_PREFLIGHT}" = "false" ] || return 0
  printf '%s\n' "$2" >>"${CC_OUT}/$1" || cc_die 5 "could not write $1"
}
# cc_json_file NAME JSON: writes one JSON file (real run only).
cc_json_file() {
  [ "${CC_PREFLIGHT}" = "false" ] || return 0
  CC_FILES+=("$1")
  jq '.' <<<"$2" >"${CC_OUT}/$1" || cc_die 5 "could not write $1"
}

# ---- Terraform outputs (never printed) ----------------------------------------------------

cc_tf_outputs() {
  cc_step "terraform-outputs"
  local rc=0
  CC_TF_FILE="${CC_TMP}/tf-outputs.json"
  "${CC_TIMEOUT_BIN}" --kill-after=30 "${CC_OUTPUT_TIMEOUT}" terraform "-chdir=${CC_TF_DIR}" output -json -no-color \
    >"${CC_TF_FILE}" 2>/dev/null || rc=$?
  [ "${rc}" -eq 124 ] && cc_die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || cc_die 3 "terraform output failed (status ${rc}); is the environment applied?"
  jq -e 'type == "object"' "${CC_TF_FILE}" >/dev/null 2>&1 || cc_die 3 "terraform output is not a JSON object"
  local jobs
  jobs="$(cc_tf '.queue_names.value.jobs')"
  [ "${jobs}" = "${CC_NAME_PREFIX}-jobs" ] ||
    cc_die 3 "terraform output queue_names.jobs is not ${CC_NAME_PREFIX}-jobs (name prefix changed?)"
  cc_info "terraform outputs read (values kept in memory, not shown)"
}

# cc_tf JQ_PATH: prints one output value (empty when missing). Callers keep
# it in a variable; it is never printed.
cc_tf() {
  jq -r "($1) // empty | if type == \"string\" then . else tojson end" "${CC_TF_FILE}" 2>/dev/null || true
}

# cc_tf_required VARNAME JQ_PATH WHAT: sets VARNAME or exits 3.
cc_tf_required() {
  local v
  v="$(cc_tf "$2")"
  [ -n "${v}" ] || cc_die 3 "terraform output lacks $3"
  printf -v "$1" '%s' "${v}"
}

# ---- AWS CLI ------------------------------------------------------------------------------

# cc_aws_raw OUTFILE ARGS...: one call; sets CC_LAST_AWS_ERROR; returns 0, 5
# or 7 (GNU timeout). The error text is read only for its AWS error code.
cc_aws_raw() {
  local out="$1" rc=0 code=""
  shift
  "${CC_TIMEOUT_BIN}" --kill-after=10 "${CC_CALL_TIMEOUT}" aws --region "${CC_REGION}" --output json \
    --cli-connect-timeout "${CC_CONNECT_TIMEOUT}" --cli-read-timeout "${CC_READ_TIMEOUT}" --no-cli-pager \
    "$@" >"${out}" 2>"${CC_TMP}/aws.err" || rc=$?
  if [ "${rc}" -eq 0 ]; then
    CC_LAST_AWS_ERROR=""
    [ -s "${out}" ] || printf '{}\n' >"${out}"
    return 0
  fi
  code="$(grep -oE 'An error occurred \([A-Za-z0-9._-]{1,80}\)' "${CC_TMP}/aws.err" 2>/dev/null | head -n 1 |
    cut -d'(' -f2 | cut -d')' -f1 || true)"
  [ -n "${code}" ] || code="none"
  CC_LAST_AWS_ERROR="cli-exit-${rc}:${code}"
  : >"${CC_TMP}/aws.err"
  if [ "${rc}" -eq 124 ] || [ "${rc}" -eq 137 ]; then
    return 7
  fi
  return 5
}

# cc_aws_read OUTFILE ARGS...: a read call, retried up to CC_MAX_TRIES times.
cc_aws_read() {
  local out="$1" try=1 rc
  shift
  while :; do
    rc=0
    cc_aws_raw "${out}" "$@" || rc=$?
    [ "${rc}" -eq 0 ] && return 0
    if [ "${try}" -ge "${CC_MAX_TRIES}" ]; then
      return "${rc}"
    fi
    sleep $((try * 3))
    try=$((try + 1))
  done
}

# ---- samples (one CSV row per item) ---------------------------------------------------------

# cc_sample_service FILE CLUSTER SERVICE...: desired, running, pending per
# service (describe-services). A missing field is an empty cell plus an error
# code, never 0.
cc_sample_service() {
  local file="$1" cluster="$2" now out="${CC_TMP}/ecs-$$-${RANDOM}.json"
  shift 2
  now="$(cc_now)"
  if ! cc_aws_read "${out}" ecs describe-services --cluster "${cluster}" --services "$@"; then
    cc_error "ecs-describe-services-${CC_LAST_AWS_ERROR}"
    local s
    for s in "$@"; do
      cc_row "${file}" "${now},${s},,,"
    done
    return 0
  fi
  local s line
  for s in "$@"; do
    line="$(jq -r --arg s "${s}" --arg t "${now}" '
      [.services[]? | select(.serviceName == $s)] | .[0] as $x
      | if $x == null then "\($t),\($s),,,"
        else "\($t),\($s),\($x.desiredCount // "" | tostring),\($x.runningCount // "" | tostring),\($x.pendingCount // "" | tostring)"
        end' "${out}" 2>/dev/null || printf '%s,%s,,,' "${now}" "${s}")"
    case "${line}" in
      *",,"* | *",") cc_error "ecs-field-missing-${s}" ;;
    esac
    cc_row "${file}" "${line}"
  done
  rm -f -- "${out}"
}

# cc_sample_queue FILE ROLE URL: visible and in-flight of one queue
# (get-queue-attributes). The URL stays in the variable; the row has the role.
cc_sample_queue() {
  local file="$1" role="$2" url="$3" now out="${CC_TMP}/sqs-$$-${RANDOM}.json" line
  now="$(cc_now)"
  if ! cc_aws_read "${out}" sqs get-queue-attributes --queue-url "${url}" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible; then
    cc_error "sqs-attributes-${role}-${CC_LAST_AWS_ERROR}"
    cc_row "${file}" "${now},${role},,"
    return 0
  fi
  line="$(jq -r --arg t "${now}" --arg r "${role}" \
    '"\($t),\($r),\(.Attributes.ApproximateNumberOfMessages // ""),\(.Attributes.ApproximateNumberOfMessagesNotVisible // "")"' \
    "${out}" 2>/dev/null || printf '%s,%s,,' "${now}" "${role}")"
  case "${line}" in
    *",,"* | *",") cc_error "sqs-field-missing-${role}" ;;
  esac
  cc_row "${file}" "${line}"
  rm -f -- "${out}"
}

# cc_queue_counts URL: prints "VISIBLE INFLIGHT" (empty words when unreadable).
cc_queue_counts() {
  local out="${CC_TMP}/sqsc-$$-${RANDOM}.json"
  if ! cc_aws_read "${out}" sqs get-queue-attributes --queue-url "$1" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible; then
    printf 'x x\n'
    return 0
  fi
  jq -r '"\(.Attributes.ApproximateNumberOfMessages // "x") \(.Attributes.ApproximateNumberOfMessagesNotVisible // "x")"' \
    "${out}" 2>/dev/null || printf 'x x\n'
  rm -f -- "${out}"
}

# cc_queue_settings URL: prints "MAX_RECEIVE_COUNT VISIBILITY_TIMEOUT" ("x" when missing).
cc_queue_settings() {
  local out="${CC_TMP}/sqss-$$.json"
  if ! cc_aws_read "${out}" sqs get-queue-attributes --queue-url "$1" \
    --attribute-names RedrivePolicy VisibilityTimeout; then
    printf 'x x\n'
    return 0
  fi
  jq -r '((.Attributes.RedrivePolicy // "{}") | fromjson? // {}) as $r
    | "\($r.maxReceiveCount // "x") \(.Attributes.VisibilityTimeout // "x")"' "${out}" 2>/dev/null || printf 'x x\n'
}

# cc_sample_alarms FILE NAME...: state of each named alarm (describe-alarms).
cc_sample_alarms() {
  local file="$1" now out="${CC_TMP}/cw-$$-${RANDOM}.json" n st
  shift
  now="$(cc_now)"
  if ! cc_aws_read "${out}" cloudwatch describe-alarms --alarm-names "$@"; then
    cc_error "describe-alarms-${CC_LAST_AWS_ERROR}"
    for n in "$@"; do
      cc_row "${file}" "${now},${n},"
    done
    return 0
  fi
  for n in "$@"; do
    st="$(jq -r --arg n "${n}" '[.MetricAlarms[]? | select(.AlarmName == $n) | .StateValue] | .[0] // ""' \
      "${out}" 2>/dev/null || true)"
    [ -n "${st}" ] || cc_error "alarm-state-missing-${n}"
    cc_row "${file}" "${now},${n},${st}"
  done
  rm -f -- "${out}"
}

# cc_alarm_state NAME: prints the state (empty when unreadable).
cc_alarm_state() {
  local out="${CC_TMP}/cws-$$-${RANDOM}.json"
  cc_aws_read "${out}" cloudwatch describe-alarms --alarm-names "$1" || {
    printf '\n'
    return 0
  }
  jq -r '[.MetricAlarms[]?.StateValue] | .[0] // ""' "${out}" 2>/dev/null || printf '\n'
  rm -f -- "${out}"
}

# cc_scaling_activities FILE CLUSTER SERVICE SINCE_EPOCH: the service's
# Application Auto Scaling activities since SINCE (time, direction from the
# alarm or policy named in the cause, status code). The cause text is not kept.
cc_scaling_activities() {
  local file="$1" cluster="$2" service="$3" since="$4" out="${CC_TMP}/aas.json"
  if ! cc_aws_read "${out}" application-autoscaling describe-scaling-activities --service-namespace ecs \
    --resource-id "service/${cluster}/${service}" --scalable-dimension ecs:service:DesiredCount --max-items 200; then
    cc_error "scaling-activities-${CC_LAST_AWS_ERROR}"
    return 0
  fi
  jq -r --arg svc "${service}" --argjson since "${since}" "${CC_JQ_DEFS}"'
    [.ScalingActivities[]?
     | {s: (.StartTime | ts), e: (.EndTime | ts), st: (.StatusCode // ""),
        d: ((.Cause // "") as $c
            | if ($c | test("worker-scale-in|worker-in")) then "in"
              elif ($c | test("worker-scale-out|worker-out")) then "out"
              else "unknown" end)}
     | select(.s != null and .s >= $since)]
    | sort_by(.s) | .[]
    | "\(.s | cell),\(.e | cell),\($svc),\(.d),\(.st)"' "${out}" 2>/dev/null >"${CC_TMP}/aas.rows" || {
    cc_error "scaling-activities-unreadable"
    return 0
  }
  local line
  while IFS= read -r line; do
    [ -n "${line}" ] && cc_row "${file}" "${line}"
  done <"${CC_TMP}/aas.rows"
}

# cc_wait_child PID SECONDS: waits at most SECONDS for PID to end (bounded;
# used by cc_signal_hook before the runner's kill-after of 30 s).
cc_wait_child() {
  local pid="$1" n=0
  [ -n "${pid}" ] || return 0
  while kill -0 "${pid}" 2>/dev/null && [ "${n}" -lt "$2" ]; do
    sleep 1
    n=$((n + 1))
  done
}

# ---- the worker's per-delivery log line -----------------------------------------------------
# The worker writes one structured log line per delivery (decision 8.1, event
# reembed-delivery) with the task ID and the outcome (processed, replayed,
# permanent-failure, transient-failure). These three constants are the ONLY
# place F and C depend on that format; 6c checked them against the rag and
# service handoffs and the Terraform stream prefix (match). A JSON line is read
# with these keys; a key=value or "key":"value" fragment inside a text line is
# read too.
readonly CC_WORKER_LOG_TASK_FIELD="taskId"
readonly CC_WORKER_LOG_OUTCOME_FIELD="outcome"
readonly CC_WORKER_LOG_STREAM_PREFIX="worker/"

# jq program over `aws logs filter-log-events` output: one line
# "EPOCH_S TASK_ID OUTCOME" per event whose task field starts with $prefix;
# outcome lower-cased, characters other than a-z and - replaced by -. The
# message itself is never printed.
# shellcheck disable=SC2016
readonly CC_WORKER_LOG_JQ='
  .events[]? | {t: (((.timestamp // 0) / 1000) | floor), m: (.message // "")}
  | (.m | fromjson? // null) as $j
  | (if ($j | type) == "object" then ($j[$tf] // null)
     else ([.m | capture($tf + "[\"]?[=:][ \"]*(?<v>[A-Za-z0-9._:-]+)") | .v] | .[0] // null) end) as $task
  | (if ($j | type) == "object" then ($j[$of] // null)
     else ([.m | capture($of + "[\"]?[=:][ \"]*(?<v>[A-Za-z_-]+)") | .v] | .[0] // null) end) as $out
  | select(($task | type) == "string" and ($task | startswith($prefix)))
  | "\(.t) \($task) \(($out // "unknown") | tostring | ascii_downcase | gsub("[^a-z-]"; "-"))"'

# ---- background sampler ---------------------------------------------------------------------

# cc_sampler_start LIMIT_SECONDS FUNCTION: runs FUNCTION every CC_SAMPLE_SECONDS
# in the background until cc_bg_stop (or TERM), bounded by a count and a
# deadline. Errors inside the sampler go to a private file (merged into the
# record by cc_sampler_merge_errors); the sampler never stops the tool.
cc_sampler_start() {
  local limit="$1" fn="$2" deadline max
  deadline=$(($(cc_now) + limit))
  max=$((limit / CC_SAMPLE_SECONDS + 2))
  (
    trap - ERR EXIT INT
    trap 'exit 0' TERM
    set +e
    # Redefined inside the sampler subshell (called by the sample functions).
    # shellcheck disable=SC2329
    cc_error() { printf '%s\n' "$1" >>"${CC_TMP}/sampler-errors.txt"; }
    n=0
    while [ "${n}" -lt "${max}" ] && [ "$(cc_now)" -le "${deadline}" ]; do
      "${fn}"
      n=$((n + 1))
      sleep "${CC_SAMPLE_SECONDS}" &
      wait "$!"
    done
  ) &
  CC_BG_PIDS+=("$!")
}

cc_sampler_merge_errors() {
  local f="${CC_TMP}/sampler-errors.txt" line
  [ -f "${f}" ] || return 0
  while IFS= read -r line; do
    [ -n "${line}" ] && cc_error "sampler:${line}"
  done < <(sort "${f}" | uniq -c | awk '{print $2 "-x" $1}')
}

# ---- clock (decision 10) ------------------------------------------------------------------

# cc_clock PHASE: the HTTP Date header of one AWS answer with the local send
# and receive seconds and curl's time_total (clock.csv; same columns as
# load-tests/query-load.sh's load-clock.csv).
cc_clock() {
  local phase="$1" send_s recv_s w rc=0 code ttot hdr="${CC_TMP}/clock-hdr.txt" d="" de=""
  send_s="$(cc_now)"
  w="$("${CC_TIMEOUT_BIN}" --kill-after=5 20 curl --silent --head --connect-timeout 5 --max-time 10 \
    --output /dev/null --dump-header "${hdr}" --write-out '%{http_code} %{time_total}' \
    "${CC_CLOCK_ENDPOINT}" </dev/null 2>/dev/null)" || rc=$?
  recv_s="$(cc_now)"
  code="${w%% *}"
  ttot="${w#* }"
  local re_code='^[0-9]{3}$' re_t='^[0-9]+(\.[0-9]+)?$'
  [[ "${code}" =~ ${re_code} ]] || code="000"
  [[ "${ttot}" =~ ${re_t} ]] || ttot=""
  if [ -f "${hdr}" ]; then
    d="$(grep -i -E '^date:' "${hdr}" | tail -n 1 | cut -d' ' -f2- | tr -d '\r' || true)"
  fi
  local re_d='^[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT$'
  if [[ "${d}" =~ ${re_d} ]]; then
    de="$(date -j -u -f '%a, %d %b %Y %H:%M:%S GMT' "${d}" +%s 2>/dev/null ||
      date -u -d "${d}" +%s 2>/dev/null || true)"
    d="${d#*, }"
  else
    d=""
    cc_error "clock-date-missing-${phase}"
  fi
  cc_row clock.csv "${phase},${send_s},${recv_s},${code},${rc},${ttot},${d},${de}"
}

# ---- API (F part 3) -------------------------------------------------------------------------

# cc_api_send API_URL METHOD PATH MAX_TIME BODY_FILE IDEMPOTENCY_KEY OUT_BODY OUT_HEADERS
# prints "CURL_EXIT HTTP_CODE TIME_TOTAL". The key travels only as a curl
# config line on standard input (printf is a builtin: never in any process's
# arguments); curl --silent prints no error text; the URL is never printed.
cc_api_send() {
  local api="$1" method="$2" path="$3" max_time="$4" body_file="$5" idem="$6" out_body="$7" out_hdr="$8"
  local args=(--silent --connect-timeout 10 --max-time "${max_time}" --request "${method}"
    --output "${out_body}" --write-out '%{http_code} %{time_total}' --header "Accept: application/json"
    --dump-header "${out_hdr}")
  if [ -n "${body_file}" ]; then
    args+=(--header "Content-Type: application/json" --data-binary "@${body_file}")
  fi
  if [ -n "${idem}" ]; then
    args+=(--header "Idempotency-Key: ${idem}")
  fi
  local rc=0 w=""
  rm -f -- "${out_body}" "${out_hdr}"
  w="$(printf 'header = "X-Api-Key: %s"\n' "${SCHOLARCHAIN_SERVICE_API_KEY}" |
    "${CC_TIMEOUT_BIN}" --kill-after=5 "$((max_time + 10))" curl --config - "${args[@]}" \
      "${api}${path}" 2>/dev/null)" || rc=$?
  local code="${w%% *}" ttot="${w#* }"
  local re_code='^[0-9]{3}$' re_t='^[0-9]+(\.[0-9]+)?$'
  [[ "${code}" =~ ${re_code} ]] || code="000"
  [[ "${ttot}" =~ ${re_t} ]] || ttot=""
  printf '%s %s %s\n' "${rc}" "${code}" "${ttot}"
}

# cc_api_key_check: the key variable is set and safe to pass in a curl config
# line (value never printed).
cc_api_key_check() {
  if [ -z "${SCHOLARCHAIN_SERVICE_API_KEY:-}" ]; then
    cc_die 3 "SCHOLARCHAIN_SERVICE_API_KEY is not set in this shell (export it before the run)"
  fi
  case "${SCHOLARCHAIN_SERVICE_API_KEY}" in
    *[[:cntrl:]]* | *'"'* | *\\*)
      cc_die 3 "the value of SCHOLARCHAIN_SERVICE_API_KEY holds a control character, a double quote or a backslash"
      ;;
  esac
}

# cc_api_url VARNAME: the API URL from terraform output (trailing / removed),
# checked as https://; never printed.
cc_api_url() {
  local u re='^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$'
  u="$(cc_tf '.api_url.value')"
  u="${u%/}"
  [[ "${u}" =~ ${re} ]] || cc_die 3 "terraform output api_url is missing or not an https:// address"
  printf -v "$1" '%s' "${u}"
}

# ---- vector keys from the DynamoDB ArtifactRegistry table (C and F; M-01) ------------------
# The vector bucket policy (infra/terraform/modules/vectors/main.tf,
# DataPlaneOnlyForRuntimeAndOps) explicitly denies GetVectors, ListVectors,
# PutVectors and QueryVectors to every principal except the query, worker and
# ops task roles, so the laptop profile that runs these collectors never calls
# s3vectors (repair-01, orchestrator decision: no security change). Keys come
# from the ArtifactRegistry table instead (no resource policy): storage
# TableSpecs.ArtifactRegistry, pk artifact#vector#INDEX/VECTOR_KEY, sk
# v#NNNNNNNN (append-only, one item per registered version, written by the
# vector ingest after PutVectors with indexName, vectorKey, textHash,
# textS3Uri, embeddingModel, embeddingDimensions). The table name is the
# Terraform output dynamodb_table_names.ArtifactRegistry (a name, never
# printed).
#
# Selection rule (repeatable): a read-only Scan of the table with
# FilterExpression begins_with(pk, "artifact#vector#INDEX/"), projection pk
# only, eventually consistent, page size CC_REGISTRY_SCAN_PAGE_SIZE items
# evaluated per request, at most min(COUNT, CC_REGISTRY_SCAN_ITEMS_PER_CALL)
# matches per CLI call, continued with --starting-token; duplicate pks (several versions
# of one key) are dropped keeping the first; the first COUNT unique keys in
# scan order are taken. Scan order is the table's partition order: the same
# for unchanged table contents, not guaranteed after repartitioning, so the
# selected keys are also recorded (selected-keys.txt and its sha256 in C).
# Bounds: CC_REGISTRY_SCAN_MAX_CALLS calls and CC_REGISTRY_SCAN_LIMIT_SECONDS
# between calls (one call can add up to CC_CALL_TIMEOUT x CC_MAX_TRIES), and
# the table's end. Read cost: a scan is charged on the size of every item
# evaluated, filtered or not (estimate in the C manifest notes).
readonly CC_REGISTRY_SCAN_PAGE_SIZE=1000
readonly CC_REGISTRY_SCAN_ITEMS_PER_CALL=1000
readonly CC_REGISTRY_SCAN_MAX_CALLS=80
readonly CC_REGISTRY_SCAN_LIMIT_SECONDS=420
CC_REGISTRY_CALLS=0
CC_REGISTRY_SCANNED=0
CC_REGISTRY_MATCHED=0
CC_REGISTRY_UNIQUE=0

# cc_registry_select TABLE INDEX COUNT KEYS_FILE: writes the first COUNT unique
# vector keys of INDEX (scan order, rule above) to KEYS_FILE, one per line.
# Returns 0 with exactly COUNT keys; 3 with fewer (table end or a bound: the
# file then holds the keys found, never used to send); the AWS call's 5 or 7
# on a failed read (CC_LAST_AWS_ERROR). Sets CC_REGISTRY_CALLS, _SCANNED (sum
# of ScannedCount), _MATCHED (items returned) and _UNIQUE. Prints nothing.
cc_registry_select() {
  local table="$1" index="$2" count="$3" keys_file="$4" prefix values token="" deadline rc n per
  local out="${CC_TMP}/ar-scan.json" pks="${CC_TMP}/ar-pks.txt"
  per="${CC_REGISTRY_SCAN_ITEMS_PER_CALL}"
  if [ "${count}" -lt "${per}" ]; then per="${count}"; fi
  prefix="artifact#vector#${index}/"
  values="$(jq -cn --arg p "${prefix}" '{":p": {S: $p}}')"
  : >"${pks}"
  : >"${keys_file}"
  CC_REGISTRY_CALLS=0
  CC_REGISTRY_SCANNED=0
  CC_REGISTRY_MATCHED=0
  CC_REGISTRY_UNIQUE=0
  deadline=$(($(cc_now) + CC_REGISTRY_SCAN_LIMIT_SECONDS))
  while [ "${CC_REGISTRY_CALLS}" -lt "${CC_REGISTRY_SCAN_MAX_CALLS}" ] && [ "$(cc_now)" -le "${deadline}" ]; do
    local args=(dynamodb scan --table-name "${table}" --filter-expression "begins_with(pk, :p)"
      --expression-attribute-values "${values}" --projection-expression pk
      --page-size "${CC_REGISTRY_SCAN_PAGE_SIZE}" --max-items "${per}")
    [ -z "${token}" ] || args+=(--starting-token "${token}")
    rc=0
    cc_aws_read "${out}" "${args[@]}" || rc=$?
    [ "${rc}" -eq 0 ] || return "${rc}"
    CC_REGISTRY_CALLS=$((CC_REGISTRY_CALLS + 1))
    n="$(jq '.ScannedCount // 0' "${out}" 2>/dev/null || printf 0)"
    if cc_is_uint "${n}"; then CC_REGISTRY_SCANNED=$((CC_REGISTRY_SCANNED + n)); fi
    n="$(jq '[.Items[]?] | length' "${out}" 2>/dev/null || printf 0)"
    if cc_is_uint "${n}"; then CC_REGISTRY_MATCHED=$((CC_REGISTRY_MATCHED + n)); fi
    jq -r '.Items[]?.pk.S // empty' "${out}" >>"${pks}" 2>/dev/null || true
    token="$(jq -r '.NextToken // empty' "${out}" 2>/dev/null || true)"
    rm -f -- "${out}"
    CC_REGISTRY_UNIQUE="$(awk '!seen[$0]++ {k++} END {print k + 0}' "${pks}")"
    [ "${CC_REGISTRY_UNIQUE}" -lt "${count}" ] || break
    [ -n "${token}" ] || break
  done
  # First COUNT unique pks in scan order, prefix removed; awk stops printing at
  # COUNT itself (no head in the pipe: no SIGPIPE under pipefail).
  awk -v p="${prefix}" -v c="${count}" '
    !seen[$0]++ && index($0, p) == 1 && length($0) > length(p) {
      if (++k <= c) print substr($0, length(p) + 1)
    }' "${pks}" >"${keys_file}"
  rm -f -- "${pks}"
  n="$(grep -c . "${keys_file}" || true)"
  [ "${n}" -eq "${count}" ] || return 3
  return 0
}

# cc_registry_latest TABLE INDEX KEY MODEL DIMS: one Query for the latest
# registered version of KEY (storage access pattern AR1: pk = artifact#vector#
# INDEX/KEY, ScanIndexForward=false, Limit 1; projection sk, textHash,
# embeddingModel, embeddingDimensions). Sets CC_REG_LINE to
# "ELIGIBLE MISSING_FIELDS MODEL_CHECK" (ELIGIBLE yes or no; MISSING_FIELDS a
# ;-joined list, "-" or "no-version"; MODEL_CHECK model-ok, model-mismatch or
# model-unknown) and CC_REG_TEXT_HASH to the version's textHash (kept in
# memory, never printed). Returns 0, or the AWS call's 5 or 7. Call it
# directly, not in a command substitution, so both variables reach the caller.
CC_REG_LINE=""
CC_REG_TEXT_HASH=""
cc_registry_latest() {
  local table="$1" index="$2" key="$3" model="$4" dims="$5" out="${CC_TMP}/ar-latest.json" values rc=0
  CC_REG_LINE=""
  CC_REG_TEXT_HASH=""
  values="$(jq -cn --arg pk "artifact#vector#${index}/${key}" '{":pk": {S: $pk}}')"
  cc_aws_read "${out}" dynamodb query --table-name "${table}" --key-condition-expression "pk = :pk" \
    --expression-attribute-values "${values}" --no-scan-index-forward --page-size 1 --max-items 1 \
    --projection-expression "sk, textHash, embeddingModel, embeddingDimensions" || rc=$?
  [ "${rc}" -eq 0 ] || return "${rc}"
  CC_REG_LINE="$(jq -r --arg model "${model}" --arg dims "${dims}" '
    (.Items[0] // null) as $it
    | if $it == null then "no no-version model-unknown"
      else ([("textHash", "embeddingModel", "embeddingDimensions") as $f | select(($it[$f] // null) == null) | $f]) as $miss
        | ((($it.embeddingModel.S // "") == $model) and ((($it.embeddingDimensions.N // "") | tostring) == $dims)) as $mok
        | "\(if ($miss | length) == 0 and $mok then "yes" else "no" end) \(if ($miss | length) == 0 then "-" else ($miss | join(";")) end) \(if $mok then "model-ok" else "model-mismatch" end)"
      end' "${out}" 2>/dev/null || true)"
  [ -n "${CC_REG_LINE}" ] || CC_REG_LINE="no unreadable model-unknown"
  CC_REG_TEXT_HASH="$(jq -r '.Items[0].textHash.S // empty' "${out}" 2>/dev/null || true)"
  rm -f -- "${out}"
  return 0
}

# ---- tool record ------------------------------------------------------------------------------

cc_write_tool_record() {
  local rc="$1" f files="[]" end
  end="$(cc_now)"
  for f in ${CC_FILES[@]+"${CC_FILES[@]}"}; do
    if [ -f "${CC_OUT}/${f}" ]; then
      files="$(jq -c --arg n "${f}" --arg s "$(cc_sha256 "${CC_OUT}/${f}")" '. + [{name: $n, sha256: $s}]' <<<"${files}")"
    fi
  done
  CC_RECORD_WRITTEN="true"
  jq -n --arg kind "${CC_RECORD_KIND}" --arg tool "${CC_TOOL}" --arg path "${CC_TOOL_PATH}" \
    --arg sha "${CC_TOOL_SHA}" --argjson args "${CC_ARGS_JSON}" --arg attempt "${CC_ATTEMPT}" \
    --arg manifest "${CC_MANIFEST}" --arg status "${CC_STATUS}" --argjson rc "${rc}" \
    --argjson start "${CC_START_EPOCH:-null}" --argjson end "${end}" --argjson limits "${CC_LIMITS_JSON}" \
    --argjson baseline "${CC_BASELINE_JSON}" --argjson counts "${CC_COUNTS_JSON}" \
    --argjson observation "${CC_OBS_JSON}" --argjson evidence "${CC_EVIDENCE_JSON}" \
    --argjson selection "${CC_SELECTION_JSON}" --argjson errors "${CC_ERRORS_JSON}" --argjson files "${files}" \
    '{kind: $kind, version: 1, tool: $tool, tool_path: $path, tool_sha256: $sha, arguments: $args,
      attempt_id: $attempt, manifest_id: $manifest, region: "us-east-1",
      start_epoch_s: $start, end_epoch_s: $end, status: $status, exit_code: $rc,
      exit_meaning: ({"0": "ran (observation recorded)", "2": "usage", "3": "prerequisite not met",
                      "5": "tool or AWS failure", "7": "wait limit or TERM"}[$rc | tostring] // "unknown"),
      limits: $limits, baseline: $baseline, selection: $selection, counts: $counts,
      observation: $observation, evidence: $evidence, errors: $errors, files: $files}' \
    >"${CC_OUT}/tool-record.json" 2>/dev/null || printf 'WARN: %s could not write tool-record.json\n' "${CC_TOOL}" >&2
}
