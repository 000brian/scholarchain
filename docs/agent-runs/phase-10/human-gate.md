# Phase 10 human gate: REST microservices

## Gate

**ACCEPT.** The REST services work and are tested. The reviewer approved the final repairs. The last `sbt check` passed: 1877 module tests and 336 contract tests (it was 1634 module tests before this phase). The live benchmark also worked better: the answer step fell back to the template on 5 of 30 questions, down from 21 in Phase 9. Two open questions from that run are left for Phase 11 (decision 9).

## Spec checklist

| Item | Status | Source |
|---|---|---|
| POST /v1/query (check the request, query ID, idempotency, sync or async) | Done. An idempotency record is made only when the request sends an `Idempotency-Key` | agent-report-service |
| Jobs API (state, times, stage, errors, result link) | Done | agent-report-service |
| Evidence API (source, link, path or text pointer) | Done. The link is the dblp page, or a `scholarchain:` reference | agent-report-service |
| Graph API with limits | Done. Only depth 1; other depths and edge types get 422 | review-report-02 |
| Explain API | Partly done. It shows what the stored audit has. Template IDs, indexes and retry counts need QueryAudit v2 (later). There is no combined score, and the answer says so | prompt 7g |
| /healthz and /readyz | Done and kept separate | review-report-02 |
| /metrics ("when the deployment allows") | Not built; CloudWatch in Phase 12 | agent-report-service |
| JSON, /v1/ paths, request and trace IDs | Done. One trace ID follows a query through every step | review-report-02 |
| Idempotency, paging, timeouts, retries, circuit breakers | Done | review-report-02, -03 |
| Typed errors | Done. Every error class in the spec is produced and tested | review-report-02 |
| Login and no secrets in answers or logs | Done: two API keys, safe comparison, won't start with a missing or repeated key. Logs: see S-4 | review-report-02 |
| Thin routes | Done | review-report-02 |
| The 10 required tests | All present | review-report-02 |
| `sbt check` and the required report items | Done | agent-report-service, repair-03 |
| **Gate:** routes don't do retrieval | Met | review-report-02 |
| **Gate:** errors carry trace IDs | Met | review-report-02 |
| **Gate:** health checks show no secrets | Met | review-report-02 |
| **Gate:** examples match the tests | Met: 15 saved examples equal the real server output exactly | review-report-02, -03 |
| **Gate:** one end-to-end test reaches an answer backed by evidence | Met | review-report-02 |

## Results

- **Tests:** 1877 module tests (243 new; 92 for services) and 336 contract tests, all passing.
- **Fake benchmark:** every target met (graph values 110/110, paths 4/4, abstentions 3/3, planted errors 89/89, injection passed, plans 30/30). 173 claims verified; 2 are now "partially supported" because they have nothing to check. That was expected.
- **Live benchmark** (run by me, `claude-haiku-5-5`, 534 s):
  - 76 model calls, none failed.
  - **Template fallbacks: 5 of 30** (Phase 9: 21). 21 answers were rejected on the first try; 16 passed on the retry. Only 1 of the 21 was cut off by the token limit.
  - Graph values 110/110, paths 4/4, names 30/30, abstentions 3/3, injection passed. Plans 24/30 (same as Phase 9).
  - Claims: 111 verified, 19 partly supported, 1 unsupported, 5 conflicting. (Phase 9's higher count came mostly from template answers.)
  - **Planted errors: 85 of 86 caught.** One wrong year was marked verified (decision 9).
- **Cost:** Anthropic ~$0.06 (decision 10). Voyage $0. AWS $0.

## What I reviewed

The prompt, the five agent reports, the three repair reports, the three review reports, both benchmark reports, instructor-approvals.md, and the spec's Phase 10, REST API, error and logging sections.

## What happened

- Build, core, storage and rag each passed `sbt check`. Then service-engineer worked while the reviewer checked the earlier steps.
- The reviews found four major problems:
  - U-1: a failed evidence or audit save was ignored.
  - U-2: no test proved shutdown closes anything.
  - S-1: async answers lost their notes.
  - S-2: one idempotency case had no reliable test.

  All four were fixed and approved.
- **Delays:** rag-engineer finished at 00:46 but stayed "waiting" for about 3.5 hours because of a log watcher that never ends. The orchestrator also stopped whenever its turn ended while an agent was still running. I restarted it by hand a few hours later, early in the morning. Two memory-file approvals also needed me. About 4 hours were lost.

## Decisions

1. **New `--graph-run` option** for AWS: accepted. The AWS stores can't find the graph run without a network call.
2. **Prompt version chosen in code** (v2 by default; `--prompts v1` replays Phase 9): accepted for now. A config setting for it comes later (with U-3).
3. **S-1 fixed in storage, then services**, instead of accepting "async answers have no notes": accepted.
4. **service-engineer's 8 changes:** accepted, as the reviewer ruled. An unknown node's neighbour list returns an empty 200; the API docs (Phase 17) must say so.
5. **storage's changes** (clock parameter, `Either` results, stage writes, write tokens, expired items read as missing): accepted.
6. **rag's choices** (early stop only for live models, case-insensitive name suffix, extra whitespace allowed around JSON): accepted.
7. **Read-only key name** differs between the core and services test files: accepted. Both are test placeholders.
8. **R-1** (after a 502, retrying with the same key gets 409 JOB_FAILED): accepted, as the prompt said. The API docs must say "after a 502, use a new key". A change can be considered in Phase 11.
9. **Two open questions from the live run, for Phase 11 (before the Phase 16 evaluation):**
   - Which claim with a wrong year was marked verified, and why.
   - Why 21 answers were rejected when only 1 was cut off. The error texts are in the per-question files of attempt-06. U-10 goes with this.
10. **Cost:** I record ~$0.06 for Anthropic charges. I have used ~$0.12 total so far.

**Minor findings:**

| Finding | Decision |
|---|---|
| U-3 query ID ignores the prompt version | Phase 11, with decision 2 |
| U-5 a model can stay open after a failed start | Phase 11 (fakes only today) |
| U-6 a bad queue URL is printed in the error (could show the account ID) | Fix before Phase 14 |
| U-7 stage writes change the job version | Already handled |
| U-8 a service key could reuse the LLM key's variable | Phase 11 |
| U-9 `.DS_Store` files | Leave out of both commits |
| U-10 retry after a cut-off answer uses the same limit | With decision 9 |
| U-11 embedding counters not exact under load | Fix before Phase 15 measurements |
| U-12 one matching name is enough for "verified" | Note as a limit in Phase 16 |
| U-13 two doc comments | Phase 11 |
| S-3 rare case: 200 now, JOB_FAILED on retry | Phase 11 |
| S-4 logs can include store error text, like a host name | Fix before Phase 14 (first CloudWatch logs) |
| S-5 shutdown can take too long | Phase 12, with the ECS stop timeout |
| S-6 thin-route test could skip silently | Phase 11 |
| S-7 keys checked after the slow data load | Phase 11 |
| S-8 overload can block new connections | Phase 11 |
| S-9 circuit breaker edge case | Phase 11 |
| S-10 POST ignores query-string values | Phase 11 |
| S-11 explain counts at most 1000 runs | Accept |

## Manual changes

No code changes. I copied the reports, ran the live benchmark from a separate tab with the key from Keychain, and copied both benchmark reports into this folder.

## Deferred

- QueryAudit v2 for a fuller explain: Phase 11.
- /metrics: Phase 12.
- Jobs limited to their caller, limits for requests without a key, longer-job leases, deeper graph search, an OpenAPI file: later.
- Phase 12: decide Step Functions or SQS only (the spec's technology table names both).
- New prompt rule: no log watchers that never end.

## Remaining risks

- Nothing ran on AWS yet; queue, tables and roles are tested only with stubs.
- A job that runs longer than its lease can run twice (documented).
- The live run used local stores and fake embeddings.
- The Bedrock request is still untested live (quota 0).

## Acceptance
I accept Phase 10 as of commit 4d9db84202e0180b2fa7a7945acde62523763d71.
