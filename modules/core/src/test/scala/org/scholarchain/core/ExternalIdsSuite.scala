package org.scholarchain.core

/** External identifier canonical forms; cases from datasets/schemas/identifiers-and-linkage.md. */
class ExternalIdsSuite extends munit.FunSuite {

  // ---- DOI ------------------------------------------------------------------------------
  test("DOI: resolver URL is stripped and ASCII uppercase is lowered (dblp <ee>)") {
    assertEquals(
      Doi.normalize("https://doi.org/10.1109/DAC56929.2023.10247992"),
      Some("10.1109/dac56929.2023.10247992")
    )
  }

  test("DOI: every documented prefix is stripped case-insensitively") {
    val bare = "10.1145/3539618.3591892"
    List(
      "https://doi.org/",
      "http://doi.org/",
      "https://dx.doi.org/",
      "http://dx.doi.org/",
      "doi:",
      "HTTPS://DOI.ORG/",
      "DOI:"
    ).foreach(p => assertEquals(Doi.normalize(p + bare), Some(bare), p))
  }

  test("DOI: mixed-case arXiv DOI from ORCID normalises") {
    assertEquals(Doi.normalize("10.48550/arXiv.2301.04643"), Some("10.48550/arxiv.2301.04643"))
  }

  test("DOI: URL forms are percent-decoded exactly once") {
    assertEquals(
      Doi.normalize("https://doi.org/10.5555/scholarchain%2Dsynthetic%2D0005"),
      Some("10.5555/scholarchain-synthetic-0005")
    )
    assertEquals(Doi.normalize("https://doi.org/10.5555/a%252Db"), Some("10.5555/a%2db"))
  }

  test("DOI: the doi: form and bare DOIs are not percent-decoded") {
    assertEquals(Doi.normalize("doi:10.5555/a%2Db"), Some("10.5555/a%2db"))
    assertEquals(Doi.normalize("10.5555/a%2Db"), Some("10.5555/a%2db"))
  }

  test("DOI: percent-decoding is UTF-8 and '+' stays '+'") {
    assertEquals(Doi.normalize("https://doi.org/10.5555/%C3%A9+x"), Some("10.5555/é+x"))
  }

  test("DOI: only ASCII A-Z is lowercased (DOI Handbook rule)") {
    assertEquals(Doi.normalize("10.5555/ÄBC"), Some("10.5555/Äbc"))
  }

  test("DOI: non-DOIs are rejected, never repaired") {
    List(
      null,
      "",
      "https://www.wikidata.org/entity/Q131012499",
      "11.1145/x",
      "10.1145",
      "10./x",
      "10.1145/a b",
      "https://doi.org/10.1145/a%20b"
    ).foreach(r => assertEquals(Doi.normalize(r), None, String.valueOf(r)))
  }

  test("DOI: only doi.org resolver URLs in dblp <ee> carry a DOI") {
    assertEquals(Doi.fromDblpEe("https://www.wikidata.org/entity/Q131012499"), None)
    assertEquals(Doi.fromDblpEe("doi:10.1145/x"), None)
    assertEquals(Doi.fromDblpEe("https://dx.doi.org/10.1145/X"), Some("10.1145/x"))
  }

  test("DOI: isCanonical") {
    assert(Doi.isCanonical("10.1145/3539618.3591892"))
    assert(!Doi.isCanonical("10.1109/DAC56929.2023.10247992"))
    assert(!Doi.isCanonical("https://doi.org/10.1145/3539618.3591892"))
    assert(!Doi.isCanonical(" 10.1145/x"))
  }

  // ---- ORCID ----------------------------------------------------------------------------
  test("ORCID: valid iDs from the samples are accepted; URI prefix is stripped") {
    assertEquals(Orcid.normalize("0000-0003-3226-9189"), Some("0000-0003-3226-9189"))
    assertEquals(
      Orcid.normalize("https://orcid.org/0000-0002-8767-8126"),
      Some("0000-0002-8767-8126")
    )
    assertEquals(
      Orcid.normalize("HTTP://ORCID.ORG/0000-0002-5475-1382"),
      Some("0000-0002-5475-1382")
    )
  }

  test("ORCID: a lowercase x check character is uppercased") {
    assertEquals(Orcid.normalize("0000-0001-8209-698x"), Some("0000-0001-8209-698X"))
  }

  test("ORCID: the ISO 7064 11,2 check digit is enforced") {
    assertEquals(Orcid.checkDigit("000000021825009"), '7')
    assertEquals(Orcid.normalize("0000-0000-0000-0000"), None) // synthetic invalid iD
    assertEquals(Orcid.normalize("0000-0003-3226-9188"), None)
  }

  test("ORCID: wrong shapes and non-ASCII digits are rejected") {
    List(
      null,
      "",
      "0000000332269189",
      "0000-0003-3226-918",
      "0000-0003-3226-9189-0000",
      "٠٠٠٠-0003-3226-9189"
    )
      .foreach(r => assertEquals(Orcid.normalize(r), None, String.valueOf(r)))
  }

  // ---- OpenAlex -------------------------------------------------------------------------
  test("OpenAlex: URL prefix is stripped and the key uppercased") {
    assertEquals(OpenAlexId.Work.normalize("https://openalex.org/W4366851189"), Some("W4366851189"))
    assertEquals(OpenAlexId.Author.normalize("a5089440969"), Some("A5089440969"))
    assertEquals(
      OpenAlexId.Source.normalize("https://openalex.org/S4363608773"),
      Some("S4363608773")
    )
    assertEquals(OpenAlexId.Institution.normalize("I182534213"), Some("I182534213"))
  }

  test("OpenAlex topic: URL prefix is stripped, key uppercased, T[1-9][0-9]* required") {
    assertEquals(OpenAlexId.Topic.letter, 'T')
    assertEquals(OpenAlexId.Topic.normalize("https://openalex.org/T10181"), Some("T10181"))
    assertEquals(OpenAlexId.Topic.normalize("HTTPS://OPENALEX.ORG/t10181"), Some("T10181"))
    assertEquals(OpenAlexId.Topic.normalize(" t11636 "), Some("T11636"))
    assert(OpenAlexId.Topic.isCanonical("T10181"))
    assert(!OpenAlexId.Topic.isCanonical("t10181"))
    assert(!OpenAlexId.Topic.isCanonical("https://openalex.org/T10181"))
    List("T0123", "T", "W4366851189", "T12a", "", null).foreach(r =>
      assertEquals(OpenAlexId.Topic.normalize(r), None, String.valueOf(r))
    )
    assertEquals(OpenAlexId.Work.normalize("T10181"), None)
  }

  test("OpenAlex: every entity kind is listed once with a distinct letter") {
    assertEquals(OpenAlexId.entities.map(_.letter), List('W', 'A', 'S', 'I', 'T'))
  }

  test("OpenAlex: wrong entity letter, leading zero, and garbage are rejected") {
    assertEquals(OpenAlexId.Work.normalize("A5089440969"), None)
    assertEquals(OpenAlexId.Work.normalize("W0123"), None)
    assertEquals(OpenAlexId.Work.normalize("W"), None)
    assertEquals(OpenAlexId.Work.normalize(null), None)
  }

  // ---- dblp -----------------------------------------------------------------------------
  test("dblp keys are opaque and case-sensitive; structure is checked") {
    assert(DblpKey.isValid("conf/sigir/Sousa0J23"))
    assert(DblpKey.isValid("reference/vision/X14bd"))
    assert(DblpKey.isValid("dblpnote/neverpublished"))
    assert(!DblpKey.isValid("Conf/sigir/x"))
    assert(!DblpKey.isValid("conf"))
    assert(!DblpKey.isValid("conf//x"))
    assert(!DblpKey.isValid("conf/sigir/a b"))
    assert(DblpKey.usesObservedAlphabet("conf/sigir/HofstatterC0Z23"))
    assert(!DblpKey.usesObservedAlphabet("conf/sigir/é"))
    assertEquals(DblpKey.streamKey("conf/sigir/Sousa0J23"), "conf/sigir")
  }

  test("dblp author names: whitespace collapsed, homonym suffix kept") {
    assertEquals(DblpAuthorName.normalize("  Ricardo\t Campos\n0001 "), Some("Ricardo Campos 0001"))
    assertEquals(DblpAuthorName.normalize("Alípio Mário Jorge"), Some("Alípio Mário Jorge"))
    assertEquals(DblpAuthorName.normalize("   "), None)
    assert(DblpAuthorName.isCanonical("Ricardo Campos 0001"))
    assert(!DblpAuthorName.isCanonical("Ricardo  Campos"))
    assertEquals(DblpAuthorName.split("Ricardo Campos 0001"), ("Ricardo Campos", Some("0001")))
    assertEquals(DblpAuthorName.split("Zhe Lin 0007"), ("Zhe Lin", Some("0007")))
    assertEquals(DblpAuthorName.split("Hugo O. Sousa"), ("Hugo O. Sousa", None))
    assertEquals(DblpAuthorName.split("Agent 007"), ("Agent 007", None))
  }
}
