SESSION 2 RESULT: READY_WITH_OPEN_ITEMS

Rule 3: every session 2 step is successful, no requirement of decisions 5-12 is `missing`, every final check passes, and no critical finding or major "interface: yes" finding is open. Open: 21 minor findings (H-02 to H-07, J-02 to J-06, M-02 to M-08, K-01 to K-03) and the unplanned deviations listed below. This line does not judge the spec gate; that is session 3 work.

# Phase 15, session 2: orchestrator summary

- Work order: `docs/agent-runs/phase-15/prompt.md`, sha256 `3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0`.
- HEAD at start and at the end: `2cbfbc72c704c40085e2876479d510b90062a12a` (nothing committed; every change is in the working tree).
- Session window: from 11:45 to 14:46 local (Nov 20). No planned stop and no stop for the human.
- Detail: `orchestrator-notes-s2.md` (step log); the agent reports, reviews and diffs are in this folder.

## Start checks (decision 1)

| Check | Result |
|---|---|
| HEAD = Phase 14 gate commit | yes; `git diff --stat 2cbfbc72..HEAD` empty; only `?? docs/agent-runs/phase-15/` |
| MCP servers visible to the orchestrator | aws-api, aws-docs and terraform (deferred tools), Claude Docs and easel. github was not visible (switched off, as expected). The Grep and Glob tools were **not** available in this session, so Bash `grep -rn` and `ls` were used instead (the allowed fallback). |
| `terraform … validate` | Success |
| Phase 14 items mapped | table in `orchestrator-notes-s2.md`, and the "Items from earlier gates" table below |

## Steps (decision 4)

| Step | Owner | Status | Report |
|---|---|---|---|
| 1 | domain-engineer | done | agent-report-domain.md |
| 5a | infra-engineer | done | agent-report-infra-5a.md |
| 6a | experiment-engineer | done | agent-report-experiment-6a.md |
| 3r | rag-engineer (read-only) | done | agent-report-rag-3r.md |
| 2 | storage-engineer | done | agent-report-storage.md |
| repair-00 (interface) | domain-engineer: QueryAudit v3 `abstentionCodes` (from 3r, interface: yes) | done | repair-00-domain.md |
| 6b | experiment-engineer | done | agent-report-experiment-6b.md |
| R-2 | code-reviewer | done (REQUEST_CHANGES) | review-report-b.md |
| 3 | rag-engineer | done | agent-report-rag.md |
| 4, attempt 1 | service-engineer | failed: BLOCKED-ON storage-engineer, a valid interface block (no raw-body embedding queue port); 8.2 was finished | agent-report-service.md |
| repair-00 (interface) | storage-engineer: `ServiceStores.embeddingQueue`, `ReceiveQueue` | done | repair-00-storage.md |
| 4, attempt 2 (wave closer) | service-engineer | done: full `sbt check` 2192/2192 and `packageCheck` ok | agent-report-service-2.md |
| 5b | infra-engineer | done | agent-report-infra-5b.md |
| R-1 | code-reviewer | done (REQUEST_CHANGES) | review-report-a.md |
| 6c | experiment-engineer | done | agent-report-experiment-6c.md |
| R-3 | code-reviewer | done (REQUEST_CHANGES) | review-report-c.md |
| 8 triage | orchestrator | done | this file and the notes |
| 9 repair | infra-engineer (H-01), experiment-engineer (J-01, M-01), service-engineer (H-01 tests; last sbt repairer: full `sbt check` 2195/2195 + 399 and `packageCheck` ok) | done | repair-01-infra-engineer.md, repair-01-experiment-engineer.md, repair-01-service-engineer.md |
| 10 re-check | code-reviewer | done: APPROVE; H-01, J-01 and M-01 RESOLVED | review-report-02.md |
| 11 final checks | orchestrator | done (below) | — |

Scope reduction (decision 4): checked at 11:45 and 12:16. The critical-path estimate ended about 17:00, before 18:30, so nothing was dropped: D-read and E's fused mode are both built.

## Requirements (decisions 5-12)

| Item | Status | Where |
|---|---|---|
| 5: `service.embeddingQueueUrl`, env `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL` | done | ConfigLoader.scala:240, :894; ScholarChainConfig.scala:408 |
| 6.1: G-27 batched and parallel writes, skip check with no per-document call | done; deviation (unplanned): the skip check reads index metadata with batched GetVectors, not BatchGetItem or a registry read (R-1 ruling 1, H-07) | VectorIngestPipeline.scala:336-389 |
| 6.2: first-pass and skip-only estimate | done (estimate) | agent-report-storage.md ESTIMATE |
| 6.3: re-embed write path, completion record, metadata rewrite | done | VectorReembedder.scala; QuerySession `reembed#…` (QS6) |
| 6.4, 7.3: U-11 counts exact under concurrency | done | ReembedCounters (atomic); EmbeddingUsage (AtomicLong); EmbeddingUsageSuite |
| 6.5: storage tests | done | IngestBatchingSuite, ReembedSuite |
| 7.1: G-27 job side (MaxStepMillis 120 min, `--max-documents`, Phase 14 skip test, D7 arguments) | done | RagIngestJob.scala:714-720; RagIngestG27Suite |
| 7.2: re-embed handler, `embedding-task-v1`, poison released at once | done (H-04 minor: the release waits for the rest of the receive batch) | ReembedHandler.scala |
| 7.4: `rag-retrieval-eval` (graph-only, vector-only, fused `fusion-exp-v1`) | done; deviation (unplanned): the benchmark main resource is a derived eval set with a recorded sha256 and an equality test, not a byte copy (R-1 ruling 3) | eval/RetrievalEvalJob.scala, RetrievalEval.scala |
| 7.4: evaluation universe | done: 11 questions in the universe, 100 labels, 19 not comparable, no graded labels (no nDCG), labelled **EXPLORATORY**. The orchestrator chose the answer-target reading (the named-researcher reading, 27 questions, would widen the universe). q25/q26 targets: top 10 of the pool (H-03, wording) | RetrievalEvalSet.scala |
| 7.5: G-28, the reason recorded | done: QueryAudit v3 `abstentionCodes` (26 fixed codes, `unclassified` fallback); cause diagnosed from the code only (most consistent: a Neptune non-timeout error, giving "graph store failure"; or no path within depth 6). No code fix (no proven defect). L1 and L2 reported for later | Records.scala:752; Abstentions.scala; agent-report-rag-3r.md |
| 8.1: worker embedding loop | done (default concurrency 2, range 1..8, a server option) | EmbeddingWorker.scala; Main.scala:88 |
| 8.2: `abstentionReasons` on explain | done | Models.scala:259; api/v1/explain.json |
| 8.3, 8.4: tests; wave closer | done | EmbeddingWorkerSuite, RoleConfigParitySuite; full check 2195/2195 |
| 9.1: worker queue access, env var, permission matrix | done (PutVectors for the worker; ReadDataViaQuery for ops; KMS not needed, SSE-S3) | iam.tf:220-249, :332-341; agent-report-infra-5b.md |
| 9.2: worker scaling on both queues | done | autoscaling.tf:139-205 |
| 9.3: execution-input fields, options, D4a line set | done (risk: JSONata `$error`, which D4a's validate line checks) | sfn.tf:171-200, :250-260; run-bounded-pipeline.sh:431-432 |
| 9.4: `--max-documents`, `--loader rag-retrieval-eval` | done | run-bounded-pipeline.sh:611-648, :1820-1850 |
| 9.5: G-29 | done | identity.sh:252-313; smoke-test.sh:1761-1763 |
| 9.6 (a)-(f): runner placeholders, `--check`, metrics for failed runs, exit 8, `--export-metrics` | done | run-experiment.sh |
| 9.7: README | done (M-06 minor: the table order) | scripts/README.md:104-172 |
| 9.8: COSTS.md | done | infra/terraform/COSTS.md |
| 9.9: validation | done | below |
| 10: experiment design rules (tools, exit table) | done | experiments/README.md:113-141 |
| 11, 6a: load generator, derive scripts, fixtures, template | done; deviation (unplanned): E and D summary plots have K or load level on the x axis, not UTC (J-04) | load-tests/, experiments/derived/ |
| 11, 6b and 6c: collectors, eight manifests, B's scaling check, compatibility matrix | done; deviation (unplanned): C and F select keys from DynamoDB ArtifactRegistry, not with s3vectors `list-vectors`/`get-vectors` (M-01: the vector bucket policy denies the data plane to the admin profile; no security change) | experiments/collectors/, experiments/manifests/ |
| 12: reviews, triage, one repair round, re-check | done | review-report-a/b/c, review-report-02 |

## Final checks (step 11, on the final tree, once)

| Check | Result |
|---|---|
| `terraform fmt -recursive -check infra/terraform` | rc=0 |
| `terraform -chdir=infra/terraform/environments/course validate` | Success, rc=0 |
| `shellcheck` on 11 files under `scripts/**`, `load-tests/query-load.sh`, and the 5 collectors plus `lib/collector-common.sh` | rc=0, rc=0 |
| `jq empty` on the 8 p15 manifests and `benchmark-v1-eval-set.json` (the only changed JSON outside test resources and fixtures) | rc=0 |
| `head -n 5 .claude/.last-test-run` | `timestamp=2026-11-20T20:36:22.215871Z`, `module_tests=2195`, `contract_tests=399` |
| `git status --short --ignored evidence infra/terraform/environments/course` | `!!` evidence/, .terraform/, local.auto.tfvars, plans/, terraform.tfstate(.backup); ` M` main.tf, outputs.tf |
| `git status --short` | 95 entries, all session 2 work plus `?? docs/agent-runs/phase-15/` (list: final-git-status.txt) |

## Findings

| ID | Sev | Interface | Owner | Status |
|---|---|---|---|---|
| H-01 | major | yes | infra-engineer + service-engineer | RESOLVED (repair-01) |
| J-01 | major | no | experiment-engineer | RESOLVED |
| M-01 | major | yes | experiment-engineer (orchestrator chose the option without a security change) | RESOLVED |
| H-02 | minor | no | rag-engineer | open: E can run about 12 min past `--max-seconds`; E's wait (2400 s) covers it |
| H-03 | minor | no | rag-engineer | open: q25/q26 top-10 wording |
| H-04 | minor | no | service-engineer | open: poison release waits for the rest of the receive batch |
| H-05 | minor | no | service-engineer | open: no test guards the stop-time bound |
| H-06 | minor | no | service-engineer | open: `taskId` logged without redaction |
| H-07 | minor | no | storage-engineer | open (deviation note): D7's skip ratio measures index state, not registry state |
| J-02 | minor | no | experiment-engineer | open: shared `aws.err` file between the sampler and the main flow |
| J-03 | minor | no | experiment-engineer | open: `emr_capacity.py` and the template still name TotalUnits*; start-up ramp seen as a scale-out |
| J-04 | minor | no | experiment-engineer | open: x axis not UTC on summary plots (unplanned deviation) |
| J-05 | minor | no | experiment-engineer | open: the preflight temp folder is left behind |
| J-06 | minor | no | infra-engineer | open, expected: at D25 the archive flags the Phase 14 smoke records (the API host) |
| M-02 | minor | no | experiment-engineer | open: E's preflight fails while an ops task runs. **Session 3: run E's `--check` after D7** |
| M-03 | minor | no | infra-engineer | open: README E example wait 1800 is too low (the manifest uses 2400) |
| M-04 | minor | no | infra-engineer | open: a refused pipeline record has no `inputs.loader` |
| M-05 | minor | no | experiment-engineer | open: E's pass-through exit meanings |
| M-06 | minor | no | infra-engineer | open: README runbook table order. **Follow decision 13, not the table** |
| M-07 | minor | no | experiment-engineer | open: C notes rate wording |
| M-08 | minor | no | experiment-engineer | open: E preflight checks argument ranges too loosely |
| K-01 | minor | no | infra-engineer | open: the H-01 invariant has no automated Terraform guard. **D3: check that all four service task definitions carry the same environment** |
| K-02 | minor | no | infra-engineer | open: stale `close()` comment in runtime/main.tf:183-186 |
| K-03 | minor | no | experiment-engineer | open: C notes wording on the 420 s scan window |

Proposed later (no ID, from 3r): L1 (NeptuneAdapters.scala:221-227: memory and query-limit errors are classed as store failures), L2 (ClaimVerification.scala:282-290). Phase 16, with any G-28 fix D6 calls for.

## Interface checks (decision 4)

- After step 1: key and env var = decision 5 = 5a's Terraform (runtime/main.tf). Match.
- After 5a: options and fields = decision 9.3; `{attempt-id}` and exit 8 = 9.6. Match.
- After step 3: `embedding-task-v1` fields (ReembedHandler.scala:18-19) = 7.2 = collectors; log keys `taskId` and `outcome` = lib/collector-common.sh:530-531. Match.
- At step 4's end: explain field `abstentionReasons` (rag) = services Models.scala:259. Match.
- Two interface repairs were raised by consumers: repair-00-domain (from 3r) and repair-00-storage (from step 4 attempt 1).

## Items earlier gates sent to Phase 15

| Item | Session 2 status |
|---|---|
| G-27 | part 1 (writes): done; part 2 (first-pass estimate): done, estimate only (unverified); parts 3 and 4 (live skip pass, live vector search): D7 and E in session 3 |
| G-28 | record the reason: done (code and explain); the live reason is read at D6 |
| G-29 | done (script side); checked live at D5 and D25 |
| RequiresAws suites | D8, D17 (risk: D17's admin-profile vector writes meet the vector bucket Deny, M-01; expect those tests to fail; report them, do not repair) |
| embedding-queue setting and reader | done (5, 8, 9.1-9.2) |
| N-1 | phase-16 (decision 2) |
| U-11 | done (code); observed in C at D12 |
| manifests and approval lines | eight manifests written (D9 signs them) |
| cleanup after the experiments | D19-D28 |
| Phase 7 live vector search | D7, D11 |
| runtime off | D19-D20 |
| first EMR run may fail | D7a, attended |
| run-experiment.sh never ran | D9a `--check`, then D7a/D10 |
| slow uplink | D2 |
| no Neptune CPU alarm | limitation (results summary) |

## Runtime lines

Every new or changed script, collector, load generator, derive script and entry point (`RetrievalEvalJob`, `ReembedHandler`, the worker embedding loop, the `--max-documents` and `rag-retrieval-eval` loaders, the runner changes, `smoke-test.sh` and `archive-evidence.sh` G-29) is `runtime: untested`. The Scala code is covered by unit tests only.

## Planned deviations

1 (experiment-engineer does not execute), 2, 3, 4 (fused mode experiment only, verified by R-1), 5, 7, 8, 9 (N-1), 11. Number 10 was not needed: no stop-hook BLOCKED-ON happened.

## Unplanned deviations (for the gate)

1. The skip check reads S3 Vectors index metadata (GetVectors), not BatchGetItem or a registry read (storage; R-1 accepted; H-07).
2. The benchmark main resource is a derived eval set, not a byte copy (rag; R-1 accepted).
3. C and F take vector keys from DynamoDB ArtifactRegistry, not the S3 Vectors data plane (orchestrator decision on M-01; no security change; R-02 RESOLVED).
4. Summary plots use a K or level x axis (J-04).
5. Worker embedding concurrency is a server option, not a config key (service; R-1 accepted).
6. Registry writes are bounded parallel conditional puts, not BatchWriteItem (the decision allows this; R-1 accepted).
7. 5a went beyond the work order: the runner waits 300 s before the metrics export, and it refuses an untracked or non-executable collector before creating a run folder (R-2 accepted).
8. Commands outside the list (all read-only, nothing written): `sed -n`, `tr`, `uniq -c` (3r); `python3 --version`, `sed -n` (6a); `cd REPO &&` prefixes (rag step 3, service attempts 1-2, infra 5b and repair, experiment repair); one Bash grep that included `environments/course/main.tf`, denied before it ran (5b).
9. Orchestrator: one shell redirection with a `$R` variable path was blocked by the guard and redone with literal paths; the Grep and Glob tools were unavailable, so Bash `grep` and `ls` were used.
10. Approval prompts seen: none.

## Expected D3 runtime-on plan (from the code, not checked against a plan)

- In-place updates:
  - the worker task policy (EmbeddingQueueConsume, VectorReembedWrite);
  - the ops task policy (NeptuneReadForEval);
  - the embedding queue policy (WorkerConsumesEmbeddingTasks);
  - the worker scale-in alarm (a four-metric sum);
  - the state machine (ReadInput resolution).
- One new alarm (`worker_out_embedding`).
- Five task-definition replacements (new image tag; all four service definitions now carry `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL`) and four service updates.
- New outputs: `dlq_urls`, `alb_arn_suffix`, `target_group_arn_suffixes`.
- No create, replace or delete of a stateful resource.
- Check at D3: identical `environment` on all four service task definitions (K-01), and D4a's validate and `test-state` lines (agent-report-infra-5a.md).

## Manifests (unsigned; D9 signs them at these sha256 unless a tool or manifest changes first)

```
1254a11cd8431bfdf41b0549edca9f62adf622a5c13858ee7e469be4fa0488d8  experiments/manifests/p15-a-hadoop.json
1875db33a1f57632e9fcec579dcce3f1c717139ac1cba13c685ed654588f9531  experiments/manifests/p15-a-spark.json
1b869c94c0370810d45810cfb1b2f7e91f272f94415b563d8c990b54d8f17c87  experiments/manifests/p15-b-spark-burst.json
36352e8fcdf6287a41b66e386f045f81d88db864a7f07e0169c7a6efa5101ab8  experiments/manifests/p15-c-embedding-backlog.json
2af43f86a39c31b00cb5c95810265e4d417be5522cef2bbdba0afaff33dc480b  experiments/manifests/p15-d-query-load.json
d851716dfbcd909f96a4b5e5be67d88a85206de67f9e74478b020825c7ce3070  experiments/manifests/p15-d-read-load.json
be3868dff8802e0cf2a2b60c5838444a3ec4bafb9cf7e9f15c2cab30ef1b8019  experiments/manifests/p15-e-retrieval-modes.json
a39c7e453f3c9264092fc22640e2c56465e06ff947bf031291ee9112a5b5fbaf  experiments/manifests/p15-f-poison-replay.json
```

## Session 3 notes (from session 2's results)

- D7: `--max-documents 5000` probe, then the full pass, each with `--wait-timeout 1800`. Skip-only estimate: 5 / 8 / 12 min (low / central / high, estimate). The skip ratio measures index state (H-07).
- B: Phase 14 clusters did scale from 2 to 4 core nodes (6b, item 6). B is expected to scale out, but nothing promises it.
- C: N = 19,200; wait 5,500 s; about 50 min central, up to about 2 h (estimate); "two-task scaling uncertain". Feed this into the runtime-hour checkpoint before D12.
- E: EXPLORATORY (11 questions). Run E's `--check` after D7 (M-02).
- First live use of the ArtifactRegistry selection: the C and F `--check` runs at D9a (the admin profile needs `dynamodb:Scan` and `Query`).
- Cost (estimate, session 2 used no paid service except runtime hours): runtime on since 16:00 UTC, about 4.8 h × $0.15 ≈ $0.70 so far (COSTS.md rate). D0 replaces this with the Billing numbers.

## Manual actions for the human (decision 16)

Copy the reports, then set the executable bits, then commit (all from the repository root):

1. `R=/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2cb924d7-0442-43bb-bde3-b3e771aed839/scratchpad/phase15-reports; rsync -a --ignore-existing --exclude runbook-log.md --exclude clock.txt "$R/" docs/agent-runs/phase-15/`
2. `chmod +x load-tests/query-load.sh experiments/collectors/ab-pipeline.sh experiments/collectors/c-embedding-backlog.sh experiments/collectors/d-load.sh experiments/collectors/e-retrieval-modes.sh experiments/collectors/f-poison-replay.sh`
3. `git add -A`
4. `git diff --cached --stat | tail -n 5`
5. `git diff --cached --name-only | grep -nE '^(evidence/|infra/terraform/environments/course/plans/)|\.tfplan$|\.tfstate|/target/'` (must print nothing)
6. `git diff --cached | grep -nE 'AKIA|ASIA|sk-ant|pa-[A-Za-z0-9_-]{20,}|[0-9]{12}'` (placeholders `123456789012` and `210987654321` and digit runs inside hashes are expected)
7. `git commit -m "phase-15 session 2: G-27 batched ingest and re-embed path, G-28 abstention codes, worker embedding loop, rag-retrieval-eval, Terraform and scripts, experiment tools and manifests; reports (domain, storage, rag, service, infra, experiment, code-reviewer)"`
8. Start session 3 with `SESSION 3 (session 2 committed in commit SHA)`.
