# Amendment 02, repair-04 (factory-engineer), final round: repair report

I made the three changes the human ordered and nothing else:
- **Guard, brace expansion:** a git word that uses shell brace expansion is now blocked, for subagents and the main session.
- **Guard, glob fix:** the three wrapper-option patterns now catch the bare `time -o`, `env -C`, `sudo -D` and `sudo -R`.
- **Settings:** `Bash(bash -n:*)` is removed from the allow list.

There are 23 new self-test checks: 12 BLOCK, 8 ALLOW and 3 settings checks. Validation passed:
- `bash -n` on 6 scripts;
- `jq empty` on 2 files;
- the validator reported **72 passed, 0 failed**.

As ordered, the self-tests were not run. Every result below comes from tracing the code by hand. By that trace, every case in `guard-selftest.sh` now passes, including `r5-time-o-marker` and `r5-env-C-relative-target`.

**One choice for you to confirm:** the brace rule is a little narrower than the literal wording "`{` together with `,` or `..`". It blocks a word only when a `{` is followed by `,` or `..` and then a `}`. Every real brace expansion has that shape, and the narrower rule lets the common `@{u}..HEAD` pass. Details are in section 2.

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: Amendment 02, repair-04, final round (docs/agent-runs/amendment-02/prompt.md; human decision (ii) on review-report-04 F1, F2 and the glob-bug note)
FILES CREATED: none in the repository. Scratchpad: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r4 (the draft; byte-identical to the installed hook)
FILES MODIFIED: .claude/hooks/guard-dangerous.sh, .claude/settings.json, .claude/hook-tests/guard-selftest.sh, .claude/agents/factory-engineer.md, docs/agents.md
COMMANDS RUN: bash -n (draft r4, 4 hooks, 2 self-tests); jq empty .claude/settings.json; jq empty .mcp.json; .claude/hooks/validate-agent-files.sh; shasum -a 256; ls -l/-la; grep; sed -n; wc -l; LC_ALL=C grep for non-ASCII bytes; git diff --no-index (r3 vs r4 drafts); git diff/status (read-only). No probes and no bash -c experiments. The self-tests were not run or chmodded.
TEST RESULTS: Self-tests not run (forbidden by the work order). All 23 new checks, plus r5-time-o-marker and r5-env-C-relative-target, traced by hand against the installed guard: all PASS. Every new BLOCK case gives ALLOW when its rule is removed (traced). Expected run of guard-selftest.sh: 0 failures. stop-hook-selftest.sh is unchanged (same sha256).
VALIDATION RESULTS: bash -n OK x6 (plus the draft); jq empty OK x2; validator "72 checks passed, 0 failed"; installed guard sha256 66dd58d53b6d8187abf775358591bec5bdbe82d08af0875d61609e1f1e33b0c8 = draft r4; -rwxr-xr-x kept on all 4 hooks; 1321 lines (was 1304).
ASSUMPTIONS: (1) Brace expansion happens inside one shell word and needs, in this order, an unquoted '{', a top-level ',' or '..', and a matching '}' (bash 3.2+, and zsh with its default options). (2) The guard's words follow shell words, with quotes removed (normalizer comment, guard :225-237). (3) Claude Code prefix rules approve allow-listed commands with no prompt (review-03 assumption 3).
RISKS: New fail-closed false positives from the brace rule: a git word whose { is followed by , or .. and then } even when it was quoted, for example `git log --format='{%h,%s}'`, `HEAD@{1}..HEAD@{0}`, or a commit message containing `{a,b}` (in the main session that commit becomes BLOCK instead of ASK; the human commits by hand). A direct `bash -n` call now prompts in every session (see INTERFACE HANDOFF).
MANUAL ACTION REQUIRED: Human: ratify, restart the session, run both self-tests (expected: 0 failures), then probes 1-9. Note that the decision-9 case `bash -n .claude/hooks/x.sh` will now show a permission prompt (the guard still allows it).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human ratification (final round, no further review), session restart, self-tests, probes 1-9.
```

## 1. Matrix basis
- Every changed file is a factory-engineer artifact under docs/agents.md "Artifact ownership":
  - `.claude/hooks/**`;
  - `.claude/hook-tests/**` (ratified in amendment-02);
  - `.claude/settings.json`;
  - `.claude/agents/**`;
  - `docs/agents.md`.
- Authority: the human's repair-04 decision (ii), quoted verbatim in the work order.
- Two documentation edits follow from the work-order notes:
  - factory-engineer.md:82 now points to the validator ("Update any role file … that tells agents to run `bash -n` directly so it points to the validator instead");
  - docs/agents.md:138 and :143 keep the guard and permission rows in sync.
- No other owner's file was touched.

## 2. Changes (file:line)

**`.claude/hooks/guard-dangerous.sh`** (1304 → 1321 lines). I drafted it as r4 in the scratchpad, ran `bash -n`, compared it with r3 (`git diff --no-index`: exactly the 4 intended hunks) and installed it with one Write. The sha256 equals the draft.
- **:36-39:** header note for repair 04.
- **:135:** new `MSG_GIT_BRACE` (the "what to do instead" text).
- **:610-611:** comment explaining the glob fix.
- **:615 (`time`/`gtime`):** `--o*|-[!-]*o*` → `--o*|-o*|-[!-]*o*`. This is the line the review called :608; the installed numbering shifted by 7 because of the header and message lines.
- **:619 (`env`/`genv`):** `--ch*|-[!-]*C*` → `--ch*|-C*|-[!-]*C*` (was :612).
- **:620 (`sudo`):** `--ch*|-[!-]*D*|-[!-]*R*` → `--ch*|-D*|-R*|-[!-]*D*|-[!-]*R*` (was :613).
- **:867-872 and :876-879, git case:** the brace rule. It is checked on every unmasked git word (the same `for a in "$cmd" "$@"` loop as repair 03) before the `--output` checks:
  ```bash
  case "$u" in
    *'{'*','*'}'*|*'{'*'..'*'}'*)
      block "git word '$u' uses shell brace expansion ({a,b} or {a..b}); the shell rewrites it before git runs, so the guard cannot read the options git receives. This applies to every agent and the orchestrator." "$MSG_GIT_BRACE" ;;
  esac
  ```

**How the brace rule is shaped, and the `@{1}` check you asked for:**
- **The shape:** a word is blocked when it has a `{`, followed later by `,` or `..`, followed later by `}`, checked on the whole unmasked word before it is split.
  - Brace expansion cannot happen without that ordered shape.
  - Checking the whole word (not the split pieces) closes a gap: a quoted space inside the braces could otherwise put `{` and `,` in different pieces.
- **`HEAD@{1}` and `stash@{0}`:** no `,` and no `..` → no match → ALLOW (r10-git-reflog-allowed, r10-git-stash-allowed-sub).
- **`HEAD~3..HEAD`:** no `{` → ALLOW.
- **`@{u}..HEAD`:** there is no `}` after the `..` → ALLOW. A literal "{ and .. anywhere" rule would have blocked it. That is the only practical difference from the wording.
- **`HEAD@{1}..HEAD@{0}`:** BLOCKED. This is a fail-closed false positive: bash does not expand it. The message gives the workaround `git log HEAD@{0} ^HEAD@{1}`.

**Wrapper options: checked for collateral matches**
- **`time`:** the GNU options are -a -f -o -p -q -v -V and the BSD options are -a -h -l -o -p. Only `-o` starts with `o`. The argument of `-f` is skipped by `wrapper_takes_next`.
- **`env`:** the options are -0 -i -u -S -P -v -C. Only `-C` (chdir) starts with `C`. The arguments of `-u` and `-S` are skipped as the next word.
- **`sudo`:** `-D` is chdir and `-R` is chroot, and both are directory changes. No other sudo option starts with D or R. The arguments of `-u`, `-g`, `-p` and similar are skipped as the next word.
- **Effect of HAS_CD:** it only blocks *relative* write targets. So `env -i`, `timeout`, `sudo -u root`, `time -p` and `env -S` still ALLOW. Existing cases r1-env-S-allowed and r5-timeout-sbt-allowed are unchanged, and the new r10-env-i-allowed, r10-sudo-u-allowed and r10-time-p-allowed pin this.

**`.claude/settings.json`**
- :30 removed `"Bash(bash -n:*)"`. Nothing was added.

**`.claude/hook-tests/guard-selftest.sh`**
- :19-21: header note.
- :469-501: new section (23 checks).

**`.claude/agents/factory-engineer.md`**
- :82, "Shell syntax checks", now reads: "run `.claude/hooks/validate-agent-files.sh`, which runs `bash -n` on every hook script and every self-test script under `.claude/hook-tests/`, and report its result. A direct `bash -n` call is not allow-listed and asks for approval."

**`docs/agents.md`**
- :138 (guard row) and :143 (permission row): one sentence appended to each (quoted below).

**Deliberately not changed:**
- dataset-engineer.md:29 and infra-engineer.md:33 still say to run `bash -n` on their own scripts (`datasets/scripts/**`, `scripts/**`). The validator does not check those paths, so pointing these agents to it would be false.
- The guard still allows those calls, but they now prompt. This is recorded in "Proposed later" and in INTERFACE HANDOFF.

## New self-test case names (guard-selftest.sh:469-501)
- **BLOCK, message "brace expansion":**
  - r10-git-diff-brace-output (main) and r10-git-diff-brace-output-sub: `git diff --outpu{t,t}=X`
  - r10-git-log-brace-claude-md and r10-git-log-brace-claude-md-sub: `git log --o{u,u}tput=CLAUDE.md`
  - r10-git-show-brace-dashes: `git show -{-,-}output=X`
  - r10-git-diff-brace-sequence and r10-git-diff-brace-sequence-sub: `git diff --outpu{t..t}=X` and `=.claude/.last-test-run`
  - r10-bash-c-git-brace: `bash -c 'git diff --outpu{t,t}=X'`
  - r10-timeout-git-brace-sub
- **ALLOW, no ask:**
  - r10-git-range-allowed, r10-git-range-allowed-sub
  - r10-git-reflog-allowed (`HEAD@{1}`), r10-git-stash-allowed-sub (`stash@{0}`)
  - r10-git-upstream-range-allowed (`@{u}..HEAD`)
- **Wrapper options:**
  - BLOCK: r10-time-o-inline (`-o.claude/.last-test-run`, "-o/--output"), r10-sudo-D-relative-target and r10-sudo-R-relative-target ("changes directory");
  - ALLOW: r10-env-i-allowed, r10-sudo-u-allowed, r10-time-p-allowed.
- **Settings:**
  - r10-bash-n-not-allowed (absent `^Bash\(bash -n`);
  - r10-no-shell-allow-rule (absent `^Bash\((ba|z|da|k)?sh[ :)]`; `shasum` and `shellcheck` do not match);
  - r10-validator-still-allowed (present).

**Does each BLOCK case fail without its rule?** Yes, by trace:
- **Brace words:** `--outpu{t,t}=X`, `--o{u,u}tput=…` and `--outpu{t..t}=…` match none of `--o…--outpu[=*]`, `*--output*` or `*format-patch*`, so they fall into `--*) ;;`. `-{-,-}output=X` only sets `gito`. HAS_OP stays 0, so the marker rule (:1196) and the constitutional rule (:1204) do not fire, and the result is ALLOW.
- **r10-time-o-inline:** `-[!-]*o*` needs a second `o`, and `.claude/.last-test-run` contains none, so the old pattern gives ALLOW.
- **sudo -D / -R:** the old patterns never set HAS_CD, so the target resolves to rag-engineer's own `modules/rag/x` and the result is ALLOW.
- **Settings checks:** r10-bash-n-not-allowed fails if the rule comes back.

**r5 traces under the fix:**
- **r5-time-o-marker:** `-o` matches `-o*` at :615 → BLOCK "time -o/--output …" → PASS.
- **r5-env-C-relative-target:** `-C` matches `-C*` at :619, so HAS_CD=1. `wrapper_takes_next env -C` skips `modules/core`. The touch target `modules/rag/x` is relative with HAS_CD=1, so :973 blocks with "…changes directory…" → PASS.

**Regressions:**
- No existing case has a git word with braces.
- The env, sudo and time cases (c-sed-env-i, r1-env-S*, r5-abs-env-sed-i, r5-abs-time-sed-i, r5-abs-sudo-u-sed-i, r9-env-git-log-output) are unchanged.
- e-ro-bash-n and i-bash-n are guard-level ALLOW and still pass.

## Changed sections, verbatim
```diff
# guard-dangerous.sh (git diff --no-index scratchpad r3 -> r4; installed = r4)
@@ -33,6 +33,10 @@
+#      Repair 04 (final round): a git word that uses shell brace expansion ({a,b} or
+#      {a..b}) is blocked for every agent and the main session; the bare wrapper
+#      options time -o, env -C and sudo -D / -R are caught (before, only clusters such
+#      as -po were).
@@ -128,6 +132,7 @@
+MSG_GIT_BRACE="write every git option and argument literally, without braces that hold a comma or .. (for example: git diff --stat, or git diff > /private/tmp/claude-<id>/x.diff). Reflog selectors such as HEAD@{1} or stash@{0} and plain ranges such as HEAD~3..HEAD or @{u}..HEAD are fine; to compare two @{...} selectors, write two words (git log HEAD@{0} ^HEAD@{1}) instead of HEAD@{1}..HEAD@{0}."
@@ -602,15 +607,17 @@ analyze_segment() {
         -*)
+          # Repair 04: -o*, -C*, -D* and -R* catch the bare option and its inline
+          # argument (-o, -ofile); -[!-]*X* catches it later in a cluster (-po).
           case "$wb" in
             time|gtime)
               case "$1" in
-                --o*|-[!-]*o*)
+                --o*|-o*|-[!-]*o*)
@@
-            env|genv) case "$1" in --ch*|-[!-]*C*) HAS_CD=1 ;; esac ;;
-            sudo) case "$1" in --ch*|-[!-]*D*|-[!-]*R*) HAS_CD=1 ;; esac ;;
+            env|genv) case "$1" in --ch*|-C*|-[!-]*C*) HAS_CD=1 ;; esac ;;
+            sudo) case "$1" in --ch*|-D*|-R*|-[!-]*D*|-[!-]*R*) HAS_CD=1 ;; esac ;;
@@ -857,9 +864,19 @@ analyze_segment() {
+      # Repair 04 (human decision): shell brace expansion. bash and zsh rewrite
+      # --outpu{t,t}=X into --output=X --output=X before git runs, so the word the
+      # guard reads is not the word git receives. A git word (quotes removed) that has
+      # a '{' followed later by a ',' or '..' and then a '}' -- the shape every brace
+      # expansion needs -- is blocked, also when it was quoted (fail closed).
+      # HEAD@{1}, stash@{0}, HEAD~3..HEAD and @{u}..HEAD do not have that shape.
       gitarch=0; gito=""
       for a in "$cmd" "$@"; do
         u="$(unmask "$a")"
+        case "$u" in
+          *'{'*','*'}'*|*'{'*'..'*'}'*)
+            block "git word '$u' uses shell brace expansion ({a,b} or {a..b}); the shell rewrites it before git runs, so the guard cannot read the options git receives. This applies to every agent and the orchestrator." "$MSG_GIT_BRACE" ;;
+        esac
```
```diff
# .claude/settings.json
       "Bash(cut:*)",
-      "Bash(bash -n:*)",
       "Bash(shellcheck:*)",
```
```diff
# .claude/agents/factory-engineer.md:82
-- **Shell syntax checks**: run `bash -n` (or the interpreter-appropriate check) on every hook script and every self-test script under `.claude/hook-tests/`.
+- **Shell syntax checks**: run `.claude/hooks/validate-agent-files.sh`, which runs `bash -n` on every hook script and every self-test script under `.claude/hook-tests/`, and report its result. A direct `bash -n` call is not allow-listed and asks for approval.
```
Sentence appended to docs/agents.md:138 (guard row):
> Amendment 02 repair 04 (final round): a git word with shell brace expansion (a `{` followed by `,` or `..` and then a `}`, for example `--outpu{t,t}=X`) is blocked for every agent and the main session, with the instruction to write every git option and argument literally; `HEAD@{1}`, `stash@{0}`, `HEAD~3..HEAD` and `@{u}..HEAD` still pass. The bare wrapper options `time -o`, `env -C` and `sudo -D`/`-R` are now caught, not only inside option clusters.

Sentence appended to docs/agents.md:143 (permission rules row):
> Amendment 02 repair 04: `Bash(bash -n:*)` is removed, because `bash -n -i -c '…'` runs code; hook and self-test syntax is checked with `.claude/hooks/validate-agent-files.sh`, and a direct `bash -n` call asks for approval.

The new self-test section, guard-selftest.sh:469-501 (header note at :19-21):
```bash
echo "== Amendment 02 repair 04: git brace expansion, bash -n allow rule, bare wrapper options =="
GB="brace expansion"
# bash and zsh expand these words into --output=... before git runs.
bash_case BLOCK r10-git-diff-brace-output         "" "git diff --outpu{t,t}=X" "$GB"
bash_case BLOCK r10-git-diff-brace-output-sub     rag-engineer "git diff --outpu{t,t}=X" "$GB"
bash_case BLOCK r10-git-log-brace-claude-md       "" "git log --o{u,u}tput=CLAUDE.md" "$GB"
bash_case BLOCK r10-git-log-brace-claude-md-sub   build-engineer "git log --o{u,u}tput=CLAUDE.md" "$GB"
bash_case BLOCK r10-git-show-brace-dashes         "" "git show -{-,-}output=X" "$GB"
bash_case BLOCK r10-git-diff-brace-sequence       "" "git diff --outpu{t..t}=X" "$GB"
bash_case BLOCK r10-git-diff-brace-sequence-sub   rag-engineer "git diff --outpu{t..t}=.claude/.last-test-run" "$GB"
bash_case BLOCK r10-bash-c-git-brace              "" "bash -c 'git diff --outpu{t,t}=X'" "$GB"
bash_case BLOCK r10-timeout-git-brace-sub         rag-engineer "timeout 60 git log --o{u,u}tput=X" "$GB"
# A reflog selector or a range without a comma or a later } is not brace expansion.
bash_case ALLOW r10-git-range-allowed             "" "git log HEAD~3..HEAD"
bash_case ALLOW r10-git-range-allowed-sub         rag-engineer "git log --oneline HEAD~3..HEAD"
bash_case ALLOW r10-git-reflog-allowed            "" "git show HEAD@{1}"
bash_case ALLOW r10-git-stash-allowed-sub         rag-engineer "git show stash@{0}"
bash_case ALLOW r10-git-upstream-range-allowed    "" "git log @{u}..HEAD"
# The bare wrapper options (repair-02 glob fix). r5-time-o-marker and
# r5-env-C-relative-target above pin -o and -C; these pin -ofile, sudo -D and sudo -R,
# and that unrelated wrapper options still pass.
bash_case BLOCK r10-time-o-inline                 build-engineer "/usr/bin/time -o.claude/.last-test-run true" "-o/--output"
bash_case BLOCK r10-sudo-D-relative-target        rag-engineer "sudo -D modules/core touch modules/rag/x" "changes directory"
bash_case BLOCK r10-sudo-R-relative-target        rag-engineer "sudo -R /tmp/claude-1 touch modules/rag/x" "changes directory"
bash_case ALLOW r10-env-i-allowed                 rag-engineer "env -i touch modules/rag/x"
bash_case ALLOW r10-sudo-u-allowed                rag-engineer "sudo -u root touch modules/rag/x"
bash_case ALLOW r10-time-p-allowed                rag-engineer "/usr/bin/time -p touch modules/rag/x"
# bash -n is no longer allow-listed (bash -n -i -c '...' runs code); the guard itself
# still allows it (e-ro-bash-n, i-bash-n), so it now asks for approval.
allow_rule absent  r10-bash-n-not-allowed           '^Bash\(bash -n'
allow_rule absent  r10-no-shell-allow-rule          '^Bash\((ba|z|da|k)?sh[ :)]'
allow_rule present r10-validator-still-allowed      '^Bash\(\.claude/hooks/validate-agent-files\.sh\)$'
```

### Updated guard-rule table rows
| Rule | Matches | Event | Applies to | Result | Self-tests |
|---|---|---|---|---|---|
| git brace expansion (new) | any word of a `git`/`git-*` command (after wrappers, `bash -c`, `;` chains), quotes removed, with `{` … `,` or `..` … `}` in that order | PreToolUse Bash | every subagent and the main session | BLOCK "git word '…' uses shell brace expansion …" / "write every git option and argument literally …" | r10-git-*-brace-*, r10-bash-c-git-brace, r10-timeout-git-brace-sub; ALLOW r10-git-range-*, r10-git-reflog-allowed, r10-git-stash-allowed-sub, r10-git-upstream-range-allowed |
| time -o (fixed) | `time`/`gtime` option word `--o*`, `-o*` (bare, or with the argument inline) or `-[!-]*o*` (in a cluster) | PreToolUse Bash | all | BLOCK "time -o/--output writes a file the guard cannot check." | r5-time-o-marker, r10-time-o-inline; ALLOW r10-time-p-allowed |
| env -C / sudo -D, -R as a directory change (fixed) | `env`/`genv` `--ch*`, `-C*`, `-[!-]*C*`; `sudo` `--ch*`, `-D*`, `-R*`, `-[!-]*D*`, `-[!-]*R*` | PreToolUse Bash | all | HAS_CD=1, so a relative write target is BLOCKED ("changes directory") | r5-env-C-relative-target, r10-sudo-D-relative-target, r10-sudo-R-relative-target; ALLOW r10-env-i-allowed, r10-sudo-u-allowed |
| No bash -n allow rule (settings) | `permissions.allow` has no `Bash(bash -n…)` and no shell-interpreter prefix; the validator rule is present | settings.json | all | `bash -n …` asks for approval | r10-bash-n-not-allowed, r10-no-shell-allow-rule, r10-validator-still-allowed |

## 3. Privilege delta
- **Narrowed:**
  - the allow list loses `Bash(bash -n:*)`, which now prompts;
  - the guard blocks brace-expanded git words for everyone;
  - the guard catches the bare `time -o`, `env -C` and `sudo -D`/`-R`.
- **Widened:** nothing.
- **Unchanged:** every agent's tools, ownership and autonomy.

## 4. Floor invariants
- **Dangerous-command guards:** strengthened (brace rule, wrapper-option fix).
- **Test-stop hook:** preserved; `verify-tests-ran.sh` is unchanged. Indirectly strengthened: `git … --outpu{t,t}=.claude/.last-test-run` and `time -o<marker>` no longer slip through.
- **Formatter:** preserved (untouched).
- **Secret-read, Terraform apply/destroy, destructive AWS and force-push denials:** preserved; byte-identical, as the r3→r4 diff shows.

## 5. Audits
- **Collision:** no new agent, command, hook file or matcher. One allow rule was removed, and no rule was added.
- **Orphans:** no new files. Both self-tests are still in the validator's `REQUIRED_SELFTESTS`, and every hook is referenced (the validator passes).
- **One writer:** `owner_of` is unchanged and still mirrors docs/agents.md.

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
-rwxr-xr-x guard-dangerous.sh 64138 bytes, 1321 lines (all 4 hooks -rwxr-xr-x; self-tests -rw-r--r--, not executable, as designed)
sha256 guard-dangerous.sh     66dd58d53b6d8187abf775358591bec5bdbe82d08af0875d61609e1f1e33b0c8 (= scratch draft r4)
sha256 guard-selftest.sh      b6c239f5d91b3ec4a9f2555c6b3dc1daf84f5672f25670646600001264bef0b9 (371 check lines = 348 + 23)
sha256 stop-hook-selftest.sh  f12cad5b3caa04b91c2f479276f2632d40f458537d193e6ba2051c92a933b65e (unchanged)
sha256 settings.json          eca91d4081dbb8c0e1b37e2c4ac4128289a0d3dc5fd9775c2d8172cef9e9cc8b
```
- **Frontmatter:** unchanged; covered by the validator's 72 passing checks.
- **bash 3.2:** the new code uses only `case` globs with quoted brace characters; nothing new needs bash 4.

## 7. Diff
- **Guard:** every r3 → r4 hunk is shown above. Reproduce it with `git diff --no-index` between `/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r3` and `/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r4`.
- **settings.json, factory-engineer.md and docs/agents.md:** the hunks are shown verbatim above.
- **The self-test file:** it is untracked; the new section is shown in full above.

## 8. Open items

**Accepted risks (recorded; not repaired):**
- **A:** a variable or `$1` carrying an option or a sed script. It prompts when the command is off the allow-list prefix.
- **C:** the recursive-rm check can be dodged with a later `rm` word or a `…/rm` path. It prompts.
- **D:** macOS command runners outside the wrapper list (`arch`, `script`, probably `taskpolicy` and `sandbox-exec`).
- **Backdating routes:** interpreters (`perl -e utime`, `python3 -c os.utime`) and `SetFile -m`. The `cp -p`, `rsync -t` and `install -p` routes are weaker than first recorded.
- **Item 6:** #10, #11, #13, #14, #15, amendment-01 #7, `Bash(sbt -batch:*)`, the review-03 minor rows, and the earlier "proposed later" items.
- **Item 7:** probe 1 assumes SubagentStop carries `agent_type`; without it the stop hook fails closed.
- **F3:** `GIT_CONFIG_COUNT`/`--config-env` alias respellings of `--output`. They prompt.
- **F4:** `${x:--}-output` parameter-expansion respellings. They depend on prefix matching.
- **F5:** fail-closed false positives of the repair-03 git rule (`git commit -m "… --output …"`, `--grep=format-patch`, `ls-files -co -- docs/archive`, a wrapper rescan reaching a `git` word).

**Proposed later (found during this repair; not fixed):**
1. **Brace expansion outside git.**
   - `tail -n 5 -{f,f} x` runs under the allow-listed `tail -n:*` and is a silent watcher stall: `RE_TAIL_FOLLOW` does not see `-{f,f}`.
   - Off the allow list, so they prompt: `sed -{i,i}`, `touch -{t,t}`, `sort -{o,o}`, `time -{o,o}`.
   - A braced command word such as `{git,} diff --output=X` is not checked by the guard at all. It also prompts, being off the allow-list prefix.
2. **zsh `BRACE_CCL`:** if the user's zsh enables it, `{ab}` expands without a comma. It is off by default.
3. **`terraform fmt -check:*` with a later `-write=true`:** unverified whether `-check` still prevents writing. Worth checking.
4. **Doc drift at docs/agents.md:143:** the row says the allow list covers `sort`, `uniq` and `sed -n`, but settings.json has none of them. It predates this repair; I left it.
5. **dataset-engineer.md:29 and infra-engineer.md:33 still name `bash -n` for their own scripts.** The validator does not cover `datasets/scripts/**` or `scripts/**`, so I did not repoint them. Option: name `shellcheck` (allow-listed, and it reports parse errors) or add a scoped syntax check.
6. **New brace-rule false positives:** `HEAD@{1}..HEAD@{0}`, JSON-like `--format='{…,…}'`, and commit messages that contain `{a,b}`.

## INTERFACE HANDOFF
- **New guard block (exit 2), for every subagent and the main session.** Message verbatim:
  - `BLOCKED by .claude/hooks/guard-dangerous.sh: git word '<word>' uses shell brace expansion ({a,b} or {a..b}); the shell rewrites it before git runs, so the guard cannot read the options git receives. This applies to every agent and the orchestrator.`
  - `What to do instead: write every git option and argument literally, without braces that hold a comma or .. (for example: git diff --stat, or git diff > /private/tmp/claude-<id>/x.diff). Reflog selectors such as HEAD@{1} or stash@{0} and plain ranges such as HEAD~3..HEAD or @{u}..HEAD are fine; to compare two @{...} selectors, write two words (git log HEAD@{0} ^HEAD@{1}) instead of HEAD@{1}..HEAD@{0}.`
- **The bare wrapper options are now enforced:**
  - `time -o FILE` / `-oFILE` → BLOCK "time -o/--output writes a file the guard cannot check."
  - `env -C DIR` and `sudo -D DIR` / `-R DIR` followed by a relative write target → BLOCK "…changes directory…"
- **`bash -n` (consequence to record against decision 9):**
  - `Bash(bash -n:*)` is removed from the allow list.
  - Decision 9 lists `bash -n .claude/hooks/x.sh` as an expected-allowed case. The guard still allows it with no ask: i-bash-n and e-ro-bash-n still PASS.
  - But the permission layer will now **prompt** for every direct `bash -n` call, in the main session and in subagents. That includes infra-engineer and dataset-engineer checking their own scripts.
  - Hook and self-test syntax is checked through the allow-listed `.claude/hooks/validate-agent-files.sh`.
  - As instructed, no rule was re-added.
- **Unchanged:** plain `git status`, `diff`, `log`, `show`, `ls-files`, `rev-parse` and `blame`, ranges and reflog selectors still run with no prompt. No new paths or owners.

Files:
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
- /Users/brian/Developer/repos/scholarchain/.claude/settings.json
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh
- /Users/brian/Developer/repos/scholarchain/.claude/agents/factory-engineer.md
- /Users/brian/Developer/repos/scholarchain/docs/agents.md
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r4 (draft = installed)
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r3 (baseline, untouched)
