package org.scholarchain.rag.runtime

import org.scholarchain.core._
import org.scholarchain.storage.ports.{
  GraphQuery,
  GraphReadPort,
  GraphReadSettings,
  GraphResult,
  GraphStop
}
import org.scholarchain.storage.runtime.RuntimeStores

/** Phase 16, decision 8 items 1 and 2 (3r's L2 and the abstention code for storage's L1 class):
  *   - L2: an absence claim ("no path within 6 hops") that cites a `path=none` evidence item is
  *     checked against that item's completeness and searched depth, not rejected as `HopsNoPath`;
  *   - a bounded search (`complete = false`, time limit or L1's resource limit) never supports an
  *     absence claim, while an honest "whether a path exists is unknown" claim stays verifiable;
  *   - a path search that stopped at a graph store resource limit (`GraphStop.ResourceLimit`) is
  *     recorded in the evidence item and the note, and an abstained answer stores the distinct code
  *     `path-query-bounded` (never `path-query-timeout` or `path-query-failed`).
  */
class PathAbsenceSuite extends munit.FunSuite {
  import RuntimeFixtures._
  import VerificationStatus._
  import ClaimVerification.Reasons

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  // ---- unit level: the verifier ----------------------------------------------------------------

  private val ctx = RuntimeContext.from(config, sparkRun, Set(Full)).toOption.get
  private val queryRun = RunId.of("rag.query", "test", "q", "c").toOption.get
  private val alice = ResearcherRef(rid("A"), "Alice Smith")
  private val bob = ResearcherRef(rid("B"), "Bob Jones")
  private val carol = ResearcherRef(rid("C"), "Carol King")
  private val src = SourceRef("graph:path_between_depth_6", sparkRun, "graph-v1", sparkRun.value)

  private def absent(complete: Boolean, searched: Int, stop: Option[GraphStop]) =
    PathAbsentFact(alice, bob, Full, FullW, 6, complete, searched, src, 20, stop)

  private val completeNone = absent(complete = true, 6, None)
  private val timeStopped = absent(complete = false, 2, Some(GraphStop.TimeLimit))
  private val limitStopped = absent(complete = false, 2, Some(GraphStop.ResourceLimit))
  private val found = PathFact(
    List(alice, carol, bob),
    Full,
    FullW,
    "graph-path-search",
    None,
    truncated = false,
    src,
    20
  )

  private def itemsOf(f: Fact) = {
    val built = new EvidenceBuilder(ctx).build(queryRun, List(f)).fold(e => fail(e), identity)
    (built.items.map(i => i.evidenceId -> i).toMap, built.idOf(f).value)
  }

  private def verify(text: String, f: Fact): VerifiedClaim = {
    val (items, id) = itemsOf(f)
    ClaimVerification.verifyClaim(DraftClaim("claim-1", text, List(id), Nil, None), items)
  }

  test("L2: a correct absence claim in hops citing a complete path=none item is verified") {
    val v = verify(
      "No coauthorship path between Alice Smith and Bob Jones exists within 6 hops.",
      completeNone
    )
    assertEquals(v.status, Verified, v.reasons.toString)
    assert(!v.reasons.contains(Reasons.HopsNoPath), v.reasons.toString)
  }

  test("L2: a shallower stated depth than the completed search is also supported") {
    val v = verify("Alice Smith and Bob Jones are not connected within 4 hops.", completeNone)
    assertEquals(v.status, Verified, v.reasons.toString)
  }

  test("L2: an absence claim deeper than the completed search is never kept") {
    val v = verify(
      "No coauthorship path between Alice Smith and Bob Jones exists within 8 hops.",
      completeNone
    )
    assert(!v.kept, s"${v.status} ${v.reasons}")
    assert(v.reasons.contains(Reasons.AbsenceBeyondDepth), v.reasons.toString)
  }

  test("L2: an unbounded absence claim is not verified (the search had a depth limit)") {
    val v = verify("No path connects Alice Smith and Bob Jones.", completeNone)
    assertNotEquals(v.status, Verified)
    assert(v.reasons.contains(Reasons.AbsenceNoDepth), v.reasons.toString)
  }

  test("L2: a search stopped at the time limit never supports an absence claim") {
    List(
      "No coauthorship path between Alice Smith and Bob Jones exists within depth 6.",
      "No coauthorship path between Alice Smith and Bob Jones exists within 6 hops.",
      "No path connects Alice Smith and Bob Jones."
    ).foreach { t =>
      val v = verify(t, timeStopped)
      assert(!v.kept, s"$t -> ${v.status} ${v.reasons}")
      assert(v.reasons.contains(Reasons.AbsenceBoundedSearch), s"$t -> ${v.reasons}")
    }
  }

  test("L2: a search stopped at a resource limit (L1) never supports an absence claim") {
    val v = verify("Alice Smith and Bob Jones are not connected within 6 hops.", limitStopped)
    assert(!v.kept, s"${v.status} ${v.reasons}")
    assert(v.reasons.contains(Reasons.AbsenceBoundedSearch), v.reasons.toString)
  }

  test("L2: an uncertainty phrase does not hide an absence statement in the same claim") {
    val v = verify(
      "The search did not complete, but no path exists between Alice Smith and Bob Jones.",
      limitStopped
    )
    assert(!v.kept, s"${v.status} ${v.reasons}")
    assert(v.reasons.contains(Reasons.AbsenceBoundedSearch), v.reasons.toString)
  }

  test("L2: an honest 'whether a path exists is unknown' claim on a bounded search stays kept") {
    List(
      "The path search between Alice Smith and Bob Jones stopped after depth 2, so whether a path exists is unknown.",
      "The path search between Alice Smith and Bob Jones did not complete after depth 2; whether a path exists is unknown."
    ).foreach { t =>
      val v = verify(t, limitStopped)
      assertEquals(v.status, Verified, s"$t -> ${v.reasons}")
    }
  }

  test("L2: found-path hop claims are checked as before") {
    val ok = verify(
      "Alice Smith and Bob Jones are connected by a shortest coauthorship path of 2 hops.",
      found
    )
    assertEquals(ok.status, Verified, ok.reasons.toString)
    val wrong = verify(
      "Alice Smith and Bob Jones are connected by a shortest coauthorship path of 3 hops.",
      found
    )
    assertEquals(wrong.status, Conflicting)
    assert(wrong.reasons.contains(Reasons.HopsConflict))
  }

  // ---- P16-A-09: a negation counts only when it governs the path phrase ------------------------

  private def verifyAll(text: String, facts: List[Fact]): VerifiedClaim = {
    val built = new EvidenceBuilder(ctx).build(queryRun, facts).fold(e => fail(e), identity)
    val items = built.items.map(i => i.evidenceId -> i).toMap
    val ids = facts.map(f => built.idOf(f).value)
    ClaimVerification.verifyClaim(DraftClaim("claim-1", text, ids, Nil, None), items)
  }

  test(
    "P16-A-09: a positive hop claim beside an unrelated negation contradicts complete path=none"
  ) {
    List(
      "Alice Smith is not a direct coauthor of Bob Jones; they are connected within 3 hops.",
      "Although they never coauthored, Alice Smith and Bob Jones are linked within 3 hops."
    ).foreach { t =>
      assert(!ClaimVerification.statesAbsence(t), t)
      val v = verify(t, completeNone)
      assertEquals(v.status, Conflicting, s"$t -> ${v.reasons}")
      assert(!v.kept, t)
      assert(v.reasons.contains(Reasons.HopsConflict), s"$t -> ${v.reasons}")
    }
  }

  test("P16-A-09: a found-path hop conflict next to an absence clause is still a HopsConflict") {
    // found path: 2 hops in 2015-2025; complete path=none to depth 6 in 2021-2023 (W1)
    val noneW1 = PathAbsentFact(alice, bob, W1, W1W, 6, true, 6, src, 20, None)
    val wrong = verifyAll(
      "Alice Smith and Bob Jones are connected by 3 hops in 2015-2025, not within 6 hops in 2021-2023.",
      List(found, noneW1)
    )
    assertEquals(wrong.status, Conflicting, wrong.reasons.toString)
    assert(wrong.reasons.contains(Reasons.HopsConflict), wrong.reasons.toString)
    // the same claim with the found path's true hop count is verified (both parts checked)
    val right = verifyAll(
      "Alice Smith and Bob Jones are connected by 2 hops in 2015-2025, not within 6 hops in 2021-2023.",
      List(found, noneW1)
    )
    assertEquals(right.status, Verified, right.reasons.toString)
  }

  test("P16-A-09: governed absence phrasings are still absence statements") {
    List(
      "No coauthorship path between Alice Smith and Bob Jones exists within 6 hops.",
      "Alice Smith and Bob Jones are not connected within 4 hops.",
      "No path connects Alice Smith and Bob Jones.",
      "In 2015-2025, no coauthorship path between Alice Smith and Bob Jones exists within depth 6 (the bounded search completed).",
      "No path between Alice Smith and Bob Jones exists within depth 6 in full-2015-2025.",
      "The search did not complete, but no path exists between Alice Smith and Bob Jones."
    ).foreach(t => assert(ClaimVerification.statesAbsence(t), t))
    List(
      "The path search between Alice Smith and Bob Jones did not complete after depth 2; whether a path exists is unknown.",
      "Alice Smith is not a direct coauthor of Bob Jones; they are connected within 3 hops."
    ).foreach(t => assert(!ClaimVerification.statesAbsence(t), t))
  }

  test("L2: the new reasons are fixed texts without digits") {
    List(Reasons.AbsenceBoundedSearch, Reasons.AbsenceBeyondDepth, Reasons.AbsenceNoDepth)
      .foreach { r =>
        assert(Reasons.all.contains(r), r)
        assert(!r.exists(_.isDigit), r)
      }
  }

  test("L1: the evidence item records why an incomplete search stopped; complete items unchanged") {
    def pairs(f: Fact) = {
      val (items, _) = itemsOf(f)
      EvidenceValues.pairs(items.values.head)
    }
    assertEquals(pairs(limitStopped).get("stoppedBy"), Some("resource-limit"))
    assertEquals(pairs(timeStopped).get("stoppedBy"), Some("time-limit"))
    assertEquals(pairs(absent(complete = false, 2, None)).get("stoppedBy"), Some("time-limit"))
    assertEquals(pairs(completeNone).get("stoppedBy"), None)
  }

  // ---- codes ------------------------------------------------------------------------------------

  test("L1 code: path-query-bounded is a distinct allowlisted code with a short fixed text") {
    val c = AbstentionCodes.PathQueryBounded
    assertEquals(c, "path-query-bounded")
    assert(AbstentionCodes.All.contains(c))
    assert(QueryAudit.isValidAbstentionCode(c))
    val t = AbstentionCodes.Table(c)
    assert(t.nonEmpty && t.length <= 120 && !t.exists(_.isDigit), t)
    assert(t.contains("unknown"), t)
    assertNotEquals(t, AbstentionCodes.Table(AbstentionCodes.PathQueryTimeout))
    assertNotEquals(t, AbstentionCodes.Table(AbstentionCodes.PathQueryFailed))
  }

  test("L1 code: the context code follows the stop reason") {
    assertEquals(
      QueryWorkflow.pathContextCodes(List(limitStopped)),
      List(AbstentionCodes.PathQueryBounded)
    )
    assertEquals(
      QueryWorkflow.pathContextCodes(List(timeStopped)),
      List(AbstentionCodes.PathQueryTimeout)
    )
    assertEquals(
      QueryWorkflow.pathContextCodes(List(absent(complete = false, 2, None))),
      List(AbstentionCodes.PathQueryTimeout)
    )
    assertEquals(
      QueryWorkflow.pathContextCodes(List(completeNone)),
      List(AbstentionCodes.NoPathWithinDepth)
    )
  }

  // ---- workflow level ---------------------------------------------------------------------------

  private lazy val w = world()

  private def boundedStores(stop: Option[GraphStop]): RuntimeStores = {
    val base = w.env.graph
    val port = new GraphReadPort {
      def settings: GraphReadSettings = base.settings
      def run(q: GraphQuery): Either[String, GraphResult] = q match {
        case p: GraphQuery.PathBetween =>
          Right(
            GraphResult(
              s"path_between_depth_${p.maxDepth}",
              Nil,
              truncated = false,
              complete = false,
              Some(2),
              stop
            )
          )
        case other => base.run(other)
      }
    }
    RuntimeStores
      .of(storageConfig, port, w.env.storage.kv, w.env.vectors, w.env.textStore)
      .fold(e => sys.error(e), identity)
  }

  private def ask(s: RuntimeStores, claim: String): WorkflowResult = {
    val model = new SplitModel(
      w.rule,
      _ =>
        Right(
          AnswerDraft("Answer.", List(DraftClaim("claim-1", claim, Nil, Nil, None)), Nil).toJson
            .render()
        )
    )
    new QueryWorkflow(
      s,
      w.ctx,
      model,
      w.prompts,
      Some(w.retriever),
      storageConfig.vectors.collaborationPathIndex
    ).run(
      QueryRequest(
        "What is the shortest collaboration path between Alice Smith and Igor Petrov in 2015-2025?"
      )
    ).fold(e => fail(s"workflow Left: $e"), identity)
  }

  test("L1 workflow: a resource-limited path search abstains with path-query-bounded") {
    val r = ask(boundedStores(Some(GraphStop.ResourceLimit)), "A path of 2 hops exists.")
    assert(r.response.abstained, r.response.answerText)
    assertEquals(
      r.audit.abstentionCodes,
      List(AbstentionCodes.PathQueryBounded, AbstentionCodes.NoVerifiedClaim)
    )
    assertEquals(Contract.validate(r.audit), Nil)
    val item = r.response.bundle.get.items.find(_.evidenceType == EvidenceType.GraphPath).get
    assertEquals(EvidenceValues.pairs(item).get("complete"), Some("false"))
    assertEquals(EvidenceValues.pairs(item).get("stoppedBy"), Some("resource-limit"))
    val text = r.response.answerText + " " + r.notes.mkString(" ")
    assert(text.contains("whether a path exists is unknown"), text)
    assert(text.contains("resource limit"), text)
    assert(!text.contains("time limit"), text)
  }

  test("L1 workflow: a model absence claim on a resource-limited search is removed") {
    val r = ask(
      boundedStores(Some(GraphStop.ResourceLimit)),
      "No coauthorship path between Alice Smith and Igor Petrov exists within depth 6."
    )
    assert(r.response.abstained, r.response.answerText)
    assert(r.audit.abstentionCodes.contains(AbstentionCodes.PathQueryBounded))
    assert(
      !r.response.claims.exists(c =>
        c.claimText.startsWith("No coauthorship path") && (c.status == Verified ||
          c.status == PartiallySupported)
      ),
      r.response.claims.toString
    )
  }

  test("L1 workflow: a time-limited search keeps path-query-timeout") {
    val r = ask(boundedStores(Some(GraphStop.TimeLimit)), "A path of 2 hops exists.")
    assert(r.response.abstained, r.response.answerText)
    assertEquals(
      r.audit.abstentionCodes,
      List(AbstentionCodes.PathQueryTimeout, AbstentionCodes.NoVerifiedClaim)
    )
  }
}
