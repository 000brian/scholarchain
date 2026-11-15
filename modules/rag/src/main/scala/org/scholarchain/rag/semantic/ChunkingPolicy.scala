package org.scholarchain.rag.semantic

/** Versioned chunking policy.
  *
  * `chunking-v1`:
  *   - Each summary (researcher profile, community, path, paper summary) is one chunk, ordinal 0.
  *   - A text longer than [[maxChars]] is split deterministically: paragraphs (separated by a blank
  *     line, `\n\n`) are packed in order, greedily, into chunks of at most `maxChars` characters,
  *     joined by `\n\n`. A single paragraph longer than `maxChars` is cut at the last sentence end
  *     (`". "`) inside the limit, else at the last space, else hard at the limit (never inside a
  *     surrogate pair). Blank pieces are dropped; each chunk is trimmed. Ordinals are 0, 1, 2, ...
  *   - `maxChars` = 4,000 for every provider: below half of the smallest supported input limit
  *     (Titan V2: 8,192 tokens), so the same text gives the same chunks whichever provider embeds
  *     it and no provider truncates. Spark texts are at most 1,197 characters (Phase 7 export), so
  *     every current document is a single chunk.
  *   - Abstracts: not applicable yet (no abstracts are collected).
  *
  * Changing any rule above requires a new version.
  */
final case class ChunkingPolicy(version: String, maxChars: Int) {
  require(maxChars >= 100, "maxChars must be >= 100")

  def split(text: String): Vector[String] =
    if (text.length <= maxChars) Vector(text)
    else {
      val pieces = text.split("\n\n", -1).toVector.map(_.trim).filter(_.nonEmpty).flatMap(cut)
      val out = Vector.newBuilder[String]
      val cur = new StringBuilder
      pieces.foreach { p =>
        if (cur.isEmpty) cur.append(p)
        else if (cur.length + 2 + p.length <= maxChars) cur.append("\n\n").append(p)
        else { out += cur.toString; cur.clear(); cur.append(p) }
      }
      if (cur.nonEmpty) out += cur.toString
      out.result()
    }

  /** Cuts one paragraph into pieces of at most `maxChars`. */
  private def cut(p: String): Vector[String] = {
    val out = Vector.newBuilder[String]
    var rest = p
    while (rest.length > maxChars) {
      val window = rest.substring(0, maxChars)
      val sentence = window.lastIndexOf(". ")
      val space = window.lastIndexOf(' ')
      var at =
        if (sentence > 0) sentence + 1
        else if (space > 0) space
        else maxChars
      if (at < rest.length && Character.isLowSurrogate(rest.charAt(at))) at -= 1
      val head = rest.substring(0, at).trim
      if (head.nonEmpty) out += head
      rest = rest.substring(at).trim
    }
    if (rest.nonEmpty) out += rest
    out.result()
  }
}

object ChunkingPolicy {
  val V1: ChunkingPolicy = ChunkingPolicy("chunking-v1", 4000)
  val current: ChunkingPolicy = V1
}
