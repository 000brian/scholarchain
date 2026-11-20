package org.scholarchain.storage

import scala.collection.immutable.ListMap

import org.scholarchain.core._
import org.scholarchain.storage.dynamo._
import org.scholarchain.storage.local.InMemoryKeyValueStore
import org.scholarchain.storage.ports._
import org.scholarchain.storage.source.{LocalDirectorySource, RejectLog}

class DynamoSuite extends munit.FunSuite {
  import TestFixtures._

  private def tables = storageConfig.dynamoDb
  private def store() = new InMemoryKeyValueStore(storageConfig.tableNames)
  private def item(pk: String, sk: String, v: String) =
    Item(Item.Pk -> AttrValue.S(pk), Item.Sk -> AttrValue.S(sk), "value" -> AttrValue.S(v))

  test("putIfAbsent writes once; a second put on the same key fails its condition") {
    val kv = store()
    val t = tables.evidenceBundleTable
    assertEquals(kv.putIfAbsent(t, item("p", "s", "1")), ConditionalResult.Written)
    assertEquals(kv.putIfAbsent(t, item("p", "s", "2")), ConditionalResult.ConditionFailed)
    assertEquals(kv.get(t, ItemKey("p", "s")).get.str("value"), "1")
  }

  test("putIfVersion replaces only at the expected version") {
    val kv = store()
    val t = tables.researcherMetricCacheTable
    kv.putIfAbsent(t, ItemHash.stamp(item("p", "s", "1"), 1))
    assertEquals(
      kv.putIfVersion(t, ItemHash.stamp(item("p", "s", "2"), 3), 2),
      ConditionalResult.ConditionFailed
    )
    assertEquals(
      kv.putIfVersion(t, ItemHash.stamp(item("p", "s", "2"), 2), 1),
      ConditionalResult.Written
    )
    assertEquals(
      kv.putIfVersion(t, ItemHash.stamp(item("missing", "s", "2"), 2), 1),
      ConditionalResult.ConditionFailed
    )
  }

  test(
    "upsertIfChanged: insert, unchanged rerun, update with version bump, conflict on a stale version"
  ) {
    val kv = store()
    val w = new ConditionalWriter(kv)
    val t = tables.researcherMetricCacheTable
    assertEquals(w.upsertIfChanged(t, item("p", "s", "1")), Change.Inserted)
    val writes = kv.writeCount(t)
    assertEquals(w.upsertIfChanged(t, item("p", "s", "1")), Change.Unchanged)
    assertEquals(kv.writeCount(t), writes, "an unchanged item is never rewritten")
    assertEquals(w.upsertIfChanged(t, item("p", "s", "2")), Change.Updated)
    assertEquals(kv.get(t, ItemKey("p", "s")).get.long(Item.Version), Some(2L))
    // a writer that read version 1 loses against the stored version 2
    assertEquals(
      kv.putIfVersion(t, ItemHash.stamp(item("p", "s", "3"), 2), 1),
      ConditionalResult.ConditionFailed
    )
  }

  test(
    "append-only tables never overwrite: same content is a rerun, different content a conflict"
  ) {
    val kv = store()
    val w = new ConditionalWriter(kv)
    val t = tables.runLedgerTable
    assertEquals(w.append(t, item("run#x", "a", "1")), Change.Inserted)
    assertEquals(w.append(t, item("run#x", "a", "1")), Change.Unchanged)
    assertEquals(w.append(t, item("run#x", "a", "2")), Change.Conflict)
    assertEquals(kv.get(t, ItemKey("run#x", "a")).get.str("value"), "1")
  }

  test("RunLedger: each open claims a new attempt; events are ordered and append-only") {
    val kv = store()
    val l1 = RunLedger.open(kv, tables.runLedgerTable, sparkRun, "first")
    val l2 = RunLedger.open(kv, tables.runLedgerTable, sparkRun, "second")
    assertEquals((l1.attempt, l2.attempt), (1, 2))
    l1.record("vector-reject", "reason" -> AttrValue.S("x"))
    l1.record("put-failure", "retries" -> AttrValue.n(3L))
    val ev = l1.events(10)
    assertEquals(ev.map(_.str("eventType")), List("claim", "vector-reject", "put-failure"))
    assertEquals(l2.events(10).map(_.str("eventType")), List("claim"))
  }

  test("ArtifactRegistry keeps every version; latest is the highest") {
    val kv = store()
    val r = new ArtifactRegistry(kv, tables.artifactRegistryTable)
    assertEquals(r.register("vector", "i/k", 0, "textHash" -> AttrValue.S("h1")), Change.Inserted)
    assertEquals(r.register("vector", "i/k", 1, "textHash" -> AttrValue.S("h2")), Change.Inserted)
    assertEquals(r.register("vector", "i/k", 1, "textHash" -> AttrValue.S("h3")), Change.Conflict)
    assertEquals(
      r.latest("vector", "i/k").map(v => (v.version, v.item.str("textHash"))),
      Some((2L, "h2"))
    )
    assertEquals(r.history("vector", "i/k", 10).size, 2)
  }

  test(
    "table specs: explicit keys, access patterns, TTL only on QuerySession, append-only ledger and registry"
  ) {
    assertEquals(
      TableSpecs.all.map(_.logicalName).toSet,
      Set(
        "RunLedger",
        "EntityLinkCache",
        "QuerySession",
        "EvidenceBundle",
        "ArtifactRegistry",
        "ResearcherMetricCache"
      )
    )
    TableSpecs.all.foreach { s =>
      assert(s.pkFormat.nonEmpty && s.skFormat.nonEmpty && s.accessPatterns.nonEmpty, s.logicalName)
      assert(
        s.accessPatterns.filter(_.operation != "TTL").forall(_.keyCondition.contains("pk")),
        s.logicalName
      )
      assert(
        s.accessPatterns.exists(_.operation == "TTL") == s.ttlAttribute.nonEmpty,
        s.logicalName
      )
    }
    assertEquals(
      TableSpecs.all.filter(_.ttlAttribute.nonEmpty).map(_.logicalName),
      List("QuerySession")
    )
    assertEquals(
      TableSpecs.all.filter(_.appendOnly).map(_.logicalName).toSet,
      Set("RunLedger", "ArtifactRegistry")
    )
    assertEquals(TableSpecs.all.map(_.tableName(tables)).toSet, storageConfig.tableNames)
    val rm4 = TableSpecs.ResearcherMetricCache.accessPatterns.find(_.id == "RM4").get
    assert(rm4.keyCondition.contains("ranking#<windowLabel>#<metric>"), rm4.keyCondition)
    assert(TableSpecs.ResearcherMetricCache.skFormat.contains("rank#<NNNN>"))
    assertEquals(
      TableSpecs.rankingPk(Full, "publication_count"),
      s"ranking#$Full#publication_count"
    )
    assertEquals(TableSpecs.rankSk(7), "rank#0007")
    val turn = ItemBuilders.sessionTurn("s1", 1, "sha256:x", None, 1800000000L)
    assertEquals(turn.long("expiresAt"), Some(1800000000L))
  }

  test("QuerySession: QS4 jobs and QS5 idempotency records share the table and its TTL") {
    val qs = TableSpecs.QuerySession
    // QS6 (Phase 15): re-embed completion records, create-only under their own prefix
    assertEquals(qs.accessPatterns.map(_.id), List("QS1", "QS2", "QS3", "QS4", "QS5", "QS6"))
    val qs6 = qs.accessPatterns.find(_.id == "QS6").get
    assert(qs6.keyCondition.startsWith("pk = reembed#<sha256"), qs6.keyCondition)
    assert(qs6.operation.contains("attribute_not_exists(pk)"), qs6.operation)
    assert(!qs6.operation.contains("version = :read"), "completion records are never updated")
    assertEquals(qs.ttlAttribute, Some("expiresAt"))
    assert(!qs.appendOnly)
    val qs4 = qs.accessPatterns.find(_.id == "QS4").get
    val qs5 = qs.accessPatterns.find(_.id == "QS5").get
    assertEquals(qs4.keyCondition, "pk = job#<jobId>, sk = job")
    assert(qs5.keyCondition.startsWith("pk = idempotency#<sha256"), qs5.keyCondition)
    List(qs4, qs5).foreach { p =>
      assert(p.operation.contains("attribute_not_exists(pk)"), p.id)
      assert(p.operation.contains("version = :read"), p.id)
    }
    assert(qs.pkFormat.contains("job#<jobId>") && qs.pkFormat.contains("idempotency#"))
    assertEquals(TableSpecs.jobPk("job-1"), "job#job-1")
    assertEquals(TableSpecs.JobSk, "job")
    assertEquals(TableSpecs.IdempotencySk, "idempotency")
    val pk = TableSpecs.idempotencyPk("full", "k")
    assert(pk.matches("idempotency#[0-9a-f]{64}"), pk)
    assertNotEquals(pk, TableSpecs.idempotencyPk("read-only", "k"))
    // the canonical encoding keeps (scope, key) pairs apart even when concatenations agree
    assertNotEquals(TableSpecs.idempotencyPk("a", "bc"), TableSpecs.idempotencyPk("ab", "c"))
  }

  test("item size follows the DynamoDB sizing rules") {
    val i = Item(
      "ab" -> AttrValue.S("xyz"),
      "n" -> AttrValue.N("12345"),
      "l" -> AttrValue.L(List(AttrValue.S("a")))
    )
    // "ab"+"xyz" = 5; "n" + ((5 digits + 1) / 2 + 1) = 1 + 4; "l" + (3 + 1 + "a") = 1 + 5
    assertEquals(ItemSize.of(i), 5 + 5 + 6)
    assertEquals(ItemSize.writeUnits(Item("k" -> AttrValue.S("x" * 1500))), 2)
  }

  test("metric cache item merges every family; a missing family is rejected, never defaulted") {
    val a = new MetricCacheAccumulator(rA.value, Full)
    List("breadth", "group_size", "bridges", "kcore").foreach(f => a.addMetrics(f, metrics(f).head))
    memberships(GroupKind.Component).headOption.foreach(a.addMembership)
    memberships(GroupKind.Community).headOption.foreach(a.addMembership)
    assert(a.toItem.isLeft, "no M_r evidence yet")
    a.addMetricEvidence(metricEvidence.head)
    val it = a.toItem.toOption.get
    assertEquals(it.key, ItemKey(s"researcher#${rA.value}", s"window#$Full"))
    List(
      "P",
      "C",
      "T",
      "A",
      "M",
      "W",
      "componentId",
      "communityId",
      "coreness",
      "participationCoefficient",
      "mrWorkIds",
      "identityConfidence",
      "runId"
    )
      .foreach(k => assert(it.get(k).nonEmpty, k))
    assertEquals(it.get("mrWorkIds"), Some(AttrValue.L(List(AttrValue.S(w1.value)))))
  }

  test("load plan: pass 1 inserts, pass 2 makes 0 changes; coauthor edges are not loaded") {
    val dir = writeExport(tempDir("dyn"))
    val src = new LocalDirectorySource(dir)
    val kv = store()
    val loader =
      new DynamoLoader(src, src.manifest(), kv, tables, new RejectLog(None), 1, rankingTopK = None)
    val run =
      StorageVersion.runId("storage.dynamodb-load", "sha256:test", storageConfig.fingerprint)
    val files = List(("nodes/Researcher/researcher-00000.csv.gz", 4L, 100L, "a" * 64))
    val p1 = loader.load(1, run, files)
    assertEquals(p1.tables("ResearcherMetricCache").inserted.get, 4L)
    assertEquals(p1.tables("EvidenceBundle").inserted.get, 1L)
    assertEquals(p1.tables("EntityLinkCache").inserted.get, 5L) // 4 names + 1 ORCID
    assertEquals(p1.tables("ArtifactRegistry").inserted.get, 1L)
    val writesAfter1 = storageConfig.tableNames.toList.map(t => t -> kv.writeCount(t)).toMap
    val p2 = loader.load(2, run, files)
    assertEquals(p2.dataChanges, 0L)
    p2.tables.values.foreach(s => assertEquals(s.conflicts.get, 0L, s.table))
    (storageConfig.tableNames - tables.runLedgerTable).foreach(t =>
      assertEquals(kv.writeCount(t), writesAfter1(t), t)
    )
    assertEquals(p2.ledgerAttempt, 2)
    val ev = kv
      .get(
        tables.evidenceBundleTable,
        ItemKey(s"evidence#${pathEvidence.head.evidenceId.value}", "window#2015-2025")
      )
      .get
    assertEquals(Contract.decode[EvidenceItem](ev.str("record")), Right(pathEvidence.head))
    assert(
      kv.scanAll(tables.researcherMetricCacheTable)
        .forall(_.str(Item.Pk).startsWith("researcher#")),
      "no ranking items without rankingTopK"
    )
    assertEquals(p1.tables("ResearcherMetricCacheRanking").items.get, 0L)
    assert(
      storageConfig.tableNames.forall(t =>
        kv.scanAll(t).forall(i => !i.str(Item.Pk).startsWith("edge"))
      )
    )
    assertEquals(kv.itemCount(tables.querySessionTable), 0L)
    assert(p1.metricItemSizes.count == 4 && p1.metricItemSizes.max < 1024)
  }

  test(
    "RM4: the loader writes top-K ranking items per window and metric; a rerun changes nothing"
  ) {
    val dir = writeExport(tempDir("dyn-rank"))
    val src = new LocalDirectorySource(dir)
    val kv = store()
    val rejects = new RejectLog(None)
    val loader = new DynamoLoader(src, src.manifest(), kv, tables, rejects, 1, Some(3))
    val run =
      StorageVersion.runId("storage.dynamodb-load", "sha256:rank", storageConfig.fingerprint)
    val p1 = loader.load(1, run, Nil)
    // 10 ranked metrics x top 3 of 4 researchers (every fixture researcher has every value)
    assertEquals(p1.tables("ResearcherMetricCacheRanking").inserted.get, 30L)
    assertEquals(p1.tables("ResearcherMetricCache").inserted.get, 4L)
    assertEquals(rejects.total, 0L)
    val t = tables.researcherMetricCacheTable
    val items = kv.query(t, s"ranking#$Full#publication_count", Some("rank#"), 10, false)
    assertEquals(items.map(_.str(Item.Sk)), List("rank#0001", "rank#0002", "rank#0003"))
    // P: B and C have 2 works, A and D have 1; ties by researcherId code point
    val expected = (List(rB, rC).map(_.value).sorted(CodePointOrder.ordering) :+
      List(rA, rD).map(_.value).min(CodePointOrder.ordering))
    assertEquals(items.map(_.str("researcherId")), expected)
    assertEquals(items.map(_.str("displayName")), expected.map(id => names(ResearcherId(id))))
    assertEquals(items.map(_.get("value")), List("2", "2", "1").map(v => Some(AttrValue.N(v))))
    assertEquals(items.map(_.long("rank")), List(1L, 2L, 3L).map(Some(_)))
    items.foreach { i =>
      assertEquals(i.str("runId"), sparkRun.value)
      assertEquals(i.str("policyVersion"), "graph-v1")
      assertEquals(i.str("metric"), "publication_count")
    }
    RankingMetrics.ranked.foreach { m =>
      assertEquals(kv.query(t, s"ranking#$Full#${m.wire}", None, 10, false).size, 3, m.wire)
    }
    val writes = kv.writeCount(t)
    val p2 = loader.load(2, run, Nil)
    assertEquals(p2.tables("ResearcherMetricCacheRanking").unchanged.get, 30L)
    assertEquals(p2.dataChanges, 0L)
    assertEquals(kv.writeCount(t), writes, "unchanged ranking items are never rewritten")
  }

  test("a changed fact is updated (version 2) on the next load, never duplicated") {
    val kv = store()
    val w = new ConditionalWriter(kv)
    val t = tables.researcherMetricCacheTable
    val base = Item(
      ListMap(
        Item.Pk -> AttrValue.S("researcher#r"),
        Item.Sk -> AttrValue.S("window#w"),
        "P" -> AttrValue.n(1L)
      )
    )
    w.upsertIfChanged(t, base)
    assertEquals(w.upsertIfChanged(t, base.updated("P", AttrValue.n(2L))), Change.Updated)
    assertEquals(kv.itemCount(t), 1L)
  }
}
