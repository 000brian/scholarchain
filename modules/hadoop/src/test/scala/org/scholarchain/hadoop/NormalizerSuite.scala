package org.scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scholarchain.core.{Contract, RunId}
import org.scholarchain.hadoop.common.RunContext
import org.scholarchain.hadoop.sources.SourceNormalizers.*
import org.scholarchain.hadoop.sources.{Normalized, SourceNormalizers}

class NormalizerSuite extends munit.FunSuite {

  private def run(ds: String) = RunContext(
    RunId.of("j", "c", "i", "f").toOption.get,
    "hadoop.test",
    ds,
    "2026-11-12",
    "source-normalize/1"
  )

  private def sampleLines(rel: String): List[String] =
    new String(
      Files.readAllBytes(TestSupport.repoRoot.resolve("datasets/samples").resolve(rel)),
      StandardCharsets.UTF_8
    ).split('\n').toList.filter(_.nonEmpty)

  private def norm(kind: String, line: String, keep: Boolean = false) =
    SourceNormalizers.normalize(kind, line, run(sourceTypeOf(kind).wire), keep)

  private def ok(kind: String, line: String): Normalized =
    norm(kind, line).fold(r => fail(r.toString), identity)

  test("OpenAlex works: canonical IDs, every authorship and source ID preserved") {
    val all = sampleLines("openalex/works.jsonl").map(ok(OpenAlexWork, _))
    assertEquals(all.size, 4)
    val viquae = all.find(_.record.dois == List("10.1145/3477495.3531753")).get.record
    assertEquals(viquae.openAlexWorkId, Some("W4224919571"))
    assertEquals(viquae.openAlexSourceId, Some("S4363608773"))
    assertEquals(viquae.authorships.size, 7)
    assertEquals(viquae.authorships.count(_.orcid.nonEmpty), 7)
    assertEquals(viquae.authorships.head.openAlexAuthorId, Some("A5009712411"))
    assertEquals(
      viquae.authorships.head.institutionIds,
      List("I1294671590", "I277688954", "I4387152856")
    )
    assertEquals(
      viquae.authorships.head.affiliations.head,
      "Université Paris-Saclay, CNRS, LISN, Orsay, France"
    )
    assertEquals(viquae.year, Some(2022))
    // W4384652670: one authorship has an ORCID but no OpenAlex author ID (dataset notes)
    val fid = all.find(_.record.openAlexWorkId.contains("W4384652670")).get.record
    assert(fid.authorships.exists(a => a.orcid.nonEmpty && a.openAlexAuthorId.isEmpty))
    all.flatMap(_.institutions).foreach(i => assertEquals(Contract.validate(i), Nil))
    assert(all.flatMap(_.institutions).nonEmpty)
  }

  test("OpenAlex authors, Crossref works and ORCID records normalize with source IDs") {
    val authors = sampleLines("openalex/authors.jsonl").map(ok(OpenAlexAuthor, _))
    assertEquals(
      authors.flatMap(_.record.openAlexAuthorId).sorted,
      List("A5029214477", "A5080716895", "A5089440969")
    )
    assert(authors.forall(_.record.orcid.nonEmpty))
    val crossref = sampleLines("crossref/works.jsonl").map(ok(CrossrefWork, _))
    assertEquals(
      crossref.map(_.record.sourceRecordId).sorted,
      List(
        "10.1145/3477495.3531753",
        "10.1145/3539618.3591687",
        "10.1145/3539618.3591892",
        "10.1145/3539618.3592048"
      )
    )
    val lerner = crossref.find(_.record.sourceRecordId == "10.1145/3477495.3531753").get.record
    assertEquals(lerner.authorships.map(_.orcid.isEmpty).distinct, List(true))
    assertEquals(lerner.authorships.last.rawName, Some("Jesús Lovón Melgarejo"))
    val orcid = sampleLines("orcid/records.jsonl").map(ok(OrcidRecord, _))
    assertEquals(orcid.head.record.orcid, Some("0000-0003-3226-9189"))
    assertEquals(
      orcid.head.record.dois,
      List("10.1145/3539618.3591892", "10.48550/arxiv.2301.04643")
    )
    assertEquals(orcid.head.record.names, List("Hugo Sousa"))
  }

  test("malformed, keyless, invalid-key and wrong-entity records are rejected with reasons") {
    assertEquals(norm(OpenAlexWork, "{not json").left.map(_.reason), Left("malformed_json"))
    assertEquals(norm(OpenAlexWork, "[1,2]").left.map(_.reason), Left("not_a_json_object"))
    assertEquals(
      norm(OpenAlexWork, """{"doi": "10.1/x"}""").left.map(_.reason),
      Left("missing_key")
    )
    assertEquals(
      norm(OpenAlexWork, """{"id": "https://openalex.org/A123"}""").left.map(_.reason),
      Left("unsupported_record")
    )
    assertEquals(norm(OpenAlexWork, """{"id": "W0"}""").left.map(_.reason), Left("invalid_key"))
    assertEquals(
      norm(CrossrefWork, """{"DOI": "not-a-doi"}""").left.map(_.reason),
      Left("invalid_key")
    )
    assertEquals(
      norm(OrcidRecord, """{"orcid-path": "0000-0000-0000-0000"}""").left.map(_.reason),
      Left("invalid_key")
    )
    assertEquals(
      norm(OpenAlexWork, """{"id": "W1", "authorships": 5}""").left.map(_.reason),
      Left("schema_failure")
    )
  }

  test("invalid optional identifiers are dropped and reported, never repaired") {
    val n = ok(
      OpenAlexWork,
      """{"id":"W1","doi":"https://doi.org/xx","authorships":[{"author":{"id":"bad","orcid":"0000-0000-0000-0000"},"institutions":[{"id":"I5","display_name":"X","country_code":"Portugal"}]}]}"""
    )
    assertEquals(n.record.dois, Nil)
    assertEquals(n.record.authorships.head.orcid, None)
    assertEquals(n.record.authorships.head.openAlexAuthorId, None)
    assertEquals(n.institutions.map(_.countryCode), List(None))
    assertEquals(
      n.issues.map(_.reason).sorted,
      List("invalid_country_code", "invalid_doi", "invalid_openalex_id", "invalid_orcid")
    )
  }

  test("abstracts and topics are preserved when present; Crossref abstracts follow the policy") {
    val n = ok(
      OpenAlexWork,
      """{"id":"W2","abstract_inverted_index":{"graphs":[2],"Temporal":[0],"coauthor":[1]},"topics":[{"id":"https://openalex.org/T10181","display_name":"Graphs"}]}"""
    )
    assertEquals(n.record.abstractText, Some("Temporal coauthor graphs"))
    assert(n.record.abstractHash.exists(_.startsWith("sha256:")))
    assertEquals(n.topics.map(_.topicId), List("T10181"))
    assertEquals(n.record.topicIds, List("T10181"))
    val line = """{"DOI":"10.1/A","abstract":"<jats:p>Text</jats:p>"}"""
    val dropped = ok(CrossrefWork, line)
    assertEquals(dropped.record.abstractText, None)
    assertEquals(dropped.issues.map(_.reason), List("abstract_dropped_by_policy"))
    assertEquals(
      norm(CrossrefWork, line, keep = true).toOption.get.record.abstractText,
      Some("<jats:p>Text</jats:p>")
    )
  }

  test("topic keys and entity recognition use the core OpenAlexId rules (Topic, entities)") {
    val raw = List(
      "https://openalex.org/T10181",
      "HTTPS://OPENALEX.ORG/t42",
      " t7 ",
      "T0",
      "T01",
      "W5",
      "https://openalex.org/"
    )
    raw.foreach { r =>
      val expected = org.scholarchain.core.OpenAlexId.Topic.normalize(r).toList
      val named = ujson.Obj("id" -> r, "display_name" -> "Graphs")
      val n = ok(OpenAlexWork, ujson.Obj("id" -> "W3", "topics" -> ujson.Arr(named)).render())
      assertEquals(n.record.topicIds, expected, r)
      assertEquals(n.topics.map(_.topicId), expected, r)
      val bad = if (expected.isEmpty) List("invalid_openalex_id") else Nil
      assertEquals(n.issues.map(_.reason), bad, r)
      // a valid topic without a display name keeps its ID and is reported, not dropped
      val unnamed = ujson.Obj("id" -> "W3", "topics" -> ujson.Arr(ujson.Obj("id" -> r))).render()
      val u = ok(OpenAlexWork, unnamed)
      assertEquals(u.record.topicIds, expected, r)
      assertEquals(u.topics, Nil, r)
      val unnamedIssue = if (expected.isEmpty) bad else List("topic_without_name")
      assertEquals(u.issues.map(_.reason), unnamedIssue, r)
    }
    // every OpenAlex entity key in core (topics included) is recognised as the wrong entity
    org.scholarchain.core.OpenAlexId.entities
      .filterNot(_ == org.scholarchain.core.OpenAlexId.Work)
      .foreach { e =>
        val line = ujson.Obj("id" -> s"https://openalex.org/${e.letter}123").render()
        assertEquals(norm(OpenAlexWork, line).left.map(_.reason), Left("unsupported_record"), e)
      }
  }
}
