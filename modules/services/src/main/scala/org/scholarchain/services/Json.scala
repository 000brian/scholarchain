package org.scholarchain.services

import upickle.default.{writeJs, Writer}

/** The one JSON encoder of the REST services: object keys sorted (recursively), two-space indent.
  * Every response body and every frozen example under `src/test/resources/api/v1/` is produced by
  * it, so a response equals its example byte for byte.
  */
object Json {

  def sorted(v: ujson.Value): ujson.Value = v match {
    case o: ujson.Obj =>
      ujson.Obj.from(o.value.toSeq.sortBy(_._1).map { case (k, x) => k -> sorted(x) })
    case a: ujson.Arr => ujson.Arr.from(a.value.map(sorted))
    case x            => x
  }

  /** The response form: sorted keys, indent 2. */
  def render(v: ujson.Value): String = sorted(v).render(indent = 2)

  def encode[A: Writer](a: A): String = render(writeJs(a))

  /** The canonical form hashed into fingerprints: sorted keys, no whitespace. */
  def canonical(v: ujson.Value): String = sorted(v).render()

  /** True when no array or object in `text` nests deeper than `maxDepth` (strings are skipped). A
    * cheap pre-scan, so a deeply nested body is refused before any recursive parser sees it.
    */
  def depthWithin(text: String, maxDepth: Int): Boolean = {
    var depth = 0
    var inString = false
    var escaped = false
    var i = 0
    while (i < text.length) {
      val c = text.charAt(i)
      if (inString) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') inString = false
      } else
        c match {
          case '"'       => inString = true
          case '[' | '{' =>
            depth += 1
            if (depth > maxDepth) return false
          case ']' | '}' => depth -= 1
          case _         => ()
        }
      i += 1
    }
    true
  }
}
