# Amendment 02, repair-02 (factory-engineer): repair report

All human decisions (a), (b) and (c) are implemented, plus cheap fixes 5, 6, 8 and 12 (part). Findings 10, 11, 13, 14 and 15 and amendment-01 #7 are recorded only, as ordered. Validation passes:
- `bash -n` on all 4 hooks and both self-tests;
- `jq empty` on `settings.json` and `.mcp.json`;
- the validator: **72 passed, 0 failed**.

Nothing was run except the allowed inspection commands. The self-tests were not run, so every outcome below is traced by hand. One heads-up for the orchestrator: `sort`, `uniq` and `sed -n` will now prompt for approval. Details are in the interface handoff.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: Amendment 02, repair-02 (docs/agent-runs/amendment-02/prompt.md; human decisions a, b, c; cheap fixes 5, 6, 8, 12-part; record-only 10, 11, 13, 14, 15, a01-#7)
FILES CREATED: none in the repository. Scratchpad: guard-dangerous.sh.r2 (draft, byte-identical to the installed hook), repair-02-guard.diff (r1 -> r2 guard diff), amendment-02-after-repair-02.diff (cumulative git diff of the constitutional files)
FILES MODIFIED: .claude/hooks/guard-dangerous.sh, .claude/settings.json, .claude/agents/build-engineer.md, docs/agents.md, .claude/hook-tests/guard-selftest.sh, .claude/hook-tests/stop-hook-selftest.sh
COMMANDS RUN: bash -n (draft guard, all hooks, both self-tests); jq empty .claude/settings.json; jq empty .mcp.json; .claude/hooks/validate-agent-files.sh; shasum -a 256; ls -l; grep, sed -n, wc; git diff, git diff --no-index, git status (read-only)
TEST RESULTS: Self-tests not run (forbidden by the work order; the human runs them after restart). All new and affected cases traced by hand against the installed guard.
VALIDATION RESULTS: bash -n OK x6; jq OK x2; validator "72 checks passed, 0 failed"; the installed guard's sha256 33bf4cbf...a9eca equals the scratch draft; the executable bit is kept on every hook.
ASSUMPTIONS: (1) GNU getopt_long accepts the abbreviation --adjust, so the case r5-wrapper-unknown-optarg-scan uses a real respelling. (2) git status --untracked-files=all respects .git/info/exclude (stop-hook s24 fixture). (3) macOS /usr/bin/xargs uses BSD getopt (-tn 1 means -t -n 1), as review-02 assumed.
RISKS: (1) The guard is now fail-closed in three new places (the re-check after a wrapper, the xargs allow list, the sed script reader). These can block rare legitimate commands; every block message says what to do instead. (2) Backdating through cp -p, rsync -a/-t and install -p remains (accepted under decision c). (3) Interpreters and awk/find -fprint writes are still unchecked (known since the first review).
MANUAL ACTION REQUIRED: Human: run both self-tests after restart (expect 0 failures). Note that sort, uniq and sed -n now prompt unless you allow them in settings.local.json.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of review-02 open rows 1-3 and B1 only; then human ratification, restart, and probes 1-9.
```

## 1. Matrix basis
- Every change is to factory-engineer artifacts:
  - `.claude/hooks/*`
  - `.claude/hook-tests/*`
  - `.claude/settings.json`
  - `.claude/agents/*`
  - `docs/agents.md`
- Authority: human decisions (a), (b) and (c) and the listed cheap fixes in this work order.
- No other owner's file was touched.

## 2. Changes, per item

**(a) Items 1 and 2: wrappers compared by basename; xargs.** File: `.claude/hooks/guard-dangerous.sh`
- **`analyze_segment` :572.** The strip loop takes `wb="${w##*/}"` (:587 `is_wrapper "$wb" || break`). Keywords (`then`, `do`, `if`, …) are still matched on the exact word.
- **`is_wrapper` :428.** Covers command, builtin, exec, nohup, time, sudo, doas, env, nice, xargs, stdbuf, timeout, caffeinate, unbuffer, setsid, ionice, eval and source, plus the g-prefixed GNU names.
- **`wrapper_takes_next` :396.** Replaces `wrapper_optarg` and reads options by getopt rules:
  - in a cluster, the last letter takes the next word (`-tn 1`, `exec -la x`);
  - an inline argument stays in the same word (`-n1`, `-I{}`);
  - GNU long options take a separate argument (`--max-args 1`, `--adjustment 5`, …).
- **Wrapper side effects (:596-603):**
  - `time -o`/`--output` is blocked;
  - `env -C`/`--chdir` and `sudo -D`/`-R`/`--chdir` set HAS_CD, so a relative write target needs a literal absolute path.
- **xargs allow list (:868).** Under xargs only these may run: grep, egrep, fgrep, ggrep, cat, head, wc, ls, echo, printf, basename, dirname, stat, du, shasum, sha1sum, sha256sum, sha512sum, md5, md5sum, cksum, realpath, readlink and true. Anything else is blocked, including all ordered writers: touch, cp, mv, tee, sed, perl, install, ln, rsync, truncate and dd. It also blocks sort, uniq, tail and shells, because input operands can turn them into writers or watchers. The old writer-only list is replaced.
- **Fail-closed re-check: `rescan_after_wrapper` :882, called at :618 and :875.** After any wrapper, every later word whose basename is a checked command (`is_checked` :436) is analyzed again from there, once per segment. This catches option arguments the table does not know.
- **Pipes into a shell (awk :241).** `ALLCODE` now also fires for a pipe into a path-qualified or wrapped shell (`| /usr/bin/env bash`).
- **Floor rules strengthened (basename rule applied):**
  - remote-code rule :1049 also catches `curl … | /usr/bin/env bash`;
  - recursive-rm rule :1013 also catches `/bin/rm -rf /`.

New self-test cases (`guard-selftest.sh`, section at :313):
- **BLOCK (xargs):** r5-abs-xargs-touch-marker, r5-xargs-tn-touch-marker, r5-xargs-max-args-touch-marker, r5-gxargs-touch, r5-xargs-sort, r5-xargs-sh, r5-xargs-uniq.
- **ALLOW (xargs):** r5-xargs-tn-grep-allowed, r5-xargs-max-args-grep-allowed (these two pin the getopt reading: without it, `1` becomes the command and is blocked), r5-xargs-wc-allowed.
- **BLOCK (path-qualified wrappers):** r5-abs-env-sed-i, r5-abs-nohup-tail-f, r5-abs-time-sed-i, r5-abs-command-sed-i, r5-abs-builtin-sed-i, r5-abs-exec-tail-f, r5-abs-nice-n-tail-f, r5-abs-timeout-s-tail-f, r5-abs-stdbuf-o-sed-i, r5-abs-sudo-u-sed-i.
- **BLOCK (other):** r5-exec-cluster-la, r5-wrapper-unknown-optarg-scan (pins the re-check), r5-time-o-marker, r5-env-C-relative-target, r5-pipe-into-abs-env-bash, r5-curl-pipe-abs-env-bash, r5-abs-rm-recursive-root.
- **ALLOW (other):** r5-timeout-sbt-allowed, r5-command-v-allowed.
- **Defense in depth:** r5-exec-cluster-la and r5-xargs-tn-touch-marker are each covered by two layers. Each layer is pinned on its own by r5-xargs-tn-grep-allowed (getopt reading) and r5-wrapper-unknown-optarg-scan (re-check). Every other new BLOCK case returns ALLOW if its own rule is removed.

**(b) Remove sort, uniq and `sed -n` from the allow list; add guard rules.**
- **`settings.json`:** `Bash(sort:*)`, `Bash(uniq:*)` and `Bash(sed -n:*)` are removed (the allow list now goes `shasum`, `cut`, `bash -n` at :28-30).
- **sort (:756):**
  - `-o`, `-oFILE`, clusters such as `-ro`, `--output[=]` and its abbreviations, and `-T`/`--temporary-directory` are write targets, checked by `check_write_target`;
  - `--compress-program` is blocked;
  - `-k`, `-t` and `-S` arguments are skipped.
- **uniq (:790):** every operand after the first is a write target (fail closed); `-f`, `-s` and `-w` arguments are skipped.
- **sed (:639, the reader functions at :447-559):**
  - the script is every `-e`/`--expression` argument, else the first operand;
  - `-f`/`--file` is blocked;
  - `sed_script_risky` reads addresses (numbers, `$`, `/re/`, `\cREc`, ranges, `I`/`M`, `!`) and commands; it treats `w`, `W`, `e`, `s///w` and `s///e` as risky, and so is an unreadable script;
  - each script is read the BSD way (bracket expressions) and the GNU way; either reading blocks;
  - a script holding `$` before a letter, `{` or `(` must appear literally in single quotes in the raw command (`RAW_CMD`, :971). This stops `p='w x'; sed -n "$p"`.
- **New cases (section at :344):**
  - BLOCK: r6-sort-o-marker, r6-sort-o-claude-md, r6-sort-output-long-main, r6-sort-output-separate, r6-sort-cluster-ro, r6-sort-attached-o, r6-sort-compress-program, r6-uniq-output-evidence-main, r6-uniq-output-other, r6-uniq-f-then-output, r6-sed-w-command, r6-sed-W-command, r6-sed-s-w-flag, r6-sed-s-e-flag, r6-sed-e-command, r6-sed-second-expression-w, r6-sed-ne-cluster-w, r6-sed-w-after-label, r6-sed-w-in-block, r6-sed-script-file, r6-sed-variable-script.
  - ALLOW: r6-sort-o-scratch-allowed, r6-sort-allowed, r6-uniq-allowed, r6-uniq-f-arg-allowed (pins the `-f` argument skip), r6-sed-n-range-allowed, r6-sed-last-line-allowed, r6-sed-subst-allowed, r6-sed-regex-range-allowed, r6-sed-block-allowed.

**(c) touch timestamp options (:736).**
- `touch`/`gtouch` with any short cluster containing `t`, `d`, `r` or `A`, or with `--d*`/`--r*` (`--date`, `--reference`), is blocked everywhere: every agent, the main session, and scratch paths.
- New cases (section at :376): r7-touch-t-own-territory, r7-touch-d, r7-touch-r, r7-touch-A, r7-touch-date-long, r7-touch-reference-long, r7-touch-cluster-mt, r7-touch-abs-path, r7-gtouch-d, r7-touch-t-scratch.
- Existing case changed: r2-touch-r-marker (:299) now expects "Agents never set file times". New r2-touch-c-marker (:300) keeps the marker message pinned.

**Fix 5: stop-hook s24 and s25** (`stop-hook-selftest.sh:225-238`)
- **s24:** the fixture uses `git rm --cached` plus `.git/info/exclude`, so status shows only `D ` while the file still exists. Without the deletion skip, the hook blocks.
- **s25:** the fixture force-adds (`git add -f`) the `target/` file, so status lists it. Without the target/ skip, the hook blocks.
- New case: s25-target-ignored-subagent.

**Fix 6: build-engineer.md:36.**
- Now reads: "Run `sbt Test/compile` and observe the output. If you changed `build.sbt`, `project/**`, or the formatter config, run the full `sbt check` and observe its output (the stop hook requires the full marker for these files). If you changed only other files, run the changed projects' targeted tests … and run the full `sbt check` when you are the wave closer …"

**Fix 8: primary gates in docs/agents.md.**
- New "Gate wording" paragraph at :19, defining "targeted tests" and "`sbt check` as wave closer" per the CLAUDE.md Definition of done.
- Primary gates rewritten:
  - build-engineer (:24): "`sbt check` after any build or formatter change; otherwise targeted tests, and `sbt check` as wave closer; code review";
  - domain-engineer (:25): "Contract tests, targeted tests (`core` and `core2_13`), `sbt check` as wave closer, and code review";
  - service-engineer (:32): "API tests (targeted tests) and `sbt check` as wave closer";
  - dependency-updater (:40): "`sbt check` (the ledger is under `project/**`, so the full marker is always required), …".
- Lifecycle rows :88, :89, :96 and :107 are aligned the same way.
- The guard row in the hooks table (:138) gains the repair-02 summary.

**12 (part): more watchers.** All are blocked with the existing "No watchers…" message:
- `sbt`/`sbtn` with a `~` task (:698), including inside a quoted multi-command argument;
- `docker`/`podman`/`docker-compose`/`kubectl` with `-…f…` or `--follow` after `logs` (:705);
- `journalctl -f`/`--follow` (:713);
- `sleep`/`gsleep` with `inf` or `infinity` in any case (:719).

New cases (section at :388):
- BLOCK: r8-sbt-tilde, r8-sbt-batch-tilde-quoted, r8-sbt-tilde-after-semicolon, r8-docker-logs-f, r8-docker-logs-tf, r8-docker-compose-logs-follow, r8-journalctl-f, r8-journalctl-follow, r8-kubectl-logs-f, r8-kubectl-ns-logs-follow, r8-sleep-infinity, r8-sleep-inf-abs.
- ALLOW: r8-sleep-5-allowed, r8-sbt-testonly-allowed, r8-docker-compose-f-allowed (pins the "after `logs`" scoping), r8-journalctl-n-allowed.

**Allowed cases, traced (no block, no ask):**
- the decision 9 cases: i-sbt-background-main/sub, i-grep-runs, i-write-own-territory, i-tail-n, i-bash-n;
- `sed -n '1,20p' file`, `sort file`, `uniq file`, `sleep 5`, `sbt -batch "core/testOnly X"` (added as ALLOW cases);
- every earlier r1/r2/r4/c/d/e/f case keeps its result. The only change is r2-touch-r-marker's message fragment.

Guard self-test case lines: 312 in total, 87 of them new.

## 3. Privilege delta
- **Narrowed:**
  - the allow list loses `Bash(sort:*)`, `Bash(uniq:*)` and `Bash(sed -n:*)`; these now prompt;
  - the guard blocks more: wrapper respellings, non-read-only commands under xargs, sort/uniq/sed writes, touch time options, and the new watchers.
- **Widened:** nothing.
- No agent's tools, ownership or autonomy changed.

## 4. Floor invariants
- **Dangerous-command guards:** strengthened (`/bin/rm -rf`, `curl | /usr/bin/env bash`, wrapper respellings).
- **Test-stop hook:** preserved. `verify-tests-ran.sh` is unchanged; its gate is strengthened indirectly, because the marker can no longer be refreshed through xargs, sort -o or time -o, or backdated with touch -t.
- **Formatter:** preserved (untouched).
- **Secret-read denial, Terraform denial, destructive-AWS denial, force-push denial:** preserved; their rules are unchanged.

## 5. Audits
- **Collision:** no new agent, command, hook or matcher.
- **Orphans:** no new hook files; both self-tests stay referenced by the validator.
- **One writer:** `owner_of` is unchanged and in sync with docs/agents.md.
- The validator's 72 checks include the roster and ownership samples: 0 failures.

## 6. Validation output
```text
bash -n OK: .claude/hooks/format-scala.sh
bash -n OK: .claude/hooks/guard-dangerous.sh
bash -n OK: .claude/hooks/validate-agent-files.sh
bash -n OK: .claude/hooks/verify-tests-ran.sh
bash -n OK: .claude/hook-tests/guard-selftest.sh
bash -n OK: .claude/hook-tests/stop-hook-selftest.sh
jq OK: settings.json
jq OK: .mcp.json
validate-agent-files.sh: 72 checks passed, 0 failed.
-rwxr-xr-x guard-dangerous.sh (sha256 33bf4cbfc23e3a6817ea5f365977cb2cd674fa681904946b4119e7e40f8a9eca, equal to the scratch draft)
```
bash 3.2 compatibility:
- no `mapfile`, associative arrays or `${x,,}`;
- regexes for `[[ =~ ]]` live in variables; the new string handling uses `${s:i:1}`;
- the awk program still contains no single quote.

## 7. Diff
- **Full guard diff (r1 to r2, 621 lines):** /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/repair-02-guard.diff (sha256 4a331761c50ff5a05ef163400ef57c9f13d094bd22619df33bc2c696eadc9c89)
- **Cumulative constitutional diff:** /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/amendment-02-after-repair-02.diff (sha256 dee8300e07293bc01ec40599494db92206fac785d16693073142e0b1cd904ca8)
- The self-tests are untracked new files, so they are not in git diff. Current sha256:
  - guard-selftest.sh: 3e8b8736…3918
  - stop-hook-selftest.sh: f12cad5b…b65e

Key changed sections, verbatim:

```bash
# settings.json allow list (repair-02 removes sort, uniq, sed -n)
      "Bash(shasum:*)",
      "Bash(cut:*)",
      "Bash(bash -n:*)",
```
```bash
# guard :587-618 (wrapper strip loop)
    is_wrapper "$wb" || break
    case "$wb" in xargs|gxargs) viax=1 ;; esac
    shift
    if [ "$wrapped" = 0 ]; then wrapped=1; after="$*"; fi
    ... time|gtime) case "$1" in --o*|-[!-]*o*) block "$wb -o/--output writes a file the guard cannot check." ...
            env|genv) case "$1" in --ch*|-[!-]*C*) HAS_CD=1 ;; esac ;;
            sudo) case "$1" in --ch*|-[!-]*D*|-[!-]*R*) HAS_CD=1 ;; esac ;;
          if wrapper_takes_next "$wb" "$1"; then shift; [ $# -gt 0 ] && shift; else shift; fi ;;
```
```bash
# guard :868-890
  if [ "$viax" = 1 ]; then
    case "$cmd" in
      grep|egrep|fgrep|ggrep|cat|head|wc|ls|echo|printf|basename|dirname|stat|du|shasum|sha1sum|sha256sum|sha512sum|md5|md5sum|cksum|realpath|readlink|true) ;;
      *) block "$cmd run through xargs takes its operands from input the guard cannot see, so it cannot check where it writes or whether it ends." "$MSG_XARGS" ;;
    esac
  fi
  [ "$wrapped" = 1 ] && rescan_after_wrapper "$after"
...
rescan_after_wrapper() {
  [ "$IN_SCAN" = 1 ] && return 0
  IN_SCAN=1
  set -- $1
  while [ $# -gt 0 ]; do
    if is_checked "${1##*/}"; then analyze_segment "$*"; fi
    shift
  done
  IN_SCAN=0
  return 0
}
```
```bash
# guard :736-745 (touch)
            --d*|--r*) block "setting file times ($cmd $a) is denied ..." "$MSG_TOUCH_TIME" ;;
            --*) continue ;;
            -?*) case "$a" in -*[tdrA]*) block "setting file times ($cmd $a) is denied ..." "$MSG_TOUCH_TIME" ;; esac; continue ;;
```
```bash
# awk :241, curl rule :1049, rm rule :1013
  if (SRC ~ /\|([^;&]*[ \t\/])?(ba|z|da|k)?sh([ \t;&|)]|$)/) ALLCODE = 1
  if matches "$cmd" '(curl|wget)[^|]*\|([^;&]*[[:space:]/])?(ba|z|da|k)?sh([[:space:]]|$)'; then
    if matches "$line" '(^|[`(][[:space:]]*|[[:space:]])(([^[:space:]]*/)?sudo[[:space:]]+)?([^[:space:]`(]*/)?rm[[:space:]]+([^[:space:]]+[[:space:]]+)*(-[[:alnum:]]*[rR][[:alnum:]]*|--recursive)([[:space:]]|$)'; then
      seg="$(printf '%s' "$line" | sed -E 's/^.*(^|[`([:space:]/])rm[[:space:]]+//')"
```
```bash
# stop-hook-selftest.sh :225-238
newrepo; ( cd "$R" && git rm -q --cached modules/services/src/main/scala/Gone.scala \
  && printf 'modules/services/src/main/scala/Gone.scala\n' >>.git/info/exclude )
touch -t "$T_SRC" "$R/modules/services/src/main/scala/Gone.scala"
expect ALLOW s24-deletion-only-subagent SubagentStop
expect ALLOW s24-deletion-only-stop Stop
newrepo; change modules/services/target/scala-3/Generated.scala
( cd "$R" && git add -f modules/services/target/scala-3/Generated.scala )
expect ALLOW s25-target-ignored Stop
expect ALLOW s25-target-ignored-subagent SubagentStop
```

### Updated guard-rule table rows

| Rule | Matches | Event | Applies to | Result | Self-tests |
|---|---|---|---|---|---|
| Wrappers by basename | `/any/path/{xargs,env,nohup,exec,command,builtin,time,nice,timeout,stdbuf,sudo,…}` | PreToolUse Bash | all | the wrapped command is checked by every rule | r5-abs-* |
| Getopt reading of wrapper options | `-tn 1`, `-la x`, `--max-args 1`, `-s KILL`, `-u root`, `-o L` | Bash | all | wrapped command found | r5-xargs-tn-*, r5-xargs-max-args-*, r5-exec-cluster-la |
| Fail-closed re-check after a wrapper | any later word naming a checked command | Bash | all | that command's rule applies | r5-wrapper-unknown-optarg-scan |
| xargs read-only allow list | xargs + any command outside the list | Bash | all | BLOCK "… run through xargs …" / "use a literal path …" | r5-*xargs*, r2-xargs-* |
| time -o | `time -o`/`--output` | Bash | all | BLOCK | r5-time-o-marker |
| env -C / sudo -D | the relative target becomes ambiguous | Bash | all | BLOCK "changes directory" | r5-env-C-relative-target |
| Pipe into a wrapped or path-qualified shell | `… \| /usr/bin/env bash` | Bash | all | quoted text read as code | r5-pipe-into-abs-env-bash |
| curl into a wrapped shell (floor, strengthened) | `curl … \| /usr/bin/env bash` | Bash | all | BLOCK | r5-curl-pipe-abs-env-bash |
| Recursive rm with a path (floor, strengthened) | `/bin/rm -rf /` | Bash | all | BLOCK | r5-abs-rm-recursive-root |
| sort output | `-o`, `-oF`, `-ro`, `--output[=]`, `-T` | Bash | all | write-target check | r6-sort-* |
| sort compress program | `--compress-program` | Bash | all | BLOCK | r6-sort-compress-program |
| uniq output | operand 2 onward | Bash | all | write-target check | r6-uniq-* |
| sed script writes or runs | `w`, `W`, `e`, `s///w`, `s///e`, `-f`, unreadable script, `$var` script | Bash | all | BLOCK | r6-sed-* |
| touch time options | `-t`, `-d`, `-r`, `-A` (any cluster), `--date`, `--reference` | Bash | all, every path | BLOCK "Agents never set file times …" | r7-*, r2-touch-r-marker |
| Watchers | `sbt ~…`; `docker`/`podman`/`kubectl … logs -f`/`--follow`; `journalctl -f`/`--follow`; `sleep inf`/`infinity` | Bash | all | BLOCK "No watchers: …" | r8-* |

## 8. Open items

**Recorded risks (decision c).** These are listed here because no risk section exists in docs/agents.md or factory-engineer.md.
- Backdating with `cp -p`, `rsync -a`/`-t` and `install -p`, or through interpreters (`perl -e utime`, `python3 -c os.utime`, `SetFile -d`), is still possible. Mitigation: the wave-end full `sbt check` runs every test whatever the timestamps say.
- No hash marker in this amendment.

**Record-only findings (not repaired, as ordered):**
- **#10:** GNU long-option abbreviations (`gsed --in-pl`, `gtail --fo`).
- **#11:** report and secret checks look at the target as written.
- **#13:** `tests/integration/**` is covered by any contractTests report.
- **#14:** no self-test cases for `>&file`, `/dev/stdout`, `install -d` or single-operand `ln`.
- **#15:** a heredoc body is parsed as code; the orchestrator writes scratch files with Write.
- **amendment-01 #7:** old build comments; owner build-engineer, later.
- **Reviewer note:** `Bash(sbt -batch:*)` is an accepted risk.

**Proposed later (found during this repair, not built; scope):**
1. A command word that is a variable (`X=sed; $X -i …`, `$(which sed) -i …`).
2. `dd of=` as a write target.
3. `aws logs tail --follow`, `kubectl get -w`, `docker events`, `docker stats`, `sleep <huge number>`.
4. `nohup.out` written into the current directory.
5. `find -fprint`; awk redirection writes.

**Known false-positive classes (fail closed, all with a "what to do instead" message):**
- after a wrapper, an argument that is exactly a checked command name (`timeout 5 grep -n cp a b`);
- a pipeline containing the word `sh`/`bash` makes quoted text be read as code;
- sed scripts with the delimiter inside brackets (`s/[/]/x/`);
- `sed -n \$p` written without single quotes.

## INTERFACE HANDOFF
- **Permission change (orchestrator and work orders):**
  - `sort`, `uniq` and `sed -n` are no longer auto-allowed; each now raises an approval prompt.
  - Unattended runs should read with Read, `grep`, `head` or `tail -n` instead.
  - A work order listing them as "allowed read-only shell" will stall unless the human allows them locally.
- **New or changed guard blocks (exit 2), messages verbatim:**
  - **Under xargs, anything outside the read-only list:** "<cmd> run through xargs takes its operands from input the guard cannot see, so it cannot check where it writes or whether it ends." / What to do instead: "use a literal path: run the command with literal operands inside your own territory or under /private/tmp/claude-*. Under xargs only read-only commands may run (grep, cat, head, wc, ls, echo, printf, stat, du, shasum, basename, dirname, realpath, readlink); never touch test reports or the test marker."
  - **`time -o`:** "<time> -o/--output writes a file the guard cannot check." / "Redirect with > into your own territory or under /private/tmp/claude-* instead."
  - **touch time options:** "setting file times (<cmd> <opt>) is denied for every agent and the orchestrator; it can make a change look older than the test evidence." / "Agents never set file times: use touch without -t, -d, -r, -A, --date or --reference, or use the Write and Edit tools."
  - **sed write/exec:** "a sed script that writes files or runs commands (w, W, e, s///w, s///e), or that the guard cannot read, is denied ('<script>')." / "Use the Edit tool to change files; read with sed -n 'N,Mp' or grep, and keep scratch output under /private/tmp/claude-* with a > redirection."
  - **sed script file:** "a sed script file (<cmd> <opt>) cannot be read by the guard."
  - **sed variable script:** "the sed script '<x>' uses a shell variable or substitution, so the guard cannot read the script sed runs." / "Write the sed script literally in single quotes. …"
  - **sort compress program:** "<cmd> --compress-program runs another program the guard cannot check." / "Sort without --compress-program."
  - **sort -o/-T and uniq output operands:** existing write-target messages ("owned by …", "no write territory", "use a literal path", "markTestRun").
  - **Watchers (`sbt ~…`, `… logs -f`, `journalctl -f`, `sleep infinity`):** "a command that never ends by itself (…) stalls the run." / "No watchers: read with tail -n or grep, and finish the step."
  - **Changed-directory message** now lists env -C and sudo -D.
- **Rules agents now follow:**
  - never set file times;
  - under xargs, use only read-only commands;
  - write sed scripts literally in single quotes and never with w/W/e;
  - sbt runs are one-shot `sbt -batch …`.
- **build-engineer:** runs the full `sbt check` only after build or formatter changes, or as wave closer.
- **No new paths or owners.**

Files:
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
- /Users/brian/Developer/repos/scholarchain/.claude/settings.json
- /Users/brian/Developer/repos/scholarchain/.claude/agents/build-engineer.md
- /Users/brian/Developer/repos/scholarchain/docs/agents.md
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/stop-hook-selftest.sh
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r2
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/repair-02-guard.diff
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/amendment-02-after-repair-02.diff
