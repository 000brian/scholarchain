package org.scholarchain.er

import org.scholarchain.core.{AnchorCandidate, MatchPath, ResearcherId, WorkId}
import org.scholarchain.er.Fixture.W
import org.scholarchain.er.cluster.{
  CannotLinks,
  ClusterResult,
  Clustering,
  EdgeCollector,
  IntOpenSet
}
import org.scholarchain.er.data.Corpus
import org.scholarchain.er.policy.Reasons

class ClusteringSuite extends munit.FunSuite {
  private val oX = Fixture.orcid("000000021825009")
  private val oY = Fixture.orcid("000000034567123")

  private def build(works: List[W]): Corpus = Fixture.corpus(works)._1
  private def name(c: Corpus, n: String): Int = c.names.spelling.indexOf(n)
  private def run(c: Corpus, edges: (String, String, Double)*): ClusterResult = {
    val e = new EdgeCollector
    edges.foreach { case (a, b, conf) =>
      e.add(name(c, a), name(c, b), conf, MatchPath.Probabilistic)
    }
    Clustering.run(c, e)
  }
  private def same(c: Corpus, r: ClusterResult, a: String, b: String) =
    r.rootOf(name(c, a)) == r.rootOf(name(c, b))

  test(
    "clusters with different ORCID iDs never merge, also through a chain (A ~ B ~ C, A and C disagree)"
  ) {
    val c = build(
      List(
        W("conf/kdd/O1", List("Ann Bell"), orcids = Map(0 -> oX)),
        W("conf/kdd/O2", List("A. Bell")),
        W("conf/kdd/O3", List("Ann M. Bell"), orcids = Map(0 -> oY))
      )
    )
    val r = run(c, ("Ann Bell", "A. Bell", 0.95), ("A. Bell", "Ann M. Bell", 0.93))
    assert(same(c, r, "Ann Bell", "A. Bell"))
    assert(!same(c, r, "Ann Bell", "Ann M. Bell"))
    assertEquals(
      r.refused(EdgeCollector.key(name(c, "A. Bell"), name(c, "Ann M. Bell"))),
      Reasons.MergeOrcid
    )
    // Merge order is by confidence, not input order: the stronger edge wins either way.
    val r2 = run(c, ("A. Bell", "Ann M. Bell", 0.97), ("Ann Bell", "A. Bell", 0.95))
    assert(same(c, r2, "A. Bell", "Ann M. Bell"))
    assert(!same(c, r2, "Ann Bell", "A. Bell"))
  }

  test("clusters holding mentions of the same work never merge") {
    val c = build(List(W("conf/kdd/S1", List("Kim Lee", "K. Lee"))))
    val r = run(c, ("Kim Lee", "K. Lee", 0.99))
    assert(!same(c, r, "Kim Lee", "K. Lee"))
    assertEquals(r.refused.values.toList, List(Reasons.MergeSameWork))
  }

  test("a transitive chain cannot join two different dblp homonym numbers") {
    val c = build(
      List(
        W("conf/kdd/H1", List("Tom Gray 0001")),
        W("conf/kdd/H2", List("Tom H. Gray")),
        W("conf/kdd/H3", List("Tom Gray 0002"))
      )
    )
    val r = run(c, ("Tom Gray 0001", "Tom H. Gray", 0.95), ("Tom H. Gray", "Tom Gray 0002", 0.92))
    assert(same(c, r, "Tom Gray 0001", "Tom H. Gray"))
    assert(!same(c, r, "Tom H. Gray", "Tom Gray 0002"))
    assertEquals(r.refused.values.toList, List(Reasons.MergeDistinctHomonym))
  }

  test(
    "an unnumbered disambiguation profile is never merged into a numbered profile of its base name"
  ) {
    val c = build(List(W("conf/kdd/D1", List("Wei Wang")), W("conf/kdd/D2", List("Wei Wang 0001"))))
    assert(c.names.disambiguation(name(c, "Wei Wang")))
    val r = run(c, ("Wei Wang", "Wei Wang 0001", 0.99))
    assert(!same(c, r, "Wei Wang", "Wei Wang 0001"))
    assertEquals(r.refused.values.toList, List(Reasons.MergeDisambiguation))
  }

  test(
    "a dblp-prior cluster with two ORCID iDs or two mentions of one work is flagged, not split"
  ) {
    val c = build(
      List(
        W("conf/kdd/F1", List("Zed Moon"), orcids = Map(0 -> oX)),
        W("conf/kdd/F2", List("Zed Moon"), orcids = Map(0 -> oY)),
        W("conf/kdd/F3", List("Kim Lee", "Kim Lee"))
      )
    )
    val r = run(c)
    val zed = r.clusters(r.rootOf(name(c, "Zed Moon")))
    assertEquals(zed.mentions, 2)
    assertEquals(zed.flags, List(Reasons.ClusterMultipleOrcids))
    assertEquals(r.clusters(r.rootOf(name(c, "Kim Lee"))).flags, List(Reasons.ClusterSameWork))
  }

  test("every mention has exactly one cluster; ResearcherId is the core earliest-paper rule") {
    val c = build(
      List(
        W("conf/kdd/E1", List("Lu Chen"), year = 2018),
        W("conf/kdd/E2", List("L. Chen"), year = 2016),
        W("conf/kdd/E3", List("Mo Park"), year = 2019)
      )
    )
    val r = run(c, ("Lu Chen", "L. Chen", 0.95))
    val clusterOf = (0 until c.mentions.size).map(i => r.clusterOfMention(c, i).researcherId)
    assertEquals(clusterOf.size, c.mentions.size)
    assertEquals(r.clusters.values.map(_.mentions).sum, c.mentions.size)
    val expected = ResearcherId.forCluster(
      List(
        AnchorCandidate(2018, WorkId.fromDblpKey("conf/kdd/E1"), 0),
        AnchorCandidate(2016, WorkId.fromDblpKey("conf/kdd/E2"), 0)
      )
    )
    assertEquals(Some(r.clusters(r.rootOf(name(c, "Lu Chen"))).researcherId), expected)
    assertEquals(r.clusters(r.rootOf(name(c, "Lu Chen"))).priorClusters, 2)
    assertEquals(r.clusters(r.rootOf(name(c, "Mo Park"))).priorClusters, 1)
  }

  // ---- Repair M1: rejected and conflict pair decisions are cannot-links --------------------------

  private def cannot(c: Corpus, links: (String, String, Int)*): CannotLinks = {
    val b = new CannotLinks.Builder(c.names.size)
    links.foreach { case (x, y, k) => b.add(name(c, x), name(c, y), k) }
    b.build()
  }
  private def runWith(c: Corpus, cl: CannotLinks, edges: (String, String, Double)*) = {
    val e = new EdgeCollector
    edges.foreach { case (a, b, conf) =>
      e.add(name(c, a), name(c, b), conf, MatchPath.Probabilistic)
    }
    Clustering.run(c, e, cl)
  }
  private lazy val smiths = build(
    List(
      W("conf/kdd/M1", List("Jane Smith", "Xavier Coa")),
      W("conf/kdd/M2", List("J. Smith", "Xavier Coa", "Yvonne Cob")),
      W("conf/kdd/M3", List("John Smith", "Yvonne Cob"))
    )
  )

  test("M1: a chain A ~ B ~ C never places a rejected pair (A, C) in one cluster") {
    val c = smiths
    val cl = cannot(c, ("Jane Smith", "John Smith", CannotLinks.Rejected))
    val edges = List(("Jane Smith", "J. Smith", 0.924), ("J. Smith", "John Smith", 0.924))
    val r = runWith(c, cl, edges*)
    assert(!same(c, r, "Jane Smith", "John Smith"))
    assertEquals(r.mergesApplied, 1)
    assertEquals(r.refused.values.toList, List(Reasons.MergeRejectedPair))
    // without the cannot-link the given-name guard (Phase 11 A-01) still keeps Jane and John
    // apart: same surname, conflicting given names, no direct accepted decision
    val implied = runWith(c, CannotLinks.empty(c.names.size), edges*)
    assert(!same(c, implied, "Jane Smith", "John Smith"))
    assertEquals(implied.refused.values.toList, List(Reasons.MergeGivenNameConflict))
    // deterministic: input order of edges and constraints does not change the result
    val r2 = runWith(c, cl, edges.reverse*)
    assertEquals(r2.rootOf.toList, r.rootOf.toList)
    assertEquals(r2.refused, r.refused)
  }

  test("M1: a conflict pair is a cannot-link with its own reason, and wins over a rejected one") {
    val c = smiths
    val both = cannot(
      c,
      ("John Smith", "Jane Smith", CannotLinks.Rejected),
      ("Jane Smith", "John Smith", CannotLinks.Conflict)
    )
    assertEquals(both.pairs, 1)
    val r = runWith(c, both, ("Jane Smith", "J. Smith", 0.95), ("J. Smith", "John Smith", 0.93))
    assert(same(c, r, "Jane Smith", "J. Smith"))
    assertEquals(r.refused.values.toList, List(Reasons.MergeConflictPair))
    // a direct accepted edge between the two clusters of a rejected pair is refused too
    val direct = runWith(
      c,
      cannot(c, ("Jane Smith", "John Smith", CannotLinks.Rejected)),
      ("Jane Smith", "John Smith", 0.99)
    )
    assert(!same(c, direct, "Jane Smith", "John Smith"))
    assertEquals(direct.refused.values.toList, List(Reasons.MergeRejectedPair))
  }

  test("M1: cannot-links follow merged clusters (small-into-large) through longer chains") {
    val c = build(
      List(
        W("conf/kdd/C1", List("Pat Ark")),
        W("conf/kdd/C2", List("P. Ark")),
        W("conf/kdd/C3", List("Pat Q. Ark")),
        W("conf/kdd/C4", List("Pat R. Ark")),
        W("conf/kdd/C5", List("P. R. Ark"))
      )
    )
    // Pat Ark cannot join P. R. Ark; Pat Q. Ark cannot join Pat R. Ark
    val cl = cannot(
      c,
      ("Pat Ark", "P. R. Ark", CannotLinks.Rejected),
      ("Pat Q. Ark", "Pat R. Ark", CannotLinks.Rejected)
    )
    val r = runWith(
      c,
      cl,
      ("Pat Ark", "P. Ark", 0.99),
      ("P. Ark", "Pat Q. Ark", 0.98),
      ("P. R. Ark", "Pat R. Ark", 0.97),
      ("Pat R. Ark", "P. Ark", 0.96)
    )
    assert(same(c, r, "Pat Ark", "Pat Q. Ark"))
    assert(same(c, r, "P. R. Ark", "Pat R. Ark"))
    assert(!same(c, r, "Pat Ark", "Pat R. Ark"))
    assertEquals(r.refused.values.toList, List(Reasons.MergeRejectedPair))
    // no final cluster holds both names of any cannot-link
    (0 until c.names.size).foreach { n =>
      cl.partnersOf(n).foreach(v => assertNotEquals(r.rootOf(n), r.rootOf(CannotLinks.partner(v))))
    }
  }

  test(
    "M1: cannot-link storage is compact, deduplicated and ignores pairs inside one prior cluster"
  ) {
    val b = new CannotLinks.Builder(5)
    (1 to 3000).foreach(_ => b.add(1, 3, CannotLinks.Rejected))
    b.add(3, 1, CannotLinks.Conflict)
    b.add(2, 2, CannotLinks.Conflict)
    b.add(4, 0, CannotLinks.Rejected)
    val cl = b.build()
    assertEquals(cl.pairs, 2)
    assertEquals(cl.partners.length, 4)
    assertEquals(cl.partnersOf(1).toList, List(CannotLinks.encode(3, CannotLinks.Conflict)))
    assertEquals(cl.partnersOf(2).toList, Nil)
    assertEquals(cl.partnersOf(0).toList, List(CannotLinks.encode(4, CannotLinks.Rejected)))
    val s = new IntOpenSet()
    (0 until 1000).foreach(i => s.add(i * 7))
    (0 until 1000).foreach(i => s.add(i * 7))
    assertEquals(s.size, 1000)
    assert(s.contains(693) && !s.contains(694))
  }

  test("decision 5: precomposed and decomposed source spellings are two dblp-prior clusters") {
    // this file's literals hold one NFC and one NFD spelling of the name
    val c = build(List(W("conf/kdd/N1", List("José Ruiz")), W("conf/kdd/N2", List("José Ruiz"))))
    assertEquals(c.names.size, 2)
  }

  test(
    "decision 5: NFC and NFD spellings are separate dblp-prior clusters, joined only by an accepted edge"
  ) {
    val nfc = "José Ruiz"
    val nfd = "José Ruiz"
    val c = build(List(W("conf/kdd/N1", List(nfc)), W("conf/kdd/N2", List(nfd))))
    assertEquals(c.names.size, 2)
    assertEquals(c.names.spelling.toSet, Set(nfc, nfd))
    // the shared core key still relates them for features and guards
    assertEquals(c.names.parts(name(c, nfc)).fullKey, c.names.parts(name(c, nfd)).fullKey)
    assert(!same(c, run(c), nfc, nfd))
    val joined = run(c, (nfc, nfd, 0.95))
    assert(same(c, joined, nfc, nfd))
    assertEquals(joined.clusters(joined.rootOf(name(c, nfc))).priorClusters, 2)
  }

  test("decision 5: the homonym suffix is part of the exact dblp-prior string") {
    val nfc = "José Ruiz 0001"
    val nfd = "José Ruiz 0001"
    val c = build(
      List(W("conf/kdd/N3", List(nfc)), W("conf/kdd/N4", List(nfd)), W("conf/kdd/N5", List(nfc)))
    )
    assertEquals(c.names.size, 2)
    assertEquals(c.priorClusterMembers(name(c, nfc)).length, 2)
    // joining two spellings of one numbered name is not a distinct-homonym conflict
    val r = run(c, (nfc, nfd, 0.95))
    assert(same(c, r, nfc, nfd))
    assertEquals(r.refused, Map.empty[Long, String])
  }
}
