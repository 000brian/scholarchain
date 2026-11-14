package org.scholarchain.er

import java.nio.file.{Files, Paths}

import org.scholarchain.er.Fixture.W
import org.scholarchain.er.llm.AlwaysAbstainAdjudicator
import org.scholarchain.er.run.ErRun

/** Synthetic throughput bench (no real data): builds a J09-like export with clearly synthetic
  * names, runs entity resolution, and prints wall time and heap use.
  *
  * Run: `sbt "entityResolution/Test/runMain org.scholarchain.er.ErBench <works> <output dir>"`.
  */
object ErBench {
  def main(args: Array[String]): Unit = {
    val n = args.headOption.map(_.toInt).getOrElse(5000)
    val out =
      Paths.get(args.lift(1).getOrElse("modules/entity-resolution/target/er-bench")).toAbsolutePath
    val rnd = new scala.util.Random(1)
    val givens = Vector(
      "Wei",
      "Wen",
      "Jun",
      "Li",
      "Ana",
      "Ann",
      "J.",
      "John",
      "Jane",
      "Yu",
      "Xin",
      "Hao",
      "Min",
      "Lei"
    )
    val family = Vector(
      "Synwang",
      "Synzhang",
      "Synli",
      "Synchen",
      "Synliu",
      "Synyang",
      "Synhuang",
      "Synzhao",
      "Synwu",
      "Synzhou"
    )
    def name(): String = {
      val base =
        s"${givens(rnd.nextInt(givens.size))} ${family(rnd.nextInt(family.size))}${rnd.nextInt(40)}"
      if (rnd.nextInt(10) == 0) f"$base ${rnd.nextInt(3) + 1}%04d" else base
    }
    val venues = Vector("conf/sigir", "conf/kdd", "conf/cvpr", "conf/acl", "conf/icde")
    val works = (0 until n).map { i =>
      val team = (1 to (2 + rnd.nextInt(5))).map(_ => name()).distinct.toList
      W(
        s"conf/bench/B$i",
        team,
        year = 2015 + rnd.nextInt(11),
        venue = venues(rnd.nextInt(venues.size)),
        title = s"Synthetic topic ${rnd.nextInt(200)} study ${rnd.nextInt(50)}"
      )
    }.toList
    val t0 = System.nanoTime()
    val input = Fixture.writeExport(
      works,
      maxBlock = 200,
      dir = Some(Files.createTempDirectory("er-bench-in"))
    )
    val cands = Fixture.lines(input.resolve("match_candidate/part-r-00000")).size + Fixture
      .lines(input.resolve("match_candidate/part-r-00001"))
      .size
    val t1 = System.nanoTime()
    val r = ErRun.run(
      RunFixture
        .config(input, out, max = 1000)
        .copy(queue = org.scholarchain.er.review.QueueSpec(300, 30, 7L)),
      new AlwaysAbstainAdjudicator("fake-model/1")
    )
    val t2 = System.nanoTime()
    val rt = Runtime.getRuntime
    println(
      f"[bench] works=$n candidates=$cands generate=${(t1 - t0) / 1e9}%.1fs resolve=${(t2 - t1) / 1e9}%.1fs " +
        f"perCandidateMicros=${(t2 - t1) / 1e3 / math.max(1, cands)}%.1f heapUsedMB=${(rt.totalMemory - rt.freeMemory) / 1048576} maxHeapMB=${rt.maxMemory / 1048576}"
    )
    println(s"[bench] runDir=${r.runDir}")
    println(r.report("pairDecisions").render(indent = 2))
  }
}
