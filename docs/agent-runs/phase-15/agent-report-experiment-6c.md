# agent-report-experiment-6c (Phase 15, session 2, step 6c)

interface: no (no fixed interface changed; the E collector consumes 5b's loader exactly as handed over)

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified with shasum), step 6c: decisions 10, 11 (shared rules, 11.4 N rule, the 6c paragraph), 13 D11, D12. Not the wave closer (no sbt source touched).
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/experiments/collectors/e-retrieval-modes.sh (new executable: the human sets `chmod +x` before the commit)
- /Users/brian/Developer/repos/scholarchain/experiments/manifests/p15-e-retrieval-modes.json
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/experiments/manifests/p15-c-embedding-backlog.json (final N, wait limit, maxDurationSeconds, resourceLimits, cost line, notes)
- /Users/brian/Developer/repos/scholarchain/experiments/README.md (line 12: e-retrieval-modes.sh listed as present, no longer "in step 6c")
COMMANDS RUN: `shasum -a 256` (work order; manifests and changed files); `ls`, `grep -n`, `wc -l`, `cut`, `head` (read-only, literal paths); `git status --short experiments load-tests` (read-only); `shellcheck -x -P <collectors dir>` on e-retrieval-modes.sh (twice) and on all five collectors plus lib/collector-common.sh (final); `jq empty` on the eight p15 manifests (final). aws-api MCP: 4 read-only calls (list-metrics: AWS/ECS with ClusterName; AWS/Neptune OpenCypherRequestsPerSec; AWS/Neptune CPUUtilization; AWS/DynamoDB ConsumedReadCapacityUnits table names). No sbt, no script, no bash/python3/curl/aws CLI run; no cd, sed, sort, uniq, tr.
TEST RESULTS: not applicable (no Scala source; agents never run the collectors). e-retrieval-modes.sh: runtime: untested; its first executable test is D9a (`run-experiment.sh --check`, which runs `--preflight`), its first live run D11.
VALIDATION RESULTS: shellcheck rc=0 (all collectors and the library); jq empty rc=0 (eight manifests); runner v1 rules checked by reading (below). Details in VALIDATION EVIDENCE.
ASSUMPTIONS:
- A1. The rag handler rate (low 2.4, central 5.4, high 10 tasks/s per receive loop) and the 5a timeline are estimates; C's numbers inherit their uncertainty.
- A2. The laptop's AWS CLI takes about 0.7 to 1.5 s per send-message-batch call (Python start-up plus HTTPS; estimate, not measured), so C's enqueue runs at about 7 to 14 messages per second.
- A3. researcher-profile-v1 holds 64,090 documents (docs/agent-runs/phase-14/agent-report-rag.md:67, citing Phase 7), so list-vectors can return 19,200 keys.
- A4. rag's report object is masked by the job itself (buckets and account, RetrievalEvalJob.scala:256-259, :317) and its error fields hold codes and exception class names only (RetrievalEval.scala:150-155, :207; SemanticRetriever.scala:78), so a byte copy into the run folder holds no host, URL, bucket or account; the runner's identifier check stays the backstop.
RISKS:
- R1. C's two-task objective depends on the send rate, which the decision 11.4 rule does not model (A2). Under the high handler rate (20/s per task) the backlog can empty near the second scale-out (t0 + 3 to 6 min). "TWO-TASK SCALING UNCERTAIN" stays in the C notes with this reason. The spec gate (0 -> 1 -> 0) is not affected. A parallel or long-lived sender would remove the dependency, but it is a collector design change, and it would make J-02 (shared aws.err) worse. Proposed for the repair round (one pass with J-01..J-05) only if the orchestrator wants it.
- R2. C at N = 19,200 takes longer than 6b's provisional plan: about 50 minutes central, and up to about 2 h 10 min worst case under the runner limit (7,700 s). The orchestrator's runtime-hour checkpoint before D12 should use these numbers.
- R3. C's list-vectors of 19,200 keys (39 pages of 500) runs in one bounded CLI call (CC_CALL_TIMEOUT 120 s, 3 tries; c-embedding-backlog.sh:270-271). A very slow listing fails with exit 5, nothing sent.
- R4. E: if the pipeline ends 7 (wait reached, task still running) or the collector's outer limit fires, the ops task may still run. The collector prints `MAY-STILL-RUN:` and the next ops loader is refused by the pipeline's one-task check.
- R5. E has no AWS/ECS series for the ops task (standalone task, Container Insights off; list-metrics shows ECS series only with ServiceName). The query was dropped and this is stated in the manifest.
- R6. Review H-01 (major, interface: yes, R-1) is outside 6c. It affects async query IDs, not C or E. F part 3 uses sync queries, but the orchestrator should confirm.
MANUAL ACTION REQUIRED:
- Human, before the session 2 commit: `chmod +x experiments/collectors/e-retrieval-modes.sh` (with the other collectors, scripts/README.md:170-172).
- Human, D9: sign the eight manifests by the sha256 list below (recompute at D9; any later edit changes it).
- Orchestrator: give R-3 the C numbers and R1/R2. Update the D12 cost and time line: central about $0.12 and about 50 min; upper bound about $0.25 and up to about 2 h.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-3 (code-reviewer: 5b and 6c), then the repair round (J-01..J-05 and any R-3 finding, one pass), then D0a/D9/D9a in session 3.

## INTERFACE CHECK (done first; every name read in the producer's report and, where it matters, in the code; no mismatch)

| Name (producer) | Where | Used by 6c as | Result |
|---|---|---|---|
| Loader call line `bash scripts/run-bounded-pipeline.sh --stage load --snapshot ID --loader rag-retrieval-eval --graph-run-id run:v1:HEX32 [--modes] [--max-seconds] [--question-timeout-seconds] [--wait-timeout]` (infra 5b) | agent-report-infra-5b.md:150-163; run-bounded-pipeline.sh:1829-1850 | manifest `action.args`, passed unchanged | match |
| `--wait-timeout` >= (max-seconds or 1200) + 300, else exit 2 (5b) | 5b handoff :160; R3 | collector requires that + 720 (H-02) | match (stricter on purpose) |
| Run record `evidence/runs/ATTEMPT_ID-load.json`, kind `scholarchain-pipeline-run-record`, `stage`, `status`, `attempt_id`, `inputs.loader`, `outputs.retrieval_eval_report.{location, copy_outcome}` (5b) | 5b handoff :169-175; run-bounded-pipeline.sh:1233-1272, :1758-1783, :1852 | `find_record`, `copy_report` | match |
| Printed lines `RUN-RECORD:`, `EVAL-REPORT:`, `EVAL-REPORT-COPY:` (5b) | 5b handoff :167 | not wrapped: reach the runner unchanged | match |
| Pipeline exit codes 0/2/3/4/5/6/7 (5b) | 5b handoff :177 | passed through as the collector's exit | match |
| Report location `<semanticContextPrefix>/retrieval-eval/<attempt-id>/report.json`, prefix `rag` (rag, 5b) | RetrievalEvalJob.scala:49, :210-212; config/course.json objectStore.semanticContextPrefix | key check `REPORT_PREFIX + attempt + /report.json` | match |
| Report fields `format` "scholarchain.rag.retrieval-eval-report", `formatVersion` 1, `attemptId`, `label`, `exploratory`, `status`, `totalMillis`, `llmCalls`, `limits.ks`, `modesRequested`, `evalSet.{questions (count), labels, notComparableCount}`, `modes.<m>.{questions, scored, recallAt{"1","5","10","30"}, recallCeilingAt, mrr, ndcg (a note string), errors, empty, abstentions, byDesignWeakQuestions, latencyMillis.{median,p95}}` (rag) | agent-report-rag.md:157; RetrievalEvalJob.scala:225-246, :261-274, :303; RetrievalEval.scala:42-55, :314-338 | jq extraction into retrieval-modes.csv | match (`ndcg` is a string note, so the cell is empty; latency stats are `{"n":0}` for an empty population, so the cell is empty, never 0) |
| retrieval-modes.csv columns read by 6a's derive script | experiments/derived/retrieval_modes.py:8-13, :41-70 | header plus three extra trailing columns (recall_ceiling, mode_questions, report_status), which DictReader ignores | match |
| Worker log line: event `reembed-delivery`, keys `taskId`, `outcome`; outcomes processed, replayed, permanent-failure, transient-failure; one JSON object per line written by rag's handler through `ServiceLog.external` (rag, service) | agent-report-rag.md:149; agent-report-service-2.md:79; Support.scala:276-286 (merges rag's keys into the services object) | collector-common.sh:530-532 constants and CC_WORKER_LOG_JQ :539-547 | match (no change needed) |
| Worker log stream prefix `worker/` | infra/terraform/modules/runtime/main.tf:193 (`awslogs-stream-prefix = each.key`, role `worker`, :67) -> stream `worker/<container>/<task>` | CC_WORKER_LOG_STREAM_PREFIX | match |
| Worker embedding concurrency 2 (service) | agent-report-service-2.md:47, :76 (ServiceLimits.EmbeddingWorkerConcurrency) | C's one-task rate = loop rate x 2 | match |
| Backlog timeline (infra 5a) | agent-report-infra-5a.md:78-86 | C's N and wait limit | match |

## Status per item (file:line)

1. **E collector and manifest: done.**
   - experiments/collectors/e-retrieval-modes.sh: argument check `parse_args` :113-160 (only stage load and loader rag-retrieval-eval; graph run pattern; `--wait-timeout` required and >= limit + 300 + 720); Terraform names `read_names` :168-179 (bucket, ECS cluster, ops family, ops container, ECR repository: in variables, never printed; prefix from config/course.json); running ops tasks :181-190; preflight :193-234 (read calls only); pipeline run :242-266 (unwrapped output, GNU timeout `--foreground` at wait + 600 s, exit 124/137 -> 7, `MAY-STILL-RUN:` line); nested record :270-296 (newest `*-load.json` newer than the start marker with `inputs.loader` = rag-retrieval-eval; a started-only record is reported); report copy :298-353 (logical key checked against `rag/retrieval-eval/<attempt>/report.json`, head-object size check <= 16 MiB, get-object, format/version/attemptId check, byte copy); extraction :355-414 (one CSV row per mode and K with `@csv`, missing values empty; per-mode summary into tool-record counts); main :417-476 (baseline: running ops tasks, exit 3 if > 0, exit 5 if unreadable; exit = the pipeline's non-zero code, else 5 if the copy failed, else 0).
   - experiments/manifests/p15-e-retrieval-modes.json: labelled EXPLORATORY (11 questions) in the description and notes; args `--stage load --snapshot dblp-2026-11-01 --loader rag-retrieval-eval --graph-run-id run:v1:f2625d504b12749389cf4f5b1a26aed4 --modes graph-only,vector-only,fused --max-seconds 1200 --question-timeout-seconds 60 --wait-timeout 2400`; maxDurationSeconds 3600; costBoundUsd 0.2 with its basis. Sizing: wait 2400 = 1200 + 720 overrun (H-02) + about 480 (task start, copy, margin), above the pipeline rule (1500) and the collector rule (2220). Outer limit 3000. Runner limit 3600 = about 120 start + 3000 + about 120 copy and extraction + 360 margin. Expected about 6 to 8 minutes.
   - E metrics, each checked: AWS/Neptune CPUUtilization, OpenCypherRequestsPerSec, TotalRequestsPerSec, TotalServerErrorsPerSec, MainRequestQueuePendingRequests (DBClusterIdentifier) `seen in account` (6b list-metrics; the dimension name was re-checked in 6c). AWS/DynamoDB ConsumedReadCapacityUnits for researcher-metric-cache, entity-link-cache and evidence-bundle `seen in account` (6c). AWS/ECS for the ops task was **dropped**: the account has no series for it (6c list-metrics).
2. **C final N and wait limit: done** (p15-c-embedding-backlog.json: description, `maxDurationSeconds` 7700, `resourceLimits.maxQueueMessages` 19200, `waitLimitSeconds` 5500, `workerEmbeddingConcurrency` 2, `action.args` `--count 19200 --wait-limit-seconds 5500`, notes). Numbers below.
3. **Worker log constants: checked, no change needed** (collector-common.sh:530-532 match the rag and service handoffs and the Terraform stream prefix, table above). The comments "FLAGGED FOR 6c" (collector-common.sh:522-529, f-poison-replay.sh:20-26) are now stale. I left them for the repair pass, because both files carry R-2 findings (J-01, J-02) that are repaired in one pass.
4. **Other manifest changes from 5b's final options: none needed.** 5b changed only the load stage (rag-ingest `--max-documents`, the new loader), README and IAM. A/B use the hadoop/spark options of 5a, which are unchanged. C, D and F do not call the pipeline.

## C's numbers (decision 11.4; all estimates, not measured)

| | low | central | high |
|---|---|---|---|
| handler rate per receive loop (rag) | 2.4/s | 5.4/s | 10/s |
| one-task rate (x worker embedding concurrency 2, service) | 4.8/s | 10.8/s | 20/s |
| N rule: (second scale-out by t0 + 5 min + 3 min = 480 s) x rate x 2 | 4,608 | 10,368 | 19,200 |
| drain time at one task for N = 19,200 | 4,000 s (about 67 min) | about 1,780 s (about 30 min); in practice set by the send phase (20 to 45 min) | 960 s; set by the send phase |
| cost (upper bound = low) | about $0.25 | about $0.12 | about $0.10 |

- **N = 19,200.** This meets the rule under the high rate and is under the 20,000 cap. The selection is the first 19,200 keys of researcher-profile-v1 in listing order (64,090 documents).
- **Wait limit 5,500 s from the first send** = 300 s (first task start) + 4,000 s (drain at the low rate) + 540 s (scale-in: 5 min at zero, alarm lag, task stop; 5a: 6 to 9 min) + 600 s.
- **maxDurationSeconds 7,700** = settle 900 + selection about 300 + wait 5,500 + activities and log counts 360 + 640 margin.
- **Cost line:** costBoundUsd 0.5. Voyage about 1.7 M tokens (free allowance). S3 Vectors PutVectors about 115 MB (about $0.02). DynamoDB 19,200 GetItem and 19,200 PutItem (about $0.02 to $0.04). S3 GETs (under $0.01). SQS (under $0.02). Worker up to 2 tasks for up to about 1.5 h (up to about $0.12).
- **"TWO-TASK SCALING UNCERTAIN" stays in the notes** (R1). The rule assumes N visible at t0, but the collector enqueues at about 7 to 14 messages/s for about 20 to 45 minutes. The second scale-out is safe under the low and central rates and marginal under the high rate.

## Final list of all eight manifests (for D9; sha256 of the working-tree files after the last edit)

| Manifest | sha256 |
|---|---|
| experiments/manifests/p15-a-hadoop.json | 1254a11cd8431bfdf41b0549edca9f62adf622a5c13858ee7e469be4fa0488d8 |
| experiments/manifests/p15-a-spark.json | 1875db33a1f57632e9fcec579dcce3f1c717139ac1cba13c685ed654588f9531 |
| experiments/manifests/p15-b-spark-burst.json | 1b869c94c0370810d45810cfb1b2f7e91f272f94415b563d8c990b54d8f17c87 |
| experiments/manifests/p15-c-embedding-backlog.json | 5dbe72f31cba671b4ae20613aff76bb2463d36edbc55c9e510af6152b12e666d |
| experiments/manifests/p15-d-query-load.json | 2af43f86a39c31b00cb5c95810265e4d417be5522cef2bbdba0afaff33dc480b |
| experiments/manifests/p15-d-read-load.json | d851716dfbcd909f96a4b5e5be67d88a85206de67f9e74478b020825c7ce3070 |
| experiments/manifests/p15-e-retrieval-modes.json | be3868dff8802e0cf2a2b60c5838444a3ec4bafb9cf7e9f15c2cab30ef1b8019 |
| experiments/manifests/p15-f-poison-replay.json | f079543cccde90c84f3581a47decf546b04d804bb1b8275b97ae497d9e999053 |

Any repair-round edit to a manifest changes its sha256; D9 recomputes from the committed files.

## Runner v1 rules for p15-e-retrieval-modes.json and p15-c-embedding-backlog.json (read from run-experiment.sh:426-479, :481-)

format: checked. formatVersion 1: checked. manifestId pattern and file name = manifestId.json: checked. example false: checked. experiment E / C: checked. description non-empty: checked. region us-east-1: checked. datasetSnapshot non-empty: checked. costBoundUsd 0.2 / 0.5 (> 0, <= 5): checked. maxDurationSeconds integer 3600 / 7700: checked. resourceLimits object: checked. action.kind command, path under experiments/collectors/ with no `..`: checked. args all strings: checked. No `{...}` left in args (check_placeholders): checked (neither has a brace). metrics array: checked.

## Argument contract with the runner (E, read end to end)

The runner passes `action.args` unchanged (plus `--preflight` in `--check`) and sets `SCHOLARCHAIN_EXPERIMENT_RUN_DIR`, `_ATTEMPT_ID` and `_MANIFEST_ID`. The collector:
- strips `--preflight` and forwards everything else to the pipeline;
- writes only into the run folder (retrieval-eval-report.json, retrieval-modes.csv, clock.csv, tool-record.json) and its own temporary folder outside the repository;
- does not wrap the pipeline's stdout or stderr, so the `RUN-RECORD:` lines reach the runner.

Exits: 0 -> runner 0 or 6. A non-zero exit (2, 3, 4, 5, 6 or 7 from the pipeline; 3 or 5 from the collector) -> runner 5 (action failed; metrics are still exported per 9.6(d)). The runner's own limit is 3,600 s, above the collector's outer limit of 3,000 s, so the collector's timeout fires first and records the attempt as timed out.

## INTERFACE HANDOFF

- Files changed: experiments/collectors/e-retrieval-modes.sh (new), experiments/manifests/p15-e-retrieval-modes.json (new), experiments/manifests/p15-c-embedding-backlog.json, experiments/README.md.
- E collector usage: `experiments/collectors/e-retrieval-modes.sh PIPELINE-ARGUMENTS... [--preflight]`. Accepts only `--stage load --loader rag-retrieval-eval`. `--graph-run-id run:v1:HEX32` and `--wait-timeout` are required, with wait >= (max-seconds or 1200) + 1020. Other arguments are forwarded unchanged.
- Raw outputs, per attempt:
  - `retrieval-eval-report.json`: a byte copy of rag's report.
  - `retrieval-modes.csv`: `mode,k,recall,mrr,ndcg,questions_scored,errors,empty,abstentions,by_design_weak,latency_p50_ms,latency_p95_ms,universe_size,not_comparable,label,recall_ceiling,mode_questions,report_status`. One row per mode and K. `mrr` is per mode, repeated on each K row. An empty cell means missing.
  - `clock.csv`.
  - `tool-record.json`: counts include `report_summary`, `nested_attempt_id`, `report_location` (logical), `job_copy_outcome` and `pipeline_exit`. Evidence items: `report_copy`, `modes_table`, `metrics`.
- Printed lines: `STEP:`, `INFO:`, `PREFLIGHT:` (preflight), the pipeline's own lines (unwrapped), `REPORT: s3://{data-bucket}/rag/retrieval-eval/ATTEMPT/report.json (job copy outcome: ...)`, `MODES: rows=N report_status=...`, `MAY-STILL-RUN:` (pipeline exit 7), `RESULT:`.
- Exit codes:
  - Run: 0; the pipeline's non-zero code; 2 usage; 3 an ops task is already running; 5 the copy failed after a successful pipeline, or the ops tasks were unreadable; 7 the outer limit.
  - Preflight: 0, 2, 3, 5.
- C manifest arguments: `--count 19200 --wait-limit-seconds 5500 --index researcher-profile-v1`.
- Thread-safety and limits: one process; the pipeline runs as a single child; every AWS call is bounded by GNU timeout (lib); the report is capped at 16 MiB.

## Proposed later (not built)

- C sender: parallel or long-lived sending (removes R1). Decide with the J-02 repair.
- Update the stale "FLAGGED FOR 6c" comments in collector-common.sh:522-529 and f-poison-replay.sh:20-26 in the repair pass.
- A runner placeholder for the Neptune cluster identifier (6b R4), if the human prefers it to the literal name.

## VALIDATION EVIDENCE

- work order: `shasum -a 256 docs/agent-runs/phase-15/prompt.md` -> 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (matches).
- `shellcheck -x -P experiments/collectors` (absolute paths) on e-retrieval-modes.sh, c-embedding-backlog.sh, f-poison-replay.sh, ab-pipeline.sh, d-load.sh, lib/collector-common.sh -> rc=0, no output (after the last edit of any file).
- `jq empty` on the eight p15 manifests -> rc=0 (after the last edit).
- `shasum -a 256` after validation: manifests as listed above; e-retrieval-modes.sh cb15ece70ca0b0652119732ffbad9f7c32bd99fc1e5e1d075d2e435f29ad4387; experiments/README.md d2cfe0acd328045abc3a37ca79e80d0a8173c31eacfff5649227f3c90e36f1f4; lib/collector-common.sh 9e4879917be89b82f002c68e208bd3c855d271a21729401ccf15f8e17f0ca8fb (unchanged by 6c).
- `git status --short experiments load-tests` -> ` M experiments/README.md`; untracked: experiments/collectors/, experiments/derived/, the eight p15 manifests, experiments/results-summary-template.md, load-tests/ (all session 2 work, uncommitted).
- `ls -l experiments/collectors/` -> e-retrieval-modes.sh `-rw-r--r--` (not executable yet: human `chmod +x` before the commit).
- aws-api: 4 read-only list-metrics calls (within the limit of 10); no identifier copied.
files changed after these runs: none
