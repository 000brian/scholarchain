package org.scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scholarchain.core.{ExternalIdType, SourceType}
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.data.Loader
import org.scholarchain.er.work.WorkResolution

class WorkResolutionSuite extends munit.FunSuite {
  private val works = List(
    W("conf/kdd/K1", List("Ida Alpha"), doi = Some("10.1145/1111.2222")),
    W("conf/kdd/K2", List("Ida Beta"), doi = Some("10.1145/3333.4444")),
    W("conf/kdd/K3", List("Ida Gamma"), ee = List("https://example.org/paper/7")),
    W("conf/kdd/K4", List("Ida Delta"), ee = List("http://www.example.org/paper/7/"))
  )

  test("J04 exact DOI and OpenAlex work links become exact_id accepted decisions with coverage") {
    val links = List(
      Fixture.workLink("conf/kdd/K1", ExternalIdType.Doi, "10.1145/1111.2222", SourceType.Crossref),
      Fixture.workLink("conf/kdd/K1", ExternalIdType.OpenAlexWork, "W42"),
      Fixture.workLink("conf/kdd/K2", ExternalIdType.Doi, "10.1145/3333.4444")
    )
    val dir = Fixture.writeExport(works, links = Some(links))
    val in = Loader.load(dir, "s")
    val r = WorkResolution.run(
      in.corpus,
      in.workLinks,
      in.linksPresent,
      None,
      "er-v1",
      "2026-11-14T00:00:00Z",
      "run:v1:x"
    )
    val accepted = r.decisions.filter(_("outcome").str == "accepted")
    assertEquals(accepted.size, 3)
    accepted.foreach(d => assertEquals(d("matchPath").str, "exact_id"))
    assertEquals(
      accepted.map(_("rulesFired")(0).str).toSet,
      Set(WorkResolution.DoiLink, WorkResolution.OpenAlexLink)
    )
    assertEquals(r.summary("exactDoiLinkCoverage")("works").num, 2.0)
    assertEquals(r.summary("exactDoiLinkCoverage")("share").num, 0.5)
    assertEquals(r.summary("externalIdLinkDirectoryPresent").bool, true)
  }

  test(
    "J04 work-level conflicts become conflict decisions; shared landing URLs are conflicts; absent links are zero coverage"
  ) {
    val dir = Fixture.writeExport(works)
    val in = Loader.load(dir, "s")
    val j04 = Files.createTempDirectory("er-j04")
    val conflicts = j04.resolve("conflicts/v1/part-r-00000")
    Files.createDirectories(conflicts.getParent)
    val line = ujson.Obj(
      "format" -> "scholarchain.hadoop.disposition",
      "reason" -> "doi_maps_to_multiple_dblp_works",
      "sourceRecordId" -> "10.1145/1111.2222",
      "runId" -> "run:v1:00000000000000000000000000000004"
    )
    val mention = ujson.Obj(
      "reason" -> "orcid_on_multiple_mentions_of_work",
      "sourceRecordId" -> "10.1145/3333.4444",
      "runId" -> "r"
    )
    Files.write(
      conflicts,
      (line.render() + "\n" + mention.render() + "\n").getBytes(StandardCharsets.UTF_8)
    )
    val r = WorkResolution.run(
      in.corpus,
      in.workLinks,
      in.linksPresent,
      Some(WorkResolution.readJ04Conflicts(j04)),
      "er-v1",
      "2026-11-14T00:00:00Z",
      "run:v1:x"
    )
    val j04Decisions = r.decisions.filter(_("rulesFired")(0).str == WorkResolution.J04Conflict)
    assertEquals(j04Decisions.size, 1)
    assertEquals(j04Decisions.head("outcome").str, "conflict")
    assertEquals(j04Decisions.head("dblpKeys").arr.map(_.str).toList, List("conf/kdd/K1"))
    assertEquals(r.summary("j04ConflictsByReason")("orcid_on_multiple_mentions_of_work").num, 1.0)
    val url = r.decisions.filter(_("idType").str == "landing_url")
    assertEquals(url.size, 1)
    assertEquals(url.head("outcome").str, "conflict")
    assertEquals(url.head("externalId").str, "https://example.org/paper/7")
    assertEquals(r.summary("externalIdLinkDirectoryPresent").bool, false)
    assertEquals(r.summary("exactDoiLinkCoverage")("works").num, 0.0)
    assert(r.summary("probabilisticWorkMatching").str.contains("finding 11"))
  }

  test("m3: counts in the work-resolution summary are JSON numbers, never strings") {
    val links = List(
      Fixture.workLink("conf/kdd/K1", ExternalIdType.Doi, "10.1145/1111.2222"),
      Fixture.workLink("conf/kdd/OUTSIDE1", ExternalIdType.Doi, "10.1145/9999.0001"),
      Fixture.workLink("conf/kdd/OUTSIDE2", ExternalIdType.Doi, "10.1145/9999.0002")
    )
    val dir = Fixture.writeExport(works, links = Some(links))
    val in = Loader.load(dir, "s")
    val r = WorkResolution
      .run(in.corpus, in.workLinks, in.linksPresent, None, "er-v1", "2026-11-14T00:00:00Z", "r")
    assertEquals(r.summary("workLinksOutsideSubset"), ujson.Num(2))
    def walk(v: ujson.Value, path: String): Unit = v match {
      case ujson.Str(s) => assert(!s.matches("-?[0-9]+(\\.[0-9]+)?"), s"$path is a numeric string")
      case o: ujson.Obj => o.value.foreach { case (k, x) => walk(x, s"$path.$k") }
      case a: ujson.Arr => a.value.zipWithIndex.foreach { case (x, i) => walk(x, s"$path[$i]") }
      case _            => ()
    }
    walk(r.summary, "summary")
  }
}
