# Phase 9 code review (code-reviewer, read-only)

## Summary

**VERDICT: REQUEST_CHANGES**

The verdict rests on three major findings, all in rag-engineer territory (`modules/rag`). I found no blocking findings, and none in core or storage.

- **R-1:** Identity uncertainty is hidden for conflict-flagged researchers who were not looked up by name. The real benchmark output shows it.
- **R-2:** Two of the verifier's "not a number" rules let a wrong number come out `verified`.
- **R-3:** The model's free-text summary is shown without verification, and its digit filter can be bypassed with a newline.

Each needs one repair round with a test that fails without the fix. The minor and advisory items are listed for the human only.

The rest of the phase holds up:
- **Storage:** A1, m1, m2 and A2 are done as specified, each with a test that would fail without the fix.
- **Core:** the contracts match decision 3.
- **Rag:** query text never comes from user text, A6/A7 hold, failed model output is kept only as hash and length, and the API key is handled correctly.
- **Benchmark:** the numbers in the rag report match `modules/rag/target/phase-09-real/attempt-01/run-report.json` exactly:
  - plan accuracy 30/30 and identity 30/30;
  - graph values 110/110 and paths 4/4;
  - recall@10 0.98 / 0.98 / 1.0;
  - claims 175 verified, 0 of every other status;
  - 3 of 3 expected abstentions and 89 of 89 planted errors caught;
  - 1,612,421 of 3,361,084 COAUTHORED_WITH rows kept;
  - ResearcherMetricCache 508,448 items (5,000 RM4);
  - heap 3,927,835,072 bytes;
  - questions sha256 `b58c5e1a…3ac`.

I could not run commands. Test pass counts come from the agents' reports. I checked the code and the run output, not test execution.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| R-1 | major | `modules/rag/src/main/scala/org/scholarchain/rag/runtime/ResearcherResolver.scala:79-83`; `QueryWorkflow.scala:178-187`; `Synthesis.scala:371-376`; `Stages.scala:183-209, 629-657` | Any ranking, bridge, path-hop or neighbour answer that includes a conflict-flagged researcher. Real run: `q-04.json:77` flags Philip S. Yu (`researcher:v1:5d6029542b54c1717977ae66c695e403`) as identity outcome conflict. `q-19.json:891,901` then presents him as "Rank 1 by other communities connected in 2015-2025 … connects 298 other communities", `verified`, `"uncertainty": null`. | The uncertainty map holds only researchers resolved from names. Researchers coming from RM4 rankings, bridge rankings, path hops and neighbours are never checked for identity outcome. A merged CONFLICT cluster can inflate breadth and bridge values, so the top bridge result here is exactly the over-merge risk the Phase 6 gate warned about ("CONFLICT clusters look as confident as normal ones unless the conflict flag is checked"). This breaks CLAUDE.md "Identity uncertainty is reported, not hidden" and decision 6 ("conflict-flagged researchers carry an uncertainty note"). Fix direction: read `identityOutcome` (for example with `ResearcherById`, which already returns it) for every researcher named in kept facts, and attach the note. Add a fixture test with a conflict-flagged researcher in a ranking. | rag-engineer |
| R-2 | major | `modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala:47-48, 141-144` | (a) Citing Alice's C=37 metric item: `"In 2015-2025, Alice Smith has 99/100 distinct coauthors."` `SlashTokenRe` (`\S*/\S*`) removes `99/100` before the number check. The years (2) and the name (1) pass and nothing is left uncovered, so the claim is `Verified`. (b) `"In 2015-2025, the distinct coauthors of Alice Smith (99)."` `VenueNumRe` (a capitalized word followed by ` (nn)`) removes `(99)`, so again `Verified`. No bound number is needed: `numbers[]` is optional for text numbers. | A wrong number is labeled `verified`, so the verifier can be fooled by ordinary formats a live model may use (ratios, "Name (n)"). The spec allows skipping digits only inside stable IDs, claim IDs, dblp homonym suffixes and venue names. The "anything with /" rule goes beyond that, and the venue rule matches any capitalized word. The existing tests (`ClaimVerificationSuite.scala:276-300`) check only the positive side of these exclusions. Fix direction: drop slash tokens only when they are a key of a cited item or match `conf/`/`journals/`-style keys, and drop `(n)` only after a venue name of a cited item. Add negative tests for (a) and (b). | rag-engineer |
| R-3 | major | `modules/rag/src/main/scala/org/scholarchain/rag/runtime/Synthesis.scala:325-352`; `QueryWorkflow.scala:260`; `Schemas.scala:77` | Model output `{"summary":"Overview.\nAlice Smith has 99 coauthors and bridges every community.", …}`. `Digit = ".*\\p{Nd}.*".r` with `matches` does not cross `\n` (no DOTALL), and `hasControl` allows `\n`, so the sanitizer passes it. Even without a newline, `"Luc Van Gool is the strongest bridge between all communities"` (no digits, no IDs) passes. | The summary is printed first in `answerText` (`QueryWorkflow.scala:260`) with no evidence IDs, no verification and no label. It can carry numerical claims (with the newline bypass) and name or path claims (always). The same applies to `missingEvidence` lines. This fails the spec gates "Every numerical and path claim has evidence IDs" and "Unsupported claims are removed, weakened, or explicitly labeled". Fix direction: use DOTALL or a `find`-based digit and ID check, and also reject (or replace) a summary that contains any name not in the bundle's graph items, or mark it explicitly as an unverified overview. Add tests for the newline case and the name case. | rag-engineer |
| m-1 | minor | `ClaimVerification.scala:159-168, 198-204`; `ClaimVerificationSuite.scala:124-129` | Citing C=37, unbound text `"In 2015-2025, Alice Smith has 38 distinct coauthors."` gives `partially_supported` and the claim is kept. Also, a claim with no checkable atom that cites a graph item (for example `"She is the key broker between all communities."`) is `verified` vacuously, because `checked` = 0 and nothing is uncovered. | The wrong number is shown, but with a "Partially supported: …" note, so it is labeled. A wrong year, by contrast, is `conflicting`, which is inconsistent. The test asserts only "not Verified". Purely qualitative claims are verified without any check. Suggestion: when a claim cites graph items, treat an unbound non-year number that matches none of their values as `conflicting`, and require `checked > 0` for `verified` on claims that are not explanatory. | rag-engineer |
| m-2 | minor | `ResearcherResolver.scala:156-158` | `"Wei Wang 0003"` when the folded key has exactly one candidate, `Wei Wang 0010` (or a plain `Wei Wang`) | It resolves to a researcher whose dblp name contradicts the suffix the user asked for. The resolution note shows the chosen name, so this is not silent, but it is a wrong-identity risk that decision 5c leaves open. Suggestion: if the asked name has a suffix that matches neither `matchedValue` nor `displayName` of the sole candidate, treat the name as unknown. | rag-engineer |
| m-3 | minor | `modules/rag/src/test/scala/org/scholarchain/rag/runtime/LiveLlmAwsSuite.scala:30-32, 62`; `Llm.scala:187`; `rag-runtime-anthropic-v1.json:92` | One live Anthropic test under repeated 429/529: 2 structured attempts × (1 + `maxRetries` 3) = up to 8 HTTP requests. Bedrock: up to 4. | Decision 5d says "at most 3 requests each". The tests only assert the count after the requests are made. The report's live bound "30 × (1+1) × 2 = 120 requests" also ignores HTTP retries (worst case 480). Cost stays well inside the $5 budget, about $1.2 at worst. Suggestion: copy the settings with `maxRetries = 0` in the live tests, and correct the bound in the report. | rag-engineer |
| m-4 | minor | `QueryWorkflow.scala:70-71`; `modules/storage/src/main/scala/org/scholarchain/storage/runtime/RuntimeReaders.scala:404-415` | Reload the stores with a new Spark run but the same config, then ask the same question. | The query run ID follows the decision-5a formula, which does not include the graph run. Evidence and bundle IDs are therefore reused, so `putBundle` reports EB1 and EB3 conflicts and keeps the old content. The workflow ignores `itemConflicts` and the bundle `Conflict`, so a stored bundle can disagree with the response. Suggestion: surface conflicts in the result or answer, or (the human's call) add the graph run to the fingerprint. | rag-engineer (surfacing); human (formula) |
| a-1 | advisory | `BenchmarkRunner.scala:142-150` | A step that hangs | The 30-minute stop is checked after the step returns, so it does not interrupt a hung step. Live calls are bounded by `requestTimeoutMillis` and retries, so the practical risk is low. | rag-engineer |
| a-2 | advisory | `benchmark-v1.json:1266, 2340` | "working on WSDM" / "profiles mention SIGIR" | The topic questions use venue names, because profiles hold no topic words. "Published in WSDM" would be refused as a venue filter, while "working on WSDM" is ranked by lexical profile similarity. The answer key's name filter (`SimpleName`) also makes questions that RulePlanner can parse, so plan accuracy 30/30 against the fake does not predict live planner accuracy. Both points are documented honestly. | rag-engineer (report wording only) |
| a-3 | advisory | domain report, COMMANDS RUN | One `sed -i ''` edit to `modules/core/src/test/scala/org/scholarchain/core/WireContractSuite.scala` | It breaks the "sed only as `sed -n`" command rule. **Ruling: it does not matter for the artifacts.** The edit was inside domain-engineer's own territory, it added only `Example("QueryAudit", queryAudit)`, the later scalafmt check and `sbt check` passed, and no other file was touched. Record it as a process deviation. Related: the frozen v2/QueryAudit wire files were produced from the encoder's own output and then frozen. That is acceptable for snapshot-style wire freezing, but they are not an independent oracle. | domain-engineer (process note only) |

## Rulings, one by one

### Phase 9 spec gate
1. **User text cannot become arbitrary database code: PASS.**
   - The plan draft is an allowlist: unknown keys, operations, windows and metrics are rejected (`Schemas.scala:114-219`).
   - Graph access is only typed `GraphQuery` values, validated by `GraphQuery.validate` (`GraphPorts.scala:168-196`): IDs must be well formed, the window must match `[a-z0-9][a-z0-9-]*` and be configured, and limit and depth are bounded.
   - `CypherTemplates` puts only validated integers and the validated window literal into the query text; IDs stay `$parameters` (`NeptuneModel.scala:48-82`).
   - Tests: `AwsEnvironmentSuite.scala:469-475` and `QueryWorkflowSuite.scala:211-227`.
2. **Ambiguous names do not silently resolve: PASS.** `Resolution.Ambiguous` is never chosen. Candidates are listed with their context, and the plan records a lookup step marked `not-executed` (`QueryWorkflow.scala:505-545`; test `QueryWorkflowSuite.scala:119-134`; real run: q28 "Yang Liu", 76 candidates). There is an edge case with a mismatched suffix (m-2).
3. **Every numerical and path claim has evidence IDs: FAIL until R-3 is fixed.** Kept claims always cite valid evidence (`ClaimVerification.scala:198-204`), but the unverified summary can carry numerical or path statements.
4. **Unsupported claims are removed, weakened or labeled: FAIL until R-2 and R-3 are fixed.** Removal and labeling work for claims the verifier catches (`QueryWorkflow.scala:188-202, 273-280`), but R-2 lets a wrong number through as `verified`.
5. **Prompt injection tests pass: PASS (per the agent's sbt output).** The tests exist and are meaningful: `PlannerAndSchemaSuite.scala:201`, `QueryWorkflowSuite.scala:211, 233, 268`. In the real run, q30 had the plan as expected and 10/10 values.
6. **Graph retrieval and semantic retrieval evaluated separately: PASS.** `run-report.json` has `deterministicGraphRetrieval` and `semanticRetrieval`, the latter labeled "fake lexical embedder, not semantic".
7. **Code review returns APPROVE: not yet (REQUEST_CHANGES).**

### Decision 6 items
- **No user text reaches query text: PASS.** This covers the planner allowlist, template compilation, the window inlined as a quoted literal only after the pattern and configured-label checks, and the integers. Researcher names go only to `EntityLinkReader` (a folded DynamoDB key) and topics only to the embedder.
- **Ambiguous names never resolve silently: PASS** (m-2 aside). **Conflict-flagged researchers carry an uncertainty note: FAIL (R-1).** This holds only for name-resolved researchers (q04, q06), not for ranked, bridge, hop or neighbour researchers (q19).
- **Every number, path and identity claim has evidence IDs: PASS for claims, FAIL for the summary (R-3).**
  - **Semantic context never supports these: PASS.** A semantic-only claim with a number or graph word is `unsupported`, and a number bound to semantic context is not covered (`ClaimVerification.scala:179-180, 192-204`).
  - **Hop counts never come from vector metadata: PASS.** Hops are `researchers.size - 1` (`RuntimeModel.scala:277`). Vector metadata supplies only `sourceEvidenceId`, and the item it points to must have the asked pair as endpoints (`Stages.scala:379-402`). The tamper test sets `pathLength` = 99 and the hop count stays 3.
- **The verifier is deterministic and not ceremonial: PARTIAL.** It is plain code with no model involved, and its tests fail for wrong bound numbers, years, hops, fabricated IDs and semantic misuse. But R-2 shows two bypasses, and m-1 shows weak assertions.
- **Failed model text is stored only as hash and length, and no reasoning is stored: PASS.**
  - `ModelAttempt.invalid` keeps sha256, length and the error (`RuntimeModel.scala:183-200`).
  - Validation errors report unknown keys by count only (`Schemas.scala:33-36`).
  - Both prompts forbid reasoning and none asks for it.
  - QueryAudit has no reasoning field (core test).
- **API key and secrets: PASS.**
  - The key is read only from `env(settings.apiKeyEnvVar)` (`Llm.scala:219-223`).
  - The client's `toString` hides it.
  - It is replaced in error text before truncation (`Llm.scala:150-151`).
  - The core loader stores only the variable name.
  - The benchmark preflight only checks that the variable is present.
  - I found no key, token, real account ID or bucket name in the changed sources, configs or run output.
  - **Placeholder account ID 123456789012** in `rag-runtime-local-v1.json` and `rag-runtime-anthropic-v1.json` (and the core and storage test files): **allowed.** It is AWS's documentation example number, core's required `aws.accountId` key needs a value, and the human already ruled it allowed in `docs/agent-runs/phase-07/human-gate.md:121`. Bucket names are `example-*`, and the endpoint is `.invalid`.
- **`RequiresAws` tests excluded from `sbt check`: PASS.** Every live test is tagged (`LiveLlmAwsSuite.scala:23, 43`; `AwsAdaptersAwsSuite.scala:35-133`), and check excludes the tag (`build.sbt:95, 378, 494`). **Bounded: minor gap (m-3).**
- **A1: PASS.** `AwsStorageEnvironment` is built from `StorageConfig` plus SDK clients. Building it makes 0 HTTP calls (`AwsEnvironmentSuite.scala:182`), each store reaches its own stub client (`:194`), and stores are refused without the runtime or neptune section (`:228`). Rag's `--stores aws` path is voyage only, with stub-store tests.
- **m1: PASS.** The query text says `LIMIT <limit+1>`, then `take(limit)` and `truncated = rows > limit` (`NeptuneModel.scala:117-165`, `GraphPorts.scala:227-228`). The test asserts the `LIMIT 5` text and both outcomes (`AwsEnvironmentSuite.scala:333-358`).
- **m2: PASS.** `AwsClientSettings` refuses `maxAttempts` other than 1 (`AwsSupport.scala:30-33`), and the retrying decorators are the only retry layer (`RetryingStores.scala`). A real DynamoDbClient over a stub transport throttled twice makes 3 HTTP calls and counts 2 retries; with the old setup the test would see 0 retries and fail (`AwsEnvironmentSuite.scala:246-295`).
- **A2: PASS.**
  - `updateSingleCardinalityProperties` is removed and `userProvidedEdgeIds` is TRUE (`GraphPorts.scala:19-29`; `NeptuneAdapters.scala:46-57`; test `:448-467`).
  - Parallelism stays MEDIUM.
  - New tests check no duplicate `:ID` and no null/NaN/Infinity typed cell (`NeptuneCsvSuite.scala:246, 257`).
  - The literal-limit and window-literal rules are in place.
  - The runbook notes are in the storage report.
- **A6/A7: PASS.** The Spark path evidence is used when the pair and window match, otherwise `PathBetween`. Every hop gets its edge from `CoauthorEdgeBetween` (at most 5 supporting works). Tests: `StagesSuite.scala:67`, `QueryWorkflowSuite.scala:85, 101`. Real run: q10 and q11 come from `precomputed-spark-path` with the same researchers as Spark.
- **The answer key does not come from the code under test: PASS.** `AnswerKeyGenerator` imports only core and storage's export-file reader (`AnswerKeyGenerator.scala:8-15`). It computes rankings, neighbours and hops itself and never touches the runtime stores or the workflow. The benchmark sha256 matches the run report.
- **Ownership: PASS.** The git status snapshot shows changes only under `modules/core/**`, `modules/storage/**` and `modules/rag/**` (plus `prompts/**` per the rag report), apart from `.DS_Store` noise. No `build.sbt`, `tests/**`, `modules/services/**` or constitutional file changed (greps of `tests/` and `modules/services/` for the new types found nothing). Each report lists only files in its owner's territory. I could not run `git`, so the untracked part of the status was not listed in full.

## Report contract

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-09/prompt.md, decision 6 (review of decisions 3, 4, 5)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run by the reviewer; agent-reported sbt check exit=0 (module_tests=1629, contract_tests=336) accepted as their claim only; benchmark run-report.json numbers verified against the rag report (all match)
VALIDATION RESULTS: 3 major (R-1, R-2, R-3), 4 minor (m-1..m-4), 3 advisory (a-1..a-3); no blocking
ASSUMPTIONS: ownership judged from the session-start git status snapshot and the agent reports (no git access); Philip S. Yu's conflict flag taken from the workflow's own q-04 output
RISKS: R-2/R-3 matter most for the human's live Anthropic benchmark run; run it after the repairs
MANUAL ACTION REQUIRED: human decides on m-4 (whether the graph run joins the query run fingerprint) and accepts or defers the minors
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: rag-engineer repair round for R-1, R-2, R-3 (each with a failing-without-fix test and sbt check), then code-reviewer re-check of those three only
```
