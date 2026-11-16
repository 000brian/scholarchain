SUMMARY: I've repaired findings 1, 2, 3 (decision A), 4, 7 and 9 as you specified. Nothing is committed, and none of it takes effect until you ratify it and restart.
- **Validation passes:** `bash -n` on 4 hooks and 2 self-tests, `jq empty` on both JSON files, and `.claude/hooks/validate-agent-files.sh` gives 72 passed, 0 failed (exit 0).
- **Nothing was executed against test events.** The work order forbids running the self-tests, so I checked the new rules by reading and tracing them only. The guard self-test grows from 182 to 226 cases (44 new) and the stop-hook self-test from 48 to 68 (20 new). They run in your probe 1.
- **Live evidence is limited to allowed calls.** The repaired guard has been installed since mid-pass, and all my later Bash, Write and Edit calls passed through it, including a redirect into the scratchpad.
- **Both hooks were installed exactly as drafted.** Each was drafted in the scratchpad, checked with `bash -n`, read through, and installed with one Write. The sha256 of each installed file equals its draft.
- **Decision needed:** your allow-list premise, "the guard still blocks anything dangerous inside them", is not fully true. `sort -o FILE`, `uniq IN OUT` and the `w` command in a `sed -n` script write files the guard does not check. With the new allow rules they now run without a prompt. Details under Open items, B1.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: Amendment 02, step 3, one repair pass (docs/agent-runs/amendment-02/prompt.md, decision 1 flow, step 3). Repair items: findings 1, 2 (with sbt timestamp check), 3 (human decision A), 4, 7, 9. Findings 5, 6, 8, 10-15 not repaired (listed for the human).
FILES CREATED: none (the scratch drafts below are not repository files)
  Scratch: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r1
           /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/verify-tests-ran.sh.r1
           /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/validate-r1.log
FILES MODIFIED (in this repair pass):
  /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
  /Users/brian/Developer/repos/scholarchain/.claude/hooks/verify-tests-ran.sh
  /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh
  /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/stop-hook-selftest.sh
  /Users/brian/Developer/repos/scholarchain/.claude/settings.json
  /Users/brian/Developer/repos/scholarchain/docs/agents.md
  /Users/brian/Developer/repos/scholarchain/.claude/agents/factory-engineer.md
  /Users/brian/Developer/repos/scholarchain/.claude/agents/{build,dataset,domain,hadoop,rag,service,storage,test,entity-resolution,spark}-engineer.md (10 role files, one phrase each)
COMMANDS RUN: Read/Write/Edit tools; ls, ls -lT, wc, head -c, grep, cut, sort, uniq, shasum -a 256, sed -n, tail -n (all read-only); git status, git diff --stat, git diff --no-index (read-only comparison of the step-1 scratch drafts with the installed hooks); /bin/bash -n on 2 drafts, 4 hooks and 2 self-tests; jq empty on settings.json and .mcp.json; .claude/hooks/validate-agent-files.sh (output redirected to the scratchpad). Disclosure: one read-only `awk '{print $3}'` in a pipeline to list self-test case names for the duplicate check (awk is not on the work order's list). No sbt, python, docker, uvx, network, chmod, git write, sed -i, or self-test execution.
TEST RESULTS: sbt not run (no source file touched). Self-tests not run (forbidden; the human runs them after restart). Live: every Bash/Write/Edit call I made after installing the repaired guard passed through it (redirects to the scratchpad, for-loops, bash -n, jq, the validator, Edit/Write in my territory).
VALIDATION RESULTS: bash -n OK on format-scala.sh, guard-dangerous.sh, validate-agent-files.sh, verify-tests-ran.sh, guard-selftest.sh, stop-hook-selftest.sh. jq empty OK on .claude/settings.json and .mcp.json. Validator: "validate-agent-files.sh: 72 checks passed, 0 failed." (exit 0). Executable bits: all 4 hooks -rwxr-xr-x (kept); both self-tests -rw-r--r-- (deliberately not executable). Installed guard sha256 81ac3384db1dfc75d957a4a8b32fbc743dd34804abdb4fad5419443f7fde05d9 = draft; installed stop hook sha256 d065d6a31c323299fb9290ca91d78fbd37b509bb0bca8031625ac2f2400c662a = draft. Self-test case names unique (sort | uniq -d empty). sbt timestamp verified to be local time: TEST-org.scholarchain.core.PairOrderingSuite.xml (jvm-2.13) has timestamp="2026-11-16T05:53:20"; `ls -lT` shows the file written at Nov 16 05:53:21 2026 local time.
ASSUMPTIONS: (1) Claude Code sends agent_type in SubagentStop hook input (if it does not, no agent is exempt, which is fail-closed). (2) The sbt JVM and the hook run with the same time zone (both inherit the user's environment). (3) sbt's timestamp is the suite start time (observed: it is 1 s before the file time). (4) macOS /usr/bin/awk handles the changed normalizer (only standard POSIX awk constructs are used; checked by reading, not run). (5) The step-1 drafts guard-dangerous.sh.new and verify-tests-ran.sh.new in the scratchpad are the step-1 installed versions (line counts 857 and 288 match the reviewer's reading); the hook diffs below are taken against them.
RISKS: see Open items B1-B6 (allow-list write gaps, backdating sources, DST hour, `parallel`, new fail-safe false positives, normalizer runtime untested).
MANUAL ACTION REQUIRED: Human: route this repair to code-reviewer for the step-4 re-check of findings 1, 2, 3, 4, 7 and 9 only; decide B1 (allow-list write gaps) and the unrepaired-findings list; after ratification restart Claude Code and run probes 1-9. The orchestrator saves this report verbatim as repair-01.md.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 4: code-reviewer re-checks the repaired findings (review-report-02.md); then human ratification, restart, probes 1-9.
```

## 1. Matrix basis
- **Finding 1:** routed to factory-engineer by the human ("Route findings 1 and 2 to factory-engineer as proposed"). Authority comes from decision 4 and from decision 10 (respellings must not work).
- **Finding 2:** same routing, plus the human's extra orders: require the sbt timestamp, block touch on test-reports paths or the marker, and add xargs-touch self-tests. Authority: decisions 3a and 3b.
- **Finding 3:** human decision A, quoted verbatim in the work order. It narrows the test-stop invariant's scope for read-only subagents only. Stop is unchanged.
- **Finding 4:** human order ("the guard must check every block rule before it returns any ask").
- **Finding 9:** human order, with the exact list of 11 allow rules.
- **Finding 7:** human order on the role files (wave-closer default).
- Nothing else was changed.

## 2. Changes, per finding

### Finding 1: respellings (major) - repaired
- **`$'…'` and `$"…"` now read like plain quotes.** The normalizer drops the `$`, so `$'sed'` and `$"sed"` read as `sed`.
  - A `$'…'` string that holds a backslash escape is not decoded. It is marked `$SCANSI` and blocked in three places:
    - (a) when it is the command word: guard-dangerous.sh:435-438;
    - (b) when it is an argument of any command the guard inspects (sed, perl, editors, tail, watch, less, cd, tee, touch, cp, mv, install, ln, rsync, rm, chmod, git, find and similar): :442-451;
    - (c) when it is run as code (bash -c, env -S, eval, source, a pipe into a shell, a here-string). The awk sets ANSICODE, emits `$SCANSICODE`, and guard_bash blocks at :780-783.
  - `printf '%s\n' $'a\tb'` and `grep -n $'\t' f` stay allowed.
  - Normalizer: awk ANSI branch :304-320; `$"` :321.
- **The `bash -c` code word is now found after options.** The normalizer is restructured to keep `raw` (the word as the shell passes it) and to decide at the end of each word (:259-283).
  - After a shell's `-c`, the code is the first word that is not one of: an option (`-*`/`+*`, including `--`), the argument of `-o`/`+o`/`-O`/`+O`, a redirection target, or a file-descriptor number before `>`/`<`.
  - `>&`, `<&`, `&>` and `>|` no longer count as command separators, so `bash -c 2>&1 '…'` works.
  - A line continuation (`\` + newline) is not a word (:285).
  - The old `cwa`/`prev` mechanism is removed.
- **`env -S` is unwrapped.** The forms `env -S 'code'`, `env -S'code'`, `env -iS 'code'`, `env --split-string 'code'` and `--split-string='code'` are all unwrapped as code (:269-273; `isenv` :248). `wrapper_optarg env` also knows `--unset`, `--chdir` and `--split-string` (:382).
- **`exec -a name` is handled:** `wrapper_optarg exec -a` (:383).
- **New self-test cases** (guard-selftest.sh, section "Amendment 02 repair, finding 1"):
  - BLOCK: r1-ansi-quoted-sed, r1-ansi-quoted-tail-f, r1-ansi-quoted-watch, r1-locale-quoted-sed, r1-ansi-escaped-command-word, r1-ansi-escaped-sed-option, r1-ansi-escaped-cp-option, r1-ansi-escaped-bash-c, r1-ansi-escaped-eval, r1-bash-c-dashdash, r1-bash-c-option-after-c, r1-bash-c-o-pipefail, r1-bash-c-redirect-first, r1-bash-c-dup-first, r1-bash-c-line-continuation, r1-env-S, r1-env-S-attached, r1-env-split-string, r1-env-S-redirect, r1-exec-a.
  - ALLOW: r1-ansi-printf-allowed, r1-ansi-grep-allowed, r1-bash-c-dashdash-allowed, r1-env-S-allowed.
  - Every BLOCK case here returns ALLOW on the step-1 guard, as traced. The one exception is r1-bash-c-line-continuation, a regression guard for the restructured parser (the step-1 parser handled it too).

### Finding 2: fake-fresh evidence (major) - repaired
- **(a) Guard: file-changing commands run through `xargs` are blocked.** analyze_segment records `viax=1` when it strips an `xargs` wrapper (:413).
  - The rule covers `touch`, `tee`, `cp`, `mv`, `install`, `ln`, `rsync`, their g-variants, and `unlink`, `truncate`, `chmod`, `chown`, `patch`, `dd`, `split`. The block is at :453-460.
  - This covers `find … | xargs touch` on reports or the marker, `echo modules/core/A.scala | xargs touch`, and `echo … | xargs cp /tmp/claude-1/a` (the missing-operand form).
  - touch with a literal test-reports path or the marker was already blocked by check_write_target. New cases pin it too.
- **(b) Stop hook: sbt's timestamp is now required.** Every fresh report (file mtime newer than the change) must also carry sbt's own `timestamp` attribute, strictly later than the changed file's mtime.
  - Code: verify-tests-ran.sh `report_after` :267-275, called at :335. `local_mtime` :95-102, used at :315.
  - **How I compare:** sbt writes `YYYY-MM-DDThh:mm:ss[.fff]` in local time with no zone (verified above). The changed file's mtime is rendered in local time as `YYYYMMDDhhmmss` with BSD `stat -f %Sm -t %Y%m%d%H%M%S` (macOS), falling back to GNU `date -r FILE +…` (Linux). The timestamp's digits are pulled out with a `sed -n -E` ERE. Both values must be exactly 14 digits; they are then compared as integers with `[ -gt ]`. No python, no GNU date on macOS, bash 3.2 safe.
  - **Fail-closed rules:** each of these means "not covered":
    - a missing timestamp;
    - an unparsable timestamp;
    - a zoned timestamp (a `Z` or offset after the seconds);
    - an mtime that cannot be rendered;
    - a timestamp in the same second as the change.
  - A report whose file time is new but whose sbt timestamp is old is treated as touched, copied or stale. So `xargs touch` on old reports now blocks even if the guard were bypassed.
- **New guard cases** (section "finding 2"):
  - BLOCK: r2-xargs-touch-reports, r2-xargs-touch-marker, r2-xargs-touch-main, r2-xargs-touch-other, r2-xargs-cp-one-operand, r2-xargs-I-cp, r2-xargs-0-tee, r2-find-exec-touch-reports, r2-touch-r-marker, r2-touch-c-report-abs.
  - ALLOW: r2-xargs-grep-allowed, r2-xargs-echo-allowed.
  - Discriminating: all r2-xargs-* BLOCK cases pass on the step-1 guard (no operand, so no target is checked, and no `test-reports` text appears).
  - Regression pins for pre-existing rules, which you asked for: r2-find-exec-touch-reports, r2-touch-r-marker, r2-touch-c-report-abs.
- **New stop-hook cases:** s29-touched-old-report, s30-core-both-builds-touched, s31-report-without-timestamp, s32-timestamp-same-second-as-change, s33-timestamp-with-zone, s34-one-touched-among-fresh. All BLOCK, and all would ALLOW without report_after.
- **Fixture change:** report() now writes `timestamp="…"`, which defaults to the file time, as sbt does. An optional 5th argument sets a different sbt time, `none` omits the attribute, and `zoned:` adds a `Z`. tsattr() converts a `touch -t` time to sbt's format.

### Finding 3: code-reviewer stall (major) - repaired as human decision A
- **The exemption (SubagentStop only).** verify-tests-ran.sh:168-203 exempts the agent only when all of these hold:
  - `agent_type` is non-empty and only `[A-Za-z0-9_-]`, not starting with `-`;
  - `.claude/agents/<agent_type>.md` exists and is readable;
  - the file starts with `---` and has a closing `---`;
  - its frontmatter `name:` equals agent_type;
  - there is exactly one `tools:` line, it is a non-empty one-line value with no `*`, and it is split on commas and blanks with `Bash(...)` reduced to `Bash`;
  - the value names none of Write, Edit, MultiEdit, NotebookEdit or Bash (case-insensitive).
- Anything else means no exemption. Stop (the main session) is unchanged: the exemption is checked only when the event is SubagentStop.
- **Recorded in two places:**
  - docs/agents.md, Stop and SubagentStop gate row (line 139);
  - factory-engineer.md floor invariant (line 60), with the spec quote "prevents a source-changing agent from stopping".
- **New stop-hook cases:**
  - ALLOW: s35-reviewer-exempt-while-services-untested.
  - BLOCK: s36-reviewer-on-stop-event-not-exempt, s37-writing-agent-not-exempt, s38-unknown-agent-not-exempt, s39-empty-agent-type-not-exempt, s40-bash-form-tool-not-exempt, s41-missing-tools-field-not-exempt, s42-multiline-tools-not-exempt, s43-wildcard-tools-not-exempt, s44-space-separated-tools-not-exempt, s45-name-mismatch-not-exempt, s46-agent-type-with-slash-not-exempt, s47-unclosed-frontmatter-not-exempt, s48-no-agent-type-not-exempt.
  - Fixture agent files are written by `agentfile` in newrepo().
- **Discrimination:** each case fails if its own check is removed, with three exceptions that are each covered by two independent checks, so removing one does not flip them:
  - s38: a missing file also fails the `head` check;
  - s41: no tools line also fails the empty-value check;
  - s48: no agent_type behaves like s39.
  - s39 is made discriminating by a `.claude/agents/.md` fixture with an empty name.

### Finding 4: ask before block (minor, ordered) - repaired
- guard_bash no longer exits on an ask. Its three asks (git commit/push, AWS mutation, main-session constitutional shell command) became `defer_ask` (:679, :761, :836).
- The constitutional rule is now if/else (ask for the main session, block for subagents).
- The dispatcher returns the first deferred ask only after guard_bash has run every block rule (:929-931). guard_path_write keeps immediate asks, because its asks are its final decisions.
- **Cases:**
  - BLOCK: r4-commit-ask-then-sed-i (`git commit -m x; sed -i s/a/b/ README.md`), r4-commit-ask-then-tail-f, r4-commit-ask-then-redirect, r4-aws-ask-then-watch. All four were ASK before.
  - ASK: r4-push-alone-still-asks, r4-aws-alone-still-asks. Both would be ALLOW if the flush were missing.

### Finding 9: allow list (minor, ordered) - repaired
- .claude/settings.json:22-32 adds exactly: `Bash(sbt -batch:*)`, `Bash(grep:*)`, `Bash(head:*)`, `Bash(tail -n:*)`, `Bash(ls:*)`, `Bash(wc:*)`, `Bash(shasum:*)`, `Bash(sort:*)`, `Bash(uniq:*)`, `Bash(cut:*)`, `Bash(sed -n:*)`.
- The PreToolUse guard still runs first on each one.
- docs/agents.md, Permission rules row (line 141), is updated.
- **Caveat:** see B1.

### Finding 7: wave-closer default (minor, ordered) - repaired
- The same phrase is replaced in build-engineer.md:36, dataset-engineer.md:29, domain-engineer.md:30, hadoop-engineer.md:22, rag-engineer.md:24, service-engineer.md:22, storage-engineer.md:22, test-engineer.md:30, entity-resolution-engineer.md:22 and spark-engineer.md:23.
- Old phrase: "when the work order makes you the wave closer".
- New phrase: "when you are the wave closer (named in the work order; if it names none, the last source-changing agent of the wave, which the orchestrator tells you when it invokes you)".
- dependency-updater keeps its unconditional full `sbt check` (ledger = project/**).

## 3. Privilege delta
- **settings.json allow:** widened by the 11 prefix rules you ordered. The deny and ask lists are unchanged.
- **code-reviewer:** the stop gate no longer applies on SubagentStop (decision A). Its tools are unchanged (Read, Grep, Glob).
- **All Bash-capable agents and the main session:** narrowed by four new blocks:
  - file-changing commands under xargs;
  - escaped `$'…'` text in command or checked-argument position, or run as code;
  - `env -S`, `exec -a` and `bash -c` code after options, now analyzed;
  - asks that can no longer mask blocks.
- **Test-stop evidence:** strengthened (the sbt timestamp is required).
- **Unchanged:** no `tools:` field, ownership or autonomy changed for any agent, including factory-engineer.

## 4. Floor invariants
- **Dangerous-command guards:** strengthened. Every raw regex rule is unchanged. The only edits in that region turn two `ask` calls into `defer_ask`, which can only turn a former ASK into a BLOCK, never an ALLOW. Evidence: the guard diff below.
- **Test-stop hooks:**
  - Narrowed only as decision A ratifies: read-only subagents on SubagentStop.
  - Stop is unchanged (s36).
  - Strengthened: fresh reports need sbt's timestamp after the change (s29-s34).
  - Build files still need the full marker; s14 and s15 are unchanged.
- **Formatter behavior:** preserved. format-scala.sh and its PostToolUse entry are untouched.
- **Secret-read denial:** preserved. is_secret_path, the raw secret regexes and the deny rules are unchanged. The new allow rules (grep, head and others) still pass the guard's secret-read regex first.
- **Terraform apply/destroy denial:** preserved (regex and deny rules identical; j-echo-terraform-destroy, a01-terraform-*).
- **Destructive AWS denial:** preserved (identical).
- **Force-push denial:** preserved (identical).

## 5. Audits
- **Collision:**
  - No agent, command or hook was added or renamed.
  - The 11 new allow rules overlap no ask rule. Deny rules (e.g. `Bash(cat .env:*)`) take precedence anyway.
  - The hook matchers are unchanged.
  - Self-test case names are unique.
- **Orphan:**
  - `.claude/hook-tests/` still holds exactly the two listed self-tests.
  - All 4 hooks are referenced in settings.json and executable.
  - The validator's orphan checks pass (72/0).
- **One-writer:**
  - owner_of is unchanged in this pass, and no new path was introduced.
  - The stop-hook fixtures live only under /tmp/claude-stoptest.*.

## 6. Validation (command output)
```text
bash -n OK  .claude/hooks/format-scala.sh
bash -n OK  .claude/hooks/guard-dangerous.sh
bash -n OK  .claude/hooks/validate-agent-files.sh
bash -n OK  .claude/hooks/verify-tests-ran.sh
bash -n OK  .claude/hook-tests/guard-selftest.sh
bash -n OK  .claude/hook-tests/stop-hook-selftest.sh
jq OK settings.json
jq OK .mcp.json
-rw-r--r-- .claude/hook-tests/guard-selftest.sh
-rw-r--r-- .claude/hook-tests/stop-hook-selftest.sh
-rwxr-xr-x .claude/hooks/format-scala.sh
-rwxr-xr-x .claude/hooks/guard-dangerous.sh
-rwxr-xr-x .claude/hooks/validate-agent-files.sh
-rwxr-xr-x .claude/hooks/verify-tests-ran.sh
validator exit 0
validate-agent-files.sh: 72 checks passed, 0 failed.
```
Frontmatter: the validator checks name, description and tools on all 18 agent files; all pass. No frontmatter was edited in this pass.

## 7. Updated guard-rule table (changed or new rules only)

| Rule | Matches | Event | Applies to | Decision | Self-test cases |
|---|---|---|---|---|---|
| ANSI-C command word | Command word (after wrappers) that came from `$'…'` containing a backslash escape | PreToolUse Bash | all agents and the main session | BLOCK: "the command word is $'...' text with backslash escapes, so the guard cannot read which command runs." | r1-ansi-escaped-command-word |
| ANSI-C argument | `$'…'`-with-escape argument of sed, perl, ed/ex/vi family, tail, watch, less, cd, tee, touch, cp, mv, install, ln, rsync, rm, unlink, truncate, chmod, chown, patch, dd, split, git, find (and g-variants) | PreToolUse Bash | all | BLOCK: "an argument of <cmd> is $'...' text with backslash escapes, so the guard cannot read it." | r1-ansi-escaped-sed-option, r1-ansi-escaped-cp-option; allowed: r1-ansi-printf-allowed, r1-ansi-grep-allowed |
| ANSI-C as code | `$'…'`-with-escape inside a `bash -c` code word, an `env -S` argument, or any command with eval, source, a pipe into a shell, or a here-string | PreToolUse Bash | all | BLOCK: "$'...' text with backslash escapes is run as code here (...), so the guard cannot read it." | r1-ansi-escaped-bash-c, r1-ansi-escaped-eval |
| Plain `$'…'` / `$"…"` | Read as ordinary quoting, so the existing sed -i, ed/ex, watcher and territory rules apply | PreToolUse Bash | all | (existing blocks) | r1-ansi-quoted-sed, r1-ansi-quoted-tail-f, r1-ansi-quoted-watch, r1-locale-quoted-sed |
| `bash -c` code word | First word after `-c` that is not an option, an `-o`/`-O` argument, a redirection target or a descriptor number; line continuations skipped | PreToolUse Bash | all | (code analyzed by the existing rules) | r1-bash-c-dashdash, r1-bash-c-option-after-c, r1-bash-c-o-pipefail, r1-bash-c-redirect-first, r1-bash-c-dup-first, r1-bash-c-line-continuation; allowed: r1-bash-c-dashdash-allowed |
| `env -S` / `--split-string` | Argument (separate, attached, or `=`) unwrapped as code | PreToolUse Bash | all | (code analyzed) | r1-env-S, r1-env-S-attached, r1-env-split-string, r1-env-S-redirect; allowed: r1-env-S-allowed |
| `exec -a` | `-a name` skipped as an option argument | PreToolUse Bash | all | (command analyzed) | r1-exec-a |
| Writers under xargs | `xargs [opts] {touch,tee,cp,mv,install,ln,rsync,unlink,truncate,chmod,chown,patch,dd,split,g-variants}` | PreToolUse Bash | all | BLOCK: "<cmd> run through xargs takes its paths from input the guard cannot see, so it cannot check where it writes." / "use a literal path: run <cmd> with literal operands inside your own territory or under /private/tmp/claude-*; never touch test reports or the test marker." | r2-xargs-touch-reports, r2-xargs-touch-marker, r2-xargs-touch-main, r2-xargs-touch-other, r2-xargs-cp-one-operand, r2-xargs-I-cp, r2-xargs-0-tee; allowed: r2-xargs-grep-allowed, r2-xargs-echo-allowed |
| touch on reports / marker (pinned) | Literal touch targets in `/test-reports/` or `.claude/.last-test-run`; `find -exec touch {}` | PreToolUse Bash | all | BLOCK (existing messages) | r2-touch-r-marker, r2-touch-c-report-abs, r2-find-exec-touch-reports |
| Ask deferral | git commit/push (main session), AWS mutation, main-session constitutional shell command | PreToolUse Bash | main session (subagents already blocked) | ASK only if no block rule fires anywhere in the command | r4-commit-ask-then-sed-i, r4-commit-ask-then-tail-f, r4-commit-ask-then-redirect, r4-aws-ask-then-watch (BLOCK); r4-push-alone-still-asks, r4-aws-alone-still-asks, a01-main-commit, e-main-chmod-hook, e-main-git-checkout (ASK) |
| Stop hook: sbt timestamp | Each fresh TEST-*.xml needs `timestamp` > changed file's local mtime | SubagentStop | subagents | BLOCK reason: "report <r> has no sbt timestamp later than <file> (touched, copied or stale report: ...)" | s29-s34 |
| Stop hook: read-only exemption | Own agent file with read-only tools (rules in section 2) | SubagentStop only | read-only subagents (code-reviewer today) | ALLOW, with message "... cannot have changed source; its stop is not gated ..." | s35 (allow); s36-s48 (block) |

The decision 9 expected-allowed cases are unchanged and still traced ALLOW: i-sbt-background-main, i-sbt-background-sub, i-grep-runs, i-write-own-territory, i-tail-n, i-bash-n. They were also observed live: my redirect into the scratchpad, `tail -n`, `bash -n` and grep all passed the installed guard.

## 8. Diff (this repair pass)

### .claude/hooks/guard-dangerous.sh (step-1 version -> repaired; `git diff --no-index -U1`)
```diff
@@ -13,4 +13,5 @@
 #      KMS, EMR, ECS), destructive database/graph statements, remote-code piping.
-#      Amendment 02 adds a command-position parser (quote-aware; unwraps `bash -c`,
-#      `$(...)`, backticks, eval, pipes into a shell, xargs and find -exec) for:
+#      Amendment 02 adds a command-position parser (quote-aware, including $'...' and
+#      $"..."; unwraps `bash -c` (also after `--` and other options), `env -S`,
+#      `$(...)`, backticks, eval, pipes into a shell, xargs, exec -a and find -exec) for:
 #      in-place editing (sed -i, perl -i, ed/ex/vi), watchers (tail -f/-F/--follow,
@@ -20,3 +21,6 @@
 #      sbt test reports, constitutional files, evidence, memory and local settings
-#      are blocked.
+#      are blocked. File-writing commands run through xargs are blocked (their paths
+#      come from input the guard cannot see). $'...' text with backslash escapes is
+#      blocked where it names or feeds a checked command or is run as code.
+#      Every block rule runs before any ask is returned (asks are deferred).
 #   B. File-path guard for Read/Grep/Write/Edit/MultiEdit/NotebookEdit: secret paths,
@@ -52,2 +56,10 @@ ask() {
 
+# defer_ask <reason> : Bash guard only. Remember the first ask; it is returned only after
+# every block rule has run, so an earlier ask can never hide a later block.
+PENDING_ASK=""
+defer_ask() {
+  [ -z "$PENDING_ASK" ] && PENDING_ASK="$1"
+  return 0
+}
+
 # jget <dotted.path> : print a string field from the event, or nothing.
@@ -100,2 +112,3 @@ MSG_WATCH="No watchers: read with tail -n or grep, and finish the step."
 MSG_REPORTS="Run the tests and let sbt write the reports: sbt -batch \"<project>/testOnly <suites>\", or sbt check."
+MSG_ANSI="write it plainly, without \$'...' backslash escapes (plain single or double quotes are fine)."
 
@@ -193,7 +206,11 @@ normpath() {
 # that `grep -n '->' f` has no redirection and `grep "a; sed -i" f` has no sed command.
-# Code that the shell will run is unwrapped onto separate lines: the argument of a
-# shell's -c option, $(...) and backtick substitutions (also inside double quotes), and
+# Code that the shell will run is unwrapped onto separate lines: the code argument of a
+# shell's -c option (the first word after -c that is not an option, an option argument
+# of -o/-O, a redirection or a file-descriptor number), the argument of env -S /
+# --split-string, $(...) and backtick substitutions (also inside double quotes), and
 # every quoted string when the command uses eval or source, pipes into a shell, or
-# feeds a here-string. The quote characters themselves are removed, so 'sed', "sed"
-# and s\ed all read as sed. Placeholders $SCCODE / $SCSUB stand for unwrapped code.
+# feeds a here-string. The quote characters themselves are removed, so 'sed', "sed",
+# $'sed', $"sed" and s\ed all read as sed. A $'...' string that holds a backslash
+# escape is not decoded: it is marked $SCANSI (and $SCANSICODE when it is run as code)
+# so that the guard can refuse it. Placeholders $SCCODE / $SCSUB stand for unwrapped code.
 # ----------------------------------------------------------------------------------
@@ -204,3 +221,3 @@ BEGIN {
   M[")"] = "\021"; M["\n"] = "\022"; M["`"] = "\023"
-  EXTRA = ""
+  EXTRA = ""; ANSICODE = 0
 }
@@ -214,2 +231,3 @@ END {
   o = norm(SRC, 0)
+  if (ANSICODE) EXTRA = EXTRA "\n$SCANSICODE"
   printf "%s\n%s\n", o, EXTRA
@@ -229,5 +247,10 @@ function match_paren(s, p,    n, d, k, c) {
 function isshell(w) { sub(/.*\//, "", w); return (w ~ /^(ba|z|da|k)?sh$/) }
-function norm(s, depth,    out, n, i, j, c, k, e, E, buf, q, word, prev, cwa, cw, code, shellseen) {
+function isenv(w) { sub(/.*\//, "", w); return (w == "env" || w == "genv") }
+# raw   : the current word as the shell passes it (quotes removed, substitutions kept)
+# cmode : 1 = after a shell -c, the next non-option word is code; 2 = after env -S
+function norm(s, depth,    out, n, i, j, c, k, e, E, buf, rb, q, raw, inw, ansi, hard, shellseen, envseen, cmode, cskip, rtarget) {
   if (depth > 6) return s
-  out = ""; n = length(s); i = 1; word = ""; prev = ""; cwa = 0; cw = ""; shellseen = 0
+  s = s " "
+  out = ""; n = length(s); i = 1; raw = ""; inw = 0; ansi = 0
+  shellseen = 0; envseen = 0; cmode = 0; cskip = 0; rtarget = 0
   while (i <= n) {
@@ -235,8 +258,30 @@
     if (c == " " || c == "\t" || c == "\n" || c == ";" || c == "&" || c == "|" || c == "(" || c == ")" || c == "<" || c == ">") {
-      if (cwa) { addcode(cw, depth); out = out "$SCCODE"; prev = "$SCCODE"; cwa = 0; cw = ""; word = "" }
-      else if (word != "") { if (isshell(word)) shellseen = 1; prev = word; word = "" }
-      if (c != " " && c != "\t" && c != "<" && c != ">") { shellseen = 0; prev = "" }
+      if (inw) {
+        if (rtarget) rtarget = 0
+        else if ((c == ">" || c == "<") && raw ~ /^[0-9]*$/) rtarget = 0
+        else if (cmode) {
+          if (cmode == 1 && cskip) cskip = 0
+          else if (cmode == 1 && raw ~ /^[-+]/) { if (raw ~ /^[-+][oO]$/) cskip = 1 }
+          else { if (ansi) ANSICODE = 1; addcode(raw, depth); cmode = 0 }
+        } else {
+          if (isshell(raw)) shellseen = 1
+          else if (shellseen && raw ~ /^-[A-Za-z]*c[A-Za-z]*$/) cmode = 1
+          if (isenv(raw)) envseen = 1
+          else if (envseen) {
+            if (raw ~ /^-[A-Za-z]*S$/ || raw == "--split-string") cmode = 2
+            else if (raw ~ /^-[A-Za-z]*S./) { if (ansi) ANSICODE = 1; addcode(substr(raw, index(raw, "S") + 1), depth) }
+            else if (raw ~ /^--split-string=/) { if (ansi) ANSICODE = 1; addcode(substr(raw, 16), depth) }
+          }
+        }
+        raw = ""; inw = 0; ansi = 0
+      }
+      hard = (c == ";" || c == "\n" || c == "(" || c == ")")
+      if (c == "&") hard = !(substr(s, i + 1, 1) == ">" || (i > 1 && (substr(s, i - 1, 1) == ">" || substr(s, i - 1, 1) == "<")))
+      if (c == "|") hard = !(i > 1 && substr(s, i - 1, 1) == ">")
+      if (hard) { shellseen = 0; envseen = 0; cmode = 0; cskip = 0; rtarget = 0 }
+      else if (c == "<" || c == ">") rtarget = 1
       out = out c; i++; continue
     }
-    if (word == "" && !cwa && shellseen && prev ~ /^-[A-Za-z]*c[A-Za-z]*$/) { cwa = 1; cw = ""; word = "$SCCODE" }
+    if (c == "\\" && substr(s, i + 1, 1) == "\n") { i += 2; continue }
+    inw = 1
     if (c == "\\") {
@@ -244,5 +289,4 @@
       i += 2
-      if (k == "\n" || k == "") continue
-      if (cwa) cw = cw k
-      else { out = out mk(k); word = word k }
+      if (k == "") continue
+      out = out mk(k); raw = raw k
       continue
@@ -251,4 +295,3 @@
       E = match_paren(s, i + 1); if (E == 0) E = n + 1
-      if (cwa) cw = cw substr(s, i, E - i + 1)
-      else { addcode(substr(s, i + 2, E - i - 2), depth); out = out "$SCSUB"; word = word "$SCSUB" }
+      addcode(substr(s, i + 2, E - i - 2), depth); out = out "$SCSUB"; raw = raw substr(s, i, E - i + 1)
       i = E + 1; continue
@@ -257,6 +300,23 @@
       e = index(substr(s, i + 1), "`"); E = (e == 0) ? n + 1 : i + e
-      if (cwa) cw = cw substr(s, i, E - i + 1)
-      else { addcode(substr(s, i + 1, E - i - 1), depth); out = out "$SCSUB"; word = word "$SCSUB" }
+      addcode(substr(s, i + 1, E - i - 1), depth); out = out "$SCSUB"; raw = raw substr(s, i, E - i + 1)
       i = E + 1; continue
     }
+    if (c == "$" && substr(s, i + 1, 1) == SQ) {
+      j = i + 2; buf = ""; e = 0
+      while (j <= n) {
+        k = substr(s, j, 1)
+        if (k == "\\" && j < n) { buf = buf k substr(s, j + 1, 1); e = 1; j += 2; continue }
+        if (k == SQ) break
+        buf = buf k; j++
+      }
+      i = j + 1
+      if (e) {
+        ansi = 1
+        if (ALLCODE) ANSICODE = 1
+        out = out "$SCANSI" maskall(buf); raw = raw "$SCANSI" buf
+      } else if (ALLCODE) { addcode(buf, depth); out = out "$SCCODE"; raw = raw buf }
+      else { out = out maskall(buf); raw = raw buf }
+      continue
+    }
+    if (c == "$" && substr(s, i + 1, 1) == "\"") { i++; continue }
     if (c == SQ) {
@@ -264,5 +324,5 @@
       buf = substr(s, i + 1, E - i - 1); i = E + 1
-      if (cwa) cw = cw buf
-      else if (ALLCODE) { addcode(buf, depth); out = out "$SCCODE"; word = word "$SCCODE" }
-      else { out = out maskall(buf); word = word buf }
+      if (ALLCODE) { addcode(buf, depth); out = out "$SCCODE" }
+      else out = out maskall(buf)
+      raw = raw buf
       continue
@@ -270,4 +330,3 @@
     if (c == "\"") {
-      code = (cwa || ALLCODE)
-      j = i + 1; buf = ""; q = ""
+      j = i + 1; rb = ""; q = ""
       while (j <= n) {
@@ -275,28 +334,23 @@
         if (k == "\"") break
-        if (k == "\\" && j < n) { k = substr(s, j + 1, 1); buf = buf k; q = q mk(k); j += 2; continue }
-        if (k == "$" && substr(s, j + 1, 1) == "(") {
-          E = match_paren(s, j + 1); if (E == 0) E = n + 1
-          if (code) buf = buf substr(s, j, E - j + 1)
-          else { addcode(substr(s, j + 2, E - j - 2), depth); buf = buf "$SCSUB"; q = q "$SCSUB" }
-          j = E + 1; continue
-        }
-        if (k == "`") {
-          e = index(substr(s, j + 1), "`"); E = (e == 0) ? n + 1 : j + e
-          if (code) buf = buf substr(s, j, E - j + 1)
-          else { addcode(substr(s, j + 1, E - j - 1), depth); buf = buf "$SCSUB"; q = q "$SCSUB" }
+        if (k == "\\" && j < n) { k = substr(s, j + 1, 1); rb = rb k; q = q mk(k); j += 2; continue }
+        if (k == "`" || (k == "$" && substr(s, j + 1, 1) == "(")) {
+          if (k == "`") { e = index(substr(s, j + 1), "`"); E = (e == 0) ? n + 1 : j + e; buf = substr(s, j + 1, E - j - 1) }
+          else { E = match_paren(s, j + 1); if (E == 0) E = n + 1; buf = substr(s, j + 2, E - j - 2) }
+          if (!ALLCODE) addcode(buf, depth)
+          rb = rb substr(s, j, E - j + 1); q = q "$SCSUB"
           j = E + 1; continue
         }
-        buf = buf k; q = q mk(k); j++
+        rb = rb k; q = q mk(k); j++
       }
       i = j + 1
-      if (cwa) cw = cw buf
-      else if (ALLCODE) { addcode(buf, depth); out = out "$SCCODE"; word = word "$SCCODE" }
-      else { out = out q; word = word buf }
+      if (ALLCODE) { addcode(rb, depth); out = out "$SCCODE" }
+      else out = out q
+      raw = raw rb
       continue
     }
-    if (cwa) cw = cw c
-    else { out = out c; word = word c }
+    out = out c; raw = raw c
     i++
   }
-  if (cwa) { addcode(cw, depth); out = out "$SCCODE" }
+  if (inw && cmode) { if (ansi) ANSICODE = 1; addcode(raw, depth) }
+  if (substr(out, length(out), 1) == " ") out = substr(out, 1, length(out) - 1)
   return out
@@ -327,3 +381,4 @@ wrapper_optarg() {
     doas) case "$2" in -u|-C) return 0 ;; esac ;;
-    env) case "$2" in -u|-C|-S|-P) return 0 ;; esac ;;
+    env) case "$2" in -u|-C|-S|-P|--unset|--chdir|--split-string) return 0 ;; esac ;;
+    exec) case "$2" in -a) return 0 ;; esac ;;
     nice) case "$2" in -n) return 0 ;; esac ;;
@@ -346,3 +401,3 @@ $1"; }
 analyze_segment() {
-  local w name cmd a sub inexec nonopt last tdir dashdash skip
+  local w name cmd a sub inexec nonopt last tdir dashdash skip viax=0
   set -- $1
@@ -357,2 +412,3 @@ analyze_segment() {
       command|builtin|exec|nohup|time|sudo|doas|env|nice|xargs|stdbuf|timeout|gtimeout|caffeinate|unbuffer|setsid|ionice|eval|source|then|do|else|elif|if|while|until|'!'|'{'|'}')
+        [ "$w" = xargs ] && viax=1
         shift
@@ -378,2 +434,6 @@ analyze_segment() {
   [ $# -eq 0 ] && return 0
+  case "$1" in
+    *'$SCANSI'*)
+      block "the command word is \$'...' text with backslash escapes, so the guard cannot read which command runs." "$MSG_ANSI" ;;
+  esac
   cmd="${1##*/}"
@@ -381,2 +441,23 @@ analyze_segment() {
 
+  # Commands the guard inspects: an argument written as $'...' with backslash escapes
+  # could hide an option (-i, -f, -t, -exec) or a path, so it is refused.
+  case "$cmd" in
+    sed|gsed|perl|perl5*|ed|red|ex|vi|vim|nvim|view|vimdiff|gvim|tail|gtail|watch|less|cd|pushd|popd|tee|gtee|touch|gtouch|cp|mv|install|ln|rsync|gcp|gmv|ginstall|gln|rm|unlink|truncate|chmod|chown|patch|dd|gsplit|split|git|find|gfind)
+      for a in "$@"; do
+        case "$a" in
+          *'$SCANSI'*) block "an argument of $cmd is \$'...' text with backslash escapes, so the guard cannot read it." "$MSG_ANSI" ;;
+        esac
+      done ;;
+  esac
+
+  # File-changing commands run through xargs take their paths from input the guard
+  # cannot see (for example `find ... | xargs touch` on test reports or the marker).
+  if [ "$viax" = 1 ]; then
+    case "$cmd" in
+      tee|gtee|touch|gtouch|cp|mv|install|ln|rsync|gcp|gmv|ginstall|gln|unlink|truncate|chmod|chown|patch|dd|gsplit|split)
+        block "$cmd run through xargs takes its paths from input the guard cannot see, so it cannot check where it writes." \
+          "use a literal path: run $cmd with literal operands inside your own territory or under /private/tmp/claude-*; never touch test reports or the test marker." ;;
+    esac
+  fi
+
   case "$cmd" in
@@ -562,3 +643,5 @@ check_write_target() {
 # ==================================================================================
-# A. Bash command guard
+# A. Bash command guard. Every rule below either blocks (exit 2 at once) or defers an
+# ask; the deferred ask is returned by the dispatcher only after guard_bash has run
+# every block rule.
 # ==================================================================================
@@ -595,3 +678,3 @@ guard_bash() {
     fi
-    ask "git commit / git push: only the human architect commits and pushes. Approve only if the human is performing this action."
+    defer_ask "git commit / git push: only the human architect commits and pushes. Approve only if the human is performing this action."
   fi
@@ -677,3 +760,3 @@ EOF
     if matches "$cmd" '[[:space:]](create|put|update|...|submit)(-[a-z0-9-]+)?([[:space:]]|$)|[[:space:]]s3[[:space:]]+(cp|sync)([[:space:]]|$)'; then
-      ask "direct AWS mutation. All AWS infrastructure is Terraform-managed and operational writes go through approved scripts; console or ad-hoc CLI mutation is not an implementation path. Approve only if this is a documented, bounded action."
+      defer_ask "direct AWS mutation. All AWS infrastructure is Terraform-managed and operational writes go through approved scripts; console or ad-hoc CLI mutation is not an implementation path. Approve only if this is a documented, bounded action."
     fi
@@ -696,2 +779,6 @@ EOF
   norm="$(printf '%s' "$cmd" | awk -v SQ="'" "$NORM_AWK")"
+  case "$norm" in
+    *'$SCANSICODE'*)
+      block "\$'...' text with backslash escapes is run as code here (bash -c, env -S, eval, source, a pipe into a shell, or a here-string), so the guard cannot read it." "$MSG_ANSI" ;;
+  esac
   # Normalize "N>" to ">", then collect redirection targets.
@@ -748,6 +835,7 @@ EOF
     if [ -z "$AGENT" ]; then
-      ask "shell command changing constitutional files or human-owned evidence (CLAUDE.md, docs/agents.md, .claude/**, .mcp.json, docs/agent-runs/**). Constitutional edits belong to factory-engineer via Write/Edit; evidence belongs to the human."
+      defer_ask "shell command changing constitutional files or human-owned evidence (CLAUDE.md, docs/agents.md, .claude/**, .mcp.json, docs/agent-runs/**). Constitutional edits belong to factory-engineer via Write/Edit; evidence belongs to the human."
+    else
+      block "shell writes to constitutional files or docs/agent-runs/** are denied for subagent '$AGENT'." \
+        "Constitutional changes route to factory-engineer through the orchestrator; docs/agent-runs/** is human-owned evidence."
     fi
-    block "shell writes to constitutional files or docs/agent-runs/** are denied for subagent '$AGENT'." \
-      "Constitutional changes route to factory-engineer through the orchestrator; docs/agent-runs/** is human-owned evidence."
   fi
@@ -841,2 +929,4 @@ case "$TOOL" in
     guard_bash "$(jget tool_input.command)"
+    # Every block rule has run; only now may a deferred ask be returned.
+    [ -n "$PENDING_ASK" ] && ask "$PENDING_ASK"
     ;;
```
(The long AWS-ask regex on the context line is shortened with "..." only here; it is unchanged in the file.)

### .claude/hooks/verify-tests-ran.sh (step-1 version -> repaired)
```diff
@@ -3,3 +3,4 @@
-# Refuses completion when relevant source changed after the last successful test run.
+# Refuses completion when relevant source changed after the last successful test run
+# (the specification's rule: it "prevents a source-changing agent from stopping").
@@ -16,2 +17,8 @@
 #     (Stop and SubagentStop);
+#   - SubagentStop only (amendment-02 repair, human decision A): the subagent's own
+#     file .claude/agents/<agent_type>.md exists, its frontmatter names it, and its
+#     single tools field lists none of Write, Edit, MultiEdit, NotebookEdit or Bash.
+#     Such an agent cannot change source, so it is not a source-changing agent. Fail
+#     closed: an empty or unusual agent_type, a missing file, or a missing, empty,
+#     multi-line, wildcard or repeated tools field means no exemption.
@@ -24,5 +31,15 @@
-#     Covered: in each folder at least one TEST-*.xml is newer than the project's newest
-#     changed file, and none of those newer reports has failures or errors above 0 (a
-#     report whose <testsuite> element cannot be read counts as failing).
+#     Covered: in each folder at least one TEST-*.xml is newer (file mtime) than the
+#     project's newest changed file, and every one of those newer reports
+#       - has failures="0" and errors="0" on its <testsuite> element, and
+#       - carries the timestamp="YYYY-MM-DDThh:mm:ss" attribute sbt writes inside the
+#         file (touch cannot change it), strictly later than the changed file's mtime.
+#     sbt writes that timestamp in local time with no zone. It is compared with the
+#     changed file's mtime rendered in the same local time as YYYYMMDDhhmmss (BSD
+#     `stat -f %Sm -t`, else GNU `date -r`); both must be exactly 14 digits and are
+#     compared as integers (no python, no GNU-only date flags on macOS).
+#     A report whose element, counts or timestamp cannot be read, a timestamp with a
+#     zone suffix, or an mtime that cannot be rendered counts as not covering (fail
+#     closed). Whole-second precision: a report from the same second as the change
+#     does not cover it.
@@ -62,2 +79,3 @@ EVENT="$(jget hook_event_name)"
 [ "$EVENT" = "SubagentStop" ] || EVENT="Stop"
+AGENT_TYPE="$(jget agent_type)"
@@ -67,2 +85,20 @@ mtime() {
+# is14 <text> : true when text is exactly 14 digits (YYYYMMDDhhmmss).
+is14() {
+  case "$1" in
+    [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) return 0 ;;
+  esac
+  return 1
+}
+
+# local_mtime <file> : the file's mtime as local time YYYYMMDDhhmmss, or nothing.
+local_mtime() {
+  local out
+  out="$(stat -f %Sm -t %Y%m%d%H%M%S "$1" 2>/dev/null)"
+  if is14 "$out"; then printf '%s' "$out"; return 0; fi
+  out="$(date -r "$1" +%Y%m%d%H%M%S 2>/dev/null)"
+  if is14 "$out"; then printf '%s' "$out"; return 0; fi
+  return 1
+}
@@ -124,2 +160,46 @@ fi
+# ----------------------------------------------------------------------------------
+# Read-only subagents (SubagentStop only; amendment-02 repair, human decision A).
+# ----------------------------------------------------------------------------------
+# read_only_agent <agent_type> : true only when .claude/agents/<agent_type>.md exists,
+# starts with a frontmatter block whose name is <agent_type>, and has exactly one
+# one-line tools field that is non-empty, has no wildcard, and names none of Write,
+# Edit, MultiEdit, NotebookEdit or Bash (also not as Bash(...)). Anything else: false.
+read_only_agent() {
+  local t="$1" f fm n tools item lc
+  [ -n "$t" ] || return 1
+  case "$t" in
+    -*|*[!A-Za-z0-9_-]*) return 1 ;;
+  esac
+  f=".claude/agents/$t.md"
+  [ -f "$f" ] && [ -r "$f" ] || return 1
+  [ "$(head -n 1 "$f" | tr -d '\r')" = "---" ] || return 1
+  # frontmatter: line 2 up to the closing '---' line, which must exist
+  fm="$(sed -n '2,/^---[[:space:]]*$/p' "$f" | tr -d '\r')"
+  printf '%s\n' "$fm" | tail -n 1 | grep -Eq '^---[[:space:]]*$' || return 1
+  fm="$(printf '%s\n' "$fm" | sed '$d')"
+  [ -n "$fm" ] || return 1
+  printf '%s\n' "$fm" | grep -Eq "^name:[[:space:]]*[\"']?$t[\"']?[[:space:]]*$" || return 1
+  n="$(printf '%s\n' "$fm" | grep -c '^tools:')"
+  [ "$n" = 1 ] || return 1
+  # drop [ ] " ' (YAML flow-list brackets and quotes)
+  tools="$(printf '%s\n' "$fm" | sed -n 's/^tools:[[:space:]]*//p' | tr -d '\133\135\042\047')"
+  [ -n "$(printf '%s' "$tools" | tr -d ' \t,')" ] || return 1
+  case "$tools" in *'*'*) return 1 ;; esac
+  local IFS=$', \t'     # split on commas and blanks, so "Read Grep Bash" is three items
+  for item in $tools; do
+    item="${item%%\(*}"
+    lc="$(printf '%s' "$item" | tr -d ' \t' | tr 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' 'abcdefghijklmnopqrstuvwxyz')"
+    case "$lc" in
+      write|edit|multiedit|notebookedit|bash) return 1 ;;
+    esac
+  done
+  return 0
+}
+
+if [ "$EVENT" = "SubagentStop" ] && read_only_agent "$AGENT_TYPE"; then
+  echo "verify-tests-ran.sh: subagent '$AGENT_TYPE' has no Write, Edit, MultiEdit, NotebookEdit or Bash tool (.claude/agents/$AGENT_TYPE.md), so it cannot have changed source; its stop is not gated (test marker $STATE). The source-changing agent and the main session are still gated." >&2
+  exit 0
+fi
@@ -168,9 +248,14 @@ newer() {
+# suite_head <xml> : the first <testsuite ...> start tag, on one line.
+suite_head() {
+  head -c 8192 "$1" 2>/dev/null | tr '\n\r' '  ' | grep -Eo '<testsuite[[:space:]][^>]*>' | head -n 1
+}
+
 # report_passes <xml> : true when the <testsuite> element has failures="0" and errors="0".
 report_passes() {
-  local head
-  head="$(head -c 8192 "$1" 2>/dev/null | tr '\n\r' '  ' | grep -Eo '<testsuite[[:space:]][^>]*>' | head -n 1)"
-  [ -n "$head" ] || return 1
-  printf '%s' "$head" | grep -Eq "[[:space:]]failures=[\"']0[\"']" || return 1
-  printf '%s' "$head" | grep -Eq "[[:space:]]errors=[\"']0[\"']" || return 1
+  local h
+  h="$(suite_head "$1")"
+  [ -n "$h" ] || return 1
+  printf '%s' "$h" | grep -Eq "[[:space:]]failures=[\"']0[\"']" || return 1
+  printf '%s' "$h" | grep -Eq "[[:space:]]errors=[\"']0[\"']" || return 1
   return 0
@@ -178,2 +263,15 @@ report_passes() {
+# report_after <xml> <since> : true when the timestamp attribute sbt wrote inside the
+# report (local time, no zone; optional fractional seconds) is strictly later than
+# <since> (YYYYMMDDhhmmss in local time). Unreadable or zoned timestamps: false.
+report_after() {
+  local h ts
+  is14 "$2" || return 1
+  h="$(suite_head "$1")"
+  [ -n "$h" ] || return 1
+  ts="$(printf '%s' "$h" | sed -n -E "s/.*[[:space:]]timestamp=[\"']([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(\.[0-9]+)?[\"'].*/\1\2\3\4\5\6/p")"
+  is14 "$ts" || return 1
+  [ "$ts" -gt "$2" ]
+}
@@ -216,2 +314,6 @@ EOF
     reason=""
+    since="$(local_mtime "$pnewest")"
+    if ! is14 "$since"; then
+      reason="$reason cannot render the local mtime of $pnewest, so sbt report timestamps cannot be compared;"
+    fi
@@ -232,2 +334,4 @@ EOF
           reason="$reason failing or unreadable report $r;"
+        elif ! report_after "$r" "$since"; then
+          reason="$reason report $r has no sbt timestamp later than $pnewest (touched, copied or stale report: its file time is new but sbt did not write it after the change);"
         fi
```

### .claude/settings.json (allow list, after "Bash(sbt scalafmtCheckAll)")
```diff
       "Bash(sbt scalafmtCheckAll)",
+      "Bash(sbt -batch:*)",
+      "Bash(grep:*)",
+      "Bash(head:*)",
+      "Bash(tail -n:*)",
+      "Bash(ls:*)",
+      "Bash(wc:*)",
+      "Bash(shasum:*)",
+      "Bash(sort:*)",
+      "Bash(uniq:*)",
+      "Bash(cut:*)",
+      "Bash(sed -n:*)",
       "Bash(bash -n:*)",
```

### docs/agents.md (sentences appended to three rows of "Hooks and permission floor")
- **PreToolUse guard row** (line 136) gains this text at the end: "Amendment 02 repair: `$'...'` and `$"..."` quoting is read like plain quoting; `bash -c` code is found after `--`, other options, `-o`/`-O` arguments and redirections; `env -S`/`--split-string` arguments and `exec -a` are unwrapped; `$'...'` text with backslash escapes is blocked when it is a command word, an argument of a command the guard inspects, or run as code; file-writing commands run through `xargs` (`touch`, `tee`, `cp`, `mv`, `install`, `ln`, `rsync`, `chmod` and similar) are blocked ("use a literal path"); every block rule runs before any ask is returned, so an earlier ask never hides a later block."
- **Stop and SubagentStop gate row** (line 139): the "Covered means" sentence now reads: "Covered means at least one `TEST-*.xml` is newer than the project's newest changed file, none of those newer reports has `failures` or `errors` above 0, and each of them carries the `timestamp` attribute sbt writes inside the report (local time, no zone) strictly later than that file's modification time (repair: touching an old report does not make it fresh; an unreadable or zoned timestamp does not count)." Before "No agent may weaken this further." it adds: "Repair, human decision A, matching the specification's wording (the gate "prevents a source-changing agent from stopping"): on SubagentStop only, a subagent whose own `.claude/agents/<agent_type>.md` exists and whose `tools` field names none of Write, Edit, MultiEdit, NotebookEdit or Bash (today: `code-reviewer`) is not a source-changing agent and is not gated; a missing file, an unreadable `tools` field, or an empty `agent_type` means no exemption (fail closed). The main-session Stop rule is unchanged."
- **Permission rules row** (line 141) gains: "Amendment 02 repair: the allow list also covers `sbt -batch`, `grep`, `head`, `tail -n`, `ls`, `wc`, `shasum`, `sort`, `uniq`, `cut`, and `sed -n` (prefix rules), so unattended runs do not stall on prompts; the guard still runs first on every one of them."

### .claude/agents/factory-engineer.md:60 (Test-stop floor invariant)
Inserted before "No change may weaken it further.": "A fresh report also needs sbt's own `timestamp` attribute later than the change. Per human decision A (amendment-02 repair), matching the specification's wording that the gate "prevents a source-changing agent from stopping": on SubagentStop only, a subagent whose own `.claude/agents/<agent_type>.md` exists and whose `tools` field names none of Write, Edit, MultiEdit, NotebookEdit or Bash is not gated; a missing file, an unreadable `tools` field or an empty `agent_type` means no exemption."

### 10 role files (finding 7), one replacement each
```diff
-... run the full `sbt check` when the work order makes you the wave closer ...
+... run the full `sbt check` when you are the wave closer (named in the work order; if it names none, the last source-changing agent of the wave, which the orchestrator tells you when it invokes you) ...
```
Files: build-engineer.md:36, dataset-engineer.md:29, domain-engineer.md:30, hadoop-engineer.md:22, rag-engineer.md:24, service-engineer.md:22, storage-engineer.md:22, test-engineer.md:30, entity-resolution-engineer.md:22, spark-engineer.md:23.

### .claude/hook-tests/guard-selftest.sh: section inserted before "== Regression ==" (verbatim)
```bash
echo "== Amendment 02 repair, finding 1: respellings (\$'...', \$\"...\", bash -c options, env -S, exec -a) =="
bash_case BLOCK r1-ansi-quoted-sed            rag-engineer "\$'sed' -i 's/a/b/' modules/core/X.scala" "Use the Edit tool."
bash_case BLOCK r1-ansi-quoted-tail-f         rag-engineer "\$'tail' -f README.md" "No watchers"
bash_case BLOCK r1-ansi-quoted-watch          rag-engineer "\$'watch' ls" "No watchers"
bash_case BLOCK r1-locale-quoted-sed          rag-engineer "\$\"sed\" -i s/a/b/ modules/rag/x" "Use the Edit tool."
bash_case BLOCK r1-ansi-escaped-command-word  rag-engineer "\$'s\\x65d' -i s/a/b/ modules/core/X.scala" "backslash escapes"
bash_case BLOCK r1-ansi-escaped-sed-option    rag-engineer "sed \$'-\\x69' s/a/b/ modules/rag/x" "backslash escapes"
bash_case BLOCK r1-ansi-escaped-cp-option     rag-engineer "cp \$'-\\x74' modules/core/ /tmp/claude-1/a" "backslash escapes"
bash_case BLOCK r1-ansi-escaped-bash-c        rag-engineer "bash -c \$'s\\x65d -i s/a/b/ modules/core/x'" "run as code"
bash_case BLOCK r1-ansi-escaped-eval          rag-engineer "eval \$'s\\x65d -i s/a/b/ modules/core/x'" "run as code"
bash_case BLOCK r1-bash-c-dashdash            rag-engineer "bash -c -- 'sed -i s/a/b/ modules/core/x'" "Use the Edit tool."
bash_case BLOCK r1-bash-c-option-after-c      rag-engineer "bash -c -e 'sed -i s/a/b/ modules/core/x'" "Use the Edit tool."
bash_case BLOCK r1-bash-c-o-pipefail          rag-engineer "bash -c -o pipefail 'tail -f /tmp/claude-1/x.log'" "No watchers"
bash_case BLOCK r1-bash-c-redirect-first      rag-engineer "bash -c 2>/dev/null 'sed -i s/a/b/ modules/core/x'" "Use the Edit tool."
bash_case BLOCK r1-bash-c-dup-first           rag-engineer "bash -c 2>&1 'sed -i s/a/b/ modules/core/x'" "Use the Edit tool."
bash_case BLOCK r1-bash-c-line-continuation   rag-engineer "$(printf 'bash -c \\\n%s' "'sed -i s/a/b/ modules/core/x'")" "Use the Edit tool."
bash_case BLOCK r1-env-S                      rag-engineer "env -S 'sed -i s/a/b/ modules/core/x'" "Use the Edit tool."
bash_case BLOCK r1-env-S-attached             rag-engineer "env -S'sed -i s/a/b/ modules/core/x'" "Use the Edit tool."
bash_case BLOCK r1-env-split-string           rag-engineer "env --split-string='tail -f /tmp/claude-1/x.log'" "No watchers"
bash_case BLOCK r1-env-S-redirect             rag-engineer "env -S 'echo x > modules/core/x'" "owned by domain-engineer"
bash_case BLOCK r1-exec-a                     rag-engineer "exec -a x sed -i s/a/b/ modules/core/x" "Use the Edit tool."
bash_case ALLOW r1-ansi-printf-allowed        rag-engineer "printf '%s\\n' \$'a\\tb'"
bash_case ALLOW r1-ansi-grep-allowed          "" "grep -n \$'\\t' CLAUDE.md"
bash_case ALLOW r1-bash-c-dashdash-allowed    rag-engineer "bash -c -- 'ls modules/rag'"
bash_case ALLOW r1-env-S-allowed              rag-engineer "env -S 'ls -la modules/rag'"

echo "== Amendment 02 repair, finding 2: writer commands through xargs; touch on reports or marker =="
bash_case BLOCK r2-xargs-touch-reports        domain-engineer "find modules/core -name 'TEST-*.xml' | xargs touch" "through xargs"
bash_case BLOCK r2-xargs-touch-marker         build-engineer "find .claude -name .last-test-run | xargs touch" "through xargs"
bash_case BLOCK r2-xargs-touch-main           "" "find modules -name 'TEST-*.xml' | xargs touch" "through xargs"
bash_case BLOCK r2-xargs-touch-other          rag-engineer "echo modules/core/A.scala | xargs touch" "through xargs"
bash_case BLOCK r2-xargs-cp-one-operand       rag-engineer "echo modules/core/a | xargs cp /tmp/claude-1/a" "through xargs"
bash_case BLOCK r2-xargs-I-cp                 rag-engineer "ls /tmp/claude-1 | xargs -I{} cp {} modules/rag/" "through xargs"
bash_case BLOCK r2-xargs-0-tee                rag-engineer "printf 'modules/core/x' | xargs -0 tee" "through xargs"
bash_case BLOCK r2-find-exec-touch-reports    domain-engineer "find modules/core/target -name 'TEST-*.xml' -exec touch {} +" "use a literal path"
bash_case BLOCK r2-touch-r-marker             build-engineer "touch -r build.sbt .claude/.last-test-run" "markTestRun"
bash_case BLOCK r2-touch-c-report-abs         domain-engineer "touch -c $ROOT/modules/core/target/jvm-3/test-reports/TEST-a.xml" "test reports"
bash_case ALLOW r2-xargs-grep-allowed         rag-engineer "find modules/rag -name '*.scala' | xargs grep -n x"
bash_case ALLOW r2-xargs-echo-allowed         "" "ls modules | xargs echo"

echo "== Amendment 02 repair, finding 4: every block rule runs before an ask =="
bash_case BLOCK r4-commit-ask-then-sed-i      "" "git commit -m x; sed -i s/a/b/ README.md" "Use the Edit tool."
bash_case BLOCK r4-commit-ask-then-tail-f     "" "git commit -m x; tail -f /tmp/claude-1/x.log" "No watchers"
bash_case BLOCK r4-commit-ask-then-redirect   "" "git commit -m x && echo y > README.md" "no write territory"
bash_case BLOCK r4-aws-ask-then-watch         "" "aws ec2 start-instances --instance-ids i-placeholder; watch ls" "No watchers"
bash_case ASK   r4-push-alone-still-asks      "" "git push origin main"
bash_case ASK   r4-aws-alone-still-asks       "" "aws ec2 start-instances --instance-ids i-placeholder"
```

### .claude/hook-tests/stop-hook-selftest.sh: changed sections (verbatim)
- **Header:** adds "Amendment 02 repair: a report whose file time is fresh but whose sbt timestamp attribute is not later than the change (touched, missing, zoned, same second) does not cover it; a read-only subagent (fixture .claude/agents files) is not gated on SubagentStop, while writing, unknown, empty or malformed agent types are."
- **New helper and fixtures:**
```bash
# agentfile <name> <content with \n escapes> : write .claude/agents/<name>.md in the repo.
agentfile() {
  mkdir -p "$(dirname "$R/.claude/agents/$1.md")"
  printf '%b' "$2" >"$R/.claude/agents/$1.md"
}
# in newrepo(), before the git commit:
  agentfile code-reviewer  '---\nname: code-reviewer\ndescription: read-only reviewer\ntools: Read, Grep, Glob\n---\n\n# Code Reviewer\n'
  agentfile service-engineer '---\nname: service-engineer\ndescription: writer\ntools: Read, Grep, Glob, Write, Edit, Bash\n---\n'
  agentfile bash-reader    '---\nname: bash-reader\ntools: Read, Bash(git status)\n---\n'
  agentfile no-tools       '---\nname: no-tools\ndescription: no tools field\n---\n'
  agentfile list-tools     '---\nname: list-tools\ntools:\n  - Read\n  - Write\n---\n'
  agentfile star-tools     '---\nname: star-tools\ntools: *\n---\n'
  agentfile spaced-tools   '---\nname: spaced-tools\ntools: Read Grep Write\n---\n'
  agentfile alias-reviewer '---\nname: code-reviewer\ntools: Read, Grep, Glob\n---\n'
  agentfile sub/x          '---\nname: sub/x\ntools: Read\n---\n'
  agentfile ""             '---\nname:\ntools: Read\n---\n'
  agentfile open-front     '---\nname: open-front\ntools: Read\n\nbody line\n'
```
- **report() and run_hook():**
```bash
tsattr() {
  local t="$1"
  printf '%s-%s-%sT%s:%s:%s' "${t:0:4}" "${t:4:2}" "${t:6:2}" "${t:8:2}" "${t:10:2}" "${t:13:2}"
}
# report <dir under repo> <name> <pass|fail|error|garbage> <file time> [sbt time]
report() {
  local d="$R/$1" f ts attr
  mkdir -p "$d"
  f="$d/TEST-$2.xml"
  ts="${5:-$4}"
  case "$ts" in
    none) attr="" ;;
    zoned:*) attr=" timestamp=\"$(tsattr "${ts#zoned:}")Z\"" ;;
    *) attr=" timestamp=\"$(tsattr "$ts")\"" ;;
  esac
  # pass/fail/error templates now end with: time="0.1"%s>  (the %s is $attr)
  ...
  touch -t "$4" "$f"
}
run_hook() {
  local ev
  ev="$(jq -n --arg e "$1" --arg m "$2" --argjson a "${3:-false}" --arg t "${4-none}" \
    '(if $e == "none" then {} else {hook_event_name: $e} end) + {stop_hook_active: $a, last_assistant_message: $m}
     + (if $t == "none" then {} else {agent_type: $t} end)')"
  ...
}
# expect(): run_hook "$ev" "$msg" "${6:-false}" "${7-none}"   (7th argument: agent_type)
```
- **New cases** (after s28):
```bash
echo "== repair, finding 2: the sbt timestamp inside each fresh report must be later than the change =="
TS_MSG="has no sbt timestamp later than"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH" "$T_STALE"
expect BLOCK s29-touched-old-report SubagentStop "$TS_MSG"
newrepo; change "$CORE_SRC"; report "$CORE3_REP" c.IdSuite pass "$T_FRESH" "$T_STALE"; report "$CORE2_REP" c.IdSuite pass "$T_FRESH" "$T_STALE"
expect BLOCK s30-core-both-builds-touched SubagentStop "$TS_MSG"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH" none
expect BLOCK s31-report-without-timestamp SubagentStop "$TS_MSG"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH" "$T_SRC"
expect BLOCK s32-timestamp-same-second-as-change SubagentStop "$TS_MSG"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH" "zoned:$T_FRESH"
expect BLOCK s33-timestamp-with-zone SubagentStop "$TS_MSG"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH"; report "$SVC_REP" a.BSuite pass "$T_FRESH" "$T_STALE"
expect BLOCK s34-one-touched-among-fresh SubagentStop "TEST-a.BSuite.xml $TS_MSG"

echo "== repair, decision A: read-only subagents are not gated on SubagentStop =="
newrepo; change "$SVC_SRC"
expect ALLOW s35-reviewer-exempt-while-services-untested SubagentStop "cannot have changed source" "STATUS: COMPLETE" false code-reviewer
expect BLOCK s36-reviewer-on-stop-event-not-exempt Stop "needs the full marker" "STATUS: COMPLETE" false code-reviewer
expect BLOCK s37-writing-agent-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false service-engineer
expect BLOCK s38-unknown-agent-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false general-purpose
expect BLOCK s39-empty-agent-type-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false ""
expect BLOCK s40-bash-form-tool-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false bash-reader
expect BLOCK s41-missing-tools-field-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false no-tools
expect BLOCK s42-multiline-tools-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false list-tools
expect BLOCK s43-wildcard-tools-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false star-tools
expect BLOCK s44-space-separated-tools-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false spaced-tools
expect BLOCK s45-name-mismatch-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false alias-reviewer
expect BLOCK s46-agent-type-with-slash-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false sub/x
expect BLOCK s47-unclosed-frontmatter-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false open-front
expect BLOCK s48-no-agent-type-not-exempt SubagentStop "Not covered"
```

## 9. Not repaired (human list)
| # | Severity | Finding (from review-report.md) | Note |
|---|---|---|---|
| 5 | minor | stop-hook self-test s25 (target/ skip) and s24 (deletion skip) pass even with their rule removed | Unchanged. s25 needs a fixture without `target/` in .gitignore; s24 is not discriminating by design. |
| 6 | minor | build-engineer.md:36 says `sbt check` both unconditionally and conditionally | Unchanged apart from the finding-7 phrase. The first sentence still reads "Run `sbt Test/compile`, then `sbt check`". |
| 8 | minor | docs/agents.md primary and lifecycle gates still list `sbt check` per agent | Unchanged; could be clarified as "the wave closer's gate". |
| 10 | minor | GNU long-option abbreviations (`gsed --in-pl`, `gtail --fo`) | Unchanged; only g-prefixed GNU tools are affected. |
| 11 | advisory | test-report and secret checks look at the written target, not the resolved path | Unchanged. |
| 12 | advisory | other never-ending commands (`sbt ~test`, `aws logs tail --follow`, `docker logs -f`, `journalctl -f`, `sleep infinity`) | Outside decision 7's list. |
| 13 | advisory | `tests/integration/**` is covered by any contractTests report | As decision 3a ordered. |
| 14 | advisory | b-touch/b-redirect/b-cp pass with either report rule removed; no cases for `>&file`, `/dev/stdout`, `install -d`, single-operand `ln` | Partly mitigated: the new r2-touch-c-report-abs (absolute path) still passes with either rule removed. |
| 15 | advisory | heredoc bodies are parsed as code (fail-safe false blocks) | Orchestrator practice: write scratch reports with the Write tool. |

## 10. Open items (risks and decisions found in this pass)
- **B1. Allow-list premise (decision needed).** "The guard still blocks anything dangerous inside them" is not fully true:
  - `sort -o FILE` / `sort --output=FILE` writes FILE (already a known guard gap, step-1 proposed-later #3);
  - `uniq IN OUT` writes OUT;
  - a `w FILE` command (or the `s///w FILE` flag) inside a `sed -n` script writes FILE;
  - `sbt -batch:*` allows any sbt task.
  - Before this pass these prompted; now they run unprompted. None of them can touch test reports or the marker by name, because the raw HAS_OP rules do not cover sort, uniq or sed, so that protection is missing too.
  - Options: (a) add guard targets for `sort -o`/`--output`, `uniq`'s second operand, and refuse sed scripts containing a `w`/`W` command (a later factory pass); (b) drop `sort`, `uniq` and `sed -n` from the allow list; (c) accept the risk.
  - I did not do (a): it is outside the ordered list.
- **B2. Backdating sources (pre-existing, now more relevant).** An agent may run `touch -t 202001010000 <own source file>`, which is allowed in its own territory. That makes old reports, and the old full marker, look newer than its change; with the timestamp check, it also makes an old timestamp look later. This defeats the stop gate without a test run.
  - Proposed later: block `touch` with `-t`, `-d`, `-r` or `-A`, and timestamp-preserving copies (`cp -p`, `rsync -t/-a`, `install -p`) onto relevant source. Or compare against git's view instead of mtimes.
- **B3. Local-time comparison.**
  - In the one fall-back DST hour a year, local wall-clock order can invert. An old report from the first 01:xx could then look later than a change in the second 01:xx.
  - If sbt's JVM and the hook run in different time zones, the comparison shifts by the offset.
  - Both are bounded and need the mtime check to be defeated as well.
- **B4. `parallel`** (GNU parallel) is not treated like `xargs`. It is not installed on macOS by default.
- **B5. New fail-safe false positives.**
  - A `$'…'` string with escapes now blocks anywhere in a command that also uses eval, source, a pipe into a shell, or `<<<` (for example `grep x <<< $'a\tb'`).
  - An escaped `$'…'` argument blocks for the inspected commands (for example `cd $'dir\tx'`).
- **B6. Normalizer runtime is still unobserved.** The restructured awk normalizer has not been executed against test events. If it hit an awk runtime error, the new shell rules would fail open (the raw rules are unaffected). Probe 1 shows this at once as many BLOCK failures.
- **B7. Spec quote.** The quoted spec phrase "prevents a source-changing agent from stopping" is taken from your decision text. I did not find it in a file I own.

## INTERFACE HANDOFF
**Rules that change (effective after ratification and restart):**
- **Read-only reviewer:**
  - code-reviewer, and any future agent whose `tools` field has none of Write, Edit, MultiEdit, NotebookEdit or Bash, may finish COMPLETE on SubagentStop while another agent's source change is still untested.
  - The source-changing agent and the main session (Stop) are still gated.
  - A new agent gets this only if its file in `.claude/agents/` has a one-line, comma-separated `tools:` field and a `name:` equal to its file name.
- **Wave closer:** when a work order names no wave closer, the orchestrator must tell the last source-changing agent of the wave, in its invocation, that it is the wave closer. That agent then runs the full `sbt check`.
- **Test evidence:**
  - A fresh sbt report counts only if sbt's own `timestamp` inside it is later than the change. Touching, copying or restoring old reports never helps.
  - Run `sbt -batch "<project>/testOnly <suites>"` after the last edit.
  - The edit and the test start must not fall in the same second. In practice compilation makes this automatic.
- **Shell:**
  - Do not pipe into `xargs` with a file-changing command (touch, tee, cp, mv, install, ln, rsync, chmod, chown, truncate, unlink, patch, dd, split). Use literal operands.
  - Do not write command names, checked options or code as `$'…'` with backslash escapes; plain quotes are fine. `printf`/`grep` arguments like `$'\t'` are still fine unless the command also uses eval, source, `| sh` or `<<<`.
  - A command that would ask (git commit/push in the main session, an AWS mutation, a constitutional shell change) and that also contains a blocked part is now blocked, not asked.
- **Permissions:** `sbt -batch …`, `grep`, `head`, `tail -n`, `ls`, `wc`, `shasum`, `sort`, `uniq`, `cut` and `sed -n` no longer prompt. The guard still runs first (see B1 for the gaps).

**New paths and owners:** none in this pass.

**New guard blocks.** Each is printed after "BLOCKED by .claude/hooks/guard-dangerous.sh: ", followed by "What to do instead: …" and the policy line.
- "the command word is $'...' text with backslash escapes, so the guard cannot read which command runs." / "write it plainly, without $'...' backslash escapes (plain single or double quotes are fine)."
- "an argument of <cmd> is $'...' text with backslash escapes, so the guard cannot read it." / same instruction.
- "$'...' text with backslash escapes is run as code here (bash -c, env -S, eval, source, a pipe into a shell, or a here-string), so the guard cannot read it." / same instruction.
- "<cmd> run through xargs takes its paths from input the guard cannot see, so it cannot check where it writes." / "use a literal path: run <cmd> with literal operands inside your own territory or under /private/tmp/claude-*; never touch test reports or the test marker."
- Existing block messages now also fire through `bash -c` with options (`--`, `-e`, `-o x`, or a redirection before the code), `env -S`/`--split-string`, `exec -a`, `$'cmd'` and `$"cmd"`.
- Ask texts are unchanged; they are now returned only when no block rule fires.

**Stop-hook messages:**
- **New allow (SubagentStop):** "verify-tests-ran.sh: subagent '<agent>' has no Write, Edit, MultiEdit, NotebookEdit or Bash tool (.claude/agents/<agent>.md), so it cannot have changed source; its stop is not gated (test marker <state>). The source-changing agent and the main session are still gated."
- **New block reasons inside "Not covered":**
  - "report <file> has no sbt timestamp later than <changed file> (touched, copied or stale report: its file time is new but sbt did not write it after the change);"
  - "cannot render the local mtime of <changed file>, so sbt report timestamps cannot be compared;"
- All other messages are unchanged.
