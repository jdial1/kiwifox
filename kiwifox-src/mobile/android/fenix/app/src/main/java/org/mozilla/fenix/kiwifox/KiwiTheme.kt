/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * A KiwiFox theme: a set of Material color roles for light and for dark mode.
 *
 * The JSON format is the one produced by the desktop `import_theme.py` pipeline:
 * `{"_source": "...", "light": {"surface": "#RRGGBB", ...}, "dark": {...}}`.
 * Only roles listed in [ROLES] are applied to the color scheme; [EXTRA_ROLES] are desktop colors
 * with no Material role (new tab page, menus, frame, icons, address field focus), read by
 * [KiwiThemeManager] and [KiwiSurfaces]. The optional `style` object holds radius, font and scale
 * settings ([KiwiStyleSpec]); the optional `header` object the desktop header per mode ([HeaderSpec]),
 * whose image files sit in a folder named after the theme file.
 */
data class KiwiTheme(
    val id: String,
    val name: String,
    val source: String,
    val light: Map<String, Int>,
    val dark: Map<String, Int>,
    val builtIn: Boolean,
    val style: KiwiStyleSpec = KiwiStyleSpec.EMPTY,
    val header: Map<String, HeaderSpec> = emptyMap(),
    /** Image bytes of a theme being imported, by file name; written next to the theme when saved. */
    val pendingFiles: Map<String, ByteArray> = emptyMap(),
) {
    /**
     * A desktop header for one mode: [toolbar] and [field] are desktop `toolbar` and `toolbar_field` with
     * their alpha (#AARRGGBB), painted over [layers]. Each layer has either a [LayerSpec.file] (an image in
     * the theme folder) or a [LayerSpec.gradient] (the body of a CSS `linear-gradient(...)`);
     * [LayerSpec.windowArea] marks layers sized to the whole window (`backgrounds_area: window`).
     */
    data class HeaderSpec(val toolbar: Long, val layers: List<LayerSpec>, val field: Long = DESKTOP_FIELD_FALLBACK)

    data class LayerSpec(
        val file: String? = null,
        val gradient: String? = null,
        val alignment: String = "left top",
        val tiling: String = "no-repeat",
        val size: String = "auto",
        val pxPerCss: Float = 1f,
        val windowArea: Boolean = false,
    )

    /** Returns [scheme] with this theme's roles for the requested mode replaced. */
    fun apply(scheme: ColorScheme, darkMode: Boolean): ColorScheme {
        var result = scheme
        for ((role, argb) in if (darkMode) dark else light) {
            result = result.withRole(role, Color(argb))
        }
        return result
    }

    /** Bar, field and text colors for a swatch preview. */
    fun preview(darkMode: Boolean): Triple<Color?, Color?, Color?> {
        val roles = if (darkMode) dark else light
        return Triple(
            roles["surface"]?.let(::Color),
            roles["surfaceContainerHighest"]?.let(::Color),
            roles["onSurface"]?.let(::Color),
        )
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("_source", source)
        json.put("name", name)
        json.put("light", rolesToJson(light))
        json.put("dark", rolesToJson(dark))
        if (!style.isEmpty) json.put("style", style.toJson())
        if (header.isNotEmpty()) {
            val h = JSONObject()
            for ((mode, spec) in header) {
                val layers = org.json.JSONArray()
                for (l in spec.layers) {
                    val o = JSONObject()
                    l.file?.let { o.put("file", it) }
                    l.gradient?.let { o.put("gradient", it) }
                    o.put("alignment", l.alignment).put("tiling", l.tiling).put("size", l.size)
                    if (l.pxPerCss != 1f) o.put("pxPerCss", l.pxPerCss.toDouble())
                    if (l.windowArea) o.put("windowArea", true)
                    layers.put(o)
                }
                h.put(
                    mode,
                    JSONObject()
                        .put("toolbar", String.format("#%08X", spec.toolbar and 0xFFFFFFFFL))
                        .put("field", String.format("#%08X", spec.field and 0xFFFFFFFFL))
                        .put("layers", layers),
                )
            }
            json.put("header", h)
        }
        return json.toString(2)
    }

    companion object {
        /** Material roles the Android toolbar and the rest of the Compose UI read. */
        val ROLES = listOf(
            "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
            "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
            "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
            "background", "onBackground", "surface", "onSurface",
            "surfaceVariant", "onSurfaceVariant", "surfaceTint",
            "inverseSurface", "inverseOnSurface",
            "outline", "outlineVariant",
            "surfaceBright", "surfaceDim", "surfaceContainer", "surfaceContainerHigh",
            "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest",
        )

        /**
         * Desktop colors without a Material role, by desktop key: `toolbar_field_border(_focus)`,
         * `toolbar_field_focus`, `toolbar_field_text_focus`, `ntp_background`, `ntp_text`,
         * `ntp_card_background`, `popup`, `popup_text`, `popup_highlight`, `popup_highlight_text`,
         * `popup_border`, `frame`, `icons`, `icons_attention`.
         */
        /** Desktop's address field where `toolbar_field` is unset: rgba(255, 255, 255, 0.8). */
        const val DESKTOP_FIELD_FALLBACK = 0xCCFFFFFFL

        val EXTRA_ROLES = listOf(
            "fieldBorder", "fieldBorderFocus", "fieldFocus", "fieldTextFocus",
            "ntpBackground", "ntpText", "ntpCard",
            "popup", "popupText", "popupHighlight", "popupHighlightText", "popupBorder",
            "frame", "icons", "iconsAttention",
            "tabSelected", "tabText", "tabBackgroundText", "tabLine",
        )

        fun fromJson(id: String, text: String, builtIn: Boolean, fallbackName: String): KiwiTheme {
            val json = JSONObject(text)
            // Bundled files carry provenance in `_source`, not a display name: use the file name for those.
            val name = json.optString("name").ifBlank {
                if (builtIn) fallbackName.replaceFirstChar { it.uppercase() } else json.optString("_source").ifBlank { fallbackName }
            }
            return KiwiTheme(
                id = id,
                name = name,
                source = json.optString("_source", name),
                light = rolesFromJson(json.optJSONObject("light")),
                dark = rolesFromJson(json.optJSONObject("dark")),
                builtIn = builtIn,
                style = KiwiStyleSpec.fromJson(json.optJSONObject("style")),
                header = headerFromJson(json.optJSONObject("header")),
            )
        }

        private fun headerFromJson(obj: JSONObject?): Map<String, HeaderSpec> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, HeaderSpec>()
            for (mode in listOf("light", "dark")) {
                val m = obj.optJSONObject(mode) ?: continue
                fun argb(key: String, default: Long): Long =
                    m.optString(key).removePrefix("#").takeIf { it.length == 8 }?.toLong(16) ?: default
                val argb = argb("toolbar", 0L)
                val field = argb("field", DESKTOP_FIELD_FALLBACK)
                val arr = m.optJSONArray("layers") ?: org.json.JSONArray()
                val layers = (0 until arr.length()).map { i ->
                    val l = arr.getJSONObject(i)
                    LayerSpec(
                        file = l.optString("file").ifBlank { null }?.takeIf { !it.contains('/') && !it.contains('\\') },
                        gradient = l.optString("gradient").ifBlank { null },
                        alignment = l.optString("alignment", "left top"),
                        tiling = l.optString("tiling", "no-repeat"),
                        size = l.optString("size", "auto"),
                        pxPerCss = l.optDouble("pxPerCss", 1.0).toFloat(),
                        windowArea = l.optBoolean("windowArea", false),
                    )
                }
                out[mode] = HeaderSpec(argb, layers, field)
            }
            return out
        }

        private fun rolesFromJson(obj: JSONObject?): Map<String, Int> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, Int>()
            for (key in obj.keys()) {
                if (key !in ROLES && key !in EXTRA_ROLES) continue
                out[key] = parseHex(obj.getString(key))
            }
            return out
        }

        private fun rolesToJson(roles: Map<String, Int>): JSONObject {
            val obj = JSONObject()
            for ((role, argb) in roles) obj.put(role, toHex(argb))
            return obj
        }

        /** Parses `#RRGGBB` into an opaque ARGB int. */
        fun parseHex(value: String): Int {
            val hex = value.trim().removePrefix("#")
            require(hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "color must be #RRGGBB, got '$value'"
            }
            return (0xFF000000L or hex.toLong(16)).toInt()
        }

        fun toHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)
    }
}

@Suppress("CyclomaticComplexMethod")
private fun ColorScheme.withRole(role: String, color: Color): ColorScheme = when (role) {
    "primary" -> copy(primary = color)
    "onPrimary" -> copy(onPrimary = color)
    "primaryContainer" -> copy(primaryContainer = color)
    "onPrimaryContainer" -> copy(onPrimaryContainer = color)
    "secondary" -> copy(secondary = color)
    "onSecondary" -> copy(onSecondary = color)
    "secondaryContainer" -> copy(secondaryContainer = color)
    "onSecondaryContainer" -> copy(onSecondaryContainer = color)
    "tertiary" -> copy(tertiary = color)
    "onTertiary" -> copy(onTertiary = color)
    "tertiaryContainer" -> copy(tertiaryContainer = color)
    "onTertiaryContainer" -> copy(onTertiaryContainer = color)
    "background" -> copy(background = color)
    "onBackground" -> copy(onBackground = color)
    "surface" -> copy(surface = color)
    "onSurface" -> copy(onSurface = color)
    "surfaceVariant" -> copy(surfaceVariant = color)
    "onSurfaceVariant" -> copy(onSurfaceVariant = color)
    "surfaceTint" -> copy(surfaceTint = color)
    "inverseSurface" -> copy(inverseSurface = color)
    "inverseOnSurface" -> copy(inverseOnSurface = color)
    "outline" -> copy(outline = color)
    "outlineVariant" -> copy(outlineVariant = color)
    "surfaceBright" -> copy(surfaceBright = color)
    "surfaceDim" -> copy(surfaceDim = color)
    "surfaceContainer" -> copy(surfaceContainer = color)
    "surfaceContainerHigh" -> copy(surfaceContainerHigh = color)
    "surfaceContainerHighest" -> copy(surfaceContainerHighest = color)
    "surfaceContainerLow" -> copy(surfaceContainerLow = color)
    "surfaceContainerLowest" -> copy(surfaceContainerLowest = color)
    else -> this
}
