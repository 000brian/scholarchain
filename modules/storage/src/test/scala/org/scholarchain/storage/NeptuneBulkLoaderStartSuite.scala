package org.scholarchain.storage

import scala.jdk.CollectionConverters._

import org.scholarchain.storage.aws.NeptuneBulkLoader
import org.scholarchain.storage.ports.{BulkLoadJob, BulkLoadStartRejected}
import software.amazon.awssdk.core.exception.{ApiCallTimeoutException, SdkServiceException}
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model
import software.amazon.awssdk.services.neptunedata.model.{
  AccessDeniedException,
  BadRequestException,
  BulkLoadIdNotFoundException,
  ClientTimeoutException,
  ConstraintViolationException,
  InternalFailureException,
  InvalidArgumentException,
  InvalidParameterException,
  LoadUrlAccessDeniedException,
  MissingParameterException,
  PreconditionsFailedException,
  S3Exception,
  StartLoaderJobRequest,
  StartLoaderJobResponse,
  ThrottlingException,
  TooManyRequestsException
}

/** Phase 14 C-07: which `StartLoaderJob` errors the Neptune adapter reports as a definite refusal
  * ([[BulkLoadStartRejected]]) and which stay an unknown outcome. Stub SDK client, no network.
  */
class NeptuneBulkLoaderStartSuite extends munit.FunSuite {

  private final class Stub(fail: Option[Throwable]) extends NeptunedataClient {
    def serviceName(): String = "neptune-db"
    def close(): Unit = ()
    override def startLoaderJob(r: StartLoaderJobRequest): StartLoaderJobResponse =
      fail match {
        case Some(e) => throw e
        case None    =>
          StartLoaderJobResponse
            .builder()
            .status("200")
            .payload(Map("loadId" -> "l-1").asJava)
            .build()
      }
  }

  private val job = BulkLoadJob(
    "s3://example-graph-bucket/graph/run=x/neptune/nodes/",
    "opencypher",
    "arn:aws:iam::123456789012:role/example-neptune-loader",
    "us-east-1",
    failOnError = true,
    parallelism = "MEDIUM",
    queueRequest = true,
    userProvidedEdgeIds = true,
    dependencies = Nil
  )

  private def start(e: Throwable): Either[Throwable, String] =
    try Right(new NeptuneBulkLoader(new Stub(Some(e))).start(job))
    catch { case t: Throwable => Left(t) }

  private val definite: List[(String, SdkServiceException)] = List(
    "BadRequestException" -> BadRequestException.builder().message("m").statusCode(400).build(),
    "BulkLoadIdNotFoundException" ->
      BulkLoadIdNotFoundException.builder().message("m").statusCode(404).build(),
    "ConstraintViolationException" ->
      ConstraintViolationException.builder().message("m").statusCode(400).build(),
    "IllegalArgumentException" ->
      model.IllegalArgumentException.builder().message("m").statusCode(400).build(),
    "InvalidArgumentException" ->
      InvalidArgumentException.builder().message("m").statusCode(400).build(),
    "InvalidParameterException" ->
      InvalidParameterException.builder().message("m").statusCode(400).build(),
    "LoadUrlAccessDeniedException" ->
      LoadUrlAccessDeniedException.builder().message("m").statusCode(400).build(),
    "MissingParameterException" ->
      MissingParameterException.builder().message("m").statusCode(400).build(),
    "PreconditionsFailedException" ->
      PreconditionsFailedException.builder().message("m").statusCode(400).build(),
    "S3Exception" -> S3Exception.builder().message("m").statusCode(400).build(),
    "UnsupportedOperationException" ->
      model.UnsupportedOperationException.builder().message("m").statusCode(400).build(),
    "AccessDeniedException" -> AccessDeniedException.builder().message("m").statusCode(403).build()
  )

  test("documented 4xx StartLoaderJob errors are definite refusals with their class name") {
    definite.foreach { case (name, e) =>
      start(e) match {
        case Left(r: BulkLoadStartRejected) =>
          assertEquals(r.errorClass, name)
          assertEquals(r.httpStatus, e.statusCode)
        case other => fail(s"$name: expected BulkLoadStartRejected, got $other")
      }
    }
  }

  test("Phase 16: throttling answers are throttled refusals (no load created), with their class") {
    val throttled: List[(String, SdkServiceException)] = List(
      "TooManyRequestsException" ->
        TooManyRequestsException.builder().message("m").statusCode(429).build(),
      "ThrottlingException" -> ThrottlingException.builder().message("m").statusCode(400).build(),
      "ThrottlingException" -> ThrottlingException.builder().message("m").statusCode(429).build()
    )
    throttled.foreach { case (name, e) =>
      start(e) match {
        case Left(r: BulkLoadStartRejected) =>
          assertEquals((r.errorClass, r.httpStatus, r.throttled), (name, e.statusCode, true))
        case other => fail(s"$name: expected a throttled BulkLoadStartRejected, got $other")
      }
    }
    // definite refusals are not throttled
    start(BadRequestException.builder().message("m").statusCode(400).build()) match {
      case Left(r: BulkLoadStartRejected) => assert(!r.throttled)
      case other                          => fail(s"expected BulkLoadStartRejected, got $other")
    }
  }

  test("timeouts, 5xx and client errors are unknown outcomes, never a refusal") {
    val unknown: List[Throwable] = List(
      ClientTimeoutException.builder().message("m").statusCode(408).build(),
      InternalFailureException.builder().message("m").statusCode(500).build(),
      // A definite or throttling class with a status that is not its documented one stays unknown.
      BadRequestException.builder().message("m").statusCode(503).build(),
      TooManyRequestsException.builder().message("m").statusCode(503).build(),
      ThrottlingException.builder().message("m").statusCode(503).build(),
      ApiCallTimeoutException.builder().message("m").build()
    )
    unknown.foreach { e =>
      start(e) match {
        case Left(_: BulkLoadStartRejected) =>
          fail(s"${e.getClass.getSimpleName} must stay unknown")
        case Left(_: StoreException) => ()
        case other                   => fail(s"expected StoreException, got $other")
      }
    }
  }

  test("a successful start returns the load ID") {
    assertEquals(new NeptuneBulkLoader(new Stub(None)).start(job), "l-1")
  }
}
