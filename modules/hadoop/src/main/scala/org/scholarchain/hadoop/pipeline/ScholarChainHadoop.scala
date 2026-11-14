package org.scholarchain.hadoop.pipeline

import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.Path
import org.apache.hadoop.util.{Tool, ToolRunner}
import org.scholarchain.hadoop.common.JobResult

/** Command-line driver (run with `hadoop jar`; configuration overrides with `-D key=value`).
  *
  * {{{
  * pipeline --output <root> --dblp <path> --dblp-snapshot <id>
  *          [--source <kind>:<datasetId>:<snapshotId>:<path>]... [--dblp-fingerprint <sha256:...>]
  * }}}
  *
  * The driver never creates clusters or submits EMR steps; cloud runs go through the approved
  * scripts owned by infra-engineer. `scholarchain.code.version` must be set with `-D`.
  */
class ScholarChainHadoop extends Configured with Tool {

  override def run(args: Array[String]): Int = {
    val opts = ScholarChainHadoop.parse(args.toList)
    val spec = PipelineSpec(
      new Path(opts("--dblp").head),
      opts("--dblp-snapshot").head,
      opts.getOrElse("--source", Nil).map { s =>
        s.split(":", 4) match {
          case Array(kind, ds, snap, path) => SourceInput(kind, ds, snap, new Path(path))
          case _                           =>
            throw new IllegalArgumentException(s"--source $s: expected kind:dataset:snapshot:path")
        }
      },
      new Path(opts("--output").head),
      opts.get("--dblp-fingerprint").map(_.head)
    )
    val result = Pipeline.run(getConf, spec)
    println(ScholarChainHadoop.report(result.all).render(indent = 2))
    0
  }
}

object ScholarChainHadoop {

  def main(args: Array[String]): Unit =
    args.headOption match {
      case Some("pipeline") =>
        sys.exit(ToolRunner.run(new Configuration(), new ScholarChainHadoop, args.tail))
      case _ =>
        System.err.println(
          "usage: pipeline --output <root> --dblp <path> --dblp-snapshot <id> " +
            "[--source kind:datasetId:snapshotId:path]..."
        )
        sys.exit(2)
    }

  private val Single = Set("--output", "--dblp", "--dblp-snapshot", "--dblp-fingerprint")

  def parse(args: List[String]): Map[String, List[String]] = {
    def go(rest: List[String], acc: Map[String, List[String]]): Map[String, List[String]] =
      rest match {
        case Nil                                  => acc
        case k :: v :: tail if k.startsWith("--") =>
          if (Single(k) && acc.contains(k)) throw new IllegalArgumentException(s"$k given twice")
          go(tail, acc.updated(k, acc.getOrElse(k, Nil) :+ v))
        case other =>
          throw new IllegalArgumentException(s"unexpected arguments: ${other.mkString(" ")}")
      }
    val m = go(args, Map.empty)
    List("--output", "--dblp", "--dblp-snapshot").foreach(k =>
      if (!m.contains(k)) throw new IllegalArgumentException(s"$k is required")
    )
    m
  }

  /** Machine-readable run report: per job run ID, output directory, counters and stats. */
  def report(results: List[JobResult]): ujson.Value =
    ujson.Arr.from(results.map { r =>
      ujson.Obj(
        "job" -> r.jobName,
        "runId" -> r.runId.value,
        "outputDir" -> r.outputDir.toString,
        "reused" -> r.reused,
        "counters" -> ujson.Obj.from(r.counters.toList.sortBy(_._1).map { case (k, v) =>
          k -> ujson.Num(v.toDouble)
        }),
        "stats" -> ujson.Obj.from(r.stats.toList.sortBy(_._1).map { case (k, s) =>
          k -> ujson.Obj("value" -> ujson.Num(s.value.toDouble), "subject" -> s.subject)
        })
      )
    })
}
