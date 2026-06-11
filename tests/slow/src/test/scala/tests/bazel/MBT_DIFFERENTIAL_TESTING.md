# MBT differential navigation testing

Automated, deterministic testing of **MBT import** code-navigation correctness in
Metals v2, against **real** repositories, with **no mocks**. MBT (`.metals/mbt.json`)
is how Metals v2 imports Bazel (and other) projects; it is new and buggy. This
framework finds and characterizes those bugs.

> Scope: this document is the design spec + rationale for the test framework. The
> day-to-day "how to author a test" playbook lives in the skill at
> `.claude/skills/mbt-nav-test-gen/`. The agents that drive it live in
> `.claude/agents/`.

## 1. Idea: differential MBT-vs-BSP testing

We cannot assert against hand-written "expected" values, because nothing about
MBT is trusted yet. Instead we import the **same real repo twice through the real
Metals server** and compare:

- **MBT** — the build server under test (`preferredBuildServer = MBT`).
- **Bazel BSP / bazelbsp** — the established build server, used as the **oracle /
  source of truth**.

The same navigation probes (go-to-definition, references, hover, document
symbols) run against both. Where MBT disagrees with BSP, MBT is *likely* wrong —
but BSP is **not gospel** (see §6), so a human/agent judges each discrepancy.

Crucially, `TestingServer` instantiates the **real** `MetalsLanguageServer` and
runs the **real** `MbtImport` + LSP handlers — the same code path VS Code/vim
drive. Only the *client* is scripted. So the framework tests the real import
flow, not a simplified model.

## 2. Components

All under `tests/slow/src/test/scala/tests/bazel/` (package `tests.bazel`):

| File | Role |
|------|------|
| `BaseRealRepoMbtSuite.scala` | The reusable harness: imports a real checkout twice (BSP then MBT), probes both, writes a report. |
| `MbtDifferential.scala` | Model: `Probe`, `DiffFeature`, `Discrepancy`, and `MbtDifferentialReport` (status classification + markdown/JSON rendering). |
| `RulesScalaMbtDifferentialSuite.scala` | Concrete suite: rules_scala root workspace, the srcjar package (pathological — exercises bugs). |
| `SemanticdbExampleMbtDifferentialSuite.scala` | Concrete suite: standalone `examples/semanticdb` workspace (healthy — shows MATCH + cross-target bugs). |
| `RulesScalaMbtSmokeSuite.scala`, `RulesScalaBspSmokeSuite.scala` | Phase-0 feasibility smoke tests (MBT-only / BSP-only). Redundant now; removal candidates. |

Supporting tooling outside this directory:
- Skill: `.claude/skills/mbt-nav-test-gen/` (generate + triage playbook).
- Agents: `.claude/agents/mbt-smoke-test-creator.md`, `.claude/agents/mbt-diff-analyzer.md`.

## 3. Implementation specification

### 3.1 A concrete suite

A suite supplies only what varies per repo; the harness is static:

```scala
class FooMbtDifferentialSuite extends BaseRealRepoMbtSuite("foo-mbt-diff") {
  override def repoDir: AbsolutePath            // existing on-disk checkout
  override def projectViewTargets: List[String] // .bazelproject `targets:`
  override def probes: List[Probe]
  test("differential") { runDifferential() }
}
```

The constructor arg is the report filename (`tests/slow/target/mbt-differential/foo-mbt-diff.{md,json}`).
`repoDir` defaults via `METALS_MBT_TEST_REPO` (falls back to a hard-coded path).

### 3.2 Probe model (`MbtDifferential.scala`)

```scala
Probe(file: String, query: String, feature: DiffFeature,
      note: String = "", category: String = "")
```

- `file` — repo-relative path.
- `query` — a `@@`-marked snippet that is a **unique substring of the real file**;
  the cursor is at `@@`. (`DocumentSymbol` ignores it.)
- `feature` — `Definition | References | Hover | DocumentSymbol`.
- `note` — human intent / expected answer.
- `category` — the high-risk pattern tag (`srcjar`, `cross-target`,
  `java-interop`, `multi-version`, `custom-root`, `generated`, `same-file`). The
  diff analyzer aggregates by it to turn many scattered failures into one named
  implementation gap.

### 3.3 The differential run (`runDifferential`)

Two phases in a single test, swapping the build server with
`cancelServer()` + `newServer()` (a `phaseConfig` var drives `newServer`'s initial
user config):

1. **Phase BSP (oracle):** `selectedServer = bsp`, `generateBspAndConnect = yes`;
   wipe generated state; write `.bazelproject`; initialize + connect; run probes.
2. **Phase MBT (under test):** `selectedServer = mbt`, MBT user config
   (`preferredBuildServer = MBT`, `referenceProvider/workspaceSymbolProvider = mbt`,
   `javaSymbolLoader = turbineClasspath`, `automaticImportBuild = All`); re-import;
   run the same probes.
3. Diff per probe → `Discrepancy` → write report.

### 3.4 Readiness barriers (determinism)

Before probing in each phase (`runProbes`):
1. `await server.server.indexingPromise.future` — the **initial-index barrier**.
   Without it, cross-file references / cross-target definitions vary run-to-run
   because the semanticdb index isn't ready. (The MBT fallback service completes
   this immediately; BSP gates on the index.)
2. For each probed file: `didOpen` + `didFocus` +
   `fullServer.getServiceFor(path).compilations.compileFile(path)` — load the real
   classpath-backed PC.
3. `waitFor(3000)` — let the real PC replace the fallback PC on the BSP side.

With these, the same suite produces identical results across runs (verified 3/3).

### 3.5 Probing without mutating the file (`positionOf`)

Probes call the LSP endpoints **directly** on a position computed from the real
file. We deliberately do **not** use `TestingServer.offsetParams` / `hover` /
`getReferenceLocations`, because those route through `positionFromString`, which
`didChange`-**overwrites the whole file buffer with the bare `@@` snippet** —
correct for synthetic inline tests, wrong for whole-file probes against a real
checkout (it produced false `MATCH`es, e.g. `package <empty>` hovers).
`positionOf` reads the real file, finds the unique snippet, computes the LSP
`Position`, and we call `fullServer.{definition, references, hover}` (hover via
`new HoverExtParams`, rendered with `tests.TestHovers.renderAsString`).

### 3.6 Report

Written to `tests/slow/target/mbt-differential/<suite>.{md,json}` (gitignored
build output). Each report stamps:
- `metalsHead` — the Metals version under test (short SHA, `+dirty` if the tree
  has uncommitted changes) — so cross-version diffs are traceable.
- `repoHead` — the target repo commit.
- per result: `file`, `feature`, `category`, `query`, `note`, `status`, `bsp`, `mbt`.

Status classification (`MbtDifferentialReport.statusOf`):

| Status | Meaning |
|--------|---------|
| `MATCH` | MBT == BSP, both non-empty. Agreement. |
| `MISMATCH` | Both non-empty but differ. Judge (could be MBT bug *or* MBT better). |
| `MBT_EMPTY` | BSP has an answer, MBT doesn't. Usually an MBT bug. |
| `BSP_EMPTY` | No oracle. Judge manually. |
| `BOTH_EMPTY` | Neither answered. No signal. |

Locations are normalized (repo-relative; jar entries → `jar:<entry>`; Metals
read-only docs → `readonly:<path>`; out-of-repo → `external:<name>`), so MBT and
BSP results are comparable.

### 3.7 Safety

The workspace is an **existing checkout**, so the harness must never destroy it:
- `createWorkspace` is overridden to return `repoDir` without deleting.
- `cleanWorkspace()` / `cleanUnmanagedFiles()` (which delete the whole workspace)
  are **never** called.
- Only generated state is removed: `.metals`, `.bsp`, `.bazelbsp`, `.bazelproject`
  (before each phase and in `afterAll`). The Bazel output base (the warm cache)
  lives outside the checkout and is preserved.

### 3.8 Running

```bash
cd /Volumes/colimavol/metals
sbt --client "slow/Test/compile"
sbt --client "slow/testOnly tests.bazel.RulesScalaMbtDifferentialSuite"
# override the checkout: METALS_MBT_TEST_REPO=/path/to/repo
```

Prerequisites: `bazel` on PATH (rules_scala pins 7.7.1 via `.bazelversion`); a
warm cache (first import may need network); bazelbsp `4.0.3` is fetched by Metals.
The suite **always passes** — it produces a report; it does not fail on
MISMATCH/MBT_EMPTY. The report is the deliverable. Dev-machine only; not CI-gated.

## 4. The agents and the bug-fixing loop

- **mbt-smoke-test-creator** — given a GitHub URL or local path, finds high-risk
  files (from build files + structure, never `mbt.json`), generates a
  `*MbtDifferentialSuite`, runs it, and returns a likely-bug list.
- **mbt-diff-analyzer** — runs selected suites under **two code versions**
  (typically before-fix vs after-fix), diffs the reports, judges expected-vs-
  unexpected from `git diff`, aggregates failures by `category` into named
  implementation gaps, and emits precise reproducible scenarios.

Loop: smoke-test-creator surfaces bugs → engineer/fixer changes Metals →
diff-analyzer runs before/after and reports what was `FIXED` / `REGRESSED`. That
before/after diff is the test-suite feedback after a fix.

## 5. Design decisions (and why)

1. **In-process MUnit via `TestingServer`** (not an out-of-process LSP client or
   nvim). Maximum fidelity (the real server + real import flow, same code path as
   editors), deterministic, least flaky, lowest effort. The cost is tight coupling
   to Metals internals — accepted deliberately (see §7).
2. **Differential MBT-vs-BSP, BSP = oracle** (not golden values). MBT is untrusted
   and golden values would be guesses; BSP gives an independent reference. A human
   /agent judges discrepancies because BSP is not always right.
3. **In-place real checkout** (not a per-run copy). Reuses the warm Bazel cache
   (output base is keyed by workspace path), so runs are fast. Safety constraints
   in §3.7 make this non-destructive.
4. **`positionOf` instead of TestingServer's buffer-overwriting helpers** (§3.5) —
   correctness for whole-file probes.
5. **`category` on probes** — enables cross-file/cross-repo pattern aggregation,
   the unit a fix actually targets.
6. **`metalsHead` stamped in reports** — makes cross-version comparison traceable.
7. **`indexingPromise` barrier** — determinism for cross-target/cross-file probes.
8. **Single repo + overlay for cross-version comparison** (rather than splitting
   into a runner repo + a code-under-test repo). See §7.

## 6. Known limitations / caveats

- **BSP is the oracle, not gospel.** Observed: a definition into a srcjar where
  BSP resolves to `.class:0:0` (degenerate) while MBT points at the real source
  line — MBT is *better* there. Always keep judgment in the loop.
- **bazelbsp is Metals-team-deprioritized.** `BazelLspSuite` is `@IgnoreSuite`
  ("we are not using the bazelbsp server anyways"). Empirically bazelbsp 4.0.3
  imports rules_scala fine, so it's a valid oracle — but it could bit-rot. If it
  is dropped, the fallback oracle is **semanticdb-from-build** (rules_scala emits
  semanticdb), which is build-tool-independent.
- **Scala-version mis-detection in root workspaces.** `BazelMbtImporter`
  `queryScalaVersionFromDeps` takes `headOption` over `scala-library-X.Y.Z` dep
  labels; a workspace that registers several Scala versions (rules_scala) makes
  MBT pick an arbitrary one (often `2.11.12`), so its PC can't load and navigation
  is empty *everywhere*. → For MATCH demonstrations use a **standalone
  single-version workspace** (own `MODULE.bazel` with `scala_version = "X.Y.Z"`);
  root packages are still good for bug-hunting.
- **`documentSymbol` renderer is fragile.** The shared `Semanticdbs.printTextDocument`
  test renderer throws a line-bounds error on tiny / no-trailing-newline files
  (identically on both servers → no signal). Prefer Definition/References/Hover.
- **Per-package Bazel build cost.** bazelbsp builds every target in the project
  view, so keep `projectViewTargets` small; warm standalone workspaces first.
- **Determinism** relies on the indexing barrier plus a fixed `waitFor(3000)`;
  there is residual reliance on timing for the PC swap.
- **Dev-machine only**; first import needs network + a warm cache; not CI-gated.

## 7. The coupling tradeoff and why we stay single-repo

The harness is **tightly coupled to Metals internals by design**: `TestingServer`
instantiates the real `MetalsLanguageServer`, and the code references internal
types (`UserConfiguration`, `Configs`, `MbtBuildServer`, `HoverExtParams`,
`indexingPromise`, `MetalsEnrichments`) and test scaffolding (`BaseLspSuite`,
`TestingServer`, the initializers in `tests/unit`). This is the price of "real
server, no mocks."

We considered splitting into two repos — a stable test runner + the code under
test — so that comparing versions is just a checkout in the code repo:

- **Approach A — out-of-process LSP client (true decoupling).** The runner becomes
  a standalone LSP/BSP client that launches a Metals **binary** built from the code
  repo; it references zero Metals classes and is version-independent. MBT-vs-BSP
  selection is already protocol-level (`workspace/didChangeConfiguration`).
  *Cost:* a new driver + readiness handling without `indexingPromise` (wait on
  work-done-progress/status notifications or poll-until-stable). Probe/report logic
  is reusable.
- **Approach B — publish a `metals-testkit`, keep in-process.** Metals publishes
  `TestingServer`/`BaseLspSuite`/initializers; the runner depends on `metals` +
  `metals-testkit @ version`. *Cost:* a Metals build change; the runner still
  compiles against internal APIs, so it only stays valid while those APIs are
  stable between the compared versions. (Note: today `tests/unit`, `tests/mtest`,
  `tests/slow` are all `publish / skip := true`, so nothing testkit-like is
  published — `publishLocal` alone is insufficient.)
- **Approach C — single repo + overlay (CHOSEN).** Keep the runner in-repo; the
  **mbt-diff-analyzer holds the test code constant by overlaying** the snapshot
  test files onto each checkout, so only the MBT implementation varies.

**Decision: Approach C.** Rationale: zero build changes, no new driver, and it is
sufficient for the primary use case — the **before-fix vs after-fix** feedback
loop, where the two versions are close and the internal APIs the harness uses are
stable. Its limitation: the overlay can fail to compile if `TestingServer` /
internal APIs drift between *distant* versions; for that scenario, revisit
Approach A (the only option that fully decouples and is version-independent).
