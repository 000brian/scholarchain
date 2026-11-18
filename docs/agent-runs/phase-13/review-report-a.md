STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-13/prompt.md (sha256 60c943dccc86a6dc58ec074b95bbd63f04e2adca2f7a1c9a9abad266f28d36a7), session 2, review R-1 of step 1 (build-engineer, decision 5 items 1-12)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not re-run (I have no Bash). Static corroboration of the builder's claims: `.claude/.last-test-run` reads module_tests=2005, contract_tests=398 (timestamp 2026-11-17T20:06:53Z), which matches the report. `modules/services/target/docker-context/` holds exactly app.jar, config/course.json and context.sha256, and context.sha256 has two lines in shasum -c format. The generated `modules/hadoop/target/scala-3.3.9/resource_managed/main/scholarchain-build-info.properties` has the 4 keys in order: buildCommit=80a3606…, buildDirty=true, module=hadoop, version. The packageCheck output ("only StorageLoadMain missing") is the builder's claim. I could not re-run it; I checked the code paths behind it instead.
VALIDATION RESULTS: all 12 items of decision 5 checked against the code (table below). No critical or major finding. Four minor findings.
ASSUMPTIONS: sbt-assembly's `assembly / packageOptions` takes `Compile/packageBin/packageOptions` (which can hold a discovered Main-Class) when `assembly / mainClass` is None. On that basis the extra filterNot at build.sbt:323 is needed and correct. sbt tasks are evaluated at most once per command and are not cached across invocations.
RISKS: It is still unverified that the runtime base digest is a multi-platform index that includes linux/arm64. The human checks this in package.sh. The packageCheck detectors other than the missing-class check have never been seen to fail (B-02).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Step 8 triage takes the minor findings (none is interface: yes, so nothing holds up step 3). Step 3 may start after R-1.

VERDICT: APPROVE

## Coverage

Read in full:
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/81476c99-8e4e-4aa9-aabf-b889baacaf6c/scratchpad/phase13-reports/diff-R-1.txt
- /Users/brian/Developer/repos/scholarchain/build.sbt
- /Users/brian/Developer/repos/scholarchain/project/BuildIdentity.scala
- /Users/brian/Developer/repos/scholarchain/project/DockerContext.scala
- /Users/brian/Developer/repos/scholarchain/project/PackageCheck.scala
- /Users/brian/Developer/repos/scholarchain/project/PromptResources.scala
- /Users/brian/Developer/repos/scholarchain/docker/services/Dockerfile
- /Users/brian/Developer/repos/scholarchain/.gitignore
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/81476c99-8e4e-4aa9-aabf-b889baacaf6c/scratchpad/phase13-reports/agent-report-build.md

Read in part (context only, outside the slice):
- prompt.md lines 1-361 and 440-469 (identity rules, decisions 4, 5 and 11, 12)
- modules/rag/src/main/scala/org/scholarchain/rag/runtime/Prompts.scala:110-194
- infra/terraform/modules/runtime/main.tf:140-219
- A9 (docs/agent-runs/phase-07/human-gate.md:125,149), checked with Grep
- D-07 (docs/agent-runs/phase-11/review-report-d.md:28, human-gate.md:186), checked with Grep
- Open reviewer risks (docs/agent-runs/phase-12/orchestrator-summary.md:78-83), checked with Grep
- config/course.json, Grep for identifiers only (none found)

Slice files not read: none. project/Ledger.scala, project/TestGate.scala and project/plugins.sbt are unchanged and outside the slice. I checked plugins.sbt with Grep only.

## Decision 5 checklist

1. `evidence/` in .gitignore: done, .gitignore:18.
2. Config in the image: done.
   - Dockerfile:21-23 creates /opt/scholarchain/config, owned by root, mode 0755.
   - Dockerfile:27 copies course.json with `--chown=root:root --chmod=0444`.
   - The path matches infra/terraform/modules/runtime/variables.tf:178.
3. Image build split: done.
   - build.sbt:412-420 and DockerContext.scala:29-38: dockerStage deletes the context and stages exactly app.jar (the services/assembly output), config/course.json and context.sha256. It runs no Docker.
   - build.sbt:422-436: dockerBuildLocal uses dockerStage and the same arguments.
   - The buildx argument list is at DockerContext.scala:41-56:
     `docker buildx build --platform linux/arm64 --load --file <Dockerfile> --build-arg JAVA_RUNTIME_IMAGE=<ledger> --tag <tag> <context>`
     It matches the builder's handoff and the Dockerfile header (Dockerfile:5-8).
4. arm64 explicit: done, DockerContext.scala:19 and :46-47. The DOCKER_DEFAULT_PLATFORM comment at build.sbt:41 is accurate.
5. No HEALTHCHECK: deviation (planned), Dockerfile:11-12.
6. Build identity: done, BuildIdentity.scala:24-34. The path list equals the prompt's list exactly.
   - buildDirty (BuildIdentity.scala:58-64) runs `git --no-optional-locks status --porcelain --untracked-files=normal -- PATHS`. Any output means dirty, and a git failure also means dirty.
   - When there is no commit or no git: `unknown`/dirty (line 65). There is no timestamp.
   - Freshness:
     - The plain task `ThisBuild / buildIdentity` (build.sbt:214-216) is evaluated once per invocation.
     - `generate` rewrites the file only when its content changed (BuildIdentity.scala:73-77).
     - The generator is wired at build.sbt:243-251, in the four assembled projects only, so app.jar holds one copy.
     - The generated file shows the correct build commit for the current HEAD history.
7. A9 and D-07: done.
   - build.sbt:235-241 discards module-info. META-INF/services/** and every `execution.interceptors` use filterDistinctLines; everything else falls back to the default.
   - Apache, Apache5 and Netty are excluded per SDK dependency (build.sbt:276-282) and forbidden in packageCheck (PackageCheck.scala:39-46).
   - D-07 is at build.sbt:450.
8. Hadoop jar has no Main-Class: done.
   - build.sbt:322-323 sets `assembly / mainClass := None` and filters `Package.MainClass` out of packageOptions.
   - packageCheck asserts it at PackageCheck.scala:200-201.
9. R3-06: done.
   - ClasspathDir (PromptResources.scala:17) equals Prompts.ResourceRoot (Prompts.scala:132) without the leading slash.
   - The generator is at build.sbt:375-380. Copies are filtered out of unmanagedResources (build.sbt:381-384), so one file exists per classpath path.
   - packageCheck fails on a copy that differs or has no source (PackageCheck.scala:162-173).
   - The 5 source files match the 5 copies by name.
   - Schemas are read only from the file system (Prompts.scala:188-189), so not mapping them is correct.
10. packageCheck class list: done, PackageCheck.scala:18-33. It is exactly decision 5.10, and each list is checked in its own jar (lines 199-223).
    - Spark jar: Spark, Hadoop and `scala/` are forbidden (line 36).
    - Services jar: Main-Class, logback.xml, byte-equal prompts, merged lists against every classpath input, UrlConnectionSdkHttpService registration, no other HTTP clients.
    - Build info is checked in all four jars.
    - The task is root only and not aggregated (build.sbt:506), and depends on the four assemblies (build.sbt:512-516).
    - The stale check (BuildIdentity.scala:109-114) is stricter than "well formed". It is consistent within one invocation and acceptable.
11. Commands run: reported by the builder. The artifacts listed under TEST RESULTS corroborate the report.
12. .gitignore review: done, with one fix (`!experiments/**/*.log`, `!releases/**/*.log`, lines 14-15).
    - Ignored as required: target/ (so docker-context), evidence/, plans/ (line 76), .terraform/, *.tfvars with course.tfvars re-included (line 75).
    - See B-04 for a residual edge.

Other checks:
- Input-surface comment: still true that build.sbt and project/** read no environment variable themselves (Grep for sys.env, getenv, envVars, System.getProperty in project/: none). The git list is incomplete (B-01).
- No secrets, account IDs, bucket names or ARNs in any slice file. config/course.json has none.
- Ownership: every changed file is in build-engineer's territory.

## Findings

| ID | Severity | Owner | Interface | File:line | Failure input and evidence | Impact |
|---|---|---|---|---|---|---|
| B-01 | minor | build-engineer | no | build.sbt:14-19, 42-47 | The new git entries name GIT_DIR … GIT_CONFIG_NOSYSTEM, but leave out other git inputs: HOME and XDG_CONFIG_HOME (global ~/.gitconfig; the HOME line lists only ~/.sbt, ~/.ivy2, Coursier and ~/.docker), GIT_CONFIG_COUNT/GIT_CONFIG_KEY_n/GIT_CONFIG_VALUE_n, GIT_CONFIG_PARAMETERS, GIT_OBJECT_DIRECTORY/GIT_COMMON_DIR. The file list (lines 42-44) does not name ~/.gitconfig or .git/config. Example: a global `core.excludesFile` that ignores an untracked file under modules/ makes buildDirty=false (BuildIdentity.scala:58-64). | The input-surface comment is not fully true for the new git input. The result is fail-safe for package.sh, but the documented surface is incomplete. |
| B-02 | minor | build-engineer | no | project/PackageCheck.scala:83-88, 104-140, 145-176, 200-201; project/BuildIdentity.scala:80-117 | No test exercises the negative paths: a hadoop Main-Class, a forbidden prefix, an unmerged service line, a differing or extra prompt, malformed or stale build info. Only `requireClasses` has been seen to fail (StorageLoadMain). The builder lists this under "Proposed later". | A detector bug could let a bad jar pass the gate unnoticed (checklist area 10). |
| B-03 | minor | build-engineer | no | project/BuildIdentity.scala:14-18; project/DockerContext.scala:9-11 | The formatter collapsed the scaladoc key and layout lists into run-on text. Example: "…`unknown` when git is unavailable or no commit matches buildDirty `true` when … module the sbt project id of the assembled project version the build version". | The documentation of the interface resource is hard to read. The code and the handoff report are correct. |
| B-04 | minor | build-engineer | no | .gitignore:13-15 vs 2, 64-66, 73-74 | Git applies the last matching pattern, so `crash.log` and `crash.*.log` (lines 73-74) re-ignore `experiments/**/crash.log` after the negation at line 14. `*.plan`, `tfplan` and `target/` also ignore matching names under experiments/** and releases/**. Example: `experiments/raw/<run>/crash.log` is ignored. | The comment at line 13 ("never ignored") overstates the rule. A committed raw result with one of these names would silently stay untracked. |

No critical findings. No major findings. No interface: yes findings, so no repair-00 is needed before step 3.
