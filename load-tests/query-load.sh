#!/usr/bin/env bash
# load-tests/query-load.sh - closed-loop load generator for experiment D
# (Phase 15 work order, decision 11 item 1). Owner: experiment-engineer.
# Run by the human only, through scripts/run-experiment.sh and the D collector
# (experiments/collectors/d-load.sh); never by an agent.
#
# runtime: untested
# static: shellcheck (see the 6a report); bash 3.2 constructs only.

set -Eeuo pipefail
umask 077

readonly TOOL_NAME="query-load.sh"
readonly TOOL_VERSION=1
readonly TF_DIR="infra/terraform/environments/course"
readonly KEY_ENV_NAME="SCHOLARCHAIN_SERVICE_API_KEY"
readonly QUESTIONS_DEFAULT="load-tests/questions-d.txt"
readonly IDS_DEFAULT="load-tests/read-ids-d.txt"
readonly OUTPUT_TIMEOUT=120
readonly CONNECT_TIMEOUT=10
# API Gateway ends integrations at about 30 s; service.syncDeadlineMillis is 25 s.
readonly QUERY_MAX_TIME=35
readonly READ_MAX_TIME=15
readonly SHORT_MAX_TIME=15
readonly READY_TRIES=3
readonly READY_BACKOFF_SECONDS=5
# Hard caps (decision 11 item 1); arguments may only lower them.
readonly QUERY_HARD_CAP=60
readonly READ_HARD_CAP=600
readonly QUERY_MAX_CLIENTS=8
readonly READ_MAX_CLIENTS=20
readonly MAX_LEVELS=6
readonly LEVEL_SECONDS_MIN=10
readonly LEVEL_SECONDS_MAX=300
readonly PAUSE_SECONDS_MAX=300
readonly EXPLAIN_PHASE_SECONDS=150
readonly LIST_MAX_ENTRIES=200
# Spacing of sequential single requests (ID check, explain reads): under the
# API Gateway stage rate of 5 requests per second.
readonly SEQUENTIAL_GAP="0.3"

# The stage keys QueryWorkflow stores in the query audit's stageMillis
# (modules/rag/.../QueryWorkflow.scala:176-231, :746-758; the "audit" stage is
# timed after the record is built, :451-452, so it is never stored). A key the
# explain response does not carry is recorded as an empty cell (missing), never 0.
readonly STAGE_KEYS="planner plannerFallback resolver metrics paths community semantic evidence identity synthesis synthesisFallback verification"

CURRENT_STEP="start"
TIMEOUT_BIN=""
TMP_DIR=""
OUT_DIR=""
API_URL=""
MODE=""
LEVELS_ARG=""
LEVEL_SECONDS=120
CAP_ARG=""
PAUSE_SECONDS=60
LIST_FILE=""
PREFLIGHT="false"
RUN_TAG=""
CAP=0
MAX_CLIENTS=0
MAX_TIME=0
SUCCESS_CLASS=""
METHOD=""
LIST_SHA=""
TOOL_SHA=""
START_EPOCH=""
STATUS="started"
RECORD_WRITTEN="false"
ARGS_JSON="[]"
LEVELS_JSON="[]"
ERRORS_JSON="[]"
CLIENT_PIDS=()
ITEM_IDS=()
ITEM_KINDS=()
ITEM_PATHS=()
ITEM_BODIES=()
ITEM_COUNT=0

usage() {
  cat <<'EOF'
Usage: load-tests/query-load.sh --mode query|read [options]
       load-tests/query-load.sh --help

Closed-loop load generator for experiment D (bash 3.2, curl, jq). Run it from
the repository root. Each client sends its next request only after the answer
to the previous one. A level ends at whichever comes first: its fixed
duration or its hard cap of requests.

Modes:
  --mode query   POST /v1/query (sync), questions in rotation from
                 load-tests/questions-d.txt, a fresh Idempotency-Key per
                 request. Default levels 1,2,4,8 clients, 120 s each, at most
                 60 queries per level. After each level's window (before the
                 pause) one GET /v1/explain/{queryId} per answered or
                 abstained query of that level, sequential, no LLM.
  --mode read    GET /v1/graph/nodes/{nodeId}/neighbors?depth=1&limit=50 and
                 GET /v1/evidence/{evidenceId} from load-tests/read-ids-d.txt,
                 no LLM. Default levels 1,5,10,20 clients, 120 s each, at most
                 600 requests per level. Before the first level, one request
                 per listed ID (the ID check, recorded in id-check.csv).

Options:
  --levels N,N,...           client counts (at most 6 levels; query at most 8
                             clients, read at most 20)
  --level-seconds N          duration of each level (10..300, default 120)
  --max-requests-per-level N lower the hard cap (query 60, read 600)
  --pause-seconds N          pause after each level (0..300, default 60)
  --list FILE                the question list (query) or the ID list (read)
  --out-dir DIR              where raw files go; default
                             $SCHOLARCHAIN_EXPERIMENT_RUN_DIR (set by
                             scripts/run-experiment.sh). Inside the
                             repository it must be gitignored.
  --preflight                read-only check, nothing written outside a new
                             temporary folder outside the repository:
                             terraform output present, GET /healthz and
                             GET /readyz (retried), the list's format;
                             read mode also sends ONE request per listed ID
                             at level 1 and prints which answer 200. No
                             POST /v1/query in any preflight.

Secrets and identifiers: the API URL comes from
`terraform output -raw api_url` into a variable (trailing / removed) and is
never printed or written. The key comes only from
SCHOLARCHAIN_SERVICE_API_KEY and travels to curl as one config line on
standard input (curl --config -), never on a command line. curl runs with
--silent (no error text); response bodies stay in a private temporary folder
and only extracted fields are written: HTTP status, curl exit code,
time_total, the response class, the API's error code, the queryId.

Raw files written to the output folder (INTERFACE: experiments/derived/
load_levels.py reads them):
  load-requests.csv     one row per request of a level
  load-explain.csv      (query) one row per explain read
  id-check.csv          (read) one row per listed ID
  load-levels.csv       one summary row per level
  load-clock.csv        GET /healthz at start and end with the HTTP Date
  load-tool-record.json tool sha256, arguments, limits, list sha256, counts,
                        start and end, status, errors

Response classes: query: answered (200, not abstained), abstained (200,
abstained), throttled (429), overloaded (503), timeout (504 or curl timeout),
other-4xx, other-5xx, client-error (curl failed), unclassified (a 2xx without
a readable answer). read: ok (200), not-found (404), then the same classes
from throttled on.

Exit codes: 0 ran (whatever the system answered; 4xx and 5xx are results);
2 usage error or malformed list; 3 prerequisite not met (tool, terraform
output, the key variable, the API not ready after its bounded settle);
5 tool failure (a file could not be written, a client crashed); 7 the run's
own deadline or TERM.
EOF
}

# ---- messages and traps ------------------------------------------------------------

step() {
  CURRENT_STEP="$1"
  printf 'STEP: %s %s\n' "${TOOL_NAME}" "$1"
}
info() { printf 'INFO: %s %s\n' "${TOOL_NAME}" "$*"; }
warn() { printf 'WARN: %s %s\n' "${TOOL_NAME}" "$*" >&2; }
die() {
  local code="$1"
  shift
  printf 'ERROR: %s step=%s exit=%s: %s\n' "${TOOL_NAME}" "${CURRENT_STEP}" "${code}" "$*" >&2
  exit "${code}"
}
on_err() {
  local rc="$?"
  printf 'ERROR: %s step=%s line=%s exit=5: command failed with status %s\n' \
    "${TOOL_NAME}" "${CURRENT_STEP}" "$1" "${rc}" >&2
  exit 5
}
stop_clients() {
  local p
  for p in ${CLIENT_PIDS[@]+"${CLIENT_PIDS[@]}"}; do
    kill "${p}" 2>/dev/null || true
  done
}
on_signal() {
  stop_clients
  add_error "signal-$1"
  STATUS="interrupted"
  if [ "$1" = "TERM" ]; then
    exit 7
  fi
  exit 5
}
on_exit() {
  local rc="$?"
  trap - ERR
  set +e
  stop_clients
  if [ "${PREFLIGHT}" = "false" ] && [ -n "${OUT_DIR}" ] && [ "${RECORD_WRITTEN}" = "false" ] &&
    [ -n "${START_EPOCH}" ]; then
    if [ "${STATUS}" = "started" ]; then
      STATUS="failed"
    fi
    write_tool_record "${rc}"
  fi
  if [ -n "${TMP_DIR}" ] && [ -d "${TMP_DIR}" ]; then
    rm -rf -- "${TMP_DIR}"
  fi
  if [ "${rc}" -eq 0 ]; then
    printf 'RESULT: %s ok\n' "${TOOL_NAME}"
  else
    printf 'RESULT: %s failed exit=%s step=%s\n' "${TOOL_NAME}" "${rc}" "${CURRENT_STEP}" >&2
  fi
  exit "${rc}"
}
trap 'on_err "${LINENO}"' ERR
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap on_exit EXIT

now_s() { date -u +%s; }

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "$1" | cut -d' ' -f1
  else
    sha256sum -- "$1" | cut -d' ' -f1
  fi
}

add_error() {
  ERRORS_JSON="$(jq -c --arg e "$1" '. + [$e]' <<<"${ERRORS_JSON}" 2>/dev/null || printf '%s' "${ERRORS_JSON}")"
}

# ---- arguments -----------------------------------------------------------------------

parse_args() {
  local a
  for a in "$@"; do
    ARGS_JSON="$(jq -c --arg a "${a}" '. + [$a]' <<<"${ARGS_JSON}")"
  done
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --mode)
        [ "$#" -ge 2 ] || die 2 "--mode needs query or read"
        MODE="$2"
        shift 2
        ;;
      --levels)
        [ "$#" -ge 2 ] || die 2 "--levels needs a list"
        LEVELS_ARG="$2"
        shift 2
        ;;
      --level-seconds)
        [ "$#" -ge 2 ] || die 2 "--level-seconds needs a number"
        LEVEL_SECONDS="$2"
        shift 2
        ;;
      --max-requests-per-level)
        [ "$#" -ge 2 ] || die 2 "--max-requests-per-level needs a number"
        CAP_ARG="$2"
        shift 2
        ;;
      --pause-seconds)
        [ "$#" -ge 2 ] || die 2 "--pause-seconds needs a number"
        PAUSE_SECONDS="$2"
        shift 2
        ;;
      --list)
        [ "$#" -ge 2 ] || die 2 "--list needs a file"
        LIST_FILE="$2"
        shift 2
        ;;
      --out-dir)
        [ "$#" -ge 2 ] || die 2 "--out-dir needs a folder"
        OUT_DIR="$2"
        shift 2
        ;;
      --preflight)
        PREFLIGHT="true"
        shift
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
  case "${MODE}" in
    query)
      CAP="${QUERY_HARD_CAP}"
      MAX_CLIENTS="${QUERY_MAX_CLIENTS}"
      MAX_TIME="${QUERY_MAX_TIME}"
      SUCCESS_CLASS="answered"
      METHOD="POST"
      [ -n "${LEVELS_ARG}" ] || LEVELS_ARG="1,2,4,8"
      [ -n "${LIST_FILE}" ] || LIST_FILE="${QUESTIONS_DEFAULT}"
      ;;
    read)
      CAP="${READ_HARD_CAP}"
      MAX_CLIENTS="${READ_MAX_CLIENTS}"
      MAX_TIME="${READ_MAX_TIME}"
      SUCCESS_CLASS="ok"
      METHOD="GET"
      [ -n "${LEVELS_ARG}" ] || LEVELS_ARG="1,5,10,20"
      [ -n "${LIST_FILE}" ] || LIST_FILE="${IDS_DEFAULT}"
      ;;
    *)
      usage >&2
      die 2 "--mode must be query or read"
      ;;
  esac
  local re_list='^[0-9]+(,[0-9]+)*$' re_num='^[0-9]+$'
  [[ "${LEVELS_ARG}" =~ ${re_list} ]] || die 2 "--levels must be a comma-separated list of client counts"
  [[ "${LEVEL_SECONDS}" =~ ${re_num} ]] && [ "${LEVEL_SECONDS}" -ge "${LEVEL_SECONDS_MIN}" ] &&
    [ "${LEVEL_SECONDS}" -le "${LEVEL_SECONDS_MAX}" ] ||
    die 2 "--level-seconds must be ${LEVEL_SECONDS_MIN}..${LEVEL_SECONDS_MAX}"
  [[ "${PAUSE_SECONDS}" =~ ${re_num} ]] && [ "${PAUSE_SECONDS}" -le "${PAUSE_SECONDS_MAX}" ] ||
    die 2 "--pause-seconds must be 0..${PAUSE_SECONDS_MAX}"
  if [ -n "${CAP_ARG}" ]; then
    [[ "${CAP_ARG}" =~ ${re_num} ]] && [ "${CAP_ARG}" -ge 1 ] && [ "${CAP_ARG}" -le "${CAP}" ] ||
      die 2 "--max-requests-per-level must be 1..${CAP} for mode ${MODE}"
    CAP="${CAP_ARG}"
  fi
  local levels=() n=0 c
  IFS=, read -r -a levels <<<"${LEVELS_ARG}"
  for c in "${levels[@]}"; do
    n=$((n + 1))
    [ "${c}" -ge 1 ] && [ "${c}" -le "${MAX_CLIENTS}" ] ||
      die 2 "each level must be 1..${MAX_CLIENTS} clients for mode ${MODE} (got ${c})"
  done
  [ "${n}" -le "${MAX_LEVELS}" ] || die 2 "at most ${MAX_LEVELS} levels"
  case "${LIST_FILE}" in
    /* | *..*) die 2 "--list must be a repository-relative path without '..'" ;;
  esac
}

enter_repo_root() {
  local top here
  top="$(git rev-parse --show-toplevel 2>/dev/null)" ||
    die 2 "not inside a git checkout; run from the repository root"
  here="$(pwd -P)"
  [ "${top}" = "${here}" ] || die 2 "run from the repository root (./load-tests/${TOOL_NAME})"
}

check_prerequisites() {
  step "prerequisites"
  local tool
  for tool in git curl jq awk sort date cut grep tr; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 ||
    die 3 "neither shasum nor sha256sum found"
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  else
    die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  command -v terraform >/dev/null 2>&1 || die 3 "required tool not found: terraform (output only)"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-load.XXXXXX")"
  TOOL_SHA="$(sha256_of "load-tests/${TOOL_NAME}")"
  RUN_TAG="${SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID:-local-$(now_s)-$$}"
  local re_tag='^[A-Za-z0-9._-]{1,80}$'
  [[ "${RUN_TAG}" =~ ${re_tag} ]] || RUN_TAG="local-$(now_s)-$$"
}

# The key's presence and shape; the value is never printed.
check_key() {
  step "key"
  if [ -z "${SCHOLARCHAIN_SERVICE_API_KEY:-}" ]; then
    die 3 "${KEY_ENV_NAME} is not set in this shell (export it before the run)"
  fi
  case "${SCHOLARCHAIN_SERVICE_API_KEY}" in
    *[[:cntrl:]]* | *'"'* | *\\*)
      die 3 "the value of ${KEY_ENV_NAME} holds a control character, a double quote or a backslash; it cannot be passed safely"
      ;;
  esac
  info "${KEY_ENV_NAME} is set (value not shown)"
}

prepare_out_dir() {
  step "out-dir"
  if [ "${PREFLIGHT}" = "true" ]; then
    OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-load-preflight.XXXXXX")"
    info "preflight output folder (temporary, outside the repository): ${OUT_DIR}"
    return 0
  fi
  [ -n "${OUT_DIR}" ] || OUT_DIR="${SCHOLARCHAIN_EXPERIMENT_RUN_DIR:-}"
  [ -n "${OUT_DIR}" ] || die 2 "no output folder: pass --out-dir or run through scripts/run-experiment.sh"
  [ -d "${OUT_DIR}" ] && [ -w "${OUT_DIR}" ] || die 5 "the output folder does not exist or is not writable"
  local top abs
  top="$(pwd -P)"
  abs="$(cd "${OUT_DIR}" && pwd -P)"
  case "${abs}/" in
    "${top}/"*)
      git check-ignore -q -- "${abs}/x" ||
        die 2 "the output folder is inside the repository but not gitignored (use evidence/experiment-runs/)"
      ;;
  esac
  local f
  for f in load-requests.csv load-explain.csv id-check.csv load-levels.csv load-clock.csv load-tool-record.json; do
    [ ! -e "${OUT_DIR}/${f}" ] || die 5 "${f} exists in the output folder; raw files are never overwritten"
  done
}

# ---- the list ------------------------------------------------------------------------

# Query list lines: ID|PLAN|QUESTION (PLAN graph-only or graph-and-vector).
# Read list lines: ROUTE|ID|WINDOW|SOURCE (ROUTE neighbors or evidence;
# WINDOW YYYY-YYYY or -). Lines starting with # and blank lines are skipped.
load_list() {
  step "list"
  [ -f "${LIST_FILE}" ] || die 2 "list file not found: ${LIST_FILE}"
  LIST_SHA="$(sha256_of "${LIST_FILE}")"
  local line lineno=0 id kind rest path window body
  local re_q_id='^q[0-9]{2}$' re_plan='^(graph-only|graph-and-vector)$'
  local re_node='^(researcher|work):v1:[0-9a-f]{32}$' re_ev='^evidence:v1:[0-9a-f]{32}$'
  local re_win='^[0-9]{4}-[0-9]{4}$'
  while IFS= read -r line || [ -n "${line}" ]; do
    lineno=$((lineno + 1))
    case "${line}" in
      '' | '#'*) continue ;;
    esac
    [ "${ITEM_COUNT}" -lt "${LIST_MAX_ENTRIES}" ] || die 2 "more than ${LIST_MAX_ENTRIES} entries in ${LIST_FILE}"
    case "${line}" in
      *"'"*) die 2 "${LIST_FILE}:${lineno}: a single quote is not allowed" ;;
    esac
    if [ "${MODE}" = "query" ]; then
      id="${line%%|*}"
      rest="${line#*|}"
      kind="${rest%%|*}"
      body="${rest#*|}"
      [[ "${id}" =~ ${re_q_id} ]] || die 2 "${LIST_FILE}:${lineno}: question ID must be qNN"
      [[ "${kind}" =~ ${re_plan} ]] || die 2 "${LIST_FILE}:${lineno}: plan must be graph-only or graph-and-vector"
      [ -n "${body}" ] && [ "${body}" != "${rest}" ] || die 2 "${LIST_FILE}:${lineno}: expected ID|PLAN|QUESTION"
      case "${body}" in
        *'|'*) die 2 "${LIST_FILE}:${lineno}: the question must not contain |" ;;
      esac
      [ "${#body}" -le 1000 ] || die 2 "${LIST_FILE}:${lineno}: the question is too long"
      ITEM_BODIES[ITEM_COUNT]="${TMP_DIR}/q-${ITEM_COUNT}.json"
      jq -n --arg q "${body}" '{question: $q, mode: "sync"}' >"${ITEM_BODIES[ITEM_COUNT]}"
      ITEM_IDS[ITEM_COUNT]="${id}"
      ITEM_KINDS[ITEM_COUNT]="${kind}"
      ITEM_PATHS[ITEM_COUNT]="/v1/query"
    else
      kind="${line%%|*}"
      rest="${line#*|}"
      id="${rest%%|*}"
      rest="${rest#*|}"
      window="${rest%%|*}"
      [ "${rest}" != "${line}" ] || die 2 "${LIST_FILE}:${lineno}: expected ROUTE|ID|WINDOW|SOURCE"
      case "${kind}" in
        neighbors)
          [[ "${id}" =~ ${re_node} ]] || die 2 "${LIST_FILE}:${lineno}: a neighbors ID must be researcher:v1:HEX32 or work:v1:HEX32"
          [ "${window}" = "-" ] || die 2 "${LIST_FILE}:${lineno}: neighbors rows take window -"
          path="/v1/graph/nodes/${id}/neighbors?depth=1&limit=50"
          ;;
        evidence)
          [[ "${id}" =~ ${re_ev} ]] || die 2 "${LIST_FILE}:${lineno}: an evidence ID must be evidence:v1:HEX32"
          path="/v1/evidence/${id}"
          if [ "${window}" != "-" ]; then
            [[ "${window}" =~ ${re_win} ]] || die 2 "${LIST_FILE}:${lineno}: window must be YYYY-YYYY or -"
            path="${path}?window=${window}"
          fi
          ;;
        *) die 2 "${LIST_FILE}:${lineno}: route must be neighbors or evidence" ;;
      esac
      ITEM_IDS[ITEM_COUNT]="${id}"
      ITEM_KINDS[ITEM_COUNT]="${kind}"
      ITEM_PATHS[ITEM_COUNT]="${path}"
      ITEM_BODIES[ITEM_COUNT]=""
    fi
    ITEM_COUNT=$((ITEM_COUNT + 1))
  done <"${LIST_FILE}"
  [ "${ITEM_COUNT}" -ge 1 ] || die 2 "${LIST_FILE} has no entries"
  local i n_a=0 n_b=0
  for ((i = 0; i < ITEM_COUNT; i++)); do
    case "${ITEM_KINDS[i]}" in
      graph-only | neighbors) n_a=$((n_a + 1)) ;;
      *) n_b=$((n_b + 1)) ;;
    esac
  done
  if [ "${MODE}" = "query" ]; then
    info "list ${LIST_FILE}: ${ITEM_COUNT} questions (graph-only ${n_a}, graph-and-vector ${n_b}); sha256 ${LIST_SHA}"
  else
    info "list ${LIST_FILE}: ${ITEM_COUNT} IDs (neighbors ${n_a}, evidence ${n_b}); sha256 ${LIST_SHA}"
  fi
}

# ---- API address (never printed) -----------------------------------------------------

read_api_url() {
  step "api-url"
  local rc=0
  API_URL="$("${TIMEOUT_BIN}" --kill-after=30 "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" \
    output -raw api_url 2>/dev/null)" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || die 3 "terraform output api_url is not available (status ${rc})"
  API_URL="${API_URL%/}"
  local re_url='^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$'
  [[ "${API_URL}" =~ ${re_url} ]] || die 3 "api_url is not an https:// address"
  info "API address read from terraform output (not shown)"
}

# ---- one request ---------------------------------------------------------------------

# send METHOD PATH MAX_TIME AUTH(yes|no) BODY_FILE IDEMPOTENCY_KEY OUT_BODY OUT_HEADERS
# prints "CURL_EXIT HTTP_CODE TIME_TOTAL". The key travels only as a curl config
# line on standard input; printf is a builtin, so it never appears in any
# process's arguments. curl --silent prints no error text.
send() {
  local method="$1" path="$2" max_time="$3" auth="$4" body_file="$5" idem="$6"
  local out_body="$7" out_hdr="$8"
  local args=(--silent --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${max_time}"
    --request "${method}" --output "${out_body}" --write-out '%{http_code} %{time_total}'
    --header "Accept: application/json")
  if [ -n "${out_hdr}" ]; then
    args+=(--dump-header "${out_hdr}")
  fi
  if [ -n "${body_file}" ]; then
    args+=(--header "Content-Type: application/json" --data-binary "@${body_file}")
  fi
  if [ -n "${idem}" ]; then
    args+=(--header "Idempotency-Key: ${idem}")
  fi
  local rc=0 w=""
  rm -f -- "${out_body}"
  if [ "${auth}" = "yes" ]; then
    w="$(printf 'header = "X-Api-Key: %s"\n' "${SCHOLARCHAIN_SERVICE_API_KEY}" |
      "${TIMEOUT_BIN}" --kill-after=5 "$((max_time + 10))" curl --config - "${args[@]}" \
        "${API_URL}${path}" 2>/dev/null)" || rc=$?
  else
    w="$("${TIMEOUT_BIN}" --kill-after=5 "$((max_time + 10))" curl "${args[@]}" \
      "${API_URL}${path}" </dev/null 2>/dev/null)" || rc=$?
  fi
  local code="${w%% *}" ttot="${w#* }"
  local re_code='^[0-9]{3}$' re_t='^[0-9]+(\.[0-9]+)?$'
  [[ "${code}" =~ ${re_code} ]] || code="000"
  [[ "${ttot}" =~ ${re_t} ]] || ttot=""
  printf '%s %s %s\n' "${rc}" "${code}" "${ttot}"
}

# error_code BODY_FILE: the API's error field when it is a code, else a fixed word.
error_code() {
  local e=""
  e="$(jq -r '.error // empty' "$1" 2>/dev/null || true)"
  local re='^[A-Z][A-Z0-9_]{0,63}$'
  if [[ "${e}" =~ ${re} ]]; then
    printf '%s' "${e}"
  elif [ -s "$1" ]; then
    printf 'no-error-field'
  else
    printf 'no-body'
  fi
}

# classify MODE CURL_EXIT HTTP_CODE BODY_FILE: prints "CLASS ERROR_CODE QUERY_ID".
classify() {
  local mode="$1" rc="$2" code="$3" body="$4" cls err="-" qid="-" ab
  local re_qid='^[A-Za-z0-9:._-]{1,100}$'
  if [ "${rc}" = "28" ]; then
    printf 'timeout curl-28 -\n'
    return 0
  fi
  if [ "${rc}" = "124" ] || [ "${rc}" = "137" ]; then
    printf 'timeout tool-timeout-%s -\n' "${rc}"
    return 0
  fi
  if [ "${rc}" != "0" ] || [ "${code}" = "000" ]; then
    printf 'client-error curl-%s -\n' "${rc}"
    return 0
  fi
  case "${code}" in
    200)
      if [ "${mode}" = "query" ]; then
        ab="$(jq -r 'if (.answer.abstained | type) == "boolean" then (.answer.abstained | tostring) else "x" end' \
          "${body}" 2>/dev/null || printf 'x')"
        qid="$(jq -r '.queryId // empty' "${body}" 2>/dev/null || true)"
        [[ "${qid}" =~ ${re_qid} ]] || qid="-"
        case "${ab}" in
          false) cls="answered" ;;
          true) cls="abstained" ;;
          *)
            cls="unclassified"
            err="unreadable-answer"
            ;;
        esac
      else
        cls="ok"
      fi
      ;;
    2??)
      cls="unclassified"
      err="http-${code}"
      ;;
    404)
      if [ "${mode}" = "read" ]; then cls="not-found"; else cls="other-4xx"; fi
      err="$(error_code "${body}")"
      ;;
    429)
      cls="throttled"
      err="$(error_code "${body}")"
      ;;
    503)
      cls="overloaded"
      err="$(error_code "${body}")"
      ;;
    504)
      cls="timeout"
      err="$(error_code "${body}")"
      ;;
    4??)
      cls="other-4xx"
      err="$(error_code "${body}")"
      ;;
    5??)
      cls="other-5xx"
      err="$(error_code "${body}")"
      ;;
    *)
      cls="unclassified"
      err="http-${code}"
      ;;
  esac
  printf '%s %s %s\n' "${cls}" "${err}" "${qid}"
}

route_label() {
  case "$1" in
    graph-only | graph-and-vector) printf 'POST /v1/query' ;;
    neighbors) printf 'GET /v1/graph/nodes/{nodeId}/neighbors?depth=1&limit=50' ;;
    evidence) printf 'GET /v1/evidence/{evidenceId}' ;;
    explain) printf 'GET /v1/explain/{queryId}' ;;
    *) printf 'GET %s' "$1" ;;
  esac
}

# ---- readiness and clock -------------------------------------------------------------

# clock_sample PHASE: GET /healthz with the response headers, recording the local
# send and receive seconds and the HTTP Date header (1 s resolution).
clock_sample() {
  local phase="$1" send_s recv_s line rc code ttot hdr="${TMP_DIR}/clock-hdr.txt" d="" de=""
  send_s="$(now_s)"
  line="$(send GET /healthz "${SHORT_MAX_TIME}" no "" "" "${TMP_DIR}/clock-body.json" "${hdr}")"
  recv_s="$(now_s)"
  read -r rc code ttot <<<"${line}"
  if [ -f "${hdr}" ]; then
    d="$(grep -i -E '^date:' "${hdr}" | tail -n 1 | cut -d' ' -f2- | tr -d '\r' || true)"
  fi
  local re_d='^[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT$'
  if [[ "${d}" =~ ${re_d} ]]; then
    de="$(date -j -u -f '%a, %d %b %Y %H:%M:%S GMT' "${d}" +%s 2>/dev/null ||
      date -u -d "${d}" +%s 2>/dev/null || true)"
    # stored without the weekday, so the CSV cell holds no comma
    d="${d#*, }"
  else
    d=""
  fi
  printf '%s,%s,%s,%s,%s,%s,%s,%s\n' "${phase}" "${send_s}" "${recv_s}" "${code}" "${rc}" \
    "${ttot}" "${d}" "${de}" >>"${OUT_DIR}/load-clock.csv"
}

# Healthz and readyz, retried: the graph check fails once after idle (G-23).
check_ready() {
  step "readiness"
  local try=1 line rc code ttot h_ok r_ok
  while :; do
    h_ok="no"
    r_ok="no"
    line="$(send GET /healthz "${SHORT_MAX_TIME}" no "" "" "${TMP_DIR}/ready-body.json" "")"
    read -r rc code ttot <<<"${line}"
    [ "${code}" = "200" ] && [ "$(jq -r '.status // empty' "${TMP_DIR}/ready-body.json" 2>/dev/null)" = "ok" ] &&
      h_ok="yes"
    line="$(send GET /readyz "${SHORT_MAX_TIME}" no "" "" "${TMP_DIR}/ready-body.json" "")"
    read -r rc code ttot <<<"${line}"
    [ "${code}" = "200" ] && [ "$(jq -r '.status // empty' "${TMP_DIR}/ready-body.json" 2>/dev/null)" = "ready" ] &&
      r_ok="yes"
    printf 'READY: try=%s healthz=%s readyz=%s readyz_status=%s\n' "${try}" "${h_ok}" "${r_ok}" "${code}"
    if [ "${h_ok}" = "yes" ] && [ "${r_ok}" = "yes" ]; then
      return 0
    fi
    if [ "${try}" -ge "${READY_TRIES}" ]; then
      STATUS="prerequisite-not-met"
      add_error "not-ready-after-${READY_TRIES}-tries"
      die 3 "the API is not ready after ${READY_TRIES} tries (prerequisite not met; nothing was sent)"
    fi
    sleep $((try * READY_BACKOFF_SECONDS))
    try=$((try + 1))
  done
}

# ---- ID check (read mode) ------------------------------------------------------------

id_check() {
  step "id-check"
  local i line rc code ttot cls err qid ok=0 bad=0 body="${TMP_DIR}/idc-body.json"
  local file="${OUT_DIR}/id-check.csv"
  printf 'route,item,start_epoch_s,http_status,curl_exit,time_total_s,class,error_code\n' >"${file}"
  for ((i = 0; i < ITEM_COUNT; i++)); do
    local s
    s="$(now_s)"
    line="$(send GET "${ITEM_PATHS[i]}" "${READ_MAX_TIME}" yes "" "" "${body}" "")"
    read -r rc code ttot <<<"${line}"
    read -r cls err qid <<<"$(classify read "${rc}" "${code}" "${body}")"
    printf '%s,%s,%s,%s,%s,%s,%s,%s\n' "${ITEM_KINDS[i]}" "${ITEM_IDS[i]}" "${s}" "${code}" "${rc}" \
      "${ttot}" "${cls}" "${err}" >>"${file}"
    printf 'ID-CHECK: %s %s status=%s class=%s\n' "${ITEM_KINDS[i]}" "${ITEM_IDS[i]}" "${code}" "${cls}"
    if [ "${cls}" = "ok" ]; then ok=$((ok + 1)); else bad=$((bad + 1)); fi
    sleep "${SEQUENTIAL_GAP}"
  done
  printf 'ID-CHECK-SUMMARY: ids=%s answered_200=%s other=%s\n' "${ITEM_COUNT}" "${ok}" "${bad}"
}

# ---- one client ----------------------------------------------------------------------

# client_loop LEVEL CLIENTS CLIENT DEADLINE TICKET_DIR ROWS_FILE
# Closed loop: claim a ticket (mkdir is atomic), send, wait for the answer,
# record, repeat; stops at the deadline or when every ticket 1..CAP is taken.
client_loop() {
  local level="$1" clients="$2" client="$3" deadline="$4" tdir="$5" rows="$6"
  local k=1 seq=0 ticket idx s e line rc code ttot cls err qid idem
  local body="${TMP_DIR}/c${level}-${client}-body.json"
  while :; do
    [ "$(now_s)" -lt "${deadline}" ] || break
    while [ "${k}" -le "${CAP}" ] && ! mkdir "${tdir}/${k}" 2>/dev/null; do
      k=$((k + 1))
    done
    [ "${k}" -le "${CAP}" ] || break
    ticket="${k}"
    k=$((k + 1))
    seq=$((seq + 1))
    idx=$(((ticket - 1) % ITEM_COUNT))
    idem=""
    if [ "${MODE}" = "query" ]; then
      idem="p15d-${RUN_TAG}-L${level}-t${ticket}"
      idem="${idem:0:128}"
    fi
    s="$(now_s)"
    line="$(send "${METHOD}" "${ITEM_PATHS[idx]}" "${MAX_TIME}" yes "${ITEM_BODIES[idx]}" "${idem}" \
      "${body}" "")"
    e="$(now_s)"
    read -r rc code ttot <<<"${line}"
    read -r cls err qid <<<"$(classify "${MODE}" "${rc}" "${code}" "${body}")"
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "${MODE}" "${level}" "${clients}" \
      "${client}" "${seq}" "${ticket}" "${ITEM_IDS[idx]}" "${s}" "${e}" \
      "$(route_label "${ITEM_KINDS[idx]}")" "${code}" "${rc}" "${ttot}" "${cls}" "${err}" "${qid}" >>"${rows}"
  done
}

# ---- one level -----------------------------------------------------------------------

# pct SORTED_FILE P: nearest-rank percentile (rank = ceil(P/100 * n)); n/a when empty.
pct() {
  awk -v p="$2" '{ v[NR] = $1 } END {
    if (NR == 0) { print "n/a"; exit }
    r = int((p * NR + 99) / 100); if (r < 1) r = 1; if (r > NR) r = NR; print v[r] }' "$1"
}

run_level() {
  local level="$1" clients="$2"
  step "level-${level}"
  local tdir="${TMP_DIR}/tickets-${level}" start deadline end c p rc stop="duration"
  mkdir -p -- "${tdir}"
  start="$(now_s)"
  deadline=$((start + LEVEL_SECONDS))
  info "level ${level}: ${clients} client(s), ${LEVEL_SECONDS} s or ${CAP} requests, whichever comes first"
  CLIENT_PIDS=()
  for ((c = 1; c <= clients; c++)); do
    : >"${TMP_DIR}/rows-${level}-${c}.csv"
    client_loop "${level}" "${clients}" "${c}" "${deadline}" "${tdir}" "${TMP_DIR}/rows-${level}-${c}.csv" &
    CLIENT_PIDS+=("$!")
  done
  for p in "${CLIENT_PIDS[@]}"; do
    rc=0
    wait "${p}" || rc=$?
    if [ "${rc}" -ne 0 ]; then
      add_error "level-${level}-client-exit-${rc}"
      CLIENT_FAILED="true"
    fi
  done
  CLIENT_PIDS=()
  end="$(now_s)"
  [ -d "${tdir}/${CAP}" ] && stop="cap"
  local lvl_rows="${TMP_DIR}/rows-${level}.csv"
  : >"${lvl_rows}"
  for ((c = 1; c <= clients; c++)); do
    cat -- "${TMP_DIR}/rows-${level}-${c}.csv" >>"${lvl_rows}"
  done
  cat -- "${lvl_rows}" >>"${OUT_DIR}/load-requests.csv"
  summarize_level "${level}" "${clients}" "${start}" "${end}" "${deadline}" "${stop}" "${lvl_rows}"
  if [ "${MODE}" = "query" ]; then
    explain_phase "${level}" "${lvl_rows}"
  fi
}

CLIENT_FAILED="false"

summarize_level() {
  local level="$1" clients="$2" start="$3" end="$4" deadline="$5" stop="$6" rows="$7"
  local wall=$((end - start)) all="${TMP_DIR}/t-all-${level}" suc="${TMP_DIR}/t-suc-${level}"
  [ "${wall}" -ge 1 ] || wall=1
  awk -F, '$13 != "" { print $13 }' "${rows}" | sort -n >"${all}"
  awk -F, -v s="${SUCCESS_CLASS}" '$14 == s && $13 != "" { print $13 }' "${rows}" | sort -n >"${suc}"
  local counts attempted completed untimed n_all n_suc note_all note_suc
  counts="$(awk -F, '{ c[$14]++ } END { for (k in c) printf "%s=%d ", k, c[k] }' "${rows}")"
  attempted="$(awk 'END { print NR }' "${rows}")"
  completed="$(awk -F, '$12 == "0" && $11 != "000" { n++ } END { print n + 0 }' "${rows}")"
  untimed="$(awk -F, '$13 == "" { n++ } END { print n + 0 }' "${rows}")"
  n_all="$(awk 'END { print NR }' "${all}")"
  n_suc="$(awk 'END { print NR }' "${suc}")"
  note_all="ok"
  [ "${n_all}" -ge 20 ] || note_all="few-samples"
  [ "${n_all}" -ge 1 ] || note_all="n/a"
  note_suc="ok"
  [ "${n_suc}" -ge 20 ] || note_suc="few-samples"
  [ "${n_suc}" -ge 1 ] || note_suc="n/a"
  local rate cls_cols c v
  rate="$(awk -v n="${completed}" -v w="${wall}" 'BEGIN { printf "%.3f", n / w }')"
  cls_cols=""
  for c in ${CLASS_LIST}; do
    v="$(awk -F, -v k="${c}" '$14 == k { n++ } END { print n + 0 }' "${rows}")"
    cls_cols="${cls_cols},${v}"
  done
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "${MODE}" "${level}" "${clients}" \
    "${start}" "${end}" "${deadline}" "${stop}" "${attempted}" "${completed}" "${rate}" "${untimed}" \
    "${cls_cols}" "${n_all}" "$(pct "${all}" 50)" "$(pct "${all}" 95)" "$(pct "${all}" 99)" "${note_all}" \
    "${n_suc}" "$(pct "${suc}" 50)" "$(pct "${suc}" 95)" "$(pct "${suc}" 99)" "${note_suc}" \
    >>"${OUT_DIR}/load-levels.csv"
  printf 'LEVEL-SUMMARY: mode=%s level=%s clients=%s stop=%s wall_s=%s attempted=%s completed=%s completed_per_s=%s untimed=%s classes: %s\n' \
    "${MODE}" "${level}" "${clients}" "${stop}" "${wall}" "${attempted}" "${completed}" "${rate}" "${untimed}" "${counts}"
  printf 'LEVEL-SUMMARY: level=%s all(n=%s,%s) p50=%s p95=%s p99=%s s; %s(n=%s,%s) p50=%s p95=%s p99=%s s\n' \
    "${level}" "${n_all}" "${note_all}" "$(pct "${all}" 50)" "$(pct "${all}" 95)" "$(pct "${all}" 99)" \
    "${SUCCESS_CLASS}" "${n_suc}" "${note_suc}" "$(pct "${suc}" 50)" "$(pct "${suc}" 95)" "$(pct "${suc}" 99)"
  LEVELS_JSON="$(jq -c --argjson l "${level}" --argjson c "${clients}" --argjson a "${attempted}" \
    --argjson d "${completed}" --arg s "${stop}" --argjson st "${start}" --argjson en "${end}" \
    '. + [{level: $l, clients: $c, attempted: $a, completed: $d, stop: $s, start_epoch_s: $st, end_epoch_s: $en}]' \
    <<<"${LEVELS_JSON}")"
}

# ---- explain reads (query mode) ------------------------------------------------------

explain_phase() {
  local level="$1" rows="$2"
  step "explain-${level}"
  local deadline n=0 qid qn cls line rc code ttot err s fields body="${TMP_DIR}/explain-body.json"
  local keys_json
  keys_json="$(jq -n -c --arg k "${STAGE_KEYS}" '$k | split(" ")')"
  deadline=$(($(now_s) + EXPLAIN_PHASE_SECONDS))
  while IFS=, read -r _m _l _c _cl _sq _t qn _s _e _r _h _x _tt cls _er qid; do
    case "${cls}" in
      answered | abstained) ;;
      *) continue ;;
    esac
    [ "${qid}" != "-" ] || continue
    if [ "$(now_s)" -ge "${deadline}" ] || [ "${n}" -ge "${CAP}" ]; then
      add_error "level-${level}-explain-phase-limit"
      break
    fi
    n=$((n + 1))
    s="$(now_s)"
    line="$(send GET "/v1/explain/${qid}" "${SHORT_MAX_TIME}" yes "" "" "${body}" "")"
    read -r rc code ttot <<<"${line}"
    err="-"
    fields=""
    if [ "${rc}" = "0" ] && [ "${code}" = "200" ]; then
      fields="$(jq -r --argjson keys "${keys_json}" '
        def cell: if . == null then "" else tostring end;
        (.stageMillis // {}) as $s
        | [ (.latencyMillis | cell) ]
          + [ $keys[] as $k | ($s[$k] | cell) ]
          + [ (.inputTokens | cell), (.outputTokens | cell),
              (.abstained | cell),
              ([($s | keys[]) | select(. as $x | $keys | index($x) | not)
                | gsub("[^A-Za-z]"; "")] | join(";")) ]
        | join(",")' "${body}" 2>/dev/null || true)"
      [ -n "${fields}" ] || err="unreadable-explain"
    else
      read -r _c err _q <<<"$(classify read "${rc}" "${code}" "${body}")"
    fi
    if [ -z "${fields}" ]; then
      # 17 empty cells (latency, 12 stages, 2 token counts, abstained, other keys): missing, never 0
      fields=",,,,,,,,,,,,,,,,"
    fi
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "${level}" "${qn}" "${qid}" "${s}" "${code}" "${rc}" "${ttot}" \
      "${err}" "${fields}" >>"${OUT_DIR}/load-explain.csv"
    sleep "${SEQUENTIAL_GAP}"
  done <"${rows}"
  info "level ${level}: ${n} explain read(s)"
}

# ---- record --------------------------------------------------------------------------

write_tool_record() {
  local rc="$1" files="[]" f
  for f in load-requests.csv load-explain.csv id-check.csv load-levels.csv load-clock.csv; do
    [ -f "${OUT_DIR}/${f}" ] && files="$(jq -c --arg f "${f}" '. + [$f]' <<<"${files}")"
  done
  RECORD_WRITTEN="true"
  jq -n --arg kind "scholarchain-load-tool-record" --argjson version "${TOOL_VERSION}" \
    --arg tool "load-tests/${TOOL_NAME}" --arg tool_sha "${TOOL_SHA}" --arg mode "${MODE}" \
    --argjson args "${ARGS_JSON}" --arg levels "${LEVELS_ARG}" --argjson level_s "${LEVEL_SECONDS}" \
    --argjson cap "${CAP}" --argjson pause "${PAUSE_SECONDS}" --argjson max_time "${MAX_TIME}" \
    --arg list "${LIST_FILE}" --arg list_sha "${LIST_SHA}" --argjson entries "${ITEM_COUNT}" \
    --arg attempt "${SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID:-}" --arg manifest "${SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID:-}" \
    --arg start "${START_EPOCH}" --arg end "$(now_s)" --arg status "${STATUS}" --argjson exit_code "${rc}" \
    --argjson per_level "${LEVELS_JSON}" --argjson errors "${ERRORS_JSON}" --argjson files "${files}" \
    --arg success "${SUCCESS_CLASS}" --arg stages "${STAGE_KEYS}" \
    '{kind: $kind, version: $version, tool: $tool, tool_sha256: $tool_sha, mode: $mode,
      arguments: $args,
      limits: {levels: $levels, level_seconds: $level_s, max_requests_per_level: $cap,
               pause_seconds: $pause, curl_max_time_s: $max_time,
               hard_caps: {query: 60, read: 600, query_clients: 8, read_clients: 20},
               explain_phase_seconds: 150},
      list: {path: $list, sha256: $list_sha, entries: $entries},
      attempt_id: (if $attempt == "" then null else $attempt end),
      manifest_id: (if $manifest == "" then null else $manifest end),
      start_epoch_s: ($start | tonumber), end_epoch_s: ($end | tonumber),
      status: $status, exit_code: $exit_code,
      success_class: $success,
      percentile_method: "nearest rank: value at rank ceil(p/100*n) of the sorted curl time_total values",
      explain_stage_keys: ($stages | split(" ")),
      per_level: $per_level, errors: $errors, files: $files,
      evidence: {requests: (if ($files | index("load-requests.csv")) then "recorded" else "missing" end),
                 clock: (if ($files | index("load-clock.csv")) then "recorded" else "missing" end)}}' \
    >"${OUT_DIR}/load-tool-record.json" || warn "could not write load-tool-record.json"
}

# ---- main ----------------------------------------------------------------------------

CLASS_LIST=""

main() {
  parse_args "$@"
  enter_repo_root
  check_prerequisites
  check_key
  load_list
  prepare_out_dir
  START_EPOCH="$(now_s)"
  if [ "${MODE}" = "query" ]; then
    CLASS_LIST="answered abstained throttled overloaded timeout other-4xx other-5xx client-error unclassified"
  else
    CLASS_LIST="ok not-found throttled overloaded timeout other-4xx other-5xx client-error unclassified"
  fi
  if [ "${PREFLIGHT}" = "false" ]; then
    printf 'mode,level,clients,client,seq,ticket,item,start_epoch_s,end_epoch_s,route,http_status,curl_exit,time_total_s,class,error_code,query_id\n' \
      >"${OUT_DIR}/load-requests.csv"
    local hdr c
    hdr="mode,level,clients,window_start_epoch_s,window_end_epoch_s,deadline_epoch_s,stop,attempted,completed,completed_per_s,untimed"
    for c in ${CLASS_LIST}; do hdr="${hdr},n_${c}"; done
    hdr="${hdr},n_all,p50_all_s,p95_all_s,p99_all_s,note_all,n_success,p50_success_s,p95_success_s,p99_success_s,note_success"
    printf '%s\n' "${hdr}" >"${OUT_DIR}/load-levels.csv"
    if [ "${MODE}" = "query" ]; then
      local eh="level,question,query_id,start_epoch_s,http_status,curl_exit,time_total_s,error_code,latency_ms" k
      for k in ${STAGE_KEYS}; do eh="${eh},${k}_ms"; done
      eh="${eh},input_tokens,output_tokens,abstained,other_stage_keys"
      printf '%s\n' "${eh}" >"${OUT_DIR}/load-explain.csv"
    fi
  fi
  printf 'phase,local_send_epoch_s,local_recv_epoch_s,http_status,curl_exit,time_total_s,http_date,http_date_epoch_s\n' \
    >"${OUT_DIR}/load-clock.csv"
  read_api_url
  clock_sample "start"
  check_ready
  if [ "${PREFLIGHT}" = "true" ]; then
    step "preflight"
    if [ "${MODE}" = "read" ]; then
      id_check
    fi
    printf 'PREFLIGHT: mode=%s list_entries=%s levels=%s level_seconds=%s cap=%s pause=%s (nothing sent beyond healthz, readyz%s)\n' \
      "${MODE}" "${ITEM_COUNT}" "${LEVELS_ARG}" "${LEVEL_SECONDS}" "${CAP}" "${PAUSE_SECONDS}" \
      "$([ "${MODE}" = "read" ] && printf ' and one GET per listed ID' || true)"
    STATUS="preflight-ok"
    step "done"
    return 0
  fi
  if [ "${MODE}" = "read" ]; then
    id_check
  fi
  local levels=() n_levels i run_deadline
  IFS=, read -r -a levels <<<"${LEVELS_ARG}"
  n_levels="${#levels[@]}"
  run_deadline=$((START_EPOCH + n_levels * (LEVEL_SECONDS + MAX_TIME + 15 + EXPLAIN_PHASE_SECONDS + PAUSE_SECONDS) + 300))
  for ((i = 0; i < n_levels; i++)); do
    if [ "$(now_s)" -ge "${run_deadline}" ]; then
      STATUS="deadline"
      add_error "run-deadline-before-level-$((i + 1))"
      die 7 "the run's own deadline passed before level $((i + 1))"
    fi
    run_level "$((i + 1))" "${levels[i]}"
    if [ "$((i + 1))" -lt "${n_levels}" ] && [ "${PAUSE_SECONDS}" -gt 0 ]; then
      info "pause ${PAUSE_SECONDS} s"
      sleep "${PAUSE_SECONDS}"
    fi
  done
  clock_sample "end"
  if [ "${CLIENT_FAILED}" = "true" ]; then
    STATUS="client-failed"
    write_tool_record 5
    die 5 "a client process failed; the recorded requests are partial (see errors in load-tool-record.json)"
  fi
  STATUS="completed"
  write_tool_record 0
  step "done"
}

main "$@"
