package tests.bazel

import java.nio.file.Files

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.io.AbsolutePath

import tests.BaseLspSuite
import tests.BaseMbtSuite
import tests.BazelMbtTestInitializer
import tests.TestHovers

/**
 * Reproduction of the rules_scala Scala 3 navigation issue, importing the REAL
 * checkout at /Volumes/colimavol/rules_scala exactly as Metals would when the
 * project is opened in VS Code and imported via MBT.
 *
 * The Scala version is NOT overridden: MBT resolves it from the Bazel project
 * configuration (which defaults to Scala 2.12.x). Under that configuration the
 * Scala 3 file `dependencyanalyzer3/DependencyAnalyzer.scala` is selected only
 * via `select_for_scala_version(any_3 = ...)`, so it is not part of the
 * configured target. We then open that file and exercise navigation at the two
 * carets reported by the user.
 *
 * This test is expected to FAIL while the bug is present (no hover tooltip /
 * no cross-file definition); it documents the broken behavior.
 */
class BazelMbtRulesScalaLspSuite
    extends BaseLspSuite("bazel-mbt-rules-scala", BazelMbtTestInitializer)
    with TestHovers
    with BaseMbtSuite {

  private val rulesScala: AbsolutePath =
    AbsolutePath("/Volumes/colimavol/rules_scala")

  /** Point the suite at the real rules_scala clone instead of a temp dir. */
  override protected def createWorkspace(name: String): AbsolutePath =
    rulesScala

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      // Fallback only; the resolved target Scala version takes precedence.
      fallbackScalaVersion = Some("2.13.12"),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = JavaSymbolLoaderConfig.turbineClasspath,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )

  private val analyzerPath =
    "third_party/dependency_analyzer/src/main/io/bazel/rulesscala/" +
      "dependencyanalyzer3/DependencyAnalyzer.scala"

  // Scope the MBT `bazel query` to the dependency_analyzer package so the import
  // stays bounded, while the Scala version still resolves from Bazel config.
  private val projectView =
    """|directories:
       |  third_party/dependency_analyzer
       |
       |targets:
       |  //third_party/dependency_analyzer/...
       |
       |additional_languages:
       |  scala
       |""".stripMargin

  /** Insert the `@@` caret marker `offset` chars after the start of `anchor`. */
  private def withCaret(text: String, anchor: String, offset: Int): String = {
    val start = text.indexOf(anchor)
    require(start >= 0, s"anchor not found in file: '$anchor'")
    val caret = start + offset
    text.substring(0, caret) + "@@" + text.substring(caret)
  }

  override def afterAll(): Unit = {
    // Leave the real checkout as we found it.
    val metalsDir = rulesScala.resolve(".metals")
    if (metalsDir.exists) RecursivelyDelete(metalsDir)
    val projectViewFile = rulesScala.resolve(".bazelproject")
    if (projectViewFile.exists) Files.deleteIfExists(projectViewFile.toNIO)
    super.afterAll()
  }

  test("rules-scala-dependency-analyzer-scala3-given") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    client.chooseBazelMbtNamespaceMode =
      Messages.BazelMbtNamespaceChoice.packages
    rulesScala.resolve(".bazelproject").writeText(projectView)
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(userConfig.toString)
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      mbtFile = rulesScala.resolve(".metals/mbt.json")
      _ = scribe.info(
        s"[REPRO-MBT] mbt.json present=${mbtFile.exists}:\n" +
          (if (mbtFile.exists) mbtFile.readText else "<missing>")
      )
      _ <- server.didOpen(analyzerPath)
      realText = rulesScala.resolve(analyzerPath).readText
      _ = scribe.info(
        s"[REPRO-MBT] diagnostics after open:\n${client.workspaceDiagnostics}"
      )

      // Caret 1: given Dependency@@AnalyzerSettings  (the given TYPE)
      query1 = withCaret(
        realText,
        "given DependencyAnalyzerSettings",
        "given Dependency".length,
      )
      hover1 <- server.hover(analyzerPath, query1, rulesScala)
      _ = scribe.info(s"[REPRO-MBT] hover on given TYPE:\n>>>\n$hover1\n<<<")
      def1 <- server.definition(analyzerPath, query1, rulesScala)
      _ = scribe.info(s"[REPRO-MBT] definition on given TYPE: $def1")

      // Caret 2: DependencyAnalyzerSettings.pa@@rseSettings  (the method)
      query2 = withCaret(
        realText,
        "DependencyAnalyzerSettings.parseSettings",
        "DependencyAnalyzerSettings.pa".length,
      )
      hover2 <- server.hover(analyzerPath, query2, rulesScala)
      _ = scribe.info(s"[REPRO-MBT] hover on parseSettings:\n>>>\n$hover2\n<<<")
      def2 <- server.definition(analyzerPath, query2, rulesScala)
      _ = scribe.info(s"[REPRO-MBT] definition on parseSettings: $def2")
    } yield {
      // Desired (correct) behavior — these assertions FAIL while the bug exists.
      assert(
        hover1.trim.nonEmpty,
        "caret 1: expected a hover tooltip on the given type " +
          "`DependencyAnalyzerSettings`, but hover was empty",
      )
      assert(
        def2.nonEmpty &&
          def2.head.getUri.contains("DependencyAnalyzerSettings.scala"),
        s"caret 2: expected `parseSettings` to resolve to " +
          s"DependencyAnalyzerSettings.scala, but got: $def2",
      )
    }
  }

}
