package org.scholarchain.hadoop

import org.apache.hadoop.fs.Path
import org.scholarchain.core.{Contract, ExternalIdLink, Institution}
import org.scholarchain.hadoop.common.{Counters, Disposition, JobRunner}
import org.scholarchain.hadoop.pipeline.{Pipeline, SourceInput}
import org.scholarchain.hadoop.sources.{NormalizeJob, SourceNormalizers}
import upickle.default.read

class PipelineSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  test("bounded pipeline over the Phase 3 samples: links, export layout, determinism") {
    val work = TestSupport.tempDir("pipeline")
    val conf = TestSupport.localConf(work)
    val a = Pipeline.run(conf, LocalBoundedPipeline.realSpec(new Path(work.resolve("a").toUri)))
    // every job's map-stage accounting identity
    a.all.foreach { r =>
      assertEquals(
        r.main(Counters.Input),
        r.main(Counters.Accepted) + r.main(Counters.Excluded) + r.main(Counters.Rejected),
        r.jobName
      )
    }
    assertEquals(a.dblp.expand.main(Counters.PairInstances), 33L)
    // the real sample has only singleton name blocks: J05 leaves an empty blocks/ directory and
    // J06 runs on it with zero input and zero candidates
    assert(java.nio.file.Files.isDirectory(java.nio.file.Paths.get(a.blocking.dir("blocks").toUri)))
    assertEquals(a.blocking.main(Counters.BlocksEmitted), 0L)
    assertEquals(a.candidates.main(Counters.Input), 0L)
    assertEquals(a.candidates.main(Counters.CandidatesEmitted), 0L)
    assertEquals(a.join.main(Counters.LinksEmitted), 67L)
    assertEquals(a.join.main(Counters.UnmatchedKeys), 1L) // the arXiv DOI listed by ORCID
    val links = TestSupport
      .lines(conf, a.join, "links")
      .map(l => Contract.decode[ExternalIdLink](l).toOption.get)
    assertEquals(
      links.count(l => l.idType.wire == "openalex_author"),
      11
    ) // one OpenAlex author ID is null
    val exportDir = java.nio.file.Paths.get(a.graphExport.outputDir.toUri)
    val exported = TestFiles.partFiles(a.graphExport.outputDir).keySet.map(_.split('/').head)
    assertEquals(
      exported,
      Set(
        "work",
        "author_mention",
        "publication_team",
        "coauthor_pair_candidate",
        "external_id_link",
        "institution",
        "venue"
      )
    )
    assert(java.nio.file.Files.isDirectory(exportDir.resolve("work/year=2023")))
    assertEquals(
      a.graphExport
        .main(Counters.Rejected) + a.graphExport.counter(Counters.Group, Counters.RejectedReduce),
      0L
    )
    val exportedLinks =
      JobRunner.readPartLines(conf, new Path(a.graphExport.outputDir, "external_id_link"))
    assertEquals(exportedLinks.size, 64) // 4 copies of the venue link merged into one
    val insts = JobRunner
      .readPartLines(conf, new Path(a.graphExport.outputDir, "institution"))
      .map(l => Contract.decode[Institution](l).toOption.get)
    assertEquals(insts.map(_.institutionId).distinct.size, insts.size)

    val b = Pipeline.run(conf, LocalBoundedPipeline.realSpec(new Path(work.resolve("b").toUri)))
    assertEquals(a.all.map(_.runId), b.all.map(_.runId))
    a.all.zip(b.all).foreach { case (x, y) =>
      val fx = TestFiles.partFiles(x.outputDir)
      val fy = TestFiles.partFiles(y.outputDir)
      assertEquals(fx.keySet, fy.keySet, x.jobName)
      fx.foreach { case (rel, bytes) =>
        assert(java.util.Arrays.equals(bytes, fy(rel)), s"${x.jobName} $rel")
      }
    }
  }

  test("J02 rejects malformed, oversize and conflicting source lines and counts them") {
    val work = TestSupport.tempDir("j02-bad")
    val conf = TestSupport.localConf(work, "scholarchain.normalize.maxLineBytes" -> "300")
    val body = List(
      """{"id":"https://openalex.org/W1","doi":"https://doi.org/10.1/a"}""",
      """{"id":"https://openalex.org/W1","doi":"https://doi.org/10.1/a"}""",
      """{"id":"https://openalex.org/W2","doi":"https://doi.org/10.1/b"}""",
      """{"id":"https://openalex.org/W2","doi":"https://doi.org/10.1/c"}""",
      """{"id": broken""",
      "",
      """{"id":"https://openalex.org/W3","title":"""" + ("x" * 400) + "\"}",
      """{"id":"https://openalex.org/A5"}"""
    ).mkString("\n") + "\n"
    val in = TestSupport.writeFile(work, "in/works.jsonl", body)
    val r = JobRunner.run(
      conf,
      NormalizeJob.spec(
        SourceNormalizers.OpenAlexWork,
        in,
        new Path(work.resolve("out").toUri),
        "openalex",
        "2026-11-12",
        JobRunner.fingerprintFiles(conf, List(in))
      )
    )
    assertEquals(r.main(Counters.Input), 8L)
    assertEquals(r.main(Counters.Accepted) + r.main(Counters.Rejected), 8L)
    val rejects =
      TestSupport.lines(conf, r, "rejects/v1").map(read[Disposition](_)).map(_.reason).sorted
    assertEquals(
      rejects,
      List(
        "blank_line",
        "duplicate_key_conflict",
        "duplicate_key_conflict",
        "malformed_json",
        "oversize_line",
        "unsupported_record"
      )
    )
    assertEquals(r.main(Counters.Duplicate), 1L)
    assertEquals(r.main(Counters.Conflict), 2L)
    assertEquals(TestSupport.lines(conf, r, NormalizeJob.Normalized).size, 1)
  }

  test("snapshot label is path-safe and order-independent") {
    val s = LocalBoundedPipeline.realSpec(new Path("/tmp/x"))
    assertEquals(
      Pipeline.snapshotLabel(s),
      "dblp-2026-11-01+crossref-2026-11-12+openalex-2026-11-12+orcid-2026-11-12"
    )
    assertEquals(
      Pipeline.snapshotLabel(s.copy(sources = s.sources.reverse)),
      Pipeline.snapshotLabel(s)
    )
    intercept[IllegalArgumentException](
      SourceInput(SourceNormalizers.OrcidRecord, "orcid", "a,b", new Path("/x"))
    )
  }

  test("every path-forming identifier is validated: no escape from the output root") {
    val s = LocalBoundedPipeline.realSpec(new Path("/tmp/x"))
    List("../../x", "..", ".", "a/b", "", "x y").foreach { bad =>
      intercept[IllegalArgumentException](s.copy(dblpSnapshot = bad))
      intercept[IllegalArgumentException](
        SourceInput(SourceNormalizers.OrcidRecord, bad, "2026-11-12", new Path("/x"))
      )
      intercept[IllegalArgumentException](
        SourceInput(SourceNormalizers.OrcidRecord, "orcid", bad, new Path("/x"))
      )
    }
    // the command-line driver refuses it before any job runs
    val work = TestSupport.tempDir("cli-escape")
    val tool = new org.scholarchain.hadoop.pipeline.ScholarChainHadoop
    tool.setConf(TestSupport.localConf(work))
    intercept[IllegalArgumentException](
      tool.run(
        Array(
          "--output",
          work.resolve("out").toString,
          "--dblp",
          TestSupport.sample("dblp/dblp-synthetic-edge-cases.xml").toString,
          "--dblp-snapshot",
          "../../x"
        )
      )
    )
    assert(!java.nio.file.Files.exists(work.resolve("out")))
  }
}
