# repair-D10 experiment-engineer: finding L-02 (class 2, collector defect)

Work order: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, verified), standing rules, decisions 10, 11, 13 ("When a step fails"). HEAD 6a6c31a.

## Finding L-02

D9a `--check` of p15-f-poison-replay stopped at f-poison-replay.sh step select-key with `(no - model-mismatch)`: both collectors passed `models.embeddingModelId` ("voyage-4") as MODEL to lib `cc_registry_latest` (collector-common.sh:781 compares it with the registry's `embeddingModel`), but storage writes the full spec ID `EmbeddingModelSpec.id`.

## Source of the expected ID (read, cited)

- `modules/rag/src/main/scala/org/scholarchain/rag/embedding/EmbeddingModelSpec.scala:35`: `id = s"${provider.wire}/$model/$dimensions/${inputType.wire}/$specVersion"`; example at :18-19.
- :52 `SpecVersion = "emb-v1"` (a code constant, not in config); :11 `InputType.Document.wire = "document"`; :25 stored vectors are always document-side; :113-118 `fromConfig` = `embedding.provider`, `models.embeddingModelId`, `embedding.dimensions`.
- `modules/core/src/main/scala/org/scholarchain/core/config/ScholarChainConfig.scala:191-195` provider wires `voyage`, `bedrock`, `fake`; `modules/core/src/main/scala/org/scholarchain/core/Enums.scala:19-22` exact-match `fromWire`.
- Document input type for stored vectors: `modules/rag/src/main/scala/org/scholarchain/rag/ingest/RagIngestJob.scala:453`, `modules/rag/src/main/scala/org/scholarchain/rag/reembed/ReembedHandler.scala:347`.
- `modelId = spec.id`: VoyageEmbedder.scala:78, BedrockTitanEmbedder.scala:75, FakeLexicalEmbedder.scala:23; written by storage VectorIngestPipeline.scala:518 and VectorReembedder.scala:367 (compared at :350).
- config/course.json: `embedding.provider` "voyage" (:61), `models.embeddingModelId` "voyage-4" (:16), `embedding.dimensions` 1024 (:63).

Exact expected ID the tools now compute from config/course.json at HEAD: `voyage/voyage-4/1024/document/emb-v1` (dimensions compared separately: `1024`).

## Changes (one status line per item)

- DONE f-poison-replay.sh:38-52: constants `EMB_INPUT_TYPE="document"`, `EMB_SPEC_VERSION="emb-v1"` with the file:line citations above.
- DONE f-poison-replay.sh:88-89: new globals `EMB_PROVIDER`, `EMB_MODEL_NAME`.
- DONE f-poison-replay.sh:181-198: new `expected_model_id`: reads provider, model, dimensions with jq; exit 3 if any is missing, the provider is not voyage|bedrock|fake, or dimensions is not a positive integer; sets `MODEL="${EMB_PROVIDER}/${EMB_MODEL_NAME}/${DIMS}/${EMB_INPUT_TYPE}/${EMB_SPEC_VERSION}"`; one cc_info line with the expected ID.
- DONE f-poison-replay.sh:212: `read_names` calls `expected_model_id` (replaces the old three lines that set MODEL to "voyage-4").
- DONE f-poison-replay.sh:266 (unchanged call): `cc_registry_latest ... "${MODEL}" "${DIMS}"` now receives the spec ID.
- DONE f-poison-replay.sh:270: the exit-3 message names the expected ID.
- DONE f-poison-replay.sh:273-281: tool-record `selection` gains `expected_embedding_model` (string) and `expected_embedding_dimensions` (number); `checked` text describes the spec-ID comparison.
- DONE f-poison-replay.sh:643-644: preflight line prints the expected embeddingModel.
- DONE f-poison-replay.sh:249-252: comment of select_valid_key updated.
- DONE c-embedding-backlog.sh:33-47: same constants and citations.
- DONE c-embedding-backlog.sh:60-61: new globals `EMB_PROVIDER`, `EMB_MODEL_NAME`.
- DONE c-embedding-backlog.sh:168-185: the same `expected_model_id` function (identical body).
- DONE c-embedding-backlog.sh:197: `read_names` calls it.
- DONE c-embedding-backlog.sh:355 (unchanged call): `cc_registry_latest` receives the spec ID for each of the 20 sampled keys.
- DONE c-embedding-backlog.sh:364-374: tool-record `selection.registry_sample` gains `expected_embedding_model` and `expected_embedding_dimensions`; `checked` text updated.
- DONE c-embedding-backlog.sh:375-376: SAMPLE line prints the expected ID and dimensions.
- DONE c-embedding-backlog.sh:334-338: comment of check_sample updated.
- CHECKED, no change needed: every other place in F and C that touches the model. F: `MODEL` used only at :266, :270, :275, :644 (all above); the task message (`task_body`, embedding-task-v1: schema, taskId, index, vectorKey) carries no model; incident-F.json has no model field. C: `MODEL` used only at :355, :368, :376; the C preflight (:537-549) prints no model; sample-check.csv `model_check` column comes from lib CC_REG_LINE (model-ok / model-mismatch), unchanged format. Dimensions comparison in lib (:781, `embeddingDimensions.N` vs "1024") was already correct.
- NOT CHANGED (constraint): experiments/collectors/lib/collector-common.sh (sha256 34a5ce14036a774ac0f12738a876df9aadf3b7dd2ebfa969cbb9d69f28cb2dd5, not in git status), e-retrieval-modes.sh, d-load.sh, ab-pipeline.sh. The fix did not need the lib: the lib compares whatever MODEL the caller passes.
- NOT CHANGED: both manifests.

## Tool and manifest hashes (shasum -a 256)

- experiments/collectors/f-poison-replay.sh: 02a5b0edff23801a10b6d3a1c11f821881e46feed3bdf22eac14b92577cf617e (new)
- experiments/collectors/c-embedding-backlog.sh: 5a9e895142fc2734921fbaba69dd3e29afe8f3e834dbbdbe3e9d5d2b7592b8f5 (new)
- experiments/manifests/p15-f-poison-replay.json: a39c7e453f3c9264092fc22640e2c56465e06ff947bf031291ee9112a5b5fbaf (unchanged, matches a39c7e45...)
- experiments/manifests/p15-c-embedding-backlog.json: 36352e8fcdf6287a41b66e386f045f81d88db864a7f07e0169c7a6efa5101ab8 (unchanged, matches 36352e8f...)

Manifests are unchanged; their notes say "model and dimensions equal to config/course.json" / "the configured model and dimensions", which the spec-ID comparison still satisfies (the ID is derived only from config plus the rag code constant), so no new approval line is needed.

```text
STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), session 3 repair L-02 (repair-D10), HEAD 6a6c31a
FILES CREATED: none in the repository (this report in the scratchpad)
FILES MODIFIED: experiments/collectors/f-poison-replay.sh, experiments/collectors/c-embedding-backlog.sh
COMMANDS RUN: shasum -a 256 (work order; tools, manifests, lib); grep -n / grep -rn (read-only searches of modules/, config/, experiments/); shellcheck on both literal files; shellcheck -x on both literal files; git show HEAD:<file> piped to shellcheck -f gcc - (baseline comparison); git status --short; git diff --stat
TEST RESULTS: no tests apply (shell collectors; the work order forbids running any script). runtime: untested
VALIDATION RESULTS: shellcheck -x on both files: exit 0, no findings. Plain shellcheck: the same codes as at HEAD (F: SC1091 x1, SC2034 x3; C: SC1091 x1, SC2034 x4), all pre-existing (lib not followed without -x; CC_* globals consumed by the sourced lib); no new finding
ASSUMPTIONS: stored vectors in researcher-profile-v1 were written with the current SpecVersion "emb-v1" and the config of config/course.json (the ID in the example of EmbeddingModelSpec.scala:18-19 and the D9a failure are consistent with this; not checked against live data, no aws-api call made); EMB_SPEC_VERSION is a copy of a Scala constant and must change if rag bumps EmbeddingModelSpec.SpecVersion
RISKS: if any registry version was written under another spec (an older SpecVersion or another dimension), F and C stop with exit 3 before sending (intended, fail closed); the copied constant can drift from rag (documented in both files next to the constant)
MANUAL ACTION REQUIRED: human commit of the two collector files after code-reviewer review; then repeat D9a --check of p15-f-poison-replay (and C's preflight) when E has finished; manifests need no new approval
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the two-file diff, human commit, re-run of D9a --check
```

## Agent-specific sections

- Manifest IDs: p15-f-poison-replay, p15-c-embedding-backlog (unchanged).
- Run IDs: none (nothing run).
- Raw-data locations: none created.
- Scale-out and scale-in evidence: not applicable.
- Failures: the D9a `--check` failure of F (model-mismatch) is the defect repaired here; it stays recorded as a failed check.
- Cost: $0 (no AWS call, no aws-api call).

## INTERFACE HANDOFF

- Files changed: experiments/collectors/f-poison-replay.sh, experiments/collectors/c-embedding-backlog.sh. No option, exit code, file name, CSV header or message format changed.
- New shell function in each tool: `expected_model_id` (no arguments; sets globals EMB_PROVIDER, EMB_MODEL_NAME, DIMS, MODEL; exit 3 via cc_die on missing or invalid config parts).
- New constants: `EMB_INPUT_TYPE="document"`, `EMB_SPEC_VERSION="emb-v1"`.
- Config read (unchanged file): config/course.json `.embedding.provider`, `.models.embeddingModelId`, `.embedding.dimensions`.
- New tool-record fields: F `selection.expected_embedding_model` (string), `selection.expected_embedding_dimensions` (number); C `selection.registry_sample.expected_embedding_model`, `selection.registry_sample.expected_embedding_dimensions`. The `checked` strings changed text.
- Console lines changed: one new cc_info line "expected stored embeddingModel ..." in both tools; F PREFLIGHT valid-key line and C SAMPLE line gain the expected ID; F select-key exit-3 message names the expected ID.
- Lib contract used unchanged: `cc_registry_latest TABLE INDEX KEY MODEL DIMS` (collector-common.sh:757-784).
- Thread-safety: not applicable (sequential shell). Limits: the spec version is copied from Scala, not read at run time.

## Proposed later

- Have the rag build export the document spec ID (or SpecVersion) in a generated, committed file the collectors read, so the copied constant cannot drift (needs rag-engineer and a work order).

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-15/prompt.md` -> 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0
- `shellcheck -x experiments/collectors/f-poison-replay.sh experiments/collectors/c-embedding-backlog.sh` -> no output, exit 0
- `shellcheck` (plain) codes HEAD vs now: F SC1091 SC2034 SC2034 SC2034 / same; C SC1091 SC2034 SC2034 SC2034 SC2034 / same
- `shasum -a 256` -> f-poison-replay.sh 02a5b0edff23801a10b6d3a1c11f821881e46feed3bdf22eac14b92577cf617e; c-embedding-backlog.sh 5a9e895142fc2734921fbaba69dd3e29afe8f3e834dbbdbe3e9d5d2b7592b8f5; p15-f-poison-replay.json a39c7e453f3c9264092fc22640e2c56465e06ff947bf031291ee9112a5b5fbaf; p15-c-embedding-backlog.json 36352e8fcdf6287a41b66e386f045f81d88db864a7f07e0169c7a6efa5101ab8; collector-common.sh 34a5ce14036a774ac0f12738a876df9aadf3b7dd2ebfa969cbb9d69f28cb2dd5
- `git status --short` -> ` M experiments/collectors/c-embedding-backlog.sh`, ` M experiments/collectors/f-poison-replay.sh` (nothing else)
- `git diff --stat` -> 2 files changed, 93 insertions(+), 16 deletions(-)
- finished: after the last command above
- files changed after these runs: none
