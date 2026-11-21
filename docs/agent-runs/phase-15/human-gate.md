# Phase 15 human gate: run the data pipeline and elasticity experiments

## Gate

**ACCEPT, with open items.** Phase 15 ran every experiment on the live course environment, recorded the live demo footage, archived the evidence, and removed the environment:
- session 2 built G-27 (batched ingest and the re-embed path), G-28 (abstention reason codes), the worker embedding loop, `rag-retrieval-eval`, the Terraform and script changes, the experiment tools and the eight manifests. Three major findings were fixed in its one repair round, and the re-check was APPROVE;
- session 3 deployed from signed plans, ran A (Hadoop and Spark halves), B, C, D (query and read), E and F through `scripts/run-experiment.sh`, recorded the live demo clips, turned the runtime off, archived the evidence, and removed 175 Terraform resources;
- all five spec gate items are met with evidence files (`experiments/results-summary.md` section 4). Experiment C shows the worker scaling 0 → 1 → 2 → 0, both in the describe-services samples and in the scaling activities;
- G-27 is closed live: the skip-only pass wrote nothing, and the first live vector search ran (D7). G-28 now records its reason: `path-query-failed`, a graph-store failure in the path query (D6).

One major finding stays open for Phase 16: **FECD-1**. Under concurrent read load, the graph route fails at the Neptune layer from 5 clients on (28 × 502, then 526 × 503 `CIRCUIT_OPEN`), while Neptune CPU stays at or below 23 %. Phase 15 cost is within its $9 bound by the orchestrator's running estimate (about $6.5). The Billing-derived range (about $3.9 to $9.5) runs up to about $0.5 over the bound in the worst case, because Phase 14 usage that billed late cannot yet be separated from Phase 15's; Cost Explorer confirms on Nov 22.

## Spec checklist

| Item | Status | Source |
|---|---|---|
| experiment-engineer executes approved experiments without changing production algorithms | Deviation (planned 1): I ran every experiment through `scripts/run-experiment.sh`. experiment-engineer wrote the tools and manifests and analysed the results read-only. No production algorithm changed during or between runs | results-summary 7; runbook-log |
| A: bounded corpus at minimum EMR capacity; input, output, tasks, duration, shuffle, failures, cost | Done (attempt 2). Hadoop J01–J09 at max = min = 2, counters equal to Phase 14's. Spark at max 2, 32 partitions. Missing: Hadoop reduce counts and shuffle (not logged by the driver) | results-summary 2.6, 2.7 |
| B: larger partition count; observe managed scaling; requested, provisioned, released capacity | Done, observational: 128 partitions, max 4. Requested and running 2 → 4 from 23:08/23:12. Released at termination (a lifecycle record, not a scale-in). n = 1, confounded | results-summary 2.8 |
| C: controlled backlog; queue depth, task count, throughput, retries, drain | Done: 19,200 re-embeds, worker 0 → 1 → 2 → 0, about 9.0 messages/s at 1 task and 24.0 at 2, 0 retries, drain 1,172 s (under concurrent enqueue, FECD-6) | results-summary 2.3 |
| D: at least four concurrency levels; p50/p95/p99, throughput, errors, tasks, CPU, memory, Neptune, DynamoDB, vector and LLM latency | Done under the deployed limits (planned 5): query 1, 2, 4, 8; read 1, 5, 10, 20. Missing: Neptune latency as a separate measure, DynamoDB throttle datapoints, more than 7 vector-latency samples | results-summary 2.4, 2.5 |
| E: graph-only vs S3-Vectors-only vs fused | Done, EXPLORATORY (11 questions). Fused is experiment-only (planned 4). No OpenSearch tier (not implemented) | results-summary 2.2 |
| F: one safe failure; retry, dead letter, idempotency, recovery | Done: poison message to the DLQ after 5 receives, sequential replay found the completion record, API replay 200 with `Idempotent-Replayed`, changed body 409; incident record with every spec field | results-summary 2.1; incident-F.json |
| Rule: every experiment has a committed manifest | Met: 8 manifests, signed (b0c92f5) | approvals.md |
| Rule: raw metrics immutable after collection | Met: run records read-only and committed. D-read attempt 2's record is not read-only (FECD-4); its immutability rests on the commit | results-summary 4 |
| Rule: derived plots and tables reproducible from scripts | Met: 5 derive scripts exit 0; the C re-run was identical (08bbb9d). Some totals were summed by hand (proposed later: a derive table) | results-summary 6, 8 |
| Rule: record commit, snapshot, configuration, limits, times, Region, run IDs | Met: run-record.json in every attempt folder | evidence-inventory 1 |
| Rule: never silently discard failed runs | Met: A-Hadoop attempt 1 (L-01), D-read attempt 2, and the D9a F preflight (L-02) are kept and listed | results-summary 1 |
| Rule: do not tune during a run | Met: the L-01 and L-02 repairs came between attempts, each followed by a new attempt | orchestrator-summary |
| Rule: raw data, plots, results summary, limitations | Met | experiments/raw, experiments/derived/out, results-summary 1–8 |
| Scaling-rule table (7 components) | EMR, embedding workers, rank and synthesis, Step Functions and SQS: evidence found. Graph retriever, vector retriever, DynamoDB: partial (no scale-out was expected; latency alarm not examined; throttle series have no datapoints) | results-summary 3 |
| **Gate:** at least one scale-out and one scale-in shown | **Met** (C): samples 0 → 1 (23:01:39) → 2 (23:04:26) → 0 (23:24:59) and scaling activities out 23:01:25, out 23:04:25, in 23:24:39 | results-summary 4 |
| **Gate:** metrics use synchronized timestamps | **Met**, as designed and measured: offset 0 to +0.5 s, about ±1.1 s, in every attempt with a clock file | agent-report-experiment-FECD.md |
| **Gate:** raw experiment data preserved | **Met**: 10 attempt folders committed | results-summary 4 |
| **Gate:** costs and limits stated | **Met**: manifest bounds and limits, real caps with file:line, Billing and estimates | results-summary 2, 6 |
| **Gate:** failures reported, not edited out | **Met** | results-summary 1, 7; incident-F.json |

**Items earlier gates sent to Phase 15:**

| From | Item | Status |
|---|---|---|
| Phase 14 | G-27 (batched writes, limits, second pass, live vector search) | Done: parts 1–2 in session 2; the skip-only pass and live search at D7 (437 s full pass) |
| Phase 14 | G-28 (record why q13 abstains) | Done: QueryAudit v3 `abstentionCodes`; D6 read `path-query-failed`. Root cause: Phase 16 (possible link to FECD-1) |
| Phase 14 | G-29 (no API host in smoke records) | Done: D5 record clean; the D25 archive check flagged the Phase 14 records as expected (J-06) |
| Phase 14 | `RequiresAws` suites | Done, with expected failures only. D8: Anthropic and Voyage pass; both Bedrock tests fail on quota 0. D17: S3 and DynamoDB pass; S3 Vectors refused by the bucket policy by design (M-01); the Neptune template and the environment check fail outside the VPC; SQS skipped. Reported, not repaired |
| Phase 14 | Embedding-queue setting and reader | Done, live in C and F |
| Phase 14 | N-1 | Phase 16 (decision 2) |
| Phase 14 | U-11 (exact counters under load) | Done: logs = SQS deletes = DynamoDB writes = 19,200 (FECD-9: in-process counters not exported) |
| Phase 14 | Manifests with approval lines | Done (b0c92f5) |
| Phase 14 | Cleanup right after the experiments and the demo recording | Done: D19–D28 |
| Phase 7 | AWS vector load: real search, speed, cost | Done: D7 search and skip rates; E latency; Voyage $0 |
| Phase 14 risk | `run-experiment.sh` never ran | Done: ran for every experiment |
| Phase 14 risk | First EMR run may fail | Happened: A-Hadoop attempt 1 (L-01, EMR refuses max = min), repaired, attempt 2 succeeded |
| Phase 14 risk | Slow uplink and upload retries | D2 exit 0 |
| Phase 14 decision 8 | No Neptune CPU alarm | Limitation stated (results-summary 7) |

## Results

- **A, Hadoop half** (attempt 2): J01 read 13,073,341 records; 155,978 works, 758,328 mentions, 99 rejects; J09 20,125,392 records. Execution 4,875 s at a fixed 2 core nodes, with up to 514 pending containers. About $0.95 (estimate).
- **A, Spark half and B:** identical outputs (200,484 researchers, 155,978 works, 1,612,421 full-window coauthor edges, 0 invalid). B scaled out 2 → 4; graph step 1,416 s vs 1,488 s (−4.8 %, confounded, n = 1). About $0.44 and $0.56 (estimate).
- **C:** 19,200 sent, received and deleted; 0 retries; worker 0 → 1 → 2 → 0; drain 1,172 s.
- **D-query:** error rate 0 %, 5.3 %, 86.7 %, 93.3 % at 1, 2, 4, 8 clients. From 4 clients on, the service answers OVERLOADED (4 concurrent queries per task by design; FECD-2 hypothesis: a timed-out workflow keeps its permit). LLM synthesis p50 9.7 s, p95 15.4 s.
- **D-read:** the API Gateway throttle (5/s, burst 10) dominates every level above 1 client. The graph route fails at Neptune from 5 clients on (FECD-1).
- **E (exploratory, n = 11):** MRR 0.818 graph-only, 0.003 vector-only, 0.776 fused. No ranking of modes is claimed.
- **F:** the poison message reached the DLQ about 2.6 minutes after it was sent, with all five receives inside one minute (released at once, not after 5 × 300 s); replay and idempotency as designed.
- **D18 (deploy-engineer, read-only):** the live services matched the release (digest three-way, load balancer targets healthy). My alarm table showed no critical alarm: the DLQ alarm (F, on purpose), the worker scale-in alarm and the three AlarmLow alarms are resting states.
- **Experiments total:** about $2.2 (estimate). Phase 15 overall: orchestrator running estimate about $6.5; Billing-derived range about $3.9 to $9.5.
- **Repairs in session 3:**
  - L-01 (no managed-scaling policy at max = min) and L-02 (full embedding model ID in the C and F collectors);
  - L-11 (`cleanup.sh` read a boolean output as empty and refused before CONFIRM).

  Each was reviewed (APPROVE) and committed by me.

**Plans applied in this phase** (all signed in `approvals.md`): `934d78c3…` (runtime on, D4), `235b56f5…` (L-01 state machine, D4b), `71fe9071…` (runtime off and cleanup mode, D20), `d636c648…` (removal, D27).

## What I reviewed

- **Session 2:** the work order (`prompt.md`, sha256 `3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0`); the session 2 summary and notes, which index the session 2 agent reports; reviews a, b, c and 02; the repair-00 and repair-01 reports.
- **Session 3:**
  - the repair reports (D7a, D10, D27) and reviews (L-01/02, L-11);
  - the FECD, AB and deploy agent reports;
  - the runbook log; `approvals.md`; the evidence inventory; the cleanup plan record; the three release records; the results summary; the phase summary;
  - my own script output for every step;
  - the spec's Phase 15 section and its demonstration requirements.

## What happened

- **No session 1:** no constitutional file changed in Phase 15.
- **Session 2** (Nov 20, 11:45 to 14:46) ran with no stops. Two interface repairs (repair-00 domain and storage) were raised by consumers. Reviews found three major issues: H-01 (the embedding-queue URL only on the worker broke the shared config fingerprint), J-01 (F could skip its replay check) and M-01 (the vector bucket policy refuses the admin profile, so C and F take keys from DynamoDB). All three were fixed in the one repair round; re-check APPROVE.
- **Session 3:**
  - **Sitting 1** (Nov 20, D0–D3) ran by mistake in the session 2 Claude Code session. I ended it after D3, committed the log and restarted in a fresh session (manual intervention).
  - **Sitting 2** (Nov 20–21, D3 signing to D20) ran the experiments and the live demo recording. D-read attempt 2 was started by accident while attempt 1 waited for metrics (manual intervention).
  - **Sitting 3** (Nov 21) did the remaining console and local clips, D8 and D17, the results, the archive and the removal. D27 attempt 1 was refused before CONFIRM (L-11). After the repair, attempt 2 removed everything; its exit 6 came from tagging-API records of already-removed resources (L-15).
- **Prompt file:** before session 2 I removed the [decided] and [proposed] marks and the drafter references from `prompt.md`; the decisions are unchanged.
- **Approval prompts:** at the start of sitting 3 the guard asked to confirm a `cp` that read `docs/agent-runs/phase-15/runbook-log.md` into the scratchpad. I approved it, because it only read the evidence folder. The guard also blocked two orchestrator heredocs and one `sed` script; they were redone safely.
- **Commands outside the list:** all read-only (`cat`, `sort`, `uniq`, `tr`, `sed -n`, `date`, `awk`, `paste`, Bash `grep`).
- **Outside assistance:** I used an outside assistant (a Claude chat) to review the work order, the orchestrator's commands and the demo plan. It wrote no repository file.

## Decisions

1. **Planned deviations** 1–9 and 11: accepted as they happened. Number 10 was not needed.
2. **Unplanned deviations:** session 2's 10 and session 3's 16 (phase summary) are accepted. Each is recorded in the runbook log or in its own commit, and none put an account ID, ARN, bucket name, host or key into a committed file. This includes the M-01 choice (C and F take vector keys from DynamoDB; no security change), E running before F, D17 running after D20, and the read-only aws-api calls used to diagnose D27's leftovers.
3. **D14 (D-read):** attempt 1 is the result; its data is complete. Attempt 2 stays in the record as a failed attempt that sent nothing (key not exported in that shell), not deleted.
4. **D27 (removal):** complete, with a warning. Exit 6 came from `check-leftovers` counting tagging-API entries of resources that were already removed (L-15). Direct per-service checks at D28 found 0 running EMR clusters, live NAT gateways, VPC endpoints, load balancers, Neptune clusters, ECS clusters, tables, buckets and vector buckets, no security group besides the default, no tagged KMS key and no secret. So EMR's own endpoint and security group, listed for Phase 17, are already gone. `--remove` was not re-run. L-15 goes to Phase 16.
5. **FECD-1:** major, Phase 16. Diagnose it and fix it in code with tests where a defect is found. The environment is gone, so any fix is **fixed in code, not validated live**, and the evaluation and final report must say so. If it isn't fixed, it is a known limitation. The G-28 root cause goes with it.
6. **Evidence archival:** the instructor approved the teardown in Teams on Nov 20 ("Yes, recording the required demonstrations while AWS resources are running and then tearing down the infrastructure is perfectly acceptable… Just make sure your video and archived evidence clearly demonstrate the actual execution of the required functionality."). On Nov 21 (14:20 local) I told him in Teams where the evidence is archived: the pushed private repository, plus the local evidence archive with its sha256 committed. He had not replied when this gate was written. The removal went ahead on the Nov 20 approval.
7. **Evidence archive** (`a7115c16…`): local only, never shared as is. It holds identifiers (L-10, J-06). Copies are under `evidence/archives/` and in `~/scholarchain-evidence-p15/`. The committed S3 copies and DynamoDB samples passed the orchestrator's identifier scan.
8. **Cost:** the Phase 15 bound was $9 across providers. The orchestrator's running estimate (about $6.5) is within it; the Billing-derived range (about $3.9 to $9.5) passes it only if none of the baseline delta was late-billed Phase 14 usage. I check Cost Explorer's daily totals on Nov 22 and record the figure at the Phase 16 gate. The project stands at about $20.71 of $25. **From now on, no paid AWS work is planned;** any paid LLM call in Phase 16 needs a cost estimate first.
9. **E** stays EXPLORATORY (11 questions), with no ranking of retrieval modes.
10. **The demo:** recorded are items 9–16 and 18–21 (live), item 22 (during the cleanup), item 7 (clip 19: the sbt gate passed in a fresh clone, 2,195 module and 399 contract tests; `check.sh` failed only on L-09), the bounded-run part of item 17 (clips 07 and 19), and a local unsupported-claim test for item 16 (clip 20). Items 1–6 and 8, item 17's agent-replay part (after the Phase 17 README) and the final video edit come later; they need no AWS.
11. **Retained after cleanup:** 20 ACTIVE task-definition revisions (cost nothing; Phase 17 deregisters them), the hand-made IAM user and budget (Phase 17), and the local Terraform state (kept for Phase 17's documented final state).

**Minor findings and limitations:**

| Finding | Decision |
|---|---|
| L-09 RetrievalEvalJob missing from `PackageCheck.scala`: `check.sh` gate 8 fails in a fresh clone | Phase 16 (needed for Phase 17's clean-checkout gate) |
| L-10 unmasked identifiers in a live-suite test-report XML inside the archive | Phase 16; the archive stays private |
| L-07 deploy.sh apply-output masking; L-12 no probe for `out()`; L-14 a missing protection field reads as unprotected; L-15 check-leftovers counts removed resources | Phase 16 |
| FECD-2 a timed-out workflow may keep its permit (hypothesis); FECD-4 D-read attempt 2 exit code and read-only bit against the exit table | Phase 16 |
| H-04 poison release waits for the receive batch; H-05 no test for the stop-time bound; H-06 `taskId` logged without redaction | Phase 16 |
| K-01 no automated guard for identical service environments | Phase 16 (test) |
| 3r L1 (memory and query-limit errors classed as store failures), L2 (ClaimVerification) | Phase 16, with FECD-1 and G-28 |
| AB-4 and J-03 derive scripts name never-queried Units series; L-08 small C plot labels | Phase 16 (local; affects the regenerated plots) |
| H-03, M-03, M-06, M-07, K-02, K-03, L-03, L-05, L-06, L-13 wording, README order and stale comments or line numbers | Phase 17 (documentation). The signed manifests are not changed |
| L-04 EMR precondition allows a core count above the minimum | Phase 17 note (the environment is gone) |
| FECD-3, FECD-6, FECD-8, FECD-9, and the A/B limitations | Accepted as stated in results-summary 7; carried into the Phase 16 evaluation |
| H-02, H-07, J-02, J-04, J-05, M-02, M-04, M-05, M-08, AB-1, AB-2, AB-3, AB-5, FECD-5, FECD-7 (details of tools and runs that will not run live again) | Accepted, not fixed: no live experiment will run again. Each is recorded in the session 2 summary or the AB and FECD reports; their effects on the results are in results-summary 7 |
| J-06 the archive flags the Phase 14 smoke records | Closed: occurred as expected at D25 |

## Manual changes

No code changes by hand. All code is agent-written and reviewed. I ran every script, signed every plan and manifest, typed every key hidden, read the bill, recorded the demo, copied the reports, and committed and pushed. I also made read-only copies of the S3-only reports (13b) and of DynamoDB samples during D27. I reworded my own D26 approval line so that D27 proceeds on the Nov 20 approval.

## Costs

| Provider | Amount |
|---|---|
| AWS (November month to date, Nov 21 evening; lagging, may still rise) | $20.47 |
| Anthropic | $0.24 |
| Voyage | $0 (inside the free tokens) |
| **Project total billed** | **$20.71 of $25** |
| Phase 15 (estimate) | about $6.5 running estimate; Billing-derived range about $3.9 to $9.5 (decision 8) |

## Deferred

- **Phase 16:**
  - FECD-1 diagnosis and fix (not validated live), and the G-28 root cause;
  - N-1;
  - the minors routed above;
  - the Cost Explorer figure for Phase 15;
  - items earlier gates sent to Phase 16: storage retries and cleanup; D-02, D-03, G-04, G-18, G-19, G-23; C-05, B-02; custom metrics; route time limits; S-8; Phase 9 n-1, n-2, n-4; the attempt-07 live benchmark (Anthropic API and the local export only).
- **Phase 17:**
  - the documentation minors routed above;
  - deregistering the 20 task-definition revisions; removing the IAM user `scholarchain-readonly` and the budget `scholarchain-25`;
  - the documented final state in place of a live environment;
  - the root README with its agent-replay section, and the remaining demo clips;
  - items earlier gates sent to Phase 17: D-01, E-01 to E-04, G-06, G-08 to G-11, G-15, G-16, G-20, G-22, G-25, G-26; B-01, B-03, F-02; the hook shellcheck false alarm; R-1 API docs; A-04; the final repository review.

## Remaining risks

- About $4.29 of the $25 remains. Billing lags, so Phase 15's final cost is unknown until Nov 22.
- Phase 16 fixes, including FECD-1, cannot be validated live.
- Phase 17's final gate ("the bounded pipeline can be rerun", "query and evidence examples work", "the archived plan matches the deployed environment or documented final state") must rely on the documented final state, recorded runs and local runs.
- `check.sh` fails in a fresh clone until L-09 is fixed.
- The evidence archive holds identifiers and must stay private. Committed records carry the account only as a fingerprint and a masked last-four tail, by design.

## Acceptance

No constitutional change in Phase 15, so there is nothing to ratify.

I accept Phase 15 as of the 27 commits listed in `commit.txt`, from `684636ef997ee041912e2b57d1c597ded8e9778d` to `819f68193ada930712395620ddcba6ce1d07fed7`.
