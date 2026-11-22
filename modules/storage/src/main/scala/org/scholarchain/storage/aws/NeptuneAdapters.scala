package org.scholarchain.storage.aws

import java.net.URI
import java.time.Duration

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters._

import org.scholarchain.storage.StoreException
import org.scholarchain.storage.neptune.CypherTemplates
import org.scholarchain.storage.ports._
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkException,
  SdkServiceException
}
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model
import software.amazon.awssdk.services.neptunedata.model._

object NeptuneClients {

  /** A Neptune data-plane client for `https://<endpoint>:<port>` (both from configuration). */
  def create(settings: AwsClientSettings, endpoint: String, port: Int): NeptunedataClient = {
    require(endpoint != null && endpoint.matches("[A-Za-z0-9.-]+"), "invalid Neptune endpoint host")
    NeptunedataClient
      .builder()
      .region(settings.awsRegion)
      .endpointOverride(URI.create(s"https://$endpoint:$port"))
      .httpClient(settings.httpClient())
      .overrideConfiguration(settings.overrides())
      .build()
  }

  /** JSON value of an SDK document. */
  def toJson(d: Document): ujson.Value =
    if (d == null || d.isNull) ujson.Null
    else if (d.isString) ujson.Str(d.asString())
    else if (d.isNumber) ujson.Num(d.asNumber().doubleValue())
    else if (d.isBoolean) ujson.Bool(d.asBoolean())
    else if (d.isList) ujson.Arr.from(d.asList().asScala.map(toJson))
    else ujson.Obj.from(d.asMap().asScala.toList.sortBy(_._1).map { case (k, v) => k -> toJson(v) })
}

/** Neptune bulk loader (`StartLoaderJob`, format opencypher). */
final class NeptuneBulkLoader(client: NeptunedataClient) extends GraphBulkLoader {
  def start(job: BulkLoadJob): String = {
    val b = StartLoaderJobRequest
      .builder()
      .source(job.source)
      .format(Format.fromValue(job.format))
      .s3BucketRegion(S3BucketRegion.fromValue(job.region))
      .iamRoleArn(job.iamRoleArn)
      .failOnError(job.failOnError)
      .parallelism(Parallelism.fromValue(job.parallelism))
      .queueRequest(job.queueRequest)
      .userProvidedEdgeIds(job.userProvidedEdgeIds)
    if (job.dependencies.nonEmpty) b.dependencies(job.dependencies.asJava)
    val r =
      try client.startLoaderJob(b.build())
      catch {
        case e: SdkServiceException if NeptuneBulkLoader.isDefiniteRejection(e) =>
          val name = e.getClass.getSimpleName
          throw new BulkLoadStartRejected(
            name,
            e.statusCode,
            s"StartLoaderJob refused: $name (HTTP ${e.statusCode}): ${e.getMessage}"
          )
        case e: SdkServiceException if NeptuneBulkLoader.isThrottled(e) =>
          val name = e.getClass.getSimpleName
          throw new BulkLoadStartRejected(
            name,
            e.statusCode,
            s"StartLoaderJob throttled: $name (HTTP ${e.statusCode}): ${e.getMessage}",
            throttled = true
          )
        case e: SdkException => throw AwsErrors.wrap(e)
      }
    Option(r.payload())
      .flatMap(p => Option(p.get("loadId")))
      .getOrElse(throw new IllegalStateException("no loadId in response"))
  }

  def status(loadId: String): BulkLoadStatus = {
    val r = AwsErrors.guard(
      client.getLoaderJobStatus(GetLoaderJobStatusRequest.builder().loadId(loadId).build())
    )
    val payload = NeptuneClients.toJson(r.payload())
    val overall = payload.objOpt.flatMap(_.get("overallStatus")).flatMap(_.objOpt)
    def num(k: String) = overall.flatMap(_.get(k)).flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
    BulkLoadStatus(
      loadId,
      overall.flatMap(_.get("status")).flatMap(_.strOpt).getOrElse("UNKNOWN"),
      num("totalRecords"),
      num("parsingErrors") + num("insertErrors") + num("datatypeMismatchErrors")
    )
  }
}

object NeptuneBulkLoader {

  /** A `StartLoaderJob` error is a definite refusal (no load was created) when it is one of the
    * operation's documented client errors below AND its HTTP status is 4xx other than 408 and 429.
    * Source: Neptune Data API reference, StartLoaderJob "Errors" and "Common Error Types"
    * (https://docs.aws.amazon.com/neptune/latest/data-api/API_StartLoaderJob.html,
    * https://docs.aws.amazon.com/neptune/latest/data-api/CommonErrors.html). Not definite (outcome
    * unknown, the load may exist): `ClientTimeoutException` (408), `InternalFailureException`
    * (500), every other service error, every client-side error (timeouts, I/O, interrupted calls)
    * and a 200 response without a load ID. Throttling answers (`TooManyRequestsException` 429,
    * `ThrottlingException`) are refusals of their own kind since Phase 16: see [[isThrottled]].
    */
  def isDefiniteRejection(e: SdkServiceException): Boolean = {
    val definiteClass = e match {
      case _: BadRequestException | _: BulkLoadIdNotFoundException |
          _: ConstraintViolationException | _: InvalidArgumentException |
          _: InvalidParameterException | _: LoadUrlAccessDeniedException |
          _: MissingParameterException | _: PreconditionsFailedException | _: S3Exception |
          _: AccessDeniedException =>
        true
      case _: model.IllegalArgumentException      => true
      case _: model.UnsupportedOperationException => true
      case _                                      => false
    }
    val s = e.statusCode
    definiteClass && s >= 400 && s < 500 && s != 408 && s != 429
  }

  /** A `StartLoaderJob` throttling answer (Phase 16, storage retries): `TooManyRequestsException`
    * with HTTP 429 ("Raised when the number of requests being processed exceeds the limit"; its
    * request ID is "the request that could not be processed",
    * https://docs.aws.amazon.com/neptune/latest/data-api/API_StartLoaderJob.html) or
    * `ThrottlingException` with HTTP 400 or 429 ("The request was denied due to request
    * throttling", https://docs.aws.amazon.com/neptune/latest/data-api/CommonErrors.html). With SDK
    * `maxAttempts = 1` this answer belongs to the one request sent, so no load was created: a
    * refusal that may be retried after a wait. Any other status of these classes stays unknown.
    */
  def isThrottled(e: SdkServiceException): Boolean = e match {
    case _: TooManyRequestsException => e.statusCode == 429
    case _: ThrottlingException      => e.statusCode == 400 || e.statusCode == 429
    case _                           => false
  }
}

/** Bounded retry of graph reads (Phase 16, FECD-1 recommendation S2). Only
  * [[GraphReadError.Transient]] and [[GraphReadError.ResourceLimit]] failures are retried;
  * timeouts, 4xx and every other client error never are. Every retry stays inside the read's
  * existing time budget (`runtime.graphTimeoutMillis`; for `PathBetween` the budget left for the
  * whole search): a retry starts only when, after its delay, at least `minAttemptMillis` of the
  * budget remain, and its API call timeout is the budget left. No limit or threshold is raised.
  *
  * Delay before retry `n` (1-based): `cap = min(maxDelayMillis, baseDelayMillis * 2^(n-1))`, then
  * equal jitter: `cap / 2 + (cap - cap / 2) * u` with `u` in [0, 1). The jitter spreads the retries
  * of callers that failed together (D-read: every failure fell in the first 1 to 2 s of a burst).
  *
  * @param maxRetries
  *   retries after the first attempt (0..3)
  */
final case class GraphRetry(
    maxRetries: Int,
    baseDelayMillis: Long,
    maxDelayMillis: Long,
    minAttemptMillis: Long
) {
  require(maxRetries >= 0 && maxRetries <= 3, "maxRetries must be in 0..3")
  require(baseDelayMillis >= 1, "baseDelayMillis must be >= 1")
  require(maxDelayMillis >= baseDelayMillis, "maxDelayMillis must be >= baseDelayMillis")
  require(minAttemptMillis >= 1, "minAttemptMillis must be >= 1")

  /** The delay before retry `retry` (1-based) for a jitter draw `u` in [0, 1). */
  def delayBeforeRetry(retry: Int, u: Double): Long = {
    val shift = math.min(math.max(retry - 1, 0), 20)
    val cap = math.min(maxDelayMillis, baseDelayMillis * (1L << shift))
    val half = cap / 2
    val unit = if (u.isNaN || u < 0) 0.0 else math.min(u, 0.999999)
    half + ((cap - half) * unit).toLong
  }
}

object GraphRetry {

  /** No retry: the behavior before Phase 16 (the class default, so stub-client tests that count
    * calls are unchanged).
    */
  val Off: GraphRetry = GraphRetry(0, 1, 1, 1)

  /** What [[AwsStorageEnvironment]] uses: 2 retries, 100..200 ms then 200..400 ms (at most 600 ms
    * of waiting per read, inside the 20 s budget), and no retry with less than 250 ms of budget
    * left. Basis (step D): every D-read failure was one Neptune 500 that returned within 0.76 s.
    */
  val Default: GraphRetry = GraphRetry(2, 200, 800, 250)
}

/** Counters of one [[NeptuneGraphRead]], exact under concurrent callers (atomic).
  *
  *   - `calls`: SDK requests sent (every attempt, so the real Neptune request count; SDK
  *     `maxAttempts` is 1)
  *   - `retries`: attempts after the first
  *   - `recovered`: reads that succeeded after at least one retry
  *   - `gaveUp`: reads that ended with a retryable failure (retries used up, or too little budget
  *     left for another attempt)
  *   - `transientFailures`, `resourceLimitFailures`, `timeouts`, `permanentFailures`: failed
  *     attempts by class
  */
final class GraphReadCounters {
  val calls = new java.util.concurrent.atomic.AtomicLong()
  val retries = new java.util.concurrent.atomic.AtomicLong()
  val recovered = new java.util.concurrent.atomic.AtomicLong()
  val gaveUp = new java.util.concurrent.atomic.AtomicLong()
  val transientFailures = new java.util.concurrent.atomic.AtomicLong()
  val resourceLimitFailures = new java.util.concurrent.atomic.AtomicLong()
  val timeouts = new java.util.concurrent.atomic.AtomicLong()
  val permanentFailures = new java.util.concurrent.atomic.AtomicLong()

  def toJson: ujson.Obj = ujson.Obj(
    "calls" -> calls.get.toDouble,
    "retries" -> retries.get.toDouble,
    "recovered" -> recovered.get.toDouble,
    "gaveUp" -> gaveUp.get.toDouble,
    "transientFailures" -> transientFailures.get.toDouble,
    "resourceLimitFailures" -> resourceLimitFailures.get.toDouble,
    "timeouts" -> timeouts.get.toDouble,
    "permanentFailures" -> permanentFailures.get.toDouble
  )
}

/** Read-only graph queries: only the allowlisted templates (see [[CypherTemplates]]), identifiers
  * always as parameters, bounded by [[GraphReadSettings]]. Use it with read-only credentials (Phase
  * 12 IAM).
  *
  *   - Row limits (m1): the text asks for `limit + 1` rows; the result holds at most `limit` rows
  *     and `truncated = rows > limit`.
  *   - `PathBetween` (iterative deepening): runs the exact-depth query for depth 1, then 2, up to
  *     `maxDepth`, and stops at the first depth that returns a path, or when the total elapsed time
  *     reaches `settings.timeoutMillis` (each call's API timeout is the remaining budget). The
  *     result's `complete` and `depthSearched` tell "no path within depth d" (complete) from a
  *     stopped search (not complete); `stoppedBy` says why it stopped: the time limit, or (Phase 16
  *     L1) a Neptune resource limit at the next depth.
  *   - Failures (Phase 16): [[runClassified]] gives the typed [[GraphReadError]]; [[run]] gives its
  *     message, as before. With `retry` other than [[GraphRetry.Off]], transient and resource-limit
  *     failures are retried within the time budget ([[GraphRetry]]); [[counters]] count attempts.
  *
  * Thread-safe: no mutable state besides the atomic [[counters]].
  *
  * @param clock
  *   monotonic milliseconds; tests inject a fake clock
  * @param retry
  *   default [[GraphRetry.Off]]; [[AwsStorageEnvironment]] passes [[GraphRetry.Default]]
  * @param sleeper
  *   waits between retries; tests inject a recording sleeper
  * @param jitter
  *   a draw in [0, 1) per retry; tests inject a fixed value
  */
final class NeptuneGraphRead(
    client: NeptunedataClient,
    val settings: GraphReadSettings,
    clock: () => Long = NeptuneGraphRead.monotonicMillis,
    val retry: GraphRetry = GraphRetry.Off,
    sleeper: org.scholarchain.storage.Sleeper = org.scholarchain.storage.Sleeper.real,
    jitter: () => Double = () => java.util.concurrent.ThreadLocalRandom.current().nextDouble()
) extends GraphReadPort {

  val counters = new GraphReadCounters

  private def execute(c: CypherTemplates.Compiled, timeoutMillis: Long): List[GraphRow] = {
    val r = AwsErrors.guard(
      client.executeOpenCypherQuery(
        ExecuteOpenCypherQueryRequest
          .builder()
          .openCypherQuery(c.cypher)
          .parameters(c.parameters.render())
          .overrideConfiguration(o => o.apiCallTimeout(Duration.ofMillis(timeoutMillis)))
          .build()
      )
    )
    NeptuneClients.toJson(r.results()).arrOpt.map(_.toList).getOrElse(Nil).map { row =>
      GraphRow(ListMap.from(row.objOpt.map(_.toList).getOrElse(Nil)))
    }
  }

  def run(query: GraphQuery): Either[String, GraphResult] =
    runClassified(query).left.map(_.message)

  override def runClassified(query: GraphQuery): Either[GraphReadError, GraphResult] =
    CypherTemplates.compile(query, settings).left.map(GraphReadError.Invalid(_)).flatMap {
      compiled =>
        query match {
          case q: GraphQuery.PathBetween => pathSearch(q, compiled)
          case q                         =>
            val c = compiled.head
            // the clock is read only when a retry may need the budget left
            val start = if (retry.maxRetries > 0) clock() else 0L
            attempt(c, start, settings.timeoutMillis.toLong)
              .map(rows => GraphResult.bounded(q.templateId, rows, c.limit))
        }
    }

  /** One read with the bounded retry. The first attempt uses `firstTimeout` (no clock read, so the
    * pre-Phase 16 clock sequence is unchanged); each retry reads the clock and uses the budget left
    * since `start`.
    */
  private def attempt(
      c: CypherTemplates.Compiled,
      start: Long,
      firstTimeout: Long
  ): Either[GraphReadError, List[GraphRow]] = {
    val budget = settings.timeoutMillis.toLong
    @annotation.tailrec
    def loop(n: Int, timeout: Long): Either[GraphReadError, List[GraphRow]] = {
      counters.calls.incrementAndGet()
      val outcome =
        try Right(execute(c, timeout))
        catch { case e: StoreException => Left(NeptuneGraphRead.classify(e)) }
      outcome match {
        case Right(rows) =>
          if (n > 0) counters.recovered.incrementAndGet()
          Right(rows)
        case Left(err) =>
          err match {
            case _: GraphReadError.Transient     => counters.transientFailures.incrementAndGet()
            case _: GraphReadError.ResourceLimit => counters.resourceLimitFailures.incrementAndGet()
            case _: GraphReadError.Timeout       => counters.timeouts.incrementAndGet()
            case _                               => counters.permanentFailures.incrementAndGet()
          }
          val retryable = NeptuneGraphRead.isRetryable(err)
          val next: Option[(Long, Long)] =
            if (!retryable || n >= retry.maxRetries) None
            else {
              val delay = retry.delayBeforeRetry(n + 1, jitter())
              val left = budget - (clock() - start) - delay
              if (left >= retry.minAttemptMillis) Some((delay, left)) else None
            }
          next match {
            case Some((delay, left)) =>
              counters.retries.incrementAndGet()
              sleeper.sleep(delay)
              loop(n + 1, left)
            case None =>
              if (retryable && retry.maxRetries > 0) counters.gaveUp.incrementAndGet()
              Left(err)
          }
      }
    }
    loop(0, firstTimeout)
  }

  private def pathSearch(
      q: GraphQuery.PathBetween,
      depths: List[CypherTemplates.Compiled]
  ): Either[GraphReadError, GraphResult] = {
    val start = clock()
    def stopped(searched: Int, why: GraphStop) =
      Right(
        GraphResult(
          q.templateId,
          Nil,
          truncated = false,
          complete = false,
          Some(searched),
          Some(why)
        )
      )
    @annotation.tailrec
    def loop(
        rest: List[CypherTemplates.Compiled],
        searched: Int
    ): Either[GraphReadError, GraphResult] =
      rest match {
        case Nil =>
          Right(GraphResult(q.templateId, Nil, truncated = false, complete = true, Some(searched)))
        case c :: tail =>
          val remaining = settings.timeoutMillis.toLong - (clock() - start)
          if (remaining <= 0) stopped(searched, GraphStop.TimeLimit)
          else {
            attempt(c, start, remaining) match {
              case Left(_: GraphReadError.Timeout)       => stopped(searched, GraphStop.TimeLimit)
              case Left(_: GraphReadError.ResourceLimit) =>
                stopped(searched, GraphStop.ResourceLimit)
              case Left(err)                    => Left(err)
              case Right(rows) if rows.nonEmpty =>
                Right(
                  GraphResult(
                    q.templateId,
                    rows.take(c.limit),
                    rows.size > c.limit,
                    complete = true,
                    c.depth
                  )
                )
              case Right(_) => loop(tail, c.depth.getOrElse(searched + 1))
            }
          }
      }
    loop(depths, 0)
  }
}

object NeptuneGraphRead {
  val monotonicMillis: () => Long = () => System.nanoTime() / 1000000L

  /** A client-side API call timeout or Neptune's own query time limit. */
  def isTimeout(e: StoreException): Boolean =
    Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).take(8).exists {
      case _: ApiCallTimeoutException        => true
      case _: ApiCallAttemptTimeoutException => true
      case _: TimeLimitExceededException     => true
      case _                                 => false
    }

  /** A Neptune resource-limit refusal (Phase 16 L1): `MemoryLimitExceededException` or
    * `QueryLimitExceededException`, both HTTP 500 and documented as retryable
    * (https://docs.aws.amazon.com/neptune/latest/data-api/API_ExecuteOpenCypherQuery.html).
    */
  def isResourceLimit(e: StoreException): Boolean =
    Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).take(8).exists {
      case _: MemoryLimitExceededException => true
      case _: QueryLimitExceededException  => true
      case _                               => false
    }

  /** The class of a failed read, checked in this order: resource limit, timeout, then the
    * [[AwsErrors.wrap]] retry class (transient: throttling, 429, 5xx, I/O; else permanent). The
    * message is the store's text (the SDK message), as `run` reported it before Phase 16.
    */
  def classify(e: StoreException): GraphReadError =
    if (isResourceLimit(e)) GraphReadError.ResourceLimit(e.getMessage)
    else if (isTimeout(e)) GraphReadError.Timeout(e.getMessage)
    else if (e.transient) GraphReadError.Transient(e.getMessage)
    else GraphReadError.Permanent(e.getMessage)

  /** Only transient and resource-limit failures are retried. */
  def isRetryable(e: GraphReadError): Boolean = e match {
    case _: GraphReadError.Transient | _: GraphReadError.ResourceLimit => true
    case _                                                             => false
  }
}

/** Parameterized MERGE upserts for small writes, capped at `neptune.mergeRowCap` rows per call. */
final class NeptuneMergeUpsert(client: NeptunedataClient, val rowCap: Int, timeoutMillis: Int)
    extends GraphUpsertPort {
  require(rowCap > 0, "rowCap must be > 0")

  def upsertNodes(label: String, rows: Seq[NodeUpsert]): Either[String, Int] =
    for {
      cypher <- CypherTemplates.mergeNodes(label)
      _ <- Either.cond(
        rows.size <= rowCap,
        (),
        s"${rows.size} rows exceed the MERGE row cap $rowCap; use the bulk loader"
      )
      _ <- Either.cond(rows.forall(r => r.id != null && r.id.trim.nonEmpty), (), "blank node ID")
    } yield {
      if (rows.nonEmpty)
        AwsErrors.guard(
          client.executeOpenCypherQuery(
            ExecuteOpenCypherQueryRequest
              .builder()
              .openCypherQuery(cypher)
              .parameters(CypherTemplates.mergeParameters(rows).render())
              .overrideConfiguration(o => o.apiCallTimeout(Duration.ofMillis(timeoutMillis.toLong)))
              .build()
          )
        )
      rows.size
    }
}
