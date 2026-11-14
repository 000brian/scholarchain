# Repair 01: entity-resolution-engineer, test-engineer findings F1-F4

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: entity-resolution-engineer

## Summary

I fixed F1–F4 inside `modules/entity-resolution/**`, each with a feature test. `sbt check` fails only at the contract-test stage, on the one pinned F3 test you predicted. Module tests: 995 of 995 passed. Contract tests (no AWS): 184 of 185 passed. Both LlmAdversarialSuite tests for F1 and F2 now pass (29 of 29 in that suite). Because the check did not pass, `.claude/.last-test-run` was not refreshed, so this report is BLOCKED-ON test-engineer.

One decision you should confirm with the human: for F3 I added a new er-v1 rule, `name.canonical-equivalent`. The version stays er-v1 (reason under F3).

```text
STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2), repair round for test-engineer findings F1-F4 in modules/entity-resolution/**
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/PriorSpellingSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/GuardPathSuite.scala
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/llm/Adjudication.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/llm/LlmStage.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/run/Report.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErRun.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/data/Loader.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/data/Model.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/review/ReviewQueue.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/cluster/Clustering.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/policy/Decide.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/scala/org/scholarchain/er/policy/Policy.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/main/resources/er/policy/er-v1.json
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/LlmSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/RunSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/entity-resolution/src/test/scala/org/scholarchain/er/ClusteringSuite.scala
COMMANDS RUN:
  read-only inspection (ls/find/grep/sed/cat/head/tail/wc/shasum, git rev-parse)
  sbt -batch "entityResolution/test"   (run twice: first a test-compile error in my RunSuite, fixed; then 72/72 passed across 11 suites)
  sbt -batch check   (background; completed in 1476 s; exit 1)
  shasum -a 256 modules/entity-resolution/src/main/resources/er/policy/er-v1.json
TEST RESULTS:
  sbt check: ledger and format checks passed; compile passed.
  Module tests: suites=40 executed=995 passed=995 failed=0.
  Contract tests (no AWS): suites=15 executed=185 passed=184 failed=1 errors=0 skipped=0.
    The only failure is IdentityAdversarialSuite "decision 5 check: precomposed and decomposed spellings form ONE dblp-prior cluster" (IdentityAdversarialSuite.scala:290, ComparisonFailException). This is the expected pinned F3 test.
    LlmAdversarialSuite 29/29 passed, including F1 "duplicate JSON keys with conflicting values are rejected, not resolved last-wins" and F2 "a cache file answers every pair: no adjudicator call, identical decisions".
  .claude/.last-test-run was not updated (sbt reported this).
VALIDATION RESULTS: no real-data run (as instructed). Gold-set evaluation not run (no labels; run 2).
ASSUMPTIONS:
  - "Exact dblp name string" means AuthorMention.name byte for byte. The core contract already guarantees collapsed whitespace, so whitespace variants cannot occur.
  - Canonically equivalent spellings are the same text under the shared core rule (author-name-key/1). So an accepted deterministic decision may join them, subject to every merge guard.
RISKS:
  - If J06 runs with skipSameNumberedName on, it skips pairs whose names share a fullNameKey and carry a homonym suffix. NFC/NFD variants of one numbered name then get no candidate. They stay separate clusters (allowed by decision 5), and the review queue's dblp-prior sample does not cover them. I expect this to be rare in dblp (its XML is normally NFC), but it is not measured.
  - The F3 change will change real-run counts: dblpPriorClusters, mergedBeyondDblpPrior, and the new name.canonical-equivalent decisions.
MANUAL ACTION REQUIRED:
  - Human: confirm the F3 reading (exact-string prior plus the new rule name.canonical-equivalent, deterministic, accepted, confidence 0.95). The alternative is no rule, so variants would be scored probabilistically.
  - Orchestrator: route the pinned IdentityAdversarialSuite test to test-engineer, then rerun sbt check.
BLOCKED-ON OWNER: test-engineer
BLOCKED-ON EVIDENCE: tests/contracts/src/test/scala/org/scholarchain/contracts/er/IdentityAdversarialSuite.scala:286-295 asserts researcher(nfA,0) == researcher(nfB,0) and cluster_prior_clusters == 1 for NFC/NFD "René Priorname" with no candidate. Under the decision-5 exact-string prior these are two clusters that do not join without an accepted decision. sbt check log line: "==> X ...IdentityAdversarialSuite.decision 5 check: precomposed and decomposed spellings form ONE dblp-prior cluster ... IdentityAdversarialSuite.scala:290".
NEXT GATE: test-engineer updates the pinned test, then sbt check (the entity-resolution part is expected to pass), then code-reviewer, then the real run from approved code under new attempt roots.
```

## Per-finding fixes

**F1: duplicate JSON keys.**
- `llm/Adjudication.scala` gains a `StrictJson` reader. It wraps the ujson.Value visitor and refuses any object, at any nesting level, that repeats a key, including inside arrays.
- `OutputValidator.validate` now uses it. A duplicate key gives reason `duplicate-key` with detail = the key (at most 64 characters). The pair ends UNRESOLVED with `llm-output-rejected:duplicate-key`, the reply is added to the rejected replies (hash only), and nothing is cached.
- Tests in `LlmSuite`:
  - "replies with a repeated key at any nesting level are rejected, never read last-wins". It also asserts that plain `ujson.read` would have read the accept.
  - "LLM stage: a duplicate-key reply ends UNRESOLVED with llm-output-rejected:duplicate-key".
- Without the fix these tests fail: `StrictJson` does not exist, and the reply is accepted.

**F2: the report counted cache answers as sent.**
- In `run/Report.scala`, `llm.pairsSentToAdjudicator` now counts only pairs not answered from the cache. Two fields are added: `pairsSelected` (the old value) and `pairsAnsweredFromCache`. The ErRun log line says the same.
- `RunSuite` test "run report: pairsSentToAdjudicator counts only real adjudicator calls; cache answers are reported apart". It reruns with the first run's adjudications as the cache and an adjudicator that throws if called. It expects 0 sent, and answered-from-cache equal to selected.

**F3: dblp prior keyed on NFD, and names shown as one spelling.**
- (a) Prior clusters:
  - `data/Loader.scala` now keys prior clusters on the exact `AuthorMention.name` (homonym suffix included, no normalization). `NameTable.display` is renamed `spelling` and holds that exact string.
  - Name features and merge guards still use the shared core keys (`NameParts`).
  - In `policy/Decide.scala`, `dblp-prior.same-name` now fires only on identical exact strings.
  - New rule `name.canonical-equivalent` (pair, deterministic, accepted, 0.95): different strings with equal full-name keys. That accepted decision is what may join the two prior clusters. The assignment records `cluster.merged` and `cluster_prior_clusters=2`.
- (b) Spelling shown per mention: the review queue (CSV and JSONL), the LLM input `name`, and `Corpus.mentionName` now show each mention's own spelling. `clusters.jsonl` `dblpNames` lists every spelling the cluster holds.
- Policy text for `dblp-prior.same-name` and `dblp-prior.cluster` is updated, and the docs in Clustering, Model and Decide are updated.
- Version: stays **er-v1**. My versioning rule (and decision 11) requires a new version only when configuration overrides a frozen policy. er-v1 is still being finalised within run 1, and it ends this run final.
- Tests:
  - `ClusteringSuite`: the old NFC/NFD test is rewritten to expect 2 clusters, plus two new tests (joined only by an edge; suffix kept in the exact string).
  - New `PriorSpellingSuite` (5 tests): prior per exact string; the same-name vs canonical-equivalent rules; merge recorded through the accepted decision; spellings stay separate without a decision; queue, LLM input and clusters show each spelling.

**F4: guard rules declared a path the decisions did not use.**
- I chose to change the declaration, not the behaviour, so each decision still names the path that produced its evidence. All four `cluster.guard.*` rules in `er-v1.json` now declare `"path": "inherited"` and `"confidence": null`. The descriptions say the refused decision keeps the match path, confidence and rules of the pair decision whose accepted evidence was refused, plus the guard rule.
- `Policy.validate` now:
  - allows only exact_id, deterministic, probabilistic, llm, any and inherited as paths;
  - requires cluster-guard rules, and only those, to declare `inherited`;
  - requires guard confidence to be null.
- New `GuardPathSuite` (3 tests):
  - the er-v1 declarations;
  - a policy file giving a guard a fixed path or confidence, or an unknown path, is rejected;
  - a run where `orcid.exact` is refused by the disambiguation guard stays on `exact_id` with rules `[cluster.guard.dblp-disambiguation-profile, orcid.exact]` and orcid.exact's confidence. Every guarded decision's path equals the declared path of one of its evidence rules.

## tests/** tests expected to change (owner test-engineer)
- **Must change:** IdentityAdversarialSuite "decision 5 check: precomposed and decomposed spellings form ONE dblp-prior cluster" (lines 286-295). It pins the old NFD prior. Under the exact-string prior, nfA and nfB (NFC/NFD "René Priorname", no candidate) are two clusters, and each assignment has `cluster_prior_clusters=1` with different ResearcherIds.
- **Should change:** the suite header comment and the line-49 comment ("the dblp prior under canonical equivalence") describe the old behaviour. They do not fail.
- **No other tests/** test fails.** These pass unchanged:
  - IdentityAdversarialSuite / DecisionContractAdversarialSuite exact_id+UNRESOLVED guard tests: the behaviour is unchanged; only the declaration changed.
  - LlmAdversarialSuite band-cap test, which expects `pairsSentToAdjudicator` = 3 with no cache.
  - NameKeyAdversarialSuite.

## Policy
- Policy version: er-v1 (status final). Thresholds are unchanged: accept 0.9, reject 0.3, LLM band [0.6, 0.9), llmMinConfidence 0.8, nearThresholdLogit 0.5.
- New er-v1 policy file sha256: `366a59ab82dff4805c522d5a8ccecc6a5e5e455267f8d6f05fe09aa9a4b18ca9`. Previous: bdd2180a…9282.
- Metrics, confusion matrices and error analysis: not applicable in run 1 (no labels).

## Updated real-run command
The command itself is unchanged. Only the code-version string and the output root must be new, and both runs must come from approved code:
```text
sbt --mem 8192 "entityResolution/runMain org.scholarchain.er.run.ErMain \
  --input <new J09 export run=... directory from the rerun Hadoop attempt> \
  --output-root modules/entity-resolution/target/phase-05-real/attempt-NN/ \
  --decision-time 2026-11-14T00:00:00Z \
  --seed 20261114 \
  --code-version phase05-<git short HEAD>-<attempt>-dirty \
  --llm-model-id none/always-abstain \
  --llm-max-per-run 1000 \
  --adjudicator always-abstain \
  --queue-size 300 \
  --queue-prior-pairs 30"
```
- Use a new attempt-NN; do not reuse attempt-01. The new policy and code hashes change the run ID, and the old root would be refused or reused only if its manifest matches.
- Full `sbt check` log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a78572bd-c42b-4db3-bd71-b2631a490ee2/scratchpad/check.log
