package org.scholarchain.rag.eval

import java.nio.file.{Files, Paths}

import org.scholarchain.core.Hashing

/** Phase 15, decision 7.4: the evaluation universe derived from benchmark-v1 (the 3r design: 11
  * questions, 100 labels, 19 not-comparable, exploratory), and the packaged main resource equal to
  * that derivation from the test copy of benchmark-v1.
  */
class RetrievalEvalSetSuite extends munit.FunSuite {

  private lazy val benchmarkBytes =
    Files.readAllBytes(Paths.get(getClass.getResource("/benchmark/benchmark-v1.json").toURI))
  private lazy val derived = RetrievalEvalSet.derive(benchmarkBytes).fold(fail(_), identity)

  test("the universe is the answer-target reading: 11 questions, 100 labels, 19 not-comparable") {
    assertEquals(
      derived.questions.map(q => q.id -> q.targets.size),
      List(
        "q01" -> 10,
        "q02" -> 5,
        "q03" -> 10,
        "q07" -> 10,
        "q08" -> 10,
        "q19" -> 10,
        "q20" -> 5,
        "q21" -> 10,
        "q25" -> 10,
        "q26" -> 10,
        "q30" -> 10
      )
    )
    assertEquals(derived.labelCount, 100)
    assertEquals(derived.notComparable.size, 19)
    assertEquals(derived.questions.size + derived.notComparable.size, 30)
    assert(derived.exploratory)
    // named subjects are never targets (q07, q08: the named researcher; q09 has only named ones)
    assert(derived.notComparable.exists(n => n.id == "q09" && n.reason.contains("named subjects")))
    assert(derived.notComparable.exists(n => n.id == "q13" && n.reason.contains("path")))
    // the targets are well-formed researcher IDs, distinct
    derived.questions.foreach(q => assertEquals(q.targets.distinct, q.targets, q.id))
  }

  test("by design weak: vector-only on rankings and edges, graph-only on topic rankings") {
    val weak = derived.questions.map(q => q.id -> q.byDesignWeak).toMap
    assertEquals(weak("q01"), List(RetrievalEvalSet.VectorOnly))
    assertEquals(weak("q07"), List(RetrievalEvalSet.VectorOnly))
    assertEquals(weak("q25"), List(RetrievalEvalSet.GraphOnly))
    assertEquals(weak("q26"), List(RetrievalEvalSet.GraphOnly))
  }

  test("q25 and q26 targets are the top 10 of the labelled pool by the benchmark's own rule") {
    val q26 = derived.questions.find(_.id == "q26").get
    // pool values 1754, 1362, 1212, 1154, 1144, 1084, 1062, 1062 (tie: code-point order), 968, 939
    assertEquals(q26.targets.head, "researcher:v1:c7883d2daae7743663d011b5212b90b6")
    assertEquals(
      q26.targets.slice(6, 8),
      List(
        "researcher:v1:225400deb1281093eb8e9510ec3210a0",
        "researcher:v1:a6b6ee7aac0df91d18ca90ab0a476c88"
      )
    )
    assertEquals(q26.targets.last, "researcher:v1:d97b5c7fa0b28c53d2188a9bd53bf8fc")
    val q25 = derived.questions.find(_.id == "q25").get
    assertEquals(q25.targets.last, "researcher:v1:fd836e0f7348e1a2ff3d0ad3953b966a")
  }

  test("the packaged resource equals the derivation from benchmark-v1 and pins its sha256") {
    assertEquals(derived.benchmarkSha256, Hashing.sha256Hex(benchmarkBytes))
    val packaged =
      RetrievalEvalSet.load().fold(e => fail(s"$e\n${derived.toJson.render(indent = 2)}"), identity)
    assertEquals(packaged, derived, derived.toJson.render(indent = 2))
    assertEquals(packaged.toJson, derived.toJson)
  }

  test("a file that is not a benchmark is refused") {
    assert(RetrievalEvalSet.derive("{}".getBytes).isLeft)
    assert(RetrievalEvalSet.fromJson(ujson.Obj("format" -> "x")).isLeft)
  }
}
