interface: yes

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 15 session 2, repair-00-domain (docs/agent-runs/phase-15/prompt.md, sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0; decisions 4 "Interface gate", 7.5, 8.2). Add `abstentionCodes` to QueryAudit (G-28).
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/QueryAuditV3Suite.scala
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v3/QueryAudit.json (frozen v3, not abstained, "abstentionCodes": [])
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v3/QueryAudit.abstained.json (frozen v3, abstained, two codes)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Records.scala (QueryAudit field, constants, version-aware codec, scaladoc)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Validator.scala (queryAuditV3 rules)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/SchemaVersions.scala (QueryAudit 3; stillReadable 1, 2)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/Fixtures.scala (queryAuditV3, queryAuditV3Abstained)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/WireContractSuite.scala (current QueryAudit example is queryAuditV3)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/SchemaVersionsSuite.scala (frozen table QueryAudit -> 3; supported {1,2,3})
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/QueryAuditV2Suite.scala (first test: v2 and v1 readable instead of "v2 is current")
  (The modules/core/**/config/* files shown modified in git status were already modified before this step; not touched here.)
COMMANDS RUN:
1. sbt -batch "core/testOnly org.scholarchain.core.QueryAuditV3Suite org.scholarchain.core.QueryAuditV2Suite org.scholarchain.core.SchemaVersionsSuite org.scholarchain.core.WireContractSuite org.scholarchain.core.RuntimeContractSuite" "core2_13/testOnly <same suites>"
2. sbt -batch "storage/Test/compile" "rag/Test/compile" "services/Test/compile"
TEST RESULTS:
- core (Scala 3): Passed: Total 342, Failed 0, Errors 0 (QueryAuditV3Suite 8, QueryAuditV2Suite 11, SchemaVersionsSuite 6, RuntimeContractSuite 28, WireContractSuite 289)
- core2_13 (Scala 2.13): Passed: Total 342, Failed 0, Errors 0 (same counts)
- storage, rag, services Test/compile: [success] each
VALIDATION RESULTS: frozen v3 wire examples decode to fixtures and the fixtures re-encode to them; frozen v1 and v2 examples still decode (abstentionCodes = Nil) and re-encode to their own form.
ASSUMPTIONS:
- Versioned per law 6 and the Phase 11 precedent: additive field -> QueryAudit schema version 3, with v1 and v2 still readable (not a breaking change: no reader of stored data breaks).
- In version 3, codes are required (>= 1) exactly when abstained is true, and must be empty when abstained is false. This enforces decision 7.5 in the contract. Older abstained v1/v2 audits read with no codes.
- Bounds chosen: code length <= 64 characters, <= 8 codes per audit, distinct.
- Refusal messages never echo the refused code (it may be prose).
RISKS:
- rag (step 3) must switch its audit to `schemaVersion = 3` to store codes (QueryWorkflow.scala:466 writes the literal 2 today). A v2 audit with codes is refused by the validator ("schemaVersion: version 2 carries no abstentionCodes"); once on v3, an abstained answer with no code is refused ("abstentionCodes: at least one code is required when abstained is true"), so rag must map every abstention path to a code.
- services ReadRoutesSuite asserts auditSchemaVersion 2 for the rag-produced audit; it will need 3 once rag bumps (service-engineer, decision 8.2, also exposes the field in explain).
- A pre-Phase-15 deployed reader (old image) refuses v3 audits as unsupported version; reader and producer ship in the same runtime image, so this only matters for mixed deployments.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: rag-engineer step 3 (emit v3 with codes), service-engineer (explain exposes abstentionCodes); step 4 wave closer full `sbt check` closes this repair; code-reviewer.

CONTRACT CHANGES AND SCHEMA VERSIONS
- QueryAudit 2 -> 3 (Phase 15, additive). SchemaVersions.current("QueryAudit") = 3; stillReadable("QueryAudit") = {1, 2}; supported = {1, 2, 3}. No other schema changed.
- Wire: v3 = v2 keys in v2 order, then "abstentionCodes" (JSON array of strings, always written, [] when not abstained). v1/v2 wires never carry it (decode refuses "abstentionCodes: not a field of QueryAudit schema version N"); v3 wire must carry it ("abstentionCodes: required in QueryAudit schema version 3").
- Sample (v3 abstained, tail): "abstained": true, ..., "cacheHits": {"researcher-metric-cache": 2}, "abstentionCodes": ["no-path-within-depth", "path-query-timeout"]

STABLE ID FORMULAS: unchanged (abstentionCodes is not an input to any stable ID).

INTERFACE HANDOFF
- Field: `QueryAudit.abstentionCodes: List[String] = Nil` (last constructor parameter, named argument), Records.scala:752.
- Constants: `QueryAudit.V3Fields = List("abstentionCodes")` (Records.scala:761); `QueryAudit.AbstentionCodePattern = "[a-z][a-z0-9]*(-[a-z0-9]+)*"` (Records.scala:767); `QueryAudit.MaxAbstentionCodeLength = 64` (Records.scala:771); `QueryAudit.MaxAbstentionCodes = 8` (Records.scala:774); `QueryAudit.isValidAbstentionCode(s: String): Boolean` (Records.scala:776).
- Codec: `QueryAudit.readWriter` (Records.scala:803), version-aware for v1/v2/v3.
- Validation: `Validator.queryAuditV3` (Validator.scala:1094): v<3 -> codes empty; v3 -> each code matches the pattern and length bound, <= 8 codes, distinct, non-empty iff abstained.
- Schema version: SchemaVersions.scala:75 (`"QueryAudit" -> 3`), :79 (stillReadable).
- Fixtures (core test classpath): `Fixtures.queryAuditV3`, `Fixtures.queryAuditV3Abstained`; frozen `/wire/v3/QueryAudit.json`, `/wire/v3/QueryAudit.abstained.json`.
- Producer usage: `QueryAudit(3, ..., abstained, provenance, queryTemplateIds = ..., ..., abstentionCodes = List("no-path-within-depth"))`. The code table and short texts belong to rag.
- Thread-safety: immutable case class and pure functions. Limits: 8 codes x 64 chars.

VALIDATION EVIDENCE
- core/testOnly (Scala 3): "[info] Passed: Total 342, Failed 0, Errors 0, Passed 342" / "[success] Total time: 15 s"
- core2_13/testOnly (Scala 2.13): "[info] Passed: Total 342, Failed 0, Errors 0, Passed 342" / "[success] Total time: 10 s"
- storage/Test/compile, rag/Test/compile, services/Test/compile: "[success]" each
finished: after the last command above
files changed after these runs: none
