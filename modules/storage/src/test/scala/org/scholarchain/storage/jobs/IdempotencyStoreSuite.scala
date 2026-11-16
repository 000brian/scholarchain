package org.scholarchain.storage.jobs

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import org.scholarchain.core.Hashing
import org.scholarchain.storage.{RetryPolicy, RetryingKeyValueStore, Sleeper, TestFixtures}
import org.scholarchain.storage.aws.DynamoDbKeyValueStore
import org.scholarchain.storage.dynamo.TableSpecs
import org.scholarchain.storage.local.InMemoryKeyValueStore
import org.scholarchain.storage.ports._

/** QS5 behavior over the in-memory tables and over the DynamoDB adapter on a conditional stub. */
abstract class IdempotencyStoreContract extends munit.FunSuite {
  import ServiceStoreFixtures._

  def freshKv(): KeyValueStore

  protected val table: String = querySessionTable
  protected val t0: Long = ManualClock.DefaultStartMillis
  protected val exp: Long = t0 / 1000L + 3600L
  protected def store(kv: KeyValueStore = freshKv()) = new TableIdempotencyStore(kv, table)

  test("first claim wins; a second claim returns the first record unchanged") {
    val s = store()
    val first = s.claim("full", "key-1", "sha256:aaa", "job-1", exp, t0)
    assertEquals(
      first,
      Right(IdempotencyClaim.Claimed(IdempotencyRecord("sha256:aaa", "job-1", exp)))
    )
    val second = s.claim("full", "key-1", "sha256:bbb", "job-2", exp + 10, t0 + 5)
    assertEquals(
      second,
      Right(IdempotencyClaim.Existing(IdempotencyRecord("sha256:aaa", "job-1", exp)))
    )
    assertEquals(
      s.get("full", "key-1", t0 + 5),
      Right(Some(IdempotencyRecord("sha256:aaa", "job-1", exp)))
    )
  }

  test("the same fingerprint from another request still gets Existing (its own job ID differs)") {
    val s = store()
    s.claim("full", "key-1", "sha256:aaa", "job-1", exp, t0)
    assertEquals(
      s.claim("full", "key-1", "sha256:aaa", "job-2", exp, t0),
      Right(IdempotencyClaim.Existing(IdempotencyRecord("sha256:aaa", "job-1", exp)))
    )
  }

  test("scopes are separate: the same key under two caller classes is two records") {
    val s = store()
    assert(
      s.claim("full", "k", "f1", "job-1", exp, t0)
        .toOption
        .exists(_.isInstanceOf[IdempotencyClaim.Claimed])
    )
    assert(
      s.claim("read-only", "k", "f2", "job-2", exp, t0)
        .toOption
        .exists(_.isInstanceOf[IdempotencyClaim.Claimed])
    )
  }

  test("an expired record is absent: get is None and a new claim replaces it") {
    val s = store()
    s.claim("full", "key-1", "sha256:aaa", "job-1", exp, t0)
    val later = exp * 1000L
    assertEquals(s.get("full", "key-1", later), Right(None))
    val again = s.claim("full", "key-1", "sha256:bbb", "job-2", exp + 3600L, later)
    assertEquals(
      again,
      Right(IdempotencyClaim.Claimed(IdempotencyRecord("sha256:bbb", "job-2", exp + 3600L)))
    )
    assertEquals(s.get("full", "key-1", later).map(_.map(_.jobId)), Right(Some("job-2")))
  }

  test("QS5 item: hashed key, and only fingerprint, jobId, expiresAt and version") {
    val kv = freshKv()
    val s = store(kv)
    s.claim("full", "client-key-42", "sha256:aaa", "job-1", exp, t0)
    val pk =
      "idempotency#" + Hashing.sha256Hex(Hashing.canonicalEncoding(Seq("full", "client-key-42")))
    assertEquals(TableSpecs.idempotencyPk("full", "client-key-42"), pk)
    val item = kv.get(table, ItemKey(pk, "idempotency")).get
    assertEquals(
      item.attributes.keySet,
      Set(Item.Pk, Item.Sk, "fingerprint", "jobId", "expiresAt", Item.Version)
    )
    assert(
      !item.attributes.values.exists(_ == AttrValue.S("client-key-42")),
      "the raw key is not stored"
    )
  }

  test("eight concurrent first claims: exactly one Claimed, every other sees the winner") {
    val s = store()
    val pool = Executors.newFixedThreadPool(8)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try {
      val start = new CountDownLatch(1)
      val fs = (1 to 8).map(n =>
        Future { start.await(); s.claim("full", "k", s"f$n", s"job-$n", exp, t0) }
      )
      start.countDown()
      val rs = Await.result(Future.sequence(fs), 30.seconds).map(_.toOption.get)
      val won = rs.collect { case IdempotencyClaim.Claimed(r) => r }
      assertEquals(won.size, 1, rs.toString)
      assert(rs.forall {
        case IdempotencyClaim.Existing(r) => r == won.head
        case _                            => true
      })
    } finally { pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS) }
  }

  test("malformed arguments are Left") {
    val s = store()
    assert(s.claim("full", "bad key", "f", "job-1", exp, t0).isLeft)
    assert(s.claim("full", "k" * 129, "f", "job-1", exp, t0).isLeft)
    assert(s.claim("FULL", "k", "f", "job-1", exp, t0).isLeft)
    assert(s.claim("full", "k", "", "job-1", exp, t0).isLeft)
    assert(s.claim("full", "k", "f", "job 1", exp, t0).isLeft)
    assert(s.claim("full", "k", "f", "job-1", t0 / 1000L, t0).isLeft, "expiresAt must be after now")
    assertEquals(s.get("full", "k", t0), Right(None))
  }

  test("a store failure is Left") {
    val toggle = new ToggleKeyValueStore(freshKv())
    toggle.failing.set(true)
    assert(
      store(toggle)
        .claim("full", "k", "f", "job-1", exp, t0)
        .left
        .exists(_.contains("store failure"))
    )
  }
}

class InMemoryIdempotencyStoreSuite extends IdempotencyStoreContract {
  def freshKv(): KeyValueStore = new InMemoryKeyValueStore(TestFixtures.storageConfig.tableNames)
}

class DynamoIdempotencyStoreSuite extends IdempotencyStoreContract {
  private val policy = RetryPolicy(2, 1, 1)
  def freshKv(): KeyValueStore =
    new RetryingKeyValueStore(
      new DynamoDbKeyValueStore(new ConditionalDynamoStub),
      policy,
      Sleeper.none
    )

  test("DynamoDB: a retried claim whose first put was applied is Claimed") {
    val stub = new ConditionalDynamoStub
    val s = store(new RetryingKeyValueStore(new DynamoDbKeyValueStore(stub), policy, Sleeper.none))
    stub.applyThenFail(1)
    assertEquals(
      s.claim("full", "k", "f", "job-1", exp, t0),
      Right(IdempotencyClaim.Claimed(IdempotencyRecord("f", "job-1", exp)))
    )
    assertEquals(stub.puts.get, 2)
  }

  test("DynamoDB: an expired record is replaced with a version-conditional put") {
    val stub = new ConditionalDynamoStub
    val s = store(new DynamoDbKeyValueStore(stub))
    s.claim("full", "k", "f", "job-1", exp, t0)
    s.claim("full", "k", "g", "job-2", exp + 3600L, exp * 1000L + 1)
    assertEquals(
      stub.conditions.toList,
      List(
        "attribute_not_exists(pk)",
        "attribute_not_exists(pk)",
        "attribute_exists(pk) AND #v = :expected"
      )
    )
  }
}
