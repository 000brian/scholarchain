package org.scholarchain.er.cluster

import org.scholarchain.er.io.ErError

/** A set of non-negative ints with open addressing (no boxing). Iteration order depends only on the
  * inserted values and their insertion order, so it is deterministic.
  */
final class IntOpenSet(initialCapacity: Int = 8) {
  private var table: Array[Int] = {
    var c = 8
    while (c < initialCapacity * 2) c <<= 1
    Array.fill(c)(IntOpenSet.Empty)
  }
  private var count = 0

  def size: Int = count

  private def slot(t: Array[Int], v: Int): Int = {
    val mask = t.length - 1
    var i = IntOpenSet.mix(v) & mask
    while (t(i) != IntOpenSet.Empty && t(i) != v) i = (i + 1) & mask
    i
  }

  def add(v: Int): Unit = {
    if (v < 0) throw new IllegalArgumentException(s"IntOpenSet holds non-negative ints, got $v")
    val i = slot(table, v)
    if (table(i) == IntOpenSet.Empty) {
      table(i) = v
      count += 1
      if (count * 2 > table.length) grow()
    }
  }

  def contains(v: Int): Boolean = v >= 0 && table(slot(table, v)) == v

  private def grow(): Unit = {
    val old = table
    table = Array.fill(old.length * 2)(IntOpenSet.Empty)
    var i = 0
    while (i < old.length) {
      if (old(i) != IntOpenSet.Empty) table(slot(table, old(i))) = old(i)
      i += 1
    }
  }

  /** Applies `f` to every value; stops early when `f` returns false. */
  def forall(f: Int => Boolean): Boolean = {
    var i = 0
    while (i < table.length) {
      val v = table(i)
      if (v != IntOpenSet.Empty && !f(v)) return false
      i += 1
    }
    true
  }

  def addAll(other: IntOpenSet): Unit = other.forall { v => add(v); true }
}

object IntOpenSet {
  private val Empty = -1
  private def mix(v: Int): Int = {
    val h = v * 0x9e3779b9
    h ^ (h >>> 16)
  }
}

/** Cannot-link constraints between dblp-prior clusters (name indices), from the REJECTED and
  * CONFLICT pair decisions whose two mentions are in different dblp-prior clusters (repair M1).
  *
  * A name pair with both a conflict and a rejected decision is a conflict cannot-link. Stored in
  * compressed sparse rows: the partners of name `n` are
  * `partners(offsets(n) until offsets(n + 1))`, each encoded as `partner * 2 + kind` with kind 1
  * for conflict and 0 for rejected. Memory is two ints per distinct name pair (the builder holds
  * one long per pair decision until [[build]]).
  */
final class CannotLinks private (val offsets: Array[Int], val partners: Array[Int]) {
  def names: Int = offsets.length - 1
  def pairs: Int = partners.length / 2

  /** Encoded partners of name `n` (see the class comment). */
  def partnersOf(n: Int): Iterator[Int] =
    if (n >= names) Iterator.empty else partners.iterator.slice(offsets(n), offsets(n + 1))
}

object CannotLinks {
  val Rejected = 0
  val Conflict = 1

  def empty(names: Int): CannotLinks = new CannotLinks(new Array[Int](names + 1), Array.empty)

  def encode(partner: Int, kind: Int): Int = partner * 2 + kind
  def partner(encoded: Int): Int = encoded >>> 1
  def kind(encoded: Int): Int = encoded & 1

  /** Collects cannot-links; [[add]] ignores pairs inside one dblp-prior cluster. */
  final class Builder(names: Int) {
    if (names >= (1 << 30)) throw new ErError(s"too many dblp names for cannot-links: $names")
    private var buf = new Array[Long](1024)
    private var n = 0

    def add(nameA: Int, nameB: Int, kind: Int): Unit =
      if (nameA != nameB) {
        if (n == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2)
        // (lo, hi, kind) packed so that sorting groups one name pair with its kinds adjacent
        val lo = math.min(nameA, nameB).toLong
        val hi = math.max(nameA, nameB).toLong
        buf(n) = (lo << 32) | (hi << 1) | kind.toLong
        n += 1
      }

    def size: Int = n

    def build(): CannotLinks = {
      val a = java.util.Arrays.copyOf(buf, n)
      buf = Array.emptyLongArray
      java.util.Arrays.sort(a)
      // distinct name pairs; conflict (kind 1) wins over rejected
      val pairKey = new Array[Long](n)
      val pairKind = new Array[Int](n)
      var k = 0
      var i = 0
      while (i < n) {
        val key = a(i) >>> 1
        val kd = (a(i) & 1L).toInt
        if (k > 0 && pairKey(k - 1) == key) pairKind(k - 1) = math.max(pairKind(k - 1), kd)
        else { pairKey(k) = key; pairKind(k) = kd; k += 1 }
        i += 1
      }
      def lo(key: Long) = (key >>> 31).toInt
      def hi(key: Long) = (key & 0x7fffffffL).toInt
      val degree = new Array[Int](names + 1)
      i = 0
      while (i < k) { degree(lo(pairKey(i))) += 1; degree(hi(pairKey(i))) += 1; i += 1 }
      val offsets = new Array[Int](names + 1)
      var s = 0
      i = 0
      while (i < names) { offsets(i) = s; s += degree(i); i += 1 }
      offsets(names) = s
      val fill = java.util.Arrays.copyOf(offsets, names + 1)
      val partners = new Array[Int](s)
      i = 0
      while (i < k) {
        val l = lo(pairKey(i))
        val h = hi(pairKey(i))
        partners(fill(l)) = encode(h, pairKind(i)); fill(l) += 1
        partners(fill(h)) = encode(l, pairKind(i)); fill(h) += 1
        i += 1
      }
      new CannotLinks(offsets, partners)
    }
  }
}
