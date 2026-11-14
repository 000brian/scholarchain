package org.scholarchain.hadoop.io

import java.io.{ByteArrayOutputStream, EOFException, IOException, InputStream}

/** One unit found by [[DblpRecordScanner]]. */
final case class ScannedUnit(
    status: Int,
    element: String,
    offset: Long,
    length: Long,
    payload: Array[Byte],
    detail: String
)

/** Splits a dblp XML byte stream into complete record elements without an XML parser.
  *
  * Record-boundary rule: a record starts at a `<` immediately followed by one of the boundary
  * element names and then whitespace, `>` or `/`; it ends after the first matching `</name>` (dblp
  * record elements never nest). A record belongs to the split in which its `<` lies, so a reader
  * stops looking for new records at `end` but always finishes a record it has started, even when
  * that crosses into the next split. A reader that starts inside a record skips forward to the next
  * record start, which the previous split's reader has not claimed. Every record start tag outside
  * comments and CDATA sections is therefore read by exactly one reader.
  *
  * Known limitation (phantom records): a reader that starts inside a comment or CDATA section
  * cannot know it is inside one, so record-like markup there (for example a commented-out
  * `<article ...>...</article>`) after its split start is taken for a record, although the reader
  * of the previous split skips that comment whole. Such a phantom unit is never silently lost or
  * hidden: it is counted as INPUT_RECORDS and handled like any record. Incomplete or malformed
  * markup is rejected (truncated_record, malformed_xml, ...); markup that happens to be a complete,
  * well-formed record within the subset can be accepted as a work (or collapse into, or conflict
  * with, the real record of the same key in the J01 reducer). The Phase 3 samples contain no record
  * markup inside comments or CDATA; a whole-file read (one split) has no phantoms.
  *
  * The byte-level scan requires an ASCII-compatible encoding (UTF-8, ISO-8859-1, US-ASCII); the
  * mapper checks the declared encoding. Quotes inside the start tag are honoured, so `>` inside an
  * attribute value does not end the tag.
  *
  * Bounded memory: at most `maxRecordBytes` bytes of a record are kept. A longer record is read to
  * its end tag without buffering and reported as [[RawRecord.Oversize]] with a preview of at most
  * `previewBytes` bytes (the input format derives it from `scholarchain.reject.previewChars`). End
  * of input inside a record yields [[RawRecord.Truncated]]. A decompression-limit or I/O failure
  * yields one final [[RawRecord.DecompressionLimit]] or [[RawRecord.InputError]] unit, then the
  * scan ends.
  */
final class DblpRecordScanner(
    in: InputStream,
    startPos: Long,
    end: Long,
    elements: Set[String],
    maxRecordBytes: Int,
    previewBytes: Int
) {

  /** Without a configuration: previews of [[RawRecord.PreviewBytes]] bytes. */
  def this(in: InputStream, startPos: Long, end: Long, elements: Set[String], maxRecordBytes: Int) =
    this(in, startPos, end, elements, maxRecordBytes, RawRecord.PreviewBytes)

  require(previewBytes > 0, s"previewBytes must be > 0 (got $previewBytes)")
  private val maxNameLength = if (elements.isEmpty) 0 else elements.map(_.length).max
  private var pos = startPos
  private var pushback = -1
  private var done = false

  def position: Long = pos

  private def read(): Int = {
    if (pushback >= 0) {
      val b = pushback
      pushback = -1
      pos += 1
      b
    } else {
      val b = in.read()
      if (b >= 0) pos += 1
      b
    }
  }

  private def unread(b: Int): Unit = {
    pushback = b
    pos -= 1
  }

  private def isNameByte(b: Int): Boolean = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
  private def isTagEnd(b: Int): Boolean =
    b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '>' || b == '/'

  /** The next unit, or `None` at the end of this split. */
  def next(): Option[ScannedUnit] =
    if (done) None
    else
      try scan()
      catch {
        case e: DecompressionLimitExceeded =>
          done = true
          Some(
            ScannedUnit(
              RawRecord.DecompressionLimit,
              "",
              pos,
              0L,
              Array.emptyByteArray,
              e.getMessage
            )
          )
        case e: EOFException =>
          done = true
          Some(
            ScannedUnit(
              RawRecord.InputError,
              "",
              pos,
              0L,
              Array.emptyByteArray,
              s"truncated input: ${e.getMessage}"
            )
          )
        case e: IOException =>
          done = true
          Some(
            ScannedUnit(
              RawRecord.InputError,
              "",
              pos,
              0L,
              Array.emptyByteArray,
              s"${e.getClass.getSimpleName}: ${e.getMessage}"
            )
          )
      }

  private def scan(): Option[ScannedUnit] = {
    while (true) {
      val b = read()
      if (b < 0) { done = true; return None }
      if (b == '<') {
        val tagStart = pos - 1
        if (tagStart >= end) { done = true; return None }
        var c = read()
        if (c == '!' || c == '?') {
          // Comment, CDATA section or processing instruction outside a record: skipped whole, so
          // a record-like tag inside it is never taken for a record. End of input inside one ends
          // the scan (no record element is lost: its content is not a record).
          if (!skipSpecial(c, () => read(), x => unread(x))) { done = true; return None }
        } else {
          val name = new StringBuilder
          while (c >= 0 && isNameByte(c) && name.length <= maxNameLength) {
            name.append(c.toChar)
            c = read()
          }
          if (c < 0) {
            done = true
            val partial = name.toString
            // End of input inside what may be a record start tag: report it, never drop it.
            if (elements.exists(_.startsWith(partial)))
              return Some(
                ScannedUnit(
                  RawRecord.Truncated,
                  partial,
                  tagStart,
                  pos - tagStart,
                  ("<" + partial).getBytes("US-ASCII"),
                  s"end of input inside start tag '<$partial' after ${pos - tagStart} bytes"
                )
              )
            return None
          }
          if (isTagEnd(c) && elements.contains(name.toString))
            return Some(capture(name.toString, tagStart, c))
          unread(c)
        }
      }
    }
    None
  }

  /** Skips the rest of a markup construct whose `<` and first byte `first` (`!` or `?`) have been
    * read: `<!-- ... -->`, `<![CDATA[ ... ]]>`, `<? ... ?>`. Any other `<!` (DOCTYPE, ENTITY) is
    * left to the caller's scan, which only looks for record start tags. Bytes are consumed through
    * `next`, so a capturing caller keeps them; a byte that turns out not to belong to the construct
    * is handed back through `back` for the caller to examine again.
    *
    * @return
    *   false only when the input ended inside the construct
    */
  private def skipSpecial(first: Int, next: () => Int, back: Int => Unit): Boolean = {
    def until(term: String): Boolean = {
      val t = term.getBytes("US-ASCII")
      val window = new Array[Int](t.length)
      var seen = 0L
      while (true) {
        val x = next()
        if (x < 0) return false
        System.arraycopy(window, 1, window, 0, t.length - 1)
        window(t.length - 1) = x
        seen += 1
        if (seen >= t.length && t.indices.forall(i => window(i) == t(i))) return true
      }
      false
    }
    def expect(rest: String): Option[Boolean] = {
      // Some(true): prefix matched; Some(false): mismatch (caller resumes); None: end of input.
      var i = 0
      while (i < rest.length) {
        val x = next()
        if (x < 0) return None
        if (x != rest.charAt(i)) { back(x); return Some(false) }
        i += 1
      }
      Some(true)
    }
    if (first == '?') until("?>")
    else
      next() match {
        case -1  => false
        case '-' =>
          expect("-") match {
            case None        => false
            case Some(true)  => until("-->")
            case Some(false) => true
          }
        case '[' =>
          expect("CDATA[") match {
            case None        => false
            case Some(true)  => until("]]>")
            case Some(false) => true
          }
        case x => back(x); true
      }
  }

  /** Reads one record whose start tag `<name` has been consumed; `afterName` is the next byte.
    *
    * The record ends at its end tag `</name S? >` (XML 1.0 [42]). Comments, CDATA sections and
    * processing instructions in the body are kept verbatim but never end the record.
    */
  private def capture(name: String, tagStart: Long, afterName: Int): ScannedUnit = {
    val buf = new ByteArrayOutputStream(1024)
    var kept = 0L
    var oversize = false
    def keep(b: Int): Unit =
      if (kept < maxRecordBytes) { buf.write(b); kept += 1 }
      else oversize = true
    ('<' +: name).foreach(ch => keep(ch.toInt))
    keep(afterName)
    // Finish the start tag (quotes honoured) and detect a self-closing tag.
    var inQuote = -1
    var prev = -1
    var b = afterName
    while (!(b == '>' && inQuote < 0)) {
      prev = b
      b = read()
      if (b < 0) return truncated(name, tagStart, buf)
      keep(b)
      if (inQuote >= 0) { if (b == inQuote) inQuote = -1 }
      else if (b == '"' || b == '\'') inQuote = b
    }
    if (prev == '/') return finish(name, tagStart, buf, oversize)
    // Body: every byte read is kept; `held` is a byte already read and kept that must be examined
    // again as the possible start of a tag.
    var held = -1
    def nb(): Int =
      if (held >= 0) { val h = held; held = -1; h }
      else { val x = read(); if (x >= 0) keep(x); x }
    while (true) {
      b = nb()
      if (b < 0) return truncated(name, tagStart, buf)
      if (b == '<') {
        val c = nb()
        if (c < 0) return truncated(name, tagStart, buf)
        if (c == '/') {
          val n = new StringBuilder
          var d = nb()
          while (d >= 0 && isNameByte(d) && n.length <= name.length) {
            n.append(d.toChar)
            d = nb()
          }
          if (d < 0) return truncated(name, tagStart, buf)
          if (n.toString == name) {
            while (d == ' ' || d == '\t' || d == '\n' || d == '\r') d = nb()
            if (d < 0) return truncated(name, tagStart, buf)
            if (d == '>') return finish(name, tagStart, buf, oversize)
          }
          held = d
        } else if (c == '!' || c == '?') {
          if (!skipSpecial(c, () => nb(), x => held = x)) return truncated(name, tagStart, buf)
        } else held = c
      }
    }
    finish(name, tagStart, buf, oversize)
  }

  private def finish(
      name: String,
      tagStart: Long,
      buf: ByteArrayOutputStream,
      oversize: Boolean
  ): ScannedUnit = {
    val length = pos - tagStart
    if (oversize)
      ScannedUnit(
        RawRecord.Oversize,
        name,
        tagStart,
        length,
        preview(buf),
        s"record of $length bytes exceeds $maxRecordBytes"
      )
    else ScannedUnit(RawRecord.Ok, name, tagStart, length, buf.toByteArray, "")
  }

  private def truncated(name: String, tagStart: Long, buf: ByteArrayOutputStream): ScannedUnit = {
    done = true
    ScannedUnit(
      RawRecord.Truncated,
      name,
      tagStart,
      pos - tagStart,
      preview(buf),
      s"end of input inside <$name> after ${pos - tagStart} bytes"
    )
  }

  private def preview(buf: ByteArrayOutputStream): Array[Byte] = {
    val all = buf.toByteArray
    if (all.length <= previewBytes) all
    else java.util.Arrays.copyOf(all, previewBytes)
  }
}
