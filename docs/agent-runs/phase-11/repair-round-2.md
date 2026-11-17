# Phase 11 repair round 2 (human-requested): R3-01 and R2-03

Phase 11 ended `COMPLETE_WITH_OPEN_ITEMS`. The spec's Phase 11 gate needs "No critical or major verified finding remains", so I am running one more repair round myself, for two items only. Everything else stays as recorded.

**Rules:** the "Standing rules" section of `docs/agent-runs/phase-11/prompt.md` applies in full (read only that section, plus this file). There are three changes:
- **Waiting:** the harness blocks `sleep 240`. The orchestrator waits for each agent's completion notice. If it needs to poll a file, it uses one bounded loop per Bash call that ends in under 10 minutes (timeout 600000).
- **Searching without a Grep tool:** if you have no Grep or Glob tool, search with Bash `grep -rn '<word>' <literal path>` and `ls`. Never use `cd`: give full repository-relative paths. Never put a word from the banned-words list on a command line. Never use `od`.
- **Reports:** write reports with the Write tool to the scratchpad folder below, using the `round2-` file names. An existing report is never overwritten.

**Starting point:**
- The Phase 11 work commit is the current HEAD. The orchestrator records `git rev-parse --short HEAD` at the start, puts it in every work order and the summary, and uses it as the base of the diff.
- Expected tree: clean, except untracked evidence files under `docs/agent-runs/phase-11/` (they are mine; leave them alone).
- The Phase 11 orchestrator summary is at `docs/agent-runs/phase-11/orchestrator-summary.md`. Read the R3-01 and R2-03 rows only.
- Round-2 reports go to this session's own scratchpad, in a new `phase11-reports/` folder there. The orchestrator gives agents the absolute path.

## Steps (one after another; one sbt at a time)

1. **rag-engineer: R3-01 (major)**
   - Problem: `ClaimVerification.scala:349-353` treats any plain number from 1900 to 2100 as a year. A metric value in that range (for example W = 2034) is marked conflicting and the claim is removed.
   - Fix: a number in the claim is checked as a year **only if it does not match a cited non-year value** under the existing `literalMatches` rules. Matching a cited metric value counts as the metric. A number that matches no cited metric value is checked against the cited year fields (`EvidenceValues.YearFields`) as now.
   - Tests that fail without the fix:
     - a claim that states a metric value of 2034, citing evidence with that metric, is supported;
     - a decimal metric in the range, for example 1999.5000 printed as `1999.5`, is supported.
   - Tests that must still pass: the planted wrong-year test (L-2) and every existing ClaimVerification suite.
   - Known limit, record it in the report: a wrong year that happens to equal a cited metric value passes. Say how likely that is with the current evidence fields.
   - Targeted tests (one sbt invocation), then re-run the fake benchmark with the long-run pattern into `modules/rag/target/phase-11-real/attempt-02-fake`. Report:
     - planted errors caught (n of n);
     - claim statuses compared with attempt-01-fake;
     - any claim whose status changed, with one line on why.
   - Report: `round2-repair-rag.md` with TEST EVIDENCE and INTERFACE HANDOFF.
2. **entity-resolution-engineer: R2-03 (er-v2)**
   - Decision (mine): merge behavior changed in Phase 11, so the policy version becomes **`er-v2`**.
   - Change the version label where the ER policy defines it. If the label also lives in a file you don't own (for example the hadoop defaults XML), don't edit that file: list the file and line for its owner.
   - Old outputs (the Phase 7 export, run=51b50d…) stay labelled `er-v1`. Do not touch them, and do not change stable-ID rules unless the version is part of the ID by design (say which it is).
   - Add a test that pins the version label to the new behavior.
   - If the bump changes golden files or more than about 10 test files, stop and report BLOCKED-ON human with the list. Don't make the sweep.
   - Targeted tests. Report: `round2-repair-er.md`.
   - Any line routed to another owner in step 2 goes to that owner as step 2b (targeted tests), only if it is a one-line label change.
3. **test-engineer: closing check (wave closer)**
   - Re-run the relevant adversarial tests, then one full `sbt check` with the long-run pattern, into the log `round2-check.log`.
   - Report: `round2-test-report.md` with TEST EVIDENCE: module and contract counts, `.fail` marks unchanged (T-02..T-06, F8, F9), and expected-failure flips.
4. **code-reviewer: re-check**
   - Read the complete diff since the Phase 11 work commit (`git diff <base>`; ignore `docs/agent-runs/`).
   - Rule R3-01 and R2-03 RESOLVED, NOT RESOLVED or PARTIAL with file:line. Note any new finding with its severity.
   - Report: `round2-review-report.md`.
5. **orchestrator: summary**
   - Start with exactly one line: `PHASE RESULT: COMPLETE`, `COMPLETE_WITH_OPEN_ITEMS` (list them), `GATE_NOT_MET` or `BLOCKED`.
   - Write it to `round2-summary.md`.
   - Print `shasum -a 256` for each round-2 file, then one `cp -n <full path> docs/agent-runs/phase-11/<name>` line per file, for me to run.
   - Do not commit.

No new repair round inside this run. A new major finding goes in the summary for me.
