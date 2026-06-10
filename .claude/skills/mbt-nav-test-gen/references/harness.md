# Differential harness API reference

All paths are under `tests/slow/src/test/scala/tests/bazel/` (package
`tests.bazel`). The harness drives the *real* `MetalsLanguageServer` through
`TestingServer` — the same code path VS Code/vim use — and imports a real
on-disk checkout twice (BSP then MBT), running the same probes against each.

## Files

- `BaseRealRepoMbtSuite.scala` — the reusable base. Subclass it.
- `MbtDifferential.scala` — `Probe`, `DiffFeature`, `Discrepancy`,
  `MbtDifferentialReport` (status classification + markdown/JSON rendering).
- `RulesScalaMbtDifferentialSuite.scala` — example: root workspace, pathological
  srcjar package (expect MBT_EMPTY / MISMATCH).
- `SemanticdbExampleMbtDifferentialSuite.scala` — example: standalone healthy
  workspace `examples/semanticdb` (expect MATCH + a real cross-target definition
  bug).

## What you implement in a subclass

```scala
class MyMbtDifferentialSuite extends BaseRealRepoMbtSuite("my-suite-name") {
  override def repoDir: AbsolutePath            // the Bazel workspace root to import
  override def projectViewTargets: List[String] // .bazelproject `targets:` entries
  override def probes: List[Probe]              // the navigation probes
  test("differential") { runDifferential() }
}
```

- The constructor arg (`"my-suite-name"`) is the **report filename** →
  `tests/slow/target/mbt-differential/my-suite-name.{md,json}`.
- `repoDir` MUST be an existing checkout. The base safely points the workspace
  at it (overrides `createWorkspace`) and **never** deletes it; it only wipes
  generated state (`.metals`, `.bsp`, `.bazelbsp`, `.bazelproject`) before each
  phase and in `afterAll`. Do not call `cleanWorkspace()`/`cleanUnmanagedFiles()`.
- `projectViewTargets` becomes `.bazelproject`:
  `targets:\n    //pkg/...\n    //other:target`.

## What `runDifferential()` does

1. Phase BSP (oracle): `selectedServer = bsp`, `generateBspAndConnect = yes`,
   wipe + write `.bazelproject`, initialize/connect, then **await
   `server.server.indexingPromise.future`** (the determinism barrier — without
   it, cross-file references / cross-target definitions vary run-to-run because
   the semanticdb index isn't ready), then for each probe file open + focus +
   `compileFile` and `waitFor(3000)` (so the real classpath-backed PC replaces
   the fallback PC), then run all probes.
2. Phase MBT (under test): `selectedServer = mbt`, `mbtConfig`
   (`preferredBuildServer = MBT`, `referenceProvider = mbt`,
   `workspaceSymbolProvider = mbt`, `javaSymbolLoader = turbineClasspath`,
   `automaticImportBuild = All`), re-import, run the same probes.
3. Diff per probe → `Discrepancy` → write markdown + JSON report.

The two phases run in one test by swapping servers (`cancelServer()` +
`newServer()`); `phaseConfig` drives `newServer`'s initial user config.

## Probe model

```scala
Probe(file: String, query: String, feature: DiffFeature, note: String = "")
```

- `file` — workspace-relative path (relative to `repoDir`).
- `query` — for Definition/References/Hover this is a `@@`-marked snippet that is
  a **unique substring of the real file**; the cursor is at `@@`. The harness
  locates the snippet in the real file (`positionOf`) and calls the LSP endpoint
  directly — it does NOT use `TestingServer.offsetParams`/`hover`/
  `getReferenceLocations`, because those call `positionFromString`, which
  *overwrites the whole file buffer with the bare snippet* (fine for synthetic
  inline tests, wrong for whole-file probes against a real checkout). For
  DocumentSymbol the query is ignored (pass `""`).
- `feature` — `DiffFeature.Definition | References | Hover | DocumentSymbol`.
- `note` — human intent; also a good place to record the expected answer.

`@@` example: file contains `val foo = new Foo()`. To probe the definition of
`Foo`, use query `new F@@oo()`.

## Result rendering & status

- Locations render as `<normalized-uri>:startLine:startCol-endLine:endCol`,
  sorted. URIs are normalized to repo-relative; jar entries → `jar:<entry>`;
  Metals read-only virtual docs → `readonly:<path>`; out-of-repo → `external:<name>`.
- Hover renders to its markdown string; DocumentSymbol to the semanticdb text.
- `MbtDifferentialReport.statusOf`: `MATCH` (equal, non-empty) /
  `MISMATCH` (differ, both non-empty) / `MBT_EMPTY` (BSP has answer, MBT doesn't)
  / `BSP_EMPTY` (no oracle — judge) / `BOTH_EMPTY`. An exception during capture
  renders as `<error: ...>` and counts as empty.

## Running

```bash
sbt --client "slow/Test/compile"
sbt --client "slow/testOnly tests.bazel.MyMbtDifferentialSuite"
```

Override the checkout with `METALS_MBT_TEST_REPO`. A warm Bazel cache makes runs
~1–3 min; a cold standalone workspace adds its first build (~1 min for tiny
modules, more for large ones). The Metals MCP tools are preferred for
compile/test if available; otherwise `sbt --client` is fine.
