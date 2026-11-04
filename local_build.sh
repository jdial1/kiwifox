#!/usr/bin/env bash
# Clone Firefox at a release tag, inject a theme, build a debug-signed release APK for local testing.
# Usage: ./local_build.sh [FIREFOX_159_0_1_RELEASE] [theme/kiwi.json]
# Needs: ~15 GB disk, Linux/macOS, python3, git. mach bootstrap installs JDK + Android SDK into ~/.mozbuild.
set -euo pipefail
TAG="${1:-FIREFOX_159_0_1_RELEASE}"
THEME="${2:-theme/kiwi.json}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/firefox-$TAG"

if [ ! -d "$SRC" ]; then
  git clone --depth 1 -b "$TAG" https://github.com/mozilla-firefox/firefox "$SRC"
fi
cd "$SRC"
git checkout -- . # start from pristine upstream so inject_theme.py anchors always match
python3 "$HERE/inject_theme.py" "$SRC" "$HERE/$THEME" "$HERE/app.json"
git --no-pager diff --stat

export MOZCONFIG="$HERE/mozconfig"
./mach --no-interactive bootstrap --application-choice "GeckoView/Firefox for Android Artifact Mode"
./mach build
./mach gradle fenix:assembleRelease   # local builds are debug-signed by Fenix's build.gradle

APK=$(find . -path '*fenix/app/outputs/apk/release/*.apk' | head -1)
test -n "$APK" || { echo "release APK not found"; exit 1; }
cp "$APK" "$HERE/kiwifox-$TAG-$(basename "$THEME" .json).apk"
echo "built $HERE/kiwifox-$TAG-$(basename "$THEME" .json).apk"
echo "install: adb install -r $HERE/kiwifox-$TAG-$(basename "$THEME" .json).apk"
