/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import org.json.JSONObject

/**
 * Port of `tools/css_theme_colors.py`: pulls toolbar colors out of a `userChrome.css` theme
 * (r/FirefoxCSS style) into a desktop-style manifest that [ThemeConverter] then validates.
 *
 * It resolves `:root` custom properties through `var()` chains, `light-dark()` and
 * `@media (prefers-color-scheme)`. Variables the theme does not set fall back to Firefox's own
 * default (Proton) values in [FIREFOX_DEFAULTS], the same way desktop Firefox fills them in when
 * the default theme is active, and CSS system color keywords map to those defaults too. Roles
 * that end up entirely on defaults are reported in the notes so the user knows the theme only
 * changes layout. Selectors keyed on browser state (`[privatebrowsingmode]`, `[style*=...]`,
 * `-moz-pref()`) are still ignored: they cannot be evaluated here.
 */
object CssThemeExtractor {
    private val ROLES: Map<String, List<String>> = linkedMapOf(
        "frame" to listOf("--toolbar-bgcolor", "--toolbar-background-color", "--lwt-accent-color", "--toolbox-bgcolor"),
        "toolbar_field" to listOf("--toolbar-field-background-color", "--urlbar-box-bgcolor", "--lwt-toolbar-field-background-color"),
        "toolbar_field_text" to listOf(
            "--toolbar-field-color", "--toolbar-field-text-color", "--urlbar-box-text-color",
            "--toolbar-color", "--lwt-text-color",
        ),
    )
    private val LITERAL = Regex("^(#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\)|hsla?\\([^)]*\\)|[a-zA-Z]+)$")
    private val ROOT_SELECTOR = Regex("^(:root|\\*|html|body)(:not\\([^)]*\\))*$")
    private val LIGHT_DARK = Regex("^light-dark\\(\\s*(.+?)\\s*,\\s*(.+)\\)$")
    private val VAR = Regex("^var\\(\\s*(--[\\w-]+)\\s*(?:,\\s*(.+))?\\)$")
    private val SCHEME_QUERY = Regex("prefers-color-scheme\\s*:\\s*(light|dark)")
    private const val MAX_DEPTH = 12

    /**
     * Effective values of Firefox 159's default theme (Proton) for the variables the roles read.
     * Light: toolbar #f9f9fb, tab bar #f0f0f4, text #15141a, field = 5% black over the toolbar.
     * Dark: toolbar rgb(43,42,51), tab bar #1c1b22, text #fbfbfe, field = 30% black over the toolbar.
     */
    private val FIREFOX_DEFAULTS: Map<String, String> = linkedMapOf(
        "--toolbar-bgcolor" to "light-dark(#f9f9fb, #2b2a33)",
        "--toolbar-background-color" to "light-dark(#f9f9fb, #2b2a33)",
        "--toolbox-bgcolor" to "light-dark(#f0f0f4, #1c1b22)",
        "--lwt-accent-color" to "light-dark(#f0f0f4, #1c1b22)",
        "--toolbar-color" to "light-dark(#15141a, #fbfbfe)",
        "--lwt-text-color" to "light-dark(#15141a, #fbfbfe)",
        "--toolbar-field-background-color" to "light-dark(rgba(0, 0, 0, 0.05), rgba(0, 0, 0, 0.3))",
        "--lwt-toolbar-field-background-color" to "light-dark(rgba(0, 0, 0, 0.05), rgba(0, 0, 0, 0.3))",
        "--urlbar-box-bgcolor" to "light-dark(rgba(0, 0, 0, 0.05), rgba(0, 0, 0, 0.3))",
        "--toolbar-field-color" to "light-dark(#15141a, #fbfbfe)",
        "--urlbar-box-text-color" to "light-dark(#15141a, #fbfbfe)",
    )

    /** CSS system colors, as Firefox's default theme renders them (lowercase keyword -> light/dark). */
    private val SYSTEM_COLORS: Map<String, Pair<String, String>> = mapOf(
        "window" to ("#f9f9fb" to "#2b2a33"),
        "canvas" to ("#f9f9fb" to "#2b2a33"),
        "-moz-dialog" to ("#f9f9fb" to "#2b2a33"),
        "menu" to ("#f9f9fb" to "#2b2a33"),
        "buttonface" to ("#f0f0f4" to "#2b2a33"),
        "activecaption" to ("#f0f0f4" to "#1c1b22"),
        "inactivecaption" to ("#f0f0f4" to "#1c1b22"),
        "field" to ("#ffffff" to "#42414d"),
        "windowtext" to ("#15141a" to "#fbfbfe"),
        "canvastext" to ("#15141a" to "#fbfbfe"),
        "fieldtext" to ("#15141a" to "#fbfbfe"),
        "buttontext" to ("#15141a" to "#fbfbfe"),
        "menutext" to ("#15141a" to "#fbfbfe"),
        "-moz-dialogtext" to ("#15141a" to "#fbfbfe"),
        "captiontext" to ("#15141a" to "#fbfbfe"),
        "graytext" to ("#6d6d6e" to "#a0a0a4"),
        "highlight" to ("#0060df" to "#00ddff"),
        "highlighttext" to ("#ffffff" to "#15141a"),
        "selecteditem" to ("#0060df" to "#00ddff"),
        "selecteditemtext" to ("#ffffff" to "#15141a"),
        "accentcolor" to ("#0060df" to "#00ddff"),
        "accentcolortext" to ("#ffffff" to "#15141a"),
        "linktext" to ("#0060df" to "#00ddff"),
    )

    class Unresolved(message: String) : Exception(message)

    /** Builds a desktop manifest (light colors, plus `dark_theme` when a distinct dark palette resolves). */
    fun toManifest(css: String, name: String): Pair<JSONObject, List<String>> {
        val notes = ArrayList<String>()
        val light = try {
            colorsFor(css, "light", notes)
        } catch (e: Unresolved) {
            throw ThemeImportException(
                "userChrome.css theme: ${e.message}. Its toolbar colors depend on browser state this importer " +
                    "cannot see. Import the matching AMO theme instead.",
            )
        }
        val manifest = JSONObject()
        manifest.put("manifest_version", 2)
        manifest.put("name", name)
        manifest.put("version", "1.0")
        manifest.put("theme", JSONObject().put("colors", JSONObject(light)))
        try {
            val dark = colorsFor(css, "dark", ArrayList())
            if (dark != light) manifest.put("dark_theme", JSONObject().put("colors", JSONObject(dark)))
        } catch (e: Unresolved) {
            notes.add("no separate dark palette resolved (${e.message}); light colors apply in both modes")
        }
        return manifest to notes
    }

    private fun colorsFor(css: String, scheme: String, notes: MutableList<String>): Map<String, String> {
        val themeVariables = collect(css, scheme)
        val withDefaults = LinkedHashMap(FIREFOX_DEFAULTS).apply { putAll(themeVariables) }
        val out = LinkedHashMap<String, String>()
        val defaulted = ArrayList<String>()
        for ((key, names) in ROLES) {
            // First the theme on its own; then the theme on top of Firefox's defaults, as on desktop.
            var found = names.firstNotNullOfOrNull { name -> runCatching { resolve(name, themeVariables, scheme, 0) }.getOrNull() }
            if (found == null) {
                found = names.firstNotNullOfOrNull { name -> runCatching { resolve(name, withDefaults, scheme, 0) }.getOrNull() }
                if (found != null) defaulted.add(key)
            }
            out[key] = systemColor(found ?: throw Unresolved("$scheme: no value for $key via $names"), scheme)
        }
        if (defaulted.size == ROLES.size) {
            notes.add("this theme changes layout only: all toolbar colors are Firefox's defaults")
        } else if (defaulted.isNotEmpty()) {
            notes.add("Firefox default used for: ${defaulted.joinToString(", ")} (the theme does not set them)")
        }
        return out
    }

    /** Replaces a CSS system color keyword with the value Firefox's default theme gives it. */
    private fun systemColor(value: String, scheme: String): String {
        val mapped = SYSTEM_COLORS[value.lowercase()] ?: return value
        return if (scheme == "light") mapped.first else mapped.second
    }

    private fun resolve(name: String, variables: Map<String, String>, scheme: String, depth: Int): String {
        if (depth > MAX_DEPTH) throw Unresolved(name)
        var value = variables[name] ?: throw Unresolved(name)
        LIGHT_DARK.matchEntire(value)?.let { m -> value = m.groupValues[if (scheme == "light") 1 else 2].trim() }
        VAR.matchEntire(value)?.let { m ->
            return try {
                resolve(m.groupValues[1], variables, scheme, depth + 1)
            } catch (e: Unresolved) {
                val fallback = m.groupValues[2].trim()
                if (fallback.isNotEmpty() && LITERAL.matches(fallback)) fallback else throw e
            }
        }
        if (!LITERAL.matches(value)) throw Unresolved("$name = $value")
        return value
    }

    /** Root-scope custom properties for one color scheme. Later wins; `!important` beats normal. */
    private fun collect(css: String, scheme: String): Map<String, String> {
        val found = LinkedHashMap<String, Pair<String, Boolean>>()
        walk(stripComments(css), scheme, true, found)
        return found.mapValues { it.value.first }
    }

    private fun walk(css: String, scheme: String, active: Boolean, found: MutableMap<String, Pair<String, Boolean>>) {
        for (rule in splitRules(css)) {
            val prelude = rule.prelude.trim()
            val body = rule.body ?: continue // `@import ...;` and stray statements have no block
            if (prelude.startsWith("@")) {
                var nestedActive = active
                if (prelude.lowercase().startsWith("@media")) {
                    if (prelude.contains("-moz-pref(")) continue // depends on a user pref we cannot read
                    val m = SCHEME_QUERY.find(prelude)
                    nestedActive = active && (m == null || m.groupValues[1] == scheme)
                }
                walk(body, scheme, nestedActive, found)
            } else if (active) {
                val selectors = prelude.split(",").map { it.trim() }
                // A selector list applies if any selector matches the default browser state.
                if (selectors.none { ROOT_SELECTOR.matches(it) }) continue
                for ((name, rawValue) in declarations(body)) {
                    if (!name.startsWith("--")) continue
                    var value = rawValue.trim()
                    val important = value.endsWith("!important")
                    if (important) value = value.removeSuffix("!important").trim()
                    val previous = found[name]
                    if (previous != null && previous.second && !important) continue
                    found[name] = value to important
                }
            }
        }
    }

    private class Rule(val prelude: String, val body: String?)

    /** Splits a stylesheet (or a block body) into rules, matching braces and skipping strings. */
    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements")
    private fun splitRules(css: String): List<Rule> {
        val rules = ArrayList<Rule>()
        var i = 0
        var start = 0
        while (i < css.length) {
            val c = css[i]
            when {
                c == '"' || c == '\'' -> i = skipString(css, i)
                c == ';' -> {
                    rules.add(Rule(css.substring(start, i), null))
                    start = i + 1
                }
                c == '{' -> {
                    val end = matchBrace(css, i)
                    rules.add(Rule(css.substring(start, i), css.substring(i + 1, end)))
                    i = end
                    start = i + 1
                }
            }
            i++
        }
        return rules
    }

    private fun matchBrace(css: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < css.length) {
            when (css[i]) {
                '"', '\'' -> i = skipString(css, i)
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return css.length - 1
    }

    private fun skipString(css: String, start: Int): Int {
        val quote = css[start]
        var i = start + 1
        while (i < css.length && css[i] != quote) {
            if (css[i] == '\\') i++
            i++
        }
        return i
    }

    /** `name: value` pairs of a declaration block, splitting on `;` outside parentheses and strings. */
    private fun declarations(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var depth = 0
        var i = 0
        var start = 0
        fun flush(end: Int) {
            val decl = body.substring(start, end).trim()
            val colon = decl.indexOf(':')
            if (colon > 0) out.add(decl.substring(0, colon).trim() to decl.substring(colon + 1))
        }
        while (i < body.length) {
            when (body[i]) {
                '"', '\'' -> i = skipString(body, i)
                '(' -> depth++
                ')' -> depth--
                ';' -> if (depth == 0) {
                    flush(i)
                    start = i + 1
                }
            }
            i++
        }
        flush(body.length)
        return out
    }

    private fun stripComments(css: String): String {
        val sb = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            if (css.startsWith("/*", i)) {
                val end = css.indexOf("*/", i + 2)
                i = if (end < 0) css.length else end + 2
                sb.append(' ')
            } else {
                sb.append(css[i])
                i++
            }
        }
        return sb.toString()
    }
}
