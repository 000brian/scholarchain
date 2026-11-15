package org.scholarchain.storage

/** Service limits that storage enforces in code.
  *
  * Source of the S3 Vectors and Neptune values: the service-limits table of the Phase 7 work order
  * (decision 8), captured by the human architect on Nov 15 from AWS documentation. Nothing here is
  * read from the network. A limit marked "not in decision 8" is a documented AWS value that was not
  * re-verified in this phase.
  */
object S3VectorsLimits {

  /** Dimension per vector: 1 to 4,096. */
  val MinDimension: Int = 1
  val MaxDimension: Int = 4096

  /** Metadata per vector: total up to 40 KB, filterable up to 2 KB, up to 50 keys. */
  val MaxTotalMetadataBytes: Int = 40 * 1024
  val MaxFilterableMetadataBytes: Int = 2 * 1024
  val MaxMetadataKeys: Int = 50

  /** Non-filterable metadata keys: up to 10 per index, fixed when the index is created. */
  val MaxNonFilterableKeys: Int = 10

  /** PutVectors: up to 500 vectors per call and a request payload up to 20 MiB. */
  val MaxPutVectorsBatch: Int = 500
  val MaxPutVectorsPayloadBytes: Long = 20L * 1024L * 1024L

  /** GetVectors: up to 100 vectors per call. */
  val MaxGetVectorsBatch: Int = 100

  /** Write rate per index: 1,000 PutVectors+DeleteVectors requests/s and 2,500 vectors/s. */
  val MaxWriteRequestsPerSecond: Int = 1000
  val MaxVectorsPerSecondPerIndex: Int = 2500

  /** Vector keys: at most 1024 UTF-8 bytes (core `VectorKey`). */
  val MaxKeyUtf8Bytes: Int = 1024

  val Source: String =
    "Phase 7 work order decision 8, captured by the human architect on Nov 15 " +
      "(AWS docs, S3 Vectors \"Limitations and restrictions\")"
}

/** Bounds of the read-only graph query port (decision 6: depth at most 6, a result limit and a
  * timeout). Core has no configuration keys for these yet, so they are storage constants; callers
  * may only lower them.
  */
object GraphQueryBounds {
  val MaxDepth: Int = 6
  val MaxResultLimit: Int = 500
  val DefaultResultLimit: Int = 100
  val MaxTimeoutMillis: Int = 30000
}

/** DynamoDB limits used by the item builders (documented AWS values, not in decision 8). */
object DynamoDbLimits {

  /** Maximum item size, attribute names included. */
  val MaxItemBytes: Int = 400 * 1024

  /** One on-demand write request unit covers up to 1 KB of item size. */
  val WriteUnitBytes: Int = 1024
}
