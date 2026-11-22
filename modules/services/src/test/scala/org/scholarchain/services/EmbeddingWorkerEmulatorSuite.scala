package org.scholarchain.services

import java.time.Clock
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import scala.concurrent.duration._
import scala.util.control.NonFatal

import org.scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import org.scholarchain.rag.reembed.ReembedHandler
import org.scholarchain.storage.{MaskedFailures, MaskedTestFailure, Sleeper, StorageConfig}
import org.scholarchain.storage.aws.{AwsClientSettings, AwsSdkClients, AwsStorageEnvironment}
import org.scholarchain.storage.aws.SqsWorkQueue
import org.scholarchain.storage.emulator._
import org.scholarchain.storage.ingest.VectorReembedder
import org.scholarchain.storage.jobs.ServiceStores
import org.scholarchain.storage.ports.{
  EmbeddingBatch,
  EmbeddingFailure,
  EmbeddingPort,
  ReceiveQueue,
  ReceivedWork,
  WorkItem
}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  GetQueueAttributesRequest,
  GetQueueUrlRequest,
  QueueAttributeName
}

/** The emulator worker demonstration (Phase 16, decision 7.1b): the services' real embedding-queue
  * loop ([[EmbeddingWorker]] over storage's production queue adapter `ServiceStores.embeddingQueue`
  * and rag's [[ReembedHandler]], built with its public constructor over storage's production
  * re-embed path `AwsStorageEnvironment.reembedder`, never `ReembedHandler.fromConfig`) against the
  * AWS Emulator's embedding queue and its dead-letter queue: one poison message is never
  * acknowledged, is released at once on each receive, and reaches the dead-letter queue after
  * `maxReceiveCount` receives (experiment F's path, replayed on the emulator). The embedder fails
  * the test if it is ever called.
  *
  * Every test is tagged `RequiresEmulator`: excluded from `Test` (so from `sbt check`) and
  * `AwsTest`; run only by the human at E14 with `sbt services/EmulatorTest/test` (a forked JVM that
  * trusts the emulator certificate, build.sbt), after `source scripts/emulator/env.sh` and
  * `scripts/emulator/setup.sh`.
  *
  * Before any client is built, `beforeAll` checks storage's emulator safety contract
  * ([[EmulatorSafety.check]], identical to `scripts/emulator/lib.sh`), then reads the emulator
  * credentials file and `SCHOLARCHAIN_CONFIG_FILE` (`config/emulator.json`). A refusal sends no
  * request, writes one `preflight` line (class `infrastructure-failure`, the rule name, no value)
  * when the results folder is usable, and fails the suite.
  *
  * Result lines go to `results-EmbeddingWorkerEmulatorSuite.jsonl` and the demonstration line
  * (`demonstration dead-letter: demonstrated | not demonstrated (REASON)`) to
  * `demonstration-EmbeddingWorkerEmulatorSuite.txt` in `SCHOLARCHAIN_EMULATOR_RESULTS_DIR`, only
  * when it is set (one file per suite: the storage suite writes its own). Outcome classes are
  * storage's: the test fails on `implemented-failed` and `infrastructure-failure`, and passes on
  * `not-implemented` and `not-exercised` as a recorded compatibility gap, never as validated
  * functionality.
  *
  * State isolation: the poison message carries the run tag in its body (`id` `emu-RUN-poison`);
  * receives, releases and the dead-letter check count only that message. The worker also handles
  * any foreign message it receives (a new emulator and setup per run, E14).
  *
  * Bounds: one message; the receive-and-release loop stops after `maxReceiveCount` + 6 receives or
  * 40 s; at most 5 dead-letter receives; 5-second API call timeouts; 60 s per test. Test-only SDK
  * calls: `GetQueueUrl` and `GetQueueAttributes` (no configuration key holds an emulator queue URL,
  * scripts/emulator/OPERATIONS.md); every queue operation of the worker goes through storage's
  * adapters. Failure messages are masked (L-10).
  */
class EmbeddingWorkerEmulatorSuite extends MaskedFailures {
  import EmbeddingWorkerEmulatorSuite._

  private val RequiresEmulator = new munit.Tag("RequiresEmulator")
  override val munitTimeout: Duration = 60.seconds

  private final case class Ctx(
      cfg: StorageConfig,
      config: ScholarChainConfig,
      clients: AwsSdkClients,
      env: AwsStorageEnvironment,
      writer: EmulatorResultWriter,
      runTag: String
  )
  @volatile private var ctx: Option[Ctx] = None

  override def maskedNames: Iterable[String] =
    ctx.toList.flatMap(c => c.cfg.zones.values.map(_.bucket) ++ List(c.cfg.vectorBucket))

  override def beforeAll(): Unit = {
    val env = sys.env
    def refuse(rule: String): Nothing = {
      EmulatorResultWriter.writePreflight(EmulatorSafety.usableResultsDir(env), SuiteName, rule)
      throw new MaskedTestFailure(
        s"emulator safety contract refused before any request: rule $rule (no client built)"
      )
    }
    val target = EmulatorSafety.check(env).fold(refuse, identity)
    val credentials = EmulatorClients
      .credentials(target)
      .fold(_ => refuse(EmulatorSafety.SuiteRules.CredentialsFileRead), identity)
    val loaded = ConfigLoader
      .loadFromEnvironment(env)
      .left
      .map(_.message)
      .flatMap(l => StorageConfig.from(l.config).left.map(_.mkString("; ")).map(s => (l.config, s)))
      .fold(_ => refuse(EmulatorSafety.SuiteRules.ConfigFileLoad), identity)
    val (config, cfg) = loaded
    val writer = EmulatorResultWriter
      .open(target.resultsDir, SuiteName)
      .fold(
        r => throw new MaskedTestFailure(s"results file refused: $r (use a new run folder)"),
        identity
      )
    val settings = AwsClientSettings(cfg.region, 3000, 5000, 5000, AwsClientSettings.SdkMaxAttempts)
    val clients = EmulatorClients.forEmulator(target, settings, cfg, credentials)
    val storageEnv = AwsStorageEnvironment.create(cfg, clients, None, Sleeper.none)
    ctx = Some(Ctx(cfg, config, clients, storageEnv, writer, target.runId))
  }

  override def afterAll(): Unit = ctx.foreach(_.clients.close())

  private def queueUrl(sqs: SqsClient, chain: EmulatorChain, queueName: String): Option[String] =
    chain("sqs", "GetQueueUrl")(
      sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl()
    )(u => u != null && u.startsWith("https://"))

  test(
    "worker dead letter: the services' embedding loop releases a poison message at once on every receive until the dead-letter queue holds it; no embedding call"
      .tag(RequiresEmulator)
  ) {
    val c = ctx.getOrElse(fail("no emulator context: the preflight did not pass"))
    val rec = new EmulatorRecorder(c.writer, "worker-dead-letter")
    val sqs = c.clients.sqs.getOrElse(fail("the emulator clients have no SQS client"))
    val chain = new EmulatorChain(rec)
    val main = queueUrl(sqs, chain, EmbeddingQueue)
    val dlq = queueUrl(sqs, chain, EmbeddingDeadLetterQueue)
    val maxReceive = main
      .flatMap { u =>
        rec
          .call("sqs", "GetQueueAttributes", (n: Int) => s"maxReceiveCount $n")(
            EmulatorAdaptersEmulatorSuite.maxReceiveCountOf(
              sqs
                .getQueueAttributes(
                  GetQueueAttributesRequest
                    .builder()
                    .queueUrl(u)
                    .attributeNames(QueueAttributeName.REDRIVE_POLICY)
                    .build()
                )
                .attributes()
                .get(QueueAttributeName.REDRIVE_POLICY)
            )
          )(n => n >= 1 && n <= 10)
          .toOption
      }
      .getOrElse(EmulatorAdaptersEmulatorSuite.AssumedMaxReceiveCount)
    val embedder = new RefusingEmbedder
    var verified = false
    (main, dlq) match {
      case (Some(mainUrl), Some(dlqUrl)) =>
        val ownId = s"emu-${c.runTag}-poison"
        // the poison: storage's job-queue envelope `{"id", "payload"}` is not embedding-task-v1
        // (no `schema`), so rag refuses it as `missing-field` before any store or embedding call
        chain("sqs", "SendMessageBatch")(
          new SqsWorkQueue(sqs, mainUrl, 1, None).enqueue(List(WorkItem(ownId, "poison")))
        )(_ == 1)
        val queues = for {
          q <- ServiceStores.embeddingQueue(c.env, mainUrl, 1)
          d <- ServiceStores.embeddingQueue(c.env, dlqUrl, 1)
        } yield (q, d)
        queues match {
          case Left(_) =>
            rec.observed(
              "application",
              "EmbeddingQueue",
              EmulatorOutcome.ImplementedFailed,
              None,
              0L,
              "queue adapter refused the URL",
              "queue-url-refused"
            )
          case Right((rawMain, rawDlq)) if !chain.isBroken =>
            val queue = new RecordingReceiveQueue(rawMain, rec, ownId)
            val sink = new MemoryLogSink
            val log = new ServiceLog(sink, "worker", Clock.systemUTC(), new Redactor(Nil))
            val indexes = c.config.vectors.toList.flatMap(_.indexNames).toSet
            val batch = math.max(
              1,
              math.min(
                c.config.embedding.map(_.batchSize).getOrElse(1),
                VectorReembedder.MaxTasksPerCall
              )
            )
            // rag's public constructor over storage's production re-embed path; never fromConfig
            val handler = new ReembedHandler(
              c.env.reembedder(embedder, Sleeper.none).reembed,
              indexes,
              batch,
              line => log.external(line, ReembedHandler.LogEvent)
            )
            val worker = new EmbeddingWorker(queue, handler, log, indexes)
            val deadline = System.nanoTime() + LoopNanos
            var polls = 0
            var emptyAfterMax = 0
            while (
              rec.failures.isEmpty && emptyAfterMax < 2 && polls < maxReceive + 6 &&
              System.nanoTime() < deadline
            ) {
              polls += 1
              val before = queue.ownReceives.get
              worker.processOne()
              if (queue.ownReceives.get == before && before >= maxReceive) emptyAfterMax += 1
            }
            val seen = queue.ownReceives.get
            var found: Option[ReceivedWork] = None
            if (rec.failures.isEmpty) {
              var dlqPolls = 0
              while (found.isEmpty && dlqPolls < 5 && !chain.isBroken) {
                dlqPolls += 1
                chain("sqs", "ReceiveMessage")(rawDlq.receive(10))().foreach(_.foreach { w =>
                  if (found.isEmpty && isOwn(w, ownId)) found = Some(w)
                  else chain("sqs", "ChangeMessageVisibility")(rawDlq.release(w))()
                })
              }
              found.foreach(w =>
                chain("sqs", "DeleteMessage", (_: Unit) => s"dead letter after $seen receives")(
                  rawDlq.ack(w)
                )()
              )
            }
            def wrong(observation: String, code: String): Unit =
              rec.observed(
                "application",
                "EmbeddingWorker",
                EmulatorOutcome.ImplementedFailed,
                None,
                0L,
                observation,
                code
              )
            if (embedder.calls.get > 0) wrong("the embedder was called", "embedder-called")
            if (queue.ownAcks.get > 0) wrong("the poison was acknowledged", "poison-acknowledged")
            if (rec.failures.isEmpty && !chain.isBroken) {
              if (queue.ownReleases.get != seen)
                wrong(s"released ${queue.ownReleases.get} of $seen receives", "poison-not-released")
              if (seen != maxReceive)
                wrong(
                  s"received $seen times, maxReceiveCount $maxReceive",
                  "receive-count-mismatch"
                )
              if (found.isEmpty)
                wrong(s"not on the dead-letter queue after $seen receives", "dead-letter-missing")
            }
            val ownLines = sink.all.count(l =>
              l.value.get("event").exists(_.strOpt.contains(ReembedHandler.LogEvent)) &&
                l.value.get("action").exists(_.strOpt.contains("release"))
            )
            verified = rec.failures.isEmpty && found.nonEmpty && seen == maxReceive &&
              queue.ownReleases.get == seen && queue.ownAcks.get == 0 &&
              embedder.calls.get == 0 && ownLines >= seen
          case Right(_) => () // a queue lookup failed: recorded by the chain
        }
      case _ => () // a queue lookup failed: recorded by the chain
    }
    val reason = rec.recorded
      .find(_.outcome != EmulatorOutcome.ImplementedPassed)
      .map(l => s"${l.service}.${l.operation} ${l.outcome}")
    val demonstrated = verified && rec.allPassed
    c.writer.demonstration(
      if (demonstrated) "demonstration dead-letter: demonstrated"
      else
        s"demonstration dead-letter: not demonstrated (${reason.getOrElse("final assertion failed")})"
    )
    assertEquals(embedder.calls.get, 0L, "the embedder must never be called for a poison message")
    if (rec.failures.nonEmpty) fail(s"emulator operations failed: ${rec.failureSummary}")
  }
}

object EmbeddingWorkerEmulatorSuite {
  val SuiteName = "EmbeddingWorkerEmulatorSuite"

  /** Fixed emulator-only names (setup.sh; scripts/emulator/OPERATIONS.md "Resource names created by
    * setup.sh"). The storage suite uses the job queue pair, so the two suites never share a queue.
    */
  val EmbeddingQueue = "scholarchain-emu-embedding"
  val EmbeddingDeadLetterQueue = "scholarchain-emu-embedding-dlq"

  /** The receive-and-release loop stops after 40 s (inside the 60 s test bound). */
  val LoopNanos: Long = 40L * 1000L * 1000L * 1000L

  /** Whether `w` is this run's poison message (its raw body carries the run-tagged ID). */
  def isOwn(w: ReceivedWork, ownId: String): Boolean =
    Option(w.item.payload).exists(_.contains("\"" + ownId + "\""))

  /** An embedder that records every call and refuses it: the poison path must never embed. */
  final class RefusingEmbedder extends EmbeddingPort {
    val calls = new AtomicLong()
    def modelId: String = "emulator-refusing-embedder-v1"
    def dimensions: Int = 1024
    def maxBatchSize: Int = 1
    def embed(texts: IndexedSeq[String]): Either[EmbeddingFailure, EmbeddingBatch] = {
      calls.incrementAndGet()
      throw new IllegalStateException("the embedder was called on the poison path")
    }
  }

  /** The production receive queue with one result line per call (its outcome class by storage's
    * [[EmulatorFailure.classify]]; the call's result or exception passes through unchanged, so the
    * worker behaves as over the bare adapter) and counts of this run's poison message.
    */
  final class RecordingReceiveQueue(u: ReceiveQueue, rec: EmulatorRecorder, ownId: String)
      extends ReceiveQueue {
    val ownReceives = new AtomicInteger()
    val ownReleases = new AtomicInteger()
    val ownAcks = new AtomicInteger()

    private def recorded[A](operation: String)(f: => A): A = {
      val t0 = System.nanoTime()
      def ms = (System.nanoTime() - t0) / 1000000L
      try {
        val a = f
        rec.observed("sqs", operation, EmulatorOutcome.ImplementedPassed, None, ms, "")
        a
      } catch {
        case NonFatal(e) =>
          val (cls, status, code) = EmulatorFailure.classify(e)
          rec.observed("sqs", operation, cls, status, ms, "", code)
          throw e
      }
    }

    def receive(max: Int): List[ReceivedWork] = {
      val got = recorded("ReceiveMessage")(u.receive(max))
      ownReceives.addAndGet(got.count(isOwn(_, ownId)))
      got
    }
    def ack(work: ReceivedWork): Unit = {
      if (isOwn(work, ownId)) ownAcks.incrementAndGet()
      recorded("DeleteMessage")(u.ack(work))
    }
    def release(work: ReceivedWork): Unit = {
      recorded("ChangeMessageVisibility")(u.release(work))
      if (isOwn(work, ownId)) ownReleases.incrementAndGet()
    }
  }
}
