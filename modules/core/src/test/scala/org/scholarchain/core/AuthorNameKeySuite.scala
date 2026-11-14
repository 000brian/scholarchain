package org.scholarchain.core

import java.text.Normalizer

/** The shared author-name key rule (finding 10). Runs under Scala 3 and Scala 2.13. */
class AuthorNameKeySuite extends munit.FunSuite {
  import AuthorNameKey._

  val joseNfc = "José García"
  val joseNfd = "José García"

  /** The Phase 4 Hadoop key functions, copied verbatim from `BlockingKeys` and `CandidateFeatures`
    * (hadoop `dblp-base-name` and name equality), for parity.
    */
  def hadoopCanonical(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
  def hadoopBaseNameKey(name: String): String = hadoopCanonical(DblpAuthorName.split(name)._1)

  val dblpNames: List[String] = List(
    "Hugo O. Sousa",
    "Ricardo Campos 0001",
    "Alípio Mário Jorge",
    joseNfc,
    joseNfd + " 0001",
    "Wei Wang 0001",
    "Wei Wang",
    "Jean-Pierre O'Brien",
    "Аlexey Ivanov",
    "王伟",
    "Zoë Smith-Jones 0123",
    "J. R. R. Tolkien"
  )

  test("version is frozen") {
    assertEquals(Version, "author-name-key/1")
  }

  test("parity: baseNameKey and fullNameKey equal the Phase 4 Hadoop keys on dblp names") {
    dblpNames.foreach { n =>
      assert(DblpAuthorName.isCanonical(n), n)
      assertEquals(baseNameKey(n), hadoopBaseNameKey(n), n)
      assertEquals(fullNameKey(n), hadoopCanonical(n), n)
      assertEquals(canonical(n), hadoopCanonical(n), n)
    }
  }

  test("homonym suffix split") {
    assertEquals(split("Wei Wang 0001"), ("Wei Wang", Some("0001")))
    assertEquals(split("Wei Wang"), ("Wei Wang", None))
    assertEquals(split("  Wei   Wang  0002 "), ("Wei Wang", Some("0002")))
    assertEquals(homonymSuffix("Ricardo Campos 0001"), Some("0001"))
    assertEquals(homonymSuffix("Ricardo Campos 001"), None)
    assertEquals(homonymSuffix("0001"), None)
    assertEquals(baseNameKey("Wei Wang 0001"), "Wei Wang")
    assertEquals(baseNameKey("Wei Wang 0001"), baseNameKey("Wei Wang 0002"))
    assertNotEquals(fullNameKey("Wei Wang 0001"), fullNameKey("Wei Wang 0002"))
  }

  test("NFC and NFD spellings share every key; the key form is NFD") {
    assertNotEquals(joseNfc, joseNfd)
    assertEquals(baseNameKey(joseNfc), baseNameKey(joseNfd))
    assertEquals(baseNameKey(joseNfc + " 0001"), joseNfd)
    assertEquals(fullNameKey(joseNfc), fullNameKey(joseNfd))
    assertEquals(foldedName(joseNfc), foldedName(joseNfd))
    assertEquals(initialsSurnameKey(joseNfc), initialsSurnameKey(joseNfd))
  }

  test("whitespace: Unicode whitespace runs collapse to one space and are trimmed") {
    val nbsp = "Hugo O.  Sousa\t"
    assertEquals(collapseWhitespace(nbsp), "Hugo O. Sousa")
    assertEquals(baseNameKey(nbsp), baseNameKey("Hugo O. Sousa"))
    assertEquals(fullNameKey(nbsp), "Hugo O. Sousa")
  }

  test("canonical keys keep case and punctuation (no matching policy in them)") {
    assertNotEquals(baseNameKey("Hugo O. Sousa"), baseNameKey("Hugo O Sousa"))
    assertNotEquals(baseNameKey("hugo o. sousa"), baseNameKey("Hugo O. Sousa"))
  }

  test("folded tokens: case, Latin diacritics, apostrophes, punctuation, suffix") {
    assertEquals(tokens("Jean-Pierre O'Brien 0002"), List("jean", "pierre", "obrien"))
    assertEquals(tokens("Jean-Pierre O’Brien"), List("jean", "pierre", "obrien"))
    assertEquals(tokens("J.R.R. Tolkien"), List("j", "r", "r", "tolkien"))
    assertEquals(tokens(joseNfc), List("jose", "garcia"))
    assertEquals(foldedName("Hugo O. Sousa"), "hugo o sousa")
    assertEquals(foldedName("Hugo O Sousa"), "hugo o sousa")
    assertEquals(foldedName("Zoë Smith-Jones 0123"), "zoe smith jones")
    assertEquals(tokens("..."), Nil)
    assertEquals(tokens(""), Nil)
    assertEquals(tokens(null), Nil)
  }

  test("folded tokens keep letters without a canonical decomposition") {
    assertEquals(foldedName("Søren Łukasz Straße"), "søren łukasz straße")
  }

  test("punctuation variants share the folded name and the initials key") {
    val variants = List("Hugo O. Sousa", "Hugo O Sousa", "Hugo  O.Sousa", "hugo o. sousa")
    assertEquals(variants.map(foldedName).distinct, List("hugo o sousa"))
    assertEquals(variants.map(initialsSurnameKey).distinct, List(Some("h o sousa")))
    assertEquals(
      initialsSurnameKey("Jean-Pierre Dupont"),
      initialsSurnameKey("Jean Pierre Dupont")
    )
  }

  test("initials plus surname: initials collisions are intended (a blocking key)") {
    val smiths = List("J. Smith", "John Smith", "Jane Smith", "John Smith 0003", "J Smith")
    assertEquals(smiths.map(initialsSurnameKey).distinct, List(Some("j smith")))
    assertNotEquals(initialsSurnameKey("John A. Smith"), initialsSurnameKey("John Smith"))
    assertEquals(initialsSurnameKey("Wei Wang 0001"), Some("w wang"))
    assertEquals(initialsSurnameKey("Mausam"), Some("mausam"))
    assertEquals(initialsSurnameKey(" - "), None)
  }

  test("Cyrillic and Latin look-alikes never share a key") {
    val latin = "Alexey Ivanov"
    val cyrillicA = "Аlexey Ivanov" // U+0410 CYRILLIC CAPITAL LETTER A
    val cyrillicAll = "Аеxеy Ivаnоv"
    List(cyrillicA, cyrillicAll).foreach { c =>
      assertNotEquals(baseNameKey(c), baseNameKey(latin))
      assertNotEquals(fullNameKey(c), fullNameKey(latin))
      assertNotEquals(foldedName(c), foldedName(latin))
    }
    assertNotEquals(initialsSurnameKey(cyrillicA), initialsSurnameKey(latin))
    // Greek capital alpha vs Latin A in an initial
    assertNotEquals(initialsSurnameKey("Α. Ivanov"), initialsSurnameKey("A. Ivanov"))
  }

  test("marks on non-Latin letters are kept: Cyrillic short i stays distinct from i") {
    val shortI = "Йван Иванов" // Йван Иванов
    val plainI = "Иван Иванов" // Иван Иванов
    assertNotEquals(foldedName(shortI), foldedName(plainI))
    assertNotEquals(initialsSurnameKey(shortI), initialsSurnameKey(plainI))
    assertEquals(initialsSurnameKey(shortI), Some("й иванов"))
  }

  test("no compatibility folding: ligatures and fullwidth letters are not folded") {
    assertNotEquals(foldedName("ﬁlip Smith"), foldedName("filip Smith"))
    assertNotEquals(foldedName("Ｊ. Smith"), foldedName("J. Smith"))
  }

  test("keys are deterministic and null-safe") {
    dblpNames.foreach { n =>
      assertEquals(initialsSurnameKey(n), initialsSurnameKey(new String(n.toCharArray)))
    }
    assertEquals(baseNameKey(null), "")
    assertEquals(fullNameKey(null), "")
    assertEquals(initialsSurnameKey(null), None)
  }
}
