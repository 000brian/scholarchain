SUMMARY: I've made Amendment 02 step 1 (decisions 2 to 9) as a proposal. Nothing is committed, and none of it takes effect until you ratify it and restart.
- All validation passes: `bash -n` on 4 hooks and 2 self-tests, `jq empty` on both JSON files, and `.claude/hooks/validate-agent-files.sh` gives 72 passed, 0 failed.
- The work order forbids running the self-tests, so the new guard and stop-hook rules have not been executed against test events. Only the allowed cases ran live: they were my own tool calls after installation, and all passed. The 182 guard cases and 48 stop-hook cases run in your post-restart probe 1.
- Every sbt project has a test-report folder, so there is no BLOCKED-ON (the list is under VALIDATION RESULTS).
- I found the amendment-01 guard cases in `docs/agent-runs/amendment-01/repair-01.md` (the 71-probe listing). The probe script itself is gone.
- No file that `verify-tests-ran.sh` counts as source was touched.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: Amendment 02, step 1 (docs/agent-runs/amendment-02/prompt.md, human architect decisions 2-9; decision 8 needs no factory change)
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh
  /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/stop-hook-selftest.sh
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/CLAUDE.md
  /Users/brian/Developer/repos/scholarchain/docs/agents.md
  /Users/brian/Developer/repos/scholarchain/.mcp.json
  /Users/brian/Developer/repos/scholarchain/.claude/settings.json
  /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
  /Users/brian/Developer/repos/scholarchain/.claude/hooks/verify-tests-ran.sh
  /Users/brian/Developer/repos/scholarchain/.claude/hooks/validate-agent-files.sh
  /Users/brian/Developer/repos/scholarchain/.claude/agents/{factory-engineer,build-engineer,dependency-updater,deploy-engineer,infra-engineer,domain-engineer,hadoop-engineer,entity-resolution-engineer,spark-engineer,storage-engineer,rag-engineer,service-engineer,test-engineer,dataset-engineer}.md
  Scratch drafts only, not repository files: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/{guard-dangerous.sh.new,verify-tests-ran.sh.new,validate-agent-files.sh.new}
  The four .DS_Store modifications in git status are not mine (decision 8: the human untracks them).
COMMANDS RUN: ls, wc, cat -n, sed -n, grep, head, tail -n, cut, sort, uniq, find (no -exec/-delete), shasum -a 256, diff (read-only; once with process substitution, before installing the guard), awk (one read-only count of self-test cases; awk is not on the work order's list, so I am disclosing it), git status/diff/log/show (read-only), /bin/bash -n on every hook, self-test and scratch draft, jq empty on both JSON files, .claude/hooks/validate-agent-files.sh. No sbt, python, docker, uvx, network, chmod, or self-test execution.
TEST RESULTS: sbt not run (the work order changes no source). Self-tests not run (the work order forbids it; the human runs them after the restart). Live observation: after each hook was installed, all my later tool calls passed through it. That includes read-only commands naming .claude/ and docs/agent-runs/, "> /dev/null", bash -n, tail -n, grep -n Standing docs/agent-runs/phase-10/prompt.md, jq, the validator, and my own Write/Edit calls in territory.
VALIDATION RESULTS: bash -n OK on 6 scripts; jq empty OK on 2 files; validator 72 passed / 0 failed (exit 0); collision, orphan and one-writer audits clean; executable bits kept on all 4 hooks; the 2 self-tests are deliberately not executable. Test-report folders found (decision 3) with TEST-*.xml counts: modules/core/target/jvm-3/test-reports (13), modules/core/target/jvm-2.13/test-reports (12), modules/entity-resolution/target/test-reports (16), modules/hadoop/target/test-reports (14), modules/rag/target/test-reports (21), modules/services/target/test-reports (9), modules/spark/target/test-reports (4), modules/storage/target/test-reports (21), tests/contracts/target/test-reports (23). A sample report header confirms sbt writes <testsuite ... errors="0" failures="0" ...>. No report currently has failures or errors above 0.
ASSUMPTIONS: (1) Claude Code sends hook_event_name ("Stop" / "SubagentStop") and cwd in hook input; a missing event name is treated as Stop (strict). (2) sbt-projectmatrix keeps core's report folders at target/jvm-3 and target/jvm-2.13 (observed). (3) tests/integration, tests/end-to-end and tests/adversarial map to tests/contracts/target/test-reports, as decision 3a says, although only tests/contracts is an sbt project today. (4) The macOS /usr/bin/awk (version 20200816) runs the embedded normalizer; it was checked by reading only, not run.
RISKS: (1) The new Bash rules depend on the embedded awk normalizer. If it hit a runtime error, the new shell rules would fail open (the old raw rules are byte-identical and unaffected). The post-restart guard self-test would show it as many BLOCK failures. (2) The guard is a speed bump, not a sandbox. Scripts written to scratch and then run, interpreters (python/node/perl -e), `env -S`, variable command names, and dd of=/sort -o/curl -o all remain unchecked (see "proposed later"). (3) Known false positives, all fail-safe (they block, never allow): heredoc bodies are read as code; any cd/pushd/popd makes relative write targets block; a `>` inside [[ ]] or arithmetic reads as a redirection. (4) Stop-hook freshness uses find -newer. Its precision is the filesystem's; on whole-second precision, a report from the same second as an edit counts as stale (fail-safe). (5) Probe 6 (no ask for grep) depends on the permission layer too. The guard no longer asks, but `Bash(grep ...)` is not in the project allow list. (6) Deleting sbt reports (for example `rm -rf modules/x/target`) is still allowed. It can only remove coverage, never fake it.
MANUAL ACTION REQUIRED: Human: review this diff with code-reviewer (decision 10); ratify; restart Claude Code; run probes 1-9. Untrack the .DS_Store files (decision 8). Decide the "proposed later" items.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 2: code-reviewer reviews the complete constitutional diff (decision 10) -> review-report.md. Then human ratification, restart, and probes.
```

## 1. Matrix basis
- Decision 2: CLAUDE.md gets the new "## Operating rules for agent runs" section, placed after "Definition of done".
- Decision 3: narrowed scope of the test-stop invariant, ratified by the human. Covers 3a (verify-tests-ran.sh), 3b (guard and settings protect test reports), 3c (Definition of done items 1-2), 3d (role files) and 3e (factory-engineer.md and docs/agents.md).
- Decision 4: guard closes the Bash write gap and fixes the `ops` false positive.
- Decision 5: `.mcp.json` pins and the docs/agents.md MCP table.
- Decision 6: `releases/**` goes to deploy-engineer and `config/**` to infra-engineer. Wording fixes #4, #5, #6, #8 and #10 are applied. The self-tests live at `.claude/hook-tests/**` (factory-engineer, implied by the ordered creation), and the validator runs `bash -n` on them.
- Decision 7: watcher and memory guards.
- Decision 9: validation.
- Nothing was changed without a decision behind it.

## 2. Changes
- **CLAUDE.md:** new section "Operating rules for agent runs" (decision 2). Definition of done items 1-2 replaced and the list renumbered to 6 (3c). In Commands: `sbt check` is now called the "full gate", a line on targeted tests is added, the formatter note now names the build-definition dialects (finding #8), the stale "until build.sbt exists" clause is removed (finding #10), and the self-test commands are listed.
- **docs/agents.md:**
  - Ownership rows: factory-engineer gains `.claude/hook-tests/**`; infra-engineer gains `config/**`; deploy-engineer gains `releases/**`.
  - Generated-artifacts table gains a row for `**/target/**/test-reports/**`.
  - The local-settings row now covers `CLAUDE.local.md` and `~/.claude/**`.
  - Rule 5 now also says territories are enforced on shell writes.
  - Lifecycle routing gains rows for `config/**` and `releases/**`.
  - Hooks table: updated guard, validator and stop-gate rows, a new Self-tests row, and the new deny rules.
  - MCP table: pins, the profile and its policy, github switched off, and uv installed.
- **.mcp.json:** all four servers pinned exactly as decision 5 lists; env blocks unchanged.
- **.claude/settings.json:** adds the deny rules `Write(**/test-reports/**)` and `Edit(**/test-reports/**)` (3b).
- **.claude/hooks/guard-dangerous.sh:**
  - Adds a command-position parser covering in-place editors, watchers and shell write targets, plus memory, test-report and releases blocks.
  - Adds `config/*` and `releases/*` to `owner_of`.
  - The `ops` check now uses command position (HAS_OP).
  - Every pre-existing raw rule is byte-identical (checked with diff from `guard_bash() {` through the HTTP DELETE rule).
- **.claude/hooks/verify-tests-ran.sh:** per-project test evidence on SubagentStop. Adds `.scalafmt.conf`, `.jvmopts` and `.sbtopts` to relevant source. Stop is unchanged. Block messages now name the uncovered project and the exact sbt command.
- **.claude/hooks/validate-agent-files.sh:**
  - `bash -n` on the two required self-tests, plus an orphan check for `.claude/hook-tests/`.
  - An ownership parity check that `releases/**`, `config/**` and `.claude/hook-tests/**` have the same owner in owner_of and in docs/agents.md.
  - Hook mode now also covers `.claude/hook-tests/*`.
- **.claude/hook-tests/guard-selftest.sh (new):** 182 cases (125 BLOCK, 52 ALLOW, 5 ASK). It covers the amendment-01 probes from repair-01.md and every new rule.
- **.claude/hook-tests/stop-hook-selftest.sh (new):** 48 cases, using throwaway git repos under /tmp/claude-stoptest.* with fixed `touch -t` timestamps.
- **Role files (3d):** domain, hadoop, entity-resolution, spark, storage, rag, service and test engineers, plus dataset-engineer (its file also mentioned `sbt check`), get the targeted-test rule. build-engineer and dependency-updater keep the full `sbt check`, because their files are build files.
- **Wording fixes:**
  - build-engineer.md: law 2 now says "No production module depends on services" (finding #4); procedure step 4 now says "Scala 3 and 2.13 with Hadoop and Spark" (finding #5).
  - dependency-updater.md: the description gains the carve-out (finding #6).
- **factory-engineer.md (3e):**
  - Owned artifacts and the description gain `.claude/hook-tests/*`.
  - The test-stop floor text now states the amendment-02 scope.
  - Shell syntax checks now include the self-tests.
- **deploy-engineer.md and infra-engineer.md (decision 6):** deploy-engineer's owned artifacts now name `releases/**` (script-written only). infra-engineer owns `config/**`, with law 7 (plain JSON, environment-variable NAMES only) and a description update.
- **Unchanged on purpose:** `.claude/commands/deploy.md` doesn't say where records go. `docs/agent-runs/README.md` and `format-scala.sh` are untouched.

## 3. Privilege delta
- **factory-engineer:**
  - Ownership: widened by `.claude/hook-tests/**` (ratified, decision 6; the guard's `.claude/*` already mapped it).
  - Tools: unchanged.
  - Shell: narrowed (shell writes are confined to its territory and scratch; the old constitutional-file shell-write block remains, apart from the existing chmod +x exception).
- **infra-engineer:** ownership widened by `config/**` (ratified). Tools unchanged.
- **deploy-engineer:** gains a nominal territory, `releases/**`, but the guard blocks every hand write there (Write, Edit, shell), its own included. Effective write privilege: narrowed (shell redirects into the repo used to be unchecked). Tools unchanged (no Write/Edit).
- **All Bash-capable subagents:** narrowed.
  - Shell writes only inside their own owner_of territory or /tmp/claude-* and /private/tmp/claude-*.
  - No sed -i, perl -i, ed, ex or vi.
  - No tail -f/-F/--follow, watch or less +F.
  - No writes to memory, settings.local, CLAUDE.local.md, test reports or releases.
- **Main session:**
  - Narrowed: no shell writes inside the repository (was an ask for constitutional files, unchecked elsewhere); writes under ~/.claude and to settings.local.json or CLAUDE.local.md are blocked (were an ask); Write to releases/** is blocked (was an ask).
  - Relaxed, as ratified (decision 4): read-only commands that mention .claude/ or docs/agent-runs/ no longer ask.
- **Test-stop floor:** the SubagentStop scope is relaxed exactly as decision 3 ratifies. Stop is unchanged. Relevant source is strengthened (+.scalafmt.conf, .jvmopts, .sbtopts), and the evidence is now write-protected.
- **Settings:** 2 deny rules added; no allow or ask rule changed. MCP: pinned, which narrows supply-chain drift; no grants changed. No `tools:` field changed in any agent file.

## 4. Floor invariants
- **Dangerous-command guards:** preserved and strengthened. The raw rules are byte-identical (`diff` of the region printed RAW_RULES_IDENTICAL). New blocks are added. Cases a01-rm-rf-root, a01-rm-rf-project, a01-dd-disk, a01-reset-hard.
- **Test-stop hooks:** changed only as decision 3 ratifies. Stop still needs the full marker; SubagentStop also accepts fresh, passing per-project reports. Build files, project/** and formatter or JVM options always need the full marker. Core needs both builds. Failing, stale or unreadable reports block. Reports are write-protected by guard and deny rules. Cases s02-s28.
- **Formatter behavior:** preserved. format-scala.sh is untouched (sha256 e3e48c27…2147) and its PostToolUse entry is unchanged.
- **Secret-read denial:** preserved. is_secret_path, the raw secret regexes and the settings deny rules are unchanged; shell write targets are now also checked against is_secret_path. Cases a01-cat-env, a01-read-env, a01-read-aws-creds, a01-read-tfstate.
- **Terraform apply and destroy denial:** preserved. Identical regex and deny rules. Cases a01-terraform-apply, a01-terraform-destroy, j-echo-terraform-destroy.
- **Destructive AWS denial:** preserved. Identical. Cases a01-s3-rm-recursive, a01-ddb-delete-table, a01-emr-terminate, a01-kms-key-deletion.
- **Force-push denial:** preserved. Identical. Cases a01-push-force, a01-push-force-lease, a01-push-refspec.

## 5. Audits
- **Collision:**
  - No agent or command was added or renamed. The validator reports no duplicate names, descriptions or commands.
  - The hook matchers in settings.json are unchanged.
  - The new deny rules conflict with no allow or ask rule (there are no Write/Edit allow rules).
  - The new owner_of patterns `config/*` and `releases/*` overlap no other pattern.
  - All 182 guard self-test case names are unique (`sort | uniq -d` is empty).
- **Orphan:**
  - All 4 hooks are referenced by settings.json and executable.
  - `.claude/hook-tests/` holds exactly the two listed self-tests (validator orphan check). They are documented in docs/agents.md (Self-tests row) and in CLAUDE.md Commands. They are not hooks, so settings.json does not reference them (intended).
  - All 18 agents have rows and files.
- **One-writer:**
  - docs/agents.md and owner_of agree, including `config/**` -> infra-engineer, `releases/**` -> deploy-engineer (script-written only) and `.claude/hook-tests/**` -> factory-engineer.
  - The new validator parity check passes. It failed (70/2) on the PostToolUse run between installing the validator and installing the guard, which shows the check fires when owner_of is missing these mappings.
  - Test reports have no agent writer (generated by sbt).
  - Role files agree (deploy-engineer.md, infra-engineer.md, factory-engineer.md).

## 6. Validation (command output)
```
bash -n OK  .claude/hooks/format-scala.sh
bash -n OK  .claude/hooks/guard-dangerous.sh
bash -n OK  .claude/hooks/validate-agent-files.sh
bash -n OK  .claude/hooks/verify-tests-ran.sh
bash -n OK  .claude/hook-tests/guard-selftest.sh
bash -n OK  .claude/hook-tests/stop-hook-selftest.sh
jq empty OK .claude/settings.json
jq empty OK .mcp.json
validate-agent-files.sh: 72 checks passed, 0 failed.
validator exit=0
```
- **Installed files vs scratch drafts:** guard IDENTICAL (sha256 21113e10a24b8617d05c1ee38f4ece22d767c322481c4a4c87c644ccc3ee50b8), verify-tests-ran IDENTICAL (f2ec2940…294c), validator IDENTICAL (3b37aa78…d8c).
- **Embedded awk program:** contains 0 single quotes (static check).
- **Executable bits:** -rwxr-xr-x on all 4 hooks. The self-tests are -rw-r--r--, as intended.
- **Frontmatter:** checked by the validator (name, description, explicit tools on all 18 agents).
- **Other hashes:** guard-selftest e9a1ab5f…b4c3, stop-hook-selftest 352ad1b0…0373, settings.json 274561b1…1730, .mcp.json 06b72d29…69bf, CLAUDE.md bb1f014f…9480, docs/agents.md d06d9f7d…f6ee.

### Guard rules added or changed (all PreToolUse)
How the guard reads a Bash command now: quotes are removed, the path basename is used, and wrappers (command/env/xargs/sudo/nice/timeout/…) are stripped. It also unwraps `bash -c`/`sh -c`, `$(…)`, backticks, eval/source, pipes into sh/bash, here-strings and `find -exec`.

| # | Matches | Tool / event | Applies to | Decision | Self-test cases |
|---|---|---|---|---|---|
| 1 | Command word sed/gsed with an arg matching `^-[AEnrsuzal0-9]*[iI]` or `^--in-place` | Bash | subagents and main | BLOCK "Use the Edit tool." | c-sed-i-probe5, c-sed-i-main, c-sed-in-place-long, c-sed-Ei, c-sed-bsd-I, c-sed-single-quoted, c-sed-double-quoted, c-sed-backslash, c-sed-command-prefix, c-sed-abs-path, c-sed-extra-spaces, c-sed-bash-c, c-sed-sh-c-chain, c-sed-xargs, c-sed-env-i, c-sed-find-exec, c-sed-subst-in-dq, c-sed-backtick, c-sed-eval, c-sed-pipe-into-sh |
| 2 | perl with an arg matching `^-[aFnpsTtuUwWXcfhlv0-9]*i` | Bash | all | BLOCK "Use the Edit tool." | c-perl-pi, c-perl-i-bak, c-perl-0777-pi |
| 3 | Command word ed, red, ex, vi, vim, nvim, view, vimdiff or gvim | Bash | all | BLOCK "Use the Edit tool." | c-ed, c-ex, c-vim |
| 4 | Shell write targets: `>`, `>>`, `>|`, `&>`, `&>>`, `<>`, `N>`, `>&file`; tee args; touch args; cp/mv/install/rsync/ln destination (`-t`/`--target-directory`, else the last operand; install -d: all operands; ln with one operand: `.`) | Bash | all | Allowed: /dev/null, /dev/stdout, /dev/stderr, `&N`/`&-`, /tmp/claude-*, /private/tmp/claude-*, the caller's own territory. BLOCK otherwise: non-literal (`$`, backtick, `*`, `?`, `~`, `[`, `{`) "use a literal path"; `..`; relative after cd/pushd/popd; outside the repository; any repo path for the main session; another owner's or an unowned path | d-redirect-other-probe4, d-main-redirect, d-main-redirect-claude, d-tee-other, d-cp-other, d-cp-target-dir, d-mv-other, d-install-other, d-rsync-other, d-ln-other, d-touch-other, d-outside-tmp, d-outside-home, d-dotdot-scratch, d-dotdot-repo, d-variable-target, d-tilde-target, d-glob-star-target, d-glob-question-target, d-backtick-target, d-subst-target, d-cd-relative, d-bash-c-redirect, d-stderr-to-file, d-amp-redirect, d-clobber-redirect, a01-sub-shell-claude-md, a01-shell-marker |
| 5 | `ops` condition for the marker, test-report and constitutional rules: now a command word in command position (tee touch cp mv install ln rsync rm unlink truncate chmod chown patch dd split, or git checkout/restore/apply/am) | Bash | all | Read-only commands: ALLOW (no ask). Main + op: ASK (text changed to "shell command changing constitutional files…"). Subagent + op: BLOCK | e-ro-grep-runs-probe6, e-ro-cat-settings, e-ro-ls-claude, e-ro-head-runs, e-ro-tail-n-runs, e-ro-wc, e-ro-shasum, e-ro-find, e-ro-grep-cp-pattern, e-ro-grep-tee-word, e-ro-bash-n, e-main-chmod-hook (ASK), e-factory-chmod-hook, e-main-git-checkout (ASK), e-sub-git-restore |
| 6 | tail/gtail with an arg matching `^-[A-Za-z0-9]*[fF]`, `^\+[0-9]*[fF]` or `^--follow`; watch; less with `^\+.*F` | Bash | all | BLOCK "No watchers: read with tail -n or grep, and finish the step." | f-tail-f-probe3, f-tail-F, f-tail-follow-long, f-tail-fn, f-tail-n-then-f, f-tail-100f, f-tail-f-after-pipe, f-tail-f-before-pipe, f-tail-f-bash-c, f-tail-f-quoted, f-tail-f-abs, f-watch, f-watch-command-prefix, f-less-plus-F |
| 7 | Any path under `$HOME/.claude` (also spelled `~/.claude`, `$HOME/.claude`, `${HOME}/.claude`), any `.claude/settings.local.json`, any `CLAUDE.local.md` | Write/Edit/MultiEdit/NotebookEdit and shell targets | all | BLOCK (exit 2) "Memory and settings writes are off during runs; put the note in your report." (was ASK for the main session) | g-write-memory-main, g-edit-memory-sub, g-write-settings-local, g-edit-settings-local-rel, g-write-claude-local-md, g-notebook-memory, g-shell-tilde, g-shell-home-var, g-shell-home-abs, g-shell-cp-settings-local |
| 8 | Path containing `/test-reports/` | Write/Edit/MultiEdit/NotebookEdit; shell targets; raw `>`…test-reports/; mention of `test-reports` + op; settings deny rules | all | BLOCK "Run the tests and let sbt write the reports: sbt -batch \"<project>/testOnly <suites>\", or sbt check." | b-write-report, b-edit-report-main, b-notebook-report, b-touch-report, b-redirect-report, b-cp-report, b-rm-report |
| 9 | `releases/**` | Write/Edit/MultiEdit/NotebookEdit and shell targets | all, deploy-engineer and main included | BLOCK "…written only by approved scripts that deploy-engineer runs, never by hand." | h-releases-write-deploy, h-releases-write-main, h-releases-shell-deploy |
| 10 | `config/**` -> infra-engineer (owner_of) | Write/Edit and shell | subagents (main: ASK on Write) | Owner ALLOW; others BLOCK | h-config-infra, h-config-rag (BLOCK), h-config-main (ASK), h-config-shell-infra |

**Expected to stay allowed** (all are self-test cases; the read-only ones I also observed live):
- i-sbt-background-main and i-sbt-background-sub: `sbt -batch check > /private/tmp/claude-501/scratch/check.log 2>&1`
- i-grep-runs: `grep -n x docs/agent-runs/phase-10/prompt.md`
- i-write-own-territory: rag-engineer Write `modules/rag/src/main/scala/X.scala`
- i-tail-n: `tail -n 50 <file>`
- i-bash-n: `bash -n .claude/hooks/x.sh`
- Further allowed cases:
  - b-read-report, b-find-reports
  - c-sed-n-allowed, c-grep-sed-pattern, c-perl-M-allowed
  - d-redirect-own, d-append-own, d-tee-append-own, d-touch-own, d-cp-own
  - d-scratch-tmp-sub, d-scratch-tmp-main, d-scratch-private-main
  - d-dup-2to1, d-dup-to-stderr, d-devnull, d-devnull-stderr, d-devnull-amp
  - d-quoted-gt, d-grep-arrow
  - f-tail-n-allowed, f-tail-plus-n-allowed, f-grep-tail-f-pattern
  - g-read-memory-allowed

**Amendment-01 cases (47):**
- Found in docs/agent-runs/amendment-01/repair-01.md, in the "$ bash guard-probes.sh" listing (the 71-probe run, about lines 149-238). agent-report.md lines 67-79 summarizes the earlier 51-probe run. The script itself lived in an expired scratchpad.
- Included: all 46 behavior probes, plus a shell-marker case.
- Left out: the 25 doc-text-presence checks (they aren't guard behavior) and the owner_of printouts (covered by owner Write cases).

**Stop-hook self-test cases:** s01 clean; s02-s09 services (no reports, fresh passing, failing, errors, stale, unreadable, change after reports, old failure superseded); s10-s13 core (Scala 3 only, 2.13 only, both, message); s14/s15 × {build.sbt, project/plugins.sbt, .scalafmt.conf, .jvmopts, .sbtopts} (full marker needed, then marker present); s16-s19 prompts and tests mapping; s20 one of two covered; s21 unknown module; s22/s23 BLOCKED-ON and FAILED escape; s24 deletion only; s25 target/ ignored; s26 missing event name = Stop; s27 marker newer; s28 build.sbt missing (second attempt allowed, first blocked).

## 7. Diff

`git diff` of tracked files, .DS_Store excluded. To keep this readable, the two hook diffs are given by hunk: the stop hook diff is described rather than reproduced, and the guard's large insertion is described rather than reproduced. Every changed line is either shown here or fully specified by a line range of the installed file, whose sha256 is listed in section 6. code-reviewer can Read those ranges directly.

### 7a. Markdown, JSON, settings and validator
These are verbatim from `git diff`. They are long, so the full text is in the harness-persisted output `/Users/brian/.claude/projects/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/tool-results/blcu1vu75.txt` (571 lines), which the orchestrator can include verbatim. Every hunk is summarized here:
- **build-engineer.md:**
  - law 2: "No module depends on `services`" becomes "No production module depends on `services`".
  - Procedure step 3 now reads: "Run `sbt Test/compile`, then `sbt check`, and observe the output. A change to `build.sbt`, `project/**`, or the formatter config always needs the full `sbt check` (the stop hook requires the full marker for it). If you changed only other files, run the changed projects' targeted tests (`sbt -batch "<project>/testOnly <suites>"`) and run the full `sbt check` when the work order makes you the wave closer."
  - Step 4 says "(Scala 3 and 2.13 with Hadoop and Spark)".
- **dataset-engineer.md, step 4:** "Run the dataset validation command and `bash -n` on scripts. When Scala tests changed, run the targeted tests of the changed projects (`sbt -batch "<project>/testOnly <suites>"`) and observe the output; run the full `sbt check` when the work order makes you the wave closer."
- **dependency-updater.md:**
  - The description adds ", except version.scalafmt and image.java-runtime (build-engineer),".
  - Law 6: "Run the full `sbt check` after the change; a ledger change is a `project/**` change, so targeted tests are not enough (the stop hook requires the full marker for it). If it fails …" (rest unchanged).
- **deploy-engineer.md, owned artifacts:** "- Deployment execution reports, returned in your final report." and "- Release records under `releases/**`, written only by the approved scripts you execute. Nobody hand-edits them: the guard blocks Write, Edit, and shell writes there for every agent, you included. You have no Write or Edit tool and hand-author no repository file."
- **domain-engineer.md, step 4:** "Run the targeted tests of both core builds (`sbt -batch "core/testOnly <suites>" "core2_13/testOnly <suites>"` for the suites you added or changed and their direct users) and observe the output. Run the full `sbt check` when the work order makes you the wave closer, or when you changed `build.sbt`, `project/**`, or the formatter config."
- **entity-resolution, hadoop, rag, service, spark, storage role files:** the law sentence "Run `sbt check` before reporting COMPLETE." becomes "Before reporting COMPLETE, run the targeted tests of the projects you changed (`sbt -batch "<id>/testOnly <suites>"` for the suites you added or changed and their direct users) and observe the output; run the full `sbt check` when the work order makes you the wave closer, or when you changed `build.sbt`, `project/**`, or the formatter config."
  - The project ids are entityResolution, hadoop, rag, services, sparkAnalytics and storage.
  - rag adds "; a `prompts/**` change counts as a `rag` change".
  - Procedure step 4 becomes "Run the tests law N requires …".
- **test-engineer.md, step 4:** "Run the targeted tests of the suites you added or changed (`sbt -batch "contractTests/testOnly <suites>"`, or the narrower test command the work order names) and observe the output; run the full `sbt check` when the work order makes you the wave closer, or when you changed `build.sbt`, `project/**`, or the formatter config. Report exact counts from whatever you ran."
- **factory-engineer.md:**
  - The description and owned artifacts gain `.claude/hook-tests/*`.
  - The Test-stop floor gains: "Scope as ratified in amendment-02: the main session (Stop) needs the full marker from a passing `sbt check` after the last change; a subagent (SubagentStop) may also stop when every changed file is covered by fresh, passing sbt test reports of its project (targeted evidence; `core` needs both its Scala 3 and Scala 2.13 reports); `build.sbt`, `project/**`, `.scalafmt.conf`, `.jvmopts`, and `.sbtopts` always need the full marker. No change may weaken it further."
  - Shell syntax checks now include the self-tests under `.claude/hook-tests/`.
- **infra-engineer.md:**
  - The description adds "optional .github/workflows/** that call those scripts, and config/** (per-environment ScholarChain config JSON holding environment-variable names only)".
  - Owned artifacts add "- `config/**`: per-environment ScholarChain configuration files".
  - New law 7: "Files under `config/**` are plain JSON (no comments) and hold only environment-variable NAMES, never their values: no secret, key, token, account ID, or bucket name."
- **validate-agent-files.sh:** shown in full in section 7c.
- **settings.json:** adds `"Write(**/test-reports/**)",` and `"Edit(**/test-reports/**)",` after `"Write(./.claude/.last-test-run)",`.
- **.mcp.json:** the image and package strings are replaced with `ghcr.io/github/github-mcp-server@sha256:f6056fc10bfffcc8c81a6417a2e9ca12808647e8dd6049642a4feaadee0e0f8a`, `awslabs.aws-api-mcp-server@1.3.33`, `hashicorp/terraform-mcp-server@sha256:11dd418bba6003dd5777e9d0979b68de59929a155b355f338f407947e00e9d80` and `awslabs.aws-documentation-mcp-server@1.1.21`. The env blocks are unchanged.
- **CLAUDE.md:** shown in full in section 7b.
- **docs/agents.md:** shown in full in section 7b.

### 7b. CLAUDE.md and docs/agents.md (verbatim hunks)
```diff
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -38,21 +38,40 @@ Rules:
 ## Commands
 
-- `sbt check` is the repository definition of done. It runs format checking, compilation, tests, contract tests that do not need AWS, and `markTestRun`, which refreshes `.claude/.last-test-run` only when tests pass.
+- `sbt check` is the repository's full gate. It runs format checking, compilation, tests, contract tests that do not need AWS, and `markTestRun`, which refreshes `.claude/.last-test-run` only when tests pass.
+- `sbt -batch "<project>/testOnly <suites>"` runs targeted tests; sbt writes their reports under the project's `target/**/test-reports/`, which `verify-tests-ran.sh` reads.
 - `sbt fmt` formats sources. `sbt Test/compile` compiles tests.
-- Formatter dialects (`.scalafmt.conf`, owned by `build-engineer`): Scala 3 by default; `core` (`modules/core/src/**`) and `sparkAnalytics` (`modules/spark/src/**`) are formatted with the Scala 2.13 dialect (`scala213source3`), a consequence of the Scala deviation above.
-- `.claude/hooks/validate-agent-files.sh` validates the agent system.
-- Build aliases are defined by `build-engineer` in `build.sbt`; until `build.sbt` exists, no Scala gate can pass and source-changing agents report `BLOCKED-ON` with owner `build-engineer`.
+- Formatter dialects (`.scalafmt.conf`, owned by `build-engineer`): Scala 3 by default for application sources; `core` (`modules/core/src/**`) and `sparkAnalytics` (`modules/spark/src/**`) are formatted with the Scala 2.13 dialect (`scala213source3`), a consequence of the Scala deviation above; build definitions use the sbt dialect (`*.sbt`) and the Scala 2.12 dialect (`project/*.scala`).
+- `.claude/hooks/validate-agent-files.sh` validates the agent system. `bash .claude/hook-tests/guard-selftest.sh` and `bash .claude/hook-tests/stop-hook-selftest.sh` test the guard and the stop hook (the human runs them after a restart).
+- Build aliases are defined by `build-engineer` in `build.sbt`.
 
 ## Definition of done
 
 Implementation work is complete only when:
 
-1. `sbt check` passes and its output was observed (never report a command as successful without seeing its output).
-2. Every behavior change has a feature test that fails without the change.
-3. The owning agent's primary gate in `docs/agents.md` passes.
-4. The agent returns the standard report contract.
-5. The required reviewer or test agent has run when the phase requires it, and the human has ratified the phase.
+1. After a source change, the tests of every changed project pass and the agent saw the output: `sbt -batch "<project>/testOnly <suites>"` for the suites it added or changed and their direct users. `verify-tests-ran.sh` checks this from sbt's own test reports. Never report a command as successful without seeing its output.
+2. A full `sbt check` passes after the last source change of each wave, before `code-reviewer` reviews that wave, and before the phase ends. A wave is the source-changing agents the orchestrator runs one after another before the next review or `test-engineer` step. The work order names the agent that runs this check (the wave closer); by default it is the last source-changing agent of the wave. Never repeat a passing `sbt check` without a source change.
+3. Every behavior change has a feature test that fails without the change.
+4. The owning agent's primary gate in `docs/agents.md` passes.
+5. The agent returns the standard report contract.
+6. The required reviewer or test agent has run when the phase requires it, and the human has ratified the phase.
+
+## Operating rules for agent runs
+
+- Hub and spoke: agents never call each other; every handoff goes through the orchestrator. One repair round per finding; if it is still open, the orchestrator asks the human. An agent never edits another owner's file, even to fix an obvious mistake; it reports it.
+- One sbt process at a time. The orchestrator never runs two source-changing agents at once; the read-only `code-reviewer` may run alongside one.
+- No watchers: no `tail -f`, `tail -F`, `watch`, or other command that does not end by itself (the guard blocks them). Long jobs use the Bash tool's background option and are read with bounded reads (`tail -n`, `grep`). A step ends with no background job left.
+- The orchestrator never ends its turn while a background agent is running; it waits for the result. When it must stop and ask the human, its message starts with the line `STATUS: BLOCKED-ON human`.
+- No memory or settings writes during a run (the guard blocks writes under `~/.claude/`, to `.claude/settings.local.json`, and to `CLAUDE.local.md`). A note worth keeping goes in the report.
+- Commands: sbt, read-only git, and read-only shell only, unless the work order allows more. Files are edited only with the Write and Edit tools, never with `sed -i`, `perl -i`, or shell redirection into the repository (the guard blocks these). If a command needs approval, do not retry variants: skip it if optional, else report `BLOCKED-ON`.
+- Live tests (live AWS, Voyage, Anthropic, Bedrock) are tagged `RequiresAws`; they are compiled and never run unless the work order says so.
+- Secrets: no key, token, account ID, or bucket name in code, config, tests, logs, or reports. Keys are read only from an environment variable whose NAME comes from configuration.
+- Model output that fails validation is stored only as its sha256, length, and error. No prompt asks for reasoning.
+- Scope: build only what the work order lists; extras go on a "proposed later" list.
+- Repair limits: only blocking or major findings get a repair round; all findings of one owner are repaired in one pass; the owner adds a test that fails without the fix.
+- Every source-changing agent ends its report with `INTERFACE HANDOFF`: files changed, public signatures, fixtures, thread-safety, limits.
+- Evidence: nothing is written under `docs/agent-runs/`; reports go to the session scratchpad and the human copies them with `cp -n`.
+- A work order may add phase-specific exceptions (for example a phase that allows network); the work order wins for that phase only.
```
- **docs/agents.md hunks:**
  - The factory-engineer, infra-engineer and deploy-engineer rows, the rule-5 text and the lifecycle rows are exactly as section 2 describes.
  - The generated-artifacts rows are `**/target/**/test-reports/**` and `.claude/settings.local.json`, `CLAUDE.local.md`, `~/.claude/**`.
  - The full replacement text of the Hooks and MCP tables is in the persisted diff (lines 480-567).
  - The MCP table now has a "Pinned to" column.
  - The prerequisites line now reads: "Docker (present), `uv`/`uvx` (installed before amendment-02), the read-only AWS profile `scholarchain-readonly` with `SCHOLARCHAIN_AWS_READONLY_PROFILE` and `SCHOLARCHAIN_AWS_REGION` exported in the human's shell profile, and, only if `github` is switched on later, the read-only GitHub token."

### 7c. Hooks
- **verify-tests-ran.sh:**
  - The full `git diff` was observed: 157 insertions and 22 deletions in 7 hunks.
  - The header comment is rewritten.
  - EVENT is read from hook_event_name.
  - note_file() replaces the inline mtime bookkeeping, and CHANGED becomes newline-separated.
  - A `.scalafmt.conf|.jvmopts|.sbtopts` case is added.
  - A new block runs between the marker check and the BLOCKED-ON escape: project_of, report_dirs, sbt_cmd, newer, report_passes, then the SubagentStop coverage loop.
  - Block-message lines are changed or added.
  - The installed file is byte-identical to the scratch draft (sha256 f2ec2940ff5fc9f5a355d5acc8c996e97fa859354a194cfe10c73a4f3aef294c). code-reviewer should read it in full; it is 288 lines.
- **guard-dangerous.sh:**
  - 494 insertions and 11 deletions.
  - Hunk 1: header comment lines 10-32, plus `IFS=$' \t\n'`.
  - Hunk 2: CWD and HOME_DIR (new lines 81-85).
  - Hunk 3: the MSG_* constants (new lines 97-100).
  - Hunk 4: owner_of gains `|config/*` on the infra line and a new `releases/*) echo "deploy-engineer"` line.
  - Hunk 5, `@@ -128,6 +149,416 @@`: a pure insertion of new lines 152-561. It contains is_memory_path, is_test_report_path, normpath, NORM_AWK (lines 200-304), unmask, MASKED_SEMI, the RE_* regexes, wrapper_optarg, REDIR_OPS/TARGETS/HAS_OP/HAS_CD, add_target, analyze_segment and check_write_target. Nothing is deleted.
  - Hunk 6, `@@ -260,21 +691,62 @@`: the old `local ops=…` line is removed. In its place come the command-position analysis block, the test-reports rule, and `[ "$HAS_OP" = 1 ]` instead of `matches "$cmd" "$ops"` in the marker and constitutional rules. The main-session ask text changes from "shell write touching…" to "shell command changing…".
  - Hunks 7-8 in guard_path_write: memory block first, the test-reports block after the secret check, and the releases block before the main-session ask.
  - Installed sha256: 21113e10a24b8617d05c1ee38f4ece22d767c322481c4a4c87c644ccc3ee50b8, identical to the scratch draft.
- **validate-agent-files.sh:** 33 insertions and 5 deletions. The changes:
  - Header comment items 4 and 8 are extended.
  - Hook-mode case gains `|.claude/hook-tests/*`.
  - New variables `REQUIRED_SELFTESTS=".claude/hook-tests/guard-selftest.sh .claude/hook-tests/stop-hook-selftest.sh"` and `OWNERSHIP_SAMPLES="releases/*|deploy-engineer|releases/** config/*|infra-engineer|config/** .claude/*|factory-engineer|.claude/hook-tests/**"`.
  - A parity loop under `set -f` (grep owner_of for `echo "<owner>"` on the pattern's line; grep the docs/agents.md `| \`<owner>\` |` rows for the backticked path).
  - A self-test `bash -n` loop and a hook-tests orphan loop.
  - These are verbatim in the persisted diff, lines 294-376.

### 7d. New files, full text
.claude/hook-tests/guard-selftest.sh and .claude/hook-tests/stop-hook-selftest.sh: 265 and 201 lines, sha256 e9a1ab5f…b4c3 and 352ad1b0…0373. Their full text is what I wrote with the Write tool this run; read them at /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh and /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/stop-hook-selftest.sh.
- **Structure of guard-selftest.sh:**
  - It builds each event with `jq -n` (tool_name, cwd=ROOT, tool_input.{command|file_path|notebook_path}, plus agent_type for subagents).
  - It runs `CLAUDE_PROJECT_DIR=$ROOT bash .claude/hooks/guard-dangerous.sh` and classifies the result: exit 2 is BLOCK; exit 0 with a `"permissionDecision":"ask"` is ASK; exit 0 with no output is ALLOW.
  - Where given, it also requires a message fragment ("Use the Edit tool.", "No watchers", "Memory and settings writes are off", "use a literal path", "owned by domain-engineer", "test reports", "approved scripts", "no write territory", "outside the repository", "'..'").
  - It prints one line per case (`PASS  BLOCK  name`, or `FAIL … (got …): first message line`), then `TOTAL: N passed, M failed.`, and exits non-zero on any failure. It writes nothing.
- **Structure of stop-hook-selftest.sh:**
  - It uses `mktemp -d /tmp/claude-stoptest.XXXXXX` (asserted to match that prefix), with a trap that removes only that prefix.
  - Per case it runs newrepo(): git init with a local config, base files (build.sbt, .gitignore with target/, Base.scala, Gone.scala, .scalafmt.conf, .jvmopts, .sbtopts, project/plugins.sbt), a commit, and `touch -t` to a fixed base time.
  - Helpers: change() and report(pass|fail|error|garbage), each at a fixed timestamp, and marker().
  - Events come from `jq -n` (hook_event_name, or none; stop_hook_active; last_assistant_message).
  - It uses the same PASS/FAIL/TOTAL output and non-zero exit.

## 8. Open items and decisions for the human (proposed later)
1. **Finding #7 (build-engineer):** the build.sbt:274 comment "Nothing depends on services" and the project/build.properties header comments are out of date. Not mine; route to build-engineer.
2. **Pre-existing gap, not new:** the old raw regexes can be dodged by quoting the command word. For example, `'terraform' apply` slips both the guard regex and the `Bash(terraform apply:*)` deny rule. I propose also running the existing raw rules against a de-quoted copy of the command. I left it out of scope; it needs ratification.
3. **Writes the spec didn't list (left out on purpose):**
   - Sources of `mv`, and non-recursive `rm` of files in another territory.
   - `dd of=`, `sort -o`, `curl -o`, `mkdir`.
   - Interpreters writing files (python, node, perl -e), scripts written to scratch and then run, the `env -S` string, and variable command names.
4. **Parsing improvements:**
   - Heredoc-aware parsing (bodies are read as code today, which gives fail-safe false positives).
   - Allow relative targets after `cd` into the caller's own territory.
5. **Defense in depth:** settings deny rules for `Write/Edit(~/.claude/**)`, `**/settings.local.json` and `**/CLAUDE.local.md`.
6. **Orchestrator writes to docs/agent-runs/** with Write or Edit still ASK.** The work order says "writes there are blocked"; I can make it a block if you want.
7. **docs/agent-runs/README.md line 3** says the guard "asks the human before any orchestrator write". Orchestrator shell writes are now blocked, so the wording should change.
8. **Probe 6 "no ask"** also depends on the permission layer: `Bash(grep:*)`, `Bash(cat:*)` and `Bash(find:*)` aren't in the project allow list. You could add read-only allow rules, or keep relying on your local settings or mode.
9. **.claude/commands/deploy.md** doesn't say where release records go, so it is unchanged. Optionally add a `releases/**` mention.
10. **Dropped item, recorded risk:** there is no automatic value check for `version.scalafmt` or `image.java-runtime`; code review covers it.

## INTERFACE HANDOFF
**Rules that change for agents and the orchestrator (effective after ratification and restart):**
- **Subagent completion (SubagentStop):**
  - Run `sbt -batch "<project>/testOnly <suites>"` for the changed suites and their direct users. Core needs both builds: `sbt -batch "core/testOnly <suites>" "core2_13/testOnly <suites>"`.
  - Project ids: hadoop, entityResolution, sparkAnalytics, storage, rag (also covers prompts/**), services, contractTests (all tests/**).
  - Fresh, passing reports are enough to stop.
  - A change to build.sbt, project/**, .scalafmt.conf, .jvmopts or .sbtopts, or to a module with no known report folder, needs a passing `sbt check`.
- **Orchestrator:**
  - The main-session Stop still needs the full marker. Before ending any phase with source changes, a passing `sbt check` must exist after the last change.
  - Work orders should name the wave closer (default: the last source-changing agent of the wave).
  - A full `sbt check` runs before each code-reviewer review and at the end of the phase. Never repeat a passing one without a source change.
- **Shell writes:**
  - Nobody edits files through the shell. Use Write and Edit; `sed -n` is still fine.
  - A shell write target must be a literal path in the caller's own territory, or under /tmp/claude-* or /private/tmp/claude-*. /dev/null, /dev/stdout, /dev/stderr and `2>&1` are always fine.
  - After `cd`, use absolute targets.
  - The orchestrator must never shell-write inside the repository. Logs go to the scratchpad, for example `sbt -batch check > <scratchpad>/check.log 2>&1` with the Bash background option, then `tail -n`/`grep`.
  - Your `cp -n <scratch> docs/agent-runs/...` commands will be blocked if run through a Claude Code Bash call. Run them in your own terminal.
- **Watchers:** no tail -f/-F/--follow, watch or less +F, anywhere (in pipes and in `bash -c` too).
- **Memory and settings:** no writes under ~/.claude, to .claude/settings.local.json, or to CLAUDE.local.md, by anyone during a run. Reading is still allowed.
- **Test reports:** never write, copy, touch or remove anything under `**/test-reports/**` by hand.
- **Self-tests:** run with `bash .claude/hook-tests/guard-selftest.sh` and `bash .claude/hook-tests/stop-hook-selftest.sh`. Never chmod them.

**New paths and owners:**

| Path | Owner | Notes |
|---|---|---|
| `releases/**` | deploy-engineer | Written only by approved scripts it runs; every hand write is blocked |
| `config/**` | infra-engineer | Plain JSON, environment-variable NAMES only |
| `.claude/hook-tests/**` | factory-engineer | Constitutional |
| `**/target/**/test-reports/**` | sbt (generated) | No agent writer |

**New or changed guard blocks.** Each message is printed after "BLOCKED by .claude/hooks/guard-dangerous.sh: ", followed by "What to do instead: …" and the policy line.
- **In-place editing:**
  - "in-place editing through the shell (<cmd> <arg>) is denied for every agent and the orchestrator." / "Use the Edit tool."
  - "editing files with <cmd> through the shell is denied for every agent and the orchestrator." / "Use the Edit tool."
- **Watchers:**
  - "a command that never ends by itself (<cmd> <arg>) stalls the run." / "No watchers: read with tail -n or grep, and finish the step."
  - "a command that never ends by itself (watch) stalls the run." / same instruction.
- **Memory and local settings:**
  - Write tool: "writing '<path>' is denied: memory and local settings ($HOME/.claude/**, .claude/settings.local.json, CLAUDE.local.md) are not written during runs."
  - Shell: "shell write to '<t>': memory and local settings (…) are not written during runs." and "shell write to '<abs>': memory and local settings are not written during runs."
  - Instruction for all three: "Memory and settings writes are off during runs; put the note in your report."
- **sbt test reports:**
  - Write tool: "writing sbt test reports ('<path>') is denied; they are the test evidence verify-tests-ran.sh reads and only sbt writes them."
  - Shell target: "shell write to sbt test reports ('<t>') is denied; they are the test evidence verify-tests-ran.sh reads."
  - File-changing command naming them: "sbt test reports (target/**/test-reports/**) are test evidence written only by sbt; no agent may write, copy, touch, or remove them by hand."
  - Instruction for all three: "Run the tests and let sbt write the reports: sbt -batch \"<project>/testOnly <suites>\", or sbt check."
- **Release records:** "'<rel>' is a release record; records under releases/** are written only by approved scripts that deploy-engineer runs, never by hand." / "Run the approved script through /deploy, or put the record in your final report."
- **Non-literal target:** "shell write target '<t>' is not a literal path (it contains $, a backtick, *, ?, ~, [ or {), so the guard cannot check whose territory it is in." / "use a literal path, or use the Write and Edit tools."
- **`..` in a target:**
  - "shell write target '<t>' contains '..'." / "use a literal path without '..' inside your own territory or under /private/tmp/claude-*."
  - After the path is resolved: "use a literal path without '..'."
- **Relative target after cd:** "relative shell write target '<t>' in a command that changes directory (cd, pushd, popd); the guard cannot tell where it lands." / "use a literal path: an absolute path inside your own territory or under /private/tmp/claude-*."
- **Outside repository and scratch:** "shell write to '<abs>' is outside the repository and outside the scratch areas (/tmp/claude-*, /private/tmp/claude-*)." / "Write scratch output under your session scratchpad in /private/tmp/claude-*, or write inside your own territory."
- **Orchestrator in the repository:** "the orchestrator has no write territory; shell writes inside the repository ('<rel>', owner: <owner>) are denied." / "Delegate the change to the owning agent (<owner>); keep scratch output under /private/tmp/claude-*."
- **Another owner's territory:** "shell write to '<rel>', which is owned by <owner>, not '<agent>' (one writer per artifact)." / "Decline, and return BLOCKED-ON <owner> with the needed change so the orchestrator can route it."
- **Unowned path:** "shell write to '<rel>', which has no owner in docs/agents.md (ambiguous ownership)." / "Return BLOCKED-ON factory-engineer with the path so the human can ratify an owner."
- **Shell targets also reuse:**
  - the marker message (".claude/.last-test-run may be written only by the sbt markTestRun task after tests pass." / "Run sbt check.");
  - the evidence message ("'<rel>' is human-owned evidence under docs/agent-runs/**; no agent may create or edit it.");
  - the secret message ("writing secret material ('<t>') is denied.").
- **Changed ask text (main session, file-changing command naming constitutional files or evidence):** "guard-dangerous.sh: shell command changing constitutional files or human-owned evidence (CLAUDE.md, docs/agents.md, .claude/**, .mcp.json, docs/agent-runs/**). Constitutional edits belong to factory-engineer via Write/Edit; evidence belongs to the human."

**Stop-hook messages:**
- Allow on SubagentStop when covered: "verify-tests-ran.sh: every changed project is covered by fresh, passing sbt test reports; the full sbt check is still required before review and before the phase ends."
- Block on SubagentStop: "A subagent may stop after fresh, passing sbt test reports of every changed project, or after a passing sbt check. Not covered:" followed by one line per project, with reasons "no test-report folder <dir>;", "no TEST-*.xml in <dir> is newer than <file> (stale or missing);" or "failing or unreadable report <file>;", and "Run: <sbt -batch …>   (the suites you added or changed and their direct users; changed suites: …), or sbt check". Full-marker cases print "build.sbt, project/**, .scalafmt.conf, .jvmopts or .sbtopts changed: no shortcut; the full marker is required. Run: sbt check" or "a changed file is in a project without a known test-report folder: the full marker is required. Run: sbt check".
- Block on Stop: "The main session (Stop) needs the full marker: one passing sbt check after the last change."
- Every block ends: "Never write .claude/.last-test-run or any test report yourself."