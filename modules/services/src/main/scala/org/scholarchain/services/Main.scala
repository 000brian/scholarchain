package org.scholarchain.services

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Clock
import java.util.concurrent.CountDownLatch

import scala.util.control.NonFatal

import org.scholarchain.core.RunId
import org.scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import org.scholarchain.rag.embedding.EmbeddingProviders
import org.scholarchain.rag.reembed.ReembedHandler
import org.scholarchain.rag.runtime.RuntimeService
import org.scholarchain.storage.jobs.ServiceStores

/** Builds the RuntimeService handle and the ServiceStores a server runs over (decision 7a). This is
  * the one place that names the local or AWS environment.
  */
object Composition {

  /** Local runs: rag's in-memory runtime over the export and the Neptune CSV files, and storage's
    * in-memory jobs, idempotency records and job queue (only role `all`, since everything lives in
    * one process). The queue hides a received message for `jobLeaseMillis` plus
    * [[ServiceLimits.LocalVisibilityMarginMillis]].
    */
  def local(
      config: ScholarChainConfig,
      exportDir: Path,
      neptuneCsvDir: Path,
      clock: Clock,
      options: RuntimeService.LocalOptions = RuntimeService.LocalOptions()
  ): Either[String, (RuntimeService, ServiceStores)] =
    for {
      settings <- config.service.toRight("the service section is not configured")
      runtime <- RuntimeService.local(config, exportDir, neptuneCsvDir, options)
      stores <- overRuntime(runtime, settings.jobLeaseMillis.toLong, clock)
    } yield (runtime, stores)

  /** The in-memory service stores over a local runtime handle (also used by tests). */
  def overRuntime(
      runtime: RuntimeService,
      jobLeaseMillis: Long,
      clock: Clock
  ): Either[String, ServiceStores] =
    runtime.localEnvironment
      .toRight("the runtime has no local environment")
      .flatMap(env =>
        ServiceStores.local(env, jobLeaseMillis + ServiceLimits.LocalVisibilityMarginMillis, clock)
      ) match {
      case l @ Left(_) => runtime.close(); l
      case r           => r
    }

  /** AWS: rag's AWS runtime (Neptune, DynamoDB, S3, S3 Vectors, the live embedder and model) for
    * graph run `graphRunId`, and the QuerySession table and SQS job queue at `service.jobQueueUrl`
    * (required here). Building makes no network call.
    */
  def aws(
      config: ScholarChainConfig,
      graphRunId: RunId,
      options: RuntimeService.AwsOptions = RuntimeService.AwsOptions()
  ): Either[String, (RuntimeService, ServiceStores)] =
    for {
      settings <- config.service.toRight("the service section is not configured")
      queueUrl <- settings.jobQueueUrl.toRight("service.jobQueueUrl is required with --stores aws")
      runtime <- RuntimeService.aws(
        config,
        RuntimeService.defaultAwsClientSettings(config),
        graphRunId,
        options
      )
      env <- runtime.awsEnvironment.toRight("the runtime has no AWS environment")
      stores <- ServiceStores.aws(env, queueUrl) match {
        case l @ Left(_) => runtime.close(); l
        case r           => r
      }
    } yield (runtime, stores)

  /** AWS, roles `worker` and `all` (Phase 15 decision 8.1): the embedding queue at
    * `service.embeddingQueueUrl` (storage's `ServiceStores.embeddingQueue`, raw bodies, no send)
    * and a factory of rag's re-embed handler (`ReembedHandler.fromConfig` with the configured
    * document embedder and storage's re-embed path `AwsStorageEnvironment.reembedder`). Right(None)
    * when the URL is not set (the worker runs the job loop only). Left when the runtime has no AWS
    * environment or the URL is refused (the message never names the URL). Building makes no network
    * call; the handler is built when the server starts.
    */
  def embeddingWork(
      config: ScholarChainConfig,
      runtime: RuntimeService,
      wiring: EmbeddingProviders.Wiring = EmbeddingProviders.Wiring()
  ): Either[String, Option[EmbeddingWork]] =
    config.service.flatMap(_.embeddingQueueUrl) match {
      case None      => Right(None)
      case Some(url) =>
        for {
          env <- runtime.awsEnvironment.toRight(
            "service.embeddingQueueUrl needs the AWS stores (--stores aws)"
          )
          queue <- ServiceStores.embeddingQueue(env, url)
        } yield Some(
          EmbeddingWork(
            queue,
            log =>
              ReembedHandler
                .fromConfig(config, embedder => env.reembedder(embedder), wiring, log)
                .map(_.handler)
          )
        )
    }
}

/** The one entry point (decision 7a):
  *
  * {{{
  * org.scholarchain.services.Main --config FILE --role query|worker|evidence|graph|all
  *     --stores local|aws [--export DIR --neptune-csv DIR] [--graph-run RUNID]
  * }}}
  *
  *   - `--stores local` allows only `--role all` and needs `--export` and `--neptune-csv`; it binds
  *     127.0.0.1.
  *   - `--stores aws` needs `--graph-run` (the Spark graph run loaded into Neptune and DynamoDB)
  *     and `service.jobQueueUrl`; it binds 0.0.0.0 (the container network). With
  *     `service.embeddingQueueUrl` set, roles `worker` and `all` also consume the embedding queue
  *     ([[Composition.embeddingWork]]); `--stores local` ignores that key (no SQS locally).
  *
  * The API keys are read from the environment variables named by the `service` section; the process
  * refuses to start when either is unset or blank, or both hold the same value.
  */
object Main {

  final case class Args(
      config: Path,
      role: Role,
      stores: String,
      exportDir: Option[Path],
      neptuneCsv: Option[Path],
      graphRun: Option[RunId]
  )

  val Usage: String =
    "usage: Main --config FILE --role query|worker|evidence|graph|all --stores local|aws " +
      "[--export DIR --neptune-csv DIR] [--graph-run RUNID]"

  def parseArgs(args: List[String]): Either[String, Args] = {
    val known = Set("--config", "--role", "--stores", "--export", "--neptune-csv", "--graph-run")
    def pairs(xs: List[String], acc: Map[String, String]): Either[String, Map[String, String]] =
      xs match {
        case Nil                                               => Right(acc)
        case k :: v :: rest if known(k) && !v.startsWith("--") =>
          if (acc.contains(k)) Left(s"$k is given twice") else pairs(rest, acc + (k -> v))
        case k :: _ => Left(s"unexpected argument '$k'; $Usage")
      }
    for {
      m <- pairs(args, Map.empty)
      config <- m.get("--config").map(Paths.get(_)).toRight(s"--config is required; $Usage")
      role <- m.get("--role").toRight(s"--role is required; $Usage").flatMap(Role.parse)
      stores <- m
        .get("--stores")
        .filter(Set("local", "aws"))
        .toRight(s"--stores must be local or aws; $Usage")
      graphRun <- m.get("--graph-run") match {
        case None    => Right(None)
        case Some(g) => RunId.parse(g).map(Some(_))
      }
      exportDir = m.get("--export").map(Paths.get(_))
      csv = m.get("--neptune-csv").map(Paths.get(_))
      _ <- stores match {
        case "local" =>
          for {
            _ <- Either.cond(
              role == Role.All,
              (),
              "--stores local allows only --role all (the in-memory stores live in one process)"
            )
            _ <- Either.cond(
              exportDir.nonEmpty && csv.nonEmpty,
              (),
              "--stores local needs --export and --neptune-csv"
            )
            _ <- Either.cond(
              graphRun.isEmpty,
              (),
              "--graph-run is refused with --stores local (the export names its graph run)"
            )
          } yield ()
        case _ =>
          for {
            _ <- Either.cond(graphRun.nonEmpty, (), "--stores aws needs --graph-run")
            _ <- Either.cond(
              exportDir.isEmpty && csv.isEmpty,
              (),
              "--export and --neptune-csv are refused with --stores aws"
            )
          } yield ()
      }
    } yield Args(config, role, stores, exportDir, csv, graphRun)
  }

  /** The configuration file plus `SCHOLARCHAIN_CFG_*` overrides from `env`. */
  def loadConfig(path: Path, env: Map[String, String]): Either[String, ScholarChainConfig] =
    for {
      text <-
        try Right(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
        catch {
          case NonFatal(_) => Left(s"cannot read the configuration file ${path.getFileName}")
        }
      cfg <- ConfigLoader
        .load(text, path.getFileName.toString, env.filter(_._1.startsWith(ConfigLoader.EnvPrefix)))
        .left
        .map(_.message)
        .map(_.config)
      _ <- cfg.service.toRight("the configuration has no service section")
    } yield cfg

  /** Parses, checks the API keys (before any runtime is built), builds and starts a server (no
    * shutdown hook; [[main]] adds it).
    */
  def start(args: List[String], env: Map[String, String]): Either[String, ServiceServer] =
    for {
      a <- parseArgs(args)
      cfg <- loadConfig(a.config, env)
      // S-7: refuse a missing, blank or shared API key before the runtime is built (fail fast)
      settings <- cfg.service.toRight("the configuration has no service section")
      _ <- ApiKeys.load(settings, env.get)
      clock = Clock.systemUTC()
      built <- a.stores match {
        case "local" => Composition.local(cfg, a.exportDir.get, a.neptuneCsv.get, clock)
        case _       => Composition.aws(cfg, a.graphRun.get)
      }
      (runtime, stores) = built
      // the embedding queue: AWS stores and the roles that run a worker only
      embedding <-
        if (a.stores == "aws" && (a.role == Role.Worker || a.role == Role.All))
          Composition.embeddingWork(cfg, runtime) match {
            case l @ Left(_) => runtime.close(); l
            case r           => r
          }
        else Right(None)
      server <- ServiceServer.start(
        runtime,
        stores,
        ServerOptions(
          role = a.role,
          bindHost = if (a.stores == "local") "127.0.0.1" else "0.0.0.0",
          clock = clock,
          env = env.get,
          embedding = embedding
        )
      ) match {
        case l @ Left(_) => runtime.close(); l
        case r           => r
      }
    } yield server

  def main(args: Array[String]): Unit = {
    // bound the time a client may take to send its request (JDK HTTP server, seconds)
    if (System.getProperty("sun.net.httpserver.maxReqTime") == null)
      System.setProperty("sun.net.httpserver.maxReqTime", "30")
    start(args.toList, sys.env) match {
      case Left(message) =>
        System.err.println(s"scholarchain services: $message")
        sys.exit(2)
      case Right(server) =>
        val stopped = new CountDownLatch(1)
        Runtime.getRuntime.addShutdownHook(
          new Thread(
            () => {
              server.close(); stopped.countDown()
            },
            s"${ServiceLimits.ThreadPrefix}shutdown"
          )
        )
        System.err.println(
          s"scholarchain services: role ${server.options.role.wire} listening on port ${server.port}"
        )
        stopped.await()
    }
  }
}
