# Review report for repair G-17

(Saved by the orchestrator from code-reviewer's final message.)

VERDICT: APPROVE

Summary: the three built-in defaults now equal the Phase 5 evaluated command line exactly (docs/agent-runs/phase-05/agent-report-hadoop.md:119). No other default changed value. maxPairsPerBlock needs no change (member cap 200 binds; 19,900 pairs <= 125,000). Tests asserting the old defaults were rewritten without weakening. No critical or major findings; two minor, both pre-existing, proposed later.

## Findings

| ID | Severity | file:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| G-18 | minor (pre-existing; proposed later) | modules/hadoop/src/main/scala/org/scholarchain/hadoop/candidates/CandidateFeatureJob.scala:139; modules/entity-resolution/src/main/scala/org/scholarchain/er/run/ErRun.scala:289 | A candidate set built with maxBlockSize 1000, fallback 500, skipSameNumberedName false | Same policyVersion `candidate-features/1-provisional;blocking/2+author-name-key/1` as Phase 5; ER accepts it; only the run-ID fingerprint distinguishes them (how G-17 slipped through). G-17 removes the cause for default runs; a -D override stays invisible to ER | hadoop-engineer (provenance string); entity-resolution-engineer (input guard) | any added segment must follow the feature-set segment so ErRun:289 keeps working |
| G-19 | minor (confirmed; proposed later) | modules/hadoop/src/test/scala/org/scholarchain/hadoop/LocalPipelineRunner.scala:256; modules/entity-resolution/src/main/scala/org/scholarchain/er/eval/EvalReport.scala:283-286 | Local run without -D for the caps | EvalReport blockingCoverage maxBlockSize / fallbackMaxBlockSize null instead of 200; EMR unaffected | hadoop-engineer | runner should record effective settings |

## Checks (all pass)
1. Defaults vs Phase 5 :119 (same as :151): keyFamilies, maxBlockSize 200 (xml:282), fallback.secondaryKeys, fallback.maxBlockSize 200 (xml:316), skipSameNumberedName true (xml:394) all equal. fallback.families, maxPairsPerBlock 125000, pairsPerShard 50000, featureSet equal then and now; reducers change layout only. Diff touches three <value> lines. EMR driver sets only the DTD key (ScholarChainHadoop.scala:36); infra/terraform/modules hold no blocking/candidates keys.
2. BlockLimit.maxMembers (BlockingJob.scala:169-181); J05 (:423, :371), J05b (FallbackJobs.scala:213, :137): effective cap 200. J06 limits (CandidateFeatureJob.scala:262-263, feature-set check, volume rule) equal Phase 5.
3. Skip logic unchanged (CandidateFeatureJob.scala:350-377, :113-115); Phase 5 skipped 5,037,958; policyVersion identical to Phase 5 (:114). ER does not read the key; Fixture.scala:171 defaults to skip; ErDeterminismAndQueueSuite:150; ErRun:289.
4. SettingsSuite :28-44 fails on old defaults; JobLevelBlockingSuite:183-187, ScaleAndProvenanceAdversarialSuite:213-254, FallbackPipelineEndToEndSuite:58-67, :158-187 keep intent; CandidateSuite SkipOff pin justified (:298, :328-343).
5. No other dependency (scripts/README.md:80, :106; spark; infra modules; docs; README). Owners' claims confirmed.
6. XML text: citations correct, numbers correct, no identifiers.

Gate evidence: g17-check.log:9886 (2076/2076), :10537 (399/399), :10539 [success]; g17-package.log:24-25 packageCheck ok.

RISKS: reproduction of 16,823,178 shown only through configuration. On the next D6 compare J05/J05b block counts, J06 MATCH_CANDIDATES_EMITTED 16,823,178 and SAME_NUMBERED_NAME_PAIRS_SKIPPED 5,037,958 with Phase 5. Every Hadoop run ID changes; nothing reused.

STATUS: COMPLETE
OWNER: code-reviewer
NEXT GATE: human gate for G-17, then D2-D4 and D6 rerun with the counter comparison.

VERDICT: APPROVE
