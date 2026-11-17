package org.scholarchain.contracts.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.scholarchain.contracts.er.ErSynth.*
import org.scholarchain.core.*
import org.scholarchain.er.llm.{
  AdjudicationCache,
  AdjudicationPrompt,
  AdjudicationRequest,
  LlmStage,
  PairKey
}
import org.scholarchain.er.policy.{DecisionEngine, Policy}

/** LLM adjudication attacks (decision 12 and 18) through `ErRun.run` and `LlmStage.run` with
  * scripted, deterministic fake adjudicators: malformed JSON, fabricated IDs, attempts to change
  * source fields, abstention, cache hit and miss for each key part, and the per-run band cap.
  */
class LlmAdversarialSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  /** One LLM-band pair: equal folded names with different spellings, no shared coauthor, different
    * research areas, ten years apart (log-odds about 1.1, p about 0.75, inside [0.6, 0.9)).
    */
  private def bandPair(i: Int): (SW, SW) = {
    val s = s"Band${('a' + i).toChar}synth"
    (
      SW(
        s"conf/sigir/SynthBandL$i",
        List(s"Cxa$i One", s"Jöhn $s", s"Cxa$i Two"),
        year = 2010,
        title = "Alpha Retrieval Studies"
      ),
      SW(
        s"conf/cvpr/SynthBandR$i",
        List(s"Cxb$i One", s"John $s", s"Cxb$i Two"),
        year = 2020,
        venue = Some("conf/cvpr"),
        title = "Gamma Vision Pipelines"
      )
    )
  }

  private def bandCandidates(i: Int): List[MatchCandidate] = {
    val (l, r) = bandPair(i)
    List(
      cand(l, 1, r, 1, "initials-surname", s"j band${('a' + i).toChar}synth"),
      cand(l, 1, r, 1, "synthetic-family", s"x$i")
    )
  }

  private def exportOf(n: Int): Path = {
    val ws = (0 until n).toList.flatMap { i =>
      val (a, b) = bandPair(i); List(a, b)
    }
    writeExport(ws, (0 until n).toList.flatMap(bandCandidates))
  }

  private lazy val one: Path = exportOf(1)
  private lazy val five: Path = exportOf(5)

  private def bandMentions(i: Int): Set[String] = {
    val (l, r) = bandPair(i)
    Set(mid(l.key, 1), mid(r.key, 1))
  }

  test("precondition: every synthetic pair is in the LLM band of the current policy") {
    val corpus = ErSynth.corpus(five)
    (0 until 5).foreach { i =>
      val c = bandCandidates(i).head
      val as = DecisionEngine.assess(
        corpus,
        Policy.erV1,
        c.features,
        corpus.mentions.indexOf(c.left),
        corpus.mentions.indexOf(c.right)
      )
      assert(as.inLlmBand, s"pair $i not in band: p=${as.matchProbability}")
    }
  }

  private val abstain =
    """{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":["synthetic"]}"""

  private def accept(req: AdjudicationRequest, conf: Double = 0.95, chosen: Option[String] = None) =
    ujson
      .Obj(
        "outcome" -> "accept",
        "chosenCandidateId" -> chosen.getOrElse(req.offeredCandidateIds.head.value),
        "citedInputFields" -> ujson.Arr("left.name", "right.name"),
        "confidence" -> conf,
        "reasonCodes" -> ujson.Arr("synthetic-accept")
      )
      .render()

  private def withExtra(req: AdjudicationRequest, key: String, v: ujson.Value): String = {
    val o = ujson.read(accept(req)).obj
    o(key) = v
    o.render()
  }

  private final case class Ran(
      dir: Path,
      adj: Scripted,
      decisions: List[MatchDecision],
      assigned: List[MatchDecision],
      report: ujson.Value
  ) {
    def rejected: List[String] = lines(dir.resolve("llm/rejected-replies.jsonl"))
    def adjudications: List[String] = lines(dir.resolve("llm/adjudications.jsonl"))
    def researcher(m: String): String = assigned.find(_.left == m).get.right
  }

  private def runWith(
      input: Path,
      reply: AdjudicationRequest => String,
      llmMax: Int = 1000,
      cache: Option[Path] = None,
      model: String = Model
  ): Ran = {
    val adj = new Scripted(model, reply)
    val out = run(erConfig(input, workRoot("llm-out-"), llmMax = llmMax, cache = cache), adj)
    Ran(out.runDir, adj, pairDecisions(out.runDir), assignments(out.runDir), out.report)
  }

  private val Marker = "SYNTHETIC-INJECTED-MARKER"

  private val rejections: List[(String, AdjudicationRequest => String, String)] = List(
    ("malformed JSON", _ => s"{not json $Marker", "malformed-json"),
    ("markdown fenced JSON", _ => "```json\n" + abstain + "\n```", "malformed-json"),
    ("two JSON values", _ => abstain + " {}", "malformed-json"),
    ("a JSON array", _ => "[" + abstain + "]", "malformed-json"),
    ("empty reply", _ => "", "malformed-json"),
    (
      "a fabricated well-formed candidate ID",
      req =>
        accept(
          req,
          chosen = Some(
            MatchCandidateId
              .of(
                MatchSubjectKind.MentionPair,
                bandCandidates(0).head.left,
                bandCandidates(0).head.right,
                "fabricated-family",
                "fabricated-key"
              )
              .fold(e => throw new IllegalArgumentException(e), _.value)
          )
        ),
      "unoffered-candidate"
    ),
    (
      "a researcher ID instead of a candidate",
      req => accept(req, chosen = Some("researcher:v1:" + "cd" * 16)),
      "unoffered-candidate"
    ),
    (
      "an attempt to change the mention name",
      req => withExtra(req, "name", ujson.Str(Marker)),
      "source-field-mutation"
    ),
    (
      "an attempt to replace the left mention",
      req => withExtra(req, "left", ujson.Obj("name" -> Marker)),
      "source-field-mutation"
    ),
    (
      "an attempt to change the features",
      req => withExtra(req, "features", ujson.Obj("orcid_match" -> 1)),
      "source-field-mutation"
    ),
    (
      "an attempt to change the year",
      req => withExtra(req, "year", ujson.Num(1999)),
      "source-field-mutation"
    ),
    ("an unknown key", req => withExtra(req, "explanation", ujson.Str(Marker)), "schema"),
    (
      "a missing key",
      req => { val o = ujson.read(accept(req)).obj; o.remove("reasonCodes"); o.render() },
      "schema"
    ),
    ("confidence as a string", req => withExtra(req, "confidence", ujson.Str("0.95")), "schema"),
    ("confidence above one", req => withExtra(req, "confidence", ujson.Num(1.5)), "schema"),
    (
      "accept without a chosen ID",
      req => withExtra(req, "chosenCandidateId", ujson.Null),
      "schema"
    ),
    ("reject naming a chosen ID", req => withExtra(req, "outcome", ujson.Str("reject")), "schema"),
    ("an unknown outcome", req => withExtra(req, "outcome", ujson.Str("merge")), "schema"),
    (
      "a cited field that is not in the input",
      req => withExtra(req, "citedInputFields", ujson.Arr("left.salary")),
      "unknown-cited-field"
    ),
    ("an adjudicator exception", _ => throw new IllegalStateException(Marker), "adjudicator-error")
  )

  rejections.foreach { case (name, reply, reason) =>
    test(s"rejected model output ($name) leaves the pair UNRESOLVED with reason $reason") {
      val r = runWith(one, reply)
      assertEquals(r.adj.calls, 1)
      val ds = r.decisions.filter(d => Set(d.left, d.right) == bandMentions(0))
      assertEquals(ds.size, 2)
      ds.foreach { d =>
        assertEquals(d.outcome, MatchOutcome.Unresolved, d.toString)
        assertEquals(d.matchPath, MatchPath.Llm)
        assert(d.reasonCodes.contains(s"llm-output-rejected:$reason"), d.reasonCodes)
        assert(d.rulesFired.contains("llm.adjudicated"), d.rulesFired)
        assertEquals(d.modelVersion, Some(Model))
        assertEquals(d.promptVersion, Some(AdjudicationPrompt.Version))
      }
      assertEquals(r.adjudications, Nil)
      if (reason == "adjudicator-error") {
        // no reply exists to record; the failure is counted in the report and on the decisions
        assertEquals(r.rejected, Nil)
        assertEquals(r.report("llm")("reasons")(s"llm-output-rejected:$reason").num, 1.0)
      } else {
        assertEquals(r.rejected.size, 1)
        assert(r.rejected.head.contains(reason), r.rejected.head)
        assert(!r.rejected.head.contains(Marker), "the reply text must not be stored")
      }
      val (a, b) = (bandMentions(0).toList.sorted.head, bandMentions(0).toList.sorted.last)
      assertNotEquals(r.researcher(a), r.researcher(b))
      // nothing names an identifier that was not in the input
      assertEquals(
        r.decisions.flatMap(_.candidateId).toSet,
        bandCandidates(0).map(_.candidateId).toSet
      )
    }
  }

  test("duplicate JSON keys with conflicting values are rejected, not resolved last-wins") {
    val r = runWith(
      one,
      req => abstain.dropRight(1) + "," + accept(req).drop(1) // {...abstain..., ...accept...}
    )
    val ds = r.decisions.filter(d => Set(d.left, d.right) == bandMentions(0))
    ds.foreach(d =>
      assertNotEquals(d.outcome, MatchOutcome.Accepted, s"ambiguous reply was accepted: $d")
    )
  }

  test("abstention leaves the pair UNRESOLVED and records the adjudication") {
    val r = runWith(one, _ => abstain)
    val ds = r.decisions.filter(d => Set(d.left, d.right) == bandMentions(0))
    ds.foreach { d =>
      assertEquals(d.outcome, MatchOutcome.Unresolved)
      assertEquals(d.matchPath, MatchPath.Llm)
      assert(d.reasonCodes.contains("llm-abstain"), d.reasonCodes)
    }
    assertEquals(r.adjudications.size, 1)
    val adj = Contract.decode[LlmAdjudication](r.adjudications.head).toOption.get
    assertEquals(adj.outcome, AdjudicationOutcome.Abstain)
    assertEquals(adj.chosenCandidateId, None)
    assertEquals(adj.modelVersion, Model)
    assertEquals(adj.offeredCandidateIds.toSet, bandCandidates(0).map(_.candidateId).toSet)
    assertEquals(r.rejected, Nil)
  }

  test("a valid accept merges the pair on the llm path; low confidence stays UNRESOLVED") {
    val r = runWith(one, req => accept(req))
    val ds = r.decisions.filter(d => Set(d.left, d.right) == bandMentions(0))
    ds.foreach { d =>
      assertEquals(d.outcome, MatchOutcome.Accepted)
      assertEquals(d.matchPath, MatchPath.Llm)
    }
    val ms = bandMentions(0).toList
    assertEquals(r.researcher(ms.head), r.researcher(ms.last))
    val a = r.assigned.find(_.left == ms.head).get
    assertEquals(a.matchPath, MatchPath.Llm)
    assertEquals(a.modelVersion, Some(Model))

    val low = runWith(one, req => accept(req, conf = 0.5))
    low.decisions.filter(d => Set(d.left, d.right) == bandMentions(0)).foreach { d =>
      assertEquals(d.outcome, MatchOutcome.Unresolved)
      assert(d.reasonCodes.contains("llm-low-confidence"), d.reasonCodes)
    }
    assertNotEquals(low.researcher(ms.head), low.researcher(ms.last))
  }

  test(
    "band cap: adjudicator calls never exceed the cap; it counts mention pairs, not candidates"
  ) {
    val r = runWith(five, _ => abstain, llmMax = 3)
    assertEquals(r.adj.calls, 3)
    val llm = r.decisions.filter(_.matchPath == MatchPath.Llm)
    val capped = r.decisions.filter(_.reasonCodes.contains("llm-band-cap-reached"))
    // 3 adjudicated pairs x 2 candidates each are labelled llm: more llm decisions than calls
    assertEquals(llm.size, 6)
    assertEquals(llm.map(d => (d.left, d.right)).distinct.size, 3)
    assertEquals(capped.size, 4)
    capped.foreach { d =>
      assertEquals(d.matchPath, MatchPath.Probabilistic)
      assertEquals(d.outcome, MatchOutcome.Unresolved)
    }
    assertEquals(r.report("llm")("bandCandidates").num, 10.0)
    assertEquals(r.report("llm")("pairsSentToAdjudicator").num, 3.0)
    // every request offers exactly the band candidates of its pair
    r.adj.requests.foreach(q => assertEquals(q.offeredCandidateIds.size, 2))

    val zero = runWith(five, _ => abstain, llmMax = 0)
    assertEquals(zero.adj.calls, 0)
    assertEquals(zero.decisions.count(_.reasonCodes.contains("llm-band-cap-reached")), 10)
  }

  test("a cache file answers every pair: no adjudicator call, identical decisions") {
    val first = runWith(one, req => accept(req))
    val cacheFile = first.dir.resolve("llm/adjudications.jsonl")
    assertEquals(first.adjudications.size, 1)
    val second =
      runWith(one, _ => throw new AssertionError("must not be called"), cache = Some(cacheFile))
    assertEquals(second.adj.calls, 0)
    assertEquals(
      second.decisions,
      first.decisions.map(d =>
        d.copy(provenance = d.provenance.copy(runId = second.decisions.head.provenance.runId))
      )
    )
    assertEquals(second.report("llm")("cacheHits").num, 1.0)
    assertEquals(second.adjudications, Nil) // nothing new
    // the report must not claim pairs were sent when the cache answered them
    assertEquals(second.report("llm")("pairsSentToAdjudicator").num, 0.0)
  }

  // ------------------------------------------------------------------------------------------
  // Cache key: hit and miss for each of the four parts (LlmStage.run directly)
  // ------------------------------------------------------------------------------------------

  private def stage(
      cache: AdjudicationCache,
      adj: Scripted,
      policy: Policy = Policy.erV1,
      prompt: AdjudicationPrompt = AdjudicationPrompt.v1,
      featureTweak: List[MatchFeature] => List[MatchFeature] = identity
  ) = {
    val corpus = ErSynth.corpus(one)
    val c = bandCandidates(0).head
    val a = corpus.mentions.indexOf(c.left)
    val b = corpus.mentions.indexOf(c.right)
    val as = DecisionEngine.assess(corpus, policy, c.features, a, b)
    val key = PairKey.of(a, b)
    val prov = Provenance(
      1,
      SourceType.Pipeline,
      "scholarchain-er",
      Snapshot,
      List("conf/sigir/SynthBandL0"),
      Run,
      "er.llm-adjudication",
      policy.policyVersion,
      None
    )
    LlmStage
      .run(
        corpus,
        policy,
        List(key -> bandCandidates(0).map(_.candidateId.value).sorted(CodePointOrder.ordering)),
        _ => featureTweak(as.features),
        _ => as.matchProbability.get,
        _ => prov,
        adj,
        cache,
        prompt
      )
      ._1(key)
  }

  test("cache: same four key parts hit; each changed part misses") {
    val cache = new AdjudicationCache
    val m1 = new Scripted(Model, req => accept(req))
    val first = stage(cache, m1)
    assertEquals((m1.calls, first.fromCache), (1, false))
    val again = stage(cache, m1)
    assertEquals((m1.calls, again.fromCache), (1, true))
    assertEquals(again.adjudicationId, first.adjudicationId)

    val m2 = new Scripted("synthetic-model-2", req => accept(req))
    assertEquals(stage(cache, m2).fromCache, false, "model version")
    assertEquals(m2.calls, 1)

    val overridden =
      Policy.erV1.withThresholds("er-v1-synthetic-override", Policy.erV1.thresholds).toOption.get
    val m3 = new Scripted(Model, req => accept(req))
    assertEquals(stage(cache, m3, policy = overridden).fromCache, false, "policy version")
    assertEquals(m3.calls, 1)

    val m4 = new Scripted(Model, req => accept(req))
    val prompt2 = AdjudicationPrompt.v1.copy(version = "er-adjudication-prompt/1-synthetic")
    assertEquals(stage(cache, m4, prompt = prompt2).fromCache, false, "prompt version")
    assertEquals(m4.calls, 1)

    val m5 = new Scripted(Model, req => accept(req))
    val tweak = (fs: List[MatchFeature]) =>
      fs.map(f => if (f.name == "year_abs_diff") f.copy(value = f.value + 1) else f)
    assertEquals(stage(cache, m5, featureTweak = tweak).fromCache, false, "input hash")
    assertEquals(m5.calls, 1)

    // each variant now hits its own entry
    val m6 = new Scripted("synthetic-model-2", _ => throw new AssertionError("cached"))
    assertEquals(stage(cache, m6).fromCache, true)
    assertEquals(cache.newEntries.size, 5)
  }

  test("a rejected reply is never cached: the next run asks again") {
    val cache = new AdjudicationCache
    val bad = new Scripted(Model, _ => "{oops")
    stage(cache, bad)
    stage(cache, bad)
    assertEquals(bad.calls, 2)
    assertEquals(cache.newEntries, Nil)
  }

  // ------------------------------------------------------------------------------------------
  // F1 repair regression: repeated keys anywhere in the reply are rejected as duplicate-key
  // ------------------------------------------------------------------------------------------

  private val abstainBody =
    """"chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":["synthetic"]"""

  private val duplicateReplies: List[(String, AdjudicationRequest => String)] = List(
    (
      "identical values repeated at top level",
      _ => s"""{"outcome":"abstain","outcome":"abstain",$abstainBody}"""
    ),
    (
      "a key repeated through a JSON escape",
      _ => s"""{"outcome":"abstain","\\u006futcome":"abstain",$abstainBody}"""
    ),
    (
      "an object with a repeated key inside an array",
      _ =>
        """{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[{"x":1,"x":2}],"confidence":0,"reasonCodes":["synthetic"]}"""
    ),
    (
      "a repeated key deep inside nested arrays and objects",
      _ =>
        """{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":[[{"a":{"b":1,"b":1}}]]}"""
    ),
    (
      "a repeated key inside an unknown key's value",
      req => {
        val o = ujson.read(accept(req)).obj
        o.render().dropRight(1) + ""","note":{"left":1,"left":2}}"""
      }
    )
  )

  duplicateReplies.foreach { case (name, reply) =>
    test(s"duplicate keys ($name) are rejected as duplicate-key, never schema or last-wins") {
      val cache = new AdjudicationCache
      val adj = new Scripted(Model, reply)
      val r = stage(cache, adj)
      assertEquals(adj.calls, 1)
      assertEquals(r.outcome, MatchOutcome.Unresolved, r.toString)
      assert(r.reasonCodes.contains("llm-output-rejected:duplicate-key"), r.reasonCodes)
      assert(!r.reasonCodes.exists(_.startsWith("llm-output-rejected:schema")), r.reasonCodes)
      assertEquals(r.adjudicationId, None)
      assertEquals(cache.newEntries, Nil)
    }
  }

  test("keys that differ only by case are not duplicates (rejected as schema, not duplicate-key)") {
    val adj = new Scripted(
      Model,
      _ => s"""{"outcome":"abstain","Outcome":"abstain",$abstainBody}"""
    )
    val r = stage(new AdjudicationCache, adj)
    assertEquals(r.outcome, MatchOutcome.Unresolved)
    assert(r.reasonCodes.contains("llm-output-rejected:schema"), r.reasonCodes)
    assert(!r.reasonCodes.contains("llm-output-rejected:duplicate-key"), r.reasonCodes)
  }

  test("a duplicate-key reply is recorded by hash only and counted in the run report") {
    val marker = "SYNTHETIC-DUP-MARKER"
    val r = runWith(
      one,
      _ => s"""{"outcome":"abstain","outcome":"$marker",$abstainBody}"""
    )
    assertEquals(r.rejected.size, 1)
    assert(r.rejected.head.contains("duplicate-key"), r.rejected.head)
    assert(!r.rejected.head.contains(marker), "the reply text must not be stored")
    assertEquals(r.report("llm")("reasons")("llm-output-rejected:duplicate-key").num, 1.0)
    assertEquals(r.report("llm")("rejectedReplies").num, 1.0)
    r.decisions.filter(d => Set(d.left, d.right) == bandMentions(0)).foreach { d =>
      assertEquals(d.outcome, MatchOutcome.Unresolved)
      assert(d.reasonCodes.contains("llm-output-rejected:duplicate-key"), d.reasonCodes)
    }
  }

  // ------------------------------------------------------------------------------------------
  // F2 repair regression: report counts of selected, sent and cached pairs agree with reality
  // ------------------------------------------------------------------------------------------

  private def llmCounts(r: Ran): (Double, Double, Double, Double, Double) = {
    val l = r.report("llm")
    (
      l("pairsSelected").num,
      l("pairsSentToAdjudicator").num,
      l("pairsAnsweredFromCache").num,
      l("cacheHits").num,
      l("cacheMisses").num
    )
  }

  test("report: selected = sent + cached, sent = adjudicator calls, with a partly warm cache") {
    // warm the cache for pair 0 only, from the one-pair export
    val warm = runWith(one, req => accept(req))
    val cacheFile = warm.dir.resolve("llm/adjudications.jsonl")
    assertEquals(warm.adjudications.size, 1)
    val (wSel, wSent, wCached, _, _) = llmCounts(warm)
    assertEquals((wSel, wSent, wCached), (1.0, 1.0, 0.0))

    // five band pairs, no cap: pair 0 answered by the cache, pairs 1 to 4 sent
    val pair0 = bandCandidates(0).map(_.candidateId).toSet
    val r = runWith(five, _ => abstain, cache = Some(cacheFile))
    val (sel, sent, cached, hits, _) = llmCounts(r)
    assertEquals(sel, 5.0)
    assertEquals(sent, r.adj.calls.toDouble)
    assertEquals(sent, 4.0)
    assertEquals(cached, 1.0)
    assertEquals(sel, sent + cached)
    assertEquals(hits, cached)
    assertEquals(r.report("llm")("bandCandidates").num, 10.0)
    // the cached pair carries the cached accept; the sent ones the abstention
    val ms0 = bandMentions(0).toList
    assertEquals(r.researcher(ms0.head), r.researcher(ms0.last))
    r.decisions.filter(d => Set(d.left, d.right) == bandMentions(1)).foreach { d =>
      assert(d.reasonCodes.contains("llm-abstain"), d.reasonCodes)
    }
    // only new answers are written as new adjudications, and the cached pair is never sent
    assertEquals(r.adjudications.size, 4)
    r.adj.requests.foreach(q =>
      assert(q.offeredCandidateIds.toSet.intersect(pair0).isEmpty, "cached pair was sent")
    )

    // cap 3 with the same warm cache: the cap counts selected pairs whether or not cached
    val capped = runWith(five, _ => abstain, llmMax = 3, cache = Some(cacheFile))
    val (cSel, cSent, cCached, cHits, _) = llmCounts(capped)
    val llmPairs = capped.decisions
      .filter(_.matchPath == MatchPath.Llm)
      .map(d => Set(d.left, d.right))
      .distinct
    assertEquals(llmPairs.size, 3)
    assertEquals(cSel, 3.0, "a cached pair still counts against the per-run cap")
    val expectCached = if (llmPairs.contains(bandMentions(0))) 1.0 else 0.0
    assertEquals(cCached, expectCached)
    assertEquals(cSent, 3.0 - expectCached)
    assertEquals(cSent, capped.adj.calls.toDouble)
    assertEquals(cHits, cCached)
    assertEquals(capped.decisions.count(_.reasonCodes.contains("llm-band-cap-reached")), 4)
  }

  test("report: rejected replies still count as sent pairs, never as cached ones") {
    val r = runWith(five, _ => "{not json", llmMax = 1000)
    val (sel, sent, cached, hits, _) = llmCounts(r)
    assertEquals(r.adj.calls, 5)
    assertEquals((sel, sent, cached, hits), (5.0, 5.0, 0.0, 0.0))
    assertEquals(r.report("llm")("rejectedReplies").num, 5.0)
    val zero = runWith(five, _ => abstain, llmMax = 0)
    val (zSel, zSent, zCached, _, _) = llmCounts(zero)
    assertEquals((zSel, zSent, zCached), (0.0, 0.0, 0.0))
    assertEquals(zero.adj.calls, 0)
  }

  // ------------------------------------------------------------------------------------------
  // Decision 13 / F3: the adjudication input shows each mention's own spelling
  // ------------------------------------------------------------------------------------------

  test("the adjudication input carries each mention's exact spelling, decomposed bytes kept") {
    val nfdName = "Jöhn Bandnfdsynth" // decomposed o + U+0308
    val l = SW(
      "conf/sigir/SynthBandNfdL",
      List("Cxn One", nfdName, "Cxn Two"),
      year = 2010,
      title = "Alpha Retrieval Studies"
    )
    val r = SW(
      "conf/cvpr/SynthBandNfdR",
      List("Cxm One", "John Bandnfdsynth", "Cxm Two"),
      year = 2020,
      venue = Some("conf/cvpr"),
      title = "Gamma Vision Pipelines"
    )
    val in =
      writeExport(List(l, r), List(cand(l, 1, r, 1, "initials-surname", "j bandnfdsynth")))
    val ran = runWith(in, _ => abstain)
    assertEquals(ran.adj.calls, 1, "precondition: the pair is in the LLM band")
    val input = ujson.read(ran.adj.requests.head.inputJson)
    val shown = Set(input("left")("name").str, input("right")("name").str)
    assertEquals(shown, Set(nfdName, "John Bandnfdsynth"))
    assert(shown.contains(nfdName) && !shown.contains("Jöhn Bandnfdsynth"), shown)
  }

  test("the adjudication input carries copies of source facts and the prompt has no model ID") {
    val adj = new Scripted(Model, _ => abstain)
    stage(new AdjudicationCache, adj)
    val req = adj.requests.head
    val in = ujson.read(req.inputJson)
    assertEquals(in("left")("name").str.nonEmpty, true)
    assertEquals(req.inputHash, ContentHash.of(req.inputJson))
    assert(req.prompt.contains(req.inputJson))
    assert(!req.prompt.contains(Model))
    val promptText = new String(
      org.scholarchain.er.io.DataFiles.readResource(AdjudicationPrompt.Resource),
      StandardCharsets.UTF_8
    )
    assert(promptText.contains("abstain"), "the prompt must offer abstention")
  }
}
