#!/usr/bin/env bash
# scripts/smoke-test.sh - smoke test of the deployed ScholarChain API through
# the API Gateway URL (spec work-order item 5). Owner: infra-engineer
# (author); run by the human or deploy-engineer after a deployment.
#
# Status (Phase 13): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="smoke-test.sh"
readonly TF_DIR="infra/terraform/environments/course"
readonly KEY_ENV_NAME="SCHOLARCHAIN_SERVICE_API_KEY"
readonly RECORD_DIR="evidence/runs"
readonly OUTPUT_TIMEOUT=120
readonly CONNECT_TIMEOUT=10
readonly SHORT_MAX_TIME=15
# API Gateway ends integrations at about 30 s; service.syncDeadlineMillis is 25 s.
readonly QUERY_MAX_TIME=35
readonly GET_TRIES=3

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
DIRTY=""
STARTED_UTC=""
CHECKS_JSON="[]"

usage() {
  cat <<'EOF'
Usage: scripts/smoke-test.sh --question TEXT [--api-url https://HOST]
       scripts/smoke-test.sh --help

Run it from the repository root (bash scripts/smoke-test.sh); it refuses
elsewhere. Choose a question the loaded graph can answer, for example
--question 'Who are the most frequent coauthors of <a researcher in the
loaded snapshot>?' (quote it in zsh).

What it does (its whole contract), against the routes, headers, status codes
and error model of modules/services (Routes.scala, HttpAdapter.scala,
Errors.scala, Models.scala, QueryService.scala):
  1. key       Refuses (exit 3) before any network call when
               SCHOLARCHAIN_SERVICE_API_KEY is unset or empty, and (exit 4)
               when its value holds a control character (newline, carriage
               return), a double quote or a backslash.
  2. api-url   Reads the API address from the Terraform output api_url (or
               --api-url); it must be https://.
  3. healthz   GET /healthz -> 200, body status "ok" (no key).
  4. readyz    GET /readyz -> 200, body status "ready" (no key).
  5. auth-error  POST /v1/query WITHOUT the key -> 401 with the documented
               error body {"error": "UNAUTHENTICATED", "retryable": false,
               ...}; checked before any query runs, so it costs nothing.
  6. query     ONE bounded researcher query: POST /v1/query, mode "sync",
               output.includeEvidence true, with the header
               Idempotency-Key: smoke-ATTEMPT_ID -> 200, a queryId, at least
               one claim in answer.claims and at least one evidence link.
               curl stops after 35 s (API Gateway ends calls at about 30 s;
               the service's synchronous deadline is 25 s).
  7. evidence  GET the first evidence link of that answer
               (/v1/evidence/ID[?window=...]) -> 200 and an evidenceId.
  8. idempotency-replay  The same body with the same Idempotency-Key ->
               200, header Idempotent-Replayed: true, and the same queryId
               (served from the stored job; no second model call).
  9. idempotency-conflict  The same Idempotency-Key with a different body
               (the question with " (2)" appended) -> 409 with error
               IDEMPOTENCY_CONFLICT (decided before any workflow runs).

API key handling (exact): the key is read only from the environment variable
SCHOLARCHAIN_SERVICE_API_KEY (the header is X-Api-Key, Auth.scala and
HttpAdapter.scala). For each request that needs it, the script writes ONE
curl config line, header = "X-Api-Key: KEY", to curl's standard input with
printf (a shell builtin, so the key never appears in any process's
arguments), and curl reads it with --config -. Request bodies come from a
file in the private temporary folder (--data-binary @FILE), never from
standard input. Never set -x, never curl -v or --trace, never -H with the
key, never the key in a message: errors print the status code and the
response's error code only. Response bodies and headers stay in the private
temporary folder, which is removed at exit.

Record: evidence/runs/ATTEMPT_ID-smoke-test.started.json, then
evidence/runs/ATTEMPT_ID-smoke-test.json (gitignored; never overwritten):
HEAD, build commit, the dirty flag (a dirty tree is recorded, not refused),
the API host, each check with its status code and result, the queryId and
evidenceId. No key, account ID or bucket name.

Retries: only the GET requests (healthz, readyz, evidence) are retried, up to
3 times, when curl could not complete them; POST requests are never retried.

It changes nothing in AWS beyond what the API itself stores for one query
(one job and one idempotency record, both expiring). It asks no question.
Requirements: bash, git, jq (1.6+), curl (7.55+), timeout or gtimeout (GNU
coreutils), terraform (output only, unless --api-url).

Exit codes: 0 every check passed; 2 usage error; 3 missing prerequisite
(tool, api_url output, or the key variable); 4 refused (not at the
repository root, unusable key value); 5 terraform output failed or a request
could not be sent; 6 a check failed (status and error code printed); 7 timed
out.

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

QUESTION=""
API_URL=""

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --question)
        [ "$#" -ge 2 ] || die 2 "--question needs a value"
        QUESTION="$2"
        shift 2
        ;;
      --api-url)
        [ "$#" -ge 2 ] || die 2 "--api-url needs a value"
        API_URL="$2"
        shift 2
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
  [ -n "${QUESTION}" ] || {
    usage >&2
    die 2 "--question is required"
  }
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

# Presence and shape of the key; the value is never printed. Runs before any
# network call (check.sh probe: exit 3 when the variable is unset).
check_key() {
  step "key"
  if [ -z "${SCHOLARCHAIN_SERVICE_API_KEY:-}" ]; then
    die 3 "refusing to start: ${KEY_ENV_NAME} is not set in this shell"
  fi
  case "${SCHOLARCHAIN_SERVICE_API_KEY}" in
    *[[:cntrl:]]* | *'"'* | *\\*)
      die 4 "refusing: the value of ${KEY_ENV_NAME} holds a control character, a double quote or a backslash; it cannot be passed safely"
      ;;
  esac
  info "${KEY_ENV_NAME} is set (value not shown)"
}

check_prerequisites() {
  step "prerequisites"
  local tool
  for tool in git curl jq; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  else
    die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-smoke.XXXXXX")"
  # I-18: the response file paths are fixed here, in the parent shell. request()
  # always runs inside a $(...) subshell, so it must never assign them itself;
  # it only writes these files, which the parent then reads.
  BODY="${TMP_DIR}/body.json"
  HEADERS="${TMP_DIR}/headers.txt"
}

# ---- record ----------------------------------------------------------------------

API_HOST=""
QUERY_ID=""
EVIDENCE_ID=""

record_json() {
  jq -n --arg kind "scholarchain-smoke-test-record" --arg status "$1" --arg attempt "${ATTEMPT_ID}" \
    --arg head "${HEAD_SHA}" --arg build "${BUILD_SHA}" --argjson dirty "${DIRTY}" \
    --arg host "${API_HOST}" --arg started "${STARTED_UTC}" --arg ended "${2:-}" \
    --argjson exit_code "${3:-null}" --arg step "${CURRENT_STEP}" --arg signal "${SIGNAL_NAME}" \
    --arg qid "${QUERY_ID}" --arg eid "${EVIDENCE_ID}" --argjson checks "${CHECKS_JSON}" \
    '{kind: $kind, version: 1, status: $status, attempt_id: $attempt, head: $head,
      build_commit: $build, tree_dirty: $dirty, api_host: $host,
      started_utc: $started, ended_utc: (if $ended == "" then null else $ended end),
      exit_code: $exit_code,
      failed_step: (if ($status | IN("started", "succeeded")) then null else $step end),
      signal: (if $signal == "" then null else $signal end),
      query_id: (if $qid == "" then null else $qid end),
      evidence_id: (if $eid == "" then null else $eid end),
      checks: $checks}'
}

start_record() {
  step "record"
  HEAD_SHA="$(head_commit)" || die 3 "not a git repository with a commit"
  BUILD_SHA="$(build_commit)" || BUILD_SHA="unknown"
  DIRTY="$(tree_dirty_flag)" || DIRTY="true"
  ATTEMPT_ID="$(new_attempt_id)"
  STARTED_UTC="$(sc_utc_now)"
  RECORD_FILE="${RECORD_DIR}/${ATTEMPT_ID}-smoke-test.json"
  git check-ignore -q -- "${RECORD_DIR}/x" || die 4 "refusing: ${RECORD_DIR}/ is not gitignored"
  record_json "started" | write_record_atomic "${RECORD_DIR}/${ATTEMPT_ID}-smoke-test.started.json" ||
    die 5 "could not write the started record"
  STARTED_WRITTEN="true"
  printf 'ATTEMPT: %s\n' "${ATTEMPT_ID}"
  info "HEAD ${HEAD_SHA}, build commit ${BUILD_SHA}, dirty ${DIRTY} (recorded, not refused)"
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
    printf 'WARN: %s could not write the final record %s\n' "${SCRIPT_NAME}" "${RECORD_FILE}" >&2
  fi
}

add_check() {
  # add_check NAME HTTP_STATUS RESULT
  CHECKS_JSON="$(jq -c --arg n "$1" --arg s "$2" --arg r "$3" \
    '. + [{check: $n, http_status: $s, result: $r}]' <<<"${CHECKS_JSON}")"
}

# ---- requests --------------------------------------------------------------------

read_api_url() {
  step "api-url"
  if [ -z "${API_URL}" ]; then
    command -v terraform >/dev/null 2>&1 || die 3 "required tool not found: terraform"
    local rc=0 outputs
    outputs="$("${TIMEOUT_BIN}" --kill-after=30 "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" \
      output -json -no-color)" || rc=$?
    [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
    [ "${rc}" -eq 0 ] || die 5 "terraform output failed (status ${rc})"
    API_URL="$(jq -r '.api_url.value // empty' <<<"${outputs}")"
  fi
  API_URL="${API_URL%/}"
  [ -n "${API_URL}" ] || die 3 "no api_url output (is the environment applied?)"
  [[ "${API_URL}" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$ ]] ||
    die 3 "api_url must be an https:// address"
  API_HOST="${API_URL#https://}"
  API_HOST="${API_HOST%%/*}"
  info "API: ${API_URL}"
}

# Response files of the last request. Set once by check_prerequisites (parent
# shell) after TMP_DIR exists; never assigned inside request() (I-18).
BODY=""
HEADERS=""

# request METHOD PATH MAX_TIME AUTH(yes|no) [BODY_FILE] [IDEMPOTENCY_KEY]:
# prints the HTTP status; the response goes to the files ${BODY} and
# ${HEADERS}, whose paths the parent set. Callers run it in $(...), so it
# only writes files and prints the status; it sets no shell variable the
# caller reads. The key travels only as a curl config line on standard input
# (--config -).
request() {
  local method="$1" path="$2" max_time="$3" auth="$4" body_file="${5:-}" idem="${6:-}"
  if [ -z "${BODY}" ] || [ -z "${HEADERS}" ]; then
    return 5
  fi
  rm -f -- "${BODY}" "${HEADERS}"
  local args=(--silent --show-error --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${max_time}"
    --request "${method}" --output "${BODY}" --dump-header "${HEADERS}" --write-out '%{http_code}'
    --header "Accept: application/json")
  if [ -n "${body_file}" ]; then
    args+=(--header "Content-Type: application/json" --data-binary "@${body_file}")
  fi
  if [ -n "${idem}" ]; then
    args+=(--header "Idempotency-Key: ${idem}")
  fi
  local rc=0 status
  if [ "${auth}" = "yes" ]; then
    status="$(printf 'header = "X-Api-Key: %s"\n' "${SCHOLARCHAIN_SERVICE_API_KEY}" |
      "${TIMEOUT_BIN}" --kill-after=5 "$((max_time + 10))" curl --config - "${args[@]}" "${API_URL}${path}")" || rc=$?
  else
    status="$("${TIMEOUT_BIN}" --kill-after=5 "$((max_time + 10))" curl "${args[@]}" "${API_URL}${path}" </dev/null)" || rc=$?
  fi
  case "${rc}" in
    0) ;;
    28 | 124 | 137) return 7 ;;
    *) return 5 ;;
  esac
  [[ "${status}" =~ ^[0-9]{3}$ ]] || return 5
  printf '%s' "${status}"
}

# get_with_retry PATH MAX_TIME AUTH: GET only (idempotent); retried when curl
# could not complete the request.
get_with_retry() {
  local path="$1" max_time="$2" auth="$3" try=1 rc status
  while :; do
    rc=0
    status="$(request GET "${path}" "${max_time}" "${auth}")" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      printf '%s' "${status}"
      return 0
    fi
    [ "${try}" -lt "${GET_TRIES}" ] || return "${rc}"
    try=$((try + 1))
    printf 'WARN: %s GET %s could not complete (status %s); retry %s/%s\n' \
      "${SCRIPT_NAME}" "${path%%\?*}" "${rc}" "${try}" "${GET_TRIES}" >&2
    sleep $((try * 3))
  done
}

body_error() {
  jq -r '.error // "n/a"' "${BODY}" 2>/dev/null || printf 'unreadable-body'
}

# expect STEP-NAME GOT WANT: on a mismatch, prints the status and the error
# code only, records the check and fails with 6.
expect() {
  local what="$1" got="$2" want="$3"
  if [ "${got}" != "${want}" ]; then
    printf 'EVIDENCE: %s status=%s expected=%s error=%s\n' "${what}" "${got}" "${want}" "$(body_error)" >&2
    add_check "${what}" "${got}" "failed: expected ${want}, error $(body_error)"
    die 6 "${what} failed"
  fi
}

sent_or_die() {
  # sent_or_die RC WHAT
  case "$1" in
    0) ;;
    7) die 7 "$2: no answer in time" ;;
    *) die 5 "$2: the request could not be sent or answered" ;;
  esac
}

check_health() {
  step "healthz"
  local status rc=0
  status="$(get_with_retry /healthz "${SHORT_MAX_TIME}" no)" || rc=$?
  sent_or_die "${rc}" "GET /healthz"
  expect "GET /healthz" "${status}" 200
  [ "$(jq -r '.status // empty' "${BODY}")" = "ok" ] || {
    add_check healthz "${status}" "failed: status is not ok"
    die 6 "GET /healthz body status is not ok"
  }
  add_check healthz "${status}" "ok"
  printf 'SMOKE: healthz ok role=%s\n' "$(jq -r '.role // "?"' "${BODY}")"
}

check_ready() {
  step "readyz"
  local status rc=0
  status="$(get_with_retry /readyz "${SHORT_MAX_TIME}" no)" || rc=$?
  sent_or_die "${rc}" "GET /readyz"
  if [ "${status}" != "200" ]; then
    printf 'EVIDENCE: readyz checks %s\n' "$(jq -c '.checks // {}' "${BODY}" 2>/dev/null || printf '{}')" >&2
  fi
  expect "GET /readyz" "${status}" 200
  [ "$(jq -r '.status // empty' "${BODY}")" = "ready" ] || {
    add_check readyz "${status}" "failed: status is not ready"
    die 6 "GET /readyz body status is not ready"
  }
  add_check readyz "${status}" "ok"
  printf 'SMOKE: readyz ready checks=%s\n' "$(jq -c '.checks // {}' "${BODY}")"
}

QUERY_REQ=""
CONFLICT_REQ=""
IDEM_KEY=""

prepare_requests() {
  QUERY_REQ="${TMP_DIR}/query.json"
  CONFLICT_REQ="${TMP_DIR}/query-conflict.json"
  jq -n --arg q "${QUESTION}" \
    '{question: $q, mode: "sync", output: {includeEvidence: true}}' >"${QUERY_REQ}"
  jq -n --arg q "${QUESTION} (2)" \
    '{question: $q, mode: "sync", output: {includeEvidence: true}}' >"${CONFLICT_REQ}"
  IDEM_KEY="smoke-${ATTEMPT_ID}"
  [[ "${IDEM_KEY}" =~ ^[A-Za-z0-9._:-]{1,128}$ ]] || die 6 "internal: the idempotency key has an unexpected form"
}

check_auth_error() {
  step "auth-error"
  local status rc=0 err retryable
  status="$(request POST /v1/query "${SHORT_MAX_TIME}" no "${QUERY_REQ}")" || rc=$?
  sent_or_die "${rc}" "POST /v1/query without key"
  expect "POST /v1/query without key" "${status}" 401
  err="$(body_error)"
  retryable="$(jq -r '.retryable' "${BODY}" 2>/dev/null || printf 'n/a')"
  if [ "${err}" != "UNAUTHENTICATED" ] || [ "${retryable}" != "false" ] ||
    ! jq -e '(.message | type) == "string" and (.traceId | type) == "string"' "${BODY}" >/dev/null 2>&1; then
    add_check auth-error "${status}" "failed: error=${err} retryable=${retryable}"
    die 6 "POST /v1/query without key: the body is not the documented UNAUTHENTICATED error"
  fi
  add_check auth-error "${status}" "ok: UNAUTHENTICATED"
  printf 'SMOKE: auth-error ok status=401 error=UNAUTHENTICATED\n'
}

EVIDENCE_PATH=""

check_query() {
  step "query"
  local status rc=0 claims links
  status="$(request POST /v1/query "${QUERY_MAX_TIME}" yes "${QUERY_REQ}" "${IDEM_KEY}")" || rc=$?
  sent_or_die "${rc}" "POST /v1/query"
  expect "POST /v1/query" "${status}" 200
  QUERY_ID="$(jq -r '.queryId // empty' "${BODY}")"
  [ -n "${QUERY_ID}" ] || {
    add_check query "${status}" "failed: no queryId"
    die 6 "POST /v1/query: no queryId in the answer"
  }
  claims="$(jq -r '(.answer.claims // []) | length' "${BODY}")"
  links="$(jq -r '(.links.evidence // []) | length' "${BODY}")"
  printf 'SMOKE: query queryId=%s abstained=%s claims=%s evidence_links=%s\n' "${QUERY_ID}" \
    "$(jq -r '.answer.abstained' "${BODY}")" "${claims}" "${links}"
  if [ "${claims}" -lt 1 ]; then
    add_check query "${status}" "failed: no claim"
    die 6 "the answer has no claim (choose a question the loaded graph answers)"
  fi
  EVIDENCE_PATH="$(jq -r '.links.evidence[0] // empty' "${BODY}")"
  add_check query "${status}" "ok: ${claims} claim(s), ${links} evidence link(s)"
}

check_evidence() {
  step "evidence"
  [ -n "${EVIDENCE_PATH}" ] || {
    add_check evidence "-" "failed: the answer cites no evidence"
    die 6 "the answer cites no evidence; nothing to fetch (choose a question the loaded graph answers)"
  }
  [[ "${EVIDENCE_PATH}" =~ ^/v1/evidence/[A-Za-z0-9._:~%-]+(\?window=[0-9]+-[0-9]+)?$ ]] || {
    add_check evidence "-" "failed: unexpected evidence link form"
    die 6 "unexpected evidence link form"
  }
  local status rc=0
  status="$(get_with_retry "${EVIDENCE_PATH}" "${SHORT_MAX_TIME}" yes)" || rc=$?
  sent_or_die "${rc}" "GET /v1/evidence"
  expect "GET /v1/evidence" "${status}" 200
  EVIDENCE_ID="$(jq -r '.evidenceId | if type == "object" then (.value // empty) else (. // empty) end' "${BODY}" 2>/dev/null || true)"
  [ -n "${EVIDENCE_ID}" ] || {
    add_check evidence "${status}" "failed: no evidenceId"
    die 6 "GET /v1/evidence: no evidenceId in the answer"
  }
  add_check evidence "${status}" "ok"
  printf 'SMOKE: evidence ok evidenceId=%s type=%s\n' "${EVIDENCE_ID}" "$(jq -r '.evidenceType // "?"' "${BODY}")"
}

check_idempotency() {
  step "idempotency-replay"
  local status rc=0 replayed qid
  status="$(request POST /v1/query "${QUERY_MAX_TIME}" yes "${QUERY_REQ}" "${IDEM_KEY}")" || rc=$?
  sent_or_die "${rc}" "POST /v1/query replay"
  expect "POST /v1/query replay" "${status}" 200
  qid="$(jq -r '.queryId // empty' "${BODY}")"
  replayed="$(grep -i -E '^Idempotent-Replayed:' "${HEADERS}" | tail -n 1 | cut -d: -f2- | tr -d ' \r' || true)"
  if [ "${qid}" != "${QUERY_ID}" ] || [ "${replayed}" != "true" ]; then
    add_check idempotency-replay "${status}" "failed: queryId same=$([ "${qid}" = "${QUERY_ID}" ] && printf yes || printf no) replayed=${replayed:-absent}"
    die 6 "the replay with the same Idempotency-Key did not return the same query (queryId or Idempotent-Replayed header)"
  fi
  add_check idempotency-replay "${status}" "ok: same queryId, Idempotent-Replayed: true"
  printf 'SMOKE: idempotency-replay ok queryId=%s\n' "${qid}"

  step "idempotency-conflict"
  rc=0
  status="$(request POST /v1/query "${QUERY_MAX_TIME}" yes "${CONFLICT_REQ}" "${IDEM_KEY}")" || rc=$?
  sent_or_die "${rc}" "POST /v1/query conflict"
  expect "POST /v1/query conflict" "${status}" 409
  [ "$(body_error)" = "IDEMPOTENCY_CONFLICT" ] || {
    add_check idempotency-conflict "${status}" "failed: error $(body_error)"
    die 6 "the same Idempotency-Key with another body did not give IDEMPOTENCY_CONFLICT"
  }
  add_check idempotency-conflict "${status}" "ok: IDEMPOTENCY_CONFLICT"
  printf 'SMOKE: idempotency-conflict ok status=409 error=IDEMPOTENCY_CONFLICT\n'
}

main() {
  parse_args "$@"
  enter_repo_root
  check_key
  check_prerequisites
  start_record
  read_api_url
  prepare_requests
  check_health
  check_ready
  check_auth_error
  check_query
  check_evidence
  check_idempotency
  step "done"
}

main "$@"
