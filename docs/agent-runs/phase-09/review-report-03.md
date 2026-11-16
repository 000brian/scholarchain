# Phase 9, repair 2: code review re-check of R-4 (review-report-03)

Reviewer: code-reviewer (read-only: Read, Grep, Glob). Scope: R-4 only, as set by docs/agent-runs/phase-09/prompt-02.md, decision 3.

## Verdict

APPROVE

## What was checked

1. **Both bodies go through the fixed builder.**
   - `AnthropicMessages.body` (modules/rag/src/main/scala/org/scholarchain/rag/runtime/Llm.scala:95-103) now sets only `model` (if given), `anthropic_version` (Bedrock only), `max_tokens`, `system` and `messages`.
   - `AnthropicClient.complete` calls it at Llm.scala:153 with `bedrock = false`.
   - `BedrockClaudeClient.complete` calls it at Llm.scala:261 with `bedrock = true` and passes the result unchanged to `invoker.invoke` (Llm.scala:265).
   - I searched all of `modules/` for `temperature|top_p|top_k|topP|topK`. In rag main code the only hit for the sampling fields is the scaladoc at Llm.scala:123. The `topK` hits are vector-retrieval and ranking parameters, not model sampling fields.
   - Nothing else in `modules/rag/src/main` builds a Messages or InvokeModel body. The other `invoke` callers are the Titan embedder (BedrockTitanEmbedder.scala:96), which is unrelated.

2. **The tests fail on the old code and cover both providers.**
   - The old test (scratchpad LlmClientsSuite.orig.scala:77-80) asserted `b("temperature").num == 0.0` and the key set `{model, max_tokens, system, messages, temperature}`. That confirms the old builder sent `temperature`.
   - New test "R-4: neither the Anthropic nor the Bedrock body sends temperature, top_p or top_k" (LlmClientsSuite.scala:82-110) checks, in three ways, that the body contains no sampling field and has exactly the expected keys:
     - a request through `AnthropicClient` on a stub transport;
     - a request through `BedrockClaudeClient` on a stub invoker;
     - direct calls to the builder for both `bedrock` values.
   - The exact-key-set checks also catch any sampling field added later.
   - The existing Anthropic shape test now expects exactly `{model, max_tokens, system, messages}` (line 77). The Bedrock test gains an exact key-set check `{anthropic_version, max_tokens, system, messages}` (line 225).
   - Against the old builder, which wrote `o("temperature") = 0` on both paths, each of these three tests fails. This matches the "3 failed" run that the repair report says it observed on the old code.

3. **No unrelated change.**
   - Comparing the old and new test files: the only edits are lines 77-80 (inverted), the new test at lines 80-110, and the added assertion at line 225. All other tests are identical.
   - In Llm.scala, endpoint, headers, retry and backoff, the limiter, key scrubbing, parsing, model-ID checks and the factory all read the same as the behaviour documented in docs/agent-runs/phase-09/agent-report-rag.md:302-309, apart from `temperature`. The `AnthropicClient` scaladoc (Llm.scala:121-128) now says no sampling field is sent.
   - Limitation: there is no pre-change copy of Llm.scala, and Phase 9 is not committed, so I checked "nothing else changed" in main code by reading the file against the documented behaviour, not with a diff.

4. **repair-02.md contents.**
   - Lines 37-42 list the human-verified fields: model, max_tokens, system and messages on the request; `content[].text`, `usage.input_tokens` and `usage.output_tokens` on the response.
   - Line 73 adds known failure mode 7: answers are no longer sampled deterministically, and correctness rests on the deterministic verifier.
   - Line 82 gives the live command with `--out modules/rag/target/phase-09-real/attempt-05-anthropic`. It matches agent-report-rag.md:314 except for `--out`.
   - Lines 85-87 say R-5 is not fixed and remains open.

5. **sbt check log** (scratchpad sbt-check-r4.log):
   - format check and ledger check pass (lines 6-10);
   - LlmClientsSuite: 0 failed, 11 total (line 125);
   - contract tests: 336 executed, 336 passed, 0 failed (line 9709);
   - module tests: 1634 executed, 1634 passed, 0 failed, 3 skipped (line 9745);
   - `[success] Total time: 923 s` (line 9747) and `exit=0` (line 9748);
   - no failing test lines in the log.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| m-1 | minor | docs/agent-runs/phase-09/agent-report-rag.md:305 | A reader looks up the request shape in the Phase 9 rag report | That report still shows `"temperature":0` in the body. repair-02.md replaces it, but anyone reading the original report alone gets the old request shape. | Human architect (docs/agent-runs/** is human-owned). Suggest a note in the human gate record that repair-02.md replaces the report's request-shape section. |
| m-2 | minor | modules/rag/src/main/scala/org/scholarchain/rag/runtime/Llm.scala:89 | Reading the `AnthropicMessages` scaladoc | It still says "(verify in the live benchmark run)", but the human has now verified the request and response fields live. The comment is out of date; behaviour is unaffected. | rag-engineer (next phase; not in this repair) |

No critical or major findings.

## Residual risks (not findings)

- No live check has shown that Bedrock accepts the body without `temperature`. Bedrock on-demand quota is 0, and repair-02.md states this.
- R-5 (counting model-call failures and fallbacks in the run report, and stopping early) is still open and goes to Phase 10. The human must inspect attempt-05-anthropic by hand.

---

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-09/prompt-02.md (Phase 9, repair 2), re-check of R-4 only
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run by the reviewer. The log sbt-check-r4.log (orchestrator scratchpad) shows module tests 1634/1634 passed, contract tests 336/336 passed, `[success]`, exit=0.
VALIDATION RESULTS: grep of modules/ finds no sampling field in any request body. Both clients build their body only through AnthropicMessages.body (Llm.scala:153, :261). The old test file confirms the old body sent `temperature`.
ASSUMPTIONS: there is no pre-change copy of Llm.scala, so "nothing else changed" in main code was checked by reading the file against agent-report-rag.md:302-309, not with a diff.
RISKS: Bedrock without temperature is not verified live. R-5 is still open.
MANUAL ACTION REQUIRED: the human saves this text as docs/agent-runs/phase-09/review-report-03.md, then runs the live command in repair-02.md (line 82, attempt-05-anthropic).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human live run to attempt-05-anthropic and the human gate. Minor findings m-1 (human architect) and m-2 (rag-engineer, next phase) are recorded and not repaired.
