# ScholarChain experiments

Owner: `experiment-engineer`. This folder holds experiment manifests, raw results, collectors and the scripts that derive tables and plots. The format is defined in `manifest-schema-v1.json` (JSON Schema draft 2020-12, format v1).

## Layout

| Path | What it holds | Who writes it |
|---|---|---|
| `manifest-schema-v1.json` | the manifest format, v1 | `experiment-engineer` |
| `manifests/MANIFEST_ID.json` | one committed manifest per experiment run plan | `experiment-engineer` |
| `manifests/example-not-approved.json` | the format example (`"example": true`); never runs | `experiment-engineer` |
| `collectors/` | committed executables a `command` action may run (Phase 15: `f-poison-replay.sh`, `c-embedding-backlog.sh`, `d-load.sh`, `ab-pipeline.sh`, `e-retrieval-modes.sh`) | `experiment-engineer` |
| `collectors/lib/collector-common.sh` | helpers the collectors source (never executed; no executable bit) | `experiment-engineer` |
| `raw/MANIFEST_ID/ATTEMPT_ID/` | raw results of one run; read-only after collection; committed by the human | `scripts/run-experiment.sh` (moved there after the run) |
| `derived/` | tables and plots, each reproducible from a committed script over `raw/` | `experiment-engineer` |
| `evaluation/evaluate.py` | Phase 16 evaluation tool: one self-contained Python 3 standard-library file; usage, rules and exit codes in its docstring | `experiment-engineer` |
| `evaluation/suite-v1.json` | the evaluation suite (entries, arguments, pinned inputs); approved only by the human's signed line at E3 | `experiment-engineer` |
| `evaluation/rubric-v1.md`, `evaluation/intended-differences-v1.csv` | the rating rubric; the intended differences of E10's current-version regeneration | `experiment-engineer` |
| `evaluation/spark-suite-table-v1.csv` | spark-engineer's Phase 16 group 2 suite table (43 tests), copied verbatim as the input of `evaluate.py graph` | `experiment-engineer` (content: `spark-engineer`) |
| `evaluation/fixtures/SUBCOMMAND/CASE/` | hand-written fixture cases (`case.json`, `input/`, `expected/`), never results | `experiment-engineer` |
| `evaluation/raw/GROUP/RUN_ID/` | evaluation outputs; immutable after collection; committed by the human (E12) | `evaluate.py`, run by the human |

Load generators live under `load-tests/` (also `experiment-engineer`).

## What a manifest is

A manifest is one JSON file that says, before anything runs, which experiment (A to F of the specification's Phase 15) is run, on which dataset snapshot, in which Region, with which declared resource limits and cost bound, by which action, and which CloudWatch metrics are exported afterwards. The file name is the `manifestId` plus `.json`. A manifest holds no secret, account ID, ARN with an account ID, queue URL or literal bucket name: S3 locations are logical (`s3://{data-bucket}/KEY`), and metric dimension values that would carry an account ID are placeholders in braces (for example `{state-machine-arn}`, `{emr-cluster-id}`) resolved at run time.

Required fields (types and value rules: `manifest-schema-v1.json`): `format`, `formatVersion`, `manifestId`, `example`, `experiment`, `description`, `region`, `datasetSnapshot`, `costBoundUsd`, `maxDurationSeconds`, `resourceLimits`, `action`, `metrics`. Optional: `notes`.

## How the human approves a manifest

1. `experiment-engineer` commits the manifest (through the human's commit).
2. The human computes `shasum -a 256 experiments/manifests/MANIFEST_ID.json` on the committed file and, at a gate, signs it by committing a whole line of exactly this form in a file named `human-gate.md` or `approvals.md` under `docs/agent-runs/`:

   ```text
   APPROVED-MANIFEST-SHA256: HEX64
   ```

   `HEX64` is the 64 lowercase hex characters of the sha256, and nothing else is on the line. Other files (agent reports, summaries, prompts) never count, so a report that quotes a checksum cannot approve it. Agents cannot write under `docs/agent-runs/`.
3. Any change to the manifest changes its sha256 and needs a new approval line.

An example manifest (`"example": true`) is never approved and never runs.

## How `scripts/run-experiment.sh` runs a manifest

Command: `bash scripts/run-experiment.sh --manifest experiments/manifests/MANIFEST_ID.json --manifest-sha256 HEX64` (authored by `infra-engineer`; this section describes its contract from decision 9.7 of the Phase 13 work order; the script's usage text is authoritative).

It refuses, checked in this order (the first failing check gives the message):

1. a dirty tree;
2. a manifest that is not tracked by git;
3. a sha256 that does not equal the file's;
4. `"example": true`;
5. a manifest that fails the v1 checks below (marked "yes"), including a Region other than `us-east-1` and `costBoundUsd` above the 5 USD cap;
6. no approval line `APPROVED-MANIFEST-SHA256: HEX64` for that sha256 in a tracked `human-gate.md` or `approvals.md` under `docs/agent-runs/`.

Three guarantees, kept apart: integrity (the committed file has exactly the given sha256; checked), approval (the human's committed approval line; checked), caller (not checked; anyone with a shell on the laptop and AWS credentials can run it, an accepted limit of a one-person project).

Then it prints the cost bound and limits labelled "declared, not enforced", and runs the action under `timeout maxDurationSeconds`:

- `"kind": "pipeline"`: `scripts/run-bounded-pipeline.sh` with the manifest's `args`, unchanged;
- `"kind": "command"`: the committed executable at `path` (only under `load-tests/` or `experiments/collectors/`) with `args`.

After the action it exports the manifest's `metrics` with CloudWatch `get-metric-data` for the run window plus 5 minutes on each side. Phase 15 (decision 9.6, `scripts/run-experiment.sh` usage text authoritative): it waits 300 s after every action that ended (succeeded, failed or timed out) before the export; a run stopped by a signal exports nothing; `{attempt-id}` in `action.args` is replaced by the attempt ID; `{alb-dimension}` and `{alb-target-group-query|evidence|graph}` are new metric placeholders; `--check` and `--export-metrics ATTEMPT_ID` are new modes; exit 8 is new (table below).

### Rules: enforced by the runner or documentation only

The runner has no JSON Schema validator; it checks with `jq` exactly the rules marked "yes". Every rule marked "no" is documentation only: a manifest that breaks it is not refused, and the human checks it when signing.

| Rule | enforced by run-experiment.sh |
|---|---|
| `format` equals `"scholarchain.experiment-manifest"` | yes |
| `formatVersion` equals `1` (a number) | yes |
| `manifestId` is a string matching `^[a-z0-9][a-z0-9-]{0,63}$` | yes |
| the file name is `manifestId` plus `.json` | yes |
| `example` is a boolean; `true` is refused | yes |
| `experiment` is one of `A`, `B`, `C`, `D`, `E`, `F` | yes |
| `description` is a non-empty string | yes |
| `region` equals `us-east-1` | yes |
| `datasetSnapshot` is a non-empty string | yes |
| `costBoundUsd` is a number greater than 0 and not above 5 (the runner's hard per-run cap) | yes |
| `maxDurationSeconds` is an integer greater than 0 | yes |
| `resourceLimits` is an object | yes |
| `action.kind` is `pipeline` or `command` | yes |
| `action.args` is an array of strings | yes |
| `action.path` (kind `command`) starts with `load-tests/` or `experiments/collectors/` and has no `..` segment | yes |
| `metrics` is an array | yes |
| `manifestId` is unique across `experiments/manifests/` | no |
| no field other than the listed ones (`additionalProperties: false`) | no |
| the recommended keys and types inside `resourceLimits` | no |
| `action.path` is a committed executable file | no (git and the shell refuse a missing or non-executable file when the action starts) |
| `action.args` match the usage text of `scripts/run-bounded-pipeline.sh` or of the command | no (the called program refuses wrong arguments) |
| each `metrics` item has `namespace`, `metricName`, `dimensions` (name and value pairs), `stat`, `periodSeconds` (60 or a multiple of 60) | no (a malformed query makes the export fail: the run becomes `succeeded-metrics-failed`) |
| no secret, account ID, ARN with an account ID, queue URL or literal bucket name in the manifest | no (the human checks it when signing) |
| `datasetSnapshot` names a snapshot in `datasets/dataset-manifest.yaml` | no |

## What the limits do

`maxDurationSeconds` bounds only the local runner; remote work it started (a Step Functions execution, an EMR cluster) keeps running and is bounded by the state machine's own timeout, the EMR managed-scaling maximum and the idle end, all set in Terraform. `resourceLimits` are recorded, not applied: the real caps are the Terraform values. `costBoundUsd` is a declaration; the runner refuses a manifest whose `costBoundUsd` is above a hard per-run cap of 5 USD (a constant in the script), and nothing measures spend during a run.

On a local timeout the runner prints what may still be running remotely (the Step Functions execution name from the nested record, running EMR clusters tagged `project=scholarchain`) and exits 7. It never stops remote work itself.

## Where the raw data goes

1. During the run everything is written under `evidence/experiment-runs/ATTEMPT_ID/` (gitignored), because the nested `scripts/run-bounded-pipeline.sh` refuses a dirty tree and a new folder under `experiments/raw/` would make the tree dirty.
2. After the metrics export the runner moves the folder to `experiments/raw/MANIFEST_ID/ATTEMPT_ID/` and makes it read-only. The human commits it; it is committed raw evidence.
3. Inside a run folder: `run-record.json` (written as `started` before the first action, then final: HEAD, build commit, manifest sha256, start and end UTC, Region, declared limits, action exit code, metric file locations, every remote ID the action reported, all as logical locations) and `metrics/` (the CloudWatch exports). Final statuses: `succeeded`, `failed`, `timed-out`, `succeeded-metrics-failed`.

`ATTEMPT_ID` is the UTC time to the second, the short HEAD and 4 random hex characters, so two runs never share a folder.

## Raw results are immutable; failed runs are kept

- Nothing under `experiments/raw/` is edited, rewritten, renamed or removed after collection, by any agent or script. A correction is a new run with a new attempt ID, or a derived table that says what it excludes and why.
- Every attempt keeps its folder and its `run-record.json`, whatever its status. A run killed so hard that it left only a `started` record is an incomplete attempt, never a success. Failed, timed-out and incomplete runs are listed in every results summary; none is silently discarded.
- Derived tables and plots are produced by committed scripts under `experiments/derived/` from `raw/` only, so anyone can reproduce them.
- No implementation is tuned during a run and reported as one experiment; a change of code, configuration or Terraform is a new build commit and a new manifest.

## Exit codes (Phase 15, decision 10; confirmed by infra-engineer against `scripts/run-experiment.sh` after 9.6(d) and (e))

| Runner exit | Meaning | Collector exit behind it | Final folder in `experiments/raw/` | Remote work may still run | Overnight chain |
|---|---|---|---|---|---|
| 0 | action succeeded, metrics exported (or none declared); `--check`: all checks and the preflight passed | 0 (ran; observation recorded) | yes (`--check`: no folder) | no | continue |
| 2 | usage error | – | no | no | stop |
| 3 | missing prerequisite or Terraform output (the runner's own) | – | no | no | stop |
| 4 | refused: dirty tree, untracked manifest, sha256, example, v1 check, a placeholder other than `{attempt-id}` and `s3://{data-bucket}/`, approval, run or raw folder exists, action path untracked or not executable | – | no (the action-path refusal also happens before the folder exists) | no | stop |
| 5 | the action failed (metrics exported after the 300 s wait; status `failed`); also a runner command failure after the start, Ctrl-C (INT: status `failed`, signal INT, no export), and `--check`'s failed preflight | any non-zero other than 124 and 137: 3 (prerequisite not met), 5 (tool or AWS failure), 7 (collector wait limit), or the nested pipeline's own failure (2 to 7, passed through by `ab-pipeline.sh`) | yes (status `failed`), except `--check` (no folder) | possibly (the collectors and the wrapper record it) | continue only after the active-cluster check |
| 6 | action succeeded, metrics failed or partial (`succeeded-metrics-failed`) | 0 | yes | no | continue |
| 7 | the runner's time limit (`maxDurationSeconds`: status `timed-out`, metrics exported, `MAY-STILL-RUN` lines) or TERM (status `timed-out`, signal TERM, no export); `--check`: the preflight passed 600 s | – (the action was stopped) | yes (time limit); yes or only a `started` record (TERM) | **yes** | stop |
| 8 | an identifier (account ID, bucket name, API host, queue or DLQ URL, ALB or Neptune host) was found in the run record or the run folder; the folder stays under `evidence/experiment-runs/` (never commit it) | – | no | no | stop |
| anything else | unknown | – | – | – | stop |

Notes (5a): a collector's own exit 7 (its wait limit) becomes runner exit 5 (the runner reports only "the action failed"); runner exit 7 always means the runner's own limit or TERM. Exit 4 can also come from the final move when the raw destination appeared meanwhile (the run then stays under `evidence/`). `--export-metrics` exits 0, 6 or 8 (plus 2, 3, 4, 5). A collector exit 3 after its bounded settle is a temporary unmet prerequisite: the attempt is kept as `failed` and a later attempt is a new run. The scientific outcome is never read from an exit code: every attempt gets four lines (`tool`, `action`, `metrics`, `observation`; decision 10).

## Collector contract (Phase 15)

- The runner starts `./experiments/collectors/NAME.sh RESOLVED_ARGS` from the repository root with standard input `/dev/null` and three variables: `SCHOLARCHAIN_EXPERIMENT_RUN_DIR` (the run folder under `evidence/experiment-runs/ATTEMPT_ID/`, gitignored), `SCHOLARCHAIN_EXPERIMENT_ATTEMPT_ID`, `SCHOLARCHAIN_EXPERIMENT_MANIFEST_ID`. `--check` appends `--preflight` as the last argument and points the run folder to a temporary folder outside the repository.
- Collectors write raw files only into the run folder (one CSV per event stream plus `tool-record.json`), never `run-record.json`, `run-record.started.json`, `manifest.json`, `action.log`, `metrics/` or `nested/` (the runner's). They store extracted fields only: no raw AWS CLI output, no error text, no response body, no URL, host, key or account. The runner's identifier check (exit 8) is the backstop.
- Exit codes: 0 ran (whatever the system answered), 2 usage, 3 prerequisite not met after a bounded settle (nothing sent), 5 tool or AWS failure, 7 the collector's own wait limit or TERM. `ab-pipeline.sh` and `d-load.sh` pass their child's exit code through.
- `ab-pipeline.sh` runs `bash scripts/run-bounded-pipeline.sh` with the arguments unchanged and does not wrap its output, so the nested `RUN-RECORD:` and `EXECUTION:` lines reach the runner (`{emr-cluster-id}` resolution); it finds the nested record by its output root.
- `--preflight` makes read calls only, prints counts and states, sends nothing and writes nothing outside a temporary folder outside the repository.
- Vector keys for C and F (repair-01, finding M-01): no collector calls the S3 Vectors data plane. The vector bucket policy (`infra/terraform/modules/vectors/main.tf`, `DataPlaneOnlyForRuntimeAndOps`) explicitly denies `ListVectors`, `GetVectors`, `PutVectors` and `QueryVectors` to every principal except the query, worker and ops task roles, so the laptop profile would be refused. Keys come from the DynamoDB `ArtifactRegistry` table (Terraform output `dynamodb_table_names.ArtifactRegistry`; items `pk = artifact#vector#INDEX/VECTOR_KEY`, `sk = v#NNNNNNNN`, one per registered version) through `lib/collector-common.sh` `cc_registry_select`: a bounded, read-only, eventually consistent Scan with `begins_with(pk, :p)`, projection `pk`, 1,000 items evaluated per request, at most 80 CLI calls and a total window of 420 s from the start of the selection (not a gap between calls: no new call starts after it, and a call already started can run past it by up to 3 x 120 s plus backoff, so the worst case is about 790 s); duplicate versions of one key are dropped keeping the first, and the first N unique keys in scan order are taken (fewer: exit 3, nothing sent). `cc_registry_latest` reads a key's latest version (Query, `ScanIndexForward=false`, Limit 1) and checks that `textHash`, `embeddingModel` and `embeddingDimensions` are present and that `embeddingModel` and `embeddingDimensions` equal the expected values; the expected `embeddingDimensions` comes from `config/course.json`, and the expected `embeddingModel` is built from `config/course.json` (provider, model, dimensions) plus two rag code constants copied into both collectors, `EMB_INPUT_TYPE="document"` and `EMB_SPEC_VERSION="emb-v1"` (`c-embedding-backlog.sh:46-47`, `f-poison-replay.sh:51-52`), and the copied `EMB_SPEC_VERSION` must change when rag bumps `EmbeddingModelSpec.SpecVersion`: C checks 20 evenly spaced keys (`sample-check.csv`, same columns as before), F checks its one key and uses that `textHash` for the completion-record check. The vector metadata itself is checked by the handler: a missing field is a permanent failure of that task, counted as a workload failure. Both preflights run the same selection and checks. A scan is charged on every item it evaluates (cost estimate in the C manifest notes).
- F's part 2 waits (repair-01, finding J-01): each wait's limit counts from the start of that wait, and the worker log is read at least once per wait (from 60 s before the replay send), so a part 1 that ends more than 720 s after the replay send no longer skips the replay check; `events.csv` records each wait's start and deadline (`wait-start-processed`, `wait-start-replayed`).
- The executable bit of every collector and of `load-tests/query-load.sh` is set by the human before the commit (decision 16); `lib/collector-common.sh` is sourced and needs none.

## Session 3 checks before the experiments (decision 13)

- **D0a (local, no AWS, before packaging):** Python syntax of every derive script (`python3 -I -c` with `ast.parse`, no bytecode written); each derive script with `--fixtures` (output to a temporary folder outside the repository); `bash -n` on every collector, on `collectors/lib/collector-common.sh` and on `load-tests/query-load.sh`; `jq empty` on every manifest; `ls -l` of the tools (executable bits); then `git status --short` prints nothing. A failure is a tool repair before AWS work starts (tools are not build inputs: no repackaging).
- **D9a (after D4 and the D9 signatures, before D10):** `bash scripts/run-experiment.sh --manifest experiments/manifests/ID.json --manifest-sha256 HEX64 --check` for every signed manifest: it runs every refusal, resolves the metric placeholders (the ALB placeholders and the DLQ URLs need the Phase 15 Terraform outputs, which exist only after D4), shows the `{attempt-id}` substitution and runs each collector's `--preflight` through the real invocation path (arguments, environment, executable bit). All manifests here are `command` actions, so all get a preflight (A and B through `ab-pipeline.sh --preflight`: inputs present, the ER record succeeded, the output root empty, the jars of the build commit present). `p15-f-poison-replay` and both D manifests need `SCHOLARCHAIN_SERVICE_API_KEY` exported in that terminal. Then `git status --short` prints nothing.
