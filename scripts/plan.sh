#!/usr/bin/env bash
# scripts/plan.sh - make and record a reviewed Terraform plan of the ScholarChain
# course environment. It NEVER applies. Owner: infra-engineer. Run by the human.
#
# Status (Phase 13): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="plan.sh"
readonly ENVIRONMENT="course"
readonly EXPECTED_REGION="us-east-1"
readonly TF_DIR="infra/terraform/environments/course"
readonly PLANS_SUBDIR="plans"
readonly INIT_TIMEOUT=600
readonly PLAN_TIMEOUT=1800
readonly SHOW_TIMEOUT=300
readonly AWS_TIMEOUT=90

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
ACCOUNT_ID=""

usage() {
  cat <<'EOF'
Usage: scripts/plan.sh [--runtime-enabled true|false] [--image-tag SHA40]
                       [--graph-run-id run:v1:HEX32] [--cleanup-mode true|false]
       scripts/plan.sh --help

Run it from the repository root (bash scripts/plan.sh); it refuses elsewhere.

What it does (its whole contract):
  1. terraform init of infra/terraform/environments/course (local backend,
     -input=false, the committed lock file read-only).
  2. terraform plan with -input=false and -var-file=course.tfvars (plus the
     optional -var values above), saved as
     infra/terraform/environments/course/plans/PLAN_COMMIT-PLAN_ID.tfplan
     PLAN_COMMIT is HEAD (the commit planned from); PLAN_ID is a new attempt
     ID (UTC time, short HEAD, 4 random hex), so several plans per commit
     (runtime off, runtime on, cleanup mode) never collide. A plan from a
     dirty tree is named PLAN_COMMIT-PLAN_ID-dirty.tfplan, is for review
     only, and deploy.sh refuses it.
  3. Shows the full plan output, every line masked (the account ID as
     <account>; bucket names of this environment, known or shaped like
     scholarchain-course-data-<digits> or scholarchain-course-vectors-<8 hex>,
     as <data-bucket> and <vector-bucket>), and keeps that MASKED copy next
     to the plan file (.plan.txt). The unmasked plan exists only inside the
     binary .tfplan file (terraform show reads it).
  4. Records, next to the plan file, under the same base name: its sha256,
     plan_commit, build_commit (the newest commit that changed a build
     input; scripts/lib/identity.sh), plan_id, whether the tree was clean,
     the account as account_sha256 (sha256 of
     "scholarchain-account-v1:ACCOUNT_ID"; deploy.sh compares it with the
     caller's) and account_masked (********NNNN), never the account ID, and
     the Region (.record.json), and a summary built from
     `terraform show -json` of the plan file (.summary.txt):
     create/update/delete/replace counts, IAM and resource-policy changes,
     network-access changes, and actions on stateful resources. The summary
     masks the account ID. The record, summary and .plan.txt are checked for
     the account ID (grep -F) before the plan is reported (exit 6 if found).
     Nothing is ever overwritten.

Plan validity (Phase 13 "Two identity rules"; scripts/lib/identity.sh
plan_usable): deploy.sh finds a plan by its sha256 and uses it only if the
tree is clean, PLAN_COMMIT is HEAD or an ancestor of HEAD, and no plan input
changed since PLAN_COMMIT. The plan inputs (SC_PLAN_INPUT_PATHS in
scripts/lib/identity.sh) are infra/terraform/ plus every repository file
outside it that Terraform reads, today
modules/storage/src/main/resources/s3vectors/index-spec-v1.json. So later
commits that change no plan input (the human gate commit with the
APPROVED-PLAN-SHA256 line, a release record, a script fix, evidence) keep the
plan usable; ANY change to a plan input makes it unusable (plan again).
Terraform itself still refuses a saved plan whose state has changed.

What it never does:
  - It never applies, never changes AWS, never edits Terraform state.
  - It writes nothing in the repository outside
    infra/terraform/environments/course/plans/ (gitignored); the .terraform/
    folder that init maintains is gitignored too.
  - It prints no secret: the four Secrets Manager containers have no values
    in Terraform.

Options:
  --runtime-enabled true|false   -var=runtime_enabled=... (Phase 14 second plan)
  --image-tag SHA40              -var=image_tag=... (40 lowercase hex: the build
                                 commit that package.sh --publish pushed)
  --graph-run-id ID              -var=graph_run_id=... (run:v1:<32 hex>)
  --cleanup-mode true|false      -var=cleanup_mode=... (README "Cleanup mode")

Requirements: bash, git, terraform, aws (CLI v2), jq, shasum or sha256sum,
timeout or gtimeout (GNU coreutils); AWS credentials in the environment (the
human's admin profile before the first apply, else the deploy profile; for
example AWS_PROFILE); owner set in local.auto.tfvars; network access to the
Terraform registry for init.

Exit codes: 0 plan written and recorded; 2 usage error; 3 missing
prerequisite; 4 refused by a safety check (not at the repository root, plan
Region, a plan name that exists); 5 terraform or AWS command failed; 6 a
written plan file (.record.json, .summary.txt, .plan.txt) still holds the
account ID; 7 timed out.

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

# Masks with the helper's mask_text once the helper is loaded (enter_repo_root
# runs before any AWS call) and the account is registered (read_account).
plan_mask() {
  mask_text "$1"
}

# Name prefix of this environment's resources (project-environment,
# environments/course/main.tf locals); used only for bucket name patterns.
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
    printf 'AWS-ERROR: %s\n' "$(plan_mask "$(cat "${errfile}")")" >&2
    die 5 "aws $1 $2 failed (status ${rc})"
  fi
  printf '%s\n' "${out}"
}

# ---- arguments -----------------------------------------------------------------

VAR_ARGS=()
OVERRIDES_JSON='{}'

add_override() {
  command -v jq >/dev/null 2>&1 || die 3 "required tool not found: jq"
  VAR_ARGS+=("-var=$1=$2")
  OVERRIDES_JSON="$(jq -c --arg k "$1" --arg v "$2" '. + {($k): $v}' <<<"${OVERRIDES_JSON}")"
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --runtime-enabled | --cleanup-mode)
        [ "$#" -ge 2 ] || die 2 "$1 needs a value (true or false)"
        case "$2" in
          true | false) ;;
          *) die 2 "$1 must be true or false" ;;
        esac
        if [ "$1" = "--runtime-enabled" ]; then
          add_override runtime_enabled "$2"
        else
          add_override cleanup_mode "$2"
        fi
        shift 2
        ;;
      --image-tag)
        [ "$#" -ge 2 ] || die 2 "--image-tag needs a value"
        [[ "$2" =~ ^[0-9a-f]{40}$ ]] || die 2 "--image-tag must be a 40-character lowercase git SHA"
        add_override image_tag "$2"
        shift 2
        ;;
      --graph-run-id)
        [ "$#" -ge 2 ] || die 2 "--graph-run-id needs a value"
        [[ "$2" =~ ^run:v1:[0-9a-f]{32}$ ]] || die 2 "--graph-run-id must look like run:v1:<32 lowercase hex>"
        add_override graph_run_id "$2"
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
}

# ---- steps ---------------------------------------------------------------------

check_prerequisites() {
  step "prerequisites"
  local tool
  for tool in git terraform aws jq cut; do
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
  [ -f "${TF_DIR}/course.tfvars" ] || die 3 "missing ${TF_DIR}/course.tfvars"
  [ -f "${TF_DIR}/.terraform.lock.hcl" ] || die 3 "missing ${TF_DIR}/.terraform.lock.hcl"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-plan.XXXXXX")"
}

PLAN_COMMIT=""
BUILD_SHA=""
PLAN_ID=""
TREE_CLEAN="false"
PLAN_NAME=""

read_git_state() {
  step "git-state"
  PLAN_COMMIT="$(head_commit)" || die 3 "not a git repository with a commit"
  BUILD_SHA="$(build_commit)" || die 3 "cannot determine the build commit"
  PLAN_ID="$(new_attempt_id)"
  local dirty
  dirty="$(tree_dirty_flag)" || die 5 "git status failed"
  if [ "${dirty}" = "false" ]; then
    TREE_CLEAN="true"
    PLAN_NAME="${PLAN_COMMIT}-${PLAN_ID}"
    info "plan commit ${PLAN_COMMIT}, build commit ${BUILD_SHA}, tree clean"
  else
    TREE_CLEAN="false"
    PLAN_NAME="${PLAN_COMMIT}-${PLAN_ID}-dirty"
    warn "the working tree has uncommitted changes; this plan is for review only (deploy.sh refuses it)"
    git status --short --untracked-files=normal >&2
  fi
  local ext
  for ext in tfplan record.json summary.txt plan.txt; do
    [ ! -e "${TF_DIR}/${PLANS_SUBDIR}/${PLAN_NAME}.${ext}" ] ||
      die 4 "refusing: ${TF_DIR}/${PLANS_SUBDIR}/${PLAN_NAME}.${ext} exists (records are never overwritten)"
  done
  info "plan id ${PLAN_ID}"
}

read_account() {
  step "account"
  local ident
  ident="$(aws_json sts get-caller-identity)" || exit "$?"
  ACCOUNT_ID="$(jq -r '.Account // empty' <<<"${ident}")"
  [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]] || die 5 "could not read the account from sts get-caller-identity"
  # I-21: from here on, everything printed or saved is masked.
  register_identifiers "${ACCOUNT_ID}" "" "" || die 5 "could not register the account"
  register_bucket_patterns "${NAME_PREFIX}" || die 5 "could not register the bucket name patterns"
  ACCOUNT_FP="$(account_fingerprint "${ACCOUNT_ID}")" || die 3 "could not compute the account fingerprint"
  info "account $(mask_account "${ACCOUNT_ID}"), profile ${AWS_PROFILE:-<default credential chain>}"
}

ACCOUNT_FP=""

terraform_init() {
  step "terraform-init"
  local rc=0
  with_timeout "${INIT_TIMEOUT}" terraform "-chdir=${TF_DIR}" init -input=false \
    -lockfile=readonly -no-color || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform init timed out after ${INIT_TIMEOUT}s"
  [ "${rc}" -eq 0 ] || die 5 "terraform init failed (status ${rc})"
}

PLAN_FILE_REL=""
PLAN_FILE=""
PLAN_SHA256=""

terraform_plan() {
  step "terraform-plan"
  mkdir -p -- "${TF_DIR}/${PLANS_SUBDIR}"
  PLAN_FILE_REL="${PLANS_SUBDIR}/${PLAN_NAME}.tfplan"
  PLAN_FILE="${TF_DIR}/${PLAN_FILE_REL}"
  local text="${TF_DIR}/${PLANS_SUBDIR}/${PLAN_NAME}.plan.txt"
  local rc=0
  info "full plan output follows, masked (the masked copy is saved to ${text})"
  with_timeout "${PLAN_TIMEOUT}" terraform "-chdir=${TF_DIR}" plan -input=false \
    -lock-timeout=60s -no-color -detailed-exitcode -var-file=course.tfvars \
    ${VAR_ARGS[@]+"${VAR_ARGS[@]}"} "-out=${PLAN_FILE_REL}" 2>&1 | mask_stream | tee "${text}" || rc=$?
  case "${rc}" in
    0) info "plan: no changes" ;;
    2) info "plan: changes present" ;;
    124) die 7 "terraform plan timed out after ${PLAN_TIMEOUT}s" ;;
    *) die 5 "terraform plan failed (status ${rc}); see the output above" ;;
  esac
  [ -s "${PLAN_FILE}" ] || die 5 "terraform plan wrote no plan file"
  PLAN_SHA256="$(sc_sha256 "${PLAN_FILE}")"
}

PLAN_JSON=""

summarize_plan() {
  step "plan-summary"
  PLAN_JSON="${TMP_DIR}/plan.json"
  local rc=0
  with_timeout "${SHOW_TIMEOUT}" terraform "-chdir=${TF_DIR}" show -json -no-color \
    "${PLAN_FILE_REL}" >"${PLAN_JSON}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform show timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform show -json failed (status ${rc})"

  local region
  region="$(jq -r '.variables.aws_region.value // empty' "${PLAN_JSON}")"
  [ "${region}" = "${EXPECTED_REGION}" ] || die 4 "plan Region is '${region}', expected ${EXPECTED_REGION}"

  local summary="${TF_DIR}/${PLANS_SUBDIR}/${PLAN_NAME}.summary.txt"
  # The summary text holds addresses and actions only (no values), plus the
  # masked account.
  (
    set -o noclobber
    {
      printf 'PLAN SUMMARY (%s environment)\n' "${ENVIRONMENT}"
      printf 'plan file: %s/%s\n' "${TF_DIR}" "${PLAN_FILE_REL}"
      printf 'plan sha256: %s\n' "${PLAN_SHA256}"
      printf 'plan commit: %s\n' "${PLAN_COMMIT}"
      printf 'build commit: %s\n' "${BUILD_SHA}"
      printf 'plan id: %s\n' "${PLAN_ID}"
      printf 'tree clean: %s\n' "${TREE_CLEAN}"
      printf 'account: %s\n' "$(mask_account "${ACCOUNT_ID}")"
      printf 'region: %s\n' "${region}"
      printf 'variable overrides: %s\n' "${OVERRIDES_JSON}"
      jq -r '
        def kind:
          .change.actions as $a
          | if ($a | index("delete")) and ($a | index("create")) then "replace"
            elif $a == ["create"] then "create"
            elif $a == ["update"] then "update"
            elif $a == ["delete"] then "delete"
            elif $a == ["read"] then "read"
            else "no-op" end;
        def iam: (.type | test("^aws_iam_"))
          or (.type | IN("aws_s3_bucket_policy", "aws_sqs_queue_policy",
                "aws_sqs_queue_redrive_allow_policy", "aws_sns_topic_policy",
                "aws_s3vectors_vector_bucket_policy", "aws_s3_bucket_public_access_block",
                "aws_secretsmanager_secret"));
        def net: .type | IN("aws_security_group", "aws_default_security_group",
                "aws_vpc_security_group_ingress_rule", "aws_vpc_security_group_egress_rule",
                "aws_lb", "aws_lb_listener", "aws_apigatewayv2_api", "aws_apigatewayv2_stage",
                "aws_apigatewayv2_vpc_link", "aws_nat_gateway", "aws_eip");
        def stateful: .type | IN("aws_s3_bucket", "aws_s3_bucket_versioning", "aws_dynamodb_table",
                "aws_neptune_cluster", "aws_neptune_cluster_instance", "aws_s3vectors_vector_bucket",
                "aws_s3vectors_index", "aws_ecr_repository", "aws_secretsmanager_secret",
                "aws_sqs_queue", "aws_cloudwatch_log_group");
        def line: "  \(.change.actions | join(",")) \(.address)";
        [.resource_changes[]? | . + {k: kind}] as $rc
        | ($rc | map(select(.k != "no-op" and .k != "read"))) as $ch
        | "counts: create=\($rc | map(select(.k == "create")) | length) update=\($rc | map(select(.k == "update")) | length) delete=\($rc | map(select(.k == "delete")) | length) replace=\($rc | map(select(.k == "replace")) | length) no-op=\($rc | map(select(.k == "no-op")) | length)",
          "terraform style: add=\($rc | map(select(.k == "create" or .k == "replace")) | length) change=\($rc | map(select(.k == "update")) | length) destroy=\($rc | map(select(.k == "delete" or .k == "replace")) | length)",
          "IAM and resource-policy changes:",
          ($ch | map(select(iam)) | if length == 0 then "  none" else .[] | line end),
          "network-access changes:",
          ($ch | map(select(net)) | if length == 0 then "  none" else .[] | line end),
          "stateful-resource actions:",
          ($ch | map(select(stateful)) | if length == 0 then "  none" else .[] | line end),
          "ATTENTION stateful delete or replace:",
          ($ch | map(select(stateful and (.k == "delete" or .k == "replace")))
            | if length == 0 then "  none" else .[] | line end)
      ' "${PLAN_JSON}"
    } >"${summary}"
  ) || die 5 "could not write ${summary}"
  cat -- "${summary}"

  local record="${TF_DIR}/${PLANS_SUBDIR}/${PLAN_NAME}.record.json"
  (
    set -o noclobber
    jq -n \
      --arg kind "scholarchain-plan-record" \
      --arg environment "${ENVIRONMENT}" \
      --arg plan_commit "${PLAN_COMMIT}" \
      --arg build_commit "${BUILD_SHA}" \
      --arg plan_id "${PLAN_ID}" \
      --argjson tree_clean "${TREE_CLEAN}" \
      --arg plan_file "${PLAN_FILE_REL}" \
      --arg plan_sha256 "${PLAN_SHA256}" \
      --arg account_sha256 "${ACCOUNT_FP}" \
      --arg account_masked "$(mask_account "${ACCOUNT_ID}")" \
      --arg region "${region}" \
      --arg created_utc "$(sc_utc_now)" \
      --argjson overrides "${OVERRIDES_JSON}" \
      '{kind: $kind, version: 3, environment: $environment,
        plan_commit: $plan_commit, commit: $plan_commit, build_commit: $build_commit,
        plan_id: $plan_id, tree_clean: $tree_clean, plan_file: $plan_file,
        plan_sha256: $plan_sha256, account_sha256: $account_sha256,
        account_masked: $account_masked, region: $region,
        created_utc: $created_utc, variable_overrides: $overrides}' >"${record}"
  ) || die 5 "could not write ${record}"
  # I-21: nothing persisted next to the plan may hold the account ID.
  check_no_identifiers "${record}" "${summary}" "${TF_DIR}/${PLANS_SUBDIR}/${PLAN_NAME}.plan.txt" ||
    die 6 "a plan file still holds the account ID (listed above); do not share or archive it"
  info "record written: ${record} (gitignored; account kept as a fingerprint for deploy.sh, never the ID)"
}

main() {
  parse_args "$@"
  enter_repo_root
  check_prerequisites
  read_git_state
  read_account
  terraform_init
  terraform_plan
  summarize_plan
  step "done"
  printf 'PLAN-SHA256: %s\n' "${PLAN_SHA256}"
  printf 'PLAN-COMMIT: %s\n' "${PLAN_COMMIT}"
  printf 'PLAN-ID: %s\n' "${PLAN_ID}"
  printf 'PLAN-TREE-CLEAN: %s\n' "${TREE_CLEAN}"
  if [ "${TREE_CLEAN}" = "true" ]; then
    info "nothing was applied. To approve, commit the line 'APPROVED-PLAN-SHA256: ${PLAN_SHA256}' in docs/agent-runs/phase-NN/human-gate.md or approvals.md; then deploy-engineer runs scripts/deploy.sh --plan-sha256 ${PLAN_SHA256}"
  else
    info "nothing was applied. This plan is from a dirty tree: review only, deploy.sh refuses it."
  fi
}

main "$@"
