package org.scholarchain.storage.jobs

import scala.annotation.tailrec
import scala.collection.immutable.ListMap

import org.scholarchain.storage.dynamo.TableSpecs
import org.scholarchain.storage.ports._
import org.scholarchain.storage.runtime.RuntimeStores

/** QS5: idempotency records in the QuerySession table, keyed by the sha256 of (caller scope,
  * Idempotency-Key). A record holds the request fingerprint, the job ID and its TTL (`expiresAt`,
  * epoch seconds) plus the `version` used to replace an expired record; never state or a result.
  *
  * `Left` is a malformed argument or a store failure (after storage's bounded retries).
  */
trait IdempotencyStore {

  /** Put-if-absent of (fingerprint, jobId, expiresAt) under (scope, key).
    *
    *   - `Claimed(record)`: this caller holds the key;
    *   - `Existing(record)`: another request holds it; `record` is the stored one, unchanged.
    *
    * A stored record whose TTL has passed at `nowMillis` (DynamoDB removes expired items lazily) is
    * treated as absent and replaced under a version condition.
    */
  def claim(
      scope: String,
      idempotencyKey: String,
      fingerprint: String,
      jobId: String,
      expiresAtSeconds: Long,
      nowMillis: Long
  ): Either[String, IdempotencyClaim]

  /** The live record under (scope, key), or None when absent or expired at `nowMillis`. */
  def get(
      scope: String,
      idempotencyKey: String,
      nowMillis: Long
  ): Either[String, Option[IdempotencyRecord]]
}

/** [[IdempotencyStore]] over any [[KeyValueStore]]. Thread-safe when `kv` is.
  *
  * A retried claim whose first put was applied server-side sees its own record: since job IDs are
  * unique per request, a stored record with this claim's job ID and fingerprint is this claim
  * (`Claimed`).
  */
final class TableIdempotencyStore(kv: KeyValueStore, table: String) extends IdempotencyStore {
  import IdempotencyStore._

  def claim(
      scope: String,
      idempotencyKey: String,
      fingerprint: String,
      jobId: String,
      expiresAtSeconds: Long,
      nowMillis: Long
  ): Either[String, IdempotencyClaim] =
    RuntimeStores.guard(for {
      k <- itemKey(scope, idempotencyKey)
      _ <- JobItems.checkPattern("fingerprint", fingerprint, JobLimits.FingerprintPattern)
      _ <- JobItems.checkJobId(jobId)
      _ <- Either.cond(nowMillis >= 0, (), "now must be >= 0")
      _ <- Either.cond(
        expiresAtSeconds * 1000L > nowMillis,
        (),
        "expiresAt must be after now"
      )
      mine = IdempotencyRecord(fingerprint, jobId, expiresAtSeconds)
      out <- attempt(k, mine, nowMillis, 1)
    } yield out)

  @tailrec
  private def attempt(
      k: ItemKey,
      mine: IdempotencyRecord,
      nowMillis: Long,
      round: Int
  ): Either[String, IdempotencyClaim] =
    if (round > MaxClaimRounds)
      Left(s"idempotency claim did not settle after $MaxClaimRounds rounds")
    else
      kv.putIfAbsent(table, encode(k, mine, 1L)) match {
        case ConditionalResult.Written         => Right(IdempotencyClaim.Claimed(mine))
        case ConditionalResult.ConditionFailed =>
          kv.get(table, k) match {
            case None    => attempt(k, mine, nowMillis, round + 1) // removed by TTL meanwhile
            case Some(i) =>
              decode(i) match {
                case Left(e)                              => Left(e)
                case Right((stored, _)) if stored == mine =>
                  Right(IdempotencyClaim.Claimed(mine))
                case Right((stored, _)) if !stored.isExpired(nowMillis) =>
                  Right(IdempotencyClaim.Existing(stored))
                case Right((_, version)) =>
                  kv.putIfVersion(table, encode(k, mine, version + 1), version) match {
                    case ConditionalResult.Written         => Right(IdempotencyClaim.Claimed(mine))
                    case ConditionalResult.ConditionFailed =>
                      attempt(k, mine, nowMillis, round + 1)
                  }
              }
          }
      }

  def get(
      scope: String,
      idempotencyKey: String,
      nowMillis: Long
  ): Either[String, Option[IdempotencyRecord]] =
    RuntimeStores.guard(for {
      k <- itemKey(scope, idempotencyKey)
      out <- kv.get(table, k) match {
        case None    => Right(None)
        case Some(i) => decode(i).map { case (r, _) => Some(r).filterNot(_.isExpired(nowMillis)) }
      }
    } yield out)
}

object IdempotencyStore {

  /** Bound on put/read rounds of one claim (each round loses only to a concurrent change). */
  val MaxClaimRounds: Int = 3

  private[jobs] def itemKey(scope: String, idempotencyKey: String): Either[String, ItemKey] =
    for {
      s <- JobItems.checkPattern("scope", scope, JobLimits.ScopePattern)
      k <- JobItems.checkPattern("idempotency key", idempotencyKey, JobLimits.IdempotencyKeyPattern)
    } yield ItemKey(TableSpecs.idempotencyPk(s, k), TableSpecs.IdempotencySk)

  private[jobs] def encode(k: ItemKey, r: IdempotencyRecord, version: Long): Item =
    Item(
      ListMap[String, AttrValue](
        Item.Pk -> AttrValue.S(k.pk),
        Item.Sk -> AttrValue.S(k.sk),
        "fingerprint" -> AttrValue.S(r.fingerprint),
        "jobId" -> AttrValue.S(r.jobId),
        "expiresAt" -> AttrValue.n(r.expiresAtSeconds),
        Item.Version -> AttrValue.n(version)
      )
    )

  private[jobs] def decode(i: Item): Either[String, (IdempotencyRecord, Long)] =
    (for {
      f <- i.strOpt("fingerprint").toRight("fingerprint is missing")
      j <- i.strOpt("jobId").toRight("jobId is missing")
      e <- i.long("expiresAt").toRight("expiresAt is missing")
      v <- i.long(Item.Version).toRight("version is missing")
    } yield (IdempotencyRecord(f, j, e), v)).left.map(e =>
      s"malformed QuerySession idempotency item ${i.strOpt(Item.Pk).getOrElse("?")}: $e"
    )
}
