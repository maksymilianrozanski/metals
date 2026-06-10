# High-risk file catalog

Patterns where MBT import is most likely to mis-resolve navigation, how to find
them from **build files + structure** (never from `mbt.json`), why they're
risky, and what to probe. Run the finders from the workspace root. Adjust globs
to the repo.

## 1. srcjar / source-jar sources

MBT lists `.srcjar` srcs as opaque "sources" instead of expanding the Java/Scala
files inside, so symbols defined in a srcjar may be unnavigable, and the actual
loose source files next to the srcjar can confuse resolution.

- Find: `grep -rl "\.srcjar" --include=BUILD --include=BUILD.bazel .` and
  `find . -name '*.srcjar'`.
- Probe: from a file that *depends* on a srcjar target, `Definition` and `Hover`
  on a symbol defined inside the srcjar; expect MBT to differ from BSP.
- rules_scala example: `test/src/main/scala/scalarules/test/srcjars_with_java/`
  (`MixedLanguageDependent.scala` references `JavaSource`/`ScalaSource` packaged
  in `MixedLanguageSourceJar.srcjar`).

## 2. Custom / non-standard source roots

Files not under `src/main/<lang>` or `src/test/<lang>` — `testFixtures/`,
`gen_src/`, `it/`, example dirs, or sources nested under odd Bazel target dirs.
MBT may attribute them to the wrong target/namespace or omit them.

- Find: list `srcs` globs in BUILD files that point outside the conventional
  layout; `find . -name '*.scala' -o -name '*.java'` and look for roots that
  aren't `src/{main,test}`.
- Probe: `Definition`/`References` crossing from a normal source into one of
  these files (and vice versa).

## 3. Multiple Scala versions / version-specific code

Dirs or files that exist per Scala version, or targets pinned to a non-default
version. MBT's version detection is unreliable (see harness gotchas), so the
presentation compiler may load the wrong (or no) compiler for these.

- Find: `grep -rn "scala_version" MODULE.bazel WORKSPACE */BUILD`;
  `find . -path '*scala2_1*' -o -path '*scala3*' -o -name '*_3.scala'`;
  look for `select_for_scala_version` or version-suffixed targets/labels.
- Probe: `Hover`/`Definition` inside a version-specific file; compare the Scala
  version MBT wrote in `mbt.json` against the toolchain Bazel actually uses
  (`bazel build` output shows e.g. `scala_compiler_2_12_21`).
- rules_scala examples: `scala/private/source_compat/{scala2_12,scala2_13,scala3}/`,
  `test_version/version_specific_tests_dir/MacroTest.scala` vs `MacroTest_3.scala`.

## 4. Java/Scala interop

Scala calling Java and Java calling Scala across targets. Mixed-language symbol
resolution (and the Java symbol loader / turbine classpath) is a frequent MBT
weak spot.

- Find: in a Scala file, `grep` for references to types whose definition is a
  `.java` file in another target; and the reverse.
- Probe: `Definition`/`Hover` from Scala onto a Java symbol and from Java onto a
  Scala symbol.
- rules_scala examples: `test/HelloLib.scala` ↔ `test/OtherJavaLib.java`;
  `test/JavaUsesScalaStdLib.java`.

## 5. Generated sources (protobuf, codegen)

Sources produced by a rule at build time (proto → Scala/Java, custom
generators). MBT may not know the generated outputs' locations, breaking
navigation into generated symbols.

- Find: `grep -rn "proto_library\|scala_proto\|genrule\|_gen" --include=BUILD .`;
  dirs like `proto/`, `gen_src/`.
- Probe: `Definition`/`Hover` on a generated type referenced from hand-written
  code.
- rules_scala examples: `test/proto/`, `test/gen_src/`.

## 6. Cross-target / cross-package references

Even with standard layout, a reference whose definition lives in a *different*
Bazel target/package exercises MBT's namespace dependency graph. MBT may resolve
hover (type known) yet fail go-to-definition across namespaces — a real observed
bug.

- Find: read `deps` in BUILD files; pick a source that uses a symbol defined in a
  dependency target.
- Probe: `Definition` AND `Hover` on the same cross-target symbol — divergence
  between the two (hover MATCH but definition MBT_EMPTY) is a strong signal.
- rules_scala example: `examples/semanticdb` `Main.scala` (`//:hello`) →
  `Foo.scala` (`//:hello_lib`).

## Choosing a balanced probe set

A good suite mixes:
- a **healthy** standalone workspace (single pinned Scala version) so some probes
  MATCH — this proves the harness isn't just reporting noise; and
- a few **high-risk** packages so real bugs surface.

For each probed symbol, prefer adding **both** a `Definition` and a `Hover`
probe: they share the PC but exercise different MBT paths, and a hover/definition
split is one of the clearest bug signatures.
