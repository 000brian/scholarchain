package org.scholarchain.er.normalize

import java.text.Normalizer
import java.util.Locale

import org.scholarchain.core.AuthorNameKey

/** Title normalization (`title-tokens/1`): markup removed, Unicode and punctuation folded, then
  * content-word tokens for title similarity.
  *
  * Rules, in order: (1) XML/HTML-like tags (`<i>`, `</sub>`) and TeX math delimiters `$` become
  * spaces; (2) NFKD, then combining marks dropped and lowercase (`Locale.ROOT`); (3) every
  * character that is not a letter or digit separates tokens; (4) tokens of one character and the
  * fixed English stop words below are dropped; (5) tokens are distinct and sorted.
  */
object TitleText {
  val Version = "title-tokens/1"

  private val Tag = """<[^<>]{1,40}>""".r

  val StopWords: Set[String] = Set(
    "a",
    "an",
    "and",
    "are",
    "as",
    "at",
    "be",
    "by",
    "for",
    "from",
    "in",
    "into",
    "is",
    "it",
    "of",
    "on",
    "or",
    "over",
    "the",
    "to",
    "towards",
    "toward",
    "via",
    "vs",
    "with",
    "without",
    "using",
    "based",
    "its",
    "their",
    "we",
    "our",
    "how",
    "what",
    "when",
    "why",
    "can",
    "do",
    "does",
    "not",
    "through",
    "under",
    "between",
    "beyond",
    "about",
    "new",
    "approach",
    "study"
  )

  def tokens(title: String): List[String] = {
    if (title == null) return Nil
    val noTags = Tag.replaceAllIn(title, " ").replace('$', ' ')
    val folded = Normalizer
      .normalize(noTags, Normalizer.Form.NFKD)
      .filterNot(c => Character.getType(c) == Character.NON_SPACING_MARK)
      .toLowerCase(Locale.ROOT)
    val sb = new StringBuilder(folded.length)
    folded.foreach(c => sb.append(if (Character.isLetterOrDigit(c)) c else ' '))
    sb.toString
      .split(' ')
      .iterator
      .filter(t => t.length > 1 && !StopWords(t))
      .toList
      .distinct
      .sorted
  }

  /** Jaccard similarity of two sorted distinct int arrays; `None` when either is empty. */
  def jaccard(a: Array[Int], b: Array[Int]): Option[Double] =
    if (a.isEmpty || b.isEmpty) None
    else {
      var i = 0
      var j = 0
      var inter = 0
      while (i < a.length && j < b.length) {
        if (a(i) == b(j)) { inter += 1; i += 1; j += 1 }
        else if (a(i) < b(j)) i += 1
        else j += 1
      }
      Some(inter.toDouble / (a.length + b.length - inter))
    }
}

/** The parts of a dblp name used by the name features, from the shared core rule [[AuthorNameKey]]
  * (`author-name-key/1`): folded tokens of the base name, given names (every token but the last)
  * and surname (the last token).
  */
final case class NameParts(
    fullKey: String,
    baseKey: String,
    suffix: Option[String],
    folded: String,
    givens: Vector[String],
    surname: String
)

object NameParts {
  def of(name: String): NameParts = {
    val toks = AuthorNameKey.tokens(name).toVector
    NameParts(
      AuthorNameKey.fullNameKey(name),
      AuthorNameKey.baseNameKey(name),
      AuthorNameKey.homonymSuffix(name),
      toks.mkString(" "),
      if (toks.isEmpty) Vector.empty else toks.init,
      toks.lastOption.getOrElse("")
    )
  }

  /** Given-name relation: 2 when the folded given names are equal, 1 when compatible (each given
    * name of the shorter list matches, in order, a given name of the longer list exactly or as an
    * initial, and the first given names match), 0 otherwise (a given-name conflict such as `Jane`
    * vs `John`, or `Wei` vs `Wen`).
    */
  def givenNameRelation(a: NameParts, b: NameParts): Int =
    if (a.givens == b.givens) 2
    else {
      val (s, l) =
        if (a.givens.size <= b.givens.size) (a.givens, b.givens) else (b.givens, a.givens)
      if (s.isEmpty) 1
      else if (!compatible(s.head, l.head)) 0
      else {
        // s.tail must be a subsequence of l.tail under token compatibility
        var j = 1
        var ok = true
        var i = 1
        while (ok && i < s.size) {
          while (j < l.size && !compatible(s(i), l(j))) j += 1
          if (j >= l.size) ok = false else { j += 1; i += 1 }
        }
        if (ok) 1 else 0
      }
    }

  private def firstCodePoint(s: String): Int = s.codePointAt(0)

  /** Two folded tokens are compatible when equal, or when one is a single grapheme initial equal to
    * the first letter of the other.
    */
  def compatible(x: String, y: String): Boolean =
    x == y || (isInitial(x) && firstCodePoint(x) == firstCodePoint(y)) ||
      (isInitial(y) && firstCodePoint(y) == firstCodePoint(x))

  private def isInitial(t: String): Boolean =
    t.nonEmpty && t.codePointCount(0, t.length) == 1
}

/** Jaro-Winkler similarity (prefix scale 0.1, prefix length up to 4) over UTF-16 code units. */
object JaroWinkler {
  def similarity(s1: String, s2: String): Double = {
    if (s1 == s2) return 1.0
    if (s1.isEmpty || s2.isEmpty) return 0.0
    val range = math.max(0, math.max(s1.length, s2.length) / 2 - 1)
    val m1 = new Array[Boolean](s1.length)
    val m2 = new Array[Boolean](s2.length)
    var matches = 0
    var i = 0
    while (i < s1.length) {
      val lo = math.max(0, i - range)
      val hi = math.min(s2.length - 1, i + range)
      var j = lo
      var found = false
      while (!found && j <= hi) {
        if (!m2(j) && s1.charAt(i) == s2.charAt(j)) {
          m1(i) = true; m2(j) = true; matches += 1; found = true
        }
        j += 1
      }
      i += 1
    }
    if (matches == 0) return 0.0
    var t = 0
    var k = 0
    i = 0
    while (i < s1.length) {
      if (m1(i)) {
        while (!m2(k)) k += 1
        if (s1.charAt(i) != s2.charAt(k)) t += 1
        k += 1
      }
      i += 1
    }
    val m = matches.toDouble
    val jaro = (m / s1.length + m / s2.length + (m - t / 2.0) / m) / 3.0
    var prefix = 0
    while (
      prefix < 4 && prefix < s1.length && prefix < s2.length && s1
        .charAt(prefix) == s2.charAt(prefix)
    )
      prefix += 1
    jaro + prefix * 0.1 * (1.0 - jaro)
  }
}
