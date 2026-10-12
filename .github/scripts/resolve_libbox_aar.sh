#!/usr/bin/env bash
# Locate the libbox.aar that a `build_libbox` run actually produced.
#
# Why this exists as a script rather than a `cp` in each workflow:
#
# Both workflows used to assume the AAR lands in the sing-box module root:
#
#   cp libbox.aar "${GITHUB_WORKSPACE}/app/libs/libbox.aar"
#
# That assumption is stale. `cmd/internal/build_libbox` runs gomobile in an isolated
# working directory and names its output by ABSOLUTE path inside it:
#
#   outputPath := filepath.Join(gomobileWorkDir(rootDir), config.OutputName)
#   gomobileWorkDir(root) := filepath.Join(root, "_libbox_build")
#
# so the artifact is `<root>/_libbox_build/libbox.aar`. The builder copies it out to a
# sibling `sing-box-for-android` checkout only when such a directory happens to exist,
# which is never the case in CI. The module root still gets `libbox.provenance`, which
# is exactly what made the old failure so misleading: the step reported
# "cp: cannot stat 'libbox.aar'" after a build that had in fact succeeded.
#
# Both workflows now call this one resolver, so the two of them cannot drift apart again
# - which is how the release path and the dev path ended up disagreeing in the first
# place. The resolution is exercised by test_resolve_libbox_aar.py, including the cases
# that must FAIL.
#
# Usage:
#   resolve_libbox_aar.sh <core-checkout-root>
#
# Writes the resolved path to stdout (and nothing else), diagnostics to stderr.
# Exit status is non-zero when the AAR cannot be determined unambiguously; it never
# falls back to "whatever file happens to be lying around".
set -euo pipefail

core_root="${1:-}"

if [ -z "$core_root" ]; then
  echo "::error::usage: resolve_libbox_aar.sh <core-checkout-root>" >&2
  exit 2
fi

if [ ! -d "$core_root" ]; then
  echo "::error::core checkout root '$core_root' is not a directory" >&2
  exit 2
fi

# Where the current builder writes, and where the older one wrote.
primary="${core_root}/_libbox_build/libbox.aar"
legacy="${core_root}/libbox.aar"

has_primary=false
has_legacy=false
[ -f "$primary" ] && has_primary=true
[ -f "$legacy" ] && has_legacy=true

if [ "$has_primary" = false ] && [ "$has_legacy" = false ]; then
  echo "::error::build_libbox produced no libbox.aar; looked in:" >&2
  echo "::error::  ${primary}   (the isolated gomobile work directory)" >&2
  echo "::error::  ${legacy}   (the legacy module-root location)" >&2
  exit 1
fi

# Two different files is an ambiguity, not a preference: one of them is a leftover from
# another build, and publishing "the one we happened to pick" is how a stale core ships.
# (Identical bytes are not ambiguous - the same artifact written twice is still one.)
if [ "$has_primary" = true ] && [ "$has_legacy" = true ]; then
  # Deciding whether these are one artifact or two requires a byte comparison. If that
  # cannot be run, the safe answer is to refuse rather than to assume they match - and
  # to say so, instead of reporting an ambiguity that was never measured.
  if ! command -v cmp >/dev/null 2>&1; then
    echo "::error::both libbox.aar locations exist but 'cmp' is unavailable, so they cannot be proven identical:" >&2
    echo "::error::  ${primary}" >&2
    echo "::error::  ${legacy}" >&2
    exit 1
  fi
  if ! cmp -s "$primary" "$legacy"; then
    echo "::error::two different libbox.aar candidates exist; refusing to guess which one this build produced:" >&2
    echo "::error::  ${primary}" >&2
    echo "::error::  ${legacy}" >&2
    echo "::error::remove the stale one (a fresh core checkout should hold only ${primary})" >&2
    exit 1
  fi
fi

if [ "$has_primary" = true ]; then
  resolved="$primary"
else
  # Legacy layout: an older build_libbox wrote to the module root. Still accepted, so a
  # pinned older core revision keeps building, but it is no longer the documented path.
  resolved="$legacy"
fi

echo "resolve_libbox_aar: using ${resolved}" >&2
printf '%s\n' "$resolved"
