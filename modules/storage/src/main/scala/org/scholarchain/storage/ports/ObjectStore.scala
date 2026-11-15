package org.scholarchain.storage.ports

import java.io.InputStream

/** An S3 object address. The bucket always comes from configuration. */
final case class ObjectRef(bucket: String, key: String) {
  def uri: String = s"s3://$bucket/$key"
}

object ObjectRef {
  private val Uri = "s3://([a-z0-9][a-z0-9.-]{1,61}[a-z0-9])/(\\S+)".r

  def parse(uri: String): Either[String, ObjectRef] = uri match {
    case Uri(bucket, key) => Right(ObjectRef(bucket, key))
    case _                => Left(s"not an s3://<bucket>/<key> URI: '${Option(uri).getOrElse("")}'")
  }
}

/** Object metadata returned by `head` and `list`. `sha256` is the user metadata written by
  * [[ObjectStore.putIfAbsent]] (hex of the object bytes) when present.
  */
final case class ObjectInfo(ref: ObjectRef, size: Long, sha256: Option[String])

/** Outcome of a conditional (put-if-absent) object write. */
sealed trait PutOutcome
object PutOutcome {

  /** The object did not exist and was written. */
  case object Created extends PutOutcome

  /** The object already existed with the same sha256: nothing was written (idempotent rerun). */
  case object AlreadyPresent extends PutOutcome

  /** The object already existed with different (or unknown) content: nothing was written. Keys are
    * versioned (they carry run IDs or content hashes), so this signals a key-design or provenance
    * error and is reported, never overwritten.
    */
  final case class Conflict(existingSha256: Option[String]) extends PutOutcome
}

/** Ordinary S3 (raw, curated, graph, semantic-context, reports and logs zones).
  *
  * Writes are put-if-absent only (S3 conditional write `If-None-Match: *`); there is no overwrite
  * and no removal operation in this port. Cleanup belongs to the approved cleanup script.
  */
trait ObjectStore {
  def putIfAbsent(
      ref: ObjectRef,
      bytes: Array[Byte],
      contentType: String,
      metadata: Map[String, String]
  ): PutOutcome
  def get(ref: ObjectRef): Option[Array[Byte]]
  def openStream(ref: ObjectRef): Option[InputStream]
  def head(ref: ObjectRef): Option[ObjectInfo]

  /** At most `maxKeys` objects under `prefix`, in key order. */
  def list(bucket: String, prefix: String, maxKeys: Int): List[ObjectInfo]
}

object ObjectStore {

  /** User-metadata key carrying the hex sha256 of the object bytes. */
  val Sha256MetadataKey = "sha256"
}
