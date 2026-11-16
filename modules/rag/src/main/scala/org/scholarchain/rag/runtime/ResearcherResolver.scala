package org.scholarchain.rag.runtime

import org.scholarchain.core._
import org.scholarchain.storage.ports.{GraphQuery, GraphRow}
import org.scholarchain.storage.runtime.{
  EntityLinkCandidate,
  EntityLinkResult,
  MetricCacheEntry,
  RuntimeStores
}

/** Typed reads of graph template rows (columns documented on each GraphQuery template). */
object GraphRows {
  def str(r: GraphRow, k: String): Option[String] = r.values.get(k).flatMap(JsonSupport.str)
  def num(r: GraphRow, k: String): Option[Double] = r.values.get(k).flatMap {
    case ujson.Num(n) => Some(n)
    case ujson.Str(s) => s.toDoubleOption
    case _            => None
  }
  def prop(r: GraphRow, k: String): Option[ujson.Value] =
    r.values.get("properties").collect { case o: ujson.Obj => o.value.get(k) }.flatten
  def propStr(r: GraphRow, k: String): Option[String] = prop(r, k).flatMap {
    case ujson.Str(s) => Some(s)
    case ujson.Num(n) => Some(Fact.fmt(n))
    case _            => None
  }
  def propNum(r: GraphRow, k: String): Option[Double] = prop(r, k).flatMap {
    case ujson.Num(n) => Some(n)
    case ujson.Str(s) => s.toDoubleOption
    case _            => None
  }
  def strList(r: GraphRow, k: String): List[String] =
    r.values.get(k).flatMap(JsonSupport.arr).getOrElse(Nil).flatMap(JsonSupport.str)
}

/** One candidate researcher for a name, with the context used to tell candidates apart. */
final case class Candidate(
    ref: ResearcherRef,
    matchedValue: String,
    identityOutcome: String,
    identityConfidence: Option[Double],
    publicationCount: Option[Double],
    distinctCoauthors: Option[Double],
    topCoauthors: List[ResearcherRef],
    link: EntityLinkCandidate
)

sealed trait Resolution { def asked: String }
object Resolution {

  /** Exactly one candidate, or a homonym suffix matching exactly one candidate's dblp name. */
  final case class Resolved(asked: String, candidate: Candidate, bySuffix: Boolean)
      extends Resolution

  /** Several candidates and no deciding suffix: never silently chosen. */
  final case class Ambiguous(
      asked: String,
      candidates: List[Candidate],
      total: Int,
      truncated: Boolean
  ) extends Resolution

  /** No candidate. */
  final case class Unknown(asked: String) extends Resolution

  /** The lookup itself failed (store error or invalid name). */
  final case class Failed(asked: String, error: String) extends Resolution
}

final case class ResolverOutput(
    resolutions: List[Resolution],
    facts: List[Fact],
    notes: List[String]
) {
  def resolved: List[Resolution.Resolved] = resolutions.collect { case r: Resolution.Resolved => r }
  def unresolved: List[Resolution] = resolutions.filterNot(_.isInstanceOf[Resolution.Resolved])

  /** Resolved researchers whose identity outcome is not `accepted` (conflict always counts). */
  def uncertain: Map[ResearcherId, String] =
    resolved.collect {
      case r if r.candidate.identityOutcome != "accepted" =>
        r.candidate.ref.id -> r.candidate.identityOutcome
    }.toMap
}

/** Stage 2, ResearcherResolver: names (folded with `AuthorNameKey.foldedName` by storage's
  * [[org.scholarchain.storage.runtime.EntityLinkReader]]) and ORCID iDs to canonical researchers.
  * Exactly one candidate resolves, unless the asked name carries a dblp homonym suffix that matches
  * neither its matched value nor its display name (then the name is unknown, Phase 9 m-2); a dblp
  * homonym suffix (`Wei Wang 0003`) matching exactly one of several candidates' names resolves;
  * anything else is ambiguous (candidates listed with context) or unknown, and the answer abstains
  * for that name. Affiliations: none (no institution data yet).
  */
final class ResearcherResolver(stores: RuntimeStores, ctx: RuntimeContext) {
  import ResearcherResolver._

  def resolve(names: List[String], orcids: List[String]): ResolverOutput = {
    val byName = names.map(n => (n, stores.entityLinks.byName(n, LookupLimit), true))
    val byOrcid = orcids.map(o => (o, stores.entityLinks.byOrcid(o, LookupLimit), false))
    val resolutions = (byName ++ byOrcid).map { case (asked, r, isName) =>
      decide(asked, r, isName)
    }
    val facts = resolutions.zipWithIndex.flatMap {
      case (Resolution.Resolved(a, c, _), i) => List(identityFact(a, c, resolved = true, 10 + i))
      case (Resolution.Ambiguous(a, cs, _, _), i) =>
        cs.zipWithIndex.map { case (c, j) =>
          identityFact(a, c, resolved = false, 100 + 10 * i + j)
        }
      case _ => Nil
    }
    val notes = resolutions.flatMap {
      case Resolution.Resolved(a, c, bySuffix) =>
        val how = if (bySuffix) " (its dblp homonym number matches exactly one candidate)" else ""
        val unc = c.identityOutcome match {
          case "accepted" => ""
          case "conflict" =>
            s" Identity outcome is conflict (confidence ${c.identityConfidence.map(Fact.fmt).getOrElse("unknown")}): the merged mentions disagree, so facts about this researcher are uncertain."
          case o => s" Identity outcome is $o, so facts about this researcher are uncertain."
        }
        List(s"'$a' was resolved to ${c.ref.displayName} (${c.ref.id.value})$how.$unc")
      case Resolution.Ambiguous(a, cs, total, truncated) =>
        val listed = cs.map(c =>
          s"${c.ref.displayName} (${c.ref.id.value}; publications ${c.publicationCount.map(Fact.fmt).getOrElse("?")}, " +
            s"distinct coauthors ${c.distinctCoauthors.map(Fact.fmt).getOrElse("?")} in ${ctx.fullWindowLabel}; " +
            s"top coauthors: ${
                if (c.topCoauthors.isEmpty) "none listed"
                else c.topCoauthors.map(_.displayName).mkString(", ")
              })"
        )
        val more =
          if (total > cs.size || truncated) s" Only the first ${cs.size} candidates are listed."
          else ""
        List(
          s"'$a' matches several researchers, so no answer is given for it. Candidates: ${listed.mkString("; ")}.$more"
        )
      case Resolution.Unknown(a) =>
        List(s"'$a' matches no researcher in the ScholarChain graph, so no answer is given for it.")
      case Resolution.Failed(a, e) =>
        List(s"'$a' could not be looked up ($e), so no answer is given for it.")
    }
    ResolverOutput(resolutions, facts, notes)
  }

  private def decide(
      asked: String,
      r: Either[String, EntityLinkResult],
      isName: Boolean
  ): Resolution =
    r match {
      case Left(e)    => Resolution.Failed(asked, e)
      case Right(res) =>
        val cands = res.candidates
          .groupBy(_.researcherId)
          .values
          .map(_.head)
          .toList
          .sortBy(_.researcherId.value)
        val hasSuffix = isName && DblpSuffix.findFirstIn(asked.trim).isDefined
        val norm = asked.trim.replaceAll("\\s+", " ")
        def suffixMatches(c: EntityLinkCandidate) =
          c.matchedValue.equalsIgnoreCase(norm) || c.displayName.equalsIgnoreCase(norm)
        cands match {
          case Nil => Resolution.Unknown(asked)
          // m-2: a homonym suffix that names neither the sole candidate's matched value nor its
          // display name asks for another researcher, so the name is unknown
          case c :: Nil if hasSuffix && !suffixMatches(c) => Resolution.Unknown(asked)
          case c :: Nil => Resolution.Resolved(asked, context(c), bySuffix = false)
          case many     =>
            val bySuffix = if (hasSuffix) many.filter(suffixMatches) else Nil
            bySuffix match {
              case one :: Nil => Resolution.Resolved(asked, context(one), bySuffix = true)
              case _          =>
                Resolution.Ambiguous(
                  asked,
                  many.take(ListedCandidates).map(context),
                  many.size,
                  res.truncated
                )
            }
        }
    }

  private def context(l: EntityLinkCandidate): Candidate = {
    val node = stores.graph
      .run(GraphQuery.ResearcherById(l.researcherId))
      .toOption
      .flatMap(_.rows.headOption)
    val entry: Option[MetricCacheEntry] =
      stores.metrics.get(l.researcherId, ctx.fullWindowLabel).toOption.flatten
    val w = ctx.runtime.defaultWindowLabel
    val top =
      if (!ctx.graphWindowLabels.contains(w)) Nil
      else
        stores.graph
          .run(
            GraphQuery
              .CoauthorNeighbors(l.researcherId, w, math.min(3, ctx.runtime.graphResultLimit))
          )
          .toOption
          .map(_.rows.flatMap { row =>
            for {
              id <- GraphRows.str(row, "coauthorId").map(ResearcherId(_)).filter(_.isWellFormed)
            } yield ResearcherRef(id, GraphRows.str(row, "displayName").getOrElse(id.value))
          })
          .getOrElse(Nil)
    Candidate(
      ResearcherRef(l.researcherId, l.displayName),
      l.matchedValue,
      node.flatMap(GraphRows.propStr(_, "identityOutcome")).getOrElse("unknown"),
      entry
        .map(_.identityConfidence)
        .orElse(node.flatMap(GraphRows.propNum(_, "minIdentityConfidence"))),
      entry.flatMap(_.value(CollaborationMetric.PublicationCount)),
      entry.flatMap(_.value(CollaborationMetric.DistinctCoauthors)),
      top,
      l
    )
  }

  private def identityFact(
      asked: String,
      c: Candidate,
      resolved: Boolean,
      priority: Int
  ): IdentityFact =
    IdentityFact(
      c.ref,
      asked,
      c.matchedValue,
      c.identityOutcome,
      c.identityConfidence,
      resolved,
      c.publicationCount,
      c.distinctCoauthors,
      c.topCoauthors,
      ctx.fullWindowLabel,
      SourceRef(c.link.recordRef, c.link.runId, c.link.policyVersion, ctx.graphRunId.value),
      priority
    )
}

/** Identity outcomes of every researcher a retrieved fact names. `uncertain` holds those whose
  * outcome is not `accepted` (conflict always counts; a failed lookup is `unknown`; a researcher
  * past the lookup bound is `unchecked`).
  */
final case class IdentityOutcomes(
    outcomes: Map[ResearcherId, String],
    names: Map[ResearcherId, String],
    lookups: Int,
    unchecked: Int
) {
  def uncertain: Map[ResearcherId, String] = outcomes.filter(_._2 != "accepted")
}

/** Reads the identity outcome of every researcher that enters facts through rankings, bridge
  * rankings, path hops, neighbours, edges or works, not only researchers resolved from a name
  * (CLAUDE.md: identity uncertainty is reported, not hidden). Outcomes already known from the
  * resolver are reused; the rest come from storage's `ResearcherById` graph template, at most
  * [[IdentityCheck.MaxLookups]] per query.
  */
final class IdentityCheck(stores: RuntimeStores) {
  import IdentityCheck._

  def run(facts: List[Fact], resolution: ResolverOutput): IdentityOutcomes = {
    val fromResolver: Map[ResearcherId, String] =
      resolution.resolutions.flatMap {
        case Resolution.Resolved(_, c, _)      => List(c.ref.id -> c.identityOutcome)
        case Resolution.Ambiguous(_, cs, _, _) => cs.map(c => c.ref.id -> c.identityOutcome)
        case _                                 => Nil
      }.toMap ++ facts.collect { case i: IdentityFact => i.researcher.id -> i.outcome }
    val refs = facts.flatMap(Fact.researcherRefs)
    val names = refs.reverse.map(r => r.id -> r.displayName).toMap
    val ids = refs.map(_.id).distinct
    val toLookUp = ids.filterNot(fromResolver.contains)
    val (looked, rest) = toLookUp.splitAt(MaxLookups)
    val read = looked.map { id =>
      val outcome = stores.graph
        .run(GraphQuery.ResearcherById(id))
        .toOption
        .flatMap(_.rows.headOption)
        .flatMap(GraphRows.propStr(_, "identityOutcome"))
        .getOrElse("unknown")
      id -> outcome
    }
    IdentityOutcomes(
      ids.flatMap(id => fromResolver.get(id).map(id -> _)).toMap ++ read ++ rest.map(
        _ -> Unchecked
      ),
      names,
      looked.size,
      rest.size
    )
  }
}

object IdentityCheck {

  /** ResearcherById reads per query (above it, researchers are reported as `unchecked`). */
  val MaxLookups = 250

  val Unchecked = "unchecked"

  /** Answer notes for uncertain researchers that the resolver did not already describe. */
  def notes(o: IdentityOutcomes, alreadyNoted: Set[ResearcherId]): List[String] = {
    val flagged = o.uncertain.toList
      .filter { case (id, out) => !alreadyNoted.contains(id) && out != Unchecked }
      .sortBy(_._1.value)
    val listed = flagged.take(10).map { case (id, out) =>
      s"${o.names.getOrElse(id, id.value)} (${id.value}): $out"
    }
    (if (flagged.isEmpty) Nil
     else
       List(
         s"Identity outcome is not accepted for researchers in the evidence, so facts about them are uncertain: ${listed
             .mkString("; ")}${if (flagged.size > 10) s"; and ${flagged.size - 10} more" else ""}."
       )) ++
      (if (o.unchecked > 0)
         List(
           s"The identity outcome of ${o.unchecked} researcher(s) was not checked (lookup bound $MaxLookups); claims about them are marked uncertain."
         )
       else Nil)
  }
}

object ResearcherResolver {

  /** Candidates read per name (EntityLinkReader allows at most 100). */
  val LookupLimit = 100

  /** Candidates listed (with context reads) for an ambiguous name. */
  val ListedCandidates = 10

  private val DblpSuffix = "\\s[0-9]{4}$".r
}
