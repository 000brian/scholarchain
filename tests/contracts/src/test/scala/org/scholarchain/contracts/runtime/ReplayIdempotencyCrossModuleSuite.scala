package org.scholarchain.contracts.runtime

import java.util.concurrent.atomic.AtomicBoolean

import org.scholarchain.contracts.runtime.CrossModuleHarness.*
import org.scholarchain.core.RunId
import org.scholarchain.rag.runtime.RuntimeServiceFixtures
import org.scholarchain.services.ServiceTestKit
import org.scholarchain.storage.{StorageConfig, StoreException}
import org.scholarchain.storage.ports.*

/** Category 18 (replayed workflow and idempotency), across services, rag and storage: one query run
  * ID per question, put-if-absent bundles, append-only audits, and a crash between the bundle write
  * and the audit write.
  */
class ReplayIdempotencyCrossModuleSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private lazy val sc =
    StorageConfig.from(ServiceTestKit.config).fold(e => sys.error(e.mkString("; ")), identity)

  private val Q = RuntimeServiceFixtures.AnsweredQuestion

  test("the same question under two idempotency keys: one query ID, one bundle, two audits") {
    val s = serve(runtime())
    try {
      val a = query(s, Q, headers = List("Idempotency-Key" -> "phase11-a"))
      val b = query(s, Q, headers = List("Idempotency-Key" -> "phase11-b"))
      assertEquals(a.status, 200, a.body.take(300))
      assertEquals(b.status, 200, b.body.take(300))
      assertEquals(b.header("Idempotent-Replayed"), None)
      assertEquals(a.json("queryId"), b.json("queryId"))
      assertEquals(a.json("answer")("bundleId"), b.json("answer")("bundleId"))
      assertEquals(a.json("answer")("claims"), b.json("answer")("claims"))
      val audits = s.runtime.stores.audits
        .read(RunId(a.json("queryId").str), 10)
        .fold(e => fail(e), identity)
      assertEquals(audits.size, 2, "each run appends one audit attempt")
      assertEquals(audits.map(_.answerHash).distinct.size, 1)
      assertEquals(audits.map(_.bundleId).distinct.size, 1)
    } finally s.close()
  }

  test("an audit write that fails after the bundle write: 502, then a retry gives the same IDs") {
    val failAudit = new AtomicBoolean(true)
    val kv: KeyValueStore => KeyValueStore = base =>
      new KeyValueStore {
        def putIfAbsent(table: String, item: Item): ConditionalResult =
          if (failAudit.get && table == sc.dynamoDb.runLedgerTable)
            throw new StoreException("injected audit failure", true)
          else base.putIfAbsent(table, item)
        def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult =
          if (failAudit.get && table == sc.dynamoDb.runLedgerTable)
            throw new StoreException("injected audit failure", true)
          else base.putIfVersion(table, item, expectedVersion)
        def get(table: String, key: ItemKey): Option[Item] = base.get(table, key)
        def query(
            table: String,
            pk: String,
            skPrefix: Option[String],
            limit: Int,
            descending: Boolean
        ): List[Item] = base.query(table, pk, skPrefix, limit, descending)
      }
    val s = serve(runtime(kv = kv))
    try {
      val queryId = s.runtime.service
        .queryRunId(RuntimeServiceFixtures.answeredRequest)
        .fold(e => fail(e), identity)
      val first = query(s, Q)
      assertEquals(first.status, 502, first.body.take(300))
      assertEquals(first.error, "DOWNSTREAM_FAILURE")
      assertEquals(
        s.runtime.stores.audits.read(queryId, 10).map(_.size),
        Right(0),
        "a failed audit append leaves no audit record"
      )
      failAudit.set(false)
      val retry = query(s, Q)
      assertEquals(retry.status, 200, retry.body.take(300))
      assertEquals(retry.json("queryId").str, queryId.value)
      // the bundle stored before the failure is the one the retry cites
      val bundleId = retry.json("answer")("bundleId").str
      val stored = s.runtime.stores.evidence
        .getBundle(org.scholarchain.core.EvidenceId(bundleId))
        .fold(e => fail(e), identity)
      assert(stored.nonEmpty)
      assertEquals(s.runtime.stores.audits.read(queryId, 10).map(_.size), Right(1))
    } finally s.close()
  }

  test("one key, same question, sync then async: the fingerprint differs, so 409") {
    val s = serve(runtime())
    try {
      val key = "Idempotency-Key" -> "phase11-mode"
      val sync = query(s, Q, headers = List(key))
      assertEquals(sync.status, 200)
      val async = call(
        s,
        "POST",
        "/v1/query",
        Some(ServiceTestKit.queryBody(Q, mode = "async")),
        headers = List(key)
      )
      assertEquals(async.status, 409, async.body.take(300))
      assertEquals(async.error, "IDEMPOTENCY_CONFLICT")
    } finally s.close()
  }

  test("a replay with the same key after the job succeeded never runs the workflow again") {
    val s = serve(runtime())
    try {
      val key = "Idempotency-Key" -> "phase11-replay"
      val first = query(s, Q, headers = List(key))
      assertEquals(first.status, 200)
      val calls = s.runtime.service.model.usage.planner.logicalCalls.get
      val again = (1 to 3).map(_ => query(s, Q, headers = List(key)))
      assert(again.forall(_.status == 200))
      assert(again.forall(_.header("Idempotent-Replayed").contains("true")))
      assertEquals(s.runtime.service.model.usage.planner.logicalCalls.get, calls)
      val audits = s.runtime.stores.audits
        .read(RunId(first.json("queryId").str), 10)
        .fold(e => fail(e), identity)
      assertEquals(audits.size, 1)
    } finally s.close()
  }
}
