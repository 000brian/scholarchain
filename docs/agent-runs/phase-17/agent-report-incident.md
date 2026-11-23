STATUS: COMPLETE
OWNER: incident-responder
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, re-checked with shasum), session 2 step N, decision 10 (tier 2)
FILES CREATED: /Users/brian/Developer/repos/scholarchain/docs/incidents/INC-P15-F-poison-message.md
FILES MODIFIED: none
COMMANDS RUN: shasum -a 256 (work order; incident-F.json); wc -l; ls; grep -n (literal words, the work order and the course specification); git ls-files (source paths); git status --short (twice); ls of every link target; grep -c (identifier patterns over the new file). Read tool: course-specification.md lines 3105-3139 ("Incident and rollback requirements", read in full), prompt.md lines 1-222 and decision 10 (lines 233-235), incident-F.json, results-summary.md lines 1-90 (sections 0, 1, 2.1). No AWS or aws-api call, no aws-docs call, no script, no sbt, no action, no rollback.
TEST RESULTS: not applicable (document only; sbt not run, as the work order says)
VALIDATION RESULTS: all 6 link targets exist (ls); all 6 source paths are committed (git ls-files); incident-F.json sha256 a75efa8a... matches the prefix in results summary 2.1; identifier-pattern count over the new file = 0 (ARN, amazonaws, bare 12-digit, URL, SQS host patterns); epoch-to-UTC conversions checked by hand against the anchor 1795214360 = 2026-11-20T22:39:20Z (day arithmetic) and against every time results summary 2.1 prints (22:41:47, 22:41:57, 22:41:58, 22:42:01, 22:42:45, 22:44:50: all agree)
ASSUMPTIONS: the document ID is INC-P15-F (file name), and the recorded incidentId is given beside it. The run-record start and end of the action (22:39:08, 22:44:55) are quoted from results summary 2.1, not from run-record.json, which I did not read (the work order names only the two sources). The epoch-to-UTC conversion is by hand.
RISKS: low. Distributed trace IDs and the API query ID are `not recorded` in the sources, so the spec field "relevant trace and run IDs" is met with run and task IDs only. User-visible impact is "not measured" in the record; the report says so and does not claim zero impact.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none. The stop hook did not block. git status shows uncommitted changes under experiments/** (experiments/README.md, experiments/collectors/lib/collector-common.sh, experiments/results-summary-template.md): they are experiment-engineer's step X1, not mine, and the stop hook does not count them
NEXT GATE: R-D1 (documentation review covers docs/incidents/**); W5 links the file in the deliverable index

## Incident ID and report path
INC-P15-F (recorded ID p15-f-20261120T223908Z-e94df52ca8fb-b4c0): /Users/brian/Developer/repos/scholarchain/docs/incidents/INC-P15-F-poison-message.md. This was a planned, safe exercise (Phase 15 experiment F), not an unplanned incident, and the report says so in bold at the top.

## Root cause
The exercise injected it on purpose: a valid-JSON embedding-task-v1 message naming an unknown index, a permanent failure by design (incident-F.json rootCause).

## Actions taken with approvals
None. No rollback, redrive or script was needed or run. The recorded recovery happened on its own: SQS moved the message to the DLQ after maxReceiveCount 5. Nothing in this step needed approval.

## Data-integrity assessment
From the record: nothing was written for the poison; the valid task rewrote one existing vector under its existing key. Limit: only the completion record's presence was checked.

## Follow-up owners
experiment-engineer (analysis, D16, done in results-summary.md 2.1); rag-engineer or service-engineer only for a part not seen, and the record shows none.

## INTERFACE HANDOFF
- File: docs/incidents/INC-P15-F-poison-message.md (for W5's deliverable index; spec deliverable "incident report").
- Headings: "# INC-P15-F: poison message on the embedding queue (planned exercise, experiment F)"; "## Sources"; "## Required fields" (one table row per spec field, in the spec's order: Incident ID, Start time, End time, Detection source, User-visible impact, Trace and run IDs, Timeline, Root cause, Recovery action, Data-integrity assessment, Follow-up owner, Preventive test or control); "## Timeline" (anchor #timeline); "## Interpretation (labelled, from results summary 2.1)"; "## What this report does not cover".
- Key values others may cite: start 2026-11-20T22:39:19Z, end 22:42:45Z, detected 22:44:50Z (DLQ alarm), detection 125 s after the end (computed by hand).
- Fixtures, thread-safety: not applicable. Limits: there was no live re-check; trace IDs, the API query ID and user-visible impact are `not recorded`.

## VALIDATION EVIDENCE
```text
$ shasum -a 256 docs/agent-runs/phase-17/prompt.md
c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24  docs/agent-runs/phase-17/prompt.md
$ shasum -a 256 experiments/raw/p15-f-poison-replay/20261120T223908Z-e94df52ca8fb-b4c0/incident-F.json
a75efa8af7e9bbfbde9f22128b1d37a0eb60b6fa5ef222325cca4cd686872b4e
$ (from docs/incidents/) ls of every linked path
../../experiments/derived/out/p15-f-poison-replay/parts.csv
../../experiments/derived/out/p15-f-poison-replay/timeline.csv
../../experiments/manifests/p15-f-poison-replay.json
../../experiments/raw/p15-f-poison-replay/20261120T223908Z-e94df52ca8fb-b4c0/incident-F.json
../../experiments/results-summary.md
../agent-runs/phase-15/agent-report-experiment-FECD.md
$ git ls-files (same 6 paths): all 6 listed (committed)
$ grep -c -E 'arn:aws|amazonaws|[^0-9a-f][0-9]{12}[^0-9a-f]|https?://|sqs\.' INC-P15-F-poison-message.md
0
$ git status --short
 M experiments/README.md                          (X1, not mine)
 M experiments/collectors/lib/collector-common.sh (X1, not mine)
 M experiments/results-summary-template.md        (X1, not mine)
?? docs/agent-runs/phase-17/
?? docs/incidents/
?? (.DS_Store files under experiments/)
files changed after these runs: none
```
