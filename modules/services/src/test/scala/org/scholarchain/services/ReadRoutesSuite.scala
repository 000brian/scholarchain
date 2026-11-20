package org.scholarchain.services

import java.nio.charset.StandardCharsets
import java.util.Base64

import org.scholarchain.core._
import org.scholarchain.rag.runtime.{AbstentionCodes, RuntimeFixtures}

/** Evidence, explain and graph exploration routes (decisions 7g, 7h). */
class ReadRoutesSuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private val h = FunFixture[Harness](_ => harness(), _.close())

  private def answer(h: Harness): CompletedResult = {
    val r = post(h, queryBody())
    assertEquals(r.status, 200, r.body)
    upickle.default.read[CompletedResult](r.body)
  }

  // ---- evidence ------------------------------------------------------------------------------

  h.test("missing evidence is 404 EVIDENCE_NOT_FOUND; malformed IDs and windows are 400") { h =>
    val missing = get(h, s"/v1/evidence/evidence:v1:${"0" * 32}")
    assertEquals(missing.status, 404)
    assertEquals(missing.error, "EVIDENCE_NOT_FOUND")
    assertEquals(missing.json("traceId").str, missing.header("X-Trace-Id").get)
    val withWindow = get(h, s"/v1/evidence/evidence:v1:${"0" * 32}?window=2015-2025")
    assertEquals(withWindow.error, "EVIDENCE_NOT_FOUND")
    assertEquals(get(h, "/v1/evidence/not-an-id").error, "INVALID_REQUEST")
    assertEquals(
      get(h, s"/v1/evidence/evidence:v1:${"0" * 32}?window=2015").error,
      "INVALID_REQUEST"
    )
    assertEquals(get(h, s"/v1/evidence/evidence:v1:${"0" * 32}?x=1").error, "INVALID_REQUEST")
    assertEquals(
      get(h, s"/v1/evidence/evidence:v1:${"0" * 32}?window=a&window=b").error,
      "INVALID_REQUEST"
    )
  }

  h.test(
    "an evidence ID stored in several windows needs ?window= (400 WINDOW_REQUIRED listing them)"
  ) { h =>
    val res = answer(h)
    val items = res.answer.evidence.get
    val item = items.find(_.window.contains(RuntimeFixtures.FullW)).get
    val bundle = h.runtime.stores.evidence.getBundle(res.answer.bundleId.get).toOption.flatten.get
    // store the same evidence ID in a second window (its own bundle)
    val other = item.copy(window = Some(RuntimeFixtures.W1W))
    val extra = EvidenceBundle(
      1,
      EvidenceId.forBundle(bundle.provenance.runId, List(other.evidenceId)),
      List(other),
      bundle.provenance
    )
    assert(h.runtime.stores.evidence.putBundle(extra).isRight)
    val r = get(h, s"/v1/evidence/${item.evidenceId.value}")
    assertEquals(r.status, 400, r.body)
    assertEquals(r.error, "WINDOW_REQUIRED")
    assertEquals(
      r.json("details")("windows").arr.map(_.str).toList.sorted,
      List("2015-2025", "2021-2023")
    )
    val one = get(h, s"/v1/evidence/${item.evidenceId.value}?window=2021-2023")
    assertEquals(one.status, 200)
    assertEquals(one.json("window")("startYear").num.toInt, 2021)
    // a single stored window needs no parameter
    val single = items.find(i => i.evidenceId != item.evidenceId && i.window.nonEmpty).get
    assertEquals(get(h, s"/v1/evidence/${single.evidenceId.value}").status, 200)
  }

  test("sourceUris: dblp keys become dblp record URLs, other records scholarchain: references") {
    val item = EvidenceItem(
      1,
      EvidenceId(s"evidence:v1:${"1" * 32}"),
      EvidenceType.Metric,
      List("x"),
      None,
      None,
      None,
      RuntimeFixtures.prov(
        List(
          "conf/acl/A16",
          "journals/tods/Smith20",
          "ResearcherMetricCache:researcher#a/window#2015-2025",
          "doi:10.1/x"
        )
      )
    )
    assertEquals(
      EvidenceService.sourceUris(item),
      List(
        "https://dblp.org/rec/conf/acl/A16",
        "https://dblp.org/rec/journals/tods/Smith20",
        "scholarchain:ResearcherMetricCache:researcher#a/window#2015-2025",
        "scholarchain:doi:10.1/x"
      )
    )
  }

  // ---- explain -------------------------------------------------------------------------------

  h.test(
    "explain: unknown query is 404 QUERY_NOT_FOUND, malformed is 400, a rerun counts attempts"
  ) { h =>
    assertEquals(get(h, s"/v1/explain/run:v1:${"0" * 32}").error, "QUERY_NOT_FOUND")
    assertEquals(get(h, "/v1/explain/qry-1").error, "INVALID_REQUEST")
    val res = answer(h)
    answer(h)
    val ex = get(h, s"/v1/explain/${res.queryId}").json
    assertEquals(ex("attemptCount").num.toInt, 2)
    assert(Set("llm", "rule").contains(ex("planSource").str))
    // Phase 11: the v3 prompt set is the default (rag 3f)
    assert(ex("promptVersions").arr.exists(_.str.startsWith("answer-synthesis-v3@sha256:")))
    assert(ex("stageMillis").obj.keySet.contains("synthesis"))
    assertEquals(ex("scoreComponents"), ujson.Arr())
  }

  h.test("explain shows the QueryAudit v2 fields; scoreComponents stays empty with its note") { h =>
    val res = answer(h)
    val ex = get(h, s"/v1/explain/${res.queryId}").json
    val stored = h.runtime.stores.audits.read(RunId(res.queryId), 10).toOption.get.last
    // Phase 15 (G-28): rag writes QueryAudit schema version 3
    assertEquals(stored.schemaVersion, 3)
    assertEquals(ex("auditSchemaVersion").num.toInt, 3)
    // not abstained: the reasons field is present and empty
    assert(!stored.abstained)
    assertEquals(ex("abstentionReasons"), ujson.Arr())
    assertEquals(ex("queryTemplateIds").arr.map(_.str).toList, stored.queryTemplateIds)
    assert(stored.queryTemplateIds.nonEmpty, "the fixture query runs graph templates")
    assertEquals(ex("indexesQueried").arr.map(_.str).toList, stored.indexesQueried)
    assertEquals(ex("metadataFilterKeys").arr.map(_.str).toList, stored.metadataFilterKeys)
    assertEquals(ex("modelCalls").obj.keySet.toSet, stored.modelCalls.keySet)
    assert(stored.modelCalls.keySet == Set("planner", "synthesis"), stored.modelCalls.toString)
    assertEquals(
      ex("modelCalls")("synthesis")("logicalCalls").num.toLong,
      stored.modelCalls("synthesis").logicalCalls
    )
    assertEquals(
      ex("modelCalls")("planner").obj.keySet.toSet,
      Set("logicalCalls", "invalidOutputs", "truncatedOutputs", "droppedBoundNumbers", "fallbacks")
    )
    assertEquals(
      ex("cacheHits").obj.map { case (k, v) => k -> v.num.toLong }.toMap,
      stored.cacheHits
    )
    assertEquals(ex("scoreComponents"), ujson.Arr())
    assertEquals(
      ex("notes").arr.map(_.str).toList,
      List(ExplainView.ScoreNote, ExplainView.AttemptsNote)
    )
    // never hashes, prompt text or model output
    assert(!ex.obj.contains("questionHash") && !ex.obj.contains("answerHash"))
  }

  h.test("explain of a schema version 1 audit: v2 fields empty and a note says so") { h =>
    val res = answer(h)
    val v2 = h.runtime.stores.audits.read(RunId(res.queryId), 10).toOption.get.last
    val v1 = v2.copy(
      schemaVersion = 1,
      queryTemplateIds = Nil,
      indexesQueried = Nil,
      metadataFilterKeys = Nil,
      modelCalls = Map.empty,
      cacheHits = Map.empty
    )
    assert(h.runtime.stores.audits.append(v1).isRight)
    val ex = get(h, s"/v1/explain/${res.queryId}").json
    assertEquals(ex("attemptCount").num.toInt, 2)
    assertEquals(ex("auditSchemaVersion").num.toInt, 1)
    assertEquals(ex("queryTemplateIds"), ujson.Arr())
    assertEquals(ex("modelCalls"), ujson.Obj())
    assertEquals(ex("cacheHits"), ujson.Obj())
    assertEquals(ex("notes").arr.map(_.str).toList, List(ExplainView.ScoreNote, ExplainView.V1Note))
    assertEquals(ex("abstentionReasons"), ujson.Arr())
  }

  h.test("explain of an abstained v3 audit lists each stored code with its fixed text (G-28)") {
    h =>
      val res = answer(h)
      val v3 = h.runtime.stores.audits.read(RunId(res.queryId), 10).toOption.get.last
      assertEquals(v3.schemaVersion, 3)
      val codes = List(
        AbstentionCodes.NoPathWithinDepth,
        AbstentionCodes.NoVerifiedClaim,
        "some-future-code" // well formed but not in rag's table: reads as unclassified
      )
      val abstained = v3.copy(abstained = true, abstentionCodes = codes)
      assert(h.runtime.stores.audits.append(abstained).isRight)
      val ex = get(h, s"/v1/explain/${res.queryId}").json
      assertEquals(ex("abstained").bool, true)
      val reasons = ex("abstentionReasons").arr.toList
      assertEquals(reasons.map(_.obj.keySet.toSet), List.fill(3)(Set("code", "text")))
      assertEquals(reasons.map(_("code").str), codes)
      assertEquals(
        reasons.map(_("text").str),
        List(
          AbstentionCodes.Table(AbstentionCodes.NoPathWithinDepth),
          AbstentionCodes.Table(AbstentionCodes.NoVerifiedClaim),
          AbstentionCodes.Table(AbstentionCodes.Unclassified)
        )
      )
      val view = upickle.default.read[ExplainView](get(h, s"/v1/explain/${res.queryId}").body)
      assertEquals(view.abstentionReasons.map(_.code), codes)
  }

  // ---- graph ---------------------------------------------------------------------------------

  h.test("graph nodes: researcher, work, unknown (404), malformed (400), other kinds (422)") { h =>
    val r = get(h, s"/v1/graph/nodes/${RuntimeFixtures.rC.value}")
    assertEquals(r.status, 200, r.body)
    assertEquals(r.json("kind").str, "researcher")
    assertEquals(r.json("graphRunId").str, RuntimeFixtures.sparkRun.value)
    val w = get(h, s"/v1/graph/nodes/${RuntimeFixtures.workId("conf/acl/A16").value}")
    assertEquals(w.status, 200, w.body)
    assertEquals(w.json("kind").str, "work")
    assertEquals(w.json("properties")("dblpKey").str, "conf/acl/A16")
    assertEquals(get(h, s"/v1/graph/nodes/researcher:v1:${"0" * 32}").error, "NODE_NOT_FOUND")
    assertEquals(get(h, "/v1/graph/nodes/nobody").error, "INVALID_REQUEST")
    assertEquals(get(h, s"/v1/graph/nodes/evidence:v1:${"0" * 32}").status, 422)
    assertEquals(get(h, s"/v1/graph/nodes/edge:v1:${"0" * 32}").error, "UNSUPPORTED_NODE_KIND")
    assertEquals(get(h, s"/v1/graph/nodes/${RuntimeFixtures.rC.value}?x=1").status, 400)
    // percent-encoded colons are decoded
    assertEquals(
      get(h, s"/v1/graph/nodes/${RuntimeFixtures.rC.value.replace(":", "%3A")}").status,
      200
    )
  }

  h.test("neighbors: researcher coauthors and work authors; bounds and unsupported kinds") { h =>
    val g = RuntimeFixtures.rG.value
    val all = get(h, s"/v1/graph/nodes/$g/neighbors")
    assertEquals(all.status, 200, all.body)
    val ids = all.json("rows").arr.map(_("coauthorId").str).toSet
    assertEquals(ids, Set(RuntimeFixtures.rC, RuntimeFixtures.rD, RuntimeFixtures.rF).map(_.value))
    assert(all.json("rows").arr.forall(_("edgeType").str == "COAUTHORED_WITH"))
    assertEquals(all.json("window").str, "full-2015-2025")
    assertEquals(all.json("truncated").bool, false)
    assertEquals(all.json("nextCursor"), ujson.Null)
    val work = get(h, s"/v1/graph/nodes/${RuntimeFixtures.workId("conf/acl/A16").value}/neighbors")
    assertEquals(work.status, 200, work.body)
    assertEquals(
      work.json("rows").arr.map(_("researcherId").str).toSet,
      Set(RuntimeFixtures.rA.value, RuntimeFixtures.rB.value)
    )
    def code(q: String) = {
      val r = get(h, s"/v1/graph/nodes/$g/neighbors?$q"); (r.status, r.error)
    }
    assertEquals(code("depth=2"), (422, "UNSUPPORTED_DEPTH"))
    assertEquals(code("depth=3"), (422, "UNSUPPORTED_DEPTH"))
    assertEquals(code("depth=4"), (400, "INVALID_REQUEST"))
    assertEquals(code("edgeTypes=HAS_TOPIC"), (422, "UNSUPPORTED_EDGE_TYPE"))
    assertEquals(code("edgeTypes=AFFILIATED_WITH"), (422, "UNSUPPORTED_EDGE_TYPE"))
    assertEquals(code("edgeTypes=PUBLISHED_IN"), (422, "UNSUPPORTED_EDGE_TYPE"))
    assertEquals(code("edgeTypes=AUTHORED&direction=out"), (422, "UNSUPPORTED_EDGE_TYPE"))
    assertEquals(code("edgeTypes=COAUTHORED_WITH,AUTHORED"), (422, "UNSUPPORTED_EDGE_TYPE"))
    assertEquals(code("edgeTypes=FRIEND"), (400, "INVALID_REQUEST"))
    assertEquals(code("limit=0"), (400, "INVALID_REQUEST"))
    assertEquals(code("limit=51"), (400, "INVALID_REQUEST"))
    assertEquals(code("limit=x"), (400, "INVALID_REQUEST"))
    assertEquals(code("direction=up"), (400, "INVALID_REQUEST"))
    assertEquals(code("window=w1999"), (400, "INVALID_REQUEST"))
    assertEquals(code("hops=1"), (400, "INVALID_REQUEST"))
    val workOut = get(
      h,
      s"/v1/graph/nodes/${RuntimeFixtures.workId("conf/acl/A16").value}/neighbors?direction=out"
    )
    assertEquals(workOut.error, "UNSUPPORTED_EDGE_TYPE")
    assertEquals(get(h, s"/v1/graph/nodes/$g/neighbors?direction=in").status, 200)
  }

  h.test("cursors: pages join to the full list without gaps") { h =>
    val g = RuntimeFixtures.rG.value
    val full = get(h, s"/v1/graph/nodes/$g/neighbors").json("rows").arr.toList
    var rows = List.empty[ujson.Value]
    var cursor: Option[String] = None
    var pages = 0
    var done = false
    while (!done) {
      val q = "limit=1" + cursor.map(c => s"&cursor=$c").getOrElse("")
      val r = get(h, s"/v1/graph/nodes/$g/neighbors?$q")
      assertEquals(r.status, 200, r.body)
      rows ++= r.json("rows").arr
      pages += 1
      r.json("nextCursor") match {
        case ujson.Str(c) => cursor = Some(c)
        case _            => done = true
      }
      assert(pages <= 10)
    }
    assertEquals(pages, 3)
    assertEquals(rows, full)
  }

  test("default limit with runtime.graphResultLimit below 20; past the cap: truncated, no cursor") {
    val cfg = config.copy(runtime = config.runtime.map(_.copy(graphResultLimit = 2)))
    val hh = harness(cfg = cfg)
    try {
      val r = get(hh, s"/v1/graph/nodes/${RuntimeFixtures.rG.value}/neighbors")
      assertEquals(r.status, 200, r.body)
      assertEquals(r.json("rows").arr.size, 2)
      assertEquals(r.json("truncated").bool, true)
      assertEquals(r.json("nextCursor"), ujson.Null)
      assertEquals(
        get(hh, s"/v1/graph/nodes/${RuntimeFixtures.rG.value}/neighbors?limit=3").status,
        400
      )
      // limit 1: one more page, then the cap
      val p1 = get(hh, s"/v1/graph/nodes/${RuntimeFixtures.rG.value}/neighbors?limit=1")
      assertEquals(p1.json("truncated").bool, false)
      val c = p1.json("nextCursor").str
      val p2 = get(hh, s"/v1/graph/nodes/${RuntimeFixtures.rG.value}/neighbors?limit=1&cursor=$c")
      assertEquals(p2.json("rows").arr.size, 1)
      assertEquals(p2.json("truncated").bool, true)
      assertEquals(p2.json("nextCursor"), ujson.Null)
    } finally hh.close()
  }

  h.test(
    "cursor validation: malformed, wrong shape, negative or unaligned offset, other params or run"
  ) { h =>
    val g = RuntimeFixtures.rG.value
    val path = s"/v1/graph/nodes/$g/neighbors"
    def enc(o: ujson.Obj) =
      Base64.getUrlEncoder.withoutPadding.encodeToString(
        o.render().getBytes(StandardCharsets.UTF_8)
      )
    def cur(
        offset: Int = 2,
        limit: Int = 2,
        window: String = "full-2015-2025",
        run: String = RuntimeFixtures.sparkRun.value,
        node: String = g,
        v: Int = 1
    ) = ujson.Obj(
      "v" -> v,
      "graphRunId" -> run,
      "nodeId" -> node,
      "direction" -> "both",
      "edgeTypes" -> ujson.Arr("COAUTHORED_WITH"),
      "window" -> window,
      "limit" -> limit,
      "offset" -> offset
    )
    def status(c: String, q: String = "limit=2") = {
      val r = get(h, s"$path?$q&cursor=$c"); (r.status, if (r.status == 200) "" else r.error)
    }
    assertEquals(status(enc(cur())), (200, ""))
    assertEquals(status("!!!not-base64"), (400, "INVALID_CURSOR"))
    assertEquals(status(enc(ujson.Obj("v" -> 1))), (400, "INVALID_CURSOR"))
    val extraKey = cur(); extraKey("extra") = 1
    assertEquals(status(enc(extraKey)), (400, "INVALID_CURSOR"))
    assertEquals(status(enc(cur(v = 2))), (400, "INVALID_CURSOR"))
    assertEquals(status(enc(cur(offset = -2))), (400, "INVALID_CURSOR"))
    assertEquals(status(enc(cur(offset = 1))), (400, "INVALID_CURSOR"))
    assertEquals(status(enc(cur(offset = 50))), (400, "INVALID_CURSOR"))
    assertEquals(
      status(enc(cur(limit = 3, offset = 3))),
      (400, "INVALID_CURSOR")
    ) // request limit 2
    assertEquals(status(enc(cur(window = "w2021-2023"))), (400, "INVALID_CURSOR"))
    assertEquals(status(enc(cur(run = s"run:v1:${"0" * 32}"))), (400, "INVALID_CURSOR"))
    assertEquals(status(enc(cur(node = RuntimeFixtures.rC.value))), (400, "INVALID_CURSOR"))
    assertEquals(
      status(Base64.getUrlEncoder.encodeToString("[1]".getBytes)),
      (400, "INVALID_CURSOR")
    )
  }
}
