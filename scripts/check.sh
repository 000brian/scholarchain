#!/usr/bin/env bash
# scripts/check.sh - every non-cloud validation gate of ScholarChain in one
# command (Phase 13 work order, decision 9.1; spec work-order item 1). Owner:
# infra-engineer (author). Run by the human, normally in a fresh clone.
#
# Status (Phase 13): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="check.sh"
readonly TF_DIR="infra/terraform/environments/course"
readonly TERRAFORM_VERSION_REQUIRED="1.17.1"
readonly OUTPUTS_TF="infra/terraform/environments/course/outputs.tf"
readonly PACKAGE_CHECK_SCALA="project/PackageCheck.scala"
readonly SFN_TF="infra/terraform/modules/workflow/sfn.tf"
readonly EXAMPLE_MANIFEST="experiments/manifests/example-not-approved.json"
readonly STEP_TIMEOUT=600
readonly SELFTEST_TIMEOUT=900
readonly DATASETS_TIMEOUT=900
readonly TF_INIT_TIMEOUT=900
readonly TF_VALIDATE_TIMEOUT=300
readonly SBT_CHECK_TIMEOUT=10800
readonly SBT_PACKAGE_TIMEOUT=5400
readonly PROBE_TIMEOUT=120
readonly TEN_SCRIPTS="check package plan deploy smoke-test run-bounded-pipeline run-experiment archive-evidence cleanup rollback"

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
REPO_ROOT=""
FAIL_FAST="false"
RECORD_DIR_ARG="evidence/checks"
RECORD_DIR=""
ATTEMPT_ID=""
RECORD_FILE=""
STARTED_WRITTEN="false"
FINAL_WRITTEN="false"
SIGNAL_NAME=""
HEAD_SHA=""
BUILD_SHA=""
DIRTY_AT_START=""
STARTED_UTC=""
STOP_GATES="false"
MODULE_TESTS="null"
CONTRACT_TESTS="null"

usage() {
  cat <<'EOF'
Usage: scripts/check.sh [--fail-fast] [--record-dir PATH]
       scripts/check.sh --help

Runs every non-cloud validation gate, prints a pass or fail line per gate and
a summary table, writes one JSON record, and exits non-zero if any gate
failed. Run it from the repository root: bash scripts/check.sh. It needs NO
AWS credentials and calls no AWS service. It refuses nothing because of a
dirty tree; it records the dirty flag instead.

Gates, in order (each has its own "STEP: check.sh gate-NAME" marker and a
"GATE: NAME passed|failed|timed-out|skipped|not-run" line):
  1 constitution     bash .claude/hooks/validate-agent-files.sh
  2 json             jq empty on every tracked *.json outside test resource
                     folders (*/src/test/* and tests/**; their tests check
                     them); every file is listed
  3 hook-syntax      bash -n on .claude/hooks/*.sh and .claude/hook-tests/*.sh,
                     then bash .claude/hook-tests/guard-selftest.sh and
                     bash .claude/hook-tests/stop-hook-selftest.sh (they write
                     only under /tmp/claude-*)
  4 shell            shellcheck -x -P SCRIPTDIR on scripts/*.sh,
                     scripts/lib/*.sh, datasets/scripts/*.sh and
                     infra/terraform/modules/emr/files/bootstrap.sh only;
                     the hooks and hook self-tests (.claude/**) are covered
                     by gates 1 and 3, not by shellcheck
  5 datasets         bash datasets/scripts/validate.sh (offline)
  6 terraform        terraform fmt -recursive -check infra/terraform; then
                     terraform -chdir=infra/terraform/environments/course init
                       -backend=false -input=false -lockfile=readonly
                     (NEEDS NETWORK ACCESS to the Terraform registry; writes
                     only the gitignored .terraform/ folder); then validate
  7 sbt              sbt -batch check (format check, Test/compile, module
                     tests, contract tests without AWS incl. the contract and
                     schema suites; writes .claude/.last-test-run), then
                     sbt -batch packageCheck ("packageCheck: ok" required)
  8 script-contracts static, grep and jq only, nothing executed:
                     every terraform output a script reads ("$(out NAME)",
                     "$(out_json NAME)" and ".NAME.value" jq paths) is an
                     output block in infra/terraform/environments/course/
                     outputs.tf; every org.scholarchain main class a script
                     or the state machine names is in packageCheck's class
                     list (project/PackageCheck.scala); every option on a
                     script line marked "# contract: ENTRY [SUBCOMMAND]"
                     (ErMain, JsonlExport, RagIngestJob, StorageLoadMain SUB)
                     and every option the state machine passes to JsonlExport
                     is in that entry point's option constant
  9 probes           offline script probes, each with every AWS credential
                     source disabled (env -u AWS_PROFILE -u AWS_ACCESS_KEY_ID
                     ... AWS_CONFIG_FILE=/dev/null
                     AWS_SHARED_CREDENTIALS_FILE=/dev/null
                     AWS_EC2_METADATA_DISABLED=true), under timeout 120 and
                     with TMPDIR inside this run's private temporary folder;
                     only the exit code and one expected output line are
                     checked: the ten scripts' --help (exit 0);
                     deploy.sh --plan-sha256 <wrong 64 hex> (exit 4);
                     run-experiment.sh on the example manifest with its real
                     sha256 (exit 4, the "example" refusal; SKIPPED on a dirty
                     tree, where the dirty-tree refusal comes first);
                     smoke-test.sh --question probe --api-url
                     https://example.invalid without SCHOLARCHAIN_SERVICE_API_KEY
                     (exit 3); cleanup.sh --list without --evidence-archive
                     (exit 4); archive-evidence.sh --local-only --out-dir TMP
                     (exit 0; the tar has one root folder, MANIFEST.json,
                     repo/, no top-level reports/, and the archive_keys rule
                     that cleanup.sh uses gives keys under repo/, plans/ and
                     MANIFEST.json only). A probe that reached a cloud call
                     would fail for lack of credentials, so no probe can
                     change AWS. These probes are the first execution of the
                     scripts; they test refusal paths and the local archive.

Options:
  --fail-fast        stop at the first failing gate (the rest: not-run)
  --record-dir PATH  where the record goes (default evidence/checks; created
                     if missing). Inside this checkout it must be gitignored;
                     outside it any folder works, for example the main
                     checkout's evidence/checks when you run this in a fresh
                     clone.

Record: PATH/check-ATTEMPT_ID.started.json before the first gate, then
PATH/check-ATTEMPT_ID.json (never overwritten): HEAD, build commit, dirty
flag, tool versions, each gate (status, exit code, seconds), the
module_tests and contract_tests counts from sbt's "Updated
.claude/.last-test-run" line, per test suite the tests, failures, errors and
skipped counts read from the sbt test-report XML files written by this run,
and each probe. This record is the archived test evidence (package.sh runs
sbt clean, which removes the XML files). A started record without a final
one is an incomplete attempt.

Prerequisites (missing or wrong: exit 3 with the name): java 17, sbt,
terraform exactly 1.17.1, shellcheck, jq, git, python3, tar, timeout or
gtimeout (GNU coreutils), shasum or sha256sum; network access to the
Terraform registry (gate 6) and to the sbt/Maven repositories on a first
run.

Exit codes: 0 every gate passed (skipped probes are listed); 2 usage error;
3 missing prerequisite; 4 refused (not run from the repository root, or the
record folder is tracked); 5 an internal command failed, or interrupted (INT);
6 one or more gates failed or timed out (summary printed); 7 this script was
stopped by TERM (for example by an outer timeout).

Status: static: shellcheck clean; runtime: untested; human execution required.
EOF
}

step() {
  CURRENT_STEP="$1"
  printf 'STEP: %s %s\n' "${SCRIPT_NAME}" "$1"
}
info() { printf 'INFO: %s %s\n' "${SCRIPT_NAME}" "$*"; }
warn() { printf 'WARN: %s %s\n' "${SCRIPT_NAME}" "$*" >&2; }
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

with_timeout() {
  local secs="$1"
  shift
  "${TIMEOUT_BIN}" --kill-after=30 "${secs}" "$@"
}

# timeout status 124 (TERM) or 137 (KILL after the grace period) -> 7.
rc_class() {
  case "$1" in
    0) printf '0' ;;
    124 | 137) printf '7' ;;
    *) printf '1' ;;
  esac
}

# ---- arguments and location ------------------------------------------------------

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --fail-fast)
        FAIL_FAST="true"
        shift
        ;;
      --record-dir)
        [ "$#" -ge 2 ] || die 2 "--record-dir needs a path"
        [ -n "$2" ] || die 2 "--record-dir needs a non-empty path"
        RECORD_DIR_ARG="$2"
        shift 2
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
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
  REPO_ROOT="${here}"
}

# ---- prerequisites and tool versions --------------------------------------------

V_JAVA=""
V_SBT=""
V_TERRAFORM=""
V_SHELLCHECK=""
V_JQ=""
V_GIT=""
V_PYTHON=""

check_prerequisites() {
  step "prerequisites"
  local tool
  for tool in java sbt terraform shellcheck jq git python3 tar cut grep; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  else
    die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 ||
    die 3 "neither shasum nor sha256sum found"
  V_JAVA="$(java -version 2>&1 | head -n 1)" || true
  [[ "${V_JAVA}" =~ version\ \"17([.\"]) ]] || die 3 "java 17 is required (found: ${V_JAVA:-none})"
  export CHECKPOINT_DISABLE=1
  V_TERRAFORM="$(with_timeout 60 terraform version -json 2>/dev/null | jq -r '.terraform_version // empty')" || true
  [ "${V_TERRAFORM}" = "${TERRAFORM_VERSION_REQUIRED}" ] ||
    die 3 "terraform ${TERRAFORM_VERSION_REQUIRED} exactly is required (found: ${V_TERRAFORM:-unknown})"
  V_SHELLCHECK="$(shellcheck --version 2>/dev/null | grep -E '^version:' | head -n 1)" || true
  V_JQ="$(jq --version 2>/dev/null)" || true
  V_GIT="$(git --version 2>/dev/null)" || true
  V_PYTHON="$(python3 --version 2>&1 | head -n 1)" || true
  V_SBT="$(with_timeout 120 sbt --script-version 2>/dev/null | tail -n 1)" || V_SBT="unknown"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-check.XXXXXX")"
  : >"${TMP_DIR}/gates.tsv"
  : >"${TMP_DIR}/probes.tsv"
  : >"${TMP_DIR}/suites.tsv"
  info "java: ${V_JAVA}; terraform ${V_TERRAFORM}; ${V_SHELLCHECK}; ${V_JQ}; ${V_GIT}; ${V_PYTHON}; sbt runner ${V_SBT}; ${TIMEOUT_BIN}"
}

# ---- record ------------------------------------------------------------------------

prepare_record_dir() {
  step "record-dir"
  mkdir -p -- "${RECORD_DIR_ARG}" || die 5 "cannot create ${RECORD_DIR_ARG}"
  RECORD_DIR="$(cd -- "${RECORD_DIR_ARG}" && pwd -P)" || die 5 "cannot enter ${RECORD_DIR_ARG}"
  case "${RECORD_DIR}/" in
    "${REPO_ROOT}/"*)
      git check-ignore -q -- "${RECORD_DIR_ARG}" ||
        die 4 "refusing: --record-dir ${RECORD_DIR_ARG} is inside the repository and not gitignored"
      ;;
  esac
  HEAD_SHA="$(head_commit)" || die 3 "not a git repository with a commit"
  BUILD_SHA="$(build_commit)" || BUILD_SHA="unknown"
  DIRTY_AT_START="$(tree_dirty_flag)" || die 5 "git status failed"
  ATTEMPT_ID="$(new_attempt_id)"
  STARTED_UTC="$(sc_utc_now)"
  RECORD_FILE="${RECORD_DIR}/check-${ATTEMPT_ID}.json"
  jq -n --arg kind "scholarchain-check-record" --arg attempt "${ATTEMPT_ID}" \
    --arg head "${HEAD_SHA}" --arg build "${BUILD_SHA}" --argjson dirty "${DIRTY_AT_START}" \
    --arg started "${STARTED_UTC}" --argjson fail_fast "${FAIL_FAST}" \
    '{kind: $kind, version: 1, status: "started", attempt_id: $attempt, head: $head,
      build_commit: $build, tree_dirty: $dirty, fail_fast: $fail_fast, started_utc: $started}' |
    write_record_atomic "${RECORD_DIR}/check-${ATTEMPT_ID}.started.json" ||
    die 5 "could not write the started record"
  STARTED_WRITTEN="true"
  info "attempt ${ATTEMPT_ID}; HEAD ${HEAD_SHA}; build commit ${BUILD_SHA}; dirty ${DIRTY_AT_START}"
  info "record: ${RECORD_FILE}"
}

tsv_to_json() {
  # $1 file, $2 jq object builder over the split fields (array .)
  jq -R -s "split(\"\n\") | map(select(length > 0) | split(\"\t\") | $2)" "$1"
}

write_final_record() {
  local rc="$1" status gates probes suites
  case "${rc}" in
    0) status="succeeded" ;;
    7) status="timed-out" ;;
    *) status="failed" ;;
  esac
  gates="$(tsv_to_json "${TMP_DIR}/gates.tsv" \
    '{name: .[0], status: .[1], exit_code: (.[2] | tonumber), seconds: (.[3] | tonumber)}' 2>/dev/null)" ||
    gates='[]'
  probes="$(tsv_to_json "${TMP_DIR}/probes.tsv" \
    '{name: .[0], status: .[1], exit_code: (.[2] | tonumber), expected_exit_code: (.[3] | tonumber)}' 2>/dev/null)" ||
    probes='[]'
  suites="$(tsv_to_json "${TMP_DIR}/suites.tsv" \
    '{report: .[0], suite: .[1], tests: (.[2] | tonumber), failures: (.[3] | tonumber), errors: (.[4] | tonumber), skipped: (.[5] | tonumber)}' 2>/dev/null)" ||
    suites='[]'
  jq -n --arg kind "scholarchain-check-record" --arg attempt "${ATTEMPT_ID}" \
    --arg head "${HEAD_SHA}" --arg build "${BUILD_SHA}" --argjson dirty "${DIRTY_AT_START:-null}" \
    --arg dirty_end "$(tree_dirty_flag 2>/dev/null || printf 'unknown')" \
    --arg started "${STARTED_UTC}" --arg ended "$(sc_utc_now)" --arg status "${status}" \
    --argjson exit_code "${rc}" --arg step "${CURRENT_STEP}" --arg signal "${SIGNAL_NAME}" \
    --argjson fail_fast "${FAIL_FAST}" \
    --arg java "${V_JAVA}" --arg sbt "${V_SBT}" --arg terraform "${V_TERRAFORM}" \
    --arg shellcheck "${V_SHELLCHECK}" --arg jqv "${V_JQ}" --arg git "${V_GIT}" \
    --arg python "${V_PYTHON}" --arg bash "${BASH_VERSION}" --arg timeout_bin "${TIMEOUT_BIN}" \
    --argjson module_tests "${MODULE_TESTS}" --argjson contract_tests "${CONTRACT_TESTS}" \
    --argjson gates "${gates}" --argjson probes "${probes}" --argjson suites "${suites}" \
    '{kind: $kind, version: 1, status: $status, exit_code: $exit_code,
      failed_step: (if $status == "succeeded" then null else $step end),
      signal: (if $signal == "" then null else $signal end),
      attempt_id: $attempt, head: $head, build_commit: $build, tree_dirty: $dirty,
      tree_dirty_at_end: $dirty_end, fail_fast: $fail_fast,
      started_utc: $started, ended_utc: $ended,
      tools: {java: $java, sbt_runner: $sbt, terraform: $terraform, shellcheck: $shellcheck,
              jq: $jqv, git: $git, python3: $python, bash: $bash, timeout: $timeout_bin},
      gates: $gates, module_tests: $module_tests, contract_tests: $contract_tests,
      test_suites: $suites, probes: $probes}' 2>/dev/null >"${TMP_DIR}/final-record.json"
  if write_record_atomic "${RECORD_FILE}" <"${TMP_DIR}/final-record.json"; then
    FINAL_WRITTEN="true"
    printf 'CHECK-RECORD: %s\n' "${RECORD_FILE}"
  else
    printf 'ERROR: %s could not write the final record %s\n' "${SCRIPT_NAME}" "${RECORD_FILE}" >&2
  fi
}

# ---- gate runner -------------------------------------------------------------------

ANY_GATE_FAILED="false"

# run_gate NAME FUNCTION: the function returns 0 (passed), 1 (failed) or
# 7 (timed out). Gate functions check every command themselves (they run in a
# condition context, where errexit does not apply).
run_gate() {
  local name="$1" fn="$2" start rc=0 status
  if [ "${STOP_GATES}" = "true" ]; then
    printf 'GATE: %s not-run (fail-fast)\n' "${name}"
    printf '%s\tnot-run\t0\t0\n' "${name}" >>"${TMP_DIR}/gates.tsv"
    return 0
  fi
  step "gate-${name}"
  start="${SECONDS}"
  "${fn}" || rc=$?
  case "${rc}" in
    0) status="passed" ;;
    7) status="timed-out" ;;
    *) status="failed" ;;
  esac
  printf 'GATE: %s %s (%ss)\n' "${name}" "${status}" "$((SECONDS - start))"
  printf '%s\t%s\t%s\t%s\n' "${name}" "${status}" "${rc}" "$((SECONDS - start))" >>"${TMP_DIR}/gates.tsv"
  if [ "${rc}" -ne 0 ]; then
    ANY_GATE_FAILED="true"
    if [ "${FAIL_FAST}" = "true" ]; then
      STOP_GATES="true"
    fi
  fi
  return 0
}

# ---- gates 1 to 7 ------------------------------------------------------------------

gate_constitution() {
  local rc=0
  with_timeout "${STEP_TIMEOUT}" bash .claude/hooks/validate-agent-files.sh || rc=$?
  return "$(rc_class "${rc}")"
}

gate_json() {
  local f n=0 bad=0
  while IFS= read -r -d '' f; do
    case "${f}" in
      */src/test/* | tests/*) continue ;;
    esac
    n=$((n + 1))
    if jq empty <"${f}" >/dev/null 2>&1; then
      printf '  json ok: %s\n' "${f}"
    else
      printf '  json INVALID: %s\n' "${f}"
      bad=1
    fi
  done < <(git ls-files -z -- '*.json')
  printf '  json files checked: %s\n' "${n}"
  [ "${n}" -gt 0 ] || return 1
  return "${bad}"
}

gate_hook_syntax() {
  local f bad=0 rc
  for f in .claude/hooks/*.sh .claude/hook-tests/*.sh; do
    [ -f "${f}" ] || continue
    if bash -n "${f}"; then
      printf '  bash -n ok: %s\n' "${f}"
    else
      printf '  bash -n FAILED: %s\n' "${f}"
      bad=1
    fi
  done
  for f in .claude/hook-tests/guard-selftest.sh .claude/hook-tests/stop-hook-selftest.sh; do
    if [ ! -f "${f}" ]; then
      printf '  missing self-test: %s\n' "${f}"
      bad=1
      continue
    fi
    rc=0
    with_timeout "${SELFTEST_TIMEOUT}" bash "${f}" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      printf '  self-test passed: %s\n' "${f}"
    else
      printf '  self-test FAILED (status %s): %s\n' "${rc}" "${f}"
      [ "$(rc_class "${rc}")" = "7" ] && return 7
      bad=1
    fi
  done
  return "${bad}"
}

gate_shell() {
  local files=() f rc=0
  # Scope (Phase 13 finding I-19, human decision): the repository's own
  # scripts only. The hooks and hook self-tests (.claude/**, factory-engineer)
  # are covered by gate 1 (validate-agent-files.sh) and gate 3 (bash -n and
  # both self-tests), not by shellcheck.
  for f in scripts/*.sh scripts/lib/*.sh datasets/scripts/*.sh \
    infra/terraform/modules/emr/files/bootstrap.sh; do
    if [ -f "${f}" ]; then
      files+=("${f}")
    fi
  done
  [ "${#files[@]}" -gt 0 ] || return 1
  printf '  shellcheck files: %s\n' "${files[*]}"
  with_timeout "${STEP_TIMEOUT}" shellcheck -x -P SCRIPTDIR "${files[@]}" || rc=$?
  return "$(rc_class "${rc}")"
}

gate_datasets() {
  local rc=0
  PYTHONDONTWRITEBYTECODE=1 with_timeout "${DATASETS_TIMEOUT}" bash datasets/scripts/validate.sh || rc=$?
  return "$(rc_class "${rc}")"
}

gate_terraform() {
  local rc=0
  with_timeout "${STEP_TIMEOUT}" terraform fmt -recursive -check infra/terraform || rc=$?
  if [ "${rc}" -ne 0 ]; then
    printf '  terraform fmt -check failed (status %s)\n' "${rc}"
    return "$(rc_class "${rc}")"
  fi
  with_timeout "${TF_INIT_TIMEOUT}" terraform "-chdir=${TF_DIR}" init -backend=false -input=false \
    -lockfile=readonly -no-color || rc=$?
  if [ "${rc}" -ne 0 ]; then
    printf '  terraform init -backend=false failed (status %s; needs the Terraform registry)\n' "${rc}"
    return "$(rc_class "${rc}")"
  fi
  with_timeout "${TF_VALIDATE_TIMEOUT}" terraform "-chdir=${TF_DIR}" validate -no-color || rc=$?
  return "$(rc_class "${rc}")"
}

# Reads per-suite counts from the sbt test-report XML files newer than STAMP.
collect_suites() {
  local stamp="$1" f line name tests failures errors skipped
  while IFS= read -r f; do
    line="$(grep -m 1 '<testsuite ' "${f}" 2>/dev/null || true)"
    name="unparsed"
    tests=0
    failures=0
    errors=0
    skipped=0
    [[ "${line}" =~ \ name=\"([^\"]*)\" ]] && name="${BASH_REMATCH[1]}"
    [[ "${line}" =~ \ tests=\"([0-9]+)\" ]] && tests="${BASH_REMATCH[1]}"
    [[ "${line}" =~ \ failures=\"([0-9]+)\" ]] && failures="${BASH_REMATCH[1]}"
    [[ "${line}" =~ \ errors=\"([0-9]+)\" ]] && errors="${BASH_REMATCH[1]}"
    [[ "${line}" =~ \ skipped=\"([0-9]+)\" ]] && skipped="${BASH_REMATCH[1]}"
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "${f#./}" "${name}" "${tests}" "${failures}" "${errors}" "${skipped}" \
      >>"${TMP_DIR}/suites.tsv"
  done < <(find . -path ./.git -prune -o -type f -name 'TEST-*.xml' -path '*/target/*' \
    -path '*test-reports*' -newer "${stamp}" -print)
  printf '  test-report suites recorded: %s\n' "$(wc -l <"${TMP_DIR}/suites.tsv" | tr -d ' ')"
}

gate_sbt() {
  local rc=0 log="${TMP_DIR}/sbt-check.log" plog="${TMP_DIR}/sbt-package.log" stamp="${TMP_DIR}/sbt.stamp" n
  : >"${stamp}"
  with_timeout "${SBT_CHECK_TIMEOUT}" sbt -batch check 2>&1 | tee "${log}" || rc="${PIPESTATUS[0]}"
  n="$(grep -oE 'module_tests=[0-9]+' "${log}" | tail -n 1 | cut -d= -f2 || true)"
  [ -n "${n}" ] && MODULE_TESTS="${n}"
  n="$(grep -oE 'contract_tests=[0-9]+' "${log}" | tail -n 1 | cut -d= -f2 || true)"
  [ -n "${n}" ] && CONTRACT_TESTS="${n}"
  collect_suites "${stamp}"
  if [ "${rc}" -ne 0 ]; then
    printf '  sbt -batch check failed (status %s)\n' "${rc}"
    return "$(rc_class "${rc}")"
  fi
  grep -q 'Updated .*\.last-test-run (module_tests=' "${log}" || {
    printf '  sbt check passed but printed no "Updated .claude/.last-test-run" line\n'
    return 1
  }
  printf '  module_tests=%s contract_tests=%s\n' "${MODULE_TESTS}" "${CONTRACT_TESTS}"
  rc=0
  with_timeout "${SBT_PACKAGE_TIMEOUT}" sbt -batch packageCheck 2>&1 | tee "${plog}" || rc="${PIPESTATUS[0]}"
  if [ "${rc}" -ne 0 ]; then
    printf '  sbt -batch packageCheck failed (status %s)\n' "${rc}"
    return "$(rc_class "${rc}")"
  fi
  grep -q 'packageCheck: ok' "${plog}" || {
    printf '  packageCheck printed no "packageCheck: ok" line\n'
    return 1
  }
  return 0
}

# ---- gate 8: script contracts (static) ---------------------------------------------

# Files whose calls are checked.
contract_script_files() {
  local f
  for f in scripts/*.sh scripts/lib/*.sh; do
    if [ -f "${f}" ]; then
      printf '%s\n' "${f}"
    fi
  done
}

# scala_block FILE START_REGEX: prints the lines of the parenthesized
# definition that starts at the first line matching START_REGEX (extended
# regex), up to the line where its parentheses balance.
scala_block() {
  local file="$1" start="$2" line started=0 seen=0 depth=0 opens closes
  while IFS= read -r line || [ -n "${line}" ]; do
    if [ "${started}" -eq 0 ]; then
      if printf '%s\n' "${line}" | grep -qE -- "${start}"; then
        started=1
      else
        continue
      fi
    fi
    printf '%s\n' "${line}"
    opens="${line//[^(]/}"
    closes="${line//[^)]/}"
    depth=$((depth + ${#opens} - ${#closes}))
    if [ "${#opens}" -gt 0 ]; then
      seen=1
    fi
    if [ "${seen}" -eq 1 ] && [ "${depth}" -le 0 ]; then
      break
    fi
  done <"${file}"
}

# constant_options KEY [SUBCOMMAND]: prints the option names (one per line) of
# an entry point's option constant; status 1 for an unknown key.
constant_options() {
  local key="$1" sub="${2:-}" file valname
  case "${key}" in
    ErMain) file="modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErMain.scala" ;;
    JsonlExport) file="modules/spark/src/main/scala/org/scholarchain/spark/JsonlExport.scala" ;;
    RagIngestJob) file="modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala" ;;
    StorageLoadMain) file="modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala" ;;
    *) return 1 ;;
  esac
  [ -f "${file}" ] || return 1
  if [ "${key}" = "StorageLoadMain" ]; then
    [[ "${sub}" =~ ^[a-z][a-z0-9-]*$ ]] || return 1
    valname="$(grep -oE "val [A-Za-z0-9_]+ = \"${sub}\"" "${file}" | head -n 1 | cut -d' ' -f2)"
    [ -n "${valname}" ] || return 1
    scala_block "${file}" "^[[:space:]]*${valname} -> SubcommandOptions\\(" |
      grep -oE '"[a-z][a-z0-9-]*"' | tr -d '"'
  else
    {
      scala_block "${file}" '^[[:space:]]*val Required: List\[String\] = List\('
      scala_block "${file}" '^[[:space:]]*val Optional: List\[String\] = List\('
    } | grep -oE '"[a-z][a-z0-9-]*"' | tr -d '"'
  fi
}

check_options_against() {
  # $1 label, $2 key, $3 subcommand (may be empty), remaining: option names
  local label="$1" key="$2" sub="$3" known opt bad=0
  shift 3
  known="$(constant_options "${key}" "${sub}")" || {
    printf '  CONTRACT unknown entry point "%s %s" (%s)\n' "${key}" "${sub}" "${label}"
    return 1
  }
  [ -n "${known}" ] || {
    printf '  CONTRACT no option constant found for "%s %s" (%s)\n' "${key}" "${sub}" "${label}"
    return 1
  }
  for opt in "$@"; do
    if printf '%s\n' "${known}" | grep -qxF -- "${opt}"; then
      printf '  option ok: %s --%s (%s %s)\n' "${label}" "${opt}" "${key}" "${sub}"
    else
      printf '  CONTRACT option --%s is not in %s %s option constant (%s)\n' "${opt}" "${key}" "${sub}" "${label}"
      bad=1
    fi
  done
  return "${bad}"
}

gate_script_contracts() {
  local bad=0 names name classes cls f line code key sub opts n_marks
  local files=()
  while IFS= read -r f; do
    files+=("${f}")
  done < <(contract_script_files)
  [ "${#files[@]}" -gt 0 ] || return 1
  [ -f "${OUTPUTS_TF}" ] && [ -f "${PACKAGE_CHECK_SCALA}" ] && [ -f "${SFN_TF}" ] || {
    printf '  missing %s, %s or %s\n' "${OUTPUTS_TF}" "${PACKAGE_CHECK_SCALA}" "${SFN_TF}"
    return 1
  }

  # (a) terraform output names.
  names="$({
    grep -ohE '\$\(out(_json)? [a-z_][a-z0-9_]*\)' "${files[@]}" | sed -E 's/^\$\(out(_json)? //; s/\)$//'
    grep -ohE "(^|[^.A-Za-z0-9_])\\.[a-z_][a-z0-9_]*\\.value" "${files[@]}" |
      sed -E 's/^[^.]*\.//; s/\.value$//'
  } | sort -u)" || true
  for name in ${names}; do
    if grep -qE "^output \"${name}\" " "${OUTPUTS_TF}"; then
      printf '  output ok: %s\n' "${name}"
    else
      printf '  CONTRACT output "%s" is read by a script but is not in %s\n' "${name}" "${OUTPUTS_TF}"
      bad=1
    fi
  done

  # (b) main classes named by scripts and the state machine.
  classes="$(grep -ohE 'org\.scholarchain(\.[a-z0-9_]+)+\.[A-Z][A-Za-z0-9_]*' "${files[@]}" "${SFN_TF}" |
    sort -u)" || true
  for cls in ${classes}; do
    if grep -qF "\"${cls}\"" "${PACKAGE_CHECK_SCALA}"; then
      printf '  class ok: %s (packageCheck list)\n' "${cls}"
    else
      printf '  CONTRACT class %s is named by a script or the state machine but is not in %s\n' \
        "${cls}" "${PACKAGE_CHECK_SCALA}"
      bad=1
    fi
  done

  # (c) options on lines marked "# contract: KEY [SUBCOMMAND]".
  local marker_re='# contract: ([A-Za-z]+)( ([a-z][a-z0-9-]*))?[[:space:]]*$'
  for f in "${files[@]}"; do
    # check.sh itself calls no entry point; its own text describes the markers.
    [ "${f}" != "scripts/check.sh" ] || continue
    n_marks=0
    # The file name is passed only as a label; nothing writes the file.
    # shellcheck disable=SC2094
    while IFS= read -r line; do
      [[ "${line}" =~ ${marker_re} ]] || continue
      key="${BASH_REMATCH[1]}"
      sub="${BASH_REMATCH[3]}"
      code="${line%%# contract: *}"
      # shellcheck disable=SC2207
      opts=($(printf '%s\n' "${code}" | grep -oE -- '(^|[^A-Za-z0-9_-])--[a-z][a-z0-9-]*' | sed -E 's/^[^-]*--//')) || true
      n_marks=$((n_marks + 1))
      check_options_against "${f}" "${key}" "${sub}" ${opts[@]+"${opts[@]}"} || bad=1
    done <"${f}"
    for key in ErMain JsonlExport RagIngestJob StorageLoadMain; do
      if grep -qE "org\\.scholarchain(\\.[a-z0-9_]+)+\\.${key}([^A-Za-z0-9_]|\$)" "${f}" &&
        ! grep -qE "# contract: ${key}( |\$)" "${f}"; then
        printf '  CONTRACT %s names %s but marks no option line with "# contract: %s"\n' "${f}" "${key}" "${key}"
        bad=1
      fi
    done
    [ "${n_marks}" -eq 0 ] || printf '  contract-marked lines in %s: %s\n' "${f}" "${n_marks}"
  done

  # (c') the state machine's JsonlExport step (spark-submit's own options excluded).
  line="$(grep -F "'org.scholarchain.spark.JsonlExport'" "${SFN_TF}" | head -n 1)" || true
  if [ -n "${line}" ]; then
    # shellcheck disable=SC2207
    opts=($(printf '%s\n' "${line}" | grep -oE -- "'--[a-z][a-z0-9-]*'" | tr -d "'" | sed -E 's/^--//' |
      grep -vxE 'deploy-mode|class|master|conf|name|jars|files|driver-memory|executor-memory|num-executors|executor-cores|packages|properties-file')) || true
    check_options_against "${SFN_TF}" JsonlExport "" ${opts[@]+"${opts[@]}"} || bad=1
  else
    printf '  CONTRACT %s has no JsonlExport step line\n' "${SFN_TF}"
    bad=1
  fi
  printf '  not checked (no option constant in source): org.scholarchain.spark.GraphAnalyticsJob, org.scholarchain.hadoop.pipeline.ScholarChainHadoop\n'
  return "${bad}"
}

# ---- gate 9: offline probes --------------------------------------------------------

PROBE_DIR=""
PROBES_BAD=0

# probe NAME EXPECTED_EXIT REGEX COMMAND...: runs COMMAND with every AWS
# credential source disabled, under timeout, TMPDIR inside PROBE_DIR.
probe() {
  local name="$1" expected="$2" regex="$3" rc=0 out status
  shift 3
  out="${PROBE_DIR}/${name}.out"
  env -u AWS_PROFILE -u AWS_DEFAULT_PROFILE -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY \
    -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN -u AWS_ROLE_ARN -u AWS_WEB_IDENTITY_TOKEN_FILE \
    -u AWS_CONTAINER_CREDENTIALS_RELATIVE_URI -u AWS_CONTAINER_CREDENTIALS_FULL_URI \
    -u AWS_CONTAINER_AUTHORIZATION_TOKEN -u SCHOLARCHAIN_SERVICE_API_KEY \
    AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true \
    TMPDIR="${PROBE_DIR}/tmp" \
    "${TIMEOUT_BIN}" --kill-after=10 "${PROBE_TIMEOUT}" "$@" >"${out}" 2>&1 || rc=$?
  if [ "${rc}" -eq "${expected}" ] && grep -qE -- "${regex}" "${out}"; then
    status="passed"
  else
    status="failed"
    PROBES_BAD=1
    printf '  probe output (last 5 lines) of %s:\n' "${name}"
    tail -n 5 "${out}" | sed 's/^/    | /'
  fi
  printf '  PROBE: %s %s (exit %s, expected %s)\n' "${name}" "${status}" "${rc}" "${expected}"
  printf '%s\t%s\t%s\t%s\n' "${name}" "${status}" "${rc}" "${expected}" >>"${TMP_DIR}/probes.tsv"
  [ "${status}" = "passed" ]
}

probe_skip() {
  printf '  PROBE: %s skipped (%s)\n' "$1" "$2"
  printf '%s\tskipped\t0\t%s\n' "$1" "$3" >>"${TMP_DIR}/probes.tsv"
}

probe_archive_layout() {
  local tars=() t listing keys root bad=0 key want got
  for t in "${PROBE_DIR}/archive"/*.tar; do
    if [ -f "${t}" ]; then
      tars+=("${t}")
    fi
  done
  if [ "${#tars[@]}" -ne 1 ]; then
    printf '  archive layout: expected exactly one .tar, found %s\n' "${#tars[@]}"
    return 1
  fi
  t="${tars[0]}"
  listing="${PROBE_DIR}/archive-listing.txt"
  keys="${PROBE_DIR}/archive-keys.txt"
  tar -tf "${t}" >"${listing}" 2>/dev/null || {
    printf '  archive layout: tar cannot list %s\n' "${t}"
    return 1
  }
  root="$(archive_root "${listing}")"
  if [ -z "${root}" ]; then
    printf '  archive layout: no single root folder\n'
    bad=1
  fi
  archive_keys "${listing}" >"${keys}"
  grep -qxF 'MANIFEST.json' "${keys}" || {
    printf '  archive layout: no MANIFEST.json under the root folder\n'
    bad=1
  }
  grep -qE "^(\\./)?${root}/repo/" "${listing}" || {
    printf '  archive layout: no repo/ folder\n'
    bad=1
  }
  if grep -qE '^reports/' "${keys}"; then
    printf '  archive layout: a top-level reports/ exists in local-only mode\n'
    bad=1
  fi
  while IFS= read -r key; do
    case "${key}" in
      repo/* | plans/* | MANIFEST.json) ;;
      *)
        printf '  archive layout: unexpected key %s\n' "${key}"
        bad=1
        ;;
    esac
  done <"${keys}"
  if [ -f "${t}.sha256" ]; then
    want="$(cut -d ' ' -f 1 <"${t}.sha256")"
    got="$(sc_sha256 "${t}")"
    [ "${want}" = "${got}" ] || {
      printf '  archive layout: %s.sha256 does not match the tar\n' "${t##*/}"
      bad=1
    }
  else
    printf '  archive layout: no %s.sha256\n' "${t##*/}"
    bad=1
  fi
  printf '  archive keys (cleanup.sh rule): %s, root folder %s\n' "$(wc -l <"${keys}" | tr -d ' ')" "${root:-<none>}"
  return "${bad}"
}

gate_probes() {
  local s rc example_sha dirty
  PROBE_DIR="${TMP_DIR}/probes"
  mkdir -p -- "${PROBE_DIR}/tmp" "${PROBE_DIR}/archive"
  PROBES_BAD=0
  for s in ${TEN_SCRIPTS}; do
    if [ ! -f "scripts/${s}.sh" ]; then
      printf '  PROBE: help-%s failed (scripts/%s.sh missing)\n' "${s}" "${s}"
      printf 'help-%s\tfailed\t127\t0\n' "${s}" >>"${TMP_DIR}/probes.tsv"
      PROBES_BAD=1
      continue
    fi
    probe "help-${s}" 0 "^Usage: scripts/${s}\\.sh" bash "scripts/${s}.sh" --help || true
  done
  probe deploy-wrong-checksum 4 '^ERROR: deploy\.sh .*exit=4' \
    bash scripts/deploy.sh --plan-sha256 0000000000000000000000000000000000000000000000000000000000000000 || true
  dirty="$(tree_dirty_flag)" || dirty="true"
  if [ ! -f "${EXAMPLE_MANIFEST}" ]; then
    printf '  PROBE: run-experiment-example failed (%s missing)\n' "${EXAMPLE_MANIFEST}"
    printf 'run-experiment-example\tfailed\t127\t4\n' >>"${TMP_DIR}/probes.tsv"
    PROBES_BAD=1
  elif [ "${dirty}" = "true" ]; then
    probe_skip run-experiment-example "dirty tree: the dirty-tree refusal comes first; run check.sh in a clean clone" 4
  else
    example_sha="$(sc_sha256 "${EXAMPLE_MANIFEST}")"
    probe run-experiment-example 4 'refusing: the manifest is an example' \
      bash scripts/run-experiment.sh --manifest "${EXAMPLE_MANIFEST}" --manifest-sha256 "${example_sha}" || true
  fi
  probe smoke-no-key 3 'SCHOLARCHAIN_SERVICE_API_KEY' \
    bash scripts/smoke-test.sh --question probe --api-url https://example.invalid || true
  probe cleanup-no-archive 4 'evidence-archive' bash scripts/cleanup.sh --list || true
  rc=0
  probe archive-local-only 0 '^ARCHIVE-TAR: ' \
    bash scripts/archive-evidence.sh --local-only --out-dir "${PROBE_DIR}/archive" || rc=1
  if [ "${rc}" -eq 0 ]; then
    if probe_archive_layout; then
      printf '  PROBE: archive-layout passed\n'
      printf 'archive-layout\tpassed\t0\t0\n' >>"${TMP_DIR}/probes.tsv"
    else
      printf '  PROBE: archive-layout failed\n'
      printf 'archive-layout\tfailed\t1\t0\n' >>"${TMP_DIR}/probes.tsv"
      PROBES_BAD=1
    fi
  fi
  return "${PROBES_BAD}"
}

# ---- summary -----------------------------------------------------------------------

print_summary() {
  step "summary"
  printf 'SUMMARY: %-18s %-10s %5s %8s\n' "gate" "status" "exit" "seconds"
  local name status code secs
  while IFS="$(printf '\t')" read -r name status code secs; do
    printf 'SUMMARY: %-18s %-10s %5s %8s\n' "${name}" "${status}" "${code}" "${secs}"
  done <"${TMP_DIR}/gates.tsv"
  printf 'SUMMARY: module_tests=%s contract_tests=%s\n' "${MODULE_TESTS}" "${CONTRACT_TESTS}"
}

main() {
  parse_args "$@"
  enter_repo_root
  check_prerequisites
  prepare_record_dir
  run_gate constitution gate_constitution
  run_gate json gate_json
  run_gate hook-syntax gate_hook_syntax
  run_gate shell gate_shell
  run_gate datasets gate_datasets
  run_gate terraform gate_terraform
  run_gate sbt gate_sbt
  run_gate script-contracts gate_script_contracts
  run_gate probes gate_probes
  print_summary
  if [ "${ANY_GATE_FAILED}" = "true" ]; then
    die 6 "one or more gates failed (summary above)"
  fi
  step "done"
}

main "$@"
