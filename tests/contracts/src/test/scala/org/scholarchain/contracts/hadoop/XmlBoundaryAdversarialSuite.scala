package org.scholarchain.contracts.hadoop

import java.io.ByteArrayInputStream

import org.scholarchain.contracts.hadoop.AdversarialSupport.*
import org.scholarchain.hadoop.io.RawRecord
import org.scholarchain.hadoop.dblp.DblpEntities

/** Adversarial J01 input cases: record boundaries at every split point, malformed and truncated
  * XML. Scanner contract: every record is read by exactly one reader; a reader finishes a record it
  * started across its split end; end of input inside a record yields a Truncated unit.
  */
class XmlBoundaryAdversarialSuite extends munit.FunSuite {

  private val corpus = fixture("split-corpus.xml")
  private val corpusText = new String(corpus, "UTF-8")
  private val expectedKeys = List(
    "conf/sigir/AdvA20",
    "homepages/00/0000",
    "journals/pvldb/AdvB21",
    "conf/kdd/AdvC22",
    "journals/pvldb/AdvD23"
  )
  private val KeyAttr = """key\s*=\s*"([^"]*)"""".r
  private def keyOf(s: String): String = KeyAttr.findFirstMatchIn(s).map(_.group(1)).getOrElse("?")

  private lazy val whole = scanAll(corpus)
  private lazy val entities =
    DblpEntities.declarations(
      DblpEntities.readProlog(new ByteArrayInputStream(corpus), 1 << 20).toOption.get._2
    )

  test("xml-boundary: single reader finds exactly the five corpus records, all Ok") {
    assertEquals(whole.map(_.status).distinct, List(RawRecord.Ok))
    assertEquals(whole.map(u => keyOf(text(u))), expectedKeys)
    whole.foreach { u =>
      val t = text(u)
      assert(t.startsWith("<" + u.element), t)
      assert(t.endsWith("/>") || t.endsWith(s"</${u.element}>"), t)
      assertEquals(
        new String(corpus.slice(u.offset.toInt, (u.offset + u.length).toInt), "UTF-8"),
        t,
        "offset and length must address the record bytes"
      )
    }
  }

  test("xml-boundary: two readers at every split point read each record exactly once") {
    val reference = whole.map(u => (u.offset, text(u)))
    val failures = (0 to corpus.length).flatMap { s =>
      val a = scanSplit(corpus, 0, s)
      val b = scanSplit(corpus, s, corpus.length)
      val got = (a ++ b).map(u => (u.status, u.offset, text(u)))
      val expected = reference.map { case (o, t) => (RawRecord.Ok, o, t) }
      if (got != expected) Some(s"split at $s: ${got.map(g => (g._1, g._2))}") else None
    }
    assertEquals(failures.take(5).toList, Nil, s"${failures.size} failing split points")
  }

  test("xml-boundary: three readers over a grid of split pairs read each record exactly once") {
    val reference = whole.map(u => (RawRecord.Ok, u.offset, text(u)))
    val points = (0 to corpus.length by 13).toList :+ corpus.length
    val failures = for {
      s1 <- points
      s2 <- points if s2 >= s1
      got = (scanSplit(corpus, 0, s1) ++ scanSplit(corpus, s1, s2) ++
        scanSplit(corpus, s2, corpus.length)).map(u => (u.status, u.offset, text(u)))
      if got != reference
    } yield (s1, s2)
    assertEquals(failures.take(5), Nil, s"${failures.size} failing split pairs")
  }

  test("xml-boundary: split points exactly at a record's '<' and just after it") {
    whole.foreach { u =>
      val at = u.offset.toInt
      for (s <- List(at - 1, at, at + 1, (u.offset + u.length).toInt - 1)) {
        val a = scanSplit(corpus, 0, s).map(_.offset)
        val b = scanSplit(corpus, s, corpus.length).map(_.offset)
        assertEquals((a ++ b).count(_ == u.offset), 1, s"record at $at, split at $s")
      }
    }
  }

  test("xml-boundary: every corpus record maps to the expected outcome (accepted or excluded)") {
    val outcomes = whole.map(u => keyOf(text(u)) -> MapperModel(u, entities)).toMap
    assert(outcomes("conf/sigir/AdvA20").isInstanceOf[Accepted], outcomes("conf/sigir/AdvA20"))
    assertEquals(outcomes("homepages/00/0000"), Excluded("element"))
    val Accepted(b) = outcomes("journals/pvldb/AdvB21"): @unchecked
    assertEquals(b.work.authorNames.size, 3)
    val Accepted(a) = outcomes("conf/sigir/AdvA20"): @unchecked
    assertEquals(a.work.title, "[SYNTHETIC] First record with markup & entities.")
    assertEquals(a.work.authorNames(1), "Synthétic Beta")
    assertEquals(a.mentions(1).orcid, Some("0000-0002-1825-0097"))
  }

  test("truncated-xml: unclosed final record yields one Truncated unit after the Ok records") {
    val cut = corpusText.indexOf("<title>[SYNTHETIC] Final record")
    val units = scanAll(corpus.take(cut))
    assertEquals(units.init.map(_.status).distinct, List(RawRecord.Ok))
    assertEquals(units.init.size, 4)
    assertEquals(units.last.status, RawRecord.Truncated)
    assertEquals(units.last.element, "article")
    assert(keyOf(text(units.last)) == "journals/pvldb/AdvD23", text(units.last))
    assert(MapperModel(units.last, entities).isInstanceOf[Rejected])
  }

  test("truncated-xml: cut anywhere after the final start tag name yields a Truncated unit") {
    val start = corpusText.indexOf("<article mdate=\"2026-11-12\" key=\"journals/pvldb/AdvD23\"")
    val end = corpusText.indexOf("</article>", start) + "</article>".length
    val bad = ((start + "<article".length + 1) until end).filter { cut =>
      val units = scanAll(corpus.take(cut))
      !(units.size == 5 && units.last.status == RawRecord.Truncated && units.last.offset == start)
    }
    assertEquals(bad.toList, Nil)
  }

  test("truncated-xml: cut inside the final record's start tag name is still reported") {
    // The file ends with "<", "<a", ..., "<article": the final record is lost. Expected: a
    // non-Ok unit (Truncated) so the loss is counted and lands in rejects, never silent.
    val start = corpusText.indexOf("<article mdate=\"2026-11-12\" key=\"journals/pvldb/AdvD23\"")
    val silent = ((start + 2) to (start + "<article".length)).filter { cut =>
      val units = scanAll(corpus.take(cut))
      !units.exists(_.status != RawRecord.Ok)
    }
    assertEquals(silent.toList, Nil, "cut points where the truncated record vanished silently")
  }

  test("truncated-xml: truncated final record spanning a split end is reported by one reader") {
    val cut = corpusText.indexOf("<title>[SYNTHETIC] Final record")
    val data = corpus.take(cut)
    val start = corpusText.indexOf("key=\"journals/pvldb/AdvD23\"")
    for (s <- List(start - 5, start + 5, cut - 1)) {
      val all = scanSplit(data, 0, s) ++ scanSplit(data, s, data.length)
      assertEquals(all.count(_.status == RawRecord.Truncated), 1, s"split at $s")
    }
  }

  test("malformed-xml: unclosed middle record never yields an accepted record") {
    val xml =
      """<dblp><inproceedings key="conf/sigir/Open1"><author>A One</author><title>Open</title><year>2020</year>
        |<inproceedings key="conf/sigir/Next2"><author>B Two</author><author>C Three</author><title>Next</title><year>2021</year></inproceedings>
        |<inproceedings key="conf/sigir/Last3"><author>D Four</author><title>Last</title><year>2022</year></inproceedings></dblp>""".stripMargin
    val units = scanAll(utf8(xml))
    val outcomes = units.map(u => keyOf(text(u)) -> MapperModel(u, Map.empty))
    // The unclosed record and the record it swallowed must not be accepted as one record.
    assert(
      !outcomes.exists { case (k, o) => k == "conf/sigir/Open1" && o.isInstanceOf[Accepted] },
      outcomes
    )
    assert(
      outcomes.exists { case (k, o) => k == "conf/sigir/Last3" && o.isInstanceOf[Accepted] },
      outcomes
    )
  }

  test("malformed-xml: mismatched, unescaped and garbage content is rejected as malformed_xml") {
    val cases = List(
      """<article key="journals/pvldb/M1"><author>A</author><title>x</year><year>2020</year></article>""",
      """<article key="journals/pvldb/M2"><author>AT&T Labs</author><title>x</title><year>2020</year></article>""",
      """<article key="journals/pvldb/M3"><author>A</author><title>a < b</title><year>2020</year></article>""",
      """<article key="journals/pvldb/M4" key="journals/pvldb/M5"><author>A</author><title>x</title><year>2020</year></article>""",
      """<article key="journals/pvldb/M6"><author>A&#1;B</author><title>x</title><year>2020</year></article>""",
      """<article key="journals/pvldb/M7"><author>A&#xD800;B</author><title>x</title><year>2020</year></article>"""
    )
    cases.foreach { xml =>
      buildXml(xml) match {
        case Rejected(reason, _) => assertEquals(reason, "malformed_xml", xml)
        case other               => fail(s"expected malformed_xml for $xml, got $other")
      }
    }
  }

  test("malformed-xml: end tag with whitespace before '>' is a well-formed record boundary") {
    // XML 1.0 [42] ETag ::= '</' Name S? '>'.
    val xml =
      """<dblp><article key="journals/pvldb/W1"><author>A</author><title>One</title><year>2020</year></article >
        |<article key="journals/pvldb/W2"><author>B</author><title>Two</title><year>2021</year></article></dblp>""".stripMargin
    val units = scanAll(utf8(xml))
    assertEquals(
      units.map(u => (u.status, keyOf(text(u)))),
      List(
        (RawRecord.Ok, "journals/pvldb/W1"),
        (RawRecord.Ok, "journals/pvldb/W2")
      )
    )
  }

  test("malformed-xml: a record-like tag inside an XML comment does not swallow the next record") {
    val xml =
      """<dblp><!-- removed: <article key="journals/pvldb/Gone"> -->
        |<article key="journals/pvldb/Real1"><author>A</author><title>Real</title><year>2020</year></article></dblp>""".stripMargin
    val units = scanAll(utf8(xml))
    val accepted = units.map(u => MapperModel(u, Map.empty)).collect { case Accepted(a) => a }
    assertEquals(accepted.map(_.work.dblpKey), List("journals/pvldb/Real1"))
  }

  test("oversize-record: a record above maxRecordBytes is Oversize with a bounded preview") {
    val units = scanAll(corpus, maxRecordBytes = 200)
    val big = units.filter(_.status == RawRecord.Oversize)
    assert(big.nonEmpty)
    big.foreach(u => assert(u.payload.length <= RawRecord.PreviewBytes))
    // Records are still separated correctly after an oversize record.
    assertEquals(units.map(u => keyOf(text(u)).take(3)).size, 5)
    assertEquals(scanAll(corpus, maxRecordBytes = 1 << 20).count(_.status == RawRecord.Oversize), 0)
  }
}
