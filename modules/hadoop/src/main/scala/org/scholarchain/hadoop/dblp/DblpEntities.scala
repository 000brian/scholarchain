package org.scholarchain.hadoop.dblp

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.{Charset, StandardCharsets}

/** The XML declaration and DTD entities that apply to one dblp file. */
final case class DblpProlog(encoding: String, entities: Map[String, String])

/** dblp entity handling.
  *
  * dblp writes non-ASCII characters as DTD-declared named entities (`&eacute;`) or numeric
  * character references. Records are parsed one at a time without the DTD, so named entities are
  * replaced by their declared replacement text first. Declarations come from the file's internal
  * DTD subset (the repository samples) and from an optional external DTD file (the real release,
  * whose DOCTYPE names a DTD file that dblp does not publish under that name). An entity declared
  * by neither rejects the record; nothing is guessed.
  *
  * The declaration pattern is the one in `datasets/scripts/dslib.py` (`dtd_general_entities`).
  */
object DblpEntities {

  private val Decl = """<!ENTITY\s+([A-Za-z][A-Za-z0-9]*)\s+"([^"]*)"\s*>""".r
  private val EncodingDecl = """<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""".r
  private val Ref = """&([A-Za-z_:][A-Za-z0-9._:-]*);""".r
  private val Predefined = Set("amp", "lt", "gt", "quot", "apos")

  /** General (non-parameter) entity declarations in DTD text. */
  def declarations(dtdText: String): Map[String, String] =
    Decl.findAllMatchIn(dtdText).map(m => m.group(1) -> m.group(2)).toMap

  /** Reads at most `maxBytes` and returns them; `None` when the stream holds more. */
  def readBounded(in: InputStream, maxBytes: Int): Option[Array[Byte]] = {
    val out = new ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var total = 0
    var n = in.read(buf)
    while (n > 0) {
      total += n
      if (total > maxBytes) return None
      out.write(buf, 0, n)
      n = in.read(buf)
    }
    Some(out.toByteArray)
  }

  /** Reads the prolog (everything before the `<dblp` root start tag) from the start of a file.
    *
    * Encoding: the XML declaration's `encoding`, or UTF-8 when absent (XML 1.0 section 4.3.3).
    */
  def readProlog(in: InputStream, maxBytes: Int): Either[String, (String, String)] = {
    val out = new ByteArrayOutputStream()
    val marker = "<dblp".getBytes(StandardCharsets.US_ASCII)
    var matched = 0
    var total = 0
    var b = in.read()
    while (b >= 0) {
      total += 1
      if (total > maxBytes) return Left(s"no <dblp> root start tag in the first $maxBytes bytes")
      out.write(b)
      if (b == marker(matched)) {
        matched += 1
        if (matched == marker.length) {
          val bytes = out.toByteArray
          val latin = new String(bytes, StandardCharsets.ISO_8859_1)
          val encoding = EncodingDecl.findFirstMatchIn(latin).map(_.group(1)).getOrElse("UTF-8")
          return Right(encoding -> new String(bytes, charsetOrLatin1(encoding)))
        }
      } else matched = if (b == marker(0)) 1 else 0
      b = in.read()
    }
    Left("end of input before the <dblp> root start tag")
  }

  private def charsetOrLatin1(name: String): Charset =
    try Charset.forName(name)
    catch { case _: Exception => StandardCharsets.ISO_8859_1 }

  /** Replaces declared named entities by their replacement text, without a size bound. Predefined
    * XML entities and numeric character references are left for the XML parser. Left: the first
    * undefined name. Jobs use the bounded overload; this one is for trusted, small inputs.
    */
  def expand(xml: String, entities: Map[String, String]): Either[String, String] =
    expand(xml, entities, Int.MaxValue) match {
      case Right(x)                         => Right(x)
      case Left(ExpandError.Undefined(n))   => Left(n)
      case Left(ExpandError.TooLarge(n, _)) =>
        // Only when the result would exceed the largest possible String.
        throw new IllegalArgumentException(s"entity expansion reaches at least $n characters")
    }

  /** Single-pass expansion with a bound on the result: replacement text is not re-scanned, and the
    * result never exceeds `maxChars` UTF-16 code units. The bound is checked before each append, so
    * a small record referencing a large entity many times (quadratic blow-up) is refused after at
    * most `maxChars` characters have been built, never by running out of memory.
    *
    * @return
    *   the expanded text; [[ExpandError.Undefined]] for the first undeclared name;
    *   [[ExpandError.TooLarge]] when the result would exceed `maxChars`
    */
  def expand(
      xml: String,
      entities: Map[String, String],
      maxChars: Int
  ): Either[ExpandError, String] = {
    if (xml.indexOf('&') < 0)
      return if (xml.length > maxChars) Left(ExpandError.TooLarge(xml.length.toLong, maxChars))
      else Right(xml)
    val sb = new java.lang.StringBuilder(xml.length + 32)
    var last = 0
    val it = Ref.findAllMatchIn(xml)
    while (it.hasNext) {
      val m = it.next()
      val name = m.group(1)
      if (!Predefined.contains(name)) {
        entities.get(name) match {
          case None              => return Left(ExpandError.Undefined(name))
          case Some(replacement) =>
            // Lower bound of the final length: what is built so far plus this chunk.
            val atLeast = sb.length.toLong + (m.start - last) + replacement.length
            if (atLeast > maxChars) return Left(ExpandError.TooLarge(atLeast, maxChars))
            sb.append(xml, last, m.start)
            sb.append(replacement)
            last = m.end
        }
      }
    }
    val total = sb.length.toLong + (xml.length - last)
    if (total > maxChars) return Left(ExpandError.TooLarge(total, maxChars))
    sb.append(xml, last, xml.length)
    Right(sb.toString)
  }
}

/** Why [[DblpEntities.expand]] refused a record. */
sealed trait ExpandError
object ExpandError {
  final case class Undefined(name: String) extends ExpandError

  /** `atLeast`: a lower bound of the expanded length at the point expansion stopped. */
  final case class TooLarge(atLeast: Long, maxChars: Int) extends ExpandError
}
