set -x
# Stamp the build for KiwiUpdater: build number (UTC yyyyMMddHHmm), commit, and whether the KiwiFox repo had
# uncommitted changes (tools/release.sh only publishes clean builds).
KIWI=/f/kiwifox
STAMP_DIR=mobile/android/fenix/app/src/main/assets/kiwifox
mkdir -p "$STAMP_DIR"
DIRTY=false; [ -n "$(git -C $KIWI status --porcelain --untracked-files=no)" ] && DIRTY=true
printf '{"build": %s, "commit": "%s", "dirty": %s}
' "$(date -u +%Y%m%d%H%M)" "$(git -C $KIWI rev-parse HEAD)" "$DIRTY" > "$STAMP_DIR/build.json"
cat "$STAMP_DIR/build.json"
./mach build
./mach gradle fenix:assembleRelease
echo "BUILD_EXIT=$?"
find . -path '*fenix/app/outputs/apk/release/*.apk'
