package org.scholarchain.core

import scala.io.Source

import upickle.default.ReadWriter

/** Frozen JSON wire examples (`src/test/resources/wire/v<N>/<Record>.json`).
  *
  * Each record's example lives under the directory of its current schema version
  * (`SchemaVersions.currentOf(name)`) and is the frozen wire form of the matching [[Fixtures]]
  * value. A change that alters any frozen file is a wire-contract change: a breaking one bumps
  * `SchemaVersions` and adds a new frozen file under `wire/v<N+1>/` instead of editing the old one,
  * which stays as the record of the superseded version (see `superseded`). Runs under Scala 3 and
  * Scala 2.13.
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
      Example("MatchCandidate", matchCandidate),
      Example("LlmAdjudication", llmAdjudication),
      Example("GraphNode", graphNode),
      Example("GraphEdge", graphEdge),
      Example("EvidenceItem", evidenceItem),
      Example("EvidenceBundle", evidenceBundle),
      Example("QueryPlan", queryPlan),
      Example("AnswerClaim", answerClaim),
      Example("AnswerResponse", answerResponse)
    )
  }

  /** Frozen example of `name` at `version` (default: the current version). */
  def frozen(name: String, version: Int): String = {
    val path = s"/wire/v$version/$name.json"
    val stream = getClass.getResourceAsStream(path)
    assert(stream != null, s"missing frozen wire example $path")
    val src = Source.fromInputStream(stream, "UTF-8")
    try src.mkString
    finally src.close()
  }

  def frozen(name: String): String = frozen(name, SchemaVersions.currentOf(name))

  /** Superseded frozen examples kept on disk: (schema, superseded version). */
  val superseded: List[(String, Int)] =
    List("WorkRecord" -> 1, "CoauthorPairCandidate" -> 1, "MatchDecision" -> 1)

  /** Additional frozen examples of current schemas: (file name, schema, fixture). */
  val variants: List[(String, String, MatchDecision)] = List(
    ("MatchDecision.identity-assignment", "MatchDecision", Fixtures.identityAssignment),
    ("MatchDecision.llm", "MatchDecision", Fixtures.llmDecision)
  )

  variants.foreach { case (file, schema, value) =>
    test(s"$file: frozen JSON and the fixture agree in both directions") {
      val json = frozen(file, SchemaVersions.currentOf(schema))
      assertEquals(Contract.decode[MatchDecision](json), Right(value))
      val encoded = Contract.encode(value).fold(e => fail(e.message), identity)
      assertEquals(ujson.read(encoded), ujson.read(json))
    }
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
      val next = SchemaVersions.currentOf(e.name) + 1
      val json = mutate(frozen(e.name))(_("schemaVersion") = ujson.Num(next.toDouble))
      assert(
        hasError(decode(e, json), s"schemaVersion: unsupported version $next"),
        decode(e, json)
      )
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

  test("schema versions bumped by the Phase 5 contract change") {
    assertEquals(SchemaVersions.currentOf("MatchDecision"), 2)
    assertEquals(SchemaVersions.currentOf("LlmAdjudication"), 1)
  }

  test("MatchDecision v2 carries every entity-linkage decision field, in a frozen order") {
    assertEquals(
      ujson.read(frozen("MatchDecision")).obj.keys.toList,
      List(
        "schemaVersion",
        "subjectKind",
        "left",
        "right",
        "candidateId",
        "outcome",
        "matchPath",
        "confidence",
        "features",
        "rulesFired",
        "reasonCodes",
        "modelVersion",
        "promptVersion",
        "policyVersion",
        "decisionTime",
        "provenance"
      )
    )
    assertEquals(
      ujson.read(frozen("MatchDecision"))("provenance")("runId").str,
      Fixtures.erRun.value
    )
  }

  test("MatchDecision v2: the identity assignment has no candidate and names its rule") {
    val json = ujson.read(frozen("MatchDecision.identity-assignment", 2))
    assertEquals(json("subjectKind"), ujson.Str("mention_researcher"))
    assertEquals(json("candidateId"), ujson.Null)
    assertEquals(json("outcome"), ujson.Str("accepted"))
    assertEquals(json("rulesFired"), ujson.Arr("fixture-dblp-prior"))
  }

  test("superseded MatchDecision v1 fails on its removed and new fields, then on the version") {
    val v1 = frozen("MatchDecision", 1)
    assert(hasError(Contract.decode[MatchDecision](v1), "decode:"))
    // v1 with the v2 fields bolted on still fails: its version and outcome name are retired.
    val patched = mutate(frozen("MatchDecision")) { j =>
      j("schemaVersion") = ujson.Num(1)
    }
    assert(hasError(Contract.decode[MatchDecision](patched), "unsupported version 1"))
    val oldOutcome = mutate(frozen("MatchDecision"))(_("outcome") = ujson.Str("match"))
    assert(hasError(Contract.decode[MatchDecision](oldOutcome), "unknown match outcome 'match'"))
  }

  test("LlmAdjudication: the chosen candidate is one of the offered ones on the wire") {
    val json = ujson.read(frozen("LlmAdjudication"))
    assert(json("offeredCandidateIds").arr.contains(json("chosenCandidateId")))
    assertEquals(json("provenance")("contentHash"), json("inputHash"))
    val forbidden = Set("reasoning", "rationale", "explanation", "thoughts")
    assertEquals(json.obj.keys.filter(k => forbidden.contains(k.toLowerCase)).toList, Nil)
  }

  test("LlmAdjudication: a fabricated chosen candidate ID is rejected on decode") {
    val fabricated = MatchCandidateId.of(
      MatchSubjectKind.MentionResearcher,
      Fixtures.candidateLeft,
      Fixtures.edgeLeft,
      "fixture-block-family",
      "invented"
    )
    val json = mutate(frozen("LlmAdjudication"))(
      _("chosenCandidateId") = ujson.Str(fabricated.toOption.get.value)
    )
    val result = Contract.decode[LlmAdjudication](json)
    assert(hasError(result, "is not one of offeredCandidateIds"), result)
  }

  test("schema versions bumped by the Phase 4 contract change") {
    assertEquals(SchemaVersions.currentOf("WorkRecord"), 2)
    assertEquals(SchemaVersions.currentOf("CoauthorPairCandidate"), 2)
    assertEquals(SchemaVersions.currentOf("MatchCandidate"), 1)
  }

  test("superseded v1 frozen examples are no longer accepted by current readers") {
    superseded.foreach { case (name, version) =>
      assert(SchemaVersions.currentOf(name) > version, name)
      val ex = examples.find(_.name == name).get
      assert(decode(ex, frozen(name, version)).isLeft, name)
    }
  }

  test("superseded v1 frozen examples fail on the new required fields, then on the version") {
    val work = mutate(frozen("WorkRecord", 1)) { j =>
      j("eeLinks") = ujson.Arr(Fixtures.eeLink)
      j("urls") = ujson.Arr(Fixtures.tocUrl)
    }
    assert(hasError(Contract.decode[WorkRecord](frozen("WorkRecord", 1)), "decode:"))
    assert(hasError(Contract.decode[WorkRecord](work), "schemaVersion: unsupported version 1"))
  }

  test("WorkRecord v2: eeLinks and urls are JSON arrays in source order") {
    val json = ujson.read(frozen("WorkRecord"))
    assertEquals(json("eeLinks"), ujson.Arr(Fixtures.eeLink))
    assertEquals(json("urls"), ujson.Arr(Fixtures.tocUrl))
  }

  test("CoauthorPairCandidate v2: positions carry both positions and both mention IDs") {
    val p = ujson.read(frozen("CoauthorPairCandidate"))("positions").arr
    assertEquals(p.size, 1)
    assertEquals(
      p(0).obj.keys.toList.sorted,
      List("leftMentionId", "leftPosition", "rightMentionId", "rightPosition")
    )
    assertEquals(p(0)("leftPosition"), ujson.Num(2))
    assertEquals(p(0)("rightPosition"), ujson.Num(0))
  }

  test("MatchCandidate carries features and no accept/reject label") {
    val json = ujson.read(frozen("MatchCandidate"))
    val forbidden = Set("outcome", "label", "decision", "confidence", "match", "accepted")
    assertEquals(json.obj.keys.filter(k => forbidden.contains(k.toLowerCase)).toList, Nil)
    assertEquals(
      json.obj.keys.toList,
      List(
        "schemaVersion",
        "candidateId",
        "subjectKind",
        "left",
        "right",
        "blockFamily",
        "blockKey",
        "features",
        "provenance"
      )
    )
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
