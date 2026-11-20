#!/usr/bin/env bash
# experiments/collectors/ab-pipeline.sh - experiments A and B: runs
# scripts/run-bounded-pipeline.sh with its arguments unchanged (one hadoop or
# spark stage), then extracts, as fields only, the evidence the spec asks for
# while it still exists (Phase 15 work order, decision 11 item 4).
# Owner: experiment-engineer. A "command" action of scripts/run-experiment.sh;
# run by the human only, never by an agent.
#
# runtime: untested
# static: shellcheck (6b report); bash 3.2 constructs only.

set -Eeuo pipefail
umask 077

readonly TOOL_NAME="ab-pipeline.sh"
readonly TOOL_PATH="experiments/collectors/ab-pipeline.sh"
COLLECTOR_DIR="$(cd "$(dirname "$0")" && pwd -P)"
# shellcheck source=experiments/collectors/lib/collector-common.sh
. "${COLLECTOR_DIR}/lib/collector-common.sh"

readonly PIPELINE="scripts/run-bounded-pipeline.sh"
readonly RECORD_DIR="evidence/runs"
readonly LOGICAL_PREFIX="s3://{data-bucket}/"
# EMR cluster end after the execution (the state machine terminates it):
# bounded wait for a terminal state.
readonly CLUSTER_END_LIMIT=900
# EMR pushes step logs to the log URI every few minutes and at termination.
readonly STEP_LOG_LIMIT=600
readonly STEP_LOG_MAX_BYTES=268435456
readonly POLL_SECONDS=30
readonly CHILD_GRACE_SECONDS=25
# COSTS.md:39 (estimate, unverified): m5.xlarge EC2 about $0.192 + EMR about
# $0.048 per instance-hour.
readonly INSTANCE_HOUR_USD="0.240"

STAGE=""
HADOOP_OUTPUT=""
OUT_ROOT=""
DBLP=""
DTD=""
HADOOP_EXPORT=""
IDENTITY=""
EMR_MAX=""
SHUFFLE=""
FWD=()
CHILD_PID=""
DATA_BUCKET=""
SM_ARN=""
LOG_URI=""
NESTED=""
STARTED_AT=""

usage() {
  cat <<'EOF'
Usage: experiments/collectors/ab-pipeline.sh PIPELINE-ARGUMENTS... [--preflight]

Experiments A and B (Phase 15 decision 11 item 4). Run from the repository
root by scripts/run-experiment.sh. Every argument except a trailing
--preflight goes UNCHANGED to bash scripts/run-bounded-pipeline.sh (its usage
text is authoritative); only --stage hadoop and --stage spark are accepted.
The pipeline's output is not wrapped, so its "RUN-RECORD:" and "EXECUTION:"
lines reach the runner as they are ({emr-cluster-id} resolution).

After the pipeline ends (whatever its exit code), from its run record under
evidence/runs/ (found by its input: --hadoop-output or --out-root), as
fields only (never a raw copy of a file that names S3 locations):
  emr-cluster.json        per cluster: created, ready, termination requested
                          (Step Functions history), ended, final state, reason
                          code, managed-scaling compute limits
  emr-instance-groups.csv requested and running instances per group
  emr-instances.csv       per instance: group, type, market, created, ready,
                          ended (the lifecycle record of provisioned and
                          released capacity; no instance ID, no address)
  emr-steps.csv           step name, state, start, end
  sfn-events.csv          execution history: time, event type, state name
  hadoop-job-counters.csv (hadoop) every _job.json under this attempt's own
                          output root: job, run ID, ScholarChain counters and
                          stats
  hadoop-step-counters.csv (hadoop) from the EMR step logs (syslog, stderr):
                          input splits per MapReduce job (= map tasks
                          planned); reduce counts are not logged (missing)
  spark-stage-metrics.csv, spark-stage-timings.csv, spark-outputs.csv,
  spark-conf.json         (spark) from the graph run's _run_report.json and
                          _run_manifest.json: tasks, stages, shuffle bytes and
                          records per stage group, stage durations, records out
                          per table, spark.sql.shuffle.partitions
  ab-evidence.json        the evidence checklist (found / partial / missing)
  clock.csv, tool-record.json

--preflight: read calls only: the input locations exist (DBLP and DTD, or the
J09 export's _job.json and the ER run's _SUCCESS), the ER record under
evidence/runs/ says succeeded for these identity assignments, the output root
(--hadoop-output or --out-root) holds no object yet, the jars of the build
commit exist; prints states only; starts nothing.

Exit codes: the pipeline's own (0 succeeded; 2, 3, 4, 5, 6 failed; 7 timed
out, remote work may still run), so the runner records "the action failed"
for any non-zero; extraction gaps never change it (ab-evidence.json says
missing). Preflight: 0, 2 usage, 3 a prerequisite not met, 5 a read failed.
EOF
}

parse_args() {
  local args=("$@") i=0 n="$#" a v last_extra=""
  while [ "${i}" -lt "${n}" ]; do
    a="${args[i]}"
    if [ "${a}" = "--preflight" ]; then
      i=$((i + 1))
      continue
    fi
    if [ "${a}" = "--help" ] || [ "${a}" = "-h" ]; then
      usage
      exit 0
    fi
    FWD+=("${a}")
    v=""
    if [ $((i + 1)) -lt "${n}" ]; then
      v="${args[i + 1]}"
    fi
    case "${a}" in
      --stage) STAGE="${v}" ;;
      --hadoop-output) HADOOP_OUTPUT="${v}" ;;
      --out-root) OUT_ROOT="${v}" ;;
      --dblp) DBLP="${v}" ;;
      --hadoop-export) HADOOP_EXPORT="${v}" ;;
      --identity-assignments) IDENTITY="${v}" ;;
      --emr-max-units) EMR_MAX="${v}" ;;
      --spark-shuffle-partitions) SHUFFLE="${v}" ;;
      --hadoop-extra-arg)
        # the DTD comes as two extra arguments in order: --dblp-dtd, LOCATION
        if [ "${last_extra}" = "--dblp-dtd" ]; then
          DTD="${v}"
        fi
        last_extra="${v}"
        FWD+=("${v}")
        i=$((i + 1))
        ;;
    esac
    i=$((i + 1))
  done
  case "${STAGE}" in
    hadoop)
      [ -n "${HADOOP_OUTPUT}" ] || cc_die 2 "--stage hadoop needs its own --hadoop-output (G-20: the record lists every run under the root)"
      [ -n "${DBLP}" ] || cc_die 2 "--stage hadoop needs --dblp"
      ;;
    spark)
      [ -n "${OUT_ROOT}" ] && [ -n "${HADOOP_EXPORT}" ] && [ -n "${IDENTITY}" ] ||
        cc_die 2 "--stage spark needs --out-root, --hadoop-export and --identity-assignments"
      ;;
    *) cc_die 2 "--stage must be hadoop or spark" ;;
  esac
  local a2
  for a2 in "${HADOOP_OUTPUT}" "${OUT_ROOT}" "${DBLP}" "${HADOOP_EXPORT}" "${IDENTITY}" "${DTD}"; do
    [ -z "${a2}" ] || case "${a2}" in
      "${LOGICAL_PREFIX}"*) ;;
      *) cc_die 2 "S3 arguments must use the logical form s3://{data-bucket}/KEY" ;;
    esac
  done
}

# key_of LOGICAL: the object key (bucket part removed).
key_of() { printf '%s' "${1#"${LOGICAL_PREFIX}"}"; }

# ---- preflight --------------------------------------------------------------------------------------

PF_BAD=0
pf_object() {
  # pf_object WHAT KEY: head-object; prints present or missing (no location).
  local out="${CC_TMP}/pf-head.json"
  if cc_aws_raw "${out}" s3api head-object --bucket "${DATA_BUCKET}" --key "$2"; then
    printf 'PREFLIGHT: %s: present (%s bytes)\n' "$1" "$(jq -r '.ContentLength // "?"' "${out}")"
  else
    printf 'PREFLIGHT: %s: MISSING (%s)\n' "$1" "${CC_LAST_AWS_ERROR}"
    PF_BAD=$((PF_BAD + 1))
  fi
}
pf_empty_prefix() {
  # pf_empty_prefix WHAT KEY_PREFIX
  local out="${CC_TMP}/pf-list.json" n
  if cc_aws_read "${out}" s3api list-objects-v2 --bucket "${DATA_BUCKET}" --prefix "$2/" --max-keys 1; then
    n="$(jq -r '.KeyCount // 0' "${out}")"
    if [ "${n}" -eq 0 ]; then
      printf 'PREFLIGHT: %s: empty (ok)\n' "$1"
    else
      printf 'PREFLIGHT: %s: NOT empty (a Phase 15 run needs its own new root)\n' "$1"
      PF_BAD=$((PF_BAD + 1))
    fi
  else
    printf 'PREFLIGHT: %s: unreadable (%s)\n' "$1" "${CC_LAST_AWS_ERROR}"
    PF_BAD=$((PF_BAD + 1))
  fi
}

preflight() {
  cc_step "preflight"
  local build out f found=""
  build="$(git log -1 --format=%H -- build.sbt project modules prompts docker config .scalafmt.conf .jvmopts .sbtopts)"
  [ -n "${build}" ] || cc_die 3 "cannot determine the build commit"
  if [ "${STAGE}" = "hadoop" ]; then
    pf_object "DBLP input" "$(key_of "${DBLP}")"
    if [ -n "${DTD}" ]; then pf_object "DBLP DTD" "$(key_of "${DTD}")"; fi
    pf_empty_prefix "Hadoop output root (this attempt)" "$(key_of "${HADOOP_OUTPUT}")"
    pf_object "Hadoop jar of the build commit" "artifacts/${build}/jars/hadoop.jar"
  else
    pf_object "J09 export _job.json" "$(key_of "${HADOOP_EXPORT}")/_job.json"
    pf_object "ER run _SUCCESS" "$(key_of "${IDENTITY%/decisions/identity_assignment}")/_SUCCESS"
    for f in "${RECORD_DIR}"/*-er.json; do
      [ -f "${f}" ] || continue
      if jq -e --arg l "${IDENTITY}" '.kind == "scholarchain-pipeline-run-record" and .stage == "er"
          and .status == "succeeded" and .outputs.identity_assignments == $l' "${f}" >/dev/null 2>&1; then
        found="${f}"
      fi
    done
    if [ -n "${found}" ]; then
      printf 'PREFLIGHT: ER run record says succeeded: %s\n' "${found}"
    else
      printf 'PREFLIGHT: NO succeeded ER run record under %s/ names these identity assignments\n' "${RECORD_DIR}"
      PF_BAD=$((PF_BAD + 1))
    fi
    pf_empty_prefix "Spark out-root (this attempt)" "$(key_of "${OUT_ROOT}")"
    pf_object "Spark jar of the build commit" "artifacts/${build}/jars/spark-analytics.jar"
    pf_object "Spark job config of the build commit" "artifacts/${build}/spark-conf/phase06.json"
  fi
  out="${CC_TMP}/pf-emr.json"
  if cc_aws_read "${out}" emr list-clusters --active; then
    printf 'PREFLIGHT: active EMR clusters now: %s\n' "$(jq '[.Clusters[]?] | length' "${out}")"
  else
    printf 'PREFLIGHT: active EMR clusters: unreadable (%s)\n' "${CC_LAST_AWS_ERROR}"
  fi
  printf 'PREFLIGHT: settings emr-max-units=%s spark-shuffle-partitions=%s (the state machine checks the Terraform bounds)\n' \
    "${EMR_MAX:-absent}" "${SHUFFLE:-absent}"
  if [ "${PF_BAD}" -gt 0 ]; then
    cc_die 3 "${PF_BAD} preflight check(s) failed (listed above); nothing started"
  fi
  printf 'PREFLIGHT: ok (nothing started, nothing written)\n'
}

# ---- the run ----------------------------------------------------------------------------------------

cc_signal_hook() {
  cc_wait_child "${CHILD_PID}" "${CHILD_GRACE_SECONDS}"
}

run_pipeline() {
  cc_step "pipeline"
  local rc=0
  STARTED_AT="$(cc_now)"
  # stdout and stderr are the runner's (not wrapped): RUN-RECORD lines intact.
  bash "${PIPELINE}" "${FWD[@]}" </dev/null &
  CHILD_PID="$!"
  wait "${CHILD_PID}" || rc=$?
  CHILD_PID=""
  PIPELINE_RC="${rc}"
  cc_info "the pipeline ended with exit ${rc}"
}

# find_record: the newest final record of this stage written since the start
# whose input names this attempt's output root (two runs may overlap: D11a
# and D11b).
find_record() {
  cc_step "nested-record"
  local f want jqpath
  if [ "${STAGE}" = "hadoop" ]; then
    want="${HADOOP_OUTPUT}"
    jqpath='.inputs.execution_input.hadoop.output'
  else
    want="${OUT_ROOT}"
    jqpath='.inputs.execution_input.spark.outRoot'
  fi
  for f in "${RECORD_DIR}"/*-"${STAGE}".json; do
    [ -f "${f}" ] || continue
    if jq -e --arg w "${want}" "(${jqpath}) == \$w" "${f}" >/dev/null 2>&1; then
      NESTED="${f}"
    fi
  done
  if [ -z "${NESTED}" ]; then
    cc_error "nested-record-not-found"
    cc_warn "no final ${STAGE} record under ${RECORD_DIR}/ names this attempt's output root"
    return 0
  fi
  cc_info "nested record ${NESTED} (status $(jq -r '.status' "${NESTED}"))"
}

# ---- EMR --------------------------------------------------------------------------------------------

CLUSTERS_JSON="[]"
TERM_REQ_EPOCH=""

sfn_history() {
  cc_step "sfn-history"
  local name arn out="${CC_TMP}/hist.json"
  cc_file sfn-events.csv "epoch_s,event_type,state_name"
  name="$(jq -r '.run_ids.execution_name // empty' "${NESTED}")"
  [ -n "${name}" ] || {
    cc_error "no-execution-name"
    return 0
  }
  case "${SM_ARN}" in
    *":stateMachine:"*) arn="${SM_ARN/:stateMachine:/:execution:}:${name}" ;;
    *)
      cc_error "state-machine-arn-unexpected"
      return 0
      ;;
  esac
  if ! cc_aws_read "${out}" stepfunctions get-execution-history --execution-arn "${arn}" --max-items 5000; then
    cc_error "execution-history-${CC_LAST_AWS_ERROR}"
    return 0
  fi
  arn=""
  jq -r "${CC_JQ_DEFS}"'
    .events[]? | [(.timestamp | ts | cell), .type,
                  (.stateEnteredEventDetails.name // .stateExitedEventDetails.name // "")] | join(",")' \
    "${out}" 2>/dev/null >"${CC_TMP}/hist.rows" || cc_error "execution-history-unreadable"
  local line
  while IFS= read -r line; do
    [ -n "${line}" ] && cc_row sfn-events.csv "${line}"
  done <"${CC_TMP}/hist.rows"
  TERM_REQ_EPOCH="$(jq -r "${CC_JQ_DEFS}"'
    [.events[]? | select(.type == "TaskScheduled"
       and ((.taskScheduledEventDetails.resource // "") | test("terminateCluster")))
     | .timestamp | ts] | max // empty' "${out}" 2>/dev/null || true)"
  rm -f -- "${out}"
}

emr_cluster() {
  # emr_cluster ID: waits (bounded) for a terminal state, then records fields.
  local id="$1" out="${CC_TMP}/dc.json" deadline st="" pol="${CC_TMP}/msp.json" limits="null"
  deadline=$(($(cc_now) + CLUSTER_END_LIMIT))
  while :; do
    if cc_aws_read "${out}" emr describe-cluster --cluster-id "${id}"; then
      st="$(jq -r '.Cluster.Status.State // ""' "${out}")"
      case "${st}" in
        TERMINATED | TERMINATED_WITH_ERRORS) break ;;
      esac
    else
      cc_error "describe-cluster-${CC_LAST_AWS_ERROR}"
    fi
    [ "$(cc_now)" -lt "${deadline}" ] || break
    printf 'POLL: EMR cluster %s state=%s (waiting for its end, at most %ss)\n' "${id}" "${st:-unreadable}" "${CLUSTER_END_LIMIT}"
    sleep "${POLL_SECONDS}"
  done
  if cc_aws_read "${pol}" emr get-managed-scaling-policy --cluster-id "${id}"; then
    limits="$(jq -c '.ManagedScalingPolicy.ComputeLimits // null
      | if . == null then null else {UnitType, MinimumCapacityUnits, MaximumCapacityUnits,
                                      MaximumOnDemandCapacityUnits, MaximumCoreCapacityUnits} end' "${pol}" 2>/dev/null || printf 'null')"
  else
    cc_error "managed-scaling-policy-${CC_LAST_AWS_ERROR}"
  fi
  if [ -s "${out}" ] && jq -e '.Cluster' "${out}" >/dev/null 2>&1; then
    CLUSTERS_JSON="$(jq -c --slurpfile c "${out}" --arg tr "${TERM_REQ_EPOCH}" --argjson lim "${limits}" "${CC_JQ_DEFS}"'
      . + [$c[0].Cluster | {clusterId: .Id,
             createdEpoch: (.Status.Timeline.CreationDateTime | ts),
             readyEpoch: (.Status.Timeline.ReadyDateTime | ts),
             terminationRequestedEpoch: (if $tr == "" then null else ($tr | tonumber) end),
             terminationRequestedSource: (if $tr == "" then "missing" else "Step Functions history: TaskScheduled of elasticmapreduce terminateCluster" end),
             endedEpoch: (.Status.Timeline.EndDateTime | ts),
             finalState: (.Status.State // null),
             stateChangeReasonCode: (.Status.StateChangeReason.Code // null),
             releaseLabel: (.ReleaseLabel // null),
             managedScalingComputeLimits: $lim}]' <<<"${CLUSTERS_JSON}")"
  else
    CLUSTERS_JSON="$(jq -c --arg id "${id}" '. + [{clusterId: $id, createdEpoch: null, readyEpoch: null,
      terminationRequestedEpoch: null, endedEpoch: null, finalState: null, stateChangeReasonCode: null,
      note: "describe-cluster unreadable: missing"}]' <<<"${CLUSTERS_JSON}")"
  fi
  local ig="${CC_TMP}/ig.json" li="${CC_TMP}/li.json" ls="${CC_TMP}/ls.json" line
  if cc_aws_read "${ig}" emr list-instance-groups --cluster-id "${id}"; then
    while IFS= read -r line; do
      [ -n "${line}" ] && cc_row emr-instance-groups.csv "${line}"
    done < <(jq -r --arg id "${id}" '.InstanceGroups[]? | [$id, (.InstanceGroupType // ""), (.InstanceType // ""),
        (.RequestedInstanceCount // "" | tostring), (.RunningInstanceCount // "" | tostring),
        (.Status.State // "")] | join(",")' "${ig}" 2>/dev/null || true)
  else
    cc_error "list-instance-groups-${CC_LAST_AWS_ERROR}"
    printf '{}\n' >"${ig}"
  fi
  if cc_aws_read "${li}" emr list-instances --cluster-id "${id}"; then
    while IFS= read -r line; do
      [ -n "${line}" ] && cc_row emr-instances.csv "${line}"
    done < <(jq -r --arg id "${id}" --slurpfile g "${ig}" "${CC_JQ_DEFS}"'
        ([$g[0].InstanceGroups[]? | {key: .Id, value: (.InstanceGroupType // "")}] | from_entries) as $types
        | .Instances[]? | [$id, ($types[.InstanceGroupId // ""] // ""), (.InstanceType // ""), (.Market // ""),
          (.Status.Timeline.CreationDateTime | ts | cell), (.Status.Timeline.ReadyDateTime | ts | cell),
          (.Status.Timeline.EndDateTime | ts | cell), (.Status.State // ""),
          (.Status.StateChangeReason.Code // "")] | join(",")' "${li}" 2>/dev/null || true)
  else
    cc_error "list-instances-${CC_LAST_AWS_ERROR}"
  fi
  if cc_aws_read "${ls}" emr list-steps --cluster-id "${id}"; then
    jq -r --arg id "${id}" "${CC_JQ_DEFS}"'.Steps[]? | [$id, (.Id // ""), (.Name // "" | gsub(","; ";")), (.Status.State // ""),
        (.Status.Timeline.StartDateTime | ts | cell), (.Status.Timeline.EndDateTime | ts | cell),
        (if .Status.FailureDetails == null then "no" else "yes" end)] | join(",")' "${ls}" 2>/dev/null \
      >"${CC_TMP}/steps-${id}.rows" || cc_error "list-steps-unreadable"
    while IFS= read -r line; do
      [ -n "${line}" ] && cc_row emr-steps.csv "$(printf '%s' "${line}" | cut -d, -f1,3-)"
    done <"${CC_TMP}/steps-${id}.rows"
  else
    cc_error "list-steps-${CC_LAST_AWS_ERROR}"
  fi
}

emr_all() {
  cc_step "emr"
  local id
  cc_file emr-instance-groups.csv "cluster_id,instance_group_type,instance_type,requested,running,state"
  cc_file emr-instances.csv "cluster_id,instance_group_type,instance_type,market,created_epoch,ready_epoch,ended_epoch,state,state_change_code"
  cc_file emr-steps.csv "cluster_id,step_name,state,start_epoch,end_epoch,failure_reported"
  for id in $(jq -r '.run_ids.emr_cluster_ids[]? // empty' "${NESTED}"); do
    case "${id}" in
      j-[A-Z0-9]*) emr_cluster "${id}" ;;
      *) cc_error "unexpected-cluster-id" ;;
    esac
  done
  cc_json_file emr-cluster.json "$(jq -n --argjson c "${CLUSTERS_JSON}" '{clusters: $c}')"
}

# ---- Hadoop -----------------------------------------------------------------------------------------

hadoop_jobs() {
  cc_step "hadoop-job-records"
  local run key out="${CC_TMP}/job.json" n=0 line
  cc_file hadoop-job-counters.csv "job_name,run_id,kind,name,value"
  for run in $(jq -r '.run_ids.hadoop_runs[]? // empty' "${NESTED}"); do
    case "${run}" in
      "${HADOOP_OUTPUT}"/*) ;;
      *) continue ;;
    esac
    key="$(key_of "${run}")/_job.json"
    if ! cc_aws_read "${CC_TMP}/get.json" s3api get-object --bucket "${DATA_BUCKET}" --key "${key}" "${out}"; then
      cc_error "job-json-${CC_LAST_AWS_ERROR}"
      continue
    fi
    n=$((n + 1))
    while IFS= read -r line; do
      [ -n "${line}" ] && cc_row hadoop-job-counters.csv "${line}"
    done < <(jq -r '(.jobName // "") as $j | (.runId // "") as $r
        | ((.counters // {}) | to_entries[] | [$j, $r, "counter", (.key | gsub(","; ";")), (.value | tostring)] | join(",")),
          ((.stats // {}) | to_entries[] | [$j, $r, "stat", (.key | gsub(","; ";")), (.value.value // "" | tostring)] | join(","))' \
      "${out}" 2>/dev/null || true)
    rm -f -- "${out}"
  done
  cc_set CC_COUNTS_JSON hadoop_job_records "${n}"
}

hadoop_step_logs() {
  cc_step "hadoop-step-logs"
  local prefix id step f key out="${CC_TMP}/log.gz" deadline size have
  cc_file hadoop-step-counters.csv "cluster_id,step_name,log,job_id,counter,value"
  prefix="${LOG_URI#s3://"${DATA_BUCKET}"/}"
  prefix="${prefix%/}"
  [ "${prefix}" != "${LOG_URI}" ] || {
    cc_error "emr-log-uri-unexpected"
    return 0
  }
  for f in "${CC_TMP}"/steps-*.rows; do
    [ -f "${f}" ] || continue
    while IFS=, read -r id step name _; do
      [ -n "${step}" ] || continue
      for log in syslog stderr; do
        key="${prefix}/${id}/steps/${step}/${log}.gz"
        deadline=$(($(cc_now) + STEP_LOG_LIMIT))
        have=""
        while :; do
          if cc_aws_raw "${CC_TMP}/head.json" s3api head-object --bucket "${DATA_BUCKET}" --key "${key}"; then
            have="yes"
            break
          fi
          [ "$(cc_now)" -lt "${deadline}" ] || break
          sleep "${POLL_SECONDS}"
        done
        if [ -z "${have}" ]; then
          cc_error "step-log-missing-${log}"
          continue
        fi
        size="$(jq -r '.ContentLength // 0' "${CC_TMP}/head.json")"
        if [ "${size}" -gt "${STEP_LOG_MAX_BYTES}" ]; then
          cc_error "step-log-too-large-${log}"
          continue
        fi
        if ! cc_aws_read "${CC_TMP}/get.json" s3api get-object --bucket "${DATA_BUCKET}" --key "${key}" "${out}"; then
          cc_error "step-log-get-${CC_LAST_AWS_ERROR}"
          continue
        fi
        gunzip -c "${out}" 2>/dev/null | awk -v c="${id}" -v s="${name}" -v l="${log}" '
          /number of splits:[0-9]+/ { n = $0; sub(/.*number of splits:/, "", n); sub(/[^0-9].*/, "", n); pending = n }
          /Submitting tokens for job: job_/ { j = $0; sub(/.*job: /, "", j); sub(/[^A-Za-z0-9_].*/, "", j)
            if (pending != "") { printf "%s,%s,%s,%s,map_input_splits,%s\n", c, s, l, j, pending; pending = "" } }' \
          >"${CC_TMP}/splits.rows" || true
        local line
        while IFS= read -r line; do
          [ -n "${line}" ] && cc_row hadoop-step-counters.csv "${line}"
        done <"${CC_TMP}/splits.rows"
        rm -f -- "${out}"
      done
    done <"${f}"
  done
}

# ---- Spark ------------------------------------------------------------------------------------------

spark_runs() {
  cc_step "spark-run-reports"
  local run rep="${CC_TMP}/rep.json" man="${CC_TMP}/man.json" n=0 line conf="{}"
  cc_file spark-stage-metrics.csv "run_id,stage_group,metric,value"
  cc_file spark-stage-timings.csv "run_id,stage,millis"
  cc_file spark-outputs.csv "run_id,table,window_label,records,invalid_records"
  for run in $(jq -r '.run_ids.graph_runs_under_out_root[]? // empty' "${NESTED}"); do
    case "${run}" in
      "${OUT_ROOT}"/*) ;;
      *) continue ;;
    esac
    if cc_aws_read "${CC_TMP}/get.json" s3api get-object --bucket "${DATA_BUCKET}" --key "$(key_of "${run}")/_run_report.json" "${rep}"; then
      n=$((n + 1))
      while IFS= read -r line; do
        [ -n "${line}" ] && cc_row spark-stage-metrics.csv "${line}"
      done < <(jq -r '(.runId // "") as $r | (.sparkStageMetrics // {}) | to_entries[] | .key as $g
          | .value | to_entries[] | [$r, ($g | gsub(","; ";")), .key, (.value | tostring)] | join(",")' "${rep}" 2>/dev/null || true)
      while IFS= read -r line; do
        [ -n "${line}" ] && cc_row spark-stage-timings.csv "${line}"
      done < <(jq -r '(.runId // "") as $r | (.stageTimingsMs // {}) | to_entries[]
          | [$r, (.key | gsub(","; ";")), (.value | tostring)] | join(",")' "${rep}" 2>/dev/null || true)
      conf="$(jq -c '{runId, sparkConf: (.sparkConf // null)}' "${rep}" 2>/dev/null || printf '{}')"
    else
      cc_error "run-report-${CC_LAST_AWS_ERROR}"
    fi
    if cc_aws_read "${CC_TMP}/get.json" s3api get-object --bucket "${DATA_BUCKET}" --key "$(key_of "${run}")/_run_manifest.json" "${man}"; then
      while IFS= read -r line; do
        [ -n "${line}" ] && cc_row spark-outputs.csv "${line}"
      done < <(jq -r '(.runId // "") as $r | .outputs[]? | [$r, (.table // "" | gsub(","; ";")),
          (.windowLabel // "" | tostring), (.records // "" | tostring), (.invalidRecords // "" | tostring)] | join(",")' \
        "${man}" 2>/dev/null || true)
    else
      cc_error "run-manifest-${CC_LAST_AWS_ERROR}"
    fi
    rm -f -- "${rep}" "${man}"
  done
  cc_json_file spark-conf.json "${conf}"
  cc_set CC_COUNTS_JSON spark_run_reports "${n}"
}

# ---- checklist --------------------------------------------------------------------------------------

rows_of() {
  if [ -f "${CC_OUT}/$1" ]; then
    printf '%s' "$(($(grep -c . "${CC_OUT}/$1" || true) - 1))"
  else
    printf '0'
  fi
}

checklist() {
  cc_step "checklist"
  local inst_s cost
  inst_s="$(awk -F, 'NR > 1 && $5 != "" { e = ($7 != "" ? $7 : now); if (e > $5) s += e - $5 } END { printf "%d", s }' \
    now="$(cc_now)" "${CC_OUT}/emr-instances.csv" 2>/dev/null || printf '0')"
  cost="$(awk -v s="${inst_s}" -v r="${INSTANCE_HOUR_USD}" 'BEGIN { printf "%.2f", s / 3600 * r }')"
  local doc
  doc="$(jq -n --arg stage "${STAGE}" --arg nested "${NESTED}" --arg status "$([ -n "${NESTED}" ] && jq -r '.status' "${NESTED}" || printf missing)" \
    --argjson hj "$(rows_of hadoop-job-counters.csv)" --argjson hs "$(rows_of hadoop-step-counters.csv)" \
    --argjson sm "$(rows_of spark-stage-metrics.csv)" --argjson st "$(rows_of spark-stage-timings.csv)" \
    --argjson so "$(rows_of spark-outputs.csv)" --argjson ei "$(rows_of emr-instances.csv)" \
    --argjson es "$(rows_of emr-steps.csv)" --argjson sf "$(rows_of sfn-events.csv)" \
    --argjson inst "${inst_s}" --arg cost "${cost}" --arg rate "${INSTANCE_HOUR_USD}" '
    def item($ok; $src; $note): {status: (if $ok then "found" else "missing" end), source: $src, note: $note};
    {stage: $stage, nested_run_record: (if $nested == "" then null else $nested end), nested_status: $status,
     items: {
      input_records: (if $stage == "hadoop" then item($hj > 0; "hadoop-job-counters.csv"; "ScholarChain counters of each job (j01-dblp-extract reads the DBLP input)")
                      else item($so > 0; "spark-outputs.csv and the nested record inputs"; "Spark reads the J09 export and ER assignments; input records per table are in the run manifest inputs, records out per table here") end),
      output_records: (if $stage == "hadoop" then item($hj > 0; "hadoop-job-counters.csv"; "counters and stats of j09-graph-export and the other jobs")
                       else item($so > 0; "spark-outputs.csv"; "records and invalid records per output table") end),
      task_counts: (if $stage == "hadoop" then {status: (if $hs > 0 then "partial" else "missing" end), source: "hadoop-step-counters.csv",
                      note: "map tasks planned = input splits from the step logs; reduce and launched-attempt counts are not logged (JobRunner waitForCompletion(false)): missing"}
                    else item($sm > 0; "spark-stage-metrics.csv"; "tasks, stages and maxTasksPerStage per stage group (StageMetricsListener)") end),
      duration: item($es > 0; "emr-steps.csv, sfn-events.csv, the nested record start and end"; "step start and end; execution events"),
      shuffle: (if $stage == "hadoop" then {status: "missing", source: null, note: "MapReduce shuffle counters are not logged by the Hadoop driver (waitForCompletion(false)); not in _job.json"}
                else item($sm > 0; "spark-stage-metrics.csv"; "shuffleReadBytes, shuffleWriteBytes, shuffleReadRecords, shuffleWriteRecords per stage group") end),
      failures: item($sf > 0 or $es > 0; "nested record status, emr-steps.csv (state, failure_reported), sfn-events.csv"; "never inferred"),
      estimated_cost: {status: (if $ei > 0 then "found" else "missing" end), source: "emr-instances.csv",
                       instance_seconds: $inst, usd_estimate: ($cost | tonumber),
                       note: "estimate (unverified): instance-seconds from the instance lifecycle x $\($rate) per instance-hour (COSTS.md:39, m5.xlarge EC2 + EMR); EBS not included"},
      requested_running_capacity: {status: "exported by the runner", source: "metrics/metric-data.json (CoreNodesRequested, CoreNodesRunning, TotalNodes*, TaskNodes*)", note: "checked at analysis; empty series stay missing"},
      released_capacity: {status: (if $ei > 0 then "found (lifecycle)" else "missing" end), source: "metrics (drop of CoreNodesRunning before the cluster end) or emr-instances.csv ended_epoch and emr-cluster.json endedEpoch",
                          note: "a release at cluster termination is a lifecycle record, not a measured zero and not a scale-in event"}
     }}')"
  cc_json_file ab-evidence.json "${doc}"
  cc_set_str CC_COUNTS_JSON instance_seconds "${inst_s}"
  cc_set_str CC_COUNTS_JSON estimated_cost_usd "${cost} (estimate)"
}

# ---- main -------------------------------------------------------------------------------------------

PIPELINE_RC=0
main() {
  cc_init "${TOOL_NAME}" "${TOOL_PATH}" "$@"
  parse_args "$@"
  cc_prepare_out emr-cluster.json emr-instance-groups.csv emr-instances.csv emr-steps.csv sfn-events.csv \
    hadoop-job-counters.csv hadoop-step-counters.csv spark-stage-metrics.csv spark-stage-timings.csv \
    spark-outputs.csv spark-conf.json ab-evidence.json clock.csv
  cc_tf_outputs
  cc_tf_required DATA_BUCKET '.data_bucket_name.value' "data_bucket_name"
  cc_tf_required SM_ARN '.state_machine_arn.value' "state_machine_arn"
  cc_tf_required LOG_URI '.emr_log_uri.value' "emr_log_uri"
  CC_LIMITS_JSON="$(jq -n --arg st "${STAGE}" --arg mx "${EMR_MAX}" --arg sp "${SHUFFLE}" \
    --argjson ce "${CLUSTER_END_LIMIT}" --argjson sl "${STEP_LOG_LIMIT}" \
    '{stage: $st, emr_max_units_arg: (if $mx == "" then null else $mx end),
      spark_shuffle_partitions_arg: (if $sp == "" then null else $sp end),
      cluster_end_wait_s: $ce, step_log_wait_s: $sl,
      remote_bounds: "state machine TimeoutSeconds (pipeline_timeout_seconds 14400), EMR managed-scaling maximum, EMR idle end 900 s (Terraform)"}')"
  if [ "${CC_PREFLIGHT}" = "true" ]; then
    preflight
    return 0
  fi
  cc_file clock.csv "phase,local_send_epoch_s,local_recv_epoch_s,http_status,curl_exit,time_total_s,http_date,http_date_epoch_s"
  cc_clock start
  run_pipeline
  find_record
  if [ -n "${NESTED}" ]; then
    sfn_history
    emr_all
    if [ "${STAGE}" = "hadoop" ]; then
      hadoop_jobs
      hadoop_step_logs
    else
      spark_runs
    fi
  fi
  checklist
  cc_clock end
  cc_set CC_COUNTS_JSON pipeline_exit "${PIPELINE_RC}"
  cc_set CC_COUNTS_JSON pipeline_started_epoch_s "${STARTED_AT}"
  cc_set_str CC_OBS_JSON result "pipeline exit ${PIPELINE_RC}; capacity over time is read from the exported metrics (requested, running, released); see ab-evidence.json"
  if [ "${PIPELINE_RC}" -eq 0 ]; then
    CC_STATUS="completed"
  fi
  # the pipeline's exit code is the collector's (exit, not return: a non-zero
  # return would trip the ERR trap and become 5)
  exit "${PIPELINE_RC}"
}

main "$@"
