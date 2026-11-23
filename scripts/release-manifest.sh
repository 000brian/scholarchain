#!/usr/bin/env bash
# scripts/release-manifest.sh - writes the ScholarChain release manifest of the
# release candidate (HEAD of this working tree) from committed files, the jars
# built in a fresh clone of the same commit, and the saved test marker of the
# fresh-clone `sbt clean check` (Phase 17 work order, decision 7 item 1; run by
# the human at step E10, dry run at E5). Owner: infra-engineer (author).
#
# Read-only: no network, no AWS call, no Terraform, no sbt. It writes only the
# new folder OUT_DIR/RC_SHA/ (through OUT_DIR/.incomplete-RC_SHA-XXXXXX/) and a
# private temporary folder under ${TMPDIR:-/tmp} that it removes on exit.
#
# Status (Phase 17): static: shellcheck clean; runtime: untested; human
# execution required.

set -Eeuo pipefail
umask 077

readonly SCRIPT_NAME="release-manifest.sh"
readonly SCRIPT_PATH="scripts/release-manifest.sh"
readonly PLANNED_TAG="v1.0-submission"
readonly NAME_PREFIX="scholarchain-course"
readonly BUILD_INFO="scholarchain-build-info.properties"
readonly CONFIG_FILE="config/course.json"
readonly DATASET_MANIFEST="datasets/dataset-manifest.yaml"
readonly PACKAGE_CHECK_SCALA="project/PackageCheck.scala"
readonly INSIDE_REPO_OUT_DIR="releases/manifest"
# Twelve-digit placeholders documented in the repository (never real accounts).
readonly PLACEHOLDER_ACCOUNTS="123456789012 210987654321"
readonly RULE_LINE="commits after this one add or change only README.md, files under docs/ except docs/agents.md, docs/incidents/** and docs/agent-runs/**, files under reports/, new files under releases/manifest/RC_SHA/, and files under docs/agent-runs/phase-17/; they never delete or rename a file, and never change docs/agents.md, another phase's records, releases/course/ or an earlier manifest"

# The four mandatory jars: label, module (scholarchain-build-info.properties
# "module"), clone-relative glob. Paths as scripts/package.sh check_jars finds
# them; the jars are the four that project/PackageCheck.scala opens
# (PackageCheck.Inputs: hadoop, er, spark, services). None is optional.
readonly JAR_SPECS="hadoop|hadoop|modules/hadoop/target/scala-*/scholarchain-hadoop-assembly-*.jar
entity-resolution|entityResolution|modules/entity-resolution/target/scala-*/scholarchain-entity-resolution-assembly-*.jar
spark-analytics|sparkAnalytics|modules/spark/target/scala-*/scholarchain-spark-analytics-assembly-*.jar
services|services|modules/services/target/scala-*/scholarchain-services-assembly-*.jar"

CURRENT_STEP="start"
CLONE_ARG=""
MARKER_ARG=""
OUT_ARG=""
REPO_ROOT=""
HEAD_SHA=""
BUILD_SHA=""
CLONE_DIR=""
CLONE_HEAD=""
OUT_DIR=""
TARGET_DIR=""
INCOMPLETE_DIR=""
PUBLISHED="false"
WORK=""
SHA_TOOL=""

usage() {
  cat <<'EOF'
Usage: scripts/release-manifest.sh --clone PATH --marker PATH --out-dir PATH
       scripts/release-manifest.sh --help

Writes the release manifest of the release candidate, which is HEAD of this
working tree (run it from the repository root): OUT_DIR/RC_SHA/
release-manifest.json and OUT_DIR/RC_SHA/release-manifest.md, where RC_SHA is
the full 40-character HEAD SHA. Read-only: no network, no AWS call, no
Terraform, no sbt; nothing is changed in the repository or the clone.

Options (all three required):
  --clone PATH    the fresh clone of the release candidate (step E3). Its
                  top folder, not this working tree; its HEAD must equal this
                  tree's HEAD. Used only to hash the four mandatory jars built
                  there (check.sh gate 7 builds all four through
                  sbt -batch packageCheck).
  --marker PATH   the saved copy of the clone's .claude/.last-test-run taken
                  right after E3's sbt clean check (lines timestamp=,
                  module_tests=, contract_tests=).
  --out-dir PATH  the folder that receives RC_SHA/. Inside this repository
                  only releases/manifest is accepted; outside it any folder
                  whose parent exists (a dry run, for example
                  /tmp/p17-manifest-dryrun-STAMP). Created if missing.

Order of checks (each refusal writes nothing): options (2); repository root
(4); tools (3); --marker exists (3); --clone is the top folder of a Git
working tree (3) and not this tree (4); clone HEAD equals this HEAD (3);
exactly one of each mandatory jar in the clone (3 when missing, 6 when more
than one); this tree is clean (4); --out-dir accepted and OUT_DIR/RC_SHA
absent (4). Then it reads every input, runs the identifier check on both
documents in its private temporary folder (exit 8 before anything is written
to OUT_DIR), writes both files into OUT_DIR/.incomplete-RC_SHA-XXXXXX/,
validates them (jq empty, every mandatory field, the Markdown carries the
SHA, the tag, every jar checksum and the rule line), checks that HEAD did not
move, and only then renames that folder to OUT_DIR/RC_SHA/. On a failure
after the temporary folder exists, no RC_SHA/ folder is left; the script
prints "INCOMPLETE-FOLDER: PATH" (never commit it; remove it, then run again).

Contents, each with its source:
  release candidate (git rev-parse HEAD) and build commit
    (scripts/lib/identity.sh build_commit); planned tag v1.0-submission;
  every release record releases/course/*.json at HEAD: commit, plan commit,
    plan sha256, environment, Region, image repository, tag and digest, per
    service the task definition, previous task definition, image tag and
    digest check, and whether a committed APPROVED-PLAN-SHA256 line in a
    docs/agent-runs/**/approvals.md or human-gate.md carries the same sha256;
  every signed plan checksum (APPROVED-PLAN-SHA256) with its file and line;
  every signed experiment manifest (APPROVED-MANIFEST-SHA256 matched against
    experiments/manifests/*.json at HEAD);
  dataset snapshots and checksums (datasets/dataset-manifest.yaml);
  run IDs: the Spark graph runs named in experiments/raw/*/*/run-record.json,
    every experiment attempt folder under experiments/raw/, every evaluation
    run folder under experiments/evaluation/raw/, every emulator run folder
    under experiments/emulator/raw/ (all from git ls-tree HEAD, so
    committed), and the local-only evidence archives (EVIDENCE-ARCHIVE-SHA256
    lines; "local only", never a repository path);
  prompt versions: path and sha256 of every file under prompts/ at HEAD;
  model identifiers from config/course.json (field paths named);
  test counts from --marker, labelled E3 with the tested SHA (clone HEAD);
  sha256, size and build info (buildCommit, buildDirty, module) of the four
    mandatory jars in the clone; a jar whose buildCommit is not the build
    commit, or whose buildDirty is not false, is refused (6);
  sha256 of key committed artifacts at HEAD (the dataset manifest, the signed
    experiment manifests, experiments/evaluation/suite-v1.json, evaluate.py,
    every frozen-files-sha256-E12.txt, every tracked *.sha256 file under
    docs/agent-runs/ and experiments/, config/course.json, the release
    records, the approval files holding an EVIDENCE-ARCHIVE-SHA256 line);
  the rule line for commits after the release candidate.
Repository files are hashed as committed at HEAD (git cat-file), which on a
clean tree equals shasum -a 256 of the working file.

Identifier check (exit 8): both documents are refused when they hold a
twelve-digit number (an alphanumeric token of exactly 12 digits) other than
the two documented placeholders and a 12-character prefix of a commit of
this repository; an ARN with an account; an amazonaws.com, execute-api or
.elb. host; an s3:// URI that is not a {placeholder}; a bucket name of this
environment; an access-key, Anthropic-key or Voyage-key shape. It prints the
document, the line and the kind, never the value.

Exit codes: 0 written; 2 usage; 3 missing prerequisite (tool, --marker file,
--clone not a Git working tree, clone HEAD differs, mandatory jar missing,
--out-dir parent missing); 4 refused by a safety check (not the repository
root, --clone is this tree, dirty tree, --out-dir not accepted, RC_SHA/
exists, HEAD moved); 5 a command failed or interrupted (INT); 6 a check
failed (malformed marker, input missing at HEAD, more than one jar per
pattern, jar build info, validation of the written files); 7 stopped by
TERM; 8 an identifier would be written.

Output lines: "STEP: release-manifest.sh NAME", "INFO: ...", "SUMMARY: ...",
"MANIFEST-FOLDER: PATH" on success, "RESULT: release-manifest.sh ok|failed".

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
  if [ "$1" = "TERM" ]; then
    exit 7
  fi
  exit 5
}
on_exit() {
  local rc="$?"
  trap - ERR
  set +e
  if [ -n "${WORK}" ] && [ -d "${WORK}" ]; then
    rm -rf -- "${WORK}"
  fi
  if [ -n "${INCOMPLETE_DIR}" ] && [ "${PUBLISHED}" != "true" ] && [ -d "${INCOMPLETE_DIR}" ]; then
    printf 'INCOMPLETE-FOLDER: %s (not a manifest; never commit it; remove it, then run again)\n' \
      "${INCOMPLETE_DIR}" >&2
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

# ---- arguments, location, tools -----------------------------------------------------

parse_args() {
  step "args"
  [ "$#" -gt 0 ] || {
    usage >&2
    die 2 "--clone, --marker and --out-dir are required"
  }
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --help | -h)
        usage
        exit 0
        ;;
      --clone | --marker | --out-dir)
        if [ "$#" -lt 2 ] || [ -z "$2" ]; then
          die 2 "$1 needs a non-empty path"
        fi
        case "$1" in
          --clone) CLONE_ARG="$2" ;;
          --marker) MARKER_ARG="$2" ;;
          *) OUT_ARG="$2" ;;
        esac
        shift 2
        ;;
      *)
        usage >&2
        die 2 "unknown argument: $1"
        ;;
    esac
  done
  [ -n "${CLONE_ARG}" ] || die 2 "--clone PATH is required"
  [ -n "${MARKER_ARG}" ] || die 2 "--marker PATH is required"
  [ -n "${OUT_ARG}" ] || die 2 "--out-dir PATH is required"
}

enter_repo_root() {
  step "repo-root"
  local top here
  top="$(git rev-parse --show-toplevel 2>/dev/null)" ||
    die 4 "refusing: not inside a git checkout; run from the repository root (bash ${SCRIPT_PATH})"
  top="$(cd -- "${top}" && pwd -P)"
  here="$(pwd -P)"
  [ "${top}" = "${here}" ] || die 4 "refusing: run from the repository root (bash ${SCRIPT_PATH})"
  [ -f scripts/lib/identity.sh ] || die 3 "missing scripts/lib/identity.sh"
  # shellcheck source=/dev/null
  . scripts/lib/identity.sh
  REPO_ROOT="${here}"
}

check_tools() {
  step "prerequisites"
  local tool
  for tool in git jq unzip cut grep mktemp dirname basename wc tr; do
    command -v "${tool}" >/dev/null 2>&1 || die 3 "required tool not found: ${tool}"
  done
  if command -v shasum >/dev/null 2>&1; then
    SHA_TOOL="shasum"
  elif command -v sha256sum >/dev/null 2>&1; then
    SHA_TOOL="sha256sum"
  else
    die 3 "neither shasum nor sha256sum found"
  fi
}

# sha256 of standard input (lowercase hex).
sha256_stdin() {
  if [ "${SHA_TOOL}" = "shasum" ]; then
    shasum -a 256 | cut -d ' ' -f 1
  else
    sha256sum | cut -d ' ' -f 1
  fi
}

# ---- refusals before anything is read or written ------------------------------------

check_marker_exists() {
  step "marker"
  [ -f "${MARKER_ARG}" ] || die 3 "--marker file not found: ${MARKER_ARG} (the saved copy of E3's .claude/.last-test-run)"
}

check_clone() {
  step "clone"
  local top
  [ -d "${CLONE_ARG}" ] || die 3 "--clone ${CLONE_ARG} is not a Git working tree (no such folder)"
  CLONE_DIR="$(cd -- "${CLONE_ARG}" && pwd -P)" || die 3 "--clone ${CLONE_ARG} cannot be entered"
  top="$(git -C "${CLONE_DIR}" rev-parse --show-toplevel 2>/dev/null)" ||
    die 3 "--clone ${CLONE_ARG} is not a Git working tree"
  top="$(cd -- "${top}" && pwd -P)" || die 3 "--clone ${CLONE_ARG} is not a Git working tree"
  [ "${top}" = "${CLONE_DIR}" ] ||
    die 3 "--clone ${CLONE_ARG} is not the top folder of a Git working tree"
  [ "${CLONE_DIR}" != "${REPO_ROOT}" ] ||
    die 4 "refusing: --clone is this working tree; give the fresh clone of step E3"
  HEAD_SHA="$(head_commit)" || die 3 "this working tree has no commit"
  CLONE_HEAD="$(git -C "${CLONE_DIR}" rev-parse --verify -q HEAD 2>/dev/null)" ||
    die 3 "--clone ${CLONE_ARG} has no commit"
  [ "${CLONE_HEAD}" = "${HEAD_SHA}" ] ||
    die 3 "the clone's HEAD ${CLONE_HEAD} differs from this tree's HEAD ${HEAD_SHA}"
  info "clone HEAD equals this HEAD: ${HEAD_SHA}"
}

# Finds exactly one file per mandatory jar pattern in the clone; keeps
# "label|module|absolute path" lines in JAR_FOUND_LINES for collect_jars.
JAR_FOUND_LINES=""
check_jars_present() {
  step "jars"
  local spec label module glob f found n missing="" lines=""
  while IFS= read -r spec; do
    [ -n "${spec}" ] || continue
    label="${spec%%|*}"
    module="${spec#*|}"
    glob="${module#*|}"
    module="${module%%|*}"
    n=0
    found=""
    # Unquoted on purpose: the glob is expanded inside the clone.
    # shellcheck disable=SC2086
    for f in "${CLONE_DIR}"/${glob}; do
      if [ -f "${f}" ]; then
        n=$((n + 1))
        found="${f}"
      fi
    done
    if [ "${n}" -eq 0 ]; then
      missing="${missing} ${label}"
    elif [ "${n}" -gt 1 ]; then
      die 6 "more than one ${label} jar matches ${glob} in the clone; expected exactly one"
    else
      lines="${lines}${label}|${module}|${found}
"
    fi
  done <<<"${JAR_SPECS}"
  [ -z "${missing}" ] ||
    die 3 "mandatory jar missing in the clone:${missing} (build it in the clone with sbt -batch packageCheck, which assembles all four; or sbt -batch packageJobs for hadoop, entity-resolution and spark-analytics plus sbt -batch services/assembly)"
  JAR_FOUND_LINES="${lines}"
  info "all four mandatory jars present in the clone"
}

check_clean_tree() {
  step "clean-tree"
  require_clean_tree || die 4 "refusing: this working tree is dirty (listed above); the manifest describes a committed release candidate"
}

check_out_dir() {
  step "out-dir"
  local parent base
  parent="$(dirname -- "${OUT_ARG}")"
  base="$(basename -- "${OUT_ARG}")"
  case "${base}" in
    . | .. | /) die 4 "refusing: --out-dir ${OUT_ARG} must name a folder" ;;
  esac
  [ -d "${parent}" ] || die 3 "the parent folder of --out-dir ${OUT_ARG} does not exist"
  parent="$(cd -- "${parent}" && pwd -P)" || die 3 "the parent folder of --out-dir ${OUT_ARG} cannot be entered"
  OUT_DIR="${parent}/${base}"
  case "${OUT_DIR}/" in
    "${REPO_ROOT}/"*)
      [ "${OUT_DIR}" = "${REPO_ROOT}/${INSIDE_REPO_OUT_DIR}" ] ||
        die 4 "refusing: inside this repository --out-dir must be ${INSIDE_REPO_OUT_DIR}"
      ;;
  esac
  case "${OUT_DIR}/" in
    "${CLONE_DIR}/"*) die 4 "refusing: --out-dir is inside the clone" ;;
  esac
  if [ -L "${OUT_DIR}" ]; then
    die 4 "refusing: --out-dir ${OUT_ARG} is a symbolic link"
  fi
  if [ -e "${OUT_DIR}" ] && [ ! -d "${OUT_DIR}" ]; then
    die 4 "refusing: --out-dir ${OUT_ARG} exists and is not a folder"
  fi
  TARGET_DIR="${OUT_DIR}/${HEAD_SHA}"
  if [ -e "${TARGET_DIR}" ] || [ -L "${TARGET_DIR}" ]; then
    die 4 "refusing: ${TARGET_DIR} exists; an earlier manifest is never changed"
  fi
  if [ -d "${OUT_DIR}" ]; then
    local leftover
    for leftover in "${OUT_DIR}/.incomplete-${HEAD_SHA}-"*; do
      if [ -e "${leftover}" ]; then
        warn "a leftover incomplete folder exists: ${leftover} (never commit it)"
      fi
    done
  fi
  return 0
}

# ---- reading inputs (into the private folder WORK) ----------------------------------

# blob_sha256 PATH: sha256 of PATH as committed at HEAD. Status 6 if missing.
blob_sha256() {
  local p="$1"
  git cat-file -e "HEAD:${p}" 2>/dev/null || {
    printf 'REASON: %s is not committed at HEAD\n' "${p}" >&2
    return 6
  }
  git cat-file blob "HEAD:${p}" | sha256_stdin
}

# head_paths PATHSPEC...: committed file paths at HEAD, one per line.
head_paths() {
  git ls-tree -r --name-only HEAD -- "$@"
}

ndjson_to_array() {
  # $1 file of JSON lines -> JSON array on stdout
  if [ -s "$1" ]; then
    jq -s -c '.' "$1"
  else
    printf '[]'
  fi
}

collect_identity() {
  step "collect-identity"
  BUILD_SHA="$(build_commit)" || die 6 "the build commit cannot be computed (scripts/lib/identity.sh build_commit)"
  info "release candidate ${HEAD_SHA}; build commit ${BUILD_SHA}"
}

collect_signed_lines() {
  step "collect-approval-lines"
  local out rest rev path lineno text kind sha
  : >"${WORK}/signed-plans.ndjson"
  : >"${WORK}/signed-manifests.ndjson"
  : >"${WORK}/archives.ndjson"
  out="$(git grep -n -E '^(APPROVED-PLAN-SHA256|APPROVED-MANIFEST-SHA256|EVIDENCE-ARCHIVE-SHA256): [0-9a-f]{64}$' HEAD -- \
    ':(glob)docs/agent-runs/**/human-gate.md' ':(glob)docs/agent-runs/**/approvals.md' 2>/dev/null || true)"
  while IFS= read -r rest; do
    [ -n "${rest}" ] || continue
    IFS=':' read -r rev path lineno text <<<"${rest}"
    [ "${rev}" = "HEAD" ] || continue
    kind="${text%%:*}"
    sha="${text##* }"
    case "${kind}" in
      APPROVED-PLAN-SHA256)
        jq -n -c --arg s "${sha}" --arg f "${path}" --argjson l "${lineno}" \
          '{plan_sha256: $s, signed_in: {path: $f, line: $l}}' >>"${WORK}/signed-plans.ndjson"
        ;;
      APPROVED-MANIFEST-SHA256)
        jq -n -c --arg s "${sha}" --arg f "${path}" --argjson l "${lineno}" \
          '{sha256: $s, signed_in: {path: $f, line: $l}}' >>"${WORK}/signed-manifests.ndjson"
        ;;
      EVIDENCE-ARCHIVE-SHA256)
        jq -n -c --arg s "${sha}" --arg f "${path}" --argjson l "${lineno}" \
          '{kind: "evidence archive", location: "local only (outside Git; never a repository path)",
            sha256: $s, sha256_source: {path: $f, line: $l}}' >>"${WORK}/archives.ndjson"
        ;;
    esac
  done <<<"${out}"
  [ -s "${WORK}/signed-plans.ndjson" ] || die 6 "no committed APPROVED-PLAN-SHA256 line found at HEAD"
  info "signed plans: $(wc -l <"${WORK}/signed-plans.ndjson" | tr -d ' '); signed manifests: $(wc -l <"${WORK}/signed-manifests.ndjson" | tr -d ' '); local-only archives: $(wc -l <"${WORK}/archives.ndjson" | tr -d ' ')"
}

collect_release_records() {
  step "collect-release-records"
  local p signed
  : >"${WORK}/records.ndjson"
  signed="$(ndjson_to_array "${WORK}/signed-plans.ndjson")"
  while IFS= read -r p; do
    case "${p}" in
      releases/course/*.json) ;;
      *) continue ;;
    esac
    git cat-file blob "HEAD:${p}" |
      jq -c --arg path "${p}" --argjson signed "${signed}" '
        . as $r
        | ([$signed[] | select(.plan_sha256 == $r.plan_sha256) | .signed_in]) as $hits
        | {source: $path, attempt_id: (.attempt_id // null), environment: (.environment // null),
           region: (.region // null), commit: (.commit // null), plan_commit: (.plan_commit // null),
           build_commit: (.build_commit // null), plan_id: (.plan_id // null),
           plan_sha256: (.plan_sha256 // null), applied_utc: (.applied_utc // null),
           runtime_enabled: (if has("runtime_enabled") then .runtime_enabled else null end),
           image: {repository: (.image.repository // null), tag: (.image.tag // null),
                   digest: (.image.digest // null)},
           services: ((.services // {}) | with_entries(.value |= {
             task_definition: (.task_definition // null),
             previous_task_definition: (.previous_task_definition // null),
             image_tag: (.image_tag // null), digest_check: (.digest_check // null)})),
           approval_line_committed: ($hits | length > 0), approval_lines: $hits}' \
        >>"${WORK}/records.ndjson" || die 6 "release record ${p} is not readable JSON"
  done < <(head_paths releases/course)
  [ -s "${WORK}/records.ndjson" ] || die 6 "no release record under releases/course/ at HEAD"
  info "release records: $(wc -l <"${WORK}/records.ndjson" | tr -d ' ')"
}

collect_signed_experiment_manifests() {
  step "collect-experiment-manifests"
  local p sha signed
  : >"${WORK}/exp-manifests.ndjson"
  signed="$(ndjson_to_array "${WORK}/signed-manifests.ndjson")"
  while IFS= read -r p; do
    case "${p}" in
      experiments/manifests/*.json) ;;
      *) continue ;;
    esac
    sha="$(blob_sha256 "${p}")" || die 6 "cannot hash ${p} at HEAD"
    jq -n -c --arg p "${p}" --arg s "${sha}" --argjson signed "${signed}" '
      [$signed[] | select(.sha256 == $s) | .signed_in] as $hits
      | select($hits | length > 0) | {path: $p, sha256: $s, signed_in: $hits}' \
      >>"${WORK}/exp-manifests.ndjson"
  done < <(head_paths experiments/manifests)
  # Signed checksums without a matching committed manifest are listed too.
  jq -c -s --slurpfile m "${WORK}/exp-manifests.ndjson" '
    .[] | . as $s | select([$m[] | select(.sha256 == $s.sha256)] | length == 0)
    | {path: null, sha256: .sha256, signed_in: [.signed_in], note: "no committed experiments/manifests/*.json has this sha256 at HEAD"}' \
    "${WORK}/signed-manifests.ndjson" >"${WORK}/exp-unmatched.ndjson" || die 5 "could not match the signed manifest checksums"
  cat -- "${WORK}/exp-unmatched.ndjson" >>"${WORK}/exp-manifests.ndjson"
  info "signed experiment manifests: $(wc -l <"${WORK}/exp-manifests.ndjson" | tr -d ' ')"
}

# Line-based reading of the fixed layout of datasets/dataset-manifest.yaml
# (dataset-engineer): under "datasets:", each "  - id: X" block; its
# "    snapshot:" fields id, doi, publication_date, published, cutoff and file
# entries "        - {name: N, bytes: B, md5: M, ...}"; its "    checksums:"
# scalar keys ending in md5 or sha256 with a hex value, plus published and
# algorithm. Every value keeps its line number.
collect_datasets() {
  step "collect-datasets"
  local yaml="${WORK}/dataset-manifest.yaml" line n=0 in_ds=0 ds="" section="" key val
  local re_hex='^([0-9a-f]{32}|[0-9a-f]{64})$'
  local re_top='^[a-z_]+:' re_id='^  - id: (.+)$' re_sec='^    ([a-z_]+):' \
    re_snap='^      (id|doi|publication_date|published|cutoff): (.+)$' \
    re_sum='^      ([a-z0-9_]+): (.+)$' \
    re_file='^        - \{name: ([^,]+), bytes: ([0-9]+), md5: ([0-9a-f]{32})'
  git cat-file blob "HEAD:${DATASET_MANIFEST}" >"${yaml}" 2>/dev/null || die 6 "${DATASET_MANIFEST} is not committed at HEAD"
  : >"${WORK}/datasets.ndjson"
  while IFS= read -r line || [ -n "${line}" ]; do
    n=$((n + 1))
    if [[ "${line}" =~ ${re_top} ]]; then
      if [ "${line}" = "datasets:" ]; then in_ds=1; else in_ds=0; fi
      continue
    fi
    [ "${in_ds}" -eq 1 ] || continue
    if [[ "${line}" =~ ${re_id} ]]; then
      ds="${BASH_REMATCH[1]}"
      section=""
      continue
    fi
    if [[ "${line}" =~ ${re_sec} ]]; then
      section="${BASH_REMATCH[1]}"
      continue
    fi
    [ -n "${ds}" ] || continue
    if [ "${section}" = "snapshot" ] && [[ "${line}" =~ ${re_snap} ]]; then
      key="${BASH_REMATCH[1]}"
      val="${BASH_REMATCH[2]}"
      val="${val#\"}"
      val="${val%\"}"
      jq -n -c --arg d "${ds}" --arg k "${key}" --arg v "${val}" --argjson l "${n}" \
        '{dataset: $d, group: "snapshot", key: $k, value: $v, line: $l}' >>"${WORK}/datasets.ndjson"
    elif [ "${section}" = "snapshot" ] && [[ "${line}" =~ ${re_file} ]]; then
      jq -n -c --arg d "${ds}" --arg k "${BASH_REMATCH[1]}" --arg v "${BASH_REMATCH[3]}" --argjson l "${n}" \
        '{dataset: $d, group: "file_md5", key: $k, value: $v, line: $l}' >>"${WORK}/datasets.ndjson"
    elif [ "${section}" = "checksums" ] && [[ "${line}" =~ ${re_sum} ]]; then
      key="${BASH_REMATCH[1]}"
      val="${BASH_REMATCH[2]}"
      val="${val#\"}"
      val="${val%\"}"
      case "${key}" in
        *md5 | *sha256)
          [[ "${val}" =~ ${re_hex} ]] || continue
          ;;
        published | algorithm) ;;
        *) continue ;;
      esac
      jq -n -c --arg d "${ds}" --arg k "${key}" --arg v "${val}" --argjson l "${n}" \
        '{dataset: $d, group: "checksum", key: $k, value: $v, line: $l}' >>"${WORK}/datasets.ndjson"
    fi
  done <"${yaml}"
  [ -s "${WORK}/datasets.ndjson" ] || die 6 "no dataset snapshot or checksum read from ${DATASET_MANIFEST}"
  jq -s -c --arg src "${DATASET_MANIFEST}" --arg sha "$(sha256_stdin <"${yaml}")" '
    {source: $src, source_sha256: $sha,
     entries: (group_by(.dataset) | map({dataset: .[0].dataset,
       snapshot: [.[] | select(.group == "snapshot") | {key, value, line}],
       checksums: [.[] | select(.group == "checksum") | {key, value, line}],
       file_md5: [.[] | select(.group == "file_md5") | {file: .key, md5: .value, line}]}))}' \
    "${WORK}/datasets.ndjson" >"${WORK}/datasets.json"
  info "datasets: $(jq '.entries | length' "${WORK}/datasets.json")"
}

# run_folders DEPTH PATHSPEC: unique folder prefixes of DEPTH path components
# of committed files that lie below such a folder (sorted, one per line).
run_folders() {
  local depth="$1" prefix="$2" p rest folder prev="" i
  while IFS= read -r p; do
    folder=""
    rest="${p}"
    i=0
    while [ "${i}" -lt "${depth}" ]; do
      case "${rest}" in
        */*) ;;
        *)
          folder=""
          break
          ;;
      esac
      folder="${folder}${rest%%/*}/"
      rest="${rest#*/}"
      i=$((i + 1))
    done
    if [ -z "${folder}" ] || [ -z "${rest}" ]; then
      continue
    fi
    folder="${folder%/}"
    if [ "${folder}" != "${prev}" ]; then
      printf '%s\n' "${folder}"
      prev="${folder}"
    fi
  done < <(head_paths "${prefix}")
}

collect_run_ids() {
  step "collect-run-ids"
  local d status p id
  : >"${WORK}/attempts.ndjson"
  : >"${WORK}/evaluations.ndjson"
  : >"${WORK}/emulator.ndjson"
  : >"${WORK}/graph-runs.tsv"
  while IFS= read -r d; do
    status="null"
    if git cat-file -e "HEAD:${d}/run-record.json" 2>/dev/null; then
      status="$(git cat-file blob "HEAD:${d}/run-record.json" | jq -c '.status // null' 2>/dev/null)" || status="null"
      git cat-file blob "HEAD:${d}/run-record.json" | grep -oE 'run:v1:[0-9a-f]{32}' | while IFS= read -r id; do
        printf '%s\t%s\n' "${id}" "${d}/run-record.json"
      done >>"${WORK}/graph-runs.tsv" || true
    fi
    p="${d#experiments/raw/}"
    jq -n -c --arg path "${d}" --arg m "${p%%/*}" --arg a "${p#*/}" --argjson s "${status}" \
      '{path: $path, manifest_id: $m, attempt_id: $a, run_record_status: $s}' >>"${WORK}/attempts.ndjson"
  done < <(run_folders 4 experiments/raw)
  while IFS= read -r d; do
    p="${d#experiments/evaluation/raw/}"
    jq -n -c --arg path "${d}" --arg k "${p%%/*}" --arg r "${p#*/}" \
      '{path: $path, kind: $k, run_id: $r}' >>"${WORK}/evaluations.ndjson"
  done < <(run_folders 5 experiments/evaluation/raw)
  while IFS= read -r d; do
    jq -n -c --arg path "${d}" --arg r "${d##*/}" '{path: $path, run_id: $r}' >>"${WORK}/emulator.ndjson"
  done < <(run_folders 4 experiments/emulator/raw)
  [ -s "${WORK}/attempts.ndjson" ] || die 6 "no experiment attempt folder under experiments/raw/ at HEAD"
  [ -s "${WORK}/evaluations.ndjson" ] || die 6 "no evaluation run folder under experiments/evaluation/raw/ at HEAD"
  [ -s "${WORK}/emulator.ndjson" ] || die 6 "no emulator run folder under experiments/emulator/raw/ at HEAD"
  [ -s "${WORK}/graph-runs.tsv" ] || die 6 "no Spark graph run (run:v1:HEX32) named in experiments/raw/*/*/run-record.json at HEAD"
  jq -R -s -c 'split("\n") | map(select(length > 0) | split("\t")) | group_by(.[0])
    | map({run_id: .[0][0], named_in: (map(.[1]) | unique)})' "${WORK}/graph-runs.tsv" >"${WORK}/graph-runs.json"
  info "experiment attempts: $(wc -l <"${WORK}/attempts.ndjson" | tr -d ' '); evaluation runs: $(wc -l <"${WORK}/evaluations.ndjson" | tr -d ' '); emulator runs: $(wc -l <"${WORK}/emulator.ndjson" | tr -d ' '); Spark graph runs: $(jq 'length' "${WORK}/graph-runs.json")"
}

collect_prompts() {
  step "collect-prompts"
  local p sha
  : >"${WORK}/prompts.ndjson"
  while IFS= read -r p; do
    sha="$(blob_sha256 "${p}")" || die 6 "cannot hash ${p} at HEAD"
    jq -n -c --arg p "${p}" --arg n "${p##*/}" --arg s "${sha}" '{path: $p, file: $n, sha256: $s}' >>"${WORK}/prompts.ndjson"
  done < <(head_paths prompts)
  [ -s "${WORK}/prompts.ndjson" ] || die 6 "no file under prompts/ at HEAD"
  info "prompt files: $(wc -l <"${WORK}/prompts.ndjson" | tr -d ' ')"
}

collect_models() {
  step "collect-models"
  git cat-file blob "HEAD:${CONFIG_FILE}" >"${WORK}/course.json" 2>/dev/null || die 6 "${CONFIG_FILE} is not committed at HEAD"
  jq -c --arg src "${CONFIG_FILE}" '
    {source: $src,
     embedding_model: {field: ".models.embeddingModelId", id: (.models.embeddingModelId // null)},
     answer_model: {field: ".models.answerModelId", id: (.models.answerModelId // null)},
     embedding_provider: {field: ".embedding.provider", id: (.embedding.provider // null)},
     embedding_dimensions: {field: ".embedding.dimensions", id: (.embedding.dimensions // null)},
     answer_provider: {field: ".llm.provider", id: (.llm.provider // null)}}' \
    "${WORK}/course.json" >"${WORK}/models.json" || die 6 "${CONFIG_FILE} is not readable JSON"
  jq -e '.embedding_model.id != null and .answer_model.id != null' "${WORK}/models.json" >/dev/null ||
    die 6 "${CONFIG_FILE} lacks .models.embeddingModelId or .models.answerModelId"
}

collect_tests() {
  step "collect-tests"
  local line ts="" mt="" ct=""
  local re_ts='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z$' re_n='^[0-9]+$'
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      timestamp=*) ts="${line#timestamp=}" ;;
      module_tests=*) mt="${line#module_tests=}" ;;
      contract_tests=*) ct="${line#contract_tests=}" ;;
    esac
  done <"${MARKER_ARG}"
  [[ "${ts}" =~ ${re_ts} ]] || die 6 "--marker has no timestamp=YYYY-MM-DDTHH:MM:SS[.f]Z line"
  [[ "${mt}" =~ ${re_n} ]] || die 6 "--marker has no module_tests=N line"
  [[ "${ct}" =~ ${re_n} ]] || die 6 "--marker has no contract_tests=N line"
  jq -n -c --arg ts "${ts}" --argjson mt "${mt}" --argjson ct "${ct}" --arg sha "${CLONE_HEAD}" \
    --arg msha "$(sha256_stdin <"${MARKER_ARG}")" --arg mname "$(basename -- "${MARKER_ARG}")" '
    {label: "E3 (sbt clean check in the fresh clone)", tested_sha: $sha,
     tested_sha_source: "git rev-parse HEAD in the --clone folder",
     marker_timestamp: $ts, module_tests: $mt, contract_tests: $ct,
     source: {option: "--marker", file_name: $mname, sha256: $msha}}' >"${WORK}/tests.json"
  info "tests (E3, ${CLONE_HEAD}): module_tests=${mt} contract_tests=${ct} marker timestamp ${ts}"
}

collect_jars() {
  step "collect-jars"
  local spec label module path rel props commit dirty mod sha bytes
  : >"${WORK}/jars.ndjson"
  while IFS= read -r spec; do
    [ -n "${spec}" ] || continue
    label="${spec%%|*}"
    module="${spec#*|}"
    path="${module#*|}"
    module="${module%%|*}"
    rel="${path#"${CLONE_DIR}/"}"
    props="$(unzip -p "${path}" "${BUILD_INFO}" 2>/dev/null)" || die 6 "${label} jar ${rel}: no ${BUILD_INFO}"
    commit="$(printf '%s\n' "${props}" | grep -E '^buildCommit=' | head -n 1 | cut -d= -f2-)" || commit=""
    dirty="$(printf '%s\n' "${props}" | grep -E '^buildDirty=' | head -n 1 | cut -d= -f2-)" || dirty=""
    mod="$(printf '%s\n' "${props}" | grep -E '^module=' | head -n 1 | cut -d= -f2-)" || mod=""
    [ "${mod}" = "${module}" ] || die 6 "${label} jar ${rel}: module '${mod}', expected '${module}'"
    [ "${commit}" = "${BUILD_SHA}" ] ||
      die 6 "${label} jar ${rel}: buildCommit '${commit}' is not the build commit ${BUILD_SHA}; rebuild it in the clone"
    [ "${dirty}" = "false" ] || die 6 "${label} jar ${rel}: buildDirty is '${dirty}', expected false; rebuild it in a clean clone"
    sha="$(sha256_stdin <"${path}")"
    bytes="$(wc -c <"${path}" | tr -d ' ')"
    jq -n -c --arg l "${label}" --arg m "${module}" --arg p "${rel}" --arg s "${sha}" --argjson b "${bytes}" \
      --arg c "${commit}" '{name: $l, module: $m, path_in_clone: $p, sha256: $s, bytes: $b,
        status: "present", build_info: {buildCommit: $c, buildDirty: false, module: $m}}' >>"${WORK}/jars.ndjson"
    info "jar ${label}: ${rel} sha256 ${sha} (${bytes} bytes)"
  done <<<"${JAR_FOUND_LINES}"
  [ "$(wc -l <"${WORK}/jars.ndjson" | tr -d ' ')" = "4" ] || die 6 "expected four jar entries"
}

collect_key_artifacts() {
  step "collect-key-artifacts"
  local p sha list="${WORK}/key-paths.txt"
  {
    printf '%s\n' "${DATASET_MANIFEST}" "${CONFIG_FILE}" "experiments/evaluation/suite-v1.json" \
      "experiments/evaluation/evaluate.py" "${PACKAGE_CHECK_SCALA}"
    jq -r 'select(.path != null) | .path' "${WORK}/exp-manifests.ndjson"
    head_paths docs/agent-runs experiments | grep -E '(^|/)frozen-files-sha256-E12\.txt$|\.sha256$' || true
    jq -r '.sha256_source.path' "${WORK}/archives.ndjson"
    head_paths releases/course | grep -E '\.json$' || true
  } | grep -v '^$' | LC_ALL=C sort -u >"${list}"
  : >"${WORK}/key.ndjson"
  while IFS= read -r p; do
    sha="$(blob_sha256 "${p}")" || die 6 "key artifact ${p} is not committed at HEAD"
    jq -n -c --arg p "${p}" --arg s "${sha}" '{path: $p, sha256: $s}' >>"${WORK}/key.ndjson"
  done <"${list}"
  grep -q 'frozen-files-sha256-E12\.txt$' "${list}" || die 6 "no frozen-files-sha256-E12.txt committed at HEAD"
  info "key artifacts: $(wc -l <"${WORK}/key.ndjson" | tr -d ' ')"
}

assemble_json() {
  step "assemble"
  local script_sha
  script_sha="$(blob_sha256 "${SCRIPT_PATH}")" || die 6 "${SCRIPT_PATH} is not committed at HEAD"
  jq -n \
    --arg rc "${HEAD_SHA}" --arg build "${BUILD_SHA}" --arg tag "${PLANNED_TAG}" --arg now "$(sc_utc_now)" \
    --arg script "${SCRIPT_PATH}" --arg script_sha "${script_sha}" --arg rule "${RULE_LINE}" \
    --arg clone_name "$(basename -- "${CLONE_DIR}")" --arg clone_head "${CLONE_HEAD}" \
    --slurpfile records "${WORK}/records.ndjson" \
    --slurpfile plans "${WORK}/signed-plans.ndjson" \
    --slurpfile expm "${WORK}/exp-manifests.ndjson" \
    --slurpfile datasets "${WORK}/datasets.json" \
    --slurpfile graph "${WORK}/graph-runs.json" \
    --slurpfile attempts "${WORK}/attempts.ndjson" \
    --slurpfile evals "${WORK}/evaluations.ndjson" \
    --slurpfile emu "${WORK}/emulator.ndjson" \
    --slurpfile archives "${WORK}/archives.ndjson" \
    --slurpfile prompts "${WORK}/prompts.ndjson" \
    --slurpfile models "${WORK}/models.json" \
    --slurpfile tests "${WORK}/tests.json" \
    --slurpfile jars "${WORK}/jars.ndjson" \
    --slurpfile key "${WORK}/key.ndjson" \
    '{kind: "scholarchain-release-manifest", version: 1, generated_utc: $now,
      generator: {script: $script, script_sha256_at_head: $script_sha},
      release_candidate: {commit: $rc, source: "git rev-parse HEAD (this working tree, clean)"},
      build_commit: {commit: $build, source: "scripts/lib/identity.sh build_commit (git log -1 --format=%H -- build.sbt project modules prompts docker config .scalafmt.conf .jvmopts .sbtopts)"},
      planned_tag: {name: $tag, source: "docs/agent-runs/phase-17/prompt.md decision 18, E13"},
      clone: {folder_name: $clone_name, head: $clone_head, head_equals_release_candidate: ($clone_head == $rc)},
      release_records: $records,
      signed_plans: $plans,
      signed_experiment_manifests: $expm,
      datasets: $datasets[0],
      run_ids: {spark_graph_runs: $graph[0], experiment_attempts: $attempts,
                evaluation_runs: $evals, emulator_runs: $emu, local_only: $archives,
                source: "git ls-tree -r --name-only HEAD (committed paths only)"},
      prompts: $prompts,
      models: $models[0],
      tests: $tests[0],
      jars: $jars,
      jar_source: "the four jars project/PackageCheck.scala opens (PackageCheck.Inputs), at the paths scripts/package.sh check_jars uses; none is optional",
      key_artifacts: $key,
      key_artifacts_source: "git cat-file blob HEAD:PATH, sha256",
      rule_line: $rule,
      identifier_check: "passed before writing (see scripts/release-manifest.sh --help)"}' \
    >"${WORK}/release-manifest.json" || die 5 "could not assemble the manifest JSON"
}

render_markdown() {
  step "render-markdown"
  jq -r '
    def cell: tostring | gsub("\\|"; "\\|") | gsub("\n"; " ");
    def lc(x): if x == null then "-" else (x | cell) end;
    "# ScholarChain release manifest",
    "",
    "Generated by `\(.generator.script)` at \(.generated_utc) (UTC). Machine-readable copy: `release-manifest.json` in this folder.",
    "",
    "| Item | Value | Source |",
    "|---|---|---|",
    "| Release candidate commit | `\(.release_candidate.commit)` | \(.release_candidate.source) |",
    "| Build commit | `\(.build_commit.commit)` | scripts/lib/identity.sh build_commit |",
    "| Planned tag | `\(.planned_tag.name)` | \(.planned_tag.source) |",
    "| Clone HEAD | `\(.clone.head)` (folder `\(.clone.folder_name)`) | git rev-parse HEAD in the clone |",
    "| Generator sha256 at HEAD | `\(.generator.script_sha256_at_head)` | \(.generator.script) |",
    "",
    "## Rule for commits after the release candidate",
    "",
    .rule_line,
    "",
    "## Release records (releases/course/)",
    "",
    "| Record | Commit | Plan sha256 | Approval line committed | Environment | Runtime | Image tag | Image digest | Task definitions |",
    "|---|---|---|---|---|---|---|---|---|",
    (.release_records[] |
      "| `\(.source)` | `\(lc(.commit))` | `\(lc(.plan_sha256))` | \(if .approval_line_committed then "yes: " + ([.approval_lines[] | "\(.path):\(.line)"] | join(", ")) else "no" end) | \(lc(.environment)) | \(lc(.runtime_enabled)) | \(lc(.image.tag)) | \(lc(.image.digest)) | \([.services | to_entries[] | "\(.key)=\(.value.task_definition // "-")"] | join(", ")) |"),
    "",
    "## Signed plan checksums",
    "",
    "| Plan sha256 | Signed in |",
    "|---|---|",
    (.signed_plans[] | "| `\(.plan_sha256)` | \(.signed_in.path):\(.signed_in.line) |"),
    "",
    "## Signed experiment manifests",
    "",
    "| Manifest | sha256 | Signed in |",
    "|---|---|---|",
    (.signed_experiment_manifests[] | "| \(lc(.path)) | `\(.sha256)` | \([.signed_in[] | "\(.path):\(.line)"] | join(", ")) |"),
    "",
    "## Dataset snapshots and checksums (\(.datasets.source), sha256 `\(.datasets.source_sha256)`)",
    "",
    "| Dataset | Kind | Key | Value | Line |",
    "|---|---|---|---|---|",
    (.datasets.entries[] | . as $d |
      ((.snapshot[] | "| \($d.dataset) | snapshot | \(.key) | \(lc(.value)) | \(.line) |"),
       (.checksums[] | "| \($d.dataset) | checksum | \(.key) | \(lc(.value)) | \(.line) |"),
       (.file_md5[] | "| \($d.dataset) | file md5 | \(.file) | \(.md5) | \(.line) |"))),
    "",
    "## Run IDs (committed paths at HEAD)",
    "",
    "| Kind | ID | Path or source |",
    "|---|---|---|",
    (.run_ids.spark_graph_runs[] | "| Spark graph run | `\(.run_id)` | named in \(.named_in | join(", ")) |"),
    (.run_ids.experiment_attempts[] | "| experiment attempt (\(.manifest_id)) | `\(.attempt_id)` | `\(.path)` (status \(lc(.run_record_status))) |"),
    (.run_ids.evaluation_runs[] | "| evaluation run (\(.kind)) | `\(.run_id)` | `\(.path)` |"),
    (.run_ids.emulator_runs[] | "| emulator run | `\(.run_id)` | `\(.path)` |"),
    (.run_ids.local_only[] | "| \(.kind) | sha256 `\(.sha256)` | \(.location); checksum committed in \(.sha256_source.path):\(.sha256_source.line) |"),
    "",
    "## Prompt versions",
    "",
    "| File | sha256 |",
    "|---|---|",
    (.prompts[] | "| `\(.path)` | `\(.sha256)` |"),
    "",
    "## Models (\(.models.source))",
    "",
    "| Field | Value |",
    "|---|---|",
    (.models | to_entries[] | .value as $m | select($m | type == "object") | "| `\($m.field)` | \(lc($m.id)) |"),
    "",
    "## Test counts",
    "",
    "| Label | Tested SHA | Marker timestamp | Module tests | Contract tests | Marker sha256 |",
    "|---|---|---|---|---|---|",
    "| \(.tests.label) | `\(.tests.tested_sha)` | \(.tests.marker_timestamp) | \(.tests.module_tests) | \(.tests.contract_tests) | `\(.tests.source.sha256)` |",
    "",
    "## Mandatory jars (in the clone)",
    "",
    "| Jar | Path in clone | sha256 | Bytes | buildCommit |",
    "|---|---|---|---|---|",
    (.jars[] | "| \(.name) | `\(.path_in_clone)` | `\(.sha256)` | \(.bytes) | `\(.build_info.buildCommit)` |"),
    "",
    "## Key committed artifacts",
    "",
    "| Path | sha256 |",
    "|---|---|",
    (.key_artifacts[] | "| `\(.path)` | `\(.sha256)` |")
  ' "${WORK}/release-manifest.json" >"${WORK}/release-manifest.md" || die 5 "could not render the Markdown manifest"
}

# ---- identifier check ----------------------------------------------------------------

# scan_identifiers FILE LABEL: prints one IDENTIFIER-CHECK line (label, line,
# kind; never the value) per finding on standard error; returns 8 when any.
scan_identifiers() {
  local file="$1" label="$2" found=0 ln tok full kind pattern entry
  local patterns="arn-with-account|arn:aws[a-z-]*:[a-z0-9-]+:[a-z0-9-]*:[0-9]{12}:
aws-host|amazonaws\\.(com|cn)|execute-api\\.|\\.elb\\.
s3-uri-with-bucket|s3://[^{]
bucket-name|${SC_DATA_BUCKET_PATTERN}|${SC_VECTOR_BUCKET_PATTERN}
access-key|(AKIA|ASIA)[0-9A-Z]{16}
anthropic-key|sk-ant-
voyage-key|(^|[^A-Za-z0-9])pa-[A-Za-z0-9_-]{20,}"
  while IFS= read -r entry; do
    [ -n "${entry}" ] || continue
    kind="${entry%%|*}"
    pattern="${entry#*|}"
    while IFS= read -r ln; do
      [ -n "${ln}" ] || continue
      printf 'IDENTIFIER-CHECK: %s line %s holds a %s\n' "${label}" "${ln}" "${kind}" >&2
      found=1
    done < <(grep -n -E -- "${pattern}" "${file}" | cut -d: -f1 || true)
  done <<<"${patterns}"
  # Twelve-digit tokens: an alphanumeric run of exactly 12 digits.
  while IFS=: read -r ln tok; do
    [[ "${tok}" =~ ^[0-9]{12}$ ]] || continue
    case " ${PLACEHOLDER_ACCOUNTS} " in
      *" ${tok} "*) continue ;;
    esac
    full="$(git rev-parse -q --verify "${tok}^{commit}" 2>/dev/null || true)"
    if [ -n "${full}" ] && [ "${full:0:12}" = "${tok}" ]; then
      continue
    fi
    printf 'IDENTIFIER-CHECK: %s line %s holds a twelve-digit number that is not a placeholder or a commit prefix\n' \
      "${label}" "${ln}" >&2
    found=1
  done < <(grep -n -o -E '[0-9A-Za-z]+' "${file}" | grep -E ':[0-9]{12}$' || true)
  [ "${found}" -eq 0 ] || return 8
  return 0
}

identifier_check() {
  step "identifier-check"
  register_bucket_patterns "${NAME_PREFIX}" || die 5 "could not register the bucket name patterns"
  local rc=0
  scan_identifiers "${WORK}/release-manifest.json" "release-manifest.json" || rc=$?
  scan_identifiers "${WORK}/release-manifest.md" "release-manifest.md" || rc=$?
  [ "${rc}" -eq 0 ] || die 8 "an identifier would be written (listed above); nothing was written to ${OUT_DIR}"
  info "identifier check passed on both documents"
}

# ---- write, validate, publish ----------------------------------------------------------

validate_files() {
  # $1 folder holding both files
  local dir="$1" sha
  jq empty "${dir}/release-manifest.json" >/dev/null 2>&1 || return 6
  jq -e --arg rc "${HEAD_SHA}" --arg tag "${PLANNED_TAG}" '
    def hex64: type == "string" and test("^[0-9a-f]{64}$");
    def hex40: type == "string" and test("^[0-9a-f]{40}$");
    .kind == "scholarchain-release-manifest"
    and .release_candidate.commit == $rc and (.build_commit.commit | hex40)
    and .planned_tag.name == $tag
    and (.release_records | length > 0)
    and all(.release_records[]; (.commit | hex40) and (.plan_sha256 | hex64) and .environment != null
            and (.approval_line_committed | type == "boolean"))
    and (.signed_plans | length > 0) and all(.signed_plans[]; .plan_sha256 | hex64)
    and (.signed_experiment_manifests | type == "array")
    and (.datasets.entries | length > 0) and (.datasets.source_sha256 | hex64)
    and (.run_ids.spark_graph_runs | length > 0) and (.run_ids.experiment_attempts | length > 0)
    and (.run_ids.evaluation_runs | length > 0) and (.run_ids.emulator_runs | length > 0)
    and (.prompts | length > 0) and all(.prompts[]; .sha256 | hex64)
    and .models.embedding_model.id != null and .models.answer_model.id != null
    and .tests.tested_sha == $rc and (.tests.module_tests | type == "number")
    and (.tests.contract_tests | type == "number")
    and (.jars | length == 4) and all(.jars[]; (.sha256 | hex64) and .status == "present")
    and (.key_artifacts | length > 0) and all(.key_artifacts[]; .sha256 | hex64)
    and (.rule_line | type == "string" and length > 0)' "${dir}/release-manifest.json" >/dev/null 2>&1 ||
    return 6
  [ -s "${dir}/release-manifest.md" ] || return 6
  grep -qF -- "${HEAD_SHA}" "${dir}/release-manifest.md" || return 6
  grep -qF -- "${PLANNED_TAG}" "${dir}/release-manifest.md" || return 6
  grep -qxF -- "${RULE_LINE}" "${dir}/release-manifest.md" || return 6
  while IFS= read -r sha; do
    grep -qF -- "${sha}" "${dir}/release-manifest.md" || return 6
  done < <(jq -r '.jars[].sha256' "${dir}/release-manifest.json")
  return 0
}

write_and_publish() {
  step "write"
  mkdir -p -- "${OUT_DIR}" || die 5 "cannot create ${OUT_DIR}"
  [ ! -L "${OUT_DIR}" ] || die 4 "refusing: ${OUT_DIR} is a symbolic link"
  if [ -e "${TARGET_DIR}" ] || [ -L "${TARGET_DIR}" ]; then
    die 4 "refusing: ${TARGET_DIR} exists; an earlier manifest is never changed"
  fi
  INCOMPLETE_DIR="$(mktemp -d "${OUT_DIR}/.incomplete-${HEAD_SHA}-XXXXXX")" ||
    die 5 "cannot create the temporary folder in ${OUT_DIR}"
  cp -- "${WORK}/release-manifest.json" "${INCOMPLETE_DIR}/release-manifest.json" || die 5 "could not write release-manifest.json"
  cp -- "${WORK}/release-manifest.md" "${INCOMPLETE_DIR}/release-manifest.md" || die 5 "could not write release-manifest.md"

  step "validate"
  validate_files "${INCOMPLETE_DIR}" || die 6 "the written files failed validation (jq empty, a mandatory field, or the Markdown content)"
  if ! scan_identifiers "${INCOMPLETE_DIR}/release-manifest.json" "release-manifest.json" ||
    ! scan_identifiers "${INCOMPLETE_DIR}/release-manifest.md" "release-manifest.md"; then
    die 8 "an identifier is in the written files (listed above)"
  fi
  [ "$(head_commit)" = "${HEAD_SHA}" ] || die 4 "refusing: HEAD moved during the run"

  step "publish"
  chmod 0755 "${INCOMPLETE_DIR}" || die 5 "chmod of the temporary folder failed"
  chmod 0644 "${INCOMPLETE_DIR}/release-manifest.json" "${INCOMPLETE_DIR}/release-manifest.md" ||
    die 5 "chmod of the written files failed"
  if [ -e "${TARGET_DIR}" ] || [ -L "${TARGET_DIR}" ]; then
    die 4 "refusing: ${TARGET_DIR} appeared during the run"
  fi
  mv -- "${INCOMPLETE_DIR}" "${TARGET_DIR}" || die 5 "could not rename the temporary folder to ${TARGET_DIR}"
  if [ -f "${TARGET_DIR}/release-manifest.json" ] && [ -f "${TARGET_DIR}/release-manifest.md" ] &&
    [ ! -e "${TARGET_DIR}/$(basename -- "${INCOMPLETE_DIR}")" ]; then
    PUBLISHED="true"
  else
    INCOMPLETE_DIR="${TARGET_DIR}/$(basename -- "${INCOMPLETE_DIR}")"
    die 5 "the rename did not produce ${TARGET_DIR} as expected; inspect it, never commit it"
  fi
  printf 'MANIFEST-FOLDER: %s\n' "${TARGET_DIR}"
}

print_summary() {
  step "summary"
  local m="${TARGET_DIR}/release-manifest.json"
  printf 'SUMMARY: release-manifest rc=%s build=%s tag=%s\n' "${HEAD_SHA}" "${BUILD_SHA}" "${PLANNED_TAG}"
  printf 'SUMMARY: release-manifest release_records=%s with_committed_approval=%s signed_plans=%s signed_experiment_manifests=%s\n' \
    "$(jq '.release_records | length' "${m}")" "$(jq '[.release_records[] | select(.approval_line_committed)] | length' "${m}")" \
    "$(jq '.signed_plans | length' "${m}")" "$(jq '.signed_experiment_manifests | length' "${m}")"
  printf 'SUMMARY: release-manifest datasets=%s spark_graph_runs=%s experiment_attempts=%s evaluation_runs=%s emulator_runs=%s local_only=%s\n' \
    "$(jq '.datasets.entries | length' "${m}")" "$(jq '.run_ids.spark_graph_runs | length' "${m}")" \
    "$(jq '.run_ids.experiment_attempts | length' "${m}")" "$(jq '.run_ids.evaluation_runs | length' "${m}")" \
    "$(jq '.run_ids.emulator_runs | length' "${m}")" "$(jq '.run_ids.local_only | length' "${m}")"
  printf 'SUMMARY: release-manifest prompts=%s jars=%s jars_not_present=0 key_artifacts=%s module_tests=%s contract_tests=%s\n' \
    "$(jq '.prompts | length' "${m}")" "$(jq '.jars | length' "${m}")" "$(jq '.key_artifacts | length' "${m}")" \
    "$(jq '.tests.module_tests' "${m}")" "$(jq '.tests.contract_tests' "${m}")"
}

main() {
  parse_args "$@"
  enter_repo_root
  check_tools
  check_marker_exists
  check_clone
  check_jars_present
  check_clean_tree
  check_out_dir
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-release-manifest.XXXXXX")" || die 5 "cannot create a private temporary folder"
  collect_identity
  collect_signed_lines
  collect_release_records
  collect_signed_experiment_manifests
  collect_datasets
  collect_run_ids
  collect_prompts
  collect_models
  collect_tests
  collect_jars
  collect_key_artifacts
  assemble_json
  render_markdown
  identifier_check
  write_and_publish
  print_summary
  step "done"
}

main "$@"
