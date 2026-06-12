package tests.bazel

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.Future
import scala.concurrent.duration.Duration

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.{Position => LspPosition}
import tests.TestHovers
import tests.TestSemanticTokens

/**
 * Collects a [[MbtSweep]] snapshot of everything observable in the configured
 * files under ONE import mode (BSP oracle or MBT under test): diagnostics,
 * semanticTokens, documentSymbol per file, and definition + hover at every
 * identifier. No hand-authored probes — coverage comes from tokenization, and
 * a later [[SweepDiff]] of a BSP snapshot against an MBT snapshot replaces
 * expected values.
 *
 * Driven by a JSON config file (NOT environment variables — those don't reach
 * the sbt server's forked test JVM): `tests/slow/target/mbt-sweep/
 * sweep-config.json`, written by `tools/mbt-sweep/collect.sh`. The test skips
 * when no config is present, so a bare `testOnly` run is harmless.
 *
 * Run: `sbt --client "slow/testOnly tests.bazel.RepoSweepCollectorSuite"`.
 */
class RepoSweepCollectorSuite extends BaseRealRepoMbtSuite("repo-sweep") {

  // Cold bazelbsp aspect builds can take well over the default 10 minutes.
  override def munitTimeout: Duration = Duration("40min")

  // Run/debug code lenses and UpdateTestExplorer notifications are only
  // produced for clients that declare the capabilities.
  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default
        .copy(testExplorerProvider = Some(true), debuggingProvider = Some(true))
    )

  // Test discovery only runs when the user interface is the Test Explorer
  // (TestSuitesProvider.isExplorerEnabled requires BOTH the client capability
  // above and this user setting; the default is CodeLenses).
  override protected def mbtConfig: UserConfiguration =
    super.mbtConfig.copy(testUserInterface = TestUserInterfaceKind.TestExplorer)

  override protected def bspConfig: UserConfiguration =
    super.bspConfig.copy(testUserInterface = TestUserInterfaceKind.TestExplorer)

  private val configPath: Path =
    PathIO.workingDirectory
      .resolve("target/mbt-sweep/sweep-config.json")
      .toNIO

  private lazy val config: Option[MbtSweep.SweepConfig] =
    if (Files.isRegularFile(configPath))
      Some(MbtSweep.SweepConfig.read(configPath))
    else None

  override def repoDir: AbsolutePath =
    config match {
      case Some(c) => AbsolutePath(c.repo)
      case None =>
        // No config: the throwaway server created by beforeEach still needs a
        // directory; the test itself skips.
        AbsolutePath(Files.createTempDirectory("mbt-sweep-unconfigured"))
    }

  override def projectViewTargets: List[String] =
    config.map(_.targets).getOrElse(Nil)

  override def probes: List[Probe] = Nil

  test("collect") {
    assume(
      config.nonEmpty,
      s"no sweep config at $configPath — run via tools/mbt-sweep/collect.sh",
    )
    val c = config.get
    require(
      c.mode == "bsp" || c.mode == "mbt",
      s"mode must be bsp or mbt, got: ${c.mode}",
    )
    val head = repoHead
    startPhase(c.mode).flatMap { _ =>
      sweep(c).map { records =>
        val out = Paths.get(c.out)
        MbtSweep.write(
          out,
          c.mode,
          head,
          metalsHead,
          c.targets,
          c.files,
          records,
        )
        scribe.info(
          s"[mbt-sweep] mode=${c.mode} files=${c.files.size} " +
            s"records=${records.size} -> $out"
        )
      }
    }
  }

  /** Mirror of one phase of [[BaseRealRepoMbtSuite.runDifferential]]. */
  private def startPhase(mode: String): Future[Unit] = {
    val cfg = if (mode == "mbt") mbtConfig else bspConfig
    phaseConfig = cfg
    cancelServer()
    newServer(s"repo-sweep-$mode")
    if (mode == "mbt") {
      client.selectedServer = Messages.ChooseBuildServer.mbt
      wipe(includeProjectView = false)
    } else {
      client.selectedServer = Messages.ChooseBuildServer.bsp
      client.generateBspAndConnect = Messages.GenerateBspAndConnect.yes
      wipe(includeProjectView = true)
    }
    writeProjectView()
    for {
      _ <- server.initialize()
      _ =
        if (mode == "bsp")
          client.generateBspAndConnect = Messages.GenerateBspAndConnect.yes
      _ <- server.initialized()
      _ <- server.didChangeConfiguration(cfg.toString)
      _ <-
        if (mode == "mbt")
          server.headServer.connectionProvider.buildServerPromise.future
        else Future.successful(())
      _ = server.assertBuildServerConnection()
    } yield ()
  }

  private def sweep(
      c: MbtSweep.SweepConfig
  ): Future[List[MbtSweep.SweepRecord]] =
    for {
      _ <- server.server.indexingPromise.future
      _ <- prepareFiles(c.files)
      // Let the real (classpath-backed) PC replace the fallback PC before
      // probing, otherwise the BSP oracle reads as empty.
      _ <- server.waitFor(3000)
      records <- c.files.foldLeft(
        Future.successful(List.empty[MbtSweep.SweepRecord])
      ) { (acc, file) =>
        acc.flatMap(sofar => sweepFile(file, c).map(sofar ++ _))
      }
      // Test discovery is push-based and can trail compilation; give the
      // accumulated UpdateTestExplorer notifications a moment to settle.
      _ <- server.waitFor(2000)
    } yield records :+ testExplorerRecord()

  private def sweepFile(
      file: String,
      c: MbtSweep.SweepConfig,
  ): Future[List[MbtSweep.SweepRecord]] = {
    val path = server.toPath(file)
    val text = path.readText
    val tdi = path.toTextDocumentIdentifier
    val idents = MbtSweep.identifiers(text, c.maxIdentsPerFile)
    val identRecords = idents.foldLeft(
      Future.successful(List.empty[MbtSweep.SweepRecord])
    ) { case (acc, (line, col, name)) =>
      val pos = new LspPosition(line, col)
      for {
        sofar <- acc
        definition <- server.fullServer
          .definition(new TextDocumentPositionParams(tdi, pos))
          .asScala
          .map(locs => renderLocations(locs.asScala.toSeq))
          .recover(renderError)
        hover <- server.fullServer
          .hover(new HoverExtParams(tdi, pos))
          .asScala
          .map(h =>
            TestHovers
              .renderAsString(text, Option(h), includeRange = false)
              .trim
          )
          .recover(renderError)
      } yield sofar ++ List(
        MbtSweep.SweepRecord(file, "definition", line, col, name, definition),
        MbtSweep.SweepRecord(file, "hover", line, col, name, hover),
      )
    }
    for {
      perIdent <- identRecords
      // File-level captures run AFTER the identifier sweep: the minutes it
      // takes give diagnostics, the lens model and test discovery time to
      // settle for this file.
      perFile <- Future.sequence(
        List(
          diagnosticsRecord(file, path),
          semanticTokensRecord(file, tdi, text),
          documentSymbolRecord(file),
          codeLensRecord(file),
        )
      )
    } yield perFile ++ perIdent
  }

  private def renderError: PartialFunction[Throwable, String] = { case e =>
    s"<error: ${e.getClass.getSimpleName}: ${e.getMessage}>"
  }

  private def diagnosticsRecord(
      file: String,
      path: AbsolutePath,
  ): Future[MbtSweep.SweepRecord] = Future {
    val rendered = client.diagnostics
      .getOrElse(path, Nil)
      .map { d =>
        val start = d.getRange.getStart
        val message =
          if (d.getMessage.isLeft) d.getMessage.getLeft
          else d.getMessage.getRight.getValue
        s"${start.getLine}:${start.getCharacter} " +
          s"${d.getSeverity} ${message.linesIterator.next()}"
      }
      .sorted
    MbtSweep.SweepRecord(
      file,
      "diagnostics",
      -1,
      -1,
      "",
      if (rendered.isEmpty) "<empty>" else rendered.mkString("\n"),
    )
  }

  private def semanticTokensRecord(
      file: String,
      tdi: org.eclipse.lsp4j.TextDocumentIdentifier,
      text: String,
  ): Future[MbtSweep.SweepRecord] =
    server.fullServer
      .semanticTokensFull(new SemanticTokensParams(tdi))
      .asScala
      .map { tokens =>
        if (tokens == null || tokens.getData == null || tokens.getData.isEmpty)
          "<empty>"
        else
          TestSemanticTokens.semanticString(
            text,
            tokens.getData().map(_.toInt).asScala.toList,
          )
      }
      .recover(renderError)
      .map(MbtSweep.SweepRecord(file, "semanticTokens", -1, -1, "", _))

  private def documentSymbolRecord(
      file: String
  ): Future[MbtSweep.SweepRecord] =
    server
      .documentSymbols(file)
      .map(_.trim)
      .recover(renderError)
      .map(MbtSweep.SweepRecord(file, "documentSymbol", -1, -1, "", _))

  /**
   * The run/debug/test lenses a client would display for this file (the "Run"
   * above a main method, the test-suite lenses). Single-shot:
   * `minExpectedLenses = 0` returns whatever the server has right now — most
   * library files legitimately have none.
   */
  private def codeLensRecord(file: String): Future[MbtSweep.SweepRecord] =
    server
      .codeLenses(file, maxRetries = 0, minExpectedLenses = 0)
      .map { lenses =>
        val rendered = lenses.map { lens =>
          val start = lens.getRange.getStart
          val title =
            Option(lens.getCommand).map(_.getTitle).getOrElse("<unresolved>")
          s"${start.getLine}:${start.getCharacter} <<$title>>"
        }.sorted
        if (rendered.isEmpty) "<empty>" else rendered.mkString("\n")
      }
      .recover(renderError)
      .map(MbtSweep.SweepRecord(file, "codeLens", -1, -1, "", _))

  /**
   * Everything the Test Explorer UI (the arrows next to test cases) would
   * have been told: the accumulated `UpdateTestExplorer` client commands.
   * One workspace-level record; absolute workspace URIs/paths are normalized
   * so the record diffs cleanly across modes.
   */
  private def testExplorerRecord(): MbtSweep.SweepRecord = {
    val workspaceUri = repoDir.toNIO.toUri.toString
    val rendered = client.clientCommands.asScala.toList
      .filter(_.getCommand == ClientCommands.UpdateTestExplorer.id)
      .flatMap(_.getArguments().asScala)
      .map(
        _.toString
          .replace(workspaceUri, "ws:/")
          .replace(repoDir.toString, "ws:")
      )
      .sorted
    MbtSweep.SweepRecord(
      "<workspace>",
      "testExplorer",
      -1,
      -1,
      "",
      if (rendered.isEmpty) "<empty>" else rendered.mkString("\n"),
    )
  }
}
