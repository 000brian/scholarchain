package org.scholarchain.core

/** Undirected pair canonicalization: (min, max) by Unicode code point, strict, no self pairs. */
class PairOrderingSuite extends munit.FunSuite {

  test("canonical pair is (min, max) regardless of argument order") {
    assertEquals(UndirectedPair.canonical("b", "a"), Right(("a", "b")))
    assertEquals(UndirectedPair.canonical("a", "b"), Right(("a", "b")))
  }

  test("self pairs are rejected") {
    assert(UndirectedPair.canonical("a", "a").isLeft)
    assert(!UndirectedPair.isCanonical("a", "a"))
  }

  test("ordering is by code point, matching Python str comparison, not UTF-16 compareTo") {
    val fullwidthA = "Ａ" // U+FF21
    val grinning = "😀" // U+1F600, a surrogate pair in UTF-16
    // UTF-16 code units: 0xD83D < 0xFF21, so compareTo puts the emoji first.
    assert(grinning.compareTo(fullwidthA) < 0)
    // Code points: U+FF21 < U+1F600.
    assert(CodePointOrder.lessThan(fullwidthA, grinning))
    assertEquals(UndirectedPair.canonical(grinning, fullwidthA), Right((fullwidthA, grinning)))
  }

  test("a prefix sorts before its extensions; case is significant") {
    assert(CodePointOrder.lessThan("Zhe Lin", "Zhe Lin 0007"))
    assert(CodePointOrder.lessThan("Zhe", "zhe"))
    assertEquals(CodePointOrder.compare("same", "same"), 0)
  }

  test("ordering of accented names follows code points") {
    // 'A' (U+0041) < 'H' (U+0048); 'Á' (U+00C1) > 'z' (U+007A).
    assert(CodePointOrder.lessThan("Alípio Mário Jorge", "Hugo O. Sousa"))
    assert(CodePointOrder.lessThan("zeta", "Ávila"))
  }
}
