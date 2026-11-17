#!/usr/bin/env bash
# scripts/smoke-test.sh - minimal smoke test of the deployed ScholarChain API
# through the API Gateway URL. Owner: infra-engineer (author); run by the
# human or deploy-engineer after a deployment. Phase 13 completes it.
#
# Status (Phase 12): static: shellcheck clean; runtime: untested (Phase 13/14
# runs it); human execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="smoke-test.sh"
readonly TF_DIR="infra/terraform/environments/course"
readonly KEY_ENV_NAME="SCHOLARCHAIN_SERVICE_API_KEY"
readonly OUTPUT_TIMEOUT=120
readonly CONNECT_TIMEOUT=10
readonly SHORT_MAX_TIME=15
# API Gateway ends integrations at about 30 s; service.syncDeadlineMillis is 25 s.
readonly QUERY_MAX_TIME=35

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
REPO_ROOT=""

usage() {
  cat <<'EOF'
Usage: scripts/smoke-test.sh --question "<question text>" [--api-url https://...]
       scripts/smoke-test.sh --help

What it does (its whole contract; minimal, Phase 13 completes it):
  1. Reads the API address from the Terraform output api_url (or --api-url).
  2. GET  /healthz  -> expects 200 and status "ok".
  3. GET  /readyz   -> expects 200 and status "ready" (every store check ok).
  4. POST /v1/query -> one bounded synchronous query (mode "sync",
     includeEvidence true) with the given question; expects 200 and a
     queryId. curl stops after 35 s (API Gateway ends calls at about 30 s).
  5. GET  the first evidence link of that answer (/v1/evidence/<id>) ->
     expects 200 and an evidenceId. An answer that cites no evidence fails
     this step (choose a question the loaded graph can answer).

The API key is read ONLY from the environment variable
SCHOLARCHAIN_SERVICE_API_KEY, which the human sets in their own shell. The
script refuses to start if it is unset or empty. The key is passed to curl on
standard input (never on a command line) and is never printed. Response
bodies stay in a private temporary folder that is removed at exit; only
status codes and identifiers are printed.

It changes nothing in AWS and writes nothing in the repository. It asks no
question. Requirements: bash, terraform (output only, unless --api-url),
curl (7.55+), jq (1.6+), timeout or gtimeout (GNU coreutils).

Exit codes: 0 every check passed; 2 usage error; 3 missing prerequisite
(tool, api_url output, or the key variable); 5 terraform output failed or a
request could not be sent; 6 a check failed (evidence printed); 7 timed out.

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

check_prerequisites() {
  step "prerequisites"
  local tool
  for tool in curl jq; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  else
    die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  # Presence check only; the value is never printed.
  if [ -z "${SCHOLARCHAIN_SERVICE_API_KEY:-}" ]; then
    die 3 "refusing to start: ${KEY_ENV_NAME} is not set in this shell"
  fi
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-smoke.XXXXXX")"
}

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
  [[ "${API_URL}" =~ ^https:// ]] || die 3 "api_url must be an https:// address"
  info "API: ${API_URL}"
}

# request METHOD PATH MAX_TIME AUTH(yes|no) [BODY_FILE]; prints the HTTP status;
# the response body goes to ${TMP_DIR}/body.json.
request() {
  local method="$1" path="$2" max_time="$3" auth="$4" body_file="${5:-}"
  local args=(--silent --show-error --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${max_time}"
    --request "${method}" --output "${TMP_DIR}/body.json" --write-out '%{http_code}'
    --header "Accept: application/json")
  if [ -n "${body_file}" ]; then
    args+=(--header "Content-Type: application/json" --data-binary "@${body_file}")
  fi
  local rc=0 status
  if [ "${auth}" = "yes" ]; then
    # The key travels on stdin (curl -H @-), never on a command line.
    status="$(printf 'X-Api-Key: %s\n' "${SCHOLARCHAIN_SERVICE_API_KEY}" |
      curl "${args[@]}" --header @- "${API_URL}${path}")" || rc=$?
  else
    status="$(curl "${args[@]}" "${API_URL}${path}")" || rc=$?
  fi
  [ "${rc}" -eq 28 ] && die 7 "${method} ${path}: no answer within ${max_time}s"
  [ "${rc}" -eq 0 ] || die 5 "${method} ${path}: request failed (curl status ${rc})"
  printf '%s' "${status}"
}

expect_status() {
  local what="$1" got="$2" want="$3"
  if [ "${got}" != "${want}" ]; then
    printf 'EVIDENCE: %s status=%s expected=%s error=%s\n' "${what}" "${got}" "${want}" \
      "$(jq -r '.error // .status // "n/a"' "${TMP_DIR}/body.json" 2>/dev/null || printf 'unreadable body')" >&2
    die 6 "${what} failed"
  fi
}

check_health() {
  step "healthz"
  local status
  status="$(request GET /healthz "${SHORT_MAX_TIME}" no)" || exit "$?"
  expect_status "GET /healthz" "${status}" 200
  [ "$(jq -r '.status // empty' "${TMP_DIR}/body.json")" = "ok" ] || die 6 "GET /healthz body status is not ok"
  printf 'SMOKE: healthz ok role=%s\n' "$(jq -r '.role // "?"' "${TMP_DIR}/body.json")"
}

check_ready() {
  step "readyz"
  local status
  status="$(request GET /readyz "${SHORT_MAX_TIME}" no)" || exit "$?"
  if [ "${status}" != "200" ]; then
    printf 'EVIDENCE: readyz checks %s\n' "$(jq -c '.checks // {}' "${TMP_DIR}/body.json" 2>/dev/null || printf '{}')" >&2
  fi
  expect_status "GET /readyz" "${status}" 200
  printf 'SMOKE: readyz ready checks=%s\n' "$(jq -c '.checks // {}' "${TMP_DIR}/body.json")"
}

EVIDENCE_PATH=""

check_query() {
  step "query"
  local req="${TMP_DIR}/query.json"
  jq -n --arg q "${QUESTION}" \
    '{question: $q, mode: "sync", output: {includeEvidence: true}}' >"${req}"
  local status
  status="$(request POST /v1/query "${QUERY_MAX_TIME}" yes "${req}")" || exit "$?"
  expect_status "POST /v1/query" "${status}" 200
  local query_id
  query_id="$(jq -r '.queryId // empty' "${TMP_DIR}/body.json")"
  [ -n "${query_id}" ] || die 6 "POST /v1/query: no queryId in the answer"
  EVIDENCE_PATH="$(jq -r '.links.evidence[0] // empty' "${TMP_DIR}/body.json")"
  printf 'SMOKE: query ok queryId=%s abstained=%s claims=%s evidence_links=%s\n' "${query_id}" \
    "$(jq -r '.answer.abstained' "${TMP_DIR}/body.json")" \
    "$(jq -r '.answer.claims | length' "${TMP_DIR}/body.json")" \
    "$(jq -r '.links.evidence | length' "${TMP_DIR}/body.json")"
}

check_evidence() {
  step "evidence"
  [ -n "${EVIDENCE_PATH}" ] || die 6 "the answer cites no evidence; nothing to fetch (choose a question the loaded graph answers)"
  [[ "${EVIDENCE_PATH}" =~ ^/v1/evidence/ ]] || die 6 "unexpected evidence link form"
  local status
  status="$(request GET "${EVIDENCE_PATH}" "${SHORT_MAX_TIME}" yes)" || exit "$?"
  expect_status "GET /v1/evidence" "${status}" 200
  local eid
  eid="$(jq -r '.evidenceId // empty' "${TMP_DIR}/body.json")"
  [ -n "${eid}" ] || die 6 "GET /v1/evidence: no evidenceId in the answer"
  printf 'SMOKE: evidence ok evidenceId=%s type=%s\n' "${eid}" "$(jq -r '.evidenceType // "?"' "${TMP_DIR}/body.json")"
}

main() {
  parse_args "$@"
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
  cd "${REPO_ROOT}"
  check_prerequisites
  read_api_url
  check_health
  check_ready
  check_query
  check_evidence
  step "done"
}

main "$@"
