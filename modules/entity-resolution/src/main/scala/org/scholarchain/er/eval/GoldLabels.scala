package org.scholarchain.er.eval

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}

import scala.collection.mutable
import scala.util.Try

import org.scholarchain.core.CodePointOrder
import org.scholarchain.er.io.{Csv, DataFiles}
import org.scholarchain.er.review.ReviewQueue

/** One human label (decision 14). */
final case class GoldLabel(
    leftMentionId: String,
    rightMentionId: String,
    candidateId: String,
    label: String,
    evidence: List[String],
    note: String,
    labeler: String,
    labeledAt: String
)

/** @param unlabeledRows
  *   queue rows present in the file with every label column empty (counted, not evaluated)
  */
final case class GoldSet(labels: List[GoldLabel], unlabeledRows: Int)

/** Strict reader of the human gold labels (decision 14). Only the evaluation harness uses it, and
  * only with paths given on the command line; no matching code imports this package.
  *
  * Columns: `leftMentionId`, `rightMentionId`, `candidateId`, `label` (MATCH | NON_MATCH | UNSURE),
  * `evidence` (one or more of orcid, openalex_author, dblp_numbered_name, shared_coauthors,
  * institution, research_area, other, separated by `;`), `note`, `labeler`, `labeledAt`
  * (`yyyy-MM-dd` or `yyyy-MM-ddTHH:mm:ssZ`). The label-blind display columns of the review queue
  * may also be present (labeling directly in the queue CSV); they must still equal the queue's
  * values. Errors (all reported, nothing silently ignored): unknown or repeated columns, missing
  * required columns, unknown label or evidence values, repeated evidence values, MATCH or NON_MATCH
  * without evidence, missing labeler, bad date, duplicate pairs, pairs not in the queue, a
  * candidate ID or display value that differs from the queue, and a row with an empty label but
  * other label fields filled. A row whose label columns are all empty is an unlabeled queue row and
  * is counted.
  */
object GoldLabels {
  val Labels: Set[String] = Set("MATCH", "NON_MATCH", "UNSURE")
  val Evidence: Set[String] =
    Set(
      "orcid",
      "openalex_author",
      "dblp_numbered_name",
      "shared_coauthors",
      "institution",
      "research_area",
      "other"
    )
  val Required: List[String] = ReviewQueue.KeyColumns ++ ReviewQueue.LabelColumns
  val Allowed: Set[String] = ReviewQueue.Columns.toSet

  private def pairKey(a: String, b: String): (String, String) =
    if (CodePointOrder.lessThan(a, b)) (a, b) else (b, a)

  /** The queue rows (JSONL written by the run) keyed by unordered mention pair. */
  def readQueue(queueJsonl: Path): Map[(String, String), Map[String, String]] = {
    val rows = mutable.LinkedHashMap.empty[(String, String), Map[String, String]]
    DataFiles.readLines(queueJsonl, queueJsonl.toString) { (line, n) =>
      if (line.trim.nonEmpty) {
        val o = Try(ujson.read(line).obj)
          .getOrElse(throw new IllegalArgumentException(s"$queueJsonl:$n: not a JSON object"))
        val m = o.value.map { case (k, v) => k -> v.str }.toMap
        rows(pairKey(m("leftMentionId"), m("rightMentionId"))) = m
      }
    }
    rows.toMap
  }

  def read(goldCsv: Path, queueJsonl: Path): Either[List[String], GoldSet] =
    if (!Files.isRegularFile(goldCsv)) Left(List(s"$goldCsv: not a file"))
    else parse(DataFiles.readUtf8(goldCsv), readQueue(queueJsonl))

  def parse(
      text: String,
      queue: Map[(String, String), Map[String, String]]
  ): Either[List[String], GoldSet] =
    Csv.parse(text) match {
      case Left(e)                     => Left(List(s"malformed CSV: $e"))
      case Right(rows) if rows.isEmpty => Left(List("empty file (no header)"))
      case Right(rows)                 =>
        val errors = List.newBuilder[String]
        val header = rows.head
        val dupCols = header.diff(header.distinct).distinct
        if (dupCols.nonEmpty) errors += s"header: repeated columns ${dupCols.mkString(", ")}"
        val unknown = header.filterNot(Allowed)
        if (unknown.nonEmpty) errors += s"header: unknown columns ${unknown.mkString(", ")}"
        val missing = Required.filterNot(header.contains)
        if (missing.nonEmpty) errors += s"header: missing columns ${missing.mkString(", ")}"
        if (dupCols.nonEmpty || unknown.nonEmpty || missing.nonEmpty) return Left(errors.result())
        val labels = List.newBuilder[GoldLabel]
        val seen = mutable.HashSet.empty[(String, String)]
        var unlabeled = 0
        rows.tail.zipWithIndex.foreach { case (r, idx) =>
          val where = s"row ${idx + 2}"
          if (r.size != header.size)
            errors += s"$where: ${r.size} fields, header has ${header.size}"
          else {
            val v = header.zip(r).toMap
            val key = pairKey(v("leftMentionId"), v("rightMentionId"))
            if (!seen.add(key)) errors += s"$where: duplicate pair ${key._1} / ${key._2}"
            queue.get(key) match {
              case None =>
                errors += s"$where: pair ${key._1} / ${key._2} is not in the review queue"
              case Some(q) =>
                if (v("candidateId") != q("candidateId"))
                  errors += s"$where: candidateId '${v("candidateId")}' differs from the queue ('${q("candidateId")}')"
                header.filter(h => ReviewQueue.DisplayColumns.contains(h)).foreach { h =>
                  if (v(h) != q(h)) errors += s"$where: display column $h differs from the queue"
                }
            }
            val label = v("label").trim
            val labelFields = List("evidence", "note", "labeler", "labeledAt")
            if (label.isEmpty) {
              if (labelFields.exists(f => v(f).trim.nonEmpty))
                errors += s"$where: label is empty but other label columns are filled"
              else unlabeled += 1
            } else {
              val ev = v("evidence").split(';').map(_.trim).filter(_.nonEmpty).toList
              if (!Labels(label))
                errors += s"$where: unknown label '$label' (expected MATCH, NON_MATCH or UNSURE)"
              ev.filterNot(Evidence).foreach(e => errors += s"$where: unknown evidence '$e'")
              if (ev.distinct.size != ev.size) errors += s"$where: repeated evidence values"
              if ((label == "MATCH" || label == "NON_MATCH") && ev.isEmpty)
                errors += s"$where: $label needs at least one evidence value"
              if (v("labeler").trim.isEmpty) errors += s"$where: labeler is empty"
              val at = v("labeledAt").trim
              val dateOk = Try(LocalDate.parse(at).toString == at).getOrElse(false) ||
                Try(Instant.parse(at).toString == at).getOrElse(false)
              if (!dateOk)
                errors += s"$where: labeledAt '$at' must be yyyy-MM-dd or yyyy-MM-ddTHH:mm:ssZ"
              labels += GoldLabel(
                key._1,
                key._2,
                v("candidateId"),
                label,
                ev.sorted,
                v("note"),
                v("labeler").trim,
                at
              )
            }
          }
        }
        val errs = errors.result()
        if (errs.nonEmpty) Left(errs) else Right(GoldSet(labels.result(), unlabeled))
    }
}
