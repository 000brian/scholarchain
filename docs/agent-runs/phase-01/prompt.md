# Phase 1 work order

## Human ratification preamble

1. I, the human architect, ratify the following sections of docs/agent-runs/phase-01/course-specification.md as the authority matrix for this phase: 
- "Agent authority and ownership matrix" 
- the control rules in "The reference agentic control model"
- "Required factory artifacts"
- "Required hook and permission behavior"
- "Required MCP policy"
- "Recommended repository layout" (for exact paths).
2. Ownership amendment. docs/agent-runs/README.md is added to factory-engineer's owned artifacts. Every other file under docs/agent-runs/** is human-owned evidence that no agent may create or edit.
3. Standard report contract. Every agent, including factory-engineer, must end its work with these fields: STATUS (COMPLETE | BLOCKED-ON | FAILED), OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Agent-specific sections may follow. factory-engineer may edit its own file only to adopt this contract and amendment 2; show that self-diff separately.
4. Generated marker. .claude/.last-test-run is written by the sbt markTestRun task (build-engineer). It is not a constitutional artifact; hooks may read it.
5. Do not run git commit, git push, or any package install. Do not create build files or application source.

## Work order (verbatim from course specification, Phase 1)

Use the factory-engineer agent to create the complete ScholarChain agent system from the authority matrix in the course project specification.

Create:

1. CLAUDE.md
- Identify ScholarChain as a Scala 3, Hadoop, Spark, AWS, Graph-RAG project.
- Define module dependency boundaries.
- Define sbt check as the repository definition of done.
- State the one-owner-per-artifact rule.
- Require feature tests for every behavior change.
- Require provenance for every data transformation and runtime claim.
- Require secrets only through Secrets Manager or approved local placeholders.
- Require all AWS infrastructure through Terraform.
- Forbid direct cloud-console mutation as an implementation path.
- Import docs/agents.md.

2. docs/agents.md
- Transcribe the complete artifact ownership table for all required agents.
- Add a lifecycle-to-agent routing table.
- Add escalation rules for constitutional changes, data destruction, security changes, Terraform apply, deployment, expensive scale changes, and ambiguous ownership.
- State that subagents do not invoke one another and that all routing passes through the orchestrator.

3. All remaining .claude/agents/*.md files
- Use precise routing descriptions in the vocabulary of this project.
- Use the narrowest practical tools field.
- Give code-reviewer no Edit or Write tool.
- Give infra-engineer authoring and read-only cloud inspection tools but no apply or live deployment authority.
- Give deploy-engineer execution authority only through approved scripts and approved environments.
- Give test-engineer write access only to test territories.
- Require each agent to decline and route out-of-scope work.
- Include a structured report contract.

4. .claude/settings.json and hooks
- PostToolUse formatter for Scala writes.
- PreToolUse dangerous-command guard.
- Stop hook requiring current tests after source changes.
- Agent-file validation hook or script.
- Explicit allow and deny lists.
- Deny reads of secrets and .env files.
- Deny terraform apply and destroy.
- Deny force push.
- Deny destructive S3, DynamoDB, Neptune, S3 Vectors, ECR, KMS, and EMR commands.

5. .mcp.json
- GitHub access.
- AWS API inspection with no unrestricted write authority.
- Terraform documentation or provider inspection.
- AWS documentation access where available.
- Document every server's trust boundary and environment variables.

6. .claude/commands
- deploy, rollback, incident, run-pipeline, and experiment commands.
- Every command must name the responsible agent and gates.

7. docs/agent-runs/README.md
- Define the phase evidence format.
- State that hidden model reasoning is not stored.

Do not create build files or application source.

Validate every JSON file with a parser, every shell script with bash -n, all hook executable bits, all agent frontmatter, unique agent names, ownership completeness, routing collisions, routing orphans, and reviewer tool fences.

Present the complete diff, per-file authority justification, audit results, validation output, and post-ratification probe instructions. Stop for human ratification.
