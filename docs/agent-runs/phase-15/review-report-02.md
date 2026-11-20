# review-report-02.md (Phase 15, session 2, step 10 re-check)

(Saved verbatim by the orchestrator from code-reviewer's final message.)

Work order: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), decision 12. I did not reopen the orchestrator's decisions: H-01 fix (a), the same environment on all four service roles, and M-01, where keys come from DynamoDB ArtifactRegistry and there is no security change.

**VERDICT: APPROVE.** All three repaired findings are RESOLVED. The round left no critical or major finding. I found three new minor ones (K-01 to K-03), none of them blocking.

## Per-finding status

### H-01 (major, interface) — RESOLVED
- **One map for every role.** `infra/terraform/modules/runtime/main.tf:67-70` puts `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL = var.embedding_queue_url` in the shared `service_environment`, next to the job queue URL. `:178` builds every service task definition's `environment` from `local.service_environment`, with no `each.key` lookup. A grep finds no `role_environment` and no per-role environment structure left in the module.
- **Nothing else varies by role in a way that reaches the config.** The secrets list (`:76-81`, `:179-182`) is the same for every role. Per role, only `--role` (`:171`), CPU/memory and the task role change, and none of these enters `ScholarChainConfig`.
- **Wiring.** The course environment passes `embedding_queue_url = module.workflow.queue_urls["embedding"]` (`environments/course/main.tf:183`). The ops task keeps `config_environment` (`:240`), which is unchanged and outside the decision.
- **The URL on non-worker roles is inert.** `Main.scala:233-239` builds embedding work only for `--stores aws` with role worker or all. `ServiceServer.scala:177-181` and `:240`/`:248` gate the handler and both loops on `workerRole`. The IAM grant `EmbeddingQueueConsume` stays worker-only (`iam.tf:227`).
- **Tests.**
  - Test 1 (`RoleConfigParitySuite.scala:128-141`): three separate compositions, each loading its config through `Main.loadConfig` with the same env. It asserts ack id == job id == result id, and that explain on the ack id returns 200.
  - Test 3 (`:143-164`): uses the Phase 14 split (URL on the worker only). It asserts the two configs differ only in that key, that their fingerprints differ, that the result id differs from the ack id while matching the worker's own run ID, and that explain on the ack id returns 404. Test 1 asserts the opposite of these results, so under the split test 1 would fail. That makes test 1 a real check.
  - Isolation: the harness defaults to `startWorker = false` (`ServiceTestKit.scala:152`), so only the worker composition consumes jobs, and the result is not racy.
  - `CompositionSuite.scala:320-407`: with the URL set, roles query, evidence and graph make 0 queue calls and 0 handler builds. A live control on the worker role shows the counters work.
- **Remaining gap:** K-01.

### J-01 (major) — RESOLVED
- **Deadline.** `f-poison-replay.sh:437-439`: `start = max(sent, now)`, `deadline = start + P2_LIMIT`.
- **Read at least once.** `:441-457` is a do-while: `log_outcomes` (`:442`) and the checks run before the deadline test at `:455`.
- **Log window.** The read starts at `sent - 60` (`:442`), so a "processed" line written while part 1 was running is found on the first read.
- **Second wait.** It gets its own window from `sent2` (`:472`, `:476`).
- **Events.** The `wait-start-*` events (`:440`) carry no commas, and `failure_timeline.py:63-71` bases its verdict only on proof sources, so the new rows cannot change it.
- **Deadlines.** The worst-case path is part 1 at 960 s, two waits of 720 s each, part 3 at about 110 s and recovery at 600 s. The DLQ-alarm loop's deadline (`DLQ_ARRIVED_AT + 900`) has normally passed by then, so it costs about one check. That totals about 3,110 s plus per-iteration overshoot (at most about 150 s per wait). The notes' 4,010 s is conservative. Both are under `TOOL_DEADLINE_SECONDS` 4,200 (`:55`, warn-only at `:667`) and under `maxDurationSeconds` 4,800 (F manifest `:11`). The sampler is bounded at 4,200 (`:646`).

### M-01 (major, interface) — RESOLVED
- **No s3vectors call remains in C or F, in the run or the preflight.** A grep of `experiments/` finds only two comments (`f-poison-replay.sh:31`, `collector-common.sh:674`) and manifest notes. `vector_bucket_name`, `cc_metadata_check` and `CC_REQUIRED_METADATA` are gone.
- **Scan** (`collector-common.sh:711-755`):
  - Read-only, eventually consistent Scan (no `--consistent-read`).
  - FilterExpression `begins_with(pk, :p)` with `:p = artifact#vector#INDEX/`. The trailing `/` prevents prefix collisions between index names.
  - Projects `pk` only; page size 1000; at most min(N, 1000) matches per CLI call (`:714-715`).
  - Bounded at 80 calls and a 420 s window before a new call may start (`:725`). One call can add up to 120 s × 3 tries.
  - Dedupes pk keeping the first (`:741`, `:747-750`; awk instead of head, so no SIGPIPE) and takes exactly N.
  - Returns 3 if fewer than N keys are found (`:752-753`).
  - Callers exit 3 before any send: C at `:287-293` (selection after settle and baseline, before `send_all` at `:546`); F at `:223-229`, `:232-235` (before baseline and the sends at `:650-652`).
- **Latest-version query** (`:768-788`): Query `pk = :pk`, `--no-scan-index-forward`, Limit 1, projecting `sk, textHash, embeddingModel, embeddingDimensions`.
- **Table name.** It comes from `.dynamodb_table_names.value.ArtifactRegistry` (C `:158`, F `:171`; output at `environments/course/outputs.tf:136`). It is held in a variable and never printed: `cc_info` says "not shown", `cc_die` texts and the selection JSON omit it, and AWS stderr is reduced to the error code (`:345-348`).
- **Field names match storage.**
  - Key: `pk = artifact#vector#INDEX/KEY` (`TableSpecs.scala:167`, `:270`; `VectorIngestPipeline.scala:674`, `:679`) and `sk = v#NNNNNNNN` (`TableSpecs.scala:271`).
  - Attributes: `textHash` (S), `embeddingModel` (S), `embeddingDimensions` (N) (`VectorIngestPipeline.scala:597-600`; `LedgerAndRegistry.scala:114-127`, with `Item.Pk`/`Sk` = "pk"/"sk" at `KeyValueStore.scala:45-46`).
  - No resource policy on DynamoDB and no customer-managed KMS key (no `server_side_encryption`/`kms_key` in modules), so the admin profile only needs `dynamodb:Scan`/`Query`.
- **C's sample check is correct.**
  - Model and dimensions come from `config/course.json` `.models.embeddingModelId` ("voyage-4", `:16`) and `.embedding.dimensions` (1024, `:63`). The ingest stores `embedder.modelId`, which is `models.embeddingModelId` (`EmbeddingModelSpec.scala:110-117`).
  - The comparison is string-to-string (`.N | tostring == $dims`).
  - 20 keys spaced every N/20 (`c-embedding-backlog.sh:309-316`); any miss exits 3 before sending (`:338-342`).
- **F's completion-record textHash is correct.** `VALID_TEXT_HASH` is the latest registry version's `textHash` (`f-poison-replay.sh:236`). That equals the vector metadata `textHash` the handler keys the record on (`VectorReembedder.scala:353`, `:417-418`), given the ingest invariant. Nothing in `modules/` deletes vectors (no DeleteVectors call path), so registry keys do not go stale that way. The canonical encoding at `:407` matches `Hashing.scala:38-44` for ASCII values.
- **Bash 3.2:** checked, no problems. Constructs used: `local args=(…)`, `args+=()`, `printf -v`, process substitution, `[[ =~ ]]` with the regex in a variable. No associative arrays, mapfile or `${,,}`. `"${args[@]}"` is never empty.
- **No identifiers recorded.** The selection JSON and counts hold counts, rule text, `keys_sha256` and the index name only.
- **Manifests.** Both have exactly the 13 required keys plus `notes`. Other checks:
  - consts are correct;
  - `costBoundUsd` is 0.5 for C and 0.1 for F, both within (0, 5];
  - `maxDurationSeconds` 7,700 and 4,800 are integers;
  - the command paths match the schema pattern;
  - args are strings with no `{…}`;
  - metric items have the required shape, with period 60.

  This satisfies `manifest-schema-v1.json` and `run-experiment.sh:446-450`. C's 7,700 s = settle 900 + selection about 500 + wait 5,500 + 360 + margin 440.

## New findings

| ID | Severity | Interface | Owner | file:line | Evidence | Failure scenario |
|---|---|---|---|---|---|---|
| K-01 | minor | no | infra-engineer (a check in plan review or a script); orchestrator decides if wanted | `infra/terraform/modules/runtime/main.tf:56-70`, `:178`; `RoleConfigParitySuite.scala:128-164` | The H-01 invariant ("every service role gets the identical `SCHOLARCHAIN_CFG_*` environment") is now enforced only by structure and a comment. The sbt tests model the deployment in-process and cannot read Terraform, as the service report says. | A later Terraform edit that adds a per-role entry (for example a merge keyed on `each.key`) passes `fmt`/`validate` and all 2,195 + 399 tests, and brings back the H-01 queryId split. The D3 plan review is the only catch. Suggestion: add "all four service task definitions have identical `environment`" to the plan-review checklist or a script check. |
| K-02 | minor | no | infra-engineer | `infra/terraform/modules/runtime/main.tf:183-186` | The comment says `close()` "shuts its four pools down one after another … at most about 4 x 26 s + 2 s = 106 s". `ServiceServer.scala:105-114` now shuts all pools (five on the worker) down together, with overlapping waits (embedding 60 s, others 25 s) plus a 2 s join. This is pre-existing drift (H-05 era), in a file this round edited. | The stopTimeout rationale in the signed plan documentation is wrong. The real bound (about 62 s) is lower, so there is no runtime harm, but a reader sizing `stop_timeout_seconds` uses the wrong model. |
| K-03 | minor | no | experiment-engineer | `experiments/collectors/lib/collector-common.sh:692-693`, `:699`; `experiments/README.md:52`; `p15-c-embedding-backlog.json:46` (notes, "at most 80 calls and 420 s between calls") | `CC_REGISTRY_SCAN_LIMIT_SECONDS` is a total window, measured from the start of the selection, after which no new call starts (`:724-725`). It is not a gap between calls. | The text the human signs at D9 reads as a 420 s pause between scan calls, or as a per-gap bound. Real worst case: about 420 s plus one call (up to 3 × 120 s plus backoff), about 790 s. That is more than the runner's 600 s `--check` bound (`run-experiment.sh:30`), so a pathologically slow scan in C's preflight would end as runner exit 7, not collector exit 3. Expected scan time is 1-3 minutes, so this is wording plus a low-probability edge. Fix the wording if the manifest is edited again before signing. |

## Files read
- In full: `f-poison-replay.sh`, `c-embedding-backlog.sh`, `p15-c-embedding-backlog.json`, `p15-f-poison-replay.json`, `manifest-schema-v1.json`, `RoleConfigParitySuite.scala`, `diff-R-02.txt`, and the three repair reports.
- In part: `collector-common.sh` (`:300-420`, `:640-810`: `cc_tf*`, `cc_aws_raw`/`read`, `cc_registry_select`, `cc_registry_latest`, the start of the tool record); `runtime/main.tf` `:20-219`; `CompositionSuite.scala` `:300-414`; `ServiceServer.scala` `:99-139`, `:170-259`; `Main.scala` (embedding gate); `TableSpecs.scala` `:155-199`; `VectorIngestPipeline.scala` `:570-619`; `LedgerAndRegistry.scala` `:95-129`; `Hashing.scala` `:38-44`; `config/course.json` (models, embedding); `run-experiment.sh` (duration/cost checks, `PREFLIGHT_TIMEOUT`); `failure_timeline.py` (grep); finding rows of review-report-a/b/c.
- Not read: the earlier-round parts of the `experiments/README.md` diff (lines 20-51, unchanged by this round apart from the bullets at `:52-53`, which I checked); the rest of `collector-common.sh` (sampler and clock helpers, not changed by this round per the repair report); the Terraform plan files.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), session 2 step 10 re-check, decision 12
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not run (reviewer). Relied on the wave closer's full sbt check (2195 module, 399 contract, 0 failed, 14:36) and packageCheck ok as reported in repair-01-service-engineer.md; not re-verified
VALIDATION RESULTS: H-01, J-01 and M-01 each checked against the files at the file:line cited; all RESOLVED. Manifests checked against manifest-schema-v1.json and run-experiment.sh:446-450 by reading (jq and shellcheck results are the producer's claims)
ASSUMPTIONS: the working tree matches diff-R-02.txt and the repair reports; AWS CLI v2 scan pagination (--max-items with --starting-token, ScannedCount aggregated) behaves as documented; the admin profile has dynamodb:Scan/Query on ArtifactRegistry; the registry textHash equals the vector metadata textHash (ingest invariant)
RISKS: first live execution of the registry selection is D9a; scan timing near the 600 s --check bound only in pathological cases (K-03); the H-01 invariant has no automated Terraform guard (K-01); D17 AwsAdaptersAwsSuite still meets the vector bucket Deny (recorded earlier, outside this round)
MANUAL ACTION REQUIRED: none from the reviewer; the human compares the D3 plan to infra's updated expected contents (all four service task definitions carry the embedding queue URL)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator records K-01..K-03 (minor) as open items or routes them; session 2 READY as far as this re-check is concerned
```

VERDICT: APPROVE
