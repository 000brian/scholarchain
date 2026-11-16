# Phase 9 human gate

## Gate
| Spec gate item | Result | Evidence |
|---|---|---|
| User text cannot become database code | PASS | Allowlisted plans; typed graph queries; only validated integers and window labels in query text (review-report.md) |
| Ambiguous names do not silently resolve | PASS | Identity 30/30 in both runs, 0 silent choices; q28 "Yang Liu" abstains |
| Every numerical and path claim has evidence IDs | PASS | R-3 fixed in repair-01; unverified summaries are replaced or labelled "not verified" |
| Unsupported claims are removed, weakened or labelled | PASS | R-2 and R-3 fixed in repair-01; live run removed 3 claims and labelled 2 |
| Prompt injection tests pass | PASS | sbt check passed; q30 kept its plan and 10/10 values in both runs |
| Graph and semantic retrieval evaluated separately | PASS | Separate sections in run-report.json |
| Code review returns APPROVE | PASS | review-report-02.md (after repair-01) and review-report-03.md (after repair-02) |

**Result: PASS.**

## Spec checklist
All ten runtime stages, the required controls, sbt check, the benchmark and the report are done (agent-report-rag.md, repair-01.md, repair-02.md). Decisions 3 (core contracts) and 4 (storage, including A1, m1, m2 and A2) are done and passed review. Known limits:
- there is no institution data, abstracts or topic index;
- communities are approximate, with no betweenness;
- local semantic search uses a fake embedder.

repair-02.md replaces the "Request/response shape" section of agent-report-rag.md, which still shows `temperature`.

## Results
| Measure | Fake LLM (attempt-03-fake-final) | Real model, claude-haiku-5-5 (attempt-05-anthropic) |
|---|---|---|
| Plans fully correct | 30/30 | 24/30 |
| Identity resolution | 30/30 | 30/30 |
| Graph values exact | 110/110 | 109/110 (q26 abstained) |
| Paths vs Spark | 4/4 | 4/4 |
| Expected abstentions | 3/3 | 3/3, plus 1 unexpected (q26) |
| Planted errors caught | 89/89 | 83/83 |
| Claims: verified / partial / unsupported / conflicting | 175 / 0 / 0 / 0 | 174 / 2 / 1 / 2 |
| Synthesis fallbacks | not applicable | 21/30 |
| Model requests; tokens in / out | 0 | 83; 172,980 / 104,623 |

The benchmark file (sha256 b58c5e1a…3ac) is unchanged. Heap was 3.93 GB.

| Cost | Amount |
|---|---|
| AWS | $0 (not used) |
| Voyage | $0 (fake embedder) |
| Anthropic | about $0.06 (console); the token counts give $0.07 |
| **Total** | **about $0.07** |

## What I reviewed
All Phase 9 reports, read in full:
- prompt.md and prompt-02.md;
- the three agent reports;
- repair-01.md and repair-02.md;
- the three review reports;
- the live run report.

I also checked the failed live run's errors myself, and ran three direct test requests to the API.

## What happened
1. The three agents finished. Review asked for changes: R-1 (hidden identity conflicts), R-2 (verifier gaps) and R-3 (unverified summary). Repair-01 fixed all three, and the re-check approved.
2. The first live run (attempt-02-anthropic) cost $0. All 60 calls were rejected because the request sent `temperature`, which this model doesn't accept, and every answer used the template fallback. The instruction to send it was in my own work order (decision 5d). Repair-02 removed it, and the re-check approved.
3. The second live run worked, but synthesis fell back to the template on 21 of 30 questions. All 44 rejected outputs were "not valid JSON". The likely cause is answers cut off at the 2,048-token limit (they averaged about 1,970 tokens). The answers are still correct, because fallback answers come from verified evidence.
4. In q26, the model read "SIGIR" as a venue filter and abstained.

## Decisions

| Item | Decision |
|---|---|
| R-5: a live run with failed calls still says `completed`, with no counts | Phase 10, first item |
| Synthesis fallbacks 21/30 | Phase 10: record stop reasons, raise the output limit or shorten the format, strip code fences, then one live re-run |
| q26 topic vs venue | Phase 10 planner prompt; the benchmark stays frozen |
| Review m-1 (unbound wrong number becomes partially supported) | Phase 10 |
| Review m-2 (homonym suffix mismatch) | Phase 10 |
| Review m-3 (live tests can make up to 8 requests) | Accept |
| Review m-4 (query run ID ignores the graph run) | Add the graph run ID to the fingerprint and surface bundle conflicts; Phase 10 |
| Review a-1, a-2, a-3 (hung-step timer; venue-worded topic questions; one `sed -i` edit) | Record; no effect on results |
| Re-check n-1..n-4 (filter edge cases, identity lookups) | Phase 11 |
| Repair-02 re-check m-1 (old request shape in the rag report) | Done: this gate notes the replacement |
| Repair-02 re-check m-2 (outdated code comment) | Phase 10 |
| Storage choices (no graph-read retries, rank gaps, stale ranking items) | Accept; revisit in Phase 14 |
| Proposed-later items | Fallback counts: Phase 10. Validator rules and the row decoder: Phase 11. Retries and cleanup: Phase 14. Others deferred. |

## Manual changes

- No code changes.
- I saved prompt-02.md and copied the reports.
- I updated instructor-approvals.md with the synthesis model, and noted that the approval was given to another student with the same Bedrock restriction.

## Deferred
- **Phase 10:**
  - R-5;
  - the synthesis fix and a live re-run;
  - q26;
  - review m-1, m-2 and m-4;
  - repair-02 re-check m-2.
- **Phase 11:** n-1..n-4.
- **Phase 13:** a live Neptune path check.
- **Phase 14:** storage retries and cleanup.

## Remaining risks
- Most live answers are template fallbacks until Phase 10.
- R-5 is open.
- Live answers vary between runs, because no sampling setting is sent.
- Bedrock and the Neptune path template are untested on live services.

## Acceptance

I accept Phase 9 as of commit 9a9174dffecf8a37ed7105d3533a507881079a27.

## What I could not verify

- Whether the invalid JSON was cut off or wrapped in extra text (stop reasons are not recorded).
- The full sha256 of review-report-03.md.
- Test counts, beyond the agents' logs.
- The exact Anthropic charge.
