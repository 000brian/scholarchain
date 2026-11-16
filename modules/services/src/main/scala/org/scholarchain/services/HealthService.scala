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
  * @param checks
  *   check name and the read it makes (Right = ok)
  */
final class HealthService(
    role: Role,
    checks: List[(String, () => Either[String, Any])],
    pool: ThreadPoolExecutor,
    clock: Clock
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
    val futures: List[(String, Option[Future[Boolean]])] = checks.map { case (name, f) =>
      val task: Callable[Boolean] = () => scala.util.Try(f()).toOption.exists(_.isRight)
      val fut =
        try Some(pool.submit(task))
        catch { case NonFatal(_) => None }
      name -> fut
    }
    val results = futures.map {
      case (name, None)      => name -> false
      case (name, Some(fut)) =>
        val remaining = math.max(0L, deadline - System.nanoTime())
        val ok =
          try fut.get(remaining, TimeUnit.NANOSECONDS)
          catch {
            case NonFatal(_) =>
              fut.cancel(true); false
          }
        name -> ok
    }
    val all = results.forall(_._2)
    ReadinessBody(
      if (all) "ready" else "not_ready",
      role.wire,
      results.map { case (n, ok) => n -> (if (ok) "ok" else "fail") }.toMap
    )
  }
}
