package org.scholarchain.er.review

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.SplittableRandom

import scala.collection.mutable

import org.scholarchain.core.{CodePointOrder, MatchCandidateId, MatchFeature, MatchSubjectKind}
import org.scholarchain.er.io.ErError
import org.scholarchain.er.data.Corpus
import org.scholarchain.er.io.Csv
import org.scholarchain.er.llm.PairKey

/** What the review key (never the label file) records about one sampled decision. */
final case class SampleInfo(
    outcome: String,
    path: String,
    family: String,
    matchProbability: Option[Double],
    confidence: Double,
    nearThreshold: Boolean,
    features: List[MatchFeature],
    rulesFired: List[String],
    reasonCodes: List[String]
)

/** One sampled unit of a stratum: a decided candidate (or a dblp-prior pair without candidate). */
final case class Sampled(u: Long, candidateId: String, left: Int, right: Int, info: SampleInfo)

/** One membership of a queue row in a stratum, with its inclusion probability `sampled /
  * population` (stratified simple random sampling without replacement).
  *
  * @param outcome
  *   the decision of this membership's own sampled unit (repair m-new-1): the candidate decision
  *   for a candidate stratum, the dblp-prior identity decision for the dblp-prior stratum. A row
  *   sampled in several strata can carry different decisions per membership, and the weighted
  *   harness scores each membership with its own decision, never with the row's.
  * @param path
  *   the match path of that same decision
  */
final case class Membership(
    stratum: String,
    candidateId: String,
    population: Long,
    sampled: Int,
    outcome: String,
    path: String
) {
  def inclusionProbability: Double = sampled.toDouble / population.toDouble
}

/** @param candidateId
  *   the value of the queue's `candidateId` column: the row's MatchCandidate ID, or for a
  *   dblp-prior pair without a candidate the reference of [[ReviewQueue.priorReference]] (same
  *   form)
  * @param candidateIdKind
  *   [[CandidateIdKind.Candidate]] or [[CandidateIdKind.PriorReference]]; recorded only in the
  *   hidden review key
  * @param info
  *   the row-level decision: the decision of the MatchCandidate shown in `candidateId` when the row
  *   has one, else the dblp-prior identity decision (repair m-new-1)
  */
final case class QueueRow(
    left: Int,
    right: Int,
    candidateId: String,
    candidateIdKind: String,
    memberships: List[Membership],
    info: SampleInfo
)

object CandidateIdKind {
  val Candidate = "match-candidate"
  val PriorReference = "dblp-prior-reference"
}

/** Review-queue specification (decision 13). */
final case class QueueSpec(size: Int, priorPairs: Int, seed: Long) {
  require(size > 0, "queue size must be > 0")
  require(priorPairs >= 0 && priorPairs <= size, "prior pairs must be in [0, queue size]")
}

/** Streams decided candidates into per-stratum bottom-k samples.
  *
  * Strata: `path=<match path>;outcome=<outcome>;family=<block family>;band=<near|far>`; `band` is
  * `near` for scored (probabilistic or LLM) decisions within the policy's nearThresholdLogit of the
  * accept or reject threshold. Within a stratum the sampling key of a candidate is the first 64
  * bits of SHA-256(seed, candidate ID): the `n` smallest keys are a simple random sample of size
  * `n`, independent of input order. Every stratum keeps its `queue size` smallest keys, enough for
  * any allocation.
  */
final class StratumSampler(spec: QueueSpec) {
  private val md = MessageDigest.getInstance("SHA-256")
  private val seedBytes = (spec.seed.toString + "\u0000").getBytes(StandardCharsets.UTF_8)
  private val ord: Ordering[Sampled] =
    Ordering.fromLessThan[Sampled]((x, y) => java.lang.Long.compareUnsigned(x.u, y.u) > 0)
  private val heaps = mutable.HashMap.empty[String, mutable.PriorityQueue[Sampled]]
  val population = mutable.HashMap.empty[String, Long]

  def key(candidateId: String): Long = {
    md.reset()
    md.update(seedBytes)
    val d = md.digest(candidateId.getBytes(StandardCharsets.UTF_8))
    var x = 0L
    var i = 0
    while (i < 8) { x = (x << 8) | (d(i) & 0xffL); i += 1 }
    x
  }

  def observe(
      stratum: String,
      candidateId: String,
      left: Int,
      right: Int,
      info: => SampleInfo
  ): Unit = {
    population(stratum) = population.getOrElse(stratum, 0L) + 1
    val u = key(candidateId)
    val h = heaps.getOrElseUpdate(stratum, mutable.PriorityQueue.empty(ord))
    if (h.size < spec.size) h.enqueue(Sampled(u, candidateId, left, right, info))
    else if (java.lang.Long.compareUnsigned(u, h.head.u) < 0) {
      h.dequeue()
      h.enqueue(Sampled(u, candidateId, left, right, info))
    }
  }

  /** Kept units of each stratum in ascending key order (ties: candidate ID). */
  def kept: Map[String, Vector[Sampled]] =
    heaps.view
      .mapValues(_.toVector.sortWith { (x, y) =>
        val c = java.lang.Long.compareUnsigned(x.u, y.u)
        c < 0 || (c == 0 && CodePointOrder.lessThan(x.candidateId, y.candidateId))
      })
      .toMap
}

object StratumSampler {
  def stratum(path: String, outcome: String, family: String, near: Boolean): String =
    s"path=$path;outcome=$outcome;family=$family;band=${if (near) "near" else "far"}"

  val PriorFamily = "dblp-prior.numbered-name"
  val PriorStratum: String = stratum("deterministic", "accepted", PriorFamily, near = false)

  def fields(stratum: String): Map[String, String] =
    stratum.split(';').toList.map(_.split("=", 2)).collect { case Array(k, v) => k -> v }.toMap

  /** Allocation weight: sqrt(population), times 3 for unresolved, 2 for near-threshold, 1.5 for
    * conflict, 1.5 for the LLM path (oversampling of uncertain decisions).
    */
  def weight(stratum: String, population: Long): Double = {
    val f = fields(stratum)
    var w = math.sqrt(population.toDouble)
    if (f.get("outcome").contains("unresolved")) w *= 3.0
    if (f.get("band").contains("near")) w *= 2.0
    if (f.get("outcome").contains("conflict")) w *= 1.5
    if (f.get("path").contains("llm")) w *= 1.5
    w
  }
}

/** Builds the review queue from the sampler and the dblp-prior clusters.
  *
  *   1. dblp-prior pairs (decision 6): `priorPairs` distinct unordered pairs drawn uniformly
  *      (seeded SplittableRandom) from all pairs of mentions that share one homonym-numbered dblp
  *      name (population: sum of m(m-1)/2 over those names); these pairs have no candidate, so
  *      their `candidateId` column holds the label-blind [[ReviewQueue.priorReference]].
  *   1. Candidate strata share the rest: every non-empty stratum gets one unit (largest weight
  *      first), then units go one at a time to the stratum with the largest weight / (n + 1) (ties:
  *      stratum key), each unit being the stratum's next-smallest sampling key. A unit whose
  *      mention pair is already in the queue joins that row (one more membership) without using a
  *      row, so the queue reaches `size` distinct mention pairs unless the strata run out.
  *   1. Rows are put in seeded random order (Fisher-Yates over rows sorted by mention pair).
  */
object ReviewQueue {

  final case class Built(
      rows: Vector[QueueRow],
      strata: List[(String, Long, Int, Double)],
      priorPopulation: Long
  )

  def build(
      corpus: Corpus,
      spec: QueueSpec,
      sampler: StratumSampler,
      priorInfo: (Int, Int) => SampleInfo
  ): Built = {
    // per mention pair: (left, right, memberships (stratum, candidate ID or "" for dblp-prior, the
    // membership's own decision))
    val chosen = mutable.LinkedHashMap
      .empty[Long, (Int, Int, mutable.ArrayBuffer[(String, String, SampleInfo)])]
    val sampledCount = mutable.HashMap.empty[String, Int]

    // 1. dblp-prior pairs.
    val names = corpus.names
    val numbered = (0 until names.size).filter(n => names.parts(n).suffix.isDefined).toArray
    val sizes = numbered.map(n => corpus.priorOffsets(n + 1) - corpus.priorOffsets(n))
    val cum = new Array[Long](numbered.length + 1)
    numbered.indices.foreach(i => cum(i + 1) = cum(i) + sizes(i).toLong * (sizes(i) - 1) / 2)
    val priorPopulation = cum(numbered.length)
    val priorTarget =
      if (priorPopulation == 0) 0 else math.min(spec.priorPairs.toLong, priorPopulation).toInt
    val rnd = new SplittableRandom(spec.seed)
    var attempts = 0
    while (
      sampledCount.getOrElse(
        StratumSampler.PriorStratum,
        0
      ) < priorTarget && attempts < priorTarget * 1000
    ) {
      attempts += 1
      val r = rnd.nextLong(priorPopulation)
      var lo = 0
      var hi = numbered.length - 1
      while (lo < hi) {
        val mid = (lo + hi) >>> 1; if (cum(mid + 1) <= r) lo = mid + 1 else hi = mid
      }
      val members = corpus.priorClusterMembers(numbered(lo))
      val i = rnd.nextInt(members.length)
      var j = rnd.nextInt(members.length - 1)
      if (j >= i) j += 1
      val k = PairKey.of(members(i), members(j))
      if (!chosen.contains(k)) {
        val a = PairKey.left(k)
        val b = PairKey.right(k)
        chosen(k) = (a, b, mutable.ArrayBuffer((StratumSampler.PriorStratum, "", priorInfo(a, b))))
        sampledCount(StratumSampler.PriorStratum) =
          sampledCount.getOrElse(StratumSampler.PriorStratum, 0) + 1
      }
    }

    // 2. Candidate strata.
    val kept = sampler.kept
    val strata = sampler.population.keys.toList.sorted(CodePointOrder.ordering)
    val weights = strata.map(s => s -> StratumSampler.weight(s, sampler.population(s))).toMap
    val next = mutable.HashMap.empty[String, Int].withDefaultValue(0)
    def canGrow(s: String) = next(s) < kept.getOrElse(s, Vector.empty).size
    def take(s: String): Unit = {
      val u = kept(s)(next(s))
      next(s) += 1
      sampledCount(s) = sampledCount.getOrElse(s, 0) + 1
      val k = PairKey.of(u.left, u.right)
      chosen.get(k) match {
        case Some((_, _, ms)) => ms += ((s, u.candidateId, u.info))
        case None             =>
          chosen(k) =
            (PairKey.left(k), PairKey.right(k), mutable.ArrayBuffer((s, u.candidateId, u.info)))
      }
    }
    strata
      .sortWith((x, y) =>
        weights(x) > weights(y) || (weights(x) == weights(y) && CodePointOrder.lessThan(x, y))
      )
      .foreach { s =>
        if (chosen.size < spec.size && canGrow(s)) take(s)
      }
    var growing = true
    while (chosen.size < spec.size && growing) {
      val options = strata.filter(canGrow)
      if (options.isEmpty) growing = false
      else {
        val s = options.maxBy(s => (weights(s) / (next(s) + 1), s))(
          Ordering.Tuple2(
            Ordering.Double.TotalOrdering,
            Ordering.fromLessThan[String]((x, y) => CodePointOrder.lessThan(y, x))
          )
        )
        take(s)
      }
    }

    val population = sampler.population.toMap + (StratumSampler.PriorStratum -> priorPopulation)
    val rowsSorted = chosen.toVector.sortBy(_._1).map { case (_, (a, b, ms)) =>
      lazy val ref = priorReference(corpus, a, b)
      val memberships = ms.toList.map { case (s, c, inf) =>
        Membership(
          s,
          if (c.isEmpty) ref else c,
          population(s),
          sampledCount(s),
          inf.outcome,
          inf.path
        )
      }
      // the row shows a real candidate ID when its pair has one (the candidate that created the
      // row, else the smallest one), else the dblp-prior reference
      val real =
        if (ms.head._2.nonEmpty) Some(ms.head._2)
        else ms.map(_._2).filter(_.nonEmpty).sorted(CodePointOrder.ordering).headOption
      // the row-level decision is the decision of the shown candidate (repair m-new-1)
      val info = real.fold(ms.head._3)(r => ms.find(_._2 == r).get._3)
      QueueRow(
        a,
        b,
        real.getOrElse(ref),
        if (real.isDefined) CandidateIdKind.Candidate else CandidateIdKind.PriorReference,
        memberships,
        info
      )
    }
    val shuffled = rowsSorted.toArray
    val shuffle = new SplittableRandom(spec.seed ^ 0x5deece66dL)
    var i = shuffled.length - 1
    while (i > 0) {
      val j = shuffle.nextInt(i + 1)
      val t = shuffled(i); shuffled(i) = shuffled(j); shuffled(j) = t
      i -= 1
    }
    val strataTable =
      (strata.map(s => (s, sampler.population(s), sampledCount.getOrElse(s, 0), weights(s))) ++
        (if (priorPopulation > 0)
           List(
             (
               StratumSampler.PriorStratum,
               priorPopulation,
               sampledCount.getOrElse(StratumSampler.PriorStratum, 0),
               0.0
             )
           )
         else Nil))
        .sortWith((x, y) => CodePointOrder.lessThan(x._1, y._1))
    Built(shuffled.toVector, strataTable, priorPopulation)
  }

  // ------------------------------------------------------------------------------------------
  // Label-blind display (decision 13) and label columns (decision 14)
  // ------------------------------------------------------------------------------------------

  /** Label-blind reference of a dblp-prior pair that has no MatchCandidate (repair m1).
    *
    * A dblp-prior row must not be recognisable in the label file by an empty `candidateId`, so its
    * `candidateId` column holds `MatchCandidateId.of(mention_pair, left, right,
    * "dblp-prior.numbered-name", <author-name-key/1 full-name key of the shared dblp name>)`: the
    * ID the pair would have as a candidate of a dblp-prior block. It has the same
    * `match-candidate:v1:<32 hex>` form as every real candidate ID, is deterministic, and is unique
    * per unordered mention pair. It is a reference only: no MatchDecision carries it, and the
    * hidden review key marks it `candidateIdKind: dblp-prior-reference`. The gold reader joins on
    * the unordered mention pair and requires the label file's `candidateId` to equal the queue's,
    * exactly as for real candidates.
    */
  def priorReference(corpus: Corpus, a: Int, b: Int): String =
    MatchCandidateId
      .of(
        MatchSubjectKind.MentionPair,
        corpus.mentions.id(a),
        corpus.mentions.id(b),
        StratumSampler.PriorFamily,
        corpus.names.parts(corpus.mentions.name(a)).fullKey
      )
      .fold(e => throw new ErError(s"dblp-prior reference: $e"), _.value)

  val KeyColumns: List[String] = List("leftMentionId", "rightMentionId", "candidateId")
  val LabelColumns: List[String] = List("label", "evidence", "note", "labeler", "labeledAt")
  val SideFields: List[String] = List(
    "name",
    "dblpKey",
    "dblpUrl",
    "title",
    "venue",
    "year",
    "authorPosition",
    "teamSize",
    "coauthors",
    "orcid",
    "doiUrl"
  )
  val DisplayColumns: List[String] =
    "rowNumber" :: (SideFields.map("left_" + _) ++ SideFields.map("right_" + _))
  val Columns: List[String] =
    List("rowNumber") ++ KeyColumns ++ SideFields.map("left_" + _) ++ SideFields.map(
      "right_" + _
    ) ++ LabelColumns

  val MaxCoauthors = 15

  def side(corpus: Corpus, i: Int): List[String] = {
    val m = corpus.mentions
    val w = corpus.works
    val wi = m.work(i)
    val team = w.authorNames(wi)
    val co = team.indices.filter(_ != m.position(i)).map(team(_))
    val coText =
      co.take(MaxCoauthors).mkString("; ") + (if (co.size > MaxCoauthors)
                                                s"; ... (+${co.size - MaxCoauthors} more)"
                                              else "")
    val key = w.dblpKey(wi)
    List(
      corpus.mentionName(i), // the name as written on this mention (decision 13)
      key,
      s"https://dblp.org/rec/$key.html",
      w.title(wi),
      Option(w.venue(wi)).map(v => corpus.venueNames.getOrElse(v, v)).getOrElse(""),
      w.year(wi).toString,
      (m.position(i) + 1).toString,
      team.length.toString,
      coText,
      m.orcids(i).mkString("; "),
      Option(w.doi(wi)).map("https://doi.org/" + _).getOrElse("")
    )
  }

  /** The label-blind row values, in [[Columns]] order (label columns empty). */
  def values(corpus: Corpus, row: QueueRow, rowNumber: Int): List[String] =
    List(
      rowNumber.toString,
      corpus.mentions.id(row.left),
      corpus.mentions.id(row.right),
      row.candidateId
    ) ++
      side(corpus, row.left) ++ side(corpus, row.right) ++ LabelColumns.map(_ => "")

  def csv(corpus: Corpus, rows: Vector[QueueRow]): String = {
    val sb = new StringBuilder
    sb.append(Csv.row(Columns)).append("\r\n")
    rows.zipWithIndex.foreach { case (r, i) =>
      sb.append(Csv.row(values(corpus, r, i + 1))).append("\r\n")
    }
    sb.toString
  }

  def jsonl(corpus: Corpus, rows: Vector[QueueRow]): String = {
    val sb = new StringBuilder
    rows.zipWithIndex.foreach { case (r, i) =>
      val vs = values(corpus, r, i + 1)
      sb.append(ujson.Obj.from(Columns.zip(vs).map { case (k, v) => k -> ujson.Str(v) }).render())
        .append('\n')
    }
    sb.toString
  }

  /** The hidden review key: one line per row with strata memberships, inclusion probabilities and
    * the decision. Never shown while labeling.
    *
    * Each membership records its own `outcome` and `matchPath` (repair m-new-1). The row also
    * records the final identity assignment that Spark consumes (repair m-new-2): `leftResearcherId`
    * and `rightResearcherId` (the ResearcherIds of the two mentions' final clusters) and
    * `sameFinalCluster`, so the harness can measure the cluster assignment, not only the pair
    * decision (an unresolved or rejected pair can still end in one cluster).
    *
    * @param researcherOf
    *   the ResearcherId of a mention's final cluster
    */
  def key(corpus: Corpus, rows: Vector[QueueRow], researcherOf: Int => String): String = {
    val sb = new StringBuilder
    rows.zipWithIndex.foreach { case (r, i) =>
      val inf = r.info
      val lr = researcherOf(r.left)
      val rr = researcherOf(r.right)
      sb.append(
        ujson
          .Obj(
            "rowNumber" -> (i + 1),
            "leftMentionId" -> corpus.mentions.id(r.left),
            "rightMentionId" -> corpus.mentions.id(r.right),
            "candidateId" -> r.candidateId,
            "candidateIdKind" -> r.candidateIdKind,
            "memberships" -> ujson.Arr.from(r.memberships.map { m =>
              ujson.Obj(
                "stratum" -> m.stratum,
                "candidateId" -> m.candidateId,
                "population" -> m.population.toDouble,
                "sampled" -> m.sampled,
                "inclusionProbability" -> m.inclusionProbability,
                "outcome" -> m.outcome,
                "matchPath" -> m.path
              )
            }),
            "leftResearcherId" -> lr,
            "rightResearcherId" -> rr,
            "sameFinalCluster" -> (lr == rr),
            "outcome" -> inf.outcome,
            "matchPath" -> inf.path,
            "family" -> inf.family,
            "matchProbability" -> inf.matchProbability.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
            "confidence" -> inf.confidence,
            "nearThreshold" -> inf.nearThreshold,
            "features" -> ujson.Obj.from(inf.features.map(f => f.name -> ujson.Num(f.value))),
            "rulesFired" -> ujson.Arr.from(inf.rulesFired.map(ujson.Str(_))),
            "reasonCodes" -> ujson.Arr.from(inf.reasonCodes.map(ujson.Str(_)))
          )
          .render()
      ).append('\n')
    }
    sb.toString
  }

  def strataJson(spec: QueueSpec, built: Built): String =
    ujson
      .Obj(
        "format" -> "scholarchain.er.review-strata",
        "formatVersion" -> 1,
        "seed" -> spec.seed.toString,
        "queueSize" -> spec.size,
        "priorPairsRequested" -> spec.priorPairs,
        "rows" -> built.rows.size,
        "sampling" -> "stratified simple random sampling without replacement per stratum; inclusionProbability = sampled / population; a row can hold several memberships (one per sampled candidate of its mention pair)",
        "strata" -> ujson.Arr.from(built.strata.map { case (s, n, k, w) =>
          ujson.Obj(
            "stratum" -> s,
            "population" -> n.toDouble,
            "sampled" -> k,
            "inclusionProbability" -> (if (n == 0) 0.0 else k.toDouble / n.toDouble),
            "allocationWeight" -> w
          )
        })
      )
      .render(indent = 2) + "\n"
}
