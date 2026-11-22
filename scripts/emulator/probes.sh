#!/usr/bin/env bash
# scripts/emulator/probes.sh - emulator probes the Scala emulator suites do not
# cover (Step Functions, S3 Vectors control plane, the M-01 vector-bucket
# policy story). Owner: infra-engineer (Phase 16, decision 12 item 4). Run by
# the human in the [EMULATOR] terminal after setup.sh; never by an agent.
#
# Status (Phase 16): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="probes.sh"
readonly TF_DIR="infra/terraform/environments/course"
# The Phase 15 D3 runtime-on plan (docs/agent-runs/phase-15/runbook-log.md, row D3).
readonly DEFAULT_PLAN_NAME="20261120T211840Z-85dacb447057-8820"
readonly SFN_ADDRESS="module.workflow.aws_sfn_state_machine.pipeline"
readonly PROBE_VECTOR_BUCKET="scholarchain-emu-probe-vectors"
readonly PROBE_INDEX="probe-v1"
readonly TF_SHOW_TIMEOUT=120

CURRENT_STEP="start"
PLAN_NAME="${DEFAULT_PLAN_NAME}"
RUN_TAG=""
N_PASSED=0
N_FAILED=0
N_NOT_IMPLEMENTED=0
N_NOT_EXERCISED=0
N_INFRA=0
ACCOUNT_CONFIRMED="no"

usage() {
  cat <<'EOF'
Usage: scripts/emulator/probes.sh [--plan-name NAME]
       scripts/emulator/probes.sh --help

Run it from the repository root, in the terminal where you sourced
scripts/emulator/env.sh, after scripts/emulator/setup.sh, with the emulator
running. SCHOLARCHAIN_EMULATOR_RESULTS_DIR must be set to an absolute,
existing folder (experiments/emulator/raw/RUN_ID/); the results go to
probes.jsonl there (an existing probes.jsonl is refused).

Safety, before any AWS call: the emulator safety contract of Phase 16
decision 6.6 (scripts/emulator/lib.sh; here SCHOLARCHAIN_EMULATOR_RESULTS_DIR
is required), the health route, and STS GetCallerIdentity returning the
emulator's account (compared, never printed). A failed rule stops the script
with exit 4 and one preflight line (when the results folder is usable). Every
AWS CLI call passes --endpoint-url "$SCHOLARCHAIN_EMULATOR_ENDPOINT" explicitly.

Probes (each AWS call is one result line with its outcome class):
  (a) Step Functions, compared with Phase 15 D4a (real AWS, runbook-log.md,
      row "D3 address list + K-01 + D4a"): the state-machine definition of
      the saved plan plans/NAME.tfplan (default NAME is the D3 runtime-on plan
      20261120T211840Z-85dacb447057-8820) is read with
      `terraform show -json` and jq into a shell variable, never printed or
      written; then ValidateStateMachineDefinition (expected result OK, no
      codes) and TestState on the Pass state ReadInput with the four D4a
      inputs: absent (expected SUCCEEDED, limits Instances,2,4,4,4, conf
      partitions 32), A max 2 / 32 (SUCCEEDED, 2,2,2,2, 32), B max 4 / 128
      (SUCCEEDED, 2,4,4,4, 128), invalid max 5 (FAILED,
      States.QueryEvaluationError, no limits). A match is implemented-passed,
      an answered difference implemented-failed. If the plan file or the
      Terraform working folder is missing, the five calls are recorded
      not-exercised (input missing / input unreadable).
  (b) S3 Vectors GetVectorBucket and ListIndexes on storage.vectorBucket of
      SCHOLARCHAIN_CONFIG_FILE (expected: found, the five vectors.*Index names).
  (c) The M-01 story, on its own vector bucket scholarchain-emu-probe-vectors
      (index probe-v1, dimension 4) so the suites are never affected: put one
      vector, read it back (baseline), put a vector-bucket policy that denies
      the five data-plane actions to every principal (as
      infra/terraform/modules/vectors, without the exception for the runtime
      roles), then GetVectors again. Whether the emulator refuses or allows is
      recorded (observation refused or allowed), never asserted: the emulator
      documentation says it does not evaluate resource-based policies, and
      real AWS refused the administrator profile in Phase 15 (M-01).

Result line: service, operation, class, httpStatus, latencyMillis, then probe,
resource, observation, errorCode. No host, account, ARN, queue URL or key.

Requirements: bash 3.2+, aws (CLI v2), jq 1.6+, curl, timeout or gtimeout;
terraform (only for probe a); perl (optional, millisecond latencies).

Exit codes: 0 every probe call answered as expected or was recorded
not-implemented or not-exercised; 2 usage; 3 missing prerequisite (tool,
configuration file, results folder, emulator health); 4 refused (safety
contract, account, existing probes.jsonl, not at the repository root); 5 an
unexpected command failure; 6 at least one call was implemented-failed or
infrastructure-failure (the results are complete; read probes.jsonl);
7 interrupted.

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
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --plan-name)
        [ "$#" -ge 2 ] || die 2 "--plan-name needs a value"
        PLAN_NAME="$2"
        shift 2
        ;;
      *)
        usage >&2
        die 2 "unknown argument"
        ;;
    esac
  done
  [[ "${PLAN_NAME}" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}-[0-9a-f]{4}$ ]] ||
    die 2 "--plan-name must look like UTCSTAMP-COMMIT12-HEX4 (a plan.sh plan name)"
}

# tally CLASS
tally() {
  case "$1" in
    implemented-passed) N_PASSED=$((N_PASSED + 1)) ;;
    implemented-failed) N_FAILED=$((N_FAILED + 1)) ;;
    not-implemented) N_NOT_IMPLEMENTED=$((N_NOT_IMPLEMENTED + 1)) ;;
    not-exercised) N_NOT_EXERCISED=$((N_NOT_EXERCISED + 1)) ;;
    *) N_INFRA=$((N_INFRA + 1)) ;;
  esac
}

# answered_class: outcome class of a call whose answer was not the expected one.
answered_class() {
  case "${EMU_CLASS}" in
    not-implemented) printf 'not-implemented' ;;
    infrastructure-failure) printf 'infrastructure-failure' ;;
    *) printf 'implemented-failed' ;;
  esac
}

# rec SERVICE OPERATION CLASS [KEY VALUE]...
rec() {
  emu_result "$@"
  tally "$3"
}

# skip SERVICE OPERATION REASON [KEY VALUE]...
skip() {
  emu_not_exercised "$@"
  tally not-exercised
}

prerequisites() {
  step "prerequisites"
  [ -f "scripts/emulator/probes.sh" ] && [ -f "build.sbt" ] && [ -f "scripts/emulator/lib.sh" ] ||
    die 4 "refused: run it from the repository root (bash scripts/emulator/probes.sh)"
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
  emu_safety_check required || rc=$?
  if [ "${rc}" -ne 0 ]; then
    if [ "${EMU_FAILED_RULE}" != "results-dir-path" ] && [ -d "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}" ] &&
      [ ! -e "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}/probes.jsonl" ]; then
      EMU_RESULTS_FILE="${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}/probes.jsonl"
      emu_preflight_line "${EMU_FAILED_RULE}"
    fi
    die 4 "refused by the emulator safety contract: rule ${EMU_FAILED_RULE} (no AWS call made)"
  fi
  info "emulator safety contract: passed"
  [ -d "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}" ] ||
    die 3 "SCHOLARCHAIN_EMULATOR_RESULTS_DIR does not exist (mkdir -p it first)"
  [ ! -e "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}/probes.jsonl" ] ||
    die 4 "refused: probes.jsonl already exists in the results folder (use a new run ID)"
  [ -f "${SCHOLARCHAIN_CONFIG_FILE}" ] || die 3 "the configuration file named by SCHOLARCHAIN_CONFIG_FILE does not exist"
  jq empty "${SCHOLARCHAIN_CONFIG_FILE}" >/dev/null 2>&1 || die 3 "the configuration file is not valid JSON"
  EMU_RESULTS_FILE="${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}/probes.jsonl"
  info "results file: \$SCHOLARCHAIN_EMULATOR_RESULTS_DIR/probes.jsonl"
  RUN_TAG="${SCHOLARCHAIN_EMULATOR_RUN_ID:-}"
  if ! [[ "${RUN_TAG}" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
    RUN_TAG="probe-$(date -u +%Y%m%dT%H%M%SZ)-${RANDOM}"
  fi
}

preconditions() {
  step "health"
  emu_health || die 3 "the emulator health route did not answer ok (is the emulator running on 127.0.0.1:${EMU_PORT}?)"
  info "health route: ok"
  step "account"
  if emu_account_matches; then
    ACCOUNT_CONFIRMED="yes"
    info "emulator account confirmed: yes"
  else
    info "emulator account confirmed: no"
    die 4 "refused: STS did not return the emulator's account (class ${EMU_CLASS}, code ${EMU_CODE:-none})"
  fi
}

# ---- (a) Step Functions -----------------------------------------------------------

# expected_for LABEL: the D4a real-AWS row, normalized.
expected_for() {
  case "$1" in
    absent) printf '%s' '{"status":"SUCCEEDED","error":null,"limits":["Instances",2,4,4,4],"conf":"spark.sql.shuffle.partitions=32"}' ;;
    A) printf '%s' '{"status":"SUCCEEDED","error":null,"limits":["Instances",2,2,2,2],"conf":"spark.sql.shuffle.partitions=32"}' ;;
    B) printf '%s' '{"status":"SUCCEEDED","error":null,"limits":["Instances",2,4,4,4],"conf":"spark.sql.shuffle.partitions=128"}' ;;
    invalid) printf '%s' '{"status":"FAILED","error":"States.QueryEvaluationError","limits":null,"conf":null}' ;;
  esac
}

input_for() {
  case "$1" in
    absent) printf '%s' '{"codeVersion":"x"}' ;;
    A) printf '%s' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":2},"spark":{"shufflePartitions":32}}' ;;
    B) printf '%s' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":4},"spark":{"shufflePartitions":128}}' ;;
    invalid) printf '%s' '{"codeVersion":"x","cluster":{"managedScalingMaxUnits":5}}' ;;
  esac
}

probe_step_functions() {
  step "probe-a-step-functions"
  local plan_rel="plans/${PLAN_NAME}.tfplan" def="" label got expected match obs
  local labels="absent A B invalid"
  if [ ! -f "${TF_DIR}/${plan_rel}" ]; then
    info "the saved plan ${PLAN_NAME} is not present locally: probe (a) not exercised"
    skip stepfunctions ValidateStateMachineDefinition "input missing" probe a-validate
    for label in ${labels}; do
      skip stepfunctions TestState "input missing" probe "a-test-state-${label}"
    done
    return 0
  fi
  if ! command -v terraform >/dev/null 2>&1 || [ ! -d "${TF_DIR}/.terraform" ]; then
    info "terraform or ${TF_DIR}/.terraform is missing: probe (a) not exercised"
    skip stepfunctions ValidateStateMachineDefinition "input unreadable" probe a-validate
    for label in ${labels}; do
      skip stepfunctions TestState "input unreadable" probe "a-test-state-${label}"
    done
    return 0
  fi
  # The definition holds role ARNs and S3 locations: it stays in this variable.
  # The substitution never fails by itself (|| true): an unreadable plan gives
  # an empty value, so no ERR trap fires inside it.
  def="$({ "${EMU_TIMEOUT_BIN}" --kill-after=10 "${TF_SHOW_TIMEOUT}" terraform "-chdir=${TF_DIR}" show -json -no-color \
    "${plan_rel}" 2>/dev/null |
    jq -r --arg a "${SFN_ADDRESS}" '.resource_changes[] | select(.address == $a) | .change.after.definition // empty' \
      2>/dev/null; } || true)"
  if [ -z "${def}" ]; then
    def=""
    info "the plan could not be read or holds no state-machine definition: probe (a) not exercised"
    skip stepfunctions ValidateStateMachineDefinition "input unreadable" probe a-validate
    for label in ${labels}; do
      skip stepfunctions TestState "input unreadable" probe "a-test-state-${label}"
    done
    return 0
  fi
  info "state-machine definition read from the plan (${#def} bytes; not printed)"

  emu_call stepfunctions validate-state-machine-definition --type STANDARD --severity WARNING --definition "${def}"
  if [ "${EMU_CLASS}" = "ok" ]; then
    got="$(jq -c '{result: (.result // null), codes: [.diagnostics[]?.code]}' "${EMU_TMP}/out" 2>/dev/null || true)"
    obs="$(printf '%s' "${got}" | jq -r '"result=\(.result) codes=\(.codes | map(tostring) | join(","))"' 2>/dev/null ||
      printf 'unparsed')"
    if [ "${got}" = '{"result":"OK","codes":[]}' ]; then
      rec stepfunctions ValidateStateMachineDefinition implemented-passed probe a-validate observation "${obs} d4a-match=yes"
    else
      rec stepfunctions ValidateStateMachineDefinition implemented-failed probe a-validate observation "${obs} d4a-match=no"
    fi
  else
    rec stepfunctions ValidateStateMachineDefinition "$(answered_class)" probe a-validate errorCode "${EMU_CODE}"
  fi

  for label in ${labels}; do
    emu_call stepfunctions test-state --definition "${def}" --state-name ReadInput \
      --input "$(input_for "${label}")" --inspection-level DEBUG
    if [ "${EMU_CLASS}" != "ok" ]; then
      rec stepfunctions TestState "$(answered_class)" probe "a-test-state-${label}" errorCode "${EMU_CODE}"
      continue
    fi
    got="$(jq -c '((.inspectionData.variables // "{}") | fromjson) as $v
      | ($v.clusterComputeLimits // null) as $l
      | {status: (.status // null), error: (.error // null),
         limits: (if $l == null then null
                  else [$l.UnitType, $l.MinimumCapacityUnits, $l.MaximumCapacityUnits,
                        $l.MaximumOnDemandCapacityUnits, $l.MaximumCoreCapacityUnits] end),
         conf: ($v.sparkShuffleConf // null)}' "${EMU_TMP}/out" 2>/dev/null || true)"
    : >"${EMU_TMP}/out"
    if [ -z "${got}" ]; then
      rec stepfunctions TestState implemented-failed probe "a-test-state-${label}" observation "unparsed answer"
      continue
    fi
    expected="$(expected_for "${label}")"
    match="$(jq -n --argjson a "${got}" --argjson b "${expected}" '$a == $b')"
    obs="$(printf '%s' "${got}" | jq -r '"status=\(.status) error=\(.error) limits=\(if .limits == null then "null" else (.limits | map(tostring) | join(",")) end) conf=\(.conf)"')"
    if [ "${match}" = "true" ]; then
      rec stepfunctions TestState implemented-passed probe "a-test-state-${label}" observation "${obs} d4a-match=yes"
    else
      rec stepfunctions TestState implemented-failed probe "a-test-state-${label}" observation "${obs} d4a-match=no"
    fi
  done
  def=""
}

# ---- (b) S3 Vectors control plane -------------------------------------------------

probe_vector_bucket() {
  step "probe-b-s3vectors"
  local bucket expected got n
  bucket="$(jq -r '.storage.vectorBucket // empty' "${SCHOLARCHAIN_CONFIG_FILE}")"
  expected="$(jq -c '.vectors // {} | [.researcherProfileIndex, .collaborationCommunityIndex, .paperAbstractIndex,
    .collaborationPathIndex, .topicInstitutionSummaryIndex] | map(select(. != null)) | sort' "${SCHOLARCHAIN_CONFIG_FILE}")"
  case "${bucket}" in
    scholarchain-emu-*) ;;
    *) die 4 "refused: storage.vectorBucket of the configuration file does not start with scholarchain-emu-" ;;
  esac
  emu_call s3vectors get-vector-bucket --vector-bucket-name "${bucket}"
  : >"${EMU_TMP}/out"
  if [ "${EMU_CLASS}" = "ok" ]; then
    rec s3vectors GetVectorBucket implemented-passed probe b-get-vector-bucket resource "${bucket}" observation found
  elif [ "${EMU_CODE}" = "NotFoundException" ]; then
    rec s3vectors GetVectorBucket implemented-failed probe b-get-vector-bucket resource "${bucket}" \
      observation "absent (setup.sh did not create it)" errorCode "${EMU_CODE}"
  else
    rec s3vectors GetVectorBucket "$(answered_class)" probe b-get-vector-bucket resource "${bucket}" errorCode "${EMU_CODE}"
  fi
  emu_call s3vectors list-indexes --vector-bucket-name "${bucket}"
  if [ "${EMU_CLASS}" = "ok" ]; then
    got="$(jq -c '[.indexes[]?.indexName] | sort' "${EMU_TMP}/out" 2>/dev/null || printf 'null')"
    n="$(printf '%s' "${got}" | jq -r 'if type == "array" then length else "unparsed" end' 2>/dev/null || printf 'unparsed')"
    if [ "${got}" = "${expected}" ]; then
      rec s3vectors ListIndexes implemented-passed probe b-list-indexes resource "${bucket}" \
        observation "${n} indexes, all five configured names"
    else
      rec s3vectors ListIndexes implemented-failed probe b-list-indexes resource "${bucket}" \
        observation "${n} indexes listed; the five configured names expected"
    fi
  else
    rec s3vectors ListIndexes "$(answered_class)" probe b-list-indexes resource "${bucket}" errorCode "${EMU_CODE}"
  fi
  : >"${EMU_TMP}/out"
}

# ---- (c) M-01: vector-bucket policy denying the caller ----------------------------

probe_m01() {
  step "probe-c-m01"
  local key="m01-${RUN_TAG}" arn="" policy="" got
  emu_call s3vectors create-vector-bucket --vector-bucket-name "${PROBE_VECTOR_BUCKET}"
  if [ "${EMU_CLASS}" = "ok" ] || [ "${EMU_CODE}" = "ConflictException" ]; then
    rec s3vectors CreateVectorBucket implemented-passed probe c-create-bucket resource "${PROBE_VECTOR_BUCKET}" \
      observation "$([ "${EMU_CLASS}" = "ok" ] && printf created || printf exists)"
  else
    rec s3vectors CreateVectorBucket "$(answered_class)" probe c-create-bucket resource "${PROBE_VECTOR_BUCKET}" \
      errorCode "${EMU_CODE}"
    m01_skip_rest "probe bucket not available" create-index
    return 0
  fi
  emu_call s3vectors create-index --vector-bucket-name "${PROBE_VECTOR_BUCKET}" --index-name "${PROBE_INDEX}" \
    --data-type float32 --dimension 4 --distance-metric cosine
  if [ "${EMU_CLASS}" = "ok" ] || [ "${EMU_CODE}" = "ConflictException" ]; then
    rec s3vectors CreateIndex implemented-passed probe c-create-index resource "${PROBE_INDEX}" \
      observation "$([ "${EMU_CLASS}" = "ok" ] && printf created || printf exists)"
  else
    rec s3vectors CreateIndex "$(answered_class)" probe c-create-index resource "${PROBE_INDEX}" errorCode "${EMU_CODE}"
    m01_skip_rest "probe index not available" put-vectors
    return 0
  fi
  emu_call s3vectors put-vectors --vector-bucket-name "${PROBE_VECTOR_BUCKET}" --index-name "${PROBE_INDEX}" \
    --vectors "$(jq -cn --arg k "${key}" '[{key: $k, data: {float32: [0.1, 0.2, 0.3, 0.4]}}]')"
  if [ "${EMU_CLASS}" = "ok" ]; then
    rec s3vectors PutVectors implemented-passed probe c-put-vector resource "${PROBE_INDEX}"
  else
    rec s3vectors PutVectors "$(answered_class)" probe c-put-vector resource "${PROBE_INDEX}" errorCode "${EMU_CODE}"
    m01_skip_rest "probe vector not written" baseline
    return 0
  fi
  emu_call s3vectors get-vectors --vector-bucket-name "${PROBE_VECTOR_BUCKET}" --index-name "${PROBE_INDEX}" \
    --keys "${key}"
  got="$(jq -r --arg k "${key}" '[.vectors[]? | select(.key == $k)] | length' "${EMU_TMP}/out" 2>/dev/null || printf 0)"
  : >"${EMU_TMP}/out"
  if [ "${EMU_CLASS}" = "ok" ] && [ "${got}" = "1" ]; then
    rec s3vectors GetVectors implemented-passed probe c-get-vectors-baseline resource "${PROBE_INDEX}" \
      observation "baseline readable"
  else
    if [ "${EMU_CLASS}" = "ok" ]; then
      rec s3vectors GetVectors implemented-failed probe c-get-vectors-baseline resource "${PROBE_INDEX}" \
        observation "baseline returned ${got} matching vectors"
    else
      rec s3vectors GetVectors "$(answered_class)" probe c-get-vectors-baseline resource "${PROBE_INDEX}" \
        errorCode "${EMU_CODE}"
    fi
    m01_skip_rest "baseline read failed" get-bucket-arn
    return 0
  fi
  # The vector-bucket ARN holds the account: it stays in this variable.
  emu_call s3vectors get-vector-bucket --vector-bucket-name "${PROBE_VECTOR_BUCKET}"
  if [ "${EMU_CLASS}" = "ok" ]; then
    arn="$(jq -r '.vectorBucket.vectorBucketArn // empty' "${EMU_TMP}/out" 2>/dev/null || true)"
  fi
  : >"${EMU_TMP}/out"
  if [ -z "${arn}" ]; then
    if [ "${EMU_CLASS}" = "ok" ]; then
      rec s3vectors GetVectorBucket implemented-failed probe c-get-bucket-arn resource "${PROBE_VECTOR_BUCKET}" \
        observation "no vectorBucketArn in the answer"
    else
      rec s3vectors GetVectorBucket "$(answered_class)" probe c-get-bucket-arn resource "${PROBE_VECTOR_BUCKET}" \
        errorCode "${EMU_CODE}"
    fi
    m01_skip_rest "vector-bucket ARN not available" put-policy
    return 0
  fi
  rec s3vectors GetVectorBucket implemented-passed probe c-get-bucket-arn resource "${PROBE_VECTOR_BUCKET}" \
    observation "ARN read (not recorded)"
  policy="$(jq -cn --arg arn "${arn}" '{Version: "2012-10-17", Statement: [{Sid: "EmulatorM01Probe",
    Effect: "Deny", Principal: "*",
    Action: ["s3vectors:DeleteVectors", "s3vectors:GetVectors", "s3vectors:ListVectors",
             "s3vectors:PutVectors", "s3vectors:QueryVectors"],
    Resource: [$arn, ($arn + "/index/*")]}]}')"
  arn=""
  emu_call s3vectors put-vector-bucket-policy --vector-bucket-name "${PROBE_VECTOR_BUCKET}" --policy "${policy}"
  policy=""
  if [ "${EMU_CLASS}" = "ok" ]; then
    rec s3vectors PutVectorBucketPolicy implemented-passed probe c-put-policy resource "${PROBE_VECTOR_BUCKET}"
  else
    rec s3vectors PutVectorBucketPolicy "$(answered_class)" probe c-put-policy resource "${PROBE_VECTOR_BUCKET}" \
      errorCode "${EMU_CODE}"
    m01_skip_rest "policy not stored" get-vectors-after
    return 0
  fi
  emu_call s3vectors get-vectors --vector-bucket-name "${PROBE_VECTOR_BUCKET}" --index-name "${PROBE_INDEX}" \
    --keys "${key}"
  got="$(jq -r --arg k "${key}" '[.vectors[]? | select(.key == $k)] | length' "${EMU_TMP}/out" 2>/dev/null || printf 0)"
  : >"${EMU_TMP}/out"
  if [ "${EMU_CLASS}" = "ok" ]; then
    rec s3vectors GetVectors implemented-passed probe c-get-vectors-after-deny resource "${PROBE_INDEX}" \
      observation "allowed (policy not enforced; ${got} vector returned; real AWS refused in Phase 15 M-01)"
  elif [ "${EMU_CODE}" = "AccessDeniedException" ] || [ "${EMU_CODE}" = "AccessDenied" ]; then
    rec s3vectors GetVectors implemented-passed probe c-get-vectors-after-deny resource "${PROBE_INDEX}" \
      observation "refused (as real AWS in Phase 15 M-01)" errorCode "${EMU_CODE}"
  else
    rec s3vectors GetVectors "$(answered_class)" probe c-get-vectors-after-deny resource "${PROBE_INDEX}" \
      errorCode "${EMU_CODE}"
  fi
}

# m01_skip_rest REASON FROM: records the M-01 calls from FROM on as not-exercised.
m01_skip_rest() {
  local reason="$1" from="$2" started=0 entry probe op
  for entry in create-index:CreateIndex put-vectors:PutVectors baseline:GetVectors \
    get-bucket-arn:GetVectorBucket put-policy:PutVectorBucketPolicy get-vectors-after:GetVectors; do
    probe="${entry%%:*}"
    op="${entry##*:}"
    [ "${probe}" = "${from}" ] && started=1
    [ "${started}" -eq 1 ] || continue
    skip s3vectors "${op}" "${reason}" probe "c-${probe}"
  done
}

main() {
  parse_args "$@"
  prerequisites
  trap 'on_err ${LINENO}' ERR
  trap 'on_signal INT' INT
  trap 'on_signal TERM' TERM
  trap on_exit EXIT
  safety
  EMU_TMP="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-emu-probes.XXXXXX")"
  preconditions
  probe_step_functions
  probe_vector_bucket
  probe_m01
  step "summary"
  printf 'SUMMARY: %s implemented-passed=%s implemented-failed=%s not-implemented=%s not-exercised=%s infrastructure-failure=%s emulator-account-confirmed=%s plan=%s\n' \
    "${SCRIPT_NAME}" "${N_PASSED}" "${N_FAILED}" "${N_NOT_IMPLEMENTED}" "${N_NOT_EXERCISED}" "${N_INFRA}" \
    "${ACCOUNT_CONFIRMED}" "${PLAN_NAME}"
  if [ "${N_FAILED}" -gt 0 ] || [ "${N_INFRA}" -gt 0 ]; then
    die 6 "$((N_FAILED + N_INFRA)) probe call(s) implemented-failed or infrastructure-failure (results complete in probes.jsonl)"
  fi
  step "done"
}

main "$@"
