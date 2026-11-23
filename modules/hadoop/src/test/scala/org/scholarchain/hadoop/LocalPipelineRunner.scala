package org.scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath, Paths}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.scholarchain.hadoop.blocking.{FallbackBlockingJob, FallbackCoverageJob}
import org.scholarchain.hadoop.common.{Counters, JobObserver, JobResult, JobRunner, Settings}
import org.scholarchain.hadoop.pipeline.{Pipeline, PipelineResult, PipelineSpec, ScholarChainHadoop}

/** Bounded local run of the whole Hadoop pipeline (J01 to J09, LocalJobRunner, local file system)
  * on a dblp file given on the command line, with no enrichment sources. Test scope only; it never
  * touches AWS or the network.
  *
  * {{{
  * sbt -batch "hadoop/Test/runMain org.scholarchain.hadoop.LocalPipelineRunner
  *   --output <new empty dir> --dblp <dblp xml or xml.gz> --dblp-snapshot <id>
  *   [--work <dir>] [--min-free-gb 8] [--max-job-minutes 120] [--max-total-minutes 0]
  *   -D scholarchain.code.version=<version> [-D key=value]..."
  * }}}
  *
  *   - `--output` must not exist or must be empty (every attempt uses a new root).
  *   - `-D key=value` (or `-Dkey=value`) sets any Hadoop or ScholarChain key;
  *     `scholarchain.code.version` is required.
  *   - Before each job the free space of the output file system is checked; below `--min-free-gb`
  *     the run stops before that job (exit 3).
  *   - A watchdog stops the JVM (exit 3) when one job runs longer than `--max-job-minutes` or the
  *     whole run longer than `--max-total-minutes` (0 = no total limit).
  *   - `settings` in the report holds the effective values the jobs read (every `scholarchain.*`
  *     key from the defaults resource and `-D`, outside `scholarchain.run.*` and
  *     `scholarchain.path.*`, plus every `-D` key); `overrides` holds the `-D` arguments as given.
  *   - `<output>/run-report.json` is rewritten after every job: status, settings, per job wall
  *     time, run ID, counters and stats, and at the end a summary (works, mentions, pairs, blocks
  *     and oversize blocks per family, fallback coverage and worst keys, candidates per family,
  *     exported records per entity). In `summary.fallback`, `largestFallbackBlock` is the largest
  *     fallback group before the cap (it may exceed `scholarchain.blocking.fallback.maxBlockSize`
  *     because it includes groups that were not emitted) and `largestEmittedFallbackBlock` is the
  *     largest fallback block written to `blocks/` (never above the cap; null when none).
  */
object LocalPipelineRunner {

  final case class Options(
      output: JPath,
      dblp: String,
      snapshot: String,
      work: Option[JPath],
      minFreeGb: Long,
      maxJobMinutes: Long,
      maxTotalMinutes: Long,
      settings: List[(String, String)]
  )

  final class RunStopped(msg: String) extends RuntimeException(msg)

  private def setting(kv: String): (String, String) = kv.split("=", 2) match {
    case Array(k, v) if k.trim.nonEmpty => k.trim -> v
    case _ => throw new IllegalArgumentException(s"-D $kv: expected key=value")
  }

  def parse(args: List[String]): Options = {
    def go(rest: List[String], o: Map[String, String], ds: List[(String, String)]): Options =
      rest match {
        case "-D" :: kv :: tail                   => go(tail, o, ds :+ setting(kv))
        case d :: tail if d.startsWith("-D")      => go(tail, o, ds :+ setting(d.drop(2)))
        case k :: v :: tail if k.startsWith("--") =>
          if (o.contains(k)) throw new IllegalArgumentException(s"$k given twice")
          go(tail, o.updated(k, v), ds)
        case Nil =>
          val known = Set(
            "--output",
            "--dblp",
            "--dblp-snapshot",
            "--work",
            "--min-free-gb",
            "--max-job-minutes",
            "--max-total-minutes"
          )
          o.keys.filterNot(known).foreach(k => throw new IllegalArgumentException(s"unknown $k"))
          List("--output", "--dblp", "--dblp-snapshot").foreach(k =>
            if (!o.contains(k)) throw new IllegalArgumentException(s"$k is required")
          )
          if (!ds.exists(_._1 == Settings.Keys.CodeVersion))
            throw new IllegalArgumentException(s"-D ${Settings.Keys.CodeVersion}=... is required")
          val dupes = ds.groupBy(_._1).collect { case (k, vs) if vs.size > 1 => k }
          if (dupes.nonEmpty)
            throw new IllegalArgumentException(s"-D given twice: ${dupes.mkString(",")}")
          def num(k: String, d: Long): Long =
            o.get(k)
              .map(v =>
                v.toLongOption
                  .filter(_ >= 0)
                  .getOrElse(
                    throw new IllegalArgumentException(s"$k $v: expected a non-negative integer")
                  )
              )
              .getOrElse(d)
          Options(
            Paths.get(o("--output")).toAbsolutePath.normalize,
            o("--dblp"),
            o("--dblp-snapshot"),
            o.get("--work").map(Paths.get(_).toAbsolutePath.normalize),
            num("--min-free-gb", 8L),
            num("--max-job-minutes", 120L),
            num("--max-total-minutes", 0L),
            ds
          )
        case other =>
          throw new IllegalArgumentException(s"unexpected arguments: ${other.mkString(" ")}")
      }
    go(args, Map.empty, Nil)
  }

  /** The first limit exceeded at `now`, if any (times in milliseconds; limits in minutes, 0 = none
    * for the total).
    */
  def overLimit(
      now: Long,
      runStart: Long,
      job: Option[(String, Long)],
      maxJobMinutes: Long,
      maxTotalMinutes: Long
  ): Option[String] = {
    val jobOver = job.collect {
      case (name, start) if maxJobMinutes > 0 && now - start > maxJobMinutes * 60000L =>
        s"job $name ran longer than $maxJobMinutes minutes"
    }
    jobOver.orElse(
      Option.when(maxTotalMinutes > 0 && now - runStart > maxTotalMinutes * 60000L)(
        s"run took longer than $maxTotalMinutes minutes"
      )
    )
  }

  private def freeBytes(p: JPath): Long = {
    var q = p
    while (q != null && !Files.exists(q)) q = q.getParent
    Files.getFileStore(q).getUsableSpace
  }

  private def group(r: JobResult, g: String): ujson.Obj =
    ujson.Obj.from(
      r.counters.toList
        .collect { case (k, v) if k.startsWith(g + ":") => k.stripPrefix(g + ":") -> v }
        .sortBy(_._1)
        .map { case (k, v) => k -> ujson.Num(v.toDouble) }
    )

  private def stat(r: JobResult, s: String): ujson.Value =
    r.stats
      .get(s)
      .map(x => ujson.Obj("value" -> x.value.toDouble, "subject" -> x.subject))
      .getOrElse(ujson.Null)

  /** Summary of a finished pipeline (decision 17 report fields that Hadoop owns). */
  def summary(conf: Configuration, p: PipelineResult, worstKeys: Int = 20): ujson.Obj = {
    val worst = JobRunner
      .readPartLines(conf, p.fallbackCoverage.dir(FallbackCoverageJob.Summary))
      .map(ujson.read(_))
      .sortWith { (a, b) =>
        val (ua, ub) = (a("uncoveredMembers").num, b("uncoveredMembers").num)
        ua > ub || (ua == ub && (a("family").str + "\t" + a("blockKey").str) <
          (b("family").str + "\t" + b("blockKey").str))
      }
      .take(worstKeys)
    val exported = p.graphExport.counters.toList
      .collect {
        case (k, v) if k.startsWith(Counters.OutputGroup + ":") =>
          k.stripPrefix(Counters.OutputGroup + ":").split('/').head -> v
      }
      .filterNot { case (e, _) => Set("rejects", "excluded", "issues", "conflicts", "stats")(e) }
      .groupMapReduce(_._1)(_._2)(_ + _)
      .toList
      .sortBy(_._1)
    ujson.Obj(
      "works" -> p.dblp.extract.counter(Counters.OutputGroup, "works").toDouble,
      "mentions" -> p.dblp.extract.counter(Counters.OutputGroup, "mentions").toDouble,
      "teams" -> p.dblp.teams.counter(Counters.OutputGroup, "teams").toDouble,
      "pairInstances" -> p.dblp.expand.main(Counters.PairInstances).toDouble,
      "consolidatedPairs" -> p.dblp.consolidate.main(Counters.EmittedPair).toDouble,
      "maxTeamSize" -> stat(p.dblp.teams, "maxTeamSize"),
      "blocking" -> ujson.Obj(
        "blocksPerFamily" -> group(p.blocking, Counters.BlocksPerFamilyGroup),
        "oversizeBlocksPerFamily" -> group(p.blocking, Counters.OversizePerFamilyGroup),
        "noKeyPerFamily" -> group(p.blocking, Counters.NoBlockKeyGroup),
        "singletonBlocks" -> p.blocking.main(Counters.SingletonBlocks).toDouble,
        "oversizeMembers" -> p.blocking.main(Counters.OversizeMembers).toDouble,
        "pairLimitOversizeBlocks" -> p.blocking.main(Counters.PairLimitOversizeBlocks).toDouble,
        "largestBlock" -> stat(p.blocking, "largestBlock")
      ),
      "fallback" -> ujson.Obj(
        "blocksPerFamily" -> group(p.fallbackBlocking, Counters.BlocksPerFamilyGroup),
        "oversizeBlocksPerFamily" -> group(p.fallbackBlocking, Counters.OversizePerFamilyGroup),
        "noSecondaryKey" -> group(p.fallbackBlocking, Counters.NoSecondaryKeyGroup),
        "notConfigured" -> group(p.fallbackBlocking, Counters.FallbackNotConfiguredGroup),
        "singletonBlocks" -> p.fallbackBlocking.main(Counters.SingletonBlocks).toDouble,
        "pairLimitOversizeBlocks" ->
          p.fallbackBlocking.main(Counters.PairLimitOversizeBlocks).toDouble,
        // largest fallback group BEFORE the cap (includes groups over
        // scholarchain.blocking.fallback.maxBlockSize that were not emitted; may exceed the cap)
        "largestFallbackBlock" -> stat(p.fallbackBlocking, "largestFallbackBlock"),
        // largest fallback block actually written to blocks/ (never exceeds the cap)
        "largestEmittedFallbackBlock" ->
          stat(p.fallbackBlocking, FallbackBlockingJob.LargestEmittedFallbackBlockStat),
        "oversizeKeys" -> p.fallbackCoverage.main(Counters.OversizeKeys).toDouble,
        "coveredMembers" -> p.fallbackCoverage.main(Counters.CoveredMembers).toDouble,
        "uncoveredMembers" -> p.fallbackCoverage.main(Counters.UncoveredMembers).toDouble,
        "uncoveredPerFamily" -> group(p.fallbackCoverage, Counters.UncoveredPerFamilyGroup),
        "worstKeys" -> ujson.Arr.from(worst)
      ),
      "candidates" -> ujson.Obj(
        "emitted" -> p.candidates.main(Counters.CandidatesEmitted).toDouble,
        "perFamily" -> ujson.Obj.from(
          p.candidates.counters.toList
            .collect {
              case (k, v) if k.startsWith(Counters.OutputGroup + ":candidates/family=") =>
                k.stripPrefix(Counters.OutputGroup + ":candidates/family=") -> ujson.Num(v.toDouble)
            }
            .sortBy(_._1)
        ),
        "sameNumberedNamePairsSkipped" ->
          p.candidates.main(Counters.SameNumberedNameSkipped).toDouble,
        "oversizeBlocks" -> p.candidates.main(Counters.OversizeBlock).toDouble,
        "oversizeBlockPairsSkipped" ->
          p.candidates.main(Counters.OversizeBlockPairsSkipped).toDouble,
        // members of blocks J06 did not expand (0 when J05/J05b ran with the same settings)
        "unexpandedMembers" -> p.candidates.main(Counters.UnexpandedMembers).toDouble,
        "largestCandidateGroup" -> stat(p.candidates, "largestCandidateGroup"),
        "featureOmitted" -> group(p.candidates, Counters.FeatureOmittedGroup)
      ),
      "exportedPerEntity" -> ujson.Obj.from(exported.map { case (e, v) =>
        e -> ujson.Num(v.toDouble)
      }),
      "exportDir" -> p.graphExport.outputDir.toString
    )
  }

  /** The settings the jobs of this run read (Phase 14 G-19): `conf` with ScholarChain's defaults
    * resource added exactly as [[JobRunner.run]] adds it, restricted to the `scholarchain.*` keys
    * outside the per-run prefixes (`scholarchain.run.*`, `scholarchain.path.*`, set by each
    * driver), plus every `-D` key (also a Hadoop key), sorted by key. A key whose value is absent
    * is recorded as "".
    */
  def effectiveSettings(
      conf: Configuration,
      overrides: List[(String, String)]
  ): List[(String, String)] = {
    val eff = Settings.withDefaults(conf)
    val keys = eff
      .iterator()
      .asScala
      .map(_.getKey)
      .filter(k =>
        k.startsWith(Settings.Prefix) && !k.startsWith(Settings.RunPrefix) &&
          !k.startsWith(Settings.PathPrefix)
      )
      .toSet ++ overrides.map(_._1)
    keys.toList.sorted.map(k => k -> Option(eff.get(k)).getOrElse(""))
  }

  private def settingsObj(kvs: List[(String, String)]): ujson.Obj =
    ujson.Obj.from(kvs.sortBy(_._1).map { case (k, v) => k -> ujson.Str(v) })

  /** Runs the pipeline; returns the final report (also written to `<output>/run-report.json`). */
  def run(o: Options): ujson.Obj = {
    if (Files.exists(o.output)) {
      val s = Files.list(o.output)
      val nonEmpty =
        try s.findAny().isPresent
        finally s.close()
      if (nonEmpty)
        throw new IllegalArgumentException(s"--output ${o.output} exists and is not empty")
    }
    val work = o.work.getOrElse(o.output.resolve("_hadoop-work"))
    val conf = TestSupport.localConf(work, o.settings*)
    val minFree = o.minFreeGb * 1024L * 1024L * 1024L
    val reportFile = o.output.resolve("run-report.json")
    val runStart = System.currentTimeMillis()
    val jobs = ujson.Arr()
    val report = ujson.Obj(
      "status" -> "running",
      "output" -> o.output.toString,
      "dblp" -> o.dblp,
      "dblpSnapshot" -> o.snapshot,
      // effective values (defaults plus -D), so a key left at its default is recorded too (G-19)
      "settings" -> settingsObj(effectiveSettings(conf, o.settings)),
      // the -D arguments exactly as given
      "overrides" -> settingsObj(o.settings),
      "minFreeGb" -> o.minFreeGb.toDouble,
      "maxJobMinutes" -> o.maxJobMinutes.toDouble,
      "maxTotalMinutes" -> o.maxTotalMinutes.toDouble,
      "freeBytesAtStart" -> freeBytes(o.output).toDouble,
      "jobs" -> jobs
    )
    def save(): Unit = report.synchronized {
      Files.createDirectories(o.output)
      Files.write(reportFile, report.render(indent = 2).getBytes(StandardCharsets.UTF_8))
    }
    @volatile var current: Option[(String, Long)] = None
    def stop(status: String, reason: String): Unit = report.synchronized {
      report("status") = status
      report("reason") = reason
      report("wallSeconds") = (System.currentTimeMillis() - runStart) / 1000.0
      save()
    }
    val observer = new JobObserver {
      override def starting(jobName: String): Unit = {
        val free = freeBytes(o.output)
        if (free < minFree) {
          val msg = s"free space $free bytes < ${o.minFreeGb} GiB before $jobName"
          stop("stopped", msg)
          throw new RunStopped(msg)
        }
        current = Some(jobName -> System.currentTimeMillis())
        println(s"[runner] starting $jobName (free ${free / (1024L * 1024L)} MiB)")
      }
      override def finished(r: JobResult, wallMillis: Long): Unit = {
        current = None
        report.synchronized {
          jobs.arr += ujson.Obj(
            "job" -> r.jobName,
            "runId" -> r.runId.value,
            "outputDir" -> r.outputDir.toString,
            "reused" -> r.reused,
            "wallSeconds" -> wallMillis / 1000.0,
            "freeBytesAfter" -> freeBytes(o.output).toDouble,
            "counters" -> ujson.Obj.from(r.counters.toList.sortBy(_._1).map { case (k, v) =>
              k -> ujson.Num(v.toDouble)
            }),
            "stats" -> ujson.Obj.from(r.stats.toList.sortBy(_._1).map { case (k, s) =>
              k -> ujson.Obj("value" -> ujson.Num(s.value.toDouble), "subject" -> s.subject)
            })
          )
          save()
        }
        println(s"[runner] finished ${r.jobName} in ${wallMillis / 1000} s (reused=${r.reused})")
      }
    }
    val watchdog = new Thread(() => {
      var done = false
      while (!done) {
        try Thread.sleep(5000L)
        catch { case _: InterruptedException => done = true }
        if (!done)
          overLimit(
            System.currentTimeMillis(),
            runStart,
            current,
            o.maxJobMinutes,
            o.maxTotalMinutes
          ).foreach { why =>
            stop("stopped", why)
            System.err.println(s"[runner] STOPPED: $why")
            Runtime.getRuntime.halt(3)
          }
      }
    })
    watchdog.setDaemon(true)
    save()
    watchdog.start()
    try {
      val spec = PipelineSpec(
        new Path(Paths.get(o.dblp).toAbsolutePath.toUri),
        o.snapshot,
        Nil,
        new Path(o.output.toUri)
      )
      val result = Pipeline.run(conf, spec, observer)
      report.synchronized {
        report("summary") = summary(conf, result)
        report("jobReport") = ScholarChainHadoop.report(result.all)
      }
      stop("complete", "")
      report
    } catch {
      case e: RunStopped => throw e
      case e: Throwable  =>
        stop("failed", Option(e.getMessage).getOrElse(e.getClass.getName))
        throw e
    } finally watchdog.interrupt()
  }

  def main(args: Array[String]): Unit = {
    val code =
      try {
        val report = run(parse(args.toList))
        println(report("summary").render(indent = 2))
        0
      } catch {
        case e: RunStopped =>
          System.err.println(s"[runner] STOPPED: ${e.getMessage}")
          3
        case e: IllegalArgumentException =>
          System.err.println(s"[runner] ${e.getMessage}")
          2
      }
    sys.exit(code)
  }
}
