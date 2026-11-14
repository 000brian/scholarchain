package org.scholarchain.contracts.hadoop

import org.scholarchain.contracts.hadoop.AdversarialSupport.*
import org.scholarchain.core.{WorkId}
import org.scholarchain.hadoop.common.Reasons
import org.scholarchain.hadoop.ids.ExactLinker
import org.scholarchain.hadoop.teams.{TeamAssembler, TeamLimits}

/** Adversarial record cases: missing key, title, year and authors; duplicate dblp keys with
  * identical or conflicting content; duplicate DOIs within and across works.
  */
class RecordFieldAdversarialSuite extends munit.FunSuite {

  private val limits = TeamLimits(100, 5000, 50)

  private def outcome(attrs: String, body: String): MapOutcome =
    buildXml(s"<inproceedings$attrs>$body</inproceedings>")

  private val okBody =
    "<author>A One</author><author>B Two</author><title>T</title><year>2020</year>"

  private def assertRejected(o: MapOutcome, reason: String)(implicit loc: munit.Location): Unit =
    o match {
      case Rejected(r, _) => assertEquals(r, reason)
      case other          => fail(s"expected rejected/$reason, got $other")
    }

  test("missing-key: absent, empty and blank key attributes are rejected as missing_key") {
    assertRejected(outcome("", okBody), Reasons.MissingKey)
    assertRejected(outcome(""" key="" """, okBody), Reasons.MissingKey)
    assertRejected(outcome(""" key="   " """, okBody), Reasons.MissingKey)
  }

  test("missing-key: structurally invalid keys are rejected as invalid_key") {
    List("conf/sigir/", "conf/sigir/a b", "Conf/sigir/X", "nokey", "/conf/x").foreach { k =>
      assertRejected(outcome(s""" key="$k"""", okBody), Reasons.InvalidKey)
    }
  }

  test("missing-title: absent, empty, blank and markup-only titles are rejected") {
    val authors = "<author>A One</author><year>2020</year>"
    assertRejected(outcome(""" key="conf/sigir/T1"""", authors), Reasons.MissingTitle)
    assertRejected(
      outcome(""" key="conf/sigir/T2"""", authors + "<title></title>"),
      Reasons.MissingTitle
    )
    assertRejected(
      outcome(""" key="conf/sigir/T3"""", authors + "<title> \n\t </title>"),
      Reasons.MissingTitle
    )
    assertRejected(
      outcome(""" key="conf/sigir/T4"""", authors + "<title><i></i><sub> </sub></title>"),
      Reasons.MissingTitle
    )
  }

  test("missing-title: two different titles are a conflicting_field rejection") {
    assertRejected(
      outcome(
        """ key="conf/sigir/T5"""",
        "<author>A</author><title>One</title><title>Two</title><year>2020</year>"
      ),
      Reasons.ConflictingField
    )
  }

  test("missing-year: absent year is excluded (year), malformed year is rejected (invalid_year)") {
    assertEquals(
      outcome(""" key="conf/sigir/Y1"""", "<author>A</author><title>T</title>"),
      Excluded(Reasons.ExYear)
    )
    List("20x0", "202", "20200", "２０２０", "٢٠٢٠", "-2020")
      .foreach { y =>
        assertRejected(
          outcome(""" key="conf/sigir/Y2"""", s"<author>A</author><title>T</title><year>$y</year>"),
          Reasons.InvalidYear
        )
      }
    assertRejected(
      outcome(""" key="conf/sigir/Y3"""", "<author>A</author><title>T</title><year></year>"),
      Reasons.InvalidYear
    )
  }

  test("missing-year: whitespace around a valid year is tolerated; two years conflict") {
    outcome(
      """ key="conf/sigir/Y4"""",
      "<author>A</author><title>T</title><year> 2020\n</year>"
    ) match {
      case Accepted(a) => assertEquals(a.work.year, 2020)
      case other       => fail(other.toString)
    }
    assertRejected(
      outcome(
        """ key="conf/sigir/Y5"""",
        "<author>A</author><title>T</title><year>2020</year><year>2021</year>"
      ),
      Reasons.ConflictingField
    )
  }

  test("missing-authors: no author, empty author and blank author are rejected") {
    assertRejected(
      outcome(""" key="conf/sigir/A1"""", "<title>T</title><year>2020</year>"),
      Reasons.NoAuthors
    )
    assertRejected(
      outcome(
        """ key="conf/sigir/A2"""",
        "<author>A</author><author></author><title>T</title><year>2020</year>"
      ),
      Reasons.InvalidAuthorName
    )
    assertRejected(
      outcome(""" key="conf/sigir/A3"""", "<author>   </author><title>T</title><year>2020</year>"),
      Reasons.InvalidAuthorName
    )
  }

  test("missing-authors: an editor-only record has no authors and is rejected") {
    assertRejected(
      outcome(""" key="conf/sigir/A4"""", "<editor>E</editor><title>T</title><year>2020</year>"),
      Reasons.NoAuthors
    )
  }

  test("duplicate-key: identical copies of a dblp key collapse into one team") {
    val o1 = buildXml(s"""<inproceedings key="conf/sigir/D1">$okBody</inproceedings>""")
    val o2 = buildXml(s"""<inproceedings key="conf/sigir/D1">$okBody</inproceedings>""")
    val (a1, a2) = (o1, o2) match {
      case (Accepted(x), Accepted(y)) => (x, y)
      case other                      => fail(other.toString)
    }
    assertEquals(a1.work, a2.work, "identical input must give an identical work (determinism)")
    assertEquals(a1.work.workId, WorkId.fromDblpKey("conf/sigir/D1"))
    val r = TeamAssembler.assemble(
      List(a1.work, a2.work),
      a1.mentions ++ a2.mentions,
      limits,
      run
    )
    assert(r.isRight, r)
    assertEquals(r.toOption.get.team.teamSize, 2)
    assertEquals(r.toOption.get.authorPapers.size, 2)
  }

  test("duplicate-key: conflicting copies of a dblp key are rejected, not merged") {
    val a = work("conf/sigir/D2", List("A One", "B Two"), title = "First")
    val b = work("conf/sigir/D2", List("A One", "B Two"), title = "Second")
    assertEquals(a.workId, b.workId)
    val r = TeamAssembler.assemble(List(a, b), mentions(a), limits, run)
    assertEquals(r.left.map(_.reason), Left(Reasons.DuplicateKeyConflict))
    // Same key, different author order: also a conflict.
    val c = work("conf/sigir/D2", List("B Two", "A One"), title = "First")
    assertEquals(
      TeamAssembler.assemble(List(a, c), mentions(a), limits, run).left.map(_.reason),
      Left(Reasons.DuplicateKeyConflict)
    )
  }

  test("duplicate-key: mentions that disagree with the work's author order are rejected") {
    val a = work("conf/sigir/D3", List("A One", "B Two", "C Three"))
    val ms = mentions(a)
    val swapped = List(ms(0), ms(1).copy(name = "C Three"), ms(2).copy(name = "B Two"))
    assertEquals(
      TeamAssembler.assemble(List(a), swapped, limits, run).left.map(_.reason),
      Left(Reasons.TeamMentionMismatch)
    )
    assertEquals(
      TeamAssembler.assemble(List(a), ms.take(2), limits, run).left.map(_.reason),
      Left(Reasons.TeamMentionMismatch)
    )
    assertEquals(
      TeamAssembler.assemble(Nil, ms, limits, run).left.map(_.reason),
      Left(Reasons.OrphanMention)
    )
  }

  test("duplicate-doi: two spellings of one DOI in a record give one DOI, no conflict") {
    val body =
      "<author>A</author><title>T</title><year>2020</year>" +
        "<ee>https://doi.org/10.5555/ABC-1</ee><ee>http://dx.doi.org/10.5555/abc%2D1</ee>"
    outcome(""" key="conf/sigir/Doi1"""", body) match {
      case Accepted(a) =>
        assertEquals(a.work.doi, Some("10.5555/abc-1"))
        assert(!a.issues.exists(_.reason == Reasons.ConflictingDois), a.issues)
      case other => fail(other.toString)
    }
  }

  test("duplicate-doi: two different DOIs in a record leave doi empty with a conflict issue") {
    val body =
      "<author>A</author><title>T</title><year>2020</year>" +
        "<ee>https://doi.org/10.5555/one</ee><ee>https://doi.org/10.5555/two</ee>"
    outcome(""" key="conf/sigir/Doi2"""", body) match {
      case Accepted(a) =>
        assertEquals(a.work.doi, None)
        assertEquals(a.issues.map(_.reason), List(Reasons.ConflictingDois))
        assertEquals(a.work.eeLinks.size, 2, "ee links are kept even when the DOI conflicts")
      case other => fail(other.toString)
    }
  }

  test("duplicate-doi: one DOI on two dblp works is a conflict and produces no links") {
    val doi = "10.5555/shared"
    val contexts = List(
      context("conf/sigir/Dup1", 0, "A One", Some(doi)),
      context("conf/sigir/Dup1", 1, "B Two", Some(doi)),
      context("journals/pvldb/Dup2", 0, "C Three", Some(doi))
    )
    val out = ExactLinker.link(doi, contexts, Nil, run)
    assertEquals(out.links, Nil)
    assertEquals(out.conflicts.map(_.reason), List(Reasons.DoiMultipleWorks))
    assert(out.conflicts.head.detail.contains("conf/sigir/Dup1"))
    assert(out.conflicts.head.detail.contains("journals/pvldb/Dup2"))
    assert(!out.unmatched)
  }

  test("duplicate-doi: a DOI with no dblp work is unmatched, not linked") {
    val out = ExactLinker.link("10.5555/none", Nil, Nil, run)
    assert(out.unmatched)
    assertEquals(out.links, Nil)
  }
}
