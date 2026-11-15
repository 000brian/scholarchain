package org.scholarchain.rag.retrieval

import scala.collection.mutable

import org.scholarchain.rag.embedding.FakeLexicalEmbedder

/** Builds retrieval smoke queries from a document's own distinctive words: the `k` tokens of the
  * document with the lowest document frequency in its index (ties broken by the token itself),
  * joined by spaces. Template words shared by every document of a kind are therefore never chosen;
  * names and identifiers are. Deterministic.
  */
final class SmokeQueries(texts: Iterable[String]) {
  private val df: mutable.HashMap[String, Int] = {
    val m = mutable.HashMap.empty[String, Int]
    texts.foreach(t =>
      FakeLexicalEmbedder.tokens(t).distinct.foreach(x => m.update(x, m.getOrElse(x, 0) + 1))
    )
    m
  }

  def documentFrequency(token: String): Int = df.getOrElse(token, 0)

  def distinctive(text: String, k: Int): List[String] =
    FakeLexicalEmbedder
      .tokens(text)
      .distinct
      .sortBy(t => (df.getOrElse(t, 0), t))
      .take(k)
      .toList

  def query(text: String, k: Int = SmokeQueries.DefaultTokens): String =
    distinctive(text, k).mkString(" ")
}

object SmokeQueries {
  val DefaultTokens = 8
}
