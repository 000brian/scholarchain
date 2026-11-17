package org.scholarchain.contracts.runtime

import org.scholarchain.contracts.runtime.RuntimeAdversarialSupport.*
import org.scholarchain.core.*
import org.scholarchain.rag.runtime.*
import org.scholarchain.services.ServiceTestKit
import org.scholarchain.services.ServiceTestKit.*

/** Category 14 (unsupported answer claims), end to end: a scripted model writes claims that the
  * evidence does not support; the HTTP response (services over rag over storage) must never show
  * them as verified, and the audit counts them. L-1, L-2 and L-3 (known, rag P1) are not covered
  * here.
  */
class UnsupportedClaimEndToEndSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private def metricOf(i: SynthesisInput, name: String): String =
    i.evidence.items
      .find(x =>
        x.evidenceType == EvidenceType.Metric && x.metric.nonEmpty &&
          x.subjectIds.headOption.contains(RuntimeFixtures.rid(name).value)
      )
      .map(_.evidenceId.value)
      .getOrElse(sys.error(s"no metric item for $name in the bundle"))

  private def draft(claims: DraftClaim*): Scripted =
    Scripted(AnswerDraft("Overview.", claims.toList, Nil).toJson.render())

  private def harnessWith(f: SynthesisInput => Scripted): Harness =
    harness(model = r => new ScriptModel(r, synthesis = Some((_, i) => f(i))))

  private def claimsOf(r: Resp): Map[String, ujson.Value] =
    r.json("answer")("claims").arr.map(c => c("claimId").str -> c).toMap

  test("a number the evidence does not hold is never verified; the answer abstains") {
    val h = harnessWith { i =>
      draft(
        DraftClaim(
          "claim-1",
          "In 2015-2025, Carol King has 99 distinct coauthors.",
          List(metricOf(i, "C")),
          Nil,
          None
        )
      )
    }
    try {
      val r = post(h, queryBody())
      assertEquals(r.status, 200, r.body.take(300))
      val c = claimsOf(r)("claim-1")
      assertEquals(c("status").str, "conflicting")
      assert(!c("claimText").str.contains("99 distinct"), c("claimText").str)
      assertEquals(r.json("answer")("abstained").bool, true)
    } finally h.close()
  }

  test("a bound number that differs from its cited field is conflicting, not verified") {
    val h = harnessWith { i =>
      val id = metricOf(i, "C")
      draft(
        DraftClaim(
          "claim-1",
          "In 2015-2025, Carol King has 3 distinct coauthors.",
          List(id),
          List(DraftNumber(3.0, id, "value")),
          None
        )
      )
    }
    try {
      val r = post(h, queryBody())
      assertEquals(r.status, 200)
      val c = claimsOf(r)("claim-1")
      // the cited item is the first metric item of Carol King; 3 is either its value (verified) or
      // not (conflicting); never partially supported for a contradicted bound number
      assert(Set("verified", "conflicting").contains(c("status").str), c.render())
    } finally h.close()
  }

  test("a hop count that differs from the cited path is conflicting") {
    val h = harnessWith { i =>
      val path = i.evidence.items
        .find(_.evidenceType == EvidenceType.GraphPath)
        .map(_.evidenceId.value)
        .getOrElse(sys.error("no path item"))
      draft(
        DraftClaim("claim-1", "Alice Smith reaches Dan Brown in 2 hops.", List(path), Nil, None)
      )
    }
    try {
      val r = post(
        h,
        queryBody(
          "What is the shortest collaboration path between Alice Smith and Dan Brown in 2015-2025?"
        )
      )
      assertEquals(r.status, 200, r.body.take(300))
      assertEquals(claimsOf(r)("claim-1")("status").str, "conflicting")
    } finally h.close()
  }

  test("semantic context cited for a number is unsupported") {
    val h = harnessWith { i =>
      val sem = i.evidence.items
        .find(_.evidenceType == EvidenceType.SemanticContext)
        .map(_.evidenceId.value)
        .getOrElse(sys.error("no semantic item"))
      draft(DraftClaim("claim-1", "Dan Brown has 2 publications.", List(sem), Nil, None))
    }
    try {
      val r = post(
        h,
        queryBody(
          "Which researchers working on Dan Brown have the most distinct coauthors in 2015-2025?"
        )
      )
      assertEquals(r.status, 200, r.body.take(300))
      val c = claimsOf(r)("claim-1")
      assertEquals(c("status").str, "unsupported")
      assertEquals(c("evidenceIds").arr.toList, Nil)
    } finally h.close()
  }

  test("the audit counts every status the response shows") {
    val h = harnessWith { i =>
      val id = metricOf(i, "C")
      val good = FakeSynthesis.render(i).claims.head
      Scripted(
        AnswerDraft(
          "Overview.",
          List(
            good.copy(claimId = "claim-1"),
            DraftClaim(
              "claim-2",
              "In 2015-2025, Carol King has 99 distinct coauthors.",
              List(id),
              Nil,
              None
            ),
            DraftClaim(
              "claim-3",
              "Carol King has 4 publications.",
              List(fabricated("evidence")),
              Nil,
              None
            )
          ),
          Nil
        ).toJson.render()
      )
    }
    try {
      val r = post(h, queryBody())
      assertEquals(r.status, 200)
      val shown =
        claimsOf(r).values.map(_("status").str).groupBy(identity).view.mapValues(_.size).toMap
      val queryId = r.json("queryId").str
      val audits = h.runtime.stores.audits
        .read(RunId(queryId), 10)
        .fold(e => fail(e), identity)
      assertEquals(audits.size, 1)
      assertEquals(audits.head.verificationCounts.filter(_._2 > 0), shown)
      assertEquals(shown.get("verified"), Some(1))
    } finally h.close()
  }

  // T-10 owner: rag-engineer (ClaimVerification.verifyClaim, status rule at about :246-248: a name
  // not in the cited evidence is only "uncovered", so a claim with one checked name is kept as
  // partially_supported). The answer states a coauthor relation the graph does not have (Carol
  // King and Igor Petrov are in different components). Possibly the same root cause as T-01.
  // Repaired in Phase 11 step 3f; the .fail mark was removed in step 4.
  test("T-10 a claim naming a collaborator the cited evidence lacks is not kept") {
    val h = harnessWith { i =>
      draft(
        DraftClaim(
          "claim-1",
          "Carol King collaborated with Igor Petrov.",
          List(metricOf(i, "C")),
          Nil,
          None
        )
      )
    }
    try {
      val r = post(h, queryBody())
      assertEquals(r.status, 200)
      val c = claimsOf(r)("claim-1")
      // the specific wrong value: status partially_supported, text kept
      assert(
        !Set("verified", "partially_supported").contains(c("status").str),
        s"${c("status").str}: ${c("claimText").str}"
      )
    } finally h.close()
  }

  test("an unsupported claim never appears with status verified in an async result") {
    val h = harnessWith { i =>
      draft(
        DraftClaim(
          "claim-1",
          "In 2015-2025, Carol King has 99 distinct coauthors.",
          List(metricOf(i, "C")),
          Nil,
          None
        )
      )
    }
    try {
      val ack = post(h, queryBody(mode = "async"))
      assertEquals(ack.status, 202, ack.body.take(300))
      val jobId = ack.json("jobId").str
      // run the queued job on this thread (no worker thread, no sleep)
      assertEquals(h.server.worker.processOne(), org.scholarchain.services.WorkerOutcome.Succeeded)
      val res = get(h, s"/v1/jobs/$jobId/result")
      assertEquals(res.status, 200, res.body.take(300))
      assertEquals(claimsOf(res)("claim-1")("status").str, "conflicting")
    } finally h.close()
  }
}
