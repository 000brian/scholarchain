package org.scholarchain.core

/** Stable ID formulas. Golden values were cross-checked with an independent Python implementation
  * of `sha256(len:component...)[:32]`; changing any of them is a breaking change.
  */
class StableIdsSuite extends munit.FunSuite {

  private def right[A](e: Either[String, A]): A = e.fold(m => fail(m), identity)

  val window: YearWindow = YearWindow(2015, 2025)

  /** A researcher ID for tests that only need some distinct researchers. */
  def researcher(dblpKey: String): ResearcherId =
    ResearcherId.fromAnchorMention(MentionId.of(WorkId.fromDblpKey(dblpKey), 0))

  def slot(year: Int, dblpKey: String, position: Int): AnchorCandidate =
    AnchorCandidate(year, WorkId.fromDblpKey(dblpKey), position)

  test("canonical encoding is length-prefixed UTF-8") {
    assertEquals(
      Hashing.canonicalEncoding(Seq("work/v1", "dblp", "conf/sigir/Sousa0J23")),
      "7:work/v14:dblp20:conf/sigir/Sousa0J23"
    )
    assertEquals(Hashing.canonicalEncoding(Seq("é")), "2:é")
    assertNotEquals(Hashing.digest32(Seq("a|b", "c")), Hashing.digest32(Seq("a", "b|c")))
    assertNotEquals(Hashing.digest32(Seq("ab", "")), Hashing.digest32(Seq("a", "b")))
  }

  test("golden: WorkId.fromDblpKey") {
    assertEquals(
      WorkId.fromDblpKey("conf/sigir/Sousa0J23").value,
      "work:v1:9908f02cfc6bda3f54556ffdc400f581"
    )
  }

  test("golden: MentionId.of") {
    assertEquals(
      MentionId.of(WorkId("work:v1:9908f02cfc6bda3f54556ffdc400f581"), 0).value,
      "mention:v1:34f70b4567c43fb2e857c8765d12d7b3"
    )
  }

  test("golden: ResearcherId.fromAnchorMention") {
    assertEquals(
      ResearcherId
        .fromAnchorMention(MentionId("mention:v1:34f70b4567c43fb2e857c8765d12d7b3"))
        .value,
      "researcher:v1:6d39fb31017461bdf6bbe118864d1809"
    )
    assertEquals(
      ResearcherId
        .fromAnchorMention(MentionId("mention:v1:75a3435346b593e5427df42c1d1f3f53"))
        .value,
      "researcher:v1:b09b464e25f55dd98a87bd4f0c20577c"
    )
    assertEquals(
      MentionId.of(WorkId("work:v1:9908f02cfc6bda3f54556ffdc400f581"), 1).value,
      "mention:v1:75a3435346b593e5427df42c1d1f3f53"
    )
  }

  test("golden: EdgeId.coauthor") {
    val a = ResearcherId("researcher:v1:6d39fb31017461bdf6bbe118864d1809")
    val b = ResearcherId("researcher:v1:b09b464e25f55dd98a87bd4f0c20577c")
    assertEquals(
      right(EdgeId.coauthor(a, b, window)).value,
      "edge:v1:4bb65089a2a14df5a532c14ba4a10cb4"
    )
  }

  test("golden: LinkId.of") {
    assertEquals(
      LinkId
        .of(
          LinkSubjectKind.Mention,
          "mention:v1:34f70b4567c43fb2e857c8765d12d7b3",
          ExternalIdType.OpenAlexAuthor,
          "A5080716895",
          SourceType.OpenAlex,
          "openalex",
          "2026-11-12"
        )
        .value,
      "link:v1:7756f92c370bb8a558652c009132dfe9"
    )
  }

  test("link IDs differ per source snapshot (OpenAlex IDs are snapshot-scoped)") {
    def at(snapshot: String) = LinkId.of(
      LinkSubjectKind.Mention,
      "mention:v1:34f70b4567c43fb2e857c8765d12d7b3",
      ExternalIdType.OpenAlexAuthor,
      "A5080716895",
      SourceType.OpenAlex,
      "openalex",
      snapshot
    )
    assertNotEquals(at("2026-11-12"), at("2026-10-14"))
    assertEquals(at("2026-11-12"), at("2026-11-12"))
  }

  test("golden: RunId.of and EvidenceId") {
    val run = right(
      RunId.of(
        "hadoop.parse-dblp",
        "fixture-code-version",
        ContentHash.of("fixture input manifest dblp-2026-11-01 dblp-subset-v1"),
        ContentHash.of("fixture configuration")
      )
    )
    assertEquals(run.value, "run:v1:044e0b57adf2607d9573368a2117394d")
    val work = "work:v1:9908f02cfc6bda3f54556ffdc400f581"
    val ev = EvidenceId.of(EvidenceType.Work, run, List(work))
    assertEquals(ev.value, "evidence:v1:b1e64e44ff8c584fc0763ee13843aa6b")
    assertEquals(
      EvidenceId.forBundle(run, List(ev)).value,
      "evidence:v1:eabadf2b2120f5c19b3f01d0245343ee"
    )
  }

  // Mentions of conf/sigir/SyntheticA24 position 1 and journals/pvldb/SyntheticE15 position 0.
  val mentionA = "mention:v1:00853dba003e529ef7295ad10ecae82b"
  val mentionB = "mention:v1:3fe0d2d64d85640503e25bfdc095bcfc"

  test("LlmAdjudicationId.of follows its formula and rejects blank inputs") {
    val h = ContentHash.of("x")
    val id = LlmAdjudicationId.of(h, "p/1", "m-1", "er-v1").toOption.get
    assertEquals(
      id.value,
      "llm-adjudication:v1:" +
        Hashing.digest32(Seq("llm-adjudication/v1", h, "p/1", "m-1", "er-v1"))
    )
    assert(LlmAdjudicationId.parse(id.value).isRight)
    assert(LlmAdjudicationId.of(" ", "p/1", "m-1", "er-v1").isLeft)
    assert(LlmAdjudicationId.of(h, "", "m-1", "er-v1").isLeft)
    assert(LlmAdjudicationId.of(h, "p/1", null, "er-v1").isLeft)
    assert(LlmAdjudicationId.of(h, "p/1", "m-1", " ").isLeft)
  }

  test("LLM adjudication cache key: each of the four parts changes the ID; repeats hit") {
    val h = ContentHash.of("x")
    def id(a: String, b: String, c: String, d: String) =
      LlmAdjudicationId.of(a, b, c, d).toOption.get
    val base = id(h, "p/1", "m-1", "er-v1")
    assertEquals(id(h, "p/1", "m-1", "er-v1"), base)
    List(
      id(ContentHash.of("y"), "p/1", "m-1", "er-v1"),
      id(h, "p/2", "m-1", "er-v1"),
      id(h, "p/1", "m-2", "er-v1"),
      id(h, "p/1", "m-1", "er-v2")
    ).foreach(other => assertNotEquals(other, base))
  }

  test("golden: MatchCandidateId.of") {
    assertEquals(
      MentionId.of(WorkId.fromDblpKey("conf/sigir/SyntheticA24"), 1).value,
      mentionA
    )
    assertEquals(
      MentionId.of(WorkId.fromDblpKey("journals/pvldb/SyntheticE15"), 0).value,
      mentionB
    )
    val id = MatchCandidateId.of(
      MatchSubjectKind.MentionPair,
      mentionA,
      mentionB,
      "dblp-base-name",
      "Synthetic Author"
    )
    assertEquals(id.map(_.value), Right("match-candidate:v1:aac7158dffa4fd74256a524403a22e6a"))
  }

  test("mention-pair candidate IDs are order-independent; self pairs have no ID") {
    def of(l: String, r: String) =
      MatchCandidateId.of(MatchSubjectKind.MentionPair, l, r, "f", "k")
    assertEquals(of(mentionA, mentionB), of(mentionB, mentionA))
    assert(of(mentionA, mentionA).isLeft)
  }

  test("mention-researcher candidate IDs keep subject roles (mention first)") {
    val r = "researcher:v1:6d39fb31017461bdf6bbe118864d1809"
    def of(l: String, rr: String) =
      MatchCandidateId.of(MatchSubjectKind.MentionResearcher, l, rr, "f", "k")
    assert(of(mentionA, r).isRight)
    assertNotEquals(of(mentionA, r), of(r, mentionA))
  }

  test("every match candidate ID input changes the ID") {
    def of(k: MatchSubjectKind, l: String, r: String, f: String, key: String) =
      right(MatchCandidateId.of(k, l, r, f, key))
    val pair = MatchSubjectKind.MentionPair
    val base = of(pair, mentionA, mentionB, "f", "k")
    val other = "mention:v1:34f70b4567c43fb2e857c8765d12d7b3"
    List(
      of(MatchSubjectKind.MentionResearcher, mentionA, mentionB, "f", "k"),
      of(pair, other, mentionB, "f", "k"),
      of(pair, mentionA, other, "f", "k"),
      of(pair, mentionA, mentionB, "g", "k"),
      of(pair, mentionA, mentionB, "f", "K")
    ).foreach(id => assertNotEquals(id, base))
    assert(MatchCandidateId.parse(base.value).isRight)
    assert(base.value.startsWith("match-candidate:v1:"))
  }

  test("IDs are deterministic: same inputs, same ID, across repeated calls") {
    val ids = (1 to 3).map(_ => WorkId.fromDblpKey("conf/sigir/0003SM23"))
    assertEquals(ids.distinct.size, 1)
    val runs = (1 to 3).map(_ => RunId.of("j", "c", "i", "f"))
    assertEquals(runs.distinct.size, 1)
  }

  test("every input component changes the ID") {
    val base = right(RunId.of("j", "c", "i", "f"))
    List(
      RunId.of("J", "c", "i", "f"),
      RunId.of("j", "C", "i", "f"),
      RunId.of("j", "c", "I", "f"),
      RunId.of("j", "c", "i", "F")
    ).foreach(r => assertNotEquals(right(r), base))
    assertNotEquals(WorkId.fromDblpKey("conf/sigir/a"), WorkId.fromDblpKey("conf/sigir/A"))
  }

  test("RunId rejects blank inputs") {
    assert(RunId.of("", "c", "i", "f").isLeft)
    assert(RunId.of("j", " ", "i", "f").isLeft)
    assert(RunId.of("j", "c", null, "f").isLeft)
  }

  test("undirected edge IDs are order-independent; directed ones are not") {
    val a = researcher("conf/x/a")
    val b = researcher("conf/x/b")
    assertEquals(EdgeId.coauthor(a, b, window), EdgeId.coauthor(b, a, window))
    val w = WorkId.fromDblpKey("conf/x/y")
    assertNotEquals(
      EdgeId.of(GraphEdgeLabel.Authored, a.value, w.value, window),
      EdgeId.of(GraphEdgeLabel.Authored, w.value, a.value, window)
    )
  }

  test("edge IDs depend on the window") {
    val a = researcher("conf/x/a")
    val b = researcher("conf/x/b")
    assertNotEquals(EdgeId.coauthor(a, b, window), EdgeId.coauthor(a, b, YearWindow(2015, 2019)))
  }

  test("an undirected self pair has no edge ID") {
    val a = researcher("conf/x/a")
    assert(EdgeId.coauthor(a, a, window).isLeft)
  }

  test("review finding 1: two clusters sharing a dblp name get different researcher IDs") {
    // Entity resolution splits the unnumbered dblp label "Wei Wang" into two people. Both
    // clusters have dblpNames == List("Wei Wang"); their mentions are disjoint.
    val weiWangA = List(slot(2019, "conf/x/WangL19", 0), slot(2021, "journals/y/WangZ21", 2))
    val weiWangB = List(slot(2020, "conf/z/ChenW20", 1), slot(2022, "conf/x/Wang22", 0))
    val a = ResearcherId.forCluster(weiWangA)
    val b = ResearcherId.forCluster(weiWangB)
    assert(a.nonEmpty && b.nonEmpty)
    assertNotEquals(a, b)
    // A cluster {m1, m2} and a cluster {m3} (disjoint mentions, overlapping names) also differ.
    val single = ResearcherId.forCluster(List(slot(2019, "conf/x/Other19", 0)))
    assertNotEquals(single, a)
  }

  test("researcher ID anchors on the earliest mention: year, then work ID, then position") {
    val early = slot(2015, "conf/x/b", 3)
    val cluster = List(slot(2023, "conf/x/a", 0), early, slot(2019, "conf/x/c", 1))
    assertEquals(ResearcherId.anchorOf(cluster), Some(early.mentionId))
    assertEquals(
      ResearcherId.forCluster(cluster),
      Some(ResearcherId.fromAnchorMention(early.mentionId))
    )
    assertEquals(ResearcherId.forCluster(cluster.reverse), ResearcherId.forCluster(cluster))
    assertEquals(ResearcherId.forCluster(Nil), None)
    // Same year: the work ID (code point order) decides, then the author position.
    val w1 = WorkId.fromDblpKey("conf/x/d")
    val w2 = WorkId.fromDblpKey("conf/x/e")
    val (lo, hi) = if (CodePointOrder.lessThan(w1.value, w2.value)) (w1, w2) else (w2, w1)
    assertEquals(
      ResearcherId.anchorOf(List(AnchorCandidate(2020, hi, 0), AnchorCandidate(2020, lo, 5))),
      Some(MentionId.of(lo, 5))
    )
    assertEquals(
      ResearcherId.anchorOf(List(AnchorCandidate(2020, lo, 4), AnchorCandidate(2020, lo, 2))),
      Some(MentionId.of(lo, 2))
    )
  }

  test("researcher ID stability: later mentions and new names keep the ID; earlier ones move it") {
    val cluster = List(slot(2018, "conf/x/a", 0), slot(2020, "conf/x/b", 1))
    val id = ResearcherId.forCluster(cluster)
    assertEquals(ResearcherId.forCluster(cluster :+ slot(2024, "conf/x/new", 0)), id)
    assertNotEquals(ResearcherId.forCluster(slot(2010, "conf/x/old", 0) :: cluster), id)
  }

  test("bundle IDs ignore item order and duplicates") {
    val run = right(RunId.of("j", "c", "i", "f"))
    val x = EvidenceId.of(EvidenceType.Work, run, List("a"))
    val y = EvidenceId.of(EvidenceType.Work, run, List("b"))
    assertEquals(EvidenceId.forBundle(run, List(x, y)), EvidenceId.forBundle(run, List(y, x, y)))
  }

  test("evidence IDs respect subject order (paths are ordered)") {
    val run = right(RunId.of("j", "c", "i", "f"))
    assertNotEquals(
      EvidenceId.of(EvidenceType.GraphPath, run, List("a", "b")),
      EvidenceId.of(EvidenceType.GraphPath, run, List("b", "a"))
    )
  }

  test("parse accepts only <kind>:v1:<32 lowercase hex>") {
    val good = "work:v1:9908f02cfc6bda3f54556ffdc400f581"
    assertEquals(WorkId.parse(good), Right(WorkId(good)))
    List(
      null,
      "",
      "work:v1:9908F02CFC6BDA3F54556FFDC400F581",
      "work:v2:9908f02cfc6bda3f54556ffdc400f581",
      "work:v1:9908f02cfc6bda3f54556ffdc400f58",
      "work:v1:9908f02cfc6bda3f54556ffdc400f5811",
      "work:v1:9908f02cfc6bda3f54556ffdc400f58g",
      "researcher:v1:9908f02cfc6bda3f54556ffdc400f581",
      " work:v1:9908f02cfc6bda3f54556ffdc400f581"
    ).foreach(s => assert(WorkId.parse(s).isLeft, String.valueOf(s)))
    assert(ResearcherId.parse("researcher:v1:6d39fb31017461bdf6bbe118864d1809").isRight)
    assert(EdgeId.parse("edge:v1:4bb65089a2a14df5a532c14ba4a10cb4").isRight)
    assert(LinkId.parse("link:v1:7756f92c370bb8a558652c009132dfe9").isRight)
    assert(EvidenceId.parse("evidence:v1:b1e64e44ff8c584fc0763ee13843aa6b").isRight)
    assert(RunId.parse("run:v1:044e0b57adf2607d9573368a2117394d").isRight)
    assert(MentionId.parse("mention:v1:34f70b4567c43fb2e857c8765d12d7b3").isRight)
    assert(RunId.parse("edge:v1:4bb65089a2a14df5a532c14ba4a10cb4").isLeft)
  }

  test("content hashes are sha256:<64 hex> of UTF-8") {
    assertEquals(
      ContentHash.of(
        "tieval: An Evaluation Framework for Temporal Information Extraction Systems."
      ),
      "sha256:097a8f0395a0f0d7b24339481fe72249a3325ae0b0e33a60d6f56208713afe33"
    )
    assert(ContentHash.isWellFormed(ContentHash.of("x")))
    assert(!ContentHash.isWellFormed("sha256:ABC"))
    assert(!ContentHash.isWellFormed("md5:" + "0" * 64))
  }
}
