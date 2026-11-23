package org.scholarchain.rag

import java.nio.file.{Files, Path, Paths}

import org.scholarchain.core.config.EmbeddingProvider
import org.scholarchain.rag.ingest.RagIngestJob
import org.scholarchain.rag.semantic.ExportDocuments
import org.scholarchain.storage.source.{LocalDirectorySource, RejectLog}

class RagIngestJobSuite extends munit.FunSuite {
  import RagFixtures._

  private def configPath(name: String): Path =
    Paths.get(getClass.getResource(s"/config/$name").toURI)

  private def runJob(
      runs: Int = 2,
      root: Path = Files.createTempDirectory("rag-job")
  ): (ujson.Obj, Path) = {
    val dir = writeExport(root.resolve("export"))
    val out = root.resolve("attempt-01")
    val opts = RagIngestJob
      .parse(
        Array(
          "--config",
          configPath("rag-local-v1.json").toString,
          "--export",
          dir.toString,
          "--out",
          out.toString,
          "--code-version",
          TestCodeVersion,
          "--runs",
          runs.toString,
          "--samples-per-index",
          "10"
        )
      )
      .fold(fail(_), identity)
    (RagIngestJob.run(opts, hooks = Hermetic), out)
  }

  test("two local runs: the second run skips 100% and changes nothing") {
    val (r, out) = runJob()
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    val runs = r("runs").arr
    assertEquals(runs.size, 2)
    val first = runs(0)("ingest")
    val second = runs(1)("ingest")
    assertEquals(first("input").num, 15.0)
    assertEquals(first("written").num, 15.0)
    assertEquals(first("rejected").num, 0.0)
    assertEquals(second("input").num, 15.0)
    assertEquals(second("skippedUnchanged").num, 15.0)
    assertEquals(second("skipRatio").num, 1.0)
    assertEquals(second("written").num, 0.0)
    assertEquals(second("embedCalls").num, 0.0)
    assertEquals(second("putCalls").num, 0.0)
    assertEquals(r("secondRunSkipRatio").num, 1.0)
    assertEquals(r("renderDeterministic").bool, true)
    assertEquals(runs(0)("corpusDigest").str, runs(1)("corpusDigest").str)
    assertEquals(
      r("indexCounts").obj.toMap.view.mapValues(_.num.toInt).toMap,
      Map(
        "researcher-profile-v1" -> 6,
        "collaboration-community-v1" -> 3,
        "paper-abstract-v1" -> 4,
        "collaboration-path-v1" -> 2,
        "topic-institution-summary-v1" -> 0
      )
    )
    val t1 = runs(0)("templates")
    assertEquals(t1("spark-researcher-profile/graph-v1")("sparkVerbatimHashMatches").num, 6.0)
    assertEquals(t1("spark-community/graph-v1")("sparkVerbatimHashMatches").num, 3.0)
    assertEquals(t1("spark-path/graph-v1")("sparkVerbatimHashMatches").num, 2.0)
    List("spark-researcher-profile/graph-v1", "spark-community/graph-v1", "spark-path/graph-v1")
      .foreach(k => assertEquals(t1(k)("sparkVerbatimHashMismatches").num, 0.0, k))
    assertEquals(r("rejects")("render").num, 0.0)
    assertEquals(r("rejects")("ingest").num, 0.0)
    assertEquals(r("label").str, "local fake, not representative")
    assert(Files.isRegularFile(out.resolve("run-report.json")))
    val smoke = r("retrievalSmoke").obj
    List("researcher-profile-v1", "collaboration-community-v1", "collaboration-path-v1").foreach {
      i =>
        assertEquals(smoke(i)("top1IsSelf").num, smoke(i)("samples").num, i)
        assertEquals(smoke(i)("top1IsSelfWithinOwnWindow").num, smoke(i)("samples").num, i)
        assertEquals(
          smoke(i)("top1TextFetchedByPointerMatchesHash").num,
          smoke(i)("samples").num,
          i
        )
    }
    assertEquals(
      r("embedding")("documentModelSpec").str,
      "fake/fake-lexical-hash-v1/1024/document/emb-v1"
    )
    val templates = r("templates").arr.map(t => t("templateId").str -> t("source").str).toMap
    assert(templates("topic-institution-summary-v1").startsWith("no input (0 documents)"))
  }

  test("an existing output root is refused (a new root per attempt)") {
    val root = Files.createTempDirectory("rag-job")
    Files.createDirectories(root.resolve("attempt-01"))
    intercept[RuntimeException](runJob(root = root))
  }

  test("the fake provider is refused for non-local stores; live providers are refused locally") {
    assert(RagIngestJob.checkProviderAndStores(EmbeddingProvider.Fake, "local").isRight)
    assert(
      RagIngestJob
        .checkProviderAndStores(EmbeddingProvider.Fake, "aws")
        .left
        .exists(_.contains("never used for a stored index"))
    )
    assert(
      RagIngestJob
        .checkProviderAndStores(EmbeddingProvider.Voyage, "local")
        .left
        .exists(_.contains("live API"))
    )
    assert(RagIngestJob.checkProviderAndStores(EmbeddingProvider.Bedrock, "aws").isLeft)
    // Phase 9 (A1): voyage is the one provider for AWS stores
    assert(RagIngestJob.checkProviderAndStores(EmbeddingProvider.Voyage, "aws").isRight)
    assert(RagIngestJob.checkProviderAndStores(EmbeddingProvider.Voyage, "s3").isLeft)
  }

  test(
    "A1: --stores aws runs the voyage provider through the AWS stores factory (stub stores, stub transport)"
  ) {
    import org.scholarchain.rag.embedding.{FakeLexicalEmbedder, HttpResult, HttpTransport}
    import org.scholarchain.storage.local.LocalStorageEnvironment
    val root = Files.createTempDirectory("rag-job-aws")
    val dir = writeExport(root.resolve("export"))
    var transportCalls = 0
    val stub = new HttpTransport {
      def postJson(uri: java.net.URI, headers: List[(String, String)], body: String): HttpResult = {
        transportCalls += 1
        assert(headers.exists(_._2 == "Bearer test-key-not-real"))
        val inputs = ujson.read(body)("input").arr.map(_.str)
        val data = inputs.zipWithIndex.map { case (t, i) =>
          ujson.Obj(
            "index" -> i,
            "embedding" -> ujson.Arr.from(
              FakeLexicalEmbedder.vector(t, 1024).map(f => ujson.Num(f.toDouble))
            )
          )
        }
        HttpResult(
          200,
          ujson
            .Obj("data" -> ujson.Arr.from(data), "usage" -> ujson.Obj("total_tokens" -> 10))
            .render(),
          None
        )
      }
    }
    val requested = scala.collection.mutable.ListBuffer.empty[String]
    val factory: (
        org.scholarchain.storage.StorageConfig,
        String,
        Path
    ) => Either[String, RagIngestJob.IngestStores] =
      (cfg, stores, _) => {
        requested += stores
        Right(
          new RagIngestJob.LocalIngestStores(
            LocalStorageEnvironment.create(cfg, None),
            kind = "aws"
          )
        )
      }
    val wiring = org.scholarchain.rag.embedding.EmbeddingProviders.Wiring(
      env = Map("SCHOLARCHAIN_VOYAGE_API_KEY" -> "test-key-not-real").get,
      httpTransport = () => stub
    )
    val opts = RagIngestJob
      .parse(
        Array(
          "--config",
          configPath("rag-voyage-example-v1.json").toString,
          "--export",
          dir.toString,
          "--out",
          root.resolve("attempt-01").toString,
          "--code-version",
          TestCodeVersion,
          "--runs",
          "1",
          "--samples-per-index",
          "3",
          "--stores",
          "aws"
        )
      )
      .fold(fail(_), identity)
    val r = RagIngestJob.run(opts, factory, wiring, Hermetic)
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    assertEquals(requested.toList, List("aws"))
    assertEquals(r("environment")("stores").str, "aws")
    assertEquals(r("embedding")("provider").str, "voyage")
    assertEquals(r("runs")(0)("ingest")("written").num, 15.0)
    assert(transportCalls > 0)
    assert(!r.render().contains("test-key-not-real"), "the key never reaches the report")
  }

  test("A1: --stores aws with the fake provider is refused before any store is built") {
    val root = Files.createTempDirectory("rag-job-aws-fake")
    val dir = writeExport(root.resolve("export"))
    var built = 0
    val opts = RagIngestJob
      .parse(
        Array(
          "--config",
          configPath("rag-local-v1.json").toString,
          "--export",
          dir.toString,
          "--out",
          root.resolve("a").toString,
          "--code-version",
          TestCodeVersion,
          "--stores",
          "aws"
        )
      )
      .fold(fail(_), identity)
    val r = RagIngestJob.run(
      opts,
      (_, _, _) => { built += 1; Left("not built") },
      hooks = Hermetic
    )
    assert(r("status").str.contains("never used for a stored index"), r("status").str)
    assertEquals(built, 0)
  }

  test("a voyage configuration fails before any network use in a local run") {
    val root = Files.createTempDirectory("rag-job")
    val dir = writeExport(root.resolve("export"))
    val opts = RagIngestJob
      .parse(
        Array(
          "--config",
          configPath("rag-voyage-example-v1.json").toString,
          "--export",
          dir.toString,
          "--out",
          root.resolve("a").toString,
          "--code-version",
          TestCodeVersion
        )
      )
      .toOption
      .get
    val r = RagIngestJob.run(opts, hooks = Hermetic)
    assert(r("status").str.contains("live API"), r("status").str)
  }

  test("the smoke window filter uses the document's own window; papers have none") {
    val p = org.scholarchain.rag.semantic.TemplateRegistry
      .fromSparkSummary(
        "community",
        W1,
        communitySummaries.find(_._1 == W1).get._2,
        localConfig.vectors.get
      )
      .toOption
      .get
      .head
    assertEquals(
      RagIngestJob.windowFilter(p),
      Some(
        org.scholarchain.storage.ports.MetadataFilter.And(
          List(
            org.scholarchain.storage.ports.MetadataFilter
              .Eq("windowStartYear", org.scholarchain.storage.ports.MetaValue.Num(2021L)),
            org.scholarchain.storage.ports.MetadataFilter
              .Eq("windowEndYear", org.scholarchain.storage.ports.MetaValue.Num(2023L))
          )
        )
      )
    )
    val paper = p.copy(window = None)
    assertEquals(RagIngestJob.windowFilter(paper), None)
  }

  test("argument parsing") {
    val base = Array("--config", "c", "--export", "e", "--out", "o", "--code-version", "abc123")
    assert(RagIngestJob.parse(Array("--config", "c", "--export", "e")).isLeft)
    assert(RagIngestJob.parse(base ++ Array("--bogus", "1")).isLeft)
    assert(RagIngestJob.parse(base ++ Array("--runs", "9")).isLeft)
    assertEquals(RagIngestJob.parse(base).map(_.runs), Right(2))
    assertEquals(RagIngestJob.parse(base).map(_.stores), Right("local"))
    assertEquals(RagIngestJob.parse(base).map(_.codeVersion), Right("abc123"))
  }

  // ---- Phase 13 decision 7 and 7b ---------------------------------------------------------------

  test("decision 7: the option constant has exactly these names") {
    assertEquals(RagIngestJob.Required, List("config", "export", "out", "code-version"))
    // Phase 15 (G-27): --max-documents added for the D7 probe
    assertEquals(
      RagIngestJob.Optional,
      List("stores", "runs", "samples-per-index", "max-documents")
    )
  }

  test("decision 7: the parser accepts exactly the constant's names and refuses the rest") {
    val all = (RagIngestJob.Required ++ RagIngestJob.Optional).flatMap { n =>
      List(
        s"--$n",
        n match {
          case "stores"            => "aws"
          case "runs"              => "1"
          case "samples-per-index" => "3"
          case "max-documents"     => "5000"
          case "code-version"      => "0123456789abcdef0123456789abcdef01234567"
          case "export"            => "s3://example-graph-bucket/graph/exports/run=" + "0" * 32
          case other               => other
        }
      )
    }
    val ok = RagIngestJob.parse(all.toArray).fold(fail(_), identity)
    assertEquals(ok.codeVersion, "0123456789abcdef0123456789abcdef01234567")
    assertEquals(ok.exportLocation, "s3://example-graph-bucket/graph/exports/run=" + "0" * 32)
    // every required name is required
    RagIngestJob.Required.foreach { n =>
      val i = all.indexOf(s"--$n")
      val without = (all.take(i) ++ all.drop(i + 2)).toArray
      assertEquals(RagIngestJob.parse(without), Left(s"--$n is required"), n)
    }
    // unknown, repeated, positional and malformed values are refused
    val unknown = RagIngestJob.parse((all ++ List("--export-dir", "x")).toArray)
    assert(unknown.left.exists(_.contains("unknown options: --export-dir")), unknown.toString)
    val repeated = RagIngestJob.parse((all ++ List("--runs", "2")).toArray)
    assert(repeated.left.exists(_.contains("repeated options: --runs")), repeated.toString)
    assert(RagIngestJob.parse((all ++ List("runs", "2")).toArray).isLeft)
    assert(RagIngestJob.parse((all :+ "--runs").toArray).isLeft)
    val badCode = all.updated(all.indexOf("--code-version") + 1, "has space")
    assert(RagIngestJob.parse(badCode.toArray).left.exists(_.contains("--code-version")))
    val badStores = all.updated(all.indexOf("--stores") + 1, "s3")
    assertEquals(RagIngestJob.parse(badStores.toArray), Left("--stores must be local or aws"))
    // an s3:// export needs the AWS stores (the local stores hold no data lake)
    val s3Local = all.updated(all.indexOf("--stores") + 1, "local")
    assertEquals(RagIngestJob.parse(s3Local.toArray), Left("an s3:// export needs --stores aws"))
  }

  test(
    "decision 7: --code-version and the build identity are recorded; CodeVersion keeps its role"
  ) {
    val root = Files.createTempDirectory("rag-job-code")
    val dir = writeExport(root.resolve("export"))
    val opts = parseOk(localArgs(dir, root.resolve("a")))
    val identity = org.scholarchain.storage.load.BuildIdentity(
      "0123456789abcdef0123456789abcdef01234567",
      "false",
      "services",
      "0.1.0-SNAPSHOT",
      "resource"
    )
    val r = RagIngestJob.run(
      opts,
      hooks = RagIngestJob.Hooks(buildIdentity = () => identity, configEnv = NoConfigEnv)
    )
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    assertEquals(r("codeVersion").str, TestCodeVersion)
    assertEquals(r("jobCodeVersion").str, "rag/1")
    assertEquals(RagIngestJob.CodeVersion, "rag/1")
    assertEquals(r("build"), identity.toJson)
    // the run ID still derives from the job's CodeVersion, not from --code-version
    val other = parseOk(
      localArgs(dir, root.resolve("b")).updated(7, "fedcba9876543210fedcba9876543210fedcba98")
    )
    val r2 = RagIngestJob.run(other, hooks = Hermetic)
    assertEquals(r2("runId").str, r("runId").str)
    assertEquals(r2("codeVersion").str, "fedcba9876543210fedcba9876543210fedcba98")
    // in module tests the jar resource is absent: the identity says so, it is never invented
    assertEquals(r2("build")("buildCommit").str, "unknown")
    assertEquals(r2("build")("status").str, "missing")
    // local stores: no copy to the data lake
    assert(!Files.exists(root.resolve("a").resolve("run-report-copy.json")))
  }

  test("7b: an s3:// export with aws stores ingests like the local export; the report is copied") {
    val f = s3Fixture()
    val r = f.result.report
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    assertEquals(r("input")("exportKind").str, "s3")
    assertEquals(r("input")("export").str, s"s3://{data-bucket}/${f.exportKey}")
    assertEquals(r("runs")(0)("ingest")("written").num, 15.0)
    // the same export read from a local directory gives the same run ID and corpus
    val local = RagIngestJob.run(
      parseOk(
        Array(
          "--config",
          configPath("rag-voyage-example-v1.json").toString,
          "--export",
          f.localExport.toString,
          "--out",
          f.root.resolve("local").toString,
          "--code-version",
          TestCodeVersion,
          "--runs",
          "1",
          "--samples-per-index",
          "3",
          "--stores",
          "aws"
        )
      ),
      f.factory(),
      f.wiring,
      Hermetic
    )
    assertEquals(local("status").str, "completed", local.render(indent = 2))
    assertEquals(local("runId").str, r("runId").str)
    assertEquals(local("input")("exportManifestSha256").str, r("input")("exportManifestSha256").str)
    assertEquals(local("runs")(0)("corpusDigest").str, r("runs")(0)("corpusDigest").str)

    // the report copy: same bytes as <out>/run-report.json, at the logical key under rag/
    val localBytes = Files.readAllBytes(f.out.resolve("run-report.json"))
    val sha = org.scholarchain.core.Hashing.sha256Hex(localBytes)
    val runHex = r("runId").str.stripPrefix(org.scholarchain.core.RunId.Prefix)
    val key = s"${f.ragPrefix}/ingest-reports/run=$runHex/run-report-sha256=$sha.json"
    assertEquals(
      RagIngestJob
        .reportCopyKey(
          f.ragPrefix,
          org.scholarchain.core.RunId.parse(r("runId").str).toOption,
          sha
        ),
      key
    )
    val stored = f.env.objects
      .get(org.scholarchain.storage.ports.ObjectRef(f.ragBucket, key))
      .getOrElse(fail(s"no report copy at $key"))
    assertEquals(org.scholarchain.core.Hashing.sha256Hex(stored), sha)
    val copy = f.result.reportCopy.getOrElse(fail("no copy outcome"))
    assertEquals(copy("outcome").str, "created")
    assertEquals(copy("object").str, s"s3://{data-bucket}/$key")
    assertEquals(copy("sha256").str, sha)
    assert(f.result.copyOk)
    val copyFile = ujson.read(Files.readAllBytes(f.out.resolve("run-report-copy.json")))
    assertEquals(copyFile("outcome").str, "created")
    assert(f.printed.exists(_.contains(s"report copy: created s3://{data-bucket}/$key")))
  }

  test(
    "7b: no bucket name, account ID or key appears in the report, the copy record or the output"
  ) {
    val f = s3Fixture()
    assertEquals(f.result.report("status").str, "completed")
    val texts = List(
      new String(Files.readAllBytes(f.out.resolve("run-report.json")), "UTF-8"),
      new String(Files.readAllBytes(f.out.resolve("run-report-copy.json")), "UTF-8")
    ) ++ f.printed
    val cfg = storageConfigOf("rag-voyage-example-v1.json")
    val forbidden = cfg.zones.values.map(_.bucket).toSet + cfg.vectorBucket + "123456789012" +
      "test-key-not-real"
    texts.foreach(t => forbidden.foreach(b => assert(!t.contains(b), s"'$b' in: ${t.take(300)}")))
  }

  test(
    "7b: an s3:// export without its manifest fails before any ingest; the report is still copied"
  ) {
    val f = s3Fixture(skip = Set("_export_manifest.json"))
    val r = f.result.report
    assert(
      r("status").str.contains("export manifest _export_manifest.json is missing"),
      r("status").str
    )
    assert(!r.value.contains("runs"))
    // the failed attempt's report still reaches the data lake (no run ID: no-run)
    val copy = f.result.reportCopy.getOrElse(fail("no copy outcome"))
    assertEquals(copy("outcome").str, "created")
    assert(copy("object").str.contains(s"/ingest-reports/no-run/run-report-sha256="), copy.render())
  }

  test("7b: an s3:// export with a changed file fails the sha256 or size check") {
    val f = s3Fixture(changeFirstFile = true)
    val r = f.result.report
    assert(r("status").str.startsWith("failed"), r("status").str)
    assert(
      r("status").str.contains("sha256") || r("status").str.contains("bytes"),
      r("status").str
    )
  }

  test("7b: an s3:// export with a listed file missing is refused before any ingest") {
    val f = s3Fixture(skip = Set("FIRST_FILE"))
    val r = f.result.report
    assert(r("status").str.contains("missing export file"), r("status").str)
    assert(!r.value.contains("runs"))
  }

  test("7b: an s3:// export outside the configured data-lake buckets is refused") {
    val f = s3Fixture(bucket = "example-other-bucket")
    val r = f.result.report
    assert(
      r("status").str.contains("--export must be in a bucket of the configured data-lake zones"),
      r("status").str
    )
    assert(!r.render().contains("example-other-bucket"))
  }

  test(
    "P17-F-12: a store or SDK failure is recorded by its exception class only, never its message"
  ) {
    import org.scholarchain.storage.ports.{ObjectInfo, ObjectRef, ObjectStore, PutOutcome}
    import org.scholarchain.storage.local.LocalStorageEnvironment
    final class ProbeStoreFailure(msg: String) extends RuntimeException(msg)
    val leak = "probe-host-leak.invalid"
    val failing = new ObjectStore {
      private def boom: Nothing = throw new ProbeStoreFailure(s"unable to reach $leak")
      def putIfAbsent(
          ref: ObjectRef,
          bytes: Array[Byte],
          contentType: String,
          metadata: Map[String, String]
      ): PutOutcome = boom
      def get(ref: ObjectRef): Option[Array[Byte]] = boom
      def openStream(ref: ObjectRef): Option[java.io.InputStream] = boom
      def head(ref: ObjectRef): Option[ObjectInfo] = boom
      def list(bucket: String, prefix: String, maxKeys: Int): List[ObjectInfo] = boom
    }
    val factory: (
        org.scholarchain.storage.StorageConfig,
        String,
        Path
    ) => Either[String, RagIngestJob.IngestStores] =
      (cfg, _, _) => {
        val base = new RagIngestJob.LocalIngestStores(LocalStorageEnvironment.create(cfg, None))
        Right(new RagIngestJob.IngestStores {
          def kind = "aws"
          def description = base.description
          def pipeline(
              e: org.scholarchain.storage.ports.EmbeddingPort,
              s: String,
              x: org.scholarchain.core.SemanticDocument => org.scholarchain.storage.ingest.DocumentExtras
          ) = base.pipeline(e, s, x)
          def vectors = base.vectors
          def textStore = base.textStore
          def objects: ObjectStore = failing
          def rejectsTotal: Long = base.rejectsTotal
          def report(c: org.scholarchain.storage.StorageConfig): ujson.Obj = base.report(c)
          def close(): Unit = base.close()
        })
      }
    val root = Files.createTempDirectory("rag-job-f12")
    val graph = storageConfigOf("rag-voyage-example-v1.json").zones("graph")
    val printed = scala.collection.mutable.ListBuffer.empty[String]
    val out = root.resolve("attempt-01")
    val opts = parseOk(
      Array(
        "--config",
        configPath("rag-voyage-example-v1.json").toString,
        "--export",
        s"s3://${graph.bucket}/${graph.prefix}/exports/run=${"0" * 32}",
        "--out",
        out.toString,
        "--code-version",
        TestCodeVersion,
        "--stores",
        "aws"
      )
    )
    val result = RagIngestJob.execute(
      opts,
      factory,
      voyageWiring,
      RagIngestJob.Hooks(print = s => printed += s, configEnv = NoConfigEnv)
    )
    // the run fails on the export read (status), and the report copy fails too (copy outcome)
    assertEquals(result.report("status").str, "failed: ProbeStoreFailure")
    val copy = result.reportCopy.getOrElse(fail("no copy outcome"))
    assertEquals(copy("outcome").str, "failed: ProbeStoreFailure")
    val texts = List(
      new String(Files.readAllBytes(out.resolve("run-report.json")), "UTF-8"),
      new String(Files.readAllBytes(out.resolve("run-report-copy.json")), "UTF-8")
    ) ++ printed
    texts.foreach(t => assert(!t.contains(leak), s"the SDK message leaked: ${t.take(300)}"))
    // the job's own refusals and the export source's validation errors keep their text
    assertEquals(
      RagIngestJob.failureText(new RuntimeException("own refusal")),
      "RuntimeException: own refusal"
    )
    assertEquals(
      RagIngestJob.failureText(
        new org.scholarchain.storage.source.ExportSourceException("missing export file x")
      ),
      "ExportSourceException: missing export file x"
    )
    assertEquals(RagIngestJob.failureText(new ProbeStoreFailure(leak)), "ProbeStoreFailure")
  }

  test("7b: the free-disk bound is 15 GiB and fits the ops task's 30 GiB ephemeral storage") {
    assertEquals(RagIngestJob.MinFreeBytes, 15L * 1024 * 1024 * 1024)
    assert(RagIngestJob.MinFreeBytes < 30L * 1024 * 1024 * 1024)
  }

  // ---- P16-L-16: the configuration environment is passed explicitly ---------------------------

  private val Overrides = Map(
    org.scholarchain.core.config.ConfigKey.envVarFor("storage.graphBucket") -> OverrideBucket,
    org.scholarchain.core.config.ConfigKey
      .envVarFor("objectStore.semanticContextBucket") -> OverrideBucket
  )

  test(
    "P16-L-16: a SCHOLARCHAIN_CFG_* override passed to the job moves the export and copy buckets"
  ) {
    assertEquals(
      Overrides.keySet,
      Set(
        "SCHOLARCHAIN_CFG_STORAGE_GRAPH_BUCKET",
        "SCHOLARCHAIN_CFG_OBJECT_STORE_SEMANTIC_CONTEXT_BUCKET"
      )
    )
    // the job receives the override: the export placed in the override graph bucket is accepted,
    // and the report copy lands in the override semantic-context bucket
    val f = s3Fixture(jobConfigEnv = Overrides)
    val r = f.result.report
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    assertEquals(f.ragBucket, OverrideBucket)
    val copy = f.result.reportCopy.getOrElse(fail("no copy outcome"))
    assertEquals(copy("outcome").str, "created")
    val key = copy("object").str.stripPrefix("s3://{data-bucket}/")
    assert(
      f.env.objects.get(org.scholarchain.storage.ports.ObjectRef(OverrideBucket, key)).nonEmpty
    )
    // the override bucket is masked like any configured bucket
    val texts = List(
      new String(Files.readAllBytes(f.out.resolve("run-report.json")), "UTF-8"),
      new String(Files.readAllBytes(f.out.resolve("run-report-copy.json")), "UTF-8")
    ) ++ f.printed
    texts.foreach(t => assert(!t.contains(OverrideBucket), t.take(300)))
  }

  test("P16-L-16: the job reads only the configuration environment it is given") {
    // the fixture puts the export in the override bucket, but the job is given no override: it
    // keeps the file's buckets and refuses the export (the human's shell case, mirrored)
    val g = s3Fixture(jobConfigEnv = Map.empty, fixtureConfigEnv = Some(Overrides))
    assert(
      g.result
        .report("status")
        .str
        .contains("--export must be in a bucket of the configured data-lake zones"),
      g.result.report("status").str
    )
    // hermetic default: fixture and job agree on the file's buckets
    val h = s3Fixture()
    assertEquals(h.result.report("status").str, "completed", h.result.report.render(indent = 2))
    assertEquals(h.ragBucket, "example-artifact-bucket")
    assertEquals(
      storageConfigOf("rag-voyage-example-v1.json").zones("graph").bucket,
      "example-graph-bucket"
    )
  }

  test("P16-L-16: the production default of the seam is the process environment") {
    // compared, never printed (the shell may hold real identifiers)
    assert(
      RagIngestJob.Hooks().configEnv() == sys.env,
      "Hooks().configEnv is not the process environment"
    )
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private val TestCodeVersion = "0123456789abcdef0123456789abcdef01234567"

  /** Every job run in this suite passes its configuration environment explicitly (P16-L-16). */
  private val Hermetic = RagIngestJob.Hooks(configEnv = NoConfigEnv)

  private def parseOk(args: Array[String]): RagIngestJob.Options =
    RagIngestJob.parse(args).fold(fail(_), identity)

  private def localArgs(dir: Path, out: Path): Array[String] =
    Array(
      "--config",
      configPath("rag-local-v1.json").toString,
      "--export",
      dir.toString,
      "--out",
      out.toString,
      "--code-version",
      TestCodeVersion,
      "--runs",
      "1",
      "--samples-per-index",
      "3"
    )

  private def storageConfigOf(
      name: String,
      configEnv: Map[String, String] = Map.empty
  ): org.scholarchain.storage.StorageConfig =
    org.scholarchain.storage.StorageConfig
      .from(RagIngestJob.loadConfig(configPath(name), configEnv).fold(fail(_), identity))
      .fold(e => fail(e.mkString("; ")), identity)

  /** A voyage stub transport that answers with the fake lexical vectors (no network). */
  private def voyageWiring: org.scholarchain.rag.embedding.EmbeddingProviders.Wiring = {
    import org.scholarchain.rag.embedding.{FakeLexicalEmbedder, HttpResult, HttpTransport}
    val stub = new HttpTransport {
      def postJson(uri: java.net.URI, headers: List[(String, String)], body: String): HttpResult = {
        val inputs = ujson.read(body)("input").arr.map(_.str)
        val data = inputs.zipWithIndex.map { case (t, i) =>
          ujson.Obj(
            "index" -> i,
            "embedding" -> ujson.Arr.from(
              FakeLexicalEmbedder.vector(t, 1024).map(f => ujson.Num(f.toDouble))
            )
          )
        }
        HttpResult(
          200,
          ujson
            .Obj("data" -> ujson.Arr.from(data), "usage" -> ujson.Obj("total_tokens" -> 10))
            .render(),
          None
        )
      }
    }
    org.scholarchain.rag.embedding.EmbeddingProviders.Wiring(
      env = Map("SCHOLARCHAIN_VOYAGE_API_KEY" -> "test-key-not-real").get,
      httpTransport = () => stub
    )
  }

  private final case class S3Fixture(
      root: Path,
      out: Path,
      localExport: Path,
      exportKey: String,
      ragBucket: String,
      ragPrefix: String,
      env: org.scholarchain.storage.local.LocalStorageEnvironment,
      result: RagIngestJob.JobResult,
      printed: List[String],
      wiring: org.scholarchain.rag.embedding.EmbeddingProviders.Wiring
  ) {

    /** A stores factory over a fresh in-memory environment (for a second, local-export run). */
    def factory(): (
        org.scholarchain.storage.StorageConfig,
        String,
        Path
    ) => Either[String, RagIngestJob.IngestStores] =
      (cfg, _, _) =>
        Right(
          new RagIngestJob.LocalIngestStores(
            org.scholarchain.storage.local.LocalStorageEnvironment.create(cfg, None),
            kind = "aws"
          )
        )
  }

  /** Copies the fixture export into the in-memory object store under the graph zone, then runs the
    * job with `--stores aws` over that store (stub AWS stores, stub voyage transport).
    *
    * @param skip
    *   relative paths to leave out of the store (`FIRST_FILE` = the first listed data file)
    * @param jobConfigEnv
    *   the configuration environment the job receives (default none: hermetic, P16-L-16)
    * @param fixtureConfigEnv
    *   the configuration environment the fixture's buckets are computed with (default: the job's)
    */
  private def s3Fixture(
      bucket: String = "",
      skip: Set[String] = Set.empty,
      changeFirstFile: Boolean = false,
      jobConfigEnv: Map[String, String] = Map.empty,
      fixtureConfigEnv: Option[Map[String, String]] = None
  ): S3Fixture = {
    import org.scholarchain.storage.ports.ObjectRef
    val root = Files.createTempDirectory("rag-job-s3")
    val local = writeExport(root.resolve("export"))
    val cfg =
      storageConfigOf("rag-voyage-example-v1.json", fixtureConfigEnv.getOrElse(jobConfigEnv))
    val graph = cfg.zones("graph")
    val exportBucket = if (bucket.isEmpty) graph.bucket else bucket
    val exportKey = s"${graph.prefix}/exports/${local.getFileName}"
    val env = org.scholarchain.storage.local.LocalStorageEnvironment.create(cfg, None)
    val files = {
      val s = Files.walk(local)
      try
        s.filter(Files.isRegularFile(_))
          .toArray
          .toList
          .map(_.asInstanceOf[Path])
          .map(p => local.relativize(p).toString.replace('\\', '/') -> p)
          .sortBy(_._1)
      finally s.close()
    }
    val firstData = files.map(_._1).find(_ != "_export_manifest.json").get
    val left = skip.map(p => if (p == "FIRST_FILE") firstData else p)
    files.filterNot(f => left(f._1)).foreach { case (rel, p) =>
      val bytes0 = Files.readAllBytes(p)
      val bytes =
        if (changeFirstFile && rel == firstData) {
          val b = bytes0.clone(); b(0) = (b(0) ^ 0x01).toByte; b
        } else bytes0
      env.objects.putIfAbsent(
        ObjectRef(exportBucket, s"$exportKey/$rel"),
        bytes,
        "text/plain",
        Map()
      )
    }
    val printed = scala.collection.mutable.ListBuffer.empty[String]
    val out = root.resolve("attempt-01")
    val wiring = voyageWiring
    val opts = parseOk(
      Array(
        "--config",
        configPath("rag-voyage-example-v1.json").toString,
        "--export",
        s"s3://$exportBucket/$exportKey",
        "--out",
        out.toString,
        "--code-version",
        TestCodeVersion,
        "--runs",
        "1",
        "--samples-per-index",
        "3",
        "--stores",
        "aws"
      )
    )
    val result = RagIngestJob.execute(
      opts,
      (_, _, _) => Right(new RagIngestJob.LocalIngestStores(env, kind = "aws")),
      wiring,
      RagIngestJob.Hooks(print = s => printed += s, configEnv = () => jobConfigEnv)
    )
    val rag = cfg.zones("semantic-context")
    S3Fixture(
      root,
      out,
      local,
      exportKey,
      rag.bucket,
      rag.prefix,
      env,
      result,
      printed.toList,
      wiring
    )
  }

  test("render rejects are recorded, never dropped: a work without a venue node") {
    val root = Files.createTempDirectory("rag-rej")
    val ts = tables.map(t => if (t.name == "nodes:venue") t.copy(lines = t.lines.take(1)) else t)
    val src = new LocalDirectorySource(writeExport(root, ts))
    val rejects = new RejectLog(None)
    val c = new ExportDocuments(src, src.manifest(), localConfig.vectors.get, rejects).renderAll()
    assertEquals(rejects.total, 2L)
    assertEquals(c.stats("paper-abstract-v1").rejected, 2L)
    assertEquals(c.stats("paper-abstract-v1").documents, 2L)
    assertEquals(c.stats("spark-researcher-profile/graph-v1").documents, 6L)
  }

  test("a tampered summary line is rejected at decode and counted") {
    val root = Files.createTempDirectory("rag-rej2")
    val ts = tables.map(t =>
      if (t.name.startsWith("semantic_summary:path"))
        t.copy(lines = t.lines.head.replace("2 hops", "9 hops") :: t.lines.tail)
      else t
    )
    val src = new LocalDirectorySource(writeExport(root, ts))
    val rejects = new RejectLog(None)
    val c = new ExportDocuments(src, src.manifest(), localConfig.vectors.get, rejects).renderAll()
    assertEquals(rejects.total, 1L)
    assertEquals(c.stats("spark-path/graph-v1").documents, 1L)
  }
}
