package org.scholarchain.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scholarchain.hadoop.io.{DblpRecordScanner, LineScanner, RawRecord, ScannedUnit}

class ScannerSuite extends munit.FunSuite {

  private val boundary =
    Set(
      "article",
      "inproceedings",
      "proceedings",
      "book",
      "incollection",
      "phdthesis",
      "mastersthesis",
      "www",
      "data"
    )

  private def scanXml(bytes: Array[Byte], start: Long, end: Long, max: Int = 1 << 20) = {
    val in = new ByteArrayInputStream(bytes)
    in.skip(start)
    val sc = new DblpRecordScanner(in, start, end, boundary, max)
    Iterator.continually(sc.next()).takeWhile(_.isDefined).flatten.toList
  }

  private def scanLines(bytes: Array[Byte], start: Long, end: Long, max: Int = 1 << 20) = {
    val in = new ByteArrayInputStream(bytes)
    in.skip(start)
    val sc = new LineScanner(in, start, end, max)
    Iterator.continually(sc.next()).takeWhile(_.isDefined).flatten.toList
  }

  private def str(u: ScannedUnit) = new String(u.payload, StandardCharsets.ISO_8859_1)

  private val realSample =
    Files.readAllBytes(TestSupport.repoRoot.resolve("datasets/samples/dblp/dblp-sample.xml"))

  test("finds all 9 records of the real sample, each a complete element") {
    val units = scanXml(realSample, 0, realSample.length)
    assertEquals(units.size, 9)
    assert(units.forall(_.status == RawRecord.Ok))
    assertEquals(
      units.map(_.element).distinct.sorted,
      List("article", "incollection", "inproceedings", "proceedings")
    )
    units.foreach(u =>
      assert(str(u).startsWith(s"<${u.element} ") && str(u).endsWith(s"</${u.element}>"))
    )
  }

  test("documented limitation: a split starting inside a comment yields a counted phantom unit") {
    val xml = ("<dblp><!-- <article key=\"conf/sigir/Ghost\"><title>G</title></article> -->\n" +
      "<article key=\"conf/sigir/Real\"><title>R</title></article></dblp>")
      .getBytes(StandardCharsets.US_ASCII)
    val whole = scanXml(xml, 0, xml.length)
    assertEquals(whole.map(str(_)).map(_.contains("Real")), List(true))
    val splitAt = xml.indexOf('<'.toByte, 10) // the '<' of the commented-out <article
    val first = scanXml(xml, 0, splitAt)
    val second = scanXml(xml, splitAt, xml.length)
    assertEquals(first, Nil)
    // the second reader cannot know it starts inside a comment: the ghost is a unit (it reaches
    // the mapper, is counted and disposed of), never silently lost
    assertEquals(second.map(u => str(u).contains("Ghost")), List(true, false))
  }

  test("raw-unit previews use the configured preview size") {
    val xml = ("<dblp><article key=\"k\">" + ("x" * 9000) + "</article></dblp>")
      .getBytes(StandardCharsets.US_ASCII)
    val in = new ByteArrayInputStream(xml)
    val sc = new DblpRecordScanner(in, 0, xml.length, boundary, 1000, 6000)
    val u = sc.next().get
    assertEquals(u.status, RawRecord.Oversize)
    assertEquals(u.payload.length, 1000) // bounded by what was kept (maxRecordBytes)
    val in2 = new ByteArrayInputStream(xml)
    val u2 = new DblpRecordScanner(in2, 0, xml.length, boundary, 8000, 6000).next().get
    assertEquals(u2.payload.length, 6000)
    val line = ("y" * 9000 + "\n").getBytes(StandardCharsets.US_ASCII)
    val l = new LineScanner(new ByteArrayInputStream(line), 0, line.length, 8000, 5000).next().get
    assertEquals((l.status, l.payload.length), (RawRecord.Oversize, 5000))
  }

  test("record-boundary safety: every split point yields each record exactly once") {
    val whole = scanXml(realSample, 0, realSample.length).map(u => u.offset -> str(u))
    (0 to realSample.length by 7).foreach { s =>
      val parts = scanXml(realSample, 0, s) ++ scanXml(realSample, s, realSample.length)
      assertEquals(parts.map(u => u.offset -> str(u)), whole, s"split at $s")
    }
  }

  test("records written back to back on one line and '>' inside an attribute value") {
    val xml =
      """<dblp><article key="a/b>c" mdate="x"><title>T</title></article><www key="w/x"></www><data key="d/y"/></dblp>"""
    val units = scanXml(xml.getBytes, 0, xml.length)
    assertEquals(units.map(_.element), List("article", "www", "data"))
    assertEquals(str(units(0)), """<article key="a/b>c" mdate="x"><title>T</title></article>""")
    assertEquals(str(units(2)), """<data key="d/y"/>""")
  }

  test("oversize records are skipped to their end tag and reported; scanning resumes") {
    val big = "<article key=\"x/y\"><title>" + ("z" * 500) + "</title></article>"
    val xml = s"<dblp>$big<article key=\"x/z\"><title>ok</title></article></dblp>"
    val units = scanXml(xml.getBytes, 0, xml.length, max = 100)
    assertEquals(units.map(_.status), List(RawRecord.Oversize, RawRecord.Ok))
    assertEquals(units.head.length, big.length.toLong)
    assertEquals(units.head.payload.length, 100)
  }

  test("end of input inside a record is reported as truncated") {
    val xml = "<dblp><article key=\"x/y\"><title>cut"
    val units = scanXml(xml.getBytes, 0, xml.length)
    assertEquals(units.map(_.status), List(RawRecord.Truncated))
  }

  test("end of input inside the start tag name of a record is reported as truncated") {
    val head = "<dblp><www key=\"w/x\"></www>\n"
    (1 to "<article".length).foreach { n =>
      val xml = head + "<article".take(n)
      val units = scanXml(xml.getBytes, 0, xml.length)
      assertEquals(units.map(_.status), List(RawRecord.Ok, RawRecord.Truncated), xml)
      assertEquals(units.last.offset, head.length.toLong, xml)
      assertEquals(units.last.element, "article".take(n - 1), xml)
    }
    // A cut name that cannot begin a record element is not a record.
    val other = head + "</dbl"
    assertEquals(scanXml(other.getBytes, 0, other.length).map(_.status), List(RawRecord.Ok))
  }

  test("end tag with whitespace before '>' ends the record (XML 1.0 [42] ETag)") {
    val xml =
      "<dblp><article key=\"a/1\"><title>T</title></article \n\t><article key=\"a/2\"><title>U</title></article\r\n></dblp>"
    val units = scanXml(xml.getBytes, 0, xml.length)
    assertEquals(units.map(_.status), List(RawRecord.Ok, RawRecord.Ok))
    assertEquals(str(units(0)), "<article key=\"a/1\"><title>T</title></article \n\t>")
    assertEquals(str(units(1)), "<article key=\"a/2\"><title>U</title></article\r\n>")
    // A longer name or other bytes after the name is not the end tag.
    val y = "<dblp><article key=\"a/3\"><t></articles><t></article x></article></dblp>"
    assertEquals(scanXml(y.getBytes, 0, y.length).map(str), List(y.drop(6).dropRight(7)))
  }

  test("comments, CDATA sections and processing instructions never start or end a record") {
    val xml =
      """<dblp><!-- gone: <article key="c/0"> --><?pi <article key="c/1">?><![CDATA[<article key="c/2">]]>""" +
        """<article key="c/3"><title>A<!-- </article> --><![CDATA[</article>]]><?x </article>?>B</title></article>""" +
        """<!-- <www key="c/4"/> --><www key="c/5"/></dblp>"""
    val units = scanXml(xml.getBytes, 0, xml.length)
    assertEquals(units.map(_.status), List(RawRecord.Ok, RawRecord.Ok))
    assertEquals(
      units.map(str),
      List(
        """<article key="c/3"><title>A<!-- </article> --><![CDATA[</article>]]><?x </article>?>B</title></article>""",
        """<www key="c/5"/>"""
      )
    )
    // "<!-" and "<![" that do not open a comment or CDATA are left alone.
    val odd = """<dblp><!-x<article key="o/1"></article><![CD<www key="o/2"/></dblp>"""
    assertEquals(scanXml(odd.getBytes, 0, odd.length).map(_.element), List("article", "www"))
    // End of input inside a comment in a record truncates that record.
    val cut = """<dblp><article key="t/1"><!-- </article>"""
    assertEquals(scanXml(cut.getBytes, 0, cut.length).map(_.status), List(RawRecord.Truncated))
  }

  test("record-boundary safety holds with comments, PIs and spaced end tags") {
    val xml =
      """<?xml version="1.0"?><!-- head --><dblp><article key="s/1"><title>A<!-- x --></title></article >""" +
        """<?pi y?><www key="s/2"/><inproceedings key="s/3"><![CDATA[ z ]]></inproceedings	>""" +
        """<!-- tail --></dblp>"""
    val bytes = xml.getBytes
    val whole = scanXml(bytes, 0, bytes.length).map(u => (u.status, u.offset, str(u)))
    assertEquals(whole.map(_._1), List(RawRecord.Ok, RawRecord.Ok, RawRecord.Ok))
    (0 to bytes.length).foreach { s =>
      val parts = scanXml(bytes, 0, s) ++ scanXml(bytes, s, bytes.length)
      assertEquals(parts.map(u => (u.status, u.offset, str(u))), whole, s"split at $s")
    }
  }

  test("non-record tags and similar names are not boundaries") {
    val xml =
      "<dblp><articles key=\"no\"></articles><author>x</author><book key=\"b/c\"></book></dblp>"
    assertEquals(scanXml(xml.getBytes, 0, xml.length).map(_.element), List("book"))
  }

  test("line scanner: every split point yields each line once, CRLF stripped") {
    val text = "{\"a\":1}\r\n{\"b\":2}\n\n{\"c\":3}"
    val bytes = text.getBytes
    val whole = scanLines(bytes, 0, bytes.length).map(u => u.offset -> str(u))
    assertEquals(whole.map(_._2), List("{\"a\":1}", "{\"b\":2}", "", "{\"c\":3}"))
    (1 to bytes.length).foreach { s => // Hadoop never creates empty splits
      val parts = scanLines(bytes, 0, s) ++ scanLines(bytes, s, bytes.length)
      assertEquals(parts.map(u => u.offset -> str(u)), whole, s"split at $s")
    }
  }

  test("line scanner reports over-long lines instead of skipping them") {
    val text = "short\n" + ("x" * 50) + "\nnext\n"
    val units = scanLines(text.getBytes, 0, text.length, max = 10)
    assertEquals(units.map(_.status), List(RawRecord.Ok, RawRecord.Oversize, RawRecord.Ok))
    assertEquals(units(1).length, 50L)
    assertEquals(str(units(2)), "next")
  }
}
