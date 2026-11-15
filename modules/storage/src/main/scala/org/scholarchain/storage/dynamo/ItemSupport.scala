package org.scholarchain.storage.dynamo

import java.nio.charset.StandardCharsets

import scala.collection.immutable.ListMap

import org.scholarchain.core.Hashing
import org.scholarchain.storage.ports.{AttrValue, ConditionalResult, Item, KeyValueStore}

/** DynamoDB item size by the documented sizing rules: attribute name bytes plus value size, where a
  * string is its UTF-8 length, a number about one byte per two significant digits plus one, a
  * boolean one byte, and a list or map 3 bytes plus one byte per element plus the elements.
  */
object ItemSize {
  def of(item: Item): Int = item.attributes.iterator.map { case (k, v) =>
    utf8(k) + value(v)
  }.sum

  private def utf8(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  def value(v: AttrValue): Int = v match {
    case AttrValue.S(s) => utf8(s)
    case AttrValue.N(n) =>
      val digits = n.count(_.isDigit).max(1)
      (digits + 1) / 2 + 1
    case AttrValue.Bool(_) => 1
    case AttrValue.L(xs)   => 3 + xs.map(x => 1 + value(x)).sum
    case AttrValue.M(m)    => 3 + m.iterator.map { case (k, x) => 1 + utf8(k) + value(x) }.sum
  }

  /** On-demand write request units for one standard write of the item (1 KB each). */
  def writeUnits(item: Item): Int = math.max(1, math.ceil(of(item) / 1024.0).toInt)
}

object ItemHash {

  /** `sha256:<hex>` of the item's attributes sorted by name, excluding `version` and `itemHash`.
    * Two loads of the same facts give the same hash, so an unchanged item is never rewritten.
    */
  def of(item: Item): String = {
    val canon = item.attributes.toList
      .filterNot { case (k, _) => k == Item.Version || k == Item.ItemHash }
      .sortBy(_._1)
      .map { case (k, v) => s"$k=${render(v)}" }
    "sha256:" + Hashing.sha256Hex(Hashing.canonicalEncoding(canon))
  }

  private def render(v: AttrValue): String = v match {
    case AttrValue.S(s)    => "S" + Hashing.canonicalEncoding(Seq(s))
    case AttrValue.N(n)    => "N" + n
    case AttrValue.Bool(b) => "B" + b
    case AttrValue.L(xs)   => "L[" + xs.map(render).mkString(",") + "]"
    case AttrValue.M(m)    =>
      "M{" + m.toList
        .sortBy(_._1)
        .map { case (k, x) => s"${Hashing.canonicalEncoding(Seq(k))}:${render(x)}" }
        .mkString(",") + "}"
  }

  /** The item with `itemHash` and `version` set. */
  def stamp(item: Item, version: Long): Item =
    Item(
      item.attributes.removed(Item.Version).removed(Item.ItemHash) ++ ListMap(
        Item.ItemHash -> AttrValue.S(of(item)),
        Item.Version -> AttrValue.n(version)
      )
    )
}

/** What one conditional load write did. */
sealed trait Change
object Change {
  case object Inserted extends Change
  case object Updated extends Change
  case object Unchanged extends Change

  /** A concurrent writer won the version check, or an append-only key already holds different
    * content. Nothing was written; the event is reported.
    */
  case object Conflict extends Change
}

/** Idempotent loads on top of the conditional [[KeyValueStore]] primitives. */
final class ConditionalWriter(store: KeyValueStore) {

  /** Mutable tables: insert if absent (version 1); if present with the same item hash, do nothing;
    * otherwise replace with version + 1 under a version check. A rerun of the same load therefore
    * makes 0 changes.
    */
  def upsertIfChanged(table: String, item: Item): Change = {
    val fresh = ItemHash.stamp(item, 1L)
    store.putIfAbsent(table, fresh) match {
      case ConditionalResult.Written         => Change.Inserted
      case ConditionalResult.ConditionFailed =>
        store.get(table, fresh.key) match {
          case None => Change.Conflict
          case Some(existing) if existing.strOpt(Item.ItemHash) == fresh.strOpt(Item.ItemHash) =>
            Change.Unchanged
          case Some(existing) =>
            val v = existing.long(Item.Version).getOrElse(0L)
            store.putIfVersion(table, ItemHash.stamp(item, v + 1), v) match {
              case ConditionalResult.Written         => Change.Updated
              case ConditionalResult.ConditionFailed => Change.Conflict
            }
        }
    }
  }

  /** Append-only tables: create if absent; an existing item with the same hash is a rerun
    * (unchanged); with a different hash it is a conflict and is never overwritten.
    */
  def append(table: String, item: Item): Change = {
    val fresh = ItemHash.stamp(item, 1L)
    store.putIfAbsent(table, fresh) match {
      case ConditionalResult.Written         => Change.Inserted
      case ConditionalResult.ConditionFailed =>
        store.get(table, fresh.key) match {
          case Some(e) if e.strOpt(Item.ItemHash) == fresh.strOpt(Item.ItemHash) => Change.Unchanged
          case _                                                                 => Change.Conflict
        }
    }
  }
}
