package tests.bazel

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.meta._

/**
 * File-sweep snapshots for BSP-vs-MBT comparison. Unlike [[Probe]]-based
 * differential suites, a sweep needs no hand-authored test cases: every
 * identifier in the configured files is probed (definition + hover) and
 * file-level signals (diagnostics, semanticTokens, documentSymbol) are
 * captured wholesale. One snapshot holds everything observed under ONE import
 * mode; [[SweepDiff]] compares two snapshots.
 *
 * Snapshot format: JSON lines. First line is a `meta` record (mode, repoHead,
 * metalsHead, targets, files); each further line is one observation keyed by
 * (file, kind, line, col, ident).
 */
object MbtSweep {

  /**
   * Collector input, read from a JSON file because the sbt server's forked
   * test JVM does not see environment variables set by the invoking shell —
   * scripts write this file and then run the suite.
   */
  case class SweepConfig(
      repo: String,
      mode: String, // "bsp" | "mbt"
      targets: List[String],
      files: List[String],
      out: String,
      maxIdentsPerFile: Int,
  )

  object SweepConfig {
    def read(path: Path): SweepConfig = {
      val json = ujson.read(Files.readString(path))
      SweepConfig(
        repo = json("repo").str,
        mode = json("mode").str,
        targets = json("targets").arr.map(_.str).toList,
        files = json("files").arr.map(_.str).toList,
        out = json("out").str,
        maxIdentsPerFile =
          json.obj.get("maxIdentsPerFile").map(_.num.toInt).getOrElse(500),
      )
    }
  }

  /** One observation; `line`/`col` are -1 for file-level kinds. */
  case class SweepRecord(
      file: String,
      kind: String,
      line: Int,
      col: Int,
      ident: String,
      result: String,
  ) {
    def key: (String, String, Int, Int, String) = (file, kind, line, col, ident)
  }

  case class Snapshot(
      mode: String,
      repoHead: String,
      metalsHead: String,
      records: List[SweepRecord],
  )

  def write(
      out: Path,
      mode: String,
      repoHead: String,
      metalsHead: String,
      targets: List[String],
      files: List[String],
      records: List[SweepRecord],
  ): Unit = {
    Files.createDirectories(out.getParent)
    val meta = ujson.Obj(
      "meta" -> ujson.Obj(
        "mode" -> ujson.Str(mode),
        "repoHead" -> ujson.Str(repoHead),
        "metalsHead" -> ujson.Str(metalsHead),
        "targets" -> ujson.Arr(targets.map(ujson.Str(_)): _*),
        "files" -> ujson.Arr(files.map(ujson.Str(_)): _*),
      )
    )
    val lines = meta.render() :: records.map { r =>
      ujson
        .Obj(
          "file" -> ujson.Str(r.file),
          "kind" -> ujson.Str(r.kind),
          "line" -> ujson.Num(r.line),
          "col" -> ujson.Num(r.col),
          "ident" -> ujson.Str(r.ident),
          "result" -> ujson.Str(r.result),
        )
        .render()
    }
    Files.writeString(out, lines.mkString("\n") + "\n")
  }

  def read(path: Path): Snapshot = {
    val lines = Files.readString(path).linesIterator.filter(_.nonEmpty).toList
    require(lines.nonEmpty, s"empty snapshot: $path")
    val meta = ujson.read(lines.head)("meta")
    val records = lines.tail.map { line =>
      val json = ujson.read(line)
      SweepRecord(
        file = json("file").str,
        kind = json("kind").str,
        line = json("line").num.toInt,
        col = json("col").num.toInt,
        ident = json("ident").str,
        result = json("result").str,
      )
    }
    Snapshot(
      meta("mode").str,
      meta("repoHead").str,
      meta("metalsHead").str,
      records,
    )
  }

  /**
   * Identifier tokens to probe: (line, col, name), 0-based LSP positions.
   * Tokenized with the Scala 3 dialect (tokenizes Scala 2 sources fine);
   * string/char literals and comments are distinct token kinds, so only real
   * identifier occurrences are returned. When a file has more identifiers
   * than `max`, every nth is kept so coverage stays spread over the file.
   */
  def identifiers(text: String, max: Int): List[(Int, Int, String)] = {
    val tokens = dialects.Scala3(text).tokenize.toOption match {
      case Some(tokenized) => tokenized.tokens.toList
      case None => Nil
    }
    val idents = tokens.collect {
      case id: Token.Ident if id.value != "_" =>
        (id.pos.startLine, id.pos.startColumn, id.value)
    }
    if (idents.size <= max) idents
    else {
      val step = math.ceil(idents.size.toDouble / max).toInt
      idents.zipWithIndex.collect {
        case (ident, i) if i % step == 0 => ident
      }
    }
  }
}

/**
 * Compares two [[MbtSweep]] snapshots and writes a diff report.
 *
 * Usage: `sbt --client "slow/Test/runMain tests.bazel.SweepDiff <a.jsonl>
 * <b.jsonl> <outDir>"` — `a` is the baseline (typically BSP), `b` the
 * candidate (typically MBT). Writes `sweep-diff.md` (human/Claude triage) and
 * `sweep-diff.json` (full non-MATCH detail) into `outDir` and prints a
 * summary to stdout. Exit code is 0 regardless of differences: the report is
 * the deliverable.
 */
object SweepDiff {

  case class Entry(
      key: (String, String, Int, Int, String),
      a: String,
      b: String,
      status: String,
  )

  def main(args: Array[String]): Unit = {
    require(
      args.length >= 3,
      "usage: SweepDiff <baseline.jsonl> <candidate.jsonl> <outDir>",
    )
    val a = MbtSweep.read(Paths.get(args(0)))
    val b = MbtSweep.read(Paths.get(args(1)))
    val outDir = Paths.get(args(2))
    Files.createDirectories(outDir)

    val aByKey = a.records.map(r => r.key -> r.result).toMap
    val bByKey = b.records.map(r => r.key -> r.result).toMap
    val keys = (aByKey.keySet ++ bByKey.keySet).toList.sorted

    val entries = keys.map { key =>
      val av = aByKey.get(key)
      val bv = bByKey.get(key)
      val status = (av, bv) match {
        case (Some(_), None) => s"ONLY_${a.mode.toUpperCase}"
        case (None, Some(_)) => s"ONLY_${b.mode.toUpperCase}"
        case (Some(x), Some(y)) => statusOf(a.mode, b.mode, x, y)
        case (None, None) => "IMPOSSIBLE"
      }
      Entry(key, av.getOrElse("<absent>"), bv.getOrElse("<absent>"), status)
    }

    val interesting = entries.filterNot(_.status == "MATCH")
    writeJson(outDir.resolve("sweep-diff.json"), a, b, interesting)
    val markdown = renderMarkdown(a, b, entries, interesting)
    Files.writeString(outDir.resolve("sweep-diff.md"), markdown)

    println(summaryOf(a, b, entries))
    println(s"report: ${outDir.resolve("sweep-diff.md")}")
    println(s"detail: ${outDir.resolve("sweep-diff.json")}")
  }

  private def isEmptyResult(t: String): Boolean =
    t.trim.isEmpty || t == "<empty>" || t.startsWith("<error") ||
      t == "<absent>"

  private def statusOf(
      modeA: String,
      modeB: String,
      a: String,
      b: String,
  ): String = {
    val ae = isEmptyResult(a)
    val be = isEmptyResult(b)
    if (ae && be) "BOTH_EMPTY"
    else if (ae) s"${modeA.toUpperCase}_EMPTY"
    else if (be) s"${modeB.toUpperCase}_EMPTY"
    else if (a.trim == b.trim) "MATCH"
    else "MISMATCH"
  }

  private def summaryOf(
      a: MbtSweep.Snapshot,
      b: MbtSweep.Snapshot,
      entries: List[Entry],
  ): String = {
    val counts = entries
      .groupBy(_.status)
      .map { case (status, group) => s"$status=${group.size}" }
      .toList
      .sorted
      .mkString(" ")
    s"sweep-diff ${a.mode}@${a.metalsHead} vs ${b.mode}@${b.metalsHead} " +
      s"repo=${a.repoHead} observations=${entries.size} $counts"
  }

  private def writeJson(
      out: Path,
      a: MbtSweep.Snapshot,
      b: MbtSweep.Snapshot,
      interesting: List[Entry],
  ): Unit = {
    val json = ujson.Obj(
      "baseline" -> ujson
        .Obj("mode" -> a.mode, "metalsHead" -> a.metalsHead),
      "candidate" -> ujson
        .Obj("mode" -> b.mode, "metalsHead" -> b.metalsHead),
      "repoHead" -> ujson.Str(a.repoHead),
      "diffs" -> ujson.Arr(
        interesting.map { e =>
          val (file, kind, line, col, ident) = e.key
          ujson.Obj(
            "file" -> ujson.Str(file),
            "kind" -> ujson.Str(kind),
            "line" -> ujson.Num(line),
            "col" -> ujson.Num(col),
            "ident" -> ujson.Str(ident),
            "status" -> ujson.Str(e.status),
            a.mode -> ujson.Str(e.a),
            b.mode -> ujson.Str(e.b),
          ): ujson.Value
        }: _*
      ),
    )
    Files.writeString(out, json.render(indent = 2))
  }

  private def renderMarkdown(
      a: MbtSweep.Snapshot,
      b: MbtSweep.Snapshot,
      entries: List[Entry],
      interesting: List[Entry],
  ): String = {
    val sb = new StringBuilder
    sb.append(s"# Sweep diff: ${a.mode} vs ${b.mode}\n\n")
    sb.append(s"- baseline: ${a.mode} @ metals ${a.metalsHead}\n")
    sb.append(s"- candidate: ${b.mode} @ metals ${b.metalsHead}\n")
    sb.append(s"- repo: ${a.repoHead}\n")
    sb.append(s"- ${summaryOf(a, b, entries)}\n\n")
    sb.append("## Per file & kind (non-MATCH counts)\n\n")
    for {
      ((file, kind), group) <- interesting
        .groupBy(e => (e.key._1, e.key._2))
        .toList
        .sortBy { case (key, _) => key }
    } {
      val counts = group
        .groupBy(_.status)
        .map { case (status, items) => s"$status=${items.size}" }
        .toList
        .sorted
        .mkString(" ")
      sb.append(s"- `$file` $kind: $counts\n")
    }
    val maxShown = 300
    sb.append(s"\n## Non-MATCH detail (first $maxShown)\n")
    for (e <- interesting.take(maxShown)) {
      val (file, kind, line, col, ident) = e.key
      val position = if (line >= 0) s":$line:$col `$ident`" else ""
      sb.append(s"\n### ${e.status} | $kind | $file$position\n")
      sb.append(s"\n${a.mode}:\n```\n${e.a}\n```\n")
      sb.append(s"\n${b.mode}:\n```\n${e.b}\n```\n")
    }
    if (interesting.size > maxShown)
      sb.append(
        s"\n…${interesting.size - maxShown} further entries in sweep-diff.json\n"
      )
    sb.toString
  }
}
