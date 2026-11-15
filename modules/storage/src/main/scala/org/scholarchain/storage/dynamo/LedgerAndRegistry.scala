package org.scholarchain.storage.dynamo

import scala.collection.immutable.ListMap

import org.scholarchain.core.RunId
import org.scholarchain.storage.ports.{AttrValue, ConditionalResult, Item, KeyValueStore}

/** Append-only RunLedger for one attempt of one run.
  *
  * The attempt number is claimed with a conditional put (`attempt#<n>#00000000#claim`), starting
  * after the highest existing attempt and moving to n+1 on conflict, so two invocations of the same
  * run never share an attempt and no event is ever overwritten. Events get consecutive sequence
  * numbers within the attempt.
  */
final class RunLedger private (
    store: KeyValueStore,
    table: String,
    val runId: RunId,
    val attempt: Int
) {
  private val writer = new ConditionalWriter(store)
  private var seq = 0L
  private var appended = 0L

  def eventsAppended: Long = synchronized(appended)

  def record(eventType: String, attrs: (String, AttrValue)*): Change = synchronized {
    require(eventType.matches("[a-z][a-z0-9-]*"), s"invalid event type '$eventType'")
    seq += 1
    val item = Item(
      ListMap(
        Item.Pk -> AttrValue.S(TableSpecs.runPk(runId.value)),
        Item.Sk -> AttrValue.S(TableSpecs.ledgerSk(attempt, seq, eventType)),
        "runId" -> AttrValue.S(runId.value),
        "attempt" -> AttrValue.n(attempt.toLong),
        "seq" -> AttrValue.n(seq),
        "eventType" -> AttrValue.S(eventType)
      ) ++ attrs
    )
    val c = writer.append(table, item)
    if (c == Change.Inserted) appended += 1
    c
  }

  /** Events of this attempt, in order (at most `limit`). */
  def events(limit: Int): List[Item] =
    store.query(
      table,
      TableSpecs.runPk(runId.value),
      Some(TableSpecs.attemptPrefix(attempt)),
      limit,
      descending = false
    )
}

object RunLedger {
  val MaxAttempts = 10000

  def open(store: KeyValueStore, table: String, runId: RunId, label: String): RunLedger = {
    val pk = TableSpecs.runPk(runId.value)
    val latest = store
      .query(table, pk, Some("attempt#"), 1, descending = true)
      .headOption
      .map(_.str(Item.Sk).split('#')(1).toInt)
      .getOrElse(0)
    var n = latest + 1
    var claimed = -1
    while (claimed < 0) {
      if (n > MaxAttempts)
        throw new IllegalStateException(s"$runId: more than $MaxAttempts attempts")
      val claim = Item(
        Item.Pk -> AttrValue.S(pk),
        Item.Sk -> AttrValue.S(TableSpecs.ledgerSk(n, 0L, "claim")),
        "runId" -> AttrValue.S(runId.value),
        "attempt" -> AttrValue.n(n.toLong),
        "eventType" -> AttrValue.S("claim"),
        "label" -> AttrValue.S(label)
      )
      store.putIfAbsent(table, ItemHash.stamp(claim, 1L)) match {
        case ConditionalResult.Written         => claimed = n
        case ConditionalResult.ConditionFailed => n += 1
      }
    }
    new RunLedger(store, table, runId, claimed)
  }
}

/** The latest registered version of one artifact. */
final case class ArtifactVersion(version: Long, item: Item)

/** Append-only ArtifactRegistry: every change of an artifact is a new version item; the latest
  * version is the highest sort key. Registration is a conditional put of version n+1, so two
  * writers racing on the same artifact cannot both win.
  */
final class ArtifactRegistry(store: KeyValueStore, table: String) {
  private val writer = new ConditionalWriter(store)

  def latest(kind: String, key: String): Option[ArtifactVersion] =
    store
      .query(table, TableSpecs.artifactPk(kind, key), Some("v#"), 1, descending = true)
      .headOption
      .map(i => ArtifactVersion(i.long("artifactVersion").getOrElse(0L), i))

  def history(kind: String, key: String, limit: Int): List[Item] =
    store.query(table, TableSpecs.artifactPk(kind, key), Some("v#"), limit, descending = false)

  /** Registers version `previous + 1` (previous = 0 for a new artifact). */
  def register(kind: String, key: String, previous: Long, attrs: (String, AttrValue)*): Change = {
    val v = previous + 1
    writer.append(
      table,
      Item(
        ListMap(
          Item.Pk -> AttrValue.S(TableSpecs.artifactPk(kind, key)),
          Item.Sk -> AttrValue.S(TableSpecs.versionSk(v)),
          "artifactKind" -> AttrValue.S(kind),
          "artifactKey" -> AttrValue.S(key),
          "artifactVersion" -> AttrValue.n(v)
        ) ++ attrs
      )
    )
  }
}
