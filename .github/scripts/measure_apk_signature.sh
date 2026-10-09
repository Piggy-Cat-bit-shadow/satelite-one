#!/usr/bin/env bash
# Measure the signature state of every release APK, and refuse to guess.
#
# This exists as a script rather than an inline `run:` block for one reason: a
# release gate that has never been shown to fail is not evidence. Both
# release-apk.yml jobs used to carry their own copy of this logic, and the publish
# job's copy looked for the APKs under `app/build/outputs/apk/release/` while
# `download-artifact` had put them in the working directory - with `nullglob` the
# glob expanded to nothing, the `for` loop body never ran, and the step printed
# "all 0 APKs verify as signed" and let an *unverified* artifact through to a
# public release. Keeping the measurement here means
# test_release_publish_gate.py can read the decision table below, and point the
# script at a fake apksigner through $APKSIGNER, instead of trusting a comment.
#
# Usage:
#   measure_apk_signature.sh <expectation> <apk> [<apk> ...]
#     expectation = unsigned | signed
#
#   <apk> may be a file or a directory; directories are expanded to the *.apk
#   files directly inside them, sorted.
#
# Writes the measured state (unsigned | debug-signed | signed-by-user) to stdout as
# the last line, and also to $GITHUB_OUTPUT as `state=<value>` when that is set.
# Exits non-zero — producing NO state — when the expectation is not met, when an
# APK fails to verify, or when there is nothing to measure.
set -euo pipefail

expectation="${1:-}"
if [ "$expectation" != "unsigned" ] && [ "$expectation" != "signed" ]; then
  echo "::error::usage: measure_apk_signature.sh <unsigned|signed> <apk|dir>..." >&2
  exit 2
fi
shift || true

if [ "$#" -eq 0 ]; then
  echo "::error::no APK path was given to measure; refusing to report a signature state" >&2
  exit 1
fi

# Resolve apksigner explicitly. A missing tool is a hard failure: silently skipping
# the measurement is exactly how an unsigned APK reaches a release.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
apksigner="${APKSIGNER:-}"
if [ -z "$apksigner" ]; then
  apksigner="$(find "$sdk/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
fi
if [ -z "$apksigner" ] || [ ! -x "$apksigner" ]; then
  echo "::error::apksigner not found under ${sdk}/build-tools; refusing to report a signature state" >&2
  exit 1
fi
echo "using ${apksigner}"

# Collect the APKs. `nullglob` ON purpose so a directory with no APKs yields an
# empty list that the count check below rejects, rather than a literal glob string.
shopt -s nullglob
apks=()
for target in "$@"; do
  if [ -d "$target" ]; then
    for f in "$target"/*.apk; do
      apks+=("$f")
    done
  elif [ -f "$target" ]; then
    apks+=("$target")
  else
    echo "::error::${target} does not exist" >&2
    exit 1
  fi
done
shopt -u nullglob

if [ "${#apks[@]}" -eq 0 ]; then
  echo "::error::no APKs found in: $*" >&2
  echo "::error::refusing to publish or measure an artifact whose contents were not found" >&2
  exit 1
fi

report="$(mktemp)"
trap 'rm -f "$report"' EXIT

signed=0
unsigned=0
for f in "${apks[@]}"; do
  {
    echo "================================================================"
    echo "### ${f}"
  } >>"$report"
  if "$apksigner" verify --verbose --print-certs "$f" >>"$report" 2>&1; then
    echo "${f}: SIGNED" | tee -a "$report"
    signed=$((signed + 1))
  else
    echo "${f}: UNSIGNED (apksigner verify exited non-zero)" | tee -a "$report"
    unsigned=$((unsigned + 1))
  fi
done
cat "$report"

if [ "$signed" -gt 0 ] && [ "$unsigned" -gt 0 ]; then
  echo "::error::mixed signature state (${signed} signed, ${unsigned} unsigned) — refusing to guess" >&2
  exit 1
fi

if [ "$unsigned" -gt 0 ]; then
  state=unsigned
elif grep -q 'CN=Android Debug' "$report"; then
  state=debug-signed
else
  state=signed-by-user
fi

# The measured state is the only thing that may name the signature state - never the
# step that installed a keystore. `debug-signed` is therefore NOT "signed" for a
# release: the Android debug keystore is a public, well-known key and proves nothing
# about the publisher.
#
# ORDER MATTERS, and it is load-bearing rather than cosmetic: in a shell `case`, `*`
# matches any string, so `unsigned:*)` also matches `unsigned:unsigned`. An arm whose
# body does nothing and simply falls through MUST come before the wildcard arm that
# would otherwise capture it - with the arms the other way round, a correctly unsigned
# dry run is rejected as "expected an unsigned artifact but apksigner measured
# 'unsigned'". test_release_publish_gate.py reads this block and would catch the swap,
# but it is cheaper to not make the mistake.
case "$expectation:$state" in
  unsigned:unsigned)
    ;;
  signed:signed-by-user)
    ;;
  signed:unsigned)
    echo "::error::expected a signed artifact but apksigner reports the APKs as unsigned" >&2
    exit 1
    ;;
  signed:debug-signed)
    echo "::error::the APKs are signed with the Android debug keystore — refusing to continue" >&2
    exit 1
    ;;
  unsigned:*)
    echo "::error::expected an unsigned artifact but apksigner measured '${state}'" >&2
    echo "::error::signing material reached a path that must not have it — check the signing gate" >&2
    exit 1
    ;;
esac

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "state=${state}" >>"$GITHUB_OUTPUT"
fi
echo "measured signature state: ${state} (expected: ${expectation}, ${#apks[@]} APK(s))"
echo "${state}"
