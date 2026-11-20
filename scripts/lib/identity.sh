# shellcheck shell=bash
# scripts/lib/identity.sh - shared identity, masking, record and archive-key
# rules of the ScholarChain scripts. SOURCED, never executed. Owner:
# infra-engineer (Phase 13, decision 9.0).
#
# Loading this file defines variables and functions only: it sets no shell
# option, installs no trap, runs no command and prints nothing. Every function
# returns an explicit status (0 ok; 2 usage; 3 missing prerequisite or unknown
# value; 4 refused by a safety rule; 5 a command failed; 6 a check failed) and
# prints its reasons on standard error; the calling script decides how to stop.
# The code is bash 3.2 compatible (macOS /bin/bash): no associative arrays, no
# mapfile.
#
# The two identity rules (Phase 13 work order, "Two identity rules"):
#   Build commit (artifacts): the newest commit that changed a build input,
#     `git log -1 --format=%H -- <SC_BUILD_INPUT_PATHS>`. Jar stamps, image
#     tags, artifact keys and the code version of every job use it. The same
#     list is in project/BuildIdentity.scala (InputPaths); scripts/package.sh
#     compares the jars' recorded buildCommit with build_commit, so the two
#     lists cannot silently differ.
#   Plan validity (plans): a plan made by plan.sh at PLAN_COMMIT is usable
#     only if the tree is clean, PLAN_COMMIT is HEAD or an ancestor of HEAD,
#     and nothing in SC_PLAN_INPUT_PATHS (infra/terraform/ plus every
#     repository file outside it that Terraform reads) changed since
#     PLAN_COMMIT (plan_usable). Terraform itself still refuses a stale saved
#     plan.
#
# Status: static: shellcheck clean; runtime: untested; human execution required.

if [ -n "${SCHOLARCHAIN_IDENTITY_SH_LOADED:-}" ]; then
  return 0
fi
SCHOLARCHAIN_IDENTITY_SH_LOADED=1

# Build inputs, relative to the repository root (= project/BuildIdentity.scala InputPaths).
SC_BUILD_INPUT_PATHS=(build.sbt project modules prompts docker config .scalafmt.conf .jvmopts .sbtopts)

# Plan inputs, relative to the repository root: what plan_usable compares
# between PLAN_COMMIT and HEAD. infra/terraform covers every file inside it
# (modules, environments, modules/emr/files/bootstrap.sh). Every other entry is
# a repository file OUTSIDE infra/terraform that Terraform reads with file(),
# templatefile(), filebase64() or a similar function; keep this list complete
# when such a read is added (Phase 13 finding I-23):
#   modules/storage/src/main/resources/s3vectors/index-spec-v1.json
#     read by infra/terraform/environments/course/main.tf (module "vectors",
#     index_spec = jsondecode(file("${path.root}/../../../../modules/storage/...")))
SC_PLAN_INPUT_PATHS=(
  infra/terraform
  modules/storage/src/main/resources/s3vectors/index-spec-v1.json
)

# Identifiers known to the running script (register_identifiers). Held only in
# shell variables; masked in what scripts print; never persisted.
SC_ACCOUNT_ID=""
SC_DATA_BUCKET=""
SC_VECTOR_BUCKET=""

# Phase 15 (G-29, decision 9.5): further identifiers a script learns at run
# time (the API host, queue URLs, endpoint hosts), registered with
# register_extra_identifier KIND VALUE. Two parallel indexed arrays and a
# count (bash 3.2: no associative arrays; the count avoids expanding an empty
# array under set -u). Masked before the account and buckets, so a URL that
# contains the account ID is replaced as a whole.
SC_EXTRA_KINDS=()
SC_EXTRA_VALUES=()
SC_EXTRA_COUNT=0

# Name patterns (ERE) of the two buckets, for output printed BEFORE their real
# names are known (the first apply creates them; Phase 13 finding I-21). Set by
# register_bucket_patterns; empty means "no pattern masking".
SC_DATA_BUCKET_PATTERN=""
SC_VECTOR_BUCKET_PATTERN=""
SC_MASK_OUT=""

sc_lib_err() {
  printf 'REASON: %s\n' "$*" >&2
}

# ---- identity -------------------------------------------------------------------

# build_commit: prints the build commit (40 lowercase hex). Status 3 if git
# fails or no commit touched a build input.
build_commit() {
  local c
  c="$(git log -1 --format=%H -- "${SC_BUILD_INPUT_PATHS[@]}" 2>/dev/null)" || {
    sc_lib_err "git log failed; is this a git checkout with history?"
    return 3
  }
  if [[ ! "${c}" =~ ^[0-9a-f]{40}$ ]]; then
    sc_lib_err "no commit changed a build input (${SC_BUILD_INPUT_PATHS[*]})"
    return 3
  fi
  printf '%s\n' "${c}"
}

# head_commit: prints HEAD (40 hex). Status 3 without a commit.
head_commit() {
  local c
  c="$(git rev-parse --verify HEAD 2>/dev/null)" || {
    sc_lib_err "not a git repository with a commit"
    return 3
  }
  printf '%s\n' "${c}"
}

# tree_dirty_flag: prints "true" when `git status --porcelain
# --untracked-files=normal` prints anything (tracked change or untracked file
# outside .gitignore), else "false". Status 5 if git status fails.
tree_dirty_flag() {
  local s
  s="$(git status --porcelain --untracked-files=normal 2>/dev/null)" || {
    sc_lib_err "git status failed"
    return 5
  }
  if [ -n "${s}" ]; then
    printf 'true\n'
  else
    printf 'false\n'
  fi
}

# require_clean_tree: status 0 on a clean tree; 4 (the changed paths listed on
# standard error) on a dirty one; 5 if git status fails.
require_clean_tree() {
  local s
  s="$(git status --porcelain --untracked-files=normal 2>/dev/null)" || {
    sc_lib_err "git status failed"
    return 5
  }
  if [ -n "${s}" ]; then
    git status --short --untracked-files=normal >&2 || true
    sc_lib_err "the working tree is dirty (tracked changes or untracked files outside .gitignore)"
    return 4
  fi
  return 0
}

# plan_usable PLAN_COMMIT: the plan-validity rule. Status 0 when the tree is
# clean, PLAN_COMMIT is HEAD or an ancestor of HEAD, and
# `git diff --quiet PLAN_COMMIT HEAD -- <SC_PLAN_INPUT_PATHS>` reports no
# change; 4 (reason on standard error, naming the changed paths) otherwise; 5
# if git fails.
plan_usable() {
  local pc="${1:-}" rc=0
  if [[ ! "${pc}" =~ ^[0-9a-f]{40}$ ]]; then
    sc_lib_err "plan commit '${pc}' is not 40 lowercase hex"
    return 4
  fi
  require_clean_tree || return "$?"
  if ! git cat-file -e "${pc}^{commit}" 2>/dev/null; then
    sc_lib_err "plan commit ${pc} is not a commit in this repository"
    return 4
  fi
  git merge-base --is-ancestor "${pc}" HEAD 2>/dev/null || rc=$?
  if [ "${rc}" -eq 1 ]; then
    sc_lib_err "plan commit ${pc} is neither HEAD nor an ancestor of HEAD"
    return 4
  elif [ "${rc}" -ne 0 ]; then
    sc_lib_err "git merge-base failed (status ${rc})"
    return 5
  fi
  rc=0
  git diff --quiet "${pc}" HEAD -- "${SC_PLAN_INPUT_PATHS[@]}" 2>/dev/null || rc=$?
  if [ "${rc}" -eq 1 ]; then
    git diff --name-only "${pc}" HEAD -- "${SC_PLAN_INPUT_PATHS[@]}" >&2 2>/dev/null || true
    sc_lib_err "a plan input (${SC_PLAN_INPUT_PATHS[*]}) changed between plan commit ${pc} and HEAD; make a new plan"
    return 4
  elif [ "${rc}" -ne 0 ]; then
    sc_lib_err "git diff failed (status ${rc})"
    return 5
  fi
  return 0
}

# find_plan_record PLANS_DIR KIND SHA256: prints the path of the ONE file
# PLANS_DIR/*.record.json whose .kind is KIND and whose .plan_sha256 is
# SHA256 (Phase 13 decision 9.4: the plan is found by its checksum, not by
# HEAD). Status 2 on a malformed SHA256; 4 when there is none or more than one.
# Reads local files only (no cloud call).
find_plan_record() {
  local dir="${1:-}" kind="${2:-}" sha="${3:-}" f found="" n=0 got
  if [[ ! "${sha}" =~ ^[0-9a-f]{64}$ ]]; then
    sc_lib_err "plan sha256 must be 64 lowercase hex characters"
    return 2
  fi
  for f in "${dir}"/*.record.json; do
    [ -f "${f}" ] || continue
    got="$(jq -r --arg k "${kind}" 'select(.kind == $k) | .plan_sha256 // empty' "${f}" 2>/dev/null)" || got=""
    if [ "${got}" = "${sha}" ]; then
      found="${f}"
      n=$((n + 1))
    fi
  done
  if [ "${n}" -eq 0 ]; then
    sc_lib_err "no ${kind} under ${dir}/ records the plan sha256 ${sha}"
    return 4
  fi
  if [ "${n}" -gt 1 ]; then
    sc_lib_err "${n} records under ${dir}/ name the plan sha256 ${sha}; expected exactly one"
    return 4
  fi
  printf '%s\n' "${found}"
}

# require_plan_approval SHA256: status 0 when a whole line
# "APPROVED-PLAN-SHA256: SHA256" (exactly that, nothing else on the line) is
# in a file named human-gate.md or approvals.md under docs/agent-runs/ as
# committed at HEAD (git grep on HEAD, so only tracked, committed text
# counts; agent reports, summaries and prompts never count). It proves that
# the approval line was committed, not who committed it. Status 2 on a
# malformed SHA256; 4 when no such line exists (git errors also give 4: fail
# closed).
require_plan_approval() {
  local sha="${1:-}" hits
  if [[ ! "${sha}" =~ ^[0-9a-f]{64}$ ]]; then
    sc_lib_err "plan sha256 must be 64 lowercase hex characters"
    return 2
  fi
  hits="$(git grep -h -E "^APPROVED-PLAN-SHA256: ${sha}\$" HEAD -- \
    ':(glob)docs/agent-runs/**/human-gate.md' ':(glob)docs/agent-runs/**/approvals.md' 2>/dev/null || true)"
  if [ -z "${hits}" ]; then
    sc_lib_err "no line 'APPROVED-PLAN-SHA256: ${sha}' in a tracked human-gate.md or approvals.md under docs/agent-runs/ at HEAD"
    return 4
  fi
  return 0
}

# ---- identifiers and masking ----------------------------------------------------

# register_identifiers ACCOUNT_ID DATA_BUCKET VECTOR_BUCKET: remembers the
# real values for mask_text, logical_s3, resolve_s3 and check_no_identifiers.
# Any argument may be empty (unknown). Status 2 on a malformed account ID.
register_identifiers() {
  local account="${1:-}" data="${2:-}" vector="${3:-}"
  if [ -n "${account}" ] && [[ ! "${account}" =~ ^[0-9]{12}$ ]]; then
    sc_lib_err "account ID is not 12 digits"
    return 2
  fi
  SC_ACCOUNT_ID="${account}"
  SC_DATA_BUCKET="${data}"
  SC_VECTOR_BUCKET="${vector}"
  return 0
}

# register_extra_identifier KIND VALUE (Phase 15, G-29): remembers VALUE (an
# API host, a queue URL, an endpoint host) for mask_text, mask_stream and
# check_no_identifiers, which print <KIND> or name KIND, never the value.
# KIND matches [a-z0-9][a-z0-9-]{0,39}. An empty VALUE is skipped (status 0:
# the output does not exist yet); a VALUE shorter than 8 characters is refused
# (status 2: it would mask ordinary text); a VALUE already registered is
# skipped. At most 32 values (status 2 beyond).
register_extra_identifier() {
  local kind="${1:-}" value="${2:-}" i=0
  if [[ ! "${kind}" =~ ^[a-z0-9][a-z0-9-]{0,39}$ ]]; then
    sc_lib_err "identifier kind must match [a-z0-9][a-z0-9-]{0,39}"
    return 2
  fi
  [ -n "${value}" ] || return 0
  if [ "${#value}" -lt 8 ]; then
    sc_lib_err "the ${kind} value is shorter than 8 characters; not registered"
    return 2
  fi
  while [ "${i}" -lt "${SC_EXTRA_COUNT}" ]; do
    [ "${SC_EXTRA_VALUES[${i}]}" = "${value}" ] && return 0
    i=$((i + 1))
  done
  if [ "${SC_EXTRA_COUNT}" -ge 32 ]; then
    sc_lib_err "too many extra identifiers (at most 32)"
    return 2
  fi
  SC_EXTRA_KINDS[SC_EXTRA_COUNT]="${kind}"
  SC_EXTRA_VALUES[SC_EXTRA_COUNT]="${value}"
  SC_EXTRA_COUNT=$((SC_EXTRA_COUNT + 1))
  return 0
}

# register_output_identifiers OUTPUTS_JSON (Phase 15, G-29 and decision
# 9.5): registers, from the JSON of `terraform output -json`, the endpoint
# values that must never be printed or recorded: api-host (the host of
# api_url), job-dlq-url and embedding-dlq-url (dlq_urls), job-queue-url
# (job_queue_url), embedding-queue-url (embedding_queue_url), alb-host
# (alb_dns_name) and neptune-host (neptune_endpoint). The dead-letter queue
# URLs are registered before the queue URLs, because each queue URL is a
# prefix of its DLQ URL (longest first, so the label is right). A missing
# output is skipped (status 0); prints nothing. Status 3 without jq; 2 when a
# value is malformed (register_extra_identifier).
register_output_identifiers() {
  local o="${1:-}" url host
  command -v jq >/dev/null 2>&1 || {
    sc_lib_err "jq not found"
    return 3
  }
  url="$(jq -r '.api_url.value // empty' <<<"${o}" 2>/dev/null)" || url=""
  host=""
  case "${url}" in
    https://*)
      host="${url#https://}"
      host="${host%%/*}"
      ;;
  esac
  register_extra_identifier api-host "${host}" || return "$?"
  register_extra_identifier job-dlq-url "$(jq -r '.dlq_urls.value.jobs // empty' <<<"${o}" 2>/dev/null || true)" || return "$?"
  register_extra_identifier embedding-dlq-url "$(jq -r '.dlq_urls.value.embedding // empty' <<<"${o}" 2>/dev/null || true)" || return "$?"
  register_extra_identifier job-queue-url "$(jq -r '.job_queue_url.value // empty' <<<"${o}" 2>/dev/null || true)" || return "$?"
  register_extra_identifier embedding-queue-url "$(jq -r '.embedding_queue_url.value // empty' <<<"${o}" 2>/dev/null || true)" || return "$?"
  register_extra_identifier alb-host "$(jq -r '.alb_dns_name.value // empty' <<<"${o}" 2>/dev/null || true)" || return "$?"
  register_extra_identifier neptune-host "$(jq -r '.neptune_endpoint.value // empty' <<<"${o}" 2>/dev/null || true)" || return "$?"
  return 0
}

# sc_mask_extras TEXT: sets SC_MASK_OUT to TEXT with every registered extra
# identifier replaced by <KIND> (no subshell).
sc_mask_extras() {
  local i=0 v
  SC_MASK_OUT="${1:-}"
  while [ "${i}" -lt "${SC_EXTRA_COUNT}" ]; do
    v="${SC_EXTRA_VALUES[${i}]}"
    SC_MASK_OUT="${SC_MASK_OUT//"${v}"/<${SC_EXTRA_KINDS[${i}]}>}"
    i=$((i + 1))
  done
  return 0
}

# register_bucket_patterns NAME_PREFIX: masks, in mask_text and mask_stream,
# any text shaped like a bucket name of this environment even when the real
# name is not known yet: NAME_PREFIX-data-<14+ digits><hex> (Terraform
# bucket_prefix, infra/terraform/modules/data-lake/main.tf) and
# NAME_PREFIX-vectors-<8 hex> (modules/vectors/main.tf). NAME_PREFIX is
# project-environment (environments/course/main.tf locals). Status 2 on a
# malformed prefix.
register_bucket_patterns() {
  local p="${1:-}"
  if [[ ! "${p}" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
    sc_lib_err "bucket name prefix must be lowercase letters, digits and '-'"
    return 2
  fi
  SC_DATA_BUCKET_PATTERN="${p}-data-[0-9]{14}[0-9a-f]*"
  SC_VECTOR_BUCKET_PATTERN="${p}-vectors-[0-9a-f]{8}"
  return 0
}

# sc_mask_patterns TEXT: sets SC_MASK_OUT to TEXT with every match of the
# registered bucket patterns replaced by <data-bucket> or <vector-bucket>
# (no subshell, so callers keep trailing newlines). Bounded loop.
sc_mask_patterns() {
  local m n
  SC_MASK_OUT="${1:-}"
  if [ -n "${SC_DATA_BUCKET_PATTERN}" ]; then
    n=0
    while [ "${n}" -lt 64 ] && [[ "${SC_MASK_OUT}" =~ ${SC_DATA_BUCKET_PATTERN} ]]; do
      m="${BASH_REMATCH[0]}"
      SC_MASK_OUT="${SC_MASK_OUT//"${m}"/<data-bucket>}"
      n=$((n + 1))
    done
  fi
  if [ -n "${SC_VECTOR_BUCKET_PATTERN}" ]; then
    n=0
    while [ "${n}" -lt 64 ] && [[ "${SC_MASK_OUT}" =~ ${SC_VECTOR_BUCKET_PATTERN} ]]; do
      m="${BASH_REMATCH[0]}"
      SC_MASK_OUT="${SC_MASK_OUT//"${m}"/<vector-bucket>}"
      n=$((n + 1))
    done
  fi
  return 0
}

# account_fingerprint ACCOUNT_ID: prints the lowercase sha256 hex of
# "scholarchain-account-v1:ACCOUNT_ID" (domain-separated, so it is not the
# plain sha256 that Terraform's account_suffix starts with). Plan records keep
# this fingerprint, never the account ID, and deploy.sh and cleanup.sh compare
# fingerprints (I-21). It hides the ID from casual reading only: a 12-digit
# space can be searched offline. Status 2 on a malformed ID; 3 without
# shasum or sha256sum.
account_fingerprint() {
  local a="${1:-}"
  if [[ ! "${a}" =~ ^[0-9]{12}$ ]]; then
    sc_lib_err "account ID is not 12 digits"
    return 2
  fi
  if command -v shasum >/dev/null 2>&1; then
    printf 'scholarchain-account-v1:%s' "${a}" | shasum -a 256 | cut -d ' ' -f 1
  elif command -v sha256sum >/dev/null 2>&1; then
    printf 'scholarchain-account-v1:%s' "${a}" | sha256sum | cut -d ' ' -f 1
  else
    sc_lib_err "neither shasum nor sha256sum found"
    return 3
  fi
}

# mask_account ACCOUNT_ID: prints ********NNNN (last four digits) or <unknown>.
mask_account() {
  local a="${1:-}"
  if [[ "${a}" =~ ^[0-9]{12}$ ]]; then
    printf '********%s' "${a: -4}"
  else
    printf '<unknown>'
  fi
}

# mask_text TEXT: prints TEXT with the registered account ID and bucket names
# replaced by <account>, <data-bucket> and <vector-bucket>, then the
# registered bucket name patterns (register_bucket_patterns).
mask_text() {
  local s="${1:-}"
  sc_mask_extras "${s}"
  s="${SC_MASK_OUT}"
  if [ -n "${SC_ACCOUNT_ID}" ]; then
    s="${s//"${SC_ACCOUNT_ID}"/<account>}"
  fi
  if [ -n "${SC_DATA_BUCKET}" ]; then
    s="${s//"${SC_DATA_BUCKET}"/<data-bucket>}"
  fi
  if [ -n "${SC_VECTOR_BUCKET}" ]; then
    s="${s//"${SC_VECTOR_BUCKET}"/<vector-bucket>}"
  fi
  sc_mask_patterns "${s}"
  printf '%s' "${SC_MASK_OUT}"
}

# mask_stream: copies standard input to standard output line by line, masked
# like mask_text (for logs of nested commands and raw Terraform output).
mask_stream() {
  local line
  while IFS= read -r line || [ -n "${line}" ]; do
    sc_mask_extras "${line}"
    line="${SC_MASK_OUT}"
    if [ -n "${SC_ACCOUNT_ID}" ]; then
      line="${line//"${SC_ACCOUNT_ID}"/<account>}"
    fi
    if [ -n "${SC_DATA_BUCKET}" ]; then
      line="${line//"${SC_DATA_BUCKET}"/<data-bucket>}"
    fi
    if [ -n "${SC_VECTOR_BUCKET}" ]; then
      line="${line//"${SC_VECTOR_BUCKET}"/<vector-bucket>}"
    fi
    sc_mask_patterns "${line}"
    printf '%s\n' "${SC_MASK_OUT}"
  done
}

# logical_s3 URI: prints s3://{data-bucket}/KEY for s3://REAL-DATA-BUCKET/KEY
# and s3://{vector-bucket}/KEY for the vector bucket; any other text unchanged.
logical_s3() {
  local u="${1:-}"
  if [ -n "${SC_DATA_BUCKET}" ]; then
    case "${u}" in
      "s3://${SC_DATA_BUCKET}") u="s3://{data-bucket}" ;;
      "s3://${SC_DATA_BUCKET}/"*) u="s3://{data-bucket}/${u#"s3://${SC_DATA_BUCKET}/"}" ;;
    esac
  fi
  if [ -n "${SC_VECTOR_BUCKET}" ]; then
    case "${u}" in
      "s3://${SC_VECTOR_BUCKET}") u="s3://{vector-bucket}" ;;
      "s3://${SC_VECTOR_BUCKET}/"*) u="s3://{vector-bucket}/${u#"s3://${SC_VECTOR_BUCKET}/"}" ;;
    esac
  fi
  printf '%s\n' "${u}"
}

# resolve_s3 URI: the inverse of logical_s3 (Phase 13 finding C-03): prints
# s3://REAL-BUCKET/KEY for s3://{data-bucket}/KEY or s3://{vector-bucket}/KEY;
# any text without a placeholder unchanged. Status 3 when the placeholder's
# bucket is not registered; 4 for any other {placeholder} in the bucket part.
resolve_s3() {
  local u="${1:-}"
  case "${u}" in
    "s3://{data-bucket}" | "s3://{data-bucket}/"*)
      if [ -z "${SC_DATA_BUCKET}" ]; then
        sc_lib_err "the data-lake bucket is not known (terraform output data_bucket_name)"
        return 3
      fi
      u="s3://${SC_DATA_BUCKET}${u#"s3://{data-bucket}"}"
      ;;
    "s3://{vector-bucket}" | "s3://{vector-bucket}/"*)
      if [ -z "${SC_VECTOR_BUCKET}" ]; then
        sc_lib_err "the vector bucket is not known (terraform output vector_bucket_name)"
        return 3
      fi
      u="s3://${SC_VECTOR_BUCKET}${u#"s3://{vector-bucket}"}"
      ;;
    "s3://{"*)
      sc_lib_err "unknown bucket placeholder in '${u%%\}*}}'; only {data-bucket} and {vector-bucket} exist"
      return 4
      ;;
  esac
  printf '%s\n' "${u}"
}

# check_no_identifiers PATH...: status 6 when any file (directories are read
# recursively) contains the registered account ID, a registered bucket name
# or (Phase 15, G-29) a registered extra identifier (grep -F); the file and
# the kind of identifier are named, never the value. Status 0 when none is
# found or no identifier is registered.
check_no_identifiers() {
  local found=0 p p_hit hits kind value i
  for p in "$@"; do
    i=0
    while [ "${i}" -lt "${SC_EXTRA_COUNT}" ]; do
      value="${SC_EXTRA_VALUES[${i}]}"
      kind="${SC_EXTRA_KINDS[${i}]}"
      i=$((i + 1))
      hits="$(grep -r -l -F -- "${value}" "${p}" 2>/dev/null || true)"
      if [ -n "${hits}" ]; then
        found=1
        while IFS= read -r p_hit; do
          [ -n "${p_hit}" ] && printf 'IDENTIFIER-CHECK: %s holds the %s\n' "${p_hit}" "${kind}" >&2
        done <<<"${hits}"
      fi
    done
    for kind in account-id data-bucket vector-bucket; do
      case "${kind}" in
        account-id) value="${SC_ACCOUNT_ID}" ;;
        data-bucket) value="${SC_DATA_BUCKET}" ;;
        *) value="${SC_VECTOR_BUCKET}" ;;
      esac
      [ -n "${value}" ] || continue
      hits="$(grep -r -l -F -- "${value}" "${p}" 2>/dev/null || true)"
      if [ -n "${hits}" ]; then
        found=1
        while IFS= read -r p_hit; do
          [ -n "${p_hit}" ] && printf 'IDENTIFIER-CHECK: %s holds the %s\n' "${p_hit}" "${kind}" >&2
        done <<<"${hits}"
      fi
    done
  done
  if [ "${found}" -ne 0 ]; then
    sc_lib_err "a record or evidence file holds a real identifier (listed above)"
    return 6
  fi
  return 0
}

# ---- attempts and records -------------------------------------------------------

# new_attempt_id: prints YYYYMMDDTHHMMSSZ-<12-hex short HEAD>-<4 hex> (UTC to
# the second, HEAD, 16 random bits from $RANDOM), so two runs in the same
# second differ.
new_attempt_id() {
  local ts short r
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  short="$(git rev-parse --short=12 HEAD 2>/dev/null)" || short="nohead"
  r=$(((RANDOM * 2 + RANDOM % 2) % 65536))
  printf '%s-%s-%04x\n' "${ts}" "${short}" "${r}"
}

# write_record_atomic FILE: reads one JSON record from standard input, writes
# FILE.partial, checks it (valid JSON with jq; check_no_identifiers), then
# moves it to FILE without ever replacing an existing FILE (hard link, which
# fails if FILE exists, then the .partial name is dropped). Status 4 if FILE
# or FILE.partial exists; 5 on a write failure; 6 when a check fails (the
# .partial file stays for inspection and nothing reads it as a record).
# A run's "started" record and its final record therefore use two names:
# NAME.started.json and NAME.json (a started record without a final one is an
# incomplete attempt, never a success).
write_record_atomic() {
  local file="${1:-}" partial
  if [ -z "${file}" ]; then
    sc_lib_err "write_record_atomic needs a file name"
    return 2
  fi
  partial="${file}.partial"
  if [ -e "${file}" ] || [ -L "${file}" ]; then
    sc_lib_err "record ${file} exists; records are never overwritten"
    return 4
  fi
  if [ -e "${partial}" ] || [ -L "${partial}" ]; then
    sc_lib_err "a partial record ${partial} exists; it is kept for inspection"
    return 4
  fi
  mkdir -p -- "$(dirname -- "${file}")" || return 5
  (
    set -o noclobber
    cat >"${partial}"
  ) || {
    sc_lib_err "could not write ${partial}"
    return 5
  }
  if ! jq empty "${partial}" >/dev/null 2>&1; then
    sc_lib_err "record ${partial} is not valid JSON"
    return 6
  fi
  check_no_identifiers "${partial}" || return 6
  if ! ln -- "${partial}" "${file}" 2>/dev/null; then
    sc_lib_err "could not create ${file} (it may exist now)"
    return 4
  fi
  rm -f -- "${partial}" || return 5
  return 0
}

# ---- small portable helpers -----------------------------------------------------

# sc_sha256 FILE: prints the lowercase sha256 hex of FILE.
sc_sha256() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "$1" | cut -d ' ' -f 1
  else
    sha256sum -- "$1" | cut -d ' ' -f 1
  fi
}

# sc_bytes FILE: prints the size of FILE in bytes.
sc_bytes() {
  wc -c <"$1" | tr -d ' '
}

# sc_utc_now: prints the UTC time, ISO 8601 to the second.
sc_utc_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

# sc_epoch_to_utc EPOCH: prints EPOCH as ISO 8601 UTC (GNU date or BSD date).
sc_epoch_to_utc() {
  date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null ||
    date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ
}

# ---- archive key rule (shared by archive-evidence.sh, cleanup.sh, check.sh) -----
#
# Moved unchanged from cleanup.sh's normalize_archive_list (Phase 12, repair
# I-13): a leading "./" is removed; when every entry lies under one single root
# folder (and that folder is not reports/ itself), that folder is removed;
# directory entries are dropped. Compare keys as whole lines (grep -F -x).

# strip_dot_slash ENTRY: prints ENTRY without any leading "./".
strip_dot_slash() {
  local e="$1"
  while [ "${e#./}" != "${e}" ]; do
    e="${e#./}"
  done
  printf '%s' "${e}"
}

# archive_root LISTING_FILE: prints the single common root folder of a tar
# listing (tar -tf output), or nothing when there is none or it is reports.
archive_root() {
  local listing="$1" entry first root="" single=1
  while IFS= read -r entry || [ -n "${entry}" ]; do
    entry="$(strip_dot_slash "${entry}")"
    if [ -z "${entry}" ] || [ "${entry}" = "." ]; then
      continue
    fi
    first="${entry%%/*}"
    if [ "${first}" = "${entry}" ]; then
      single=0 # a file at the top level: no common root folder
      break
    fi
    if [ -z "${root}" ]; then
      root="${first}"
    elif [ "${first}" != "${root}" ]; then
      single=0
      break
    fi
  done <"${listing}"
  if [ -z "${root}" ] || [ "${root}" = "reports" ]; then
    single=0
  fi
  if [ "${single}" -eq 1 ]; then
    printf '%s' "${root}"
  fi
}

# archive_keys LISTING_FILE: prints one key per line: every file entry of the
# tar listing with "./" and the common root folder (archive_root) removed;
# directory entries (ending in "/") dropped.
archive_keys() {
  local listing="$1" entry root
  root="$(archive_root "${listing}")"
  while IFS= read -r entry || [ -n "${entry}" ]; do
    entry="$(strip_dot_slash "${entry}")"
    if [ -n "${root}" ]; then
      entry="${entry#"${root}"/}"
    fi
    if [ -z "${entry}" ] || [ "${entry}" = "." ] || [ "${entry%/}" != "${entry}" ]; then
      continue
    fi
    printf '%s\n' "${entry}"
  done <"${listing}"
}
