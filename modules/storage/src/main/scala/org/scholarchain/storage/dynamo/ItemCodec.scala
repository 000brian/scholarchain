package org.scholarchain.storage.dynamo

import scala.collection.immutable.ListMap

import org.scholarchain.storage.ports.{AttrValue, Item}

/** Compact JSON form of an item in DynamoDB's attribute-value shape (`{"S": ...}`, `{"N": ...}`),
  * used by the in-memory fake and by load-plan files.
  */
object ItemCodec {

  def toJson(v: AttrValue): ujson.Value = v match {
    case AttrValue.S(s)    => ujson.Obj("S" -> s)
    case AttrValue.N(n)    => ujson.Obj("N" -> n)
    case AttrValue.Bool(b) => ujson.Obj("BOOL" -> b)
    case AttrValue.L(xs)   => ujson.Obj("L" -> ujson.Arr.from(xs.map(toJson)))
    case AttrValue.M(m) => ujson.Obj("M" -> ujson.Obj.from(m.map { case (k, x) => k -> toJson(x) }))
  }

  def fromJson(j: ujson.Value): AttrValue = {
    val o = j.obj
    o.headOption match {
      case Some(("S", s))    => AttrValue.S(s.str)
      case Some(("N", n))    => AttrValue.N(n.str)
      case Some(("BOOL", b)) => AttrValue.Bool(b.bool)
      case Some(("L", l))    => AttrValue.L(l.arr.toList.map(fromJson))
      case Some(("M", m))    =>
        AttrValue.M(ListMap.from(m.obj.map { case (k, x) => k -> fromJson(x) }))
      case other => throw new IllegalArgumentException(s"unknown attribute value $other")
    }
  }

  def encode(item: Item): String =
    ujson.Obj.from(item.attributes.map { case (k, v) => k -> toJson(v) }).render()

  def decode(s: String): Item = Item(
    ListMap.from(ujson.read(s).obj.map { case (k, v) => k -> fromJson(v) })
  )
}
