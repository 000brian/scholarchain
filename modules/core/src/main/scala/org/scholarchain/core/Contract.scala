package org.scholarchain.core

import scala.util.control.NonFatal

import upickle.default.{read, write, ReadWriter}

/** A rejected record: every reason, one message per problem. */
final case class ContractError(messages: List[String]) {
  def message: String = messages.mkString("; ")
}

/** The only sanctioned way to put contract records on or take them off the wire.
  *
  * Both directions validate: a record that fails [[Validator]] is never written and never returned
  * from `decode`.
  */
object Contract {

  /** Null fields first (a JSON `null` or absent provenance is rejected), then the record rules. */
  def validate[A](a: A)(implicit v: Validator[A]): List[String] = {
    val nulls = NullCheck.paths(a, "")
    if (nulls.nonEmpty) nulls else v.validate(a)
  }

  def decode[A](
      json: String
  )(implicit rw: ReadWriter[A], v: Validator[A]): Either[ContractError, A] =
    try {
      val a = read[A](json)
      validate(a) match {
        case Nil    => Right(a)
        case errors => Left(ContractError(errors))
      }
    } catch {
      case NonFatal(e) => Left(ContractError(List(s"decode: ${describe(e)}")))
    }

  /** Message chain of an exception and its causes (uPickle reports the JSON path at the top). */
  private def describe(e: Throwable): String =
    Iterator
      .iterate(e)(_.getCause)
      .takeWhile(_ != null)
      .take(8)
      .map(t => Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
      .mkString(": ")

  def encode[A](a: A, indent: Int = -1)(implicit
      rw: ReadWriter[A],
      v: Validator[A]
  ): Either[ContractError, String] =
    validate(a) match {
      case Nil    => Right(write(a, indent = indent))
      case errors => Left(ContractError(errors))
    }
}

/** Finds null references anywhere in a record tree (case classes, collections, options). */
object NullCheck {

  def paths(value: Any, path: String): List[String] = value match {
    case null         => List(s"${if (path.isEmpty) "(root)" else path}: missing (null)")
    case o: Option[_] => o.toList.flatMap(paths(_, path))
    case m: scala.collection.Map[_, _] =>
      m.toList.flatMap { case (k, v) => paths(k, s"$path.<key>") ++ paths(v, s"$path[$k]") }
    case it: Iterable[_] =>
      it.toList.zipWithIndex.flatMap { case (v, i) => paths(v, s"$path[$i]") }
    case p: Product =>
      p.productElementNames
        .zip(p.productIterator)
        .toList
        .flatMap { case (n, v) => paths(v, if (path.isEmpty) n else s"$path.$n") }
    case _ => Nil
  }
}
