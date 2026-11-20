#!/usr/bin/env bash
# experiments/collectors/f-poison-replay.sh - experiment F (failure and
# recovery): one poison message and one sequential duplicate on the embedding
# queue, one API idempotency replay (Phase 15 work order, decision 11 item 4).
# Owner: experiment-engineer. A "command" action of scripts/run-experiment.sh;
# run by the human only, never by an agent.
#
# runtime: untested
# static: shellcheck (6b report); bash 3.2 constructs only.

set -Eeuo pipefail
umask 077

readonly TOOL_NAME="f-poison-replay.sh"
readonly TOOL_PATH="experiments/collectors/f-poison-replay.sh"
COLLECTOR_DIR="$(cd "$(dirname "$0")" && pwd -P)"
# shellcheck source=experiments/collectors/lib/collector-common.sh
. "${COLLECTOR_DIR}/lib/collector-common.sh"

# ---- the worker's log line ------------------------------------------------------------------
# The field names of the worker's per-delivery log line are ONE set of
# constants, CC_WORKER_LOG_TASK_FIELD, CC_WORKER_LOG_OUTCOME_FIELD and
# CC_WORKER_LOG_STREAM_PREFIX, in the "worker's per-delivery log line" section
# of lib/collector-common.sh (shared with c-embedding-backlog.sh); 6c checked
# them against the rag and service handoffs. The worker-log filter below uses
# only those constants.
#
# ---- vector key (M-01, repair-01) -----------------------------------------------------------
# The vector bucket policy denies the S3 Vectors data plane to every principal
# except the query, worker and ops task roles, so this tool never calls
# s3vectors. The valid key and its textHash come from the DynamoDB
# ArtifactRegistry table (lib cc_registry_select and cc_registry_latest).

# ---- fixed interface (decision 7.2): message embedding-task-v1 ------------------------------
readonly TASK_SCHEMA="embedding-task-v1"
readonly POISON_INDEX="p15-f-unknown-index"

# ---- wait limits (estimates; stated in the record) ------------------------------------------
# Worker scale-out from 0: alarm period 60 s + SQS metric delay 1-3 min +
# Fargate start 1-2 min (infra 5a timeline, estimate) -> 480 s allowance.
readonly SCALEOUT_ALLOWANCE=480
# One receive-and-release cycle of the poison message with the release-at-once
# rule (decision 7.2): long poll up to 20 s plus validation (estimate).
readonly RELEASE_CYCLE=60
# DLQ ApproximateNumberOfMessages lag (estimate).
readonly DLQ_COUNT_LAG=120
# Processing of one valid task (Voyage call, GetVectors, PutVectors) plus
# CloudWatch Logs delivery (estimate).
readonly PROCESS_ALLOWANCE=120
readonly LOG_DELIVERY_ALLOWANCE=120
# In flight back to 0 after the parts; DLQ alarm (period 300 s, 1 of 1).
readonly RECOVERY_LIMIT=600
readonly DLQ_ALARM_LIMIT=900
readonly API_MAX_TIME=35
readonly TOOL_DEADLINE_SECONDS=4200
readonly POLL_SECONDS=15
readonly LOG_POLL_SECONDS=20
readonly DLQ_RECEIVE_TRIES=5

INDEX="researcher-profile-v1"
QUESTION_ID="q05"
CLUSTER=""
WORKER=""
EMB_URL=""
JOB_URL=""
EMB_DLQ_URL=""
JOB_DLQ_URL=""
REGISTRY_TABLE=""
QS_TABLE=""
LOG_GROUP=""
API_URL=""
MODEL=""
DIMS=""
MRC=""
VIS=""
P1_LIMIT=0
P2_LIMIT=0
DEADLINE=0
VALID_KEY=""
VALID_TEXT_HASH=""
POISON_TID=""
REPLAY_TID=""
ALARM_DLQ=""
ALARM_OUT=""
ALARM_OUT_EMB=""
ALARM_IN=""

usage() {
  cat <<'EOF'
Usage: experiments/collectors/f-poison-replay.sh [--index NAME] [--question-id qNN] [--preflight]

Experiment F (Phase 15 decision 11 item 4). Run from the repository root by
scripts/run-experiment.sh (it sets SCHOLARCHAIN_EXPERIMENT_RUN_DIR,
_ATTEMPT_ID and _MANIFEST_ID). Needs SCHOLARCHAIN_SERVICE_API_KEY (part 3).

Parts, in order, each with its own wait limit:
  1 poison   one valid-JSON embedding-task-v1 message naming an unknown index
             (taskId p15-f-poison-ATTEMPT); done when the embedding DLQ holds
             baseline + 1 and a DLQ receive (--visibility-timeout 0, never
             deleted) shows that taskId.
  2 replay   one valid embedding-task-v1 message for one existing vector key
             of --index (the first key of the index in a bounded, read-only
             scan of the DynamoDB ArtifactRegistry table, checked against its
             latest registry version; never the S3 Vectors data plane, which
             the bucket policy denies to this principal) (sent right after
             the poison message, so it is processed while the poison is
             retried: the recovery evidence); wait for the worker log line
             "processed"; only then the same message again; wait for
             "replayed". Each wait's limit counts from when that wait
             starts, and the log is read at least once (J-01). processed/replayed count only
             from the worker log or the completion record (QuerySession);
             queue counts never prove a replay ("unverified" otherwise).
  3 api      POST /v1/query (sync, --question-id from
             load-tests/questions-d.txt) twice with one Idempotency-Key, then
             the same key with another body (409 expected).
Then: in flight back to 0, the DLQ alarm state, incident-F.json.

--preflight: read calls only (Terraform outputs, queue settings and counts,
the worker service, alarm states, one registry key and its latest registry
version, one log read, GET /healthz); prints counts and states; sends nothing; writes nothing
outside a private temporary folder (outside the repository, removed at exit).

Files (run folder): events.csv, queue-samples.csv, service-samples.csv,
alarm-samples.csv, clock.csv, incident-F.json, tool-record.json.

Exit codes: 0 ran (each part's outcome is an observation, including "not
seen"); 2 usage; 3 prerequisite not met (nothing sent); 5 tool or AWS failure;
7 the tool's own deadline or TERM.
EOF
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --index)
        [ "$#" -ge 2 ] || cc_die 2 "--index needs a name"
        INDEX="$2"
        shift 2
        ;;
      --question-id)
        [ "$#" -ge 2 ] || cc_die 2 "--question-id needs qNN"
        QUESTION_ID="$2"
        shift 2
        ;;
      --preflight) shift ;;
      *)
        usage >&2
        cc_die 2 "unknown argument: $1"
        ;;
    esac
  done
  local re_index='^[a-z0-9][a-z0-9-]{2,62}$' re_q='^q[0-9]{2}$'
  [[ "${INDEX}" =~ ${re_index} ]] || cc_die 2 "--index must be an index name"
  [[ "${QUESTION_ID}" =~ ${re_q} ]] || cc_die 2 "--question-id must be qNN"
}

# ---- setup ------------------------------------------------------------------------------------

read_names() {
  cc_step "names"
  cc_tf_required CLUSTER '.ecs_cluster_name.value' "ecs_cluster_name"
  cc_tf_required WORKER '.ecs_service_names.value.worker' "ecs_service_names.worker"
  cc_tf_required EMB_URL '.embedding_queue_url.value' "embedding_queue_url"
  cc_tf_required JOB_URL '.job_queue_url.value' "job_queue_url"
  cc_tf_required EMB_DLQ_URL '.dlq_urls.value.embedding' "dlq_urls.embedding (Phase 15 output)"
  cc_tf_required JOB_DLQ_URL '.dlq_urls.value.jobs' "dlq_urls.jobs (Phase 15 output)"
  cc_tf_required REGISTRY_TABLE '.dynamodb_table_names.value.ArtifactRegistry' "dynamodb_table_names.ArtifactRegistry"
  cc_tf_required QS_TABLE '.dynamodb_table_names.value.QuerySession' "dynamodb_table_names.QuerySession"
  cc_tf_required LOG_GROUP '.log_group_names.value.ecs' "log_group_names.ecs"
  cc_api_url API_URL
  MODEL="$(jq -r '.models.embeddingModelId // empty' config/course.json)"
  DIMS="$(jq -r '.embedding.dimensions // empty' config/course.json)"
  [ -n "${MODEL}" ] && [ -n "${DIMS}" ] || cc_die 3 "config/course.json lacks models.embeddingModelId or embedding.dimensions"
  ALARM_DLQ="${CC_NAME_PREFIX}-embedding-dlq-not-empty"
  ALARM_OUT="${CC_NAME_PREFIX}-worker-scale-out"
  ALARM_OUT_EMB="${CC_NAME_PREFIX}-worker-scale-out-embedding"
  ALARM_IN="${CC_NAME_PREFIX}-worker-scale-in"
  cc_info "ECS cluster ${CLUSTER}, worker service ${WORKER}; queue URLs, table names and the API URL read (not shown)"
}

read_settings() {
  cc_step "queue-settings"
  read -r MRC VIS <<<"$(cc_queue_settings "${EMB_URL}")"
  if ! cc_is_uint "${MRC}" || ! cc_is_uint "${VIS}"; then
    cc_die 3 "the embedding queue's maxReceiveCount or VisibilityTimeout could not be read"
  fi
  P1_LIMIT=$((SCALEOUT_ALLOWANCE + (MRC + 1) * RELEASE_CYCLE + DLQ_COUNT_LAG))
  P2_LIMIT=$((SCALEOUT_ALLOWANCE + PROCESS_ALLOWANCE + LOG_DELIVERY_ALLOWANCE))
  CC_LIMITS_JSON="$(jq -n --argjson mrc "${MRC}" --argjson vis "${VIS}" --argjson p1 "${P1_LIMIT}" \
    --argjson p2 "${P2_LIMIT}" --argjson so "${SCALEOUT_ALLOWANCE}" --argjson rc "${RELEASE_CYCLE}" \
    --argjson lag "${DLQ_COUNT_LAG}" --argjson rec "${RECOVERY_LIMIT}" --argjson al "${DLQ_ALARM_LIMIT}" \
    --argjson dl "${TOOL_DEADLINE_SECONDS}" --argjson api "${API_MAX_TIME}" \
    '{embedding_max_receive_count: $mrc, embedding_visibility_timeout_s: $vis,
      part1_poison_limit_s: $p1, part2_each_wait_limit_s: $p2, scaleout_allowance_s: $so,
      release_cycle_s: $rc, dlq_count_lag_s: $lag, recovery_limit_s: $rec, dlq_alarm_limit_s: $al,
      api_max_time_s: $api, tool_deadline_s: $dl,
      basis: "estimates: scale-out from 0 = alarm 60 s + SQS metric delay + Fargate start (infra 5a timeline); poison limit = allowance + (maxReceiveCount + 1) release cycles (release-at-once rule, decision 7.2) + DLQ count lag; WITHOUT release-at-once the poison needs maxReceiveCount x visibility timeout and is reported not seen within this limit; each part 2 wait limit counts from the start of that wait (after part 1 for the first), and the worker log is read at least once per wait (J-01)"}')"
  cc_info "embedding queue: maxReceiveCount ${MRC}, visibility ${VIS}s; part 1 limit ${P1_LIMIT}s, part 2 limits ${P2_LIMIT}s each"
}

question_text() {
  local line
  line="$(grep -E "^${QUESTION_ID}\\|" load-tests/questions-d.txt | head -n 1 || true)"
  [ -n "${line}" ] || cc_die 3 "${QUESTION_ID} is not in load-tests/questions-d.txt"
  printf '%s' "${line#*|*|}"
}

# select_valid_key (M-01): the first unique key of --index in the scan order
# of the ArtifactRegistry table (lib cc_registry_select, COUNT 1), checked
# against its latest registry version (lib cc_registry_latest: textHash,
# embeddingModel, embeddingDimensions present; model and dimensions equal to
# config/course.json). The textHash of that version is the one the completion
# record key uses. The vector's own metadata is checked by the handler, which
# fails the task permanently on a missing field (recorded, never hidden).
select_valid_key() {
  cc_step "select-key"
  local keys="${CC_TMP}/f-key.txt" rc=0
  cc_registry_select "${REGISTRY_TABLE}" "${INDEX}" 1 "${keys}" || rc=$?
  case "${rc}" in
    0) ;;
    3) cc_die 3 "the ArtifactRegistry scan found no vector key of ${INDEX} within its bounds (${CC_REGISTRY_CALLS} calls, ${CC_REGISTRY_SCANNED} items scanned); nothing sent" ;;
    *) cc_die 5 "ArtifactRegistry scan failed (${CC_LAST_AWS_ERROR})" ;;
  esac
  VALID_KEY="$(head -n 1 "${keys}")"
  [ -n "${VALID_KEY}" ] || cc_die 3 "index ${INDEX} returned no vector key"
  cc_registry_latest "${REGISTRY_TABLE}" "${INDEX}" "${VALID_KEY}" "${MODEL}" "${DIMS}" ||
    cc_die 5 "ArtifactRegistry latest-version query failed (${CC_LAST_AWS_ERROR})"
  case "${CC_REG_LINE}" in
    "yes "*) ;;
    *) cc_die 3 "the selected key's latest registry version lacks textHash, embeddingModel or embeddingDimensions, or names another model (${CC_REG_LINE}); nothing sent" ;;
  esac
  VALID_TEXT_HASH="${CC_REG_TEXT_HASH}"
  CC_SELECTION_JSON="$(jq -n --arg i "${INDEX}" --argjson calls "${CC_REGISTRY_CALLS}" \
    --argjson scanned "${CC_REGISTRY_SCANNED}" --argjson matched "${CC_REGISTRY_MATCHED}" '
    {index: $i, source: "DynamoDB ArtifactRegistry (M-01: the vector bucket policy denies the S3 Vectors data plane to this principal)",
     rule: "the first unique pk artifact#vector#INDEX/KEY in scan order (bounded read-only scan, begins_with(pk), projection pk), then the latest version (Query ScanIndexForward=false Limit 1)",
     sampled: 1, eligible: 1, scan_calls: $calls, items_scanned: $scanned, items_matched: $matched,
     checked: "textHash, embeddingModel, embeddingDimensions present in the latest registry version; model and dimensions equal to config/course.json; the vector metadata itself is checked by the handler (permanent failure on a missing field)"}')"
  cc_info "one vector key of ${INDEX} selected from the registry; latest version fields present, model ok"
}

# ---- samples ------------------------------------------------------------------------------------

sample_once() {
  cc_sample_queue queue-samples.csv embedding "${EMB_URL}"
  cc_sample_queue queue-samples.csv embedding-dlq "${EMB_DLQ_URL}"
  cc_sample_queue queue-samples.csv job "${JOB_URL}"
  cc_sample_queue queue-samples.csv job-dlq "${JOB_DLQ_URL}"
  cc_sample_service service-samples.csv "${CLUSTER}" "${WORKER}"
  cc_sample_alarms alarm-samples.csv "${ALARM_DLQ}" "${ALARM_OUT}" "${ALARM_OUT_EMB}" "${ALARM_IN}"
}

event() {
  # event PART SOURCE EVENT TASK_ID OUTCOME CODE
  cc_row events.csv "$(cc_now),$1,$2,$3,$4,$5,$6"
}
event_at() {
  # event_at EPOCH PART SOURCE EVENT TASK_ID OUTCOME CODE
  cc_row events.csv "$1,$2,$3,$4,$5,$6,$7"
}

baseline() {
  cc_step "baseline"
  local ev ei edv edi jv ji jdv jdi svc dlq_state
  read -r ev ei <<<"$(cc_queue_counts "${EMB_URL}")"
  read -r edv edi <<<"$(cc_queue_counts "${EMB_DLQ_URL}")"
  read -r jv ji <<<"$(cc_queue_counts "${JOB_URL}")"
  read -r jdv jdi <<<"$(cc_queue_counts "${JOB_DLQ_URL}")"
  cc_is_uint "${edv}" || cc_die 3 "the embedding DLQ count could not be read"
  svc="${CC_TMP}/svc.json"
  cc_aws_read "${svc}" ecs describe-services --cluster "${CLUSTER}" --services "${WORKER}" ||
    cc_die 5 "describe-services failed (${CC_LAST_AWS_ERROR})"
  dlq_state="$(cc_alarm_state "${ALARM_DLQ}")"
  CC_BASELINE_JSON="$(jq -n --arg ev "${ev}" --arg ei "${ei}" --arg edv "${edv}" --arg edi "${edi}" \
    --arg jv "${jv}" --arg ji "${ji}" --arg jdv "${jdv}" --arg jdi "${jdi}" --arg al "${dlq_state}" \
    --slurpfile s "${svc}" --argjson t "$(cc_now)" '
    def n: if test("^[0-9]+$") then tonumber else null end;
    {epoch_s: $t,
     embedding: {visible: ($ev | n), in_flight: ($ei | n)},
     embedding_dlq: {visible: ($edv | n), in_flight: ($edi | n)},
     job: {visible: ($jv | n), in_flight: ($ji | n)},
     job_dlq: {visible: ($jdv | n), in_flight: ($jdi | n)},
     worker: ($s[0].services[0] // {} | {desired: .desiredCount, running: .runningCount, pending: .pendingCount}),
     embedding_dlq_alarm_state: (if $al == "" then null else $al end)}')"
  printf 'BASELINE: embedding visible=%s in_flight=%s; embedding-dlq visible=%s; job visible=%s in_flight=%s; job-dlq visible=%s; worker %s; dlq alarm %s\n' \
    "${ev}" "${ei}" "${edv}" "${jv}" "${ji}" "${jdv}" \
    "$(jq -r '.worker | "desired=\(.desired) running=\(.running) pending=\(.pending)"' <<<"${CC_BASELINE_JSON}")" \
    "${dlq_state:-unreadable}"
}

# ---- sending -------------------------------------------------------------------------------------

task_body() {
  jq -cn --arg s "${TASK_SCHEMA}" --arg t "$1" --arg i "$2" --arg k "$3" \
    '{schema: $s, taskId: $t, index: $i, vectorKey: $k}'
}

# send_task PART EVENT TASK_ID INDEX KEY: one send-message (never retried).
send_task() {
  local out="${CC_TMP}/send.json" body rc=0
  body="$(task_body "$3" "$4" "$5")"
  cc_aws_raw "${out}" sqs send-message --queue-url "${EMB_URL}" --message-body "${body}" || rc=$?
  if [ "${rc}" -ne 0 ] || [ -z "$(jq -r '.MessageId // empty' "${out}" 2>/dev/null)" ]; then
    event "$1" collector "$2" "$3" send-failed "${CC_LAST_AWS_ERROR:-no-message-id}"
    cc_die 5 "send-message failed for ${3} (${CC_LAST_AWS_ERROR:-no MessageId})"
  fi
  event "$1" collector "$2" "$3" sent ""
  printf 'SENT: part=%s event=%s task=%s\n' "$1" "$2" "$3"
}

# ---- part 1: poison ------------------------------------------------------------------------------

POISON_SENT_AT=0
DLQ_ARRIVED_AT=""

part_poison_wait() {
  cc_step "part1-poison"
  local base deadline v i out found=""
  base="$(jq -r '.embedding_dlq.visible' <<<"${CC_BASELINE_JSON}")"
  deadline=$((POISON_SENT_AT + P1_LIMIT))
  while [ "$(cc_now)" -le "${deadline}" ]; do
    read -r v _ <<<"$(cc_queue_counts "${EMB_DLQ_URL}")"
    if cc_is_uint "${v}" && [ "${v}" -ge $((base + 1)) ]; then
      DLQ_ARRIVED_AT="$(cc_now)"
      event poison sqs dlq-count "${POISON_TID}" "dlq-count-baseline-plus-1" "visible=${v}"
      break
    fi
    sleep "${POLL_SECONDS}"
  done
  if [ -z "${DLQ_ARRIVED_AT}" ]; then
    event poison collector wait-limit "${POISON_TID}" not-seen "limit=${P1_LIMIT}s"
    cc_set_str CC_OBS_JSON part1_poison "not seen within ${P1_LIMIT}s (DLQ count never reached baseline + 1)"
    return 0
  fi
  out="${CC_TMP}/dlq.json"
  i=0
  while [ "${i}" -lt "${DLQ_RECEIVE_TRIES}" ] && [ -z "${found}" ]; do
    i=$((i + 1))
    if cc_aws_raw "${out}" sqs receive-message --queue-url "${EMB_DLQ_URL}" --max-number-of-messages 10 \
      --visibility-timeout 0 --wait-time-seconds 2; then
      found="$(jq -r --arg t "${POISON_TID}" '[.Messages[]?.Body | (fromjson? // {}) | .taskId? // empty
        | select(. == $t)] | .[0] // empty' "${out}" 2>/dev/null || true)"
    else
      cc_error "dlq-receive-${CC_LAST_AWS_ERROR}"
    fi
    : >"${out}"
  done
  if [ -n "${found}" ]; then
    event poison sqs dlq-receive "${POISON_TID}" dlq-received ""
    cc_set_str CC_OBS_JSON part1_poison "seen: DLQ count baseline + 1 and the poison taskId read back from the DLQ (never deleted)"
  else
    event poison sqs dlq-receive "${POISON_TID}" dlq-taskid-not-read "tries=${DLQ_RECEIVE_TRIES}"
    cc_set_str CC_OBS_JSON part1_poison "partial: DLQ count baseline + 1, but the poison taskId was not among the messages read back"
  fi
}

# ---- part 2: sequential replay -------------------------------------------------------------------

LOGS_READABLE="unknown"
SEEN_PROCESSED=""
SEEN_REPLAYED=""
SEEN_KEYS=""

# log_outcomes TASK_ID SINCE_EPOCH: reads the worker's log lines for TASK_ID
# and records each new (time, outcome) once; prints nothing.
log_outcomes() {
  local tid="$1" since="$2" out="${CC_TMP}/logs.json" line t o
  if ! cc_aws_raw "${out}" logs filter-log-events --log-group-name "${LOG_GROUP}" \
    --log-stream-name-prefix "${CC_WORKER_LOG_STREAM_PREFIX}" --start-time "$((since * 1000))" \
    --max-items 2000; then
    LOGS_READABLE="no"
    cc_error "worker-log-${CC_LAST_AWS_ERROR}"
    return 0
  fi
  LOGS_READABLE="yes"
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    t="${line%% *}"
    o="${line##* }"
    [ "$(printf '%s' "${line}" | cut -d' ' -f2)" = "${tid}" ] || continue
    case " ${SEEN_KEYS} " in
      *" ${t}:${o} "*) continue ;;
    esac
    SEEN_KEYS="${SEEN_KEYS} ${t}:${o}"
    event_at "${t}" replay worker-log delivery "${tid}" "${o}" ""
    case "${o}" in
      processed) SEEN_PROCESSED="${SEEN_PROCESSED:-${t}}" ;;
      replayed) SEEN_REPLAYED="${SEEN_REPLAYED:-${t}}" ;;
    esac
  done < <(jq -r --arg prefix "${tid}" --arg tf "${CC_WORKER_LOG_TASK_FIELD}" \
    --arg of "${CC_WORKER_LOG_OUTCOME_FIELD}" "${CC_WORKER_LOG_JQ}" "${out}" 2>/dev/null || true)
}

# completion_record: "present EPOCH", "absent" or "unreadable" for
# (REPLAY_TID, the vector's textHash) in QuerySession (storage QS6 key rule:
# pk = reembed#sha256(canonical(taskId, textHash)), sk = reembed).
completion_record() {
  local canon pk key out="${CC_TMP}/ddb.json"
  [ -n "${VALID_TEXT_HASH}" ] || {
    printf 'unreadable\n'
    return 0
  }
  canon="${#REPLAY_TID}:${REPLAY_TID}${#VALID_TEXT_HASH}:${VALID_TEXT_HASH}"
  pk="reembed#$(printf '%s' "${canon}" | if command -v shasum >/dev/null 2>&1; then shasum -a 256; else sha256sum; fi | cut -d' ' -f1)"
  key="$(jq -cn --arg pk "${pk}" '{pk: {S: $pk}, sk: {S: "reembed"}}')"
  if ! cc_aws_read "${out}" dynamodb get-item --table-name "${QS_TABLE}" --key "${key}" \
    --projection-expression completedAt --consistent-read; then
    cc_error "completion-record-${CC_LAST_AWS_ERROR}"
    printf 'unreadable\n'
    return 0
  fi
  jq -r 'if .Item == null then "absent" else "present \(.Item.completedAt.N // "")" end' "${out}" 2>/dev/null ||
    printf 'unreadable\n'
}

# wait_outcome WANT SENT_AT: polls the worker log (and, for "processed", the
# completion record) until WANT is seen or the wait limit passes.
# J-01 (repair-01): the limit P2_LIMIT counts from when THIS wait starts,
# max(SENT_AT, now), not from SENT_AT, and the log is read at least once
# (do-while loop: the deadline is checked only after a read). The log query
# still starts at SENT_AT - 60 s, so a line written while part 1 ran is found.
# Trace of the reviewer's case (P1_LIMIT 960 s, P2_LIMIT 720 s): poison sent
# at T, replay sent at T+1 (SENT_AT); the worker logs "processed" at T+400;
# part 1 ends at T+960 (DLQ count lag). Before the fix: deadline T+721 < now
# T+960, zero reads, "processed not seen", the duplicate was never sent.
# After: wait start T+960, deadline T+1680; the first read (start time
# T+1-60) returns the T+400 line, SEEN_PROCESSED = T+400, return 0 at about
# T+961; the duplicate is sent and "replayed" gets its own 720 s from its
# own wait start. The wait-start rows in events.csv record each start and
# deadline.
wait_outcome() {
  local want="$1" sent="$2" start deadline cr
  start="$(cc_now)"
  [ "${start}" -ge "${sent}" ] || start="${sent}"
  deadline=$((start + P2_LIMIT))
  event replay collector "wait-start-${want}" "${REPLAY_TID}" waiting "limit=${P2_LIMIT}s;deadline=${deadline}"
  while :; do
    log_outcomes "${REPLAY_TID}" "$((sent - 60))"
    if [ "${want}" = "processed" ] && [ -n "${SEEN_PROCESSED}" ]; then return 0; fi
    if [ "${want}" = "replayed" ] && [ -n "${SEEN_REPLAYED}" ]; then return 0; fi
    if [ "${want}" = "processed" ] && [ "${LOGS_READABLE}" = "no" ]; then
      cr="$(completion_record)"
      case "${cr}" in
        present*)
          event replay completion-record present "${REPLAY_TID}" processed "completedAt=${cr#present }"
          SEEN_PROCESSED="$(cc_now)"
          return 0
          ;;
      esac
    fi
    [ "$(cc_now)" -lt "${deadline}" ] || break
    sleep "${LOG_POLL_SECONDS}"
  done
  return 1
}

part_replay() {
  cc_step "part2-replay"
  local sent2 cr
  if wait_outcome processed "${REPLAY_SENT_AT}"; then
    cr="$(completion_record)"
    case "${cr}" in
      present*) event replay completion-record present "${REPLAY_TID}" processed "completedAt=${cr#present }" ;;
      absent) event replay completion-record absent "${REPLAY_TID}" no-record "" ;;
      *) event replay completion-record unreadable "${REPLAY_TID}" unreadable "" ;;
    esac
    send_task replay sent-again "${REPLAY_TID}" "${INDEX}" "${VALID_KEY}"
    sent2="$(cc_now)"
    if [ "${LOGS_READABLE}" = "no" ]; then
      event replay collector replay-check "${REPLAY_TID}" unverified "worker-log-unreadable"
      cc_set_str CC_OBS_JSON part2_replay "processed seen (completion record); replay unverified: the worker log could not be read (queue counts never prove a replay)"
    elif wait_outcome replayed "${sent2}"; then
      cc_set_str CC_OBS_JSON part2_replay "seen: worker log 'processed', then after the second send 'replayed'"
    else
      event replay collector wait-limit "${REPLAY_TID}" replayed-not-seen "limit=${P2_LIMIT}s"
      cc_set_str CC_OBS_JSON part2_replay "processed seen; 'replayed' not seen within ${P2_LIMIT}s of the start of its wait (after the second send)"
    fi
  else
    event replay collector wait-limit "${REPLAY_TID}" processed-not-seen "limit=${P2_LIMIT}s"
    cc_set_str CC_OBS_JSON part2_replay "not seen: 'processed' not seen within ${P2_LIMIT}s of the start of its wait (log readable: ${LOGS_READABLE}; log read from 60 s before the first send); the duplicate was NOT sent"
  fi
  cc_set_str CC_EVIDENCE_JSON worker_log "${LOGS_READABLE}"
}

# ---- part 3: API idempotency -----------------------------------------------------------------------

part_api() {
  cc_step "part3-api"
  local q key b1="${CC_TMP}/q1.json" b2="${CC_TMP}/q2.json" body="${CC_TMP}/ans.json" hdr="${CC_TMP}/ans.hdr"
  local n line rc code ttot replayed err qid qid1="" results=""
  q="$(question_text)"
  key="p15f-${CC_ATTEMPT}-idem"
  jq -n --arg q "${q}" '{question: $q, mode: "sync"}' >"${b1}"
  jq -n --arg q "${q} Answer in one sentence." '{question: $q, mode: "sync"}' >"${b2}"
  for n in first replay conflict; do
    local bf="${b1}"
    [ "${n}" = "conflict" ] && bf="${b2}"
    line="$(cc_api_send "${API_URL}" POST /v1/query "${API_MAX_TIME}" "${bf}" "${key}" "${body}" "${hdr}")"
    read -r rc code ttot <<<"${line}"
    replayed="$(grep -i -E '^idempotent-replayed:' "${hdr}" 2>/dev/null | tail -n 1 | cut -d':' -f2 | tr -d ' \r' || true)"
    err="$(jq -r '.error // empty' "${body}" 2>/dev/null || true)"
    [[ "${err}" =~ ^[A-Z][A-Z0-9_]{0,63}$ ]] || err=""
    qid="$(jq -r '.queryId // empty' "${body}" 2>/dev/null || true)"
    if [ "${n}" = "first" ]; then qid1="${qid}"; fi
    local same="-"
    if [ "${n}" = "replay" ] && [ -n "${qid}" ] && [ -n "${qid1}" ]; then
      if [ "${qid}" = "${qid1}" ]; then same="same-query-id"; else same="other-query-id"; fi
    fi
    event api api "${n}" "" "${code}" "curl=${rc};t=${ttot};error=${err:--};Idempotent-Replayed=${replayed:-absent};${same}"
    printf 'API: %s status=%s error=%s idempotent_replayed=%s %s\n' "${n}" "${code}" "${err:--}" "${replayed:-absent}" "${same}"
    results="${results}${n}=${code}/${replayed:-absent} "
    rm -f -- "${body}" "${hdr}"
    sleep 1
  done
  cc_set_str CC_OBS_JSON part3_api "statuses ${results}(expected: first 200, replay 200 with Idempotent-Replayed true, conflict 409 IDEMPOTENCY_CONFLICT)"
}

# ---- recovery and incident -------------------------------------------------------------------------

RECOVERED_AT=""
ALARM_AT=""

recovery() {
  cc_step "recovery"
  local deadline v i st
  deadline=$(($(cc_now) + RECOVERY_LIMIT))
  while [ "$(cc_now)" -le "${deadline}" ]; do
    read -r v i <<<"$(cc_queue_counts "${EMB_URL}")"
    if cc_is_uint "${v}" && cc_is_uint "${i}" && [ $((v + i)) -eq 0 ]; then
      RECOVERED_AT="$(cc_now)"
      event recovery sqs embedding-empty "" in-flight-0 ""
      break
    fi
    sleep "${POLL_SECONDS}"
  done
  [ -n "${RECOVERED_AT}" ] || event recovery collector wait-limit "" embedding-not-empty "limit=${RECOVERY_LIMIT}s"
  if [ -n "${DLQ_ARRIVED_AT}" ]; then
    deadline=$((DLQ_ARRIVED_AT + DLQ_ALARM_LIMIT))
    while :; do
      st="$(cc_alarm_state "${ALARM_DLQ}")"
      if [ "${st}" = "ALARM" ]; then
        ALARM_AT="$(cc_now)"
        event recovery cloudwatch dlq-alarm "" ALARM ""
        break
      fi
      [ "$(cc_now)" -le "${deadline}" ] || break
      sleep "${POLL_SECONDS}"
    done
    [ -n "${ALARM_AT}" ] || event recovery collector wait-limit "" dlq-alarm-not-in-alarm "state=${st:-unreadable}"
  fi
  cc_set_str CC_OBS_JSON recovery "$(printf 'embedding in flight back to 0: %s; DLQ alarm ALARM: %s; valid task processed while the poison was retried: %s' \
    "${RECOVERED_AT:+yes}" "${ALARM_AT:+yes}" "${SEEN_PROCESSED:+yes (sent right after the poison)}")"
}

write_incident() {
  local timeline
  timeline="$(awk -F, 'NR > 1 {printf "%s{\"epoch_s\":%s,\"part\":\"%s\",\"source\":\"%s\",\"event\":\"%s\",\"outcome\":\"%s\"}", (n++ ? "," : ""), $1, $2, $3, $4, $6}' \
    "${CC_OUT}/events.csv")"
  cc_json_file incident-F.json "$(jq -n --arg id "p15-f-${CC_ATTEMPT}" --argjson start "${POISON_SENT_AT}" \
    --arg det "${ALARM_AT}" --arg dlq "${DLQ_ARRIVED_AT}" --arg rec "${RECOVERED_AT}" \
    --arg ptid "${POISON_TID}" --arg rtid "${REPLAY_TID}" --arg attempt "${CC_ATTEMPT}" --arg mid "${CC_MANIFEST}" \
    --argjson mrc "${MRC}" --argjson obs "${CC_OBS_JSON}" --argjson tl "[${timeline}]" '
    def utc: if . == "" or . == null then null else (tonumber | todateiso8601) end;
    {incidentId: $id,
     startUtc: ($start | todateiso8601),
     endUtc: ($rec | utc),
     detectionSource: (if $det != "" then "CloudWatch alarm on the embedding DLQ (ApproximateNumberOfMessagesVisible > 0), state ALARM at \($det | utc)"
                       elif $dlq != "" then "the collector DLQ count check at \($dlq | utc) (the DLQ alarm was not seen in ALARM within the limit)"
                       else "not detected within the limits" end),
     detectedAtUtc: (if $det != "" then ($det | utc) elif $dlq != "" then ($dlq | utc) else null end),
     userVisibleImpact: "not measured by this tool; the embedding queue serves no API route (design, decision 8.1)",
     traceAndRunIds: {attemptId: $attempt, manifestId: $mid, poisonTaskId: $ptid, replayTaskId: $rtid},
     timeline: $tl,
     rootCause: "injected by experiment F: a valid-JSON embedding-task-v1 message naming an unknown index, a permanent failure by design (decision 7.2)",
     recoveryAction: "none by hand: SQS moves the message to the dead-letter queue after maxReceiveCount (\($mrc)) receives; the worker keeps processing valid tasks",
     dataIntegrityAssessment: "the poison names no existing index, so nothing is written for it; the valid task rewrites one existing vector under its existing key and metadata (storage design, decision 6.3); this tool checks only the completion record presence",
     followUpOwner: "experiment-engineer (analysis, D16); rag-engineer or service-engineer for any part not seen",
     preventiveTestOrControl: "dead-letter queue with maxReceiveCount, the DLQ alarm, the release-at-once rule for permanent failures (decision 7.2), the completion record (decision 6.3) and the ReembedSuite unit tests",
     observation: $obs}')"
}

# ---- preflight ---------------------------------------------------------------------------------------

preflight() {
  cc_step "preflight"
  local out="${CC_TMP}/pf-logs.json" body="${CC_TMP}/pf.json" line rc code ttot
  if cc_aws_raw "${out}" logs filter-log-events --log-group-name "${LOG_GROUP}" \
    --log-stream-name-prefix "${CC_WORKER_LOG_STREAM_PREFIX}" --start-time "$((($(cc_now) - 300) * 1000))" --max-items 1; then
    printf 'PREFLIGHT: worker log readable: yes (events in the last 5 min: %s)\n' "$(jq '[.events[]?] | length' "${out}")"
  else
    printf 'PREFLIGHT: worker log readable: no (%s); part 2 would rely on the completion record and report the replay unverified\n' "${CC_LAST_AWS_ERROR}"
  fi
  case "$(completion_record_probe)" in
    ok) printf 'PREFLIGHT: completion-record table readable: yes\n' ;;
    *) printf 'PREFLIGHT: completion-record table readable: no\n' ;;
  esac
  line="$(cc_api_send "${API_URL}" GET /healthz 15 "" "" "${body}" "${CC_TMP}/pf.hdr")"
  read -r rc code ttot <<<"${line}"
  printf 'PREFLIGHT: GET /healthz status=%s curl=%s time_total=%s\n' "${code}" "${rc}" "${ttot}"
  printf 'PREFLIGHT: question %s found in load-tests/questions-d.txt (%s characters)\n' "${QUESTION_ID}" "$(question_text | wc -c | tr -d ' ')"
  printf 'PREFLIGHT: valid key: the first %s key in the ArtifactRegistry scan (%s calls, %s items scanned); latest registry version: %s\n' \
    "${INDEX}" "${CC_REGISTRY_CALLS}" "${CC_REGISTRY_SCANNED}" "${CC_REG_LINE}"
  printf 'PREFLIGHT: limits part1=%ss part2=%ss each (from each wait start), recovery=%ss, dlq alarm=%ss, tool deadline=%ss\n' \
    "${P1_LIMIT}" "${P2_LIMIT}" "${RECOVERY_LIMIT}" "${DLQ_ALARM_LIMIT}" "${TOOL_DEADLINE_SECONDS}"
  printf 'PREFLIGHT: ok (nothing sent, nothing written)\n'
}

completion_record_probe() {
  local out="${CC_TMP}/pf-ddb.json"
  if cc_aws_read "${out}" dynamodb get-item --table-name "${QS_TABLE}" \
    --key '{"pk":{"S":"reembed#p15-f-preflight-probe"},"sk":{"S":"reembed"}}' --projection-expression completedAt; then
    printf 'ok\n'
  else
    printf 'no\n'
  fi
}

# ---- main ----------------------------------------------------------------------------------------------

main() {
  cc_init "${TOOL_NAME}" "${TOOL_PATH}" "$@"
  parse_args "$@"
  cc_prepare_out events.csv queue-samples.csv service-samples.csv alarm-samples.csv clock.csv incident-F.json
  cc_api_key_check
  cc_tf_outputs
  read_names
  read_settings
  select_valid_key
  baseline
  if [ "${CC_PREFLIGHT}" = "true" ]; then
    preflight
    return 0
  fi
  DEADLINE=$(($(cc_now) + TOOL_DEADLINE_SECONDS))
  cc_file events.csv "epoch_s,part,source,event,task_id,outcome,code"
  cc_file queue-samples.csv "epoch_s,queue,visible,in_flight"
  cc_file service-samples.csv "epoch_s,service,desired,running,pending"
  cc_file alarm-samples.csv "epoch_s,alarm,state"
  cc_file clock.csv "phase,local_send_epoch_s,local_recv_epoch_s,http_status,curl_exit,time_total_s,http_date,http_date_epoch_s"
  cc_clock start
  sample_once
  cc_sampler_start "${TOOL_DEADLINE_SECONDS}" sample_once
  POISON_TID="p15-f-poison-${CC_ATTEMPT}"
  REPLAY_TID="p15-f-replay-${CC_ATTEMPT}"
  POISON_SENT_AT="$(cc_now)"
  send_task poison sent "${POISON_TID}" "${POISON_INDEX}" "${VALID_KEY}"
  REPLAY_SENT_AT="$(cc_now)"
  send_task replay sent "${REPLAY_TID}" "${INDEX}" "${VALID_KEY}"
  part_poison_wait
  part_replay
  part_api
  recovery
  cc_bg_stop
  sample_once
  cc_sampler_merge_errors
  cc_clock end
  cc_set CC_COUNTS_JSON messages_sent "$(grep -c ',collector,sent' "${CC_OUT}/events.csv" || true)"
  cc_set CC_EVIDENCE_JSON processed_seen "$([ -n "${SEEN_PROCESSED}" ] && printf true || printf false)"
  cc_set CC_EVIDENCE_JSON replayed_seen "$([ -n "${SEEN_REPLAYED}" ] && printf true || printf false)"
  cc_set CC_EVIDENCE_JSON dlq_received "$(grep -q ',dlq-received,' "${CC_OUT}/events.csv" && printf true || printf false)"
  write_incident
  CC_STATUS="completed"
  [ "$(cc_now)" -le "${DEADLINE}" ] || cc_warn "the run passed its own deadline during the last step"
}

REPLAY_SENT_AT=0
main "$@"
