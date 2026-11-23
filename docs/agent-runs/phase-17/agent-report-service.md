# agent-report-service (Phase 17 session 2, step 4, P16-A-12, wave closer)

STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24), decision 9.3 (P16-A-12, tier 2); wave closer (decision 4)
FILES CREATED: none
FILES MODIFIED: modules/services/src/test/scala/org/scholarchain/services/CompositionSuite.scala (one new test, one import; test only)
COMMANDS RUN:
- shasum -a 256 docs/agent-runs/phase-17/prompt.md (matches c22adfc5...)
- sbt -batch "services/testOnly org.scholarchain.services.CompositionSuite" (new test green: 10/10)
- temporary local revert of Composition.embeddingWork's third EmbeddingWork argument (Main.scala, the `config.vectors.toList.flatMap(_.indexNames).toSet` line and its comment), then the same testOnly: 1 failed of 10 (the new test, CompositionSuite.scala:434, obtained Set() vs the five configured index names)
- restore of Main.scala, `git diff --stat -- modules/services` (only CompositionSuite.scala changed; Main.scala identical to HEAD)
- sbt -batch "services/testOnly org.scholarchain.services.CompositionSuite org.scholarchain.services.EmbeddingWorkerSuite": 22 passed, 0 failed
- sbt check (background, log in scratchpad sbt-check.log): exit=0, `[success] Total time: 1151 s`
- sbt -batch packageCheck: `packageCheck: ok`, `[success]`
- head -n 5 .claude/.last-test-run; git status --short
- Commands not on the list (disclosed, read-only, before I re-read the list): one `cd ... && shasum ... && grep ... ; sed -n 10,20p ...` and `sed -n 60,110p` on docs/agent-runs files at the start; every later Bash call began with `cd <repo> &&`. No sbt fmt was run (the PostToolUse formatter hook formatted the edited test file).
TEST RESULTS:
- targeted: CompositionSuite 10/10, EmbeddingWorkerSuite 12/12 (22 total, 0 failed, 0 errors)
- fails-without-the-wiring proof: 1 failed / 9 passed with the line reverted (expected); restored
- full sbt check: passed; marker module_tests=2336, contract_tests=399
VALIDATION RESULTS: sbt check exit 0; packageCheck ok (spark, entity-resolution, hadoop, services assemblies built)
ASSUMPTIONS:
- The "Main.scala:107-108" of review-report-a2.md is now Main.scala:108-109 (the comment plus `config.vectors.toList.flatMap(_.indexNames).toSet` inside `Composition.embeddingWork`); line numbers shifted by one, content unchanged.
- The test reuses EmbeddingWorkerSuite's companion (`Index`, `Key`, `StubQueue`, `processed`) and the CompositionSuite stub-SDK AWS runtime (zero HTTP calls, asserted).
RISKS: none new. No real defect found: the wiring is correct; the gap was test coverage only.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R-1 and F-2 (code-reviewer)

## Route table
No route changed.

## Error model coverage
No change.

## Limits
No change.

## P16-A-12 test
`CompositionSuite`: "P16-A-12 (H-04): Composition.embeddingWork passes the configured vector index names into EmbeddingWork.indexes, so the composed worker releases an unknown index before re-embedding". It builds `Composition.aws` + `Composition.embeddingWork` from stub SDK clients with `service.embeddingQueueUrl` set, then (1) asserts `built.indexes == config.vectors...indexNames.toSet` (five names, `researcher-profile-v1` among them); (2) keeps the composed `indexes`, swaps only the queue (StubQueue) and the re-embed function, starts a worker-role server, and shows that one `processOne` over an ok task plus an `unknown-index-v1` task releases the unknown one before the re-embed call starts (`EmbeddingOutcome(2, 1, 1, 0, 0)`, released-before-reembed == List(List(unknown))); (3) zero SDK HTTP calls.

## INTERFACE HANDOFF
- Files changed: modules/services/src/test/scala/org/scholarchain/services/CompositionSuite.scala only (test scope).
- Public signatures: none changed. Production code unchanged (Main.scala identical to HEAD after the revert proof).
- Fixtures: none added; golden files unchanged.
- Thread-safety: the test uses the existing thread-safe StubQueue and a ConcurrentLinkedQueue.
- Limits: none changed.

## VALIDATION EVIDENCE
- (1) targeted: `services/testOnly CompositionSuite EmbeddingWorkerSuite`: Passed: Total 22, Failed 0, Errors 0
- revert proof: CompositionSuite 1 failed / 10 with the wiring removed; restored
- (2) sbt check: exit=0, [success] Total time 1151 s; .claude/.last-test-run: timestamp=2026-11-23T00:20:15.391719Z, module_tests=2336, contract_tests=399
- (3) sbt -batch packageCheck: packageCheck: ok, [success]
- files changed after these runs: none by me (git status --short after packageCheck shows untracked docs/final-state.md, docs/runbook.md, docs/threat-model.md, docs/tool-versions.md that appeared during my run; they are W2's documentation files, outside modules/**, not counted by the stop hook, and not mine)
