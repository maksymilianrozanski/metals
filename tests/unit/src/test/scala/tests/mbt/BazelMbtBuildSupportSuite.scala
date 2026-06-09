package tests.mbt

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.mbt.importer.BazelMbtBuildSupport
import scala.meta.internal.metals.mbt.importer.BazelMbtNamespaceMode

import munit.FunSuite

class BazelMbtBuildSupportSuite extends FunSuite {

  private val unconfiguredNamespace = "bazel-unconfigured-sources"

  private def discover(
      scalaVersionByTarget: Map[String, Option[String]],
      inactiveSourceVersions: Map[String, String],
  ) =
    BazelMbtBuildSupport
      .fromDiscovery(
        granularity = BazelMbtNamespaceMode.BuildFile,
        targetLabels = List("//a:lib", "//b:lib"),
        srcsByTarget = Map(
          // `//a:lib` cross-compiles: Foo.scala is built in the default
          // (Scala 2) configuration, Bar3.scala only under Scala 3.
          "//a:lib" -> List("//a:Foo.scala", "//a:Bar3.scala"),
          "//b:lib" -> List("//b:Baz.scala"),
        ),
        scalacOptionsByTarget = Map.empty,
        javacOptionsByTarget = Map.empty,
        directDepRules = Map.empty,
        externalDepsByTarget = Map.empty,
        runTargets = Set.empty,
        classDirectoriesByTarget = Map.empty,
        dependencyModules = Nil,
        scalaVersionByTarget = scalaVersionByTarget,
        inactiveSourceVersions = inactiveSourceVersions,
      )
      .namespaces
      .asScala

  test("inactive-sources-are-tagged-with-their-own-branch-version") {
    // No Scala 3 target is in scope (both targets resolve to Scala 2), yet the
    // inactive source comes from the Scala 3 `select()` branch. It must be
    // tagged 3.7.4 — its real branch version — not the project-wide max
    // (2.12.21), which is what a scope-dependent guess would have produced.
    val namespaces = discover(
      scalaVersionByTarget =
        Map("//a:lib" -> Some("2.12.21"), "//b:lib" -> Some("2.11.12")),
      inactiveSourceVersions = Map("//a:Bar3.scala" -> "3.7.4"),
    )

    // Active source stays in its target's namespace at the resolved version.
    val a = namespaces("//a")
    assertEquals(a.getSources.asScala.toList, List("a/Foo.scala"))
    assertEquals(a.scalaVersion, "2.12.21")

    // Inactive Scala 3 source gets a Scala 3 namespace regardless of scope.
    val unconfigured = namespaces(unconfiguredNamespace)
    assertEquals(unconfigured.getSources.asScala.toList, List("a/Bar3.scala"))
    assertEquals(unconfigured.scalaVersion, "3.7.4")
  }

  test("inactive-sources-of-different-versions-get-separate-namespaces") {
    val namespaces = discover(
      scalaVersionByTarget =
        Map("//a:lib" -> Some("2.12.21"), "//b:lib" -> Some("2.12.21")),
      inactiveSourceVersions =
        Map("//a:Bar3.scala" -> "3.7.4", "//b:Baz.scala" -> "2.11.12"),
    )

    val v3 = namespaces(s"$unconfiguredNamespace-3.7.4")
    assertEquals(v3.getSources.asScala.toList, List("a/Bar3.scala"))
    assertEquals(v3.scalaVersion, "3.7.4")

    val v211 = namespaces(s"$unconfiguredNamespace-2.11.12")
    assertEquals(v211.getSources.asScala.toList, List("b/Baz.scala"))
    assertEquals(v211.scalaVersion, "2.11.12")
  }

  test("no-inactive-sources-keeps-current-behavior") {
    val namespaces = discover(
      scalaVersionByTarget =
        Map("//a:lib" -> Some("2.12.21"), "//b:lib" -> Some("3.7.4")),
      inactiveSourceVersions = Map.empty,
    )

    assert(!namespaces.contains(unconfiguredNamespace))
    assertEquals(
      namespaces("//a").getSources.asScala.toList,
      List("a/Bar3.scala", "a/Foo.scala"),
    )
  }
}
