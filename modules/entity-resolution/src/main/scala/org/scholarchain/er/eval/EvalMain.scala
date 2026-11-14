package org.scholarchain.er.eval

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.util.Try

import org.scholarchain.er.io.DataFiles

/** Command-line gold-set evaluation (decision 15; runs in run 2 of Phase 5).
  *
  * {{{
  * sbt "entityResolution/runMain org.scholarchain.er.eval.EvalMain \
  *   --gold <human gold-label CSV> \
  *   --queue <run dir>/review-queue/review-queue.jsonl \
  *   --key <run dir>/review-queue/review-key.jsonl \
  *   --output <new directory> \
  *   [--work-summary <run dir>/work_resolution/summary.json] [--seed 20261114] [--bootstrap-reps 1000] [--note <text>]"
  * }}}
  *
  * Writes `metrics.json` and `summary.md`. A gold file that fails the strict reader stops the run
  * with every error listed.
  */
object EvalMain {
  def main(args: Array[String]): Unit = {
    if (args.length % 2 != 0)
      throw new IllegalArgumentException("arguments must be --name value pairs")
    val a = args.grouped(2).map(p => p(0).stripPrefix("--") -> p(1)).toMap
    val allowed =
      Set("gold", "queue", "key", "output", "work-summary", "seed", "bootstrap-reps", "note")
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
    val key = KeyRow.readAll(Paths.get(a("key")))
    val work = a.get("work-summary").map(p => ujson.read(DataFiles.readUtf8(Paths.get(p))))
    val seed = a
      .get("seed")
      .map(s => Try(s.toLong).getOrElse(throw new IllegalArgumentException("--seed")))
      .getOrElse(20261114L)
    val reps = a.get("bootstrap-reps").map(_.toInt).getOrElse(1000)
    val metrics = Harness.evaluate(gold, key, work, seed, reps, a.get("note"))
    val out = Paths.get(a("output"))
    Files.createDirectories(out)
    Files.write(
      out.resolve("metrics.json"),
      (metrics.render(indent = 2) + "\n").getBytes(StandardCharsets.UTF_8)
    )
    Files.write(
      out.resolve("summary.md"),
      Harness.markdown(metrics).getBytes(StandardCharsets.UTF_8)
    )
    println(Harness.markdown(metrics))
  }
}
