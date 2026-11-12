import sbt._

/** Evaluates sbt test outputs for the `markTestRun` gate.
  *
  * A gate passes only when every suite passed. It returns the number of executed tests so that
  * `markTestRun` can refuse to write the marker when nothing ran.
  */
object TestGate {
  def evaluate(label: String, outputs: Seq[Tests.Output], log: Logger): Int = {
    val suites = outputs.flatMap(_.events.toSeq)
    val passed = suites.map(_._2.passedCount).sum
    val failed = suites.map(_._2.failureCount).sum
    val errors = suites.map(_._2.errorCount).sum
    val skipped = suites.map { case (_, r) =>
      r.skippedCount + r.ignoredCount + r.canceledCount + r.pendingCount
    }.sum
    val executed = passed + failed + errors
    val failedSuites = suites.collect { case (name, r) if r.result != TestResult.Passed => name }
    val overallPassed = outputs.forall(_.overall == TestResult.Passed)

    log.info(
      s"$label: suites=${suites.size} executed=$executed passed=$passed failed=$failed errors=$errors skipped=$skipped"
    )
    if (!overallPassed || failed > 0 || errors > 0) {
      failedSuites.sorted.foreach(s => log.error(s"$label failed suite: $s"))
      throw new MessageOnlyException(s"$label failed; .claude/.last-test-run was not updated.")
    }
    executed
  }
}
