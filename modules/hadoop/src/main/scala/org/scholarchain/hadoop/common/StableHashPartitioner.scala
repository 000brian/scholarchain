package org.scholarchain.hadoop.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.apache.hadoop.io.{Text, WritableComparable, WritableComparator, WritableUtils}
import org.apache.hadoop.mapreduce.Partitioner

/** Explicit partition strategy used by every ScholarChain job: the first 4 bytes of SHA-256 of the
  * UTF-8 map-output key, modulo the reducer count. Independent of JVM, Hadoop version and
  * `String.hashCode`, so the same key lands in the same partition file in every run with the same
  * reducer count.
  */
class StableHashPartitioner extends Partitioner[Text, Text] {
  override def getPartition(key: Text, value: Text, numPartitions: Int): Int =
    StableHashPartitioner.partition(key.toString, numPartitions)
}

/** Stable hash of the first two tab-separated fields of the map-output key only (the whole key when
  * it has fewer), so every key sharing that prefix goes to one reduce task. Since reduce keys
  * arrive sorted, keys with one prefix are then contiguous within that task, which lets a reducer
  * aggregate per prefix while streaming (J05c, per oversize block key).
  */
class TwoFieldPrefixPartitioner extends Partitioner[Text, Text] {
  override def getPartition(key: Text, value: Text, numPartitions: Int): Int =
    StableHashPartitioner.partition(TwoFieldPrefixPartitioner.prefix(key.toString), numPartitions)
}

object TwoFieldPrefixPartitioner {
  def prefix(key: String): String = {
    val first = key.indexOf('\t')
    val second = if (first < 0) -1 else key.indexOf('\t', first + 1)
    if (second < 0) key else key.substring(0, second)
  }
}

/** Grouping comparator for a secondary sort on [[TwoFieldPrefixPartitioner]] keys: two map-output
  * keys belong to one `reduce` call when their first two tab-separated fields are equal (the whole
  * key when it has fewer fields). Compares the UTF-8 bytes of those prefixes, which is the order of
  * the default [[Text]] sort comparator restricted to the prefix, so with keys
  * `<field1>\t<field2>\t<sort suffix>` whose first two fields contain no tab, the keys of one group
  * are contiguous in the sorted reduce input and the values of a group arrive in suffix order.
  */
class TwoFieldPrefixGroupingComparator extends WritableComparator(classOf[Text], false) {

  override def compare(
      b1: Array[Byte],
      s1: Int,
      l1: Int,
      b2: Array[Byte],
      s2: Int,
      l2: Int
  ): Int = {
    val h1 = WritableUtils.decodeVIntSize(b1(s1))
    val h2 = WritableUtils.decodeVIntSize(b2(s2))
    val start1 = s1 + h1
    val start2 = s2 + h2
    val p1 = TwoFieldPrefixGroupingComparator.prefixLength(b1, start1, l1 - h1)
    val p2 = TwoFieldPrefixGroupingComparator.prefixLength(b2, start2, l2 - h2)
    WritableComparator.compareBytes(b1, start1, p1, b2, start2, p2)
  }

  override def compare(a: WritableComparable[?], b: WritableComparable[?]): Int = {
    val x = a.asInstanceOf[Text]
    val y = b.asInstanceOf[Text]
    WritableComparator.compareBytes(
      x.getBytes,
      0,
      TwoFieldPrefixGroupingComparator.prefixLength(x.getBytes, 0, x.getLength),
      y.getBytes,
      0,
      TwoFieldPrefixGroupingComparator.prefixLength(y.getBytes, 0, y.getLength)
    )
  }
}

object TwoFieldPrefixGroupingComparator {

  /** Bytes before the second tab (0x09, never part of a multi-byte UTF-8 sequence) of `len` bytes
    * at `start`, or `len` when there are fewer than two tabs.
    */
  def prefixLength(b: Array[Byte], start: Int, len: Int): Int = {
    var tabs = 0
    var i = 0
    while (i < len) {
      if (b(start + i) == '\t') {
        tabs += 1
        if (tabs == 2) return i
      }
      i += 1
    }
    len
  }
}

object StableHashPartitioner {
  def partition(key: String, numPartitions: Int): Int = {
    val d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))
    val h = ((d(0) & 0xff) << 24) | ((d(1) & 0xff) << 16) | ((d(2) & 0xff) << 8) | (d(3) & 0xff)
    Integer.remainderUnsigned(h, numPartitions)
  }
}
