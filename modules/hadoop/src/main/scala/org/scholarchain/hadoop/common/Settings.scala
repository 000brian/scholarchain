package org.scholarchain.hadoop.common

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.scholarchain.core.Hashing

/** Typed, required access to ScholarChain Hadoop settings.
  *
  * Defaults live only in the classpath resource [[Settings.DefaultsResource]]; this class has no
  * fallback values. A missing or malformed key throws [[SettingsError]] when the driver or a task
  * reads it.
  */
final class Settings(val conf: Configuration) {

  def string(key: String): String =
    Option(conf.getTrimmed(key)).filter(_.nonEmpty).getOrElse(throw SettingsError(key, "missing"))

  /** An optional value: absent and empty are the same (Hadoop drops empty XML values when it
    * serialises the job configuration). Only keys documented as optional use this.
    */
  def optional(key: String): Option[String] = Option(conf.getTrimmed(key)).filter(_.nonEmpty)

  def int(key: String): Int =
    string(key).toIntOption.getOrElse(throw SettingsError(key, s"not an integer: ${string(key)}"))

  def positiveInt(key: String): Int = {
    val v = int(key)
    if (v <= 0) throw SettingsError(key, s"must be > 0 (got $v)")
    v
  }

  def long(key: String): Long =
    string(key).toLongOption.getOrElse(throw SettingsError(key, s"not a long: ${string(key)}"))

  def positiveLong(key: String): Long = {
    val v = long(key)
    if (v <= 0) throw SettingsError(key, s"must be > 0 (got $v)")
    v
  }

  def boolean(key: String): Boolean = string(key) match {
    case "true"  => true
    case "false" => false
    case other   => throw SettingsError(key, s"not true or false: $other")
  }

  /** Bytes of a non-Ok raw unit (oversize or truncated record or line) kept by the input formats as
    * its preview: `scholarchain.reject.previewChars` times [[RawRecordPreview.MaxBytesPerChar]], so
    * the preview always holds at least `previewChars` characters in every supported encoding (UTF-8
    * uses at most 4 bytes per character; ISO-8859-1 and US-ASCII use 1). A value whose byte size
    * does not fit in an Int fails with [[SettingsError]] instead of being capped silently.
    */
  def rejectPreviewBytes: Int = {
    val chars = positiveInt(Settings.Keys.RejectPreviewChars)
    val bytes = chars.toLong * RawRecordPreview.MaxBytesPerChar
    if (bytes > RawRecordPreview.MaxPreviewBytes)
      throw SettingsError(
        Settings.Keys.RejectPreviewChars,
        s"$chars characters need $bytes preview bytes, more than ${RawRecordPreview.MaxPreviewBytes}"
      )
    bytes.toInt
  }

  /** Comma-separated, trimmed, non-empty values; the list itself may be empty. */
  def list(key: String): List[String] =
    optional(key).toList.flatMap(_.split(',').toList.map(_.trim).filter(_.nonEmpty))
}

/** Sizing of raw-unit previews (see [[Settings.rejectPreviewBytes]]). */
object RawRecordPreview {

  /** Maximum bytes of one character in a supported encoding (UTF-8). */
  val MaxBytesPerChar = 4

  /** Largest Java array length that is safe to request (JVM array header limit). */
  val MaxPreviewBytes: Long = Int.MaxValue - 8L
}

final case class SettingsError(key: String, problem: String)
    extends IllegalArgumentException(s"configuration $key: $problem")

object Settings {

  /** Classpath resource with every default (see its header). */
  val DefaultsResource = "scholarchain-hadoop-defaults.xml"

  /** Keys set per run by drivers; excluded from the configuration fingerprint. */
  val RunPrefix = "scholarchain.run."
  val PathPrefix = "scholarchain.path."
  val Prefix = "scholarchain."

  /** A new configuration with Hadoop's defaults plus ScholarChain's defaults resource. */
  def withDefaults(base: Configuration): Configuration = {
    val conf = new Configuration(base)
    conf.addResource(DefaultsResource)
    conf
  }

  /** Operational keys: they change how a job is executed or recovered, never the bytes of any file
    * it writes under its run directory (records, dispositions, stats, `_job.json`), so they are
    * excluded from the configuration fingerprint and changing one keeps the run ID (see
    * [[JobRunner]]).
    *
    *   - `scholarchain.output.replaceIncomplete`: recovery switch for an incomplete directory of
    *     the same run; the recovery rerun must address that same directory.
    *   - `scholarchain.intermediate.compress` and `scholarchain.intermediate.codec`: shuffle (map
    *     output) compression only; reducers see the same sorted key groups either way.
    *
    * Deliberately fingerprinted although they look operational: the reduce-task counts
    * (`scholarchain.*.reducers`, `scholarchain.pairs.expandReducers`,
    * `scholarchain.pairs.consolidateReducers`; they change the split into `part-r-NNNNN` files and
    * the `scholarchain.output-records:stats` counter, one stat line per task, recorded in
    * `_job.json`), the shard sizes `scholarchain.pairs.pairsPerShard` and
    * `scholarchain.candidates.pairsPerShard` (PAIR_SHARDS and CANDIDATE_SHARDS counters), and
    * `scholarchain.output.compress` and `scholarchain.output.codec` (output file bytes and names).
    *
    * What a run ID guarantees (and what it does not): it fixes the job, the code version, the input
    * content (per file: base name, length and sha256, or the upstream run IDs) and every
    * fingerprinted key. Rerunning it on the same input files at the same locations with the same
    * Hadoop input-split configuration produces byte-identical files and counters. It does not fix:
    *   - the input split layout (`mapreduce.input.fileinputformat.split.minsize` and `maxsize`, the
    *     file system block size), which is Hadoop configuration outside `scholarchain.*`. It
    *     changes how map-side outputs (rejects, exclusions, issues and stats written by mappers)
    *     are spread over `part-m-NNNNN` files, and the `scholarchain.output-records` counters
    *     recorded in `_job.json`;
    *   - the input location: dispositions record `location` as `<absolute file path>:<offset>`,
    *     while the input fingerprint uses base names, so the same files under another directory
    *     keep the run ID but change the `location` strings.
    * The weaker claim was chosen over fingerprinting split settings and relativising locations; the
    * split layout cannot be fixed by `scholarchain.*` keys alone.
    */
  val OperationalKeys: Set[String] =
    Set(Keys.ReplaceIncomplete, Keys.IntermediateCompress, Keys.IntermediateCodec)

  /** True for keys that take part in the configuration fingerprint: every `scholarchain.*` key
    * except the per-run keys (`scholarchain.run.*`, `scholarchain.path.*`) and [[OperationalKeys]].
    */
  def fingerprinted(key: String): Boolean =
    key.startsWith(Prefix) && !key.startsWith(RunPrefix) && !key.startsWith(PathPrefix) &&
      !OperationalKeys.contains(key)

  /** `sha256:<hex>` over the sorted `key=value` lines of every [[fingerprinted]] key. Used as the
    * `configFingerprint` of `RunId.of`.
    */
  def fingerprint(conf: Configuration): String = {
    val lines = conf
      .iterator()
      .asScala
      .map(e => e.getKey -> e.getValue)
      .filter { case (k, _) => fingerprinted(k) }
      .map { case (k, _) => s"$k=${Option(conf.get(k)).getOrElse("")}" }
      .toList
      .sorted
    "sha256:" + Hashing.sha256Hex(lines.mkString("\n"))
  }

  object Keys {
    val CodeVersion = "scholarchain.code.version"
    val ReplaceIncomplete = "scholarchain.output.replaceIncomplete"
    val IntermediateCompress = "scholarchain.intermediate.compress"
    val IntermediateCodec = "scholarchain.intermediate.codec"
    val OutputCompress = "scholarchain.output.compress"
    val OutputCodec = "scholarchain.output.codec"
    val RejectPreviewChars = "scholarchain.reject.previewChars"
    val MaxDecompressedBytes = "scholarchain.input.maxDecompressedBytesPerFile"
    val InternalMaxLineBytes = "scholarchain.internal.maxLineBytes"

    val DblpParsePolicy = "scholarchain.dblp.parsePolicyVersion"
    val DblpBoundaryElements = "scholarchain.dblp.boundaryElements"
    val DblpRecordElements = "scholarchain.dblp.recordElements"
    val DblpSubsetEnabled = "scholarchain.dblp.subset.enabled"
    val DblpKeyPrefixes = "scholarchain.dblp.subset.keyPrefixes"
    val DblpYearMin = "scholarchain.dblp.subset.yearMin"
    val DblpYearMax = "scholarchain.dblp.subset.yearMax"
    val DblpExcludePubltype = "scholarchain.dblp.subset.excludePubltypeTokens"
    val DblpSupportedEncodings = "scholarchain.dblp.supportedEncodings"
    val DblpMaxRecordBytes = "scholarchain.dblp.maxRecordBytes"
    val DblpMaxPrologBytes = "scholarchain.dblp.maxPrologBytes"
    val DblpDtdPath = "scholarchain.dblp.dtdPath"
    val DblpMaxExpandedChars = "scholarchain.dblp.maxExpandedRecordChars"
    val DblpReducers = "scholarchain.dblp.reducers"

    val TeamOversize = "scholarchain.teams.oversizeThreshold"
    val TeamHardLimit = "scholarchain.teams.hardLimit"
    val TeamMaxCoauthorContext = "scholarchain.teams.maxCoauthorContext"
    val TeamReducers = "scholarchain.teams.reducers"

    val PairsPerShard = "scholarchain.pairs.pairsPerShard"
    val PairExpandReducers = "scholarchain.pairs.expandReducers"
    val PairConsolidateReducers = "scholarchain.pairs.consolidateReducers"

    val NormalizePolicy = "scholarchain.normalize.policyVersion"
    val NormalizeMaxLineBytes = "scholarchain.normalize.maxLineBytes"
    val NormalizeKeepCrossrefAbstracts = "scholarchain.normalize.keepCrossrefAbstracts"
    val NormalizeReducers = "scholarchain.normalize.reducers"
    val NormalizeKind = "scholarchain.run.normalizeKind"

    val IdJoinPolicy = "scholarchain.idjoin.policyVersion"
    val IdJoinMaxGroup = "scholarchain.idjoin.maxGroupSize"
    val IdJoinReducers = "scholarchain.idjoin.reducers"

    val BlockingFamilies = "scholarchain.blocking.keyFamilies"
    val BlockingMaxBlock = "scholarchain.blocking.maxBlockSize"
    val BlockingEmitSingletons = "scholarchain.blocking.emitSingletons"
    val BlockingPolicy = "scholarchain.blocking.policyVersion"
    val BlockingReducers = "scholarchain.blocking.reducers"

    val CandidateFeatureSet = "scholarchain.candidates.featureSet"
    val CandidateMaxPairsPerBlock = "scholarchain.candidates.maxPairsPerBlock"
    val CandidatePairsPerShard = "scholarchain.candidates.pairsPerShard"
    val CandidateReducers = "scholarchain.candidates.reducers"

    val ExportReducers = "scholarchain.export.reducers"
    val ExportPolicy = "scholarchain.export.policyVersion"

    // Per-run keys (set by drivers, never in the defaults file).
    val RunId = "scholarchain.run.id"
    val RunJobName = "scholarchain.run.jobName"
    val RunDatasetId = "scholarchain.run.datasetId"
    val RunSnapshotId = "scholarchain.run.snapshotId"
    val RunPolicyVersion = "scholarchain.run.policyVersion"
  }
}
