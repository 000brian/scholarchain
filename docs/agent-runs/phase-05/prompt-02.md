# Phase 5 work order: entity resolution (run 2 of 2: gold-set evaluation)

## Human architect decisions

1. **Scope.** Run 1 built entity resolution, froze policy er-v1 (commit 2297990) and produced the review queue. I have now labeled that queue. This run evaluates the attempt-02 decisions against my labels, reports the results the spec asks for, and ends with a final review. Nothing new is built.

2. **No AWS, no network.** No AWS calls of any kind, no downloads, no WebFetch.

3. **Inputs (all read-only).**
   - Gold labels (human-owned): `docs/agent-runs/phase-05/gold/gold-labels.csv`. Its columns are exactly the gold-label columns from run 1's decision 14, the same header as the synthetic test file `gold-valid.csv`: leftMentionId, rightMentionId, candidateId, label, evidence, note, labeler, labeledAt. No agent edits, moves or rewrites it.
   - The attempt-02 entity-resolution run: `modules/entity-resolution/target/phase-05-real/attempt-02/run=9f96c70b5115294dcc9d3b2d1d4438b9/`, including `review-queue/review-key.jsonl` and `review-queue/strata.json`.
   - Policy: `modules/entity-resolution/src/main/resources/er/policy/er-v1.json`.

4. **Preconditions.** Before any agent starts, the orchestrator checks, read-only:
   - the gold file exists;
   - `shasum -a 256` of er-v1.json is `4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac`;
   - `shasum -a 256` of the attempt-02 `review-queue/review-queue.csv` is `75e80120afb35721a7c32686a999c74f5163481559ecc7484e2b0f3d0932df5a`.

   If any check fails, stop and tell me.

5. **The freeze holds.** No change to matching code: normalization, features, scoring, policy, rules, clustering, the LLM stage, the review-queue generator, modules/hadoop or modules/core. The only code that may change is the evaluation harness and the gold-label reader (the `er/eval` package and its tests), and only to make the evaluation run correctly. Every such change needs a test that fails without it, and is reported as a repair. Any other change: stop and ask me.

6. **Agents and order** (hub and spoke; agents never call each other):
   1. entity-resolution-engineer runs the gold-set evaluation on attempt-02.
      - If the gold-label reader rejects my file, report the exact error first.
      - If the cause is the reader, fixing the reader is allowed under decision 5.
      - If the cause is my labels (an unknown word, a duplicate pair, a pair not in the queue), stop and list the rows. Do not edit my file.
   2. test-engineer: only if harness or reader code changed. Add adversarial tests for the change in tests/**.
   3. code-reviewer: review any code diff, the evaluation results and the leakage question (decision 9). APPROVE is required.

   Routing rules from run 1 still apply: findings go to the owner, one repair round per finding, then ask me. Every source-changing agent runs `sbt check` before reporting COMPLETE. sbt runs one at a time.

7. **What the evaluation reports.** Everything in run 1's decision 15, plus the spec's report list:
   - Researcher-identity resolution:
     - precision, recall and F1;
     - the confusion matrix (4 decisions by 3 labels);
     - coverage and the unresolved rate.
   - Breakdowns: by match path (`exact_id`, `deterministic`, `probabilistic`, `llm`), by blocking family, and per stratum, plus estimates weighted by the attempt-02 inclusion probabilities, with 95% intervals.
   - The LLM path: the adjudicator always abstained in this phase, so state plainly that LLM-adjudicated metrics are empty and why.
   - Labels whose only evidence is `dblp_numbered_name`, reported separately from labels with independent evidence.
   - UNSURE labels are excluded from precision and recall, and counted. Unlabeled rows, if any, are counted.
   - Error analysis: up to 10 false positives and 10 false negatives (all, if fewer), each with features, rules fired and reason codes, traceable to their dblp keys.
   - Threshold sensitivity: sweep the accept and reject thresholds and report precision, recall and coverage. This is reporting only; er-v1 does not change.
   - Work resolution: exact identifiers only. Report exact-link coverage and conflict counts, and say plainly that probabilistic work matching was not implemented (finding 11).
   - Also report:
     - the policy version, feature definitions, thresholds, and model and prompt versions (the always-abstain adjudicator; no live model);
     - the gold set's size: 300 queue rows, how many labeled, how many MATCH or NON_MATCH;
     - its scope: all 16 venues of dblp-subset-v1.
   - Known limitations to state in the report:
     - m3: the dblp-prior sample stratum is labelled outcome=accepted, but its pairs are conflict pairs;
     - m4: weighted metrics count decisions, not distinct mention pairs, because some pairs are both candidates and dblp-prior pairs;
     - the middle-name recall gap (6,447 groups, 97,681 dblp names never compared);
     - the 43,466 members with no candidates (fallback cap 200).

8. **Outputs.** The metrics file and a Markdown summary go under the attempt-02 run folder (for example `.../eval/`). The full Markdown summary also goes, verbatim, into entity-resolution-engineer's final report, so it ends up in the phase evidence.

9. **Review** (code-reviewer, read-only). Rule on:
   - Leakage:
     - no matching code reads the gold path or imports the evaluation code;
     - er-v1.json is byte-identical to commit 2297990;
     - `git diff 2297990 -- modules/entity-resolution/src/main` touches only the `er/eval` package, if anything.
   - Whether the reported numbers match the metrics file and the decision files, by spot-checking at least the confusion matrix and three error-analysis rows against review-key.jsonl and the gold file.
   - Each spec gate item:
     - exact-ID conflicts do not silently merge;
     - the LLM can abstain;
     - evaluation examples are traceable to source records;
     - gold labels: they are supplied by the human architect in the human-owned file, and no agent wrote or changed them. 
     - precision and recall are reported separately;
     - no data leakage.

10. **Commands.** Run 1's decision 21 command rules still apply: sbt, read-only git, and read-only shell commands only; keep watched words off command lines.

11. **Evidence and stopping.**
    - Write nothing under docs/agent-runs/ until all the work is done, and never write under `docs/agent-runs/phase-05/gold/`.
    - When done, first print the summary:
      - each agent's status;
      - the headline metrics (precision, recall, F1, coverage, unresolved rate);
      - open findings;
      - the er-v1.json sha256 at start and end;
      - anything I must decide.
    - Then write the reports verbatim to docs/agent-runs/phase-05/, with my approval for each file: agent-report-er-eval.md, review-report-02.md, plus test-report-02.md and repair-NN.md (numbering after run 1's) as needed.
    - Do not commit.

## Spec work order (verbatim)

```text
Use the entity-resolution-engineer agent to implement the ScholarChain entity-resolution subsystem against the Phase 3 contracts and Phase 4 MatchCandidate records.

Required behavior:
- Exact identifier rules for DOI, arXiv ID, ORCID, OpenAlex ID, Semantic Scholar ID, and repository URL.
- Normalization for Unicode, punctuation, whitespace, title markup, initials, name order, and DOI prefixes.
- Feature functions for researcher-name similarity, initials, author order, coauthor overlap, affiliation compatibility, ORCID or external IDs, title similarity, year distance, venue compatibility, citation-neighborhood evidence when available, and source reliability.
- A versioned scoring policy.
- Separate thresholds for accepted, rejected, and unresolved results.
- Conflict detection when authoritative identifiers disagree.
- LLM adjudication only for a bounded unresolved band.
- Strict JSON schema for LLM adjudication with accept, reject, and abstain outcomes.
- No LLM call may alter source records or create an identifier not present in the candidates.
- Cache LLM adjudications by input hash, prompt version, model version, and policy version.
- Produce accepted, rejected, unresolved, and conflict outputs.
- Generate a review queue for human-labeled evaluation examples.

Evaluation requirements:
- At least 200 manually reviewed candidate pairs.
- Precision, recall, F1, confusion matrix, and coverage, reported separately for researcher-identity resolution and work resolution when both are implemented.
- Metrics by match path: exact ID, deterministic bibliographic, probabilistic, and LLM-adjudicated.
- Error analysis with at least ten false positives and ten false negatives when available.
- Threshold sensitivity analysis.

Run sbt check and the gold-set evaluation.
Report the policy version, feature definitions, thresholds, model and prompt versions, confusion matrix, unresolved rate, and representative errors.
```

## Spec gate (checked at my gate after this run)

- Exact ID conflicts do not silently merge.
- The LLM can abstain.
- Evaluation examples are traceable to source records.
- Gold labels are human-reviewed.
- Precision and recall are reported separately, not hidden behind one aggregate score.
- The reviewer verifies there is no data leakage from evaluation labels into matching logic.
