#!/usr/bin/env bash
# One-shot BSP-vs-MBT sweep feedback loop:
#   1. reuse (or collect) the BSP oracle snapshot for the target repo's SHA —
#      BSP results are implementation-independent, so the expensive bazelbsp
#      build phase runs at most once per repo SHA;
#   2. collect a fresh MBT snapshot with the current Metals code;
#   3. diff and print the summary.
#
# usage: tools/mbt-sweep/run.sh [--fresh-baseline]
# Configuration: same environment variables as collect.sh.
set -euo pipefail

fresh_baseline=false
[[ "${1:-}" == "--fresh-baseline" ]] && fresh_baseline=true

metals_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="${MBT_SWEEP_REPO:-/Volumes/colimavol/rules_scala}"
sweep_dir="${MBT_SWEEP_DIR:-$metals_root/tests/slow/target/mbt-sweep}"

repo_sha="$(git -C "$repo" rev-parse --short HEAD)"
metals_sha="$(git -C "$metals_root" rev-parse --short HEAD)"
if [[ -n "$(git -C "$metals_root" status --porcelain)" ]]; then
  metals_sha="$metals_sha-dirty"
fi

baseline="$sweep_dir/bsp-$repo_sha.jsonl"
candidate="$sweep_dir/mbt-$repo_sha-$metals_sha.jsonl"
outdir="$sweep_dir/diff-$repo_sha-$metals_sha"

if [[ -s "$baseline" && "$fresh_baseline" == "false" ]]; then
  echo ">> reusing BSP baseline: $baseline"
else
  "$script_dir/collect.sh" bsp "$baseline"
fi

"$script_dir/collect.sh" mbt "$candidate"
"$script_dir/diff.sh" "$baseline" "$candidate" "$outdir"
