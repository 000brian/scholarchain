package org.scholarchain.contracts.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import org.scholarchain.contracts.er.ErSynth.*
import org.scholarchain.core.*
import org.scholarchain.er.eval.{GoldLabel, GoldLabels, GoldSet, Harness, KeyRow}
import org.scholarchain.er.io.ErError
import org.scholarchain.er.llm.{AdjudicationRequest, AlwaysAbstainAdjudicator}
import org.scholarchain.er.policy.{DecisionEngine, Policy}
import org.scholarchain.er.review.{QueueSpec, ReviewQueue, StratumSampler}

/** Regression attacks on the Phase 5 repair round (code-reviewer REQUEST_CHANGES):
  *
  *   - M2: weighted metrics never use the summed weights as a sample size; the interval comes from
  *     a stratified bootstrap and is not narrower than the unweighted Wilson interval on a design
  *     where weights are unequal; `labeledUnits` and `kishEffectiveN` have their stated meaning.
  *   - m1: dblp-prior rows carry an opaque `match-candidate:v1:<32 hex>` reference, so the label
  *     file cannot tell them apart by the candidateId column; the strict gold reader and the
  *     harness join them.
  *   - m2: spreadsheet formula triggers are neutralised in the CSV and survive the round trip
  *     through the strict gold reader, with or without a spreadsheet removing the apostrophe.
  *   - m4: adjudicator exceptions are recorded by class and message hash only, counted, never
  *     cached, and leave the pair UNRESOLVED.
  *   - m5: reuse of a completed run re-verifies input file contents (same size, different bytes).
  */
class ErRepairRegressionSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private def sha256Hex(b: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 0xff}%02x").mkString

  private def readString(p: Path) = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** Independent RFC 4180 splitter: raw cell text after unquoting, no other transformation. */
  private def rawCsv(text: String): Vector[Vector[String]] = {
    val rows = Vector.newBuilder[Vector[String]]
    var row = Vector.newBuilder[String]
    val cell = new StringBuilder
    var i = 0
    var q = false
    var any = false
    while (i < text.length) {
      val c = text.charAt(i)
      if (q) {
        if (c == '"' && i + 1 < text.length && text.charAt(i + 1) == '"') {
          cell.append('"'); i += 1
        } else if (c == '"') q = false
        else cell.append(c)
      } else if (c == '"') { q = true; any = true }
      else if (c == ',') { row += cell.toString; cell.clear(); any = true }
      else if (c == '\r' && i + 1 < text.length && text.charAt(i + 1) == '\n') {
        row += cell.toString; cell.clear(); rows += row.result(); row = Vector.newBuilder;
        any = false
        i += 1
      } else { cell.append(c); any = true }
      i += 1
    }
    if (any || cell.nonEmpty) { row += cell.toString; rows += row.result() }
    rows.result()
  }

  /** Independent CSV writer: quotes when needed, never adds a formula prefix. */
  private def writeCsv(rows: Seq[Seq[String]]): String =
    rows
      .map(_.map { v =>
        if (v.exists(c => c == ',' || c == '"' || c == '\r' || c == '\n'))
          "\"" + v.replace("\"", "\"\"") + "\""
        else v
      }.mkString(","))
      .mkString("", "\r\n", "\r\n")

  private val Triggers = Set('=', '+', '-', '@', '\t', '\r', '\'')

  /** Fills the label columns of raw queue CSV rows. */
  private def labeled(
      raw: Vector[Vector[String]],
      note: Int => String = _ => "SYNTHETIC"
  ): Vector[Vector[String]] = {
    val h = raw.head
    val li = h.indexOf("label")
    raw.head +: raw.tail.zipWithIndex.map { case (r, i) =>
      r.take(li) ++ Vector("NON_MATCH", "other", note(i), "SYNTHETIC-LABELER", "2026-11-14")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // M2: weighted intervals and sample-size semantics
  // ---------------------------------------------------------------------------------------------

  private def m(i: Int) = f"mention:v1:$i%032x"
  private def c(i: Int) = f"match-candidate:v1:$i%032x"
  private val S1 = "path=probabilistic;outcome=accepted;family=f1;band=far"
  private val S2 = "path=probabilistic;outcome=accepted;family=f2;band=far"

  /** rows: (label, memberships) all accepted on the probabilistic path. */
  private def harness(rows: List[(String, List[(String, Double)])], seed: Long = 11L): ujson.Obj = {
    val gold = GoldSet(
      rows.zipWithIndex.map { case ((label, _), i) =>
        GoldLabel(
          m(2 * i + 1),
          m(2 * i + 2),
          c(i + 1),
          label,
          List("other"),
          "SYNTHETIC",
          "SYNTHETIC-LABELER",
          "2026-11-14"
        )
      },
      0
    )
    val key = rows.zipWithIndex.map { case ((_, ms), i) =>
      (m(2 * i + 1), m(2 * i + 2)) -> KeyRow(
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
    }.toMap
    Harness.evaluate(gold, key, None, seed, 1000, Some("SYNTHETIC"))
  }

  /** S1: population 1000, 10 labeled (7 MATCH, 3 NON_MATCH); S2: population 10, 10 labeled MATCH.
    */
  private lazy val unequal = harness(
    List.fill(7)("MATCH" -> List(S1 -> 1000.0)) ++ List.fill(3)(
      "NON_MATCH" -> List(S1 -> 1000.0)
    ) ++
      List.fill(10)("MATCH" -> List(S2 -> 10.0))
  )

  private def wilson(p: Double, n: Double): (Double, Double) = {
    val z = 1.959963984540054
    val centre = (p + z * z / (2 * n)) / (1 + z * z / n)
    val half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    (centre - half, centre + half)
  }

  test(
    "M2: weighted precision has no Wilson interval and a bootstrap interval with n = labeled units"
  ) {
    val w = unequal("researcherResolution")("weighted")("metrics")("precision")
    assertEqualsDouble(w("value").num, 710.0 / 1010.0, 1e-12)
    assertEqualsDouble(w("denominator").num, 1010.0, 1e-9) // estimated population, not n
    assertEquals(w("labeledUnits").num, 20.0)
    assertEqualsDouble(w("kishEffectiveN").num, 1010.0 * 1010.0 / 100010.0, 1e-9)
    assertEquals(w("wilson95"), ujson.Null)
    assert(w("intervalMethod").str.contains("bootstrap"), w("intervalMethod"))
    val iv = w("interval95").arr.map(_.num)
    assert(iv(0) <= w("value").num && w("value").num <= iv(1), s"$iv vs ${w("value")}")
    // consistent with the bootstrap table
    assertEquals(
      unequal("researcherResolution")("weighted")("bootstrap95")("precision"),
      w("interval95")
    )
    val u = unequal("researcherResolution")("unweighted")("precision")
    assertEquals(u("labeledUnits").num, 20.0)
    assertEquals(u("interval95"), u("wilson95"))
    assert(u("intervalMethod").str.toLowerCase.contains("wilson"), u("intervalMethod"))
  }

  test(
    "M2: the weighted interval is not narrower than the unweighted one, nor a Wilson on summed weights"
  ) {
    val w = unequal("researcherResolution")("weighted")("metrics")
    val u = unequal("researcherResolution")("unweighted")
    def width(v: ujson.Value) = v("interval95").arr(1).num - v("interval95").arr(0).num
    val wWidth = width(w("precision"))
    val uWidth = width(u("precision"))
    assert(wWidth >= uWidth, s"weighted width $wWidth < unweighted width $uWidth")
    // the pre-repair interval: Wilson with n = the summed weights (1010) -- far too narrow
    val (lo, hi) = wilson(710.0 / 1010.0, 1010.0)
    assert(
      wWidth >= 3 * (hi - lo),
      s"weighted width $wWidth looks like a Wilson on n = 1010 (${hi - lo})"
    )
    // every weighted proportion follows the same rule
    List("precision", "recall", "coverage", "unresolvedRate").foreach { k =>
      assertEquals(w(k)("wilson95"), ujson.Null, k)
      assert(w(k)("labeledUnits").num <= 20.0, k)
      assert(w(k)("kishEffectiveN").num <= w(k)("labeledUnits").num + 1e-9, k)
    }
    val f1 = w("f1Interval95").arr.map(_.num)
    assert(0.0 <= f1(0) && f1(0) <= f1(1) && f1(1) <= 1.0, f1)
    assertEquals(u("f1Interval95"), ujson.Null)
  }

  test("M2: weighted intervals are deterministic for one seed") {
    val again = harness(
      List.fill(7)("MATCH" -> List(S1 -> 1000.0)) ++ List.fill(3)(
        "NON_MATCH" -> List(S1 -> 1000.0)
      ) ++
        List.fill(10)("MATCH" -> List(S2 -> 10.0))
    )
    assertEquals(again.render(), unequal.render())
  }

  test("M2: n semantics: weighted units are stratum memberships, unweighted units are rows") {
    // three labeled rows; the first was sampled in two strata (one candidate per block family)
    val r = harness(
      List(
        "MATCH" -> List(S1 -> 40.0, S2 -> 20.0),
        "MATCH" -> List(S1 -> 40.0),
        "NON_MATCH" -> List(S2 -> 20.0)
      )
    )
    val u = r("researcherResolution")("unweighted")("precision")
    val w = r("researcherResolution")("weighted")("metrics")("precision")
    assertEquals(u("labeledUnits").num, 3.0)
    assertEquals(u("denominator").num, 3.0)
    // memberships: S1 has 2 labeled (weight 20 each), S2 has 2 labeled (weight 10 each)
    assertEquals(w("labeledUnits").num, 4.0)
    assertEqualsDouble(w("denominator").num, 60.0, 1e-9)
    assertEqualsDouble(w("value").num, 50.0 / 60.0, 1e-12) // MATCH: 20 + 20 + 10
    assertEqualsDouble(w("kishEffectiveN").num, 3600.0 / 1000.0, 1e-9)
  }

  // ---------------------------------------------------------------------------------------------
  // m1 and m2 through a real run
  // ---------------------------------------------------------------------------------------------

  /** Four mentions of one numbered dblp name with no candidate (six dblp-prior pairs), plus a chain
    * of candidates whose titles start with every spreadsheet formula trigger.
    */
  private val numbered =
    (0 until 4).toList.map(i =>
      SW(s"conf/sigir/SynthRefNum$i", List("Nia Synthref 0001", s"Coa Ref$i"), year = 2010 + i)
    )
  private val triggerTitles = List(
    "=HYPERLINK(\"https://example.invalid/x\",\"Synthetic\")",
    "+Plus Synthetic Title",
    "-Minus Synthetic Title",
    "@At Synthetic Title",
    "\tTab Synthetic Title",
    "\rCarriage Synthetic Title",
    "'Tis Synthetic Title",
    "''Twice Synthetic Title"
  )
  private val trig = triggerTitles.zipWithIndex.map { case (t, i) =>
    SW(
      s"conf/sigir/SynthTrig$i",
      List("Mo Injectsynth", s"Coa Trig$i"),
      year = 2015 + (i % 3),
      title = t
    )
  }
  private val trigCands =
    trig.indices.toList
      .sliding(2)
      .map(p => cand(trig(p(0)), 0, trig(p(1)), 0, "dblp-base-name", "Mo Injectsynth"))
      .toList

  private lazy val qInput = writeExport(numbered ++ trig, trigCands)
  private def qRun(tag: String) =
    run(
      erConfig(qInput, workRoot(s"repair-q-$tag-"), queue = QueueSpec(40, 3, 7L)),
      new AlwaysAbstainAdjudicator(Model)
    )
  private lazy val q1 = qRun("a")
  private lazy val q2 = qRun("b")
  private def queueCsv(r: org.scholarchain.er.run.RunOutcome) =
    r.runDir.resolve("review-queue/review-queue.csv")
  private def queueJsonl(r: org.scholarchain.er.run.RunOutcome) =
    r.runDir.resolve("review-queue/review-queue.jsonl")
  private def keyRows(r: org.scholarchain.er.run.RunOutcome) =
    lines(r.runDir.resolve("review-queue/review-key.jsonl")).map(ujson.read(_))

  private val CandidateIdForm = "^match-candidate:v1:[0-9a-f]{32}$".r

  test(
    "m1: every candidateId cell has the match-candidate form; prior rows are told apart only in the key"
  ) {
    val raw = rawCsv(readString(queueCsv(q1)))
    assertEquals(raw.head.toList, ReviewQueue.Columns)
    val ci = raw.head.indexOf("candidateId")
    val ids = raw.tail.map(_(ci))
    assertEquals(raw.tail.size, trigCands.size + 3)
    ids.foreach(id => assert(CandidateIdForm.matches(id), s"candidateId '$id'"))
    assertEquals(ids.distinct.size, ids.size)
    val keys = keyRows(q1)
    val prior = keys.filter(_("candidateIdKind").str == "dblp-prior-reference")
    val real = keys.filter(_("candidateIdKind").str == "match-candidate")
    assertEquals(prior.size, 3)
    assertEquals(real.size, trigCands.size)
    // a prior reference is no real candidate ID, and the key's prior membership names it
    val candidateIds = trigCands.map(_.candidateId.value).toSet
    prior.foreach { k =>
      assert(!candidateIds(k("candidateId").str), k)
      val ms = k("memberships").arr
      assertEquals(ms.map(_("stratum").str).toList, List(StratumSampler.PriorStratum))
      assertEquals(ms.head("candidateId").str, k("candidateId").str)
      val pairMentions = Set(k("leftMentionId").str, k("rightMentionId").str)
      assert(pairMentions.subsetOf(numbered.map(w => mid(w.key, 0)).toSet), k)
    }
    real.foreach(k => assert(candidateIds(k("candidateId").str), k))
    // deterministic: another output root gives the same references for the same pairs
    def refs(r: org.scholarchain.er.run.RunOutcome) = keyRows(r)
      .filter(_("candidateIdKind").str == "dblp-prior-reference")
      .map(k => (k("leftMentionId").str, k("rightMentionId").str) -> k("candidateId").str)
      .toMap
    assertEquals(refs(q2), refs(q1))
    // no MatchDecision carries a prior reference
    val decided = pairDecisions(q1.runDir).flatMap(_.candidateId).map(_.value).toSet
    prior.foreach(k => assert(!decided(k("candidateId").str)))
  }

  test("m1: the strict gold reader and the harness join dblp-prior rows by their reference") {
    val raw = rawCsv(readString(queueCsv(q1)))
    val gold = workRoot("repair-gold-").resolve("gold.csv")
    Files.write(gold, writeCsv(labeled(raw)).getBytes(StandardCharsets.UTF_8))
    val g = GoldLabels.read(gold, queueJsonl(q1)).fold(e => fail(e.mkString("\n")), identity)
    assertEquals(g.labels.size, raw.size - 1)
    val key = KeyRow.readAll(q1.runDir.resolve("review-queue/review-key.jsonl"))
    val report = Harness.evaluate(g, key, None, 3L, 50, Some("SYNTHETIC"))
    assertEquals(report("labeledRows").num, (raw.size - 1).toDouble)
    val byStratum = report("researcherResolution")("byStratum")
    assertEquals(byStratum(StratumSampler.PriorStratum)("units").num, 3.0)
    // a prior row whose candidateId was blanked is an error, exactly as for real candidates
    val ci = raw.head.indexOf("candidateId")
    val keysByPair =
      keyRows(q1).map(k => (k("leftMentionId").str, k("rightMentionId").str) -> k).toMap
    val li = raw.head.indexOf("leftMentionId")
    val ri = raw.head.indexOf("rightMentionId")
    val blanked = labeled(raw).zipWithIndex.map { case (r, i) =>
      if (i > 0 && keysByPair((r(li), r(ri)))("candidateIdKind").str == "dblp-prior-reference")
        r.updated(ci, "")
      else r
    }
    Files.write(gold, writeCsv(blanked).getBytes(StandardCharsets.UTF_8))
    GoldLabels.read(gold, queueJsonl(q1)) match {
      case Right(_)   => fail("a blanked dblp-prior candidateId was accepted")
      case Left(errs) =>
        assertEquals(errs.count(_.contains("differs from the queue")), 3, errs.mkString("\n"))
    }
  }

  test(
    "m2: no CSV cell starts with a formula trigger; each trigger cell is the raw value plus one quote"
  ) {
    val raw = rawCsv(readString(queueCsv(q1)))
    val json = lines(queueJsonl(q1)).map(ujson.read(_).obj)
    assertEquals(json.size, raw.size - 1)
    val header = raw.head
    raw.tail.zip(json).foreach { case (r, j) =>
      header.zip(r).foreach { case (col, cell) =>
        val value = j(col).str
        if (cell.nonEmpty)
          assert(!Set('=', '+', '-', '@', '\t', '\r')(cell.head), s"$col cell '$cell'")
        if (value.nonEmpty && Triggers(value.head)) assertEquals(cell, "'" + value, col)
        else assertEquals(cell, value, col)
      }
    }
    // every trigger title made it into the queue unchanged in the JSONL
    val titles = json.flatMap(j => List(j("left_title").str, j("right_title").str)).toSet
    triggerTitles.foreach(t => assert(titles(t), s"title ${t.take(12)} missing from the queue"))
  }

  test("m2: the label file round-trips through the strict reader, with or without a spreadsheet") {
    val raw = rawCsv(readString(queueCsv(q1)))
    val dir = workRoot("repair-m2-")
    // hand-typed notes: one starts with a quote and a letter, one is a formula a spreadsheet kept
    val notes: Int => String =
      i => if (i == 0) "'quoted note" else if (i == 1) "=1+1" else "SYNTHETIC"
    // (a) saved without touching the cells
    val asIs = dir.resolve("as-is.csv")
    Files.write(asIs, writeCsv(labeled(raw, notes)).getBytes(StandardCharsets.UTF_8))
    val g = GoldLabels.read(asIs, queueJsonl(q1)).fold(e => fail(e.mkString("\n")), identity)
    assertEquals(g.labels.size, raw.size - 1)
    // (b) a spreadsheet that drops the leading text apostrophe from every cell on save
    val stripped = labeled(raw, notes).map(_.map(v => if (v.startsWith("'")) v.substring(1) else v))
    val sheet = dir.resolve("sheet.csv")
    Files.write(sheet, writeCsv(stripped).getBytes(StandardCharsets.UTF_8))
    // Every value comes back equal except the one raw title that itself starts with a quote and a
    // trigger ("''Twice ..."): after the spreadsheet drops one quote, the reader cannot know, and
    // the strict reader refuses that row loudly (display column differs) instead of accepting a
    // changed value. Nothing else is reported.
    GoldLabels.read(sheet, queueJsonl(q1)) match {
      case Right(g2)  => fail(s"accepted ${g2.labels.size} rows; the ''Twice title must be refused")
      case Left(errs) =>
        assertEquals(errs.size, 1, errs.mkString("\n"))
        assert(errs.head.contains("display column") && errs.head.contains("_title"), errs.head)
    }
    // without that one row, the spreadsheet-saved file reads completely
    val ti0 = raw.head.indexOf("left_title")
    val ti1 = raw.head.indexOf("right_title")
    val twice = triggerTitles.last
    // the CSV held "'" + twice; the spreadsheet dropped that quote, so the cell equals twice again
    val noTwice = stripped.filterNot(r => r(ti0) == twice || r(ti1) == twice)
    assertEquals(noTwice.size, stripped.size - 1)
    Files.write(sheet, writeCsv(noTwice).getBytes(StandardCharsets.UTF_8))
    val g2 = GoldLabels.read(sheet, queueJsonl(q1)).fold(e => fail(e.mkString("\n")), identity)
    assertEquals(g2.labels.size, raw.size - 2)
    // the hand-typed note that starts with a quote and a letter is read unchanged in (a)
    val li = raw.head.indexOf("leftMentionId")
    val ri = raw.head.indexOf("rightMentionId")
    val first = raw(1)
    val firstLabel =
      g.labels.find(l => Set(l.leftMentionId, l.rightMentionId) == Set(first(li), first(ri))).get
    assertEquals(firstLabel.note, "'quoted note")
    // still strict: a changed display value is an error after the round trip
    val ti = raw.head.indexOf("left_title")
    val edited = labeled(raw).zipWithIndex.map { case (r, i) =>
      if (i == 1) r.updated(ti, r(ti) + " edited") else r
    }
    val bad = dir.resolve("edited.csv")
    Files.write(bad, writeCsv(edited).getBytes(StandardCharsets.UTF_8))
    assert(GoldLabels.read(bad, queueJsonl(q1)).isLeft)
  }

  // ---------------------------------------------------------------------------------------------
  // m4: adjudicator exceptions
  // ---------------------------------------------------------------------------------------------

  /** LLM-band pair i: equal folded names spelled differently, no shared coauthor, different
    * research areas, ten years apart.
    */
  private def bandPair(i: Int): (SW, SW) = {
    val s = s"Errband${('a' + i).toChar}synth"
    (
      SW(
        s"conf/sigir/SynthErrL$i",
        List(s"Cea$i One", s"Jöhn $s", s"Cea$i Two"),
        year = 2010,
        title = "Alpha Retrieval Studies"
      ),
      SW(
        s"conf/cvpr/SynthErrR$i",
        List(s"Ceb$i One", s"John $s", s"Ceb$i Two"),
        year = 2020,
        venue = Some("conf/cvpr"),
        title = "Gamma Vision Pipelines"
      )
    )
  }
  private val bands = (0 until 3).toList.map(bandPair)
  private val bandCands = bands.zipWithIndex.map { case ((l, r), i) =>
    cand(l, 1, r, 1, "initials-surname", s"j errband${('a' + i).toChar}synth")
  }
  private lazy val bandInput = writeExport(bands.flatMap { case (l, r) => List(l, r) }, bandCands)
  private def bandMentions(i: Int) = Set(mid(bands(i)._1.key, 1), mid(bands(i)._2.key, 1))

  private val Secret = "SYNTHETIC-SENSITIVE-DETAIL-7f3a"
  private val abstain =
    """{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":["synthetic"]}"""
  private def failing(req: AdjudicationRequest): String = {
    // pairs are told apart by their synthetic surnames in the adjudication input
    if (req.inputJson.contains("Errbandasynth")) throw new IllegalStateException(Secret)
    else if (req.inputJson.contains("Errbandbsynth")) throw new RuntimeException() // no message
    else abstain
  }

  test("precondition: the m4 pairs are in the LLM band") {
    val corpus = ErSynth.corpus(bandInput)
    bandCands.foreach { cd =>
      val as = DecisionEngine.assess(
        corpus,
        Policy.erV1,
        cd.features,
        corpus.mentions.indexOf(cd.left),
        corpus.mentions.indexOf(cd.right)
      )
      assert(as.inLlmBand, s"not in band: ${as.matchProbability}")
    }
  }

  test("m4: a throwing adjudicator is recorded by class and message hash, counted, never cached") {
    val adj = new Scripted(Model, failing)
    val out = run(erConfig(bandInput, workRoot("repair-m4-")), adj)
    assertEquals(adj.calls, 3)
    val errs = lines(out.runDir.resolve("llm/adjudicator-errors.jsonl")).map(ujson.read(_))
    assertEquals(errs.size, 2)
    val byPair = errs.map(e => Set(e("leftMentionId").str, e("rightMentionId").str) -> e).toMap
    val e0 = byPair(bandMentions(0))
    assertEquals(e0("reason").str, "adjudicator-error")
    assertEquals(e0("errorClass").str, "java.lang.IllegalStateException")
    assertEquals(e0("messageSha256").str, sha256Hex(Secret.getBytes(StandardCharsets.UTF_8)))
    assert(e0("inputHash").str.nonEmpty)
    val e1 = byPair(bandMentions(1))
    assertEquals(e1("errorClass").str, "java.lang.RuntimeException")
    assertEquals(e1("messageSha256"), ujson.Null)
    // the message text appears in no output file
    tree(out.runDir).foreach { case (rel, bytes) =>
      assert(
        !new String(bytes, StandardCharsets.UTF_8).contains(Secret),
        s"$rel leaks the exception message"
      )
    }
    // counted; the errored pairs are UNRESOLVED on the llm path with the error reason
    assertEquals(out.report("llm")("adjudicatorErrors").num, 2.0)
    assertEquals(out.report("llm")("pairsSentToAdjudicator").num, 3.0)
    assertEquals(out.report("llm")("rejectedReplies").num, 0.0)
    val ds = pairDecisions(out.runDir)
    List(0, 1).foreach { i =>
      val d = ds.find(x => Set(x.left, x.right) == bandMentions(i)).get
      assertEquals(d.outcome, MatchOutcome.Unresolved)
      assertEquals(d.matchPath, MatchPath.Llm)
      assert(d.reasonCodes.contains("llm-output-rejected:adjudicator-error"), d.reasonCodes)
      assertEquals(d.modelVersion, Some(Model))
    }
    assertEquals(lines(out.runDir.resolve("llm/rejected-replies.jsonl")), Nil)
    // only the abstention was cached; a run with that cache asks again for the two errored pairs
    val cacheFile = out.runDir.resolve("llm/adjudications.jsonl")
    assertEquals(lines(cacheFile).size, 1)
    val adj2 = new Scripted(Model, _ => abstain)
    val again = run(erConfig(bandInput, workRoot("repair-m4b-"), cache = Some(cacheFile)), adj2)
    assertEquals(adj2.calls, 2)
    assertEquals(again.report("llm")("adjudicatorErrors").num, 0.0)
    assertEquals(lines(again.runDir.resolve("llm/adjudicator-errors.jsonl")), Nil)
  }

  // ---------------------------------------------------------------------------------------------
  // m5: reuse re-verifies input contents
  // ---------------------------------------------------------------------------------------------

  /** Replaces the first occurrence of `from` by `to` (same byte length) in `file`. */
  private def sameSizeEdit(file: Path, from: String, to: String): Unit = {
    assertEquals(
      from.getBytes(StandardCharsets.UTF_8).length,
      to.getBytes(StandardCharsets.UTF_8).length
    )
    val t = readString(file)
    assert(t.contains(from), s"$from not in $file")
    val before = Files.size(file)
    Files.write(
      file,
      t.replaceFirst(java.util.regex.Pattern.quote(from), to).getBytes(StandardCharsets.UTF_8)
    )
    assertEquals(Files.size(file), before)
  }

  test("m5: same-size content changes in an input file make reuse refuse, for each input kind") {

    /** Reverses the line order of a file: same size, different bytes, still a valid input. */
    def reverseLines(file: Path): Unit = {
      val ls = lines(file)
      assert(ls.distinct.size >= 2, s"$file needs two different lines")
      val before = Files.size(file)
      writeLines(file, ls.reverse)
      assertEquals(Files.size(file), before)
    }
    List[(String, Path => Unit)](
      "work/year=2020/part-r-00000" ->
        (f => sameSizeEdit(f, "Synthetic Retrieval Models", "Synthetic Retrieval Modals")),
      "author_mention/part-r-00000" -> reverseLines,
      "match_candidate/part-r-00000" -> reverseLines
    ).foreach { case (rel, change) =>
      val ws = List(
        SW("conf/sigir/SynthReuseA", List("Ana Detreuse", "Coa Reuse1")),
        SW("conf/sigir/SynthReuseB", List("A. Detreuse", "Coa Reuse1")),
        SW("conf/sigir/SynthReuseC", List("Ana Detreuse", "Coa Reuse2"))
      )
      val in = writeExport(
        ws,
        List(
          cand(ws(0), 0, ws(1), 0, "initials-surname", "a detreuse"),
          cand(ws(1), 0, ws(2), 0, "initials-surname", "a detreuse")
        )
      )
      val outRoot = workRoot("repair-m5-")
      val first = run(erConfig(in, outRoot), new AlwaysAbstainAdjudicator(Model))
      assert(!first.reused)
      // positive control: unchanged input is reused
      assert(run(erConfig(in, outRoot), new AlwaysAbstainAdjudicator(Model)).reused)
      change(in.resolve(rel))
      val err = intercept[ErError](run(erConfig(in, outRoot), new AlwaysAbstainAdjudicator(Model)))
      assert(err.getMessage.contains(rel) && err.getMessage.contains("content"), err.getMessage)
      // a new, empty output root still runs on the changed input
      assert(
        !run(erConfig(in, workRoot("repair-m5-new-")), new AlwaysAbstainAdjudicator(Model)).reused
      )
    }
  }
}
