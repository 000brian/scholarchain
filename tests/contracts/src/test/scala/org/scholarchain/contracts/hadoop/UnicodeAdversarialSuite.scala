package org.scholarchain.contracts.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.text.Normalizer

import org.scholarchain.contracts.hadoop.AdversarialSupport.*
import org.scholarchain.core.{CodePointOrder, Contract, PairPosition}
import org.scholarchain.hadoop.blocking.BlockingKeys
import org.scholarchain.hadoop.dblp.DblpEntities
import org.scholarchain.hadoop.io.RawRecord
import org.scholarchain.hadoop.pairs.{ExpandedPair, PairExpansion}
import org.scholarchain.hadoop.teams.{TeamAssembler, TeamLimits}

/** Adversarial Unicode cases: NFC/NFD, confusables, entities (declared, undeclared, nested,
  * recursive), non-BMP characters, byte order mark.
  */
class UnicodeAdversarialSuite extends munit.FunSuite {

  private val limits = TeamLimits(100, 5000, 50)
  private def rec(author: String, title: String = "T"): String =
    s"""<inproceedings key="conf/sigir/U1"><author>$author</author><author>Other Person</author><title>$title</title><year>2020</year></inproceedings>"""

  private def accepted(o: MapOutcome) = o match {
    case Accepted(a) => a
    case other       => fail(s"expected an accepted record, got $other")
  }

  test("unicode-nfd: decomposed author names are preserved verbatim (no lossy rewrite)") {
    val nfd = "José García"
    val a = accepted(buildXml(rec(nfd)))
    assertEquals(a.work.authorNames.head, nfd)
    assertEquals(a.mentions.head.name, nfd)
    assertEquals(Contract.validate(a.work), Nil)
  }

  test("unicode-nfc-nfd: canonically equivalent names share a dblp-base-name blocking key") {
    val nfc = Normalizer.normalize("José García", Normalizer.Form.NFC)
    val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
    assertNotEquals(nfc, nfd)
    val kNfc = BlockingKeys.keys(BlockingKeys.DblpBaseName, context("conf/sigir/N1", 0, nfc))
    val kNfd = BlockingKeys.keys(BlockingKeys.DblpBaseName, context("conf/sigir/N2", 0, nfd))
    assertEquals(
      kNfd,
      kNfc,
      "NFC and NFD spellings of one name land in different blocks and are never compared by J06"
    )
  }

  test("unicode-nfc-nfd: homonym suffix still splits off a decomposed name") {
    val nfd = "José García 0001"
    val k = BlockingKeys.keys(BlockingKeys.DblpBaseName, context("conf/sigir/N3", 0, nfd))
    assertEquals(k, List("José García"))
  }

  test("confusables: Cyrillic and Latin look-alikes stay distinct names, never a self pair") {
    val latin = "Synthetic Wang"
    val cyr = "Synthetic Wаng" // U+0430 CYRILLIC SMALL LETTER A
    val a = accepted(buildXml(rec(cyr)))
    assertEquals(a.work.authorNames.head, cyr, "confusable must not be transliterated")
    val w = work("conf/sigir/Conf1", List(latin, cyr))
    val t = TeamAssembler.assemble(List(w), mentions(w), limits, run).toOption.get.team
    PairExpansion.pairAt(t, 0, 1, run) match {
      case ExpandedPair.Candidate(p) =>
        assertEquals((p.left, p.right), (latin, cyr)) // 'a' U+0061 < U+0430
        assertEquals(p.positions.map(x => (x.leftPosition, x.rightPosition)), List((0, 1)))
      case other => fail(s"expected a candidate, got $other")
    }
  }

  test("entities: declared, predefined, decimal and hex references decode to the right text") {
    val ents = Map("eacute" -> "&#233;", "uuml" -> "&#252;")
    val xml =
      """<inproceedings key="conf/sigir/E1"><author>Ren&eacute; M&#xFC;ller</author><author>J&uuml;rgen</author><title>A &amp; B &lt;C&gt; &quot;q&quot; &apos;s&apos;</title><year>2020</year></inproceedings>"""
    val a = accepted(buildXml(xml, ents))
    assertEquals(a.work.authorNames, List("René Müller", "Jürgen"))
    assertEquals(a.work.title, "A & B <C> \"q\" 's'")
  }

  test("entities: undeclared named entity rejects the record with its name") {
    buildXml(rec("Synth&oslash;tic"), Map("eacute" -> "&#233;")) match {
      case Rejected(reason, detail) =>
        assertEquals(reason, "undefined_entity")
        assertEquals(detail, "oslash")
      case other => fail(s"expected undefined_entity, got $other")
    }
    assertEquals(DblpEntities.expand("a &nope; b", Map.empty), Left("nope"))
  }

  test("entities: nested and self-recursive declarations are not expanded exponentially") {
    // Billion-laughs shape: one expansion pass, then the parser (DTD disabled) refuses the rest.
    val ents = Map("lol" -> "&lol;&lol;", "a" -> "&b;", "b" -> "x")
    val t0 = System.nanoTime()
    val r1 = buildXml(rec("Synth &lol; etic"), ents)
    val r2 = buildXml(rec("Synth &a; etic"), ents)
    assert((System.nanoTime() - t0) < 5_000_000_000L)
    assert(r1.isInstanceOf[Rejected], r1)
    assert(r2.isInstanceOf[Rejected], r2)
    assertEquals(DblpEntities.expand("&lol;", ents), Right("&lol;&lol;"))
  }

  test("entities: external DTD declarations are not fetched (XXE)") {
    val xml =
      """<!DOCTYPE x [<!ENTITY ext SYSTEM "file:///etc/passwd">]><inproceedings key="conf/sigir/X1"><author>A</author><title>&ext;</title><year>2020</year></inproceedings>"""
    // The scanner only yields the record element, but the parser must also refuse a DOCTYPE.
    val parsed = new org.scholarchain.hadoop.dblp.DblpRecordParser().parse(xml)
    parsed match {
      case Right(f) => assert(!f.titles.exists(_.contains("root:")), f.titles)
      case Left(_)  => ()
    }
    // Declarations of SYSTEM entities are not picked up as replacement text.
    assertEquals(DblpEntities.declarations("""<!ENTITY ext SYSTEM "file:///etc/passwd">"""), Map())
  }

  test("non-bmp: supplementary characters by reference and raw UTF-8 survive intact") {
    val xml =
      "<inproceedings key=\"conf/sigir/B1\"><author>&#x1D49C;da Synthetic</author><author>😀 Raw</author><title>Emoji &#128512; and 𝐀</title><year>2020</year></inproceedings>"
    val a = accepted(buildXml(xml))
    assertEquals(a.work.authorNames.head, "𝒜da Synthetic")
    assertEquals(a.work.authorNames.head.codePointAt(0), 0x1d49c)
    assertEquals(a.work.authorNames(1), "😀 Raw")
    assertEquals(a.work.title, "Emoji 😀 and 𝐀")
    assertEquals(Contract.validate(a.work), Nil)
  }

  test("non-bmp: pair canonicalisation uses code point order, not UTF-16 order") {
    val fullwidthA = "Ａ Synthetic" // U+FF21
    val mathA = "𝐀 Synthetic" // U+1D400: UTF-16 sorts it first, code point last
    assert(fullwidthA.compareTo(mathA) > 0)
    assert(CodePointOrder.lessThan(fullwidthA, mathA))
    val w = work("conf/sigir/Cp1", List(mathA, fullwidthA))
    val t = TeamAssembler.assemble(List(w), mentions(w), limits, run).toOption.get.team
    PairExpansion.pairAt(t, 0, 1, run) match {
      case ExpandedPair.Candidate(p) =>
        assertEquals((p.left, p.right), (fullwidthA, mathA))
        assertEquals(
          p.positions,
          List(PairPosition.forNames(t.workId, mathA, 0, fullwidthA, 1).toOption.get)
        )
        assertEquals(p.positions.head.leftPosition, 1)
        assertEquals(p.positions.head.rightPosition, 0)
      case other => fail(s"expected a candidate, got $other")
    }
  }

  test("non-bmp: a lone surrogate in raw UTF-8 bytes is rejected as invalid encoding") {
    // CESU-style encoding of U+D800 (ED A0 80) is not valid UTF-8.
    val prefix = utf8("<inproceedings key=\"conf/sigir/S1\"><author>A")
    val suffix = utf8("</author><title>T</title><year>2020</year></inproceedings>")
    val bytes = prefix ++ Array(0xed.toByte, 0xa0.toByte, 0x80.toByte) ++ suffix
    val units = scanAll(bytes)
    assertEquals(units.map(_.status), List(RawRecord.Ok))
    assertEquals(MapperModel(units.head, Map.empty).getClass, classOf[Rejected])
    assertEquals(
      MapperModel(units.head, Map.empty).asInstanceOf[Rejected].reason,
      "invalid_encoding"
    )
  }

  test("bom: UTF-8 byte order mark before the XML declaration is tolerated") {
    val bytes = fixture("bom-utf8.xml")
    assertEquals(bytes.take(3).toList, List(0xef, 0xbb, 0xbf).map(_.toByte))
    val Right((enc, prolog)) =
      DblpEntities.readProlog(new ByteArrayInputStream(bytes), 1 << 20): @unchecked
    assertEquals(enc.toUpperCase, "UTF-8")
    val ents = DblpEntities.declarations(prolog)
    assertEquals(ents, Map("eacute" -> "&#233;"))
    val units = scanAll(bytes)
    assertEquals(units.size, 1)
    val a = accepted(MapperModel(units.head, ents, StandardCharsets.UTF_8))
    assertEquals(
      a.work.authorNames,
      List("Synthetic 𝒜da", "Synthetic José", "Synthetic José")
    )
    assert(!a.work.authorNames.exists(_.contains("﻿")))
    assert(!a.work.title.contains("﻿"))
    assertEquals(a.work.title, "[SYNTHETIC] Emoji 😀 and CJK 李明 in a title.")
  }

  test("bom: BOM without an XML declaration defaults to UTF-8") {
    val bytes = Array(0xef, 0xbb, 0xbf).map(_.toByte) ++ utf8("<dblp>")
    val Right((enc, _)) =
      DblpEntities.readProlog(new ByteArrayInputStream(bytes), 1 << 10): @unchecked
    assertEquals(enc, "UTF-8")
  }

  test("unicode-whitespace: NBSP, NEL and ideographic space collapse to one space in names") {
    val a = accepted(buildXml(rec("Synthetic \u0085　 Name")))
    assertEquals(a.work.authorNames.head, "Synthetic Name")
  }
}
