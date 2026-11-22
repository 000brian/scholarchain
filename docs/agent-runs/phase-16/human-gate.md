# Phase 16 human gate: evaluate data quality, retrieval, answers and the agentic factory (plus the AWS Emulator track)

## Gate

**ACCEPT, with open items. Phase result: COMPLETE_WITH_OPEN_ITEMS** (rule 4, after decision 1 below). Session 3's summary reported TIER1_NOT_MET under rule 3 only because I had reserved L2's status for this gate.

Phase 16 evaluated ScholarChain from frozen evidence, fixed the defects earlier gates routed here, and ran ScholarChain's own code against the AWS Emulator:
- session 2 (unattended) wrote the FECD-1 fixes, L1, L2, L-09, N-1, N-2, M3, M4, the emulator wiring and both emulator suites, the evaluation tool `evaluate.py`, the suite file (signed by me at E3), the rubric, and the drafts of `docs/evaluation.md` and `docs/emulator.md`. Its one repair round resolved all 7 findings in scope; the step 10 re-check found one new major, P16-K-01, in L2;
- session 3 (attended, with an unattended overnight repair) repaired L2 twice and made the rag tests hermetic (P16-L-16), then ran E0 to E16: the clean-checkout check (failed at 7951f94, passed at 79b5583), signing of the suite, all seven evaluation groups, my ratings and audit, the freeze (ecc1305), the reviewed final `docs/evaluation.md`, and the emulator track with the reviewed `docs/emulator.md`;
- all four spec gate items are met (E15). No re-run was needed: no build, module, script or configuration file changed after E2, E5 or E14;
- the emulator track demonstrated both application paths: `demonstration dead-letter: demonstrated` and `demonstration re-embed: demonstrated`.

One tier 1 item is accepted as a deviation: **L2**. Its stated scope is done and tested, but four findings inside its rule remain open (P16-L-10, P16-L-11 major; P16-L-12, P16-L-13 minor), and two further minors fall outside it (P16-L-14, P16-L-15). All six go to Phase 17. Every code fix in this phase is fixed in code, tested offline, not validated live.

## Spec checklist

| Item | Status | Source |
|---|---|---|
| experiment-engineer runs the approved evaluation suite | Deviation (planned 1): experiment-engineer wrote the suite and the tool; I ran every evaluation and committed the raw outputs. The suite was approved by my signed line before any real evaluation run (E3; only the E1 fixture checks ran before it) | approvals.md; runbook-log E3 to E11 |
| documentation-engineer prepares the results after raw outputs are frozen | Met: freeze commit ecc13052b09e0b0396f47894e8858c6029632886 (E12, 151 files with sha256) before documentation-engineer wrote the results (E13) | runbook-log E12, E13; frozen-files-sha256-E12.txt |
| Group 1, entity resolution | Done, reduced plan (planned 5): Phase 5's ER run folder is gone, so group 1 recomputes the committed Phase 5 table; 5 of 5 recomputed metrics equal the report. er-v2 accuracy is not measurable: the gold labels cannot be joined to the Phase 14 er-v2 run, and the er-v1 decision set needed for a comparison is gone | er/20261122T170516Z-7e551c8003c3-91dd; docs/evaluation.md 10.1 |
| Group 2, graph correctness | Done (planned 2): 43 of 43 hand-worked Spark tests (KnownGraphSuite 15, PipelineFixtureSuite 28); Spark invariant counts; provenance per table, descriptive (samples not random). Full-graph integrity, duplicate, orphan and support rates are not measurable: no graph export exists | graph/20261122T172641Z-7e551c8003c3-a0dc; docs/evaluation.md 10.2 |
| Group 3, retrieval | Done, EXPLORATORY (n = 11): MRR graph-only 0.818182, vector-only 0.003247, fused 0.775974. Precision@K not measurable (unjudged retrievals); nDCG not measurable (no graded labels); no OpenSearch tier (not implemented) | retrieval/20261122T170516Z-7e551c8003c3-1864; docs/evaluation.md 10.3 |
| Group 4, answer quality | Done as internal consistency plus my rating: claim support attempt-06 111/136, demo 28/31; contradiction 5/136 and 1/31; abstention 3/30 and 0/6. Ratings n = 6, one rater. dblp.org names check 6/6 "yes". Answer accuracy is not independently measurable | answers/20261122T173416Z-7e551c8003c3-8128; ratings/20261122T173416Z-7e551c8003c3-c89f; docs/evaluation.md 10.4 |
| Group 5, performance and elasticity | Done from Phase 15's frozen data (planned 2): 32 measures; costs are estimates; no new run | performance/20261122T170516Z-7e551c8003c3-78bc; docs/evaluation.md 10.5 |
| Group 6, agentic software factory | Done: 388 events (362 counted), 27 unclassified; my audit of the 20-event sample: 20 of 20 real and correctly classified | factory/20261122T170517Z-7e551c8003c3-d311; docs/evaluation.md 10.6 |
| Group 7, reproducibility | Done: clean checkout failed at 7951f94 (P16-L-16), passed at 79b5583 (9 of 9 gates); bounded-pipeline replay passed with Phase 15 clip 19c's numbers; historical tables and plots reproduced exactly (59 of 59 at 08bbb9d); current regeneration: 51 equal, 8 intended differences, 0 unexplained; 7 of 7 release records linked to a signed plan line (record consistency; deployment not independently verified) | repro/*; docs/evaluation.md 10.7 |
| Freeze raw outputs before writing conclusions | Met: E12 before E13 | runbook-log E12 |
| Confidence intervals or variability where repeated runs are feasible | Met as far as feasible: no repeated live run is possible. Bootstrap and Wilson intervals are labelled EXPLORATORY, and each carries a plain line that its questions or answers are not a random sample (decision 2) | docs/evaluation.md sections 1, 4.3, 4.4, 5, 10.3, 10.4 |
| Threats to validity and missing data | Met | docs/evaluation.md threats and missing-data sections |
| **Gate:** evaluation data not generated by the same prompt without independent review | **Met.** No LLM judge. Independent evidence is only Phase 5's gold labels, my ratings and the dblp.org column. The verifier and answer key are labelled internal consistency. code-reviewer checked every number in the final document against the frozen files. Caveat stated in the document: R05's answer text showed its own verification statuses, so my rating was blind only to the sheet's status fields (P16-L-17) | review-report-evaluation.md; review-report-evaluation-2.md |
| **Gate:** benchmark questions and relevance labels are versioned | **Met.** suite-v1.json pins benchmark-v1, the gold labels, rubric-v1 and the sheet generator by sha256; signed in approvals.md; pins re-hashed before every run | approvals.md; runbook-log E3 |
| **Gate:** the report separates measured facts from interpretation | **Met.** Every results section of docs/evaluation.md (10.1 to 10.7) has a "Measured facts" and an "Interpretation" subsection | docs/evaluation.md |
| **Gate:** negative results remain | **Met.** Vector-only retrieval, E2 attempt 1, FECD-1 and the D overload, failed attempts, not-measurable items, the open L2 findings, the low ratings, the abstention flag not set on the false-premise demo answer; in docs/emulator.md the not-exercised Step Functions probes, the limitations and the unexpected policy refusals | orchestrator-summary.md E15 |

**Items earlier gates sent to Phase 16:**

| From | Item | Status |
|---|---|---|
| Phase 15 gate decision 5 | FECD-1 diagnosis and fix | Done: diagnosis (agent-report-storage-D.md); bounded graph-read retry (GraphRetrySuite); breaker fix (GraphBreakerSuite). Not validated live |
| Phase 15 gate decision 5 | G-28 root cause | Done: "cause narrowed to" (agent-report-rag.md) |
| Phase 15 gate | 3r L1 | Done: resource-limit errors become a bounded search; new abstention code `path-query-bounded` |
| Phase 15 gate | 3r L2 | Deviation (decision 1) |
| Phase 15 gate | N-1; Phase 11 N-2 | Done (`Policy.erV1` kept as a deprecated forwarder; 13 uses in tests/contracts, removal proposed later) |
| Phase 15 minors | L-09 | Done: E2 attempt 2, gate 8 passed in a fresh clone |
| Phase 15 minors | L-07, L-10, L-12, L-14, L-15; FECD-4; H-04, H-05, H-06; AB-4, J-03, L-08 | Done |
| Phase 15 minors | FECD-2 | Done: not a defect (an existing test shows the permit is released) |
| Phase 15 minors | K-01 | Open: a Terraform guard owned by infra-engineer; Phase 17 |
| Phase 15 gate | FECD-3, FECD-6, FECD-8, FECD-9, A/B limitations | Carried into the threats of docs/evaluation.md |
| Phase 15 gate decision 8 | Cost Explorer figure for Phase 15 | Read at E0, not usable: Cost Explorer showed $0.00 for Nov 20, 21 and 22, inconsistent with the month-to-date figure; Phase 17 reconciles (decision 9) |
| Phase 5 gate (re-stated Phase 6) | M3, M4; F8, F9 marks | Done (Phase16RepairSuite; contracts er 231/231) |
| Phase 9 (via Phase 11, 14) | storage retries and cleanup | Retries done (StartLoaderJob throttle); itest cleanup helper proposed later (needs delete permission) |
| Phase 9, 10, 11; Phase 12, 13 | n-1, n-2, n-4; S-8; N-3, N-4, weak contract tests, test-plan items, IdentityCrossRecordSuite, T-02 to T-06; Phase 11 minors; custom metrics | Not built, as planned; status lines written (all "still present" with file:line). B-07 partly resolved in 98b712a; D-04, D-05 status not checked (owner not run) |
| Phase 12, 13 | route time limits | Done (readDeadlineMillis 10 s, 504 READ_TIMEOUT) |
| Phase 13 | C-05, B-02 | Done |
| Phase 14 | D-02, D-03, G-04, G-18, G-19, G-23 | Done |
| Phase 10 | U-12 limit note | Phase 17 final report |
| Phase 11 summary | attempt-07 live benchmark | Not run: its local inputs are gone (planned 4) |

## Results

- **Evaluation:** as in the spec checklist above; full tables in docs/evaluation.md section 10, every number traced to a frozen file.
- **My ratings (E11, n = 6, one rater):** usefulness 4, 5, 4, 2, 4, 5; clarity 4, 4, 3, 2, 2, 4; no answer marked not rateable; dblp.org names exist "yes" for all six.
- **Emulator track (E14):** AWS Emulator package 1.0.569 (aarch64), built from the documented Dockerfile, port 9297 published on loopback only, telemetry off.
  - Setup: 20 create operations (18 resources, one bucket policy, one TTL setting), 0 failed, emulator account confirmed without printing it.
  - Suites: storage 8 tests passed, services 1 passed, both exit 0.
  - Result lines: 122 in all; 117 implemented-passed (3 of them are recorded refusals), 5 not exercised (the Step Functions probes: the saved Phase 15 plan is not present locally); 0 failed, 0 not implemented, 0 infrastructure failures.
  - Both demonstrations demonstrated: the real worker loop moved a poison message to the dead-letter queue after 5 receives, and the re-embed path re-embedded a vector and reported a second delivery as replayed with no second embed call.
  - Observed: the S3 deny-all bucket policy (PutObject, GetObject) and the S3 Vectors bucket policy (GetVectors) were both refused, although the emulator documentation, as cited in docs/emulator.md, says resource-based policies are not evaluated. Recorded as observed, not asserted; the vector refusal matches real AWS in Phase 15 (M-01).
- **Repairs in session 3** (each committed by me; each reviewed by code-reviewer, except the two documentation touch-ups for P16-L-23, L-24 and P16-L-25 to L-30, which the orchestrator spot-checked):
  - repair-E00-rag (P16-L-01, carries P16-K-01) and repair-E00-experiment (P16-L-02, my P16-C-02 interval decision): both reviews PARTIAL;
  - repair-E01-rag (the fail-closed absence rule, P16-L-03 to L-06) and repair-E01-experiment (P16-L-07, L-08): all six RESOLVED; new P16-L-10 to L-15;
  - repair-E02-rag (P16-L-16, hermetic configuration in the rag tests): APPROVE; confirmed by E2 attempt 2 in the same hostile shell;
  - documentation passes on docs/evaluation.md (P16-L-17 to L-24) and docs/emulator.md (P16-L-25 to L-30): all resolved. The E13 writer also closed P16-L-09, C-03, C-04, C-05 and K-03, and the E14 writer closed P16-K-02.
- **Full sbt checks:** session 2 three (2195, 2282, 2285 module tests; 399 contract tests each); session 3 three (2291, 2300, 2306; 399 each); all exit 0, packageCheck ok after each. Final marker 2026-11-22T15:54:10Z.

## What I reviewed

- **Session 2:** the work order (`prompt.md`, sha256 `c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8`); the session 2 summary and notes; reviews a1, a2, b, c and 02; the repair-01 reports; the staged file list and the identifier scan before the commit.
- **Session 3:**
  - the repair reports (E00, E01, E02, E13, E14) and reviews (P16-L-01, P16-L-02, round 2, P16-L-16, evaluation, evaluation-2, emulator);
  - the runbook log; `approvals.md`; the phase summary;
  - docs/evaluation.md and docs/emulator.md;
  - my own command output for every step, and the staged file list and identifier scan before each commit.

## What happened

- **No session 1:** no constitutional file changed in Phase 16.
- **Session 2** (Nov 21, 19:14 to 22:55, unattended) ran every step with no stall and dropped no tier 2 item. R-2, R-3 and R-1b asked for changes, R-1a approved. The one repair round resolved P16-B-01, B-02, A-09, C-01, B-04, B-05 and A-02. The step 10 re-check found P16-K-01 (major, pre-existing since step 4, not caused by the repair), so session 2 ended NOT_READY. The stop-hook waiver (planned 8) was used once, for I1.
- **Session 3** ran in one Claude Code session from Nov 21 night into Nov 22.
  - **Overnight:** after my six decisions on session 2's open items, repair-E00-rag ran with its re-check. The orchestrator also ran repair-E00-experiment, which I had not listed, because my P16-C-02 decision needed an `evaluate.py` change before E3 (accepted, decision 5).
  - **Morning:** I approved one more round with a fail-closed rule for absence claims and a stopping rule: no further round before E1, and L2's status decided at this gate. E0, E1 and E2 followed. E2 attempt 1 failed because the rag tests read my shell's leftover Phase 15 `SCHOLARCHAIN_CFG_*` exports (P16-L-16). After repair-E02-rag, E2 attempt 2 passed in the same shell.
  - **Midday:** E3 to E12. I ran the E4/E6/E9 block twice (the first time at 11:04); the first attempt wrote no group 1 folder, and its retrieval, performance and factory results are byte-identical to the second attempt's. Both stay in the record (manual intervention). Before the freeze I unstaged LibreOffice lock files (manual intervention; not committed).
  - **Afternoon:** E13 and E14 ran side by side (the documentation agent ran no sbt and changed no source). In the emulator terminal I cleared the `SCHOLARCHAIN_CFG_*` variables with `unset -m` before the setup. I closed the probes terminal before copying its `exit=` line; the probes SUMMARY line and probes.jsonl record the result.
- **Ratings and audit (E11):** I rated the six answers and audited the 20 factory events myself. In the audit sheet I entered `1` for yes in both columns on every row.
- **Approval prompts:** none in either session. The guard blocked a few agent and orchestrator commands (an in-place edit form, greps naming environment or trust-store words, a non-literal redirect, a `sed` prefix); each was redone safely or not retried.
- **Commands outside the list:** all read-only (`cat`, `tr`, `rev`, `sed -n`, `awk`, `sort`, `uniq`, `find`, `cd`, `ps`, `date -u`, bounded wait loops, and the others listed in both summaries); agents also copied a file within their own territory for revert tests.


## Decisions

1. **L2: accepted as a deviation.** Its stated scope is done and tested: an absence claim citing a `path=none` item is checked against that item's searched depth, completeness and time window, and a bounded search never supports an absence claim. The fail-closed rule (repair-E01-rag) also makes any cited found path between the pair, an unparsed time phrase, or a third or ambiguous name block a Verified status. Open, routed to Phase 17 with P16-L-14 and P16-L-15:
   - P16-L-10 (major): absence synonyms such as "disconnected" or "not in the same component" are not recognised as absence claims;
   - P16-L-11 (major): a third researcher named with one word ("King") is not counted;
   - P16-L-12 (minor): a cited found path where the pair are inner nodes is missed;
   - P16-L-13 (minor): a time qualifier such as "before 2021-2023" is not caught.

   The reviewer classed these "within rule", reading the rule's phrases as examples. I judge them as vocabulary breadth beyond the requirement as written. They remain a risk for any future live answer (remaining risks) and are listed among the negative results in docs/evaluation.md.
2. **Intervals on the demo answers and attempt-06:** kept. Each table is labelled EXPLORATORY and carries the line that the answers are not a random sample, so the interval only illustrates variability and is not a confidence interval for the system. This follows code-reviewer's ruling and my P16-C-02 decision (no interval over dependent rows: clip 01's two attempts count as one answer; the D-query explain rows are descriptive with n).
3. **P16-C-06:** docs/emulator.md stays at service level, so the Teams post can be based on it without code, prompts or architecture detail.
4. **Planned deviations** 1 to 9: accepted as they happened. Planned 5: the er-v1 decision set is gone, so er-v2 accuracy is not claimed. Planned 8 was used once (I1).
5. **Unplanned deviations:** accepted. Session 2:
   1. the Dockerfile binds through the documented `ENV EMULATOR_BIND_ADDRESS=0.0.0.0`, not the work order's `-b 0.0.0.0` wording (the work order also said "exactly the documented one");
   2. emulator clients are built by their own builders, not through `AwsSdkClients.build`'s `credentials` parameter (P16-A-04; behaviour meets the intent; production builder unchanged);
   3. step 2 partly fixed Phase 5's M2 so that F9 passes;
   4. read-only commands outside the list;
   5. the orchestrator's routing note to the running rag agent (P16-A-08).

   Session 3:
   1. repair-E00-experiment ran before I listed it;
   2. repair rounds beyond decision 15's rule (E01, E02) and minor-only documentation passes against CLAUDE.md's major-only rule, each approved by me;
   3. the full E2 logs stay outside Git, because the commit scan forbids the twelve-zero runs in committed fixture folder names; the raw folder holds the gate lines and the log sha256;
   4. my `unset -m 'SCHOLARCHAIN_CFG_*'` in the emulator terminal;
   5. E5's Hadoop command was taken from LocalBoundedPipeline.scala, because the Phase 15 runbook row does not hold the command text;
   6. read-only commands outside the list;
   7. the new reason code `Reasons.AbsenceNotSearched`, against the "no change to Reasons.all" wording (additive; no consumer uses the list as an allowlist).

   None put an account ID, ARN, bucket name, host or key into a committed file.
6. **AWS's published documentation example access key** in EmulatorWiringSuite.scala (P16-A-01): accepted as a deliberate test input; it is an expected match of the staged-diff scan.
7. **Emulator bonus:** the tier 1 demonstration requirement is met: both demonstration lines read `demonstrated`. The 5 not-exercised Step Functions probes are recorded as a gap, not a pass. The two policy refusals are recorded as observed, against the documentation, not as proof that the emulator evaluates policies like AWS.
8. **K-01 and the recorded minors:** see the table below.
9. **Cost:** the Phase 16 bound was $0 planned and at most $1.50 Anthropic only after a new decision. Phase 16 made no paid call; the bound was not used. Cost Explorer's daily totals read $0.00 for Nov 20, 21 and 22, which is inconsistent with the month-to-date figure, so they are not a Phase 15 cost figure (lag or filter). AWS month to date rose from $20.47 (Nov 21 evening) to $21.22 (Nov 22 08:21) with no AWS use in Phase 16, so the $0.75 is most likely late billing of earlier usage (Phase 15's D28 checks found no remaining resource of the types they covered). Whether Phase 15 stayed within its $9 bound is still unconfirmed; Phase 17 reconciles it.

**Minor findings and limitations:**

| Finding | Decision |
|---|---|
| P16-L-14 (an unbounded absence claim is kept as partially supported against a depth-limited search), P16-L-15 (an absence for unnamed coauthors is Verified) | Phase 17 (rag) |
| K-01 no automated guard for identical service environments | Phase 17 (infra; Terraform guard) |
| P16-B-03 probes.sh:195 unbound variable after an earlier refusal | Phase 17 (infra) |
| P16-A-03 InMemoryGraph time limit untested; P16-A-05 lib.sh comparison test partial; P16-A-06 step D report wording (429 rows) | Phase 17 (storage) |
| P16-A-11 doc comment order (hadoop); P16-A-12 Main.scala:107-108 untested (service) | Phase 17 |
| P16-A-08 trace wrapper does not override `runClassified` (latent, not hit) | Proposed later (rag) |
| P16-A-07, P16-A-13 commands outside the list | Recorded (decision 5) |
| P16-L-23, L-24 (docs/evaluation.md) and P16-L-25 to L-30 (docs/emulator.md) | Resolved by touch-ups; spot-checked by the orchestrator, no second review |
| Proposed later in session 2 and 3 | Phase 17 list: RetrievalEvalJob options check in check.sh gate 8; itest cleanup helper; f2 resolver `.toOption`; remove the `Policy.erV1` forwarder and migrate 13 contract uses, N-4 rename; M2 cache-hit part; EvalMain gold-pair-by-identity mode; ER blocking-settings check; `Settings.fingerprint` in the run report; J-03 second part; counting repeated BLOCKED-ON episodes once; ratings summarizer subcommand (P16-C-04); fixture mode for suite checks (B-04); rag hermeticity source-scan test; a dedicated reason text for a non-contradicting found path; `env.sh` also clears `SCHOLARCHAIN_CFG_*` |

## Manual changes

No code changes by hand. All code is agent-written and reviewed. I ran every script and evaluation, signed the suite, read the bill, rated the answers and audited the factory sample, copied the six demo answers into the repository after two identifier scans (E7), built and ran the emulator, recorded the emulator footage, copied the reports, and committed. I unstaged LibreOffice lock files before the freeze commit.

## Costs

| Provider | Amount |
|---|---|
| AWS (November month to date, Nov 22 08:21 CST; lagging) | $21.22 |
| Anthropic | $0.24 |
| Voyage | $0.00 |
| **Project total billed** | **$21.46 of $25** |
| Phase 16 | $0 (no paid call, no AWS call, no model call) |
| Cost Explorer daily, Nov 20 to 22 | $0.00 each as read; not usable (decision 9) |

## Deferred

- **Phase 17:**
  - L2 vocabulary breadth (P16-L-10 to L-13) and P16-L-14, L-15;
  - K-01 and the minors routed above, and the proposed-later list;
  - reconciling the Cost Explorer figure for Phase 15;
  - removing the Phase 15 `SCHOLARCHAIN_CFG_*` exports from my shell profile (they point at removed resources and hold real identifiers);
  - the items decision 19 of the work order lists: D-01, E-01 to E-04, G-06, G-08 to G-11, G-15, G-16, G-20, G-22, G-25, G-26; B-01, B-03, F-02; the hook shellcheck false alarm; the root README with the agent-replay section; the documentation minors from the Phase 15 gate (H-03, M-03, M-06, M-07, K-02, K-03, L-03, L-05, L-06, L-13); L-04's note; deregistering the 20 task-definition revisions; removing the IAM user `scholarchain-readonly` and the budget `scholarchain-25`; the documented final state; R-1 API docs; U-12's limit note; A-04; the final repository review; the release manifest; `docs/demo-script.md`; the remaining demo clips. Spark C1 and C2 stay proposed later, unscheduled.
- **Outside the phases:** the emulator Teams post and YouTube video, based on docs/emulator.md (decision 18 of the work order).

## Remaining risks

- No fix in this phase is validated live, FECD-1 included.
- Until P16-L-10 and L-11 are fixed, a live answer could show an absence claim as Verified when it uses a synonym that a cited path contradicts, or names a third researcher with one word.
- Group 4 rests on internal consistency, one rater and six rated answers; my rating was not blind to the statuses shown in R05's answer text. The benchmark labels come from the Phase 7 graph run, not the deployed Phase 14 graph.
- The emulator results show compatibility for the operations ScholarChain calls, not AWS behaviour under load, permissions, Neptune, the query pipeline or the LLM; the package is `LTS/latest` (version 1.0.569 recorded).
- About $3.54 of the $25 remains, and Phase 15's final cost is not yet reconciled.
- Phase 17's final gate must rely on the documented final state, recorded runs and local runs.
- The full E2 logs and the Phase 15 evidence archive hold identifiers or flagged strings and stay outside Git and private.

## Acceptance

No constitutional change in Phase 16, so there is nothing to ratify.

I accept Phase 16 as of the 8 commits listed in `commit.txt`, from `d37389bf764c6b6fe2c579167a35919973409cb6` to `be1ec675b0fbd9e7321d1550de951ddf4fe4b038`.
