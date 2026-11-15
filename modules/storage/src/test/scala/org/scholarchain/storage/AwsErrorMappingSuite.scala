package org.scholarchain.storage

import scala.jdk.CollectionConverters._

import org.scholarchain.storage.aws.{
  AwsErrors,
  DynamoDbKeyValueStore,
  S3ObjectStore,
  S3VectorsIndex
}
import org.scholarchain.storage.dynamo.{Change, ConditionalWriter, ItemHash}
import org.scholarchain.storage.ingest.TextStore
import org.scholarchain.storage.ports._
import org.scholarchain.storage.s3.S3KeyLayout
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.exception.{SdkClientException, SdkException}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue => Av,
  ConditionalCheckFailedException,
  DynamoDbException,
  GetItemRequest,
  GetItemResponse,
  PutItemRequest,
  PutItemResponse
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  HeadObjectResponse,
  NoSuchKeyException,
  PutObjectRequest,
  PutObjectResponse,
  S3Exception
}
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetIndexRequest,
  GetIndexResponse,
  NotFoundException,
  S3VectorsException
}

/** The AWS adapters' exception mapping, without AWS: stub SDK clients throw the exact service
  * exceptions the real services raise, and the adapters must turn the expected outcomes
  * (conditional-check failure, missing key, missing index, S3 412) into their port results while
  * every other SDK exception still surfaces as a `StoreException` with the right retry class.
  */
class AwsErrorMappingSuite extends munit.FunSuite {
  import TestFixtures._

  private val table = "example-table"

  private def ccf(): ConditionalCheckFailedException =
    ConditionalCheckFailedException
      .builder()
      .message("The conditional request failed")
      .statusCode(400)
      .build()
      .asInstanceOf[ConditionalCheckFailedException]

  private def toAv(v: AttrValue): Av = v match {
    case AttrValue.S(s)    => Av.fromS(s)
    case AttrValue.N(n)    => Av.fromN(n)
    case AttrValue.Bool(b) => Av.fromBool(b)
    case AttrValue.L(xs)   => Av.fromL(xs.map(toAv).asJava)
    case AttrValue.M(m)    => Av.fromM(m.map { case (k, x) => k -> toAv(x) }.asJava)
  }

  /** DynamoDB stub: every PutItem throws `onPut`; GetItem returns `stored` (if any). */
  private final class DynamoStub(onPut: () => Throwable, stored: Option[Item])
      extends DynamoDbClient {
    var puts = 0
    def serviceName(): String = "dynamodb"
    def close(): Unit = ()
    override def putItem(r: PutItemRequest): PutItemResponse = {
      puts += 1
      throw onPut()
    }
    override def getItem(r: GetItemRequest): GetItemResponse =
      stored match {
        case Some(i) =>
          GetItemResponse
            .builder()
            .item(i.attributes.map { case (k, v) => k -> toAv(v) }.asJava)
            .build()
        case None => GetItemResponse.builder().build()
      }
  }

  private def item(v: String) =
    Item(Item.Pk -> AttrValue.S("p"), Item.Sk -> AttrValue.S("s"), "value" -> AttrValue.S(v))

  // ---- DynamoDB conditional writes ----

  test("DynamoDB putIfAbsent returns ConditionFailed on ConditionalCheckFailedException") {
    val kv = new DynamoDbKeyValueStore(new DynamoStub(() => ccf(), None))
    assertEquals(kv.putIfAbsent(table, item("1")), ConditionalResult.ConditionFailed)
  }

  test("DynamoDB putIfVersion returns ConditionFailed on ConditionalCheckFailedException") {
    val kv = new DynamoDbKeyValueStore(new DynamoStub(() => ccf(), None))
    assertEquals(
      kv.putIfVersion(table, ItemHash.stamp(item("1"), 2), 1),
      ConditionalResult.ConditionFailed
    )
  }

  test("ConditionalWriter.upsertIfChanged on the AWS adapter: same hash is Unchanged") {
    val existing = ItemHash.stamp(item("1"), 1)
    val stub = new DynamoStub(() => ccf(), Some(existing))
    val w = new ConditionalWriter(new DynamoDbKeyValueStore(stub))
    assertEquals(w.upsertIfChanged(table, item("1")), Change.Unchanged)
    assertEquals(stub.puts, 1, "an unchanged item is never rewritten")
  }

  test("ConditionalWriter.upsertIfChanged on the AWS adapter: lost version race is Conflict") {
    val existing = ItemHash.stamp(item("old"), 1)
    val stub = new DynamoStub(() => ccf(), Some(existing))
    val w = new ConditionalWriter(new DynamoDbKeyValueStore(stub))
    assertEquals(w.upsertIfChanged(table, item("new")), Change.Conflict)
    assertEquals(stub.puts, 2, "putIfAbsent, then one putIfVersion")
  }

  test("ConditionalWriter.append on the AWS adapter: rerun is Unchanged, other content Conflict") {
    val existing = ItemHash.stamp(item("1"), 1)
    val w = new ConditionalWriter(
      new DynamoDbKeyValueStore(new DynamoStub(() => ccf(), Some(existing)))
    )
    assertEquals(w.append(table, item("1")), Change.Unchanged)
    assertEquals(w.append(table, item("2")), Change.Conflict)
  }

  test("DynamoDB: other service errors still become StoreException with the right retry class") {
    val denied = DynamoDbException
      .builder()
      .message("denied")
      .statusCode(400)
      .build()
    val e1 = intercept[StoreException](
      new DynamoDbKeyValueStore(new DynamoStub(() => denied, None)).putIfAbsent(table, item("1"))
    )
    assert(!e1.transient, e1.getMessage)
    val unavailable = DynamoDbException.builder().message("down").statusCode(503).build()
    val e2 = intercept[StoreException](
      new DynamoDbKeyValueStore(new DynamoStub(() => unavailable, None))
        .putIfVersion(table, item("1"), 1)
    )
    assert(e2.transient, e2.getMessage)
    val client = SdkClientException.builder().message("io").build()
    intercept[StoreException](
      new DynamoDbKeyValueStore(new DynamoStub(() => client, None)).putIfAbsent(table, item("1"))
    )
  }

  // ---- S3 ----

  /** S3 stub: GetObject throws `onGet`, or returns `body`; PutObject throws `onPut`; HeadObject
    * returns `headSha`.
    */
  private final class S3Stub(
      onGet: Option[() => Throwable],
      body: Array[Byte] = Array.emptyByteArray,
      onPut: Option[() => Throwable] = None,
      headSha: Option[String] = None
  ) extends S3Client {
    def serviceName(): String = "s3"
    def close(): Unit = ()
    override def getObjectAsBytes(r: GetObjectRequest): ResponseBytes[GetObjectResponse] =
      onGet match {
        case Some(e) => throw e()
        case None    => ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), body)
      }
    override def putObject(r: PutObjectRequest, b: RequestBody): PutObjectResponse =
      onPut match {
        case Some(e) => throw e()
        case None    => PutObjectResponse.builder().build()
      }
    override def headObject(r: HeadObjectRequest): HeadObjectResponse =
      HeadObjectResponse
        .builder()
        .contentLength(1L)
        .metadata(
          headSha
            .map(s => Map(ObjectStore.Sha256MetadataKey -> s))
            .getOrElse(Map.empty[String, String])
            .asJava
        )
        .build()
  }

  private def noSuchKey(): Throwable =
    NoSuchKeyException.builder().message("missing").statusCode(404).build()

  private val ref = ObjectRef("example-bucket", "some/key.txt")

  test("S3 get returns None on NoSuchKeyException") {
    assertEquals(new S3ObjectStore(new S3Stub(Some(() => noSuchKey()))).get(ref), None)
  }

  test("TextStore.fetch returns Left (not a throw) when the object is missing") {
    val ts = new TextStore(
      new S3ObjectStore(new S3Stub(Some(() => noSuchKey()))),
      new S3KeyLayout(storageConfig.zones)
    )
    val r = ts.fetch("s3://example-bucket/some/key.txt", "sha256:" + "0" * 64)
    assert(r.isLeft, r.toString)
    assert(r.left.toOption.get.contains("no object"), r.toString)
  }

  test("S3 get: other errors still become StoreException") {
    val forbidden = () => S3Exception.builder().message("denied").statusCode(403).build()
    val e = intercept[StoreException](new S3ObjectStore(new S3Stub(Some(forbidden))).get(ref))
    assert(!e.transient, e.getMessage)
  }

  test(
    "S3 putIfAbsent maps HTTP 412 to AlreadyPresent or Conflict, other errors to StoreException"
  ) {
    val bytes = "hello".getBytes("UTF-8")
    val sha = org.scholarchain.core.Hashing.sha256Hex(bytes)
    val pre = () => S3Exception.builder().message("PreconditionFailed").statusCode(412).build()
    assertEquals(
      new S3ObjectStore(new S3Stub(None, onPut = Some(pre), headSha = Some(sha)))
        .putIfAbsent(ref, bytes, "text/plain", Map.empty),
      PutOutcome.AlreadyPresent
    )
    assertEquals(
      new S3ObjectStore(new S3Stub(None, onPut = Some(pre), headSha = Some("f" * 64)))
        .putIfAbsent(ref, bytes, "text/plain", Map.empty),
      PutOutcome.Conflict(Some("f" * 64))
    )
    val slow = () => S3Exception.builder().message("SlowDown").statusCode(503).build()
    val e = intercept[StoreException](
      new S3ObjectStore(new S3Stub(None, onPut = Some(slow)))
        .putIfAbsent(ref, bytes, "text/plain", Map.empty)
    )
    assert(e.transient, e.getMessage)
  }

  // ---- S3 Vectors ----

  private final class VectorsStub(onGet: () => Throwable) extends S3VectorsClient {
    def serviceName(): String = "s3vectors"
    def close(): Unit = ()
    override def getIndex(r: GetIndexRequest): GetIndexResponse = throw onGet()
  }

  test("S3 Vectors describeIndex returns None on NotFoundException") {
    val nf = () => NotFoundException.builder().message("no index").statusCode(404).build()
    val idx = new S3VectorsIndex(new VectorsStub(nf), "example-vectors", _ => Set.empty)
    assertEquals(idx.describeIndex("researcher-profile-v1"), None)
  }

  test("S3 Vectors describeIndex: other errors still become StoreException") {
    val denied = () => S3VectorsException.builder().message("denied").statusCode(403).build()
    val idx = new S3VectorsIndex(new VectorsStub(denied), "example-vectors", _ => Set.empty)
    val e = intercept[StoreException](idx.describeIndex("researcher-profile-v1"))
    assert(!e.transient, e.getMessage)
  }

  // ---- AwsErrors itself ----

  test("AwsErrors.guard wraps every SdkException; recover maps only the listed outcomes") {
    val boom: SdkException = SdkClientException.builder().message("boom").build()
    intercept[StoreException](AwsErrors.guard[Int](throw boom))
    val expected: PartialFunction[SdkException, String] = {
      case _: ConditionalCheckFailedException => "failed"
    }
    assertEquals(AwsErrors.recover(expected)(throw ccf()), "failed")
    assertEquals(AwsErrors.recover(expected)("ok"), "ok")
    val other = intercept[StoreException](AwsErrors.recover(expected)(throw boom))
    assertEquals(other.getCause, boom)
    // a StoreException raised inside the block passes through unchanged
    val inner = new StoreException("inner", true)
    assert(intercept[StoreException](AwsErrors.recover(expected)(throw inner)) eq inner)
  }
}
