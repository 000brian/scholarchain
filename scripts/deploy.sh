#!/usr/bin/env bash
# scripts/deploy.sh - apply ONE saved, recorded and signed plan of the
# ScholarChain course environment, verify the deployed ECS revisions, and
# write a release record under releases/. Owner: infra-engineer (author);
# executed by deploy-engineer after the human signs the plan checksum.
#
# Status (Phase 12): static: shellcheck clean; runtime: untested (Phase 13/14
# runs it); human execution required.

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
readonly ROLES="query evidence graph worker"
readonly CONTAINER_NAME="app"

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
ACCOUNT_ID=""
REPO_ROOT=""

usage() {
  cat <<'EOF'
Usage: scripts/deploy.sh --plan-sha256 <64 hex>
       scripts/deploy.sh --help

What it does (its whole contract):
  1. Refuses a dirty working tree (any change or untracked file that git shows).
  2. Refuses unless --plan-sha256 equals BOTH the sha256 of the saved plan
     file infra/terraform/environments/course/plans/<HEAD>.tfplan AND the
     checksum plan.sh recorded for the current commit (<HEAD>.record.json,
     made from a clean tree). This proves the file is the one that was
     reviewed. The approval itself is the human's signature in human-gate.md,
     which deploy-engineer checks BEFORE running this script; the script
     cannot see that signature.
  3. Refuses unless the AWS account and Region equal the recorded ones, and,
     when the plan turns the runtime on, unless the plan's image_tag is a
     40-hex git SHA that exists in the ECR repository.
  4. Applies only that saved plan file (terraform apply -input=false <plan>);
     Terraform itself refuses a saved plan that is stale. The full apply
     output is shown and kept next to the plan (.apply.txt).
  5. Verifies the deployed revision: every ECS service (query, evidence,
     graph, worker) runs the latest ACTIVE revision of its task-definition
     family, its image tag equals the plan's image tag, and, when the runtime
     is on, the services are stable and every running task's image digest
     equals the ECR digest of that tag.
  6. Writes a release record releases/course/<UTC time>-<short commit>.json:
     commit, plan checksum, Region, image repository/tag/digest, and per
     service the deployed and the previous task definition (family:revision).
     It holds no account ID, ARN, bucket name or secret.

It never edits Terraform state by hand, never runs any other plan, and asks
no question. If the apply fails, no release record is written; run plan.sh
again to see what was applied.

Requirements: bash, git, terraform, aws (CLI v2), jq (1.6+), shasum or
sha256sum, timeout or gtimeout (GNU coreutils); AWS credentials of the deploy
profile in the environment.

Exit codes: 0 applied, verified and recorded; 2 usage error; 3 missing
prerequisite; 4 refused by a safety check (dirty tree, checksum, account,
Region, image); 5 terraform or AWS command failed; 6 deployed revision not
verified (the apply happened; see the evidence printed); 7 timed out.

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
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-deploy.XXXXXX")"
}

COMMIT=""

check_clean_tree() {
  step "clean-tree"
  COMMIT="$(git rev-parse --verify HEAD)" || die 3 "not a git repository with a commit"
  local dirty
  dirty="$(git status --porcelain --untracked-files=normal)" || die 5 "git status failed"
  if [ -n "${dirty}" ]; then
    git status --short --untracked-files=normal >&2
    die 4 "refusing: the working tree is dirty (commit or stash first; the plan must match a commit)"
  fi
  info "commit ${COMMIT}, tree clean"
}

PLAN_FILE_REL=""
RECORD_ACCOUNT=""

check_plan_checksum() {
  step "plan-checksum"
  local record="${TF_DIR}/${PLANS_SUBDIR}/${COMMIT}.record.json"
  PLAN_FILE_REL="${PLANS_SUBDIR}/${COMMIT}.tfplan"
  local plan_file="${TF_DIR}/${PLAN_FILE_REL}"
  [ -f "${record}" ] || die 4 "refusing: no plan record for commit ${COMMIT} (run scripts/plan.sh on a clean tree)"
  [ -f "${plan_file}" ] || die 4 "refusing: no saved plan file ${plan_file}"

  local kind rcommit rclean rsha rfile renv rregion
  kind="$(jq -r '.kind // empty' "${record}")"
  renv="$(jq -r '.environment // empty' "${record}")"
  rcommit="$(jq -r '.commit // empty' "${record}")"
  rclean="$(jq -r '.tree_clean' "${record}")"
  rsha="$(jq -r '.plan_sha256 // empty' "${record}")"
  rfile="$(jq -r '.plan_file // empty' "${record}")"
  rregion="$(jq -r '.region // empty' "${record}")"
  RECORD_ACCOUNT="$(jq -r '.account_id // empty' "${record}")"

  [ "${kind}" = "scholarchain-plan-record" ] || die 4 "refusing: ${record} is not a plan record"
  [ "${renv}" = "${ENVIRONMENT}" ] || die 4 "refusing: record environment '${renv}' is not ${ENVIRONMENT}"
  [ "${rcommit}" = "${COMMIT}" ] || die 4 "refusing: record commit differs from HEAD"
  [ "${rclean}" = "true" ] || die 4 "refusing: the plan was made from a dirty tree"
  [ "${rfile}" = "${PLAN_FILE_REL}" ] || die 4 "refusing: record names another plan file (${rfile})"
  [ "${rregion}" = "${EXPECTED_REGION}" ] || die 4 "refusing: record Region '${rregion}' is not ${EXPECTED_REGION}"

  local actual
  actual="$(sha256_of "${plan_file}")"
  info "argument sha256: ${PLAN_SHA256_ARG}"
  info "plan file sha256: ${actual}"
  info "recorded sha256:  ${rsha}"
  [ "${actual}" = "${PLAN_SHA256_ARG}" ] || die 4 "refusing: the plan file checksum differs from --plan-sha256"
  [ "${rsha}" = "${PLAN_SHA256_ARG}" ] || die 4 "refusing: the recorded checksum differs from --plan-sha256"
}

check_account() {
  step "account"
  local ident
  ident="$(aws_json sts get-caller-identity)" || exit "$?"
  ACCOUNT_ID="$(jq -r '.Account // empty' <<<"${ident}")"
  [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]] || die 5 "could not read the account from sts get-caller-identity"
  [ "${ACCOUNT_ID}" = "${RECORD_ACCOUNT}" ] ||
    die 4 "refusing: caller account $(masked_account "${ACCOUNT_ID}") differs from the recorded account $(masked_account "${RECORD_ACCOUNT}")"
  info "account $(masked_account "${ACCOUNT_ID}"), Region ${EXPECTED_REGION}, profile ${AWS_PROFILE:-<default credential chain>}"
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
  [[ "${PLAN_IMAGE_TAG}" =~ ^[0-9a-f]{40}$ ]] || die 4 "refusing: runtime on but image_tag is not a 40-hex git SHA"
  local outputs
  outputs="$(tf_output_json)" || exit "$?"
  IMAGE_REPOSITORY="$(jq -r '.ecr_repository_name.value // empty' <<<"${outputs}")"
  [ -n "${IMAGE_REPOSITORY}" ] || die 4 "refusing: no ECR repository in the current state (apply the runtime-off plan first)"
  local images
  images="$(aws_json ecr describe-images --repository-name "${IMAGE_REPOSITORY}" \
    --image-ids "imageTag=${PLAN_IMAGE_TAG}")" || exit "$?"
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
  local text="${TF_DIR}/${PLANS_SUBDIR}/${COMMIT}.apply.txt"
  local rc=0
  info "full apply output follows (also saved to ${text})"
  with_timeout "${APPLY_TIMEOUT}" terraform "-chdir=${TF_DIR}" apply -input=false \
    -lock-timeout=60s -no-color "${PLAN_FILE_REL}" 2>&1 | tee "${text}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform apply timed out after ${APPLY_TIMEOUT}s; no release record written; run scripts/plan.sh to see the state"
  [ "${rc}" -eq 0 ] || die 5 "terraform apply failed (status ${rc}); no release record written; run scripts/plan.sh to see what was applied"
}

SERVICES_RECORD='{}'
CLUSTER=""

verify_deployment() {
  step "verify-revision"
  local outputs
  outputs="$(tf_output_json)" || exit "$?"
  CLUSTER="$(jq -r '.ecs_cluster_name.value // empty' <<<"${outputs}")"
  [ -n "${CLUSTER}" ] || die 6 "no ecs_cluster_name output after apply"
  IMAGE_REPOSITORY="$(jq -r '.ecr_repository_name.value // empty' <<<"${outputs}")"
  local runtime_out
  runtime_out="$(jq -r '.runtime_enabled.value // false' <<<"${outputs}")"
  [ "${runtime_out}" = "${PLAN_RUNTIME_ENABLED}" ] || die 6 "output runtime_enabled=${runtime_out} differs from the plan"

  local names=() role name
  for role in ${ROLES}; do
    name="$(jq -r --arg r "${role}" '.ecs_service_names.value[$r] // empty' <<<"${outputs}")"
    [ -n "${name}" ] || die 6 "no service name output for role ${role}"
    names+=("${name}")
  done

  if [ "${PLAN_RUNTIME_ENABLED}" = "true" ]; then
    info "waiting for the services to become stable (at most ${STABLE_TIMEOUT}s)"
    local rc=0
    with_timeout "${STABLE_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 60 --no-cli-pager ecs wait services-stable --cluster "${CLUSTER}" \
      --services "${names[@]}" || rc=$?
    [ "${rc}" -eq 124 ] && die 7 "services did not become stable within ${STABLE_TIMEOUT}s"
    [ "${rc}" -eq 0 ] || die 6 "ecs wait services-stable failed (status ${rc})"
  fi

  local services
  services="$(aws_json ecs describe-services --cluster "${CLUSTER}" --services "${names[@]}")" || exit "$?"
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
      if [ "${role}" != "worker" ] && [ "${desired}" -lt 1 ]; then
        printf 'EVIDENCE: service=%s desired=%s with the runtime on (expected at least 1; 2b RISK R9)\n' \
          "${name}" "${desired}" >&2
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
    elif [ "${desired}" -ne 0 ]; then
      printf 'EVIDENCE: service=%s desired=%s but the runtime is off\n' "${name}" "${desired}" >&2
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
  local stamp short file
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  short="${COMMIT:0:12}"
  file="${RELEASES_DIR}/${stamp}-${short}.json"
  [ -e "${file}" ] && die 4 "refusing to overwrite ${file}"
  jq -n \
    --arg kind "scholarchain-release" \
    --arg environment "${ENVIRONMENT}" \
    --arg commit "${COMMIT}" \
    --arg plan_sha256 "${PLAN_SHA256_ARG}" \
    --arg region "${EXPECTED_REGION}" \
    --arg applied_utc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson runtime_enabled "${PLAN_RUNTIME_ENABLED}" \
    --arg repository "${IMAGE_REPOSITORY}" \
    --arg tag "${PLAN_IMAGE_TAG}" \
    --arg digest "${IMAGE_DIGEST}" \
    --arg cluster "${CLUSTER}" \
    --argjson services "${SERVICES_RECORD}" \
    '{kind: $kind, version: 1, environment: $environment, commit: $commit,
      plan_sha256: $plan_sha256, region: $region, applied_utc: $applied_utc,
      runtime_enabled: $runtime_enabled,
      image: {repository: $repository,
              tag: (if $tag == "" then null else $tag end),
              digest: (if $digest == "" then null else $digest end)},
      ecs_cluster: $cluster, services: $services, verified: true}' >"${file}"
  printf 'RELEASE-RECORD: %s\n' "${file}"
  info "commit the release record with the phase evidence (the human commits)"
}

main() {
  parse_args "$@"
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
  cd "${REPO_ROOT}"
  check_prerequisites
  check_clean_tree
  check_plan_checksum
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
