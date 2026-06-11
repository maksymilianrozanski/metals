package tests.bazel

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs
import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs.InactiveSource
import scala.meta.internal.metals.mbt.importer.BazelMbtBuildSupport
import scala.meta.internal.metals.mbt.importer.BazelMbtNamespaceMode

import tests.BaseSuite

/**
 * Inactive `select()` branch sources (e.g. the Scala 3 branch of a
 * `select_for_scala_version` target whose default configuration is Scala 2):
 * version + origin-target recovery from `streamed_jsonproto` output
 * ([[BazelBuildSrcs.inactiveSources]]) and the per-origin
 * `<namespace>@<version>` namespaces with inherited dependencies
 * ([[BazelMbtBuildSupport.fromDiscovery]]).
 */
class BazelUnconfiguredSourcesSuite extends BaseSuite {

  private val v2_12 = "@rules_scala_config//:scala_version_2_12_21"
  private val v3_3 = "@rules_scala_config//:scala_version_3_3_0"
  private val v3_8 = "@rules_scala_config//:scala_version_3_8_3"

  private def selectRule(
      name: String,
      branches: (String, List[String])*
  ): String =
    ujson
      .Obj(
        "rule" -> ujson.Obj(
          "name" -> ujson.Str(name),
          "attribute" -> ujson.Arr(
            ujson.Obj(
              "name" -> ujson.Str("srcs"),
              "selectorList" -> ujson.Obj(
                "elements" -> ujson.Arr(
                  ujson.Obj(
                    "entries" -> ujson.Arr(
                      branches.map { case (label, srcs) =>
                        ujson.Obj(
                          "label" -> ujson.Str(label),
                          "stringListValue" -> ujson
                            .Arr(srcs.map(ujson.Str(_)): _*),
                        ): ujson.Value
                      }: _*
                    )
                  )
                )
              ),
            )
          ),
        )
      )
      .render()

  private def plainRule(name: String, srcs: List[String]): String =
    ujson
      .Obj(
        "rule" -> ujson.Obj(
          "name" -> ujson.Str(name),
          "attribute" -> ujson.Arr(
            ujson.Obj(
              "name" -> ujson.Str("srcs"),
              "stringListValue" -> ujson.Arr(srcs.map(ujson.Str(_)): _*),
            )
          ),
        )
      )
      .render()

  test("inactive-source-version-and-origin") {
    val queryOutput = selectRule(
      "//pkg/a:lib",
      v2_12 -> List("//pkg/a:A2.scala"),
      v3_8 -> List("//pkg/a:A3.scala"),
    )
    val result = BazelBuildSrcs.inactiveSources(
      queryOutput,
      Map("//pkg/a:lib" -> Some("2.12.21")),
    )
    assertEquals(
      result,
      Map("//pkg/a:A3.scala" -> InactiveSource("3.8.3", "//pkg/a:lib")),
    )
  }

  test("inactive-source-highest-version-then-smallest-target-wins") {
    val queryOutput = List(
      selectRule("//pkg/b:lib", v3_3 -> List("//pkg:Shared.scala")),
      selectRule("//pkg/c:lib", v3_8 -> List("//pkg:Shared.scala")),
      selectRule("//pkg/a:lib", v3_8 -> List("//pkg:Shared.scala")),
    ).mkString("\n")
    val scalaVersions: Map[String, Option[String]] = Map(
      "//pkg/a:lib" -> Some("2.12.21"),
      "//pkg/b:lib" -> Some("2.12.21"),
      "//pkg/c:lib" -> Some("2.12.21"),
    )
    val result = BazelBuildSrcs.inactiveSources(queryOutput, scalaVersions)
    assertEquals(
      result,
      Map("//pkg:Shared.scala" -> InactiveSource("3.8.3", "//pkg/a:lib")),
    )
  }

  test("inactive-source-active-in-another-target-is-excluded") {
    val queryOutput = List(
      selectRule("//pkg/a:lib", v3_8 -> List("//pkg:Shared.scala")),
      plainRule("//pkg/b:lib", List("//pkg:Shared.scala")),
    ).mkString("\n")
    val scalaVersions: Map[String, Option[String]] = Map(
      "//pkg/a:lib" -> Some("2.12.21"),
      "//pkg/b:lib" -> Some("2.12.21"),
    )
    val result = BazelBuildSrcs.inactiveSources(queryOutput, scalaVersions)
    assertEquals(result, Map.empty[String, InactiveSource])
  }

  test("from-discovery-unconfigured-namespace-inherits-origin-edges") {
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//pkg/a:lib", "//pkg/b:lib"),
      srcsByTarget = Map(
        "//pkg/a:lib" -> List("//pkg/a:A2.scala", "//pkg/a:A3.scala"),
        "//pkg/b:lib" -> List("//pkg/b:B.scala"),
      ),
      scalacOptionsByTarget = Map("//pkg/a:lib" -> List("-deprecation")),
      javacOptionsByTarget = Map.empty,
      directDepRules = Map("//pkg/a:lib" -> List("//pkg/b:lib")),
      externalDepsByTarget = Map("//pkg/a:lib" -> List("org.foo:bar:1.0.0")),
      runTargets = Set.empty,
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersionByTarget = Map(
        "//pkg/a:lib" -> Some("2.12.21"),
        "//pkg/b:lib" -> Some("2.12.21"),
      ),
      inactiveSources =
        Map("//pkg/a:A3.scala" -> InactiveSource("3.8.3", "//pkg/a:lib")),
    )
    val namespaces = build.getNamespaces.asScala
    assertEquals(
      namespaces.keySet,
      Set("//pkg/a", "//pkg/b", "//pkg/a@3.8.3"),
    )

    val origin = namespaces("//pkg/a")
    assertEquals(origin.getSources.asScala.toList, List("pkg/a/A2.scala"))
    assertEquals(origin.scalaVersion, "2.12.21")

    val unconfigured = namespaces("//pkg/a@3.8.3")
    assertEquals(
      unconfigured.getSources.asScala.toList,
      List("pkg/a/A3.scala"),
    )
    assertEquals(unconfigured.scalaVersion, "3.8.3")
    assertEquals(
      unconfigured.getDependsOn.asScala.toList,
      List("//pkg/a", "//pkg/b"),
    )
    assertEquals(
      unconfigured.getDependencyModuleIds.asScala.toList,
      List("org.foo:bar:1.0.0"),
    )
    // The origin's flags target a different Scala version — never copied.
    assertEquals(unconfigured.getScalacOptions.asScala.toList, List.empty)
  }
}
