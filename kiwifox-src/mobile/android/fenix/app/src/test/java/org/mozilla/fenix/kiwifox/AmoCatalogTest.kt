/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.fetch.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * Every [ThemeCatalog.AMO_ENTRIES] theme imports completely through the app's own importer:
 * no error, no unreadable-text correction, no dropped images, and every palette differs.
 * Needs KIWIFOX_AMO_DIR with `<slug>.xpi` files (tools/amo_survey.py downloads them);
 * skipped when unset.
 */
@RunWith(RobolectricTestRunner::class)
class AmoCatalogTest {
    private val offline = object : Client() {
        override fun fetch(request: Request): Response = throw IOException("offline test")
    }

    @Test
    fun `AMO catalogue themes import completely and look distinct`() {
        val dir = System.getenv("KIWIFOX_AMO_DIR")?.let(::File) ?: return
        val importer = ThemeImporter(offline)
        val palettes = LinkedHashMap<String, List<Int?>>()
        for (entry in ThemeCatalog.AMO_ENTRIES) {
            val xpi = File(dir, "${slug(entry)}.xpi")
            assertTrue("missing ${xpi.name}", xpi.isFile)
            val imported = importer.importFile(xpi.readBytes(), xpi.name)
            val theme = imported.theme
            println("AMO ${entry.name}: light ${hex(theme.light["surface"])}/${hex(theme.light["surfaceContainerHighest"])}/${hex(theme.light["onSurface"])}" +
                " dark ${hex(theme.dark["surface"])} notes: ${imported.notes.joinToString(" / ")}")
            assertTrue("${entry.name}: text was unreadable and replaced", imported.notes.none { it.contains("replaced") })
            assertTrue("${entry.name}: depends on images", imported.notes.none { it.contains("images") })
            for (role in listOf("surface", "surfaceContainerHighest", "onSurface")) {
                assertTrue("${entry.name}: no $role", theme.light[role] != null)
            }
            palettes[entry.name] = listOf(theme.light["surface"], theme.light["surfaceContainerHighest"])
        }
        assertEquals("two catalogue themes share a bar+field palette", palettes.size, palettes.values.toSet().size)
    }

    private fun slug(entry: ThemeCatalog.Entry) = entry.repoUrl.trimEnd('/').substringAfterLast('/')

    private fun hex(argb: Int?) = argb?.let { KiwiTheme.toHex(it) } ?: "-"
}
