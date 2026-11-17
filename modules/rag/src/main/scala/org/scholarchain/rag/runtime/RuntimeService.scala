package org.scholarchain.rag.runtime

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

import org.scholarchain.core._
import org.scholarchain.core.config.{EmbeddingProvider, LlmProvider, ScholarChainConfig}
import org.scholarchain.rag.embedding.{EmbeddingProviders, InputType}
import org.scholarchain.rag.retrieval.SemanticRetriever
import org.scholarchain.rag.semantic.ExportDocuments
import org.scholarchain.storage.{Sleeper, StorageConfig}
import org.scholarchain.storage.aws.{AwsClientSettings, AwsSdkClients, AwsStorageEnvironment}
import org.scholarchain.storage.jobs.ServiceStores
import org.scholarchain.storage.local.{LocalRuntimeEnvironment, LocalRuntimeOptions}
import org.scholarchain.storage.runtime.RuntimeStores
import org.scholarchain.storage.source.{ExportManifest, LocalDirectorySource, RejectLog}

/** The runtime entry point for callers outside rag (the services module, the benchmark runner): one
  * [[QueryWorkflow]] over one set of [[RuntimeStores]], with its model, prompts and retriever,
  * built by one of the factories in the companion object. Callers never repeat rag's wiring.
  *
  * Thread safety:
  *   - safe from several threads at once: [[workflow]] (`run`, `validate`, `queryRunId`),
  *     [[queryRunId]], [[stores]] (storage's in-memory and AWS runtime stores), [[model]] (the
  *     fake, Anthropic and Bedrock models; their usage counters are atomic), [[retriever]],
  *     [[close]] (idempotent; once it returns, the stores and clients it owns are closed and later
  *     queries fail);
  *   - immutable: [[config]], [[context]], [[prompts]], [[graphRunId]], [[environment]],
  *     [[ingestReport]];
  *   - not covered here: the environment objects' own loaders and pipelines (callers lock around
  *     them), and a caller-supplied model or store in [[RuntimeService.fromStores]] (as safe as the
  *     caller made it).
  *
  * [[close]] closes what the factory opened or took over: the local or AWS environment (and its SDK
  * clients, including caller-supplied `AwsOptions.clients`, whose ownership passes to the handle)
  * and an owned model or embedder that is closeable. A handle from [[RuntimeService.fromStores]]
  * owns only the closeables passed to it.
  */
final class RuntimeService private (
    val config: ScholarChainConfig,
    val workflow: QueryWorkflow,
    val stores: RuntimeStores,
    val environment: RuntimeService.Environment,
    val context: RuntimeContext,
    val model: LanguageModel,
    val prompts: PromptSet,
    val retriever: Option[SemanticRetriever],
    val ingestReport: Option[ujson.Obj],
    owned: List[AutoCloseable]
) extends AutoCloseable {
  private val closed = new AtomicBoolean(false)

  /** The Spark graph run this runtime queries (part of every query run ID). */
  def graphRunId: RunId = context.graphRunId

  /** The query run ID `request` gets (the same ID [[QueryWorkflow.run]] uses). */
  def queryRunId(request: QueryRequest): Either[String, RunId] = workflow.queryRunId(request)

  /** The in-memory environment, when built by [[RuntimeService.local]] or over one. */
  def localEnvironment: Option[LocalRuntimeEnvironment] = environment match {
    case RuntimeService.Local(e) => Some(e)
    case _                       => None
  }

  /** The AWS environment, when built by [[RuntimeService.aws]] (for `ServiceStores.aws`). */
  def awsEnvironment: Option[AwsStorageEnvironment] = environment match {
    case RuntimeService.Aws(e) => Some(e)
    case _                     => None
  }

  def isClosed: Boolean = closed.get

  /** Closes every client and store this handle opened, once; later calls do nothing. */
  def close(): Unit =
    if (closed.compareAndSet(false, true))
      owned.foreach(c =>
        try c.close()
        catch { case NonFatal(_) => () }
      )
}

object RuntimeService {

  /** What the stores came from. */
  sealed trait Environment { def label: String }

  /** storage's in-memory runtime environment (graph from the Neptune CSV files, tables from the
    * export).
    */
  final case class Local(env: LocalRuntimeEnvironment) extends Environment {
    val label = "local stores (in-memory)"
  }

  /** storage's AWS environment (Neptune, DynamoDB, S3, S3 Vectors). */
  final case class Aws(env: AwsStorageEnvironment) extends Environment {
    val label = "AWS stores"
  }

  /** Stores supplied by the caller ([[fromStores]]) that are not a local environment. */
  case object Supplied extends Environment {
    val label = "caller-supplied stores"
  }

  /** The job name of the run ID under which the local summaries are ingested. */
  val LocalIngestJob = "rag.local-ingest"

  /** Times one named build step (the benchmark runner reports and bounds them). */
  trait StepTimer {
    def apply[A](name: String)(f: => A): A
  }
  object StepTimer {
    val Untimed: StepTimer = new StepTimer { def apply[A](name: String)(f: => A): A = f }
  }

  /** Options of [[local]].
    *
    * @param promptSet
    *   which prompts (and claims schema) the workflow uses; default [[Prompts.Current]]
    * @param promptsRoot
    *   the `prompts` directory; default: found with [[Prompts.locate]]
    * @param windowLabels
    *   COAUTHORED_WITH windows loaded into the in-memory graph; default
    *   `runtime.defaultWindowLabel`
    * @param loadRejectFile
    *   where rejected export rows of the load are logged; default: counted only
    * @param renderRejectFile
    *   where rejected summaries of the semantic render are logged; default: counted only
    * @param llmWiring
    *   environment, transport, sleeper and clock of the model client (tests pass stubs)
    * @param steps
    *   timer of the two long steps `load-runtime-environment` and `render-and-ingest-summaries`
    */
  final case class LocalOptions(
      promptSet: PromptSetSpec = Prompts.Current,
      promptsRoot: Option[Path] = scala.None,
      windowLabels: Option[List[String]] = scala.None,
      loadRejectFile: Option[Path] = scala.None,
      renderRejectFile: Option[Path] = scala.None,
      llmWiring: LlmFactory.Wiring = LlmFactory.Wiring(),
      steps: StepTimer = StepTimer.Untimed
  )

  /** Options of [[aws]].
    *
    * @param clients
    *   SDK clients to use (tests pass stub clients); default: built from configuration and the
    *   client settings, without a network call
    * @param embeddingWiring
    *   environment and transport of the query embedder (tests pass stubs)
    */
  final case class AwsOptions(
      promptSet: PromptSetSpec = Prompts.Current,
      promptsRoot: Option[Path] = scala.None,
      clients: Option[AwsSdkClients] = scala.None,
      llmWiring: LlmFactory.Wiring = LlmFactory.Wiring(),
      embeddingWiring: EmbeddingProviders.Wiring = EmbeddingProviders.Wiring(),
      rejectFile: Option[Path] = scala.None,
      sleeper: Sleeper = Sleeper.real
  )

  /** Smallest SDK socket and API-call timeout [[aws]] accepts: above the job queue's SQS long poll
    * (`ServiceStores.SqsWaitSeconds`, 1 s) plus 4 s of margin, so a long-poll receive never times
    * out client-side.
    */
  val MinAwsCallTimeoutMillis: Int = ServiceStores.SqsWaitSeconds * 1000 + 4000

  /** The SDK client settings [[aws]] is meant to run with: connect 10 s, socket and API call 60 s
    * (above `runtime.graphTimeoutMillis`, at most 30 s, and the SQS long poll), one SDK attempt
    * (storage's bounded retry is the only one).
    */
  def defaultAwsClientSettings(config: ScholarChainConfig): AwsClientSettings =
    AwsClientSettings(config.aws.region, 10000, 60000, 60000, AwsClientSettings.SdkMaxAttempts)

  // D-02: an explicit root, else the repository's prompts folder, else the classpath copies (the
  // services image holds only the jar)
  private def promptSet(
      root: Option[Path],
      spec: PromptSetSpec
  ): Either[String, PromptSet] =
    Prompts.loadForRuntime(root, spec)

  private def closeable(x: Any): List[AutoCloseable] = x match {
    case c: AutoCloseable => List(c)
    case _                => Nil
  }

  private def pathIndexOf(config: ScholarChainConfig): Either[String, String] =
    config.vectors.map(_.collaborationPathIndex).toRight("vectors section is not configured")

  /** What [[BenchmarkRunner]] did before its questions: storage's [[LocalRuntimeEnvironment]]
    * (graph from the Neptune CSV files, tables from the export), the researcher-profile, community
    * and path documents rendered and ingested with the `fake` embedder, the prompts, the model of
    * `llm.provider` (`fake` or a live one) and one [[QueryWorkflow]]. Refuses a live embedder
    * (`embedding.provider` other than `fake`) with local stores. Read-only toward both input
    * folders. A Left or an exception closes whatever was opened.
    */
  def local(
      config: ScholarChainConfig,
      exportDir: Path,
      neptuneCsvDir: Path,
      options: LocalOptions = LocalOptions()
  ): Either[String, RuntimeService] = {
    var env: LocalRuntimeEnvironment = null
    // U-5: the model and the embedders built before a later Left are closed too
    var opened: List[AutoCloseable] = Nil
    def cleanup(): Unit = {
      opened.foreach(c =>
        try c.close()
        catch { case NonFatal(_) => () }
      )
      opened = Nil
      if (env != null) { env.close(); env = null }
    }
    try {
      val out = for {
        emb <- config.embedding.toRight("embedding section is not configured")
        p <- emb.providerKind
        _ <- Either.cond(
          p == EmbeddingProvider.Fake,
          (),
          s"embedding provider ${p.wire} is refused with local stores (local runs use the fake embedder)"
        )
        _ <- config.llm.toRight("llm section is not configured")
        cfg <- StorageConfig.from(config).left.map(_.mkString("; "))
        pathIndex <- pathIndexOf(config)
        prompts <- promptSet(options.promptsRoot, options.promptSet)
        src = new LocalDirectorySource(exportDir)
        manifest = src.manifest()
        manifestSha = Hashing.sha256Hex(
          java.nio.file.Files.readAllBytes(exportDir.resolve(ExportManifest.FileName))
        )
        ctx0 <- RuntimeContext.from(config, manifest.sourceRunId, Set.empty)
        model <- LlmFactory.create(
          config,
          new RulePlanner(ctx0.windows, ctx0.bounds),
          options.llmWiring
        )
        _ = { opened = closeable(model) }
        doc <- EmbeddingProviders.create(config, InputType.Document)
        _ = { opened = closeable(doc.port) ++ opened }
        query <- EmbeddingProviders.create(config, InputType.Query)
        _ = { opened = closeable(query.port) ++ opened }
        loaded <- options.steps("load-runtime-environment") {
          LocalRuntimeEnvironment.load(
            cfg,
            exportDir,
            neptuneCsvDir,
            LocalRuntimeOptions(
              windowLabels = options.windowLabels,
              rejectFile = options.loadRejectFile
            )
          )
        }
        _ = { env = loaded }
        ingestRunId <- RunId.of(
          LocalIngestJob,
          QueryWorkflow.CodeVersion,
          s"sha256:$manifestSha",
          config.fingerprint
        )
        ingest = options.steps("render-and-ingest-summaries") {
          val rejects = new RejectLog(options.renderRejectFile)
          try {
            val corpus =
              new ExportDocuments(src, manifest, cfg.vectors, rejects).renderSparkSummaries()
            val s = loaded.pipeline(doc.port, "dblp").open(ingestRunId, "local runtime summaries")
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
              "note" -> BenchmarkRunner.SemanticLabel
            )
          } finally rejects.close()
        }
        ctx <- RuntimeContext.from(
          config,
          manifest.sourceRunId,
          loaded.report.graphWindowLabels.toSet
        )
      } yield {
        val retriever = new SemanticRetriever(query.port, query.spec, loaded.vectors, cfg.vectors)
        new RuntimeService(
          config,
          new QueryWorkflow(loaded, ctx, model, prompts, Some(retriever), pathIndex),
          loaded,
          Local(loaded),
          ctx,
          model,
          prompts,
          Some(retriever),
          Some(ingest),
          List[AutoCloseable](loaded) ++ closeable(model) ++ closeable(doc.port) ++
            closeable(query.port)
        )
      }
      if (out.isLeft) cleanup()
      out
    } catch {
      case e: Throwable =>
        cleanup()
        throw e
    }
  }

  /** The runtime over AWS: storage's [[AwsStorageEnvironment]] stores (Neptune, DynamoDB, S3, S3
    * Vectors), the live query embedder of `embedding.provider` (`voyage`; `bedrock` is the switch),
    * the prompts and the live model of `llm.provider`. Refuses the fake LLM and the fake embedder
    * with AWS stores, and SDK timeouts below [[MinAwsCallTimeoutMillis]]. Every graph window is
    * served (Neptune holds all of them). Building it makes no network call: SDK clients connect and
    * resolve credentials on their first request, and the model and embedder clients send nothing
    * until used.
    *
    * Ownership (U-13): SDK clients passed in [[AwsOptions.clients]] are taken over. The handle's
    * [[RuntimeService.close]] closes them (through the AWS environment), and a Left or an exception
    * of this factory closes them too; the caller must not use or close them afterwards.
    *
    * @param graphRunId
    *   the Spark graph run loaded into these stores (part of every query run ID); the stores cannot
    *   be asked for it without a network call, so the caller passes it (from its deployment
    *   configuration)
    */
  def aws(
      config: ScholarChainConfig,
      clientSettings: AwsClientSettings,
      graphRunId: RunId,
      options: AwsOptions = AwsOptions()
  ): Either[String, RuntimeService] = {
    var built: List[AutoCloseable] = Nil
    def cleanup(): Unit = {
      built.foreach(c =>
        try c.close()
        catch { case NonFatal(_) => () }
      )
      built = Nil
    }
    try {
      val out = for {
        llm <- config.llm.toRight("llm section is not configured")
        lp <- llm.providerKind
        _ <- Either.cond(lp != LlmProvider.Fake, (), "the fake LLM is refused with AWS stores")
        emb <- config.embedding.toRight("embedding section is not configured")
        ep <- emb.providerKind
        _ <- Either.cond(
          ep != EmbeddingProvider.Fake,
          (),
          "the fake embedder is refused with AWS stores"
        )
        _ <- Either.cond(
          clientSettings.apiCallTimeoutMillis >= MinAwsCallTimeoutMillis &&
            clientSettings.socketTimeoutMillis >= MinAwsCallTimeoutMillis,
          (),
          s"SDK socket and API-call timeouts must be at least $MinAwsCallTimeoutMillis ms (above the SQS long poll)"
        )
        _ <- Either.cond(
          graphRunId != null && graphRunId.isWellFormed,
          (),
          "graph run ID is malformed"
        )
        cfg <- StorageConfig.from(config).left.map(_.mkString("; "))
        _ <- Either.cond(
          clientSettings.region == cfg.region,
          (),
          "client Region must be the configured aws.region"
        )
        pathIndex <- pathIndexOf(config)
        prompts <- promptSet(options.promptsRoot, options.promptSet)
        ctx <- RuntimeContext.from(config, graphRunId, config.graph.windows.map(_.label).toSet)
        model <- LlmFactory.create(
          config,
          new RulePlanner(ctx.windows, ctx.bounds),
          options.llmWiring
        )
        _ = { built = closeable(model) }
        query <- EmbeddingProviders.create(config, InputType.Query, options.embeddingWiring)
        _ = { built = built ++ closeable(query.port) }
        clients = options.clients.getOrElse(AwsSdkClients.build(clientSettings, cfg))
        _ = { built = clients :: built }
        env <-
          try Right(AwsStorageEnvironment.create(cfg, clients, options.rejectFile, options.sleeper))
          catch { case NonFatal(e) => Left(s"AWS storage environment: ${e.getMessage}") }
        // the environment closes the clients it was given
        _ = { built = env :: built.filterNot(_ eq clients) }
        stores <- env.runtimeStores
      } yield {
        val retriever = new SemanticRetriever(query.port, query.spec, stores.vectors, cfg.vectors)
        new RuntimeService(
          config,
          new QueryWorkflow(stores, ctx, model, prompts, Some(retriever), pathIndex),
          stores,
          Aws(env),
          ctx,
          model,
          prompts,
          Some(retriever),
          scala.None,
          built
        )
      }
      if (out.isLeft) cleanup()
      out
    } catch {
      case e: Throwable =>
        cleanup()
        throw e
    }
  }

  /** A runtime over stores the caller built (tests, fixtures): no load, no ingest. The environment
    * is `environment` when given, else [[Local]] when `stores` is a [[LocalRuntimeEnvironment]],
    * else [[Supplied]]. [[close]] closes only `owned` (by default nothing: the caller keeps its
    * stores).
    *
    * @param graphWindowLabels
    *   windows whose coauthor edges the graph port holds
    * @param prompts
    *   default: [[Prompts.Current]] loaded from [[Prompts.locate]]
    * @param environment
    *   the environment to report (for stores that wrap a local environment)
    */
  def fromStores(
      config: ScholarChainConfig,
      stores: RuntimeStores,
      retriever: Option[SemanticRetriever],
      model: LanguageModel,
      graphRunId: RunId,
      graphWindowLabels: Set[String],
      prompts: Option[PromptSet] = scala.None,
      owned: List[AutoCloseable] = Nil,
      clock: () => Long = () => System.nanoTime(),
      environment: Option[Environment] = scala.None
  ): Either[String, RuntimeService] =
    for {
      _ <- Either.cond(stores != null && model != null, (), "stores and model are required")
      pathIndex <- pathIndexOf(config)
      p <- prompts.map(Right(_)).getOrElse(promptSet(scala.None, Prompts.Current))
      ctx <- RuntimeContext.from(config, graphRunId, graphWindowLabels)
    } yield new RuntimeService(
      config,
      new QueryWorkflow(stores, ctx, model, p, retriever, pathIndex, clock),
      stores,
      environment.getOrElse(stores match {
        case l: LocalRuntimeEnvironment => Local(l)
        case _                          => Supplied
      }),
      ctx,
      model,
      p,
      retriever,
      scala.None,
      owned
    )
}
