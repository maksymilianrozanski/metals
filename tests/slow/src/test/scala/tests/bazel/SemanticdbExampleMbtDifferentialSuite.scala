package tests.bazel

import scala.meta.io.AbsolutePath

/**
 * Differential MBT-vs-BSP test against a HEALTHY standalone workspace
 * (`rules_scala/examples/semanticdb`). Unlike the root workspace — where MBT
 * mis-detects the Scala version because every registered version is visible —
 * this module pins `scala_version = "2.13.18"` in its own MODULE.bazel, so MBT
 * resolves the right version and the presentation compiler works. The probes
 * exercise clean cross-file / cross-target navigation (`Main` -> `Foo`) and are
 * expected to largely MATCH, demonstrating the harness distinguishes agreement
 * from genuine MBT bugs.
 *
 * Run: `sbt --client "slow/testOnly tests.bazel.SemanticdbExampleMbtDifferentialSuite"`
 */
class SemanticdbExampleMbtDifferentialSuite
    extends BaseRealRepoMbtSuite("semanticdb-example-mbt-diff") {

  override def repoDir: AbsolutePath = {
    val root = Option(System.getenv("METALS_MBT_TEST_REPO"))
      .getOrElse("/Volumes/colimavol/rules_scala")
    AbsolutePath(root).resolve("examples/semanticdb")
  }

  override def projectViewTargets: List[String] = List("//...")

  override def probes: List[Probe] = List(
    // Same-file/same-target baseline — MBT should handle this, so expect MATCH.
    Probe(
      "Main.scala",
      "println(f@@oo.sayHello)",
      DiffFeature.Definition,
      note = "def of local val foo (same file) — expect MATCH",
      category = "same-file",
    ),
    Probe(
      "Main.scala",
      "println(f@@oo.sayHello)",
      DiffFeature.Hover,
      note = "hover on local val foo (same file) — expect MATCH (: Foo)",
      category = "same-file",
    ),
    // Cross-target probes below — exercise MBT's namespace dependency graph.
    Probe(
      "Main.scala",
      "new F@@oo()",
      DiffFeature.Definition,
      note = "def of Foo (cross-target //:hello -> //:hello_lib)",
      category = "cross-target",
    ),
    Probe(
      "Main.scala",
      "foo.say@@Hello",
      DiffFeature.Definition,
      note = "def of Foo.sayHello (cross-file member)",
      category = "cross-target",
    ),
    Probe(
      "Main.scala",
      "foo.say@@Hello",
      DiffFeature.Hover,
      note = "hover on Foo.sayHello (expect : String)",
      category = "cross-target",
    ),
    Probe(
      "Foo.scala",
      "def say@@Hello",
      DiffFeature.References,
      note = "references of sayHello (expect Main usage)",
      category = "cross-target",
    ),
    Probe(
      "Foo.scala",
      "class F@@oo",
      DiffFeature.References,
      note = "references of Foo (expect Main usage)",
      category = "cross-target",
    ),
  )

  test("differential") {
    runDifferential()
  }
}
