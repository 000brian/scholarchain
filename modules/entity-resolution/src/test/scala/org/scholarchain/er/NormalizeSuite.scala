package org.scholarchain.er

import org.scholarchain.er.io.Csv
import org.scholarchain.er.normalize.*

class NormalizeSuite extends munit.FunSuite {

  test("arXiv IDs from DataCite DOIs, abs and pdf links, versions dropped, old style kept") {
    assertEquals(ArxivId.fromDoi("10.48550/arxiv.2101.00001"), Some("2101.00001"))
    assertEquals(ArxivId.fromLink("https://doi.org/10.48550/arXiv.2312.12345"), Some("2312.12345"))
    assertEquals(ArxivId.fromLink("https://arxiv.org/abs/2101.00001v3"), Some("2101.00001"))
    assertEquals(ArxivId.fromLink("http://arxiv.org/pdf/1706.03762.pdf"), Some("1706.03762"))
    assertEquals(ArxivId.fromLink("https://arxiv.org/abs/cs/0101001v1"), Some("cs/0101001"))
    assertEquals(ArxivId.normalize("arXiv:math.AG/0601001"), Some("math.AG/0601001"))
    assertEquals(ArxivId.fromLink("https://example.org/abs/2101.00001"), None)
    assertEquals(ArxivId.fromDoi("10.1145/3336191.3371867"), None)
  }

  test("landing URLs are normalized and exclude DOI and arXiv links") {
    assertEquals(
      LandingUrl.fromLink("http://WWW.Example.org:80/Paper/42/#sec2"),
      Some("https://example.org/Paper/42")
    )
    assertEquals(
      LandingUrl.fromLink("https://example.org/view?id=7"),
      Some("https://example.org/view?id=7")
    )
    assertEquals(LandingUrl.fromLink("https://doi.org/10.1145/1.2"), None)
    assertEquals(LandingUrl.fromLink("https://arxiv.org/abs/2101.00001"), None)
    assertEquals(LandingUrl.fromLink("db/conf/sigir/sigir2023.html#X"), None)
    val ids = WorkIdentifiers.of(
      Some("10.48550/arxiv.2101.00001"),
      List("https://doi.org/10.48550/arXiv.2101.00001", "https://openreview.net/forum?id=abc")
    )
    assertEquals(
      ids,
      WorkIdentifiers(
        Some("10.48550/arxiv.2101.00001"),
        Some("2101.00001"),
        List("https://openreview.net/forum?id=abc")
      )
    )
  }

  test("title tokens drop markup, fold Unicode and drop stop words; NFC and NFD agree") {
    assertEquals(
      TitleText.tokens("<i>Deep</i> Learning for the Web: A Study"),
      List("deep", "learning", "web")
    )
    assertEquals(TitleText.tokens("Café Graphs"), TitleText.tokens("Café graphs"))
    assertEquals(TitleText.jaccard(Array(1, 2, 3), Array(2, 3, 4)), Some(0.5))
    assertEquals(TitleText.jaccard(Array.empty, Array(1)), None)
  }

  test("given-name relation: equal variants, compatible initials, conflicts") {
    def rel(a: String, b: String) = NameParts.givenNameRelation(NameParts.of(a), NameParts.of(b))
    assertEquals(rel("Jean-Pierre Dupont", "Jean Pierre Dupont"), 2)
    assertEquals(rel("José Smith", "Jose Smith"), 2)
    assertEquals(rel("Wei Wang", "Wei Wang 0001"), 2)
    assertEquals(rel("J. Smith", "John Smith"), 1)
    assertEquals(rel("John Smith", "John Q. Smith"), 1)
    assertEquals(rel("Jane Smith", "John Smith"), 0)
    assertEquals(rel("Wei Wang", "Wen Wang"), 0)
    assertEquals(NameParts.of("Hugo O. Sousa").surname, "sousa")
  }

  test("Cyrillic and Latin look-alikes stay different") {
    val latin = NameParts.of("Anna Petrova")
    val cyr = NameParts.of("Аnna Petrova")
    assertNotEquals(latin.folded, cyr.folded)
    assertEquals(NameParts.givenNameRelation(latin, cyr), 0)
  }

  test("Jaro-Winkler basics") {
    assertEquals(JaroWinkler.similarity("abc", "abc"), 1.0)
    assertEquals(JaroWinkler.similarity("", "abc"), 0.0)
    assert(math.abs(JaroWinkler.similarity("martha", "marhta") - 0.9611) < 1e-3)
  }

  test("CSV round trip with quotes, commas and newlines") {
    val rows = Vector(Vector("a", "b,c", "say \"hi\""), Vector("line\nbreak", "", "x"))
    val text = rows.map(Csv.row).mkString("\r\n") + "\r\n"
    assertEquals(Csv.parse(text), Right(rows))
    assert(Csv.parse("\"open").isLeft)
  }
}
