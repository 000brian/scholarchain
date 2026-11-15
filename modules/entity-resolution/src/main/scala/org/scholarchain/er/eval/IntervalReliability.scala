package org.scholarchain.er.eval

import scala.annotation.tailrec

/** Reliability of the weighted bootstrap intervals (repair-20, reviewer R1; repair-21, R2).
  *
  * The stratified percentile bootstrap resamples labeled rows only within their stratum, so a
  * stratum whose labeled rows show a share of 0% or 100% (a zero-event stratum, for example no
  * NON_MATCH among its accepted rows) contributes no bootstrap variance however few rows it has.
  * When such strata carry most of a metric's weight the interval collapses (for example "100.0%
  * [100.0%, 100.0%]") although the labels support far less certainty. This object detects that for
  * every weighted metric, names the strata and their weight share, and gives a conservative
  * zero-event bound. It never changes a point estimate or a bootstrap interval.
  *
  * A weighted proportion is written per stratum h as `P_h * s1 * s2 * s3 * s4 / P_h * s1 * s2`
  * summed over strata, with `P_h` the stratum's population weight (the summed design weights of its
  * labeled memberships), `s1` the share of the stratum's units whose decision meets the
  * denominator's outcome condition, `s2` the share of those whose label meets the denominator's
  * label condition, `s3` the share of denominator units whose decision meets the numerator's
  * outcome condition and `s4` the share of those whose label meets the numerator's label condition.
  * A share is fixed by the design when it depends only on the decision outcome and the stratum is
  * defined by that outcome with every labeled membership agreeing (pair-level metrics only; a final
  * cluster outcome is never a stratum dimension). Every other share is estimated from the labeled
  * rows; it is a zero event when it is 0 or 1, or undefined (no row in its base).
  */
object IntervalReliability {

  /** A weighted proportion: denominator = `dOut(outcome) && dLab(label)`; numerator = denominator
    * `&& nOut(outcome) && nLab(label)`. `None` is a condition that always holds.
    */
  final case class Spec(
      name: String,
      dOut: Option[String => Boolean],
      dLab: Option[String => Boolean],
      nOut: Option[String => Boolean],
      nLab: Option[String => Boolean]
  )

  private val matchOrNonMatch: String => Boolean = l => l == "MATCH" || l == "NON_MATCH"

  /** The pair-level weighted metrics of [[Metrics]], in its terms. */
  val PairSpecs: List[Spec] = List(
    Spec("precision", Some(_ == "accepted"), Some(matchOrNonMatch), None, Some(_ == "MATCH")),
    Spec("recall", None, Some(_ == "MATCH"), Some(_ == "accepted"), None),
    Spec(
      "coverage",
      None,
      Some(matchOrNonMatch),
      Some(o => o == "accepted" || o == "rejected"),
      None
    ),
    Spec("unresolvedRate", None, None, Some(_ == "unresolved"), None)
  )

  /** The cluster-level weighted metrics (outcome `accepted` = same final cluster). */
  val ClusterSpecs: List[Spec] = PairSpecs.filter(s => s.name == "precision" || s.name == "recall")

  val Alpha = 0.05

  /** A weighted interval is flagged unreliable when zero-event strata hold more than this share. */
  val FlagShare = 0.5

  /** Exact one-sided 95% (Clopper-Pearson) limit for 0 events in m: `1 - 0.05^(1/m)`, about 3/m
    * (the rule of three). By symmetry `0.05^(1/m)` is the lower limit for m events in m.
    */
  def zeroEventLimit(m: Int): Double = 1 - math.pow(Alpha, 1.0 / m)

  val Rule: String =
    "A percentile bootstrap resamples labeled rows only within their stratum, so a stratum whose " +
      "labeled rows show a share of 0% or 100% in an estimated part of the metric (a zero-event " +
      "stratum: for example no NON_MATCH among its accepted rows, or no UNSURE or no MATCH row " +
      "among the rows that could enter the denominator) contributes no bootstrap variance there, " +
      "however few rows it has; the observed proportion shown per stratum is the metric's own. For " +
      "each weighted metric the zero-event strata are named with their share of the population " +
      "weight of the strata that can enter the metric's denominator and of the observed " +
      "denominator weight; the bootstrap interval is flagged unreliable when either share exceeds " +
      "50%. A share fixed by the design (a decision outcome in a stratum defined by that outcome, " +
      "every labeled membership agreeing) is not a zero event. The zero-event bound recomputes the " +
      "weighted estimate with each zero-event share moved to its exact one-sided 95% limit " +
      "(Clopper-Pearson, 1 - 0.05^(1/n) from the boundary for n rows, about 3/n: the rule of " +
      "three; a share with no row in its base spans 0 to 1), per stratum in the direction that " +
      "lowers (for the lower bound) or raises (for the upper bound) the estimate; every other " +
      "share stays at its observed value. It is a conservative sensitivity bound for the " +
      "uncertainty the bootstrap cannot see, not a joint 95% interval."

  val DesignDeterminedNote: String =
    "design-determined: the unresolved rate depends only on the decision, never on a label, and " +
      "every stratum is defined by decision outcome, so the weighted rate is fixed by the strata's " +
      "population decision counts rather than estimated from labels; its narrow bootstrap interval " +
      "reflects only the pooling of single-row strata and memberships whose decision differs from " +
      "their stratum (m3), not label uncertainty."

  final case class Box(lo: Double, hi: Double, widened: Boolean)

  private val one = Box(1.0, 1.0, widened = false)

  final case class StratumResult(
      stratum: String,
      population: Double,
      units: Int,
      denominatorUnits: Int,
      numeratorUnits: Int,
      denominatorWeight: Double,
      relevant: Boolean,
      zeroEvent: Boolean,
      weight: Box,
      rate: Box
  ) {
    def observed: Option[Double] =
      if (denominatorUnits > 0) Some(numeratorUnits.toDouble / denominatorUnits) else None
  }

  final case class Result(
      spec: Spec,
      strata: List[StratumResult],
      estimate: Option[Double],
      shareOfRelevantPopulation: Double,
      shareOfDenominatorWeight: Double,
      designDetermined: Boolean,
      bound: Option[(Double, Double)]
  ) {
    def unreliable: Boolean =
      !designDetermined &&
        (shareOfRelevantPopulation > FlagShare || shareOfDenominatorWeight > FlagShare)
    def zeroEventStrata: List[StratumResult] =
      strata
        .filter(s => s.relevant && s.zeroEvent)
        .sortBy(s => (-s.population, s.stratum))
  }

  private def stratumOutcome(s: String): String = Harness.fieldOf(s, "outcome")

  private def share(
      cond: Option[String => Boolean],
      base: List[EvalUnit],
      value: EvalUnit => String,
      fixed: Option[String]
  ): Box = cond match {
    case None    => one
    case Some(p) =>
      fixed match {
        case Some(o) => val x = if (p(o)) 1.0 else 0.0; Box(x, x, widened = false)
        case None    =>
          val m = base.size
          val x = base.count(u => p(value(u)))
          if (m == 0) Box(0.0, 1.0, widened = true)
          else if (x == 0) Box(0.0, zeroEventLimit(m), widened = true)
          else if (x == m) Box(1.0 - zeroEventLimit(m), 1.0, widened = true)
          else Box(x.toDouble / m, x.toDouble / m, widened = false)
      }
  }

  private def stratum(
      spec: Spec,
      name: String,
      us: List[EvalUnit],
      outcomeFixedByStratum: Boolean
  ): StratumResult = {
    val so = stratumOutcome(name)
    val fixed =
      if (outcomeFixedByStratum && so.nonEmpty && us.forall(_.outcome == so)) Some(so) else None
    val s1 = share(spec.dOut, us, _.outcome, fixed)
    val u1 = us.filter(u => spec.dOut.forall(_(u.outcome)))
    val s2 = share(spec.dLab, u1, _.label, None)
    val u2 = u1.filter(u => spec.dLab.forall(_(u.label)))
    val s3 = share(spec.nOut, u2, _.outcome, fixed)
    val u3 = u2.filter(u => spec.nOut.forall(_(u.outcome)))
    val s4 = share(spec.nLab, u3, _.label, None)
    val u4 = u3.filter(u => spec.nLab.forall(_(u.label)))
    val weight = Box(s1.lo * s2.lo, s1.hi * s2.hi, s1.widened || (s1.hi > 0 && s2.widened))
    val rate = Box(s3.lo * s4.lo, s3.hi * s4.hi, s3.widened || (s3.hi > 0 && s4.widened))
    val relevant = weight.hi > 0
    StratumResult(
      name,
      us.map(_.weight).sum,
      us.size,
      u2.size,
      u4.size,
      u2.map(_.weight).sum,
      relevant,
      relevant && (weight.widened || rate.widened),
      weight,
      rate
    )
  }

  /** The minimum (`lower`) or maximum of `sum P*b*t / sum P*b` over the boxes (Dinkelbach). */
  private def extreme(strata: List[StratumResult], lower: Boolean, start: Double): Double = {
    @tailrec def go(r: Double, left: Int): Double = {
      val picks = strata.map { s =>
        val t = if (lower) s.rate.lo else s.rate.hi
        val heavy = if (lower) t < r else t > r
        val b = s.population * (if (heavy) s.weight.hi else s.weight.lo)
        (b * t, b)
      }
      val den = picks.map(_._2).sum
      if (den <= 0) r
      else {
        val next = picks.map(_._1).sum / den
        if (left == 0 || math.abs(next - r) < 1e-13) next else go(next, left - 1)
      }
    }
    go(start, 1000)
  }

  def evaluate(units: List[EvalUnit], spec: Spec, outcomeFixedByStratum: Boolean): Result = {
    val strata = units
      .groupBy(_.stratum)
      .toList
      .sortBy(_._1)
      .map { case (s, us) => stratum(spec, s, us, outcomeFixedByStratum) }
    val den = strata.map(_.denominatorWeight).sum
    val estimate =
      if (den > 0)
        Some(
          units
            .filter(u =>
              spec.dOut.forall(_(u.outcome)) && spec.dLab.forall(_(u.label)) &&
                spec.nOut.forall(_(u.outcome)) && spec.nLab.forall(_(u.label))
            )
            .map(_.weight)
            .sum / den
        )
      else None
    val relevantPop = strata.filter(_.relevant).map(_.population).sum
    val zero = strata.filter(s => s.relevant && s.zeroEvent)
    val designDetermined = outcomeFixedByStratum && spec.dLab.isEmpty && spec.nLab.isEmpty &&
      units.nonEmpty && units.forall(u => stratumOutcome(u.stratum).nonEmpty)
    Result(
      spec,
      strata,
      estimate,
      if (relevantPop > 0) zero.map(_.population).sum / relevantPop else 0.0,
      if (den > 0) zero.map(_.denominatorWeight).sum / den else 0.0,
      designDetermined,
      estimate.map(e => (extreme(strata, lower = true, e), extreme(strata, lower = false, e)))
    )
  }

  private def f1(p: Double, r: Double): Double = if (p + r > 0) 2 * p * r / (p + r) else 0.0

  /** The reliability section of a weighted metrics object (pair or cluster level). */
  def json(units: List[EvalUnit], specs: List[Spec], outcomeFixedByStratum: Boolean): ujson.Obj = {
    val results = specs.map(s => evaluate(units, s, outcomeFixedByStratum))
    def iv(b: Option[(Double, Double)]): ujson.Value =
      b.fold[ujson.Value](ujson.Null) { case (l, h) => ujson.Arr(l, h) }
    val perMetric = results.map { r =>
      val relevantPop = r.strata.filter(_.relevant).map(_.population).sum
      r.spec.name -> ujson.Obj(
        "estimate" -> r.estimate.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
        "unreliable" -> r.unreliable,
        "designDetermined" -> r.designDetermined,
        "designDeterminedNote" -> (if (r.designDetermined) ujson.Str(DesignDeterminedNote)
                                   else ujson.Null),
        "zeroEventShareOfRelevantPopulation" -> r.shareOfRelevantPopulation,
        "zeroEventShareOfDenominatorWeight" -> r.shareOfDenominatorWeight,
        "zeroEventBound95" -> iv(r.bound),
        "zeroEventStrata" -> ujson.Arr.from(r.zeroEventStrata.map { s =>
          ujson.Obj(
            "stratum" -> s.stratum,
            "shareOfRelevantPopulation" -> (if (relevantPop > 0) s.population / relevantPop
                                            else 0.0),
            "labeledUnits" -> s.units,
            "denominatorUnits" -> s.denominatorUnits,
            "numeratorUnits" -> s.numeratorUnits,
            "observedProportion" -> s.observed.fold[ujson.Value](ujson.Null)(ujson.Num(_))
          )
        })
      )
    }
    val byName = results.map(r => r.spec.name -> r).toMap
    val f1Part = for { p <- byName.get("precision"); r <- byName.get("recall") } yield {
      val bound = for { (pl, ph) <- p.bound; (rl, rh) <- r.bound } yield (f1(pl, rl), f1(ph, rh))
      "f1" -> ujson.Obj(
        "unreliable" -> (p.unreliable || r.unreliable),
        "zeroEventBound95" -> iv(bound),
        "note" -> "F1 is not a proportion: it is flagged when precision or recall is, and its bound is F1 of the precision and recall bounds (F1 increases in both)"
      )
    }
    ujson.Obj(
      "rule" -> Rule,
      "flagThreshold" -> FlagShare,
      "outcomeSharesFixedByStratum" -> outcomeFixedByStratum,
      "metrics" -> ujson.Obj.from(perMetric ++ f1Part.toList)
    )
  }
}
