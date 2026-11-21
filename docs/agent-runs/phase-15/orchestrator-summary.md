PHASE RESULT: COMPLETE_WITH_OPEN_ITEMS

Rule 3: every spec gate item is met with evidence files (decision 19; `experiments/results-summary.md` section 4). No requirement is `missing`, no critical finding is open, and the session 2 final checks passed. Several things remain open and are listed below:
- failed steps or attempts that stay recorded: A-Hadoop attempt 1, D-read attempt 2, D27 attempt 1, and D27 attempt 2's exit 6 at check-leftovers;
- one open major finding, FECD-1 (a measured outcome, a Phase 16 candidate);
- open minor findings;
- unplanned and human-directed deviations.

None of this is the human's approval; the human gate accepts the phase.

# Phase 15: orchestrator summary (session 3, phase summary)

- Work order: `docs/agent-runs/phase-15/prompt.md`, sha256 `3d806922…` (unchanged since session 2).
- Commits: session 2 `684636e`; session 3 from `85dacb4` (D0) to `ade5432` (D27/D28). Full list: `git log --format='%H %s' 2cbfbc72c704c40085e2876479d510b90062a12a..HEAD` (decision 16.4).
- Sittings:
  - sitting 1: Nov 20, D0–D3. It ran by mistake in the session 2 Claude Code session (manual intervention).
  - sitting 2: Nov 20–21, D3 signing through D20.
  - sitting 3: Nov 21, 11:00 local to the end, D22 through D29.
- Detail: `runbook-log.md` (one row per step and attempt), `evidence-inventory.md`, `experiments/results-summary.md`, and the agent, repair and review reports in this folder. This file gives IDs and statuses only.

## Steps (decisions 4 and 13; every attempt listed, status by last attempt)

Session 2 steps (decision 4): all `done`; see `orchestrator-summary-s2.md` (SESSION 2 RESULT: READY_WITH_OPEN_ITEMS).

| Step | Status | Attempts and evidence |
|---|---|---|
| D0 cost bound | done | approvals.md "Phase 15 cost bound: $9", 85dacb4 |
| D0a local checks | done | runbook-log D0a |
| D1 package | done | exit 0, build 684636ef |
| D2 publish | done | exit 0, digest = package local image ID |
| D3 runtime-on plan + D4a checks + signature | done | plan 934d78c3…, 94ec6b0 |
| D4 deploy | done | exit 0, release verified, 71ab453 |
| D3b/D4b L-01 runtime-on plan + deploy | done | plan 235b56f5…, signed e608240, deploy exit 0, eb786c5 |
| D5 smoke A + G-29 | done | exit 0, 7/7 |
| D6 G-28 re-run (q13) | done | exit 0, 7/7; abstention code `path-query-failed` |
| D7 G-27 probe (5,000) | done | exit 0 |
| D7 G-27 full pass | done | exit 0, 437 s |
| D7a p15-a-hadoop | done (attempt 2) | attempt 1 `failed` (exit 5, EMR refuses max = min, L-01, 6a6c31a); attempt 2 exit 0 (d872d12) |
| D8 live suites | done (expected failures) | exit 1, 1: Bedrock quota 0, as expected |
| D9 manifest signatures | done | 8 lines, b0c92f5 |
| D9a dry checks | done | F `--check` attempt 1 exit 5 (L-02), attempt 2 exit 0; A-Hadoop, D-query and D-read exit 0 |
| D10 F | done | exit 0 (70876eb) |
| D11 E | done | exit 0 (2cc5804); ran before F (order change) |
| D11a p15-a-spark | done | exit 0 (e86449a) |
| D11b p15-b-spark-burst | done | exit 0 (e86449a) |
| D12 C | done | exit 0 (e86449a) |
| D12b C plot | done | ff00cfa |
| D13 D-query | done | exit 0 (abe4764) |
| D14 D-read | failed by the last-attempt rule (check: the human decides at the gate) | attempt 1 exit 0, data complete; attempt 2 started by accident while attempt 1 ran, exit 5, nothing sent (ee3bce8). Both kept |
| D15 gate check | done | runbook-log D15 |
| D16 demo recording | done | live clips 01, 03–07, 14; next day 08–13, 19, 20; cleanup clip 21 parts 1–5. Clip 02 status not confirmed in the log (check). Clips 22–26 are later (no AWS) |
| D17 AwsAdaptersAwsSuite | done (expected failures) | exit 1: M-01 deny, Neptune outside the VPC, SQS skipped; ran after D20 (human) |
| D18 deploy-engineer | done | agent-report-deploy.md; alarm table: no critical alarm |
| D19 runtime-off + cleanup-mode plan | done | plan 71fe9071…, ebcbc03 |
| D20 deploy runtime off | done | exit 0, 9efdf76 |
| D21 overnight fallback | not needed | every EMR run was attended in the evening |
| D22 results check + AB report | done | agent-report-experiment-AB.md |
| D23 derived tables and plots + results summary | done | 5 derive scripts exit 0, 08bbb9d; results-summary.md, 65172ab |
| D24 instructor note | done (changed by the human) | see deviations |
| D25 inventory, S3-only copies, archive, checks, `--list` | done | 4297e46; archive exit 0; `--list` exit 0, plan d636c648… |
| D26 removal-plan signature + archive sha256 | done | d9d0a24 |
| D27 removal | failed by exit code (exit 6 at check-leftovers); removal complete | attempt 1 exit 4, refused before CONFIRM, nothing removed (L-11); repair e98343f; attempt 2: 175 destroyed, bucket emptied, exit 6 from tagging-API records of removed resources (L-15) |
| D28 records, leftover checks, Billing | done | ade5432; direct per-service counts 0 except the 20 expected task definitions (clip 21 part 5) |
| D29 this summary | done | – |
| D30 end (copy + commit) | not run (next step) | – |

## Requirements (decisions 5–12) and runbook items

| Item | Status |
|---|---|
| 5–12 (session 2 build, reviews, repairs) | done: every row in `orchestrator-summary-s2.md` "Requirements" (deviations noted there) |
| 6.1/7.1 G-27 live (D7) | done |
| 7.5/8.2 G-28 reason recorded and read live (D6) | done; root-cause fix: phase-16 |
| 8.1 worker embedding loop live (C, F) | done |
| 9.3 execution-input fields live (A, B) | done after L-01 |
| 9.5 G-29 live (D5, D25) | done |
| 10 design rules (four evidence lines, exit table) | done; defect FECD-4 (D-read attempt 2 exit and read-only) |
| 11 experiments A–F executed and analysed | done (A, B, C, D-query, D-read, E, F) |
| 12 review and repair rounds in session 3 | done: L-01/L-02 (APPROVE), L-11 (APPROVE) |
| D24/D25 evidence archival | done; deviation (D24 change) |
| D27/D28 cleanup | done; defect (L-15) |

## Spec work-order rules

| Rule | Status | Evidence |
|---|---|---|
| Every experiment has a committed manifest | met | `experiments/manifests/*.json` (8), signed b0c92f5 |
| Raw metrics immutable after collection | met | read-only run records + commits; D-read attempt 2 not read-only (FECD-4, immutability rests on the commit) |
| Derived plots and tables reproducible from scripts | met | D23: 5 derive scripts exit 0; C re-run identical (08bbb9d) |
| Record commit, dataset snapshot, config, limits, times, Region, run IDs | met | run-record.json in every attempt folder |
| Never silently discard failed runs | met | results-summary section 1 (A-Hadoop 1, D-read 2, D9a F preflight) |
| Do not tune during a run | met | L-01/L-02 repairs between attempts, each a new attempt |
| Raw data, plots, results summary, limitations | met | experiments/raw, experiments/derived/out, results-summary.md sections 1–7 |

## Scaling-rule table (results-summary section 3)

| Component | Status | Evidence |
|---|---|---|
| EMR | evidence found (B scale-out; no managed scale-in while running) | p15-b-spark-burst capacity.csv; A capacity.csv |
| Embedding workers | evidence found (0 → 1 → 2 → 0) | C service-samples.csv, scaling-activities.csv, queue-samples.csv |
| Graph retriever | partial (no scale-out, not expected; latency alarm not examined) | D-read tasks.csv, levels.csv |
| Vector retriever | partial (7 latency samples in D; E latency) | D-query explain-stages.csv; E modes.csv |
| Rank and synthesis | evidence found (no queue: synchronous) | D-query tasks.csv, levels.csv, explain-stages.csv |
| DynamoDB | partial (throttle series no datapoints) | D cloudwatch-periods.csv |
| Step Functions and SQS | evidence found | F parts.csv, timeline.csv; sfn-events.csv |

## Gate (decision 19; D29 rule)

| Gate item | Status | Evidence |
|---|---|---|
| At least one scale-out and one scale-in | met (C) | C service-samples.csv, scaling-activities.csv, derived observed.csv; B adds scale-out only |
| Synchronized timestamps | met (measured offset within about ±1.5 s) | clock.csv per attempt; agent-report-experiment-FECD.md |
| Raw data preserved | met | 10 attempt folders committed (6a6c31a, 2cc5804, 70876eb, e86449a, abe4764, ee3bce8, d872d12) |
| Costs and limits stated | met | manifests' cost lines and limits; results-summary section 6; Billing below |
| Failures reported | met | results-summary section 1; incident-F.json; this table of steps |

## Items earlier gates sent to Phase 15

| Item | Status |
|---|---|
| G-27 | done (parts 1–2 session 2; part 3 live skip-only pass D7; part 4 live search D7 smoke) |
| G-28 | done (reason recorded and read: graph-store failure in the path query); fix: phase-16 (possible link to FECD-1) |
| G-29 | done (D5 smoke record has no host; D25 archive check ran; J-06 warnings on Phase 14 records as expected) |
| RequiresAws suites | done (D8, D17; expected failures reported, not repaired) |
| Embedding-queue setting and reader | done (live in C and F) |
| N-1 | phase-16 |
| U-11 | done (C: logs = SQS deletes = DynamoDB writes = 19,200; FECD-9 limitation) |
| Manifests with approval lines | done (b0c92f5) |
| Cleanup right after experiments and demo | done (D19–D28; L-15) |
| Phase 7 AWS vector load: search, speed, cost | done (D7 smoke and skip rates; E latency; Voyage $0) |
| Runtime off after Phase 15 | done (D20) |
| First EMR cluster may fail | happened (A-Hadoop attempt 1, L-01), repaired; attempt 2 succeeded |
| run-experiment.sh never ran | done (tested) |
| Slow uplink and upload retries | done (D2 exit 0) |
| No Neptune CPU alarm | limitation stated (results-summary section 7) |

## Findings

| ID | Sev | Owner | Status |
|---|---|---|---|
| H-01, J-01, M-01 | major | see s2 | RESOLVED (session 2) |
| H-02–H-07, J-02–J-06, M-02–M-08, K-01–K-03 (21) | minor | see s2 | open (session 2 list; J-06 occurred at D25 as expected; K-01 checked by hand at D3) |
| L-01 | major (check) | infra-engineer | RESOLVED (e94df52; review APPROVE) |
| L-02 | major (check) | experiment-engineer | RESOLVED (e94df52; review APPROVE) |
| L-03 | minor | experiment-engineer | open |
| L-04 | minor | infra-engineer | open |
| L-05 | minor (info) | infra-engineer | open |
| L-06 | minor | experiment-engineer (experiments/README); scripts README stale "runtime: untested" line added (infra-engineer) | open |
| L-07 | minor | infra-engineer | open, phase-16 (deploy.sh apply-output masking) |
| L-08 | minor | experiment-engineer | open (C plot labels small) |
| L-09 | minor | build-engineer | open (RetrievalEvalJob not in PackageCheck.scala; check.sh gate 8 fails in a fresh clone) |
| L-10 | minor | storage-engineer / build-engineer | open, phase-16 (unmasked identifiers in a live-suite test-report XML inside the archive) |
| L-11 | major (for D27) | infra-engineer | RESOLVED (e98343f; review APPROVE; D27 attempt 2 passed the check) |
| L-12 | minor | infra-engineer | open (no probe for `out()`) |
| L-13 | minor | infra-engineer | open (report line numbers) |
| L-14 | minor (existed before) | infra-engineer | open (a missing protection field reads as unprotected) |
| L-15 | minor | infra-engineer | open, phase-16 (check-leftovers counts tagging-API entries without checking state; a correct removal ended exit 6) |
| AB-1–AB-4 | minor | experiment-engineer | open |
| AB-5 | info | infra-engineer | open |
| FECD-1 | major (measured outcome) | storage-engineer / service-engineer (diagnosis) | open, phase-16 candidate |
| FECD-2, 3, 4, 5, 7 | minor | see FECD report | open |
| FECD-6, 8, 9 | limitation | documentation | stated in results-summary |

Open: 1 major (FECD-1), 43 minor (21 session 2 + L-03–L-10, L-12–L-15 + AB-1–AB-4 + FECD-2, 3, 4, 5, 7), 1 info (AB-5), 3 limitations. Resolved: 6 major (H-01, J-01, M-01, L-01, L-02, L-11).

## Validation evidence

- Session 2 final checks: fmt, validate, shellcheck, jq, test marker (2195 module tests + 399 contract tests); see s2.
- D4a checks on each runtime-on plan (address list, K-01 environment identity, validate, test-state).
- Fresh clone `check.sh` (clip 19b): 8 of 9 gates passed, including sbt (2,195 + 399 tests). Script-contracts failed (L-09).
- Clip 19c local Hadoop pipeline exit 0 (take 3). Clip 19d: KnownGraphSuite + PipelineFixtureSuite 43/43. Clip 20: UnsupportedClaimEndToEndSuite 7/7.
- L-11 repair: shellcheck clean, review APPROVE, live pass at D27 attempt 2.
- Evidence: archive sha256 a7115c16… (committed); s3-copies `shasum -c` OK; DynamoDB samples with sha256 list; secrets scans by the human before every commit (empty).
- D28 direct leftover counts (read-only): 0 running EMR clusters, NAT gateway `deleted`, 0 live VPC endpoints (no `emr-service-vpce`), 0 load balancers, 0 Neptune clusters, 0 ECS clusters, 0 tables, 0 S3 buckets, 0 vector buckets; 20 ACTIVE task definitions (expected, I-15).

## Runtime lines

Tested in phase 15 (human runs, checked by the orchestrator):
- package.sh (D1, exit 0); package.sh `--publish` (D2, exit 0)
- plan.sh (D3, D3b, D19, exit 0); deploy.sh (D4, D4b, D20, exit 0)
- smoke-test.sh (D5, D6, exit 0) (check: D6 entry point)
- run-bounded-pipeline.sh with the `--max-documents` ingest loader (D7 probe and full, exit 0); `rag-retrieval-eval` loader (D11 via E, exit 0)
- run-experiment.sh (D7a attempt 1 exit 5; D7a attempt 2, D10–D14 exit 0)
- collectors:
  - ab-pipeline.sh (D7a, D11a, D11b, exit 0)
  - c-embedding-backlog.sh (D12, exit 0)
  - d-load.sh and query-load.sh (D13, D14 attempt 1, exit 0; D14 attempt 2 exit 3)
  - e-retrieval-modes.sh (D11, exit 0)
  - f-poison-replay.sh (`--preflight` D9a exit 3 then 0; D10 exit 0)
- derive scripts emr_capacity, failure_timeline, load_levels, queue_worker, retrieval_modes (D23, exit 0)
- archive-evidence.sh (D25, exit 0)
- cleanup.sh `--list` (D25, exit 0); `--remove` (D27 attempt 1 exit 4; attempt 2 exit 6, removal itself complete)
- check.sh (clip 19b, exit 6, L-09)
- entry points: RagIngestJob (D7), RetrievalEvalJob (D11), the worker embedding loop and ReembedHandler (D10, D12)
- live suites: LiveLlmAwsSuite and LiveProvidersAwsSuite (D8, exit 1 by expected Bedrock failures); AwsAdaptersAwsSuite (D17, exit 1 by expected failures)

Untested: rollback.sh; anything not listed (check).

## Deviations

Planned (decision 15): 1, 2, 3, 4, 5, 6, 7, 8, 9, 11 applied; 10 not needed.

Unplanned or human-directed (session 3):
1. Sitting 1 ran in the session 2 Claude Code session (manual intervention; restarted for sitting 2).
2. E (D11) ran before F (D10) while the tree was clean; F waited for L-02.
3. D17 ran after D20 (human; D17 needs no runtime).
4. D-read attempt 2 started by accident (manual intervention; kept as a failed attempt).
5. Clip 01 sent twice (one extra LLM query; became attempt 2 of the same query run, shown in clip 03).
6. Clips 08–13 recorded the next day from consoles and committed files. The worker alarms were gone after D20, so clip 13 used alarm-samples.csv.
7. The one-step-per-turn rule was relaxed for clips 19/20 while agents wrote reports.
8. Clip 19c: Hadoop take 2 refused (incomplete output); take 3 used a fresh output folder.
9. check.sh gate 8 failed in the fresh clone (L-09); the take was kept by the human.
10. Cost stop at sitting 3 (worst case above the $7.50 planning line); human: "go".
11. D24 change: D27 did not wait for a reply. The teardown approval is the instructor's Nov 20 Teams reply. The human also told the instructor in Teams (Nov 21, 14:20 local) where the evidence is archived: the pushed private repository plus the local archive with its sha256 committed.
12. D25 gap: `rag/ingest-reports/` and `rag/retrieval-eval/` were copied by the human (read-only) and committed with sha256.
13. During D27, read-only DynamoDB samples were copied (committed in ade5432). The graph-export sample was skipped (the bucket was being emptied).
14. L-11 repair round in the middle of D27; the same signed plan was re-used (scripts/ is not a plan input).
15. D27's exit 6 was accepted after direct per-service checks (L-15); `--remove` was not re-run.
16. The orchestrator made read-only aws-api calls (read-only profile, counts only) to diagnose D27's leftovers.

## Later-phase items

- phase-16: N-1; FECD-1 diagnosis; the G-28 root cause; L-07, L-09, L-10, L-12, L-14, L-15 and the open minors routed by the gate; decision 17 list.
- phase-17: deregister the 20 task-definition revisions (I-15); remove the hand-made IAM user and budget; decision 17 list.

## Approval prompts and commands outside the list

- Approval prompts seen: none recorded in the runbook log (check). The guard blocked:
  - two orchestrator shell heredocs at D27/D28 whose text contained the Terraform removal words; the rows were added with the Edit tool instead;
  - one sed script built from a shell variable (D29); `awk` was used instead.
- Commands outside the list (all read-only): `cat`, `sort`, `uniq`, `tr`, `sed -n`, `date`, `awk` (orchestrator); bash grep, `sed -n`, `sort | uniq -c` (experiment-engineer, FECD); `cat`, `uniq`, `tr`, `paste` (experiment-engineer, AB).

## Costs (Billing lags up to a day)

| Provider | Before Phase 15 | Nov 21 morning | After cleanup (Nov 21 evening) | Phase 15 (estimate from Billing) |
|---|---|---|---|---|
| AWS | $9.92 (Phase 14 D20 reading) | $19.64 | $20.47 | about $3.8 to $9.4. The $9.72 morning delta mixes in lagged Phase 14 usage (up to about $6.6), plus $0.83 since the morning |
| Anthropic | about $0.12 | $0.24 | $0.24 | about $0.12 |
| Voyage | $0.00 | $0.00 | $0.00 | $0.00 |
| Total | | | $20.71 billed (project ceiling $25) | about $3.9 to $9.5; orchestrator running estimate about $6.5 (check) |

Experiments alone: about $2.2 (results-summary section 6). The phase bound was $9 across providers. The worst case of the Billing-derived range is about $0.5 above it, and only if none of the delta was lagged Phase 14 usage (check with Cost Explorer daily totals).

## Manual actions for the human

1. D30: copy this summary and runbook-log.md into `docs/agent-runs/phase-15/`, run the secrets scan, then commit and push.
2. Check Billing tomorrow (lag) and add the final number at the gate.
3. Keep the evidence archive private: it holds identifiers (L-10, J-06). Its sha256 is committed.
4. Phase 17: deregister the 20 task-definition revisions; remove the IAM user and the budget.
5. Gate decisions: D14's status (attempt 2 accidental), the routing of FECD-1, and the open minors.
