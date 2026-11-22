package org.scholarchain.storage

import scala.concurrent.Future

/** Phase 16 L-10: failure messages of `RequiresAws` and `RequiresEmulator` tests reach sbt's test
  * report XML, so they are masked before MUnit records them. Masked: ARNs, `s3://` bucket names,
  * URL hosts (queue URLs included), AWS host names, IPv4 addresses, 12-digit account IDs, and the
  * literal resource names a suite passes (its configured buckets). The replacement exception keeps
  * the stack trace (class and method names) but drops the cause chain, whose messages are not
  * masked.
  */
object FailureMasking {

  private val Rules: List[(scala.util.matching.Regex, String)] = List(
    """arn:aws[A-Za-z-]*:[^\s"',;)\]}]*""".r -> "<arn>",
    """s3://[^/\s"']+""".r -> "s3://<bucket>",
    """(https?|wss?)://[^/\s"':]+(:\d+)?""".r -> "$1://<host>",
    """\b[A-Za-z0-9.-]+\.amazonaws\.com(\.cn)?\b""".r -> "<host>",
    """\b[A-Za-z0-9.-]+\.(api\.aws|on\.aws)\b""".r -> "<host>",
    """(?<![\d.])\d{1,3}(\.\d{1,3}){3}(?![\d.])""".r -> "<ip>",
    """(?<!\d)\d{12}(?!\d)""".r -> "<account>"
  )

  /** `text` with every identifier class above and every literal in `names` replaced. */
  def mask(text: String, names: Iterable[String] = Nil): String =
    if (text == null) null
    else {
      val literals = names.filter(n => n != null && n.length >= 3).toList.distinct.sortBy(-_.length)
      val afterNames = literals.foldLeft(text)((t, n) => t.replace(n, "<name>"))
      Rules.foldLeft(afterNames) { case (t, (re, to)) => re.replaceAllIn(t, to) }
    }

  /** A failure carrying only the masked message of `e` (and of its causes, joined), with `e`'s
    * stack trace.
    */
  def masked(e: Throwable, names: Iterable[String] = Nil): Throwable = {
    val chain = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(8).toList
    val text = chain
      .map(c => s"${c.getClass.getSimpleName}: ${Option(c.getMessage).getOrElse("")}")
      .mkString(" <- ")
    val out = new MaskedTestFailure(mask(text, names))
    out.setStackTrace(e.getStackTrace)
    out
  }
}

/** The masked failure a [[MaskedFailures]] suite reports (a RuntimeException, so a Future carries
  * it unboxed).
  */
final class MaskedTestFailure(message: String) extends RuntimeException(message)

/** Mix into a `RequiresAws` or `RequiresEmulator` suite: every failing test body is reported with
  * [[FailureMasking.masked]]; `maskedNames` lists the suite's literal resource names.
  */
trait MaskedFailures extends munit.FunSuite {

  /** Literal names to mask besides the generic classes (configured buckets and the like). */
  def maskedNames: Iterable[String] = Nil

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ new TestTransform(
      "mask identifiers in failures (L-10)",
      t =>
        t.withBodyMap(
          _.recoverWith { case e: Throwable =>
            Future.failed(FailureMasking.masked(e, safeNames))
          }(munitExecutionContext)
        )
    )

  /** [[maskedNames]], or nothing when computing them fails (a configuration that does not load). */
  protected def safeNames: Iterable[String] =
    try maskedNames
    catch { case scala.util.control.NonFatal(_) => Nil }
}
