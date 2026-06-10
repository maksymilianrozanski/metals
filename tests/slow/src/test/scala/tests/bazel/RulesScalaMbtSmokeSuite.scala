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
import tests.BazelMbtTestInitializer

/**
 * Phase 0 feasibility smoke test: import a REAL repository (rules_scala) as MBT
 * through the real Metals server and confirm `.metals/mbt.json` is generated and
 * the build server connects. Scoped to a single small Bazel package so the
 * `bazel query` MBT extraction stays fast.
 *
 * SAFETY: this suite points the workspace at an existing on-disk checkout. It
 * MUST NOT call `cleanWorkspace()`/`cleanUnmanagedFiles()` (those delete the
 * whole workspace). Only `.metals` and the generated `.bazelproject` are removed.
 *
 * Runs only when the checkout exists (default `/Volumes/colimavol/rules_scala`,
 * override with `METALS_MBT_TEST_REPO`).
 */
class RulesScalaMbtSmokeSuite
    extends BaseLspSuite("rules-scala-mbt-smoke", BazelMbtTestInitializer) {

  private val repoDir: AbsolutePath =
    AbsolutePath(
      Option(System.getenv("METALS_MBT_TEST_REPO"))
        .getOrElse("/Volumes/colimavol/rules_scala")
    )

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some("2.13.18"),
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = JavaSymbolLoaderConfig.turbineClasspath,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )

  /** SAFETY: return the real checkout as-is; never delete it. */
  override protected def createWorkspace(name: String): AbsolutePath = {
    require(
      repoDir.isDirectory,
      s"rules_scala checkout not found at $repoDir (set METALS_MBT_TEST_REPO)",
    )
    repoDir
  }

  private def projectView: AbsolutePath = repoDir.resolve(".bazelproject")

  private def wipeGeneratedState(): Unit = {
    RecursivelyDelete(repoDir.resolve(".metals"))
    RecursivelyDelete(projectView)
  }

  override def afterAll(): Unit = {
    wipeGeneratedState()
    super.afterAll()
  }

  test("import-rules-scala-srcjars-as-mbt") {
    client.selectedServer = Messages.ChooseBuildServer.mbt
    // Regenerate mbt.json from scratch, scoped to one small package.
    wipeGeneratedState()
    Files.writeString(
      projectView.toNIO,
      """|targets:
         |    //test/src/main/scala/scalarules/test/srcjars_with_java/...
         |""".stripMargin,
    )
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(userConfig.toString)
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ = server.assertBuildServerConnection()
      mbtFile = repoDir.resolve(".metals/mbt.json")
      _ = assert(mbtFile.isFile, "mbt.json was not generated")
      contents = mbtFile.readText
      _ = scribe.info(s"[mbt-smoke] .metals/mbt.json:\n$contents")
      _ = assert(
        contents.contains("namespaces"),
        s"mbt.json has no namespaces:\n$contents",
      )
      _ <- server.didOpen(
        "test/src/main/scala/scalarules/test/srcjars_with_java/MixedLanguageDependent.scala"
      )
    } yield ()
  }
}
