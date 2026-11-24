# documentation-engineer report, Phase 17 session 3, step E12 (replay-line fill-in)

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (results rules lines 37-45, replay-line forms line 45, E11 line 352, release-manifest rule line 213); E12 invocation prompt. HEAD ade85aad5bab4535fc8431d82c574b3a9b70aa1a; RC b4d8f1229fc145e1492e1a8cbb8a44a368d026af.
FILES CREATED: none in the repository (this report and diff-E12-docs.txt in the scratchpad only)
FILES MODIFIED: README.md, docs/runbook.md, docs/demo-script.md, reports/final-report.md (docs/final-state.md: no line repeats the old status; unchanged)
COMMANDS RUN: grep (read-only) over the four docs, docs/final-state.md, scripts/README.md (read only) and the runbook log; Read of scripts/archive-evidence.sh:560-599 (read only, to check which branch the E12 WARN implies); ls; git -C REPO diff --stat / diff -U0 / status --short; git -C REPO diff > scratchpad/diff-E12-docs.txt. One deviation: my first command used `sed -n` (print only, read-only) to read prompt.md lines 37-45, 213, 352; the invocation forbids `sed`. Nothing was written by it. No `cd` took effect in a write; one `cd ... 2>/dev/null;` prefix was used before a grep (no effect on output; outside the command forms, noted). No sbt, no scripts, no AWS, no network.
TEST RESULTS: n/a (Markdown only; no source change)
VALIDATION RESULTS: changed-line link check passed (see below); identifier scan of the 19 added lines: 0 hits; `not replayed in phase 17` now remains only on the lines the work order keeps (README:147, :170-:173; demo-script:71, :72, :175, :188) plus the legend texts that name the form.
ASSUMPTIONS: (1) Evidence label = the exact runbook-log row label (e.g. "E12r.2 QueryApiSuite (README:276)"). (2) "in the same clone" = /tmp/p17-clone-20261123T1850Z, as the E12 summary states.
RISKS: (1) Judgment beyond the literal wording "current code is runtime tested once": scripts/archive-evidence.sh:575-577 shows the WARN printed at E12r.6 comes from the `SC_ACCOUNT_ID` empty branch, so the E-03 branch (:580-586, which needs a known account) did not execute. The docs say the mode's current code ran once (exit 0) AND that the E-03 branch itself has still not run. The orchestrator should confirm this wording. (2) The README:110 command cell now says the command prints the installed version, not "must print 2.1.330".
MANUAL ACTION REQUIRED: human review of the diff; commit.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer check of the changed lines against runbook-log rows E12r.1-E12r.6; identifier scan; human commit.

## Changed lines (old -> new status)

| File:line (after edit) | Old | New |
|---|---|---|
| README.md:9 (legend) | replays "steps E3 to E6 and E10"; "E3, E4 and most of E5 ran in a fresh clone"; "Step E11 filled in the observed results." | "steps E3 to E6, E10 and E12"; "E3, E4, most of E5 and E12 ran in a fresh clone"; "...and step E12 the results of its six replays." |
| README.md:106 | "Install Claude Code `2.1.330` and the local toolchain." | "Install Claude Code and the local toolchain." 2.1.330 kept as the recorded Phase 17 version; adds that the client updates itself and printed 2.1.331 at the E12 replay |
| README.md:110 | `claude --version` (must print `2.1.330`) / not replayed in phase 17 (no E5 row runs it; ...) | command cell: prints installed version, 2.1.330 recorded, E12 printed 2.1.331 after automatic update / replayed in phase 17 (E12, exit 0); evidence: runbook-log row "E12r.1 `claude --version` (README:110)" |
| README.md:112 | 2.1.330 facts + E11 human report | same facts kept; adds "It is the Claude Code version recorded for Phase 17", the E12 replay printed 2.1.331 with exit 0, and that this shows only an automatic update; recorded version stays 2.1.330. No claim that any session used 2.1.331. |
| README.md:270 (intro) | "the other four suites ran inside the full test runs" | "step E12 ran the other four suites as separate `testOnly` lines in the same clone" |
| README.md:276 QueryApiSuite | not replayed in phase 17 (...) | replayed in phase 17 (E12, exit 0); evidence: runbook-log row "E12r.2 QueryApiSuite (README:276)" (11/11) |
| README.md:278 AlgorithmsSuite | not replayed in phase 17 (...) | replayed in phase 17 (E12, exit 0); evidence: ... "E12r.3 AlgorithmsSuite (README:278)" (6/6) |
| README.md:279 JsonlExportSuite | not replayed in phase 17 (...) | replayed in phase 17 (E12, exit 0); evidence: ... "E12r.4 JsonlExportSuite (README:279)" (14/14) |
| README.md:280 JobConfigSuite | not replayed in phase 17 (...) | replayed in phase 17 (E12, exit 0); evidence: ... "E12r.5 JobConfigSuite (README:280)" (3/3) |
| README.md:319 archive --local-only | not replayed in phase 17 (...) | replayed in phase 17 (E12, exit 0); evidence: ... "E12r.6 `archive-evidence.sh --local-only` (README:319)" (RESULT ok, 170 files, one by-design WARN) |
| README.md:325 script facts | E-03 `runtime: untested`; mode last ran in Phase 13 | mode ran in Phase 13 and once with current code at E12 (exit 0, row E12r.6); scripts/README.md not changed after the RC and does not record this run; the E-03 branch itself (needs a known account) has still not run |
| docs/runbook.md:14 (legend) | "steps E3 to E6 and E10 (filled in ... at step E11)"; "E3, E4 and most of E5 ran in the fresh clone" | "steps E3 to E6, E10 and E12 (... at steps E11 and E12)"; "E3, E4, most of E5 and E12 ran in the fresh clone" |
| docs/runbook.md:184 | not replayed in phase 17 (...) | replayed in phase 17 (E12, exit 0); evidence: runbook-log row "E12r.6 ..." |
| docs/runbook.md:189 | E-03 `runtime: untested`; "not replayed in Phase 17 ... untested at runtime" | replayed once at E12 in the clone, exit 0; current code of this mode runtime tested once, local-only mode; scripts/README.md note; E-03 branch itself not run |
| docs/demo-script.md:9 (legend) | "steps E3 to E6 (filled in ... at step E11)" | "steps E3 to E6 and E12 (... at steps E11 and E12; E12 ran in the fresh clone of the release candidate)" |
| docs/demo-script.md:159 QueryApiSuite | not replayed in phase 17 (...) | replayed in phase 17 (E12, exit 0); evidence: runbook-log row "E12r.2 QueryApiSuite (README:276)" (11/11) |
| reports/final-report.md:389 | "the human runs them (E3 to E6)" | "(E3 to E6 and E12)" |
| reports/final-report.md:612 | script changes "all `runtime: untested`" | same, except archive-evidence --local-only ran once at E12 (exit 0, row E12r.6); the E-03 branch itself has still not run |
| reports/final-report.md:746 | "The `QueryApiSuite` line itself was not run as a separate `testOnly` line ..." | E12 ran the six README lines E5 had not run, each exit 0 (claude --version printed 2.1.331 after an automatic update, recorded version 2.1.330; the four testOnly lines with counts; archive local-only), rows "E12r.1" to "E12r.6" |

Unchanged on purpose: the three video marks; P17-M-06 `exit n/a` lines; README:147, :170-:173; demo-script:71, :72, :175, :188; docs/final-state.md (no matching line); docs/tool-versions.md:27 (2.1.330 is the recorded value).

## Link and fragment check (changed lines, by hand)

- `docs/tool-versions.md#1-operator-machine-phase-17` (README:106, :112): file exists; heading `## 1. Operator machine (Phase 17)` at line 18 -> fragment valid.
- `docs/agent-runs/phase-13/human-gate.md` (README:325; runbook:189 as `agent-runs/phase-13/human-gate.md`, relative to docs/): file exists. The quoted label in both lines (pre-existing text) is "Archive (`--local-only`)"; the file reads `**Archive** (`--local-only`): passed, 13 files` (line 66): same words, bold markup differs; not changed by me.
- No new links were added; every runbook-log reference is a row label, checked against runbook-log rows 77-82.

## Identifier scan of added lines

`git diff -U0 | grep '^+'` (19 added lines) against twelve-digit runs, `arn:aws`, `amazonaws`, `s3://`, `sqs.`, `http(s)://`, `AKIA`, dotted IPv4: 0 hits. No archive sha256 or archive path written.

## Unverified claims / open items

- E11 re-check open minor (demo-script:9 says the `sed -i.bak`/`tail -f` probes are in "item 6"; they are in item 4) is NOT fixed: the E12 work order says "change nothing else". Needs an explicit instruction if it is to be fixed.
- P17-M-04 (scripts/README.md:59 and its status line not recording E10/E12 runs) stays open; infra-engineer, not changed after the RC.
- Proposed later: docs/tool-versions.md note on 2.1.331 (E11 list).

## INTERFACE HANDOFF

Files changed: README.md, docs/runbook.md, docs/demo-script.md, reports/final-report.md (Markdown only; 19 lines changed, 19 insertions / 19 deletions). Public signatures: none. Fixtures: none. Thread-safety: n/a. Limits: no file added, deleted or renamed; nothing under scripts/**, docs/agents.md, docs/agent-runs/**, docs/incidents/**. Diff: scratchpad phase17-reports/diff-E12-docs.txt (150 lines).
