package org.scholarchain.rag.runtime

import java.nio.file.Paths

import org.scholarchain.core._
import org.scholarchain.rag.RagFixtures.NoConfigEnv
import org.scholarchain.rag.benchmark.AnswerKeyGenerator

/** Phase 17 decision 6.2 (monotonic, the corpus part): every claim the fake benchmark produces (all
  * questions of the fixture answer key, fake model, local stores, as `BenchmarkRunner` runs them)
  * and every claim of the golden-example question (`RuntimeServiceFixtures.answeredRequest`, the
  * request behind `services/src/test/resources/api/v1/completed-result.json`) is verified with and
  * without the Phase 17 absence rule ([[VerifierOracle]]): no status rose, no Phase 16 reason was
  * dropped, and the statuses that fell are exactly the listed ones. The verifier suites route their
  * claims through the same oracle.
  */
class VerifierMonotonicSuite extends munit.FunSuite {
  import RuntimeFixtures._

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  /** The claims of this corpus whose status falls, with the clause of decision 6 item 1. */
  private val expectedFalls: Set[(String, String, String)] = Set.empty

  test("monotonic: fake benchmark and golden-example claims; no status rose, falls are listed") {
    val (exportDir, csvDir) = inputs()
    val key = AnswerKeyGenerator.generate(exportDir, 5000)
    val cfg = Paths.get(getClass.getResource("/config/rag-runtime-local-v1.json").toURI)
    val config = BenchmarkRunner.loadConfig(cfg, NoConfigEnv()).fold(e => fail(e), identity)
    val service = RuntimeService.local(config, exportDir, csvDir).fold(e => fail(e), identity)
    try {
      val windows =
        service.context.windows.map(w => w.label -> YearWindow(w.startYear, w.endYear)).toMap
      val requests = key("questions").arr.toList.map(q => QueryRequest(q("question").str)) :+
        RuntimeServiceFixtures.answeredRequest
      val results = requests.map(r => service.workflow.run(r).fold(e => fail(s"$r: $e"), identity))
      val checked = results.flatMap { res =>
        val items =
          res.evidence.bundle.map(_.items).getOrElse(Nil).map(i => i.evidenceId -> i).toMap
        res.verified.map { v =>
          val (now, before) = VerifierOracle.compare(v.claim, items, windows)
          // the workflow's own verdict is the Phase 17 one
          assertEquals(v.status, now.status, v.claim.text)
          (v.claim.text, before.status, now.status)
        }
      }
      assert(checked.size >= requests.size, s"only ${checked.size} claims")
      val absenceClaims = checked.count(c => ClaimVerification.statesAbsence(c._1))
      println(s"[monotonic corpus] claims=${checked.size} absence=$absenceClaims")
      val fell = checked.collect {
        case (t, b, n) if VerifierOracle.rank(n) < VerifierOracle.rank(b) => (t, b.wire, n.wire)
      }.toSet
      assertEquals(fell, expectedFalls)
    } finally service.close()
  }
}
