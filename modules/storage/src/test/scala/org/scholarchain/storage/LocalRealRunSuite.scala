package org.scholarchain.storage

import java.nio.file.Files

class LocalRealRunSuite extends munit.FunSuite {
  import TestFixtures._

  test(
    "the local run on the fixture export: ids match, DynamoDB pass 2 changes nothing, vector run 2 skips 100%"
  ) {
    val exportDir = writeExport(tempDir("run-in"))
    val out = tempDir("run-out").resolve("attempt-01")
    LocalRealRun.main(Array("--export", exportDir.toString, "--out", out.toString))
    val r = ujson.read(Files.readString(out.resolve("run-report.json")))
    assertEquals(r("status").str, "completed")
    assert(r("neptune")("types").arr.filter(_("rows").num > 0).forall(_("idsMatch").bool))
    assertEquals(r("dynamodb")("pass2")("dataChanges").num, 0.0)
    assert(r("dynamodb")("pass1")("dataChanges").num > 0)
    assertEquals(r("vectorIngest")("run1")("written").num, 6.0)
    assertEquals(r("vectorIngest")("run2")("skipRatio").num, 1.0)
    assertEquals(r("rejects")("total").num, 0.0)
    assert(
      r("vectorIngest")("samples").arr.forall(s =>
        s("top1IsSelf").bool && s("textFetchedByPointerMatchesHash").bool
      )
    )
    intercept[RuntimeException](
      LocalRealRun.main(Array("--export", exportDir.toString, "--out", out.toString))
    )
  }
}
