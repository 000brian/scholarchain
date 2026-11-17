STATUS: BLOCKED-ON human (proposal complete; constitutional ratification and session restart required)
OWNER: factory-engineer
WORK ORDER: Phase 12, session 1, step F (decision 3 items 1-6); prompt sha256 5744ab73aa42946f1c556d1257a45031fd57868b75480de623879a9c73de7f56; HEAD 00fc238
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/.mcp.json
- /Users/brian/Developer/repos/scholarchain/.claude/settings.json
- /Users/brian/Developer/repos/scholarchain/.claude/agents/dataset-engineer.md
- /Users/brian/Developer/repos/scholarchain/.claude/agents/infra-engineer.md
- /Users/brian/Developer/repos/scholarchain/docs/agents.md
COMMANDS RUN:
- grep -n (section headings of the phase-12 prompt; read-only)
- .claude/hooks/validate-agent-files.sh (no arguments) -> "72 checks passed, 0 failed.", exit 0
- jq empty .claude/settings.json .mcp.json -> exit 0
- git status --short; git diff --stat; date; ls -l .claude/hooks (read-only)
TEST RESULTS: none (no sbt in this phase; no sbt source touched)
VALIDATION RESULTS: validator exit 0 (72/0); jq exit 0; all four hook scripts executable (-rwxr-xr-x); frontmatter of both edited agent files unchanged (name, description, tools present; validator passed)
ASSUMPTIONS:
- Item 3: infra-engineer.md said "`bash -n` and `shellcheck` on scripts"; a literal replacement would read "`shellcheck` and `shellcheck`", so the step now says "`shellcheck` on scripts" once.
- Item 4: the formatting text uses the exact allowed command forms; the old `terraform fmt -check -recursive` (no path) is replaced by `terraform fmt -recursive -check infra/terraform`. The `terraform validate` wording in the role file is unchanged (the `-chdir` form is a project detail recorded in docs/agents.md and the work order, not a timeless role rule; item 4 does not ask for it).
- Item 2: the sort/uniq/sed -n claim is replaced by an explicit statement that they are not allow-listed and ask for approval.
RISKS:
- `Bash(terraform -chdir=infra/terraform/environments/course validate:*)` is a prefix rule; extra trailing arguments to validate (for example `-json`) are allowed. validate is read-only, so the risk is low. The guard still runs first.
- `mcp<2` in .mcp.json is a version bound, not an exact pin, for a transitive dependency; the server package pins are unchanged.
MANUAL ACTION REQUIRED: human reviews the diff, ratifies, commits, restarts Claude Code, runs `/mcp` (aws-api, aws-docs, terraform connected).
BLOCKED-ON OWNER: human (ratification)
BLOCKED-ON EVIDENCE: constitutional change; not effective before ratification and restart.
NEXT GATE: code-reviewer reads the diff (read-only), then human ratification.

## Matrix basis
Human decision 3 items 1-6 in docs/agent-runs/phase-12/prompt.md (human-owned work order) authorizes each change. factory-engineer owns all five files (docs/agents.md artifact ownership table).

## Changes
- .mcp.json: aws-api and aws-docs `args` gain `"--python", "3.12", "--with", "mcp<2"` before the package name. Nothing else changed.
- .claude/settings.json: allow list gains exactly `Bash(terraform -chdir=infra/terraform/environments/course validate:*)` and `Bash(terraform fmt -recursive infra/terraform)` (inserted after `Bash(terraform validate:*)`). Nothing else changed.
- .claude/agents/dataset-engineer.md: procedure step 4 `bash -n` -> `shellcheck`.
- .claude/agents/infra-engineer.md: procedure step 4 rewritten: format (write HCL, run `terraform fmt -recursive infra/terraform` once, then `terraform fmt -recursive -check infra/terraform`, never fix alignment by hand); `terraform validate` and `shellcheck` on scripts (`bash -n` removed); security scan in agent runs = `shellcheck` plus the `code-reviewer` checklist, a Terraform scanner if any is run by the human; plan.sh sentence unchanged.
- docs/agents.md: Permission rules row: allow-list claim now lists `sbt -batch`, `grep`, `head`, `tail -n`, `ls`, `wc`, `shasum`, `cut`; states `sort`, `uniq`, `sed -n` are not allow-listed; adds the two Phase 12 rules with reasons and "no init, plan, script run, or scanner". MCP table: aws-api and aws-docs trust-boundary cells note `uvx` runs with `--python 3.12 --with 'mcp<2'` (amendment-02 known gap 2; package pin unchanged). Pinned-to column unchanged.

## Privilege delta
- All agents (session-level allow list): widened by two narrow Bash allow rules (terraform validate via -chdir on the course environment; terraform fmt rewriting whitespace in infra/terraform). Both human-ratified in decision 3 item 5. No ask or deny rule changed.
- Agent tools fields: unchanged for every agent. Ownership: unchanged. MCP grants: unchanged (only the uvx runtime flags changed).

## Floor invariants
- Dangerous-command guards: preserved (guard-dangerous.sh untouched; PreToolUse hook entry unchanged).
- Test-stop hooks: preserved (verify-tests-ran.sh and Stop/SubagentStop entries untouched).
- Formatter behavior: preserved (format-scala.sh and PostToolUse entry untouched).
- Secret-read denial: preserved (deny list untouched).
- Terraform apply/destroy denial: preserved (deny rules untouched; new allow rules match neither; terraform fmt rule is exact).
- Destructive AWS denial: preserved (deny list untouched).
- Force-push denial: preserved (deny list untouched).

## Audits
- Collision: no agent or command added or renamed; the two new allow rules do not overlap any ask or deny rule (`terraform init:*`, `terraform plan:*` asks and apply/destroy denies are distinct prefixes). No finding.
- Orphan: no hook, agent, or command added or removed; validator (which checks references) passed. No finding.
- One-writer: no ownership change; all five files are factory-engineer artifacts. The fmt rule writes only under infra/terraform/** (infra-engineer territory). No finding.

## Validation
- `.claude/hooks/validate-agent-files.sh` -> "validate-agent-files.sh: 72 checks passed, 0 failed." exit 0 (includes bash -n on hooks and self-tests, frontmatter checks).
- `jq empty .claude/settings.json .mcp.json` -> no output, exit 0.
- Executable bits: format-scala.sh, guard-dangerous.sh, validate-agent-files.sh, verify-tests-ran.sh all -rwxr-xr-x.
- Frontmatter: name, description, tools present in both edited agent files (only body lines changed).

## Diff
`git diff --stat`: dataset-engineer.md 2 +-, infra-engineer.md 2 +-, settings.json 2 ++, .mcp.json 4 ++--, docs/agents.md 6 +++---; 5 files, 9 insertions, 7 deletions. `git status --short`: the five files above modified, plus untracked docs/agent-runs/phase-12/ (human-owned, not touched). The orchestrator saves the full `git diff` for code-reviewer.

## Open items
- No refusals. Human decision needed only for ratification.

INTERFACE HANDOFF
- Files changed: the five listed above.
- Effective after ratification and restart: agents may run `terraform -chdir=infra/terraform/environments/course validate` and `terraform fmt -recursive infra/terraform` without approval; uvx-based MCP servers start with Python 3.12 and mcp<2.
- No public signatures, fixtures, or thread-safety concerns (configuration only).

VALIDATION EVIDENCE
- validate-agent-files.sh -> exit 0 (72 checks passed, 0 failed)
- jq empty .claude/settings.json .mcp.json -> exit 0
- finished: 2026-11-17 01:41:55 CST
- files changed after these runs: no
