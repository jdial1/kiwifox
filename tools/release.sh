#!/usr/bin/env bash
# Publish the last build.sh build as a GitHub release that installed KiwiFox apps offer as an update
# (KiwiUpdater checks github.com/jdial1/kiwifox/releases/latest). Run from Git Bash after build.sh:
#   bash tools/release.sh
# Refuses builds made with uncommitted changes or from a commit that isn't pushed. The release is tagged
# build-<number> on the build's commit and carries one APK per ABI plus the commit list since the last one.
set -euo pipefail
cd "$(dirname "$0")/.."
TREE=firefox-FIREFOX_159_0_1_RELEASE
STAMP=$TREE/mobile/android/fenix/app/src/main/assets/kiwifox/build.json
APKS=$TREE/objdir-kiwifox/gradle/build/mobile/android/fenix/app/outputs/apk/release
REPO=jdial1/kiwifox

read -r NUMBER COMMIT DIRTY < <(python -c "import json,sys; j=json.load(open(sys.argv[1])); print(j['build'], j['commit'], str(j['dirty']).lower())" "$STAMP")
[ "$DIRTY" = "false" ] || { echo "build $NUMBER was made with uncommitted changes: commit, rebuild, then release"; exit 1; }
for f in "$APKS"/fenix-*-release.apk; do
  [ "$f" -nt "$STAMP" ] || { echo "$f is older than the build stamp: run build.sh first"; exit 1; }
done
git fetch -q origin --tags
git branch -r --contains "$COMMIT" | grep -q . || { echo "commit $COMMIT is not pushed"; exit 1; }

TAG=build-$NUMBER
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
for f in "$APKS"/fenix-*-release.apk; do
  abi=$(basename "$f" | sed -E 's/^fenix-(.*)-release\.apk$/\1/')
  cp "$f" "$OUT/kiwifox-$NUMBER-$abi.apk"
done

PREV=$(git tag --list 'build-*' --sort=-version:refname | head -1 || true)
{
  echo "KiwiFox build $NUMBER ($(git log -1 --format=%h "$COMMIT"), branch $(git branch -r --contains "$COMMIT" | head -1 | xargs))."
  echo
  if [ -n "$PREV" ]; then
    echo "Changes since $PREV:"
    git log --format='- %s' "$PREV..$COMMIT"
  else
    echo "Changes:"
    git log --format='- %s' -10 "$COMMIT"
  fi
} > "$OUT/notes.md"

gh release create "$TAG" "$OUT"/kiwifox-*.apk -R "$REPO" --target "$COMMIT" \
  --title "KiwiFox build $NUMBER" --notes-file "$OUT/notes.md" --latest
echo "released $TAG: https://github.com/$REPO/releases/tag/$TAG"
