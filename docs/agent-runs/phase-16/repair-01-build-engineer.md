# Phase 16, session 2, step 9 repair round: repair-01-build-engineer

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, re-hashed and matched); decisions 5, 15; repair findings P16-B-01 (major), P16-A-02 (minor); last repairer touching build.sbt/modules, so the full sbt check runs here
FILES CREATED: none
FILES MODIFIED: docker/emulator/Dockerfile (rewritten; still untracked), build.sbt, project/BuildSelfTest.scala (still untracked)
COMMANDS RUN: sha256 of the work order; cat/grep/sed -n/git diff --stat/git status --short (read-only); sbt -batch check (log: S/../r01-check.log, i.e. /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/ee36fc30-b7d0-4ca7-9ec6-7f86622ec5d3/scratchpad/r01-check.log); sbt -batch packageCheck (log: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/ee36fc30-b7d0-4ca7-9ec6-7f86622ec5d3/scratchpad/r01-packagecheck.log); bounded wait loops (for/grep/sleep 10, at most 16 or 55 iterations) on the background check's output file. The formatter hook's own `sbt scalafmtOnly build.sbt` ran once after a build.sbt edit and failed to load (build.sbt referenced BuildSelfTest.aliasProblems before I added it); the next edit added the function, and scalafmtSbtCheck in the check passed. No docker, no sbt clean.
TEST RESULTS: sbt check exit 0: Module tests suites=148 executed=2285 passed=2285 failed=0 errors=0 skipped=5; Contract tests (no AWS) suites=34 executed=399 passed=399 failed=0 errors=0 skipped=0; marker updated (module_tests=2285, contract_tests=399), [success] Total time 987 s, completed Nov 21, 2026, 10:40:57 PM
VALIDATION RESULTS: Ledger check passed (scalafmt 3.11.5); buildSelfTest: ok (includes the new aliasCaseProblems self-test); buildPolicyCheck: ok (includes the new aliasProblems check on the registered alias); packageCheck exit 0, "packageCheck: ok", [success] 4 s, completed 10:41:20 PM (assemblies were up to date from the check's own inputs)
ASSUMPTIONS: sbt 1.13.1's BasicCommands.allAliases(state) returns the session's registered (name, command) alias pairs (it compiled, and buildPolicyCheck passed with the new form); the documentation excerpt in S/ref-emulator-docker-doc.md is the verbatim documented Dockerfile
RISKS: Dockerfile runtime: untested (no docker by rule); the package is LTS/latest and the base image a tag, so the emulator version must be recorded at E14; the `uname -m` architecture check was dropped (not in the documented RUN line; `--platform=linux/arm64` on FROM plus step 3's `Architecture` check in scripts/README.md cover it)
MANUAL ACTION REQUIRED: human decides (P16-B-01 note) that the documented `ENV EMULATOR_BIND_ADDRESS=0.0.0.0` replaces the work order's `-b 0.0.0.0` wording (orchestrator decision applied); at E14 the human builds and records the printed emulator version and image ID
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of P16-B-01 and P16-A-02; E14 human build
```

## Findings

| ID | Severity | Status | file:line | What changed |
|---|---|---|---|---|
| P16-B-01 | major | RESOLVED | docker/emulator/Dockerfile:40-47 (header :1-38) | The documented Dockerfile verbatim with the documented Arm substitution: `FROM --platform=linux/arm64 public.ecr.aws/amazonlinux/amazonlinux:2023-minimal` (:40), `ADD https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator-aarch64.rpm /tmp/aws-emulator.rpm` (:41), `RUN rpm -i /tmp/aws-emulator.rpm && rm /tmp/aws-emulator.rpm && mkdir -p /home/emulator && chown 1000 /home/emulator` (:42-43), `USER 1000` (:44), `ENV HOME=/home/emulator EMULATOR_BIND_ADDRESS=0.0.0.0` (:45), `EXPOSE 9297` (:46), `ENTRYPOINT ["aws-emulator"]` (:47). Removed: the guessed S3 URL and ARG, curl/tar install, `dnf install tar gzip shadow-utils`, `useradd`, PATH change, WORKDIR, `-b 0.0.0.0`. Header (:10-16) states that the documented ENV binding replaces the work order's `-b 0.0.0.0` wording (governing rule "exactly the documented one"; orchestrator decision); header keeps LTS/latest and record-the-version-at-E14 (:27-30), never pushed / not part of the services build / BuildIdentity note (:32-38). No non-loopback host other than the documented download host remains. |
| P16-A-02 | minor | RESOLVED | build.sbt:711 (comments :103, :706-709); build.sbt:642 (check); project/BuildSelfTest.scala:318-357, :230 | Alias now `addCommandAlias("emulatorTest", "; storage/EmulatorTest/test ; services/EmulatorTest/test")`: storage first, services only after storage passes, never together. Name unchanged. buildPolicyCheck now reads the registered aliases (`BasicCommands.allAliases(state.value)`) and fails if `emulatorTest` is missing, defined more than once, contains the word `all` ("runs both emulator suites at once against one emulator"), or differs from the exact sequential form (BuildSelfTest.aliasProblems, :326). buildSelfTest runs aliasCaseProblems (:345), which asserts the sequential form passes, the old `all storage/EmulatorTest/test services/EmulatorTest/test` form is reported, and a missing alias is reported; so the check fails with the old form. |

## scripts/README.md consistency (not edited)

scripts/README.md:227 `docker build -t scholarchain-emulator docker/emulator` and :228 `--format '{{.Architecture}}'` (arm64) stay valid. :231 `docker run --rm --memory=2g --memory-swap=2g -p 127.0.0.1:9297:9297 -e HOME="$HOME" -v "$HOME/.aws/emulator:$HOME/.aws/emulator" scholarchain-emulator -p 9297 --telemetry off` stays valid: the arguments after the image name are appended to `ENTRYPOINT ["aws-emulator"]`, as the documented run command (`aws-emulator -p $PORT`) and the telemetry page ("add `--telemetry off` after the image name") show. :239-240 (steps 12 and 13, storage then services, "never at the same time") now match the alias. No mismatch for infra-engineer.

## INTERFACE HANDOFF

- Files changed: docker/emulator/Dockerfile, build.sbt, project/BuildSelfTest.scala.
- Dockerfile build command: unchanged (`docker build -t scholarchain-emulator docker/emulator`); image tag unchanged; run command unchanged; port 9297 exposed; the container user is UID 1000 (no named user), HOME=/home/emulator; the bind address comes from `EMULATOR_BIND_ADDRESS=0.0.0.0` instead of `-b`.
- Alias semantics: `sbt emulatorTest` now runs `storage/EmulatorTest/test`, then `services/EmulatorTest/test`, in sequence; a storage failure stops the command before services runs (previously `all` ran both concurrently and reported both). Alias name unchanged. Not part of check or markTestRun.
- New build-internal signatures (project/BuildSelfTest.scala): `val EmulatorTestAliasName: String`, `val EmulatorTestAlias: String`, `def aliasProblems(aliases: Seq[(String, String)]): Seq[String]`, `def aliasCaseProblems(): Seq[String]`.
- Fixtures: none. Thread-safety: pure functions. Limits: the alias check reads the aliases of the loaded sbt session only.
- Dockerfile runtime: untested (static review against the documentation excerpt only; human execution required at E14).

## Proposed later

- None.

## VALIDATION EVIDENCE

- `sbt -batch check`: exit=0; log lines: `[info] Ledger check passed (scalafmt 3.11.5).`, `[info] buildSelfTest: ok`, `[info] buildPolicyCheck: ok`, `[info] Contract tests (no AWS): suites=34 executed=399 passed=399 failed=0 errors=0 skipped=0`, `[info] Module tests: suites=148 executed=2285 passed=2285 failed=0 errors=0 skipped=5`, `[info] Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=2285, contract_tests=399)`, `[success] Total time: 987 s (0:16:27.0), completed Nov 21, 2026, 10:40:57 PM`. Marker timestamp=2026-11-22T04:40:57.276316Z.
- `sbt -batch packageCheck`: exit=0; `[info] packageCheck: ok`, `[success] Total time: 4 s, completed Nov 21, 2026, 10:41:20 PM`.
- `find build.sbt project docker modules tests prompts -newer .claude/.last-test-run -type f -not -path "*/target/*"`: no output.
- files changed after these runs: none (only this report, in the scratchpad).
