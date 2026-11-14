package org.scholarchain.hadoop

import org.apache.hadoop.fs.Path
import org.scholarchain.core.{AuthorMention, Contract, Venue, WorkRecord}
import org.scholarchain.hadoop.common.{Counters, Disposition, JobRunner}
import org.scholarchain.hadoop.dblp.DblpExtractJob
import upickle.default.read

class DblpExtractJobSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private def runOn(input: Path, snapshot: String, overrides: (String, String)*) = {
    val work = TestSupport.tempDir("j01")
    val conf = TestSupport.localConf(work, overrides*)
    val out = new Path(work.resolve("out").toUri)
    val fp = JobRunner.fingerprintFiles(conf, List(input))
    val r = JobRunner.run(conf, DblpExtractJob.spec(input, out, snapshot, fp))
    (conf, r)
  }

  test("J01 extracts the real dblp sample: 4 subset works, 5 exclusions, accounting holds") {
    val (conf, r) = runOn(TestSupport.sample("dblp/dblp-sample.xml"), "2026-11-01")
    val works =
      TestSupport.lines(conf, r, "works").map(l => Contract.decode[WorkRecord](l).toOption.get)
    assertEquals(
      works.map(_.dblpKey).sorted,
      List(
        "conf/sigir/0003SM23",
        "conf/sigir/HofstatterC0Z23",
        "conf/sigir/LernerFGBB0L22",
        "conf/sigir/Sousa0J23"
      )
    )
    val excluded = TestSupport.lines(conf, r, "excluded/v1").map(read[Disposition](_))
    assertEquals(
      excluded.map(d => d.sourceRecordId.get -> d.reason).toMap,
      Map(
        "conf/sigir/KemmererGMAE14" -> "year",
        "conf/sigir/2023" -> "element",
        "conf/dac/ShenZWLDWCG23" -> "key_prefix",
        "reference/vision/X14bd" -> "element",
        "dblpnote/neverpublished" -> "key_prefix"
      )
    )
    assertEquals(r.main(Counters.Input), 9L)
    assertEquals(
      r.main(Counters.Accepted) + r.main(Counters.Excluded) + r.main(Counters.Rejected),
      9L
    )
    val sousa = works.find(_.dblpKey == "conf/sigir/Sousa0J23").get
    assertEquals(
      sousa.authorNames,
      List("Hugo O. Sousa", "Ricardo Campos 0001", "Alípio Mário Jorge")
    )
    assertEquals(sousa.doi, Some("10.1145/3539618.3591892"))
    assertEquals(sousa.venueKey, Some("conf/sigir"))
    assertEquals(sousa.year, 2023)
    assertEquals(sousa.provenance.snapshotId, "2026-11-01")
    assertEquals(sousa.provenance.runId, r.runId)
    val mentions = TestSupport
      .lines(conf, r, "mentions")
      .map(l => Contract.decode[AuthorMention](l).toOption.get)
    assertEquals(mentions.size, 3 + 4 + 3 + 7)
    assertEquals(
      mentions.filter(_.workId == sousa.workId).map(_.orcid.isDefined),
      List(true, true, true)
    )
    val venues =
      TestSupport.lines(conf, r, "venues").map(l => Contract.decode[Venue](l).toOption.get)
    assertEquals(venues.map(v => v.venueKey -> v.name), List("conf/sigir" -> "SIGIR"))
  }

  private val header = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE dblp [
<!ENTITY eacute "&#233;">
]>
<dblp>
"""
  private def rec(key: String, extra: String = "", year: String = "2020") =
    s"""<article key="$key"><author>A B</author><author>C D</author><title>T $key</title><year>$year</year>$extra</article>\n"""

  private def reasons(conf: org.apache.hadoop.conf.Configuration, r: common.JobResult) =
    TestSupport
      .lines(conf, r, "rejects/v1")
      .map(read[Disposition](_))
      .map(d => d.sourceRecordId.getOrElse("") -> d.reason)
      .sorted

  test(
    "malformed, undefined-entity, oversize, duplicate and conflicting records are rejected and counted"
  ) {
    val dir = TestSupport.tempDir("j01-bad")
    val body = header +
      rec("conf/sigir/ok1", "<author>J&eacute;</author>") +
      rec("conf/sigir/ent", "<author>&ouml;</author>") +
      "<article key=\"conf/sigir/bad\"><author>A</author><title>x</year></article>\n" +
      rec("conf/sigir/big", "<note>" + ("n" * 3000) + "</note>") +
      rec("conf/sigir/dup") + rec("conf/sigir/dup") +
      rec("conf/sigir/cfl") + rec("conf/sigir/cfl", year = "2021") +
      "</dblp>\n"
    val input = TestSupport.writeFile(dir, "in/bad.xml", body)
    val (conf, r) = runOn(input, "t", "scholarchain.dblp.maxRecordBytes" -> "2000")
    assertEquals(
      reasons(conf, r),
      List(
        "conf/sigir/bad" -> "malformed_xml",
        "conf/sigir/big" -> "oversize_record",
        "conf/sigir/cfl" -> "duplicate_key_conflict",
        "conf/sigir/cfl" -> "duplicate_key_conflict",
        "conf/sigir/ent" -> "undefined_entity"
      )
    )
    assertEquals(r.main(Counters.Input), 8L)
    assertEquals(
      r.main(Counters.Accepted) + r.main(Counters.Excluded) + r.main(Counters.Rejected),
      8L
    )
    assertEquals(r.main(Counters.Duplicate), 1L)
    assertEquals(r.main(Counters.Conflict), 2L)
    val works =
      TestSupport.lines(conf, r, "works").map(l => Contract.decode[WorkRecord](l).toOption.get)
    assertEquals(works.map(_.dblpKey).sorted, List("conf/sigir/dup", "conf/sigir/ok1"))
    assertEquals(works.find(_.dblpKey == "conf/sigir/ok1").get.authorNames.last, "Jé")
  }

  test("entity blow-up beyond maxExpandedRecordChars is rejected with a reason, not expanded") {
    val dir = TestSupport.tempDir("j01-bomb")
    val bombHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE dblp [\n" +
      "<!ENTITY eacute \"&#233;\">\n<!ENTITY big \"" + ("b" * 1000) + "\">\n]>\n<dblp>\n"
    val body = bombHeader +
      rec("conf/sigir/ok1", "<author>J&eacute;</author>") +
      rec("conf/sigir/bomb", "<note>" + ("&big;" * 100) + "</note>") +
      "</dblp>\n"
    val input = TestSupport.writeFile(dir, "in/bomb.xml", body)
    val (conf, r) = runOn(input, "t", "scholarchain.dblp.maxExpandedRecordChars" -> "50000")
    assertEquals(reasons(conf, r), List("conf/sigir/bomb" -> "expanded_record_oversize"))
    assertEquals(r.counter(Counters.RejectReasonGroup, "expanded_record_oversize"), 1L)
    assertEquals(r.main(Counters.Accepted), 1L)
    assertEquals(
      r.main(Counters.Input),
      r.main(Counters.Accepted) + r.main(Counters.Excluded) + r.main(Counters.Rejected)
    )
    // the default bound (2 Mi characters) admits the 100,000-character expansion
    val (_, r2) = runOn(input, "t")
    assertEquals(r2.main(Counters.Accepted), 2L)
  }

  test("reject previews of raw units follow scholarchain.reject.previewChars (no fixed cap)") {
    val dir = TestSupport.tempDir("j01-preview")
    val body = header + rec("conf/sigir/big", "<note>" + ("n" * 20000) + "</note>") + "</dblp>\n"
    val input = TestSupport.writeFile(dir, "in/p.xml", body)
    val (conf, r) = runOn(
      input,
      "t",
      "scholarchain.dblp.maxRecordBytes" -> "10000",
      "scholarchain.reject.previewChars" -> "6000"
    )
    val rejects = TestSupport.lines(conf, r, "rejects/v1").map(read[Disposition](_))
    assertEquals(rejects.map(_.reason), List("oversize_record"))
    assertEquals(rejects.head.preview.map(_.length), Some(6000))
  }

  test("truncated input and unsupported encodings are rejected, never dropped") {
    val dir = TestSupport.tempDir("j01-trunc")
    val cut = header + rec("conf/sigir/a1") + "<article key=\"conf/sigir/a2\"><author>X</author><ti"
    val (conf, r) = runOn(TestSupport.writeFile(dir, "in/cut.xml", cut), "t")
    assertEquals(reasons(conf, r), List("conf/sigir/a2" -> "truncated_record"))
    assertEquals(r.main(Counters.Accepted), 1L)
    val dir2 = TestSupport.tempDir("j01-enc")
    val utf16 =
      "<?xml version=\"1.0\" encoding=\"UTF-16\"?><dblp>" + rec("conf/sigir/u1") + "</dblp>"
    val (conf2, r2) = runOn(TestSupport.writeFile(dir2, "in/u.xml", utf16), "t")
    assertEquals(reasons(conf2, r2), List("conf/sigir/u1" -> "unsupported_encoding"))
  }

  test("gzip input is read whole; the decompression guard stops oversized files with a reject") {
    val dir = TestSupport.tempDir("j01-gz")
    val body = header + (1 to 50).map(i => rec(s"conf/sigir/g$i")).mkString + "</dblp>\n"
    val gz = dir.resolve("in/sample.xml.gz")
    java.nio.file.Files.createDirectories(gz.getParent)
    val os = new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(gz))
    os.write(body.getBytes("UTF-8")); os.close()
    val input = new Path(gz.getParent.toUri)
    val (conf, r) = runOn(input, "t")
    assertEquals(r.main(Counters.Accepted), 50L)
    val (conf2, r2) = runOn(input, "t", "scholarchain.input.maxDecompressedBytesPerFile" -> "2000")
    assert(reasons(conf2, r2).exists(_._2 == "decompression_limit_exceeded"))
    assert(r2.main(Counters.Accepted) < 50L)
    assertEquals(
      r2.main(Counters.Input),
      r2.main(Counters.Accepted) + r2.main(Counters.Excluded) + r2.main(Counters.Rejected)
    )
  }

  test("record-boundary-safe splitting: many small splits give the same works as one split") {
    val in = TestSupport.sample("dblp/dblp-sample.xml")
    val (c1, one) = runOn(in, "2026-11-01")
    val (c2, many) =
      runOn(in, "2026-11-01", "mapreduce.input.fileinputformat.split.maxsize" -> "257")
    assert(many.counters.contains("scholarchain:INPUT_RECORDS"))
    assertEquals(many.main(Counters.Input), 9L)
    assertEquals(
      TestSupport.lines(c2, many, "works").sorted.map(_.replaceAll("run:v1:[0-9a-f]+", "")),
      TestSupport.lines(c1, one, "works").sorted.map(_.replaceAll("run:v1:[0-9a-f]+", ""))
    )
  }
}
