# Phase 13 human gate: build, packaging and scripts

## Gate

**ACCEPT.** The build, the image and all ten scripts are done. Every check I could run passed:
- `check.sh` passed all 9 gates in a fresh clone.
- `package.sh` built the four jars and the ARM64 image.
- `plan.sh` made a plan that only creates things. I sign it below.
- `archive-evidence.sh --local-only` saved the plan checksum.

All repaired findings are fixed. No critical or major finding is open. Nine minor findings are open, each with a decision below. Nothing ran in AWS. Phases 14 and 15 test the scripts for real.

## Spec checklist

| Item | Status | Source |
|---|---|---|
| infra-engineer writes the scripts | Done. The build, the manifest format and the loader code came first, each by its owner | orchestrator-summary |
| GitHub Actions (optional) | Not built (decision 12.4) | prompt |
| 1 `check.sh` | Done. 9 gates: agent files, JSON, hooks, shellcheck, datasets, Terraform, `sbt check` and `packageCheck`, script contracts, offline probes. Fails if any gate fails. **My fresh-clone run: all 9 passed** | my check run |
| 2 `package.sh` | Done. Builds 4 jars and the ARM64 image, records checksums, tags with the build commit. **My run: passed** | my package run |
| 3 `plan.sh` | Done. Records the plan, its checksum, commits, masked account, Region and a summary. Never applies. **My run: 175 create, nothing else** | my plan run |
| 4 `deploy.sh` | Written. Needs a clean tree, the plan checksum and my committed approval line. Runs in Phase 14 | review-report-c, -03 |
| 5 `smoke-test.sh` | Written. Health, one researcher query, evidence for one claim, idempotency, one expected error. The API key never shows up on a command line. Runs in Phase 14 | review-report-c, -02 |
| 6 `run-bounded-pipeline.sh` | Written. Five stages: upload input, Hadoop, ER (on my laptop), Spark with export, load. Each stage writes a run record | agent-report-infra-scripts-b |
| 7 `run-experiment.sh` | Written. Runs only a committed manifest whose checksum I approved. Never runs the example | agent-report-infra-scripts-a |
| 8 `archive-evidence.sh` | Done. Local archive in the layout `cleanup.sh` expects. **My run: passed** | my archive run |
| 9 `cleanup.sh` | Done. Lists everything first, needs my typed confirmation and an archive. I-08 and I-15 fixed | review-report-c |
| Strict mode, no secrets, timeouts, safe retries, step markers, exit codes | Done in all ten scripts | review-report-c |
| Works from a clean checkout | Done: `scripts/README.md` lists what to install; my fresh-clone run proves it | my check run |
| Run every safe script in the environment | Deviation 12.1: no environment exists yet. I ran the four that don't need one | this gate |
| Report commands, environment variables, approval points, evidence, rollback | Done in `scripts/README.md` | agent-report-infra-scripts-b |
| **Gate:** a clean checkout runs all non-cloud checks | Met | my check run |
| **Gate:** planning is repeatable and the plan checksum is archived | Met | my plan and archive runs |
| **Gate:** deploying needs my approval | Met: the checksum plus my committed approval line. A `check.sh` probe tested one refusal | review-report-03, my check run |
| **Gate:** smoke test runs a real query | Phase 14: nothing is deployed yet | – |
| **Gate:** the README uses the same scripts | `scripts/README.md` now; the root README links it in Phase 17 (deviation 12.5) | – |

**Items earlier gates sent to Phase 13:**

| From | Item | Status |
|---|---|---|
| Phase 4, Phase 6 | Code version from the scripts; build stamp in the jars | Done |
| Phase 7 A9 | Services jar with the AWS SDK | Done |
| Phase 9 | Live Neptune check | Phase 14 (no Neptune yet) |
| Phase 11 D-07, R3-06 | Build changes; prompts read from `prompts/` | Done |
| Phase 11 | First `s3://` run | Phase 14; tested locally |
| Phase 12 | Config file in the image | Done |
| Phase 12 | Finish the scripts; full smoke test | Done |
| Phase 12 | Loaders and export that work on AWS | Done (tested locally) |
| Phase 12 | Wider permission limit for batch writes | Not needed |
| Phase 12 | No main class in the Hadoop jar | Done |
| Phase 12 RK-E | Archive layout for cleanup | Done |
| Phase 12 RK1 | Neptune can reach S3 | Done; not tested yet |
| Phase 12 2b R8 | Pipeline gap | Done (5 stages) |
| Phase 12 | Container health check, if quick | Not built (decision 12.6) |
| Phase 12 | GNU coreutils | Installed and documented |
| Phase 12 | aws-api and terraform MCP servers | aws-api: fixed. terraform: cause found, fix in Phase 14 (decision 6) |
| Phase 12 | I-02, I-06, I-08, I-09, I-10, I-12, I-15, I-16, F-01 | Done |
| Phase 12 | Embedding-queue setting | Phase 15 (nothing uses it yet) |

## Results

- **Check** (fresh clone of `98b712a`): all 9 gates passed in about 21 minutes. 2065 module tests and 398 contract tests passed.
- **Package** (`bc179ed`, build commit `98b712a`): passed. The jars and config inside the image match the checked files. The image is `linux/arm64`. One Docker warning about the missing default base image: expected, because the Dockerfile leaves it out on purpose.
- **Plan** (`bc179ed`, clean tree, `default` profile, us-east-1): 175 create, 0 change, 0 delete, 0 replace. **I compared it with the Phase 12 plan: the only new resource is the Neptune egress rule to S3.** The other Phase 13 changes are inside resources the plan already had.
- **Archive** (`--local-only`): passed, 13 files, with the plan checksum.
- **I checked:**
  - the base image includes `linux/arm64/v8`;
  - `/mcp` shows aws-api connected, so the new default values in `.mcp.json` work.

**Plan signature:**
> I approve this plan to be applied in Phase 14 with my own admin profile (`default`).
> Plan file `plans/bc179edb5f726a327478ca4a49e3d180adef2ad1-20261118T013253Z-bc179edb5f72-c9cf.tfplan`, plan commit `bc179ed`: 175 create, 0 change, 0 delete, 0 replace.
> It stays valid while nothing under `infra/terraform/` or the vector index spec changes. Otherwise I sign a new plan.
> Signed: Brian, Nov 17, 2026

APPROVED-PLAN-SHA256: aee655c7c8caa1b0e7c3912a07175f73bd12184cf198dca24983e96b70f9437d

## What I reviewed

The prompt; the session 1 report, review and diff; the nine agent reports; the three reviews (a, b, c) and three re-checks (02, 03, 04); the four repair reports; the orchestrator notes and both summaries; my check, package, plan and archive output and records; and the spec's Phase 13 section. 

## What happened

- **Session 1:** factory-engineer gave the aws-api settings default values, wrote down how to start Claude Code, added the `evidence/**` row and fixed F-01. The reviewer approved. I committed it (`d4b2f98`), restarted, and all three MCP servers connected.
- **Session 2** ran alone from about 1:40 to 5:40 PM, with no stops and no approval prompts.
- **Three major problems were found and fixed:**
  - C-02: the DynamoDB load could write before checking the export.
  - I-18: every smoke test would have failed.
  - I-19: `check.sh` failed on a false alarm in a hook. In round 2, I limited that gate to the scripts. The hooks are still checked by two other gates.
- **Round 2** also fixed I-23: changing the vector index spec now makes a plan invalid, because Terraform reads that file.
- **Round 3** fixed I-27, found by my `package.sh` run: the check wanted `linux/arm64` but Docker says `linux/arm64/v8`. Only `package.sh` changed, so the build commit stayed the same.
- **Commands not on the list:** `sed -n`, `cat`, `uniq`, `tr`, `cd`, Bash `grep` (the orchestrator had no Grep tool) and one `mv`. The `mv` moved the five old prompt copies out of the repository, which was the removal rag-engineer was asked to make. No approval prompts. `cd` breaks a standing rule again.

## Decisions

1. **Neptune before Nov 20:** I create it at the Phase 14 apply. The free trial lasts 30 days from then, past the deadline.
2. **Unplanned deviations:** all accepted. Each one keeps records unchanged or free of my account ID:
   - the publish result goes in its own file;
   - exports go in an attempt folder;
   - release, rollback and smoke-test records are named by attempt;
   - plan records keep only a masked account.
3. **R-3 did not re-read infra files unchanged since Phase 12:** accepted. Phase 12 reviewed them.
4. **`210987654321`** in `BuildIdentitySuite.scala` is a fake test account, not mine.
5. **The old Phase 12 `.plan.txt`** in the ignored `plans/` folder shows my account ID. It stays on my laptop and is never shared or archived.
6. **terraform MCP:** infra-engineer is given a tool the server doesn't have, and lacks the docs search tool the server does have. factory-engineer fixes this in Phase 14 session 1.
7. **Phase 14 order:** the `ops` task can't run until the runtime is on, so loading must come after the runtime-on deploy. Order: graph run ID, runtime-on plan, deploy, load, smoke test.
8. **`check.sh` ran on `98b712a`; package and plan ran on `bc179ed`:** accepted. `bc179ed` only changed `package.sh` and added reports.
9. **The Phase 12 plan is withdrawn.** This plan replaces it. `deploy.sh` would refuse the old one anyway.
10. **Cost:** this phase cost $0 everywhere; my AWS bill shows $0. Total so far: AWS $0, Anthropic about $0.12, Voyage $0; together about $0.12.

**Minor findings:**

| Finding | Decision |
|---|---|
| C-07 a failed first Neptune start locks that graph run, and the error points to a missing runbook | Phase 14, before the first Neptune load |
| C-06 a retry after one kind of failure can start a second edges load | Phase 14, with C-07 |
| I-25 the local archive says it checked for my account ID when it didn't | Phase 14 (script only) |
| I-26 a plan that fails the account-ID check still counts as usable | Phase 14 (script only) |
| B-04 `.gitignore` can still hide some files under `experiments/` and `releases/` | Phase 14, before the first experiment |
| C-05 no test for one masked error message in the Spark export | Phase 16 |
| B-02 no tests that `packageCheck` catches bad jars | Phase 16 |
| B-01 the build's list of inputs misses some git settings | Phase 17 |
| B-03 two code comments lost their line breaks | Phase 17 |
| F-01 (session 1) `.gitignore` lacked `evidence/` | Done |
| F-02 (session 1) the guard's message for `evidence/` says "no owner" | Phase 17 |
| False alarm in hook `verify-tests-ran.sh:182` | Phase 17; then decide whether `check.sh` lints the hooks again |

## Manual changes

No code changes by hand. All code is agent-written and reviewed. I ordered rounds 2 and 3, ran the four scripts, checked the base image, compared the plan with Phase 12, and copied the reports here. 

## Deferred

- **Phase 14:**
  - session 1: fix the terraform MCP tools (decision 6);
  - fix C-06, C-07, I-25, I-26 and B-04;
  - apply this plan;
  - enter the four secret values, subscribe to the alarm topic, turn on cost tags;
  - `package.sh --publish`;
  - runtime-on plan and deploy, in the order of decision 7;
  - first `s3://` runs, the live Neptune check, and the smoke test;
  - one writer at a time (Phase 7 m3), the `RequiresAws` tests, storage retries and cleanup;
  - the AWS CLI options the reviewer couldn't check;
  - keep the no-Grep-tool fallback in the prompt.
- **Phase 15:** the embedding-queue setting (experiment C has nothing reading that queue yet); the real experiment manifests, each approved with an `APPROVED-MANIFEST-SHA256:` line.
- **Phase 16:** C-05, B-02; custom metrics; route time limits; earlier Phase 16 items.
- **Phase 17:** B-01, B-03, F-02; the hook false alarm; the root README; removing the hand-made IAM user and budget; old task versions; EMR's own endpoint and security group; cleanup by tag; the final repository review.
- **Ideas from agents, not scheduled:**
  - a check that the list of files Terraform reads stays complete;
  - option checks for `GraphAnalyticsJob`;
  - an easier way for the export to find its graph run;
  - an attempt ID in the rag report name;
  - storage run reports copied to S3, and Neptune CSVs in the registry;
  - metrics for failed experiment runs;
  - a real lock for one `ops` task at a time.

## Remaining risks

- No script has run against AWS yet.
- The list of files Terraform reads is kept by hand.
- It's not tested that Neptune uses the new egress rule.
- IAM policy sizes are estimates. If one is too big, the apply fails and I make a new plan.
- Prices are estimates. I check real costs after the first apply, turn the runtime off after Phase 15, and clean up soon after Phase 16.
- The first EMR run may fail on a missing permission or a step detail.
- Terraform doesn't check the state machine's steps. AWS checks them at the apply.
- An export needs a second pipeline run, which reuses the first one's result.
- The idempotency check depends on a response header getting through API Gateway (not tested).
- Spark and EMR logs may show the bucket name. I look at the first real output.
- `deploy.sh` waits at most 20 minutes for the services to stop.
- Hooks get syntax checks and self-tests, but no shellcheck, until Phase 17.

## Acceptance

I ratify the step F changes to `.mcp.json`, `docs/agents.md` and `.claude/agents/infra-engineer.md` (commit `d4b2f98b1f362222ce6d7d9b166276b9dfef8cb9`).

I accept Phase 13 as of commits `d4b2f98b1f362222ce6d7d9b166276b9dfef8cb9`, `98b712af1f5934f63c1c81c4c688f28bea212c80` and `bc179edb5f726a327478ca4a49e3d180adef2ad1`, and I sign the plan above.
