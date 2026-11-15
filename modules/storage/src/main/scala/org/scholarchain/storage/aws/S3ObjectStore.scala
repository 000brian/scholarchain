package org.scholarchain.storage.aws

import java.io.InputStream

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.scholarchain.core.Hashing
import org.scholarchain.storage.ports.{ObjectInfo, ObjectRef, ObjectStore, PutOutcome}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._

/** Ordinary S3 through the synchronous S3 client. Writes use the S3 conditional write
  * `If-None-Match: *` (HTTP 412 when the key exists), so nothing is overwritten. No removal
  * operation exists here.
  */
final class S3ObjectStore(client: S3Client) extends ObjectStore {

  def putIfAbsent(
      ref: ObjectRef,
      bytes: Array[Byte],
      contentType: String,
      metadata: Map[String, String]
  ): PutOutcome = {
    val sha = Hashing.sha256Hex(bytes)
    val req = PutObjectRequest
      .builder()
      .bucket(ref.bucket)
      .key(ref.key)
      .contentType(contentType)
      .metadata((metadata + (ObjectStore.Sha256MetadataKey -> sha)).asJava)
      .ifNoneMatch("*")
      .build()
    try {
      client.putObject(req, RequestBody.fromBytes(bytes))
      PutOutcome.Created
    } catch {
      case e: S3Exception if e.statusCode == 412 =>
        val existing = head(ref).flatMap(_.sha256)
        if (existing.contains(sha)) PutOutcome.AlreadyPresent else PutOutcome.Conflict(existing)
      case e: software.amazon.awssdk.core.exception.SdkException => throw AwsErrors.wrap(e)
    }
  }

  def get(ref: ObjectRef): Option[Array[Byte]] =
    AwsErrors.recover[Option[Array[Byte]]] { case _: NoSuchKeyException => None } {
      Some(
        client
          .getObjectAsBytes(GetObjectRequest.builder().bucket(ref.bucket).key(ref.key).build())
          .asByteArray()
      )
    }

  def openStream(ref: ObjectRef): Option[InputStream] =
    try Some(client.getObject(GetObjectRequest.builder().bucket(ref.bucket).key(ref.key).build()))
    catch {
      case _: NoSuchKeyException                                 => None
      case e: software.amazon.awssdk.core.exception.SdkException => throw AwsErrors.wrap(e)
    }

  def head(ref: ObjectRef): Option[ObjectInfo] =
    try {
      val r = client.headObject(HeadObjectRequest.builder().bucket(ref.bucket).key(ref.key).build())
      Some(
        ObjectInfo(
          ref,
          r.contentLength().longValue,
          Option(r.metadata().get(ObjectStore.Sha256MetadataKey))
        )
      )
    } catch {
      case _: NoSuchKeyException                                 => None
      case e: S3Exception if e.statusCode == 404                 => None
      case e: software.amazon.awssdk.core.exception.SdkException => throw AwsErrors.wrap(e)
    }

  def list(bucket: String, prefix: String, maxKeys: Int): List[ObjectInfo] = {
    require(maxKeys >= 1 && maxKeys <= 100000, "maxKeys must be in 1..100000")
    val out = mutable.ListBuffer.empty[ObjectInfo]
    var token: Option[String] = None
    var more = true
    while (more && out.size < maxKeys) {
      val b = ListObjectsV2Request
        .builder()
        .bucket(bucket)
        .prefix(prefix)
        .maxKeys(math.min(1000, maxKeys - out.size))
      token.foreach(b.continuationToken)
      val r = AwsErrors.guard(client.listObjectsV2(b.build()))
      r.contents()
        .asScala
        .foreach(o => out += ObjectInfo(ObjectRef(bucket, o.key()), o.size().longValue, None))
      token = Option(r.nextContinuationToken())
      more = r.isTruncated() && token.isDefined
    }
    out.toList.take(maxKeys)
  }
}

object S3ObjectStore {
  def create(settings: AwsClientSettings): S3ObjectStore =
    new S3ObjectStore(
      S3Client
        .builder()
        .region(settings.awsRegion)
        .httpClient(settings.httpClient())
        .overrideConfiguration(settings.overrides())
        .build()
    )
}
