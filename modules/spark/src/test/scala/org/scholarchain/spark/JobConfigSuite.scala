package org.scholarchain.spark

import org.scholarchain.core.config.NamedWindow

class JobConfigSuite extends munit.FunSuite {

  private def shipped = FixtureData.config.copy(outputPartitions = 4)

  test("the shipped Phase 6 configuration parses with the decided windows and caps") {
    val c = shipped
    assertEquals(
      c.windows.map(w => (w.label, w.startYear, w.endYear)),
      List(
        ("full-2015-2025", 2015, 2025),
        ("w2015-2017", 2015, 2017),
        ("w2018-2020", 2018, 2020),
        ("w2021-2023", 2021, 2023),
        ("w2024-2025", 2024, 2025)
      )
    )
    assertEquals(c.fullWindowLabel, "full-2015-2025")
    val a = c.algorithms
    assertEquals(
      (a.componentsMaxIterations, a.labelPropagationMaxIterations, a.kcoreMaxIterations),
      (30, 10, 30)
    )
    assertEquals(
      (a.pathMaxDepth, a.pathTopK, a.pathRandomPairs, a.diameterSources, a.diameterMaxDepth),
      (6, 10, 20, 30, 12)
    )
    assertEquals(SparkJobConfig.parse(c.json), Right(c))
    assertEquals(c.fingerprint, SparkJobConfig.parse(c.json).toOption.get.fingerprint)
  }

  test("invalid configurations are rejected with every problem") {
    val c = shipped
    val bad = c.copy(
      fullWindowLabel = "missing",
      graph = c.graph.copy(windows = NamedWindow("Bad Label", 2020, 2019) :: c.windows),
      algorithms = c.algorithms.copy(componentsMaxIterations = 0)
    )
    val errors = SparkJobConfig.validate(bad)
    assert(errors.exists(_.startsWith("fullWindowLabel")), errors.toString)
    assert(errors.exists(_.contains("'Bad Label' must match")), errors.toString)
    assert(errors.exists(_.contains("invalid year range")), errors.toString)
    assert(errors.exists(_.startsWith("componentsMaxIterations")), errors.toString)
    assert(SparkJobConfig.parse("{\"configVersion\": 1}").isLeft)
  }

  test("job arguments are required") {
    assert(
      GraphAnalyticsJob
        .parseArgs(Seq("--hadoop-export", "x"))
        .left
        .exists(_.contains("missing --identity-assignments"))
    )
  }
}
