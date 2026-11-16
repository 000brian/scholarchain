# Phase 9, repair 2 (R-4): rag-engineer repair report

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: docs/agent-runs/phase-09/prompt-02.md (Phase 9, repair 2, finding R-4, major), under the standing rules of docs/agent-runs/phase-09/prompt.md
FILES CREATED: none
FILES MODIFIED:
- modules/rag/src/main/scala/org/scholarchain/rag/runtime/Llm.scala
- modules/rag/src/test/scala/org/scholarchain/rag/runtime/LlmClientsSuite.scala
COMMANDS RUN:
- read-only inspection (cat, sed -n, grep, ls, git status, git diff --no-index against a scratchpad copy of the original test file)
- sbt -batch "rag/testOnly org.scholarchain.rag.runtime.LlmClientsSuite" (new tests, OLD main code): 3 failed, 8 passed, 11 total
- sbt -batch "rag/testOnly org.scholarchain.rag.runtime.LlmClientsSuite" (after the fix): Passed: Total 11, Failed 0, Errors 0, Passed 11
- sbt -batch check (exactly once, after the fix; log in the scratchpad as sbt-check-r4.log, sha256 0323be386885c6e7365579bfc65870c5632652607898d05750cf30640386c30f): exit 0
TEST RESULTS: see "sbt check result" below
VALIDATION RESULTS: no benchmark run (fake path unchanged, per decision 3); no live call; no AWS
ASSUMPTIONS:
- The Bedrock InvokeModel body is built by the same `AnthropicMessages.body(..., bedrock = true)` as the Anthropic body, so removing the one line fixes both. Verified by reading Llm.scala (BedrockClaudeClient.complete calls AnthropicMessages.body(None, r, settings.maxOutputTokens, bedrock = true)) and by the new Bedrock assertions.
- No prompt text or prompt version changed (prompts/** untouched; the prompts never mentioned sampling). Model ID unchanged (claude-haiku-5-5; Bedrock anthropic.claude-haiku-5-5).
RISKS:
- Bedrock acceptance of the body without temperature is not verified live (Bedrock on-demand quota is 0; Bedrock is not used by the benchmark).
- See the added known failure mode (non-deterministic sampling).
MANUAL ACTION REQUIRED: the human runs the live command below (agents never run it), then R-5 goes to Phase 10.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of R-4 only (APPROVE required), then the human live run to attempt-05-anthropic.

## The change

`AnthropicMessages.body` in modules/rag/src/main/scala/org/scholarchain/rag/runtime/Llm.scala no longer adds `o("temperature") = 0`. No other sampling field (`top_p`, `top_k`) is or was sent. The body is now exactly:

- Anthropic Messages API: `model`, `max_tokens`, `system`, `messages`
- Bedrock InvokeModel: `anthropic_version` (`bedrock-2023-05-31`), `max_tokens`, `system`, `messages` (no `model`)

The `AnthropicClient` scaladoc that said "temperature 0" now says the body is model, max_tokens, system, messages only, with no sampling field (temperature, top_p, top_k). Nothing else changed (endpoint, headers, retries, limiter, key handling, parsing, fallback, R-5 counting all untouched).

## Request/response fields verified live by the human (Nov 15, 2026, claude-haiku-5-5)

Direct requests to the same endpoint and key: minimal request -> HTTP 200; plus `system` -> HTTP 200; plus `"temperature":0` -> HTTP 400 "`temperature` is deprecated for this model." The fields the client now uses are exactly the live-verified ones:

- request: `model`, `max_tokens`, `system`, `messages`
- response: `content[].text` (text blocks joined), `usage.input_tokens`, `usage.output_tokens`

## Tests and why they fail on the old code

In modules/rag/src/test/scala/org/scholarchain/rag/runtime/LlmClientsSuite.scala:

1. New test `R-4: neither the Anthropic nor the Bedrock body sends temperature, top_p or top_k`: sends one request through `AnthropicClient` (stub transport) and one through `BedrockClaudeClient` (stub invoker, no live call), and calls the body builder directly for both `bedrock = true/false`; asserts the key set intersected with {temperature, top_p, top_k} is empty, and the exact key sets above. On the old code it failed with obtained `HashSet("temperature")` vs expected `Set()` (observed).
2. Changed test `anthropic: Messages API request shape and response parsing`: the old lines 77-80 asserted `b("temperature").num == 0.0` and key set `{model, max_tokens, system, messages, temperature}`; the new assertion is the inverse (key set `{model, max_tokens, system, messages}`). On the old code it failed (observed).
3. Changed test `bedrock: InvokeModel body, throttling retried, permanent errors not`: added an exact key-set assertion `{anthropic_version, max_tokens, system, messages}`. On the old code it failed with obtained keys including `temperature` (observed). Previously no test covered sampling fields in the Bedrock body.

Observed on old main code with the new tests: `Test run org.scholarchain.rag.runtime.LlmClientsSuite finished: 3 failed, 0 ignored, 11 total` (the three tests above). After the fix: `Passed: Total 11, Failed 0, Errors 0, Passed 11`.

## sbt check result (run once, after the fix)

```
Test run org.scholarchain.rag.runtime.LlmClientsSuite finished: 0 failed, 0 ignored, 11 total 0.12s
[info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=1634, contract_tests=336)
[success] Total time: 923 s (0:15:23.0), completed Nov 15, 2026, 8:42:42 PM
exit=0
```

## Prompt and model versions

Unchanged. No file under prompts/** changed, so no prompt version bump. Model IDs unchanged: `claude-haiku-5-5` (anthropic), `anthropic.claude-haiku-5-5` (bedrock), `fake-rule-template-v1` (fake).

## Evaluation

The benchmark was not run (decision 3: the fake path is unchanged; `--llm anthropic` is human-only).

## Known failure modes and grounding gaps (addition)

7. Answers are no longer sampled deterministically. Without `temperature`, the model uses its default sampling, so two runs of the same question can give different wording, claim selection or evidence citations. Correctness rests on the deterministic verifier: every number, identifier, edge and path is checked against the evidence bundle, unsupported or conflicting claims are removed, and after one retry the template fallback is used and recorded. Run-to-run differences in live answers are expected and are not a regression; the facts themselves come only from deterministic retrieval.

(Items 1-6 in docs/agent-runs/phase-09/agent-report-rag.md are unchanged.)

## Live benchmark command for the human (run from the repository root, never by agents)

Same as docs/agent-runs/phase-09/agent-report-rag.md line 314, with only `--out` changed. Verified in the repository: the config file, export directory, Neptune CSV directory, question file and BenchmarkRunner exist; the runner's flags are `--config --export --neptune-csv --questions --out --llm`; the config has `answerModelId: claude-haiku-5-5` and `apiKeyEnvVar: SCHOLARCHAIN_ANTHROPIC_API_KEY`; attempt-05-anthropic does not exist yet.

```
SCHOLARCHAIN_ANTHROPIC_API_KEY=... sbt -batch -J-Xmx12g "rag/runMain org.scholarchain.rag.runtime.BenchmarkRunner --config modules/rag/src/test/resources/config/rag-runtime-anthropic-v1.json --export modules/spark/target/phase-07-export/run=51b50d578d5b609842c1c21b8266a00b --neptune-csv modules/storage/target/phase-07-real/attempt-01/neptune --questions modules/rag/src/test/resources/benchmark/benchmark-v1.json --out modules/rag/target/phase-09-real/attempt-05-anthropic --llm anthropic"
```

Export the key in your own shell first; the leading assignment only shows where it goes. Note that R-5 is not fixed: if calls still fail, the run report will not count failures or fallbacks and will not stop early, so check `attempt-05-anthropic` for fallback records and token usage by hand.

Not in this repair: R-5 (run-report counting of failures, fallbacks and tokens; early stop). Not implemented.
