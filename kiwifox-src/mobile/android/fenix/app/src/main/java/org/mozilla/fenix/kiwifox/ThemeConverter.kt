/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToInt

/** A theme could not be converted. [message] says exactly why, for the user. */
class ThemeImportException(message: String) : Exception(message)

/** An opaque-or-not color as desktop Firefox parses it. */
data class Rgba(val r: Int, val g: Int, val b: Int, val a: Double = 1.0) {
    fun opaque() = copy(a = 1.0)

    /** Alpha-composites this color onto an opaque base, as the desktop browser does when layering. */
    fun over(base: Rgba): Rgba {
        fun mix(top: Int, bottom: Int) = (top * a + bottom * (1 - a)).roundToInt().coerceIn(0, 255)
        return Rgba(mix(r, base.r), mix(g, base.g), mix(b, base.b), 1.0)
    }

    fun argb(): Int = (0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()).toInt()

    fun hex(): String = KiwiTheme.toHex(argb())

    fun luminance(): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }
}

/**
 * Port of `import_theme.py`: converts a desktop static theme `manifest.json` (AMO themes,
 * Firefox Color exports, Mozilla's bundled themes) into the Material roles the Android toolbar reads.
 *
 * Desktop rules mirrored (Firefox 159 `LightweightThemeManager` / `LightweightThemeConsumer`):
 * missing or transparent `frame` -> white with alpha dropped; missing `toolbar_field` ->
 * rgba(255,255,255,0.8) over the bar; missing `toolbar_field_text` -> black; no `dark_theme`
 * block -> the same colors in both modes.
 */
object ThemeConverter {
    private val WHITE = Rgba(255, 255, 255)
    private val BLACK = Rgba(0, 0, 0)
    private val DESKTOP_FIELD_FALLBACK = Rgba(255, 255, 255, 0.8)
    private const val MIN_TEXT_CONTRAST = 4.5
    private const val MIN_ICON_CONTRAST = 3.0

    /** The subset of desktop keys that have a place on the Android toolbar. */
    private val SUPPORTED_KEYS = setOf(
        "frame", "toolbar", "toolbar_field", "toolbar_field_text", "toolbar_field_border", "toolbar_field_border_focus",
    )

    class Converted(val theme: KiwiTheme, val notes: List<String>)

    fun convert(manifest: JSONObject, fallbackName: String): Converted {
        val themeObj = manifest.optJSONObject("theme")
            ?: throw ThemeImportException("manifest has no `theme` key; this is not a static theme")
        val colors = themeObj.optJSONObject("colors") ?: JSONObject()
        val darkColors = manifest.optJSONObject("dark_theme")?.optJSONObject("colors")

        if (colors.length() == 0 && (darkColors == null || darkColors.length() == 0)) {
            val scheme = themeObj.optJSONObject("properties")?.optString("color_scheme")
            throw ThemeImportException(
                "theme defines no colors (color_scheme=$scheme); on desktop it only picks the built-in " +
                    "light or dark look. Choose that mode in Firefox settings instead.",
            )
        }

        val notes = ArrayList<String>()
        val allKeys = LinkedHashSet<String>()
        colors.keys().forEach { allKeys.add(it) }
        darkColors?.keys()?.forEach { allKeys.add(it) }
        val dropped = allKeys.filter { it !in SUPPORTED_KEYS }.sorted()
        if (dropped.isNotEmpty()) notes.add("dropped (no place on the Android toolbar): ${dropped.joinToString(", ")}")
        if ((themeObj.optJSONObject("images")?.length() ?: 0) > 0) notes.add("dropped: images (Android toolbar has no image layer)")

        val light = schemeFromColors(colors, notes, "light")
        val dark = if (darkColors != null && darkColors.length() > 0) schemeFromColors(darkColors, notes, "dark") else light
        val name = manifest.optString("name").ifBlank { fallbackName }
        // Desktop draws a 1px border around the URL field when the theme colors it.
        val bordered = "fieldBorder" in light || "fieldBorder" in dark
        val style = if (bordered) KiwiStyleSpec(fieldBorderWidth = 1f) else KiwiStyleSpec.EMPTY
        return Converted(
            KiwiTheme(id = "", name = name, source = name, light = light, dark = dark, builtIn = false, style = style),
            notes,
        )
    }

    /**
     * Maps one desktop `colors` object onto Material roles. When the theme's URL text would be
     * unreadable on the field or the bar, black or white replaces it and [notes] says so.
     */
    fun schemeFromColors(colors: JSONObject, notes: MutableList<String> = ArrayList(), mode: String = ""): Map<String, Int> {
        fun get(key: String): Rgba? {
            if (!colors.has(key) || colors.isNull(key)) return null
            val value = colors.get(key)
            if (value is String && value.isBlank()) return null
            return parseColor(key, value)
        }

        val frameRaw = get("frame")
        val frame = if (frameRaw == null || frameRaw.a == 0.0) WHITE else frameRaw.opaque()
        // Android has one bar where desktop has frame + toolbar: draw toolbar over frame, as desktop does.
        val bar = get("toolbar")?.over(frame) ?: frame
        val field = (get("toolbar_field") ?: DESKTOP_FIELD_FALLBACK).over(bar)
        val themeText = (get("toolbar_field_text") ?: BLACK).over(field)

        // The Android toolbar paints URL text and bar icons with one role (onSurface): both must be readable.
        val fieldText = if (contrast(themeText, field) >= MIN_TEXT_CONTRAST && contrast(themeText, bar) >= MIN_ICON_CONTRAST) {
            themeText
        } else {
            val replacement = listOf(BLACK, WHITE).maxByOrNull { minOf(contrast(it, field), contrast(it, bar)) } ?: BLACK
            notes.add(
                "${if (mode.isEmpty()) "" else "$mode: "}URL text ${themeText.hex()} replaced with ${replacement.hex()}: " +
                    "contrast ${"%.2f".format(contrast(themeText, field))}:1 on the address field is too low to read",
            )
            replacement
        }

        val roles = LinkedHashMap<String, Int>()
        for (role in listOf("background", "surface", "surfaceBright", "surfaceContainer", "surfaceContainerLow", "surfaceContainerLowest")) {
            roles[role] = bar.argb()
        }
        roles["surfaceContainerHigh"] = field.argb()
        roles["surfaceContainerHighest"] = field.argb()
        for (role in listOf("onBackground", "onSurface", "onSurfaceVariant")) roles[role] = fieldText.argb()
        get("toolbar_field_border_focus")?.let { accent ->
            val over = accent.over(bar).argb()
            roles["primary"] = over
            roles["tertiary"] = over
            if (accent.a > 0.0) roles["fieldBorderFocus"] = over
        }
        get("toolbar_field_border")?.takeIf { it.a > 0.0 }?.let { border ->
            roles["fieldBorder"] = border.over(field).argb()
        }
        return roles
    }

    fun contrast(a: Rgba, b: Rgba): Double {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private val HEX = Regex("^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$")
    private val RGB = Regex("^rgba?\\(\\s*([\\d.]+)[\\s,]+([\\d.]+)[\\s,]+([\\d.]+)(?:[\\s,/]+([\\d.]+%?))?\\s*\\)$")
    private val HSL = Regex("^hsla?\\(\\s*([\\d.]+)(?:deg)?[\\s,]+([\\d.]+)%[\\s,]+([\\d.]+)%(?:[\\s,/]+([\\d.]+%?))?\\s*\\)$")

    /** Parses the color forms desktop accepts: RGB(A) arrays and CSS color strings. */
    @Suppress("ReturnCount")
    fun parseColor(key: String, value: Any): Rgba {
        if (value is JSONArray) {
            if (value.length() != 3 && value.length() != 4) {
                throw ThemeImportException("$key: color array must have 3 or 4 entries, got $value")
            }
            val alpha = if (value.length() == 4) value.getDouble(3) else 1.0
            return Rgba(value.getInt(0), value.getInt(1), value.getInt(2), alpha)
        }
        val text = value.toString().trim().lowercase()
        if (text == "transparent") return Rgba(0, 0, 0, 0.0)
        HEX.matchEntire(text)?.let { m ->
            var digits = m.groupValues[1]
            if (digits.length == 3 || digits.length == 4) digits = digits.map { "$it$it" }.joinToString("")
            val alpha = if (digits.length == 8) digits.substring(6, 8).toInt(16) / 255.0 else 1.0
            return Rgba(digits.substring(0, 2).toInt(16), digits.substring(2, 4).toInt(16), digits.substring(4, 6).toInt(16), alpha)
        }
        RGB.matchEntire(text)?.let { m ->
            val (r, g, b, a) = m.destructured
            return Rgba(r.toDouble().roundToInt(), g.toDouble().roundToInt(), b.toDouble().roundToInt(), parseAlpha(a))
        }
        HSL.matchEntire(text)?.let { m ->
            val (h, s, l, a) = m.destructured
            val (r, g, b) = hslToRgb(h.toDouble() / 360, s.toDouble() / 100, l.toDouble() / 100)
            return Rgba((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt(), parseAlpha(a))
        }
        CssColors.NAMED[text]?.let { argb ->
            return Rgba((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        }
        throw ThemeImportException("$key: unsupported color syntax '$value' (convert it to hex)")
    }

    private fun parseAlpha(a: String): Double = when {
        a.isEmpty() -> 1.0
        a.endsWith("%") -> a.dropLast(1).toDouble() / 100
        else -> a.toDouble()
    }

    private fun hslToRgb(h: Double, s: Double, l: Double): Triple<Double, Double, Double> {
        if (s == 0.0) return Triple(l, l, l)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return Triple(hue(h + 1.0 / 3), hue(h), hue(h - 1.0 / 3))
    }
}

/** CSS named colors (CSS Color Module Level 4), as `webcolors` resolves them on the desktop pipeline. */
object CssColors {
    val NAMED: Map<String, Int> = mapOf(
        "aliceblue" to 0xF0F8FF, "antiquewhite" to 0xFAEBD7, "aqua" to 0x00FFFF, "aquamarine" to 0x7FFFD4,
        "azure" to 0xF0FFFF, "beige" to 0xF5F5DC, "bisque" to 0xFFE4C4, "black" to 0x000000,
        "blanchedalmond" to 0xFFEBCD, "blue" to 0x0000FF, "blueviolet" to 0x8A2BE2, "brown" to 0xA52A2A,
        "burlywood" to 0xDEB887, "cadetblue" to 0x5F9EA0, "chartreuse" to 0x7FFF00, "chocolate" to 0xD2691E,
        "coral" to 0xFF7F50, "cornflowerblue" to 0x6495ED, "cornsilk" to 0xFFF8DC, "crimson" to 0xDC143C,
        "cyan" to 0x00FFFF, "darkblue" to 0x00008B, "darkcyan" to 0x008B8B, "darkgoldenrod" to 0xB8860B,
        "darkgray" to 0xA9A9A9, "darkgreen" to 0x006400, "darkgrey" to 0xA9A9A9, "darkkhaki" to 0xBDB76B,
        "darkmagenta" to 0x8B008B, "darkolivegreen" to 0x556B2F, "darkorange" to 0xFF8C00, "darkorchid" to 0x9932CC,
        "darkred" to 0x8B0000, "darksalmon" to 0xE9967A, "darkseagreen" to 0x8FBC8F, "darkslateblue" to 0x483D8B,
        "darkslategray" to 0x2F4F4F, "darkslategrey" to 0x2F4F4F, "darkturquoise" to 0x00CED1, "darkviolet" to 0x9400D3,
        "deeppink" to 0xFF1493, "deepskyblue" to 0x00BFFF, "dimgray" to 0x696969, "dimgrey" to 0x696969,
        "dodgerblue" to 0x1E90FF, "firebrick" to 0xB22222, "floralwhite" to 0xFFFAF0, "forestgreen" to 0x228B22,
        "fuchsia" to 0xFF00FF, "gainsboro" to 0xDCDCDC, "ghostwhite" to 0xF8F8FF, "gold" to 0xFFD700,
        "goldenrod" to 0xDAA520, "gray" to 0x808080, "green" to 0x008000, "greenyellow" to 0xADFF2F,
        "grey" to 0x808080, "honeydew" to 0xF0FFF0, "hotpink" to 0xFF69B4, "indianred" to 0xCD5C5C,
        "indigo" to 0x4B0082, "ivory" to 0xFFFFF0, "khaki" to 0xF0E68C, "lavender" to 0xE6E6FA,
        "lavenderblush" to 0xFFF0F5, "lawngreen" to 0x7CFC00, "lemonchiffon" to 0xFFFACD, "lightblue" to 0xADD8E6,
        "lightcoral" to 0xF08080, "lightcyan" to 0xE0FFFF, "lightgoldenrodyellow" to 0xFAFAD2, "lightgray" to 0xD3D3D3,
        "lightgreen" to 0x90EE90, "lightgrey" to 0xD3D3D3, "lightpink" to 0xFFB6C1, "lightsalmon" to 0xFFA07A,
        "lightseagreen" to 0x20B2AA, "lightskyblue" to 0x87CEFA, "lightslategray" to 0x778899, "lightslategrey" to 0x778899,
        "lightsteelblue" to 0xB0C4DE, "lightyellow" to 0xFFFFE0, "lime" to 0x00FF00, "limegreen" to 0x32CD32,
        "linen" to 0xFAF0E6, "magenta" to 0xFF00FF, "maroon" to 0x800000, "mediumaquamarine" to 0x66CDAA,
        "mediumblue" to 0x0000CD, "mediumorchid" to 0xBA55D3, "mediumpurple" to 0x9370DB, "mediumseagreen" to 0x3CB371,
        "mediumslateblue" to 0x7B68EE, "mediumspringgreen" to 0x00FA9A, "mediumturquoise" to 0x48D1CC, "mediumvioletred" to 0xC71585,
        "midnightblue" to 0x191970, "mintcream" to 0xF5FFFA, "mistyrose" to 0xFFE4E1, "moccasin" to 0xFFE4B5,
        "navajowhite" to 0xFFDEAD, "navy" to 0x000080, "oldlace" to 0xFDF5E6, "olive" to 0x808000,
        "olivedrab" to 0x6B8E23, "orange" to 0xFFA500, "orangered" to 0xFF4500, "orchid" to 0xDA70D6,
        "palegoldenrod" to 0xEEE8AA, "palegreen" to 0x98FB98, "paleturquoise" to 0xAFEEEE, "palevioletred" to 0xDB7093,
        "papayawhip" to 0xFFEFD5, "peachpuff" to 0xFFDAB9, "peru" to 0xCD853F, "pink" to 0xFFC0CB,
        "plum" to 0xDDA0DD, "powderblue" to 0xB0E0E6, "purple" to 0x800080, "rebeccapurple" to 0x663399,
        "red" to 0xFF0000, "rosybrown" to 0xBC8F8F, "royalblue" to 0x4169E1, "saddlebrown" to 0x8B4513,
        "salmon" to 0xFA8072, "sandybrown" to 0xF4A460, "seagreen" to 0x2E8B57, "seashell" to 0xFFF5EE,
        "sienna" to 0xA0522D, "silver" to 0xC0C0C0, "skyblue" to 0x87CEEB, "slateblue" to 0x6A5ACD,
        "slategray" to 0x708090, "slategrey" to 0x708090, "snow" to 0xFFFAFA, "springgreen" to 0x00FF7F,
        "steelblue" to 0x4682B4, "tan" to 0xD2B48C, "teal" to 0x008080, "thistle" to 0xD8BFD8,
        "tomato" to 0xFF6347, "turquoise" to 0x40E0D0, "violet" to 0xEE82EE, "wheat" to 0xF5DEB3,
        "white" to 0xFFFFFF, "whitesmoke" to 0xF5F5F5, "yellow" to 0xFFFF00, "yellowgreen" to 0x9ACD32,
    )
}
