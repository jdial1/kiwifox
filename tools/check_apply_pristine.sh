#!/usr/bin/env bash
# Dry-runs the full apply_kiwifox.py against pristine copies (git HEAD) of every upstream file it
# patches, in a temp dir, so anchor breakage shows up without touching the build tree.
# Usage: tools/check_apply_pristine.sh [firefox-tree]   (default: firefox-FIREFOX_159_0_1_RELEASE)
set -euo pipefail
cd "$(dirname "$0")/.."
TREE=${1:-firefox-FIREFOX_159_0_1_RELEASE}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
FENIX=mobile/android/fenix/app/src/main
AC=mobile/android/android-components/components/compose
FILES="
$FENIX/java/org/mozilla/fenix/theme/FirefoxTheme.kt
$FENIX/java/org/mozilla/fenix/settings/CustomizationFragment.kt
$FENIX/res/xml/customization_preferences.xml
$FENIX/res/navigation/nav_graph.xml
$FENIX/res/values/strings.xml
$FENIX/res/values/preference_keys.xml
$FENIX/java/org/mozilla/fenix/HomeActivity.kt
$FENIX/java/org/mozilla/fenix/components/toolbar/BrowserToolbarMiddleware.kt
$FENIX/res/xml/preferences.xml
$FENIX/java/org/mozilla/fenix/utils/Settings.kt
mobile/android/fenix/app/nimbus.fml.yaml
mobile/android/fenix/app/build.gradle
$AC/base/src/main/java/mozilla/components/compose/base/theme/AcornTheme.kt
$AC/browser-toolbar/src/main/java/mozilla/components/compose/browser/toolbar/ui/FullDisplayToolbar.kt
$AC/browser-toolbar/src/main/java/mozilla/components/compose/browser/toolbar/BrowserEditToolbar.kt
$AC/browser-toolbar/src/main/java/mozilla/components/compose/browser/toolbar/ui/Origin.kt
$AC/browser-toolbar/src/main/java/mozilla/components/compose/browser/toolbar/ui/InlineAutocompleteTextField.kt
$AC/browser-toolbar/src/main/java/mozilla/components/compose/browser/toolbar/BrowserToolbar.kt
$FENIX/java/org/mozilla/fenix/home/HomeFragment.kt
$FENIX/java/org/mozilla/fenix/home/ui/Homepage.kt
$FENIX/java/org/mozilla/fenix/components/menu/compose/MenuDialogBottomSheet.kt
$FENIX/java/org/mozilla/fenix/search/awesomebar/AwesomeBarComposable.kt
"
for f in $FILES; do
  mkdir -p "$TMP/$(dirname "$f")"
  git -C "$TREE" show "HEAD:$f" > "$TMP/$f"
done
mkdir -p "$TMP/$FENIX/res/values-night"
python apply_kiwifox.py "$TMP" app.json | grep -v '^added\|^asset'
# Every patched file must now match the build tree, which was applied the same way.
fail=0
for f in $FILES; do
  if ! cmp -s "$TMP/$f" "$TREE/$f"; then echo "DIFFERS from build tree: $f"; fail=1; fi
done
[ $fail = 0 ] && echo "PRISTINE APPLY OK: output identical to the build tree"
exit $fail
