#!/usr/bin/env bash
# Diff two sweep snapshots and write a report.
#
# usage: tools/mbt-sweep/diff.sh <baseline.jsonl> <candidate.jsonl> [outdir]
#
# Default outdir: $MBT_SWEEP_DIR/diff-<timestamp> (MBT_SWEEP_DIR defaults to
# <metals>/tests/slow/target/mbt-sweep). Writes sweep-diff.md (triage) and
# sweep-diff.json (full non-MATCH detail); prints the summary line.
set -euo pipefail

baseline="${1:?usage: diff.sh <baseline.jsonl> <candidate.jsonl> [outdir]}"
candidate="${2:?usage: diff.sh <baseline.jsonl> <candidate.jsonl> [outdir]}"

metals_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sweep_dir="${MBT_SWEEP_DIR:-$metals_root/tests/slow/target/mbt-sweep}"
outdir="${3:-$sweep_dir/diff-$(date +%Y%m%d-%H%M%S)}"

baseline="$(cd "$(dirname "$baseline")" && pwd)/$(basename "$baseline")"
candidate="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
mkdir -p "$outdir"

(cd "$metals_root" && sbt --client \
  "slow/Test/runMain tests.bazel.SweepDiff $baseline $candidate $outdir")
