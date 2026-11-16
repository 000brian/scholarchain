# Amendment 02 work order: faster, stall-proof factory

All evidence for this session goes in docs/agent-runs/amendment-02/.

This session changes only constitutional files (owner: factory-engineer). No application code, build file, test or prompt changes. Every change here is a proposal until I ratify it and restart Claude Code.

## Standing rules (Phase 10 version, plus the NEW lines)

- **Routing:** hub and spoke; agents never call each other. All handoffs go through the orchestrator. If a finding is still open after its repair round, stop and ask me. A BLOCKED-ON goes to the named owner, then the blocked agent reruns in a fresh context. For an unowned path, stop with BLOCKED-ON factory-engineer. An agent never edits another owner's file, even to fix an obvious mistake; it reports it.
- **Tests:** this session changes no file that `verify-tests-ran.sh` counts as source (build.sbt, project/, modules/, tests/, prompts/), so nobody runs sbt. If you find you must change such a file, stop with BLOCKED-ON and name its owner.
- **One sbt at a time (strict):** never two sbt processes. (None are expected here.)
- **No watchers:** never start `tail -f`, `tail -F`, `watch`, or any command that does not end by itself. Read files with bounded reads (`tail -n 50`, grep). Every step ends at once with no background job left. The orchestrator must not end its turn while a background agent is still running; it waits for the agent's result.
- **No memory or settings writes:** no writes under `~/.claude/`, no memory files, no `.claude/settings.local.json`. (`.claude/settings.json` is changed only by factory-engineer, as ordered below.) If you want to remember something, put it in your report.
- **When the orchestrator must stop and ask me,** its message starts with the line `STATUS: BLOCKED-ON human`.
- **Commands** (I may be away; an approval prompt stalls the run):
  - Use only: the Read, Grep, Glob, Write and Edit tools; read-only git (status, diff, log, show, ls-files); read-only shell (ls, cat, head, tail -n, wc, grep, du, df, shasum, sort, uniq, cut, `sed -n`, find without -delete or -exec); `bash -n <file>`; `jq empty <file>`; and exactly `.claude/hooks/validate-agent-files.sh`.
  - No python, java, curl, installs, docker, uvx, aws, terraform, sbt. Do not run the new self-test scripts (they would need my approval); I run them after the restart.
  - Keep these words off command lines: rm, delete, drop, truncate, destroy, apply, force, reset, printenv, secret, credential, curl. (Hook source and test cases may contain them as data; command lines may not.)
  - Edit files only with Write and Edit, never `sed -i` or shell redirection.
  - If something would need my approval, don't retry variants: skip it if optional, else finish with BLOCKED-ON.
- **No AWS, no network.**
- **Secrets:** no key, token, account ID or bucket name in any file or report. The AWS profile name below is not a secret.
- **Scope:** do only what this work order lists. Anything else goes on a "proposed later" list.
- **Repair limits:** only blocking or major findings get a repair round, all of them in one pass by factory-engineer. Minor and advisory findings are listed for me. code-reviewer's re-check covers only the repaired findings.
- **Interface handoff:** factory-engineer ends its report with `INTERFACE HANDOFF`: every rule an agent or the orchestrator must now follow differently, every new path and owner, every new guard block with its message text.
- **Evidence:** write nothing under docs/agent-runs/ (writes there are blocked). When all work is done:
  - print the summary first;
  - write each report verbatim into your session scratchpad directory (under `/private/tmp/claude-501/`) and print its absolute path;
  - run `shasum -a 256` on each file and print one `cp -n <scratchpad file> docs/agent-runs/amendment-02/<name>` command per file, each on one line, with its sha256. I run the `cp` commands. Do not commit.

  File names: agent-report-factory.md, review-report.md, repair-01.md (if any), review-report-02.md (if any).

## Human architect decisions

### 1. Flow

| Step | Who | What |
|---|---|---|
| 1 | factory-engineer | decisions 2 to 8, then validation (decision 9) |
| 2 | code-reviewer | review of the complete constitutional diff (decision 10) → review-report.md |
| 3 | factory-engineer | one repair pass for blocking or major findings, if any → repair-01.md |
| 4 | code-reviewer | re-check of the repaired findings only → review-report-02.md |

Safety rule for hook edits (hook scripts take effect on the next tool call, not after a restart):
- Write each new hook version first to your scratchpad (for example `<scratchpad>/guard-dangerous.sh.new`), run `bash -n` on it, read it through once, and only then install it over the real file with one Write.
- Never leave a hook half-edited between tool calls.
- If an installed hook blocks your own legitimate edits, stop at once with `STATUS: BLOCKED-ON human` and the evidence. I restore the file with git.
- Hooks must stay compatible with macOS /bin/bash 3.2 (no associative arrays, no `${x,,}`, no `mapfile`).

### 2. Standing rules into CLAUDE.md (handoff item 1)

Add a section `## Operating rules for agent runs` to CLAUDE.md, after "Definition of done", with this content (you may tighten wording; keep every rule):

- Hub and spoke; one repair round per finding, then the orchestrator asks the human. An agent never edits another owner's file.
- One sbt process at a time. The orchestrator never runs two source-changing agents at once; read-only code-reviewer may run alongside one.
- No watchers: no `tail -f`, `tail -F`, `watch` or other command that does not end by itself (the guard blocks them). Long jobs use the Bash tool's background option and are read with bounded reads. A step ends with no background job left.
- The orchestrator never ends its turn while a background agent is running; it waits for the result. When it must stop and ask the human, its message starts with `STATUS: BLOCKED-ON human`.
- No memory or settings writes during a run (the guard blocks writes under `~/.claude/` and to `.claude/settings.local.json`).
- Commands: sbt, read-only git and read-only shell only, unless the work order allows more. Files are edited only with Write and Edit, never `sed -i`, `perl -i` or shell redirection into the repository (the guard blocks these). If a command needs approval, do not retry variants: skip it if optional, else report BLOCKED-ON.
- Live tests (live AWS, Voyage, Anthropic, Bedrock) are tagged `RequiresAws`, compiled and never run unless the work order says so.
- Secrets: no key, token, account ID or bucket name in code, config, tests, logs or reports; keys are read only from an environment variable whose NAME comes from configuration.
- Model output that fails validation is stored only as sha256, length and error. No prompt asks for reasoning.
- Scope: build only what the work order lists; extras go on a "proposed later" list.
- Repair limits: only blocking or major findings get a repair round; all findings of one owner are repaired in one pass; the owner adds a test that fails without the fix.
- Every source-changing agent ends its report with `INTERFACE HANDOFF` (files changed, public signatures, fixtures, thread-safety, limits).
- Evidence: nothing is written under docs/agent-runs/; reports go to the session scratchpad and the human copies them with `cp -n`.
- Work orders may add phase-specific exceptions (for example a phase that allows network); the work order wins for that phase only.

This is shared, cross-phase project memory, not a per-run instruction, so it belongs in CLAUDE.md.

### 3. Faster test gate (handoff item 2, DECIDED: per-project test evidence)

I ratify this narrow change to the test-stop floor invariant. Tests are still required before any agent stops; only their scope changes for subagents, and a full `sbt check` is still required before review and before a phase ends.

**3a. `verify-tests-ran.sh`.**
- Relevant source stays as today, plus `.scalafmt.conf`, `.jvmopts` and `.sbtopts`.
- If the full marker `.claude/.last-test-run` is newer than every changed file: allow (both events), as today.
- The `STATUS: BLOCKED-ON` / `STATUS: FAILED` escape stays as today.
- **Stop (main session):** unchanged. It needs the full marker. So no phase can end without one passing `sbt check` after the last change.
- **SubagentStop:** also allow when every changed file is covered by fresh, passing sbt test reports of its project. Read the event name from the hook input (`hook_event_name`).
  - Mapping: `modules/<m>/**` → test reports under `modules/<m>/target/`; `prompts/**` → `modules/rag/target/`; `tests/**` → `tests/contracts/target/`.
  - `modules/core/**`: core is cross-built. Find its test-report folders for the Scala 3 and the Scala 2.13 builds under `modules/core/target/` and require fresh reports in both. Name the folders in your report.
  - `build.sbt`, `project/**`, `.scalafmt.conf`, `.jvmopts`, `.sbtopts`: no shortcut; the full marker is required.
  - Covered means: in that project's report folder(s), at least one `TEST-*.xml` is newer than the newest changed file of that project, and no `TEST-*.xml` newer than that file has `failures` or `errors` greater than 0.
  - sbt writes these reports itself (checked by me on Nov 16: `modules/services/target/test-reports/TEST-*.xml` exist). Before relying on it, list (read-only `find`, no -exec) the report folders of every project, including tests/contracts and both core builds. If a project has no report folder after its tests ran, stop with BLOCKED-ON build-engineer and the evidence.
  - The block message names each uncovered project and says exactly what to run: `sbt -batch "<project>/testOnly <suites>"` for the changed suites and their direct users, or `sbt check`.
- Keep the deletion skip, the target/ skip and the build.sbt-missing rule as today.

**3b. Protect the evidence.** The guard blocks every Write/Edit/MultiEdit/NotebookEdit to any path containing `/test-reports/` and every shell command that writes there (same treatment as the marker). Add matching deny rules in settings.json: `Write(**/test-reports/**)` and `Edit(**/test-reports/**)`.

**3c. CLAUDE.md "Definition of done".** Replace item 1 with:
1. After a source change, the tests of every changed project pass and the agent saw the output: `sbt -batch "<project>/testOnly <suites>"` for the suites it added or changed and their direct users. `verify-tests-ran.sh` checks this from sbt's own test reports.
2. A full `sbt check` passes after the last source change of each wave, before code-reviewer reviews that wave, and before the phase ends. A wave is the source-changing agents the orchestrator runs one after another before the next review or test-engineer step. The work order names the agent that runs this check (the wave closer); by default it is the last source-changing agent of the wave. Never repeat a passing `sbt check` without a source change.

Keep the other items (renumbered).

**3d. Role files.** Every agent file that tells the agent to run `sbt check` before reporting COMPLETE (grep for it; at least domain, hadoop, entity-resolution, spark, storage, rag, service, test, build, dependency-updater) gets the same rule in one or two lines: run the changed projects' targeted tests; run the full `sbt check` when the work order makes you the wave closer, or when you changed build.sbt, project/** or the formatter config. test-engineer still reports exact counts from whatever it ran.

**3e. factory-engineer.md and docs/agents.md.** Update the floor-invariant text and the hooks table so they describe the new rule exactly (subagents: targeted evidence; main session: full marker), with "ratified in amendment-02". The invariant otherwise stays: no agent may weaken it further.

### 4. Close the Bash write gap (handoff item 3)

Guard additions, for subagents AND the main session (the orchestrator never edits files through the shell either):
- Block `sed -i`, `sed --in-place`, `perl -i` (any `-…i…` flag combination such as `-pi`), and `ed`/`ex` editing, everywhere. Message: "Use the Edit tool."
- Block shell writes into the repository outside the caller's territory. A write is: a `>` or `>>` redirection target, a `tee` argument, the destination of `cp`, `mv`, `install`, `rsync`, `ln`, and any path argument of `touch`.
  - Never a write: `2>&1`, `>&2` and other `&N` targets; `/dev/null`, `/dev/stdout`, `/dev/stderr`.
  - Always allowed targets: under `/tmp/claude-*` and `/private/tmp/claude-*`.
  - Inside the repository: allowed only when `owner_of` gives the caller's own territory. The main session has no territory, so it may not shell-write inside the repository at all.
  - A target the guard cannot read literally (contains `$`, a backtick, `*`, `?` or `~`) is blocked, with the message "use a literal path".
  - Outside the repository and outside the scratch areas: blocked.
- The existing marker, constitutional and evidence rules stay; the new rules add to them.
- Fix the known false positive: a read-only command (only cat, grep, `sed -n`, head, tail -n, ls, wc, shasum, find without -exec/-delete) that merely mentions `docs/agent-runs/` or `.claude/` must pass with no ask. The `ops` check must match command words in command position, not the same letters inside a grep pattern or path.

### 5. MCP servers (handoff item 4)

Done by me before this run: uv installed; a read-only AWS profile `scholarchain-readonly` (IAM user with the AWS managed policy `job-function/ViewOnlyAccess`, created by hand in the console or CLI; recorded in my gate as a manual action); `SCHOLARCHAIN_AWS_READONLY_PROFILE=scholarchain-readonly` and `SCHOLARCHAIN_AWS_REGION=us-east-1` exported in my shell profile; the `github` server switched off in my own `.claude/settings.local.json` (no GitHub token is set).

Change `.mcp.json` to pin every server:
- aws-api: `awslabs.aws-api-mcp-server@1.3.33`
- aws-docs: `awslabs.aws-documentation-mcp-server@1.1.21`
- github: `ghcr.io/github/github-mcp-server@sha256:f6056fc10bfffcc8c81a6417a2e9ca12808647e8dd6049642a4feaadee0e0f8a`
- terraform: `hashicorp/terraform-mcp-server@sha256:11dd418bba6003dd5777e9d0979b68de59929a155b355f338f407947e00e9d80`

(Versions found on PyPI on Nov 16; they may not be the newest, which is fine for pinning. Digests from my `docker inspect`.) Keep every env setting as is. Update the MCP table in docs/agents.md: pinned versions, the profile name and its policy, "github defined but switched off locally (no token)", and that uv is now installed. Do not run uvx or docker.

### 6. Ownership and rule-file cleanups (handoff item 5)

- **Release records:** new path `releases/**`, owner deploy-engineer. Records there are written only by approved scripts that deploy-engineer runs; nobody hand-edits them. Update docs/agents.md (ownership table and lifecycle routing), the guard's `owner_of`, deploy-engineer.md, and `.claude/commands/deploy.md` if it mentions where records go.
- **Real configuration files:** new path `config/**`, owner infra-engineer: per-environment ScholarChain config JSON (plain JSON, no comments, only environment-variable NAMES, never values). Update docs/agents.md, `owner_of`, infra-engineer.md.
- **Wording fixes:** read `docs/agent-runs/amendment-01/review-report.md` and apply its findings #4, #5, #6, #8 and #10 where they touch files you own. Finding #7 (old build comments) is build-engineer's; list it under proposed later.
- **Guard self-tests:** save the amendment-01 guard test cases (read `docs/agent-runs/amendment-01/agent-report.md` or wherever they are recorded; say where you found them, or that you did not) plus a case for every new rule in this work order, as `.claude/hook-tests/guard-selftest.sh`. Add `.claude/hook-tests/stop-hook-selftest.sh` for decision 3: it builds a throwaway git repository under `/tmp/claude-*` with fake modules, changed files and fake `TEST-*.xml` files (passing, failing, stale), sets `CLAUDE_PROJECT_DIR` to it, and checks Stop vs SubagentStop outcomes, the BLOCKED-ON escape, core's two builds, and build files needing the full marker. Both scripts feed JSON events to the hooks, print one PASS/FAIL line per case and a total, exit non-zero on any failure, and write nothing outside `/tmp/claude-*`. They live outside `.claude/hooks/` on purpose (the validator flags any unreferenced file there). Run them with `bash`; do not chmod them (the guard allows chmod only on `.claude/hooks/*.sh`). Extend the validator so it runs `bash -n` on them.
- **Dropped:** the automatic value check for `version.scalafmt` and `image.java-runtime`. It stays a recorded risk covered by code review.

### 7. Stall guards (handoff item 7, plus memory writes)

Guard additions, for subagents and the main session:
- Block `tail -f`, `tail -F`, `tail --follow` (any form, also inside a pipe), `watch`, and `less +F`. Message: "No watchers: read with tail -n or grep, and finish the step."
- Block (exit 2, not ask) every Write/Edit/MultiEdit/NotebookEdit and shell write under `$HOME/.claude/` and to `.claude/settings.local.json` and `CLAUDE.local.md`. Message: "Memory and settings writes are off during runs; put the note in your report." (Phase 10: two memory-file approval prompts stalled the run.) Reads stay allowed except where they are already denied.

### 8. `.DS_Store` (handoff item 6)

No change for you. `.gitignore` already ignores `.DS_Store`; I untrack the four files myself with git.

### 9. Validation (factory-engineer, all required)

- `bash -n` on every hook and both self-test scripts; `jq empty` on settings.json and .mcp.json; `.claude/hooks/validate-agent-files.sh` passes (report its pass and fail counts).
- Collision, orphan and one-writer audits (including `releases/**` and `config/**` in docs/agents.md, `owner_of` and the validator's roster).
- A table of every guard rule you added or changed: the exact command or path it matches, the event, who it applies to, block or ask, and its self-test case names. Also the cases you expect to stay allowed (`sbt -batch check > <scratchpad>/check.log 2>&1` in the background; `grep -n x docs/agent-runs/phase-10/prompt.md`; a Write inside the caller's own territory; `tail -n 50 <file>`; `bash -n .claude/hooks/x.sh`).
- The complete diff (`git diff` plus the full text of every new file).

### 10. Review (code-reviewer, read-only)

Review the complete constitutional diff. Rule on:
- every floor invariant: preserved, or changed only as decision 3 ratifies;
- the stop hook: a source change with no fresh passing tests still blocks every subagent; a failing report blocks; a stale report blocks; build files need the full marker; the main session still needs the full marker; core needs both builds; no path lets an agent stop COMPLETE without some test run after its change;
- the guard: each new rule matches what decisions 3b, 4 and 7 say; it cannot be sidestepped by a trivial respelling (quoting, `bash -c`, an extra space, `command sed`, `/usr/bin/sed`); it does not block the expected-allowed cases in decision 9; every message says what to do instead;
- bash 3.2 compatibility; ownership tables in sync (docs/agents.md, `owner_of`, validator roster); `.mcp.json` pins and JSON validity; CLAUDE.md and every role file consistent with the new definition of done; wording fixes applied as #4, #5, #6, #8, #10 say;
- the self-test scripts actually test each rule (a test that would pass with the rule removed is a finding).

Return APPROVE or REQUEST_CHANGES with severity, file:line, failure input, impact and owner for each finding.

## Gate (factory-engineer's primary gate in docs/agents.md)

- Human constitutional ratification and code review: code-reviewer returns APPROVE.
- Validation in decision 9 passes.
- No floor invariant weakened beyond decision 3.
- After I restart Claude Code, my probes pass:
  1. `bash .claude/hook-tests/guard-selftest.sh` and `bash .claude/hook-tests/stop-hook-selftest.sh`: 0 failures.
  2. `bash .claude/hooks/validate-agent-files.sh`: 0 failures.
  3. "Run: tail -f README.md" → blocked.
  4. "Use the rag-engineer agent to run: echo x > modules/core/probe.txt" → blocked.
  5. "Use the rag-engineer agent to run: sed -i '' 's/a/a/' modules/rag/README.md" → blocked.
  6. "Run: grep -n Standing docs/agent-runs/phase-10/prompt.md" → runs with no ask.
  7. Stop-hook probe (Phase 1 probe 5 again): "Use the domain-engineer agent to add a comment line to one file in modules/core/src/main, then finish without running any test" → its stop is refused; then I revert the file with git.
  8. "Run: echo 'terraform destroy'" → still blocked (regression).
  9. `/mcp` shows aws-api, aws-docs and terraform connected, github off.
