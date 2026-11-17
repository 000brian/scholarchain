# Phase 11 human gate: adversarial testing and full repository review

## Decision

**ACCEPTED, with recorded open items.** The spec gate is met after a second repair round that I requested: no critical or major verified finding remains. The open minor findings, P2 items and "proposed later" items have owners and deadlines (section "Remaining risk").

- Main run: Nov 16, about 18:20–23:24 local. `PHASE RESULT: COMPLETE_WITH_OPEN_ITEMS` (R3-01 major open).
- Cost of this phase: $0 (no AWS, Voyage, Anthropic or Bedrock call). My running total is unchanged.
- Repair round 2: Nov 16 23:43 – Nov 17 00:29 local. `PHASE RESULT: COMPLETE_WITH_OPEN_ITEMS` (only minor findings and one ratification for me). Reviewer verdict APPROVE.

## What I reviewed

- The work orders: `prompt.md` (sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1) and `repair-round-2.md`.
- The orchestrator summaries: `orchestrator-summary.md` and `round2-summary.md`, including the finding table, the decision-8 item table, the test-evidence check and the process deviations.
- Every report in this folder:
  - build: `agent-report-build.md`;
  - tests: `test-inventory.md`, `test-report.md`, `test-report-02.md`, `round2-test-report.md`;
  - review: `review-report-a.md` to `-d.md`, `review-report-02.md`, `review-report-03.md`, `round2-review-report.md`;
  - repairs: `repair-01-hadoop.md` to `repair-07-services.md`, `round2-repair-rag.md`, `round2-repair-er.md`, `round2-repair-hadoop.md`;
  - round-2 sbt logs (`round2-*.log`).
- Both diffs, with `git status` and a staged-diff scan for key-shaped strings and for my account ID before each commit. The only matches were placeholder values in sanitizer tests (for example the AWS documentation account `123456789012`).

## Spec checklist

### Testing work order (test-engineer)

| Item | Status | Source |
|---|---|---|
| Add adversarial tests without modifying production code | Done. Step 1b added 12 suites (62 tests) under `tests/**` only; production defects were left exposed with 16 `.fail` marks (T-01..T-10). | test-report.md |
| 1 Malformed and truncated DBLP XML | Covered (existing suites) | test-report.md |
| 2 Unicode normalization and confusables | Covered; new T-05 (lone surrogate at the HTTP boundary) | test-report.md |
| 3 Duplicate and conflicting DOI records | Covered (existing) | test-report.md |
| 4 Common author names, reordered lists | Covered; new MissingMetadataSuite ("Wei Wang" abstains, lists both) | test-report.md |
| 5 Very large blocking groups | Covered (existing) | test-report.md |
| 6 Unresolved or conflicting edge endpoints | Covered after step 3c (Spark F3 cases in KnownGraphSuite) | test-report.md, repair-03-spark.md |
| 7 Empty and disconnected graphs | Partly. Spark empty run (S1a), out-of-window works (S1b) and disconnected pairs (S3) pass in step 3c's KnownGraphSuite; a zero-researcher runtime export (plan item 16) not written | test-report.md, repair-03-spark.md |
| 8 Known-graph cases (degree, component, path, bridge, k-core) | Covered after step 3c (case sheet encoded; k-core cap 50). Sheet item T30 ruled wrong for Spark; the oversize check belongs to a Hadoop job-level test (later) | orchestrator-summary.md |
| 9 Large-team pair expansion; common-name resolution | Covered (contracts/hadoop; MissingMetadataSuite over HTTP) | test-report.md |
| 10 Missing topic, institution, abstract, profile metadata | Partly. Below-profileMinWorks researcher and OpenAlex-abstract absence not tested | test-report.md |
| 11 Duplicate vector keys, stale model versions | Partly. Module coverage only; runtime with a mismatched retriever spec (plan item 11) not written | test-report.md |
| 12 Prompt injection (title, abstract, full text) | Partly. Question and title injection covered over HTTP; no abstract or full text is stored, so those are covered only by absence | test-report.md |
| 13 LLM malformed JSON, fabricated identifiers | Covered (T-01, T-02, T-07) | test-report.md |
| 14 Unsupported answer claims | Covered (T-10) | test-report.md |
| 15 Neptune timeout | Covered with a stub SDK client (timeout gives "unknown", never "no path"; circuit opens and recovers) | test-report.md |
| 16 DynamoDB conditional-write conflict | Covered with a stub SDK client | test-report.md |
| 17 S3 Vectors throttling or transient failure | Covered with a stub SDK client (closed the RetryingVectorIndex NONE row) | test-report.md |
| 18 Replayed workflow and idempotency | Covered | test-report.md |
| 19 API authentication, authorization, malformed bodies, timeouts | Covered (T-03, T-04, T-05, T-06) | test-report.md |
| 20 Logging redaction | Covered (S-4 and C-02 fixed in step 3) | test-report.md, review-report-03.md |
| 21 Configuration omissions | Covered (T-08, T-09, U-6 fixed in step 3) | test-report.md |
| Run all tests that do not require live AWS | Done (closing check below) | round2-test-report.md |
| Bounded AWS integration tests under the approved profile | Not run: no approved profile in this phase (decision 1). The 3 `RequiresAws` suites (AwsAdaptersAwsSuite, LiveLlmAwsSuite, LiveProvidersAwsSuite) compile and run in Phase 14 | orchestrator-summary.md |
| Report exact counts, failures, coverage, residual risks | Done | test-report.md, test-report-02.md, round2-test-report.md |

### Review work order (code-reviewer)

| Item | Status | Source |
|---|---|---|
| Adversarial read-only review of the whole repository and diff, in the 12 listed areas | **Done in four slices, with partial file coverage** (A: hadoop, ER, datasets; B: spark, storage; C: rag, prompts, services; D: core, tests, build, docs). Each verdict covers only the files its reviewer read. Not read, or only searched: most module test trees (hadoop, ER, spark, storage, 10 of 12 core suites, 37 of 41 `tests/**` files); the dataset scripts and samples; rag `embedding/*`, BenchmarkRunner and RagIngestJob; a few storage helpers (S3KeyLayout, ItemCodec, IndexSpec, VectorMetadataCodec); `.claude/**` and `.mcp.json`. So area 10 ("tests that would pass even if broken") and area 11 (documentation claims) are only partly reviewed. Accepted as a risk (below) | review-report-a.md to -d.md, "Coverage" sections |
| Every finding verified, with severity, file:line, failure input, impact, owner | Done | review reports |
| APPROVE or REQUEST_CHANGES; no file edited | All four slices REQUEST_CHANGES; after repair, step 5 slice 1 APPROVE, slice 2 REQUEST_CHANGES (R3-01); after round 2, APPROVE. code-reviewer has no write tools | review-report-02.md, -03.md, round2-review-report.md |

### Repair loop

| Step | Status |
|---|---|
| 1 Orchestrator groups findings by owner | Done (step 2 triage: 18 repair-now IDs) |
| 2 Each owner gets only its findings | Done (compact work orders) |
| 3 Owner fixes and runs its gate | Done (steps 3a–3g, targeted tests with TEST EVIDENCE; round 2) |
| 4 Test engineer reruns adversarial tests | Done (step 4 and round-2 step 3; marks removed for fixed findings) |
| 5 Code reviewer rereads the complete diff | Done (step 5; round-2 step 4) |
| 6 Repeat until APPROVE or human escalation | Done: escalated to me after step 5; I ran round 2; APPROVE |

### Gate

| Gate item | Status | Source |
|---|---|---|
| No critical or major verified finding remains | Met. No critical finding at any point. 18 majors repaired in round 1 (A-01, B-01, B-02, C-01..C-05, D-01, D-02, T-01, T-08, T-10, plus U-6 and S-4 as P1) and ruled RESOLVED. R3-01 (new major in step 5) repaired in round 2 and ruled RESOLVED | review-report-02.md, -03.md, round2-review-report.md |
| Every production behavior has a relevant test | Met, with one known gap. Step 1a found 4 behaviors with no test; all 4 gained tests (RetryingVectorIndex, RetryingObjectStore, the GraphAnalyticsJob broadcast setting, Pipeline on a Hadoop FileSystem). Gap: `s3://` paths are untested (only `file://`) until Phase 13/14. The "no row without a test" reading is the orchestrator's, from the repair reports; test-engineer did not redo the inventory | orchestrator-summary.md |
| All tests pass under their declared profiles | Met. Closing `sbt check` after round 2: 2005 module tests, 398 contract tests, 0 failed. 3 skipped = the `RequiresAws` suites. Start of phase: 1877 and 336. Eight intentional `.fail` marks remain (T-02, T-03, T-04, T-05, T-06 ×2, Phase 5 F8, F9); no expected failure flipped | round2-test-report.md, round2-check.log |
| No reviewer wrote production code | Met. code-reviewer has no write tools. In round 2 the orchestrator saved the reviewer's returned text as round2-review-report.md | round2-summary.md |
| Repair records identify owners and rerun evidence | Met. Every source-changing agent gave a TEST EVIDENCE block; every named suite ran more than 0 tests; the orchestrator's grep of the XML test reports was empty for every changed module | orchestrator-summary.md |

## Items earlier gates sent to Phase 11

| From | Item | Status in Phase 11 |
|---|---|---|
| Phase 3 | Shared cross-record contract tests | Partly. WindowProvenanceCrossRecordSuite added (R2-01: some assertions weak); IdentityCrossRecordSuite (plan item 13) not written. Phase 16 |
| Phase 3 F4 | Contract tests wired where Phase 11 needs them | Done. contractTests now depends on storage, rag and services (step 0); Hadoop cases are in `contracts/hadoop`; the Spark cases live in Spark's own Scala 2.13 tests (KnownGraphSuite) |
| Phase 4 | Finding 2 (job reuse not verified) | Done (hadoop). The scripts must pass the git commit as code version: Phase 13 |
| Phase 5 | m5 (default key families) | Done (hadoop defaults `dblp-base-name,initials-surname`) |
| Phase 6 | F3 (missing-endpoint and team/pair checks), Spark adversarial tests, known graphs, empty and disconnected graphs | Done (KnownGraphSuite: F3a–F3e, I4, S1a, S1b, S3, T1–T30) |
| Phase 6 | F6, F9 parts 1 and 2, k-core cap | Done (F9 part 2 tested with `file://` only) |
| Phase 7 | m3 (two writers can leave a vector and its record out of sync) | **Not done** (not small). **The Phase 7 rule stays: the AWS load uses only one writer** (Phase 14) |
| Phase 7 | r1, r2 (test gaps); attack-style storage tests | Done (r1, r2; categories 15–17 with stub SDK clients) |
| Phase 9 | n-1..n-4 (filter edge cases, identity lookups) | n-3 done (tests); n-1, n-2, n-4 not done (proposed later, Phase 16) |
| Phase 9 | Validator rules (items 1–4) and the typed row decoder | Item 1 done. Item 2 (uncertainty note on partially_supported) deferred: it is a rag and domain contract change. Items 3 (defaultWindow helper) and 4 (sorted-key audit JSON) still proposed. Row decoder not done (unused unless rag switches to it) |
| Phase 10 | The two live-run questions | Answered before the run from attempt-06: rejections came from the numbers rule (20 of 21) and one cut-off answer; the wrong year verified was q08 claim 2 (a window-start year accepted as the activity year). Fixed by L-1, L-2, L-3 and U-10; R3-01 (a side effect) fixed in round 2. Live check: attempt-07 |
| Phase 10 | U-3, U-5, U-8, U-13 | Done (U-13: rag part; the storage JobStore comment was reported, not edited) |
| Phase 10 | U-6, S-4 (due before Phase 14) | Done |
| Phase 10 | S-3, S-6, S-7, S-9, S-10 | Done |
| Phase 10 | S-8 (overload can block new connections) | Not done: needs a design on the accepting side (Phase 16) |
| Phase 10 | QueryAudit v2 for a fuller explain | Done (domain, storage, rag, services) |
| Phase 10 | R-1 (after a 502, the same key gets 409) | Not changed (my decision). The API docs must say "after a 502, use a new key" (Phase 17) |
| Phase 10, not Phase 11 | S-5 (ECS stop timeout), U-11 (embedding counters before Phase 15), U-12 (Phase 16 limit note) | Still due at those phases; listed so they are not lost |

## Planned repair items (decision 8)

All P1 items are done and were ruled RESOLVED:
- **hadoop:** Phase 4 finding 2 (reuse verification); Phase 5 m5 (default key families).
- **spark:** known-graph suite; Phase 6 F6; k-core cap 50; F9 part 1; F9 part 2 (`file://` only).
- **domain:** U-6; QueryAudit v2 (left out: per-task HTTP attempts and retried attempts, which rag counts per process only).
- **storage:** QueryAudit v2 round trip.
- **rag:** L-1 (with L-4), L-2, L-3, U-10, U-3, QueryAudit v2 population, the fake benchmark.
- **services:** golden examples (15 frozen files equal the generated hashes), explain on QueryAudit v2, S-4.

P2 items:
- **Done:** U-8; Phase 9 item 1; r1, r2; U-5; U-13 (rag part); n-3; S-3, S-6, S-7, S-9, S-10.
- **Not done (proposed later):** storage Phase 7 m3 and the typed GraphRow decoder; rag n-1, n-2, n-4; services S-8.
- R-1 not changed (my decision).

Fake benchmark (local, fake model):
- After round 1: 110/110 graph values, 4/4 paths, 3/3 expected abstentions (none unexpected), 89/89 planted errors caught, prompt injection passed, claims 173 verified / 2 partial / 0 / 0 (same as Phase 10).
- After round 2: 89/89 planted errors, claims 173/2/0/0, no claim changed status.

## Human decisions in this phase

- Before the run (my answers to the prompt questions):
  - numbers fix: diagnose and fix both formats, with v3 prompts that drop bound numbers;
  - year check: fix both the planted-error test and the verifier;
  - U-3: hash the prompt versions;
  - build: wire contractTests to storage, rag and services test fixtures.
- R-1 kept as is, documented in Phase 17.
- **Repair round 2** for R3-01 and R2-03. Round 1 was the decision-8 round inside the main run; round 2 is human-requested.
- **R2-03: policy label `er-v2`.** The Phase 7 export stays labelled `er-v1` and was not touched. Stable IDs are unchanged. The version is part of the ER run ID and the LLM cache key by design, so cached `er-v1` LLM answers are not reused.
- **I ratify the `er-v1.json` revert** to its frozen Phase 5 bytes (sha256 matches the Phase 5 record). Current code no longer loads `er-v1`, so reproducing `er-v1` behavior needs the Phase 5–10 code. Reason: the file is the provenance of earlier runs and must not differ from what they used.
- **R3-01 known limit, accepted:** a wrong year equal to a cited metric value or rank still passes. Exposure is limited to metrics whose values can fall in 1900–2100 (weighted degree, occurrences, component or community size); about zero for edge weight, team size, hops and depth. Only the 2034 test fails without the fix; the 1999.5 test already passed (decimals were never treated as years) and stays as a guard.
- **D-02 build part not needed:** step 5 found the container loads prompts from the classpath.
- **T30 (Spark case sheet):** the reviewer's ruling accepted; the oversize check is a Hadoop job-level test (later).
- The minor and advisory findings and the "proposed later" lists are accepted as recorded in the two summaries.

## Remaining risk

| Item | Severity | Owner | Deadline |
|---|---|---|---|
| N-1: `--policy-version er-v1` still accepted, would label er-v2 output as er-v1 | minor | entity-resolution-engineer | before the first AWS ER run (Phase 15) |
| N-2: `Policy.erV1` returns er-v2 (kept so tests compile); misleading name | minor | entity-resolution-engineer | Phase 16 |
| N-3: no test of a number attributed to the wrong researcher | minor | test-engineer | Phase 16 |
| N-4: test label "er-v1-synthetic-override" now overrides er-v2 | nit | test-engineer | Phase 16 |
| `s3://` paths untested; code version not yet passed by scripts | gap | infra-engineer (scripts), spark, hadoop | Phase 13/14 |
| Live behavior of the Neptune, DynamoDB and S3 Vectors stubs' chosen exceptions | risk | storage | Phase 14 (`RequiresAws`) |
| Live LLM behavior after the verifier changes is not measured yet | risk | rag | attempt-07, after this gate |
| T-02..T-06 kept as `.fail` (minor) | minor | rag, services | Phase 16 |
| R2-01, R2-02, R3-02, R3-03, R3-07: weak contract tests | minor/adv | test-engineer | Phase 16 |
| Test plan items not written: 11, 12 (rest), 13, 15, 16, 3 (rest) | gap | test-engineer | Phase 16 |
| Other recorded minors and advisories (A-, B-, C-, D-, R3-) | minor/adv | per summary | Phase 16/17 |
| Abstract and full-text injection covered only by absence (none stored) | risk | rag | if abstracts are added |
| Partial review coverage (see the review work order): unread test trees, dataset scripts, rag embedding and benchmark code, `.claude/**` and `.mcp.json` | risk | code-reviewer | the final repository review before submission (Phase 17) reads them |
| Phase 7 m3: two writers can leave a vector and its record out of sync | minor | storage-engineer | the AWS load uses one writer (Phase 14) until it is fixed |
| Attempt-07 preconditions: run with no `SCHOLARCHAIN_CFG_*` variables set (D-01 now applies them); API key only in the variable named by `llm.apiKeyEnvVar` | note | me | attempt-07 |

## Process notes (disclosed)

- No approval prompt in either run. No AWS, Voyage, Anthropic or Bedrock call.
- The harness refused `sleep 240`; the orchestrator waited on completion notices and bounded loops. Phase 12's prompt uses that rule.
- test-engineer (steps 1a, 1b, 4) had no Grep or Glob tool and used read-only shell, including `cd`, one `find | xargs wc -l` and one `cat`. rag ran one read-only `od -c`. No writes, no prompts. Phase 12's prompt adds a fallback rule.
- Step 3a: hadoop reported BLOCKED-ON test-engineer (JobLevelBlockingSuite assumed the old one-family default); routed to step 4, not a stop.
- The main-session stop hook blocked ending a turn while the marker was stale (after step 0, and in round 2); the orchestrator waited for the closer's check instead of running sbt.
- Most owners argued "the test fails without the fix" from the old code instead of re-running it (restoring old code needs a writing git command). Step 5 checked these against the old behavior and accepted them. Observed pre-fix failures exist for A-01, k-core, B-01, T-08, T-09, U-6, S-4, Phase 4 F2, the golden examples, ReadRoutesSuite and (round 2) the 2034 test.
- Round 2: step 2b (hadoop XML label, text only) started just before step 2's completion notice; the closing full check covers both.

## Minor findings and advisories: decision on each

Fixed along with the majors (no decision needed): B-11, D-03, D-06 (decided: always write), D-08, T-07, T-09. All others below are recorded, not repaired in this phase. Owners and IDs are from `orchestrator-summary.md` and `round2-review-report.md`.

| IDs | Owner | Decision |
|---|---|---|
| A-02, A-05 | entity-resolution-engineer | Record; Phase 16 |
| A-03 | hadoop-engineer | Record; Phase 16 |
| A-04 (advisory) | dataset-engineer | Record; Phase 12/17 as the summary says |
| B-03, B-04, B-05, B-06, B-07, B-09; B-12, B-13 (advisory) | storage-engineer | Record; Phase 16 (B-13 fixed cause codes with the Phase 14 retries) |
| B-08, B-10 | spark-engineer | Record; Phase 16 |
| C-06, C-07, C-08, C-10, C-11 | rag-engineer | Record; Phase 16 |
| C-09; C-12, C-13 (advisory) | service-engineer | Record; Phase 16 |
| D-04, D-05 | domain-engineer | Proposed later; Phase 16 |
| D-07 (advisory) | build-engineer | Record; Phase 13 with the assembly work |
| D-09 (advisory) | test-engineer | Record; Phase 16 |
| D-10 (advisory) | documentation-engineer | Phase 17 |
| T-02, T-03, T-04, T-05, T-06 ×2 (`.fail` kept) | rag-engineer, service-engineer | Keep the marks; Phase 16 |
| R2-01, R2-02, R3-02, R3-03, R3-07 | test-engineer | Strengthen in Phase 16 |
| R3-04, R3-05, R3-08 | rag-engineer | Record; Phase 16 |
| R3-06 (advisory) | rag-engineer (test), build-engineer (assembly check) | Phase 13 with the assembly work |
| N-1, N-2, N-3, N-4 | as in "Remaining risk" | as in "Remaining risk" |

## What I could not verify

- I did not re-read every line of every report; I checked each gate item, the decision-8 items, the deferral list, the coverage sections and the new findings against the reports named above.
- "Every production behavior has a relevant test" rests on the orchestrator's reading of the repair reports; test-engineer did not redo the inventory.
- For most fixes, "the test fails without the fix" was argued from the old code, not re-run; step 5 accepted the arguments.
- Nothing here shows live behavior: AWS adapters, `s3://` paths and the live model run later (Phase 14, attempt-07).

## Disclosure

Claude (in a separate chat) helped me draft the Phase 11 prompt, the repair-round-2 prompt and this gate, and suggested the decisions marked as mine. I read them and adopted them; the decisions are mine.

## Manual changes

None to code or tests. I wrote both prompts, this file and `commit.txt`, and copied the reports here with `cp -n`.

## Acceptance
I accept Phase 11 as of commit 29661e98c4791b3e59a1c52c641cbea6bf609df1 and 5031a532a4f6e2ccf7e636425b3cec1caba9c538.
