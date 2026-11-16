# Phase 10 work order: REST microservices

All evidence for this phase goes in docs/agent-runs/phase-10/.

## Standing rules (same as Phase 9, with the exceptions marked NEW)

- **Routing:** hub and spoke; agents never call each other. All handoffs go through the orchestrator. If a finding is still open after its repair round, stop and ask me. A BLOCKED-ON goes to the named owner, then the blocked agent reruns in a fresh context. For an unowned path, stop with BLOCKED-ON factory-engineer. An agent never edits another owner's file, even to fix an obvious mistake; it reports it.
- **Tests (NEW wording):** while working, run only targeted tests, `sbt -batch "<project>/testOnly <suites>"` for the suites you add or change and their direct users. At the end of each pass (the first pass or a repair pass), run `sbt check` once (about 15 minutes); rerun it only if it failed and you changed source to fix it. Never repeat a passing `sbt check` without a source change. (CLAUDE.md's definition of done and the `verify-tests-ran.sh` stop hook both require a passing `sbt check` after a source change, so this cannot be reduced further in this phase.)
- **One sbt at a time (NEW, strict):** only one sbt process runs at any moment. The orchestrator never runs two source-changing agents at the same time. Read-only agents (code-reviewer) may run alongside one source-changing agent, because they run no commands.
- **Commands** (I may be asleep; an approval prompt stalls the run):
  - Use only sbt, read-only git, and read-only shell commands (ls, cat, head, tail, wc, grep, du, df, shasum, sort, uniq, cut, `sed -n`, and find without -delete or -exec). No python, java, curl, installs, docker, aws or terraform.
  - Keep these words off command lines: rm, delete, drop, truncate, destroy, apply, force, reset, printenv, secret, credential, curl. (Test source files may contain such words as data; command lines may not.)
  - Edit files only with the Write and Edit tools, never `sed -i` (Phase 9 slip a-3).
  - Run long jobs with the Bash tool's background option. Read input data only by passing paths to sbt.
  - If something would need my approval, don't retry variants: skip it if it's optional, or finish with BLOCKED-ON.
- **No AWS, no external network.** No live call to AWS, Voyage, Anthropic or Bedrock. No new library (decision 2). Tests may open HTTP connections to a server they start themselves on `127.0.0.1` with port 0; nothing else.
- **Live tests:** every test that needs live AWS or a live external API is tagged `RequiresAws`. Write and compile them; never run them. Never run `sbt awsTest` or `--llm anthropic`.
- **Scope:** build only what this work order lists. Anything else goes on a "proposed later" list in the report. Run reporting is plain counts and timings in JSON.
- **Repair limits (speed):**
  - Only blocking or major findings get a repair round. Minor and advisory findings are listed for me, never repaired automatically.
  - NEW: all blocking and major findings of one owner are repaired in one pass by that owner, with one `sbt check` at the end of the pass. Owners repair one after another (one sbt at a time).
  - After a repair, the owner adds a test that fails without the fix.
  - code-reviewer's re-check covers only the repaired findings. New minor findings are recorded, not repaired.
- **Secrets:** no API key, token, account ID or bucket name in code, config files, tests, logs, responses or reports. Code reads a key only from an environment variable whose NAME comes from configuration. Tests use fakes and placeholder values.
- **Model text:** model output that fails validation is stored only as sha256, length and error. No prompt asks for reasoning; no reasoning text is stored.
- **Interface handoff (NEW):** every source-changing agent ends its report with a section `INTERFACE HANDOFF`: files changed; public types and method signatures created or changed; test fixtures others can use (class and package); thread-safety of each public object; known limitations. The orchestrator pastes the relevant handoffs into later work orders. Later agents start from them and read source only where they need detail.
- **Evidence:** write nothing under docs/agent-runs/ until all the work is done. Then:
  - print the summary first;
  - write each report verbatim into your session scratchpad directory (the one Claude Code provides under `/private/tmp/claude-501/`; not docs/agent-runs/, where writes are blocked) and print that directory's absolute path;
  - run `shasum -a 256` on each file and print one `cp -n <scratchpad file> docs/agent-runs/phase-10/<name>` command per file for me, each on one line, with its sha256. You run only `shasum`; I run the `cp` commands. The scratchpad lives until I copy the files or reboot.

  File names: agent-report-build.md, agent-report-domain.md, agent-report-storage.md, agent-report-rag.md, agent-report-service.md, review-report.md (upstream review), review-report-02.md (services review), repair-NN.md, review-report-03.md (re-check). Do not commit.

## Human architect decisions

### 1. Environment and what "done" means

- Everything runs on this laptop with storage's in-memory stores. No AWS resources exist yet (Terraform is Phase 12). Do not create any cloud resource by any means.
- The AWS wiring (`--stores aws`) is compiled and unit-tested with stub SDK clients only (no network); a live smoke is `RequiresAws`.
- Service tests use small fixtures and the fake LLM. Nobody starts a real-data server in this phase.
- After the reviews approve, I run the live benchmark once myself (decision 5e). The agents make that command work; they never run it.

### 2. HTTP stack: the JDK's built-in server

- Use `com.sun.net.httpserver.HttpServer` (module `jdk.httpserver`, part of Java 17) with bounded thread pools. No new dependency.
- JSON stays uPickle. Logging is slf4j with the existing logback Runtime dependency.
- Do not add any library. If one seems necessary, finish with BLOCKED-ON build-engineer and say why.

### 3. Agents, order and the critical path

| Step | Who | What | Runs sbt |
|---|---|---|---|
| 1 | build-engineer | decision 4 | yes |
| 2 | domain-engineer | decision 5a | yes |
| 3 | storage-engineer | decision 6 | yes |
| 4 | rag-engineer | decisions 5b–5e, then the fake benchmark | yes |
| 5 | orchestrator | interface gate (below); no agent | no |
| 6a | service-engineer | decision 7 | yes |
| 6b | code-reviewer, **at the same time as 6a** | upstream review of steps 1–4 (decision 8a) → review-report.md | no |
| 7 | code-reviewer | services review (decision 8b) → review-report-02.md | no |
| 8 | owners, one after another | one repair pass per owner with findings, in the order build, domain, storage, rag, services | yes |
| 9 | code-reviewer | re-check of repaired findings only → review-report-03.md | no |

- Steps 1–4 are sequential because each must end with its own `sbt check` and only one sbt may run at a time; their code does not depend on each other, so their order only matters for the handoffs.
- **Interface gate (step 5):** before starting service-engineer, the orchestrator checks that the handoffs contain: `RuntimeService` (5d), `ServiceStores`, `JobStore`, `IdempotencyStore`, the job `WorkQueue` (6), the `service` config section (5a), and the existing `RuntimeStores`, `EvidenceStore`, `QueryAuditWriter` and graph templates as used. Anything missing goes back to its owner as BLOCKED-ON; service-engineer never builds a replacement.
- Upstream findings from 6b are repaired in step 8, after service-engineer finishes (never during 6a). If a repair changes an interface services uses, service-engineer adapts in its own repair pass.
- **No test-engineer pass.** service-engineer freezes the request and response examples in its own module tests (decision 7j). Cross-module adversarial API tests are Phase 11.

### 4. Build (build-engineer)

One change only: `services` gets test access to the storage and rag test fixtures, `dependsOn(coreScala3, rag % "compile->compile;test->test", storage % "compile->compile;test->test")`. Run `sbt check`. Report the exact diff of build.sbt. (The assembly main class waits until the Phase 13 Docker work.)

### 5. Core and rag

#### 5a. Core (domain-engineer): one new optional config section, `service`

Same rules as the Phase 7 and 9 sections: absent unless a key is set, then every required key is set, no defaults. Nothing else in core changes. Targeted tests: the config loader and wire suites in core and core2_13.

| Key | Rule |
|---|---|
| `port` | 1..65535 |
| `apiKeyEnvVar` | env var NAME, same rules as `llm.apiKeyEnvVar`; the full-access key |
| `readOnlyApiKeyEnvVar` | env var NAME, same rules, different from `apiKeyEnvVar`; the read-only key |
| `maxRequestBytes` | 1024..1048576 |
| `syncDeadlineMillis` | 1..120000 |
| `maxConcurrentQueries` | 1..64 |
| `workerConcurrency` | 1..64 |
| `jobMaxAttempts` | 1..5 |
| `jobLeaseMillis` | 10000..900000, and > `syncDeadlineMillis` |
| `jobTtlSeconds` | 3600..604800, and `jobTtlSeconds × 1000 > jobLeaseMillis` (also the idempotency record lifetime) |
| `circuitFailureThreshold` | 1..100 |
| `circuitOpenMillis` | 1000..600000 |
| `requestsPerMinute` | > 0 (per process, all authenticated routes) |
| `jobQueueUrl` (optional) | `https://` URL; needed only by `--stores aws` (checked by services, not core) |

The loader stores only the variable names, never their values (test it). Frozen config example, validation tests (including both cross-key rules), `sbt check`.

#### 5b. R-5 and synthesis fallbacks (rag-engineer)

- **R-5:** per task (planner, synthesis), count logical calls (one call = one `complete` invocation including its client retries) and HTTP attempts: logical calls attempted, succeeded and failed (after retries) by cause (`http-<status>`, `timeout`, `transport`, `malformed-response`); HTTP attempts and retried attempts; outputs that failed validation; truncated outputs; stop reasons; fallbacks (`call-failed` vs `output-invalid`); input and output tokens. `LlmUsage` holds them in `AtomicLong`s (services run queries on several threads). `run-report.json` gets a `modelCalls` section.
  - A live run in which **any logical call failed after its retries** ends `failed: <n> model calls failed (<causes>)` and exits non-zero. A retried attempt that later succeeded is counted under retries and does not fail the run. Invalid-output fallbacks are counted, not a failure status.
  - Early stop: when the first 3 logical calls of a live run all fail with the same non-retryable 4xx (not 429), stop at once with `stopped: first 3 model calls rejected with HTTP <status>`, exit non-zero.
- **Stop reasons:** read Anthropic's `stop_reason` (Bedrock returns the same body) and record it on every attempt (`ModelAttempt.stopReason`; a missing field is `unknown`). An invalid output with `stop_reason = max_tokens` gets the error `truncated at max_tokens`.
- **Smaller output:** Phase 9's 44 rejected synthesis outputs averaged about 1,970 tokens against a 2,048 cap, so they were most likely cut off. New `prompts/runtime/answer-synthesis-v2.md` and `prompts/schemas/answer-claims-v2.schema.json`: at most 15 claims, at most 10 numbers per claim, claim text at most 300 characters, "numbers" lists only numbers that appear in the text. Also accept one JSON object surrounded only by whitespace (fences are already handled). v1 files stay unchanged.
- **Configs:** new `rag-runtime-anthropic-v2.json` (as v1 with the v2 prompts, `maxOutputTokens` 4096 and `requestsPerMinute` 19; 19 × 4,096 = 77,824 output tokens per minute, under this account's 80,000) and `rag-runtime-local-v2.json` (fake, v2 prompts). The v1 configs stay unchanged as the Phase 9 record.

#### 5c. Other carry-ins (rag-engineer)

- **q26:** new `prompts/runtime/query-planner-v2.md`. A venue name that describes what researchers work on or what their profiles mention ("working on WSDM", "profiles mention SIGIR") is a topic. Only "published in/at <venue>", "papers in <venue>" and similar are venue filters. The benchmark stays frozen (sha256 b58c5e1a…3ac).
- **m-1:** an unbound non-year number that matches no value of the cited graph items is `conflicting`. `verified` needs at least one passed check; a claim with nothing checkable is `partially_supported` with the reason `nothing checkable`.
- **m-2:** a dblp homonym suffix that matches neither `matchedValue` nor `displayName` of the sole candidate makes the name unknown.
- **m-4:** `QueryRequest.fingerprint` also hashes the graph run ID; `QueryWorkflow.CodeVersion` becomes `rag-runtime/2`. A non-empty `itemConflicts` or a bundle `Conflict` from `putBundle` becomes an answer note and a `WorkflowResult` field.
- **review-report-03 m-2:** fix the outdated "verify in the live benchmark run" comment in Llm.scala.
- **QueryRequest:** add `topic: Option[String]` (at most 200 characters, part of the fingerprint, overrides the draft's topic).
- **Stage listener:** `QueryWorkflow.run` takes an optional `onStage: String => Unit`, called with the stage name as each stage starts. A listener exception never breaks the query.

#### 5d. Runtime entry point for services (rag-engineer)

Services must not repeat rag's wiring. Add `org.scholarchain.rag.runtime.RuntimeService`:
- `local(config, exportDir, neptuneCsvDir, options)`: what BenchmarkRunner does today before the questions (LocalRuntimeEnvironment, ingest of the profile, community and path documents with the `fake` embedder, prompts, model, one `QueryWorkflow`). BenchmarkRunner uses it.
- `aws(config, clientSettings)`: `AwsStorageEnvironment` stores, the `voyage` embedder, prompts, model. Refuse fake LLM or fake embedder with AWS stores, and live embedders with local stores. Building it makes no network call.
- `fromStores(config, stores, retriever, model, ...)`: for tests over fixture stores.
- The handle exposes the workflow, the `RuntimeStores`, the `AwsStorageEnvironment` or `LocalRuntimeEnvironment` it was built from, the graph run ID, `queryRunId(request)` and `close()` (which closes every client and store it opened, idempotently). The scaladoc says which objects are safe to call from several threads; callers lock around anything else.

Tests for each change in 5b–5d that fail without it (targeted: the rag runtime suites you touch). After `sbt check`, run `BenchmarkRunner --llm fake` once with `rag-runtime-local-v2.json` under `modules/rag/target/phase-10-real/attempt-01-fake/` (`sbt -batch -J-Xmx12g`, about 4 minutes). Targets: graph values 110/110, paths 4/4, expected abstentions 3/3 and no unexpected ones, planted errors all caught, injection passes. The verified-claim count may change because of m-1; report it next to Phase 9's 175 together with the other status counts. Report any miss as a finding; don't tune.

#### 5e. Live re-run (me, after the reviews approve)

Print the exact one-line command: `rag-runtime-anthropic-v2.json`, `--out modules/rag/target/phase-10-real/attempt-06-anthropic`. Phase 9's live run used 172,980 input and 104,623 output tokens (about $0.07).

### 6. Storage (storage-engineer): jobs, idempotency and the job queue

Storage owns the item formats; services never builds a key. Add two access patterns to the existing QuerySession table (it has TTL on `expiresAt`) and to TableSpecs. Storage reads no clock: every time (`now`, `createdAt`, `leaseUntil`, `expiresAt`) is passed in by the caller.

- **QS4 job:** one item per job: jobId, mode (`sync` or `async`), state (`queued`, `running`, `succeeded`, `failed`), stage, createdAt, updatedAt, attempts, leaseUntil, queryRunId, the canonical request (question, window label, topic, includeEvidence; the worker runs from it), traceId, error code and message, the result, `version`, `expiresAt`.
  - Create is put-if-absent. Every change is put-if-version on the version the caller read; a version mismatch returns `ConditionFailed`, never an exception.
  - Allowed transitions only: queued→running (claim), running→running (reclaim, allowed only when the stored `leaseUntil` is before the caller's `now`), running→queued (a released attempt), running→succeeded (the result is written in the same conditional put), running→failed, queued→failed. Every claim and reclaim increments `attempts` and sets `leaseUntil`. Anything else is refused.
  - The result is a string (the encoded completed result, decision 7c, without evidence items). A result over 300,000 bytes is refused with a typed `ResultTooLarge` outcome (DynamoDB items are limited to 400 KB).
- **QS5 idempotency:** keyed by the sha256 of (caller scope, Idempotency-Key); holds the request fingerprint (decision 7c), the jobId and `expiresAt`. Claim is put-if-absent; a losing claim returns the existing record. The record holds no state and no result: both live only on the job.
- **Job queue:** a `WorkQueue` for jobs. Local: a new in-memory queue with a visibility timeout (a received item is hidden for `visibilityMillis` and returns if not acked; `release` returns it at once; receive counts tracked), driven by an injected clock so tests are deterministic. AWS: the existing `SqsWorkQueue` (queue URL as a parameter; its visibility timeout is set on the queue by Terraform in Phase 12 and must exceed `jobLeaseMillis`).
- Bundle them as `ServiceStores` with `ServiceStores.local(env: LocalRuntimeEnvironment, visibilityMillis)` and `ServiceStores.aws(env: AwsStorageEnvironment, queueUrl)` (no network call). Do not change the `RuntimeStores` trait.
- Tests (targeted: the new suites plus DynamoSuite): every allowed and refused transition; two claims of the same version (exactly one wins); reclaim refused while the lease is live and allowed after it; the result written atomically with `succeeded`; `ResultTooLarge`; a second idempotency claim returns the first record; queue receive, hide, return after the visibility timeout, ack, release. Run `sbt check`.

**Handoff:** the access patterns, every signature services will use, and one usage example.

### 7. Services (service-engineer): the spec Phase 10 work order

Package `org.scholarchain.services`. Services reach rag and storage only through `RuntimeService` (5d), `RuntimeStores`, `ServiceStores` and storage's typed graph templates. Recommended build order inside this step: models, errors and the HTTP adapter first; then the read routes; then query, jobs and idempotency; then the fixtures and golden tests. Targeted tests while working; one `sbt check` at the end.

a. **Processes:** one entry point `org.scholarchain.services.Main --config FILE --role ROLE --stores local|aws [--export DIR --neptune-csv DIR]`.

   | Role | Routes |
   |---|---|
   | `query` | POST /v1/query, GET /v1/jobs/{jobId}, GET /v1/jobs/{jobId}/result |
   | `worker` | runs queued jobs |
   | `evidence` | GET /v1/evidence/{evidenceId}, GET /v1/explain/{queryId} |
   | `graph` | GET /v1/graph/nodes/{nodeId}, GET /v1/graph/nodes/{nodeId}/neighbors |
   | `all` | every route plus the worker (local runs and tests) |

   Every role serves GET /healthz and /readyz. A route of another role returns 404. With `--stores local` only `all` is allowed (the in-memory stores and queue live in one process); the other roles need `--stores aws`.

b. **Thin routes:** HTTP adapter (parse, auth, limits, headers) → route table → application services (`QueryService`, `JobService`, `Worker`, `EvidenceService`, `GraphService`, `ExplainService`, `HealthService`) → rag and storage. Route and adapter files import no storage adapter package (`aws`, `dynamo`, `neptune`, `local`) and no rag stage. The server takes an ID generator and a clock as parameters so tests can fix job, request and trace IDs, leases and timestamps.

c. **POST /v1/query** (JSON; unknown fields → 400):
   - `question`: required, 1..2000 characters after `QueryRequest.canonicalQuestion`.
   - `constraints` (optional): `yearFrom` and `yearTo` (both or neither; must equal one configured window exactly, else 422 `UNSUPPORTED_CONSTRAINT` listing the windows); `topics` (0 or 1 entry, at most 200 characters, else 422); `venues` and `institutions` must be empty, else 422 `UNSUPPORTED_CONSTRAINT` (no venue metrics or institution data yet).
   - `mode`: `sync` (default) or `async`.
   - The spec's request objects are accepted so its example request shape is valid: `output.includeEvidence` (default true) is applied; `output.includeScores` and `output.includeUncertainty` must be true when present (scores and uncertainty are always returned); `retrieval.graphLimit`, `retrieval.vectorLimit` and `retrieval.maxPathDepth`, when present, must be within `runtime.graphResultLimit`, `vectors.queryTopK` and `runtime.maxPathDepth` (else 422 `UNSUPPORTED_CONSTRAINT`); `analysis.*` fields are validated by type (`rankingMetric`, when present, must be a ranked metric wire name) and are not applied in this phase, because the plan comes from the question. The response notes list every accepted but unapplied field. Unknown fields anywhere → 400.
   - **Mapping:** the matching window's label → `QueryRequest.windowLabel`; the one topic → `QueryRequest.topic`. No other request field reaches rag. The trace ID is not part of `QueryRequest` (rag's query run ID must not depend on it).
   - **Request fingerprint:** sha256 of the canonical JSON of the whole validated request after defaults are applied (question in canonical form, windowLabel, topic, mode, `output`, `retrieval` and `analysis` values), with keys sorted.
   - **Completed result** (the one representation of a finished query): `{queryId, answer, links: {explain, evidence: [/v1/evidence/{id}?window=<start>-<end> per cited item]}}`. `queryId` is the query run ID. `answer` is a services view of the core AnswerResponse v2 (built from a contract-valid one): every field except `bundle`, plus `bundleId`, plus `evidence` (the bundle's items) when `includeEvidence` is true. A core AnswerResponse may omit its bundle only when it abstains, so the view is a services record. Stored results hold the view without `evidence`; it is read back with `EvidenceStore.getBundle` when returned.
   - Sync → 200 with the completed result. Async → 202 acknowledgment `{queryId, jobId, status: "QUEUED", statusUrl}`; the completed result later comes from GET /v1/jobs/{jobId}/result and is identical to the sync result for the same request (the test compares them exactly).
   - Job IDs are `job-` plus a UUID from the ID generator (operational IDs, not stable IDs).

d. **Execution capacity and sync timeouts:**
   - Sync queries run on a fixed pool of `maxConcurrentQueries` threads with no waiting queue. When every thread is busy, the request gets 503 `OVERLOADED` (retryable, Retry-After) at once.
   - A sync request that passes `syncDeadlineMillis` gets 504 `WORKFLOW_TIMEOUT`. The workflow is not interrupted; it keeps its thread (its permit) until it finishes, so timed-out work is still counted against the limit and can never pile up.
   - When the request carried an Idempotency-Key, the finishing workflow writes its result to the job as usual, so a retry with the same key gets it (decision 7e). Without a key, a finished result after a timeout is discarded (its audit is still written by rag).
   - Workers run on a separate fixed pool of `workerConcurrency` threads.

e. **Idempotency** (`Idempotency-Key` header, optional, 1..128 characters `[A-Za-z0-9._:-]`; caller scope = the caller class, decision 7i). It never runs a second workflow for the same key and fingerprint while the first job's lease is live, and never returns a different result for it. It does not promise exactly-once execution (see 7f).
   - **New key, sync:** create the job (mode `sync`, then claim it to `running` with a lease of `jobLeaseMillis`), then claim the key with that jobId, then run.
   - **New key, async:** create the job (`queued`), enqueue it, then claim the key. (Enqueue before the claim, so a crash can never leave a claimed key pointing at a job no worker will see.)
   - **Lost claim (a concurrent request with the same key won):** mark this request's own job `failed` with `SUPERSEDED` (if it is still `queued`; a job a worker already claimed may run; harmless) and follow the existing record.
   - **Existing record, different fingerprint:** 409 `IDEMPOTENCY_CONFLICT` (not retryable).
   - **Existing record, same fingerprint**, by the job's state:
     - `succeeded` → the stored completed result: 200 with header `Idempotent-Replayed: true` (sync), or the 202 acknowledgment again with the job's current status (async);
     - `queued`, or `running` with a live lease → 409 `IDEMPOTENCY_IN_PROGRESS` (retryable, Retry-After);
     - `failed` → 409 `JOB_FAILED` with the job's error code in `details` (not retryable with this key; the client retries with a new key);
     - a `sync` job `running` with an expired lease (its process died) → mark it `failed` with `LEASE_EXPIRED`, then answer as `failed`. (`async` jobs with an expired lease are recovered by the queue, 7f.)
   - Records and jobs expire with `jobTtlSeconds` (always longer than the lease); after expiry the same key starts a new job.

f. **Worker (async jobs; at-least-once queue, at most one live execution per job):**
   - Receive one message, read the job, then:
     - `succeeded` or `failed` → ack (a duplicate or late delivery);
     - `running` with a live lease → neither ack nor release; the queue's visibility timeout (longer than the lease) brings the message back later;
     - `queued` → claim it; `running` with an expired lease → reclaim it. If the claim or reclaim returns `ConditionFailed` (another worker won), do nothing with the message (as above).
     - If `attempts` has already reached `jobMaxAttempts`, mark the job `failed` with `ATTEMPTS_EXHAUSTED` and ack instead of claiming.
   - Run the workflow with `onStage` updating the job's stage (a stage write that fails is logged and ignored).
   - **Trace propagation:** the worker logs with the trace ID stored on the job, so one trace ID follows a query from the query role through the queue to the worker. For sync and async runs alike, `onStage` also writes one log line per workflow stage with the traceId, jobId (when any) and queryId.
   - On success: write `succeeded` with the result in one conditional put, **then** ack. A result over the limit → `failed` with `RESULT_TOO_LARGE` (not retryable), then ack. If the final write returns `ConditionFailed` (the lease expired and another worker reclaimed the job), discard this result and do nothing with the message.
   - On a workflow failure: a store or model failure is retryable: `running→queued`, then release (when `attempts < jobMaxAttempts`), else `failed` with `ATTEMPTS_EXHAUSTED` and ack. A non-retryable failure (a request rag rejects) → `failed` and ack.
   - A crash at any point leaves the message unacked; it returns after the visibility timeout and the rules above recover the job. Two executions of one job can happen only after a lease expires; both produce the same query run ID, the bundle write is put-if-absent and the audit is appended twice. This is accepted and documented.
   - GET /v1/jobs/{id}: `{jobId, queryId, mode, state (QUEUED|RUNNING|SUCCEEDED|FAILED), stage, createdAt, updatedAt, attempts, resultUrl, error}`. GET /v1/jobs/{id}/result: 200 with the completed result when succeeded; 409 `JOB_NOT_READY` (retryable) when queued or running; 409 `JOB_FAILED` with the error when failed; 404 when unknown.
   - A sync job with a key that fails with `RESULT_TOO_LARGE` returns 500 `RESULT_TOO_LARGE` (not retryable) to the caller and records `failed`.

g. **Evidence and explain.** **GET /v1/evidence/{evidenceId}?window=<start>-<end>:** `EvidenceStore.get`. Without `window`: the item when exactly one window is stored (`EvidenceStore.windows`), else 400 `WINDOW_REQUIRED` listing them. Response: the core EvidenceItem plus `sourceUris`: `https://dblp.org/rec/<key>` for each `provenance.sourceRecordIds` entry shaped like a dblp key, else `scholarchain:<recordRef>`. Unknown → 404 `EVIDENCE_NOT_FOUND`.

   **GET /v1/explain/{queryId}:** the latest `query-audit` record (`QueryAuditWriter.read`) plus `attemptCount`: plan, plan source, prompt versions, model ID, evidence IDs, stage latencies (including the `plannerFallback`/`synthesisFallback` keys), token counts and verification counts. `scoreComponents` is `[]` with the note "rankings use one stored metric; no composite score is computed". Never prompt text, model output or reasoning. Unknown → 404 `QUERY_NOT_FOUND`.

h. **Graph exploration:**
   - GET /v1/graph/nodes/{nodeId}: researcher ID → `ResearcherById`; work ID → `WorkById`; another stable ID kind → 422 `UNSUPPORTED_NODE_KIND`; malformed → 400; not found → 404.
   - GET .../neighbors with `direction=in|out|both` (default both), `depth=1..3` (default 1), `limit=1..runtime.graphResultLimit` (default `min(20, runtime.graphResultLimit)`), `edgeTypes` (default `COAUTHORED_WITH` for a researcher, `AUTHORED` for a work), `window` (configured label, default `runtime.defaultWindowLabel`), `cursor`. Supported: researcher + `COAUTHORED_WITH` (`CoauthorNeighbors`), work + `AUTHORED` in/both (`WorkAuthors`). Depth 2 or 3 → 422 `UNSUPPORTED_DEPTH`. `HAS_TOPIC`, `AFFILIATED_WITH`, `PUBLISHED_IN`, and `AUTHORED` out of a researcher → 422 `UNSUPPORTED_EDGE_TYPE`. Out-of-range or unknown values → 400.
   - **Paging** (no storage change): the graph data of one graph run is immutable, so offsets are stable within a graph run. The cursor is unsigned base64url JSON `{v: 1, graphRunId, nodeId, direction, edgeTypes (sorted), window, limit, offset}`. The service asks the template for `offset + limit` rows (never more than `runtime.graphResultLimit`) and returns the slice. A neighbourhood is capped at `runtime.graphResultLimit` rows; past the cap the response says `truncated: true` and gives no next cursor.
   - **Cursor validation** (the guarantee is validation, not tamper-proofing; editing an offset only asks for another valid page): 400 `INVALID_CURSOR` when the cursor is not valid base64url JSON of that exact shape, `v` is not 1, the offset is negative, not a multiple of `limit` or not below the cap, or any field differs from the request's normalized parameters or the current graph run.
   - Response `{nodeId, window, rows, truncated, nextCursor}`.

i. **HTTP behavior:**
   - **IDs:** accept `X-Request-Id` (1..64 characters `[A-Za-z0-9._-]`) or generate one; take the trace ID from a valid W3C `traceparent` or generate 32 hex. Return `X-Request-Id` and `X-Trace-Id` on every response; store the trace ID on jobs and log it with the query ID.
   - **Errors:** every non-2xx response except `/readyz` has the body `{error, message, traceId, retryable, details}`, including 404, 405, 413, 415 and 500. `message` is a fixed text per code; downstream error text never reaches a client.

     | Status | Codes |
     |---|---|
     | 400 | INVALID_JSON, INVALID_REQUEST (details lists field errors), WINDOW_REQUIRED, INVALID_CURSOR |
     | 401 | UNAUTHENTICATED |
     | 403 | FORBIDDEN |
     | 404 | ROUTE_NOT_FOUND, QUERY_NOT_FOUND, JOB_NOT_FOUND, NODE_NOT_FOUND, EVIDENCE_NOT_FOUND |
     | 405 | METHOD_NOT_ALLOWED |
     | 409 | IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS, JOB_NOT_READY, JOB_FAILED |
     | 413 | PAYLOAD_TOO_LARGE |
     | 415 | UNSUPPORTED_MEDIA_TYPE |
     | 422 | UNSUPPORTED_CONSTRAINT, UNSUPPORTED_EDGE_TYPE, UNSUPPORTED_DEPTH, UNSUPPORTED_NODE_KIND |
     | 500 | INTERNAL, RESULT_TOO_LARGE |
     | 502 | DOWNSTREAM_FAILURE |
     | 429 | RATE_LIMITED (over `service.requestsPerMinute`; Retry-After; retryable) |
     | 503 | OVERLOADED, CIRCUIT_OPEN |
     | 504 | WORKFLOW_TIMEOUT |

     Job error codes (stored on jobs, shown in `details`): `SUPERSEDED`, `LEASE_EXPIRED`, `ATTEMPTS_EXHAUSTED`, `RESULT_TOO_LARGE`, `WORKFLOW_FAILED`.
   - **Auth:** header `X-Api-Key`, compared in constant time with the values of the env vars named by `service.apiKeyEnvVar` (caller class `full`, all routes) and `service.readOnlyApiKeyEnvVar` (caller class `read-only`, GET routes only; POST → 403). Missing or wrong → 401. `/healthz` and `/readyz` are open. At startup the process refuses to start when either variable is unset, blank, or both hold the same value; the refusal message names the variable, never a value.
   - **Timeouts, retries, circuit breaker:** services add no retries (storage and the model clients already retry, bounded). Graph calls are bounded by storage (`runtime.graphTimeoutMillis`); any store Left → 502. One circuit breaker per downstream (workflow, graph, evidence/audit reads): after `circuitFailureThreshold` consecutive failures it opens for `circuitOpenMillis` (→ 503 `CIRCUIT_OPEN`), then lets one trial call through. A 4xx never counts as a failure.
   - **Health:** `/healthz` is liveness only, always 200 `{status: "ok", role}`. `/readyz` is the one exception to the error body: 200 or 503 with `{status: ready|not_ready, role, checks: {name: ok|fail}}`. It does one cheap read per store through the ports (a metric lookup and a `ResearcherById` of a fixed well-formed ID) with an overall deadline of 2 seconds (a check that passes the deadline is `fail`). At most one probe runs at a time; other requests during a probe, and requests within 5 seconds of the last probe, get the last result. Neither body holds a config value, endpoint, table, bucket, key name or error text.
   - **Logs:** one JSON line per request: time, role, traceId, requestId, method, route template (never the raw path or query string), status, latencyMillis, error code, caller class (`full`, `read-only`, `anonymous`). No bodies, no headers. Logged exception text passes a redactor that removes the configured key values, `arn:` strings, 12-digit numbers and `s3://` URIs.
   - **Shutdown:** `Main` installs a shutdown hook, and the server object has `close()` (also used by tests): stop accepting, stop the worker loop, shut down every pool waiting at most `syncDeadlineMillis`, then close the `RuntimeService` handle. All threads the services create are named `scholarchain-*`.
   - **Rate limit:** one token bucket per process for authenticated routes (`service.requestsPerMinute`); over it → 429 `RATE_LIMITED`. `/healthz` and `/readyz` are not limited.
   - `/metrics` is not built in this phase (CloudWatch metrics come with Phase 12); list it under "proposed later".
   - Fixed constants (readiness deadline 2 s and cache 5 s, default neighbour limit 20, result limit 300,000 bytes, ID and key lengths) are defined once, named, and documented with their reason in the code and the report.

j. **Examples (the gate's contract tests):** REST models with uPickle codecs in `modules/services`. Freeze these examples under `modules/services/src/test/resources/api/v1/`: sync query request, completed result, async acknowledgment, job status, evidence item, node, neighbors page, explain, readiness (not ready), and one error body each for 400, 401, 404, 409, 422 and 504. Tests: each example decodes and re-encodes to the same JSON, and each response example equals, byte for byte after the shared encoder, what the running server returns for the fixture request that produced it (fixed ID generator and clock).

k. **Tests** (module tests; fixture stores from the rag and storage test trees, fake LLM, server on `127.0.0.1` port 0). Spec-required:
   - successful synchronous query;
   - asynchronous path: queued → running → succeeded, a stage update seen, completed result identical to the sync result for the same request;
   - invalid JSON (plus unknown field, oversized body, wrong media type);
   - unsupported query constraint (venues, institutions, years that are not a window);
   - missing evidence (404) and WINDOW_REQUIRED;
   - idempotent repeated request (sync and async; the workflow runs once);
   - timeout (a stub workflow slower than the deadline → 504) and downstream failure (a store that throws → 502, the circuit opens → 503, then recovers);
   - unauthorized (no key or a wrong key → 401) and forbidden (read-only key on POST → 403);
   - health versus readiness (a failing probe → /readyz 503 while /healthz stays 200), with no secret or config value in either body;
   - **full query-to-evidence lifecycle with fakes:** POST /v1/query → a non-abstained answer with at least one `verified` claim → GET /v1/evidence for one cited item returns it → GET /v1/explain shows the same evidence IDs.

   Invariants of this design:
   - idempotency: different fingerprint → 409; same key while running → 409 in progress; replay after a 504 returns the result once the workflow finishes, and the workflow ran once; replay of a failed job → 409 `JOB_FAILED`; two concurrent first requests with one key run one workflow;
   - capacity: after a 504 the timed-out workflow still holds its permit (a request beyond `maxConcurrentQueries` gets 503 `OVERLOADED`) and frees it when it finishes;
   - worker: a duplicate delivery of a finished job is acked without running; two workers racing on one job run it once; a worker that claims and stops (clock moved past the lease) → the redelivered message reclaims and finishes it, with `attempts` 2; `attempts` at the limit → `ATTEMPTS_EXHAUSTED`; a final write that loses the version race discards its result; an oversized result → `RESULT_TOO_LARGE`;
   - restart: a new server over the same `ServiceStores` returns a finished async result;
   - cursors: paging across pages joins to the full list without gaps; malformed base64, wrong shape, a negative or unaligned offset, and a cursor from different parameters or another graph run → 400; default limit with `runtime.graphResultLimit` below 20;
   - auth startup: unset, blank or equal key values refuse start, and the message holds no value;
   - rate limit: requests over `requestsPerMinute` → 429 with Retry-After; health routes never limited;
   - request shape: the spec's example request (with a configured window) is accepted; an out-of-bounds `retrieval` value → 422; trace: one traceId appears on the query role's log line, the job, the worker's lines and every stage line;
   - readiness: a probe slower than 2 seconds → `fail`; concurrent `/readyz` calls run one probe;
   - every error body's `traceId` equals the response's `X-Trace-Id`; a route of another role → 404; log redaction;
   - shutdown: after `close()`, no `scholarchain-*` thread is left alive;
   - `--stores aws` is built from stub SDK clients with zero HTTP calls; a live smoke is `RequiresAws`.

l. **Config:** `modules/services/src/test/resources/config/services-local-v1.json`: the rag local v2 config plus a `service` section with placeholder variable names (`SCHOLARCHAIN_EXAMPLE_API_KEY`, `SCHOLARCHAIN_EXAMPLE_READONLY_API_KEY`).

m. Run `sbt check`. Print, for me, the exact one-line commands to start the `all` role on the real data later (`sbt -batch -J-Xmx12g`, the paths in decision 10) and one example request.

**Report:** the spec's report line: route table (role, method, path, auth level), status-code mapping, timeout policy, dependency graph (which service calls which port), authentication method, and exact test counts; plus the idempotency and worker rules as built.

### 8. Reviews (code-reviewer, read-only)

Review only what changed in this phase (the files the reports list, plus what they call when needed to judge them). Phase 9 code that this phase didn't touch was approved and is not re-reviewed.

**8a. Upstream review (step 6b, while service-engineer works; do not read `modules/services`)** of build, domain, storage and rag. Rule on:
- R-5 (logical-call failure exits non-zero, retried successes do not, early stop on 3 equal non-retryable 4xx, counters match stubs), stop reasons, v2 prompts and schema, q26, m-1, m-2, m-4, each with a test that fails without it; the fake benchmark targets;
- the `service` section matches decision 5a (both cross-key rules) and the loader never reads key values;
- job transitions, lease reclaim, atomic result write, `ResultTooLarge`, the idempotency claim, and the in-memory queue's visibility timeout behave as decision 6 says;
- ownership of each changed file.

**8b. Services review (step 7).** Rule on:
- each Phase 10 spec gate below, one by one;
- thin routes: no storage adapter import, no key building, no query text, no retrieval logic in route or adapter files;
- every non-2xx response except `/readyz` carries the trace ID; no downstream error text reaches a client;
- health, readiness, errors and logs hold no secret, key value, key name, config value, table, bucket, endpoint or account ID;
- auth is constant-time and fails closed; equal key values refuse start; the read-only key cannot POST;
- idempotency and worker rules (7e, 7f) as written, including the order job → enqueue → claim, ack only after the terminal write, and permits held until a workflow ends; no claim of exactly-once anywhere;
- cursor validation (7h) and the paging cap;
- every error class of the spec's error model (400, 401, 403, 404, 409, 422, 429, 502, 503, 504, 500) is produced by some path and tested;
- one trace ID follows a query across the query role, the job, the worker and every workflow stage log line;
- the golden tests compare real server output with the frozen files;
- every pool is bounded; nothing creates threads or tasks without a bound;
- ownership: `modules/services/**` only; `docker/**`, `tests/**` and constitutional files unchanged.

### 9. Service limits (captured by me on Nov 15, 2026; agents use no network)

| Item | Value | Source |
|---|---|---|
| Synthesis model | `claude-haiku-5-5`; Bedrock ID `anthropic.claude-haiku-5-5` | Anthropic model pages |
| Haiku 5.5 price | $0.10 / $0.50 per million input / output tokens (prompts up to 100,000 tokens) | Anthropic pricing |
| This account's limits | 1,000 requests, 500,000 input tokens and 80,000 output tokens per minute | Anthropic console |
| Live-verified request fields | request: model, max_tokens, system, messages; response: `content[].text`, `usage.input_tokens`, `usage.output_tokens` | my direct requests, Nov 15 (phase-09 prompt-02.md) |
| Anthropic budget | $5 prepaid, $5 monthly limit | my account |
| Bedrock | on-demand quota 0 (switch only) | my Service Quotas check |
| DynamoDB item size | 400 KB | AWS docs (StorageLimits) |
| SQS delivery | at least once; a received message is hidden for the queue's visibility timeout | AWS docs (general knowledge; the queue itself is Phase 12) |

### 10. Expected inputs (from earlier reports, not re-counted)

- Phase 7 export (read-only): `modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b/`.
- Neptune CSV (read-only): `modules/storage/target/phase-07-real/attempt-01/neptune/`.
- Phase 9 local runtime: 3.93 GB heap with one window; load 114.3 s.
- `sbt check` before this phase: 1634 module and 336 contract tests, about 13–16 minutes.

Report what you actually find. If a count differs, report it; don't force it.

## Spec work order, Phase 10 (verbatim)

```text
Use the service-engineer agent to implement ScholarChain RESTful microservices in Scala 3.

Required services:

1. Query API
- POST /v1/query
- Validate request.
- Create query ID and idempotency record.
- Start synchronous or asynchronous Graph-RAG workflow.
- Return answer or job reference.

2. Jobs API
- GET /v1/jobs/{jobId}
- GET /v1/jobs/{jobId}/result
- Report state, timestamps, stage, errors, and result link.

3. Evidence API
- GET /v1/evidence/{evidenceId}
- Return provenance, source record, source URI, graph path or text pointer, and compact supporting content.

4. Graph exploration API
- GET /v1/graph/nodes/{nodeId}
- GET /v1/graph/nodes/{nodeId}/neighbors
- Bound depth, edge types, result count, and timeout.

5. Explain and audit API
- GET /v1/explain/{queryId}
- Return QueryPlan, stages executed, prompt versions, model versions, evidence IDs, score components, counters, and verification status.
- Do not return hidden chain-of-thought.

6. Operations endpoints
- GET /healthz
- GET /readyz
- GET /metrics when the deployment model permits it

Required HTTP behavior:
- JSON request and response contracts.
- Path-based API versioning.
- Request IDs and trace IDs.
- Idempotency-Key support for POST operations.
- Pagination or cursoring for graph neighborhoods.
- Timeouts, bounded retries, and circuit breakers.
- Typed error model.
- Authentication and authorization appropriate to the course deployment.
- No secrets or raw credentials in responses or logs.
- Thin routes; application logic belongs in services and ports.

Required tests:
- Successful synchronous query.
- Asynchronous query path.
- Invalid JSON.
- Unsupported query constraint.
- Missing evidence.
- Idempotent repeated request.
- Timeout and downstream failure.
- Unauthorized request.
- Health and readiness distinction.
- Full query-to-evidence lifecycle with fakes.

Run sbt check.
Report route table, status-code mapping, timeout policy, dependency graph, authentication method, and exact test counts.
```

Phase 10 notes that apply to the verbatim text above:
- "Independently scalable": one jar, one role per process (decision 7a); ECS services and scaling are Phase 12.
- "Idempotency record": created whenever the request carries an `Idempotency-Key`; without a key a POST is not idempotent (7e).
- "Query ID": the deterministic query run ID (now including the graph run, m-4). Job IDs are operational.
- "Source URI": dblp record URLs where a dblp key is known, else a `scholarchain:` reference (7g).
- "Score components": none exist; the explain response says so (7g).
- "Bounded retries": done inside storage and the model clients; services add none (7i).
- "/metrics": not permitted yet; CloudWatch metrics come with the deployment (Phase 12).
- "JSON request and response contracts": the spec's REST section allows endpoint and field changes when the contract is internally consistent. The spec's request objects are accepted (7c); responses use the completed-result and core record shapes, documented by the frozen examples (7j).
- "Counters" in explain: stage latencies, token counts, verification counts and the fallback markers from the stored QueryAudit v1. Per-call retry counts are in rag's run report, not in the audit (a QueryAudit v2 is proposed later).
- "Pagination": validated offset cursors within `runtime.graphResultLimit`, bound to the graph run (7h).

## Spec gate (checked at my gate)

- Routes do not embed retrieval algorithms.
- Error responses include trace IDs.
- Health checks do not expose secrets.
- Request and response examples match contract tests.
- At least one end-to-end API test reaches an evidence-backed answer using bounded fixtures.
