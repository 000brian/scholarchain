package org.scholarchain.storage.local

import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable

import org.scholarchain.core.VectorKey
import org.scholarchain.storage.{S3VectorsLimits, StoreException}
import org.scholarchain.storage.ports._
import org.scholarchain.storage.vectors.{IndexSpecCatalog, VectorMetadataCodec}

/** In-memory S3 Vectors bucket holding the indexes of the index spec. It enforces the service
  * limits the adapter relies on (dimension, at most 500 vectors per put, at most 100 keys per get,
  * key length, metadata limits, filters on filterable keys only) and supports fault injection for
  * retry tests: `failNext(n, transient)` makes the next n put calls fail.
  */
final class InMemoryVectorIndex(spec: IndexSpecCatalog) extends VectorIndexPort {
  private val indexes: Map[String, mutable.LinkedHashMap[String, VectorRecord]] =
    spec.indexes.map(i => i.name -> mutable.LinkedHashMap.empty[String, VectorRecord]).toMap
  private var pendingFailures = 0
  private var pendingTransient = true
  val putCalls = new AtomicLong()
  val vectorsWritten = new AtomicLong()
  val failedCalls = new AtomicLong()

  def failNext(n: Int, transient: Boolean): Unit = synchronized {
    pendingFailures = n
    pendingTransient = transient
  }

  private def index(name: String) =
    indexes.getOrElse(name, throw new StoreException(s"NotFoundException: index '$name'", false))

  def describeIndex(name: String): Option[IndexDescription] =
    spec
      .forName(name)
      .map(i =>
        IndexDescription(i.name, spec.dimension, spec.distanceMetric, i.nonFilterableKeys.toSet)
      )

  def putVectors(name: String, vectors: Seq[VectorRecord]): Unit = synchronized {
    putCalls.incrementAndGet()
    if (pendingFailures > 0) {
      pendingFailures -= 1
      failedCalls.incrementAndGet()
      throw new StoreException(
        if (pendingTransient) "TooManyRequestsException: injected throttling"
        else "ValidationException: injected",
        pendingTransient
      )
    }
    val idx = index(name)
    val entry = spec.forName(name).get
    if (vectors.isEmpty || vectors.size > S3VectorsLimits.MaxPutVectorsBatch)
      throw new StoreException(s"ValidationException: ${vectors.size} vectors per call", false)
    vectors.foreach { v =>
      if (!VectorKey.isValid(v.key))
        throw new StoreException(s"ValidationException: key '${v.key}'", false)
      if (v.data.length != spec.dimension)
        throw new StoreException(
          s"ValidationException: dimension ${v.data.length} != ${spec.dimension}",
          false
        )
      if (v.data.exists(f => f.isNaN || f.isInfinite))
        throw new StoreException("ValidationException: non-finite vector value", false)
      val errs = VectorMetadataCodec.checkLimits(v.metadata, entry, None)
      if (errs.nonEmpty)
        throw new StoreException(s"ValidationException: ${errs.mkString("; ")}", false)
    }
    vectors.foreach(v => idx.update(v.key, v.copy(data = v.data.clone())))
    vectorsWritten.addAndGet(vectors.size.toLong)
  }

  def getVectors(name: String, keys: Seq[String], returnData: Boolean): List[VectorRecord] =
    synchronized {
      if (keys.size > S3VectorsLimits.MaxGetVectorsBatch)
        throw new StoreException(s"ValidationException: ${keys.size} keys per GetVectors", false)
      val idx = index(name)
      keys.toList
        .flatMap(idx.get)
        .map(v =>
          if (returnData) v.copy(data = v.data.clone()) else v.copy(data = Array.emptyFloatArray)
        )
    }

  def query(
      name: String,
      vector: Array[Float],
      topK: Int,
      filter: Option[MetadataFilter],
      returnMetadata: Boolean
  ): List[VectorHit] = synchronized {
    val idx = index(name)
    val entry = spec.forName(name).get
    if (topK < 1) throw new StoreException(s"ValidationException: topK $topK", false)
    if (vector.length != spec.dimension)
      throw new StoreException("ValidationException: query dimension", false)
    filter.foreach { f =>
      val bad = MetadataFilter.keys(f) -- entry.filterableKeys.toSet
      if (bad.nonEmpty)
        throw new StoreException(
          s"ValidationException: not filterable: ${bad.mkString(",")}",
          false
        )
    }
    val qn = norm(vector)
    idx.valuesIterator
      .filter(v => filter.forall(f => InMemoryVectorIndex.matches(f, v.metadata)))
      .map(v => (v, 1.0 - dot(vector, v.data) / (qn * norm(v.data)).max(1e-12)))
      .toList
      .sortBy { case (v, d) => (d, v.key) }
      .take(topK)
      .map { case (v, d) => VectorHit(v.key, d, if (returnMetadata) Some(v.metadata) else None) }
  }

  private def dot(a: Array[Float], b: Array[Float]): Double = {
    var s = 0.0
    var i = 0
    while (i < a.length) { s += a(i).toDouble * b(i); i += 1 }
    s
  }
  private def norm(a: Array[Float]): Double = math.sqrt(dot(a, a))

  def count(name: String): Int = synchronized(index(name).size)
  def keys(name: String): List[String] = synchronized(index(name).keys.toList)
}

object InMemoryVectorIndex {
  def matches(f: MetadataFilter, m: StoredMetadata): Boolean = {
    val all = m.all
    f match {
      case MetadataFilter.Eq(k, v)  => all.get(k).contains(v)
      case MetadataFilter.In(k, vs) => all.get(k).exists(vs.contains)
      case MetadataFilter.Gte(k, x) =>
        all.get(k).exists { case MetaValue.Num(n) => n >= x; case _ => false }
      case MetadataFilter.Lte(k, x) =>
        all.get(k).exists { case MetaValue.Num(n) => n <= x; case _ => false }
      case MetadataFilter.And(fs) => fs.forall(matches(_, m))
    }
  }
}
