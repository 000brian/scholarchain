# ScholarChain REST API reference

This is the route reference of the ScholarChain services, written from the code at commit `50ba087` (`modules/services/**`); section 9 (claim verification) describes the verifier after the Phase 17 code changes (commit pending). **No OpenAPI file was generated**: the route table in `modules/services/src/main/scala/org/scholarchain/services/Routes.scala` and the frozen response examples under `modules/services/src/test/resources/api/v1/` are the contract, and `GoldenExamplesSuite` checks that every frozen example equals what the running server returns for its fixture request (`modules/services/src/test/scala/org/scholarchain/services/GoldenExamplesSuite.scala:65`).

**No live system exists.** The AWS environment was removed on Nov 21 (Phase 15 gate, [`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md)). The live examples below are archived records of Phases 14 and 15; they cannot be sent again.

Contents:

- [1. Base address and the trailing slash (G-22)](#1-base-address-and-the-trailing-slash-g-22)
- [2. Roles and routing](#2-roles-and-routing)
- [3. Authentication](#3-authentication)
- [4. Common request and response rules](#4-common-request-and-response-rules)
- [5. Routes](#5-routes)
- [6. Error model](#6-error-model)
- [7. Idempotency and the 502 rule (R-1)](#7-idempotency-and-the-502-rule-r-1)
- [8. Limits and timeouts](#8-limits-and-timeouts)
- [9. Claim verification statuses and reasons](#9-claim-verification-statuses-and-reasons)
- [10. Examples and their sources](#10-examples-and-their-sources)

## 1. Base address and the trailing slash (G-22)

The deployed API was an API Gateway HTTP API (HTTPS, `$default` stage) in front of an internal load balancer. Its address came from the Terraform output `api_url` (`infra/terraform/environments/course/outputs.tf:17-20`). Documents write it as `{api-url}`.

**`api_url` ends in `/`.** A client that joins it with a path that starts with `/` gets `//v1/...`, which the load balancer answers with its fixed 404 (Phase 14, step D12: [`docs/agent-runs/phase-14/runbook-log.md`](agent-runs/phase-14/runbook-log.md), rows D12 and G-22; gate entry in [`docs/agent-runs/phase-14/human-gate.md`](agent-runs/phase-14/human-gate.md), minor findings table). Remove the trailing slash first: `scripts/smoke-test.sh:333` does `API_URL="${API_URL%/}"` before it appends any path. At Phase 14, no other script built a URL from `api_url` (G-22 row of the Phase 14 runbook log). In the current repository two more scripts do, and both strip the trailing slash the same way: the experiment collectors' `cc_api_url` (`experiments/collectors/lib/collector-common.sh:661-665`) and the load test's `read_api_url` (`load-tests/query-load.sh:495-504`).

## 2. Roles and routing

One container image runs in one of five roles (`Main --role`, `modules/services/src/main/scala/org/scholarchain/services/HttpAdapter.scala:14-24`). On AWS the four services `query`, `evidence`, `graph` and `worker` each ran one role; role `all` (every route plus the worker) is for local runs only (Phase 12 gate, area 10: [`docs/agent-runs/phase-12/human-gate.md`](agent-runs/phase-12/human-gate.md)).

| Route | Method | Role that serves it | Who may call | Source |
|---|---|---|---|---|
| `/healthz` | GET | every role | anyone (no key) | `Routes.scala:30` |
| `/readyz` | GET | every role | anyone (no key) | `Routes.scala:31` |
| `/v1/query` | POST | `query` | full key | `Routes.scala:32` |
| `/v1/jobs/{jobId}` | GET | `query` | full or read-only key | `Routes.scala:33-39` |
| `/v1/jobs/{jobId}/result` | GET | `query` | full or read-only key | `Routes.scala:40-46` |
| `/v1/evidence/{evidenceId}` | GET | `evidence` | full or read-only key | `Routes.scala:47-53` |
| `/v1/explain/{queryId}` | GET | `evidence` | full or read-only key | `Routes.scala:54-60` |
| `/v1/graph/nodes/{nodeId}` | GET | `graph` | full or read-only key | `Routes.scala:61-67` |
| `/v1/graph/nodes/{nodeId}/neighbors` | GET | `graph` | full or read-only key | `Routes.scala:68-74` |

A route of another role does not exist in a process: it answers 404 `ROUTE_NOT_FOUND` (`HttpAdapter.scala:94`, `:231-233`; test `HttpBehaviorSuite.scala:186`). The internal load balancer sent `/v1/query`, `/v1/jobs/*`, `/healthz` and `/readyz` to `query`, `/v1/evidence/*` and `/v1/explain/*` to `evidence`, and `/v1/graph/*` to `graph`, and answered anything else with a fixed 404 (Phase 12 report, [`docs/agent-runs/phase-12/agent-report-infra-2b.md`](agent-runs/phase-12/agent-report-infra-2b.md), "Rules"; routing the health paths to `query` is recorded there as a deviation from the work order, so the smoke test can reach them). The `worker` role serves only the health routes; it reads the job queue (and, on AWS, the embedding queue) instead.

## 3. Authentication

- Header `X-Api-Key`. Two keys exist: a **full** key (every route) and a **read-only** key (GET routes only). A missing or wrong key is 401 `UNAUTHENTICATED`; the read-only key on `POST /v1/query` is 403 `FORBIDDEN` (`HttpAdapter.scala:219-222`, `:242-243`; test `HttpBehaviorSuite.scala:35`).
- The service reads the two key values once at start from the environment variables **named** in configuration: `service.apiKeyEnvVar` = `SCHOLARCHAIN_SERVICE_API_KEY` and `service.readOnlyApiKeyEnvVar` = `SCHOLARCHAIN_SERVICE_READONLY_API_KEY` (`config/course.json:94-95`). It refuses to start when either is unset or blank, or both hold the same value (`Auth.scala:47-63`). It keeps only SHA-256 digests and compares in constant time (`Auth.scala:20-35`).
- On AWS the values came from AWS Secrets Manager containers; API Gateway passed the header through unchanged and did no authentication of its own (Phase 12 report, [`docs/agent-runs/phase-12/agent-report-infra-2b.md`](agent-runs/phase-12/agent-report-infra-2b.md), "Ingress").
- The health routes are open and are never rate-limited (`HttpAdapter.scala:196-217`).

## 4. Common request and response rules

**Order of checks** for every request (`HttpAdapter.scala:80-82`): health routes → API key (401) → rate limit (429) → route lookup (404) → method (405, with an `Allow` header) → permission (403) → media type (415) → body size (413) → UTF-8 (400) → handler.

**Request headers:**

| Header | Rule | Source |
|---|---|---|
| `X-Api-Key` | see section 3 | `HttpAdapter.scala:220` |
| `Content-Type` | `application/json`, optionally `charset=utf-8`; anything else on a POST is 415 | `HttpAdapter.scala:286-293` |
| `Idempotency-Key` | optional, `POST /v1/query` only; 1 to 128 characters `[A-Za-z0-9._:-]`, else 400 | `QueryService.scala:285-290`; pattern `modules/storage/src/main/scala/org/scholarchain/storage/jobs/JobModel.scala:199` |
| `X-Request-Id` | optional; 1 to 64 characters `[A-Za-z0-9._-]` is echoed, anything else is replaced by a generated ID | `ServiceLimits.scala:39`; `HttpAdapter.scala:98-100` |
| `traceparent` | optional W3C trace context; its trace ID becomes the response's trace ID, else one is generated | `HttpAdapter.scala:101-102`; `Support.scala:53-59` |

**Response headers on every answer:** `Content-Type: application/json; charset=utf-8`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `X-Request-Id`, `X-Trace-Id` (`HttpAdapter.scala:144-150`). Retryable throttling answers add `Retry-After` (seconds). Replayed idempotent answers add `Idempotent-Replayed: true`. A 202 adds `Location`.

**Bodies:** JSON with sorted keys. Empty optional fields are `null`, not missing (`Models.scala:8-10`). Unknown fields anywhere in the `POST /v1/query` body are 400, and an unknown query-string parameter on any route is 400 (`QueryRequestParser.scala:98-100`; `Routes.scala:22-27`; `QueryService.scala:279-284`).

## 5. Routes

### 5.1 `GET /healthz`

Liveness only. Always 200 with `{"role": ROLE, "status": "ok"}` (`HealthService.scala:46`; model `HealthBody`, `Models.scala:22-24`).

### 5.2 `GET /readyz`

One cheap read per store through the storage ports, at most 2 s in total (`ServiceLimits.ReadinessDeadlineMillis`, `ServiceLimits.scala:14`); the result is cached for 5 s (`ServiceLimits.scala:19`). 200 with `status` `ready`, or 503 with `status` `not_ready`; `checks` maps each check name to `ok` or `fail` (`HealthService.scala:84-88`; `Models.scala:26-28`). The body never holds a configuration value, endpoint, table, key name or error text (`HealthService.scala:9-13`). Example: `modules/services/src/test/resources/api/v1/readiness-not-ready.json`.

Each failed check writes one `readiness-check-failed` log line with a reason class (Phase 16 G-23, `HealthService.scala:15-24`). In Phase 14 the graph check failed once on a cold Neptune call and then passed six times (runbook log row "D12 readiness, recheck").

### 5.3 `POST /v1/query`

Asks a question. The runtime workflow plans, retrieves, builds an evidence bundle, synthesises an answer and verifies every claim (stage names below).

**Request body** (`QueryRequestParser.scala:109-307`):

| Field | Type | Rule |
|---|---|---|
| `question` | string | required; canonicalised; not blank; at most 2,000 characters (`JobModel.scala:176`) |
| `mode` | string | `sync` (default) or `async` |
| `constraints.yearFrom`, `constraints.yearTo` | integers | given together; must equal one configured window, else 422 with the list of windows in `details.windows`. The configured windows are `full-2015-2025`, `w2015-2017`, `w2018-2020`, `w2021-2023`, `w2024-2025` (`config/course.json:20-26`) |
| `constraints.topics` | array of strings | at most one topic (more is 422); not blank, no control characters; at most 200 characters (`JobModel.scala:179`; longer is 422) |
| `constraints.venues`, `constraints.institutions` | arrays of strings | must be empty (422 otherwise): no venue metrics or institution data exist |
| `output.includeEvidence` | boolean | default `true`: the result carries the bundle's evidence items |
| `output.includeScores`, `output.includeUncertainty` | booleans | must be `true` (always returned); `false` is 422 |
| `retrieval.graphLimit`, `retrieval.vectorLimit`, `retrieval.maxPathDepth` | integers | at least 1 (400) and at most the deployment's bound (422): `runtime.graphResultLimit` 50, `vectors.queryTopK` 30, `runtime.maxPathDepth` 6 (`config/course.json:58`, `:87-88`; the vector bound is the context's `vectorTopK`, `QueryService.scala:268-272`). **Accepted but not applied**: the plan comes from the question, and the result's `notes` says so (`QueryRequestParser.scala:46-51`) |
| `analysis.rankingMetric` | string | a ranked metric wire name, else 422; accepted but not applied |
| `analysis.includeTeamSize`, `includeBridgeMetrics`, `includeCommunities`, `includePaths` | booleans | accepted but not applied |

What reaches the runtime is only the question, the window label and the topic (`QueryRequestParser.scala:38-39`).

**Responses:**

- **Sync, 200:** a completed result (`CompletedResult`, `Models.scala:131-140`): `queryId` (the runtime's query run ID, `run:v1:<32 hex>`), `answer` (`AnswerView`, `Models.scala:30-47`: `schemaVersion`, `question`, `answerText`, `modelId`, `plan`, `bundleId`, `claims`, `identityNotes`, `abstained`, `abstentionReason`, `provenance`, `evidence`), `links` (`explain` and one `evidence` link per cited item, with `?window=START-END` when the item has a window; `QueryService.scala:135-140`), `notes`. Example: `modules/services/src/test/resources/api/v1/completed-result.json`.
- **Async, 202:** `{queryId, jobId, status: "QUEUED", statusUrl}` with a `Location` header (`QueryService.scala:536-548`; `Models.scala:142-149`). Example: `modules/services/src/test/resources/api/v1/async-acknowledgment.json`.
- **Errors:** 400, 401, 403, 409, 413, 415, 422, 429, 500, 502, 503, 504 as in section 6.

**The query ID is deterministic.** The same validated question, window and topic give the same `queryId` (`QueryService.scala:296`); two runs of one question write one evidence bundle (put-if-absent) and one audit record each (contract test `tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/ReplayIdempotencyCrossModuleSuite.scala:25-43`).

**Last guard on the answer:** `ResponseGuard` withholds any source reference that is a URI and replaces any answer text or note that holds a store or provider reference (an AWS resource name string, an `s3://` URI, an AWS service host name or a stand-alone 12-digit number; the exact pattern is in the code) with a fixed text (`Models.scala:71-123`).

### 5.4 `GET /v1/jobs/{jobId}` and `GET /v1/jobs/{jobId}/result`

`jobId` must match `[A-Za-z0-9._-]{1,128}` (`JobModel.scala:187`), else 400; unknown is 404 `JOB_NOT_FOUND` (`QueryService.scala:622-628`).

- **Status, 200** (`JobStatusView`, `Models.scala:154-169`): `jobId`, `queryId`, `mode` (`sync` or `async`), `state` (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`), `stage`, `createdAt`, `updatedAt` (ISO-8601 UTC), `attempts`, `resultUrl` (set when succeeded), `error` (`{code, message}`). Example: `modules/services/src/test/resources/api/v1/job-status.json`. `stage` is the last workflow stage written: `planner`, `resolver`, `metrics`, `paths`, `community`, `semantic`, `evidence`, `identity`, `synthesis`, `verification`, `audit` (`modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala:828-840` at `50ba087`).
- **Result:** 200 with the completed result when succeeded; 409 `JOB_NOT_READY` (retryable) while queued or running; 409 `JOB_FAILED` with `details.jobError` when failed (`QueryService.scala:633-643`).
- Job error codes: `SUPERSEDED`, `LEASE_EXPIRED`, `ATTEMPTS_EXHAUSTED`, `RESULT_TOO_LARGE`, `WORKFLOW_FAILED`, each with a fixed message (`Errors.scala:152-171`).

Both a sync request with an `Idempotency-Key` and every async request create a job record; a sync request without a key creates none (Phase 10 gate, [`docs/agent-runs/phase-10/human-gate.md`](agent-runs/phase-10/human-gate.md); `QueryService.scala:297-301`).

### 5.5 `GET /v1/evidence/{evidenceId}`

One evidence item (`EvidenceService`, `ReadServices.scala:11-63`). Optional query parameter `window=START-END`. Without it, an item stored in several windows is 400 `WINDOW_REQUIRED` with `details.windows` (at most 100 windows are read, `ServiceLimits.scala:79`). A malformed ID or window is 400; unknown is 404 `EVIDENCE_NOT_FOUND`.

200 body (`EvidenceView`, `Models.scala:171-205`): `schemaVersion`, `evidenceId`, `evidenceType` (one of `work`, `authorship`, `coauthor_edge`, `graph_path`, `metric`, `match_decision`, `external_record`, `semantic_context`; `modules/core/src/main/scala/org/scholarchain/core/Enums.scala:113-123`), `subjectIds`, `window`, `metric`, `snippet`, `provenance` (dataset, snapshot, source record IDs, run ID, job name, policy and schema versions, content hash), `sourceUris` (a `https://dblp.org/rec/<key>` link per dblp key, otherwise a `scholarchain:` reference; `ReadServices.scala:76-82`). Example: `modules/services/src/test/resources/api/v1/evidence-item.json`.

### 5.6 `GET /v1/explain/{queryId}`

The latest stored audit record of a query run, plus `attemptCount` (`ExplainService`, `ReadServices.scala:85-101`; `ExplainView`, `Models.scala:223-321`). No query parameters. Unknown is 404 `QUERY_NOT_FOUND`. At most 1,000 audit records are read (`ServiceLimits.scala:76`).

Fields: `queryId`, `attemptCount`, `auditSchemaVersion`, `plan`, `planSource`, `graphRunId`, `promptVersions`, `modelId`, `evidenceIds`, `bundleId`, `resolvedResearcherIds`, `unresolvedNames`, `metricPolicyVersions`, `stageMillis`, `latencyMillis`, `inputTokens`, `outputTokens`, `verificationCounts` (claims per verification status, section 9), `abstained`, `abstentionReasons` (each stored code with its fixed text, from `modules/rag/src/main/scala/org/scholarchain/rag/runtime/Abstentions.scala`), `queryTemplateIds`, `indexesQueried`, `metadataFilterKeys`, `modelCalls`, `cacheHits`, `scoreComponents` (always empty: rankings use one stored metric), `notes`. It never holds prompt text, model output or reasoning (`Models.scala:223-238`). Example: `modules/services/src/test/resources/api/v1/explain.json`.

### 5.7 `GET /v1/graph/nodes/{nodeId}`

A researcher (`researcher:v1:<32 hex>`) or work node of the loaded graph run (`GraphService.node`, `ReadServices.scala:211-243`). 200 body (`NodeView`, `Models.scala:207-211`): `nodeId`, `kind` (`researcher` or `work`), `graphRunId`, `properties`. Another stable-ID kind (mention, edge, evidence, run and others) is 422 `UNSUPPORTED_NODE_KIND`; a string that is not a stable ID is 400; unknown is 404 `NODE_NOT_FOUND` (`ReadServices.scala:121-126`). Example: `modules/services/src/test/resources/api/v1/node.json`.

### 5.8 `GET /v1/graph/nodes/{nodeId}/neighbors`

One page of neighbours (`GraphService.neighbors`, `ReadServices.scala:245-341`). The server rejects unbounded exploration:

| Parameter | Values | Default | Source |
|---|---|---|---|
| `direction` | `in`, `out`, `both` | `both` | `ReadServices.scala:258-259` |
| `depth` | integer 1..3 accepted; **only 1 is served**, 2 and 3 are 422 `UNSUPPORTED_DEPTH` | 1 | `ReadServices.scala:260`, `:303`; `ServiceLimits.scala:26-29` |
| `limit` | integer 1..`runtime.graphResultLimit` (50 in `config/course.json:88`) | the smaller of 20 and that cap | `ReadServices.scala:261`; `ServiceLimits.scala:21-24` |
| `edgeTypes` | comma list of `COAUTHORED_WITH`, `AUTHORED`, `HAS_TOPIC`, `AFFILIATED_WITH`, `PUBLISHED_IN` | `COAUTHORED_WITH` for a researcher, `AUTHORED` for a work | `ReadServices.scala:265-282`, `:344-352` |
| `window` | a configured window label | `runtime.defaultWindowLabel` = `full-2015-2025` (`config/course.json:84`) | `ReadServices.scala:262-264` |
| `cursor` | the `nextCursor` of the previous page, for the same node, direction, edge types, window and limit | none | `ReadServices.scala:283-302` |

Only two combinations are served: a researcher with `COAUTHORED_WITH` (any direction) and a work with `AUTHORED` (`in` or `both`); every other combination is 422 `UNSUPPORTED_EDGE_TYPE` with the supported list in `details.supported` (`ReadServices.scala:304-311`). A bad cursor is 400 `INVALID_CURSOR`.

200 body (`NeighborsPage`, `Models.scala:213-221`): `nodeId`, `window`, `rows` (each row carries `edgeType`), `truncated`, `nextCursor`. **A well-formed but unknown node ID gets 200 with empty `rows`, not 404**: this route does not look the node up first (`ReadServices.scala:277-340`), a behaviour the human architect accepted in Phase 10 with the instruction that the API docs say so ([`docs/agent-runs/phase-10/human-gate.md`](agent-runs/phase-10/human-gate.md), decision 4). Use `GET /v1/graph/nodes/{nodeId}` to tell an unknown node (404) from a node without neighbours. Paging stops at the cap: past `runtime.graphResultLimit` rows the last page has `truncated: true` and no cursor (`ReadServices.scala:319-338`; test `ReadRoutesSuite.scala:321`). Example: `modules/services/src/test/resources/api/v1/neighbors-page.json`.

## 6. Error model

Every non-2xx body except `/readyz` is `{error, message, traceId, retryable, details}` (`ErrorBody`, `Models.scala:12-20`). `message` is the code's fixed text; downstream error text never reaches a client (`Errors.scala:3-6`). `traceId` equals the `X-Trace-Id` header. Field problems are listed in `details.fieldErrors` as `{field, problem}` (`Errors.scala:134-143`).

All codes, from `Errors.scala:12-86`:

| HTTP | Code | Retryable | Meaning |
|---|---|---|---|
| 400 | `INVALID_JSON` | no | body is not valid JSON (or not UTF-8, or nested deeper than 16) |
| 400 | `INVALID_REQUEST` | no | a field, header, path or query parameter is invalid (`details.fieldErrors`) |
| 400 | `WINDOW_REQUIRED` | no | the evidence ID is stored in several windows; name one |
| 400 | `INVALID_CURSOR` | no | the neighbour cursor does not fit this request |
| 401 | `UNAUTHENTICATED` | no | missing or wrong `X-Api-Key` |
| 403 | `FORBIDDEN` | no | this key may not call this route |
| 404 | `ROUTE_NOT_FOUND`, `QUERY_NOT_FOUND`, `JOB_NOT_FOUND`, `NODE_NOT_FOUND`, `EVIDENCE_NOT_FOUND` | no | unknown route or ID |
| 405 | `METHOD_NOT_ALLOWED` | no | wrong method (with `Allow`) |
| 413 | `PAYLOAD_TOO_LARGE` | no | body over `service.maxRequestBytes` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | no | POST body not `application/json` |
| 409 | `IDEMPOTENCY_CONFLICT` | no | this key was used with a different request |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | yes | a request with this key is still running |
| 409 | `JOB_NOT_READY` | yes | the job has not finished |
| 409 | `JOB_FAILED` | no | the job failed (`details.jobError`) |
| 422 | `UNSUPPORTED_CONSTRAINT`, `UNSUPPORTED_EDGE_TYPE`, `UNSUPPORTED_DEPTH`, `UNSUPPORTED_NODE_KIND` | no | valid JSON or parameters that this deployment cannot serve |
| 429 | `RATE_LIMITED` | yes | the per-process token bucket is empty (`Retry-After`) |
| 500 | `INTERNAL` | no | unexpected failure; quote the trace ID |
| 500 | `RESULT_TOO_LARGE` | no | the completed result is over the stored limit |
| 502 | `DOWNSTREAM_FAILURE` | yes | a store or the workflow failed (see section 7 before retrying a keyed query) |
| 503 | `OVERLOADED`, `CIRCUIT_OPEN` | yes | every query slot is busy, or a circuit breaker is open (`Retry-After: 2`) |
| 504 | `WORKFLOW_TIMEOUT` | yes | the sync query passed its deadline; the workflow keeps running |
| 504 | `READ_TIMEOUT` | yes | an evidence or explain read passed its route time limit |

`Retry-After` is 2 s for 503 and for the retryable 409 answers (`ServiceLimits.scala:81-84`; `Errors.scala:145-146`), and the time to the next token for 429 (`Support.scala:238-247`). Frozen examples: `error-400.json`, `error-401.json`, `error-404.json`, `error-409.json`, `error-422.json`, `error-504.json` in `modules/services/src/test/resources/api/v1/` (test `GoldenExamplesSuite.scala:76`).

Three circuit breakers protect the workflow, the graph port and the evidence and explain reads: after `service.circuitFailureThreshold` (3) consecutive failures a breaker opens for `service.circuitOpenMillis` (30,000 ms), then lets one trial through (`QueryService.scala:23-45`; `Support.scala:64-67`; `config/course.json:103-104`). For graph reads only availability failures count against the breaker; a refused query or a 4xx from the store gives its slot back (`GraphReads.scala:6-34`, Phase 16 FECD-1).

## 7. Idempotency and the 502 rule (R-1)

`POST /v1/query` accepts an optional `Idempotency-Key`. The key is scoped to the caller class, so the full and read-only keys never share records (`QueryService.scala:334`, `:495`). The request fingerprint is the sha256 of the canonical JSON of the whole validated request after defaults, `mode` included (`QueryRequestParser.scala:53-76`). A key's record lives for `service.jobTtlSeconds` (86,400 s, `config/course.json:102`; `QueryService.scala:70`); after that the same key starts a new job (test `IdempotencySuite.scala:302-312`).

What the same key returns (`QueryService.follow`, `QueryService.scala:552-594`):

| State of the key's job | Answer to the same key and same request |
|---|---|
| different request (fingerprint differs) | 409 `IDEMPOTENCY_CONFLICT`, not retryable (`IdempotencySuite.scala:63-84`) |
| queued, or running with a live lease | 409 `IDEMPOTENCY_IN_PROGRESS`, retryable, `Retry-After: 2` |
| succeeded, sync | 200, the stored result, `Idempotent-Replayed: true`; the workflow does not run again (`IdempotencySuite.scala:23-38`; contract `ReplayIdempotencyCrossModuleSuite.scala:111-127`) |
| succeeded, async | 202, the acknowledgment with the job's current status, `Idempotent-Replayed: true` (`IdempotencySuite.scala:40-61`) |
| failed | 409 `JOB_FAILED`, not retryable, `details.jobError` = the job's error code |
| sync job whose process died (lease expired) | the job is marked `LEASE_EXPIRED`, then 409 `JOB_FAILED` (`IdempotencySuite.scala:133-175`) |

**R-1: after a 502, the same key gets 409.** Confirmed in the code at `50ba087`:

1. A keyed sync query whose workflow fails (a store or model failure inside the runtime) records a breaker failure, marks its job `failed` with `WORKFLOW_FAILED`, and answers 502 `DOWNSTREAM_FAILURE`, which is marked retryable (`QueryService.scala:427-430`; `Errors.scala:76-77`).
2. A retry with the same key and the same request finds the key's record (`QueryService.scala:335-336`), follows it to the failed job and answers 409 `JOB_FAILED` with `details.jobError` `WORKFLOW_FAILED`, not retryable (`QueryService.scala:575`, `:663-671`). This lasts until the key's record expires (86,400 s).
3. Module test: `IdempotencySuite` "replay of a failed job is 409 JOB_FAILED with the job error code" (`modules/services/src/test/scala/org/scholarchain/services/IdempotencySuite.scala:117-131`) asserts 502 `DOWNSTREAM_FAILURE` first, then 409 `JOB_FAILED` with `jobError` `WORKFLOW_FAILED` and `retryable` false.
4. Contract test: `ReplayIdempotencyCrossModuleSuite` "an audit write that fails after the bundle write: 502, then a retry gives the same IDs" (`tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/ReplayIdempotencyCrossModuleSuite.scala:45-91`) shows the other half: a retry **without** the failed key, across services, rag and storage, answers 200 with the same query ID and cites the bundle stored before the failure. No test under `tests/**` asserts the 409 after a 502 itself; that assertion is the module test of item 3.

This behaviour was found in the Phase 10 review (R-1, [`docs/agent-runs/phase-10/review-report-03.md`](agent-runs/phase-10/review-report-03.md)) and kept unchanged by the human architect's decision ([`docs/agent-runs/phase-10/human-gate.md`](agent-runs/phase-10/human-gate.md), decision 8; [`docs/agent-runs/phase-11/human-gate.md`](agent-runs/phase-11/human-gate.md), "Human decisions in this phase").

**Client rule: after a 502, use a new key.** A 502 from other causes (for example a job or idempotency store failure while the key is being claimed) leaves the key in a state that the code does not pin to one answer; the rule covers every 502.

**What a new key does.** It starts a new workflow run with its own job (`job-<uuid>`) and its own idempotency record (`QueryService.scala:338-372`). Because the query ID comes from the question, the same question keeps the same `queryId`: the evidence bundle write is put-if-absent, so the new run reuses the stored bundle, and the run appends one more audit record under that query ID (contract `ReplayIdempotencyCrossModuleSuite.scala:25-43`: two keys, one query ID, one bundle, two audit records). A query reads the graph, metric, evidence and vector stores and writes only its own records: its job, its idempotency record, its evidence bundle (put-if-absent) and its audit record.

**When the same key is right:**

- After 504 `WORKFLOW_TIMEOUT`: the workflow is never interrupted; it keeps running and writes its result to the job (`QueryService.scala:376-379`). The same key answers 409 `IDEMPOTENCY_IN_PROGRESS` while it runs and then 200 with `Idempotent-Replayed: true` (test `IdempotencySuite.scala:86-115`). A 504 on a request **without** a key leaves no job, so its result cannot be fetched; a retry runs the workflow again.
- After 409 `IDEMPOTENCY_IN_PROGRESS` or 409 `JOB_NOT_READY`: wait `Retry-After` and retry.
- When no response arrived at all (a network failure): the same key either replays the result, reports the run in progress, or, if the request never reached the service, runs it once.
- After 503 `OVERLOADED` or `CIRCUIT_OPEN` on a keyed sync query: in the usual case the slot check happens before any job or key record is written (`QueryService.scala:337-338`, `:306-314`), so the same key is safe. **One rare exception:** if the query pool rejects the task after the job and the key record were written (for example during shutdown), the service also answers 503 `OVERLOADED`, but marks that job `WORKFLOW_FAILED` (`QueryService.scala:344-358`, `:390-396`); a retry with the same key then follows the failed job and gets 409 `JOB_FAILED` (`QueryService.scala:575`, `:663-666`). A client that gets 409 `JOB_FAILED` after a 503 retries with a new key, as after a 502.

**Async requests** are not affected by R-1 in the same way: a store or model failure in the worker puts the job back to `queued` and releases the message for another attempt, up to `service.jobMaxAttempts` (3, `config/course.json:100`), and only then marks it `ATTEMPTS_EXHAUSTED` (`Worker.scala:38-55`).

## 8. Limits and timeouts

Fixed constants are in `modules/services/src/main/scala/org/scholarchain/services/ServiceLimits.scala`; every other bound comes from the `service` and `runtime` sections of `config/course.json`.

| Limit | Value | Source |
|---|---|---|
| Request body | 65,536 bytes (`service.maxRequestBytes`) | `config/course.json:96`; `HttpAdapter.scala:258-268` |
| JSON nesting depth | 16 | `ServiceLimits.scala:50-54` |
| Path length, query-string length | 512, 2,048 characters | `ServiceLimits.scala:56-60` |
| Question length | 2,000 characters | `JobModel.scala:176` |
| Topic length | 200 characters | `JobModel.scala:179` |
| Time for a client to send its request | 30 s (JDK HTTP server `maxReqTime`) | `Main.scala:259-261` |
| Rate limit | 600 requests per minute per process, one token bucket for every authenticated request (`service.requestsPerMinute`) | `config/course.json:105`; `HttpAdapter.scala:224-229`; `Support.scala:231-248` |
| API Gateway throttle (deployed, Terraform) | rate 5 requests/s, burst 10 | Phase 12 report, `agent-report-infra-2b.md` "Ingress"; Phase 15 D-read observed it dominate every level above one client ([`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md), Results) |
| API Gateway integration timeout (deployed) | 30,000 ms | Phase 12 report, `agent-report-infra-2b.md` "Ingress" |
| Concurrent queries per process | 4 (`service.maxConcurrentQueries`); the fifth gets 503 `OVERLOADED` | `config/course.json:98`; `QueryService.scala:306-314` |
| Sync query deadline | 25,000 ms (`service.syncDeadlineMillis`), then 504 `WORKFLOW_TIMEOUT` | `config/course.json:97`; `QueryService.scala:398-411` |
| Evidence and explain read limit | 10,000 ms by default (1..60,000 accepted), then 504 `READ_TIMEOUT` | `ServiceLimits.scala:62-73`; `QueryService.scala:80-125`; test `RouteTimeLimitSuite.scala:22` |
| Graph reads | `runtime.graphTimeoutMillis` 20,000 ms and `neptune.queryTimeoutMillis` 20,000 ms; not under the read limit | `config/course.json:45`, `:89`; test `RouteTimeLimitSuite.scala:89` |
| Readiness probe | 2,000 ms deadline, 5,000 ms cache | `ServiceLimits.scala:10-19` |
| Graph neighbours | depth 1 only; page 1..50, default 20; paging stops at 50 rows | section 5.8 |
| Stored completed result | 300,000 bytes, else 500 `RESULT_TOO_LARGE` | `JobModel.scala:173`; `ServiceLimits.scala:31-34` |
| Explain audit read, evidence windows read | 1,000 records, 100 windows | `ServiceLimits.scala:75-79` |
| Job lease, job and key lifetime, worker attempts | 120,000 ms, 86,400 s, 3 | `config/course.json:100-102` |
| Evidence bundle size | 200 items (`runtime.maxBundleItems`) | `config/course.json:86` |
| Researcher names per question | 5 (`runtime.maxResearcherNames`) | `config/course.json:90` |

The verified service limits of AWS itself (S3 Vectors, DynamoDB, Neptune, SQS, API Gateway) are recorded in [`docs/service-limits.md`](service-limits.md) (Phase 17 step W2).

## 9. Claim verification statuses and reasons

Every claim in `answer.claims` carries `status`, one of four wire names (`modules/core/src/main/scala/org/scholarchain/core/Enums.scala:315-332`):

| Status | Meaning (core) | Kept in the answer |
|---|---|---|
| `verified` | every check against the cited evidence passes | yes |
| `partially_supported` | some numbers or names are verified, others are not covered by the cited evidence | yes, with `uncertainty` "Partially supported: REASON." |
| `unsupported` | no valid supporting evidence; the only status that may cite no evidence | no: `claimText` becomes "Removed (unsupported): REASON" and `evidenceIds` is empty |
| `conflicting` | a number or name contradicts its cited evidence | no: `claimText` becomes "Removed (conflicting): REASON" |

How a removed or partial claim is written: `modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala:302-327` (not changed in Phase 17). `REASON` is the first fixed reason text of the verifier; reasons never contain a number, identifier, name or title from the claim (`modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:9-12`, `:81-166`). The explain route's `verificationCounts` counts claims per status.

**Code state of this section.** Unlike sections 1 to 8, this section describes the verifier as it stands after Phase 17 step 1 and the Phase 17 code repair round (`repair-01-rag.md`), in the working tree that the Phase 17 session 2 commit will hold (commit pending). Every change listed here is **fixed in code, tested offline, not validated live**: no live system existed in Phase 17.

**The verifier is deterministic code, never a model** (`ClaimVerification.scala:25`). It now gives 31 fixed reason texts (`ClaimVerification.Reasons.all`, `ClaimVerification.scala:133-165`): the 25 of Phase 16 plus 6 that Phase 17 added. Every Phase 17 code is **additive**: no Phase 16 reason text changed, and the four statuses are unchanged. The reasons that concern claims about the absence of a connection, and the status each one leads to:

| Reason code (`Reasons.*`) | Fixed text | Status it leads to | Since |
|---|---|---|---|
| `AbsenceBoundedSearch` | "an absence claim cites a path search that stopped early, so whether a path exists is unknown" | `unsupported` | Phase 16 L2 |
| `AbsenceBeyondDepth` | "an absence claim states a depth beyond the completed path search" | `unsupported` | Phase 16 L2 |
| `AbsenceNoDepth` | "an absence claim states no depth limit, but the path search was bounded" | `unsupported` when the Phase 17 rule checks the claim (`ClaimVerification.scala:697`, `:723-728`; P16-L-14); the Phase 16 check alone gave `partially_supported` (`:578`) | Phase 16 L2; status changed in Phase 17 step 1 |
| `AbsenceNotSearched` | "an absence claim states a researcher pair or window that no cited path search covered" | `unsupported`; since the Phase 17 repair it means only what it says (no cited search covered the pair or the window, or the pair does not resolve); the Phase 17 rule gives the dedicated codes below for the other causes, while the Phase 16 checks still give it as before (`:565-597`) | Phase 16 (P16-K-01) |
| `AbsenceAmbiguous` | "the claim may state that researchers are not connected, in wording the verifier cannot check" | at most `partially_supported`, never `verified` | Phase 17 step 1 |
| `AbsenceThirdName` | "an absence claim may name a researcher other than the researcher pair of the path search" | `unsupported` | Phase 17 repair (P17-A-05) |
| `AbsenceUnnamed` | "an absence claim is about researchers it does not name" | `unsupported` | Phase 17 repair (P17-A-05) |
| `AbsenceTimeQualifier` | "an absence claim states a time qualifier, an unparsed year or a year range other than its window" | `unsupported` | Phase 17 repair (P17-A-05) |
| `AbsenceFoundPath` | "an absence claim is about a researcher pair that a found path or edge in the evidence bundle connects" | `unsupported` | Phase 17 repair (P17-A-05) |
| `ConnectionUnconfirmed` | "the claim names researchers that a found path connects, but its wording does not affirm the connection in a form the verifier can check" | at most `partially_supported`, never `verified` | Phase 17 repair (P17-A-01) |
| `HopsConflict` (general) | "a hop count differs from the cited path" | `conflicting`; also given when a found path or edge in the bundle lies inside an absence claim's window and depth | Phase 11 (commit `29661e9`); widened in Phase 17 |

Sources: `ClaimVerification.scala:107-132` (texts), `:560-598` (Phase 16 absence checks), `:601-730` (Phase 17 absence rule), `:732-790` (the connected-pair rule and the found-path check of a possible absence), `:930-938` (status function); handoffs in `agent-report-rag.md` and `repair-01-rag.md` (Phase 17 session 2 reports, to be copied into `docs/agent-runs/phase-17/`).

**The absence rule as built (Phase 17, decision 6 plus the repair round).** It runs after the Phase 16 checks, never changes them and only adds reasons, so a claim's status can only fall, never rise (decision 6 item 2). The lists and the scope rule live in one place, `private[runtime] object AbsenceRule` (`ClaimVerification.scala:978` onward).

1. **Recognition by predicate and negation scope.** A clause is an absence statement when a connection predicate (`ConnectionWords`, `:1014`; `ConnectionPhrases`, `:1043`) stands under a negation that governs it (`Negations`, `:991`: no, not, never, none, without, cannot, neither, nor, nothing, nobody, every contraction ending in n't after curly apostrophes are normalised, and, since the repair, lack, lacks, lacked, lacking), or when an absence predicate that is not itself negated appears (`AbsenceWords`, `:1047`, including absent and unrelated since the repair; `AbsencePhrases`, `:1062`, including "distinct [connected] component(s)" since the repair). A negated absence predicate ("A and B are not disconnected") is a connection claim. The Phase 16 phrase list is kept as a second recogniser.
2. **Ambiguity fails closed.** An unclear negation scope, both kinds of predicate in one claim, a negation beside the named pair with no listed predicate, or any word outside the closed vocabulary of absence claims (`FunctionWords`, `:1121`; `inVocabulary`, `:1446`) gives `AbsenceAmbiguous`: at most `partially_supported`.
3. **Positive eligibility.** An absence claim can be `verified` only when every condition is established (`:686-719`): recognised without ambiguity; no possible third name (capitalised words not in the pair's names and not on `Allowlist`, `:1163`, or any name part of another researcher in the bundle; `isThirdName`, `:1440`), else `AbsenceThirdName`; a stated depth, else `AbsenceNoDepth`; a known window, every year range equal to it, and no time qualifier (before, after, since, until, till, prior, between with a year; `TimeQualifierRe`, `:1263`), else `AbsenceTimeQualifier`; a cited, complete `path=none` search for exactly the pair and window, at least as deep as the stated depth, else `AbsenceNotSearched`, `AbsenceBoundedSearch` or `AbsenceBeyondDepth`; and no found path or edge in the bundle (cited or not) with both researchers anywhere on it, else `HopsConflict` (inside the window and depth) or `AbsenceFoundPath`. A claim about unnamed people ("no common coauthors"; `UnnamedWords`, `:1232`) gets `AbsenceUnnamed`.
4. **Connected-pair rule (repair, P17-A-01; `:732-762`).** When a claim names two researchers whom a found path or edge in the bundle connects, it can be `verified` only when some clause reads as a connection and every word **before** its first affirmative predicate is in the closed vocabulary (`affirms`, `:1323`; `AffirmWords`, `:1393`; `inAffirmVocabulary`, `:1436`), or when no clause has a predicate or a negation and every word is in that vocabulary (a quantity of the pair, such as joint publications). Otherwise `ConnectionUnconfirmed`.
5. **A possible absence against a found path (repair, P17-A-04; `:763-790`).** With no affirmed connection, a clause that may state an absence is contradicted by a found path or edge between a named connected pair inside the clause's window and depth: `HopsConflict`, `conflicting`.

**Disclosed behaviour that API users will see** (each pinned by `AbsenceRuleSuite`; `repair-01-rag.md`, RISKS and INTERFACE HANDOFF):

- The deterministic edge-absence answer ("In W, A and B have no direct coauthor edge.", `Synthesis.scala:653`, an `edge=none` fact computed by Spark) is now `partially_supported` with `AbsenceAmbiguous` (Phase 16: `verified`), because edge absence is not a checked class. This is finding P17-A-02; an `edge=none` check is proposed later.
- An affirmative connection claim with a negated side clause ("A is not a direct coauthor of B; they are connected within 2 hops in W.", "A and B are connected within 2 hops in W, although they are not direct coauthors.") is now `partially_supported` with `AbsenceAmbiguous`, even when it is true (finding P17-A-03).
- A claim about two connected researchers in wording outside the closed vocabulary ("A reaches B in 2 hops") is now at most `partially_supported` with `ConnectionUnconfirmed`.
- The positive controls keep their Phase 16 status and reasons: "A and B are not disconnected in W." and "A and B are not only connected but frequent coauthors in W." (each with a cited found path), and the deterministic path and edge templates of `Synthesis.scala`. No existing fixture, golden example or fake-benchmark claim fell (`VerifierMonotonicSuite`: 59 claims, 0 rose, 0 fell; `repair-01-rag.md`).

**What is still open (L2 is not closed).** The Phase 17 code re-check (`review-report-02.md`, step 7) found the repair of P17-A-01 **PARTIAL, still major**: the connected-pair rule checks only the words before the first affirmative predicate, so an absence meaning carried by unlisted words after the predicate is still `verified` when a cited found path or edge contradicts it. Its traced inputs, each `verified` today: "Alice Smith and Bob Jones have a path that is missing in 2015-2025.", "Alice Smith and Bob Jones collaborated zero times in 2015-2025." (against an edge of 3 joint publications) and "A path linking Alice Smith and Bob Jones is nonexistent in 2015-2025.". So **P16-L-10 is not closed**. **P16-L-11 is closed** for absence claims the rule recognises; for unrecognised absence wording with no found path in the bundle, the third-name and depth checks are skipped and the claim can still be `verified` (finding P17-K-02, minor; for example "Alice Smith and Bob Jones are strangers in 2021-2023." against a search limited to depth 6). These go to the human's decision in Phase 17 session 3 (work order decision 18, E1); under decision 6 the final reviewer cannot return APPROVE while P16-L-10 is open.

**Related, not an API route: the RAG ingest job's run status.** `RagIngestJob` (the batch job that loads evidence and embeddings, not a REST service) now records a failed run as `failed: CLASS`, the exception class only, instead of `failed: CLASS: MESSAGE`, in its `run-report.json`, its report copy and its printed lines (`modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala:316-319`, `:657`, `:722`; finding P17-F-12, Phase 17 repair). The message is kept only for the job's own refusals (an exact `RuntimeException` from `sys.error`, and `ExportSourceException`). One path still keeps an SDK message: the store-build failure at `RagIngestJob.scala:340` (finding P17-K-01, minor, recorded, not repaired).

## 10. Examples and their sources

Every example is labelled with where it comes from. Nothing below is invented.

**Fixture examples (committed contract fixtures, local fake model, synthetic test graph; not the DBLP corpus).** The request `modules/services/src/test/resources/api/v1/sync-query-request.json` is:

```json
{
  "constraints": {
    "yearFrom": 2015,
    "yearTo": 2025
  },
  "mode": "sync",
  "output": {
    "includeEvidence": true
  },
  "question": "How many distinct coauthors does Carol King have in 2015-2025?"
}
```

Its sync answer is `completed-result.json` (three claims, each `status` `verified`), its async acknowledgment is `async-acknowledgment.json`, and its job is `job-status.json`. The conflict error `error-409.json` is shown below with one change: its `traceId`, a fixed 32-character test value made almost entirely of zeros, is replaced here by a description.

```json
{
  "details": {},
  "error": "IDEMPOTENCY_CONFLICT",
  "message": "This Idempotency-Key was used with a different request.",
  "retryable": false,
  "traceId": "<the fixture's 32-character test trace ID>"
}
```

All files are in `modules/services/src/test/resources/api/v1/`; `GoldenExamplesSuite` (`modules/services/src/test/scala/org/scholarchain/services/GoldenExamplesSuite.scala:49`, `:65`) checks each against the running server.

**Recorded live smoke runs (Phase 14, archived; status codes only, because `smoke-test.sh` keeps no response body).** From [`docs/agent-runs/phase-14/runbook-log.md`](agent-runs/phase-14/runbook-log.md), row "D14 smoke test A (spec gate)": `/healthz` 200, `/readyz` 200 with graph, jobs and metrics `ok`, a request without a key 401 `UNAUTHENTICATED`, the query 200 with 2 claims and 3 evidence links and `abstained` false, the evidence read 200 (type `metric`), the replay with the same key 200 with `Idempotent-Replayed: true` and the same `queryId`, and a changed body under the same key 409 `IDEMPOTENCY_CONFLICT`. Row "D15 smoke test B" recorded the same checks with an answer that abstained (finding G-28; Phase 15 later recorded its reason code `path-query-failed`, Phase 15 gate).

**Recorded live answers (Phase 15, archived bodies).** Six full `POST /v1/query` result bodies, saved from the live Phase 15 API before the teardown, are committed under `experiments/evaluation/raw/answers/p15-demo/` with their provenance and sha256 in `experiments/evaluation/raw/answers/p15-demo/PROVENANCE.txt`. For example, `p15-demo-q.json` answers "Which paper of Wanli Ouyang had the most coauthors on one paper between 2015 and 2025?" with two claims; `p15-demo-n.json` is the false-premise question "Which Nobel Prize did Wanli Ouyang win in 2019, and with which coauthors?". Their evaluation is in `docs/evaluation.md`.

**Not an actual run:** the request and response shapes in the course specification ("REST API contract") are illustrative. `modules/services/src/test/resources/api/v1/spec-example-request.json` is the specification's example request adapted to this deployment (`yearTo` 2025, a configured window; `rankingMetric` written as a ranked metric wire name). Its `retrieval.graphLimit` of 100 is above this deployment's `runtime.graphResultLimit` of 50, so against `config/course.json` it is answered 422 `UNSUPPORTED_CONSTRAINT`; the tests that send it raise the limit to 100 first and then receive 200 with eight "accepted but not applied" notes (`modules/services/src/test/scala/org/scholarchain/services/QueryApiSuite.scala:188-212`).
