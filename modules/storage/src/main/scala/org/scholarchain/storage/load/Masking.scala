package org.scholarchain.storage.load

import org.scholarchain.storage.ports.ObjectRef

/** Hides identifiers in everything the loaders print or write: bucket names become `{data-bucket}`,
  * the configured account ID and the account field of any ARN become `{account}`. Bucket names come
  * from configuration; nothing here names one.
  */
final class Masking(buckets: Iterable[String], accountIds: Iterable[String]) {
  private val bucketList =
    buckets.filter(b => b != null && b.nonEmpty).toList.distinct.sortBy(-_.length)
  private val accounts = accountIds.filter(a => a != null && a.nonEmpty).toList.distinct

  def apply(text: String): String =
    if (text == null) ""
    else {
      val b = bucketList.foldLeft(text)((t, x) => t.replace(x, Masking.DataBucket))
      val a = accounts.foldLeft(b)((t, x) => t.replace(x, Masking.Account))
      Masking.ArnAccount.replaceAllIn(
        a,
        m => scala.util.matching.Regex.quoteReplacement(s"${m.group(1)}${Masking.Account}:")
      )
    }

  /** `s3://{data-bucket}/KEY`: the logical form of an S3 location. */
  def s3(ref: ObjectRef): String = s"s3://${Masking.DataBucket}/${ref.key}"
}

object Masking {
  val DataBucket = "{data-bucket}"
  val Account = "{account}"
  private val ArnAccount = "(arn:[a-z0-9-]+:[a-z0-9-]+:[a-z0-9-]*:)[0-9]{12}:".r
}
