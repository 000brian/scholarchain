# Review report 05: Amendment 02, final re-check after repair-04

VERDICT: APPROVE (for the three items in the human's repair-04 decision only)

All three items are CLOSED. I traced every requested input against the installed guard. Every decision-9 expected-allowed case still gets ALLOW from the guard, with no block and no ask. I did not run anything, so every result below comes from reading and tracing the code.

## Ruling per item

**Item 1: settings.json, `Bash(bash -n:*)` removed. CLOSED.**
- `.claude/settings.json:5-49` (the allow list) has no `bash -n` entry, and no rule that starts with a shell name (`sh`, `bash`, `zsh`, `dash`, `ksh`).
- `Bash(.claude/hooks/validate-agent-files.sh)` is still present at `:32`.
- The self-tests at `guard-selftest.sh:499-501` match these facts.
- `r10-bash-n-not-allowed` would fail if the rule came back.
- The `r10-no-shell-allow-rule` pattern does not match `shasum` or `shellcheck`.

**Item 2: guard blocks git brace expansion for subagents and the main session. CLOSED.**
- The new check is at `guard-dangerous.sh:876-879`, inside the `git|git-*` branch of `analyze_segment`.
- It runs on every git word, with quotes removed and before the word is split, using the pattern `*'{'*','*'}'*|*'{'*'..'*'}'*`.
- `block()` (`:57-64`) exits 2 whatever the agent is. The only check that looks at the agent before `analyze_segment` is the commit/push rule (`:1047`).
- Traces (all BLOCK, message "uses shell brace expansion"):
  - `git diff --outpu{t,t}=X`: matches `{`, `,`, `}` in that order. BLOCK for the main session and for a subagent.
  - `git log --o{u,u}tput=CLAUDE.md`: BLOCK (main session and build-engineer).
  - `git show -{-,-}output=X`: BLOCK.
  - `git diff --outpu{t..t}=X` and `…=.claude/.last-test-run`: match the `..` pattern. BLOCK (main session and rag-engineer).
  - `bash -c 'git diff --outpu{t,t}=X'`: the normalizer pulls the `-c` code out onto its own line, and that line reaches the git branch. BLOCK.
  - `timeout 60 git log --o{u,u}tput=X`: the wrapper and its duration are stripped (`:630-632`). BLOCK.
- **The narrower shape satisfies the human's wording.** The rule blocks only `{`, then `,` or `..`, then `}`, in that order. Bash and default zsh expand a brace only when an unquoted `{` is followed by a top-level comma or a `x..y` sequence and then a matching `}`, so every real brace expansion inside a git word has that shape. The narrowing only lets through strings that are not brace expansion, such as `@{u}..HEAD`.
- **The new BLOCK tests would fail with the rule removed.** I traced them against r3 (the baseline guard):
  - The brace words match none of `--o…--outpu[=*]`, `*--output*` or `*format-patch*`.
  - So HAS_OP stays 0, the marker rule (`:1196`) and the constitutional rule (`:1204`) do not fire, and the result is ALLOW.
  - `-{-,-}output=X` does not contain the text `--output` (it reads `-}output`), so it also gives ALLOW.
- **The ALLOW cases still pass** (r10-git-range-*, `HEAD@{1}`, `stash@{0}`, `@{u}..HEAD`). None of them has the ordered shape. `--oneline` reaches `--*) ;;`, which does nothing.
- **No existing test case has a git word with braces**, so nothing that used to pass is affected.

**Item 3: the three wrapper-option patterns. CLOSED.**
- The three lines (r3 `:608/:612/:613`, now `:615/:619/:620`) read:
  - `--o*|-o*|-[!-]*o*`
  - `--ch*|-C*|-[!-]*C*`
  - `--ch*|-D*|-R*|-[!-]*D*|-[!-]*R*`
- These are the exact strings review-04 recommended.
- The r3 scratch copy shows the old patterns on those three lines. The r4 scratch copy matches the installed guard on all three lines, the new message (`:135`) and the brace check (`:877`).
- Traces:
  - **r5-time-o-marker** (`guard-selftest.sh:339`): `-o` matches `-o*` → BLOCK "time -o/--output writes a file…" → **PASS**.
  - **r5-env-C-relative-target** (`:340`): `-C` matches `-C*`, so HAS_CD=1. `wrapper_takes_next env -C` skips `modules/core` (C is in the option spec and is the last letter). The touch target `modules/rag/x` is relative with HAS_CD=1, so `:973-975` BLOCKs with "…changes directory…" → **PASS**.
  - **r10-time-o-inline:** BLOCK. With the old pattern it gives ALLOW, because `.claude/.last-test-run` has no second `o`. So the test fails without the fix.
  - **r10-sudo-D-relative-target and r10-sudo-R-relative-target:** BLOCK "changes directory". D and R are both in sudo's option spec, so the directory argument is skipped. With the old patterns they give ALLOW.
  - **r10-env-i-allowed, r10-sudo-u-allowed, r10-time-p-allowed:** ALLOW. None of `-i`, `-u`, `-p` matches the new patterns, and the target is in rag-engineer's own territory.
- **No regressions found.** Of the existing env, sudo and time test cases, only `r9-env-git-log-output` uses an option-like word, and its `GIT_PAGER=cat` is not an option word.

## Decision-9 expected-allowed cases

The guard result is ALLOW for each, with no block and no ask. repair-04 changed only the git branch, the time/env/sudo option patterns and one message variable, and none of these cases reaches those.

| Case | Guard trace | Permission layer |
|---|---|---|
| `sbt -batch check > <scratchpad>/check.log 2>&1` | The redirect goes to `/private/tmp/claude-*`, which is allowed (`:984`). `2>&1` is just output duplication. ALLOW. | Allowed (`Bash(sbt -batch:*)`) |
| `grep -n x docs/agent-runs/phase-10/prompt.md` | The constitutional pattern matches, but HAS_OP=0, so there is no ask. ALLOW. | Allowed (`grep:*`) |
| Write in own territory | `guard_path_write`: owner equals the agent (`:1290`). ALLOW. | Allowed |
| `tail -n 50 <file>` | `-n` is not a follow option. ALLOW. | Allowed (`tail -n:*`) |
| `bash -n .claude/hooks/x.sh` | No `-c`, so no code is pulled out. HAS_OP=0. ALLOW (i-bash-n `:265`, e-ro-bash-n `:212`). | **Now prompts.** |

The `bash -n` prompt is the direct result of the human's own decision to remove `Bash(bash -n:*)`. It is not a defect. Hook and self-test syntax is now checked through the allow-listed validator.

## What to expect from probe 1 (by trace)

- **`guard-selftest.sh`: 0 failures.**
  - The 2 failures review-04 predicted (r5-time-o-marker, r5-env-C-relative-target) now pass.
  - The 23 new r10 checks pass.
  - No earlier case is affected.
  - This depends on jq being on PATH, which the harness requires.
- **`stop-hook-selftest.sh`: unchanged by repair-04.**
  - repair-04 did not list it as modified, and factory reports the same sha256. I could not compute the hash myself.
  - It should give the same result as the previously reviewed baseline, which is 0 failures.
  - This rests on the recorded item 7 assumption that SubagentStop events carry `agent_type`. If they do not, the hook fails closed.

## Findings table (the three items)

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| none | none | none | none | none |

## Noted, accepted risk (no severity; not to be repaired)

- A braced command word, such as `{git,} diff --output=X`, never reaches the git branch. It prompts because it does not start with an allow-listed prefix. factory already disclosed this.
- Brace expansion outside git, such as `tail -n 5 -{f,f} x`, which runs under the allow-listed `tail -n:*`. factory already disclosed this.
- New false blocks from the brace rule: `HEAD@{1}..HEAD@{0}`, `--format='{%h,%s}'`, and a commit message containing `{a,b}`.
- `dataset-engineer.md:29` and `infra-engineer.md:33` still tell those agents to run `bash -n` on their own scripts, which now prompts.
- Pre-existing doc drift at `docs/agents.md:143`: it says the allow list covers `sort`, `uniq` and `sed -n`, but settings.json has none of them.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Amendment 02, final re-check after repair-04 (docs/agent-runs/amendment-02/prompt.md); the three human repair-04 items plus the decision-9 expected-allowed cases only
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run. By trace, all r10 cases in guard-selftest.sh:470-501 give their expected results, r5-time-o-marker and r5-env-C-relative-target now PASS, and the new BLOCK cases give ALLOW against the r3 code. Expected probe 1: guard-selftest.sh 0 failures; stop-hook-selftest.sh unchanged by repair-04.
VALIDATION RESULTS: Read guard-dangerous.sh :44-163, :211-451, :560-680, :840-1321; settings.json :1-60; guard-selftest.sh :25-69, :440-508 and grep hits (:212, :265, :339-340, :425); repair-04.md; review-report-04.md; grep of the r3 and r4 scratch copies on the changed lines (r4 matches the installed guard at :135, :615, :619, :620, :877; r3 shows the old patterns at :608, :612, :613). Item 1 CLOSED, item 2 CLOSED (the narrowed shape is adequate), item 3 CLOSED. Decision-9 cases: guard ALLOW, no ask.
ASSUMPTIONS: (1) The installed guard equals r4 (checked on the changed lines; I could not compute the sha256). (2) stop-hook-selftest.sh is unchanged (factory's sha claim, not verified). (3) Bash and default zsh brace-expansion rules as stated above (BRACE_CCL off).
RISKS: Only the "noted, accepted risk" list above; none affects the three items.
MANUAL ACTION REQUIRED: Human: ratify, restart the session, run both self-tests (expected 0 failures), then probes 1-9. Expect a permission prompt for direct bash -n calls.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human ratification of Amendment 02, session restart, self-tests, probes 1-9.
```

Files:
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
- /Users/brian/Developer/repos/scholarchain/.claude/settings.json
- /Users/brian/Developer/repos/scholarchain/.claude/hook-tests/guard-selftest.sh
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r3
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/guard-dangerous.sh.r4
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/repair-04.md
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/a639b54e-c340-49f8-b6f1-643d8641143b/scratchpad/review-report-04.md
