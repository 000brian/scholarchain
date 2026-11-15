package org.scholarchain.storage.local

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import scala.jdk.CollectionConverters._

import org.scholarchain.core.Hashing
import org.scholarchain.storage.StoreException
import org.scholarchain.storage.dynamo.ItemCodec
import org.scholarchain.storage.ports._

/** In-memory ordinary S3 with S3's conditional-write semantics (put-if-absent only). */
final class InMemoryObjectStore extends ObjectStore {
  private final case class Obj(bytes: Array[Byte], meta: Map[String, String])
  private val objects = new ConcurrentHashMap[String, Obj]()
  val creates = new AtomicLong()

  private def id(ref: ObjectRef) = s"${ref.bucket}/${ref.key}"

  def putIfAbsent(
      ref: ObjectRef,
      bytes: Array[Byte],
      contentType: String,
      metadata: Map[String, String]
  ): PutOutcome = {
    val sha = Hashing.sha256Hex(bytes)
    val prev = objects.putIfAbsent(
      id(ref),
      Obj(bytes.clone(), metadata + (ObjectStore.Sha256MetadataKey -> sha))
    )
    if (prev == null) { creates.incrementAndGet(); PutOutcome.Created }
    else if (prev.meta.get(ObjectStore.Sha256MetadataKey).contains(sha)) PutOutcome.AlreadyPresent
    else PutOutcome.Conflict(prev.meta.get(ObjectStore.Sha256MetadataKey))
  }

  def get(ref: ObjectRef): Option[Array[Byte]] = Option(objects.get(id(ref))).map(_.bytes.clone())
  def openStream(ref: ObjectRef): Option[InputStream] = get(ref).map(new ByteArrayInputStream(_))
  def head(ref: ObjectRef): Option[ObjectInfo] =
    Option(objects.get(id(ref))).map(o =>
      ObjectInfo(ref, o.bytes.length.toLong, o.meta.get(ObjectStore.Sha256MetadataKey))
    )

  def list(bucket: String, prefix: String, maxKeys: Int): List[ObjectInfo] =
    objects.keySet.asScala.toList
      .filter(_.startsWith(s"$bucket/$prefix"))
      .sorted
      .take(maxKeys)
      .flatMap(k => head(ObjectRef(bucket, k.stripPrefix(s"$bucket/"))))

  def objectCount: Int = objects.size
  def totalBytes: Long = objects.values.asScala.map(_.bytes.length.toLong).sum
}

/** Ordinary S3 on the local file system: `<root>/<bucket>/<key>`, with a `.sha256` sidecar. Files
  * are created with CREATE_NEW, so nothing is ever overwritten.
  */
final class LocalFileObjectStore(root: Path) extends ObjectStore {
  private def path(ref: ObjectRef): Path = {
    val parts = (ref.bucket +: ref.key.split('/').toSeq)
    require(!parts.exists(p => p.isEmpty || p == "." || p == ".."), s"invalid key ${ref.key}")
    parts.foldLeft(root)(_.resolve(_))
  }
  private def shaPath(p: Path) = p.resolveSibling(p.getFileName.toString + ".sha256")

  def putIfAbsent(
      ref: ObjectRef,
      bytes: Array[Byte],
      contentType: String,
      metadata: Map[String, String]
  ): PutOutcome = synchronized {
    val p = path(ref)
    val sha = Hashing.sha256Hex(bytes)
    if (Files.exists(p)) {
      val existing = if (Files.exists(shaPath(p))) Some(Files.readString(shaPath(p)).trim) else None
      if (existing.contains(sha)) PutOutcome.AlreadyPresent else PutOutcome.Conflict(existing)
    } else {
      Files.createDirectories(p.getParent)
      Files.write(p, bytes, StandardOpenOption.CREATE_NEW)
      Files.write(shaPath(p), sha.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW)
      PutOutcome.Created
    }
  }

  def get(ref: ObjectRef): Option[Array[Byte]] = {
    val p = path(ref)
    if (Files.isRegularFile(p)) Some(Files.readAllBytes(p)) else None
  }
  def openStream(ref: ObjectRef): Option[InputStream] = {
    val p = path(ref)
    if (Files.isRegularFile(p)) Some(Files.newInputStream(p)) else None
  }
  def head(ref: ObjectRef): Option[ObjectInfo] = {
    val p = path(ref)
    if (!Files.isRegularFile(p)) None
    else
      Some(
        ObjectInfo(
          ref,
          Files.size(p),
          if (Files.exists(shaPath(p))) Some(Files.readString(shaPath(p)).trim) else None
        )
      )
  }
  def list(bucket: String, prefix: String, maxKeys: Int): List[ObjectInfo] = {
    val b = root.resolve(bucket)
    if (!Files.isDirectory(b)) Nil
    else {
      val s = Files.walk(b)
      try
        s.iterator.asScala
          .filter(p => Files.isRegularFile(p) && !p.getFileName.toString.endsWith(".sha256"))
          .map(p => b.relativize(p).toString.replace(java.io.File.separatorChar, '/'))
          .filter(_.startsWith(prefix))
          .toList
          .sorted
          .take(maxKeys)
          .flatMap(k => head(ObjectRef(bucket, k)))
      finally s.close()
    }
  }
}

/** In-memory DynamoDB tables with the same conditional semantics as the AWS adapter. Items are kept
  * in their compact JSON form (UTF-8 bytes) to bound memory on full local loads. Thread-safe.
  */
final class InMemoryKeyValueStore(tables: Set[String]) extends KeyValueStore {
  private val data: Map[String, ConcurrentHashMap[String, java.util.TreeMap[String, Array[Byte]]]] =
    tables
      .map(t => t -> new ConcurrentHashMap[String, java.util.TreeMap[String, Array[Byte]]]())
      .toMap
  private val writes = new ConcurrentHashMap[String, AtomicLong]()

  private def table(t: String) =
    data.getOrElse(
      t,
      throw new StoreException(s"ResourceNotFoundException: table '$t' does not exist", false)
    )

  private def partition(t: String, pk: String) =
    table(t).computeIfAbsent(pk, _ => new java.util.TreeMap[String, Array[Byte]]())

  private def enc(i: Item) = ItemCodec.encode(i).getBytes(StandardCharsets.UTF_8)
  private def dec(b: Array[Byte]) = ItemCodec.decode(new String(b, StandardCharsets.UTF_8))
  private def countWrite(t: String): Unit =
    writes.computeIfAbsent(t, _ => new AtomicLong()).incrementAndGet()

  /** Successful writes per table since creation (every Written result). */
  def writeCount(t: String): Long = Option(writes.get(t)).map(_.get).getOrElse(0L)
  def itemCount(t: String): Long =
    table(t).values.asScala.map(p => p.synchronized(p.size.toLong)).sum

  def putIfAbsent(t: String, item: Item): ConditionalResult = {
    val k = item.key
    val p = partition(t, k.pk)
    p.synchronized {
      if (p.containsKey(k.sk)) ConditionalResult.ConditionFailed
      else { p.put(k.sk, enc(item)); countWrite(t); ConditionalResult.Written }
    }
  }

  def putIfVersion(t: String, item: Item, expectedVersion: Long): ConditionalResult = {
    val k = item.key
    val p = partition(t, k.pk)
    p.synchronized {
      Option(p.get(k.sk)).map(dec) match {
        case Some(existing) if existing.long(Item.Version).contains(expectedVersion) =>
          p.put(k.sk, enc(item)); countWrite(t); ConditionalResult.Written
        case _ => ConditionalResult.ConditionFailed
      }
    }
  }

  def get(t: String, key: ItemKey): Option[Item] = {
    val p = table(t).get(key.pk)
    if (p == null) None else p.synchronized(Option(p.get(key.sk)).map(dec))
  }

  def query(
      t: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int,
      descending: Boolean
  ): List[Item] = {
    val p = table(t).get(pk)
    if (p == null) Nil
    else
      p.synchronized {
        val m = if (descending) p.descendingMap() else p
        m.entrySet.asScala.iterator
          .filter(e => skPrefix.forall(e.getKey.startsWith))
          .take(limit)
          .map(e => dec(e.getValue))
          .toList
      }
  }

  /** Every item of a table (tests and local reports only). */
  def scanAll(t: String): Iterator[Item] =
    table(t).values.asScala.iterator.flatMap(p =>
      p.synchronized(p.values.asScala.toList).iterator.map(dec)
    )
}
