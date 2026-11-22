package org.scholarchain.services

import org.scholarchain.storage.StoreException
import org.scholarchain.storage.ports.{GraphQuery, GraphReadError, GraphReadPort, GraphResult}

/** Graph reads of the graph routes through the graph circuit breaker (Phase 16, FECD-1 breaker
  * side, step D's B3, B4 and B5).
  *
  *   - The port is called through `runClassified` (storage's typed error class, after storage's
  *     bounded graph-read retry); a thrown [[StoreException]] becomes `graph-transient` or
  *     `graph-permanent` by its own flag.
  *   - Only availability failures count against the breaker ([[counts]]): `graph-transient`,
  *     `graph-resource-limit`, `graph-timeout`, and `graph-unclassified` (a port that does not
  *     classify; counted as before Phase 16). `graph-invalid-query` (refused before any store call)
  *     and `graph-permanent` (a 4xx: the store answered) give their slot back without an outcome.
  *     Every failure still answers 502 `DOWNSTREAM_FAILURE` (the error model is unchanged); an open
  *     breaker answers 503 `CIRCUIT_OPEN`.
  *   - Each failure writes one `graph-read-failed` line with the template ID, the allowlisted code,
  *     whether it counted, and the breaker state after it; never the store's message.
  *
  * Services hold no graph logic here: the template is built by the route, the query runs in
  * storage.
  */
object GraphReads {

  /** Whether a classified failure counts against the breaker (availability failures only). */
  def counts(e: GraphReadError): Boolean = e match {
    case _: GraphReadError.Transient     => true
    case _: GraphReadError.ResourceLimit => true
    case _: GraphReadError.Timeout       => true
    case _: GraphReadError.Unclassified  => true
    case _: GraphReadError.Invalid       => false
    case _: GraphReadError.Permanent     => false
  }

  /** `port.runClassified(q)`, with a thrown [[StoreException]] turned into its class. */
  def classified(port: GraphReadPort, q: GraphQuery): Either[GraphReadError, GraphResult] =
    try port.runClassified(q)
    catch {
      case e: StoreException =>
        Left(
          if (e.transient) GraphReadError.Transient(e.getMessage)
          else GraphReadError.Permanent(e.getMessage)
        )
    }

  /** One graph read through `breaker`. Any other thrown exception is recorded as a failure and
    * rethrown (the adapter answers 500 INTERNAL).
    */
  def run(breaker: CircuitBreaker, port: GraphReadPort, log: ServiceLog)(
      q: GraphQuery
  ): Either[ApiError, GraphResult] =
    breaker.acquire() match {
      case None       => Left(ApiError.retryable(ErrorCode.CIRCUIT_OPEN))
      case Some(slot) =>
        val r =
          try classified(port, q)
          catch {
            case e: Throwable =>
              breaker.recordFailure(slot); throw e
          }
        r match {
          case Right(result) =>
            breaker.recordSuccess(slot); Right(result)
          case Left(err) =>
            val counted = counts(err)
            if (counted) breaker.recordFailure(slot) else breaker.cancel(slot)
            log.event(
              "graph-read-failed",
              None,
              List(
                "template" -> ujson.Str(q.templateId),
                "code" -> ujson.Str(err.code),
                "counted" -> ujson.Bool(counted),
                "breaker" -> ujson.Str(breaker.state)
              )
            )
            Left(ApiError.downstream)
        }
    }
}
