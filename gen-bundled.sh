# Regenerate the desktop-derived bundled themes (theme/alpenglow, cascade, materialfox) with the app's
# converter. Rasterize Alpenglow's SVGs first: python tools/rasterize_svg.py F:/kiwifox-dl/raster <svgs>
set -x
export KIWIFOX_BUNDLE_DIR=F:/kiwifox/theme
export KIWIFOX_FIREFOX_SRC=F:/kiwifox/firefox-FIREFOX_159_0_1_RELEASE
export KIWIFOX_DESKTOP_ZIPS=F:/kiwifox/themes-desktop
export KIWIFOX_RASTER_DIR=F:/kiwifox-dl/raster
./mach gradle fenix:testDebugUnitTest --tests "org.mozilla.fenix.kiwifox.BundledThemesGenerator" -i 2>&1 | grep -E "BUNDLE |FAILED|BUILD|^e: |Exception"
echo "GEN_EXIT=${PIPESTATUS[0]}"
