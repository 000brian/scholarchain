package org.scholarchain.er

import java.nio.charset.StandardCharsets

import org.scholarchain.core.*
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.io.DataFiles
import org.scholarchain.er.llm.*
import org.scholarchain.er.policy.{Policy, Reasons}

/** Deterministic fake adjudicator: replies from a script; counts calls. */
final class ScriptedAdjudicator(val modelVersion: String)(reply: AdjudicationRequest => String)
    extends Adjudicator {
  var calls = 0
  def adjudicate(request: AdjudicationRequest): String = { calls += 1; reply(request) }
}

class LlmSuite extends munit.FunSuite {
  private val policy = Policy.current
  private val prompt = AdjudicationPrompt.v1
  private lazy val corpus = Fixture
    .corpus(
      List(
        W("conf/www/L1", List("John Smith", "Alice Coauthor"), title = "Graph Mining"),
        W("conf/www/L2", List("J. Smith", "Bob Coauthor"), title = "Graph Search")
      )
    )
    ._1
  private val c1 = MatchCandidateId
    .of(MatchSubjectKind.MentionPair, "a", "b", "initials-surname", "j smith")
    .toOption
    .get
    .value
  private val c2 = MatchCandidateId
    .of(MatchSubjectKind.MentionPair, "a", "b", "dblp-base-name", "x")
    .toOption
    .get
    .value
  private val offered = List(c1, c2).sorted
  private def request(features: List[MatchFeature] = List(MatchFeature("same_venue", 1))) =
    AdjudicationRequest.of(
      LlmStage.input(corpus, 0, 1, features, offered),
      offered.map(MatchCandidateId(_)),
      prompt
    )
  private def prov = Provenance(
    1,
    SourceType.Pipeline,
    "scholarchain-er",
    "s",
    List("conf/www/L1"),
    Fixture.Run,
    "er.llm-adjudication",
    "er-v1",
    None
  )
  private def validate(raw: String) =
    OutputValidator.validate(raw, request(), "fake-model/1", "er-v1", prov)

  test(
    "prompt and schema resources are versioned; the schema allows exactly the validator's keys"
  ) {
    assertEquals(prompt.version, "er-adjudication-prompt/1")
    assertEquals(prompt.outputSchemaVersion, "er-adjudication-output/1")
    assert(prompt.body.contains("abstain"))
    assert(prompt.render("{}").endsWith("{}\n"))
    val schema = ujson.read(
      new String(DataFiles.readResource(AdjudicationPrompt.SchemaResource), StandardCharsets.UTF_8)
    )
    assertEquals(schema("$id").str, AdjudicationPrompt.OutputSchemaVersion)
    assertEquals(schema("additionalProperties").bool, false)
    assertEquals(schema("properties").obj.keySet.toSet, OutputValidator.Keys)
    assertEquals(schema("required").arr.map(_.str).toSet, OutputValidator.Keys)
    assertEquals(
      schema("properties")("outcome")("enum").arr.map(_.str).toSet,
      Set("accept", "reject", "abstain")
    )
  }

  test("the input hash is the content hash of the exact input; the prompt embeds it as data") {
    val r = request()
    assertEquals(r.inputHash, ContentHash.of(r.inputJson))
    assert(r.prompt.contains(r.inputJson))
    assert(r.citablePaths.contains("left.coauthors"))
    assert(r.citablePaths.contains("features.same_venue"))
    assertNotEquals(request(List(MatchFeature("same_venue", 0))).inputHash, r.inputHash)
  }

  test("a valid accept names an offered candidate and becomes a validated LlmAdjudication") {
    val raw =
      s"""{"outcome":"accept","chosenCandidateId":"$c1","citedInputFields":["right.title","left.coauthors"],"confidence":0.9,"reasonCodes":["shared-topic"]}"""
    val adj = validate(raw).toOption.get
    assertEquals(adj.outcome, AdjudicationOutcome.Accept)
    assertEquals(adj.chosenCandidateId.map(_.value), Some(c1))
    assertEquals(adj.citedInputFields, List("left.coauthors", "right.title"))
    assertEquals(
      adj.adjudicationId,
      LlmAdjudicationId.of(adj.inputHash, prompt.version, "fake-model/1", "er-v1").toOption.get
    )
    assertEquals(Contract.validate(adj), Nil)
  }

  test(
    "malformed JSON, a fabricated ID, a source-field change, unknown keys and unknown cited fields are rejected"
  ) {
    def reason(raw: String) = validate(raw).left.toOption.map(_.reason)
    assertEquals(reason("not json"), Some("malformed-json"))
    assertEquals(reason("[1,2]"), Some("malformed-json"))
    val fabricated =
      MatchCandidateId.of(MatchSubjectKind.MentionPair, "x", "y", "f", "k").toOption.get.value
    assertEquals(
      reason(
        s"""{"outcome":"accept","chosenCandidateId":"$fabricated","citedInputFields":["left.name"],"confidence":0.9,"reasonCodes":[]}"""
      ),
      Some("unoffered-candidate")
    )
    assertEquals(
      reason(
        s"""{"outcome":"accept","chosenCandidateId":"researcher:v1:00000000000000000000000000000000","citedInputFields":["left.name"],"confidence":0.9,"reasonCodes":[]}"""
      ),
      Some("unoffered-candidate")
    )
    assertEquals(
      reason(
        """{"outcome":"reject","chosenCandidateId":null,"citedInputFields":["left.name"],"confidence":0.9,"reasonCodes":[],"left":{"name":"Someone Else"}}"""
      ),
      Some("source-field-mutation")
    )
    assertEquals(
      reason(
        """{"outcome":"reject","chosenCandidateId":null,"citedInputFields":["left.name"],"confidence":0.9,"reasonCodes":[],"orcids":["0000-0002-1825-0097"]}"""
      ),
      Some("source-field-mutation")
    )
    assertEquals(
      reason(
        """{"outcome":"reject","chosenCandidateId":null,"citedInputFields":["left.name"],"confidence":0.9,"reasonCodes":[],"rationale":"hidden"}"""
      ),
      Some("schema")
    )
    assertEquals(
      reason(
        """{"outcome":"maybe","chosenCandidateId":null,"citedInputFields":[],"confidence":0.5,"reasonCodes":[]}"""
      ),
      Some("schema")
    )
    assertEquals(
      reason(
        """{"outcome":"reject","chosenCandidateId":null,"citedInputFields":["left.salary"],"confidence":0.9,"reasonCodes":[]}"""
      ),
      Some("unknown-cited-field")
    )
    assertEquals(
      reason(
        """{"outcome":"reject","chosenCandidateId":null,"citedInputFields":[],"confidence":0.9,"reasonCodes":[]}"""
      ),
      Some("contract")
    )
    assertEquals(
      reason(
        """{"outcome":"reject","chosenCandidateId":null,"citedInputFields":["left.name"],"confidence":1.5,"reasonCodes":[]}"""
      ),
      Some("schema")
    )
  }

  test("replies with a repeated key at any nesting level are rejected, never read last-wins") {
    def reason(raw: String) = validate(raw).left.toOption.map(r => (r.reason, r.detail))
    val abstain =
      """{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":[]"""
    val accept =
      s""""outcome":"accept","chosenCandidateId":"$c1","citedInputFields":["left.name"],"confidence":0.95,"reasonCodes":[]}"""
    // ujson.read alone would resolve this to the accept (last wins)
    assert(ujson.read(abstain + "," + accept).obj("outcome").str == "accept")
    assertEquals(reason(abstain + "," + accept), Some(("duplicate-key", "outcome")))
    // the same key twice with the same value is still ambiguous output
    assertEquals(
      reason(
        """{"outcome":"abstain","outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":[]}"""
      ),
      Some(("duplicate-key", "outcome"))
    )
    // nested objects, also inside arrays (rejected before any schema check)
    assertEquals(
      reason(
        """{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":[],"x":{"a":1,"a":2}}"""
      ),
      Some(("duplicate-key", "a"))
    )
    assertEquals(
      reason(
        """{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":[{"k":1,"k":1}]}"""
      ),
      Some(("duplicate-key", "k"))
    )
    // equal keys in different objects are fine
    assert(
      validate(
        """{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":[]}"""
      ).isRight
    )
    assertEquals(StrictJson.read("""[{"a":1},{"a":2}]""").map(_.arr.size), Right(2))
    assertEquals(StrictJson.read("{"), Left(StrictJson.Malformed))
    assertEquals(StrictJson.read(null), Left(StrictJson.Malformed))
  }

  test("LLM stage: a duplicate-key reply ends UNRESOLVED with llm-output-rejected:duplicate-key") {
    val key = PairKey.of(0, 1)
    val reply =
      s"""{"outcome":"abstain","chosenCandidateId":null,"citedInputFields":[],"confidence":0,"reasonCodes":[],"outcome":"accept","chosenCandidateId":"$c1","citedInputFields":["left.name"],"confidence":0.99,"reasonCodes":[]}"""
    val adj = new ScriptedAdjudicator("fake-model/1")(_ => reply)
    val cache = new AdjudicationCache
    val (res, rej, _) =
      LlmStage.run(
        corpus,
        policy,
        List(key -> offered),
        _ => Nil,
        _ => 0.7,
        _ => prov,
        adj,
        cache,
        prompt
      )
    assertEquals(
      (res(key).outcome, res(key).reasonCodes, res(key).adjudicationId),
      (MatchOutcome.Unresolved, List(Reasons.llmRejected("duplicate-key")), None)
    )
    assertEquals(rej.map(_.reason), List("duplicate-key"))
    assertEquals(cache.size, 0) // nothing cached from an ambiguous reply
  }

  test("abstention is valid and needs no cited field; the always-abstain adjudicator abstains") {
    val adj =
      validate(new AlwaysAbstainAdjudicator("fake-model/1").adjudicate(request())).toOption.get
    assertEquals(adj.outcome, AdjudicationOutcome.Abstain)
    assertEquals(adj.chosenCandidateId, None)
  }

  test(
    "cache: hit on the same key; a miss when any of input hash, prompt, model or policy version changes"
  ) {
    val cache = new AdjudicationCache
    val adj =
      validate(new AlwaysAbstainAdjudicator("fake-model/1").adjudicate(request())).toOption.get
    cache.put(adj)
    val h = adj.inputHash
    assert(cache.get(h, prompt.version, "fake-model/1", "er-v1").isDefined)
    assert(
      cache.get(ContentHash.of("other input"), prompt.version, "fake-model/1", "er-v1").isEmpty
    )
    assert(cache.get(h, "er-adjudication-prompt/2", "fake-model/1", "er-v1").isEmpty)
    assert(cache.get(h, prompt.version, "fake-model/2", "er-v1").isEmpty)
    assert(cache.get(h, prompt.version, "fake-model/1", "er-v2").isEmpty)
    assertEquals((cache.hits, cache.misses), (1L, 4L))
  }

  test("band selection keeps the smallest pair keys up to the cap, with all their candidates") {
    val sel = new BandSelection[Int](2)
    sel.offer(PairKey.of(5, 9), "c5", 5)
    sel.offer(PairKey.of(1, 2), "c1", 1)
    sel.offer(PairKey.of(3, 4), "c3", 3)
    sel.offer(PairKey.of(1, 2), "c1b", 1)
    sel.offer(PairKey.of(7, 8), "c7", 7)
    assertEquals(
      sel.selected,
      List(PairKey.of(1, 2) -> List("c1", "c1b"), PairKey.of(3, 4) -> List("c3"))
    )
    assertEquals(sel.bandCandidates, 5L)
    val none = new BandSelection[Int](0)
    none.offer(1L, "c", 0)
    assertEquals(none.selected, Nil)
  }

  test(
    "LLM stage: accept, reject, abstain, low confidence and rejected replies map to decisions; cache hits skip the call"
  ) {
    val key = PairKey.of(0, 1)
    def runWith(reply: String, cache: AdjudicationCache = new AdjudicationCache) = {
      val adj = new ScriptedAdjudicator("fake-model/1")(_ => reply)
      val (res, rej, _) = LlmStage.run(
        corpus,
        policy,
        List(key -> offered),
        _ => Nil,
        _ => 0.7,
        _ => prov,
        adj,
        cache,
        prompt
      )
      (res(key), rej, adj.calls)
    }
    val acc = runWith(
      s"""{"outcome":"accept","chosenCandidateId":"$c2","citedInputFields":["left.name"],"confidence":0.85,"reasonCodes":[]}"""
    )
    assertEquals((acc._1.outcome, acc._1.confidence), (MatchOutcome.Accepted, 0.85))
    val rej = runWith(
      """{"outcome":"reject","chosenCandidateId":null,"citedInputFields":["left.name"],"confidence":0.95,"reasonCodes":[]}"""
    )
    assertEquals(rej._1.outcome, MatchOutcome.Rejected)
    val low = runWith(
      s"""{"outcome":"accept","chosenCandidateId":"$c2","citedInputFields":["left.name"],"confidence":0.5,"reasonCodes":[]}"""
    )
    assertEquals(
      (low._1.outcome, low._1.reasonCodes, low._1.confidence),
      (MatchOutcome.Unresolved, List(Reasons.LlmLowConfidence), 0.7)
    )
    val bad = runWith("garbage")
    assertEquals(
      (bad._1.outcome, bad._1.reasonCodes),
      (MatchOutcome.Unresolved, List(Reasons.llmRejected("malformed-json")))
    )
    assertEquals(bad._2.map(_.reason), List("malformed-json"))
    val cache = new AdjudicationCache
    val first = runWith(new AlwaysAbstainAdjudicator("fake-model/1").adjudicate(null), cache)
    assertEquals((first._1.reasonCodes, first._3), (List(Reasons.LlmAbstain), 1))
    val second = runWith("never used", cache)
    assertEquals(
      (second._1.reasonCodes, second._1.fromCache, second._3),
      (List(Reasons.LlmAbstain), true, 0)
    )
  }

  test(
    "m4: an adjudicator exception is returned as an error record (class and message hash only)"
  ) {
    val key = PairKey.of(0, 1)
    val secretish = "SYNTHETIC reply text that must not be stored"
    val adj =
      new ScriptedAdjudicator("fake-model/1")(_ => throw new IllegalStateException(secretish))
    val (res, rej, errs) = LlmStage.run(
      corpus,
      policy,
      List(key -> offered),
      _ => Nil,
      _ => 0.7,
      _ => prov,
      adj,
      new AdjudicationCache,
      prompt
    )
    assertEquals(res(key).reasonCodes, List(Reasons.llmRejected("adjudicator-error")))
    assertEquals(rej, Nil)
    assertEquals(errs.size, 1)
    assertEquals(errs.head.errorClass, "java.lang.IllegalStateException")
    assertEquals(
      errs.head.messageSha256,
      Some(DataFiles.sha256OfBytes(secretish.getBytes(StandardCharsets.UTF_8)))
    )
    assertEquals((errs.head.left, errs.head.right), (corpus.mentions.id(0), corpus.mentions.id(1)))
  }

  test("m4: a run records adjudicator errors in llm/adjudicator-errors.jsonl and counts them") {
    val input = Fixture.writeExport(RunFixture.works, maxBlock = 6)
    val marker = "SYNTHETIC-MARKER-m4"
    val adj = new ScriptedAdjudicator("fake-model/1")(_ => throw new IllegalStateException(marker))
    val out = RunFixture.run(input, java.nio.file.Files.createTempDirectory("er-m4"), adj)
    val errors = Fixture.lines(out.runDir.resolve("llm/adjudicator-errors.jsonl"))
    val sent = out.report("llm")("pairsSentToAdjudicator").num.toInt
    assert(sent > 0)
    assertEquals(errors.size, sent)
    assertEquals(out.report("llm")("adjudicatorErrors").num.toInt, sent)
    assertEquals(out.report("llm")("rejectedReplies").num.toInt, 0)
    errors.map(ujson.read(_)).foreach { e =>
      assertEquals(e("reason").str, "adjudicator-error")
      assertEquals(e("errorClass").str, "java.lang.IllegalStateException")
      assert(e("messageSha256").str.length == 64)
    }
    errors.foreach(l => assert(!l.contains(marker), "the exception message must not be stored"))
    val m = ujson.read(
      new String(
        java.nio.file.Files.readAllBytes(out.runDir.resolve("_manifest.json")),
        StandardCharsets.UTF_8
      )
    )
    assert(m("outputs").arr.exists(_("path").str == "llm/adjudicator-errors.jsonl"))
  }
}
