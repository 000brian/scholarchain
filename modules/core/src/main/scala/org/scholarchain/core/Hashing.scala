package org.scholarchain.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Deterministic hashing primitives used by every stable identifier and content hash.
  *
  * Nothing here reads the clock, randomness, the environment, or the host.
  */
object Hashing {

  private val HexDigits = "0123456789abcdef".toCharArray

  /** Lowercase hexadecimal SHA-256 of the given bytes (64 characters). */
  def sha256Hex(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = new Array[Char](digest.length * 2)
    var i = 0
    while (i < digest.length) {
      val b = digest(i) & 0xff
      out(2 * i) = HexDigits(b >>> 4)
      out(2 * i + 1) = HexDigits(b & 0x0f)
      i += 1
    }
    new String(out)
  }

  /** Lowercase hexadecimal SHA-256 of the UTF-8 encoding of `text`. */
  def sha256Hex(text: String): String = sha256Hex(text.getBytes(StandardCharsets.UTF_8))

  /** Unambiguous canonical encoding of an ordered list of components.
    *
    * Each component is written as `<n>:<component>` where `<n>` is the decimal length of the
    * component's UTF-8 encoding; the encoded components are concatenated with no separator.
    * Example: `Seq("work/v1", "dblp")` encodes as `7:work/v14:dblp`. Because every component is
    * length-prefixed, `Seq("a|b", "c")` and `Seq("a", "b|c")` never collide.
    */
  def canonicalEncoding(components: Seq[String]): String = {
    val sb = new StringBuilder
    components.foreach { c =>
      sb.append(c.getBytes(StandardCharsets.UTF_8).length).append(':').append(c)
    }
    sb.toString
  }

  /** First 32 lowercase hex characters (128 bits) of SHA-256 over `canonicalEncoding`. */
  def digest32(components: Seq[String]): String =
    sha256Hex(canonicalEncoding(components)).substring(0, 32)
}

/** Content hashes carried in `Provenance.contentHash`: `sha256:<64 lowercase hex>` of UTF-8. */
object ContentHash {
  val Prefix = "sha256:"

  def of(text: String): String = Prefix + Hashing.sha256Hex(text)

  def isWellFormed(s: String): Boolean =
    s != null && s.length == Prefix.length + 64 && s.startsWith(Prefix) &&
      s.substring(Prefix.length).forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))
}

/** Canonical string ordering for undirected pairs: lexicographic by Unicode code point.
  *
  * This is the ordering of Python `str` comparison (used by `datasets/scripts/dslib.py`
  * `canonical_pair`). It differs from `java.lang.String.compareTo`, which compares UTF-16 code
  * units, for characters above U+FFFF versus characters in U+E000..U+FFFF.
  */
object CodePointOrder {

  def compare(a: String, b: String): Int = {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
      val ca = a.codePointAt(i)
      val cb = b.codePointAt(j)
      if (ca != cb) return Integer.compare(ca, cb)
      i += Character.charCount(ca)
      j += Character.charCount(cb)
    }
    val aDone = i >= a.length
    val bDone = j >= b.length
    if (aDone && bDone) 0 else if (aDone) -1 else 1
  }

  def lessThan(a: String, b: String): Boolean = compare(a, b) < 0

  val ordering: Ordering[String] = new Ordering[String] {
    def compare(x: String, y: String): Int = CodePointOrder.compare(x, y)
  }
}

/** Undirected pair canonicalization.
  *
  * Rule: the canonical pair of `{x, y}` is `(min, max)` under [[CodePointOrder]], and the stored
  * pair must satisfy `left < right` strictly. Equal endpoints (self pairs) have no canonical form
  * and are rejected.
  */
object UndirectedPair {

  def canonical(x: String, y: String): Either[String, (String, String)] = {
    val c = CodePointOrder.compare(x, y)
    if (c == 0) Left(s"self pair is not allowed: '$x'")
    else if (c < 0) Right((x, y))
    else Right((y, x))
  }

  /** True when `(left, right)` is already in canonical order (strictly increasing). */
  def isCanonical(left: String, right: String): Boolean = CodePointOrder.lessThan(left, right)
}
