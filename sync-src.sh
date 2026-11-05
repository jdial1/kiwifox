# Copy kiwifox-src/ (and theme JSON assets) over the already-applied Firefox tree, without re-running
# apply_kiwifox.py (which needs a pristine tree). Use after editing files under kiwifox-src/.
cd "$(dirname "$0")"
cp -r kiwifox-src/. firefox-FIREFOX_159_0_1_RELEASE/
cp theme/*.json firefox-FIREFOX_159_0_1_RELEASE/mobile/android/fenix/app/src/main/assets/kiwifox/themes/
echo synced
