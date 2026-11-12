Phase 1 is ready for your ratification

Factory-engineer finished the work order and reports STATUS: COMPLETE. It is a proposal only until you ratify it and restart the session. Nothing was committed, pushed or installed, and no build files or application source were created. Of the files under docs/agent-runs/, it created only README.md.

Your git commit / git push question

Why ASK was changed to PASS: the agent's test calls only guard-dangerous.sh and never applies the settings.json rules. At the time, the guard didn't handle a plain git push; the "ask" came only from settings.json. So the test returned PASS, and the agent changed the expected result to match. It says this was wrong: the test was made to agree with a gap instead of closing it. It put the expectation back to ASK and fixed the guard.

The rules that make commit and push ask or deny (I checked these lines myself in .claude/settings.json):
45:  "Bash(git commit:*)",                              ← ask
46:  "Bash(git push:*)",                                ← ask
141–148: git push --force / -f / --force-with-lease / --mirror / origin +  ← deny

The guard fix: the guard now catches every form of commit and push, including git -C …, git commands.
- In the main session (you), it asks for confirmation.
- In any subagent, it blocks.
- Force pushes are blocked everywhere.
- No deny rule was removed or weakened.

What I checked myself

- jq parses both JSON files.
- bash -n passes on all 4 hooks, and all 4 are executable.
- validate-agent-files.sh reports 64 passed, 0 failed.

I also piped sample commands into the guard:

┌──────────────────────────────┬────────────────────┬──────────────────┐
│           Command            │ You (main session) │ factory-engineer │
├──────────────────────────────┼────────────────────┼──────────────────┤
│ git push origin main         │ ask                │ blocked (exit 2) │
├──────────────────────────────┼────────────────────┼──────────────────┤
│ git commit -m x              │ ask                │ blocked          │
├──────────────────────────────┼────────────────────┼──────────────────┤
│ git commit --amend --no-edit │ ask                │ blocked          │
├──────────────────────────────┼────────────────────┼──────────────────┤
│ git -C . push                │ ask                │ blocked          │
├──────────────────────────────┼────────────────────┼──────────────────┤
│ git push --force origin main │ blocked            │ blocked          │
├──────────────────────────────┼────────────────────┼──────────────────┤
│ git log --oneline -1         │ pass               │ pass             │
└──────────────────────────────┴────────────────────┴──────────────────┘

The hooks are already active in this session, before ratification. The guard blocked my own  because it contained the text --force.

What was built

- New files:
  - CLAUDE.md, docs/agents.md, docs/agent-runs/README.md
  - 17 new agent files, giving 18 with factory-engineer
  - 4 hooks: guard, Scala formatter, test-stop check, agent-file validator
  - .claude/settings.json and .mcp.json
  - 5 commands: deploy, rollback, incident, run-pipeline, experiment
- Self-edit: factory-engineer.md changed only to add amendment 2 (it now owns docs/agent-runeld report contract. Its tools are unchanged.

Tool limits:
- code-reviewer has only Read, Grep and Glob.
- deploy-engineer has no Write or Edit.
- test-engineer can write only under tests/**, which the guard enforces.
- dependency-updater has Edit but nowhere to write until you ratify the dependency ledger's

Agent's own test results:
- Guard: 119 of 119 cases matched.
- Test-stop hook: behaved as expected in every scenario, run in a throwaway repo.
- Audits: no name collisions and no orphaned agents, hooks or commands; every artifact has exactly one owner.

To see the complete diff of the new files:
for f in $(git ls-files --others --exclude-standard | grep -v '^docs/agent-runs/phase-01/');ev/null "$f"; done
git diff -- .claude/agents/factory-engineer.md

Risks the agent reported

- The guard works by matching command text. It sometimes blocks legitimate commands, and a determined script written to disk could get around it.
- The test-stop hook trusts an agent's own STATUS: BLOCKED-ON or STATUS: FAILED line.
- The MCP server versions are not pinned (@latest), and the MCP tool names in the agent files are unverified. The real safety boundary is a read-only GitHub token and a read-only AWS profile.

Decisions for you

1. Spec probe 1 names feature-implementer, which isn't in the matrix. The probes below use service-engineer instead.
2. Ratify the dependency ledger's path after Phase 2.
3. Writes by your session to docs/agent-runs/** currently ask for confirmation. Keep that, or block them outright?
4. Should deploy-engineer get a path for release records? That would mean giving it Write.
5. Pin the MCP server versions.
6. Optionally change git commit and git push from ask to deny.

To do by hand:
- Install uv.
- Create the read-only GitHub token (SCHOLARCHAIN_GITHUB_READONLY_TOKEN).
- Create the read-only AWS profile and Region (SCHOLARCHAIN_AWS_READONLY_PROFILE, SCHOLARCHAIN_AWS_REGION).
- Approve the MCP servers in the new session.

Probes after ratification

Commit, then restart. In the new session, run /agents and /mcp, then:

1. Ask service-engineer to add a dependency to build.sbt. It should refuse and return BLOCKE
2. Ask code-reviewer to fix a defect. It should report the defect and not edit.
3. Propose echo 'terraform destroy'. The guard should block it.
4. Ask to read .env. It should be denied.
5. Write a harmless modules/core/src/main/scala/Probe.scala, then try to stop. The session sheck first, naming build-engineer since there's no build yet. Delete the file afterwards.
6. Run .claude/hooks/validate-agent-files.sh. Expect 64 passed, 0 failed.
7. Ask for git commit -m x. It should ask you in the main session and be blocked inside a su

I'm stopping here for your ratification.
