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
import re
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


OVERLAY_ROLES = {
    # Material and Fenix XML attributes -> theme role; values are written as #AARRGGBB literals.
    "colorSurface": "surface", "colorSurfaceBright": "surface", "colorSurfaceContainer": "surface",
    "colorSurfaceContainerLow": "surface", "colorSurfaceContainerLowest": "surface", "colorSurfaceDim": "surface",
    "colorSurfaceContainerHigh": "surfaceContainerHighest", "colorSurfaceContainerHighest": "surfaceContainerHighest",
    "colorSurfaceVariant": "surfaceContainerHighest", "colorOutlineVariant": "surfaceContainerHighest",
    "colorSurfaceContainerSelected": "surfaceContainerHighest", "layer2": "surfaceContainerHighest",
    "colorOnSurface": "onSurface", "colorOnBackground": "onSurface", "android:textColorPrimary": "onSurface",
    "colorOnSurfaceVariant": "onSurfaceVariant", "android:textColorSecondary": "onSurfaceVariant",
    "android:colorBackground": "surface", "android:windowBackground": "surface",
    "android:statusBarColor": "surface", "android:navigationBarColor": "surface",
    "colorPrimary": "primary", "colorTertiary": "primary", "android:colorAccent": "primary",
    "android:textColorLink": "primary", "colorOnPrimary": "onPrimary", "colorOnTertiary": "onTertiary",
}


FONT_FAMILIES = [
    # Must match KiwiStyleSpec.FONT_FAMILIES (Android's fonts.xml aliases, present on every device).
    "sans-serif", "sans-serif-light", "sans-serif-thin", "sans-serif-medium", "sans-serif-black",
    "sans-serif-condensed", "sans-serif-condensed-light", "sans-serif-smallcaps",
    "serif", "monospace", "serif-monospace", "casual", "cursive",
]


def luminance(hex_color: str) -> float:
    def channel(c: int) -> float:
        v = c / 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def write_overlays(root: Path) -> None:
    """One <style> per bundled theme and mode, applied at runtime by KiwiThemeManager.applyOverlay.
    Colors are emitted as <color> resources and referenced, because some Fenix attrs only accept references."""
    for mode, folder in (("light", "values"), ("dark", "values-night")):
        colors = []
        styles = []
        for theme_file in sorted((HERE / "theme").glob("*.json")):
            roles = json.loads(read(theme_file)).get(mode) or {}
            slug = "".join(c if c.isalnum() else "_" for c in theme_file.stem).lower()
            styles.append(f'    <style name="KiwiFoxOverlay_{slug}">')
            for role in sorted({r for r in OVERLAY_ROLES.values() if r in roles}):
                colors.append(f'    <color name="kiwifox_{slug}_{role.lower()}">#FF{roles[role][1:].upper()}</color>')
            for attr, role in OVERLAY_ROLES.items():
                if role in roles:
                    styles.append(f'        <item name="{attr}">@color/kiwifox_{slug}_{role.lower()}</item>')
            surface = roles.get("surface")
            if surface:
                light = "true" if luminance(surface) > 0.5 else "false"
                styles.append(f'        <item name="android:windowLightStatusBar">{light}</item>')
                styles.append(f'        <item name="android:windowLightNavigationBar">{light}</item>')
            styles.append("    </style>")
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 "<!-- Generated by apply_kiwifox.py from theme/*.json. Do not edit. -->",
                 "<resources>", *colors, *styles, "</resources>"]
        out = root / FENIX / "res" / folder / "kiwifox_overlays.xml"
        write(out, "\n".join(lines) + "\n")
        print(f"wrote   res/{folder}/kiwifox_overlays.xml")
    # One font overlay per Android family (KiwiStyleSpec.FONT_FAMILIES), applied by
    # KiwiThemeManager.applyOverlay so XML screens (Settings) use the style's font too.
    font_styles = []
    for family in FONT_FAMILIES:
        font_styles.append(f'    <style name="KiwiFoxFont_{family.replace("-", "_")}">')
        font_styles.append(f'        <item name="android:fontFamily">{family}</item>')
        font_styles.append("    </style>")
    write(
        root / FENIX / "res/values/kiwifox_fonts.xml",
        "\n".join(['<?xml version="1.0" encoding="utf-8"?>',
                   "<!-- Generated by apply_kiwifox.py. Do not edit. -->",
                   "<resources>", *font_styles, "</resources>"]) + "\n",
    )
    print("wrote   res/values/kiwifox_fonts.xml")
    # The overlays are looked up by name at runtime, so the resource shrinker must not strip them.
    keep = root / FENIX / "res/raw/kiwifox_keep.xml"
    keep.parent.mkdir(parents=True, exist_ok=True)
    write(
        keep,
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources xmlns:tools="http://schemas.android.com/tools"\n'
        '    tools:keep="@style/KiwiFoxOverlay_*,@style/KiwiFoxFont_*,@color/kiwifox_*" />\n',
    )
    print("wrote   res/raw/kiwifox_keep.xml")


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
        # Header images sit in a folder named after the theme file.
        images = theme.with_suffix("")
        if images.is_dir():
            shutil.copytree(images, assets / images.name, dirs_exist_ok=True)
            print(f"asset   kiwifox/themes/{images.name}/ ({len(list(images.iterdir()))} files)")


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
        '    <string name="kiwifox_import_hint">Links: addons.mozilla.org themes, GitHub repos, or direct .xpi/.zip/.css/.json. Files: theme .xpi/.zip, manifest.json, userChrome.css.</string>\n'
        "    <!-- KiwiFox: section headers of the theme list -->\n"
        '    <string name="kiwifox_section_installed">Installed</string>\n'
        '    <string name="kiwifox_section_catalog">r/FirefoxCSS themes</string>\n'
        '    <string name="kiwifox_catalog_summary">The most popular userChrome.css themes, fetched from their GitHub repositories. Only themes whose toolbar colors are literal values convert; others take their colors from an AMO theme or browser state, and Add explains why.</string>\n'
        "    <!-- KiwiFox: catalogue row actions -->\n"
        '    <string name="kiwifox_add">Add</string>\n'
        '    <string name="kiwifox_installed">Installed</string>\n'
        "    <!-- KiwiFox: Customize > Toolbar switch for the Kiwi-style Home button -->\n"
        '    <string name="kiwifox_home_button">Home button</string>\n'
        '    <string name="kiwifox_home_button_summary">Show a Home button at the start of the toolbar, like Kiwi Browser</string>\n'
        "    <!-- KiwiFox: Settings section titles, mirroring Kiwi Browser -->\n"
        '    <string name="kiwifox_category_basics">Basics</string>\n'
        '    <string name="kiwifox_category_autofill">Autofill</string>\n'
        '    <string name="kiwifox_category_appearance">Appearance</string>\n'
        '    <string name="kiwifox_category_more">More</string>\n'
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
        '    <string name="pref_key_kiwifox_themes" translatable="false">pref_key_kiwifox_themes</string>\n'
        '    <string name="pref_key_kiwifox_home_button" translatable="false">pref_key_kiwifox_home_button</string>\n',
        "preference_keys.xml",
    )


def patch_import_flags_nimbus(text: str) -> str:
    """Bookmark import from file is a hidden feature in release Firefox; KiwiFox ships it on."""
    return replace_once(
        text,
        "        description: If true, the import bookmarks feature will be enabled.\n        default: false\n",
        "        description: If true, the import bookmarks feature will be enabled.\n        default: true\n",
        "nimbus.fml.yaml import-bookmarks",
    )


def patch_import_flags_settings(text: str) -> str:
    """Password import from file (CSV) likewise."""
    return replace_once(
        text,
        "            key = appContext.getPreferenceKey(R.string.pref_key_enable_import_passwords),\n            default = false,\n",
        "            key = appContext.getPreferenceKey(R.string.pref_key_enable_import_passwords),\n            default = true,\n",
        "Settings.kt importPasswordsFeatureFlagEnabled",
    )


def patch_home_activity(text: str) -> str:
    """Apply the bundled theme overlay before views inflate; tint the XML app bar and system bars."""
    text = insert_after(
        text,
        "            themeManager.setActivityTheme(this)\n",
        "            KiwiThemeManager.applyOverlay(this)\n",
        "HomeActivity setupTheme",
    )
    text = insert_after(
        text,
        "import org.mozilla.fenix.theme.ThemeManager\n",
        "import org.mozilla.fenix.kiwifox.KiwiThemeManager\n",
        "HomeActivity imports",
    )
    text = insert_after(
        text,
        "            isToolbarInflated = true\n",
        "            KiwiThemeManager.applyToActivity(this)\n",
        "HomeActivity toolbar inflate",
    )
    return insert_after(
        text,
        "    override fun onResume() {\n        super.onResume()\n",
        "        KiwiThemeManager.applyToActivity(this)\n",
        "HomeActivity onResume",
    )


def patch_home_button_settings(text: str) -> str:
    """Kiwi Browser shows a Home button at the start of the toolbar; KiwiFox does too, switchable."""
    return insert_after(
        text,
        "            default = { ShortcutType.NEW_TAB.value },\n            persistDefaultIfNotExists = true,\n        )\n",
        "\n"
        "    /** KiwiFox: show a Home button at the start of the toolbar, as Kiwi Browser does. */\n"
        "    var kiwiHomeButton by\n"
        "        booleanPreference(\n"
        "            key = appContext.getPreferenceKey(R.string.pref_key_kiwifox_home_button),\n"
        "            default = true,\n"
        "        )\n",
        "Settings.kt kiwiHomeButton",
    )


def patch_toolbar_middleware(text: str) -> str:
    return insert_after(
        text,
        "        return listOf(\n                ToolbarActionConfig(ToolbarAction.Back) { isWideScreen },\n",
        "                ToolbarActionConfig(ToolbarAction.Homepage) { settings.kiwiHomeButton },\n",
        "BrowserToolbarMiddleware start actions",
    )


def patch_customization_toolbar_xml(text: str) -> str:
    return insert_after(
        text,
        '            android:key="@string/pref_key_toolbar_bottom"\n            android:title="@string/preference_bottom_toolbar" />\n',
        "        <androidx.preference.SwitchPreferenceCompat\n"
        '            android:defaultValue="true"\n'
        '            android:key="@string/pref_key_kiwifox_home_button"\n'
        '            android:title="@string/kiwifox_home_button"\n'
        '            android:summary="@string/kiwifox_home_button_summary"\n'
        '            app:iconSpaceReserved="false" />\n',
        "customization_preferences.xml toolbar category",
    )


AC_COMPOSE = "mobile/android/android-components/components/compose"
AC_TOOLBAR = f"{AC_COMPOSE}/browser-toolbar/src/main/java/mozilla/components/compose/browser/toolbar"
KIWI_STYLE_IMPORTS = (
    "import mozilla.components.compose.base.theme.KiwiStyles\n"
    "import mozilla.components.compose.base.theme.kiwiFieldBorder\n"
)


def patch_acorn_theme(text: str) -> str:
    """Material shapes and typography, and AcornTheme.typography, follow the KiwiFox style."""
    text = replace_once(
        text,
        "            shapes = AcornShapes,\n",
        "            shapes = KiwiStyles.shapes ?: AcornShapes,\n"
        "            typography = KiwiStyles.materialTypography ?: MaterialTheme.typography,\n",
        "AcornTheme shapes",
    )
    return replace_once(
        text,
        "        get() = defaultTypography\n",
        "        get() = KiwiStyles.typography ?: defaultTypography\n",
        "AcornTheme.typography",
    )


def patch_full_display_toolbar(text: str) -> str:
    """Address field: theme radius, height (inside the fixed 48 dp slot) and border."""
    text = insert_after(text, "import mozilla.components.compose.base.theme.AcornTheme\n", KIWI_STYLE_IMPORTS,
                        "FullDisplayToolbar imports")
    return replace_once(
        text,
        "                            .height(48.dp)\n"
        "                            .background(\n"
        "                                color = MaterialTheme.colorScheme.surfaceContainerHighest,\n"
        "                                shape = CircleShape,\n"
        "                            )\n",
        "                            .height(48.dp)\n"
        "                            .padding(vertical = KiwiStyles.fieldInset)\n"
        "                            .kiwiFieldBorder(focused = false)\n"
        "                            .background(\n"
        "                                color = MaterialTheme.colorScheme.surfaceContainerHighest,\n"
        "                                shape = KiwiStyles.fieldShape,\n"
        "                            )\n",
        "FullDisplayToolbar field",
    )


def patch_edit_toolbar(text: str) -> str:
    text = insert_after(text, "import mozilla.components.compose.base.theme.AcornTheme\n", KIWI_STYLE_IMPORTS,
                        "BrowserEditToolbar imports")
    return replace_once(
        text,
        "                        .height(48.dp)\n"
        "                        .clip(shape = CircleShape)\n",
        "                        .height(48.dp)\n"
        "                        .padding(vertical = KiwiStyles.fieldInset)\n"
        "                        .kiwiFieldBorder(focused = true)\n"
        "                        .clip(shape = KiwiStyles.fieldShape)\n",
        "BrowserEditToolbar field",
    )


def patch_origin(text: str) -> str:
    """The page title line builds its TextStyle inline, so it needs the theme font explicitly."""
    text = insert_after(text, "import mozilla.components.compose.base.theme.AcornTheme\n",
                        "import mozilla.components.compose.base.theme.KiwiStyles\n", "Origin imports")
    return replace_once(
        text,
        "        style =\n"
        "            TextStyle(\n"
        "                fontSize = URL_TEXT_SIZE_ALONE.sp,\n"
        "                color = MaterialTheme.colorScheme.onSurface,\n"
        "            ),\n",
        "        style =\n"
        "            KiwiStyles.font(\n"
        "                TextStyle(\n"
        "                    fontSize = URL_TEXT_SIZE_ALONE.sp,\n"
        "                    color = MaterialTheme.colorScheme.onSurface,\n"
        "                ),\n"
        "            ),\n",
        "Origin title style",
    )


def patch_inline_autocomplete(text: str) -> str:
    text = insert_after(text, "import mozilla.components.compose.base.theme.AcornTheme\n",
                        "import mozilla.components.compose.base.theme.KiwiStyles\n", "InlineAutocompleteTextField imports")
    return replace_once(
        text,
        "                    TextStyle(\n"
        "                        fontSize = TEXT_SIZE.sp,\n"
        "                        color = MaterialTheme.colorScheme.onSurface,\n",
        "                    TextStyle(\n"
        "                        fontFamily = KiwiStyles.current.fontFamily,\n"
        "                        fontSize = TEXT_SIZE.sp,\n"
        "                        color = MaterialTheme.colorScheme.onSurface,\n",
        "InlineAutocompleteTextField text style",
    )


def patch_home_activity_scale(text: str) -> str:
    """Text and interface scale from the theme style apply to the whole activity configuration."""
    return replace_once(
        text,
        "        base.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {\n"
        "            super.attachBaseContext(base)\n",
        "        base.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {\n"
        "            super.attachBaseContext(KiwiThemeManager.wrapBaseContext(base))\n",
        "HomeActivity attachBaseContext",
    )


def patch_style_strings(text: str) -> str:
    return insert_after(
        text,
        '    <string name="kiwifox_import_failed">Import failed: %1$s</string>\n',
        "    <!-- KiwiFox: Style section of the theme screen. %1$s is the theme name -->\n"
        '    <string name="kiwifox_style_title">Style: %1$s</string>\n'
        '    <string name="kiwifox_style_stock">Firefox\\\'s own shapes, font and sizes</string>\n'
        '    <string name="kiwifox_theme_styled">styled</string>\n'
        '    <string name="kiwifox_style_pill">Round address bar</string>\n'
        '    <string name="kiwifox_style_home_wallpaper">Image themes: artwork behind the homepage</string>\n'
        '    <string name="kiwifox_style_field_radius">Address bar corners</string>\n'
        '    <string name="kiwifox_style_field_height">Address bar height</string>\n'
        '    <string name="kiwifox_style_field_border">Address bar border</string>\n'
        '    <string name="kiwifox_style_corners">Corner radius (menus, cards, dialogs)</string>\n'
        '    <string name="kiwifox_style_text_scale">Text size</string>\n'
        '    <string name="kiwifox_style_ui_scale">Interface scale</string>\n'
        '    <string name="kiwifox_style_font">Font</string>\n'
        '    <string name="kiwifox_style_font_system">System font</string>\n'
        '    <string name="kiwifox_style_font_theme">Theme\\\'s font</string>\n'
        '    <string name="kiwifox_style_font_choose">Choose</string>\n'
        '    <string name="kiwifox_style_font_import">Import .ttf</string>\n'
        '    <string name="kiwifox_style_reset">Reset to theme</string>\n'
        '    <string name="kiwifox_style_hint">Imported userChrome.css themes fill these in from the theme\\\'s CSS and user.js. Changes apply to the selected theme; text size, interface scale and font reload the screen.</string>\n',
        "strings.xml style",
    )


def patch_amo_catalog_strings(text: str) -> str:
    return insert_before(
        text,
        '    <string name="kiwifox_catalog_summary">',
        "    <!-- KiwiFox: section of popular addons.mozilla.org themes on the theme screen -->\n"
        '    <string name="kiwifox_section_amo">Popular Firefox themes</string>\n'
        '    <string name="kiwifox_amo_summary">The most-used themes on addons.mozilla.org that convert completely: colors only, no images, readable address bar, and each one looks different.</string>\n',
        "strings.xml amo catalogue",
    )


KIWI_GROUPS = [
    # (category title string, [preference keys in order]) mirroring Kiwi Browser's Settings sections.
    ("kiwifox_category_basics", ["pref_key_search_settings", "pref_key_home", "pref_key_make_default_browser"]),
    ("kiwifox_category_autofill", ["pref_key_credit_cards", "pref_key_passwords", "pref_key_email_masks"]),
    ("kiwifox_category_appearance", ["pref_key_accessibility", "pref_key_tabs", "pref_key_customize", "pref_key_language"]),
]


def patch_settings_groups(text: str) -> str:
    """Split the General category into Kiwi's Basics / Autofill / Appearance; leftovers go to More."""
    start_anchor = '    <androidx.preference.PreferenceCategory\n        android:title="@string/preferences_category_general"'
    end_anchor = "    </androidx.preference.PreferenceCategory>\n"
    if text.count(start_anchor) != 1:
        raise AnchorMissing(f"preferences.xml: expected 1 General category, found {text.count(start_anchor)}")
    start = text.index(start_anchor)
    end = text.index(end_anchor, start) + len(end_anchor)
    block = text[start:end]
    header_end = block.index(">", len(start_anchor)) + 1
    header_attrs = block[len("    <androidx.preference.PreferenceCategory"):header_end - 1]
    body = block[header_end:block.rindex(end_anchor)]
    elements = re.findall(r"\s*<[A-Za-z0-9_.]+(?:\s[^>]*?)?/>", body, flags=re.S)
    if "".join(elements).strip() != body.strip():
        raise AnchorMissing("preferences.xml: General category has nested or unexpected elements")
    by_key = {}
    order = []
    for element in elements:
        m = re.search(r'android:key="@string/([A-Za-z0-9_]+)"', element)
        key = m.group(1) if m else f"_anon{len(order)}"
        by_key[key] = element.strip("\n")
        order.append(key)
    used = set()
    out = []
    attrs_wo_title = re.sub(r'\s*android:title="[^"]*"', "", header_attrs)
    def category(title_key, keys):
        items = [by_key[k] for k in keys if k in by_key]
        if not items:
            return
        used.update(k for k in keys if k in by_key)
        out.append('    <androidx.preference.PreferenceCategory\n        android:title="@string/' + title_key + '"' + attrs_wo_title + ">\n")
        out.extend(item + "\n" for item in items)
        out.append(end_anchor + "\n")
    for title_key, keys in KIWI_GROUPS:
        category(title_key, keys)
    category("kiwifox_category_more", [k for k in order if k not in used])
    return text[:start] + "".join(out).rstrip("\n") + "\n" + text[end:]


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


KIWI_SURFACES = "org.mozilla.fenix.kiwifox.KiwiSurfaces"


def patch_firefox_theme_mode(text: str) -> str:
    """Tell the tree whether a KiwiFox theme applies and in which mode (null in private mode)."""
    text = insert_after(
        text,
        "            theme == Theme.Dark || theme == Theme.Oled -> kiwiTheme.apply(colorScheme, darkMode = true)\n"
        "            else -> colorScheme\n"
        "        }\n",
        "    val kiwiDark: Boolean? =\n"
        "        when {\n"
        "            kiwiTheme == null -> null\n"
        "            theme == Theme.Light -> false\n"
        "            theme == Theme.Dark || theme == Theme.Oled -> true\n"
        "            else -> null\n"
        "        }\n",
        "FirefoxTheme kiwiDark",
    )
    return replace_once(
        text,
        "            gradients = gradients,\n            content = content,\n",
        f"            gradients = gradients,\n            content = {{ {KIWI_SURFACES}.ProvideMode(kiwiDark, content) }},\n",
        "FirefoxTheme content",
    )


def patch_home_fragment_background(text: str) -> str:
    """Desktop `ntp_background` behind the homepage (a user wallpaper still draws on top, as on desktop)."""
    text = insert_before(
        text,
        # Firefox 159 passes WallpaperBackground( its arguments on the next lines, 160+ calls a one-line helper.
        "                        if (!isPrivateMode) {\n                            WallpaperBackground(",
        f"                        if (!isPrivateMode) {KIWI_SURFACES}.HomeBackground()\n",
        "HomeFragment wallpaper",
    )
    # Fenix sets the system bars from its own theme on every homepage show; put KiwiFox's back.
    return insert_after(
        text,
        "            activity.themeManager.applyStatusBarTheme(activity)\n",
        "            org.mozilla.fenix.kiwifox.KiwiThemeManager.reapplySystemBarIcons(activity)\n",
        "HomeFragment system bars",
    )


def patch_homepage_scope(text: str) -> str:
    """The homepage content in the new tab page colors: keep the original under a new name, wrap it."""
    text = replace_once(
        text,
        "internal fun Homepage(\n    state: HomepageState,\n",
        "private fun KiwiUnthemedHomepage(\n    state: HomepageState,\n",
        "Homepage rename",
    )
    return text.rstrip("\n") + (
        "\n\n/** KiwiFox: the homepage in the theme's new tab page colors (desktop `ntp_*`). */\n"
        "@Composable\n"
        "internal fun Homepage(\n"
        "    state: HomepageState,\n"
        "    interactor: HomepageInteractor,\n"
        "    onCollectionsMigrationCardAction: (CollectionsMigrationCardAction) -> Unit,\n"
        "    onTopSitesItemBound: () -> Unit,\n"
        "    modifier: Modifier = Modifier,\n"
        ") {\n"
        f"    {KIWI_SURFACES}.HomeScope {{\n"
        "        KiwiUnthemedHomepage(state, interactor, onCollectionsMigrationCardAction, onTopSitesItemBound, modifier)\n"
        "    }\n"
        "}\n"
    )


def patch_menu_sheet_scope(text: str) -> str:
    """The main menu sheet in the theme's popup colors."""
    text = replace_once(
        text,
        "fun MenuDialogBottomSheet(\n    modifier: Modifier = Modifier,\n",
        "private fun KiwiUnthemedMenuDialogBottomSheet(\n    modifier: Modifier = Modifier,\n",
        "MenuDialogBottomSheet rename",
    )
    return text.rstrip("\n") + (
        "\n\n/** KiwiFox: the menu sheet in the theme's popup colors (desktop `popup*`). */\n"
        "@Composable\n"
        "fun MenuDialogBottomSheet(\n"
        "    modifier: Modifier = Modifier,\n"
        "    onRequestDismiss: () -> Unit,\n"
        "    menuHandleState: MenuHandleState,\n"
        "    snackbarHostState: SnackbarHostState,\n"
        "    cornerShape: Shape =\n"
        "        MaterialTheme.shapes.large.copy(\n"
        "            bottomStart = CornerSize(0.dp),\n"
        "            bottomEnd = CornerSize(0.dp),\n"
        "        ),\n"
        "    content: @Composable () -> Unit,\n"
        ") {\n"
        f"    {KIWI_SURFACES}.PopupScope {{\n"
        "        KiwiUnthemedMenuDialogBottomSheet(modifier, onRequestDismiss, menuHandleState, snackbarHostState, cornerShape, content)\n"
        "    }\n"
        "}\n"
    )


def patch_awesomebar_scope(text: str) -> str:
    """Search suggestions (desktop's address bar dropdown) in the theme's popup colors."""
    text = replace_once(
        text,
        "modifier.background(MaterialTheme.colorScheme.surface).fillMaxSize().pointerInput(\n"
        "                            WindowInsets.isImeVisible\n",
        f"modifier.background({KIWI_SURFACES}.popupBackground(MaterialTheme.colorScheme.surface)).fillMaxSize().pointerInput(\n"
        "                            WindowInsets.isImeVisible\n",
        "AwesomeBar background",
    )
    text = replace_once(
        text,
        "                        AwesomeBar(\n                            text = state.query,\n",
        f"                        {KIWI_SURFACES}.PopupScope {{\n"
        "                        AwesomeBar(\n                            text = state.query,\n",
        "AwesomeBar scope open",
    )
    return replace_once(
        text,
        "                            profiler = components.core.engine.profiler,\n                        )\n",
        "                            profiler = components.core.engine.profiler,\n                        )\n"
        "                        }\n",
        "AwesomeBar scope close",
    )


def patch_browser_toolbar_header(text: str) -> str:
    """Header images and translucent toolbar color behind the toolbar; theme icon color for toolbar buttons."""
    text = insert_after(
        text,
        "import mozilla.components.compose.base.theme.AcornTheme\n",
        "import mozilla.components.compose.base.theme.KiwiHeaderBox\n"
        "import mozilla.components.compose.base.theme.KiwiStyles\n",
        "BrowserToolbar imports",
    )
    text = replace_once(
        text,
        "@Composable\nfun BrowserToolbar(\n    store: BrowserToolbarStore,\n",
        "@Composable\nprivate fun KiwiUnthemedBrowserToolbar(\n    store: BrowserToolbarStore,\n",
        "BrowserToolbar rename",
    )
    text = replace_once(
        text,
        "    val backgroundColor = MaterialTheme.colorScheme.surface\n",
        "    val backgroundColor = KiwiStyles.toolbarColor(MaterialTheme.colorScheme.surface)\n",
        "BrowserToolbar background",
    )
    text = replace_once(
        text,
        "                } else {\n                    MaterialTheme.colorScheme.surface\n                },\n",
        "                } else {\n                    KiwiStyles.toolbarColor(MaterialTheme.colorScheme.surface)\n                },\n",
        "BrowserToolbar edit background",
    )
    return text.rstrip("\n") + (
        "\n\n/**\n"
        " * KiwiFox: [KiwiUnthemedBrowserToolbar] over the theme's header (frame color and images), with the\n"
        " * theme's `icons` color on the browser actions unless the caller sets one.\n"
        " */\n"
        "@Composable\n"
        "fun BrowserToolbar(\n"
        "    store: BrowserToolbarStore,\n"
        "    cfr: BrowserToolbarCFR? = null,\n"
        "    useMinimalBottomToolbarWhenEnteringText: Boolean = false,\n"
        "    browserActionsColor: Color? = null,\n"
        ") {\n"
        "    KiwiHeaderBox {\n"
        "        KiwiUnthemedBrowserToolbar(\n"
        "            store = store,\n"
        "            cfr = cfr,\n"
        "            useMinimalBottomToolbarWhenEnteringText = useMinimalBottomToolbarWhenEnteringText,\n"
        "            browserActionsColor = browserActionsColor ?: KiwiStyles.iconColor(),\n"
        "        )\n"
        "    }\n"
        "}\n"
    )


def patch_browser_actions_attention(text: str) -> str:
    """Desktop `icons_attention` (active toolbar buttons) on the browser actions."""
    return replace_once(
        text,
        "            colorScheme = MaterialTheme.colorScheme.copy(onSurface = color),\n",
        "            colorScheme = MaterialTheme.colorScheme.copy(\n"
        "                onSurface = color,\n"
        "                tertiary = KiwiStyles.chrome()?.iconsAttention?.takeIf { it != Color.Unspecified }\n"
        "                    ?: MaterialTheme.colorScheme.tertiary,\n"
        "            ),\n",
        "FullDisplayToolbar icons_attention",
    )


def patch_edit_toolbar_focus_field(text: str) -> str:
    """Desktop `toolbar_field_focus`: the address field background while editing."""
    return replace_once(
        text,
        "                        .background(color = MaterialTheme.colorScheme.surfaceContainerHighest),\n",
        "                        .background(color = KiwiStyles.fieldFocus(MaterialTheme.colorScheme.surfaceContainerHighest)),\n",
        "BrowserEditToolbar focus field",
    )


def patch_inline_autocomplete_focus_text(text: str) -> str:
    """Desktop `toolbar_field_text_focus`: the address text while editing."""
    return replace_once(
        text,
        "                        fontFamily = KiwiStyles.current.fontFamily,\n"
        "                        fontSize = TEXT_SIZE.sp,\n"
        "                        color = MaterialTheme.colorScheme.onSurface,\n",
        "                        fontFamily = KiwiStyles.current.fontFamily,\n"
        "                        fontSize = TEXT_SIZE.sp,\n"
        "                        color = KiwiStyles.fieldTextFocus(MaterialTheme.colorScheme.onSurface),\n",
        "InlineAutocompleteTextField focus text",
    )


def patch_display_field_over_header(text: str) -> str:
    """Desktop `toolbar_field` keeps its alpha over the header: the artwork shows through the address field."""
    return replace_once(
        text,
        "                                color = MaterialTheme.colorScheme.surfaceContainerHighest,\n"
        "                                shape = KiwiStyles.fieldShape,\n",
        "                                color = KiwiStyles.fieldColor(MaterialTheme.colorScheme.surfaceContainerHighest),\n"
        "                                shape = KiwiStyles.fieldShape,\n",
        "FullDisplayToolbar field over header",
    )


def patch_amo_image_strings(text: str) -> str:
    return insert_before(
        text,
        '    <string name="kiwifox_section_amo">',
        "    <!-- KiwiFox: section of popular image / animated addons.mozilla.org themes on the theme screen -->\n"
        '    <string name="kiwifox_section_amo_images">Popular image themes</string>\n'
        '    <string name="kiwifox_amo_images_summary">The most-used themes on addons.mozilla.org whose look is artwork, including animated ones. The artwork runs across the status bar and toolbar, as it runs across the tab strip and toolbar on desktop.</string>\n',
        "strings.xml amo image catalogue",
    )


def patch_tabs_tray_scope(text: str) -> str:
    """The tab tray in desktop's tab strip colors (tab_selected, tab_text, tab_background_text, tab_line)."""
    return replace_once(
        text,
        "        trackersBlockedCount: Int?,\n    ) {\n        val tabGroupDotColors = tabGroupDotColors()\n        TabsTray(\n",
        f"        trackersBlockedCount: Int?,\n    ) = {KIWI_SURFACES}.TabsScope {{\n"
        "        val tabGroupDotColors = tabGroupDotColors()\n        TabsTray(\n",
        "TabManagementFragment RenderTabsTray",
    )


def apply_parity_patches(root: Path) -> None:
    """Desktop surfaces beyond the toolbar: new tab page, menus and dropdown, header images, icons, focus field.
    Separate so an applied tree can take just these (`apply_kiwifox.py --parity-only <tree>`)."""
    patch(root, f"{FENIX}/java/org/mozilla/fenix/theme/FirefoxTheme.kt", patch_firefox_theme_mode)
    patch(root, f"{FENIX}/java/org/mozilla/fenix/home/HomeFragment.kt", patch_home_fragment_background)
    patch(root, f"{FENIX}/java/org/mozilla/fenix/home/ui/Homepage.kt", patch_homepage_scope)
    patch(root, f"{FENIX}/java/org/mozilla/fenix/components/menu/compose/MenuDialogBottomSheet.kt", patch_menu_sheet_scope)
    patch(root, f"{FENIX}/java/org/mozilla/fenix/search/awesomebar/AwesomeBarComposable.kt", patch_awesomebar_scope)
    patch(root, f"{AC_TOOLBAR}/BrowserToolbar.kt", patch_browser_toolbar_header)
    patch(root, f"{AC_TOOLBAR}/ui/FullDisplayToolbar.kt", patch_browser_actions_attention)
    patch(root, f"{AC_TOOLBAR}/BrowserEditToolbar.kt", patch_edit_toolbar_focus_field)
    patch(root, f"{AC_TOOLBAR}/ui/InlineAutocompleteTextField.kt", patch_inline_autocomplete_focus_text)
    patch(root, f"{AC_TOOLBAR}/ui/FullDisplayToolbar.kt", patch_display_field_over_header)
    patch(root, f"{FENIX}/java/org/mozilla/fenix/tabstray/ui/TabManagementFragment.kt", patch_tabs_tray_scope)
    patch(root, f"{FENIX}/res/values/strings.xml", patch_amo_image_strings)


def apply_style_patches(root: Path) -> None:
    """Theme style (radius, font, sizes, scale). Separate so an already-applied tree can take just these
    (`apply_kiwifox.py --style-only <tree>`) after copy_sources/write_overlays."""
    base_theme = f"{AC_COMPOSE}/base/src/main/java/mozilla/components/compose/base/theme"
    patch(root, f"{base_theme}/AcornTheme.kt", patch_acorn_theme)
    patch(root, f"{AC_TOOLBAR}/ui/FullDisplayToolbar.kt", patch_full_display_toolbar)
    patch(root, f"{AC_TOOLBAR}/BrowserEditToolbar.kt", patch_edit_toolbar)
    patch(root, f"{AC_TOOLBAR}/ui/Origin.kt", patch_origin)
    patch(root, f"{AC_TOOLBAR}/ui/InlineAutocompleteTextField.kt", patch_inline_autocomplete)
    patch(root, f"{FENIX}/java/org/mozilla/fenix/HomeActivity.kt", patch_home_activity_scale)
    patch(root, f"{FENIX}/res/values/strings.xml", patch_style_strings)
    patch(root, f"{FENIX}/res/values/strings.xml", patch_amo_catalog_strings)


def patch_main_menu_update(text: str) -> str:
    """"Update KiwiFox" at the top of the main menu while a newer build is waiting (KiwiUpdateMenuItem)."""
    return insert_before(
        text,
        "        if (accessPoint == MenuAccessPoint.Home && showBanner) {\n",
        "        org.mozilla.fenix.kiwifox.KiwiUpdateMenuItem()\n\n",
        "MainMenu banner",
    )


def patch_settings_update(text: str) -> str:
    """A "KiwiFox update" entry at the top of Settings, filled in and wired up by KiwiUpdateUi.kt."""
    anchor = 'android:key="@string/pref_key_sync_debug"'
    if text.count(anchor) != 1:
        raise AnchorMissing(f"preferences.xml: expected 1 occurrence of {anchor!r}, found {text.count(anchor)}")
    end = text.index("/>\n", text.index(anchor)) + len("/>\n")
    entry = (
        "\n    <!-- KiwiFox: installed build and waiting update (KiwiUpdateUi.bindUpdatePreference) -->\n"
        "    <androidx.preference.Preference\n"
        '        app:iconSpaceReserved="false"\n'
        '        android:key="kiwifox_update"\n'
        '        android:title="KiwiFox updates" />\n'
    )
    return text[:end] + entry + text[end:]


def patch_version_kiwifox(text: str) -> str:
    """Name the KiwiFox build in the app's version (Android's app info, Firefox's About screen):
    versionName "<Firefox version> · KiwiFox <build>", and a versionCode that grows with the build number
    (2,100,000,000 + minutes since 2026-11-01 UTC; above Fennec-style codes), so every newer KiwiFox build,
    local or from GitHub, installs as an update. The build number is assets/kiwifox/build.json (build.sh, CI)."""
    return insert_after(
        text,
        "                output.versionCode.set(versionCodeOverride)\n",
        "                // KiwiFox: the build stamp written by build.sh / the GitHub workflow.\n"
        '                def kiwiStamp = file("src/main/assets/kiwifox/build.json")\n'
        "                if (kiwiStamp.exists()) {\n"
        "                    def kiwiBuild = new groovy.json.JsonSlurper().parse(kiwiStamp).build.toString()\n"
        "                    def kiwiTime = java.time.LocalDateTime.parse(kiwiBuild, java.time.format.DateTimeFormatter.ofPattern(\"yyyyMMddHHmm\"))\n"
        "                    def kiwiMinutes = java.time.Duration.between(java.time.LocalDateTime.of(2026, 11, 1, 0, 0), kiwiTime).toMinutes()\n"
        "                    output.versionCode.set((2100000000L + kiwiMinutes) as int)\n"
        '                    output.versionName.set("${versionName} · KiwiFox ${kiwiBuild}".toString())\n'
        "                }\n",
        "build.gradle versionCode override",
    )


def apply_update_patches(root: Path) -> None:
    """KiwiFox updates surfaced like browser updates. Separate so an applied tree can take just these
    (`apply_kiwifox.py --update-only <tree>`) after copy_sources."""
    patch(root, f"{FENIX}/java/org/mozilla/fenix/components/menu/compose/MainMenu.kt", patch_main_menu_update)
    patch(root, f"{FENIX}/res/xml/preferences.xml", patch_settings_update)
    patch(root, "mobile/android/fenix/app/build.gradle", patch_version_kiwifox)


def patch_settings_history_import(text: str) -> str:
    """An "Import browsing history" entry in Settings > More, wired up by KiwiHistoryImportFragment.kt
    (bindHistoryImportPreference). Sits after Firefox's own hidden "Import bookmarks" entry."""
    anchor = 'android:key="@string/pref_key_import_bookmarks"'
    if text.count(anchor) != 1:
        raise AnchorMissing(f"preferences.xml: expected 1 occurrence of {anchor!r}, found {text.count(anchor)}")
    end = text.index("/>\n", text.index(anchor)) + len("/>\n")
    entry = (
        "        <!-- KiwiFox: Chrome / Chromium history file import (KiwiHistoryImportFragment) -->\n"
        "        <androidx.preference.Preference\n"
        '            android:key="kiwifox_history_import"\n'
        '            app:iconSpaceReserved="false"\n'
        '            android:title="Import browsing history"\n'
        '            android:summary="From Chrome (Google Takeout) or the History file of a computer browser" />\n'
    )
    return text[:end] + entry + text[end:]


def patch_nav_graph_history_import(text: str) -> str:
    return insert_before(
        text,
        '    <fragment\n        android:id="@+id/kiwiThemesFragment"\n',
        "    <fragment\n"
        '        android:id="@+id/kiwiHistoryImportFragment"\n'
        '        android:name="org.mozilla.fenix.kiwifox.KiwiHistoryImportFragment"\n'
        '        android:label="Import browsing history" />\n',
        "nav_graph.xml history import",
    )


def patch_settings_share_import(text: str) -> str:
    """An "Import from Chrome (Wi-Fi)" entry in Settings > More, under the history-import entry; wired up by
    KiwiShareImportFragment.kt (bindShareImportPreference). Pulls the encrypted bundle off the LAN share."""
    anchor = 'android:key="kiwifox_history_import"'
    if text.count(anchor) != 1:
        raise AnchorMissing(f"preferences.xml: expected 1 occurrence of {anchor!r}, found {text.count(anchor)}")
    end = text.index("/>\n", text.index(anchor)) + len("/>\n")
    entry = (
        "        <!-- KiwiFox: pull the encrypted Chrome bundle off the Wi-Fi share (KiwiShareImportFragment) -->\n"
        "        <androidx.preference.Preference\n"
        '            android:key="kiwifox_share_import"\n'
        '            app:iconSpaceReserved="false"\n'
        '            android:title="Import from Chrome (Wi-Fi)"\n'
        '            android:summary="Pull bookmarks, history and passwords from the PC share in one step" />\n'
    )
    return text[:end] + entry + text[end:]


def patch_nav_graph_share_import(text: str) -> str:
    return insert_before(
        text,
        '    <fragment\n        android:id="@+id/kiwiThemesFragment"\n',
        "    <fragment\n"
        '        android:id="@+id/kiwiShareImportFragment"\n'
        '        android:name="org.mozilla.fenix.kiwifox.KiwiShareImportFragment"\n'
        '        android:label="Import from Chrome (Wi-Fi)" />\n',
        "nav_graph.xml share import",
    )


def apply_import_patches(root: Path) -> None:
    """Chrome import: browsing-history-from-file and the Wi-Fi share pull. Separate so an applied tree can take
    just these (`apply_kiwifox.py --import-only <tree>`) after copy_sources. Needs patch_nav_graph and
    patch_settings_groups to have run."""
    patch(root, f"{FENIX}/res/xml/preferences.xml", patch_settings_history_import)
    patch(root, f"{FENIX}/res/navigation/nav_graph.xml", patch_nav_graph_history_import)
    patch(root, f"{FENIX}/res/xml/preferences.xml", patch_settings_share_import)
    patch(root, f"{FENIX}/res/navigation/nav_graph.xml", patch_nav_graph_share_import)


def main() -> None:
    if len(sys.argv) == 3 and sys.argv[1] == "--import-only":
        root = Path(sys.argv[2]).resolve()
        try:
            copy_sources(root)
            apply_import_patches(root)
        except AnchorMissing as e:
            sys.exit(f"APPLY FAILED: {e}")
        return
    if len(sys.argv) == 3 and sys.argv[1] == "--update-only":
        root = Path(sys.argv[2]).resolve()
        try:
            copy_sources(root)
            apply_update_patches(root)
        except AnchorMissing as e:
            sys.exit(f"APPLY FAILED: {e}")
        return
    if len(sys.argv) == 3 and sys.argv[1] == "--parity-only":
        root = Path(sys.argv[2]).resolve()
        try:
            copy_sources(root)
            apply_parity_patches(root)
        except AnchorMissing as e:
            sys.exit(f"APPLY FAILED: {e}")
        return
    if len(sys.argv) == 3 and sys.argv[1] == "--style-only":
        root = Path(sys.argv[2]).resolve()
        try:
            copy_sources(root)
            write_overlays(root)
            apply_style_patches(root)
        except AnchorMissing as e:
            sys.exit(f"APPLY FAILED: {e}")
        return
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    root = Path(sys.argv[1]).resolve()
    app = json.loads(read(Path(sys.argv[2]) if len(sys.argv) == 3 else HERE / "app.json"))
    try:
        copy_sources(root)
        write_overlays(root)
        patch(root, f"{FENIX}/java/org/mozilla/fenix/theme/FirefoxTheme.kt", patch_firefox_theme)
        patch(root, f"{FENIX}/java/org/mozilla/fenix/settings/CustomizationFragment.kt", patch_customization_fragment)
        patch(root, f"{FENIX}/res/xml/customization_preferences.xml", patch_customization_xml)
        patch(root, f"{FENIX}/res/navigation/nav_graph.xml", patch_nav_graph)
        patch(root, f"{FENIX}/res/values/strings.xml", patch_strings)
        patch(root, f"{FENIX}/res/values/preference_keys.xml", patch_preference_keys)
        patch(root, f"{FENIX}/java/org/mozilla/fenix/HomeActivity.kt", patch_home_activity)
        patch(root, f"{FENIX}/java/org/mozilla/fenix/components/toolbar/BrowserToolbarMiddleware.kt", patch_toolbar_middleware)
        patch(root, f"{FENIX}/res/xml/customization_preferences.xml", patch_customization_toolbar_xml)
        patch(root, f"{FENIX}/res/xml/preferences.xml", patch_settings_groups)
        patch(root, "mobile/android/fenix/app/nimbus.fml.yaml", patch_import_flags_nimbus)
        patch(root, f"{FENIX}/java/org/mozilla/fenix/utils/Settings.kt", patch_import_flags_settings)
        patch(root, f"{FENIX}/java/org/mozilla/fenix/utils/Settings.kt", patch_home_button_settings)
        patch(root, "mobile/android/fenix/app/build.gradle", lambda t: patch_app_identity(t, app))
        apply_style_patches(root)
        apply_parity_patches(root)
        apply_update_patches(root)
        apply_import_patches(root)
    except AnchorMissing as e:
        sys.exit(f"APPLY FAILED: {e}")


if __name__ == "__main__":
    main()
