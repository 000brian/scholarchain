package org.scholarchain.er.cluster

import scala.collection.mutable

import org.scholarchain.core.{MatchPath, MentionId, ResearcherId}
import org.scholarchain.er.data.Corpus
import org.scholarchain.er.policy.Reasons

/** Path ranks: lower is stronger evidence. The assignment path of a merged cluster is its weakest
  * (highest-rank) merge path.
  */
object PathRank {
  val order: List[MatchPath] =
    List(MatchPath.ExactId, MatchPath.Deterministic, MatchPath.Probabilistic, MatchPath.Llm)
  def of(p: MatchPath): Int = order.indexOf(p)
  def path(rank: Int): MatchPath = order(rank)
}

/** Accepted pair decisions between different dblp-prior clusters, aggregated per cluster pair: the
  * best confidence and its path.
  */
final class EdgeCollector {
  private val best = mutable.HashMap.empty[Long, (Double, Int)]

  def add(nameA: Int, nameB: Int, confidence: Double, path: MatchPath): Unit =
    if (nameA != nameB) {
      val k = EdgeCollector.key(nameA, nameB)
      val r = PathRank.of(path)
      best.get(k) match {
        case Some((c, pr)) if c > confidence || (c == confidence && pr <= r) => ()
        case _ => best(k) = (confidence, r)
      }
    }

  def size: Int = best.size

  /** Edges in merge order: confidence descending, then path rank, then the two prior-cluster names
    * (code point). Independent of input order.
    */
  def sorted: Array[(Long, Double, Int)] =
    best.iterator
      .map { case (k, (c, r)) => (k, c, r) }
      .toArray
      .sortWith { (x, y) =>
        if (x._2 != y._2) x._2 > y._2
        else if (x._3 != y._3) x._3 < y._3
        else x._1 < y._1
      }
}

object EdgeCollector {
  def key(a: Int, b: Int): Long = {
    val lo = math.min(a, b)
    val hi = math.max(a, b)
    (lo.toLong << 32) | hi.toLong
  }
  def left(k: Long): Int = (k >>> 32).toInt
  def right(k: Long): Int = (k & 0xffffffffL).toInt
}

/** One final identity cluster.
  *
  * @param mergePathRank
  *   weakest merge path rank, or -1 when the cluster is a single dblp-prior cluster
  */
final case class ClusterInfo(
    researcherId: ResearcherId,
    anchor: Int,
    priorClusters: Int,
    mentions: Int,
    mergePathRank: Int,
    mergeMinConfidence: Double,
    flags: List[String]
)

/** The clustering result.
  *
  * @param rootOf
  *   final cluster root (a name index) of every dblp-prior cluster (name index)
  * @param refused
  *   refused merges: cluster-pair edge key to reason code (`merge-refused:*`)
  */
final class ClusterResult(
    val rootOf: Array[Int],
    val refused: Map[Long, String],
    val clusters: Map[Int, ClusterInfo],
    val mergesApplied: Int
) {
  def clusterOfMention(corpus: Corpus, m: Int): ClusterInfo = clusters(
    rootOf(corpus.mentions.name(m))
  )
}

/** Identity clustering (decision 5).
  *
  * Starts from one cluster per exact dblp name string (as the mention records hold it, homonym
  * suffix included, no normalization; canonically equivalent spellings are separate clusters) and
  * merges two clusters only through accepted decisions, in the order of [[EdgeCollector.sorted]]. A
  * merge is refused when the merged cluster would hold (in order of checking): two different ORCID
  * iDs (`merge-refused:orcid-conflict`); two mentions of one work (`merge-refused:same-work`); two
  * different homonym numbers of one base name (`merge-refused:dblp-distinct-homonym`); or an
  * unnumbered dblp name whose base also occurs numbered in the input together with one of those
  * numbered names (`merge-refused:dblp-disambiguation-profile`); the two mentions of a CONFLICT
  * pair decision (`merge-refused:conflict-pair`); or the two mentions of a REJECTED pair decision
  * (`merge-refused:rejected-pair`). The last two are the cannot-links of [[CannotLinks]] (repair
  * M1): a transitive chain A ~ B ~ C never puts a rejected or conflict pair (A, C) into one
  * cluster. Cannot-link sets are kept per cluster root and merged small-into-large; a check scans
  * the smaller of the two roots' sets, so memory stays at the size of the constraint list and the
  * merge order (and therefore the output) is unchanged by the guard. A dblp-prior cluster that
  * already holds two ORCID iDs or two mentions of one work is kept as it is (never split) and
  * flagged conflict. Mentions without any decision stay in their dblp-prior cluster, so every
  * mention has exactly one cluster. ResearcherIds follow the core earliest-paper rule.
  */
object Clustering {

  private final class State(
      val orcids: mutable.Set[String],
      val works: mutable.Set[Int],
      val numbered: mutable.Map[String, Set[String]],
      val disambiguationBases: mutable.Set[String],
      var size: Int,
      var priorClusters: Int,
      var pathRank: Int,
      var minConfidence: Double,
      var cannot: IntOpenSet
  )

  def run(corpus: Corpus, edges: EdgeCollector): ClusterResult =
    run(corpus, edges, CannotLinks.empty(corpus.names.size))

  def run(corpus: Corpus, edges: EdgeCollector, cannotLinks: CannotLinks): ClusterResult = {
    val names = corpus.names
    val m = corpus.mentions
    val parent = Array.tabulate(names.size)(identity)
    def find(x: Int): Int = {
      var r = x
      while (parent(r) != r) r = parent(r)
      var y = x
      while (parent(y) != r) { val n = parent(y); parent(y) = r; y = n }
      r
    }
    val states = mutable.HashMap.empty[Int, State]
    def state(root: Int): State = states.getOrElseUpdate(
      root, {
        val members = corpus.priorClusterMembers(root)
        val p = names.parts(root)
        State(
          mutable.Set.from(members.iterator.flatMap(m.orcids)),
          mutable.Set.from(members.iterator.map(m.work(_))),
          if (p.suffix.isDefined) mutable.Map(p.baseKey -> Set(p.fullKey)) else mutable.Map.empty,
          if (names.disambiguation(root)) mutable.Set(p.baseKey) else mutable.Set.empty,
          members.length,
          1,
          -1,
          1.0, {
            val s = new IntOpenSet()
            cannotLinks.partnersOf(root).foreach(s.add)
            s
          }
        )
      }
    )

    /** The strongest cannot-link kind between the clusters of roots `ra` and `rb`, if any. */
    def cannotLink(ra: Int, a: State, rb: Int, b: State): Option[Int] = {
      val (s, other) = if (a.cannot.size <= b.cannot.size) (a.cannot, rb) else (b.cannot, ra)
      var found = -1
      s.forall { v =>
        if (find(CannotLinks.partner(v)) == other) found = math.max(found, CannotLinks.kind(v))
        found != CannotLinks.Conflict
      }
      if (found < 0) None else Some(found)
    }
    val refused = mutable.HashMap.empty[Long, String]
    var merges = 0
    edges.sorted.foreach { case (k, conf, rank) =>
      val ra = find(EdgeCollector.left(k))
      val rb = find(EdgeCollector.right(k))
      if (ra != rb) {
        val a = state(ra)
        val b = state(rb)
        val reason =
          if ((a.orcids ++ b.orcids).size > 1) Some(Reasons.MergeOrcid)
          else if ({
            val (s, l) =
              if (a.works.size <= b.works.size) (a.works, b.works) else (b.works, a.works)
            s.exists(l.contains)
          }) Some(Reasons.MergeSameWork)
          else if (
            a.numbered.exists { case (base, fs) =>
              b.numbered.get(base).exists(gs => (fs ++ gs).size > 1)
            }
          )
            Some(Reasons.MergeDistinctHomonym)
          else if (
            a.disambiguationBases
              .exists(b.numbered.contains) || b.disambiguationBases.exists(a.numbered.contains)
          )
            Some(Reasons.MergeDisambiguation)
          else
            cannotLink(ra, a, rb, b).map {
              case CannotLinks.Conflict => Reasons.MergeConflictPair
              case _                    => Reasons.MergeRejectedPair
            }
        reason match {
          case Some(r) => refused(k) = r
          case None    =>
            val (big, small, bigRoot, smallRoot) =
              if (a.size > b.size || (a.size == b.size && ra < rb)) (a, b, ra, rb)
              else (b, a, rb, ra)
            parent(smallRoot) = bigRoot
            big.orcids ++= small.orcids
            big.works ++= small.works
            small.numbered.foreach { case (base, fs) =>
              big.numbered(base) = big.numbered.getOrElse(base, Set.empty) ++ fs
            }
            big.disambiguationBases ++= small.disambiguationBases
            big.size += small.size
            big.priorClusters += small.priorClusters
            big.pathRank = math.max(math.max(big.pathRank, small.pathRank), rank)
            big.minConfidence = math.min(math.min(big.minConfidence, small.minConfidence), conf)
            if (small.cannot.size > big.cannot.size) {
              val t = big.cannot; big.cannot = small.cannot; small.cannot = t
            }
            big.cannot.addAll(small.cannot)
            states.remove(smallRoot)
            merges += 1
        }
      }
    }
    val rootOf = Array.tabulate(names.size)(find)

    // Final clusters: anchor, flags.
    val byRoot = mutable.HashMap.empty[Int, mutable.ArrayBuffer[Int]]
    var n = 0
    while (n < names.size) {
      byRoot.getOrElseUpdate(rootOf(n), mutable.ArrayBuffer.empty) += n; n += 1
    }
    val clusters = byRoot.iterator.map { case (root, priors) =>
      var anchor = -1
      val seenWorks = mutable.HashSet.empty[Int]
      var sameWork = false
      val orcids = mutable.HashSet.empty[String]
      var count = 0
      priors.foreach { p =>
        corpus.priorClusterMembers(p).foreach { i =>
          count += 1
          if (!seenWorks.add(m.work(i))) sameWork = true
          orcids ++= m.orcids(i)
          if (anchor < 0 || before(corpus, i, anchor)) anchor = i
        }
      }
      val flags = List(
        if (orcids.size > 1) Some(Reasons.ClusterMultipleOrcids) else None,
        if (sameWork) Some(Reasons.ClusterSameWork) else None
      ).flatten
      val st = states.get(root)
      root -> ClusterInfo(
        ResearcherId.fromAnchorMention(MentionId(m.id(anchor))),
        anchor,
        priors.size,
        count,
        st.fold(-1)(_.pathRank),
        st.fold(1.0)(_.minConfidence),
        flags.sorted
      )
    }.toMap
    new ClusterResult(rootOf, refused.toMap, clusters, merges)
  }

  /** Core anchor order (ResearcherId.anchorOrdering): year, work ID, position. Work indices follow
    * work-ID code-point order.
    */
  def before(corpus: Corpus, x: Int, y: Int): Boolean = {
    val m = corpus.mentions
    val yx = corpus.mentionYear(x)
    val yy = corpus.mentionYear(y)
    if (yx != yy) yx < yy
    else if (m.work(x) != m.work(y)) m.work(x) < m.work(y)
    else m.position(x) < m.position(y)
  }
}
