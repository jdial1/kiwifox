/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * Regenerates the desktop-derived bundled themes (theme/alpenglow, cascade, materialfox) with the
 * app's own converter, so bundled and imported themes follow the same desktop rules. Each theme keeps
 * its `_source` and `style` from the existing file; header images go to theme/<name>/.
 *
 * Runs only with KIWIFOX_BUNDLE_DIR (the repository's theme/ folder), KIWIFOX_FIREFOX_SRC (the Firefox
 * tree, for Alpenglow), KIWIFOX_DESKTOP_ZIPS (themes-desktop/) and KIWIFOX_RASTER_DIR (SVGs
 * pre-rendered by tools/rasterize_svg.py as `<name>.svg.png` at 3 px per CSS px).
 */
@RunWith(RobolectricTestRunner::class)
class BundledThemesGenerator {

    @Test
    fun `regenerate desktop-derived bundled themes`() {
        val out = System.getenv("KIWIFOX_BUNDLE_DIR")?.let(::File) ?: return
        val firefox = File(System.getenv("KIWIFOX_FIREFOX_SRC") ?: return)
        val zips = File(System.getenv("KIWIFOX_DESKTOP_ZIPS") ?: return)
        val raster = File(System.getenv("KIWIFOX_RASTER_DIR") ?: return)
        val rasterize = { path: String, _: ByteArray ->
            File(raster, path.substringAfterLast('/') + ".png").takeIf { it.isFile }?.let { it.readBytes() to RASTER_SCALE }
        }

        val alpenglowDir = File(firefox, "browser/themes/addons/alpenglow")
        val alpenglowFiles = alpenglowDir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }
        write(out, "alpenglow", JSONObject(File(alpenglowDir, "manifest.json").readText()), alpenglowFiles, rasterize)

        for (name in listOf("cascade", "materialfox")) {
            ZipFile(File(zips, "$name.zip")).use { zip ->
                val files = zip.entries().asSequence().filter { !it.isDirectory }
                    .associate { it.name to zip.getInputStream(it).readBytes() }
                write(out, name, JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8)), files, rasterize)
            }
        }
    }

    private fun write(
        out: File,
        name: String,
        manifest: JSONObject,
        files: Map<String, ByteArray>,
        rasterize: (String, ByteArray) -> Pair<ByteArray, Float>?,
    ) {
        val existing = File(out, "$name.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        val converted = ThemeConverter.convert(manifest, name, files, rasterize)
        val keptStyle = KiwiStyleSpec.fromJson(existing?.optJSONObject("style"))
        val theme = converted.theme.copy(source = existing?.optString("_source")?.ifBlank { null } ?: name, style = keptStyle)
        val json = JSONObject(theme.toJson())
        json.remove("name") // bundled themes are named after their file
        File(out, "$name.json").writeText(json.toString(2) + "\n")
        val imageDir = File(out, name)
        imageDir.deleteRecursively()
        if (theme.pendingFiles.isNotEmpty()) {
            imageDir.mkdirs()
            for ((file, bytes) in theme.pendingFiles) File(imageDir, file).writeBytes(bytes)
        }
        println("BUNDLE $name: ${theme.header.mapValues { it.value.layers.size }} layers, notes: ${converted.notes.joinToString(" / ")}")
        assertTrue("$name: no colors", theme.light.isNotEmpty())
    }

    private companion object {
        const val RASTER_SCALE = 3f
    }
}
