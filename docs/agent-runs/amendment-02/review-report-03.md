# Amendment 02: final re-check after repair-02 (review-report-03)

**VERDICT: REQUEST_CHANGES.** Every literal input you ordered fixed in repair-02 is now blocked, and the cheap fixes are correct. Four major problems remain:
- **Shell variables still get past the new rules.** A variable or `$1` can carry the option or the sed script, which defeats the new sort, sed and touch rules (row A).
- **The recursive-delete check can now be dodged with a path ending in `/rm`.** Repair-02 changed this floor rule; the older trick using a bare `rm` word already worked (row C).
- **Older gaps I found this round:**
  - two allow-listed commands can overwrite any file with no prompt: `git diff/log/show --output=` and `python3 -m json.tool IN OUT` (row B);
  - the macOS tools `arch` and `script` run other commands but the guard does not treat them as wrappers (row D).

There is no further repair round, so all of these are listed as accepted risks at the end.

I ran nothing. Every ruling comes from tracing the commands by hand through the installed `/Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh` (1271 lines). The stop hook is unchanged since review-02: the r1→r2 diff touches only the guard, and the line positions in `verify-tests-ran.sh` match review-02.

## Rulings on the repaired items

**(a) Review-02 rows 1 and 2: CLOSED for every input in that review.**
- **xargs inputs:**
  - `find .claude -name .last-test-run | /usr/bin/xargs touch` is blocked. `/usr/bin/xargs` is recognised by its basename (:578, :587), and touch is not on the xargs list (:868-873).
  - `xargs -tn 1 touch` is blocked. The getopt reading at :415-422 treats `n` as the last letter of the cluster, so `1` is taken as its argument.
  - `xargs --max-args 1 touch` is blocked.
- **Path-qualified wrappers:** `/usr/bin/env sed -i …`, `/usr/bin/nohup tail -f …` and `/usr/bin/time sed -i …` are blocked.
- **Fail-closed design:** when the guard misreads an xargs option, the misread word becomes the command, and that word is not on the xargs list, so it blocks (for example `xargs --max-a 1 touch`).
- **Self-tests:**
  - r5-abs-xargs-touch-marker and r5-abs-env-sed-i would ALLOW without the basename change.
  - r5-xargs-tn-touch-marker would ALLOW without the xargs list.
  - The getopt reading is pinned by r5-xargs-tn-grep-allowed.
  - The re-check after a wrapper is pinned by r5-wrapper-unknown-optarg-scan.
  - r5-time-o-marker would ALLOW without the `time -o` rule.
- **Residual (minor):**
  - The re-check after a wrapper (:882-892) does not keep the xargs context. A GNU long-option abbreviation whose argument looks like an allowed name gets through, for example `… | gxargs --pr cat touch` or `gxargs --a /private/tmp/claude-…/cat touch`.
  - GNU xargs is not installed in /opt/homebrew/bin or /usr/local/bin, so this is theoretical here. It belongs to recorded class #10.
  - Unlisted wrappers are row D.

**(b) B1: CLOSED for silent writes; OPEN for variable respellings.**
- **Allow list:** `settings.json:5-50` no longer contains sort, uniq or `sed -n`.
- **Literal forms all block:**
  - `sort -o` in its `-ro`, `-oF`, `--output[=]` and `--compress-program` forms;
  - uniq's second operand;
  - the sed `w`, `W` and `e` commands and the `s///w` and `s///e` flags, inside `-e` scripts, clusters, labels and blocks;
  - `sed -f`.
- **Self-tests:** every r6 BLOCK case would ALLOW with its rule removed. r6-uniq-f-arg-allowed pins the `-f` skip.
- **Open:** a variable or positional parameter gets past both the sort rule and sed's variable check (row A).

**(c) touch time options: CLOSED for literal options.**
- These are blocked anywhere in the argument list, including GNU's options-after-operands order: `-t`, `-d`, `-r`, `-A`, any cluster containing them, `--d*` and `--r*` (:743-750).
- **Self-tests:** r7-touch-t-own-territory and r7-touch-t-scratch would ALLOW without the rule.
- **Open:** `-$X` gets past it (row A).
- **Correction to the recorded risk:** `cp -p`, `rsync -t` and `install -p` copy the source file's old time. They can bring back old content with its old time, but they cannot give new content an old time. The real remaining backdating routes are:
  - interpreters (`perl -e utime`, `python3 -c os.utime`);
  - `/usr/bin/SetFile -m '01/01/2000 00:00' <file>` (SetFile is present on this machine);
  - row A.

**Fix 5: CLOSED.**
- s24 fails without the deletion skip at `verify-tests-ran.sh:121`. Its fixture is a staged deletion whose file still exists and is hidden by `.git/info/exclude`.
- s25 fails without the target/ skip at `:130`. Its fixture is a force-added file under target/.

**Fix 6: CLOSED (minor residual).** `build-engineer.md:36` now gives one rule. It names only `build.sbt`, `project/**` and "the formatter config", while `docs/agents.md:19` and the stop hook (`:211`) also require the full marker for `.jvmopts` and `.sbtopts`.

**Fix 8: CLOSED.** The gate wording is at `docs/agents.md:19`. Rows :24, :25, :32, :40 and lifecycle rows :88, :89, :96 match `CLAUDE.md:53`.

**Fix 12 (part): CLOSED.**
- These are blocked:
  - sbt `~` tasks, also inside quoted multi-command arguments;
  - `docker`/`podman`/`kubectl` `logs -f`/`--follow`, but only after the word `logs`;
  - `journalctl -f`/`--follow`;
  - `sleep inf`/`infinity` in any letter case.
- The r8 BLOCK cases would ALLOW with their rules removed. `docker compose -f … logs --tail 50` and `journalctl -n 50` stay allowed.

## Regression checks (still hold)

- **Decision 9 cases, traced with no block or ask:**
  - `sbt -batch check > <scratch>/check.log 2>&1` in the background;
  - `grep -n x docs/agent-runs/phase-10/prompt.md`;
  - a Write in the caller's own territory;
  - `tail -n 50 <file>`;
  - `bash -n .claude/hooks/x.sh`.
- **Other expected-allowed commands, traced with no block** (except `sbt -batch`, sort, uniq and sed now prompt because they are off the allow list):
  - `sed -n '1,20p' f`, `sed -n '$p' f`, `sort f`, `uniq f`, `sleep 5`, `sbt -batch "core/testOnly X"`;
  - `timeout 600 sbt -batch …`;
  - `env JAVA_OPTS=… sbt …`;
  - git read commands;
  - scratchpad writes under /private/tmp/claude-*.
- **Probes 3-8:**
  - `tail -f README.md` is blocked.
  - rag-engineer `echo x > modules/core/probe.txt` is blocked (owned by domain-engineer).
  - `sed -i '' …` is blocked.
  - `grep -n Standing …` is allowed with no ask.
  - The stop hook is unchanged, so probe 7 behaves as before.
  - `echo 'terraform destroy'` is blocked.
- **Earlier findings stay closed:** 1 (code at :621-636 is unchanged), 3 (stop hook unchanged), 4 (every Bash-path ask is deferred: :1001, :1084, :1159), 7 and 9 (as amended by decision b). Main-session Stop is unchanged.
- **bash 3.2:** no problems found.
  - `${s:$i:1}` slicing, regexes kept in variables, and character-class `case` patterns are all fine.
  - The awk program has no single quote, and BSD awk accepts `[ \t\/]`.
  - `sed_skip` changes the caller's `i` through dynamic scope as intended. It declares its own `d` as local.

## Open findings

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| A. major (repair-02 rules sidestepped; prompted, not silent) | guard-dangerous.sh:764-788 (an option word starting with `$` falls through every sort case); :544, :550-551 (sed variable regex misses `$1`-`$9`; the quote test searches the whole command); :746-751 (`-$X` passes the touch check) | `o=-o; sort "$o" .claude/.last-test-run /dev/null`<br>`p='w .claude/.last-test-run'; echo '$p'; sed -n "$p" /dev/null`<br>`set -- 'w .claude/.last-test-ru'; sed -n "$1"n /dev/null`<br>`X=r; touch -$X /private/tmp/claude-501/s/old modules/core/src/main/scala/X.scala` | All return ALLOW. The first three truncate or refresh the marker, so Stop and SubagentStop pass with no test run. The last backdates a source file. The same class predates repair-02 for other option rules, for example `X=-f; tail "$X" x.log`. | factory-engineer |
| B. major (pre-existing, found now; silent) | settings.json:10-12, :33; guard-dangerous.sh:840-849 (git sets HAS_OP only for checkout/restore/apply/am); :1145, :1153 (these rules need HAS_OP or `>`) | `git diff --output=.claude/.last-test-run`<br>`git log --output=docs/agent-runs/amendment-02/prompt.md -1`<br>`python3 -m json.tool .mcp.json CLAUDE.md` | Allow-listed, so they run with no prompt. They refresh the marker (defeating both stop gates), or overwrite constitutional files and human evidence. The amendment-02 evidence directory is untracked, so git cannot restore it. This is the same class as B1. | factory-engineer (guard: treat `--output` as a write target; settings: drop `python3 -m json.tool:*` or pin it to the one-operand form) |
| C. major (floor rule; pre-existing, extended by repair-02) | guard-dangerous.sh:1014 (the greedy `^.*…rm` keeps only the text after the last `rm`; repair-02 added `/` as a place to split) | `rm -fr ~/Developer x/rm target` and `rm -rf modules x/rm target` are ALLOW in r2 (blocked in r1). `rm -rf modules rm target` was already ALLOW in r1. | Broad recursive delete of the home directory or the source tree with only a prompt. The settings deny rules match only the exact prefixes `rm -rf /` and `rm -rf ~`. | factory-engineer (check every operand after the first `rm`) |
| D. major (pre-existing; same class as review-02 row 2) | guard-dangerous.sh:428-433 (`arch` and `script` are not wrappers; both exist in /usr/bin) | `arch touch .claude/.last-test-run`<br>`script -q /dev/null touch .claude/.last-test-run`<br>`arch sed -i s/a/b/ modules/core/X.scala` | ALLOW with a prompt. The marker is refreshed, and the sed -i and territory rules are dodged. These tools were outside the human's list of wrappers. | factory-engineer |
| minor | guard-dangerous.sh:438 with :630-636; :868-873; :389 and :701; :241 and :1049 | Allowed workflows that are now blocked or misread:<br>`sort -t$'\t' -k2 f`: sort now counts as a checked command, so `$'\t'` is refused.<br>`find .claude -name '*.sh' \| xargs bash -n`<br>`sbt -ivy ~/.ivy2 check`<br>`curl -s URL \| grep -c bash` is blocked as remote code.<br>`… \| grep bash` makes quoted text be read as code. | False positives on rare but legitimate commands. Every message says what to do instead. | factory-engineer |
| minor | .claude/agents/build-engineer.md:36 | build-engineer changes only `.jvmopts`. | It follows the targeted-test path, and its stop is refused (the full marker is required). It costs one retry. | factory-engineer |
| minor (class #10) | guard-dangerous.sh:882-892, :399, :715 | `gxargs --pr cat touch`; `journalctl --foll` | GNU abbreviations get through. The GNU tools are not installed on this machine. | factory-engineer |

## Accepted-risk list for the human gate

1. **Row A:** a shell variable or `$1` carrying an option or sed script gets past the sort -o, sed w/e, touch-time and (older) tail -f, sed -i and perl -i rules. It still prompts. Mitigation: the wave-end full `sbt check`.
2. **Row B:** `git diff`/`log`/`show --output=FILE` and `python3 -m json.tool IN OUT` are allow-listed writers. They are silent, so a prompt does not catch them. This is the most serious open item; it is a two-line settings or guard change if you choose to do it outside this amendment.
3. **Row C:** the recursive-delete check can be dodged with a later `rm` word or `…/rm` path. It still prompts.
4. **Row D:** macOS command runners outside the wrapper list (`arch`, `script`; probably others such as `taskpolicy` and `sandbox-exec`, which I did not check).
5. **Backdating** through interpreters and `SetFile -m`, under decision (c). The `cp -p`/`rsync`/`install -p` routes are weaker than recorded.
6. **Recorded under your orders:** #10, #11, #13, #14, #15, amendment-01 #7, `Bash(sbt -batch:*)`, the minor rows above, and the factory's "proposed later" items 1-5.
7. **Probe 1 assumption:** SubagentStop must carry `agent_type`. If it does not, the stop hook fails closed and the code-reviewer stall returns.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Amendment 02, final re-check after repair-02 (docs/agent-runs/amendment-02/prompt.md; human decisions a, b, c; fixes 5, 6, 8, 12-part)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Self-tests not executed (read-only fence). All rulings traced by hand.
VALIDATION RESULTS: Read guard-dangerous.sh 1-1271, the r1->r2 diff (guard only), settings.json, stop-hook-selftest.sh (full), the guard-selftest r5-r8/i-/r2 cases, verify-tests-ran.sh 80-214, build-engineer.md 25-44, docs/agents.md 17-42, CLAUDE.md:41,53, prompt.md Gate section. (a) CLOSED; (b) settings CLOSED, guard OPEN (row A); (c) CLOSED for literal options, OPEN (row A); fixes 5, 8, 12-part CLOSED; fix 6 CLOSED with a minor residual. New or pre-existing majors: rows B, C, D.
ASSUMPTIONS: (1) git's --output=<file> opens and truncates the file even when the diff is empty. (2) BSD sed opens w files when it compiles the script, even with /dev/null input. (3) Claude Code matches Bash(git diff:*) on prefix, so --output does not prompt. (4) macOS arch(1) and script(1) run a following command with no options required.
RISKS: Rows A-D, as listed above.
MANUAL ACTION REQUIRED: Human: accept or route rows A-D. At minimum, consider row B (silent) before ratification. Run both self-tests after restart.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human ratification, with rows A-D accepted or routed to factory-engineer; then restart and probes 1-9.
```

VERDICT: REQUEST_CHANGES

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| major | .claude/hooks/guard-dangerous.sh:764-788, :544, :550-551, :746-751 | `o=-o; sort "$o" .claude/.last-test-run /dev/null`; `p='w …'; echo '$p'; sed -n "$p" /dev/null`; `X=r; touch -$X <scratch-old> <src>` | Marker refreshed or source backdated (prompted) | factory-engineer |
| major | .claude/settings.json:10-12, :33; .claude/hooks/guard-dangerous.sh:840-849 | `git diff --output=.claude/.last-test-run`; `python3 -m json.tool .mcp.json CLAUDE.md` | Silent marker refresh, constitutional overwrite, or loss of evidence | factory-engineer |
| major | .claude/hooks/guard-dangerous.sh:1014 | `rm -fr ~/Developer x/rm target` | Recursive-delete floor dodged (prompted) | factory-engineer |
| major | .claude/hooks/guard-dangerous.sh:428-433 | `arch touch .claude/.last-test-run`; `script -q /dev/null touch …` | Marker refreshed, wrapper rules dodged (prompted) | factory-engineer |
| minor | .claude/hooks/guard-dangerous.sh:438/:630, :868, :389, :241, :1049 | `sort -t$'\t' …`; `… \| xargs bash -n`; `sbt -ivy ~/.ivy2`; `curl … \| grep bash` | False positives | factory-engineer |
| minor | .claude/agents/build-engineer.md:36 | change only `.jvmopts` | Stop refused once (needs the full marker) | factory-engineer |
| minor | .claude/hooks/guard-dangerous.sh:882-892 | `gxargs --pr cat touch` (GNU tools not installed) | Theoretical bypass of the xargs list | factory-engineer |

Files referenced:
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/verify-tests-ran.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/stop-hook-selftest.sh
- /Users/brian/Developer/repos/scholarchain/.claude/settings.json
- /Users/brian/Developer/repos/scholarchain/.claude/agents/build-engineer.md
- /Users/brian/Developer/repos/scholarchain/docs/agents.md
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/repair-02-guard.diff
