package ru.bitec.app.ops
package domain.configuration

import scala.collection.mutable.ArrayBuffer

/** A bounded unified line diff. `text` is empty when nothing but counts could be computed within the
  * limits; the counts are exact unless `approximate` is set.
  */
final case class ConfigurationDiff(
  text: String,
  truncated: Boolean,
  approximate: Boolean,
  addedLines: Int,
  removedLines: Int
)

/** Deterministic line diff (Myers' O(ND) algorithm) in unified format, computed in this process:
  * no `diff` or `git` is ever run, locally or remotely. The result is shown to the requesting user
  * only; it is never logged or stored, because a remote file may hold sensitive values.
  */
object ConfigurationLineDiff {
  val MaxOutputBytes: Int = 64 * 1024
  val MaxOutputLines: Int = 400
  /** Beyond this many changed lines only counts are returned: memory stays O(D²) and small. */
  val MaxEditDistance: Int = 1000
  val Context: Int = 3

  private sealed trait Op
  private final case class Keep(oldIndex: Int, newIndex: Int) extends Op
  private final case class Remove(oldIndex: Int) extends Op
  private final case class Add(newIndex: Int) extends Op

  def between(before: String, after: String): ConfigurationDiff = {
    val (oldLines, oldEol) = lines(before)
    val (newLines, newEol) = lines(after)
    edits(oldLines, newLines) match {
      case None =>
        // Too different for a bounded diff: report multiset counts instead.
        val remaining = scala.collection.mutable.HashMap.empty[String, Int]
        newLines.foreach(line => remaining.update(line, remaining.getOrElse(line, 0) + 1))
        var removed = 0
        oldLines.foreach { line =>
          remaining.get(line) match {
            case Some(count) if count > 0 => remaining.update(line, count - 1)
            case _ => removed += 1
          }
        }
        val added = remaining.values.sum
        ConfigurationDiff("", truncated = true, approximate = true, added, removed)
      case Some(ops) =>
        val added = ops.count(_.isInstanceOf[Add])
        val removed = ops.count(_.isInstanceOf[Remove])
        val (text, truncated) = render(ops, oldLines, newLines, oldEol != newEol)
        ConfigurationDiff(text, truncated, approximate = false, added, removed)
    }
  }

  /** Splits into lines; a final newline ends the last line instead of starting an empty one. */
  private def lines(text: String): (IndexedSeq[String], Boolean) = {
    if (text.isEmpty) (Vector.empty, true)
    else {
      val parts = text.split("\n", -1).toVector
      if (parts.last.isEmpty) (parts.init, true) else (parts, false)
    }
  }

  private def edits(a: IndexedSeq[String], b: IndexedSeq[String]): Option[Vector[Op]] = {
    var prefix = 0
    while (prefix < a.size && prefix < b.size && a(prefix) == b(prefix)) prefix += 1
    var suffix = 0
    while (suffix < a.size - prefix && suffix < b.size - prefix &&
      a(a.size - 1 - suffix) == b(b.size - 1 - suffix)) suffix += 1
    val n = a.size - prefix - suffix
    val m = b.size - prefix - suffix
    middle(n, m, (i, j) => a(prefix + i) == b(prefix + j)).map { core =>
      val head = (0 until prefix).map(i => Keep(i, i): Op)
      val body = core.map {
        case Keep(i, j) => Keep(i + prefix, j + prefix)
        case Remove(i) => Remove(i + prefix)
        case Add(j) => Add(j + prefix)
      }
      val tail = (0 until suffix).map(s => Keep(a.size - suffix + s, b.size - suffix + s): Op)
      (head ++ body ++ tail).toVector
    }
  }

  /** Myers' greedy algorithm with a per-round copy of the frontier for backtracking. */
  private def middle(n: Int, m: Int, equal: (Int, Int) => Boolean): Option[Vector[Op]] = {
    val bound = math.min(n + m, MaxEditDistance)
    val offset = bound + 2
    val v = new Array[Int](2 * offset + 1)
    val trace = ArrayBuffer.empty[Array[Int]]
    var found = -1
    var d = 0
    while (found < 0 && d <= bound) {
      // The frontier as round d starts, for k in [-(d+1), d+1].
      trace += java.util.Arrays.copyOfRange(v, offset - d - 1, offset + d + 2)
      var k = -d
      while (found < 0 && k <= d) {
        var x = if (k == -d || (k != d && v(offset + k - 1) < v(offset + k + 1))) v(offset + k + 1)
          else v(offset + k - 1) + 1
        var y = x - k
        while (x < n && y < m && equal(x, y)) { x += 1; y += 1 }
        v(offset + k) = x
        if (x >= n && y >= m) found = d
        k += 2
      }
      d += 1
    }
    if (found < 0) None
    else {
      val ops = ArrayBuffer.empty[Op]
      var x = n
      var y = m
      var round = found
      while (round > 0) {
        val frontier = trace(round)
        def at(k: Int): Int = frontier(k + round + 1)
        val k = x - y
        val previousK = if (k == -round || (k != round && at(k - 1) < at(k + 1))) k + 1 else k - 1
        val previousX = at(previousK)
        val previousY = previousX - previousK
        while (x > previousX && y > previousY) { x -= 1; y -= 1; ops += Keep(x, y) }
        if (x == previousX) { y -= 1; ops += Add(y) } else { x -= 1; ops += Remove(x) }
        round -= 1
      }
      while (x > 0 && y > 0) { x -= 1; y -= 1; ops += Keep(x, y) }
      Some(ops.reverse.toVector)
    }
  }

  private def render(ops: Vector[Op], a: IndexedSeq[String], b: IndexedSeq[String],
                     eolChanged: Boolean): (String, Boolean) = {
    val changes = ops.indices.filterNot(i => ops(i).isInstanceOf[Keep])
    val output = new StringBuilder
    var lineCount = 0
    var truncated = false

    def emit(line: String): Unit =
      if (!truncated) {
        if (lineCount >= MaxOutputLines || output.length + line.length + 1 > MaxOutputBytes) truncated = true
        else { output.append(line).append('\n'); lineCount += 1 }
      }

    if (changes.nonEmpty) {
      emit("--- remote")
      emit("+++ desired")
      // Group changes whose unchanged gap is small enough to share context.
      val groups = ArrayBuffer.empty[(Int, Int)]
      changes.foreach { index =>
        groups.lastOption match {
          case Some((start, end)) if index - end <= 2 * Context => groups(groups.size - 1) = (start, index)
          case _ => groups += (index -> index)
        }
      }
      groups.foreach { case (first, last) =>
        val from = math.max(0, first - Context)
        val to = math.min(ops.size - 1, last + Context)
        val slice = ops.slice(from, to + 1)
        def oldStart = slice.collectFirst { case Keep(i, _) => i; case Remove(i) => i }
        def newStart = slice.collectFirst { case Keep(_, j) => j; case Add(j) => j }
        val oldCount = slice.count(op => !op.isInstanceOf[Add])
        val newCount = slice.count(op => !op.isInstanceOf[Remove])
        emit(s"@@ -${oldStart.fold(0)(_ + 1)},$oldCount +${newStart.fold(0)(_ + 1)},$newCount @@")
        slice.foreach {
          case Keep(i, _) => emit(" " + a(i))
          case Remove(i) => emit("-" + a(i))
          case Add(j) => emit("+" + b(j))
        }
      }
    }
    if (eolChanged) emit("\\ newline at end of file differs")
    (output.toString, truncated)
  }
}
