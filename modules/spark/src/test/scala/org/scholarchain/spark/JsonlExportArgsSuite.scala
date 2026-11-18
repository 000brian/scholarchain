package org.scholarchain.spark

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.apache.hadoop.fs.Path

/** The JsonlExport entry-point contract (Phase 13 decision 7): option names, parsing, masking and
  * the build identity. No Spark session.
  */
class JsonlExportArgsSuite extends munit.FunSuite {

  private val ok = Seq("--source-run", "s3://example-bucket/graph/run=ab", "--out-root", "/tmp/x")

  test("the option constants hold exactly the entry point's option names") {
    assertEquals(JsonlExport.Required, List("source-run", "out-root"))
    assertEquals(JsonlExport.Optional, List("records-per-file", "code-version"))
  }

  test("every option of the constants is accepted, with the documented defaults") {
    val a = JsonlExport.parseArgs(ok).fold(e => fail(e), identity)
    assertEquals(a.sourceRun, new Path("s3://example-bucket/graph/run=ab"))
    assertEquals(a.outRoot, new Path("/tmp/x"))
    assertEquals(a.recordsPerFile, JsonlExport.DefaultRecordsPerFile)
    assertEquals(a.codeVersion, None)
    val all = JsonlExport
      .parseArgs(ok ++ Seq("--records-per-file", "9", "--code-version", "a" * 40))
      .fold(e => fail(e), identity)
    assertEquals(all.recordsPerFile, 9L)
    assertEquals(all.codeVersion, Some("a" * 40))
    // The job alias parses the same way.
    assertEquals(JsonlExportJob.parseArgs(ok), JsonlExport.parseArgs(ok))
  }

  test("unknown, repeated, missing and malformed options are refused") {
    def left(argv: Seq[String]): String =
      JsonlExport.parseArgs(argv).fold(identity, a => fail(s"accepted: $a"))
    assert(left(ok ++ Seq("--source", "x")).contains("unknown arguments: source"))
    assert(left(ok ++ Seq("--out-root", "/tmp/y")).contains("repeated arguments: out-root"))
    assert(left(Seq("--out-root", "/tmp/y")).contains("missing arguments: --source-run"))
    assert(left(ok :+ "--code-version").contains("--name value pairs"))
    assert(left(Seq("source-run", "x", "--out-root", "y")).contains("--name"))
    assert(left(ok ++ Seq("--records-per-file", "0")).contains("records-per-file"))
    assert(left(ok ++ Seq("--records-per-file", "x")).contains("records-per-file"))
    assert(left(Seq("--source-run", " ", "--out-root", "y")).contains("--source-run is empty"))
    // An invalid code version is refused without echoing the value.
    val e = left(ok ++ Seq("--code-version", "token=abc/def"))
    assert(e.contains("--code-version"), e)
    assert(!e.contains("token=abc"), e)
  }

  test("show masks the bucket or authority of every non-local location") {
    assertEquals(
      JsonlExport.show(new Path("s3://example-bucket/exports/run=ab")),
      "s3://{data-bucket}/exports/run=ab"
    )
    assertEquals(JsonlExport.show(new Path("s3a://example-bucket/k")), "s3a://{data-bucket}/k")
    assertEquals(JsonlExport.show(new Path("hdfs://nn:8020/x")), "hdfs://{authority}/x")
    assertEquals(JsonlExport.show(new Path("file:///tmp/a/run=ab")), "/tmp/a/run=ab")
    assertEquals(JsonlExport.show(new Path("/tmp/a")), "/tmp/a")
  }

  test("maskText removes bucket names and the account of ARNs") {
    val text = "getFileStatus on s3a://example-bucket/x: User: " +
      "arn:aws:sts::123456789012:assumed-role/r/i is not authorized; Bucket example-bucket"
    val m = JsonlExport.maskText(text, Seq("example-bucket"))
    assert(!m.contains("example-bucket"), m)
    assert(!m.contains("123456789012"), m)
    assert(m.contains("s3a://{data-bucket}/x"), m)
    assert(m.contains("arn:aws:sts::{account}:assumed-role"), m)
    assertEquals(JsonlExport.maskText("s3://other/k"), "s3://{data-bucket}/k")
  }

  test("build identity: parsed from the resource, unknown when missing or malformed") {
    val good = s"buildCommit=${"0a" * 20}\nbuildDirty=false\nmodule=sparkAnalytics\nversion=0.1.0\n"
    assertEquals(
      BuildIdentity.parse(good),
      Right(BuildIdentity("0a" * 20, "false", "sparkAnalytics", "0.1.0", "resource"))
    )
    assert(BuildIdentity.parse(good.replace("false", "maybe")).isLeft)
    assert(BuildIdentity.parse("buildDirty=true\nmodule=m\nversion=1").isLeft)
    assert(BuildIdentity.parse(good.replace("0a" * 20, "abc")).isLeft)

    val dir = Files.createTempDirectory("build-identity").toFile
    def loader(text: Option[String]): ClassLoader = {
      val d = new File(dir, s"cp-${text.hashCode.abs}")
      d.mkdirs()
      text.foreach { t =>
        Files.write(
          new File(d, BuildIdentity.ResourceName).toPath,
          t.getBytes(StandardCharsets.UTF_8)
        )
      }
      new URLClassLoader(Array(d.toURI.toURL), null)
    }
    assertEquals(BuildIdentity.load(loader(Some(good))).status, "resource")
    assertEquals(BuildIdentity.load(loader(None)), BuildIdentity.unknown("missing"))
    val bad = BuildIdentity.load(loader(Some("buildCommit=xyz")))
    assertEquals(bad.buildCommit, "unknown")
    assert(bad.status.startsWith("malformed: "), bad.status)
    // The module's own class path: the generated resource is well formed when present.
    assert(Set("resource", "missing")(BuildIdentity.load().status), BuildIdentity.load().status)
  }
}
