# Render every theme (light + dark) with the real toolbar/theme code via Robolectric native graphics.
# PNGs go to $KIWIFOX_SHOT_DIR; tools/contact_sheets.py then builds docs/screenshots/.
set -x
export KIWIFOX_CATALOG_DIR=F:/kiwifox-dl/catalog
export KIWIFOX_AMO_DIR=F:/kiwifox-dl/amo/xpi
export KIWIFOX_SHOT_DIR=F:/kiwifox-dl/shots
./mach gradle fenix:testDebugUnitTest --tests "org.mozilla.fenix.kiwifox.KiwiThemeScreenshotTest" -i 2>&1 | grep -E "SHOT |FAILED|BUILD|^e: |Exception|Error"
echo "SHOT_EXIT=${PIPESTATUS[0]}"
