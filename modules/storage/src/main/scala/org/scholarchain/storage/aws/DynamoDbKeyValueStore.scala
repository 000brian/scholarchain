package org.scholarchain.storage.aws

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.scholarchain.storage.ports._
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue => Av, _}

/** DynamoDB tables through the synchronous client. Every write is a conditional PutItem; reads are
  * strongly consistent. Throttling is retried by the SDK's standard retry strategy and then
  * surfaces as a transient `StoreException`.
  */
final class DynamoDbKeyValueStore(client: DynamoDbClient) extends KeyValueStore {

  private def toAv(v: AttrValue): Av = v match {
    case AttrValue.S(s)    => Av.fromS(s)
    case AttrValue.N(n)    => Av.fromN(n)
    case AttrValue.Bool(b) => Av.fromBool(b)
    case AttrValue.L(xs)   => Av.fromL(xs.map(toAv).asJava)
    case AttrValue.M(m)    => Av.fromM(m.map { case (k, x) => k -> toAv(x) }.asJava)
  }

  private def fromAv(a: Av): AttrValue = a.`type`() match {
    case Av.Type.S    => AttrValue.S(a.s())
    case Av.Type.N    => AttrValue.N(a.n())
    case Av.Type.BOOL => AttrValue.Bool(a.bool().booleanValue)
    case Av.Type.L    => AttrValue.L(a.l().asScala.toList.map(fromAv))
    case Av.Type.M    =>
      AttrValue.M(
        ListMap.from(a.m().asScala.toList.sortBy(_._1).map { case (k, x) => k -> fromAv(x) })
      )
    case other => throw new IllegalArgumentException(s"unsupported attribute type $other")
  }

  private def item(m: java.util.Map[String, Av]): Item =
    Item(ListMap.from(m.asScala.toList.sortBy(_._1).map { case (k, v) => k -> fromAv(v) }))

  private def avMap(i: Item) = i.attributes.map { case (k, v) => k -> toAv(v) }.asJava

  /** A failed condition is an expected outcome, not an error: it must reach the caller as
    * `ConditionFailed` (not as a `StoreException`), so [[AwsErrors.recover]] maps it before the
    * generic wrapping.
    */
  private val conditionFailed
      : PartialFunction[software.amazon.awssdk.core.exception.SdkException, ConditionalResult] = {
    case _: ConditionalCheckFailedException => ConditionalResult.ConditionFailed
  }

  def putIfAbsent(table: String, i: Item): ConditionalResult =
    AwsErrors.recover(conditionFailed) {
      client.putItem(
        PutItemRequest
          .builder()
          .tableName(table)
          .item(avMap(i))
          .conditionExpression("attribute_not_exists(pk)")
          .build()
      )
      ConditionalResult.Written
    }

  def putIfVersion(table: String, i: Item, expectedVersion: Long): ConditionalResult =
    AwsErrors.recover(conditionFailed) {
      client.putItem(
        PutItemRequest
          .builder()
          .tableName(table)
          .item(avMap(i))
          .conditionExpression("attribute_exists(pk) AND #v = :expected")
          .expressionAttributeNames(Map("#v" -> Item.Version).asJava)
          .expressionAttributeValues(
            Map(":expected" -> Av.fromN(expectedVersion.toString)).asJava
          )
          .build()
      )
      ConditionalResult.Written
    }

  def get(table: String, key: ItemKey): Option[Item] = {
    val r = AwsErrors.guard(
      client.getItem(
        GetItemRequest
          .builder()
          .tableName(table)
          .key(Map(Item.Pk -> Av.fromS(key.pk), Item.Sk -> Av.fromS(key.sk)).asJava)
          .consistentRead(true)
          .build()
      )
    )
    if (r.hasItem && !r.item().isEmpty) Some(item(r.item())) else None
  }

  def query(
      table: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int,
      descending: Boolean
  ): List[Item] = {
    require(limit >= 1 && limit <= 10000, "query limit must be in 1..10000")
    val out = mutable.ListBuffer.empty[Item]
    var start: java.util.Map[String, Av] = null
    var more = true
    while (more && out.size < limit) {
      val values = (Map(":pk" -> Av.fromS(pk)) ++ skPrefix.map(p => ":p" -> Av.fromS(p))).asJava
      val b = QueryRequest
        .builder()
        .tableName(table)
        .keyConditionExpression(
          if (skPrefix.isDefined) "pk = :pk AND begins_with(sk, :p)" else "pk = :pk"
        )
        .expressionAttributeValues(values)
        .scanIndexForward(!descending)
        .consistentRead(true)
        .limit(limit - out.size)
      if (start != null) b.exclusiveStartKey(start)
      val r = AwsErrors.guard(client.query(b.build()))
      r.items().asScala.foreach(m => out += item(m))
      start =
        if (r.hasLastEvaluatedKey && !r.lastEvaluatedKey().isEmpty) r.lastEvaluatedKey() else null
      more = start != null
    }
    out.toList.take(limit)
  }
}

object DynamoDbKeyValueStore {
  def create(settings: AwsClientSettings): DynamoDbKeyValueStore =
    new DynamoDbKeyValueStore(
      DynamoDbClient
        .builder()
        .region(settings.awsRegion)
        .httpClient(settings.httpClient())
        .overrideConfiguration(settings.overrides())
        .build()
    )
}
