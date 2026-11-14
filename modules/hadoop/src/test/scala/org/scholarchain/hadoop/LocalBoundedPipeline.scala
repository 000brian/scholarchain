package org.scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import org.apache.hadoop.fs.Path
import org.scholarchain.hadoop.common.{Counters, JobResult}
import org.scholarchain.hadoop.pipeline.{
  Pipeline,
  PipelineResult,
  PipelineSpec,
  ScholarChainHadoop,
  SourceInput
}
import org.scholarchain.hadoop.sources.SourceNormalizers

/** The local bounded pipeline over the Phase 3 samples (LocalJobRunner, local file system).
  *
  * Run: `sbt "hadoop/Test/runMain org.scholarchain.hadoop.LocalBoundedPipeline <output dir>"`. Two
  * runs: the real samples (dblp release 2026-11-01 plus the OpenAlex, Crossref and ORCID API
  * projections retrieved 2026-11-12) and the synthetic dblp edge cases (own snapshot label, no
  * enrichment). Prints and writes `<output dir>/local-run-report.json`.
  */
object LocalBoundedPipeline {

  val RetrievalDate = "2026-11-12"

  def realSpec(out: Path): PipelineSpec = PipelineSpec(
    TestSupport.sample("dblp/dblp-sample.xml"),
    "2026-11-01",
    List(
      SourceInput(
        SourceNormalizers.OpenAlexWork,
        "openalex",
        RetrievalDate,
        TestSupport.sample("openalex/works.jsonl")
      ),
      SourceInput(
        SourceNormalizers.OpenAlexAuthor,
        "openalex",
        RetrievalDate,
        TestSupport.sample("openalex/authors.jsonl")
      ),
      SourceInput(
        SourceNormalizers.CrossrefWork,
        "crossref",
        RetrievalDate,
        TestSupport.sample("crossref/works.jsonl")
      ),
      SourceInput(
        SourceNormalizers.OrcidRecord,
        "orcid",
        RetrievalDate,
        TestSupport.sample("orcid/records.jsonl")
      )
    ),
    new Path(out, "real")
  )

  def syntheticSpec(out: Path): PipelineSpec = PipelineSpec(
    TestSupport.sample("dblp/dblp-synthetic-edge-cases.xml"),
    "synthetic-2026-11-12",
    Nil,
    new Path(out, "synthetic")
  )

  def summary(name: String, r: PipelineResult): ujson.Obj = {
    def stat(j: JobResult, s: String) =
      j.stats
        .get(s)
        .map(x => ujson.Obj("value" -> x.value.toDouble, "subject" -> x.subject))
        .getOrElse(ujson.Null)
    ujson.Obj(
      "run" -> name,
      "maximumObservedTeamSize" -> stat(r.dblp.teams, "maxTeamSize"),
      "largestPairExpansion" -> stat(r.dblp.expand, "largestPairExpansion"),
      "largestBlockingGroup" -> stat(r.blocking, "largestBlock"),
      "largestCandidateBlockSeen" -> stat(r.candidates, "largestBlockSeen"),
      "largestCandidateGroup" -> stat(r.candidates, "largestCandidateGroup"),
      "matchCandidates" -> r.candidates.main(Counters.CandidatesEmitted).toDouble,
      "candidateOversizeBlocks" -> r.candidates.main(Counters.OversizeBlock).toDouble,
      "largestIdentifierGroup" -> stat(r.join, "largestIdentifierGroup"),
      "pairInstancesGenerated" -> r.dblp.expand.main(Counters.PairInstances).toDouble,
      "consolidatedPairs" -> r.dblp.consolidate.main(Counters.EmittedPair).toDouble,
      "links" -> r.join.main(Counters.LinksEmitted).toDouble,
      "jobs" -> ScholarChainHadoop.report(r.all)
    )
  }

  def main(args: Array[String]): Unit = {
    val outDir = Paths
      .get(
        args.headOption.getOrElse(
          TestSupport.repoRoot.resolve("modules/hadoop/target/local-bounded-run").toString
        )
      )
      .toAbsolutePath
    Files.createDirectories(outDir)
    val conf = TestSupport.localConf(outDir.resolve("work"))
    val out = new Path(outDir.toUri)
    val real = Pipeline.run(conf, realSpec(out))
    val synthetic = Pipeline.run(conf, syntheticSpec(out))
    val report = ujson.Arr(summary("real-samples", real), summary("synthetic-dblp", synthetic))
    val text = report.render(indent = 2)
    Files.write(outDir.resolve("local-run-report.json"), text.getBytes(StandardCharsets.UTF_8))
    println(text)
  }
}
