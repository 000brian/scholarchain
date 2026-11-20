# Phase 14 human gate: apply and deploy through human approval

## Gate

**ACCEPT, with open items.** The course environment exists in AWS, the first bounded pipeline ran on it, the runtime is on with the loaded graph, and the smoke test passed:
- five runtime-off applies (attempts 1 and 2 stopped partway; 3 to 5 finished) and one runtime-on deploy, each from a plan I signed;
- the pipeline ran end to end on AWS: upload, Hadoop on EMR, ER on my laptop, Spark graph and export on EMR;
- all loads finished: DynamoDB, the Neptune CSVs, the Neptune bulk load (nodes and edges, 0 errors) and the vector ingest (all 251,094 documents written);
- the D14 smoke test passed 7 of 7 on the loaded graph;
- deploy-engineer checked the release records, the image digests (three-way match) and the live services.

Two major findings stay open for Phase 15: G-27 (the vector ingest is too slow to finish inside its own limits, so no live vector search has run yet) and G-28 (the path question abstained). The $8 cost bound was passed: Phase 14 cost about $9.92 on AWS.

## Spec checklist

| Item | Status | Source |
|---|---|---|
| Before starting: I verify the plan checksum | Done for every apply: Phase 13 gate (attempt 1), `approvals.md` (attempts 2 to 5, runtime-on); `deploy.sh` checks the committed line | approvals.md, release records |
| Before starting: I confirm account and Region | Done: `deploy.sh` compares the account fingerprint with the plan record; us-east-1 only | release records, agent-report-deploy |
| Before starting: I confirm cost bounds | Late and passed: I wrote the $8 bound at D3 (Nov 18), after the first three runtime-off applies. Phase 14 then cost about $9.92 (decision 3). The project ceiling ($25, budget alerts at $10, $15 and $20) was in place from the start | approvals.md, orchestrator-summary |
| Before starting: no unexplained destroy or replacement | Done: no deletes in any plan; the only replacements were the five task definitions in the runtime-on plan, which hold no data | approvals.md, runbook-log D10 |
| Before starting: I approve the deploy command and apply path | Done: `scripts/deploy.sh --plan-sha256 HEX64`, which applies only the saved plan | approvals.md |
| deploy-engineer deploys the approved commit and plan | Deviation 1: I ran every script; deploy-engineer checked records and the live state read-only and wrote the report | agent-report-deploy |
| Refuse a dirty tree | Done: every script refused or recorded `tree_dirty false` | runbook-log |
| Record commit, plan checksum, account, Region, environment | Done in all four release records (account as a fingerprint only) | agent-report-deploy |
| Only approved, committed scripts | Done: every deployment, upload, pipeline run and load went through `deploy.sh`, `package.sh`, `run-bounded-pipeline.sh`, `smoke-test.sh` or `archive-evidence.sh`. My only other AWS changes were the documented console steps: the four secret values, the alarm e-mail subscription and the cost tags | runbook-log |
| No edits to Terraform, scripts or code by deploy-engineer | Done: repairs went to their owners, with a review and my commit each time | runbook-log |
| Wait for stabilization with a time limit | Done: D11 rollouts COMPLETED, running = desired | agent-report-deploy |
| Verify image digests and task revisions | Done: digest `sha256:79297e42…59eb` equal in the publish record, ECR and all three running tasks; revisions match the D11 record | agent-report-deploy |
| Run `smoke-test.sh` | Done: D14 7/7 (spec gate), D15 7/7 but abstained (G-28) | runbook-log D14, D15 |
| Rollback policy; no destructive data repair | Done: no rollback was needed. The first runtime-on deploy had nothing to roll back to (deviation 4). No data was repaired by hand | orchestrator-summary |
| Return a deployment report | Done: `agent-report-deploy.md` | agent-report-deploy |
| **Gate:** services healthy and ready | **Met**: query, evidence and graph at 1/1, ALB targets healthy, `/readyz` 200 (D12 re-check, D14) | agent-report-deploy, runbook-log |
| **Gate:** image digests match the commit | **Met**: tag = build commit `f159535e`, digest three-way | agent-report-deploy |
| **Gate:** smoke query returns an evidence-backed response | **Met**: D14 on the loaded graph: 2 claims, 3 evidence links, evidence lookup 200 | runbook-log D14 |
| **Gate:** no critical alarm active | **Met**: I read all 8 alarms: 5xx and DLQ OK; the three "AlarmLow" alarms in ALARM are the idle scale-in signal at minimum capacity, not a problem (decision 8) | runbook-log D17 |
| **Gate:** deployment report archived | **Met**: `agent-report-deploy.md` committed here; the release records are committed under `releases/course/` and are also in the D18 archive (decision 9) | runbook-log D18 |

**Items earlier gates sent to Phase 14:**

| From | Item | Status |
|---|---|---|
| Phase 13 gate 6 | terraform MCP tools for infra-engineer | Done (session 1, `c4fbf62`) |
| Phase 13 | C-06, C-07 (Neptune start once, retry after a definite failure) | Done; the start-once guard refused a real duplicate start at D13d |
| Phase 13 | I-25, I-26 | Done; I-25 ran for real at D18 |
| Phase 13 | B-04 | Done (D-01 open, minor) |
| Phase 13 gate 7 | order: graph run ID, runtime-on plan, deploy, load, smoke | Done; the three-way graph run check passed |
| Phase 13 | apply, secrets, alarm subscription, cost tags, `--publish` | Done |
| Phase 11, 13 | first `s3://` runs of the loaders and the export | Done |
| Phase 9 | live Neptune path check | Partly: the path query ran on live Neptune, but the answer abstained (G-28, Phase 15) |
| Phase 7 m3 | one writer | Accepted risk (one `ops` task at a time, checked by the script). Seen working: the script refused D13f attempt 1 while a status task was still running |
| Phase 11 | `RequiresAws` suites | Phase 15 (decision 5) |
| Phase 9 | storage retries and cleanup | Phase 16 |
| Phase 7 | AWS vector load of at least 10,000 vectors, real search, speed and cost | Partly: 251,094 vectors loaded and the real speed measured; no live vector search yet (G-27, Phase 15) |
| Phase 12 RK1 | Neptune loader reaches S3 | Done (D13c to e) |
| Phase 12 R9 | runtime-on desired count | Done: the scaling minimums brought the services to 1 |
| Phase 13 R-3 | AWS CLI options not checked against the docs | Done: cited in session 2 (enum values and the tag-filter shorthand stayed unverified). The calls used by the deploy, pipeline and load stages all ran in session 3; `run-task` needed G-24. The calls only `cleanup.sh` uses haven't run yet |
| Phase 13 | no-Grep-tool fallback | Kept and used |
| Phase 13 risk | first EMR run fails on a permission or step detail | Happened: G-14 (missing EMR tag permission), fixed with plan attempt 4 |
| Phase 13 risk | the state machine's steps are checked only at apply | Accepted at every apply; the Spark steps needed driver settings (G-21) |
| Phase 13 risk | IAM policy sizes | No size error at any apply |
| Phase 13 risk | the idempotency header through API Gateway | Done: D14's replay returned `Idempotent-Replayed: true`, and the conflict returned 409 |
| Phase 13 risk | bucket names in EMR and Spark logs | Not checked. The logs stay in AWS and are never copied into the repository; the cleanup removes them |

## Results

- **Applies:**
  - attempt 1 stopped at Neptune: the AWS managed key `aws/rds` wasn't ready on first use;
  - attempt 2 created Neptune and the services but stopped at the dashboard (I-29);
  - attempt 3 created the dashboard;
  - attempt 4 added a missing EMR tagging permission (G-14);
  - attempt 5 gave the Spark steps a 6 GB driver (G-21);
  - D11 turned the runtime on.
- **Pipeline:**
  - Hadoop on EMR: the DTD was used (no undefined-entity rejects).
  - G-17: the first good Hadoop run used other blocking defaults than Phase 5 and made 2.3 times as many candidates. hadoop-engineer set the defaults to Phase 5's evaluated values, and the re-run matched Phase 5 on every counter.
  - **ER on my laptop matched Phase 5's accepted run exactly:** 16,823,178 pair decisions, 758,328 identity assignments, 200,484 clusters.
  - Spark graph and export on EMR: graph run `run:v1:f2625d504b12749389cf4f5b1a26aed4`.
- **Loads:**
  - DynamoDB: 763,577 items, 0 conflicts.
  - Neptune: 14,290,698 node records and 64,423,404 edge records, 0 errors.
  - Vectors: 251,094 documents in 3.6 hours (G-27).
- **Smoke tests:** D14 (Wanli Ouyang, benchmark q05): 7/7, answered with evidence. D15 (Tommaso Green to Yiannis Demiris, q13): 7/7, but abstained (G-28).
- **Repairs in session 3:** G-01 (smaller ER upload option), G-02 (dashboard key), G-07 (multipart uploads), G-12 (bash 3.2), G-14 (EMR tag permission), G-17 (Hadoop defaults), G-21 (Spark driver memory), G-24 (`run-task` arguments). Each was reviewed by code-reviewer and committed by me.

**Plans applied in this phase:** `aee655c7…` (signed at the Phase 13 gate; attempt 1). **Signed in this phase** (all in `approvals.md`): `6d4a629c…` (attempt 2), `a865a94d…` (attempt 3), `5c77365f…` (attempt 4), `61957631…` (attempt 5), `5656145e…` (runtime-on, live now).

## What I reviewed

Both prompts (`prompt.md` for sessions 1 and 2, sha256 `b1f95168…`, the copy session 1 recorded; `prompt-s3.md` for session 3, sha256 `c0bd687e…`, the copy session 3 recorded); the session 1 report, review and diff; the session 2 agent reports, reviews a and b and both diffs; the session 2 summary and log; every session 3 repair report, review and diff; the G-21 diagnosis; the runbook log; the deployment report; the session 3 summary; `approvals.md`; my own script output for every step; and the spec's Phase 14 section.

## What happened

- **Session 1:** factory-engineer fixed infra-engineer's terraform tools. The reviewer approved. I committed it (`c4fbf62`).
- **Session 2** ran overnight with no stops: B-04, C-06, C-07, I-25, I-26, I-28, I-29 and the DTD option. Both reviews approved, with 7 minor findings.
- **Session 3** took two days (Nov 18 to 20), with 7 repair rounds (8 fixes: G-01 and G-02 shared one round). Most first-time failures were in scripts that had never run against AWS, and they showed up during my runs. Only G-02 (the dashboard key) was flagged in advance, as a risk by a session 2 reviewer.
- **Session 2's interim summary said `GATE_NOT_MET`** only because the prompt's result rules counted spec gate items that belong to session 3. Its own work was `COMPLETE_WITH_OPEN_ITEMS`.
- **Commands not on the list:** `sort`, `uniq`, `tr`, `cd` and `sed -n` again, plus some Bash `grep` over Terraform files. During D-ER, one search matched lines inside `terraform.tfstate`; I checked the three D-ER reports for my account ID (0 matches), and before committing the gate I checked the whole folder (0 matches). All were read-only.
- **Prompt files:** I removed the [decided] and [proposed] marks from `prompt.md` before session 1; `prompt-s3.md` keeps them. Their decisions are the same, except that `prompt-s3.md` adds step D-ER (the ER upload size and the dashboard key check) and a note on session 2's result line.

## Decisions

1. **Planned deviations 1 to 6:** accepted as recorded.
2. **Unplanned deviations:** accepted. All were read-only, and none put an identifier into a committed file.
3. **Cost:** Phase 14 passed its $8 bound. The bound was a declaration, and nothing could measure spend during the run. The drivers were the EMR runs (including the failed Spark run and the G-17 re-run), the NAT gateway and load balancer hours, Fargate (including the 3.7-hour ingest), and Neptune I/O beyond the free 10 million (15.8 million used, about $1.16). **From now on, no paid re-run without a cost estimate first.**
4. **Clean up early:** I clean up the AWS environment right after Phase 15's experiments and the demo recording, as the instructor asked ("stop paid resources when your experiments are complete"). Phases 16 and 17 work from frozen data and records.
5. **D16 live suites:** moved to Phase 15.
6. **G-27 and G-28:** open, major, Phase 15. G-27 also covers the second ingest pass and the retrieval check that never ran.
7. **Unused outputs in S3** (the D6 attempt 3 export with 39.3 million candidates, and the partial D8 attempt 1 output): kept until the cleanup. Storing them costs well under $1 a month.
8. **The "AlarmLow" alarms** stay in ALARM while the services idle at their minimum. That is how ECS target tracking works, not a fault. There is no Neptune CPU alarm; the bulk load ran at 89 to 97% CPU for 3.5 hours. Noted for the final report; no change, since the environment ends after Phase 15.
9. **The D18 archive tar** holds the API host (G-29), so it is never committed. Its S3 copy goes away with the cleanup; I keep the local copy under `evidence/archives/` until grading is over.
10. **The runtime stays on** for Phase 15.

**Minor findings:**

| Finding | Decision |
|---|---|
| SF-01 (`uniq` in session 1), SF-02 (session 1 status contradiction) | Accepted |
| D-01 `.DS_Store` and similar noise under `experiments/` and `releases/` is no longer ignored | Phase 17: ignore OS noise at the end of `.gitignore`. Until then I check `git status` before each commit |
| D-02, D-03 storage edge cases and a missing test | Phase 16 |
| E-01 stale `deploy.sh` usage text; E-02 report line numbers; E-03 `--local-only` wording; E-04 README note on the ECS circuit breaker | Phase 17 |
| G-03 left-out ER files only in `$TMPDIR` | Closed: D7 uploaded the full output (nothing left out) |
| G-04 no test for the ER upload option (`--upload identity-assignments-only`, G-01; D7 used the full upload, so the option has never run) | Phase 16 |
| G-05 D7 download limit | Closed: D7 ran with a limit that fit the 15.2 GiB export |
| G-06, G-08 harmless `ERROR … line=N` lines for expected "not found" results and retried parts | Phase 17 (documented in `scripts/README.md` until then) |
| G-09, G-10, G-11 multipart edge cases | Phase 17 |
| G-13 test retyped the helper | Closed: the orchestrator's test loaded the real function, and D6 ran it |
| G-15 permissions boundary has no tag-key limit | Accepted risk; Phase 17 note |
| G-16 threat-note wording | Phase 17 |
| G-18 the ER policy version doesn't name the blocking caps it was evaluated with; G-19 the local pipeline runner records only `-D` overrides, not the effective settings | Phase 16 (evaluation records) |
| G-20 Hadoop record lists every run under the root | Phase 17 |
| G-22 `api_url` ends in `/` | Phase 17 docs |
| G-23 `/readyz` graph check fails on a cold call and doesn't log why | Phase 16 |
| G-25 long program lines cut at 2,000 characters; G-26 "not finished" recorded as a failure | Phase 17 |
| G-29 smoke test records the API host | Phase 15 (small script fix, since Phase 15 runs the smoke test again) |
| Spark C1, C2 (checkpoint the violation checks, limit BFS partitions) | Proposed later; not scheduled |

## Manual changes

No code changes by hand. All code is agent-written and reviewed. I ran every script, signed every plan, entered the secrets in the console, subscribed to the alarm topic, activated the cost tags, read the bill and the alarms, and copied the reports here.

## Costs

| Provider | Amount |
|---|---|
| AWS (November month to date, lagging; it may rise as the bill catches up, for example the Neptune I/O of about $1.16) | $9.92 |
| Anthropic | about $0.12 |
| Voyage | $0 (inside the free tokens) |
| **Total so far** | **about $10.04 of $25** |

## Deferred

- **Phase 15:**
  - G-27 (batched ingest writes; ingest limits sized for a full run; the second pass and a live vector search);
  - G-28 (why q13 abstains; record the abstention reason);
  - G-29;
  - the `RequiresAws` suites (`AwsAdaptersAwsSuite` writes 2 stub vectors into `researcher-profile-v1` and doesn't clean them up: decide before running it);
  - the embedding-queue setting; the experiment manifests;
  - N-1, U-11;
  - the runtime-off plan or the cleanup right after the experiments and the demo recording.
- **Phase 16:** storage retries and cleanup; D-02, D-03, G-04, G-18, G-19, G-23; C-05, B-02; earlier Phase 16 items.
- **Phase 17:** D-01; E-01 to E-04; G-06, G-08 to G-11, G-15, G-16, G-20, G-22, G-25, G-26; earlier Phase 17 items; removing the hand-made IAM user and budget; the final repository review.

- **Ideas from agents, not scheduled:**
  - ranged or resumable download for the ER stage (one large export object can hit the same connection drops as the uploads did);
  - tighter EMR tag and pass-role conditions in the state machine's policy;
  - an empty-input guard so J07 doesn't fail when J01 accepts nothing;
  - a `/readyz` that says whether data is loaded;
  - a head-object check for `s3://` extra arguments before EMR starts;
  - a `cleanup.sh` rule for live-suite (`itest`) data, and a read-only `neptune-bulk-list` subcommand.

## Remaining risks

- About $15 of the $25 remains, and the environment costs about $3.70 a day while it runs (estimate). Phase 15 must be cheap and short.
- No live vector search has run yet (G-27).
- The path question abstains, and the reason isn't recorded (G-28).
- `run-experiment.sh` has never run.
- My home upload connection drops; large uploads now use multipart, but a slow link can still use up the retries.
- AWS bills lag by hours or a day, so I can't see spend live.

## Acceptance

I ratify the step F change to `.claude/agents/infra-engineer.md` (commit `c4fbf62b8c49aded064b89fc6b26ef7aa4f073d5`).

I accept Phase 14 as of the 20 commits listed in `commit.txt`, from `af140ce0d9545ae1ef6fc4061d2407007cf45a23` to `56801c5fa81bf21a85bf1b5f82e255ef87323265`.
