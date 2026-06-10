// TEMPLATE — copy into tests/slow/src/test/scala/tests/bazel/ and fill the
// placeholders (CLASS NAME, "suite-name", repoDir, projectViewTargets, probes).
// The constructor arg becomes the report filename:
//   tests/slow/target/mbt-differential/<suite-name>.{md,json}
//
// Probe queries are `@@`-marked snippets that must be UNIQUE substrings of the
// real file. Prefer Definition/References/Hover; use DocumentSymbol only on
// larger, normal files. Pair Definition + Hover on the same symbol when you can.
package tests.bazel

import scala.meta.io.AbsolutePath

class TemplateMbtDifferentialSuite
    extends BaseRealRepoMbtSuite("template-mbt-diff") {

  override def repoDir: AbsolutePath = {
    val root = Option(System.getenv("METALS_MBT_TEST_REPO"))
      .getOrElse("/Volumes/colimavol/rules_scala")
    // For a standalone nested workspace, resolve into it, e.g.:
    //   AbsolutePath(root).resolve("examples/semanticdb")
    AbsolutePath(root)
  }

  // Keep small — bazelbsp builds every target listed here.
  override def projectViewTargets: List[String] = List(
    "//path/to/package/..."
  )

  override def probes: List[Probe] = List(
    Probe(
      "path/to/File.scala",
      "some uniq@@ue snippet",
      DiffFeature.Definition,
      note = "what this checks / expected target",
      // category tags the high-risk pattern so the diff analyzer can aggregate
      // failures across files/repos: srcjar | cross-target | java-interop |
      // multi-version | custom-root | generated | same-file
      category = "cross-target",
    )
    // ... more probes ...
  )

  test("differential") {
    runDifferential()
  }
}
