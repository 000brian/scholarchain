package org.scholarchain.services

import org.scholarchain.storage.jobs.JobLimits

/** Fixed constants of the REST services (decision 7i: defined once, named, with their reason).
  * Every other bound comes from the `service` and `runtime` configuration sections.
  */
object ServiceLimits {

  /** Overall deadline of one `/readyz` probe. A load balancer health check must answer quickly; two
    * seconds is long enough for one cheap read per store and short enough that a hung store reads
    * as not ready instead of hanging the probe.
    */
  val ReadinessDeadlineMillis: Long = 2000L

  /** `/readyz` answers from its last result for this long, so frequent health checks (several load
    * balancer nodes, every few seconds) cost at most one probe per five seconds.
    */
  val ReadinessCacheMillis: Long = 5000L

  /** Default `limit` of `GET /v1/graph/nodes/{id}/neighbors`: a page small enough for one screen,
    * capped by `runtime.graphResultLimit` when that is lower.
    */
  val DefaultNeighborLimit: Int = 20

  /** Largest neighbourhood depth the API accepts as a value (the spec's `depth=1..3`). Only depth 1
    * is served in this phase; 2 and 3 are `UNSUPPORTED_DEPTH`.
    */
  val MaxNeighborDepth: Int = 3

  /** Largest stored completed result in UTF-8 bytes: storage's QS4 limit (DynamoDB items are
    * limited to 400 KB, and the rest of a job item is well under 100 KB).
    */
  val MaxResultBytes: Int = JobLimits.MaxResultBytes

  /** `X-Request-Id`: 1..64 characters `[A-Za-z0-9._-]`; long enough for a UUID or a ULID with a
    * prefix, short enough to log on every line.
    */
  val RequestIdPattern: String = "[A-Za-z0-9._-]{1,64}"

  /** `Idempotency-Key`: 1..128 characters `[A-Za-z0-9._:-]` (decision 7e; storage's QS5 rule). */
  val IdempotencyKeyPattern: String = JobLimits.IdempotencyKeyPattern

  /** Question length after canonicalization (rag's and storage's bound). */
  val MaxQuestionChars: Int = JobLimits.MaxQuestionChars

  /** One topic constraint (rag's and storage's bound). */
  val MaxTopicChars: Int = JobLimits.MaxTopicChars

  /** Deepest JSON nesting accepted in a request body. The deepest valid request is 3 levels (body,
    * `constraints`, `topics`); 16 leaves room for client libraries and stops stack-exhausting
    * bodies before they reach the parser.
    */
  val MaxJsonDepth: Int = 16

  /** Longest request path or query string accepted: every valid path is under 200 characters
    * (stable IDs are 40), a cursor is under 1 KB.
    */
  val MaxPathChars: Int = 512
  val MaxQueryChars: Int = 2048

  /** Audit events read for one explain request (storage's read bound). */
  val ExplainAuditLimit: Int = 1000

  /** Windows read for one evidence ID (storage's EB2 bound). */
  val EvidenceWindowLimit: Int = 100

  /** Retry-After seconds sent with 503 `OVERLOADED`, `CIRCUIT_OPEN` and 409 in-progress answers:
    * long enough to let a query finish, short enough for an interactive client.
    */
  val RetryAfterSeconds: Int = 2

  /** HTTP handler threads beyond `maxConcurrentQueries`: reads, health checks and requests waiting
    * for a sync result share the handler pool; this headroom keeps reads served while every query
    * permit is taken.
    */
  val HttpExtraThreads: Int = 8

  /** Bound of the HTTP handler queue. When it is full the dispatcher thread runs the request itself
    * (caller-runs), which stops it accepting new connections: back-pressure instead of an unbounded
    * queue.
    */
  val HttpQueueCapacity: Int = 256

  /** Threads of the readiness probe (one per check) and the bound of its queue. */
  val ReadinessThreads: Int = 3
  val ReadinessQueueCapacity: Int = 9

  /** How long an idle worker thread waits before polling the job queue again (the SQS receive
    * itself also long-polls for one second).
    */
  val WorkerIdleMillis: Long = 200L

  /** Visibility timeout of the local in-memory job queue, beyond `jobLeaseMillis`: a message must
    * stay hidden longer than a lease so a live lease is never seen twice (decision 6; on SQS this
    * is the queue's own setting, Terraform in Phase 12).
    */
  val LocalVisibilityMarginMillis: Long = 30000L

  /** Server socket backlog. */
  val Backlog: Int = 128

  /** Fixed researcher ID read by the readiness probe (any well-formed ID; absence is fine). */
  val ProbeResearcherId: String = "researcher:v1:" + "0" * 32

  /** Fixed job ID read by the readiness probe (absence is fine). */
  val ProbeJobId: String = "job-readiness-probe"

  /** Prefix of every thread the services create. */
  val ThreadPrefix: String = "scholarchain-"
}
