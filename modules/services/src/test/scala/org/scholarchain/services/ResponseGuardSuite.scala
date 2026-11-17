package org.scholarchain.services

import org.scholarchain.core._
import org.scholarchain.rag.runtime.RuntimeServiceFixtures
import org.scholarchain.storage.jobs._

/** Phase 11 C-02 and C-03 (services part): answer and evidence responses never show a bucket, an S3
  * URI, an ARN, an AWS host or an account number, also for bundles and results stored before rag
  * stopped producing them.
  */
class ResponseGuardSuite extends munit.FunSuite {
  import ServiceTestKit._

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private val Bucket = "example-artifact-bucket"
  private val S3Uri = s"s3://$Bucket/semantic-context/text/a1b2"
  private val Arn = "arn:aws:dynamodb:us-east-1:123456789012:table/scholarchain-prod-metrics"
  private val Host = "db-prod-7.cluster-ro-q1w2e3.us-east-1.neptune.amazonaws.com"
  private val Account = "123456789012"
  private val Forbidden = List(Bucket, "s3://", "arn:aws", Host, Account)

  private def leaks(text: String): List[String] = Forbidden.filter(text.contains)

  /** A sync answer of the fixture world (decoded). */
  private def answer(h: Harness): (Resp, CompletedResult) = {
    val r = post(h, queryBody())
    assertEquals(r.status, 200, r.body)
    (r, upickle.default.read[CompletedResult](r.body))
  }

  /** A stored item of `res` re-stored under a new evidence ID with the text's S3 URI in its
    * provenance (the shape rag wrote for semantic context before Phase 11), in its own bundle.
    */
  private def storeOldShapeBundle(h: Harness, res: CompletedResult): (EvidenceItem, EvidenceId) = {
    val base = res.answer.evidence.get.find(_.window.nonEmpty).get
    val subjects = base.subjectIds :+ "shape=pre-phase-11"
    val old = base.copy(
      evidenceId = EvidenceId.of(base.evidenceType, base.provenance.runId, subjects),
      subjectIds = subjects,
      provenance = base.provenance.copy(sourceRecordIds = List("vector-key-1", S3Uri))
    )
    val stored = h.runtime.stores.evidence.getBundle(res.answer.bundleId.get).toOption.flatten.get
    val bundleId = EvidenceId.forBundle(stored.provenance.runId, List(old.evidenceId))
    val bundle = EvidenceBundle(1, bundleId, List(old), stored.provenance)
    h.runtime.stores.evidence.putBundle(bundle).fold(e => fail(e), identity)
    (old, bundleId)
  }

  test("C-03 GET /v1/evidence of an item stored with an s3:// source reference hides it") {
    val h = harness()
    try {
      val (_, res) = answer(h)
      val (old, _) = storeOldShapeBundle(h, res)
      val r = get(h, s"/v1/evidence/${old.evidenceId.value}")
      assertEquals(r.status, 200, r.body)
      assertEquals(leaks(r.body), Nil, r.body)
      assertEquals(
        r.json("provenance")("sourceRecordIds").arr.map(_.str).toList,
        List("vector-key-1")
      )
      assertEquals(r.json("sourceUris").arr.map(_.str).toList, List("scholarchain:vector-key-1"))
    } finally h.close()
  }

  test("C-02, C-03 a result stored earlier with store text and an s3:// bundle item is guarded") {
    val h = harness()
    try {
      val (_, res) = answer(h)
      val (_, bundleId) = storeOldShapeBundle(h, res)
      // a result as an earlier build stored it: store text in the notes, the S3 URI in provenance
      val oldResult = res.copy(answer =
        res.answer.copy(
          bundleId = Some(bundleId),
          evidence = None,
          answerText = s"No answer: store failure (permanent): DynamoDbException: $Arn",
          identityNotes = List(s"Name lookup failed: connect to $Host"),
          abstentionReason = Some(s"metrics read failed for account $Account"),
          provenance = res.answer.provenance
            .copy(sourceRecordIds = res.answer.provenance.sourceRecordIds :+ S3Uri)
        )
      )
      val now = h.clock.millis()
      val qid = RunId(res.queryId)
      val req = JobRequest("q", None, None, includeEvidence = true)
      val created = h.stores.jobs
        .create(
          NewJob("job-old-shape", JobMode.Async, qid, req, "0" * 31 + "7", now, now / 1000 + 3600)
        )
        .toOption
        .get
        .asInstanceOf[JobChange.Written]
        .job
      val running =
        h.stores.jobs
          .claim(created, now, now + 1000)
          .toOption
          .get
          .asInstanceOf[JobChange.Written]
          .job
      assert(h.stores.jobs.succeed(running, now, Results.stored(oldResult)).isRight)
      val r = get(h, "/v1/jobs/job-old-shape/result")
      assertEquals(r.status, 200, r.body)
      assertEquals(leaks(r.body), Nil, r.body.take(2000))
      assertEquals(r.json("answer")("answerText").str, ResponseGuard.WithheldAnswerText)
      assertEquals(
        r.json("answer")("identityNotes").arr.map(_.str).toList,
        List(ResponseGuard.WithheldNote)
      )
      assertEquals(r.json("answer")("abstentionReason").str, ResponseGuard.WithheldNote)
      assertEquals(
        r.json("answer")("evidence")(0)("provenance")("sourceRecordIds").arr.map(_.str).toList,
        List("vector-key-1")
      )
    } finally h.close()
  }

  test("C-02, C-03 a fresh workflow response with store text is guarded before it is returned") {
    val h = harness()
    try {
      val wr = h.runtime.workflow
        .run(RuntimeServiceFixtures.answeredRequest)
        .fold(e => fail(e), identity)
      val clean = Results.completed(wr.queryRunId, wr.response, includeEvidence = true, Nil)
      // a normal answer passes unchanged
      assertEquals(
        clean.answer,
        AnswerView(
          wr.response.schemaVersion,
          wr.response.question,
          wr.response.answerText,
          wr.response.modelId,
          wr.response.plan,
          wr.response.bundle.map(_.bundleId),
          wr.response.claims,
          wr.response.identityNotes,
          wr.response.abstained,
          wr.response.abstentionReason,
          wr.response.provenance,
          wr.response.bundle.map(_.items)
        )
      )
      val items = wr.response.bundle.get.items
      val dirty = wr.response.copy(
        answerText = s"No answer: semantic context unavailable: $S3Uri",
        identityNotes = List(s"lookup failed at $Host"),
        abstentionReason = Some(s"denied: $Arn"),
        claims = wr.response.claims.map(_.copy(uncertainty = Some(s"account $Account"))),
        bundle = wr.response.bundle.map(
          _.copy(items =
            items.map(i =>
              i.copy(provenance =
                i.provenance.copy(sourceRecordIds = i.provenance.sourceRecordIds :+ S3Uri)
              )
            )
          )
        )
      )
      val guarded = Results.completed(wr.queryRunId, dirty, includeEvidence = true, Nil)
      assertEquals(leaks(Json.encode(guarded)), Nil)
      assertEquals(leaks(Results.stored(guarded)), Nil)
      assertEquals(guarded.answer.evidence.get.map(_.provenance), items.map(_.provenance))
      assert(guarded.answer.claims.forall(_.uncertainty.contains(ResponseGuard.WithheldNote)))
    } finally h.close()
  }

  test("C-03 URI detection: only <scheme>:// references are withheld") {
    assert(ResponseGuard.isUri(S3Uri))
    assert(ResponseGuard.isUri("https://example.org/x"))
    List(
      "conf/acl/A16",
      "EntityLinkCache:name#carol king/researcher#researcher:v1:61ad71f7aee0afa1185ca185ab9e3042",
      "run:v1:ce890bd70723da010b9dffac391c0f97",
      "doi:10.1/x",
      "semantic-context/vector-key"
    ).foreach(r => assert(!ResponseGuard.isUri(r), r))
    // a hex stable ID with a 12-digit run is not an account number
    assert(!ResponseGuard.hasDownstreamText("researcher:v1:ab123456789012cd0000000000000000"))
    assert(ResponseGuard.hasDownstreamText(s"account $Account."))
  }
}
