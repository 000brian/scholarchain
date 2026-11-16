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
    name: String = "srv"
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
  *   - `ready`: 3 readiness check threads, queue 9.
  *
  * `close()` stops accepting, stops the worker loop, shuts every pool down waiting at most
  * `syncDeadlineMillis`, waits for its threads, then closes the RuntimeService handle. Thread-safe;
  * idempotent.
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
    val threadPrefix: String
) extends AutoCloseable {
  private val closed = new AtomicBoolean(false)

  def port: Int = http.getAddress.getPort
  def baseUri: String = s"http://${options.bindHost}:$port"

  /** Every thread this server created (tests check none is alive after close). */
  def threads: List[Thread] = pools.flatMap(_._2.threads)

  def isClosed: Boolean = closed.get

  def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      loop.foreach(_.stop())
      try http.stop(0)
      catch { case NonFatal(_) => () }
      val wait = settings.syncDeadlineMillis.toLong
      pools.foreach { case (p, _) => Pools.shutdown(p, wait) }
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
    val pools = List(httpPool, queryPool, workerPool, readyPool)
    try {
      val breakers = new Breakers(settings, clock)
      val ctx = new AppContext(runtime, stores, settings, clock, options.ids, log, breakers)
      val probeId = ResearcherId(ServiceLimits.ProbeResearcherId)
      val storeChecks: List[(String, () => Either[String, Any])] = List(
        "metrics" -> (() =>
          runtime.stores.metrics.get(probeId, runtime.context.runtime.defaultWindowLabel)
        ),
        "graph" -> (() => runtime.stores.graph.run(GraphQuery.ResearcherById(probeId)))
      )
      val jobCheck: List[(String, () => Either[String, Any])] =
        if (Set[Role](Role.Query, Role.Worker, Role.All).contains(options.role))
          List("jobs" -> (() => stores.jobs.get(ServiceLimits.ProbeJobId, clock.millis())))
        else Nil
      val health = new HealthService(options.role, storeChecks ++ jobCheck, readyPool._1, clock)
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
        if (options.startWorker && (options.role == Role.Worker || options.role == Role.All))
          Some(new WorkerLoop(worker, workerPool._1, settings.workerConcurrency, log))
        else None
      http.start()
      loop.foreach(_.start())
      log.event(
        "started",
        None,
        List("port" -> ujson.Num(http.getAddress.getPort), "worker" -> ujson.Bool(loop.nonEmpty))
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
          prefix
        )
      )
    } catch {
      case NonFatal(e) =>
        pools.foreach { case (p, _) => p.shutdownNow(); p.awaitTermination(1, TimeUnit.SECONDS) }
        Left(s"the server did not start: ${e.getClass.getSimpleName}")
    }
  }
}
