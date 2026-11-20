# repair-01-service-engineer (Phase 15, session 2, step 9)

STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: docs/agent-runs/phase-15/prompt.md (sha256 3d806922c6dd74ada61bceca45d3a302740a4083f260f0ae3ca6b6b6b9dfa3e0), decision 12, repair round 01, finding H-01 (regression tests only); step 9, last sbt-source repairer of the round (runs the full `sbt check` and `sbt -batch packageCheck`).
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/RoleConfigParitySuite.scala
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/services/src/test/scala/org/scholarchain/services/CompositionSuite.scala (one new test, lines 320-407)
- No production file changed.
COMMANDS RUN:
- `sbt -batch "services/testOnly org.scholarchain.services.RoleConfigParitySuite org.scholarchain.services.CompositionSuite"` -> Passed: Total 11, Failed 0, Errors 0 (RoleConfigParitySuite 2/2, CompositionSuite 9/9).
- `sbt check` (log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2cb924d7-0442-43bb-bde3-b3e771aed839/scratchpad/sbt-check-svc-r01.log) -> exit 0; `[info] Module tests: suites=140 executed=2195 passed=2195 failed=0 errors=0 skipped=3`; `[info] Contract tests (no AWS): suites=34 executed=399 passed=399 failed=0 errors=0 skipped=0`; `[info] Updated .../.claude/.last-test-run (module_tests=2195, contract_tests=399)`; `[success] Total time: 940 s, completed Nov 20, 2026, 2:36:22 PM`.
- `sbt -batch packageCheck` (log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/2cb924d7-0442-43bb-bde3-b3e771aed839/scratchpad/sbt-packagecheck-svc-r01.log) -> exit 0; `[info] packageCheck: ok`; `[success] Total time: 1 s, completed Nov 20, 2026, 2:36:39 PM`.
- `head -n 5 .claude/.last-test-run` ->
  timestamp=2026-11-20T20:36:22.215871Z
  module_tests=2195
  contract_tests=399
TEST RESULTS: all green (see above). The new tests pass on the current code.
VALIDATION RESULTS: full `sbt check` passed after the last source change; packageCheck ok.
ASSUMPTIONS:
- "Same environment" is modelled as each role loading `services-local-v1.json` through `Main.loadConfig(path, env)` (the production config path with `SCHOLARCHAIN_CFG_*` overrides), one fresh load per role.
- Cross-role sharing in the test matches the deployment: roles share the runtime data (fixture world: audits, bundles, graph) and the job table and queue; each role has its own config, runtime handle and server.
RISKS:
- Test (2) exercises the role gate in `ServiceServer.start` (`options.embedding.filter(_ => workerRole)`), the one every role passes through. The earlier gate in `Main.start` (aws stores and role worker/all before `Composition.embeddingWork`) is not driven directly, because `Main.start --stores aws` builds real SDK clients and offers no stub injection; it is defence in depth over the tested gate.
- Test (2) waits 300 ms per role for a loop that must not exist; the absence is asserted structurally first (no worker, loop not running, start line `embeddingWorker=false`, zero handler builds), so the wait only adds the "no receive happened" observation.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review of H-01 (infra-engineer fix plus these regression tests).

## Tests and whether they fail without the Terraform fix

The services code had no defect: the fix is in Terraform (every role gets `SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL`). Tests (1) and (2) already pass on the current services code; they guard the invariant that the fix establishes. Unit tests cannot read Terraform, so none of them can fail "without the Terraform fix" directly; test (3) reproduces the pre-fix deployment (URL on the worker only) in-process and shows the observable breakage, which is how I know test (1) is sensitive to the defect: the same flow with the pre-fix environment split gives a different stored queryId and a 404 explain, exactly what test (1) asserts against.

1. `modules/services/src/test/scala/org/scholarchain/services/RoleConfigParitySuite.scala:128` "H-01: query and worker compositions from the same environment (embedding queue URL included) give the ack queryId, the job's queryId and the stored result's queryId alike". Three separate compositions (query, worker, evidence), each from `Main.loadConfig(path, Map(SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL -> placeholder))`; one async POST to the query role; the worker role's loop runs it. Asserts: the config really resolved the URL; result `queryId` == ack `queryId` == job `queryId`; result explain link names the ack ID; `GET /v1/explain/{ackQueryId}` on the evidence role is 200. Passes on current code; fails if any role resolves a different config (demonstrated by test 3).
2. `modules/services/src/test/scala/org/scholarchain/services/CompositionSuite.scala:320` "H-01: with service.embeddingQueueUrl set (every role gets the same environment), roles query, evidence and graph start no embedding loop, build no handler and make no SQS call". `--stores aws` from stub SDK clients; the embedding work wrapped to count every queue call and handler build; roles query, evidence and graph started with `startWorker = true` (as Main does) and the work passed in. Asserts per role: `embeddingWorker == None`, loop not running, start line `embeddingWorker=false` and `worker=false`, no URL in the log; overall 0 queue calls, 0 handler builds, 0 SDK HTTP calls. Control: the worker role with the same work builds the handler once, runs the loop, and receives from the counted queue (so the counters are live). Passes on current code (the role gate already exists); it guards the claim that giving the URL to every role changes nothing for non-worker roles.
3. `modules/services/src/test/scala/org/scholarchain/services/RoleConfigParitySuite.scala:143` "H-01 hazard: roles whose configs differ only in service.embeddingQueueUrl give different query run IDs, so the ack queryId does not name the stored result (every role needs the same environment)". Asserts the two resolved configs are equal except `service.embeddingQueueUrl`, their fingerprints differ, and in the Phase 14 split (query and evidence without the URL, worker with it): result `queryId` != ack `queryId`, job `queryId` == ack, result `queryId` == the worker's own run ID for the request, and `GET /v1/explain/{ackQueryId}` is 404. This documents the hazard; it passes because the hazard is real in the fingerprint design.

## Route table, error model, limits

No route, status code, error code or limit changed.

## INTERFACE HANDOFF

- Files changed: the two test files above only. No production signature changed.
- Public signatures: none added. Tests use existing APIs: `Main.loadConfig`, `Composition.aws`, `Composition.embeddingWork`, `ServiceServer.start`/`ServerOptions`, `EmbeddingWork`, `QueryRequestParser`, `RuntimeService.fromStores`, `RuntimeService.queryRunId`.
- Fixtures: `/config/services-local-v1.json` plus the placeholder URL `https://queue.example.invalid/example-embedding-tasks` (no real endpoint, account or key).
- Thread-safety: counters are AtomicInteger; servers closed in finally blocks; runtimes built with `fromStores(owned = Nil)` close nothing shared.
- Limits: test (1)/(3) bounded by `waitUntil` 20 s and the suite timeout 120 s; test (2) by 300 ms per role plus `waitUntil` 20 s for the control.
- Invariant guarded for other owners: every service role must receive the identical `SCHOLARCHAIN_CFG_*` environment (infra-engineer, `infra/terraform/modules/runtime/main.tf`); any key that enters `ScholarChainConfig.fingerprint` and differs between roles splits query run IDs.
- Not fixed (recorded): H-04, H-05, H-06.

## VALIDATION EVIDENCE

- targeted: `[info] Passed: Total 11, Failed 0, Errors 0, Passed 11` / `[success] Total time: 6 s, completed Nov 20, 2026, 2:20:33 PM`
- sbt check: exit 0; `Module tests: suites=140 executed=2195 passed=2195 failed=0 errors=0 skipped=3`; `Contract tests (no AWS): suites=34 executed=399 passed=399 failed=0 errors=0 skipped=0`; `[success] Total time: 940 s (0:15:40.0), completed Nov 20, 2026, 2:36:22 PM`
- packageCheck: exit 0; `[info] packageCheck: ok`; `[success] Total time: 1 s, completed Nov 20, 2026, 2:36:39 PM`
- marker: `timestamp=2026-11-20T20:36:22.215871Z`, `module_tests=2195`, `contract_tests=399`

files changed after these runs: none
