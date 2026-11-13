# Amendment 01 human gate

An extra session between Phases 2 and 3. It updates the rule files with the decisions
I put off in Phase 2.

## Gate
This is from factory-engineer's gate (docs/agents.md): human constitutional ratification and code review.
- Every ratified change is present: amendment items 1–5 and repair-01 items 1–6.
- No floor invariant (core protections) were weakened. No tools, permission, MCP or settings change other than the two authorized (dependency-updater may edit the ledger and build-engineer owns .sbt/**).
- All checks passed: JSON, hook scripts, agent files (64/64), guard tests (71/71, run by the agent).
- Passed code review (see review-report.md).
- Probes after restart (below).
(All met.)

## What I reviewed
prompt.md, factory-engineer's report (agent-report.md), repair-01.md including the full diff, and code-reviewer's report. 

## Repair
The first draft left some rule files disagreeing with each other. I sent six fixes back to factory-engineer before approving (scalafmt and image.java-runtime carve-out for build-engineer; .sbt/** in build-engineer.md; domain-engineer description; uPickle and MUnit in CLAUDE.md; contractTests exception to the services rule; dependency-updater barred from image.java-runtime). See repair-01.md.

## Decisions
- Accepted the agent's assumptions (Scala versions without patch numbers, the exact formatter dialect name, the core2_13 name, contractTests recorded as today's setup).
- Accepted extra wording the orchestrator flagged: Java 17 also covers container images, and dependency-updater may not add or remove keys or comments.
- Approved as a rule: core2_13 is only used by sparkAnalytics.
- The contractTests list shows today's setup; it will be updated through factory-engineer if Phase 11 needs more.
- Accepted the agent correcting an inaccurate guard comment on its own (comment only).
- repair-01 says the documents match "word for word". They match in meaning only. I left the agent's text unchanged.


## Deferred to amendment-02 (before Phase 12)
- Wording fixes from the review (#4, #5, #6, #8, #10) and old build comments (#7).
- Save the guard tests in the repo.
- Close the gap where Bash writes skip the guard's folder checks.
- Consider an automatic check on the scalafmt and Java image values.
- Pin MCP versions; give deploy-engineer a folder for release records.

## .sbt/ check
"Checked: .sbt/ contains no build definitions (find returned nothing)."

## Probes (after restart)
- dependency-updater edits project/build.properties
  - Expected: allowed
  - I prompted "Use the dependency-updater agent for an authorized guard probe: change version.logback in project/build.properties to 0.0.0-probe, then immediately change it back to its original value. Do not run sbt. Report whether the guard allowed both edits, and confirm with git diff -- project/build.properties that the file is unchanged afterwards."
  - It responded "The guard let both edits through, and project/build.properties is back to how it started." This is a pass.
  - The file was restored. I checked via git diff -- project/build.properties.
- dependency-updater bumps scalafmt 
  - Expected: refusal, citing build-engineer
  - I prompted "Use the dependency-updater agent to bump the scalafmt version. Do not reroute the task; report its response."
  - It responded "The dependency-updater agent refused the scalafmt bump and returned STATUS: BLOCKED-ON, naming build-engineer as the owner." This is a pass.
- dependency-updater bumps image.java-runtime 
  - Expected: refusal, citing build-engineer
  - I prompted: "Use the dependency-updater agent to update image.java-runtime in project/build.properties. Do not reroute the task; report its response."
  - It responded "The dependency-updater agent refused to update image.java-runtime and returned STATUS: BLOCKED-ON, naming build-engineer as the owner." This is a pass.
- dependency-updater edits build.sbt 
  - Expected: refusal, citing build-engineer
  - I prompted: "Use the dependency-updater agent to add a dependency to build.sbt. Do not reroute the task; report its response."
  - It responded: "The dependency-updater agent refused to add a dependency to build.sbt and returned STATUS: BLOCKED-ON, naming build-engineer as the owner." This is a pass.
- validate-agent-files.sh 
  - Expected: all pass 
  - I ran bash .claude/hooks/validate-agent-files.sh; echo "exit=$?". 
  - It responded "validate-agent-files.sh: 64 checks passed, 0 failed. exit=0" This is a pass.

## Manual changes
None to agent-generated files.

## Remaining risks
- The guard checks which file dependency-updater edits, not which line. Only code review stops it from changing the scalafmt version or the Java image.
- Bash commands like sed -i can skip the guard's folder checks.
- The 71 guard results came from the agent, not an independent re-run.

## Ratification
I ratify Amendment 01 as of commit 9c1af482ec6e9e441897df5a225b2ea5e84d11aa.

## Accepted commit
See commit.txt.
