STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 11 step 3d (docs/agent-runs/phase-11/prompt.md sha256 a0b59faeaeaae620ad87ce8b5dd1272efc1078d273e9a84baa426a9cd47e9ed1, decision 8 / 3d). Targeted tests only; not the wave closer.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/config/ConfigRefusalSuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/QueryAuditV2Suite.scala
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v2/QueryAudit.json (frozen v2 wire example)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/config/ConfigLoader.scala (no value echo; U-8; D-08)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/config/ScholarChainConfig.scala (doc of the service key rules only)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Records.scala (ModelCallCounts; QueryAudit v2 fields and version-aware codec)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Validator.scala (QueryAudit v2 rules; semantic_context carries no metric)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/SchemaVersions.scala (QueryAudit -> 2; stillReadable QueryAudit 1)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/config/ConfigLoaderSuite.scala, ServiceConfigSuite.scala (expected messages updated to the decided no-echo text)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/Fixtures.scala (queryAuditV2), WireContractSuite.scala (QueryAudit example is v2), SchemaVersionsSuite.scala (table + supported-set test), RuntimeContractSuite.scala (semantic_context metric test)
COMMANDS RUN:
- read-only: grep, sed -n, ls, git status (via context), head/tail -n
- sbt -batch "core/testOnly <7 suites>" "core2_13/testOnly <7 suites>" (first pass, log scratchpad/step3d-core-first.log): Scala 3 393/393 passed; core2_13 Test compile error in the new QueryAuditV2Suite (2.13 type inference of `fold(..., identity)`), fixed.
- the one verification invocation (TEST EVIDENCE below), log /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/858dada8-c8dd-43c7-b05d-961e323b855c/scratchpad/step3d-targeted.log
TEST RESULTS: core (Scala 3) 393 run / 393 passed; core2_13 393 / 393; storage RuntimeReadersSuite 9 / 9; rag/Test/compile success; services/Test/compile success; contractTests ConfigOmissionSuite 6 run, 3 passed, 3 "expected failure but test passed" (T-08, T-09, U-6 flips; expected). Orchestrator grep for failures/errors over core jvm-3, core jvm-2.13 and storage test reports: empty output.
VALIDATION RESULTS: every behavior change has a module test (below, per item). QueryAudit v1 frozen file unchanged and still decodes; v2 frozen file round-trips in both builds; existing positional QueryAudit constructors (storage test, rag QueryWorkflow) compile unchanged (rag/services Test/compile green, storage suite green).
ASSUMPTIONS:
- "Never the value" applies to string values and raw environment values. Refused numbers (ports, limits, thresholds, configVersion when numeric) are still quoted; they are not names or secrets.
- droppedBoundNumbers is included in QueryAudit v2 because decision 8 / 3f (L-1) makes rag count dropped bound numbers per attempt in this phase; until rag's step 3f lands, rag fills 0.
- cacheHits keys are rag's fixed vocabulary (like stageMillis keys); core checks only the name pattern.
RISKS:
- If rag's L-1 item is not done, droppedBoundNumbers would be 0 rather than "unknown".
- The WindowList decode error still appends the uPickle message (type and JSON position, not a value, in uPickle 4.4.3) and the "not valid JSON" file error still appends the ujson parser message (it can name one offending character). Recorded, not changed.
- The D-08 denylist is a fixed list (ConfigValidator.ReservedEnvVarNames); names outside it (for example other OS variables) are still accepted.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 3e storage-engineer (QueryAudit v2 round trip using the frozen example below), then 3f rag (populate v2), 3g services (explain); step 4 test-engineer removes the T-08, T-09, U-6 `.fail` marks in ConfigOmissionSuite; wave closer runs `sbt check`; code-reviewer re-checks in step 5.

## Per-item status and proof

| ID | Sev / prio | Status | Proof |
|---|---|---|---|
| T-08 | major | fixed | ConfigLoader `apiKeyEnvVarName` messages are `<field>: must match [A-Z][A-Z0-9_]*` / `must not start with SCHOLARCHAIN_CFG_` (no value); `service.readOnlyApiKeyEnvVar: must differ from service.apiKeyEnvVar` (no value). Module test ConfigRefusalSuite "T-08: a key pasted into service.apiKeyEnvVar is refused by field and rule only" (exact message) and the all-leaves test. Contract flip: ConfigOmissionSuite "T-08 ..." expected failure but passed. |
| U-6 | P1 | fixed | `service.jobQueueUrl: must be an https:// URL with a host`. Module test ConfigRefusalSuite "U-6 ..." (exact message); ServiceConfigSuite jobQueueUrl test updated to the value-free message. Contract flip: ConfigOmissionSuite "U-6 ..." expected failure but passed. |
| T-09 | minor (record-only, covered by the same root-cause fix) | fixed | Same fix: environment, aws.region, all bucket, prefix, table, endpoint, vector index, provider, window-label, bridge.algorithm and runtime.defaultWindowLabel messages no longer contain the value. ConfigRefusalSuite "T-08/T-09/U-6: no refused string value is repeated; the refusal names its key" walks every string leaf of a config with all sections (more than 40 paths), asserts no sentinel text and that the refusal names the key, and asserts 18 named fields are refused. Contract flip: ConfigOmissionSuite "T-09 ..." expected failure but passed. |
| D-03 | minor (covered) | fixed | Same fix plus: `SCHOLARCHAIN_CFG_*` non-JSON numeric value -> `<VAR>: is not a JSON literal` (line 361 echo removed); type mismatch -> `expected an integer, found a string` (JSON type, not the rendered value); non-numeric `configVersion` -> `unsupported value a string`; provider errors -> `must be one of: ...` (no longer the WireEnum message that quoted the value). ConfigRefusalSuite "D-03: ..." tests (two). |
| QueryAudit v2 | P1 | done | See section below; QueryAuditV2Suite (11 tests) and WireContractSuite (QueryAudit example now v2) in both builds. |
| D-06 | advisory | decided and tested | Decision: always write. The QueryAudit codec writes every v2 field, at its default as `[]` / `{}`, in declared order; a v2 record missing a v2 key is rejected on decode; a v1 record is written without the v2 keys (exact v1 bytes) and a v1 record carrying a v2 key is rejected. Tests: QueryAuditV2Suite "D-06: v2 fields at their defaults are written ..." and "D-06: a v2 record missing any v2 field ...", both builds. ScholarChainConfig keeps its documented omission (fingerprint stability), unchanged. |
| U-8 | P2 | done | New rule `serviceKeysDistinct`: `service.apiKeyEnvVar: must differ from llm.apiKeyEnvVar` / `... embedding.apiKeyEnvVar`, same for `service.readOnlyApiKeyEnvVar`; applies when those sections set a key name. ConfigRefusalSuite "U-8: ..." (file and environment paths; service-only config still loads). |
| D-08 | minor (attached to U-8, small) | done | `ConfigValidator.ReservedEnvVarNames` (HOME, HOSTNAME, LANG, LOGNAME, PATH, PWD, SHELL, TERM, TMPDIR, TZ, USER, JAVA_HOME, JAVA_TOOL_OPTIONS, AWS_REGION, AWS_DEFAULT_REGION, AWS_PROFILE, AWS_EXECUTION_ENV, AWS_CONTAINER_CREDENTIALS_RELATIVE_URI, ECS_CONTAINER_METADATA_URI, ECS_CONTAINER_METADATA_URI_V4, SCHOLARCHAIN_CONFIG_FILE) refused for all four API-key name fields: `<field>: must not name a well-known environment variable (see ConfigValidator.ReservedEnvVarNames)`. ConfigRefusalSuite "D-08: ...". |
| Phase 9 proposed-later item 1 | P2 | done | EvidenceItem rule: `metric: must be null for semantic_context evidence (semantic context never supports a number)`. rag already writes `metric = None` for semantic items (EvidenceBuilder.scala:386-393). RuntimeContractSuite "Phase 11: a semantic_context item carries no metric (on encode and on decode)". |
| Phase 9 proposed-later item 2 | not in this phase | recorded | Proposed later (see below). |
| D-04 | minor, record only | recorded | Not changed. |
| D-05 | advisory, record only | recorded | Not changed. |

How "fails without the fix" was checked:
- T-08, U-6, T-09: the step 1b run of ConfigOmissionSuite on the pre-fix code failed these tests with the value in the message (test-report.md lines 75-83); ConfigRefusalSuite asserts the same property over a superset of fields, so it fails on that code. The flips in this run prove the fix.
- D-03, U-8, D-08, semantic_context: checked by reading the pre-fix code, not by reverting: the old messages contained `'$raw'`, `${other.render()}` and the WireEnum text `unknown ... '<value>'` (the new tests assert they are absent); the U-8, D-08 and semantic_context rules did not exist, so the tests' expected messages could not appear.
- QueryAudit v2: the new fields, `ModelCallCounts`, `V2Fields` and the v2 frozen file did not exist; the old `macroRW` codec omits default fields (the D-06 test asserts their presence) and had `supported("QueryAudit") == Set(1)` (the frozen v2 example would be rejected as an unsupported version).

## Contract changes and schema versions

- QueryAudit 1 -> 2 (additive). `SchemaVersions.current("QueryAudit") == 2`; `SchemaVersions.stillReadable == Map("QueryAudit" -> Set(1))`; `supported("QueryAudit") == Set(1, 2)`; every other schema still supports only its current version. Not breaking: v1 records decode and re-encode to byte-identical v1 JSON; existing 21-argument constructors compile.
- EvidenceItem stays 1. The new rule (no metric on semantic_context) tightens a rule that was already documented (Records.scala EvidenceItem doc); no producer emits the rejected form.
- ScholarChainConfig stays 1. New semantic rules (U-8, D-08) reject configurations that were accepted before: a service key name equal to the llm or embedding key name, or a reserved name. No shipped config in the repository uses one (grep of every `apiKeyEnvVar` value in *.json).
- Configuration error texts changed (no values). Only core tests asserted the old texts; updated in ConfigLoaderSuite and ServiceConfigSuite (decided behavior change).

## QueryAudit v2 field list

Appended after `provenance`, in this order, each with a constructor default:

| Field | Type | Default | Rule | rag source (already in rag) |
|---|---|---|---|---|
| queryTemplateIds | List[String] | Nil | distinct, `[a-z0-9][a-z0-9_.-]*` (QueryAudit.NamePattern) | `GraphResult.templateId` of each graph query (facts' SourceRef `graph:<templateId>`, Stages.scala:447,465) |
| indexesQueried | List[String] | Nil | distinct, VectorIndexName.Pattern | `SemanticContext.query.indexName` (QueryInfo, SemanticContext.scala:60) |
| metadataFilterKeys | List[String] | Nil | distinct, non-empty, no whitespace | `QueryInfo.filterKeys` (keys only, never values) |
| modelCalls | Map[String, ModelCallCounts] | Map.empty | key NamePattern; all counts >= 0; invalidOutputs and truncatedOutputs <= logicalCalls | per task from `PlannerOutput.attempts` / `SynthesisOutput.attempts` (ModelAttempt outcome, stopReason) and `fallbackReason`; droppedBoundNumbers from rag's L-1 counting (step 3f) |
| cacheHits | Map[String, Long] | Map.empty | key NamePattern; values >= 0 | rag's reads served from caches (fixed names chosen by rag, e.g. metric-cache entries read) |

`ModelCallCounts(logicalCalls: Long, invalidOutputs: Long, truncatedOutputs: Long, droppedBoundNumbers: Long, fallbacks: Long)`.

Spec items left out, and why:
- HTTP attempts per task and retried attempts per task: rag counts them only per process (`LlmUsage` / `TaskCallCounters.httpAttempts`, `retriedAttempts`, Llm.scala:118-121, incremented by the network clients at Llm.scala:431,476,552,555). `ModelReply` carries no retry count, so a per-query value would need new plumbing in rag (or a delta of shared counters, which is wrong under concurrent queries in services). They stay in the run report's `modelCalls` section.

## Stable ID formulas

Unchanged. No stable ID formula was touched; latency, token and v2 counters are measurements and never inputs of any stable ID.

## Configuration precedence

Unchanged (environment `SCHOLARCHAIN_CFG_<PATH>` > file named by `SCHOLARCHAIN_CONFIG_FILE` > error; no defaults). Only refusal texts and two new semantic rules changed.

## Sample serialized record

Frozen: /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v2/QueryAudit.json (the v1 example with `schemaVersion: 2` and these tail fields):

```json
"queryTemplateIds": ["researcher_by_id", "coauthor_neighbors"],
"indexesQueried": ["researcher-profile-v1"],
"metadataFilterKeys": ["windowStartYear", "windowEndYear"],
"modelCalls": {
  "planner":   {"logicalCalls": 1, "invalidOutputs": 0, "truncatedOutputs": 0, "droppedBoundNumbers": 0, "fallbacks": 0},
  "synthesis": {"logicalCalls": 2, "invalidOutputs": 1, "truncatedOutputs": 1, "droppedBoundNumbers": 1, "fallbacks": 0}
},
"cacheHits": {"researcher-metric-cache": 2}
```

Sample refusal (sentinel pasted into service.apiKeyEnvVar): `service.apiKeyEnvVar: must match [A-Z][A-Z0-9_]*`.

## Proposed later

1. Phase 9 item 2: a `partially_supported` AnswerClaim must carry an `uncertainty` note (contract change for rag and domain together; not in this phase).
2. D-04: reject a dotted flat key that duplicates a nested key (ConfigLoader.scala `flatten`/`ListMap`), or reject dotted top-level keys outright.
3. D-05: QueryAudit rule `bundleId == EvidenceId.forBundle(queryRunId, evidenceIds)` when present, and `bundleId` empty iff `evidenceIds` empty (rag already produces this form).
4. Per-query HTTP attempts and retried attempts in QueryAudit (needs rag to return per-call retry counts in `ModelReply`); then an additive QueryAudit v3 or added ModelCallCounts fields.
5. Drop the uPickle/ujson parser message from the WindowList and "not valid JSON" configuration errors (now position and type only, but not guaranteed by the library).
6. Phase 9 items 3 and 4 (defaultWindow helper; sorted-key JSON for QueryAudit maps) remain proposed.

## INTERFACE HANDOFF

- Files changed: listed above (modules/core only).
- Public types and signatures:
  - `final case class ModelCallCounts(logicalCalls: Long, invalidOutputs: Long, truncatedOutputs: Long, droppedBoundNumbers: Long, fallbacks: Long)` with `ModelCallCounts.readWriter`.
  - `QueryAudit(... 21 v1 fields ..., queryTemplateIds: List[String] = Nil, indexesQueried: List[String] = Nil, metadataFilterKeys: List[String] = Nil, modelCalls: Map[String, ModelCallCounts] = Map.empty, cacheHits: Map[String, Long] = Map.empty)`.
  - `QueryAudit.V2Fields: List[String]`, `QueryAudit.NamePattern: String`, `QueryAudit.isValidName(s: String): Boolean`; `QueryAudit.readWriter` is now version-aware (always via `Contract.encode` / `Contract.decode[QueryAudit]`).
  - `SchemaVersions.stillReadable: Map[String, Set[Int]]`; `SchemaVersions.currentOf("QueryAudit") == 2`.
  - `ConfigValidator.ReservedEnvVarNames: Set[String]`.
- Version rule for producers and readers: rag writes `schemaVersion = 2` and fills the five fields (empty is valid: "nothing of that kind in this query"). A record with `schemaVersion = 1` must keep all five at defaults; it is encoded without them. On the wire, v2 requires all five keys; v1 must not carry any of them. Version 3+ is rejected.
- storage (3e): `QueryAuditWriter` needs no change (it encodes and decodes through `Contract`); round-trip the frozen v2 file above and a v1 record (frozen v1: /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/QueryAudit.json). Note: core test resources are not on other modules' classpaths; copy the JSON text or build the record like `Fixtures.queryAuditV2` (org.scholarchain.core.Fixtures, core test scope).
- rag (3f): construct with named arguments for the v2 fields and `schemaVersion = 2`; keys of `modelCalls` and `cacheHits` must match `[a-z0-9][a-z0-9_.-]*`; index names must match VectorIndexName.Pattern; filter keys must have no whitespace; keep `invalidOutputs`, `truncatedOutputs` <= `logicalCalls` per task.
- Fixtures for others: `org.scholarchain.core.Fixtures.queryAuditV2` and `.queryAudit` (core test scope only).
- Thread-safety: all new types are immutable case classes; codecs are stateless.
- Known limits: see RISKS; no per-query HTTP or retry counts.

TEST EVIDENCE
- command: sbt -batch "core/testOnly org.scholarchain.core.config.ConfigLoaderSuite org.scholarchain.core.config.ServiceConfigSuite org.scholarchain.core.config.ConfigRefusalSuite org.scholarchain.core.QueryAuditV2Suite org.scholarchain.core.WireContractSuite org.scholarchain.core.SchemaVersionsSuite org.scholarchain.core.RuntimeContractSuite" "core2_13/testOnly org.scholarchain.core.config.ConfigLoaderSuite org.scholarchain.core.config.ServiceConfigSuite org.scholarchain.core.config.ConfigRefusalSuite org.scholarchain.core.QueryAuditV2Suite org.scholarchain.core.WireContractSuite org.scholarchain.core.SchemaVersionsSuite org.scholarchain.core.RuntimeContractSuite" "storage/testOnly org.scholarchain.storage.RuntimeReadersSuite" "rag/Test/compile" "services/Test/compile" "contractTests/testOnly org.scholarchain.contracts.config.ConfigOmissionSuite"
- finished: 2026-11-16 20:57:28 (local)
- per suite (core, Scala 3 / jvm-3): ConfigLoaderSuite run=37 passed=37 failed=0 skipped=0; ServiceConfigSuite run=14 passed=14 failed=0 skipped=0; ConfigRefusalSuite run=8 passed=8 failed=0 skipped=0; QueryAuditV2Suite run=11 passed=11 failed=0 skipped=0; WireContractSuite run=289 passed=289 failed=0 skipped=0; SchemaVersionsSuite run=6 passed=6 failed=0 skipped=0; RuntimeContractSuite run=28 passed=28 failed=0 skipped=0 (core total "Passed: Total 393, Failed 0, Errors 0, Passed 393")
- per suite (core2_13 / jvm-2.13): same seven suites, same counts (37, 14, 8, 11, 289, 6, 28), all passed, skipped=0 ("Passed: Total 393, Failed 0, Errors 0, Passed 393")
- per suite (storage): RuntimeReadersSuite run=9 passed=9 failed=0 skipped=0 ("Passed: Total 9, Failed 0, Errors 0, Passed 9")
- rag/Test/compile: [success]; services/Test/compile: [success]
- per suite (contractTests): ConfigOmissionSuite run=6 passed=3 failed=3 skipped=0; the 3 failures are exactly the expected flips below
- result line: `[error] Failed: Total 6, Failed 3, Errors 0, Passed 3` (contractTests, last project in the command; then `EXIT=1`). Every earlier project in the same invocation ended `[success]`.
- expected-failure flips: T-08 (ConfigOmissionSuite.scala:113 "expected failure but test passed"); T-09 (ConfigOmissionSuite.scala:121 "expected failure but test passed"); U-6 (ConfigOmissionSuite.scala:131 "expected failure but test passed")
- source changed after this run: no
