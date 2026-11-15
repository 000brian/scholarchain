package org.scholarchain.storage.ingest

import java.nio.charset.StandardCharsets

import org.scholarchain.core.{ContentHash, Hashing}
import org.scholarchain.storage.ports.{ObjectRef, ObjectStore, PutOutcome}
import org.scholarchain.storage.s3.S3KeyLayout

/** Result of storing one document text. */
final case class TextPut(ref: ObjectRef, textHash: String, outcome: PutOutcome)

/** Authoritative document text in ordinary S3 (semantic-context zone), content-addressed by its
  * hash: `<semantic-context prefix>/text/v1/index=<index>/sha256=<hex>.txt`. A vector's metadata
  * holds only the `s3://` pointer and the hash; [[fetch]] reads the text back and verifies it.
  */
final class TextStore(store: ObjectStore, layout: S3KeyLayout) {

  def put(indexName: String, text: String): TextPut = {
    val hash = ContentHash.of(text)
    val ref = layout.textObject(indexName, hash)
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    val outcome = store.putIfAbsent(
      ref,
      bytes,
      "text/plain; charset=utf-8",
      Map(ObjectStore.Sha256MetadataKey -> Hashing.sha256Hex(bytes))
    )
    TextPut(ref, hash, outcome)
  }

  /** The text behind a vector result's `textS3Uri`, checked against its `textHash`. */
  def fetch(textS3Uri: String, expectedHash: String): Either[String, String] =
    for {
      ref <- ObjectRef.parse(textS3Uri)
      bytes <- store.get(ref).toRight(s"no object at $textS3Uri")
      text = new String(bytes, StandardCharsets.UTF_8)
      _ <-
        if (ContentHash.of(text) == expectedHash) Right(())
        else Left(s"text at $textS3Uri does not match $expectedHash")
    } yield text
}
