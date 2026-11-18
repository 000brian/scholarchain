package org.scholarchain.storage.load

import java.nio.file.{Files, Path}

import scala.collection.mutable

import org.scholarchain.core.config.{ConfigKey, ValueKind}
import org.scholarchain.storage.{Sleeper, StorageConfig, TestFixtures}
import org.scholarchain.storage.local.LocalFileObjectStore
import org.scholarchain.storage.ports.{BulkLoadJob, BulkLoadStatus, GraphBulkLoader}

/** `StorageLoadMain` end to end in local mode on the small synthetic export (file-system object
  * store, in-memory tables, recording bulk loader; no AWS call, no network), plus the AWS-mode
  * rules with injected stores.
  */
class StorageLoadMainLocalSuite extends munit.FunSuite {
  import LoadFixtures._
  import StorageLoadMain._
  import TestFixtures.{tempDir, writeExport}

  private val Code = "0123456789abcdef0123456789abcdef01234567"
  private val Build = BuildIdentity(Code, "false", "services", "0.1.0-SNAPSHOT", "resource")

  /** A workspace: config file, local root holding the export at [[ExportUri]], fresh out dirs. */
  private final class Workspace(name: String) {
    val dir: Path = tempDir(name)
    val config: Path = configFile(dir)
    val root: Path = dir.resolve("local-s3")
    val exportDir: Path = writeExport(tempDir(s"$name-export"))
    upload(new LocalFileObjectStore(root), exportDir)
    private var n = 0
    def out(): Path = { n += 1; dir.resolve(s"out-$n") }
    def common(o: Path): List[String] = List(
      "--config",
      config.toString,
      "--stores",
      "local",
      "--local-root",
      root.toString,
      "--out",
      o.toString,
      "--code-version",
      Code
    )
  }

  private def wiring(
      p: Printed,
      stores: Option[LoadStores] = None,
      sleeper: Sleeper = Sleeper.none
  ) =
    Wiring(
      env = Map.empty,
      stores = stores match {
        case None    => LoadStores.default
        case Some(s) => (_, _, _) => Right(s)
      },
      sleeper = sleeper,
      buildIdentity = () => Build,
      print = p.print
    )

  private def assertMasked(text: String): Unit = {
    AllBuckets.foreach(b => assert(!text.contains(b), s"bucket $b in: $text"))
    assert(!text.contains(AccountId), s"account ID in: $text")
  }

  private def csvBuild(w: Workspace, p: Printed, o: Path, stores: Option[LoadStores] = None) =
    run(
      NeptuneCsvBuild :: w.common(o) ++ List(
        "--export",
        ExportUri,
        "--dataset-id",
        "scholarchain-graph",
        "--snapshot-id",
        "dblp-2026-11-01"
      ),
      wiring(p, stores)
    )

  private def graphRunUri(report: ujson.Value): String =
    report("graphRun")("graphRun").str.replace(Masking.DataBucket, GraphBucket)

  test("neptune-csv-build uploads the CSV files and the run manifest; a rerun is all present") {
    val w = new Workspace("csv")
    val p = new Printed
    val o1 = w.out()
    assertEquals(csvBuild(w, p, o1), Exit.Completed, p.text)
    val (text, r) = readReport(o1)
    assertMasked(text)
    assertMasked(p.text)
    assertEquals(r("status").str, "completed")
    assertEquals(r("codeVersion").str, Code)
    assertEquals(r("build")("buildCommit").str, Code)
    assertEquals(r("build")("module").str, "services")
    assertEquals(r("exitCode").num.toInt, 0)
    val g = r("graphRun")
    assert(g("graphRun").str.startsWith("s3://{data-bucket}/graph/dataset=scholarchain-graph/"))
    val objects = g("objects").num.toInt
    assert(objects > 4, g.toString)
    assertEquals(g("objectsCreated").num.toInt, objects)
    assertEquals(g("runManifestOutcome").str, "created")
    assert(r("neptuneCsv")("types").arr.forall(_("idsMatch").bool))
    val runKey = graphRunUri(r).stripPrefix(s"s3://$GraphBucket/")
    assert(Files.isRegularFile(w.root.resolve(GraphBucket).resolve(s"$runKey/_run_manifest.json")))

    val o2 = w.out()
    assertEquals(csvBuild(w, p, o2), Exit.Completed)
    val g2 = readReport(o2)._2("graphRun")
    assertEquals(g2("graphRun").str, g("graphRun").str)
    assertEquals(g2("objectsCreated").num.toInt, 0)
    assertEquals(g2("objectsAlreadyPresent").num.toInt, objects)
    assertEquals(g2("runManifestOutcome").str, "already-present")
  }

  test("neptune-bulk-start starts nodes then edges; a second start of the run is refused") {
    val w = new Workspace("bulk")
    val p = new Printed
    val o1 = w.out()
    assertEquals(csvBuild(w, p, o1), Exit.Completed)
    val graphRun = graphRunUri(readReport(o1)._2)
    val cfg = StorageConfig
      .from(StorageLoadMain.loadConfig(w.config, Map.empty).toOption.get)
      .toOption
      .get
    val shared = new LoadStores.Local(w.root, cfg)
    def start(o: Path) = run(
      NeptuneBulkStart :: w
        .common(o) ++ List("--graph-run", graphRun, "--loader-role-arn", RoleArn),
      wiring(p, Some(shared))
    )
    val o2 = w.out()
    assertEquals(start(o2), Exit.Completed, p.text)
    val (text, r) = readReport(o2)
    assertMasked(text)
    assertMasked(p.text)
    assertEquals(r("loaderRoleArn").str, "arn:aws:iam::{account}:role/example-neptune-loader")
    assertEquals(r("bulkLoad")("nodesLoadId").str, "local-load-0001")
    assertEquals(r("bulkLoad")("edgesLoadId").str, "local-load-0002")
    assertEquals(r("bulkLoad")("registry").str, "registered")
    val jobs = shared.recorder.jobs.toList
    assertEquals(jobs.map(_.source.split("/neptune/").last), List("nodes/", "edges/"))
    assertEquals(jobs.map(_.dependencies), List(Nil, List("local-load-0001")))
    assert(jobs.forall(j => j.region == "us-east-1" && j.failOnError && j.format == "opencypher"))

    val o3 = w.out()
    assertEquals(start(o3), Exit.Refused)
    val r3 = readReport(o3)._2
    assert(r3("status").str.contains("already started"), r3("status").str)
    assert(r3("status").str.contains("nodesLoadId=local-load-0001"), r3("status").str)
    assertEquals(shared.recorder.jobs.size, 2)
  }

  /** Local stores whose bulk loader fails the start of the given stages, once each. */
  private final class FailingStartStores(local: LoadStores.Local, failOnce: Set[String])
      extends LoadStores {
    private val pending = mutable.Set.from(failOnce)
    val loader: GraphBulkLoader = new GraphBulkLoader {
      def start(job: BulkLoadJob): String = {
        val stage = job.source.split("/neptune/").last.stripSuffix("/")
        if (pending.remove(stage))
          throw new java.io.IOException(s"simulated $stage start failure")
        local.recorder.start(job)
      }
      def status(loadId: String): BulkLoadStatus = local.recorder.status(loadId)
    }
    val kind = "local"
    val description = "local stores with a failing bulk loader"
    def objects = local.objects
    def kv = local.kv
    def bulkLoader: Either[String, GraphBulkLoader] = Right(loader)
    def report(c: StorageConfig): ujson.Obj = local.report(c)
    def close(): Unit = ()
  }

  private def localConfig(w: Workspace): StorageConfig =
    StorageConfig
      .from(StorageLoadMain.loadConfig(w.config, Map.empty).toOption.get)
      .toOption
      .get

  test("neptune-bulk-start: an edges start failure, then a retry starts edges only (C-04)") {
    val w = new Workspace("bulk-retry")
    val p = new Printed
    val o1 = w.out()
    assertEquals(csvBuild(w, p, o1), Exit.Completed)
    val graphRun = graphRunUri(readReport(o1)._2)
    val local = new LoadStores.Local(w.root, localConfig(w))
    val stores = new FailingStartStores(local, Set("edges"))
    def start(o: Path) = run(
      NeptuneBulkStart :: w
        .common(o) ++ List("--graph-run", graphRun, "--loader-role-arn", RoleArn),
      wiring(p, Some(stores))
    )
    val o2 = w.out()
    assertEquals(start(o2), Exit.Failed, p.text)
    assert(readReport(o2)._2("status").str.contains("simulated edges"), p.text)
    assertEquals(local.recorder.jobs.map(_.source.split("/neptune/").last).toList, List("nodes/"))

    val o3 = w.out()
    assertEquals(start(o3), Exit.Completed, p.text)
    val r3 = readReport(o3)._2
    val stages = local.recorder.jobs.map(_.source.split("/neptune/").last).toList
    assertEquals(stages, List("nodes/", "edges/"), "the retry must not start nodes again")
    assertEquals(local.recorder.jobs.last.dependencies, List("local-load-0001"))
    assertEquals(r3("bulkLoad")("nodesLoadId").str, "local-load-0001")
    assertEquals(r3("bulkLoad")("edgesLoadId").str, "local-load-0002")
    assertEquals(r3("bulkLoad")("resumed").bool, true)

    val o4 = w.out()
    assertEquals(start(o4), Exit.Refused)
    assert(readReport(o4)._2("status").str.contains("already started"))
    assertEquals(local.recorder.jobs.size, 2)
  }

  test("neptune-bulk-start: a failed nodes start is never retried blindly (C-04)") {
    val w = new Workspace("bulk-nodes-fail")
    val p = new Printed
    val o1 = w.out()
    assertEquals(csvBuild(w, p, o1), Exit.Completed)
    val graphRun = graphRunUri(readReport(o1)._2)
    val local = new LoadStores.Local(w.root, localConfig(w))
    val stores = new FailingStartStores(local, Set("nodes"))
    def start(o: Path) = run(
      NeptuneBulkStart :: w
        .common(o) ++ List("--graph-run", graphRun, "--loader-role-arn", RoleArn),
      wiring(p, Some(stores))
    )
    val o2 = w.out()
    assertEquals(start(o2), Exit.Failed, p.text)
    val o3 = w.out()
    assertEquals(start(o3), Exit.Refused, p.text)
    val r3 = readReport(o3)._2
    assert(r3("status").str.contains("load ID was not registered"), r3("status").str)
    assertEquals(local.recorder.jobs.size, 0)
  }

  test("neptune-bulk-start refuses a graph run without a manifest and one outside the graph zone") {
    val w = new Workspace("bulk-missing")
    val p = new Printed
    val missing =
      s"s3://$GraphBucket/graph/dataset=scholarchain-graph/snapshot=s/run=${"0" * 32}"
    val o1 = w.out()
    assertEquals(
      run(
        NeptuneBulkStart :: w.common(o1) ++
          List("--graph-run", missing, "--loader-role-arn", RoleArn),
        wiring(p)
      ),
      Exit.Refused
    )
    assert(readReport(o1)._2("status").str.contains("graph run manifest is missing"))
    val o2 = w.out()
    val outside = s"s3://example-raw-bucket/graph/dataset=d/snapshot=s/run=${"0" * 32}"
    assertEquals(
      run(
        NeptuneBulkStart :: w.common(o2) ++
          List("--graph-run", outside, "--loader-role-arn", RoleArn),
        wiring(p)
      ),
      Exit.Refused
    )
    val (t2, r2) = readReport(o2)
    assert(r2("status").str.contains("configured graph zone"), r2("status").str)
    assertMasked(t2)
    val o3 = w.out()
    assertEquals(
      run(
        NeptuneBulkStart :: w.common(o3) ++
          List("--graph-run", missing, "--loader-role-arn", "not-an-arn"),
        wiring(p)
      ),
      Exit.Refused
    )
    assert(readReport(o3)._2("status").str.contains("iamRoleArn is not an IAM role ARN"))
  }

  /** A bulk loader whose status walks through the given states, then stays on the last. */
  private final class ScriptedLoader(states: List[String]) extends GraphBulkLoader {
    val calls = new java.util.concurrent.atomic.AtomicInteger()
    def start(job: BulkLoadJob): String = "unused"
    def status(loadId: String): BulkLoadStatus = {
      val i = calls.getAndIncrement()
      BulkLoadStatus(loadId, states(math.min(i, states.size - 1)), 10L, 0L)
    }
  }

  private def statusRun(
      w: Workspace,
      states: List[String],
      waitSeconds: Int,
      sleeps: mutable.ListBuffer[Long]
  ) = {
    val cfg = StorageConfig
      .from(StorageLoadMain.loadConfig(w.config, Map.empty).toOption.get)
      .toOption
      .get
    val loader = new ScriptedLoader(states)
    val local = new LoadStores.Local(w.root, cfg)
    val stores = new LoadStores {
      val kind = "local"
      val description = "local stores with a scripted bulk loader"
      def objects = local.objects
      def kv = local.kv
      def bulkLoader: Either[String, GraphBulkLoader] = Right(loader)
      def report(c: StorageConfig): ujson.Obj = ujson.Obj()
      def close(): Unit = ()
    }
    val o = w.out()
    val p = new Printed
    val code = run(
      NeptuneBulkStatus :: w.common(o) ++
        List("--load-id", "abc-123", "--wait-seconds", waitSeconds.toString),
      wiring(p, Some(stores), (ms: Long) => { sleeps += ms; () })
    )
    (code, readReport(o)._2, loader.calls.get)
  }

  test("neptune-bulk-status: completed is 0, still running is 3, a failed load is 1") {
    val w = new Workspace("status")
    val sleeps = mutable.ListBuffer.empty[Long]
    val (c1, r1, n1) = statusRun(w, List("LOAD_COMPLETED"), 0, sleeps)
    assertEquals((c1, n1), (Exit.Completed, 1))
    assertEquals(r1("bulkLoadStatus")("status").str, "LOAD_COMPLETED")
    val (c2, r2, _) = statusRun(w, List("LOAD_IN_PROGRESS"), 0, sleeps)
    assertEquals(c2, Exit.NotFinished)
    assert(r2("status").str.startsWith("not finished"), r2("status").str)
    val (c3, _, n3) =
      statusRun(w, List("LOAD_IN_QUEUE", "LOAD_IN_PROGRESS", "LOAD_COMPLETED"), 600, sleeps)
    assertEquals((c3, n3), (Exit.Completed, 3))
    assertEquals(sleeps.toList, List(StatusPollMillis, StatusPollMillis))
    val (c4, r4, _) = statusRun(w, List("LOAD_FAILED"), 0, sleeps)
    assertEquals(c4, Exit.Failed)
    assert(r4("status").str.contains("LOAD_FAILED"), r4("status").str)
  }

  test("neptune-bulk-status in local mode reads the recording loader") {
    val w = new Workspace("status-local")
    val p = new Printed
    val o = w.out()
    assertEquals(
      run(NeptuneBulkStatus :: w.common(o) ++ List("--load-id", "local-load-0001"), wiring(p)),
      Exit.Completed
    )
    assert(p.text.contains("load local-load-0001 status LOAD_COMPLETED"), p.text)
  }

  test("dynamodb-load from an s3:// export: pass 2 makes no data changes") {
    val w = new Workspace("dyn")
    val p = new Printed
    val o = w.out()
    val code = run(
      DynamoDbLoad :: w.common(o) ++ List("--export", ExportUri, "--passes", "2"),
      wiring(p)
    )
    assertEquals(code, Exit.Completed, p.text)
    val (text, r) = readReport(o)
    assertMasked(text)
    assertMasked(p.text)
    val passes = r("dynamodb")("passes").arr
    assertEquals(passes.size, 2)
    assert(passes(0)("dataChanges").num > 0, passes(0).toString)
    assertEquals(passes(1)("dataChanges").num, 0.0)
    assertEquals(r("dynamodb")("rankingTopK").num.toInt, 3)
    assertEquals(r("input")("export").str, s"s3://{data-bucket}/$ExportKey")
    assert(r("input")("files").num > 0)
    assert(r("store")("itemsPerTable")("example-researcher-metric-cache").num > 0)
    assert(p.text.contains("[storage-load] dynamodb-load completed"), p.text)
    assert(p.text.contains("[storage-load] report {"), p.text)
  }

  test("dynamodb-load from a local export directory gives the same items as from s3://") {
    val w = new Workspace("dyn-local")
    val p = new Printed
    val o1 = w.out()
    val o2 = w.out()
    assertEquals(run(DynamoDbLoad :: w.common(o1) ++ List("--export", ExportUri), wiring(p)), 0)
    assertEquals(
      run(DynamoDbLoad :: w.common(o2) ++ List("--export", w.exportDir.toString), wiring(p)),
      0
    )
    val a = readReport(o1)._2
    val b = readReport(o2)._2
    assertEquals(a("dynamodb")("runId"), b("dynamodb")("runId"))
    assertEquals(a("store")("itemsPerTable"), b("store")("itemsPerTable"))
  }

  test("dynamodb-load fails on a changed export file and on a missing one") {
    val w = new Workspace("dyn-bad")
    val bad = dataFile("nodes/node_label=researcher")
    val store = new LocalFileObjectStore(w.root)
    val p = new Printed
    val o1 = w.out()
    val canonical = dataFile("canonical_researcher")
    val tamperedCanonical = Files.readAllBytes(w.exportDir.resolve(canonical)).clone()
    tamperedCanonical(tamperedCanonical.indexOf('A'.toByte)) = 'B'.toByte
    val badKey2 = "graph/exports3/run=" + runHex
    upload(store, w.exportDir, key = badKey2, replace = { case `canonical` => tamperedCanonical })
    assertEquals(
      run(
        DynamoDbLoad :: w.common(o1) ++ List("--export", s"s3://$GraphBucket/$badKey2"),
        wiring(p)
      ),
      Exit.Failed
    )
    val r1 = readReport(o1)._2
    assert(r1("status").str.contains("sha256"), r1("status").str)
    val missingKey = "graph/exports4/run=" + runHex
    upload(store, w.exportDir, key = missingKey, skip = Set(bad))
    val o2 = w.out()
    assertEquals(
      run(
        DynamoDbLoad :: w.common(o2) ++ List("--export", s"s3://$GraphBucket/$missingKey"),
        wiring(p)
      ),
      Exit.Failed
    )
    val (t2, r2) = readReport(o2)
    assert(r2("status").str.contains(s"missing export file $bad"), r2("status").str)
    assertMasked(t2)
  }

  test("dynamodb-load: a same-size tampered export makes 0 writes in every table (C-02)") {
    val w = new Workspace("dyn-tamper")
    val cfg = localConfig(w)
    val local = new LoadStores.Local(w.root, cfg)
    val canonical = dataFile("canonical_researcher")
    val original = Files.readAllBytes(w.exportDir.resolve(canonical))
    val tampered = original.clone()
    tampered(tampered.indexOf('A'.toByte)) = 'B'.toByte
    assertEquals(tampered.length, original.length)
    val key = "graph/exports5/run=" + runHex
    upload(local.objects, w.exportDir, key = key, replace = { case `canonical` => tampered })
    val p = new Printed
    val o = w.out()
    assertEquals(
      run(
        DynamoDbLoad :: w.common(o) ++ List("--export", s"s3://$GraphBucket/$key"),
        wiring(p, Some(local))
      ),
      Exit.Failed
    )
    val r = readReport(o)._2
    assert(r("status").str.contains("sha256"), r("status").str)
    val writes = local.report(cfg)("writesPerTable").obj
    assertEquals(writes.keySet, cfg.tableNames)
    assert(writes.values.forall(_.num == 0.0), s"writes before verification: $writes")
  }

  test(
    "aws mode: store names come from the file plus SCHOLARCHAIN_CFG_*; exports stay in the zones"
  ) {
    val w = new Workspace("aws")
    val envBucket = "example-env-graph-bucket"
    val env = Map(ConfigKey("storage.graphBucket", ValueKind.Text).envVar -> envBucket)
    var seen: Option[StorageConfig] = None
    val p = new Printed
    def awsArgs(o: Path, location: String) = List(
      DynamoDbLoad,
      "--config",
      w.config.toString,
      "--stores",
      "aws",
      "--out",
      o.toString,
      "--code-version",
      Code,
      "--export",
      location
    )
    val wire = Wiring(
      env = env,
      stores = (kind, cfg, root) => {
        assertEquals((kind, root), ("aws", None))
        seen = Some(cfg)
        Right(new LoadStores.Local(w.root, cfg))
      },
      buildIdentity = () => Build,
      print = p.print
    )
    val o1 = w.out()
    val outside = s"s3://example-other-bucket/graph/exports/run=$runHex"
    assertEquals(run(awsArgs(o1, outside), wire), Exit.Refused)
    assertEquals(seen.map(_.zones("graph").bucket), Some(envBucket))
    assert(readReport(o1)._2("status").str.contains("configured data-lake zones"))

    upload(new LocalFileObjectStore(w.root), w.exportDir, bucket = envBucket)
    val o2 = w.out()
    assertEquals(run(awsArgs(o2, s"s3://$envBucket/$ExportKey"), wire), Exit.Completed, p.text)
    val (text, r) = readReport(o2)
    assert(!text.contains(envBucket), text)
    assert(!p.text.contains(envBucket), p.text)
    assertEquals(r("stores").str, "aws")
    assertEquals(r("writers")("parallelism").num.toInt, 1)
  }

  test("an unknown SCHOLARCHAIN_CFG_ variable or a broken config file is a refusal") {
    val w = new Workspace("cfg")
    val p = new Printed
    val o = w.out()
    val code = run(
      DynamoDbLoad :: w.common(o) ++ List("--export", ExportUri),
      wiring(p).copy(env = Map("SCHOLARCHAIN_CFG_NOT_A_KEY" -> "x"))
    )
    assertEquals(code, Exit.Refused)
    assert(readReport(o)._2("status").str.contains("SCHOLARCHAIN_CFG_NOT_A_KEY"))
  }
}
