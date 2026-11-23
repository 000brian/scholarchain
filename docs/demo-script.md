# ScholarChain demonstration script

This is the script for the final demonstration video (deliverable D19): the course specification's 22 demonstration items, in its order ("Demonstration requirements", `docs/agent-runs/phase-17/course-specification.md`). For each item it gives the clip (already recorded, or still to record), what to show, what to say, the exact command or screen, what to blur, and the replay line of every command.

**State.** The AWS environment was removed on Nov 21, 2026 under the instructor-approved teardown (Phase 15 gate, decision 6, [`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md)). The instructor's condition was that "your video and archived evidence clearly demonstrate the actual execution of the required functionality" (same gate, decision 6). So the live items (9 to 16 and 18 to 22) were recorded while the system ran, in Phase 15; the remaining items need no AWS and are recorded from the repository. Where a live item can also be shown today on local fixtures, the script adds that command, so the video does not rely only on prerecorded screens.

**Which clips exist.** The Phase 15 gate, decision 10, records: items 9 to 16 and 18 to 21 recorded live, item 22 recorded during the cleanup, item 7 (clip 19: the sbt gate passed in a fresh clone, 2,195 module and 399 contract tests; `check.sh` failed only on L-09), the bounded-run part of item 17 (clips 07 and 19), and a local unsupported-claim test for item 16 (clip 20). Items 1 to 6 and 8, and item 17's agent-replay part, are still to record. The clip numbers and what each clip showed come from the Phase 15 runbook log, [`docs/agent-runs/phase-15/runbook-log.md`](agent-runs/phase-15/runbook-log.md), rows "D16 clip NN" and "D25"/"D27"/"D28 ... clip 21". The log does not confirm whether clip 02 was recorded (row "D16 end"), so this script does not use it. The clips themselves are video files the human keeps outside Git; they are not in the repository.

**Replay lines.** Every command below carries exactly one replay line, as the Phase 17 work order requires ("How the run ends (results)"): `replayed earlier (phase NN, EVIDENCE)`, `not replayable after teardown (archived evidence: PATH)`, `not replayed in phase 17 (REASON)`, or, in this draft, `planned for replay (ENN)` for a command the human replays in Phase 17 steps E3 to E6; step E11 replaces each `planned for replay` line with the observed result. No command here was run by the documentation agent (Phase 17 planned deviation 1). A screen (a file, a console page, a recorded clip) is not a command and has no replay line. Commands run from the repository root unless a line says otherwise. Every command here is also in [`README.md`](../README.md), with the same replay line, except three demonstration-only lines (the `sed -i.bak` and `tail -f` probes of item 6 and the unsupported-claim suite of item 16); those three are marked `not replayed in phase 17 (REASON)`, because the human's README replay (step E5) does not include them.

**What to blur, in every clip.** The AWS account ID (the console's account menu, any ARN, any `sts` output), AWS profile names (the `AWS_PROFILE` value and the read-only profile's name, which `plan.sh` prints), the API address (`{api-url}`), queue URLs, bucket names, endpoint hosts, and any API key or token. The scripts mask the account in their own output (`********NNNN` or `<account>`), but console pages and raw command output do not. Nothing in this document is such a value; logical names (`{api-url}`, `{data-bucket}`) stand for them.

## Overview

| # | Demonstration item (specification) | Clip | Shown today on local files or fixtures as well |
|---|---|---|---|
| 1 | Private repository structure | to record | yes (repository) |
| 2 | Agent ownership map | to record | yes (`docs/agents.md`) |
| 3 | At least one agent role file | to record | yes (`.claude/agents/`) |
| 4 | A dangerous-command hook safely blocking a probe | to record | yes (three probes) |
| 5 | A test-stop hook or equivalent deterministic gate | to record | yes (self-test) |
| 6 | A phase report and repair record | to record | yes (`docs/agent-runs/`) |
| 7 | A clean `sbt check` result | recorded (clip 19, Phase 15) | yes (fresh clone, E3) |
| 8 | A dataset manifest and actual source samples | to record | yes (`datasets/`) |
| 9 | Hadoop job execution evidence and counters | recorded (clip 10) | yes (local pipeline) |
| 10 | Spark job execution evidence and graph outputs | recorded (clip 11) | yes (fixture suites) |
| 11 | Neptune graph query | recorded (clip 05) | no (Neptune removed) |
| 12 | DynamoDB run or query record | recorded (clip 08) | committed sample only |
| 13 | S3 Vectors query returning source pointers | recorded (clip 06) | emulator results only |
| 14 | A complete user query through the API | recorded (clip 01) | yes (fixture suite) |
| 15 | Evidence and explain endpoints for the answer | recorded (clip 03) | yes (fixture suite) |
| 16 | Claim verification, including one unsupported claim test | recorded (clips 04 and 20) | yes (contract suite) |
| 17 | Reproduction scripts, agent replay instructions, a clean-checkout bounded run | partly recorded (clips 07 and 19); agent-replay part to record | yes |
| 18 | Terraform plan or deployed-resource evidence | recorded (clips 14 and 18) | yes (validate; optional plan) |
| 19 | CloudWatch elasticity dashboard | recorded (clip 12) | committed plots only |
| 20 | Scale-out and scale-in evidence | recorded (clips 13 and 11) | committed plots, regenerated |
| 21 | One failure and recovery example | recorded (clip 09) | committed record, regenerated timeline |
| 22 | Cleanup or resource inventory evidence | recorded (clip 21, parts 1 to 5) | Phase 17 state count (E6) |

## 1. Private repository structure

- **Clip:** to record (new clip).
- **Show:** the GitHub repository page with its "Private" label and the top-level folders; then the same tree in the editor: `modules/` (the seven code projects), `tests/contracts/`, `infra/terraform/`, `scripts/`, `datasets/`, `experiments/`, `prompts/`, `docs/`, `reports/`, `releases/`, `.claude/`.
- **Say:** "This is the ScholarChain repository. It stays private until the submission deadline and is then made public, as the instructor asked. The code is an sbt multi-project build; everything the agents produced, and every phase record, is in this one history."
- **Screen:** the repository page and the editor's file tree. The layout and what each folder holds: [`README.md`](../README.md#architecture-summary) and the [deliverable index](../README.md#deliverable-index).
- **Blur:** nothing expected on the repository page; check that no browser tab or bookmark shows a console URL with the account.

## 2. Agent ownership map

- **Clip:** to record (new clip).
- **Show:** [`docs/agents.md`](agents.md), section "Artifact ownership" (one writer per path), then "Tool fences" and "Lifecycle routing".
- **Say:** "Every path has exactly one owning agent. The orchestrator routes each step to that owner, and agents never call each other. The guard hook mirrors this table and blocks a write outside the agent's territory."
- **Screen:** `docs/agents.md`, "Artifact ownership". The same map in short form: the [agent catalog](../README.md#agent-catalog) of the README.
- **Blur:** nothing.

## 3. At least one agent role file

- **Clip:** to record (new clip).
- **Show:** [`.claude/agents/spark-engineer.md`](../.claude/agents/spark-engineer.md): the frontmatter `tools` field (the tool fence), the owned path `modules/spark/**`, the refusal boundaries and the report contract. Then the agent list in a Claude Code session.
- **Say:** "This is one of the 18 role files. The tools field is the fence: this agent can edit only its own module, and it ends every run with the standard report contract. The reviewer, by contrast, has only read tools."
- **Command (in a `claude` session started from the repository root):** `/agents` (lists the 18 agents). Replay: planned for replay (E5) (an optional recorded session in the clone).
- **Blur:** nothing.

## 4. A dangerous-command hook safely blocking a probe

- **Clip:** to record (new clip). The course specification asks for at least three safe probes ("Required hook and permission behavior"); each one below is blocked by the PreToolUse guard before anything runs, and each has a matching case in the guard self-test.
- **Show:** in a `claude` session, ask the orchestrator to run each command; the guard's block message appears instead of a result. Then the guard self-test's PASS lines.
- **Say:** "The guard hook reads every command before it runs. It blocks Terraform removal, in-place file edits and watchers, and it tells the agent what to do instead. A hook is policy, not a suggestion: the command never runs."
- **Commands:**
  - In the `claude` session, ask: "Run this shell command: `echo 'terraform destroy'`". Expected: `PreToolUse:Bash hook error: BLOCKED by .claude/hooks/guard-dangerous.sh ...` (the Phase 1 probe, [`docs/agent-runs/phase-01/human-gate.md`](agent-runs/phase-01/human-gate.md), probe 3). Replay: planned for replay (E5) (same optional session).
  - In the same session, ask: "Run this shell command: `sed -i.bak s/a/a/ README.md`". Expected: blocked with "Use the Edit tool." (self-test case `c-sed-i-main`, `.claude/hook-tests/guard-selftest.sh:129`). Replay: not replayed in phase 17 (demonstration-only probe for the video session; not in the README replay list, and E5 replays only the README's one dangerous-command probe; the self-test case above covers the same rule).
  - In the same session, ask: "Run this shell command: `tail -f README.md`". Expected: blocked with "No watchers: read with tail -n or grep, and finish the step." (self-test case `f-tail-f-probe3`, `.claude/hook-tests/guard-selftest.sh:219`). Replay: not replayed in phase 17 (demonstration-only probe for the video session; not in the README replay list, and E5 replays only the README's one dangerous-command probe; the self-test case above covers the same rule).
  - In a terminal: `bash .claude/hook-tests/guard-selftest.sh` (one PASS or FAIL line per case, then a total). Replay: planned for replay (E5).
- **Blur:** nothing expected (the block messages hold no identifier).

## 5. A test-stop hook or equivalent deterministic gate

- **Clip:** to record (new clip).
- **Show:** the stop-hook self-test running; then the recorded Phase 1 probe, in which the Stop hook refused to let the session finish after a source change without a passing test run ([`docs/agent-runs/phase-01/human-gate.md`](agent-runs/phase-01/human-gate.md), probe 5); then the "Stop and SubagentStop gate" row of [`docs/agents.md`](agents.md#hooks-and-permission-floor).
- **Say:** "A source-changing agent cannot stop until fresh, passing test reports or a passing sbt check exist after its last change. The marker file is written only by the build's own task, never by hand."
- **Commands:**
  - `bash .claude/hook-tests/stop-hook-selftest.sh`. Replay: planned for replay (E5).
  - `.claude/hooks/validate-agent-files.sh` (validates the agent files, settings and hook wiring). Replay: planned for replay (E5).
- **Blur:** nothing.

## 6. A phase report and repair record

- **Clip:** to record (new clip).
- **Show:** one complete loop from the records of Phase 15's cleanup: the runbook-log row "D27 attempt 1" (the removal refused before its confirmation prompt, finding L-11), the repair record [`docs/agent-runs/phase-15/repair-D27-infra-engineer.md`](agent-runs/phase-15/repair-D27-infra-engineer.md), the reviewer's verdict [`docs/agent-runs/phase-15/review-report-L-11.md`](agent-runs/phase-15/review-report-L-11.md) (APPROVE), the row "D27 attempt 2", and the human gate [`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md). Optionally, a Phase 17 pair once the human has copied it: `docs/agent-runs/phase-17/repair-01-rag.md` and its re-check `review-report-02.md`, which found one finding still PARTIAL.
- **Say:** "Each phase leaves its prompt, the agents' reports, the review and repair records, the human gate and the commit list. Here a live refusal became a finding, the owning agent repaired it, an independent reviewer approved the repair, and only then did the removal run again."
- **Screen:** the files above; the folder map is in the [deliverable index](../README.md#deliverable-index), D03.
- **Blur:** nothing expected (the records are committed and were scanned for identifiers at each commit).

## 7. A clean `sbt check` result

- **Clip:** recorded: clip 19 (Phase 15, a fresh clone): `check.sh` passed 8 of 9 gates, its sbt gate passed with 2,195 module and 399 contract tests in 1,256 s, and the script-contracts gate failed only on L-09 (runbook-log row "D16 clip 19b check.sh in fresh clone"; Phase 15 gate, decision 10). L-09 was repaired in Phase 16, and the Phase 16 fresh-clone `check.sh` passed 9 of 9 gates with 2306 module and 399 contract tests ([`docs/evaluation.md`](evaluation.md#107-group-7-reproducibility)). Optional new clip: the Phase 17 fresh-clone run at E3.
- **Show:** clip 19; then the Phase 16 result; then, if recorded, the E3 run's final `[success]` line and its marker counts.
- **Say:** "This is the full gate from a clean checkout: formatting, compilation, every module test and the contract tests that need no AWS. The first fresh-clone run in Phase 15 exposed one packaging-list gap, which Phase 16 fixed; the final gate's run is the Phase 17 fresh clone."
- **Commands (inside the fresh clone):**
  - `sbt clean check`. Replay: planned for replay (E3).
  - `bash scripts/check.sh --record-dir "$REPO/evidence/checks"` (`REPO` is the main checkout's absolute path). Replay: planned for replay (E4).
- **Blur:** nothing expected; `check.sh` prints no account.

## 8. A dataset manifest and actual source samples

- **Clip:** to record (new clip).
- **Show:** [`datasets/dataset-manifest.yaml`](../datasets/dataset-manifest.yaml) (DBLP plus the OpenAlex, Crossref and ORCID entries, each with its snapshot and checksums), the committed samples under [`datasets/samples/`](../datasets/samples/) (`dblp/`, `openalex/`, `crossref/`, `orcid/`), [`datasets/LICENSES.md`](../datasets/LICENSES.md), and the validator's output.
- **Say:** "The manifest pins every source snapshot by checksum, with its license and the bounded subset rule. These are real source records, small enough to commit, and the local pipeline runs on them."
- **Command:** `bash datasets/scripts/validate.sh`. Replay: planned for replay (E5).
- **Blur:** nothing.

## 9. Hadoop job execution evidence and counters

- **Clip:** recorded: clip 10 (Phase 15): the Step Functions execution list (A-Hadoop attempt 2 succeeded in 1:21:14), the EMR cluster's one completed step on 1 primary and 2 core m5.xlarge nodes, and the counters from the committed files: J01 read 13,073,341 records = 155,978 accepted + 12,917,264 excluded + 99 rejected; J09 20,125,392 (runbook-log row "D16 clip 10"). The committed run folder: [`experiments/raw/p15-a-hadoop/20261120T225632Z-eb786c5275a3-5c75/`](../experiments/raw/p15-a-hadoop/20261120T225632Z-eb786c5275a3-5c75/) (`hadoop-job-counters.csv`, `emr-steps.csv`); results in [`experiments/results-summary.md`](../experiments/results-summary.md), section 2.6.
- **Show:** clip 10; then, live, the local bounded pipeline on the committed samples and its `local-run-report.json`.
- **Say:** "Hadoop MapReduce does the first distributed pass over the full DBLP release: parse, normalize, author mentions, blocking and coauthor pairs, with counters and a reason for every rejected record. The same jobs run locally on the committed samples."
- **Command:** `sbt "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline /tmp/p17-hadoop-STAMP"` (a new output folder). Replay: planned for replay (E5).
- **Blur (clip 10):** the account menu, the EMR cluster ARN and the state machine ARN in the console.

## 10. Spark job execution evidence and graph outputs

- **Clip:** recorded: clip 11 (Phase 15): A-Spark's step took 1,488 s and B's 1,416 s, both with the same outputs (200,484 researchers, 155,978 works, 1,612,421 full-window edges, 0 invalid), and the Spark History Server (runbook-log row "D16 clip 11"). Committed: [`experiments/raw/p15-a-spark/20261120T225850Z-eb786c5275a3-4f7e/spark-outputs.csv`](../experiments/raw/p15-a-spark/20261120T225850Z-eb786c5275a3-4f7e/spark-outputs.csv); [`experiments/results-summary.md`](../experiments/results-summary.md), sections 2.7 and 2.8.
- **Show:** clip 11; then, live, the known-graph and pipeline fixture suites passing.
- **Say:** "Spark reads the Hadoop outputs and computes the graph facts: coauthor counts, weighted degree, team sizes, components, communities, a bridge measure and k-core. The fixture suites check these on graphs small enough to work out by hand."
- **Command:** `sbt -batch "sparkAnalytics/testOnly org.scholarchain.spark.KnownGraphSuite org.scholarchain.spark.PipelineFixtureSuite"`. Replay: planned for replay (E5).
- **Blur (clip 11):** the account menu and cluster ARN; the History Server address if it shows a host.

## 11. Neptune graph query

- **Clip:** recorded: clip 05 (Phase 15): the API's neighbour route `GET /v1/graph/nodes/{nodeId}/neighbors` answered 200 with 10 `COAUTHORED_WITH` rows for the window `full-2015-2025`, sorted by weight (31, 27, 18, ...), each with its edge evidence key and a next-page cursor (runbook-log row "D16 clip 05"). The route and its bounds: [`docs/api.md`](api.md#58-get-v1graphnodesnodeidneighbors).
- **Show:** clip 05.
- **Say:** "The graph service answers from Neptune with fixed, parameterised openCypher templates, depth 1 and at most 50 rows, so user text never becomes database code. Each row carries the key of the Spark edge it comes from."
- **Screen:** clip 05 only. The exact command line of that request is not in the committed record. Neptune was removed with the environment, so there is no live replay.
- **Blur (clip 05):** the API address and the API key.

## 12. DynamoDB run or query record

- **Clip:** recorded: clip 08 (Phase 15, console, read-only): the run ledger items of clip 01's query run (4 items, 2 attempts, each a `claim` and a `query-audit` item) and of the ingest run (attempt 7 = clip 07's demo run: input 5000, skipped unchanged 5000, written 0) (runbook-log row "D16 clip 08").
- **Show:** clip 08; then the committed read-only copy of the same query run's items, [`docs/agent-runs/phase-15/dynamodb-sample/run-ledger-clip01-query.json`](agent-runs/phase-15/dynamodb-sample/run-ledger-clip01-query.json).
- **Say:** "DynamoDB holds the operational state: one ledger item per attempt, written with conditions so that a retry never overwrites a finished record. This copy was taken from the live table before the cleanup."
- **Screen:** clip 08 and the committed sample.
- **Blur (clip 08):** the account menu and the table ARN.

## 13. S3 Vectors query returning source pointers

- **Clip:** recorded: clip 06 (Phase 15): the topic question q26 answered with one metric item and five `semantic_context` items from the index `researcher-profile-v1`; each semantic item carries a logical source pointer (`scholarchain:rp:full-2015-2025:researcher:v1:...`) and no text snippet, by design (runbook-log row "D16 clip 06").
- **Show:** clip 06 (the answer's evidence items and the explain record's `indexesQueried`); then the AWS Emulator result, where an S3 Vectors write, read and similarity query passed locally ([`docs/emulator.md`](emulator.md#4-what-was-exercised)).
- **Say:** "Semantic context comes from S3 Vectors, queried with an embedding of the question, and every hit points back to the researcher profile it came from. Semantic context can describe, but it never supports a number, an edge or a path."
- **Screen:** clip 06; the emulator's committed results under [`experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/`](../experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/).
- **Blur (clip 06):** the API address and the API key.

## 14. A complete user query through the API

- **Clip:** recorded: clip 01 (Phase 15): `POST /v1/query` with "Which paper of Wanli Ouyang had the most coauthors on one paper between 2015 and 2025?" answered 200, not abstained: claim 1 verified (at most 19 coauthors, metric evidence, window 2015-2025), claim 2 removed as unsupported (it named a researcher the evidence does not hold), claim 3 verified; model `claude-haiku-5-5` (runbook-log row "D16 clip 01"). The body of the second send is committed: [`experiments/evaluation/raw/answers/p15-demo/p15-demo-q.json`](../experiments/evaluation/raw/answers/p15-demo/p15-demo-q.json).
- **Show:** clip 01; then, live, the services end-to-end fixture suite, which sends a query through the HTTP server to evidence and explain on local fixtures.
- **Say:** "One question goes through the whole workflow: plan, graph and vector retrieval, one evidence bundle, synthesis by the model, then deterministic verification. The claim the evidence did not support was removed and shown as removed."
- **Commands:**
  - `bash scripts/smoke-test.sh --question 'Which paper of Wanli Ouyang had the most coauthors on one paper between 2015 and 2025?'` (the scripted form of the same question). Replay: not replayable after teardown (archived evidence: `docs/agent-runs/phase-14/runbook-log.md`, row D14; `docs/agent-runs/phase-15/runbook-log.md`, row D5).
  - `sbt -batch "services/testOnly org.scholarchain.services.QueryApiSuite"` (its test "full query-to-evidence lifecycle with fakes", `modules/services/src/test/scala/org/scholarchain/services/QueryApiSuite.scala:87`). Replay: planned for replay (E5).
- **Blur (clip 01):** the API address and the API key; the log notes that one content hash shows a masked digit run, which is not an identifier.

## 15. Evidence and explain endpoints for the answer

- **Clip:** recorded: clip 03 (Phase 15): `GET /v1/explain/{queryId}` for clip 01's query: 2 attempts, the latest with 2 verified claims, 3 evidence IDs (match, metric, work), plan source `llm`, the stage timings and token counts, and the query templates used; the evidence items were read through the links in the answer (runbook-log rows "D16 clip 03" and "D16 clip 01"). Routes: [`docs/api.md`](api.md#55-get-v1evidenceevidenceid) and [`docs/api.md`](api.md#56-get-v1explainqueryid).
- **Show:** clip 03; the live fixture suite of item 14 also follows the evidence and explain links.
- **Say:** "Every claim names its evidence IDs, and each one can be fetched. The explain record shows how the answer was made: the plan, the templates, the prompt versions and the verification counts, but never the prompt text or the model's reasoning."
- **Screen:** clip 03. The local command is the `QueryApiSuite` line of item 14 (its replay line is there).
- **Blur (clip 03):** the API address and the API key.

## 16. Claim verification behaviour, including one unsupported claim test

- **Clip:** recorded: clip 04 (Phase 15): real non-verified outcomes from experiment D's explain records (q10 unsupported, q16 conflicting, q01 partially supported) and the false-premise question "Which Nobel Prize did Wanli Ouyang win in 2019, and with which coauthors?", whose year claim was removed as conflicting and two claims kept as partially supported (runbook-log row "D16 clip 04"; body [`experiments/evaluation/raw/answers/p15-demo/p15-demo-n.json`](../experiments/evaluation/raw/answers/p15-demo/p15-demo-n.json)); and clip 20 (Phase 15, local): `UnsupportedClaimEndToEndSuite` passed 7 of 7 in 15 s, with its first test's source shown (runbook-log row "D16 clip 20").
- **Show:** clip 04, then clip 20, or the same suite live.
- **Say:** "The verifier is deterministic code, not a model. A number the evidence does not hold is never verified, and a claim that contradicts its evidence is removed. One honest limit: claims that two researchers are not connected are only partly covered; after Phase 17 such a claim with a negation or absence word must match a fixed accepted form, but wording with no listed word can still pass, and that is a recorded known limitation."
- **Command:** `sbt -batch "contractTests/testOnly org.scholarchain.contracts.runtime.UnsupportedClaimEndToEndSuite"`. Replay: not replayed in phase 17 (demonstration-only line, not in the README replay list; the Phase 15 clip 20 run of this suite, runbook-log row "D16 clip 20", is the earlier record, and E3's full `sbt clean check` runs every contract suite).
- **What to say about absence claims, if asked:** the rule as built and its known limitations are in [`docs/api.md`](api.md#9-claim-verification-statuses-and-reasons) and the [final report, section 12.2](../reports/final-report.md#122-claim-verification) (after session 3, step E1: P17-A-01 resolved against the human's allow-list rule, P16-L-10 closed for wording that contains a list word, absence wording with no list word a known limitation; the final review stays REQUEST_CHANGES for the control slice, P17-F-01, accepted as a known limitation).
- **Blur (clip 04):** the API address and the API key.

## 17. Reproduction scripts, agent replay instructions, and a clean-checkout bounded run

- **Clip:** partly recorded. Clip 07 (Phase 15, live): a bounded `rag-ingest` load stage of `run-bounded-pipeline.sh` with `--max-documents 5000`, run from the clean checkout, `RESULT ok`, all 5000 documents skipped as unchanged (runbook-log row "D16 clip 07 skip probe"; the full command line with its `--snapshot` value is not in the committed record). Clip 19 (Phase 15, a fresh clone): the local Hadoop pipeline exited 0 in 126 s (maximum team size 7, 33 pair instances, 33 consolidated pairs, 67 links) and the two Spark fixture suites passed 43 of 43 in 162 s (runbook-log rows "D16 clip 19c" and "D16 clip 19d–e"). **To record (new clip):** the agent-replay part, from the Phase 17 README.
- **Show:** [`scripts/README.md`](../scripts/README.md) ("The ten scripts", "Full reproduction, in order"); the README sections [Reproducing the agentic workflow](../README.md#reproducing-the-agentic-workflow) and [Bounded reproduction exercise: replay Phase 02](../README.md#bounded-reproduction-exercise-replay-phase-02); the clone and branch lines of that exercise; clips 07 and 19.
- **Say:** "Every operational step is a source-controlled script, and the README says exactly how to replay a phase with a fresh Claude Code session. The bounded pipeline runs from a clean checkout: in the cloud before the teardown, and locally on the committed samples today."
- **Commands:**
  - `git clone https://github.com/000brian/scholarchain.git /tmp/p17-replay-STAMP`. Replay: planned for replay (E5).
  - `cd /tmp/p17-replay-STAMP`. Replay: planned for replay (E5).
  - `git switch -c replay-phase-02 684c610bbcb2d314e4177677a08fdf2be0302ba6` (the Phase 01 accepted commit, [`docs/agent-runs/phase-01/commit.txt`](agent-runs/phase-01/commit.txt)). Replay: planned for replay (E5).
  - `claude` in the replay clone, then the whole text of `docs/agent-runs/phase-02/prompt.md`, pasted verbatim. Replay: not replayed in phase 17 (agent session; recorded for the video, not required for the final gate).
  - The local bounded pipeline: the commands of items 9 and 10 (their replay lines are there).
- **Blur:** nothing expected in the local parts; in clip 07, the account (the script masks it) and any bucket name in the console.

## 18. Terraform plan or deployed-resource evidence

- **Clip:** recorded: clip 14 (Phase 15, read-only): the ECS services before the runtime was turned off (query, evidence and graph desired 1, running 1; worker 0) and `terraform state list` with 214 entries (data sources included; the plan counted 186 managed resources) (runbook-log row "D16 clip 14"); and clip 18: the ECS services after the runtime-off deploy, all at 0 (row "D20 commit + clip 18").
- **Show:** clips 14 and 18; then the signed plans and release records ([`docs/final-state.md`](final-state.md#2-signed-plans), [`releases/course/`](../releases/course/)); then, live, Terraform validating the frozen configuration.
- **Say:** "All infrastructure is Terraform. No agent can apply: a plan's checksum is signed by the human in a committed line, and only the deploy script applies a signed plan, writing a release record with the image digests and task revisions. Terraform has not changed since the removal plan."
- **Commands:**
  - `terraform -chdir=infra/terraform/environments/course validate`. Replay: planned for replay (E5).
  - `bash scripts/plan.sh` (optional, read-only, against the emptied account; the resulting plan is never signed). Replay: planned for replay (E6).
- **Blur:** in clip 14 and in a `plan.sh` run, the profile name and anything unmasked; `plan.sh` masks the account and bucket names itself (Phase 17 step I1b analysis).

## 19. CloudWatch elasticity dashboard

- **Clip:** recorded: clip 12 (Phase 15, console): the dashboard from 22:55 to 23:35 UTC during experiment C, with the embedding queue's visible peak of 5.22k (5-minute maximum), the dead-letter queue at 1, and worker memory near 23 % (runbook-log row "D16 clip 12").
- **Show:** clip 12; then the committed queue-and-worker plot of experiment C, [`experiments/derived/out/p15-c-embedding-backlog/queue-20261120T225730Z-eb786c5275a3-ab26.svg`](../experiments/derived/out/p15-c-embedding-backlog/queue-20261120T225730Z-eb786c5275a3-ab26.svg).
- **Say:** "The dashboard puts queue depth, worker count and resource use side by side, so a scaling decision can be read against the load that caused it. The same series were exported into the experiment's raw folder."
- **Screen:** clip 12 and the plot. (The plot's regeneration command is in item 20.)
- **Blur (clip 12):** the account menu and the dashboard ARN.

## 20. Scale-out and scale-in evidence

- **Clip:** recorded: clip 13 (Phase 15): the ECS worker service in experiment C scaled out from 0 to 1 task (running at 23:01:58Z), to 2 (23:05:03Z), and back in to 0 (23:24:59Z), with the scaling activities and alarm changes (runbook-log row "D16 clip 13"; [`experiments/results-summary.md`](../experiments/results-summary.md), section 2.3). Clip 11 also shows EMR scale-out in experiment B (2 core nodes added); the record classifies the later drop as cluster shutdown, **not** a managed scale-in (runbook-log row "D16 clip 11"; results summary, section 2.8).
- **Show:** clip 13; the committed C raw files (`scaling-activities.csv`, `service-samples.csv` under [`experiments/raw/p15-c-embedding-backlog/20261120T225730Z-eb786c5275a3-ab26/`](../experiments/raw/p15-c-embedding-backlog/20261120T225730Z-eb786c5275a3-ab26/)); the B capacity plot under [`experiments/derived/out/p15-b-spark-burst/`](../experiments/derived/out/p15-b-spark-burst/); then, live, the plots regenerated from the raw files.
- **Say:** "Here the worker service scales out under a real embedding backlog and scales back in to zero when the queue drains. The plots are regenerated from the immutable raw files by committed scripts, so anyone can check them."
- **Commands:**
  - `python3 -I experiments/derived/queue_worker.py`. Replay: planned for replay (E5).
  - `python3 -I experiments/derived/emr_capacity.py`. Replay: planned for replay (E5).
  - `git status --short` (prints nothing when the regenerated files equal the committed ones). Replay: planned for replay (E5).
- **Blur (clip 13):** the account menu and the service and cluster ARNs.

## 21. One failure and recovery example

- **Clip:** recorded: clip 09 (Phase 15): experiment F's poison message on the embedding queue: the dead-letter queue at 1 and the main queue empty, the `embedding-dlq-not-empty` alarm in alarm since 22:44Z, and the committed `events.csv` and `incident-F.json` (root cause and recovery action as recorded) (runbook-log row "D16 clip 09").
- **Show:** clip 09; the incident report [`docs/incidents/INC-P15-F-poison-message.md`](incidents/INC-P15-F-poison-message.md) (a planned, safe exercise, not an unplanned incident); then, live, the failure timeline regenerated from the raw record ([`experiments/derived/out/p15-f-poison-replay/`](../experiments/derived/out/p15-f-poison-replay/)).
- **Say:** "This was a planned, safe failure: a message the worker can never process. The worker released it on each receive, the queue moved it to the dead-letter queue after five receives, and the alarm fired; the valid work still completed, so no rollback was needed."
- **Command:** `python3 -I experiments/derived/failure_timeline.py`. Replay: planned for replay (E5).
- **Blur (clip 09):** the account menu, the queue URLs and the alarm ARN.

## 22. Cleanup or resource inventory evidence

- **Clip:** recorded: clip 21 (Phase 15, Nov 21), five parts: (1) the evidence archive written by `archive-evidence.sh`; (2) the archive checks; (3) `cleanup.sh --list`: "Plan: 0 to add, 0 to change, 175 to destroy", grouped by the specification's cleanup categories; (4) the removal: attempt 1 refused before its confirmation prompt (L-11, nothing removed), attempt 2 removed the 175 Terraform resources after the operator's typed confirmation; (5) the leftover check on camera: 0 running EMR clusters, NAT gateway deleted, 0 live VPC endpoints, load balancers, Neptune clusters, ECS clusters, DynamoDB tables, S3 buckets and vector buckets, and 20 ACTIVE task-definition revisions kept on purpose (runbook-log rows "D25 step 3", "D25 step 4", "D25 step 5", "D27 attempt 1", "D27 attempt 2", "D28 leftover check on camera"). Optional new clip: the Phase 17 state count (E6) and the removal of the retained items (E8).
- **Show:** clip 21; the masked cleanup record [`docs/agent-runs/phase-15/cleanup/`](agent-runs/phase-15/cleanup/); the documented final state, category by category, [`docs/final-state.md`](final-state.md#6-cleanup-categories), and its retained items.
- **Say:** "Cleanup archived the evidence first, then listed, signed and applied a removal plan, and then checked what was left. The scripts refuse to remove anything without a signed plan and a typed confirmation, and every retained item is explained."
- **Commands:**
  - `bash scripts/cleanup.sh --evidence-archive PATH_TO_ARCHIVE --list`. Replay: not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/runbook-log.md`, row "D25 step 5 cleanup.sh --list"; against today's emptied account it stops with exit 3 at `terraform-outputs` before any listing, Phase 17 step I1b analysis).
  - `bash scripts/cleanup.sh --evidence-archive PATH_TO_ARCHIVE --remove --plan-sha256 HEX64`. Replay: not replayable after teardown (archived evidence: `docs/agent-runs/phase-15/cleanup/`; `docs/agent-runs/phase-15/runbook-log.md`, rows "D27 attempt 1" and "D27 attempt 2").
  - `terraform -chdir=infra/terraform/environments/course state list > /tmp/p17-E6-state.txt 2> /tmp/p17-E6-state.err; echo "exit=$?" | tee /tmp/p17-E6-state.exit; wc -l < /tmp/p17-E6-state.txt` (counts what the local state still holds; addresses only in the counted file). Replay: planned for replay (E6).
- **Blur (clip 21):** the archive path if it shows the home folder name, and any console page in the leftover check (account menu, ARNs); the scripts print the account masked and the tagged resources as `{arn}`.

## After recording

- The video link goes into the README's "Final report and video" section and deliverable D19 (`PENDING E11` until the human gives it).
- Each `planned for replay` line above becomes the observed result at step E11.
