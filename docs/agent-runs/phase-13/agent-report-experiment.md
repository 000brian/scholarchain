# agent-report-experiment.md (phase 13, session 2, step 2)

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), decision 6 (with standing rules, 9.7, 10)
FILES CREATED:
- experiments/README.md (sha256 b1029b284ee72fce2a09609f52f64713bf9d8f4816502f9895c32729e1548b5e)
- experiments/manifest-schema-v1.json (sha256 61b6f383dfc2e13eefdaf109c6ea70f94beefebc06b53e566b79bc59d3d7f56f)
- experiments/manifests/example-not-approved.json (sha256 0b80661473aeea6e0e97696a0d38f84fe9d71c91384cb7a578dc1c33d736a291)
FILES MODIFIED: none
COMMANDS RUN: shasum -a 256 (prompt, new files); wc -l; ls; grep -rniE / grep -nE (read-only searches, words not on the guard list); jq empty (two literal paths); git status --short --ignored experiments. Off-list, read-only: one `sed -n 30,40p datasets/dataset-manifest.yaml` (printing only; the standing rules list `sed` as not allowed; no approval prompt appeared; reported here as required).
TEST RESULTS: not applicable (no sbt; no Scala change)
VALIDATION RESULTS: jq empty on both JSON files -> exit 0. git status: `?? experiments/` (untracked, not ignored, as decision 5.12 requires).
ASSUMPTIONS:
- Experiment A's example runs only the Hadoop half (`--stage hadoop`, decision 9.6), since one manifest has one action and one pipeline call has one stage.
- datasetSnapshot names the DBLP snapshot `2026-11-01` and subset rule `dblp-subset-v1` from datasets/dataset-manifest.yaml (lines 34, 376).
- Example limits (EMR 1..2 units, cost 1 USD, 3600 s) are illustrative, not read from Terraform values.
RISKS (interface items for step 7a/7b, infra-engineer; each is a runner/pipeline behavior this format assumes but cannot enforce):
- R1: S3 arguments in manifests are logical (`s3://{data-bucket}/KEY`, identifier rule). Either run-experiment.sh or run-bounded-pipeline.sh must resolve `{data-bucket}` from `terraform output`; nothing in decision 9 says which. Without it, pipeline manifests cannot hold S3 arguments.
- R2: metric dimension placeholders `{emr-cluster-id}` and `{state-machine-arn}` (example lines 34-58) must be resolved by run-experiment.sh at export time (cluster IDs from the nested run record, the ARN from terraform output). Not in decision 9.7 text.
- R3: example `action.args` use `--stage`, `--dblp`, `--dblp-snapshot`, `--wait-timeout` (Phase 12 names plus decision 9.6 `--stage`). Step 7b may rename them; the example never runs, but should be updated if names change (a manifest change, so a new sha256).
- R4: the README table marks the `action.path` "no `..` segment" rule as enforced (README:80); decision 6 says "the path prefix"; a prefix check without the `..` rule would let `load-tests/../x` escape. infra-engineer should implement both.
- R5: experiment C needs an embedding-queue consumer, none exists (decision 7, phase-15).
MANUAL ACTION REQUIRED: none in this phase (the example is never approved). Real manifests for Phase 15 need the human's `APPROVED-MANIFEST-SHA256:` line.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-2 (code-reviewer, steps 2-5); step 7a writes run-experiment.sh against README "Rules: enforced by the runner or documentation only".

## Requirements (decision 6)

- README, what a manifest is: done, experiments/README.md:18-22
- README, how the human approves (approval line format `APPROVED-MANIFEST-SHA256: HEX64`): done, experiments/README.md:24-36 (line at :30)
- README, how run-experiment.sh runs it (refusal order per 9.7, three guarantees, action kinds, metrics export): done, experiments/README.md:38-60
- README, enforced yes/no table: done, experiments/README.md:62-89
- README, limits text exactly as decision 6 (incl. 5 USD hard cap): done, experiments/README.md:93
- README, where raw results go (decision 9.7): done, experiments/README.md:97-103
- README, raw results immutable, failed runs kept: done, experiments/README.md:105-110
- Schema, JSON Schema draft 2020-12, format v1, all required fields: done, experiments/manifest-schema-v1.json:2 ($schema), :7-21 (required list)
- Example, `"example": true`, experiment A, small limits: done, experiments/manifests/example-not-approved.json:5-11
- jq empty on every JSON file: done (exit 0)

## Manifest field list

Authoritative: experiments/manifest-schema-v1.json (required list at :7-21; per-field rules in `properties`). Required: format, formatVersion, manifestId, example, experiment, description, region, datasetSnapshot, costBoundUsd, maxDurationSeconds, resourceLimits, action, metrics. Optional: notes.

## Manifest IDs, run IDs, raw data, scale evidence, failures, cost

- Manifest IDs: example-not-approved (example, never runs). Run IDs: none. Raw data: none. Scale-out/in evidence: none (no run). Failures: none. Cost: 0 USD; no AWS call made.

VALIDATION EVIDENCE
- fmt: not applicable
- validate: not applicable
- shellcheck: not applicable
- jq: jq empty /Users/brian/Developer/repos/scholarchain/experiments/manifest-schema-v1.json /Users/brian/Developer/repos/scholarchain/experiments/manifests/example-not-approved.json -> 0
- finished: after the last command above
- files changed after these runs: no
