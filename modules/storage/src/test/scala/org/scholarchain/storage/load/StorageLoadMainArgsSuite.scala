package org.scholarchain.storage.load

import java.nio.file.Files

import scala.collection.immutable.ListMap

import org.scholarchain.storage.TestFixtures

/** Argument handling of `StorageLoadMain` (decision 7: the option constant is the contract, an
  * unknown option is refused; AWS mode allows one writer only).
  */
class StorageLoadMainArgsSuite extends munit.FunSuite {
  import StorageLoadMain._

  test("the option constant has exactly these subcommands and option names") {
    assertEquals(
      Options,
      ListMap(
        "dynamodb-load" -> SubcommandOptions(
          List("config", "stores", "export", "out", "code-version"),
          List("local-root", "parallelism", "passes", "ranking-top-k")
        ),
        "neptune-csv-build" -> SubcommandOptions(
          List("config", "stores", "export", "out", "code-version", "dataset-id", "snapshot-id"),
          List("local-root", "rows-per-file", "full-window")
        ),
        "neptune-bulk-start" -> SubcommandOptions(
          List("config", "stores", "graph-run", "loader-role-arn", "out", "code-version"),
          List("local-root")
        ),
        "neptune-bulk-status" -> SubcommandOptions(
          List("config", "stores", "load-id", "out", "code-version"),
          List("local-root", "wait-seconds")
        )
      )
    )
    assertEquals(
      List(Exit.Completed, Exit.Failed, Exit.Refused, Exit.NotFinished),
      List(0, 1, 2, 3)
    )
  }

  private val base = List(
    "--config",
    "c.json",
    "--stores",
    "local",
    "--local-root",
    "root",
    "--out",
    "out",
    "--code-version",
    "0123456789abcdef0123456789abcdef01234567"
  )
  private def status(extra: String*) =
    "neptune-bulk-status" :: base ++ List("--load-id", "load-1") ++ extra
  private def dyn(extra: String*) = "dynamodb-load" :: base ++ List("--export", "dir") ++ extra
  private def err(args: List[String]): String =
    parse(args).flatMap(settings).left.toOption.getOrElse(fail(s"accepted $args"))

  test("every subcommand accepts exactly its required options") {
    Options.foreach { case (sub, spec) =>
      val args =
        sub :: spec.required.flatMap(n => List(s"--$n", if (n == "stores") "local" else "x"))
      val inv = parse(args).fold(e => fail(e), identity)
      assertEquals(inv.subcommand, sub)
      assertEquals(inv.values.keySet, spec.required.toSet)
    }
  }

  test("no subcommand, an unknown subcommand and malformed pairs are refused") {
    assert(err(Nil).contains("a subcommand is required"))
    assert(err(List("drop-tables")).contains("unknown subcommand"))
    assert(err(status("--wait-seconds")).contains("--name value pairs"))
    assert(err("neptune-bulk-status" :: "config" :: "c.json" :: Nil).contains("--name value"))
  }

  test("an unknown option is refused, also an option of another subcommand") {
    assert(
      err(status("--verbose", "1")).contains("unknown options for neptune-bulk-status: --verbose")
    )
    assert(err(status("--export", "x")).contains("--export"))
    assert(err(dyn("--load-id", "x")).contains("unknown options for dynamodb-load"))
  }

  test("repeated and missing options are refused") {
    assert(err(status("--load-id", "load-2")).contains("repeated options: --load-id"))
    assert(err("dynamodb-load" :: base).contains("missing options for dynamodb-load: --export"))
  }

  test("store kinds and the local root") {
    val aws = (a: List[String]) => a.updated(a.indexOf("local"), "aws")
    assert(
      err(dyn().updated(dyn().indexOf("local"), "s3")).contains("--stores must be local or aws")
    )
    assert(
      err(dyn().patch(dyn().indexOf("--local-root"), Nil, 2))
        .contains("--local-root is required with --stores local")
    )
    assert(err(aws(dyn())).contains("--local-root is only for --stores local"))
  }

  test("AWS mode refuses more than one writer and says why; local mode allows it") {
    val awsDyn = "dynamodb-load" :: List(
      "--config",
      "c.json",
      "--stores",
      "aws",
      "--out",
      "out",
      "--code-version",
      "abc",
      "--export",
      "s3://example-graph-bucket/graph/exports/run=0"
    )
    val e = err(awsDyn ++ List("--parallelism", "2"))
    assert(e.contains("one writer only"), e)
    assert(e.contains("Phase 7 m3"), e)
    assert(parse(awsDyn ++ List("--parallelism", "1")).flatMap(settings).isRight)
    assertEquals(
      parse(dyn("--parallelism", "8")).flatMap(settings).toOption.get.parallelism,
      8
    )
    assert(err(awsDyn.updated(awsDyn.size - 1, "/local/dir")).contains("--export must be an s3://"))
  }

  test("value ranges and patterns; refused values are not repeated") {
    assert(err(dyn("--parallelism", "65")).contains("--parallelism must be an integer in 1..64"))
    assert(err(dyn("--passes", "3")).contains("--passes must be an integer in 1..2"))
    assert(err(dyn("--ranking-top-k", "0")).contains("--ranking-top-k"))
    assert(err(status("--wait-seconds", "3601")).contains("--wait-seconds"))
    val cv = base.indexOf("--code-version") + 1
    val badCode = "neptune-bulk-status" :: base.updated(cv, "bad version!") ++
      List("--load-id", "x")
    assert(err(badCode).contains("--code-version must match"))
    val badId = "neptune-bulk-status" :: base ++ List("--load-id", "x;y")
    assert(err(badId).contains("--load-id must match"))
    val csv = "neptune-csv-build" :: base ++
      List("--export", "d", "--dataset-id", "../x", "--snapshot-id", "s")
    val m = err(csv)
    assert(m.contains("--dataset-id must match"), m)
    assert(!m.contains("../x"), m)
  }

  test("run: a refusal is exit 2 with one printed line; an existing --out is refused") {
    val printed = new LoadFixtures.Printed
    assertEquals(run(List("nope"), Wiring(env = Map.empty, print = printed.print)), Exit.Refused)
    assert(printed.text.startsWith("[storage-load] refused: unknown subcommand"), printed.text)
    val out = TestFixtures.tempDir("exists")
    val args = status().updated(status().indexOf("out"), out.toString)
    val p2 = new LoadFixtures.Printed
    assertEquals(run(args, Wiring(env = Map.empty, print = p2.print)), Exit.Refused)
    assert(p2.text.contains("--out exists"), p2.text)
    assertEquals(Files.list(out).count(), 0L)
  }
}
