package org.scholarchain.rag.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

import org.scholarchain.core._
import org.scholarchain.core.config.{ConfigLoader, LlmProvider, ScholarChainConfig}
import org.scholarchain.rag.embedding.{EmbeddingProviders, InputType}
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.rag.semantic.ExportDocuments
import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.local.{LocalRuntimeEnvironment, LocalRuntimeOptions}
import org.scholarchain.storage.source.{ExportManifest, LocalDirectorySource, RejectLog}

/** Runs the frozen benchmark questions through [[QueryWorkflow]] on the local runtime stores.
  *
  * {{{
  * sbt -batch -J-Xmx12g "rag/runMain org.scholarchain.rag.runtime.BenchmarkRunner --config FILE --export DIR --neptune-csv DIR --questions FILE --out DIR --llm fake|anthropic"
  * }}}
  *
  * It loads storage's `LocalRuntimeEnvironment` (graph from the Neptune CSV files, tables from the
  * export), renders and ingests only the researcher-profile, community and path documents into the
  * in-memory vector index with the `fake` embedder (no paper index locally), runs every question
  * one at a time, and writes `run-report.json` plus one `q-NN.json` per question. Rules: `--out`
  * must not exist; `--llm` must equal the configuration's `llm.provider`; `--llm anthropic` refuses
  * to start unless the environment variable named by `llm.apiKeyEnvVar` is set (its value is never
  * read here beyond that check, and never written); a step over 30 minutes or an out-of-memory
  * error stops the run. Every report is labelled "local stores, fake embeddings".
  */
object BenchmarkRunner {
  val Label = "local stores, fake embeddings"
  val MaxStepMillis: Long = 30L * 60 * 1000
  val SemanticLabel = "fake lexical embedder, not semantic"

  final class StopRun(msg: String) extends RuntimeException(msg)

  final case class Options(
      config: Path,
      exportDir: Path,
      neptuneCsv: Path,
      questions: Path,
      out: Path,
      llm: String
  )

  def parse(args: Array[String]): Either[String, Options] =
    if (args.length % 2 != 0) Left("arguments must be --key value pairs")
    else {
      val m = args.grouped(2).map(a => a(0) -> a(1)).toMap
      val known = Set("--config", "--export", "--neptune-csv", "--questions", "--out", "--llm")
      def req(k: String) = m.get(k).filter(_.trim.nonEmpty).toRight(s"$k is required")
      for {
        _ <- Either.cond(
          (m.keySet -- known).isEmpty,
          (),
          s"unknown arguments: ${(m.keySet -- known).toList.sorted.mkString(", ")}"
        )
        c <- req("--config")
        e <- req("--export")
        n <- req("--neptune-csv")
        q <- req("--questions")
        o <- req("--out")
        l <- req("--llm").filterOrElse(
          Set("fake", "anthropic").contains,
          "--llm must be fake or anthropic"
        )
      } yield Options(
        Paths.get(c).toAbsolutePath,
        Paths.get(e).toAbsolutePath,
        Paths.get(n).toAbsolutePath,
        Paths.get(q).toAbsolutePath,
        Paths.get(o).toAbsolutePath,
        l
      )
    }

  def loadConfig(path: Path): Either[String, ScholarChainConfig] = {
    val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    ConfigLoader.load(text, path.getFileName.toString, Map.empty).left.map(_.message).map(_.config)
  }

  /** Startup checks that need no store: provider agreement and, for anthropic, the key variable. */
  def preflight(
      config: ScholarChainConfig,
      llm: String,
      env: String => Option[String]
  ): Either[String, Unit] =
    for {
      s <- config.llm.toRight("llm section is not configured")
      p <- s.providerKind
      _ <- Either.cond(
        p.wire == llm,
        (),
        s"--llm $llm does not match llm.provider ${p.wire} of the configuration"
      )
      _ <- Either.cond(p != LlmProvider.Bedrock, (), "the benchmark runs fake or anthropic only")
      _ <-
        if (p != LlmProvider.Anthropic) Right(())
        else
          s.apiKeyEnvVar
            .toRight("llm.apiKeyEnvVar is required for anthropic")
            .flatMap(n =>
              Either.cond(
                env(n).exists(_.trim.nonEmpty),
                (),
                s"environment variable $n is not set; refusing to start"
              )
            )
      e <- config.embedding.toRight("embedding section is not configured")
      _ <- Either.cond(e.provider == "fake", (), "the local benchmark uses the fake embedder only")
    } yield ()

  def main(args: Array[String]): Unit = {
    val opts = parse(args).fold(e => sys.error(e), identity)
    val report = run(opts)
    println(s"[benchmark] ${report("status").str}; report: ${opts.out.resolve("run-report.json")}")
    if (!report("status").str.startsWith("completed")) sys.exit(1)
  }

  def run(
      opts: Options,
      env: String => Option[String] = sys.env.get,
      llmWiring: LlmFactory.Wiring = LlmFactory.Wiring()
  ): ujson.Obj = {
    if (Files.exists(opts.out)) sys.error(s"${opts.out} exists; every attempt uses a new root")
    Files.createDirectories(opts.out)
    val report = ujson.Obj(
      "format" -> "scholarchain.rag.benchmark-run-report",
      "formatVersion" -> 1,
      "label" -> Label
    )
    val steps = ujson.Obj()
    report("steps") = steps
    def write(): Unit =
      Files.write(
        opts.out.resolve("run-report.json"),
        report.render(indent = 2).getBytes(StandardCharsets.UTF_8)
      )
    def step[A](name: String)(f: => A): A = {
      val t0 = System.nanoTime()
      println(s"[benchmark] step $name started")
      val a = f
      val ms = (System.nanoTime() - t0) / 1000000L
      steps(name) = ms.toDouble
      println(s"[benchmark] step $name finished in $ms ms")
      if (ms > MaxStepMillis) throw new StopRun(s"step $name took $ms ms (> 30 minutes)")
      a
    }
    val t0 = System.nanoTime()
    var runtime: LocalRuntimeEnvironment = null
    try {
      val config = loadConfig(opts.config).fold(e => sys.error(e), identity)
      preflight(config, opts.llm, env).fold(e => sys.error(e), identity)
      val cfg = StorageConfig.from(config).fold(e => sys.error(e.mkString("; ")), identity)
      val questionsBytes = Files.readAllBytes(opts.questions)
      val bench = ujson.read(new String(questionsBytes, StandardCharsets.UTF_8))
      val manifestSha =
        Hashing.sha256Hex(Files.readAllBytes(opts.exportDir.resolve(ExportManifest.FileName)))
      report("input") = ujson.Obj(
        "configFile" -> opts.config.getFileName.toString,
        "configFingerprint" -> config.fingerprint,
        "exportManifestSha256" -> manifestSha,
        "questionsFile" -> opts.questions.getFileName.toString,
        "questionsSha256" -> Hashing.sha256Hex(questionsBytes),
        "llm" -> opts.llm
      )
      write()

      runtime = step("load-runtime-environment") {
        LocalRuntimeEnvironment
          .load(
            cfg,
            opts.exportDir,
            opts.neptuneCsv,
            LocalRuntimeOptions(rejectFile = Some(opts.out.resolve("load-rejects.jsonl")))
          )
          .fold(e => sys.error(e), identity)
      }
      report("runtimeEnvironment") = runtime.report.toJson
      write()

      val doc =
        EmbeddingProviders.create(config, InputType.Document).fold(e => sys.error(e), identity)
      val query =
        EmbeddingProviders.create(config, InputType.Query).fold(e => sys.error(e), identity)
      val src = new LocalDirectorySource(opts.exportDir)
      val manifest = src.manifest()
      val ingest = step("render-and-ingest-summaries") {
        val rejects = new RejectLog(Some(opts.out.resolve("render-rejects.jsonl")))
        try {
          val corpus =
            new ExportDocuments(src, manifest, cfg.vectors, rejects).renderSparkSummaries()
          val runId = RunId
            .of(
              "rag.benchmark-ingest",
              QueryWorkflow.CodeVersion,
              s"sha256:$manifestSha",
              config.fingerprint
            )
            .fold(e => sys.error(e), identity)
          val s = runtime.pipeline(doc.port, "dblp").open(runId, "benchmark summaries")
          s.ingest(corpus.documents.iterator)
          val r = s.finish()
          ujson.Obj(
            "documents" -> corpus.documents.size.toDouble,
            "documentsPerIndex" -> ujson.Obj.from(
              corpus.documents.groupMapReduce(_.indexName)(_ => 1L)(_ + _).toList.sorted.map {
                case (k, v) => k -> ujson.Num(v.toDouble)
              }
            ),
            "renderRejects" -> rejects.total.toDouble,
            "ingest" -> r.toJson,
            "embedding" -> doc.spec.id,
            "note" -> SemanticLabel
          )
        } finally rejects.close()
      }
      report("semanticIngest") = ingest
      write()

      val ctx = RuntimeContext
        .from(config, manifest.sourceRunId, runtime.report.graphWindowLabels.toSet)
        .fold(e => sys.error(e), identity)
      val prompts = Prompts.locate().flatMap(Prompts.loadSet).fold(e => sys.error(e), identity)
      val rule = new RulePlanner(ctx.windows, ctx.bounds)
      val model =
        LlmFactory.create(config, rule, llmWiring.copy(env = env)).fold(e => sys.error(e), identity)
      val retriever = new SemanticRetriever(query.port, query.spec, runtime.vectors, cfg.vectors)
      val workflow = new QueryWorkflow(
        runtime,
        ctx,
        model,
        prompts,
        Some(retriever),
        cfg.vectors.collaborationPathIndex
      )
      report("versions") = ujson.Obj(
        "prompts" -> ujson.Arr.from(prompts.versions.map(ujson.Str(_))),
        "schemas" -> ujson.Arr.from(Prompts.SchemaIds.map(ujson.Str(_))),
        "model" -> model.modelId,
        "provider" -> model.provider.wire,
        "codeVersion" -> QueryWorkflow.CodeVersion
      )

      val questions = bench("questions").arr.toList
      val results = questions.zipWithIndex.map { case (q, i) =>
        val id = q("id").str
        step(s"question-$id") {
          val out = workflow.run(QueryRequest(q("question").str))
          val eval = BenchmarkEvaluation.evaluate(q, out, ctx)
          Files.write(
            opts.out.resolve(f"q-${i + 1}%02d.json"),
            BenchmarkEvaluation
              .resultJson(q, out, eval)
              .render(indent = 2)
              .getBytes(StandardCharsets.UTF_8)
          )
          (q, out, eval)
        }
      }
      val semantic = step("semantic-recall") {
        BenchmarkEvaluation.semanticRecall(
          bench.obj.get("semanticQueries").map(_.arr.toList).getOrElse(Nil),
          retriever,
          cfg.vectors
        )
      }
      val planted =
        step("planted-errors")(BenchmarkEvaluation.plantedErrors(results.flatMap(_._2.toOption)))
      report("results") = BenchmarkEvaluation.summarize(results, semantic, planted)
      report("llmUsage") = model.usage.toJson
      report("status") = "completed"
    } catch {
      case e: StopRun          => report("status") = s"stopped: ${e.getMessage}"
      case e: OutOfMemoryError => report("status") = s"stopped: out of memory (${e.getMessage})"
      case NonFatal(e) => report("status") = s"failed: ${e.getClass.getSimpleName}: ${e.getMessage}"
    } finally {
      if (runtime != null) runtime.close()
      report("totalMillis") = ((System.nanoTime() - t0) / 1000000L).toDouble
      write()
    }
    report
  }
}

/** Compares workflow outputs with the frozen answer key (which comes from the export tables, never
  * from this code).
  */
object BenchmarkEvaluation {
  private def items(r: WorkflowResult) = r.response.bundle.map(_.items).getOrElse(Nil)
  private def close(a: Double, b: Double) = math.abs(a - b) <= 1e-9 * math.max(1.0, math.abs(b))
  private def strs(v: ujson.Value): List[String] = v.arr.toList.map(_.str)
  private def opt(o: ujson.Obj, k: String): Option[ujson.Value] =
    o.value.get(k).filter(_ != ujson.Null)

  final case class Eval(json: ujson.Obj)

  /** Per-question comparison: plan fields, identity, abstention and every expected value. */
  def evaluate(q: ujson.Value, out: Either[String, WorkflowResult], ctx: RuntimeContext): Eval =
    out match {
      case Left(e)  => Eval(ujson.Obj("workflowError" -> e))
      case Right(r) =>
        val exp = q("expected").obj
        val step = r.plannedSteps.headOption
        val ops = r.draft.operations.map(_.wire).toSet
        val effWindow = r.plannedSteps.headOption
          .map(s =>
            if (s.operation == QueryOperation.TemporalComparison) "temporal" else s.windowLabel
          )
          .orElse(Some(r.draft.windowLabel.getOrElse(ctx.runtime.defaultWindowLabel)))
        val expWindow = opt(exp, "windowLabel").map(_.str)
        val expTemporal = opt(exp, "comparisonWindowLabels").map(strs)
        val planChecks = ujson.Obj(
          "operations" -> (ops == strs(exp("operations")).toSet),
          "window" -> (expTemporal match {
            case Some(ws) =>
              r.draft.comparisonWindowLabels.toSet == ws.toSet || (r.draft.comparisonWindowLabels.isEmpty && ws.toSet == ctx.windowLabels
                .filterNot(_ == ctx.fullWindowLabel)
                .toSet)
            case None =>
              expWindow.forall(w =>
                effWindow.contains(w) || r.draft.windowLabel.contains(
                  w
                ) || (r.draft.windowLabel.isEmpty && w == ctx.runtime.defaultWindowLabel)
              )
          }),
          "metric" -> opt(exp, "rankingMetric").forall(m =>
            r.draft.rankingMetric.map(_.wire).contains(m.str)
          ),
          "limit" -> opt(exp, "limit").forall(l =>
            r.draft.limit.getOrElse(QueryWorkflow.DefaultLimit) == l.num.toInt
          ),
          "unsupported" -> opt(exp, "unsupported").forall(u =>
            strs(u).toSet.subsetOf(r.draft.unsupportedConstraints.map(_.kind).toSet)
          )
        )
        val expIdentity = opt(exp, "identity").map(_.str).getOrElse("none")
        val gotIdentity =
          if (r.resolver.resolutions.isEmpty) "none"
          else if (r.resolver.resolutions.exists(_.isInstanceOf[Resolution.Ambiguous])) "ambiguous"
          else if (
            r.resolver.resolutions
              .exists(x => x.isInstanceOf[Resolution.Unknown] || x.isInstanceOf[Resolution.Failed])
          ) "unknown"
          else "resolved"
        val expIds = opt(exp, "researcherIds").map(strs).getOrElse(Nil)
        val gotIds = r.resolver.resolved.map(_.candidate.ref.id.value)
        val identityOk =
          expIdentity == gotIdentity && (expIdentity != "resolved" || expIds.toSet == gotIds.toSet)
        val silentChoice = expIdentity == "ambiguous" && r.resolver.resolved.nonEmpty
        val values = opt(exp, "values").map(_.arr.toList).getOrElse(Nil)
        val valueChecks = values.map(v => checkValue(v.obj, items(r)))
        val pathCheck = opt(exp, "path").map(p => checkPath(p.obj, items(r)))
        val claims = r.response.claims
        Eval(
          ujson.Obj(
            "plan" -> planChecks,
            "planAllCorrect" -> planChecks.value.values.forall(_ == ujson.True),
            "identityExpected" -> expIdentity,
            "identityGot" -> gotIdentity,
            "identityCorrect" -> identityOk,
            "silentChoiceOnAmbiguousName" -> silentChoice,
            "abstainExpected" -> opt(exp, "abstain").exists(_.bool),
            "abstained" -> r.response.abstained,
            "values" -> ujson.Arr.from(valueChecks),
            "valuesExpected" -> valueChecks.size,
            "valuesMatched" -> valueChecks.count(_("matched").bool),
            "path" -> pathCheck.getOrElse(ujson.Null),
            "claimCounts" -> ujson.Obj.from(
              VerificationStatus.values.map(s =>
                s.wire -> ujson.Num(claims.count(_.status == s).toDouble)
              )
            ),
            "planSource" -> r.planSource.wire,
            "step" -> step
              .map(s => ujson.Str(s"${s.operation.wire}@${s.windowLabel}"))
              .getOrElse(ujson.Null)
          )
        )
    }

  private def num(i: EvidenceItem, f: String) = EvidenceValues.numbers(i).get(f)
  private def pair(i: EvidenceItem, k: String) = EvidenceValues.pairs(i).get(k)

  /** One expected value against the bundle's graph evidence. */
  def checkValue(v: collection.Map[String, ujson.Value], items: List[EvidenceItem]): ujson.Obj = {
    val kind = v("kind").str
    val found: Option[(Boolean, String)] = kind match {
      case "metric" | "ranking" | "group" =>
        val rid = v("researcherId").str
        val metric = v("metric").str
        val wl = v("windowLabel").str
        items
          .find(i =>
            i.evidenceType == EvidenceType.Metric && i.subjectIds.headOption.contains(
              rid
            ) && i.metric.exists(_.metric.wire == metric) && pair(i, "windowLabel").contains(wl) &&
              (kind != "ranking" || num(i, "rank").contains(v("rank").num))
          )
          .map { i =>
            val valueOk = i.metric.exists(m => close(m.value, v("value").num))
            val groupOk = v.get("groupId").forall(g => pair(i, "groupId").contains(g.str))
            (valueOk && groupOk, s"stored ${i.metric.map(m => Fact.fmt(m.value)).getOrElse("-")}")
          }
      case "work" =>
        items
          .find(i =>
            i.evidenceType == EvidenceType.Work && i.subjectIds.headOption.contains(v("workId").str)
          )
          .map { i =>
            val ok = num(i, "year").contains(v("year").num) && num(i, "teamSize").contains(
              v("teamSize").num
            ) && pair(i, "title").contains(v("title").str)
            (
              ok,
              s"year ${num(i, "year").map(Fact.fmt).getOrElse("-")}, team ${num(i, "teamSize").map(Fact.fmt).getOrElse("-")}"
            )
          }
      case "edge" =>
        val ab = Set(v("a").str, v("b").str)
        items
          .find(i =>
            i.evidenceType == EvidenceType.CoauthorEdge && pair(i, "found").isEmpty && Set(
              pair(i, "researcherA"),
              pair(i, "researcherB")
            ).flatten == ab && pair(i, "windowLabel").contains(v("windowLabel").str)
          )
          .map { i =>
            val ok = num(i, "jointPublicationCount").contains(
              v("jointPublicationCount").num
            ) && num(i, "weight").exists(w =>
              close(
                w,
                BigDecimal(v("weight").num).setScale(4, BigDecimal.RoundingMode.HALF_UP).toDouble
              )
            )
            (ok, s"jpc ${num(i, "jointPublicationCount").map(Fact.fmt).getOrElse("-")}")
          }
      case "topicRanked" =>
        val pool = v("pool").obj
        val wl = v("windowLabel").str
        val metric = v("metric").str
        val ranked = items.filter(i =>
          i.evidenceType == EvidenceType.Metric && num(i, "rank").isDefined && i.metric.exists(
            _.metric.wire == metric
          ) && pair(i, "windowLabel").contains(wl)
        )
        val inPool = ranked.filter(i => pool.contains(i.subjectIds.head))
        val ok =
          inPool.forall(i => i.metric.exists(m => close(m.value, pool(i.subjectIds.head).num)))
        Some(
          (
            ranked.nonEmpty && ok,
            s"${ranked.size} ranked, ${inPool.size} in the key pool, ${ranked.size - inPool.size} outside the pool (semantic matching, not checked)"
          )
        )
      case _ => None
    }
    ujson.Obj(
      "expected" -> ujson.Obj.from(v.toList),
      "matched" -> found.exists(_._1),
      "detail" -> found.map(x => ujson.Str(x._2)).getOrElse(ujson.Str("not in the evidence bundle"))
    )
  }

  def checkPath(p: collection.Map[String, ujson.Value], items: List[EvidenceItem]): ujson.Obj = {
    val paths = items.filter(_.evidenceType == EvidenceType.GraphPath)
    p.get("noPathWithinDepth") match {
      case Some(d) =>
        val ok = paths.exists(i =>
          pair(i, "found").contains("false") && pair(i, "complete").contains("true") && num(
            i,
            "maxDepth"
          ).contains(d.num)
        )
        ujson.Obj("expected" -> s"no path within depth ${d.num.toInt}", "matched" -> ok)
      case None =>
        val hops = p("hops").num
        val ids = p.get("researcherIds").map(strs).getOrElse(Nil)
        val found = paths.find(i => pair(i, "found").isEmpty)
        val gotHops = found.flatMap(num(_, "hops"))
        val gotIds = found.toList.flatMap(_.subjectIds.filter(ResearcherId.isWellFormed))
        val idsOk = ids.isEmpty || gotIds == ids || gotIds == ids.reverse
        ujson.Obj(
          "expectedHops" -> hops,
          "gotHops" -> gotHops.map(ujson.Num(_)).getOrElse(ujson.Null),
          "hopsMatch" -> gotHops.contains(hops),
          "sameResearchersAsSpark" -> idsOk,
          "origin" -> found.flatMap(pair(_, "origin")).map(ujson.Str(_)).getOrElse(ujson.Null),
          "matched" -> (gotHops.contains(hops) && idsOk)
        )
    }
  }

  def resultJson(q: ujson.Value, out: Either[String, WorkflowResult], e: Eval): ujson.Obj = {
    val base = ujson.Obj(
      "id" -> q("id"),
      "category" -> q("category"),
      "question" -> q("question"),
      "evaluation" -> e.json
    )
    out.foreach { r =>
      base("queryRunId") = r.queryRunId.value
      base("planDraft") = r.draft.toJson
      base("planSource") = r.planSource.wire
      base("response") =
        ujson.read(Contract.encode(r.response).fold(err => sys.error(err.message), identity))
      base("audit") =
        ujson.read(Contract.encode(r.audit).fold(err => sys.error(err.message), identity))
      base("verification") = ujson.Arr.from(
        r.verified.map(v =>
          ujson.Obj(
            "claimId" -> v.claim.claimId,
            "status" -> v.status.wire,
            "reasons" -> ujson.Arr.from(v.reasons.map(ujson.Str(_)))
          )
        )
      )
      base("modelAttempts") = ujson.Arr.from(r.attempts.map(_.toJson))
      base("stageMillis") = ujson.Obj.from(r.stageMillis.map { case (k, v) =>
        k -> ujson.Num(v.toDouble)
      })
      base("bundleWrite") = r.bundleWrite
        .map(
          _.fold(
            ujson.Str(_),
            w =>
              ujson.Obj(
                "inserted" -> w.itemsInserted,
                "unchanged" -> w.itemsUnchanged,
                "conflicts" -> w.itemConflicts.size,
                "bytes" -> w.encodedBytes
              )
          )
        )
        .getOrElse(ujson.Null)
      base("auditWrite") = r.auditWrite.fold(
        ujson.Str(_),
        w => ujson.Obj("attempt" -> w.attempt, "bytes" -> w.encodedBytes)
      )
    }
    base
  }

  /** Recall@10 of known-neighbour queries, each within its own window. */
  def semanticRecall(
      queries: List[ujson.Value],
      retriever: SemanticRetriever,
      vectors: org.scholarchain.core.config.VectorSettings
  ): ujson.Obj = {
    val byType = queries.groupBy(_("documentType").str)
    ujson.Obj.from(byType.toList.sortBy(_._1).map { case (t, qs) =>
      val dt = SemanticDocumentType.fromWire(t).toOption.get
      val hits = qs.map { q =>
        val f = for {
          s <- q.obj.get("windowStartYear"); e <- q.obj.get("windowEndYear")
        } yield SemanticRetriever.windowFilter(s.num.toInt, e.num.toInt)
        retriever
          .retrieve(dt, q("query").str, math.min(10, vectors.queryTopK), f)
          .toOption
          .exists(_.context.entries.exists(_.vectorKey == q("expectedVectorKey").str))
      }
      t -> ujson.Obj(
        "queries" -> qs.size,
        "hitsAt10" -> hits.count(identity),
        "recallAt10" -> (if (qs.isEmpty) 0.0 else hits.count(identity).toDouble / qs.size),
        "label" -> BenchmarkRunner.SemanticLabel
      )
    })
  }

  /** Plants errors into verified claims and checks that the verifier catches each one. */
  def plantedErrors(results: List[WorkflowResult]): ujson.Obj = {
    val cases = results.flatMap { r =>
      val bundle = r.response.bundle
      val items = bundle.map(_.items).getOrElse(Nil)
      val verified = r.verified.filter(_.status == VerificationStatus.Verified)
      val semantic = items.find(_.evidenceType == EvidenceType.SemanticContext)
      val withNumber = verified.find(
        _.claim.numbers.exists(n =>
          !EvidenceValues.YearFields.contains(n.field) && n.field != "rank"
        )
      )
      val withYear =
        verified.find(v => "\\b(19|20)[0-9]{2}\\b".r.findFirstIn(v.claim.text).isDefined)
      val withHops = verified.find(v => "\\b[0-9]+ hops\\b".r.findFirstIn(v.claim.text).isDefined)
      def check(kind: String, c: DraftClaim): (String, VerificationStatus) =
        kind -> ClaimVerification.verify(AnswerDraft("s", List(c), Nil), bundle).head.status
      withNumber.toList.flatMap { v =>
        val n = v.claim.numbers
          .find(n => !EvidenceValues.YearFields.contains(n.field) && n.field != "rank")
          .get
        val lit = Fact.fmt(n.value)
        val wrong = Fact.fmt(n.value + 7)
        val wrongText = v.claim.text
          .replaceFirst("(?<![0-9.])" + java.util.regex.Pattern.quote(lit) + "(?![0-9])", wrong)
        if (wrongText == v.claim.text) Nil
        else
          List(
            check(
              "wrong-number",
              v.claim.copy(
                text = wrongText,
                numbers = v.claim.numbers.map(x => if (x == n) x.copy(value = n.value + 7) else x)
              )
            )
          ) ++
            semantic.toList.map(s =>
              check(
                "semantic-cited-for-number",
                v.claim.copy(evidenceIds = List(s.evidenceId.value), numbers = Nil)
              )
            )
      } ++ withYear.toList.flatMap { v =>
        val y = "\\b((19|20)[0-9]{2})\\b".r.findFirstMatchIn(v.claim.text).get.group(1)
        List(
          check(
            "wrong-window-year",
            v.claim.copy(text = v.claim.text.replaceFirst(y, (y.toInt - 9).toString))
          )
        )
      } ++ withHops.toList.flatMap { v =>
        val h = "\\b([0-9]+) hops\\b".r.findFirstMatchIn(v.claim.text).get.group(1)
        List(
          check(
            "wrong-hop-count",
            v.claim.copy(
              text = v.claim.text.replaceFirst(s"\\b$h hops\\b", s"${h.toInt + 1} hops"),
              numbers = Nil
            )
          )
        )
      } ++ List(
        check(
          "fabricated-evidence-id",
          DraftClaim(
            "claim-1",
            "This claim cites evidence that does not exist.",
            List("evidence:v1:" + "0" * 32),
            Nil,
            None
          )
        )
      )
    }
    val byKind = cases.groupBy(_._1).toList.sortBy(_._1)
    ujson.Obj(
      "planted" -> cases.size,
      "caught" -> cases.count(_._2 != VerificationStatus.Verified),
      "catchRate" -> (if (cases.isEmpty) 0.0
                      else cases.count(_._2 != VerificationStatus.Verified).toDouble / cases.size),
      "byKind" -> ujson.Obj.from(byKind.map { case (k, xs) =>
        k -> ujson.Obj(
          "planted" -> xs.size,
          "caught" -> xs.count(_._2 != VerificationStatus.Verified),
          "statuses" -> ujson.Obj.from(xs.groupBy(_._2.wire).toList.sortBy(_._1).map {
            case (s, ys) => s -> ujson.Num(ys.size.toDouble)
          })
        )
      })
    )
  }

  private def stats(xs: Seq[Double]): ujson.Obj =
    if (xs.isEmpty) ujson.Obj("n" -> 0)
    else {
      val s = xs.sorted
      def pct(p: Double) = s(math.min(s.size - 1, math.ceil(p * s.size).toInt - 1).max(0))
      ujson.Obj(
        "n" -> s.size,
        "min" -> s.head,
        "median" -> pct(0.5),
        "p95" -> pct(0.95),
        "max" -> s.last
      )
    }

  def summarize(
      results: List[(ujson.Value, Either[String, WorkflowResult], Eval)],
      semantic: ujson.Obj,
      planted: ujson.Obj
  ): ujson.Obj = {
    val evals = results.map(_._3.json)
    val ok = results.collect { case (q, Right(r), e) => (q, r, e.json) }
    def count(f: ujson.Obj => Boolean) =
      evals.count(e => !e.value.contains("workflowError") && f(e))
    val planFields = List("operations", "window", "metric", "limit", "unsupported")
    val valueRows = ok.flatMap(_._3("values").arr)
    val paths = ok.flatMap(x => Option(x._3("path")).filter(_ != ujson.Null))
    val stageNames = ok.flatMap(_._2.stageMillis.keys).distinct
    val injection = ok.filter(_._1("category").str == "prompt_injection")
    ujson.Obj(
      "questions" -> results.size,
      "workflowErrors" -> evals.count(_.value.contains("workflowError")),
      "planAccuracy" -> ujson.Obj.from(
        planFields.map(f =>
          f -> ujson.Str(s"${count(e => e("plan")(f).bool)}/${ok.size}")
        ) :+ ("allFieldsCorrect" -> ujson.Str(s"${count(_("planAllCorrect").bool)}/${ok.size}"))
      ),
      "identityResolution" -> ujson.Obj(
        "correct" -> s"${count(_("identityCorrect").bool)}/${ok.size}",
        "byExpected" -> ujson.Obj.from(List("resolved", "ambiguous", "unknown", "none").map { k =>
          val xs = ok.filter(_._3("identityExpected").str == k)
          k -> ujson
            .Obj("questions" -> xs.size, "correct" -> xs.count(_._3("identityCorrect").bool))
        }),
        "silentChoicesOnAmbiguousNames" -> count(_("silentChoiceOnAmbiguousName").bool)
      ),
      "deterministicGraphRetrieval" -> ujson.Obj(
        "expectedValues" -> valueRows.size,
        "exactMatches" -> valueRows.count(_("matched").bool),
        "exactMatchRate" -> (if (valueRows.isEmpty) 0.0
                             else valueRows.count(_("matched").bool).toDouble / valueRows.size),
        "misses" -> ujson.Arr.from(
          ok.flatMap(x =>
            x._3("values")
              .arr
              .filterNot(_("matched").bool)
              .map(v => ujson.Obj("question" -> x._1("id"), "value" -> v))
          ).take(20)
        ),
        "pathsAgainstSparkEvidence" -> ujson.Obj(
          "checked" -> paths.size,
          "matched" -> paths.count(_("matched").bool),
          "details" -> ujson.Arr.from(paths)
        )
      ),
      "semanticRetrieval" -> semantic,
      "answers" -> ujson.Obj(
        "claimCounts" -> ujson.Obj.from(
          VerificationStatus.values.map(s =>
            s.wire -> ujson.Num(ok.map(_._2.response.claims.count(_.status == s)).sum.toDouble)
          )
        ),
        "abstentionsExpected" -> count(_("abstainExpected").bool),
        "expectedAbstentionsHappened" -> count(e =>
          e("abstainExpected").bool && e("abstained").bool
        ),
        "unexpectedAbstentions" -> ujson.Arr.from(
          ok.filter(x => !x._3("abstainExpected").bool && x._3("abstained").bool)
            .map(x =>
              ujson.Obj(
                "question" -> x._1("id"),
                "reason" -> x._2.response.abstentionReason.map(ujson.Str(_)).getOrElse(ujson.Null)
              )
            )
        ),
        "missedAbstentions" -> ujson.Arr.from(
          ok.filter(x => x._3("abstainExpected").bool && !x._3("abstained").bool)
            .map(x => x._1("id"))
        ),
        "plantedErrors" -> planted,
        "injection" -> ujson.Arr.from(
          injection.map(x =>
            ujson.Obj(
              "question" -> x._1("id"),
              "planAsExpected" -> x._3("planAllCorrect"),
              "valuesMatched" -> x._3("valuesMatched"),
              "valuesExpected" -> x._3("valuesExpected")
            )
          )
        )
      ),
      "stageLatencyMillisLocalNotRepresentative" -> ujson.Obj.from(
        stageNames.map(s => s -> stats(ok.flatMap(_._2.stageMillis.get(s)).map(_.toDouble)))
      ),
      "totalLatencyMillisLocalNotRepresentative" -> stats(
        ok.map(_._2.audit.latencyMillis.toDouble)
      ),
      "label" -> BenchmarkRunner.Label
    )
  }
}
