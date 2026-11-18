#!/usr/bin/env bash
# scripts/rollback.sh - point the ECS services back to the previous
# task-definition revision named in a release record. Owner: infra-engineer
# (author); executed by deploy-engineer or incident-responder after approval.
#
# Status (Phase 13, repair I-22): static: shellcheck clean; structure
# reviewed: repo-root refusal, INT/TERM traps, attempt-ID record through
# scripts/lib/identity.sh; runtime: untested; human execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="rollback.sh"
readonly ENVIRONMENT="course"
readonly EXPECTED_REGION="us-east-1"
readonly TF_DIR="infra/terraform/environments/course"
readonly RELEASES_DIR="releases/course"
readonly OUTPUT_TIMEOUT=120
readonly AWS_TIMEOUT=90
readonly STABLE_TIMEOUT=1200
readonly ALL_ROLES="query evidence graph worker"
readonly CONTAINER_NAME="app"

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
ACCOUNT_ID=""
ATTEMPT_ID=""

usage() {
  cat <<'EOF'
Usage: scripts/rollback.sh --release releases/course/<record>.json [--role ROLE]...
       scripts/rollback.sh --help

Run it from the repository root (bash scripts/rollback.sh); it refuses
elsewhere (exit 4).

What it does (its whole contract):
  - Reads a release record written by deploy.sh. For each selected service
    (default: query, evidence, graph and worker; --role narrows it), it
    refuses unless the service currently runs the record's task_definition,
    and unless the record's previous_task_definition is of the same family
    and still ACTIVE in ECS (ECS cannot point a service at an INACTIVE
    revision; Terraform deregisters replaced revisions unless the task
    definitions keep them).
  - Updates each selected service to that previous revision
    (aws ecs update-service --task-definition family:revision), waits until
    the services are stable, and verifies that each service and its running
    tasks use the previous revision.
  - Writes a rollback record releases/course/ATTEMPT_ID-rollback.json
    (ATTEMPT_ID: UTC time, short HEAD and 4 random hex, from
    scripts/lib/identity.sh new_attempt_id; from/to revision per service; no
    account ID, ARN, bucket name or secret: checked with grep -F before it
    is written; written atomically and never overwritten). It is written
    only after a verified rollback; a failed or interrupted run writes no
    record (the printed lines are its evidence). Commit it like a release
    record. Account ID and bucket names are masked in what it prints.

What it is NOT:
  - It is not a rollback of the infrastructure. It never touches stateful
    resources (S3, DynamoDB, Neptune, S3 Vectors, SQS, secrets), never
    changes data, never runs terraform apply and never edits Terraform
    state. Desired counts and scaling limits are left as they are.
  - The next terraform plan WILL show drift on the ECS services (Terraform
    wants its own revision back). That is expected; fix forward with a new
    signed plan.

It asks no question. Requirements: bash, git, terraform (output only),
aws (CLI v2), jq (1.6+), timeout or gtimeout (GNU coreutils); AWS
credentials of the deploy profile in the environment.

Exit codes: 0 rolled back and verified; 2 usage error; 3 missing
prerequisite; 4 refused by a safety check (not at the repository root,
release record, cluster, revisions, a record name that exists); 5 terraform
or AWS command failed, or interrupted (INT); 6 rollback not verified
(evidence printed), or the record held an identifier; 7 timed out (a time
limit, or TERM, which timeout sends).

Status: static: shellcheck clean; runtime: untested; human execution required.
EOF
}

step() {
  CURRENT_STEP="$1"
  printf 'STEP: %s %s\n' "${SCRIPT_NAME}" "$1"
}
info() { printf 'INFO: %s %s\n' "${SCRIPT_NAME}" "$*"; }
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
# I-22: INT and TERM (timeout sends TERM) end with explicit codes, as in the
# other nine scripts.
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

RELEASE_FILE=""
SELECTED_ROLES=""

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --release)
        [ "$#" -ge 2 ] || die 2 "--release needs a path"
        RELEASE_FILE="$2"
        shift 2
        ;;
      --role)
        [ "$#" -ge 2 ] || die 2 "--role needs a value"
        case "$2" in
          query | evidence | graph | worker) ;;
          *) die 2 "--role must be query, evidence, graph or worker" ;;
        esac
        SELECTED_ROLES="${SELECTED_ROLES} $2"
        shift 2
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
  [ -n "${RELEASE_FILE}" ] || {
    usage >&2
    die 2 "--release is required"
  }
  if [ -z "${SELECTED_ROLES}" ]; then
    SELECTED_ROLES="${ALL_ROLES}"
  fi
}

# I-22: refuses outside the repository root instead of changing directory.
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
  for tool in git terraform aws jq; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_BIN="timeout"
  elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_BIN="gtimeout"
  else
    die 3 "neither timeout nor gtimeout found (install GNU coreutils)"
  fi
  aws --version 2>&1 | grep -q '^aws-cli/2' || die 3 "AWS CLI v2 is required"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-rollback.XXXXXX")"
}

check_release_record() {
  step "release-record"
  [ -f "${RELEASE_FILE}" ] || die 4 "refusing: release record not found: ${RELEASE_FILE}"
  local kind renv rregion
  kind="$(jq -r '.kind // empty' "${RELEASE_FILE}")" || die 4 "refusing: release record is not valid JSON"
  renv="$(jq -r '.environment // empty' "${RELEASE_FILE}")"
  rregion="$(jq -r '.region // empty' "${RELEASE_FILE}")"
  [ "${kind}" = "scholarchain-release" ] || die 4 "refusing: ${RELEASE_FILE} is not a deploy.sh release record"
  [ "${renv}" = "${ENVIRONMENT}" ] || die 4 "refusing: record environment '${renv}' is not ${ENVIRONMENT}"
  [ "${rregion}" = "${EXPECTED_REGION}" ] || die 4 "refusing: record Region '${rregion}' is not ${EXPECTED_REGION}"
  info "release record: ${RELEASE_FILE} (commit $(jq -r '.commit' "${RELEASE_FILE}"))"
}

CLUSTER=""
OUTPUTS=""

read_outputs() {
  step "terraform-outputs"
  local rc=0
  OUTPUTS="$(with_timeout "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" output -json -no-color)" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform output failed (status ${rc})"
  CLUSTER="$(jq -r '.ecs_cluster_name.value // empty' <<<"${OUTPUTS}")"
  [ -n "${CLUSTER}" ] || die 3 "no ecs_cluster_name output (nothing deployed?)"
  local rcluster
  rcluster="$(jq -r '.ecs_cluster // empty' "${RELEASE_FILE}")"
  [ "${rcluster}" = "${CLUSTER}" ] || die 4 "refusing: record cluster '${rcluster}' differs from the deployed cluster '${CLUSTER}'"
  local ident
  ident="$(aws_json sts get-caller-identity)" || exit "$?"
  ACCOUNT_ID="$(jq -r '.Account // empty' <<<"${ident}")"
  [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]] || die 5 "could not read the account from sts get-caller-identity"
  # I-22: registered for masking and for the record's identifier check.
  register_identifiers "${ACCOUNT_ID}" "$(jq -r '.data_bucket_name.value // empty' <<<"${OUTPUTS}")" \
    "$(jq -r '.vector_bucket_name.value // empty' <<<"${OUTPUTS}")" || die 5 "could not register the identifiers"
  info "account $(mask_account "${ACCOUNT_ID}"), cluster ${CLUSTER}, Region ${EXPECTED_REGION}, profile ${AWS_PROFILE:-<default credential chain>}"
}

PLAN_LINES=""

plan_rollback() {
  step "check-targets"
  local role name svc current recorded previous td status
  for role in ${SELECTED_ROLES}; do
    name="$(jq -r --arg r "${role}" '.ecs_service_names.value[$r] // empty' <<<"${OUTPUTS}")"
    [ -n "${name}" ] || die 3 "no service name output for role ${role}"
    recorded="$(jq -r --arg n "${name}" '.services[$n].task_definition // empty' "${RELEASE_FILE}")"
    previous="$(jq -r --arg n "${name}" '.services[$n].previous_task_definition // empty' "${RELEASE_FILE}")"
    [ -n "${recorded}" ] || die 4 "refusing: the record has no task_definition for service ${name}"
    [ -n "${previous}" ] || die 4 "refusing: the record has no previous_task_definition for service ${name} (first deploy?)"
    [[ "${previous}" =~ ^[A-Za-z0-9_-]+:[0-9]+$ ]] || die 4 "refusing: malformed previous_task_definition for ${name}"
    [ "${previous%:*}" = "${recorded%:*}" ] || die 4 "refusing: previous and recorded revisions of ${name} are of different families"

    svc="$(aws_json ecs describe-services --cluster "${CLUSTER}" --services "${name}")" || exit "$?"
    current="$(jq -r '.services[0].taskDefinition // empty' <<<"${svc}")"
    current="${current##*/}"
    [ "${current}" = "${recorded}" ] ||
      die 4 "refusing: ${name} runs ${current}, not the record's ${recorded} (use the release record of the current deployment)"

    td="$(aws_json ecs describe-task-definition --task-definition "${previous}")" || exit "$?"
    status="$(jq -r '.taskDefinition.status // empty' <<<"${td}")"
    [ "${status}" = "ACTIVE" ] ||
      die 4 "refusing: ${previous} is ${status:-unknown}; ECS cannot point a service at an INACTIVE revision"
    printf 'TARGET: service=%s from=%s to=%s\n' "${name}" "${recorded}" "${previous}"
    PLAN_LINES="${PLAN_LINES}${name} ${recorded} ${previous}
"
  done
}

ROLLED_JSON='{}'

do_rollback() {
  step "update-services"
  local name from to names=()
  while read -r name from to; do
    if [ -z "${name}" ]; then
      continue
    fi
    aws_json ecs update-service --cluster "${CLUSTER}" --service "${name}" \
      --task-definition "${to}" >/dev/null || exit "$?"
    info "service ${name}: update requested ${from} -> ${to}"
    names+=("${name}")
  done <<<"${PLAN_LINES}"
  [ "${#names[@]}" -gt 0 ] || die 4 "nothing to roll back"

  step "wait-stable"
  local rc=0
  with_timeout "${STABLE_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
    --cli-read-timeout 60 --no-cli-pager ecs wait services-stable --cluster "${CLUSTER}" \
    --services "${names[@]}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "services did not become stable within ${STABLE_TIMEOUT}s"
  [ "${rc}" -eq 0 ] || die 6 "ecs wait services-stable failed (status ${rc})"

  step "verify-revision"
  local failed=0 svc current tasks task_arns t desc
  while read -r name from to; do
    if [ -z "${name}" ]; then
      continue
    fi
    svc="$(aws_json ecs describe-services --cluster "${CLUSTER}" --services "${name}")" || exit "$?"
    current="$(jq -r '.services[0].taskDefinition // empty' <<<"${svc}")"
    current="${current##*/}"
    if [ "${current}" != "${to}" ]; then
      printf 'EVIDENCE: service=%s revision=%s expected=%s\n' "${name}" "${current}" "${to}" >&2
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
      desc="$(aws_json ecs describe-tasks --cluster "${CLUSTER}" --tasks "${task_arns[@]}")" || exit "$?"
      if ! jq -e --arg td "${to}" '[.tasks[] | (.taskDefinitionArn | split("/") | last) == $td] | all' \
        <<<"${desc}" >/dev/null; then
        printf 'EVIDENCE: service=%s running tasks not all on %s\n' "${name}" "${to}" >&2
        jq -r --arg c "${CONTAINER_NAME}" '.tasks[] | "  task_definition=\(.taskDefinitionArn | split("/") | last) digest=\([.containers[] | select(.name == $c) | .imageDigest // "none"] | first // "none")"' <<<"${desc}" >&2
        failed=1
      fi
    fi
    printf 'VERIFY: service=%s revision=%s running_tasks=%s\n' "${name}" "${current}" "${#task_arns[@]}"
    ROLLED_JSON="$(jq -c --arg n "${name}" --arg f "${from}" --arg t "${to}" \
      '. + {($n): {from: $f, to: $t}}' <<<"${ROLLED_JSON}")"
  done <<<"${PLAN_LINES}"
  [ "${failed}" -eq 0 ] || die 6 "rollback NOT verified (evidence above); no rollback record written"
}

# I-22: the record is named by the attempt ID (new_attempt_id, taken before
# the first AWS call) and written with the helper's write_record_atomic,
# which refuses an existing name, checks the JSON and runs
# check_no_identifiers before the record appears under its final name.
write_record() {
  step "rollback-record"
  local file="${RELEASES_DIR}/${ATTEMPT_ID}-rollback.json" rc=0
  jq -n \
    --arg kind "scholarchain-rollback" \
    --arg attempt "${ATTEMPT_ID}" \
    --arg environment "${ENVIRONMENT}" \
    --arg region "${EXPECTED_REGION}" \
    --arg source_release "${RELEASE_FILE}" \
    --arg commit "$(git rev-parse --verify HEAD)" \
    --arg rolled_back_utc "$(sc_utc_now)" \
    --arg cluster "${CLUSTER}" \
    --argjson services "${ROLLED_JSON}" \
    '{kind: $kind, version: 2, attempt_id: $attempt, environment: $environment, region: $region,
      source_release: $source_release, repository_commit: $commit,
      rolled_back_utc: $rolled_back_utc, ecs_cluster: $cluster,
      services: $services, verified: true,
      note: "ECS services only; no stateful resource, data or Terraform state was touched; the next plan shows drift."}' |
    write_record_atomic "${file}" || rc=$?
  case "${rc}" in
    0) ;;
    4) die 4 "refusing: ${file} (or its .partial) exists; records are never overwritten" ;;
    6) die 6 "the rollback record failed its checks (reason above; the .partial file is kept, never commit it); the rollback itself happened and was verified" ;;
    *) die 5 "could not write the rollback record ${file} (status ${rc}); the rollback itself happened and was verified" ;;
  esac
  printf 'ROLLBACK-RECORD: %s\n' "${file}"
}

main() {
  parse_args "$@"
  enter_repo_root
  ATTEMPT_ID="$(new_attempt_id)"
  printf 'ATTEMPT: %s\n' "${ATTEMPT_ID}"
  check_prerequisites
  check_release_record
  read_outputs
  plan_rollback
  do_rollback
  write_record
  step "done"
}

main "$@"
