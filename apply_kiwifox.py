#!/usr/bin/env python3
"""Apply KiwiFox to a Firefox for Android source tree: runtime theme switching with an
in-app importer, bundled themes as assets, and the KiwiFox app identity.

Usage: apply_kiwifox.py <firefox-src-root> [app.json]

Replaces inject_theme.py (which hard-coded one theme per APK). Every edit to an upstream file
is anchored to text that exists in the upstream source; a missing anchor aborts with an error
instead of guessing. New files are copied from kiwifox-src/ and theme/*.json into assets.
Run on a pristine tree (local_build.sh does `git checkout -- .` first).
"""
import json
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
FENIX = "mobile/android/fenix/app/src/main"


class AnchorMissing(Exception):
    pass


def read(path: Path) -> str:
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def write(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def insert_after(text: str, anchor: str, addition: str, label: str) -> str:
    if text.count(anchor) != 1:
        raise AnchorMissing(f"{label}: expected 1 occurrence of {anchor!r}, found {text.count(anchor)}")
    return text.replace(anchor, anchor + addition)


def insert_before(text: str, anchor: str, addition: str, label: str) -> str:
    if text.count(anchor) != 1:
        raise AnchorMissing(f"{label}: expected 1 occurrence of {anchor!r}, found {text.count(anchor)}")
    return text.replace(anchor, addition + anchor)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if text.count(old) != 1:
        raise AnchorMissing(f"{label}: expected 1 occurrence of {old!r}, found {text.count(old)}")
    return text.replace(old, new)


def patch(root: Path, rel: str, transform) -> None:
    path = root / rel
    original = read(path)
    patched = transform(original)
    if patched == original:
        raise AnchorMissing(f"{rel}: no change made (already applied?)")
    write(path, patched)
    print(f"patched {rel}")


def copy_sources(root: Path) -> None:
    src = HERE / "kiwifox-src"
    for file in sorted(src.rglob("*")):
        if file.is_file():
            dest = root / file.relative_to(src)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, dest)
            print(f"added   {file.relative_to(src).as_posix()}")
    assets = root / FENIX / "assets/kiwifox/themes"
    assets.mkdir(parents=True, exist_ok=True)
    for theme in sorted((HERE / "theme").glob("*.json")):
        shutil.copyfile(theme, assets / theme.name)
        print(f"asset   kiwifox/themes/{theme.name}")


def patch_firefox_theme(text: str) -> str:
    text = insert_after(
        text,
        "import androidx.compose.runtime.ReadOnlyComposable\n",
        "import androidx.compose.ui.platform.LocalContext\n",
        "FirefoxTheme imports",
    )
    text = insert_after(
        text,
        "import mozilla.components.compose.base.theme.privateColorPalette\n",
        "import mozilla.components.compose.base.utils.inComposePreview\n"
        "import org.mozilla.fenix.kiwifox.KiwiThemeManager\n",
        "FirefoxTheme imports 2",
    )
    text = insert_after(
        text,
        "            Theme.Private -> acornPrivateColorScheme()\n        }\n",
        "\n"
        "    // KiwiFox: the selected theme overrides the Material roles for light and dark mode.\n"
        "    val kiwiTheme = if (inComposePreview) {\n"
        "        null\n"
        "    } else {\n"
        "        KiwiThemeManager.ensureLoaded(LocalContext.current)\n"
        "        KiwiThemeManager.current\n"
        "    }\n"
        "    val themedColorScheme: ColorScheme =\n"
        "        when {\n"
        "            kiwiTheme == null -> colorScheme\n"
        "            theme == Theme.Light -> kiwiTheme.apply(colorScheme, darkMode = false)\n"
        "            theme == Theme.Dark || theme == Theme.Oled -> kiwiTheme.apply(colorScheme, darkMode = true)\n"
        "            else -> colorScheme\n"
        "        }\n",
        "FirefoxTheme colorScheme",
    )
    return replace_once(
        text,
        "            colorScheme = colorScheme,\n",
        "            colorScheme = themedColorScheme,\n",
        "FirefoxTheme AcornTheme call",
    )


def patch_customization_fragment(text: str) -> str:
    text = insert_after(
        text,
        "import androidx.navigation.fragment.navArgs\n",
        "import androidx.navigation.fragment.findNavController\n",
        "CustomizationFragment imports",
    )
    text = insert_after(
        text,
        "import org.mozilla.fenix.ext.components\n",
        "import org.mozilla.fenix.ext.getPreferenceKey\n",
        "CustomizationFragment imports 2",
    )
    return insert_after(
        text,
        "        setPreferencesFromResource(R.xml.customization_preferences, rootKey)\n",
        "\n"
        "        // KiwiFox theme picker.\n"
        "        findPreference<Preference>(getPreferenceKey(R.string.pref_key_kiwifox_themes))?.setOnPreferenceClickListener {\n"
        "            findNavController().navigate(R.id.kiwiThemesFragment)\n"
        "            true\n"
        "        }\n",
        "CustomizationFragment onCreatePreferences",
    )


def patch_customization_xml(text: str) -> str:
    return insert_after(
        text,
        '        android:title="@string/preferences_theme"\n        app:iconSpaceReserved="false">\n',
        "        <androidx.preference.Preference\n"
        '            android:key="@string/pref_key_kiwifox_themes"\n'
        '            android:title="@string/kiwifox_themes_title"\n'
        '            android:summary="@string/kiwifox_themes_summary"\n'
        '            app:iconSpaceReserved="false" />\n\n',
        "customization_preferences.xml theme category",
    )


def patch_nav_graph(text: str) -> str:
    return insert_before(
        text,
        '    <fragment\n        android:id="@+id/wallpaperSettingsFragment"\n',
        "    <fragment\n"
        '        android:id="@+id/kiwiThemesFragment"\n'
        '        android:name="org.mozilla.fenix.kiwifox.KiwiThemesFragment"\n'
        '        android:label="@string/kiwifox_themes_title" />\n',
        "nav_graph.xml",
    )


def patch_strings(text: str) -> str:
    return insert_after(
        text,
        '    <string name="preferences_customize">Customize</string>\n',
        "\n"
        "    <!-- KiwiFox: title of the theme picker screen and its entry in Customize settings -->\n"
        '    <string name="kiwifox_themes_title">Toolbar themes</string>\n'
        "    <!-- KiwiFox: summary under the Customize entry -->\n"
        '    <string name="kiwifox_themes_summary">Pick a theme or import one from a link or file</string>\n'
        "    <!-- KiwiFox: the row that restores Firefox\\'s own colors -->\n"
        '    <string name="kiwifox_theme_default">Firefox default</string>\n'
        '    <string name="kiwifox_theme_default_summary">No theme applied</string>\n'
        "    <!-- KiwiFox: subtitle of a theme shipped with the app -->\n"
        '    <string name="kiwifox_theme_bundled">Bundled</string>\n'
        "    <!-- KiwiFox: import buttons and dialog -->\n"
        '    <string name="kiwifox_import_link">Import from link</string>\n'
        '    <string name="kiwifox_import_file">Import file</string>\n'
        '    <string name="kiwifox_import_action">Import</string>\n'
        '    <string name="kiwifox_cancel">Cancel</string>\n'
        '    <string name="kiwifox_import_hint">Links: addons.mozilla.org themes, r/FirefoxCSS Reddit posts, GitHub repos, or direct .xpi/.zip/.css/.json. Files: theme .xpi/.zip, manifest.json, userChrome.css. Many userChrome.css themes take their colors from an AMO theme and cannot be imported; the error says why.</string>\n'
        "    <!-- KiwiFox: import status messages. %1$s is the theme name or the error -->\n"
        '    <string name="kiwifox_import_working">Importing…</string>\n'
        '    <string name="kiwifox_import_done">Imported and applied: %1$s</string>\n'
        '    <string name="kiwifox_import_failed">Import failed: %1$s</string>\n',
        "strings.xml",
    )


def patch_preference_keys(text: str) -> str:
    return insert_after(
        text,
        '    <string name="pref_key_light_theme" translatable="false">pref_key_light_theme</string>\n',
        '    <string name="pref_key_kiwifox_themes" translatable="false">pref_key_kiwifox_themes</string>\n',
        "preference_keys.xml",
    )


def patch_app_identity(text: str, app: dict) -> str:
    start_anchor, end_anchor = "release releaseTemplate >> {", "benchmark releaseTemplate >> {"
    for anchor in (start_anchor, end_anchor):
        if text.count(anchor) != 1:
            raise AnchorMissing(f"build.gradle: expected 1 occurrence of {anchor!r}, found {text.count(anchor)}")
    start = text.index(start_anchor)
    end = text.index(end_anchor, start)
    block = text[start:end]
    for old, new in (
        ('applicationIdSuffix ".firefox"', f'applicationIdSuffix "{app["applicationIdSuffix"]}"'),
        ('"sharedUserId": "org.mozilla.firefox.sharedID"', f'"sharedUserId": "{app["sharedUserId"]}"'),
    ):
        if block.count(old) != 1:
            raise AnchorMissing(f"build.gradle release block: expected 1 occurrence of {old!r}, found {block.count(old)}")
        block = block.replace(old, new)
    return text[:start] + block + text[end:]


def main() -> None:
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    root = Path(sys.argv[1]).resolve()
    app = json.loads(read(Path(sys.argv[2]) if len(sys.argv) == 3 else HERE / "app.json"))
    try:
        copy_sources(root)
        patch(root, f"{FENIX}/java/org/mozilla/fenix/theme/FirefoxTheme.kt", patch_firefox_theme)
        patch(root, f"{FENIX}/java/org/mozilla/fenix/settings/CustomizationFragment.kt", patch_customization_fragment)
        patch(root, f"{FENIX}/res/xml/customization_preferences.xml", patch_customization_xml)
        patch(root, f"{FENIX}/res/navigation/nav_graph.xml", patch_nav_graph)
        patch(root, f"{FENIX}/res/values/strings.xml", patch_strings)
        patch(root, f"{FENIX}/res/values/preference_keys.xml", patch_preference_keys)
        patch(root, "mobile/android/fenix/app/build.gradle", lambda t: patch_app_identity(t, app))
    except AnchorMissing as e:
        sys.exit(f"APPLY FAILED: {e}")


if __name__ == "__main__":
    main()
