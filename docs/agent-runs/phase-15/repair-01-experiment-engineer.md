STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0, recomputed, matches), session 2 step 9, repair-01-experiment-engineer: J-01 (review-report-b.md) and M-01 (review-report-c.md), one pass; minors J-02..J-05, M-02, M-05, M-07, M-08 deliberately not touched.
FILES CREATED: none
FILES MODIFIED:
- experiments/collectors/f-poison-replay.sh (J-01, M-01, stale FLAGGED comment)
- experiments/collectors/c-embedding-backlog.sh (M-01)
- experiments/collectors/lib/collector-common.sh (M-01 helpers replace the S3 Vectors metadata check; stale FLAGGED comment)
- experiments/manifests/p15-c-embedding-backlog.json (description, notes: M-01, cost and duration lines)
- experiments/manifests/p15-f-poison-replay.json (description, notes: J-01, M-01, stale "flagged for 6c")
- experiments/README.md (collector contract: two bullets, M-01 and J-01)
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-15/prompt.md -> 3d806922...dfa3e0
- read-only grep/ls/wc/cat over the repo and scratchpad reports; git status --short (territory)
- shellcheck 0.11.0 (-x -P repo root) on c-embedding-backlog.sh, f-poison-replay.sh, lib/collector-common.sh -> rc 0; on ab-pipeline.sh, d-load.sh, e-retrieval-modes.sh (they source the changed lib) -> rc 0
- jq empty on both changed manifests -> rc 0; jq keys (top-level keys unchanged, 14 schema keys); jq action.args (unchanged)
- shasum -a 256 on all eight p15 manifests (list below)
- aws-api (read-only profile), 3 of 5 allowed calls: dynamodb list-tables (table names); dynamodb describe-table on the artifact-registry table (key schema pk HASH S, sk RANGE S; PAY_PER_REQUEST; no GSI; about 251,000 items, about 205.5 MB); one dynamodb scan --max-items 2 projected to sk/artifactVersion/embeddingDimensions -> AccessDeniedException (the read-only profile's ViewOnlyAccess has no dynamodb:Scan). No item data was read; the item shape is taken from the storage code (TableSpecs.scala:165-192, VectorIngestPipeline.scala:587-602, LedgerAndRegistry.scala:104-124). No identifier copied.
- RULE DEVIATION (reported, not hidden): four early read-only Bash calls began with `cd /Users/brian/Developer/repos/scholarchain &&` (grep/ls/shellcheck only), against the work order's "No cd". No write resulted; all later calls used absolute paths.
- Not run (as ordered): sbt, any script, bash, python3, curl, aws CLI.
TEST RESULTS: no sbt tests (no Scala source changed; experiments/** is not stop-hook relevant source). Collectors are runtime-untested by design; first executable tests are D0a (bash -n, jq empty) and D9a (--preflight of C and F, which now exercise the registry scan and query).
VALIDATION RESULTS: shellcheck rc 0 (six collector files); jq empty rc 0 (two changed manifests); no remaining s3vectors/list-vectors/get-vectors/VECTOR_BUCKET/cc_metadata_check/FLAGGED in experiments/collectors (grep; only two explanatory comments name s3vectors).
ASSUMPTIONS:
- The human's admin profile (which runs the collectors) has dynamodb:Scan and dynamodb:Query on the ArtifactRegistry table and the table has no resource policy (orchestrator checked: only vectors/main.tf has a Deny). The read-only MCP profile lacks Scan, so the live item shape was not observed; D9a's preflights are the first live check.
- Registry items carry pk `artifact#vector#INDEX/VECTOR_KEY`, sk `v#NNNNNNNN`, attributes textHash (S), embeddingModel (S), embeddingDimensions (N) (code, as cited). The work order's "dimensions" is the attribute `embeddingDimensions`.
- The latest registry version's textHash equals the vector's stored metadata textHash (ingest registers after PutVectors with the same metadata), so F's completion-record key is right.
- AWS CLI v2 `dynamodb scan` aggregates ScannedCount across pages under --max-items and returns NextToken for --starting-token (standard paginator behavior; unverified here).
RISKS:
- Scan order is partition order: repeatable for unchanged contents, not guaranteed after repartitioning; mitigated by recording selected-keys.txt and keys_sha256.
- A registry key whose vector is absent or whose vector metadata lacks a handler field gives a permanent failure (workload failure), visible in worker-outcomes.csv; the 20-key check no longer reads vector metadata.
- One scan CLI call can run up to 120 s x 3 tries beyond the 420 s between-call bound; expected selection time 1 to 3 minutes (estimate). C's --preflight must finish inside the runner's 600 s --check bound.
- D17 AwsAdaptersAwsSuite (admin profile) hits the same vector bucket Deny (orchestrator's recorded risk; outside this repair).
MANUAL ACTION REQUIRED: none new. The executable bits stay the human's step (decision 16). Both changed manifests have new sha256 values (below); none was signed.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 10 re-check of f-poison-replay.sh, c-embedding-backlog.sh, lib/collector-common.sh and the two manifests (code-reviewer); then D0a/D9a in session 3.

## Finding J-01 (major): part 2 deadline measured from REPLAY_SENT_AT

Change: experiments/collectors/f-poison-replay.sh:435-459 `wait_outcome`.
- :436-439: `start = max(SENT_AT, now)`; `deadline = start + P2_LIMIT`. The limit now counts from when this wait starts, not from the replay send.
- :441-458: do-while loop. The log is read (`log_outcomes`, start time SENT_AT - 60 s) and checked before the deadline test (`[ now -lt deadline ] || break` at :456), so at least one read always happens.
- :440: a `wait-start-processed` / `wait-start-replayed` row in events.csv with the limit and deadline (raw evidence of when each wait started). failure_timeline.py treats it as an ordinary collector event; the replay verdict uses only worker-log and completion-record sources (failure_timeline.py:64-71), so it is unaffected.
- :480, :484 (`part_replay`): the not-seen texts now say "within 720 s of the start of its wait".
- :201 (limits basis) and :605-607 (preflight lines) state the rule; usage text :101-110.
Why it fixes it: in the reviewer's case the first wait now starts after part 1, gets its full 720 s, and its first read covers the whole period since the replay send, so a "processed" line written during part 1 is found.
Documented trace (comment at :420-434, J-01 note from :422): P1_LIMIT 960, P2_LIMIT 720; poison at T, replay at T+1; worker logs "processed" at T+400; part 1 ends at T+960. Before: deadline T+721 < T+960, zero reads, "processed not seen", duplicate never sent. After: wait start T+960, deadline T+1680, first read (from T-59) returns the T+400 line, return 0 at about T+961, duplicate sent, "replayed" gets its own 720 s from its own wait start.
Deadline check: worst case 960 + 720 + 720 + about 110 (part 3) + 600 + 900 = about 4,010 s < tool deadline 4,200 s < maxDurationSeconds 4,800.

## Finding M-01 (major, interface: yes): S3 Vectors data plane denied to the laptop profile

Orchestrator decision applied: no security change; no collector calls s3vectors.
- lib/collector-common.sh:669-695 (rule comment), :696-699 constants (page size 1000, at most 1000 matches per CLI call, 80 calls, 420 s between calls).
- lib :701-755 (function at :711) `cc_registry_select TABLE INDEX COUNT KEYS_FILE`: read-only, eventually consistent `dynamodb scan` with FilterExpression `begins_with(pk, :p)` (:p = `artifact#vector#INDEX/`), `--projection-expression pk`, `--page-size 1000`, `--max-items min(COUNT,1000)`, continued with `--starting-token`; pks deduped keeping the first (awk `!seen[$0]++`, scan order kept, no head in a pipe); first COUNT unique keys, prefix stripped; returns 0 (exactly COUNT), 3 (fewer: table end or bound), or the AWS 5/7. Records calls, ScannedCount sum, matched and unique counts.
- lib :757-788 (function at :768) `cc_registry_latest TABLE INDEX KEY MODEL DIMS`: one `dynamodb query` `pk = :pk`, `--no-scan-index-forward --page-size 1 --max-items 1`, projection `sk, textHash, embeddingModel, embeddingDimensions` (AR1); sets CC_REG_LINE "ELIGIBLE MISSING MODEL_CHECK" and CC_REG_TEXT_HASH (memory only).
- Removed: CC_REQUIRED_METADATA and cc_metadata_check (no other user; grep).
- c-embedding-backlog.sh: table from Terraform output `dynamodb_table_names.ArtifactRegistry` (:158) instead of vector_bucket_name; `select_keys` :269-296 (exactly N unique or exit 3, nothing sent; scan counts in counts JSON); `check_sample` :298-343 (20 evenly spaced keys, latest registry version each; sample-check.csv same columns; any miss exit 3, nothing sent; selection JSON records source, rule, keys_sha256, scan counts); usage text :77-87, :96-97, :105-107. LIST_PAGE_SIZE removed. Preflight path unchanged in shape (select_keys + check_sample + preflight, main :524-528).
- f-poison-replay.sh: header note :28-32; table output :171; `select_valid_key` :212-243 (COUNT 1, latest version check, exit 3 on a miss, VALID_TEXT_HASH from the registry version for the completion record); preflight prints the selection and latest-version line :605-606; usage :101-110, :118-120.
- Manifests: C description and notes (KEY SELECTION and CHECK paragraphs naming M-01, duration line, scan cost estimate); F description and notes (KEY SELECTION paragraph naming M-01, cost line).
- README: experiments/README.md:136 (M-01 bullet), :137 (J-01 bullet).
- Read-capacity estimate (labelled estimate, in the C notes): upper bound one full-table scan per selection, about 205.5 MB / 4 KB = about 50,200 units, halved for eventual consistency = about 25,100 read request units, at $0.125 per million (COSTS.md) about $0.003; with the D9a preflight about $0.006; plus 20 one-item queries. F: one call, typically about 100 or more read request units, under $0.001.
- Stale "FLAGGED FOR 6c" comments removed: lib :522-529, f-poison-replay.sh :20-26; F manifest's "flagged for 6c" limitation replaced.

## Compatibility matrix lines that change (6b report table)

| Path | Implemented at | First executable test |
|---|---|---|
| runner `--check` -> collector `--preflight` | each tool's preflight now f :588, c :498; C and F preflights also run the registry selection and checks (c main :524-528, f main select_valid_key before preflight) | D9a |
| collector -> vector key source (NEW row, M-01) | lib cc_registry_select :711-755, cc_registry_latest :768-788; c select_keys :276, check_sample :306; f select_valid_key :219; Terraform output dynamodb_table_names.ArtifactRegistry (c :158, f :171) | D9a (preflights of C and F), then D10 (F) and the C run |
| runner -> collector arguments | parse_args now f :132, c :113 (arguments unchanged) | D9a, D10 |
| F part 2 waits (NEW row, J-01) | f wait_outcome :435-459, part_replay :461 | D10 (F live run) |

## Manifests (all eight, full sha256 after this repair)

- p15-a-hadoop.json 1254a11cd8431bfdf41b0549edca9f62adf622a5c13858ee7e469be4fa0488d8 (unchanged)
- p15-a-spark.json 1875db33a1f57632e9fcec579dcce3f1c717139ac1cba13c685ed654588f9531 (unchanged)
- p15-b-spark-burst.json 1b869c94c0370810d45810cfb1b2f7e91f272f94415b563d8c990b54d8f17c87 (unchanged)
- p15-c-embedding-backlog.json 36352e8fcdf6287a41b66e386f045f81d88db864a7f07e0169c7a6efa5101ab8 (CHANGED; was 5dbe72f31cba671b4ae20613aff76bb2463d36edbc55c9e510af6152b12e666d per 6c)
- p15-d-query-load.json 2af43f86a39c31b00cb5c95810265e4d417be5522cef2bbdba0afaff33dc480b (unchanged)
- p15-d-read-load.json d851716dfbcd909f96a4b5e5be67d88a85206de67f9e74478b020825c7ce3070 (unchanged)
- p15-e-retrieval-modes.json be3868dff8802e0cf2a2b60c5838444a3ec4bafb9cf7e9f15c2cab30ef1b8019 (unchanged)
- p15-f-poison-replay.json a39c7e453f3c9264092fc22640e2c56465e06ff947bf031291ee9112a5b5fbaf (CHANGED; was f079543cccde90c84f3581a47decf546b04d804bb1b8275b97ae497d9e999053 per 6c)

Manifest IDs, run IDs, raw-data locations, scale evidence, failures: none (no run). Cost of this repair: three read-only AWS API calls (no charge beyond request noise).

## INTERFACE HANDOFF

- Files changed: as under FILES MODIFIED.
- New lib functions (sourced by C and F only): `cc_registry_select TABLE INDEX COUNT KEYS_FILE` -> rc 0/3/5/7, globals CC_REGISTRY_CALLS, CC_REGISTRY_SCANNED, CC_REGISTRY_MATCHED, CC_REGISTRY_UNIQUE; `cc_registry_latest TABLE INDEX KEY MODEL DIMS` -> rc 0/5/7, globals CC_REG_LINE, CC_REG_TEXT_HASH (call directly, not in $(...)). Removed: `cc_metadata_check`, `CC_REQUIRED_METADATA`.
- Terraform output read (new for collectors): `dynamodb_table_names.ArtifactRegistry`; `vector_bucket_name` is no longer read by C or F.
- AWS permissions the running profile needs (new): dynamodb:Scan and dynamodb:Query on the ArtifactRegistry table; no s3vectors permission is needed any more.
- Raw files: sample-check.csv keeps its columns (ordinal,eligible,missing_fields,model_check; MISSING may be "no-version"); events.csv (F) gains rows with event `wait-start-processed`/`wait-start-replayed`, outcome `waiting`, code `limit=720s;deadline=EPOCH`; tool-record selection JSON gains `source`, `scan` (C) / scan counts (F); counts JSON (C) gains registry_scan_calls, registry_items_scanned, registry_items_matched.
- Arguments and exit codes: unchanged.
- Fixtures: none changed (derive scripts unaffected; the trace for J-01 is documented in the script comment and above).
- Thread-safety: selection and checks run in the main process before the background sampler starts; temporary files under CC_TMP only.
- Limits: scan 80 calls, 420 s between calls, page 1000, 1000 matches per call; per call 120 s x 3 tries (existing).

## Proposed later (not built)

- Add AWS/DynamoDB ConsumedReadCapacityUnits of the artifact-registry table to C's metrics to measure the scan's real read cost (manifest change, new approval).
- Minors J-02..J-05, M-02, M-05, M-07, M-08 remain open as triaged.

## VALIDATION EVIDENCE

- shasum -a 256 docs/agent-runs/phase-15/prompt.md -> 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0
- shellcheck -x -P REPO c-embedding-backlog.sh f-poison-replay.sh lib/collector-common.sh -> "shellcheck rc=0" (no findings)
- shellcheck -x -P REPO ab-pipeline.sh d-load.sh e-retrieval-modes.sh -> "shellcheck others rc=0"
- jq empty p15-c-embedding-backlog.json -> rc=0; jq empty p15-f-poison-replay.json -> rc=0
- jq keys: both manifests have exactly the 14 schema keys; action.args unchanged ("--count 19200 --wait-limit-seconds 5500 --index researcher-profile-v1"; "--index researcher-profile-v1 --question-id q05")
- grep for s3vectors|list-vectors|get-vectors|VECTOR_BUCKET|cc_metadata_check|FLAGGED|LIST_PAGE in experiments/collectors -> only two explanatory comments (lib :674, f :31)
- shasum -a 256 experiments/manifests/p15-*.json -> the eight values above
- Note: lib/collector-common.sh (the `per` cap and its comment) was edited after a first shellcheck run (rc 0) and before the shellcheck runs listed above; no repository file changed after the shellcheck, jq and shasum runs listed above (the only later writes were to this report).
files changed after these runs: none
