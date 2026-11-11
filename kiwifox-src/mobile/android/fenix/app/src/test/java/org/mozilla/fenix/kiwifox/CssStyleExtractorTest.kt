/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Layout extraction from userChrome.css + user.js, and the style JSON round trip. */
@RunWith(RobolectricTestRunner::class)
class CssStyleExtractorTest {

    @Test
    fun `stock-looking css changes nothing`() {
        val css = ":root { --toolbar-bgcolor: #ffffff; } #urlbar[focused] #urlbar-background { border-radius: 0 !important; }"
        assertTrue(CssStyleExtractor.extract(css).style.isEmpty)
    }

    @Test
    fun `urlbar radius keeps its proportion to the bar height`() {
        // 8px on a 32px desktop bar = a quarter of the height -> 12 dp on the 48 dp field.
        val style = CssStyleExtractor.extract("#urlbar-background { border-radius: 8px !important; }").style
        assertEquals(12f, style.fieldRadius)
        assertNull(style.fieldHeight)
    }

    @Test
    fun `square and pill urlbars`() {
        assertEquals(0f, CssStyleExtractor.extract("#urlbar-background { border-radius: 0 }").style.fieldRadius)
        assertEquals(
            KiwiStyleSpec.PILL,
            CssStyleExtractor.extract(":root { --urlbar-border-radius: calc(var(--urlbar-height) / 2); }").style.fieldRadius,
        )
        assertEquals(KiwiStyleSpec.PILL, CssStyleExtractor.extract("#urlbar-background { border-radius: 50% }").style.fieldRadius)
    }

    @Test
    fun `urlbar height and radius through variables`() {
        val css = """
            :root { --my-h: 28px; --urlbar-height: var(--my-h); --urlbar-border-radius: var(--border-radius-small); --border-radius-small: 2px; }
        """.trimIndent()
        val style = CssStyleExtractor.extract(css).style
        assertEquals(42f, style.fieldHeight) // 48 * 28 / 32
        assertEquals(3f, style.fieldRadius) // 2px of 28px -> 3 dp of 42 dp
        assertEquals(0.5f, style.cornerScale) // --border-radius-small 2px vs desktop 4px
    }

    @Test
    fun `negations hold, state selectors are skipped`() {
        val css = """
            #urlbar:not([focused]) #urlbar-background { border-radius: 4px; }
            #urlbar:hover #urlbar-background { border-radius: 16px; }
            #urlbar[breakout-extend] #urlbar-background { border-radius: 16px; }
        """.trimIndent()
        assertEquals(6f, CssStyleExtractor.extract(css).style.fieldRadius)
    }

    @Test
    fun `nested rules and the Firefox 159 urlbar-background class`() {
        val css = """
            :root { --my-radius: 8px; }
            #urlbar {
              color: red;
              .urlbar-background { border-radius: var(--my-radius) !important; }
              &[focused] .urlbar-background { border-radius: 0 !important; }
            }
            .urlbar-input-container { border-radius: 0 !important; }
        """.trimIndent()
        // The visible box (.urlbar-background, nested) wins over the inner container; the focused rule is skipped.
        assertEquals(12f, CssStyleExtractor.extract(css).style.fieldRadius)
    }

    @Test
    fun `values equal to desktop stock are not emitted`() {
        val css = "#urlbar { min-height: 32px; } #urlbar-background { border: 0 } .tab-background { border-radius: 4px }"
        assertTrue(CssStyleExtractor.extract(css).style.isEmpty)
    }

    @Test
    fun `compact density and devPixelsPerPx come from user js`() {
        val userJs = """
            // comment: user_pref("layout.css.devPixelsPerPx", "3");
            user_pref("browser.uidensity", 1);
            user_pref("layout.css.devPixelsPerPx", "0.9");
        """.trimIndent()
        val css = ":root[uidensity=compact] #urlbar-background { border-radius: 14px; } #urlbar-background { border-radius: 2px; }"
        val style = CssStyleExtractor.extract(css, userJs).style
        assertEquals(42f, style.fieldHeight)
        assertEquals(KiwiStyleSpec.PILL, style.fieldRadius) // the compact rule wins and is >= half of 28px
        assertEquals(0.9f, style.uiScale)
    }

    @Test
    fun `fonts map to Android families`() {
        assertEquals("monospace", CssStyleExtractor.extract(":root { font-family: 'JetBrains Mono', monospace !important; }").style.fontFamily)
        assertEquals("serif", CssStyleExtractor.extract("* { font-family: Georgia, serif; }").style.fontFamily)
        assertNull(CssStyleExtractor.extract("* { font-family: Inter, sans-serif; }").style.fontFamily)
        assertEquals("monospace", CssStyleExtractor.extract("#urlbar-input { font: 13px 'Fira Code', monospace; }").style.fontFamily)
        val sized = CssStyleExtractor.extract(":root { font-size: 15px; }").style
        assertEquals(1.25f, sized.textScale)
        // A font only in a menu rule does not decide the app font.
        assertNull(CssStyleExtractor.extract("menupopup { font-family: monospace; }").style.fontFamily)
    }

    @Test
    fun `corner scale follows tab and panel radii`() {
        assertEquals(2f, CssStyleExtractor.extract(".tab-background { border-radius: 8px !important; }").style.cornerScale)
        assertEquals(0f, CssStyleExtractor.extract(":root { --arrowpanel-border-radius: 0px; }").style.cornerScale)
    }

    @Test
    fun `desktop field border colors become a border`() {
        val manifest = JSONObject(
            """{"name":"b","theme":{"colors":{"frame":"#202020","toolbar_field":"#303030","toolbar_field_text":"#ffffff",
               "toolbar_field_border":"#ff0000","toolbar_field_border_focus":"#00ff00"}}}""",
        )
        val converted = ThemeConverter.convert(manifest, "b")
        assertEquals(0xFFFF0000.toInt(), converted.theme.light["fieldBorder"])
        assertEquals(0xFF00FF00.toInt(), converted.theme.light["fieldBorderFocus"])
        assertEquals(1f, converted.theme.style.fieldBorderWidth)
        assertTrue(converted.notes.none { it.contains("toolbar_field_border") })
    }

    @Test
    fun `style survives the theme JSON round trip and is clamped`() {
        val theme = KiwiTheme(
            id = "x", name = "x", source = "x", light = mapOf("surface" to 0xFF101010.toInt(), "fieldBorder" to 0xFFFF0000.toInt()),
            dark = emptyMap(), builtIn = false,
            style = KiwiStyleSpec(fieldRadius = 99f, fieldHeight = 10f, cornerScale = 0.5f, fontFamily = "monospace", uiScale = 2f),
        )
        val back = KiwiTheme.fromJson("x", theme.toJson(), builtIn = false, fallbackName = "x")
        assertEquals(KiwiStyleSpec.MAX_FIELD_RADIUS, back.style.fieldRadius)
        assertEquals(KiwiStyleSpec.MIN_FIELD_HEIGHT, back.style.fieldHeight)
        assertEquals(0.5f, back.style.cornerScale)
        assertEquals("monospace", back.style.fontFamily)
        assertEquals(KiwiStyleSpec.MAX_UI_SCALE, back.style.uiScale)
        assertEquals(0xFFFF0000.toInt(), back.light["fieldBorder"])
        assertEquals(KiwiStyleSpec(fontFamily = "serif", textScale = 1.1f), KiwiStyleSpec(fontFamily = "monospace").overlay(KiwiStyleSpec(fontFamily = "serif", textScale = 1.1f)))
    }

    /** Prints what each catalogue theme's layout becomes on Android (KIWIFOX_CATALOG_DIR, as in the colors test). */
    @Test
    fun `catalogue layouts`() {
        val dir = System.getenv("KIWIFOX_CATALOG_DIR") ?: return
        val repos = File(dir).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        for (repo in repos) {
            val notGit = { f: File -> !f.path.contains("${File.separator}.git${File.separator}") }
            val files = repo.walkTopDown()
                .filter { it.isFile && it.extension == "css" && notGit(it) }
                .sortedBy { it.relativeTo(repo).path.replace('\\', '/') }
                .sortedBy { if (it.name == "userChrome.css") 0 else 1 }
                .take(40)
                .toList()
            val css = StringBuilder()
            for (f in files) {
                css.append("\n/* ${f.relativeTo(repo).path} */\n").append(f.readText())
                if (css.length > 2 * 1024 * 1024) break
            }
            val userJs = repo.walkTopDown().filter { it.isFile && it.name == "user.js" && notGit(it) }
                .minByOrNull { it.relativeTo(repo).path.count { c -> c == File.separatorChar } }?.readText()
            val line = runCatching {
                val result = CssStyleExtractor.extract(css.toString(), userJs)
                "${result.style.toJson()} | ${result.notes.joinToString(" / ")}"
            }.getOrElse { "FAIL $it" }
            println("STYLE ${repo.name} (user.js=${userJs != null}): $line")
        }
    }
}
