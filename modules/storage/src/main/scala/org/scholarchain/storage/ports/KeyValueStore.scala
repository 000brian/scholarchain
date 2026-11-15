package org.scholarchain.storage.ports

import scala.collection.immutable.ListMap

/** A DynamoDB attribute value (the subset storage writes). */
sealed trait AttrValue
object AttrValue {
  final case class S(value: String) extends AttrValue

  /** A number in its canonical decimal string form (DynamoDB numbers travel as strings). */
  final case class N(value: String) extends AttrValue
  final case class Bool(value: Boolean) extends AttrValue
  final case class L(values: List[AttrValue]) extends AttrValue
  final case class M(values: ListMap[String, AttrValue]) extends AttrValue

  def n(i: Long): N = N(i.toString)

  /** Finite doubles only; the plain (non-scientific) decimal form keeps the canonical string. */
  def n(d: Double): N = {
    require(!d.isNaN && !d.isInfinite, s"non-finite number $d")
    N(java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString)
  }
}

/** One DynamoDB item. Every table uses the generic key attributes `pk` (partition) and `sk` (sort),
  * both strings.
  */
final case class Item(attributes: ListMap[String, AttrValue]) {
  def key: ItemKey = ItemKey(str(Item.Pk), str(Item.Sk))
  def get(name: String): Option[AttrValue] = attributes.get(name)
  def str(name: String): String = attributes.get(name) match {
    case Some(AttrValue.S(v)) => v
    case other => throw new IllegalArgumentException(s"$name is not a string: $other")
  }
  def strOpt(name: String): Option[String] = attributes.get(name).collect { case AttrValue.S(v) =>
    v
  }
  def long(name: String): Option[Long] = attributes.get(name).collect { case AttrValue.N(v) =>
    v.toLong
  }
  def updated(name: String, v: AttrValue): Item = Item(attributes.updated(name, v))
}

object Item {
  val Pk = "pk"
  val Sk = "sk"
  val Version = "version"
  val ItemHash = "itemHash"
  def apply(attrs: (String, AttrValue)*): Item = Item(ListMap(attrs: _*))
}

final case class ItemKey(pk: String, sk: String)

/** Outcome of a conditional item write. */
sealed trait ConditionalResult
object ConditionalResult {
  case object Written extends ConditionalResult

  /** The condition did not hold (the item exists, or its version differs). Nothing was written. */
  case object ConditionFailed extends ConditionalResult
}

/** DynamoDB tables. Every write is conditional:
  *   - `putIfAbsent`: `attribute_not_exists(pk)`;
  *   - `putIfVersion`: replaces an existing item only when its `version` equals `expectedVersion`
  *     (optimistic locking).
  *
  * There is no unconditional put, no batch write without conditions and no removal operation.
  * Throttling and 5xx errors surface as transient `StoreException`s after the SDK's own retries.
  */
trait KeyValueStore {
  def putIfAbsent(table: String, item: Item): ConditionalResult
  def putIfVersion(table: String, item: Item, expectedVersion: Long): ConditionalResult
  def get(table: String, key: ItemKey): Option[Item]

  /** Items of one partition, in sort-key order (descending when asked), optionally restricted to
    * sort keys starting with `skPrefix`, at most `limit` items.
    */
  def query(
      table: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int,
      descending: Boolean
  ): List[Item]
}
