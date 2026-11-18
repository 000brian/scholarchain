STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: Phase 14 session 1 step F (decision 3), prompt sha256 b1f951683cc708c297d3e74d6b21332f5218324e4db0086d33307ec8bf8b88a2 (verified with shasum), HEAD af140ce0d9545ae1ef6fc4061d2407007cf45a23 (verified with git rev-parse)
FILES CREATED: none in the repository
FILES MODIFIED: .claude/agents/infra-engineer.md (line 4, tools field only)
COMMANDS RUN: shasum -a 256 docs/agent-runs/phase-14/prompt.md; git rev-parse HEAD; git status --short; grep -n (prompt headings; tools lines; mcp__ IDs across CLAUDE.md, docs/agents.md, .claude/agents, .claude/hooks, .claude/settings.json, .mcp.json, .claude/commands); mkdir -p (scratchpad report folder); .claude/hooks/validate-agent-files.sh; jq empty .claude/settings.json .mcp.json; grep/cut/uniq -d over agent names; ls .claude/{agents,commands,hooks,hook-tests}; ls -l .claude/hooks; grep -c '^tools:'; grep -n 'hooks/' .claude/settings.json; git diff --stat; git diff
TEST RESULTS: not applicable (no relevant source change; no sbt run)
VALIDATION RESULTS: validator "72 checks passed, 0 failed." exit 0; jq empty exit 0
ASSUMPTIONS: tool IDs taken from the servers' own instructions visible in this session: terraform names `search_provider_docs` (and `get_provider_details`, `search_modules`, `get_module_details`); aws-docs names `read_sections`.
RISKS: none new. The grant stays inside the ratified fence (terraform registry docs, aws-docs read). Tool IDs take effect only after ratification and restart; `/mcp` confirms the servers connect.
MANUAL ACTION REQUIRED: review the diff, ratify, commit, restart Claude Code from a terminal, run `/mcp`.
BLOCKED-ON OWNER: human (constitutional ratification)
BLOCKED-ON EVIDENCE: diff below; constitutional change is a proposal until ratified
NEXT GATE: code-reviewer read-only review of the diff, then human ratification and restart

## Matrix basis
- docs/agents.md "Tool fences": infra-engineer MCP = "aws-api (read-only), terraform (registry docs), aws-docs". `search_provider_docs` is a terraform registry-docs tool; `read_sections` is an aws-docs read tool. Both within the ratified server grants. Human decision 3 (prompt lines 121-130) directs exactly this edit.

## Changes
- .claude/agents/infra-engineer.md:4: `mcp__terraform__search_providers` (non-existent tool) -> `mcp__terraform__search_provider_docs`; added `mcp__aws-docs__read_sections` (after read_documentation). Nothing else changed.
- docs/agents.md: not changed. Its tool-fence matrix names servers only ("terraform (registry docs)", "aws-docs"), never individual tool IDs (grep for `mcp__` in docs/agents.md and CLAUDE.md: no hits).
- deploy-engineer: not changed. .claude/agents/deploy-engineer.md:4 reads exactly `tools: Read, Grep, Glob, Bash, mcp__aws-api__call_aws, mcp__aws-api__suggest_aws_commands`, which fits decision 13 (read-only checks and a report). Its "executes scripts" text stays (planned deviation 1, not amended).
- No allow rule added; .claude/settings.json and .mcp.json untouched.

## Privilege delta
- infra-engineer: terraform server: one non-existent tool ID replaced by the real registry-docs search tool (corrects a dead grant; no new server). aws-docs server: +read_sections (read-only documentation reading, same server already granted). No Write/Edit/Bash, ownership, permission or autonomy change.
- All other agents: unchanged.

## Floor invariants
- Dangerous-command guards: preserved (guard-dangerous.sh untouched).
- Test-stop hooks: preserved (verify-tests-ran.sh untouched; Stop and SubagentStop wiring unchanged).
- Formatter behavior: preserved (format-scala.sh untouched).
- Secret-read denial: preserved (settings.json untouched).
- Terraform apply/destroy denial: preserved (settings.json and guard untouched; new tools are registry/documentation reads only).
- Destructive AWS denial: preserved (aws-api grant unchanged).
- Force-push denial: preserved.

## Audits
- Collision: 18 agent files, duplicate `name:` check empty; 5 commands with distinct names; hook matchers unchanged; no new permission rule.
- Orphan: hooks referenced in settings.json (guard-dangerous, format-scala, validate-agent-files --hook, verify-tests-ran) all exist; all four existing hooks are referenced; validator passed its orphan/parity checks.
- One-writer: no ownership change; infra-engineer.md remains factory-engineer territory; code-reviewer tools still `Read, Grep, Glob`.

## Validation
- JSON: `jq empty .claude/settings.json .mcp.json` exit 0.
- Shell syntax: validator (runs bash -n on hooks and hook-tests) "72 checks passed, 0 failed." exit 0.
- Executable bits: all four hooks -rwxr-xr-x.
- Frontmatter: every agent file has exactly one `tools:` line (grep -c = 1 for all 18); validator checks name/description/tools passed.

## Diff
```
-tools: Read, Grep, Glob, Write, Edit, Bash, mcp__aws-api__call_aws, mcp__aws-api__suggest_aws_commands, mcp__terraform__search_providers, mcp__terraform__get_provider_details, mcp__terraform__search_modules, mcp__terraform__get_module_details, mcp__aws-docs__search_documentation, mcp__aws-docs__read_documentation, mcp__aws-docs__recommend
+tools: Read, Grep, Glob, Write, Edit, Bash, mcp__aws-api__call_aws, mcp__aws-api__suggest_aws_commands, mcp__terraform__search_provider_docs, mcp__terraform__get_provider_details, mcp__terraform__search_modules, mcp__terraform__get_module_details, mcp__aws-docs__search_documentation, mcp__aws-docs__read_documentation, mcp__aws-docs__read_sections, mcp__aws-docs__recommend
```
git status --short: ` M .claude/agents/infra-engineer.md`, `?? docs/agent-runs/phase-14/prompt.md` (human's, untouched). git diff --stat: 1 file changed, 1 insertion(+), 1 deletion(-).

## Open items
- Rule deviation (self-reported): the name-collision check used `uniq -d`, which the Phase 14 standing rules list as a never-use command. It ran without a prompt, is read-only and wrote nothing; reported for the record.
- No refusals. Decision for the human: ratify the one-line diff.

## INTERFACE HANDOFF
- Files changed: .claude/agents/infra-engineer.md (frontmatter tools line only).
- Public signatures / fixtures / thread-safety / limits: not applicable (agent constitution, no code).

## VALIDATION EVIDENCE
- validator: `.claude/hooks/validate-agent-files.sh` exit 0, "72 checks passed, 0 failed."
- jq: `jq empty .claude/settings.json .mcp.json` exit 0
- finished: after the last command above
- files changed after these runs: no
