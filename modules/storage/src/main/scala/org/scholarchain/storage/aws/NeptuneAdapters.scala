package org.scholarchain.storage.aws

import java.net.URI
import java.time.Duration

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters._

import org.scholarchain.storage.GraphQueryBounds
import org.scholarchain.storage.neptune.CypherTemplates
import org.scholarchain.storage.ports._
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
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
      .updateSingleCardinalityProperties(job.updateSingleCardinalityProperties)
    if (job.dependencies.nonEmpty) b.dependencies(job.dependencies.asJava)
    val r = AwsErrors.guard(client.startLoaderJob(b.build()))
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

/** Read-only graph queries: only the allowlisted templates, always with parameters, a row limit and
  * a client-side API call timeout (at most [[GraphQueryBounds.MaxTimeoutMillis]]). Use it with
  * read-only credentials (Phase 12 IAM).
  */
final class NeptuneGraphRead(client: NeptunedataClient, timeoutMillis: Int) extends GraphReadPort {
  require(
    timeoutMillis > 0 && timeoutMillis <= GraphQueryBounds.MaxTimeoutMillis,
    "timeout out of bounds"
  )

  def run(query: GraphQuery): Either[String, GraphResult] =
    CypherTemplates.compile(query).flatMap { c =>
      try {
        val r = AwsErrors.guard(
          client.executeOpenCypherQuery(
            ExecuteOpenCypherQueryRequest
              .builder()
              .openCypherQuery(c.cypher)
              .parameters(c.parameters.render())
              .overrideConfiguration(o => o.apiCallTimeout(Duration.ofMillis(timeoutMillis.toLong)))
              .build()
          )
        )
        val rows =
          NeptuneClients.toJson(r.results()).arrOpt.map(_.toList).getOrElse(Nil).map { row =>
            GraphRow(ListMap.from(row.objOpt.map(_.toList).getOrElse(Nil)))
          }
        Right(GraphResult(c.templateId, rows.take(c.limit), rows.size > c.limit))
      } catch { case e: org.scholarchain.storage.StoreException => Left(e.getMessage) }
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
