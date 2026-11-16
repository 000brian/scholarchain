package org.scholarchain.core

/** Validation rules for every contract record. An empty list means valid.
  *
  * Messages are `<field path>: <problem>`. Validators assume no null fields; [[Contract.validate]]
  * runs [[NullCheck]] first, so a missing (null) provenance or field is reported, never defaulted.
  */
trait Validator[A] {
  def validate(a: A): List[String]
}

object Validator {

  def apply[A](implicit v: Validator[A]): Validator[A] = v

  def instance[A](f: A => List[String]): Validator[A] = new Validator[A] {
    def validate(a: A): List[String] = f(a)
  }

  // ------------------------------------------------------------------------------------------
  // Shared rules
  // ------------------------------------------------------------------------------------------

  private[core] def check(ok: Boolean, msg: => String): List[String] = if (ok) Nil else List(msg)

  private[core] def nested(prefix: String, errors: List[String]): List[String] =
    errors.map(e => s"$prefix.$e")

  private[core] def nonBlank(field: String, s: String): List[String] =
    check(s.trim.nonEmpty, s"$field: must not be blank")

  private[core] def schema(name: String, version: Int): List[String] =
    check(
      SchemaVersions.isSupported(name, version),
      s"schemaVersion: unsupported version $version for $name " +
        s"(supported: ${SchemaVersions.supported.getOrElse(name, Set.empty).toList.sorted.mkString(", ")})"
    )

  private[core] def confidence(field: String, d: Double): List[String] =
    check(!d.isNaN && d >= 0.0 && d <= 1.0, s"$field: confidence $d is outside [0, 1]")

  private[core] def finiteNonNegative(field: String, d: Double): List[String] =
    check(!d.isNaN && !d.isInfinite && d >= 0.0, s"$field: $d must be finite and >= 0")

  private[core] def year(field: String, y: Int): List[String] =
    check(y >= 1 && y <= 9999, s"$field: year $y is outside 1..9999")

  private[core] def window(field: String, w: YearWindow): List[String] =
    year(s"$field.startYear", w.startYear) ++ year(s"$field.endYear", w.endYear) ++
      check(
        w.startYear <= w.endYear,
        s"$field: startYear ${w.startYear} exceeds endYear ${w.endYear}"
      )

  private[core] def stableId(field: String, id: StableId): List[String] =
    check(id.isWellFormed, s"$field: malformed stable ID '${id.value}'")

  private[core] def ordered(field: String, left: String, right: String): List[String] =
    check(
      UndirectedPair.isCanonical(left, right),
      s"$field: undirected pair ('$left', '$right') is not in canonical order " +
        "(left < right by Unicode code point, no self pairs)"
    )

  private def optional[T](field: String, o: Option[T])(f: (String, T) => List[String]) =
    o.toList.flatMap(f(field, _))

  private def doi(field: String, s: String) =
    check(Doi.isCanonical(s), s"$field: '$s' is not a canonical DOI")
  private def orcid(field: String, s: String) =
    check(Orcid.isCanonical(s), s"$field: '$s' is not a canonical ORCID iD")
  private def openAlex(entity: OpenAlexEntity)(field: String, s: String) =
    check(entity.isCanonical(s), s"$field: '$s' is not a canonical OpenAlex ${entity.letter} ID")
  private def dblpName(field: String, s: String) =
    check(DblpAuthorName.isCanonical(s), s"$field: '$s' is not a canonical dblp author name")
  private def venueKey(field: String, s: String) =
    check(VenueKey.isValid(s), s"$field: '$s' is not a dblp venue key")
  private def each[T](field: String, xs: List[T])(f: (String, T) => List[String]) =
    xs.zipWithIndex.flatMap { case (x, i) => f(s"$field[$i]", x) }
  private def distinct[T](field: String, xs: List[T]) =
    check(xs.distinct.size == xs.size, s"$field: contains duplicates")

  /** Verbatim source text kept in a list (dblp `<ee>`, `<url>`): trimmed, non-blank, no controls.
    */
  private def sourceText(field: String, v: String): List[String] =
    check(v.trim.nonEmpty, s"$field: must not be blank") ++
      check(v == v.trim, s"$field: must not have leading or trailing whitespace") ++
      check(!v.exists(Character.isISOControl), s"$field: must not contain control characters")

  private def textHash(p: Provenance, text: String, what: String): List[String] =
    p.contentHash match {
      case None    => List(s"provenance.contentHash: required because the record carries $what")
      case Some(h) =>
        check(h == ContentHash.of(text), s"provenance.contentHash: does not match $what")
    }

  private def prov(p: Provenance): List[String] = nested("provenance", provenance.validate(p))

  private def datasetIdRule(field: String, d: String): List[String] =
    check(d.matches("[a-z0-9][a-z0-9-]*"), s"$field: '$d' must match [a-z0-9][a-z0-9-]*")

  /** dblp-published records: every fact they carry, including DOIs and ORCID iDs, is dblp's. */
  private def fromDblp(record: String, p: Provenance): List[String] =
    check(
      p.sourceType == SourceType.Dblp,
      s"provenance.sourceType: $record carries dblp facts only, so its source must be dblp " +
        s"(got ${p.sourceType.wire}); link other sources' identifiers with ExternalIdLink"
    )

  private def externalValue(field: String, t: ExternalIdType, v: String): List[String] =
    check(t.isCanonical(v), s"$field: '$v' is not a canonical ${t.wire} identifier")

  private def assertedBy(field: String, t: ExternalIdType, source: SourceType): List[String] =
    check(
      t.sources.contains(source),
      s"$field: a ${t.wire} identifier cannot be asserted by source ${source.wire} " +
        s"(allowed: ${t.sources.map(_.wire).toList.sorted.mkString(", ")})"
    )

  // ------------------------------------------------------------------------------------------
  // Record validators
  // ------------------------------------------------------------------------------------------

  implicit val yearWindow: Validator[YearWindow] = instance(w => window("window", w))

  implicit val provenance: Validator[Provenance] = instance { p =>
    schema("Provenance", p.schemaVersion) ++
      datasetIdRule("datasetId", p.datasetId) ++
      nonBlank("snapshotId", p.snapshotId) ++
      check(p.sourceRecordIds.nonEmpty, "sourceRecordIds: must not be empty") ++
      each("sourceRecordIds", p.sourceRecordIds)(nonBlank) ++
      stableId("runId", p.runId) ++
      nonBlank("jobName", p.jobName) ++
      nonBlank("policyVersion", p.policyVersion) ++
      optional("contentHash", p.contentHash)((f, h) =>
        check(ContentHash.isWellFormed(h), s"$f: '$h' must be sha256:<64 lowercase hex>")
      )
  }

  implicit val workRecord: Validator[WorkRecord] = instance { w =>
    schema("WorkRecord", w.schemaVersion) ++
      stableId("workId", w.workId) ++
      check(DblpKey.isValid(w.dblpKey), s"dblpKey: '${w.dblpKey}' is not a dblp key") ++
      check(
        !DblpKey.isValid(w.dblpKey) || w.workId == WorkId.fromDblpKey(w.dblpKey),
        s"workId: ${w.workId} is not WorkId.fromDblpKey(${w.dblpKey})"
      ) ++
      nonBlank("publicationType", w.publicationType) ++
      nonBlank("title", w.title) ++
      year("year", w.year) ++
      optional("venueKey", w.venueKey)(venueKey) ++
      optional("doi", w.doi)(doi) ++
      each("eeLinks", w.eeLinks)(sourceText) ++
      distinct("eeLinks", w.eeLinks) ++
      each("urls", w.urls)(sourceText) ++
      distinct("urls", w.urls) ++
      optional("doi", w.doi)((f, d) =>
        check(
          w.eeLinks.exists(e => Doi.fromDblpEe(e).contains(d)),
          s"$f: '$d' is not the DOI of any eeLinks entry (Doi.fromDblpEe)"
        )
      ) ++
      check(w.authorNames.nonEmpty, "authorNames: must not be empty") ++
      each("authorNames", w.authorNames)(dblpName) ++
      fromDblp("WorkRecord", w.provenance) ++
      prov(w.provenance)
  }

  implicit val authorMention: Validator[AuthorMention] = instance { m =>
    schema("AuthorMention", m.schemaVersion) ++
      stableId("mentionId", m.mentionId) ++
      stableId("workId", m.workId) ++
      check(m.position >= 0, s"position: ${m.position} must be >= 0") ++
      check(
        m.mentionId == MentionId.of(m.workId, m.position),
        s"mentionId: ${m.mentionId} is not MentionId.of(workId, position)"
      ) ++
      dblpName("name", m.name) ++
      optional("orcid", m.orcid)(orcid) ++
      fromDblp("AuthorMention", m.provenance) ++
      prov(m.provenance)
  }

  implicit val sourcedExternalId: Validator[SourcedExternalId] = instance { x =>
    externalValue("value", x.idType, x.value) ++
      assertedBy("sourceType", x.idType, x.sourceType) ++
      datasetIdRule("datasetId", x.datasetId) ++
      nonBlank("snapshotId", x.snapshotId) ++
      nonBlank("sourceRecordId", x.sourceRecordId)
  }

  implicit val canonicalResearcher: Validator[CanonicalResearcher] = instance { r =>
    val orcids = r.externalIds.filter(_.idType == ExternalIdType.Orcid).map(_.value).distinct
    schema("CanonicalResearcher", r.schemaVersion) ++
      stableId("researcherId", r.researcherId) ++
      stableId("anchorMentionId", r.anchorMentionId) ++
      check(
        r.researcherId == ResearcherId.fromAnchorMention(r.anchorMentionId),
        s"researcherId: ${r.researcherId} is not ResearcherId.fromAnchorMention(anchorMentionId)"
      ) ++
      nonBlank("displayName", r.displayName) ++
      check(r.dblpNames.nonEmpty, "dblpNames: must not be empty") ++
      each("dblpNames", r.dblpNames)(dblpName) ++
      check(
        r.dblpNames == r.dblpNames.distinct.sorted(CodePointOrder.ordering),
        "dblpNames: must be distinct and sorted by Unicode code point"
      ) ++
      each("externalIds", r.externalIds)((f, x) =>
        check(
          ExternalIdType.forResearcher.contains(x.idType),
          s"$f.idType: ${x.idType.wire} is not a researcher identifier"
        ) ++ nested(f, sourcedExternalId.validate(x))
      ) ++
      check(
        r.externalIds == r.externalIds.distinct.sorted(SourcedExternalId.ordering),
        "externalIds: must be distinct and sorted by SourcedExternalId.ordering"
      ) ++
      check(
        orcids.size <= 1,
        s"externalIds: at most one ORCID iD per researcher (got ${orcids.mkString(", ")})"
      ) ++
      prov(r.provenance)
  }

  implicit val authorshipRecord: Validator[AuthorshipRecord] = instance { a =>
    schema("AuthorshipRecord", a.schemaVersion) ++
      stableId("researcherId", a.researcherId) ++
      stableId("workId", a.workId) ++
      stableId("mentionId", a.mentionId) ++
      check(a.position >= 0, s"position: ${a.position} must be >= 0") ++
      check(
        a.mentionId == MentionId.of(a.workId, a.position),
        s"mentionId: ${a.mentionId} is not MentionId.of(workId, position)"
      ) ++
      year("year", a.year) ++
      confidence("matchConfidence", a.matchConfidence) ++
      prov(a.provenance)
  }

  implicit val publicationTeam: Validator[PublicationTeam] = instance { t =>
    schema("PublicationTeam", t.schemaVersion) ++
      stableId("workId", t.workId) ++
      year("year", t.year) ++
      check(t.authorNames.nonEmpty, "authorNames: must not be empty") ++
      each("authorNames", t.authorNames)(dblpName) ++
      check(
        t.teamSize == t.authorNames.size,
        s"teamSize: ${t.teamSize} != authorNames size ${t.authorNames.size}"
      ) ++
      prov(t.provenance)
  }

  implicit val coauthorPairCandidate: Validator[CoauthorPairCandidate] = instance { c =>
    schema("CoauthorPairCandidate", c.schemaVersion) ++
      stableId("workId", c.workId) ++
      year("year", c.year) ++
      dblpName("left", c.left) ++
      dblpName("right", c.right) ++
      ordered("left/right", c.left, c.right) ++
      check(c.teamSize >= 2, s"teamSize: ${c.teamSize} must be >= 2 for a pair") ++
      pairPositions(c) ++
      prov(c.provenance)
  }

  private def pairPositions(c: CoauthorPairCandidate): List[String] = {
    def position(field: String, p: Int): List[String] =
      check(p >= 0, s"$field: $p must be >= 0") ++
        check(p < 0 || p < c.teamSize, s"$field: $p must be < teamSize ${c.teamSize}")
    def mention(field: String, id: MentionId, p: Int): List[String] =
      stableId(field, id) ++
        check(
          !id.isWellFormed || p < 0 || id == MentionId.of(c.workId, p),
          s"$field: $id is not MentionId.of(workId, $p)"
        )
    val both =
      c.positions.map(_.leftPosition).toSet.intersect(c.positions.map(_.rightPosition).toSet)
    check(c.positions.nonEmpty, "positions: must not be empty") ++
      each("positions", c.positions)((f, p) =>
        position(s"$f.leftPosition", p.leftPosition) ++
          position(s"$f.rightPosition", p.rightPosition) ++
          check(
            p.leftPosition != p.rightPosition,
            s"$f: leftPosition and rightPosition must differ (got ${p.leftPosition})"
          ) ++
          mention(s"$f.leftMentionId", p.leftMentionId, p.leftPosition) ++
          mention(s"$f.rightMentionId", p.rightMentionId, p.rightPosition)
      ) ++
      check(
        c.positions.zip(c.positions.drop(1)).forall { case (a, b) =>
          PairPosition.ordering.compare(a, b) < 0
        },
        "positions: must be sorted by (leftPosition, rightPosition) and distinct"
      ) ++
      check(
        both.isEmpty,
        s"positions: positions ${both.toList.sorted.mkString("[", ", ", "]")} appear on both the " +
          "left and the right (left positions hold the left name, right positions the right name)"
      )
  }

  implicit val coauthorEdge: Validator[CoauthorEdge] = instance { e =>
    val pairOk = UndirectedPair.isCanonical(e.left.value, e.right.value)
    schema("CoauthorEdge", e.schemaVersion) ++
      stableId("edgeId", e.edgeId) ++
      stableId("left", e.left) ++
      stableId("right", e.right) ++
      ordered("left/right", e.left.value, e.right.value) ++
      window("window", e.window) ++
      check(
        !pairOk || EdgeId.coauthor(e.left, e.right, e.window) == Right(e.edgeId),
        s"edgeId: ${e.edgeId} is not EdgeId.coauthor(left, right, window)"
      ) ++
      finiteNonNegative("weight", e.weight) ++
      check(e.sharedWorkCount >= 1, s"sharedWorkCount: ${e.sharedWorkCount} must be >= 1") ++
      check(
        e.sharedWorkCount == e.supportingWorkIds.size,
        s"sharedWorkCount: ${e.sharedWorkCount} != supportingWorkIds size ${e.supportingWorkIds.size}"
      ) ++
      each("supportingWorkIds", e.supportingWorkIds)(stableId) ++
      distinct("supportingWorkIds", e.supportingWorkIds) ++
      check(
        e.firstYear <= e.lastYear,
        s"firstYear: ${e.firstYear} exceeds lastYear ${e.lastYear}"
      ) ++
      check(e.window.contains(e.firstYear), s"firstYear: ${e.firstYear} is outside window") ++
      check(e.window.contains(e.lastYear), s"lastYear: ${e.lastYear} is outside window") ++
      confidence("minIdentityConfidence", e.minIdentityConfidence) ++
      prov(e.provenance)
  }

  implicit val metricValue: Validator[MetricValue] =
    instance(m => finiteNonNegative(s"value(${m.metric.wire})", m.value))

  implicit val researcherMetrics: Validator[ResearcherMetrics] = instance { r =>
    schema("ResearcherMetrics", r.schemaVersion) ++
      stableId("researcherId", r.researcherId) ++
      window("window", r.window) ++
      check(r.values.nonEmpty, "values: must not be empty") ++
      distinct("values.metric", r.values.map(_.metric)) ++
      each("values", r.values)((f, m) => nested(f, metricValue.validate(m))) ++
      confidence("identityConfidence", r.identityConfidence) ++
      prov(r.provenance)
  }

  implicit val researcherGroupMembership: Validator[ResearcherGroupMembership] = instance { g =>
    val componentRules = g.groupKind match {
      case GroupKind.Component =>
        check(
          CodePointOrder.compare(g.groupId.value, g.researcherId.value) <= 0,
          s"groupId: ${g.groupId} of a component must be its smallest member ResearcherId, " +
            s"so it cannot exceed researcherId ${g.researcherId} by code point"
        ) ++
          check(
            g.groupSize != 1 || g.groupId == g.researcherId,
            s"groupId: a singleton component's groupId must equal researcherId ${g.researcherId}"
          )
      case GroupKind.Community => Nil
    }
    schema("ResearcherGroupMembership", g.schemaVersion) ++
      stableId("researcherId", g.researcherId) ++
      window("window", g.window) ++
      stableId("groupId", g.groupId) ++
      check(g.groupSize >= 1, s"groupSize: ${g.groupSize} must be >= 1") ++
      componentRules ++
      prov(g.provenance)
  }

  implicit val venue: Validator[Venue] = instance { v =>
    schema("Venue", v.schemaVersion) ++
      venueKey("venueKey", v.venueKey) ++
      nonBlank("name", v.name) ++
      fromDblp("Venue", v.provenance) ++
      prov(v.provenance)
  }

  implicit val institution: Validator[Institution] = instance { i =>
    schema("Institution", i.schemaVersion) ++
      openAlex(OpenAlexId.Institution)("institutionId", i.institutionId) ++
      assertedBy(
        "provenance.sourceType",
        ExternalIdType.OpenAlexInstitution,
        i.provenance.sourceType
      ) ++
      nonBlank("name", i.name) ++
      optional("countryCode", i.countryCode)((f, c) =>
        check(c.matches("[A-Z]{2}"), s"$f: '$c' is not an ISO 3166-1 alpha-2 code")
      ) ++
      prov(i.provenance)
  }

  implicit val topic: Validator[Topic] = instance { t =>
    schema("Topic", t.schemaVersion) ++
      check(
        t.topicId.matches("\\S+"),
        s"topicId: '${t.topicId}' must be non-empty, no whitespace"
      ) ++
      nonBlank("name", t.name) ++
      prov(t.provenance)
  }

  private def linkSubjectRule(kind: LinkSubjectKind, id: String): List[String] = kind match {
    case LinkSubjectKind.Work =>
      check(WorkId.isWellFormed(id), s"subjectId: '$id' is not a work ID")
    case LinkSubjectKind.Mention =>
      check(MentionId.isWellFormed(id), s"subjectId: '$id' is not a mention ID")
    case LinkSubjectKind.Venue => venueKey("subjectId", id)
  }

  implicit val externalIdLink: Validator[ExternalIdLink] = instance { l =>
    val p = l.provenance
    val expected =
      LinkId.of(
        l.subjectKind,
        l.subjectId,
        l.idType,
        l.externalId,
        p.sourceType,
        p.datasetId,
        p.snapshotId
      )
    schema("ExternalIdLink", l.schemaVersion) ++
      stableId("linkId", l.linkId) ++
      linkSubjectRule(l.subjectKind, l.subjectId) ++
      check(
        l.subjectKind.idTypes.contains(l.idType),
        s"idType: ${l.idType.wire} cannot be linked to a ${l.subjectKind.wire} " +
          s"(allowed: ${l.subjectKind.idTypes.map(_.wire).toList.sorted.mkString(", ")})"
      ) ++
      externalValue("externalId", l.idType, l.externalId) ++
      assertedBy("provenance.sourceType", l.idType, p.sourceType) ++
      check(
        p.sourceType != SourceType.Dblp,
        "provenance.sourceType: dblp-published identifiers are carried on WorkRecord.doi and " +
          "AuthorMention.orcid, not linked"
      ) ++
      check(
        l.linkId == expected,
        s"linkId: ${l.linkId} is not LinkId.of(subjectKind, subjectId, idType, externalId, " +
          "provenance.sourceType, provenance.datasetId, provenance.snapshotId)"
      ) ++
      prov(p)
  }

  private def features(fs: List[MatchFeature]): List[String] =
    each("features", fs)((f, x) =>
      nonBlank(s"$f.name", x.name) ++
        check(!x.value.isNaN && !x.value.isInfinite, s"$f.value: must be finite")
    ) ++ distinct("features.name", fs.map(_.name))

  private def featuresSorted(fs: List[MatchFeature]): List[String] =
    check(
      fs.map(_.name) == fs.map(_.name).sorted(CodePointOrder.ordering),
      "features: must be sorted by name (Unicode code point)"
    )

  private val CodeToken = "[A-Za-z0-9][A-Za-z0-9._:/-]*"
  private val FieldToken = """[A-Za-z0-9][A-Za-z0-9._:/\[\]-]*"""

  /** A sorted, distinct list of code tokens (rule names, reason codes, cited field paths). */
  private def tokens(field: String, xs: List[String], pattern: String): List[String] =
    each(field, xs)((f, x) => check(x.matches(pattern), s"$f: '$x' must match $pattern")) ++
      check(
        xs.zip(xs.drop(1)).forall { case (a, b) => CodePointOrder.lessThan(a, b) },
        s"$field: must be sorted by Unicode code point and distinct"
      )

  private def fromPipeline(record: String, p: Provenance): List[String] =
    check(
      p.sourceType == SourceType.Pipeline,
      s"provenance.sourceType: a $record is produced by entity resolution, so its source must " +
        s"be pipeline (got ${p.sourceType.wire})"
    )

  private def samePolicy(policyVersion: String, p: Provenance): List[String] =
    nonBlank("policyVersion", policyVersion) ++
      check(
        policyVersion == p.policyVersion,
        s"policyVersion: '$policyVersion' must equal provenance.policyVersion '${p.policyVersion}'"
      )

  private val UtcInstant = """[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z""".r.pattern

  /** `yyyy-MM-ddTHH:mm:ssZ`, a real UTC instant (no fractional seconds, no offset other than Z). */
  private[core] def utcInstant(field: String, s: String): List[String] = {
    val parses =
      UtcInstant.matcher(s).matches() &&
        scala.util.Try(java.time.Instant.parse(s)).toOption.exists(_.toString == s)
    check(parses, s"$field: '$s' must be a UTC instant yyyy-MM-ddTHH:mm:ssZ")
  }

  private def matchSubjects(kind: MatchSubjectKind, left: String, right: String): List[String] =
    kind match {
      case MatchSubjectKind.MentionPair =>
        mentionSubject("left", left) ++
          mentionSubject("right", right) ++
          ordered("left/right", left, right)
      case MatchSubjectKind.MentionResearcher =>
        mentionSubject("left", left) ++ researcherSubject("right", right)
    }

  implicit val matchDecision: Validator[MatchDecision] = instance { m =>
    val candidateRule = m.subjectKind match {
      case MatchSubjectKind.MentionPair =>
        check(m.candidateId.nonEmpty, "candidateId: required for a mention_pair decision")
      case MatchSubjectKind.MentionResearcher =>
        check(
          m.candidateId.nonEmpty ||
            m.outcome == MatchOutcome.Accepted || m.outcome == MatchOutcome.Conflict,
          s"outcome: an identity assignment (mention_researcher without candidateId) must be " +
            s"accepted or conflict (got ${m.outcome.wire})"
        )
    }
    val pathRule = m.matchPath match {
      case MatchPath.Llm =>
        check(m.modelVersion.nonEmpty, "modelVersion: required for an llm decision") ++
          check(m.promptVersion.nonEmpty, "promptVersion: required for an llm decision")
      case other =>
        check(m.modelVersion.isEmpty, s"modelVersion: must be null for a ${other.wire} decision") ++
          check(
            m.promptVersion.isEmpty,
            s"promptVersion: must be null for a ${other.wire} decision"
          ) ++
          check(
            (other != MatchPath.ExactId && other != MatchPath.Deterministic) ||
              m.rulesFired.nonEmpty,
            s"rulesFired: a ${other.wire} decision must name the rule that fired"
          )
    }
    val reasonRule = check(
      (m.outcome != MatchOutcome.Unresolved && m.outcome != MatchOutcome.Conflict) ||
        m.reasonCodes.nonEmpty,
      s"reasonCodes: a ${m.outcome.wire} decision must carry at least one reason code"
    )
    schema("MatchDecision", m.schemaVersion) ++
      matchSubjects(m.subjectKind, m.left, m.right) ++
      optional("candidateId", m.candidateId)(stableId) ++
      candidateRule ++
      pathRule ++
      reasonRule ++
      confidence("confidence", m.confidence) ++
      features(m.features) ++
      featuresSorted(m.features) ++
      tokens("rulesFired", m.rulesFired, CodeToken) ++
      tokens("reasonCodes", m.reasonCodes, CodeToken) ++
      optional("modelVersion", m.modelVersion)(nonBlank) ++
      optional("promptVersion", m.promptVersion)(nonBlank) ++
      samePolicy(m.policyVersion, m.provenance) ++
      utcInstant("decisionTime", m.decisionTime) ++
      fromPipeline("MatchDecision", m.provenance) ++
      prov(m.provenance)
  }

  implicit val llmAdjudication: Validator[LlmAdjudication] = instance { a =>
    val offered = a.offeredCandidateIds.map(_.value)
    val idRule =
      LlmAdjudicationId.of(a.inputHash, a.promptVersion, a.modelVersion, a.policyVersion) match {
        case Right(id) =>
          check(
            id == a.adjudicationId,
            s"adjudicationId: ${a.adjudicationId} is not LlmAdjudicationId.of(inputHash, " +
              "promptVersion, modelVersion, policyVersion)"
          )
        case Left(_) => Nil // blank inputs are reported by their own rules
      }
    val choiceRule = a.outcome match {
      case AdjudicationOutcome.Accept =>
        a.chosenCandidateId match {
          case None    => List("chosenCandidateId: required when outcome is accept")
          case Some(c) =>
            check(
              a.offeredCandidateIds.contains(c),
              s"chosenCandidateId: ${c.value} is not one of offeredCandidateIds"
            )
        }
      case other =>
        check(
          a.chosenCandidateId.isEmpty,
          s"chosenCandidateId: must be null when outcome is ${other.wire}"
        )
    }
    schema("LlmAdjudication", a.schemaVersion) ++
      stableId("adjudicationId", a.adjudicationId) ++
      check(
        ContentHash.isWellFormed(a.inputHash),
        s"inputHash: '${a.inputHash}' must be sha256:<64 lowercase hex>"
      ) ++
      check(
        a.provenance.contentHash.contains(a.inputHash),
        "provenance.contentHash: must equal inputHash"
      ) ++
      idRule ++
      check(a.offeredCandidateIds.nonEmpty, "offeredCandidateIds: must not be empty") ++
      each("offeredCandidateIds", a.offeredCandidateIds)(stableId) ++
      check(
        offered.zip(offered.drop(1)).forall { case (x, y) => CodePointOrder.lessThan(x, y) },
        "offeredCandidateIds: must be sorted by Unicode code point and distinct"
      ) ++
      optional("chosenCandidateId", a.chosenCandidateId)(stableId) ++
      choiceRule ++
      tokens("citedInputFields", a.citedInputFields, FieldToken) ++
      check(
        a.outcome == AdjudicationOutcome.Abstain || a.citedInputFields.nonEmpty,
        s"citedInputFields: a ${a.outcome.wire} adjudication must cite at least one input field"
      ) ++
      confidence("confidence", a.confidence) ++
      tokens("reasonCodes", a.reasonCodes, CodeToken) ++
      nonBlank("promptVersion", a.promptVersion) ++
      nonBlank("modelVersion", a.modelVersion) ++
      samePolicy(a.policyVersion, a.provenance) ++
      fromPipeline("LlmAdjudication", a.provenance) ++
      prov(a.provenance)
  }

  private def mentionSubject(field: String, id: String): List[String] =
    check(MentionId.isWellFormed(id), s"$field: '$id' is not a mention ID")

  private def researcherSubject(field: String, id: String): List[String] =
    check(ResearcherId.isWellFormed(id), s"$field: '$id' is not a researcher ID")

  implicit val matchCandidate: Validator[MatchCandidate] = instance { m =>
    val subjects = m.subjectKind match {
      case MatchSubjectKind.MentionPair =>
        mentionSubject("left", m.left) ++
          mentionSubject("right", m.right) ++
          ordered("left/right", m.left, m.right)
      case MatchSubjectKind.MentionResearcher =>
        mentionSubject("left", m.left) ++ researcherSubject("right", m.right)
    }
    val idRule =
      MatchCandidateId.of(m.subjectKind, m.left, m.right, m.blockFamily, m.blockKey) match {
        case Right(id) =>
          check(
            id == m.candidateId,
            s"candidateId: ${m.candidateId} is not MatchCandidateId.of(subjectKind, left, right, " +
              "blockFamily, blockKey)"
          )
        case Left(_) => Nil // self pair, reported by the ordering rule
      }
    schema("MatchCandidate", m.schemaVersion) ++
      stableId("candidateId", m.candidateId) ++
      subjects ++
      idRule ++
      check(
        m.blockFamily.matches("[a-z0-9][a-z0-9._-]*"),
        s"blockFamily: '${m.blockFamily}' must match [a-z0-9][a-z0-9._-]*"
      ) ++
      nonBlank("blockKey", m.blockKey) ++
      check(
        !m.blockKey.exists(Character.isISOControl),
        "blockKey: must not contain control characters"
      ) ++
      check(m.features.nonEmpty, "features: must not be empty") ++
      features(m.features) ++
      check(
        m.features.map(_.name) == m.features.map(_.name).sorted(CodePointOrder.ordering),
        "features: must be sorted by name (Unicode code point)"
      ) ++
      prov(m.provenance)
  }

  private def nodeIdRule(field: String, label: GraphNodeLabel, id: String): List[String] =
    label match {
      case GraphNodeLabel.Researcher =>
        check(ResearcherId.isWellFormed(id), s"$field: '$id' is not a researcher ID")
      case GraphNodeLabel.Work =>
        check(WorkId.isWellFormed(id), s"$field: '$id' is not a work ID")
      case GraphNodeLabel.Venue       => venueKey(field, id)
      case GraphNodeLabel.Institution => openAlex(OpenAlexId.Institution)(field, id)
      case GraphNodeLabel.Topic       =>
        check(id.matches("\\S+"), s"$field: '$id' must be non-empty, no whitespace")
      case GraphNodeLabel.AuthorMention =>
        check(MentionId.isWellFormed(id), s"$field: '$id' is not a mention ID")
      case GraphNodeLabel.DatasetSnapshot =>
        check(id.matches("\\S+"), s"$field: '$id' must be non-empty, no whitespace")
      case GraphNodeLabel.TextChunk =>
        check(ChunkId.isWellFormed(id), s"$field: '$id' is not a chunk ID")
    }

  implicit val graphNode: Validator[GraphNode] = instance { n =>
    schema("GraphNode", n.schemaVersion) ++
      nodeIdRule("nodeId", n.label, n.nodeId) ++
      nonBlank("displayName", n.displayName) ++
      each("properties", n.properties.keys.toList.sorted)(nonBlank) ++
      prov(n.provenance)
  }

  implicit val graphEdge: Validator[GraphEdge] = instance { e =>
    val pairRule =
      if (e.label.undirected) ordered("from/to", e.from, e.to) else Nil
    val idRule = EdgeId.of(e.label, e.from, e.to, e.window) match {
      case Right(id) =>
        check(id == e.edgeId, s"edgeId: ${e.edgeId} is not EdgeId.of(label, from, to, window)")
      case Left(_) => Nil // reported by pairRule
    }
    schema("GraphEdge", e.schemaVersion) ++
      stableId("edgeId", e.edgeId) ++
      nodeIdRule("from", e.label.fromLabel, e.from) ++
      nodeIdRule("to", e.label.toLabel, e.to) ++
      pairRule ++
      window("window", e.window) ++
      idRule ++
      optional("weight", e.weight)(finiteNonNegative) ++
      prov(e.provenance)
  }

  // ---- semantic documents and vector metadata (Phase 7) ------------------------------------

  private def indexNameRule(field: String, s: String): List[String] =
    check(VectorIndexName.isValid(s), s"$field: '$s' must match ${VectorIndexName.Pattern}")

  private def vectorKeyRule(field: String, s: String): List[String] =
    nonBlank(field, s) ++
      check(
        VectorKey.utf8Length(s) <= VectorKey.MaxUtf8Bytes,
        s"$field: ${VectorKey.utf8Length(s)} UTF-8 bytes exceeds ${VectorKey.MaxUtf8Bytes}"
      )

  private def researcherIdText(field: String, s: String): List[String] =
    check(ResearcherId.isWellFormed(s), s"$field: '$s' is not a researcher ID")

  private def semanticSubjects(t: SemanticDocumentType, ids: List[String]): List[String] = {
    val allResearchers = each("subjectIds", ids)(researcherIdText)
    t match {
      case SemanticDocumentType.ResearcherProfile =>
        check(ids.size == 1, s"subjectIds: researcher_profile needs exactly 1, got ${ids.size}") ++
          allResearchers
      case SemanticDocumentType.CommunitySummary  => allResearchers
      case SemanticDocumentType.CollaborationPath =>
        check(
          ids.size >= 2,
          s"subjectIds: collaboration_path needs at least 2, got ${ids.size}"
        ) ++ allResearchers
      case SemanticDocumentType.PaperSummary =>
        ids.headOption.toList.flatMap(h =>
          check(WorkId.isWellFormed(h), s"subjectIds[0]: '$h' is not a work ID")
        )
      case SemanticDocumentType.TopicInstitutionSummary => Nil
    }
  }

  implicit val semanticDocument: Validator[SemanticDocument] = instance { d =>
    schema("SemanticDocument", d.schemaVersion) ++
      stableId("documentId", d.documentId) ++
      indexNameRule("indexName", d.indexName) ++
      vectorKeyRule("vectorKey", d.vectorKey) ++
      check(d.subjectIds.nonEmpty, "subjectIds: must not be empty") ++
      each("subjectIds", d.subjectIds)(nonBlank) ++
      semanticSubjects(d.documentType, d.subjectIds) ++
      check(
        !SemanticDocumentType.requiresWindow.contains(d.documentType) || d.window.nonEmpty,
        s"window: required for ${d.documentType.wire} documents"
      ) ++
      optional("window", d.window)(window) ++
      nonBlank("text", d.text) ++
      textHash(d.provenance, d.text, "text") ++
      optional("sourceEvidenceId", d.sourceEvidenceId)(stableId) ++
      prov(d.provenance)
  }

  /** Every string value in a record tree longer than `max` characters (stable IDs included). */
  private def longStrings(path: String, value: Any, max: Int): List[String] = value match {
    case s: String =>
      check(s.length <= max, s"$path: ${s.length} characters exceeds the $max-character limit")
    case id: StableId    => longStrings(path, id.value, max)
    case _: WireEnum     => Nil
    case o: Option[_]    => o.toList.flatMap(longStrings(path, _, max))
    case it: Iterable[_] =>
      it.toList.zipWithIndex.flatMap { case (v, i) => longStrings(s"$path[$i]", v, max) }
    case p: Product =>
      p.productElementNames
        .zip(p.productIterator)
        .toList
        .flatMap { case (n, v) => longStrings(if (path.isEmpty) n else s"$path.$n", v, max) }
    case _ => Nil
  }

  private val S3Uri = """s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/\S+""".r.pattern

  implicit val vectorMetadata: Validator[VectorMetadata] = instance { m =>
    val windowYears = (m.windowStartYear, m.windowEndYear) match {
      case (Some(s), Some(e)) => window("windowStartYear/windowEndYear", YearWindow(s, e))
      case (None, None)       =>
        check(
          !SemanticDocumentType.requiresWindow.contains(m.documentType),
          s"windowStartYear/windowEndYear: required for ${m.documentType.wire} vectors"
        )
      case _ => List("windowStartYear/windowEndYear: must be both present or both absent")
    }
    def required(field: String, o: Option[_]): List[String] =
      check(o.nonEmpty, s"$field: required for ${m.documentType.wire} vectors")
    val subjects = m.documentType match {
      case SemanticDocumentType.ResearcherProfile => required("researcherId", m.researcherId)
      case SemanticDocumentType.CommunitySummary  => required("communityId", m.communityId)
      case SemanticDocumentType.CollaborationPath =>
        required("startResearcherId", m.startResearcherId) ++
          required("endResearcherId", m.endResearcherId) ++
          required("pathLength", m.pathLength) ++
          check(
            m.startResearcherId.isEmpty || m.startResearcherId != m.endResearcherId,
            "startResearcherId/endResearcherId: a path needs two different endpoints"
          )
      case SemanticDocumentType.PaperSummary            => required("workId", m.workId)
      case SemanticDocumentType.TopicInstitutionSummary =>
        check(
          m.topicId.nonEmpty || m.institutionId.nonEmpty,
          "topicId/institutionId: one is required for topic_institution_summary vectors"
        )
    }
    schema("VectorMetadata", m.schemaVersion) ++
      vectorKeyRule("vectorKey", m.vectorKey) ++
      indexNameRule("indexName", m.indexName) ++
      stableId("documentId", m.documentId) ++
      optional("researcherId", m.researcherId)(stableId) ++
      optional("communityId", m.communityId)(stableId) ++
      optional("startResearcherId", m.startResearcherId)(stableId) ++
      optional("endResearcherId", m.endResearcherId)(stableId) ++
      optional("workId", m.workId)(stableId) ++
      optional("topicId", m.topicId)((f, t) =>
        check(t.matches("\\S+"), s"$f: '$t' must be non-empty, no whitespace")
      ) ++
      optional("institutionId", m.institutionId)(openAlex(OpenAlexId.Institution)) ++
      subjects ++
      windowYears ++
      optional("year", m.year)(year) ++
      optional("venueKey", m.venueKey)(venueKey) ++
      optional("pathLength", m.pathLength)((f, n) => check(n >= 1, s"$f: $n must be >= 1")) ++
      datasetIdRule("sourceDataset", m.sourceDataset) ++
      check(
        S3Uri.matcher(m.textS3Uri).matches(),
        s"textS3Uri: '${m.textS3Uri}' must be s3://<bucket>/<key>"
      ) ++
      check(
        ContentHash.isWellFormed(m.textHash),
        s"textHash: '${m.textHash}' must be sha256:<64 lowercase hex>"
      ) ++
      check(
        m.provenance.contentHash.contains(m.textHash),
        "provenance.contentHash: must equal textHash"
      ) ++
      optional("sourceEvidenceId", m.sourceEvidenceId)(stableId) ++
      check(
        m.embeddingModel.matches("\\S+"),
        s"embeddingModel: '${m.embeddingModel}' must be non-empty, no whitespace"
      ) ++
      check(
        m.embeddingDimensions >= VectorMetadata.MinDimensions &&
          m.embeddingDimensions <= VectorMetadata.MaxDimensions,
        s"embeddingDimensions: ${m.embeddingDimensions} is outside " +
          s"${VectorMetadata.MinDimensions}..${VectorMetadata.MaxDimensions}"
      ) ++
      stableId("runId", m.runId) ++
      check(
        m.runId == m.provenance.runId,
        s"runId: ${m.runId} must equal provenance.runId ${m.provenance.runId}"
      ) ++
      longStrings("", m, VectorMetadata.MaxFieldChars) ++
      prov(m.provenance)
  }

  implicit val evidenceItem: Validator[EvidenceItem] = instance { i =>
    schema("EvidenceItem", i.schemaVersion) ++
      stableId("evidenceId", i.evidenceId) ++
      check(i.subjectIds.nonEmpty, "subjectIds: must not be empty") ++
      each("subjectIds", i.subjectIds)(nonBlank) ++
      check(
        i.evidenceId == EvidenceId.of(i.evidenceType, i.provenance.runId, i.subjectIds),
        s"evidenceId: ${i.evidenceId} is not EvidenceId.of(evidenceType, provenance.runId, subjectIds)"
      ) ++
      check(
        !EvidenceType.requiresWindow.contains(i.evidenceType) || i.window.nonEmpty,
        s"window: required for ${i.evidenceType.wire} evidence"
      ) ++
      optional("window", i.window)(window) ++
      check(
        i.evidenceType != EvidenceType.Metric || i.metric.nonEmpty,
        "metric: required for metric evidence"
      ) ++
      optional("metric", i.metric)((f, m) => nested(f, metricValue.validate(m))) ++
      optional("snippet", i.snippet)((f, s) =>
        nonBlank(f, s) ++ textHash(i.provenance, s, "snippet")
      ) ++
      prov(i.provenance)
  }

  implicit val evidenceBundle: Validator[EvidenceBundle] = instance { b =>
    val ids = b.items.map(_.evidenceId)
    schema("EvidenceBundle", b.schemaVersion) ++
      stableId("bundleId", b.bundleId) ++
      check(b.items.nonEmpty, "items: must not be empty") ++
      distinct("items.evidenceId", ids) ++
      check(
        b.bundleId == EvidenceId.forBundle(b.provenance.runId, ids),
        s"bundleId: ${b.bundleId} is not EvidenceId.forBundle(provenance.runId, item IDs)"
      ) ++
      each("items", b.items)((f, i) => nested(f, evidenceItem.validate(i))) ++
      prov(b.provenance)
  }

  private def queryStep(field: String, s: QueryStep): List[String] = {
    val countRule = s.operation match {
      case QueryOperation.ShortestPath =>
        check(s.researcherIds.size == 2, s"$field.researcherIds: shortest_path needs exactly 2")
      case op if QueryOperation.mayOmitResearchers.contains(op) => Nil
      case _                                                    =>
        check(s.researcherIds.nonEmpty, s"$field.researcherIds: must not be empty")
    }
    countRule ++
      each(s"$field.researcherIds", s.researcherIds)(stableId) ++
      check(
        !QueryOperation.requiresWindow.contains(s.operation) || s.window.nonEmpty,
        s"$field.window: required for ${s.operation.wire}"
      ) ++
      optional(s"$field.window", s.window)(window) ++
      each(s"$field.parameters", s.parameters.keys.toList.sorted)(nonBlank)
  }

  implicit val queryPlan: Validator[QueryPlan] = instance { q =>
    schema("QueryPlan", q.schemaVersion) ++
      nonBlank("question", q.question) ++
      stableId("graphRunId", q.graphRunId) ++
      check(q.steps.nonEmpty, "steps: must not be empty") ++
      each("steps", q.steps)(queryStep) ++
      textHash(q.provenance, q.question, "question") ++
      prov(q.provenance)
  }

  implicit val answerClaim: Validator[AnswerClaim] = instance { c =>
    schema("AnswerClaim", c.schemaVersion) ++
      check(
        AnswerClaim.isValidClaimId(c.claimId),
        s"claimId: '${c.claimId}' must match ${AnswerClaim.ClaimIdPattern}"
      ) ++
      nonBlank("claimText", c.claimText) ++
      check(
        c.status == VerificationStatus.Unsupported || c.evidenceIds.nonEmpty,
        s"evidenceIds: a ${c.status.wire} claim must cite evidence"
      ) ++
      each("evidenceIds", c.evidenceIds)(stableId) ++
      distinct("evidenceIds", c.evidenceIds) ++
      optional("window", c.window)(window) ++
      optional("uncertainty", c.uncertainty)(nonBlank) ++
      textHash(c.provenance, c.claimText, "claimText") ++
      prov(c.provenance)
  }

  implicit val answerResponse: Validator[AnswerResponse] = instance { r =>
    val bundleIds = r.bundle.toList.flatMap(_.items.map(_.evidenceId)).toSet
    val claimIds = r.claims.map(_.claimId)
    val abstentionRule =
      if (r.abstained)
        check(
          r.abstentionReason.nonEmpty,
          "abstentionReason: required when abstained is true"
        ) ++ optional("abstentionReason", r.abstentionReason)(nonBlank)
      else
        check(
          r.abstentionReason.isEmpty,
          "abstentionReason: must be null when abstained is false"
        ) ++
          check(r.bundle.nonEmpty, "bundle: required unless the response abstains") ++
          check(r.claims.nonEmpty, "claims: must not be empty unless the response abstains")
    schema("AnswerResponse", r.schemaVersion) ++
      nonBlank("question", r.question) ++
      nonBlank("answerText", r.answerText) ++
      optional("modelId", r.modelId)(nonBlank) ++
      check(r.question == r.plan.question, "plan.question: must equal question") ++
      nested("plan", queryPlan.validate(r.plan)) ++
      optional("bundle", r.bundle)((f, b) => nested(f, evidenceBundle.validate(b))) ++
      abstentionRule ++
      each("claims", r.claims)((f, c) => nested(f, answerClaim.validate(c))) ++
      check(
        claimIds.distinct.size == claimIds.size,
        s"claims.claimId: claim IDs must be unique within the response " +
          s"(duplicates: ${claimIds.diff(claimIds.distinct).distinct.mkString(", ")})"
      ) ++
      each("claims", r.claims)((f, c) =>
        c.evidenceIds.filterNot(bundleIds).map(id => s"$f.evidenceIds: $id is not in bundle")
      ) ++
      each("identityNotes", r.identityNotes)(nonBlank) ++
      textHash(r.provenance, r.answerText, "answerText") ++
      prov(r.provenance)
  }

  implicit val queryAudit: Validator[QueryAudit] = instance { a =>
    def hash(field: String, h: String): List[String] =
      check(ContentHash.isWellFormed(h), s"$field: '$h' must be sha256:<64 lowercase hex>")
    def nonNegative(field: String, n: Long): List[String] =
      check(n >= 0L, s"$field: $n must be >= 0")
    val statusNames = VerificationStatus.values.map(_.wire)
    schema("QueryAudit", a.schemaVersion) ++
      stableId("queryRunId", a.queryRunId) ++
      hash("questionHash", a.questionHash) ++
      nested("plan", queryPlan.validate(a.plan)) ++
      check(
        a.plan.graphRunId == a.graphRunId,
        s"plan.graphRunId: ${a.plan.graphRunId} must equal graphRunId ${a.graphRunId}"
      ) ++
      each("resolvedResearcherIds", a.resolvedResearcherIds)(stableId) ++
      distinct("resolvedResearcherIds", a.resolvedResearcherIds) ++
      each("unresolvedNames", a.unresolvedNames)(nonBlank) ++
      each("evidenceIds", a.evidenceIds)(stableId) ++
      distinct("evidenceIds", a.evidenceIds) ++
      optional("bundleId", a.bundleId)(stableId) ++
      stableId("graphRunId", a.graphRunId) ++
      each("metricPolicyVersions", a.metricPolicyVersions)(nonBlank) ++
      each("promptVersions", a.promptVersions)((f, p) =>
        check(
          QueryAudit.isValidPromptVersion(p),
          s"$f: '$p' must match ${QueryAudit.PromptVersionPattern}"
        )
      ) ++
      distinct("promptVersions", a.promptVersions) ++
      optional("modelId", a.modelId)(nonBlank) ++
      optional("inputTokens", a.inputTokens)(nonNegative) ++
      optional("outputTokens", a.outputTokens)(nonNegative) ++
      nonNegative("latencyMillis", a.latencyMillis) ++
      each("stageMillis", a.stageMillis.keys.toList.sorted)(nonBlank) ++
      a.stageMillis.toList.sortBy(_._1).flatMap { case (k, v) =>
        nonNegative(s"stageMillis[$k]", v)
      } ++
      a.verificationCounts.toList.sortBy(_._1).flatMap { case (k, v) =>
        check(
          statusNames.contains(k),
          s"verificationCounts: '$k' is not a verification status " +
            s"(expected one of: ${statusNames.mkString(", ")})"
        ) ++ nonNegative(s"verificationCounts[$k]", v.toLong)
      } ++
      hash("answerHash", a.answerHash) ++
      check(
        a.provenance.sourceType == SourceType.Runtime,
        s"provenance.sourceType: a QueryAudit is produced by the runtime, so its source must be " +
          s"runtime (got ${a.provenance.sourceType.wire})"
      ) ++
      check(
        a.provenance.runId == a.queryRunId,
        s"provenance.runId: ${a.provenance.runId} must equal queryRunId ${a.queryRunId}"
      ) ++
      prov(a.provenance)
  }
}
