# Phase 5 work order: entity resolution (run 1 of 2)

## Human architect decisions

1. **Two runs.** This phase needs my hand labels, so it runs twice.
   - Run 1 (this work order): build the entity-resolution subsystem, freeze scoring policy er-v1, run it on real DBLP data, and produce a review queue. This run reports no evaluation numbers.
   - Between the runs, I label the queue myself.
   - Run 2 (a separate work order in a fresh session): run the gold-set evaluation, then the final review and my gate.
   - Policy er-v1 (features, weights, rules, thresholds) must be final when this run ends. In run 2 the only changes allowed are bug fixes I approve, and each one is recorded.

2. **No AWS, no network.**
   - No AWS calls of any kind, Bedrock included.
   - No downloads and no WebFetch.
   - The only real input is the full DBLP release I downloaded, read-only, in `/Users/brian/scholarchain-data/dblp/release=2026-11-01/` (`dblp-2026-11-01.xml.gz` and `dblp.dtd`). Do not copy, move or change these files.

3. **Preconditions.** Before any agent starts, the orchestrator checks, read-only:
   - both DBLP files exist;
   - `docs/agent-runs/phase-05/dblp-subset-stats.txt` exists, ends with `exit=0`, and reports at least 100,000 subset works;
   - `df -h ~` shows at least 18 GB free (measured after the DBLP download).

   If any check fails, stop and tell me. Do nothing else.

4. **Agents and order** (hub and spoke; agents never call each other):
   1. dataset-engineer: record the counts from `dblp-subset-stats.txt` in the manifest as `kind: measurement`, following `verification_plan`. Make no other manifest change. Run `bash datasets/scripts/validate.sh`.
   2. domain-engineer: the core changes in decision 7.
   3. hadoop-engineer: the Hadoop changes in decision 8, then its part of the real run (decision 17).
   4. entity-resolution-engineer: the subsystem (decisions 5, 6 and 9 to 16), then its part of the real run (decision 17).
   5. test-engineer: adversarial tests (decision 18).
   6. code-reviewer: review of the full diff (decision 19).

   Routing rules:
   - Findings go to the owning agent. Each finding gets one repair round. If a finding is still open after its repair round, or the owner can't fix it, stop and ask me.
   - A BLOCKED-ON goes to the named owner. Then the blocked agent reruns in a fresh context.
   - For an unowned path, stop with BLOCKED-ON factory-engineer and ask me.
   - Every source-changing agent runs `sbt check` before it reports COMPLETE. Targeted tests are fine while it works. Agents run sbt one at a time, never in parallel.

5. **What a researcher is, before and after entity resolution (the dblp prior).**
   - dblp has already disambiguated names. "Wei Wang 0001" and "Wei Wang 0002" are different people in dblp, and all mentions of one exact dblp name string belong to one dblp profile. Treat this as the starting point (the dblp prior), not as ground truth.
   - Entity resolution assigns every subset author mention to exactly one identity cluster:
     - It starts with one cluster per exact dblp name string, homonym suffix included.
     - It merges two clusters only through ACCEPTED decisions.
     - It never merges clusters whose authoritative identifiers disagree (two different valid ORCIDs). That is a CONFLICT.
     - If a cluster already holds two different ORCIDs, or two mentions of the same work, it is flagged CONFLICT. It is kept as it is, not split automatically, and the uncertainty is recorded.
     - A mention with no decision stays in its dblp-prior cluster. No mention is ever left without a cluster, including mentions in oversize blocks.
   - Each cluster gets its ResearcherId from the existing core rule (earliest paper).
   - Spark (Phase 6) reads these assignments and does not redo the clustering.

6. **One decision for every candidate.**
   - Every MatchCandidate gets exactly one decision: ACCEPTED, REJECTED, UNRESOLVED or CONFLICT.
   - Every decision names its match path: `exact_id`, `deterministic`, `probabilistic` or `llm`.
   - Every decision keeps all the fields the spec's entity linkage policy lists: source record ID, candidate target ID, features used, rules fired, model and prompt version when applicable, confidence, decision, reason codes, timestamp, run ID. It also keeps the candidate ID and subject kind.
   - The timestamp must not break determinism: the same inputs and configuration give the same output bytes. One way is a single decision time per run, supplied by configuration and recorded in the run manifest.
   - Optional volume rule: a pair whose two mentions carry the same homonym-numbered dblp name may be decided by a deterministic dblp-prior rule, or J06 may skip emitting it. If such pairs are skipped, the review queue must still include a sample of them, drawn directly from the dblp-prior clusters.

7. **Core changes allowed** (domain-engineer). Breaking changes get a new schema version and new frozen wire examples, the same way as in Phase 4.
   - MatchDecision v2. Today (Records.scala:378) it holds only schemaVersion, mentionId, candidateResearcherId, outcome, confidence, features and provenance, so it can describe a mention-to-researcher link but not a mention pair. v2 must:
     - cover both MatchSubjectKind values, using the same left/right convention as MatchCandidate: `mention_pair` for pairwise decisions and `mention_researcher` for the identity assignment;
     - carry every field in decision 6: candidate ID (when there is one), match path, rules fired, reason codes, model and prompt version (optional), policy version and decision time.
   - The identity assignment from decision 5 is one `mention_researcher` MatchDecision per subset mention: the mention, its cluster's ResearcherId, ACCEPTED or CONFLICT, the match path, and the rules (for example the dblp-prior rule). Add a separate record only if this truly can't hold what decision 5 needs, and say why.
   - LLM adjudication record: input hash, the candidate IDs offered, outcome ACCEPT / REJECT / ABSTAIN, the chosen candidate ID (which must be one of those offered), cited input fields, confidence, reason codes, prompt version, model version and policy version. Storage will cache it later (EntityLinkCache).
   - Finding 10: one shared author-name key rule in core, used by both hadoop and entity resolution. It covers the Unicode NFD canonical folding hadoop does today, whitespace and punctuation rules, the homonym-suffix split, and an initials-plus-surname key.
   - Nothing else. Add no arXiv or Semantic Scholar enum values unless the design truly needs to persist them. If it does, the change is additive only and the report says why.

8. **Hadoop changes allowed** (hadoop-engineer):
   - Switch J05 and J06 to the core name-key rule (finding 10) and remove the local copy.
   - Add a blocking family keyed by initials plus surname, using the core key.
   - Oversize fallback. The spec's "Detailed Hadoop requirements" ask for a maximum block size, fallback keys, counters and a policy for oversize groups. When a block exceeds maxBlockSize, re-block its members by the block key plus a secondary key (a shared coauthor name key, venue, or both). Fallback blocks are capped too. Count the members that end up in no emitted block, and list the worst keys. Fallback families and secondary keys come from configuration.
   - J06 computes the existing features for every family. New features belong in entity resolution, not J06, unless they need data that only Hadoop has.
   - Keep everything Phase 4 guaranteed: the counter identity, determinism across output roots, provenance and inputRuns.
   - Not in this phase: finding 2, finding 11, finding 14, N1 to N5, and work-level blocking.

9. **Exact identifier rules** (entity-resolution-engineer):
   - DOI, ORCID and OpenAlex: use the core normalizers, and reuse the J04 exact links and conflicts.
   - arXiv ID: normalize from `10.48550/arXiv.*` DOIs and from arxiv.org ee links.
   - Repository or landing URL: a normalizer plus an exact rule.
   - Apply each rule where the data has the identifier, and report where it has none.
   - Semantic Scholar is not one of our datasets. Record it as not applicable and write no code for it.
   - Two different valid ORCIDs give CONFLICT and are never merged.

10. **Features** (entity-resolution-engineer). Implement the spec's feature list in the entity-resolution module, reusing the J06 values where they exist.
    - Available now: name similarity, initials, author order and position, coauthor overlap, ORCID, year distance, venue compatibility, title similarity (the titles of the two works) and source reliability (fixed per-source weights in the policy file).
    - Affiliation comes only from the J04 mention-to-institution links. The real run has no enrichment data, so affiliation will be absent there.
    - Citation-neighborhood evidence: we have no citation data. Record it as not applicable; no stub.
    - A feature that can't be computed is left out, never filled with a default (the same rule as J06).

11. **Scoring policy er-v1:**
    - A versioned policy file in `modules/entity-resolution/src/main/resources/` holds the weights, rules and thresholds: accept, reject and the LLM band. Every decision records the policy version.
    - Weights and thresholds are set from what each feature means, or estimated without labels from the unlabeled candidates. They are never fitted to gold labels. No labels exist yet.
    - Configuration may override the thresholds, but then the policy version must change.

12. **LLM adjudication (no live call in Phase 5):**
    - Build the port (interface), the strict JSON schema with accept, reject and abstain, the versioned prompt, output validation, and the cache keyed by input hash, prompt version, model version and policy version.
    - The model ID comes from configuration. No model ID is hardcoded.
    - Only candidates in the LLM band go to the adjudicator, up to a configured maximum per run.
    - Output that is malformed, names an ID that was not offered, or tries to change a source field is rejected, and the candidate becomes UNRESOLVED with a reason code.
    - Tests use a deterministic fake adjudicator. The real run uses an adjudicator that always abstains, so band candidates end as UNRESOLVED.
    - The real Bedrock adapter comes later, with the Bedrock client from Phase 9. Add no AWS dependencies.
    - The adjudication prompt and its JSON schema live in `modules/entity-resolution/src/main/resources/`, owned by entity-resolution-engineer. docs/agents.md gives `prompts/**` (runtime prompts and schemas) to rag-engineer. This prompt is not a runtime prompt; it belongs to the match policy and the match decision schemas, which entity-resolution-engineer owns. This is my decision. Do not route it to rag-engineer.

13. **Review queue (for my labels):**
    - 300 researcher mention pairs from the real run, drawn with a fixed seed that is recorded.
    - Stratified by match path, decision and blocking family. Oversample UNRESOLVED and near-threshold pairs, and include a sample of dblp-prior pairs (decision 6). The strata and each row's inclusion probability go in a separate file, so the metrics can be weighted.
    - Rows are in seeded random order. If I stop early, the rows I have labeled are still spread across the strata.
    - Label-blind: the file I label shows no score, decision, band or stratum.
    - Each row is keyed by its unordered mention-ID pair, which stays stable when blocking changes. The candidateId is included for reference.
    - For both mentions, each row shows: the name as written, dblp key, dblp record link `https://dblp.org/rec/<key>.html`, title, venue, year, author position, team size, up to 15 coauthors, the ORCID if any and the DOI link if any. It ends with the empty label columns from decision 14.
    - Format: CSV (I label in a spreadsheet), plus the same rows as JSONL.

14. **Gold labels are mine; no agent writes them.**
    - Path: `docs/agent-runs/phase-05/gold/`. docs/agents.md makes gold-set labels a human decision but names no path, and docs/agent-runs/** is already human-owned.
    - Columns: leftMentionId, rightMentionId, candidateId, label (MATCH | NON_MATCH | UNSURE), evidence (one or more of: orcid, openalex_author, dblp_numbered_name, shared_coauthors, institution, research_area, other), note, labeler, labeledAt.
    - Build the gold-label reader now. It is strict: unknown columns, unknown label or evidence values, duplicate pairs, and pairs that are not in the queue are errors. Nothing is silently ignored.
    - Only the evaluation harness reads gold labels, and only from a path given on the command line. No matching code (normalization, features, scoring, rules, clustering) may import the evaluation code or read that path.
    - Test the harness only with labels that are clearly synthetic, kept in test resources.

15. **Evaluation harness (build and test now; it runs in run 2):**
    - For researcher resolution: precision, recall, F1, the confusion matrix (4 decisions by 3 labels), coverage and the unresolved rate. Work resolution is reported separately (decision 16).
    - Broken down by match path, blocking family and stratum, plus estimates weighted by the inclusion probabilities. Give 95% intervals (Wilson or bootstrap).
    - Report labels whose only evidence is dblp_numbered_name separately from labels with independent evidence, because the dblp-prior rule uses the same dblp information.
    - Error analysis: up to 10 false positives and 10 false negatives (all of them if there are fewer), each with its features, rules fired and reason codes.
    - Threshold sensitivity: sweep the accept and reject thresholds and report precision, recall and coverage. This is reporting only; it must not change er-v1.
    - Output: a machine-readable metrics file plus a Markdown summary.

16. **Work resolution uses exact identifiers only.**
    - Turn the J04 exact DOI links into `exact_id` decisions, and the J04 conflicts into CONFLICT decisions.
    - Report coverage (the share of subset works with an exact link) and the conflict counts.
    - No probabilistic work matching: there is no shared contract for non-dblp works yet (finding 11). The report says so plainly.

17. **Real run (bounded, local, no AWS):**
    - hadoop-engineer runs J01 to J09 on the full DBLP release with the full subset dblp-subset-v1 (all 16 venues). If needed, use a test-scope runner like LocalBoundedPipeline that takes paths and configuration as arguments.
    - Fallback: if the full-subset chain has not finished within 3 hours, stop it and rerun with `scholarchain.dblp.subset.keyPrefixes` set by configuration to `conf/sigir/`, `conf/kdd/`, `conf/cikm/` and `conf/www/`. All other subset rules stay as they are. Change no code to do this.
    - Required settings: `-D scholarchain.dblp.dtdPath=/Users/brian/scholarchain-data/dblp/release=2026-11-01/dblp.dtd` and `-D scholarchain.code.version=phase05-<git short HEAD>-<attempt>-dirty`.
    - Finding 2 is still open, so every attempt uses a new, empty output root: `modules/hadoop/target/phase-05-real/attempt-NN/`. Never reuse an earlier root after a code change.
    - Disk guard: check free space (`df -h ~`) before each attempt and between jobs. If less than 8 GB is free, stop and report. Delete nothing.
    - If any single job runs longer than 2 hours, stop and report.
    - If the fallback was used, the report and the evaluation must state that the gold set covers those 4 venues only.
    - entity-resolution-engineer then runs entity resolution on the J09 export, writes to `modules/entity-resolution/target/phase-05-real/attempt-NN/`, and generates the review queue.
    - The entity-resolution run ID comes from the inputs, the policy version and the code version, and the run manifest records the input runs. If a completed output would be reused, it must match its manifest, or the run refuses (do not repeat finding 2).
    - If any code changes after code-reviewer approves, rerun both parts, so the final queue comes from approved code.
    - Report: wall time per job, counters, works, mentions, pairs, blocks per family, oversize blocks and fallback results, largest block, candidates per family, decisions per outcome and per path, clusters (total, merged beyond the dblp prior, conflict-flagged, largest), and the exact paths of the queue files.
    - No real data goes into git.

18. **Tests** (test-engineer; tests/** only, no production edits). At least these cases:
    - Conflicting ORCIDs never merge, at pair level or cluster level.
    - Two mentions on the same work never merge.
    - NFC/NFD and punctuation variants share a key; Cyrillic/Latin look-alikes do not.
    - Initials collisions, such as "J. Smith", "John Smith" and "Jane Smith".
    - A transitive chain: A matches B and B matches C, while A and C conflict.
    - Every mention gets exactly one cluster, including mentions in oversize blocks.
    - The oversize fallback is bounded and counted.
    - LLM: malformed JSON, a fabricated ID, an attempt to change a source field, abstention, a cache hit and miss for each part of the key, and the band cap.
    - Determinism across output roots.
    - Missing features stay missing.
    - The gold-label reader rejects bad files.
    - Harness metrics on a small synthetic set that can be checked by hand.

19. **Review** (code-reviewer, read-only). Review the full diff. Besides the usual review order, rule on:
    - Leakage: no matching code imports the evaluation code or reads gold paths, and no weight or threshold comes from labels.
    - Exact-ID conflicts never merge.
    - The LLM can abstain and cannot create identifiers.
    - Every decision field is present, and outputs are deterministic.
    - The review queue is label-blind and traceable to dblp records.
    - Ownership: core, hadoop, entity-resolution and tests were each changed only by their owner.
    - Every spec gate item that can be checked now.

    APPROVE is required before the final real run.

20. **Evidence and stopping:**
    - Write no files under docs/agent-runs/ until all the work is done.
    - When the work is done, first print the summary: the status of each agent, open findings, the policy file path and its sha256, the queue file paths, any step skipped under decision 21, and anything I must decide.
    - Then write the reports verbatim to docs/agent-runs/phase-05/, with my approval for each file: agent-report-dataset.md, agent-report-domain.md, agent-report-hadoop.md, agent-report-er.md, test-report.md, review-report.md, and repair-NN.md as needed.
    - Do not commit.

21. **Unattended overnight run: avoid anything that needs my approval.** I will be asleep for most of this run, and an approval prompt pauses everything until morning.
    - Use only these kinds of commands:
      - sbt;
      - read-only git (status, diff, log, show, rev-parse);
      - read-only shell commands: ls, cat, head, tail, wc, grep, du, df, shasum, and find without -delete or -exec.

      No python, java, curl, brew, pip, npm, uv, cs, docker, aws or terraform. If a check would need another tool, skip it and say so in the report.
    - The guard matches text. Keep these words off command lines, including inside strings, arguments and testOnly patterns: rm, delete, drop, truncate, destroy, apply, force, reset, printenv, secret, credential, curl. If a test needs such text, put it in a file.
    - Run long jobs (the real run, long sbt commands) with the Bash tool's background option and check on them. Do not use nohup, `&` or disown.
    - Read the DBLP files only by passing their paths to sbt. Do not open them with Read, Grep or shell commands.
    - If a command is refused or would need my approval, don't try variants of it. Skip the step if it is optional. Otherwise the agent finishes with BLOCKED-ON and the evidence, and the orchestrator carries on with any work that doesn't depend on that step.
    - Until the end, the orchestrator writes no files and runs no git command that changes anything.

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

## Spec gate (for reference; checked at my gate after run 2)

- Exact ID conflicts do not silently merge.
- The LLM can abstain.
- Evaluation examples are traceable to source records.
- Gold labels are human-reviewed.
- Precision and recall are reported separately, not hidden behind one aggregate score.
- The reviewer verifies there is no data leakage from evaluation labels into matching logic.
