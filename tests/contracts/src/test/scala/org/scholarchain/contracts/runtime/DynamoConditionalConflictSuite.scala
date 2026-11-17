package org.scholarchain.contracts.runtime

import org.scholarchain.contracts.runtime.CrossModuleHarness.*
import org.scholarchain.core.EvidenceBundle
import org.scholarchain.rag.runtime.RuntimeServiceFixtures
import org.scholarchain.services.ServiceTestKit
import org.scholarchain.storage.{RetryingKeyValueStore, RetryPolicy, Sleeper, StorageConfig}
import org.scholarchain.storage.aws.DynamoDbKeyValueStore
import org.scholarchain.storage.dynamo.Change
import org.scholarchain.storage.jobs.{
  ConditionalDynamoStub,
  JobChange,
  ManualClock,
  ServiceStoreFixtures
}
import org.scholarchain.storage.runtime.EvidenceStore

/** Category 16 (DynamoDB conditional-write conflict): storage's real `DynamoDbKeyValueStore` over
  * storage's `ConditionalDynamoStub` (which evaluates the put-if-absent and put-if-version
  * conditions), carrying rag's evidence bundles and the services' jobs and idempotency records. No
  * network.
  */
class DynamoConditionalConflictSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private lazy val sc =
    StorageConfig.from(ServiceTestKit.config).fold(e => sys.error(e.mkString("; ")), identity)

  /** A real answer bundle of the fixture world (fake model). */
  private lazy val bundle: EvidenceBundle = {
    val fx = RuntimeServiceFixtures.fixture()
    try
      fx.service.workflow
        .run(RuntimeServiceFixtures.answeredRequest)
        .fold(e => fail(e), identity)
        .response
        .bundle
        .getOrElse(fail("the answered question must have a bundle"))
    finally fx.service.close()
  }

  private def store(kv: org.scholarchain.storage.ports.KeyValueStore) =
    new EvidenceStore(kv, sc.dynamoDb.evidenceBundleTable, 500)

  test("a bundle ID that already holds other content is a Conflict and is never overwritten") {
    val stub = new ConditionalDynamoStub
    val es = store(new DynamoDbKeyValueStore(stub))
    val first = es.putBundle(bundle).fold(e => fail(e), identity)
    assertEquals(first.bundle, Change.Inserted)
    // same bundle ID and item IDs, one stored metric value changed
    val i = bundle.items.indexWhere(_.metric.nonEmpty)
    assert(i >= 0, "the fixture bundle must hold a metric item")
    val changed =
      bundle.items(i).copy(metric = bundle.items(i).metric.map(m => m.copy(value = m.value + 1.0)))
    val other = bundle.copy(items = bundle.items.updated(i, changed))
    assertEquals(other.bundleId, bundle.bundleId)
    val second = es.putBundle(other).fold(e => fail(e), identity)
    assertEquals(second.bundle, Change.Conflict)
    assert(second.itemConflicts.nonEmpty, second.toString)
    assertEquals(es.getBundle(bundle.bundleId), Right(Some(bundle)))
    // the same content again is Unchanged, not a conflict
    assertEquals(es.putBundle(bundle).map(_.bundle), Right(Change.Unchanged))
  }

  test("a lost DynamoDB response (applied, then HTTP 500) retried is Unchanged, not a Conflict") {
    val stub = new ConditionalDynamoStub
    val kv = new RetryingKeyValueStore(
      new DynamoDbKeyValueStore(stub),
      RetryPolicy(2, 1L, 1L),
      Sleeper.none
    )
    stub.applyThenFail(1)
    val w = store(kv).putBundle(bundle).fold(e => fail(e), identity)
    assert(w.bundle == Change.Inserted || w.bundle == Change.Unchanged, w.toString)
    assertEquals(w.itemConflicts, Nil)
    assertEquals(store(kv).getBundle(bundle.bundleId), Right(Some(bundle)))
    assertEquals(kv.counters.retries.get, 1L)
  }

  test("two claims of one job version over DynamoDB: exactly one wins") {
    val stub = new ConditionalDynamoStub
    val clock = new ManualClock()
    val stores = org.scholarchain.storage.jobs.ServiceStores.over(
      new DynamoDbKeyValueStore(stub),
      sc.dynamoDb.querySessionTable,
      new org.scholarchain.storage.local.InMemoryWorkQueue(60000L, clock)
    )
    val now = clock.millis()
    val created = stores.jobs
      .create(ServiceStoreFixtures.newJob("job-00000000-0000-4000-8000-000000000001", now))
      .fold(e => fail(e), identity) match {
      case JobChange.Written(j) => j
      case other                => fail(s"create: $other")
    }
    val a = stores.jobs.claim(created, now, now + 30000L)
    val b = stores.jobs.claim(created, now, now + 30000L)
    val wins = List(a, b).count {
      case Right(JobChange.Written(_)) => true
      case _                           => false
    }
    assertEquals(wins, 1, s"a=$a b=$b")
    assert(stub.conditions.exists(_.contains("#v = :expected")))
  }

  test("services over DynamoDB: a replayed key returns the stored answer; another body is 409") {
    val stub = new ConditionalDynamoStub
    val s = serve(runtime(), jobKv = Some(new DynamoDbKeyValueStore(stub)))
    try {
      val key = "Idempotency-Key" -> "phase11-dynamo-1"
      val first = query(s, RuntimeServiceFixtures.AnsweredQuestion, headers = List(key))
      assertEquals(first.status, 200, first.body.take(300))
      val calls = s.runtime.service.model.usage.synthesis.logicalCalls.get
      val replay = query(s, RuntimeServiceFixtures.AnsweredQuestion, headers = List(key))
      assertEquals(replay.status, 200, replay.body.take(300))
      assertEquals(replay.header("Idempotent-Replayed"), Some("true"))
      assertEquals(replay.json("queryId"), first.json("queryId"))
      assertEquals(replay.json("answer")("claims"), first.json("answer")("claims"))
      assertEquals(
        s.runtime.service.model.usage.synthesis.logicalCalls.get,
        calls,
        "a replay must not run the workflow again"
      )
      val other = query(s, RuntimeServiceFixtures.AbstainedQuestion, headers = List(key))
      assertEquals(other.status, 409, other.body.take(300))
      assertEquals(other.error, "IDEMPOTENCY_CONFLICT")
      assert(stub.puts.get >= 2)
    } finally s.close()
  }
}
