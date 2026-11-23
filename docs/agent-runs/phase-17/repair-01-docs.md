# repair-01-docs.md (Phase 17 session 2, step 9: documentation repair round)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, recomputed with shasum at the start: match). Decisions 12, 13, 14 and 15 (documentation triage and repair; every documentation finding, minor ones included, in one pass; Markdown files only) and the results section (replay-line forms). Findings: review-report-docs-1.md P17-D-01 to D-10, review-report-docs-2.md P17-D-31 to D-40, review-report-docs-3.md P17-D-61 to D-69. Orchestrator rulings applied: P17-D-07 (reword exactly docs/emulator.md lines 48, 187, 197), P17-D-08 (`not replayable after teardown (archived evidence: PATH)` with a committed record as PATH), P17-D-04 (IL-11 to `unverified`; 60 / 54 / 6 everywhere), P17-D-38 (option 2: correct demo-script.md:9 and mark the three lines `not replayed in phase 17 (REASON)`).
FILES CREATED: none in the repository. This report only.
FILES MODIFIED (all Markdown, all in documentation-engineer territory):
- /Users/brian/Developer/repos/scholarchain/README.md
- /Users/brian/Developer/repos/scholarchain/docs/threat-model.md
- /Users/brian/Developer/repos/scholarchain/docs/data-lineage.md
- /Users/brian/Developer/repos/scholarchain/docs/service-limits.md
- /Users/brian/Developer/repos/scholarchain/docs/api.md
- /Users/brian/Developer/repos/scholarchain/docs/emulator.md (lines 48, 187, 197 only; `git diff --stat`: 3 insertions, 3 deletions against HEAD, the same 3 lines as W5's change)
- /Users/brian/Developer/repos/scholarchain/docs/runbook.md
- /Users/brian/Developer/repos/scholarchain/docs/final-state.md
- /Users/brian/Developer/repos/scholarchain/docs/demo-script.md
- /Users/brian/Developer/repos/scholarchain/reports/final-report.md
COMMANDS RUN: shasum -a 256 (work order); wc; ls; head; cut; grep (Bash, read-only); git diff / git diff --stat / git status --short / git ls-files / git check-ignore (read-only); dirname; read-only shell loops (while read, case, printf, typeset -l, parameter expansion) for the link and anchor check, with stdout redirected to the literal scratchpad path .../scratchpad/linkcheck-repair01.txt; Read, Write and Edit tools. Not on the work order's command list (unplanned deviations, all read-only, see RISKS): `sed -n 'N,Mp'` (reads), `awk` and `diff` (the D01-D20 spec comparison, writing only to literal scratchpad paths), `sort`, `uniq`, `tr`. No sbt, no script, no python, no terraform, no network, no AWS, no MCP call.
TEST RESULTS: not applicable (Markdown only; no sbt run, as instructed). The stop hook did not block (no relevant source path changed: README.md, docs/** and reports/** are not stop-hook source).
VALIDATION RESULTS: 29 of 29 findings RESOLVED, 0 OPEN (table below). Link check over the 10 changed files: 834 link occurrences (equal to the `](` count of every file), 822 repository links: 0 missing, 0 ignored, 348 fragments matched a heading under the GitHub anchor rule and 0 did not; negative control: 2 of 2 fake anchors reported missing; 361 links point to untracked targets, all documents created in Phase 17 (W1 to W5, N) or same-file anchors in them; 12 external links (all in docs/emulator.md, unchanged lines; not fetched). Identifier grep over the 10 files: no ARN, AWS host, execute-api, ELB host, access-key, Anthropic or Voyage key shape, twelve-zero account, non-placeholder s3:// URI or bucket name; the only twelve-digit run is the commit prefix inside the release-record name `20261119T193246Z-167196849087-d539.json` (docs/final-state.md:59, docs/runbook.md:63), an expected match the reviewers already accepted; the only IPv4-shaped matches are version strings (1.4.7.1, 1.3.2.0) and the emulator's 127.0.0.1 and 0.0.0.0 (unchanged lines).
ASSUMPTIONS:
- P17-D-04: infra-engineer's source row (agent-report-infra-I1a.md:70) still says `verified`; per the orchestrator ruling, the documents record `unverified` and say why (service-limits.md:5, :41). The source report is not mine and was not changed.
- P17-D-35: the replay evidence for `archive-evidence.sh --local-only` predates the E-03 change, so I changed its line to `planned for replay (E5)` (the mode is offline). P17-D-34: `claude --version` is now `planned for replay (E5)`. Both add one line each to the human's E5 replay list.
- P17-D-38: I followed option 2 of the ruling (no new README rows). The unsupported-claim suite is not tagged `RequiresAws` (grep of tests/contracts/.../UnsupportedClaimEndToEndSuite.scala), so E3's `sbt clean check` runs it; the reason text says so.
- P17-D-37: Claude Code asks you to approve each project MCP server from `.mcp.json`; I wrote "decline `github`" as the tracked-file-free way to keep it off and did not claim any settings key.
- Same-pattern fixes outside the cited lines (same defect, same file owner, so they belong in this pass): README.md:319 had the same `archived evidence: none` form as P17-D-08 (fixed the same way); docs/runbook.md:88 cited 127 s to the same wrong files as P17-D-36 (fixed the same way).
RISKS:
- Unplanned deviations (commands): `sed -n`, `awk`, `diff`, `sort`, `uniq`, `tr` are not on the work order's list; all read-only, writes only to literal scratchpad paths (one unused scratch file `.../scratchpad/dl.py` was written by a heredoc and never run). Three guard blocks, each on a read-only command, none retried in a variant that bypasses it: a sed script built from a shell variable (redone with a literal script); a grep whose search text contained the dangerous-command probe words (redone without them); a grep naming the env-file probe (redone by line number). One Claude Code safety-check denial on the first form of the link-check loop (zsh-only expansions it could not parse; the command deleted nothing); re-sent in plain bash/zsh syntax, as the denial text allows for a command that deletes nothing.
- The overall final review stays REQUEST_CHANGES: P17-F-01 (major, constitutional) and P17-A-01 (PARTIAL, major) are open and P16-L-10 is not closed (review-report-02.md, review-report-docs-3.md). Nothing here closes L2 or the final review, and no document claims it does.
- The `PENDING` marks left in README.md are only the allowed ones: E8 (IAM user, :214), E10 (release manifest, D14), E11 (video, :396 and D19).
MANUAL ACTION REQUIRED: (1) Orchestrator or human: add `claude --version` (README step 2) and `bash scripts/archive-evidence.sh --local-only` (README scripts table) to the E5 replay list, or tell me to change them to `not replayed in phase 17 (REASON)` at E11. (2) Human: the two new E5 lines and the three demo lines now `not replayed in phase 17` are open items of the gate item "README commands have been replayed" only for the README lines; the three demo-script lines are not README commands.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: documentation re-check `review-report-docs-02.md` (code-reviewer), decision 15; no second round in session 2.

## Findings: status and fix location

| ID | Sev. | Status | Fix (file:line after the repair) |
|---|---|---|---|
| P17-D-01 | major | RESOLVED | docs/threat-model.md:80 (the guard blocks only a command that names a state file, guard-dangerous.sh:179, :1110; does not block a recursive search, P17-F-03); :133 (the Phase 14 D-ER grep recorded, with repair-DER-infra.md RISKS, Phase 14 gate "Commands not on the list", and the work-order rule since Phase 15: phase-15 prompt line 69, phase-16 line 65, phase-17 line 73); :134 (P17-F-03 named as residual risk) |
| P17-D-02 | minor | RESOLVED | docs/threat-model.md:128 (section 2.11 residual risk names P17-F-01, the symlink bypass, guard-dangerous.sh:829-854, :1241-1299) |
| P17-D-03 | minor | RESOLVED | docs/data-lineage.md:142 (EvidenceBundle 64), :143 (EntityLinkCache 255,065), :144 (ArtifactRegistry 0 from D13a; 251,094 labelled as a CloudWatch write-unit sum, not a loader count), :145 (full per-table counts, all inserted, 0 updated/conflicts/rejected), all from phase-14 runbook-log row "D13a/D13b full reports (log streams)" |
| P17-D-04 | minor | RESOLVED | docs/service-limits.md:3 (60 / 54 / 6 / 0), :5 (table 1: 16 verified, 5 unverified), :8 ("6 rows"), :41 (IL-11 `unverified` with reason); README.md:421; reports/final-report.md:603 (IL-11 added to the unverified list). Recount: 54 `verified on` cells, 6 `unverified` cells |
| P17-D-05 | minor | RESOLVED | docs/api.md:24 (Phase 14 statement kept as past; the two current scripts named: collector-common.sh:661-665, query-load.sh:495-504, both strip the slash) |
| P17-D-06 | minor | RESOLVED | docs/api.md:223 (the rare second OVERLOADED path after the job and key record exist: QueryService.scala:344-358, :390-396; same-key retry gets 409 JOB_FAILED, :575, :663-666; use a new key) |
| P17-D-07 | minor | RESOLVED | docs/emulator.md:48, :187, :197 only (Phase 16 behaviour in the past tense; "Since Phase 17 (commit pending) ... `runtime: untested` ... a change made after Phase 16's emulator run (Phase 16 step E14), which was not repeated"); no number changed |
| P17-D-08 | minor | RESOLVED | docs/runbook.md:63 (archived evidence: `releases/course/20261119T193246Z-167196849087-d539.json`); :167 (archived evidence: `releases/course/`); same form at README.md:319 |
| P17-D-09 | minor | RESOLVED | docs/final-state.md:126 (cleanup.sh --list: not planned for E6, I1b's finding, link to runbook #10-cleanup) |
| P17-D-10 | minor | RESOLVED | docs/final-state.md:80, :81, :82 (worded as the Phase 17 plan for E8, outcome PENDING E8 in section 9) |
| P17-D-31 | minor | RESOLVED | README.md:72 (build-engineer + 04 `repair-02.md`), :77 (spark-engineer + 11 `repair-03-spark.md`), :78 (storage-engineer + 11 `repair-05-storage.md`), :81 (test-engineer + 14 `repair-D6-tests.md`, 17 step T1); OWNER lines verified in each record |
| P17-D-32 | minor | RESOLVED | README.md:102 (step 1's cd line now excepts the release-manifest lines); :320, :321 (run from the main checkout's root, never from inside the clone; refusal exit 4, scripts/README.md "Release manifest") |
| P17-D-33 | minor | RESOLVED | README.md:411 and :453 (PENDING E8 replaced by links to docs/final-state.md#9-after-phase-17) |
| P17-D-34 | minor | RESOLVED | README.md:110 (`planned for replay (E5)`), :112 (the start check named as the version's source, not a replay) |
| P17-D-35 | minor | RESOLVED | README.md:317 (`planned for replay (E5)`); :323 (E-03 at archive-evidence.sh:580-586 and P16-B-03 at probes.sh:198-202 added to the script facts, both `runtime: untested`; the Phase 13 run predates E-03) |
| P17-D-36 | minor | RESOLVED | README.md:280 (127 s cited to hadoop-pipeline.log line 476; exit 0 to run.txt; counts to local-run-report.json); same fix at docs/runbook.md:88 |
| P17-D-37 | minor | RESOLVED | README.md:121 (nothing tracked turns github off; the project's switch was the untracked settings.local.json; decline it in a fresh clone); :129 (approve aws-docs, terraform, optional aws-api; decline github); :134 (expected `/mcp`: github not connected) |
| P17-D-38 | minor | RESOLVED | docs/demo-script.md:9 (claim corrected: three demonstration-only lines are not in the README); :71, :72, :175 (`not replayed in phase 17 (REASON)`) |
| P17-D-39 | minor | RESOLVED | README.md:430 (columns stated as copied word for word); :434 to :453 (ID, Deliverable, Minimum contents verbatim; `diff` of the three columns against course-specification.md "Required deliverables": identical, 20 rows) |
| P17-D-40 | minor | RESOLVED | README.md:436 (D03 maps Phase 11's `round2-review-report.md`, `round2-test-report.md`, `round2-repair-{rag,er,hadoop}.md`, `round2-summary.md`, `repair-round-2.md` (the round's work order, read to confirm), `round2-*.log`) |
| P17-D-61 | minor | RESOLVED | reports/final-report.md:457 (two L2 repairs, E00 and E01; E02 was the hermetic configuration, P16-L-16) |
| P17-D-62 | minor | RESOLVED | reports/final-report.md:130 (27 unclassified rows moved into Measured, source evaluation.md table row); :179 (new "Measured: known gaps" paragraph with 97,681 / 43,466 / 88,663, phase-05 human-gate.md lines 62, 63, 98); :181 (Interpretation without numbers) |
| P17-D-63 | minor | RESOLVED | reports/final-report.md:116 (the env-file probe recorded as the orchestrator declining and citing the deny rule; no deterministic denial recorded) |
| P17-D-64 | minor | RESOLVED | reports/final-report.md:150 (category 1 exceptions: the demo answers and the human's evaluation inputs); :152 (each gate's actual wording, per phase; the Phase 15 S3/DynamoDB copies and the Phase 16 demo-answer copy added, with PROVENANCE.txt) |
| P17-D-65 | minor | RESOLVED | reports/final-report.md:152 (3 counted rows = 2 events; D-read counted twice, E00312 and E00316; the extractor paired E00315 with E00312); :140 (pointer "2 distinct events, one counted twice") |
| P17-D-66 | minor | RESOLVED | reports/final-report.md:56 (three deviations are in instructor-approvals.md; the teardown's only record is the Phase 15 gate, decision 6) |
| P17-D-67 | minor | RESOLVED | reports/final-report.md:224 (the full set of 21 filterable keys, identical in all five indexes of index-spec-v1.json) |
| P17-D-68 | minor | RESOLVED | reports/final-report.md:257 and :444 (`claims=59 absence=0`: the corpus never exercises the absence rule) |
| P17-D-69 | minor | RESOLVED | reports/final-report.md:625 (status column `still present`, T0's line, routing-p17.md:61; "proposed later, unscheduled" moved into the description) |

No finding needed a code, test, script, configuration or build change; none is left open for E1.

## Link-check table (changed files)

| File | Link occurrences (= `](` count) | Repository links | Missing | Ignored | Fragments ok / bad | Untracked targets (all Phase 17 documents) | External |
|---|---|---|---|---|---|---|---|
| README.md | 245 | 245 | 0 | 0 | 99 / 0 | 124 | 0 |
| docs/threat-model.md | 70 | 70 | 0 | 0 | 34 / 0 | 39 | 0 |
| docs/data-lineage.md | 25 | 25 | 0 | 0 | 11 / 0 | 11 | 0 |
| docs/service-limits.md | 11 | 11 | 0 | 0 | 6 / 0 | 9 | 0 |
| docs/api.md | 24 | 24 | 0 | 0 | 10 / 0 | 11 | 0 |
| docs/emulator.md | 17 | 5 | 0 | 0 | 0 / 0 | 0 | 12 |
| docs/runbook.md | 79 | 79 | 0 | 0 | 19 / 0 | 21 | 0 |
| docs/final-state.md | 39 | 39 | 0 | 0 | 13 / 0 | 13 | 0 |
| docs/demo-script.md | 47 | 47 | 0 | 0 | 16 / 0 | 15 | 0 |
| reports/final-report.md | 277 | 277 | 0 | 0 | 140 / 0 | 118 | 0 |
| **Total** | **834** | **822** | **0** | **0** | **348 / 0** | **361** | **12** |

Note: the 12 external occurrences are all in docs/emulator.md (`http(s)://` targets inside `](…)`, unchanged lines); not fetched. docs/service-limits.md cites its AWS pages as bare URLs, not `](…)` links. Method (decision 12): `grep -o` of every `](target)`; each path resolved against the file's folder; `ls -d`; `git check-ignore -q` (must not match); `git ls-files` (tracked, or for a folder at least one tracked file); untracked targets compared with the Phase 17 created documents; each `#fragment` compared with the target's headings turned into GitHub anchors (lowercase, characters other than letters, digits, space, `_`, `-` dropped, spaces to hyphens). Negative control: `fake-anchor-xyz` and `9-after-phase-17x` reported missing. Anchors added in this repair, each matched: `#10-cleanup`, `#9-after-phase-17`, `#source-controlled-automation-scripts`, `#1-operator-machine-phase-17`. Links added in this repair, each tracked: phase-14/repair-DER-infra.md, phase-14/human-gate.md, phase-15/prompt.md, phase-05/human-gate.md, phase-13/human-gate.md, phase-15/human-gate.md, phase-16/human-gate.md, releases/course/, e5-pipeline-…/hadoop-pipeline.log, p15-demo/PROVENANCE.txt. Full per-link list: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/55d7dba8-48f0-4b1f-820e-b18b37d7212c/scratchpad/linkcheck-repair01.txt.

## Replayed commands and outcomes

None. documentation-engineer does not replay commands in Phase 17 (planned deviation 1). Replay lines changed in this repair: README.md:110 and :317 to `planned for replay (E5)`; README.md:319, docs/runbook.md:63 and :167 to `not replayable after teardown (archived evidence: PATH)`; docs/demo-script.md:71, :72, :175 to `not replayed in phase 17 (REASON)`. Each command keeps exactly one replay line.

## Unverified claims

- P17-D-37: what `/mcp` prints for a declined project server, and the exact approval dialog, were not observed (no Claude Code session run); the text says only "not connected" and "decline".
- P17-D-06: the rare OVERLOADED path is read from code only (QueryService.scala:390-396); no test was run or read that exercises it.

## INTERFACE HANDOFF

- Files changed: the 10 Markdown files listed under FILES MODIFIED. No code, test, script, configuration, build, prompt or constitutional file changed.
- Public signatures: none (documentation only).
- Fixtures: none.
- Thread-safety: not applicable.
- Limits: docs/emulator.md changed only at lines 48, 187, 197 (ruling P17-D-07), no number changed; docs/evaluation.md not touched; the IL-11 status in the documents now differs from infra-engineer's step I1a report by the orchestrator's ruling P17-D-04.

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-17/prompt.md` -> c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (match).
- Link check loop over the 10 changed files -> 834 occurrences; MISSING 0; IGNORED 0; frag ok 348, NOANCHOR 0; untracked 361 (all Phase 17 documents); external 12; per-file occurrence counts equal the `](` counts; negative control 2 of 2 fake anchors missing.
- Identifier grep (12-digit runs; ARN with account; amazonaws.com; execute-api; .elb.; AKIA/ASIA keys; sk-ant-; pa- keys; twelve zeros; s3:// non-placeholder; bucket pattern; IPv4) over the 10 files -> only the expected release-record commit prefix (final-state.md:59, runbook.md:63), version strings and 127.0.0.1 / 0.0.0.0.
- `diff` of README D01-D20 ID/Deliverable/Minimum-contents columns against course-specification.md -> IDENTICAL, 20 rows.
- `grep -c "| verified on" docs/service-limits.md` -> 54; `grep -c "| unverified" docs/service-limits.md` -> 6.
- `git diff --stat docs/emulator.md` -> 1 file changed, 3 insertions(+), 3 deletions(-).
- Replay-form grep: no `replayed earlier (phase 17`, no `archived evidence: none` left in README.md, docs/*.md, reports/final-report.md.
- files changed after these runs: none
