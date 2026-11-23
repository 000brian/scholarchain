# ScholarChain Phase 15 results summary (TEMPLATE)

Owner: `experiment-engineer`. This is the template `experiments/results-summary.md` is written from at D23 (decision 13). Replace every `FILL` with a value and its source file; never leave a number without its source. Measured facts and interpretation stay in separate sub-sections. Every number that was not measured in this phase is labelled `estimate` with its basis. A value that does not exist is written `missing` (with the reason), never 0 and never inferred.

Status words (decision "How the run ends"): step status `done | not needed | skipped (depends on STEP) | failed | not run`; requirement status `done | defect (FINDING-ID) | deviation | missing | phase-16 | phase-17 | blocked-on-human`. Three things are kept apart for every experiment: its **execution** (step status, by its last attempt), its **measured outcome** (what the data show, including "not seen") and whether it **satisfies a spec gate item**.

## 0. Identity of the phase

| Field | Value | Source |
|---|---|---|
| Work order | `docs/agent-runs/phase-15/prompt.md`, sha256 FILL | `shasum -a 256` |
| Session 2 commit (tools, manifests) | FILL | `git log` |
| Build commit (image, jars, `--code-version`) | FILL | `evidence/packages/…` package record |
| Runtime-on plan and release | plan FILL, release FILL | `plans/*.record.json`, `releases/course/*.json` |
| Region | `us-east-1` | every run record |
| Dataset snapshot | `dblp-2026-11-01` (`dblp-subset-v1` where a manifest names it) | manifests, run records |
| Graph run in the stores | `run:v1:f2625d504b12749389cf4f5b1a26aed4` | Phase 14 records |
| Clock | laptop offset from AWS time FILL s, uncertainty plus or minus FILL s (at least 1 s plus half the round trip); CloudWatch data in 60 s periods | `experiments/derived/out/p15-d-*/clock.csv` |

Timestamps claim: "aligned on UTC to the measured offset and its uncertainty, inside 60 s metric periods", never millisecond synchronization (decision 10).

## 1. Attempt index (every attempt, failed and incomplete ones included)

One row per attempt folder under `experiments/raw/` and per started-only record under `evidence/experiment-runs/`. A later success never replaces an earlier failure.

| Manifest | Attempt ID | Runner exit | tool | action | metrics | observation | Raw folder |
|---|---|---|---|---|---|---|---|
| FILL | FILL | FILL | `ran / failed / refused` | `succeeded / failed / timed-out` | `complete / partial (list) / none` | `seen / not seen / not applicable` (evidence file) | `experiments/raw/MANIFEST_ID/ATTEMPT_ID/` |

## 2. Per experiment

Repeat this block for each manifest, in run order: `p15-f-poison-replay`, `p15-e-retrieval-modes`, `p15-c-embedding-backlog` (and `-2` if run), `p15-d-query-load`, `p15-d-read-load`, `p15-a-hadoop`, `p15-a-spark`, `p15-b-spark-burst`. A manifest whose live run was dropped by the scope rule says `deviation (planned scope reduction)` and why.

### 2.x MANIFEST_ID (experiment X)

**Manifest:** `experiments/manifests/MANIFEST_ID.json`, sha256 FILL, approval line in `docs/agent-runs/phase-15/approvals.md` (FILL: commit). Description FILL.

**Configuration and limits:**

| Item | Value | Source |
|---|---|---|
| Action and arguments (resolved `{attempt-id}`) | FILL | `run-record.json` `.action` |
| Declared limits (not enforced) | `costBoundUsd` FILL, `maxDurationSeconds` FILL, `resourceLimits` FILL | `run-record.json` `.declared_not_enforced` |
| Real caps (Terraform) | FILL (for example `worker_max_count` 2, `emr_managed_scaling_max_units`, API throttle 5 per second burst 10, `maxConcurrentQueries` 4, `llm.requestsPerMinute` 19) | file:line |
| Tool sha256 and list sha256 | FILL | `tool-record.json` / `load-tool-record.json` |

**Attempts** (each with its four evidence-status lines):

```text
ATTEMPT_ID
  tool: ran | failed | refused          (runner exit FILL)
  action: succeeded | failed | timed-out (run-record.json status FILL)
  metrics: complete | partial (FILL) | none
  observation: FILL: seen | not seen | not applicable (evidence: FILL path)
  HEAD FILL, build commit FILL, start FILL UTC, end FILL UTC, run IDs FILL (execution names, EMR cluster IDs, nested run records)
```

**Measured results** (tables from `experiments/derived/out/MANIFEST_ID/*.csv`, each table naming its CSV and the raw sha256 list in `inputs.csv`):

FILL table.

**Plots:** `experiments/derived/out/MANIFEST_ID/FILL.svg` (UTC on the x axis; scale-out and scale-in events marked; missing series named in the legend).

**Evidence sources:** from CloudWatch: FILL; from the tool's own records: FILL; missing: FILL (from `missing.csv`).

**Failures** (never edited out): FILL (attempt, cause class 1 to 4 of decision 10, what was kept).

**Cost:** FILL `estimate` (basis) and, where Billing shows it, the measured share (labelled).

**Interpretation** (separate from the facts above): FILL.

Experiment-specific required content:

- **A (`p15-a-hadoop`, `p15-a-spark`):** input and output records, task counts (Hadoop counters; Spark summaries), duration (run record, step times), shuffle (Spark event log totals, else `missing`), failures, estimated cost (instance-hours times COSTS.md rates, `estimate`), flat capacity baseline. `p15-a-hadoop` stands alone (Phase 14 D6 attempt 4 is an earlier, unmanaged reference); never a total pipeline duration across A and B.
- **B (`p15-b-spark-burst`):** requested, provisioned (running) and released capacity over time (`CoreNodesRequested`, `CoreNodesRunning`, `TotalNodesRequested`, `TotalNodesRunning`; the Units series are not published for instance-group clusters and are not queried, Phase 16 J-03); classification `scale-out and scale-in while running`, `scale-out observed; no scale-in while running` or `no scale-out observed` (decision 11.6); released capacity at cluster end is a **lifecycle record, not a measured zero** and **not a scale-in event**. Compared only with `p15-a-spark` (partitions 32 vs 128, maximum 2 vs 4 core units, same inputs and code). B is observational and is never re-run because it did not scale.
- **C:** the three results kept apart (decision 13 D12): (1) spec gate, at least one worker scale-out and one scale-in shown by the service samples **and** the scaling activities (an alarm in `ALARM` alone is not a scaling event); (2) extended objective 0 to 1 to 2 to 0 (`reached` or `extended objective not reached`); (3) fully observed: queue depth, throughput, retries, drain time, embedding DLQ against its baseline (not 0 after F), counters (U-11: `processed`, `replayed`; concurrent duplicates are not observable). Re-embed write rate is the re-embed path only, not a first ingest. Handler permanent failures are workload failures (bad selected documents), reported apart from worker failures.
- **D (`p15-d-query-load`, `p15-d-read-load`):** per level: attempted requests (the denominator), completed per second, counts per class, error rate, p50, p95, p99 over all attempted requests and over the success population (`answered`, `ok`), `few samples` under 20, `n/a` under 1; task counts per service (samples), CPU and memory (CloudWatch), ALB latency, DynamoDB `ThrottledRequests`, the explain stage latencies (planner, graph stages, semantic, synthesis, verification) and the items they do not cover (`explain-coverage.csv`: retries, Neptune latency separate from the graph stage, Voyage latency separate from the S3 Vectors query: `missing`). 60 s periods across a level boundary or a pause are marked and not attributed. Throttling, overload and timeout answers under the deployed limits are results, not defects (decision 3); scale-out is not expected.
- **E:** the evaluation universe (questions with at least one labelled target researcher), `not-comparable` count, the same questions and K values for every mode, recall@K and MRR (K = 1, 5, 10, 30), nDCG only where graded labels exist, latency, errors, empty results, abstentions, `by-design-weak`; under 15 questions every table says **exploratory**; the fused mode says **"experiment only; the production workflow has no fused ranking"**; no OpenSearch tier exists.
- **F:** poison message to the DLQ (count baseline + 1, the `taskId`), sequential replay (`processed` then `replayed` from the worker log or the completion record, else `unverified`; queue counts alone never prove a replay), API idempotency (replay with `Idempotent-Replayed`, 409 on a changed body), recovery (valid task processed while the poison message was retried, in-flight back to 0, the DLQ alarm in `ALARM` as the detection source), and the incident record `incident-F.json` with the spec's incident fields.

## 3. Spec scaling-rule table (each row with its evidence or `missing`)

| Component | Required rule or policy | This project's rule (file:line) | Required evidence | Evidence found (file) | Status |
|---|---|---|---|---|---|
| EMR | Managed scaling or justified fleet policy with bounded minimum and maximum | A: no managed-scaling policy (`cluster.managedScalingMaxUnits` 2 = the minimum 2, so the request carries no `ManagedScalingPolicy`, repair L-01; a fixed 1 primary + 2 core cluster, flat capacity, `managedScalingComputeLimits: null` in the ab-pipeline record; FILL); B: managed scaling, min 2, max 4 core units through `cluster.managedScalingMaxUnits` (FILL) | Node or capacity units over time, pending work, duration | A, B: `capacity.csv`, `attempts.csv`, `capacity-*.svg` | FILL |
| Embedding workers | ECS target tracking or step scaling on SQS backlog per task | step scaling from 0 on the embedding and job queues, maximum 2 (FILL) | Queue depth, desired and running tasks, drain time | C: `observed.csv`, `samples.csv`, `queue-*.svg` | FILL |
| Graph retriever | ECS target tracking on CPU or request count with latency alarm | CPU target tracking 60 %, 1 to 2 tasks (FILL) | Task count, CPU, p95 latency | D: `tasks.csv`, `cloudwatch-periods.csv`, `levels.csv` | FILL |
| Vector retriever | ECS target tracking on CPU or request count | served inside the query service (semantic stage); CPU target tracking (FILL) | Task count, query latency, errors | D: `explain-stages.csv` (semantic), `tasks.csv`; E: `modes.csv` | FILL |
| Rank and synthesis services | Queue depth, CPU, or request-based scaling with bounded maximum | query service CPU target tracking, maximum 2 (FILL) | Task count, queue, p95 latency, LLM latency | D: `levels.csv`, `explain-stages.csv` (synthesis), `tasks.csv` | FILL |
| DynamoDB | On-demand or autoscaled provisioned capacity | on demand (FILL) | Consumed capacity and throttles | D: `cloudwatch-periods.csv` (`ConsumedReadCapacityUnits`, `ThrottledRequests`) | FILL |
| Step Functions and SQS | Retry and dead-letter policy rather than manual polling loops | SQS redrive `maxReceiveCount` 5 (FILL); state machine retries (FILL) | Execution state, retries, dead-letter count | F: `parts.csv`, `timeline.csv`; A, B: execution states | FILL |

## 4. Spec gate (the D15 and D29 rule: judged on observations with evidence files, never on exit codes)

| Gate item | Status (`met (evidence)`, `pending (run)`, `not met`) | Evidence |
|---|---|---|
| At least one scale-out and one scale-in event are shown | FILL (C alone suffices; B only with a managed scale-in before termination) | FILL |
| Metrics use synchronized timestamps | FILL (decision 10 claim and the measured offset) | FILL |
| Raw experiment data is preserved | FILL (`experiments/raw/**` committed, commit FILL) | FILL |
| Costs and limits are stated | FILL (manifest cost lines and limits, running estimate, Billing numbers) | FILL |
| Failures are reported, not edited out of existence | FILL (section 1, every attempt; incident record) | FILL |

## 5. Runs that overlapped

| Run | Window (UTC) | Overlapped with | Shared resources | Effect on attribution |
|---|---|---|---|---|
| FILL | FILL | FILL | FILL (none expected: EMR uses no ECS service, queue or Neptune; both use the NAT gateway) | FILL |

## 6. Cost

| Provider | Phase 15 measured (Billing, with reading time) | Not yet billed (`estimate`, basis) | Per experiment (`estimate`) |
|---|---|---|---|
| AWS | FILL | FILL | FILL |
| Anthropic | FILL | FILL | FILL (D-query: tokens per query from Phase 10 attempt-06, `estimate`) |
| Voyage | FILL | FILL | FILL |

Phase 15 bound: $9 across all providers; project ceiling $25. Items moved out of "not yet billed" once Billing caught up: FILL.

## 7. Limitations

Known in advance (each to be confirmed, corrected or extended with what the runs showed):

- **Planned deviations (decision 15):** (1) the human, not experiment-engineer, executed every experiment through `scripts/run-experiment.sh`; (2) the human's admin profile ran every script; (3) A's Spark half reads Phase 14's J09 export and ER output (build commit `f159535e`) while it runs at the session 2 build commit, so A's two halves are not one chained run; (4) E's fused mode is experiment-only (`fusion-exp-v1`); (5) D runs under the deployed limits (API throttle 5 per second burst 10, 4 concurrent queries per task, 19 LLM requests per minute per task) at levels 1, 2, 4, 8 and 1, 5, 10, 20, not 1, 10, 50, 100; (6) the cleanup ran right after the experiments and the demo recording; (7) one runtime-off plan with cleanup mode on; (11) every EMR run was attended in the evening.
- **A and B rules:** results compare only like with like (`p15-a-spark` with `p15-b-spark-burst`; `p15-a-hadoop` alone); B is observational and a flat capacity series is a valid result reported as "no scale-out observed"; released capacity at cluster end is a lifecycle record.
- **Scaling in D:** the query, evidence and graph services scale 1 to 2 tasks on 60 % average CPU; under the deployed limits throttling and overload come before CPU, so no CPU scale-out is expected.
- **Task counts:** Container Insights is off; task counts come from `describe-services` samples every 15 s and the scaling activities, so a task that lived under 15 s may be unseen.
- **Clock:** the HTTP `Date` header has 1 s resolution and macOS `date` has no sub-second field; CloudWatch data are 60 s periods and can arrive minutes late.
- **Explain stages:** the stored stage latencies do not separate Neptune from DynamoDB reads in the graph stages, nor Voyage from S3 Vectors in the semantic stage, and carry no per-query HTTP retry count.
- **E universe:** researcher IDs only; questions labelled only with works, paths or numbers are `not-comparable`; under 15 questions the result is exploratory.
- **F:** overlapping duplicate deliveries are a unit-test matter (decision 6.5), not observed live; concurrent duplicates are not observable.
- **C:** the re-embed write rate is the re-embed path only; G-27's first-pass speed is an estimate, never measured in Phase 15.
- **No Neptune CPU alarm** (Phase 14 decision 8): Neptune load in D is read from its CloudWatch series only.
- **Fixtures:** `experiments/derived/fixtures/**` are hand-written test data for the derive scripts, never results.
- FILL: limitations the runs showed (missing series, partial exports, re-exports used, overlapping runs).
