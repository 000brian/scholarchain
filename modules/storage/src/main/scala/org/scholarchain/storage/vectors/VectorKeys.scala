package org.scholarchain.storage.vectors

import org.scholarchain.core.{
  ChunkId,
  ResearcherId,
  SemanticDocument,
  SemanticDocumentType,
  VectorKey,
  WorkId
}

/** Vector keys depend on the subject and window, never on the run or the text, so a Spark rerun
  * that produces the same text for the same subject finds the same key and is skipped.
  *
  * Rule (derived from the document's `subjectIds` and the label of its window):
  *
  * | type                      | key                                                          | subject                         |
  * |:--------------------------|:-------------------------------------------------------------|:--------------------------------|
  * | researcher_profile        | `rp:<windowLabel>:<researcherId>`                            | the only subject ID             |
  * | community_summary         | `cm:<windowLabel>:<communityGroupId>`                        | the first subject ID (group ID) |
  * | collaboration_path        | `pa:<windowLabel>:<sourceResearcherId>:<targetResearcherId>` | first and last subject IDs      |
  * | paper_summary             | `pp:<workId>`                                                | the first subject ID            |
  * | topic_institution_summary | `ti:<windowLabel or ->:<subjectKey>`                         | all subject IDs joined by `,`   |
  *
  * Chunk ordinals above 0 (text split by rag's chunking policy) append `#<ordinal>`.
  */
object VectorKeys {

  def derive(
      documentType: SemanticDocumentType,
      windowLabel: Option[String],
      subjectIds: List[String],
      ordinal: Int
  ): Either[String, String] = {
    def window = windowLabel.toRight(s"${documentType.wire}: a window label is required")
    def researcher(s: String) =
      if (ResearcherId.isWellFormed(s)) Right(s) else Left(s"'$s' is not a researcher ID")
    val base: Either[String, String] = documentType match {
      case SemanticDocumentType.ResearcherProfile =>
        subjectIds match {
          case List(r) => for { w <- window; id <- researcher(r) } yield s"rp:$w:$id"
          case _       => Left("researcher_profile: exactly one subject ID is required")
        }
      case SemanticDocumentType.CommunitySummary =>
        subjectIds.headOption.toRight("community_summary: no subject IDs").flatMap { g =>
          for { w <- window; id <- researcher(g) } yield s"cm:$w:$id"
        }
      case SemanticDocumentType.CollaborationPath =>
        if (subjectIds.size < 2) Left("collaboration_path: at least two subject IDs are required")
        else
          for {
            w <- window
            a <- researcher(subjectIds.head)
            b <- researcher(subjectIds.last)
          } yield s"pa:$w:$a:$b"
      case SemanticDocumentType.PaperSummary =>
        subjectIds.headOption
          .filter(WorkId.isWellFormed)
          .map(w => s"pp:$w")
          .toRight("paper_summary: the first subject ID must be a work ID")
      case SemanticDocumentType.TopicInstitutionSummary =>
        ChunkId
          .subjectKeyOf(subjectIds)
          .map(k => s"ti:${windowLabel.getOrElse(ChunkId.NoWindow)}:$k")
    }
    base.flatMap { k =>
      val key = if (ordinal > 0) s"$k#$ordinal" else if (ordinal == 0) k else null
      if (key == null) Left(s"ordinal $ordinal must be >= 0")
      else if (!VectorKey.isValid(key)) Left(s"vector key exceeds ${VectorKey.MaxUtf8Bytes} bytes")
      else Right(key)
    }
  }

  /** The key a document must carry, for the given window label (ordinal 0). */
  def forDocument(doc: SemanticDocument, windowLabel: Option[String]): Either[String, String] =
    derive(doc.documentType, windowLabel, doc.subjectIds, 0)

  /** Checks that `doc.vectorKey` follows the rule: the ordinal-0 key, or that key plus `#<n>` with
    * n >= 1.
    */
  def check(doc: SemanticDocument, windowLabel: Option[String]): Either[String, Unit] =
    forDocument(doc, windowLabel).flatMap { base =>
      val k = doc.vectorKey
      val ok = k == base || (k.startsWith(base + "#") && {
        val n = k.substring(base.length + 1)
        n.nonEmpty && n.forall(_.isDigit) && !n.startsWith("0")
      })
      if (ok) Right(())
      else Left(s"vectorKey '$k' does not follow the key rule (expected '$base' or '$base#<n>')")
    }
}
