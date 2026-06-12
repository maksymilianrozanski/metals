package tests.bazel

import java.nio.file.Files

/** Server-free checks of the sweep data layer. */
class MbtSweepUnitSuite extends munit.FunSuite {

  test("identifiers-skip-keywords-literals-comments") {
    val code =
      """|package a.b
         |// commentWord
         |object Foo {
         |  val bar = "stringWord" + 42
         |}
         |""".stripMargin
    val names = MbtSweep.identifiers(code, max = 100).map { case (_, _, name) =>
      name
    }
    assertEquals(names, List("a", "b", "Foo", "bar", "+"))
  }

  test("identifiers-cap-spreads-evenly") {
    val code = (1 to 40).map(i => s"val name$i = name${i + 1}").mkString("\n")
    val idents = MbtSweep.identifiers(code, max = 20)
    assert(idents.size <= 20, s"expected <= 20, got ${idents.size}")
    val lines = idents.map { case (line, _, _) => line }
    assert(
      lines.last - lines.head > 30,
      s"expected spread across the file, got lines $lines",
    )
  }

  test("snapshot-write-read-roundtrip") {
    val out = Files.createTempDirectory("mbt-sweep").resolve("snap.jsonl")
    val records = List(
      MbtSweep.SweepRecord("a/B.scala", "definition", 3, 7, "Foo",
        "a/B.scala:1:0-1:3"),
      MbtSweep.SweepRecord("a/B.scala", "diagnostics", -1, -1, "", "<empty>"),
    )
    MbtSweep.write(
      out,
      mode = "mbt",
      repoHead = "abc123",
      metalsHead = "def456+dirty",
      targets = List("//a/..."),
      files = List("a/B.scala"),
      records = records,
    )
    val snapshot = MbtSweep.read(out)
    assertEquals(snapshot.mode, "mbt")
    assertEquals(snapshot.repoHead, "abc123")
    assertEquals(snapshot.metalsHead, "def456+dirty")
    assertEquals(snapshot.records, records)
  }
}
