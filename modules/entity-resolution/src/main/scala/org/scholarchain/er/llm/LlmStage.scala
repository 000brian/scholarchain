package org.scholarchain.er.llm

import scala.collection.mutable
import scala.util.control.NonFatal

import org.scholarchain.core.{
  AdjudicationOutcome,
  CodePointOrder,
  LlmAdjudication,
  MatchCandidateId,
  MatchFeature,
  MatchOutcome,
  Provenance
}
import org.scholarchain.er.data.Corpus
import org.scholarchain.er.policy.{Policy, Reasons}

/** Selection of LLM-band pairs, bounded by the per-run maximum (decision 12): the band pairs with
  * the smallest pair keys (mention indices, i.e. mention-ID code-point order) are kept, with every
  * band candidate ID of each kept pair. The selection does not depend on input order or on the
  * cache.
  */
final class BandSelection[P](val max: Int) {
  require(max >= 0, "LLM max per run must be >= 0")
  private final class Entry(val ids: mutable.ArrayBuffer[String], val payload: P)
  private val kept = new java.util.TreeMap[java.lang.Long, Entry]()
  var bandCandidates = 0L

  /** Offers one band candidate of pair `pairKey`; `payload` is evaluated only when the pair is
    * newly kept.
    */
  def offer(pairKey: Long, candidateId: String, payload: => P): Unit = {
    bandCandidates += 1
    val k = java.lang.Long.valueOf(pairKey)
    val existing = kept.get(k)
    if (existing != null) existing.ids += candidateId
    else if (kept.size < max) kept.put(k, new Entry(mutable.ArrayBuffer(candidateId), payload))
    else if (max > 0 && pairKey < kept.lastKey().longValue) {
      kept.pollLastEntry()
      kept.put(k, new Entry(mutable.ArrayBuffer(candidateId), payload))
    }
  }

  def contains(pairKey: Long): Boolean = kept.containsKey(java.lang.Long.valueOf(pairKey))

  def payload(pairKey: Long): P = kept.get(java.lang.Long.valueOf(pairKey)).payload

  /** Kept pairs in key order with their distinct candidate IDs (sorted). */
  def selected: List[(Long, List[String])] = {
    val out = List.newBuilder[(Long, List[String])]
    val it = kept.entrySet.iterator
    while (it.hasNext) {
      val e = it.next()
      out += (e.getKey.longValue -> e.getValue.ids.toList.distinct.sorted(CodePointOrder.ordering))
    }
    out.result()
  }
}

object PairKey {
  def of(a: Int, b: Int): Long = (math.min(a, b).toLong << 32) | math.max(a, b).toLong
  def left(k: Long): Int = (k >>> 32).toInt
  def right(k: Long): Int = (k & 0xffffffffL).toInt
}

/** The decision of one adjudicated pair. */
final case class LlmResult(
    outcome: MatchOutcome,
    confidence: Double,
    reasonCodes: List[String],
    adjudicationId: Option[String],
    fromCache: Boolean
)

/** An adjudicator call that threw instead of replying (repair m4). Only the exception class and the
  * SHA-256 of its message are kept, never the message text (it may echo model or input text).
  */
final case class AdjudicatorError(
    left: String,
    right: String,
    inputHash: String,
    errorClass: String,
    messageSha256: Option[String]
)

/** A model reply that failed validation (only its hash is kept, never its text). */
final case class RejectedReply(
    left: String,
    right: String,
    inputHash: String,
    reason: String,
    detail: String,
    replySha256: String
)

object LlmStage {

  val MaxCoauthors = 15

  /** The adjudication input of a pair: copies of the two mentions' source facts and the features.
    */
  def input(
      corpus: Corpus,
      a: Int,
      b: Int,
      features: List[MatchFeature],
      offered: List[String]
  ): ujson.Obj = {
    def mention(i: Int): ujson.Obj = {
      val m = corpus.mentions
      val w = corpus.works
      val wi = m.work(i)
      val team = w.authorNames(wi)
      val co = team.indices.filter(_ != m.position(i)).map(team(_)).take(MaxCoauthors)
      ujson.Obj(
        "mentionId" -> m.id(i),
        "name" -> corpus.mentionName(i), // the name as written on this mention
        "dblpKey" -> w.dblpKey(wi),
        "title" -> w.title(wi),
        "venue" -> Option(w.venue(wi))
          .map(v => ujson.Str(corpus.venueNames.getOrElse(v, v)))
          .getOrElse(ujson.Null),
        "year" -> w.year(wi),
        "authorPosition" -> (m.position(i) + 1),
        "teamSize" -> team.length,
        "coauthors" -> ujson.Arr.from(co.map(ujson.Str(_))),
        "orcids" -> ujson.Arr.from(m.orcids(i).map(ujson.Str(_)))
      )
    }
    ujson.Obj(
      "task" -> "same-researcher",
      "offeredCandidateIds" -> ujson.Arr.from(offered.map(ujson.Str(_))),
      "left" -> mention(a),
      "right" -> mention(b),
      "features" -> ujson.Obj.from(features.map(f => f.name -> ujson.Num(f.value)))
    )
  }

  /** Adjudicates the selected pairs. `features(pairKey)` and `probability(pairKey)` give the pair's
    * features and scored match probability; `provenance(pairKey)` the provenance template.
    *
    * Returns one result per selected pair, the rejected replies, and the adjudicator errors (calls
    * that threw; their pairs end UNRESOLVED with `llm-output-rejected:adjudicator-error`).
    */
  def run(
      corpus: Corpus,
      policy: Policy,
      selected: List[(Long, List[String])],
      features: Long => List[MatchFeature],
      probability: Long => Double,
      provenance: Long => Provenance,
      adjudicator: Adjudicator,
      cache: AdjudicationCache,
      prompt: AdjudicationPrompt
  ): (Map[Long, LlmResult], List[RejectedReply], List[AdjudicatorError]) = {
    val results = mutable.HashMap.empty[Long, LlmResult]
    val rejected = List.newBuilder[RejectedReply]
    val errors = List.newBuilder[AdjudicatorError]
    val minConf = policy.thresholds.llmMinConfidence
    selected.foreach { case (key, offered) =>
      val a = PairKey.left(key)
      val b = PairKey.right(key)
      val p = probability(key)
      val ids = offered.map(MatchCandidateId(_))
      val req = AdjudicationRequest.of(input(corpus, a, b, features(key), offered), ids, prompt)
      val cached =
        cache.get(req.inputHash, req.promptVersion, adjudicator.modelVersion, policy.policyVersion)
      val validated: Either[OutputValidator.Rejected, (LlmAdjudication, Boolean)] = cached match {
        case Some(adj) => Right((adj, true))
        case None      =>
          val reply =
            try Right(adjudicator.adjudicate(req))
            catch {
              case NonFatal(e) =>
                errors += AdjudicatorError(
                  corpus.mentions.id(a),
                  corpus.mentions.id(b),
                  req.inputHash,
                  e.getClass.getName,
                  Option(e.getMessage).map(msg =>
                    org.scholarchain.er.io.DataFiles.sha256OfBytes(msg.getBytes("UTF-8"))
                  )
                )
                Left(OutputValidator.Rejected("adjudicator-error", e.getClass.getSimpleName))
            }
          reply
            .flatMap(r =>
              OutputValidator
                .validate(r, req, adjudicator.modelVersion, policy.policyVersion, provenance(key))
                .left
                .map { rej =>
                  rejected += RejectedReply(
                    corpus.mentions.id(a),
                    corpus.mentions.id(b),
                    req.inputHash,
                    rej.reason,
                    rej.detail,
                    org.scholarchain.er.io.DataFiles.sha256OfBytes(r.getBytes("UTF-8"))
                  )
                  rej
                }
            )
            .map { adj => cache.put(adj); (adj, false) }
      }
      results(key) = validated match {
        case Left(rej) =>
          LlmResult(MatchOutcome.Unresolved, p, List(Reasons.llmRejected(rej.reason)), None, false)
        case Right((adj, hit)) =>
          val id = Some(adj.adjudicationId.value)
          adj.outcome match {
            case AdjudicationOutcome.Accept if adj.confidence >= minConf =>
              LlmResult(MatchOutcome.Accepted, adj.confidence, Nil, id, hit)
            case AdjudicationOutcome.Reject if adj.confidence >= minConf =>
              LlmResult(MatchOutcome.Rejected, adj.confidence, Nil, id, hit)
            case AdjudicationOutcome.Abstain =>
              LlmResult(MatchOutcome.Unresolved, p, List(Reasons.LlmAbstain), id, hit)
            case _ =>
              LlmResult(MatchOutcome.Unresolved, p, List(Reasons.LlmLowConfidence), id, hit)
          }
      }
    }
    (results.toMap, rejected.result(), errors.result())
  }
}
