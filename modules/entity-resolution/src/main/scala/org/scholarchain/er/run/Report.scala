package org.scholarchain.er.run

import java.nio.file.Path

import org.scholarchain.core.{AuthorNameKey, MatchOutcome}
import org.scholarchain.er.cluster.ClusterResult
import org.scholarchain.er.data.{Corpus, ExportJob, LoadedInput}
import org.scholarchain.er.io.{FileDigest, OutputFile}
import org.scholarchain.er.llm.{AdjudicationCache, AdjudicationPrompt, LlmResult}
import org.scholarchain.er.policy.Policy
import org.scholarchain.er.review.ReviewQueue

/** The machine-readable run report (`run-report.json`). Deterministic: no wall-clock values. */
object Report {

  private def num(x: Long): ujson.Value = ujson.Num(x.toDouble)

  private def obj(m: Iterable[(String, Long)]): ujson.Obj =
    ujson.Obj.from(m.toList.sortBy(_._1).map { case (k, v) => k -> num(v) })

  def build(
      corpus: Corpus,
      loaded: LoadedInput,
      job: ExportJob,
      candidates: Long,
      perFamily: Map[String, Long],
      mentionsWithCandidates: Int,
      counts: Map[(String, String, String), Long],
      histogram: Map[(String, Int), Long],
      assignCounts: Map[(String, String), Long],
      clusters: ClusterResult,
      refusedPairs: Map[String, Long],
      bothOrcidPairs: Long,
      bandCandidates: Long,
      llmPairs: Int,
      llm: Map[Long, LlmResult],
      rejectedReplies: Int,
      adjudicatorErrors: Int,
      cannotLinks: ujson.Obj,
      cache: AdjudicationCache,
      workSummary: ujson.Obj,
      queue: ReviewQueue.Built,
      policy: Policy
  ): ujson.Obj = {
    def sumBy(f: ((String, String, String)) => String) =
      counts.groupMapReduce { case (k, _) => f(k) } { case (_, v) => v }(_ + _)
    val total = counts.values.sum
    val unresolved = sumBy(_._2).getOrElse(MatchOutcome.Unresolved.wire, 0L)
    val cl = clusters.clusters.values
    val largest = cl.maxByOption(c => (c.mentions, c.researcherId.value))
    val mentionsWithOrcid =
      (0 until corpus.mentions.size).count(i => corpus.mentions.orcids(i).nonEmpty)
    val mentionLinks = (0 until corpus.mentions.size).count(i => corpus.mentions.links(i).nonEmpty)
    ujson.Obj(
      "format" -> "scholarchain.er.run-report",
      "formatVersion" -> 1,
      "policyVersion" -> policy.policyVersion,
      "inputRun" -> job.runId,
      "inputs" -> ujson.Obj(
        "works" -> corpus.works.size,
        "authorMentions" -> corpus.mentions.size,
        "dblpNames" -> corpus.names.size,
        "venues" -> corpus.venueNames.size,
        "matchCandidates" -> num(candidates),
        "matchCandidatesPerFamily" -> obj(perFamily),
        "mentionsWithCandidates" -> mentionsWithCandidates,
        "mentionsWithoutCandidates" -> (corpus.mentions.size - mentionsWithCandidates),
        "externalIdLinkDirectoryPresent" -> loaded.linksPresent
      ),
      "exactIdentifiers" -> ujson.Obj(
        "mentionsWithOrcid" -> mentionsWithOrcid,
        "mentionsWithJ04Links" -> mentionLinks,
        "candidatesWithOrcidOnBothMentions" -> num(bothOrcidPairs),
        "openAlexAuthorAndAffiliation" -> (if (loaded.linksPresent) "from J04 mention links"
                                           else
                                             "absent: the input has no external_id_link directory (no enrichment data)"),
        "semanticScholarId" -> "not applicable: Semantic Scholar is not a ScholarChain dataset",
        "citationNeighborhood" -> "not applicable: no citation data is ingested"
      ),
      "pairDecisions" -> ujson.Obj(
        "total" -> num(total),
        "byOutcome" -> obj(sumBy(_._2)),
        "byPath" -> obj(sumBy(_._3)),
        "byOutcomeAndPath" -> obj(sumBy(k => s"${k._2}/${k._3}")),
        "byFamilyAndOutcome" -> obj(sumBy(k => s"${k._1}/${k._2}")),
        "unresolvedRate" -> (if (total == 0) 0.0 else unresolved.toDouble / total),
        "mergeRefusalsByReason" -> obj(refusedPairs)
      ),
      "llm" -> ujson.Obj(
        "promptVersion" -> AdjudicationPrompt.Version,
        "outputSchemaVersion" -> AdjudicationPrompt.OutputSchemaVersion,
        "bandCandidates" -> num(bandCandidates),
        "pairsSelected" -> llmPairs,
        "pairsSentToAdjudicator" -> llm.values.count(!_.fromCache),
        "pairsAnsweredFromCache" -> llm.values.count(_.fromCache),
        "outcomes" -> obj(llm.values.groupMapReduce(_.outcome.wire)(_ => 1L)(_ + _)),
        "reasons" -> obj(
          llm.values.flatMap(_.reasonCodes).groupMapReduce(identity)(_ => 1L)(_ + _)
        ),
        "cacheHits" -> num(cache.hits),
        "cacheMisses" -> num(cache.misses),
        "rejectedReplies" -> rejectedReplies,
        "adjudicatorErrors" -> adjudicatorErrors
      ),
      "identityAssignments" -> obj(assignCounts.map { case ((o, p), v) => s"$o/$p" -> v }),
      "clusters" -> ujson.Obj(
        "dblpPriorClusters" -> corpus.names.size,
        "total" -> cl.size,
        "mergedBeyondDblpPrior" -> cl.count(_.priorClusters > 1),
        "mentionsInMergedClusters" -> cl
          .filter(_.priorClusters > 1)
          .map(_.mentions.toLong)
          .sum
          .toDouble,
        "conflictFlagged" -> cl.count(_.flags.nonEmpty),
        "conflictFlagsByReason" -> obj(
          cl.flatMap(_.flags).groupMapReduce(identity)(_ => 1L)(_ + _)
        ),
        "mergesApplied" -> clusters.mergesApplied,
        "mergesRefused" -> clusters.refused.size,
        "mergesRefusedByReason" -> obj(
          clusters.refused.values.groupMapReduce(identity)(_ => 1L)(_ + _)
        ),
        "cannotLinks" -> cannotLinks,
        "largest" -> largest.fold[ujson.Value](ujson.Null)(c =>
          ujson.Obj(
            "researcherId" -> c.researcherId.value,
            "mentions" -> c.mentions,
            "priorClusters" -> c.priorClusters,
            "anchorName" -> corpus.mentionName(c.anchor)
          )
        )
      ),
      "calibration" -> ujson.Obj(
        "description" -> "Label-free distribution of the scored match probability (probabilistic and LLM-band pairs), 20 bins of width 0.05, by final outcome. Reporting only; never used to set er-v1.",
        "histogram" -> obj(histogram.map { case ((o, b), v) => f"$o/${b * 0.05}%.2f" -> v })
      ),
      "workResolution" -> workSummary,
      "reviewQueue" -> ujson.Obj(
        "rows" -> queue.rows.size,
        "dblpPriorPairPopulation" -> num(queue.priorPopulation),
        "strata" -> queue.strata.size
      ),
      "knownRecallGaps" -> knownRecallGaps(corpus)
    )
  }

  /** Recall gaps that the blocking design causes and the weights cannot fix (repair m-new-3).
    * Reporting only: whether to add a blocking family is a human decision.
    */
  def knownRecallGaps(corpus: Corpus): ujson.Arr = {
    val (groups, names) = middleInitialSplit(corpus.names.spelling.toList)
    ujson.Arr(
      ujson.Obj(
        "id" -> "blocking.middle-name-variants",
        "description" -> "Middle-name and middle-initial variants are never compared: the initials-surname key keeps every given-name initial (John A. Smith -> j a smith, John Smith -> j smith) and the dblp-base-name key keeps the full base name, so two spellings that differ only by a middle name or middle initial share no block, get no MatchCandidate and stay in their own dblp-prior clusters. This is a recall gap of the blocking (author-name-key/1 and the configured families), not of the er-v1 weights; adding a blocking family (for example first initial plus surname) is a human decision.",
        "measurement" -> "dblp names of this input grouped by first given-name initial plus surname (author-name-key/1 initials-surname key without its middle initials); a group holding two or more different initials-surname keys contains middle-name variants that no block pairs",
        "firstInitialSurnameGroupsSplitByMiddleInitials" -> groups,
        "dblpNamesInThoseGroups" -> names
      )
    )
  }

  /** (groups with two or more initials-surname keys, dblp names in those groups) over `spellings`,
    * grouped by first initial plus surname. Names with a single token have no given name and are
    * left out.
    */
  def middleInitialSplit(spellings: List[String]): (Int, Int) = {
    val keyed = spellings.flatMap { s =>
      AuthorNameKey.initialsSurnameKey(s).map(_.split(' ').toList).collect {
        case ks if ks.size >= 2 => ((ks.head, ks.last), ks.mkString(" "), s)
      }
    }
    val split = keyed.groupBy(_._1).values.filter(_.map(_._2).distinct.size >= 2)
    (split.size, split.map(_.map(_._3).distinct.size).sum)
  }
}

/** The run manifest (`_manifest.json`, format `scholarchain.er.run-manifest` version 1). */
object RunManifest {
  val File = "_manifest.json"

  def build(
      c: ErConfig,
      job: ExportJob,
      runId: String,
      input: Path,
      inFp: String,
      cfgFp: String,
      cfgJson: ujson.Obj,
      inputs: List[FileDigest],
      outputs: List[OutputFile]
  ): ujson.Obj =
    ujson.Obj(
      "format" -> "scholarchain.er.run-manifest",
      "formatVersion" -> 1,
      "jobName" -> ErRun.JobName,
      "runId" -> runId,
      "codeVersion" -> c.codeVersion,
      "policyVersion" -> c.policy.policyVersion,
      "decisionTime" -> c.decisionTime,
      "seed" -> c.seed.toString,
      "inputDir" -> input.toString,
      "inputRun" -> ujson.Obj(
        "jobName" -> job.jobName,
        "runId" -> job.runId,
        "snapshotId" -> job.snapshotId,
        "codeVersion" -> job.codeVersion,
        "inputRuns" -> ujson.Arr.from(job.inputRuns.map(ujson.Str(_)))
      ),
      "inputRuns" -> ujson.Arr.from((job.runId :: job.inputRuns).distinct.sorted.map(ujson.Str(_))),
      "inputFingerprint" -> inFp,
      "configFingerprint" -> cfgFp,
      "config" -> cfgJson,
      "inputFiles" -> ujson.Arr.from(inputs.sortBy(_.path).map { d =>
        ujson.Obj("path" -> d.path, "bytes" -> d.bytes.toDouble, "sha256" -> d.sha256)
      }),
      "outputs" -> ujson.Arr.from(outputs.sortBy(_.path).map { o =>
        ujson.Obj(
          "path" -> o.path,
          "bytes" -> o.bytes.toDouble,
          "sha256" -> o.sha256,
          "records" -> o.records.toDouble
        )
      })
    )
}
