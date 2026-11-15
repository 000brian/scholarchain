package org.scholarchain.storage

import java.nio.file.Files

import org.scholarchain.core.GraphNode
import org.scholarchain.storage.source._

class ExportSourceSuite extends munit.FunSuite {
  import TestFixtures._

  test("manifest parses and every table decodes with Contract.decode") {
    val dir = writeExport(tempDir("src"))
    val src = new LocalDirectorySource(dir)
    val m = src.manifest()
    assertEquals(m.runId, sparkRun)
    assertEquals(m.windowLabels, List(Full))
    val rejects = new RejectLog(None)
    val stats =
      TableReader.foreach[GraphNode](src, m.require("nodes:researcher"), rejects, "t")(_ => ())
    assertEquals(stats, ReadStats("nodes:researcher", 4, 4, 0))
  }

  test("a malformed line is counted and logged as a reject, never dropped silently") {
    val dir = writeExport(
      tempDir("src-bad"),
      edit = t =>
        if (t.name == "nodes:researcher")
          t.lines.updated(1, """{"schemaVersion":1,"nodeId":"bad"}""")
        else t.lines
    )
    val src = new LocalDirectorySource(dir)
    val logFile = dir.resolve("rejects.jsonl")
    val rejects = new RejectLog(Some(logFile))
    var seen = 0
    val stats =
      TableReader.foreach[GraphNode](src, src.manifest().require("nodes:researcher"), rejects, "t")(
        _ => seen += 1
      )
    rejects.close()
    assertEquals(stats.decoded, 3L)
    assertEquals(stats.rejected, 1L)
    assertEquals(seen, 3)
    assertEquals(rejects.count("t", "nodes:researcher"), 1L)
    val logged = Files.readAllLines(logFile)
    assertEquals(logged.size, 1)
    assert(logged.get(0).contains("\"line\":2"), logged.get(0))
  }

  test("a line count that differs from the manifest fails the read") {
    val dir = writeExport(tempDir("src-count"), manifestRecords = Map("nodes:venue" -> 2L))
    val src = new LocalDirectorySource(dir)
    intercept[ExportSourceException] {
      TableReader.foreach[GraphNode](
        src,
        src.manifest().require("nodes:venue"),
        new RejectLog(None),
        "t"
      )(_ => ())
    }
  }

  test("a file whose bytes differ from the manifest sha256 fails the read") {
    val dir = writeExport(tempDir("src-sha"))
    val src = new LocalDirectorySource(dir)
    val m = src.manifest()
    val t = m.require("nodes:venue")
    val tampered = t.copy(files = t.files.map(_.copy(sha256 = "0" * 64)))
    val e = intercept[ExportSourceException] {
      TableReader.foreach[GraphNode](src, tampered, new RejectLog(None), "t")(_ => ())
    }
    assert(e.getMessage.contains("sha256"))
  }

  test("paths escaping the export directory are refused") {
    val src = new LocalDirectorySource(tempDir("src-esc"))
    intercept[ExportSourceException](src.lines(ExportFile("../x.jsonl", 0, "", 0)))
  }

  test("an unsupported manifest format is rejected") {
    assert(ExportManifest.parse("""{"format":"other","formatVersion":1}""").isLeft)
  }
}
