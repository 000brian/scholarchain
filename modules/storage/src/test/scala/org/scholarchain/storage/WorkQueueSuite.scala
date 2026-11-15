package org.scholarchain.storage

import org.scholarchain.core.EvidenceItem
import org.scholarchain.storage.ingest._
import org.scholarchain.storage.local._
import org.scholarchain.storage.ports.WorkItem
import org.scholarchain.storage.source.LocalDirectorySource

class WorkQueueSuite extends munit.FunSuite {
  import TestFixtures._

  test(
    "the manifest queue is idempotent, survives a restart through its journal, and isolates poison"
  ) {
    val journal = tempDir("queue").resolve("queue.jsonl")
    val items = List(WorkItem("a", "{}"), WorkItem("b", "{}"), WorkItem("c", "{}"))
    val q1 = new ManifestWorkQueue(journal, maxReceives = 2)
    assertEquals(q1.enqueue(items), 3)
    assertEquals(q1.enqueue(items), 0)
    intercept[IllegalStateException](q1.enqueue(List(WorkItem("a", "{\"x\":1}"))))
    val got = q1.receive(2)
    assertEquals(got.map(_.item.id), List("a", "b"))
    assertEquals(q1.receive(5).map(_.item.id), List("c"), "received items are hidden")
    q1.ack(got.head)
    got.drop(1).foreach(q1.release)
    q1.close()
    val q2 = new ManifestWorkQueue(journal, maxReceives = 2)
    assertEquals(q2.doneCount, 1)
    assertEquals(q2.enqueue(items), 0)
    // b and c fail twice, then are poison
    (1 to 2).foreach(_ => q2.receive(10).foreach(q2.release))
    assertEquals(q2.receive(10), Nil)
    assertEquals(q2.poisoned.toSet, Set("b", "c"))
    q2.close()
  }

  test(
    "planning is deterministic and verified; a worker drains the queue into one ingest session, twice"
  ) {
    val dir = writeExport(tempDir("plan"))
    val src = new LocalDirectorySource(dir)
    val m = src.manifest()
    val tables = m.withPrefix("semantic_summary:").map(_.table)
    val plan = IngestWorkPlanner.plan(src, m, tables, linesPerItem = 3)
    assertEquals(plan, IngestWorkPlanner.plan(src, m, tables, linesPerItem = 3))
    assertEquals(plan.size, 4) // profiles 4 lines -> 2 items; community 1; path 1
    val env = LocalStorageEnvironment.create(storageConfig, None)
    val pipeline = env.pipeline(new StubEmbedder("storage-stub-sha256-v1", 1024, 8), "dblp")
    val run =
      StorageVersion.runId("storage.vector-ingest", "sha256:plan", storageConfig.fingerprint)
    def documentsFor(payload: String) = LineRange.parse(payload).map { r =>
      val kind = SummaryDocuments.kindOfTable(r.table).get
      val label = m.require(r.table).windowLabel.get
      IngestWorkPlanner.readLines(src, m, r).iterator.map { l =>
        val e = org.scholarchain.core.Contract.decode[EvidenceItem](l).toOption.get
        SummaryDocuments.build(kind, label, e, storageConfig.vectors).toOption.get
      }
    }
    def once(journalName: String) = {
      val q = new ManifestWorkQueue(dir.resolve(journalName), maxReceives = 3)
      q.enqueue(plan)
      val s = pipeline.open(run, "vector-ingest")
      val d = new IngestWorker(q, receiveBatch = 10, maxItems = 100).drain(s, documentsFor)
      q.close()
      (d, s.finish())
    }
    val (d1, r1) = once("q1.jsonl")
    assertEquals((d1.itemsDone, d1.itemsFailed, d1.documents), (4L, 0L, 6L))
    assertEquals(r1.written, 6L)
    val (_, r2) = once("q2.jsonl")
    assertEquals(r2.skipRatio, 1.0)
  }

  test("a failing work item is released, recorded with its receive count, and bounded") {
    val q = new ManifestWorkQueue(tempDir("fail").resolve("q.jsonl"), maxReceives = 2)
    q.enqueue(List(WorkItem("x", "{}")))
    val env = LocalStorageEnvironment.create(storageConfig, None)
    val s = env.pipeline(new StubEmbedder("m", 1024, 4), "dblp").open(sparkRun, "t")
    val d = new IngestWorker(q, 10, maxItems = 10).drain(s, _ => Left("cannot render"))
    assertEquals(d.itemsFailed, 2L)
    assertEquals(q.poisoned, List("x"))
    assertEquals(s.ledger.events(10).count(_.str("eventType") == "work-failure"), 2)
  }
}
