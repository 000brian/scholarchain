#!/usr/bin/env bash
# scripts/emulator/setup.sh - creates, in the local AWS Emulator only, the
# resources config/emulator.json names. Owner: infra-engineer (Phase 16,
# decision 12 item 3). Run by the human in the [EMULATOR] terminal, after
# `source scripts/emulator/env.sh`; never by an agent.
#
# Status (Phase 16): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="setup.sh"
readonly INDEX_SPEC="modules/storage/src/main/resources/s3vectors/index-spec-v1.json"
readonly POLICY_BUCKET="scholarchain-emu-policy-probe"
readonly NAME_PREFIX="scholarchain-emu-"
readonly MAX_RECEIVE_COUNT=5
readonly JOB_VISIBILITY=150
readonly JOB_RETENTION=86400
readonly EMBEDDING_VISIBILITY=300
readonly EMBEDDING_RETENTION=345600
readonly DLQ_RETENTION=1209600
readonly ACTIVE_TRIES=30

CURRENT_STEP="start"
N_CREATED=0
N_EXISTS=0
N_NOT_IMPLEMENTED=0
N_FAILED=0
N_NOT_EXERCISED=0
ACCOUNT_CONFIRMED="no"

usage() {
  cat <<'EOF'
Usage: scripts/emulator/setup.sh
       scripts/emulator/setup.sh --help

Run it from the repository root, in the terminal where you sourced
scripts/emulator/env.sh, with the AWS Emulator running on 127.0.0.1:9297.

Safety, before any AWS call (stops at once, exit 4, nothing created):
  - the emulator safety contract of Phase 16 decision 6.6 (scripts/emulator/lib.sh:
    rules endpoint, aws-endpoint-url, credentials-file, credentials-file-exists,
    config-file, unset:NAME for the eight credential variables, config-file-path,
    results-dir-path); only the failing rule's name is printed;
  - the configuration file SCHOLARCHAIN_CONFIG_FILE names only resources that
    start with scholarchain-emu- and the Region us-east-1;
  - the health route /__admin/health answers ok (exit 3 if not: the emulator is
    not running or not ready);
  - STS GetCallerIdentity returns the emulator's fixed account (compared,
    never printed; the script prints "emulator account confirmed: yes|no").
Every AWS CLI call then passes --endpoint-url "$SCHOLARCHAIN_EMULATOR_ENDPOINT",
--ca-bundle, --region us-east-1, --cli-connect-timeout 5, --cli-read-timeout 30,
one attempt, under timeout.

What it creates (each create is recorded as created, exists, not-implemented,
or an error code; resource names come from the configuration file, plus the
fixed names of scripts/emulator/OPERATIONS.md):
  - S3 buckets: every distinct bucket of storage.* and objectStore.*
    (scholarchain-emu-data), and scholarchain-emu-policy-probe with a deny-all
    bucket policy (Deny, Principal *, s3:GetObject, s3:PutObject, s3:ListBucket);
  - the six DynamoDB tables of dynamoDb.* (pk S hash, sk S range, on demand;
    TTL on expiresAt for the query-session table), as TableSpecs.scala and
    infra/terraform/modules/dynamodb;
  - the vector bucket storage.vectorBucket and the five indexes vectors.*Index
    with dimension, data type, distance metric and non-filterable keys from
    modules/storage/src/main/resources/s3vectors/index-spec-v1.json;
  - SQS queues scholarchain-emu-jobs and scholarchain-emu-embedding, each with
    a dead-letter queue (NAME-dlq) and maxReceiveCount 5, visibility and
    retention as infra/terraform/modules/workflow.
A not-implemented create is recorded and the independent creates go on (the
tests needing that resource become not-exercised). An implemented create that
fails is recorded, the independent creates go on, and the script exits 6 at
the end. A dependent step whose input failed is recorded as not-exercised.

Results: one JSON line per AWS call (service, operation, class, httpStatus,
latencyMillis, then resource, outcome, errorCode, observation) is printed as a
RESULT line and, when SCHOLARCHAIN_EMULATOR_RESULTS_DIR is set, appended to
SCHOLARCHAIN_EMULATOR_RESULTS_DIR/setup.jsonl (the folder must exist; an
existing setup.jsonl is refused: one setup per run folder). No host, account,
ARN, queue URL or key is printed or recorded. Queue URLs and ARNs stay in
shell variables.

Requirements: bash 3.2+, aws (CLI v2), jq 1.6+, curl, timeout or gtimeout;
perl (optional, for millisecond latencies).

Exit codes: 0 every create is created, exists or not-implemented; 2 usage;
3 missing prerequisite (tool, configuration or index-spec file, results folder
missing, emulator not answering its health route); 4 refused by a safety rule
(contract, names, account); 5 an unexpected command failure; 6 at least one
create failed (implemented-failed or infrastructure-failure); 7 interrupted.

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
on_signal() {
  printf 'ERROR: %s step=%s exit=7: interrupted by %s\n' "${SCRIPT_NAME}" "${CURRENT_STEP}" "$1" >&2
  exit 7
}
on_exit() {
  if [ -n "${EMU_TMP:-}" ] && [ -d "${EMU_TMP}" ]; then
    rm -rf -- "${EMU_TMP}"
  fi
}

parse_args() {
  if [ "$#" -eq 1 ] && { [ "$1" = "--help" ] || [ "$1" = "-h" ]; }; then
    usage
    exit 0
  fi
  if [ "$#" -ne 0 ]; then
    usage >&2
    die 2 "unexpected arguments (this script takes none)"
  fi
}

# Counts one create's final outcome.
count_outcome() {
  case "$1" in
    created) N_CREATED=$((N_CREATED + 1)) ;;
    exists) N_EXISTS=$((N_EXISTS + 1)) ;;
    not-implemented) N_NOT_IMPLEMENTED=$((N_NOT_IMPLEMENTED + 1)) ;;
    not-exercised) N_NOT_EXERCISED=$((N_NOT_EXERCISED + 1)) ;;
    *) N_FAILED=$((N_FAILED + 1)) ;;
  esac
}

# call_class: maps EMU_CLASS of an unexpected answer to an outcome class.
call_class() {
  case "${EMU_CLASS}" in
    ok) printf 'implemented-passed' ;;
    not-implemented) printf 'not-implemented' ;;
    infrastructure-failure) printf 'infrastructure-failure' ;;
    *) printf 'implemented-failed' ;;
  esac
}

# create_outcome: outcome word of a failed create call (error code or class).
create_outcome() {
  case "${EMU_CLASS}" in
    not-implemented) printf 'not-implemented' ;;
    *) printf '%s' "${EMU_CODE:-error}" ;;
  esac
}

# count_check_failure CLASS: a check call (not a create) that answered wrongly
# or not at all also makes the run fail; not-implemented stays a recorded gap.
count_check_failure() {
  case "$1" in
    implemented-failed | infrastructure-failure) N_FAILED=$((N_FAILED + 1)) ;;
  esac
}

# record_create SERVICE OPERATION RESOURCE OUTCOME CLASS
record_create() {
  emu_result "$1" "$2" "$5" resource "$3" outcome "$4" errorCode "${EMU_CODE}"
  count_outcome "$4"
}

prerequisites() {
  step "prerequisites"
  [ -f "scripts/emulator/setup.sh" ] && [ -f "build.sbt" ] && [ -f "scripts/emulator/lib.sh" ] ||
    die 4 "refused: run it from the repository root (bash scripts/emulator/setup.sh)"
  # shellcheck source=scripts/emulator/lib.sh
  . scripts/emulator/lib.sh
  EMU_SCRIPT="${SCRIPT_NAME}"
  local tool
  for tool in aws jq curl; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "missing prerequisite: ${tool}"
  done
  emu_find_timeout || die 3 "missing prerequisite: GNU timeout or gtimeout"
}

safety() {
  step "safety-contract"
  local rc=0
  emu_safety_check optional || rc=$?
  if [ "${rc}" -ne 0 ]; then
    if [ "${EMU_FAILED_RULE}" != "results-dir-path" ] && [ -n "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR:-}" ] &&
      [ -d "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}" ] && [ ! -e "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}/setup.jsonl" ]; then
      EMU_RESULTS_FILE="${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}/setup.jsonl"
      emu_preflight_line "${EMU_FAILED_RULE}"
    fi
    die 4 "refused by the emulator safety contract: rule ${EMU_FAILED_RULE} (no AWS call made)"
  fi
  info "emulator safety contract: passed"
  if [ -n "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR:-}" ]; then
    [ -d "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}" ] ||
      die 3 "SCHOLARCHAIN_EMULATOR_RESULTS_DIR does not exist (mkdir -p it first)"
    [ ! -e "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}/setup.jsonl" ] ||
      die 4 "refused: setup.jsonl already exists in the results folder (one setup per run folder; use a new run ID)"
    EMU_RESULTS_FILE="${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}/setup.jsonl"
    info "results file: \$SCHOLARCHAIN_EMULATOR_RESULTS_DIR/setup.jsonl"
  else
    info "SCHOLARCHAIN_EMULATOR_RESULTS_DIR is not set: RESULT lines are printed only"
  fi
}

# Configuration values (set by read_config).
DATA_BUCKETS=""
VECTOR_BUCKET=""
TABLES=""
QUERY_SESSION_TABLE=""
INDEXES=""
SPEC_DIMENSION=""
SPEC_DATA_TYPE=""
SPEC_METRIC=""

read_config() {
  step "configuration"
  local cfg="${SCHOLARCHAIN_CONFIG_FILE}" n region
  [ -f "${cfg}" ] || die 3 "the configuration file named by SCHOLARCHAIN_CONFIG_FILE does not exist"
  [ -f "${INDEX_SPEC}" ] || die 3 "missing ${INDEX_SPEC}"
  jq empty "${cfg}" >/dev/null 2>&1 || die 3 "the configuration file is not valid JSON"
  jq empty "${INDEX_SPEC}" >/dev/null 2>&1 || die 3 "${INDEX_SPEC} is not valid JSON"
  region="$(jq -r '.aws.region // empty' "${cfg}")"
  [ "${region}" = "${EMU_REGION}" ] || die 4 "refused: aws.region of the configuration file is not ${EMU_REGION}"
  [ -z "$(jq -r '.neptune // empty | tostring' "${cfg}")" ] ||
    die 4 "refused: the configuration file has a neptune section (the emulator configuration has none)"
  DATA_BUCKETS="$(jq -r '[.storage.rawBucket, .storage.curatedBucket, .storage.graphBucket,
      .objectStore.semanticContextBucket, .objectStore.reportsBucket, .objectStore.logsBucket]
      | map(select(. != null)) | unique | .[]' "${cfg}")"
  VECTOR_BUCKET="$(jq -r '.storage.vectorBucket // empty' "${cfg}")"
  TABLES="$(jq -r '.dynamoDb // {} | [.runLedgerTable, .entityLinkCacheTable, .querySessionTable,
      .evidenceBundleTable, .artifactRegistryTable, .researcherMetricCacheTable]
      | map(select(. != null)) | .[]' "${cfg}")"
  QUERY_SESSION_TABLE="$(jq -r '.dynamoDb.querySessionTable // empty' "${cfg}")"
  INDEXES="$(jq -r '.vectors // {} | [.researcherProfileIndex, .collaborationCommunityIndex,
      .paperAbstractIndex, .collaborationPathIndex, .topicInstitutionSummaryIndex]
      | map(select(. != null)) | .[]' "${cfg}")"
  SPEC_DIMENSION="$(jq -r '.dimension' "${INDEX_SPEC}")"
  SPEC_DATA_TYPE="$(jq -r '.dataType' "${INDEX_SPEC}")"
  SPEC_METRIC="$(jq -r '.distanceMetric' "${INDEX_SPEC}")"
  [ -n "${DATA_BUCKETS}" ] && [ -n "${VECTOR_BUCKET}" ] && [ -n "${QUERY_SESSION_TABLE}" ] ||
    die 3 "the configuration file lacks storage buckets, the vector bucket or the dynamoDb section"
  n="$(printf '%s\n' "${TABLES}" | grep -c . || true)"
  [ "${n}" -eq 6 ] || die 3 "the configuration file names ${n} DynamoDB tables, expected 6"
  n="$(printf '%s\n' "${INDEXES}" | grep -c . || true)"
  [ "${n}" -eq 5 ] || die 3 "the configuration file names ${n} vector indexes, expected 5"
  local name
  for name in ${DATA_BUCKETS} ${VECTOR_BUCKET} ${TABLES}; do
    case "${name}" in
      "${NAME_PREFIX}"*) ;;
      *) die 4 "refused: a bucket or table name in the configuration file does not start with ${NAME_PREFIX}" ;;
    esac
  done
  for name in ${INDEXES}; do
    jq -e --arg n "${name}" '.indexes | map(.name) | index($n) != null' "${INDEX_SPEC}" >/dev/null ||
      die 3 "vector index ${name} is not in ${INDEX_SPEC}"
  done
  info "configuration: $(printf '%s\n' "${DATA_BUCKETS}" | grep -c .) bucket(s), vector bucket ${VECTOR_BUCKET}, 6 tables, 5 indexes"
}

preconditions() {
  step "health"
  if ! emu_health; then
    die 3 "the emulator health route did not answer ok (is the emulator running on 127.0.0.1:${EMU_PORT}?)"
  fi
  info "health route: ok"
  step "account"
  if emu_account_matches; then
    ACCOUNT_CONFIRMED="yes"
    info "emulator account confirmed: yes"
  else
    info "emulator account confirmed: no"
    die 4 "refused: STS did not return the emulator's account (class ${EMU_CLASS}, code ${EMU_CODE:-none}); nothing was created"
  fi
}

# ---- S3 ---------------------------------------------------------------------------

# ensure_bucket NAME: prints nothing; sets BUCKET_OK=1 when created or present.
BUCKET_OK=0
ensure_bucket() {
  local b="$1" outcome
  BUCKET_OK=0
  emu_call s3api head-bucket --bucket "${b}"
  if [ "${EMU_CLASS}" = "ok" ]; then
    record_create s3 HeadBucket "${b}" exists implemented-passed
    BUCKET_OK=1
    return 0
  fi
  case "${EMU_CODE}" in
    404 | NotFound | NoSuchBucket)
      emu_result s3 HeadBucket implemented-passed resource "${b}" observation absent
      ;;
    *)
      emu_result s3 HeadBucket "$(call_class)" resource "${b}" errorCode "${EMU_CODE}"
      ;;
  esac
  emu_call s3api create-bucket --bucket "${b}"
  if [ "${EMU_CLASS}" = "ok" ]; then
    record_create s3 CreateBucket "${b}" created implemented-passed
    BUCKET_OK=1
  elif [ "${EMU_CODE}" = "BucketAlreadyOwnedByYou" ]; then
    record_create s3 CreateBucket "${b}" exists implemented-passed
    BUCKET_OK=1
  else
    outcome="$(create_outcome)"
    record_create s3 CreateBucket "${b}" "${outcome}" "$(call_class)"
  fi
}

create_buckets() {
  step "s3-buckets"
  local b policy
  for b in ${DATA_BUCKETS}; do
    ensure_bucket "${b}"
  done
  ensure_bucket "${POLICY_BUCKET}"
  if [ "${BUCKET_OK}" -ne 1 ]; then
    emu_not_exercised s3 PutBucketPolicy "bucket-not-available" resource "${POLICY_BUCKET}" outcome not-exercised
    count_outcome not-exercised
    return 0
  fi
  policy="$(jq -cn --arg b "${POLICY_BUCKET}" '{Version: "2012-10-17", Statement: [{Sid: "EmulatorDenyAllProbe",
    Effect: "Deny", Principal: "*", Action: ["s3:GetObject", "s3:PutObject", "s3:ListBucket"],
    Resource: ["arn:aws:s3:::\($b)", "arn:aws:s3:::\($b)/*"]}]}')"
  emu_call s3api put-bucket-policy --bucket "${POLICY_BUCKET}" --policy "${policy}"
  if [ "${EMU_CLASS}" = "ok" ]; then
    record_create s3 PutBucketPolicy "${POLICY_BUCKET}" created implemented-passed
  else
    record_create s3 PutBucketPolicy "${POLICY_BUCKET}" "$(create_outcome)" "$(call_class)"
  fi
}

# ---- DynamoDB ---------------------------------------------------------------------

# wait_active TABLE: status 0 when DescribeTable reports ACTIVE (bounded).
wait_active() {
  local t="$1" i status=""
  for ((i = 1; i <= ACTIVE_TRIES; i++)); do
    emu_call dynamodb describe-table --table-name "${t}" --query 'Table.TableStatus' --output text
    if [ "${EMU_CLASS}" = "ok" ]; then
      status="$(head -c 64 "${EMU_TMP}/out" | tr -d '[:space:]')"
      if [ "${status}" = "ACTIVE" ]; then
        emu_result dynamodb DescribeTable implemented-passed resource "${t}" observation "ACTIVE after ${i} tries"
        return 0
      fi
    elif [ "${EMU_CLASS}" != "error" ]; then
      emu_result dynamodb DescribeTable "$(call_class)" resource "${t}" errorCode "${EMU_CODE}"
      count_check_failure "$(call_class)"
      return 1
    fi
    sleep 1
  done
  emu_result dynamodb DescribeTable implemented-failed resource "${t}" errorCode "${EMU_CODE:-not-active}" \
    observation "not ACTIVE after ${ACTIVE_TRIES} tries"
  count_check_failure implemented-failed
  return 1
}

create_tables() {
  step "dynamodb-tables"
  local t ok
  for t in ${TABLES}; do
    ok=0
    emu_call dynamodb create-table --table-name "${t}" \
      --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
      --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
      --billing-mode PAY_PER_REQUEST --table-class STANDARD
    if [ "${EMU_CLASS}" = "ok" ]; then
      record_create dynamodb CreateTable "${t}" created implemented-passed
      ok=1
    elif [ "${EMU_CODE}" = "ResourceInUseException" ]; then
      record_create dynamodb CreateTable "${t}" exists implemented-passed
      ok=1
    else
      record_create dynamodb CreateTable "${t}" "$(create_outcome)" "$(call_class)"
    fi
    if [ "${t}" = "${QUERY_SESSION_TABLE}" ]; then
      if [ "${ok}" -ne 1 ]; then
        emu_not_exercised dynamodb UpdateTimeToLive "table-not-available" resource "${t}" outcome not-exercised
        count_outcome not-exercised
      elif ! wait_active "${t}"; then
        emu_not_exercised dynamodb UpdateTimeToLive "table-not-active" resource "${t}" outcome not-exercised
        count_outcome not-exercised
      else
        emu_call dynamodb update-time-to-live --table-name "${t}" \
          --time-to-live-specification Enabled=true,AttributeName=expiresAt
        if [ "${EMU_CLASS}" = "ok" ]; then
          record_create dynamodb UpdateTimeToLive "${t}" created implemented-passed
        elif [ "${EMU_CODE}" = "ValidationException" ] && [ "${EMU_MESSAGE_HINT}" = "already-enabled" ]; then
          record_create dynamodb UpdateTimeToLive "${t}" exists implemented-passed
        else
          record_create dynamodb UpdateTimeToLive "${t}" "$(create_outcome)" "$(call_class)"
        fi
      fi
    fi
  done
}

# ---- S3 Vectors -------------------------------------------------------------------

create_vectors() {
  step "s3vectors"
  local ok=0 idx nonfilterable
  emu_call s3vectors create-vector-bucket --vector-bucket-name "${VECTOR_BUCKET}"
  if [ "${EMU_CLASS}" = "ok" ]; then
    record_create s3vectors CreateVectorBucket "${VECTOR_BUCKET}" created implemented-passed
    ok=1
  elif [ "${EMU_CODE}" = "ConflictException" ]; then
    record_create s3vectors CreateVectorBucket "${VECTOR_BUCKET}" exists implemented-passed
    ok=1
  else
    record_create s3vectors CreateVectorBucket "${VECTOR_BUCKET}" "$(create_outcome)" "$(call_class)"
  fi
  for idx in ${INDEXES}; do
    if [ "${ok}" -ne 1 ]; then
      emu_not_exercised s3vectors CreateIndex "vector-bucket-not-available" resource "${idx}" outcome not-exercised
      count_outcome not-exercised
      continue
    fi
    emu_call s3vectors get-index --vector-bucket-name "${VECTOR_BUCKET}" --index-name "${idx}"
    if [ "${EMU_CLASS}" = "ok" ]; then
      record_create s3vectors GetIndex "${idx}" exists implemented-passed
      continue
    elif [ "${EMU_CODE}" = "NotFoundException" ]; then
      emu_result s3vectors GetIndex implemented-passed resource "${idx}" observation absent
    else
      emu_result s3vectors GetIndex "$(call_class)" resource "${idx}" errorCode "${EMU_CODE}"
    fi
    nonfilterable="$(jq -c --arg n "${idx}" '{nonFilterableMetadataKeys: (.indexes[] | select(.name == $n) | .nonFilterableKeys)}' "${INDEX_SPEC}")"
    emu_call s3vectors create-index --vector-bucket-name "${VECTOR_BUCKET}" --index-name "${idx}" \
      --data-type "${SPEC_DATA_TYPE}" --dimension "${SPEC_DIMENSION}" --distance-metric "${SPEC_METRIC}" \
      --metadata-configuration "${nonfilterable}"
    if [ "${EMU_CLASS}" = "ok" ]; then
      record_create s3vectors CreateIndex "${idx}" created implemented-passed
    elif [ "${EMU_CODE}" = "ConflictException" ]; then
      record_create s3vectors CreateIndex "${idx}" exists implemented-passed
    else
      record_create s3vectors CreateIndex "${idx}" "$(create_outcome)" "$(call_class)"
    fi
  done
}

# ---- SQS --------------------------------------------------------------------------

# ensure_queue NAME ATTRIBUTES_JSON: sets QUEUE_URL (kept in memory only) when
# the queue exists or was created.
QUEUE_URL=""
ensure_queue() {
  local q="$1" attrs="$2"
  QUEUE_URL=""
  emu_call sqs get-queue-url --queue-name "${q}" --query QueueUrl --output text
  if [ "${EMU_CLASS}" = "ok" ]; then
    QUEUE_URL="$(head -c 2048 "${EMU_TMP}/out" | tr -d '[:space:]')"
    : >"${EMU_TMP}/out"
    record_create sqs GetQueueUrl "${q}" exists implemented-passed
    return 0
  fi
  case "${EMU_CODE}" in
    AWS.SimpleQueueService.NonExistentQueue | QueueDoesNotExist)
      emu_result sqs GetQueueUrl implemented-passed resource "${q}" observation absent
      ;;
    *)
      emu_result sqs GetQueueUrl "$(call_class)" resource "${q}" errorCode "${EMU_CODE}"
      ;;
  esac
  emu_call sqs create-queue --queue-name "${q}" --attributes "${attrs}" --query QueueUrl --output text
  if [ "${EMU_CLASS}" = "ok" ]; then
    QUEUE_URL="$(head -c 2048 "${EMU_TMP}/out" | tr -d '[:space:]')"
    : >"${EMU_TMP}/out"
    record_create sqs CreateQueue "${q}" created implemented-passed
  else
    record_create sqs CreateQueue "${q}" "$(create_outcome)" "$(call_class)"
  fi
}

# create_queue_pair BASE VISIBILITY RETENTION
create_queue_pair() {
  local base="$1" visibility="$2" retention="$3" dlq="${1}-dlq" dlq_arn="" attrs redrive count
  ensure_queue "${dlq}" "$(jq -cn --arg r "${DLQ_RETENTION}" '{MessageRetentionPeriod: $r}')"
  if [ -n "${QUEUE_URL}" ]; then
    emu_call sqs get-queue-attributes --queue-url "${QUEUE_URL}" --attribute-names QueueArn \
      --query Attributes.QueueArn --output text
    if [ "${EMU_CLASS}" = "ok" ]; then
      dlq_arn="$(head -c 2048 "${EMU_TMP}/out" | tr -d '[:space:]')"
      : >"${EMU_TMP}/out"
      emu_result sqs GetQueueAttributes implemented-passed resource "${dlq}" observation "QueueArn read"
    else
      emu_result sqs GetQueueAttributes "$(call_class)" resource "${dlq}" errorCode "${EMU_CODE}"
      count_check_failure "$(call_class)"
    fi
  fi
  if [ -z "${dlq_arn}" ]; then
    emu_not_exercised sqs CreateQueue "dead-letter-queue-not-available" resource "${base}" outcome not-exercised
    count_outcome not-exercised
    return 0
  fi
  attrs="$(jq -cn --arg v "${visibility}" --arg r "${retention}" --arg arn "${dlq_arn}" \
    --arg m "${MAX_RECEIVE_COUNT}" \
    '{VisibilityTimeout: $v, MessageRetentionPeriod: $r, RedrivePolicy: ({deadLetterTargetArn: $arn, maxReceiveCount: $m} | tojson)}')"
  dlq_arn=""
  ensure_queue "${base}" "${attrs}"
  attrs=""
  [ -n "${QUEUE_URL}" ] || return 0
  emu_call sqs get-queue-attributes --queue-url "${QUEUE_URL}" --attribute-names RedrivePolicy \
    --query Attributes.RedrivePolicy --output text
  QUEUE_URL=""
  if [ "${EMU_CLASS}" = "ok" ]; then
    redrive="$(head -c 4096 "${EMU_TMP}/out")"
    : >"${EMU_TMP}/out"
    count="$(printf '%s' "${redrive}" | jq -r '.maxReceiveCount // empty | tostring' 2>/dev/null || true)"
    redrive=""
    if [ "${count}" = "${MAX_RECEIVE_COUNT}" ]; then
      emu_result sqs GetQueueAttributes implemented-passed resource "${base}" observation "maxReceiveCount ${count}"
    else
      emu_result sqs GetQueueAttributes implemented-failed resource "${base}" \
        observation "maxReceiveCount ${count:-absent}, expected ${MAX_RECEIVE_COUNT}"
      N_FAILED=$((N_FAILED + 1))
    fi
  else
    emu_result sqs GetQueueAttributes "$(call_class)" resource "${base}" errorCode "${EMU_CODE}"
    count_check_failure "$(call_class)"
  fi
}

create_queues() {
  step "sqs-queues"
  create_queue_pair "${NAME_PREFIX}jobs" "${JOB_VISIBILITY}" "${JOB_RETENTION}"
  create_queue_pair "${NAME_PREFIX}embedding" "${EMBEDDING_VISIBILITY}" "${EMBEDDING_RETENTION}"
}

main() {
  parse_args "$@"
  prerequisites
  trap 'on_err ${LINENO}' ERR
  trap 'on_signal INT' INT
  trap 'on_signal TERM' TERM
  trap on_exit EXIT
  safety
  read_config
  EMU_TMP="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-emu-setup.XXXXXX")"
  preconditions
  create_buckets
  create_tables
  create_vectors
  create_queues
  step "summary"
  printf 'SUMMARY: %s created=%s exists=%s not-implemented=%s not-exercised=%s failed=%s emulator-account-confirmed=%s\n' \
    "${SCRIPT_NAME}" "${N_CREATED}" "${N_EXISTS}" "${N_NOT_IMPLEMENTED}" "${N_NOT_EXERCISED}" "${N_FAILED}" \
    "${ACCOUNT_CONFIRMED}"
  if [ "${N_FAILED}" -gt 0 ]; then
    die 6 "${N_FAILED} create(s) failed (RESULT lines above)"
  fi
  step "done"
}

main "$@"
