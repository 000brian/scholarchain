package org.scholarchain.rag

import java.nio.file.{Files, Path, Paths}

import org.scholarchain.core.{RunId, SemanticDocument}
import org.scholarchain.rag.embedding.{EmbeddingProviders, InputType}
import org.scholarchain.rag.ingest.RagIngestJob
import org.scholarchain.rag.semantic.ExportDocuments
import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.ingest.{DocumentExtras, TextStore}
import org.scholarchain.storage.local.LocalStorageEnvironment
import org.scholarchain.storage.ports.{EmbeddingPort, ObjectStore, VectorIndexPort}
import org.scholarchain.storage.source.{LocalDirectorySource, RejectLog}

/** Phase 15, G-27 job side: the step cap, `--max-documents`, and the D7 rule that a second pass
  * over data ingested by older code (another build commit, even another job run ID) writes nothing.
  */
class RagIngestG27Suite extends munit.FunSuite {
  import RagFixtures._

  private val Phase14Build = "f159535e498ad1a4504dad1fac0a3fb6ceee14e6"
  private val NewBuild = "0123456789abcdef0123456789abcdef01234567"

  private def configPath(name: String): Path =
    Paths.get(getClass.getResource(s"/config/$name").toURI)

  /** One in-memory environment shared by several job executions (the job closes its stores; this
    * wrapper keeps them open, as the AWS stores outlive any one task).
    */
  final class Shared {
    var env: Option[LocalStorageEnvironment] = None
    def factory: (StorageConfig, String, Path) => Either[String, RagIngestJob.IngestStores] =
      (cfg, _, _) => {
        val e = env.getOrElse {
          val x = LocalStorageEnvironment.create(cfg, None); env = Some(x); x
        }
        val inner = new RagIngestJob.LocalIngestStores(e)
        Right(new RagIngestJob.IngestStores {
          val kind = "local"
          val description = inner.description
          def pipeline(p: EmbeddingPort, s: String, x: SemanticDocument => DocumentExtras) =
            inner.pipeline(p, s, x)
          def vectors: VectorIndexPort = inner.vectors
          def textStore: TextStore = inner.textStore
          def objects: ObjectStore = inner.objects
          def rejectsTotal: Long = inner.rejectsTotal
          def report(c: StorageConfig): ujson.Obj = inner.report(c)
          def close(): Unit = ()
        })
      }
  }

  private def args(dir: Path, out: Path, code: String, extra: String*): Array[String] =
    Array(
      "--config",
      configPath("rag-local-v1.json").toString,
      "--export",
      dir.toString,
      "--out",
      out.toString,
      "--code-version",
      code,
      "--runs",
      "1",
      "--samples-per-index",
      "3"
    ) ++ extra

  private def exec(a: Array[String], shared: Shared): ujson.Obj =
    RagIngestJob.run(RagIngestJob.parse(a).fold(fail(_), identity), shared.factory)

  test("G-27: the step cap is 120 minutes and is recorded in the report") {
    assertEquals(RagIngestJob.MaxStepMillis, 120L * 60L * 1000L)
    val root = Files.createTempDirectory("rag-g27-cap")
    val r = exec(args(writeExport(root.resolve("export")), root.resolve("a"), NewBuild), new Shared)
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    assertEquals(r("limits")("maxStepMillis").num, 7200000.0)
    assert(r("limits")("maxDocuments").isNull)
  }

  test(
    "G-27 (decision 7.1): data ingested by the Phase 14 build is skipped at a new build commit with zero text and vector writes"
  ) {
    val root = Files.createTempDirectory("rag-g27-skip")
    val dir = writeExport(root.resolve("export"))
    val shared = new Shared
    // seed: the pipeline as the Phase 14 build ran it (same text, same model)
    val seed = exec(args(dir, root.resolve("phase14"), Phase14Build), shared)
    assertEquals(seed("status").str, "completed", seed.render(indent = 2))
    assertEquals(seed("runs")(0)("ingest")("written").num, 15.0)
    val env = shared.env.get
    val putCalls = env.vectors.putCalls.get
    val vectorsWritten = env.vectors.vectorsWritten.get
    val objects = env.objects.objectCount
    val creates = env.objects.creates.get

    val r = exec(args(dir, root.resolve("phase15"), NewBuild), shared)
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    val ingest = r("runs")(0)("ingest")
    assertEquals(ingest("input").num, 15.0)
    assertEquals(ingest("skippedUnchanged").num, 15.0)
    assertEquals(ingest("skipRatio").num, 1.0)
    assertEquals(ingest("written").num, 0.0)
    assertEquals(ingest("textCreated").num, 0.0)
    assertEquals(ingest("textAlreadyPresent").num, 0.0)
    assertEquals(ingest("embedCalls").num, 0.0)
    assertEquals(ingest("putCalls").num, 0.0)
    assertEquals(r("runs")(0)("embedRequestsThisRun").num, 0.0)
    assertEquals(env.vectors.putCalls.get, putCalls)
    assertEquals(env.vectors.vectorsWritten.get, vectorsWritten)
    assertEquals(env.objects.objectCount, objects)
    assertEquals(env.objects.creates.get, creates)
    // the retrieval smoke still runs on the skipped corpus
    assert(r("retrievalSmoke").obj.nonEmpty)
  }

  test("G-27: data written under another job run ID (older job code version) is skipped too") {
    val root = Files.createTempDirectory("rag-g27-runid")
    val dir = writeExport(root.resolve("export"))
    val shared = new Shared
    val cfg = StorageConfig
      .from(RagIngestJob.loadConfig(configPath("rag-local-v1.json")).fold(fail(_), identity))
      .fold(e => fail(e.mkString("; ")), identity)
    val env = LocalStorageEnvironment.create(cfg, None)
    shared.env = Some(env)
    val config = RagIngestJob.loadConfig(configPath("rag-local-v1.json")).fold(fail(_), identity)
    val doc = EmbeddingProviders.create(config, InputType.Document).fold(fail(_), identity)
    val src = new LocalDirectorySource(dir)
    val corpus =
      new ExportDocuments(src, src.manifest(), cfg.vectors, new RejectLog(None)).renderAll()
    val olderRun =
      RunId.of("rag.vector-ingest", "rag/0", "sha256:" + "a" * 64, "f" * 64).toOption.get
    val s = env.pipeline(doc.port, "dblp", corpus.extrasOf).open(olderRun, "older code")
    s.ingest(corpus.documents.iterator)
    assertEquals(s.finish().written, corpus.documents.size.toLong)
    val creates = env.objects.creates.get
    val putCalls = env.vectors.putCalls.get

    val r = exec(args(dir, root.resolve("new"), NewBuild), shared)
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    assertNotEquals(r("runId").str, olderRun.value)
    assertEquals(r("runs")(0)("ingest")("written").num, 0.0)
    assertEquals(r("runs")(0)("ingest")("skipRatio").num, 1.0)
    assertEquals(env.objects.creates.get, creates)
    assertEquals(env.vectors.putCalls.get, putCalls)
  }

  test("G-27: --max-documents ingests only the first N rendered documents, every run") {
    val root = Files.createTempDirectory("rag-g27-max")
    val dir = writeExport(root.resolve("export"))
    val shared = new Shared
    val a = args(dir, root.resolve("a"), NewBuild, "--max-documents", "5")
      .updated(9, "2") // --runs 2
    val r = exec(a, shared)
    assertEquals(r("status").str, "completed", r.render(indent = 2))
    assertEquals(r("limits")("maxDocuments").num, 5.0)
    val runs = r("runs").arr
    assertEquals(runs.map(_("documents").num).toList, List(15.0, 15.0))
    assertEquals(runs.map(_("documentsIngested").num).toList, List(5.0, 5.0))
    assertEquals(runs(0)("ingest")("written").num, 5.0)
    assertEquals(runs(1)("ingest")("input").num, 5.0)
    assertEquals(runs(1)("ingest")("written").num, 0.0)
    assertEquals(shared.env.get.vectors.vectorsWritten.get, 5L)
    val smoked = r("retrievalSmoke").obj.values.map(_("samples").num).sum
    assert(smoked >= 1.0 && smoked <= 5.0, smoked.toString)
    // a probe over unchanged data writes nothing
    val probe = exec(args(dir, root.resolve("b"), NewBuild, "--max-documents", "5"), shared)
    assertEquals(probe("runs")(0)("ingest")("written").num, 0.0)
    assertEquals(shared.env.get.vectors.vectorsWritten.get, 5L)
  }

  test("G-27: --max-documents is bounded") {
    val base = Array("--config", "c", "--export", "e", "--out", "o", "--code-version", "abc123")
    assertEquals(RagIngestJob.parse(base).map(_.maxDocuments), Right(None))
    assertEquals(
      RagIngestJob.parse(base ++ Array("--max-documents", "5000")).map(_.maxDocuments),
      Right(Some(5000))
    )
    List("0", "-1", "x", "10000001").foreach { v =>
      assertEquals(
        RagIngestJob.parse(base ++ Array("--max-documents", v)),
        Left("--max-documents must be in 1..10000000"),
        v
      )
    }
  }
}
