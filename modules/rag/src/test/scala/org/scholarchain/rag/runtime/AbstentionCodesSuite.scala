package org.scholarchain.rag.runtime

import org.scholarchain.core._
import org.scholarchain.storage.ports.{GraphQuery, GraphReadPort, GraphReadSettings, GraphResult}
import org.scholarchain.storage.runtime.RuntimeStores

/** Phase 15, G-28: every abstained answer stores fixed abstention codes in its QueryAudit (schema
  * version 3); a non-abstained answer stores none; the codes come from one fixed table with short
  * texts (the explain view's `abstentionReasons`).
  */
class AbstentionCodesSuite extends munit.FunSuite {
  import RuntimeFixtures._

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private lazy val w = world()

  private def stores(graph: GraphReadPort => GraphReadPort): RuntimeStores =
    RuntimeStores
      .of(storageConfig, graph(w.env.graph), w.env.storage.kv, w.env.vectors, w.env.textStore)
      .fold(e => sys.error(e), identity)

  private def workflow(
      s: RuntimeStores = w.env,
      model: LanguageModel = new FakeModel(w.rule)
  ): QueryWorkflow =
    new QueryWorkflow(
      s,
      w.ctx,
      model,
      w.prompts,
      Some(w.retriever),
      storageConfig.vectors.collaborationPathIndex
    )

  private def run(wf: QueryWorkflow, q: String): WorkflowResult =
    wf.run(QueryRequest(q)).fold(e => fail(s"workflow Left: $e"), identity)

  private def pathPort(answer: GraphQuery.PathBetween => Either[String, GraphResult])(
      base: GraphReadPort
  ): GraphReadPort =
    new GraphReadPort {
      def settings: GraphReadSettings = base.settings
      def run(q: GraphQuery): Either[String, GraphResult] = q match {
        case p: GraphQuery.PathBetween => answer(p)
        case other                     => base.run(other)
      }
    }

  /** The QueryAudit v3 rule, the stored copy and the table, for any result. */
  private def checkAudit(r: WorkflowResult): Unit = {
    val a = r.audit
    assertEquals(a.schemaVersion, 3)
    assertEquals(a.abstained, r.response.abstained)
    if (a.abstained) assert(a.abstentionCodes.nonEmpty, r.response.answerText)
    else assertEquals(a.abstentionCodes, Nil)
    assert(a.abstentionCodes.forall(AbstentionCodes.Table.contains), a.abstentionCodes.toString)
    assert(!a.abstentionCodes.contains(AbstentionCodes.Unclassified), a.abstentionCodes.toString)
    assertEquals(Contract.validate(a), Nil)
    assertEquals(
      w.env.audits.read(r.queryRunId, 10).map(_.last.abstentionCodes),
      Right(a.abstentionCodes)
    )
  }

  test(
    "G-28: the code table is fixed, valid for QueryAudit, and every text is short and name-free"
  ) {
    assertEquals(AbstentionCodes.All.distinct, AbstentionCodes.All)
    AbstentionCodes.Table.foreach { case (code, text) =>
      assert(QueryAudit.isValidAbstentionCode(code), code)
      assert(text.nonEmpty && text.length <= 120, code)
      assert(!text.exists(_.isDigit), s"$code: a fixed text holds no number")
    }
    assertEquals(
      AbstentionCodes.text("not-a-code"),
      AbstentionCodes.Table(AbstentionCodes.Unclassified)
    )
    assertEquals(
      AbstentionCodes.reasons(List(AbstentionCodes.PathQueryFailed)),
      List(AbstentionCodes.PathQueryFailed -> "the path search failed (graph store failure)")
    )
  }

  test(
    "G-28: forAudit is empty when answered, falls back to unclassified, and keeps at most eight"
  ) {
    assertEquals(AbstentionCodes.forAudit(false, List(AbstentionCodes.PathQueryFailed)), Nil)
    assertEquals(AbstentionCodes.forAudit(true, Nil), List(AbstentionCodes.Unclassified))
    assertEquals(
      AbstentionCodes.forAudit(true, List("Not A Code", "made-up-code")),
      List(AbstentionCodes.Unclassified)
    )
    val many = AbstentionCodes.All.filterNot(_ == AbstentionCodes.Unclassified)
    assert(many.size > QueryAudit.MaxAbstentionCodes)
    assertEquals(
      AbstentionCodes.forAudit(true, many ++ many),
      many.take(QueryAudit.MaxAbstentionCodes)
    )
  }

  test("G-28: answered questions store no code") {
    List(
      "How many distinct coauthors does Carol King have in 2015-2025?",
      "Which researchers have the most distinct coauthors in 2015-2025?",
      "What is the shortest coauthorship path between Alice Smith and Dan Brown in 2015-2025?"
    ).foreach { q =>
      val r = run(workflow(), q)
      assert(!r.response.abstained, s"$q\n${r.response.answerText}")
      checkAudit(r)
    }
  }

  test("G-28: each abstention path stores its own code") {
    val cases = List(
      "How many distinct coauthors does Zebulon Quartermaine have in 2015-2025?" ->
        AbstentionCodes.ResearcherNotResolved,
      "Who are the direct collaborators of Wei Wang in 2015-2025?" ->
        AbstentionCodes.ResearcherNotResolved,
      "Which researchers at Stanford University have the most distinct coauthors in 2015-2025?" ->
        AbstentionCodes.UnsupportedInstitutionFilter,
      "Who are the direct collaborators of Carol King in 2021-2023?" ->
        AbstentionCodes.WindowNotLoaded
    )
    cases.foreach { case (q, code) =>
      val r = run(workflow(), q)
      assert(r.response.abstained, s"$q\n${r.response.answerText}")
      assert(r.audit.abstentionCodes.contains(code), s"$q: ${r.audit.abstentionCodes}")
      checkAudit(r)
    }
  }

  test("G-28: a failed path query stores path-query-failed, never the store message") {
    val s = stores(pathPort(_ => Left("store failure (transient): secret-host.example")))
    val r = run(
      workflow(s),
      "What is the shortest collaboration path between Alice Smith and Igor Petrov in 2015-2025?"
    )
    assert(r.response.abstained, r.response.answerText)
    assertEquals(r.audit.abstentionCodes, List(AbstentionCodes.PathQueryFailed))
    assert(!upickle.default.write(r.audit).contains("secret-host"))
    checkAudit(r)
  }

  test(
    "G-28: no path within depth plus a removed claim stores no-path-within-depth and no-verified-claim"
  ) {
    // the q13 cause-1 pattern (3r report): the search is complete and empty, the claim is removed
    val model = new SplitModel(
      w.rule,
      _ =>
        Right(
          AnswerDraft(
            "No path.",
            List(DraftClaim("claim-1", "The two researchers are connected.", Nil, Nil, None)),
            Nil
          ).toJson.render()
        )
    )
    val r = run(
      workflow(model = model),
      "Is there a coauthorship path between Alice Smith and Hana Ito in 2015-2025?"
    )
    assert(r.response.abstained, r.response.answerText)
    assertEquals(
      r.audit.abstentionCodes,
      List(AbstentionCodes.NoPathWithinDepth, AbstentionCodes.NoVerifiedClaim)
    )
    // the prose is unchanged by the codes
    assertEquals(r.response.abstentionReason, Some(QueryWorkflow.NoVerifiedClaimText))
    checkAudit(r)
  }

  test("G-28: a path search stopped at its time budget gives path-query-timeout when it abstains") {
    val s = stores(
      pathPort(p =>
        Right(
          GraphResult(s"path_between_depth_${p.maxDepth}", Nil, false, complete = false, Some(2))
        )
      )
    )
    val model = new SplitModel(
      w.rule,
      _ =>
        Right(
          AnswerDraft(
            "Unknown.",
            List(DraftClaim("claim-1", "A path of 2 hops exists.", Nil, Nil, None)),
            Nil
          ).toJson.render()
        )
    )
    val r = run(
      workflow(s, model),
      "What is the shortest collaboration path between Alice Smith and Igor Petrov in 2015-2025?"
    )
    assert(r.response.abstained, r.response.answerText)
    assertEquals(
      r.audit.abstentionCodes,
      List(AbstentionCodes.PathQueryTimeout, AbstentionCodes.NoVerifiedClaim)
    )
    checkAudit(r)
  }

  test("G-28: path context codes come only from absent-path facts") {
    val ref = ResearcherRef(rid("A"), "A")
    val src = SourceRef("graph:path_between_depth_6", sparkRun, "graph-v1", sparkRun.value)
    val w0 = YearWindow(2015, 2025)
    def absent(complete: Boolean) =
      PathAbsentFact(ref, ref, Full, w0, 6, complete, 6, src, 20)
    assertEquals(QueryWorkflow.pathContextCodes(Nil), Nil)
    assertEquals(
      QueryWorkflow.pathContextCodes(List(absent(true), absent(true))),
      List(AbstentionCodes.NoPathWithinDepth)
    )
    assertEquals(
      QueryWorkflow.pathContextCodes(List(absent(false))),
      List(AbstentionCodes.PathQueryTimeout)
    )
  }
}
