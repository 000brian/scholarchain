package org.scholarchain.hadoop

import java.nio.file.Files

import org.apache.hadoop.fs.Path
import org.scholarchain.core.{
  CoauthorPairCandidate,
  Contract,
  MentionId,
  PairPosition,
  PublicationTeam
}
import org.scholarchain.hadoop.common.{Counters, Disposition}
import org.scholarchain.hadoop.pairs.{PairConsolidateJob, PairExpandJob}
import org.scholarchain.hadoop.pipeline.DblpChain
import org.scholarchain.hadoop.teams.{MentionContext, PublicationTeamJob}
import upickle.default.read

class DblpChainSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private def pairs(conf: org.apache.hadoop.conf.Configuration, r: common.JobResult, dir: String) =
    TestSupport.lines(conf, r, dir).map(l => Contract.decode[CoauthorPairCandidate](l).toOption.get)

  test("J07 and J08 on the real sample: n(n-1)/2 pairs per team, provenance on every pair") {
    val work = TestSupport.tempDir("chain-real")
    val conf = TestSupport.localConf(work)
    val r = DblpChain.run(
      conf,
      TestSupport.sample("dblp/dblp-sample.xml"),
      new Path(work.resolve("out").toUri),
      "2026-11-01"
    )
    val teams = TestSupport
      .lines(conf, r.teams, PublicationTeamJob.Teams)
      .map(l => Contract.decode[PublicationTeam](l).toOption.get)
    assertEquals(teams.map(_.teamSize).sorted, List(3, 3, 4, 7))
    val expected = teams.map(t => t.teamSize.toLong * (t.teamSize - 1) / 2).sum
    assertEquals(expected, 33L)
    assertEquals(r.expand.main(Counters.PairInstances), 33L)
    assertEquals(r.expand.main(Counters.EmittedPair), 33L)
    val instances = pairs(conf, r.expand, PairExpandJob.Instances)
    assertEquals(instances.size, 33)
    instances.foreach { p =>
      val team = teams.find(_.workId == p.workId).get
      val lp = team.authorNames.indexOf(p.left)
      val rp = team.authorNames.indexOf(p.right)
      // v2: positions carry the author positions; provenance names only the source record.
      assertEquals(p.provenance.sourceRecordIds, team.provenance.sourceRecordIds)
      assertEquals(
        p.positions,
        List(PairPosition(lp, MentionId.of(p.workId, lp), rp, MentionId.of(p.workId, rp)))
      )
      assertEquals(p.year, team.year)
      assertEquals(p.provenance.datasetId, "dblp")
      assertEquals(p.provenance.snapshotId, "2026-11-01")
      assertEquals(p.provenance.runId, r.expand.runId)
    }
    assertEquals(pairs(conf, r.consolidate, PairConsolidateJob.Pairs).size, 33)
    val contexts =
      TestSupport.lines(conf, r.teams, PublicationTeamJob.Contexts).map(read[MentionContext](_))
    assertEquals(contexts.size, 17)
    assertEquals(r.teams.stats("maxTeamSize").value, 7L)
    assertEquals(r.expand.stats("largestPairExpansion").value, 21L)
  }

  test(
    "synthetic edge cases: duplicate author yields a self pair; 7 distinct pairs after consolidation"
  ) {
    val work = TestSupport.tempDir("chain-synth")
    val conf = TestSupport.localConf(work)
    val r = DblpChain.run(
      conf,
      TestSupport.sample("dblp/dblp-synthetic-edge-cases.xml"),
      new Path(work.resolve("out").toUri),
      "synthetic-2026-11-12"
    )
    assertEquals(r.extract.main(Counters.Accepted), 3L)
    assertEquals(r.extract.counter(Counters.ExcludedReasonGroup, "year"), 1L)
    assertEquals(r.extract.counter(Counters.ExcludedReasonGroup, "publtype"), 1L)
    assertEquals(r.extract.counter(Counters.IssueGroup, "invalid_orcid_attr"), 1L)
    assertEquals(r.expand.main(Counters.PairInstances), 10L + 1L)
    assertEquals(r.expand.main(Counters.SelfPairs), 1L)
    assertEquals(r.expand.main(Counters.SingleAuthorTeams), 1L)
    val consolidated = pairs(conf, r.consolidate, PairConsolidateJob.Pairs)
    assertEquals(consolidated.size, 7)
    assertEquals(r.consolidate.main(Counters.Duplicate), 3L)
    val dupe = consolidated
      .find(p => p.left == "Synthetic Author 0001" && p.right == "Synthetic Author 0002")
      .get
    // names at positions 0, 1, 4 are 0001, 0002, 0001: one consolidated pair, both positions
    val dw = dupe.workId
    assertEquals(
      dupe.positions,
      List(
        PairPosition(0, MentionId.of(dw, 0), 1, MentionId.of(dw, 1)),
        PairPosition(4, MentionId.of(dw, 4), 1, MentionId.of(dw, 1))
      )
    )
    assertEquals(dupe.provenance.sourceRecordIds, List("conf/sigir/SyntheticA24"))
    // J08b writes the consolidated record, so it carries the J08b run, not the J08a one
    consolidated.foreach { p =>
      assertEquals(p.provenance.runId, r.consolidate.runId)
      assertEquals(p.provenance.jobName, PairConsolidateJob.JobName)
      assertEquals(p.provenance.jobName, "hadoop.j08b-pair-consolidate")
    }
    assertNotEquals(dupe.provenance.runId, r.expand.runId)
    val instance = pairs(conf, r.expand, PairExpandJob.Instances)
      .find(p => p.workId == dw && p.left == dupe.left && p.right == dupe.right)
      .get
    // source facts are kept from the instances
    assertEquals(dupe.provenance.sourceType, instance.provenance.sourceType)
    assertEquals(dupe.provenance.contentHash, instance.provenance.contentHash)
    assertEquals(dupe.provenance.datasetId, instance.provenance.datasetId)
    assertEquals(dupe.provenance.snapshotId, instance.provenance.snapshotId)
    assertEquals(dupe.provenance.policyVersion, instance.provenance.policyVersion)
    // every consolidated pair is a valid v2 record with sorted, distinct positions
    consolidated.foreach(p => assertEquals(Contract.validate(p), Nil))
    val issues = TestSupport.lines(conf, r.expand, "issues/v1").map(read[Disposition](_))
    assertEquals(issues.map(_.reason), List("self_pair"))
  }

  test("_job.json records the upstream run IDs in plain form (inputRuns)") {
    val work = TestSupport.tempDir("chain-lineage")
    val conf = TestSupport.localConf(work)
    val r = DblpChain.run(
      conf,
      TestSupport.sample("dblp/dblp-synthetic-edge-cases.xml"),
      new Path(work.resolve("out").toUri),
      "s"
    )
    def inputRuns(j: common.JobResult): List[String] =
      ujson
        .read(Files.readString(java.nio.file.Paths.get(j.dir("_job.json").toUri)))("inputRuns")
        .arr
        .map(_.str)
        .toList
    assertEquals(inputRuns(r.extract), Nil)
    assertEquals(inputRuns(r.teams), List(r.extract.runId.value))
    assertEquals(inputRuns(r.expand), List(r.teams.runId.value))
    assertEquals(inputRuns(r.consolidate), List(r.expand.runId.value))
  }

  test("a changed DTD file content (same path) changes the J01 input fingerprint and run ID") {
    val work = TestSupport.tempDir("chain-dtd")
    val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<dblp>\n" +
      "<article key=\"conf/sigir/Dtd20\"><author>J&eacute; One</author><author>B Two</author>" +
      "<title>T.</title><year>2020</year></article>\n</dblp>\n"
    val in = TestSupport.writeFile(work, "in/d.xml", xml)
    val dtd = work.resolve("dtd/dblp.dtd")
    TestSupport.writeFile(work, "dtd/dblp.dtd", "<!ENTITY eacute \"&#233;\">\n")
    val conf = TestSupport.localConf(work, "scholarchain.dblp.dtdPath" -> dtd.toString)
    val out = new Path(work.resolve("out").toUri)
    val a = DblpChain.run(conf, in, out, "d")
    assertEquals(a.extract.main(Counters.Accepted), 1L)
    TestSupport.writeFile(work, "dtd/dblp.dtd", "<!ENTITY eacute \"&#201;\">\n")
    val b = DblpChain.run(conf, in, out, "d")
    assertNotEquals(b.extract.runId, a.extract.runId)
    assert(!b.extract.reused, "a changed DTD must not reuse the old J01 output")
    // also with an explicitly supplied raw fingerprint
    val c = DblpChain.run(conf, in, out, "d", Some("sha256:given"))
    TestSupport.writeFile(work, "dtd/dblp.dtd", "<!ENTITY eacute \"&#233;\">\n")
    val d = DblpChain.run(conf, in, out, "d", Some("sha256:given"))
    assertNotEquals(c.extract.runId, d.extract.runId)
  }

  test("path-forming snapshot IDs are validated before anything is written") {
    val work = TestSupport.tempDir("chain-escape")
    val conf = TestSupport.localConf(work)
    val root = work.resolve("out/inner")
    List("../../x", "..", ".", "a/b", "").foreach { bad =>
      intercept[IllegalArgumentException](
        DblpChain.run(
          conf,
          TestSupport.sample("dblp/dblp-synthetic-edge-cases.xml"),
          new Path(root.toUri),
          bad
        )
      )
    }
    assert(!Files.exists(work.resolve("out")), "nothing may be created for an invalid snapshot")
  }

  test("reruns are idempotent and outputs are byte-identical across output roots") {
    val work = TestSupport.tempDir("chain-det")
    val conf = TestSupport.localConf(work)
    val in = TestSupport.sample("dblp/dblp-synthetic-edge-cases.xml")
    val a = DblpChain.run(conf, in, new Path(work.resolve("a").toUri), "s")
    val again = DblpChain.run(conf, in, new Path(work.resolve("a").toUri), "s")
    assert(again.all.forall(_.reused))
    val b = DblpChain.run(conf, in, new Path(work.resolve("b").toUri), "s")
    assertEquals(a.all.map(_.runId), b.all.map(_.runId))
    a.all.zip(b.all).foreach { case (x, y) =>
      val fx = TestFiles.partFiles(x.outputDir)
      val fy = TestFiles.partFiles(y.outputDir)
      assertEquals(fx.keySet, fy.keySet)
      fx.foreach { case (rel, bytes) => assert(java.util.Arrays.equals(bytes, fy(rel)), rel) }
    }
  }

  test("giant teams: counted, sharded across reducers, n(n-1)/2 pairs; hard limit rejects") {
    val work = TestSupport.tempDir("chain-giant")
    val n = 120
    val authors = (1 to n).map(i => f"<author>Giant Member $i%04d</author>").mkString
    val small =
      "<author>Wei Wang 0001</author><author>Wei Wang 0002</author><author>Wei Wang</author>"
    val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<dblp>\n" +
      s"<article key=\"conf/sigir/Giant20\">$authors<title>Big.</title><year>2020</year></article>\n" +
      s"<article key=\"conf/sigir/Small20\">$small<title>Small.</title><year>2020</year></article>\n</dblp>\n"
    val in = TestSupport.writeFile(work, "in/giant.xml", xml)
    val conf = TestSupport.localConf(
      work,
      "scholarchain.pairs.pairsPerShard" -> "500",
      "scholarchain.blocking.maxBlockSize" -> "2"
    )
    val r = DblpChain.run(conf, in, new Path(work.resolve("out").toUri), "g")
    assertEquals(r.teams.main(Counters.OversizeTeam), 1L)
    assertEquals(TestSupport.lines(conf, r.teams, PublicationTeamJob.Oversize).size, 1)
    val expected = n.toLong * (n - 1) / 2 + 3L
    assertEquals(r.expand.main(Counters.PairShards), (n.toLong * (n - 1) / 2 + 499) / 500 + 1)
    assertEquals(r.expand.main(Counters.PairInstances), expected)
    assertEquals(r.expand.main(Counters.EmittedPair), expected)
    val parts = TestFiles.partFiles(r.expand.outputDir).keys.filter(_.startsWith("pairInstances/"))
    assert(parts.size > 1, s"shards of one team should land on several reducers: $parts")
    assertEquals(r.consolidate.main(Counters.EmittedPair), expected)
    assertEquals(r.expand.stats("largestPairExpansion").value, n.toLong * (n - 1) / 2)

    val blocks = common.JobRunner.run(
      conf,
      org.scholarchain.hadoop.blocking.BlockingJob.spec(
        r.teams.dir(PublicationTeamJob.Contexts),
        new Path(work.resolve("out").toUri),
        "g",
        common.JobRunner.fingerprintRuns(List(r.teams.runId))
      )
    )
    // "Giant Member" (120 homonym-numbered mentions) and "Wei Wang" (3) both exceed the limit 2
    assertEquals(blocks.main(Counters.OversizeBlock), 2L)
    assertEquals(blocks.stats("largestBlock").value, 120L)
    assertEquals(blocks.stats("largestBlock").subject, "dblp-base-name\tGiant Member")
    assertEquals(TestSupport.lines(conf, blocks, "blocks"), Nil)

    val conf2 = TestSupport.localConf(work, "scholarchain.teams.hardLimit" -> "100")
    val r2 = DblpChain.run(conf2, in, new Path(work.resolve("out2").toUri), "g")
    val rejects = TestSupport.lines(conf2, r2.teams, "rejects/v1").map(read[Disposition](_))
    assertEquals(
      rejects.map(d => d.sourceRecordId -> d.reason),
      List(Some("conf/sigir/Giant20") -> "team_exceeds_hard_limit")
    )
    assertEquals(r2.expand.main(Counters.PairInstances), 3L)
  }
}

object TestFiles {

  /** Relative path -> bytes of every data file (part-*) under a job output directory. */
  def partFiles(dir: Path): Map[String, Array[Byte]] = {
    val root = java.nio.file.Paths.get(dir.toUri)
    val stream = Files.walk(root)
    try
      stream.toArray.toList
        .map(_.asInstanceOf[java.nio.file.Path])
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.startsWith("part-"))
        .map(p => root.relativize(p).toString -> Files.readAllBytes(p))
        .toMap
    finally stream.close()
  }
}
