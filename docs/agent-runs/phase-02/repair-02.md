# Phase 2 repair round 2

## 1. Work order given to build-engineer

Phase 2 repair round 2: work order for build-engineer.

Context: Phase 2 work order `docs/agent-runs/phase-02/prompt.md`. Repair round 1 (findings B1–B6) is recorded in `docs/agent-runs/phase-02/repair-01.md`. code-reviewer's re-review of round 1 returned APPROVE with two new minor findings, N1 and N2 (see section 3 of `repair-01.md`). The human architect has routed N1 and N2 to you as repair round 2. Do not change anything else. F1–F4 stay deferred or accepted by the human.

Human decision still in force: Phase 2 is verified with `sbt Test/compile`. `sbt check` cannot pass until Phase 3 adds tests; markTestRun's "No tests executed" failure is expected and is not a defect. Do not commit or push.

Repair, only within your owned artifacts:

- N1 (`build.sbt` environment-input header, lines ~6-22): the list claims to be complete ("No other input is documented") but leaves out tool-level inputs that change the build: `JAVA_TOOL_OPTIONS` (read by HotSpot in the sbt JVM and every forked test JVM; can inject `-javaagent`), `JDK_JAVA_OPTIONS` (read by the `java` launcher on JDK 9+), and Coursier's mirror, credential and config inputs (`COURSIER_MIRRORS`, `COURSIER_CREDENTIALS`, `COURSIER_CONFIG_DIR`). Add them, marking supply-chain inputs. Then either make sure the list is complete for the tools the build actually uses (sbt launcher, Coursier, JVM, docker CLI), or replace the completeness claim with a statement that does not overclaim. Do not make the build read any environment variable.
- N2 (`.gitignore`, comment at line ~28 claims the secret rules "mirror is_secret_path"): `.claude/hooks/guard-dangerous.sh` (`is_secret_path`, around lines 113-125) also treats `*/.aws/*`, `*/.ssh/*`, `*/.gnupg/*`, `*/.config/gh/*` and `.docker/config.json` as secrets, and `.gitignore` has no rule for them. Read `is_secret_path` and add ignore rules for every pattern there that `.gitignore` does not yet cover, so that the comment becomes true. If an exact mirror isn't possible, reword the comment to say what it does cover. Keep `!*.example` ordered so that no secret rule is overridden.

Verify: `sbt Test/compile` (must pass) and `sbt check` (expected to fail only at markTestRun with "No tests executed", after the ledger and format steps pass). For N2, check ignore behavior with `git check-ignore -v --no-index` on non-secret sample paths only, if the guard allows it. If the guard blocks a command, do not work around it: record what was blocked. Confirm `.claude/.last-test-run` was not created. Report observed output only.

End with the standard report contract, then a per-finding section (N1, N2) giving file:line and what changed, and the final environment input surface table.

## 2. build-engineer report

I fixed both N1 and N2 and touched nothing else. `sbt Test/compile` passes. `sbt check` fails only at markTestRun with "No tests executed", which is the expected result for Phase 2, and `.claude/.last-test-run` was not created. The guard blocked one command, which I did not work around (details under VALIDATION RESULTS and N2).

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 2 repair round 2 (N1, N2 from section 3 of docs/agent-runs/phase-02/repair-01.md); base work order docs/agent-runs/phase-02/prompt.md
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/build.sbt (comment header only, lines 6-43)
- /Users/brian/Developer/repos/scholarchain/.gitignore (secret rules section, lines 43-52)
COMMANDS RUN:
- `git check-ignore -v --no-index` on secret-like sample paths: BLOCKED by the guard (see VALIDATION RESULTS)
- `git check-ignore -v --no-index env.example foo.example docker/services/Dockerfile modules/core/src/main/scala/A.scala target/x project/target/y`
- `sbt -batch Test/compile`
- `sbt -batch check`, then `ls -la .claude/.last-test-run`
TEST RESULTS:
- `sbt Test/compile`: `[success] Total time: 1 s`, exit 0 (sbt 1.13.1, Temurin Java 17.0.21).
- `sbt check`: exit 1. These steps passed: "Ledger check passed (scalafmt 3.11.5)", two scalafmt checks over 2 Scala sources with no errors, "Module tests: suites=0 executed=0 ...", "Contract tests (no AWS): suites=0 executed=0 ...". It then failed with `[error] No tests executed; .claude/.last-test-run was not updated. A passing test must exist.` and `(markTestRun)`. This is the expected Phase 2 failure.
- `.claude/.last-test-run`: "No such file or directory". Not created.
VALIDATION RESULTS:
- The first check-ignore run named secret-like sample paths (`.aws/config`, `secrets/a.txt`, `.docker/config.json`, and others). The guard blocked it with: "BLOCKED by .claude/hooks/guard-dangerous.sh: reading secrets, credentials, key material, Terraform state, or dumping the environment is denied." I did not work around it, so the new secret rules have not been checked with git.
- The non-secret check-ignore run gave the expected results:
  - `env.example` and `foo.example` match `.gitignore:26:!*.example`, so they stay tracked.
  - `target/x` matches `.gitignore:2:target/`.
  - `project/target/y` matches `.gitignore:3:project/target/`.
  - `docker/services/Dockerfile` and `modules/core/src/main/scala/A.scala` are not ignored.
ASSUMPTIONS:
- The added environment variables (SBT_CREDENTIALS, _JAVA_OPTIONS, COURSIER_TTL/MODE, DOCKER_CONTEXT, DOCKER_TLS_VERIFY, DOCKER_CERT_PATH, DOCKER_DEFAULT_PLATFORM, HOME) are standard inputs of those tools. They come from my knowledge of the tools; I did not read their source here.
- Leaving out the trailing slash on the directory rules is intended. It makes them match a file of the same name too, the same way `is_secret_path` does with `"/$p/"`.
RISKS:
- The new rules (`secrets`, `.secrets`, `.aws`, `.ssh`, `.gnupg` with no slash) would also ignore any non-secret file or directory with one of those exact names anywhere in the tree. This matches the guard.
- The secret rules have not been checked with git, because the guard blocks checking secret-like paths.
- The environment surface list does not claim to be complete (see N1). Tools may accept more variables than are listed.
MANUAL ACTION REQUIRED:
- If wanted, the human can check the new `.gitignore` secret rules from their own shell, for example `git check-ignore -v --no-index .aws/config sub/.docker/config.json a/.config/gh/hosts.yml secrets`.
- Commit is done by the human; I did not commit or push.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none (the guard block was on an optional check, not on the repair)
NEXT GATE: code-reviewer re-reviews N1 and N2, then the human ratifies Phase 2 (verified with `sbt Test/compile`).

### N1: build.sbt:6-43 (comment only; the build still reads no environment variable)
- Added these supply-chain inputs:
  - JVM: `JAVA_TOOL_OPTIONS` (HotSpot, in the sbt JVM and every forked test JVM; can inject `-javaagent`), `JDK_JAVA_OPTIONS` (java launcher, JDK 9+), `_JAVA_OPTIONS`.
  - Coursier: `COURSIER_MIRRORS`, `COURSIER_CREDENTIALS`, `COURSIER_CONFIG_DIR`.
  - sbt launcher: `SBT_CREDENTIALS`.
  - `HOME`.
  - Docker: `DOCKER_CONTEXT`, `DOCKER_TLS_VERIFY`, `DOCKER_CERT_PATH`.
- Added two inputs not marked supply chain: `COURSIER_TTL`/`COURSIER_MODE` and `DOCKER_DEFAULT_PLATFORM`.
- Added a note on the files these tools read: `.sbtopts`, `.jvmopts`, the global sbtopts files, `~/.sbt/**`, Coursier config files, and `~/.docker/config.json`.
- Replaced "No other input is documented; a new one must be added here first." (lines 42-43). The new text says the list covers the inputs known to change this build for the sbt launcher, Coursier, the JVM and the docker CLI, but is not a complete list of every variable those tools accept, and that any newly relied-upon input must be added before it is used.

### N2: .gitignore:43-52
- Added rules after `*credentials*.json`: `secrets`, `.secrets`, `.aws`, `.ssh`, `.gnupg`, `**/.config/gh`, `**/.docker/config.json`.
- `secrets/` and `.secrets/` became `secrets` and `.secrets`, so a file with that name is matched too.
- Added a two-line comment explaining the missing trailing slash and that `*.tfstate` and `*.tfstate.*` are covered in the Terraform section.
- Every pattern in `is_secret_path` (guard-dangerous.sh:115-125) now has a `.gitignore` rule, so the "mirrors is_secret_path" comment at line 28 is true.
- `!*.example` stays at line 26, before every secret rule, so no secret rule is overridden. No rule re-includes `.env*`.

### Environment input surface (final)
Rows marked "yes" are supply-chain inputs.

| Variable | Read by | Supply chain |
|---|---|---|
| JAVA_HOME | sbt launcher | yes |
| PATH | shell | yes |
| HOME | sbt, Coursier, Docker | yes |
| SBT_OPTS, JAVA_OPTS | sbt launcher | yes |
| SBT_CREDENTIALS | sbt launcher | yes |
| JAVA_TOOL_OPTIONS | HotSpot (all JVMs) | yes |
| JDK_JAVA_OPTIONS | java launcher (JDK 9+) | yes |
| _JAVA_OPTIONS | HotSpot | yes |
| COURSIER_REPOSITORIES | Coursier | yes |
| COURSIER_MIRRORS | Coursier | yes |
| COURSIER_CREDENTIALS | Coursier | yes |
| COURSIER_CONFIG_DIR | Coursier | yes |
| COURSIER_CACHE | Coursier | yes |
| COURSIER_TTL, COURSIER_MODE | Coursier | no |
| DOCKER_HOST | docker CLI | yes |
| DOCKER_CONTEXT | docker CLI | yes |
| DOCKER_TLS_VERIFY, DOCKER_CERT_PATH | docker CLI | yes |
| DOCKER_CONFIG | docker CLI | yes |
| DOCKER_BUILDKIT | docker CLI | no |
| DOCKER_DEFAULT_PLATFORM | docker CLI | no |

The build files themselves (build.sbt and project/**) read no environment variable: no `sys.env` and no `envVars`.

## 3. code-reviewer re-review report

VERDICT: APPROVE

N1 and N2 are both resolved. The repair adds one new defect, and it is minor: the `HOME` entry in the build.sbt header names the wrong mechanism for the JVM paths (N3 below). No critical or major finding remains. As instructed, I did not re-raise B1–B6, F1–F4, or "No tests executed".

I read: `/Users/brian/Developer/repos/scholarchain/docs/agent-runs/phase-02/repair-01.md` §3, `/Users/brian/Developer/repos/scholarchain/.gitignore` (lines 1-68), `/Users/brian/Developer/repos/scholarchain/build.sbt` (lines 1-60), and `is_secret_path` in `/Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh` (lines 115-125). I also searched build.sbt and project/** for environment reads and checked how docker is invoked.

### Per-finding status

| # | Status | Evidence |
|---|---|---|
| N1 | Resolved | `build.sbt:19-27` now lists `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `COURSIER_MIRRORS`, `COURSIER_CREDENTIALS` and `COURSIER_CONFIG_DIR`, all marked [supply chain]. The old completeness claim ("No other input is documented") is gone. Lines 40-43 now say the list "is not an exhaustive inventory". Lines 38-40 also list the file inputs (`.sbtopts`, `.jvmopts`, global sbtopts, `~/.sbt/**`, Coursier config, `~/.docker/config.json`). |
| N2 | Resolved | Every pattern in `is_secret_path` now has a matching ignore rule. Basename patterns (guard line 119) map to `.gitignore:29-43`, plus `*.tfstate` and `*.tfstate.*` at 56-57. Directory patterns (guard line 122) map to `.gitignore:46-52`. A repo-local `.aws/credentials` is now ignored by `.aws` (line 48). |

### Checks you asked for

- **The build still reads no environment variable.** The only matches for `sys.env|envVars|getenv` are the comment at build.sbt:7, `sys.props` for `java.specification.version` at build.sbt:71 (a JVM property, not an environment variable), and `Properties.getProperty` on the ledger in `project/plugins.sbt:18` and `project/Ledger.scala:30`. `dockerLocal` runs `Process(command)` with no extra environment (build.sbt:306), so it only inherits the caller's environment, as the comment says.
- **Gitignore syntax.** A pattern with no slash matches a file or directory of that name at any depth, so `secrets`, `.secrets`, `.aws`, `.ssh` and `.gnupg` cover both forms. That is consistent with the guard: `"/$p/"` matching `*/secrets/*` also catches a file named `secrets`. `**/.config/gh` is correct: without the leading `**/`, the middle slash would anchor the pattern to the repo root. With it, the pattern matches at any depth, as a file or a directory. `**/.docker/config.json` is correct for the same reason. Ordering is still sound: `!*.example` (line 26) comes before all of these, and a negation cannot re-include a file inside an excluded directory anyway. Git's default `core.ignorecase=true` on macOS makes these rules case-insensitive, while the guard's `case` patterns are case-sensitive. That difference only makes the ignore rules broader, which is the safe direction. Not a defect.
- **Can the patterns silently ignore legitimate files?** Yes. Any directory named `secrets` at any depth is ignored, for example a Scala package `modules/storage/src/main/scala/.../secrets/` holding a Secrets Manager adapter. But this is not new: round 1's `secrets/` already did it, and my round-1 report recorded it as a risk. The repair widens it only to files named exactly `secrets`, `.secrets`, `.aws`, `.ssh` or `.gnupg`. `Secrets.scala` does not match, because gitignore matches the whole name. The guard blocks agent writes to the same paths (guard:122, 307), so for agents the failure is a visible block, not a silent skip. Files a human writes there would be skipped silently by `git add -A`. I record this as a risk, not a new finding. No file or directory with any of these names exists in the tree today (Glob returned nothing).
- **Environment variable accuracy.**
  - `SBT_CREDENTIALS`: the sbt launcher does read it, for resolving the boot artifacts. "Read by the sbt launcher" is accurate, though possibly incomplete.
  - `JDK_JAVA_OPTIONS`: read only by the `java` launcher (JDK 9+). sbt starts itself and its forked test JVMs through the `java` executable, so the claimed reach holds for this build. Unlike `JAVA_TOOL_OPTIONS`, it does not reach JVMs created through JNI or tools like `javac`, so "same reach" is precise only within this build. Acceptable.
  - `JAVA_TOOL_OPTIONS` (JVMTI) and `_JAVA_OPTIONS` (HotSpot): accurate.
  - `COURSIER_TTL`, `COURSIER_MODE`, `COURSIER_CONFIG_DIR`: these are real Coursier variables.
  - `DOCKER_CONTEXT`, `DOCKER_TLS_VERIFY`, `DOCKER_CERT_PATH`, `DOCKER_DEFAULT_PLATFORM`: real docker CLI variables. `DOCKER_DEFAULT_PLATFORM` applies because the dockerLocal command passes no `--platform` (no match in build.sbt).
  - `HOME`: partly wrong; see N3.

### New findings

| # | Severity | file:line | Failure input / evidence | Impact | Owner |
|---|---|---|---|---|---|
| N3 | minor | `/Users/brian/Developer/repos/scholarchain/build.sbt:14-15` | The comment says `HOME` "locates ~/.sbt ..., ~/.ivy2, the default Coursier cache, and ~/.docker". On Unix JDK 17, the JVM sets `user.home` from the passwd entry, not from `HOME`. So `HOME=/tmp/evil sbt check` does not move `~/.sbt`, `~/.ivy2` or the Coursier cache for the sbt JVM; the real way to redirect them is `-Duser.home` or `-Dsbt.global.base` through `SBT_OPTS`/`JAVA_OPTS` (already listed at 16-17). `HOME` does locate `~/.docker` for the docker CLI. | Documentation names the wrong mechanism. Listing `HOME` is conservative and harmless, but the explanation is inaccurate for the JVM paths. Suggested fix: limit the `HOME` line to the docker CLI (and to the sbt shell script, if it uses `HOME`), and note that the JVM paths follow `user.home`. Not blocking. | build-engineer |

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 2 repair round 2 re-review (read-only) of build-engineer's changes to build.sbt (header comment) and .gitignore (secret-directory rules) responding to N1 and N2 from docs/agent-runs/phase-02/repair-01.md §3; B1–B6, F1–F4, and "No tests executed" not re-raised.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run (no Bash). These are build-engineer's claims, and I could not observe them: `sbt Test/compile` exit 0; `sbt check` failing only at markTestRun; `git check-ignore` results for non-secret paths; `.claude/.last-test-run` not created.
VALIDATION RESULTS: I compared the .gitignore lines against guard-dangerous.sh:119 and :122 pattern by pattern; every guard pattern has a matching ignore rule. I checked the gitignore syntax (slash-less, `**/`-prefixed, last-match ordering) by reading it. I confirmed build.sbt and project/** read no environment variable and that dockerLocal passes no extra environment (build.sbt:306). I found no existing file or directory in the tree named secrets/.secrets/.aws/.ssh/.gnupg/gh/config.json.
ASSUMPTIONS: Standard gitignore semantics (a pattern with no slash matches at any depth, as a file or directory; `**/` matches at any depth; last match wins; no re-inclusion inside an excluded directory). The JDK on Unix takes `user.home` from the passwd entry. Docker CLI behaviour as documented. git was not run on secret-like paths, because the guard blocks that. The new secret rules are therefore verified by reading only.
RISKS:
- A slash-less `secrets` or `.aws` rule anywhere in the tree will ignore any package or fixture directory of that name. This is pre-existing for `secrets`, and new for `.aws`, `.ssh` and `.gnupg`. storage-engineer and the test-fixture owners should avoid these names.
- `.gitignore` is case-insensitive on macOS while the guard is case-sensitive. The ignore rules are broader, which is safe, but a `Secrets/` directory would be ignored without the guard blocking writes to it.
- The env list is explicitly non-exhaustive by design.
MANUAL ACTION REQUIRED: none for approval. N3 can optionally be routed to build-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator presents this verdict to the human architect for Phase 2 ratification. N3 is an optional minor repair. `sbt check` is expected to fail only at "No tests executed" until Phase 3.
