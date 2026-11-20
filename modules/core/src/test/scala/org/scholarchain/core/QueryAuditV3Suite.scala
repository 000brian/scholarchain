package org.scholarchain.core

import scala.io.Source

/** QueryAudit schema version 3 (Phase 15, additive; decision 7.5): `abstentionCodes`, the fixed
  * codes of why an answer abstained. Codes only, never prose. Versions 1 and 2 still read (absent
  * field -> empty). Runs under Scala 3 and Scala 2.13.
  */
class QueryAuditV3Suite extends munit.FunSuite {
  import Fixtures._

  def frozen(version: Int, variant: String = ""): String = {
    val path = s"/wire/v$version/QueryAudit$variant.json"
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

  def rejected(a: QueryAudit, fragment: String)(implicit loc: munit.Location): Unit = {
    val e = Contract.validate(a)
    assert(e.exists(_.contains(fragment)), s"expected '$fragment' in $e")
  }

  val V2Keys: List[String] = ujson.read(frozen(2)).obj.keys.toList

  test("v3 is the current version; versions 1 and 2 stay readable") {
    assertEquals(SchemaVersions.currentOf("QueryAudit"), 3)
    assertEquals(SchemaVersions.supported("QueryAudit"), Set(1, 2, 3))
    assertEquals(QueryAudit.V3Fields, List("abstentionCodes"))
  }

  test("v3 wire: the v2 fields in v2 order, then abstentionCodes") {
    assertEquals(ujson.read(frozen(3)).obj.keys.toList, V2Keys ++ QueryAudit.V3Fields)
    assertEquals(ujson.read(frozen(3, ".abstained")).obj.keys.toList, V2Keys :+ "abstentionCodes")
    assertEquals(encoded(queryAuditV3).obj.keys.toList, V2Keys :+ "abstentionCodes")
    // not abstained: written as [], never omitted
    assertEquals(encoded(queryAuditV3)("abstentionCodes"), ujson.Arr())
  }

  test("round trip with codes: the abstained v3 frozen example and its fixture agree") {
    val json = frozen(3, ".abstained")
    assertEquals(Contract.decode[QueryAudit](json), Right(queryAuditV3Abstained))
    assertEquals(encoded(queryAuditV3Abstained), ujson.read(json))
    assertEquals(
      ujson.read(json)("abstentionCodes"),
      ujson.Arr("no-path-within-depth", "path-query-timeout")
    )
    assertEquals(Contract.decode[QueryAudit](frozen(3)), Right(queryAuditV3))
  }

  test("an old stored audit without the field reads: v2 and v1 decode with no codes") {
    val v2 = Contract.decode[QueryAudit](frozen(2))
    assertEquals(v2, Right(queryAuditV2))
    assertEquals(v2.toOption.get.abstentionCodes, Nil)
    val v1 = Contract.decode[QueryAudit](frozen(1))
    assertEquals(v1, Right(queryAudit))
    assertEquals(v1.toOption.get.abstentionCodes, Nil)
    // an old abstained v2 audit (stored before Phase 15) still reads, with no codes
    val oldAbstained = mutate(frozen(2))(_("abstained") = ujson.Bool(true))
    val r = Contract.decode[QueryAudit](oldAbstained)
    assert(r.isRight, r)
    assertEquals(r.toOption.get.abstentionCodes, Nil)
    // and re-encodes to its own version, without the v3 key
    assertEquals(encoded(queryAuditV2).obj.keys.toList, V2Keys)
  }

  test("a v3 record missing abstentionCodes on the wire is rejected") {
    val json = mutate(frozen(3))(_.obj.remove("abstentionCodes"))
    val r = Contract.decode[QueryAudit](json)
    assert(
      errors(r).exists(_.contains("abstentionCodes: required in QueryAudit schema version 3")),
      r
    )
  }

  test("a v1 or v2 record carrying abstentionCodes is rejected, on the wire and in the validator") {
    List(1, 2).foreach { v =>
      val json = mutate(frozen(v)) { j =>
        j("abstained") = ujson.Bool(true)
        j("abstentionCodes") = ujson.Arr("no-path-within-depth")
      }
      val r = Contract.decode[QueryAudit](json)
      assert(
        errors(r).exists(
          _.contains(s"abstentionCodes: not a field of QueryAudit schema version $v")
        ),
        s"v$v: $r"
      )
    }
    rejected(
      queryAuditV2.copy(abstained = true, abstentionCodes = List("no-path-within-depth")),
      "schemaVersion: version 2 carries no abstentionCodes"
    )
  }

  test("refusal of a non-code value: prose, case, spacing, length and count are rejected") {
    def bad(code: String)(implicit loc: munit.Location): Unit =
      rejected(queryAuditV3Abstained.copy(abstentionCodes = List(code)), "abstentionCodes[0]")
    bad("No path was found within depth 3, so I abstained.")
    bad("no path within depth")
    bad("No-Path")
    bad("no_path")
    bad("-no-path")
    bad("no-path-")
    bad("no--path")
    bad("3-hops")
    bad("")
    bad("a" * (QueryAudit.MaxAbstentionCodeLength + 1))
    // the longest allowed code passes
    assertEquals(
      Contract.validate(
        queryAuditV3Abstained.copy(abstentionCodes = List("a" * QueryAudit.MaxAbstentionCodeLength))
      ),
      Nil
    )
    rejected(
      queryAuditV3Abstained.copy(abstentionCodes =
        List.tabulate(QueryAudit.MaxAbstentionCodes + 1)(i => s"code-$i")
      ),
      s"abstentionCodes: at most ${QueryAudit.MaxAbstentionCodes} codes"
    )
    rejected(
      queryAuditV3Abstained.copy(abstentionCodes =
        List("graph-store-failure", "graph-store-failure")
      ),
      "abstentionCodes: contains duplicates"
    )
    // a non-code value is refused on decode too
    val json = mutate(frozen(3, ".abstained"))(
      _("abstentionCodes") = ujson.Arr("The graph had no path; I could not answer.")
    )
    assert(errors(Contract.decode[QueryAudit](json)).exists(_.contains("abstentionCodes[0]")))
  }

  test("v3: codes are present exactly when the answer abstained") {
    rejected(
      queryAuditV3.copy(abstentionCodes = List("researcher-not-resolved")),
      "abstentionCodes: must be empty when abstained is false"
    )
    rejected(
      queryAuditV3Abstained.copy(abstentionCodes = Nil),
      "abstentionCodes: at least one code is required when abstained is true"
    )
    List(
      "no-path-within-depth",
      "path-query-timeout",
      "graph-store-failure",
      "researcher-not-resolved"
    ).foreach { c =>
      assertEquals(Contract.validate(queryAuditV3Abstained.copy(abstentionCodes = List(c))), Nil, c)
    }
  }
}
