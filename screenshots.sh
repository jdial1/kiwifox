# Render every theme (light + dark) with the real toolbar/theme code via Robolectric native graphics,
# plus the popular image / animated AMO themes. PNGs go to F:/kiwifox-dl/shots; tools/contact_sheets.py
# then builds docs/screenshots/. Options go in F:/kiwifox-dl/shots.properties (the Gradle daemon keeps
# the environment it was first started with, so new options can't be environment variables).
set -x
export KIWIFOX_CATALOG_DIR=F:/kiwifox-dl/catalog
export KIWIFOX_AMO_DIR=F:/kiwifox-dl/amo/xpi
export KIWIFOX_SHOT_DIR=F:/kiwifox-dl/shots
cat > /f/kiwifox-dl/shots.properties <<'PROPS'
extra_amo=nova-ash,nicothin-space,spirited-away-animated,blue-nebula-space-theme,praisethesun,nyan-cat-animated
PROPS
./mach gradle fenix:testDebugUnitTest --tests "org.mozilla.fenix.kiwifox.KiwiThemeScreenshotTest" -i > /f/kiwifox/shots-full.log 2>&1
STATUS=$?
rm -f /f/kiwifox-dl/shots.properties
echo "SHOT_EXIT=$STATUS"
