# Amendment 02: code-reviewer report (decision 10)

**Verdict: REQUEST_CHANGES.** Three major findings remain, each checked against the installed files:
1. **Respellings get past the new parser.** Simple rewrites of a command slip through the new Bash parser, which item 10 says must resist them.
2. **The new test evidence can be faked without running tests.** A subagent can make old sbt reports look fresh and stop COMPLETE with no test run after its change.
3. **The stop hook will stall code-reviewer.** CLAUDE.md now lets the read-only reviewer run alongside a source-changing agent, but the stop hook will refuse the reviewer's stop and it has no way to clear the block.

Everything else in the diff matches decisions 2 to 9. Floor invariants, ownership sync, the `.mcp.json` pins, the Stop and SubagentStop logic, bash 3.2 compatibility and the wording fixes all check out. The self-tests were not run; everything below comes from reading the files and tracing each case by hand.

## What checks out

**Floor invariants**
- Every pre-existing raw rule in `guard-dangerous.sh` (lines 565-692) is unchanged in the diff: Terraform, force push, history rewrite, recursive rm, disk writes, secret reads, AWS, database and HTTP DELETE.
- The only change to the old rules (hunk at line 781 of the diff) swaps the `ops` regex for the new `HAS_OP` flag and rewords one ask message. That is the false-positive fix decision 4 orders.
- `format-scala.sh` and its PostToolUse entry are untouched. The secret deny rules are unchanged. `settings.json` gains only the two 3b deny rules.
- The test-stop invariant changes only as decision 3 ratifies.

**Stop hook (`.claude/hooks/verify-tests-ran.sh`)**
- Stop (main session) still needs the full marker:
  - the coverage block runs only when the event is SubagentStop (line 181);
  - a missing event name is treated as Stop (line 62).
- On SubagentStop:
  - **No reports:** blocks (line 226).
  - **Stale reports:** block (line 224 uses `find -newer` against the project's newest changed file).
  - **Failing, errored or unreadable reports:** block (`report_passes`, lines 170-177).
  - **Build files:** `build.sbt`, `project/**`, `.scalafmt.conf`, `.jvmopts` and `.sbtopts` map to FULL and always need the full marker (line 131).
  - **Unknown module:** needs the full marker.
  - **core:** needs fresh reports in both `modules/core/target/jvm-3/test-reports` and `modules/core/target/jvm-2.13/test-reports` (line 147). Both folders exist, and sbt's reports there carry `failures`, `errors` and `timestamp` attributes.
- The BLOCKED-ON/FAILED escape, the deletion skip, the target/ skip and the missing-build.sbt rule are as before.

**Expected-allowed cases from decision 9**
I traced each one through the normalizer, the target checker and the raw rules. None is blocked or asked:
- `sbt -batch check > /private/tmp/claude-…/check.log 2>&1`: `2>&1` is treated as descriptor duplication, and the log path is in the scratch area.
- `grep -n x docs/agent-runs/phase-10/prompt.md`: `HAS_OP` stays 0 and there is no redirection.
- A Write inside the caller's own territory.
- `tail -n 50 <file>`
- `bash -n .claude/hooks/x.sh`

**Block messages.** Every new block passes a "what to do instead" line (guard lines 386-408, 501-558, 740, 783, 790, 809). The stop hook names each uncovered project and the exact `sbt -batch "<id>/testOnly …"` command.

**bash 3.2.** No associative arrays, `mapfile` or `${x,,}`. The `[[ =~ ]]` patterns come from variables. The control-character masks avoid `\001` and `\177`, and `tr` maps the 11 masks one to one. The awk program contains no single quote.

**Ownership in sync.**
- `docs/agents.md` rows: `config/**` → infra-engineer, `releases/**` → deploy-engineer, `.claude/hook-tests/**` → factory-engineer.
- `owner_of` (guard lines 109, 123, 124) agrees.
- The validator checks all three (validate-agent-files.sh, `OWNERSHIP_SAMPLES`).
- The role files agree: deploy-engineer.md:14, infra-engineer.md:16 and law 7, factory-engineer.md.
- No agent was added, so the roster is unchanged.

**`.mcp.json`.** All four pins match the work order character for character (lines 10, 20, 31, 35). The env blocks are unchanged. The JSON is valid by inspection (no trailing commas, braces balanced).

**Decision 2.** All 14 rules are present in CLAUDE.md:59-74, placed after "Definition of done".

**Decision 3c.** Definition of done items 1-2 replaced, list renumbered to 6 (CLAUDE.md:52-57).

**Amendment-01 wording fixes**
- #4: build-engineer.md:24
- #5: build-engineer.md:37
- #6: dependency-updater.md:3
- #8: CLAUDE.md:44
- #10: CLAUDE.md:46
- #7 is listed under proposed later (factory report, section 8 item 1).

**Orchestrator observation.** `… > $S/agent-report-factory.md` being blocked with "not a literal path" is correct under decision 4 (guard lines 504-506). It is not a defect.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| 1 | major | `.claude/hooks/guard-dangerous.sh:250-255, 297-298` (`$` before a quote is copied literally), `:241` (`-c` code detection), `:328` and `:357-374` (wrapper stripping) | (a) rag-engineer: `$'sed' -i 's/a/b/' modules/core/X.scala` → ALLOW. The normalizer emits `$sed`, so the command word is `$sed`, not `sed`. The same works for `$'tail' -f README.md` and `$'watch' ls`. (b) `bash -c -- 'sed -i s/a/b/ modules/core/x'` → ALLOW. When `--` starts, the previous word is `-c`, so `--` is taken as the code word. The real code string is then masked as data. (c) `env -S 'sed -i s/a/b/ modules/core/x'` → ALLOW. `wrapper_optarg env -S` discards the string that env runs. (d) `exec -a x sed -i s/a/b/ modules/core/x` → ALLOW. `-a` is not known to take an argument, so `x` becomes the command word. | Decision 4 (sed -i, ed/ex, territory) and decision 7 (watchers) can be sidestepped by quoting and `bash -c` respellings, which item 10 says must not work. No self-test case covers these. The factory report lists `env -S` as a known gap but not the others. | factory-engineer. Suggested fix: treat `$'…'` and `$"…"` as quotes (drop the `$`; if a command word came from `$'…'` and holds a backslash escape, treat it as non-literal and block). After a shell's `-c`, skip `--` and option words before taking the code word. Unwrap the `env -S`/`--split-string` argument as code. Add `exec -a`. Add self-test cases for each. |
| 2 | major | `.claude/hooks/guard-dangerous.sh:357` (xargs stripped), `:413-461` (writer targets taken only from literal operands), `:739` (raw report rule needs the text `test-reports`); `.claude/hooks/verify-tests-ran.sh:224` (freshness is file mtime only) | domain-engineer edits `modules/core/src/main/scala/X.scala`, runs no tests, then runs `find modules/core -name 'TEST-*.xml' \| xargs touch`. Guard: xargs is stripped, `touch` has no operand so no target is checked, `HAS_OP=1` but the command never says `test-reports`, so the result is ALLOW. Every existing passing report in jvm-3 and jvm-2.13 is now newer than the change, and SubagentStop exits 0 ("covered"). The same form reaches the full marker for Stop: `find .claude -name .last-test-run \| xargs touch` contains neither `.claude/.last-test-run` nor `.claude/`, so it is allowed (this gap existed before, but is now in scope). It also breaks territory: `echo modules/core/A.scala \| xargs touch` (rag-engineer), or `echo modules/core/a \| xargs cp /tmp/claude-1/a` (one literal operand, so no target is checked) → ALLOW. | There is a path to COMPLETE with no test run after the change, which item 10 forbids. Decision 3b ("every shell command that writes there") and decision 4 (territory) are both evaded. | factory-engineer. Suggested fix: (a) if a writer command (`touch`, `tee`, `cp`, `mv`, `install`, `ln`, `rsync`) runs under `xargs`, or with fewer operands than it needs, add a non-literal target so it blocks with "use a literal path". (b) Defense in depth in the stop hook: also require the `<testsuite timestamp="…">` attribute, which sbt writes and which `touch` cannot change (seen at `modules/core/target/jvm-2.13/test-reports/TEST-org.scholarchain.core.PairOrderingSuite.xml:2`), to be later than the changed file's mtime. Note the attribute is local time with no zone. (c) Add self-test cases. |
| 3 | major | `CLAUDE.md:62` (read-only code-reviewer may run alongside a source-changing agent) against `.claude/hooks/verify-tests-ran.sh:79-113, 181-251, 288` (changed files come from the whole repository's git status; no exemption by agent) | The orchestrator runs code-reviewer alongside service-engineer, which has edited `modules/services/src/main/scala/A.scala` and not yet run tests. code-reviewer finishes with `STATUS: COMPLETE` and gets exit 2: "Not covered: services". It has no Bash, Write or Edit, so it cannot clear the block. `stop_hook_active` is honored only when `build.sbt` is missing (line 268). | The reviewer loops at stop (a stall, the failure this amendment exists to remove), or misreports `BLOCKED-ON` to escape. Decisions 2 and 3 conflict as installed. | human (this touches the floor invariant's scope), then factory-engineer. Options: on SubagentStop, exempt agent types whose tool fence has no Write, Edit or Bash (code-reviewer; the validator already enforces that fence), or remove the parallel allowance. |
| 4 | minor | `.claude/hooks/guard-dangerous.sh:591-597, 677-679` (`ask` exits 0) run before the command-position analysis at `:694-727` | Main session: `git commit -m x; sed -i s/a/b/ CLAUDE.md` or `git commit -m x; tail -f log` → ASK with the git-commit reason instead of BLOCK. The same happens after any AWS-mutation ask (`aws ec2 start-instances …; watch ls`). | The new blocks turn into a human prompt with a misleading reason, which also stalls an unattended run. | factory-engineer. Run the new analysis before any `ask`, or hold asks until all block checks are done. |
| 5 | minor | `.claude/hook-tests/stop-hook-selftest.sh:50, 190-191` (s25); `:187-189` (s24) | s25: the fixture's `.gitignore` contains `target/`, so `git status` never lists `modules/services/target/scala-3/Generated.scala`. The hook's target/ skip (verify-tests-ran.sh:94) is never exercised, and the case passes with the skip removed. s24: the deleted file doesn't exist, so the `[ -f ]` check at :106 drops it anyway, and the case passes with the D skip (:85) removed. | Item 10: a test that passes with its rule removed is a finding. Both rules are carried over unchanged, so the risk is low. | factory-engineer. For s25, don't ignore `target/` in that fixture (or force-add the file). For s24, document that the case is not discriminating. |
| 6 | minor | `.claude/agents/build-engineer.md:36` | "Run `sbt Test/compile`, then `sbt check`, and observe the output. … If you changed only other files, run the changed projects' targeted tests … and run the full `sbt check` when the work order makes you the wave closer." | The first sentence makes `sbt check` unconditional, and the third makes it conditional. The rule contradicts itself. | factory-engineer |
| 7 | minor | `CLAUDE.md:53` against role files: `domain-engineer.md:30`, `hadoop-engineer.md:22`, `entity-resolution-engineer.md:22`, `spark-engineer.md:23`, `storage-engineer.md:22`, `rag-engineer.md:24`, `service-engineer.md:22`, `test-engineer.md:30`, `dataset-engineer.md:29` | The work order names no wave closer. CLAUDE.md says the closer is then, by default, the last source-changing agent, but the role files trigger the full check only "when the work order makes you the wave closer". | No agent runs `sbt check` before review. The orchestrator's Stop then blocks, or the review happens without the full gate. | factory-engineer (role files: add "or when the orchestrator tells you that you are the last source-changing agent of the wave"), or the human requires every work order to name the closer |
| 8 | minor | `docs/agents.md:22-23, 30, 38, 86-87, 94, 105` (primary gates and lifecycle gates still list `sbt check` per agent) | Definition of done item 4 ("the owning agent's primary gate … passes") combined with "Contract tests, `sbt check`" for domain-engineer | Reads as if every domain or service agent must run the full gate, which contradicts decision 3d. | factory-engineer (clarify that `sbt check` there is the wave closer's gate) |
| 9 | minor | `.claude/settings.json:16-21` (allow list has only exact `sbt check`, `sbt test`, and similar) | `sbt -batch "core/testOnly X" "core2_13/testOnly X"` (now required by CLAUDE.md:52), `sbt -batch check > <scratch>/check.log 2>&1` (decision 9), and `grep`/`cat`/`find` (probe 6) | Each prompts for permission unless the human's local settings allow it. That stalls unattended runs. The factory flagged only the grep case (proposed later #8). | human (outside decision scope), then factory-engineer |
| 10 | minor | `.claude/hooks/guard-dangerous.sh:315, 319` | `gsed --in-pl s/a/b/ modules/core/x` and `gtail --fo log` | GNU tools accept unambiguous abbreviations of long options, and the regexes anchor on the full `--in-place`/`--follow`. macOS `/usr/bin` sed and tail have no long options, so only the g-prefixed GNU tools are affected. | factory-engineer |
| 11 | advisory | `.claude/hooks/guard-dangerous.sh:511-516` vs `:526` | The test-report and secret checks look at the target as written (`t`), not the resolved absolute path. | Reachable only with a persisted cwd inside a report or secret folder. Subagent cwd resets between calls, and the main session cannot shell-write in the repository at all. | factory-engineer |
| 12 | advisory | decision 7 scope | `sbt ~test`, `aws logs tail --follow`, `docker logs -f`, `journalctl -f`, `sleep infinity` | Never-ending commands that the guard does not block (outside decision 7's list). | human (proposed later) |
| 13 | advisory | `.claude/hooks/verify-tests-ran.sh:139` (decision 3a mapping) | A change in `tests/integration/**` is "covered" by any `contractTests` report, although that sbt project may not compile it. | Decision 3a ordered this mapping; recorded for human awareness. | human |
| 14 | advisory | `.claude/hook-tests/guard-selftest.sh:114-116` | The b-touch, b-redirect and b-cp cases pass with either of the two report rules removed (guard :511 and :739); none pins `check_write_target`'s report check on its own. There are no cases for `>&file`, `/dev/stdout`, `install -d` or single-operand `ln`. | Coverage gaps; the outcomes are correct today. | factory-engineer |
| 15 | advisory | normalizer (factory risk 3) | A heredoc body is parsed as code, so `cat > /private/tmp/claude-…/r.md <<'EOF'` with `->` in the body can be blocked. | Fail-safe, but the orchestrator should write scratch reports with the Write tool (allowed for `/private/tmp/claude-*`). | orchestrator practice |

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Amendment 02, step 2 (docs/agent-runs/amendment-02/prompt.md, decision 10): read-only review of the full constitutional diff against decisions 2-9.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Self-tests not executed (forbidden by the work order). Each guard and stop-hook case was traced by hand against the installed hook code.
VALIDATION RESULTS: Read in full: guard-dangerous.sh (858 lines), verify-tests-ran.sh (289), both self-tests, settings.json, .mcp.json, CLAUDE.md:38-74, the full diff (validator, agents.md, CLAUDE.md and role-file hunks), amendment-01 review-report.md, agent-report-factory.md. Confirmed core report folders jvm-3 and jvm-2.13 exist and that sbt writes failures, errors and timestamp attributes. Raw guard rules unchanged; .mcp.json pins exact; ownership in sync; wording fixes #4, #5, #6, #8, #10 applied.
ASSUMPTIONS: (1) macOS /usr/bin/env supports -S (FreeBSD-derived). (2) bash accepts `--` between -c and the command string. (3) SubagentStop fires for code-reviewer with its final report as last_assistant_message. (4) The awk normalizer runs as written under the macOS awk; not executed.
RISKS: The guard stays a speed bump: interpreters, tar/unzip, dd of= and mkdir remain unchecked (factory proposed-later #3). Findings 1-3 should be fixed before the human's probes 1-9 are trusted.
MANUAL ACTION REQUIRED: Human: route findings 1-3 to the factory-engineer repair round (finding 3 needs a human decision on scope first); decide minor findings 4-10 and advisories 11-15.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 3, factory-engineer repair pass for findings 1-3 (repair-01.md), then step 4, code-reviewer re-check of those findings only (review-report-02.md).
```

VERDICT: REQUEST_CHANGES

Files referenced:
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/verify-tests-ran.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/stop-hook-selftest.sh
- /Users/brian/Developer/repos/scholarchain/.claude/settings.json
- /Users/brian/Developer/repos/scholarchain/CLAUDE.md
- /Users/brian/Developer/repos/scholarchain/docs/agents.md
- /Users/brian/Developer/repos/scholarchain/.claude/agents/build-engineer.md
- /Users/brian/Developer/repos/scholarchain/modules/core/target/jvm-2.13/test-reports/TEST-org.scholarchain.core.PairOrderingSuite.xml
