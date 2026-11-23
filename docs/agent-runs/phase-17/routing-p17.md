# Phase 17 routing table (T0, orchestrator)

HEAD at start: 50ba087ecd33178eaa8ccc69286b9de85e8a8146. Work order sha256: c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24.
Sources are repository paths under docs/agent-runs/ (file:line). "Present" checks were made at T0 by reading the named file.

Classes: T1 = tier 1; T2 = tier 2 (step named); SL = status line only (known limitation in the final report); DOC = documentation-only item written into a named document.

| ID | Source (file:line) | Concerns (file) | Owner (docs/agents.md) | Class / step | T0 status line |
|---|---|---|---|---|---|
| P16-L-10 | phase-16/review-report-P16-L-round2.md:59; phase-16/human-gate.md:103 | modules/rag/.../runtime/ClaimVerification.scala:156-164, :376 | rag-engineer | T1, step 1 (decision 6.1a) | open |
| P16-L-11 | phase-16/review-report-P16-L-round2.md:60; human-gate.md:104 | ClaimVerification.scala:451-466; RulePlanner.scala:207-208 | rag-engineer | T1, step 1 (6.1b) | open |
| P16-L-12 | phase-16/review-report-P16-L-round2.md:61; human-gate.md:105 | ClaimVerification.scala:412-421, :491 | rag-engineer | T1, step 1 (6.1c) | open |
| P16-L-13 | phase-16/review-report-P16-L-round2.md:62; human-gate.md:106 | ClaimVerification.scala:200-205, :468-486 | rag-engineer | T1, step 1 (6.1d) | open |
| P16-L-14 | phase-16/review-report-P16-L-round2.md:63; human-gate.md:138 | ClaimVerification.scala:501 | rag-engineer | T1, step 1 (6.1e) | open |
| P16-L-15 | phase-16/review-report-P16-L-round2.md:64; human-gate.md:138 | ClaimVerification.scala:451-466 | rag-engineer | T1, step 1 (6.1e) | open |
| H-03 | phase-15/review-report-a.md:15; phase-15/human-gate.md:125 | modules/rag/.../RetrievalEvalSet.scala:90, :126-134 (rule text, q25/q26 "top 10" wording) | rag-engineer | T2, step 1 (Phase 15 documentation minor routed to the file's owner) | open |
| P16-B-03 | phase-16/review-report-b.md:15; human-gate.md:140 | scripts/emulator/probes.sh:195 (present at :195 at T0) | infra-engineer | T2, I1b (decision 7.3) | still present (scripts/emulator/probes.sh:195) |
| E-01 | phase-14/review-report-b.md:135; phase-14/human-gate.md:121 | scripts/deploy.sh:101-111 ("Phase 14 order" at :109 at T0) | infra-engineer | T2, I1b (7.3) | still present (scripts/deploy.sh:109) |
| E-03 | phase-14/review-report-b.md:137; human-gate.md:121 | scripts/archive-evidence.sh:551-566 | infra-engineer | T2, I1b (7.3) | open |
| M-03 | phase-15/review-report-c.md:15; phase-15/human-gate.md:125 | scripts/README.md:195-196 (E example `--wait-timeout 1800`; manifest uses 2400) | infra-engineer | T2, I1b (Phase 15 doc minor) | open |
| M-06 | phase-15/review-report-c.md:18; human-gate.md:125 | scripts/README.md:156, :167-173 (runbook table order vs decision 13) | infra-engineer | T2, I1b (Phase 15 doc minor) | open |
| L-06 (scripts part) | phase-15/orchestrator-summary.md:148; review-report-L-01-02.md:118 | scripts/README.md stale "runtime: untested" line | infra-engineer | T2, I1b (Phase 15 doc minor) | open |
| hook shellcheck false alarm | phase-13/human-gate.md:148; phase-16/human-gate.md:170 | .claude/hooks/verify-tests-ran.sh:182; scripts/check.sh gate 4 | factory-engineer (hooks); infra-engineer (check.sh) | T2 evaluation, I1b (decision 7.4); otherwise SL (constitutional) | still present (no constitutional change since Phase 13) |
| L-06 (experiments part) | phase-15/review-report-L-01-02.md:118 | experiments/README.md:136 | experiment-engineer | T2, X1 (9.4) | open |
| K-03 | phase-15/review-report-02.md:70; human-gate.md:125 | experiments/collectors/lib/collector-common.sh:692-693, :699; experiments/README.md:52; signed manifest p15-c-embedding-backlog.json:46 (not changed) | experiment-engineer | T2, X1 for the comment and README; manifest part SL (signed) | open |
| L-03 | phase-15/review-report-L-01-02.md:115; human-gate.md:125 | experiments/results-summary-template.md:85; signed manifests p15-a-hadoop.json:7, :64 and p15-a-spark.json (not changed) | experiment-engineer | T2, X1 for the template; manifest part SL (signed) | open |
| M-07 | phase-15/review-report-c.md:19; human-gate.md:125 | signed manifest experiments/manifests/p15-c-embedding-backlog.json:46 only | experiment-engineer | SL (a signed manifest is never changed) | still present (signed manifest) |
| P16-A-03 | phase-16/review-report-a1.md:19; human-gate.md:141 | modules/storage/.../local/InMemoryGraph.scala:284-292 (test missing) | storage-engineer | T2, step 2 (8.2) | open |
| P16-A-05 | phase-16/review-report-a1.md:21; human-gate.md:141 | modules/storage/src/test/.../EmulatorWiringSuite.scala:266-299 | storage-engineer | T2, step 2 (8.2) | open |
| D-01 | phase-14/review-report-a.md:122; phase-14/human-gate.md:119 | .gitignore:83-84 | build-engineer | T2, step 3 (9.1). Start check 2: 0 tracked .DS_Store | open |
| B-03 | phase-13/review-report-a.md:100; phase-13/human-gate.md:124 | project/BuildIdentity.scala:14-18 (still run-on at T0); project/DockerContext.scala:9-11 | build-engineer | Decision 2 would make it T2 for build-engineer (its own files). **Orchestrator routing: SL, deviation (unplanned) for the gate.** Reason: a change under project/** always needs the full sbt check marker (stop hook; build-engineer's gate), which would add a third full check against decision 4's "exactly two full sbt check runs" rule | still present (project/BuildIdentity.scala:14-18) |
| P16-A-11 | phase-16/review-report-a2.md:13; human-gate.md:142 | modules/hadoop/src/test/.../LocalPipelineRunner.scala:240 | hadoop-engineer | T2, step 3 (9.2) | open |
| P16-A-12 | phase-16/review-report-a2.md:14; human-gate.md:142 | modules/services/.../Main.scala:107-108 (test missing) | service-engineer | T2, step 4 (9.3) | open |
| E-04 | phase-14/review-report-b.md:138; human-gate.md:121 | scripts/README.md:120 vs infra/terraform/modules/runtime/main.tf:281-284 | documentation-engineer (runbook) | DOC, W2 docs/runbook.md | open |
| G-06 | phase-14/runbook-log.md:18; human-gate.md:125 | scripts/package.sh:160-165 harmless `ERROR … line=N` | documentation-engineer (runbook) | DOC, W2 docs/runbook.md | documented in scripts/README.md (Phase 14) |
| G-08 | phase-14/review-report-G-07.md:13; human-gate.md:125 | scripts/run-bounded-pipeline.sh:723-729 and others, same pattern | documentation-engineer (runbook) | DOC, W2 docs/runbook.md | documented in scripts/README.md (Phase 14) |
| G-15 | phase-14/review-report-G-14.md:13; human-gate.md:128 | infra/terraform/modules/identity/boundary.tf:195-205 | documentation-engineer (threat model) | DOC, W2 docs/threat-model.md (accepted risk) | still present (Terraform frozen) |
| G-16 | phase-14/review-report-G-14.md:14; human-gate.md:129 | threat-note wording (repair-D6-infra-iam.md; frozen Terraform comments) | documentation-engineer (threat model) | DOC, W2 docs/threat-model.md | still present |
| G-22 | phase-14/runbook-log.md:59; human-gate.md:132 | `api_url` output ends in `/`; scripts/smoke-test.sh:309 strips it | documentation-engineer (API reference) | DOC, W1 docs/api.md | documented in Phase 17 |
| R-1 | phase-11/human-gate.md:104, :133; phase-11/repair-07-services.md:50 | services idempotency: after a 502 the same key gets 409 | documentation-engineer (API reference) | DOC, W1 docs/api.md | not changed (decision); W1 confirms from code |
| U-12 | phase-10/review-report.md:198; phase-10/human-gate.md:86; phase-16/human-gate.md:57 | ClaimVerificationSuite:113-116 (one name check enough for verified) | documentation-engineer (final report) | DOC, W4 section 23 | still present (design limit) |
| L-04 | phase-15/review-report-L-01-02.md:116; phase-15/human-gate.md:126 | infra/terraform/modules/workflow/sfn.tf:225 with modules/emr/main.tf:33 | documentation-engineer (final report) | DOC, W4 section 23 | still present (Terraform frozen) |
| K-01 | phase-15/review-report-02.md:68; phase-16/human-gate.md:139 | infra/terraform/modules/runtime/main.tf:56-70, :178 | infra-engineer | SL (Terraform frozen, decision 2) | still present |
| K-02 | phase-15/review-report-02.md:69; phase-15/human-gate.md:125 | infra/terraform/modules/runtime/main.tf:183-186 (stale close() comment, present at :183 at T0) | infra-engineer | SL (Terraform frozen; T0 routes it to the file's owner, who may not change it in this phase) | still present (infra/terraform/modules/runtime/main.tf:183) |
| L-05 | phase-15/review-report-L-01-02.md:117; orchestrator-summary.md:147 | committed report repair-D7a-infra-engineer.md:66 (estimate wording) | human-owned record | SL (committed reports under docs/agent-runs/ are never edited) | still present (committed report) |
| L-13 | phase-15/review-report-L-11.md:40; orchestrator-summary.md:155 | committed report repair-D27-infra-engineer.md audit table (line numbers) | human-owned record | SL (committed report) | still present (committed report) |
| E-02 | phase-14/review-report-b.md:136; human-gate.md:121 | committed report phase-14/agent-report-infra.md item 4 (line numbers) | human-owned record | SL (lines sit in a committed report under docs/agent-runs/) | still present (committed report) |
| B-01 | phase-13/review-report-a.md:98; phase-13/human-gate.md:123 | build.sbt:14-19, 42-47 (git inputs list) | build-engineer | SL | still present (no build.sbt change to that list recorded since Phase 13) |
| F-02 | phase-13/review-report-factory.md:30; phase-13/human-gate.md:126 | .claude/hooks/guard-dangerous.sh:1010, 1296 (evidence/ message "no owner") | factory-engineer | SL (constitutional, decision 1) | still present |
| A-04 | phase-11/review-report-a.md:25; phase-11/human-gate.md:180 | datasets/scripts/fetch_enrichment.py:32, :227 (hard-coded key variable name) | dataset-engineer | SL | still present (datasets/scripts/fetch_enrichment.py:227) |
| G-09 | phase-14/review-report-G-07.md:14; human-gate.md:126 | scripts/run-bounded-pipeline.sh mpu_self_test (:751-760 at Phase 14) | infra-engineer | SL | still present (not changed since; T0 did not re-locate lines) |
| G-10 | phase-14/review-report-G-07.md:15; human-gate.md:126 | scripts/run-bounded-pipeline.sh timeout/on_exit | infra-engineer | SL | still present (as G-09) |
| G-11 | phase-14/review-report-G-07.md:16; human-gate.md:126 | scripts/run-bounded-pipeline.sh multipart already-present path | infra-engineer | SL | still present (as G-09) |
| G-20 | phase-14/human-gate.md:131 (no review row found; source of the definition: source not found beyond the gate line) | scripts/run-bounded-pipeline.sh Hadoop record | infra-engineer | SL | not re-checked (definition only in the gate line) |
| G-25 | phase-14/orchestrator-summary.md:37; human-gate.md:134 | scripts/run-bounded-pipeline.sh (`cut -c 1-2000`, now at :1953) | infra-engineer | SL | still present (scripts/run-bounded-pipeline.sh:1953) |
| G-26 | phase-14/orchestrator-summary.md:38; human-gate.md:134 | scripts/run-bounded-pipeline.sh exit 3 mapped to 6 | infra-engineer | SL | still present (not changed since; lines not re-located) |
| P16-A-04 | phase-16/review-report-a1.md:20; human-gate.md:114 | modules/storage/src/test/.../EmulatorClients.scala:61-108 | storage-engineer | SL (accepted unplanned deviation, Phase 16) | still present |
| P16-A-06 | phase-16/review-report-a1.md:22; human-gate.md:141 | committed report phase-16/agent-report-storage-D.md:31 (wording) | human-owned record | SL (committed report) | still present (committed report) |
| P16-A-08 | phase-16/review-report-a1.md:24; human-gate.md:143 | GraphPorts.scala:310-311; Stages.scala:108-114 | rag-engineer | SL (proposed later) | still present (latent) |
| Phase 16 proposed-later list | phase-16/human-gate.md:146 | various | various | SL (each with its Phase 16 status line, reused) | still present unless Phase 17 builds it; `env.sh` clears `SCHOLARCHAIN_CFG_*` is T2 in I1b (decision 7.2) |
| Spark C1, C2 | phase-14/orchestrator-summary.md:41; phase-16/human-gate.md:170 | modules/spark | spark-engineer | SL (proposed later, unscheduled) | still present |
| Phase 16 "not built" list | phase-16/human-gate.md:53 | various | various | SL (Phase 16 status lines reused) | as recorded in Phase 16 |
| `env.sh` clears SCHOLARCHAIN_CFG_* | phase-16/human-gate.md:146 | scripts/emulator/env.sh | infra-engineer | T2, I1b (7.2) | open |

## Summary per step

- Step 1 (rag): T1 P16-L-10..L-15; T2 H-03.
- I1a (infra): T1 release-manifest script, verified limits (infra side).
- I1b (infra): T2 env.sh, P16-B-03, E-01, E-03, M-03, M-06, L-06 (scripts README line), hook shellcheck evaluation, decision 7.5 analysis.
- L (storage, read-only): T1 verified limits (storage side).
- Step 2 (storage): T2 P16-A-03, P16-A-05.
- Step 3: build-engineer T2 D-01; hadoop-engineer T2 P16-A-11. B-03: SL (orchestrator routing, deviation unplanned).
- Step 4 (service, wave closer): T2 P16-A-12.
- X1 (experiment): T2 L-06 (experiments/README.md:136), K-03 (comment and README), L-03 (template only).
- W1: R-1, G-22. W2: E-04, G-06, G-08 (runbook); G-15, G-16 (threat model). W4: U-12, L-04.
- Status lines (final report section 23): K-01, K-02, M-07, L-05, L-13, E-02, B-01, B-03, F-02, hook false alarm (unless I1b resolves it), A-04, G-09, G-10, G-11, G-20, G-25, G-26, P16-A-04, P16-A-06, P16-A-08, Spark C1, C2, Phase 16 proposed-later list and not-built list; manifest parts of K-03, L-03.
