# Amendment 02, step 4: code-reviewer re-check (review-report-02)

**VERDICT: REQUEST_CHANGES.** Findings 1, 3, 4, 7 and 9 are closed. Finding 2 is still open: the new xargs block can be dodged with two trivial respellings, and both reach the test marker. The repair also added one major problem of its own: the new allow list lets some write commands run without any prompt (your open item B1). One older major issue, backdating a source file (B2), also stays open.

I did not run anything. Each ruling below comes from tracing the inputs by hand through the installed `/Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh` (the normalizer at :217-357, `analyze_segment` at :401-571, `guard_bash` at :648-843, the dispatcher at :927-932) and `/Users/brian/Developer/repos/scholarchain/.claude/hooks/verify-tests-ran.sh`.

## Rulings on the repaired findings

**Finding 1: respellings. CLOSED for the four forms I reported.**
- **(a) `$'sed' -i 's/a/b/' modules/core/X.scala`:** the normalizer at :304-318 now reads `$'sed'` as `sed`, so the sed -i rule at :464-469 blocks it. `$'tail' -f` and `$'watch'` are blocked the same way.
- **Escaped forms:** `$'s\x65d'` as the command word is blocked at :435-438. As an argument of a command the guard inspects it is blocked at :444-451. Run as code, it is blocked at :780-783.
- **(b) `bash -c -- '…'`:** the code word is now found after options (:262-265), so it is checked and blocks. `-o pipefail`, a redirection before the code, and `2>&1` before the code are handled at :260-261, :279-282 and :285.
- **(c) `env -S`:** unwrapped as code in all three forms (separate, attached, `--split-string`) at :269-273.
- **(d) `exec -a x sed -i …`:** blocks through the `wrapper_optarg` entry at :383.
- **Self-tests:** the r1-* BLOCK cases would return ALLOW if their rules were removed.
- **Residual:** a wrapper written with its path, such as `/usr/bin/env sed -i …`, still gets through. It is a separate gap, recorded as the second row of finding 2's table.

**Finding 2: fake-fresh evidence. OPEN.**

What works:
- **Original inputs:** both now block (`find modules/core -name 'TEST-*.xml' | xargs touch` and `echo … | xargs cp /tmp/claude-1/a`).
- **Literal touches:** `touch` on a literal test-report path or the marker blocks at :592-593 and :624-626.
- **Timestamp check:** `report_after` (stop hook :267-275, :335) requires the timestamp sbt writes inside each report to be later than the change. Touching an old report therefore no longer covers a change, even if the guard were bypassed.
- **Self-tests:** s29-s34 would allow without `report_after`.

What does not work:
- **Respellings reach the marker.** The marker check (stop hook :151-155) looks only at the file's modification time, and it runs before any per-project check. Two respellings of the new xargs rule still touch it.
- **Backdating (B2)** defeats the new timestamp check.

Details are in the findings table.

**Finding 3: the code-reviewer stall. CLOSED (human decision A).**
- **Gating:** the exemption at stop hook :200 is checked only on SubagentStop. `read_only_agent` (:168-198) fails closed on each of these:
  - an empty or unusual `agent_type`;
  - a missing file;
  - missing or unclosed frontmatter;
  - a `name:` that does not match;
  - zero or several `tools:` lines, or a multi-line, empty or wildcard `tools` field;
  - Write, Edit, MultiEdit, NotebookEdit or Bash, including `Bash(...)`, in any letter case.
- **The real file:** `.claude/agents/code-reviewer.md:1-5` (`tools: Read, Grep, Glob`) qualifies.
- **The three required self-tests exist:**
  - s35: the reviewer is exempt while service changes are untested;
  - s37: a writing agent is not exempt;
  - s38: an unknown agent is not exempt.

  s35 and s37 would fail if their rule were removed. s38 is covered by two separate checks, as the factory report says.
- **Main-session Stop is unchanged** (s36 pins it).
- **Records:** the spec wording is recorded in `docs/agents.md:139` and `factory-engineer.md:60`.
- **Assumption for probe 1:** Claude Code must send `agent_type` in SubagentStop events. If it does not, the hook fails closed and the reviewer stall comes back.

**Finding 4: an ask hiding a later block. CLOSED.**
- The three asks inside `guard_bash` now use `defer_ask` (:679, :761, :836). The only remaining direct asks are in the path guard (:885, :904, :906), where an ask is the final decision.
- The dispatcher returns the deferred ask only after `guard_bash` has run every rule (:931).
- `git commit -m x; sed -i s/a/b/ README.md` in the main session is now blocked; the r4 cases cover it. r4-push-alone and r4-aws-alone would return ALLOW if the deferred ask were never returned.

**Finding 7: the wave-closer default. CLOSED.**
- The new phrase is in all 10 role files, and the old phrase no longer appears anywhere outside `docs/agent-runs/`.
- It now matches `CLAUDE.md:53`.

**Finding 9: the allow list. CLOSED as ordered.**
- `settings.json:22-32` holds exactly the 11 ordered rules.
- The new problem it causes is the first row of the second table (B1).

**Decision 9 expected-allowed cases: all still pass with no block or ask.**
- I re-traced `sbt -batch check > <scratch>/check.log 2>&1`: `2>&1` is not treated as a separator (:279), and `&1` is skipped as a target (:791).
- I also re-traced `grep -n x docs/agent-runs/phase-10/prompt.md`, `tail -n 50 <file>` and `bash -n .claude/hooks/x.sh`.
- The path guard for Write is unchanged, so a Write in the caller's own territory is still allowed.

**bash 3.2.** No problems found. The new code uses `${t:i:n}`, `local IFS=$', \t'` with `set -f` on, 14-digit `[ -gt ]` comparisons (64-bit), and BSD `sed -E` with `{n}` intervals. The awk program still contains no single quote.

## Open findings

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major (finding 2 OPEN) | `guard-dangerous.sh:412-413` (wrappers matched only as bare names, so `viax` is never set for `/usr/bin/xargs`); `:415-424` with `:379` (a combined flag with a separate argument) | (1) `find .claude -name .last-test-run \| /usr/bin/xargs touch`. (2) `find .claude -name .last-test-run \| xargs -tn 1 touch`: `-tn` is not a known argument-taking option, so `1` becomes the command word. Both return ALLOW, because the raw marker and `.claude/` rules (:822, :830) need the literal text `.claude/.last-test-run` or `.claude/`. GNU `xargs --max-args 1 touch` behaves the same way. | The marker's modification time is refreshed, so `verify-tests-ran.sh:153-154` exits 0 for both Stop and SubagentStop with no test run. Report touches are still caught by `report_after`, but the marker is not. Each command still prompts, because `find` is not allow-listed, so this is a guard-floor bypass, not a silent one. | factory-engineer. Match wrappers on `${w##*/}`. Under xargs, fail closed: block when any remaining word, by its basename, is a file-writing command. Add self-tests for `/usr/bin/xargs touch` and `xargs -tn 1 touch`. |
| major (pre-existing, from step 1; I missed it in my first review) | `guard-dangerous.sh:412` (same cause) | `/usr/bin/env sed -i 's/a/b/' modules/core/X.scala`, `/usr/bin/nohup tail -f x.log`, `/usr/bin/time sed -i …` all return ALLOW. | Decision 4's sed -i rule and territory rules, and decision 7's watcher rule, can be dodged with a path-qualified wrapper. This is the same class as finding 1. | factory-engineer (the same basename fix closes it; add self-test cases) |
| major (pre-existing; disclosed by factory as B2) | `guard-dangerous.sh:503-516` (`touch -t` on a literal path in the agent's own territory is allowed); `verify-tests-ran.sh:109, 153, 315` (the comparisons use the changed file's modification time, which the agent controls) | domain-engineer edits `modules/core/src/main/scala/X.scala` and then runs `touch -t 200001010000 modules/core/src/main/scala/X.scala`. | Any earlier marker, and any earlier sbt report, now looks newer than the change. The agent stops COMPLETE with no test run after its change. This also defeats the new sbt-timestamp check. | human (scope), then factory-engineer. Options: block `touch -t/-d/-r/-A` and timestamp-preserving copies (`cp -p`, `rsync -t`/`-a`, `install -p`) onto relevant source, or stop relying on modification times (for example, use a content hash recorded by markTestRun). |

## New problem caused by the repair, with the B1 ruling

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major (introduced by the new allow rules) | `.claude/settings.json:29, 30, 32`; `guard-dangerous.sh:463-569` (no target rule for `sort -o`, `uniq IN OUT` or sed `w`); `:822, :830` (these raw rules need `HAS_OP`, which sort, uniq and sed never set) | Any subagent can run, with no prompt: `sort -o .claude/.last-test-run /dev/null`, `sort -o CLAUDE.md CLAUDE.md`, `sed -n 'w modules/core/X.scala' /dev/null`, `uniq /dev/null docs/agent-runs/phase-10/prompt.md`. Each passes the guard and matches an allow rule. | The first command refreshes the marker and silently defeats the Stop and SubagentStop gates. The others silently rewrite or truncate constitutional files, human-owned evidence, or another agent's territory. Before this repair, each of these commands raised a permission prompt. | Human decision on B1, then factory-engineer |

**B1 ruling: major. Settle it before ratification.**
- The quickest safe fix is option (b): remove `Bash(sort:*)`, `Bash(uniq:*)` and `Bash(sed -n:*)` from the allow list now.
- The lasting fix is option (a), in a later factory pass. Treat these as write targets: `sort -o`/`--output`, `uniq`'s second operand, and any sed `w`/`W` command or `s///w` flag. Add self-tests for them.
- `Bash(sbt -batch:*)` is a lower concern. sbt already runs project code during tests, so allowing any sbt task (`eval`, `runMain`) adds little exposure. Record it as an accepted risk.
- The other allow rules (`grep`, `head`, `tail -n`, `ls`, `wc`, `shasum`, `cut`) cannot write files.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Amendment 02, step 4: read-only re-check of findings 1, 2, 3, 4, 7, 9 after repair-01 (docs/agent-runs/amendment-02/prompt.md, decision 1 flow; human repair decision A and the attached orders)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Self-tests not executed (read-only fence). Every ruling was traced by hand against the installed hooks.
VALIDATION RESULTS: Read the installed guard-dangerous.sh (lines 1-939), verify-tests-ran.sh (full, 393 lines), settings.json (full), stop-hook-selftest.sh:1-140, the guard-selftest r2/r4/i-* case lines, code-reviewer.md frontmatter, the build.sbt markTestRun definition, and grep evidence for the wave-closer phrase (10 role files, old phrase gone) and the spec quote (docs/agents.md:139, factory-engineer.md:60). Findings 1, 3, 4, 7, 9: CLOSED. Finding 2: OPEN. One new major problem from the allow list (B1).
ASSUMPTIONS: (1) Claude Code sends agent_type in SubagentStop input; otherwise the code-reviewer exemption fails closed and the stall returns. (2) macOS /usr/bin/xargs uses BSD getopt, so `-tn 1` means -t with -n 1. (3) BSD sed opens `w` files when the script starts, even with empty input. (4) Claude Code checks prefix allow rules on each part of a compound command.
RISKS: The marker counts as fresh by modification time alone, so any unprompted write to it defeats both stop gates. Interpreter writes (python3 -c, perl -e) are still unchecked, as noted in the first review.
MANUAL ACTION REQUIRED: Human: (1) decide B1, the minimum being to drop sort, uniq and sed -n from the allow list; (2) decide the scope of B2 (backdating); (3) route the finding-2 respelling fix (basename matching of wrappers, xargs fail-closed) to factory-engineer; (4) in probe 1, confirm that agent_type arrives on SubagentStop.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: factory-engineer repair round 2 for finding 2 (both respellings plus path-qualified wrappers), plus B1 and B2 as the human decides; then a code-reviewer re-check of those items only; then human ratification, restart, and probes 1-9.
```

VERDICT: REQUEST_CHANGES

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major | .claude/hooks/guard-dangerous.sh:412-413, :379, :415-424 | `find .claude -name .last-test-run \| /usr/bin/xargs touch`; `… \| xargs -tn 1 touch` | Marker refreshed; Stop and SubagentStop pass with no test run | factory-engineer |
| major | .claude/hooks/guard-dangerous.sh:412 | `/usr/bin/env sed -i 's/a/b/' modules/core/X.scala`; `/usr/bin/nohup tail -f x` | sed -i and watcher rules dodged | factory-engineer |
| major | .claude/hooks/guard-dangerous.sh:503-516; .claude/hooks/verify-tests-ran.sh:109, 153, 315 | `touch -t 200001010000 modules/core/src/main/scala/X.scala` | Stops COMPLETE with no test run after the change | human, then factory-engineer |
| major (regression) | .claude/settings.json:29, 30, 32; .claude/hooks/guard-dangerous.sh:822, 830 | `sort -o .claude/.last-test-run /dev/null`; `sort -o CLAUDE.md CLAUDE.md`; `sed -n 'w modules/core/X.scala' /dev/null` | Silent defeat of the test gate; unprompted constitutional or cross-territory writes | human (B1), then factory-engineer |

Files referenced:
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/verify-tests-ran.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/stop-hook-selftest.sh
- /Users/brian/Developer/repos/scholarchain/.claude/settings.json
- /Users/brian/Developer/repos/scholarchain/.claude/agents/code-reviewer.md
- /Users/brian/Developer/repos/scholarchain/docs/agents.md
- /Users/brian/Developer/repos/scholarchain/CLAUDE.md
- /Users/brian/Developer/repos/scholarchain/build.sbt
