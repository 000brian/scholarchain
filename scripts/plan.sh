#!/usr/bin/env bash
# scripts/plan.sh - make and record a reviewed Terraform plan of the ScholarChain
# course environment. It NEVER applies. Owner: infra-engineer. Run by the human.
#
# Status (Phase 12): static: shellcheck clean; runtime: untested (Phase 13/14
# runs it); human execution required.

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

What it does (its whole contract):
  1. terraform init of infra/terraform/environments/course (local backend,
     -input=false, the committed lock file read-only).
  2. terraform plan with -input=false and -var-file=course.tfvars (plus the
     optional -var values above), saved to
     infra/terraform/environments/course/plans/<commit>.tfplan
     (<commit>-dirty.tfplan when the working tree has uncommitted changes).
  3. Shows the FULL plan output unchanged and keeps a copy next to the plan
     file (.plan.txt). The raw output can contain the account ID; mask it
     yourself before you share it.
  4. Records, next to the plan file: its sha256, the git commit, whether the
     tree was clean, the account and the Region (.record.json), and a summary
     built from `terraform show -json` of the plan file (.summary.txt):
     create/update/delete/replace counts, IAM and resource-policy changes,
     network-access changes, and actions on stateful resources. The printed
     summary masks the account ID.

What it never does:
  - It never applies, never changes AWS, never edits Terraform state.
  - It writes nothing in the repository outside
    infra/terraform/environments/course/plans/ (gitignored); the .terraform/
    folder that init maintains is gitignored too.
  - It prints no secret: the four Secrets Manager containers have no values
    in Terraform.

deploy.sh accepts only a plan recorded here from a CLEAN tree at the current
commit, and only with its sha256. A plan from a dirty tree is for review only.

Options:
  --runtime-enabled true|false   -var=runtime_enabled=... (Phase 14 second plan)
  --image-tag SHA40              -var=image_tag=... (40 lowercase hex git SHA)
  --graph-run-id ID              -var=graph_run_id=... (run:v1:<32 hex>)
  --cleanup-mode true|false      -var=cleanup_mode=... (README "Cleanup mode")

Requirements: bash, git, terraform, aws (CLI v2), jq, shasum or sha256sum,
timeout or gtimeout (GNU coreutils); AWS credentials of the deploy profile in
the environment (for example AWS_PROFILE); owner set in local.auto.tfvars.

Exit codes: 0 plan written and recorded; 2 usage error; 3 missing
prerequisite; 4 refused by a safety check; 5 terraform or AWS command failed;
7 timed out.

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
trap on_exit EXIT

mask_text() {
  local s="$1"
  if [ -n "${ACCOUNT_ID}" ]; then
    s="${s//"${ACCOUNT_ID}"/<account>}"
  fi
  printf '%s' "${s}"
}

masked_account() {
  local a="$1"
  if [ "${#a}" -ge 4 ]; then
    printf '********%s' "${a: -4}"
  else
    printf '<unknown>'
  fi
}

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
  [ -f "${TF_DIR}/.terraform.lock.hcl" ] || die 3 "missing ${TF_DIR}/.terraform.lock.hcl (the human runs init once)"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-plan.XXXXXX")"
}

COMMIT=""
TREE_CLEAN="false"
PLAN_NAME=""

read_git_state() {
  step "git-state"
  COMMIT="$(git rev-parse --verify HEAD)" || die 3 "not a git repository with a commit"
  local dirty
  dirty="$(git status --porcelain --untracked-files=normal)" || die 5 "git status failed"
  if [ -z "${dirty}" ]; then
    TREE_CLEAN="true"
    PLAN_NAME="${COMMIT}"
    info "commit ${COMMIT}, tree clean"
  else
    TREE_CLEAN="false"
    PLAN_NAME="${COMMIT}-dirty"
    warn "the working tree has uncommitted changes; this plan is for review only (deploy.sh refuses it)"
    git status --short --untracked-files=normal >&2
  fi
}

read_account() {
  step "account"
  local ident
  ident="$(aws_json sts get-caller-identity)" || exit "$?"
  ACCOUNT_ID="$(jq -r '.Account // empty' <<<"${ident}")"
  [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]] || die 5 "could not read the account from sts get-caller-identity"
  info "account $(masked_account "${ACCOUNT_ID}"), profile ${AWS_PROFILE:-<default credential chain>}"
}

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
  info "full plan output follows (also saved to ${text})"
  with_timeout "${PLAN_TIMEOUT}" terraform "-chdir=${TF_DIR}" plan -input=false \
    -lock-timeout=60s -no-color -detailed-exitcode -var-file=course.tfvars \
    ${VAR_ARGS[@]+"${VAR_ARGS[@]}"} "-out=${PLAN_FILE_REL}" 2>&1 | tee "${text}" || rc=$?
  case "${rc}" in
    0) info "plan: no changes" ;;
    2) info "plan: changes present" ;;
    124) die 7 "terraform plan timed out after ${PLAN_TIMEOUT}s" ;;
    *) die 5 "terraform plan failed (status ${rc}); see the output above" ;;
  esac
  [ -s "${PLAN_FILE}" ] || die 5 "terraform plan wrote no plan file"
  PLAN_SHA256="$(sha256_of "${PLAN_FILE}")"
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
  {
    printf 'PLAN SUMMARY (%s environment)\n' "${ENVIRONMENT}"
    printf 'plan file: %s/%s\n' "${TF_DIR}" "${PLAN_FILE_REL}"
    printf 'plan sha256: %s\n' "${PLAN_SHA256}"
    printf 'commit: %s\n' "${COMMIT}"
    printf 'tree clean: %s\n' "${TREE_CLEAN}"
    printf 'account: %s\n' "$(masked_account "${ACCOUNT_ID}")"
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
  cat -- "${summary}"

  local record="${TF_DIR}/${PLANS_SUBDIR}/${PLAN_NAME}.record.json"
  jq -n \
    --arg kind "scholarchain-plan-record" \
    --arg environment "${ENVIRONMENT}" \
    --arg commit "${COMMIT}" \
    --argjson tree_clean "${TREE_CLEAN}" \
    --arg plan_file "${PLAN_FILE_REL}" \
    --arg plan_sha256 "${PLAN_SHA256}" \
    --arg account_id "${ACCOUNT_ID}" \
    --arg region "${region}" \
    --arg created_utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson overrides "${OVERRIDES_JSON}" \
    '{kind: $kind, version: 1, environment: $environment, commit: $commit,
      tree_clean: $tree_clean, plan_file: $plan_file, plan_sha256: $plan_sha256,
      account_id: $account_id, region: $region, created_utc: $created_utc,
      variable_overrides: $overrides}' >"${record}"
  info "record written: ${record} (gitignored; holds the unmasked account for deploy.sh)"
}

main() {
  parse_args "$@"
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
  cd "${REPO_ROOT}"
  check_prerequisites
  read_git_state
  read_account
  terraform_init
  terraform_plan
  summarize_plan
  step "done"
  printf 'PLAN-SHA256: %s\n' "${PLAN_SHA256}"
  printf 'PLAN-COMMIT: %s\n' "${COMMIT}"
  printf 'PLAN-TREE-CLEAN: %s\n' "${TREE_CLEAN}"
  info "nothing was applied. Sign the checksum in human-gate.md; deploy-engineer runs scripts/deploy.sh --plan-sha256 <checksum>."
}

main "$@"
