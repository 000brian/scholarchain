package org.scholarchain.er.data

import org.scholarchain.core.CodePointOrder
import org.scholarchain.er.normalize.{NameParts, WorkIdentifiers}

/** Compact 128-bit stable IDs (`<kind>:v1:<32 hex>`) as two unsigned longs; the unsigned order of
  * `(hi, lo)` is the code-point order of the ID strings of one kind.
  */
object IdCodec {
  def parse(prefix: String, id: String): Option[(Long, Long)] =
    if (id == null || id.length != prefix.length + 32 || !id.startsWith(prefix)) None
    else {
      val h = id.substring(prefix.length)
      if (!h.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) None
      else
        Some(
          (
            java.lang.Long.parseUnsignedLong(h.substring(0, 16), 16),
            java.lang.Long.parseUnsignedLong(h.substring(16), 16)
          )
        )
    }

  private def hex16(x: Long): String = {
    val s = java.lang.Long.toHexString(x)
    if (s.length == 16) s else "0" * (16 - s.length) + s
  }

  def format(prefix: String, hi: Long, lo: Long): String = prefix + hex16(hi) + hex16(lo)

  def compare(h1: Long, l1: Long, h2: Long, l2: Long): Int = {
    val c = java.lang.Long.compareUnsigned(h1, h2)
    if (c != 0) c else java.lang.Long.compareUnsigned(l1, l2)
  }

  /** Index of `(h, l)` in the sorted arrays, or -1. */
  def search(his: Array[Long], los: Array[Long], h: Long, l: Long): Int = {
    var lo = 0
    var hi = his.length - 1
    while (lo <= hi) {
      val mid = (lo + hi) >>> 1
      val c = compare(his(mid), los(mid), h, l)
      if (c == 0) return mid
      if (c < 0) lo = mid + 1 else hi = mid - 1
    }
    -1
  }
}

/** Distinct dblp name strings, exactly as the mention records hold them (homonym suffix included,
  * no normalization), sorted by code point. Index = dblp-prior cluster (decision 5): canonically
  * equivalent spellings (precomposed and decomposed) are different entries; the name features and
  * merge guards compare them through the shared core keys in [[parts]].
  *
  * @param spelling
  *   the exact dblp name string of each entry, i.e. the name as written on every mention of it
  * @param parts
  *   the author-name-key/1 parts of each spelling
  */
final class NameTable(val spelling: Array[String], val parts: Array[NameParts]) {
  def size: Int = spelling.length

  /** True for an unnumbered name whose base-name key also occurs with a homonym number in the input
    * (a dblp disambiguation profile).
    */
  val disambiguation: Array[Boolean] = {
    val numberedBases = parts.iterator.filter(_.suffix.isDefined).map(_.baseKey).toSet
    parts.map(p => p.suffix.isEmpty && numberedBases.contains(p.baseKey))
  }
}

/** A mention-level external identifier from a J04 ExternalIdLink. */
final case class MentionLink(idType: String, value: String, sourceType: String)

/** Works sorted by work ID. Arrays are indexed by work index. */
final class WorkTable(
    val hi: Array[Long],
    val lo: Array[Long],
    val dblpKey: Array[String],
    val title: Array[String],
    val year: Array[Int],
    val venue: Array[String], // dblp venue key or null
    val doi: Array[String], // canonical DOI or null
    val authorNames: Array[Array[String]],
    val identifiers: Array[WorkIdentifiers],
    val titleTokens: Array[Array[Int]]
) {
  def size: Int = hi.length
  def id(i: Int): String = IdCodec.format(WorkTable.Prefix, hi(i), lo(i))
  def indexOf(workId: String): Int =
    IdCodec.parse(WorkTable.Prefix, workId).fold(-1) { case (h, l) => IdCodec.search(hi, lo, h, l) }
}
object WorkTable { val Prefix = "work:v1:" }

/** Mentions sorted by mention ID (so mention indices follow code-point order of mention IDs). */
final class MentionTable(
    val hi: Array[Long],
    val lo: Array[Long],
    val work: Array[Int],
    val position: Array[Int],
    val name: Array[Int], // name index (exact dblp name string = dblp-prior cluster)
    val dblpOrcid: Array[String], // dblp <author orcid> or null
    val links: Array[List[MentionLink]]
) {
  def size: Int = hi.length
  def id(i: Int): String = IdCodec.format(MentionTable.Prefix, hi(i), lo(i))
  def indexOf(mentionId: String): Int =
    IdCodec.parse(MentionTable.Prefix, mentionId).fold(-1) { case (h, l) =>
      IdCodec.search(hi, lo, h, l)
    }

  /** Every ORCID iD of the mention (dblp attribute and J04 links), distinct, sorted. */
  def orcids(i: Int): List[String] =
    (Option(dblpOrcid(i)).toList ++ links(i)
      .filter(_.idType == "orcid")
      .map(_.value)).distinct.sorted

  def linked(i: Int, idType: String): List[MentionLink] = links(i).filter(_.idType == idType)
}
object MentionTable { val Prefix = "mention:v1:" }

/** Everything entity resolution reads about the subset, held in memory (mention-sized arrays; the
  * candidates are streamed).
  */
final class Corpus(
    val names: NameTable,
    val works: WorkTable,
    val mentions: MentionTable,
    val venueNames: Map[String, String],
    val snapshotId: String
) {

  /** Mention indices grouped by dblp-prior cluster (name index): CSR offsets and members. */
  private lazy val prior: (Array[Int], Array[Int]) = {
    val counts = new Array[Int](names.size + 1)
    var i = 0
    while (i < mentions.size) { counts(mentions.name(i) + 1) += 1; i += 1 }
    var k = 0
    while (k < names.size) { counts(k + 1) += counts(k); k += 1 }
    val members = new Array[Int](mentions.size)
    val fill = counts.clone()
    i = 0
    while (i < mentions.size) {
      val n = mentions.name(i)
      members(fill(n)) = i
      fill(n) += 1
      i += 1
    }
    (counts, members)
  }

  /** CSR offsets: members of prior cluster `n` are `priorMembers(priorOffsets(n) until
    * priorOffsets(n + 1))`, in mention-index order.
    */
  def priorOffsets: Array[Int] = prior._1
  def priorMembers: Array[Int] = prior._2

  def priorClusterMembers(nameIdx: Int): Array[Int] =
    java.util.Arrays.copyOfRange(priorMembers, priorOffsets(nameIdx), priorOffsets(nameIdx + 1))

  /** The mention's dblp name as written (its own exact spelling). */
  def mentionName(i: Int): String = names.spelling(mentions.name(i))
  def mentionYear(i: Int): Int = works.year(mentions.work(i))
}

object NameOrder {
  val ordering: Ordering[String] = CodePointOrder.ordering
}
