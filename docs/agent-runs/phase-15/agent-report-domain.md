interface: no (key and environment variable exactly as decision 5 fixes them)

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922...dfa3e0), session 2 step 1, decision 5: optional service.embeddingQueueUrl with env override SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL
FILES CREATED: none
FILES MODIFIED:
- modules/core/src/main/scala/org/scholarchain/core/config/ScholarChainConfig.scala (ServiceSettings field + scaladoc, :386, :408)
- modules/core/src/main/scala/org/scholarchain/core/config/ConfigLoader.scala (optional key :240, resolution :551, validation :894-895)
- modules/core/src/test/resources/config/valid-v1-service.json (frozen example gains embeddingQueueUrl placeholder)
- modules/core/src/test/scala/org/scholarchain/core/config/ServiceConfigSuite.scala (4 new tests :372, :386, :408, :433; Expected, key count 14 -> 15, env-only test)
- modules/core/src/test/scala/org/scholarchain/core/config/ConfigRefusalSuite.scala (new U-6 test :113; key added to refused list)
- modules/core/src/test/scala/org/scholarchain/core/config/ConfigLoaderSuite.scala (:526-528 key inventory +1)
COMMANDS RUN:
1. sbt -batch "core/testOnly org.scholarchain.core.config.ServiceConfigSuite org.scholarchain.core.config.ConfigRefusalSuite" (tests first, before implementation): FAILED as expected, 6 compile errors "ServiceSettings does not have a parameter embeddingQueueUrl"
2. sbt -batch "core/testOnly org.scholarchain.core.config.*": Failed 1 of 64 (ConfigLoaderSuite:528 key inventory count, expected; updated the inventory)
3. sbt -batch "core/testOnly org.scholarchain.core.config.*" "core2_13/testOnly org.scholarchain.core.config.*": Passed: Total 64, Failed 0 (Scala 3); Passed: Total 64, Failed 0 (Scala 2.13)
4. read-only: grep, git status --short, git diff --stat
TEST RESULTS: core (Scala 3) config suites 64/64 passed; core2_13 config suites 64/64 passed. The 4 "decision 5" ServiceConfigSuite tests and the ConfigRefusalSuite decision-5 test appear in both builds' test reports.
VALIDATION RESULTS:
- valid: https URL from file loads, origin File (ServiceConfigSuite:372); env override wins with origin Environment(SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL) (:408)
- absent: None, no origin, job queue unchanged, not serialized, old ServiceSettings JSON reads as None (:386)
- malformed (8 URL shapes, file and env): exactly one error "service.embeddingQueueUrl: must be an https:// URL with a host"; message contains neither the value nor its host (:433); non-string: "service.embeddingQueueUrl: expected a string"; Sentinel refusal names key, never value (ConfigRefusalSuite:113 and the all-string-leaves sweep)
- each fails without the change: confirmed by command 1 (compile failure); without the loader key the file value would be "unknown configuration key"
- config/course.json: grep finds no embeddingQueueUrl / EMBEDDING_QUEUE (holds no value, as decision 5 requires; file is infra-engineer's, untouched)
ASSUMPTIONS:
- Additive optional key with default None: no configVersion bump (still 1). None is omitted by uPickle, so existing configurations keep their JSON and fingerprint (asserted in :386).
- The frozen test example valid-v1-service.json gains a placeholder value (queue.example.invalid) so the refusal sweep covers the new key.
RISKS:
- Downstream modules (services, storage, rag) were not compiled in this step (work order: targeted core tests only). Source compatibility relies on the trailing default parameter; named copy(...) calls in services tests are unaffected. Wave closer's sbt check confirms.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: interface check by steps 2, 3, 4 consumers; orchestrator literal Grep against decision 5 and 5a Terraform; sbt check by the wave closer (service-engineer, step 4).

CONTRACT CHANGES AND SCHEMA VERSIONS
- ServiceSettings gains `embeddingQueueUrl: Option[String] = None` (additive). configVersion unchanged (1). No wire break: absent field reads as None, None is not written.

STABLE ID FORMULAS: none changed.

CONFIGURATION PRECEDENCE: environment variable SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL over file key service.embeddingQueueUrl (existing loader rule); either one alone activates the optional service section, which then needs its 13 required keys.

INTERFACE HANDOFF
- Config key: service.embeddingQueueUrl (optional, Text), ConfigLoader.scala:240
- Env var: SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL (= ConfigKey.envVarFor("service.embeddingQueueUrl"), asserted ServiceConfigSuite:408)
- Accessor: org.scholarchain.core.config.ServiceSettings#embeddingQueueUrl: Option[String] (ScholarChainConfig.scala:408); read as `config.service.flatMap(_.embeddingQueueUrl)`
- Validation: must satisfy ConfigValidator.isHttpsUrl (same as jobQueueUrl); refusal text exactly "service.embeddingQueueUrl: must be an https:// URL with a host" (ConfigLoader.scala:895), never the value
- Absent => None: worker runs only the job loop (behaviour decided by services, step 4)
- Fixtures: modules/core/src/test/resources/config/valid-v1-service.json now has "embeddingQueueUrl": "https://queue.example.invalid/example-embedding-tasks"
- Thread-safety: immutable case class. Limits: none beyond the URL rule.

PROPOSED LATER: none

VALIDATION EVIDENCE
- sbt -batch "core/testOnly org.scholarchain.core.config.*" "core2_13/testOnly org.scholarchain.core.config.*": "Passed: Total 64, Failed 0, Errors 0, Passed 64" twice (Scala 3, Scala 2.13)
- test reports: modules/core/target/jvm-3/test-reports/ and modules/core/target/jvm-2.13/test-reports/ contain the decision-5 tests (ServiceConfigSuite 4, ConfigRefusalSuite 1)
- pre-implementation run failed to compile on the missing parameter (tests fail without the change)
- finished: after the last command above
files changed after these runs: none
