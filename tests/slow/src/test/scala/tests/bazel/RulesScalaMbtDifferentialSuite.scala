package tests.bazel

import scala.meta.io.AbsolutePath

/**
 * Differential MBT-vs-BSP navigation test against the real rules_scala repo.
 *
 * The harness ([[BaseRealRepoMbtSuite]]) is static; only [[repoDir]],
 * [[projectViewTargets]] and [[probes]] vary per repo. Scoped to a small set of
 * packages so the Bazel import stays fast on a dev machine.
 *
 * Run: `sbt --client "slow/testOnly tests.bazel.RulesScalaMbtDifferentialSuite"`
 * (set `METALS_MBT_TEST_REPO` to override the checkout path). Report is written
 * to `tests/slow/target/mbt-differential/`.
 */
class RulesScalaMbtDifferentialSuite
    extends BaseRealRepoMbtSuite("rules-scala-mbt-diff") {

  override def repoDir: AbsolutePath =
    AbsolutePath(
      Option(System.getenv("METALS_MBT_TEST_REPO"))
        .getOrElse("/Volumes/colimavol/rules_scala")
    )

  private val srcjars = "test/src/main/scala/scalarules/test/srcjars_with_java"

  override def projectViewTargets: List[String] = List(s"//$srcjars/...")

  override def probes: List[Probe] = List(
    Probe(
      s"$srcjars/MixedLanguageDependent.scala",
      "List(Java@@Source.line",
      DiffFeature.Definition,
      note = "def of Java symbol packaged inside a srcjar",
      category = "srcjar,java-interop",
    ),
    Probe(
      s"$srcjars/MixedLanguageDependent.scala",
      ", Scala@@Source.line)",
      DiffFeature.Definition,
      note = "def of Scala symbol packaged inside a srcjar",
      category = "srcjar",
    ),
    Probe(
      s"$srcjars/MixedLanguageDependent.scala",
      "JavaSource.li@@ne",
      DiffFeature.Hover,
      note = "hover on Java member from srcjar (expect String)",
      category = "srcjar,java-interop",
    ),
    Probe(
      s"$srcjars/MixedLanguageDependent.scala",
      "ScalaSource.li@@ne",
      DiffFeature.Hover,
      note = "hover on Scala member from srcjar (expect String)",
      category = "srcjar",
    ),
    Probe(
      s"$srcjars/MixedLanguageDependent.scala",
      "List(Java@@Source",
      DiffFeature.References,
      note = "references of JavaSource across files",
      category = "srcjar,java-interop",
    ),
    // NOTE: DocumentSymbol intentionally omitted here — the shared
    // `Semanticdbs.printTextDocument` test renderer throws a line-bounds error
    // on these tiny no-trailing-newline files (identically for BSP and MBT, so
    // no differential signal). The harness still supports the feature.
    Probe(
      s"$srcjars/JavaDependent.scala",
      "= Java@@Source.line",
      DiffFeature.Definition,
      note = "def of Java symbol from a second dependent file",
      category = "srcjar,java-interop",
    ),
  )

  test("differential") {
    runDifferential()
  }
}
