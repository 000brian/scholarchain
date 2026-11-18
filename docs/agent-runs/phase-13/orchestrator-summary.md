PHASE RESULT: COMPLETE_WITH_OPEN_ITEMS

Rule applied: rule 3. Every step is successful and the final checks pass on the final tree. No critical finding is open and no requirement is `missing`. Still open: one major finding, I-19 (it makes check.sh `defect (I-19)`), several minor findings, unplanned deviations and `blocked-on-human` items, all listed below. This result is not approval: your script runs (decision 13) and your gate accept the phase.

# Phase 13 session 2: orchestrator summary

- Work order: docs/agent-runs/phase-13/prompt.md, sha256 `60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7`.
- Start HEAD: `d4b2f98b1f362222ce6d7d9b166276b9dfef8cb9` (the step-F commit is HEAD itself).
- Nothing was committed. No script was run, no AWS call was made, and nothing was applied or planned. aws-api calls by all agents: 0. Cost: $0.
- Working notes with per-step detail: orchestrator-notes.md (same folder).

## Start check (decision 1)
- Step F is present:
  - `.mcp.json:22` has `:-scholarchain-readonly`;
  - `.claude/agents/infra-engineer.md:33` has `-chdir … validate`;
  - `docs/agents.md:48` has the `evidence/**` row.
- The tree was clean except for `docs/agent-runs/phase-13/`.
- MCP tools visible to the orchestrator (deferred): aws-api (2 tools), aws-docs (4), terraform (9).
- `terraform … validate` printed "Success!".
- **The Grep tool was not available in this session.** The orchestrator used the work order's fallback, Bash `grep` with literal words.

## Steps

The porcelain snapshot is `git status --porcelain --untracked-files=all | shasum -a 256`. It cannot see edits to files that were already dirty, so file times were also compared where needed.

| Step | Who | Status | Evidence check by orchestrator | Snapshot after |
|---|---|---|---|---|
| 1 | build-engineer | done | marker 2005/398 = report; packageCheck 1 line (StorageLoadMain), as expected | a3397adb… |
| R-1 | code-reviewer | done (APPROVE, full coverage) | – | – |
| 2 | experiment-engineer | done | `jq empty` exit 0 = report | 5a1b9523… |
| 3 | storage-engineer | done | test reports fresh, no failures (232 passed, 1 ignored; rag 59) | 3440aba0… |
| 4 | rag-engineer | done | test reports fresh, no failures (231 passed, 2 ignored) | ea8915cf… |
| 5 | spark-engineer (wave B closer) | done | marker 2062/398 = report; `packageCheck: ok` | 49bec607… |
| R-2 | code-reviewer | done (REQUEST_CHANGES, full coverage) | – | – |
| 6 | infra-engineer | done | fmt 0, validate Success = report | 65ecfdcf… |
| 7a | infra-engineer | done | shellcheck 0, fmt 0, validate Success = report | 85720b91… |
| 7b | infra-engineer | done | shellcheck 0, fmt 0, validate Success = report | 7b84a32b… |
| R-3 | code-reviewer | done (REQUEST_CHANGES; see coverage note) | – | – |
| 8 | orchestrator triage | done | – | – |
| 9 | repair-01-storage (closer) | done | marker 2065/398 = report, newer than the storage sources; `packageCheck: ok` | 7b84a32b… (unchanged: only already-dirty files changed) |
| 9 | repair-01-infra | done | shellcheck 0, fmt 0, validate Success = report | d1fd5a47… |
| 10 | code-reviewer re-check | done (all 7 repairs RESOLVED; REQUEST_CHANGES only because I-19 is open) | – | – |
| 11 | orchestrator final checks | done | see "Final checks" | d1fd5a47… |

- No `repair-00` step was needed.
  - C-01 (major, interface: yes) was already addressed by step 6, which pins exports under `graph/` (`sfn.tf:39-41`, `:122-133`, `:180-196`). R-3 ruled it RESOLVED.
  - C-03 (minor, interface: yes) went to step 7a as input. R-3 ruled it RESOLVED.
- Runs at once: R-1 with step 2; R-2 with step 6. Never two writers at once.
- **R-3 coverage note:** R-3 did not re-read the infra files that are unchanged since their Phase 12 review; it lists them. Every changed file was read. The orchestrator counted this as covered under decision 11 ("every file's final state is reviewed once": those final states were reviewed in Phase 12), not as `failed`. **You may rule otherwise at the gate.**
- "files changed after these runs: no" agreed with the next snapshot for every writer. Spark's repository-wide `sbt fmt` did not touch other owners' files: storage and rag source times are 14:27-14:44, before step 5.

## Requirements (decisions 5-9)

**Decision 5 (build):**
- 5.1 through 5.4: done.
- 5.5: deviation (planned).
- 5.6 through 5.12: done.
- Details are in review-report-a.md, checklist 1-12. The runtime-digest arm64 index is `unverified` (you check it in package.sh).

**Decision 6 (experiments):**
- README: done.
- Schema v1: done.
- Example manifest: done.

**Decision 7 (code):**
- Common entry-point rules: done.
- 7a storage: done (C-02 resolved; minors C-06 and C-07 open).
- 7b rag: done.
- 7c spark: done (minor C-05 open).
- Embedding-queue key: phase-15.

**Decision 8 (Terraform):**
- 8.1 I-02, I-06, I-10, I-16 and I-12: done.
- 8.2 ops role: done. `s3:PutObject` on `graph/*`; the boundary is not widened.
- 8.3 JsonlExport on EMR: done. It uses the allowed fallback: two fields, `spark.exportSourceRun` and `spark.exportRoot`, both under `graph/`.
- 8.4 RK1 egress: done. That the loader's traffic uses this SG is `unverified`.
- 8.5 and 8.6: done. New output `ops_container`.
- 8.7: done.

**Decision 9 (scripts):**

| Item | Status |
|---|---|
| 9.0 helper | done |
| 9.1 check.sh | **defect (I-19)** |
| 9.2 package.sh | deviation: the `--publish` result goes to a separate `publish/ATTEMPT.json`, not into package-record.json, because records are never overwritten |
| 9.3 plan.sh | done |
| 9.4 deploy.sh | done |
| 9.5 smoke-test.sh | done (I-18 resolved) |
| 9.6 run-bounded-pipeline.sh | done; deviation: the export goes under `--export-root/ATTEMPT_ID` |
| 9.7 run-experiment.sh | done |
| 9.8 archive-evidence.sh | done (minor I-25 open) |
| 9.9 cleanup.sh | done |
| 9.10 rollback.sh | done (changed by the I-22 repair) |
| 9.11 scripts/README.md | done |

Further unplanned deviations from 7b: release records are named by attempt ID; the smoke-test record goes in `evidence/runs/`; the rollback record is named `ATTEMPT_ID-rollback.json`.

Plan and cleanup records are now version 3: `account_sha256` and `account_masked` replace `account_id`. Readers still accept the legacy field.

**Runtime lines:**
- All ten scripts and `scripts/lib/identity.sh`: `runtime: untested`.
- All new entry points (StorageLoadMain, RagIngestJob from S3, JsonlExport to S3): `runtime: untested`. They are tested locally only; the first `s3://` run is phase-14.

## Spec work-order items and gate items

**Spec items:**
- Items 2, 3, 4, 6, 7, 8 and 9: built.
- Item 1: built, `defect (I-19)`.
- Item 5: built, and its I-18 defect is resolved.
- "Run every non-destructive script": deviation (planned) 12.1.
- GitHub Actions: deviation (planned) 12.4.

**Gate items:**
- Clean checkout runs all non-cloud validation: blocked-on-human. You run it; gate 4 will fail until I-19 is decided.
- Plan reproducible and checksum archived: blocked-on-human (your plan.sh and archive runs).
- Deployment needs the documented approval: done statically (`deploy.sh:607-614`, `identity.sh:178-191`).
- Smoke test runs a real query: phase-14.
- Same scripts in the README: done via `scripts/README.md` (deviation 12.5).

## Items earlier gates sent to Phase 13

| Item | Status |
|---|---|
| Code version and build identity (P4 f2, P6 F6) | done |
| A9 services packaging | done |
| Phase 9 live Neptune path check | phase-14 |
| D-07 and R3-06 | done |
| First `s3://` run | phase-14 |
| Config file in the image | done |
| Finish the scripts; smoke-test spec item 5 | done (check.sh defect I-19) |
| Loaders on AWS | done (tested locally) |
| Boundary widening | not needed (done) |
| No Main-Class in the Hadoop jar | done |
| RK-E archive layout | done |
| RK1 egress | done |
| 2b R8 pipeline gap | done (5 stages; ER on laptop, deviation 12.2) |
| Container health check | not built, deviation (planned) |
| GNU coreutils documented | done |
| MCP servers | done in session 1 (all three visible this session) |
| Plan and artifact identity across commits | done |
| I-02, I-06, I-08, I-09, I-10, I-12, I-15, I-16, F-01 | done |
| Embedding-queue key | phase-15 |

## Findings

| ID | Sev | Owner | Status |
|---|---|---|---|
| B-01 | minor | build-engineer | open |
| B-02 | minor | build-engineer | open |
| B-03 | minor | build-engineer | open |
| B-04 | minor | build-engineer | open |
| C-01 | major (interface) | infra-engineer | RESOLVED (step 6; R-3) |
| C-02 | major | storage-engineer | RESOLVED (repair-01; step 10) |
| C-03 | minor (interface) | infra-engineer | RESOLVED (7a; R-3) |
| C-04 | minor | storage-engineer | RESOLVED |
| C-05 | minor | spark-engineer | open |
| C-06 | minor | storage-engineer | open (new in step 10) |
| C-07 | minor | storage-engineer, plus runbook text | open (new in step 10) |
| I-18 | major | infra-engineer | RESOLVED |
| I-19 | major | factory-engineer | **open, blocked-on-human** |
| I-20 | minor | infra-engineer | RESOLVED |
| I-21 | minor | infra-engineer | RESOLVED |
| I-22 | minor | infra-engineer | RESOLVED |
| I-23 | minor | infra-engineer, plus a rule amendment | open, blocked-on-human |
| I-24 | minor | infra-engineer | RESOLVED |
| I-25 | minor | infra-engineer | open (new in step 10) |
| I-26 | minor | infra-engineer | open (new in step 10) |

- **I-19:** `check.sh` gate 4 runs shellcheck on `.claude/hooks/*.sh` and `.claude/hook-tests/*.sh`.
  - Confirmed by the orchestrator: 1 error and 33 output lines in all.
  - The error is `verify-tests-ran.sh:182` SC1087. It is a false positive: the regex `"$t[\"']"` is read as array indexing.
  - So your fresh-clone `check.sh` will exit 6 with `shell: failed`, while the other gates still run.
  - Only factory-engineer can fix it, through a constitutional change and ratification. Alternatively you can rule on the scope of gate 4.
  - It is not critical, so it is not a stop.
- **I-23:** `plan_usable` ignores `modules/storage/src/main/resources/s3vectors/index-spec-v1.json`, which Terraform reads. Fixing it needs your amendment of the plan-validity rule.

## Final checks (step 11, final tree, after the last write)
- `terraform fmt -recursive -check infra/terraform`: exit 0.
- `terraform -chdir=infra/terraform/environments/course validate`: "Success! The configuration is valid."
- `shellcheck scripts/*.sh scripts/lib/*.sh`: exit 0, clean.
- `jq empty` on the changed JSON outside test folders (`experiments/manifest-schema-v1.json`, `experiments/manifests/example-not-approved.json`): exit 0.
- `git status --short --ignored evidence infra/terraform/environments/course`:
  - no `evidence/` entries exist;
  - `.terraform/`, `local.auto.tfvars` and `plans/` are ignored (`!!`).
- `git status --short`: no evidence or plan files are tracked or untracked-but-not-ignored.
- sbt: `.claude/.last-test-run` timestamp is 2026-11-17T23:09:26Z, module_tests=2065, contract_tests=398. It is newer than every sbt-relevant change (the last was the storage repair at 16:52 local).

## Planned deviations (decision 12)
- 12.1: no script run in an environment.
- 12.2: ER on the laptop.
- 12.3: local archive.
- 12.4: no GitHub Actions.
- 12.5: `scripts/README.md` instead of the root README.
- 12.6: no HEALTHCHECK.

## Later-phase items
- **phase-14:**
  - first `s3://` runs of every loader and of JsonlExport;
  - live Neptune path check;
  - smoke test against a real deployment;
  - one-writer guard across concurrent ops tasks (only check-then-act today);
  - Phase 7 m3;
  - `RequiresAws` suites;
  - Step Functions definition first checked at apply;
  - unverified AWS CLI options (list in review-report-c.md).
- **phase-15:**
  - embedding-queue key;
  - experiment C has no consumer of the embedding queue (risk line).

## Gaps routed to other owners or to you
- I-19: factory-engineer, or your ruling on gate scope.
- I-23: your rule amendment.
- C-07: runbook text, from infra-engineer (`scripts/README.md`) or documentation-engineer.
- The Phase 14 open question (where the graph run ID comes from) is constrained: `--stage load` refuses while the runtime is off, because the ops image is a placeholder (R-3 RISKS).

## Approval prompts and commands not on the list
- **Approval prompts seen:** none.
- **Commands not on the list, all read-only, no prompt:**

| Who | Commands |
|---|---|
| experiment-engineer | `sed -n` |
| rag-engineer | `mv` (moved the five prompt copies into the scratchpad) |
| infra 7a | `sed -n`, `cat .gitignore`, `uniq -c`, `tr` |
| infra 7b | `cd REPO &&` prefixes; `tr` and `echo` in read-only checks |
| storage repair | `cat /dev/null` |
| infra repair | `cd … && grep -n` |
| orchestrator | Bash `grep` (no Grep tool); `git diff > SCRATCHPAD/diff-R-n.txt`; `ls -lT`, which with `cut` read file times; `-no-color` on validate |

## Manual actions for you
1. Decision 13 steps 1-7 (copy the reports, scans and commit, fresh-clone `check.sh`, `package.sh`, `plan.sh`, `archive-evidence.sh --local-only`, gate commit).
   - Expect `check.sh` gate 4 to fail (I-19), unless you decide first.
   - Your commit scan `[0-9]{12}` will match the fake account `210987654321` in `modules/storage/src/test/scala/org/scholarchain/storage/load/BuildIdentitySuite.scala:65,70`. Please confirm it is not a real account.
2. Do not share or archive the legacy Phase 12 `.plan.txt` in the gitignored `infra/terraform/environments/course/plans/`. It predates I-21 masking and holds the unmasked account ID.
3. Decide at the gate:
   - I-19 (factory fix or gate-4 scope);
   - I-23 (rule amendment);
   - the unplanned deviations (9.2 publish record, 9.6 export subfolder, release, rollback and smoke-test record names);
   - the R-3 coverage note.
4. Phase 14 order: as in the work order's "Note for Phase 14".
