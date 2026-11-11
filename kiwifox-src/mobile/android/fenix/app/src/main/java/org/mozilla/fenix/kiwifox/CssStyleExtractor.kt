/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import kotlin.math.roundToInt

/**
 * The layout half of a userChrome.css theme: what it does to corner radius, the URL bar's height
 * and border, the chrome font and its size, plus `browser.uidensity` and
 * `layout.css.devPixelsPerPx` from the theme's `user.js`. Produces a [KiwiStyleSpec].
 *
 * Desktop measures in CSS px on a 32 px URL bar (Firefox 159, Windows, normal density); Android's
 * field is 48 dp. Heights scale by 48/32, radii keep the theme's radius-to-height proportion, so a
 * 4 px radius on a 32 px desktop bar is 6 dp on a 48 dp field. A key is only emitted when the theme
 * changes it from desktop's default; everything else keeps Firefox for Android's own look.
 *
 * Only rules that apply in the default browser state count: selectors with attribute or state
 * conditions (`[focused]`, `:hover`, `[open]` ...) are skipped, `:not(...)` is taken as true, and
 * `:root[uidensity=...]` matches the density from user.js. Media queries follow [CssThemeExtractor].
 */
object CssStyleExtractor {
    class Result(val style: KiwiStyleSpec, val notes: List<String>)

    private const val DESKTOP_URLBAR_PX = 32f
    private const val DESKTOP_COMPACT_URLBAR_PX = 28f
    private const val DESKTOP_FONT_PX = 12f // 9pt Segoe UI, the Windows chrome font
    private const val ANDROID_FIELD_DP = 48f
    private const val PILL_PX = 9999f
    private const val MAX_DEPTH = 12

    /** Firefox 159 desktop defaults for the layout variables read here. */
    private val DESKTOP_DEFAULTS: Map<String, String> = linkedMapOf(
        "--border-radius-small" to "4px",
        "--border-radius-medium" to "8px",
        "--border-radius-large" to "16px",
        "--button-border-radius" to "var(--border-radius-small)",
        "--toolbarbutton-border-radius" to "var(--border-radius-small)",
        "--urlbar-border-radius" to "var(--button-border-radius)",
        "--tab-border-radius" to "var(--toolbarbutton-border-radius)",
        "--arrowpanel-border-radius" to "var(--border-radius-medium)",
        "--panel-border-radius" to "var(--border-radius-medium)",
        "--urlbar-height" to "32px",
        "--urlbar-min-height" to "32px",
    )

    // The visible box first (Firefox 159 class, then the older id), the inner container last.
    private val URLBAR_BOX = listOf(
        ".urlbar-background", "#urlbar-background", "#urlbar", "#urlbar-input-container", ".urlbar-input-container",
    )
    private val URLBAR_TEXT = listOf("#urlbar-input", ".urlbar-input", "#urlbar-input-box")
    private val CHROME_ROOT = listOf(
        ":root", "*", "html", "window", "#main-window", "#navigator-toolbox", "toolbox", "toolbar", "#nav-bar",
        "#tabbrowser-tabs", ".tabbrowser-tab", ".tab-label",
    )
    private val TAB_BOX = listOf(".tab-background")
    private val PANEL_BOX = listOf("menupopup", "panel", ".panel-arrowcontent", ".menupopup-arrowscrollbox")
    private val TARGETS = (URLBAR_BOX + URLBAR_TEXT + CHROME_ROOT + TAB_BOX + PANEL_BOX).toSet()

    private val PREF = Regex("""user_pref\(\s*"([^"]+)"\s*,\s*"?([^")]*?)"?\s*\)""")
    private val NUMBER_UNIT = Regex("""^(-?\d*\.?\d+)(px|pt|em|rem|%)?$""", RegexOption.IGNORE_CASE)

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun extract(css: String, userJs: String? = null): Result {
        val notes = ArrayList<String>()
        val prefs = userJs?.let(::parsePrefs) ?: emptyMap()
        val density = when (prefs["browser.uidensity"]?.trim()) {
            "1" -> "compact"
            "2" -> "touch"
            else -> "normal"
        }

        val themeVars = CssThemeExtractor.collect(css, "light")
        val withDefaults = LinkedHashMap(DESKTOP_DEFAULTS).apply { putAll(themeVars) }
        val rules = collectRules(css, density)

        fun prop(targets: List<String>, vararg names: String): Pair<String, String>? {
            for (t in targets) {
                val decls = rules[t] ?: continue
                for (n in names) decls[n]?.let { return t to it }
            }
            return null
        }
        fun length(raw: String) = evalLength(raw, withDefaults)
        fun changedVar(name: String): Float? {
            val themed = evalLength("var($name)", withDefaults) ?: return null
            val stock = evalLength("var($name)", DESKTOP_DEFAULTS)
            return if (stock == null || kotlin.math.abs(themed - stock) > 0.01f) themed else null
        }

        // ---- URL bar height -------------------------------------------------------------------
        val stockHeight = if (density == "compact") DESKTOP_COMPACT_URLBAR_PX else DESKTOP_URLBAR_PX
        var urlbarPx = stockHeight
        var heightChanged = density == "compact"
        val heightRule = prop(listOf("#urlbar", "#urlbar-input-container", ".urlbar-input-container"), "height", "min-height")
        val heightFromRule = heightRule?.let { length(it.second) }
            ?.takeIf { it > 0f && it < PILL_PX && kotlin.math.abs(it - stockHeight) > 0.5f }
        val heightFromVar = changedVar("--urlbar-height") ?: changedVar("--urlbar-min-height")
        (heightFromRule ?: heightFromVar)?.let {
            urlbarPx = it
            heightChanged = true
            notes.add("URL bar height ${fmt(it)}px from " + (heightRule?.let { r -> "${r.first} ${r.second.trim()}" } ?: "--urlbar-height"))
        }
        if (density == "compact" && heightFromRule == null && heightFromVar == null) {
            notes.add("user.js sets compact density: URL bar ${fmt(DESKTOP_COMPACT_URLBAR_PX)}px")
        }
        val fieldHeightDp = if (heightChanged) {
            val dp = ANDROID_FIELD_DP * urlbarPx / DESKTOP_URLBAR_PX
            val clamped = dp.coerceIn(KiwiStyleSpec.MIN_FIELD_HEIGHT, KiwiStyleSpec.MAX_FIELD_HEIGHT)
            if (clamped != dp) notes.add("address bar height ${fmt(dp)} dp limited to ${fmt(clamped)} dp (the Android toolbar slot is 48 dp)")
            // 48 dp is stock Firefox for Android: a taller desktop bar changes nothing here.
            clamped.roundToInt().toFloat().takeIf { it != ANDROID_FIELD_DP }
        } else {
            null
        }

        // ---- URL bar radius -------------------------------------------------------------------
        val radiusRule = prop(URLBAR_BOX, "border-radius")
        val radiusPx = radiusRule?.let { length(firstValue(it.second)) } ?: changedVar("--urlbar-border-radius")
        val fieldRadius = radiusPx?.let { r ->
            notes.add("URL bar radius ${if (r >= PILL_PX) "50%" else fmt(r) + "px"} from " +
                (radiusRule?.first ?: "--urlbar-border-radius"))
            if (r * 2 >= urlbarPx) {
                KiwiStyleSpec.PILL
            } else {
                val heightDp = fieldHeightDp ?: ANDROID_FIELD_DP
                (r * heightDp / urlbarPx).roundToInt().toFloat()
            }
        }

        // ---- URL bar border -------------------------------------------------------------------
        val borderWidth = prop(listOf(".urlbar-background", "#urlbar-background", "#urlbar"), "border-width", "border")?.let { (target, value) ->
            val v = value.replace("!important", "").trim().lowercase()
            val width = if (v == "none" || v == "0") 0f else CssThemeExtractor.splitTopLevel(v, ' ')
                .firstNotNullOfOrNull { token -> length(token)?.takeIf { token.trim().isNotEmpty() && it < PILL_PX } }
            // Desktop's own field border is 1px and transparent: only a visible width is a change.
            width?.takeIf { it > 0f }?.also { notes.add("URL bar border ${fmt(it)}px from $target") }
        }

        // ---- corner radius of everything else -------------------------------------------------
        val cornerScale = cornerScale(rules, ::length, ::changedVar, notes)

        // ---- font -----------------------------------------------------------------------------
        var fontFamily: String? = null
        var fontPx: Float? = null
        for (targets in listOf(CHROME_ROOT, URLBAR_TEXT)) {
            val familyDecl = prop(targets, "font-family")?.let { it.first to it.second }
            val shorthand = prop(targets, "font")?.let { it.first to parseFontShorthand(resolveVars(it.second, withDefaults)) }
            val familyList = familyDecl?.let { resolveVars(it.second, withDefaults) } ?: shorthand?.second?.second
            if (fontFamily == null && familyList != null) {
                val where = familyDecl?.first ?: shorthand!!.first
                val (family, note) = mapFontFamily(familyList)
                notes.add("font ${familyList.trim()} from $where: ${note}")
                fontFamily = family
            }
            if (targets === CHROME_ROOT && fontPx == null) {
                fontPx = prop(targets, "font-size")?.let { length(firstValue(it.second)) } ?: shorthand?.second?.first
            }
        }
        val textScale = fontPx?.takeIf { it > 0f && it < PILL_PX }?.let { px ->
            val scale = px / DESKTOP_FONT_PX
            if (kotlin.math.abs(scale - 1f) < 0.01f) {
                null
            } else {
                notes.add("chrome font size ${fmt(px)}px (desktop default ${fmt(DESKTOP_FONT_PX)}px): text ×${fmt(scale)}")
                scale
            }
        }

        // ---- interface scale ------------------------------------------------------------------
        val uiScale = prefs["layout.css.devPixelsPerPx"]?.trim()?.toFloatOrNull()?.takeIf { it > 0f && it != 1f }?.also {
            notes.add("user.js layout.css.devPixelsPerPx = ${fmt(it)}: interface scale ×${fmt(it)}")
        }

        val spec = KiwiStyleSpec(
            fieldRadius = fieldRadius,
            fieldHeight = fieldHeightDp,
            fieldBorderWidth = borderWidth,
            cornerScale = cornerScale,
            fontFamily = fontFamily,
            textScale = textScale,
            uiScale = uiScale,
        )
        val clamped = spec.clamped()
        if (clamped != spec) notes.add("some values were outside what Android can show and were limited")
        return Result(clamped, notes)
    }

    private fun cornerScale(
        rules: Map<String, Map<String, String>>,
        length: (String) -> Float?,
        changedVar: (String) -> Float?,
        notes: MutableList<String>,
    ): Float? {
        // (where, theme value, desktop default)
        val candidates = sequence {
            changedVar("--tab-border-radius")?.let { yield(Triple("--tab-border-radius", it, 4f)) }
            rules[".tab-background"]?.get("border-radius")?.let { v -> length(firstValue(v))?.let { yield(Triple(".tab-background", it, 4f)) } }
            changedVar("--toolbarbutton-border-radius")?.let { yield(Triple("--toolbarbutton-border-radius", it, 4f)) }
            changedVar("--arrowpanel-border-radius")?.let { yield(Triple("--arrowpanel-border-radius", it, 8f)) }
            changedVar("--panel-border-radius")?.let { yield(Triple("--panel-border-radius", it, 8f)) }
            for (t in PANEL_BOX) {
                rules[t]?.get("border-radius")?.let { v -> length(firstValue(v))?.let { yield(Triple(t, it, 8f)) } }
            }
            changedVar("--border-radius-medium")?.let { yield(Triple("--border-radius-medium", it, 8f)) }
            changedVar("--border-radius-small")?.let { yield(Triple("--border-radius-small", it, 4f)) }
        }
        val (where, value, stock) = candidates.firstOrNull() ?: return null
        val scale = if (value >= PILL_PX) KiwiStyleSpec.MAX_CORNER_SCALE else value / stock
        if (kotlin.math.abs(scale - 1f) < 0.01f) return null
        notes.add("corner radius ${if (value >= PILL_PX) "50%" else fmt(value) + "px"} from $where (desktop ${fmt(stock)}px): corners ×${fmt(scale)}")
        return scale
    }

    // ---- rules --------------------------------------------------------------------------------

    /** A declaration's value and what it needs to win the cascade. */
    private class Decl(val value: String, val important: Boolean, val specificity: Int)

    /** Declarations per target selector after the cascade: `!important`, then specificity, then later wins. */
    private fun collectRules(css: String, density: String): Map<String, Map<String, String>> {
        val found = HashMap<String, LinkedHashMap<String, Decl>>()
        walk(CssThemeExtractor.stripComments(css), density, found, emptyList())
        return found.mapValues { (_, decls) -> decls.mapValues { it.value.value } }
    }

    /** Selector specificity (ids, classes/attributes/pseudo-classes, types) packed into one comparable int. */
    internal fun specificity(selector: String): Int {
        val noPseudoElements = selector.replace(Regex("""::[\w-]+"""), "")
        val ids = Regex("""#[\w-]+""").findAll(noPseudoElements).count()
        val classes = Regex("""\.[\w-]+|\[[^\]]*\]|:(?!not\b|is\b|where\b)[\w-]+""").findAll(noPseudoElements).count()
        val types = noPseudoElements.split(Regex("""[\s>+~]+""")).count { it.isNotEmpty() && it[0].isLetter() }
        return ids * 10_000 + classes * 100 + types
    }

    /**
     * Walks a stylesheet or a nested block. [parents] are the enclosing style rule's selectors for
     * CSS nesting: `&` is replaced by each parent, other nested selectors are descendants of it.
     */
    private fun walk(
        css: String,
        density: String,
        found: MutableMap<String, LinkedHashMap<String, Decl>>,
        parents: List<String>,
    ) {
        for (rule in CssThemeExtractor.splitRules(css)) {
            val prelude = rule.prelude.trim()
            val body = rule.body ?: continue
            if (prelude.startsWith("@")) {
                val lower = prelude.lowercase()
                if (lower.contains("-moz-pref") || lower.contains("-moz-bool-pref")) continue
                if (lower.startsWith("@media") && CssThemeExtractor.mediaMatches(prelude, "light") != true) continue
                if (lower.startsWith("@-moz-document") || lower.startsWith("@keyframes") || lower.startsWith("@font-face")) continue
                if (parents.isEmpty()) walk(body, density, found, parents) else styleRule(parents, body, density, found)
                continue
            }
            val own = CssThemeExtractor.splitTopLevel(prelude, ',').map { it.trim() }.filter { it.isNotEmpty() }
            val selectors = if (parents.isEmpty()) {
                own
            } else {
                parents.flatMap { parent -> own.map { if ('&' in it) it.replace("&", parent) else "$parent $it" } }
            }
            styleRule(selectors, body, density, found)
        }
    }

    /** Applies a style rule's declarations to its targets, then walks any nested rules. */
    private fun styleRule(
        selectors: List<String>,
        body: String,
        density: String,
        found: MutableMap<String, LinkedHashMap<String, Decl>>,
    ) {
        val declarations: List<Pair<String, String>>
        if ('{' in body) {
            // Nested rules: declarations are the pieces without a block, nested rules are walked.
            val pieces = CssThemeExtractor.splitRules(body + ";")
            declarations = pieces.filter { it.body == null }.flatMap { CssThemeExtractor.declarations(it.prelude) }
            val nested = pieces.filter { it.body != null }
                .joinToString("\n") { "${it.prelude} {${it.body}}" }
            walk(nested, density, found, selectors)
        } else {
            declarations = CssThemeExtractor.declarations(body)
        }
        val targets = selectors.mapNotNull { sel -> targetOf(sel, density)?.let { it to specificity(sel) } }
        if (targets.isEmpty()) return
        for ((rawName, rawValue) in declarations) {
            val name = rawName.trim().lowercase()
            if (!name.matches(Regex("[a-z-]+"))) continue
            var value = rawValue.trim()
            val important = value.endsWith("!important")
            if (important) value = value.removeSuffix("!important").trim()
            for ((t, spec) in targets) {
                val decls = found.getOrPut(t) { LinkedHashMap() }
                val previous = decls[name]
                if (previous != null) {
                    if (previous.important && !important) continue
                    if (previous.important == important && previous.specificity > spec) continue
                }
                decls[name] = Decl(value, important, spec)
            }
        }
    }

    /** The element a selector styles, if it is one we read and the selector holds in the default state. */
    @Suppress("ReturnCount")
    internal fun targetOf(selector: String, density: String): String? {
        var s = selector
        // Negations hold in the default state (nothing focused, open or hovered).
        while (true) {
            val next = s.replace(Regex(""":not\([^()]*(\([^()]*\))?[^()]*\)"""), "")
            if (next == s) break
            s = next
        }
        // Density attribute on the root: matches only the density user.js selects.
        val densityAttr = Regex(""":root\[uidensity\s*=\s*["']?(\w+)["']?\]""")
        densityAttr.find(s)?.let { m ->
            if (m.groupValues[1] != density) return null
            s = s.replace(m.value, ":root")
        }
        if ('[' in s || '(' in s || "::" in s) return null
        val last = s.trim().split(Regex("""\s*[>+~]\s*|\s+""")).lastOrNull()?.trim() ?: return null
        // Any remaining pseudo-class (:hover, :focus-within, :-moz-...) is a state we do not assume.
        val pseudo = Regex(""":(?!root\b)[\w-]+""")
        if (pseudo.containsMatchIn(s.replace(":root", ""))) return null
        return last.lowercase().takeIf { it in TARGETS }
    }

    // ---- values -------------------------------------------------------------------------------

    private fun firstValue(value: String): String =
        CssThemeExtractor.splitTopLevel(value.replace("!important", "").trim(), ' ').firstOrNull { it.isNotBlank() } ?: value

    /** Replaces every `var(--x, fallback)` with its value, recursively. */
    internal fun resolveVars(value: String, vars: Map<String, String>, depth: Int = 0): String {
        if (depth > MAX_DEPTH) return value
        val start = value.indexOf("var(")
        if (start < 0) return value
        var depthParen = 0
        var end = start + 3
        while (end < value.length) {
            when (value[end]) {
                '(' -> depthParen++
                ')' -> {
                    depthParen--
                    if (depthParen == 0) break
                }
            }
            end++
        }
        if (end >= value.length) return value
        val inner = value.substring(start + 4, end)
        val parts = CssThemeExtractor.splitTopLevel(inner, ',')
        val name = parts[0].trim()
        val fallback = if (parts.size > 1) inner.substring(inner.indexOf(',') + 1).trim() else null
        val replacement = vars[name]?.let { resolveVars(it, vars, depth + 1) } ?: fallback?.let { resolveVars(it, vars, depth + 1) } ?: ""
        return resolveVars(value.substring(0, start) + replacement + value.substring(end + 1), vars, depth + 1)
    }

    /** A CSS length in desktop px, or null when it cannot be evaluated; `50%` and huge radii come back as [PILL_PX]. */
    internal fun evalLength(raw: String, vars: Map<String, String>): Float? {
        val text = resolveVars(raw.replace("!important", ""), vars).trim().lowercase()
        if (text.isEmpty()) return null
        return runCatching { LengthParser(text).parse() }.getOrNull()?.let { if (it >= 999f) PILL_PX else it }
    }

    /** `[style] [weight] size[/line-height] family, family` -> (size px, family list). */
    internal fun parseFontShorthand(value: String): Pair<Float?, String?> {
        val v = value.replace("!important", "").trim()
        val tokens = Regex("""^((?:(?:italic|oblique|normal|bold|bolder|lighter|small-caps|\d{3})\s+)*)(\S+?)(?:/\S+)?\s+(.+)$""").find(v)
            ?: return null to null
        val size = evalLength(tokens.groupValues[2], emptyMap())
        return size to tokens.groupValues[3]
    }

    /** Maps a CSS font-family list to an Android family (null = keep the system font) and a note. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    internal fun mapFontFamily(list: String): Pair<String?, String> {
        val families = CssThemeExtractor.splitTopLevel(list, ',').map { it.trim().trim('"', '\'').trim() }.filter { it.isNotEmpty() }
        val first = families.firstOrNull() ?: return null to "empty"
        for (f in families) {
            val l = f.lowercase()
            val mapped = when {
                l in setOf("system-ui", "-apple-system", "blinkmacsystemfont", "menu", "message-box", "caption",
                    "-moz-default", "sans-serif", "roboto", "segoe ui", "segoe ui variable", "segoe ui variable display",
                    "segoe ui variable text", "inherit", "initial", "unset") -> "keep"
                l == "monospace" || l == "ui-monospace" || listOf("mono", "code", "consol", "courier", "iosevka", "hack",
                    "menlo", "cascadia", "fixedsys", "terminus").any { it in l } -> "monospace"
                l == "serif" || l == "ui-serif" || listOf("georgia", "times", "garamond", "merriweather", "baskerville",
                    "serif", "playfair", "lora", "crimson", "bitter", "palatino", "cambria").any { it in l } -> "serif"
                l == "cursive" || "script" in l || "brush" in l -> "cursive"
                l == "fantasy" || "comic" in l || "casual" in l -> "casual"
                "condensed" in l || "narrow" in l -> "sans-serif-condensed"
                l.startsWith("roboto ") || l.startsWith("segoe ui ") -> when {
                    "thin" in l -> "sans-serif-thin"
                    "light" in l -> "sans-serif-light"
                    "medium" in l || "semibold" in l -> "sans-serif-medium"
                    "black" in l -> "sans-serif-black"
                    else -> "keep"
                }
                else -> null
            }
            when (mapped) {
                null -> continue // a named font Android does not have: desktop would also fall through
                "keep" -> return null to (if (f == first) "system font, unchanged" else "'$first' is not on Android; system font kept")
                else -> return mapped to (if (f == first) "Android $mapped" else "'$first' is not on Android; closest Android font $mapped")
            }
        }
        return null to "'$first' is not on Android; system font kept (import its .ttf under Style to use it)"
    }

    private fun parsePrefs(userJs: String): Map<String, String> =
        PREF.findAll(CssThemeExtractor.stripComments(userJs.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun fmt(v: Float) = if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

    /** Evaluates numbers with units and calc()/min()/max()/clamp() in desktop px. */
    private class LengthParser(private val s: String) {
        private var i = 0

        fun parse(): Float {
            val v = expr()
            skipWs()
            require(i == s.length) { "trailing input" }
            return v
        }

        private fun expr(): Float {
            var v = term()
            while (true) {
                skipWs()
                if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                    val op = s[i++]
                    val r = term()
                    v = if (op == '+') v + r else v - r
                } else {
                    return v
                }
            }
        }

        private fun term(): Float {
            var v = factor()
            while (true) {
                skipWs()
                if (i < s.length && (s[i] == '*' || s[i] == '/')) {
                    val op = s[i++]
                    val r = factor()
                    v = if (op == '*') v * r else v / r
                } else {
                    return v
                }
            }
        }

        private fun factor(): Float {
            skipWs()
            for (fn in listOf("calc", "max", "min", "clamp")) {
                if (s.startsWith("$fn(", i)) {
                    i += fn.length + 1
                    val args = ArrayList<Float>()
                    while (true) {
                        args.add(expr())
                        skipWs()
                        require(i < s.length) { "unclosed" }
                        if (s[i] == ',') {
                            i++
                            continue
                        }
                        require(s[i] == ')') { "expected )" }
                        i++
                        break
                    }
                    return when (fn) {
                        "calc" -> args.single()
                        "max" -> args.max()
                        "min" -> args.min()
                        else -> args[1].coerceIn(args[0], args[2])
                    }
                }
            }
            if (s.startsWith("(", i)) {
                i++
                val v = expr()
                skipWs()
                require(i < s.length && s[i] == ')') { "expected )" }
                i++
                return v
            }
            val m = Regex("""-?\d*\.?\d+(px|pt|em|rem|%)?""", RegexOption.IGNORE_CASE).find(s, i)
            require(m != null && m.range.first == i) { "expected number at $i" }
            i = m.range.last + 1
            val n = NUMBER_UNIT.matchEntire(m.value)!!
            val number = n.groupValues[1].toFloat()
            return when (n.groupValues[2]) {
                "px", "" -> number
                "pt" -> number * 4f / 3f
                "em", "rem" -> number * DESKTOP_FONT_PX
                "%" -> if (number >= 50f) 9999f else number / 100f * DESKTOP_URLBAR_PX // only meaningful for radii
                else -> error("unit")
            }
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
