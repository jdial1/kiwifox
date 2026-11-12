#!/usr/bin/env bash
# Dry-runs apply_kiwifox.py against the upstream files it patches at another Firefox release tag (downloaded
# from github.com/mozilla-firefox/firefox), so anchor breakage on a new Firefox shows up before a build.
# Usage: tools/check_apply_upstream.sh FIREFOX_160_0_RELEASE
set -euo pipefail
cd "$(dirname "$0")/.."
TAG=${1:?Firefox release tag}
FENIX=mobile/android/fenix/app/src/main
AC=mobile/android/android-components/components/compose
# The patched-file list is kept in one place: check_apply_pristine.sh.
FILES=$(sed -n '/^FILES="$/,/^"$/p' tools/check_apply_pristine.sh | sed '1d;$d' | sed "s#\$FENIX#$FENIX#; s#\$AC#$AC#")
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
for f in $FILES; do
  mkdir -p "$TMP/$(dirname "$f")"
  curl -fsSL "https://raw.githubusercontent.com/mozilla-firefox/firefox/$TAG/$f" -o "$TMP/$f" \
    || { echo "MISSING upstream at $TAG: $f"; exit 1; }
done
mkdir -p "$TMP/$FENIX/res/values-night"
python apply_kiwifox.py "$TMP" app.json > "$TMP/apply.log" 2>&1 || { tail -5 "$TMP/apply.log"; echo "UPSTREAM APPLY FAILED at $TAG"; exit 1; }
echo "UPSTREAM APPLY OK: every KiwiFox patch applies to $TAG"
