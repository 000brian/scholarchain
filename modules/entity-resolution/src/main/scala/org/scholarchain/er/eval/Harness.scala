package org.scholarchain.er.eval

import java.nio.file.Path
import java.util.SplittableRandom

import scala.collection.mutable
import scala.util.Try

import org.scholarchain.er.io.DataFiles

/** The decision of one stratum membership of a review-key row (repair m-new-1). */
final case class MembershipDecision(
    stratum: String,
    population: Double,
    outcome: String,
    path: String
)

/** A review-key row (the hidden decision of one queue row).
  *
  * @param outcome
  *   the row-level pair decision (the decision of the candidate shown in the queue, else the
  *   dblp-prior identity decision)
  * @param membershipDecisions
  *   `(outcome, path)` of each membership, aligned with `memberships` (repair m-new-1). The review
  *   key always records them and [[KeyRow.readAll]] requires them; empty only for a row built in
  *   memory, where every membership then carries the row-level decision.
  * @param leftResearcherId
  *   the ResearcherId of the left mention's final cluster (repair m-new-2)
  * @param rightResearcherId
  *   the ResearcherId of the right mention's final cluster
  * @param sameFinalCluster
  *   whether the two mentions end in one final cluster (the assignment Spark consumes); `None` only
  *   for a row built in memory without cluster information
  */
final case class KeyRow(
    left: String,
    right: String,
    candidateId: String,
    memberships: List[(String, Double)], // (stratum, population)
    outcome: String,
    path: String,
    family: String,
    matchProbability: Option[Double],
    confidence: Double,
    features: List[(String, Double)],
    rulesFired: List[String],
    reasonCodes: List[String],
    membershipDecisions: List[(String, String)] = Nil,
    leftResearcherId: Option[String] = None,
    rightResearcherId: Option[String] = None,
    sameFinalCluster: Option[Boolean] = None
) {
  require(
    membershipDecisions.isEmpty || membershipDecisions.size == memberships.size,
    s"row $left / $right: ${membershipDecisions.size} membership decisions for ${memberships.size} memberships"
  )

  /** Every membership with its own decision. */
  def decisions: List[MembershipDecision] =
    if (membershipDecisions.isEmpty)
      memberships.map { case (s, p) => MembershipDecision(s, p, outcome, path) }
    else
      memberships.zip(membershipDecisions).map { case ((s, p), (o, mp)) =>
        MembershipDecision(s, p, o, mp)
      }
}

object KeyRow {
  def readAll(file: Path): Map[(String, String), KeyRow] = {
    val out = mutable.HashMap.empty[(String, String), KeyRow]
    DataFiles.readLines(file, file.toString) { (line, n) =>
      if (line.trim.nonEmpty) {
        def bad(msg: String) = throw new IllegalArgumentException(s"$file:$n: $msg")
        val o = Try(ujson.read(line).obj).getOrElse(bad("not a JSON object"))
        def field(obj: collection.Map[String, ujson.Value], k: String): ujson.Value =
          obj.getOrElse(k, bad(s"missing field '$k'"))
        val ms = field(o, "memberships").arr.toList.map(_.obj)
        val lr = field(o, "leftResearcherId").str
        val rr = field(o, "rightResearcherId").str
        val same = field(o, "sameFinalCluster").bool
        if (same != (lr == rr))
          bad(s"sameFinalCluster $same disagrees with the ResearcherIds $lr / $rr")
        val r = KeyRow(
          field(o, "leftMentionId").str,
          field(o, "rightMentionId").str,
          field(o, "candidateId").str,
          ms.map(m => (field(m, "stratum").str, field(m, "population").num)),
          field(o, "outcome").str,
          field(o, "matchPath").str,
          field(o, "family").str,
          field(o, "matchProbability") match { case ujson.Null => None; case v => Some(v.num) },
          field(o, "confidence").num,
          field(o, "features").obj.toList.map { case (k, v) => k -> v.num },
          field(o, "rulesFired").arr.toList.map(_.str),
          field(o, "reasonCodes").arr.toList.map(_.str),
          ms.map(m => (field(m, "outcome").str, field(m, "matchPath").str)),
          Some(lr),
          Some(rr),
          Some(same)
        )
        out((r.left, r.right)) = r
      }
    }
    out.toMap
  }
}

/** Proportion with a 95% Wilson score interval.
  *
  * @param units
  *   the number of labeled units in the denominator (the sample size n)
  * @param effectiveN
  *   Kish effective sample size of the denominator units, `(sum w)^2 / sum w^2`; equals `units`
  *   when every weight is equal
  * @param rows
  *   the number of distinct labeled rows (review-queue pairs, the unit of labeling) behind the
  *   denominator units; for weighted metrics a row sampled in several strata is one row but several
  *   units (memberships), so `rows <= units`
  */
final case class Proportion(
    numerator: Double,
    denominator: Double,
    units: Int = -1,
    effectiveN: Double = Double.NaN,
    rows: Int = -1
) {
  def value: Option[Double] = if (denominator > 0) Some(numerator / denominator) else None

  /** Wilson interval with n = `denominator`. Valid only for unweighted counts (every weight 1); for
    * weighted metrics the summed weight is an estimated population count, not a sample size, and
    * this interval would be falsely narrow (repair M2): use the stratified bootstrap instead.
    */
  def wilson: Option[(Double, Double)] =
    if (denominator <= 0) None
    else {
      val z = 1.959963984540054
      val n = denominator
      val p = numerator / n
      val centre = (p + z * z / (2 * n)) / (1 + z * z / n)
      val half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
      Some((math.max(0.0, centre - half), math.min(1.0, centre + half)))
    }
}

/** One evaluation unit: a decision with its human label and weight. `row` identifies the labeled
  * review-queue row the unit comes from (several units share a row when it has several stratum
  * memberships).
  */
final case class EvalUnit(
    outcome: String,
    label: String,
    weight: Double,
    stratum: String,
    row: String = ""
)

/** Metrics of a set of units (decision 15). Positive = accepted (predicted) and MATCH (label).
  *
  *   - precision = accepted & MATCH / accepted & (MATCH or NON_MATCH)
  *   - recall = accepted & MATCH / MATCH (a MATCH left rejected, unresolved or conflict is missed)
  *   - F1 = harmonic mean of precision and recall
  *   - coverage = (accepted or rejected) / units labeled MATCH or NON_MATCH
  *   - unresolvedRate = unresolved / all labeled units (UNSURE included)
  *   - confusion = 4 decisions x 3 labels (weighted sums)
  */
final case class Metrics(units: List[EvalUnit]) {
  private def w(p: EvalUnit => Boolean) = units.filter(p).map(_.weight).sum
  private def labeledMN(u: EvalUnit) = u.label == "MATCH" || u.label == "NON_MATCH"
  private def isAccepted(u: EvalUnit) = u.outcome == "accepted"
  val tp: Double = w(u => isAccepted(u) && u.label == "MATCH")
  val fp: Double = w(u => isAccepted(u) && u.label == "NON_MATCH")
  val matches: Double = w(_.label == "MATCH")
  val decidable: Double = w(labeledMN)
  val decided: Double = w(u => labeledMN(u) && (isAccepted(u) || u.outcome == "rejected"))

  /** A proportion whose denominator is the units satisfying `den`. */
  private def prop(num: Double, den: EvalUnit => Boolean): Proportion = {
    val us = units.filter(den)
    val ws = us.map(_.weight)
    val s = ws.sum
    val s2 = ws.map(x => x * x).sum
    Proportion(num, s, ws.size, if (s2 > 0) s * s / s2 else 0.0, us.map(_.row).distinct.size)
  }
  val precision: Proportion = prop(tp, u => isAccepted(u) && labeledMN(u))
  val recall: Proportion = prop(tp, _.label == "MATCH")
  val coverage: Proportion = prop(decided, labeledMN)
  val unresolvedRate: Proportion = prop(w(_.outcome == "unresolved"), _ => true)
  val f1: Option[Double] = for {
    p <- precision.value; r <- recall.value if p + r > 0
  } yield 2 * p * r / (p + r)
  def confusion: Map[(String, String), Double] =
    (for { o <- Harness.Outcomes; l <- Harness.LabelOrder } yield (o, l) -> w(u =>
      u.outcome == o && u.label == l
    )).toMap
}

/** The gold-set evaluation harness (decision 15). Reads only: the gold labels, the review queue,
  * the review key (all paths from the command line), and optionally the work-resolution summary.
  * Never changes policy er-v1: the threshold sweep is reporting only.
  */
object Harness {
  val Outcomes: List[String] = List("accepted", "rejected", "unresolved", "conflict")
  val LabelOrder: List[String] = List("MATCH", "NON_MATCH", "UNSURE")
  val AcceptGrid: List[Double] = List(0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 0.99)
  val RejectGrid: List[Double] = List(0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5)

  final case class Labeled(gold: GoldLabel, key: KeyRow)

  def join(gold: GoldSet, key: Map[(String, String), KeyRow]): List[Labeled] =
    gold.labels
      .map { g =>
        Labeled(
          g,
          key.getOrElse(
            (g.leftMentionId, g.rightMentionId),
            throw new IllegalArgumentException(
              s"pair ${g.leftMentionId} / ${g.rightMentionId} has no review-key row"
            )
          )
        )
      }
      .sortBy(l => (l.gold.leftMentionId, l.gold.rightMentionId))

  private def num(o: Option[Double]): ujson.Value = o.fold[ujson.Value](ujson.Null)(ujson.Num(_))

  private def interval(i: Option[(Double, Double)]): ujson.Value =
    i.fold[ujson.Value](ujson.Null) { case (l, h) => ujson.Arr(l, h) }

  val WilsonMethod = "wilson-score (n = labeled units)"
  val BootstrapMethod =
    "stratified-bootstrap-percentile (resamples labeled rows, the unit of labeling, within resampling strata with fixed design weights; single-row strata collapsed; the summed weights are never used as n)"

  /** Metrics of unweighted units: every proportion carries its Wilson interval (`wilson95`, also as
    * `interval95`), with n = labeled units.
    */
  def metricsJson(m: Metrics): ujson.Obj = metricsJson(m, None)

  /** With `bootstrap` (weighted metrics, repair M2): `wilson95` is null because the summed weight
    * is an estimated population count, not a sample size; `interval95` is the stratified bootstrap
    * percentile interval; `labeledUnits` counts the denominator units (stratum memberships),
    * `labeledRows` the distinct labeled rows behind them (the bootstrap resampling unit; a row in
    * several strata is one row) and `kishEffectiveN` the Kish effective sample size of the units.
    */
  def metricsJson(
      m: Metrics,
      bootstrap: Option[Map[String, Option[(Double, Double)]]]
  ): ujson.Obj = {
    def prop(name: String, p: Proportion) = ujson.Obj(
      "value" -> num(p.value),
      "numerator" -> p.numerator,
      "denominator" -> p.denominator,
      "labeledUnits" -> p.units,
      "labeledRows" -> p.rows,
      "kishEffectiveN" -> p.effectiveN,
      "wilson95" -> (if (bootstrap.isEmpty) interval(p.wilson) else ujson.Null),
      "interval95" -> bootstrap.fold(interval(p.wilson))(b => interval(b.getOrElse(name, None))),
      "intervalMethod" -> (if (bootstrap.isEmpty) WilsonMethod else BootstrapMethod)
    )
    ujson.Obj(
      "units" -> m.units.size,
      "precision" -> prop("precision", m.precision),
      "recall" -> prop("recall", m.recall),
      "f1" -> num(m.f1),
      "f1Interval95" -> bootstrap.fold[ujson.Value](ujson.Null)(b =>
        interval(b.getOrElse("f1", None))
      ),
      "coverage" -> prop("coverage", m.coverage),
      "unresolvedRate" -> prop("unresolvedRate", m.unresolvedRate),
      "confusion" -> ujson.Obj.from(
        Outcomes.map(o =>
          o -> ujson.Obj.from(LabelOrder.map(l => l -> ujson.Num(m.confusion((o, l)))))
        )
      )
    )
  }

  /** Units weighted for population estimates: each sampled membership (one decided candidate, or
    * one dblp-prior pair) in stratum h weighs population_h / labeled_h, where labeled_h counts the
    * labeled memberships of h (inverse inclusion probability, adjusted for rows left unlabeled).
    */
  def weightedUnits(labeled: List[Labeled]): List[EvalUnit] =
    weightedUnitsBy(labeled)((_, d) => d.outcome)

  /** Weighted units whose predicted outcome is `outcomeOf(row, membership)`. Pair-level metrics use
    * each membership's own decision (repair m-new-1): a row sampled both as a dblp-prior pair and
    * as a candidate is scored once with the dblp-prior decision and once with the candidate
    * decision, each with the weight of its own stratum.
    */
  def weightedUnitsBy(
      labeled: List[Labeled]
  )(outcomeOf: (Labeled, MembershipDecision) => String): List[EvalUnit] = {
    val ms = labeled.flatMap(l => l.key.decisions.map(d => (d, l)))
    val perStratum = ms.groupBy(_._1.stratum).view.mapValues(_.size).toMap
    ms.map { case (d, l) =>
      EvalUnit(
        outcomeOf(l, d),
        l.gold.label,
        d.population / perStratum(d.stratum),
        d.stratum,
        rowId(l)
      )
    }
  }

  /** The labeled row (the unit of labeling) a unit belongs to: its unordered mention-ID pair. */
  def rowId(l: Labeled): String = s"${l.key.left} ${l.key.right}"

  val MetricNames: List[String] = List("precision", "recall", "f1", "coverage", "unresolvedRate")

  /** One bootstrap resampling group: labeled rows drawn with replacement, `rows.size` per
    * replicate.
    */
  final case class BootstrapGroup(
      name: String,
      strata: List[String],
      rows: Vector[Labeled],
      collapsed: Boolean
  )

  /** The bootstrap design (test-engineer finding 3). `unavailable` explains why no interval can be
    * given (fewer than two labeled rows with a stratum membership).
    */
  final case class BootstrapDesign(groups: List[BootstrapGroup], unavailable: Option[String])

  val ResamplingRule: String =
    "The resampling unit is the labeled row (one review-queue pair), never a stratum membership: " +
      "a row sampled in several strata is drawn once and brings all its memberships, each with " +
      "its fixed design weight population_h / labeledMemberships_h. Each row is resampled within " +
      "its resampling stratum, the first of its membership strata in string order."
  val CollapseRule: String =
    "A resampling stratum with a single labeled row would contribute no bootstrap variance, so " +
      "it is collapsed: all single-row resampling strata are pooled into one group; if exactly one " +
      "exists, it joins the smallest other group (fewest rows, then name). With fewer than two " +
      "labeled rows in total no interval is reported."
  val CollapsedPrefix = "collapsed:"

  /** The first membership stratum of a row (string order). */
  def resamplingStratum(k: KeyRow): String = k.memberships.map(_._1).min

  def bootstrapDesign(labeled: List[Labeled]): BootstrapDesign = {
    val rows = labeled.filter(_.key.memberships.nonEmpty)
    if (rows.size < 2)
      BootstrapDesign(
        Nil,
        Some(
          s"${rows.size} labeled row(s) with a stratum membership: a bootstrap needs at least two"
        )
      )
    else {
      val by = rows.groupBy(l => resamplingStratum(l.key)).toList.sortBy(_._1)
      val (single, multi) = by.partition(_._2.size == 1)
      def collapsedGroup(strata: List[String], ls: Vector[Labeled]) = {
        val names = strata.sorted
        BootstrapGroup(CollapsedPrefix + names.mkString(" + "), names, ls, collapsed = true)
      }
      val base = multi.map { case (s, ls) => BootstrapGroup(s, List(s), ls.toVector, false) }
      val groups =
        if (single.isEmpty) base
        else if (single.size >= 2 || base.isEmpty)
          base :+ collapsedGroup(single.map(_._1), single.flatMap(_._2).toVector)
        else {
          val (s, ls) = single.head
          val target = base.minBy(g => (g.rows.size, g.name))
          base.map(g =>
            if (g.name == target.name) collapsedGroup(g.strata :+ s, g.rows ++ ls) else g
          )
        }
      BootstrapDesign(groups.sortBy(_.name), None)
    }
  }

  /** Stratified bootstrap (seeded) of the weighted metrics: 95% percentile intervals. Labeled rows
    * are resampled within the groups of [[bootstrapDesign]] (see [[ResamplingRule]] and
    * [[CollapseRule]]); each drawn row contributes all its weighted memberships.
    */
  def bootstrap(
      labeled: List[Labeled],
      reps: Int,
      seed: Long
  ): Map[String, Option[(Double, Double)]] =
    bootstrapOf(labeled, weightedUnits(labeled), reps, seed)

  /** The same stratified bootstrap over given weighted units of `labeled` (one row id per row). */
  def bootstrapOf(
      labeled: List[Labeled],
      units: List[EvalUnit],
      reps: Int,
      seed: Long
  ): Map[String, Option[(Double, Double)]] = {
    val design = bootstrapDesign(labeled)
    if (design.unavailable.isDefined) MetricNames.map(_ -> None).toMap
    else bootstrapGroups(design.groups, units.groupBy(_.row), reps, seed)
  }

  private def bootstrapGroups(
      groups: List[BootstrapGroup],
      unitsOf: Map[String, List[EvalUnit]],
      reps: Int,
      seed: Long
  ): Map[String, Option[(Double, Double)]] = {
    val rnd = new SplittableRandom(seed)
    val byGroup = groups.map(_.rows.map(l => unitsOf(rowId(l))))
    val samples = Map(
      "precision" -> mutable.ArrayBuffer.empty[Double],
      "recall" -> mutable.ArrayBuffer.empty[Double],
      "f1" -> mutable.ArrayBuffer.empty[Double],
      "coverage" -> mutable.ArrayBuffer.empty[Double],
      "unresolvedRate" -> mutable.ArrayBuffer.empty[Double]
    )
    (1 to reps).foreach { _ =>
      val draw = byGroup.flatMap(v => Vector.fill(v.size)(v(rnd.nextInt(v.size)))).flatten
      val m = Metrics(draw)
      m.precision.value.foreach(samples("precision") += _)
      m.recall.value.foreach(samples("recall") += _)
      m.f1.foreach(samples("f1") += _)
      m.coverage.value.foreach(samples("coverage") += _)
      m.unresolvedRate.value.foreach(samples("unresolvedRate") += _)
    }
    samples.map { case (k, xs) =>
      val s = xs.sorted
      k -> (if (s.isEmpty) None
            else
              Some((s(((s.size - 1) * 0.025).round.toInt), s(((s.size - 1) * 0.975).round.toInt))))
    }
  }

  private def rowUnits(ls: List[Labeled]): List[EvalUnit] =
    ls.map(l => EvalUnit(l.key.outcome, l.gold.label, 1.0, "", rowId(l)))

  /** The sampling design as evaluated: per stratum, labeled memberships, distinct labeled rows, the
    * design weight and a single-labeled-row flag; the bootstrap groups; the rules.
    */
  def designJson(labeled: List[Labeled], design: BootstrapDesign): ujson.Obj = {
    val ms = labeled.flatMap(l => l.key.memberships.map { case (s, pop) => (s, pop, l) })
    val strata = ms.groupBy(_._1).toList.sortBy(_._1).map { case (s, xs) =>
      val rows = xs.map(x => rowId(x._3)).distinct.size
      s -> ujson.Obj(
        "population" -> xs.head._2,
        "labeledMemberships" -> xs.size,
        "labeledRows" -> rows,
        "weight" -> xs.head._2 / xs.size,
        "singleLabeledRow" -> (rows == 1)
      )
    }
    ujson.Obj(
      "labeledRows" -> labeled.count(_.key.memberships.nonEmpty),
      "labeledMemberships" -> ms.size,
      "resamplingUnit" -> "labeled row",
      "resamplingRule" -> ResamplingRule,
      "collapseRule" -> CollapseRule,
      "singleLabeledRowStrata" -> ujson.Arr.from(
        strata.collect { case (s, o) if o("singleLabeledRow").bool => ujson.Str(s) }
      ),
      "strata" -> ujson.Obj.from(strata),
      "bootstrapGroups" -> ujson.Arr.from(design.groups.map { g =>
        ujson.Obj(
          "name" -> g.name,
          "strata" -> ujson.Arr.from(g.strata.map(ujson.Str(_))),
          "rows" -> g.rows.size,
          "collapsed" -> g.collapsed
        )
      }),
      "bootstrapUnavailable" -> design.unavailable.fold[ujson.Value](ujson.Null)(ujson.Str(_))
    )
  }

  /** Outcome of a row under swept thresholds: only scored rows (probabilistic or LLM path, with a
    * match probability, no cluster guard) are re-thresholded; LLM effects are not simulated.
    */
  def sweptOutcome(k: KeyRow, accept: Double, reject: Double): String =
    k.matchProbability match {
      case Some(p)
          if (k.path == "probabilistic" || k.path == "llm") && !k.rulesFired.exists(
            _.startsWith("cluster.guard.")
          ) =>
        if (p >= accept) "accepted" else if (p < reject) "rejected" else "unresolved"
      case _ => k.outcome
    }

  def evaluate(
      gold: GoldSet,
      key: Map[(String, String), KeyRow],
      workSummary: Option[ujson.Value],
      seed: Long,
      reps: Int,
      note: Option[String]
  ): ujson.Obj = {
    val labeled = join(gold, key)
    val overall = Metrics(rowUnits(labeled))
    val weighted = Metrics(weightedUnits(labeled))
    val design = bootstrapDesign(labeled)
    val boot = bootstrap(labeled, reps, seed)
    // Breakdowns score each row by the decision of its membership in the group (repair m-new-1):
    // one unit per (group, labeled row), from the row's first membership (stratum order) in that
    // group. A row without memberships counts only in the path breakdown, under its row decision.
    def breakdown(group: MembershipDecision => String, rowFallback: Boolean = false) = {
      val entries = labeled.flatMap { l =>
        val ds = l.key.decisions match {
          case Nil if rowFallback => List(MembershipDecision("", 0.0, l.key.outcome, l.key.path))
          case xs                 => xs.sortBy(_.stratum)
        }
        ds.groupBy(group).toList.map { case (g, d) =>
          g -> EvalUnit(d.head.outcome, l.gold.label, 1.0, "", rowId(l))
        }
      }
      ujson.Obj.from(entries.groupBy(_._1).toList.sortBy(_._1).map { case (g, us) =>
        g -> metricsJson(Metrics(us.map(_._2)))
      })
    }
    def onlyNumbered(l: Labeled) = l.gold.evidence == List("dblp_numbered_name")
    val numberedOnly = labeled.filter(onlyNumbered)
    val independent = labeled.filter(l => l.gold.evidence.nonEmpty && !onlyNumbered(l))
    def errors(ls: List[Labeled]) = ujson.Arr.from(ls.take(10).map { l =>
      ujson.Obj(
        "leftMentionId" -> l.gold.leftMentionId,
        "rightMentionId" -> l.gold.rightMentionId,
        "candidateId" -> l.gold.candidateId,
        "label" -> l.gold.label,
        "evidence" -> ujson.Arr.from(l.gold.evidence.map(ujson.Str(_))),
        "note" -> l.gold.note,
        "outcome" -> l.key.outcome,
        "matchPath" -> l.key.path,
        "family" -> l.key.family,
        "matchProbability" -> num(l.key.matchProbability),
        "features" -> ujson.Obj.from(l.key.features.map { case (k, v) => k -> ujson.Num(v) }),
        "rulesFired" -> ujson.Arr.from(l.key.rulesFired.map(ujson.Str(_))),
        "reasonCodes" -> ujson.Arr.from(l.key.reasonCodes.map(ujson.Str(_)))
      )
    })
    val fps = labeled.filter(l => l.key.outcome == "accepted" && l.gold.label == "NON_MATCH")
    val fns = labeled.filter(l => l.key.outcome != "accepted" && l.gold.label == "MATCH")
    val sweep = for { a <- AcceptGrid; r <- RejectGrid if r <= a } yield {
      val m = Metrics(labeled.map(l => EvalUnit(sweptOutcome(l.key, a, r), l.gold.label, 1.0, "")))
      ujson.Obj(
        "accept" -> a,
        "reject" -> r,
        "precision" -> num(m.precision.value),
        "recall" -> num(m.recall.value),
        "coverage" -> num(m.coverage.value),
        "unresolvedRate" -> num(m.unresolvedRate.value)
      )
    }
    ujson.Obj(
      "format" -> "scholarchain.er.evaluation",
      "formatVersion" -> 1,
      "note" -> note.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "labeledRows" -> labeled.size,
      "unlabeledRows" -> gold.unlabeledRows,
      "researcherResolution" -> ujson.Obj(
        "unweighted" -> metricsJson(overall),
        "weighted" -> ujson.Obj(
          "description" -> "Horvitz-Thompson style: memberships weighted by population / labeled memberships of their stratum; 95% intervals from a seeded stratified bootstrap that resamples labeled rows (see design)",
          "metrics" -> metricsJson(weighted, Some(boot)),
          "design" -> designJson(labeled, design),
          "bootstrap95" -> ujson.Obj.from(boot.toList.sortBy(_._1).map { case (k, v) =>
            k -> v.fold[ujson.Value](ujson.Null) { case (l, h) => ujson.Arr(l, h) }
          }),
          "bootstrapReps" -> reps,
          "seed" -> seed.toString
        ),
        "byMatchPath" -> breakdown(_.path, rowFallback = true),
        "byFamily" -> breakdown(d => fieldOf(d.stratum, "family")),
        "byStratum" -> breakdown(_.stratum),
        "evidence" -> ujson.Obj(
          "dblpNumberedNameOnly" -> metricsJson(Metrics(rowUnits(numberedOnly))),
          "independentEvidence" -> metricsJson(Metrics(rowUnits(independent))),
          "noEvidence" -> labeled.count(_.gold.evidence.isEmpty)
        ),
        "errorAnalysis" -> ujson.Obj(
          "falsePositives" -> errors(fps),
          "falsePositivesTotal" -> fps.size,
          "falseNegatives" -> errors(fns),
          "falseNegativesTotal" -> fns.size
        ),
        "thresholdSensitivity" -> ujson.Obj(
          "description" -> "Reporting only; never changes er-v1. Scored rows re-thresholded on their match probability; other rows keep their outcome.",
          "grid" -> ujson.Arr.from(sweep)
        )
      ),
      "researcherClusterAssignment" -> clusterAssignment(labeled, reps, seed),
      "workResolution" -> ujson.Obj(
        "labels" -> "none: the review queue holds researcher mention pairs only, so work-resolution precision and recall cannot be estimated",
        "summary" -> workSummary.getOrElse(ujson.Null)
      )
    )
  }

  val SameCluster = "sameFinalCluster"
  val DifferentClusters = "differentFinalClusters"

  /** The cluster-level outcome of a row, in [[Metrics]] terms: `accepted` when the two mentions end
    * in one final cluster (predicted MATCH), `rejected` otherwise.
    */
  private def clusterOutcome(l: Labeled): String =
    if (l.key.sameFinalCluster.contains(true)) "accepted" else "rejected"

  /** Cluster-level evaluation (repair m-new-2): the label against whether the two mentions share a
    * final ResearcherId, i.e. the identity assignment Spark consumes, reported apart from the
    * pair-level decision metrics. Positive = same final cluster (predicted) and MATCH (label). Rows
    * without cluster information (only rows built in memory) are counted and left out.
    */
  def clusterAssignment(labeled: List[Labeled], reps: Int, seed: Long): ujson.Obj = {
    val withCluster = labeled.filter(_.key.sameFinalCluster.isDefined)
    val unweighted = Metrics(
      withCluster.map(l => EvalUnit(clusterOutcome(l), l.gold.label, 1.0, "", rowId(l)))
    )
    val wUnits = weightedUnitsBy(withCluster)((l, _) => clusterOutcome(l))
    val weighted = Metrics(wUnits)
    val boot = bootstrapOf(withCluster, wUnits, reps, seed)
    def render(m: Metrics, b: Option[Map[String, Option[(Double, Double)]]]): ujson.Obj = {
      val j = metricsJson(m, b)
      j.value.remove("coverage")
      j.value.remove("unresolvedRate")
      j("confusion") = ujson.Obj(
        SameCluster -> ujson.Obj.from(
          LabelOrder.map(l => l -> ujson.Num(m.confusion(("accepted", l))))
        ),
        DifferentClusters -> ujson.Obj.from(
          LabelOrder.map(l => l -> ujson.Num(m.confusion(("rejected", l))))
        )
      )
      j
    }
    // how the pair decisions of the labeled rows relate to the final clusters (unweighted rows)
    val byPair = withCluster
      .groupBy(l => (l.key.outcome, clusterOutcome(l) == "accepted"))
      .view
      .mapValues(_.size)
      .toMap
    ujson.Obj(
      "description" -> "Cluster level: the label against whether the two mentions share a final ResearcherId (the identity assignment Spark consumes). Positive = same final cluster and MATCH. Reported apart from the pair-level decision metrics: an unresolved or rejected pair can still end in one cluster (for example inside one dblp-prior cluster), and an accepted pair can end in two clusters when a merge guard refused it.",
      "labeledRowsWithClusterInfo" -> withCluster.size,
      "labeledRowsWithoutClusterInfo" -> (labeled.size - withCluster.size),
      "unweighted" -> render(unweighted, None),
      "weighted" -> ujson.Obj(
        "description" -> "memberships weighted by population / labeled memberships of their stratum (as for pair level); every membership of a row carries the row's cluster outcome; 95% intervals from the same seeded stratified bootstrap of labeled rows",
        "metrics" -> render(weighted, Some(boot)),
        "bootstrap95" -> ujson.Obj.from(
          List("precision", "recall", "f1").map(k =>
            k -> boot
              .getOrElse(k, None)
              .fold[ujson.Value](ujson.Null) { case (l, h) => ujson.Arr(l, h) }
          )
        )
      ),
      "pairOutcomeByFinalCluster" -> ujson.Obj.from(Outcomes.map { o =>
        o -> ujson.Obj(
          SameCluster -> byPair.getOrElse((o, true), 0),
          DifferentClusters -> byPair.getOrElse((o, false), 0)
        )
      })
    )
  }

  def fieldOf(stratum: String, field: String): String =
    stratum
      .split(';')
      .map(_.split("=", 2))
      .collectFirst { case Array(k, v) if k == field => v }
      .getOrElse("")

  def markdown(m: ujson.Obj): String = {
    val sb = new StringBuilder
    val rr = m("researcherResolution")
    def pct(v: ujson.Value) = v match {
      case ujson.Null => "n/a"; case x => f"${x.num * 100}%.1f%%"
    }
    def ci(v: ujson.Value) = v match {
      case ujson.Null => "n/a";
      case a          => f"[${a.arr(0).num * 100}%.1f%%, ${a.arr(1).num * 100}%.1f%%]"
    }
    def line(name: String, x: ujson.Value) =
      sb.append(
        s"| $name | ${x("units").num.toInt} | ${pct(x("precision")("value"))} ${ci(x("precision")("interval95"))} | ${pct(x("recall")("value"))} ${ci(x("recall")("interval95"))} | ${pct(x("f1"))} | ${pct(x("coverage")("value"))} | ${pct(x("unresolvedRate")("value"))} |\n"
      )
    sb.append("# Entity-resolution gold-set evaluation\n\n")
    m("note") match { case ujson.Str(s) => sb.append(s"Note: $s\n\n"); case _ => () }
    sb.append(
      s"Labeled rows: ${m("labeledRows").num.toInt}; unlabeled queue rows: ${m("unlabeledRows").num.toInt}.\n\n"
    )
    sb.append(
      "## Researcher resolution\n\nPrecision and recall show a 95% interval: Wilson score (n = labeled units) for unweighted rows; stratified bootstrap percentile for the weighted estimate (marked \"bootstrap\").\n\n| Set | Units | Precision (95%) | Recall (95%) | F1 | Coverage | Unresolved |\n|---|---|---|---|---|---|---|\n"
    )
    line("All rows (unweighted)", rr("unweighted"))
    line("Weighted estimate (bootstrap)", rr("weighted")("metrics"))
    rr("byMatchPath").obj.foreach { case (k, v) => line(s"path $k", v) }
    rr("byFamily").obj.foreach { case (k, v) => line(s"family $k", v) }
    line("evidence: dblp_numbered_name only", rr("evidence")("dblpNumberedNameOnly"))
    line("evidence: independent", rr("evidence")("independentEvidence"))
    sb.append("\nWeighted bootstrap 95%: ")
    sb.append(
      rr("weighted")("bootstrap95").obj.map { case (k, v) => s"$k ${ci(v)}" }.mkString(", ")
    ).append("\n\n")
    val design = rr("weighted")("design")
    sb.append(
      s"Weighted sample: ${design("labeledRows").num.toInt} labeled rows (the resampling unit) in ${design("labeledMemberships").num.toInt} stratum memberships (the weighted units). ${design("resamplingRule").str} ${design("collapseRule").str}\n\n"
    )
    design("bootstrapUnavailable") match {
      case ujson.Str(s) => sb.append(s"No weighted interval: $s.\n\n")
      case _            => ()
    }
    val singles = design("singleLabeledRowStrata").arr.map(_.str)
    if (singles.nonEmpty)
      sb.append(
        s"Strata with a single labeled row (flagged): ${singles.mkString(", ")}. Collapsed bootstrap groups: ${design("bootstrapGroups").arr.filter(_("collapsed").bool).map(_("name").str).mkString(", ")}.\n\n"
      )
    sb.append(
      "### Confusion matrix (unweighted rows)\n\n| Decision | MATCH | NON_MATCH | UNSURE |\n|---|---|---|---|\n"
    )
    Outcomes.foreach { o =>
      val c = rr("unweighted")("confusion")(o)
      sb.append(
        s"| $o | ${c("MATCH").num.toLong} | ${c("NON_MATCH").num.toLong} | ${c("UNSURE").num.toLong} |\n"
      )
    }
    sb.append(
      s"\n### Errors\n\nFalse positives: ${rr("errorAnalysis")("falsePositivesTotal").num.toInt}; false negatives: ${rr("errorAnalysis")("falseNegativesTotal").num.toInt} (details in metrics.json).\n\n"
    )
    sb.append(
      "### Threshold sensitivity (reporting only)\n\n| accept | reject | precision | recall | coverage |\n|---|---|---|---|---|\n"
    )
    rr("thresholdSensitivity")("grid").arr.foreach { g =>
      sb.append(
        s"| ${g("accept").num} | ${g("reject").num} | ${pct(g("precision"))} | ${pct(g("recall"))} | ${pct(g("coverage"))} |\n"
      )
    }
    val ca = m("researcherClusterAssignment")
    sb.append(
      s"\n## Researcher resolution: final cluster assignment (reported apart from pair decisions)\n\n${ca("description").str}\n\nLabeled rows with cluster information: ${ca("labeledRowsWithClusterInfo").num.toInt}; without: ${ca("labeledRowsWithoutClusterInfo").num.toInt}.\n\n| Set | Units | Precision (95%) | Recall (95%) | F1 |\n|---|---|---|---|---|\n"
    )
    def clusterLine(name: String, x: ujson.Value) =
      sb.append(
        s"| $name | ${x("units").num.toInt} | ${pct(x("precision")("value"))} ${ci(x("precision")("interval95"))} | ${pct(x("recall")("value"))} ${ci(x("recall")("interval95"))} | ${pct(x("f1"))} |\n"
      )
    clusterLine("Cluster level, all rows (unweighted)", ca("unweighted"))
    clusterLine("Cluster level, weighted estimate (bootstrap)", ca("weighted")("metrics"))
    sb.append(
      "\n### Cluster-level confusion matrix (unweighted rows)\n\n| Final clusters | MATCH | NON_MATCH | UNSURE |\n|---|---|---|---|\n"
    )
    List(SameCluster -> "same final cluster", DifferentClusters -> "different final clusters")
      .foreach { case (k, label) =>
        val c = ca("unweighted")("confusion")(k)
        sb.append(
          s"| $label | ${c("MATCH").num.toLong} | ${c("NON_MATCH").num.toLong} | ${c("UNSURE").num.toLong} |\n"
        )
      }
    sb.append(
      "\n### Pair decision by final cluster (unweighted rows)\n\n| Pair decision | same final cluster | different final clusters |\n|---|---|---|\n"
    )
    Outcomes.foreach { o =>
      val c = ca("pairOutcomeByFinalCluster")(o)
      sb.append(s"| $o | ${c(SameCluster).num.toLong} | ${c(DifferentClusters).num.toLong} |\n")
    }
    sb.append("\n## Work resolution\n\n").append(m("workResolution")("labels").str).append(".\n")
    sb.toString
  }
}
