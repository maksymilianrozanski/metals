package tests.bazel

/** A single navigation/highlighting probe against a real on-disk file. */
sealed trait DiffFeature { def name: String }
object DiffFeature {
  case object Definition extends DiffFeature { val name = "definition" }
  case object References extends DiffFeature { val name = "references" }
  case object Hover extends DiffFeature { val name = "hover" }
  case object DocumentSymbol extends DiffFeature { val name = "documentSymbol" }
}

/**
 * A probe locates a cursor with a `@@`-marked snippet that is a unique substring
 * of the real file. `DocumentSymbol` ignores the query.
 *
 * `category` tags the high-risk pattern this probe exercises (e.g. "srcjar",
 * "cross-target", "java-interop", "multi-version", "custom-root", "generated").
 * It is what lets the diff analyzer aggregate failures into patterns across
 * files and repos, so always set it.
 */
final case class Probe(
    file: String,
    query: String,
    feature: DiffFeature,
    note: String = "",
    category: String = "",
)

/** The MBT result vs the BSP (source of truth) result for one probe. */
final case class Discrepancy(
    probe: Probe,
    bsp: String,
    mbt: String,
    status: String,
)

object MbtDifferentialReport {
  private def isEmptyResult(s: String): Boolean = {
    val t = s.trim
    t.isEmpty || t == "<empty>" || t.startsWith("<error")
  }

  def statusOf(bsp: String, mbt: String): String = {
    val b = isEmptyResult(bsp)
    val m = isEmptyResult(mbt)
    if (b && m) "BOTH_EMPTY"
    else if (b) "BSP_EMPTY"
    else if (m) "MBT_EMPTY"
    else if (bsp.trim == mbt.trim) "MATCH"
    else "MISMATCH"
  }

  def summary(discrepancies: List[Discrepancy]): String =
    discrepancies
      .groupBy(_.status)
      .map { case (k, v) => s"$k=${v.size}" }
      .toList
      .sorted
      .mkString(", ")

  def markdown(
      metalsHead: String,
      repoHead: String,
      discrepancies: List[Discrepancy],
  ): String = {
    val header =
      s"""|# MBT vs BSP differential report
          |
          |metals version: `$metalsHead`
          |target repo HEAD: `$repoHead`
          |
          |summary: ${summary(discrepancies)}
          |
          |BSP = bazelbsp (source of truth); MBT = under test.
          |MISMATCH / MBT_EMPTY are likely MBT bugs; BSP_EMPTY means no oracle (judge manually).
          |""".stripMargin
    val body = discrepancies
      .map { d =>
        val cat =
          if (d.probe.category.nonEmpty) s" _[${d.probe.category}]_" else ""
        val head =
          s"## ${d.probe.feature.name} — `${d.probe.file}` — **${d.status}**$cat"
        val q =
          s"query: `${d.probe.query.replace("\n", "\\n")}`" +
            (if (d.probe.note.nonEmpty) s" — ${d.probe.note}" else "")
        if (d.status == "MATCH") s"$head\n$q\n"
        else
          s"""|$head
              |$q
              |
              |BSP (truth):
              |```
              |${d.bsp}
              |```
              |MBT:
              |```
              |${d.mbt}
              |```
              |""".stripMargin
      }
      .mkString("\n")
    header + "\n" + body
  }

  private def esc(s: String): String =
    s.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c => c.toString
    }

  def json(
      metalsHead: String,
      repoHead: String,
      discrepancies: List[Discrepancy],
  ): String = {
    val items = discrepancies.map { d =>
      s"""{"file":"${esc(d.probe.file)}","feature":"${d.probe.feature.name}",""" +
        s""""category":"${esc(d.probe.category)}","query":"${esc(d.probe.query)}",""" +
        s""""note":"${esc(d.probe.note)}","status":"${d.status}",""" +
        s""""bsp":"${esc(d.bsp)}","mbt":"${esc(d.mbt)}"}"""
    }
    s"""{"metalsHead":"${esc(metalsHead)}","repoHead":"${esc(repoHead)}",""" +
      s""""results":[${items.mkString(",")}]}"""
  }
}
