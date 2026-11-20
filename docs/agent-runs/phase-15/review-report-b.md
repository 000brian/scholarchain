# review-report-b.md: Phase 15, session 2, review R-2 (steps 5a, 6a, 6b)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

Work order: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), decisions 3, 9, 10, 11, 12 and the Standing rules. Base: HEAD 2cbfbc72c704c40085e2876479d510b90062a12a.

VERDICT: REQUEST_CHANGES

I found 1 major finding (J-01) and 5 minor ones. None is critical and none is marked interface: yes. Everything else on the decision 12 checklist was checked against the files and is listed below with file:line.

## Findings

| ID | Severity | Interface | Owner | file:line | Evidence | Failure scenario |
|---|---|---|---|---|---|---|
| J-01 | major | no | experiment-engineer | experiments/collectors/f-poison-replay.sh:398-418 (`wait_outcome`), :423, :606-611 | `wait_outcome` sets `deadline=$((sent + P2_LIMIT))` with `sent = REPLAY_SENT_AT`. The replay message is sent right after the poison (:608-609), and then `part_poison_wait` runs first (:610), for up to `P1_LIMIT` = 960 s from the poison send. `P2_LIMIT` is 720 s. If part 1 takes longer than 720 s, the loop `while [ now -le deadline ]` runs zero times. No worker-log read happens (`LOGS_READABLE` stays "unknown"), and `part_replay` records "processed not seen … the duplicate was NOT sent" (:441-443). | Part 1 can take more than 720 s in two cases: (a) a slow scale-out from 0 plus DLQ lag (the 480 s allowance plus about 4 min); (b) the release-at-once rule not taking effect, so the poison needs 5 × 300 s and part 1 hits its 960 s limit. In both cases F's idempotency replay is never tested, even though "processed" is very likely already in the worker log. The record then calls the replay "not seen" when it was never looked for. Fix: measure the part-2 deadline from when part 2 starts (for example `max(REPLAY_SENT_AT, now)`), or read the log at least once. Add a fixture or test showing the case. |
| J-02 | minor | no | experiment-engineer | experiments/collectors/lib/collector-common.sh:337-349 (`cc_aws_raw` always writes `${CC_TMP}/aws.err`), :555-575 (sampler subshell) | The background sampler and the main flow (C: `send_all`, `wait_drain`; F: parts 1-3) both call `cc_aws_raw` at the same time, and both write and truncate the same `aws.err` file. | A failed call can be recorded with the other process's AWS error code, or with "none". Only the error codes in `tool-record.json` are affected; no data is lost. Fix: give each call its own error file (for example `aws-$$-$RANDOM.err`, or a sampler-specific name). |
| J-03 | minor | no | experiment-engineer | experiments/derived/emr_capacity.py:35-46, :112-115, :59-87; experiments/results-summary-template.md:75 | The manifests now use `CoreNodes*`/`TotalNodes*`, because 6b found that `TotalUnits*`/`CoreUnits*` are not published for instance-group clusters. The derive script still lists `TotalUnits*`/`CoreUnits*` in `UNIT_METRICS`, so every A/B attempt gets a "series not exported" entry in missing.csv for metrics that were dropped on purpose. `TotalNodesRequested`/`TotalNodesRunning` are never plotted or classified. The template still names `TotalUnitsRequested` and `TotalUnitsRunning` for B. Separately, `classify` treats any rise above the first datapoint as a scale-out and does not compare against `readyEpoch`. | missing.csv is noisy and misleading, the plot shows no TotalNodes* series, and the template's B section asks for series that never exist. If the first CoreNodesRunning datapoint is taken while the initial core nodes are still starting, the start-up ramp is labelled a "scale-out" (for example on A, which is flat by construction). Fix: align the metric lists with the manifests, and ignore points before `readyEpoch` when classifying. |
| J-04 | minor | no | experiment-engineer (or the orchestrator records a deviation) | experiments/derived/retrieval_modes.py:71-80; experiments/derived/load_levels.py:189-203 | Decision 11.2 says "every plot has UTC on the x axis". recall-/mrr-*.svg (E) put K on the x axis, and latency-/classes-*.svg (D) put load levels on it. Only tasks-*.svg (D) uses UTC. retrieval_modes.py states this in its docstring; load_levels.py does not. | This is an unplanned deviation that is not recorded anywhere. No data is wrong. Ruling below (Q4). |
| J-05 | minor | no | experiment-engineer | load-tests/query-load.sh:386-389 vs :203-205 | In `--preflight` mode, `OUT_DIR` is its own `mktemp -d` folder, but `on_exit` removes only `TMP_DIR`. | Each D9a `--check` of a D manifest leaves a `scholarchain-load-preflight.*` folder (load-clock.csv, and id-check.csv in read mode) in `$TMPDIR`. It is outside the repository, so the rule holds, but it is never cleaned up. Fix: remove the folder on exit, or create it under `TMP_DIR`. |
| J-06 | minor | no | infra-engineer | scripts/archive-evidence.sh:571-574 with scripts/lib/identity.sh:287-309 | Not a defect, an expectation to set. Since G-29, the API host is a registered identifier. The Phase 14 smoke records under evidence/runs/ still contain `api_host` and are copied into the archive's repo/ folder. | At D25 the archive will record `byte_copies_hold_identifiers: true` and print a warning. This is expected and correct, but nothing says so in advance. The orchestrator should be told before D25 so it does not treat this as a new problem. |

## Rulings on the six specific questions

1. **5a's two extras: both accepted.**
   - The 300 s wait before the metrics export (scripts/run-experiment.sh:806-816, :1069) is already assumed by decision 10 ("the runner waits 5 minutes after the action"). Without it, the +300 s end margin of the window would lie in the future.
   - Refusing an untracked or non-executable collector before the run folder exists (:1062-1066) matches decision 10's exit-4 row ("Final folder: no").
   - Side effects are documented: every run takes 300 s longer, and a Ctrl-C during the wait means no export (`--export-metrics` recovers it).
2. **`$error` in a Pass state (infra/terraform/modules/workflow/sfn.tf:176-200, :250-259): low risk; no change needed before D4a.**
   - It fails closed either way: if `$error` were unsupported, calling an unknown function also raises a JSONata evaluation error.
   - JSONata evaluates only the conditional branch it takes, so valid and absent inputs never reach `$error`.
   - No cluster exists at ReadInput, and a Pass state takes no Catch, so no cleanup is needed.
   - The remaining risk is that Step Functions rejects `$error` when it validates the definition, which would make the D4 update fail. The D4a `validate-state-machine-definition` line runs before signing and catches that.
   - I could not verify, because I have no docs tool: (a) whether Step Functions' JSONata supports `$error`; (b) whether EMR accepts MaximumCapacityUnits = MinimumCapacityUnits = 2, which is A's setting. D4a's test-state does not exercise (b); D7a is its first live check.
3. **Neptune DBClusterIdentifier as a literal in the D manifests (p15-d-query-load.json:59-63, p15-d-read-load.json:50-54): allowed.**
   - It is a resource name. Terraform gives it a random suffix through `cluster_identifier_prefix` (modules/neptune/main.tf:57); it is not derived from the account.
   - The forbidden list covers endpoint hosts. The host cannot be rebuilt from the identifier alone, because the cluster resource suffix is also needed.
   - The runner registers the full `neptune_endpoint` as an identifier (identity.sh:307), so a recorded host would still be caught.
   - Dropping AWS/ApiGateway is consistent with this: the ApiId plus the Region does rebuild the public execute-api host.
4. **Plots with K (and load levels) on the x axis: acceptable in substance.** These are summary plots over categories, not time series, so there is no scaling event to align. Decision 11.2 says "every plot", so this should be recorded as a deviation (J-04). The time-series plots (queue_worker, failure_timeline, emr_capacity, D tasks) use UTC and mark events (common.py:454-584).
5. **B's remote upper bound of about $5.10 vs the runner's $5 cap: acceptable.**
   - The arithmetic checks out: (14,400 + 900) s = 4.25 h × 5 instances × $0.24 = $5.10. EBS is not included (the COSTS.md row says "plus EBS"; B's notes do not).
   - The runner's cap applies to the declared `costBoundUsd` (check_v1, run-experiment.sh:446), which is 2. That is a planning bound of about twice the expected cost, not a worst case, and the notes state both numbers.
   - D11b is attended and has a stop rule. The D21 fallback rule (upper bound under $9) still holds.
6. **CoreNodes*/TotalNodes* in A/B vs decision 10's TotalUnits*: accepted.** Decision 10 requires checking each metric with list-metrics and dropping any query that is not published. 6b records the drop and its reason. The phase notes for experiment B (prompt.md:541) also name CoreNodesRequested and CoreNodesRunning. The derive script and template still use the old names (J-03).

## Checklist (decision 12), with file:line or "checked, none"

- **9.1 IAM least privilege and the queue policy.**
  - The worker's role policy adds only `ReceiveMessage`, `DeleteMessage` and `ChangeMessageVisibility` on the embedding queue ARN (modules/runtime/iam.tf:220-234); there is no SendMessage.
  - The queue policy adds the statement `WorkerConsumesEmbeddingTasks` with the same three actions, for the worker role only (modules/workflow/main.tf:86-91; environments/course/main.tf:215-216). The TLS deny is unchanged.
  - The boundary statement `ProjectQueueMessages` covers these actions on `NAME_PREFIX-*` (identity/boundary.tf:90-97).
  - The queue URL reaches the worker task only, through `role_environment` (runtime/main.tf:61-71, :178), and comes from the module output, never a literal.
  - Checked, no finding.
- **9.2 Scaling.**
  - The new alarm `worker_out_embedding` has the same policy, threshold, period and notBreaching setting as the job-queue alarm (autoscaling.tf:139-157). The scale-in expression is the plain four-metric sum, with no FILL and `breaching` kept (:159-205).
  - `worker_max_count`, the scale-in minutes and the cooldown (120 s, :88) are unchanged.
  - I checked 5a's five alarm cases and its timeline (report lines 66-86) and found no issue.
- **9.3 State machine, absent fields, before and after.**
  - Before: ComputeLimits was the literal object of `local.cs.managed_scaling` (UnitType, Min, Max, OnDemand, Core). After: `compute_limits_default` (sfn.tf:182) has the same five keys from the same Terraform values. The value sent to EMR is the same; only the definition text changes.
  - Spark arguments: the shuffle term goes from the literal `'spark.sql.shuffle.partitions=32'` to `$sparkShuffleConf`, which equals `'spark.sql.shuffle.partitions=' & $string(32)` when the field is absent (:192-200). This gives the same string, and the other entries are unchanged (:159-167).
  - With A's values: maxima 2 and 2, partitions 32. With B's: 4 and 4, partitions 128. With an invalid value: `$error` in ReadInput, before any cluster.
  - The bounds are interpolated from Terraform (min..max of the units, 8..512 for partitions). The new default variable is validated (workflow/variables.tf:534-545).
  - The script side adds the input fields only when an option is given (run-bounded-pipeline.sh:845-855), with numeric checks (:820-833), records them in `execution_settings`, and allows the options only for the hadoop and spark stages (:529, :548-549).
- **9.5 G-29.**
  - smoke-test.sh records `api_url: "{api-url}"` (:1761-1763), masks every printed line (:1727-1738), registers the output identifiers (:1782-1785), and hides curl's error text (:1808-1832).
  - archive-evidence.sh registers them too (:481-486).
  - run-experiment.sh registers them (:557) and masks them in action.log through `mask_stream` (identity.sh:422-439).
  - The final folder check exits 8 (run-experiment.sh:1005-1020).
  - DLQ URLs are registered before queue URLs, so the longer value is masked first (identity.sh:302-305).
- **9.6 runner changes.**
  - `{attempt-id}`: resolved only in `action.args` (:566-571), recorded as `resolved_args` (:621); any other placeholder is refused with exit 4 (:484-498).
  - `--check`: runs every refusal, then reads only, then the preflight through the same `invoke_action` with RUN_DIR in TMPDIR, outside the repository (:911-951). No run folder, no export.
  - Metrics for failed and timed-out actions (:687-717, :1068-1071); a signal means no export.
  - Exit 8 is separate (:1010, :1019).
  - `--export-metrics` writes a new folder and never touches the original (:957-976, :1054-1061).
  - The exit-code table in experiments/README.md matches the code.
- **9.8 COSTS.md:** two per-use lines, both marked "estimate (unverified)". Checked, no finding.
- **9.9 Validation:** shellcheck, fmt and validate are reported by 5a. I did not re-run them (no Bash). The expected D3 plan list looks consistent with the diff.
- **11, 6a.**
  - query-load.sh: hard caps per level (:28-31, :729-732); closed-loop clients (:723-752); the key passed only through `curl --config -` (:531-534); the URL never printed (:495-506); classes as specified (:561-629); explain reads after the level window and before the pause (:794-797, :846-893); percentiles over all requests and over the success population, with "few samples" below 20 and "n/a" below 1 (:802-842); bounded deadline (:987).
  - Derive scripts: standard library only, `--fixtures` writes only to mktemp outside the repository (common.py:78-84), gaps are never filled with 0 (:454-584), the sha256 of each input is printed, and bytecode is suppressed.
  - Template present.
- **11, 6b.**
  - Collectors: strict mode, every AWS call with region, json output, connect and read timeouts and GNU timeout (lib :334-354). Every loop is bounded (sampler :555-575, settle, waits).
  - Exit classes 0, 2, 3, 5, 7.
  - Manifests: all seven are valid against manifest-schema-v1.json and the runner's v1 checks by reading (format, version, id = file name, example false, experiment letter, region, snapshot, costBoundUsd ≤ 5, integer maxDurationSeconds, resourceLimits with terraformSource, command path, metric item shape, placeholders only `{attempt-id}` and `s3://{data-bucket}/`).
- **First-run contract between the runner and each collector.**
  - Arguments are passed unchanged plus `--preflight` last; every collector strips it at any position (f :138, c :129, d :71, ab :107-110).
  - The three environment variables are set (runner :676-678); stdin is /dev/null.
  - Nested `RUN-RECORD:` lines: ab-pipeline does not wrap the pipeline's output (:249), and the runner greps `^RUN-RECORD: ` (:744); d-load's child prints none.
  - Exit codes: collector 0 → runner 0 or 6; any other code except 124/137 → 5; the runner's own timeout → 7 with metrics.
  - File names do not collide with the runner's files, and `tool-record.json` ≠ `load-tool-record.json`.
  - I checked 6b's compatibility matrix line by line. The references point to the right code; some ranges are off by a few lines. No finding.
- **Each `--preflight` makes only read calls.**
  - F (:547-577): logs filter, DynamoDB get-item, healthz, list/get-vectors, queue attributes, describe-services, describe-alarms.
  - C (:475-488, :501-505): the same family plus describe-scaling-activities.
  - D: describe-services and query-load's GETs only (:969-979); never a POST.
  - A/B (:194-236): head-object, list-objects-v2, emr list-clusters.
  - Checked, no finding.
- **AWS CLI JSON paths and empty or missing fields.** I checked each path and what happens when the field is missing:
  - SQS `Attributes.*` → "x" or an empty cell plus an error code (lib :404-448).
  - ECS `services[].desiredCount` → empty cell (:377-402).
  - `MetricAlarms[].StateValue` → empty plus an error.
  - `ScalingActivities[]` → null times dropped.
  - `vectors[].key` and metadata presence (c :272-281, lib :680-694).
  - `Cluster.Status.Timeline.*` → `ts` gives null, never 0 (lib :77-89).
  - `events[].timestamp`.
  - `_run_report.json` keys exist in Spark (Pipeline.scala:1021-1031); `_run_manifest.json` `outputs[]` (Output.scala:258-268).
  - The ER record's `.outputs.identity_assignments` and `_SUCCESS` exist (evidence/runs/…-er.json:51, :209).
  - The completion-record key matches storage (TableSpecs.scala:264, Hashing.canonicalEncoding).
  - The worker log fields `taskId`/`outcome` and the values `processed`/`replayed` match ReembedHandler.scala:290-326; the stream prefix `worker/` matches `awslogs-stream-prefix = each.key` (runtime/main.tf:193).
- **bash 3.2 (G-12).** None found. I searched with Grep for associative arrays, `${var,,}`, `mapfile`/`readarray`, `|&`, `wait -n`, `[[ -v` and quoted replacements: no hits outside comments. The new replacement `${SC_MASK_OUT//"${v}"/<KIND>}` (identity.sh:318) quotes only the pattern, the form G-12 confirmed safe. Empty-array expansions are guarded or the arrays are never empty.
- **Tools never print or record a URL, host, key or account.** None found. `CC_CLOCK_ENDPOINT` (lib :45) is a public AWS regional endpoint, used only to read the Date header and never recorded. My ruling: acceptable, it is not an environment identifier.
- **API key only on stdin.** lib :636-638 and query-load.sh :531-534. Checked, no finding.
- **Every tool and manifest bounded.** Checked, no finding. Details: F tool deadline about 4,035 s against maxDurationSeconds 4,800; C about 6,400 s against 7,500; D about 1,820 s against 2,400; A/B wait 14,700 s plus a 900 s cluster-end wait.
- **No production default changed for an experiment.** None found: shuffle default 32, unit maximum = Terraform maximum, thresholds unchanged.
- **Strict mode, timeouts and honest status lines.** All present; every new tool says "runtime: untested".

## Files read

- **In full:**
  - the work order (all 549 lines, in three pages), diff-R-2.txt, diff-R-2-files.txt;
  - experiments/collectors/{ab-pipeline.sh, c-embedding-backlog.sh, d-load.sh, f-poison-replay.sh, lib/collector-common.sh};
  - experiments/derived/{common.py, emr_capacity.py, failure_timeline.py, load_levels.py, queue_worker.py, retrieval_modes.py, fixtures/README.md};
  - experiments/manifests/{p15-a-hadoop, p15-a-spark, p15-b-spark-burst, p15-c-embedding-backlog, p15-d-query-load, p15-d-read-load, p15-f-poison-replay}.json;
  - experiments/results-summary-template.md, experiments/manifest-schema-v1.json;
  - load-tests/{query-load.sh, questions-d.txt, read-ids-d.txt};
  - infra/terraform/modules/workflow/sfn.tf, infra/terraform/modules/workflow/main.tf.
- **In part:**
  - scripts/run-experiment.sh :230-1076 (lines 1-229, the header and usage text, only through the diff);
  - scripts/lib/identity.sh :150-599; scripts/run-bounded-pipeline.sh :715-771, :1170-1410 plus Grep;
  - scripts/archive-evidence.sh :350-489 plus Grep; scripts/smoke-test.sh :560-596 plus Grep;
  - modules/runtime/autoscaling.tf :74-143;
  - evidence/runs/20261119T020635Z-f159535e498a-5bd1-er.json :1-54 plus Grep;
  - one plan summary (ba345f8d…22ba.summary.txt);
  - ReembedHandler.scala :300-339, Spark Output.scala :250-290, services explain.json;
  - Producer reports, only these sections: agent-report-infra-5a.md lines 66-208 (this goes beyond INTERFACE HANDOFF: decision 9.2 says R-2 checks the alarm cases, and I needed the before/after table); agent-report-experiment-6a.md lines 81-115 (INTERFACE HANDOFF); agent-report-experiment-6b.md lines 83-176 (Will-B-scale data, cost lines, enforced checks, contract, compatibility matrix, INTERFACE HANDOFF).
- **Through the diff only, not read in full:** experiments/README.md, infra/terraform/COSTS.md, environments/course/{main.tf, outputs.tf}, modules/runtime/{iam.tf, main.tf, variables.tf}, modules/workflow/{outputs.tf, variables.tf}.
- **Fixtures sampled:** p15-b-spark-burst b002 emr-cluster.json and p15-c-embedding-backlog c001 run-record.json, read in full. All fixtures and manifests were searched with Grep for `https?://|amazonaws|arn:aws`: the only hits are the AWS documentation links in three manifests' notes. The other fixtures were not read.
- **Never opened:** any *.tfstate*, .terraform/, *.tfplan, *.plan.txt or *.apply.txt.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), session 2 review R-2 (steps 5a, 6a, 6b), decision 12 checklist
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run (I have no Bash). Nothing in this slice has a test that can run before session 3; D0a and D9a are the first executable checks.
VALIDATION RESULTS: All seven manifests are valid against manifest-schema-v1.json and the runner's v1 checks, by reading. shellcheck, terraform fmt and validate, and jq empty are claimed by the producers and were not re-run by me.
ASSUMPTIONS: The working tree matches diff-R-2.txt. Step Functions' JSONata behaves as the cited guide says. The orchestrator compares step 3's and step 4's final worker log line, message format and config key with these collectors (6c and the interface check).
RISKS: (1) Unverified: `$error` support in Step Functions JSONata. D4a's validate line catches a rejection before signing. (2) Unverified: whether EMR accepts MaximumCapacityUnits = MinimumCapacityUnits = 2 (A). The first check is D7a, attended; a rejection fails before any cluster exists. (3) J-01 could cost F its replay evidence; a re-run is cheap but uses attended time. (4) The first live run of the runner and the collectors is still the real test (D9a, D10).
MANUAL ACTION REQUIRED: none from the reviewer. The human sets the executable bit on the four collectors and on query-load.sh before the session 2 commit (decision 16).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 8 triage. J-01 (major) goes to experiment-engineer for the repair round. J-02 to J-05 are minor and recorded (J-04 can instead be recorded as a deviation). J-06 is a note to pass on before D25. Step 10 re-checks f-poison-replay.sh after the repair.

VERDICT: REQUEST_CHANGES
