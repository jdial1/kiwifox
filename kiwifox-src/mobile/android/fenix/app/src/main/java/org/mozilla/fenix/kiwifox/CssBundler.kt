/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

/**
 * Builds the stylesheet desktop Firefox actually loads for a userChrome.css theme: the entry file
 * with every relative `@import` inlined where it appears, recursively. Files the entry never imports
 * (optional palettes, integrations, userContent.css) are left out, as desktop leaves them out; reading
 * every `.css` in a repository let an unused palette override the real colors (Cascade's
 * `integrations/` folder turned its light mode dark).
 *
 * `@import "x.css" <media>;` is inlined as `@media <media> { ... }` and `@import "x.css" supports(...)`
 * as `@supports ... { ... }`, so the extractors' condition handling (prefers-color-scheme, -moz-pref)
 * applies. Remote and `chrome://` imports are skipped.
 */
object CssBundler {
    private val IMPORT = Regex(
        """@import\s+(?:url\(\s*)?["']?([^"')\s;]+)["']?\s*\)?\s*([^;]*);""",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_DEPTH = 16

    /** @param read returns a file's text by repository-relative path, or null when it does not exist. */
    fun bundle(entry: String, read: (String) -> String?): String = inline(normalize(entry), read, HashSet(), 0) ?: ""

    /** The userChrome.css desktop would use: the shallowest one, preferring `chrome/userChrome.css`. */
    fun findEntry(paths: Collection<String>): String? =
        paths.filter { it.substringAfterLast('/').equals("userChrome.css", ignoreCase = true) }
            .minWithOrNull(compareBy({ !it.contains("chrome/", ignoreCase = true) }, { it.count { c -> c == '/' } }, { it }))

    private fun inline(path: String, read: (String) -> String?, seen: MutableSet<String>, depth: Int): String? {
        if (depth > MAX_DEPTH || !seen.add(path)) return ""
        val text = read(path) ?: return null
        val dir = path.substringBeforeLast('/', "")
        val out = StringBuilder("\n/* $path */\n")
        var last = 0
        for (m in IMPORT.findAll(stripComments(text))) {
            out.append(text, last, m.range.first)
            last = m.range.last + 1
            val target = m.groupValues[1]
            if (target.contains("://") || target.startsWith("//")) continue
            val child = inline(normalize(if (dir.isEmpty()) target else "$dir/$target"), read, seen, depth + 1) ?: continue
            val condition = m.groupValues[2].trim()
            out.append(
                when {
                    condition.isEmpty() -> child
                    condition.startsWith("supports(", ignoreCase = true) ->
                        "@supports ${condition.removePrefix("supports").trim()} {$child}"
                    condition.startsWith("layer", ignoreCase = true) -> child
                    else -> "@media $condition {$child}"
                },
            )
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    /** Comments are blanked with spaces so match positions still line up with the original text. */
    private fun stripComments(css: String): String {
        val sb = StringBuilder(css)
        var i = css.indexOf("/*")
        while (i >= 0) {
            val end = css.indexOf("*/", i + 2).let { if (it < 0) css.length else it + 2 }
            for (k in i until end) if (sb[k] != '\n') sb.setCharAt(k, ' ')
            i = css.indexOf("/*", end)
        }
        return sb.toString()
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.replace('\\', '/').split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }
}
