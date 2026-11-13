package org.scholarchain.core

import scala.io.Source

import upickle.default.ReadWriter

/** Frozen JSON wire examples (`src/test/resources/wire/v1/<Record>.json`).
  *
  * Each file is the frozen v1 wire form of the matching [[Fixtures]] value. A change that alters
  * any frozen file is a wire-contract change: a breaking one bumps `SchemaVersions` and adds new
  * frozen files instead of editing these. Runs under Scala 3 and Scala 2.13.
  */
object WireContractSuite {
  final case class Example[A](name: String, value: A)(implicit
      val rw: ReadWriter[A],
      val validator: Validator[A]
  )
}

class WireContractSuite extends munit.FunSuite {
  import WireContractSuite.Example

  val examples: List[Example[_]] = {
    import Fixtures._
    List(
      Example("Provenance", provenance),
      Example("WorkRecord", workRecord),
      Example("AuthorMention", authorMention),
      Example("ExternalIdLink", externalIdLink),
      Example("CanonicalResearcher", canonicalResearcher),
      Example("AuthorshipRecord", authorshipRecord),
      Example("PublicationTeam", publicationTeam),
      Example("CoauthorPairCandidate", coauthorPairCandidate),
      Example("CoauthorEdge", coauthorEdge),
      Example("ResearcherMetrics", researcherMetrics),
      Example("Venue", venue),
      Example("Institution", institution),
      Example("Topic", topic),
      Example("MatchDecision", matchDecision),
      Example("GraphNode", graphNode),
      Example("GraphEdge", graphEdge),
      Example("EvidenceItem", evidenceItem),
      Example("EvidenceBundle", evidenceBundle),
      Example("QueryPlan", queryPlan),
      Example("AnswerClaim", answerClaim),
      Example("AnswerResponse", answerResponse)
    )
  }

  def frozen(name: String): String = {
    val stream = getClass.getResourceAsStream(s"/wire/v1/$name.json")
    assert(stream != null, s"missing frozen wire example wire/v1/$name.json")
    val src = Source.fromInputStream(stream, "UTF-8")
    try src.mkString
    finally src.close()
  }

  def decode[A](e: Example[A], json: String): Either[ContractError, A] =
    Contract.decode[A](json)(e.rw, e.validator)

  def encode[A](e: Example[A]): String =
    Contract.encode[A](e.value)(e.rw, e.validator).fold(err => fail(err.message), identity)

  def mutate(json: String)(f: ujson.Value => Unit): String = {
    val tree = ujson.read(json)
    f(tree)
    tree.render()
  }

  def hasError(result: Either[ContractError, _], fragment: String): Boolean =
    result.left.exists(_.messages.exists(_.contains(fragment)))

  test("every schema in SchemaVersions except the config has a frozen wire example") {
    assertEquals(
      examples.map(_.name).toSet,
      SchemaVersions.current.keySet - "ScholarChainConfig"
    )
  }

  examples.foreach(e => register(e))

  def register[A](e: Example[A]): Unit = {
    test(s"${e.name}: the fixture is valid") {
      assertEquals(Contract.validate(e.value)(e.validator), Nil)
    }

    test(s"${e.name}: frozen JSON decodes to the fixture") {
      assertEquals(decode(e, frozen(e.name)), Right(e.value))
    }

    test(s"${e.name}: encoding the fixture reproduces the frozen JSON") {
      assertEquals(ujson.read(encode(e)), ujson.read(frozen(e.name)))
    }

    test(s"${e.name}: decode(encode(x)) == x") {
      assertEquals(decode(e, encode(e)), Right(e.value))
    }

    test(s"${e.name}: an unsupported schemaVersion is rejected") {
      val json = mutate(frozen(e.name))(_("schemaVersion") = ujson.Num(2))
      assert(hasError(decode(e, json), "schemaVersion: unsupported version 2"), decode(e, json))
    }

    test(s"${e.name}: a missing schemaVersion is rejected") {
      val json = mutate(frozen(e.name))(_.obj.remove("schemaVersion"))
      assert(decode(e, json).isLeft)
    }

    if (e.name != "Provenance") {
      test(s"${e.name}: missing provenance is rejected") {
        val json = mutate(frozen(e.name))(_.obj.remove("provenance"))
        assert(hasError(decode(e, json), "provenance"), decode(e, json))
      }

      test(s"${e.name}: null provenance is rejected") {
        val json = mutate(frozen(e.name))(_("provenance") = ujson.Null)
        assert(hasError(decode(e, json), "provenance: missing (null)"), decode(e, json))
      }

      test(s"${e.name}: an unsupported provenance schemaVersion is rejected") {
        val json = mutate(frozen(e.name))(_("provenance")("schemaVersion") = ujson.Num(99))
        assert(
          hasError(decode(e, json), "provenance.schemaVersion: unsupported version 99"),
          decode(e, json)
        )
      }

      test(s"${e.name}: provenance without source record IDs is rejected") {
        val json = mutate(frozen(e.name))(_("provenance")("sourceRecordIds") = ujson.Arr())
        assert(hasError(decode(e, json), "provenance.sourceRecordIds: must not be empty"))
      }
    }
  }

  test("an unknown enum wire name is rejected") {
    val json = mutate(frozen("Provenance"))(_("sourceType") = ujson.Str("wikipedia"))
    val result = Contract.decode[Provenance](json)
    assert(hasError(result, "unknown source type 'wikipedia'"), result)
  }

  test("a malformed stable ID on the wire is rejected") {
    val json = mutate(frozen("WorkRecord"))(_("workId") = ujson.Str("work:v1:NOT-HEX"))
    val result = Contract.decode[WorkRecord](json)
    assert(hasError(result, "malformed work ID 'work:v1:NOT-HEX'"), result)
  }

  test("a stable ID of the wrong kind on the wire is rejected") {
    val json = mutate(frozen("WorkRecord"))(_("workId") = ujson.Str(Fixtures.sousa.value))
    assert(Contract.decode[WorkRecord](json).isLeft)
  }

  test("a JSON null in a required string field is rejected") {
    val json = mutate(frozen("WorkRecord"))(_("title") = ujson.Null)
    val result = Contract.decode[WorkRecord](json)
    assert(hasError(result, "title: missing (null)"), result)
  }

  test("an absent Option field is rejected (Options are written as null, never omitted)") {
    val json = mutate(frozen("WorkRecord"))(_.obj.remove("doi"))
    assert(Contract.decode[WorkRecord](json).isLeft)
  }

  test("None is written as JSON null") {
    assertEquals(ujson.read(frozen("Provenance"))("contentHash"), ujson.Null)
  }

  test("dblp-sourced frozen records carry no identifiers from another source") {
    List("WorkRecord", "AuthorMention", "Venue").foreach { name =>
      val json = ujson.read(frozen(name))
      assertEquals(json("provenance")("sourceType"), ujson.Str("dblp"), name)
      assert(!json.obj.keys.exists(_.toLowerCase.contains("openalex")), name)
    }
  }

  test("every frozen researcher external ID names its source dataset and snapshot") {
    val ids = ujson.read(frozen("CanonicalResearcher"))("externalIds").arr
    assert(ids.nonEmpty)
    ids.foreach { x =>
      assert(x("datasetId").str.nonEmpty && x("snapshotId").str.nonEmpty, x)
      assert(x("sourceRecordId").str.nonEmpty, x)
    }
  }
}
