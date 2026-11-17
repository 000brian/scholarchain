package org.scholarchain.spark

import java.io.File

import org.scholarchain.core._
import org.scholarchain.spark.FixtureData.Slot

/** Writes small known-graph inputs in the layout of the Hadoop J09 export and of a complete
  * entity-resolution run (`decisions/identity_assignment`, `_manifest.json`, `_SUCCESS`), through
  * the core contracts. Used by [[KnownGraphSuite]] (Phase 11 step-1a case sheet).
  *
  * Researcher IDs follow the anchor rule (`ResearcherId.forCluster` of the person's mentions), so
  * the pipeline reports no `researcher_anchor_mismatch`. Plants (team size, pair year, missing or
  * duplicated pair rows, an assignment for a mention that does not exist) are applied only where a
  * spec asks for them.
  */
object KnownGraphInputs {

  final case class KWork(code: String, year: Int, slots: List[Slot]) {
    val dblpKey: String = s"conf/kg/$code"
    val workId: WorkId = WorkId.fromDblpKey(dblpKey)
    def mention(i: Int): MentionId = MentionId.of(workId, i)
  }

  final case class Identity(confidence: Double, outcome: MatchOutcome)
  val DefaultIdentity: Identity = Identity(0.95, MatchOutcome.Accepted)

  /** Phase 6 F3a plant: an identity assignment for mention `orphanPosition` of `work` (absent from
    * author_mention) to `person`, and a pair row from slot `fromPosition` to that mention.
    */
  final case class OrphanPlant(
      work: String,
      fromPosition: Int,
      orphanPosition: Int,
      orphanName: String,
      person: String
  )

  final case class Spec(
      works: List[KWork],
      identities: Map[String, Identity] = Map.empty,
      teamSizeOverride: Map[String, Int] = Map.empty,
      pairYearOverride: Map[String, Int] = Map.empty,
      dropPairs: Set[String] = Set.empty,
      duplicatePairs: Set[String] = Set.empty,
      orphans: List[OrphanPlant] = Nil
  ) {
    def work(code: String): KWork =
      works.find(_.code == code).getOrElse(throw new NoSuchElementException(code))

    lazy val researcher: Map[String, ResearcherId] = {
      val fromMentions = works
        .flatMap(wk =>
          wk.slots.zipWithIndex.collect { case (Slot(_, Some(k)), i) =>
            k -> AnchorCandidate(wk.year, wk.workId, i)
          }
        )
        .groupBy(_._1)
        .map { case (k, xs) => k -> ResearcherId.forCluster(xs.map(_._2)).get }
      fromMentions ++ orphans.map(o =>
        o.person -> ResearcherId.fromAnchorMention(work(o.work).mention(o.orphanPosition))
      )
    }

    def rid(person: String): String =
      researcher.getOrElse(person, throw new NoSuchElementException(person)).value

    def identity(person: String): Identity = identities.getOrElse(person, DefaultIdentity)
  }

  private def get[A](e: Either[String, A]): A =
    e.fold(m => throw new IllegalStateException(m), identity)

  private val hadoopRun: RunId = get(
    RunId.of("hadoop.j09-graph-export", "known-graph", "sha256:kg-in", "sha256:kg-cfg")
  )
  val erRun: RunId = get(RunId.of("er.resolve", "known-graph", "sha256:kg-er-in", "sha256:kg-cfg"))
  private val venueKey = "conf/kg"

  private def dblpProv(key: String): Provenance =
    Provenance(
      1,
      SourceType.Dblp,
      "dblp",
      "2026-11-01",
      List(key),
      hadoopRun,
      "hadoop.kg",
      "dblp-parse/1",
      None
    )

  private def erProv(key: String): Provenance =
    Provenance(
      1,
      SourceType.Pipeline,
      "scholarchain-er",
      "dblp-kg",
      List(key),
      erRun,
      "er.assign-identities",
      "er-v1",
      None
    )

  private def enc[A](a: A)(implicit rw: upickle.default.ReadWriter[A], v: Validator[A]): String =
    Contract.encode(a).fold(e => throw new IllegalStateException(e.message), identity)

  private def put(f: File, lines: Seq[String]): Unit =
    Files2.writeString(f, lines.map(_ + "\n").mkString)

  private def pairLines(spec: Spec, wk: KWork): Seq[String] =
    if (spec.dropPairs(wk.code)) Nil
    else {
      val n = wk.slots.size
      val year = spec.pairYearOverride.getOrElse(wk.code, wk.year)
      val instances = for {
        i <- 0 until n
        j <- (i + 1) until n
        if wk.slots(i).name != wk.slots(j).name
        pp = get(PairPosition.forNames(wk.workId, wk.slots(i).name, i, wk.slots(j).name, j))
        (l, r) = get(UndirectedPair.canonical(wk.slots(i).name, wk.slots(j).name))
      } yield (l, r, pp)
      val lines = instances.groupBy(x => (x._1, x._2)).toSeq.sortBy(_._1).map { case ((l, r), xs) =>
        val positions = xs.map(_._3).distinct.sorted(PairPosition.ordering).toList
        enc(CoauthorPairCandidate(2, wk.workId, year, l, r, positions, n, dblpProv(wk.dblpKey)))
      }
      if (spec.duplicatePairs(wk.code)) lines ++ lines else lines
    }

  private def orphanPairLine(spec: Spec, o: OrphanPlant): String = {
    val wk = spec.work(o.work)
    val from = wk.slots(o.fromPosition).name
    val pp = get(
      PairPosition.forNames(wk.workId, from, o.fromPosition, o.orphanName, o.orphanPosition)
    )
    val (l, r) = get(UndirectedPair.canonical(from, o.orphanName))
    enc(
      CoauthorPairCandidate(
        2,
        wk.workId,
        wk.year,
        l,
        r,
        List(pp),
        o.orphanPosition + 1,
        dblpProv(wk.dblpKey)
      )
    )
  }

  private def assignment(spec: Spec, wk: KWork, mention: MentionId, person: String): String = {
    val id = spec.identity(person)
    val conflict = id.outcome == MatchOutcome.Conflict
    enc(
      MatchDecision(
        2,
        MatchSubjectKind.MentionResearcher,
        mention.value,
        spec.rid(person),
        None,
        id.outcome,
        MatchPath.Deterministic,
        id.confidence,
        Nil,
        List("fixture.rule"),
        if (conflict) List("cluster-multiple-orcids") else Nil,
        None,
        None,
        "er-v1",
        "2026-11-14T00:00:00Z",
        erProv(wk.dblpKey)
      )
    )
  }

  /** Writes the spec under `root`; returns (hadoop export run directory, identity assignments). */
  def write(root: File, spec: Spec): (File, File) = {
    val h = new File(root, "hadoop/run=kg")
    val er = new File(root, "er/run=kg")
    val ia = new File(er, "decisions/identity_assignment")
    Files2.writeString(
      new File(h, "_job.json"),
      ujson.write(
        ujson.Obj(
          "jobName" -> "hadoop.j09-graph-export",
          "runId" -> hadoopRun.value,
          "snapshotId" -> "dblp-kg",
          "codeVersion" -> "known-graph",
          "inputRuns" -> ujson.Arr()
        )
      )
    )
    val ws = spec.works
    put(
      new File(h, "work/part-r-00000"),
      ws.map { wk =>
        enc(
          WorkRecord(
            2,
            wk.workId,
            wk.dblpKey,
            "inproceedings",
            s"Known graph work ${wk.code}.",
            wk.year,
            Some(venueKey),
            None,
            Nil,
            Nil,
            wk.slots.map(_.name),
            dblpProv(wk.dblpKey)
          )
        )
      }
    )
    put(
      new File(h, "publication_team/part-r-00000"),
      ws.map { wk =>
        val size = spec.teamSizeOverride.getOrElse(wk.code, wk.slots.size)
        val names = wk.slots.map(_.name) ++
          (wk.slots.size until size).map(i => s"Extra Author $i")
        enc(PublicationTeam(1, wk.workId, wk.year, names, size, dblpProv(wk.dblpKey)))
      }
    )
    put(
      new File(h, "coauthor_pair_candidate/part-r-00000"),
      ws.flatMap(pairLines(spec, _)) ++ spec.orphans.map(orphanPairLine(spec, _))
    )
    put(
      new File(h, "author_mention/part-r-00000"),
      ws.flatMap { wk =>
        wk.slots.zipWithIndex.map { case (s, i) =>
          enc(AuthorMention(1, wk.mention(i), wk.workId, i, s.name, None, dblpProv(wk.dblpKey)))
        }
      }
    )
    put(
      new File(h, "venue/part-r-00000"),
      if (ws.isEmpty) Nil
      else Seq(enc(Venue(1, venueKey, "Known Graph Conference", dblpProv(venueKey))))
    )
    val assignments = ws.flatMap { wk =>
      wk.slots.zipWithIndex.collect { case (Slot(_, Some(k)), i) =>
        assignment(spec, wk, wk.mention(i), k)
      }
    } ++ spec.orphans.map { o =>
      val wk = spec.work(o.work)
      assignment(spec, wk, wk.mention(o.orphanPosition), o.person)
    }
    put(new File(ia, "part-00000.jsonl"), assignments)
    FixtureData.completeErRun(er, erRun)
    (h, ia)
  }
}
