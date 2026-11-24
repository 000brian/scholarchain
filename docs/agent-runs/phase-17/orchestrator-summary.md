PHASE RESULT: READY_TO_TAG_WITH_OPEN_ITEMS

Rule 3 applies. Rule 1 does not: the run reached E12. Rule 2 does not: every final-gate item and every tier 1 requirement is met or `deviation`; no critical finding is open; every final check passed. **The `deviation` lines below count as accepted only once the human accepts each by name in `human-gate.md`** (the E13 precondition). Open items: D19 (video link, blocked-on-human), accepted deviations (planned and unplanned), known limitations, recorded minor findings, and E13/E14. Each is listed below.

# Phase 17 session 3 summary (orchestrator)

- **Session 3:** sittings from 2026-11-22 evening to 2026-11-24 (local), attended; the human ran every command.
- **Work order sha256:** c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (unchanged). Course specification sha256 6a0eddaddbb91435198ec868699ca99aec22ae8ee5ff515b6f7b34f18e362695.
- **Release candidate:** b4d8f1229fc145e1492e1a8cbb8a44a368d026af (E1 commit, recorded at E2, 2026-11-23T18:50:03Z).
- **HEAD at this summary:** d8b49fa4eede72e7a7742bb49115143e4593719a (E12 replay commit). The gate commit (this summary, `human-gate.md`, `commit.txt`, the log) follows; it is the commit to tag.
- **Detail:** `runbook-log.md` (one row per step, every exit line and count). This summary gives IDs and statuses; prose is in the reports named.

## Steps (decision 18)

| Step | Status | Evidence (runbook-log row; report) |
|---|---|---|
| Resumption check | done | RL "Resumption and session 3 start check" |
| E0 | done (attempt 2) | commit 95a8d9fead71db252af1d7ca833e368a63e14e23 (approvals.md, cost bound) |
| E1 rag repair (P17-A-01) | done | repair-E1-rag.md; review-report-P17-L-01.md (APPROVE, rag slice) |
| E1 documentation pass (K-10..K-12) | done | repair-E1-docs.md; review-report-P17-L-05.md (APPROVE, documentation slice) |
| E1 commit | done | b4d8f1229fc145e1492e1a8cbb8a44a368d026af (= release candidate) |
| E2 | done | CFG count 0; tree clean; RC recorded; push verified (origin/main) |
| E3 `sbt clean check` in a fresh clone | done | exit 0, 1303 s, 0 `[error]`; module 2352, contract 399 (34 suites); marker 2026-11-23T19:15:55Z |
| E4 `check.sh` in the clone | done | exit 0; 9 of 9 gates; 8 release-manifest probes passed |
| E5 parts 1 to 4 (README replays in the clone) | done | every `E5.NN exit=0` (E5.08 re-given); counts in RL rows "E5 part 1" to "E5 part 4"; E5.40 manifest dry run exit 0; Phase 02 exercise ok |
| E5 optional recorded `claude` session | done (human report) | `/agents` not replayable in 2.1.330; `ls .claude/agents \| wc -l` = 18; `/mcp` 3 servers; guard probe blocked |
| E6 | done | account check re-established through the plan record (step 1, see deviations); state list exit 0, count 0; `plan.sh` exit 0, create 175 / update 0 / delete 0 / replace 0, never signed |
| E7 cost reconciliation | done | RL "E7"; costs below |
| E8 leftovers | done | 20 of 20 task-definition revisions INACTIVE; budget `scholarchain-25` removed 01:05Z; IAM user `scholarchain-readonly` removed 01:09Z (2026-11-24); inventory after: ACTIVE 0, INACTIVE 20, no user, no budgets; local profile removed (human report at E11) |
| E9 test-engineer final report | done (MET, both gate items) | agent-report-test-final.md |
| E10 release manifest | done | exit 0; commit c6bbe148cc8aad0f65018c6cada3e6543dcada5b; json sha256 e0698986d6a2567a906131d4064f97d70f729148581e96518ef347a34181556a, md sha256 8a2d634d2f5f8f79991694226aba9d3934948f1b6809274be559c4a207e7d377 |
| E11 pre-step, manifest checksums | done | manifest-checksums.txt: 51 MATCH, 18 MATCH-RECORD, 0 DIFF |
| E11 fill-ins | done | agent-report-docs-E11.md |
| E11 final documentation check | done (REQUEST_CHANGES: 2 major, 11 minor) | review-report-final-docs.md |
| E11 repair pass | done | repair-E11-docs.md |
| E11 focused re-check | done (**APPROVE**) | review-report-final-docs-2.md |
| E11 identifier scan 2 | done (no unexpected match) | identifier-scan-2.md |
| E11 commit | done | ade85aad5bab4535fc8431d82c574b3a9b70aa1a |
| E12 post-RC path check | done (pass) | `git log --name-status RC_SHA..HEAD`: c6bbe14, ade85aa, d8b49fa: only `A`/`M` on allowed paths; no `D`, no `R`, no `M` under releases/manifest/ |
| E12 history scan | done (expected matches only) | RL "E12 history scan" and "human inspection"; see deviations (pattern form) |
| E12 replays E12r.1 to E12r.6 | done | all exit 0 in /tmp/p17-clone-20261123T1850Z at the RC SHA (log /tmp/p17-E12r.log) |
| E12 replay-line fill-in | done | agent-report-docs-E12.md; review-report-E12-docs.md (**APPROVE**) |
| E12 commit | done | d8b49fa4eede72e7a7742bb49115143e4593719a |
| E12 summary | this file | – |
| E12 identifier scan 3, human gate, `commit.txt`, gate commit | pending (the human) | – |
| E13 tag | pending | precondition: this result line, `human-gate.md` accepting the phase and every listed deviation by name |
| E14 publication check | pending | after the tag; not committed |

## Final gate (decision 3 table, decision 23)

| Final-gate item | Status | Evidence |
|---|---|---|
| `sbt clean check` succeeds from a clean checkout | met (deviation planned 6: run in a fresh clone only) | E3 |
| Terraform validates and the archived plan matches the documented final state | met (deviation planned 3) | E4 gate 6; E5.18-E5.20 (fmt, init, validate exit 0); E6 state count 0 and `plan.sh` 175/0/0/0 (equal to Phase 13 and Phase 15 at count level); docs/final-state.md; Terraform frozen |
| README commands have been replayed | met, tag-checkout line deferred to E14; **deviation (unplanned)** for the lines below | E5, E10, E12r.1-E12r.6. Not in the closed replay form: the six P17-M-06 `exit n/a: interactive session` lines (README:127, :133, :134, :145; demo-script:61, :70). Recorded as `not replayed in phase 17`: the Phase 02 agent-session lines README:147, :170-:173 (agent sessions recorded for the video; the Phase 02 exercise itself ran at E5) and the demo-only lines demo-script:71, :72, :175, :188 (not README lines). Planned deviation 1 (the human replays, not agents). |
| The bounded pipeline can be rerun | met (deviation planned 2) | E5.13 local Hadoop pipeline (130 s, local-run-report.json); E5.07 Spark fixture suites 43/43; E12r.3-r.5 Spark suites |
| Query and evidence examples work | met (deviation planned 2) | E12r.2 QueryApiSuite 11/11; E5.12 GoldenExamplesSuite 17/17; E5.10 services 137 passed |
| Final reviewer returns APPROVE | **deviation (accepted at E1), not met** | F-1 (control slice) REQUEST_CHANGES for P17-F-01 stands; P17-F-01 accepted as a known limitation (RL "E1 decisions" item 2). E11's final documentation check: APPROVE after repair (review-report-final-docs-2.md); E12: APPROVE (review-report-E12-docs.md) |
| Cleanup script rehearsed in a safe environment | met (deviation planned 4) | Phase 15 real cleanup records; E4 `check.sh` refusal probes; `cleanup.sh --list` not run (I1b: exits 3 at terraform output) |
| Release manifest is complete | met (deviation planned 7) | E10; manifest-checksums.txt (0 DIFF); review-report-final-docs.md section 2 |

## Spec work order

| Item | Status |
|---|---|
| documentation-engineer: complete documentation (README, architecture, agents referenced, data lineage, threat model, service limits, runbook, evaluation, final report, demo script, API reference, deliverable index) | done (W1-W5, E1, E11, E12; reviewed APPROVE) |
| Rules: replay before documenting; no invented results; result tables linked; optional components stated (OpenSearch, GitHub Actions, GitHub MCP switched off, OpenAPI); no constitutional edits | done (planned deviation 1 for replay) |
| code-reviewer final read-only repository review | done (F-1 REQUEST_CHANGES, control slice; see gate item 6) |
| test-engineer final test run | done as planned deviation 5 (E3, E4 by the human; E9 report MET) |
| Release manifest (commit SHA, image digests, plan checksum, dataset snapshots, run IDs, prompt and model versions, test counts, artifact checksums) | done (E10) |

## Tier 1 requirements

| Item | Status | Evidence |
|---|---|---|
| L2 fail closed (P16-L-10..L-15), decision 6 | done (fixed in code, tested offline, not validated live) | P17-A-01 RESOLVED at E1 against the human's rule; P16-L-10 closed only for claims that give both full names and contain a list word; known limitations below |
| Release-manifest script, decision 7.1 | done | runtime tested in phase 17 (E5.40, E10, exit 0); scripts/README.md:59 still says untested (P17-M-04) |
| Verified limits, decisions 7.6, 8.1 | deviation (unplanned), accepted at E1 (decision 6) | 54 of 60 verified; IL-02, IL-04, IL-06, IL-11, IL-14, SL-29 unverified |
| Test inventory, decision 11 | done | agent-report-test-inventory-2.md |
| Documents, decisions 12-14 | done (3 video marks open) | README.md, docs/**, reports/final-report.md |
| Reviews, decision 15 | done | final verdict: see gate item 6 |
| Identifier scans, decision 16 | done (S-ID, scan 2); scan 3 pending before the gate commit | identifier-scan.md, identifier-scan-2.md |

## Deliverable index (D01 to D20)

| ID | Status | Evidence |
|---|---|---|
| D01 Private Git repository | met for source and history; tag and public access pending | github.com/000brian/scholarchain; tag at E13; made public at the deadline (human), checked at E14 |
| D02 Agentic constitution | met | CLAUDE.md, docs/agents.md, .claude/agents/ (18), settings, hooks, commands, .mcp.json, docs/tool-versions.md |
| D03 Agent run evidence | met | docs/agent-runs/phase-00 to phase-17, amendment-01, amendment-02 |
| D04 Deterministic build | met | build.sbt, project/, .scalafmt.conf; E3 |
| D05 Dataset package | met | datasets/**; E5.17 PASS |
| D06 Hadoop package | met | modules/hadoop; E5.05, E5.13 |
| D07 Entity-resolution package | met | modules/entity-resolution; E5.06; evaluation.py 30 fixtures |
| D08 Spark package | met | modules/spark; experiments/raw/p15-a-spark/; E5.07, E12r.3-r.5 |
| D09 Storage package | met (OpenSearch not implemented, stated) | modules/storage; E5.08 |
| D10 Runtime collaboration-analysis package | met | modules/rag, prompts/; E5.09 |
| D11 Microservices package | met | modules/services, docs/api.md; E5.10, E5.12, E12r.2 |
| D12 Terraform package | met | infra/terraform, config/course.json; E4, E5.18-20, E6 |
| D13 Reproducibility automation package | met (no CI workflow, stated) | scripts/, scripts/README.md, docs/runbook.md; E4, E5.40, E10, E12r.6 |
| D14 Deployment package | met | releases/course/ (7 records), releases/manifest/b4d8f1229fc145e1492e1a8cbb8a44a368d026af/ |
| D15 Experiment package | met | experiments/, load-tests/ |
| D16 Evaluation package | met | docs/evaluation.md, experiments/evaluation/, docs/emulator.md |
| D17 Documentation package | met | README and docs/ |
| D18 Final report | met | reports/final-report.md |
| D19 Demonstration video | **not met yet (blocked-on-human)** | README:398, :454, demo-script:242 `PENDING`; the human adds the link to the README later (then E11's fill-in for that line, and E12 repeats with orchestrator-summary-2.md); if not ready by Wednesday 21:00 CST, tag anyway and accept D19 as a deviation (E13) |
| D20 Cleanup evidence | met | docs/final-state.md; docs/agent-runs/phase-15/cleanup/; E8 |

## Submission logistics

| Item | Status |
|---|---|
| Repository URL | met: github.com/000brian/scholarchain (private until the deadline; then public, E14 records `VISIBILITY`) |
| Tag name | pending: `v1.0-submission` at E13, on the gate commit |
| Video link | open (D19) |
| Report link | met: reports/final-report.md |
| Archived results | met: experiments/**, docs/agent-runs/**; local-only folders ~/scholarchain-evidence-p15, -p16; ~/scholarchain-evidence-p17 filled at E14 |
| Resource inventory and cleanup dates | met: docs/final-state.md (Phase 15 removal of 175 resources, Nov 20-21; E8 removals 2026-11-24 01:05Z and 01:09Z) |
| Instructor-approved deviations | met: docs/agent-runs/instructor-approvals.md; final report section 1.1 (Phase 17 human-accepted deviations in section 1.2) |

## Finding table (session 3; session 2 table in orchestrator-summary-s2.md)

| ID | Severity | Owner | Status |
|---|---|---|---|
| P17-A-01 | major | rag-engineer | RESOLVED at E1 (review-report-P17-L-01.md) |
| P17-F-01 | major | factory-engineer | open; accepted by the human as a known limitation (E1); F-1 stays REQUEST_CHANGES |
| P17-K-02 | minor | rag-engineer | RESOLVED for its two inputs (E1) |
| P17-K-10, K-11, K-12 | minor | documentation-engineer | RESOLVED (E1) |
| P17-L-02, L-03, L-04 | minor | rag-engineer | known limitations (E1) |
| P17-L-06 to L-09 | minor | documentation-engineer | RESOLVED (E11) |
| P17-M-01, M-02 | major | documentation-engineer | RESOLVED (E11 repair) |
| P17-M-03, M-05, M-07 to M-13 | minor | documentation-engineer | RESOLVED (E11 repair) |
| P17-M-04 | minor | infra-engineer | open: scripts/README.md:59 runtime line stale (not changeable after the RC) |
| P17-M-06 | minor | documentation-engineer | open by human decision: `exit n/a` form kept (deviation) |
| E11 re-check minor | minor | documentation-engineer | open: demo-script:9 says "item 6" for probes in item 4 |
| E12 m-1 | minor | documentation-engineer | open: README:325, runbook:189 E-03 clause without the explicit `runtime: untested` tag |
| E12 m-2 | minor | documentation-engineer | resolved in the runbook log (E12r clone path recorded) |
| Session 2 recorded minors (P17-A-06, A-07, F-04; F-02, F-03, F-06 to F-11, F-13 to F-16; K-01; P16-A-08) | minor | various | recorded minors or known limitations (E1 decisions 4, 7, 8); P17-F-05 avoided by the T/Z stamp |

## Known limitations (listed, accepted by the human at E1 unless noted)

- L2: the rule applies only when a claim gives both researchers' full names (P17-L-04, surname-only); absence wording with no list word; hyphenated list words (P17-L-02); single-name false negatives (P17-L-03); pronoun references; the SDK message in one rag ingest path (P17-K-01). All fixed in code, tested offline, not validated live.
- P17-F-01 (constitutional, F-1).
- Verified limits: 6 unverified rows.
- The hook shellcheck findings (decision 7.4); the archive E-03 branch has not run at runtime (needs a known account; S3 mode not replayable after teardown).
- Claude Code updated itself to 2.1.331 after session 2; the recorded Phase 17 version stays 2.1.330.

## Fix status, runtime and replay lines

- Code fixes: `fixed in code, tested offline, not validated live`.
- Runtime: `release-manifest.sh` tested (E5.40, E10); `archive-evidence.sh --local-only` ran once with the current code (E12r.6); the other Phase 17 script changes stay `runtime: untested`.
- Replay lines: every command in README.md, docs/runbook.md and docs/demo-script.md carries one; open forms listed under gate item 3.

## Deviations

**Planned (decision 17), used:** 1, 2, 3, 4, 5, 6, 7 (as in the gate table).

**Unplanned, for the human to accept by name in `human-gate.md`:**
1. Gate item 3 forms: the six `exit n/a: interactive session` lines (P17-M-06); the five Phase 02 agent-session README lines recorded as `not replayed in phase 17`.
2. E6 step 1: the orchestrator's account-check line used `--family-prefix` (matches only full family names) and printed the fingerprint of "None"; reclassified unverified and re-established through the plan record's account fingerprint; comparison source the Phase 15 cleanup record instead of a release record (accepted at E6).
3. E6: the human's hashed `aws sts get-caller-identity` line (shell history 1117), suggested in an outside chat; output only hashed, account never printed (accepted at E8).
4. E8: the first "already absent" reading (from the `--family-prefix` line) withdrawn; the work order text at prompt.md:346 left unchanged by the human's decision.
5. E11: the six `exit n/a` lines (also item 1); M-04 left open.
6. E12 history scan: the guard blocked `pa-[A-Za-z0-9_-]{20,}`; run with `{20}` (same matches; accepted by the human).
7. E11 step 2 scan line given by the orchestrator was narrower than section 20 item 3; later commits used the section 20 line. identifier-scan-2.md counts runbook-log.md "1" for the record prefix; its own row makes it 2 (same expected value).
8. Commands outside the lists (all read-only, nothing written outside the scratchpad): orchestrator `cd` (sitting 1 twice, accepted at E1; once at E11), `sed -n`, `perl -pe`, `sort`/`uniq`, `tr`, `cat`, shell loops; documentation-engineer `cd` prefixes, `tr`, `awk`, `sed -n`, and `bash` on its own scratchpad link-check script.
9. Verified limits (tier 1), accepted at E1.
10. Session 2's unplanned deviations (orchestrator-summary-s2.md) carried for the gate.

**Approval prompts seen:** the MCP server approval at the recorded session's first start (github disabled). Guard blocks: a git word with braces; a non-literal write target; `sleep 300`; the history-scan pattern; each redone in a permitted form or not retried.

## Costs

- AWS usage for November: $21.22 (Nov 18 $3.05, Nov 19 $5.61, Nov 20 $9.83, Nov 21 $2.73), covered by AWS promotional credits (not confirmed on the Bills page); Cost Explorer as of 2026-11-24T00:36Z, subject to billing lag.
- Anthropic $0.24; Voyage $0.00.
- **Project total $21.46 of the $25 bound** (usage before credits).
- Phase 17: no AWS call, no model-provider call from ScholarChain code; E8 removals and E6/E7/E8 reads were the human's own commands. Claude Code usage is outside the project budget.

## Manual actions for the human (in order)

1. Copy this summary and the log: the `cp` lines in the orchestrator's message.
2. Write `docs/agent-runs/phase-17/human-gate.md` (what you reviewed, accepted or rejected, every manual change, every deviation accepted by name, remaining risks, the accepted release candidate SHA) and `commit.txt` (`git log --reverse --format='%H %s' 50ba087..HEAD`, work commits only).
3. The orchestrator runs identifier-scan-3.md over the summary, the gate, `commit.txt` and the log; an unexpected match blocks the commit.
4. The gate commit (section 20 scans, no Co-Authored-By line). Then E13 and E14.
5. When the video link exists: E11's fill-in for that line, and E12 repeats (orchestrator-summary-2.md).
