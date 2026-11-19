package org.scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import org.scholarchain.hadoop.blocking.BlockLimit
import org.scholarchain.hadoop.common.{Settings, SettingsError}

class SettingsSuite extends munit.FunSuite {

  private def defaults = Settings.withDefaults(new Configuration(false))

  test("every configuration key used by the jobs has a documented default") {
    val conf = defaults
    val keys = Settings.Keys.getClass.getDeclaredFields.toList
      .filter(_.getType == classOf[String])
      .map { f => f.setAccessible(true); f.get(Settings.Keys).asInstanceOf[String] }
      .filterNot(k => k.startsWith(Settings.RunPrefix) || k.startsWith(Settings.PathPrefix))
    val optional = Set(Settings.Keys.DblpDtdPath, Settings.Keys.CodeVersion)
    assert(keys.size > 30, keys)
    keys.filterNot(optional).foreach(k => assert(conf.get(k) != null, s"no default for $k"))
    // The defaults resource itself documents every key with a description.
    val xml = scala.io.Source.fromResource(Settings.DefaultsResource).mkString
    keys
      .filterNot(_ == Settings.Keys.CodeVersion)
      .foreach(k => assert(xml.contains(s"<name>$k</name>"), k))
    assertEquals(xml.split("<property>").length - 1, xml.split("<description>").length - 1)
  }

  test("G-17: blocking and candidate defaults equal Phase 5's evaluated real-run settings") {
    // docs/agent-runs/phase-05/agent-report-hadoop.md:119 (the er-v2 calibration candidate set);
    // the EMR state machine cannot pass -D, so the built-in defaults must carry these values.
    val s = new Settings(defaults)
    assertEquals(s.positiveInt(Settings.Keys.BlockingMaxBlock), 200)
    assertEquals(s.positiveInt(Settings.Keys.FallbackMaxBlock), 200)
    assertEquals(s.boolean(Settings.Keys.CandidateSkipSameNumberedName), true)
    assertEquals(s.positiveLong(Settings.Keys.CandidateMaxPairsPerBlock), 125000L)
    assertEquals(s.string(Settings.Keys.BlockingFamilies), "dblp-base-name,initials-surname")
    assertEquals(s.string(Settings.Keys.FallbackSecondaryKeys), "coauthor")
    // effective caps of J05 primary and J05b fallback blocks: 200 members, set by the member cap
    List(Settings.Keys.BlockingMaxBlock, Settings.Keys.FallbackMaxBlock).foreach { k =>
      val lim = BlockLimit.of(s, k)
      assertEquals(lim.effective, 200, k)
      assert(!lim.pairBound, k)
    }
  }

  test("missing and malformed values fail loudly; no fallback values") {
    val s = new Settings(defaults)
    intercept[SettingsError](s.string(Settings.Keys.CodeVersion))
    val conf = defaults
    conf.set(Settings.Keys.TeamHardLimit, "many")
    intercept[SettingsError](new Settings(conf).int(Settings.Keys.TeamHardLimit))
    conf.set(Settings.Keys.TeamHardLimit, "0")
    intercept[SettingsError](new Settings(conf).positiveInt(Settings.Keys.TeamHardLimit))
  }

  test("raw-unit preview bytes derive from previewChars; an unrepresentable value fails loudly") {
    val conf = defaults
    assertEquals(new Settings(conf).rejectPreviewBytes, 512 * 4)
    conf.set(Settings.Keys.RejectPreviewChars, "100000")
    assertEquals(new Settings(conf).rejectPreviewBytes, 400000)
    conf.set(Settings.Keys.RejectPreviewChars, Int.MaxValue.toString)
    intercept[SettingsError](new Settings(conf).rejectPreviewBytes)
  }

  test("fingerprint ignores per-run keys and changes with any threshold") {
    val a = defaults
    val base = Settings.fingerprint(a)
    a.set(Settings.Keys.RunId, "run:v1:00000000000000000000000000000000")
    a.set("scholarchain.path.anything", "/tmp/x")
    assertEquals(Settings.fingerprint(a), base)
    a.set(Settings.Keys.TeamOversize, "101")
    assertNotEquals(Settings.fingerprint(a), base)
  }

  test("operational keys do not change the fingerprint; output-affecting keys do") {
    val base = Settings.fingerprint(defaults)
    // recovery and shuffle-only settings: same fingerprint, so the same run ID and directory
    List(
      Settings.Keys.ReplaceIncomplete -> "true",
      Settings.Keys.IntermediateCompress -> "false",
      Settings.Keys.IntermediateCodec -> "org.apache.hadoop.io.compress.GzipCodec"
    ).foreach { case (k, v) =>
      val c = defaults
      c.set(k, v)
      assertEquals(Settings.fingerprint(c), base, k)
      assert(!Settings.fingerprinted(k), k)
    }
    assertEquals(
      Settings.OperationalKeys,
      Set(
        Settings.Keys.ReplaceIncomplete,
        Settings.Keys.IntermediateCompress,
        Settings.Keys.IntermediateCodec
      )
    )
    // keys that change output bytes (records, file layout, counters in _job.json) stay in it
    List(
      Settings.Keys.CodeVersion -> "other",
      Settings.Keys.OutputCompress -> "true",
      Settings.Keys.OutputCodec -> "org.apache.hadoop.io.compress.DefaultCodec",
      Settings.Keys.RejectPreviewChars -> "64",
      Settings.Keys.DblpYearMin -> "2016",
      Settings.Keys.DblpKeyPrefixes -> "conf/sigir/",
      Settings.Keys.CandidateFeatureSet -> "candidate-features/other",
      Settings.Keys.BlockingPolicy -> "blocking/other",
      Settings.Keys.PairsPerShard -> "7",
      Settings.Keys.CandidatePairsPerShard -> "7",
      Settings.Keys.DblpReducers -> "1",
      Settings.Keys.PairExpandReducers -> "1",
      Settings.Keys.ExportReducers -> "1"
    ).foreach { case (k, v) =>
      val c = defaults
      c.set(k, v)
      assertNotEquals(Settings.fingerprint(c), base, k)
      assert(Settings.fingerprinted(k), k)
    }
  }
}
