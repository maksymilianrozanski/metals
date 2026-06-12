#!/usr/bin/env bash
# Collect a sweep snapshot of the configured files under ONE import mode.
#
# usage: tools/mbt-sweep/collect.sh <bsp|mbt> [out.jsonl]
#
# Configuration via environment (read by THIS script and baked into a config
# file, because env vars do not reach the sbt server's forked test JVM):
#   MBT_SWEEP_REPO        target repo checkout
#                         (default: /Volumes/colimavol/rules_scala)
#   MBT_SWEEP_TARGETS     project view targets, space-separated
#   MBT_SWEEP_FILES       workspace-relative files to sweep, space-separated
#   MBT_SWEEP_DIR         artifact directory
#                         (default: <metals>/tests/slow/target/mbt-sweep)
#   MBT_SWEEP_MAX_IDENTS  per-file identifier cap (default: 500)
#
# Default out: $MBT_SWEEP_DIR/<mode>-<repo-sha>[-<metals-sha>].jsonl
# (the metals sha is included for mbt snapshots so candidates from different
# code states coexist; bsp snapshots are implementation-independent oracles
# keyed by repo sha only).
set -euo pipefail

mode="${1:?usage: collect.sh <bsp|mbt> [out.jsonl]}"
[[ "$mode" == "bsp" || "$mode" == "mbt" ]] || {
  echo "mode must be bsp or mbt, got: $mode" >&2
  exit 2
}

metals_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
repo="${MBT_SWEEP_REPO:-/Volumes/colimavol/rules_scala}"
sweep_dir="${MBT_SWEEP_DIR:-$metals_root/tests/slow/target/mbt-sweep}"
max_idents="${MBT_SWEEP_MAX_IDENTS:-500}"
targets="${MBT_SWEEP_TARGETS:-//third_party/dependency_analyzer/... //src/java/io/bazel/rulesscala/scalac/reporter/...}"
files="${MBT_SWEEP_FILES:-third_party/dependency_analyzer/src/main/io/bazel/rulesscala/dependencyanalyzer/DependencyAnalyzer.scala third_party/dependency_analyzer/src/main/io/bazel/rulesscala/dependencyanalyzer3/DependencyAnalyzer.scala third_party/dependency_analyzer/src/main/io/bazel/rulesscala/dependencyanalyzer/DependencyAnalyzerSettings.scala third_party/dependency_analyzer/src/test/io/bazel/rulesscala/dependencyanalyzer/ScalaVersionTest.scala}"

repo_sha="$(git -C "$repo" rev-parse --short HEAD)"
metals_sha="$(git -C "$metals_root" rev-parse --short HEAD)"
if [[ -n "$(git -C "$metals_root" status --porcelain)" ]]; then
  metals_sha="$metals_sha-dirty"
fi

if [[ "$mode" == "mbt" ]]; then
  default_out="$sweep_dir/$mode-$repo_sha-$metals_sha.jsonl"
else
  default_out="$sweep_dir/$mode-$repo_sha.jsonl"
fi
out="${2:-$default_out}"

mkdir -p "$sweep_dir" "$metals_root/tests/slow/target/mbt-sweep"

# The suite reads this fixed location (see RepoSweepCollectorSuite).
jq -n \
  --arg repo "$repo" \
  --arg mode "$mode" \
  --arg out "$out" \
  --argjson maxIdents "$max_idents" \
  --arg targets "$targets" \
  --arg files "$files" \
  '{
    repo: $repo,
    mode: $mode,
    out: $out,
    maxIdentsPerFile: $maxIdents,
    targets: ($targets | split(" ") | map(select(. != ""))),
    files: ($files | split(" ") | map(select(. != "")))
  }' >"$metals_root/tests/slow/target/mbt-sweep/sweep-config.json"

echo ">> collecting mode=$mode repo=$repo@$repo_sha metals=$metals_sha"
echo ">> out=$out"
(cd "$metals_root" && sbt --client "slow/testOnly tests.bazel.RepoSweepCollectorSuite")

if [[ ! -s "$out" ]]; then
  echo "!! snapshot was not written: $out" >&2
  exit 1
fi
echo ">> snapshot: $out ($(wc -l <"$out") records)"
