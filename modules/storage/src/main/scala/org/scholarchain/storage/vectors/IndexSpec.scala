package org.scholarchain.storage.vectors

import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

import org.scholarchain.core.{SemanticDocumentType, VectorIndexName}
import org.scholarchain.core.config.VectorSettings
import org.scholarchain.storage.S3VectorsLimits

/** One index of the spec file `s3vectors/index-spec-v1.json`. */
final case class IndexSpecEntry(
    name: String,
    configKey: String,
    documentType: SemanticDocumentType,
    filterableKeys: List[String],
    nonFilterableKeys: List[String]
) {
  def allKeys: Set[String] = (filterableKeys ++ nonFilterableKeys).toSet
}

/** The five S3 Vectors indexes. Terraform (Phase 12) creates the indexes from the same file. */
final case class IndexSpecCatalog(
    dimension: Int,
    dataType: String,
    distanceMetric: String,
    indexes: List[IndexSpecEntry]
) {
  def forType(t: SemanticDocumentType): IndexSpecEntry =
    indexes.find(_.documentType == t).getOrElse(throw new IllegalStateException(s"no index for $t"))
  def forName(name: String): Option[IndexSpecEntry] = indexes.find(_.name == name)

  /** Spec self-checks against the service limits (decision 8). */
  def validate: List[String] = {
    val perIndex = indexes.flatMap { i =>
      val dup = i.filterableKeys.toSet.intersect(i.nonFilterableKeys.toSet)
      List(
        Option.when(!VectorIndexName.isValid(i.name))(s"${i.name}: invalid index name"),
        Option.when(i.nonFilterableKeys.size > S3VectorsLimits.MaxNonFilterableKeys)(
          s"${i.name}: ${i.nonFilterableKeys.size} non-filterable keys exceed " +
            S3VectorsLimits.MaxNonFilterableKeys
        ),
        Option.when(i.allKeys.size > S3VectorsLimits.MaxMetadataKeys)(
          s"${i.name}: ${i.allKeys.size} keys exceed ${S3VectorsLimits.MaxMetadataKeys}"
        ),
        Option.when(dup.nonEmpty)(s"${i.name}: keys both filterable and not: ${dup.mkString(",")}"),
        Option.when(
          i.filterableKeys.distinct.size != i.filterableKeys.size ||
            i.nonFilterableKeys.distinct.size != i.nonFilterableKeys.size
        )(s"${i.name}: repeated keys"),
        Option.when(!VectorMetadataCodec.AllKeys.subsetOf(i.allKeys))(
          s"${i.name}: missing keys ${(VectorMetadataCodec.AllKeys -- i.allKeys).mkString(",")}"
        ),
        Option.when(!i.allKeys.subsetOf(VectorMetadataCodec.AllKeys))(
          s"${i.name}: unknown keys ${(i.allKeys -- VectorMetadataCodec.AllKeys).mkString(",")}"
        ),
        Option.when(!VectorMetadataCodec.NonFilterableKeys.subsetOf(i.nonFilterableKeys.toSet))(
          s"${i.name}: textS3Uri, textHash, documentId, sourceEvidenceId, runId, " +
            "sourceRecordIds and schemaVersion must be non-filterable"
        )
      ).flatten
    }
    val global = List(
      Option.when(
        dimension < S3VectorsLimits.MinDimension || dimension > S3VectorsLimits.MaxDimension
      )(s"dimension $dimension is outside 1..4096"),
      Option.when(distanceMetric != "cosine")(s"distanceMetric '$distanceMetric' is not cosine"),
      Option.when(dataType != "float32")(s"dataType '$dataType' is not float32"),
      Option.when(indexes.map(_.documentType).toSet != SemanticDocumentType.values.toSet)(
        "every document type needs exactly one index"
      ),
      Option.when(indexes.map(_.name).distinct.size != indexes.size)("repeated index names")
    ).flatten
    global ++ perIndex
  }

  /** The configured index names and embedding dimension must match the spec, so storage never
    * writes into an index whose metadata configuration differs from what it encodes.
    */
  def checkConfig(vectors: VectorSettings, embeddingDimensions: Int): List[String] =
    SemanticDocumentType.values.flatMap { t =>
      val configured = vectors.indexFor(t)
      val spec = forType(t).name
      Option.when(configured != spec)(
        s"${forType(t).configKey}: configured '$configured' differs from the index spec '$spec'"
      )
    } ++ Option
      .when(embeddingDimensions != dimension)(
        s"embedding.dimensions $embeddingDimensions differs from the index spec dimension $dimension"
      )
      .toList
}

object IndexSpecCatalog {
  val ResourcePath = "/s3vectors/index-spec-v1.json"

  def load(): IndexSpecCatalog = {
    val in = getClass.getResourceAsStream(ResourcePath)
    if (in == null) throw new IllegalStateException(s"missing resource $ResourcePath")
    val text =
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    parse(text).fold(e => throw new IllegalStateException(e), identity)
  }

  def parse(text: String): Either[String, IndexSpecCatalog] =
    try {
      val o = ujson.read(text).obj
      if (
        o("format").str != "scholarchain.storage.s3vectors-index-spec" || o(
          "formatVersion"
        ).num != 1
      )
        Left("unsupported index spec format")
      else {
        val entries = o("indexes").arr.toList.map { e =>
          val eo = e.obj
          SemanticDocumentType.fromWire(eo("documentType").str).map { t =>
            IndexSpecEntry(
              eo("name").str,
              eo("configKey").str,
              t,
              eo("filterableKeys").arr.toList.map(_.str),
              eo("nonFilterableKeys").arr.toList.map(_.str)
            )
          }
        }
        entries.collectFirst { case Left(e) => e } match {
          case Some(e) => Left(e)
          case None    =>
            val cat = IndexSpecCatalog(
              o("dimension").num.toInt,
              o("dataType").str,
              o("distanceMetric").str,
              entries.collect { case Right(x) => x }
            )
            cat.validate match {
              case Nil  => Right(cat)
              case errs => Left(errs.mkString("; "))
            }
        }
      }
    } catch { case NonFatal(e) => Left(s"malformed index spec: ${e.getMessage}") }
}
