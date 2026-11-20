#!/usr/bin/env bash
# experiments/collectors/c-embedding-backlog.sh - experiment C (embedding
# backlog): N existing documents enqueued for re-embedding under their existing
# keys; observes queue depth, worker desired/running tasks, scaling activities,
# drain and scale-in (Phase 15 work order, decision 11 item 4).
# Owner: experiment-engineer. A "command" action of scripts/run-experiment.sh;
# run by the human only, never by an agent.
#
# runtime: untested
# static: shellcheck (6b report); bash 3.2 constructs only.

set -Eeuo pipefail
umask 077

readonly TOOL_NAME="c-embedding-backlog.sh"
readonly TOOL_PATH="experiments/collectors/c-embedding-backlog.sh"
COLLECTOR_DIR="$(cd "$(dirname "$0")" && pwd -P)"
# shellcheck source=experiments/collectors/lib/collector-common.sh
. "${COLLECTOR_DIR}/lib/collector-common.sh"

# Fixed interface (decision 7.2): message embedding-task-v1, exactly these
# four fields: schema, taskId, index, vectorKey.
readonly TASK_SCHEMA="embedding-task-v1"
readonly MAX_COUNT=20000
readonly SAMPLE_KEYS=20
# Preconditions (decision 11.4): poll every 30 s for at most 15 minutes.
readonly SETTLE_POLL_SECONDS=30
readonly SETTLE_LIMIT_SECONDS=900
readonly WAIT_POLL_SECONDS=15
readonly SEND_RETRIES=2
readonly LOG_COUNT_LIMIT_SECONDS=300

# ---- expected stored embedding model ID (repair-D10, finding L-02) --------------------------
# The registry's embeddingModel is the full spec ID, not models.embeddingModelId.
# rag EmbeddingModelSpec.id (modules/rag/src/main/scala/org/scholarchain/rag/embedding/
# EmbeddingModelSpec.scala:35) is "<provider.wire>/<model>/<dimensions>/<inputType.wire>/<specVersion>",
# for example voyage/voyage-4/1024/document/emb-v1 (:18-19). Built from config by fromConfig
# (:113-118): provider.wire = embedding.provider (core ScholarChainConfig.scala:191-195, exact
# match, Enums.scala:19-22), model = models.embeddingModelId, dimensions = embedding.dimensions.
# Stored vectors are document-side (:25; InputType.Document.wire "document" at :11; the ingest
# and the re-embed handler create the embedder with InputType.Document: RagIngestJob.scala:453,
# ReembedHandler.scala:347). specVersion is the code constant EmbeddingModelSpec.SpecVersion
# "emb-v1" (:52), not a config value: it must change here when rag bumps it. Storage records
# embedder.modelId = spec.id (VoyageEmbedder.scala:78, BedrockTitanEmbedder.scala:75,
# FakeLexicalEmbedder.scala:23; VectorIngestPipeline.scala:518, VectorReembedder.scala:367).
readonly EMB_INPUT_TYPE="document"
readonly EMB_SPEC_VERSION="emb-v1"

COUNT=""
INDEX="researcher-profile-v1"
WAIT_LIMIT=""
CLUSTER=""
WORKER=""
EMB_URL=""
JOB_URL=""
EMB_DLQ_URL=""
JOB_DLQ_URL=""
REGISTRY_TABLE=""
LOG_GROUP=""
EMB_PROVIDER=""
EMB_MODEL_NAME=""
MODEL=""
DIMS=""
ALARM_DLQ=""
ALARM_OUT=""
ALARM_OUT_EMB=""
ALARM_IN=""
TASK_PREFIX=""
FIRST_SEND=""
LAST_SEND=""
SENT_OK=0
SEND_FAILED=0
SEND_BROKEN="false"

usage() {
  cat <<'EOF'
Usage: experiments/collectors/c-embedding-backlog.sh --count N --wait-limit-seconds S [--index NAME] [--preflight]

Experiment C (Phase 15 decision 11 item 4). Run from the repository root by
scripts/run-experiment.sh (SCHOLARCHAIN_EXPERIMENT_RUN_DIR, _ATTEMPT_ID,
_MANIFEST_ID).

  --count N               documents to re-embed (1..20000); the manifest sets
                          it (6b provisional, 6c final)
  --wait-limit-seconds S  wait for drain and scale-in after the first send
                          (60..14400; the manifest sets it with N)
  --index NAME            the index whose first N keys are re-embedded
                          (default researcher-profile-v1)

Steps: (1) preconditions with a bounded settle: every 30 s for at most
15 minutes until both queues are empty (visible plus in flight 0), the worker
is at desired 0 and running 0, and no scaling activity is in progress; each
poll is recorded with the unmet condition and why it should clear; past the
limit exit 3 with nothing sent (never purges a queue, never changes a
service). (2) baseline (embedding DLQ count included). (3) selection (M-01:
never the S3 Vectors data plane, which the vector bucket policy denies to
this principal): the first N unique keys of the index in the scan order of
the DynamoDB ArtifactRegistry table (bounded read-only scan,
begins_with(pk, "artifact#vector#INDEX/"), projection pk, page size 1000,
duplicate versions of one key dropped); exactly N unique keys or exit 3; for
20 evenly spaced keys the latest registry version is read (Query, newest
first, Limit 1) and checked for textHash, embeddingModel and
embeddingDimensions and the configured model; any miss: exit 3, nothing
sent. The vector metadata itself is checked by the handler (a missing field
is a permanent failure, counted as a workload failure). (4) N messages
embedding-task-v1 (taskId p15-c-ATTEMPT-I) with send-message-batch, 10 per
call. (5) every 15 s: the four queues, the worker's desired/running/pending,
the four worker and DLQ alarm states, until the embedding queue is empty and
the worker is back at 0 (or the wait limit: exit 7). (6) the worker's scaling
activities (time, direction, status code) and the worker log outcome counts
for this attempt's task IDs.

--preflight: read calls only (Terraform outputs, queue settings and counts,
the worker service, scaling activities, the ArtifactRegistry scan for the N
keys and the 20 latest-version reads, one log read); prints counts and states; sends nothing; writes
nothing outside a private temporary folder (outside the repository).

Files (run folder): settle.csv, queue-samples.csv, service-samples.csv,
alarm-samples.csv, scaling-activities.csv, sends.csv, sample-check.csv,
selected-keys.txt, worker-outcomes.csv, clock.csv, tool-record.json.

Exit codes: 0 ran (the observed sequence is the result, whatever it is);
2 usage; 3 prerequisite not met (settle limit, fewer than N unique registry
keys, a sampled latest registry version incomplete or of another model:
nothing sent); 5 tool or AWS failure (also a send call that
failed part-way: the run is observed to its end, then exits 5); 7 the wait
limit or TERM (remote state recorded).
EOF
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --count)
        [ "$#" -ge 2 ] || cc_die 2 "--count needs a number"
        COUNT="$2"
        shift 2
        ;;
      --wait-limit-seconds)
        [ "$#" -ge 2 ] || cc_die 2 "--wait-limit-seconds needs a number"
        WAIT_LIMIT="$2"
        shift 2
        ;;
      --index)
        [ "$#" -ge 2 ] || cc_die 2 "--index needs a name"
        INDEX="$2"
        shift 2
        ;;
      --preflight) shift ;;
      *)
        usage >&2
        cc_die 2 "unknown argument: $1"
        ;;
    esac
  done
  cc_is_uint "${COUNT}" && [ "${COUNT}" -ge 1 ] && [ "${COUNT}" -le "${MAX_COUNT}" ] ||
    cc_die 2 "--count must be 1..${MAX_COUNT}"
  cc_is_uint "${WAIT_LIMIT}" && [ "${WAIT_LIMIT}" -ge 60 ] && [ "${WAIT_LIMIT}" -le 14400 ] ||
    cc_die 2 "--wait-limit-seconds must be 60..14400"
  local re_index='^[a-z0-9][a-z0-9-]{2,62}$'
  [[ "${INDEX}" =~ ${re_index} ]] || cc_die 2 "--index must be an index name"
}

# expected_model_id (L-02): sets EMB_PROVIDER, EMB_MODEL_NAME, DIMS and MODEL,
# the document-side spec ID storage writes as embeddingModel (see the
# EMB_SPEC_VERSION comment above); exit 3 when config/course.json lacks a part.
expected_model_id() {
  local re_dims='^[1-9][0-9]*$'
  EMB_PROVIDER="$(jq -r '.embedding.provider // empty' config/course.json)"
  EMB_MODEL_NAME="$(jq -r '.models.embeddingModelId // empty' config/course.json)"
  DIMS="$(jq -r '.embedding.dimensions // empty' config/course.json)"
  [ -n "${EMB_PROVIDER}" ] && [ -n "${EMB_MODEL_NAME}" ] && [ -n "${DIMS}" ] ||
    cc_die 3 "config/course.json lacks embedding.provider, models.embeddingModelId or embedding.dimensions"
  case "${EMB_PROVIDER}" in
    voyage | bedrock | fake) ;;
    *) cc_die 3 "config/course.json embedding.provider '${EMB_PROVIDER}' is not voyage, bedrock or fake" ;;
  esac
  [[ "${DIMS}" =~ ${re_dims} ]] || cc_die 3 "config/course.json embedding.dimensions is not a positive integer"
  MODEL="${EMB_PROVIDER}/${EMB_MODEL_NAME}/${DIMS}/${EMB_INPUT_TYPE}/${EMB_SPEC_VERSION}"
  cc_info "expected stored embeddingModel ${MODEL}, embeddingDimensions ${DIMS} (config/course.json + EmbeddingModelSpec.id)"
}

read_names() {
  cc_step "names"
  cc_tf_required CLUSTER '.ecs_cluster_name.value' "ecs_cluster_name"
  cc_tf_required WORKER '.ecs_service_names.value.worker' "ecs_service_names.worker"
  cc_tf_required EMB_URL '.embedding_queue_url.value' "embedding_queue_url"
  cc_tf_required JOB_URL '.job_queue_url.value' "job_queue_url"
  cc_tf_required EMB_DLQ_URL '.dlq_urls.value.embedding' "dlq_urls.embedding (Phase 15 output)"
  cc_tf_required JOB_DLQ_URL '.dlq_urls.value.jobs' "dlq_urls.jobs (Phase 15 output)"
  cc_tf_required REGISTRY_TABLE '.dynamodb_table_names.value.ArtifactRegistry' "dynamodb_table_names.ArtifactRegistry"
  cc_tf_required LOG_GROUP '.log_group_names.value.ecs' "log_group_names.ecs"
  expected_model_id
  ALARM_DLQ="${CC_NAME_PREFIX}-embedding-dlq-not-empty"
  ALARM_OUT="${CC_NAME_PREFIX}-worker-scale-out"
  ALARM_OUT_EMB="${CC_NAME_PREFIX}-worker-scale-out-embedding"
  ALARM_IN="${CC_NAME_PREFIX}-worker-scale-in"
  TASK_PREFIX="p15-c-${CC_ATTEMPT}-"
  CC_LIMITS_JSON="$(jq -n --argjson n "${COUNT}" --argjson w "${WAIT_LIMIT}" --argjson s "${SETTLE_LIMIT_SECONDS}" \
    --argjson sp "${SETTLE_POLL_SECONDS}" --argjson wp "${WAIT_POLL_SECONDS}" --argjson k "${SAMPLE_KEYS}" \
    --argjson max "${MAX_COUNT}" --argjson lc "${LOG_COUNT_LIMIT_SECONDS}" \
    '{count: $n, max_count: $max, wait_limit_s: $w, settle_limit_s: $s, settle_poll_s: $sp,
      sample_poll_s: $wp, registry_sample_keys: $k, log_count_limit_s: $lc,
      batch_size: 10, note: "N and the wait limit are manifest arguments (6b provisional, 6c final)"}')"
  cc_info "ECS cluster ${CLUSTER}, worker ${WORKER}, index ${INDEX}, N ${COUNT}, wait limit ${WAIT_LIMIT}s; URLs and table names read (not shown)"
}

# ---- preconditions ------------------------------------------------------------------------------

# settle_poll: one poll; sets SETTLE_RESULT ("MET" or "UNMET reason;reason")
# and LAST_STATE; one settle.csv row. Called directly (not in a command
# substitution), so both variables reach the caller.
LAST_STATE=""
SETTLE_RESULT=""
settle_poll() {
  local ev ei jv ji svc d r p acts inprog="x" now reasons=""
  now="$(cc_now)"
  read -r ev ei <<<"$(cc_queue_counts "${EMB_URL}")"
  read -r jv ji <<<"$(cc_queue_counts "${JOB_URL}")"
  svc="${CC_TMP}/settle-svc.json"
  d="x"
  r="x"
  p="x"
  if cc_aws_read "${svc}" ecs describe-services --cluster "${CLUSTER}" --services "${WORKER}"; then
    read -r d r p <<<"$(jq -r '.services[0] // {} | "\(.desiredCount // "x") \(.runningCount // "x") \(.pendingCount // "x")"' "${svc}")"
  fi
  acts="${CC_TMP}/settle-aas.json"
  if cc_aws_read "${acts}" application-autoscaling describe-scaling-activities --service-namespace ecs \
    --resource-id "service/${CLUSTER}/${WORKER}" --scalable-dimension ecs:service:DesiredCount --max-items 10; then
    inprog="$(jq -r '[.ScalingActivities[]? | select(.StatusCode == "InProgress" or .StatusCode == "Pending")] | length' "${acts}")"
  fi
  cc_is_uint "${ev}" && cc_is_uint "${ei}" || reasons="${reasons}embedding-unreadable(read error; retried next poll);"
  cc_is_uint "${jv}" && cc_is_uint "${ji}" || reasons="${reasons}job-unreadable(read error; retried next poll);"
  if cc_is_uint "${ev}" && [ "${ev}" -gt 0 ]; then reasons="${reasons}embedding-visible(the worker scales out and drains it);"; fi
  if cc_is_uint "${ei}" && [ "${ei}" -gt 0 ]; then reasons="${reasons}embedding-in-flight(a message still in flight; returns after its visibility timeout or is deleted);"; fi
  if cc_is_uint "${jv}" && [ "${jv}" -gt 0 ]; then reasons="${reasons}job-visible(a queued job; the worker drains it);"; fi
  if cc_is_uint "${ji}" && [ "${ji}" -gt 0 ]; then reasons="${reasons}job-in-flight(a job still leased);"; fi
  if ! cc_is_uint "${d}" || ! cc_is_uint "${r}"; then
    reasons="${reasons}worker-unreadable(read error; retried next poll);"
  else
    if [ "${d}" -gt 0 ]; then reasons="${reasons}worker-desired>0(scale-in still within its 5 minutes at zero plus alarm lag);"; fi
    if [ "${r}" -gt 0 ]; then reasons="${reasons}worker-running>0(tasks stopping, stopTimeout up to 120 s);"; fi
  fi
  if ! cc_is_uint "${inprog}"; then
    reasons="${reasons}scaling-activities-unreadable(read error; retried next poll);"
  elif [ "${inprog}" -gt 0 ]; then
    reasons="${reasons}scaling-in-progress(an activity is still running);"
  fi
  LAST_STATE="embedding=${ev}/${ei} job=${jv}/${ji} worker=${d}/${r}/${p} scaling_in_progress=${inprog}"
  cc_row settle.csv "${now},${ev},${ei},${jv},${ji},${d},${r},${p},${inprog},$([ -z "${reasons}" ] && printf yes || printf no),${reasons%;}"
  if [ -z "${reasons}" ]; then
    SETTLE_RESULT="MET"
  else
    SETTLE_RESULT="UNMET ${reasons%;}"
  fi
}

settle() {
  cc_step "preconditions"
  local deadline result polls=0 max
  deadline=$(($(cc_now) + SETTLE_LIMIT_SECONDS))
  max=$((SETTLE_LIMIT_SECONDS / SETTLE_POLL_SECONDS + 2))
  while [ "${polls}" -lt "${max}" ]; do
    polls=$((polls + 1))
    settle_poll
    result="${SETTLE_RESULT}"
    printf 'SETTLE: poll=%s %s (%s)\n' "${polls}" "${result%% *}" "${LAST_STATE}"
    if [ "${result}" = "MET" ]; then
      cc_set CC_COUNTS_JSON settle_polls "${polls}"
      return 0
    fi
    [ "$(cc_now)" -lt "${deadline}" ] || break
    sleep "${SETTLE_POLL_SECONDS}"
  done
  cc_set CC_COUNTS_JSON settle_polls "${polls}"
  CC_STATUS="prerequisite-not-met"
  cc_set_str CC_OBS_JSON result "not run: preconditions not met within ${SETTLE_LIMIT_SECONDS}s (last unmet: ${result#UNMET })"
  cc_die 3 "preconditions not met within ${SETTLE_LIMIT_SECONDS}s; nothing sent (temporary unmet prerequisite: a later attempt is a new run)"
}

baseline() {
  cc_step "baseline"
  local edv edi jdv jdi st
  read -r edv edi <<<"$(cc_queue_counts "${EMB_DLQ_URL}")"
  read -r jdv jdi <<<"$(cc_queue_counts "${JOB_DLQ_URL}")"
  st="$(cc_alarm_state "${ALARM_DLQ}")"
  CC_BASELINE_JSON="$(jq -n --arg edv "${edv}" --arg edi "${edi}" --arg jdv "${jdv}" --arg jdi "${jdi}" \
    --arg st "${st}" --arg state "${LAST_STATE}" --argjson t "$(cc_now)" '
    def n: if test("^[0-9]+$") then tonumber else null end;
    {epoch_s: $t, embedding_dlq: {visible: ($edv | n), in_flight: ($edi | n)},
     job_dlq: {visible: ($jdv | n), in_flight: ($jdi | n)},
     embedding_dlq_alarm_state: (if $st == "" then null else $st end),
     settled_state: $state,
     note: "after experiment F the embedding DLQ is not 0; C compares with this baseline"}')"
  printf 'BASELINE: embedding-dlq visible=%s; job-dlq visible=%s; dlq alarm %s; %s\n' "${edv}" "${jdv}" "${st:-unreadable}" "${LAST_STATE}"
}

# ---- selection ------------------------------------------------------------------------------------

# select_keys (M-01, repair-01): the first N unique keys of the index in the
# scan order of the DynamoDB ArtifactRegistry table (lib cc_registry_select:
# bounded read-only scan, begins_with(pk, "artifact#vector#INDEX/"),
# projection pk, duplicates of one key dropped). Never the S3 Vectors data
# plane: the vector bucket policy denies it to this principal. Exactly N
# unique keys or exit 3, nothing sent.
KEYS_FILE=""
select_keys() {
  cc_step "select-keys"
  local rc=0 n
  KEYS_FILE="${CC_TMP}/keys.txt"
  cc_registry_select "${REGISTRY_TABLE}" "${INDEX}" "${COUNT}" "${KEYS_FILE}" || rc=$?
  n="$(grep -c . "${KEYS_FILE}" || true)"
  printf 'SELECTION: index=%s source=ArtifactRegistry scan_calls=%s items_scanned=%s items_matched=%s unique_keys=%s taken=%s wanted=%s\n' \
    "${INDEX}" "${CC_REGISTRY_CALLS}" "${CC_REGISTRY_SCANNED}" "${CC_REGISTRY_MATCHED}" "${CC_REGISTRY_UNIQUE}" "${n}" "${COUNT}"
  cc_set CC_COUNTS_JSON registry_scan_calls "${CC_REGISTRY_CALLS}"
  cc_set CC_COUNTS_JSON registry_items_scanned "${CC_REGISTRY_SCANNED}"
  cc_set CC_COUNTS_JSON registry_items_matched "${CC_REGISTRY_MATCHED}"
  case "${rc}" in
    0) ;;
    3)
      cc_set_str CC_OBS_JSON result "not run: the ArtifactRegistry scan found ${n} unique keys of ${INDEX} within its bounds, not ${COUNT}"
      CC_STATUS="prerequisite-not-met"
      cc_die 3 "the ArtifactRegistry scan found ${n} unique keys, not exactly ${COUNT}; nothing sent"
      ;;
    *) cc_die 5 "ArtifactRegistry scan failed (${CC_LAST_AWS_ERROR})" ;;
  esac
}

# check_sample (M-01, repair-01): for 20 evenly spaced selected keys, the
# latest registry version (lib cc_registry_latest, one Query each: AR1,
# ScanIndexForward=false, Limit 1) must hold textHash, embeddingModel and
# embeddingDimensions, with embeddingModel equal to MODEL, the spec ID of
# expected_model_id (L-02), and dimensions equal to config/course.json;
# any miss: exit 3, nothing sent. The vector metadata itself (the handler's
# RequiredKeys) is checked by the handler, which fails a task permanently on
# a missing field; such failures are counted as workload failures
# (worker-outcomes.csv permanent-failure), not worker failures.
check_sample() {
  cc_step "registry-sample"
  local step i=0 keys=() ok=0 bad=0 key ord=0
  step=$((COUNT / SAMPLE_KEYS))
  [ "${step}" -ge 1 ] || step=1
  while IFS= read -r key; do
    if [ $((i % step)) -eq 0 ] && [ "${#keys[@]}" -lt "${SAMPLE_KEYS}" ]; then
      keys+=("${key}")
    fi
    i=$((i + 1))
  done <"${KEYS_FILE}"
  cc_file sample-check.csv "ordinal,eligible,missing_fields,model_check"
  for key in "${keys[@]}"; do
    cc_registry_latest "${REGISTRY_TABLE}" "${INDEX}" "${key}" "${MODEL}" "${DIMS}" ||
      cc_die 5 "ArtifactRegistry latest-version query failed (${CC_LAST_AWS_ERROR})"
    cc_row sample-check.csv "${ord},$(printf '%s' "${CC_REG_LINE}" | tr ' ' ',')"
    case "${CC_REG_LINE}" in
      "yes "*) ok=$((ok + 1)) ;;
      *) bad=$((bad + 1)) ;;
    esac
    ord=$((ord + 1))
  done
  CC_SELECTION_JSON="$(jq -n --arg i "${INDEX}" --argjson n "${COUNT}" --argjson k "${#keys[@]}" --argjson ok "${ok}" \
    --argjson bad "${bad}" --argjson step "${step}" --arg sha "$(cc_sha256 "${KEYS_FILE}")" \
    --argjson calls "${CC_REGISTRY_CALLS}" --argjson scanned "${CC_REGISTRY_SCANNED}" \
    --argjson matched "${CC_REGISTRY_MATCHED}" --argjson uniq "${CC_REGISTRY_UNIQUE}" \
    --arg model "${MODEL}" --arg dims "${DIMS}" '
    {index: $i, source: "DynamoDB ArtifactRegistry (M-01: the vector bucket policy denies the S3 Vectors data plane to this principal)",
     rule: "the first N unique pk artifact#vector#INDEX/KEY in scan order (read-only Scan, FilterExpression begins_with(pk), projection pk, eventually consistent, page size 1000, duplicates of one key dropped keeping the first)",
     count: $n, keys_sha256: $sha, scan: {calls: $calls, items_scanned: $scanned, items_matched: $matched, unique_keys_seen: $uniq},
     registry_sample: {keys: $k, every_nth: $step, eligible: $ok, not_eligible: $bad,
     expected_embedding_model: $model, expected_embedding_dimensions: ($dims | tonumber),
     checked: "latest registry version (Query ScanIndexForward=false Limit 1): textHash, embeddingModel, embeddingDimensions present; embeddingModel equal to expected_embedding_model (the document-side EmbeddingModelSpec.id built from config/course.json embedding.provider, models.embeddingModelId, embedding.dimensions and the rag spec version, L-02) and embeddingDimensions equal to config/course.json. The vector metadata is checked by the handler (permanent failure on a missing field, counted as a workload failure)"}}')"
  printf 'SAMPLE: keys=%s eligible=%s not_eligible=%s (latest registry versions; expected embeddingModel %s, embeddingDimensions %s)\n' \
    "${#keys[@]}" "${ok}" "${bad}" "${MODEL}" "${DIMS}"
  if [ "${bad}" -gt 0 ]; then
    cc_set_str CC_OBS_JSON result "not run: ${bad} of ${#keys[@]} sampled keys have a latest registry version without textHash, embeddingModel or embeddingDimensions, or with another model"
    CC_STATUS="prerequisite-not-met"
    cc_die 3 "sampled registry check failed (${bad} of ${#keys[@]}); nothing sent"
  fi
}

# ---- sending --------------------------------------------------------------------------------------

send_all() {
  cc_step "send"
  local batches="${CC_TMP}/batches.jsonl" b bf="${CC_TMP}/batch.json" out="${CC_TMP}/sb.json" no=0
  local ok failed codes try rc
  jq -R -s -c --arg s "${TASK_SCHEMA}" --arg i "${INDEX}" --arg p "${TASK_PREFIX}" '
    split("\n") | map(select(length > 0)) | to_entries
    | map({Id: ("m\(.key % 10)"),
           MessageBody: ({schema: $s, taskId: ($p + (.key | tostring)), index: $i, vectorKey: .value} | tojson)})
    | [range(0; length; 10) as $o | .[$o:($o + 10)]] | .[]' "${KEYS_FILE}" >"${batches}"
  FIRST_SEND="$(cc_now)"
  while IFS= read -r b; do
    no=$((no + 1))
    printf '%s' "${b}" >"${bf}"
    try=0
    while :; do
      rc=0
      cc_aws_raw "${out}" sqs send-message-batch --queue-url "${EMB_URL}" --entries "file://${bf}" || rc=$?
      if [ "${rc}" -ne 0 ]; then
        # never retried as a whole: a timed-out call may have sent the batch
        cc_row sends.csv "$(cc_now),${no},$(jq 'length' "${bf}"),,,call-${CC_LAST_AWS_ERROR}"
        cc_error "send-batch-${no}-${CC_LAST_AWS_ERROR}"
        SEND_BROKEN="true"
        break
      fi
      ok="$(jq '[.Successful[]?] | length' "${out}")"
      failed="$(jq '[.Failed[]?] | length' "${out}")"
      codes="$(jq -r '[.Failed[]?.Code // "none"] | unique | join(";")' "${out}")"
      cc_row sends.csv "$(cc_now),${no},$(jq 'length' "${bf}"),${ok},${failed},${codes}"
      SENT_OK=$((SENT_OK + ok))
      if [ "${failed}" -eq 0 ]; then
        break
      fi
      if [ "${try}" -ge "${SEND_RETRIES}" ]; then
        SEND_FAILED=$((SEND_FAILED + failed))
        break
      fi
      try=$((try + 1))
      jq -c --slurpfile r "${out}" '[.[] | select(.Id as $id | [$r[0].Failed[]?.Id] | index($id))]' "${bf}" >"${bf}.retry"
      mv -- "${bf}.retry" "${bf}"
      sleep 1
    done
    [ "${SEND_BROKEN}" = "false" ] || break
  done <"${batches}"
  LAST_SEND="$(cc_now)"
  cc_set CC_COUNTS_JSON sent_ok "${SENT_OK}"
  cc_set CC_COUNTS_JSON send_failed_entries "${SEND_FAILED}"
  cc_set CC_COUNTS_JSON send_calls "${no}"
  printf 'SENT: ok=%s failed=%s calls=%s seconds=%s broken=%s\n' "${SENT_OK}" "${SEND_FAILED}" "${no}" \
    "$((LAST_SEND - FIRST_SEND))" "${SEND_BROKEN}"
}

# ---- observation ---------------------------------------------------------------------------------

sample_once() {
  cc_sample_queue queue-samples.csv embedding "${EMB_URL}"
  cc_sample_queue queue-samples.csv embedding-dlq "${EMB_DLQ_URL}"
  cc_sample_queue queue-samples.csv job "${JOB_URL}"
  cc_sample_queue queue-samples.csv job-dlq "${JOB_DLQ_URL}"
  cc_sample_service service-samples.csv "${CLUSTER}" "${WORKER}"
  cc_sample_alarms alarm-samples.csv "${ALARM_OUT}" "${ALARM_OUT_EMB}" "${ALARM_IN}" "${ALARM_DLQ}"
}

DRAINED_AT=""
BACK_TO_ZERO_AT=""
wait_drain() {
  cc_step "wait"
  local deadline ev ei svc d r scaled="no"
  deadline=$((FIRST_SEND + WAIT_LIMIT))
  svc="${CC_TMP}/wait-svc.json"
  while [ "$(cc_now)" -le "${deadline}" ]; do
    read -r ev ei <<<"$(cc_queue_counts "${EMB_URL}")"
    d="x"
    r="x"
    if cc_aws_read "${svc}" ecs describe-services --cluster "${CLUSTER}" --services "${WORKER}"; then
      read -r d r <<<"$(jq -r '.services[0] // {} | "\(.desiredCount // "x") \(.runningCount // "x")"' "${svc}")"
    fi
    if cc_is_uint "${d}" && [ "${d}" -gt 0 ]; then scaled="yes"; fi
    if [ -z "${DRAINED_AT}" ] && cc_is_uint "${ev}" && cc_is_uint "${ei}" && [ $((ev + ei)) -eq 0 ]; then
      DRAINED_AT="$(cc_now)"
      printf 'DRAINED: embedding queue empty %ss after the first send\n' "$((DRAINED_AT - FIRST_SEND))"
    fi
    if [ -n "${DRAINED_AT}" ] && cc_is_uint "${ev}" && [ $((ev + ei)) -gt 0 ]; then
      DRAINED_AT=""
    fi
    if [ -n "${DRAINED_AT}" ] && [ "${scaled}" = "yes" ] && [ "${d}" = "0" ] && [ "${r}" = "0" ]; then
      BACK_TO_ZERO_AT="$(cc_now)"
      printf 'BACK-TO-ZERO: worker desired 0 running 0 %ss after the queue emptied\n' "$((BACK_TO_ZERO_AT - DRAINED_AT))"
      return 0
    fi
    printf 'POLL: embedding=%s/%s worker desired=%s running=%s elapsed=%ss\n' "${ev}" "${ei}" "${d}" "${r}" \
      "$(($(cc_now) - FIRST_SEND))"
    sleep "${WAIT_POLL_SECONDS}"
  done
  return 1
}

# worker_outcomes: counts of the worker log outcomes for this attempt's task
# IDs (bounded); the per-delivery line format is the shared constant set.
worker_outcomes() {
  cc_step "worker-log-outcomes"
  local out="${CC_TMP}/c-logs.json" rc=0
  cc_file worker-outcomes.csv "outcome,lines,distinct_task_ids,source"
  "${CC_TIMEOUT_BIN}" --kill-after=10 "${LOG_COUNT_LIMIT_SECONDS}" aws --region "${CC_REGION}" --output json \
    --cli-connect-timeout "${CC_CONNECT_TIMEOUT}" --cli-read-timeout "${CC_READ_TIMEOUT}" --no-cli-pager \
    logs filter-log-events --log-group-name "${LOG_GROUP}" --log-stream-name-prefix "${CC_WORKER_LOG_STREAM_PREFIX}" \
    --start-time "$(((FIRST_SEND - 60) * 1000))" --max-items "$((COUNT * 5 + 2000))" >"${out}" 2>/dev/null || rc=$?
  if [ "${rc}" -ne 0 ]; then
    cc_error "worker-log-count-cli-exit-${rc}"
    cc_set_str CC_EVIDENCE_JSON worker_log_outcomes "missing (filter-log-events exit ${rc}; 124 = the ${LOG_COUNT_LIMIT_SECONDS}s bound)"
    return 0
  fi
  jq -r --arg prefix "${TASK_PREFIX}" --arg tf "${CC_WORKER_LOG_TASK_FIELD}" --arg of "${CC_WORKER_LOG_OUTCOME_FIELD}" \
    "${CC_WORKER_LOG_JQ}" "${out}" 2>/dev/null | awk '{lines[$3]++; key=$3 " " $2; if (!(key in seen)) {seen[key]=1; tids[$3]++}}
      END {for (o in lines) printf "%s,%d,%d,worker-log\n", o, lines[o], tids[o]}' | sort >"${CC_TMP}/outcomes.csv" || true
  local line
  while IFS= read -r line; do
    [ -n "${line}" ] && cc_row worker-outcomes.csv "${line}"
  done <"${CC_TMP}/outcomes.csv"
  cc_set_str CC_EVIDENCE_JSON worker_log_outcomes "$(grep -c . "${CC_TMP}/outcomes.csv" || true) outcome kinds (worker-outcomes.csv)"
  rm -f -- "${out}"
}

# observed_sequence: from service-samples.csv, the first times the worker's
# desired count went 0 -> 1, reached 2, and returned to 0 after the first
# scale-out; each that did not happen is said plainly.
observed_sequence() {
  local seq
  seq="$(awk -F, -v svc="${WORKER}" 'NR > 1 && $2 == svc && $3 != "" {
      d = $3 + 0
      if (!o1 && d >= 1) { o1 = $1 }
      if (!o2 && d >= 2) { o2 = $1 }
      if (o1 && !z && d == 0 && $1 > o1) { z = $1 }
      if (d > mx) { mx = d }
    }
    END { printf "%s %s %s %d", (o1 ? o1 : "-"), (o2 ? o2 : "-"), (z ? z : "-"), mx }' "${CC_OUT}/service-samples.csv")"
  local o1 o2 z mx
  read -r o1 o2 z mx <<<"${seq}"
  cc_set CC_OBS_JSON sequence "$(jq -n --arg o1 "${o1}" --arg o2 "${o2}" --arg z "${z}" --argjson mx "${mx:-0}" \
    --arg f "${FIRST_SEND}" '
    def at: if . == "-" then null else tonumber end;
    {scale_out_0_to_1_epoch_s: ($o1 | at), reached_2_epoch_s: ($o2 | at), back_to_0_epoch_s: ($z | at),
     max_desired: $mx, first_send_epoch_s: ($f | tonumber),
     said_plainly: ([(if $o1 == "-" then "0 -> 1 did not happen" else empty end),
                     (if $o2 == "-" then "1 -> 2 did not happen" else empty end),
                     (if $z == "-" then "-> 0 did not happen within the wait" else empty end)] | join("; ")),
     source: "service-samples.csv (describe-services every 15 s); scaling-activities.csv records the actual ECS actions; an alarm in ALARM alone is not a scaling event"}')"
  printf 'SEQUENCE: 0->1 at %s; reached 2 at %s; back to 0 at %s; max desired %s\n' "${o1}" "${o2}" "${z}" "${mx}"
}

# ---- preflight ------------------------------------------------------------------------------------

preflight() {
  cc_step "preflight"
  local result out="${CC_TMP}/pf-logs.json"
  settle_poll
  result="${SETTLE_RESULT}"
  printf 'PREFLIGHT: preconditions now: %s (%s); the real run settles for up to %ss\n' "${result%% *}" "${LAST_STATE}" "${SETTLE_LIMIT_SECONDS}"
  if cc_aws_raw "${out}" logs filter-log-events --log-group-name "${LOG_GROUP}" \
    --log-stream-name-prefix "${CC_WORKER_LOG_STREAM_PREFIX}" --start-time "$((($(cc_now) - 300) * 1000))" --max-items 1; then
    printf 'PREFLIGHT: worker log readable: yes\n'
  else
    printf 'PREFLIGHT: worker log readable: no (%s); worker-outcomes.csv would be missing\n' "${CC_LAST_AWS_ERROR}"
  fi
  printf 'PREFLIGHT: N=%s wait limit=%ss; ok (nothing sent, nothing written)\n' "${COUNT}" "${WAIT_LIMIT}"
}

main() {
  cc_init "${TOOL_NAME}" "${TOOL_PATH}" "$@"
  parse_args "$@"
  cc_prepare_out settle.csv queue-samples.csv service-samples.csv alarm-samples.csv scaling-activities.csv \
    sends.csv sample-check.csv selected-keys.txt worker-outcomes.csv clock.csv
  cc_tf_outputs
  read_names
  local mrc vis
  read -r mrc vis <<<"$(cc_queue_settings "${EMB_URL}")"
  cc_set_str CC_LIMITS_JSON embedding_max_receive_count "${mrc}"
  cc_set_str CC_LIMITS_JSON embedding_visibility_timeout_s "${vis}"
  if [ "${CC_PREFLIGHT}" = "true" ]; then
    select_keys
    check_sample
    preflight
    return 0
  fi
  cc_file settle.csv "epoch_s,embedding_visible,embedding_in_flight,job_visible,job_in_flight,worker_desired,worker_running,worker_pending,scaling_in_progress,met,unmet_reasons"
  cc_file clock.csv "phase,local_send_epoch_s,local_recv_epoch_s,http_status,curl_exit,time_total_s,http_date,http_date_epoch_s"
  cc_clock start
  settle
  baseline
  select_keys
  check_sample
  CC_FILES+=("selected-keys.txt")
  cp -- "${KEYS_FILE}" "${CC_OUT}/selected-keys.txt"
  cc_file queue-samples.csv "epoch_s,queue,visible,in_flight"
  cc_file service-samples.csv "epoch_s,service,desired,running,pending"
  cc_file alarm-samples.csv "epoch_s,alarm,state"
  cc_file scaling-activities.csv "start_epoch_s,end_epoch_s,service,direction,status_code"
  cc_file sends.csv "epoch_s,batch,entries,successful,failed,failed_codes"
  sample_once
  cc_sampler_start "$((WAIT_LIMIT + 900))" sample_once
  send_all
  local waited=0
  wait_drain || waited=$?
  cc_bg_stop
  sample_once
  cc_sampler_merge_errors
  cc_scaling_activities scaling-activities.csv "${CLUSTER}" "${WORKER}" "$((FIRST_SEND - 60))"
  worker_outcomes
  cc_clock end
  observed_sequence
  local dlq_end
  read -r dlq_end _ <<<"$(cc_queue_counts "${EMB_DLQ_URL}")"
  cc_set CC_COUNTS_JSON drain_seconds "$([ -n "${DRAINED_AT}" ] && printf '%s' "$((DRAINED_AT - FIRST_SEND))" || printf null)"
  cc_set CC_COUNTS_JSON scale_in_after_drain_seconds "$([ -n "${BACK_TO_ZERO_AT}" ] && printf '%s' "$((BACK_TO_ZERO_AT - DRAINED_AT))" || printf null)"
  cc_set_str CC_COUNTS_JSON embedding_dlq_visible_end "${dlq_end}"
  cc_set_str CC_EVIDENCE_JSON task_counts "service-samples.csv (describe-services every 15 s) and scaling-activities.csv; Container Insights is off"
  if [ "${SEND_BROKEN}" = "true" ]; then
    cc_set_str CC_OBS_JSON result "partial: a send-message-batch call failed after ${SENT_OK} messages; the run was observed to its end"
    cc_die 5 "a send call failed part-way (${SENT_OK} of ${COUNT} sent); observation recorded"
  fi
  if [ "${waited}" -ne 0 ]; then
    CC_STATUS="wait-limit"
    cc_set_str CC_OBS_JSON result "wait limit ${WAIT_LIMIT}s reached: drained=${DRAINED_AT:-no}, worker back to 0=no (remote state in the last samples)"
    cc_die 7 "the wait limit ${WAIT_LIMIT}s passed before the queue drained and the worker returned to 0"
  fi
  cc_set_str CC_OBS_JSON result "drained and back to 0 (see sequence)"
  CC_STATUS="completed"
}

main "$@"
