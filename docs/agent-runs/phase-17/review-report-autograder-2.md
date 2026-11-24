## Focused re-check: E12 autograder follow-up (response draft fixes F1-F5, docs F7/F8, identifiers)

**VERDICT: APPROVE.** No critical or major finding is left. F1 (the only major) is fixed and the record supports it. F3, F4, F7 and F8 are fixed. F2 and F5 are fixed in substance, with two small wording leftovers in the draft (N1, N2 below). They are optional and don't block.

### Item 1: response draft (R/autograder-response.md)

| Item | Result | Evidence |
|---|---|---|
| F1 (A6) | FIXED | Draft :96 cites the log row "E12 A6 tfvars review (human)" and adds "A read-only check of course.tfvars:1-31 agrees". The row is at R/runbook-log.md:99. It quotes the human's E12 instruction ("A6: I reviewed course.tfvars myself: no secrets.") and says openly that the earlier row did not record it. |
| F2 (A8) | FIXED | Table :120-122 gives 27 / 5 / 0. That adds up to 32, which matches "every 11th item, starting with item 1" at :113 (items 1 to 342). Item 331 is classed as a scratchpad path at :124, and the "…" wildcard rule is stated there too. Log row :100 records the correction to 27/5/0. Leftover N1 below. |
| F3 (A2) | FIXED | :54 now reads "an `APPROVED-PLAN-SHA256:` line, or for phase 12 the plan sha256 line in the phase-12 human gate". The table at :58 says "(plan sha256)". |
| F4 (K02) | FIXED | :81 reads "5 entries in 4 files, in commits 6647034 (… runbook-log.md) and d37389b (the other three files) … K02 does not list ErRepairRegressionSuite.scala." |
| F5 (a) spec folder | FIXED | :218 cites `docs/agent-runs/phase-17/course-specification.md:363` and `:384`. |
| F5 (b) actor voice | MOSTLY FIXED | The checks are now attributed to the orchestrator or the research agent (:53, :69, :113, :164, :191). Leftover N2 below. |
| A4/A5, the two other K01 hits | SUPPORTED | Draft :88-90 says EmulatorWiringSuite.scala:113 and ErRepairRegressionSuite.scala:454 "are made-up test strings, checked by the human (runbook-log row "E12 autograder decisions 3")". Row R/runbook-log.md:101, item (2), says the human looked at both lines and "states both are made-up test strings; recorded in autograder-response.md A4/A5 as checked by the human." The example-key hits cite "decisions 2", and row :94 supports that. I did not open the key strings. |
| C1 kept as is | SUPPORTED | Draft :275-277 ties the partial to A2/A7 and marks the gate-commit effect "(not verified)". Row :101, item (1), says "C1 as is". |
| Other cited rows | EXIST and MATCH | Each of these rows exists and says what the draft claims: "E12 autograder work, instructor approval" (:90, draft :3), "Repository visibility" (:96, draft :3), "E12 autograder decisions 2" (:94, draft :70 and :90), "E12 A14 guard probes" (:95, draft :217), "E12 autograder results" (:91, draft :262). |

### Item 2: docs

- **F7: FIXED.** `docs/hadoop-job-contracts.md:47` now ends with "(inferred from those two lines and from the bounded-pipeline run record note "built-in defaults (-D scholarchain.code.version only)", `scripts/run-bounded-pipeline.sh:1439-1440`; no EMR output listing was checked)". All three sources check out:
  - `infra/terraform/modules/workflow/sfn.tf:358`: the `Args` list has only the `-D scholarchain.code.version=…` before `--output`.
  - `ScholarChainHadoop.scala:20-21`: "generic `-D` options are only read before the first application option".
  - `run-bounded-pipeline.sh:1439-1440`: both branches of the note say "built-in defaults".

  The agent kept `modules/workflow/sfn.tf` instead of the brief's misnamed environments path (agent report :20). That is correct. The file is still 227 lines, and the tail at :224-227 is unchanged. Because the file is untracked, I can't diff it to prove nothing else changed; the agent's report says only line 47 was touched.
- **F8: FIXED.** `reports/final-report.md:199` adds "one, `sizeBuckets`, grows sublinearly with the input, see [section 23.1](#231-system-and-scope-limitations)". The anchor resolves to `### 23.1 System and scope limitations` at :606, the only match. The new text now agrees with :619, which says it "collects one row per distinct component or community size … fewer than √(2N) rows".
- **Nothing else changed.** R/diff-autograder-docs-2.txt has only the :199 hunk and the :616-619 hunk, which I approved in the earlier review and which is unchanged.

### Item 3: identifiers

I searched the response draft and R/diff-autograder-docs-2.txt for twelve-digit runs, `arn:aws`, `amazonaws`, `execute-api`, AKIA/ASIA key shapes, IPv4 addresses and http(s) URLs. There were no matches in either file. The draft also has no `s3://` and no queue URL. The new text at hadoop-job-contracts.md:47 holds only file paths and a property name. No key text is quoted anywhere.

### Findings

| ID | Severity | file:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| N1 | minor (optional) | R/autograder-response.md:133 | The heading still says "The 4 that are not claims" and lists 4 items, but the table at :121 now says 5. The fifth, item 331, is explained separately at :124. | A reader may think the count is off by one. Possible fix: "The other 4 (item 331 above)". | orchestrator / human |
| N2 | minor (optional) | R/autograder-response.md:114 | "I used `git show --name-only` and `git log`" is still in the first person, right after "The research agent took a sample" at :113. | This is what F5(b) was about: in a human-voiced record it suggests the human ran the commands. Possible fix: "It used …". | orchestrator / human |

Process note, not a finding: documentation-engineer ran `sed -n` (read-only, but not on the allow list) and redirected a `git diff` into the scratchpad, which is an allowed `/private/tmp/claude-*` target (agent report :9-13).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17, session 3, E12 autograder follow-up, focused read-only re-check of R/autograder-response.md (F1-F5, A4/A5 K01 hits, C1) and docs F7/F8
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run (Markdown review only)
VALIDATION RESULTS: F1 fixed (draft :96; runbook-log :99); F2 fixed, counts 27/5/0 (:120-122, :124; log :100); F3 fixed (:54, :58); F4 fixed (:81); F5 folder fixed (:218); A4/A5 supported by log :101 (2) and :94; C1 supported by log :101 (1); every cited log row exists (:90, :91, :94, :95, :96, :99, :101); F7 fixed and its sources verified (sfn.tf:358, ScholarChainHadoop.scala:19-21, run-bounded-pipeline.sh:1439-1440); F8 fixed, anchor resolves to final-report.md:606, consistent with :619; diff has only the :199 hunk plus the previously approved 23.1 hunk; identifier search of the draft and the diff is clean
ASSUMPTIONS: R/runbook-log.md rows 99-102 are the version the human will commit alongside the response; row 99 correctly records the human's E12 instruction (it is the orchestrator's record of it, and the human ratifies it by committing); hadoop-job-contracts.md is untracked, so "only line 47 changed" rests on the unchanged line count and tail plus the agent's report
RISKS: N1 and N2 wording leftovers if left as they are; the course.tfvars content was verified in my earlier review and not re-opened in this pass
MANUAL ACTION REQUIRED: human: optionally fix N1/N2 in the draft before copying it; copy autograder-response.md and the updated runbook-log.md into docs/agent-runs/phase-17/ in the same commit (earlier F6)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: identifier scans of the staged diff (section 20 item 3: staged-diff scan, plus the history scan for the new commit), then the human commit of the docs, the response and the runbook log; then the E12 repeats and the gate commit (human-gate.md, commit.txt)
```
