# documentation-engineer: Phase 17 session 3, E11 repeat after repair round R3

Source of every value: the run log `phase17-reports/runbook-log.md` (scratchpad copy), rows "E2 (repeat, R3)" (:181), "E3a (repeat, R3)" (:182), "E3b (repeat, R3)" (:183), "E4 (repeat, R3)" (:184), "E5 part 1 (repeat, R3; ...)" (:185), "E5 parts 2 and 3a (repeat, R3; ...)" (:186), "E5.40 to E5.47 (repeat, R3; one block)" (:187), "E10 (repeat, R3) release manifest" (:188), "E10b (repeat, R3) commit" (:190), "E11 (repeat, R3) pre-step, manifest checksums" (:191); first-run rows E3 (:32), E4 (:33), "E5 part 1" (:34), "E10 release manifest" (:63), "E10 commit" (:64), "E12r clone path" (:85). Checked in the repository: the two new manifest files' sha256 and byte sizes (`shasum -a 256`, `wc -c`: 1ec1f4d0..., 42,240; 300dca4a..., 20,392, equal to row :188), `daca689` parent = `4feef0f` (`git log -1 --format='%H %P'`), no commit `b4d8f12..4feef0f` touches `infra/terraform/` (0) and none touches `modules/rag`, `prompts`, `modules/services` (empty `git diff --stat`), new manifest md lines 139-147 = "Models (config/course.json)".

Citation convention added (README:9, runbook:14, demo-script:9): repeat runs are cited as "E5.NN (repeat, R3)" or by row name with "(repeat, R3)", the same form as the existing lines.

## Changed lines (old -> new; log row)

### README.md (line count unchanged)
- :9 replay intro: "release candidate b4d8f12 ... clone 1850Z" -> first RC b4d8f12 kept as history, plus new statement: R3 made RC 4feef0f (recorded 2026-11-25T17:23:26Z), E2-E5 and E10 repeated Nov 25 (E3 exit 0, 2362/418 tests in words; E4 9 of 9; every E5 exit 0; E10 exit 0; clone 1725Z, replay clone 1725Z); E6, E12, claude session not repeated (:181-:190, :187).
- :67 catalog intro, claude-session row: + "the first E5's session, not repeated after R3" (:40, :187).
- :71 factory-engineer: E5.14 -> "E5.14 (repeat, R3), 72 passed, 0 failed" (:186).
- :72 build-engineer: "row E3, 2352 module, 399 contract" -> "row "E3b (repeat, R3)", 2362 module, 418 contract" (:183).
- :73 E5.04 (repeat, R3) 600/600 (:185); :74 E5.17 (repeat, R3) (:186); :75 hadoop "E5.05, 114/114" -> "E5.05 (repeat, R3), 124 of 124; 114 of 114 in the first E5, before R3 added suites" (:185, :34); :76 E5.06 147/147; :77 E5.07 43/43; :78 "row E5.08 (re-given)" -> "E5.08 (repeat, R3), 321 passed, 2 ignored"; :79 E5.09 360/2; :80 E5.10 137/1; :81 "E5.11, 399/399" -> "E5.11 (repeat, R3), 418 of 418" (all :185).
- :83 E5.18 and E5.20 (repeat, R3) (:186); :85 E5.35 (repeat, R3) (:186); :88 dependency-updater "row E3" -> "row "E3b (repeat, R3)"" (:183).
- :102 clone line: "E5.42 (1850Z replay) and row E3 (1850Z clone)" -> "E5.42 (repeat, R3; /tmp/p17-replay-20261125T1725Z) and row "E3a (repeat, R3)" (/tmp/p17-clone-20261125T1725Z, HEAD = 4feef0f)" (:187, :182).
- :103 tag-checkout line: stays "deferred to E14"; evidence "E5.41" -> "E5.41 (repeat, R3; "HEAD is now at 4feef0f")" (:187).
- :104 cd line: rows "E5 part 1" to "E5 part 3a" (clone 1850Z) -> "E5 part 1 (repeat, R3)" and "E5 parts 2 and 3a (repeat, R3)" (clone 1725Z) (:185, :186).
- :112 claude --version (E12): "in the clone at the release candidate" -> "in the clone of the first release candidate b4d8f12; E12 was not repeated after R3" (:85).
- :120-121 exports: "E5.21 (... "exports ok")" -> "E5.21 (repeat, R3; both export lines in a subshell)" ("exports ok" not in the repeat row, removed) (:186).
- :125, :129, :135, :136, :138, :147 claude-session lines: stay citing row "E5 optional recorded `claude` session"; added "the first E5's session, on b4d8f12, not repeated after R3" (:40, :187).
- :144-146 E5.14 (72 passed, 0 failed), E5.15 (369/0), E5.16 (69/0), each "(repeat, R3; ...)" (:186).
- :167-171 Phase 02 replay: E5.42 1850Z -> (repeat, R3; 1725Z); "row E5 part 4" -> row "E5.40 to E5.47 (repeat, R3)" (dropped "replay clone HEAD 684c610... afterwards", not in the repeat row); E5.43, E5.44 (printed `684c610…` with that subject, as the repeat row writes it), E5.45 64/0, all (repeat, R3) (:187).
- :176-177 E5.46, E5.47 (repeat, R3) (:187).
- :254 test counts: "final-gate counts ... E3 on b4d8f12: 2352/399, marker 2026-11-23T19:15:55.402093Z" -> "E3 repeated after R3 on 4feef0f: 2362 module (151 suites, 5 skipped), 418 contract (35 suites), marker 2026-11-25T17:46:00.163680Z, row "E3b (repeat, R3)""; first E3 kept as history (:183, :32).
- :258-266 table: E5.01/E5.02, E5.03, E5.07, E5.36-E5.39 (repeat, R3); sbt clean check "row E3, 1303 s, 2352, 34 suites 399 of 399" -> "row "E3b (repeat, R3)", 1205 s (0:20:05.0), 0 error lines, 151 suites 2362 passed 5 skipped, 35 suites 418 of 418"; package.sh lint warning detail dropped (not in repeat row); check.sh "row E4, 2352/399" -> "row "E4 (repeat, R3)", module_tests=2362 contract_tests=418, 8 probes passed" (:183-:186).
- :272 bounded-sample intro: E5 "repeated after repair round R3"; E12 ran in "the first E5's clone, of b4d8f12 (E12 not repeated after R3)" (:85).
- :276 Hadoop pipeline: "E5.13 (1850Z, 130 s, report 18,792 bytes); has not been replayed with the R3 code" -> "E5.13 (repeat, R3; /tmp/p17-hadoop-20261125T1725Z, 150 s; SLF4J notice). This repeat ran the R3 blocking chain (R3 wording kept)"; first run kept as history (:185, :34).
- :277 E5.07, :279 E5.12 (repeat, R3) (:185).
- :292-294 E5.18 ("listed nothing"), E5.19 (provider-version detail dropped, not in repeat row), E5.20 (repeat, R3) (:186).
- :295 plan.sh (E6): "at the release candidate" -> "at the first release candidate b4d8f12; E6 was not repeated after R3" (E6 rows; repeat list :180).
- :324 dry run: row "E5.40 release-manifest dry run" -> "E5.40 (repeat, R3; RESULT ok)" (:187).
- :325 real run: row "E10 release manifest", output b4d8f12 folder, commit c6bbe14 -> row "E10 (repeat, R3) release manifest", output 4feef0f folder, commit daca689 (row "E10b (repeat, R3) commit"); b4d8f12 folder kept as history, commit c6bbe14 (:188, :190, :63, :64).
- :327 script facts: + "both runs were repeated after repair round R3, each exit 0" with the two rows (:187, :188).
- :377-382 derive scripts and git status: (repeat, R3) (:186).
- :388-395 evaluate.py: (repeat, R3); "30 RESULT fixture ... pass lines and no other result line" -> "30 fixture results, all pass, 0 fail" (:186).
- :451 D14: manifest link and sha256 values -> 4feef0f folder, json 1ec1f4d0..., md 300dca4a..., commit daca689, rows "E10 (repeat, R3) release manifest" and "E10b (repeat, R3) commit"; b4d8f12 manifest kept as history with its checksums and commit (:188, :190, :63, :64).
- Unchanged on purpose: :400 and :456 video marks (PENDING E11), :103 deferred to E14.

### docs/runbook.md
- :14 intro: "the release candidate was b4d8f12" -> "the first release candidate was b4d8f12"; + R3 statement (RC 4feef0f, E2-E5 and E10 repeated, clone 1725Z, all exit 0; E6, E12 not repeated) (:181-:190).
- :34 check.sh: row E4 2352/399 -> row "E4 (repeat, R3)" 2362/418 (:184). :35 E5.36, :36 E5.39 (repeat, R3) (:186).
- :46 plan.sh: "at the release candidate" -> "at the first release candidate b4d8f12; E6 was not repeated after R3".
- :85 Hadoop pipeline: as README:276 (:185, :34). :86 E5.07 (repeat, R3). :116 E5.22-E5.26, E5.27 (repeat, R3) (:186).
- :186 dry run -> E5.40 (repeat, R3) (:187). :187 real run -> row "E10 (repeat, R3) release manifest", 4feef0f folder; b4d8f12 folder kept as history (:188). :191 + repeat runs, both exit 0 (:187, :188).

### docs/demo-script.md
- :9 intro: E12 "fresh clone of the release candidate" -> "of the first release candidate b4d8f12"; + R3 statement (:181-:190, :85).
- :61, :70 claude-session lines: + "the first E5's session, on b4d8f12, not repeated after R3".
- :73 E5.15, :82 E5.16, :83 E5.14, :109 E5.17, :125 E5.07, :185 E5.42, :187 E5.43, :198 E5.20, :216-218 E5.22-E5.27, :226: (repeat, R3) (:185-:187).
- :100 sbt clean check: row E3 1303 s 2352 399 -> row "E3b (repeat, R3)" 1205 s (0:20:05.0), 2362, 418 of 418; first E3 kept as history (:183, :32). :101 check.sh -> row "E4 (repeat, R3)" (:184).
- :117 Hadoop pipeline: as README:276 (:185, :34). :186 "row E5 part 4" -> row "E5.40 to E5.47 (repeat, R3)" (:187).

### docs/final-state.md
- :31 new timeline row "Nov 25 (UTC)": new RC 4feef0f; E2-E5 and E10 repeated; manifest written and committed (:181-:190; commit time of daca689 is 21:56Z on Nov 25).
- :111 Terraform validates: row E4 -> row "E4 (repeat, R3)" on 4feef0f; first E4 kept (:184, :33).
- :126 plan.sh: "the release candidate b4d8f12" -> "the first release candidate b4d8f12 (superseded after R3 by 4feef0f; E6 was not repeated, and R3 changed no file under infra/terraform/)" (git log check: 0 commits).
- :133 release manifest: b4d8f12 values -> 4feef0f folder, 42,240 bytes / 1ec1f4d0..., 20,392 bytes / 300dca4a..., commit daca689, build commit 24490a5... (last commit touching build inputs, by design), counts 2362/418, checksums 51 MATCH, 18 MATCH-RECORD, 0 DIFF (row "E11 (repeat, R3) pre-step"); b4d8f12 manifest kept as history (:188, :190, :191, :63, :64).

### docs/threat-model.md
- :7 "b4d8f12 (..., the release candidate)" -> "the first release candidate, superseded after repair round R3 by 4feef0f; R3 did not change the claim verifier" (git diff b4d8f12 4feef0f -- modules/rag prompts modules/services: empty).

### docs/architecture.md
- :260 manifest link b4d8f12/release-manifest.md -> 4feef0f/release-manifest.md (lines 139-147 checked in the new file).

### reports/final-report.md (+3 lines)
- :10 new status bullet: R3 made RC 4feef0f (recorded 2026-11-25T17:23:26Z); b4d8f12 was tested at E3-E5 and E10 and is superseded; E2-E5 and E10 repeated (E3 2362/418, E4 9 of 9, E5 all exit 0, E10 manifest committed); E6-E9 and E12 not repeated (:181-:190).
- :349 Terraform: E4 on 4feef0f row "E4 (repeat, R3)"; first E4 kept; E6 "at the first release candidate b4d8f12" (not repeated) (:184, :33).
- :375-378 test table: first E3/E4 rows relabelled "first release candidate (superseded after R3)"; two new rows: E3 repeat (1205 s (0:20:05.0), marker 2026-11-25T17:46:00.163680Z, 2362, 418, row "E3b (repeat, R3)") and E4 repeat (9 of 9, 2362, 418, row "E4 (repeat, R3)") (:183, :184).
- :380 paragraph: first E3/E4 kept as history with E9's counts; + repeat counts (151 suites, 2362, 5 skipped; 35 suites, 418 of 418; E4 9 of 9, 8 probes); "E9 was not repeated; the repeat counts come from the runbook log" (:183, :184).
- :390 manifest paragraph: -> 4feef0f folder, commit daca689, sha256 1ec1f4d0... / 300dca4a..., build commit 24490a5..., 51/18/0 (rows :188, :191); b4d8f12 manifest kept as history.
- :615 release-manifest.sh: + repeated at E5.40 and E10 after R3, each exit 0 (:187, :188).
- :678 P17-F-05 text: + repeat clone 1725Z, row "E3a (repeat, R3)" (:182).
- :699 P17-F-05 row: + repeat clone 1725Z and E10 repeat exit 0 (:182, :188).
- :750 state after Phase 17: plan "at the first release candidate b4d8f12 (E6, not repeated after R3)".
- :754 local reproduction: first replay kept as history; + repeat on 4feef0f (E3 2362/418, E4 9 of 9, every E5 exit 0, Hadoop pipeline with the R3 blocking chain); E12 "(not repeated after R3) ... in the first clone" (:183-:187, :85).

## Unverified claims
None. Every new value is in a named log row or was checked in the repository with read-only commands (listed above). Details in first-run text that the repeat rows do not repeat ("exports ok", provider 6.59.0, Docker lint warning, local-run-report.json size, replay clone HEAD) were removed from repeat citations or kept only on first-run history clauses.

## Link check
Relative Markdown links in README.md, reports/final-report.md and the 13 owned docs/*.md files (docs/agents.md excluded), anchors stripped, each target checked with `git ls-files` and `git check-ignore --no-index`: links=909 broken=0 ignored=0. Sanity check: a nonexistent manifest path returns 0 files; the new 4feef0f folder returns 2. No heading changed, so no anchor changed.

## Identifier and ratio scan (added lines of `git diff -U0`, 123 lines)
- twelve-digit runs: 0 (the release-record short commit was not added anywhere)
- twelve zeros: 0
- arn:aws / amazonaws / s3:// / AKIA: 0
- IPv4: 0
- N/N ratios: 12, all old text on edited lines (43/43 x5, 11/11, 14/14, 17/17, 3/3, 6/6, 147/147, 600/600). The ratios this pass added or changed are in words ("124 of 124", "114 of 114", "418 of 418").

## Diff
`phase17-reports/diff-E11-docs-R3.txt` (624 lines, sha256 5f13ca608e4fe8db0ee2b6858e8ce2ad87995a052aef336894f820772ac0af5b).

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17 session 3, E11 repeat after repair round R3 (instructor-approved)
FILES CREATED: none in the repository; scratchpad: phase17-reports/diff-E11-docs-R3.txt, phase17-reports/agent-report-docs-E11-R3.md
FILES MODIFIED: README.md, docs/runbook.md, docs/demo-script.md, docs/final-state.md, docs/threat-model.md, docs/architecture.md, reports/final-report.md
COMMANDS RUN: read-only grep, git -C (diff, log, ls-files, check-ignore), shasum -a 256, wc, ls, head, cut; link-check loop; git diff > scratchpad diff file
TEST RESULTS: not applicable (Markdown only; no sbt)
VALIDATION RESULTS: link check 909 links, 0 broken, 0 ignored; identifier scan 0 matches; ratios: 12 old N/N on edited lines, new ones in words
ASSUMPTIONS: repeat rows are cited by their short name plus "(repeat, R3)", as the work order names them; the committed docs/agent-runs/phase-17/runbook-log.md will hold these rows once the human copies the log
RISKS: the abbreviated row names (for example "E5 part 1 (repeat, R3)") are prefixes of the full row headers; the old N/N ratios on edited lines were left as they were
MANUAL ACTION REQUIRED: human copies runbook-log.md with the repeat rows into docs/agent-runs/phase-17/ so the cited rows are there; video link still open (PENDING E11 marks unchanged)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer check of the changed lines and replay lines against the runbook log and manifest-checksums-2.txt; identifier scan; human commit

## INTERFACE HANDOFF
- Files changed: the seven above, Markdown only; no heading or anchor changed; README line count unchanged; final-state.md +1 line (after :30), final-report.md +3 lines (after :9 and :375), so the final-report line numbers after :9 moved by 1 and after :376 by 3 (no document cites final-report or final-state line numbers).
- Public signatures, fixtures, thread-safety: not applicable.
- Limits: E6, E7, E8, E9, E12 and the optional claude session are still cited from the first candidate, and the text says so.
