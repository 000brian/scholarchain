package org.scholarchain.services

import java.time.Clock
import java.util.concurrent.{Callable, CompletableFuture, Future, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

/** `/healthz` (liveness, always 200) and `/readyz` (decision 7i): one cheap read per store through
  * the ports, at most [[ServiceLimits.ReadinessDeadlineMillis]] in total (a check that passes the
  * deadline is `fail`). At most one probe runs at a time; requests during a probe, and within
  * [[ServiceLimits.ReadinessCacheMillis]] of the last probe, get the last result. Neither body
  * holds a config value, endpoint, table, bucket, key name or error text.
  *
  * Phase 16 G-23: each failed check writes one `readiness-check-failed` line with the check name
  * and a reason class ([[HealthService.reasonOf]]): an allowlisted graph code from storage
  * (`graph-transient`, `graph-timeout`, ...; the graph check reads through `runClassified`),
  * `store-failure` for any other Left, `deadline-passed` when the 2-second probe deadline ended it,
  * `rejected` when the probe pool refused it, or `unexpected-exception` with the exception's class
  * name. Never a store message. No retry is added here: storage's graph port already retries
  * transient and resource-limit failures inside its own budget, a cold call that passes the
  * 2-second deadline cannot be rescued by a retry inside the same deadline, and the next probe (at
  * most [[ServiceLimits.ReadinessCacheMillis]] later) is the retry a load balancer's
  * consecutive-failure threshold already allows for.
  *
  * @param checks
  *   check name and the read it makes (Right = ok; a Left that is one of storage's
  *   `GraphReadError.Codes` is logged as that code)
  * @param log
  *   where the failure lines go (None: not logged)
  */
final class HealthService(
    role: Role,
    checks: List[(String, () => Either[String, Any])],
    pool: ThreadPoolExecutor,
    clock: Clock,
    log: Option[ServiceLog] = None
) {
  private var last: Option[(Long, ReadinessBody)] = None
  private var inFlight: Option[CompletableFuture[ReadinessBody]] = None
  private val probes = new AtomicInteger(0)

  /** Probes run so far (tests). */
  def probeCount: Int = probes.get

  def healthz(): ApiResponse = ApiResponse.json(200, HealthBody("ok", role.wire))

  def readyz(): ApiResponse = {
    val (mine, waitFor) = synchronized {
      last match {
        case Some((t, b)) if clock.millis() - t < ServiceLimits.ReadinessCacheMillis =>
          return response(b)
        case _ => ()
      }
      inFlight match {
        case Some(f) =>
          last match {
            case Some((_, b)) => return response(b)
            case None         => (false, f)
          }
        case None =>
          val f = new CompletableFuture[ReadinessBody]()
          inFlight = Some(f)
          (true, f)
      }
    }
    if (mine) {
      val body =
        try probe()
        catch { case NonFatal(_) => notReady(checks.map(_._1)) }
      synchronized {
        last = Some(clock.millis() -> body)
        inFlight = None
      }
      waitFor.complete(body)
      response(body)
    } else
      response(
        try waitFor.get(ServiceLimits.ReadinessDeadlineMillis * 2, TimeUnit.MILLISECONDS)
        catch { case NonFatal(_) => notReady(checks.map(_._1)) }
      )
  }

  private def notReady(names: List[String]): ReadinessBody =
    ReadinessBody("not_ready", role.wire, names.map(_ -> "fail").toMap)

  private def response(b: ReadinessBody): ApiResponse =
    ApiResponse.json(if (b.status == "ready") 200 else 503, b)

  private def probe(): ReadinessBody = {
    probes.incrementAndGet()
    val deadline = System.nanoTime() + ServiceLimits.ReadinessDeadlineMillis * 1000000L
    // each task gives None (ok) or the failure's reason fields
    val futures: List[(String, Option[Future[Option[List[(String, ujson.Value)]]]])] =
      checks.map { case (name, f) =>
        val task: Callable[Option[List[(String, ujson.Value)]]] = () =>
          try
            f() match {
              case Right(_) => None
              case Left(e)  => Some(List("reason" -> ujson.Str(HealthService.reasonOf(e))))
            }
          catch {
            case NonFatal(e) =>
              Some(
                List(
                  "reason" -> ujson.Str(LogCause.UnexpectedException),
                  "exception" -> ujson.Str(LogCause.className(e))
                )
              )
          }
        val fut =
          try Some(pool.submit(task))
          catch { case NonFatal(_) => None }
        name -> fut
      }
    val outcomes: List[(String, Option[List[(String, ujson.Value)]])] = futures.map {
      case (name, None)      => name -> Some(List("reason" -> ujson.Str(HealthService.Rejected)))
      case (name, Some(fut)) =>
        val remaining = math.max(0L, deadline - System.nanoTime())
        val failure =
          try fut.get(remaining, TimeUnit.NANOSECONDS)
          catch {
            case NonFatal(_) =>
              fut.cancel(true)
              Some(List("reason" -> ujson.Str(HealthService.DeadlinePassed)))
          }
        name -> failure
    }
    outcomes.foreach {
      case (name, Some(fields)) =>
        log.foreach(_.event("readiness-check-failed", None, ("check" -> ujson.Str(name)) :: fields))
      case _ => ()
    }
    val results = outcomes.map { case (n, failure) => n -> failure.isEmpty }
    val all = results.forall(_._2)
    ReadinessBody(
      if (all) "ready" else "not_ready",
      role.wire,
      results.map { case (n, ok) => n -> (if (ok) "ok" else "fail") }.toMap
    )
  }
}

object HealthService {
  val DeadlinePassed = "deadline-passed"
  val Rejected = "rejected"

  /** The reason class of a failed check's Left: one of storage's allowlisted graph codes as it is,
    * anything else `store-failure` (the text itself is never logged).
    */
  def reasonOf(left: String): String =
    if (org.scholarchain.storage.ports.GraphReadError.Codes.contains(left)) left
    else LogCause.StoreFailure
}
