package org.scholarchain.hadoop

import org.apache.hadoop.fs.Path
import org.apache.hadoop.util.ToolRunner
import org.scholarchain.hadoop.common.{Counters, Settings}
import org.scholarchain.hadoop.pipeline.{Pipeline, PipelineSpec, ScholarChainHadoop}

/** The command-line driver as the EMR step calls it (Phase 14 decision 7, DBLP DTD on EMR).
  *
  * The state machine (`infra/terraform/modules/workflow/sfn.tf`, HadoopPipelineStep) runs
  * `pipeline -D scholarchain.code.version=V --output O --dblp D --dblp-snapshot S` and appends its
  * extra arguments after them. These tests feed that exact order through `ToolRunner`, which is
  * what `ScholarChainHadoop.main` does after the leading `pipeline` word.
  */
class ScholarChainHadoopSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(900, "s")

  // No internal DTD subset, like the real release: &eacute; is declared only in the DTD file.
  private val Xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n" +
    "<!DOCTYPE dblp SYSTEM \"dblp-2023-06-28.dtd\">\n<dblp>\n" +
    "<article key=\"conf/sigir/Cli20\"><author>J&eacute; One</author><author>B Two</author>" +
    "<title>T.</title><year>2020</year></article>\n" +
    "<article key=\"conf/sigir/Cli21\"><author>C Three</author><author>B Two</author>" +
    "<title>U.</title><year>2021</year></article>\n</dblp>\n"

  private def emrArgs(out: java.nio.file.Path, in: Path): Array[String] =
    Array(
      "-D",
      s"${Settings.Keys.CodeVersion}=test",
      "--output",
      out.toString,
      "--dblp",
      in.toString,
      "--dblp-snapshot",
      "cli"
    )

  private def setup(prefix: String) = {
    val work = TestSupport.tempDir(prefix)
    val in = TestSupport.writeFile(work, "in/dblp.xml", Xml)
    val dtd = TestSupport.writeFile(work, "raw/dblp.dtd", "<!ENTITY eacute \"&#233;\">\n")
    (work, in, dtd)
  }

  test("EMR order: a trailing -D for the DTD is refused before any job runs") {
    val (work, in, dtd) = setup("cli-trailing-d")
    val out = work.resolve("out")
    val e = intercept[IllegalArgumentException](
      ToolRunner.run(
        TestSupport.localConf(work),
        new ScholarChainHadoop,
        emrArgs(out, in) ++ Array("-D", s"${Settings.Keys.DblpDtdPath}=$dtd")
      )
    )
    assert(e.getMessage.contains("-D"), e.getMessage)
    assert(!java.nio.file.Files.exists(out))
  }

  test("EMR order: --dblp-dtd after the application options sets the DTD, same runs as -D") {
    val (work, in, dtd) = setup("cli-dtd")
    val out = work.resolve("out")
    val rc = ToolRunner.run(
      TestSupport.localConf(work),
      new ScholarChainHadoop,
      emrArgs(out, in) ++ Array("--dblp-dtd", dtd.toString)
    )
    assertEquals(rc, 0)
    // The same pipeline with the key set directly reuses every run the driver wrote: identical
    // run IDs, so the option is exactly equivalent to -D scholarchain.dblp.dtdPath.
    val conf = TestSupport.localConf(work, Settings.Keys.DblpDtdPath -> dtd.toString)
    val r = Pipeline.run(conf, PipelineSpec(in, "cli", Nil, new Path(out.toUri)))
    r.all.foreach(j => assert(j.reused, s"${j.jobName} was not reused"))
    assertEquals(r.dblp.extract.main(Counters.Accepted), 2L)
    assertEquals(r.dblp.extract.main(Counters.Rejected), 0L)
    // Control: without the DTD the record is rejected (undefined_entity), never guessed.
    val plain = TestSupport.localConf(work)
    val p = Pipeline.run(plain, PipelineSpec(in, "cli", Nil, new Path(work.resolve("plain").toUri)))
    assertEquals(p.dblp.extract.main(Counters.Accepted), 1L)
    assertEquals(p.dblp.extract.counter(Counters.RejectReasonGroup, "undefined_entity"), 1L)
  }

  test("--dblp-dtd: empty, given twice, or conflicting with -D is refused; unknown options too") {
    val (work, in, dtd) = setup("cli-dtd-bad")
    val out = work.resolve("out")
    def refused(conf: org.apache.hadoop.conf.Configuration, extra: String*): Unit = {
      intercept[IllegalArgumentException](
        ToolRunner.run(conf, new ScholarChainHadoop, emrArgs(out, in) ++ extra)
      )
      ()
    }
    refused(TestSupport.localConf(work), "--dblp-dtd", " ")
    refused(TestSupport.localConf(work), "--dblp-dtd", dtd.toString, "--dblp-dtd", dtd.toString)
    refused(
      TestSupport.localConf(work, Settings.Keys.DblpDtdPath -> "/elsewhere/dblp.dtd"),
      "--dblp-dtd",
      dtd.toString
    )
    refused(TestSupport.localConf(work), "--dblp-dtdpath", dtd.toString)
    assert(!java.nio.file.Files.exists(out))
  }
}
