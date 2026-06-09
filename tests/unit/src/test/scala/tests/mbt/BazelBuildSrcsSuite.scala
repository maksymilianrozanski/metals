package tests.mbt

import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs

import munit.FunSuite

class BazelBuildSrcsSuite extends FunSuite {

  // Mirrors `bazel query --output=streamed_jsonproto --proto:flatten_selects=false`
  // for a `select_for_scala_version` target: `srcs` keeps the `select()` as a
  // `selectorList`, with each branch keyed by a scala_version `config_setting`.
  // The literal list operand (`["Settings.scala", "Options.scala"] + select(..)`)
  // is its own `elements` entry with no version label. A trailing SOURCE_FILE
  // target and a blank line exercise the "skip non-rules" path.
  private val jsonOutput =
    """|{"type":"RULE","rule":{"name":"//third_party/dep:dep","ruleClass":"scala_library_for_plugin_bootstrapping","attribute":[{"name":"srcs","type":"SELECTOR_LIST","selectorList":{"type":"LABEL_LIST","elements":[{"entries":[{"isDefaultValue":true,"stringListValue":["//third_party/dep:a/Settings.scala","//third_party/dep:a/Options.scala"]}]},{"entries":[{"label":"@rules_scala_config//:scala_version_2_12_21","stringListValue":["//third_party/dep:a/Analyzer.scala"]},{"label":"@rules_scala_config//:scala_version_2_11_12","stringListValue":["//third_party/dep:a/Reporter.scala"]},{"label":"@rules_scala_config//:scala_version_3_7_4","stringListValue":["//third_party/dep:a3/Analyzer.scala","//third_party/dep:a3/Finder.scala"]},{"label":"//conditions:default","isDefaultValue":true,"stringListValue":[]}]}]}},{"name":"deps","type":"LABEL_LIST","stringListValue":["//x:y"]}]}}
       |
       |{"type":"SOURCE_FILE","sourceFile":{"name":"//third_party/dep:a/Settings.scala"}}
       |""".stripMargin

  test("parse-srcs-select-by-version") {
    val parsed = BazelBuildSrcs.parse(jsonOutput)
    // Only the rule is a key; SOURCE_FILE targets are skipped.
    assertEquals(parsed.keySet, Set("//third_party/dep:dep"))
    val srcs = parsed("//third_party/dep:dep")
    assertEquals(
      srcs.unconditional,
      Set(
        "//third_party/dep:a/Settings.scala",
        "//third_party/dep:a/Options.scala",
      ),
    )
    assertEquals(
      srcs.byVersion("3.7.4"),
      Set(
        "//third_party/dep:a3/Analyzer.scala",
        "//third_party/dep:a3/Finder.scala",
      ),
    )
    assertEquals(
      srcs.activeFor(Some("2.12.21")),
      Set(
        "//third_party/dep:a/Settings.scala",
        "//third_party/dep:a/Options.scala",
        "//third_party/dep:a/Analyzer.scala",
      ),
    )
  }

  test("plain-srcs-list-without-select-is-unconditional") {
    val plain =
      """|{"type":"RULE","rule":{"name":"//b:lib","ruleClass":"scala_library","attribute":[{"name":"srcs","type":"LABEL_LIST","stringListValue":["//b:Baz.scala"]}]}}
         |""".stripMargin
    val srcs = BazelBuildSrcs.parse(plain)("//b:lib")
    assertEquals(srcs.unconditional, Set("//b:Baz.scala"))
    assertEquals(srcs.byVersion, Map.empty[String, Set[String]])
  }

  test("inactive-sources-are-non-default-version-branches") {
    val inactive = BazelBuildSrcs.inactiveSourceVersions(
      jsonOutput,
      Map("//third_party/dep:dep" -> Some("2.12.21")),
    )
    // Scala 3 sources and the 2.11-only Reporter are inactive under 2.12.21,
    // each mapped to its own branch version; unconditional sources and the
    // matching 2.12.21 branch are not.
    assertEquals(
      inactive,
      Map(
        "//third_party/dep:a/Reporter.scala" -> "2.11.12",
        "//third_party/dep:a3/Analyzer.scala" -> "3.7.4",
        "//third_party/dep:a3/Finder.scala" -> "3.7.4",
      ),
    )
  }
}
