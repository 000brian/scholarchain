# agent-report-experiment-6a (Phase 15, session 2, step 6a)

interface: no (no fixed interface of this prompt is changed; this step defines new tool interfaces for 6b, listed under INTERFACE HANDOFF)

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, checked with shasum at start), decision 11 part 6a items 1-3; HEAD 2cbfbc72c704c40085e2876479d510b90062a12a. Scope check: --mode read in scope (no reduction).

FILES CREATED:
- load-tests/query-load.sh (NEW EXECUTABLE: needs chmod +x by the human)
- load-tests/questions-d.txt
- load-tests/read-ids-d.txt
- experiments/derived/common.py
- experiments/derived/emr_capacity.py
- experiments/derived/queue_worker.py
- experiments/derived/load_levels.py
- experiments/derived/retrieval_modes.py
- experiments/derived/failure_timeline.py
- experiments/derived/fixtures/README.md
- experiments/derived/fixtures/raw/p15-a-spark/20261120T200000Z-000000000000-a001/{run-record.json, emr-cluster.json, metrics/queries.json, metrics/metric-data.json}
- experiments/derived/fixtures/raw/p15-b-spark-burst/20261120T203000Z-000000000000-b001/{run-record.json, metrics/queries.json, metrics/metric-data.json}   (failed attempt, all series empty, no lifecycle record)
- experiments/derived/fixtures/raw/p15-b-spark-burst/20261120T210000Z-000000000000-b002/{run-record.json, emr-cluster.json, metrics/queries.json, metrics/metric-data.json}
- experiments/derived/fixtures/raw/p15-c-embedding-backlog/20261120T220000Z-000000000000-c001/{run-record.json, service-samples.csv, queue-samples.csv}   (failed attempt, collector exit 3, no metrics)
- experiments/derived/fixtures/raw/p15-c-embedding-backlog/20261120T223000Z-000000000000-c002/{run-record.json, service-samples.csv, queue-samples.csv, scaling-activities.csv, metrics/queries.json, metrics/metric-data.json}
- experiments/derived/fixtures/raw/p15-f-poison-replay/20261120T213000Z-000000000000-f001/{run-record.json, events.csv, queue-samples.csv, alarm-samples.csv, service-samples.csv, incident-F.json, metrics/queries.json, metrics/metric-data.json}
- experiments/derived/fixtures/raw/p15-d-query-load/20261120T230000Z-000000000000-d001/{run-record.json, load-requests.csv, load-levels.csv, load-explain.csv, load-clock.csv, service-samples.csv, metrics/queries.json, metrics/metric-data.json}
- experiments/derived/fixtures/raw/p15-d-read-load/20261120T233000Z-000000000000-r001/{run-record.json, load-requests.csv, id-check.csv, load-levels.csv, load-clock.csv}   (metrics failed: missing)
- experiments/derived/fixtures/raw/p15-e-retrieval-modes/20261120T214500Z-000000000000-e001/{run-record.json, retrieval-modes.csv}
- experiments/results-summary-template.md
FILES MODIFIED: none (no existing file touched)

COMMANDS RUN (all read-only except Write/Edit of the files above):
- shasum -a 256 of the work order; wc; ls; git status --short; git check-ignore -v; git ls-files; git -C . ls-files modules/services/src/main
- grep over modules/services, modules/rag, modules/core, config/course.json, infra/terraform/environments/course/variables.tf, docs/agent-runs/phase-10|14 (read-only)
- jq over benchmark-v1.json, the Phase 10 attempt-06 run report, the two Phase 14 smoke-test records (evidence/runs, fields status/query_id/evidence_id only)
- shellcheck load-tests/query-load.sh (three times); jq empty on all 24 fixture JSON files
- a read-only bash loop (grep -o , | wc -l) counting commas per line in every fixture CSV; a read-only loop (jq) matching each questions-d.txt line to benchmark-v1
- DEVIATIONS from the command list (each read-only, nothing written, reported here): (1) `python3 --version` once (chained after `command -v`), to know the interpreter version for stdlib choices (3.15.2); (2) one `sed -n '/^### 19/,/^### 20/p' … | head -n 0` that printed nothing (head refused the count); the section was then read with the Read tool; (3) several Bash calls started with `cd /Users/brian/Developer/repos/scholarchain &&` instead of absolute paths. No script, sbt, curl, aws or docker was run; aws-api not used.

TEST RESULTS: no sbt (not a Scala step; never run). No tool or derive script run (forbidden; D0a is the human's first run).

VALIDATION RESULTS: see VALIDATION EVIDENCE at the end.

ASSUMPTIONS:
- The runner's run folder layout is as in scripts/run-experiment.sh at HEAD (run-record.json, metrics/queries.json, metrics/metric-data.json as raw get-metric-data output with ISO timestamps). infra-engineer's 5a edits run-experiment.sh in parallel (git status shows it modified); if 5a changes file names or the record fields `.status`, `.exit_code`, `.action.window_epoch`, `.metrics.unresolved`, common.py needs the same change (6b/6c check).
- benchmark-v1 researcher and work IDs are from the Phase 10 export (sourceRunId run:v1:51b50d…); whether they exist in the live Phase 14 graph is unverified until the read preflight (D9a).
- Phase 10 attempt-06 answered all 27 non-abstention questions (expectedAbstentionsHappened 3, unexpectedAbstentions []); only q05 is confirmed answering live (Phase 14 D14).

RISKS:
- R1 first run: query-load.sh is runtime: untested; its first live use is D9a (--preflight through the runner's --check) and D13/D14. Bash 3.2 only constructs used (no associative arrays, mapfile, ${x,,}, wait -n, quoted pattern replacement; empty-array expansion guarded with ${a[@]+...}).
- R2 BSD vs GNU date for the HTTP Date conversion: tries `date -j -u -f` then `date -u -d`; if both fail the epoch cell is empty and clock.csv says "HTTP Date missing" (never invented).
- R3 Read-mode share: 3 of 29 listed IDs are evidence reads (about 10 % of read requests); the evidence route needs a window when an item has several (WINDOW_REQUIRED, recorded as other-4xx).
- R4 Explain reads happen after each level window (up to 60 sequential GETs, at most 150 s per level), so a query whose audit is not yet written shows QUERY_NOT_FOUND (recorded, stages missing).
- R5 Derive scripts are not executed by any agent; syntax and logic are checked only by reading (reviewer R-2). They set sys.dont_write_bytecode before importing common.py, because `python3 -I` does not imply -B and `experiments/**` is un-ignored (.gitignore:83 `!experiments/**`), so a __pycache__ would dirty the tree.
- R6 E plots have K on the x axis, not UTC (E has no time series or scaling event); a planned exception to "every plot has UTC on the x axis" for the reviewer and the human to accept or reject.

MANUAL ACTION REQUIRED:
- chmod +x load-tests/query-load.sh before the session 2 commit (decision 16 step 2). The .py scripts run as `python3 -I experiments/derived/NAME.py` and need no executable bit.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 (code-reviewer reads 5a, 6a, 6b); 6b consumes INTERFACE HANDOFF below; D0a (human runs each derive script with --fixtures); D9a (runner --check runs query-load.sh --preflight through d-load.sh).

## Status per item (one line each, file:line)

- 6a.1 load generator: done. load-tests/query-load.sh:1-1011; closed-loop clients :723 (client_loop, mkdir tickets for the hard cap, deadline per level); levels and caps :28-36, :234 (parse_args, query cap 60 and at most 8 clients, read cap 600 and at most 20, at most 6 levels, 10..300 s, pause 0..300 s); API URL from `terraform output -raw api_url`, trailing / stripped, never printed :495; key only via curl --config - on stdin :514 (send); classes :561 (classify); per-request row :723-760; explain reads after each level with stages from QueryWorkflow :846 (explain_phase); clock samples with HTTP Date :645; healthz/readyz retried (G-23) :668; read-mode ID check before the levels and in --preflight :698; per-level summary twice (all and success population), nearest-rank, n/a and few-samples :802; tool record :897; exit codes 0/2/3/5/7 in usage :81.
- 6a.1 question list: done. load-tests/questions-d.txt (25 questions; plan column; selection rule and exclusions q13, q27-q30 in its header; texts match benchmark-v1 exactly: loop check 25/25).
- 6a.1 read-ID list: done. load-tests/read-ids-d.txt (26 graph IDs from benchmark-v1, all present in it; 2 evidence IDs from the Phase 14 D14/D15 smoke records, the q05 metric item also with window 2015-2025; 29 lines, all well-formed).
- 6a.1 explain stage fields: done (from code, rag step 3 will confirm). Recorded: latencyMillis and stageMillis keys planner, plannerFallback, resolver, metrics, paths, community, semantic, evidence, identity, synthesis, synthesisFallback, verification, plus inputTokens, outputTokens, abstained. Sources: modules/services/src/main/scala/org/scholarchain/services/Models.scala:233-260 (ExplainView: stageMillis :247, latencyMillis :248, inputTokens/outputTokens :249-250, abstained :252), :290-295 (copied from QueryAudit); modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala:164-167 (timed), :176-231 (stage names), :177 and :227 (fallback markers), :451-452 and :482 (the map stored in the audit is taken before the "audit" stage ends, so "audit" is never stored), :746-758 (StageNames). Missing (no field in explain today): HTTP retries per query (Models.scala:272-273 AttemptsNote), Neptune latency separate from the graph stages, Voyage latency separate from the S3 Vectors query (both inside "semantic"), the G-28 abstention reason (not yet in ExplainView; rag/service add it in steps 3/4; the tool does not record it: proposed later). Mapping to spec D items in experiments/derived/load_levels.py:52-61 (COVERAGE).
- 6a.1 cost estimates: done (below, all labelled estimate).
- 6a.2 derive scripts: done. experiments/derived/common.py (shared: Context, Attempt, metric parsing, nearest-rank pct, SVG time_chart and category_chart, step_events, inputs.csv/missing.csv); emr_capacity.py (A, B); queue_worker.py (C, F); load_levels.py (D); retrieval_modes.py (E); failure_timeline.py (F). Each: `--manifest ID` (repeatable) and `--fixtures`; reads only experiments/raw/** or experiments/derived/fixtures/raw/**; writes to experiments/derived/out/MANIFEST_ID/ or, with --fixtures, only to a new tempfile.mkdtemp folder outside the repository (checked, common.py Context.__init__) whose path it prints; prints READ: sha256 path, MISSING: lines, METRICS-EXPORT: which export it used (a later ATTEMPT-metrics-UTCSTAMP re-export is preferred when present); never fills a gap with 0 (empty cell; line breaks after max_gap; missing series named in the legend); UTC x axis with scale-out (green), scale-in (red) and lifecycle (gray) markers; deterministic output (no generation time written).
- 6a.2 fixtures: done. experiments/derived/fixtures/README.md and raw/ (7 manifest folders, 10 attempts; failed attempts: p15-b-spark-burst b001, p15-c-embedding-backlog c001; empty series in A, B, C, D-query, F; D-read with no metrics folder; E with a missing K row and a universe under 15; p15-a-hadoop deliberately absent to exercise "no attempts").
- 6a.3 results-summary template: done. experiments/results-summary-template.md (identity, attempt index with the four evidence-status lines, per-experiment block with configuration, limits, attempts, results, plots, evidence sources, failures, cost, interpretation kept apart; experiment-specific content for A to F; the spec scaling-rule table with evidence column; the gate table under the D15/D29 rule; overlapping runs; cost; limitations including decision 15 deviations 1-7 and 11 and the A/B rules).

## Cost estimates (all estimate, not measured)

- D-query (`--mode query`, levels 1,2,4,8, 120 s, cap 60): upper bound 240 queries x about $0.0023 = about $0.55 (estimate). Basis: Phase 10 attempt-06 used 164,511 input and 105,161 output tokens for 30 questions (docs/agent-runs/phase-10/run-report-attempt-06-anthropic.json llmUsage), about 5,500 in and 3,500 out per question; Haiku 5.5 at $0.10/$0.50 per million (docs/agent-runs/phase-10/prompt.md:312); Phase 10 human gate recorded about $0.06 for the 30 questions (consistent). Expected well below the bound (estimate): llm.requestsPerMinute 19 per task (config/course.json:79) and about 2.5 model calls per query (76 requests / 30 questions) allow about 7-8 queries per minute per query task, so about 60-80 answered queries over the four levels, about $0.15-0.20 (estimate); throttled and overloaded requests make no model call. Explain reads: DynamoDB reads only, negligible (estimate). Voyage: only q25 and q26 embed a query, inside the free allowance (estimate). Duration upper bound: 4 x (120 + 35 + 150 + 60) s, about 25 min; the tool's own run deadline is 1,820 s for the defaults (query-load.sh:987).
- D-read (`--mode read`, levels 1,5,10,20, 120 s, cap 600): at most 2,400 level requests plus 29 ID-check requests; the API stage throttle (5 per second, burst 10) also bounds each level at about 600 accepted requests (estimate). Neptune I/O: about 2,200 neighbour reads x an assumed 100 I/Os each = about 0.22 million I/Os x $0.20 per million = about $0.05 (estimate; the 100 I/Os per depth-1, limit-50 read is an assumption, unverified). DynamoDB evidence reads and API Gateway ($1 per million) under $0.01 (estimate). Duration about 4 x (120 + 15 + 60) s + ID check about 15 s, about 13 min (estimate).
- Fixtures and derive scripts: no AWS cost.

## INTERFACE HANDOFF

Files changed: listed under FILES CREATED. Public entry points:

1. `load-tests/query-load.sh --mode query|read [--levels N,N,...] [--level-seconds N] [--max-requests-per-level N] [--pause-seconds N] [--list FILE] [--out-dir DIR] [--preflight]`
   - Run from the repository root (refuses elsewhere, exit 2). Defaults: query levels 1,2,4,8, list load-tests/questions-d.txt; read levels 1,5,10,20, list load-tests/read-ids-d.txt; 120 s per level; pause 60 s; caps query 60, read 600 (arguments may only lower them); clients at most 8 (query) / 20 (read); at most 6 levels.
   - Environment: SCHOLARCHAIN_SERVICE_API_KEY (required in both modes and in --preflight; refused when unset, or holding a control character, a double quote or a backslash: exit 3); SCHOLARCHAIN_EXPERIMENT_RUN_DIR (default output folder; set by the runner); SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID and SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID (recorded; the attempt ID is part of each Idempotency-Key `p15d-ATTEMPT-L<level>-t<ticket>`); TMPDIR (temporary folder). Needs git, curl, jq, awk, sort, date, cut, grep, tr, shasum or sha256sum, timeout or gtimeout, terraform (output only).
   - --out-dir must exist and be writable; inside the repository it must be gitignored (evidence/experiment-runs/…); refuses (exit 5) if any of its output files already exists. --preflight writes only into a new `mktemp -d` folder outside the repository (path printed), sends GET /healthz (with headers, for the clock) and GET /readyz (3 tries, 5 s and 10 s backoff), checks the list format, and in read mode sends one GET per listed ID (0.3 s apart) printing `ID-CHECK: ROUTE ID status=CODE class=CLASS` and `ID-CHECK-SUMMARY: ids=N answered_200=N other=N`; never a POST /v1/query.
   - stdout lines: `STEP:`, `INFO:`, `READY: try=N healthz=yes|no readyz=yes|no readyz_status=CODE`, `ID-CHECK:`, `LEVEL-SUMMARY:` (two per level), `PREFLIGHT:`, `RESULT: query-load.sh ok|failed exit=N step=S`; errors `ERROR: query-load.sh step=S exit=N: …` on stderr. It prints no `RUN-RECORD:` line (it starts no nested script). It never prints the URL, host or key.
   - Exit codes: 0 ran (any HTTP answers); 2 usage, malformed list, not at repo root, output folder not gitignored; 3 prerequisite not met (tool, terraform output api_url, key, API not ready after 3 tries: nothing sent); 5 tool failure (output folder unusable or a file exists, a client process failed: the record says client-failed and the data are partial); 7 the run's own deadline or TERM (INT gives 5). The record is written on every exit after the start (status completed | failed | prerequisite-not-met | deadline | interrupted | client-failed).
   - Output files (raw layout under the run folder):
     - `load-requests.csv`: mode,level,clients,client,seq,ticket,item,start_epoch_s,end_epoch_s,route,http_status,curl_exit,time_total_s,class,error_code,query_id. item = question ID (query) or the stable ID (read). route = template (`POST /v1/query`, `GET /v1/graph/nodes/{nodeId}/neighbors?depth=1&limit=50`, `GET /v1/evidence/{evidenceId}`). http_status 000 when curl got no answer; time_total_s empty when curl printed none; error_code = the API's `error` code, `no-error-field`, `no-body`, `curl-N`, `tool-timeout-N`, `unreadable-answer`, `http-N`, or `-`; query_id `-` when absent.
     - classes: query answered, abstained, throttled (429), overloaded (503), timeout (504 or curl 28 or tool timeout), other-4xx, other-5xx, client-error, unclassified (a 2xx without a readable answer.abstained); read ok, not-found (404), then the same.
     - `load-levels.csv`: mode,level,clients,window_start_epoch_s,window_end_epoch_s,deadline_epoch_s,stop(duration|cap),attempted,completed,completed_per_s,untimed,n_<class> for the 9 classes of the mode,n_all,p50_all_s,p95_all_s,p99_all_s,note_all,n_success,p50_success_s,p95_success_s,p99_success_s,note_success (note ok | few-samples | n/a; completed = an HTTP answer was received).
     - `load-explain.csv` (query): level,question,query_id,start_epoch_s,http_status,curl_exit,time_total_s,error_code,latency_ms,planner_ms,plannerFallback_ms,resolver_ms,metrics_ms,paths_ms,community_ms,semantic_ms,evidence_ms,identity_ms,synthesis_ms,synthesisFallback_ms,verification_ms,input_tokens,output_tokens,abstained,other_stage_keys (empty cell = missing).
     - `id-check.csv` (read): route,item,start_epoch_s,http_status,curl_exit,time_total_s,class,error_code.
     - `load-clock.csv`: phase(start|end),local_send_epoch_s,local_recv_epoch_s,http_status,curl_exit,time_total_s,http_date (without weekday),http_date_epoch_s.
     - `load-tool-record.json`: kind scholarchain-load-tool-record, version 1, tool, tool_sha256, mode, arguments, limits, list {path, sha256, entries}, attempt_id, manifest_id, start/end epoch, status, exit_code, success_class, percentile_method, explain_stage_keys, per_level[], errors[], files[], evidence {requests, clock}.
   - Thread-safety: clients are background subshells writing their own rows file; the per-level cap uses atomic `mkdir` tickets; rows are merged after the level.
   - Limits: hard caps as above; curl --max-time 35 s (query) / 15 s (read, explain, health); timeout wraps each curl (max-time + 10 s); explain phase at most 150 s and at most cap reads per level; run deadline levels x (level + max-time + 15 + 150 + pause) + 300 s.

2. Raw files the derive scripts expect from 6b's collectors (6b writes them with these exact names and headers; every cell an extracted field; empty cell = missing):
   - `service-samples.csv`: epoch_s,service,desired,running,pending (service = ECS service name; the worker's name must contain "worker"). Used by C, F (worker) and D (d-load.sh: query, evidence, graph).
   - `queue-samples.csv`: epoch_s,queue,visible,in_flight with queue one of embedding, embedding-dlq, job, job-dlq (role names, not URLs).
   - `scaling-activities.csv`: start_epoch_s,end_epoch_s,service,direction(out|in|unknown),status_code.
   - `alarm-samples.csv` (F): epoch_s,alarm,state.
   - `events.csv` (F): epoch_s,part(poison|replay|api),source(collector|worker-log|completion-record|sqs|api),event,task_id,outcome,code. Replay is proven only by outcomes processed and replayed from source worker-log or completion-record; poison DLQ arrival uses outcome `dlq-received`.
   - `incident-F.json` (F): any object; its top-level keys are listed.
   - `emr-cluster.json` (A, B, from ab-pipeline.sh): {"clusters":[{"clusterId","createdEpoch","readyEpoch","terminationRequestedEpoch","endedEpoch","finalState","stateChangeReasonCode"}]} (epoch seconds; terminationRequestedEpoch separates scale-in while running from release at the end).
   - `retrieval-modes.csv` (E, from 6c's e-retrieval-modes.sh): mode(graph-only|vector-only|fused),k,recall,mrr,ndcg,questions_scored,errors,empty,abstentions,by_design_weak,latency_p50_ms,latency_p95_ms,universe_size,not_comparable,label.
   - From the runner: run-record.json (`.status`, `.exit_code`, `.action.window_epoch.start|end`, `.metrics.unresolved`), or run-record.started.json only (reported as incomplete); metrics/queries.json (Id, Label, MetricStat.Metric.Namespace/MetricName/Dimensions, Period, Stat); metrics/metric-data.json (MetricDataResults[].Id, Timestamps, Values, StatusCode); re-export folders `ATTEMPT-metrics-UTCSTAMP/metrics/` (decision 9.6 f) are used in preference when present.
   - Metric names the scripts look for: AWS/ElasticMapReduce TotalUnitsRequested, TotalUnitsRunning, CoreUnitsRequested, CoreUnitsRunning, CoreNodesRequested, CoreNodesRunning, ContainerPending, AppsPending, YARNMemoryAvailablePercentage (dimension JobFlowId); AWS/SQS ApproximateNumberOfMessagesVisible, NumberOfMessagesDeleted (QueueName containing "embedding", DLQ names containing "dlq"); AWS/ApplicationELB TargetResponseTime; AWS/ECS CPUUtilization, MemoryUtilization; every other exported series still appears in D's cloudwatch-periods.csv.

3. Derive scripts: `python3 -I experiments/derived/NAME.py [--manifest ID ...] [--fixtures]`, NAME in emr_capacity (default manifests p15-a-hadoop, p15-a-spark, p15-b-spark-burst), queue_worker (p15-c-embedding-backlog, p15-f-poison-replay), load_levels (p15-d-query-load, p15-d-read-load), retrieval_modes (p15-e-retrieval-modes), failure_timeline (p15-f-poison-replay). Exit 0 normally; 2 for a malformed --manifest; 5 if the temporary folder would be inside the repository. Outputs per manifest under out/MANIFEST_ID/: inputs.csv (path, sha256), missing.csv, and: emr_capacity capacity.csv, attempts.csv, capacity-ATTEMPT-CLUSTER.svg; queue_worker samples.csv, observed.csv, queue-ATTEMPT.svg; load_levels levels.csv, explain-stages.csv, explain-coverage.csv, clock.csv, tasks.csv, cloudwatch-periods.csv, id-check-summary.csv, latency-/classes-/tasks-ATTEMPT.svg; retrieval_modes modes.csv, recall-/mrr-ATTEMPT.svg; failure_timeline timeline.csv, parts.csv, incident-keys.csv, timeline-ATTEMPT.svg. Two scripts writing the same manifest folder (queue_worker and failure_timeline both on p15-f-poison-replay) write different file names except inputs.csv and missing.csv, which the later run overwrites (proposed later: per-script names).

## Proposed later (not built)

- Record the G-28 abstention reason field in load-explain.csv once steps 3/4 fix its name (one line in the jq filter; a tool change, no manifest change).
- Per-script inputs/missing file names when two scripts share a manifest folder.
- A cross-manifest A/B comparison table (now the summary compares rows of attempts.csv).

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-15/prompt.md` -> 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0 (matches the work order)
- `shellcheck load-tests/query-load.sh` -> no output, exit 0 (last run after the last edit of the script)
- `jq empty` on all 24 fixture JSON files (run-record, queries, metric-data, emr-cluster, incident-F) -> exit 0
- comma count per line of every fixture CSV: constant within each file (after fixing load-clock.csv end row of p15-d-query-load from 6 to 7 commas)
- questions-d.txt vs benchmark-v1: 25 of 25 lines match id and question text exactly; single quotes in the file: 0
- read-ids-d.txt: 29 of 29 IDs match ^(researcher|work|evidence):v1:[0-9a-f]{32}$; 26 of 26 graph IDs found in benchmark-v1; single quotes: 0
- `git status --short experiments load-tests` -> `?? experiments/derived/`, `?? experiments/results-summary-template.md`, `?? load-tests/` (no tracked file of mine changed; other changed files in the tree belong to other steps)
- sha256 of deliverables (final): query-load.sh 05b255ad4e0a1489790c019a88081872e8d8a61ef790d843d10859cf70488612; questions-d.txt f20a57a973fafdf8201a36b5ee07d92d3d9de1d581b6f0f4090e6668b4b55eb5; read-ids-d.txt 2629832c8cee319d852133d0eb92fddbe18f655c8f3ac95dcd712b6736fd1b00; common.py 8ecd5fd7ec05ff28f169063565bd59697ff35b914cf5b3b7fe3f2243bde8fd83; emr_capacity.py 5fbaabe227d8043cdf2b5f37af5272ff91fef1637b7d9f3efd379ffaffcd876b; queue_worker.py d9d5fc69dc5b8a6e712ddc440df45a50543c12d8ed0848ff0cdfdf8662461710; load_levels.py d288db0ad801c59a4a0164e76b72807340b17ce10bfd1009bc930f0b9f5c5606; retrieval_modes.py c83c499dcbffbd4cec606bff675c12ce630f6df250fba67e71c060d0eafb1a61; failure_timeline.py 6241f181750bc0fc513b0b16716c4668f4dd441b62f1ed8a37a124fcfffae173; results-summary-template.md 4b0f6d52ac649c6aa3aa7ae3939f0c98838165566de3b10041103e4c8114358e; fixtures/README.md 3e41453907a651f7110f515e9337c8491dda8033420911f6001fbc2d022fafb8
- Python files: not executed by any agent (decision 11 item 2); syntax and logic checked by reading only: unverified until R-2 and D0a
- finished: after the last command above
files changed after these runs: none
