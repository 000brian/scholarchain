# Phase 9, repair 2: remove `temperature` from the model request

This is a short repair inside Phase 9, run in a fresh orchestrator session. Read docs/agent-runs/phase-09/prompt.md first: its standing rules and file ownership apply unchanged. Those rules include:
- `sbt check` before COMPLETE;
- only sbt, read-only git and read-only shell commands;
- no AWS and no network: never run `--llm anthropic` and never run `sbt awsTest`;
- evidence goes to the scratchpad, then one-line `cp -n` commands with sha256.

Nothing from Phase 9 is committed yet. Do not commit.

File names: repair-02.md (rag-engineer) and review-report-03.md (code-reviewer).

## What happened (human evidence, Nov 15, 2026)

- The human's live benchmark run (`modules/rag/target/phase-09-real/attempt-02-anthropic/`) made 60 model calls. All 60 returned `HTTP 400 invalid_request_error: "`temperature` is deprecated for this model."` (for example request_id req_011Ch5oSc8173f9MkcrZEXTs). Every question then used the fake fallback.
- The human checked with direct requests to the same endpoint, key and model (`claude-haiku-5-5`):
  - a minimal request: HTTP 200, with `usage.input_tokens` and `usage.output_tokens`;
  - the same request plus `system`: HTTP 200;
  - the same request plus `"temperature":0`: HTTP 400 with the message above.
- `temperature` is the only cause. The Phase 9 work order (decision 5d) told rag to send it; that instruction was wrong.

## Human architect decisions

### 1. Agents and order
1. rag-engineer: R-4, one repair round.
2. code-reviewer: re-check of R-4 only. APPROVE required.

### 2. R-4 (major): the model request sends `temperature`

- Remove `temperature` from the Anthropic Messages request body and from the Bedrock InvokeModel body.
- Send no other sampling field (`top_p`, `top_k`).
- Nothing else changes.
- **Test:** neither body contains `temperature`, `top_p` or `top_k`. The test must fail on the old code.
- In repair-02.md:
  - list the request fields verified live by the human: model, max_tokens, system, messages, `content[].text`, `usage.input_tokens` and `usage.output_tokens`;
  - add one known failure mode: answers are no longer sampled deterministically, and correctness rests on the deterministic verifier.

### 3. Speed limits
- Run `sbt check` once, after the fix.
- Do not run the benchmark: the fake path is unchanged.
- The re-check covers R-4 only.
- Do not change attempt-01, attempt-02-anthropic or attempt-03-fake-final.
- Print the exact live command for the human with `--out modules/rag/target/phase-09-real/attempt-05-anthropic`.

### 4. Not in this repair (goes to the Phase 10 prompt)
R-5: the run report must count model-call failures, fallbacks and tokens. A live run where calls fail must not report `completed`, and a run must stop early when the first calls are all rejected.
