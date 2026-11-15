package org.scholarchain.er.eval

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.util.Try

import org.scholarchain.er.io.DataFiles

/** Command-line gold-set evaluation (decision 15; runs in run 2 of Phase 5).
  *
  * {{{
  * sbt "entityResolution/runMain org.scholarchain.er.eval.EvalMain \
  *   --gold <human gold-label CSV> \
  *   --queue <run dir>/review-queue/review-queue.jsonl \
  *   --key <run dir>/review-queue/review-key.jsonl \
  *   --output <output directory> \
  *   [--work-summary <run dir>/work_resolution/summary.json] [--seed 20261114] [--bootstrap-reps 1000] [--note <text>] \
  *   [--policy <policy json>] [--manifest <run dir>/_manifest.json] [--run-report <run dir>/run-report.json] \
  *   [--upstream-report <run report of the input run>]"
  * }}}
  *
  * Writes `metrics.json` and `summary.md` into the output directory, which is created if absent. An
  * existing directory is allowed (the run's `eval/` folder is re-used on a re-run): earlier copies
  * of these two files are replaced, and the sha256 of each replaced file is recorded in the new
  * `metrics.json` (`outputs.replaced`) and `summary.md` ("Outputs" section), so a replaced result
  * stays traceable (repair 23, reviewer R4). No other file is touched or removed.
  *
  * All or nothing (repair 27, test-engineer F7): both outputs are computed in full before anything
  * is written, so an input that makes the metrics or the Markdown fail leaves no partial output. A
  * gold file that fails the strict reader stops the run with every error listed.
  */
object EvalMain {
  def main(args: Array[String]): Unit = run(args, Harness.markdown)

  /** The evaluation with the Markdown renderer as a parameter (a test seam for the all-or-nothing
    * rule of repair 27); `main` always uses [[Harness.markdown]].
    */
  def run(args: Array[String], render: ujson.Obj => String): Unit = {
    if (args.length % 2 != 0)
      throw new IllegalArgumentException("arguments must be --name value pairs")
    val a = args.grouped(2).map(p => p(0).stripPrefix("--") -> p(1)).toMap
    val allowed =
      Set(
        "gold",
        "queue",
        "key",
        "output",
        "work-summary",
        "seed",
        "bootstrap-reps",
        "note",
        "policy",
        "manifest",
        "run-report",
        "upstream-report"
      )
    val unknown = a.keySet -- allowed
    if (unknown.nonEmpty)
      throw new IllegalArgumentException(s"unknown arguments: ${unknown.mkString(", ")}")
    List("gold", "queue", "key", "output").foreach(k =>
      if (!a.contains(k)) throw new IllegalArgumentException(s"missing --$k")
    )
    val gold = GoldLabels.read(Paths.get(a("gold")), Paths.get(a("queue"))) match {
      case Right(g)     => g
      case Left(errors) =>
        throw new IllegalArgumentException(
          s"gold labels rejected (${errors.size} errors):\n" + errors.mkString("\n")
        )
    }
    // repair 18: a malformed optional JSON input stops the run with its role and path named
    def json(k: String): Option[ujson.Value] = a.get(k).map { p =>
      val content = DataFiles.readUtf8(Paths.get(p))
      Try(ujson.read(content)).fold(
        e => throw new IllegalArgumentException(s"--$k $p: not valid JSON (${e.getMessage})", e),
        identity
      )
    }
    val key = KeyRow.readAll(Paths.get(a("key")))
    val work = json("work-summary")
    val seed = a
      .get("seed")
      .map(s => Try(s.toLong).getOrElse(throw new IllegalArgumentException("--seed")))
      .getOrElse(20261114L)
    val reps = a.get("bootstrap-reps").map(_.toInt).getOrElse(1000)
    val roles = List(
      "gold",
      "queue",
      "key",
      "work-summary",
      "policy",
      "manifest",
      "run-report",
      "upstream-report"
    )
    val inputs = EvalInputs(
      queue = GoldLabels.readQueue(Paths.get(a("queue"))),
      policy = json("policy"),
      manifest = json("manifest"),
      runReport = json("run-report"),
      upstreamReport = json("upstream-report"),
      provenance =
        roles.flatMap(r => a.get(r).map(p => (r, p, DataFiles.sha256OfFile(Paths.get(p)))))
    )
    val metrics = Harness.evaluate(gold, key, work, seed, reps, a.get("note"), inputs)
    val out = Paths.get(a("output"))
    // repair 23 (reviewer R4): record the sha256 of any earlier output that this run replaces
    metrics("outputs") = outputsJson(out)
    // repair 27 (test-engineer F7): render both outputs in full before writing either
    val metricsText = metrics.render(indent = 2) + "\n"
    val summaryText = render(metrics)
    Files.createDirectories(out)
    Files.write(out.resolve(MetricsFile), metricsText.getBytes(StandardCharsets.UTF_8))
    Files.write(out.resolve(SummaryFile), summaryText.getBytes(StandardCharsets.UTF_8))
    println(summaryText)
  }

  val MetricsFile = "metrics.json"
  val SummaryFile = "summary.md"

  val OverwriteRule: String =
    "EvalMain writes metrics.json and summary.md into the output directory (created if absent) and replaces earlier copies of these two files; it touches no other file. The sha256 of each replaced file is recorded here."

  /** The output directory, the overwrite rule and the sha256 of each output file it replaces. */
  def outputsJson(out: Path): ujson.Obj = {
    val replaced = List(MetricsFile, SummaryFile).flatMap { f =>
      val p = out.resolve(f)
      if (Files.isRegularFile(p))
        Some(ujson.Obj("file" -> f, "previousSha256" -> DataFiles.sha256OfFile(p)))
      else None
    }
    ujson.Obj(
      "directory" -> out.toString,
      "overwriteRule" -> OverwriteRule,
      "replaced" -> ujson.Arr.from(replaced),
      // repair 30: replaced files are stale, never sources of the numbers in this file
      "replacedStatus" -> (if (replaced.isEmpty) ujson.Null
                           else ujson.Str(EvalReport.ReplacedLabel))
    )
  }
}
