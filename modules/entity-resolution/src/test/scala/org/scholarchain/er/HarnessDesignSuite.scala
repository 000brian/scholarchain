package org.scholarchain.er

import org.scholarchain.er.eval.*

/** Test-engineer finding 3: the weighted bootstrap resamples labeled rows (the unit of labeling),
  * not stratum memberships; single-labeled-row strata are flagged and collapsed so they contribute
  * variance; distinct labeled rows are reported next to memberships. Synthetic labels only.
  */
class HarnessDesignSuite extends munit.FunSuite {

  private val S1 = "path=probabilistic;outcome=accepted;family=f1;band=far"
  private val S2 = "path=probabilistic;outcome=accepted;family=f2;band=far"
  private val S3 = "path=probabilistic;outcome=accepted;family=f3;band=far"

  private def m(i: Int) = f"mention:v1:$i%032x"
  private def c(i: Int) = f"match-candidate:v1:$i%032x"

  /** rows: (label, memberships), all accepted on the probabilistic path. */
  private def labeled(rows: List[(String, List[(String, Double)])]): List[Harness.Labeled] =
    rows.zipWithIndex.map { case ((label, ms), i) =>
      Harness.Labeled(
        GoldLabel(
          m(2 * i + 1),
          m(2 * i + 2),
          c(i + 1),
          label,
          List("other"),
          "SYNTHETIC",
          "SYNTHETIC-LABELER",
          "2026-11-14"
        ),
        KeyRow(
          m(2 * i + 1),
          m(2 * i + 2),
          c(i + 1),
          ms,
          "accepted",
          "probabilistic",
          "f",
          Some(0.95),
          0.95,
          Nil,
          Nil,
          Nil
        )
      )
    }

  private def evaluate(rows: List[(String, List[(String, Double)])], seed: Long = 5L) = {
    val ls = labeled(rows)
    Harness.evaluate(
      GoldSet(ls.map(_.gold), 0),
      ls.map(l => (l.key.left, l.key.right) -> l.key).toMap,
      None,
      seed,
      1000,
      Some("SYNTHETIC")
    )
  }

  private def weighted(r: ujson.Value) = r("researcherResolution")("weighted")
  private def interval(v: ujson.Value): (Double, Double) =
    (v("interval95").arr(0).num, v("interval95").arr(1).num)

  test("finding 3: distinct labeled rows are reported separately from stratum memberships") {
    val r = evaluate(
      List(
        "MATCH" -> List(S1 -> 40.0, S2 -> 20.0),
        "MATCH" -> List(S1 -> 40.0),
        "NON_MATCH" -> List(S2 -> 20.0)
      )
    )
    val w = weighted(r)("metrics")("precision")
    assertEquals(w("labeledUnits").num, 4.0) // memberships (meaning unchanged)
    assertEquals(w("labeledRows").num, 3.0)
    val u = r("researcherResolution")("unweighted")("precision")
    assertEquals(u("labeledUnits").num, 3.0)
    assertEquals(u("labeledRows").num, 3.0)
    val d = weighted(r)("design")
    assertEquals(d("labeledRows").num, 3.0)
    assertEquals(d("labeledMemberships").num, 4.0)
    assertEquals(d("resamplingUnit").str, "labeled row")
    assertEquals(d("strata")(S1)("labeledRows").num, 2.0)
    assertEquals(d("strata")(S1)("labeledMemberships").num, 2.0)
    assertEqualsDouble(d("strata")(S1)("weight").num, 20.0, 1e-12)
    assert(Harness.markdown(r).contains("3 labeled rows (the resampling unit) in 4 stratum"))
  }

  test("finding 3: a row sampled in two strata is resampled once, not as two independent units") {
    val labels = List("MATCH", "NON_MATCH", "MATCH", "MATCH", "NON_MATCH", "MATCH")
    // every row in S1 (population 600) and S2 (population 300): weight 100 + 50 per row
    val twoStrata = evaluate(labels.map(_ -> List(S1 -> 600.0, S2 -> 300.0)))
    // the same rows in one stratum of population 900: weight 150 per row
    val oneStratum = evaluate(labels.map(_ -> List(S1 -> 900.0)))
    val p2 = weighted(twoStrata)("metrics")("precision")
    val p1 = weighted(oneStratum)("metrics")("precision")
    assertEqualsDouble(p2("value").num, p1("value").num, 1e-12)
    // memberships resampled independently per stratum would average two draws and narrow the
    // interval; resampling rows makes the two designs identical
    assertEquals(interval(p2), interval(p1))
    assertEquals(
      weighted(twoStrata)("bootstrap95").render(),
      weighted(oneStratum)("bootstrap95").render()
    )
    val (lo, hi) = interval(p2)
    assert(hi - lo > 0.1, s"[$lo, $hi]")
    assertEquals(p2("labeledUnits").num, 12.0)
    assertEquals(p2("labeledRows").num, 6.0)
  }

  test("finding 3: a stratum with one labeled row is flagged and collapsed, so it adds variance") {
    // S1: three MATCH rows; S2: one NON_MATCH row. Without collapsing, every replicate redraws
    // the same S1 labels and the one S2 row: precision is 0.75 in every replicate (zero width).
    val r = evaluate(List.fill(3)("MATCH" -> List(S1 -> 30.0)) :+ ("NON_MATCH" -> List(S2 -> 10.0)))
    val p = weighted(r)("metrics")("precision")
    assertEqualsDouble(p("value").num, 0.75, 1e-12)
    val (lo, hi) = interval(p)
    assert(hi - lo > 0.1, s"[$lo, $hi]")
    assert(lo <= 0.75 && 0.75 <= hi, s"[$lo, $hi]")
    val d = weighted(r)("design")
    assertEquals(d("singleLabeledRowStrata").arr.map(_.str).toList, List(S2))
    assert(d("strata")(S2)("singleLabeledRow").bool)
    assert(!d("strata")(S1)("singleLabeledRow").bool)
    val groups = d("bootstrapGroups").arr.toList
    assertEquals(groups.size, 1)
    assertEquals(groups.head("strata").arr.map(_.str).toList, List(S1, S2))
    assertEquals(groups.head("rows").num, 4.0)
    assert(groups.head("collapsed").bool)
    assertEquals(d("bootstrapUnavailable"), ujson.Null)
    assert(Harness.markdown(r).contains(s"Strata with a single labeled row (flagged): $S2."))
  }

  test("finding 3: several single-row strata are pooled into one collapsed group") {
    val ls = labeled(
      List.fill(3)("MATCH" -> List(S1 -> 30.0)) ++ List(
        "NON_MATCH" -> List(S3 -> 10.0),
        "MATCH" -> List(S2 -> 10.0)
      )
    )
    val design = Harness.bootstrapDesign(ls)
    assertEquals(design.unavailable, None)
    assertEquals(
      design.groups.map(g => (g.name, g.strata, g.rows.size, g.collapsed)),
      List(
        (s"${Harness.CollapsedPrefix}$S2 + $S3", List(S2, S3), 2, true),
        (S1, List(S1), 3, false)
      )
    )
  }

  test("finding 3: with one labeled row no weighted interval is given, and the report says why") {
    val r = evaluate(List("MATCH" -> List(S1 -> 30.0)))
    val w = weighted(r)
    assertEquals(w("metrics")("precision")("interval95"), ujson.Null)
    assertEquals(w("metrics")("f1Interval95"), ujson.Null)
    assertEquals(w("bootstrap95")("precision"), ujson.Null)
    assert(w("design")("bootstrapUnavailable").str.contains("at least two"))
    assert(Harness.markdown(r).contains("No weighted interval:"))
  }
}
