package tests.bazel

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.{util => ju}

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtNamespace
import scala.meta.internal.metals.mbt.importer.BazelSrcjarSources
import scala.meta.io.AbsolutePath

import tests.BaseSuite

class BazelSrcjarSourcesSuite extends BaseSuite {

  private def newWorkspace(): AbsolutePath =
    AbsolutePath(Files.createTempDirectory("srcjar-workspace"))

  private def writeSrcjar(
      workspace: AbsolutePath,
      relativePath: String,
      entries: (String, String)*
  ): Unit = {
    val path = workspace.resolve(relativePath).toNIO
    Files.createDirectories(path.getParent)
    val zip = new ZipOutputStream(Files.newOutputStream(path))
    try
      entries.foreach { case (name, content) =>
        zip.putNextEntry(new ZipEntry(name))
        zip.write(content.getBytes("UTF-8"))
        zip.closeEntry()
      }
    finally zip.close()
  }

  test("extracts-only-sources-and-prevents-zip-slip") {
    val workspace = newWorkspace()
    writeSrcjar(
      workspace,
      "pkg/Lib.srcjar",
      "pkg/A.scala" -> "class A",
      "B.java" -> "class B {}",
      "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0",
      "../Evil.scala" -> "class Evil",
    )
    val Some(relativeDir) =
      BazelSrcjarSources.materialize(workspace, "pkg/Lib.srcjar")
    val outDir = workspace.resolve(relativeDir)
    assert(outDir.resolve("pkg/A.scala").isFile)
    assert(outDir.resolve("B.java").isFile)
    assert(
      !outDir.resolve("META-INF/MANIFEST.MF").isFile,
      "non-source kept out",
    )
    assert(
      !outDir.toNIO.getParent.resolve("Evil.scala").toFile.exists(),
      "zip-slip entry must not escape the extraction directory",
    )
  }

  test("re-extracts-only-when-the-srcjar-changes") {
    val workspace = newWorkspace()
    writeSrcjar(workspace, "pkg/Lib.srcjar", "A.scala" -> "class A")
    val Some(relativeDir) =
      BazelSrcjarSources.materialize(workspace, "pkg/Lib.srcjar")
    val extracted = workspace.resolve(relativeDir).resolve("A.scala").toNIO
    val firstMtime = Files.getLastModifiedTime(extracted)

    assertEquals(
      BazelSrcjarSources.materialize(workspace, "pkg/Lib.srcjar"),
      Some(relativeDir),
    )
    assertEquals(
      Files.getLastModifiedTime(extracted),
      firstMtime,
      "unchanged srcjar must not be re-extracted",
    )

    writeSrcjar(workspace, "pkg/Lib.srcjar", "A.scala" -> "class A2")
    Files.setLastModifiedTime(
      workspace.resolve("pkg/Lib.srcjar").toNIO,
      java.nio.file.attribute.FileTime.fromMillis(
        firstMtime.toMillis + 10_000
      ),
    )
    BazelSrcjarSources.materialize(workspace, "pkg/Lib.srcjar")
    assertNoDiff(Files.readString(extracted), "class A2")
  }

  test("materialize-all-rewrites-srcjar-sources-only") {
    val workspace = newWorkspace()
    writeSrcjar(workspace, "pkg/Lib.srcjar", "A.scala" -> "class A")
    val namespace = new MbtNamespace(
      sources = List("pkg/Dependent.scala", "pkg/Lib.srcjar").asJava,
      scalacOptions = ju.Collections.emptyList(),
      javacOptions = ju.Collections.emptyList(),
      dependencyModules = ju.Collections.emptyList(),
      scalaVersion = "2.13.16",
      javaHome = null,
    )
    val build = MbtBuild(
      ju.Collections.emptyList(),
      new ju.LinkedHashMap(ju.Map.of("//pkg", namespace)),
    )
    val rewritten = BazelSrcjarSources.materializeAll(workspace, build)
    val sources =
      rewritten.getNamespaces.get("//pkg").getSources.asScala.toList
    assertEquals(
      sources,
      List(
        "pkg/Dependent.scala",
        ".metals/mbt-srcjar-sources/pkg_Lib.srcjar",
      ),
    )
    // A srcjar that cannot be read keeps its original entry.
    val missing = namespace.copy(sources = List("pkg/Missing.srcjar").asJava)
    val kept = BazelSrcjarSources.materializeAll(
      workspace,
      MbtBuild(
        ju.Collections.emptyList(),
        new ju.LinkedHashMap(ju.Map.of("//pkg", missing)),
      ),
    )
    assertEquals(
      kept.getNamespaces.get("//pkg").getSources.asScala.toList,
      List("pkg/Missing.srcjar"),
    )
  }
}
