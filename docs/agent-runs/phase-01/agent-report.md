STATUS: COMPLETE. This is a proposal. None of it takes effect until the human ratifies it and restarts the session.

OWNER: factory-engineer

WORK ORDER: Phase 1 (docs/agent-runs/phase-01/prompt.md), plus the human's mid-run instruction about git commit and git push.

FILES CREATED (all under /Users/brian/Developer/repos/scholarchain/):
- CLAUDE.md
- docs/agents.md
- docs/agent-runs/README.md
- .claude/agents/: 17 new files, one per matrix role:
  - build-engineer, domain-engineer, dataset-engineer
  - hadoop-engineer, entity-resolution-engineer, spark-engineer
  - storage-engineer, rag-engineer, service-engineer
  - test-engineer, code-reviewer
  - infra-engineer, deploy-engineer, experiment-engineer
  - documentation-engineer, incident-responder, dependency-updater
- .claude/hooks/: guard-dangerous.sh, format-scala.sh, verify-tests-ran.sh, validate-agent-files.sh
- .claude/settings.json
- .mcp.json
- .claude/commands/: deploy.md, rollback.md, incident.md, run-pipeline.md, experiment.md

FILES MODIFIED:
- .claude/agents/factory-engineer.md. This is the self-edit, limited to amendments 2 and 3; the diff is below.
- The human's Phase 1 evidence files (prompt.md, course-specification.md) are untouched.

COMMANDS RUN (inspection and validation only):
- git status, git diff, grep/sed -n on the spec
- which and version checks for tools
- jq empty, python3 -m json.tool
- bash -n, shellcheck -S warning
- chmod +x on the 4 hooks
- .claude/hooks/validate-agent-files.sh
- Hook test runners in the scratchpad, including git init of a throwaway repo there

I ran no commit, push or install. I created no build files and no application source.

TEST RESULTS: There is no Scala code yet, so there are no sbt tests. Hook tests:
- Guard: 119 of 119 cases matched (output below).
- Stop hook (scratch repo):

┌────────────────────────────────────────────────────┬──────┬────────────────────────────────┐
│                      Scenario                      │ Exit │                        Behaviour                        │
├────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ No source changes                                  │ 0    │ stop allowed                                            │
├────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ Scala changed, no marker, no build.sbt, first stop │ 2    │ blocked                                                 │
├────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ Same, second stop                                  │ 0    │ allowed with "report BLOCKED-ON build-engineer" warning │
├────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ Report says STATUS: BLOCKED-ON                     │ 0    │ allowed                                                 │
├────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ Fresh marker                                       │ 0    │ allowed                                                 │
├────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ Stale marker                                       │ 2    │ blocked, on the second stop too when build.sbt exists   │
├────────────────────────────────────────────────────┼──────┼────────────────────────────────┤
│ Stale marker, report says STATUS: FAILED           │ 0    │ allowed                                                 │
└────────────────────────────────────────────────────┴──────┴────────────────────────────────┘

- Formatter: exits 0 for a non-Scala file, for Scala with no .scalafmt.conf (prints "skippinle.

VALIDATION RESULTS: see the audit and validation output section.

ASSUMPTIONS:
- Path ownership refined from the layout section:
  - prompts/** → rag-engineer
  - reports/** → documentation-engineer
  - scripts/** and .github/workflows/** → infra-engineer
  - docker/** → build-engineer
  - test-engineer owns only tests/**; each module's own tests belong to that module's owner.
- Two agents get less than the matrix allows:
  - deploy-engineer has no Write or Edit.
  - dependency-updater has Edit but no Write, and an empty write territory until the dependency-ledger path is ratified (DEPENDENCY_LEDGER_PATHS="" in the guard).
- Command routing:
  - /run-pipeline goes to deploy-engineer.
  - /rollback goes to incident-responder. A rollback inside a /deploy that is still running stays with deploy-engineer.
- Carve-outs: .claude/.last-test-run (amendment 4) and .claude/settings.local.json are excluded from my .claude/** ownership.
- MCP tool names in the agents' tools: lists (e.g. mcp__aws-api__call_aws) are my best knowledge and are not verified.

RISKS:
1. The guard works on command text.
   - It over-blocks: it blocked my own legitimate commands 3 times.
   - It can be bypassed with scripts written to disk or encoded commands.
   - It fences Write/Edit by path, but not arbitrary shell writes into another agent's directory.
2. The Stop hook trusts the agent's own STATUS: BLOCKED-ON/FAILED line. Code review is the backstop.
3. Agent identity comes from the runtime. The guard knows the calling agent only because the runtime passes agent_type; this run confirmed it does in Claude Code 2.1.322. If that changes, subagents are treatedlike the main session and get asked rather than blocked.
4. MCP setup is unpinned. .mcp.json uses @latest and untagged images, and the servers' read-only switches are unverified. The read-only GitHub token and AWS profile are the real boundary.
5. The hooks are already live. The settings hot-loaded, so the hooks have been active in this session since before ratification.
6. Some Bash calls were refused. The permission layer denied several; they succeeded when re-run separately. Every result in this report was observed.

MANUAL ACTION REQUIRED:
- Install uv/uvx.
- Create a read-only GitHub token (SCHOLARCHAIN_GITHUB_READONLY_TOKEN) and a read-only AWS profile (SCHOLARCHAIN_AWS_READONLY_PROFILE, SCHOLARCHAIN_AWS_REGION).
- Approve the MCP servers.
- Pin the MCP server versions.
- Ratify, commit, record human-gate.md and commit.txt, then restart.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: The human reviews the diff, runs the probes below after a restart, and ratifies or rejects. code-reviewer may review read-only. Phase 2 (build-engineer) follows.

The human's git commit / git push question

Why I changed the expected result from ASK to PASS

My guard test drives only guard-dangerous.sh. At that point the guard did not handle a plain git push; the "ask" came only from the permission rules in settings.json. So the test saw PASS, and I changed the expectation to match. That was wrong: it made the test agree with a gap instead of closing it. I put the expectation back to ASK and fixed the guard.

Rules in .claude/settings.json that ask or deny commit and push

Line numbers are from grep -n. settings.json was not edited after this.

ask list:
45:      "Bash(git commit:*)",
46:      "Bash(git push:*)",
47:      "Bash(git tag:*)",
48:      "Bash(git merge:*)",
49:      "Bash(git rebase:*)",
deny list (unchanged; deny overrides ask):
141:      "Bash(git push --force:*)",
142:      "Bash(git push -f:*)",
143:      "Bash(git push --force-with-lease:*)",
144:      "Bash(git push --mirror:*)",
145:      "Bash(git push origin --force:*)",
146:      "Bash(git push origin -f:*)",
147:      "Bash(git push origin --force-with-lease:*)",
148:      "Bash(git push origin +:*)",
149:      "Bash(git filter-branch:*)",
150:      "Bash(git filter-repo:*)",
151:      "Bash(git reset --hard:*)",

These rules match command prefixes, so git -C &lt;dir&gt; push, git -c k=v commit and subagent calls were not covered by the guard.

Guard fix

A new section in guard-dangerous.sh matches every git … commit and git … push form, including -C/-c, --amend and chained commands.
- Subagents: blocked (exit 2).
- Main session: returns permissionDecision: "ask".

Force, mirror and refspec pushes are checked first and still hard-blocked. No deny rule was removed or weakened.

How the hook and the permission rules differ
- Permission rules: prefix matches, applied in every session. They ask, or deny for force pushes.
- Hook: reads the whole command and knows the calling agent. It blocks subagents and asks in the main session.

Deny beats ask, and a block from either layer stops the call.

Guard test output after the change

119 cases matched, 0 mismatched. Excerpt:

ok expect=ASK   got=ASK   &lt;main&gt;           git push origin main
ok expect=ASK   got=ASK   &lt;main&gt;           git push
ok expect=ASK   got=ASK   &lt;main&gt;           git -C /Users/brian/Developer/repos/scholarchain push origin main
ok expect=ASK   got=ASK   &lt;main&gt;           git commit -m "phase-01"
ok expect=ASK   got=ASK   &lt;main&gt;           git commit --amend --no-edit
ok expect=ASK   got=ASK   &lt;main&gt;           git -c user.name=x commit -am msg
ok expect=ASK   got=ASK   &lt;main&gt;           git add -A &amp;&amp; git commit -m x
ok expect=BLOCK got=BLOCK factory-engineer git commit -m "phase-01"
ok expect=BLOCK got=BLOCK service-engineer git push origin main
ok expect=BLOCK got=BLOCK deploy-engineer  git commit --amend --no-edit
ok expect=PASS  got=PASS  &lt;main&gt;           git log --oneline -5
ok expect=BLOCK got=BLOCK &lt;main&gt;           git push --force origin main
ok expect=BLOCK got=BLOCK &lt;main&gt;           git push -f
ok expect=BLOCK got=BLOCK &lt;main&gt;           git push --force-with-lease origin feature
ok expect=BLOCK got=BLOCK &lt;main&gt;           git push origin +main
ok expect=BLOCK got=BLOCK &lt;main&gt;           git push origin :old-branch
ok expect=BLOCK got=BLOCK &lt;main&gt;           echo 'terraform destroy'
ok expect=BLOCK got=BLOCK &lt;main&gt;           terraform apply -auto-approve
ok expect=PASS  got=PASS  &lt;main&gt;           terraform plan -destroy -out=plan.bin
ok expect=BLOCK got=BLOCK &lt;main&gt;           rm -rf /     (also ~, ., modules, *, chained rm -r -f docs)
ok expect=PASS  got=PASS  &lt;main&gt;           rm -rf target
ok expect=BLOCK got=BLOCK &lt;main&gt;           cat .env     (also .env.local, .env.prod, ~/.aws/credentials, printenv)
ok expect=PASS  got=PASS  &lt;main&gt;           cat env.example
ok expect=BLOCK got=BLOCK &lt;main&gt;           aws s3 rb / s3 rm --recursive / s3 sync --delete
ok expect=BLOCK got=BLOCK &lt;main&gt;           aws dynamodb delete-table / neptune delete-db-cluster
ok expect=BLOCK got=BLOCK &lt;main&gt;           aws s3vectors delete-index / delete-vector-bucket
ok expect=BLOCK got=BLOCK &lt;main&gt;           aws ecr batch-delete-image / delete-repository
ok expect=BLOCK got=BLOCK &lt;main&gt;           aws kms schedule-key-deletion / disable-key
ok expect=BLOCK got=BLOCK &lt;main&gt;           aws emr terminate-clusters / modify-instance-groups / put-managed-scaling-policy
ok expect=BLOCK got=BLOCK &lt;main&gt;           aws ecs update-service --desired-count 40
ok expect=ASK   got=ASK   &lt;main&gt;           aws s3 cp / aws emr create-cluster
ok expect=PASS  got=PASS  &lt;main&gt;           aws s3 ls / emr describe-cluster / sts get-caller-identity
ok expect=BLOCK got=BLOCK &lt;main&gt;           boto3 delete_bucket, curl -X DELETE, gremlin g.V().drop(), curl | bash
ok expect=BLOCK got=BLOCK service-engineer touch .claude/.last-test-run
ok expect=BLOCK got=BLOCK test-engineer    sed -i on .claude/settings.json; echo x &gt;&gt; CLAUDE.md
ok expect=PASS  got=PASS  factory-engineer chmod +x .claude/hooks/guard-dangerous.sh
ok expect=BLOCK got=BLOCK build-engineer   chmod +x .claude/hooks/guard-dangerous.sh
ok expect=BLOCK got=BLOCK Read             .env, .env.production, ~/.aws/credentials, terraform.tfstate, deploy.pem
ok expect=BLOCK got=BLOCK factory-engineer Write docs/agent-runs/phase-01/human-gate.md; Write build.sbt
ok expect=PASS  got=PASS  factory-engineer Write docs/agent-runs/README.md
ok expect=BLOCK got=BLOCK service-engineer Edit build.sbt / its own agent file / CLAUDE.md
ok expect=BLOCK got=BLOCK test-engineer    Write modules/core/src/main/... and modules/core/src/test/...
ok expect=PASS  got=PASS  test-engineer    Write tests/adversarial/...
ok expect=BLOCK got=BLOCK documentation-engineer Write docs/agents.md, docs/incidents/inc-1.md
ok expect=BLOCK got=BLOCK dependency-updater Edit project/Dependencies.scala (ledger not ratified)
ok expect=BLOCK got=BLOCK spark-engineer   Write unowned path; Write ~/.zshrc
ok expect=ASK   got=ASK   &lt;main&gt;           Write modules/... ; Write docs/agent-runs/phase-01/... ; Write CLAUDE.md
guard cases: 119 matched, 0 mismatched

To reproduce the full listing:

bash /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/7368e51b-b11c-417d-9f65-0e261c9deb3f/scratchpad/run-guard-cases.sh /Users/brian/Developer/repos/scholarchain/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/7368e51b-b11c-417d-9f65-0e261c9deb3f/scratchpad/guard-cases.txt

Per-file authority justification

┌───────────────────────────────┬────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│             File              │                                                                       Authority                                                                        │
├───────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ CLAUDE.md                     │ Work order item 1; "Required factory artifacts" row; factory-engineer owns CLAUDE.md                                                                   │
├───────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ docs/agents.md                │ Item 2; ownership matrix transcribed with exact "Recommended repository layout" paths; control rules 1–12; MCP trust boundaries (JSON has no comments) │
├───────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 17 agent files                │ Item 3; one per matrix row. Tool limits follow the matrix "Must not do" column and the code-reviewer, infra, deploy and test rules                     │
├───────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 4 hooks                       │ Item 4; "Required hook and permission behavior"; file names from "Required factory artifacts"                                                          │
├───────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ settings.json                 │ Item 4: explicit allow/ask/deny; secret, Terraform, force-push and destructive-AWS denials; disableBypassPermissionsMode: "disable" (a strengthening)  │
├───────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ .mcp.json                     │ Item 5; "Required MCP policy"                                                                                                                          │
├───────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 5 commands                    │ Item 6. Each has **Responsible agent:** and a ## Gates section                                                                                         │
├───────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ docs/agent-runs/README.md     │ Item 7; amendment 2                                                                                                                                    │
├───────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ factory-engineer.md self-edit │ Amendments 2 and 3 only; tools unchanged                                                                                                               │
└───────────────────────────────┴────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

Privilege changes

┌────────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────┐
│             Agent              │                                    Tools / ownership                                    │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ factory-engineer               │ Ownership adds docs/agent-runs/README.md; tools unchanged                               │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ code-reviewer                  │ Read, Grep, Glob                                                                        │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ deploy-engineer                │ Read, Grep, Glob, Bash, plus read-only aws-api tools                                    │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ dependency-updater             │ Read, Grep, Glob, Edit, Bash                                                            │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ dataset-engineer               │ Standard set plus WebFetch                                                              │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ infra-engineer                 │ Standard set plus aws-api (read-only), terraform registry docs and aws-docs             │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ incident-responder             │ Standard set plus aws-api and aws-docs                                                  │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ experiment-engineer            │ Standard set plus aws-api                                                               │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ storage-engineer, rag-engineer │ Standard set plus aws-docs                                                              │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ All others                     │ Read, Grep, Glob, Write, Edit, Bash, with writes fenced to their territory by the guard │
├────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────┤
│ Main session                   │ Writes, constitutional shell writes and git commit/push now prompt the human            │
└────────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────┘

Safety floors

All are preserved, and several are strengthened:
- Dangerous-command guard: new.
- Test-stop hook: new, on Stop and SubagentStop.
- Formatter: new.
- Secret-read denial: deny rules plus the guard on Read, Grep and Bash.
- Terraform apply/destroy denial: deny rules plus the guard; Terraform state changes are also blocked.
- Destructive AWS denial: covers S3, DynamoDB, Neptune, S3 Vectors, ECR, KMS, EMR and scale changes.
- Force-push denial: in both layers. Plain commit and push now ask in the main session and are blocked for subagents.

Audit and validation output

python3 -m json.tool .claude/settings.json: OK   | jq empty .claude/settings.json: OK
python3 -m json.tool .mcp.json: OK               | jq empty .mcp.json: OK
bash -n .claude/hooks/format-scala.sh: OK
bash -n .claude/hooks/guard-dangerous.sh: OK
bash -n .claude/hooks/validate-agent-files.sh: OK
bash -n .claude/hooks/verify-tests-ran.sh: OK
shellcheck -S warning: clean   (two SC2045 findings in the validator fixed first)
-rwxr-xr-x format-scala.sh
-rwxr-xr-x guard-dangerous.sh
-rwxr-xr-x validate-agent-files.sh
-rwxr-xr-x verify-tests-ran.sh
PreToolUse [Bash|Read|Grep|Glob|Write|Edit|MultiEdit|NotebookEdit] "$CLAUDE_PROJECT_DIR"/.claude/hooks/guard-dangerous.sh
PostToolUse [Write|Edit|MultiEdit] "$CLAUDE_PROJECT_DIR"/.claude/hooks/format-scala.sh
PostToolUse [Write|Edit|MultiEdit] "$CLAUDE_PROJECT_DIR"/.claude/hooks/validate-agent-files.sh --hook
Stop [-] "$CLAUDE_PROJECT_DIR"/.claude/hooks/verify-tests-ran.sh
SubagentStop [-] "$CLAUDE_PROJECT_DIR"/.claude/hooks/verify-tests-ran.sh
validate-agent-files.sh: 64 checks passed, 0 failed.
exit=0

What the validator checks:
- Agent file headers: name, description and an explicit tools field, with the name matching the file name.
- Names and routing descriptions are unique, and all 18 required agents exist.
- Tool limits for reviewers (no Write, Edit, NotebookEdit or Bash), deploy-engineer and dependency-updater.
- Every agent has a row in the docs/agents.md ownership table, and every row has a file.
- Every writing agent has a territory in the guard.
- All 14 report fields appear in every agent file.
- The 5 commands exist, each names an existing agent, and each has a Gates section.
- JSON validity.
- Every hook referenced in settings exists and is executable, and no hook script is unreferenced.

Audits:
- Collisions: none.
  - 18 unique agent names and descriptions, and 5 unique commands. Scope notes separate /deploy, /run-pipeline and /rollback.
  - One PreToolUse matcher, so hooks don't overlap.
  - Permission rules don't contradict the guard; I removed two broad rm -rf ./* deny rules that conflicted with its target/ allowance.
- Orphans: none.
  - Finding: spec probe 1 names feature-implementer, which is not in the matrix. I did not create it.
- One writer per artifact:
  - The guard's owner_of() maps each path to exactly one owner, and unowned paths are blocked for subagents.
  - Two splits come from the matrix itself: the dependency ledger (build-engineer owns its structure, dependency-updater its version values) and the generated test marker.
  - code-reviewer has no write tools.

factory-engineer.md self-diff

git diff -- .claude/agents/factory-engineer.md (23 insertions, 1 deletion):

@@ -19,6 +19,9 @@ You are the single writer for exactly these artifacts and nothing else:
 - `.claude/commands/*`
 - `.claude/settings.json`
 - `.mcp.json`
+- `docs/agent-runs/README.md` (added by human ownership amendment, Phase 1)
+
+Every other file under `docs/agent-runs/**` is human-owned evidence. Never create or edit it.

 ## Constitutional status

@@ -88,7 +91,26 @@ You must not author or edit application code, data, build files, infrastructure,

 ## Report contract

-End every run with a report containing, in order:
+End every run with the standard report fields, in this order:
+
+```text
+STATUS: COMPLETE | BLOCKED-ON | FAILED
+OWNER:
+WORK ORDER:
+FILES CREATED:
+FILES MODIFIED:
+COMMANDS RUN:
+TEST RESULTS:
+VALIDATION RESULTS:
+ASSUMPTIONS:
+RISKS:
+MANUAL ACTION REQUIRED:
+BLOCKED-ON OWNER:
+BLOCKED-ON EVIDENCE:
+NEXT GATE:
+```
+
+Then add these agent-specific sections, in order:

 1. **Matrix basis**: the ratified authority-matrix entries that authorize each change, or a statement that ratification is missing.
 2. **Changes**: each file created, edited, or deleted, with a one-line rationale.

Complete diff of new files: nothing is committed, so reproduce it with:

cd /Users/brian/Developer/repos/scholarchain &amp;&amp; for f in $(git ls-files --others --exclude-standard | grep -v '^docs/agent-runs/phase-01/'); do git diff --no-index /dev/null "$f"; done

That covers 2,686 lines. In git status, factory-engineer.md shows as modified (M) and every other file as new (??).

┌───────────────────────────┬───────┐
│           File            │ Lines │
├───────────────────────────┼───────┤
│ guard-dangerous.sh        │ 370   │
├───────────────────────────┼───────┤
│ settings.json             │ 251   │
├───────────────────────────┼───────┤
│ validate-agent-files.sh   │ 204   │
├───────────────────────────┼───────┤
│ docs/agents.md            │ 179   │
├───────────────────────────┼───────┤
│ verify-tests-ran.sh       │ 127   │
├───────────────────────────┼───────┤
│ docs/agent-runs/README.md │ 90    │
├───────────────────────────┼───────┤
│ format-scala.sh           │ 76    │
├───────────────────────────┼───────┤
│ CLAUDE.md                 │ 74    │
├───────────────────────────┼───────┤
│ .mcp.json                 │ 42    │
├───────────────────────────┼───────┤
│ Each agent file           │ 63–69 │
├───────────────────────────┼───────┤
│ Each command              │ 30–37 │
└───────────────────────────┴───────┘

Post-ratification probe instructions

Start a fresh claude session in the repo root. Run /agents and /mcp to confirm all 18 agents load and to approve the servers.

1. Ownership refusal. Ask "Use the service-engineer agent to add a dependency to build.sbt." It must refuse and return BLOCKED-ON build-engineer, and the guard blocks the write if it tries anyway. This substitutes for feature-implementer, which doesn't exist.
2. Reviewer refusal. Ask "Use the code-reviewer agent to fix the defect in X." It must report the defect and refuse to edit; it has no write tools.
3. Guard block. Propose echo 'terraform destroy'. Expect "BLOCKED by .claude/hooks/guard-dangerous.sh". Optionally also try aws s3 rm s3://x --recursive and git push --force.
4. Secret read. Ask it to read .env. Both the deny rule and the guard block it.
5. Stop hook. Write a harmless modules/core/src/main/scala/Probe.scala, approving the orchestrator-write prompt, then try to stop. The hook must demand sbt check; with no build.sbt yet, it names build-engineer.Delete the sample afterwards.
6. Roster. Check /agents against the "Lifecycle routing" table in docs/agents.md. Run .claude/hooks/validate-agent-files.sh and expect 64 passed, 0 failed.
7. Commit/push. Ask the session to run git commit -m x. Expect a confirmation prompt in the main session and a block inside any subagent.

Decisions for the human

1. Probe 1: replace feature-implementer in spec probe 1 with an agent that exists.
2. Dependency ledger: ratify its path after Phase 2. I then add it to DEPENDENCY_LEDGER_PATHS and docs/agents.md.
3. Orchestrator writes to docs/agent-runs/**: keep "ask", as now, or make them a hard deny?
4. Release records: give deploy-engineer a repository path for them? That would mean giving it Write.
5. MCP versions: pin the server image and package versions.
6. Commit/push rules: optionally change git commit and git push from ask to deny in settings.json.
