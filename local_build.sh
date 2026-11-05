#!/usr/bin/env bash
# Clone Firefox at a release tag, apply KiwiFox (runtime theme switching + importer), build a debug-signed release APK.
# Usage: ./local_build.sh [FIREFOX_159_0_1_RELEASE]
# Needs: ~15 GB disk, Linux/macOS, python3, git. mach bootstrap installs JDK + Android SDK into ~/.mozbuild.
set -euo pipefail
TAG="${1:-FIREFOX_159_0_1_RELEASE}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/firefox-$TAG"

if [ ! -d "$SRC" ]; then
  git clone --depth 1 -b "$TAG" https://github.com/mozilla-firefox/firefox "$SRC"
fi
cd "$SRC"
git checkout -- . # start from pristine upstream so apply_kiwifox.py anchors always match
git clean -fdq mobile/android/fenix/app/src/main/java/org/mozilla/fenix/kiwifox mobile/android/fenix/app/src/main/assets/kiwifox
python3 "$HERE/apply_kiwifox.py" "$SRC" "$HERE/app.json"
git --no-pager diff --stat

export MOZCONFIG="$HERE/mozconfig"
./mach --no-interactive bootstrap --application-choice "GeckoView/Firefox for Android Artifact Mode"
./mach build
./mach gradle fenix:assembleRelease   # local builds are debug-signed by Fenix's build.gradle

APK=$(find . -path '*fenix/app/outputs/apk/release/*.apk' | head -1)
test -n "$APK" || { echo "release APK not found"; exit 1; }
cp "$APK" "$HERE/kiwifox-$TAG.apk"
echo "built $HERE/kiwifox-$TAG.apk"
echo "install: adb install -r $HERE/kiwifox-$TAG.apk"
