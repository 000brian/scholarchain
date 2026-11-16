STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: docs/agent-runs/phase-10/prompt.md (sha256 c032785a6cbef13124c196fb3c42be52b43cfd6e10e712a1861553d1cde5f49a, checked), step 2, decision 5a only: a new optional config section `service`.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/config/valid-v1-service.json (the frozen example: the base config plus a full `service` section)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/config/ServiceConfigSuite.scala (14 tests)
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/config/ScholarChainConfig.scala (new `ServiceSettings` and its companion constants; new field `service: Option[ServiceSettings] = None` added last in `ScholarChainConfig`)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/config/ConfigLoader.scala (new `ConfigSection("service", ...)` with 13 required keys and 1 optional key; build step; `ConfigValidator.service` rules plus an `isHttpsUrl` helper; two doc comment lines updated)
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/config/ConfigLoaderSuite.scala (two existing assertions that list or count every key now include the 14 service keys; no other change)
COMMANDS RUN:
- shasum -a 256 on the work order (matched); sed -n, cat, grep and ls for reading; read-only git status and git diff --stat.
- sbt -batch "core/testOnly ...ConfigLoaderSuite ...ServiceConfigSuite ...WireContractSuite" "core2_13/testOnly <same>". First run: 1 failure in ConfigLoaderSuite (line 328, an existing test that lists every key and now saw the service keys). I fixed the test. Second run: core 340/340 passed, core2_13 340/340 passed.
- sbt -batch check, run once in the background with output logged to the scratchpad: exit=0, "[success] Total time: 817 s".
TEST RESULTS:
- Targeted runs: ServiceConfigSuite 14/14, ConfigLoaderSuite 37/37, WireContractSuite 289/289, on both Scala 3 (core) and Scala 2.13 (core2_13).
- sbt check: module_tests=1662 (was 1634; +14 in core and +14 in core2_13), contract tests suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0. markTestRun updated .claude/.last-test-run (module_tests=1662, contract_tests=336).
VALIDATION RESULTS: The config is loaded at runtime from a file plus environment variables. It is not a wire record, so no SchemaVersions entry is involved. The change is additive: `configVersion` stays 1, and a config without `service` serializes and fingerprints exactly as before (tested).
ASSUMPTIONS:
- `service.jobQueueUrl` passes only if it starts with lowercase `https://`, has no whitespace, parses as a java.net.URI with a non-empty host, and has no user-info part. I added the no-user-info rule so a URL with an embedded password is rejected. `HTTPS://` (uppercase) is rejected.
- The two cross-key rules are checked even when a range rule has already failed, so every problem is reported at once. This matches how the existing sections behave. The TTL rule compares as Long, so a large TTL cannot overflow.
- An empty-string key in the file is rejected as "must not be blank" by the existing Text decoder, the same as in every other section.
RISKS:
- Any code that builds or pattern-matches `ScholarChainConfig` by position would break because of the new last field. Grep found none outside core, and sbt check passes.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: storage-engineer (step 3, decision 6), then the step 5 interface gate, which checks for the `service` config section described below.

CONTRACT CHANGES AND SCHEMA VERSIONS
- ScholarChainConfig `configVersion` stays 1. The change is additive. The new optional section `service` resolves to None unless one of its 14 keys is set in the file or the environment. Once any key is set, all 13 required keys must be set. There are no defaults.
- Environment variables: `SCHOLARCHAIN_CFG_SERVICE_<UPPER_SNAKE>`, for example SCHOLARCHAIN_CFG_SERVICE_PORT, SCHOLARCHAIN_CFG_SERVICE_READ_ONLY_API_KEY_ENV_VAR, SCHOLARCHAIN_CFG_SERVICE_JOB_TTL_SECONDS, SCHOLARCHAIN_CFG_SERVICE_JOB_QUEUE_URL. As in every section, an environment variable takes precedence over the file.
- Rules and their error messages (an error starts with the field path):
  - port: 1..65535
  - apiKeyEnvVar and readOnlyApiKeyEnvVar: must match `[A-Z][A-Z0-9_]*` and must not start with `SCHOLARCHAIN_CFG_` (the `llm.apiKeyEnvVar` rules). readOnlyApiKeyEnvVar must differ from apiKeyEnvVar ("must differ from service.apiKeyEnvVar").
  - maxRequestBytes: 1024..1048576
  - syncDeadlineMillis: 1..120000
  - maxConcurrentQueries: 1..64
  - workerConcurrency: 1..64
  - jobMaxAttempts: 1..5
  - jobLeaseMillis: 10000..900000, and "service.jobLeaseMillis: L must be greater than syncDeadlineMillis S"
  - jobTtlSeconds: 3600..604800, and "service.jobTtlSeconds: T s must be longer than jobLeaseMillis L ms" (T*1000 > L, compared as Long)
  - circuitFailureThreshold: 1..100
  - circuitOpenMillis: 1000..600000
  - requestsPerMinute: > 0
  - jobQueueUrl: optional; "must be an https:// URL with a host"
- Sample of the frozen `service` section (values are placeholders):
  {"port":8080,"apiKeyEnvVar":"SCHOLARCHAIN_EXAMPLE_API_KEY","readOnlyApiKeyEnvVar":"SCHOLARCHAIN_EXAMPLE_READ_ONLY_API_KEY","maxRequestBytes":65536,"syncDeadlineMillis":20000,"maxConcurrentQueries":8,"workerConcurrency":4,"jobMaxAttempts":3,"jobLeaseMillis":120000,"jobTtlSeconds":86400,"circuitFailureThreshold":5,"circuitOpenMillis":30000,"requestsPerMinute":120,"jobQueueUrl":"https://queue.example.invalid/example-jobs"}
- What the tests cover: absent section gives None with the old serialization and fingerprint; loading every value from the frozen file with its origins; a serialization round trip; jobQueueUrl optional; partial sections report every missing key (13 when only the URL is set, 12 when only the port is set); environment precedence; a section built from the environment alone; each range rule with its boundaries accepted; both cross-key rules, including the equal case, the case just above the limit, and no overflow; the URL rules; env var naming and an unknown-variable rejection; and the name-only test described next.
- Name-only test: setting SCHOLARCHAIN_EXAMPLE_API_KEY and SCHOLARCHAIN_EXAMPLE_READ_ONLY_API_KEY to placeholder values gives the same config and fingerprint as leaving them unset. Neither value appears in the serialized config or in the origins, and an empty value is ignored.

STABLE ID FORMULAS: unchanged (no ID change in this step).

INTERFACE HANDOFF
- Files changed: listed under FILES CREATED and FILES MODIFIED above. Nothing else in core changed.
- Public types and signatures (package org.scholarchain.core.config):
  final case class ServiceSettings(
      port: Int,
      apiKeyEnvVar: String,              // env var NAME of the full-access key
      readOnlyApiKeyEnvVar: String,      // env var NAME of the read-only key
      maxRequestBytes: Int,
      syncDeadlineMillis: Int,
      maxConcurrentQueries: Int,
      workerConcurrency: Int,
      jobMaxAttempts: Int,
      jobLeaseMillis: Int,
      jobTtlSeconds: Int,                // also the idempotency record lifetime
      circuitFailureThreshold: Int,
      circuitOpenMillis: Int,
      requestsPerMinute: Int,
      jobQueueUrl: Option[String])       // services decides when it is required (e.g. --stores aws)
  object ServiceSettings { implicit val readWriter: ReadWriter[ServiceSettings]; constants MinMaxRequestBytes=1024, MaxMaxRequestBytes=1048576, MaxSyncDeadlineMillis=120000, MaxConcurrentQueries=64, MaxWorkerConcurrency=64, MaxJobAttempts=5, MinJobLeaseMillis=10000, MaxJobLeaseMillis=900000, MinJobTtlSeconds=3600, MaxJobTtlSeconds=604800, MaxCircuitFailureThreshold=100, MinCircuitOpenMillis=1000, MaxCircuitOpenMillis=600000 }
  ScholarChainConfig gains a new last field: `service: Option[ServiceSettings] = None`.
- How a caller gets it:
  ConfigLoader.loadFromEnvironment(env: Map[String, String]): Either[ContractError, LoadedConfig]
  (or ConfigLoader.load(fileText, fileName, env)), then `loaded.config.service: Option[ServiceSettings]`.
  The key values are read by the caller: `env.get(settings.apiKeyEnvVar)` and `env.get(settings.readOnlyApiKeyEnvVar)`. Core never reads them.
  `loaded.origins("service.port")` reports whether each value came from the file or the environment.
- Test fixtures:
  - Frozen example: modules/core/src/test/resources/config/valid-v1-service.json (classpath `/config/valid-v1-service.json`).
  - Expected-value constant: `ServiceConfigSuite.Expected`, class org.scholarchain.core.config.ServiceConfigSuite.
  - services cannot use either one directly: build.sbt gives services only core's compile scope, with no test->test dependency on core. service-engineer should build its own `ServiceSettings(...)` value in its tests (the case class is public) or copy the JSON into its own test resources.
- Thread-safety: ServiceSettings and ScholarChainConfig are immutable case classes and safe to share across threads. ConfigLoader and ConfigValidator are stateless objects, safe to call from any thread.
- Known limitations:
  - Core does not check that the service key names differ from `llm.apiKeyEnvVar` or `embedding.apiKeyEnvVar`; it was not in the work order (proposed later).
  - Core does not require jobQueueUrl for `--stores aws`; per decision 5a, services enforces that.
  - The relationship between workerConcurrency and maxConcurrentQueries is not checked; none was specified.
  - Core never checks whether the named key variables are set or non-empty; the services module must fail at startup if they are missing.
