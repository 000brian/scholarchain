package org.scholarchain.storage.ingest

import java.util.concurrent.{
  Callable,
  ExecutionException,
  ExecutorService,
  Executors,
  ThreadFactory
}
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters._

/** Runs store calls with at most `threads` of them in flight (G-27). With one thread every call
  * runs on the caller's thread. Worker threads are daemon threads, so a pool that is never closed
  * cannot keep the JVM alive.
  *
  * Rules for callers: the function given to [[map]] must be thread-safe (store ports and the
  * synchronized RunLedger are); counters are never updated inside it, only from its results on the
  * calling thread, so counts stay exact.
  */
final class BoundedPool(name: String, val threads: Int) extends AutoCloseable {
  require(
    threads >= 1 && threads <= BoundedPool.MaxThreads,
    s"$name: threads must be in 1..${BoundedPool.MaxThreads}"
  )

  private val exec: Option[ExecutorService] =
    if (threads == 1) None
    else {
      val n = new AtomicInteger()
      val factory: ThreadFactory = (r: Runnable) => {
        val t = new Thread(r, s"$name-${n.incrementAndGet()}")
        t.setDaemon(true)
        t
      }
      Some(Executors.newFixedThreadPool(threads, factory))
    }

  /** `f` applied to every item with at most `threads` calls at once; results in input order. Waits
    * for every call to end, then rethrows the first failure in input order (so no call is left
    * running behind a failure).
    */
  def map[A, B](items: Seq[A])(f: A => B): Vector[B] =
    exec match {
      case Some(e) if items.size > 1 =>
        val tasks = items.map(a => (() => f(a)): Callable[B]).asJava
        e.invokeAll(tasks).asScala.toVector.map { fu =>
          try fu.get()
          catch { case x: ExecutionException if x.getCause != null => throw x.getCause }
        }
      case _ => items.iterator.map(f).toVector
    }

  def close(): Unit = exec.foreach(_.shutdown())
}

object BoundedPool {

  /** Upper bound of any pool here (a configuration mistake must not open hundreds of connections).
    */
  val MaxThreads: Int = 64
}
