# Fast iteration loop: debug variant (no R8, no release lint), no `mach build` configure step,
# then install + launch on the adb-connected phone if there is one.
# Usage: run-mach.cmd /f/kiwifox/dev.sh   (after editing kiwifox-src and syncing with sync-src.sh)
set -x
./mach gradle fenix:assembleDebug
echo "DEV_BUILD_EXIT=$?"
APK=$(find objdir-kiwifox/gradle/build/mobile/android/fenix/app/outputs/apk/debug -name 'fenix-arm64-v8a-debug.apk' | head -1)
ls -la "$APK"
cp "$APK" /f/kiwifox/kiwifox-dev-debug.apk
if adb devices | grep -qw device; then
  adb install -r "$APK" && adb shell am start -n org.mozilla.fenix.debug/org.mozilla.fenix.App
  echo "DEV_INSTALL_EXIT=$?"
else
  echo "no adb device: copy /f/kiwifox/kiwifox-dev-debug.apk to the phone (or pair wireless debugging)"
fi
