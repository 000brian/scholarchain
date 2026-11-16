# Review report 04: Amendment 02, re-check of finding B after repair-03

VERDICT (finding B): REQUEST_CHANGES

## Summary

- **The failing inputs from review-03 are closed.**
  - `git diff/log/show --output=FILE`, `--output FILE`, the abbreviations, `git format-patch` and `git archive -o` all BLOCK, with the human's message, for subagents and the main session.
  - `Bash(python3 -m json.tool:*)` is gone from `.claude/settings.json`, so that command now prompts.
- **The respellings named in the rule are closed:** path-qualified git, the dashed `git-format-patch`, wrappers, `-C`/`-c`, `-c alias.x='…'`, quoting, `bash -c`, and `;` chains.
- **The self-tests are correct.**
  - Every r9 case traces to its expected result against the installed guard.
  - Each BLOCK case would get ALLOW against the r2 git branch (r2:840-849, which only set HAS_OP for checkout/restore/apply/am). So each case fails if the rule is removed.
  - Plain `git diff`, `git log` and `git show` still ALLOW with no ask, for the main session and for subagents.
- **Two problems remain**, both in the class the human named as the core of B (an allow-listed command that silently writes a file the guard does not check):
  - **F1:** shell brace expansion is a respelling of `--output` that the guard does not see.
  - **F2:** `Bash(bash -n:*)` lets any command run with no prompt.
- **Because of these two major findings, B is OPEN.** Both fixes are small, but the human has said there is no further repair round. So the human either accepts F1 and F2 as recorded risks or authorizes one more targeted change.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| F1 | major | .claude/hooks/guard-dangerous.sh:866 (the git word patterns); allowed by .claude/settings.json:10-12 | `git diff --outpu{t,t}=.claude/.last-test-run` (also `git log --o{u,u}tput=CLAUDE.md`, `git show -{-,-}output=docs/agent-runs/amendment-02/prompt.md`) | The guard sees the word `--outpu{t,t}=…`. It matches none of `--outpu=*`, `*--output*` or `--*o*`. HAS_OP stays 0, so the marker rule (:1179) and the constitutional rule (:1187) do not fire, and the result is ALLOW. bash and zsh brace-expand the word to `--output=… --output=…`, and git opens and truncates the file. This is the same silent arbitrary-file overwrite as review-03 row B, under the allow-listed `git diff:*`/`git log:*`/`git show:*` prefixes. No r9 self-test covers braces. | factory-engineer |
| F2 | major | .claude/settings.json:30 `"Bash(bash -n:*)"` | `bash -n -i -c 'python3 -c "import os; os.utime(\".claude/.last-test-run\")"'` (or any command after `-i -c`) | The bash manual says `-n` "is ignored by interactive shells", and `-i` makes a `-c` shell interactive. So the code runs, and the allow prefix `bash -n` approves it with no prompt. The guard checks only redirections and the commands it knows inside the `-c` code. Unchecked writers such as python3, awk, curl -o or node run silently. This turns the accepted "interpreters prompt" risk (backdating routes, row A) into silent runs: in effect an allow-all that only the guard limits. | factory-engineer |
| F3 | minor | guard-dangerous.sh:861 (the loop starts after assignments are removed at :590-593) | `GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=alias.d GIT_CONFIG_VALUE_0='diff --output=X' git d`; `V='diff --output=X' git --config-env=alias.d=V d` | This is the environment-variable twin of the `-c alias` respelling that repair-03 claims to cover. Assignment words are removed before the git loop, so the result is ALLOW. It prompts, because it is off the allow-list prefix, so it is not silent. It is close to recorded risk A. | factory-engineer |
| F4 | minor | guard-dangerous.sh:861-866 | `git diff ${x:--}-output=X` (x unset) | A parameter expansion builds `--output` and the guard returns ALLOW. This is class A (variable carries an option). The human accepted A on the premise that it prompts. That premise holds here only if Claude Code refuses to prefix-match a command containing `${…}`, which I cannot confirm from the repository. | factory-engineer |
| F5 | minor | guard-dangerous.sh:866, :869-874 | Main session: `git commit -m "drop --output flag"`, `git log --grep=format-patch`, `git ls-files -co -- docs/archive`; after a wrapper, `timeout 9 grep -e git -e format-patch docs/` (the rescan reaches the `git` word) | New fail-closed false positives. The commit case goes from ASK to BLOCK, but the human commits by hand, and repair-03 discloses this. None of them breaks the normal read-only git workflow. | factory-engineer (accept or narrow) |

## Rule-by-rule check for B

1. **The review-03 failing inputs now block or prompt.**
   - Git: CLOSED (BLOCK, guard :864-874, message "read-only git only; print to stdout").
   - python3: CLOSED. No allow rule is left (settings.json:5-50), and the only remaining python3 use is the hook-internal fallback in validate-agent-files.sh:83.
2. **No allow-listed command silently writes an unchecked file.** OPEN: F1 and F2. I scanned the whole allow list again:
   - **`git status`, `ls-files`, `rev-parse`, `blame`:** no option writes a file.
   - **`grep`, `head`, `tail -n`, `ls`, `wc`, `shasum`, `cut`, `shellcheck`:** no output-file option.
   - **`jq empty`:** jq cannot write files.
   - **`terraform fmt -check`:** `-check` turns off `-write`.
   - **`terraform validate`, `terraform version`, `aws sts get-caller-identity`:** for aws, `--output` means the output format, not a file.
   - **`validate-agent-files.sh`:** an exact rule; it writes only its own mktemp file.
   - **`sbt check`/`fmt`/`test`:** exact rules that write by design.
   - **`sbt -batch:*`:** a silent writer, but recorded under item 6 as an accepted risk.
   - **`bash -n:*`:** NOT clean (F2).
3. **Trivial respellings do not sidestep the rule.**
   - CLOSED for path-qualified git, wrappers (env, timeout, command, exec, sudo, xargs), `-C`/`-c`, `-c alias`, abbreviations, quote and backslash splitting, `bash -c`/`eval`/pipe-to-shell, and the dashed command form.
   - OPEN for brace expansion (F1).
   - Variable and substitution respellings fall under accepted row A (F3, F4).
4. **The requested self-test cases exist:** guard-selftest.sh:409-466.
   - **The human's list:** `git diff --output=X` (:409), `git log --output X` (:411), `git show --output=.claude/.last-test-run` (:412-413), `git format-patch` (:415-416), plain diff/log/show ALLOW (:433-438).
   - **python3 -m json.tool is no longer allowed:** this is checked as a settings test (:464-465). There is no guard test because there is no guard rule; the human decision asked only for the allow rule to be removed.
   - **They would fail with the rule removed:** yes, by trace. Each BLOCK case would get ALLOW under the r2 git branch, and each needs the "$GW" message fragment.
   - **Gap:** there is no brace case (F1).
5. **Plain git diff, log and show still ALLOW with no ask:** CLOSED (traced :433-438, :440-442).
6. **No regressions:**
   - **Decision 9 and earlier git cases:** a01-*, e-main-git-checkout, e-sub-git-restore, r4-* trace unchanged. Push, reset and commit are blocked or asked before analyze_segment, and the HAS_OP logic at :876-883 is byte-identical to r2.
   - **Probes 3-8:** they involve no git option words, so they are unaffected.
7. **bash 3.2:** the new code uses only `case` globs, `for b in $u` under the global `set -f`, `local`, and the existing `unmask`. `"$@"` under `set -u` is already used throughout the guard. No new bash-4 constructs: OK.

## Note: the repair-02 glob bug (not a re-review)

**CONFIRMED.**
- In a bash `case`, the pattern `-[!-]*o*` cannot match the two-character word `-o`:
  - the `-` matches `-`;
  - `[!-]` has to consume `o`;
  - the trailing `*o*` then needs a second `o`, and there is none.
- The same applies to `-[!-]*C*` (guard-dangerous.sh:612) and to `-[!-]*D*` / `-[!-]*R*` (guard-dangerous.sh:613). These three lines (608, 612, 613) are the only uses of the pattern.

**Traces:**
- **r5-time-o-marker** (guard-selftest.sh:337, `/usr/bin/time -o .claude/.last-test-run true`, build-engineer):
  - :608 does not fire.
  - `wrapper_takes_next time -o` skips the file word.
  - The command is `true`, the rescan finds no checked word, and HAS_OP=0.
  - Result: ALLOW, expected BLOCK, so FAIL.
- **r5-env-C-relative-target** (guard-selftest.sh:338, `env -C modules/core touch modules/rag/x`, rag-engineer):
  - HAS_CD is never set.
  - The touch target resolves to `$CWD/modules/rag/x`, which rag-engineer owns.
  - Result: ALLOW, expected BLOCK "changes directory", so FAIL.

**What the human should expect from probe 1:**
- `guard-selftest.sh` exits non-zero with exactly these 2 FAILs, both "got ALLOW". By trace, every r9 case PASSES.
- `stop-hook-selftest.sh` is unchanged by repair-03; its sha256 is the same as before.
- So probe 1's "0 failures" criterion will not be met unless the human authorizes factory-engineer's one-line fix: :608 `--o*|-o*|-[!-]*o*`, :612 `--ch*|-C*|-[!-]*C*`, :613 `--ch*|-D*|-R*|-[!-]*D*|-[!-]*R*`. By reading, that fix is correct.
- **Real-world exposure is low.** `time`, `env -C` and `sudo -D` are not allow-listed, so the bare spellings prompt rather than run silently.

## Human confirmation for F1 and F2 (in your own terminal; harmless)

- **F1:** `echo git diff --outpu{t,t}=X` prints `git diff --output=X --output=X`.
- **F2:** `bash -n -i -c 'echo EXECUTED'` prints EXECUTED.
- **Both findings rest on one assumption,** the same as review-03 assumption 3: Claude Code's `Bash(prefix:*)` rules approve these strings with no prompt. You can check that in-session with a harmless target in the scratchpad.

## Possible fixes (for the owner; not applied)

- **F1:** in the git loop, block any git word that contains `{` together with `,` or `..`. Use fail-closed matching, or compare against RAW_CMD single-quoting the way sed_check does. Also add a self-test such as `git diff --outpu{t,t}=X`.
- **F2:** remove `Bash(bash -n:*)`. validate-agent-files.sh already runs `bash -n` on every hook. Alternatively, add a guard rule that blocks a shell started with `-n` together with `-i`, `--rcfile` or `--init-file`.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Amendment 02, re-check of finding B only after repair-03 (docs/agent-runs/amendment-02/prompt.md; review-report-03 row B)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run (no Bash). Traced by hand: all r9 cases at guard-selftest.sh:409-466 give their expected results; r5-time-o-marker (:337) and r5-env-C-relative-target (:338) give ALLOW where BLOCK is expected (2 expected FAILs in probe 1).
VALIDATION RESULTS: Read the installed guard-dangerous.sh :1-1304 (relevant parts: 1-130, 380-711, 735-770, 840-1304), the r2 baseline git branch (r2:840-849), settings.json in full, the guard-selftest.sh header, harness and r5/r9 lines, verify-tests-ran.sh marker and mtime lines, validate-agent-files.sh write sites, factory-engineer.md:81, docs/agents.md:138, and prompt.md probes. B: OPEN (F1 and F2 major).
ASSUMPTIONS: (1) Claude Code prefix rules approve `git diff --outpu{t,t}=X` and `bash -n -i -c '…'` with no prompt (same as review-03 assumption 3). (2) Bash manual: -n is ignored by interactive shells, and -i makes a -c shell interactive (bash 3.2 and 5). (3) git's diff --output callback opens the file while it parses the options.
RISKS: F1 and F2 leave a silent arbitrary-file overwrite or command run under allow-listed prefixes. F3 and F4 are respellings that prompt (class A). F5 adds fail-closed false positives. The repair-02 glob bug makes probe 1 fail with 2 FAILs.
MANUAL ACTION REQUIRED: Human: (a) run the two terminal checks above; (b) accept F1 and F2 as recorded risks or authorize a targeted factory-engineer change; (c) decide on the one-line glob fix for :608/:612/:613, otherwise expect 2 FAILs in probe 1.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human decision on F1/F2 and on the glob fix; then ratification, restart, and probes 1-9.
```

Relevant files:
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
- /Users/brian/Developer/repos/scholarchain/.claude/settings.json
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r2
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/repair-03.md
