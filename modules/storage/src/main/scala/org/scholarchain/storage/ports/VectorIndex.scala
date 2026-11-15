package org.scholarchain.storage.ports

import scala.collection.immutable.ListMap

/** An S3 Vectors metadata value: string, number, boolean or list of strings. */
sealed trait MetaValue
object MetaValue {
  final case class Str(value: String) extends MetaValue
  final case class Num(value: Long) extends MetaValue
  final case class Bool(value: Boolean) extends MetaValue
  final case class StrList(values: List[String]) extends MetaValue
}

/** Metadata of one stored vector, split as the index spec says. Keys are disjoint. */
final case class StoredMetadata(
    filterable: ListMap[String, MetaValue],
    nonFilterable: ListMap[String, MetaValue]
) {
  def all: ListMap[String, MetaValue] = filterable ++ nonFilterable
  def keyCount: Int = filterable.size + nonFilterable.size
}

/** One vector to put or one vector read back. `data` has the index dimension. */
final case class VectorRecord(key: String, data: Array[Float], metadata: StoredMetadata)

/** One query hit. `distance` is the index metric's distance (cosine: 1 - cosine similarity). */
final case class VectorHit(key: String, distance: Double, metadata: Option[StoredMetadata])

/** Index configuration as reported by the service (or the fake). */
final case class IndexDescription(
    name: String,
    dimension: Int,
    distanceMetric: String,
    nonFilterableKeys: Set[String]
)

/** A metadata filter over filterable keys only (S3 Vectors filter operators). */
sealed trait MetadataFilter
object MetadataFilter {
  final case class Eq(key: String, value: MetaValue) extends MetadataFilter
  final case class In(key: String, values: List[MetaValue]) extends MetadataFilter
  final case class Gte(key: String, value: Long) extends MetadataFilter
  final case class Lte(key: String, value: Long) extends MetadataFilter
  final case class And(filters: List[MetadataFilter]) extends MetadataFilter

  def keys(f: MetadataFilter): Set[String] = f match {
    case Eq(k, _)  => Set(k)
    case In(k, _)  => Set(k)
    case Gte(k, _) => Set(k)
    case Lte(k, _) => Set(k)
    case And(fs)   => fs.flatMap(keys).toSet
  }
}

/** S3 Vectors indexes in the configured vector bucket.
  *
  * Indexes are created by Terraform from the index spec file; this port only describes them. Writes
  * are `putVectors` (an existing key is replaced, which is why the ingest pipeline checks the
  * ArtifactRegistry first and never overwrites silently). There is no removal operation.
  *
  * Failures surface as `StoreException`: throttling and 5xx are transient; validation failures are
  * permanent. Callers bound retries with `RetryPolicy`.
  */
trait VectorIndexPort {
  def describeIndex(index: String): Option[IndexDescription]

  /** At most 500 vectors per call (the caller batches). */
  def putVectors(index: String, vectors: Seq[VectorRecord]): Unit

  /** At most 100 keys per call. Missing keys are absent from the result. */
  def getVectors(index: String, keys: Seq[String], returnData: Boolean): List[VectorRecord]

  def query(
      index: String,
      vector: Array[Float],
      topK: Int,
      filter: Option[MetadataFilter],
      returnMetadata: Boolean
  ): List[VectorHit]
}
