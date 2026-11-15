STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: docs/agent-runs/phase-07/prompt.md, "Human architect decisions -> 3. Build: AWS SDK (build-engineer)", under that file's "Standing rules"
FILES CREATED: none
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/project/build.properties: new ledger key `version.awssdk=2.59.1`, with a comment, in the Libraries section.
- /Users/brian/Developer/repos/scholarchain/project/Ledger.scala: new `lazy val awssdk = version("version.awssdk")`, also added to `validateAll()`. The key is checked like every other key (exact version, no floating values).
- /Users/brian/Developer/repos/scholarchain/build.sbt: in the Dependencies section, new `awsSdkHttpExclusions`, `awsSdk(artifact)`, `awsSdkHttpClient`, `awsSdkStorage` and `awsSdkRag`. `storage` gets `libraryDependencies ++= awsSdkStorage` and `rag` gets `libraryDependencies ++= awsSdkRag`. Nothing else in the build changed.
COMMANDS RUN:
1. `sbt -batch storage/update rag/update services/update contractTests/update <project>/evicted ... export <project>/Runtime/fullClasspath` for all 9 projects. Exit 0; artifacts downloaded from Maven Central. This run showed that SDK 2.59.1 also pulls in `apache5-client` (httpclient5 5.6.4, httpcore5 5.4.3, httpcore5-h2 5.4.3), so I added it to the exclusions.
2. `sbt -batch <project>/evicted ... export {storage,rag}/{Runtime,Test}/fullClasspath, services/Runtime, contractTests/Test` (after the exclusion). Exit 0.
3. `sbt -batch check` (background). Exit 0, `[success] Total time: 732 s`, finished 2026-11-15 09:37:09 local time.
4. Read-only only otherwise: cat, grep, tail, sed -n, wc, cut, sort, uniq, `git status --short`, `git diff --stat`.
TEST RESULTS (from `sbt check` output):
- `Ledger check passed (scalafmt 3.11.5).` The scalafmt sbt and source checks passed in every project, and Test/compile passed in every project.
- `Module tests: suites=51 executed=1158 passed=1158 failed=0 errors=0 skipped=0`
- `Contract tests (no AWS): suites=22 executed=336 passed=336 failed=0 errors=0 skipped=0`
- `Updated /Users/brian/Developer/repos/scholarchain/.claude/.last-test-run (module_tests=1158, contract_tests=336)`, written by markTestRun. The marker now reads timestamp=2026-11-15T15:37:09.369928Z.
- No `[error]` or `[warn]` lines appeared in the check log. No `RequiresAws` test ran and `awsTest` was never run.
VALIDATION RESULTS:
- Only `url-connection-client` remains as an HTTP client. No storage, rag, services or contractTests classpath contains netty-nio-client, any io.netty jar, apache-client, apache5-client, httpclient, httpclient5 or httpcore5. aws-crt is absent too; `crt-core` is only the small SDK SPI jar with no native code.
- The boundaries hold. The exported Runtime classpaths of `core`, `core2_13`, `hadoop`, `entityResolution` and `sparkAnalytics` contain only the Scala library and upickle/ujson/upack/upickle-core/upickle-implicits/geny. They have no software.amazon.awssdk artifact, so they are unchanged.
- `services` and `contractTests` get the SDK only transitively through storage and rag, as CLAUDE.md allows. Nothing new depends on services, and there are no new inter-project edges.
- Every SDK version comes from the ledger (`Ledger.awssdk`); the build has no literal version strings.
ASSUMPTIONS:
- `url-connection-client` (JDK HttpURLConnection) is the one lightweight HTTP client allowed by decision 3. It has no Netty and no Apache HttpClient. With apache-client and apache5-client excluded, it is the only `SdkHttpService` on the classpath. Adapters may also pass `UrlConnectionHttpClient.builder()` explicitly.
- I set the exclusions on each dependency (`excludeAll`), not with a project-wide `excludeDependencies`. Per-dependency exclusions carry into the dependency descriptors, so they also apply in services and contractTests; the classpaths above confirm this.
- sbt has no native BOM import, so I pinned each artifact at `version.awssdk`. Every SDK jar resolved to 2.59.1.
RISKS:
- Async clients can't be used. netty-nio-client is excluded, so any `*AsyncClient` (for example S3AsyncClient or Bedrock streaming responses) fails at runtime unless an async HTTP client is added later as a build change. Owners must use the synchronous clients.
- url-connection-client limits: no HTTP/2, a basic connection pool, and no `Expect: 100-continue` tuning. That is fine for bounded batch loads, but large S3 multipart uploads may be slower than with the Apache client.
- In `storage` and `rag` alone, slf4j-api 1.7.36 has no binding, so SDK logging is a no-op there (SLF4J may print a one-time NOP notice in tests). `services` already provides logback at Runtime.
- Packaging is not verified. I did not run `services/assembly`. SDK jars ship same-path resources (for example `software/amazon/awssdk/global/handlers/execution.interceptors` and `META-INF/services/*`), and the current merge strategy may reject or deduplicate them wrongly. This is outside this work order's scope ("no other build change"); see proposed later.
- Scala 3 compatibility: the SDK is pure Java, so it has no cross-version issue. Spark and Hadoop are untouched.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer (decision 9) and the human gate. The next agent in order is domain-engineer (decision 4).

RESOLVED ARTIFACTS (storage and rag Runtime classpaths, observed after the exclusion)
- Declared in storage: software.amazon.awssdk s3, dynamodb, s3vectors, neptunedata, sqs, url-connection-client, all 2.59.1
- Declared in rag: software.amazon.awssdk bedrockruntime and url-connection-client, both 2.59.1 (plus storage's set, transitively)
- Transitive software.amazon.awssdk artifacts, all 2.59.1: annotations, arns, auth, aws-core, aws-json-protocol, aws-query-protocol, aws-xml-protocol, checksums, checksums-spi, crt-core, endpoints-spi, eventstream, http-auth, http-auth-aws, http-auth-aws-eventstream, http-auth-spi, http-client-spi, identity-spi, json-utils, metrics-spi, profiles, protocol-core, regions, retries, retries-spi, sdk-core, third-party-jackson-core (shaded Jackson), utils, utils-lite
- Other transitive: org.reactivestreams reactive-streams 1.0.4 and org.slf4j slf4j-api 1.7.36 (2.0.19 in services and contractTests)
- Excluded, so absent everywhere: software.amazon.awssdk netty-nio-client (and all io.netty), apache-client (httpclient 4, httpcore 4, commons-codec), apache5-client (httpclient5 5.6.4, httpcore5 5.4.3, httpcore5-h2 5.4.3)

EVICTION WARNINGS
- services and contractTests: `org.slf4j:slf4j-api:2.0.19 is selected over {1.7.36}`. The SDK's utils, sdk-core and regions ask for 1.7.36; services (and logback-classic 1.6.6) need 2.0.19. slf4j 2.x keeps binary compatibility with 1.7 API callers, so this is safe. It is not suppressed.
- Informational, not new: `org.scala-lang:scala3-library_3:3.3.9 is selected over {3.3.1, 3.3.7}` (upickle and geny). This was already the case before the change.
- No eviction among software.amazon.awssdk artifacts; all resolved to 2.59.1.
- The `sbt check` run itself printed no `[warn]` lines.

VERSION LEDGER (changes only): added `version.awssdk=2.59.1`. All other values are unchanged.

MODULE DEPENDENCY GRAPH: unchanged. core, core2_13 (no internal dependency); hadoop, entityResolution and storage depend on core; sparkAnalytics on core2_13; rag on core and storage; services on core, rag and storage; contractTests on core, hadoop, entityResolution, storage, rag and services. The new external dependencies are only on storage (AWS SDK data-store clients and SQS) and rag (bedrockruntime).

ENVIRONMENT VARIABLES READ BY THE BUILD: unchanged. build.sbt and project/** still read no environment variables. The new artifacts come from the default Maven Central resolver, subject to the COURSIER_* inputs already documented in the build.sbt header.

PACKAGING CHOICES: unchanged. assembly settings and docker were not touched.

PROPOSED LATER (not implemented)
- Check `services/assembly` with the SDK on the classpath. If it fails, add a `MergeStrategy.concat` or filterDistinctLines rule for `software/amazon/awssdk/**/execution.interceptors` and `META-INF/services/**`. This needs its own build work order.
- Only if an async or streaming client is ever needed: decide on an async HTTP client then (this would bring Netty back).
