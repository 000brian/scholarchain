package org.scholarchain.storage.emulator

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException,
  SdkServiceException
}

/** The five outcome classes of decision 6.6 (the same words as `scripts/emulator/lib.sh`). A test
  * fails on [[ImplementedFailed]] and [[InfrastructureFailure]]; it passes on [[NotImplemented]]
  * and [[NotExercised]] as a recorded compatibility gap, never as validated functionality.
  */
object EmulatorOutcome {
  val ImplementedPassed = "implemented-passed"
  val ImplementedFailed = "implemented-failed"
  val NotImplemented = "not-implemented"
  val NotExercised = "not-exercised"
  val InfrastructureFailure = "infrastructure-failure"

  val Classes: List[String] =
    List(ImplementedPassed, ImplementedFailed, NotImplemented, NotExercised, InfrastructureFailure)

  /** The classes that fail a test. */
  val Failing: Set[String] = Set(ImplementedFailed, InfrastructureFailure)
}

/** One result line: `service`, `operation`, `class`, `httpStatus`, `latencyMillis` first (the fixed
  * interface), then additive optional fields from a fixed vocabulary (`step`, `errorCode`,
  * `observation`, `rule`, `calls`). Never a host, account, ARN, queue URL, key or store message.
  *
  * @param httpStatus
  *   501 for NotImplemented; the HTTP status of a service error; None (JSON null) for an answered
  *   call, as the scripts write (the adapters do not expose the status of a success)
  */
final case class EmulatorResultLine(
    service: String,
    operation: String,
    outcome: String,
    httpStatus: Option[Int],
    latencyMillis: Long,
    extra: ListMap[String, String] = ListMap.empty
) {
  require(EmulatorOutcome.Classes.contains(outcome), s"unknown outcome class $outcome")
  require(
    extra.keySet.subsetOf(EmulatorResultLine.ExtraFields),
    s"unknown field ${extra.keySet -- EmulatorResultLine.ExtraFields}"
  )

  def render: String = {
    val head: List[(String, ujson.Value)] = List(
      "service" -> ujson.Str(service),
      "operation" -> ujson.Str(operation),
      "class" -> ujson.Str(outcome),
      "httpStatus" -> httpStatus.map(s => ujson.Num(s.toDouble)).getOrElse(ujson.Null),
      "latencyMillis" -> ujson.Num(latencyMillis.toDouble)
    )
    ujson.Obj.from(head ++ extra.toList.map { case (k, v) => k -> ujson.Str(v) }).render()
  }
}

object EmulatorResultLine {

  /** The additive fields a Scala suite may write. */
  val ExtraFields: Set[String] = Set("step", "errorCode", "observation", "rule", "calls")

  /** Error codes and observations are kept only when they are short fixed-vocabulary words (an AWS
    * error code or a class name); anything else becomes `other`.
    */
  def safeWord(s: String): String =
    Option(s).filter(_.matches("[A-Za-z0-9._:() -]{1,80}")).getOrElse("other")

  /** The preflight line of a refused run (decision 6.6). */
  def preflight(rule: String): EmulatorResultLine =
    EmulatorResultLine(
      "preflight",
      "preflight",
      EmulatorOutcome.InfrastructureFailure,
      None,
      0L,
      ListMap("rule" -> safeWord(rule))
    )
}

/** Writes the result lines of one suite to `results-SUITE.jsonl` in the results folder, and the
  * demonstration line to `demonstration-SUITE.txt`, only when a results folder is given (never
  * otherwise). One file per suite, so the storage and services forked JVMs never write the same
  * file. The file is created new: an existing one (an earlier run) is refused, never appended to.
  *
  * Thread-safe (synchronized appends).
  */
final class EmulatorResultWriter private (file: Option[Path], demoFile: Option[Path]) {

  def write(line: EmulatorResultLine): Unit = synchronized {
    file.foreach { f =>
      Files.write(
        f,
        (line.render + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.APPEND
      )
    }
    ()
  }

  /** Prints the demonstration line and writes it (when a results folder is set). */
  def demonstration(line: String): Unit = synchronized {
    require(line.matches("demonstration [a-z-]+: (demonstrated|not demonstrated \\(.{1,200}\\))"))
    println(line)
    demoFile.foreach { f =>
      Files.write(
        f,
        (line + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    }
    ()
  }

  def path: Option[Path] = file
}

object EmulatorResultWriter {

  val SuiteNamePattern: String = "[A-Za-z0-9]{1,80}"

  def fileName(suite: String): String = s"results-$suite.jsonl"
  def demonstrationFileName(suite: String): String = s"demonstration-$suite.txt"

  /** A writer that writes nothing (no results folder set). */
  val none: EmulatorResultWriter = new EmulatorResultWriter(None, None)

  /** Creates `results-SUITE.jsonl` in `dir` (Left `results-file-exists` when it already exists, or
    * the I/O failure's class name). None writes nothing.
    */
  def open(dir: Option[Path], suite: String): Either[String, EmulatorResultWriter] = {
    require(suite.matches(SuiteNamePattern), "suite name")
    dir match {
      case None    => Right(none)
      case Some(d) =>
        val f = d.resolve(fileName(suite))
        try {
          Files.write(
            f,
            Array.emptyByteArray,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
          )
          Right(new EmulatorResultWriter(Some(f), Some(d.resolve(demonstrationFileName(suite)))))
        } catch {
          case _: FileAlreadyExistsException => Left(EmulatorSafety.SuiteRules.ResultsFileExists)
          case NonFatal(e)                   => Left(e.getClass.getSimpleName)
        }
    }
  }

  /** The preflight line of a refused run, written only when `dir` is a usable folder and the suite
    * file does not exist yet. Returns whether it was written.
    */
  def writePreflight(dir: Option[Path], suite: String, rule: String): Boolean =
    open(dir, suite) match {
      case Right(w) if w.path.nonEmpty => w.write(EmulatorResultLine.preflight(rule)); true
      case _                           => false
    }
}

/** How a failed operation is classed. */
object EmulatorFailure {

  /** (class, httpStatus, errorCode) of a failure, from its cause chain (at most 8 deep):
    *   - a service error with HTTP 501 or error code `NotImplemented`: not-implemented, 501;
    *   - any other service error (the emulator answered): implemented-failed, its status and AWS
    *     error code;
    *   - a client error with no service error behind it (no answer, TLS, timeout, connection):
    *     infrastructure-failure, code `tls`, `timeout`, `connection` or `client`;
    *   - anything else (a wrong result, an assertion): implemented-failed, its class name.
    */
  def classify(t: Throwable): (String, Option[Int], String) = {
    val chain = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(8).toList
    chain.collectFirst { case s: SdkServiceException => s } match {
      case Some(s) =>
        val code = s match {
          case a: AwsServiceException if a.awsErrorDetails() != null =>
            Option(a.awsErrorDetails().errorCode()).getOrElse(s.getClass.getSimpleName)
          case _ => s.getClass.getSimpleName
        }
        if (s.statusCode == 501 || code == "NotImplemented")
          (EmulatorOutcome.NotImplemented, Some(501), "NotImplemented")
        else
          (
            EmulatorOutcome.ImplementedFailed,
            Some(s.statusCode).filter(_ > 0),
            EmulatorResultLine.safeWord(code)
          )
      case None if chain.exists(_.isInstanceOf[SdkClientException]) =>
        val code =
          if (chain.exists(_.isInstanceOf[javax.net.ssl.SSLException])) "tls"
          else if (
            chain.exists(c =>
              c.isInstanceOf[ApiCallTimeoutException] ||
                c.isInstanceOf[ApiCallAttemptTimeoutException] ||
                c.isInstanceOf[java.net.SocketTimeoutException]
            )
          ) "timeout"
          else if (chain.exists(_.isInstanceOf[IOException])) "connection"
          else "client"
        (EmulatorOutcome.InfrastructureFailure, None, code)
      case None =>
        (
          EmulatorOutcome.ImplementedFailed,
          None,
          EmulatorResultLine.safeWord(t.getClass.getSimpleName)
        )
    }
  }
}

/** Records the outcome of each operation of one test and writes its line. Thread-safe.
  *
  * [[call]] runs one adapter or SDK call: success and an `ok` result give implemented-passed; a
  * result `ok` refuses gives implemented-failed (`unexpected-result`); an exception is classed by
  * [[EmulatorFailure.classify]]. [[skip]] records not-exercised with the operation it needed.
  */
final class EmulatorRecorder(writer: EmulatorResultWriter, step: String) {
  private val lines = scala.collection.mutable.ListBuffer.empty[EmulatorResultLine]

  def recorded: List[EmulatorResultLine] = synchronized(lines.toList)

  def record(line: EmulatorResultLine): Unit = synchronized {
    lines += line
    writer.write(line)
  }

  private def extras(more: (String, String)*): ListMap[String, String] =
    ListMap(("step" -> step) +: more.filter(_._2.nonEmpty)*)

  def call[A](service: String, operation: String, observation: A => String = (_: A) => "")(
      f: => A
  )(ok: A => Boolean = (_: A) => true): Either[String, A] = {
    val t0 = System.nanoTime()
    val r =
      try Right(f)
      catch { case NonFatal(e) => Left(e) }
    val ms = (System.nanoTime() - t0) / 1000000L
    r match {
      case Right(a) if ok(a) =>
        record(
          EmulatorResultLine(
            service,
            operation,
            EmulatorOutcome.ImplementedPassed,
            None,
            ms,
            extras("observation" -> {
              val o = observation(a)
              if (o.isEmpty) "" else EmulatorResultLine.safeWord(o)
            })
          )
        )
        Right(a)
      case Right(a) =>
        record(
          EmulatorResultLine(
            service,
            operation,
            EmulatorOutcome.ImplementedFailed,
            None,
            ms,
            extras("errorCode" -> "unexpected-result")
          )
        )
        Left(EmulatorOutcome.ImplementedFailed)
      case Left(e) =>
        val (cls, status, code) = EmulatorFailure.classify(e)
        record(EmulatorResultLine(service, operation, cls, status, ms, extras("errorCode" -> code)))
        Left(cls)
    }
  }

  /** A line with a class decided by the caller (the resource-policy probe records an answered
    * refusal as observed, not failed).
    */
  def observed(
      service: String,
      operation: String,
      outcome: String,
      httpStatus: Option[Int],
      latencyMillis: Long,
      observation: String,
      errorCode: String = ""
  ): Unit =
    record(
      EmulatorResultLine(
        service,
        operation,
        outcome,
        httpStatus,
        latencyMillis,
        extras(
          "observation" -> (if (observation.isEmpty) ""
                            else EmulatorResultLine.safeWord(observation)),
          "errorCode" -> (if (errorCode.isEmpty) "" else EmulatorResultLine.safeWord(errorCode))
        )
      )
    )

  def skip(service: String, operation: String, needs: String): Unit =
    record(
      EmulatorResultLine(
        service,
        operation,
        EmulatorOutcome.NotExercised,
        None,
        0L,
        extras("observation" -> EmulatorResultLine.safeWord(s"needs $needs"))
      )
    )

  /** The lines of this recorder that fail a test (implemented-failed, infrastructure-failure). */
  def failures: List[EmulatorResultLine] =
    recorded.filter(l => EmulatorOutcome.Failing.contains(l.outcome))

  /** Every line implemented-passed (the demonstration rule). */
  def allPassed: Boolean =
    recorded.nonEmpty && recorded.forall(_.outcome == EmulatorOutcome.ImplementedPassed)

  /** A failure message naming service, operation, class and code only. */
  def failureSummary: String =
    failures
      .map(l => s"${l.service}.${l.operation}=${l.outcome}(${l.extra.getOrElse("errorCode", "")})")
      .mkString(", ")
}

/** A chain of dependent operations: once one does not pass, every later one is recorded
  * not-exercised (naming the operation it needed) and not run.
  */
final class EmulatorChain(rec: EmulatorRecorder) {
  private var broken: Option[String] = None

  def isBroken: Boolean = broken.nonEmpty

  def apply[A](service: String, operation: String, observation: A => String = (_: A) => "")(
      f: => A
  )(ok: A => Boolean = (_: A) => true): Option[A] =
    broken match {
      case Some(b) => rec.skip(service, operation, b); None
      case None    =>
        rec.call(service, operation, observation)(f)(ok) match {
          case Right(a) => Some(a)
          case Left(_)  => broken = Some(s"$service.$operation"); None
        }
    }

  /** Marks the chain broken by a step that is not an SDK call (for example a missing input). */
  def breakAt(what: String): Unit = if (broken.isEmpty) broken = Some(what)
}
