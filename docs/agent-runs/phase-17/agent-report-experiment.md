STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: Phase 17 session 2, step X1 (tier 2, decision 9.4); docs/agent-runs/phase-17/prompt.md sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (verified with shasum); HEAD 50ba087ecd33178eaa8ccc69286b9de85e8a8146 (verified); routing input phase17-reports/routing-p17.md rows L-06 (experiments part), K-03, L-03.
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/experiments/README.md (line 142 only)
- /Users/brian/Developer/repos/scholarchain/experiments/collectors/lib/collector-common.sh (comment lines 692-696 and a trailing comment on line 701; no code change)
- /Users/brian/Developer/repos/scholarchain/experiments/results-summary-template.md (line 85 only)
COMMANDS RUN: shasum -a 256 (work order); git rev-parse HEAD; git status --short; git diff / git diff --stat / git diff -U0 (read-only); grep, sed -n (read-only); shellcheck --version; shellcheck /Users/brian/Developer/repos/scholarchain/experiments/collectors/lib/collector-common.sh. No script run, no sbt, no AWS, no aws-api MCP.
TEST RESULTS: not applicable (no Scala or behaviour change; sbt not run by rule).
VALIDATION RESULTS: shellcheck 0.11.0 on collector-common.sh: no output, exit 0. git diff of collector-common.sh: only comment text changed (the readonly assignment value 420 is unchanged; the trailing "# ..." comment follows whitespace, so the shell reads it as a comment).
ASSUMPTIONS:
- The review's line numbers (README :52 and :136) are from the Phase 15 tree; at HEAD both the "420 s between calls" wording (K-03) and the "against config/course.json" wording (L-06) are on experiments/README.md:142 (the single "Vector keys for C and F" bullet). README:52 at HEAD is runner refusal item 5, unrelated. I edited line 142.
- L-03 facts checked read-only: infra/terraform/modules/workflow/sfn.tf:218-229 (max = min gives {} , no ManagedScalingPolicy, repair L-01); infra/terraform/environments/course/variables.tf:110-126 (core count 2, min 2, max 4); experiments/collectors/ab-pipeline.sh:327, :343, :360 (limits default "null", recorded as managedScalingComputeLimits).
- L-06 facts checked: c-embedding-backlog.sh:46-47 and f-poison-replay.sh:51-52 hold the copied constants (the review cited c:46-47, f:51-52); collector-common.sh:771-787 checks presence of textHash and equality of embeddingModel and embeddingDimensions only, so the README now says that exactly.
- K-03 worst case: CC_CALL_TIMEOUT 120 (collector-common.sh:37) x CC_MAX_TRIES 3 (:39) plus backoff, about 790 s, as review-report-02.md:70 computed.
RISKS:
- Low: experiments/results-summary.md:3 says it was written from the template; the template's new line 85 wording for A no longer matches the summary's own scaling-table row if that row repeats the old wording. results-summary.md is a Phase 15 result summary and was not in scope; not changed.
- Status lines unchanged by rule: K-03 manifest part (p15-c-embedding-backlog.json:46 "420 s between calls"), L-03 manifest part (p15-a-hadoop.json:7, :64; p15-a-spark.json:7 "managed-scaling maximum 2"), M-07 (p15-c-embedding-backlog.json:46). Signed manifests not touched.
MANUAL ACTION REQUIRED: none. (Untracked experiments/**/.DS_Store files exist, pre-existing; D-01 belongs to build-engineer.)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator re-runs shellcheck on collector-common.sh; code-reviewer / documentation review of the three wording changes (F-2 / R-D slices as the orchestrator assigns).

## Per-ID changes

### L-06 (experiments part) - done
Source: docs/agent-runs/phase-15/review-report-L-01-02.md:118. File: experiments/README.md:142 (review cited :136).
Before: "`cc_registry_latest` reads a key's latest version (Query, `ScanIndexForward=false`, Limit 1) and checks `textHash`, `embeddingModel` and `embeddingDimensions` against `config/course.json`: C checks 20 evenly spaced keys ..."
After: "`cc_registry_latest` reads a key's latest version (Query, `ScanIndexForward=false`, Limit 1) and checks that `textHash`, `embeddingModel` and `embeddingDimensions` are present and that `embeddingModel` and `embeddingDimensions` equal the expected values; the expected `embeddingDimensions` comes from `config/course.json`, and the expected `embeddingModel` is built from `config/course.json` (provider, model, dimensions) plus two rag code constants copied into both collectors, `EMB_INPUT_TYPE="document"` and `EMB_SPEC_VERSION="emb-v1"` (`c-embedding-backlog.sh:46-47`, `f-poison-replay.sh:51-52`), and the copied `EMB_SPEC_VERSION` must change when rag bumps `EmbeddingModelSpec.SpecVersion`: C checks 20 evenly spaced keys ..."

### K-03 (comment and README) - done; manifest part stays a status line
Source: docs/agent-runs/phase-15/review-report-02.md:70.
1. experiments/collectors/lib/collector-common.sh:692-693 (now :692-696)
Before:
  # Bounds: CC_REGISTRY_SCAN_MAX_CALLS calls and CC_REGISTRY_SCAN_LIMIT_SECONDS
  # between calls (one call can add up to CC_CALL_TIMEOUT x CC_MAX_TRIES), and
  # the table's end. ...
After:
  # Bounds: CC_REGISTRY_SCAN_MAX_CALLS calls; CC_REGISTRY_SCAN_LIMIT_SECONDS, a
  # total window measured from the start of the selection (not a gap between
  # calls), after which no new call starts, so a call started inside the window
  # can run past it by up to CC_CALL_TIMEOUT x CC_MAX_TRIES plus backoff; and
  # the table's end. ...
2. experiments/collectors/lib/collector-common.sh:699 (now :701)
Before: readonly CC_REGISTRY_SCAN_LIMIT_SECONDS=420
After:  readonly CC_REGISTRY_SCAN_LIMIT_SECONDS=420 # total selection window, not a per-call gap
3. experiments/README.md:142 (review cited :52)
Before: "... 1,000 items evaluated per request, at most 80 CLI calls and 420 s between calls; duplicate versions ..."
After: "... 1,000 items evaluated per request, at most 80 CLI calls and a total window of 420 s from the start of the selection (not a gap between calls: no new call starts after it, and a call already started can run past it by up to 3 x 120 s plus backoff, so the worst case is about 790 s); duplicate versions ..."

### L-03 (template only) - done; manifest parts stay status lines
Source: docs/agent-runs/phase-15/review-report-L-01-02.md:115. File: experiments/results-summary-template.md:85 (EMR row, "This project's rule" cell).
Before: "managed scaling, min FILL, max 2 (A) / 4 (B) through `cluster.managedScalingMaxUnits` (FILL)"
After: "A: no managed-scaling policy (`cluster.managedScalingMaxUnits` 2 = the minimum 2, so the request carries no `ManagedScalingPolicy`, repair L-01; a fixed 1 primary + 2 core cluster, flat capacity, `managedScalingComputeLimits: null` in the ab-pipeline record; FILL); B: managed scaling, min 2, max 4 core units through `cluster.managedScalingMaxUnits` (FILL)"

## Manifest IDs, run IDs, raw-data locations, scale evidence, failures, cost
None: no experiment run, no manifest change, no raw or derived file touched, no experiments/evaluation/** file touched. Cost: 0.

## INTERFACE HANDOFF
- Files changed: experiments/README.md:142; experiments/collectors/lib/collector-common.sh:692-696, :701 (comments only); experiments/results-summary-template.md:85.
- Public signatures: unchanged (no function, variable, option, exit code or output changed; CC_REGISTRY_SCAN_LIMIT_SECONDS still 420).
- Fixtures: none. Thread-safety: not applicable. Limits: wording only; signed manifests keep the old wording (status lines K-03, L-03, M-07).

## VALIDATION EVIDENCE
- shellcheck --version: "version: 0.11.0"
- shellcheck /Users/brian/Developer/repos/scholarchain/experiments/collectors/lib/collector-common.sh: no findings, exit=0 (run after the last edit to that file)
- git diff --stat: experiments/README.md 2 +-; experiments/collectors/lib/collector-common.sh 8 +++++---; experiments/results-summary-template.md 2 +- (3 files, 7 insertions, 5 deletions); git diff -U0 hunks: README @@ -142 +142, template @@ -85 +85
- git status --short (repository files of this step): M experiments/README.md, M experiments/collectors/lib/collector-common.sh, M experiments/results-summary-template.md; nothing under experiments/manifests/**, experiments/raw/**, experiments/derived/**, experiments/evaluation/** changed
files changed after these runs: none
