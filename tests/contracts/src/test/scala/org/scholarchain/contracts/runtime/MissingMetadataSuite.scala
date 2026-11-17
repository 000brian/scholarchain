package org.scholarchain.contracts.runtime

import org.scholarchain.rag.runtime.RuntimeServiceFixtures
import org.scholarchain.services.ServiceTestKit.*

/** Category 10 (missing topic, institution, abstract or profile metadata) and 9b (common-name
  * researcher resolution) over HTTP.
  */
class MissingMetadataSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  test("an ambiguous common name over HTTP abstains and names both candidates") {
    val h = harness()
    try {
      val r = post(h, queryBody(RuntimeServiceFixtures.AbstainedQuestion))
      assertEquals(r.status, 200, r.body.take(300))
      val a = r.json("answer")
      assertEquals(a("abstained").bool, true)
      val text = a("answerText").str
      assert(text.contains("Wei Wang 0001") && text.contains("Wei Wang 0002"), text)
      assert(a("claims").arr.forall(_("status").str != "verified"), a("claims").render())
    } finally h.close()
  }

  test("an institution constraint is refused 422, never silently ignored") {
    val h = harness()
    try {
      val body = queryBody(
        extra = List(
          "constraints" -> ujson.Obj(
            "yearFrom" -> 2015,
            "yearTo" -> 2025,
            "institutions" -> ujson.Arr("Example University")
          )
        )
      )
      val r = post(h, body)
      assertEquals(r.status, 422, r.body.take(300))
      assertEquals(r.error, "UNSUPPORTED_CONSTRAINT")
    } finally h.close()
  }

  test("a topic no profile matches gives no verified ranking claim built on semantic text") {
    val h = harness()
    try {
      val r = post(
        h,
        queryBody(
          "Which researchers working on quantum chromodynamics lattice gauge theory have the most distinct coauthors in 2015-2025?"
        )
      )
      assertEquals(r.status, 200, r.body.take(300))
      val a = r.json("answer")
      val claims = a("claims").arr
      // a claim may rank by a graph metric; none may cite only semantic context and be verified
      val ev = a("evidence").arr.map(i => i("evidenceId").str -> i("evidenceType").str).toMap
      assert(
        claims.forall(c =>
          c("status").str != "verified" ||
            c("evidenceIds").arr.map(_.str).exists(id => ev.get(id).exists(_ != "semantic_context"))
        ),
        claims.map(_.render()).mkString("\n")
      )
    } finally h.close()
  }
}
