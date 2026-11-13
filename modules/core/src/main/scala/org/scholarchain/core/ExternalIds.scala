package org.scholarchain.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.Locale

/** External (source) identifiers and their canonical forms.
  *
  * Input contract: `datasets/schemas/identifiers-and-linkage.md` and the `identifiers` block of
  * `datasets/dataset-manifest.yaml` (owner: dataset-engineer); reference implementation
  * `datasets/scripts/dslib.py`. External identifiers are stored in records as plain strings in
  * canonical form. `normalize` maps a raw value to the canonical form or `None` (reject, never
  * repair); `isCanonical` checks a stored value.
  *
  * Deliberate difference from `dslib.py`: digits are ASCII `0-9` only. Python's `\d` also accepts
  * other Unicode decimal digits; ScholarChain rejects them.
  */
object Doi {

  private val Prefixes =
    List("https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/")
  private val SchemePrefix = "doi:"
  // (?U): \S is Unicode-aware, matching Python's `\S` in a str pattern.
  private val Pattern = """(?U)10\.[0-9]+(\.[0-9]+)*/\S+""".r.pattern

  /** Canonical DOI or `None`.
    *
    *   1. trim; 2. strip one resolver prefix (case-insensitive): the four doi.org/dx.doi.org URL
    *      forms or `doi:`; 3. for URL forms only, percent-decode once (UTF-8, malformed bytes
    *      become U+FFFD, `+` is not a space); 4. trim, lowercase ASCII A-Z only; 5. require the DOI
    *      regex.
    */
  def normalize(raw: String): Option[String] = {
    if (raw == null) return None
    var s = raw.trim
    var wasUrl = false
    Prefixes.find(p => s.regionMatches(true, 0, p, 0, p.length)) match {
      case Some(p) =>
        s = s.substring(p.length)
        wasUrl = true
      case None =>
        if (s.regionMatches(true, 0, SchemePrefix, 0, SchemePrefix.length))
          s = s.substring(SchemePrefix.length)
    }
    if (wasUrl) s = percentDecodeOnce(s)
    s = asciiLower(s.trim)
    if (Pattern.matcher(s).matches()) Some(s) else None
  }

  def isCanonical(s: String): Boolean =
    s != null && Pattern.matcher(s).matches() && !s.exists(c => c >= 'A' && c <= 'Z') &&
      s == s.trim

  /** Only DOI resolver URLs in a dblp `<ee>` element carry a DOI. */
  def fromDblpEe(ee: String): Option[String] =
    if (ee == null) None
    else {
      val t = ee.trim
      if (Prefixes.exists(p => t.regionMatches(true, 0, p, 0, p.length))) normalize(t) else None
    }

  private def asciiLower(s: String): String =
    s.map(c => if (c >= 'A' && c <= 'Z') (c + 32).toChar else c)

  private def hexValue(c: Char): Int = Character.digit(c, 16)

  private def isAsciiHex(c: Char): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  /** Equivalent of Python `urllib.parse.unquote(s)`: `%XX` decoded as UTF-8 with replacement. */
  private[core] def percentDecodeOnce(s: String): String = {
    if (s.indexOf('%') < 0) return s
    val out = new ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (
        c == '%' && i + 2 < s.length &&
        isAsciiHex(s.charAt(i + 1)) && isAsciiHex(s.charAt(i + 2))
      ) {
        out.write(hexValue(s.charAt(i + 1)) * 16 + hexValue(s.charAt(i + 2)))
        i += 3
      } else {
        val cp = s.codePointAt(i)
        val bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)
        out.write(bytes, 0, bytes.length)
        i += Character.charCount(cp)
      }
    }
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPLACE)
      .onUnmappableCharacter(CodingErrorAction.REPLACE)
      .decode(ByteBuffer.wrap(out.toByteArray))
      .toString
  }
}

object Orcid {

  private val Prefixes = List("https://orcid.org/", "http://orcid.org/")
  private val Pattern = """[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]""".r.pattern

  /** ISO 7064 11,2 check character over 15 ASCII digits (ORCID OrcidCheckDigitGenerator). */
  def checkDigit(base15: String): Char = {
    var total = 0
    base15.foreach(ch => total = (total + (ch - '0')) * 2)
    val result = (12 - total % 11) % 11
    if (result == 10) 'X' else ('0' + result).toChar
  }

  /** Canonical bare uppercase iD or `None`: strip an orcid.org URI prefix (case-insensitive),
    * uppercase, require the 4x4 hyphenated shape and a valid check digit.
    */
  def normalize(raw: String): Option[String] = {
    if (raw == null) return None
    var s = raw.trim
    Prefixes.find(p => s.regionMatches(true, 0, p, 0, p.length)).foreach { p =>
      s = s.substring(p.length)
    }
    s = s.toUpperCase(Locale.ROOT)
    if (isCanonical(s)) Some(s) else None
  }

  def isCanonical(s: String): Boolean =
    s != null && Pattern.matcher(s).matches() && {
      val digits = s.replace("-", "")
      checkDigit(digits.substring(0, 15)) == digits.charAt(15)
    }
}

/** OpenAlex entity keys: entity letter plus digits, no URL prefix, uppercase. */
sealed abstract class OpenAlexEntity(val letter: Char) {
  private val pattern = (letter.toString + "[1-9][0-9]*").r.pattern
  private val UrlPrefix = "https://openalex.org/"

  def normalize(raw: String): Option[String] = {
    if (raw == null) return None
    var s = raw.trim
    if (s.regionMatches(true, 0, UrlPrefix, 0, UrlPrefix.length)) s = s.substring(UrlPrefix.length)
    s = s.toUpperCase(Locale.ROOT)
    if (isCanonical(s)) Some(s) else None
  }

  def isCanonical(s: String): Boolean = s != null && pattern.matcher(s).matches()
}

object OpenAlexId {
  case object Work extends OpenAlexEntity('W')
  case object Author extends OpenAlexEntity('A')
  case object Source extends OpenAlexEntity('S')
  case object Institution extends OpenAlexEntity('I')
}

/** dblp record keys are opaque and used verbatim (case-sensitive) as source record IDs.
  *
  * Structural rule enforced here: a lowercase ASCII first segment, then one or more `/`-separated
  * non-empty segments without whitespace. This is a superset of the observed key alphabet
  * `^[a-z]+(/[A-Za-z0-9_\-.'+]+)+$` recorded by dataset-engineer, which is not a dblp guarantee.
  */
object DblpKey {
  private val Pattern = """[a-z]+(/[^/\s]+)+""".r.pattern
  private val ObservedPattern = """[a-z]+(/[A-Za-z0-9_\-.'+]+)+""".r.pattern

  def isValid(s: String): Boolean = s != null && Pattern.matcher(s).matches()

  /** True when the key uses only the alphabet observed in dblp releases so far. */
  def usesObservedAlphabet(s: String): Boolean = s != null && ObservedPattern.matcher(s).matches()

  /** dblp stream (venue) key: the first two segments, e.g. `conf/sigir/Sousa0J23 -> conf/sigir`. */
  def streamKey(key: String): String = key.split('/').take(2).mkString("/")
}

/** dblp venue (stream) key: exactly two segments, e.g. `conf/sigir` or `journals/tois`. */
object VenueKey {
  private val Pattern = """[a-z]+/[^/\s]+""".r.pattern
  def isValid(s: String): Boolean = s != null && Pattern.matcher(s).matches()
}

/** dblp author names: entity-decoded `<author>` text, whitespace collapsed, homonym suffix kept.
  *
  * Entity decoding belongs to the XML reader (hadoop-engineer). `normalize` trims and collapses
  * every run of Unicode whitespace to a single U+0020 space.
  */
object DblpAuthorName {
  private val Whitespace = """(?U)\s+""".r.pattern
  private val Homonym = """(?U)(.*\S) ([0-9]{4})""".r.pattern

  def normalize(raw: String): Option[String] =
    if (raw == null) None
    else {
      val s = Whitespace.matcher(raw).replaceAll(" ").trim
      if (s.isEmpty || s.exists(Character.isISOControl)) None else Some(s)
    }

  def isCanonical(s: String): Boolean = s != null && normalize(s).contains(s)

  /** `Ricardo Campos 0001 -> ("Ricardo Campos", Some("0001"))`; no suffix -> `(name, None)`. */
  def split(name: String): (String, Option[String]) = {
    val m = Homonym.matcher(name)
    if (m.matches()) (m.group(1), Some(m.group(2))) else (name, None)
  }
}
