## repair-E13-docs-2: touch-up of docs/evaluation.md for P16-L-23 and P16-L-24

### Changes

1. P16-L-23, docs/evaluation.md:205 (section 5, Wilson row): "Both are EXPLORATORY:" replaced by "The two group 4 sets are EXPLORATORY:". The rest of the row, including the plain line, is unchanged. Group 1's Phase 5 Wilson rows are no longer covered by the label.
2. P16-L-24, docs/evaluation.md:207 (section 5, group 3 bootstrap row, "Where" column): "group 3" now reads "group 3 (EXPLORATORY: the questions are not a random sample, so the interval only illustrates variability and is not a confidence interval for the system)". This uses the "questions" wording for group 3, as at :50, :130 and :480.
3. P16-L-24, docs/evaluation.md:208 (section 5, group 4 bootstrap row): "EXPLORATORY, as above" replaced by "EXPLORATORY: the answers are not a random sample, so the interval only illustrates variability and is not a confidence interval for the system". This is the human's plain line, verbatim.
4. 10.9 repair list, docs/evaluation.md:846: added one paragraph recording that the re-check (APPROVE) raised P16-L-23 and P16-L-24 and that both were repaired in this touch-up.

Nothing else changed. No interval value, parameter or citation was changed. The file went from 848 to 850 lines: one new paragraph plus a blank line before it.

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, as given), Phase 16 session 3 touch-up for P16-L-23 and P16-L-24 (minor, human approved)
FILES CREATED: none in the repository (this report is in the session scratchpad)
FILES MODIFIED: docs/evaluation.md (:205, :207, :208, :846)
COMMANDS RUN: shasum -a 256 docs/evaluation.md; grep -cE '[0-9]{12}' docs/evaluation.md; grep -nE '[0-9]{12}' docs/evaluation.md; wc -l docs/evaluation.md; git status --short
TEST RESULTS: not applicable (documentation only; no source in a relevant path changed)
VALIDATION RESULTS: 12-digit scan: 0 matches; wc -l 850; git status shows only docs/evaluation.md modified among tracked files (the untracked experiments/ entries were already there at the start of the session)
ASSUMPTIONS: the 10.9 "line" recording the touch-up may be one short paragraph after the L-17..L-22 list; "questions" replaces "answers" in the group 3 plain line, following the reviewer's note on group 3 wording
RISKS: none known; no link was added or changed, so the link check is not repeated
MANUAL ACTION REQUIRED: the human commits docs/evaluation.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: optional code-reviewer glance at :205-208 and :846; human commit; E14; E15
```

### VALIDATION EVIDENCE

- `shasum -a 256 docs/evaluation.md`: cebfb7f06e04ad9ba1f0a78ba74c92b313e34c3010fffd9e6a504e842ec2aa88
- `grep -cE '[0-9]{12}' docs/evaluation.md`: 0

files changed after these runs: none
