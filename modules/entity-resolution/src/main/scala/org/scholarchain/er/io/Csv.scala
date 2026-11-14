package org.scholarchain.er.io

/** RFC 4180 CSV: comma separator, `"` quoting, `""` escapes, CRLF or LF line ends.
  *
  * Spreadsheet formula injection (repair m2): [[row]] writes a field whose first character is `=`,
  * `+`, `-`, `@`, tab, carriage return or `'` with one extra leading `'` (spreadsheets then show it
  * as text instead of evaluating it). [[parse]] reverses exactly that: a field that starts with `'`
  * followed by one of those characters loses its first `'`. Because `'` itself is a trigger, the
  * mapping is a bijection: every value read back equals the value written, so the strict gold
  * reader still matches the label file against the queue JSONL (which holds the raw values). A
  * field typed by hand that starts with `'` followed by another character is read unchanged.
  */
object Csv {

  private def trigger(c: Char): Boolean =
    c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' || c == '\''

  /** The field with formula triggers neutralised (see the object comment). */
  def neutralise(field: String): String =
    if (field.nonEmpty && trigger(field.charAt(0))) "'" + field else field

  /** Inverse of [[neutralise]]. */
  def restore(field: String): String =
    if (field.length >= 2 && field.charAt(0) == '\'' && trigger(field.charAt(1)))
      field.substring(1)
    else field

  def escape(field: String): String = {
    val f = neutralise(field)
    if (f.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r'))
      "\"" + f.replace("\"", "\"\"") + "\""
    else f
  }

  def row(fields: Seq[String]): String = fields.map(escape).mkString(",")

  /** Parses a whole CSV document into rows. Quoted fields may span lines. `Left` on a malformed
    * document (unterminated quote, text after a closing quote).
    */
  def parse(text: String): Either[String, Vector[Vector[String]]] = {
    val s = if (text.startsWith("﻿")) text.substring(1) else text
    val rows = Vector.newBuilder[Vector[String]]
    var fields = Vector.newBuilder[String]
    val cur = new StringBuilder
    var i = 0
    var line = 1
    var inQuotes = false
    var afterQuote = false
    var rowHasContent = false
    def endField(): Unit = {
      fields += restore(cur.toString)
      cur.clear()
      afterQuote = false
    }
    def endRow(): Unit = {
      endField()
      val r = fields.result()
      if (!(r.size == 1 && r.head.isEmpty && !rowHasContent)) rows += r
      fields = Vector.newBuilder[String]
      rowHasContent = false
    }
    while (i < s.length) {
      val c = s.charAt(i)
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < s.length && s.charAt(i + 1) == '"') { cur.append('"'); i += 1 }
          else { inQuotes = false; afterQuote = true }
        } else {
          if (c == '\n') line += 1
          cur.append(c)
        }
      } else if (c == '"') {
        if (cur.nonEmpty || afterQuote) return Left(s"line $line: unexpected quote inside a field")
        inQuotes = true
        rowHasContent = true
      } else if (c == ',') {
        endField(); rowHasContent = true
      } else if (c == '\r') {
        if (i + 1 < s.length && s.charAt(i + 1) == '\n') i += 1
        endRow(); line += 1
      } else if (c == '\n') {
        endRow(); line += 1
      } else {
        if (afterQuote) return Left(s"line $line: text after a closing quote")
        cur.append(c)
        rowHasContent = true
      }
      i += 1
    }
    if (inQuotes) return Left(s"line $line: unterminated quoted field")
    if (cur.nonEmpty || rowHasContent || afterQuote) endRow()
    Right(rows.result())
  }
}
