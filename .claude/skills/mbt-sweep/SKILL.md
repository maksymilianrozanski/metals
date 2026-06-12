---
name: mbt-sweep
description: >-
  Sweep-based BSP-vs-MBT validation for Metals against a real Bazel repository
  — no hand-authored test cases. Collects everything observable in configured
  files (diagnostics, semanticTokens, documentSymbol, and definition+hover at
  EVERY identifier) under one import mode into a snapshot file, then diffs two
  snapshots into a report. Use when validating the impact of an MBT importer /
  compiler change ("did my change improve or regress navigation?"), when
  expanding coverage to new files without writing probes, or when asked to
  run/interpret an mbt-sweep. The BSP snapshot is a cached oracle keyed by repo
  SHA, so the expensive bazelbsp build phase runs at most once per repo state.
---

# MBT sweep: collect / diff / interpret

Three components, all in this repo:

- **Collector** — `tests.bazel.RepoSweepCollectorSuite`
  (`tests/slow/src/test/scala/tests/bazel/`): imports the target repo through
  the real Metals server under ONE mode (`bsp` = bazelbsp oracle, `mbt` =
  under test) and sweeps the configured files. Per file: one `diagnostics`,
  one `semanticTokens`, one `documentSymbol` record, plus `definition` and
  `hover` at every identifier token (scalameta-tokenized; literals, comments
  and keywords are never probed; capped per file, evenly spread). Output: a
  JSON-lines snapshot — first line metadata (mode, repoHead, metalsHead),
  then one record per observation keyed by `(file, kind, line, col, ident)`.
- **Differ** — `tests.bazel.SweepDiff` (same file as `MbtSweep`): pairs two
  snapshots by key, classifies each pair, writes `sweep-diff.md` (triage
  view: summary, per-file/kind counts, full non-MATCH detail capped at 300)
  and `sweep-diff.json` (all non-MATCH entries), prints a one-line summary.
- **Scripts** — `tools/mbt-sweep/`:
  - `collect.sh <bsp|mbt> [out.jsonl]` — one snapshot.
  - `diff.sh <baseline.jsonl> <candidate.jsonl> [outdir]`.
  - `run.sh [--fresh-baseline]` — the feedback loop: reuse-or-collect the BSP
    baseline for the repo's current SHA, collect a fresh MBT snapshot named
    with the Metals SHA, diff, print summary.

## Configuration (environment variables, read by the scripts)

| Variable | Meaning | Default |
|---|---|---|
| `MBT_SWEEP_REPO` | target repo checkout | `/Volumes/colimavol/rules_scala` |
| `MBT_SWEEP_TARGETS` | project view targets (space-sep) | dependency_analyzer + reporter packages |
| `MBT_SWEEP_FILES` | files to sweep (space-sep, repo-relative) | the 3 dependency_analyzer files |
| `MBT_SWEEP_DIR` | artifact directory | `tests/slow/target/mbt-sweep` |
| `MBT_SWEEP_MAX_IDENTS` | per-file identifier cap | 500 |

CRITICAL plumbing detail: environment variables do NOT reach the sbt
server's forked test JVM. The scripts bake them into
`tests/slow/target/mbt-sweep/sweep-config.json`, which the suite reads from
that fixed location. Never try to configure the suite with env vars directly.

## Artifact layout (under `MBT_SWEEP_DIR`)

- `bsp-<repoSha>.jsonl` — the oracle, implementation-independent; reuse
  across Metals changes, refresh only when the repo SHA changes or scope/files
  change (`--fresh-baseline`).
- `mbt-<repoSha>-<metalsSha>[-dirty].jsonl` — one candidate per code state;
  keep them, they are the before/after material.
- `diff-<repoSha>-<metalsSha>/sweep-diff.{md,json}` — reviewable artifacts.

## Classification semantics & triage judgment

`MATCH`, `MISMATCH`, `<MODE>_EMPTY` (one side empty/error), `BOTH_EMPTY`,
`ONLY_<MODE>` (key present in one snapshot only — file-set or tokenizer
drift). Judgment rules learned from differential testing here:

- **BSP is the oracle, not gospel.** `MISMATCH` where MBT returns a real
  source position and BSP returns degenerate `jar:/….class:0:0` is MBT being
  BETTER (e.g. definitions into `-sources.jar`). Same for hover with scaladoc
  vs bare signature.
- **`BSP_EMPTY` on inactive `select()` branch files is expected** — those
  files belong to no configured target, BSP has no oracle there; judge the
  MBT answer on its own merits.
- **Definition at a definition site returns its own position in both modes**
  → MATCH, harmless noise.
- **`diagnostics` records are the cheapest PC-health signal**: a wave of
  parse/type errors in the MBT snapshot only = wrong-version or
  broken-classpath presentation compiler for that file. NOTE the polarity
  inversion: for diagnostics, `<empty>` means NO errors, so
  `MBT_EMPTY` = MBT clean while BSP reports diagnostics (often fine) and
  `BSP_EMPTY` = MBT-only diagnostics (usually an MBT bug).
- **`semanticTokens` empty on one side** = that side's PC is effectively dead
  for the file, whatever individual probes say.
- To compare two MBT candidates (before vs after a change), diff each against
  the same BSP baseline and compare the two `sweep-diff.json` files (e.g. with
  `jq`) — do not SweepDiff two `mbt` snapshots directly: both sides would be
  labelled `MBT_*` in the statuses.

## Operational gotchas (hard-won)

- Runtimes (warm): BSP collect 2–6 min (bazelbsp aspect build dominates; COLD
  aspect configs can take 10–25 min — the suite timeout is 40 min), MBT
  collect 1.5–3 min, diff seconds. Probe count is nearly free; new FILES cost
  ~10–20 s each; new PACKAGES in `MBT_SWEEP_TARGETS` make the BSP phase
  rebuild — that's the real cost knob.
- NEVER compile while a collect is running — the forked test JVM lazy-loads
  classes and a mid-run recompile contaminates the measurement.
- `sbt --client` can detach mid-run while the server finishes the task; the
  scripts fail on a missing snapshot — before retrying, check whether a test
  JVM is still alive (`ps -eo pid,etime,cmd | grep "java @/tmp/sbt-args"`)
  and whether the snapshot landed late.
- Zombie bazel servers are the main flakiness source in this sandbox; see the
  cleanup procedure in the mbt-nav-test-gen skill / memory before chasing
  import failures.
- The collector never deletes the target checkout — only generated state
  (`.metals`/`.bsp`/`.bazelbsp`/`.bazelproject`).

## Relation to the probe-based differential harness

`*MbtDifferentialSuite` (see the mbt-nav-test-gen skill) keeps a small set of
hand-authored, named probes as regression anchors for previously-fixed bugs;
the sweep is for DISCOVERY and for broad before/after validation of a change.
Prefer the sweep when asked "did my change break/fix anything in this repo";
add a named probe when a sweep finding becomes a tracked bug.
