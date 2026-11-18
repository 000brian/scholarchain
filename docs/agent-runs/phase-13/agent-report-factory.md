# factory-engineer report, Phase 13 session 1, step F (decision 3)

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md, decision 3 items 1-6; prompt sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7 (verified with shasum); start HEAD 15df6fb01dbf487fa8aae6209b404856ca035b09 (verified)
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED: .mcp.json; docs/agents.md; .claude/agents/infra-engineer.md
COMMANDS RUN: shasum -a 256 (prompt); git rev-parse HEAD; git status --short; grep -n / grep -rln (guard and validator lookups, no banned words; the Grep tool is not available in this session); .claude/hooks/validate-agent-files.sh; jq empty .claude/settings.json .mcp.json; git diff --stat; git diff -U1; ls -l .claude/hooks
TEST RESULTS: no sbt source changed; no tests required. Self-tests not run (human runs them after restart; agents never run .claude/hook-tests/**).
VALIDATION RESULTS: validate-agent-files.sh 72 checks passed, 0 failed, exit 0; jq empty exit 0 on both files
ASSUMPTIONS: ${VAR:-default} expansion in .mcp.json is supported by Claude Code: unverified (no WebFetch; no repo file documents it other than the prompt itself). Change kept as decided.
RISKS: if Claude Code does not expand the :-default form, AWS_API_MCP_PROFILE_NAME/AWS_REGION would receive literal text and aws-api would still fail (visible at /mcp; JSON stays valid). If aws-api still fails with the variables exported, next suspect: uvx startup time (package resolution/download on first start exceeding the MCP startup timeout).
MANUAL ACTION REQUIRED: review and ratify the diff; commit; restart Claude Code from a terminal with SCHOLARCHAIN_AWS_READONLY_PROFILE and SCHOLARCHAIN_AWS_REGION exported; run /mcp (probe 9). Decide on the item-2 tool-name mismatch (open item 1).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the diff, then human ratification and restart
```

## Matrix basis
- docs/agents.md "Artifact ownership", factory-engineer row: `.mcp.json`, `docs/agents.md`, `.claude/agents/**`. Human decision 3 items 1, 3, 4 (prompt) authorize each edit.

## Changes
- `.mcp.json`:22-23: aws-api env defaults `${SCHOLARCHAIN_AWS_READONLY_PROFILE:-scholarchain-readonly}`, `${SCHOLARCHAIN_AWS_REGION:-us-east-1}` (item 1). Package pin and `--python 3.12 --with mcp<2` unchanged.
- `docs/agents.md`:154 aws-api row gets both defaults; :157 Prerequisites paragraph adds the start-from-terminal + `/mcp` instruction and the Nov 17 diagnosis sentence (item 1).
- `docs/agents.md`:48 new `evidence/**` row in "Human-owned and generated artifacts" (item 4).
- `.claude/agents/infra-engineer.md`:33 step 4 uses `terraform -chdir=infra/terraform/environments/course validate` (item 3, F-01).
- Item 2: no edit. Item 5: no allow rule added; `.claude/settings.json` untouched.

## Item 4 guard confirmation (no guard change)
`.claude/hooks/guard-dangerous.sh` `owner_of` (lines 140-163) has no `evidence/*` case; it falls to line 161 `*) echo "unowned"`. Subagent Write/Edit to unowned is blocked at line 1296; shell writes at line 1010. Note: the main session (no agent) gets an ask, not a block, for any write (line 1280), same as every other path; unchanged.

## Item 2: terraform MCP comparison (diagnosis only)
| Tool name | Granted in infra-engineer.md `tools:` (line 4) | Offered in session (orchestrator list) |
|---|---|---|
| mcp__terraform__search_providers | yes | no (does not exist) |
| mcp__terraform__get_provider_details | yes | yes |
| mcp__terraform__search_modules | yes | yes |
| mcp__terraform__get_module_details | yes | yes |
| mcp__terraform__search_provider_docs | no | yes |
| mcp__terraform__get_provider_capabilities | no | yes |
| mcp__terraform__get_latest_provider_version | no | yes |
| mcp__terraform__get_latest_module_version | no | yes |
| mcp__terraform__search_policies | no | yes |
| mcp__terraform__get_policy_details | no | yes |

Finding: the one granted search name (`search_providers`) does not exist; the server's provider-docs search is `search_provider_docs`, which is not granted. This explains the Phase 12 gap (agents had no provider-docs search; `get_provider_details` needs a doc ID that search returns). Side note: aws-docs offers `read_sections`, not granted; aws-api names both match.

## Privilege delta
- infra-engineer: tools unchanged; procedure text only (validate form already allow-listed in Phase 12). All other agents: unchanged. MCP: aws-api gets default values for two non-secret env vars; no new server, tool, permission, or grant.

## Floor invariants
Dangerous-command guards, test-stop hooks, formatter, secret-read denial, terraform apply/destroy denial, destructive AWS denial, force-push denial: all preserved (no hook or settings file touched; `git status` shows only the three files). aws-api read-only flags `READ_OPERATIONS_ONLY=true`, `REQUIRE_MUTATION_CONSENT=true` unchanged.

## Audits
- Collision: no agent, command, hook, or permission added; none.
- Orphan: no agent/hook/command added or removed; validator roster and hook-reference checks pass.
- One-writer: `evidence/**` added as a generated artifact with no agent writer; guard maps it to unowned (blocks agents). No overlap introduced.

## Validation
- JSON: `jq empty .claude/settings.json .mcp.json` exit 0.
- Shell syntax, frontmatter, roster, ownership parity: `validate-agent-files.sh: 72 checks passed, 0 failed.` exit 0.
- Executable bits: format-scala.sh, guard-dangerous.sh, validate-agent-files.sh, verify-tests-ran.sh all -rwxr-xr-x.

## Diff
`git diff --stat`: .claude/agents/infra-engineer.md 2 +-, .mcp.json 4 ++--, docs/agents.md 5 +++--; 3 files, 6 insertions, 5 deletions. Full diff: `git diff` on the working tree (orchestrator saves it for code-reviewer).

## Open items
1. Human decision: correct infra-engineer's terraform grant (replace `mcp__terraform__search_providers` with `mcp__terraform__search_provider_docs`, possibly add `get_provider_capabilities`) in a later ratified amendment. Not done: item 2 is diagnose-only, and adding a tool needs a ratified matrix entry. (Tool-fence table in docs/agents.md says "terraform (registry docs)", so the change would be a correction within that grant, but it still needs ratification.)
2. `${VAR:-default}` support unverified; probe 9 (`/mcp`) confirms it.

## VALIDATION EVIDENCE
- `.claude/hooks/validate-agent-files.sh`: "validate-agent-files.sh: 72 checks passed, 0 failed." exit 0
- `jq empty .claude/settings.json .mcp.json`: no output, exit 0
- files changed after these runs: no
