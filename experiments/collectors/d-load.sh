#!/usr/bin/env bash
# experiments/collectors/d-load.sh - experiment D (query load): runs
# load-tests/query-load.sh with the mode and levels from its arguments and
# samples the three ALB services' desired, running and pending task counts
# every 15 s (Phase 15 work order, decision 11 item 4).
# Owner: experiment-engineer. A "command" action of scripts/run-experiment.sh;
# run by the human only, never by an agent.
#
# runtime: untested
# static: shellcheck (6b report); bash 3.2 constructs only.

set -Eeuo pipefail
umask 077

readonly TOOL_NAME="d-load.sh"
readonly TOOL_PATH="experiments/collectors/d-load.sh"
COLLECTOR_DIR="$(cd "$(dirname "$0")" && pwd -P)"
# shellcheck source=experiments/collectors/lib/collector-common.sh
. "${COLLECTOR_DIR}/lib/collector-common.sh"

readonly LOAD_TOOL="load-tests/query-load.sh"
# Upper bound of the sampler: query-load.sh's own run deadline for the default
# levels is about 1,820 s (query) or 1,740 s (read) (6a report); the sampler
# stops when the load tool ends and never runs past this bound.
readonly SAMPLER_LIMIT_SECONDS=3600
readonly CHILD_GRACE_SECONDS=20

MODE=""
FWD=()
CLUSTER=""
SERVICES=()
CHILD_PID=""

usage() {
  cat <<'EOF'
Usage: experiments/collectors/d-load.sh --mode query|read [query-load.sh options] [--preflight]

Experiment D (Phase 15 decision 11 item 4). Run from the repository root by
scripts/run-experiment.sh. Every argument except --preflight is passed
unchanged to load-tests/query-load.sh (its usage text is authoritative:
--levels, --level-seconds, --max-requests-per-level, --pause-seconds,
--list); query-load.sh writes its raw files into the same run folder
($SCHOLARCHAIN_EXPERIMENT_RUN_DIR). Needs SCHOLARCHAIN_SERVICE_API_KEY
(query-load.sh reads it; never printed).

While the load tool runs, every 15 s: desired, running and pending of the
query, evidence and graph services (describe-services) -> service-samples.csv.

--preflight: query-load.sh --preflight (GET /healthz and /readyz, the list's
format; read mode: one GET per listed ID; never a POST /v1/query) plus one
describe-services read printed as counts; writes nothing outside temporary
folders outside the repository.

Files (run folder): service-samples.csv, tool-record.json (plus the
load-tool files of query-load.sh).

Exit codes: query-load.sh's own (0 ran, whatever the API answered; 2 usage;
3 prerequisite not met; 5 tool failure; 7 its deadline or TERM); a failed
sample never changes it (the record lists the errors).
EOF
}

parse_args() {
  local a prev=""
  for a in "$@"; do
    case "${a}" in
      --help | -h)
        usage
        exit 0
        ;;
      --preflight) ;;
      *) FWD+=("${a}") ;;
    esac
    if [ "${prev}" = "--mode" ]; then
      MODE="${a}"
    fi
    prev="${a}"
  done
  case "${MODE}" in
    query | read) ;;
    *) cc_die 2 "--mode query|read is required" ;;
  esac
}

sample_once() {
  cc_sample_service service-samples.csv "${CLUSTER}" "${SERVICES[@]}"
}

# On TERM or INT, give query-load.sh a bounded moment to write its own record
# (called by cc_on_signal in lib/collector-common.sh).
# shellcheck disable=SC2329
cc_signal_hook() {
  cc_wait_child "${CHILD_PID}" "${CHILD_GRACE_SECONDS}"
}

main() {
  cc_init "${TOOL_NAME}" "${TOOL_PATH}" "$@"
  parse_args "$@"
  cc_prepare_out service-samples.csv
  cc_tf_outputs
  cc_step "names"
  cc_tf_required CLUSTER '.ecs_cluster_name.value' "ecs_cluster_name"
  local q e g
  cc_tf_required q '.ecs_service_names.value.query' "ecs_service_names.query"
  cc_tf_required e '.ecs_service_names.value.evidence' "ecs_service_names.evidence"
  cc_tf_required g '.ecs_service_names.value.graph' "ecs_service_names.graph"
  SERVICES=("${q}" "${e}" "${g}")
  [ -f "${LOAD_TOOL}" ] || cc_die 3 "${LOAD_TOOL} is missing"
  CC_LIMITS_JSON="$(jq -n --arg m "${MODE}" --argjson s "${CC_SAMPLE_SECONDS}" --argjson l "${SAMPLER_LIMIT_SECONDS}" \
    --arg sha "$(cc_sha256 "${LOAD_TOOL}")" \
    '{mode: $m, sample_interval_s: $s, sampler_limit_s: $l, load_tool: "load-tests/query-load.sh", load_tool_sha256: $sha,
      note: "request caps, levels and durations are query-load.sh limits (load-tool-record.json)"}')"
  local rc=0
  if [ "${CC_PREFLIGHT}" = "true" ]; then
    cc_step "preflight"
    local out="${CC_TMP}/pf-svc.json"
    cc_aws_read "${out}" ecs describe-services --cluster "${CLUSTER}" --services "${SERVICES[@]}" ||
      cc_die 5 "describe-services failed (${CC_LAST_AWS_ERROR})"
    jq -r '.services[]? | "PREFLIGHT: service \(.serviceName) desired=\(.desiredCount) running=\(.runningCount) pending=\(.pendingCount)"' "${out}"
    bash "${LOAD_TOOL}" ${FWD[@]+"${FWD[@]}"} --preflight </dev/null &
    CHILD_PID="$!"
    wait "${CHILD_PID}" || rc=$?
    CHILD_PID=""
    printf 'PREFLIGHT: %s --preflight exit=%s\n' "${LOAD_TOOL}" "${rc}"
    # exit (not return): a non-zero return would trip the ERR trap (exit 5)
    exit "${rc}"
  fi
  cc_file service-samples.csv "epoch_s,service,desired,running,pending"
  sample_once
  cc_sampler_start "${SAMPLER_LIMIT_SECONDS}" sample_once
  cc_step "load"
  bash "${LOAD_TOOL}" ${FWD[@]+"${FWD[@]}"} </dev/null &
  CHILD_PID="$!"
  wait "${CHILD_PID}" || rc=$?
  CHILD_PID=""
  cc_bg_stop
  cc_step "after-load"
  sample_once
  cc_sampler_merge_errors
  cc_set CC_COUNTS_JSON load_tool_exit "${rc}"
  cc_set CC_COUNTS_JSON service_sample_rows "$(($(grep -c . "${CC_OUT}/service-samples.csv" || true) - 1))"
  cc_set_str CC_EVIDENCE_JSON task_counts "service-samples.csv (describe-services every ${CC_SAMPLE_SECONDS}s; Container Insights is off)"
  cc_set_str CC_EVIDENCE_JSON requests "load-requests.csv, load-levels.csv, load-explain.csv (query) or id-check.csv (read), load-clock.csv, load-tool-record.json (query-load.sh)"
  cc_set_str CC_OBS_JSON result "see load-levels.csv (classes and percentiles per level) and service-samples.csv (task counts); scale-out not expected under the deployed limits (decision 3)"
  if [ "${rc}" -eq 0 ]; then
    CC_STATUS="completed"
  fi
  # query-load.sh's exit code is the collector's (exit, not return: see above)
  exit "${rc}"
}

main "$@"
