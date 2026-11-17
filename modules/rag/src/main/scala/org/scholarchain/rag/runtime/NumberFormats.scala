package org.scholarchain.rag.runtime

import java.math.{BigDecimal => JBigDecimal, RoundingMode}
import java.util.Locale

/** Number literals in claim text, shared by the validator ([[AnswerClaimsSchema]], the
  * numbers-in-text rule) and the verifier ([[ClaimVerification]]), so both accept exactly the same
  * formats (Phase 11, L-1 and L-4):
  *   - digits with optional decimals: `1234`, `4.5714`;
  *   - integers with standard three-digit comma groups: `1,234` and `12,345,678` (the value without
  *     the commas).
  *
  * Not accepted (the v3 prompt forbids them): number words (`one`, `a single`, `first`), percent
  * signs, other group separators (`1 234`), fewer decimals than the value needs (see
  * [[literalMatches]]). Digits inside stable IDs, claim IDs, dblp homonym numbers (`Wei Wang
  * 0003`), dblp-key-shaped slash tokens and quoted titles are never number literals (L-4).
  *
  * [[failureClass]] names, with a fixed vocabulary computed in code, why a value does not appear in
  * a text. It never returns or stores the text.
  */
object NumberFormats {

  val StableIdRe: scala.util.matching.Regex =
    "\\b(researcher|work|evidence|edge|run|mention|chunk|link):v1:[0-9a-f]{32}\\b".r
  val ClaimIdRe: scala.util.matching.Regex = "\\bclaim-[0-9]{1,4}\\b".r
  val QuotedRe: scala.util.matching.Regex = "\"([^\"]{1,400})\"".r
  val HomonymRe: scala.util.matching.Regex = "(?<=\\p{L}[\\p{L}.'’-]{0,40}) 0[0-9]{3}\\b".r
  // a token with a slash that has the shape of a real dblp key (`conf/acl`, `conf/acl/SmithJ16`)
  val SlashTokenRe: scala.util.matching.Regex = "[^\\s,;()\\[\\]\"]*/[^\\s,;()\\[\\]\"]*".r
  val DblpKeyRe: scala.util.matching.Regex =
    "(?:conf|journals|books|series|reference|phd|tr|ms)/[a-z][a-z0-9]*(?:/(?=[A-Za-z0-9._-]*[A-Za-z])[A-Za-z0-9._-]+)?".r

  /** One number literal: comma-grouped or plain digits, optional decimals, not inside a word, an
    * identifier or a longer number.
    */
  val LiteralRe: scala.util.matching.Regex =
    "(?<![\\p{L}\\p{N}_.:])((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?)(?![\\p{L}\\p{N}_]|\\.[0-9])".r

  /** Fixed failure classes of a bound number whose value is not in its claim text. */
  val Percent = "percent"
  val Separator = "separator"
  val TooFewDecimals = "too-few-decimals"
  val Words = "words"
  val IdDigits = "id-digits"
  val Absent = "absent"
  val Classes: List[String] = List(Percent, Separator, TooFewDecimals, Words, IdDigits, Absent)

  /** The literal without its group commas. */
  def plain(lit: String): String = lit.replace(",", "")

  private def decimals(lit: String): Int = {
    val p = plain(lit); val i = p.indexOf('.'); if (i < 0) 0 else p.length - i - 1
  }
  private def decimalsOf(v: Double): Int =
    JBigDecimal.valueOf(v).stripTrailingZeros().scale().max(0)

  /** A stated literal matches a stored value: integers exactly; otherwise rounded (half up) to the
    * literal's decimals, which must be at least min(2, the stored value's decimals).
    */
  def literalMatches(lit: String, stored: Double): Boolean = {
    val p = plain(lit)
    val d = decimals(p)
    val x = BigDecimal(p)
    if (d == 0) stored.isWhole && BigDecimal(stored) == x
    else
      d >= math.min(2, decimalsOf(stored)) &&
      BigDecimal(JBigDecimal.valueOf(stored).setScale(d, RoundingMode.HALF_UP)) == x
  }

  private def blank(re: scala.util.matching.Regex, t: String): String =
    re.replaceAllIn(t, m => " " * m.matched.length)

  /** `text` with the evidence-independent non-numbers blanked (same length, so positions hold):
    * quoted titles, stable IDs, claim IDs, dblp homonym numbers and dblp-key-shaped slash tokens.
    */
  def withoutNonNumbers(text: String): String = {
    val t =
      List(QuotedRe, StableIdRe, ClaimIdRe, HomonymRe).foldLeft(text)((acc, re) => blank(re, acc))
    SlashTokenRe.replaceAllIn(
      t,
      m => {
        val tok = m.matched.stripSuffix(".").stripSuffix(":")
        if (DblpKeyRe.matches(tok)) " " * m.matched.length
        else scala.util.matching.Regex.quoteReplacement(m.matched)
      }
    )
  }

  /** The number literals of a text whose non-numbers were already removed. */
  def literals(text: String): List[String] = LiteralRe.findAllMatchIn(text).map(_.group(1)).toList

  /** True when `value` appears in `text` as an accepted number literal (the validator's rule). */
  def appearsInText(value: Double, text: String): Boolean =
    literals(withoutNonNumbers(text)).exists(literalMatches(_, value))

  private val Small = List(
    "zero",
    "one",
    "two",
    "three",
    "four",
    "five",
    "six",
    "seven",
    "eight",
    "nine",
    "ten",
    "eleven",
    "twelve",
    "thirteen",
    "fourteen",
    "fifteen",
    "sixteen",
    "seventeen",
    "eighteen",
    "nineteen",
    "twenty"
  )
  private val Ordinals = List(
    "zeroth",
    "first",
    "second",
    "third",
    "fourth",
    "fifth",
    "sixth",
    "seventh",
    "eighth",
    "ninth",
    "tenth"
  )
  private val PercentRe = "([0-9]+(?:\\.[0-9]+)?)\\s*(?:%|percent\\b|per cent\\b)".r
  private val GroupedRe = "[0-9]{1,3}(?:[ \\u00A0\\u2009\\u202F'.,][0-9]{2,3})+".r
  private val AnyDigitsRe = "[0-9]+(?:\\.[0-9]+)?".r

  /** Why `value` does not appear in `text` as an accepted literal: one of [[Classes]]. Checked in
    * this order: a percentage of it (`95%` for 0.95), a group separator other than standard commas
    * (`1 234`), a literal with too few decimals (`4.6` or `5` for 4.5714), a number word or ordinal
    * (`one`, `a single`, `first`), digits only inside an identifier, otherwise absent. Text is
    * read, never returned.
    */
  def failureClass(value: Double, text: String): String = {
    val lower = text.toLowerCase(Locale.ROOT)
    val clean = withoutNonNumbers(text)
    def asPercent =
      PercentRe.findAllMatchIn(clean).exists { m =>
        val p = BigDecimal(m.group(1)) / 100
        (BigDecimal(value) - p).abs <= BigDecimal("0.005")
      }
    def asGroups =
      value.isWhole && GroupedRe.findAllIn(clean).exists { g =>
        val digits = g.filter(_.isDigit)
        digits.nonEmpty && digits.length <= 18 && BigDecimal(digits) == BigDecimal(value) &&
        !LiteralRe.pattern.matcher(g).matches()
      }
    def fewDecimals = literals(clean).exists { lit =>
      val p = plain(lit)
      val d = decimals(p)
      !literalMatches(lit, value) &&
      BigDecimal(JBigDecimal.valueOf(value).setScale(d, RoundingMode.HALF_UP)) == BigDecimal(p)
    }
    def asWords = value.isWhole && value >= 0 && {
      val n = value.toInt
      def has(w: String) = s"(?<![\\p{L}])${java.util.regex.Pattern.quote(w)}(?![\\p{L}])".r
        .findFirstIn(lower)
        .isDefined
      (n < Small.size && has(Small(n))) || (n < Ordinals.size && has(Ordinals(n))) ||
      (n == 1 && (has("a single") || has("single") || has("once") || has("sole")))
    }
    def inIds = AnyDigitsRe.findAllIn(text).exists(lit => literalMatches(lit, value))
    if (asPercent) Percent
    else if (asGroups) Separator
    else if (fewDecimals) TooFewDecimals
    else if (asWords) Words
    else if (inIds) IdDigits
    else Absent
  }

  /** Counts per class, in [[Classes]] order (absent classes left out). */
  def tally(classes: List[String]): Map[String, Long] =
    Classes
      .flatMap(c => {
        val n = classes.count(_ == c).toLong; if (n > 0) List(c -> n) else Nil
      })
      .toMap
}
