package org.scholarchain.er.run

import java.nio.file.{Path, Paths}

import scala.util.Try

import org.scholarchain.er.io.ErError
import org.scholarchain.er.llm.{AlwaysAbstainAdjudicator, Adjudicator}
import org.scholarchain.er.policy.{Policy, Thresholds}
import org.scholarchain.er.review.QueueSpec

/** Command-line runner of one entity-resolution run (decision 17). No network, no AWS.
  *
  * {{{
  * sbt --mem 8192 "entityResolution/runMain org.scholarchain.er.run.ErMain \
  *   --input <J09 export run=... directory> \
  *   --output-root <new directory> \
  *   --decision-time 2026-11-14T00:00:00Z \
  *   --seed 20261114 \
  *   --code-version phase05-<git short HEAD>-<attempt>-dirty \
  *   --llm-model-id <model ID recorded on llm decisions> \
  *   --llm-max-per-run 1000 \
  *   --adjudicator always-abstain"
  * }}}
  *
  * Optional: `--queue-size 300`, `--queue-prior-pairs 30`, `--llm-cache <LlmAdjudication JSON
  * lines>`, `--j04-run-dir <J04 run directory with conflicts/v1>`, and threshold overrides
  * `--accept-threshold`, `--reject-threshold`, `--llm-band-lower`, `--llm-min-confidence`, which
  * require `--policy-version <new version>` different from the policy file's version. The only
  * adjudicator available from the command line is `always-abstain` (decision 12); the live adapter
  * comes later. Failures are thrown (never `System.exit`), so sbt reports them and exits non-zero.
  */
object ErMain {

  val Required: List[String] = List(
    "input",
    "output-root",
    "decision-time",
    "seed",
    "code-version",
    "llm-model-id",
    "llm-max-per-run",
    "adjudicator"
  )
  val Optional: List[String] = List(
    "queue-size",
    "queue-prior-pairs",
    "llm-cache",
    "j04-run-dir",
    "policy-version",
    "accept-threshold",
    "reject-threshold",
    "llm-band-lower",
    "llm-min-confidence"
  )

  def parseArgs(args: Seq[String]): Map[String, String] = {
    if (args.size % 2 != 0) throw new ErError("arguments must be --name value pairs")
    val pairs = args
      .grouped(2)
      .map { case Seq(k, v) =>
        if (!k.startsWith("--")) throw new ErError(s"expected --name, got '$k'")
        k.stripPrefix("--") -> v
      }
      .toList
    val names = pairs.map(_._1)
    val dup = names.diff(names.distinct)
    if (dup.nonEmpty) throw new ErError(s"repeated arguments: ${dup.distinct.mkString(", ")}")
    val unknown = names.filterNot(n => Required.contains(n) || Optional.contains(n))
    if (unknown.nonEmpty) throw new ErError(s"unknown arguments: ${unknown.mkString(", ")}")
    val missing = Required.filterNot(names.contains)
    if (missing.nonEmpty)
      throw new ErError(s"missing arguments: ${missing.map("--" + _).mkString(", ")}")
    pairs.toMap
  }

  def config(a: Map[String, String]): (ErConfig, Adjudicator) = {
    def long(k: String) = Try(a(k).toLong).getOrElse(throw new ErError(s"--$k must be an integer"))
    def int(k: String, d: Int) =
      a.get(k).fold(d)(v => Try(v.toInt).getOrElse(throw new ErError(s"--$k must be an integer")))
    def dbl(k: String) =
      a.get(k).map(v => Try(v.toDouble).getOrElse(throw new ErError(s"--$k must be a number")))
    val base = Policy.current
    val overrides =
      List("accept-threshold", "reject-threshold", "llm-band-lower", "llm-min-confidence").filter(
        a.contains
      )
    val policy =
      if (overrides.isEmpty) {
        if (a.contains("policy-version"))
          throw new ErError("--policy-version is only allowed together with a threshold override")
        base
      } else {
        val t = base.thresholds
        base
          .withThresholds(
            a.getOrElse("policy-version", ""),
            Thresholds(
              dbl("accept-threshold").getOrElse(t.accept),
              dbl("reject-threshold").getOrElse(t.reject),
              dbl("llm-band-lower").getOrElse(t.llmBandLower),
              dbl("llm-min-confidence").getOrElse(t.llmMinConfidence),
              t.nearThresholdLogit
            )
          )
          .fold(e => throw new ErError(e), identity)
      }
    val adjudicator: Adjudicator = a("adjudicator") match {
      case "always-abstain" => new AlwaysAbstainAdjudicator(a("llm-model-id"))
      case other            =>
        throw new ErError(s"--adjudicator '$other' is not available (only always-abstain)")
    }
    val max = int("llm-max-per-run", 0)
    if (max < 0) throw new ErError("--llm-max-per-run must be >= 0")
    val queue = Try(QueueSpec(int("queue-size", 300), int("queue-prior-pairs", 30), long("seed")))
      .fold(e => throw new ErError(e.getMessage), identity)
    val cfg = ErConfig(
      Paths.get(a("input")),
      Paths.get(a("output-root")),
      a("decision-time"),
      long("seed"),
      a("code-version"),
      a("llm-model-id"),
      max,
      a("adjudicator"),
      queue,
      a.get("llm-cache").map(Paths.get(_)),
      a.get("j04-run-dir").map(Paths.get(_)),
      policy
    )
    (cfg, adjudicator)
  }

  def main(args: Array[String]): Unit = {
    val (cfg, adjudicator) = config(parseArgs(args.toSeq))
    val out = ErRun.run(cfg, adjudicator)
    println(s"[er] runId=${out.runId}")
    println(s"[er] runDir=${out.runDir}")
    println(s"[er] reused=${out.reused}")
    println(out.report.render(indent = 2))
  }

  def path(s: String): Path = Paths.get(s)
}
