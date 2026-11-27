# repair-docs-final-cut-2: documentation-engineer repair of M-1 to M-3 and the item 20 sample-spacing note (docs/demo-script.md), Phase 17 session 3, post-release-candidate docs-only pass

(Saved by the orchestrator from documentation-engineer's final message, in substance.)

| Point | Line(s) | Change |
|---|---|---|
| M-1 (item 1) | :71 | "the Public label" kept; citation added: on screen in clip 22a, the human's confirmation, runbook-log row "E12 final-cut decisions (human)". |
| M-2 (overview) | :27, :29 | Column header "Time" -> "Planned start"; one sentence: the column holds the times of the human's video edit plan, not times measured on the final cut. Times :31-41 unchanged. |
| M-3 (blur rule) | :17 | "(08 to 13)" kept (every console clip in the records is in that range); added "clip 18 is not a console page, the human, runbook-log row ...". Item 18 still "nothing to blur" (:250). |
| Item 20 | new :264 | "Sample spacing": raw service samples (service-samples.csv) about 19 s apart (mostly 18 or 19 s), versus the 15 s in experiments/results-summary.md line 446 ("15 s describe-services samples"); that file unchanged (the human's decision, row ...). |

Verification: the committed raw samples file of experiment C (read only): 88 samples; after a first gap of 3 s, gaps of 18 or 19 s with three of 20-21 s; 87 gaps average about 18.5 s. results-summary.md:446 says "Counts come from 15 s describe-services samples"; not edited.

Incremental change since diff-demo-script-8.txt: only these four edits; no command added; 34 replay lines and 25 headings unchanged; no later-fill-in or post-tag wording. Link check: one new link (../experiments/results-summary.md), target exists. Identifier scan of changed lines: clean.

Diff: diff-demo-script-9.txt, 443 lines (153 insertions, 141 deletions against HEAD 5b911d9), sha256 995ad136b690452bc0984d1cf3e0fb7f983e8e985278108b3197314624f5d047.

Points to note: :17 says "cropped" while the human's frame check says "blacked out" (left as the order's wording); the row "E12 final-cut decisions (human)" is cited at :17, :71, :264 and must be copied into the committed runbook log in the gate commit.

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md; Phase 17 session 3; the human's final-cut decisions on M-1, M-2, M-3 and item 20
FILES CREATED: scratchpad diff-demo-script-9.txt
FILES MODIFIED: docs/demo-script.md
COMMANDS RUN: read-only ls, wc -l, grep, head -n, tail -n, cut, tr, shasum -a 256, git diff (to the literal scratchpad path), git diff --stat. Deviations: cut and tr outside the listed commands; two read-only calls began with `cd` into the repository root (nothing written).
TEST RESULTS: none (documentation only)
VALIDATION RESULTS: as above
ASSUMPTIONS: the cited row is copied unchanged; console clips are those whose Blur lines say console (08 to 13)
RISKS: "cropped" vs "blacked out"; the cited row must be committed
MANUAL ACTION REQUIRED: the human copies the row in the gate commit; optionally decides "cropped" vs "blacked out"
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: quick code-reviewer re-check of diff-demo-script-9.txt, then the scan step and the commit
INTERFACE HANDOFF: docs/demo-script.md lines :17, :27, :29, :71, new :264; no command, replay line or heading changed.
