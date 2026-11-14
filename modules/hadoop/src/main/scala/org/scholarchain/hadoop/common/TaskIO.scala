package org.scholarchain.hadoop.common

import org.apache.hadoop.io.{NullWritable, Text}
import org.scholarchain.core.CodePointOrder
import org.apache.hadoop.mapreduce.TaskInputOutputContext
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import upickle.default.{macroRW, write, ReadWriter}

/** Per-task output helper: named outputs, counters and disposition records.
  *
  * One instance per map or reduce task (created in `setup`, closed in `cleanup`); it holds no state
  * shared with other tasks.
  *
  * @param reduceStage
  *   rejects at the reduce stage count REJECTED_REDUCE_RECORDS instead of REJECTED_RECORDS, so the
  *   map-stage accounting identity stays exact
  */
final class TaskIO(ctx: TaskInputOutputContext[?, ?, ?, ?], reduceStage: Boolean) {
  private val mos =
    new MultipleOutputs[Any, Any](ctx.asInstanceOf[TaskInputOutputContext[Any, Any, Any, Any]])
  val run: RunContext = RunContext.from(ctx.getConfiguration)
  private val settings = new Settings(ctx.getConfiguration)
  val previewChars: Int = settings.positiveInt(Settings.Keys.RejectPreviewChars)
  private val text = new Text()

  def count(name: String, by: Long = 1L): Unit =
    ctx.getCounter(Counters.Group, name).increment(by)

  def countIn(group: String, name: String, by: Long = 1L): Unit =
    ctx.getCounter(group, name).increment(by)

  /** Writes one line to `<dir>/part-[mr]-NNNNN` of the named output `named`. */
  def emit(named: String, dir: String, line: String): Unit = {
    text.set(line)
    mos.write(named, NullWritable.get, text, s"$dir/part")
    ctx.getCounter(Counters.OutputGroup, dir).increment(1)
  }

  def reject(
      reason: String,
      detail: String,
      sourceRecordId: Option[String],
      location: Option[String],
      input: Option[String]
  ): Unit = {
    count(if (reduceStage) Counters.RejectedReduce else Counters.Rejected)
    countIn(Counters.RejectReasonGroup, reason)
    if (reason == Reasons.MissingKey) count(Counters.MissingKey)
    if (reason == Reasons.SchemaFailure) count(Counters.SchemaFailure)
    val d = Disposition.make(
      run,
      Disposition.Rejected,
      reason,
      detail,
      sourceRecordId,
      location,
      input,
      previewChars
    )
    emit(TaskIO.Rejects, TaskIO.RejectsDir, Disposition.toJson(d))
  }

  def exclude(reason: String, sourceRecordId: Option[String], location: Option[String]): Unit = {
    count(Counters.Excluded)
    countIn(Counters.ExcludedReasonGroup, reason)
    val d = Disposition.make(
      run,
      Disposition.Excluded,
      reason,
      "outside the configured subset",
      sourceRecordId,
      location,
      None,
      previewChars
    )
    emit(TaskIO.Excluded, TaskIO.ExcludedDir, Disposition.toJson(d))
  }

  def issue(
      reason: String,
      detail: String,
      sourceRecordId: Option[String],
      location: Option[String]
  ): Unit = {
    count(Counters.FieldIssues)
    countIn(Counters.IssueGroup, reason)
    val d = Disposition.make(
      run,
      Disposition.FieldIssue,
      reason,
      detail,
      sourceRecordId,
      location,
      None,
      previewChars
    )
    emit(TaskIO.Issues, TaskIO.IssuesDir, Disposition.toJson(d))
  }

  def conflict(reason: String, detail: String, sourceRecordId: Option[String]): Unit = {
    count(Counters.Conflict)
    countIn(Counters.RejectReasonGroup, reason)
    val d = Disposition.make(
      run,
      Disposition.ConflictKind,
      reason,
      detail,
      sourceRecordId,
      None,
      None,
      previewChars
    )
    emit(TaskIO.Conflicts, TaskIO.ConflictsDir, Disposition.toJson(d))
  }

  def stat(name: String, value: Long, subject: String): Unit =
    emit(TaskIO.Stats, TaskIO.StatsDir, write(Stat(name, value, subject)))

  def close(): Unit = mos.close()
}

object TaskIO {
  // Named outputs (Hadoop requires letters and digits only) and their directories.
  val Rejects = "rejects"
  val RejectsDir = "rejects/v1"
  val Excluded = "excluded"
  val ExcludedDir = "excluded/v1"
  val Issues = "issues"
  val IssuesDir = "issues/v1"
  val Conflicts = "conflicts"
  val ConflictsDir = "conflicts/v1"
  val Stats = "stats"
  val StatsDir = "stats"

  val accountingOutputs: List[String] = List(Rejects, Excluded, Issues, Conflicts, Stats)
}

/** A per-task maximum (for example the largest team) written to `stats/`; the driver keeps the
  * largest value per name (ties: smallest subject by code point).
  */
final case class Stat(name: String, value: Long, subject: String)
object Stat { implicit val rw: ReadWriter[Stat] = macroRW }

/** Tracks a running maximum inside one task (ties: smallest subject by code point). */
final class MaxTracker(name: String) {
  private var best: Option[Stat] = None
  def offer(value: Long, subject: String): Unit = {
    val better = best match {
      case None    => true
      case Some(b) =>
        value > b.value || (value == b.value && CodePointOrder.lessThan(subject, b.subject))
    }
    if (better) best = Some(Stat(name, value, subject))
  }
  def flush(io: TaskIO): Unit = best.foreach(s => io.stat(s.name, s.value, s.subject))
}
