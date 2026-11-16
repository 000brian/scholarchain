STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 10 step 4. Decisions 5b (R-5, stop reasons, smaller synthesis output, v2 configs), 5c (q26, m-1, m-2, m-4, Llm.scala comment, `QueryRequest.topic`, stage listener) and 5d (`RuntimeService`), then the fake benchmark. The work order was docs/agent-runs/phase-10/prompt.md; its sha256 matched c032785a…. Nothing was written under docs/agent-runs/.

FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/runtime/RuntimeService.scala
- /Users/brian/Developer/repos/scholarchain/prompts/runtime/query-planner-v2.md
- /Users/brian/Developer/repos/scholarchain/prompts/runtime/answer-synthesis-v2.md
- /Users/brian/Developer/repos/scholarchain/prompts/schemas/answer-claims-v2.schema.json
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/resources/config/rag-runtime-anthropic-v2.json
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/resources/config/rag-runtime-local-v2.json
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/ModelCallsSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/RuntimeServiceSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/test/scala/org/scholarchain/rag/runtime/RuntimeServiceFixtures.scala

FILES MODIFIED (all under /Users/brian/Developer/repos/scholarchain/modules/rag/src/):
- main/scala/org/scholarchain/rag/runtime/: Llm.scala, RuntimeModel.scala, Schemas.scala, Prompts.scala, Synthesis.scala, ClaimVerification.scala, ResearcherResolver.scala, QueryWorkflow.scala, BenchmarkRunner.scala
- test/scala/org/scholarchain/rag/runtime/: BenchmarkRunnerSuite.scala, ClaimVerificationSuite.scala, PlannerAndSchemaSuite.scala, QueryWorkflowSuite.scala, RuntimeFixtures.scala
- The v1 prompts, the v1 claims schema, the v1 runtime configs and benchmark-v1.json are byte-for-byte unchanged. A new test pins their sha256 values; the benchmark is still b58c5e1a6cb29e192035ce77308c20e2597d2d1dbaf8d136761ae5c3316ff3ac.

COMMANDS RUN:
- `sbt -batch "rag/Test/compile"` (twice)
- `sbt -batch "rag/testOnly org.scholarchain.rag.runtime.*"` (several runs)
- `sbt -batch "rag/testOnly <QueryWorkflowSuite ClaimVerificationSuite BenchmarkRunnerSuite>"`
- `sbt -batch check` (background) → /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/check-01.log
- `sbt -batch -J-Xmx12g "rag/runMain org.scholarchain.rag.runtime.BenchmarkRunner --config modules/rag/src/test/resources/config/rag-runtime-local-v2.json --export modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b --neptune-csv modules/storage/target/phase-07-real/attempt-01/neptune --questions modules/rag/src/test/resources/benchmark/benchmark-v1.json --out modules/rag/target/phase-10-real/attempt-01-fake --llm fake"` (background) → /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/aaf86ae1-6986-4770-9813-b764b8dca793/scratchpad/bench-01.log
- Otherwise only read-only git, grep, sed -n, ls, shasum, and log-waiting with tail.
- The tail/read waiter commands I started on check-01.log only read the log. Each ends when it sees the `exit=` line, which is now written, or at its 170 s read timeout. There is no stop tool in my set, so I could not stop them directly. No sbt process is left from me.

TEST RESULTS:
- **Targeted:** the rag runtime suites passed 112 tests, with 1 ignored (the `RequiresAws` test).
- **`sbt check`:** passed, exit 0, 914 s, finished at 00:44.
  - Module tests: 100 suites, 1773 executed, 1773 passed, 0 failed, 0 errors, 3 skipped.
  - Contract tests (no AWS): 22 suites, 336 executed, 336 passed.
  - `.claude/.last-test-run` was refreshed by `markTestRun`: timestamp 2026-11-16T06:44:22Z, module_tests=1773, contract_tests=336.
  - The count before this step was 1734 module tests, so this step added 39 net.

VALIDATION RESULTS (fake benchmark, scored from modules/rag/target/phase-10-real/attempt-01-fake/run-report.json; status `completed`, exit 0, 97.4 s):

| 5d target | Result | Met |
|---|---|---|
| Graph values 110/110 | 110/110 exact (exactMatchRate 1.0) | yes |
| Paths 4/4 | 4 checked, 4 matched | yes |
| Expected abstentions 3/3, no unexpected | 3/3 happened; unexpected []; missed [] | yes |
| Planted errors all caught | 89 planted, 89 caught (catch rate 1.0) | yes |
| Injection passes | q30: plan as expected, values 10/10 | yes |

Other results:
- Workflow errors: 0.
- Plan accuracy: 30/30 on every field.
- Identity resolution: 30/30, 0 silent choices on ambiguous names.
- Store conflicts: none in all 30 q-files.

Claim status counts, Phase 10 against Phase 9 (attempt-03-fake-final):

| Status | Phase 10 | Phase 9 |
|---|---|---|
| verified | 173 | 175 |
| partially_supported | 2 | 0 |
| unsupported | 0 | 0 |
| conflicting | 0 | 0 |

- The total is still 175 claims.
- The two claims that moved from verified to partially_supported are the descriptive semantic-context claims (claim-11 in two questions), with the reason `nothing checkable`. This is the intended m-1 effect, not a miss.
- The v2 cap of 15 claims removed no claims on this benchmark.

`modelCalls` section of the fake run:
- Planner and synthesis each made 30 logical calls, all succeeded.
- Failures, HTTP attempts, retries, invalid outputs, truncated outputs and fallbacks are all 0.
- Stop reasons are `unknown: 30` per task, because the fake model reports none.
- Tokens are 0.

There are no misses against the targets.

ASSUMPTIONS:
1. **The configuration cannot name the prompts.** Core's `ConfigLoader` rejects unknown keys, and the only core change this phase is the `service` section. So the prompt set is chosen by the caller: `BenchmarkRunner --prompts v1|v2` (default `v2`) and the `promptSet` option of `RuntimeService`.
   - The v2 configs carry the v2 limits: `maxOutputTokens` 4096 and `requestsPerMinute` 19. `rag-runtime-local-v2.json` uses the same limits and differs from the Anthropic file only in provider, model and key-variable name.
   - Replaying Phase 9 exactly now needs `--prompts v1`.
2. **`RuntimeService.aws` takes the graph run ID as a parameter.** The AWS stores cannot report it without a network call. Services' `Main` will need a way to pass it, for example a `--graph-run` argument.
3. **`ModelAttempt.stopReason` is a String.** It is `unknown` when the reply has no `stop_reason`, and also for a call that failed (no reply); the `outcome` field tells those two apart.
4. **Fallbacks are counted under three kinds:** `call-failed`, `output-invalid`, and an extra `prompt-render` for a template that cannot be filled.
5. **The early stop is opt-in.** The benchmark runner calls `LlmUsage.armEarlyStop()` only for live providers. Once it fires, `invoke` refuses further calls without sending a request, and the runner stops after the current question with `stopped: first 3 model calls rejected with HTTP <status>`.
6. **m-2 suffix comparison ignores case**, in both the single-candidate and the multi-candidate paths.
7. **"Whitespace only" around model JSON** also covers Unicode space separators and the byte-order mark; the old `trim` handled only ASCII whitespace.

RISKS:
- For m-4, the bundle is now written before the response contract check, so a conflict can be stated in the answer. If a response ever failed its contract, a stored bundle would have no answer pointing to it.
- The fake model and the template fallback now drop claims longer than the schema's text limit (300 characters in v2), so a long-title claim can be missing in fake and fallback answers.
- An unbound number that matches no cited value is now `conflicting` and removed. A live model's harmless numbers, such as "top 10", will be removed as conflicting.
- The embedders' `EmbeddingUsage` counters still use `@volatile +=`, so their counts are approximate under concurrent queries. The LLM counters are atomic.

MANUAL ACTION REQUIRED: the human runs the decision 5e live command after the reviews approve. Agents never run it. Export `SCHOLARCHAIN_ANTHROPIC_API_KEY` in your own shell first. Phase 9 used about 173k input and 105k output tokens, about $0.07.
```
sbt -batch -J-Xmx12g "rag/runMain org.scholarchain.rag.runtime.BenchmarkRunner --config modules/rag/src/test/resources/config/rag-runtime-anthropic-v2.json --export modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b --neptune-csv modules/storage/target/phase-07-real/attempt-01/neptune --questions modules/rag/src/test/resources/benchmark/benchmark-v1.json --out modules/rag/target/phase-10-real/attempt-06-anthropic --llm anthropic"
```
Exit 1 means `failed: <n> model calls failed (<causes>)` or `stopped: first 3 model calls rejected with HTTP <status>`. See `modelCalls` in run-report.json.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator interface gate (step 5), then service-engineer (6a) with the upstream code review (6b), then the human's 5e live run.

Prompt and model versions:
- query-planner-v2@sha256:60b5260e9c2eeabff46150fb09cf41a89fe39fbd23701b7023cdf0663e5684a1
- answer-synthesis-v2@sha256:f1f23c569b8a6504e658459eb425a122fa6e9bc54ba5ddedc1141675c8903537
- Schemas: query-plan-draft-v1, answer-claims-v2, semantic-context-v1.
- Code version `rag-runtime/2`. Fake model `fake-rule-template-v1`; live model `claude-haiku-5-5` (Bedrock `anthropic.claude-haiku-5-5`).

Known grounding gaps:
- The summary and missing-evidence text are not verified; they are labelled and sanitized of digits, identifiers and unknown names.
- Semantic-only descriptive claims are partially supported, never verified.

Proposed later:
- A prompt-set key in the `llm` config section (domain-engineer).
- Atomic `EmbeddingUsage` counters.
- A graph-run argument for services' `--stores aws`.

---

INTERFACE HANDOFF

**Files changed:** listed above.

**`org.scholarchain.rag.runtime.RuntimeService`** (final class, `AutoCloseable`):
- Fields:
  - `config: ScholarChainConfig`
  - `workflow: QueryWorkflow`
  - `stores: RuntimeStores`
  - `environment: RuntimeService.Environment` (`Local(env)`, `Aws(env)` or `Supplied`)
  - `context: RuntimeContext`
  - `model: LanguageModel`
  - `prompts: PromptSet`
  - `retriever: Option[SemanticRetriever]`
  - `ingestReport: Option[ujson.Obj]`
- Methods:
  - `graphRunId: RunId`
  - `queryRunId(request: QueryRequest): Either[String, RunId]`
  - `localEnvironment: Option[LocalRuntimeEnvironment]` → pass to `ServiceStores.local(env, visibilityMillis, clock)`
  - `awsEnvironment: Option[AwsStorageEnvironment]` → pass to `ServiceStores.aws(env, queueUrl)`
  - `isClosed: Boolean`
  - `close(): Unit` (idempotent; closes only what the factory opened: the environment and its SDK clients, and a closeable model or embedder)

**Factories in the `RuntimeService` companion:**
- `local(config, exportDir: Path, neptuneCsvDir: Path, options: LocalOptions = LocalOptions()): Either[String, RuntimeService]`
  - `LocalOptions(promptSet = Prompts.Current, promptsRoot, windowLabels, loadRejectFile, renderRejectFile, llmWiring: LlmFactory.Wiring, steps: StepTimer)`
  - Refuses a live embedder with local stores. Allows the fake or a live LLM.
- `aws(config, clientSettings: AwsClientSettings, graphRunId: RunId, options: AwsOptions = AwsOptions()): Either[String, RuntimeService]`
  - `AwsOptions(promptSet, promptsRoot, clients: Option[AwsSdkClients], llmWiring, embeddingWiring: EmbeddingProviders.Wiring, rejectFile, sleeper)`
  - Refuses the fake LLM, the fake embedder, and socket or API-call timeouts below `MinAwsCallTimeoutMillis` (5000; the 1 s SQS long poll plus 4 s of margin).
  - Makes no network call while building.
  - `defaultAwsClientSettings(config)` gives connect 10 s, socket and API call 60 s, one SDK attempt.
- `fromStores(config, stores, retriever, model, graphRunId, graphWindowLabels: Set[String], prompts: Option[PromptSet] = None, owned: List[AutoCloseable] = Nil, clock = () => System.nanoTime(), environment: Option[Environment] = None): Either[String, RuntimeService]`

**`QueryRequest(question: String, windowLabel: Option[String] = None, topic: Option[String] = None)`:**
- `MaxQuestionChars` is 2000 and `MaxTopicChars` is 200.
- `canonicalQuestion` and `canonicalText` give NFC, trimmed text with whitespace runs collapsed.
- `fingerprint(r, graphRunId: RunId)` hashes the graph run ID, the canonical question, the canonical topic and the window label.

**`QueryWorkflow`:**
- `validate(request): Either[String, QueryRequest]` returns the canonical request, or rag's rejection (map it to 4xx): blank or over-long question, unconfigured window, blank, over-long or control-character topic.
- `run(request, onStage: String => Unit = QueryWorkflow.NoStageListener): Either[String, WorkflowResult]`
  - A Left for a request that passed `validate` is an internal or store failure (retryable; 502).
  - Model failures never give a Left; the workflow uses its deterministic fallbacks.
  - `onStage` gets `QueryWorkflow.StageNames`: planner, resolver, metrics, paths, community, semantic, evidence, identity, synthesis, verification, audit. Listener exceptions are ignored.
- Also: `queryRunId(r)`, `graphRunId`, `QueryWorkflow.CodeVersion = "rag-runtime/2"`, `conflictsOf` and `conflictNote`.

**`WorkflowResult`:**
- New last field: `storeConflicts: List[String]` (`bundle <id>` and EB1 `evidenceId/window` keys). When it is non-empty the answer carries a note.
- For 7c (completed result): `response: AnswerResponse` (core v2, with `bundle`), plus `queryRunId`. The bundle is stored as EB3 and its items as EB1 before the result is returned.
- For 7g (explain): `stores.audits.read(queryRunId, limit)` returns the `QueryAudit` records. Each holds `evidenceIds` (equal to the bundle item IDs), `promptVersions`, `modelId`, token counts, `stageLatencies` (including the `plannerFallback` and `synthesisFallback` keys) and verification counts.

**Model types:**
- `LanguageModel.invoke(request)` (final) counts each call. Stub models implement `complete`.
- `ModelReply(text, inputTokens, outputTokens, stopReason: Option[String] = None)`
- `ModelFailure(message, retries, cause = "transport")`; causes are `http-<status>`, `timeout`, `transport`, `malformed-response`, `halted-after-early-stop`.
- `LlmUsage`:
  - `planner` and `synthesis` (`TaskCallCounters`, `AtomicLong` fields)
  - `recordCall`, `armEarlyStop()`, `earlyStopStatus`, `earlyStopReason`, `failedCalls`, `failedCauses`, `failureStatus`
  - `modelCallsJson`, plus `toJson` (totals)
- `ModelAttempt` has a new `stopReason` field.
- `StopReasons.TruncatedError` is `"truncated at max_tokens"`.

**Prompts and schemas:**
- `Prompts.V1`, `Prompts.V2`, `Prompts.Current` (= V2), `Prompts.setNamed`, `Prompts.loadSet(root, spec)`.
- `PromptSet(planner, synthesis, claims)` with `schemaIds`.
- `AnswerClaimsSpec.V1` and `.V2`; `AnswerClaimsSchema.validate(text, spec)`.

**Test fixtures (package `org.scholarchain.rag.runtime`, rag test tree, reachable through `rag % "test->test"`):**
- **`RuntimeServiceFixtures.fixture(model: RulePlanner => LanguageModel = new FakeModel(_), config = configV2): Fixture(world, flaky, service)`**
  - `service` is built by `fromStores` over a fresh synthetic world.
  - `service.localEnvironment == Some(world.env)`; `close()` closes the world.
  - `configV2` is `rag-runtime-local-v2.json`.
- **Requests and helpers:**
  - `answeredRequest` (`AnsweredQuestion` with `AnsweredWindowLabel = "full-2015-2025"`) gives a non-abstained answer: every claim verified, cited items in EB1/EB3, an audit written. This is tested in `RuntimeServiceSuite`.
  - `AbstainedQuestion` abstains.
  - `citedEvidence(result)` lists the cited evidence IDs.
- **`FlakyStores`** (`fixture.flaky`):
  - `failGraph` makes the next workflow return a Left (502 path); setting it back to false recovers.
  - `failKeyValue` makes EvidenceStore and audit reads return a Left.
  - `graphCalls` counts graph reads.
- **Slow and failing models:**
  - `GatedModel(delegate)`: `release()`, `awaitEntered(ms)`, `entered`. Use it for a workflow slower than the deadline that finishes on demand (504).
  - `SlowModel(delegate, delayMillis)`, with the delay adjustable.
  - `FailingModel(cause)`: the workflow still answers through fallbacks.
- **Also available:** `RuntimeFixtures.world()`, `ScriptedModel(..., stopReason)` and `SplitModel`.

**Thread safety:**
- Safe from several threads: `QueryWorkflow` (`run`, `validate`, `queryRunId`), `RuntimeService` (all methods; `close` is idempotent), `FakeModel`, `AnthropicClient` and `BedrockClaudeClient` (atomic counters, synchronized rate limiter), `LlmUsage`, `SemanticRetriever`, storage's runtime stores, and `FlakyStores` (atomic switches).
- Immutable: `PromptSet`, `RuntimeContext`, `QueryRequest`, `WorkflowResult`.
- Fixture models: `ScriptedModel` is synchronized; `GatedModel` and `SlowModel` are as safe as their delegate.
- Embedder usage counters are approximate under concurrency.

**Known limitations:**
- Assumptions 1, 2 and 5 above.
- The bundle is written before the contract check (see Risks).
- `RuntimeService.local` reloads the whole export (Phase 9 measured about 114 s and 3.9 GB on the real data).
- `FlakyStores` requires a `LocalRuntimeEnvironment`.
