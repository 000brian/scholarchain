package org.scholarchain.hadoop.common

/** Validation of identifiers that become output path segments (dataset IDs, snapshot IDs, job
  * scopes), so that no identifier can escape the output root (for example `../../x`), which
  * `JobRunner` may delete under `scholarchain.output.replaceIncomplete`.
  */
object PathIds {

  /** Characters allowed in a dataset or snapshot identifier. */
  val IdPattern = "[A-Za-z0-9._-]+"

  /** Characters allowed in one scope segment (identifiers joined with `=` and `+`). */
  val SegmentPattern = "[A-Za-z0-9._=+-]+"

  private def isDotSegment(v: String): Boolean = v == "." || v == ".."

  /** `Left` with the problem when `value` is not a safe identifier. */
  def check(what: String, value: String): Either[String, String] =
    if (value == null || !value.matches(IdPattern))
      Left(s"$what '$value' must match $IdPattern")
    else if (isDotSegment(value)) Left(s"$what '$value' must not be '.' or '..'")
    else Right(value)

  /** Throws IllegalArgumentException when `value` is not a safe identifier. */
  def require(what: String, value: String): String =
    check(what, value).fold(e => throw new IllegalArgumentException(e), identity)

  /** Throws IllegalArgumentException unless `value` is one safe path segment: it matches
    * [[SegmentPattern]] and is not `.` or `..` (used for derived labels such as the
    * `dblp-<snapshot>+<dataset>-<snapshot>` snapshot label of cross-dataset jobs).
    */
  def requireSegment(what: String, value: String): String =
    if (value == null || !value.matches(SegmentPattern) || isDotSegment(value))
      throw new IllegalArgumentException(
        s"$what '$value' must match $SegmentPattern and not be '.' or '..'"
      )
    else value

  /** Throws IllegalArgumentException unless every `/`-separated segment of `scope` matches
    * [[SegmentPattern]] and is not `.` or `..`.
    */
  def requireScope(scope: String): String = {
    val segments = Option(scope).toList.flatMap(_.split("/", -1).toList)
    if (segments.isEmpty || segments.exists(s => !s.matches(SegmentPattern) || isDotSegment(s)))
      throw new IllegalArgumentException(
        s"scope '$scope': every segment must match $SegmentPattern and not be '.' or '..'"
      )
    scope
  }
}
