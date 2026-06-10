package tests.mbt

import scala.meta.internal.metals.mbt.importer.BazelScalaConfig

import munit.FunSuite

class BazelScalaConfigSuite extends FunSuite {

  // Mirrors `bazel query @rules_scala_config//:scala_version
  // --output=streamed_jsonproto`: a single `string_setting` rule whose
  // `build_setting_default` STRING attribute holds the active Scala version.
  // (The real output also carries a `values` STRING_LIST of every configured
  // version and many other attributes, omitted here.)
  private val configOutput =
    """|{"type":"RULE","rule":{"name":"@rules_scala_config//:scala_version","ruleClass":"string_setting","attribute":[{"name":"build_setting_default","type":"STRING","stringValue":"2.12.21","explicitlySpecified":true},{"name":"name","type":"STRING","stringValue":"scala_version","explicitlySpecified":true},{"name":"values","type":"STRING_LIST","stringListValue":["2.12.21","2.13.18","3.7.4"]}]}}
       |""".stripMargin

  test("default-scala-version-from-build-setting-default") {
    assertEquals(
      BazelScalaConfig.defaultScalaVersion(configOutput),
      Some("2.12.21"),
    )
  }

  test("no-scala-version-setting-yields-none") {
    // A different rule (not the `:scala_version` `string_setting`) is ignored.
    val other =
      """|{"type":"RULE","rule":{"name":"//a:lib","ruleClass":"scala_library","attribute":[{"name":"build_setting_default","type":"STRING","stringValue":"2.12.21"}]}}
         |""".stripMargin
    assertEquals(BazelScalaConfig.defaultScalaVersion(other), None)
  }

  test("blank-and-garbage-lines-are-skipped") {
    val noisy =
      """|
         |not json at all
         |{"type":"RULE","rule":{"name":"@rules_scala_config//:scala_version","ruleClass":"string_setting","attribute":[{"name":"build_setting_default","type":"STRING","stringValue":"3.7.4"}]}}
         |""".stripMargin
    assertEquals(BazelScalaConfig.defaultScalaVersion(noisy), Some("3.7.4"))
  }
}
