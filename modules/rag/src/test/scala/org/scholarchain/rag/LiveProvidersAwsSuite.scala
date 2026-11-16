package org.scholarchain.rag

import scala.concurrent.duration._

import org.scholarchain.core._
import org.scholarchain.core.config.ScholarChainConfig
import org.scholarchain.rag.embedding._
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.rag.semantic.TemplateRegistry
import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.local.LocalStorageEnvironment

/** Real-model retrieval smoke test (Voyage `voyage-4`, 1024 dimensions) with known expected
  * neighbours, and a Titan V2 probe. Every test is tagged `RequiresAws` ("needs live cloud
  * credentials"): excluded from `sbt check`, run only with `sbt rag/AwsTest/testOnly ...` in the
  * deferred real run (Phases 14/15).
  *
  * The Voyage key is read from the environment variable named in the configuration
  * (`SCHOLARCHAIN_VOYAGE_API_KEY`); it is never printed. Bounds: one document batch of 8 short
  * texts plus 8 one-text queries (at most 9 Voyage requests, about 2,000 tokens), retries from the
  * embedding settings, 120 s per test. Vectors go to storage's in-memory index, so nothing is
  * written to AWS. Titan: one InvokeModel call.
  *
  * The documents are synthetic but use Spark's template shapes; the expected neighbours depend on
  * meaning (venue fields, topics, names), which the lexical fake cannot provide.
  */
class LiveProvidersAwsSuite extends munit.FunSuite {
  private val RequiresAws = new munit.Tag("RequiresAws")
  override val munitTimeout: Duration = 120.seconds

  private val window = YearWindow(2015, 2025)
  private val run = RunId.of("rag.live-smoke", "rag/1", "sha256:smoke", "config").toOption.get
  private def rid(i: Int) =
    ResearcherId.fromAnchorMention(MentionId.of(WorkId.fromDblpKey(s"conf/x/S$i"), 0))
  private def prov(ids: List[String], text: String) =
    Provenance(
      1,
      SourceType.Pipeline,
      "scholarchain-graph",
      "smoke",
      ids,
      run,
      TemplateRegistry.SparkJob,
      TemplateRegistry.GraphPolicy,
      Some(ContentHash.of(text))
    )

  private val people = Map(
    1 -> "Lena Hartmann",
    2 -> "Kenji Watanabe",
    3 -> "Amara Okafor",
    4 -> "Pablo Ruiz",
    5 -> "Ingrid Solberg",
    6 -> "Omar Haddad"
  )

  private def profile(i: Int, venues: String): EvidenceItem = {
    val r = rid(i)
    val text =
      s"Researcher ${people(i)} (${r.value}), window 2015-2025. Publications P=12; distinct coauthors C=20; " +
        "total coauthor occurrences T=40; average coauthors per paper A=3.3333; maximum coauthors on one paper M=6; " +
        s"weighted degree W=40.0000. Connected component size 5000; community ${r.value} with 30 members; coreness 4; " +
        "participation coefficient 0.2000; coauthors in other communities 3; other communities connected 2. " +
        s"Most frequent venues: $venues. Identity: accepted, confidence 0.9500."
    EvidenceItem(
      1,
      EvidenceId.of(EvidenceType.Metric, run, List(r.value)),
      EvidenceType.Metric,
      List(r.value),
      Some(window),
      None,
      Some(text),
      prov(List(r.value), text)
    )
  }

  private def community(group: Int, members: List[Int]): EvidenceItem = {
    val ids = rid(group).value :: members.map(rid(_).value)
    val text =
      s"Community ${rid(group).value}, window 2015-2025: 25 researchers, 60 coauthor edges inside the community, " +
        "12 coauthor edges to other communities. Members with the most distinct coauthors: " +
        members.map(m => s"${people(m)} (${rid(m).value}, C=15)").mkString("; ") + "."
    EvidenceItem(
      1,
      EvidenceId.of(EvidenceType.Metric, run, ids),
      EvidenceType.Metric,
      ids,
      Some(window),
      None,
      Some(text),
      prov(ids, text)
    )
  }

  private def path(ids: List[Int]): EvidenceItem = {
    def n(i: Int) = s"${people(i)} (${rid(i).value})"
    val hops = ids.zip(ids.tail).map { case (a, b) =>
      s"${n(a)} to ${n(b)}: 2 joint publications, 2019-2022"
    }
    val text =
      s"Collaboration path in window 2015-2025 from ${n(ids.head)} to ${n(ids.last)} with ${ids.size - 1} hops " +
        s"(shortest within the depth cap). Hops: ${hops.mkString("; ")}."
    val s = ids.map(rid(_).value)
    EvidenceItem(
      1,
      EvidenceId.of(EvidenceType.GraphPath, run, s),
      EvidenceType.GraphPath,
      s,
      Some(window),
      None,
      Some(text),
      prov(s, text)
    )
  }

  /** (summary kind, evidence) and the expected neighbours: (document type, query, expected top-1
    * vector key).
    */
  private val profiles = List(
    1 -> "ACL (9), EMNLP (7), NAACL (4)",
    2 -> "CVPR (10), ICCV (6), ECCV (5)",
    3 -> "SIGMOD (8), VLDB (7), ICDE (3)",
    4 -> "CCS (6), USENIX Security (5), IEEE S&P (4)"
  )
  private val communities = List(community(5, List(5, 1, 3)), community(6, List(6, 2, 4)))
  private val paths = List(path(List(1, 5, 3)), path(List(2, 6, 4)))
  private val full = "full-2015-2025"

  private def expected: List[(SemanticDocumentType, String, String)] = List(
    (
      SemanticDocumentType.ResearcherProfile,
      "a natural language processing researcher",
      s"rp:$full:${rid(1).value}"
    ),
    (
      SemanticDocumentType.ResearcherProfile,
      "computer vision and image recognition",
      s"rp:$full:${rid(2).value}"
    ),
    (
      SemanticDocumentType.ResearcherProfile,
      "database systems and query processing",
      s"rp:$full:${rid(3).value}"
    ),
    (
      SemanticDocumentType.ResearcherProfile,
      "computer security and privacy research",
      s"rp:$full:${rid(4).value}"
    ),
    (
      SemanticDocumentType.CommunitySummary,
      "the collaboration community that includes Ingrid Solberg and Lena Hartmann",
      s"cm:$full:${rid(5).value}"
    ),
    (
      SemanticDocumentType.CommunitySummary,
      "the group around Omar Haddad and Kenji Watanabe",
      s"cm:$full:${rid(6).value}"
    ),
    (
      SemanticDocumentType.CollaborationPath,
      "how is Lena Hartmann connected to Amara Okafor",
      s"pa:$full:${rid(1).value}:${rid(3).value}"
    ),
    (
      SemanticDocumentType.CollaborationPath,
      "connection between Kenji Watanabe and Pablo Ruiz",
      s"pa:$full:${rid(2).value}:${rid(4).value}"
    )
  )

  private def config: ScholarChainConfig = RagFixtures.voyageConfig

  test("Voyage voyage-4: real-model retrieval returns the expected neighbours".tag(RequiresAws)) {
    val cfg = StorageConfig.from(config).fold(e => fail(e.mkString), identity)
    val doc = EmbeddingProviders.create(config, InputType.Document).fold(e => fail(e), identity)
    val q = EmbeddingProviders.create(config, InputType.Query).fold(e => fail(e), identity)
    assertEquals(doc.spec.id, "voyage/voyage-4/1024/document/emb-v1")
    val vectors = config.vectors.get
    val docs =
      profiles.map { case (i, v) =>
        TemplateRegistry.fromSparkSummary("researcher_profile", full, profile(i, v), vectors)
      } ++
        communities.map(TemplateRegistry.fromSparkSummary("community", full, _, vectors)) ++
        paths.map(TemplateRegistry.fromSparkSummary("path", full, _, vectors))
    val all = docs.map(_.fold(e => fail(e), identity)).flatten
    val env = LocalStorageEnvironment.create(cfg, None)
    try {
      val report = env.pipeline(doc.port, "dblp").run(run, all.iterator)
      assertEquals(report.rejected, 0L)
      val retriever = new SemanticRetriever(q.port, q.spec, env.vectors, vectors)
      expected.foreach { case (t, query, key) =>
        // Phase 9: windowed indexes are queried within one window
        val hits = retriever
          .retrieve(t, query, 3, Some(SemanticRetriever.windowFilter(2015, 2025)))
          .fold(e => fail(e), _.context.entries)
        assertEquals(hits.headOption.map(_.vectorKey), Some(key), s"query '$query'")
      }
      assert(doc.usage.requests <= 2L && q.usage.requests <= 8L + 5L)
    } finally env.close()
  }

  test("Bedrock Titan V2: one text, 1024 normalized dimensions".tag(RequiresAws)) {
    val c = RagFixtures.bedrockConfig
    val e = EmbeddingProviders.create(c, InputType.Document).fold(err => fail(err), identity)
    val v = e.port
      .embed(Vector("Researcher probe, window 2015-2025."))
      .fold(f => fail(f.message), identity)
      .vectors
      .head
    assertEquals(v.length, 1024)
    assertEqualsDouble(math.sqrt(v.map(x => x.toDouble * x).sum), 1.0, 1e-3)
    assertEquals(e.port.modelId, "bedrock/amazon.titan-embed-text-v2:0/1024/document/emb-v1")
  }
}
