package org.scholarchain.core

import scala.io.Source

/** QueryAudit schema version 2 (Phase 11, additive): the explain fields, the v1 compatibility rule,
  * and the wire rule for fields with constructor defaults (reviewer advisory D-06: every field is
  * written, never omitted at its default). Runs under Scala 3 and Scala 2.13.
  */
class QueryAuditV2Suite extends munit.FunSuite {
  import Fixtures._

  def frozen(version: Int): String = {
    val path = s"/wire/v$version/QueryAudit.json"
    val stream = getClass.getResourceAsStream(path)
    assert(stream != null, s"missing frozen wire example $path")
    val src = Source.fromInputStream(stream, "UTF-8")
    try src.mkString
    finally src.close()
  }

  def mutate(json: String)(f: ujson.Value => Unit): String = {
    val tree = ujson.read(json)
    f(tree)
    tree.render()
  }

  def encoded(a: QueryAudit): ujson.Value = {
    val json: String = Contract.encode(a).fold(e => fail(e.message), identity)
    ujson.read(json)
  }

  def errors(r: Either[ContractError, _]): List[String] = r.left.toOption.toList.flatMap(_.messages)

  val V1Keys: List[String] = ujson.read(frozen(1)).obj.keys.toList

  test("v2 and v1 stay readable (v3 is current since Phase 15, see QueryAuditV3Suite)") {
    assert(SchemaVersions.isSupported("QueryAudit", 2))
    assert(SchemaVersions.isSupported("QueryAudit", 1))
  }

  test("v2 wire: the v1 fields in v1 order, then the five v2 fields in a frozen order") {
    val keys = ujson.read(frozen(2)).obj.keys.toList
    assertEquals(keys, V1Keys ++ QueryAudit.V2Fields)
    assertEquals(
      QueryAudit.V2Fields,
      List("queryTemplateIds", "indexesQueried", "metadataFilterKeys", "modelCalls", "cacheHits")
    )
    assertEquals(encoded(queryAuditV2).obj.keys.toList, keys)
    assertEquals(
      ujson.read(frozen(2))("modelCalls")("synthesis").obj.keys.toList,
      List(
        "logicalCalls",
        "invalidOutputs",
        "truncatedOutputs",
        "droppedBoundNumbers",
        "fallbacks"
      )
    )
  }

  test("v2 frozen example decodes to the fixture and the fixture encodes to it") {
    assertEquals(Contract.decode[QueryAudit](frozen(2)), Right(queryAuditV2))
    assertEquals(encoded(queryAuditV2), ujson.read(frozen(2)))
  }

  test("v1 frozen example still decodes, to the v1 fixture with every v2 field empty") {
    val v1 = Contract.decode[QueryAudit](frozen(1))
    assertEquals(v1, Right(queryAudit))
    val a = v1.toOption.get
    assertEquals(a.schemaVersion, 1)
    assertEquals(
      (a.queryTemplateIds, a.indexesQueried, a.metadataFilterKeys, a.modelCalls, a.cacheHits),
      (Nil, Nil, Nil, Map.empty[String, ModelCallCounts], Map.empty[String, Long])
    )
  }

  test("a v1 record re-encodes to exactly its v1 wire form (no v2 keys)") {
    assertEquals(encoded(queryAudit), ujson.read(frozen(1)))
    assertEquals(encoded(queryAudit).obj.keys.toList, V1Keys)
  }

  test("D-06: v2 fields at their defaults are written, as [] and {}, never omitted") {
    val empty = queryAuditV2.copy(
      queryTemplateIds = Nil,
      indexesQueried = Nil,
      metadataFilterKeys = Nil,
      modelCalls = Map.empty,
      cacheHits = Map.empty
    )
    val json = encoded(empty)
    assertEquals(json.obj.keys.toList, V1Keys ++ QueryAudit.V2Fields)
    assertEquals(json("queryTemplateIds"), ujson.Arr())
    assertEquals(json("indexesQueried"), ujson.Arr())
    assertEquals(json("metadataFilterKeys"), ujson.Arr())
    assertEquals(json("modelCalls"), ujson.Obj())
    assertEquals(json("cacheHits"), ujson.Obj())
    assertEquals(Contract.decode[QueryAudit](json.render()), Right(empty))
    // some at default, some not: the declared order still holds
    val mixed = empty.copy(indexesQueried = List("researcher-profile-v1"))
    assertEquals(encoded(mixed).obj.keys.toList, V1Keys ++ QueryAudit.V2Fields)
    // also through plain uPickle, the codec Contract uses
    assertEquals(
      ujson.read(upickle.default.write(empty)).obj.keys.toList,
      V1Keys ++ QueryAudit.V2Fields
    )
  }

  test("D-06: a v2 record missing any v2 field on the wire is rejected") {
    QueryAudit.V2Fields.foreach { k =>
      val json = mutate(frozen(2))(_.obj.remove(k))
      val r = Contract.decode[QueryAudit](json)
      assert(
        errors(r).exists(_.contains(s"$k: required in QueryAudit schema version 2")),
        s"$k: $r"
      )
    }
  }

  test("a v1 record carrying a v2 field is rejected, on the wire and in the validator") {
    QueryAudit.V2Fields.foreach { k =>
      val json = mutate(frozen(1))(_(k) = ujson.read(frozen(2))(k))
      val r = Contract.decode[QueryAudit](json)
      assert(
        errors(r).exists(_.contains(s"$k: not a field of QueryAudit schema version 1")),
        s"$k: $r"
      )
    }
    val r = Contract.validate(queryAudit.copy(queryTemplateIds = List("work_by_id")))
    assert(r.exists(_.startsWith("schemaVersion: version 1 carries none of")), r)
  }

  test("v2 field rules: names, index names, filter keys, distinctness") {
    def rejected(a: QueryAudit, fragment: String)(implicit loc: munit.Location): Unit = {
      val e = Contract.validate(a)
      assert(e.exists(_.contains(fragment)), s"expected '$fragment' in $e")
    }
    rejected(
      queryAuditV2.copy(queryTemplateIds = List("Coauthor Neighbors")),
      "queryTemplateIds[0]"
    )
    rejected(queryAuditV2.copy(queryTemplateIds = List("work_by_id", "work_by_id")), "duplicates")
    rejected(queryAuditV2.copy(indexesQueried = List("Bad_Index")), "indexesQueried[0]: must match")
    rejected(queryAuditV2.copy(indexesQueried = List("ab-c", "ab-c")), "indexesQueried: contains")
    rejected(queryAuditV2.copy(metadataFilterKeys = List("window start")), "metadataFilterKeys[0]")
    rejected(queryAuditV2.copy(metadataFilterKeys = List("k", "k")), "metadataFilterKeys: contains")
    rejected(queryAuditV2.copy(cacheHits = Map("Cache Name" -> 1L)), "cacheHits.<key>: must match")
    rejected(queryAuditV2.copy(cacheHits = Map("ranking" -> -1L)), "cacheHits[ranking]: -1 must")
    rejected(
      queryAuditV2.copy(modelCalls = Map("Planner" -> ModelCallCounts(0L, 0L, 0L, 0L, 0L))),
      "modelCalls.<key>: must match"
    )
  }

  test("v2 model-call counters are non-negative and bounded by the logical calls") {
    def rejected(c: ModelCallCounts, fragment: String)(implicit loc: munit.Location): Unit = {
      val e = Contract.validate(queryAuditV2.copy(modelCalls = Map("planner" -> c)))
      assert(e.exists(_.contains(fragment)), s"expected '$fragment' in $e")
    }
    rejected(ModelCallCounts(-1L, 0L, 0L, 0L, 0L), "modelCalls[planner].logicalCalls: -1")
    rejected(ModelCallCounts(1L, 0L, 0L, -1L, 0L), "modelCalls[planner].droppedBoundNumbers: -1")
    rejected(ModelCallCounts(1L, 0L, 0L, 0L, -1L), "modelCalls[planner].fallbacks: -1")
    rejected(ModelCallCounts(1L, 2L, 0L, 0L, 0L), "invalidOutputs: 2 exceeds logicalCalls 1")
    rejected(ModelCallCounts(1L, 0L, 2L, 0L, 0L), "truncatedOutputs: 2 exceeds logicalCalls 1")
    // a fallback without any model call (the fake or rule path) and dropped numbers are unbounded
    assertEquals(
      Contract.validate(
        queryAuditV2.copy(modelCalls = Map("planner" -> ModelCallCounts(0L, 0L, 0L, 3L, 1L)))
      ),
      Nil
    )
  }

  test("existing v1 constructors compile unchanged and keep writing v1") {
    // positional construction with the 21 v1 arguments (as storage and rag do today)
    val a = QueryAudit(
      1,
      queryAudit.queryRunId,
      queryAudit.questionHash,
      queryAudit.plan,
      queryAudit.planSource,
      queryAudit.resolvedResearcherIds,
      queryAudit.unresolvedNames,
      queryAudit.evidenceIds,
      queryAudit.bundleId,
      queryAudit.graphRunId,
      queryAudit.metricPolicyVersions,
      queryAudit.promptVersions,
      queryAudit.modelId,
      queryAudit.inputTokens,
      queryAudit.outputTokens,
      queryAudit.latencyMillis,
      queryAudit.stageMillis,
      queryAudit.verificationCounts,
      queryAudit.answerHash,
      queryAudit.abstained,
      queryAudit.provenance
    )
    assertEquals(a, queryAudit)
    assertEquals(encoded(a), ujson.read(frozen(1)))
  }
}
