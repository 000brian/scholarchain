package org.scholarchain.contracts.er

import java.io.{ByteArrayInputStream, DataInputStream}
import java.nio.charset.StandardCharsets

import scala.collection.mutable

/** Independent leakage detector for decision 14 (repair m6, test-engineer's part).
  *
  * The matching code (everything in the entity-resolution module outside the `eval` package) must
  * not reach the evaluation code or gold-label paths. A plain text search for `er.eval` misses a
  * package-relative `import eval.Harness` inside `package org.scholarchain.er`, a renaming import
  * selector, a Scala 3 `as` rename, a backticked segment, or a reference inside an interpolated
  * string. This detector works on tokens instead:
  *
  *   1. Source: comments are removed; string literals are separated from code (the `${...}` and
  *      `$name` parts of interpolated strings go back to code). Any code identifier `eval`
  *      (backticks removed) is a finding, since no non-eval source needs that name; so is any code
  *      identifier equal to a top-level name declared in the eval sources; so is any string literal
  *      naming the eval package (reflection) or a gold-label path.
  *   2. Class files: the constant pool is parsed; any CONSTANT_Utf8 naming the eval package
  *      (`org/scholarchain/er/eval/` or the dotted form) is a finding.
  *   3. TASTy files: the name table entry of the simple name `eval` (tag UTF8 = 1, length 4 as a
  *      stop-bit natural 0x84, then the bytes) is a finding.
  */
object LeakageDetector {

  final case class Split(code: String, strings: List[String])

  private def isIdStart(c: Char) = Character.isUnicodeIdentifierStart(c) || c == '_' || c == '$'
  private def isIdPart(c: Char) = Character.isUnicodeIdentifierPart(c) || c == '$'

  /** Removes comments and separates string literal contents from code. */
  def split(src: String): Split = {
    val code = new StringBuilder
    val strings = mutable.ListBuffer.empty[String]
    var i = 0
    val n = src.length
    def at(k: Int): Char = if (k < n) src.charAt(k) else '\u0000'

    /** Interpolated body: literal text to `strings`, `${...}` and `$id` to code. */
    def interpolated(body: String): Unit = {
      val lit = new StringBuilder
      var j = 0
      while (j < body.length) {
        val c = body.charAt(j)
        if (c == '$' && j + 1 < body.length && body.charAt(j + 1) == '{') {
          var depth = 1
          var k = j + 2
          while (k < body.length && depth > 0) {
            if (body.charAt(k) == '{') depth += 1
            else if (body.charAt(k) == '}') depth -= 1
            k += 1
          }
          // the splice is code again, possibly with its own string literals
          val inner = split(body.substring(j + 2, math.max(j + 2, k - 1)))
          code.append(' ').append(inner.code).append(' ')
          strings ++= inner.strings
          j = k
        } else if (c == '$' && j + 1 < body.length && body.charAt(j + 1) == '$') {
          lit.append('$'); j += 2
        } else if (c == '$' && j + 1 < body.length && isIdStart(body.charAt(j + 1))) {
          var k = j + 1
          while (k < body.length && isIdPart(body.charAt(k)) && body.charAt(k) != '$') k += 1
          code.append(' ').append(body.substring(j + 1, k)).append(' ')
          j = k
        } else { lit.append(c); j += 1 }
      }
      strings += lit.toString
    }

    while (i < n) {
      val c = src.charAt(i)
      if (c == '/' && at(i + 1) == '/') {
        while (i < n && src.charAt(i) != '\n') i += 1
      } else if (c == '/' && at(i + 1) == '*') {
        var depth = 1
        i += 2
        while (i < n && depth > 0) {
          if (src.charAt(i) == '/' && at(i + 1) == '*') { depth += 1; i += 2 }
          else if (src.charAt(i) == '*' && at(i + 1) == '/') { depth -= 1; i += 2 }
          else i += 1
        }
        code.append(' ')
      } else if (c == '"') {
        val interp = code.nonEmpty && isIdPart(code.last)
        val triple = at(i + 1) == '"' && at(i + 2) == '"'
        val body = new StringBuilder
        if (triple) {
          i += 3
          while (i < n && !(src.charAt(i) == '"' && at(i + 1) == '"' && at(i + 2) == '"')) {
            body.append(src.charAt(i)); i += 1
          }
          i += 3
          while (i < n && src.charAt(i) == '"') { body.append('"'); i += 1 }
        } else {
          i += 1
          while (i < n && src.charAt(i) != '"' && src.charAt(i) != '\n') {
            if (interp && src.charAt(i) == '$' && at(i + 1) == '$') {
              body.append("$$"); i += 2
            } else if (interp && src.charAt(i) == '$' && at(i + 1) == '{') {
              // a splice may hold nested string literals: copy it whole, braces balanced
              body.append("${"); i += 2
              var depth = 1
              while (i < n && depth > 0) {
                val ch = src.charAt(i)
                if (ch == '"') {
                  body.append(ch); i += 1
                  while (i < n && src.charAt(i) != '"' && src.charAt(i) != '\n') {
                    body.append(src.charAt(i)); i += 1
                  }
                  if (i < n && src.charAt(i) == '"') { body.append('"'); i += 1 }
                } else {
                  if (ch == '{') depth += 1 else if (ch == '}') depth -= 1
                  body.append(ch); i += 1
                }
              }
            } else {
              // an escape keeps its next character inside the literal
              if (src.charAt(i) == '\\') { body.append(src.charAt(i)); i += 1 }
              if (i < n) { body.append(src.charAt(i)); i += 1 }
            }
          }
          i += 1
        }
        if (interp) interpolated(body.toString) else strings += body.toString
        code.append(' ')
      } else if (c == '\'' && at(i + 2) == '\'' && at(i + 1) != '\\') {
        strings += at(i + 1).toString; i += 3; code.append(' ')
      } else if (c == '\'' && at(i + 1) == '\\') {
        // skip the backslash and the escaped character, then up to the closing quote
        var k = i + 3
        while (k < n && src.charAt(k) != '\'' && k - i < 10) k += 1
        strings += src.substring(i + 1, math.min(n, k)); i = k + 1; code.append(' ')
      } else { code.append(c); i += 1 }
    }
    Split(code.toString, strings.toList)
  }

  /** Identifier tokens of code: plain identifiers and the contents of backticked ones. */
  def identifiers(code: String): List[String] = {
    val out = mutable.ListBuffer.empty[String]
    var i = 0
    while (i < code.length) {
      val c = code.charAt(i)
      if (c == '`') {
        val k = code.indexOf('`', i + 1)
        if (k < 0) i = code.length else { out += code.substring(i + 1, k); i = k + 1 }
      } else if (isIdStart(c)) {
        var k = i + 1
        while (k < code.length && isIdPart(code.charAt(k))) k += 1
        out += code.substring(i, k)
        i = k
      } else i += 1
    }
    out.toList
  }

  /** Names declared at brace depth 0 of a source (top-level definitions). */
  def topLevelNames(src: String): Set[String] = {
    val code = split(src).code
    val decl =
      // keyword at a word start: "er.eval" must not read as the keyword "val"
      """(?<![\p{L}\p{N}_$.])(?:object|class|trait|enum|type|def|val|var|given)\s+`?([\p{L}_$][\p{L}\p{N}_$]*)""".r
    val sb = new StringBuilder
    var depth = 0
    code.foreach { c =>
      if (c == '{') depth += 1
      else if (c == '}') depth -= 1
      else if (depth == 0) sb.append(c)
      if (depth != 0 && c != '{') sb.append(if (c == '\n') '\n' else ' ')
    }
    decl.findAllMatchIn(sb.toString).map(_.group(1)).toSet -- Set("eval")
  }

  val EvalPackageSlash = "org/scholarchain/er/eval"
  val EvalPackageDot = "org.scholarchain.er.eval"

  /** Findings for one non-eval source text. */
  def sourceFindings(src: String, evalNames: Set[String]): List[String] = {
    val s = split(src)
    val ids = identifiers(s.code)
    val f = mutable.ListBuffer.empty[String]
    if (ids.contains("eval"))
      f += "code identifier `eval` (qualified, relative, selector or rename)"
    ids
      .filter(evalNames)
      .distinct
      .foreach(n => f += s"code identifier $n declared in the eval package")
    s.strings.foreach { lit =>
      val l = lit.replace('\\', '/')
      if (l.contains(EvalPackageDot) || l.contains(EvalPackageSlash))
        f += s"string literal naming the eval package"
      if (
        l.contains("agent-runs") || l.toLowerCase
          .contains("/gold") || l.toLowerCase.contains("gold/")
      )
        f += s"string literal naming a gold-label path"
    }
    f.toList.distinct
  }

  /** CONSTANT_Utf8 entries of a class file's constant pool. */
  def classUtf8(bytes: Array[Byte]): List[String] = {
    val in = new DataInputStream(new ByteArrayInputStream(bytes))
    if (in.readInt() != 0xcafebabe) throw new IllegalArgumentException("not a class file")
    in.readUnsignedShort(); in.readUnsignedShort()
    val count = in.readUnsignedShort()
    val out = mutable.ListBuffer.empty[String]
    var idx = 1
    while (idx < count) {
      in.readUnsignedByte() match {
        case 1                          => out += in.readUTF()
        case 3 | 4                      => in.skipBytes(4)
        case 5 | 6                      => in.skipBytes(8); idx += 1
        case 7 | 8 | 16 | 19 | 20       => in.skipBytes(2)
        case 9 | 10 | 11 | 12 | 17 | 18 => in.skipBytes(4)
        case 15                         => in.skipBytes(3)
        case t => throw new IllegalArgumentException(s"unknown constant pool tag $t")
      }
      idx += 1
    }
    out.toList
  }

  def classFindings(bytes: Array[Byte]): List[String] =
    classUtf8(bytes).filter(u =>
      u.contains(EvalPackageSlash + "/") || u.contains(EvalPackageDot + ".")
    )

  private val TastyEvalName: Array[Byte] =
    Array[Byte](1, 0x84.toByte) ++ "eval".getBytes(StandardCharsets.UTF_8)

  def tastyNamesEval(bytes: Array[Byte]): Boolean = indexOf(bytes, TastyEvalName) >= 0

  def indexOf(hay: Array[Byte], needle: Array[Byte]): Int = {
    var i = 0
    while (i + needle.length <= hay.length) {
      var j = 0
      while (j < needle.length && hay(i + j) == needle(j)) j += 1
      if (j == needle.length) return i
      i += 1
    }
    -1
  }

  /** A minimal synthetic class file whose constant pool holds the given UTF8 entries plus one long
    * (two slots), for the parser self-check.
    */
  def syntheticClass(utf8: List[String]): Array[Byte] = {
    val bo = new java.io.ByteArrayOutputStream
    val out = new java.io.DataOutputStream(bo)
    out.writeInt(0xcafebabe); out.writeShort(0); out.writeShort(61)
    out.writeShort(utf8.size + 3) // + long (2 slots) + 1 for index 0
    out.writeByte(5); out.writeLong(42L)
    utf8.foreach { u => out.writeByte(1); out.writeUTF(u) }
    out.flush()
    bo.toByteArray
  }
}
