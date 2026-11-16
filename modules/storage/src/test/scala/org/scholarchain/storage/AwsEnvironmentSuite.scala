package org.scholarchain.storage

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.scholarchain.core.ResearcherId
import org.scholarchain.storage.aws._
import org.scholarchain.storage.ports._
import org.scholarchain.storage.s3.S3KeyLayout
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.http.{
  AbortableInputStream,
  ExecutableHttpRequest,
  HttpExecuteRequest,
  HttpExecuteResponse,
  SdkHttpClient,
  SdkHttpResponse
}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  PutItemRequest,
  PutItemResponse,
  QueryRequest,
  QueryResponse
}
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse,
  NeptunedataException,
  StartLoaderJobRequest,
  StartLoaderJobResponse
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  HeadObjectRequest,
  HeadObjectResponse,
  NoSuchKeyException
}
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetIndexRequest,
  GetIndexResponse,
  NotFoundException
}

/** A1 wiring, m1, m2 and the Neptune path search, without AWS and without network: stub SDK
  * clients, or real SDK clients over a stub HTTP transport.
  */
class AwsEnvironmentSuite extends munit.FunSuite {
  import TestFixtures._

  private val settings = AwsClientSettings("us-east-1", 1000, 1000, 5000, 1)

  // ---- stub SDK clients ------------------------------------------------------------------

  private final class DynamoStub extends DynamoDbClient {
    val puts = mutable.ListBuffer.empty[PutItemRequest]
    val queries = mutable.ListBuffer.empty[QueryRequest]
    def serviceName(): String = "dynamodb"
    def close(): Unit = ()
    override def putItem(r: PutItemRequest): PutItemResponse = {
      puts += r; PutItemResponse.builder().build()
    }
    override def query(r: QueryRequest): QueryResponse = {
      queries += r; QueryResponse.builder().items(java.util.List.of()).build()
    }
  }

  private final class S3Stub extends S3Client {
    var heads = 0
    def serviceName(): String = "s3"
    def close(): Unit = ()
    override def headObject(r: HeadObjectRequest): HeadObjectResponse = {
      heads += 1
      throw NoSuchKeyException.builder().message("missing").statusCode(404).build()
    }
  }

  private final class VectorsStub extends S3VectorsClient {
    var gets = 0
    def serviceName(): String = "s3vectors"
    def close(): Unit = ()
    override def getIndex(r: GetIndexRequest): GetIndexResponse = {
      gets += 1
      throw NotFoundException.builder().message("no index").statusCode(404).build()
    }
  }

  /** Neptune stub: `rows(cypher)` gives the rows the server would match; the stub honours the
    * literal `LIMIT n` in the text like Neptune does. `fail(cypher)` may throw instead.
    */
  private final class NeptuneStub(
      rows: String => List[Map[String, String]],
      fail: String => Option[Throwable] = _ => None
  ) extends NeptunedataClient {
    val requests = mutable.ListBuffer.empty[ExecuteOpenCypherQueryRequest]
    var loaderRequest: Option[StartLoaderJobRequest] = None
    def serviceName(): String = "neptune-db"
    def close(): Unit = ()
    private val Limit = """LIMIT (\d+)$""".r.unanchored
    override def executeOpenCypherQuery(
        r: ExecuteOpenCypherQueryRequest
    ): ExecuteOpenCypherQueryResponse = {
      requests += r
      fail(r.openCypherQuery()).foreach(e => throw e)
      val n = r.openCypherQuery() match {
        case Limit(x) => x.toInt
        case _        => Int.MaxValue
      }
      val docs = rows(r.openCypherQuery()).take(n).map { m =>
        Document.fromMap(m.map { case (k, v) => k -> Document.fromString(v) }.asJava)
      }
      ExecuteOpenCypherQueryResponse.builder().results(Document.fromList(docs.asJava)).build()
    }
    override def startLoaderJob(r: StartLoaderJobRequest): StartLoaderJobResponse = {
      loaderRequest = Some(r)
      StartLoaderJobResponse
        .builder()
        .status("200")
        .payload(Map("loadId" -> "load-1").asJava)
        .build()
    }
  }

  private def depthOf(cypher: String): Int =
    """\*(\d+)\.\.""".r.findFirstMatchIn(cypher).map(_.group(1).toInt).getOrElse(0)

  // ---- stub HTTP transport (for real SDK clients) -----------------------------------------

  /** Answers request `n` (1-based) with `respond(n)`; records every request. */
  private final class StubHttp(respond: Int => (Int, Map[String, String], String))
      extends SdkHttpClient {
    val calls = new AtomicInteger()
    val hosts = mutable.ListBuffer.empty[String]
    override def prepareRequest(r: HttpExecuteRequest): ExecutableHttpRequest =
      new ExecutableHttpRequest {
        def call(): HttpExecuteResponse = {
          val n = calls.incrementAndGet()
          hosts += r.httpRequest().host()
          val (status, headers, body) = respond(n)
          val bytes = body.getBytes(StandardCharsets.UTF_8)
          val b = SdkHttpResponse.builder().statusCode(status)
          (headers + ("Content-Length" -> bytes.length.toString)).foreach { case (k, v) =>
            b.putHeader(k, v)
          }
          HttpExecuteResponse
            .builder()
            .response(b.build())
            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(bytes)))
            .build()
        }
        def abort(): Unit = ()
      }
    def close(): Unit = ()
  }

  private val json = Map("Content-Type" -> "application/x-amz-json-1.0")
  private val throttled = (
    400,
    json + ("x-amzn-ErrorType" -> "ThrottlingException"),
    """{"__type":"com.amazonaws.dynamodb.v20120810#ThrottlingException","message":"Rate exceeded"}"""
  )
  private val ok = (200, json, "{}")

  private def realClients(http: StubHttp) =
    AwsSdkClients.build(
      settings,
      runtimeStorageConfig,
      Some(http),
      Some(AnonymousCredentialsProvider.create())
    )

  // ---- A1 -------------------------------------------------------------------------------

  test("A1: building the AWS environment from real SDK clients makes no network call") {
    val http = new StubHttp(_ => fail("no request expected"))
    val env = AwsStorageEnvironment.create(runtimeStorageConfig, realClients(http), None)
    assert(env.graph.isRight, env.graph.toString)
    assert(env.runtimeStores.isRight)
    env.pipeline(new local.StubEmbedder("m", 1024, 8), "dblp")
    assertEquals(http.calls.get, 0)
    env.close()
    // the default transport and default credentials also build without connecting
    AwsStorageEnvironment.fromConfig(runtimeStorageConfig, settings, None).close()
  }

  test("A1: every store of the AWS environment reaches its own SDK client") {
    val (dyn, s3, vec) = (new DynamoStub, new S3Stub, new VectorsStub)
    val nep = new NeptuneStub(_ => List(Map("researcherId" -> rA.value)))
    val env = AwsStorageEnvironment.create(
      runtimeStorageConfig,
      AwsSdkClients(s3, dyn, vec, Some(nep)),
      None,
      Sleeper.none
    )
    val item = Item(Item.Pk -> AttrValue.S("p"), Item.Sk -> AttrValue.S("s"))
    assertEquals(
      env.kv.putIfAbsent(runtimeStorageConfig.dynamoDb.runLedgerTable, item),
      ConditionalResult.Written
    )
    assertEquals(dyn.puts.map(_.tableName()).toList, List("example-run-ledger"))
    assertEquals(dyn.puts.head.conditionExpression(), "attribute_not_exists(pk)")
    val ref = new S3KeyLayout(runtimeStorageConfig.zones)
      .textObject("researcher-profile-v1", "sha256:" + "a" * 64)
    assertEquals(env.objects.head(ref), None)
    assertEquals(s3.heads, 1)
    assertEquals(env.vectors.describeIndex("researcher-profile-v1"), None)
    assertEquals(vec.gets, 1)
    val stores = env.runtimeStores.toOption.get
    val r = stores.graph.run(GraphQuery.ResearcherById(rA)).toOption.get
    assertEquals(r.rows.head.values("researcherId").str, rA.value)
    assertEquals(nep.requests.size, 1)
    assertEquals(
      stores.metrics.ranking(Full, org.scholarchain.core.CollaborationMetric.Coreness, 3),
      Right(Nil)
    )
    assertEquals(dyn.queries.last.tableName(), "example-researcher-metric-cache")
    assertEquals(stores.settings.rankingCacheTopK, 3)
  }

  test("A1: without the runtime or neptune section the graph and runtime stores are refused") {
    val env = AwsStorageEnvironment.create(
      storageConfig, // no runtime section
      AwsSdkClients(new S3Stub, new DynamoStub, new VectorsStub, Some(new NeptuneStub(_ => Nil))),
      None
    )
    assert(env.graph.left.toOption.exists(_.contains("runtime")), env.graph.toString)
    assert(env.runtimeStores.isLeft)
    val noNeptune = AwsStorageEnvironment.create(
      runtimeStorageConfig,
      AwsSdkClients(new S3Stub, new DynamoStub, new VectorsStub, None),
      None
    )
    assert(noNeptune.graph.left.toOption.exists(_.contains("neptune")))
  }

  // ---- m2 -------------------------------------------------------------------------------

  test("m2: SDK maxAttempts is 1; a client throttled twice makes 3 SDK calls and 2 retries") {
    val http = new StubHttp(n => if (n <= 2) throttled else ok)
    val env =
      AwsStorageEnvironment.create(runtimeStorageConfig, realClients(http), None, Sleeper.none)
    assertEquals(http.calls.get, 0)
    assertEquals(env.kv.get(runtimeStorageConfig.dynamoDb.runLedgerTable, ItemKey("p", "s")), None)
    assertEquals(http.calls.get, 3, "exactly 3 SDK requests")
    assertEquals(env.kv.counters.retries.get, 2L, "storage Retry counted both retries")
    assertEquals(env.kv.counters.calls.get, 3L)
    assert(http.hosts.forall(_.contains("dynamodb")), http.hosts.toString)
    env.close()
  }

  test("m2 contrast: an SDK client with 3 attempts hides the throttles from storage's counts") {
    // the pre-fix configuration: SDK retries inside storage's Retry
    val http = new StubHttp(n => if (n <= 2) throttled else ok)
    val sdkRetrying = DynamoDbClient
      .builder()
      .region(software.amazon.awssdk.regions.Region.US_EAST_1)
      .httpClient(http)
      .credentialsProvider(AnonymousCredentialsProvider.create())
      .overrideConfiguration(
        software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
          .builder()
          .retryStrategy(
            software.amazon.awssdk.awscore.retry.AwsRetryStrategy
              .standardRetryStrategy()
              .toBuilder()
              .maxAttempts(3)
              .backoffStrategy(
                software.amazon.awssdk.retries.api.BackoffStrategy.retryImmediately()
              )
              .throttlingBackoffStrategy(
                software.amazon.awssdk.retries.api.BackoffStrategy.retryImmediately()
              )
              .build()
          )
          .build()
      )
      .build()
    val kv = new RetryingKeyValueStore(
      new DynamoDbKeyValueStore(sdkRetrying),
      runtimeStorageConfig.retryPolicy,
      Sleeper.none
    )
    assertEquals(kv.get(runtimeStorageConfig.dynamoDb.runLedgerTable, ItemKey("p", "s")), None)
    assertEquals(http.calls.get, 3)
    assertEquals(kv.counters.retries.get, 0L, "the two retries are invisible to storage")
    sdkRetrying.close()
  }

  test("m2: a permanent error is one SDK call and no retry; retries stop at maxRetries") {
    val denied = (
      400,
      json,
      """{"__type":"com.amazonaws.dynamodb.v20120810#ValidationException","message":"bad"}"""
    )
    val http = new StubHttp(_ => denied)
    val env =
      AwsStorageEnvironment.create(runtimeStorageConfig, realClients(http), None, Sleeper.none)
    val e = intercept[StoreException](
      env.kv.get(runtimeStorageConfig.dynamoDb.runLedgerTable, ItemKey("p", "s"))
    )
    assert(!e.transient, e.getMessage)
    assertEquals((http.calls.get, env.kv.counters.retries.get), (1, 0L))
    val always = new StubHttp(_ => throttled)
    val env2 =
      AwsStorageEnvironment.create(runtimeStorageConfig, realClients(always), None, Sleeper.none)
    intercept[StoreException](
      env2.kv.get(runtimeStorageConfig.dynamoDb.runLedgerTable, ItemKey("p", "s"))
    )
    val maxRetries = runtimeStorageConfig.vectors.maxRetries
    assertEquals(always.calls.get, maxRetries + 1)
    assertEquals(env2.kv.counters.retries.get, maxRetries.toLong)
  }

  test("m2: AwsClientSettings refuses SDK maxAttempts other than 1") {
    intercept[IllegalArgumentException](AwsClientSettings("us-east-1", 1000, 1000, 5000, 3))
    intercept[IllegalArgumentException](AwsClientSettings("us-east-1", 1000, 1000, 5000, 0))
    assertEquals(AwsClientSettings.SdkMaxAttempts, 1)
  }

  // ---- m1 -------------------------------------------------------------------------------

  private def neighbourRows(n: Int): String => List[Map[String, String]] =
    _ => (1 to n).toList.map(i => Map("coauthorId" -> s"c$i"))

  test(
    "m1: Neptune asks for limit + 1 rows and reports truncated only when more than limit exist"
  ) {
    val stub = new NeptuneStub(neighbourRows(5))
    val g = new NeptuneGraphRead(stub, graphSettings)
    val four = g.run(GraphQuery.CoauthorNeighbors(rA, Full, 4)).toOption.get
    assert(
      stub.requests.last.openCypherQuery().endsWith("LIMIT 5"),
      stub.requests.last.openCypherQuery()
    )
    assertEquals((four.rows.size, four.truncated, four.complete), (4, true, true))
    val five = g.run(GraphQuery.CoauthorNeighbors(rA, Full, 5)).toOption.get
    assertEquals((five.rows.size, five.truncated), (5, false), "exactly limit rows: not truncated")
    // a server that returns limit + 1 rows: limit rows back, truncated
    val plusOne = new NeptuneGraphRead(new NeptuneStub(neighbourRows(4)), graphSettings)
      .run(GraphQuery.CoauthorNeighbors(rA, Full, 3))
      .toOption
      .get
    assertEquals(
      (plusOne.rows.map(_.values("coauthorId").str), plusOne.truncated),
      (List("c1", "c2", "c3"), true)
    )
    val params = ujson.read(stub.requests.last.parameters())
    assertEquals(params("researcherId").str, rA.value)
    assert(!params.obj.contains("limit"), "the limit is literal text, not a parameter")
  }

  // ---- path search ----------------------------------------------------------------------

  private def pathRow = Map("researcherIds" -> "x", "hops" -> "2")

  test("path search on Neptune: depth 1, then 2, stopping at the first path") {
    val stub = new NeptuneStub(c => if (depthOf(c) == 2) List(pathRow, pathRow) else Nil)
    val r = new NeptuneGraphRead(stub, graphSettings)
      .run(GraphQuery.PathBetween(rA, rD, Full, 4))
      .toOption
      .get
    assertEquals(stub.requests.map(q => depthOf(q.openCypherQuery())).toList, List(1, 2))
    assertEquals((r.rows.size, r.truncated, r.complete, r.depthSearched), (1, true, true, Some(2)))
    stub.requests.foreach { q =>
      assert(q.openCypherQuery().contains(s"{windowLabel: '$Full'}"), q.openCypherQuery())
      assert(!q.openCypherQuery().contains(rA.value) && !q.openCypherQuery().contains(rD.value))
      val p = ujson.read(q.parameters())
      assertEquals((p("from").str, p("to").str), (rA.value, rD.value))
      val t = q.overrideConfiguration().get().apiCallTimeout().get().toMillis
      assert(t >= 1 && t <= graphSettings.timeoutMillis, t.toString)
    }
  }

  test("path search on Neptune: no path within depth d is complete") {
    val stub = new NeptuneStub(_ => Nil)
    val r = new NeptuneGraphRead(stub, graphSettings)
      .run(GraphQuery.PathBetween(rA, rD, Full, 3))
      .toOption
      .get
    assertEquals(stub.requests.size, 3)
    assertEquals((r.rows, r.complete, r.depthSearched), (Nil, true, Some(3)))
  }

  test("path search on Neptune: stopping at the time limit is not complete") {
    var t = 0L
    val clock = () => { val now = t; t += 600; now } // the budget is 1000 ms
    val stub = new NeptuneStub(_ => Nil)
    val r = new NeptuneGraphRead(stub, graphSettings, clock)
      .run(GraphQuery.PathBetween(rA, rD, Full, 4))
      .toOption
      .get
    assertEquals(stub.requests.size, 1, "depth 2 never starts: the budget is spent")
    assertEquals((r.rows, r.complete, r.depthSearched), (Nil, false, Some(1)))
    // the per-call timeout is the remaining budget
    assertEquals(
      stub.requests.head.overrideConfiguration().get().apiCallTimeout().get().toMillis,
      400L
    )
  }

  test("path search on Neptune: a call timeout stops the search; other errors are Left") {
    val timeout = new NeptuneStub(
      _ => Nil,
      c =>
        if (depthOf(c) == 2) Some(ApiCallTimeoutException.builder().message("slow").build())
        else None
    )
    val r = new NeptuneGraphRead(timeout, graphSettings)
      .run(GraphQuery.PathBetween(rA, rD, Full, 4))
      .toOption
      .get
    assertEquals((r.complete, r.depthSearched), (false, Some(1)))
    val broken = new NeptuneStub(
      _ => Nil,
      _ => Some(NeptunedataException.builder().message("bad query").statusCode(400).build())
    )
    assert(
      new NeptuneGraphRead(broken, graphSettings)
        .run(GraphQuery.PathBetween(rA, rD, Full, 2))
        .isLeft
    )
    assert(new NeptuneGraphRead(broken, graphSettings).run(GraphQuery.ResearcherById(rA)).isLeft)
  }

  test("WorkById on Neptune: validated work ID as a parameter, LIMIT 2, one row back") {
    val stub = new NeptuneStub(_ => List(Map("workId" -> w1.value, "displayName" -> "T")))
    val r = new NeptuneGraphRead(stub, graphSettings).run(GraphQuery.WorkById(w1)).toOption.get
    assertEquals(r.rows.map(_.values("displayName").str), List("T"))
    assert(stub.requests.head.openCypherQuery().endsWith("LIMIT 2"))
    assertEquals(ujson.read(stub.requests.head.parameters())("workId").str, w1.value)
    assert(
      new NeptuneGraphRead(stub, graphSettings)
        .run(GraphQuery.WorkById(org.scholarchain.core.WorkId(rA.value)))
        .isLeft
    )
  }

  // ---- A2 -------------------------------------------------------------------------------

  test(
    "A2: the bulk loader sends userProvidedEdgeIds and never updateSingleCardinalityProperties"
  ) {
    val stub = new NeptuneStub(_ => Nil)
    val job = neptune.BulkLoadRequests
      .build(
        ObjectRef("example-graph-bucket", "graph/run=x/neptune/edges/"),
        "arn:aws:iam::123456789012:role/example-neptune-loader",
        "us-east-1",
        List("load-0")
      )
      .toOption
      .get
    assertEquals(new NeptuneBulkLoader(stub).start(job), "load-1")
    val req = stub.loaderRequest.get
    assertEquals(req.userProvidedEdgeIds(), java.lang.Boolean.TRUE)
    assertEquals(req.updateSingleCardinalityProperties(), null)
    assertEquals(req.parallelismAsString(), "MEDIUM")
    assertEquals(req.dependencies().asScala.toList, List("load-0"))
  }

  test("identifiers never reach Neptune text even when the graph port is driven directly") {
    val stub = new NeptuneStub(_ => Nil)
    val g = new NeptuneGraphRead(stub, graphSettings)
    assert(g.run(GraphQuery.ResearcherById(ResearcherId("researcher:v1:' RETURN 1 //"))).isLeft)
    assert(g.run(GraphQuery.PathBetween(rA, rD, "full-2015-2025'}]-(x) RETURN x //", 2)).isLeft)
    assertEquals(stub.requests.size, 0, "rejected before any query text is sent")
  }
}
