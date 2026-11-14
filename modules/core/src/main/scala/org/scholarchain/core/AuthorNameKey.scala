package org.scholarchain.core

import java.text.Normalizer
import java.util.Locale

/** The shared author-name key rule (`author-name-key/1`), used by Hadoop blocking and features and
  * by entity resolution, so every stage derives the same key from the same name.
  *
  * Keys are for blocking and comparison only; source names are kept verbatim in every record. Every
  * function is pure and total (a `null` name is treated as empty).
  *
  * Rules, in order:
  *   1. Whitespace: every run of Unicode whitespace becomes one U+0020 space; the result is trimmed
  *      (the [[DblpAuthorName.normalize]] rule, a no-op on canonical dblp names).
  *   1. Homonym suffix: a trailing space plus four ASCII digits is the dblp homonym number
  *      ([[DblpAuthorName.split]]): `Wei Wang 0001 -> ("Wei Wang", Some("0001"))`.
  *   1. Canonical equivalence: Unicode Normalization Form D ([[canonical]]). Canonically equivalent
  *      spellings (precomposed U+00E9 and `e` + U+0301) are the same text and get the same key. No
  *      compatibility folding (NFKD), no transliteration, and no cross-script folding: Cyrillic `А`
  *      (U+0410) and Latin `A` (U+0041) stay different.
  *
  * [[baseNameKey]] and [[fullNameKey]] apply only rules 1 to 3; on canonical dblp names they equal
  * the Phase 4 Hadoop keys (`NFD(DblpAuthorName.split(name)._1)` for block family `dblp-base-name`,
  * and `NFD(name)` for name equality and coauthor overlap), so switching Hadoop to this object does
  * not change its outputs.
  *
  * [[tokens]], [[foldedName]] and [[initialsSurnameKey]] add the loose folding rules, applied to
  * the base name (suffix removed), in order (rules 4 to 8):
  *   - Case: lowercase with `Locale.ROOT` (letters keep their script), then NFD again.
  *   - Latin diacritics: a combining mark (general category Mn) directly after a Latin-script base
  *     letter, or after another such mark, is dropped (`José -> jose`). Marks on other scripts are
  *     kept (Cyrillic `й` stays `и` + U+0306, distinct from `и`). Letters without a canonical
  *     decomposition (`ø`, `ł`, `ß`, `æ`) are kept as they are.
  *   - Apostrophes (U+0027, U+0060, U+00B4, U+2018, U+2019, U+02BC) are dropped, joining their
  *     neighbours (`O'Brien -> obrien`).
  *   - Every other punctuation (general categories Pc, Pd, Ps, Pe, Pi, Pf, Po) or symbol (Sm, Sc,
  *     Sk, So) character separates tokens (`Jean-Pierre -> jean pierre`, `J.R. -> j r`).
  *   - Tokens are the non-empty runs between spaces, in source order. Name order is not changed:
  *     dblp writes the given names first and the family name last.
  */
object AuthorNameKey {

  /** Version of this rule; record it in the policy version of anything keyed by it. */
  val Version = "author-name-key/1"

  private val Whitespace = """(?U)\s+""".r.pattern

  private val Apostrophes: Set[Int] = Set(0x0027, 0x0060, 0x00b4, 0x2018, 0x2019, 0x02bc)

  private val Separators: Set[Int] = Set(
    Character.CONNECTOR_PUNCTUATION,
    Character.DASH_PUNCTUATION,
    Character.START_PUNCTUATION,
    Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION,
    Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION,
    Character.MATH_SYMBOL,
    Character.CURRENCY_SYMBOL,
    Character.MODIFIER_SYMBOL,
    Character.OTHER_SYMBOL
  ).map(_.toInt)

  /** Rule 1: Unicode whitespace runs to one space, trimmed. */
  def collapseWhitespace(name: String): String =
    if (name == null) "" else Whitespace.matcher(name).replaceAll(" ").trim

  /** Rule 3: Unicode Normalization Form D (canonical decomposition only). */
  def canonical(s: String): String =
    if (s == null) "" else Normalizer.normalize(s, Normalizer.Form.NFD)

  /** Rules 1 and 2: (base name, homonym suffix), whitespace collapsed, otherwise verbatim. */
  def split(name: String): (String, Option[String]) =
    DblpAuthorName.split(collapseWhitespace(name))

  /** The dblp homonym number (`0001`) or `None`. */
  def homonymSuffix(name: String): Option[String] = split(name)._2

  /** Rules 1 to 3 on the base name: `NFD(base name)`. Suffix removed, case and punctuation kept.
    * `Ricardo Campos 0001` and `Ricardo Campos` share this key. Hadoop block family
    * `dblp-base-name`.
    */
  def baseNameKey(name: String): String = canonical(split(name)._1)

  /** Rules 1 and 3 on the whole name: `NFD(name)`, homonym suffix kept. Two dblp names are the same
    * dblp profile exactly when their full-name keys are equal.
    */
  def fullNameKey(name: String): String = canonical(collapseWhitespace(name))

  /** Rules 1 to 8: the folded tokens of the base name. Empty when the name has no letters or digits
    * outside punctuation.
    */
  def tokens(name: String): List[String] = {
    val lower = canonical(canonical(split(name)._1).toLowerCase(Locale.ROOT))
    val sb = new java.lang.StringBuilder(lower.length)
    var i = 0
    var afterLatin = false // previous kept code point is a Latin letter or a dropped Latin mark
    while (i < lower.length) {
      val cp = lower.codePointAt(i)
      val tpe = Character.getType(cp)
      if (tpe == Character.NON_SPACING_MARK) {
        if (!afterLatin) sb.appendCodePoint(cp)
      } else if (Apostrophes.contains(cp)) {
        afterLatin = false
      } else if (Separators.contains(tpe) || Character.isWhitespace(cp)) {
        sb.append(' ')
        afterLatin = false
      } else {
        sb.appendCodePoint(cp)
        afterLatin = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
      }
      i += Character.charCount(cp)
    }
    sb.toString.split(' ').toList.filter(_.nonEmpty)
  }

  /** The tokens joined by single spaces: `Jean-Pierre O'Brien 0002 -> jean pierre obrien`. */
  def foldedName(name: String): String = tokens(name).mkString(" ")

  /** Initials plus surname: the first grapheme (first code point plus its following combining
    * marks) of every token except the last, then the last token, joined by single spaces. `J.
    * Smith`, `John Smith`, `Jane Smith` and `John Smith 0003` all give `j smith`; `Hugo O. Sousa`
    * gives `h o sousa`; a single-token name gives the token itself. `None` when there are no
    * tokens.
    */
  def initialsSurnameKey(name: String): Option[String] =
    tokens(name) match {
      case Nil  => None
      case toks =>
        val initials = toks.init.map(firstGrapheme)
        Some((initials :+ toks.last).mkString(" "))
    }

  private def firstGrapheme(token: String): String = {
    var end = Character.charCount(token.codePointAt(0))
    while (end < token.length && isMark(token.codePointAt(end)))
      end += Character.charCount(token.codePointAt(end))
    token.substring(0, end)
  }

  private def isMark(cp: Int): Boolean = {
    val t = Character.getType(cp)
    t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK ||
    t == Character.ENCLOSING_MARK
  }
}
