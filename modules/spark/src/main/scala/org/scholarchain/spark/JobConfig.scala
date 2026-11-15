package org.scholarchain.spark

import scala.util.control.NonFatal

import org.scholarchain.core.Hashing
import org.scholarchain.core.config.{GraphSettings, NamedWindow}
import upickle.default.{macroRW, read, write, ReadWriter}

/** Iteration caps, depths, sample sizes and the seed of the graph algorithms. Every value is
  * required (no defaults); see `GraphAnalyticsJob` for their meaning.
  *
  * @param labelPropagationSelfWeight
  *   self vote of a researcher for its own current label, as a multiple of its maximum incident
  *   edge weight (0 gives plain synchronous label propagation, which oscillates on bipartite
  *   structures such as a single coauthor pair; 1 makes such ties resolve to the smaller label).
  * @param seed
  *   seed of the deterministic (hash-ordered) choice of random path pairs and diameter sources
  */
final case class AlgorithmSettings(
    componentsMaxIterations: Int,
    labelPropagationMaxIterations: Int,
    labelPropagationSelfWeight: Double,
    kcoreMaxIterations: Int,
    pathMaxDepth: Int,
    pathTopK: Int,
    pathRandomPairs: Int,
    diameterSources: Int,
    diameterMaxDepth: Int,
    seed: String
)
object AlgorithmSettings { implicit val readWriter: ReadWriter[AlgorithmSettings] = macroRW }

/** Thresholds of the deterministic semantic-summary templates. */
final case class SummarySettings(
    profileMinWorks: Int,
    communityMinMembers: Int,
    profileTopVenues: Int,
    communityTopMembers: Int
)
object SummarySettings { implicit val readWriter: ReadWriter[SummarySettings] = macroRW }

/** Configuration of one Spark graph-analytics run. It is fingerprinted into the run ID, so every
  * value here may change outputs. Runtime-only knobs (master, shuffle partitions, disk floor) are
  * runner arguments and do not change outputs.
  *
  * `graph.windows` uses the core [[GraphSettings]] contract; `fullWindowLabel` names the window
  * used for path evidence, the diameter estimate and researcher profiles.
  */
final case class SparkJobConfig(
    configVersion: Int,
    jobName: String,
    datasetId: String,
    policyVersion: String,
    graph: GraphSettings,
    fullWindowLabel: String,
    algorithms: AlgorithmSettings,
    summaries: SummarySettings,
    outputPartitions: Int
) {
  def windows: List[NamedWindow] = graph.windows
  def fullWindow: NamedWindow = windows
    .find(_.label == fullWindowLabel)
    .getOrElse(
      throw new IllegalStateException(s"fullWindowLabel $fullWindowLabel is not a window")
    )
  def json: String = write(this)

  /** `sha256:<hex>` of the compact JSON; the `configFingerprint` input of the run ID. */
  def fingerprint: String = "sha256:" + Hashing.sha256Hex(json)
}

object SparkJobConfig {
  implicit val readWriter: ReadWriter[SparkJobConfig] = macroRW

  val SupportedVersion = 1
  private val Label = "[a-z0-9][a-z0-9-]*"

  def parse(json: String): Either[List[String], SparkJobConfig] =
    try {
      val c = read[SparkJobConfig](json)
      validate(c) match {
        case Nil    => Right(c)
        case errors => Left(errors)
      }
    } catch {
      case NonFatal(e) => Left(List(s"config: ${e.getMessage}"))
    }

  def validate(c: SparkJobConfig): List[String] = {
    def check(ok: Boolean, msg: String) = if (ok) Nil else List(msg)
    val a = c.algorithms
    val labels = c.windows.map(_.label)
    check(c.configVersion == SupportedVersion, s"configVersion: ${c.configVersion} unsupported") ++
      check(c.jobName.trim.nonEmpty, "jobName: blank") ++
      check(
        c.datasetId.matches("[a-z0-9][a-z0-9-]*"),
        "datasetId: must match [a-z0-9][a-z0-9-]*"
      ) ++
      check(c.policyVersion.trim.nonEmpty, "policyVersion: blank") ++
      check(c.windows.nonEmpty, "graph.windows: empty") ++
      check(labels.distinct.size == labels.size, "graph.windows: labels must be distinct") ++
      c.windows.flatMap { w =>
        check(w.label.matches(Label), s"graph.windows: label '${w.label}' must match $Label") ++
          check(
            w.startYear >= 1 && w.endYear <= 9999 && w.startYear <= w.endYear,
            s"graph.windows: '${w.label}' has an invalid year range"
          )
      } ++
      check(labels.contains(c.fullWindowLabel), "fullWindowLabel: not a configured window") ++
      check(
        !c.graph.minEdgeWeight.isNaN && c.graph.minEdgeWeight >= 1.0,
        "graph.minEdgeWeight: must be >= 1 (weights are joint publication counts)"
      ) ++
      check(a.componentsMaxIterations >= 1, "componentsMaxIterations: must be >= 1") ++
      check(a.labelPropagationMaxIterations >= 1, "labelPropagationMaxIterations: must be >= 1") ++
      check(
        !a.labelPropagationSelfWeight.isNaN && a.labelPropagationSelfWeight >= 0.0,
        "labelPropagationSelfWeight: must be >= 0"
      ) ++
      check(a.kcoreMaxIterations >= 1, "kcoreMaxIterations: must be >= 1") ++
      check(a.pathMaxDepth >= 1, "pathMaxDepth: must be >= 1") ++
      check(a.pathTopK >= 0 && a.pathTopK <= 100, "pathTopK: must be in 0..100") ++
      check(a.pathRandomPairs >= 0 && a.pathRandomPairs <= 100, "pathRandomPairs: 0..100") ++
      check(a.diameterSources >= 0 && a.diameterSources <= 100, "diameterSources: 0..100") ++
      check(a.diameterMaxDepth >= 1, "diameterMaxDepth: must be >= 1") ++
      check(a.seed.trim.nonEmpty, "seed: blank") ++
      check(c.summaries.profileMinWorks >= 1, "profileMinWorks: must be >= 1") ++
      check(c.summaries.communityMinMembers >= 1, "communityMinMembers: must be >= 1") ++
      check(c.summaries.profileTopVenues >= 0, "profileTopVenues: must be >= 0") ++
      check(c.summaries.communityTopMembers >= 1, "communityTopMembers: must be >= 1") ++
      check(c.outputPartitions >= 1, "outputPartitions: must be >= 1")
  }
}
