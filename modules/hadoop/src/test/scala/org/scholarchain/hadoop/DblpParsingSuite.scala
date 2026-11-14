package org.scholarchain.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

import org.scholarchain.core.{RunId, WorkId}
import org.scholarchain.hadoop.common.RunContext
import org.scholarchain.hadoop.dblp.*

class DblpParsingSuite extends munit.FunSuite {

  private val run = RunContext(
    RunId.of("j", "c", "i", "f").toOption.get,
    "hadoop.test",
    "dblp",
    "2026-11-01",
    "dblp-parse/1"
  )
  private val rule = DblpSubsetRule(
    Set("article", "inproceedings"),
    enabled = true,
    List("conf/sigir/", "journals/pvldb/"),
    2015,
    2025,
    Set("informal")
  )
  private val parser = new DblpRecordParser

  private def build(xml: String): DblpOutcome =
    DblpRecordBuilder.build(parser.parse(xml).toOption.get, rule, run, xml)

  test("entity declarations and expansion: declared, predefined and numeric references") {
    val ents =
      DblpEntities.declarations("""<!ENTITY eacute "&#233;"> <!ENTITY  auml   "&#228;" >""")
    assertEquals(ents, Map("eacute" -> "&#233;", "auml" -> "&#228;"))
    assertEquals(
      DblpEntities.expand("Jos&eacute; &amp; &#26446; &lt;", ents),
      Right("Jos&#233; &amp; &#26446; &lt;")
    )
    assertEquals(DblpEntities.expand("&ouml;", ents), Left("ouml"))
  }

  test("bounded expansion refuses quadratic blow-up before building it") {
    // a ~1 MiB entity referenced 300,000 times would expand to ~300 GiB
    val big = "x" * (1 << 20)
    val ents = Map("big" -> big)
    val record = "<title>" + ("&big;" * 300000) + "</title>"
    DblpEntities.expand(record, ents, 2 << 20) match {
      case Left(ExpandError.TooLarge(atLeast, max)) =>
        assertEquals(max, 2 << 20)
        assert(atLeast > max)
        assert(atLeast < 4L * (1 << 20), s"stopped late: $atLeast")
      case other => fail(s"expected TooLarge, got ${other.toString.take(80)}")
    }
    // within the bound: expanded; the bound counts the final length exactly
    val small = "a&eacute;b"
    val e2 = Map("eacute" -> "&#233;")
    assertEquals(DblpEntities.expand(small, e2, 8), Right("a&#233;b"))
    assert(DblpEntities.expand(small, e2, 7).isLeft)
    // a record longer than the bound is refused even without entities
    assert(DblpEntities.expand("abcdef", Map.empty, 5).isLeft)
    assertEquals(DblpEntities.expand("abcdef", Map.empty, 6), Right("abcdef"))
    assertEquals(DblpEntities.expand("&nope;", Map.empty, 100), Left(ExpandError.Undefined("nope")))
  }

  test("prolog: declared encoding and internal subset of the real sample") {
    val bytes = java.nio.file.Files
      .readAllBytes(TestSupport.repoRoot.resolve("datasets/samples/dblp/dblp-sample.xml"))
    val Right((enc, text)) =
      DblpEntities.readProlog(new ByteArrayInputStream(bytes), 1 << 20): @unchecked
    assertEquals(enc, "ISO-8859-1")
    assertEquals(DblpEntities.declarations(text).size, 10)
    assert(DblpEntities.readProlog(new ByteArrayInputStream("<x/>".getBytes), 100).isLeft)
    assert(DblpEntities.readProlog(new ByteArrayInputStream(bytes), 100).isLeft)
  }

  test("parser: nested title markup, orcid attributes, repeated ee") {
    val xml =
      """<article key="journals/pvldb/X1" mdate="2020-01-01"><author orcid="0000-0002-1825-0097">A&#228;</author><author>B</author><title>On <i>x</i><sub>2</sub>.</title><year>2020</year><journal>PVLDB</journal><ee>https://doi.org/10.1/A</ee><ee>https://example.org</ee></article>"""
    val f = parser.parse(xml).toOption.get
    assertEquals(
      f.authors,
      List(RawAuthor("Aä", Some("0000-0002-1825-0097")), RawAuthor("B", None))
    )
    assertEquals(f.titles, List("On x2."))
    assertEquals(f.ees.size, 2)
    assert(parser.parse("<article key=\"a/b\"><title>x</article>").isLeft)
    assert(parser.parse("<article key=\"a/b\"></article><article key=\"a/c\"></article>").isLeft)
  }

  test("builder: accepted record maps every field into the core contracts") {
    val xml =
      """<inproceedings key="conf/sigir/K1" mdate="2024-01-01"><author orcid="0000-0002-1825-0097">Ann   Lee 0001</author><author orcid="0000-0000-0000-0000">Bo Li</author><title>A
 title.</title><year>2023</year><booktitle>SIGIR</booktitle><ee>https://doi.org/10.1145/ABC</ee><ee>http://dx.doi.org/10.1145/abc</ee></inproceedings>"""
    build(xml) match {
      case DblpOutcome.Accepted(w, ms, label, issues) =>
        assertEquals(w.workId, WorkId.fromDblpKey("conf/sigir/K1"))
        assertEquals(w.title, "A title.")
        assertEquals(w.authorNames, List("Ann Lee 0001", "Bo Li"))
        assertEquals(w.doi, Some("10.1145/abc"))
        assertEquals(w.venueKey, Some("conf/sigir"))
        assertEquals(w.publicationType, "inproceedings")
        assertEquals(label, Some("SIGIR"))
        assertEquals(ms.map(_.orcid), List(Some("0000-0002-1825-0097"), None))
        assertEquals(issues.map(_.reason), List("invalid_orcid_attr"))
        assert(w.provenance.contentHash.isDefined)
        assertEquals(w.schemaVersion, 2)
        assertEquals(
          w.eeLinks,
          List("https://doi.org/10.1145/ABC", "http://dx.doi.org/10.1145/abc")
        )
        assertEquals(w.urls, Nil)
      case other => fail(s"unexpected $other")
    }
  }

  test("builder: subset decision order matches the dataset manifest") {
    def outcome(xml: String) = build(xml) match {
      case DblpOutcome.Excluded(r, _)    => s"excluded:$r"
      case DblpOutcome.Rejected(r, _, _) => s"rejected:$r"
      case _: DblpOutcome.Accepted       => "accepted"
    }
    assertEquals(
      outcome(
        "<proceedings key=\"conf/sigir/2023\"><title>P</title><year>2023</year></proceedings>"
      ),
      "excluded:element"
    )
    assertEquals(
      outcome("<article key=\"dblpnote/x\" publtype=\"informal\"><title>t</title></article>"),
      "excluded:key_prefix"
    )
    assertEquals(
      outcome("<article key=\"conf/sigir/a\"><author>A</author><title>t</title></article>"),
      "excluded:year"
    )
    assertEquals(
      outcome(
        "<article key=\"conf/sigir/a\"><author>A</author><title>t</title><year>2014</year></article>"
      ),
      "excluded:year"
    )
    assertEquals(
      outcome(
        "<article key=\"conf/sigir/a\" publtype=\"informal withdrawn\"><author>A</author><title>t</title><year>2020</year></article>"
      ),
      "excluded:publtype"
    )
    assertEquals(
      outcome("<article><author>A</author><title>t</title><year>2020</year></article>"),
      "rejected:missing_key"
    )
    assertEquals(
      outcome("<article key=\"has space/x\"><title>t</title></article>"),
      "rejected:invalid_key"
    )
    assertEquals(
      outcome(
        "<article key=\"conf/sigir/a\"><author>A</author><title>t</title><year>20x0</year></article>"
      ),
      "rejected:invalid_year"
    )
    assertEquals(
      outcome(
        "<article key=\"conf/sigir/a\"><author>A</author><title>t</title><year>2020</year><year>2021</year></article>"
      ),
      "rejected:conflicting_field"
    )
    assertEquals(
      outcome(
        "<article key=\"conf/sigir/a\"><author>A</author><title> </title><year>2020</year></article>"
      ),
      "rejected:missing_title"
    )
    assertEquals(
      outcome("<article key=\"conf/sigir/a\"><title>t</title><year>2020</year></article>"),
      "rejected:no_authors"
    )
    assertEquals(
      outcome(
        "<article key=\"conf/sigir/a\"><author> </author><title>t</title><year>2020</year></article>"
      ),
      "rejected:invalid_author_name"
    )
    assertEquals(
      outcome(
        "<article key=\"conf/sigir/a\"><author>A</author><title>t</title><year>2020</year></article>"
      ),
      "accepted"
    )
  }

  test("builder: two distinct DOIs leave doi empty and report a conflict") {
    val xml =
      "<article key=\"conf/sigir/a\"><author>A</author><title>t</title><year>2020</year><ee>https://doi.org/10.1/a</ee><ee>https://doi.org/10.1/b</ee></article>"
    build(xml) match {
      case DblpOutcome.Accepted(w, _, _, issues) =>
        assertEquals(w.doi, None)
        assertEquals(issues.map(_.reason), List("conflicting_dois"))
      case other => fail(s"unexpected $other")
    }
  }

  test("builder: subset rule can be disabled (element filter still applies)") {
    val open = rule.copy(enabled = false)
    val xml =
      "<article key=\"journals/x/a\"><author>A</author><title>t</title><year>1990</year></article>"
    assert(
      DblpRecordBuilder
        .build(parser.parse(xml).toOption.get, open, run, xml)
        .isInstanceOf[DblpOutcome.Accepted]
    )
    val text = new String(
      "<www key=\"homepages/a\"><author>A</author><title>Home Page</title></www>"
        .getBytes(StandardCharsets.UTF_8),
      StandardCharsets.UTF_8
    )
    assertEquals(
      DblpRecordBuilder.build(parser.parse(text).toOption.get, open, run, text),
      DblpOutcome.Excluded("element", "homepages/a")
    )
  }

  test("builder: eeLinks and urls are decoded, trimmed, in source order, first occurrence kept") {
    val xml =
      """<inproceedings key="conf/sigir/L1" mdate="2024-01-01"><author>A</author><title>t</title><year>2023</year><ee>  https://example.org/b?x=1&amp;y=2 </ee><ee>https://doi.org/10.1145/X</ee><ee>https://example.org/b?x=1&amp;y=2</ee><url>db/conf/sigir/sigir2023.html#L1</url><url>db/conf/sigir/sigir2023.html#L1</url><url>  db/other.html </url></inproceedings>"""
    build(xml) match {
      case DblpOutcome.Accepted(w, _, _, issues) =>
        assertEquals(
          w.eeLinks,
          List("https://example.org/b?x=1&y=2", "https://doi.org/10.1145/X")
        )
        assertEquals(w.urls, List("db/conf/sigir/sigir2023.html#L1", "db/other.html"))
        assertEquals(w.doi, Some("10.1145/x"))
        assertEquals(issues, Nil)
        assertEquals(org.scholarchain.core.Contract.validate(w), Nil)
      case other => fail(s"unexpected $other")
    }
  }

  test("builder: blank and control-character links are dropped with field issues") {
    val xml =
      "<article key=\"conf/sigir/L2\"><author>A</author><title>t</title><year>2020</year><ee>   </ee><ee>https://doi.org/10.1/&#10;a</ee><ee>https://doi.org/10.1/b</ee><url></url><url>db/x.html</url></article>"
    build(xml) match {
      case DblpOutcome.Accepted(w, _, _, issues) =>
        assertEquals(w.eeLinks, List("https://doi.org/10.1/b"))
        assertEquals(w.urls, List("db/x.html"))
        // the DOI comes only from a kept link, so it satisfies the WorkRecord v2 doi rule
        assertEquals(w.doi, Some("10.1/b"))
        assertEquals(
          issues.map(_.reason),
          List("blank_source_link", "invalid_source_link", "blank_source_link")
        )
        assertEquals(org.scholarchain.core.Contract.validate(w), Nil)
      case other => fail(s"unexpected $other")
    }
  }

  test("sourceLinks keeps the first occurrence and reports positions") {
    val (kept, issues) =
      DblpRecordBuilder.sourceLinks("ee", List("b", " a ", "b", "", "a"))
    assertEquals(kept, List("b", "a"))
    assertEquals(issues.map(_.detail), List("ee position 3 is blank"))
  }
}
