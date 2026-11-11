# Render the image / gradient / animated AMO themes only (iteration loop for header work).
# Options go in F:/kiwifox-dl/shots.properties (the Gradle daemon keeps its first environment).
set -x
cat > /f/kiwifox-dl/shots.properties <<'PROPS'
extra_amo=nova-ash,nicothin-space,spirited-away-animated,blue-nebula-space-theme,praisethesun,nyan-cat-animated
only=amo-nova-ash,amo-nicothin,amo-spirited,amo-blue-nebula,amo-praise,amo-nyan
PROPS
./mach gradle fenix:testDebugUnitTest --tests "org.mozilla.fenix.kiwifox.KiwiThemeScreenshotTest" -i > /f/kiwifox/shots-full.log 2>&1
STATUS=$?
rm -f /f/kiwifox-dl/shots.properties
echo "SHOT_EXIT=$STATUS"
