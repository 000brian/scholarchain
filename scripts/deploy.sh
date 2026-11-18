#!/usr/bin/env bash
# scripts/deploy.sh - apply ONE saved, recorded and approved plan of the
# ScholarChain course environment, verify the deployed ECS revisions, and
# write a release record under releases/. Owner: infra-engineer (author);
# executed by deploy-engineer after the human commits the approval line.
#
# Status (Phase 14, I-28): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="deploy.sh"
readonly ENVIRONMENT="course"
readonly EXPECTED_REGION="us-east-1"
readonly TF_DIR="infra/terraform/environments/course"
readonly PLANS_SUBDIR="plans"
readonly RELEASES_DIR="releases/course"
readonly APPLY_TIMEOUT=5400
readonly SHOW_TIMEOUT=300
readonly OUTPUT_TIMEOUT=120
readonly AWS_TIMEOUT=90
readonly STABLE_TIMEOUT=1200
readonly DRAIN_POLL_SECONDS=20
readonly ROLES="query evidence graph worker"
readonly CONTAINER_NAME="app"

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
ACCOUNT_ID=""

usage() {
  cat <<'EOF'
Usage: scripts/deploy.sh --plan-sha256 HEX64
       scripts/deploy.sh --help

Run it from the repository root (bash scripts/deploy.sh); it refuses elsewhere.

What it does (its whole contract), in this order; every refusal before
step 6 happens before any cloud call:
  1. Refuses a dirty working tree (any tracked change or untracked file
     outside .gitignore).
  2. Finds, among infra/terraform/environments/course/plans/*.record.json,
     the ONE plan record (kind scholarchain-plan-record, written by plan.sh)
     whose plan_sha256 equals --plan-sha256; refuses if there is none or
     more than one, or if it holds no account fingerprint.
  3. Refuses unless that record was made from a clean tree, in environment
     course and Region us-east-1, its plan file exists, and the plan file's
     sha256 equals --plan-sha256.
  4. Plan validity (scripts/lib/identity.sh plan_usable): refuses unless the
     tree is clean, the record's plan_commit is HEAD or an ancestor of HEAD,
     and no plan input changed between plan_commit and HEAD. Plan inputs
     (SC_PLAN_INPUT_PATHS in scripts/lib/identity.sh): infra/terraform/ plus
     every repository file outside it that Terraform reads, today
     modules/storage/src/main/resources/s3vectors/index-spec-v1.json. Later
     commits that touch no plan input (the gate commit, release records,
     script fixes) keep a plan usable.
  5. Approval: refuses unless a whole line
       APPROVED-PLAN-SHA256: HEX64
     (the same 64 hex, nothing else on the line) is in a tracked file named
     human-gate.md or approvals.md under docs/agent-runs/, as committed at
     HEAD (git grep on HEAD). Agent reports, summaries and prompts never
     count; agents cannot write there. This proves the approval line was
     committed; it does not prove who committed it (one-person repository).
  6. Refuses unless the AWS account equals the recorded one (the plan
     record keeps account_sha256, a fingerprint of the account, never the
     ID; a record without one is refused in step 2) and, when the plan
     turns the runtime on, the plan's image_tag is a 40-hex build commit
     that exists in the ECR repository (a missing tag or repository is a
     refusal, exit 4).
  7. Applies only that saved plan file (terraform apply -input=false PLAN);
     Terraform itself refuses a saved plan that is stale. The full apply
     output is shown with every line masked (the account ID as <account>;
     bucket names of this environment, even ones this apply creates, as
     <data-bucket> and <vector-bucket>) and that masked copy is kept next
     to the plan (.apply.txt).
  8. Verifies the deployed revision: every ECS service (query, evidence,
     graph, worker) runs the latest ACTIVE revision of its task-definition
     family and its image tag equals the plan's image tag. Runtime on: waits
     (at most 1200 s) until the services are stable and checks every running
     task's image digest against the ECR digest of that tag; query, evidence
     and graph must each show desired >= 1 and running >= 1 after the wait,
     else exit 6 with every service's desired, running and pending counts
     printed (I-28: zero tasks never pass the digest check). Runtime off
     (I-09): waits (at most 1200 s, polling every 20 s) until every
     service's desired AND running counts are 0, because Application Auto
     Scaling lowers them after the apply; on time-out it prints the counts
     and exits 7.
  9. Writes a release record releases/course/ATTEMPT_ID.json (never
     overwritten): HEAD, plan commit, build commit, plan id, plan checksum,
     Region, image repository/tag/digest, and per service the deployed and
     the previous task definition (family:revision). It holds no account
     ID, ARN, bucket name or secret (checked before it is written).

The release record is a tracked file: commit it before the next script. It
changes no plan input (infra/terraform/ and the files listed in
SC_PLAN_INPUT_PATHS) and no build input, so signed plans
and packaged artifacts stay valid.

Phase 14 order (decided in the Phase 13 work order):
  1. deploy.sh with the plan signed at the Phase 13 gate (runtime off);
     commit its release record.
  2. bash scripts/package.sh --publish (image to ECR, jars to
     artifacts/BUILD_COMMIT/).
  3. bash scripts/plan.sh --runtime-enabled true --image-tag BUILD_COMMIT
     --graph-run-id ID; commit APPROVED-PLAN-SHA256 for it in
     docs/agent-runs/phase-14/approvals.md.
  4. deploy.sh with that second plan; commit the release record; then run
     scripts/smoke-test.sh.

It never edits Terraform state by hand, never runs any other plan, and asks
no question. If the apply fails, no release record is written; run plan.sh
again to see what was applied.

Requirements: bash, git, terraform, aws (CLI v2), jq (1.6+), shasum or
sha256sum, timeout or gtimeout (GNU coreutils); AWS credentials of the deploy
profile (or the human's admin profile for the first apply) in the
environment.

Exit codes: 0 applied, verified and recorded; 2 usage error; 3 missing
prerequisite; 4 refused by a safety check (not at the repository root, dirty
tree, no or several matching plan records, plan validity, checksum, approval
line, account, Region, image); 5 terraform or AWS command failed; 6 deployed
revision not verified (the apply happened; see the evidence printed); 7
timed out.

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

# Masks with the helper's mask_text (loaded in enter_repo_root, before any AWS
# call; the account is registered in check_account).
deploy_mask() {
  mask_text "$1"
}

# Name prefix of this environment's resources (project-environment,
# environments/course/main.tf locals); used only for bucket name patterns, so
# a bucket created by this apply is masked before its name is known (I-21).
readonly NAME_PREFIX="scholarchain-course"

with_timeout() {
  local secs="$1"
  shift
  "${TIMEOUT_BIN}" --kill-after=30 "${secs}" "$@"
}

aws_json() {
  local rc=0 out errfile
  errfile="${TMP_DIR}/aws.err"
  out="$(with_timeout "${AWS_TIMEOUT}" aws --region "${EXPECTED_REGION}" \
    --cli-connect-timeout 10 --cli-read-timeout 60 --no-cli-pager --output json \
    "$@" 2>"${errfile}")" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    [ "${rc}" -eq 124 ] && die 7 "aws $1 $2 timed out after ${AWS_TIMEOUT}s"
    printf 'AWS-ERROR: %s\n' "$(deploy_mask "$(cat "${errfile}")")" >&2
    die 5 "aws $1 $2 failed (status ${rc})"
  fi
  printf '%s\n' "${out}"
}

# family:revision from a task-definition ARN (no account ID in the result).
family_rev() { printf '%s' "${1##*/}"; }

PLAN_SHA256_ARG=""

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
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
  [ -n "${PLAN_SHA256_ARG}" ] || {
    usage >&2
    die 2 "--plan-sha256 is required"
  }
  [[ "${PLAN_SHA256_ARG}" =~ ^[0-9a-f]{64}$ ]] || die 2 "--plan-sha256 must be 64 lowercase hex characters"
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

# Local tools only: the refusals of steps 1-5 need no cloud tool.
check_local_prerequisites() {
  step "prerequisites-local"
  local tool
  for tool in git jq cut; do
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
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-deploy.XXXXXX")"
}

check_cloud_prerequisites() {
  step "prerequisites-cloud"
  local tool
  for tool in terraform aws; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  aws --version 2>&1 | grep -q '^aws-cli/2' || die 3 "AWS CLI v2 is required"
}

HEAD_SHA=""
BUILD_SHA=""

check_clean_tree() {
  step "clean-tree"
  HEAD_SHA="$(head_commit)" || die 3 "not a git repository with a commit"
  require_clean_tree || die 4 "refusing: the working tree is dirty (commit first; the plan must match committed code)"
  BUILD_SHA="$(build_commit)" || die 3 "cannot determine the build commit"
  info "HEAD ${HEAD_SHA}, build commit ${BUILD_SHA}, tree clean"
}

RECORD=""
PLAN_FILE_REL=""
PLAN_COMMIT=""
PLAN_ID=""
RECORD_ACCOUNT=""

find_plan() {
  step "plan-lookup"
  RECORD="$(find_plan_record "${TF_DIR}/${PLANS_SUBDIR}" "scholarchain-plan-record" "${PLAN_SHA256_ARG}")" ||
    die 4 "refusing: no single plan record with sha256 ${PLAN_SHA256_ARG} (run scripts/plan.sh on a clean tree)"
  info "plan record ${RECORD}"

  local renv rclean rregion actual
  renv="$(jq -r '.environment // empty' "${RECORD}")"
  rclean="$(jq -r '.tree_clean' "${RECORD}")"
  rregion="$(jq -r '.region // empty' "${RECORD}")"
  PLAN_FILE_REL="$(jq -r '.plan_file // empty' "${RECORD}")"
  PLAN_COMMIT="$(jq -r '.plan_commit // .commit // empty' "${RECORD}")"
  PLAN_ID="$(jq -r '.plan_id // empty' "${RECORD}")"
  # I-21: plan records keep account_sha256 (account_fingerprint), never the
  # ID. A record from before this change holds account_id; it is turned into
  # the same fingerprint here and never printed.
  RECORD_ACCOUNT="$(jq -r '.account_sha256 // empty' "${RECORD}")"
  if [ -z "${RECORD_ACCOUNT}" ]; then
    local legacy
    legacy="$(jq -r '.account_id // empty' "${RECORD}")"
    if [[ "${legacy}" =~ ^[0-9]{12}$ ]]; then
      RECORD_ACCOUNT="$(account_fingerprint "${legacy}")" || die 3 "could not compute the account fingerprint"
    fi
  fi
  [[ "${RECORD_ACCOUNT}" =~ ^[0-9a-f]{64}$ ]] || die 4 "refusing: the plan record holds no account fingerprint"

  [ "${renv}" = "${ENVIRONMENT}" ] || die 4 "refusing: record environment '${renv}' is not ${ENVIRONMENT}"
  [ "${rclean}" = "true" ] || die 4 "refusing: the plan was made from a dirty tree (review only)"
  [ "${rregion}" = "${EXPECTED_REGION}" ] || die 4 "refusing: record Region '${rregion}' is not ${EXPECTED_REGION}"
  case "${PLAN_FILE_REL}" in
    "${PLANS_SUBDIR}"/*.tfplan) ;;
    *) die 4 "refusing: the record names an unexpected plan file '${PLAN_FILE_REL}'" ;;
  esac
  case "${PLAN_FILE_REL}" in
    *..* | */*/*) die 4 "refusing: the record names an unexpected plan file '${PLAN_FILE_REL}'" ;;
  esac
  [ -f "${TF_DIR}/${PLAN_FILE_REL}" ] || die 4 "refusing: the saved plan ${TF_DIR}/${PLAN_FILE_REL} is missing"
  actual="$(sc_sha256 "${TF_DIR}/${PLAN_FILE_REL}")"
  info "argument sha256:  ${PLAN_SHA256_ARG}"
  info "plan file sha256: ${actual}"
  [ "${actual}" = "${PLAN_SHA256_ARG}" ] || die 4 "refusing: the plan file checksum differs from --plan-sha256"
}

check_plan_validity() {
  step "plan-validity"
  local rc=0
  plan_usable "${PLAN_COMMIT}" || rc=$?
  [ "${rc}" -eq 0 ] || {
    [ "${rc}" -eq 5 ] && die 5 "git failed while checking plan validity"
    die 4 "refusing: the plan is not usable at HEAD (reason above; make a new plan)"
  }
  info "plan commit ${PLAN_COMMIT} is HEAD or an ancestor, and no plan input (${SC_PLAN_INPUT_PATHS[*]}) changed since"
}

check_approval() {
  step "approval"
  require_plan_approval "${PLAN_SHA256_ARG}" ||
    die 4 "refusing: the plan is not approved (commit 'APPROVED-PLAN-SHA256: ${PLAN_SHA256_ARG}' in docs/agent-runs/phase-NN/human-gate.md or approvals.md)"
  info "approval line found at HEAD (it proves the line was committed, not who committed it)"
}

check_account() {
  step "account"
  local ident
  ident="$(aws_json sts get-caller-identity)" || exit "$?"
  ACCOUNT_ID="$(jq -r '.Account // empty' <<<"${ident}")"
  [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]] || die 5 "could not read the account from sts get-caller-identity"
  register_identifiers "${ACCOUNT_ID}" "" "" || die 5 "could not register the account"
  register_bucket_patterns "${NAME_PREFIX}" || die 5 "could not register the bucket name patterns"
  local caller_fp
  caller_fp="$(account_fingerprint "${ACCOUNT_ID}")" || die 3 "could not compute the account fingerprint"
  [ "${caller_fp}" = "${RECORD_ACCOUNT}" ] ||
    die 4 "refusing: caller account $(mask_account "${ACCOUNT_ID}") differs from the account of the plan record (fingerprint ${RECORD_ACCOUNT:0:12}...)"
  info "account $(mask_account "${ACCOUNT_ID}"), Region ${EXPECTED_REGION}, profile ${AWS_PROFILE:-<default credential chain>}"
}

tf_output_json() {
  local rc=0 out
  out="$(with_timeout "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" output -json -no-color)" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform output failed (status ${rc})"
  if [ -z "${out}" ]; then
    out='{}'
  fi
  printf '%s\n' "${out}"
}

PLAN_RUNTIME_ENABLED="false"
PLAN_IMAGE_TAG=""

check_plan_variables() {
  step "plan-variables"
  local rc=0 pj="${TMP_DIR}/plan.json"
  with_timeout "${SHOW_TIMEOUT}" terraform "-chdir=${TF_DIR}" show -json -no-color \
    "${PLAN_FILE_REL}" >"${pj}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform show timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform show -json failed (status ${rc})"
  PLAN_RUNTIME_ENABLED="$(jq -r '.variables.runtime_enabled.value // false' "${pj}")"
  PLAN_IMAGE_TAG="$(jq -r '.variables.image_tag.value // empty' "${pj}")"
  info "plan: runtime_enabled=${PLAN_RUNTIME_ENABLED} image_tag=${PLAN_IMAGE_TAG:-<none>}"
  jq -r '"plan counts: " + ([.resource_changes[]? | .change.actions | join(",")]
    | group_by(.) | map("\(.[0])=\(length)") | join(" "))' "${pj}"
}

IMAGE_REPOSITORY=""
IMAGE_DIGEST=""

check_image() {
  step "image-check"
  if [ "${PLAN_RUNTIME_ENABLED}" != "true" ]; then
    info "runtime off in this plan: no image needs to exist"
    return 0
  fi
  [[ "${PLAN_IMAGE_TAG}" =~ ^[0-9a-f]{40}$ ]] || die 4 "refusing: runtime on but image_tag is not a 40-hex build commit"
  if [ "${PLAN_IMAGE_TAG}" != "${BUILD_SHA}" ]; then
    warn "the plan's image_tag ${PLAN_IMAGE_TAG} is not the current build commit ${BUILD_SHA} (allowed: it only has to exist in ECR)"
  fi
  local outputs
  outputs="$(tf_output_json)" || exit "$?"
  IMAGE_REPOSITORY="$(jq -r '.ecr_repository_name.value // empty' <<<"${outputs}")"
  [ -n "${IMAGE_REPOSITORY}" ] || die 4 "refusing: no ECR repository in the current state (apply the runtime-off plan first)"
  # I-24: a missing tag (or repository) is a refusal (exit 4), not a failed
  # command (exit 5); describe-images reports it as ImageNotFoundException
  # (RepositoryNotFoundException), so this call does not go through aws_json.
  local images rc=0 errfile="${TMP_DIR}/ecr.err"
  images="$(with_timeout "${AWS_TIMEOUT}" aws --region "${EXPECTED_REGION}" \
    --cli-connect-timeout 10 --cli-read-timeout 60 --no-cli-pager --output json \
    ecr describe-images --repository-name "${IMAGE_REPOSITORY}" \
    --image-ids "imageTag=${PLAN_IMAGE_TAG}" 2>"${errfile}")" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    [ "${rc}" -eq 124 ] && die 7 "aws ecr describe-images timed out after ${AWS_TIMEOUT}s"
    if grep -q -E 'ImageNotFoundException|RepositoryNotFoundException' "${errfile}"; then
      die 4 "refusing: image tag ${PLAN_IMAGE_TAG} not found in ECR repository ${IMAGE_REPOSITORY} (run scripts/package.sh --publish first)"
    fi
    printf 'AWS-ERROR: %s\n' "$(deploy_mask "$(cat "${errfile}")")" >&2
    die 5 "aws ecr describe-images failed (status ${rc})"
  fi
  IMAGE_DIGEST="$(jq -r '.imageDetails[0].imageDigest // empty' <<<"${images}")"
  [[ "${IMAGE_DIGEST}" =~ ^sha256:[0-9a-f]{64}$ ]] || die 4 "refusing: image tag ${PLAN_IMAGE_TAG} not found in ECR"
  info "image ${IMAGE_REPOSITORY}:${PLAN_IMAGE_TAG} digest ${IMAGE_DIGEST}"
}

PREVIOUS_JSON='{}'

capture_previous() {
  step "previous-revisions"
  local outputs cluster services
  outputs="$(tf_output_json)" || exit "$?"
  cluster="$(jq -r '.ecs_cluster_name.value // empty' <<<"${outputs}")"
  if [ -z "${cluster}" ]; then
    info "no ECS cluster in the current state (first apply): no previous revisions"
    return 0
  fi
  local names=() role name
  for role in ${ROLES}; do
    name="$(jq -r --arg r "${role}" '.ecs_service_names.value[$r] // empty' <<<"${outputs}")"
    if [ -n "${name}" ]; then
      names+=("${name}")
    fi
  done
  if [ "${#names[@]}" -eq 0 ]; then
    info "no ECS service names in the current state: no previous revisions"
    return 0
  fi
  services="$(aws_json ecs describe-services --cluster "${cluster}" --services "${names[@]}")" || exit "$?"
  PREVIOUS_JSON="$(jq -c '[.services[] | {key: .serviceName,
    value: (.taskDefinition | split("/") | last)}] | from_entries' <<<"${services}")"
  info "previous revisions: ${PREVIOUS_JSON}"
}

apply_plan() {
  step "terraform-apply"
  local base text rc=0
  base="${PLAN_FILE_REL##*/}"
  base="${base%.tfplan}"
  text="${TF_DIR}/${PLANS_SUBDIR}/${base}.apply.txt"
  [ ! -e "${text}" ] || die 4 "refusing: ${text} exists (this plan was applied before; make a new plan)"
  APPLY_TEXT="${text}"
  info "full apply output follows, masked (the masked copy is saved to ${text})"
  with_timeout "${APPLY_TIMEOUT}" terraform "-chdir=${TF_DIR}" apply -input=false \
    -lock-timeout=60s -no-color "${PLAN_FILE_REL}" 2>&1 | mask_stream | tee "${text}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform apply timed out after ${APPLY_TIMEOUT}s; no release record written; run scripts/plan.sh to see the state"
  [ "${rc}" -eq 0 ] || die 5 "terraform apply failed (status ${rc}); no release record written; run scripts/plan.sh to see what was applied"
}

APPLY_TEXT=""
SERVICES_RECORD='{}'
CLUSTER=""
SERVICE_NAMES=()

# I-09: with the runtime off, Application Auto Scaling lowers the desired
# counts after the apply; wait (bounded) until every service is at desired 0
# and running 0.
wait_runtime_drained() {
  step "wait-runtime-off"
  local deadline now services busy
  deadline=$(($(date +%s) + STABLE_TIMEOUT))
  while :; do
    services="$(aws_json ecs describe-services --cluster "${CLUSTER}" --services "${SERVICE_NAMES[@]}")" || exit "$?"
    busy="$(jq -r '[.services[] | select(.desiredCount != 0 or .runningCount != 0)
      | "\(.serviceName) desired=\(.desiredCount) running=\(.runningCount)"] | join("; ")' <<<"${services}")"
    if [ -z "${busy}" ]; then
      info "runtime off: every service at desired 0 and running 0"
      return 0
    fi
    now="$(date +%s)"
    if [ "${now}" -ge "${deadline}" ]; then
      printf 'EVIDENCE: counts after %ss: %s\n' "${STABLE_TIMEOUT}" "${busy}" >&2
      die 7 "the services did not reach desired 0 and running 0 within ${STABLE_TIMEOUT}s (the apply happened; no release record written)"
    fi
    printf 'POLL: waiting for desired 0 and running 0: %s\n' "${busy}"
    sleep "${DRAIN_POLL_SECONDS}"
  done
}

# I-28: prints desired, running and pending counts of every service (names,
# counts and family:revision only; no ARN). Best effort: a failed read prints
# a note and returns 0 so the caller's own exit code stands.
print_service_counts() {
  local out rc=0
  out="$(with_timeout "${AWS_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
    --cli-read-timeout 60 --no-cli-pager --output json ecs describe-services --cluster "${CLUSTER}" \
    --services "${SERVICE_NAMES[@]}" 2>/dev/null)" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    printf 'EVIDENCE: service counts could not be read (status %s)\n' "${rc}" >&2
    return 0
  fi
  jq -r '.services[] | "EVIDENCE: service=\(.serviceName) desired=\(.desiredCount) running=\(.runningCount) pending=\(.pendingCount) revision=\(.taskDefinition | split("/") | last)"' \
    <<<"${out}" >&2 || true
}

verify_deployment() {
  step "verify-revision"
  local outputs
  outputs="$(tf_output_json)" || exit "$?"
  # I-21: the real bucket names exist now; register them so the release
  # record check (write_record_atomic) covers them, and check the masked
  # apply copy once more.
  register_identifiers "${ACCOUNT_ID}" "$(jq -r '.data_bucket_name.value // empty' <<<"${outputs}")" \
    "$(jq -r '.vector_bucket_name.value // empty' <<<"${outputs}")" || die 5 "could not register the identifiers"
  if [ -n "${APPLY_TEXT}" ] && ! check_no_identifiers "${APPLY_TEXT}"; then
    warn "the saved apply output ${APPLY_TEXT} still holds an identifier (listed above); do not share it"
  fi
  CLUSTER="$(jq -r '.ecs_cluster_name.value // empty' <<<"${outputs}")"
  [ -n "${CLUSTER}" ] || die 6 "no ecs_cluster_name output after apply"
  IMAGE_REPOSITORY="$(jq -r '.ecr_repository_name.value // empty' <<<"${outputs}")"
  local runtime_out
  runtime_out="$(jq -r '.runtime_enabled.value // false' <<<"${outputs}")"
  [ "${runtime_out}" = "${PLAN_RUNTIME_ENABLED}" ] || die 6 "output runtime_enabled=${runtime_out} differs from the plan"

  local role name
  SERVICE_NAMES=()
  for role in ${ROLES}; do
    name="$(jq -r --arg r "${role}" '.ecs_service_names.value[$r] // empty' <<<"${outputs}")"
    [ -n "${name}" ] || die 6 "no service name output for role ${role}"
    SERVICE_NAMES+=("${name}")
  done

  if [ "${PLAN_RUNTIME_ENABLED}" = "true" ]; then
    info "waiting for the services to become stable (at most ${STABLE_TIMEOUT}s)"
    local rc=0
    with_timeout "${STABLE_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 60 --no-cli-pager ecs wait services-stable --cluster "${CLUSTER}" \
      --services "${SERVICE_NAMES[@]}" || rc=$?
    if [ "${rc}" -ne 0 ]; then
      print_service_counts
      [ "${rc}" -eq 124 ] && die 7 "services did not become stable within ${STABLE_TIMEOUT}s (counts above)"
      die 6 "ecs wait services-stable failed (status ${rc}; counts above)"
    fi
  else
    wait_runtime_drained
    step "verify-revision"
  fi

  local services
  services="$(aws_json ecs describe-services --cluster "${CLUSTER}" --services "${SERVICE_NAMES[@]}")" || exit "$?"
  local failed=0
  local svc td_arn td_ref desired running td latest_ref status image image_tag digests digest_ok prev
  local tasks t task_arns
  for role in ${ROLES}; do
    name="$(jq -r --arg r "${role}" '.ecs_service_names.value[$r]' <<<"${outputs}")"
    svc="$(jq -c --arg n "${name}" '.services[] | select(.serviceName == $n)' <<<"${services}")"
    [ -n "${svc}" ] || die 6 "service ${name} not found in the cluster"
    td_arn="$(jq -r '.taskDefinition' <<<"${svc}")"
    td_ref="$(family_rev "${td_arn}")"
    desired="$(jq -r '.desiredCount' <<<"${svc}")"
    running="$(jq -r '.runningCount' <<<"${svc}")"
    td="$(aws_json ecs describe-task-definition --task-definition "${td_ref%:*}")" || exit "$?"
    latest_ref="$(jq -r '"\(.taskDefinition.family):\(.taskDefinition.revision)"' <<<"${td}")"
    status="$(jq -r '.taskDefinition.status' <<<"${td}")"
    image="$(jq -r --arg c "${CONTAINER_NAME}" '.taskDefinition.containerDefinitions[] | select(.name == $c) | .image' <<<"${td}")"
    image_tag="${image##*:}"
    digest_ok="not-applicable (runtime off, no task runs)"

    if [ "${td_ref}" != "${latest_ref}" ] || [ "${status}" != "ACTIVE" ]; then
      printf 'EVIDENCE: service=%s service_revision=%s latest_active=%s status=%s\n' \
        "${name}" "${td_ref}" "${latest_ref}" "${status}" >&2
      failed=1
    fi
    if [ "${PLAN_RUNTIME_ENABLED}" = "true" ]; then
      if [ "${image_tag}" != "${PLAN_IMAGE_TAG}" ]; then
        printf 'EVIDENCE: service=%s image_tag=%s expected=%s\n' "${name}" "${image_tag}" "${PLAN_IMAGE_TAG}" >&2
        failed=1
      fi
      # query, evidence and graph scale from min 1 when the runtime is on
      # (2b RISK R9: if Application Auto Scaling did not lift the count, this
      # fails loudly); the worker scales from 0 on queue depth.
      # I-28: services-stable passes a service at desired 0 = running 0, and
      # the digest check below has nothing to check over zero tasks, so an
      # ALB role must show desired >= 1 AND running >= 1 here, or the
      # deployment is not verified (exit 6). scripts/README.md "First
      # runtime-on deploy (D11)" gives the remedy.
      if [ "${role}" != "worker" ] && { [ "${desired}" -lt 1 ] || [ "${running}" -lt 1 ]; }; then
        printf 'EVIDENCE: service=%s desired=%s running=%s pending=%s with the runtime on (expected desired and running at least 1; I-28)\n' \
          "${name}" "${desired}" "${running}" "$(jq -r '.pendingCount' <<<"${svc}")" >&2
        failed=1
      fi
      task_arns=()
      tasks="$(aws_json ecs list-tasks --cluster "${CLUSTER}" --service-name "${name}" --desired-status RUNNING)" || exit "$?"
      while IFS= read -r t; do
        if [ -n "${t}" ]; then
          task_arns+=("${t}")
        fi
      done < <(jq -r '.taskArns[]' <<<"${tasks}")
      if [ "${#task_arns[@]}" -gt 0 ]; then
        digests="$(aws_json ecs describe-tasks --cluster "${CLUSTER}" --tasks "${task_arns[@]}")" || exit "$?"
        if jq -e --arg d "${IMAGE_DIGEST}" --arg c "${CONTAINER_NAME}" --arg td "${td_ref}" '
            [.tasks[] | (.taskDefinitionArn | split("/") | last) as $t
              | .containers[] | select(.name == $c) | (.imageDigest == $d and $t == $td)]
            | all' <<<"${digests}" >/dev/null; then
          digest_ok="verified (${#task_arns[@]} running task(s) on ${IMAGE_DIGEST})"
        else
          printf 'EVIDENCE: service=%s running tasks not all on %s with digest %s\n' \
            "${name}" "${td_ref}" "${IMAGE_DIGEST}" >&2
          jq -r --arg c "${CONTAINER_NAME}" '.tasks[] | "  task_definition=\(.taskDefinitionArn | split("/") | last) digest=\(.containers[] | select(.name == $c) | .imageDigest // "none")"' <<<"${digests}" >&2
          failed=1
          digest_ok="MISMATCH"
        fi
      else
        digest_ok="no running task (desired ${desired})"
        if [ "${desired}" -gt 0 ]; then
          printf 'EVIDENCE: service=%s desired=%s but no running task\n' "${name}" "${desired}" >&2
          failed=1
        fi
      fi
    elif [ "${desired}" -ne 0 ] || [ "${running}" -ne 0 ]; then
      printf 'EVIDENCE: service=%s desired=%s running=%s but the runtime is off\n' "${name}" "${desired}" "${running}" >&2
      failed=1
    fi

    prev="$(jq -r --arg n "${name}" '.[$n] // empty' <<<"${PREVIOUS_JSON}")"
    printf 'VERIFY: service=%s revision=%s desired=%s running=%s image_tag=%s digest=%s\n' \
      "${name}" "${td_ref}" "${desired}" "${running}" "${image_tag}" "${digest_ok}"
    SERVICES_RECORD="$(jq -c --arg n "${name}" --arg td "${td_ref}" --arg prev "${prev}" \
      --argjson desired "${desired}" --argjson running "${running}" --arg tag "${image_tag}" \
      --arg digest "${digest_ok}" \
      '. + {($n): {task_definition: $td,
                   previous_task_definition: (if $prev == "" then null else $prev end),
                   desired_count: $desired, running_count: $running,
                   image_tag: $tag, digest_check: $digest}}' <<<"${SERVICES_RECORD}")"
  done
  [ "${failed}" -eq 0 ] || die 6 "deployed revision NOT verified (evidence above); the apply happened; no release record written"
  info "deployed revision verified for every service"
}

write_release_record() {
  step "release-record"
  mkdir -p -- "${RELEASES_DIR}"
  local attempt file
  attempt="$(new_attempt_id)"
  file="${RELEASES_DIR}/${attempt}.json"
  jq -n \
    --arg kind "scholarchain-release" \
    --arg environment "${ENVIRONMENT}" \
    --arg attempt "${attempt}" \
    --arg head "${HEAD_SHA}" \
    --arg plan_commit "${PLAN_COMMIT}" \
    --arg build_commit "${BUILD_SHA}" \
    --arg plan_id "${PLAN_ID}" \
    --arg plan_sha256 "${PLAN_SHA256_ARG}" \
    --arg region "${EXPECTED_REGION}" \
    --arg applied_utc "$(sc_utc_now)" \
    --argjson runtime_enabled "${PLAN_RUNTIME_ENABLED}" \
    --arg repository "${IMAGE_REPOSITORY}" \
    --arg tag "${PLAN_IMAGE_TAG}" \
    --arg digest "${IMAGE_DIGEST}" \
    --arg cluster "${CLUSTER}" \
    --argjson services "${SERVICES_RECORD}" \
    '{kind: $kind, version: 2, environment: $environment, attempt_id: $attempt,
      commit: $head, head: $head, plan_commit: $plan_commit, build_commit: $build_commit,
      plan_id: $plan_id, plan_sha256: $plan_sha256, region: $region, applied_utc: $applied_utc,
      runtime_enabled: $runtime_enabled,
      image: {repository: $repository,
              tag: (if $tag == "" then null else $tag end),
              digest: (if $digest == "" then null else $digest end)},
      ecs_cluster: $cluster, services: $services, verified: true}' |
    write_record_atomic "${file}" || die 6 "could not write the release record ${file} (reason above)"
  printf 'RELEASE-RECORD: %s\n' "${file}"
  info "commit the release record before the next script (the human commits)"
}

main() {
  parse_args "$@"
  enter_repo_root
  check_local_prerequisites
  check_clean_tree
  find_plan
  check_plan_validity
  check_approval
  check_cloud_prerequisites
  check_account
  check_plan_variables
  check_image
  capture_previous
  apply_plan
  verify_deployment
  write_release_record
  step "done"
}

main "$@"
