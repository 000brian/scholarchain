package org.scholarchain.contracts.er

import java.nio.file.Files

import org.scholarchain.contracts.er.ErSynth.*
import org.scholarchain.er.io.ErError
import org.scholarchain.er.llm.AlwaysAbstainAdjudicator
import org.scholarchain.er.review.ReviewQueue

/** Determinism across output roots and input locations, refusal of tampered reuse, and the
  * label-blind review queue (decisions 6, 13, 17, 18).
  */
class ErDeterminismAndQueueSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  /** Thirty mentions in five surname groups with a mix of rule, scored and band decisions. */
  private val works: List[SW] = (0 until 5).toList.flatMap { g =>
    val s = s"Detname${('a' + g).toChar}"
    List(
      SW(s"conf/sigir/SynthDet${g}a", List(s"Ana $s", s"Coa Det$g", "Coa Detshared"), year = 2015),
      SW(s"conf/sigir/SynthDet${g}b", List(s"A. $s", s"Coa Det$g", "Coa Detx$g"), year = 2017),
      SW(
        s"conf/kdd/SynthDet${g}c",
        List(s"Ana $s", s"Coa Dety$g"),
        year = 2019,
        venue = Some("conf/kdd")
      ),
      SW(
        s"conf/cvpr/SynthDet${g}d",
        List(s"Anna $s", s"Coa Detz$g"),
        year = 2009,
        venue = Some("conf/cvpr")
      ),
      SW(s"conf/sigir/SynthDet${g}e", List(s"Ana $s 0001", s"Coa Detw$g"), year = 2021),
      SW(s"conf/sigir/SynthDet${g}f", List(s"Ana $s 0001", s"Coa Detv$g"), year = 2022)
    )
  }

  private val candidates = (0 until 5).toList.flatMap { g =>
    val ws = works.slice(g * 6, g * 6 + 6)
    val key = s"a detname${('a' + g).toChar}"
    for { i <- ws.indices; j <- ws.indices if i < j } yield cand(
      ws(i),
      0,
      ws(j),
      0,
      "initials-surname",
      key
    )
  }

  private lazy val input = writeExport(works, candidates)

  private def runTo(in: java.nio.file.Path, out: java.nio.file.Path) =
    run(erConfig(in, out), new AlwaysAbstainAdjudicator(Model))

  private lazy val first = runTo(input, workRoot("det-a-"))
  private lazy val second = runTo(input, workRoot("det-b-"))
  private lazy val moved =
    runTo(copyTree(input, workRoot("det-moved-").resolve("run=copy")), workRoot("det-c-"))

  private def comparable(t: Map[String, Array[Byte]]): Map[String, Seq[Byte]] =
    t.collect {
      case (k, v) if k != "_manifest.json" => k -> v.toSeq
    }

  test(
    "same inputs and configuration give the same run ID and the same bytes in another output root"
  ) {
    assertEquals(first.runId, second.runId)
    assertEquals(comparable(tree(second.runDir)), comparable(tree(first.runDir)))
    val m1 = ujson.read(Files.readString(first.runDir.resolve("_manifest.json"))).obj
    val m2 = ujson.read(Files.readString(second.runDir.resolve("_manifest.json"))).obj
    assertEquals(m2.value.toMap - "inputDir", m1.value.toMap - "inputDir")
  }

  test("the same export at another path gives the same run ID and output bytes") {
    assertEquals(moved.runId, first.runId)
    assertEquals(comparable(tree(moved.runDir)), comparable(tree(first.runDir)))
  }

  test("a completed run is reused only when it still matches its manifest") {
    val again = runTo(input, first.runDir.getParent)
    assert(again.reused)
    val target = second.runDir.resolve("clusters/clusters.jsonl")
    Files.write(target, (Files.readString(target) + "\n").getBytes("UTF-8"))
    intercept[ErError](runTo(input, second.runDir.getParent))
  }

  test("every decision of the run is written with one decision time and validates") {
    val ds = pairDecisions(first.runDir)
    assertEquals(ds.size, candidates.size)
    assertEquals(ds.map(_.decisionTime).distinct, List(DecisionTime))
    assertEquals(assignments(first.runDir).size, works.map(_.authors.size).sum)
  }

  private lazy val queueCsv =
    Files.readString(first.runDir.resolve("review-queue/review-queue.csv"))
  private lazy val queueRows =
    lines(first.runDir.resolve("review-queue/review-queue.jsonl")).map(ujson.read(_).obj)

  test(
    "the label file is blind: no score, decision, band, stratum or probability column or value"
  ) {
    val header = queueCsv.linesIterator.next().split(',').toList
    assertEquals(header, ReviewQueue.Columns)
    val forbidden = List(
      "score",
      "decision",
      "outcome",
      "band",
      "stratum",
      "probab",
      "confidence",
      "rule",
      "reason",
      "path",
      "inclusion"
    )
    header.foreach(h =>
      forbidden.foreach(f => assert(!h.toLowerCase.contains(f), s"column $h reveals $f"))
    )
    val decisionWords =
      List("accepted", "rejected", "unresolved", "conflict", "llm-", "score.", "dblp-prior")
    queueRows.foreach { r =>
      r.value.values.map(_.str).foreach { v =>
        decisionWords.foreach(w => assert(!v.contains(w), s"queue value '$v' reveals '$w'"))
      }
      ReviewQueue.LabelColumns.foreach(c => assertEquals(r(c).str, "", c))
    }
  }

  test("queue rows are keyed by unordered mention pairs, unique, and traceable to dblp records") {
    assert(queueRows.nonEmpty)
    val pairs = queueRows.map(r => Set(r("leftMentionId").str, r("rightMentionId").str))
    assertEquals(pairs.distinct.size, pairs.size)
    val keys = works.map(_.key).toSet
    queueRows.foreach { r =>
      List("left", "right").foreach { side =>
        val k = r(s"${side}_dblpKey").str
        assert(keys(k), k)
        assertEquals(r(s"${side}_dblpUrl").str, s"https://dblp.org/rec/$k.html")
      }
    }
    assertEquals(queueCsv.linesIterator.size - 1, queueRows.size)
  }

  test("with same-numbered pairs skipped by J06, the queue still samples dblp-prior pairs") {
    // four mentions of one numbered dblp name and no candidate among them (the volume rule)
    val ws = (0 until 4).toList.map(i =>
      SW(s"conf/sigir/SynthNum$i", List("Nia Synthnum 0001", s"Coa Num$i"), year = 2010 + i)
    )
    val other = List(
      SW("conf/sigir/SynthNumX", List("Nia Synthnum", "Coa Numx")),
      SW("conf/sigir/SynthNumY", List("N. Synthnum", "Coa Numy"))
    )
    val cands = List(cand(other(0), 0, other(1), 0, "initials-surname", "n synthnum"))
    val in = writeExport(ws ++ other, cands)
    val out = run(
      erConfig(in, workRoot("prior-q-"), queue = org.scholarchain.er.review.QueueSpec(10, 3, 5L)),
      new AlwaysAbstainAdjudicator(Model)
    )
    val keyRows = lines(out.runDir.resolve("review-queue/review-key.jsonl")).map(ujson.read(_))
    val prior = keyRows.filter(
      _("memberships").arr.exists(
        _("stratum").str == org.scholarchain.er.review.StratumSampler.PriorStratum
      )
    )
    assertEquals(prior.size, 3)
    val numbered = ws.map(w => mid(w.key, 0)).toSet
    prior.foreach { r =>
      assert(numbered(r("leftMentionId").str) && numbered(r("rightMentionId").str), r.toString)
      // inclusion probability recorded: 3 sampled of C(4, 2) = 6 prior pairs
      val m = r("memberships").arr.head
      assertEqualsDouble(m("inclusionProbability").num, 0.5, 1e-12)
    }
  }

  test("inputs that break the candidate contract are refused, not defaulted") {
    val good = cand(works(0), 0, works(1), 0, "initials-surname", "a detnamea")
    val unknownFeature =
      good.copy(features =
        (good.features :+ org.scholarchain.core.MatchFeature("zz_invented", 1.0))
      )
    val wrongSet =
      good.copy(provenance = good.provenance.copy(policyVersion = "candidate-features/9;x"))
    List(unknownFeature, wrongSet).foreach { bad =>
      val in = writeExport(works.take(2), List(bad))
      intercept[ErError](runTo(in, workRoot("refuse-")))
    }
    val ghost = writeExport(works.take(1), List(good)) // names a mention not in the export
    intercept[ErError](runTo(ghost, workRoot("refuse-")))
  }
}
