package org.scholarchain.storage.dynamo

import org.scholarchain.core.Hashing
import org.scholarchain.core.config.DynamoDbSettings

/** One documented access pattern: who reads or writes, and with which key condition. */
final case class AccessPattern(
    id: String,
    description: String,
    operation: String,
    keyCondition: String
)

/** Key design of one DynamoDB table. Every table uses string attributes `pk` (partition key) and
  * `sk` (sort key); there are no secondary indexes in Phase 7.
  *
  * @param appendOnly
  *   items are only ever created (put-if-absent); never replaced
  * @param ttlAttribute
  *   the TTL attribute (epoch seconds) when items are genuinely disposable
  */
final case class TableSpec(
    logicalName: String,
    pkFormat: String,
    skFormat: String,
    appendOnly: Boolean,
    ttlAttribute: Option[String],
    accessPatterns: List[AccessPattern],
    tableName: DynamoDbSettings => String
)

object TableSpecs {

  val RunLedger: TableSpec = TableSpec(
    "RunLedger",
    "run#<runId>",
    "attempt#<NNNNNN>#<seq NNNNNNNN>#<eventType>   (attempt claim: attempt#<NNNNNN>#00000000#claim)",
    appendOnly = true,
    ttlAttribute = None,
    List(
      AccessPattern("RL1", "All events of one run, in order", "Query", "pk = run#<runId>"),
      AccessPattern(
        "RL2",
        "Events of one attempt of a run",
        "Query",
        "pk = run#<runId> AND begins_with(sk, attempt#<NNNNNN>#)"
      ),
      AccessPattern(
        "RL3",
        "Claim the next attempt number of a run (conditional put, retried with n+1 on conflict)",
        "PutItem attribute_not_exists(pk)",
        "pk = run#<runId>, sk = attempt#<NNNNNN>#00000000#claim"
      ),
      AccessPattern(
        "RL4",
        "Append an event (load summary, vector reject, overwrite old/new hash, failure with retry count)",
        "PutItem attribute_not_exists(pk)",
        "pk = run#<runId>, sk = attempt#<NNNNNN>#<seq>#<eventType>"
      )
    ),
    _.runLedgerTable
  )

  val EntityLinkCache: TableSpec = TableSpec(
    "EntityLinkCache",
    "name#<folded dblp name> | orcid#<ORCID iD>",
    "researcher#<researcherId>",
    appendOnly = false,
    ttlAttribute = None,
    List(
      AccessPattern(
        "EL1",
        "Resolve a typed researcher name to candidate researcher IDs (author-name-key/1 folding)",
        "Query (Limit n)",
        "pk = name#<folded name>"
      ),
      AccessPattern("EL2", "Resolve an ORCID iD to researcher IDs", "Query", "pk = orcid#<iD>"),
      AccessPattern(
        "EL3",
        "Check one (name, researcher) link",
        "GetItem",
        "pk = name#<folded name>, sk = researcher#<researcherId>"
      )
    ),
    _.entityLinkCacheTable
  )

  val QuerySession: TableSpec = TableSpec(
    "QuerySession",
    "session#<sessionId> | job#<jobId> (QS4) | idempotency#<sha256 hex of (scope, key)> (QS5)",
    "turn#<NNNNNN> | job (QS4) | idempotency (QS5)",
    appendOnly = false,
    ttlAttribute = Some("expiresAt"),
    List(
      AccessPattern("QS1", "Load the turns of one session in order", "Query", "pk = session#<id>"),
      AccessPattern(
        "QS2",
        "Append the next turn of a session",
        "PutItem attribute_not_exists(pk)",
        "pk = session#<id>, sk = turn#<NNNNNN>"
      ),
      AccessPattern(
        "QS3",
        "Expire abandoned sessions, jobs and idempotency records (DynamoDB TTL on expiresAt, " +
          "epoch seconds; all three are disposable; readers treat an expired item as absent)",
        "TTL",
        "expiresAt < now"
      ),
      AccessPattern(
        "QS4",
        "One job (Phase 10): read it; create it put-if-absent; change it (claim, reclaim, " +
          "release, succeed with its result, fail, stage) only at the version the caller read",
        "GetItem; PutItem attribute_not_exists(pk); PutItem attribute_exists(pk) AND version = :read",
        "pk = job#<jobId>, sk = job"
      ),
      AccessPattern(
        "QS5",
        "One idempotency record (Phase 10): claim it put-if-absent (a losing claim reads the " +
          "existing record; an expired record is replaced at its version); read it",
        "GetItem; PutItem attribute_not_exists(pk); PutItem attribute_exists(pk) AND version = :read",
        "pk = idempotency#<sha256 hex of canonical (scope, Idempotency-Key)>, sk = idempotency"
      )
    ),
    _.querySessionTable
  )

  val EvidenceBundle: TableSpec = TableSpec(
    "EvidenceBundle",
    "evidence#<evidenceId>",
    "window#<startYear>-<endYear> | window#none (item without a window) | bundle (an answer bundle)",
    appendOnly = false,
    ttlAttribute = None,
    List(
      AccessPattern(
        "EB1",
        "Fetch one cited evidence item in one window (EvidenceId ignores the window)",
        "GetItem",
        "pk = evidence#<evidenceId>, sk = window#<start>-<end>"
      ),
      AccessPattern(
        "EB2",
        "Fetch every window of one evidence ID",
        "Query",
        "pk = evidence#<evidenceId>"
      ),
      AccessPattern(
        "EB3",
        "Fetch an answer's evidence bundle (Phase 9)",
        "GetItem",
        "pk = evidence#<bundleId>, sk = bundle"
      )
    ),
    _.evidenceBundleTable
  )

  val ArtifactRegistry: TableSpec = TableSpec(
    "ArtifactRegistry",
    "artifact#<kind>#<key>   (vector: artifact#vector#<index>/<vectorKey>)",
    "v#<NNNNNNNN> (version, from 00000001)",
    appendOnly = true,
    ttlAttribute = None,
    List(
      AccessPattern(
        "AR1",
        "Latest registered version of an artifact (vector key: text hash, embedding model, dimensions)",
        "Query ScanIndexForward=false Limit 1",
        "pk = artifact#<kind>#<key>"
      ),
      AccessPattern(
        "AR2",
        "Full version history of an artifact",
        "Query",
        "pk = artifact#<kind>#<key>"
      ),
      AccessPattern(
        "AR3",
        "Register the next version (conditional put; a concurrent writer gets a conflict)",
        "PutItem attribute_not_exists(pk)",
        "pk = artifact#<kind>#<key>, sk = v#<n+1>"
      )
    ),
    _.artifactRegistryTable
  )

  val ResearcherMetricCache: TableSpec = TableSpec(
    "ResearcherMetricCache",
    "researcher#<researcherId> | ranking#<windowLabel>#<metric wire name> (RM4)",
    "window#<windowLabel> | rank#<NNNN> (RM4, rank from 0001)",
    appendOnly = false,
    ttlAttribute = None,
    List(
      AccessPattern(
        "RM1",
        "Breadth, group-size, bridge and k-core metrics of one researcher in one window",
        "GetItem",
        "pk = researcher#<researcherId>, sk = window#<windowLabel>"
      ),
      AccessPattern(
        "RM2",
        "Every window of one researcher (trend over windows)",
        "Query",
        "pk = researcher#<researcherId>"
      ),
      AccessPattern(
        "RM3",
        "Idempotent reload: insert if absent, replace only when the item hash changed (version check)",
        "PutItem with condition",
        "pk, sk as RM1"
      ),
      AccessPattern(
        "RM4",
        "Top-K researchers of one window by one ranked metric (runtime.rankingCacheTopK per window " +
          "and metric; value descending, then researcherId; communities_connected: value, then " +
          "cross_community_degree descending, then researcherId). Upsert-if-changed like RM3.",
        "Query Limit k",
        "pk = ranking#<windowLabel>#<metric>, begins_with(sk, rank#)"
      )
    ),
    _.researcherMetricCacheTable
  )

  val all: List[TableSpec] =
    List(
      RunLedger,
      EntityLinkCache,
      QuerySession,
      EvidenceBundle,
      ArtifactRegistry,
      ResearcherMetricCache
    )

  def runPk(runId: String): String = s"run#$runId"
  def attemptPrefix(attempt: Int): String = f"attempt#$attempt%06d#"
  def ledgerSk(attempt: Int, seq: Long, eventType: String): String =
    f"attempt#$attempt%06d#$seq%08d#$eventType"
  def namePk(folded: String): String = s"name#$folded"
  def orcidPk(orcid: String): String = s"orcid#$orcid"
  def researcherSk(researcherId: String): String = s"researcher#$researcherId"
  def sessionPk(sessionId: String): String = s"session#$sessionId"
  def turnSk(turn: Int): String = f"turn#$turn%06d"
  def jobPk(jobId: String): String = s"job#$jobId"
  val JobSk = "job"

  /** QS5 partition key: the sha256 of the canonical encoding of (caller scope, Idempotency-Key), so
    * neither value is stored in clear and two scopes never share a key.
    */
  def idempotencyPk(scope: String, idempotencyKey: String): String =
    "idempotency#" + Hashing.sha256Hex(Hashing.canonicalEncoding(Seq(scope, idempotencyKey)))
  val IdempotencySk = "idempotency"
  def evidencePk(evidenceId: String): String = s"evidence#$evidenceId"
  def windowSk(start: Int, end: Int): String = s"window#$start-$end"
  val NoWindowSk = "window#none"
  val BundleSk = "bundle"
  def artifactPk(kind: String, key: String): String = s"artifact#$kind#$key"
  def versionSk(version: Long): String = f"v#$version%08d"
  def researcherPk(researcherId: String): String = s"researcher#$researcherId"
  def windowLabelSk(label: String): String = s"window#$label"
  val WindowSkPrefix = "window#"
  def rankingPk(windowLabel: String, metricWire: String): String =
    s"ranking#$windowLabel#$metricWire"
  def rankSk(rank: Int): String = f"rank#$rank%04d"
  val RankSkPrefix = "rank#"
}
