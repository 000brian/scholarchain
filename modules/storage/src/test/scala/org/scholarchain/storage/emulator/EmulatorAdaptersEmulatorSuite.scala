package org.scholarchain.storage.emulator

import java.nio.charset.StandardCharsets

import scala.collection.immutable.ListMap
import scala.concurrent.duration._
import scala.util.control.NonFatal

import org.scholarchain.core._
import org.scholarchain.core.config.ConfigLoader
import org.scholarchain.storage._
import org.scholarchain.storage.aws._
import org.scholarchain.storage.ingest.{ReembedOutcome, ReembedTask, TextStore, VectorReembedder}
import org.scholarchain.storage.local.{LocalStorageEnvironment, StubEmbedder}
import org.scholarchain.storage.ports._
import org.scholarchain.storage.s3.S3KeyLayout
import org.scholarchain.storage.vectors.IndexSpecCatalog
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  GetQueueAttributesRequest,
  GetQueueUrlRequest,
  QueueAttributeName
}

/** The storage adapters against the local AWS Emulator (Phase 16, decision 6.6). Every test is
  * tagged `RequiresEmulator`: excluded from `Test` (so from `sbt check`) and `AwsTest`; run only by
  * the human at E14 with `sbt storage/EmulatorTest/test` (a forked JVM that trusts the emulator
  * certificate, build.sbt), after `source scripts/emulator/env.sh` and `scripts/emulator/setup.sh`.
  *
  * Before any client is built, `beforeAll` checks the emulator safety contract
  * ([[EmulatorSafety.check]], identical to `scripts/emulator/lib.sh`), then reads the emulator
  * credentials file and `SCHOLARCHAIN_CONFIG_FILE` (`config/emulator.json`). A refusal sends no
  * request, writes one `preflight` line (class `infrastructure-failure`, the rule name, no value)
  * when the results folder is usable, and fails the suite.
  *
  * Clients: [[EmulatorClients.forEmulator]] (loopback endpoint, host-prefix injection off,
  * path-style S3, static credentials from the emulator file, one SDK attempt). Each operation
  * writes one result line to `results-EmulatorAdaptersEmulatorSuite.jsonl` in
  * `SCHOLARCHAIN_EMULATOR_RESULTS_DIR` (only when set). A test fails on `implemented-failed` and
  * `infrastructure-failure`; it passes on `not-implemented` and `not-exercised` as a recorded
  * compatibility gap, never as validated functionality. The exit code says only that the suite ran.
  *
  * State isolation: every key, message body, `taskId` and record carries the run tag (from
  * `SCHOLARCHAIN_EMULATOR_RUN_ID`, else generated), and the tests assert only on items carrying it.
  *
  * Neptune is left out: the emulator documentation says nothing about `neptunedata`, and resources
  * with an endpoint of their own listen on a separate port, which the one-endpoint safety contract
  * refuses (scripts/emulator/OPERATIONS.md, rows "Neptune data" and S6;
  * https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-docker.html).
  * `config/emulator.json` has no Neptune section, so the environment's graph is not configured.
  *
  * Bounds (as `AwsAdaptersAwsSuite`): at most 2 objects, 2 items, 2 vectors and a few messages per
  * test; 5-second API call timeouts; 60 seconds per test. Failure messages are masked (L-10).
  */
class EmulatorAdaptersEmulatorSuite extends MaskedFailures {
  import EmulatorAdaptersEmulatorSuite._

  private val RequiresEmulator = new munit.Tag("RequiresEmulator")
  override val munitTimeout: Duration = 60.seconds

  private final case class Ctx(
      target: EmulatorSafety.Target,
      cfg: StorageConfig,
      clients: AwsSdkClients,
      writer: EmulatorResultWriter,
      runTag: String,
      run: RunId
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
    val cfg = ConfigLoader
      .loadFromEnvironment(env)
      .left
      .map(_.message)
      .flatMap(l => StorageConfig.from(l.config).left.map(_.mkString("; ")))
      .fold(_ => refuse(EmulatorSafety.SuiteRules.ConfigFileLoad), identity)
    val writer = EmulatorResultWriter
      .open(target.resultsDir, SuiteName)
      .fold(
        r => throw new MaskedTestFailure(s"results file refused: $r (use a new run folder)"),
        identity
      )
    val settings = AwsClientSettings(cfg.region, 3000, 5000, 5000, AwsClientSettings.SdkMaxAttempts)
    val clients = EmulatorClients.forEmulator(target, settings, cfg, credentials)
    val runTag = target.runId
    val run = StorageVersion.runId(
      "storage.emulator-itest",
      ContentHash.of(s"emulator-run:$runTag"),
      cfg.fingerprint
    )
    ctx = Some(Ctx(target, cfg, clients, writer, runTag, run))
  }

  override def afterAll(): Unit = ctx.foreach(_.clients.close())

  /** One emulator test: its operations are recorded under `step`; it fails on implemented-failed or
    * infrastructure-failure lines only.
    */
  private def emulatorTest(name: String, step: String)(
      body: (Ctx, EmulatorRecorder) => Unit
  ): Unit =
    test(name.tag(RequiresEmulator)) {
      val c = ctx.getOrElse(fail("no emulator context: the preflight did not pass"))
      val rec = new EmulatorRecorder(c.writer, step)
      body(c, rec)
      if (rec.failures.nonEmpty) fail(s"emulator operations failed: ${rec.failureSummary}")
    }

  private def name(o: Any): String = o.getClass.getSimpleName.stripSuffix("$")

  private def queueUrl(sqs: SqsClient, chain: EmulatorChain, queueName: String): Option[String] =
    chain("sqs", "GetQueueUrl")(
      sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl()
    )(u => u != null && u.startsWith("https://"))

  // ---- the bounded tests of AwsAdaptersAwsSuite ----------------------------------------------

  emulatorTest("S3: put-if-absent is idempotent and never overwrites", "s3-put-if-absent") {
    (c, rec) =>
      val store = new S3ObjectStore(c.clients.s3)
      val ref = new S3KeyLayout(c.cfg.zones)
        .logObject("itest", "itest", c.run, "storage-emulator", s"probe-${c.runTag}.json")
      val bytes = s"""{"probe":"${c.runTag}"}""".getBytes(StandardCharsets.UTF_8)
      val chain = new EmulatorChain(rec)
      chain("s3", "PutObject", name)(store.putIfAbsent(ref, bytes, "application/json", Map.empty))(
        o => o == PutOutcome.Created || o == PutOutcome.AlreadyPresent
      )
      chain("s3", "PutObject", name)(store.putIfAbsent(ref, bytes, "application/json", Map.empty))(
        _ == PutOutcome.AlreadyPresent
      )
      chain("s3", "PutObject", name)(
        store.putIfAbsent(ref, "{}".getBytes(StandardCharsets.UTF_8), "application/json", Map.empty)
      )(_.isInstanceOf[PutOutcome.Conflict])
      chain("s3", "ListObjectsV2")(store.list(ref.bucket, ref.key, 2))(_.map(_.ref) == List(ref))
  }

  emulatorTest("DynamoDB: conditional writes on RunLedger", "dynamodb-conditional") { (c, rec) =>
    val kv = new DynamoDbKeyValueStore(c.clients.dynamoDb)
    val t = c.cfg.dynamoDb.runLedgerTable
    val i = Item(
      Item.Pk -> AttrValue.S(s"run#itest#${c.run.value}#${c.runTag}"),
      Item.Sk -> AttrValue.S("itest"),
      "v" -> AttrValue.n(1L)
    )
    val chain = new EmulatorChain(rec)
    chain("dynamodb", "PutItem", name)(kv.putIfAbsent(t, i))(_ == ConditionalResult.Written)
    chain("dynamodb", "PutItem", name)(kv.putIfAbsent(t, i))(_ == ConditionalResult.ConditionFailed)
    chain("dynamodb", "GetItem")(kv.get(t, i.key))(_.map(_.key).contains(i.key))
    chain("dynamodb", "Query")(kv.query(t, i.key.pk, None, 2, descending = false))(
      _.map(_.key) == List(i.key)
    )
  }

  emulatorTest(
    "S3 Vectors: indexes match the index spec; a 2-vector put/get/query round trip",
    "s3vectors-round-trip"
  ) { (c, rec) =>
    val spec = IndexSpecCatalog.load()
    val idx = new S3VectorsIndex(
      c.clients.s3Vectors,
      c.cfg.vectorBucket,
      n => spec.forName(n).map(_.nonFilterableKeys.toSet).getOrElse(Set.empty)
    )
    val chain = new EmulatorChain(rec)
    spec.indexes.foreach { s =>
      chain("s3vectors", "GetIndex", (_: Option[IndexDescription]) => s.name)(
        idx.describeIndex(s.name)
      )(
        _.exists(d =>
          (d.dimension, d.distanceMetric, d.nonFilterableKeys) ==
            (spec.dimension, spec.distanceMetric, s.nonFilterableKeys.toSet)
        )
      )
    }
    val index = spec.indexes.head.name
    val meta = StoredMetadata(
      ListMap("documentType" -> MetaValue.Str("researcher_profile")),
      ListMap("runId" -> MetaValue.Str(c.run.value))
    )
    val vs = List("a", "b")
      .map(s => s"itest:${c.runTag}:$s")
      .map(k => VectorRecord(k, StubEmbedder.vector(k, spec.dimension), meta))
    chain("s3vectors", "PutVectors")(idx.putVectors(index, vs))()
    chain("s3vectors", "GetVectors")(idx.getVectors(index, vs.map(_.key), returnData = false))(
      _.map(_.key).toSet == vs.map(_.key).toSet
    )
    // only own items are asserted: the nearest of vector a among this run's keys is a
    chain("s3vectors", "QueryVectors")(
      idx.query(index, vs.head.data, 2, None, returnMetadata = true)
    )(hits => hits.filter(h => vs.exists(_.key == h.key)).headOption.exists(_.key == vs.head.key))
  }

  emulatorTest(
    "AwsStorageEnvironment: one bounded read per store through the emulator clients",
    "environment-reads"
  ) { (c, rec) =>
    // bounds: 1 GetItem, 1 HeadObject, 1 GetIndex; no write. The environment shares the suite's
    // clients and is not closed here (afterAll closes them).
    val env = AwsStorageEnvironment.create(c.cfg, c.clients, None, Sleeper.none)
    assertEquals(env.graph.left.toOption, Some("neptune section is not configured"))
    val chain = new EmulatorChain(rec)
    val missing = ItemKey(s"run#itest-missing#${c.run.value}#${c.runTag}", "itest")
    chain("dynamodb", "GetItem")(env.kv.get(c.cfg.dynamoDb.runLedgerTable, missing))(_.isEmpty)
    val ref = new S3KeyLayout(c.cfg.zones)
      .logObject("itest", "itest", c.run, "storage-emulator", s"absent-${c.runTag}.json")
    chain("s3", "HeadObject")(env.objects.head(ref))(_.isEmpty)
    chain("s3vectors", "GetIndex")(env.vectors.describeIndex(c.cfg.vectors.researcherProfileIndex))(
      _.nonEmpty
    )
  }

  emulatorTest("SQS: one message round trip on the job queue", "sqs-round-trip") { (c, rec) =>
    val sqs = c.clients.sqs.get
    val chain = new EmulatorChain(rec)
    queueUrl(sqs, chain, JobQueue).foreach { url =>
      val q = new SqsWorkQueue(sqs, url, 1, None)
      val id = s"itest:${c.runTag}:round-trip"
      chain("sqs", "SendMessageBatch")(q.enqueue(List(WorkItem(id, "{}"))))(_ == 1)
      var mine: Option[ReceivedWork] = None
      var polls = 0
      while (mine.isEmpty && polls < 3 && !chain.isBroken) {
        polls += 1
        chain("sqs", "ReceiveMessage")(q.receive(10))().foreach(_.foreach { w =>
          if (w.item.id == id && mine.isEmpty) mine = Some(w)
          else chain("sqs", "ChangeMessageVisibility")(q.release(w))()
        })
      }
      mine match {
        case Some(w) => chain("sqs", "DeleteMessage")(q.ack(w))()
        case None    =>
          if (!chain.isBroken)
            rec.observed(
              "sqs",
              "ReceiveMessage",
              EmulatorOutcome.ImplementedFailed,
              None,
              0L,
              "own message not received in 3 polls",
              "own-message-missing"
            )
      }
    }
  }

  // ---- the SQS dead-letter test ---------------------------------------------------------------

  emulatorTest(
    "SQS dead letter: a message released at once on every receive reaches the dead-letter queue after maxReceiveCount",
    "sqs-dead-letter"
  ) { (c, rec) =>
    val sqs = c.clients.sqs.get
    val chain = new EmulatorChain(rec)
    val main = queueUrl(sqs, chain, JobQueue)
    val dlq = queueUrl(sqs, chain, JobDeadLetterQueue)
    // the queue's own maxReceiveCount (setup.sh: 5); a failed read is recorded and 5 is assumed
    val maxReceive = main
      .flatMap { u =>
        rec
          .call("sqs", "GetQueueAttributes", (n: Int) => s"maxReceiveCount $n")(
            maxReceiveCountOf(
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
      .getOrElse(AssumedMaxReceiveCount)
    (main, dlq) match {
      case (Some(mainUrl), Some(dlqUrl)) =>
        val q = new SqsWorkQueue(sqs, mainUrl, 1, None)
        val id = s"itest:${c.runTag}:dead-letter"
        chain("sqs", "SendMessageBatch")(q.enqueue(List(WorkItem(id, "{}"))))(_ == 1)
        val deadline = System.nanoTime() + DeadLetterLoopNanos
        var seen = 0
        var emptyAfterMax = 0
        var polls = 0
        while (
          !chain.isBroken && emptyAfterMax < 2 && polls < maxReceive + 6 &&
          System.nanoTime() < deadline
        ) {
          polls += 1
          chain("sqs", "ReceiveMessage")(q.receive(10))().foreach { got =>
            // the adapter's own release call, at once, for every message received
            got.foreach(w => chain("sqs", "ChangeMessageVisibility")(q.release(w))())
            if (got.exists(_.item.id == id)) seen += 1
            else if (seen >= maxReceive) emptyAfterMax += 1
          }
        }
        if (!chain.isBroken) {
          val dq = new SqsWorkQueue(sqs, dlqUrl, 1, None)
          var found: Option[ReceivedWork] = None
          var dlqPolls = 0
          while (found.isEmpty && dlqPolls < 5 && !chain.isBroken) {
            dlqPolls += 1
            chain("sqs", "ReceiveMessage")(dq.receive(10))().foreach(_.foreach { w =>
              if (w.item.id == id && found.isEmpty) found = Some(w)
              else chain("sqs", "ChangeMessageVisibility")(dq.release(w))()
            })
          }
          (found, chain.isBroken) match {
            case (Some(w), _) =>
              chain("sqs", "DeleteMessage", (_: Unit) => s"dead letter after $seen receives")(
                dq.ack(w)
              )()
              if (seen != maxReceive)
                rec.observed(
                  "sqs",
                  "ReceiveMessage",
                  EmulatorOutcome.ImplementedFailed,
                  None,
                  0L,
                  s"received $seen times, maxReceiveCount $maxReceive",
                  "receive-count-mismatch"
                )
            case (None, false) =>
              rec.observed(
                "sqs",
                "ReceiveMessage",
                EmulatorOutcome.ImplementedFailed,
                None,
                0L,
                s"not on the dead-letter queue after $seen receives",
                "dead-letter-missing"
              )
            case (None, true) => ()
          }
        }
      case _ => () // a queue lookup failed: recorded by the chain
    }
  }

  // ---- the resource-policy probe ---------------------------------------------------------------

  emulatorTest(
    "resource-policy probe: the deny-all bucket's PutObject and GetObject outcomes are recorded, not asserted",
    "s3-policy-probe"
  ) { (c, rec) =>
    val store = new S3ObjectStore(c.clients.s3)
    val ref = ObjectRef(PolicyProbeBucket, s"itest/policy-probe/${c.runTag}.json")
    def probe(operation: String)(f: => Any): Unit = {
      val t0 = System.nanoTime()
      def ms = (System.nanoTime() - t0) / 1000000L
      try {
        f
        rec.observed(
          "s3",
          operation,
          EmulatorOutcome.ImplementedPassed,
          None,
          ms,
          "allowed (bucket policy not evaluated)"
        )
      } catch {
        case NonFatal(e) =>
          EmulatorFailure.classify(e) match {
            case (EmulatorOutcome.ImplementedFailed, Some(403), code) =>
              rec.observed(
                "s3",
                operation,
                EmulatorOutcome.ImplementedPassed,
                Some(403),
                ms,
                s"refused ($code)",
                code
              )
            case (EmulatorOutcome.ImplementedFailed, status, code @ "NoSuchBucket") =>
              rec.observed(
                "s3",
                operation,
                EmulatorOutcome.NotExercised,
                status,
                ms,
                "policy probe bucket missing (setup)",
                code
              )
            case (cls, status, code) =>
              rec.observed("s3", operation, cls, status, ms, "", code)
          }
      }
    }
    probe("PutObject")(
      store.putIfAbsent(
        ref,
        s"""{"probe":"${c.runTag}"}""".getBytes(StandardCharsets.UTF_8),
        "application/json",
        Map.empty
      )
    )
    probe("GetObject")(store.get(ref))
  }

  // ---- the re-embed demonstration ----------------------------------------------------------------

  emulatorTest(
    "re-embed demonstration: VectorReembedder with the StubEmbedder over the emulator; a second delivery is replayed",
    "reembed-demonstration"
  ) { (c, rec) =>
    val spec = IndexSpecCatalog.load()
    val layout = new S3KeyLayout(c.cfg.zones)
    val model = "storage-stub-sha256-v1"
    val doc = emulatorProfileDoc(c.runTag, c.cfg)
    // realistic metadata: the real ingest pipeline, in memory, under the emulator configuration
    val local = LocalStorageEnvironment.create(c.cfg, None)
    val written = local
      .pipeline(new StubEmbedder(model, spec.dimension, 8), "dblp")
      .run(c.run, Iterator(doc))
      .written
    assertEquals(written, 1L)
    val stored = local.vectors
      .getVectors(doc.indexName, List(doc.vectorKey), returnData = true)
      .headOption
      .getOrElse(fail("the local ingest wrote no vector"))
    val textUri = stored.metadata.all
      .get("textS3Uri")
      .collect { case MetaValue.Str(s) => s }
      .getOrElse(fail("no textS3Uri"))
    val newData = StubEmbedder.vector(doc.text, spec.dimension)
    val oldData = newData.map(x => -x)

    val rawObjects = new S3ObjectStore(c.clients.s3)
    val rawKv = new DynamoDbKeyValueStore(c.clients.dynamoDb)
    val rawVectors = new S3VectorsIndex(
      c.clients.s3Vectors,
      c.cfg.vectorBucket,
      n => spec.forName(n).map(_.nonFilterableKeys.toSet).getOrElse(Set.empty)
    )
    val chain = new EmulatorChain(rec)
    // one text object and one vector holding other data under the same metadata
    chain("s3", "PutObject", (p: org.scholarchain.storage.ingest.TextPut) => name(p.outcome))(
      new TextStore(rawObjects, layout).put(doc.indexName, doc.text)
    )(p => p.ref.uri == textUri && !p.outcome.isInstanceOf[PutOutcome.Conflict])
    chain("s3vectors", "PutVectors")(
      rawVectors.putVectors(
        doc.indexName,
        List(VectorRecord(doc.vectorKey, oldData, stored.metadata))
      )
    )()

    val task = ReembedTask(s"emu-${c.runTag}-reembed-1", doc.indexName, doc.vectorKey)
    val embedder = new StubEmbedder(model, spec.dimension, 8)
    var first: Option[ReembedOutcome] = None
    var second: Option[ReembedOutcome] = None
    var verified = false
    if (!chain.isBroken) {
      val reembedder = new VectorReembedder(
        embedder,
        new RecordingVectorIndex(rawVectors, rec),
        new TextStore(new RecordingObjectStore(rawObjects, rec), layout),
        new RecordingKeyValueStore(rawKv, rec),
        c.cfg.dynamoDb.querySessionTable,
        spec,
        RetryPolicy(0, 1, 1),
        Sleeper.none
      )
      first = Some(reembedder.reembed(List(task)).head)
      first match {
        case Some(p: ReembedOutcome.Processed) if p.completionRecorded =>
          val hash = p.textHash
          val dataOk = chain("s3vectors", "GetVectors")(
            rawVectors.getVectors(doc.indexName, List(doc.vectorKey), returnData = true)
          )(vs =>
            vs.size == 1 && vs.head.data.sameElements(
              newData
            ) && vs.head.metadata == stored.metadata
          ).nonEmpty
          val recordOk = chain("dynamodb", "GetItem")(
            rawKv.get(
              c.cfg.dynamoDb.querySessionTable,
              VectorReembedder.completionKey(task.taskId, hash)
            )
          )(
            _.exists(i =>
              i.strOpt("taskId").contains(task.taskId) && i.strOpt("textHash").contains(hash)
            )
          ).nonEmpty
          if (dataOk && recordOk) {
            second = Some(reembedder.reembed(List(task)).head)
            verified =
              second.exists(_.isInstanceOf[ReembedOutcome.Replayed]) && embedder.calls == 1L
            if (!verified && rec.failures.isEmpty && rec.allPassed)
              rec.observed(
                "application",
                "ReembedDelivery",
                EmulatorOutcome.ImplementedFailed,
                None,
                0L,
                "second delivery not replayed",
                EmulatorResultLine.safeWord(second.map(_.code).getOrElse("none"))
              )
          }
        case Some(other) if rec.allPassed =>
          // every store call answered, yet the delivery did not complete: a wrong result
          rec.observed(
            "application",
            "ReembedDelivery",
            EmulatorOutcome.ImplementedFailed,
            None,
            0L,
            "first delivery not processed",
            EmulatorResultLine.safeWord(other.code)
          )
        case _ => () // a store call did not pass: its own line says why
      }
    }
    val reason =
      rec.recorded
        .find(_.outcome != EmulatorOutcome.ImplementedPassed)
        .map(l => s"${l.service}.${l.operation} ${l.outcome}")
    val demonstrated = verified && rec.allPassed
    c.writer.demonstration(
      if (demonstrated) "demonstration re-embed: demonstrated"
      else
        s"demonstration re-embed: not demonstrated (${reason.getOrElse("final assertion failed")})"
    )
  }
}

object EmulatorAdaptersEmulatorSuite {
  val SuiteName = "EmulatorAdaptersEmulatorSuite"

  /** Fixed emulator-only names (setup.sh; no configuration key holds a queue or probe bucket name;
    * scripts/emulator/OPERATIONS.md "Resource names created by setup.sh").
    */
  val JobQueue = "scholarchain-emu-jobs"
  val JobDeadLetterQueue = "scholarchain-emu-jobs-dlq"
  val PolicyProbeBucket = "scholarchain-emu-policy-probe"

  /** setup.sh's maxReceiveCount, used only when the queue's RedrivePolicy cannot be read. */
  val AssumedMaxReceiveCount = 5

  /** The receive-and-release loop stops after 40 s (inside the 60 s test bound). */
  val DeadLetterLoopNanos: Long = 40L * 1000L * 1000L * 1000L

  /** `maxReceiveCount` of a RedrivePolicy JSON document (a number or a numeric string). */
  def maxReceiveCountOf(policy: String): Int =
    ujson.read(policy).obj("maxReceiveCount") match {
      case ujson.Num(n) => n.toInt
      case ujson.Str(s) => s.trim.toInt
      case other        =>
        throw new IllegalArgumentException(s"maxReceiveCount is ${other.getClass.getSimpleName}")
    }

  /** A researcher-profile document unique to `runTag` (its work key, vector key, text and text hash
    * all differ per run), built like the storage test fixtures.
    */
  def emulatorProfileDoc(runTag: String, cfg: StorageConfig): SemanticDocument = {
    val tag = Hashing.sha256Hex(runTag.getBytes(StandardCharsets.UTF_8)).take(12)
    val r = ResearcherId.fromAnchorMention(MentionId.of(WorkId.fromDblpKey(s"conf/emu/K$tag"), 0))
    val e = TestFixtures.summary(
      EvidenceType.Metric,
      List(r.value),
      s"Emulator researcher $tag (${r.value}), window 2015-2025. Publications P=2.",
      Some(MetricValue(CollaborationMetric.PublicationCount, 2))
    )
    SummaryDocuments
      .build("researcher_profile", TestFixtures.Full, e, cfg.vectors)
      .fold(err => throw new IllegalStateException(err.toString), identity)
  }
}
