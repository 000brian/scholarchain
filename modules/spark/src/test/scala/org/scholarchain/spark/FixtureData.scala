package org.scholarchain.spark

import java.io.File

import org.scholarchain.core._

/** Hand-checkable fixture inputs in the layout of the Hadoop J09 export and the entity-resolution
  * identity assignments, written through the core contracts.
  *
  * Fixture A (breadth, identity, windows):
  * {{{
  *   W1 2016  a, b, c
  *   W2 2016  a, b
  *   W6 2018  Alice Able, Bob Baker, Alice Able   (homonym slots: both "Alice Able" are a)
  *   W3 2019  a, Dan Dorn, Daniel Dorn            (both Dan mentions resolve to d: ER self pair)
  *   W4 2019  a                                   (single author)
  *   W5 2023  b, e                                (e is a CONFLICT cluster, confidence 0.5)
  *   W8 2019  h, Uma Unknown                      (Uma has no identity assignment)
  * }}}
  * Fixture B (structure, all 2020): K4 {p,q,r,s} from three 4-author works (edge weight 3); K3
  * {t,u,v} from four 3-author works (weight 4); s-t from two works (weight 2); v-x from two works
  * (weight 2); y-z from one work (weight 1); g writes alone.
  *
  * Invalid inputs: two bad work lines (malformed JSON, wrong workId), one decision that is not an
  * identity assignment (it names a candidateId), and one assignment for a mention that is not in
  * author_mention.
  */
object FixtureData {

  private def get[A](e: Either[String, A]): A =
    e.fold(m => throw new IllegalStateException(m), identity)

  val hadoopRun: RunId = get(
    RunId.of("hadoop.j09-graph-export", "fixture", "sha256:fixture-in", "sha256:fixture-cfg")
  )
  val erRun: RunId = get(
    RunId.of("er.resolve", "fixture", "sha256:fixture-er-in", "sha256:fixture-cfg")
  )
  val snapshot = "dblp-fixture"
  val venueKey = "conf/fix"

  val names: Map[String, String] = Map(
    "a" -> "Alice Able",
    "b" -> "Bob Baker",
    "c" -> "Carol Crane",
    "e" -> "Eve Ember",
    "h" -> "Hank Hill",
    "p" -> "Pia Park",
    "q" -> "Quinn Quill",
    "r" -> "Rae Rook",
    "s" -> "Sam Stone",
    "t" -> "Tia Tusk",
    "u" -> "Uli Umber",
    "v" -> "Val Vane",
    "x" -> "Xia Xu",
    "y" -> "Yan Yoon",
    "z" -> "Zed Zane",
    "g" -> "Gus Gale"
  )

  /** One author slot: the dblp name and the person it resolves to (None = unassigned). */
  final case class Slot(name: String, person: Option[String])
  final case class Work(code: String, year: Int, slots: List[Slot]) {
    val dblpKey: String = s"conf/fix/$code"
    val workId: WorkId = WorkId.fromDblpKey(dblpKey)
  }

  private def p(k: String): Slot = Slot(names(k), Some(k))
  private def w(code: String, year: Int, people: String*): Work =
    Work(code, year, people.map(p).toList)

  val works: List[Work] = List(
    w("W1", 2016, "a", "b", "c"),
    w("W2", 2016, "a", "b"),
    Work("W6", 2018, List(p("a"), p("b"), p("a"))),
    Work("W3", 2019, List(p("a"), Slot("Dan Dorn", Some("d")), Slot("Daniel Dorn", Some("d")))),
    w("W4", 2019, "a"),
    w("W5", 2023, "b", "e"),
    Work("W8", 2019, List(p("h"), Slot("Uma Unknown", None))),
    w("G1", 2020, "p", "q", "r", "s"),
    w("G2", 2020, "p", "q", "r", "s"),
    w("G3", 2020, "p", "q", "r", "s"),
    w("H1", 2020, "t", "u", "v"),
    w("H2", 2020, "t", "u", "v"),
    w("H3", 2020, "t", "u", "v"),
    w("H4", 2020, "t", "u", "v"),
    w("B1", 2020, "s", "t"),
    w("B2", 2020, "s", "t"),
    w("X1", 2020, "v", "x"),
    w("X2", 2020, "v", "x"),
    w("Y1", 2020, "y", "z"),
    w("Z1", 2020, "g")
  )

  def work(code: String): Work = works.find(_.code == code).get

  /** Identity confidence and outcome per person (e is a CONFLICT cluster). */
  def confidence(person: String): Double = if (person == "e") 0.5 else 0.95
  def outcome(person: String): MatchOutcome =
    if (person == "e") MatchOutcome.Conflict else MatchOutcome.Accepted

  /** ResearcherId of a person: derived from its earliest mention (the anchor rule). */
  lazy val researcher: Map[String, ResearcherId] =
    works
      .flatMap(wk =>
        wk.slots.zipWithIndex.collect { case (Slot(_, Some(k)), i) =>
          k -> AnchorCandidate(wk.year, wk.workId, i)
        }
      )
      .groupBy(_._1)
      .map { case (k, xs) => k -> ResearcherId.forCluster(xs.map(_._2)).get }

  def rid(person: String): String = researcher(person).value

  private def dblpProv(key: String): Provenance =
    Provenance(
      1,
      SourceType.Dblp,
      "dblp",
      "2026-11-01",
      List(key),
      hadoopRun,
      "hadoop.fixture",
      "dblp-parse/1",
      None
    )

  private def erProv(key: String): Provenance =
    Provenance(
      1,
      SourceType.Pipeline,
      "scholarchain-er",
      snapshot,
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

  /** Writes the fixture; returns (hadoop export run directory, identity assignment directory). */
  def write(root: File): (File, File) = {
    val h = new File(root, "hadoop/run=fixture")
    val er = new File(root, "er/run=fixture")
    val ia = new File(er, "decisions/identity_assignment")
    Files2.writeString(
      new File(h, "_job.json"),
      ujson.write(
        ujson.Obj(
          "jobName" -> "hadoop.j09-graph-export",
          "runId" -> hadoopRun.value,
          "snapshotId" -> snapshot,
          "codeVersion" -> "fixture",
          "inputRuns" -> ujson.Arr()
        )
      )
    )
    Files2.writeString(
      new File(er, "_manifest.json"),
      ujson.write(
        ujson.Obj(
          "jobName" -> "er.resolve",
          "runId" -> erRun.value,
          "codeVersion" -> "fixture",
          "policyVersion" -> "er-v1"
        )
      )
    )
    val byYear = works.groupBy(_.year)
    for ((year, ws) <- byYear) {
      val workLines = ws.map { wk =>
        enc(
          WorkRecord(
            2,
            wk.workId,
            wk.dblpKey,
            "inproceedings",
            s"Fixture work ${wk.code}.",
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
      val extra =
        if (year == 2016) Seq("{not json", badWorkLine) else Nil
      put(new File(h, s"work/year=$year/part-r-00000"), workLines ++ extra)
      put(
        new File(h, s"publication_team/year=$year/part-r-00000"),
        ws.map { wk =>
          enc(
            PublicationTeam(
              1,
              wk.workId,
              wk.year,
              wk.slots.map(_.name),
              wk.slots.size,
              dblpProv(wk.dblpKey)
            )
          )
        }
      )
      put(new File(h, s"coauthor_pair_candidate/year=$year/part-r-00000"), ws.flatMap(pairLines))
    }
    put(
      new File(h, "author_mention/part-r-00000"),
      works.flatMap { wk =>
        wk.slots.zipWithIndex.map { case (s, i) =>
          enc(
            AuthorMention(
              1,
              MentionId.of(wk.workId, i),
              wk.workId,
              i,
              s.name,
              None,
              dblpProv(wk.dblpKey)
            )
          )
        }
      }
    )
    put(
      new File(h, "venue/part-r-00000"),
      Seq(enc(Venue(1, venueKey, "Fixture Conference", dblpProv(venueKey))))
    )
    put(new File(ia, "part-00000.jsonl"), assignmentLines)
    (h, ia)
  }

  /** A schema-valid JSON work whose workId is not WorkId.fromDblpKey(dblpKey): rejected. */
  def badWorkLine: String = {
    val good = work("W1")
    val json = ujson.read(
      enc(
        WorkRecord(
          2,
          good.workId,
          good.dblpKey,
          "inproceedings",
          "Bad.",
          2016,
          Some(venueKey),
          None,
          Nil,
          Nil,
          List("Alice Able"),
          dblpProv(good.dblpKey)
        )
      )
    )
    json("workId") = WorkId.fromDblpKey("conf/fix/other").value
    ujson.write(json)
  }

  /** Consolidated pair candidates of one work, as Hadoop J08b writes them. */
  def pairLines(wk: Work): Seq[String] = {
    val n = wk.slots.size
    val instances = for {
      i <- 0 until n
      j <- (i + 1) until n
      if wk.slots(i).name != wk.slots(j).name
      pp = get(PairPosition.forNames(wk.workId, wk.slots(i).name, i, wk.slots(j).name, j))
      (l, r) = get(UndirectedPair.canonical(wk.slots(i).name, wk.slots(j).name))
    } yield (l, r, pp)
    instances.groupBy(x => (x._1, x._2)).toSeq.sortBy(_._1).map { case ((l, r), xs) =>
      val positions = xs.map(_._3).distinct.sorted(PairPosition.ordering).toList
      enc(CoauthorPairCandidate(2, wk.workId, wk.year, l, r, positions, n, dblpProv(wk.dblpKey)))
    }
  }

  def assignmentLines: Seq[String] = {
    val good = works.flatMap { wk =>
      wk.slots.zipWithIndex.collect { case (Slot(_, Some(k)), i) =>
        val conflict = outcome(k) == MatchOutcome.Conflict
        enc(
          MatchDecision(
            2,
            MatchSubjectKind.MentionResearcher,
            MentionId.of(wk.workId, i).value,
            rid(k),
            None,
            outcome(k),
            MatchPath.Deterministic,
            confidence(k),
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
    }
    val w8 = work("W8")
    // Not an identity assignment: it decides a candidate (candidateId set). Rejected.
    val candidate = get(
      MatchCandidateId.of(
        MatchSubjectKind.MentionResearcher,
        MentionId.of(w8.workId, 1).value,
        rid("h"),
        "fixture",
        "key"
      )
    )
    val notIdentity = enc(
      MatchDecision(
        2,
        MatchSubjectKind.MentionResearcher,
        MentionId.of(w8.workId, 1).value,
        rid("h"),
        Some(candidate),
        MatchOutcome.Rejected,
        MatchPath.Probabilistic,
        0.1,
        Nil,
        Nil,
        Nil,
        None,
        None,
        "er-v1",
        "2026-11-14T00:00:00Z",
        erProv(w8.dblpKey)
      )
    )
    // Valid identity assignment for a mention that author_mention does not contain.
    val orphanMention = MentionId.of(w8.workId, 5)
    val orphan = enc(
      MatchDecision(
        2,
        MatchSubjectKind.MentionResearcher,
        orphanMention.value,
        ResearcherId.fromAnchorMention(orphanMention).value,
        None,
        MatchOutcome.Accepted,
        MatchPath.Deterministic,
        0.95,
        Nil,
        List("fixture.rule"),
        Nil,
        None,
        None,
        "er-v1",
        "2026-11-14T00:00:00Z",
        erProv(w8.dblpKey)
      )
    )
    good ++ Seq(notIdentity, orphan)
  }

  /** The fixture job configuration (production windows, small output partitioning). */
  def configFile: File = {
    val dir = sys.props("user.dir")
    Seq(new File(dir, "conf/phase06.json"), new File(dir, "modules/spark/conf/phase06.json"))
      .find(_.isFile)
      .getOrElse(throw new IllegalStateException(s"conf/phase06.json not found from $dir"))
  }

  def config: SparkJobConfig =
    SparkJobConfig
      .parse(Files2.readString(configFile))
      .fold(e => throw new IllegalStateException(e.mkString("; ")), identity)
      .copy(outputPartitions = 1)
}
