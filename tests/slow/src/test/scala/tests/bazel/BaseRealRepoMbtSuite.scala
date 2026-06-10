package tests.bazel

import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

import scala.concurrent.Future

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location
import tests.BaseLspSuite
import tests.BazelMbtTestInitializer

/**
 * Differential harness: import a REAL repo twice through the real Metals server
 * — once via Bazel BSP (bazelbsp, the source of truth) and once via MBT (under
 * test) — run the same probes against both, and write a discrepancy report.
 *
 * SAFETY: the workspace is an existing on-disk checkout. We override
 * `createWorkspace` to point at it without deleting, and NEVER call
 * `cleanWorkspace()`/`cleanUnmanagedFiles()`. Only generated state (`.metals`,
 * `.bsp`, `.bazelbsp`, `.bazelproject`) is removed.
 */
abstract class BaseRealRepoMbtSuite(suiteName: String)
    extends BaseLspSuite(suiteName, BazelMbtTestInitializer) {

  /** The real checkout to import (must already exist on disk). */
  def repoDir: AbsolutePath

  /** `.bazelproject` `targets:` entries scoping the import. */
  def projectViewTargets: List[String]

  /** Navigation probes run against both build servers. */
  def probes: List[Probe]

  /** SAFETY: return the real checkout as-is; never delete it. */
  override protected def createWorkspace(name: String): AbsolutePath = {
    require(
      repoDir.isDirectory,
      s"checkout not found at $repoDir (set METALS_MBT_TEST_REPO)",
    )
    repoDir
  }

  private def baseConfig: UserConfiguration =
    super.userConfig.copy(
      buildOnChange = false,
      buildOnFocus = false,
    )

  /** Config that selects the MBT build server. */
  protected def mbtConfig: UserConfiguration =
    baseConfig.copy(
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      javaSymbolLoader = JavaSymbolLoaderConfig.turbineClasspath,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )

  /** Config that selects the Bazel BSP (bazelbsp) build server. */
  protected def bspConfig: UserConfiguration = baseConfig

  // Drives `newServer`'s initial user config; flipped per phase before each
  // `newServer` call. Initialized to a harmless default for the throwaway
  // server created by `beforeEach`.
  private var phaseConfig: UserConfiguration = UserConfiguration()
  override def userConfig: UserConfiguration = phaseConfig

  private def projectView: AbsolutePath = repoDir.resolve(".bazelproject")

  private def wipe(includeProjectView: Boolean): Unit = {
    RecursivelyDelete(repoDir.resolve(".metals"))
    RecursivelyDelete(repoDir.resolve(".bsp"))
    RecursivelyDelete(repoDir.resolve(".bazelbsp"))
    if (includeProjectView) RecursivelyDelete(projectView)
  }

  override def afterAll(): Unit = {
    wipe(includeProjectView = true)
    super.afterAll()
  }

  private def writeProjectView(): Unit = {
    val content =
      "targets:\n" + projectViewTargets.map(t => s"    $t").mkString("\n") + "\n"
    Files.writeString(projectView.toNIO, content)
  }

  private def repoHead: String =
    scala.util
      .Try(
        scala.sys.process
          .Process(List("git", "rev-parse", "--short", "HEAD"), repoDir.toFile)
          .!!
          .trim
      )
      .getOrElse("unknown")

  /** Render an LSP uri relative to the workspace (or tagged for jars/readonly). */
  private def normalizeUri(uri: String): String = {
    val readonly = "/.metals/readonly/"
    if (uri.contains(readonly))
      "readonly:" + uri.substring(uri.indexOf(readonly) + readonly.length)
    else if (uri.startsWith("file:")) {
      val p = Paths.get(new URI(uri))
      if (p.startsWith(repoDir.toNIO)) repoDir.toNIO.relativize(p).toString
      else "external:" + p.getFileName.toString
    } else if (uri.startsWith("jar:")) {
      val bang = uri.lastIndexOf('!')
      if (bang >= 0) "jar:" + uri.substring(bang + 1) else uri
    } else uri
  }

  private def renderLocations(locs: List[Location]): String = {
    val rendered = for (loc <- locs) yield {
      val r = loc.getRange
      s"${normalizeUri(loc.getUri)}:${r.getStart.getLine}:${r.getStart.getCharacter}" +
        s"-${r.getEnd.getLine}:${r.getEnd.getCharacter}"
    }
    if (rendered.isEmpty) "<empty>" else rendered.sorted.mkString("\n")
  }

  private def capture(probe: Probe): Future[String] = {
    val result = probe.feature match {
      case DiffFeature.Definition =>
        server
          .definitionSubstringQuery(probe.file, probe.query)
          .map(renderLocations)
      case DiffFeature.References =>
        server
          .getReferenceLocations(probe.file, probe.query)
          .map(renderLocations)
      case DiffFeature.Hover =>
        server.hover(probe.file, probe.query, workspace).map(_.trim)
      case DiffFeature.DocumentSymbol =>
        server.documentSymbols(probe.file).map(_.trim)
    }
    result.recover { case e =>
      s"<error: ${e.getClass.getSimpleName}: ${e.getMessage}>"
    }
  }

  /** Open + focus + compile each file so the real PC (with classpath) is ready. */
  private def prepareFiles(files: List[String]): Future[Unit] =
    files.foldLeft(Future.successful(())) { (acc, file) =>
      acc.flatMap { _ =>
        val path = server.toPath(file)
        for {
          _ <- server.didOpen(file)
          _ <- server.didFocus(file)
          _ <- server.fullServer.getServiceFor(path).compilations.compileFile(path)
        } yield ()
      }
    }

  private def runProbes(): Future[Map[Probe, String]] = {
    val files = probes.map(_.file).distinct
    for {
      _ <- prepareFiles(files)
      // Give the real (classpath-backed) PC time to replace the fallback PC on
      // the BSP side before probing, otherwise the oracle reads as empty.
      _ <- server.waitFor(3000)
      results <- probes.foldLeft(Future.successful(Map.empty[Probe, String])) {
        (acc, probe) =>
          acc.flatMap(m => capture(probe).map(res => m + (probe -> res)))
      }
    } yield results
  }

  protected def runDifferential(): Future[Unit] = {
    val head = repoHead
    // ---- Phase 1: BSP (source of truth) ----
    phaseConfig = bspConfig
    cancelServer()
    newServer(s"$suiteName-bsp")
    client.selectedServer = Messages.ChooseBuildServer.bsp
    client.generateBspAndConnect = Messages.GenerateBspAndConnect.yes
    wipe(includeProjectView = true)
    writeProjectView()
    for {
      _ <- server.initialize()
      _ = (client.generateBspAndConnect = Messages.GenerateBspAndConnect.yes)
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(bspConfig.toString)
      _ = server.assertBuildServerConnection()
      bspResults <- runProbes()
      // ---- Phase 2: MBT (under test) ----
      _ = {
        phaseConfig = mbtConfig
        cancelServer()
        newServer(s"$suiteName-mbt")
        client.selectedServer = Messages.ChooseBuildServer.mbt
        wipe(includeProjectView = false) // keep the .bazelproject
        writeProjectView()
      }
      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(mbtConfig.toString)
      _ <- server.headServer.connectionProvider.buildServerPromise.future
      _ = server.assertBuildServerConnection()
      mbtResults <- runProbes()
    } yield {
      val discrepancies = probes.map { p =>
        val bsp = bspResults.getOrElse(p, "<empty>")
        val mbt = mbtResults.getOrElse(p, "<empty>")
        Discrepancy(p, bsp, mbt, MbtDifferentialReport.statusOf(bsp, mbt))
      }
      writeReport(head, discrepancies)
    }
  }

  private def writeReport(
      head: String,
      discrepancies: List[Discrepancy],
  ): Unit = {
    val dir =
      PathIO.workingDirectory.resolve("target").resolve("mbt-differential")
    Files.createDirectories(dir.toNIO)
    val md = dir.resolve(s"$suiteName.md")
    val js = dir.resolve(s"$suiteName.json")
    Files.writeString(md.toNIO, MbtDifferentialReport.markdown(head, discrepancies))
    Files.writeString(js.toNIO, MbtDifferentialReport.json(head, discrepancies))
    scribe.info(s"[mbt-differential] ${MbtDifferentialReport.summary(discrepancies)}")
    scribe.info(s"[mbt-differential] report: ${md.toNIO}")
    scribe.info("\n" + MbtDifferentialReport.markdown(head, discrepancies))
  }
}
