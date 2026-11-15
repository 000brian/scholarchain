package org.scholarchain.storage

import java.nio.charset.StandardCharsets

import org.scholarchain.core.ContentHash
import org.scholarchain.storage.local.{InMemoryObjectStore, LocalFileObjectStore}
import org.scholarchain.storage.ports.{ObjectRef, ObjectStore, PutOutcome}
import org.scholarchain.storage.s3.{ManifestEntry, RunManifest, S3KeyLayout}

class ObjectStoreSuite extends munit.FunSuite {
  import TestFixtures._

  private val layout = new S3KeyLayout(storageConfig.zones)

  test("run-scoped keys carry dataset, snapshot and run ID; text keys carry the text hash") {
    val k = layout.curatedObject("dblp", "2026-11-01", sparkRun, "authorship/part-00000.jsonl")
    assertEquals(
      k.key,
      s"curated/dataset=dblp/snapshot=2026-11-01/run=${sparkRun.value.stripPrefix("run:v1:")}/authorship/part-00000.jsonl"
    )
    assert(
      layout
        .neptuneStagePrefix("scholarchain-graph", "dblp-2026-11-01", sparkRun, "edges")
        .key
        .endsWith("/neptune/edges/")
    )
    assert(
      layout
        .rawObject("dblp", "2026-11-01", "dblp.xml.gz")
        .key
        .startsWith("raw/dataset=dblp/snapshot=2026-11-01/")
    )
    assert(
      layout
        .reportObject("scholarchain-graph", "s", sparkRun, "run-report.json")
        .key
        .startsWith("reports/")
    )
    assert(
      layout
        .logObject("scholarchain-graph", "s", sparkRun, "storage", "rejects.jsonl")
        .key
        .startsWith("logs/")
    )
    val h = ContentHash.of("hello")
    assertEquals(
      layout.textObject("researcher-profile-v1", h).key,
      s"semantic-context/text/v1/index=researcher-profile-v1/sha256=${h.drop(7)}.txt"
    )
    assertEquals(
      layout.zone("semantic-context").bucket,
      storageConfig.zones("semantic-context").bucket
    )
    intercept[IllegalArgumentException](layout.curatedObject("dblp", "../x", sparkRun, "a"))
    intercept[IllegalArgumentException](layout.curatedObject("dblp", "s", sparkRun, "a/../../b"))
    intercept[IllegalArgumentException](layout.neptuneStagePrefix("d", "s", sparkRun, "everything"))
  }

  test(
    "object writes are put-if-absent: rerun is AlreadyPresent, different bytes a Conflict, never overwritten"
  ) {
    List[ObjectStore](new InMemoryObjectStore, new LocalFileObjectStore(tempDir("objs"))).foreach {
      s =>
        val ref = ObjectRef("example-artifact-bucket", "reports/a.json")
        val a = "a".getBytes(StandardCharsets.UTF_8)
        assertEquals(s.putIfAbsent(ref, a, "application/json", Map.empty), PutOutcome.Created)
        assertEquals(
          s.putIfAbsent(ref, a, "application/json", Map.empty),
          PutOutcome.AlreadyPresent
        )
        assert(
          s.putIfAbsent(ref, "b".getBytes, "application/json", Map.empty)
            .isInstanceOf[PutOutcome.Conflict]
        )
        assertEquals(s.get(ref).map(new String(_, StandardCharsets.UTF_8)), Some("a"))
        assertEquals(s.list("example-artifact-bucket", "reports/", 10).map(_.ref), List(ref))
    }
  }

  test("run manifests list objects with sha256 and source references, and are idempotent") {
    val s = new InMemoryObjectStore
    val m = RunManifest(
      "graph",
      sparkRun,
      "scholarchain-graph",
      "dblp-2026-11-01",
      "storage.neptune-csv",
      List(
        ManifestEntry("graph/x/b.csv.gz", 10, "b" * 64, List("export:nodes:work")),
        ManifestEntry("graph/x/a.csv.gz", 5, "a" * 64, Nil)
      )
    )
    assertEquals(m.write(s, layout), PutOutcome.Created)
    assertEquals(m.write(s, layout), PutOutcome.AlreadyPresent)
    val json = ujson.read(
      s.get(layout.runManifest("graph", "scholarchain-graph", "dblp-2026-11-01", sparkRun)).get
    )
    assertEquals(
      json("objects").arr.map(_("key").str).toList,
      List("graph/x/a.csv.gz", "graph/x/b.csv.gz")
    )
    assertEquals(json("runId").str, sparkRun.value)
  }

  test("ObjectRef URIs parse and reject non-S3 pointers") {
    assertEquals(
      ObjectRef.parse("s3://example-bucket/a/b.txt"),
      Right(ObjectRef("example-bucket", "a/b.txt"))
    )
    assert(ObjectRef.parse("https://example.com/a").isLeft)
  }

  test("retry policy: exponential, capped, bounded, transient only") {
    val p = RetryPolicy(3, 100, 250)
    assertEquals((1 to 4).map(p.delayBeforeRetry).toList, List(100L, 200L, 250L, 250L))
    var calls = 0
    val slept = scala.collection.mutable.ArrayBuffer.empty[Long]
    val r = Retry.run(p, (ms: Long) => slept += ms) {
      calls += 1; throw new StoreException("throttled", true)
    }
    assertEquals((calls, r.retries, slept.toList), (4, 3, List(100L, 200L, 250L)))
    calls = 0
    val perm = Retry.run(p, Sleeper.none) { calls += 1; throw new StoreException("denied", false) }
    assertEquals((calls, perm.retries, perm.result.isLeft), (1, 0, true))
    calls = 0
    val ok = Retry.run(p, Sleeper.none) {
      calls += 1; if (calls < 3) throw new StoreException("5xx", true) else 42
    }
    assertEquals((ok.result, ok.retries), (Right(42), 2))
  }
}
