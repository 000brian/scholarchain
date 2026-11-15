package org.scholarchain.storage.local

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.scholarchain.storage.ports.{ReceivedWork, WorkItem, WorkQueue}

/** The manifest-driven work queue for local runs. State is an append-only journal (JSON lines:
  * `enqueue`, `ack`, `poison`) replayed on open, so a rerun over the same journal skips finished
  * items and enqueuing the same plan twice adds nothing.
  *
  *   - `receive` hands out pending items in enqueue order and hides them until `ack` or `release`.
  *   - An item received more than `maxReceives` times is journaled as poison and not handed out
  *     again (reported through `poisoned`).
  */
final class ManifestWorkQueue(journal: Path, maxReceives: Int)
    extends WorkQueue
    with AutoCloseable {
  require(maxReceives >= 1, "maxReceives must be >= 1")
  private val items = mutable.LinkedHashMap.empty[String, WorkItem]
  private val done = mutable.Set.empty[String]
  private val poison = mutable.LinkedHashSet.empty[String]
  private val inFlight = mutable.Set.empty[String]
  private val receives = mutable.HashMap.empty[String, Int]

  if (Files.exists(journal))
    Files.readAllLines(journal, StandardCharsets.UTF_8).asScala.filter(_.nonEmpty).foreach { l =>
      val o = ujson.read(l).obj
      val id = o("id").str
      o("op").str match {
        case "enqueue" => items.getOrElseUpdate(id, WorkItem(id, o("payload").str))
        case "ack"     => done += id
        case "poison"  => poison += id
        case other     => throw new IllegalStateException(s"unknown journal op '$other'")
      }
    }
  Files.createDirectories(journal.getParent)
  private val out = new BufferedWriter(
    new OutputStreamWriter(
      Files.newOutputStream(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
      StandardCharsets.UTF_8
    )
  )

  private def append(o: ujson.Obj): Unit = { out.write(o.render()); out.write('\n'); out.flush() }

  def enqueue(newItems: Seq[WorkItem]): Int = synchronized {
    var added = 0
    newItems.foreach { i =>
      items.get(i.id) match {
        case Some(existing) if existing.payload != i.payload =>
          throw new IllegalStateException(
            s"work item ${i.id} already enqueued with a different payload"
          )
        case Some(_) => ()
        case None    =>
          items.update(i.id, i)
          append(ujson.Obj("op" -> "enqueue", "id" -> i.id, "payload" -> i.payload))
          added += 1
      }
    }
    added
  }

  def receive(max: Int): List[ReceivedWork] = synchronized {
    val out = mutable.ListBuffer.empty[ReceivedWork]
    val it = items.valuesIterator
    while (out.size < max && it.hasNext) {
      val i = it.next()
      if (!done(i.id) && !poison(i.id) && !inFlight(i.id)) {
        val n = receives.getOrElse(i.id, 0) + 1
        receives.update(i.id, n)
        if (n > maxReceives) {
          poison += i.id
          append(ujson.Obj("op" -> "poison", "id" -> i.id))
        } else {
          inFlight += i.id
          out += ReceivedWork(i, i.id, n)
        }
      }
    }
    out.toList
  }

  def ack(work: ReceivedWork): Unit = synchronized {
    if (inFlight.remove(work.receipt)) {
      done += work.receipt
      append(ujson.Obj("op" -> "ack", "id" -> work.receipt))
    }
  }

  def release(work: ReceivedWork): Unit = synchronized { inFlight.remove(work.receipt); () }

  def pendingCount: Int = synchronized(items.keysIterator.count(id => !done(id) && !poison(id)))
  def doneCount: Int = synchronized(done.size)
  def poisoned: List[String] = synchronized(poison.toList)
  def close(): Unit = synchronized(out.close())
}
