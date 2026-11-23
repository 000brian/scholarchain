# agent-report-docs-W5 (documentation-engineer, Phase 17 session 2, step W5)

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, checked with shasum at the start and the end), step W5; decisions 2 ("Dropped producers"), 4 (interface checks), 12 (rules for every W step, and W5), 13, 14; course specification "Demonstration requirements" (course-specification.md:2892-2919) and "Required deliverables" (:2759-2784) read verbatim
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/docs/demo-script.md (243 lines, sha256 2d9c503ce4f29d87af516a6fd7defebf0286db7d799c121db68fa379529f2041)
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/README.md (451 lines, sha256 8ac6ceb883dcbd2547e0dda7ffd4e18bc875891dba8f1daad9d7f9f8f115f953)
  /Users/brian/Developer/repos/scholarchain/docs/api.md (346 lines, sha256 be7e2021b30b21cc2d9063521b3844e0ea6d8bd97346df27e302525130b9998b)
  /Users/brian/Developer/repos/scholarchain/docs/threat-model.md (189 lines, sha256 344d9c6c98b04435d07c53c324cf7ba79abd15d3b04f9a7f2c86ee2105003ec7)
  /Users/brian/Developer/repos/scholarchain/docs/runbook.md (232 lines, sha256 11b287791e7c864d6abc50e76a7e64f35f196604bdb026af376ff5e954929f16)
  /Users/brian/Developer/repos/scholarchain/docs/architecture.md (one plain-text path turned into a link; sha256 ac145d9796ed2b291487f83dea575848c6bf7b7103a51713e102ad0990c429ed)
  /Users/brian/Developer/repos/scholarchain/docs/emulator.md (3 lines, touch-up only; sha256 0d9af9ba348cee06aa051a41e1a913131bc1ed84bb704c193b964dcf82890bd5)
  /Users/brian/Developer/repos/scholarchain/reports/final-report.md (729 lines, sha256 851a635265917505483690b0aab6ec0a2e2047b47b01669360eddb1dff7f55ec)
  docs/evaluation.md: not changed (no path it cites changed)
COMMANDS RUN: read-only only: shasum, wc, ls, head, cut, grep, git status --short, git diff (read-only), git ls-files, git check-ignore, git log -S (one, to date a reason code), dirname; zsh loops (while read, case, printf, typeset -l, parameter expansion) for the link and anchor checks, writing only into literal scratchpad paths. Read, Write, Edit tools. No sbt, no script, no python, no terraform, no network, no AWS, no MCP call. Unplanned deviations: see RISKS.
TEST RESULTS: none run (W steps never run sbt). Test sections reconciled from T1 (agent-report-test-inventory-2.md) and the latest full check (repair-01-rag.md: marker 2026-11-23T01:13:43Z, module 2343, contract 399).
VALIDATION RESULTS: interface check passed (no mismatch, below). Link check: 14 files, 982 link occurrences: 834 repository path links (0 missing, 0 ignored; every untracked target is a document created in Phase 17), 364 fragment links (364 match a heading under the GitHub rule, 0 missing; negative control detected 2 of 2 fake anchors), 12 external links (docs/emulator.md only, AWS documentation, not fetched). Identifier scan of the 8 changed or created files: 0 key, ARN, AWS host, execute-api or twelve-zero matches; one twelve-digit run (docs/runbook.md:63, the release-record file name 20261119T193246Z-167196849087-d539.json, an expected match, W2's text). `PENDING W5` marks left: 0.
ASSUMPTIONS:
  - "docs/** (except docs/agents.md)" for the link check is read as the documentation deliverables: docs/*.md and docs/incidents/*.md. docs/agent-runs/** (human-owned phase records, never edited by agents) was not link-checked.
  - The 22 demo items: a recorded live clip has no command of its own unless the committed record gives one; where the record gives only a description (clips 05, 07), the demo script says so and gives no invented command line.
  - Phase 17 session 2 reports (agent-report-rag.md, repair-01-rag.md, review-report-02.md, agent-report-test-inventory-2.md and the review reports) are cited as plain-text names "to be copied into docs/agent-runs/phase-17/", never as links (they are not in Git yet).
  - The README already linked the incident report in D15 (W3); W5 added it to D14 (rollback evidence) and D17 as well.
RISKS:
  - Unplanned deviations (read-only, for the gate): one Bash `sed -n` (to read ClaimVerification.scala lines; sed is on the never-run list); `cd REPO;` prefixes; `dirname`, `typeset -l` and zsh parameter expansion in the link-check loops; one `git log -S` (read-only git). One Bash call was blocked by the guard because its grep pattern held a guard-text word; it was not retried in that form (the probe evidence was read from docs/agent-runs/phase-01/human-gate.md and guard-selftest.sh lines instead). One Bash call was refused by Claude Code's built-in removal check (a zsh parameter-expansion test that deletes nothing); it was not retried in that form, and the anchor transform was rewritten with plain expansions.
  - Demo-only commands not in E5's README list (the orchestrator should add them to E5 if they are to be replayed): `sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite"` (item 16), and the two extra guard probes `sed -i.bak s/a/a/ README.md` and `tail -f README.md` asked in the optional claude session (item 4; the spec asks for at least three safe probes, course-specification.md:392). New README rows (also E5): `QueryApiSuite`, `AlgorithmsSuite`, `JsonlExportSuite`, `JobConfigSuite` (T1's exact lines).
  - The env.sh and probes.sh replay lines in docs/runbook.md section 11 stay `replayed earlier (phase 16, ...)`: they refer to the Phase 16 versions; the bullets under the table say so. The Phase 17 versions never ran (`runtime: untested`).
  - docs/api.md now mixes code states: sections 1 to 8 describe 50ba087, section 9 the Phase 17 working tree (stated at the top and in section 9).
MANUAL ACTION REQUIRED: none for W5. For E5: the demo-only lines above. For E11: the PENDING marks (README 6 lines, final report 9, final-state 11, demo script 1) and every `planned for replay` line (README 71, runbook 11, demo script 26).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none. The stop hook did not block (no sbt run; the modules/** changes belong to rag, storage, services and hadoop, covered by the 01:13:43Z marker).
NEXT GATE: R-D1 (docs/architecture.md, data-lineage.md, api.md, threat-model.md, service-limits.md, runbook.md, final-state.md, docs/incidents/**, and the changed lines of docs/emulator.md: 48, 187, 197), R-D2 (README.md, docs/demo-script.md, docs/tool-versions.md), R-D3 (reports/final-report.md)
```

## INTERFACE CHECK

Producers: step 1 (agent-report-rag.md) as changed by repair-01-rag.md and judged by review-report-02.md; I1b (agent-report-infra-I1b.md); T1 (agent-report-test-inventory-2.md); N (agent-report-incident.md); review reports for item 3. No producer was dropped.

| Name, path or field used | Exists as handed over? | Where checked (file:line) |
|---|---|---|
| `Reasons.AbsenceAmbiguous` text | yes | ClaimVerification.scala:117-118 |
| `Reasons.AbsenceThirdName` text | yes | :121-122 |
| `Reasons.AbsenceUnnamed` text | yes | :123 |
| `Reasons.AbsenceTimeQualifier` text | yes | :124-125 |
| `Reasons.AbsenceFoundPath` text | yes | :126-127 |
| `Reasons.ConnectionUnconfirmed` text | yes | :131-132 |
| `Reasons.all` holds all six new codes (31 entries) | yes | :133-165 (new at :158-163) |
| `AbsenceNoDepth` unsupported in the Phase 17 block (unbacked) | yes | :697 with :723-728; Phase 16 block still uncovered at :578 |
| `AbsenceNotSearched` still given by the Phase 16 checks | yes | :565, :569, :572, :597 |
| Phase 17 rule block; rule (h) `connectedPairs`, ConnectionUnconfirmed; (c on possible absence) | yes | :601-730; :744, :749, :762; :763-790 |
| `object AbsenceRule`; Negations (lack...), ConnectionWords, ConnectionPhrases, AbsenceWords (absent, unrelated), AbsencePhrases, ScopeWords, FunctionWords, Allowlist, UnnamedWords, TimeQualifierRe, Reading, classify, affirms, read, AffirmWords, inAffirmVocabulary, isThirdName, inVocabulary | yes | :978; :991 (:1004-1005); :1014; :1043; :1047 (:1057-1058); :1062; :1077; :1121; :1163; :1232; :1263; :1314; :1317; :1323; :1329; :1393; :1436; :1440; :1446 |
| status function | yes | :930-938 |
| `RagIngestJob.failureText`, "failed: CLASS" status, copy outcome class only | yes | RagIngestJob.scala:316, :657, :722 |
| AbsenceRuleSuite tests cited (P16-L-10/11/12, P17-A-01..A-05) | yes | AbsenceRuleSuite.scala:131, :146, :158, :360, :374, :402, :411, :440, :458, :476 |
| env.sh sweep, INFO line text, runtime line | yes | scripts/emulator/env.sh:80-106 (INFO at :102), :55-58; sha256 36543675… equals I1b's |
| probes.sh P16-B-03 guard | yes | scripts/emulator/probes.sh:198-202; sha256 fa0ad60e… equals I1b's |
| scripts/README.md step 8 text (hand unset no longer needed) | yes | scripts/README.md:256 (section "AWS Emulator (Phase 16, decision 12; run book step E14)", :239) |
| check.sh unchanged by I1b (hook shellcheck stays out of gate 4) | yes | sha256 a892d065… equals I1a's and I1b's |
| plan.sh "safe and useful for E6: yes"; cleanup.sh --list exit 3 at terraform-outputs, "no" | yes (analysis, report only) | agent-report-infra-I1b.md item 5 |
| Phase 15 runbook-log row "D25 step 5 cleanup.sh --list" | yes | docs/agent-runs/phase-15/runbook-log.md:122 |
| QueryApiSuite "full query-to-evidence lifecycle with fakes" | yes | QueryApiSuite.scala:87 |
| Spark suites AlgorithmsSuite, JsonlExportSuite, JobConfigSuite, KnownGraphSuite, PipelineFixtureSuite | yes | modules/spark/src/test/scala/org/scholarchain/spark/ (ls) |
| marker 2026-11-23T01:13:43Z, 2343, 399 | yes (as reported by T1 and repair-01-rag; W5 did not read the marker) | agent-report-test-inventory-2.md:8-10; repair-01-rag.md:127 |
| docs/incidents/INC-P15-F-poison-message.md | yes | ls; heading "# INC-P15-F: ..." |

No mismatch.

## Items

| # | Item | Status | Where |
|---|---|---|---|
| 1 | docs/demo-script.md, 22 items (clip recorded or to record, show, say, command or screen, blur, replay line) | done | new file; overview table plus one section per item; sources: Phase 15 gate decision 10, phase-15/runbook-log.md rows "D16 clip NN", "D25"/"D27"/"D28"; probes from phase-01/human-gate.md and guard-selftest.sh:129, :219 |
| 2 | Reconcile L2 statements and reason codes | done | docs/api.md section 9 (rewritten: 31 reasons, table of the 11 absence-related codes with status and origin, the rule as built in 5 steps, disclosed behaviour, "L2 is not closed" paragraph with P17-A-01 PARTIAL, P16-L-10 not closed, P16-L-11 closed for recognised claims, P17-K-02; RagIngestJob "failed: CLASS" note with P17-K-01); header line 3. docs/threat-model.md intro note, 2.1 residual risk, 2.3 new "Phase 17 change" bullet and residual risk. README "Known limitations": L2 bullet split into a fix-status bullet, an "L2 is not closed" bullet and a "final review is REQUEST_CHANGES" bullet. Final report: 12.2 rewritten (7 points plus "L2 is not closed"), 13 (golden examples after the repair), 15, 16, 18, 19, 23.1, 23.2 (proposed-later row), 23.4, 25, status paragraph; 0 `PENDING W5` left |
| 3 | Final report 23.4 open findings after the re-check | done | heading renamed "23.4 Phase 17 review findings (status after the code re-check)"; P17-A-01 PARTIAL (major remains); A-02 to A-05 and F-12 RESOLVED; new P17-K-01, K-02; P16-A-08 line; verdict paragraph: overall REQUEST_CHANGES, P17-F-01 and P17-A-01 open, F-2's conditional APPROVE withdrawn |
| 4 | Test sections vs T1 and the latest full check | done | README "Formatting, compiling, testing and packaging" intro (2343/399, marker, link to final report 15); "Bounded local sample" table: QueryApiSuite row (T1's E5 suite), GoldenExamplesSuite kept as the frozen-examples row, the three further Spark suites as T1's exact lines; final report 15 (new table row for step 6, T1 inventory paragraph), 25 (QueryApiSuite) |
| 5 | Scripts vs I1b | done | docs/runbook.md: plan.sh row (optional E6, I1b "yes", never signed); D25 cleanup --list row now `not replayable after teardown` with "not planned for Phase 17 step E6" and I1b's reason (exit 3 at terraform-outputs before any listing); section 11 env.sh note rewritten (clears exported SCHOLARCHAIN_CFG_*, prints a count, runtime: untested, the replay line is the Phase 16 version) and a probes.sh note. docs/threat-model.md section 5: hook shellcheck outcome (33 findings, 3 at warning or error, gate 4 unchanged, known limitation). README script facts already matched I1b (no change needed) |
| 6 | Incident report in the deliverable index | done | README D15 (already, W3), plus D14 (rollback evidence) and D17 |
| 7 | Deliverable index D01-D20 | done | D14 release manifest `PENDING E10` kept; D17 links demo-script, incident, tool versions, final state, emulator; D18 links the final report and section 23; D19 `PENDING E11` plus the demo script link; "Final report and video" section links the report and the demo script |
| 8 | Touch-ups | done | docs/emulator.md sections 2.3, 8, 9: one sentence each, appended (lines 48, 187, 197), no number; docs/evaluation.md: no change (no cited path changed; its 99 path links all resolve and are tracked). Also turned W1/W2's plain-text forward paths into links (docs/api.md section 8 to service-limits.md; docs/architecture.md intro to final-state.md) |
| 9 | Full link check | done | table below |

Extra, inside my territory and the W reports' "for W5" lists: docs/threat-model.md section 5 now records P17-F-01 (major, open, constitutional) and P17-F-03, and names P17-F-02 on the sts bullet, so the security document matches the final review.

## Changed lines in docs/emulator.md and docs/evaluation.md

- docs/emulator.md:48 (section 2.3): appended "Since Phase 17 (commit pending), `env.sh` clears every exported `SCHOLARCHAIN_CFG_*` variable itself, a change made after Phase 16's emulator run (Phase 16 step E14), which was not repeated."
- docs/emulator.md:187 (section 8, "Limitations of this work"): the same sentence appended.
- docs/emulator.md:197 (section 9, status line of `scripts/emulator/env.sh`): the same sentence appended.
- No other line changed (`git diff --stat docs/emulator.md`: 3 insertions, 3 deletions).
- docs/evaluation.md: no change.

## Link-check table (decision 12 method)

Method: `grep -o` of every `](target)` in each file (counts equal to the `](` count in every file, so no link was missed). For each path target, resolved against the file's folder: `ls -d`; `git check-ignore -q` (must not match); `git ls-files` (tracked, or for a folder at least one tracked file). Untracked targets were checked against the documents created in Phase 17 (W1, W2, W3, W5, N). Fragments: each `#anchor` compared with the target file's `#` headings turned into GitHub anchors (lowercase, characters other than letters, digits, space, `_` and `-` dropped, spaces to hyphens); negative control: two fake anchors were reported missing.

| File | Path links | Missing | Ignored | Untracked (all Phase 17 documents) | Fragment links | Fragment missing | External |
|---|---|---|---|---|---|---|---|
| README.md | 191 | 0 | 0 | 75 | 96 | 0 | 0 |
| docs/api.md | 14 | 0 | 0 | 1 | 10 | 0 | 0 |
| docs/architecture.md | 35 | 0 | 0 | 8 | 10 | 0 | 0 |
| docs/data-lineage.md | 16 | 0 | 0 | 2 | 11 | 0 | 0 |
| docs/demo-script.md | 47 | 0 | 0 | 15 | 16 | 0 | 0 |
| docs/emulator.md | 5 | 0 | 0 | 0 | 0 | 0 | 12 |
| docs/evaluation.md | 99 | 0 | 0 | 0 | 0 | 0 | 0 |
| docs/final-state.md | 29 | 0 | 0 | 3 | 12 | 0 | 0 |
| docs/runbook.md | 66 | 0 | 0 | 9 | 19 | 0 | 0 |
| docs/service-limits.md | 7 | 0 | 0 | 5 | 6 | 0 | 0 |
| docs/threat-model.md | 61 | 0 | 0 | 33 | 34 | 0 | 0 |
| docs/tool-versions.md | 11 | 0 | 0 | 1 | 9 | 0 | 0 |
| docs/incidents/INC-P15-F-poison-message.md | 6 | 0 | 0 | 0 | 1 | 0 | 0 |
| reports/final-report.md | 247 | 0 | 0 | 93 | 140 | 0 | 0 |
| Total | 834 | 0 | 0 | 245 | 364 | 0 | 12 |

The 245 untracked path links all point to Phase 17 documents: README.md (as `README.md`, `../README.md`, `docs/../README.md`, `reports/../README.md`), docs/api.md, architecture.md, data-lineage.md, demo-script.md, final-state.md, runbook.md, service-limits.md, threat-model.md, tool-versions.md, docs/incidents/INC-P15-F-poison-message.md and reports/final-report.md (checked with `grep -v` of those names: nothing else remained).

External links (not fetched, network not allowed): 12 in docs/emulator.md, all `https://docs.aws.amazon.com/emulator/latest/userguide/` pages (what-is-service, fidelity, getting-started-docker, telemetry, point-sdk-to-emulator, cli-reference, security), as written in Phase 16.

PENDING entries (not links): README 6 lines (E8 twice, E10, E11 twice, E8 for D20), final report 9 lines (E3, E4, E5, E6/E8, E7, E10), docs/final-state.md 11 lines (E6/E8), docs/demo-script.md 1 line (E11). Plain-text, not linked (not in Git yet): the Phase 17 session 2 reports, the Phase 17 work order and course specification, `releases/manifest/RC_SHA/`.

## Replayed commands and outcomes

None (planned deviation 1). Every command in docs/demo-script.md carries one replay line; the commands added to README.md and docs/runbook.md carry one each.

## Unverified claims

- docs/demo-script.md: which clips exist and what each showed rests on the Phase 15 gate (decision 10) and the Phase 15 runbook log rows; the video files themselves are outside Git and were not seen. Clip 02's status is unconfirmed in the log and is not used.
- The two extra guard probes (item 4) are taken from the guard self-test cases `c-sed-i-main` and `f-tail-f-probe3`; the exact strings in the demo (`sed -i.bak s/a/a/ README.md`, `tail -f README.md`) were not run (no agent may run them here). The first differs from the self-test input only in the substitution text.
- docs/api.md section 9: the status each reason leads to is read from the code's reason sets (unbacked, uncovered, conflicts) and the status function, not from a test run.
- The test counts 2343/399 come from repair-01-rag.md and T1; W5 did not read `.claude/.last-test-run`.

## INTERFACE HANDOFF

Files: docs/demo-script.md (new), README.md, docs/api.md, docs/threat-model.md, docs/runbook.md, docs/architecture.md, docs/emulator.md, reports/final-report.md. Documents only; no other owner's file changed.

- Anchors others may link: docs/demo-script.md `#1-private-repository-structure` to `#22-cleanup-or-resource-inventory-evidence`, `#overview`, `#after-recording`; reports/final-report.md `#234-phase-17-review-findings-status-after-the-code-re-check` (renamed from `#234-open-phase-17-review-findings-status-at-this-draft`; no document linked the old anchor), `#122-claim-verification`; docs/api.md `#9-claim-verification-statuses-and-reasons` (unchanged).
- For R-D1 to R-D3: the demo script's commands reuse README lines except item 16's `UnsupportedClaimEndToEndSuite` line and item 4's two extra probes.
- For E11: every `planned for replay (ENN)` line and every PENDING mark listed above; README D14 release-manifest link (E10), D19 and "Final report and video" video link (E11).
- If E1 repairs P17-A-01 or accepts it, the statements to update are: docs/api.md section 9 ("What is still open"), docs/threat-model.md intro, 2.1 and 2.3, README "Known limitations" (L2 and final-review bullets), final report 12.2 ("L2 is not closed"), 16, 19, 23.4.
- Thread-safety and limits: not applicable (documents).

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-17/prompt.md` -> c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (start and end; matches)
- interface check: `grep -n` of the reason codes, AbsenceRule members and failureText in ClaimVerification.scala and RagIngestJob.scala (lines above); `shasum -a 256` of env.sh, probes.sh, deploy.sh, archive-evidence.sh, scripts/README.md, check.sh, release-manifest.sh -> all equal to I1b's recorded values
- link check: 982 occurrences; 834 path links: MISSING 0, IGNORED 0, untracked 245 (all Phase 17 documents); 364 fragment links: ok=364 bad=0; negative control: 2 of 2 fake anchors reported "no"; external 12
- identifier scan (AKIA/ASIA keys, sk-ant-, pa- tokens, arn:aws:, amazonaws.com, execute-api, .elb., twelve zeros, isolated twelve-digit runs) over README.md, docs/api.md, docs/threat-model.md, docs/runbook.md, docs/demo-script.md, docs/emulator.md, docs/architecture.md, reports/final-report.md -> 0, except the expected release-record file name at docs/runbook.md:63
- `grep -c 'PENDING W5'` over README.md, docs/*.md (W-territory) and reports/final-report.md -> 0 each
- `git diff --stat docs/emulator.md docs/evaluation.md` -> docs/emulator.md 3 insertions, 3 deletions; docs/evaluation.md unchanged
- `git status --short`: my changes are docs/emulator.md (M), README.md, docs/demo-script.md, docs/api.md, docs/threat-model.md, docs/runbook.md, docs/architecture.md, reports/ (untracked, Phase 17 documents); every other entry belongs to other agents
- sha256 after the last edit: as listed under FILES CREATED and FILES MODIFIED
files changed after these runs: none
