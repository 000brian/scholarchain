#!/usr/bin/env bash
# scripts/cleanup.sh - list, then (only after an explicit typed confirmation)
# remove the ScholarChain course environment in a safe order. Explicit human
# invocation only; never run by an agent. Owner: infra-engineer (author).
#
# Status (Phase 13): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="cleanup.sh"
readonly ENVIRONMENT="course"
readonly EXPECTED_REGION="us-east-1"
readonly TF_DIR="infra/terraform/environments/course"
readonly PLANS_SUBDIR="plans"
readonly INIT_TIMEOUT=600
readonly PLAN_TIMEOUT=1800
readonly SHOW_TIMEOUT=300
readonly OUTPUT_TIMEOUT=120
readonly APPLY_TIMEOUT=5400
readonly AWS_TIMEOUT=120
readonly EMR_WAIT_TIMEOUT=2400
readonly VPCE_WAIT_TIMEOUT=900
readonly CONFIRM_TIMEOUT=300
readonly MAX_DELETE_ROUNDS=20000
readonly ROLES="query evidence graph worker"

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
ACCOUNT_ID=""
DATA_BUCKET=""
VECTOR_BUCKET=""

usage() {
  cat <<'EOF'
Usage: scripts/cleanup.sh --evidence-archive PATH --list
       scripts/cleanup.sh --evidence-archive PATH --remove --plan-sha256 HEX64
       scripts/cleanup.sh --help

EXPLICIT HUMAN INVOCATION ONLY. Agents never run this script. Run it from the
repository root (bash scripts/cleanup.sh); it refuses elsewhere.

Who runs it (I-08): the human, with the human's OWN administrator profile
(Phase 12 decision 4), for listing, emptying and applying the removal plan.
Never under the deployment role: the removal plan removes the deployment
role and its policy, and that role is denied object removal outside
artifacts/emr/, so a run under it would stop partway.

Both modes refuse, before any cloud call, unless PATH is an existing,
non-empty local evidence archive (made by scripts/archive-evidence.sh) that
tar can list. The archive's keys are computed with archive_keys of
scripts/lib/identity.sh (the same rule archive-evidence.sh and check.sh use:
a leading "./" and one common root folder that is not reports/ are removed,
directory entries are dropped). Both modes then refuse unless every object
under the data lake's reports/ prefix appears as an exact key (whole-line
match; the archive's count of reports/ entries is printed and recorded).
Both modes refuse a dirty working tree (tracked changes or untracked files
outside .gitignore).

--list (changes nothing in AWS):
  1. Lists every target: each resource of a Terraform removal plan
     (terraform plan -destroy, saved as
     infra/terraform/environments/course/plans/cleanup-PLAN_COMMIT-PLAN_ID.tfplan,
     PLAN_COMMIT = HEAD, PLAN_ID a new attempt ID; full output shown); load
     balancers and networking components by name (API Gateway HTTP API, VPC
     link, internal ALB, NAT gateway and its address, VPC); things Terraform
     does not hold (running EMR clusters of this environment, the
     EMR-created interface endpoint "emr-service-vpce" or any other
     interface endpoint in the VPC, ECR images, object versions in the
     data-lake bucket, vector indexes); every resource still tagged
     project=scholarchain environment=course; and what is never touched.
  2. Records the removal plan's sha256, plan_commit, plan_id and build
     commit (cleanup-PLAN_COMMIT-PLAN_ID.record.json, never overwritten) and
     prints the sha256. To approve it, the human commits the whole line
       APPROVED-PLAN-SHA256: HEX64
     in docs/agent-runs/phase-NN/human-gate.md or approvals.md.

--remove --plan-sha256 HEX:
  1. Refuses unless exactly one cleanup-*.record.json under plans/ records
     HEX, the saved plan file's sha256 is HEX, the plan is still usable
     (scripts/lib/identity.sh plan_usable: clean tree, plan_commit is HEAD
     or an ancestor, no plan input changed since: infra/terraform/ plus
     every repository file outside it that Terraform reads, listed in
     SC_PLAN_INPUT_PATHS, today
     modules/storage/src/main/resources/s3vectors/index-spec-v1.json; so the
     gate commit that approves it keeps it usable), the approval line
     "APPROVED-PLAN-SHA256: HEX" is committed at HEAD in a tracked
     human-gate.md or approvals.md under docs/agent-runs/ (same check as
     deploy.sh), and the caller account equals the recorded one.
  2. Lists every target again (from the saved plan and live reads).
  3. Refuses unless the runtime is off (runtime_enabled=false, every ECS
     service at desired 0 and running 0), no pipeline execution is running,
     and deletion protection is off on the six DynamoDB tables and Neptune
     (apply a signed cleanup_mode=true plan first; README "Cleanup mode").
  4. Asks for ONE typed confirmation on the terminal:
       REMOVE course <first 12 characters of the plan sha256>
     Anything else, or no answer within 300 s, stops with nothing removed.
  5. Removes in this order: end running EMR clusters of this environment
     (and wait); remove interface endpoints in the VPC (Terraform creates
     none; EMR creates "emr-service-vpce"); delete every ECR image; delete
     every object version and delete marker in the data-lake bucket (the
     evidence archive holds reports/); apply the saved removal plan (API
     Gateway, VPC link, ALB, ECS, Step Functions, SQS, Neptune, DynamoDB,
     S3 Vectors, secrets, log groups, NAT gateway, address, VPC, IAM roles).
  6. Lists leftovers by tag and fails (exit 6) if any unexpected one
     remains. EXPECTED leftovers (I-15) are listed separately and do not
     count toward exit 6: ACTIVE task-definition revisions of this
     environment's families (query, evidence, graph, worker, ops). They stay
     because the task definitions use skip_destroy (rollback keeps the
     previous revision); they cost nothing; Phase 17 deregisters them.

Never touched: the repository (reports/, docs/agent-runs/, releases/ and the
evidence archive are local grading evidence and stay as they are), the local
Terraform state file except through terraform apply, the hand-made IAM user
scholarchain-readonly and budget scholarchain-25 (Phase 17 manual items).
No secret is printed; account IDs and bucket names are masked in what this
script prints and saves, the Terraform plan and apply output included
(every line masked; the .plan.txt and .apply.txt copies are the masked
text). The removal-plan record keeps the account as account_sha256 (sha256
of "scholarchain-account-v1:ACCOUNT_ID", compared with the caller's in
--remove) and account_masked, never the ID; the record and .plan.txt are
checked for identifiers (grep -F) before the sha256 is printed.

Requirements: bash, git, terraform, aws (CLI v2), jq (1.6+), tar, shasum or
sha256sum, timeout or gtimeout (GNU coreutils); a terminal for the
confirmation; the human's own administrator credentials (see "Who runs it").

Exit codes: 0 done (list written, or everything removed; expected
task-definition leftovers are listed); 2 usage error; 3 missing
prerequisite; 4 refused by a safety check (not at the repository root,
archive, dirty tree, no or several matching records, checksum, plan
validity, approval line, account, runtime on, running execution, protection
on, confirmation); 5 terraform or AWS command failed; 6 unexpected leftovers
remain after removal (listed), or (--list) the removal-plan record or
.plan.txt still holds an identifier; 7 timed out.

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
trap 'exit 5' INT
trap 'exit 7' TERM
trap on_exit EXIT

# Until the helper is loaded (enter_repo_root), mask_text prints its argument
# unchanged; the helper's mask_text (scripts/lib/identity.sh) then replaces
# this definition and masks the identifiers registered in read_outputs.
mask_text() { printf '%s' "$*"; }

with_timeout() {
  local secs="$1"
  shift
  "${TIMEOUT_BIN}" --kill-after=30 "${secs}" "$@"
}

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "$1" | cut -d ' ' -f 1
  else
    sha256sum -- "$1" | cut -d ' ' -f 1
  fi
}

aws_json() {
  local rc=0 out errfile
  errfile="${TMP_DIR}/aws.err"
  out="$(with_timeout "${AWS_TIMEOUT}" aws --region "${EXPECTED_REGION}" \
    --cli-connect-timeout 10 --cli-read-timeout 60 --no-cli-pager --output json \
    "$@" 2>"${errfile}")" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    [ "${rc}" -eq 124 ] && die 7 "aws $1 $2 timed out after ${AWS_TIMEOUT}s"
    printf 'AWS-ERROR: %s\n' "$(mask_text "$(cat "${errfile}")")" >&2
    die 5 "aws $1 $2 failed (status ${rc})"
  fi
  if [ -z "${out}" ]; then
    out='{}'
  fi
  printf '%s\n' "${out}"
}

# ---- arguments -----------------------------------------------------------------

MODE=""
ARCHIVE=""
PLAN_SHA256_ARG=""

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --evidence-archive)
        [ "$#" -ge 2 ] || die 2 "--evidence-archive needs a path"
        ARCHIVE="$2"
        shift 2
        ;;
      --list | --remove)
        [ -z "${MODE}" ] || die 2 "give exactly one of --list and --remove"
        MODE="${1#--}"
        shift
        ;;
      --plan-sha256)
        [ "$#" -ge 2 ] || die 2 "--plan-sha256 needs a value"
        PLAN_SHA256_ARG="$2"
        shift 2
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
  [ -n "${MODE}" ] || {
    usage >&2
    die 2 "give --list or --remove"
  }
  [ -n "${ARCHIVE}" ] || {
    usage >&2
    die 4 "refusing: --evidence-archive PATH is required (scripts/archive-evidence.sh makes it)"
  }
  if [ "${MODE}" = "remove" ]; then
    [[ "${PLAN_SHA256_ARG}" =~ ^[0-9a-f]{64}$ ]] || die 2 "--remove needs --plan-sha256 HEX64 (64 lowercase hex)"
  elif [ -n "${PLAN_SHA256_ARG}" ]; then
    die 2 "--plan-sha256 belongs to --remove"
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

# ---- preconditions -------------------------------------------------------------

check_prerequisites() {
  step "prerequisites"
  local tool
  for tool in git terraform aws jq tar cut; do
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
  aws --version 2>&1 | grep -q '^aws-cli/2' || die 3 "AWS CLI v2 is required"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-cleanup.XXXXXX")"
}

ARCHIVE_LIST=""
ARCHIVE_KEYS=""
ARCHIVE_ROOT=""
ARCHIVE_REPORTS_COUNT=0

# normalize_archive_list: writes ARCHIVE_KEYS, one key per line, with the
# shared rule archive_keys (scripts/lib/identity.sh; the rule moved there
# unchanged from this script in Phase 13, so archive-evidence.sh, check.sh's
# probe and this script agree). The reports/ check then compares whole lines
# (grep -F -x), never substrings (repair I-13).
normalize_archive_list() {
  ARCHIVE_ROOT="$(archive_root "${ARCHIVE_LIST}")"
  ARCHIVE_KEYS="${TMP_DIR}/archive-keys.txt"
  archive_keys "${ARCHIVE_LIST}" >"${ARCHIVE_KEYS}"
  ARCHIVE_REPORTS_COUNT="$(grep -c -E '^reports/.' "${ARCHIVE_KEYS}" || true)"
  [[ "${ARCHIVE_REPORTS_COUNT}" =~ ^[0-9]+$ ]] || ARCHIVE_REPORTS_COUNT=0
}

check_archive() {
  step "evidence-archive"
  [ -f "${ARCHIVE}" ] || die 4 "refusing: evidence archive not found: ${ARCHIVE}"
  [ -s "${ARCHIVE}" ] || die 4 "refusing: evidence archive is empty: ${ARCHIVE}"
  ARCHIVE_LIST="${TMP_DIR}/archive-list.txt"
  with_timeout 300 tar -tf "${ARCHIVE}" >"${ARCHIVE_LIST}" 2>/dev/null ||
    die 4 "refusing: tar cannot list the evidence archive"
  [ -s "${ARCHIVE_LIST}" ] || die 4 "refusing: the evidence archive lists no entries"
  normalize_archive_list
  info "evidence archive ${ARCHIVE}: $(wc -l <"${ARCHIVE_LIST}" | tr -d ' ') entries, sha256 $(sha256_of "${ARCHIVE}")"
  info "evidence archive root folder: ${ARCHIVE_ROOT:-<none>}; reports/ file entries: ${ARCHIVE_REPORTS_COUNT}"
}

COMMIT=""
BUILD_SHA=""

check_clean_tree() {
  step "clean-tree"
  COMMIT="$(head_commit)" || die 3 "not a git repository with a commit"
  require_clean_tree || die 4 "refusing: the working tree is dirty (commit the evidence first)"
  BUILD_SHA="$(build_commit)" || die 3 "cannot determine the build commit"
  info "HEAD ${COMMIT}, build commit ${BUILD_SHA}, tree clean"
}

OUTPUTS=""
ACCOUNT_FP=""
# Name prefix of this environment's resources (project-environment,
# environments/course/main.tf locals); used only for bucket name patterns.
readonly NAME_PREFIX="scholarchain-course"

read_outputs() {
  step "terraform-outputs"
  local rc=0
  OUTPUTS="$(with_timeout "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" output -json -no-color)" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform output failed (status ${rc})"
  DATA_BUCKET="$(jq -r '.data_bucket_name.value // empty' <<<"${OUTPUTS}")"
  VECTOR_BUCKET="$(jq -r '.vector_bucket_name.value // empty' <<<"${OUTPUTS}")"
  [ -n "$(jq -r '.vpc_id.value // empty' <<<"${OUTPUTS}")" ] ||
    die 3 "no outputs in the local state (nothing deployed, or the state is missing: then clean up by tag, README)"
  local ident
  ident="$(aws_json sts get-caller-identity)" || exit "$?"
  ACCOUNT_ID="$(jq -r '.Account // empty' <<<"${ident}")"
  [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]] || die 5 "could not read the account"
  register_identifiers "${ACCOUNT_ID}" "${DATA_BUCKET}" "${VECTOR_BUCKET}" || die 5 "could not register the identifiers"
  register_bucket_patterns "${NAME_PREFIX}" || die 5 "could not register the bucket name patterns"
  ACCOUNT_FP="$(account_fingerprint "${ACCOUNT_ID}")" || die 3 "could not compute the account fingerprint"
  info "account $(mask_account "${ACCOUNT_ID}"), Region ${EXPECTED_REGION}, profile ${AWS_PROFILE:-<default credential chain>} (use the human's own administrator profile, I-08)"
}

out() { jq -r --arg k "$1" '.[$k].value // empty' <<<"${OUTPUTS}"; }

check_reports_archived() {
  step "reports-archived"
  [ -n "${DATA_BUCKET}" ] || return 0
  local listing keys missing=0 key count=0
  listing="$(aws_json s3api list-objects-v2 --bucket "${DATA_BUCKET}" --prefix "reports/")" || exit "$?"
  keys="$(jq -r '.Contents[]?.Key' <<<"${listing}")"
  while IFS= read -r key; do
    if [ -z "${key}" ] || [ "${key%/}" != "${key}" ]; then
      continue
    fi
    count=$((count + 1))
    # Exact whole-line match against the normalized archive paths (repair
    # I-13): reports/a.json is not "found" in reports/a.json.sha256.
    if ! grep -F -x -q -- "${key}" "${ARCHIVE_KEYS}"; then
      printf 'EVIDENCE: s3 object %s is not in the evidence archive\n' "${key}" >&2
      missing=1
    fi
  done <<<"${keys}"
  info "reports/ objects in S3: ${count}; reports/ file entries in the evidence archive: ${ARCHIVE_REPORTS_COUNT}"
  [ "${missing}" -eq 0 ] || die 4 "refusing: the evidence archive does not hold every reports/ object (run scripts/archive-evidence.sh again)"
  info "reports/ objects: ${count}, all present in the evidence archive (exact path match)"
}

# ---- removal plan (list mode) --------------------------------------------------

PLAN_FILE_REL=""
PLAN_JSON=""
PLAN_ID=""
PLAN_BASE=""

terraform_removal_plan() {
  step "terraform-init"
  local rc=0
  with_timeout "${INIT_TIMEOUT}" terraform "-chdir=${TF_DIR}" init -input=false \
    -lockfile=readonly -no-color || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform init timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform init failed (status ${rc})"

  step "terraform-removal-plan"
  mkdir -p -- "${TF_DIR}/${PLANS_SUBDIR}"
  PLAN_ID="$(new_attempt_id)"
  PLAN_BASE="cleanup-${COMMIT}-${PLAN_ID}"
  local ext
  for ext in tfplan record.json plan.txt; do
    [ ! -e "${TF_DIR}/${PLANS_SUBDIR}/${PLAN_BASE}.${ext}" ] ||
      die 4 "refusing: ${TF_DIR}/${PLANS_SUBDIR}/${PLAN_BASE}.${ext} exists (records are never overwritten)"
  done
  PLAN_FILE_REL="${PLANS_SUBDIR}/${PLAN_BASE}.tfplan"
  local text="${TF_DIR}/${PLANS_SUBDIR}/${PLAN_BASE}.plan.txt"
  rc=0
  info "full removal plan output follows, masked (the masked copy is saved to ${text})"
  with_timeout "${PLAN_TIMEOUT}" terraform "-chdir=${TF_DIR}" plan -destroy -input=false \
    -lock-timeout=60s -no-color -var-file=course.tfvars -var=cleanup_mode=true \
    -var=runtime_enabled=false "-out=${PLAN_FILE_REL}" 2>&1 | mask_stream | tee "${text}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform plan -destroy timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform plan -destroy failed (status ${rc})"
}

show_plan_json() {
  PLAN_JSON="${TMP_DIR}/plan.json"
  local rc=0
  with_timeout "${SHOW_TIMEOUT}" terraform "-chdir=${TF_DIR}" show -json -no-color \
    "${PLAN_FILE_REL}" >"${PLAN_JSON}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform show timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform show -json failed (status ${rc})"
}

write_cleanup_record() {
  step "cleanup-record"
  local sha record="${TF_DIR}/${PLANS_SUBDIR}/${PLAN_BASE}.record.json"
  sha="$(sha256_of "${TF_DIR}/${PLAN_FILE_REL}")"
  (
    set -o noclobber
    jq -n --arg kind "scholarchain-cleanup-plan-record" --arg environment "${ENVIRONMENT}" \
      --arg plan_commit "${COMMIT}" --arg build_commit "${BUILD_SHA}" --arg plan_id "${PLAN_ID}" \
      --arg plan_file "${PLAN_FILE_REL}" --arg plan_sha256 "${sha}" \
      --arg account_sha256 "${ACCOUNT_FP}" --arg account_masked "$(mask_account "${ACCOUNT_ID}")" \
      --arg region "${EXPECTED_REGION}" \
      --arg archive_sha256 "$(sha256_of "${ARCHIVE}")" \
      --argjson archive_reports_entries "${ARCHIVE_REPORTS_COUNT}" \
      --arg created_utc "$(sc_utc_now)" \
      '{kind: $kind, version: 3, environment: $environment,
        plan_commit: $plan_commit, commit: $plan_commit, build_commit: $build_commit,
        plan_id: $plan_id, tree_clean: true, plan_file: $plan_file, plan_sha256: $plan_sha256,
        account_sha256: $account_sha256, account_masked: $account_masked,
        region: $region, evidence_archive_sha256: $archive_sha256,
        evidence_archive_reports_entries: $archive_reports_entries,
        created_utc: $created_utc}' >"${record}"
  ) || die 5 "could not write ${record}"
  # I-21: nothing persisted next to the removal plan may hold the account ID
  # or a bucket name.
  check_no_identifiers "${record}" "${TF_DIR}/${PLANS_SUBDIR}/${PLAN_BASE}.plan.txt" ||
    die 6 "a removal-plan file still holds an identifier (listed above); do not share or archive it"
  printf 'CLEANUP-PLAN-SHA256: %s\n' "${sha}"
  printf 'CLEANUP-PLAN-COMMIT: %s\n' "${COMMIT}"
  printf 'CLEANUP-PLAN-ID: %s\n' "${PLAN_ID}"
  info "nothing was removed. To approve, commit the line 'APPROVED-PLAN-SHA256: ${sha}' in docs/agent-runs/phase-NN/human-gate.md or approvals.md, then run --remove --plan-sha256 ${sha}"
}

# Local checks of --remove (no cloud call): the record found by sha256, the
# plan file's checksum, plan validity and the committed approval line.
check_cleanup_plan_local() {
  step "plan-lookup"
  CLEANUP_RECORD="$(find_plan_record "${TF_DIR}/${PLANS_SUBDIR}" "scholarchain-cleanup-plan-record" "${PLAN_SHA256_ARG}")" ||
    die 4 "refusing: no single removal-plan record with sha256 ${PLAN_SHA256_ARG} (run --list first)"
  PLAN_FILE_REL="$(jq -r '.plan_file // empty' "${CLEANUP_RECORD}")"
  case "${PLAN_FILE_REL}" in
    "${PLANS_SUBDIR}"/cleanup-*.tfplan) ;;
    *) die 4 "refusing: the record names an unexpected plan file '${PLAN_FILE_REL}'" ;;
  esac
  case "${PLAN_FILE_REL}" in
    *..* | */*/*) die 4 "refusing: the record names an unexpected plan file '${PLAN_FILE_REL}'" ;;
  esac
  PLAN_BASE="${PLAN_FILE_REL##*/}"
  PLAN_BASE="${PLAN_BASE%.tfplan}"
  [ -f "${TF_DIR}/${PLAN_FILE_REL}" ] || die 4 "refusing: no saved removal plan ${TF_DIR}/${PLAN_FILE_REL}"
  [ "$(jq -r '.environment // empty' "${CLEANUP_RECORD}")" = "${ENVIRONMENT}" ] || die 4 "refusing: record environment is not ${ENVIRONMENT}"
  [ "$(jq -r '.region // empty' "${CLEANUP_RECORD}")" = "${EXPECTED_REGION}" ] || die 4 "refusing: record Region is not ${EXPECTED_REGION}"
  local actual plan_commit rc=0
  actual="$(sha256_of "${TF_DIR}/${PLAN_FILE_REL}")"
  info "removal-plan record: ${CLEANUP_RECORD}"
  info "argument sha256:  ${PLAN_SHA256_ARG}"
  info "plan file sha256: ${actual}"
  [ "${actual}" = "${PLAN_SHA256_ARG}" ] || die 4 "refusing: the removal plan checksum differs from --plan-sha256"

  step "plan-validity"
  plan_commit="$(jq -r '.plan_commit // .commit // empty' "${CLEANUP_RECORD}")"
  plan_usable "${plan_commit}" || rc=$?
  [ "${rc}" -eq 0 ] || {
    [ "${rc}" -eq 5 ] && die 5 "git failed while checking plan validity"
    die 4 "refusing: the removal plan is not usable at HEAD (reason above; run --list again)"
  }
  info "plan commit ${plan_commit} is HEAD or an ancestor, and no plan input (${SC_PLAN_INPUT_PATHS[*]}) changed since"

  step "approval"
  require_plan_approval "${PLAN_SHA256_ARG}" ||
    die 4 "refusing: the removal plan is not approved (commit 'APPROVED-PLAN-SHA256: ${PLAN_SHA256_ARG}' in docs/agent-runs/phase-NN/human-gate.md or approvals.md)"
  info "approval line found at HEAD (it proves the line was committed, not who committed it)"
}

CLEANUP_RECORD=""

check_cleanup_account() {
  step "plan-account"
  # I-21: records keep account_sha256 (account_fingerprint), never the ID; a
  # record from before this change holds account_id, turned into the same
  # fingerprint here.
  local recorded legacy
  recorded="$(jq -r '.account_sha256 // empty' "${CLEANUP_RECORD}")"
  if [ -z "${recorded}" ]; then
    legacy="$(jq -r '.account_id // empty' "${CLEANUP_RECORD}")"
    if [[ "${legacy}" =~ ^[0-9]{12}$ ]]; then
      recorded="$(account_fingerprint "${legacy}")" || die 3 "could not compute the account fingerprint"
    fi
  fi
  [[ "${recorded}" =~ ^[0-9a-f]{64}$ ]] || die 4 "refusing: the removal-plan record holds no account fingerprint"
  [ "${recorded}" = "${ACCOUNT_FP}" ] ||
    die 4 "refusing: record account differs from the caller account $(mask_account "${ACCOUNT_ID}")"
  info "record account matches the caller account"
}

# ---- listing (both modes) ------------------------------------------------------

EMR_CLUSTERS=""
INTERFACE_ENDPOINTS=""

list_targets() {
  step "list-terraform-targets"
  printf 'TARGETS (Terraform removal plan, %s resources):\n' \
    "$(jq '[.resource_changes[]? | select(.change.actions | index("delete"))] | length' "${PLAN_JSON}")"
  jq -r '.resource_changes[]? | select(.change.actions | index("delete")) | "  remove \(.address)"' "${PLAN_JSON}"
  local nondelete
  nondelete="$(jq '[.resource_changes[]? | select((.change.actions | index("delete")) | not)
    | select(.change.actions != ["no-op"] and .change.actions != ["read"])] | length' "${PLAN_JSON}")"
  [ "${nondelete}" -eq 0 ] || die 4 "refusing: the removal plan holds ${nondelete} change(s) that are not removals"

  step "list-network"
  printf 'LOAD BALANCERS AND NETWORKING (removed by the plan above):\n'
  printf '  API Gateway HTTP API id: %s\n' "$(out api_id)"
  jq -r '.resource_changes[]? | select(.type == "aws_apigatewayv2_vpc_link") | "  VPC link: \(.address)"' "${PLAN_JSON}"
  printf '  internal ALB: %s\n' "$(out alb_dns_name)"
  printf '  NAT gateway: %s\n' "$(out nat_gateway_id)"
  jq -r '.resource_changes[]? | select(.type == "aws_eip") | "  NAT address: \(.address)"' "${PLAN_JSON}"
  printf '  VPC: %s\n' "$(out vpc_id)"

  step "list-outside-terraform"
  printf 'NOT IN TERRAFORM STATE (removed by this script before the plan is applied):\n'
  local clusters id desc
  EMR_CLUSTERS=""
  clusters="$(aws_json emr list-clusters --active)" || exit "$?"
  for id in $(jq -r '.Clusters[]?.Id' <<<"${clusters}"); do
    desc="$(aws_json emr describe-cluster --cluster-id "${id}")" || exit "$?"
    if jq -e --arg env "${ENVIRONMENT}" '[.Cluster.Tags[]? | select((.Key == "project" and .Value == "scholarchain")
         or (.Key == "environment" and .Value == $env))] | length == 2' <<<"${desc}" >/dev/null; then
      EMR_CLUSTERS="${EMR_CLUSTERS} ${id}"
      printf '  EMR cluster %s (%s, %s)\n' "${id}" "$(jq -r '.Cluster.Name' <<<"${desc}")" \
        "$(jq -r '.Cluster.Status.State' <<<"${desc}")"
    fi
  done
  [ -n "${EMR_CLUSTERS}" ] || printf '  EMR clusters of this environment: none running\n'

  local endpoints
  endpoints="$(aws_json ec2 describe-vpc-endpoints --filters "Name=vpc-id,Values=$(out vpc_id)" \
    "Name=vpc-endpoint-type,Values=Interface")" || exit "$?"
  INTERFACE_ENDPOINTS="$(jq -r '[.VpcEndpoints[]? | select(.State != "deleted" and .State != "deleting") | .VpcEndpointId] | join(" ")' <<<"${endpoints}")"
  jq -r '.VpcEndpoints[]? | "  interface endpoint \(.VpcEndpointId) \(.ServiceName) \(.State) name=\([.Tags[]? | select(.Key == "Name") | .Value] | first // "-")"' <<<"${endpoints}"
  [ -n "${INTERFACE_ENDPOINTS}" ] || printf '  interface endpoints in the VPC (emr-service-vpce): none\n'

  local repo images
  repo="$(out ecr_repository_name)"
  if [ -n "${repo}" ]; then
    images="$(aws_json ecr list-images --repository-name "${repo}")" || exit "$?"
    printf '  ECR images in %s: %s\n' "${repo}" "$(jq '[.imageIds[]?.imageDigest] | unique | length' <<<"${images}")"
  fi

  if [ -n "${DATA_BUCKET}" ]; then
    local versions
    versions="$(aws_json s3api list-object-versions --bucket "${DATA_BUCKET}" --max-items 1000)" || exit "$?"
    printf '  data-lake bucket <data-bucket>: %s object versions and delete markers (first 1000 counted%s)\n' \
      "$(jq '([.Versions[]?] + [.DeleteMarkers[]?]) | length' <<<"${versions}")" \
      "$(jq -r 'if .NextToken then "; more exist" else "" end' <<<"${versions}")"
  fi

  if [ -n "${VECTOR_BUCKET}" ]; then
    local indexes
    indexes="$(aws_json s3vectors list-indexes --vector-bucket-name "${VECTOR_BUCKET}")" || exit "$?"
    printf '  vector indexes in <vector-bucket> (removed by the plan): %s\n' \
      "$(jq -r '[.indexes[]?.indexName] | join(", ")' <<<"${indexes}")"
  fi

  list_tagged "TAGGED project=scholarchain environment=${ENVIRONMENT} (now):"

  step "list-excluded"
  printf 'NEVER TOUCHED:\n'
  printf '  repository files: reports/, docs/agent-runs/, releases/ (local grading evidence)\n'
  printf '  the evidence archive %s\n' "${ARCHIVE}"
  printf '  IAM user scholarchain-readonly and budget scholarchain-25 (hand-made; Phase 17 manual items)\n'
}

TAGGED_COUNT=0
EXPECTED_COUNT=0

# expected_leftover ARN: status 0 for an ECS task-definition revision of one
# of this environment's families (I-15: they stay ACTIVE because of
# skip_destroy; Phase 17 deregisters them).
expected_leftover() {
  local arn="$1" fam_rev family
  case "${arn}" in
    arn:*:ecs:*:task-definition/*) ;;
    *) return 1 ;;
  esac
  fam_rev="${arn##*:task-definition/}"
  family="${fam_rev%:*}"
  jq -e --arg f "${family}" '(.ecs_task_definition_families.value // {}) | [.[]] | index($f) != null' \
    <<<"${OUTPUTS}" >/dev/null 2>&1
}

# list_tagged TITLE: lists every resource tagged project=scholarchain
# environment=course; TAGGED_COUNT counts the UNEXPECTED ones,
# EXPECTED_COUNT the task-definition revisions of expected_leftover.
list_tagged() {
  local title="$1" tagged arn expected=""
  tagged="$(aws_json resourcegroupstaggingapi get-resources \
    --tag-filters "Key=project,Values=scholarchain" "Key=environment,Values=${ENVIRONMENT}")" || exit "$?"
  TAGGED_COUNT=0
  EXPECTED_COUNT=0
  printf '%s\n' "${title}"
  while IFS= read -r arn; do
    [ -n "${arn}" ] || continue
    if expected_leftover "${arn}"; then
      EXPECTED_COUNT=$((EXPECTED_COUNT + 1))
      expected="${expected}  ${arn##*:task-definition/}
"
    else
      TAGGED_COUNT=$((TAGGED_COUNT + 1))
      printf '  %s\n' "$(mask_text "${arn}")"
    fi
  done < <(jq -r '.ResourceTagMappingList[]?.ResourceARN' <<<"${tagged}")
  printf '  unexpected (counted): %s\n' "${TAGGED_COUNT}"
  printf 'EXPECTED task-definition revisions (I-15; skip_destroy keeps them ACTIVE; not counted; Phase 17 deregisters them): %s\n' \
    "${EXPECTED_COUNT}"
  if [ -n "${expected}" ]; then
    printf '%s' "${expected}"
  fi
}

# ---- remove-mode preconditions -------------------------------------------------

check_runtime_off() {
  step "check-runtime-off"
  [ "$(out runtime_enabled)" = "false" ] ||
    die 4 "refusing: runtime_enabled is not false (apply a signed plan with runtime_enabled=false first)"
  local cluster names=() role name services busy
  cluster="$(out ecs_cluster_name)"
  for role in ${ROLES}; do
    name="$(jq -r --arg r "${role}" '.ecs_service_names.value[$r] // empty' <<<"${OUTPUTS}")"
    if [ -n "${name}" ]; then
      names+=("${name}")
    fi
  done
  if [ -n "${cluster}" ] && [ "${#names[@]}" -gt 0 ]; then
    services="$(aws_json ecs describe-services --cluster "${cluster}" --services "${names[@]}")" || exit "$?"
    busy="$(jq -r '[.services[] | select(.desiredCount != 0 or .runningCount != 0)
      | "\(.serviceName) desired=\(.desiredCount) running=\(.runningCount)"] | join("; ")' <<<"${services}")"
    [ -z "${busy}" ] || die 4 "refusing: ECS services still run tasks: ${busy}"
  fi
  info "runtime off: every ECS service at desired 0 and running 0"
}

check_no_running_execution() {
  step "check-no-running-execution"
  local sm running
  sm="$(out state_machine_arn)"
  [ -n "${sm}" ] || return 0
  running="$(aws_json stepfunctions list-executions --state-machine-arn "${sm}" --status-filter RUNNING)" || exit "$?"
  [ "$(jq '[.executions[]?] | length' <<<"${running}")" -eq 0 ] ||
    die 4 "refusing: a pipeline execution is RUNNING (let it end or stop it by hand, then check its clusters)"
  info "no running pipeline execution"
}

check_protection_off() {
  step "check-protection-off"
  local table desc protected=""
  for table in $(jq -r '.dynamodb_table_names.value // {} | .[]' <<<"${OUTPUTS}"); do
    desc="$(aws_json dynamodb describe-table --table-name "${table}")" || exit "$?"
    if [ "$(jq -r '.Table.DeletionProtectionEnabled // false' <<<"${desc}")" != "false" ]; then
      protected="${protected} ${table}"
    fi
  done
  local endpoint clusters
  endpoint="$(out neptune_endpoint)"
  if [ -n "${endpoint}" ]; then
    clusters="$(aws_json neptune describe-db-clusters --filters "Name=engine,Values=neptune")" || exit "$?"
    if jq -e --arg e "${endpoint}" '[.DBClusters[]? | select(.Endpoint == $e and .DeletionProtection == true)] | length > 0' \
      <<<"${clusters}" >/dev/null; then
      protected="${protected} neptune-cluster"
    fi
  fi
  [ -z "${protected}" ] ||
    die 4 "refusing: deletion protection is on for:${protected} (apply a signed cleanup_mode=true plan first)"
  info "deletion protection off on the DynamoDB tables and the Neptune cluster"
}

confirm() {
  step "confirm"
  local expected answer=""
  expected="REMOVE ${ENVIRONMENT} ${PLAN_SHA256_ARG:0:12}"
  [ -r /dev/tty ] || die 4 "refusing: no terminal for the typed confirmation"
  printf 'CONFIRM: type exactly "%s" and press Enter (anything else stops; %ss limit):\n' \
    "${expected}" "${CONFIRM_TIMEOUT}"
  if ! read -r -t "${CONFIRM_TIMEOUT}" answer </dev/tty; then
    die 4 "refusing: no confirmation within ${CONFIRM_TIMEOUT}s; nothing was removed"
  fi
  [ "${answer}" = "${expected}" ] || die 4 "refusing: confirmation did not match; nothing was removed"
  info "confirmed by the human at the terminal"
}

# ---- removal -------------------------------------------------------------------

end_emr_clusters() {
  step "remove-emr-clusters"
  if [ -z "${EMR_CLUSTERS}" ]; then
    info "no EMR cluster to end"
    return 0
  fi
  local id rc
  for id in ${EMR_CLUSTERS}; do
    aws_json emr terminate-clusters --cluster-ids "${id}" >/dev/null || exit "$?"
    info "ending EMR cluster ${id}"
  done
  for id in ${EMR_CLUSTERS}; do
    rc=0
    with_timeout "${EMR_WAIT_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 60 --no-cli-pager emr wait cluster-terminated --cluster-id "${id}" || rc=$?
    [ "${rc}" -eq 124 ] && die 7 "EMR cluster ${id} did not end within ${EMR_WAIT_TIMEOUT}s"
    [ "${rc}" -eq 0 ] || die 5 "waiting for EMR cluster ${id} failed (status ${rc})"
    info "EMR cluster ${id} ended"
  done
}

remove_interface_endpoints() {
  step "remove-interface-endpoints"
  if [ -z "${INTERFACE_ENDPOINTS}" ]; then
    info "no interface endpoint to remove"
    return 0
  fi
  local ids=() id result
  for id in ${INTERFACE_ENDPOINTS}; do
    ids+=("${id}")
  done
  result="$(aws_json ec2 delete-vpc-endpoints --vpc-endpoint-ids "${ids[@]}")" || exit "$?"
  [ "$(jq '[.Unsuccessful[]?] | length' <<<"${result}")" -eq 0 ] ||
    die 5 "some interface endpoints were not removed: $(jq -c '.Unsuccessful' <<<"${result}")"
  local deadline left
  deadline=$(($(date +%s) + VPCE_WAIT_TIMEOUT))
  while :; do
    result="$(aws_json ec2 describe-vpc-endpoints --filters "Name=vpc-id,Values=$(out vpc_id)" \
      "Name=vpc-endpoint-type,Values=Interface")" || exit "$?"
    left="$(jq '[.VpcEndpoints[]? | select(.State != "deleted")] | length' <<<"${result}")"
    if [ "${left}" -eq 0 ]; then
      break
    fi
    [ "$(date +%s)" -lt "${deadline}" ] || die 7 "${left} interface endpoint(s) still present after ${VPCE_WAIT_TIMEOUT}s"
    sleep 15
  done
  info "interface endpoints removed"
}

empty_ecr() {
  step "remove-ecr-images"
  local repo images total chunk
  repo="$(out ecr_repository_name)"
  [ -n "${repo}" ] || return 0
  images="$(aws_json ecr list-images --repository-name "${repo}")" || exit "$?"
  total="$(jq '[.imageIds[]?.imageDigest] | unique | length' <<<"${images}")"
  local offset=0 result
  while [ "${offset}" -lt "${total}" ]; do
    chunk="${TMP_DIR}/ecr-chunk.json"
    jq -c --argjson o "${offset}" '[.imageIds[]?.imageDigest] | unique | .[$o:$o + 100]
      | map({imageDigest: .})' <<<"${images}" >"${chunk}"
    result="$(aws_json ecr batch-delete-image --repository-name "${repo}" \
      --image-ids "file://${chunk}")" || exit "$?"
    [ "$(jq '[.failures[]?] | length' <<<"${result}")" -eq 0 ] ||
      die 5 "ECR image removal failures: $(jq -c '[.failures[]? | .failureCode] | unique' <<<"${result}")"
    offset=$((offset + 100))
  done
  info "ECR images removed: ${total}"
}

empty_data_bucket() {
  step "remove-bucket-objects"
  [ -n "${DATA_BUCKET}" ] || return 0
  local round=0 page count payload result removed=0
  payload="${TMP_DIR}/s3-payload.json"
  while [ "${round}" -lt "${MAX_DELETE_ROUNDS}" ]; do
    round=$((round + 1))
    page="$(aws_json s3api list-object-versions --bucket "${DATA_BUCKET}" --max-items 1000)" || exit "$?"
    count="$(jq '([.Versions[]?] + [.DeleteMarkers[]?]) | length' <<<"${page}")"
    if [ "${count}" -eq 0 ]; then
      info "data-lake bucket empty after removing ${removed} versions and delete markers"
      return 0
    fi
    jq -c '{Objects: (([.Versions[]?] + [.DeleteMarkers[]?])[:1000]
      | map({Key: .Key, VersionId: .VersionId})), Quiet: true}' <<<"${page}" >"${payload}"
    result="$(aws_json s3api delete-objects --bucket "${DATA_BUCKET}" --delete "file://${payload}")" || exit "$?"
    [ "$(jq '[.Errors[]?] | length' <<<"${result}")" -eq 0 ] ||
      die 5 "object removal errors: $(jq -c '[.Errors[]? | .Code] | unique' <<<"${result}")"
    removed=$((removed + count))
    if [ $((round % 20)) -eq 0 ]; then
      info "removed ${removed} versions and delete markers so far"
    fi
  done
  die 5 "the data-lake bucket is not empty after ${MAX_DELETE_ROUNDS} rounds"
}

apply_removal_plan() {
  step "terraform-apply-removal-plan"
  local text="${TF_DIR}/${PLANS_SUBDIR}/${PLAN_BASE}.apply.txt" rc=0
  [ ! -e "${text}" ] || die 4 "refusing: ${text} exists (this removal plan was applied before; run --list again)"
  info "full apply output follows, masked (the masked copy is saved to ${text})"
  with_timeout "${APPLY_TIMEOUT}" terraform "-chdir=${TF_DIR}" apply -input=false \
    -lock-timeout=60s -no-color "${PLAN_FILE_REL}" 2>&1 | mask_stream | tee "${text}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform apply of the removal plan timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform apply of the removal plan failed (status ${rc}); run --list again to see what is left"
}

check_leftovers() {
  step "check-leftovers"
  list_tagged "LEFTOVERS tagged project=scholarchain environment=${ENVIRONMENT}:"
  if [ "${TAGGED_COUNT}" -ne 0 ]; then
    warn "the tagging API can show removed resources for a while; check again later before removing anything by hand"
    die 6 "${TAGGED_COUNT} unexpected tagged resource(s) remain (listed above; the ${EXPECTED_COUNT} expected task-definition revisions are not counted)"
  fi
  info "no unexpected tagged resource remains; expected leftovers: ${EXPECTED_COUNT} task-definition revisions (I-15, Phase 17); manual items: IAM user scholarchain-readonly, budget scholarchain-25 (Phase 17)"
}

main() {
  parse_args "$@"
  enter_repo_root
  check_prerequisites
  check_archive
  check_clean_tree
  if [ "${MODE}" = "remove" ]; then
    check_cleanup_plan_local
  fi
  read_outputs
  check_reports_archived
  if [ "${MODE}" = "list" ]; then
    terraform_removal_plan
    show_plan_json
    list_targets
    write_cleanup_record
  else
    check_cleanup_account
    show_plan_json
    list_targets
    check_runtime_off
    check_no_running_execution
    check_protection_off
    confirm
    end_emr_clusters
    remove_interface_endpoints
    empty_ecr
    empty_data_bucket
    apply_removal_plan
    check_leftovers
  fi
  step "done"
}

main "$@"
