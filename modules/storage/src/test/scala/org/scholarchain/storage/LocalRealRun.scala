package org.scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.immutable.ListMap

import org.scholarchain.core.{Contract, EvidenceItem, Hashing}
import org.scholarchain.storage.dynamo.DynamoLoader
import org.scholarchain.storage.ingest._
import org.scholarchain.storage.local._
import org.scholarchain.storage.neptune._
import org.scholarchain.storage.ports.{GraphQuery, ObjectStore}
import org.scholarchain.storage.s3.{ManifestEntry, RunManifest, S3KeyLayout}
import org.scholarchain.storage.source._
import org.scholarchain.storage.vectors.VectorMetadataCodec

/** Storage's local real-data run over the Phase 7 JSONL export (test scope; in-memory and
  * local-file fakes only, no network):
  *
  *   1. Neptune openCypher CSV files from the full export (rows, files, bytes per type; IDs re-read
  *      and compared);
  *   2. the DynamoDB load plan applied to the in-memory tables twice (the second pass must make 0
  *      changes);
  *   3. vector ingest of every semantic summary through the manifest work queue, the in-memory
  *      vector index and the stub embedder, twice (the second run must skip 100%).
  *
  * Writes `<out>/run-report.json`. `<out>` must not exist (a new root per attempt). Stops when a
  * step exceeds 60 minutes or free disk drops below 15 GB.
  *
  * {{{
  * sbt "storage/Test/runMain org.scholarchain.storage.LocalRealRun --export <run dir> --out <attempt dir>"
  * }}}
  */
object LocalRealRun {
  private val MinFreeBytes = 15L * 1024 * 1024 * 1024
  private val MaxStepMillis = 60L * 60 * 1000

  final class StopRun(msg: String) extends RuntimeException(msg)

  private def merge(a: ujson.Obj, b: ujson.Obj): ujson.Obj = {
    b.value.foreach { case (k, v) => a(k) = v }
    a
  }

  def main(args: Array[String]): Unit = {
    val opts = args.grouped(2).collect { case Array(k, v) => k -> v }.toMap
    val exportDir =
      Paths.get(opts.getOrElse("--export", sys.error("--export DIR is required"))).toAbsolutePath
    val out = Paths.get(opts.getOrElse("--out", sys.error("--out DIR is required"))).toAbsolutePath
    if (Files.exists(out)) sys.error(s"$out exists; every attempt uses a new root")
    Files.createDirectories(out)
    val report =
      ujson.Obj("format" -> "scholarchain.storage.local-run-report", "formatVersion" -> 1)
    val steps = ujson.Obj()
    report("steps") = steps
    def disk(): Long = Files.getFileStore(out).getUsableSpace
    def checkDisk(when: String): Unit = {
      val free = disk()
      if (free < MinFreeBytes)
        throw new StopRun(s"free disk ${free / (1L << 30)} GB < 15 GB at $when")
    }
    def step[A](name: String)(f: => A): A = {
      checkDisk(s"start of $name")
      val t0 = System.nanoTime()
      println(s"[storage-local-run] step $name started")
      val a = f
      val ms = (System.nanoTime() - t0) / 1000000L
      println(s"[storage-local-run] step $name finished in $ms ms")
      steps(name) = ujson.Obj("millis" -> ms.toDouble, "freeDiskBytesAfter" -> disk().toDouble)
      if (ms > MaxStepMillis) throw new StopRun(s"step $name took $ms ms (> 60 minutes)")
      a
    }
    def write(): Unit =
      Files.write(
        out.resolve("run-report.json"),
        report.render(indent = 2).getBytes(StandardCharsets.UTF_8)
      )

    val t0 = System.nanoTime()
    val rejects = new RejectLog(Some(out.resolve("rejects.jsonl")))
    try {
      val cfg = TestFixtures.storageConfig
      val src = new LocalDirectorySource(exportDir)
      val manifest = src.manifest()
      val manifestSha =
        Hashing.sha256Hex(Files.readAllBytes(exportDir.resolve(ExportManifest.FileName)))
      report("input") = ujson.Obj(
        "exportDir" -> exportDir.toString,
        "exportManifestSha256" -> manifestSha,
        "sourceRunId" -> manifest.sourceRunId.value,
        "tables" -> manifest.tables.size,
        "records" -> manifest.tables.map(_.records).sum.toDouble
      )
      report("environment") = ujson.Obj(
        "heapMaxBytes" -> Runtime.getRuntime.maxMemory.toDouble,
        "processors" -> Runtime.getRuntime.availableProcessors,
        "freeDiskBytesAtStart" -> disk().toDouble,
        "stores" -> "in-memory and local-file fakes only; no AWS call, no network"
      )
      val layout = new S3KeyLayout(cfg.zones)
      val datasetId = "scholarchain-graph"
      val snapshotId = "dblp-2026-11-01"

      // ---- 1. Neptune CSV --------------------------------------------------------------------
      val neptuneRun =
        StorageVersion.runId("storage.neptune-csv", s"sha256:$manifestSha", cfg.fingerprint)
      val nb = step("neptune-csv") {
        new NeptuneCsvBuilder(
          src,
          manifest,
          out.resolve("neptune"),
          NeptuneBuildSettings(
            "full-2015-2025",
            500000,
            gzip = true,
            cfg.vectors.researcherProfileIndex,
            cfg.zones("semantic-context").prefix
          ),
          rejects
        ).build()
      }
      val stagePrefixes = ujson.Obj.from(
        S3KeyLayout.NeptuneStages.toList.sorted.map(s =>
          s -> ujson.Str(layout.neptuneStagePrefix(datasetId, snapshotId, neptuneRun, s).key)
        )
      )
      val graphStore = new LocalFileObjectStore(out.resolve("local-s3"))
      val entries = nb.types.flatMap(t =>
        t.files.map { f =>
          val stage = f.relativePath.takeWhile(_ != '/')
          val key =
            layout.neptuneStagePrefix(datasetId, snapshotId, neptuneRun, stage).key + f.relativePath
              .drop(stage.length + 1)
          ManifestEntry(key, f.bytes, f.sha256, List(s"export:${manifest.sourceRunId.value}"))
        }
      )
      val rm =
        RunManifest("graph", neptuneRun, datasetId, snapshotId, "storage.neptune-csv", entries)
      rm.write(graphStore, layout)
      report("neptune") = merge(
        NeptuneCsvBuilder.toJson(nb),
        ujson.Obj(
          "runId" -> neptuneRun.value,
          "outputDir" -> out.resolve("neptune").toString,
          "s3StagePrefixKeys" -> stagePrefixes,
          "runManifestObjects" -> entries.size,
          "totals" -> ujson.Obj(
            "rows" -> nb.types.map(_.rows).sum.toDouble,
            "files" -> nb.types.map(_.files.size).sum,
            "bytes" -> nb.types.map(_.bytes).sum.toDouble,
            "uncompressedBytes" -> nb.types.map(_.uncompressedBytes).sum.toDouble
          )
        )
      )
      write()

      // ---- 2. DynamoDB load plan, twice ----------------------------------------------------------
      val dynRun =
        StorageVersion.runId("storage.dynamodb-load", s"sha256:$manifestSha", cfg.fingerprint)
      val kv = new InMemoryKeyValueStore(cfg.tableNames)
      val loader = new DynamoLoader(src, manifest, kv, cfg.dynamoDb, rejects, parallelism = 1)
      val files = nb.types.flatMap(_.files.map(f => (f.relativePath, f.rows, f.bytes, f.sha256)))
      val p1 = step("dynamodb-pass-1")(loader.load(1, dynRun, files))
      val writes1 = cfg.tableNames.toList.sorted.map(t => t -> kv.writeCount(t))
      val p2 = step("dynamodb-pass-2")(loader.load(2, dynRun, files))
      val writes2 = cfg.tableNames.toList.sorted.map(t => t -> kv.writeCount(t))
      report("dynamodb") = ujson.Obj(
        "runId" -> dynRun.value,
        "pass1" -> p1.toJson,
        "pass2" -> p2.toJson,
        "storeWritesAfterPass1" -> ujson.Obj.from(writes1.map { case (k, v) =>
          k -> ujson.Num(v.toDouble)
        }),
        "storeWritesAfterPass2" -> ujson.Obj.from(writes2.map { case (k, v) =>
          k -> ujson.Num(v.toDouble)
        }),
        "itemsPerTable" -> ujson.Obj.from(
          cfg.tableNames.toList.sorted.map(t => t -> ujson.Num(kv.itemCount(t).toDouble))
        ),
        "note" -> "store writes on the RunLedger table are the append-only audit events of each pass (claim + summary); data tables must not change in pass 2"
      )
      write()

      // ---- 3. Vector ingest, twice ---------------------------------------------------------------
      val env = LocalStorageEnvironment.create(cfg, None)
      val embedder = new StubEmbedder(
        "storage-stub-sha256-v1",
        cfg.embedding.dimensions,
        cfg.embedding.batchSize
      )
      val pipeline = env.pipeline(embedder, "dblp")
      val ingestRun =
        StorageVersion.runId("storage.vector-ingest", s"sha256:$manifestSha", cfg.fingerprint)
      val summaryTables = manifest.withPrefix("semantic_summary:").map(_.table)
      val plan = step("vector-plan")(
        IngestWorkPlanner.plan(src, manifest, summaryTables, linesPerItem = 1000)
      )
      var buildRejects = 0L
      def documentsFor(payload: String) = LineRange.parse(payload).map { r =>
        val kind = SummaryDocuments.kindOfTable(r.table).get
        val label = manifest.require(r.table).windowLabel.get
        IngestWorkPlanner.readLines(src, manifest, r).iterator.zipWithIndex.flatMap { case (l, i) =>
          Contract
            .decode[EvidenceItem](l)
            .left
            .map(_.message)
            .flatMap(e => SummaryDocuments.build(kind, label, e, cfg.vectors)) match {
            case Right(d)  => Iterator(d)
            case Left(err) =>
              buildRejects += 1
              rejects.record(Reject("vector-documents", r.table, r.file, r.startLine + i, err))
              Iterator.empty
          }
        }
      }
      def ingestOnce(n: Int) = step(s"vector-ingest-run-$n") {
        val q = new ManifestWorkQueue(out.resolve(s"vector-queue/run-$n.jsonl"), maxReceives = 3)
        q.enqueue(plan)
        val s = pipeline.open(ingestRun, s"vector-ingest run $n")
        val d = new IngestWorker(q, receiveBatch = 10, maxItems = plan.size.toLong * 3)
          .drain(s, documentsFor)
        q.close()
        (d, s.finish(), q.poisoned)
      }
      val (d1, r1, poison1) = ingestOnce(1)
      val (d2, r2, poison2) = ingestOnce(2)

      // verification sample: own-vector query returns the document; the text pointer resolves
      val samples = summaryTables.flatMap { t =>
        val kind = SummaryDocuments.kindOfTable(t).get
        val tab = manifest.require(t)
        IngestWorkPlanner
          .readLines(src, manifest, LineRange(t, tab.files.head.path, 1, 1))
          .headOption
          .map { l =>
            val d = SummaryDocuments
              .build(
                kind,
                tab.windowLabel.get,
                Contract.decode[EvidenceItem](l).toOption.get,
                cfg.vectors
              )
              .toOption
              .get
            val q0 = System.nanoTime()
            val hits = env.vectors.query(
              d.indexName,
              StubEmbedder.vector(d.text, embedder.dimensions),
              cfg.vectors.queryTopK,
              None,
              returnMetadata = true
            )
            val qMs = (System.nanoTime() - q0) / 1e6
            val meta = VectorMetadataCodec
              .decode(hits.head.key, d.indexName, hits.head.metadata.get)
              .toOption
              .get
            ujson.Obj(
              "table" -> t,
              "vectorKey" -> d.vectorKey,
              "top1IsSelf" -> (hits.head.key == d.vectorKey),
              "textFetchedByPointerMatchesHash" -> env.textStore
                .fetch(meta.textS3Uri, meta.textHash)
                .isRight,
              "embeddingModelStored" -> meta.embeddingModel,
              "queryMillisLocalFakeNotRepresentative" -> qMs
            )
          }
      }
      val indexCounts =
        env.spec.indexes.map(i => i.name -> ujson.Num(env.vectors.count(i.name).toDouble))
      report("vectorIngest") = ujson.Obj(
        "runId" -> ingestRun.value,
        "embedder" -> ujson.Obj(
          "modelId" -> embedder.modelId,
          "dimensions" -> embedder.dimensions,
          "maxBatchSize" -> embedder.maxBatchSize,
          "kind" -> "storage stub (sha256 counter mode), no semantics"
        ),
        "workItems" -> plan.size,
        "linesPerItem" -> 1000,
        "documentBuildRejects" -> buildRejects.toDouble,
        "run1" -> merge(
          r1.toJson,
          ujson.Obj(
            "workItemsDone" -> d1.itemsDone.toDouble,
            "workItemsFailed" -> d1.itemsFailed.toDouble,
            "documents" -> d1.documents.toDouble,
            "poisonItems" -> poison1.size,
            "docsPerSecond" -> (if (r1.millis("total") > 0)
                                  d1.documents * 1000.0 / r1.millis("total")
                                else 0.0)
          )
        ),
        "run2" -> merge(
          r2.toJson,
          ujson.Obj(
            "workItemsDone" -> d2.itemsDone.toDouble,
            "workItemsFailed" -> d2.itemsFailed.toDouble,
            "documents" -> d2.documents.toDouble,
            "poisonItems" -> poison2.size
          )
        ),
        "indexCounts" -> ujson.Obj.from(indexCounts),
        "textObjects" -> env.objects.objectCount,
        "textBytes" -> env.objects.totalBytes.toDouble,
        "vectorPutCalls" -> env.vectors.putCalls.get.toDouble,
        "vectorsWritten" -> env.vectors.vectorsWritten.get.toDouble,
        "ledgerItems" -> env.kv.itemCount(cfg.dynamoDb.runLedgerTable).toDouble,
        "registryItems" -> env.kv.itemCount(cfg.dynamoDb.artifactRegistryTable).toDouble,
        "samples" -> ujson.Arr.from(samples)
      )
      report("rejects") = ujson.Obj(
        "total" -> rejects.total.toDouble,
        "byStageAndTable" -> ujson.Arr.from(rejects.byStageAndTable.map { case ((s, t), n) =>
          ujson.Obj("stage" -> s, "table" -> t, "count" -> n.toDouble)
        })
      )
      report("status") = "completed"
    } catch {
      case e: StopRun =>
        report("status") = s"stopped: ${e.getMessage}"
      case e: Throwable =>
        report("status") = s"failed: ${e.getClass.getSimpleName}: ${e.getMessage}"
        rejects.close()
        report("totalMillis") = ((System.nanoTime() - t0) / 1000000L).toDouble
        write()
        throw e
    }
    rejects.close()
    report("totalMillis") = ((System.nanoTime() - t0) / 1000000L).toDouble
    write()
    println(
      s"[storage-local-run] ${report("status").str}; report: ${out.resolve("run-report.json")}"
    )
  }
}
