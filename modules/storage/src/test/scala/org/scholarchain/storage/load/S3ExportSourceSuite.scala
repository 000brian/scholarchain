package org.scholarchain.storage.load

import java.nio.charset.StandardCharsets

import org.scholarchain.core.GraphNode
import org.scholarchain.storage.TestFixtures
import org.scholarchain.storage.local.InMemoryObjectStore
import org.scholarchain.storage.source._

/** The S3 export source (decision 7a) against the storage stub object store (in-memory ordinary S3
  * with S3's semantics): the same checks as [[LocalDirectorySource]]; no network.
  */
class S3ExportSourceSuite extends munit.FunSuite {
  import LoadFixtures._
  import TestFixtures.{tempDir, writeExport}

  private def s3Source(store: InMemoryObjectStore): S3ExportSource =
    S3ExportSource.fromUri(store, ExportUri).fold(e => fail(e), identity)

  private val researcherFile = dataFile("nodes/node_label=researcher")

  test("an S3 export reads like the local directory: manifest, sha256, presence and records") {
    val dir = writeExport(tempDir("s3src"))
    val store = new InMemoryObjectStore
    upload(store, dir)
    val local = new LocalDirectorySource(dir)
    val s3 = s3Source(store)
    val (m, sha) = s3.manifestWithSha256()
    val (lm, lsha) = local.manifestWithSha256()
    assertEquals(m, lm)
    assertEquals(sha, lsha)
    assertEquals(s3.checkPresence(m), local.checkPresence(lm))
    assertEquals(s3.verifyAll(m), local.verifyAll(lm))
    def read(src: ExportSource) = {
      val seen = scala.collection.mutable.ListBuffer.empty[String]
      val stats = TableReader.foreach[GraphNode](
        src,
        src.manifest().require("nodes:researcher"),
        new RejectLog(None),
        "t"
      )(n => seen += n.nodeId)
      (stats, seen.toList)
    }
    assertEquals(read(s3), read(local))
    assertEquals(read(s3)._1, ReadStats("nodes:researcher", 4, 4, 0))
  }

  test("the bucket never appears in describe or in an error message") {
    val store = new InMemoryObjectStore
    val s3 = s3Source(store)
    assert(!s3.describe.contains(GraphBucket), s3.describe)
    assert(s3.describe.startsWith("s3://{data-bucket}/graph/exports/run="), s3.describe)
    val e = intercept[ExportSourceException](s3.manifest())
    assert(!e.getMessage.contains(GraphBucket), e.getMessage)
  }

  test("a missing manifest is refused with a clear message") {
    val dir = writeExport(tempDir("s3src-nomanifest"))
    val store = new InMemoryObjectStore
    upload(store, dir, skip = Set(ExportManifest.FileName))
    val e = intercept[ExportSourceException](s3Source(store).manifest())
    assert(e.getMessage.contains("export manifest _export_manifest.json is missing"), e.getMessage)
  }

  test("a changed file (same size, different bytes) fails the sha256 check when read") {
    val dir = writeExport(tempDir("s3src-sha"))
    val original = java.nio.file.Files.readAllBytes(dir.resolve(researcherFile))
    val tampered = original.clone()
    val i = tampered.indexOf('A'.toByte)
    assert(i >= 0)
    tampered(i) = 'B'.toByte
    val store = new InMemoryObjectStore
    upload(store, dir, replace = { case `researcherFile` => tampered })
    val s3 = s3Source(store)
    val m = s3.manifest()
    assertEquals(s3.checkPresence(m).files, m.tables.map(_.files.size).sum)
    val e1 = intercept[ExportSourceException](s3.verifyAll(m))
    assert(e1.getMessage.contains("sha256"), e1.getMessage)
    assert(e1.getMessage.contains(researcherFile), e1.getMessage)
    val e2 = intercept[ExportSourceException](
      TableReader.foreach[GraphNode](s3, m.require("nodes:researcher"), new RejectLog(None), "t")(
        _ => ()
      )
    )
    assert(e2.getMessage.contains("sha256"), e2.getMessage)
  }

  test("a file whose size differs from the manifest fails the presence check") {
    val dir = writeExport(tempDir("s3src-size"))
    val store = new InMemoryObjectStore
    upload(
      store,
      dir,
      replace = { case `researcherFile` =>
        java.nio.file.Files.readAllBytes(dir.resolve(researcherFile)) ++
          "\n".getBytes(StandardCharsets.UTF_8)
      }
    )
    val s3 = s3Source(store)
    val e = intercept[ExportSourceException](s3.checkPresence(s3.manifest()))
    assert(e.getMessage.contains("bytes present, manifest says"), e.getMessage)
  }

  test("a missing listed file fails the presence check and the read") {
    val dir = writeExport(tempDir("s3src-missing"))
    val store = new InMemoryObjectStore
    upload(store, dir, skip = Set(researcherFile))
    val s3 = s3Source(store)
    val m = s3.manifest()
    val e1 = intercept[ExportSourceException](s3.checkPresence(m))
    assert(e1.getMessage.contains(s"missing export file $researcherFile"), e1.getMessage)
    val e2 = intercept[ExportSourceException](s3.verifyAll(m))
    assert(e2.getMessage.contains("missing export file"), e2.getMessage)
  }

  test("the local directory source makes the same presence and manifest checks") {
    val dir = writeExport(tempDir("local-checks"))
    java.nio.file.Files.delete(dir.resolve(researcherFile))
    val local = new LocalDirectorySource(dir)
    val e = intercept[ExportSourceException](local.checkPresence(local.manifest()))
    assert(e.getMessage.contains("missing export file"), e.getMessage)
    val empty = new LocalDirectorySource(tempDir("local-empty"))
    val e2 = intercept[ExportSourceException](empty.manifest())
    assert(e2.getMessage.contains("is missing"), e2.getMessage)
  }

  test("manifest paths that escape the run prefix are refused") {
    val s3 = s3Source(new InMemoryObjectStore)
    List("../x.jsonl", "/abs.jsonl", "a//b.jsonl", "a/./b.jsonl").foreach { p =>
      intercept[ExportSourceException](s3.lines(ExportFile(p, 0, "", 0)))
    }
  }

  test("only s3://BUCKET/PREFIX/run=<32 hex> locations are accepted; messages omit the URI") {
    val store = new InMemoryObjectStore
    val bad = List(
      "https://example-graph-bucket/graph/run=" + runHex,
      s"s3://$GraphBucket/graph/exports",
      s"s3://$GraphBucket/graph/run=XYZ",
      s"s3://$GraphBucket/graph/../run=$runHex",
      s"s3://$GraphBucket/run=$runHex/../x",
      "s3://Bad_Bucket/graph/run=" + runHex,
      null
    )
    bad.foreach { u =>
      val r = S3ExportSource.fromUri(store, u)
      assert(r.isLeft, s"accepted $u")
      assert(!r.left.toOption.get.contains(GraphBucket), r.toString)
    }
    assert(S3ExportSource.fromUri(store, ExportUri + "/").isRight)
    assertEquals(S3ExportSource.fromUri(store, ExportUri).toOption.get.root.key, ExportKey)
  }

  test("ExportSources.open: s3:// through the store, otherwise a local directory") {
    val dir = writeExport(tempDir("open"))
    val store = new InMemoryObjectStore
    assert(ExportSources.open(ExportUri, Some(store)).toOption.get.isInstanceOf[S3ExportSource])
    assert(ExportSources.open(ExportUri, None).isLeft)
    assert(
      ExportSources.open(dir.toString, None).toOption.get.isInstanceOf[LocalDirectorySource]
    )
    assert(ExportSources.open(dir.resolve("absent").toString, None).isLeft)
  }
}
