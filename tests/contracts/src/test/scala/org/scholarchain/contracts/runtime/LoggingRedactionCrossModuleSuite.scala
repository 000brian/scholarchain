package org.scholarchain.contracts.runtime

import org.scholarchain.contracts.runtime.CrossModuleHarness.*
import org.scholarchain.rag.runtime.RuntimeServiceFixtures
import org.scholarchain.storage.StoreException
import org.scholarchain.storage.ports.*

/** Category 20 (logging redaction), across storage, rag and services: a store failure whose message
  * holds a host name, a table name, an ARN and a 12-digit account number. No response body and no
  * log line may show any of them.
  */
class LoggingRedactionCrossModuleSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private val Host = "db-prod-7.cluster-ro-q1w2e3.us-east-1.neptune.amazonaws.com"
  private val Table = "scholarchain-prod-run-ledger"
  private val Arn = "arn:aws:dynamodb:us-east-1:123456789012:table/scholarchain-prod-run-ledger"
  private val Account = "123456789012"
  private val Sensitive =
    s"connect timed out to $Host while reading $Table ($Arn) for account $Account"
  private val Secrets = List(Host, Table, Arn, Account)
  private val evidenceTable = org.scholarchain.storage.StorageConfig
    .from(org.scholarchain.services.ServiceTestKit.config)
    .fold(e => sys.error(e.mkString), identity)
    .dynamoDb
    .evidenceBundleTable

  private val Collaborators = "Who are the direct collaborators of Carol King in 2015-2025?"

  /** The graph port throws (as an adapter without its own catch would) or returns Left. */
  private def graph(throwing: Boolean)(base: GraphReadPort): GraphReadPort = new GraphReadPort {
    def settings: GraphReadSettings = base.settings
    def run(q: GraphQuery): Either[String, GraphResult] = q match {
      case _: GraphQuery.CoauthorNeighbors =>
        if (throwing) throw new StoreException(Sensitive, true) else Left(Sensitive)
      case other => base.run(other)
    }
  }

  private def leaks(text: String): List[String] = Secrets.filter(text.contains)

  test("a throwing store gives 502 with a fixed body: no host, table, ARN or account in it") {
    val s = serve(runtime(graph = graph(throwing = true)))
    try {
      val r = query(s, Collaborators)
      assertEquals(r.status, 502, r.body.take(300))
      assertEquals(r.error, "DOWNSTREAM_FAILURE")
      assertEquals(leaks(r.body), Nil)
      // the redactor removes ARNs and account numbers from every log line
      val lines = s.log.rendered
      assert(lines.nonEmpty)
      assert(lines.forall(l => !l.contains(Arn) && !l.contains(Account)), lines.mkString("\n"))
    } finally s.close()
  }

  // Known S-4 (services P1, decision 8 step 3g): log lines carry downstream text. Owner:
  // service-engineer. Repaired in Phase 11 step 3g; the .fail mark was removed in step 4.
  test("S-4 no log line carries the host or table name of a store failure") {
    val s = serve(runtime(graph = graph(throwing = true)))
    try {
      val r = query(s, Collaborators)
      assertEquals(r.status, 502)
      val bad = s.log.rendered.filter(l => l.contains(Host) || l.contains(Table))
      // the specific wrong value: the workflow-failed line's detail holds Host and Table
      assertEquals(bad, Nil)
    } finally s.close()
  }

  // Same as C-02 (reviewer slice C; owner rag-engineer): a graph-port Left (NeptuneGraphRead
  // returns the StoreException message as its Left) becomes an abstention line of a 200 answer.
  // Repaired in Phase 11 steps 3f/3g; the .fail mark was removed in step 4.
  test("C-02 a graph-port Left with store text never reaches the 200 answer") {
    val s = serve(runtime(graph = graph(throwing = false)))
    try {
      val r = query(s, Collaborators)
      assertEquals(r.status, 200, r.body.take(300))
      // the specific wrong value: "neighbor lookup failed: connect timed out to <host> ..."
      assertEquals(leaks(r.body), Nil, r.json("answer")("answerText").str.take(600))
    } finally s.close()
  }

  // Same as C-02 (Stages.scala:745 "Semantic context unavailable: $e"; owner rag-engineer).
  // Repaired in Phase 11 step 3f; the .fail mark was removed in step 4.
  test("C-02 a vector-index failure with store text never reaches the 200 answer notes") {
    val failing: VectorIndexPort => VectorIndexPort = base =>
      new VectorIndexPort {
        def describeIndex(index: String) = base.describeIndex(index)
        def putVectors(index: String, vectors: Seq[VectorRecord]): Unit =
          base.putVectors(index, vectors)
        def getVectors(index: String, keys: Seq[String], returnData: Boolean) =
          base.getVectors(index, keys, returnData)
        def query(
            index: String,
            vector: Array[Float],
            topK: Int,
            filter: Option[MetadataFilter],
            returnMetadata: Boolean
        ): List[VectorHit] = throw new StoreException(Sensitive, true)
      }
    val s = serve(runtime(vectors = failing))
    try {
      val r = query(
        s,
        "Which researchers working on Dan Brown have the most distinct coauthors in 2015-2025?"
      )
      assertEquals(r.status, 200, r.body.take(300))
      assertEquals(leaks(r.body), Nil, r.json("answer")("answerText").str.take(600))
    } finally s.close()
  }

  test("an unreadable evidence write fails the query (502) without store text in the body") {
    val failingKv: KeyValueStore => KeyValueStore = base =>
      new KeyValueStore {
        def putIfAbsent(table: String, item: Item): ConditionalResult =
          if (table == evidenceTable) throw new StoreException(Sensitive, true)
          else base.putIfAbsent(table, item)
        def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult =
          base.putIfVersion(table, item, expectedVersion)
        def get(table: String, key: ItemKey): Option[Item] = base.get(table, key)
        def query(
            table: String,
            pk: String,
            skPrefix: Option[String],
            limit: Int,
            descending: Boolean
        ): List[Item] = base.query(table, pk, skPrefix, limit, descending)
      }
    val s = serve(runtime(kv = failingKv))
    try {
      val r = query(s, RuntimeServiceFixtures.AnsweredQuestion)
      assertEquals(r.status, 502, r.body.take(300))
      assertEquals(leaks(r.body), Nil)
    } finally s.close()
  }
}
