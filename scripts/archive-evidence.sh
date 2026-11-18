#!/usr/bin/env bash
# scripts/archive-evidence.sh - gather the grading evidence into one
# versioned local folder, a tar of it and the tar's sha256 (Phase 13 work
# order, decision 9.8, RK-E; spec work-order item 8). Owner: infra-engineer
# (author). Run by the human.
#
# Status (Phase 13): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="archive-evidence.sh"
readonly EXPECTED_REGION="us-east-1"
readonly TF_DIR="infra/terraform/environments/course"
readonly PLANS_DIR="infra/terraform/environments/course/plans"
readonly DEFAULT_OUT_DIR="evidence/archives"
readonly DEFAULT_MAX_MB=200
readonly OUTPUT_TIMEOUT=120
readonly AWS_TIMEOUT=120
readonly GET_TIMEOUT=600
readonly TAR_TIMEOUT=1800
readonly MAX_TRIES=3

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
LOCAL_ONLY="false"
OUT_DIR_ARG="${DEFAULT_OUT_DIR}"
OUT_DIR=""
MAX_MB="${DEFAULT_MAX_MB}"
MAX_MB_GIVEN="false"
METRICS_FILES=()
HEAD_SHA=""
BUILD_SHA=""
TREE_DIRTY=""
NAME=""
PARTIAL=""
FINAL_DIR=""
TAR_FILE=""
TAR_SHA=""
STARTED_WRITTEN="false"
FINAL_WRITTEN="false"
SIGNAL_NAME=""
STARTED_UTC=""
DATA_BUCKET=""
REPORTS_COUNT=0
REPORTS_BYTES=0
FILE_COUNT=0
IDENTIFIER_WARNINGS="false"

usage() {
  cat <<'EOF'
Usage: scripts/archive-evidence.sh --local-only [--out-dir DIR]
       scripts/archive-evidence.sh [--out-dir DIR] [--max-mb N] [--metrics-file PATH]...
       scripts/archive-evidence.sh --help

Run from the repository root: bash scripts/archive-evidence.sh ...
It never refuses a dirty tree; it records the dirty flag.

Builds DIR/NAME.partial/ (DIR: evidence/archives by default, gitignored;
NAME: the attempt ID, UTC time, short HEAD and 4 random hex), renames it to
DIR/NAME/ only when complete, then writes DIR/NAME.tar (one root folder,
NAME/) and DIR/NAME.tar.sha256, and prints both. It refuses (exit 4) if NAME
already exists in DIR. A failure leaves NAME.partial/, which nothing reads as
an archive. Nothing is ever overwritten or removed.

Layout inside NAME/:
  MANIFEST.json  every other file with its size and sha256; HEAD, build
                 commit, dirty flag, mode (local-only or s3), and whether
                 reports/ and metrics/ are included.
  repo/          copies of repository evidence, at their repository paths:
                 releases/**, .claude/.last-test-run, every sbt test-report
                 XML under */target/**/test-reports/ that still exists (after
                 package.sh's sbt clean there are none; the check records keep
                 the counts), evidence/checks/**, evidence/packages/**,
                 evidence/runs/**, experiments/raw/**, experiments/manifests/**
                 and the repository's own reports/** (here, at repo/reports/,
                 never at the archive's top-level reports/: RK-E namespace
                 rule).
  plans/         for each plan record infra/terraform/environments/course/
                 plans/*.record.json: NAME.json (plan sha256, plan commit,
                 build commit, plan ID, clean flag, Region, whether the plan
                 file still exists and matches the sha256; the account ID
                 masked) and NAME.summary.txt (plan.sh's summary, account
                 masked). Never the raw .tfplan file or the raw plan text.
  reports/       S3 mode only: an exact byte copy of every object under the
                 data lake's reports/ prefix, at reports/KEY-AFTER-reports/,
                 so that scripts/cleanup.sh finds each S3 key at
                 [NAME/]reports/KEY (its archive_keys rule:
                 scripts/lib/identity.sh).
  metrics/       S3 mode only: the CloudWatch exports given with
                 --metrics-file (for example an experiment's
                 metrics/metric-data.json), by file name.

Modes:
  --local-only   no AWS call at all; reports/ and metrics/ are absent and
                 MANIFEST.json says so.
  default (S3)   reads data_bucket_name and vector_bucket_name from terraform
                 output and the account (aws sts get-caller-identity), lists
                 s3://{data-bucket}/reports/ (aws s3api list-objects-v2) and
                 refuses if its total size is above --max-mb (default 200),
                 then copies each object (aws s3api get-object, retried, a
                 read) and checks its size.

Options:
  --out-dir DIR        output folder (created if missing; inside this
                       checkout it must be gitignored)
  --max-mb N           S3 mode: upper bound of the reports/ copy (1..10000)
  --metrics-file PATH  S3 mode: a CloudWatch export to include (repeatable)

Records: DIR/NAME.started.json first, then DIR/NAME.json (status, tar path and
sha256, file count, mode). No record or MANIFEST.json holds an account ID,
ARN with an account ID or bucket name (checked with grep -F before it is
final); plans/ is checked the same way. The byte copies under repo/ and
reports/ are kept exactly; if one holds an identifier, the script warns and
records it. The archive is gitignored and never committed.

Requirements: bash, git, jq, tar, find, shasum or sha256sum, timeout or
gtimeout (GNU coreutils); S3 mode also terraform (output only) and aws (CLI
v2) with read access to the data lake's reports/ prefix.

Exit codes: 0 archive written; 2 usage error; 3 missing prerequisite or
Terraform output; 4 refused (NAME exists, output folder tracked, reports/
above --max-mb, unsafe S3 key, duplicate metrics file name); 5 a command
failed; 6 a check failed (identifier in MANIFEST.json, plans/ or a metrics
file; size mismatch); 7 timed out.

Status: static: shellcheck clean; runtime: untested; human execution required.
EOF
}

step() {
  CURRENT_STEP="$1"
  printf 'STEP: %s %s\n' "${SCRIPT_NAME}" "$1"
}
info() { printf 'INFO: %s %s\n' "${SCRIPT_NAME}" "$(mask_text "$*")"; }
warn() { printf 'WARN: %s %s\n' "${SCRIPT_NAME}" "$(mask_text "$*")" >&2; }
die() {
  local code="$1"
  shift
  printf 'ERROR: %s step=%s exit=%s: %s\n' "${SCRIPT_NAME}" "${CURRENT_STEP}" "${code}" "$(mask_text "$*")" >&2
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

# Until the helper is loaded, mask_text prints its argument unchanged.
mask_text() { printf '%s' "$*"; }

with_timeout() {
  local secs="$1"
  shift
  "${TIMEOUT_BIN}" --kill-after=30 "${secs}" "$@"
}

# ---- arguments and location ------------------------------------------------------

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --local-only)
        LOCAL_ONLY="true"
        shift
        ;;
      --out-dir)
        [ "$#" -ge 2 ] && [ -n "$2" ] || die 2 "--out-dir needs a path"
        OUT_DIR_ARG="$2"
        shift 2
        ;;
      --max-mb)
        [ "$#" -ge 2 ] || die 2 "--max-mb needs a value"
        [[ "$2" =~ ^[0-9]+$ ]] && [ "$2" -ge 1 ] && [ "$2" -le 10000 ] || die 2 "--max-mb must be 1..10000"
        MAX_MB="$2"
        MAX_MB_GIVEN="true"
        shift 2
        ;;
      --metrics-file)
        [ "$#" -ge 2 ] && [ -n "$2" ] || die 2 "--metrics-file needs a path"
        METRICS_FILES+=("$2")
        shift 2
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
  if [ "${LOCAL_ONLY}" = "true" ]; then
    [ "${#METRICS_FILES[@]}" -eq 0 ] || die 2 "--metrics-file belongs to S3 mode (not --local-only)"
    [ "${MAX_MB_GIVEN}" = "false" ] || die 2 "--max-mb belongs to S3 mode (not --local-only)"
  fi
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

check_prerequisites() {
  step "prerequisites"
  local tool
  for tool in git jq tar find cut grep; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if [ "${LOCAL_ONLY}" = "false" ]; then
    for tool in terraform aws; do
      command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
    done
    aws --version 2>&1 | grep -q '^aws-cli/2' || die 3 "AWS CLI v2 is required"
  fi
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  else
    die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 ||
    die 3 "neither shasum nor sha256sum found"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-archive.XXXXXX")"
}

# ---- records ---------------------------------------------------------------------------

prepare() {
  step "prepare"
  HEAD_SHA="$(head_commit)" || die 3 "not a git repository with a commit"
  BUILD_SHA="$(build_commit)" || BUILD_SHA="unknown"
  TREE_DIRTY="$(tree_dirty_flag)" || die 5 "git status failed"
  mkdir -p -- "${OUT_DIR_ARG}" || die 5 "cannot create ${OUT_DIR_ARG}"
  OUT_DIR="$(cd -- "${OUT_DIR_ARG}" && pwd -P)" || die 5 "cannot enter ${OUT_DIR_ARG}"
  case "${OUT_DIR}/" in
    "$(pwd -P)/"*)
      git check-ignore -q -- "${OUT_DIR_ARG}" ||
        die 4 "refusing: --out-dir ${OUT_DIR_ARG} is inside the repository and not gitignored"
      ;;
  esac
  NAME="$(new_attempt_id)"
  PARTIAL="${OUT_DIR}/${NAME}.partial"
  FINAL_DIR="${OUT_DIR}/${NAME}"
  TAR_FILE="${OUT_DIR}/${NAME}.tar"
  local p
  for p in "${PARTIAL}" "${FINAL_DIR}" "${TAR_FILE}" "${TAR_FILE}.sha256" "${OUT_DIR}/${NAME}.json"; do
    [ ! -e "${p}" ] || die 4 "refusing: ${p} exists"
  done
  STARTED_UTC="$(sc_utc_now)"
  jq -n --arg kind "scholarchain-evidence-archive-record" --arg name "${NAME}" --arg head "${HEAD_SHA}" \
    --arg build "${BUILD_SHA}" --argjson dirty "${TREE_DIRTY}" --arg started "${STARTED_UTC}" \
    --arg mode "$(mode_name)" \
    '{kind: $kind, version: 1, status: "started", name: $name, mode: $mode, head: $head,
      build_commit: $build, tree_dirty: $dirty, started_utc: $started}' |
    write_record_atomic "${OUT_DIR}/${NAME}.started.json" || die 5 "could not write the started record"
  STARTED_WRITTEN="true"
  mkdir -p -- "${PARTIAL}/repo" "${PARTIAL}/plans"
  info "archive ${NAME} in ${OUT_DIR} (mode $(mode_name); HEAD ${HEAD_SHA}; dirty ${TREE_DIRTY})"
}

mode_name() {
  if [ "${LOCAL_ONLY}" = "true" ]; then
    printf 'local-only'
  else
    printf 's3'
  fi
}

write_final_record() {
  local rc="$1" status rel_tar=""
  case "${rc}" in
    0) status="succeeded" ;;
    7) status="timed-out" ;;
    *) status="failed" ;;
  esac
  [ "${rc}" -eq 0 ] && rel_tar="${NAME}.tar"
  if jq -n --arg kind "scholarchain-evidence-archive-record" --arg name "${NAME}" --arg head "${HEAD_SHA}" \
    --arg build "${BUILD_SHA}" --argjson dirty "${TREE_DIRTY:-null}" --arg started "${STARTED_UTC}" \
    --arg ended "$(sc_utc_now)" --arg status "${status}" --argjson exit_code "${rc}" \
    --arg step "${CURRENT_STEP}" --arg signal "${SIGNAL_NAME}" --arg mode "$(mode_name)" \
    --arg tar "${rel_tar}" --arg tar_sha "${TAR_SHA}" --argjson files "${FILE_COUNT}" \
    --argjson reports "${REPORTS_COUNT}" --argjson warnings "${IDENTIFIER_WARNINGS}" \
    '{kind: $kind, version: 1, status: $status, exit_code: $exit_code,
      failed_step: (if $status == "succeeded" then null else $step end),
      signal: (if $signal == "" then null else $signal end),
      name: $name, mode: $mode, head: $head, build_commit: $build, tree_dirty: $dirty,
      started_utc: $started, ended_utc: $ended,
      tar: (if $tar == "" then null else $tar end), tar_sha256: (if $tar_sha == "" then null else $tar_sha end),
      files: $files, reports_objects: $reports, byte_copies_hold_identifiers: $warnings,
      partial_left: (if $status == "succeeded" then null else ($name + ".partial") end)}' \
    >"${TMP_DIR}/final.json" 2>/dev/null &&
    write_record_atomic "${OUT_DIR}/${NAME}.json" <"${TMP_DIR}/final.json"; then
    FINAL_WRITTEN="true"
    printf 'ARCHIVE-RECORD: %s/%s.json\n' "${OUT_DIR}" "${NAME}"
  else
    printf 'ERROR: %s could not write the record %s/%s.json\n' "${SCRIPT_NAME}" "${OUT_DIR}" "${NAME}" >&2
  fi
}

# ---- repo/ -----------------------------------------------------------------------------

copy_file() {
  local src="$1" dest="${PARTIAL}/repo/$1"
  mkdir -p -- "$(dirname -- "${dest}")"
  cp -p -- "${src}" "${dest}"
}

copy_repo_evidence() {
  step "repo-evidence"
  local root f n=0
  for root in releases evidence/checks evidence/packages evidence/runs experiments/raw \
    experiments/manifests reports; do
    [ -d "${root}" ] || continue
    while IFS= read -r -d '' f; do
      f="${f#./}"
      copy_file "${f}"
      n=$((n + 1))
    done < <(find "${root}" -type f -print0)
  done
  if [ -f .claude/.last-test-run ]; then
    copy_file .claude/.last-test-run
    n=$((n + 1))
  fi
  while IFS= read -r -d '' f; do
    f="${f#./}"
    copy_file "${f}"
    n=$((n + 1))
  done < <(find . -path ./.git -prune -o -path ./evidence -prune -o -type f -name '*.xml' \
    -path '*/target/*' -path '*/test-reports/*' -print0)
  info "repo/ files copied: ${n}"
}

# ---- plans/ ----------------------------------------------------------------------------

LOCAL_ACCOUNT=""

copy_plan_records() {
  step "plans"
  local rec base account plan_file present matches sum out n=0 line
  for rec in "${PLANS_DIR}"/*.record.json; do
    [ -f "${rec}" ] || continue
    base="${rec##*/}"
    base="${base%.record.json}"
    account="$(jq -r '.account_id // empty' "${rec}" 2>/dev/null)" || {
      warn "skipping unreadable plan record ${rec}"
      continue
    }
    [ -z "${LOCAL_ACCOUNT}" ] && [[ "${account}" =~ ^[0-9]{12}$ ]] && LOCAL_ACCOUNT="${account}"
    plan_file="$(jq -r '.plan_file // empty' "${rec}")"
    present="false"
    matches="false"
    if [ -n "${plan_file}" ] && [ -f "${TF_DIR}/${plan_file}" ]; then
      present="true"
      sum="$(sc_sha256 "${TF_DIR}/${plan_file}")"
      [ "${sum}" = "$(jq -r '.plan_sha256 // empty' "${rec}")" ] && matches="true"
    fi
    out="${PARTIAL}/plans/${base}.json"
    # Records since repair I-21 hold account_masked (and account_sha256), no
    # account_id; older records hold account_id, masked here.
    jq --arg src "${PLANS_DIR}/${base}.record.json" \
      --arg masked "$(jq -r '.account_masked // empty' "${rec}" 2>/dev/null || true)" \
      --arg legacy_masked "$(mask_account "${account}")" \
      --argjson present "${present}" --argjson matches "${matches}" \
      '{source_record: $src, kind: (.kind // null), plan_sha256: (.plan_sha256 // null),
        plan_commit: (.plan_commit // .commit // null), build_commit: (.build_commit // null),
        plan_id: (.plan_id // null), tree_clean: (.tree_clean // null), environment: (.environment // null),
        region: (.region // null), created_utc: (.created_utc // null),
        variable_overrides: (.variable_overrides // null),
        account: (if $masked != "" then $masked else $legacy_masked end),
        account_sha256: (.account_sha256 // null),
        plan_file: (.plan_file // null), plan_file_present: $present,
        plan_file_sha256_matches: $matches}' "${rec}" >"${out}" || die 5 "could not summarize ${rec}"
    if [ -f "${PLANS_DIR}/${base}.summary.txt" ]; then
      while IFS= read -r line || [ -n "${line}" ]; do
        if [ -n "${account}" ]; then
          line="${line//"${account}"/<account>}"
        fi
        printf '%s\n' "${line}"
      done <"${PLANS_DIR}/${base}.summary.txt" >"${PARTIAL}/plans/${base}.summary.txt"
    fi
    if [ -n "${account}" ] && grep -r -q -F -- "${account}" "${PARTIAL}/plans/${base}".* 2>/dev/null; then
      die 6 "plans/${base}: the account ID is still present after masking"
    fi
    n=$((n + 1))
  done
  info "plan records summarized: ${n}"
}

# ---- S3 mode ---------------------------------------------------------------------------

aws_errfile() { printf '%s/aws.err' "${TMP_DIR}"; }

aws_read() {
  local try=1 rc out
  while :; do
    rc=0
    out="$(with_timeout "${AWS_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 60 --no-cli-pager --output json "$@" 2>"$(aws_errfile)")" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      [ -n "${out}" ] || out='{}'
      printf '%s\n' "${out}"
      return 0
    fi
    if [ "${try}" -ge "${MAX_TRIES}" ]; then
      printf 'AWS-ERROR: %s\n' "$(mask_text "$(cat "$(aws_errfile)" 2>/dev/null)")" >&2
      [ "${rc}" -eq 124 ] && return 7
      return 5
    fi
    try=$((try + 1))
    sleep $((try * 5))
  done
}

read_outputs() {
  step "terraform-outputs"
  local rc=0 outputs vector ident
  outputs="$(with_timeout "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" output -json -no-color 2>/dev/null)" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform output failed (status ${rc})"
  DATA_BUCKET="$(jq -r '.data_bucket_name.value // empty' <<<"${outputs}")"
  vector="$(jq -r '.vector_bucket_name.value // empty' <<<"${outputs}")"
  [ -n "${DATA_BUCKET}" ] || die 3 "terraform output lacks data_bucket_name (nothing applied yet? use --local-only)"
  ident="$(aws_read sts get-caller-identity)" || die 5 "aws sts get-caller-identity failed"
  register_identifiers "$(jq -r '.Account // empty' <<<"${ident}")" "${DATA_BUCKET}" "${vector}" ||
    die 5 "could not read the account"
  info "account $(mask_account "${SC_ACCOUNT_ID:-}"), bucket <data-bucket>"
}

copy_reports() {
  step "s3-reports"
  local listing keys key size dest got rc try max_bytes
  listing="$(aws_read s3api list-objects-v2 --bucket "${DATA_BUCKET}" --prefix "reports/")" ||
    die 5 "aws s3api list-objects-v2 failed"
  keys="${TMP_DIR}/report-keys.tsv"
  jq -r '.Contents[]? | select(.Key | endswith("/") | not) | "\(.Size)\t\(.Key)"' <<<"${listing}" >"${keys}"
  REPORTS_BYTES="$(jq '[.Contents[]? | select(.Key | endswith("/") | not) | .Size] | add // 0' <<<"${listing}")"
  REPORTS_COUNT="$(wc -l <"${keys}" | tr -d ' ')"
  max_bytes=$((MAX_MB * 1048576))
  [ "${REPORTS_BYTES}" -le "${max_bytes}" ] ||
    die 4 "refusing: reports/ holds ${REPORTS_BYTES} bytes, above --max-mb ${MAX_MB}"
  while IFS="$(printf '\t')" read -r size key; do
    [ -n "${key}" ] || continue
    case "/${key}/" in
      */../* | */./* | *//*) die 4 "refusing: unsafe S3 key under reports/: ${key}" ;;
    esac
    case "${key}" in
      reports/?*) ;;
      *) die 4 "refusing: unexpected key outside reports/: ${key}" ;;
    esac
    dest="${PARTIAL}/${key}"
    mkdir -p -- "$(dirname -- "${dest}")"
    try=1
    while :; do
      rc=0
      with_timeout "${GET_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
        --cli-read-timeout 120 --no-cli-pager --output json s3api get-object --bucket "${DATA_BUCKET}" \
        --key "${key}" "${dest}" >/dev/null 2>"$(aws_errfile)" || rc=$?
      [ "${rc}" -eq 0 ] && break
      [ "${try}" -lt "${MAX_TRIES}" ] || {
        printf 'AWS-ERROR: %s\n' "$(mask_text "$(cat "$(aws_errfile)" 2>/dev/null)")" >&2
        [ "${rc}" -eq 124 ] && die 7 "get-object timed out"
        die 5 "aws s3api get-object failed for s3://{data-bucket}/${key}"
      }
      try=$((try + 1))
      sleep $((try * 5))
    done
    got="$(sc_bytes "${dest}")"
    [ "${got}" = "${size}" ] || die 6 "s3://{data-bucket}/${key}: ${got} bytes copied, ${size} listed"
  done <"${keys}"
  info "reports/ objects copied: ${REPORTS_COUNT} (${REPORTS_BYTES} bytes)"
}

copy_metrics() {
  step "metrics"
  local f base
  [ "${#METRICS_FILES[@]}" -gt 0 ] || return 0
  mkdir -p -- "${PARTIAL}/metrics"
  for f in "${METRICS_FILES[@]}"; do
    [ -f "${f}" ] || die 3 "metrics file not found: ${f}"
    base="${f##*/}"
    [ ! -e "${PARTIAL}/metrics/${base}" ] || die 4 "refusing: two metrics files share the name ${base}"
    cp -p -- "${f}" "${PARTIAL}/metrics/${base}"
    check_no_identifiers "${PARTIAL}/metrics/${base}" || die 6 "metrics file ${f} holds an identifier"
  done
  info "metrics files copied: ${#METRICS_FILES[@]}"
}

# ---- manifest, rename, tar -------------------------------------------------------------

write_manifest() {
  step "manifest"
  local list="${TMP_DIR}/files.tsv" f rel
  : >"${list}"
  while IFS= read -r -d '' f; do
    rel="${f#"${PARTIAL}"/}"
    printf '%s\t%s\t%s\n' "${rel}" "$(sc_bytes "${f}")" "$(sc_sha256 "${f}")" >>"${list}"
  done < <(find "${PARTIAL}" -type f -print0)
  FILE_COUNT="$(wc -l <"${list}" | tr -d ' ')"
  if [ "${LOCAL_ONLY}" = "true" ] && [ -n "${LOCAL_ACCOUNT}" ]; then
    register_identifiers "${LOCAL_ACCOUNT}" "" "" || true
  fi
  if ! check_no_identifiers "${PARTIAL}/repo"; then
    IDENTIFIER_WARNINGS="true"
    warn "byte copies under repo/ hold an identifier (listed above); they are kept unchanged; never commit the archive"
  fi
  if [ -d "${PARTIAL}/reports" ] && ! check_no_identifiers "${PARTIAL}/reports"; then
    IDENTIFIER_WARNINGS="true"
    warn "byte copies under reports/ hold an identifier (listed above); kept unchanged"
  fi
  check_no_identifiers "${PARTIAL}/plans" || die 6 "plans/ holds an identifier"
  jq -R -s --arg name "${NAME}" --arg head "${HEAD_SHA}" --arg build "${BUILD_SHA}" \
    --argjson dirty "${TREE_DIRTY}" --arg mode "$(mode_name)" --arg created "$(sc_utc_now)" \
    --argjson reports "${REPORTS_COUNT}" --argjson reports_bytes "${REPORTS_BYTES}" \
    --argjson metrics "${#METRICS_FILES[@]}" --argjson warnings "${IDENTIFIER_WARNINGS}" \
    --argjson local_only "${LOCAL_ONLY}" \
    '{kind: "scholarchain-evidence-archive-manifest", version: 1, name: $name, mode: $mode,
      commit: $head, build_commit: $build, tree_dirty: $dirty, created_utc: $created,
      reports: (if $local_only then {included: false, reason: "--local-only: no AWS call"}
                else {included: true, objects: $reports, bytes: $reports_bytes,
                      location: "reports/ (S3 key = path below the root folder)"} end),
      metrics: (if $local_only then {included: false, reason: "--local-only: no AWS call"}
                else {included: ($metrics > 0), files: $metrics} end),
      byte_copies_hold_identifiers: $warnings,
      files: (split("\n") | map(select(length > 0) | split("\t")
               | {path: .[0], bytes: (.[1] | tonumber), sha256: .[2]}))}' "${list}" |
    write_record_atomic "${PARTIAL}/MANIFEST.json" || die 6 "could not write MANIFEST.json"
  info "MANIFEST.json: ${FILE_COUNT} files"
}

finish_archive() {
  step "rename"
  [ ! -e "${FINAL_DIR}" ] || die 4 "refusing: ${FINAL_DIR} exists"
  mv -- "${PARTIAL}" "${FINAL_DIR}" || die 5 "could not rename ${PARTIAL}"
  step "tar"
  [ ! -e "${TAR_FILE}" ] || die 4 "refusing: ${TAR_FILE} exists"
  local rc=0
  COPYFILE_DISABLE=1 with_timeout "${TAR_TIMEOUT}" tar -cf "${TAR_FILE}" -C "${OUT_DIR}" "${NAME}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "tar timed out"
  [ "${rc}" -eq 0 ] || die 5 "tar failed (status ${rc})"
  TAR_SHA="$(sc_sha256 "${TAR_FILE}")"
  (
    set -o noclobber
    printf '%s  %s\n' "${TAR_SHA}" "${NAME}.tar" >"${TAR_FILE}.sha256"
  ) || die 5 "could not write ${TAR_FILE}.sha256"
  printf 'ARCHIVE-DIR: %s\n' "${FINAL_DIR}"
  printf 'ARCHIVE-TAR: %s\n' "${TAR_FILE}"
  printf 'ARCHIVE-SHA256: %s\n' "${TAR_SHA}"
}

main() {
  parse_args "$@"
  enter_repo_root
  check_prerequisites
  prepare
  if [ "${LOCAL_ONLY}" = "false" ]; then
    read_outputs
  fi
  copy_repo_evidence
  copy_plan_records
  if [ "${LOCAL_ONLY}" = "false" ]; then
    copy_reports
    copy_metrics
  fi
  write_manifest
  finish_archive
  step "done"
}

main "$@"
