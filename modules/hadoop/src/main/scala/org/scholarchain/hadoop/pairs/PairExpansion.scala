package org.scholarchain.hadoop.pairs

import org.scholarchain.core.{
  CoauthorPairCandidate,
  PairPosition,
  PublicationTeam,
  SchemaVersions,
  UndirectedPair
}
import org.scholarchain.hadoop.common.RunContext

/** Pair indexing for a team of `n` authors.
  *
  * The `n(n-1)/2` position pairs `(i, j)` with `i < j` are numbered in row-major order: row `i`
  * holds `(i, i+1) ... (i, n-1)`. A shard is a half-open index range, so a giant team can be
  * expanded by many reducers, each streaming its own range without holding the others.
  */
object PairIndex {

  def pairCount(n: Int): Long = if (n < 2) 0L else n.toLong * (n - 1) / 2

  /** Number of shards of at most `perShard` pairs. */
  def shardCount(n: Int, perShard: Long): Long = {
    val p = pairCount(n)
    if (p == 0) 0L else (p + perShard - 1) / perShard
  }

  /** Index range `[from, until)` of shard `s`. */
  def shardRange(n: Int, perShard: Long, s: Long): (Long, Long) = {
    val from = s * perShard
    (from, math.min(pairCount(n), from + perShard))
  }

  /** The position pair with index `k` (0-based). */
  def unrank(n: Int, k: Long): (Int, Int) = {
    require(k >= 0 && k < pairCount(n), s"pair index $k out of range for n=$n")
    var i = 0
    var remaining = k
    var rowLength = (n - 1).toLong
    while (remaining >= rowLength) {
      remaining -= rowLength
      i += 1
      rowLength -= 1
    }
    (i, i + 1 + remaining.toInt)
  }

  /** Visits the position pairs with index in `[from, until)` in index order. */
  def foreachPair(n: Int, from: Long, until: Long)(f: (Int, Int) => Unit): Unit =
    if (from < until) {
      var (i, j) = unrank(n, from)
      var k = from
      while (k < until) {
        f(i, j)
        k += 1
        j += 1
        if (j == n) { i += 1; j = i + 1 }
      }
    }
}

/** One expanded position pair: a pair candidate, or a self pair (the same name at two positions,
  * which has no canonical unordered form).
  */
sealed trait ExpandedPair
object ExpandedPair {
  final case class Candidate(pair: CoauthorPairCandidate) extends ExpandedPair
  final case class SelfPair(name: String, i: Int, j: Int) extends ExpandedPair

  /** `PairPosition.forNames` refused the positions (cannot happen for `0 <= i < j`). */
  final case class Invalid(detail: String) extends ExpandedPair
}

/** Builds pair candidates from a team.
  *
  * Every candidate (CoauthorPairCandidate v2) keeps the work ID, year and team size and carries
  * exactly one [[PairPosition]] built by core `PairPosition.forNames`: the author positions and
  * mention IDs of its left and right names. Its provenance is the team's source record (the dblp
  * key, dataset, snapshot and content hash) under this job's run; positions are not encoded in
  * `provenance.sourceRecordIds` (they live in `positions`).
  */
object PairExpansion {

  /** A team prepared for expanding many pairs: `PublicationTeam.authorNames` is a `List` (O(n) per
    * index), so it is copied once into an indexed sequence with O(1) access. Build one per shard
    * ([[PairExpandReducer]]), not one per pair.
    */
  final class IndexedTeam(val team: PublicationTeam) {
    val names: IndexedSeq[String] = team.authorNames.toVector
  }

  def index(team: PublicationTeam): IndexedTeam = new IndexedTeam(team)

  /** One pair of a single team (indexes the team on each call; for many pairs of one team use
    * [[index]] once and the [[IndexedTeam]] overload).
    */
  def pairAt(team: PublicationTeam, i: Int, j: Int, run: RunContext): ExpandedPair =
    pairAt(index(team), i, j, run)

  def pairAt(indexed: IndexedTeam, i: Int, j: Int, run: RunContext): ExpandedPair = {
    val team = indexed.team
    val a = indexed.names(i)
    val b = indexed.names(j)
    UndirectedPair.canonical(a, b) match {
      case Left(_)              => ExpandedPair.SelfPair(a, i, j)
      case Right((left, right)) =>
        PairPosition.forNames(team.workId, a, i, b, j) match {
          case Left(err)       => ExpandedPair.Invalid(s"positions ($i, $j): $err")
          case Right(position) =>
            val prov = run.provenance(
              team.provenance.sourceType,
              team.provenance.sourceRecordIds,
              team.provenance.contentHash,
              team.provenance.datasetId,
              team.provenance.snapshotId
            )
            ExpandedPair.Candidate(
              CoauthorPairCandidate(
                SchemaVersions.currentOf("CoauthorPairCandidate"),
                team.workId,
                team.year,
                left,
                right,
                List(position),
                team.teamSize,
                prov
              )
            )
        }
    }
  }
}
