/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import org.json.JSONObject

/**
 * The non-color half of a theme: what desktop userChrome.css themes change besides colors.
 * Stored as the `style` object of a theme JSON; every key is optional and an absent key keeps
 * stock Firefox's value. Values are clamped to ranges the Android UI can take.
 *
 * ```
 * "style": {
 *   "fieldRadius": 6,          // dp; -1 = pill (Firefox's own)
 *   "fieldHeight": 40,         // dp, 36..48 (the toolbar slot stays 48)
 *   "fieldBorderWidth": 1,     // dp, 0..3; colors are the light/dark `fieldBorder` roles
 *   "cornerScale": 0.5,        // multiplies Material corner radii (menus, cards, dialogs), 0..3
 *   "fontFamily": "monospace", // an Android font family, see FONT_FAMILIES
 *   "fontFile": "Inter.ttf",   // a font imported into the app (overrides fontFamily in Compose UI)
 *   "textScale": 1.1,          // multiplies the system font scale, 0.8..1.4
 *   "uiScale": 0.9,            // multiplies the screen density (desktop devPixelsPerPx), 0.8..1.25
 *   "homeWallpaper": false     // image themes: header artwork behind the homepage (default true)
 * }
 * ```
 */
data class KiwiStyleSpec(
    val fieldRadius: Float? = null,
    val fieldHeight: Float? = null,
    val fieldBorderWidth: Float? = null,
    val cornerScale: Float? = null,
    val fontFamily: String? = null,
    val fontFile: String? = null,
    val textScale: Float? = null,
    val uiScale: Float? = null,
    val homeWallpaper: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == EMPTY

    /** This spec with every key that [top] sets replaced by [top]'s value. */
    fun overlay(top: KiwiStyleSpec) = KiwiStyleSpec(
        fieldRadius = top.fieldRadius ?: fieldRadius,
        fieldHeight = top.fieldHeight ?: fieldHeight,
        fieldBorderWidth = top.fieldBorderWidth ?: fieldBorderWidth,
        cornerScale = top.cornerScale ?: cornerScale,
        fontFamily = top.fontFamily ?: fontFamily,
        fontFile = top.fontFile ?: fontFile,
        textScale = top.textScale ?: textScale,
        uiScale = top.uiScale ?: uiScale,
        homeWallpaper = top.homeWallpaper ?: homeWallpaper,
    )

    /** The same spec with every value forced into its supported range. */
    fun clamped() = KiwiStyleSpec(
        fieldRadius = fieldRadius?.let { if (it < 0f) PILL else it.coerceIn(0f, MAX_FIELD_RADIUS) },
        fieldHeight = fieldHeight?.coerceIn(MIN_FIELD_HEIGHT, MAX_FIELD_HEIGHT),
        fieldBorderWidth = fieldBorderWidth?.coerceIn(0f, MAX_BORDER),
        cornerScale = cornerScale?.coerceIn(0f, MAX_CORNER_SCALE),
        fontFamily = fontFamily?.takeIf { it in FONT_FAMILIES },
        fontFile = fontFile?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') },
        textScale = textScale?.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
        uiScale = uiScale?.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE),
        homeWallpaper = homeWallpaper,
    )

    fun toJson(): JSONObject {
        val json = JSONObject()
        fieldRadius?.let { json.put("fieldRadius", it.toDouble()) }
        fieldHeight?.let { json.put("fieldHeight", it.toDouble()) }
        fieldBorderWidth?.let { json.put("fieldBorderWidth", it.toDouble()) }
        cornerScale?.let { json.put("cornerScale", it.toDouble()) }
        fontFamily?.let { json.put("fontFamily", it) }
        fontFile?.let { json.put("fontFile", it) }
        textScale?.let { json.put("textScale", it.toDouble()) }
        uiScale?.let { json.put("uiScale", it.toDouble()) }
        homeWallpaper?.let { json.put("homeWallpaper", it) }
        return json
    }

    /** One line per set key, for the importer's notes and the style editor's summary. */
    fun describe(): List<String> = buildList {
        fieldRadius?.let { add(if (it < 0f) "address bar: pill corners" else "address bar: ${fmt(it)} dp corners") }
        fieldHeight?.let { add("address bar: ${fmt(it)} dp tall") }
        fieldBorderWidth?.let { add("address bar: ${fmt(it)} dp border") }
        cornerScale?.let { add("corner radius ×${fmt(it)}") }
        fontFamily?.let { add("font: $it") }
        fontFile?.let { add("font file: $it") }
        textScale?.let { add("text size ×${fmt(it)}") }
        uiScale?.let { add("interface scale ×${fmt(it)}") }
        homeWallpaper?.let { add(if (it) "artwork behind the homepage" else "plain homepage") }
    }

    companion object {
        const val PILL = -1f
        const val MIN_FIELD_HEIGHT = 36f
        const val MAX_FIELD_HEIGHT = 48f
        const val MAX_FIELD_RADIUS = 24f
        const val MAX_BORDER = 3f
        const val MAX_CORNER_SCALE = 3f
        const val MIN_TEXT_SCALE = 0.8f
        const val MAX_TEXT_SCALE = 1.4f
        const val MIN_UI_SCALE = 0.8f
        const val MAX_UI_SCALE = 1.25f

        val EMPTY = KiwiStyleSpec()

        /** Font families every Android device ships (`/system/etc/fonts.xml` aliases). */
        val FONT_FAMILIES = listOf(
            "sans-serif", "sans-serif-light", "sans-serif-thin", "sans-serif-medium", "sans-serif-black",
            "sans-serif-condensed", "sans-serif-condensed-light", "sans-serif-smallcaps",
            "serif", "monospace", "serif-monospace", "casual", "cursive",
        )

        fun fromJson(obj: JSONObject?): KiwiStyleSpec {
            if (obj == null) return EMPTY
            fun f(key: String): Float? =
                if (obj.has(key) && !obj.isNull(key)) obj.optDouble(key, Double.NaN).toFloat().takeIf { !it.isNaN() } else null
            fun s(key: String): String? = obj.optString(key, "").ifBlank { null }
            return KiwiStyleSpec(
                fieldRadius = f("fieldRadius"),
                fieldHeight = f("fieldHeight"),
                fieldBorderWidth = f("fieldBorderWidth"),
                cornerScale = f("cornerScale"),
                fontFamily = s("fontFamily"),
                fontFile = s("fontFile"),
                textScale = f("textScale"),
                uiScale = f("uiScale"),
                homeWallpaper = if (obj.has("homeWallpaper") && !obj.isNull("homeWallpaper")) obj.optBoolean("homeWallpaper") else null,
            ).clamped()
        }

        private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v).trimEnd('0')
    }
}
