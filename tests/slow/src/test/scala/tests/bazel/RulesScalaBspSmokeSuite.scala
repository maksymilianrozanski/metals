package tests.bazel

import java.nio.file.Files

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.io.AbsolutePath

import tests.BaseLspSuite
import tests.BazelServerInitializer

/**
 * Phase 0 oracle-reliability spike: can Bazel BSP (bazelbsp 4.0.3) import the
 * REAL rules_scala repo and connect? This is the linchpin of the differential
 * MBT-vs-BSP design (BSP = source of truth). Scoped to the same package as
 * [[RulesScalaMbtSmokeSuite]] for a direct comparison.
 *
 * SAFETY: points the workspace at an existing checkout; never calls
 * `cleanWorkspace()`/`cleanUnmanagedFiles()`. Only removes generated state.
 */
class RulesScalaBspSmokeSuite
    extends BaseLspSuite("rules-scala-bsp-smoke", BazelServerInitializer) {

  private val repoDir: AbsolutePath =
    AbsolutePath(
      Option(System.getenv("METALS_MBT_TEST_REPO"))
        .getOrElse("/Volumes/colimavol/rules_scala")
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
    RecursivelyDelete(repoDir.resolve(".bsp"))
    RecursivelyDelete(repoDir.resolve(".bazelbsp"))
    RecursivelyDelete(projectView)
  }

  override def afterAll(): Unit = {
    wipeGeneratedState()
    super.afterAll()
  }

  test("import-rules-scala-srcjars-as-bsp") {
    client.selectedServer = Messages.ChooseBuildServer.bsp
    client.generateBspAndConnect = Messages.GenerateBspAndConnect.yes
    wipeGeneratedState()
    Files.writeString(
      projectView.toNIO,
      """|targets:
         |    //test/src/main/scala/scalarules/test/srcjars_with_java/...
         |""".stripMargin,
    )
    for {
      _ <- server.initialize()
      _ = (client.generateBspAndConnect = Messages.GenerateBspAndConnect.yes)
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(userConfig.toString)
      _ = server.assertBuildServerConnection()
      bspConfig = repoDir.resolve(".bsp/bazelbsp.json")
      _ = scribe.info(
        s"[bsp-smoke] .bsp/bazelbsp.json exists: ${bspConfig.exists}"
      )
      connectionName =
        server.server.bspSession.map(_.mainConnection.name).getOrElse("<none>")
      _ = scribe.info(s"[bsp-smoke] connected build server: $connectionName")
      _ = assert(bspConfig.exists, "bazelbsp.json was not generated")
      _ <- server.didOpen(
        "test/src/main/scala/scalarules/test/srcjars_with_java/MixedLanguageDependent.scala"
      )
    } yield ()
  }
}
