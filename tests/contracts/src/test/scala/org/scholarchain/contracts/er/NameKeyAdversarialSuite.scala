package org.scholarchain.contracts.er

import org.scholarchain.core.AuthorNameKey
import org.scholarchain.er.normalize.NameParts
import org.scholarchain.hadoop.blocking.{BlockMember, FallbackKeys}
import org.scholarchain.hadoop.candidates.CandidateFeatures

/** Adversarial attacks on the shared author-name key rule (`author-name-key/1`, finding 10) and on
  * its two consumers (hadoop J05/J06 and entity resolution). Decision 18: NFC/NFD and punctuation
  * variants share a key; Cyrillic/Latin look-alikes do not; initials collisions.
  */
class NameKeyAdversarialSuite extends munit.FunSuite {
  import AuthorNameKey.*

  private val NfcJose = "José García"
  private val NfdJose = "José García"

  test("NFC and NFD spellings share every key") {
    assertNotEquals(NfcJose, NfdJose) // different code points
    assertEquals(fullNameKey(NfcJose), fullNameKey(NfdJose))
    assertEquals(baseNameKey(NfcJose + " 0007"), baseNameKey(NfdJose + " 0007"))
    assertEquals(foldedName(NfcJose), "jose garcia")
    assertEquals(foldedName(NfdJose), "jose garcia")
    assertEquals(initialsSurnameKey(NfcJose), Some("j garcia"))
    assertEquals(initialsSurnameKey(NfdJose), Some("j garcia"))
  }

  test("punctuation and apostrophe variants share the folded and initials keys") {
    val variants = List(
      "Jean-Pierre O'Brien",
      "Jean Pierre OBrien",
      "Jean–Pierre O’Brien", // en dash, right single quotation mark
      "Jean-Pierre OʼBrien", // modifier letter apostrophe
      "Jean.Pierre O`Brien",
      "  Jean-Pierre\tO'Brien  "
    )
    variants.foreach { v =>
      assertEquals(foldedName(v), "jean pierre obrien", v)
      assertEquals(initialsSurnameKey(v), Some("j p obrien"), v)
    }
    assertEquals(tokens("J.R. Smith"), tokens("J. R. Smith"))
    assertEquals(initialsSurnameKey("J.R. Smith"), Some("j r smith"))
  }

  test("Unicode whitespace variants (NBSP, tabs, runs) share the full-name key") {
    val k = fullNameKey("Wei Wang")
    List("Wei Wang", "Wei\tWang", "Wei   Wang", " Wei Wang ", "Wei Wang").foreach { v =>
      assertEquals(fullNameKey(v), k, v)
    }
  }

  test("Cyrillic, Greek and Ukrainian look-alikes never share a key with Latin") {
    val pairs = List(
      "Anna Smith" -> "Аnna Smith", // Cyrillic capital A
      "Anna Smith" -> "Anna Ѕmith", // Cyrillic DZE looks like S
      "Alpha Beta" -> "Αlpha Beta", // Greek capital alpha
      "Ivan Ivanov" -> "Іvan Ivanov", // Ukrainian I
      "Oleg Popov" -> "Oleg Pоpov" // Cyrillic small o
    )
    pairs.foreach { case (latin, mixed) =>
      assertNotEquals(fullNameKey(latin), fullNameKey(mixed), mixed)
      assertNotEquals(baseNameKey(latin), baseNameKey(mixed), mixed)
      assertNotEquals(foldedName(latin), foldedName(mixed), mixed)
      assertNotEquals(initialsSurnameKey(latin), initialsSurnameKey(mixed), mixed)
    }
    // A combining mark on a Cyrillic letter is kept: й and и stay different.
    assertNotEquals(foldedName("Игорь"), foldedName("Йгорь"))
  }

  test("compatibility characters are not folded (no NFKD): fullwidth J is not J") {
    assertNotEquals(foldedName("Ｊohn Smith"), foldedName("John Smith"))
  }

  test("initials collisions: J. Smith, John Smith, Jane Smith share only the initials key") {
    val names = List("J. Smith", "John Smith", "Jane Smith", "John Smith 0003")
    names.foreach(n => assertEquals(initialsSurnameKey(n), Some("j smith"), n))
    assertEquals(names.map(fullNameKey).distinct.size, 4)
    assertEquals(baseNameKey("John Smith 0003"), baseNameKey("John Smith"))
    assertNotEquals(baseNameKey("Jane Smith"), baseNameKey("John Smith"))
    // entity resolution reads the same rule: compatible initials, conflicting full given names
    def rel(a: String, b: String) = NameParts.givenNameRelation(NameParts.of(a), NameParts.of(b))
    assertEquals(rel("J. Smith", "John Smith"), 1)
    assertEquals(rel("J. Smith", "Jane Smith"), 1)
    assertEquals(rel("John Smith", "Jane Smith"), 0)
    assertEquals(rel("John Smith", "John Smith 0003"), 2)
  }

  test("homonym suffix is exactly a trailing space plus four ASCII digits") {
    assertEquals(split("Wei Wang 0001"), ("Wei Wang", Some("0001")))
    assertEquals(homonymSuffix("Wei Wang 001"), None)
    assertEquals(homonymSuffix("Wei Wang 00012"), None)
    assertEquals(homonymSuffix("Wei Wang0001"), None)
    assertEquals(homonymSuffix("0001"), None)
    assertEquals(homonymSuffix("Wei Wang ١٢٣٤"), None) // Arabic-Indic digits
    assertEquals(baseNameKey("Wei  Wang   0002"), "Wei Wang")
  }

  test("degenerate names are total: null, empty, punctuation only") {
    assertEquals(fullNameKey(null), "")
    assertEquals(tokens(null), Nil)
    assertEquals(initialsSurnameKey(null), None)
    assertEquals(initialsSurnameKey("..."), None)
    assertEquals(initialsSurnameKey("- . -"), None)
    assertEquals(initialsSurnameKey("Madonna"), Some("madonna"))
  }

  test("entity resolution NameParts uses the core keys verbatim") {
    List(NfcJose, NfdJose + " 0002", "Jean-Pierre O'Brien", "Аnna Smith").foreach { n =>
      val p = NameParts.of(n)
      assertEquals(p.fullKey, fullNameKey(n))
      assertEquals(p.baseKey, baseNameKey(n))
      assertEquals(p.suffix, homonymSuffix(n))
      assertEquals(p.folded, foldedName(n))
    }
  }

  private def member(id: Int, name: String, coauthors: List[String]) =
    BlockMember(
      f"mention:v1:$id%032x",
      f"work:v1:$id%032x",
      s"conf/sigir/NameKey$id",
      name,
      2020,
      None,
      Some("conf/sigir"),
      coauthors,
      false
    )

  test("hadoop J06 and J05b compare names by the same rule (NFC = NFD, Cyrillic != Latin)") {
    def exact(a: String, b: String) =
      CandidateFeatures
        .compute(member(1, a, Nil), member(2, b, Nil))
        .find(_.name == CandidateFeatures.NameExactMatch)
        .map(_.value)
    assertEquals(exact(NfcJose, NfdJose), Some(1.0))
    assertEquals(exact("Anna Smith", "Аnna Smith"), Some(0.0))
    def shared(a: List[String], b: List[String]) =
      CandidateFeatures
        .compute(member(1, "X Y", a), member(2, "X Y", b))
        .find(_.name == CandidateFeatures.CoauthorSharedCount)
        .map(_.value)
    assertEquals(shared(List(NfcJose), List(NfdJose)), Some(1.0))
    assertEquals(shared(List("Anna Smith"), List("Аnna Smith")), Some(0.0))
    assertEquals(
      FallbackKeys.values(FallbackKeys.Coauthor, member(1, "X", List(NfcJose))),
      FallbackKeys.values(FallbackKeys.Coauthor, member(2, "X", List(NfdJose)))
    )
  }
}
