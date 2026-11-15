package org.scholarchain.storage.aws

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters._

import org.scholarchain.storage.S3VectorsLimits
import org.scholarchain.storage.ports._
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model._

/** S3 Vectors indexes in one vector bucket (from configuration) through the synchronous client.
  * Indexes are created by Terraform; this adapter only describes, writes, reads and queries them.
  */
final class S3VectorsIndex(
    client: S3VectorsClient,
    vectorBucket: String,
    nonFilterable: String => Set[String]
) extends VectorIndexPort {

  def describeIndex(index: String): Option[IndexDescription] =
    AwsErrors.recover[Option[IndexDescription]] { case _: NotFoundException => None } {
      val i = client
        .getIndex(GetIndexRequest.builder().vectorBucketName(vectorBucket).indexName(index).build())
        .index()
      val nf = Option(i.metadataConfiguration())
        .map(_.nonFilterableMetadataKeys().asScala.toSet)
        .getOrElse(Set.empty[String])
      Some(IndexDescription(i.indexName(), i.dimension().intValue, i.distanceMetricAsString(), nf))
    }

  private def toDoc(v: MetaValue): Document = v match {
    case MetaValue.Str(s)      => Document.fromString(s)
    case MetaValue.Num(n)      => Document.fromNumber(n)
    case MetaValue.Bool(b)     => Document.fromBoolean(b)
    case MetaValue.StrList(xs) => Document.fromList(xs.map(Document.fromString).asJava)
  }

  private def toDoc(m: StoredMetadata): Document =
    Document.fromMap(m.all.map { case (k, v) => k -> toDoc(v) }.asJava)

  private def fromDoc(d: Document, nf: Set[String]): StoredMetadata = {
    val all = d.asMap().asScala.toList.sortBy(_._1).map { case (k, v) =>
      val mv: MetaValue =
        if (v.isString) MetaValue.Str(v.asString())
        else if (v.isNumber) MetaValue.Num(v.asNumber().longValue())
        else if (v.isBoolean) MetaValue.Bool(v.asBoolean())
        else if (v.isList) MetaValue.StrList(v.asList().asScala.toList.map(_.asString()))
        else throw new IllegalArgumentException(s"unsupported metadata value for $k")
      k -> mv
    }
    StoredMetadata(
      ListMap.from(all.filterNot(kv => nf(kv._1))),
      ListMap.from(all.filter(kv => nf(kv._1)))
    )
  }

  private def data(v: Array[Float]): VectorData =
    VectorData.builder().float32(v.toSeq.map(java.lang.Float.valueOf).asJava).build()

  def putVectors(index: String, vectors: Seq[VectorRecord]): Unit = {
    require(
      vectors.nonEmpty && vectors.size <= S3VectorsLimits.MaxPutVectorsBatch,
      s"${vectors.size} vectors per PutVectors"
    )
    val in = vectors.map(v =>
      PutInputVector.builder().key(v.key).data(data(v.data)).metadata(toDoc(v.metadata)).build()
    )
    AwsErrors.guard(
      client.putVectors(
        PutVectorsRequest
          .builder()
          .vectorBucketName(vectorBucket)
          .indexName(index)
          .vectors(in.asJava)
          .build()
      )
    )
    ()
  }

  def getVectors(index: String, keys: Seq[String], returnData: Boolean): List[VectorRecord] = {
    require(keys.size <= S3VectorsLimits.MaxGetVectorsBatch, s"${keys.size} keys per GetVectors")
    val nf = nonFilterable(index)
    val r = AwsErrors.guard(
      client.getVectors(
        GetVectorsRequest
          .builder()
          .vectorBucketName(vectorBucket)
          .indexName(index)
          .keys(keys.asJava)
          .returnData(returnData)
          .returnMetadata(true)
          .build()
      )
    )
    r.vectors().asScala.toList.map { v =>
      val d =
        if (returnData && v.data() != null) v.data().float32().asScala.map(_.floatValue).toArray
        else Array.emptyFloatArray
      VectorRecord(
        v.key(),
        d,
        Option(v.metadata())
          .map(fromDoc(_, nf))
          .getOrElse(StoredMetadata(ListMap.empty, ListMap.empty))
      )
    }
  }

  private def filterDoc(f: MetadataFilter): Document = f match {
    case MetadataFilter.Eq(k, v) =>
      Document.fromMap(Map(k -> Document.fromMap(Map("$eq" -> toDoc(v)).asJava)).asJava)
    case MetadataFilter.In(k, vs) =>
      Document.fromMap(
        Map(
          k -> Document.fromMap(Map("$in" -> Document.fromList(vs.map(toDoc).asJava)).asJava)
        ).asJava
      )
    case MetadataFilter.Gte(k, n) =>
      Document.fromMap(
        Map(k -> Document.fromMap(Map("$gte" -> Document.fromNumber(n)).asJava)).asJava
      )
    case MetadataFilter.Lte(k, n) =>
      Document.fromMap(
        Map(k -> Document.fromMap(Map("$lte" -> Document.fromNumber(n)).asJava)).asJava
      )
    case MetadataFilter.And(fs) =>
      Document.fromMap(Map("$and" -> Document.fromList(fs.map(filterDoc).asJava)).asJava)
  }

  def query(
      index: String,
      vector: Array[Float],
      topK: Int,
      filter: Option[MetadataFilter],
      returnMetadata: Boolean
  ): List[VectorHit] = {
    val nf = nonFilterable(index)
    filter.foreach { f =>
      val bad = MetadataFilter.keys(f).intersect(nf)
      require(bad.isEmpty, s"filter on non-filterable keys ${bad.mkString(",")}")
    }
    val b = QueryVectorsRequest
      .builder()
      .vectorBucketName(vectorBucket)
      .indexName(index)
      .queryVector(data(vector))
      .topK(topK)
      .returnMetadata(returnMetadata)
      .returnDistance(true)
    filter.foreach(f => b.filter(filterDoc(f)))
    AwsErrors.guard(client.queryVectors(b.build())).vectors().asScala.toList.map { v =>
      VectorHit(
        v.key(),
        Option(v.distance()).map(_.doubleValue).getOrElse(Double.NaN),
        Option(v.metadata()).filter(_ => returnMetadata).map(fromDoc(_, nf))
      )
    }
  }
}

object S3VectorsIndex {
  def create(
      settings: AwsClientSettings,
      vectorBucket: String,
      nonFilterable: String => Set[String]
  ): S3VectorsIndex =
    new S3VectorsIndex(
      S3VectorsClient
        .builder()
        .region(settings.awsRegion)
        .httpClient(settings.httpClient())
        .overrideConfiguration(settings.overrides())
        .build(),
      vectorBucket,
      nonFilterable
    )
}
