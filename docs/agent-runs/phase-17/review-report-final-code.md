[Saved verbatim by the orchestrator from code-reviewer's final message (F-2).]

# Review report F-2: final repository review, application code and tests

Reviewer: code-reviewer. Read-only: I used only the Read, Grep and Glob tools.
Work order: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc5…ec8ac24, as given; I did not recompute it). I applied decision 15 (the F-2 bullet, the finding format, the IDs and the checklist), the standing rules, and the spec's 12 review areas (course-specification.md:1710-1722), in order.

## VERDICT: APPROVE (F-2 slice)

- No critical or major finding is verified in this slice. All 7 new findings (P17-F-10 to P17-F-16) are minor. One existing item, P16-A-08, is still present at minor.
- **Limit of this verdict:** decision 6 says the final reviewer cannot return APPROVE while P16-L-10 or P16-L-11 is open. R-1 decides whether those two are closed; I did not repeat that work.
  - I read all of ClaimVerification.scala. The Phase 17 rule (`AbsenceRule`, :869-1268) implements items a and b.
  - I probed it with a lowercase unknown participant, a capitalised third name, an unnegated "unconnected", and a name placed in a different clause from the absence wording. None of these led to `verified`.
  - This APPROVE stands only if R-1 also finds both items closed.
- The final review as a whole stays REQUEST_CHANGES because of F-1's major P17-F-01. The work order also says that accepting a major as a known limitation does not change that verdict.

## Findings

| ID | Sev | File:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| P17-F-10 | minor | modules/core/src/main/scala/org/scholarchain/core/config/ConfigLoader.scala:712 (`vectors.queryTopK` is checked only as > 0); modules/storage/src/main/scala/org/scholarchain/storage/aws/S3VectorsIndex.scala:135-150 (one QueryVectors call, no `nextToken` loop). This is step L R1 / SL-12, SL-13. | `SCHOLARCHAIN_CFG_VECTORS_QUERY_TOP_K=150`. The topic ranking uses this value unchanged (Stages.scala:961-966, via `ctx.vectorTopK`). | Per the step-L report (not re-checked by me), one page holds at most 100 results. A topic-filtered ranking would then silently rank at most 100 candidates, while its note says "top 150 profiles" (Stages.scala:437). Latent: course.json:58 sets 30. Fix options: cap the value at 100 in validation (a core contract change, domain-engineer) or page in the adapter. | storage-engineer (adapter); domain-engineer if the fix is a validation bound | yes if the core bound is chosen; else no |
| P17-F-11 | minor | modules/storage/src/main/scala/org/scholarchain/storage/load/StorageLoadMain.scala:364-365 (`failed: ${e.getClass.getSimpleName}: ${e.getMessage}`), :380-383 (written to run-report.json and printed to stdout). Masking.scala:14-23 masks only bucket names and account IDs. | `neptune-bulk-start` or `neptune-bulk-status` hits a Neptune connect, DNS or timeout failure. The wrapped SDK message names the cluster endpoint host. | A real endpoint host can reach the step log (CloudWatch) and run-report.json, against the identifier rule. RetrievalEvalJob.scala:309-314 itself says "store and SDK messages can hold hosts or ARNs" and records only the class name there. StorageLoadMain does not. Verified: the code path. Not reproduced offline: the SDK message text itself. | storage-engineer | no |
| P17-F-12 | minor | modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala:642-643 (same pattern), :660 (printed), :650 (copied to S3) | An AWS-store ingest fails with an SDK exception. | Same gap as P17-F-11, with lower exposure: S3, DynamoDB and S3 Vectors hosts are public regional hosts, and bucket names and ARN account IDs are masked. It is still inconsistent with RetrievalEvalJob's rule. | rag-engineer | no |
| P17-F-13 | minor | modules/storage/src/main/scala/org/scholarchain/storage/neptune/BulkLoadRequests.scala:10 (step L R3) | A reader relies on "`failOnError = TRUE`: a bad row stops the job instead of loading a partial graph". | Per the step-L report, AWS keeps the data loaded before the error, so the comment claims an all-or-nothing load that does not exist. It is a documentation claim the implementation does not support (area 11); behavior is unchanged. | storage-engineer | no |
| P17-F-14 | minor | modules/storage/src/main/scala/org/scholarchain/storage/StorageLimits.scala:3 ("Service limits that storage enforces in code") and :31-33 (`MaxWriteRequestsPerSecond`, `MaxVectorsPerSecondPerIndex`; step L R5) | Grep across modules/**/src: both constants are referenced nowhere else. | The object says it enforces limits that no code enforces. Ingest relies on retry with backoff instead. Overstated documentation (area 11). | storage-engineer | no |
| P17-F-15 | minor | modules/spark/src/test/scala/org/scholarchain/spark/JsonlExportSuite.scala:197-208, against JsonlExport.scala:369-376 (the disagreement check) and :685-689 (the count check) | Delete the disagreement check at :372-375. The test raises `nodes:venue` records by 1 in neptune.json, the first target, which `merged` keeps. The export then fails at :686 with the same `ExportCountMismatchException`. | The test passes even if the disagreement check is removed (area 10). It asserts neither the message ("disagree") nor that nothing was written before the refusal. | spark-engineer | no |
| P17-F-16 | minor | tests/contracts/src/test/scala/org/scholarchain/contracts/runtime/PromptInjectionCrossModuleSuite.scala:42-57 | Change `Prompts.asData` so it no longer neutralises the question tags, or put the question after `</question>`. | The test is named "reaches the planner model only inside the data block", but it checks only that the system prompt lacks the text and that `user.indexOf(...) > 0`. It would pass if the question escaped the `<question>` block (area 10). | test-engineer | no |

## Known items checked again

- **P16-A-08:** still present, minor (proposed later).
  - `QueryTrace.wrap` builds a graph port that overrides only `settings` and `run` (Stages.scala:106-114).
  - `runClassified` on a traced port therefore falls back to `Unclassified` (GraphPorts.scala:339-340).
  - rag has no `runClassified` caller, so this is latent.
- **H-03:** resolved in the working tree. The rule text now says "a derivation of this evaluation set from the pool, not a benchmark label" (RetrievalEvalSet.scala:90; benchmark-v1-eval-set.json:8). Only the rule text changed; the targets did not (diff-R-1.txt:100-125).
- **U-12:** a design limit and a DOC item. I saw nothing new beyond it.

## Decision 15 checklist (F-2)

- **Decision 6's rule as specified and monotonic:** checked, none found.
  - The Phase 17 block (ClaimVerification.scala:586-712) only adds to `conflicts`, `unbacked` and `uncovered`. It never changes `checked`.
  - The status function (:851-859) can only stay or fall when one of those sets grows.
  - Recognition (:1200-1253) and third names (:1256-1259) follow items a and b. The closed vocabulary (:1262-1267) follows item g. Positive eligibility (:650-711) follows item f.
  - `verifyClaimPhase16` (:336-340) is the baseline for the monotonic tests.
  - Detail of P16-L-10 to L-15: R-1.
- **No production limit, threshold, prompt, default or golden file changed to improve a number:** checked, none found.
  - The Phase 17 production diffs are the eval-set rule text (json:8, RetrievalEvalSet.scala:90) and the verifier rule, which only lowers statuses.
  - No file under prompts/** changed. No change to a services golden resource shows in git status.
- **Each behavior change has a test that fails without it:**
  - Phase 17: AbsenceRuleSuite and VerifierMonotonicSuite exist (untracked). Detail: R-1.
  - The 73 historical files: not re-proven per change; that was covered by their phase reviews.
  - Weak tests found in the sample: P17-F-15, P17-F-16.
- **Release-manifest script:** not in the F-2 slice; covered by F-1's checklist.
- **env.sh zsh / bash 3.2:** not in the F-2 slice; F-1 checked env.sh:88-106.
- **No file under infra/terraform/** changed:** checked, none. git status shows none.
- **Secrets and identifiers:** checked.
  - Grep of modules/**/src/main and prompts/** for 12-digit numbers, amazonaws.com hosts, AKIA keys and sk-ant keys: no match except the descriptive comment at Models.scala:79.
  - Sample tests use made-up hosts and the documented placeholder account ID (RuntimeRepairsSuite.scala:46-49, NeptuneTimeoutStubSuite.scala:23).
  - The open gaps are P17-F-11 and P17-F-12.

## Spec review areas (in order)

1. **Correctness and data loss:** P17-F-10. Otherwise checked, none:
   - put-if-absent and append-only writes;
   - VerifiedLines and verifyAll before writes;
   - the BlockingJob oversize streaming;
   - the J06 shard conflict reject.
2. **Contract and schema drift:** none. QueryAudit v1/v2/v3 read-write gating (Records.scala:803-831) agrees with Validator.scala:1048-1122 and SchemaVersions.scala:47-84.
3. **Artifact ownership:** none. The prompts live in prompts/runtime (rag-engineer); the build maps them to generated resources (Prompts.scala:131-135).
4. **Security, secrets, injection:** P17-F-11, P17-F-12. Otherwise checked:
   - the Voyage key is redacted (`scrub`, a key-free `toString`);
   - prompt data blocks are neutralised (Prompts.scala:235-240);
   - graph templates are allowlisted (GraphPorts.scala:190-218);
   - ResponseGuard and the Redactor are in place.
5. **Hadoop and Spark distribution:** none. Checked:
   - J05 secondary sort and grouping;
   - J06 sharding;
   - JsonlExport repartition and in-file sort;
   - RunFs through the Hadoop FileSystem.
6. **ER over-merging and unreported uncertainty:** none. Checked:
   - frozen-version guard (Policy.scala:91-105, 128);
   - the `evaluatedWith` note (:145-164);
   - the IntervalReliability unreliable / partly-design-fixed flags;
   - the cluster-level evaluation kept apart from pair decisions.
7. **Storage idempotency and provenance:** P17-F-13. Otherwise checked, none:
   - the registry state machine (StorageLoadMain.scala:608-761);
   - the ingest skip rule;
   - the re-embed completion records;
   - content-addressed text keys.
8. **RAG grounding:** P16-A-08 (existing). Otherwise none:
   - the semantic-context-only guard (ClaimVerification.scala:838-855);
   - the summary and uncertainty sanitising (Synthesis.scala:348-404).
9. **API behavior and failure handling:** none. Checked:
   - breaker generations (Support.scala:84-203);
   - timedRead (QueryService.scala:87-125);
   - GraphReads counting;
   - fixed error texts;
   - readiness deadline and cache.
10. **Tests that pass when broken:** P17-F-15, P17-F-16. The other 19 sampled suites assert concrete values or texts and the refusal side effects.
11. **Documentation claims:** P17-F-13, P17-F-14.
12. **Cost and cleanup:** none. The pools are bounded (BoundedPool max 64; service pools have fixed sizes and bounded queues); there are rate limiters (RateLimiter); RagIngestJob has step and disk guards; StorageLoadMain allows one writer on AWS.

## Coverage

**Slice (a), read in full (12/12):** rag `embedding/` BedrockTitanEmbedder, EmbeddingModelSpec, EmbeddingProviders, FakeLexicalEmbedder, RateLimiter and VoyageEmbedder; `ingest/RagIngestJob`; `runtime/BenchmarkRunner`. storage `dynamo/ItemCodec`, `s3/S3KeyLayout`, `vectors/IndexSpec` and `vectors/VectorMetadataCodec`.

**Slice (b), all 73 listed paths** (files-F-2-changed-since-p11.txt):
- **68 read in full at the listed path.** I skipped none on the strength of a later review. Later coverage sections (p15 review-report-a.md:112-128, p16 a1:154-179, a2:89-109) show that several of these files changed after they were read, so I read all of them myself.
- **5 listed paths do not exist in the working tree:** `modules/rag/src/main/resources/org/scholarchain/rag/prompts/runtime/{answer-synthesis-v1,-v2,-v3,query-planner-v1,-v2}.md`.
  - They were deleted in the diff range (git diff --name-only lists deletions); git status shows no " D".
  - Their single source is now `prompts/runtime/<same names>.md`, generated onto the classpath (Prompts.scala:131-135; copies under modules/rag/target/**).
  - I read all five `prompts/runtime/*.md` in full. I treat these 5 as moved files, not unreadable ones, so the review is not `failed`. The orchestrator may want to correct the list.

**Slice (c), 21/21 suites read in full:**
- hadoop: ScannerSuite, ScholarChainHadoopSuite, SettingsSuite
- entity-resolution: ReviewKeyRepairSuite, RunSuite, WorkResolutionSuite
- spark: JsonlExportSuite, KnownGraphSuite, PipelineFixtureSuite
- storage: load/S3ExportSourceSuite, load/StorageLoadMainArgsSuite, load/StorageLoadMainLocalSuite
- rag: runtime/RuntimeRepairsSuite, RuntimeServiceSuite, StagesSuite
- services: RoleConfigParitySuite, RouteTimeLimitSuite, WorkerSuite
- tests/contracts: runtime/MissingMetadataSuite, NeptuneTimeoutStubSuite, PromptInjectionCrossModuleSuite

**Also read:**
- prompt.md:60-109, :190-229, :295-309, plus Grep of decision headers;
- course-specification.md:1700-1741;
- routing-p17.md (full); phase-16/human-gate.md:125-154; review-report-final-control.md (full);
- agent-report-limits-storage.md (Grep of R1/R3/R5, SL-12/13);
- diff-R-1.txt:100-129 (header list and the eval-set and RetrievalEvalSet hunks);
- StorageLimits.scala (full); BulkLoadRequests.scala:1-30; Pipeline.scala (spark):360-389;
- the coverage sections of phase-14 review-report-a/b, phase-15 review-report-a, and phase-16 review-report-a1/a2;
- Grep of config/*.json for the queryTopK and batch values.

**Not read:**
- the ClaimVerification diff hunks in diff-R-1.txt beyond the headers (R-1's scope; I read the final file in full);
- `.tfstate`, `.terraform/`, `plans/`, `evidence/`, `~/.aws` (forbidden).

No file in the slice was unreadable.

## Report contract

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17 session 2, review F-2 (final repository review, application code and tests), docs/agent-runs/phase-17/prompt.md decision 15 (sha256 c22adfc5…ec8ac24 as given)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (static review; no tests run)
VALIDATION RESULTS: 0 critical, 0 major, 7 new minors (P17-F-10 to P17-F-16); existing P16-A-08 still present (minor); H-03 resolved in the working tree; checklist passed except as noted
ASSUMPTIONS: The S3 Vectors 100-results-per-page limit and the Neptune failOnError behaviour are taken from the step-L report (marked verified there), not re-checked against AWS documentation. That SDK failure messages can name the Neptune host is taken from the project's own statement at RetrievalEvalJob.scala:309-310, not reproduced. The 5 missing prompt paths were treated as moved to prompts/runtime, not unreadable.
RISKS: This APPROVE depends on R-1 finding P16-L-10 and P16-L-11 closed (decision 6 fail-closed rule). The overall final-review verdict stays REQUEST_CHANGES while F-1's P17-F-01 is open.
MANUAL ACTION REQUIRED: none for this slice. The orchestrator may correct files-F-2-changed-since-p11.txt (5 deleted prompt paths).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 5 code triage (merge with R-1 and F-1); all F-2 findings are minor and are recorded under the major-only repair rule
```
