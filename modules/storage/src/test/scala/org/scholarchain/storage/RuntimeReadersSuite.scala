package org.scholarchain.storage

import org.scholarchain.core._
import org.scholarchain.storage.dynamo._
import org.scholarchain.storage.ingest.TextStore
import org.scholarchain.storage.local.{InMemoryGraph, InMemoryKeyValueStore, InMemoryObjectStore}
import org.scholarchain.storage.ports._
import org.scholarchain.storage.runtime._
import org.scholarchain.storage.s3.S3KeyLayout
import org.scholarchain.storage.source.{LocalDirectorySource, RejectLog}
import org.scholarchain.storage.vectors.IndexSpecCatalog

class RuntimeReadersSuite extends munit.FunSuite {
  import TestFixtures._

  private val cfg = runtimeStorageConfig
  private val tables = cfg.dynamoDb

  /** In-memory tables loaded from the fixture export (ranking top 3), and the runtime stores. */
  private def loaded(): (InMemoryKeyValueStore, RuntimeStores) = {
    val dir = writeExport(tempDir("readers"))
    val src = new LocalDirectorySource(dir)
    val kv = new InMemoryKeyValueStore(cfg.tableNames)
    val run = StorageVersion.runId("storage.readers-test", "sha256:t", cfg.fingerprint)
    new DynamoLoader(src, src.manifest(), kv, tables, new RejectLog(None), 1, Some(3))
      .load(1, run, Nil)
    val stores = RuntimeStores
      .of(
        cfg,
        new InMemoryGraph(1, graphSettings),
        kv,
        new local.InMemoryVectorIndex(IndexSpecCatalog.load()),
        new TextStore(new InMemoryObjectStore, new S3KeyLayout(cfg.zones))
      )
      .toOption
      .get
    (kv, stores)
  }

  // ---- MetricCacheReader -----------------------------------------------------------------

  test("MetricCacheReader: one researcher in one window, typed, with its item key") {
    val (_, s) = loaded()
    val e = s.metrics.get(rB, Full).toOption.flatten.get
    assertEquals(e.researcherId, rB)
    assertEquals(e.window, FullWindow)
    assertEquals(e.value(CollaborationMetric.PublicationCount), Some(2.0))
    assertEquals(e.value(CollaborationMetric.DistinctCoauthors), Some(2.0))
    assertEquals(e.value(CollaborationMetric.AverageCoauthorsPerPaper), Some(1.0))
    assertEquals(e.value(CollaborationMetric.ParticipationCoefficient), Some(0.0))
    assertEquals(e.mrWorkIds.toSet, Set(w1, w2))
    assertEquals((e.runId, e.policyVersion), (sparkRun, "graph-v1"))
    assertEquals(e.recordRef, s"ResearcherMetricCache:researcher#${rB.value}/window#$Full")
    assertEquals(s.metrics.get(rB, "w2015-2017"), Right(None), "configured window, no item")
    assert(s.metrics.get(rB, "w1999-2000").isLeft, "unconfigured window")
    assert(s.metrics.get(ResearcherId("x"), Full).isLeft)
  }

  test("MetricCacheReader: all windows of one researcher, and top-K of a ranking") {
    val (_, s) = loaded()
    assertEquals(s.metrics.allWindows(rC).map(_.map(_.windowLabel)), Right(List(Full)))
    val top = s.metrics.ranking(Full, CollaborationMetric.DistinctCoauthors, 3).toOption.get
    assertEquals(top.map(_.rank), List(1, 2, 3))
    assertEquals(top.take(2).map(_.researcherId.value).toSet, Set(rB.value, rC.value))
    assertEquals(top.map(_.displayName), top.map(t => names(t.researcherId)))
    assert(top.forall(_.metric == CollaborationMetric.DistinctCoauthors))
    assertEquals(
      s.metrics.ranking(Full, CollaborationMetric.Coreness, 2).map(_.size),
      Right(2)
    )
    assert(s.metrics.ranking(Full, CollaborationMetric.DistinctCoauthors, 4).isLeft, "topK > 3")
    assert(s.metrics.ranking(Full, CollaborationMetric.ComponentSize, 3).isLeft, "not ranked")
    assert(s.metrics.ranking("nope", CollaborationMetric.Coreness, 3).isLeft)
    assertEquals(s.metrics.ranking("w2015-2017", CollaborationMetric.Coreness, 3), Right(Nil))
  }

  // ---- RankingOrder (4g) --------------------------------------------------------------------

  private def acc(id: String, values: (CollaborationMetric, Double)*) = {
    val a = new MetricCacheAccumulator(id, Full)
    values.foreach { case (m, v) => a.values.update(m, v) }
    a.provenance = Some(prov(List(id)))
    a
  }

  test("RM4 order: value descending, then researcherId by code point; only present values ranked") {
    val r = new RankingAccumulator(Full, 2)
    r.offer(acc("r-b", CollaborationMetric.PublicationCount -> 5))
    r.offer(acc("r-a", CollaborationMetric.PublicationCount -> 5))
    r.offer(acc("r-c", CollaborationMetric.PublicationCount -> 9))
    r.offer(acc("r-d", CollaborationMetric.AverageCoauthorsPerPaper -> 1.5))
    assertEquals(
      r.ranking(CollaborationMetric.PublicationCount).map(_.researcherId),
      List("r-c", "r-a"),
      "top 2 only; the tie at 5 goes to the smaller ID"
    )
    assertEquals(
      r.ranking(CollaborationMetric.AverageCoauthorsPerPaper).map(_.researcherId),
      List("r-d")
    )
    assertEquals(r.ranking(CollaborationMetric.ParticipationCoefficient), Nil)
    // code point order, not locale order: "Z" (0x5A) sorts before "a" (0x61)
    val cp = new RankingAccumulator(Full, 3)
    cp.offer(acc("a", CollaborationMetric.Coreness -> 1))
    cp.offer(acc("Z", CollaborationMetric.Coreness -> 1))
    assertEquals(cp.ranking(CollaborationMetric.Coreness).map(_.researcherId), List("Z", "a"))
  }

  test("RM4 bridge order: communities_connected, then cross_community_degree, then researcherId") {
    val r = new RankingAccumulator(Full, 5)
    import CollaborationMetric.{CommunitiesConnected => CC, CrossCommunityDegree => CCD}
    r.offer(acc("r1", CC -> 3, CCD -> 4))
    r.offer(acc("r2", CC -> 3, CCD -> 9))
    r.offer(acc("r0", CC -> 3, CCD -> 4))
    r.offer(acc("r3", CC -> 5, CCD -> 1))
    assertEquals(r.ranking(CC).map(_.researcherId), List("r3", "r2", "r0", "r1"))
    // the tie-break applies only to communities_connected
    assertEquals(r.ranking(CCD).map(_.researcherId), List("r2", "r0", "r1", "r3"))
    val item = RankingItems.item(Full, CC, 1, r.ranking(CC).head, "Name")
    assertEquals(item.key, ItemKey(s"ranking#$Full#communities_connected", "rank#0001"))
    assertEquals(
      item.attributes.keys.toList,
      List(
        "pk",
        "sk",
        "windowLabel",
        "metric",
        "rank",
        "researcherId",
        "displayName",
        "value",
        "runId",
        "policyVersion"
      )
    )
  }

  // ---- EntityLinkReader ------------------------------------------------------------------

  test("EntityLinkReader: folded name and ORCID lookups, bounded with a truncation flag") {
    val (kv, s) = loaded()
    val alice = s.entityLinks.byName("Alice Smith", 5).toOption.get
    assertEquals(alice.candidates.map(_.researcherId), List(rA))
    assertEquals(alice.candidates.head.matchedValue, "Alice Smith")
    assertEquals(alice.candidates.head.keyRule, AuthorNameKey.Version)
    assertEquals(alice.lookupKey, "name#alice smith")
    // the reader folds: case, spacing and the dblp homonym suffix do not matter
    assertEquals(
      s.entityLinks.byName("  ALICE   smith 0003", 5).map(_.candidates.map(_.researcherId)),
      Right(List(rA))
    )
    assertEquals(
      s.entityLinks
        .byOrcid("https://orcid.org/0000-0002-1825-0097", 5)
        .map(_.candidates.map(_.researcherId)),
      Right(List(rA))
    )
    assert(s.entityLinks.byOrcid("0000-0002-1825-0098", 5).isLeft, "bad check digit")
    assert(s.entityLinks.byName("  ''  ", 5).isLeft)
    assert(s.entityLinks.byName("Alice Smith", 0).isLeft)
    assert(s.entityLinks.byName("x" * 201, 5).isLeft)
    assertEquals(s.entityLinks.byName("Nobody Here", 5).map(_.candidates), Right(Nil))
    // a homonym: a second researcher with the same folded name
    val bob = canonical.find(_.researcherId == rB).get.copy(dblpNames = List("Alice Smith 0002"))
    ItemBuilders
      .entityLinks(bob)
      ._1
      .foreach(i => new ConditionalWriter(kv).upsertIfChanged(tables.entityLinkCacheTable, i))
    val one = s.entityLinks.byName("Alice Smith", 1).toOption.get
    assertEquals((one.candidates.size, one.truncated), (1, true))
    val two = s.entityLinks.byName("Alice Smith", 2).toOption.get
    assertEquals((two.candidates.map(_.researcherId).toSet, two.truncated), (Set(rA, rB), false))
  }

  // ---- EvidenceStore ---------------------------------------------------------------------

  private val queryRun: RunId = RunId.of("rag.query", "rag/1", "sha256:q", "sha256:c").toOption.get

  private def runtimeProv(ids: List[String], hash: Option[String] = None) =
    Provenance(
      1,
      SourceType.Runtime,
      "scholarchain-runtime",
      "dblp-2026-11-01",
      ids,
      queryRun,
      "rag.query",
      "rag-policy/1",
      hash
    )

  private def metricItem(r: ResearcherId, v: Double): EvidenceItem = {
    val subjects = List(r.value, "metric=publication_count", s"window=$Full")
    EvidenceItem(
      1,
      EvidenceId.of(EvidenceType.Metric, queryRun, subjects),
      EvidenceType.Metric,
      subjects,
      Some(FullWindow),
      Some(MetricValue(CollaborationMetric.PublicationCount, v)),
      None,
      runtimeProv(List(s"ResearcherMetricCache:researcher#${r.value}/window#$Full", sparkRun.value))
    )
  }

  private def bundleOf(items: List[EvidenceItem]) =
    EvidenceBundle(
      1,
      EvidenceId.forBundle(queryRun, items.map(_.evidenceId)),
      items,
      runtimeProv(items.map(_.evidenceId.value))
    )

  test("EvidenceStore: path evidence by (evidenceId, window); an answer bundle round trip") {
    val (kv, s) = loaded()
    val path = pathEvidence.head
    assertEquals(s.evidence.get(path.evidenceId, Some(FullWindow)), Right(Some(path)))
    assertEquals(s.evidence.get(path.evidenceId, None), Right(None))
    assertEquals(s.evidence.windows(path.evidenceId, 10), Right(List(path)))
    assert(s.evidence.get(EvidenceId("evidence:v1:bad"), None).isLeft)

    val items = List(metricItem(rA, 1), metricItem(rB, 2), path)
    val b = bundleOf(items)
    val w1 = s.evidence.putBundle(b).toOption.get
    assertEquals(w1.bundle, Change.Inserted)
    assertEquals((w1.itemsInserted, w1.itemsUnchanged, w1.itemConflicts), (2, 1, Nil))
    assertEquals(s.evidence.getBundle(b.bundleId), Right(Some(b)))
    items.foreach(i => assertEquals(s.evidence.get(i.evidenceId, i.window), Right(Some(i))))
    val stored =
      kv.get(tables.evidenceBundleTable, ItemKey(s"evidence#${b.bundleId.value}", "bundle")).get
    assertEquals(stored.long("itemCount"), Some(3L))
    // an identical rerun writes nothing
    val writes = kv.writeCount(tables.evidenceBundleTable)
    val w2 = s.evidence.putBundle(b).toOption.get
    assertEquals((w2.bundle, w2.itemsUnchanged), (Change.Unchanged, 3))
    assertEquals(kv.writeCount(tables.evidenceBundleTable), writes)
  }

  test("EvidenceStore: refuses bundles over maxBundleItems or 350,000 bytes, and invalid bundles") {
    val (kv, s) = loaded()
    val six = researchers.flatMap(r => List(metricItem(r, 1))) ++ List(
      metricItem(ResearcherId.fromAnchorMention(MentionId.of(w3, 9)), 1),
      metricItem(ResearcherId.fromAnchorMention(MentionId.of(w3, 8)), 1)
    )
    val tooMany = s.evidence.putBundle(bundleOf(six.take(6)))
    assert(tooMany.left.toOption.exists(_.contains("at most 5")), tooMany.toString)
    val big = metricItem(rA, 1).copy(snippet = None)
    val longSubject = List(rA.value, "x" * 360000)
    val huge = big.copy(
      evidenceId = EvidenceId.of(EvidenceType.Metric, queryRun, longSubject),
      subjectIds = longSubject
    )
    val r = s.evidence.putBundle(bundleOf(List(huge)))
    assert(r.left.toOption.exists(_.contains("350000")), r.toString.take(200))
    val wrongId =
      bundleOf(List(metricItem(rA, 1))).copy(bundleId = EvidenceId.forBundle(sparkRun, Nil))
    assert(s.evidence.putBundle(wrongId).isLeft)
    assertEquals(kv.itemCount(tables.evidenceBundleTable), 1L, "nothing written by a refusal")
  }

  // ---- QueryAuditWriter ------------------------------------------------------------------

  private def audit(abstained: Boolean): QueryAudit = {
    val question = "Who has the most distinct coauthors in 2015-2025?"
    val plan = QueryPlan(
      1,
      question,
      sparkRun,
      List(
        QueryStep(
          QueryOperation.ResearcherRanking,
          Nil,
          Some(FullWindow),
          Map("metric" -> "distinct_coauthors", "limit" -> "3")
        )
      ),
      runtimeProv(List("question"), Some(ContentHash.of(question)))
    )
    QueryAudit(
      1,
      queryRun,
      ContentHash.of(question),
      plan,
      PlanSource.Rule,
      Nil,
      Nil,
      Nil,
      None,
      sparkRun,
      List("graph-v1"),
      List(QueryAudit.promptVersion("query-planner-v1", "prompt")),
      None,
      None,
      None,
      12L,
      Map("planner" -> 1L),
      Map("verified" -> 0),
      ContentHash.of("answer"),
      abstained,
      runtimeProv(List(queryRun.value))
    )
  }

  test("QueryAuditWriter: append-only query-audit events holding the Contract-encoded record") {
    val (kv, s) = loaded()
    val a1 = s.audits.append(audit(false)).toOption.get
    val a2 = s.audits.append(audit(true)).toOption.get
    assertEquals((a1.change, a2.change), (Change.Inserted, Change.Inserted))
    assertEquals((a1.attempt, a2.attempt), (1, 2), "each append claims a new attempt")
    assertEquals(a1.key, ItemKey(s"run#${queryRun.value}", "attempt#000001#00000001#query-audit"))
    val stored = kv.get(tables.runLedgerTable, a1.key).get
    assertEquals(stored.str("eventType"), "query-audit")
    assertEquals(Contract.decode[QueryAudit](stored.str("record")), Right(audit(false)))
    assertEquals(s.audits.read(queryRun, 100), Right(List(audit(false), audit(true))))
    val invalid = audit(false).copy(answerHash = "md5:nope")
    assert(s.audits.append(invalid).isLeft)
  }

  // ---- QueryAudit v2 (Phase 11, step 3e) ------------------------------------------------

  /** Copies of domain's frozen wire examples (`modules/core/src/test/resources/wire/v{1,2}`). */
  private def frozen(version: Int): String = {
    val in = getClass.getResourceAsStream(s"/wire/v$version/QueryAudit.json")
    assert(in != null, s"wire/v$version/QueryAudit.json is on the test classpath")
    try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally in.close()
  }

  test("QueryAuditWriter: the frozen v2 record is appended and read back unchanged") {
    val (kv, s) = loaded()
    val v2 = Contract.decode[QueryAudit](frozen(2)).toOption.get
    assertEquals(v2.schemaVersion, 2)
    assert(v2.queryTemplateIds.nonEmpty && v2.indexesQueried.nonEmpty, v2.toString)
    assert(v2.metadataFilterKeys.nonEmpty && v2.modelCalls.nonEmpty && v2.cacheHits.nonEmpty)
    val w = s.audits.append(v2).toOption.get
    assertEquals(w.change, Change.Inserted)
    val stored = kv.get(tables.runLedgerTable, w.key).get
    assertEquals(stored.long("schemaVersion"), Some(2L))
    assertEquals(ujson.read(stored.str("record")), ujson.read(frozen(2)), "same wire JSON")
    assertEquals(s.audits.read(v2.queryRunId, 100), Right(List(v2)))
  }

  test("QueryAuditWriter: a stored v1 record still reads, next to a v2 record of the same run") {
    val (kv, s) = loaded()
    val v1Text = frozen(1)
    val v1 = Contract.decode[QueryAudit](v1Text).toOption.get
    assertEquals(v1.schemaVersion, 1)
    // the item exactly as a Phase 10 writer stored it: raw v1 JSON in `record`
    val runPk = TableSpecs.runPk(v1.queryRunId.value)
    val legacy = Item(
      Item.Pk -> AttrValue.S(runPk),
      Item.Sk -> AttrValue.S("attempt#000001#00000001#query-audit"),
      "eventType" -> AttrValue.S("query-audit"),
      "schemaVersion" -> AttrValue.N("1"),
      "record" -> AttrValue.S(v1Text)
    )
    assertEquals(kv.putIfAbsent(tables.runLedgerTable, legacy), ConditionalResult.Written)
    assertEquals(s.audits.read(v1.queryRunId, 100), Right(List(v1)))
    // a v1 record appended today keeps the v1 wire shape (no v2 keys)
    val again = s.audits.append(v1).toOption.get
    assertEquals(
      ujson.read(kv.get(tables.runLedgerTable, again.key).get.str("record")),
      ujson.read(v1Text)
    )
    val v2 = Contract.decode[QueryAudit](frozen(2)).toOption.get
    s.audits.append(v2).toOption.get
    assertEquals(s.audits.read(v1.queryRunId, 100), Right(List(v1, v1, v2)))
  }

  test("readers turn store failures into Left") {
    val failing = new KeyValueStore {
      private def boom = throw new StoreException("ThrottlingException (HTTP 400)", true)
      def putIfAbsent(t: String, i: Item) = boom
      def putIfVersion(t: String, i: Item, v: Long) = boom
      def get(t: String, k: ItemKey) = boom
      def query(t: String, pk: String, p: Option[String], l: Int, d: Boolean) = boom
    }
    val s = RuntimeStores
      .of(
        cfg,
        new InMemoryGraph(1, graphSettings),
        failing,
        new local.InMemoryVectorIndex(IndexSpecCatalog.load()),
        null
      )
      .toOption
      .get
    assert(s.metrics.get(rA, Full).left.toOption.exists(_.contains("transient")))
    assert(s.entityLinks.byName("Alice Smith", 3).isLeft)
    assert(s.evidence.get(pathEvidence.head.evidenceId, Some(FullWindow)).isLeft)
    assert(s.audits.append(audit(false)).isLeft)
  }
}
