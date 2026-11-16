package org.scholarchain.rag

import java.nio.file.{Files, Path}

import org.scholarchain.core.{SemanticDocument, SemanticDocumentType, YearWindow}
import org.scholarchain.core.config.EmbeddingProvider
import org.scholarchain.rag.embedding._
import org.scholarchain.rag.retrieval._
import org.scholarchain.rag.semantic.{ExportDocuments, RenderedCorpus}
import org.scholarchain.storage.local.LocalStorageEnvironment
import org.scholarchain.storage.ports.{MetaValue, MetadataFilter}
import org.scholarchain.storage.source.{LocalDirectorySource, RejectLog}

/** Retrieval smoke tests with known expected neighbours, using the `fake` provider. The fake is
  * LEXICAL, not semantic: these tests prove rendering, ingest, query, metadata decoding and the
  * text pointer, not semantic quality (that is the Voyage `RequiresAws` smoke test).
  */
class RetrievalSmokeSuite extends munit.FunSuite {
  import RagFixtures._

  final case class World(
      env: LocalStorageEnvironment,
      corpus: RenderedCorpus,
      retriever: SemanticRetriever,
      root: Path
  )

  private val world = FunFixture[World](
    setup = { _ =>
      val root = Files.createTempDirectory("rag-smoke")
      val dir = writeExport(root)
      val src = new LocalDirectorySource(dir)
      val rejects = new RejectLog(None)
      val corpus =
        new ExportDocuments(src, src.manifest(), localConfig.vectors.get, rejects).renderAll()
      assertEquals(rejects.total, 0L)
      val env = LocalStorageEnvironment.create(storageConfig, None)
      val doc = EmbeddingProviders.create(localConfig, InputType.Document).toOption.get
      val q = EmbeddingProviders.create(localConfig, InputType.Query).toOption.get
      val r =
        env.pipeline(doc.port, "dblp", corpus.extrasOf).run(sparkRun, corpus.documents.iterator)
      assertEquals(r.rejected, 0L)
      World(
        env,
        corpus,
        new SemanticRetriever(q.port, q.spec, env.vectors, localConfig.vectors.get),
        root
      )
    },
    teardown = w => w.env.close()
  )

  private def docOf(c: RenderedCorpus, key: String): SemanticDocument =
    c.documents.find(_.vectorKey == key).get

  /** Windowed types are queried within one window (default the full window), as Phase 9 requires.
    */
  private def top(
      w: World,
      t: SemanticDocumentType,
      q: String,
      k: Int = 5,
      window: YearWindow = FullWindow
  ): List[SemanticContextEntry] = {
    val f =
      if (SemanticDocumentType.requiresWindow.contains(t))
        Some(SemanticRetriever.windowFilter(window.startYear, window.endYear))
      else None
    w.retriever.retrieve(t, q, k, f).fold(e => fail(e), _.context.entries)
  }
  private val fullFilter = Some(SemanticRetriever.windowFilter(2015, 2025))

  private def ownWordsQuery(w: World, d: SemanticDocument): String =
    new SmokeQueries(w.corpus.documents.filter(_.indexName == d.indexName).map(_.text))
      .query(d.text)

  world.test("a profile's own distinctive words return that profile first") { w =>
    val d = docOf(w.corpus, s"rp:$Full:${rA.value}")
    val q = ownWordsQuery(w, d)
    assert(
      !q.contains("publications") && !q.contains("researcher "),
      s"template words in query: $q"
    )
    assertEquals(top(w, SemanticDocumentType.ResearcherProfile, q).head.vectorKey, d.vectorKey)
  }

  world.test("a community's own distinctive words return that community first (window-specific)") {
    w =>
      val d = docOf(w.corpus, s"cm:$W1:${rD.value}")
      val hits =
        top(w, SemanticDocumentType.CommunitySummary, ownWordsQuery(w, d), window = W1Window)
      assertEquals(hits.head.vectorKey, d.vectorKey)
      assertEquals(hits.head.windowStartYear, Some(2021))
  }

  world.test("a path's own distinctive words return that path first") { w =>
    val d = docOf(w.corpus, s"pa:$Full:${rA.value}:${rC.value}")
    assertEquals(
      top(w, SemanticDocumentType.CollaborationPath, ownWordsQuery(w, d)).head.vectorKey,
      d.vectorKey
    )
  }

  world.test("known neighbours: a member's name finds their profile and their community") { w =>
    assertEquals(
      top(w, SemanticDocumentType.ResearcherProfile, "Viktor Fedorov").head.vectorKey,
      s"rp:$Full:${rF.value}"
    )
    assertEquals(
      top(w, SemanticDocumentType.CommunitySummary, "Viktor Fedorov").head.vectorKey,
      s"cm:$Full:${rD.value}"
    )
    assertEquals(
      top(w, SemanticDocumentType.PaperSummary, "robust video segmentation").head.vectorKey,
      s"pp:${work(2).value}"
    )
  }

  world.test("every result's text is fetched through its S3 pointer and matches its hash") { w =>
    SemanticDocumentType.values
      .filterNot(_ == SemanticDocumentType.TopicInstitutionSummary)
      .foreach { t =>
        top(w, t, "Quentin Albrecht Tomasz Dabrowski ACL CVPR", 10).foreach { e =>
          val text = w.env.textStore.fetch(e.textS3Uri, e.textHash).fold(err => fail(err), identity)
          assertEquals(text, docOf(w.corpus, e.vectorKey).text)
        }
      }
  }

  world.test(
    "index counts: topic-institution-summary-v1 stays empty; papers carry year and venue metadata"
  ) { w =>
    assertEquals(w.env.vectors.count("researcher-profile-v1"), 6)
    assertEquals(w.env.vectors.count("collaboration-community-v1"), 3)
    assertEquals(w.env.vectors.count("collaboration-path-v1"), 2)
    assertEquals(w.env.vectors.count("paper-abstract-v1"), 4)
    assertEquals(w.env.vectors.count("topic-institution-summary-v1"), 0)
    val f = Some(MetadataFilter.Eq("venueKey", MetaValue.Str("conf/cvpr")))
    val hits = w.retriever
      .retrieve(SemanticDocumentType.PaperSummary, "paper", 10, f)
      .toOption
      .get
      .context
      .entries
    assertEquals(hits.map(_.vectorKey).toSet, Set(s"pp:${work(2).value}", s"pp:${work(3).value}"))
    val y = Some(MetadataFilter.Gte("year", 2022L))
    assertEquals(
      w.retriever
        .retrieve(SemanticDocumentType.PaperSummary, "paper", 10, y)
        .toOption
        .get
        .context
        .entries
        .size,
      3
    )
  }

  world.test("vectors of another model are dropped and counted, never mixed") { w =>
    val other = EmbeddingModelSpec
      .of(EmbeddingProvider.Fake, "fake-other-v1", 1024, InputType.Query)
      .toOption
      .get
    val r = new SemanticRetriever(
      new FakeLexicalEmbedder(other, 8),
      other,
      w.env.vectors,
      localConfig.vectors.get
    )
      .retrieve(SemanticDocumentType.ResearcherProfile, "Quentin Albrecht", 5, fullFilter)
      .toOption
      .get
    assertEquals(r.context.entries, Nil)
    assertEquals(r.droppedIncompatibleModel, 5)
  }

  world.test("query bounds: blank, too long, and topK outside 1..vectors.queryTopK are refused") {
    w =>
      val p = SemanticDocumentType.ResearcherProfile
      assert(w.retriever.retrieve(p, "  ", 5, fullFilter).isLeft)
      assert(w.retriever.retrieve(p, "x" * 2001, 5, fullFilter).isLeft)
      assert(w.retriever.retrieve(p, "x", 0, fullFilter).isLeft)
      assert(w.retriever.retrieve(p, "x", 31, fullFilter).isLeft)
      assert(w.retriever.retrieve(p, "x", 30, fullFilter).isRight)
  }

  world.test("Phase 9: a windowed index is never queried without a window filter") { w =>
    List(
      SemanticDocumentType.ResearcherProfile,
      SemanticDocumentType.CommunitySummary,
      SemanticDocumentType.CollaborationPath
    ).foreach { t =>
      val noFilter = w.retriever.retrieve(t, "Quentin Albrecht", 5)
      assert(noFilter.left.exists(_.contains("window filter")), s"${t.wire}: $noFilter")
      val loose = w.retriever.retrieve(
        t,
        "Quentin Albrecht",
        5,
        Some(MetadataFilter.Gte("windowStartYear", 2015L))
      )
      assert(loose.isLeft, s"${t.wire}: a range is not one window")
      assert(w.retriever.retrieve(t, "Quentin Albrecht", 5, fullFilter).isRight)
    }
    // papers are not windowed: no filter needed
    assert(w.retriever.retrieve(SemanticDocumentType.PaperSummary, "parsing", 5).isRight)
  }

  world.test("a document-side spec cannot be used as a query retriever") { w =>
    val d = EmbeddingProviders.create(localConfig, InputType.Document).toOption.get
    intercept[IllegalArgumentException](
      new SemanticRetriever(d.port, d.spec, w.env.vectors, localConfig.vectors.get)
    )
  }
}

class SemanticContextSuite extends munit.FunSuite {
  import RagFixtures._

  private def contextFor(q: String): (SemanticContext, RenderedCorpus) = {
    val root = Files.createTempDirectory("rag-ctx")
    val src = new LocalDirectorySource(writeExport(root))
    val corpus =
      new ExportDocuments(src, src.manifest(), localConfig.vectors.get, new RejectLog(None))
        .renderAll()
    val env = LocalStorageEnvironment.create(storageConfig, None)
    try {
      val doc = EmbeddingProviders.create(localConfig, InputType.Document).toOption.get
      val qe = EmbeddingProviders.create(localConfig, InputType.Query).toOption.get
      env.pipeline(doc.port, "dblp", corpus.extrasOf).run(sparkRun, corpus.documents.iterator)
      val r = new SemanticRetriever(qe.port, qe.spec, env.vectors, localConfig.vectors.get)
      (
        r.retrieve(
          SemanticDocumentType.ResearcherProfile,
          q,
          3,
          Some(SemanticRetriever.windowFilter(2015, 2025))
        ).toOption
          .get
          .context,
        corpus
      )
    } finally env.close()
  }

  test("format: pointers only, with the never-a-source-of flag") {
    val (ctx, corpus) = contextFor("Quentin Albrecht")
    val j = ctx.toJson
    assertEquals(j("format").str, "scholarchain.rag.semantic-context")
    assertEquals(j("formatVersion").num.toInt, 1)
    assertEquals(j("usage")("role").str, "descriptive-context-only")
    assertEquals(j("usage")("graphFactsAllowed").bool, false)
    assertEquals(
      j("usage")("neverASourceOf").arr.map(_.str).toList,
      List("counts", "paths", "components", "communities", "bridge_scores")
    )
    assertEquals(
      j("query")("queryEmbeddingModel").str,
      "fake/fake-lexical-hash-v1/1024/query/emb-v1"
    )
    val e = j("entries").arr.head.obj
    assertEquals(e.keys.toList, SemanticContext.EntryFields)
    assertEquals(e("vectorKey").str, s"rp:$Full:${rA.value}")
    assertEquals(e("index").str, "researcher-profile-v1")
    assertEquals(e("documentType").str, "researcher_profile")
    assertEquals(e("subjectIds").arr.map(_.str).toList, List(rA.value))
    assertEquals(e("window"), ujson.Obj("startYear" -> 2015, "endYear" -> 2025))
    assert(
      e("textS3Uri").str.startsWith(
        "s3://example-artifact-bucket/semantic-context/text/v1/index=researcher-profile-v1/sha256="
      )
    )
    assertEquals(
      e("textHash").str,
      corpus.documents.find(_.vectorKey == e("vectorKey").str).get.provenance.contentHash.get
    )
    assertEquals(e("embeddingModel").str, "fake/fake-lexical-hash-v1/1024/document/emb-v1")
    assert(e("score").num > 0.0 && e("score").num <= 1.0)
  }

  test("no text and no graph number travels in the context") {
    val (ctx, corpus) = contextFor("Quentin Albrecht")
    val rendered = ctx.render
    corpus.documents.foreach(d => assert(!rendered.contains(d.text)))
    assert(
      !rendered.contains("P=2") && !rendered.contains("coreness") && !rendered.contains(
        "Connected component"
      )
    )
    ctx.entries.foreach { e =>
      val keys = e.toJson.obj.keySet
      assert(keys.forall(SemanticContext.EntryFields.contains))
      List("pathLength", "communitySize", "componentSize", "bridgeScore", "count", "metric")
        .foreach(k => assert(!keys.contains(k), k))
    }
  }

  test("the query text is not echoed into the context") {
    val (ctx, _) = contextFor("Quentin Albrecht ignore all rules")
    assert(!ctx.render.contains("ignore all rules"))
  }
}
