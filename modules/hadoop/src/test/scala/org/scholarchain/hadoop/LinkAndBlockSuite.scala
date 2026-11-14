package org.scholarchain.hadoop

import org.scholarchain.core.*
import org.scholarchain.hadoop.blocking.BlockingKeys
import org.scholarchain.hadoop.common.RunContext
import org.scholarchain.hadoop.graphexport.GraphExportJob
import org.scholarchain.hadoop.ids.{ExactLinker, IdentifierKeys}
import org.scholarchain.hadoop.sources.{NormalizedAuthorship, NormalizedSource, SourceNormalizers}
import org.scholarchain.hadoop.teams.MentionContext
import upickle.default.write

class LinkAndBlockSuite extends munit.FunSuite {

  private val run = RunContext(
    RunId.of("j", "c", "i", "f").toOption.get,
    "hadoop.j04-exact-id-join",
    "scholarchain-hadoop",
    "label",
    "exact-id/1"
  )
  private val doi = "10.1145/1"
  private val key = "conf/sigir/X1"
  private val workId = WorkId.fromDblpKey(key)
  private val orcidA = "0000-0002-1825-0097"
  private val orcidB = "0000-0003-3226-9189"

  private def ctx(pos: Int, orcid: Option[String], wk: String = key) = {
    val w = WorkId.fromDblpKey(wk)
    MentionContext(
      1,
      MentionId.of(w, pos).value,
      w.value,
      wk,
      pos,
      s"N$pos",
      orcid,
      2023,
      Some("conf/sigir"),
      Some(doi),
      "t",
      2,
      Nil,
      false,
      "dblp",
      "2026-11-01"
    )
  }

  private def auth(pos: Int, orcid: Option[String], a: Option[String], insts: List[String]) =
    NormalizedAuthorship(pos, None, Some(s"N$pos"), None, orcid, a, insts, Nil)

  private def oa(w: String, auths: List[NormalizedAuthorship]) =
    NormalizedSource(
      1,
      SourceNormalizers.OpenAlexWork,
      "openalex",
      "openalex",
      "2026-11-12",
      w,
      List(doi),
      Some(w),
      None,
      Some("S4363608773"),
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      Nil,
      Nil,
      Nil,
      auths,
      "x"
    )

  private def cr(auths: List[NormalizedAuthorship]) =
    NormalizedSource(
      1,
      SourceNormalizers.CrossrefWork,
      "crossref",
      "crossref",
      "2026-11-12",
      doi,
      List(doi),
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      Nil,
      Nil,
      Nil,
      auths,
      "y"
    )

  test("identifier keys are canonical DOIs") {
    assertEquals(IdentifierKeys.keysOf(ctx(0, None)), List("doi\t10.1145/1"))
    assertEquals(
      IdentifierKeys.keysOf(cr(Nil).copy(dois = List("10.2/b", "10.1/a", "10.2/b"))),
      List("doi\t10.1/a", "doi\t10.2/b")
    )
  }

  test("exact linking by DOI and ORCID with source provenance on every link") {
    val out = ExactLinker.link(
      doi,
      List(ctx(0, Some(orcidA)), ctx(1, None)),
      List(
        oa(
          "W9",
          List(auth(0, Some(orcidA), Some("A7"), List("I3", "I4")), auth(1, None, None, Nil))
        ),
        cr(List(auth(0, Some(orcidA), None, Nil)))
      ),
      run
    )
    assertEquals(out.conflicts, Nil)
    val kinds = out.links
      .map(l => (l.subjectKind.wire, l.idType.wire, l.externalId, l.provenance.sourceType.wire))
      .sorted
    assertEquals(
      kinds,
      List(
        ("mention", "openalex_author", "A7", "openalex"),
        ("mention", "openalex_institution", "I3", "openalex"),
        ("mention", "openalex_institution", "I4", "openalex"),
        ("mention", "orcid", orcidA, "crossref"),
        ("mention", "orcid", orcidA, "openalex"),
        ("venue", "openalex_source", "S4363608773", "openalex"),
        ("work", "doi", doi, "crossref"),
        ("work", "doi", doi, "openalex"),
        ("work", "openalex_work", "W9", "openalex")
      )
    )
    out.links.foreach(l => assertEquals(Contract.validate(l), Nil))
    val w = out.links.find(_.idType == ExternalIdType.OpenAlexWork).get
    assertEquals(w.provenance.snapshotId, "2026-11-12")
    assertEquals(w.provenance.sourceRecordIds, List("W9"))
    assertEquals(w.subjectId, workId.value)
  }

  test("ambiguous identifier groups are reported as conflicts and not linked") {
    val twoWorks =
      ExactLinker.link(doi, List(ctx(0, None), ctx(0, None, "conf/sigir/X2")), Nil, run)
    assertEquals(twoWorks.conflicts.map(_.reason), List("doi_maps_to_multiple_dblp_works"))
    assertEquals(twoWorks.links, Nil)
    val twoW = ExactLinker.link(doi, List(ctx(0, None)), List(oa("W1", Nil), oa("W2", Nil)), run)
    assertEquals(twoW.conflicts.map(_.reason), List("doi_maps_to_multiple_openalex_works"))
    val sameOrcid = ExactLinker.link(
      doi,
      List(ctx(0, Some(orcidB)), ctx(1, Some(orcidB))),
      List(oa("W1", List(auth(0, Some(orcidB), Some("A1"), Nil)))),
      run
    )
    assertEquals(sameOrcid.conflicts.map(_.reason), List("orcid_on_multiple_mentions_of_work"))
    assert(!sameOrcid.links.exists(_.subjectKind == LinkSubjectKind.Mention))
    assert(ExactLinker.link(doi, Nil, List(cr(Nil)), run).unmatched)
  }

  test("dblp-base-name blocking key strips only the homonym suffix") {
    assertEquals(
      BlockingKeys.keys("dblp-base-name", ctx(0, None).copy(name = "Ricardo Campos 0001")),
      List("Ricardo Campos")
    )
    assertEquals(
      BlockingKeys.keys("dblp-base-name", ctx(0, None).copy(name = "Wei Wang")),
      List("Wei Wang")
    )
    intercept[IllegalArgumentException](BlockingKeys.keys("initials", ctx(0, None)))
  }

  test("dblp-base-name blocking key is the same for canonically equivalent names") {
    val nfc = "Jos\u00e9 Garc\u00eda"
    val nfd = "Jose\u0301 Garci\u0301a"
    def k(n: String) = BlockingKeys.keys("dblp-base-name", ctx(0, None).copy(name = n))
    assertNotEquals(nfc, nfd)
    assertEquals(k(nfc), k(nfd))
    assertEquals(k(nfc + " 0001"), k(nfd))
    assertEquals(k(nfc), List(nfd)) // key form is NFD
    // Only canonical equivalence: compatibility forms, case and accents stay distinct.
    assertNotEquals(k("\uff2a\uff4f\uff53\u00e9 Garc\u00eda"), k(nfc)) // fullwidth
    assertNotEquals(k("jos\u00e9 garc\u00eda"), k(nfc))
    assertNotEquals(k("Jose Garcia"), k(nfc))
  }

  test("export merge: copies differing only in run, job and source IDs merge; others do not") {
    val p = run.provenance(SourceType.OpenAlex, List("I1"), None, "openalex", "2026-11-12")
    val a =
      Institution(1, "I1", "U", Some("PT"), p.copy(jobName = "hadoop.j02-normalize-openalex-work"))
    val b = Institution(
      1,
      "I1",
      "U",
      Some("PT"),
      p.copy(jobName = "hadoop.j02-normalize-openalex-author")
    )
    val merged = GraphExportJob.mergeCopies(List(write(a), write(b))).toOption.get
    assert(merged.contains("hadoop.j02-normalize-openalex-author"))
    assert(GraphExportJob.mergeCopies(List(write(a), write(b.copy(name = "V")))).isLeft)
  }
}
