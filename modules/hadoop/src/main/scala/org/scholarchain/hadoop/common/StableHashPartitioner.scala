package org.scholarchain.hadoop.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.apache.hadoop.io.Text
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

object StableHashPartitioner {
  def partition(key: String, numPartitions: Int): Int = {
    val d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))
    val h = ((d(0) & 0xff) << 24) | ((d(1) & 0xff) << 16) | ((d(2) & 0xff) << 8) | (d(3) & 0xff)
    Integer.remainderUnsigned(h, numPartitions)
  }
}
