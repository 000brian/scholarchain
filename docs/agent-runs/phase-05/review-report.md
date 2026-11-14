# Review report: code-reviewer

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: code-reviewer, review 1 (REQUEST_CHANGES: M1, M2, m1-m8)

VERDICT: REQUEST_CHANGES

I found two major defects, both in entity-resolution-engineer territory, so the verdict is REQUEST_CHANGES. Nothing is critical. Every other decision-19 item is satisfied in the code as it stands. Each finding below was checked against the files; reported numbers come from the pre-repair attempt-01 run report (`modules/entity-resolution/target/phase-05-real/attempt-01/run=75549bbac746dd31dcdf63fe90c0714e/run-report.json`). I cannot run git, so for modified files I judged the current content, not a line diff.

## Findings

| # | Severity | file:line | Failure input / evidence | Impact | Owner | Required fix |
|---|---|---|---|---|---|---|
| M1 | major | `modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErRun.scala:297-298`, `:660-663`; `.../er/cluster/Clustering.scala:151-197` | Only ACCEPTED pairs feed `EdgeCollector`. REJECTED pair decisions never act as a cannot-link, and a rejected pair whose two mentions end up in one cluster is returned unchanged by `finalDecision`, with no flag. Concrete case, three mentions on different works in initials-surname block `j smith`: M1 "Jane Smith" (coauthor X), M2 "J. Smith" (coauthors X, Y), M3 "John Smith" (coauthor Y). M1-M2 scores -1 +1 (given compatible) +2.0 (1 shared coauthor) +0.5 (overlap ratio 1) = 2.5, p=0.924, accepted. M2-M3 is the same. M1-M3 scores -4 -0.5 -1, rejected. Clustering merges all three into one cluster. The M1-M3 decision still says `rejected`, the identity assignment says `accepted`, and nothing is flagged or counted. | Over-merging that is not reported: Jane and John Smith become one ResearcherId. The run report has no count of rejected (or conflict) pairs inside a final cluster. The run-2 evaluation samples pair decisions, so it would score M1-M3 as a correct NON_MATCH and never see the cluster error. This breaks "identity uncertainty is reported, not hidden" (CLAUDE.md) and review item 6. | entity-resolution-engineer | Either refuse merges that would put a REJECTED or CONFLICT pair inside one cluster (record it as a cluster guard → UNRESOLVED/CONFLICT with a reason code), or at minimum flag such clusters and their identity assignments (rule plus reason code) and report the counts. Add a module test for the chain above. |
| M2 | major | `.../er/eval/Harness.scala:56-67` (`Proportion.wilson`), `:223`, `:138`, `:337` | `metricsJson(weighted)` computes `wilson95` with n = the sum of weights (population/labeled_h), not the number of labeled units. With real strata (populations in the millions, about 300 labels) the interval collapses to about ±0.0002. The markdown row "Weighted estimate" prints it under "Precision (Wilson 95%)". In the existing synthetic test (`GoldAndHarnessAdversarialSuite.scala:239`) the weighted precision 60/80 gets a Wilson interval at n=80 from 4 labeled units; no test checks this. | A falsely precise 95% interval in the run-2 metrics file and summary that the human gates on. Decision 15 requires 95% intervals. | entity-resolution-engineer | For weighted metrics, set `wilson95` to null or base it on the number of labeled units or Kish's effective sample size. Print the stratified bootstrap interval in the weighted markdown row. Add a test asserting the weighted interval is not narrower than the unweighted one. |
| m1 | minor | `.../er/review/ReviewQueue.scala:192`, `:249-251` | The 30 dblp-prior rows in the label file have an empty `candidateId` (verified: 30 matching rows in the attempt-01 `review-queue.csv`). | An empty ID reveals the stratum (dblp-prior, deterministic accept), against decision 13's "no stratum". The identical numbered names on both sides reveal much the same, so the impact is small. | entity-resolution-engineer | Document it for the human, or show a neutral reference in that column. Human decision. |
| m2 | minor | `.../er/io/Csv.scala:6-9` | Fields starting with `=`, `+`, `-` or `@` are written as is. None occur in the attempt-01 queue. | Spreadsheet formula injection when the human labels in a spreadsheet. The strict reader would then fail on a display-column mismatch: loud, not silent. | entity-resolution-engineer | Neutralise formula-leading cells, or document the risk. |
| m3 | minor | `.../er/work/WorkResolution.scala:198` | `"workLinksOutsideSubset" -> linksOutside` (a Long) is rendered as the string `"0"` (attempt-01 run-report.json line 169). | Schema drift: a count written as a string. | entity-resolution-engineer | Write it with `ujson.Num(v.toDouble)`, as Report.scala does. |
| m4 | minor | `.../er/llm/LlmStage.scala:156-161` | An exception thrown by the adjudicator becomes UNRESOLVED with reason `llm-output-rejected:adjudicator-error`, but is not written to `llm/rejected-replies.jsonl` and is not counted in `rejectedReplies`. | Incomplete failure accounting. | entity-resolution-engineer | Record and count adjudicator errors. |
| m5 | minor | `.../er/run/ErRun.scala:115-118`, `Reuse` `:201-209` | The input fingerprint uses the J09 run ID plus file paths and sizes, not content. Reuse re-hashes outputs but not inputs, although input sha256s are in the manifest. The run ID trusts the human-supplied `codeVersion`, so a code change without a new code version would be reused: the finding-2 pattern. | Mitigated only by the procedure of a new output root per attempt. | entity-resolution-engineer | Re-verify input sha256s from the manifest on reuse, or document the limit next to the decision-17 claim. |
| m6 | minor | `.../er/BoundarySuite.scala:37-44` (also the contract test at `GoldAndHarnessAdversarialSuite.scala:272`) | The leakage test is a text search for `er.eval`. A relative import (`import eval.Harness` inside `package org.scholarchain.er`) would get past it. | The test could pass with the boundary broken. | entity-resolution-engineer, test-engineer | Also forbid the token `eval.` and package-relative imports, or check bytecode references. |
| m7 | minor | `.../resources/er/policy/er-v1.json:16` and its weights | The rationale says accept needs "equal given names plus one more agreeing signal". But `name_similarity` (+0.3) always fires when the folded names are equal, so folded-equal different strings score -1 +3 +0.3 = 2.3 (p=0.909, accepted) with no independent evidence when `coauthor_shared_count` is omitted (teams over 50 authors). Same venue alone (+0.4 +0.3) also outweighs zero shared coauthors (2.5). | The policy text claims more than the weights do. The human should know this before er-v1 is frozen. The weights are not taken from labels, so this is not leakage. | entity-resolution-engineer (human decides) | Correct the rationale, or make the two name features not double-count. Human decision before freeze. |
| m8 | minor | `tests/contracts/.../er/NameKeyAdversarialSuite.scala:74` | Decision 18's initials-collision case is only tested at the key and relation level. No test checks cluster-level behaviour for J. Smith, John Smith and Jane Smith. | The M1 gap went undetected. | test-engineer | Add a cluster-level adversarial test once M1 is fixed. |

## Decision 19 rulings

1. **Leakage: PASS.** No file outside `er/eval` imports `er.eval` or names `GoldLabel`, `KeyRow`, `agent-runs` or `/gold`. `eval` imports `review`, never the other way round. The policy file has no label-derived fields. Gold paths reach the code only through `EvalMain` command-line arguments. The synthetic gold resources are clearly synthetic. Caveat: m6.
2. **Exact-ID conflicts never merge: PASS at pair and cluster level.**
   - Pair rules: `orcid.disagree` and the two `*-homonym-differs` rules (`Decide.scala:174-196`).
   - Cluster guards: ORCID, same-work, distinct-homonym and disambiguation-profile (`Clustering.scala:157-175`).
   - Prior clusters that already hold two ORCIDs are flagged and kept whole.
   - Every pair-level CONFLICT type has a matching cluster guard.
   - Caveat: REJECTED pairs do not block merges (M1).
3. **The LLM can abstain and cannot create identifiers: PASS.**
   - The output schema and the validator require `chosenCandidateId` to be one of the offered IDs; the core validator checks it again.
   - Replies that try to change a source field, repeat a key, or are malformed are rejected and the pair becomes UNRESOLVED.
   - The real run uses `AlwaysAbstainAdjudicator`. The model ID comes only from `--llm-model-id`.
   - The cache is keyed by input hash, prompt version, model version and policy version.
   - The cap counts mention pairs (1,000 pairs gave 1,063 candidates). That is acceptable and is reported.
4. **Every decision field present, outputs deterministic: PASS.**
   - MatchDecision v2 carries every decision-6 field: subject kind, left/right (source record and target), candidate ID, path, features, rules fired, reason codes, model and prompt versions, confidence, outcome, policy version, decision time, `provenance.runId`. The core validator enforces them.
   - Decision time is a single value from `--decision-time`, recorded in the run manifest.
   - Output ordering is fixed: sorted file lists, the code-point edge order, seeded sampling, and the run ID from code version, input fingerprint and config fingerprint.
   - Caveat: m5.
5. **Review queue label-blind and traceable: PASS with m1 and m2.**
   - The label file has none of score, decision, band or stratum. Those are only in `review-key.jsonl` and `strata.json`, together with the inclusion probabilities.
   - Rows are in seeded Fisher-Yates order and keyed by the unordered mention pair; `candidateId` is included for reference.
   - Each side has the decision-13 columns, including the name as written and `https://dblp.org/rec/<key>.html`, followed by the decision-14 label columns.
   - CSV and JSONL are both written. dblp-prior pairs are sampled directly from the prior clusters (population 3,425,801).
6. **Gold reader (decision 14): PASS.**
   - Rejects unknown, repeated or missing columns, unknown label or evidence values, duplicate pairs in either order, pairs not in the queue, candidate-ID or display-column mismatches, missing labeler, bad dates and half-filled rows. All errors are reported.
   - It also accepts the queue's own display columns so labels can be entered directly in the queue CSV. They must match the queue exactly, so I judge this inside "unknown columns are errors".
   - The label path `docs/agent-runs/phase-05/gold/` is human-owned and is never named in code.
7. **Decision 5 (every mention gets exactly one cluster): PASS.**
   - Prior clusters are keyed on the exact `AuthorMention.name` (F3 repair verified, `Loader.scala:166`).
   - Every name index has a root, and one identity assignment is written per mention, including oversize and orphan mentions.
   - ResearcherIds follow the core earliest-paper order.
   - Caveat: M1 (merges are only through accepted decisions, but rejected decisions are ignored).
8. **F3 routing (`name.canonical-equivalent`): sound and in scope for er-v1.** It is a deterministic accepted decision under policy, subject to all merge guards, and decision 5 allows merges through accepted decisions. Whitespace-only variants: `fullNameKey` does collapse whitespace, but J01 already normalises it (`DblpRecordBuilder.scala:140`, `DblpAuthorName.normalize`), so whitespace-only joins cannot happen on J01/J09 data. No change needed beyond optionally correcting the rule description, which mentions only canonical equivalence.
9. **Decision 7 ("nothing else" in core): PASS.** The core additions are:
   - `AuthorNameKey` (finding 10);
   - MatchDecision v2 with the `MatchOutcome` wire names renamed (needed for decision 6), with a schema bump and new frozen wire examples;
   - `MatchPath` and `AdjudicationOutcome`;
   - LlmAdjudication v1 and `LlmAdjudicationId` (the cache key);
   - validators.

   No arXiv or Semantic Scholar enum values were added.
10. **Decision 8 (Hadoop scope): PASS.**
    - J05 and J06 use the core key; no local name normalisation remains in the hadoop main sources.
    - The initials-surname family was added.
    - The oversize fallback is configured by settings and capped. J05b counts the members left in no emitted block, and J05c lists them and writes the worst-key summary.
    - J06 computes the same features for every family.
    - The J06 volume rule is the option decision 6 allows.
    - inputRuns and run-ID fingerprinting are kept.
    - The F5 repair is verified: separate pre-cap and emitted largest-block stats.
11. **Hadoop and ER run settings without labels: acceptable.** keyFamilies `dblp-base-name,initials-surname`, maxBlockSize 200 and fallback coauthor cap 200 are volume bounds: 19,900 pairs per block, all within maxPairsPerBlock. They are fingerprinted into the run ID and their cost is reported (43,466 uncovered members keep only their prior cluster). The er-v1 thresholds come from what each feature means, as decision 11 allows; caveat m7.
12. **Ownership: PASS by path.**
    - The changes fall inside: `datasets/dataset-manifest.yaml` (only the `subset_rule.measurement` block is new in content), `modules/core/**`, `modules/hadoop/**` (including `src/test` runners), `modules/entity-resolution/**` and `tests/contracts/**`.
    - No out-of-territory paths and no `build.sbt` change are listed.
    - The ER module depends only on core; it has no AWS, HTTP or hadoop imports and no hardcoded model ID.
    - I cannot see which agent session actually wrote each file. The reported heredoc/cp writes stayed inside hadoop territory.
13. **Spec gate items checkable now: PASS.**
    - Exact-ID conflicts do not silently merge.
    - The LLM can abstain.
    - Review examples trace back to dblp records.
    - Precision and recall are reported separately (M2 is about the weighted interval).
    - The leakage boundary holds.
    - "Gold labels are human-reviewed" cannot be checked until run 2.
14. **Real data: PASS.** All real-run outputs are under `modules/*/target/**` and `tests/contracts/target/**`, which `.gitignore` excludes (`target/`). No DBLP data is under tracked paths. `datasets/samples/**` predates this phase.

The F1, F2, F4 and F5 repairs are verified in code: strict duplicate-key parsing (`Adjudication.scala:144-204`), sent and cached counts split (`Report.scala:92-93`), guards inheriting the refused decision's path (`Policy.scala:259-265`, `ErRun.scala:677-686`), and the J05b pre-cap and emitted stats.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-05/prompt.md, run 1 of 2, agent 6 of 6 (decision 19 review of the full uncommitted diff on HEAD 1279c37)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run by reviewer; orchestrator-reported `sbt check` exit 0 (module 995/995, contract 204/204), not observed by me
VALIDATION RESULTS: findings verified by reading source, the er-v1 policy file, and the attempt-01 run-report.json and review-queue.csv (both under target/)
ASSUMPTIONS: the changed-file list in the work order is complete (git status was truncated); real-run numbers come from pre-repair code
RISKS: M1 hides cluster-level over-merges from both the run report and the run-2 evaluation; M2 would put a false 95% interval into the run-2 metrics
MANUAL ACTION REQUIRED: human decision on m1 and m7 before er-v1 is frozen
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: route M1, M2 and m3-m7 to entity-resolution-engineer and m6, m8 to test-engineer (one repair round each), rerun `sbt check`, then a fresh code-reviewer review before the final real run

---

## Report 2: code-reviewer, review 2 (REQUEST_CHANGES: M-new-1, m-new-1..3)

## Phase 5 run 1 review (fresh, independent): REQUEST_CHANGES

I'm asking for changes because of one major finding (M-new-1). I found no critical findings. The repairs from the last round (M1, M2, m1 to m8, F1 to F5) are all in the code, and I checked each one in the source rather than taking the reports' word for it. The major finding is a coverage gap in the shipped Hadoop defaults. It doesn't depend on the repairs, and the previous review didn't list it. The fix is small. Alternatively the human can accept it as a documented risk and pass a `-D` override in the final run. Either way it needs a decision before the final real run.

VERDICT: REQUEST_CHANGES

### Findings

| # | Severity | File:line | Failure input | Impact | Owner | Required fix |
|---|---|---|---|---|---|---|
| M-new-1 | major | `modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml:272-279` (`scholarchain.blocking.maxBlockSize` = 1000) with `:342-347` (`scholarchain.candidates.maxPairsPerBlock` = 125000); `modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala:354` (fallback only when n > maxBlock); `modules/hadoop/src/main/scala/org/scholarchain/hadoop/candidates/CandidateFeatureJob.scala:274-289` (J06 skips blocks whose pairs exceed maxPairs) | A primary `dblp-base-name` or `initials-surname` block with 501 to 1000 members (for example a common base name or an initials key with about 700 mentions) | J05 emits the block, because it is not over 1000 members, so the block never goes to `oversizeMembers/`, J05b or J05c. J06 then refuses to expand it (it has more than 125,000 pairs), so its members get no candidates in that family. The fallback doesn't apply to them, they aren't counted in FALLBACK_UNCOVERED_MEMBERS, and they don't appear in `uncoveredMembers/` or the worst-key list. The behaviour is upside down: a 700-member block loses everything, while a 1,100-member block gets fallback coverage. The xml text itself says to keep maxBlockSize at 500 or less with these defaults, but the shipped default breaks that, and nothing checks it. No code sets maxBlockSize: `LocalPipelineRunner` takes `-D` options but has no default, and decision 17 lists no such override, so the real run would use 1000. The decision 8 and spec item "policy for oversize groups" is not met for this size range. | hadoop-engineer | Either set the `scholarchain.blocking.maxBlockSize` default to 500 or less, or add a configuration-time check that fails when maxBlockSize·(maxBlockSize−1)/2 > maxPairsPerBlock (a default change alone still lets an override recreate the gap). Add a test that fails today with a block between the two limits. The other option is for the human to accept the risk and require `-D scholarchain.blocking.maxBlockSize=500` in the final run. |
| m-new-1 | minor | `modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/Harness.scala:228-233`; `modules/entity-resolution/src/main/scala/org/scholarchain/er/review/ReviewQueue.scala:204-235` | A dblp-prior queue pair that a candidate stratum also samples, where the prior cluster is conflict-flagged but this pair's own decision is `dblp-prior.same-name` accepted (or `orcid.exact`, path exact_id) | The row keeps the dblp-prior `info` (inserted first). The weighted harness then scores every membership with that row-level outcome and path, even the candidate-stratum membership whose stratum says `outcome=accepted`. Weighted counts and the by-path breakdown can mislabel such rows. Likelihood is low (a 30-pair prior sample against millions of candidates). | entity-resolution-engineer | Record the outcome and path for each membership in the review key, and use the stratum's own decision for the weighted units. Or document the rule. |
| m-new-2 | minor | `modules/entity-resolution/src/main/scala/org/scholarchain/er/review/ReviewQueue.scala:411-446`; `modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/Harness.scala:104-142` | A pair decided UNRESOLVED or REJECTED whose two mentions still end in one final cluster (by transitivity, or because they are in the same dblp-prior cluster) | The harness measures pair decisions only. The review key doesn't record whether the two mentions share a final ResearcherId, so run 2 can't report the precision of the cluster assignment that Spark consumes. Decision 15 is met as written, but this uncertainty is unreported. | entity-resolution-engineer, or human decision | Add `sameFinalCluster` to the review key (it is hidden, so the queue stays label-blind) and a cluster-level precision and recall. Or the human records this as out of scope. |
| m-new-3 | minor | `modules/core/src/main/scala/org/scholarchain/core/AuthorNameKey.scala:126-132` with `modules/entity-resolution/src/main/resources/er/policy/er-v1.json:16` (rationale (3)) | "John A. Smith" vs "John Smith": the initials-surname keys are `j a smith` vs `j smith`, and the base names differ | Middle-name and middle-initial variants are never in the same block, so they are never compared. The rationale's example "only a middle initial missing" describes a pair the blocking cannot produce, and this recall gap is not reported anywhere. | Blocking design (hadoop-engineer and domain-engineer key), rationale text (entity-resolution-engineer); human decision | Either state the gap in the policy rationale and the run report, or add a blocking family. Do not change weights (that is a human decision). |

### Repairs from the previous round, checked in code

- **M1 (cannot-links):**
  - `ErRun.scala:329-332,366-371` sends pass-1 REJECTED and CONFLICT decisions, and LLM rejects, to `CannotLinks.Builder`. Pairs inside one dblp-prior cluster are ignored.
  - `Clustering.scala:164-204` checks after the ORCID, same-work, homonym and disambiguation guards; conflict wins over rejected.
  - Cannot-link sets are merged small into large (`:222-225`).
  - The guards map to CONFLICT and UNRESOLVED with an inherited path (`ErRun.scala:771-789`), and the policy enforces `inherited` (`Policy.scala:259-265`).
  - The report has `cannotLinks`, including `rejectedOrConflictPairsInsideOneClusterSplit` {acrossPriorClusters, withinPriorCluster} (`ErRun.scala:670-685`).
  - acrossPriorClusters is empty by construction. A refusal reason never goes away (ORCID sets, work sets, homonym map and cannot-link sets only grow), so a refused pair can never merge later. The internal assertion at `ErRun.scala:765-770` backs this up.
  - The bit packing is correct: lo and hi are below 2^30, keys are positive, `key>>>31` and `key&0x7fffffff` round-trip.
  - Memory at about 7.2M decisions and about 201k names: roughly 58 MB of buffer, about 90 MB of dedup arrays, then 2 ints per distinct name pair. Bounded.
  - Merge order comes from `EdgeCollector.sorted` (confidence, then path rank, then name key) and doesn't depend on input order. The guard doesn't reorder merges.
  - The tests are real: `CannotLinkAdversarialSuite` includes a no-cannot-link control, all 24 merge orders on a path, an input-reversal byte check, and the whole-prior-cluster case.
- **M2 (weighted intervals):** weighted `wilson95` is null, `interval95` is a row-level stratified bootstrap, and `labeledUnits`, `labeledRows`, `kishEffectiveN`, the collapse rule and `bootstrapUnavailable` are all present (`Harness.scala:191-205,256-301,355-386`).
- **m1 (prior queue rows):** `ReviewQueue.priorReference` (`:318-327`) produces a deterministic ID in the same `match-candidate:v1` form. `candidateIdKind` appears only in the hidden key, and no hadoop family named `dblp-prior.numbered-name` exists, so it can't collide with a real candidate ID.
- **m2 (CSV formula injection):** the `'`-prefix encoding is a true bijection, because `'` itself is a trigger (`Csv.scala:15-26`).
- **m4 (adjudicator errors):** written to `llm/adjudicator-errors.jsonl`, storing only a hash of the message.
- **m5 (reuse):** `Reuse.verify` re-hashes the inputs and refuses unrecorded files.
- **m6 (leakage tests):** the detector covers source tokens, class constant pools and TASTy, and has planted self-checks.
- **m7 (er-v1 rationale):** I re-derived the rationale's arithmetic and it matches the weights: 2.3 → 0.909; 1.8 → 0.858; 2.2 → 0.9002; 2.0 → 0.881; 3.5 → 0.97. The worst case for a given-name conflict with no shared coauthors is about −1.6 to −1.8, below logit(0.3) = −0.847. The weights themselves are a human decision, not a defect.
- **m8 (initials collisions at cluster level):** covered by tests.
- **F1 to F5:**
  - F1: StrictJson rejects duplicate keys.
  - F2: `pairsSentToAdjudicator` is reported.
  - F3: the dblp prior is keyed on the exact `AuthorMention.name` (`Loader.scala:159-178`), together with the `name.canonical-equivalent` rule.
  - F4: guard rules use the inherited path.
  - F5: `largestEmittedFallbackBlock` is reported.
- **er-v1 file:** it is consistent with the code. It lists exactly the 30 rule IDs in `Rules.All` (24 plus 6 work rules), and guard paths, outcomes and confidences match `finalDecision`. `Policy.validate` enforces this when the file loads. I could not check the sha256 4c0939…f5 because I have no hashing tool.

### Decision 19 rulings

1. **Leakage: PASS.** Nothing outside `org.scholarchain.er.eval` references eval, gold or agent-runs; the grep matches outside eval are only comments ("evaluated"). The dependency runs eval → review only. The weights and thresholds are constants in er-v1.json, nothing writes to them, and the threshold sweep is reporting only. The gold reader runs only with command-line paths in EvalMain. The test gold is clearly marked SYNTHETIC.
2. **Exact-ID conflicts never merge: PASS.**
   - Pair level: `orcid.disagree` gives CONFLICT and is checked before any accept rule (`Decide.scala:172-180`).
   - Cluster level: the ORCID guard (`Clustering.scala:183`).
   - Transitive chains: conflict-pair cannot-links.
   - Two ORCIDs already inside one prior cluster: flagged CONFLICT and never split.
3. **The LLM can abstain and cannot create identifiers: PASS.** Abstain gives UNRESOLVED. The chosen ID must be one of those offered (`Adjudication.scala:273-283`, and again in the core Validator). Edges come from the pair key, never from the model's text. Changing a source field, malformed output or a duplicate key gives UNRESOLVED with a reason code. The cache key has all four parts. The model ID comes only from configuration.
4. **Decision fields present, outputs deterministic: PASS.** MatchDecision v2 has every field decision 6 lists, plus candidateId and subjectKind. The decision time comes from configuration and the clock is never read. Ordering is fixed throughout: TreeMaps, code-point-sorted names and mentions, sorted edges, seeded sampling and shuffle. The run ID doesn't depend on the output root.
5. **Review queue is label-blind and traceable: PASS.** The CSV and JSONL show mention data only, plus dblp key, URL, title, venue, year, position, team, up to 15 coauthors, ORCID, DOI and empty label columns. Prior rows now show an opaque reference. Score, decision, band and stratum appear only in `review-key.jsonl` and `strata.json`.
6. **Ownership by path: PASS (by path only).** Every changed path is inside its owner's territory: core, hadoop (including its own test tree), entity-resolution, `tests/contracts`, and the dataset manifest's measurement block. I can't attribute authorship without git; `build.sbt` is not in the change list.
7. **Spec gate items checkable now:**
   - Exact-ID conflicts don't silently merge: PASS.
   - The LLM can abstain: PASS.
   - Evaluation examples are traceable to source records: PASS.
   - Gold labels are human-reviewed: N/A until run 2. No gold directory exists, and the reader requires a labeler.
   - Precision and recall are reported separately: PASS (harness design).
   - The reviewer verifies there is no label leakage: PASS.
8. **No real data under tracked paths: PASS.** Test resources are synthetic, and the real-run outputs go under `target/`.

**Human decisions (not defects):** the er-v1 weights and thresholds, including accepting different dblp strings with identical folded names on the name alone (p 0.909); the blocking design for middle-name variants (m-new-3); whether cluster-level evaluation is in scope (m-new-2); and whether to accept M-new-1 with a `-D` override.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-05/prompt.md, decision 19 (run 1 of 2, review after the repair round, HEAD 1279c37 with uncommitted changes)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run (reviewer fence). The claimed `sbt check` result (exit 0, module 1020/1020, contract 230/230) was not observed by me.
VALIDATION RESULTS: read and verified in source: CannotLinks, Clustering, ErRun, Decide, Policy, er-v1.json, LlmStage, Adjudication, ReviewQueue, Csv, GoldLabels, Harness, Report, ErMain, Loader, Model, PairFeatures, AuthorNameKey, core MatchDecision and LlmAdjudication records and validators, FallbackJobs, BlockingJob reducer, CandidateFeatureJob, StableHashPartitioner, Pipeline wiring, hadoop defaults xml, CannotLinkAdversarialSuite, LeakageDetector, the manifest measurement block. Checked the er-v1 rationale arithmetic and checked the rule set against `Rules.All`.
ASSUMPTIONS: The changed-path list in the invocation is complete; with no git access I can't diff, so findings are against current file contents. Authorship matches the path owners.
RISKS: The er-v1 sha256 was not verified (no hashing tool). Hadoop jobs not changed in this repair round were reviewed only where they touch J05, J05b, J05c and J06.
MANUAL ACTION REQUIRED: The human decides on M-new-1 (fix it, or accept it and require `-D scholarchain.blocking.maxBlockSize=500` in the final run) and on the human-decision items above.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Route M-new-1 to hadoop-engineer for one repair round (or a recorded human risk acceptance). Optionally route m-new-1 and m-new-2 to entity-resolution-engineer and m-new-3 to the human. Then a fresh code-reviewer re-check, then the final real run from approved code.


---

## Report 3: code-reviewer, review 3 (APPROVE; open minors m1-m5)

## Phase 5 run 1 code review

**VERDICT: APPROVE.** I found no blocker (critical) or major finding. I found five minor findings. None of them changes a match decision, a cluster or the review-queue contents. The real run can go ahead, but read the run-configuration note in m5 first.

I am read-only and could not run git, so I reviewed the current file contents at the paths in the work order. I checked ownership by path only, not by who wrote each file.

### Checks against the decisions

**Merging and over-merging (decisions 5, 9)**
- Clusters start from exact dblp name strings. `Loader.scala:159-166` keys them on `AuthorMention.name` verbatim, and `Model.scala:50-60` keeps that.
- Edges come only from ACCEPTED decisions: pass 1 at `ErRun.scala:327-328` and LLM accepts at `ErRun.scala:358-365`. A mention with no decision keeps its dblp-prior cluster.
- The ORCID guard runs first on every merge (`Clustering.scala:183`). At pair level, `orcid.disagree` fires before any accept rule (`Decide.scala:174-180`).
- The same-work, distinct-homonym, disambiguation-profile, conflict-pair and rejected-pair guards are all present.
- **M1 verified.** Rejected and conflict pair decisions become cannot-links, kept per cluster root and merged small-into-large (`CannotLinks.scala`, `Clustering.scala:165-173, 201-204`).
- I checked the bit packing in `CannotLinks.Builder` (lo<<32 | hi<<1 | kind, with names below 2^30); it is correct.
- A cluster that already holds two ORCIDs or two mentions of one work is flagged, not split (`Clustering.scala:239-266`).
- Every mention gets exactly one identity assignment (`ErRun.scala:484-527`). ResearcherIds follow the core earliest-paper order (`Clustering.scala:274-281`, which matches `StableIds.scala:94-103`).
- `finalDecision` (`ErRun.scala:764-794`) is consistent: an accepted pair across two final clusters must have a recorded refusal, and I confirmed that invariant holds.

**Decision fields (decisions 6, 7)**
- Every candidate line produces exactly one MatchDecision v2 (`ErRun.scala:404-475`). It carries candidate ID, subject kind, match path, rules fired, reason codes, the model and prompt version only when the path is llm, policy version and the configured decision time.
- The core validator enforces these rules (`Validator.scala:478-528`).
- Schema history is recorded (`SchemaVersions.scala:21-26`). The v1 wire example is kept, and new v2 and LlmAdjudication v1 examples exist. No other module uses `MatchOutcome`.
- **F4 verified.** The policy validator requires cluster-guard rules, and only those, to use path `inherited` (`Policy.scala:259-265`).

**Determinism**
- The decision time comes from configuration. Edges are put in a fixed order (`EdgeCollector.sorted`), and keeping the best edge per pair does not depend on input order.
- Cannot-links are sorted, the LLM band selection is a bounded TreeMap, the sampler keys are SHA-256 based, and every output is sorted.
- **J05 secondary sort verified** (`BlockingSort`, `TwoFieldPrefixGroupingComparator`). Block keys cannot contain a tab, because `AuthorNameKey` collapses all `(?U)\s` whitespace. Keys sharing a two-field prefix sort next to each other, so grouping is correct.
- **m5 verified.** Reuse re-hashes inputs and outputs (`ErRun.scala:199-241`).

**LLM (decisions 12, 19)**
- **F1 verified.** `StrictJson` rejects repeated keys at any nesting depth (`Adjudication.scala:144-204`).
- Replies are rejected and the pair ends UNRESOLVED for: an ID that was not offered, an attempt to change a source field, a schema violation, a citation of a field not in the input, and an adjudicator exception.
- Abstaining is always allowed. The chosen ID is checked against the offered IDs twice: in the validator and again in core (`Validator.scala:542-557`).
- The cache key is the four-part `LlmAdjudicationId`. No model ID is hardcoded; it comes only from `--llm-model-id`.
- **F2 verified.** `pairsSentToAdjudicator` counts `!fromCache` (`Report.scala:94`).
- LLM output never supplies a number or ID: the decision confidence is the validated adjudication confidence, the pair is already fixed, and edges are keyed by name index.

**Gold-label leakage (decisions 11, 14, 19)**
- No code under `src/main` outside `er/eval` imports the eval package or mentions `agent-runs` or gold paths. I checked this with grep.
- er-v1 thresholds and weights are justified from feature meaning. The calibration histogram is label-free and reporting only.
- Contract tests include a leakage detector with planted cases.

**Policy er-v1**
- It is internally consistent. I recomputed the rationale examples: logit(0.9)=2.197, so 2.3 is accepted; 1.8 gives p=0.858, in the band; a given-name conflict with no shared coauthor is at most -1.6, so rejected.
- The policy's rule set must equal `Rules.All`, unknown keys fail, threshold order is checked, and overrides need a new version.
- **F3 verified.** `dblp-prior.same-name` compares exact strings; `name.canonical-equivalent` compares `fullNameKey`.

**Review queue (decision 13)**
- The CSV and JSONL show no score, decision, band or stratum.
- dblp-prior rows get an opaque reference in the same form as a real candidate ID (**m1 verified**). Each row shows the mention's own spelling and the dblp record link.
- Rows are in seeded shuffled order. Per-membership decisions and `sameFinalCluster` are in the hidden key only (**m-new-1 and m-new-2 verified**).
- **m2 verified.** CSV formula escaping is a bijection.

**Hadoop**
- Memory is bounded: J05 and J05b buffer at most the effective cap and stream oversize members; J05c is constant per member.
- **M-new-1 verified.** `BlockLimit` sends blocks over the pair limit to the fallback.
- **F5 verified.** `largestEmittedFallbackBlock` exists.
- J06 has no local name rule and uses the core key.

**Other**
- Dataset manifest: the measurement block matches `dblp-subset-stats.txt` value for value.
- No secrets, AWS dependency or model ID found. `target/` is gitignored, and test resources are clearly synthetic.

**Open observation (PriorStratum label):** I agree. The stratum is named `outcome=accepted`, but each membership's real outcome (accepted or conflict) comes from `priorInfo` (`ErRun.scala:804`). The harness scores with membership outcomes (`Harness.scala:60-66, 290-304`), so no metric is affected; only the stratum name is misleading. See m3.

### Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| m1 | minor | `modules/entity-resolution/src/main/scala/org/scholarchain/er/llm/Adjudication.scala:242`, `:277`, `:279`; docs at `LlmStage.scala:86` | A reply whose `chosenCandidateId` is arbitrary text, e.g. `{"outcome":"accept","chosenCandidateId":"<10 KB of prose>",...}`, or whose unknown key name is prose | Model-written text is stored verbatim and without a length limit in the `detail` field of `llm/rejected-replies.jsonl`. This contradicts "only its hash is kept, never its text" and the rule against storing model reasoning. It cannot happen in Phase 5 (the adjudicator always abstains). Fix before the live adapter: hash or tightly truncate detail text that came from the model. Test gap: `tests/contracts/.../er/LlmAdversarialSuite.scala:181` puts the marker only in the value, not the key, and no case uses an unparseable chosen ID. | entity-resolution-engineer (code); test-engineer (test gap) |
| m2 | minor | `modules/entity-resolution/src/test/scala/org/scholarchain/er/PolicySuite.scala:16` (asserts status only) | Edit a weight in `er-v1.json` and leave `"policyVersion": "er-v1"` | `sbt check` still passes, and decisions are labelled er-v1 under different weights. The freeze depends on the human recording the file's sha256 (decision 20). A test that pins the policy file's SHA-256 would enforce decision 1. | entity-resolution-engineer |
| m3 | minor | `modules/entity-resolution/src/main/scala/org/scholarchain/er/review/ReviewQueue.scala:143` | A sampled dblp-prior pair inside a conflict-flagged cluster | The stratum is named `outcome=accepted` but holds conflict memberships. Metrics are unaffected; only the labels in `byStratum` and `strata.json` mislead. I recommend documenting it in the run 1 and run 2 reports rather than changing code now, because a change alters queue bytes and forces a rerun. | entity-resolution-engineer |
| m4 | minor | `ReviewQueue.scala:197-201`; `Harness.scala:290-304`, `:553` | With the default `skipSameNumberedName=false`, two mentions of one numbered name in an emitted dblp-base-name block are both a candidate (a `dblp-prior.same-name` stratum) and in the dblp-prior population | Weighted metrics count such pairs once in each population. The estimate is therefore over decisions (candidate decisions plus dblp-prior pair decisions), not over distinct mention pairs, but the description only says "Horvitz-Thompson style". The run 2 report must state this. Unweighted and per-stratum metrics are unaffected. | entity-resolution-engineer |
| m5 | minor (run risk) | `modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml:263-269`; compare the rationale in `er-v1.json:9` and `:16` | Real run started without a key-family override | The default blocks only on dblp-base-name, so the initials-surname family that er-v1 is reasoned around would produce no candidates. The probabilistic path and its strata would be barely used and the queue would come out different. The real-run command must pass `-D scholarchain.blocking.keyFamilies=dblp-base-name,initials-surname`, or the report must say it did not. | hadoop-engineer (run command), orchestrator to confirm |

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (Phase 5 run 1, decision 19 review of the full working-tree diff against 1279c37)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run by reviewer; relied on the orchestrator's report (hadoop-engineer saw sbt check pass: 1035 module tests and 253 contract tests, 0 failures). I read the test sources for strength and sampled IdentityAdversarialSuite, LlmAdversarialSuite and PolicySuite: they test behaviour, not just shape.
VALIDATION RESULTS: All earlier repairs (F1-F5, M1, M2, m1-m8, M-new-1, m-new-1..3, the J05 secondary sort) verified against the code; the spec-gate items checkable now pass (exact-ID conflicts never merge, the LLM can abstain, queue rows trace to dblp records, no gold leakage into matching code).
ASSUMPTIONS: Could not run git. Ownership checked by path (core, hadoop, entity-resolution, tests and datasets changes each sit in their owner's territory); authorship not verified. Phase 4 behaviour of the modified Hadoop files not compared line by line.
RISKS: m5 run configuration; m1 matters before the Phase 9 live adapter; with no labels yet, er-v1's name-only accept path for equal folded given names (er-v1.json:16, point 1) is a documented over-merge risk that run 2 will measure.
MANUAL ACTION REQUIRED: Confirm the real-run blocking families (m5). Record the er-v1.json sha256 in the human gate (m2). Decide whether m3 and m4 are documented or repaired.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Final real run on approved code (decision 17), then the human labels the queue between run 1 and run 2.
```

**VERDICT: APPROVE**

Findings: m1, m2, m3, m4, m5, all minor (see the table above). No critical or major finding.
