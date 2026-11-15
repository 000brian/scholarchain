package org.scholarchain.storage

import java.nio.charset.StandardCharsets

import scala.concurrent.duration._

import org.scholarchain.core.config.ConfigLoader
import org.scholarchain.storage.aws._
import org.scholarchain.storage.ports._
import org.scholarchain.storage.s3.S3KeyLayout
import org.scholarchain.storage.vectors.IndexSpecCatalog

/** Bounded AWS integration tests. Every test is tagged `RequiresAws`: excluded from `sbt check`,
  * run only with `sbt storage/AwsTest/test` against a deployed environment (deferred to Phases
  * 14/15). Configuration comes from `SCHOLARCHAIN_CONFIG_FILE`; credentials from the default
  * provider chain. Bounds: at most 2 objects, 2 items, 2 vectors and 1 message per test; 5-second
  * API call timeouts with at most 3 attempts; 60 seconds per test. Test data uses the `itest`
  * prefix so the cleanup script can find it.
  */
class AwsAdaptersAwsSuite extends munit.FunSuite {
  private val RequiresAws = new munit.Tag("RequiresAws")
  override val munitTimeout: Duration = 60.seconds

  private lazy val cfg: StorageConfig =
    ConfigLoader
      .loadFromEnvironment(sys.env)
      .left
      .map(_.message)
      .flatMap(l => StorageConfig.from(l.config).left.map(_.mkString("; ")))
      .fold(e => throw new IllegalStateException(e), identity)

  private lazy val client = AwsClientSettings(cfg.region, 3000, 5000, 5000, 3)
  private lazy val run = StorageVersion.runId("storage.aws-itest", "sha256:itest", cfg.fingerprint)

  test("S3: put-if-absent is idempotent and never overwrites".tag(RequiresAws)) {
    val store = S3ObjectStore.create(client)
    val layout = new S3KeyLayout(cfg.zones)
    val ref = layout.logObject("itest", "itest", run, "storage-itest", "probe.json")
    val bytes = "{\"probe\":1}".getBytes(StandardCharsets.UTF_8)
    val first = store.putIfAbsent(ref, bytes, "application/json", Map.empty)
    assert(first == PutOutcome.Created || first == PutOutcome.AlreadyPresent)
    assertEquals(
      store.putIfAbsent(ref, bytes, "application/json", Map.empty),
      PutOutcome.AlreadyPresent
    )
    assert(
      store
        .putIfAbsent(ref, "{}".getBytes, "application/json", Map.empty)
        .isInstanceOf[PutOutcome.Conflict]
    )
    assertEquals(store.list(ref.bucket, ref.key, 2).size, 1)
  }

  test("DynamoDB: conditional writes on RunLedger".tag(RequiresAws)) {
    val kv = DynamoDbKeyValueStore.create(client)
    val t = cfg.dynamoDb.runLedgerTable
    val i = Item(
      Item.Pk -> AttrValue.S(s"run#itest#${run.value}"),
      Item.Sk -> AttrValue.S("itest"),
      "v" -> AttrValue.n(1L)
    )
    kv.putIfAbsent(t, i)
    assertEquals(kv.putIfAbsent(t, i), ConditionalResult.ConditionFailed)
    assertEquals(kv.get(t, i.key).map(_.key), Some(i.key))
    assert(kv.query(t, i.key.pk, None, 2, descending = false).nonEmpty)
  }

  test(
    "S3 Vectors: indexes match the index spec; a 2-vector put/get/query round trip".tag(RequiresAws)
  ) {
    val spec = IndexSpecCatalog.load()
    val idx = S3VectorsIndex.create(
      client,
      cfg.vectorBucket,
      n => spec.forName(n).map(_.nonFilterableKeys.toSet).getOrElse(Set.empty)
    )
    spec.indexes.foreach { s =>
      val d = idx.describeIndex(s.name).getOrElse(fail(s"index ${s.name} does not exist"))
      assertEquals(
        (d.dimension, d.distanceMetric, d.nonFilterableKeys),
        (spec.dimension, spec.distanceMetric, s.nonFilterableKeys.toSet)
      )
    }
    val name = spec.indexes.head.name
    val meta = StoredMetadata(
      scala.collection.immutable.ListMap("documentType" -> MetaValue.Str("researcher_profile")),
      scala.collection.immutable.ListMap("runId" -> MetaValue.Str(run.value))
    )
    val vs = List("itest:a", "itest:b").map(k =>
      VectorRecord(k, local.StubEmbedder.vector(k, spec.dimension), meta)
    )
    idx.putVectors(name, vs)
    assertEquals(
      idx.getVectors(name, vs.map(_.key), returnData = false).map(_.key).toSet,
      vs.map(_.key).toSet
    )
    assert(idx.query(name, vs.head.data, 2, None, returnMetadata = true).nonEmpty)
  }

  test("Neptune: read-only template with a bounded timeout".tag(RequiresAws)) {
    val port = cfg.neptune.map(_.port).getOrElse(fail("neptune section is not configured"))
    val g = new NeptuneGraphRead(NeptuneClients.create(client, cfg.neptuneEndpoint, port), 5000)
    val missing = org.scholarchain.core.ResearcherId("researcher:v1:" + "0" * 32)
    assertEquals(g.run(ports.GraphQuery.ResearcherById(missing)).map(_.rows), Right(Nil))
  }

  test(
    "SQS: one message round trip (queue URL from SCHOLARCHAIN_STORAGE_ITEST_QUEUE_URL)".tag(
      RequiresAws
    )
  ) {
    val url = sys.env.get("SCHOLARCHAIN_STORAGE_ITEST_QUEUE_URL")
    assume(url.nonEmpty, "no test queue configured")
    val q = SqsWorkQueue.create(client, url.get, waitSeconds = 2, visibilitySeconds = 30)
    assertEquals(q.enqueue(List(WorkItem(s"itest:${run.value}", "{}"))), 1)
    val got = q.receive(1)
    got.foreach(q.ack)
    assert(got.size <= 1)
  }
}
