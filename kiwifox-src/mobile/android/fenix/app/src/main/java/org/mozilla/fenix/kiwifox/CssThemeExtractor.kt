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
 * `@media (prefers-color-scheme)`. The browser state it assumes is desktop Firefox on Windows
 * with the default theme and no special prefs: `-moz-platform: windows` queries are true, GTK
 * and macOS queries false, `:root:not([lwtheme])`-style selectors match. Variables the theme
 * does not set, or sets to something that cannot be evaluated, fall back to Firefox's own
 * default (Proton) values in [FIREFOX_DEFAULTS], as desktop does for an invalid declaration;
 * CSS system color keywords map to those defaults too. Roles that end up on defaults are
 * reported in the notes.
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
    private val LITERAL = Regex("^(#[0-9a-fA-F]{3,8}|rgba?\\([^()]*\\)|hsla?\\([^()]*\\)|[a-zA-Z-]+)$")
    private val NOT_A_COLOR = setOf("inherit", "initial", "unset", "revert", "revert-layer", "currentcolor", "none", "auto")

    /** `:root`, optionally followed by negations only: `:not(...)` or `:is(:not(...), :not(...))`. */
    private val ROOT_SELECTOR = Regex("^(:root|\\*|html|body)(:not\\([^()]*\\)|:is\\((\\s*:not\\([^()]*\\)\\s*,?)+\\s*\\))*$")
    private val LIGHT_DARK = Regex("^light-dark\\(\\s*(.+?)\\s*,\\s*(.+)\\)$")
    private val VAR = Regex("^var\\(\\s*(--[\\w-]+)\\s*(?:,\\s*(.+))?\\)$")
    private val SCHEME_QUERY = Regex("prefers-color-scheme\\s*:\\s*(light|dark)")
    private val PLATFORM_QUERY = Regex("-moz-platform\\s*:\\s*([a-z]+)")
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
        "--toolbar-field-text-color" to "light-dark(#15141a, #fbfbfe)",
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
        "-moz-accent-color" to ("#0060df" to "#00ddff"),
        "-moz-accent-color-foreground" to ("#ffffff" to "#15141a"),
        "linktext" to ("#0060df" to "#00ddff"),
        "threedshadow" to ("#a0a0a0" to "#5b5b66"),
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
            var found: String? = null
            var fromDefault = false
            for (name in names) {
                // The theme on top of Firefox's defaults, as on desktop.
                found = tryResolve(name, withDefaults, scheme)
                if (found != null) {
                    fromDefault = tryResolve(name, themeVariables, scheme) == null
                    break
                }
                // The theme sets it to something unevaluable: desktop drops the declaration and uses its default.
                val default = FIREFOX_DEFAULTS[name]
                if (default != null) {
                    found = tryResolveValue(default, withDefaults, scheme)
                    fromDefault = true
                    if (found != null) break
                }
            }
            if (fromDefault) defaulted.add(key)
            out[key] = systemColor(found ?: throw Unresolved("$scheme: no value for $key via $names"), scheme)
        }
        if (defaulted.size == ROLES.size) {
            notes.add("this theme changes layout only: all toolbar colors are Firefox's defaults")
        } else if (defaulted.isNotEmpty()) {
            notes.add("Firefox default used for: ${defaulted.joinToString(", ")} (the theme does not set them)")
        }
        return out
    }

    private fun tryResolve(name: String, variables: Map<String, String>, scheme: String): String? =
        runCatching { resolve(name, variables, scheme, 0) }.getOrNull()

    private fun tryResolveValue(value: String, variables: Map<String, String>, scheme: String): String? =
        runCatching { resolveValue(value, variables, scheme, 0) }.getOrNull()

    /** Replaces a CSS system color keyword with the value Firefox's default theme gives it. */
    private fun systemColor(value: String, scheme: String): String {
        val mapped = SYSTEM_COLORS[value.lowercase()] ?: return value
        return if (scheme == "light") mapped.first else mapped.second
    }

    private fun resolve(name: String, variables: Map<String, String>, scheme: String, depth: Int): String {
        if (depth > MAX_DEPTH) throw Unresolved(name)
        val value = variables[name] ?: throw Unresolved(name)
        return resolveValue(value, variables, scheme, depth)
    }

    private fun resolveValue(raw: String, variables: Map<String, String>, scheme: String, depth: Int): String {
        if (depth > MAX_DEPTH) throw Unresolved(raw)
        var value = raw.trim()
        LIGHT_DARK.matchEntire(value)?.let { m -> value = m.groupValues[if (scheme == "light") 1 else 2].trim() }
        VAR.matchEntire(value)?.let { m ->
            return try {
                resolve(m.groupValues[1], variables, scheme, depth + 1)
            } catch (e: Unresolved) {
                val fallback = m.groupValues[2].trim()
                if (fallback.isEmpty()) throw e
                resolveValue(fallback, variables, scheme, depth + 1)
            }
        }
        if (!LITERAL.matches(value) || value.lowercase() in NOT_A_COLOR) throw Unresolved("$raw = $value")
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
                    nestedActive = active && (mediaMatches(prelude, scheme) ?: continue)
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

    /**
     * Evaluates a media query for the assumed browser state (desktop Firefox on Windows, default
     * theme). Returns null when the query depends on a user pref and the block must be skipped.
     */
    @Suppress("ReturnCount")
    private fun mediaMatches(prelude: String, scheme: String): Boolean? {
        val q = prelude.lowercase()
        if (q.contains("-moz-pref(")) return null
        SCHEME_QUERY.find(q)?.let { if (it.groupValues[1] != scheme) return false }
        PLATFORM_QUERY.find(q)?.let { if (it.groupValues[1] != "windows") return false }
        if (q.contains("-moz-gtk") || q.contains("-moz-mac") || q.contains("-moz-overlay-scrollbars")) return false
        if (q.contains("forced-colors") && !q.contains("forced-colors: none") && !q.contains("forced-colors:none")) return false
        if (q.contains("prefers-contrast") && !q.contains("no-preference")) return false
        if (q.contains("print")) return false
        return true
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
