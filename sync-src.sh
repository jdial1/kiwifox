# Copy kiwifox-src/ (and theme JSON assets with their header image folders) over the already-applied
# Firefox tree, without re-running apply_kiwifox.py (which needs a pristine tree). Use after editing
# files under kiwifox-src/ or theme/.
cd "$(dirname "$0")"
cp -r kiwifox-src/. firefox-FIREFOX_159_0_1_RELEASE/
A=firefox-FIREFOX_159_0_1_RELEASE/mobile/android/fenix/app/src/main/assets/kiwifox/themes
cp theme/*.json "$A"/
for d in theme/*/; do [ -d "$d" ] && cp -r "$d" "$A"/; done
echo synced
