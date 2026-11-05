/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Runs the CSS extractor and converter over local clones of the catalogue repositories.
 * Set KIWIFOX_CATALOG_DIR to a directory of cloned repos; the test prints one line per repo
 * and is skipped when the variable is unset. Files are read in the same order as
 * [ThemeImporter] reads them from GitHub (userChrome.css first, then by path, 40 at most).
 */
@RunWith(RobolectricTestRunner::class)
class CssThemeExtractorCatalogTest {

    @Test
    fun `catalogue repositories convert`() {
        val dir = System.getenv("KIWIFOX_CATALOG_DIR") ?: return
        val repos = File(dir).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        var ok = 0
        for (repo in repos) {
            val files = repo.walkTopDown()
                .filter { it.isFile && it.extension == "css" && !it.path.contains("${File.separator}.git${File.separator}") }
                .sortedBy { it.relativeTo(repo).path.replace('\\', '/') }
                .sortedBy { if (it.name == "userChrome.css") 0 else 1 }
                .take(40)
                .toList()
            val css = StringBuilder()
            for (f in files) {
                css.append("\n/* ${f.relativeTo(repo).path} */\n").append(f.readText())
                if (css.length > 2 * 1024 * 1024) break
            }
            val line = runCatching {
                val (manifest, notes) = CssThemeExtractor.toManifest(css.toString(), repo.name)
                val converted = ThemeConverter.convert(manifest, repo.name)
                ok++
                val light = converted.theme.light
                val dark = converted.theme.dark
                "OK light bar=${hex(light["surface"])} field=${hex(light["surfaceContainerHighest"])} text=${hex(light["onSurface"])}" +
                    " | dark bar=${hex(dark["surface"])} field=${hex(dark["surfaceContainerHighest"])} text=${hex(dark["onSurface"])}" +
                    " | notes: ${(notes + converted.notes).joinToString(" / ")}"
            }.getOrElse { "FAIL ${it.message}" }
            println("CATALOG ${repo.name} (${files.size} css): $line")
        }
        println("CATALOG SUMMARY: $ok of ${repos.size} convert")
        assertTrue("no repositories found in $dir", repos.isNotEmpty())
    }

    @Test
    fun `platform and selector rules`() {
        val css = """
            @media (-moz-platform: windows) { :root:is(:not([lwtheme]), :not(:-moz-lwtheme)) { --toolbar-field-color: #112233 !important; } }
            @media (-moz-platform: macos) { :root { --toolbar-field-color: #ff0000 !important; } }
            @media (-moz-gtk-csd-available) { :root { --toolbar-field-background-color: Field !important; } }
            :root { --toolbar-bgcolor: light-dark(#ffffff, #000000); --toolbar-field-background-color: var(--missing, #eeeeee); }
        """.trimIndent()
        val (manifest, _) = CssThemeExtractor.toManifest(css, "t")
        val colors = manifest.getJSONObject("theme").getJSONObject("colors")
        assertEquals("#112233", colors.getString("toolbar_field_text"))
        assertEquals("#ffffff", colors.getString("frame"))
        assertEquals("#eeeeee", colors.getString("toolbar_field"))
        val dark = manifest.getJSONObject("dark_theme").getJSONObject("colors")
        assertEquals("#000000", dark.getString("frame"))
    }

    @Test
    fun `unreadable text is corrected, not refused`() {
        val css = ":root { --toolbar-bgcolor: #ffffff; --toolbar-field-background-color: #ffffff; --toolbar-field-color: #fbfbfe; }"
        val (manifest, _) = CssThemeExtractor.toManifest(css, "t")
        val converted = ThemeConverter.convert(manifest, "t")
        assertEquals(0xFF000000.toInt(), converted.theme.light["onSurface"])
        assertTrue(converted.notes.any { it.contains("replaced") })
    }

    private fun hex(argb: Int?): String = argb?.let { KiwiTheme.toHex(it) } ?: "-"
}
