package org.scholarchain.er.work

import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.util.Try

import org.scholarchain.core.{CodePointOrder, ExternalIdLink, ExternalIdType}
import org.scholarchain.er.data.Corpus
import org.scholarchain.er.io.{DataFiles, ErError}

/** Work resolution (decision 16): exact identifiers only.
  *
  *   - J04 work links (ExternalIdLink, subject `work`, DOI or OpenAlex work) become `exact_id`
  *     accepted decisions (`work.doi.exact-link`, `work.openalex-work.exact-link`).
  *   - Work-level J04 conflicts (Hadoop disposition records `conflicts/v1`, when a J04 run
  *     directory is given) become conflict decisions (`work.j04-conflict`).
  *   - Exact identifiers of dblp works (decision 9: dblp DOI, arXiv ID from arXiv DOIs and
  *     arxiv.org links, landing URL) shared by two or more subset works become conflict decisions
  *     (`work.doi.shared-by-dblp-works`, `work.arxiv.shared-by-dblp-works`,
  *     `work.landing-url.shared-by-dblp-works`): dblp lists separate records that one identifier
  *     names as one work, so nothing is merged.
  *
  * There is no probabilistic work matching: no shared contract for non-dblp works exists yet
  * (finding 11). Semantic Scholar is not a dataset (not applicable). An absent `external_id_link`
  * directory means zero exact-link coverage, not a failure.
  *
  * Records are entity-resolution job outputs (`scholarchain.er.work-decision` version 1), not core
  * contracts: core's MatchDecision has no work subject kind.
  */
object WorkResolution {
  val Format = "scholarchain.er.work-decision"
  val FormatVersion = 1

  val DoiLink = "work.doi.exact-link"
  val OpenAlexLink = "work.openalex-work.exact-link"
  val J04Conflict = "work.j04-conflict"
  val SharedDoi = "work.doi.shared-by-dblp-works"
  val SharedArxiv = "work.arxiv.shared-by-dblp-works"
  val SharedUrl = "work.landing-url.shared-by-dblp-works"

  val WorkLevelJ04Reasons: Set[String] =
    Set(
      "doi_maps_to_multiple_dblp_works",
      "doi_maps_to_multiple_openalex_works",
      "identifier_group_too_large"
    )

  final case class Result(decisions: List[ujson.Obj], summary: ujson.Obj)

  /** Reads J04 conflict dispositions (`<j04 run dir>/conflicts/v1/`) as loose JSON. */
  def readJ04Conflicts(j04RunDir: Path): List[(String, String, String)] = {
    val dir = j04RunDir.resolve("conflicts").resolve("v1")
    val out = List.newBuilder[(String, String, String)]
    DataFiles.list(dir).foreach { f =>
      DataFiles.readLines(f, f.toString) { (line, n) =>
        val j = Try(ujson.read(line).obj).getOrElse(throw new ErError(s"$f:$n: not a JSON object"))
        def s(k: String) = j.get(k).collect { case ujson.Str(v) => v }.getOrElse("")
        out += ((s("reason"), s("sourceRecordId"), s("runId")))
      }
    }
    out.result()
  }

  def run(
      corpus: Corpus,
      workLinks: List[ExternalIdLink],
      linksPresent: Boolean,
      j04Conflicts: Option[List[(String, String, String)]],
      policyVersion: String,
      decisionTime: String,
      runId: String
  ): Result = {
    val w = corpus.works
    def record(
        kind: String,
        works: List[Int],
        idType: String,
        externalId: String,
        outcome: String,
        rule: String,
        reasons: List[String],
        linkIds: List[String],
        sources: List[String]
    ): ujson.Obj = ujson.Obj(
      "format" -> Format,
      "formatVersion" -> FormatVersion,
      "decisionKind" -> kind,
      "workIds" -> ujson.Arr.from(works.map(i => ujson.Str(w.id(i)))),
      "dblpKeys" -> ujson.Arr.from(works.map(i => ujson.Str(w.dblpKey(i)))),
      "idType" -> idType,
      "externalId" -> externalId,
      "outcome" -> outcome,
      "matchPath" -> "exact_id",
      "rulesFired" -> ujson.Arr(rule),
      "reasonCodes" -> ujson.Arr.from(reasons.sorted.map(ujson.Str(_))),
      "sourceLinkIds" -> ujson.Arr.from(linkIds.map(ujson.Str(_))),
      "sources" -> ujson.Arr.from(sources.map(ujson.Str(_))),
      "policyVersion" -> policyVersion,
      "decisionTime" -> decisionTime,
      "runId" -> runId
    )
    val decisions = List.newBuilder[ujson.Obj]
    var linksOutside = 0L
    val linkedDoi = mutable.HashSet.empty[Int]
    val linkedOpenAlex = mutable.HashSet.empty[Int]
    workLinks
      .filter(l => l.idType == ExternalIdType.Doi || l.idType == ExternalIdType.OpenAlexWork)
      .groupBy(l => (l.subjectId, l.idType.wire, l.externalId))
      .toList
      .sortBy { case ((s, t, v), _) => (s, t, v) }
      .foreach { case ((subject, t, v), ls) =>
        val i = w.indexOf(subject)
        if (i < 0) linksOutside += ls.size
        else {
          if (t == "doi") linkedDoi += i else linkedOpenAlex += i
          decisions += record(
            "work_external_id",
            List(i),
            t,
            v,
            "accepted",
            if (t == "doi") DoiLink else OpenAlexLink,
            Nil,
            ls.map(_.linkId.value).sorted,
            ls.map(l =>
              s"${l.provenance.sourceType.wire}/${l.provenance.datasetId}/${l.provenance.snapshotId}"
            ).distinct
              .sorted
          )
        }
      }

    // J04 work-level conflicts.
    val doiToWorks = mutable.HashMap.empty[String, List[Int]]
    (0 until w.size).foreach(i =>
      Option(w.doi(i)).foreach(d => doiToWorks(d) = i :: doiToWorks.getOrElse(d, Nil))
    )
    val j04Counts = mutable.TreeMap.empty[String, Long]
    j04Conflicts.getOrElse(Nil).foreach { case (reason, subject, j04Run) =>
      j04Counts(reason) = j04Counts.getOrElse(reason, 0L) + 1
      if (WorkLevelJ04Reasons(reason))
        decisions += record(
          "work_j04_conflict",
          doiToWorks.getOrElse(subject, Nil).sorted,
          "doi",
          subject,
          "conflict",
          J04Conflict,
          List(s"j04:$reason"),
          Nil,
          List(s"hadoop.j04-exact-id-join/$j04Run")
        )
    }

    // Exact identifiers shared by several dblp works.
    def collisions(idType: String, rule: String, ids: Int => List[String]): Map[String, Long] = {
      val groups = mutable.HashMap.empty[String, List[Int]]
      (0 until w.size).foreach(i => ids(i).foreach(v => groups(v) = i :: groups.getOrElse(v, Nil)))
      var groupsN = 0L
      var worksN = 0L
      groups.toList
        .filter(_._2.size > 1)
        .sortWith((x, y) => CodePointOrder.lessThan(x._1, y._1))
        .foreach { case (v, is) =>
          groupsN += 1
          worksN += is.size
          decisions += record(
            "work_identifier_collision",
            is.sorted,
            idType,
            v,
            "conflict",
            rule,
            List(s"$idType-shared-by-${is.size}-dblp-works"),
            Nil,
            List("dblp")
          )
        }
      Map("groups" -> groupsN, "works" -> worksN)
    }
    val doiColl = collisions("doi", SharedDoi, i => Option(w.doi(i)).toList)
    val arxivColl = collisions("arxiv", SharedArxiv, i => w.identifiers(i).arxiv.toList)
    val urlColl = collisions("landing_url", SharedUrl, i => w.identifiers(i).urls)

    val n = w.size.toDouble
    def share(c: Int): Double = if (n == 0) 0.0 else c / n
    val withDoi = (0 until w.size).count(i => w.doi(i) != null)
    val withArxiv = (0 until w.size).count(i => w.identifiers(i).arxiv.isDefined)
    val withUrl = (0 until w.size).count(i => w.identifiers(i).urls.nonEmpty)
    val summary = ujson.Obj(
      "subsetWorks" -> w.size,
      "externalIdLinkDirectoryPresent" -> linksPresent,
      "exactDoiLinkCoverage" -> ujson
        .Obj("works" -> linkedDoi.size, "share" -> share(linkedDoi.size)),
      "exactOpenAlexWorkLinkCoverage" -> ujson
        .Obj("works" -> linkedOpenAlex.size, "share" -> share(linkedOpenAlex.size)),
      // ujson writes a Long as a string; counts are JSON numbers (repair m3)
      "workLinksOutsideSubset" -> ujson.Num(linksOutside.toDouble),
      "j04ConflictsDirectoryGiven" -> j04Conflicts.isDefined,
      "j04ConflictsByReason" -> ujson.Obj.from(j04Counts.toList.map { case (k, v) =>
        k -> ujson.Num(v.toDouble)
      }),
      "identifierAvailability" -> ujson.Obj(
        "dblpDoi" -> ujson.Obj("works" -> withDoi, "share" -> share(withDoi)),
        "arxivId" -> ujson.Obj("works" -> withArxiv, "share" -> share(withArxiv)),
        "landingUrl" -> ujson.Obj("works" -> withUrl, "share" -> share(withUrl)),
        "semanticScholarId" -> "not applicable: Semantic Scholar is not a ScholarChain dataset"
      ),
      "dblpIdentifierCollisions" -> ujson.Obj(
        "doi" -> ujson.Obj.from(doiColl.toList.sorted.map { case (k, v) =>
          k -> ujson.Num(v.toDouble)
        }),
        "arxivId" -> ujson.Obj.from(arxivColl.toList.sorted.map { case (k, v) =>
          k -> ujson.Num(v.toDouble)
        }),
        "landingUrl" -> ujson.Obj.from(urlColl.toList.sorted.map { case (k, v) =>
          k -> ujson.Num(v.toDouble)
        })
      ),
      "probabilisticWorkMatching" -> "not implemented: there is no shared contract for non-dblp works yet (finding 11); work resolution uses exact identifiers only"
    )
    Result(decisions.result(), summary)
  }

  def exists(p: Path): Boolean = Files.isDirectory(p)
}
