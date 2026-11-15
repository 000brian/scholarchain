package org.scholarchain.storage.vectors

import java.nio.charset.StandardCharsets

import scala.collection.immutable.ListMap

import org.scholarchain.core.{
  ChunkId,
  Contract,
  EvidenceId,
  Provenance,
  ResearcherId,
  RunId,
  SchemaVersions,
  SemanticDocumentType,
  SourceType,
  VectorMetadata,
  WorkId
}
import org.scholarchain.storage.S3VectorsLimits
import org.scholarchain.storage.ports.{MetaValue, StoredMetadata}

/** Byte and key counts of one vector's stored metadata. Sizes are a conservative estimate: key name
  * bytes plus value bytes (UTF-8 for strings and list elements, 8 bytes per number).
  */
final case class MetadataSizes(
    filterableBytes: Int,
    totalBytes: Int,
    keys: Int,
    nonFilterableKeys: Int
)

/** Flattens a core [[VectorMetadata]] into S3 Vectors metadata (flat keys, no nested objects) and
  * back.
  *
  *   - `vectorKey` and `indexName` are not stored: they are the vector's key and index.
  *   - Provenance is flattened: `sourceType`, `datasetId`, `snapshotId`, `jobName`, `policyVersion`
  *     (filterable), `sourceRecordIds` (non-filterable list). `provenance.runId` equals `runId` and
  *     `provenance.contentHash` equals `textHash` (core rules), so they are not repeated. The
  *     provenance schema version is the current one.
  *   - Absent optional fields are absent keys (never null).
  *   - Document text is never a field.
  */
object VectorMetadataCodec {

  val FilterableKeys: Set[String] = Set(
    "documentType",
    "researcherId",
    "communityId",
    "startResearcherId",
    "endResearcherId",
    "workId",
    "topicId",
    "institutionId",
    "windowStartYear",
    "windowEndYear",
    "year",
    "venueKey",
    "pathLength",
    "sourceDataset",
    "embeddingModel",
    "embeddingDimensions",
    "sourceType",
    "datasetId",
    "snapshotId",
    "jobName",
    "policyVersion"
  )

  val NonFilterableKeys: Set[String] = Set(
    "textS3Uri",
    "textHash",
    "documentId",
    "sourceEvidenceId",
    "runId",
    "sourceRecordIds",
    "schemaVersion"
  )

  val AllKeys: Set[String] = FilterableKeys ++ NonFilterableKeys

  private def s(v: String): MetaValue = MetaValue.Str(v)
  private def n(v: Int): MetaValue = MetaValue.Num(v.toLong)

  /** Validates `m` with core rules, flattens it, and checks every limit against `spec`. */
  def encode(m: VectorMetadata, spec: IndexSpecEntry): Either[List[String], StoredMetadata] = {
    val core = Contract.validate(m)
    val extra = List(
      Option.when(m.indexName != spec.name)(
        s"indexName '${m.indexName}' differs from index '${spec.name}'"
      ),
      Option.when(m.documentType != spec.documentType)(
        s"documentType ${m.documentType.wire} does not belong in index ${spec.name}"
      ),
      Option.when(m.provenance.schemaVersion != SchemaVersions.currentOf("Provenance"))(
        s"provenance.schemaVersion ${m.provenance.schemaVersion} is not current"
      )
    ).flatten
    if (core.nonEmpty || extra.nonEmpty) Left(core ++ extra)
    else {
      val all: List[(String, Option[MetaValue])] = List(
        "documentType" -> Some(s(m.documentType.wire)),
        "researcherId" -> m.researcherId.map(x => s(x.value)),
        "communityId" -> m.communityId.map(x => s(x.value)),
        "startResearcherId" -> m.startResearcherId.map(x => s(x.value)),
        "endResearcherId" -> m.endResearcherId.map(x => s(x.value)),
        "workId" -> m.workId.map(x => s(x.value)),
        "topicId" -> m.topicId.map(s),
        "institutionId" -> m.institutionId.map(s),
        "windowStartYear" -> m.windowStartYear.map(n),
        "windowEndYear" -> m.windowEndYear.map(n),
        "year" -> m.year.map(n),
        "venueKey" -> m.venueKey.map(s),
        "pathLength" -> m.pathLength.map(n),
        "sourceDataset" -> Some(s(m.sourceDataset)),
        "embeddingModel" -> Some(s(m.embeddingModel)),
        "embeddingDimensions" -> Some(n(m.embeddingDimensions)),
        "sourceType" -> Some(s(m.provenance.sourceType.wire)),
        "datasetId" -> Some(s(m.provenance.datasetId)),
        "snapshotId" -> Some(s(m.provenance.snapshotId)),
        "jobName" -> Some(s(m.provenance.jobName)),
        "policyVersion" -> Some(s(m.provenance.policyVersion)),
        "textS3Uri" -> Some(s(m.textS3Uri)),
        "textHash" -> Some(s(m.textHash)),
        "documentId" -> Some(s(m.documentId.value)),
        "sourceEvidenceId" -> m.sourceEvidenceId.map(x => s(x.value)),
        "runId" -> Some(s(m.runId.value)),
        "sourceRecordIds" -> Some(MetaValue.StrList(m.provenance.sourceRecordIds)),
        "schemaVersion" -> Some(n(m.schemaVersion))
      )
      val present = all.collect { case (k, Some(v)) => k -> v }
      val filterableSet = spec.filterableKeys.toSet
      val stored = StoredMetadata(
        ListMap(present.filter { case (k, _) => filterableSet(k) }: _*),
        ListMap(present.filterNot { case (k, _) => filterableSet(k) }: _*)
      )
      checkLimits(stored, spec, None) match {
        case Nil  => Right(stored)
        case errs => Left(errs)
      }
    }
  }

  def valueBytes(v: MetaValue): Int = v match {
    case MetaValue.Str(x)      => x.getBytes(StandardCharsets.UTF_8).length
    case MetaValue.Num(_)      => 8
    case MetaValue.Bool(_)     => 1
    case MetaValue.StrList(xs) => xs.map(_.getBytes(StandardCharsets.UTF_8).length).sum
  }

  private def bytesOf(m: ListMap[String, MetaValue]): Int =
    m.iterator.map { case (k, v) => k.getBytes(StandardCharsets.UTF_8).length + valueBytes(v) }.sum

  def sizes(stored: StoredMetadata): MetadataSizes = {
    val f = bytesOf(stored.filterable)
    MetadataSizes(f, f + bytesOf(stored.nonFilterable), stored.keyCount, stored.nonFilterable.size)
  }

  /** Limit checks (decision 8) and the no-text rule. When `text` is given, no metadata value may
    * contain it.
    */
  def checkLimits(
      stored: StoredMetadata,
      spec: IndexSpecEntry,
      text: Option[String]
  ): List[String] = {
    val z = sizes(stored)
    val wrongSide =
      stored.filterable.keySet.diff(spec.filterableKeys.toSet) ++
        stored.nonFilterable.keySet.diff(spec.nonFilterableKeys.toSet)
    val strings = stored.all.valuesIterator.flatMap {
      case MetaValue.Str(x)      => List(x)
      case MetaValue.StrList(xs) => xs
      case _                     => Nil
    }.toList
    List(
      Option.when(z.filterableBytes > S3VectorsLimits.MaxFilterableMetadataBytes)(
        s"filterable metadata ${z.filterableBytes} bytes exceeds ${S3VectorsLimits.MaxFilterableMetadataBytes}"
      ),
      Option.when(z.totalBytes > S3VectorsLimits.MaxTotalMetadataBytes)(
        s"metadata ${z.totalBytes} bytes exceeds ${S3VectorsLimits.MaxTotalMetadataBytes}"
      ),
      Option.when(z.keys > S3VectorsLimits.MaxMetadataKeys)(
        s"${z.keys} metadata keys exceed ${S3VectorsLimits.MaxMetadataKeys}"
      ),
      Option.when(z.nonFilterableKeys > S3VectorsLimits.MaxNonFilterableKeys)(
        s"${z.nonFilterableKeys} non-filterable keys exceed ${S3VectorsLimits.MaxNonFilterableKeys}"
      ),
      Option.when(wrongSide.nonEmpty)(
        s"keys not declared on that side of the index spec: ${wrongSide.toList.sorted.mkString(",")}"
      ),
      Option.when(strings.exists(_.length > VectorMetadata.MaxFieldChars))(
        s"a metadata value exceeds ${VectorMetadata.MaxFieldChars} characters"
      ),
      text.filter(_.trim.nonEmpty).flatMap { t =>
        Option.when(strings.exists(v => v.contains(t) || (v.length >= 64 && t.contains(v))))(
          "metadata contains document text"
        )
      }
    ).flatten
  }

  /** Rebuilds the core record from stored metadata; missing or mistyped keys are errors. The result
    * is validated with core rules.
    */
  def decode(
      vectorKey: String,
      indexName: String,
      stored: StoredMetadata
  ): Either[List[String], VectorMetadata] = {
    val m = stored.all
    val errors = List.newBuilder[String]
    def str(k: String): Option[String] = m.get(k) match {
      case Some(MetaValue.Str(v)) => Some(v)
      case None                   => None
      case Some(other)            => errors += s"$k: expected a string, got $other"; None
    }
    def num(k: String): Option[Int] = m.get(k) match {
      case Some(MetaValue.Num(v)) if v.isValidInt => Some(v.toInt)
      case None                                   => None
      case Some(other) => errors += s"$k: expected an integer, got $other"; None
    }
    def req[A](k: String, v: Option[A]): Option[A] = {
      if (v.isEmpty && !m.contains(k)) errors += s"$k: missing"
      v
    }
    def parsed[A](k: String, v: Option[String], f: String => Either[String, A]): Option[A] =
      v.flatMap(x => f(x).fold(e => { errors += s"$k: $e"; None }, Some(_)))

    val unknown = m.keySet -- AllKeys
    if (unknown.nonEmpty) errors += s"unknown metadata keys: ${unknown.toList.sorted.mkString(",")}"
    val documentType =
      parsed(
        "documentType",
        req("documentType", str("documentType")),
        SemanticDocumentType.fromWire
      )
    val sourceType = parsed("sourceType", req("sourceType", str("sourceType")), SourceType.fromWire)
    val documentId = parsed("documentId", req("documentId", str("documentId")), ChunkId.parse)
    val runId = parsed("runId", req("runId", str("runId")), RunId.parse)
    val textHash = req("textHash", str("textHash"))
    val sourceRecordIds = m.get("sourceRecordIds") match {
      case Some(MetaValue.StrList(xs)) => Some(xs)
      case None                        => errors += "sourceRecordIds: missing"; None
      case Some(other) => errors += s"sourceRecordIds: expected a list, got $other"; None
    }
    val r = for {
      dt <- documentType
      st <- sourceType
      doc <- documentId
      run <- runId
      th <- textHash
      srcIds <- sourceRecordIds
      datasetId <- req("datasetId", str("datasetId"))
      snapshotId <- req("snapshotId", str("snapshotId"))
      jobName <- req("jobName", str("jobName"))
      policy <- req("policyVersion", str("policyVersion"))
      sourceDataset <- req("sourceDataset", str("sourceDataset"))
      uri <- req("textS3Uri", str("textS3Uri"))
      model <- req("embeddingModel", str("embeddingModel"))
      dims <- req("embeddingDimensions", num("embeddingDimensions"))
      schema <- req("schemaVersion", num("schemaVersion"))
    } yield VectorMetadata(
      schemaVersion = schema,
      vectorKey = vectorKey,
      indexName = indexName,
      documentId = doc,
      documentType = dt,
      researcherId = parsed("researcherId", str("researcherId"), ResearcherId.parse),
      communityId = parsed("communityId", str("communityId"), ResearcherId.parse),
      startResearcherId = parsed("startResearcherId", str("startResearcherId"), ResearcherId.parse),
      endResearcherId = parsed("endResearcherId", str("endResearcherId"), ResearcherId.parse),
      workId = parsed("workId", str("workId"), WorkId.parse),
      topicId = str("topicId"),
      institutionId = str("institutionId"),
      windowStartYear = num("windowStartYear"),
      windowEndYear = num("windowEndYear"),
      year = num("year"),
      venueKey = str("venueKey"),
      pathLength = num("pathLength"),
      sourceDataset = sourceDataset,
      textS3Uri = uri,
      textHash = th,
      sourceEvidenceId = parsed("sourceEvidenceId", str("sourceEvidenceId"), EvidenceId.parse),
      embeddingModel = model,
      embeddingDimensions = dims,
      runId = run,
      provenance = Provenance(
        SchemaVersions.currentOf("Provenance"),
        st,
        datasetId,
        snapshotId,
        srcIds,
        run,
        jobName,
        policy,
        Some(th)
      )
    )
    val errs = errors.result()
    r match {
      case Some(meta) if errs.isEmpty =>
        Contract.validate(meta) match {
          case Nil => Right(meta)
          case ve  => Left(ve)
        }
      case _ => Left(if (errs.isEmpty) List("metadata is incomplete") else errs)
    }
  }
}
