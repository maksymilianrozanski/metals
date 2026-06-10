---
name: mbt-nav-test-gen
description: >-
  Generate and triage MBT-vs-BSP differential navigation tests for Metals
  against real Bazel repositories (e.g. rules_scala). Use this skill whenever
  the user wants to add or expand MBT import navigation tests, find high-risk
  Scala/Java files for MBT testing (custom source roots, srcjars, multi-Scala-
  version dirs, Java/Scala interop, generated sources), write a new
  *MbtDifferentialSuite, choose probe positions/queries for code navigation
  (definition/references/hover/documentSymbol), or triage/judge the discrepancy
  report a differential run produced. Reach for it even when the user just says
  "add MBT test cases for <repo or package>", "find risky files for MBT", or
  "why does MBT navigation fail here" — it knows the BaseRealRepoMbtSuite
  harness conventions and the known MBT failure patterns.
---

# MBT navigation test generator

Metals v2 imports Bazel projects through **MBT** (`.metals/mbt.json`). MBT is
new and buggy, so we test its code-navigation correctness *differentially*: the
same real repo is imported twice through the real Metals server — once via MBT
(under test) and once via **Bazel BSP / bazelbsp** (the source of truth) — and
the same navigation probes run against both. Disagreements point at MBT bugs.

This skill does two jobs:

1. **Generate** — scan a repo's build files + directory structure for files
   where MBT is *likely* to mis-resolve, pick concrete probe positions, and emit
   a new `*MbtDifferentialSuite` (a hardcoded list of `Probe`s).
2. **Triage** — read the JSON report a run produced and judge each discrepancy
   (MBT bug / BSP gap / acceptable difference), because the oracle is not always
   right.

The harness is static; only the repo + the probe list change per test. Read
`references/harness.md` for the full `BaseRealRepoMbtSuite` / `Probe` contract
and run/report mechanics before generating or editing a suite.

## Why find high-risk files from build files, NOT from mbt.json

A file silently **missing** from `mbt.json` (e.g. a srcjar source, a custom
source root MBT didn't pick up, a generated file) is itself one of the bugs we
want to catch. If you derive candidates from `mbt.json` you are blind to exactly
those cases. So always discover candidates from `BUILD`/`BUILD.bazel`,
`MODULE.bazel`, `WORKSPACE`, and the on-disk source layout — never from
`mbt.json`. (You may *read* `mbt.json` afterwards to predict where MBT will
fail, but never to decide what to test.)

## Mode 1 — generate a differential suite

1. **Classify the workspace.** Is the target the **root** Bazel workspace or a
   **standalone nested** one? In rules_scala, `examples/*` each have their own
   `MODULE.bazel`/`WORKSPACE` and are separate workspace roots (excluded from
   the root `//...`). This changes both scoping and the Scala-version behaviour
   (see Gotchas). Point `repoDir` at the actual workspace root you want.

2. **Pick a small scope.** bazelbsp builds every target in the project view, so
   keep `projectViewTargets` to a few small packages. Favour packages that hit
   the high-risk patterns in `references/high-risk-catalog.md`. For a standalone
   workspace, `//...` is usually fine.

3. **Find high-risk files** from build files + structure. Work through
   `references/high-risk-catalog.md` — it lists each pattern, the grep/find that
   surfaces it, and why MBT tends to fail there. Prioritise: srcjar sources,
   custom source roots, multi-Scala-version dirs, Java/Scala interop, generated
   sources, and cross-target/cross-package references.

4. **Choose probe positions.** For each risky file, pick cursor positions where
   mis-resolution is likely: a reference that crosses a file/target/version/
   language boundary, a hover on a symbol defined elsewhere, a definition that
   should land in a srcjar or another target. Express each as a `Probe` with a
   `@@`-marked snippet that is a **unique substring of the real file** (the
   harness finds the *first* occurrence, so make it unique). Read the file and
   confirm the snippet before writing it.

5. **Emit the suite.** Copy `assets/DifferentialSuiteTemplate.scala` into
   `tests/slow/src/test/scala/tests/bazel/`, fill in the class name, the
   `suiteName` (this becomes the report filename), `repoDir`,
   `projectViewTargets`, and `probes`. One `test("differential")` body calling
   `runDifferential()` is all that's needed.

6. **Validate before running.** Re-read each probed file to confirm every
   `@@`-query is present and unique. Then `sbt --client "slow/Test/compile"`. If
   the user wants, run it (see Running) and hand the report to Mode 2.

## Mode 2 — triage a differential report

After a run, read `tests/slow/target/mbt-differential/<suiteName>.json` (and the
`.md` for humans). For every result that is **not** `MATCH`, judge it — do not
assume BSP is correct:

- **MBT bug** — BSP gives a sensible answer, MBT is empty/wrong. The common,
  high-value finding.
- **BSP gap / MBT better** — BSP is empty or degenerate (e.g. a definition into
  a srcjar that resolves to `.class:0:0` with no real position) while MBT points
  at the actual source line. This happens; flag it as MBT being *better*, not a
  bug.
- **Both wrong / no oracle** — `BOTH_EMPTY`, or both point somewhere implausible.
  Note it but don't over-invest.
- **Acceptable difference** — semantically equivalent, differs only in path
  normalization or ordering.

For each, read the relevant source to ground the judgment, and give a one-line
rationale. Output a short triaged markdown (group by verdict). When a judged
MBT answer is correct, you may record it as the probe's `expected` for future
regression (the `Probe.note` field is a fine place to capture intent).

## Critical gotchas (these waste runs if ignored)

- **Scala-version mis-detection in root workspaces.** MBT's
  `BazelMbtImporter.queryScalaVersionFromDeps` takes `headOption` over
  `scala-library-X.Y.Z` dep labels. A root workspace that registers several
  Scala versions (rules_scala registers 2.11/2.12/2.13/3.x) makes MBT grab an
  arbitrary one (often `2.11.12`), so its presentation compiler can't load and
  navigation is empty *everywhere*. Consequence:
  - To demonstrate **MATCH** (MBT working) use a **standalone single-version
    workspace** whose own `MODULE.bazel` pins `scala_version = "X.Y.Z"` (e.g.
    `examples/semanticdb` → 2.13.18).
  - Root-workspace packages are still great for **bug-hunting** — they expose the
    version bug and its downstream navigation failures.

- **BSP is the oracle, not gospel.** See the srcjar `.class:0:0` example above.
  Always keep Mode 2 judgment in the loop.

- **documentSymbol renderer is fragile.** The shared `Semanticdbs.printTextDocument`
  test renderer throws a line-bounds error on tiny / no-trailing-newline files
  (identically for both servers → zero signal). Prefer `Definition`, `References`,
  `Hover`. Use `DocumentSymbol` only on larger, normal files.

- **Probe queries must be unique `@@`-substrings.** The position is found via the
  first `indexOf` of the snippet (minus `@@`). Ambiguous snippets resolve to the
  wrong place.

- **Keep scope small + warm the cache.** Each scoped target is built by bazelbsp.
  For a standalone workspace, run `bazel build //<scope>` once first to warm its
  (separate) Bazel output base, or the first run is slow.

## Running

```bash
sbt --client "slow/Test/compile"
sbt --client "slow/testOnly tests.bazel.<YourSuite>"
```

Set `METALS_MBT_TEST_REPO` to override the checkout path (defaults to
`/Volumes/colimavol/rules_scala`). The report lands in
`tests/slow/target/mbt-differential/<suiteName>.{md,json}`. The suite always
passes (it produces a report; it does not fail on MISMATCH) — the report is the
deliverable. Reports are gitignored build output.

Format any new Scala with `./bin/scalafmt <file>` and `sbt --client scalafixAll`
before the user commits.

## Reference files

- `references/harness.md` — `BaseRealRepoMbtSuite`/`Probe`/`DiffFeature` API,
  the `@@`-query convention, run + report mechanics, existing suites.
- `references/high-risk-catalog.md` — catalog of high-risk patterns with the
  grep/find that surfaces each and why MBT fails there.
- `assets/DifferentialSuiteTemplate.scala` — copy-and-fill suite template.
