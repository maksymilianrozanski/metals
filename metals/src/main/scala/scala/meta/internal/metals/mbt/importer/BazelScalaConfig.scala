package scala.meta.internal.metals.mbt.importer

import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
 * Reads the active Scala version from `bazel query
 * @rules_scala_config//:scala_version --output=streamed_jsonproto` (see
 * [[BazelQuery.scalaConfigQuery]]) instead of parsing MODULE.bazel/WORKSPACE.
 *
 * rules_scala stores the workspace's default Scala version as the
 * `build_setting_default` of the `string_setting` rule
 * `@rules_scala_config//:scala_version`, so the query returns that single rule
 * and its `build_setting_default` is the version every target resolves to
 * unless it overrides `scala_version` itself.
 */
object BazelScalaConfig {

  /**
   * Finds the `string_setting` rule named `…:scala_version` and returns its
   * non-empty `build_setting_default` string. One JSON object per line; lines
   * that are blank, not the expected rule, or otherwise unparseable are
   * skipped.
   */
  def defaultScalaVersion(queryOutput: String): Option[String] =
    queryOutput.linesIterator
      .filter(_.trim.nonEmpty)
      .flatMap(parseDefaultVersion)
      .nextOption()

  private def parseDefaultVersion(jsonLine: String): Option[String] =
    Try {
      val target = ujson.read(jsonLine)
      for {
        rule <- target.obj.get("rule")
        if ruleClass(rule).contains("string_setting")
        name <- ruleName(rule)
        if name.endsWith(":scala_version")
        version <- stringAttribute(rule, "build_setting_default")
        if version.nonEmpty
      } yield version
    } match {
      case Success(parsed) => parsed
      case Failure(err) =>
        scribe.debug(
          s"bazel-mbt: skipping unparseable scala_version config: ${err.getMessage}"
        )
        None
    }

  private def ruleName(rule: ujson.Value): Option[String] =
    rule.obj.get("name").collect { case ujson.Str(n) => n }

  private def ruleClass(rule: ujson.Value): Option[String] =
    rule.obj.get("ruleClass").collect { case ujson.Str(c) => c }

  private def stringAttribute(
      rule: ujson.Value,
      attributeName: String,
  ): Option[String] =
    rule.obj
      .get("attribute")
      .toList
      .flatMap(_.arr)
      .find(_.obj.get("name").contains(ujson.Str(attributeName)))
      .flatMap(_.obj.get("stringValue"))
      .collect { case ujson.Str(s) => s }

}
