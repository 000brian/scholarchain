#!/usr/bin/env bash
# scripts/package.sh - build the ScholarChain jars and the services image from
# scratch for the build commit, check what ships, and record checksums and
# tags; with --publish, push the image to ECR and upload the jars and the
# Spark job config to the data lake (Phase 13 work order, decision 9.2; spec
# work-order item 2). Owner: infra-engineer (author). Run by the human.
#
# Status (Phase 13): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="package.sh"
readonly EXPECTED_REGION="us-east-1"
readonly TF_DIR="infra/terraform/environments/course"
readonly LEDGER="project/build.properties"
readonly DOCKERFILE="docker/services/Dockerfile"
readonly CONTEXT_DIR="modules/services/target/docker-context"
readonly IMAGE_NAME="scholarchain-services"
readonly PLATFORM="linux/arm64"
# Accepted platform values (ERE, used whole-value anchored): linux/arm64 or
# linux/arm64/<variant>, the variant being letters and digits only (v8).
# linux/arm/v7, linux/arm64x and linux/amd64 do not match.
readonly ARM64_PLATFORM_RE='linux/arm64(/[[:alnum:]]+)?'
readonly BUILD_INFO="scholarchain-build-info.properties"
readonly SPARK_CONF_DIR="modules/spark/conf"
readonly SBT_TIMEOUT=5400
readonly DOCKER_BUILD_TIMEOUT=3600
readonly DOCKER_TIMEOUT=300
readonly IMAGETOOLS_TIMEOUT=120
readonly PUSH_TIMEOUT=1800
readonly OUTPUT_TIMEOUT=120
readonly AWS_TIMEOUT=120
readonly UPLOAD_TIMEOUT=900
readonly MAX_TRIES=3

CURRENT_STEP="start"
TMP_DIR=""
TIMEOUT_BIN=""
MODE="build"
ALLOW_DIRTY="false"
HEAD_SHA=""
SHA40=""
TREE_DIRTY=""
TAG=""
PKG_DIR=""
ATTEMPT_ID=""
ATTEMPT_RECORD=""
STARTED_WRITTEN="false"
FINAL_WRITTEN="false"
SIGNAL_NAME=""
STARTED_UTC=""
CONTAINER_ID=""
RESULT_JSON="{}"

usage() {
  cat <<'EOF'
Usage: scripts/package.sh [--allow-dirty]
       scripts/package.sh --publish
       scripts/package.sh --help

Run from the repository root: bash scripts/package.sh. SHA40 below is the
BUILD COMMIT (scripts/lib/identity.sh: the newest commit that changed a build
input: build.sbt project modules prompts docker config .scalafmt.conf .jvmopts
.sbtopts). A commit that changes only scripts, Terraform, tests or evidence
keeps the same SHA40, so the image and jars stay valid.

Build mode (default; no AWS call; the base-image check needs network):
  1. Refuses a dirty tree (any tracked change or untracked file outside
     .gitignore). --allow-dirty builds anyway, for local use only: the tag is
     scholarchain-services:SHA40-dirty, no package-record.json is written, and
     --publish never accepts it.
  2. Refuses if evidence/packages/SHA40/package-record.json exists (this build
     commit is already packaged; records are never overwritten). To package
     the same build commit again, move that folder aside yourself first.
  3. Checks that the ledger runtime image (image.java-runtime in
     project/build.properties, pinned by digest) lists linux/arm64 or
     linux/arm64/<variant> (for example linux/arm64/v8) on a Platform line:
     docker buildx imagetools inspect (network; retried, a read).
  4. Builds from scratch: sbt -batch clean packageJobs services/dockerStage
     packageCheck ("packageCheck: ok" required); then
     docker buildx build --platform linux/arm64 --load
       --file docker/services/Dockerfile
       --build-arg JAVA_RUNTIME_IMAGE=<image.java-runtime>
       --tag scholarchain-services:SHA40 modules/services/target/docker-context
  5. Checks the artifacts that ship: each jar's scholarchain-build-info.properties
     has buildCommit = SHA40 and buildDirty = false (true allowed only with
     --allow-dirty) and the expected module; the staged app.jar is byte-equal
     to the services assembly packageCheck opened; the staged
     config/course.json is byte-equal to git show SHA40:config/course.json
     (the working-tree file with --allow-dirty); context.sha256 agrees; the
     image is linux/arm64 or linux/arm64/<variant> (docker image inspect
     Os/Architecture[/Variant]); docker create plus docker cp
     copy /opt/scholarchain/app.jar and /opt/scholarchain/config/course.json
     out of the image and both equal the staged files (sha256); the one
     stopped container this script created is then removed by its ID.
  6. Records evidence/packages/SHA40/package-record.json (atomic, never
     overwritten): build commit, HEAD, dirty flag, each jar's path, size,
     sha256 and build info, image tag, local image ID, platform, ledger
     runtime image, config sha256, context checksums. Every attempt also has
     evidence/packages/SHA40[-dirty]/attempts/ATTEMPT_ID.started.json and
     ATTEMPT_ID.json (succeeded, failed with step and exit code, or
     timed-out).

Publish mode (--publish; Phase 14, run by the human; refuses --allow-dirty):
  1. Refuses a dirty tree; refuses unless
     evidence/packages/SHA40/package-record.json exists (package first) and
     the local jars and the local image still match it (sha256, image ID).
  2. Reads ecr_repository_url, data_bucket_name, data_prefixes and
     vector_bucket_name from terraform output (refuses if missing: nothing
     applied yet), and the account from aws sts get-caller-identity.
  3. Image: if tag SHA40 exists in ECR (aws ecr describe-images) with the
     digest this local image carries, nothing is pushed; with any other digest
     it fails (tags are immutable). Otherwise: aws ecr get-login-password piped
     into docker login --password-stdin (the password is never an argument or
     printed), docker tag, docker push (retried only while the tag is still
     absent), then the ECR digest is read back.
  4. Uploads each jar to s3://{data-bucket}/artifacts/SHA40/jars/NAME.jar
     (NAME: hadoop, entity-resolution, spark-analytics, services) and each
     modules/spark/conf/*.json to s3://{data-bucket}/artifacts/SHA40/spark-conf/
     with object metadata scholarchain-commit=SHA40 and scholarchain-sha256,
     a SHA256 checksum, and --if-none-match '*'. A key that exists with the
     same sha256 and commit is a no-op; a key with other bytes is a failure;
     nothing is ever overwritten. Uploads are retried (idempotent).
  5. Records evidence/packages/SHA40/publish/ATTEMPT_ID.json (started record
     first): ECR repository name, tag and digest, and every key as a logical
     location s3://{data-bucket}/KEY with its sha256, bytes and outcome. No
     account ID, ARN, repository URL or bucket name is recorded; the account
     and bucket are masked in what this script prints.

Requirements: bash, git, java 17, sbt, docker with buildx (daemon running),
jq, unzip, shasum or sha256sum, timeout or gtimeout (GNU coreutils); for
--publish also terraform (output only) and aws (CLI v2) with credentials
allowed to push to the ECR repository and put objects under artifacts/.

Exit codes: 0 done; 2 usage error; 3 missing prerequisite or missing
Terraform output; 4 refused by a safety check (dirty tree, already packaged,
no package record, local artifacts changed since packaging); 5 a command
failed (sbt, docker, terraform, aws); 6 a check failed (build identity,
byte equality, platform, ECR tag with another digest, S3 key with other
bytes, identifier found in a record); 7 timed out.

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
  if [ -n "${CONTAINER_ID}" ]; then
    "${TIMEOUT_BIN}" 60 docker rm "${CONTAINER_ID}" >/dev/null 2>&1 ||
      printf 'WARN: %s could not remove the stopped container %s; remove it by hand\n' "${SCRIPT_NAME}" "${CONTAINER_ID}" >&2
  fi
  if [ "${STARTED_WRITTEN}" = "true" ] && [ "${FINAL_WRITTEN}" = "false" ]; then
    write_attempt_final "${rc}"
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
      --allow-dirty)
        ALLOW_DIRTY="true"
        shift
        ;;
      --publish)
        MODE="publish"
        shift
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
  if [ "${MODE}" = "publish" ] && [ "${ALLOW_DIRTY}" = "true" ]; then
    die 2 "--allow-dirty is refused together with --publish (only clean, recorded packages are published)"
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
  for tool in git jq unzip docker cut grep; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if [ "${MODE}" = "build" ]; then
    for tool in java sbt; do
      command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
    done
    [[ "$(java -version 2>&1 | head -n 1)" =~ version\ \"17([.\"]) ]] || die 3 "java 17 is required"
  else
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
  with_timeout 60 docker buildx version >/dev/null 2>&1 || die 3 "docker buildx is required (and a running Docker daemon)"
  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-package.XXXXXX")"
}

read_identity() {
  step "identity"
  HEAD_SHA="$(head_commit)" || die 3 "not a git repository with a commit"
  SHA40="$(build_commit)" || die 3 "cannot determine the build commit"
  TREE_DIRTY="$(tree_dirty_flag)" || die 5 "git status failed"
  if [ "${ALLOW_DIRTY}" = "true" ]; then
    [ "${TREE_DIRTY}" = "false" ] || warn "--allow-dirty: building from a dirty tree (local tag only)"
  else
    require_clean_tree || die 4 "refusing: the working tree is dirty (commit first, or --allow-dirty for a local-only build)"
  fi
  if [ "${ALLOW_DIRTY}" = "true" ]; then
    TAG="${SHA40}-dirty"
  else
    TAG="${SHA40}"
  fi
  PKG_DIR="evidence/packages/${TAG}"
  info "HEAD ${HEAD_SHA}; build commit ${SHA40}; tree dirty ${TREE_DIRTY}; image tag ${IMAGE_NAME}:${TAG}"
}

# ---- attempt records ----------------------------------------------------------------

start_attempt() {
  local sub="$1"
  ATTEMPT_ID="$(new_attempt_id)"
  STARTED_UTC="$(sc_utc_now)"
  ATTEMPT_RECORD="${PKG_DIR}/${sub}/${ATTEMPT_ID}.json"
  jq -n --arg kind "scholarchain-package-attempt" --arg mode "${MODE}" --arg attempt "${ATTEMPT_ID}" \
    --arg head "${HEAD_SHA}" --arg build "${SHA40}" --argjson dirty "${TREE_DIRTY}" \
    --argjson allow_dirty "${ALLOW_DIRTY}" --arg started "${STARTED_UTC}" \
    '{kind: $kind, version: 1, status: "started", mode: $mode, attempt_id: $attempt, head: $head,
      build_commit: $build, tree_dirty: $dirty, allow_dirty: $allow_dirty, started_utc: $started}' |
    write_record_atomic "${PKG_DIR}/${sub}/${ATTEMPT_ID}.started.json" ||
    die 5 "could not write the started record"
  STARTED_WRITTEN="true"
  info "attempt ${ATTEMPT_ID}; record ${ATTEMPT_RECORD}"
}

write_attempt_final() {
  local rc="$1" status
  case "${rc}" in
    0) status="succeeded" ;;
    7) status="timed-out" ;;
    *) status="failed" ;;
  esac
  if jq -n --arg kind "scholarchain-package-attempt" --arg mode "${MODE}" --arg attempt "${ATTEMPT_ID}" \
    --arg head "${HEAD_SHA}" --arg build "${SHA40}" --argjson dirty "${TREE_DIRTY}" \
    --argjson allow_dirty "${ALLOW_DIRTY}" --arg started "${STARTED_UTC}" --arg ended "$(sc_utc_now)" \
    --arg status "${status}" --argjson exit_code "${rc}" --arg step "${CURRENT_STEP}" \
    --arg signal "${SIGNAL_NAME}" --argjson result "${RESULT_JSON}" \
    '{kind: $kind, version: 1, status: $status, exit_code: $exit_code,
      failed_step: (if $status == "succeeded" then null else $step end),
      signal: (if $signal == "" then null else $signal end), mode: $mode, attempt_id: $attempt,
      head: $head, build_commit: $build, tree_dirty: $dirty, allow_dirty: $allow_dirty,
      started_utc: $started, ended_utc: $ended, result: $result}' >"${TMP_DIR}/attempt-final.json" 2>/dev/null &&
    write_record_atomic "${ATTEMPT_RECORD}" <"${TMP_DIR}/attempt-final.json"; then
    FINAL_WRITTEN="true"
    printf 'ATTEMPT-RECORD: %s\n' "${ATTEMPT_RECORD}"
  else
    printf 'ERROR: %s could not write the attempt record %s\n' "${SCRIPT_NAME}" "${ATTEMPT_RECORD}" >&2
  fi
}

# ---- build mode ----------------------------------------------------------------------

RUNTIME_IMAGE=""

read_ledger_image() {
  step "ledger-runtime-image"
  [ -f "${LEDGER}" ] || die 3 "missing ${LEDGER}"
  RUNTIME_IMAGE="$(grep -E '^image\.java-runtime=' "${LEDGER}" | head -n 1 | cut -d= -f2-)"
  [[ "${RUNTIME_IMAGE}" =~ ^[A-Za-z0-9._/:-]+@sha256:[0-9a-f]{64}$ ]] ||
    die 6 "image.java-runtime in ${LEDGER} is not pinned by a sha256 digest"
  info "runtime image ${RUNTIME_IMAGE}"
}

check_base_platform() {
  step "base-image-platform"
  local try=1 rc out="${TMP_DIR}/imagetools.txt"
  while :; do
    rc=0
    with_timeout "${IMAGETOOLS_TIMEOUT}" docker buildx imagetools inspect "${RUNTIME_IMAGE}" >"${out}" 2>&1 || rc=$?
    if [ "${rc}" -eq 0 ]; then
      break
    fi
    [ "${try}" -lt "${MAX_TRIES}" ] || {
      tail -n 5 "${out}" >&2
      [ "${rc}" -eq 124 ] && die 7 "docker buildx imagetools inspect timed out"
      die 5 "docker buildx imagetools inspect failed (status ${rc}; needs network)"
    }
    try=$((try + 1))
    sleep $((try * 5))
  done
  grep -qE "^[[:space:]]*Platform:[[:space:]]+${ARM64_PLATFORM_RE}[[:space:]]*\$" "${out}" ||
    die 6 "the ledger runtime image does not list linux/arm64 or linux/arm64/<variant> (docker buildx imagetools inspect)"
  info "base image lists linux/arm64 (variant allowed)"
}

refuse_if_packaged() {
  step "package-record-absent"
  if [ "${ALLOW_DIRTY}" = "false" ] && [ -e "${PKG_DIR}/package-record.json" ]; then
    die 4 "refusing: ${PKG_DIR}/package-record.json exists (build commit already packaged; records are never overwritten; move the folder aside yourself to package again)"
  fi
}

sbt_build() {
  step "sbt-build"
  local rc=0 log="${TMP_DIR}/sbt.log"
  with_timeout "${SBT_TIMEOUT}" sbt -batch clean packageJobs services/dockerStage packageCheck 2>&1 |
    tee "${log}" || rc="${PIPESTATUS[0]}"
  [ "${rc}" -eq 124 ] && die 7 "sbt timed out after ${SBT_TIMEOUT}s"
  [ "${rc}" -eq 0 ] || die 5 "sbt -batch clean packageJobs services/dockerStage packageCheck failed (status ${rc})"
  grep -q 'packageCheck: ok' "${log}" || die 6 "packageCheck printed no 'packageCheck: ok' line"
}

# one_file GLOB...: prints the single existing file the pattern matches.
one_file() {
  local found=() f
  for f in "$@"; do
    if [ -f "${f}" ]; then
      found+=("${f}")
    fi
  done
  [ "${#found[@]}" -eq 1 ] || return 1
  printf '%s\n' "${found[0]}"
}

JAR_HADOOP=""
JAR_ER=""
JAR_SPARK=""
JAR_SERVICES=""
JARS_JSON="[]"

check_jar() {
  local jar="$1" module="$2" name="$3" props commit dirty mod sha bytes
  props="$(unzip -p "${jar}" "${BUILD_INFO}" 2>/dev/null)" || die 6 "${jar}: no ${BUILD_INFO}"
  commit="$(printf '%s\n' "${props}" | grep -E '^buildCommit=' | head -n 1 | cut -d= -f2-)"
  dirty="$(printf '%s\n' "${props}" | grep -E '^buildDirty=' | head -n 1 | cut -d= -f2-)"
  mod="$(printf '%s\n' "${props}" | grep -E '^module=' | head -n 1 | cut -d= -f2-)"
  [ "${commit}" = "${SHA40}" ] ||
    die 6 "${jar}: buildCommit '${commit}' differs from the scripts' build commit ${SHA40} (build.sbt and scripts/lib/identity.sh path lists must agree)"
  [ "${mod}" = "${module}" ] || die 6 "${jar}: module '${mod}', expected '${module}'"
  case "${dirty}" in
    false) ;;
    true) [ "${ALLOW_DIRTY}" = "true" ] || die 6 "${jar}: buildDirty=true from a tree this script saw clean" ;;
    *) die 6 "${jar}: buildDirty '${dirty}' is neither true nor false" ;;
  esac
  sha="$(sc_sha256 "${jar}")"
  bytes="$(sc_bytes "${jar}")"
  JARS_JSON="$(jq -c --arg m "${module}" --arg n "${name}" --arg p "${jar}" --arg s "${sha}" \
    --argjson b "${bytes}" --arg c "${commit}" --arg d "${dirty}" \
    '. + [{module: $m, name: $n, path: $p, bytes: $b, sha256: $s,
           build_info: {buildCommit: $c, buildDirty: ($d == "true"), module: $m}}]' <<<"${JARS_JSON}")"
  info "jar ok: ${jar} (${module}, ${bytes} bytes, sha256 ${sha})"
}

check_jars() {
  step "check-jars"
  JAR_HADOOP="$(one_file modules/hadoop/target/scala-*/scholarchain-hadoop-assembly-*.jar)" ||
    die 6 "expected exactly one hadoop assembly jar"
  JAR_ER="$(one_file modules/entity-resolution/target/scala-*/scholarchain-entity-resolution-assembly-*.jar)" ||
    die 6 "expected exactly one entity-resolution assembly jar"
  JAR_SPARK="$(one_file modules/spark/target/scala-*/scholarchain-spark-analytics-assembly-*.jar)" ||
    die 6 "expected exactly one spark assembly jar"
  JAR_SERVICES="$(one_file modules/services/target/scala-*/scholarchain-services-assembly-*.jar)" ||
    die 6 "expected exactly one services assembly jar"
  check_jar "${JAR_HADOOP}" hadoop hadoop
  check_jar "${JAR_ER}" entityResolution entity-resolution
  check_jar "${JAR_SPARK}" sparkAnalytics spark-analytics
  check_jar "${JAR_SERVICES}" services services
}

CONFIG_SHA=""
APPJAR_SHA=""

check_context() {
  step "check-docker-context"
  local expected_cfg="${TMP_DIR}/course.json" line sum path
  [ -f "${CONTEXT_DIR}/app.jar" ] && [ -f "${CONTEXT_DIR}/config/course.json" ] &&
    [ -f "${CONTEXT_DIR}/context.sha256" ] || die 6 "the staged context ${CONTEXT_DIR} is incomplete"
  APPJAR_SHA="$(sc_sha256 "${CONTEXT_DIR}/app.jar")"
  [ "${APPJAR_SHA}" = "$(sc_sha256 "${JAR_SERVICES}")" ] ||
    die 6 "staged app.jar differs from the services assembly packageCheck opened"
  if [ "${ALLOW_DIRTY}" = "true" ]; then
    cp -- config/course.json "${expected_cfg}"
  else
    git show "${SHA40}:config/course.json" >"${expected_cfg}" || die 5 "git show ${SHA40}:config/course.json failed"
  fi
  CONFIG_SHA="$(sc_sha256 "${CONTEXT_DIR}/config/course.json")"
  [ "${CONFIG_SHA}" = "$(sc_sha256 "${expected_cfg}")" ] ||
    die 6 "staged config/course.json differs from the build commit's config/course.json"
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    sum="${line%%  *}"
    path="${line#*  }"
    case "${path}" in
      app.jar | config/course.json) ;;
      *) die 6 "context.sha256 lists an unexpected file: ${path}" ;;
    esac
    [ "${sum}" = "$(sc_sha256 "${CONTEXT_DIR}/${path}")" ] || die 6 "context.sha256 disagrees for ${path}"
  done <"${CONTEXT_DIR}/context.sha256"
  info "context ok: app.jar ${APPJAR_SHA}, config/course.json ${CONFIG_SHA}"
}

IMAGE_ID=""

docker_build() {
  step "docker-build"
  local rc=0
  with_timeout "${DOCKER_BUILD_TIMEOUT}" docker buildx build --platform "${PLATFORM}" --load \
    --file "${DOCKERFILE}" --build-arg "JAVA_RUNTIME_IMAGE=${RUNTIME_IMAGE}" \
    --tag "${IMAGE_NAME}:${TAG}" "${CONTEXT_DIR}" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "docker buildx build timed out"
  [ "${rc}" -eq 0 ] || die 5 "docker buildx build failed (status ${rc})"
}

check_image() {
  step "check-image"
  local platform out_dir="${TMP_DIR}/from-image"
  platform="$(with_timeout "${DOCKER_TIMEOUT}" docker image inspect \
    --format '{{.Os}}/{{.Architecture}}{{with .Variant}}/{{.}}{{end}}' "${IMAGE_NAME}:${TAG}")" ||
    die 5 "docker image inspect failed"
  [[ "${platform}" =~ ^${ARM64_PLATFORM_RE}$ ]] ||
    die 6 "image platform is ${platform}, expected ${PLATFORM} or ${PLATFORM}/<variant>"
  IMAGE_ID="$(with_timeout "${DOCKER_TIMEOUT}" docker image inspect --format '{{.Id}}' "${IMAGE_NAME}:${TAG}")" ||
    die 5 "docker image inspect failed"
  CONTAINER_ID="$(with_timeout "${DOCKER_TIMEOUT}" docker create --platform "${PLATFORM}" "${IMAGE_NAME}:${TAG}")" ||
    die 5 "docker create failed"
  mkdir -p -- "${out_dir}"
  with_timeout "${DOCKER_TIMEOUT}" docker cp "${CONTAINER_ID}:/opt/scholarchain/app.jar" "${out_dir}/app.jar" ||
    die 5 "docker cp of app.jar failed"
  with_timeout "${DOCKER_TIMEOUT}" docker cp "${CONTAINER_ID}:/opt/scholarchain/config/course.json" "${out_dir}/course.json" ||
    die 5 "docker cp of course.json failed"
  with_timeout 60 docker rm "${CONTAINER_ID}" >/dev/null || die 5 "docker rm of the probe container failed"
  CONTAINER_ID=""
  [ "$(sc_sha256 "${out_dir}/app.jar")" = "${APPJAR_SHA}" ] || die 6 "the image's app.jar differs from the staged app.jar"
  [ "$(sc_sha256 "${out_dir}/course.json")" = "${CONFIG_SHA}" ] ||
    die 6 "the image's config/course.json differs from the staged file"
  info "image ${IMAGE_NAME}:${TAG} ok: ${platform}, id ${IMAGE_ID}"
}

write_package_record() {
  step "package-record"
  RESULT_JSON="$(jq -n --arg kind "scholarchain-package-record" --arg build "${SHA40}" --arg head "${HEAD_SHA}" \
    --argjson dirty "${TREE_DIRTY}" --arg attempt "${ATTEMPT_ID}" --argjson jars "${JARS_JSON}" \
    --arg tag "${IMAGE_NAME}:${TAG}" --arg image_id "${IMAGE_ID}" --arg platform "${PLATFORM}" \
    --arg runtime "${RUNTIME_IMAGE}" --arg config_sha "${CONFIG_SHA}" --arg appjar_sha "${APPJAR_SHA}" \
    --arg created "$(sc_utc_now)" \
    '{kind: $kind, version: 1, build_commit: $build, head: $head, tree_dirty: $dirty,
      attempt_id: $attempt, created_utc: $created, jars: $jars,
      image: {tag: $tag, local_image_id: $image_id, platform: $platform, java_runtime_image: $runtime},
      config: {path: "config/course.json", sha256: $config_sha},
      docker_context: {app_jar_sha256: $appjar_sha, config_sha256: $config_sha}}')"
  if [ "${ALLOW_DIRTY}" = "false" ]; then
    printf '%s\n' "${RESULT_JSON}" | write_record_atomic "${PKG_DIR}/package-record.json" ||
      die 6 "could not write ${PKG_DIR}/package-record.json"
    printf 'PACKAGE-RECORD: %s\n' "${PKG_DIR}/package-record.json"
  else
    info "--allow-dirty: no package-record.json (the attempt record holds the details)"
  fi
  printf 'IMAGE-TAG: %s:%s\n' "${IMAGE_NAME}" "${TAG}"
  printf 'BUILD-COMMIT: %s\n' "${SHA40}"
}

build_mode() {
  read_identity
  refuse_if_packaged
  start_attempt attempts
  read_ledger_image
  check_base_platform
  sbt_build
  check_jars
  check_context
  docker_build
  check_image
  write_package_record
}

# ---- publish mode --------------------------------------------------------------------

RECORD=""
REPO_URL=""
REGISTRY=""
REPO_NAME=""
DATA_BUCKET=""
ARTIFACTS_PREFIX=""
ACCOUNT_ID=""

aws_errfile() { printf '%s/aws.err' "${TMP_DIR}"; }

# aws_call TRIES ARGS...: runs aws with timeouts; retries (for reads) up to
# TRIES times; prints the JSON output; on failure prints the masked error and
# returns 5 (7 on timeout). The caller decides.
aws_call() {
  local tries="$1" try=1 rc out
  shift
  while :; do
    rc=0
    out="$(with_timeout "${AWS_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 60 --no-cli-pager --output json "$@" 2>"$(aws_errfile)")" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      [ -n "${out}" ] || out='{}'
      printf '%s\n' "${out}"
      return 0
    fi
    # A "not found" answer is final: no retry.
    if grep -qE 'NotFound|\(404\)' "$(aws_errfile)" 2>/dev/null; then
      return 5
    fi
    if [ "${try}" -ge "${tries}" ]; then
      [ "${rc}" -eq 124 ] && return 7
      return 5
    fi
    try=$((try + 1))
    sleep $((try * 5))
  done
}

print_aws_error() {
  printf 'AWS-ERROR: %s\n' "$(mask_text "$(cat "$(aws_errfile)" 2>/dev/null)")" >&2
}

load_package_record() {
  step "package-record"
  RECORD="${PKG_DIR}/package-record.json"
  [ -f "${RECORD}" ] || die 4 "refusing: ${RECORD} is missing (run scripts/package.sh first)"
  [ "$(jq -r '.kind // empty' "${RECORD}")" = "scholarchain-package-record" ] || die 4 "refusing: ${RECORD} is not a package record"
  [ "$(jq -r '.build_commit // empty' "${RECORD}")" = "${SHA40}" ] || die 4 "refusing: ${RECORD} is for another build commit"
  local path sha n i
  n="$(jq '.jars | length' "${RECORD}")"
  [ "${n}" -eq 4 ] || die 4 "refusing: ${RECORD} lists ${n} jars, expected 4"
  i=0
  while [ "${i}" -lt "${n}" ]; do
    path="$(jq -r --argjson i "${i}" '.jars[$i].path' "${RECORD}")"
    sha="$(jq -r --argjson i "${i}" '.jars[$i].sha256' "${RECORD}")"
    [ -f "${path}" ] || die 4 "refusing: ${path} is gone since packaging (package again)"
    [ "$(sc_sha256 "${path}")" = "${sha}" ] || die 4 "refusing: ${path} changed since packaging (package again)"
    i=$((i + 1))
  done
  IMAGE_ID="$(with_timeout "${DOCKER_TIMEOUT}" docker image inspect --format '{{.Id}}' "${IMAGE_NAME}:${SHA40}" 2>/dev/null)" ||
    die 4 "refusing: local image ${IMAGE_NAME}:${SHA40} not found (package again)"
  [ "${IMAGE_ID}" = "$(jq -r '.image.local_image_id' "${RECORD}")" ] ||
    die 4 "refusing: local image ${IMAGE_NAME}:${SHA40} differs from the recorded image ID (package again)"
  info "package record and local artifacts agree (${RECORD})"
}

read_outputs() {
  step "terraform-outputs"
  local rc=0 outputs vector ident
  outputs="$(with_timeout "${OUTPUT_TIMEOUT}" terraform "-chdir=${TF_DIR}" output -json -no-color 2>/dev/null)" || rc=$?
  [ "${rc}" -eq 124 ] && die 7 "terraform output timed out"
  [ "${rc}" -eq 0 ] || die 5 "terraform output failed (status ${rc})"
  REPO_URL="$(jq -r '.ecr_repository_url.value // empty' <<<"${outputs}")"
  DATA_BUCKET="$(jq -r '.data_bucket_name.value // empty' <<<"${outputs}")"
  ARTIFACTS_PREFIX="$(jq -r '.data_prefixes.value.artifacts // empty' <<<"${outputs}")"
  vector="$(jq -r '.vector_bucket_name.value // empty' <<<"${outputs}")"
  [ -n "${REPO_URL}" ] && [ -n "${DATA_BUCKET}" ] && [ -n "${ARTIFACTS_PREFIX}" ] ||
    die 3 "terraform output lacks ecr_repository_url, data_bucket_name or data_prefixes (nothing applied yet?)"
  REGISTRY="${REPO_URL%%/*}"
  REPO_NAME="${REPO_URL#*/}"
  case "${REGISTRY}" in
    *.dkr.ecr."${EXPECTED_REGION}".amazonaws.com) ;;
    *) die 6 "the ECR repository is not in ${EXPECTED_REGION}" ;;
  esac
  ident="$(aws_call 3 sts get-caller-identity)" || {
    print_aws_error
    die 5 "aws sts get-caller-identity failed"
  }
  ACCOUNT_ID="$(jq -r '.Account // empty' <<<"${ident}")"
  register_identifiers "${ACCOUNT_ID}" "${DATA_BUCKET}" "${vector}" || die 5 "could not read the account"
  info "account $(mask_account "${ACCOUNT_ID}"), Region ${EXPECTED_REGION}, repository ${REPO_NAME}, bucket <data-bucket>"
}

# remote_digest: prints the digest of tag SHA40 in ECR, or nothing if absent.
remote_digest() {
  local out rc=0
  out="$(aws_call 3 ecr describe-images --repository-name "${REPO_NAME}" --image-ids "imageTag=${SHA40}")" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    if grep -q 'ImageNotFoundException' "$(aws_errfile)" 2>/dev/null; then
      return 0
    fi
    print_aws_error
    return "${rc}"
  fi
  jq -r '.imageDetails[0].imageDigest // empty' <<<"${out}"
}

local_has_digest() {
  local digest="$1" digests
  digests="$(with_timeout "${DOCKER_TIMEOUT}" docker image inspect --format '{{json .RepoDigests}}' "${IMAGE_NAME}:${SHA40}" 2>/dev/null)" ||
    return 1
  jq -e --arg d "${REPO_URL}@${digest}" 'index($d) != null' <<<"${digests}" >/dev/null 2>&1
}

ECR_DIGEST=""
ECR_OUTCOME=""

push_image() {
  step "ecr-image"
  local digest rc try=1
  digest="$(remote_digest)" || die 5 "aws ecr describe-images failed"
  if [ -n "${digest}" ]; then
    local_has_digest "${digest}" ||
      die 6 "tag ${SHA40} exists in ECR with digest ${digest}, which this local image does not carry (tags are immutable)"
    ECR_DIGEST="${digest}"
    ECR_OUTCOME="already-present"
    info "tag ${SHA40} already in ECR with this image's digest ${digest}; nothing pushed"
    return 0
  fi
  step "ecr-login"
  rc=0
  with_timeout "${AWS_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 --cli-read-timeout 60 \
    ecr get-login-password 2>"$(aws_errfile)" |
    with_timeout "${DOCKER_TIMEOUT}" docker login --username AWS --password-stdin "${REGISTRY}" >"${TMP_DIR}/login.out" 2>&1 ||
    rc=$?
  mask_stream <"${TMP_DIR}/login.out"
  [ "${rc}" -eq 0 ] || {
    print_aws_error
    die 5 "ECR login failed (status ${rc})"
  }
  step "ecr-push"
  with_timeout "${DOCKER_TIMEOUT}" docker tag "${IMAGE_NAME}:${SHA40}" "${REPO_URL}:${SHA40}" || die 5 "docker tag failed"
  while :; do
    rc=0
    with_timeout "${PUSH_TIMEOUT}" docker push "${REPO_URL}:${SHA40}" 2>&1 | mask_stream || rc="${PIPESTATUS[0]}"
    [ "${rc}" -eq 0 ] && break
    digest="$(remote_digest)" || die 5 "aws ecr describe-images failed after a failed push"
    [ -z "${digest}" ] || die 6 "the push failed (status ${rc}) but tag ${SHA40} now exists in ECR; check it by hand"
    [ "${try}" -lt "${MAX_TRIES}" ] || {
      [ "${rc}" -eq 124 ] && die 7 "docker push timed out"
      die 5 "docker push failed (status ${rc}) after ${MAX_TRIES} tries"
    }
    try=$((try + 1))
    warn "docker push failed (status ${rc}); the tag is still absent in ECR; retry ${try}/${MAX_TRIES}"
    sleep $((try * 10))
  done
  digest="$(remote_digest)" || die 5 "aws ecr describe-images failed after the push"
  [ -n "${digest}" ] || die 6 "tag ${SHA40} is not in ECR after the push"
  local_has_digest "${digest}" || warn "the local image does not list the ECR digest ${digest} in RepoDigests (unverified match)"
  ECR_DIGEST="${digest}"
  ECR_OUTCOME="pushed"
  info "pushed ${REPO_NAME}:${SHA40} digest ${digest}"
}

UPLOADS_JSON="[]"

# head_meta KEY: prints "SHA256 COMMIT" of an existing object's metadata, or
# nothing (status 0) if the key does not exist.
head_meta() {
  local key="$1" out rc=0
  out="$(aws_call 3 s3api head-object --bucket "${DATA_BUCKET}" --key "${key}")" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    if grep -qE '\(404\)|Not Found' "$(aws_errfile)" 2>/dev/null; then
      return 0
    fi
    print_aws_error
    return "${rc}"
  fi
  jq -r '"\(.Metadata["scholarchain-sha256"] // "-") \(.Metadata["scholarchain-commit"] // "-")"' <<<"${out}"
}

upload_once() {
  local file="$1" key="$2" sha bytes meta outcome="" try=1 rc
  sha="$(sc_sha256 "${file}")"
  bytes="$(sc_bytes "${file}")"
  while :; do
    meta="$(head_meta "${key}")" || die 5 "aws s3api head-object failed for $(logical_s3 "s3://${DATA_BUCKET}/${key}")"
    if [ -n "${meta}" ]; then
      [ "${meta}" = "${sha} ${SHA40}" ] ||
        die 6 "$(logical_s3 "s3://${DATA_BUCKET}/${key}") exists with other bytes or another commit; nothing is overwritten"
      outcome="${outcome:-already-present}"
      break
    fi
    rc=0
    with_timeout "${UPLOAD_TIMEOUT}" aws --region "${EXPECTED_REGION}" --cli-connect-timeout 10 \
      --cli-read-timeout 300 --no-cli-pager --output json s3api put-object --bucket "${DATA_BUCKET}" \
      --key "${key}" --body "${file}" --if-none-match '*' --checksum-algorithm SHA256 \
      --metadata "scholarchain-commit=${SHA40},scholarchain-sha256=${sha}" >/dev/null 2>"$(aws_errfile)" || rc=$?
    if [ "${rc}" -eq 0 ]; then
      outcome="uploaded"
      continue # read the metadata back once
    fi
    [ "${try}" -lt "${MAX_TRIES}" ] || {
      print_aws_error
      [ "${rc}" -eq 124 ] && die 7 "upload timed out"
      die 5 "upload of $(logical_s3 "s3://${DATA_BUCKET}/${key}") failed after ${MAX_TRIES} tries"
    }
    try=$((try + 1))
    warn "upload failed (status ${rc}); checking the key and retrying (${try}/${MAX_TRIES})"
    sleep $((try * 5))
  done
  UPLOADS_JSON="$(jq -c --arg u "$(logical_s3 "s3://${DATA_BUCKET}/${key}")" --arg s "${sha}" \
    --argjson b "${bytes}" --arg o "${outcome}" --arg f "${file}" \
    '. + [{location: $u, source: $f, sha256: $s, bytes: $b, outcome: $o}]' <<<"${UPLOADS_JSON}")"
  info "$(logical_s3 "s3://${DATA_BUCKET}/${key}") ${outcome}"
}

upload_artifacts() {
  step "s3-artifacts"
  local n i path name conf
  n="$(jq '.jars | length' "${RECORD}")"
  i=0
  while [ "${i}" -lt "${n}" ]; do
    path="$(jq -r --argjson i "${i}" '.jars[$i].path' "${RECORD}")"
    name="$(jq -r --argjson i "${i}" '.jars[$i].name' "${RECORD}")"
    [[ "${name}" =~ ^[a-z][a-z-]*$ ]] || die 6 "bad jar name in the record: ${name}"
    upload_once "${path}" "${ARTIFACTS_PREFIX}/${SHA40}/jars/${name}.jar"
    i=$((i + 1))
  done
  for conf in "${SPARK_CONF_DIR}"/*.json; do
    [ -f "${conf}" ] || continue
    git ls-files --error-unmatch -- "${conf}" >/dev/null 2>&1 || die 6 "${conf} is not tracked"
    upload_once "${conf}" "${ARTIFACTS_PREFIX}/${SHA40}/spark-conf/${conf##*/}"
  done
}

publish_mode() {
  read_identity
  load_package_record
  read_outputs
  start_attempt publish
  push_image
  upload_artifacts
  step "publish-record"
  RESULT_JSON="$(jq -n --arg build "${SHA40}" --arg repo "${REPO_NAME}" --arg tag "${SHA40}" \
    --arg digest "${ECR_DIGEST}" --arg outcome "${ECR_OUTCOME}" --argjson uploads "${UPLOADS_JSON}" \
    --arg record "${RECORD}" \
    '{build_commit: $build, package_record: $record,
      ecr: {repository_name: $repo, tag: $tag, digest: $digest, outcome: $outcome},
      artifacts: $uploads}')"
  check_no_identifiers "${RECORD}" || warn "the package record holds an identifier (it should not)"
  printf 'ECR-DIGEST: %s\n' "${ECR_DIGEST}"
}

main() {
  parse_args "$@"
  enter_repo_root
  check_prerequisites
  if [ "${MODE}" = "build" ]; then
    build_mode
  else
    publish_mode
  fi
  step "done"
}

main "$@"
