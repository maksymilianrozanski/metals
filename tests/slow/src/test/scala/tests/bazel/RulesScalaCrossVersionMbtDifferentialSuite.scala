package tests.bazel

import scala.meta.io.AbsolutePath

/**
 * Differential MBT-vs-BSP navigation test for CROSS-COMPILED (multi-Scala-
 * version) sources in the real rules_scala ROOT workspace.
 *
 * `//third_party/dependency_analyzer/src/main:dependency_analyzer` cross-
 * compiles via `select_for_scala_version(...)`: the `dependencyanalyzer/` dir
 * holds the Scala 2 branch (ACTIVE in the default 2.12 config) and
 * `dependencyanalyzer3/` the Scala 3 branch (INACTIVE by default — but bazel
 * query flattens select(), so MBT still sees those srcs). Before commit
 * ddddcfd1 the inactive Scala 3 sources were tagged with the project-wide max
 * Scala version; with this scope NARROWED so no Scala 3-default target is in
 * the project view, that max collapsed to Scala 2 and the Scala 3 files were
 * handed to a Scala 2 presentation compiler — navigation came back empty. The
 * fix resolves each inactive source's version from its select() branch
 * (`@rules_scala_config//:scala_version_x_y_z`), so the dependencyanalyzer3
 * probes should now resolve on the MBT side.
 *
 * NOTE on the oracle: BSP only indexes sources of CONFIGURED targets, so it
 * may legitimately return nothing for the inactive `dependencyanalyzer3/`
 * files (BSP_EMPTY / BOTH_EMPTY there is itself a signal, not noise — judge
 * the MBT answer on its own merits in triage).
 *
 * Run: `sbt --client "slow/testOnly
 * tests.bazel.RulesScalaCrossVersionMbtDifferentialSuite"` (set
 * `METALS_MBT_TEST_REPO` to override the checkout path). Report is written to
 * `tests/slow/target/mbt-differential/`.
 */
class RulesScalaCrossVersionMbtDifferentialSuite
    extends BaseRealRepoMbtSuite("rules-scala-cross-version-mbt-diff") {

  override def repoDir: AbsolutePath =
    AbsolutePath(
      Option(System.getenv("METALS_MBT_TEST_REPO"))
        .getOrElse("/Volumes/colimavol/rules_scala")
    )

  private val pkg = "third_party/dependency_analyzer/src/main"
  private val scala2Dir = s"$pkg/io/bazel/rulesscala/dependencyanalyzer"
  private val scala3Dir = s"$pkg/io/bazel/rulesscala/dependencyanalyzer3"

  // CRITICAL: keep the scope narrow so NO Scala 3-default target is imported —
  // that is what made the pre-fix max-version fallback collapse to Scala 2 and
  // reproduce the bug. (All targets under this package use the workspace
  // default Scala 2.12 config.) Do NOT widen to //...
  override def projectViewTargets: List[String] = List(
    "//third_party/dependency_analyzer/..."
  )

  override def probes: List[Probe] = List(
    // --- Scala 3 branch (INACTIVE in default config) ---
    Probe(
      s"$scala3Dir/DependencyAnalyzer.scala",
      "given Dependency@@AnalyzerSettings =",
      DiffFeature.Definition,
      note = "Scala 3 inactive-branch file -> shared DependencyAnalyzerSettings"
        + " (expect dependencyanalyzer/DependencyAnalyzerSettings.scala;"
        + " pre-fix MBT was empty: Scala 3 source under Scala 2 PC)",
      category = "multi-version",
    ),
    Probe(
      s"$scala3Dir/DependencyAnalyzer.scala",
      "given Dependency@@AnalyzerSettings =",
      DiffFeature.Hover,
      note = "hover on the same Scala 3 usage of DependencyAnalyzerSettings",
      category = "multi-version",
    ),
    Probe(
      s"$scala3Dir/DependencyAnalyzer.scala",
      "Dependency@@AnalyzerSettings.parseSettings",
      DiffFeature.Definition,
      note = "Scala 3 file, cursor on the companion-object usage"
        + " `DependencyAnalyzerSettings.parseSettings` (the commit-message"
        + " scenario; expect object DependencyAnalyzerSettings in"
        + " dependencyanalyzer/DependencyAnalyzerSettings.scala)",
      category = "multi-version",
    ),
    Probe(
      s"$scala3Dir/DependencyAnalyzer.scala",
      "Dependency@@AnalyzerSettings.parseSettings",
      DiffFeature.Hover,
      note = "hover on the same Scala 3 companion-object usage",
      category = "multi-version",
    ),
    Probe(
      s"$scala3Dir/DependencyAnalyzer.scala",
      "handles = Dependency@@TrackingMethod.Ast,",
      DiffFeature.Definition,
      note = "second cross-version symbol from the Scala 3 file"
        + " (DependencyTrackingMethod is also defined in"
        + " DependencyAnalyzerSettings.scala)",
      category = "multi-version",
    ),
    // --- Scala 2 branch (ACTIVE in default config) ---
    Probe(
      s"$scala2Dir/DependencyAnalyzer.scala",
      "settings: Dependency@@AnalyzerSettings = null",
      DiffFeature.Definition,
      note = "Scala 2 active-branch file -> shared DependencyAnalyzerSettings"
        + " (worked even before the fix)",
      category = "multi-version",
    ),
    Probe(
      s"$scala2Dir/DependencyAnalyzer.scala",
      "settings: Dependency@@AnalyzerSettings = null",
      DiffFeature.Hover,
      note = "hover on the same Scala 2 usage of DependencyAnalyzerSettings",
      category = "multi-version",
    ),
    Probe(
      s"$scala2Dir/DependencyAnalyzer.scala",
      "Dependency@@AnalyzerSettings.parseSettings",
      DiffFeature.Definition,
      note = "Scala 2 file, cursor on the companion-object usage"
        + " `DependencyAnalyzerSettings.parseSettings` (mirror of the Scala 3"
        + " probe; expect object DependencyAnalyzerSettings)",
      category = "multi-version",
    ),
    // --- same-file healthy control (expect MATCH) ---
    Probe(
      s"$scala2Dir/DependencyAnalyzer.scala",
      "= findUsed@@JarsAndPositions",
      DiffFeature.Definition,
      note = "same-file def: findUsedJarsAndPositions is defined further down"
        + " in this very file (MATCH control)",
      category = "same-file",
    ),
    Probe(
      s"$scala2Dir/DependencyAnalyzer.scala",
      "= findUsed@@JarsAndPositions",
      DiffFeature.Hover,
      note = "same-file hover control (expect Map[AbstractFile,"
        + " global.Position])",
      category = "same-file",
    ),
  )

  test("differential") {
    runDifferential()
  }
}
