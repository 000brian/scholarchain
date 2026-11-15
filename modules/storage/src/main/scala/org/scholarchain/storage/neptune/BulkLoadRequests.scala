package org.scholarchain.storage.neptune

import org.scholarchain.storage.ports.{BulkLoadJob, ObjectRef}

/** Builds Neptune bulk-loader requests (format `opencypher`).
  *
  * A graph load is two jobs: the `nodes` stage prefix, then the `edges` stage prefix with the nodes
  * job as a dependency (relationships need their endpoints). The source prefix, IAM role ARN and
  * Region come from configuration or the deploy environment, never from code. Settings:
  *   - `failOnError = TRUE`: a bad row stops the job instead of loading a partial graph;
  *   - `parallelism = MEDIUM`: a conservative default for the db.t4g.medium free-trial instance;
  *   - `queueRequest = TRUE`: jobs queue instead of failing when another load runs;
  *   - `updateSingleCardinalityProperties = TRUE`: reloading the same IDs replaces property values,
  *     so a rerun of the same files converges to the same graph (IDs are the core stable IDs).
  */
object BulkLoadRequests {
  val Format = "opencypher"
  private val RoleArn = "arn:aws[a-z-]*:iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_/-]+".r
  private val Region = "[a-z]{2}(-[a-z]+)+-[0-9]".r

  def build(
      source: ObjectRef,
      iamRoleArn: String,
      region: String,
      dependencies: List[String]
  ): Either[String, BulkLoadJob] = {
    val errors = List(
      Option
        .when(!source.key.endsWith("/"))(s"source '${source.key}' must be a prefix ending in /"),
      Option.when(!source.key.contains("/neptune/"))("source must be a Neptune load-stage prefix"),
      Option.when(iamRoleArn == null || !RoleArn.matches(iamRoleArn))(
        "iamRoleArn is not an IAM role ARN"
      ),
      Option
        .when(region == null || !Region.matches(region))(s"region '$region' is not an AWS Region"),
      Option.when(dependencies.exists(d => d == null || d.trim.isEmpty))("blank dependency load ID")
    ).flatten
    if (errors.nonEmpty) Left(errors.mkString("; "))
    else
      Right(
        BulkLoadJob(
          source = source.uri,
          format = Format,
          iamRoleArn = iamRoleArn,
          region = region,
          failOnError = true,
          parallelism = "MEDIUM",
          queueRequest = true,
          updateSingleCardinalityProperties = true,
          dependencies = dependencies
        )
      )
  }

  /** The loader HTTP API body (`POST /loader`), for review and for the runbook. */
  def toJson(job: BulkLoadJob): ujson.Obj = {
    val o = ujson.Obj(
      "source" -> job.source,
      "format" -> job.format,
      "iamRoleArn" -> job.iamRoleArn,
      "region" -> job.region,
      "failOnError" -> (if (job.failOnError) "TRUE" else "FALSE"),
      "parallelism" -> job.parallelism,
      "queueRequest" -> (if (job.queueRequest) "TRUE" else "FALSE"),
      "updateSingleCardinalityProperties" -> (if (job.updateSingleCardinalityProperties) "TRUE"
                                              else "FALSE")
    )
    if (job.dependencies.nonEmpty)
      o("dependencies") = ujson.Arr.from(job.dependencies.map(ujson.Str(_)))
    o
  }
}
