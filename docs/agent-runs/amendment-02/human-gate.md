# Amendment 02: human gate
Accepted, with the risks listed below.

The last review (review-report-05) says APPROVE, but it only checked the three repair-04 fixes, as I asked. The three reviews before it said REQUEST_CHANGES. I accepted their open items as risks instead of repairing them. I stopped after four repair rounds because each round found new ways to spell a blocked command differently.

## What changed

These take effect after this gate and a restart.

- **CLAUDE.md:**
  - New section "Operating rules for agent runs".
  - New definition of done. An agent runs the tests of the projects it changed. One full `sbt check` runs at the end of each wave, before each review and before a phase ends.
- **Stop hook:**
  - The main session still needs a passing full `sbt check` before it can stop.
  - A subagent may also stop when fresh, passing sbt test reports cover every project it changed.
  - code-reviewer, which cannot change files, is exempt.
  - `.scalafmt.conf`, `.jvmopts` and `.sbtopts` now count as source.
- **Guard:** now blocks:
  - editing files through the shell (`sed -i`, `perl -i`, vi);
  - shell writes outside the agent's own folders;
  - commands that never end (`tail -f`, `watch`, and similar);
  - memory and local-settings writes;
  - hand writes to test reports and `releases/**`;
  - several ways to write files with sort, uniq, sed, touch, xargs and git.
- **Settings:**
  - New allow rules: `sbt -batch`, `grep`, `head`, `tail -n`, `ls`, `wc`, `shasum`, `cut`.
  - Not allow-listed (they prompt): `sort`, `uniq`, `sed -n`, `bash -n`, `python3 -m json.tool`.
- **New owners:**
  - `releases/**`: deploy-engineer (only scripts write there).
  - `config/**`: infra-engineer.
  - `.claude/hook-tests/**`: factory-engineer.
- **Role files:** 10 files say to run targeted tests and when to run the full check. Five wording fixes from amendment-01.
- **New self-test scripts** for the guard and the stop hook.
- **.mcp.json:** every MCP server is pinned to an exact version.

## Evidence

| File | What it is |
|---|---|
| prompt.md | My work order |
| agent-report-factory.md | factory-engineer's first pass |
| review-report.md | First review: 3 major findings |
| repair-01.md, review-report-02.md | First repair and re-check |
| repair-02.md, review-report-03.md | Second repair and re-check |
| repair-03.md, review-report-04.md | Third repair and re-check |
| repair-04.md, review-report-05.md | Final repair and re-check (APPROVE) |
| amendment-02*.diff | The diffs after each round |

## My probes (after restart)

| # | What I tried | Result |
|---|---|---|
| 1 | Run both self-test scripts | Pass: 369/369 and 69/69 |
| 2 | Run the validator | Pass: 72/72 |
| 3 | `tail -f README.md` | Pass. The session refused it itself, so the guard was not reached here; a self-test covers the guard. |
| 4 | rag-engineer writes into `modules/core` | Pass: blocked, no file made |
| 5 | rag-engineer runs `sed -i` | Pass: blocked |
| 6 | `grep` on an evidence file | Pass: ran with no prompt |
| 7a | domain-engineer changes core and runs no tests | **Fail:** it was allowed to stop (tried twice) |
| 7b | code-reviewer runs during 7a | Finished normally; proves nothing while 7a fails |
| 8 | `echo 'terraform destroy'` | Pass: blocked |
| 9 | `/mcp` | **Partly failed:** terraform works; aws-api and aws-docs do not start |

For 7a, I ran the hook by hand with a fake stop event. It blocked correctly in 0.15 seconds. So the hook itself works, but in a real run it does not stop the agent. I reverted every probe edit with `git checkout`.

## Known gaps

1. **The subagent stop check does not work in real runs.** The likely cause: the orchestrator runs every agent in the background, and Claude Code does not enforce the check for background agents. I could not confirm this. It probably affected Phases 2–10 too, but nobody noticed, because every agent ran `sbt check` anyway. These still protect the work:
   - the main session cannot finish a phase without a passing full `sbt check`;
   - the full check at the end of each wave;
   - code-reviewer;
   - from Phase 11, the orchestrator checks for test output in every COMPLETE report.

   This differs from the spec's description of the hook, and I record it here.
2. **The aws-api and aws-docs MCP servers do not start.** The pinned packages pull in a newer library (mcp 2.x) and Python 3.15. Started by hand with `--python 3.12 --with 'mcp<2'`, both work. factory-engineer fixes `.mcp.json` as the first step of Phase 12. Phase 11 does not use MCP.

## Accepted risks

Most of these still ask me for approval, so they cannot run unnoticed.

- A shell variable can hide an option from the guard, for example `o=-o; sort "$o" …`. (It prompts.)
- The recursive-delete check can be dodged with an extra `rm` word. (It prompts.)
- `arch` and `script` can run other commands without being checked. (It prompts.)
- File times can still be changed through scripting languages or `SetFile`. (It prompts.)
- git settings passed through environment variables. (It prompts.)
- Brace expansion outside git, for example `tail -n 5 -{f,f} x`. This one runs with no prompt, but it can only stall a run, not write a file.
- `sbt -batch` can run any sbt task with no prompt.
- Some harmless commands are now wrongly blocked, for example `sort -t$'\t'`, `xargs bash -n`, `HEAD@{1}..HEAD@{0}`, and git commit messages with `--output` or braces. I commit by hand, so this does not affect me.
- Minor review items #10, #11, #13, #14 and #15. `build-engineer.md` doesn't mention `.jvmopts` or `.sbtopts`.
- My own `settings.local.json` has allowed `sort`, `uniq` and `sed -n` since Phase 6. Their file-writing forms ran with no prompt until the guard learned them in repair-02. I keep these rules, because the guard now blocks those forms.
- `bash -n` now asks for approval. dataset-engineer and infra-engineer are told to use it. I fix that wording in Phase 12.

## Things I did by hand

- **AWS:** created the IAM user `scholarchain-readonly` with the `ViewOnlyAccess` policy and one access key, outside Terraform. It costs nothing. I delete it at cleanup.
- **Local setup:**
  - installed `uv`;
  - added the profile and region variables to `~/.zshrc`;
  - turned off the GitHub MCP server;
  - approved aws-api, aws-docs and terraform one at a time;
  - got the image digests with `docker inspect`.
- **.DS_Store:** stopped tracking the four files, in a separate commit.

## Agent slips

The agents reported these themselves. None of them wrote anything.
- factory-engineer used `awk` once (step 1).
- It ran one `bash -c` test and used `od` (repair-03).

None of these were on the allowed command list.

## Cost and time

- **Cost:** no AWS, Anthropic or Voyage charges.
- **Time:** the run took about 11:30 to 16:00, plus probes.
- **Lesson:** guard work never ends on its own. Next time: one repair round, then record the rest.

## Follow-ups

- **Phase 11 prompt:**
  - the orchestrator checks for test output in every COMPLETE report;
  - it names the wave closer;
  - code-reviewer may run beside one source-changing agent.
- **Phase 12 prompt, first step:**
  - fix the MCP startup and redo probe 9;
  - fix the wording in `docs/agents.md:143` and in the dataset-engineer and infra-engineer files.
- **Later:**
  - old build comments (amendment-01 #7, build-engineer);
  - factory-engineer's "proposed later" list.

## Commits

- **Work commit** (agent files): hash in `commit.txt`.
- **`.DS_Store` commit.**
- **Human commit:** this gate and the evidence.

## Acceptance 
I accept Amendement 02 as of commit 03760778f510e5617ba834c0bfc89eb9efb0a4fc.
