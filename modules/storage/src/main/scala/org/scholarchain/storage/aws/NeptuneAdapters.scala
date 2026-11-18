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
    * unknown, the load may exist): `ClientTimeoutException` (408), `TooManyRequestsException`
    * (429), `ThrottlingException`, `InternalFailureException` (500), every other service error,
    * every client-side error (timeouts, I/O, interrupted calls) and a 200 response without a load
    * ID.
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
}

/** Read-only graph queries: only the allowlisted templates (see [[CypherTemplates]]), identifiers
  * always as parameters, bounded by [[GraphReadSettings]]. Use it with read-only credentials (Phase
  * 12 IAM). Graph reads are not retried: a failed read is reported to the caller (Left).
  *
  *   - Row limits (m1): the text asks for `limit + 1` rows; the result holds at most `limit` rows
  *     and `truncated = rows > limit`.
  *   - `PathBetween` (iterative deepening): runs the exact-depth query for depth 1, then 2, up to
  *     `maxDepth`, and stops at the first depth that returns a path, or when the total elapsed time
  *     reaches `settings.timeoutMillis` (each call's API timeout is the remaining budget). The
  *     result's `complete` and `depthSearched` tell "no path within depth d" (complete) from
  *     "stopped at the time limit" (not complete).
  *
  * @param clock
  *   monotonic milliseconds; tests inject a fake clock
  */
final class NeptuneGraphRead(
    client: NeptunedataClient,
    val settings: GraphReadSettings,
    clock: () => Long = NeptuneGraphRead.monotonicMillis
) extends GraphReadPort {

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
    CypherTemplates.compile(query, settings).flatMap { compiled =>
      query match {
        case q: GraphQuery.PathBetween => pathSearch(q, compiled)
        case q                         =>
          val c = compiled.head
          try
            Right(
              GraphResult.bounded(q.templateId, execute(c, settings.timeoutMillis.toLong), c.limit)
            )
          catch { case e: StoreException => Left(e.getMessage) }
      }
    }

  private def pathSearch(
      q: GraphQuery.PathBetween,
      depths: List[CypherTemplates.Compiled]
  ): Either[String, GraphResult] = {
    val start = clock()
    def stopped(searched: Int) =
      Right(GraphResult(q.templateId, Nil, truncated = false, complete = false, Some(searched)))
    @annotation.tailrec
    def loop(rest: List[CypherTemplates.Compiled], searched: Int): Either[String, GraphResult] =
      rest match {
        case Nil =>
          Right(GraphResult(q.templateId, Nil, truncated = false, complete = true, Some(searched)))
        case c :: tail =>
          val remaining = settings.timeoutMillis.toLong - (clock() - start)
          if (remaining <= 0) stopped(searched)
          else {
            val outcome =
              try Right(execute(c, remaining))
              catch {
                case e: StoreException if NeptuneGraphRead.isTimeout(e) => Left(None)
                case e: StoreException                                  => Left(Some(e.getMessage))
              }
            outcome match {
              case Left(None)                   => stopped(searched)
              case Left(Some(err))              => Left(err)
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
