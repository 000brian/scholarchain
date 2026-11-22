# shellcheck shell=bash
# The EMU_* result variables are read by the scripts that source this file.
# shellcheck disable=SC2034
# scripts/emulator/lib.sh - shared rules of scripts/emulator/setup.sh and
# scripts/emulator/probes.sh. SOURCED, never executed. Owner: infra-engineer
# (Phase 16, decision 12).
#
# Loading this file defines variables and functions only: it sets no shell
# option, installs no trap, runs no command and prints nothing. bash 3.2
# compatible (macOS /bin/bash): no associative arrays, no mapfile, no ${v,,}.
#
# 1. The emulator safety contract (Phase 16 decision 6.6), the same rules as
#    the Scala emulator suites (storage-engineer), checked before any call:
#      rule endpoint            SCHOLARCHAIN_EMULATOR_ENDPOINT is exactly
#                               https://127.0.0.1:9297 (no localhost, no
#                               other host, no other port)
#      rule aws-endpoint-url    AWS_ENDPOINT_URL equals it
#      rule credentials-file    AWS_SHARED_CREDENTIALS_FILE is exactly
#                               $HOME/.aws/emulator/9297/emulator_credentials
#      rule credentials-file-exists   and that file exists
#      rule config-file         AWS_CONFIG_FILE is exactly
#                               $HOME/.aws/emulator/9297/config
#      rule unset:NAME          each of AWS_PROFILE, AWS_ACCESS_KEY_ID,
#                               AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN,
#                               AWS_ROLE_ARN, AWS_WEB_IDENTITY_TOKEN_FILE,
#                               AWS_CONTAINER_CREDENTIALS_RELATIVE_URI,
#                               AWS_CONTAINER_CREDENTIALS_FULL_URI is unset
#                               (set to an empty value counts as set)
#      rule config-file-path    SCHOLARCHAIN_CONFIG_FILE is set and absolute
#      rule results-dir-path    SCHOLARCHAIN_EMULATOR_RESULTS_DIR is absolute
#                               when set (probes.sh: it must be set)
#    The first failing rule is reported by name only, never with a value.
#    Then, still before any AWS call: the health route answers "ok"
#    (https://docs.aws.amazon.com/emulator/latest/userguide/cli-reference.html,
#    "Control routes"), and STS GetCallerIdentity returns the emulator's
#    fixed account (point-sdk-to-emulator.html, "To check that you're using
#    the emulator"). The account is compared with a value built at run time
#    (twelve zero digits made by printf), so the literal never appears in this
#    file, and it is never printed or recorded: scripts print only
#    "emulator account confirmed: yes|no".
#
# 2. One wrapper, emu_aws, for every AWS CLI call: it always passes
#    --endpoint-url "$SCHOLARCHAIN_EMULATOR_ENDPOINT" (only after the contract
#    passed), --ca-bundle (the emulator's ca.pem), --region us-east-1,
#    --cli-connect-timeout, --cli-read-timeout, no pager, JSON output, one SDK
#    attempt (AWS_MAX_ATTEMPTS=1, so latency is one request), all under
#    timeout --kill-after.
#
# 3. Outcome classes and result lines (decision 6.6): implemented-passed,
#    implemented-failed, not-implemented (HTTP 501 NotImplemented,
#    https://docs.aws.amazon.com/emulator/latest/userguide/fidelity.html),
#    not-exercised, infrastructure-failure. One JSON line per operation:
#    {"service","operation","class","httpStatus","latencyMillis"} first, then
#    the additive fields "resource" (an emulator-only name), "outcome"
#    (setup creates), "probe", "observation" (fixed vocabulary), "errorCode"
#    and "rule" (preflight). No host, account, ARN, queue URL or key ever.
#    httpStatus: 501 for NotImplemented, otherwise null (the CLI does not
#    print the status of an answered call). latencyMillis: wall time of the
#    one CLI process (milliseconds through perl Time::HiRes when present,
#    else whole seconds times 1000), so it includes CLI start-up.
#
# Status: static: shellcheck clean; runtime: untested; human execution required.

if [ -n "${SCHOLARCHAIN_EMULATOR_LIB_LOADED:-}" ]; then
  return 0
fi
SCHOLARCHAIN_EMULATOR_LIB_LOADED=1

EMU_ENDPOINT_EXPECTED="https://127.0.0.1:9297"
EMU_PORT="9297"
EMU_REGION="us-east-1"
EMU_UNSET_VARS="AWS_PROFILE AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE AWS_CONTAINER_CREDENTIALS_RELATIVE_URI AWS_CONTAINER_CREDENTIALS_FULL_URI"
EMU_CONNECT_TIMEOUT=5
EMU_READ_TIMEOUT=30
EMU_CALL_TIMEOUT=60
EMU_HEALTH_TIMEOUT=10

# Set by the functions below.
EMU_FAILED_RULE=""
EMU_TIMEOUT_BIN=""
EMU_TMP=""
EMU_RESULTS_FILE=""
EMU_SCRIPT=""
EMU_RC=0
EMU_CLASS=""
EMU_CODE=""
EMU_HTTP="null"
EMU_LATENCY=0
EMU_MESSAGE_HINT=""

emu_dir() { printf '%s/.aws/emulator/%s' "${HOME:-}" "${EMU_PORT}"; }
emu_ca_file() { printf '%s/ca.pem' "$(emu_dir)"; }

# emu_safety_check MODE: MODE "required" (probes.sh) or "optional" (setup.sh)
# for SCHOLARCHAIN_EMULATOR_RESULTS_DIR. Status 0, or 4 with EMU_FAILED_RULE.
emu_safety_check() {
  local mode="$1" v dir
  EMU_FAILED_RULE=""
  if [ "${SCHOLARCHAIN_EMULATOR_ENDPOINT:-}" != "${EMU_ENDPOINT_EXPECTED}" ]; then
    EMU_FAILED_RULE="endpoint"
    return 4
  fi
  if [ "${AWS_ENDPOINT_URL:-}" != "${SCHOLARCHAIN_EMULATOR_ENDPOINT}" ]; then
    EMU_FAILED_RULE="aws-endpoint-url"
    return 4
  fi
  case "${HOME:-}" in
    /*) ;;
    *)
      EMU_FAILED_RULE="credentials-file"
      return 4
      ;;
  esac
  dir="$(emu_dir)"
  if [ "${AWS_SHARED_CREDENTIALS_FILE:-}" != "${dir}/emulator_credentials" ]; then
    EMU_FAILED_RULE="credentials-file"
    return 4
  fi
  if [ ! -f "${AWS_SHARED_CREDENTIALS_FILE}" ]; then
    EMU_FAILED_RULE="credentials-file-exists"
    return 4
  fi
  if [ "${AWS_CONFIG_FILE:-}" != "${dir}/config" ]; then
    EMU_FAILED_RULE="config-file"
    return 4
  fi
  for v in ${EMU_UNSET_VARS}; do
    if [ -n "${!v+x}" ]; then
      EMU_FAILED_RULE="unset:${v}"
      return 4
    fi
  done
  case "${SCHOLARCHAIN_CONFIG_FILE:-}" in
    /*) ;;
    *)
      EMU_FAILED_RULE="config-file-path"
      return 4
      ;;
  esac
  if [ "${mode}" = "required" ] || [ -n "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR+x}" ]; then
    case "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR:-}" in
      /*) ;;
      *)
        EMU_FAILED_RULE="results-dir-path"
        return 4
        ;;
    esac
  fi
  return 0
}

# emu_find_timeout: sets EMU_TIMEOUT_BIN (GNU timeout or gtimeout); status 3 if none.
emu_find_timeout() {
  if command -v timeout >/dev/null 2>&1 && timeout --version >/dev/null 2>&1; then
    EMU_TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    EMU_TIMEOUT_BIN="gtimeout"
  else
    return 3
  fi
  return 0
}

emu_now_ms() {
  if command -v perl >/dev/null 2>&1; then
    perl -MTime::HiRes=time -e 'printf("%d\n", time() * 1000)' 2>/dev/null && return 0
  fi
  printf '%s000\n' "$(date +%s)"
}

# emu_health: status 0 when GET /__admin/health answers exactly "ok".
emu_health() {
  local body
  # || true inside the substitution: a refused connection gives an empty body.
  body="$("${EMU_TIMEOUT_BIN}" --kill-after=5 "${EMU_HEALTH_TIMEOUT}" curl -sS -f \
    --max-time "${EMU_HEALTH_TIMEOUT}" --cacert "$(emu_ca_file)" \
    "${SCHOLARCHAIN_EMULATOR_ENDPOINT}/__admin/health" 2>/dev/null || true)"
  [ "${body}" = "ok" ]
}

# emu_aws ARGS...: the only way these scripts call the AWS CLI.
emu_aws() {
  AWS_MAX_ATTEMPTS=1 AWS_PAGER="" "${EMU_TIMEOUT_BIN}" --kill-after=5 "${EMU_CALL_TIMEOUT}" \
    aws --endpoint-url "${SCHOLARCHAIN_EMULATOR_ENDPOINT}" --ca-bundle "$(emu_ca_file)" \
    --region "${EMU_REGION}" --cli-connect-timeout "${EMU_CONNECT_TIMEOUT}" \
    --cli-read-timeout "${EMU_READ_TIMEOUT}" --no-cli-pager --output json "$@"
}

# emu_call ARGS...: runs emu_aws, keeps standard output in "$EMU_TMP/out",
# standard error in "$EMU_TMP/err" (never printed: it may name an ARN or a
# URL), and sets EMU_RC, EMU_CLASS (ok, error, not-implemented,
# infrastructure-failure), EMU_CODE (the AWS error code, or connection, tls,
# timeout, cli-exit-N), EMU_HTTP and EMU_LATENCY. EMU_MESSAGE_HINT is set to
# "already-enabled" when the error text says so (DynamoDB TTL); the message
# itself is never kept.
emu_call() {
  local t0 t1 err=""
  EMU_RC=0
  EMU_CODE=""
  EMU_HTTP="null"
  EMU_MESSAGE_HINT=""
  t0="$(emu_now_ms)"
  emu_aws "$@" >"${EMU_TMP}/out" 2>"${EMU_TMP}/err" || EMU_RC=$?
  t1="$(emu_now_ms)"
  EMU_LATENCY=$((t1 - t0))
  if [ "${EMU_RC}" -eq 0 ]; then
    EMU_CLASS="ok"
    return 0
  fi
  err="$(head -c 4000 "${EMU_TMP}/err" 2>/dev/null || true)"
  if [ "${EMU_RC}" -eq 124 ] || [ "${EMU_RC}" -eq 137 ]; then
    EMU_CLASS="infrastructure-failure"
    EMU_CODE="timeout"
  elif [[ "${err}" =~ An\ error\ occurred\ \(([^\)]+)\) ]]; then
    EMU_CODE="${BASH_REMATCH[1]}"
    if [ "${EMU_CODE}" = "NotImplemented" ]; then
      EMU_CLASS="not-implemented"
      EMU_HTTP="501"
    else
      EMU_CLASS="error"
    fi
    case "${err}" in
      *"already enabled"* | *"already exists"*) EMU_MESSAGE_HINT="already-enabled" ;;
    esac
  elif [[ "${err}" == *SSL* ]] || [[ "${err}" == *ertificate* ]]; then
    EMU_CLASS="infrastructure-failure"
    EMU_CODE="tls"
  elif [[ "${err}" == *"Could not connect"* ]] || [[ "${err}" == *"Connection was closed"* ]] ||
    [[ "${err}" == *"onnection refused"* ]]; then
    EMU_CLASS="infrastructure-failure"
    EMU_CODE="connection"
  elif [[ "${err}" == *"timeout"* ]] || [[ "${err}" == *"timed out"* ]]; then
    EMU_CLASS="infrastructure-failure"
    EMU_CODE="timeout"
  else
    EMU_CLASS="infrastructure-failure"
    EMU_CODE="cli-exit-${EMU_RC}"
  fi
  return 0
}

# emu_result SERVICE OPERATION CLASS [KEY VALUE]...: prints one RESULT line and
# appends the JSON line to EMU_RESULTS_FILE when it is set. Uses EMU_HTTP and
# EMU_LATENCY of the last call (callers reset them for calls not made).
emu_result() {
  local service="$1" operation="$2" class="$3" line args=() k v printed
  shift 3
  printed="service=${service} operation=${operation} class=${class}"
  while [ "$#" -ge 2 ]; do
    k="$1"
    v="$2"
    shift 2
    [ -n "${v}" ] || continue
    args+=(--arg "${k}" "${v}")
    printed="${printed} ${k}=${v}"
  done
  line="$(jq -cn --arg service "${service}" --arg operation "${operation}" --arg class "${class}" \
    --argjson httpStatus "${EMU_HTTP}" --argjson latencyMillis "${EMU_LATENCY}" \
    ${args[@]+"${args[@]}"} \
    '{service: $service, operation: $operation, class: $class, httpStatus: $httpStatus, latencyMillis: $latencyMillis} + ($ARGS.named | del(.service, .operation, .class, .httpStatus, .latencyMillis))')"
  printf 'RESULT: %s %s\n' "${EMU_SCRIPT}" "${printed}"
  if [ -n "${EMU_RESULTS_FILE}" ]; then
    printf '%s\n' "${line}" >>"${EMU_RESULTS_FILE}"
  fi
}

# emu_not_exercised SERVICE OPERATION REASON [KEY VALUE]...
emu_not_exercised() {
  local service="$1" operation="$2" reason="$3"
  shift 3
  EMU_HTTP="null"
  EMU_LATENCY=0
  emu_result "${service}" "${operation}" "not-exercised" observation "${reason}" "$@"
}

# emu_preflight_line RULE: the one preflight line of a stopped run, written
# only when the results folder itself passed its rule and exists.
emu_preflight_line() {
  EMU_HTTP="null"
  EMU_LATENCY=0
  emu_result "preflight" "preflight" "infrastructure-failure" rule "$1"
}

# emu_account_matches: status 0 when STS reports the emulator's account;
# the value is held in a local variable only.
emu_account_matches() {
  local account expected
  expected="$(printf '%012d' 0)"
  emu_call sts get-caller-identity --query Account --output text
  [ "${EMU_CLASS}" = "ok" ] || return 1
  account="$(head -c 64 "${EMU_TMP}/out" | tr -d '[:space:]')"
  : >"${EMU_TMP}/out"
  [ "${account}" = "${expected}" ]
}
