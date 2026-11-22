package org.scholarchain.services

import java.net.InetSocketAddress
import java.time.Clock
import java.util.concurrent.{ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.util.control.NonFatal

import com.sun.net.httpserver.HttpServer

import org.scholarchain.core.ResearcherId
import org.scholarchain.core.config.ServiceSettings
import org.scholarchain.rag.runtime.RuntimeService
import org.scholarchain.storage.jobs.ServiceStores
import org.scholarchain.storage.ports.GraphQuery

/** How to run one server process.
  *
  * @param port
  *   None: `service.port`; tests pass Some(0) (an ephemeral port)
  * @param env
  *   where the API key values are read (the variables named by the `service` section)
  * @param startWorker
  *   run the worker loop for roles `worker` and `all` (tests may drive [[Worker.processOne]])
  * @param closeRuntime
  *   `close()` also closes the [[RuntimeService]] handle (decision 7i); a restart test keeps it
  * @param embedding
  *   the embedding queue and rag's handler (Phase 15 decision 8.1; `Composition.embeddingWork`);
  *   roles `worker` and `all` then run a second loop on it next to the job loop; other roles ignore
  *   it. None: the job loop only
  * @param embeddingConcurrency
  *   threads of the embedding-queue loop, 1..[[ServiceLimits.MaxEmbeddingWorkerConcurrency]]
  *   (default [[ServiceLimits.EmbeddingWorkerConcurrency]])
  * @param readDeadlineMillis
  *   Phase 16 route time limit of the evidence and explain reads, 1..
  *   [[ServiceLimits.MaxReadRouteDeadlineMillis]] (default
  *   [[ServiceLimits.ReadRouteDeadlineMillis]])
  * @param embeddingDrainMillis
  *   Phase 16 H-05: how long `close()` waits for in-flight embedding-queue calls, 1..
  *   [[ServiceLimits.EmbeddingDrainMillis]] (the default; a test shortens it, nothing may lengthen
  *   it)
  */
final case class ServerOptions(
    role: Role,
    bindHost: String = "127.0.0.1",
    port: Option[Int] = None,
    clock: Clock = Clock.systemUTC(),
    ids: IdGenerator = IdGenerator.random,
    logSink: LogSink = LogSink.slf4j,
    env: String => Option[String] = sys.env.get,
    startWorker: Boolean = true,
    closeRuntime: Boolean = true,
    name: String = "srv",
    embedding: Option[EmbeddingWork] = None,
    embeddingConcurrency: Int = ServiceLimits.EmbeddingWorkerConcurrency,
    readDeadlineMillis: Long = ServiceLimits.ReadRouteDeadlineMillis,
    embeddingDrainMillis: Long = ServiceLimits.EmbeddingDrainMillis
)

/** One running services process: the JDK HTTP server, the route table of its role, the bounded
  * pools, and (roles `worker` and `all`) the worker loop.
  *
  * Pools (every one fixed-size with a bounded queue; threads named
  * `scholarchain-<server>-<pool>-n`):
  *   - `http`: `maxConcurrentQueries + 8` handler threads, queue 256, then caller-runs;
  *   - `query`: `maxConcurrentQueries` workflow threads; admission is a permit taken before submit
  *     (no waiting queue: a request without a permit gets 503 OVERLOADED);
  *   - `worker`: `workerConcurrency` worker loop threads;
  *   - `embedding` (roles `worker` and `all` with [[ServerOptions.embedding]]):
  *     `embeddingConcurrency` embedding-queue loop threads;
  *   - `ready`: 3 readiness check threads, queue 9;
  *   - `read` (Phase 16, route time limits): `maxConcurrentQueries + 8` threads, queue of the same
  *     size, for the evidence and explain reads, each waited for at most
  *     [[ServerOptions.readDeadlineMillis]].
  *
  * `close()` stops accepting, stops both worker loops, shuts every pool down at once and waits for
  * them together (each at most `syncDeadlineMillis`, the embedding pool at most
  * [[ServerOptions.embeddingDrainMillis]] (default [[ServiceLimits.EmbeddingDrainMillis]]), all
  * measured from the same start, so the drain stays inside the ECS stop timeout), waits for its
  * threads, then closes the RuntimeService handle. Thread-safe; idempotent.
  */
final class ServiceServer private (
    val settings: ServiceSettings,
    val options: ServerOptions,
    val runtime: RuntimeService,
    val stores: ServiceStores,
    val app: Application,
    val worker: Worker,
    val breakers: Breakers,
    http: HttpServer,
    pools: List[(ThreadPoolExecutor, NamedThreadFactory)],
    loop: Option[WorkerLoop],
    val threadPrefix: String,
    val embeddingWorker: Option[EmbeddingWorker],
    embeddingLoop: Option[EmbeddingLoop],
    embeddingPool: Option[ThreadPoolExecutor]
) extends AutoCloseable {
  private val closed = new AtomicBoolean(false)

  def port: Int = http.getAddress.getPort
  def baseUri: String = s"http://${options.bindHost}:$port"

  /** Every thread this server created (tests check none is alive after close). */
  def threads: List[Thread] = pools.flatMap(_._2.threads)

  def isClosed: Boolean = closed.get

  /** True while the embedding-queue loop runs (tests). */
  def embeddingLoopRunning: Boolean = embeddingLoop.exists(_.isRunning)

  def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      loop.foreach(_.stop())
      embeddingLoop.foreach(_.stop())
      try http.stop(0)
      catch { case NonFatal(_) => () }
      val start = System.nanoTime()
      def waitOf(p: ThreadPoolExecutor): Long =
        if (embeddingPool.exists(_ eq p)) options.embeddingDrainMillis
        else settings.syncDeadlineMillis.toLong
      // every pool stops taking work now; the waits below overlap instead of adding up
      pools.foreach { case (p, _) => p.shutdown() }
      pools.foreach { case (p, _) =>
        val left = waitOf(p) - (System.nanoTime() - start) / 1000000L
        Pools.shutdown(p, math.max(0L, left))
      }
      val deadline = System.nanoTime() + 2000L * 1000000L
      threads.foreach { t =>
        val left = (deadline - System.nanoTime()) / 1000000L
        if (left > 0) t.join(left)
      }
      if (options.closeRuntime) runtime.close()
    }
}

object ServiceServer {
  private val instances = new AtomicInteger(0)

  /** Starts a server over `runtime` and `stores`. Left (nothing left running) when the config has
    * no `service` section or the API keys refuse start (unset, blank or equal values; the message
    * names the variable, never a value).
    */
  def start(
      runtime: RuntimeService,
      stores: ServiceStores,
      options: ServerOptions
  ): Either[String, ServiceServer] =
    for {
      settings <- runtime.config.service.toRight("the service section is not configured")
      keys <- ApiKeys.load(settings, options.env)
      _ <- Either.cond(
        options.embeddingConcurrency >= 1 &&
          options.embeddingConcurrency <= ServiceLimits.MaxEmbeddingWorkerConcurrency,
        (),
        s"embeddingConcurrency must be in 1..${ServiceLimits.MaxEmbeddingWorkerConcurrency}"
      )
      _ <- Either.cond(
        options.readDeadlineMillis >= 1 &&
          options.readDeadlineMillis <= ServiceLimits.MaxReadRouteDeadlineMillis,
        (),
        s"readDeadlineMillis must be in 1..${ServiceLimits.MaxReadRouteDeadlineMillis}"
      )
      _ <- Either.cond(
        options.embeddingDrainMillis >= 1 &&
          options.embeddingDrainMillis <= ServiceLimits.EmbeddingDrainMillis,
        (),
        s"embeddingDrainMillis must be in 1..${ServiceLimits.EmbeddingDrainMillis}"
      )
      server <- build(runtime, stores, options, settings, keys)
    } yield server

  private def build(
      runtime: RuntimeService,
      stores: ServiceStores,
      options: ServerOptions,
      settings: ServiceSettings,
      keys: ApiKeys
  ): Either[String, ServiceServer] = {
    val prefix = s"${ServiceLimits.ThreadPrefix}${options.name}${instances.incrementAndGet()}"
    val clock = options.clock
    val redactor = new Redactor(ApiKeys.valuesForRedaction(settings, options.env))
    val log = new ServiceLog(options.logSink, options.role.wire, clock, redactor)
    def pool(name: String, threads: Int, queue: Int, callerRuns: Boolean = false) = {
      val f = new NamedThreadFactory(s"$prefix-$name")
      val p =
        if (callerRuns) Pools.fixed(threads, queue, f, new ThreadPoolExecutor.CallerRunsPolicy())
        else Pools.fixed(threads, queue, f)
      (p, f)
    }
    val httpPool =
      pool(
        "http",
        settings.maxConcurrentQueries + ServiceLimits.HttpExtraThreads,
        ServiceLimits.HttpQueueCapacity,
        callerRuns = true
      )
    val queryPool = pool("query", settings.maxConcurrentQueries, settings.maxConcurrentQueries)
    val workerPool = pool("worker", settings.workerConcurrency, settings.workerConcurrency)
    val readyPool =
      pool("ready", ServiceLimits.ReadinessThreads, ServiceLimits.ReadinessQueueCapacity)
    val readThreads = settings.maxConcurrentQueries + ServiceLimits.HttpExtraThreads
    val readPool = pool("read", readThreads, readThreads)
    val workerRole = options.role == Role.Worker || options.role == Role.All
    // rag's handler, built once with this server's log (its one line per delivery)
    val handler
        : Either[String, Option[(EmbeddingWork, org.scholarchain.rag.reembed.ReembedHandler)]] =
      options.embedding.filter(_ => workerRole) match {
        case None    => Right(None)
        case Some(e) =>
          try
            e.handlerOf(line => log.external(line, "reembed-delivery"))
              .map(h => Some(e -> h))
          catch {
            case NonFatal(x) =>
              Left(s"the re-embed handler did not build: ${x.getClass.getSimpleName}")
          }
      }
    val embeddingPool = handler.toOption.flatten.map(_ =>
      pool("embedding", options.embeddingConcurrency, options.embeddingConcurrency)
    )
    val pools =
      List(httpPool, queryPool, workerPool, readyPool, readPool) ++ embeddingPool.toList
    if (handler.isLeft) {
      pools.foreach { case (p, _) => p.shutdownNow() }
      return Left(handler.left.getOrElse(""))
    }
    try {
      // FECD-1 (B2, B4): every breaker state change is one `circuit-state` line
      val breakers = new Breakers(
        settings,
        clock,
        (name, state) =>
          log.event(
            "circuit-state",
            None,
            List("breaker" -> ujson.Str(name), "state" -> ujson.Str(state))
          )
      )
      val ctx = new AppContext(
        runtime,
        stores,
        settings,
        clock,
        options.ids,
        log,
        breakers,
        Some(new ReadTimeLimit(readPool._1, options.readDeadlineMillis))
      )
      val probeId = ResearcherId(ServiceLimits.ProbeResearcherId)
      val storeChecks: List[(String, () => Either[String, Any])] = List(
        "metrics" -> (() =>
          runtime.stores.metrics.get(probeId, runtime.context.runtime.defaultWindowLabel)
        ),
        // G-23: the Left is storage's allowlisted code, so the failure line names its class
        "graph" -> (() =>
          GraphReads
            .classified(runtime.stores.graph, GraphQuery.ResearcherById(probeId))
            .left
            .map(_.code)
        )
      )
      val jobCheck: List[(String, () => Either[String, Any])] =
        if (Set[Role](Role.Query, Role.Worker, Role.All).contains(options.role))
          List("jobs" -> (() => stores.jobs.get(ServiceLimits.ProbeJobId, clock.millis())))
        else Nil
      val health =
        new HealthService(options.role, storeChecks ++ jobCheck, readyPool._1, clock, Some(log))
      val app = new Application(
        new QueryService(ctx, queryPool._1),
        new JobService(ctx),
        new EvidenceService(ctx),
        new ExplainService(ctx),
        new GraphService(ctx),
        health
      )
      val worker = new Worker(ctx)
      val adapter = new HttpAdapter(
        options.role,
        Routes.table(app),
        keys,
        new TokenBucket(settings.requestsPerMinute, clock),
        settings.maxRequestBytes,
        options.ids,
        log
      )
      val http = HttpServer.create(
        new InetSocketAddress(options.bindHost, options.port.getOrElse(settings.port)),
        ServiceLimits.Backlog
      )
      http.createContext("/", adapter)
      http.setExecutor(httpPool._1)
      val loop =
        if (options.startWorker && workerRole)
          Some(new WorkerLoop(worker, workerPool._1, settings.workerConcurrency, log))
        else None
      val embeddingWorker = handler.toOption.flatten.map { case (e, h) =>
        new EmbeddingWorker(e.queue, h, log, e.indexes)
      }
      val embeddingLoop =
        for {
          w <- embeddingWorker if options.startWorker
          p <- embeddingPool
        } yield new EmbeddingLoop(w, p._1, options.embeddingConcurrency, log)
      http.start()
      loop.foreach(_.start())
      embeddingLoop.foreach(_.start())
      log.event(
        "started",
        None,
        List(
          "port" -> ujson.Num(http.getAddress.getPort),
          "worker" -> ujson.Bool(loop.nonEmpty),
          "embeddingWorker" -> ujson.Bool(embeddingLoop.nonEmpty)
        )
      )
      Right(
        new ServiceServer(
          settings,
          options,
          runtime,
          stores,
          app,
          worker,
          breakers,
          http,
          pools,
          loop,
          prefix,
          embeddingWorker,
          embeddingLoop,
          embeddingPool.map(_._1)
        )
      )
    } catch {
      case NonFatal(e) =>
        pools.foreach { case (p, _) => p.shutdownNow(); p.awaitTermination(1, TimeUnit.SECONDS) }
        Left(s"the server did not start: ${e.getClass.getSimpleName}")
    }
  }
}
