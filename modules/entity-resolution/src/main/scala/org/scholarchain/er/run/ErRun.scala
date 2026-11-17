package org.scholarchain.er.run

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.mutable

import org.scholarchain.core.{
  CodePointOrder,
  Contract,
  Hashing,
  MatchCandidate,
  MatchCandidateId,
  MatchDecision,
  MatchFeature,
  MatchOutcome,
  MatchPath,
  MatchSubjectKind,
  Provenance,
  RunId,
  SchemaVersions,
  SourceType
}
import org.scholarchain.er.cluster.{CannotLinks, ClusterResult, Clustering, EdgeCollector, PathRank}
import org.scholarchain.er.data.{Corpus, ExportJob, ExportLayout, LoadedInput, Loader}
import org.scholarchain.er.features.PairFeatures
import org.scholarchain.er.io.{
  DataFiles,
  ErError,
  FileDigest,
  HashingLineWriter,
  OutputFile,
  TextOutput
}
import org.scholarchain.er.llm.{
  AdjudicationCache,
  AdjudicationPrompt,
  Adjudicator,
  BandSelection,
  LlmResult,
  LlmStage,
  PairKey
}
import org.scholarchain.er.policy.{
  Assessment,
  DecisionEngine,
  PairFeatureNames,
  Policy,
  Reasons,
  Rules
}
import org.scholarchain.er.review.{QueueSpec, ReviewQueue, SampleInfo, StratumSampler}
import org.scholarchain.er.work.WorkResolution

/** Configuration of one entity-resolution run (decision 17). Paths are not part of the run ID or of
  * any output byte except the manifest's `inputDir`.
  */
final case class ErConfig(
    input: Path,
    outputRoot: Path,
    decisionTime: String,
    seed: Long,
    codeVersion: String,
    llmModelId: String,
    llmMaxPerRun: Int,
    adjudicatorName: String,
    queue: QueueSpec,
    llmCache: Option[Path],
    j04RunDir: Option[Path],
    policy: Policy
)

final case class RunOutcome(runId: String, runDir: Path, reused: Boolean, report: ujson.Value)

/** One entity-resolution run over a J09 graph export.
  *
  * Output layout under `<outputRoot>/run=<32 hex of the run ID>/`:
  *   - `decisions/mention_pair/part-NNNNN.jsonl`: one MatchDecision v2 per MatchCandidate (one part
  *     per input part, input order);
  *   - `decisions/identity_assignment/part-00000.jsonl`: one `mention_researcher` MatchDecision per
  *     subset mention (mention-ID order);
  *   - `clusters/clusters.jsonl`: one summary line per identity cluster (researcher-ID order);
  *   - `work_resolution/decisions.jsonl`, `work_resolution/summary.json`;
  *   - `llm/adjudications.jsonl` (LlmAdjudication, new this run), `llm/rejected-replies.jsonl`;
  *   - `review-queue/review-queue.csv`, `review-queue.jsonl` (label-blind), `review-key.jsonl`,
  *     `strata.json` (hidden: strata, inclusion probabilities, decisions);
  *   - `run-report.json`, `_manifest.json`, `_SUCCESS` (written last).
  *
  * Run ID: `RunId.of("er.resolve", codeVersion, inputFingerprint, configFingerprint)`: the input
  * fingerprint covers the J09 run ID and every input file's path and size, the configuration
  * fingerprint the policy (version and file hash), prompt and schema hashes, decision time, seed,
  * LLM settings, queue settings, LLM cache and J04 conflict inputs. An existing complete run
  * directory is reused only when its manifest matches the recomputed fingerprints, every input file
  * still matches its recorded SHA-256, and every output file still matches its recorded hash
  * ([[Reuse]]); otherwise, or when the directory is incomplete, the run refuses (nothing is
  * overwritten or removed).
  *
  * Limits (repair m5): the run ID trusts the supplied code version (`--code-version`): a code
  * change under an unchanged code version gives the same run ID, and reuse cannot detect it. The
  * procedure therefore stays: every attempt writes to a new, empty output root.
  *
  * Also written: `llm/adjudicator-errors.jsonl` (adjudicator calls that threw: exception class and
  * message SHA-256 only).
  */
object ErRun {
  val JobName = "er.resolve"
  val PairJob = "er.resolve-researchers"
  val AssignJob = "er.assign-identities"
  val LlmJob = "er.llm-adjudication"
  val DatasetId = "scholarchain-er"

  private val UtcInstant = """[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z""".r

  def checkDecisionTime(s: String): Unit =
    if (
      s == null || !UtcInstant.matches(s) ||
      !scala.util.Try(java.time.Instant.parse(s)).toOption.exists(_.toString == s)
    ) throw new ErError(s"decision time '$s' must be a UTC instant yyyy-MM-ddTHH:mm:ssZ")

  private def fingerprint(parts: Seq[String]): String =
    "sha256:" + Hashing.sha256Hex(Hashing.canonicalEncoding(parts))

  def inputFingerprint(job: ExportJob, files: List[(String, Path)]): String =
    fingerprint(Seq("j09-run", job.runId) ++ files.flatMap { case (rel, p) =>
      Seq(rel, Files.size(p).toString)
    })

  def configJson(c: ErConfig): ujson.Obj = {
    val t = c.policy.thresholds
    ujson.Obj(
      "policyVersion" -> c.policy.policyVersion,
      "policySha256" -> c.policy.sha256,
      "policyOverriddenFrom" -> c.policy.overriddenFrom.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "thresholds" -> ujson.Obj(
        "accept" -> t.accept,
        "reject" -> t.reject,
        "llmBandLower" -> t.llmBandLower,
        "llmMinConfidence" -> t.llmMinConfidence,
        "nearThresholdLogit" -> t.nearThresholdLogit
      ),
      "nameKeyVersion" -> c.policy.nameKeyVersion,
      "candidateFeatureSet" -> c.policy.candidateFeatureSet,
      "promptVersion" -> AdjudicationPrompt.v1.version,
      "promptSha256" -> AdjudicationPrompt.v1.sha256,
      "outputSchemaVersion" -> AdjudicationPrompt.OutputSchemaVersion,
      "outputSchemaSha256" -> AdjudicationPrompt.schemaSha256,
      "decisionTime" -> c.decisionTime,
      "seed" -> c.seed.toString,
      "llmModelId" -> c.llmModelId,
      "llmMaxPerRun" -> c.llmMaxPerRun,
      "adjudicator" -> c.adjudicatorName,
      "queueSize" -> c.queue.size,
      "queuePriorPairs" -> c.queue.priorPairs,
      "llmCacheSha256" -> c.llmCache.fold[ujson.Value](ujson.Null)(p =>
        ujson.Str(DataFiles.sha256OfFile(p))
      ),
      "j04Conflicts" -> c.j04RunDir.fold[ujson.Value](ujson.Null) { d =>
        val files = DataFiles.list(d.resolve("conflicts").resolve("v1"))
        ujson.Str(
          fingerprint(files.flatMap(f => Seq(d.relativize(f).toString, DataFiles.sha256OfFile(f))))
        )
      }
    )
  }

  def run(c: ErConfig, adjudicator: Adjudicator, log: String => Unit = println): RunOutcome = {
    checkDecisionTime(c.decisionTime)
    if (c.codeVersion == null || c.codeVersion.trim.isEmpty)
      throw new ErError("code version must not be blank")
    if (adjudicator.modelVersion != c.llmModelId)
      throw new ErError(
        s"adjudicator model ${adjudicator.modelVersion} != configured ${c.llmModelId}"
      )
    val input = c.input.toAbsolutePath.normalize
    val job = ExportLayout.readJob(input)
    val files = ExportLayout.inputFiles(input)
    val inFp = inputFingerprint(job, files)
    val cfgJson = configJson(c)
    val cfgFp = "sha256:" + Hashing.sha256Hex(cfgJson.render())
    val runId =
      RunId.of(JobName, c.codeVersion, inFp, cfgFp).fold(e => throw new ErError(e), identity)
    val runDir =
      c.outputRoot.toAbsolutePath.normalize.resolve("run=" + runId.value.stripPrefix(RunId.Prefix))
    if (Files.exists(runDir)) return Reuse.verify(runDir, runId.value, inFp, cfgFp, log, input)
    log(s"[er] run $runId -> $runDir")
    new Execution(c, adjudicator, input, job, runId, inFp, cfgFp, cfgJson, runDir, log).execute()
  }
}

/** Reuse of a completed output (decision 17): it must match its manifest, or the run refuses.
  *
  * Checked in order: `_SUCCESS` and the manifest exist; run ID, input fingerprint and configuration
  * fingerprint match; every input file the manifest records (`inputFiles`: path relative to the
  * input directory, bytes, sha256) still exists in `input` with the same size and SHA-256, and the
  * input holds no file the manifest does not record (repair m5: the input fingerprint covers only
  * paths and sizes, so content is re-verified here); every output file still matches its recorded
  * size and SHA-256. Re-hashing the inputs costs one read of the export.
  */
object Reuse {
  def verify(
      runDir: Path,
      runId: String,
      inFp: String,
      cfgFp: String,
      log: String => Unit,
      input: Path
  ): RunOutcome = {
    val manifestFile = runDir.resolve(RunManifest.File)
    if (!Files.isRegularFile(runDir.resolve("_SUCCESS")) || !Files.isRegularFile(manifestFile))
      throw new ErError(
        s"refusing to run: $runDir exists but is not a completed run (no _SUCCESS or manifest); use a new, empty output root"
      )
    val m = ujson.read(DataFiles.readUtf8(manifestFile)).obj
    def mismatch(what: String) =
      throw new ErError(
        s"refusing to reuse $runDir: $what does not match its manifest; use a new, empty output root"
      )
    if (m("runId").str != runId) mismatch("run ID")
    if (m("inputFingerprint").str != inFp) mismatch("input fingerprint")
    if (m("configFingerprint").str != cfgFp) mismatch("configuration fingerprint")
    val recorded = m("inputFiles").arr.map(o => o("path").str).toSet
    val present = ExportLayout.inputFiles(input).map(_._1).toSet
    (present -- recorded).toList.sorted.headOption.foreach(p =>
      mismatch(s"input $p (not recorded)")
    )
    m("inputFiles").arr.foreach { o =>
      val f = input.resolve(o("path").str)
      if (!Files.isRegularFile(f)) mismatch(s"input ${o("path").str} (missing)")
      if (Files.size(f) != o("bytes").num.toLong || DataFiles.sha256OfFile(f) != o("sha256").str)
        mismatch(s"input ${o("path").str} (content)")
    }
    m("outputs").arr.foreach { o =>
      val f = runDir.resolve(o("path").str)
      if (!Files.isRegularFile(f)) mismatch(s"output ${o("path").str} (missing)")
      if (Files.size(f) != o("bytes").num.toLong || DataFiles.sha256OfFile(f) != o("sha256").str)
        mismatch(s"output ${o("path").str}")
    }
    log(s"[er] reusing completed run $runId at $runDir (manifest verified)")
    val report = ujson.read(DataFiles.readUtf8(runDir.resolve("run-report.json")))
    RunOutcome(runId, runDir, reused = true, report)
  }
}

private final class Execution(
    c: ErConfig,
    adjudicator: Adjudicator,
    input: Path,
    job: ExportJob,
    runId: RunId,
    inFp: String,
    cfgFp: String,
    cfgJson: ujson.Obj,
    runDir: Path,
    log: String => Unit
) {
  private val policy = c.policy
  private val prompt = AdjudicationPrompt.v1
  private val outputs = mutable.ArrayBuffer.empty[OutputFile]
  private val started = System.nanoTime()
  private def elapsed = f"${(System.nanoTime() - started) / 1e9}%.1fs"

  private def provenance(job: String, sourceIds: List[String]): Provenance =
    Provenance(
      SchemaVersions.currentOf("Provenance"),
      SourceType.Pipeline,
      ErRun.DatasetId,
      this.job.snapshotId,
      sourceIds,
      runId,
      job,
      policy.policyVersion,
      None
    )

  private def candidateFiles: List[Path] = DataFiles.list(input.resolve(ExportLayout.Candidates))

  /** Decodes every candidate, resolving its two mentions; returns the per-file digests. */
  private def forEachCandidate(
      corpus: Corpus
  )(f: (MatchCandidate, Int, Int, Int) => Unit): List[FileDigest] =
    candidateFiles.zipWithIndex.map { case (file, fi) =>
      val label = input.relativize(file).toString
      DataFiles.readLines(file, label) { (line, n) =>
        val cand = Contract
          .decode[MatchCandidate](line)
          .fold(e => throw new ErError(s"$label:$n: ${e.message}"), identity)
        if (cand.subjectKind != MatchSubjectKind.MentionPair)
          throw new ErError(s"$label:$n: subject kind ${cand.subjectKind.wire} is not supported")
        if (cand.provenance.policyVersion.split(';').head != policy.candidateFeatureSet)
          throw new ErError(
            s"$label:$n: candidate feature set '${cand.provenance.policyVersion}' is not ${policy.candidateFeatureSet}"
          )
        cand.features.foreach(x =>
          if (!PairFeatureNames.J06(x.name))
            throw new ErError(s"$label:$n: unknown J06 feature '${x.name}'")
        )
        val a = corpus.mentions.indexOf(cand.left)
        val b = corpus.mentions.indexOf(cand.right)
        if (a < 0 || b < 0)
          throw new ErError(s"$label:$n: candidate ${cand.candidateId} names an unknown mention")
        f(cand, a, b, fi)
      }
    }

  def execute(): RunOutcome = {
    Files.createDirectories(runDir)
    val loaded: LoadedInput = Loader.load(input, job.snapshotId)
    val corpus = loaded.corpus
    log(
      s"[er] loaded ${corpus.mentions.size} mentions, ${corpus.works.size} works, ${corpus.names.size} dblp names ($elapsed)"
    )

    // Pass 1: pair assessments, accepted cluster edges, LLM band selection.
    val edges = new EdgeCollector
    // REJECTED and CONFLICT pair decisions between different dblp-prior clusters (repair M1)
    val cannot = new CannotLinks.Builder(corpus.names.size)
    final case class BandPayload(features: List[MatchFeature], p: Double, sourceIds: List[String])
    val band = new BandSelection[BandPayload](c.llmMaxPerRun)
    val touched = new java.util.BitSet(corpus.mentions.size)
    val perFamily = mutable.TreeMap.empty[String, Long]
    var candidates = 0L
    val pass1 = forEachCandidate(corpus) { (cand, a, b, _) =>
      candidates += 1
      perFamily(cand.blockFamily) = perFamily.getOrElse(cand.blockFamily, 0L) + 1
      touched.set(a); touched.set(b)
      val as = DecisionEngine.assess(corpus, policy, cand.features, a, b)
      if (as.outcome == MatchOutcome.Accepted)
        edges.add(corpus.mentions.name(a), corpus.mentions.name(b), as.confidence, as.path)
      else if (as.outcome == MatchOutcome.Rejected)
        cannot.add(corpus.mentions.name(a), corpus.mentions.name(b), CannotLinks.Rejected)
      else if (as.outcome == MatchOutcome.Conflict)
        cannot.add(corpus.mentions.name(a), corpus.mentions.name(b), CannotLinks.Conflict)
      if (as.inLlmBand)
        band.offer(
          PairKey.of(a, b),
          cand.candidateId.value,
          BandPayload(as.features, as.matchProbability.get, cand.provenance.sourceRecordIds)
        )
    }
    log(
      s"[er] pass 1: $candidates candidates, ${edges.size} cluster edges, ${band.bandCandidates} LLM-band candidates ($elapsed)"
    )

    // LLM adjudication of the selected band pairs.
    val cache = c.llmCache.fold(new AdjudicationCache)(AdjudicationCache.load)
    val selected = band.selected
    val (llm, rejectedReplies, adjudicatorErrors) = LlmStage.run(
      corpus,
      policy,
      selected,
      k => band.payload(k).features,
      k => band.payload(k).p,
      k => provenance(ErRun.LlmJob, band.payload(k).sourceIds),
      adjudicator,
      cache,
      prompt
    )
    llm.foreach { case (k, r) =>
      if (r.outcome == MatchOutcome.Accepted)
        edges.add(
          corpus.mentions.name(PairKey.left(k)),
          corpus.mentions.name(PairKey.right(k)),
          r.confidence,
          MatchPath.Llm
        )
      else if (r.outcome == MatchOutcome.Rejected)
        cannot.add(
          corpus.mentions.name(PairKey.left(k)),
          corpus.mentions.name(PairKey.right(k)),
          CannotLinks.Rejected
        )
    }
    log(
      s"[er] LLM: ${selected.size} pairs selected, ${llm.values.count(!_.fromCache)} sent to the adjudicator, ${llm.values.count(_.fromCache)} answered from the cache ($elapsed)"
    )

    // Clustering.
    val cannotDecisions = cannot.size
    val cannotLinks = cannot.build()
    val clusters = Clustering.run(corpus, edges, cannotLinks)
    log(
      s"[er] clustering: ${cannotLinks.pairs} cannot-link cluster pairs from $cannotDecisions rejected or conflict decisions, ${clusters.clusters.size} clusters, ${clusters.mergesApplied} merges, ${clusters.refused.size} refused ($elapsed)"
    )

    // Pass 2: final decisions, review sampling.
    val sampler = new StratumSampler(c.queue)
    val counts = mutable.TreeMap.empty[(String, String, String), Long]
    val histogram = mutable.TreeMap.empty[(String, Int), Long]
    val refusedPairs = mutable.TreeMap.empty[String, Long]
    // Final REJECTED or CONFLICT pair decisions whose two mentions share one final cluster, by
    // outcome (report field `rejectedOrConflictPairsInsideOneCluster`, meaning unchanged). The
    // total splits into two additive report fields (repair of test-engineer finding 1):
    // - `acrossPriorClusters`: the two mentions carry different dblp names (different dblp-prior
    //   clusters), so the decision was a cannot-link; with repair M1 this must stay empty.
    // - `withinPriorCluster`: the two mentions carry the same dblp name (one dblp-prior cluster,
    //   for example two mentions of one work or two ORCIDs within one dblp profile). These are
    //   expected on a correct run: the prior cluster is kept and flagged, never split (decision 5).
    val insideOneCluster = mutable.TreeMap.empty[String, Long]
    val insideAcrossPrior = mutable.TreeMap.empty[String, Long]
    val insideWithinPrior = mutable.TreeMap.empty[String, Long]
    var bothOrcid = 0L
    var writer: HashingLineWriter = null
    var writerIndex = -1
    val pass2 = forEachCandidate(corpus) { (cand, a, b, fi) =>
      if (fi != writerIndex) {
        if (writer != null) outputs += writer.close()
        writerIndex = fi
        val rel = f"decisions/mention_pair/part-$fi%05d.jsonl"
        writer = new HashingLineWriter(runDir.resolve(rel), rel)
      }
      val as = DecisionEngine.assess(corpus, policy, cand.features, a, b)
      val d = finalDecision(corpus, clusters, llm, as, a, b)
      if (as.features.exists(_.name == PairFeatureNames.OrcidMatch)) bothOrcid += 1
      d.refusal.foreach(r => refusedPairs(r) = refusedPairs.getOrElse(r, 0L) + 1)
      if (
        (d.outcome == MatchOutcome.Rejected || d.outcome == MatchOutcome.Conflict) &&
        clusters.rootOf(corpus.mentions.name(a)) == clusters.rootOf(corpus.mentions.name(b))
      ) {
        val k = d.outcome.wire
        insideOneCluster(k) = insideOneCluster.getOrElse(k, 0L) + 1
        val split =
          if (corpus.mentions.name(a) == corpus.mentions.name(b)) insideWithinPrior
          else insideAcrossPrior
        split(k) = split.getOrElse(k, 0L) + 1
      }
      val decision = MatchDecision(
        SchemaVersions.currentOf("MatchDecision"),
        MatchSubjectKind.MentionPair,
        cand.left,
        cand.right,
        Some(cand.candidateId),
        d.outcome,
        d.path,
        d.confidence,
        as.features,
        d.rules,
        d.reasons,
        if (d.path == MatchPath.Llm) Some(c.llmModelId) else None,
        if (d.path == MatchPath.Llm) Some(prompt.version) else None,
        policy.policyVersion,
        c.decisionTime,
        provenance(ErRun.PairJob, cand.provenance.sourceRecordIds)
      )
      writer.line(
        Contract
          .encode(decision)
          .fold(e => throw new ErError(s"decision ${cand.candidateId}: ${e.message}"), identity)
      )
      val key = (cand.blockFamily, d.outcome.wire, d.path.wire)
      counts(key) = counts.getOrElse(key, 0L) + 1
      as.matchProbability.foreach { p =>
        val bin = math.min(19, (p * 20).toInt)
        histogram((d.outcome.wire, bin)) = histogram.getOrElse((d.outcome.wire, bin), 0L) + 1
      }
      val scored = d.path == MatchPath.Probabilistic || d.path == MatchPath.Llm
      val near = scored && as.nearThreshold
      sampler.observe(
        StratumSampler.stratum(d.path.wire, d.outcome.wire, cand.blockFamily, near),
        cand.candidateId.value,
        a,
        b,
        SampleInfo(
          d.outcome.wire,
          d.path.wire,
          cand.blockFamily,
          as.matchProbability,
          d.confidence,
          near,
          as.features,
          d.rules,
          d.reasons
        )
      )
    }
    if (writer != null) outputs += writer.close()
    if (pass1 != pass2) throw new ErError("candidate files changed between pass 1 and pass 2")
    log(s"[er] pass 2: decisions written ($elapsed)")

    // Identity assignments.
    val assignRel = "decisions/identity_assignment/part-00000.jsonl"
    val aw = new HashingLineWriter(runDir.resolve(assignRel), assignRel)
    val assignCounts = mutable.TreeMap.empty[(String, String), Long]
    var i = 0
    while (i < corpus.mentions.size) {
      val info = clusters.clusterOfMention(corpus, i)
      val merged = info.priorClusters > 1
      val path = if (merged) PathRank.path(info.mergePathRank) else MatchPath.Deterministic
      val outcome = if (info.flags.nonEmpty) MatchOutcome.Conflict else MatchOutcome.Accepted
      val rules = (List(Rules.PriorCluster) ++ (if (merged) List(Rules.ClusterMerged) else Nil) ++
        info.flags.map {
          case Reasons.ClusterMultipleOrcids    => Rules.FlagMultipleOrcids
          case Reasons.ClusterGivenNameConflict => Rules.FlagGivenNameConflict
          case _                                => Rules.FlagSameWork
        }).distinct.sorted(CodePointOrder.ordering)
      val conf = math.min(policy.ruleConfidence(Rules.PriorCluster), info.mergeMinConfidence)
      val d = MatchDecision(
        SchemaVersions.currentOf("MatchDecision"),
        MatchSubjectKind.MentionResearcher,
        corpus.mentions.id(i),
        info.researcherId.value,
        None,
        outcome,
        path,
        conf,
        List(
          MatchFeature("cluster_mentions", info.mentions.toDouble),
          MatchFeature("cluster_prior_clusters", info.priorClusters.toDouble)
        ),
        rules,
        info.flags,
        if (path == MatchPath.Llm) Some(c.llmModelId) else None,
        if (path == MatchPath.Llm) Some(prompt.version) else None,
        policy.policyVersion,
        c.decisionTime,
        provenance(ErRun.AssignJob, List(corpus.works.dblpKey(corpus.mentions.work(i))))
      )
      aw.line(
        Contract
          .encode(d)
          .fold(
            e => throw new ErError(s"assignment ${corpus.mentions.id(i)}: ${e.message}"),
            identity
          )
      )
      val k = (outcome.wire, path.wire)
      assignCounts(k) = assignCounts.getOrElse(k, 0L) + 1
      i += 1
    }
    outputs += aw.close()

    // Cluster summaries.
    val clusterRel = "clusters/clusters.jsonl"
    val cw = new HashingLineWriter(runDir.resolve(clusterRel), clusterRel)
    val priorsByRoot = (0 until corpus.names.size).groupBy(clusters.rootOf(_))
    clusters.clusters.toList.sortBy(_._2.researcherId.value).foreach { case (root, info) =>
      // every exact dblp name string (dblp-prior cluster) the cluster holds, as written
      val names = priorsByRoot(root).map(corpus.names.spelling).sorted(CodePointOrder.ordering)
      cw.line(
        ujson
          .Obj(
            "format" -> "scholarchain.er.cluster",
            "formatVersion" -> 1,
            "researcherId" -> info.researcherId.value,
            "anchorMentionId" -> corpus.mentions.id(info.anchor),
            "mentions" -> info.mentions,
            "priorClusters" -> info.priorClusters,
            "dblpNames" -> ujson.Arr.from(names.map(ujson.Str(_))),
            "mergePath" -> (if (info.priorClusters > 1)
                              ujson.Str(PathRank.path(info.mergePathRank).wire)
                            else ujson.Null),
            "outcome" -> (if (info.flags.isEmpty) "accepted" else "conflict"),
            "reasonCodes" -> ujson.Arr.from(info.flags.map(ujson.Str(_))),
            "policyVersion" -> policy.policyVersion,
            "runId" -> runId.value
          )
          .render()
      )
    }
    outputs += cw.close()

    // Work resolution.
    val j04 = c.j04RunDir.map(WorkResolution.readJ04Conflicts)
    val work = WorkResolution.run(
      corpus,
      loaded.workLinks,
      loaded.linksPresent,
      j04,
      policy.policyVersion,
      c.decisionTime,
      runId.value
    )
    val workRel = "work_resolution/decisions.jsonl"
    val ww = new HashingLineWriter(runDir.resolve(workRel), workRel)
    work.decisions.foreach(o => ww.line(o.render()))
    outputs += ww.close()
    outputs += TextOutput.write(
      runDir,
      "work_resolution/summary.json",
      work.summary.render(indent = 2) + "\n"
    )

    // LLM outputs.
    val lw =
      new HashingLineWriter(runDir.resolve("llm/adjudications.jsonl"), "llm/adjudications.jsonl")
    cache.newEntries.foreach(a =>
      lw.line(Contract.encode(a).fold(e => throw new ErError(e.message), identity))
    )
    outputs += lw.close()
    val rw = new HashingLineWriter(
      runDir.resolve("llm/rejected-replies.jsonl"),
      "llm/rejected-replies.jsonl"
    )
    rejectedReplies.sortBy(r => (r.left, r.right)).foreach { r =>
      rw.line(
        ujson
          .Obj(
            "leftMentionId" -> r.left,
            "rightMentionId" -> r.right,
            "inputHash" -> r.inputHash,
            "reason" -> r.reason,
            "detail" -> r.detail,
            "replySha256" -> r.replySha256
          )
          .render()
      )
    }
    outputs += rw.close()
    val ew = new HashingLineWriter(
      runDir.resolve("llm/adjudicator-errors.jsonl"),
      "llm/adjudicator-errors.jsonl"
    )
    adjudicatorErrors.sortBy(r => (r.left, r.right)).foreach { r =>
      ew.line(
        ujson
          .Obj(
            "leftMentionId" -> r.left,
            "rightMentionId" -> r.right,
            "inputHash" -> r.inputHash,
            "reason" -> "adjudicator-error",
            "errorClass" -> r.errorClass,
            "messageSha256" -> r.messageSha256.fold[ujson.Value](ujson.Null)(ujson.Str(_))
          )
          .render()
      )
    }
    outputs += ew.close()

    // Review queue.
    val built =
      ReviewQueue.build(corpus, c.queue, sampler, (a, b) => priorInfo(corpus, clusters, a, b))
    outputs += TextOutput.write(
      runDir,
      "review-queue/review-queue.csv",
      ReviewQueue.csv(corpus, built.rows)
    )
    outputs += TextOutput.write(
      runDir,
      "review-queue/review-queue.jsonl",
      ReviewQueue.jsonl(corpus, built.rows)
    )
    outputs += TextOutput.write(
      runDir,
      "review-queue/review-key.jsonl",
      ReviewQueue.key(
        corpus,
        built.rows,
        i => clusters.clusterOfMention(corpus, i).researcherId.value
      )
    )
    outputs += TextOutput.write(
      runDir,
      "review-queue/strata.json",
      ReviewQueue.strataJson(c.queue, built)
    )

    // Report.
    val report = Report.build(
      corpus,
      loaded,
      job,
      candidates,
      perFamily.toMap,
      touched.cardinality,
      counts.toMap,
      histogram.toMap,
      assignCounts.toMap,
      clusters,
      refusedPairs.toMap,
      bothOrcid,
      band.bandCandidates,
      selected.size,
      llm,
      rejectedReplies.size,
      adjudicatorErrors.size,
      ujson.Obj(
        "cannotLinkDecisions" -> ujson.Num(cannotDecisions.toDouble),
        "cannotLinkClusterPairs" -> cannotLinks.pairs,
        "rejectedOrConflictPairsInsideOneCluster" -> ujson.Obj.from(
          insideOneCluster.toList.map { case (k, v) => k -> ujson.Num(v.toDouble) }
        ),
        // the two parts of rejectedOrConflictPairsInsideOneCluster (see the pass 2 comment)
        "rejectedOrConflictPairsInsideOneClusterSplit" -> ujson.Obj(
          "acrossPriorClusters" -> ujson.Obj.from(
            insideAcrossPrior.toList.map { case (k, v) => k -> ujson.Num(v.toDouble) }
          ),
          "withinPriorCluster" -> ujson.Obj.from(
            insideWithinPrior.toList.map { case (k, v) => k -> ujson.Num(v.toDouble) }
          )
        )
      ),
      cache,
      work.summary,
      built,
      policy
    )
    outputs += TextOutput.write(runDir, "run-report.json", report.render(indent = 2) + "\n")

    // Manifest, then the success marker.
    val manifest = RunManifest.build(
      c,
      job,
      runId.value,
      input,
      inFp,
      cfgFp,
      cfgJson,
      loaded.digests ++ pass1,
      outputs.toList
    )
    Files.write(
      runDir.resolve(RunManifest.File),
      (manifest.render(indent = 2) + "\n").getBytes(StandardCharsets.UTF_8)
    )
    Files.write(runDir.resolve("_SUCCESS"), Array.emptyByteArray)
    log(s"[er] done ($elapsed)")
    RunOutcome(runId.value, runDir, reused = false, report)
  }

  private final case class Final(
      outcome: MatchOutcome,
      path: MatchPath,
      confidence: Double,
      rules: List[String],
      reasons: List[String],
      refusal: Option[String]
  )

  private def finalDecision(
      corpus: Corpus,
      clusters: ClusterResult,
      llm: Map[Long, LlmResult],
      as: Assessment,
      a: Int,
      b: Int
  ): Final = {
    def sorted(xs: List[String]) = xs.distinct.sorted(CodePointOrder.ordering)
    val base =
      if (!as.inLlmBand)
        Final(as.outcome, as.path, as.confidence, as.rulesFired, as.reasonCodes, None)
      else
        llm.get(PairKey.of(a, b)) match {
          case Some(r) =>
            Final(
              r.outcome,
              MatchPath.Llm,
              r.confidence,
              sorted(as.rulesFired :+ Rules.LlmAdjudicated),
              sorted(
                r.reasonCodes ++ (if (r.outcome == MatchOutcome.Unresolved) as.reasonCodes else Nil)
              ),
              None
            )
          case None =>
            Final(
              as.outcome,
              as.path,
              as.confidence,
              as.rulesFired,
              sorted(as.reasonCodes :+ Reasons.LlmCap),
              None
            )
        }
    val na = corpus.mentions.name(a)
    val nb = corpus.mentions.name(b)
    if (
      base.outcome != MatchOutcome.Accepted || na == nb || clusters
        .rootOf(na) == clusters.rootOf(nb)
    ) base
    else {
      val reason = clusters.refused.getOrElse(
        EdgeCollector.key(na, nb),
        throw new ErError(
          s"internal: accepted pair ${corpus.mentions.id(a)} / ${corpus.mentions.id(b)} was neither merged nor refused"
        )
      )
      val (rule, outcome) = reason match {
        case Reasons.MergeOrcid           => (Rules.GuardOrcid, MatchOutcome.Conflict)
        case Reasons.MergeSameWork        => (Rules.GuardSameWork, MatchOutcome.Conflict)
        case Reasons.MergeDistinctHomonym => (Rules.GuardDistinctHomonym, MatchOutcome.Conflict)
        case Reasons.MergeDisambiguation  => (Rules.GuardDisambiguation, MatchOutcome.Unresolved)
        case Reasons.MergeConflictPair    => (Rules.GuardConflictPair, MatchOutcome.Conflict)
        case Reasons.MergeRejectedPair    => (Rules.GuardRejectedPair, MatchOutcome.Unresolved)
        // implied cannot-link (finding A-01), recorded under the rejected-pair guard rule
        case Reasons.MergeGivenNameConflict => (Rules.GuardRejectedPair, MatchOutcome.Unresolved)
        case other => throw new ErError(s"internal: unknown merge refusal '$other'")
      }
      // Cluster guards declare path "inherited" in the policy: the refused decision keeps the path,
      // confidence and rules of the pair decision whose accepted evidence was refused.
      Final(
        outcome,
        base.path,
        base.confidence,
        sorted(base.rules :+ rule),
        sorted(base.reasons :+ reason),
        Some(reason)
      )
    }
  }

  /** The decision recorded in the review key for a dblp-prior pair (no candidate): the identity
    * assignment of its cluster, with the entity-resolution features of the pair.
    */
  private def priorInfo(corpus: Corpus, clusters: ClusterResult, a: Int, b: Int): SampleInfo = {
    val info = clusters.clusterOfMention(corpus, a)
    val ev = PairFeatures.compute(corpus, policy, Nil, a, b)
    SampleInfo(
      if (info.flags.isEmpty) "accepted" else "conflict",
      "deterministic",
      StratumSampler.PriorFamily,
      None,
      policy.ruleConfidence(Rules.PriorCluster),
      false,
      ev.features,
      List(Rules.PriorCluster),
      info.flags
    )
  }
}
