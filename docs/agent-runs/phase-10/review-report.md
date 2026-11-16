STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-10/prompt.md, step 6b, upstream review (decision 8a) of build (step 1), domain (step 2), storage (step 3) and rag (step 4). modules/services was not read.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: none run by the reviewer. Observed in /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/check-01.log: lines 9511, 9899 and 9901-9902 show `Module tests: suites=100 executed=1773 passed=1773 failed=0 errors=0 skipped=3`, `Contract tests (no AWS): suites=22 executed=336 passed=336`, `[success] Total time: 914 s` and `exit=0`.
VALIDATION RESULTS: I read the fake benchmark at /Users/brian/Developer/repos/scholarchain/modules/rag/target/phase-10-real/attempt-01-fake/run-report.json. It meets every 5d target (details under "Fake benchmark" below).
ASSUMPTIONS: I can't run git, so I judged ownership from the agent reports' file lists, the git status snapshot at session start (truncated after `?? modules/rag/src/main/sc...`), and directory listings of prompts/runtime, docs/agent-runs/phase-10 and both test-resource trees. I could not list every untracked path.
RISKS: Untracked files beyond the truncated status line were not listed exhaustively (see the ownership ruling).
MANUAL ACTION REQUIRED: Human decision on U-4: where `--stores aws` gets its graph run ID, because 7a's CLI has no argument for it. Before committing, leave out the modified `.DS_Store` files (U-9).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 8 repair pass for rag-engineer (U-1, U-2) after service-engineer finishes 6a. Then step 9 re-checks U-1 and U-2 only.

VERDICT: REQUEST_CHANGES

Two major findings, both owned by rag-engineer (U-1, U-2). Build, domain and storage have no blocking or major finding.

## Rulings (decision 8a)

### R-5 (logical-call failures, early stop, counters, `modelCalls`): MET
- **A failure after retries makes the run fail non-zero.**
  - `LlmUsage.failureStatus` (Llm.scala:285-290) produces `failed: <n> model calls failed (<cause>: <count>)`.
  - BenchmarkRunner.scala:245 sets the status and :130 calls `sys.exit(1)` for any status other than `completed`.
  - Test: BenchmarkRunnerSuite:160-169 (HTTP 500 stub) asserts the status, `calls == failed*4` and `retriedAttempts == failed*3`.
- **A call that is retried and then succeeds does not fail the run.**
  - Retries are counted only in `retriedAttempts` (Llm.scala:474-478).
  - Tests: BenchmarkRunnerSuite:171-201 (529 then 200 gives `completed`) and ModelCallsSuite:139-152 (3 attempts, 2 retries, 1 logical call, `failureStatus == None`).
- **Invalid-output fallbacks are counted, not failures.** Test: ModelCallsSuite:123-137.
- **Early stop.**
  - Llm.scala:205-229 records the outcomes of the first 3 logical calls only. It fires only when all 3 share one `http-4xx` cause other than 429 (`isNonRetryable4xx`, :82-83).
  - Once armed and fired, `invoke` refuses calls without sending a request (:320-322), and the runner throws `StopRun` after the current question (:231).
  - Tests: ModelCallsSuite:191-226 cover 2 calls, 429, 5xx, timeout, mixed statuses and "only the first three". BenchmarkRunnerSuite:150-158 asserts exactly 3 HTTP calls and the status `stopped: first 3 model calls rejected with HTTP 401`.
- **Counters match the stubs.** ModelCallsSuite:98-121, 139-163 and 228-236 (8 threads, exact atomic totals).
- **`modelCalls` in run-report.json.** Present (run-report.json:533-593), with per-task and total sections, `earlyStopArmed` and `earlyStopStatus`.

### Stop reasons and `truncated at max_tokens`: MET
- `AnthropicMessages.parse` reads `stop_reason` and keeps only `[a-z_]{1,40}` (Llm.scala:385).
- `ModelAttempt.stopReason` is recorded on every attempt. A missing reason, or a failed call, is `unknown` (RuntimeModel.scala:205-234).
- `invalidError` maps `max_tokens` to `truncated at max_tokens` (:239-241).
- Tests: ModelCallsSuite:70-96, and BenchmarkRunnerSuite:194-200, which also checks that the rejected text is not stored.

### v2 prompts, schema and configs: MET
- **`AnswerClaimsSpec.V2`** (Schemas.scala:293): 15 claims, 300-character text, 10 numbers per claim, `numbersInText`, enforced at :408-413.
- **The schema file** prompts/schemas/answer-claims-v2.schema.json matches these limits. An agreement test exists (PlannerAndSchemaSuite:25, 196).
- **JSON with only whitespace around it** (Unicode and BOM included) is accepted, and a second value or prose still fails closed (Schemas.scala:48-82; test at :182).
- **v1 files are pinned by sha256** (PlannerAndSchemaSuite:61).
- **The v2 configs** have `maxOutputTokens` 4096 and `requestsPerMinute` 19 (test at :90). The limiter applies to every HTTP attempt, retries included (Llm.scala:430), so the 19 × 4096 bound holds.
- **No prompt asks for reasoning** (test at :227).

### q26: MET
query-planner-v2.md:39-46 contains the topic-versus-venue rule. Test: PlannerAndSchemaSuite:115.

### m-1: MET
- An unbound non-year number that matches no value of the cited graph items is `conflicting` (ClaimVerification.scala:204-208).
- `verified` needs `checked > 0`. Otherwise the claim is `partially_supported` with the reason `nothing checkable` (:240-249).
- Tests: ClaimVerificationSuite:102-127 and 151-155.

### m-2: MET
ResearcherResolver.scala:160-166. Test: QueryWorkflowSuite:143-159.

### m-4: MET in substance (see U-1 for the Left case)
- The fingerprint hashes the graph run ID (RuntimeModel.scala:45-55), and `CodeVersion` is `rag-runtime/2` (QueryWorkflow.scala:667).
- A bundle `Conflict` and `itemConflicts` become an answer note and `WorkflowResult.storeConflicts` (:314-316, 691-709).
- Tests: QueryWorkflowSuite:161-218.

### Llm.scala comment: MET
Llm.scala:351-354 now records the human's live verification. The "verify in the live benchmark run" text is gone.

### `QueryRequest.topic`: MET
- Bounded to 200 characters, canonicalized, rejects control characters (QueryWorkflow.scala:99-120).
- Part of the fingerprint, and overrides the draft topic (:156-159).
- Test: QueryWorkflowSuite:220-253.

### `onStage`: MET
- Called once per stage through `timed`, and exceptions from the listener are swallowed (QueryWorkflow.scala:143-149). It covers all 11 `StageNames`.
- Test: RuntimeServiceSuite:118-126 checks the order and that a throwing listener changes nothing.

### RuntimeService (5d): MET in code, NOT MET for "a test that fails without it" on `close()` (U-2)
- **`local`** refuses a live embedder (RuntimeService.scala:208-212). BenchmarkRunner uses it (:186-199).
- **`aws`** refuses the fake LLM, the fake embedder and short timeouts (:331-344), and makes no network call at build (RuntimeServiceSuite:235-249 asserts 0 SDK, 0 LLM and 0 embedder calls).
- **`fromStores`** is present.
- **`close`** is idempotent through `compareAndSet` (:75-80).
- **The thread-safety scaladoc** is present (:24-38).
- **Test gap:** no test checks that `close()` actually closes the owned stores and clients, or closes them only once (U-2).

### `service` config section (5a): MET
- Every range matches the 5a table (ConfigLoader.scala:783-831).
- Both cross-key rules are implemented: lease > sync deadline (:804-808), and TTL × 1000 > lease compared as Long (:810-814).
- `readOnlyApiKeyEnvVar` must differ from `apiKeyEnvVar` (:788-792), and both names follow the `llm.apiKeyEnvVar` rules. The config environment-variable names are distinct (ServiceConfigSuite:380-381).
- `jobQueueUrl` is optional and must be `https://` with a host and no user info.
- The loader never reads key values: ServiceConfigSuite:175-194 sets the named variables and gets the same config and fingerprint, and the values appear nowhere.
- Every boundary and both cross-key rules (equal, just above, overflow) are tested (:221-341).
- Nothing else in core changed. ConfigLoaderSuite only had its key list and count updated.

### Jobs, idempotency and queue (decision 6): MET
- **Create** is put-if-absent.
- **Every change** is put-if-version and returns `ConditionFailed`, never an exception (JobStore.scala:197-217).
- **Transitions** match decision 6 exactly (:116-188). The full operation × state matrix is tested (JobStoreSuite:210-249).
- **Reclaim** is allowed only when `leaseUntil < now`, and is refused at `leaseUntil == now` (JobModel.scala:114-115; test at :102-120).
- **Claim and reclaim** increment `attempts` and set the lease.
- **`succeed`** writes the state and the result in one conditional put (test at :152-170).
- **`ResultTooLarge`** is checked against 300,000 UTF-8 bytes and writes nothing (:149-168; test at :172-187).
- **Idempotency claim** is put-if-absent. A losing claim returns the stored record unchanged. The record holds only fingerprint, jobId, expiresAt and version, and the key is hashed (IdempotencyStore.scala:84-104; IdempotencyStoreSuite:26, 80, 98).
- **InMemoryWorkQueue** uses the injected clock for visibility, `release` makes a message visible at once, receive counts are tracked, and stale receipts are ignored (InMemoryWorkQueue.scala:50-76; 6 tests).
- **Storage reads no clock.** A grep of storage main finds only the injected `clock.millis()` in the queue, besides pre-existing elapsed-time measurements.
- Both the in-memory and the DynamoDB-stub stores run the same contract suite.

### Ownership: MET for every listed file
- build-engineer: build.sbt only (:339-345 matches the reported diff).
- domain-engineer: modules/core/** only.
- storage-engineer: modules/storage/** only.
- rag-engineer: modules/rag/** and prompts/** only. prompts/runtime holds v1 and v2 only.
- docs/agent-runs/phase-10/ contains only prompt.md (human).
- The rag and storage test resources have no path collisions on the services test classpath.
- The modified `.DS_Store` files are not agent artifacts (U-9).

### Stated deviations and assumptions
- **Storage `ServiceStores.local` extra `clock` parameter and `Either` returns:** ACCEPTED. The clock is required by "storage reads no clock" plus "injected clock". `Either` replaces exceptions for bad inputs.
- **`setStage` as a non-transition write:** ACCEPTED. Running only, version-conditioned, needed by 7f. Note U-7.
- **Job `writeToken` and idempotency `version`:** ACCEPTED. These are write mechanics, not state or result. The token makes a retried, already-applied put report `Written` (JobStoreSuite:353; IdempotencyStoreSuite:154).
- **Expired items treated as absent:** ACCEPTED. Consistent with lazy TTL and 7e ("after expiry the same key starts a new job"). Replacement is version-conditioned with bounded rounds (IdempotencyStore.scala:81-101).
- **`SqsWorkQueue` and `AwsSdkClients.sqs` changes:** ACCEPTED. Backward compatible: the Int constructor is kept, `sqs` defaults to None, `close` includes it, and a None visibility uses the queue's own setting.
- **Prompt set chosen by the caller (`--prompts`, default v2):** ACCEPTED, because core's loader rejects unknown keys and core was frozen. Consequence in U-3.
- **`RuntimeService.aws` taking a graph run ID:** ACCEPTED for rag. It leaves an interface gap for services (U-4).
- **Early stop opt-in for live providers only:** ACCEPTED. Decision 5b scopes it to "a live run". The fake model cannot fail.
- **Bundle written before the response contract check:** ACCEPTED. IDs are deterministic and the write is put-if-absent, so a retry is `Unchanged`. A bundle stored with no answer is harmless. The Left case is U-1.
- **Unbound numbers like "top 10" becoming `conflicting`:** ACCEPTED. This is the literal m-1 rule. Its effect on the live 5e run is a risk to watch, not a defect.
- **m-2 case-insensitive suffix:** ACCEPTED. It also changes the multi-candidate path, which is low risk because homonym numbers are digits.
- **Unicode whitespace around JSON:** ACCEPTED. A strict superset of `trim`; still exactly one object.

### Fake benchmark (5d targets): ALL MET (run-report.json)
- Graph values: 110/110, `exactMatchRate` 1.
- Paths: 4/4.
- Expected abstentions: 3/3, with `unexpectedAbstentions` and `missedAbstentions` both empty.
- Planted errors: 89 planted, 89 caught.
- Injection (q30): values 10/10, plan as expected.
- Status: `completed`. `modelCalls` shows 0 failures.
- Claim counts: verified 173 (Phase 9: 175), partially_supported 2, unsupported 0, conflicting 0. The 2 partially supported claims are descriptive semantic claims with "nothing checkable", the intended m-1 effect.

## Findings

**U-1, MAJOR, rag-engineer.** A failed bundle or audit write is dropped silently, and the workflow still returns Right.
- **Evidence:**
  - QueryWorkflow.scala:314-316: `bundleWrite = auditStage.putBundle(...)`. `conflictsOf` (:691-692) uses `write.flatMap(_.toOption)`, so a `Left` gives no conflict, no note and no failure.
  - :466: the `auditStage.append` result `aw` is only stored in the result.
  - `EvidenceStore.putBundle` can return Left: for an oversized bundle, a contract failure, or a store failure after retries (storage RuntimeReaders.scala:367-372).
  - The rag handoff tells services the opposite. agent-report-rag.md:161 says "A Left for a request that passed `validate` is an internal or store failure (retryable; 502)". :168 says "The bundle is stored as EB3 and its items as EB1 before the result is returned."
- **Failure scenario:**
  - With `--stores aws`, the EvidenceBundle table throttles past storage's bounded retry during a sync query. `run` returns Right with `abstained=false`.
  - Services returns 200 with `links.evidence` URLs that answer 404 `EVIDENCE_NOT_FOUND`.
  - For an async or keyed job, the job is written `succeeded`. 7c reads evidence back with `EvidenceStore.getBundle`, which fails for every replay until the TTL ends.
  - If the audit write failed, GET /v1/explain returns 404 `QUERY_NOT_FOUND` for an answered query.
  - This breaks the full query-to-evidence lifecycle that the spec gate requires.
- **Repair:** Make a `Left` from `putBundle` (and from the audit append) a workflow `Left`, which maps to 502 and is retryable; the deterministic IDs make a retry safe. Alternatively, explicitly decide and document another contract in the handoff. Add a test with a store whose `putBundle` returns Left that fails without the fix.

**U-2, MAJOR, rag-engineer.** No test fails if `RuntimeService.close()` closes nothing.
- **Evidence:**
  - RuntimeServiceSuite:92-97 ("close is idempotent and closes what the handle owns") only asserts `isClosed`.
  - The `local` test (:155-156) and the `aws` test (:245-247) also only assert `isClosed` and the call counts.
  - The fixture passes `owned = List(w.env)` (RuntimeServiceFixtures.scala:92), but nothing checks that it was closed.
- **Failure scenario:** If the body of `close()` were reduced to `closed.set(true)`, every test would still pass. Services' shutdown (7i) would then leak the SDK clients or the 3.9 GB local environment, and the "no `scholarchain-*` thread alive" check would not catch the SDK's own threads.
- **Why this is major:** decision 5d requires `close()` to close "every client and store it opened, idempotently", and 8a requires a test that fails without each 5d behavior (CLAUDE.md definition of done, item 2).
- **Repair:** Add a test that passes counting closeables through `fromStores(owned = ...)` and AWS stub clients through `aws` (`AwsOptions.clients`). It should assert each is closed exactly once after two `close()` calls.

**U-3, MINOR, rag-engineer.** The query run ID does not include the prompt set.
- **Evidence:** `queryRunId` = `RunId.of(JobName, CodeVersion, fingerprint(request, graphRun), configFingerprint)` (QueryWorkflow.scala:86-92). The prompt set is chosen outside the config (BenchmarkRunner.scala:78; `LocalOptions.promptSet`).
- **Scenario:** `--prompts v1` and `--prompts v2` over the same stores produce the same query run ID and bundle ID, with audits of different `promptVersions` appended under one ID. Provenance survives in the audit, but "same run ID = same inputs" no longer holds.
- **Repair:** Not repaired now. Proposed later: hash `prompts.versions` into the fingerprint, or add the prompt-set config key that rag already proposed.

**U-4, MINOR, human decision / service-engineer.** `RuntimeService.aws` needs a graph run ID, but 7a's `Main` has no source for it.
- **Evidence:** RuntimeService.scala:313-317. 7a's CLI is `--config --role --stores [--export --neptune-csv]`.
- **Scenario:** service-engineer has to invent a CLI argument or config key that 7a does not include, or `--stores aws` cannot be built.
- **Action:** The human decides (for example a `--graph-run` argument). Not a rag defect.

**U-5, MINOR, rag-engineer.** `RuntimeService.local` leaks the model and embedder closeables on a Left path.
- **Evidence:** `cleanup()` closes only `env` (RuntimeService.scala:203, 291). A model or embedder built at :223-229 is not closed when a later step returns Left (for example `LocalRuntimeEnvironment.load` or `RuntimeContext.from`).
- **Impact:** Low; today's local model and embedder are fakes.

**U-6, MINOR, domain-engineer.** The `jobQueueUrl` validation error echoes the URL.
- **Evidence:** ConfigLoader.scala:829: `s"service.jobQueueUrl: '$u' must be..."`.
- **Scenario:** A real SQS URL (`https://sqs.<region>.amazonaws.com/<12-digit account>/<name>`) written as `HTTPS://`, or with a stray space, prints the account ID in startup error output. This conflicts with the "no account ID in logs" rule.
- **Repair:** Name the field and the rule without the value.

**U-7, ADVISORY, service-engineer (awareness).** `setStage` bumps the job's `version` (JobStore.scala:180-188, 206).
- The worker has to carry the record returned by each stage write into the final `succeed`. Otherwise its own stage writes make `succeed` return `ConditionFailed`.
- If a stage write was applied but returned Left (the token read-back failed), the worker's record is stale. The result is then discarded and the job runs again after the lease expires. 7f's "a stage write that fails is logged and ignored" should take this into account.

**U-8, ADVISORY, domain-engineer.** Core does not stop `service.apiKeyEnvVar` or `service.readOnlyApiKeyEnvVar` from naming the same variable as `llm.apiKeyEnvVar` or the embedding key variable. If they did, the Anthropic key would become a REST credential. domain-engineer already lists this under "proposed later".

**U-9, ADVISORY, human / build-engineer.** `.DS_Store` files are tracked and modified, including under `.claude/` and `docs/agent-runs/`. They are not agent artifacts. Leave them out of the phase commit. A `.gitignore` entry is build-engineer's to add.

**U-10, ADVISORY, rag-engineer.** A structured retry after an output truncated at `max_tokens` resends with the same token cap (Synthesis.scala:60-84, 223-247), so it will probably be truncated again, at extra cost. Consider skipping the retry, or shortening the request, when the error is `truncated at max_tokens`.

**U-11, ADVISORY, rag-engineer.** `EmbeddingUsage` counters are still non-atomic (`@volatile +=`), so the query embedder's counts are approximate under concurrent queries in services. rag already states this risk.

**U-12, ADVISORY, rag-engineer.** One passed name check is enough for `verified`. For example, "Alice Smith is a key broker between all communities." is verified (ClaimVerificationSuite:113-116). This follows m-1 literally, but `verified` stays weak for qualitative claims.

**U-13, ADVISORY, rag-engineer.**
- `RuntimeService.aws` takes ownership of caller-supplied `AwsOptions.clients`: they are closed through the environment (RuntimeService.scala:367-373). The scaladoc says close "closes only what the factory opened"; the doc should state the ownership transfer.
- `TableJobStore` checks transitions against the record the caller passes. A caller that edits a record but keeps its version could get around the transition rules. Services is a trusted caller, so this is documentation only (JobStore.scala:21-24).

Relevant files:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/RuntimeService.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/RuntimeServiceSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/Llm.scala
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/config/ConfigLoader.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/jobs/JobStore.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/jobs/IdempotencyStore.scala
- /Users/brian/Developer/repos/scholarchain/modules/storage/src/main/scala/org/scholarchain/storage/local/InMemoryWorkQueue.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/target/phase-10-real/attempt-01-fake/run-report.json
