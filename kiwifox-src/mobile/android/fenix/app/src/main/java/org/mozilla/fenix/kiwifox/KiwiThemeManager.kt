/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import androidx.appcompat.widget.Toolbar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import mozilla.components.compose.base.theme.KiwiChromeColors
import mozilla.components.compose.base.theme.KiwiStyle
import mozilla.components.compose.base.theme.KiwiStyles
import mozilla.components.support.ktx.android.content.getColorFromAttr
import org.json.JSONObject
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import java.io.File
import kotlin.math.roundToInt
import com.google.android.material.R as materialR

/**
 * Holds the installed themes and the selected one. [current] is Compose state, so every
 * `FirefoxTheme { }` composition recomposes as soon as the selection changes.
 *
 * Bundled themes live in `assets/kiwifox/themes/` (one `.json` per theme); imported ones in
 * `filesDir/kiwifox/themes/`. The selection is a theme id in the "kiwifox" preferences.
 *
 * Besides colors, a theme has a [KiwiStyleSpec] (corner radius, font, address bar metrics, text
 * and interface scale). The user can override any of it per theme; [style] is the result, and it
 * is pushed to [KiwiStyles], which the patched toolbar and Acorn theme read.
 */
object KiwiThemeManager {
    private const val PREFS = "kiwifox"
    private const val KEY_SELECTED = "selected_theme"
    private const val KEY_STYLE_PREFIX = "style_"
    private const val ASSET_DIR = "kiwifox/themes"

    /** Style-override key used when no theme is selected: Firefox's colors can still get a style. */
    const val DEFAULT_THEME_KEY = "firefox-default"

    /** `fontFile` value in an override that cancels the theme's font file. */
    const val NO_FONT_FILE = "none"

    /** The selected theme, or null for Firefox's own colors. */
    var current: KiwiTheme? by mutableStateOf(null)
        private set

    /** Bundled themes followed by imported ones. */
    var themes: List<KiwiTheme> by mutableStateOf(emptyList())
        private set

    /** The user's style changes for the selected theme (only the keys they changed). */
    var overrides: KiwiStyleSpec by mutableStateOf(KiwiStyleSpec.EMPTY)
        private set

    /** The style in effect: the selected theme's own style with [overrides] on top. */
    var style: KiwiStyleSpec by mutableStateOf(KiwiStyleSpec.EMPTY)
        private set

    @Volatile
    private var loaded = false

    /** Loads themes and the saved selection once per process. Cheap after the first call. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            reload(context.applicationContext)
            loaded = true
        }
    }

    fun reload(context: Context) {
        val app = context.applicationContext
        val all = loadBundled(app) + loadImported(app)
        val selectedId = prefs(app).getString(KEY_SELECTED, null)
        themes = all
        current = all.firstOrNull { it.id == selectedId }
        refreshStyle(app)
    }

    fun select(context: Context, theme: KiwiTheme?) {
        prefs(context).edit().putString(KEY_SELECTED, theme?.id).apply()
        current = theme
        refreshStyle(context.applicationContext)
    }

    /** Replaces the user's style overrides for the selected theme. An empty spec restores the theme's own style. */
    fun setOverrides(context: Context, spec: KiwiStyleSpec) {
        val key = styleKey()
        val clamped = spec.clamped()
        val editor = prefs(context).edit()
        if (clamped.isEmpty) editor.remove(key) else editor.putString(key, clamped.toJson().toString())
        editor.apply()
        refreshStyle(context.applicationContext)
    }

    /**
     * True when going from style [a] to [b] needs the activity recreated: text and interface scale
     * live in the activity configuration, and the XML font overlay is applied when the activity starts.
     */
    fun needsRecreate(a: KiwiStyleSpec, b: KiwiStyleSpec): Boolean =
        a.textScale != b.textScale || a.uiScale != b.uiScale || a.fontFamily != b.fontFamily

    /** Writes an imported theme to disk under a fresh id and returns the stored copy. */
    fun saveImported(context: Context, theme: KiwiTheme): KiwiTheme {
        val dir = importDir(context)
        dir.mkdirs()
        val slug = theme.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "theme" }
        var id = "user-$slug"
        var n = 2
        while (File(dir, "$id.json").exists()) id = "user-$slug-${n++}"
        val stored = theme.copy(id = id, builtIn = false, pendingFiles = emptyMap())
        // Header images live in a folder named after the theme file.
        if (theme.pendingFiles.isNotEmpty()) {
            val imageDir = File(dir, id).apply { mkdirs() }
            for ((name, bytes) in theme.pendingFiles) File(imageDir, name).writeBytes(bytes)
        }
        File(dir, "$id.json").writeText(stored.toJson())
        reload(context)
        return stored
    }

    fun delete(context: Context, theme: KiwiTheme) {
        if (theme.builtIn) return
        File(importDir(context), "${theme.id}.json").delete()
        File(importDir(context), theme.id).deleteRecursively()
        prefs(context).edit().remove(KEY_STYLE_PREFIX + theme.id).apply()
        if (current?.id == theme.id) select(context, null)
        reload(context)
    }

    @Volatile
    private var upgradeStarted = false

    /**
     * Converts imported themes made by an older [ThemeConverter.VERSION] again from their source link, so
     * themes imported before a mapping existed (new tab page, menus, header images...) gain it. Runs once
     * per process on a background thread; themes imported from a file have no link and keep what they have.
     */
    fun upgradeOutdated(context: Context, client: mozilla.components.concept.fetch.Client) {
        if (upgradeStarted) return
        upgradeStarted = true
        val app = context.applicationContext
        ensureLoaded(app)
        val outdated = themes.filter {
            !it.builtIn && it.converter < ThemeConverter.VERSION &&
                (it.source.startsWith("https://") || it.source.startsWith("http://"))
        }
        if (outdated.isEmpty()) return
        Thread({
            var changed = false
            for (old in outdated) {
                val fresh = runCatching { ThemeImporter(client).importUrl(old.source).theme }.getOrNull() ?: continue
                val dir = importDir(app)
                File(dir, old.id).deleteRecursively()
                if (fresh.pendingFiles.isNotEmpty()) {
                    val imageDir = File(dir, old.id).apply { mkdirs() }
                    for ((name, bytes) in fresh.pendingFiles) File(imageDir, name).writeBytes(bytes)
                }
                // Same id and name: the selection and the user's style overrides stay attached.
                val upgraded = fresh.copy(id = old.id, name = old.name, builtIn = false, pendingFiles = emptyMap())
                File(dir, "${old.id}.json").writeText(upgraded.toJson())
                changed = true
            }
            if (changed) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { reload(app) }
            }
        }, "KiwiFox theme upgrade").start()
    }

    /** Copies a .ttf/.otf font into the app and returns the file name to use as `fontFile`. */
    fun importFont(context: Context, bytes: ByteArray, displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext != "ttf" && ext != "otf") {
            throw ThemeImportException("only .ttf and .otf fonts work on Android (got '$displayName'; convert woff/woff2 first)")
        }
        val base = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "font" }
        val dir = fontDir(context)
        dir.mkdirs()
        val file = File(dir, "$base.$ext")
        file.writeBytes(bytes)
        runCatching { Typeface.createFromFile(file) }.onFailure {
            file.delete()
            throw ThemeImportException("not a usable font file: ${it.message}")
        }
        return file.name
    }

    /** Fonts imported into the app, by file name. */
    fun importedFonts(context: Context): List<String> =
        fontDir(context).listFiles { f -> f.isFile }?.map { it.name }?.sorted() ?: emptyList()

    /**
     * Applies the style's text and interface scale to an activity's base context, as desktop's
     * font size and `layout.css.devPixelsPerPx` scale the browser chrome. Covers XML and Compose
     * UI alike; web content keeps its own zoom. Call from `attachBaseContext`.
     */
    fun wrapBaseContext(base: Context): Context {
        ensureLoaded(base)
        val ui = style.uiScale ?: 1f
        val text = style.textScale ?: 1f
        if (ui == 1f && text == 1f) return base
        val config = Configuration(base.resources.configuration)
        if (ui != 1f) config.densityDpi = (config.densityDpi * ui).roundToInt()
        if (text != 1f) config.fontScale = config.fontScale * text
        return base.createConfigurationContext(config)
    }

    /**
     * Applies the static theme overlay generated for a bundled theme (see apply_kiwifox.py), so XML
     * screens such as Settings take the theme's colors, and the font overlay for the style's font
     * family. Must run before the activity inflates views; imported themes have no color overlay and
     * keep Firefox's XML colors. Returns true when a color overlay was applied.
     */
    fun applyOverlay(activity: Activity): Boolean {
        ensureLoaded(activity)
        style.fontFamily?.let { family ->
            val fontId = activity.resources.getIdentifier(fontOverlayName(family), "style", activity.packageName)
            if (fontId != 0) activity.theme.applyStyle(fontId, true)
        }
        val theme = current ?: return false
        if (!theme.builtIn) return false
        val resId = activity.resources.getIdentifier(overlayName(theme), "style", activity.packageName)
        if (resId == 0) return false
        activity.theme.applyStyle(resId, true)
        return true
    }

    /** Style name of a bundled theme's overlay: `KiwiFoxOverlay_<file name>` with non-alphanumerics as `_`. */
    fun overlayName(theme: KiwiTheme): String =
        "KiwiFoxOverlay_" + theme.id.removePrefix("bundled-").replace(Regex("[^A-Za-z0-9]"), "_").lowercase()

    /** Style name of the XML font overlay for an Android font family (generated by apply_kiwifox.py). */
    fun fontOverlayName(family: String): String = "KiwiFoxFont_" + family.replace('-', '_')

    /**
     * Tints the parts of the activity chrome that are XML, not Compose: the settings screens' top app
     * bar and the status and navigation bars. With no theme selected, Firefox's own theme attributes
     * are restored. Call after the toolbar is inflated, on resume, and whenever the selection changes.
     */
    fun applyToActivity(activity: Activity) {
        ensureLoaded(activity)
        runCatching { upgradeOutdated(activity, activity.components.core.client) }
        KiwiSettingsTint.register(activity)
        if (activity is org.mozilla.fenix.HomeActivity) runCatching { KiwiUpdater.maybeCheck(activity) }
        val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val roles = current?.let { if (dark) it.dark else it.light }
        val surface = roles?.get("surface") ?: activity.getColorFromAttr(materialR.attr.colorSurface)
        val onSurface = roles?.get("onSurface") ?: activity.getColorFromAttr(materialR.attr.colorOnSurface)

        // Desktop shows its tab strip (`frame` and the header images) above the toolbar; Android has the
        // status bar there, so it takes the header's color at that height, else frame.
        val private = runCatching { (activity as? org.mozilla.fenix.HomeActivity)?.let { it.browsingModeManager.mode.isPrivate } }
            .getOrNull() == true
        val header = (if (dark) KiwiStyles.current.dark else KiwiStyles.current.light).header

        activity.findViewById<Toolbar>(R.id.navigationToolbar)?.let { bar ->
            // The settings app bar continues the header the status bar draws, as the browser toolbar does.
            if (header != null && !private) {
                bar.background = KiwiStatusBar.headerBackground(bar, header)
            } else {
                bar.setBackgroundColor(surface)
            }
            bar.setTitleTextColor(onSurface)
            bar.navigationIcon?.setTint(onSurface)
            bar.overflowIcon?.setTint(onSurface)
        }
        val statusBar = if (private) null else header?.statusBar?.toArgb() ?: roles?.get("frame") ?: roles?.get("surface")
        val window = activity.window
        @Suppress("DEPRECATION")
        window.statusBarColor = statusBar ?: surface
        @Suppress("DEPRECATION")
        window.navigationBarColor = surface
        KiwiStatusBar.apply(activity, statusBar, if (private) null else header)
        // The navigation bar continues the page above it: desktop's new tab page color (the homepage),
        // else the theme background.
        val navigationBar = if (private || roles == null) null else roles["ntpBackground"] ?: roles["background"] ?: surface
        KiwiStatusBar.applyNavigationBar(activity, navigationBar, if (private) null else header?.takeIf { it.wallpaper && it.hasPicture })
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(statusBar ?: surface) > 0.5
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(navigationBar ?: surface) > 0.5
        }
    }

    /**
     * Re-asserts the system bar icon colors after Fenix's ThemeManager (called on every homepage
     * show) set them from its own colors; the painted bars themselves are overlay views it can't reach.
     */
    fun reapplySystemBarIcons(activity: Activity) {
        if (current == null) return
        applyToActivity(activity)
    }

    private fun styleKey() = KEY_STYLE_PREFIX + (current?.id ?: DEFAULT_THEME_KEY)

    private fun refreshStyle(context: Context) {
        overrides = prefs(context).getString(styleKey(), null)
            ?.let { runCatching { KiwiStyleSpec.fromJson(JSONObject(it)) }.getOrNull() }
            ?: KiwiStyleSpec.EMPTY
        style = (current?.style ?: KiwiStyleSpec.EMPTY).overlay(overrides)
        KiwiStyles.current = toComposeStyle(context, style, current)
    }

    private fun toComposeStyle(context: Context, spec: KiwiStyleSpec, theme: KiwiTheme?): KiwiStyle {
        fun chrome(mode: String): KiwiChromeColors {
            val roles = (if (mode == "dark") theme?.dark else theme?.light).orEmpty()
            fun color(key: String) = roles[key]?.let { Color(it) } ?: Color.Unspecified
            return KiwiChromeColors(
                fieldBorder = color("fieldBorder"),
                fieldBorderFocus = color("fieldBorderFocus"),
                // While typing, desktop 159 draws the address field and its results as one panel in
                // the popup colors; toolbar_field_focus only shows with the results closed.
                fieldFocus = roles["popup"]?.let { Color(it) } ?: color("fieldFocus"),
                fieldTextFocus = roles["popupText"]?.let { Color(it) } ?: color("fieldTextFocus"),
                icons = color("icons"),
                iconsAttention = color("iconsAttention"),
                header = theme?.let { t ->
                    t.header[mode]?.let { KiwiHeaderLoader.load(context, t, it, roles["frame"]) }
                        ?.copy(wallpaper = spec.homeWallpaper ?: true)
                },
            )
        }
        return KiwiStyle(
            fieldCornerRadius = spec.fieldRadius?.takeIf { it >= 0f }?.dp,
            fieldHeight = (spec.fieldHeight ?: KiwiStyleSpec.MAX_FIELD_HEIGHT).dp,
            fieldBorderWidth = (spec.fieldBorderWidth ?: 0f).dp,
            light = chrome("light"),
            dark = chrome("dark"),
            cornerScale = spec.cornerScale ?: 1f,
            fontFamily = fontFamily(context, spec),
        )
    }

    /** Where a theme's header images are: assets for bundled themes, files for imported ones. */
    internal fun openThemeFile(context: Context, theme: KiwiTheme, name: String): java.io.InputStream? =
        if (theme.builtIn) {
            runCatching { context.assets.open("$ASSET_DIR/${theme.id.removePrefix("bundled-")}/$name") }.getOrNull()
        } else {
            File(File(importDir(context), theme.id), name).takeIf { it.isFile }?.inputStream()
        }

    /** The theme's role colors for the activity's current mode, or null with no theme. */
    internal fun rolesFor(activity: Activity): Map<String, Int>? {
        val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return current?.let { if (dark) it.dark else it.light }
    }

    @Suppress("ReturnCount")
    private fun fontFamily(context: Context, spec: KiwiStyleSpec): FontFamily? {
        val fileName = spec.fontFile?.takeIf { it != NO_FONT_FILE }
        if (fileName != null) {
            val file = File(fontDir(context), fileName)
            val typeface = if (file.isFile) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
            if (typeface != null) return FontFamily(typeface)
        }
        return when (val name = spec.fontFamily) {
            null -> null
            "sans-serif" -> FontFamily.SansSerif
            "serif" -> FontFamily.Serif
            "monospace" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> FontFamily(Typeface.create(name, Typeface.NORMAL))
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun importDir(context: Context) = File(context.filesDir, "kiwifox/themes")

    private fun fontDir(context: Context) = File(context.filesDir, "kiwifox/fonts")

    private fun loadBundled(context: Context): List<KiwiTheme> {
        val names = context.assets.list(ASSET_DIR)?.filter { it.endsWith(".json") }?.sorted() ?: emptyList()
        return names.mapNotNull { file ->
            runCatching {
                val text = context.assets.open("$ASSET_DIR/$file").bufferedReader().use { it.readText() }
                val base = file.removeSuffix(".json")
                KiwiTheme.fromJson(id = "bundled-$base", text = text, builtIn = true, fallbackName = base)
            }.getOrNull()
        }
    }

    private fun loadImported(context: Context): List<KiwiTheme> {
        val files = importDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { file ->
            runCatching {
                val id = file.name.removeSuffix(".json")
                KiwiTheme.fromJson(id = id, text = file.readText(), builtIn = false, fallbackName = id)
            }.getOrNull()
        }
    }
}
