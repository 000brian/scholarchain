package org.scholarchain.services

/** One error code of the REST error model (decision 7i): its HTTP status, whether a client may
  * retry the same request, and the fixed message sent with it. Downstream error text never reaches
  * a client; only these messages do.
  */
sealed abstract class ErrorCode(val status: Int, val retryable: Boolean, val message: String) {
  def name: String = toString
}

object ErrorCode {
  // 400
  case object INVALID_JSON extends ErrorCode(400, false, "The request body is not valid JSON.")
  case object INVALID_REQUEST
      extends ErrorCode(400, false, "The request is invalid; see details for each field.")
  case object WINDOW_REQUIRED
      extends ErrorCode(
        400,
        false,
        "This evidence ID is stored in several windows; name one with the window parameter."
      )
  case object INVALID_CURSOR
      extends ErrorCode(400, false, "The cursor is not valid for this request.")
  // 401, 403
  case object UNAUTHENTICATED
      extends ErrorCode(401, false, "A valid API key is required in the X-Api-Key header.")
  case object FORBIDDEN
      extends ErrorCode(403, false, "This API key is not allowed to call this route.")
  // 404
  case object ROUTE_NOT_FOUND extends ErrorCode(404, false, "No such route.")
  case object QUERY_NOT_FOUND extends ErrorCode(404, false, "No audit exists for this query ID.")
  case object JOB_NOT_FOUND extends ErrorCode(404, false, "No such job.")
  case object NODE_NOT_FOUND extends ErrorCode(404, false, "No such graph node.")
  case object EVIDENCE_NOT_FOUND extends ErrorCode(404, false, "No such evidence item.")
  // 405, 413, 415
  case object METHOD_NOT_ALLOWED
      extends ErrorCode(405, false, "This method is not allowed on this route.")
  case object PAYLOAD_TOO_LARGE
      extends ErrorCode(413, false, "The request body exceeds the configured size limit.")
  case object UNSUPPORTED_MEDIA_TYPE
      extends ErrorCode(415, false, "The request body must be application/json.")
  // 409
  case object IDEMPOTENCY_CONFLICT
      extends ErrorCode(
        409,
        false,
        "This Idempotency-Key was used with a different request."
      )
  case object IDEMPOTENCY_IN_PROGRESS
      extends ErrorCode(
        409,
        true,
        "A request with this Idempotency-Key is still in progress; retry later."
      )
  case object JOB_NOT_READY
      extends ErrorCode(409, true, "The job has not finished yet; retry later.")
  case object JOB_FAILED
      extends ErrorCode(409, false, "The job failed; see details for its error code.")
  // 422
  case object UNSUPPORTED_CONSTRAINT
      extends ErrorCode(422, false, "A constraint of this request is not supported.")
  case object UNSUPPORTED_EDGE_TYPE
      extends ErrorCode(422, false, "This edge type is not supported for this node.")
  case object UNSUPPORTED_DEPTH
      extends ErrorCode(422, false, "Only neighbourhoods of depth 1 are supported.")
  case object UNSUPPORTED_NODE_KIND
      extends ErrorCode(422, false, "Only researcher and work nodes are supported.")
  // 429
  case object RATE_LIMITED extends ErrorCode(429, true, "Too many requests; retry later.")
  // 500
  case object INTERNAL
      extends ErrorCode(500, false, "An unexpected internal error occurred; quote the trace ID.")
  case object RESULT_TOO_LARGE
      extends ErrorCode(500, false, "The completed result exceeds the stored result size limit.")
  // 502, 503, 504
  case object DOWNSTREAM_FAILURE
      extends ErrorCode(502, true, "A downstream store or service failed.")
  case object OVERLOADED extends ErrorCode(503, true, "Every query slot is busy; retry later.")
  case object CIRCUIT_OPEN
      extends ErrorCode(503, true, "A downstream dependency is failing; retry later.")
  case object WORKFLOW_TIMEOUT
      extends ErrorCode(504, true, "The query did not finish within the synchronous deadline.")

  /** Phase 16 (route time limits): an evidence or explain read passed its time limit. */
  case object READ_TIMEOUT
      extends ErrorCode(504, true, "The read did not finish within the route time limit.")

  val all: List[ErrorCode] = List(
    INVALID_JSON,
    INVALID_REQUEST,
    WINDOW_REQUIRED,
    INVALID_CURSOR,
    UNAUTHENTICATED,
    FORBIDDEN,
    ROUTE_NOT_FOUND,
    QUERY_NOT_FOUND,
    JOB_NOT_FOUND,
    NODE_NOT_FOUND,
    EVIDENCE_NOT_FOUND,
    METHOD_NOT_ALLOWED,
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_MEDIA_TYPE,
    IDEMPOTENCY_CONFLICT,
    IDEMPOTENCY_IN_PROGRESS,
    JOB_NOT_READY,
    JOB_FAILED,
    UNSUPPORTED_CONSTRAINT,
    UNSUPPORTED_EDGE_TYPE,
    UNSUPPORTED_DEPTH,
    UNSUPPORTED_NODE_KIND,
    RATE_LIMITED,
    INTERNAL,
    RESULT_TOO_LARGE,
    DOWNSTREAM_FAILURE,
    OVERLOADED,
    CIRCUIT_OPEN,
    WORKFLOW_TIMEOUT,
    READ_TIMEOUT
  )
}

/** An error answer: its code, machine-readable details (never downstream text), and a Retry-After
  * value in seconds for retryable throttling answers.
  */
final case class ApiError(
    code: ErrorCode,
    details: ujson.Value = ujson.Obj(),
    retryAfterSeconds: Option[Int] = None,
    extraHeaders: List[(String, String)] = Nil
)

object ApiError {

  /** 400 INVALID_REQUEST with one `{field, problem}` entry per problem. */
  def invalid(problems: List[(String, String)]): ApiError =
    ApiError(ErrorCode.INVALID_REQUEST, fieldErrors(problems))

  def invalid(field: String, problem: String): ApiError = invalid(List(field -> problem))

  def fieldErrors(problems: List[(String, String)]): ujson.Obj =
    ujson.Obj("fieldErrors" -> ujson.Arr.from(problems.map { case (f, p) =>
      ujson.Obj("field" -> f, "problem" -> p)
    }))

  def retryable(code: ErrorCode, details: ujson.Value = ujson.Obj()): ApiError =
    ApiError(code, details, Some(ServiceLimits.RetryAfterSeconds))

  val downstream: ApiError = ApiError(ErrorCode.DOWNSTREAM_FAILURE)
  val internal: ApiError = ApiError(ErrorCode.INTERNAL)
}

/** Job error codes stored on jobs and shown in `details.jobError` (decision 7i). */
object JobErrorCodes {
  val Superseded = "SUPERSEDED"
  val LeaseExpired = "LEASE_EXPIRED"
  val AttemptsExhausted = "ATTEMPTS_EXHAUSTED"
  val ResultTooLarge = "RESULT_TOO_LARGE"
  val WorkflowFailed = "WORKFLOW_FAILED"

  /** The fixed message stored with each code (never downstream text). */
  val messages: Map[String, String] = Map(
    Superseded -> "A concurrent request with the same Idempotency-Key ran this query.",
    LeaseExpired -> "The process running this query stopped before it finished.",
    AttemptsExhausted -> "The query failed on every allowed attempt.",
    ResultTooLarge -> "The completed result exceeds the stored result size limit.",
    WorkflowFailed -> "The runtime rejected this query."
  )

  def error(code: String): org.scholarchain.storage.jobs.JobError =
    org.scholarchain.storage.jobs.JobError(code, messages(code))
}
