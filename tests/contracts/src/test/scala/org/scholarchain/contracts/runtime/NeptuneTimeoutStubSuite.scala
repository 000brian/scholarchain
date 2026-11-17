package org.scholarchain.contracts.runtime

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import org.scholarchain.contracts.runtime.CrossModuleHarness.*
import org.scholarchain.storage.StoreException
import org.scholarchain.storage.aws.{AwsErrors, NeptuneGraphRead}
import org.scholarchain.storage.ports.*
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse
}

/** Category 15 (Neptune timeout): storage's real `NeptuneGraphRead` over a stub `NeptunedataClient`
  * that times out, under rag's workflow and the services HTTP layer. No network.
  */
class NeptuneTimeoutStubSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val Endpoint = "db-neptune-1.cluster-abc123.us-east-1.neptune.amazonaws.com"

  /** Every openCypher call times out on the client side (as the SDK does at `apiCallTimeout`). */
  final class TimingOutNeptune extends NeptunedataClient {
    val calls = new AtomicInteger(0)
    def serviceName(): String = "neptune-db"
    def close(): Unit = ()
    override def executeOpenCypherQuery(
        r: ExecuteOpenCypherQueryRequest
    ): ExecuteOpenCypherQueryResponse = {
      calls.incrementAndGet()
      throw ApiCallTimeoutException
        .builder()
        .message(
          s"Client execution did not complete before the specified timeout configuration: 1000 millis ($Endpoint)"
        )
        .build()
    }
  }

  /** Path searches and neighbour lookups go to Neptune over `stub`; every other template is read
    * from the fixture graph.
    */
  private def viaNeptune(stub: NeptunedataClient)(base: GraphReadPort): GraphReadPort = {
    val neptune = new NeptuneGraphRead(stub, base.settings, () => 0L)
    new GraphReadPort {
      def settings: GraphReadSettings = base.settings
      def run(q: GraphQuery): Either[String, GraphResult] = q match {
        case _: GraphQuery.PathBetween | _: GraphQuery.CoauthorNeighbors => neptune.run(q)
        case other                                                       => base.run(other)
      }
    }
  }

  test("a path search that times out at every depth is reported unknown, never 'no path'") {
    val stub = new TimingOutNeptune
    val s = serve(runtime(graph = viaNeptune(stub)))
    try {
      val r = query(
        s,
        "What is the shortest collaboration path between Bob Jones and Dan Brown in 2015-2025?"
      )
      assertEquals(r.status, 200, r.body.take(300))
      assert(stub.calls.get >= 1, "the path search must reach the Neptune adapter")
      val text = r.json("answer")("answerText").str
      assert(!text.contains("No path between"), text)
      assert(text.contains("whether a path exists is unknown"), text)
      val claims = r.json("answer")("claims").arr
      assert(
        !claims.exists(c => c("status").str == "verified" && c("claimText").str.contains("hop")),
        claims.map(_.render()).mkString("\n")
      )
    } finally s.close()
  }

  test("a neighbour lookup that times out is never presented as an empty collaborator list") {
    val stub = new TimingOutNeptune
    val s = serve(runtime(graph = viaNeptune(stub)))
    try {
      val r = query(s, "Who are the direct collaborators of Carol King in 2015-2025?")
      assert(r.status == 200 || r.status == 502, r.body.take(300))
      assert(stub.calls.get >= 1)
      if (r.status == 200) {
        val a = r.json("answer")
        val text = a("answerText").str
        assert(a("abstained").bool || text.contains("neighbor lookup failed"), text)
        assert(!text.contains("has no direct coauthor"), text)
        assert(!text.contains(" 0 "), text)
      }
    } finally s.close()
  }

  // Same as B-01 (reviewer slice B; owner storage-engineer): AwsErrors.wrap maps an SDK client
  // timeout as permanent, so storage's bounded retry never retries a Neptune or DynamoDB timeout.
  // Repaired in Phase 11 step 3e; the .fail mark was removed in step 4.
  test("B-01 a client-side API call timeout is classified transient") {
    val e = AwsErrors.wrap(ApiCallTimeoutException.builder().message("timed out").build())
    // the specific wrong value: transient = false
    assertEquals(e.transient, true)
  }

  test("repeated store failures open the workflow circuit; it recovers after the open period") {
    val failing = new AtomicBoolean(true)
    val graphCalls = new AtomicInteger(0)
    val toggled: GraphReadPort => GraphReadPort = base =>
      new GraphReadPort {
        def settings: GraphReadSettings = base.settings
        def run(q: GraphQuery): Either[String, GraphResult] = {
          graphCalls.incrementAndGet()
          if (failing.get) throw new StoreException("injected timeout", true) else base.run(q)
        }
      }
    val s = serve(runtime(graph = toggled))
    try {
      val st = s.server.settings
      val q = "Who are the direct collaborators of Carol King in 2015-2025?"
      (1 to st.circuitFailureThreshold).foreach { i =>
        val r = query(s, q)
        assertEquals(r.status, 502, s"failure $i: ${r.body.take(200)}")
      }
      val before = graphCalls.get
      val open = query(s, q)
      assertEquals(open.status, 503, open.body.take(200))
      assertEquals(open.error, "CIRCUIT_OPEN")
      assertEquals(graphCalls.get, before, "an open circuit must not call the graph")
      failing.set(false)
      s.clock.advance(st.circuitOpenMillis.toLong + 1L)
      val trial = query(s, q)
      assertEquals(trial.status, 200, trial.body.take(300))
      assertEquals(query(s, q).status, 200)
    } finally s.close()
  }
}
