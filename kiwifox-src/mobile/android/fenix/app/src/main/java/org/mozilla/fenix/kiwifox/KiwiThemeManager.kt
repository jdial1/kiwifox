/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import mozilla.components.support.ktx.android.content.getColorFromAttr
import org.mozilla.fenix.R
import com.google.android.material.R as materialR
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Holds the installed themes and the selected one. [current] is Compose state, so every
 * `FirefoxTheme { }` composition recomposes as soon as the selection changes.
 *
 * Bundled themes live in `assets/kiwifox/themes/` (one `.json` per theme); imported ones in
 * `filesDir/kiwifox/themes/`. The selection is a theme id in the "kiwifox" preferences.
 */
object KiwiThemeManager {
    private const val PREFS = "kiwifox"
    private const val KEY_SELECTED = "selected_theme"
    private const val ASSET_DIR = "kiwifox/themes"

    /** The selected theme, or null for Firefox's own colors. */
    var current: KiwiTheme? by mutableStateOf(null)
        private set

    /** Bundled themes followed by imported ones. */
    var themes: List<KiwiTheme> by mutableStateOf(emptyList())
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
    }

    fun select(context: Context, theme: KiwiTheme?) {
        prefs(context).edit().putString(KEY_SELECTED, theme?.id).apply()
        current = theme
    }

    /** Writes an imported theme to disk under a fresh id and returns the stored copy. */
    fun saveImported(context: Context, theme: KiwiTheme): KiwiTheme {
        val dir = importDir(context)
        dir.mkdirs()
        val slug = theme.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "theme" }
        var id = "user-$slug"
        var n = 2
        while (File(dir, "$id.json").exists()) id = "user-$slug-${n++}"
        val stored = theme.copy(id = id, builtIn = false)
        File(dir, "$id.json").writeText(stored.toJson())
        reload(context)
        return stored
    }

    fun delete(context: Context, theme: KiwiTheme) {
        if (theme.builtIn) return
        File(importDir(context), "${theme.id}.json").delete()
        if (current?.id == theme.id) select(context, null)
        reload(context)
    }

    /**
     * Applies the static theme overlay generated for a bundled theme (see apply_kiwifox.py), so XML
     * screens such as Settings take the theme's colors. Must run before the activity inflates views;
     * imported themes have no overlay and keep Firefox's XML colors. Returns true when applied.
     */
    fun applyOverlay(activity: Activity): Boolean {
        ensureLoaded(activity)
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

    /**
     * Tints the parts of the activity chrome that are XML, not Compose: the settings screens' top app
     * bar and the status and navigation bars. With no theme selected, Firefox's own theme attributes
     * are restored. Call after the toolbar is inflated, on resume, and whenever the selection changes.
     */
    fun applyToActivity(activity: Activity) {
        ensureLoaded(activity)
        val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val roles = current?.let { if (dark) it.dark else it.light }
        val surface = roles?.get("surface") ?: activity.getColorFromAttr(materialR.attr.colorSurface)
        val onSurface = roles?.get("onSurface") ?: activity.getColorFromAttr(materialR.attr.colorOnSurface)

        activity.findViewById<Toolbar>(R.id.navigationToolbar)?.let { bar ->
            bar.setBackgroundColor(surface)
            bar.setTitleTextColor(onSurface)
            bar.navigationIcon?.setTint(onSurface)
            bar.overflowIcon?.setTint(onSurface)
        }

        val window = activity.window
        @Suppress("DEPRECATION")
        window.statusBarColor = surface
        @Suppress("DEPRECATION")
        window.navigationBarColor = surface
        val light = ColorUtils.calculateLuminance(surface) > 0.5
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun importDir(context: Context) = File(context.filesDir, "kiwifox/themes")

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
