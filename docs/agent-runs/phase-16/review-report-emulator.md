REVIEW REPORT: review-report-emulator (Phase 16 session 3, E14 review of docs/emulator.md)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

I reviewed /Users/brian/Developer/repos/scholarchain/docs/emulator.md (197 lines; the version in diff-E14-emulator.txt) against the E14 result files, the Phase 15 records, scripts/README.md and work-order decisions 6.6, 12, 14.2, 17 (E14) and 18. I found no critical or major issues and six minor ones, all owned by documentation-engineer.

VERDICT: APPROVE

## Check results

1. **Counts and outcomes: match.** I recounted every line by hand, by file, service, class and distinct operation.
   - Lines per file: setup.jsonl 36, results-EmulatorAdaptersEmulatorSuite.jsonl 54, results-EmbeddingWorkerEmulatorSuite.jsonl 18, probes.jsonl 14. Total 122.
   - Calls by service (distinct operations in brackets):
     - S3: 5 + 9 = 14 (7)
     - DynamoDB: 8 + 9 = 17 (6)
     - S3 Vectors: 11 + 14 + 9 = 34 (9)
     - SQS: 12 + 22 + 18 = 52 (7)
     - Step Functions: 5 not-exercised (2): ValidateStateMachineDefinition 1, TestState 4
   - Classes: 117 implemented-passed, 5 not-exercised, 0 of every other class. The per-service operation lists match the files.
   - Both demonstration files say `demonstrated`. The re-embed definition (a second delivery `replayed` with exactly one embed call) matches EmulatorAdaptersEmulatorSuite.scala:502 and :532. Both dead-letter paths end with `dead letter after 5 receives` (adapters :41, worker :18).
   - The probe totals in the status lines (9 passed, 5 not exercised) match probes.jsonl.
   - Nothing not-implemented is shown as passed. Not-exercised is stated in :120, :129, :131, :161 and :170.
2. **Comparison with Phase 15: accurate and not overclaimed.**
   - D17 matches docs/agent-runs/phase-15/runbook-log.md:106: 6 tests, 2 passed, 3 failed (S3 Vectors AccessDenied from M-01, Neptune and the environment check outside the VPC), 1 skipped (SQS).
   - F matches experiments/results-summary.md:72-73 and :87.
   - D4a is stated as not possible (input missing).
   - M-01 keeps the write-versus-read nuance (:162). One small imprecision, see P16-L-29.
3. **Not exercised: stated plainly** (:166-172): Neptune, the query pipeline, the LLM and embedding provider, Step Functions, EMR/ECS/autoscaling/alarms/IAM, and load. "Does not show the full ScholarChain query working" is in bold.
4. **Limitations: complete.** Every item asked for is there: in-memory state, one account, not load-tested, the LTS/latest package with recorded version 1.0.569, fidelity varies, env.sh does not clear SCHOLARCHAIN_CFG_*, and no live comparison is possible now. The S3 bucket-policy refusal is reported as observed, without speculation (:151, :180). One wording asymmetry, see P16-L-28.
5. **P16-C-06: complied with.** The file stays at service level. It has no class structure in prose, no diagram and no prompt. The only code is four command lines (:54-59). It covers what the professor asked for in decision 18 (installation and configuration, components, results, limitations and differences from real AWS), so it can be the base of the Teams post. Suite class names appear only inside cited result file names and the Phase 15 row title "D17 AwsAdaptersAwsSuite". That is acceptable, but the human may want to paraphrase them in the post.
6. **Secrets and identifiers: clean.** I grepped docs/emulator.md for twelve-digit runs, `arn:`, `amazonaws`, AKIA/ASIA and `scholarchain-` names other than `scholarchain-emu*`: no match. The only `scholarchain-e…` name outside the `scholarchain-emu-` prefix is the local image tag `scholarchain-emulator`, which the work order itself uses. The endpoint is loopback only.
7. **P16-K-02: fixed.** Neither "under repair" nor "being repaired" appears (grep); :25 and :192 give the repaired status. **Links: valid.** docker/emulator/Dockerfile, config/emulator.json, scripts/README.md, experiments/results-summary.md and the results folder all exist (Glob). I did not re-fetch the external AWS documentation URLs (no web tool).
8. **Spec gate item 4 (negative results remain): met** for the emulator part. Section 7 lists what was not exercised and section 8 lists the limitations.

## Findings (all minor, owner documentation-engineer, interface: no)

| ID | Severity | file:line | What is wrong | Evidence |
|---|---|---|---|---|
| P16-L-25 | minor | docs/emulator.md:3, :5 | Says "committed result files" and "Results folder (committed by the human)". At review time `experiments/emulator/` is untracked: the session's git status shows `?? experiments/emulator/`, and the writer's own report lists the commit as a pending manual action. The claim only becomes true if the human commits the raw folder before or together with this file. | gitStatus snapshot; agent-report-docs-E14.md:26 |
| P16-L-26 | minor | docs/emulator.md:101, :193 | "20 resources created". setup.jsonl has 20 `created` outcomes, but two of them are settings, not resources: PutBucketPolicy (setup.jsonl:5) and UpdateTimeToLive (setup.jsonl:10). The resources are 18 (2 buckets, 6 tables, 1 vector bucket, 5 indexes, 4 queues), which matches the list at :46. The script's own summary says `created=20`, so it should read "20 create operations (18 resources, one bucket policy, one TTL setting)" or similar. | setup.jsonl:1-36; runbook-log.md:58 |
| P16-L-27 | minor | docs/emulator.md:84 (also :77) | The section 4 table, headed "What was exercised", lists "Step Functions definition validation and single-state tests" and names Step Functions as a service. All five Step Functions lines are not-exercised (latency 0, `input missing`), as :120 and :170 say. A reader building the Teams post from section 4 could count Step Functions as exercised. It should mark them "attempted; not exercised (input missing)". | probes.jsonl:1-5 |
| P16-L-28 | minor | docs/emulator.md:151 (against :180 and :162) | :151 says only the S3 result "goes against the expectation" from the documentation. The vector-bucket refusal (probes.jsonl:14) runs against the same documented statement ("does not evaluate IAM or resource policies"), as :180 itself says ("Yet both policy probes … were refused"). The M-01 row at :162 then says "Same outcome" without that caveat, which a reader could take as evidence that the emulator evaluates policies like AWS. Section 5.5 and the M-01 row should say that both refusals are unexpected given the documentation. | probes.jsonl:14; results-EmulatorAdaptersEmulatorSuite.jsonl:42-43 |
| P16-L-29 | minor | docs/emulator.md:161 | D4a is described as "four single-state tests" while two rows are cited. Row "D3 address list + K-01 + D4a" ran four inputs (absent, A, B, invalid). Row "D4a (L-01 plan)" ran five (absent, A, 3, B, invalid). It should say "four (D3 plan) and five (L-01 plan)", or cite only the D3 row: decision 12(4)(a) uses the D3 plan, whose four inputs the four TestState probes mirror. | docs/agent-runs/phase-15/runbook-log.md:26, :43; probes.jsonl:2-5 |
| P16-L-30 | minor | docs/emulator.md:116, :118, :131 | Two S3 lines (HTTP 403 AccessDenied, policy probe) and one S3 Vectors line (AccessDeniedException after the deny policy) are counted as implemented-passed in the 5.3 table. :131 then says "every check passed", yet the S3 probe is "recorded, not asserted" (decision 6.6). The counts are correct. The gap is transparency: 5.3 should note that 3 of the 117 implemented-passed lines are recorded refusals (see 5.5), so a reader does not take all 14 S3 calls as successful requests. | results-EmulatorAdaptersEmulatorSuite.jsonl:42-43; probes.jsonl:14 |

## Files read

Read in full: docs/emulator.md; diff-E14-emulator.txt; agent-report-docs-E14.md (scratchpad phase16-reports/); the six files in experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/ (setup.jsonl, results-EmulatorAdaptersEmulatorSuite.jsonl, results-EmbeddingWorkerEmulatorSuite.jsonl, probes.jsonl, demonstration-EmulatorAdaptersEmulatorSuite.txt, demonstration-EmbeddingWorkerEmulatorSuite.txt); scripts/README.md emulator section, lines 218-250.

Read in part: runbook-log.md E14 rows (lines 57-66) and grep hits (line 10, P16-C-06); docs/agent-runs/phase-16/prompt.md lines 176-335 plus grep hits (lines 27, 299), sha256 not recomputed (no Bash); docs/agent-runs/phase-15/runbook-log.md rows at lines 26, 43 and 106; docs/agent-runs/phase-15/human-gate.md M-01 grep hits (lines 45, 89, 102); experiments/results-summary.md section 2.1 (lines 49-88); modules/storage/src/test/scala/org/scholarchain/storage/emulator/EmulatorAdaptersEmulatorSuite.scala grep hits (:415-536); docs/agent-runs/phase-16/review-report-02.md and orchestrator-summary-s2.md P16-K-02 grep hits.

Not read: the external AWS documentation pages. The documentation claims at :9, :151, :177-180 are carried from the W1 draft and are unverified by me.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 16 session 3, E14 review of docs/emulator.md (review-report-emulator); docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f8... as given; not recomputed, no Bash), decisions 6.6, 12, 14.2, 17 (E14), 18; human decision P16-C-06
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not applicable (documentation review; no tests run)
VALIDATION RESULTS: all 122 result lines recounted by service, class and distinct operation; they equal the 5.3 table (117 implemented-passed, 5 not-exercised, 0 in the other classes). Both demonstration lines equal the files. The D17, F, D4a and M-01 comparisons were checked against the Phase 15 records (one imprecision, P16-L-29). Identifier grep clean. Relative links exist. P16-K-02 fixed. P16-C-06 complied with. Spec gate item 4 met for the emulator part.
ASSUMPTIONS: session facts labelled "as recorded" (image ID, package 1.0.569, start time, lint warning, unset of SCHOLARCHAIN_CFG_*, suite pass counts) were checked against runbook-log.md rows 57, 58, 61 and 65, not against primary logs (the /tmp logs were not read). The external AWS documentation claims were not verified.
RISKS: the raw results folder is still untracked (P16-L-25). If the doc is committed without it, the results link and the "committed" claim break. Untracked .DS_Store files under experiments/ (git status) should not be swept into that commit. Suite class names appear in cited result file names; the human may paraphrase them in the Teams post.
MANUAL ACTION REQUIRED: the human commits experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/ (after the identifier scan, without .DS_Store files) before or with docs/emulator.md. The human decides whether the six minor findings P16-L-25 to P16-L-30 get a touch-up round: under CLAUDE.md only blocking or major findings get a repair round.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human decides on the minors, then commits; then E15 gate check (spec gate item 4: emulator part met).
```

VERDICT: APPROVE
