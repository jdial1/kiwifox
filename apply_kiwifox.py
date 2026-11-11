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


def main() -> None:
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
    except AnchorMissing as e:
        sys.exit(f"APPLY FAILED: {e}")


if __name__ == "__main__":
    main()
