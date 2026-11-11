/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Desktop theme keys beyond the toolbar: new tab page, popups, frame, icons, focus, header images. */
@RunWith(RobolectricTestRunner::class)
class DesktopParityTest {
    private fun hex(argb: Int?) = argb?.let { KiwiTheme.toHex(it) }

    @Test
    fun `theme colors for new tab, popups, frame, icons and focus are kept`() {
        val manifest = JSONObject(
            """{"name":"t","theme":{"colors":{
              "frame":"#14367B","toolbar":"#3E66B6","toolbar_field":"#FAFBFF","toolbar_field_text":"#000000",
              "toolbar_field_border_focus":"#00B3F4","toolbar_field_focus":"#FFFFFF","toolbar_field_text_focus":"#111111",
              "ntp_background":"#C9D6F3","ntp_text":"#000000","ntp_card_background":"#FAFBFF",
              "popup":"#FAFBFF","popup_text":"#000000","popup_highlight":"rgba(0,0,0,0.5)","popup_highlight_text":"#FFFFFF",
              "popup_border":"#CCCCCC","icons":"#FFFFFF","icons_attention":"#FFD700"}}}""",
        )
        val converted = ThemeConverter.convert(manifest, "t")
        val l = converted.theme.light
        assertEquals("#C9D6F3", hex(l["ntpBackground"]))
        assertEquals("#000000", hex(l["ntpText"]))
        assertEquals("#FAFBFF", hex(l["ntpCard"]))
        assertEquals("#FAFBFF", hex(l["popup"]))
        assertEquals("#7D7E80", hex(l["popupHighlight"])) // 50% black over #FAFBFF
        assertEquals("#FFFFFF", hex(l["popupHighlightText"]))
        assertEquals("#CCCCCC", hex(l["popupBorder"]))
        assertEquals("#14367B", hex(l["frame"]))
        assertEquals("#FFFFFF", hex(l["icons"]))
        assertEquals("#FFD700", hex(l["iconsAttention"]))
        assertEquals("#00B3F4", hex(l["fieldBorderFocus"]))
        assertEquals("#FFFFFF", hex(l["fieldFocus"]))
        assertEquals("#111111", hex(l["fieldTextFocus"]))
        assertTrue(converted.notes.joinToString(), converted.notes.none { it.startsWith("dropped") })
    }

    @Test
    fun `unset keys take desktop 159 defaults by surface brightness`() {
        val light = ThemeConverter.convert(JSONObject("""{"theme":{"colors":{"frame":"#FAFAFC","toolbar_field_text":"#1E2021"}}}"""), "l").theme.light
        assertEquals("#F7F6FB", hex(light["ntpBackground"]))
        assertEquals("#FFFFFF", hex(light["popup"]))
        assertEquals("#764EDD", hex(light["fieldBorderFocus"]))
        assertNull(light["icons"])
        val dark = ThemeConverter.convert(
            JSONObject("""{"theme":{"colors":{"frame":"#1E2021","toolbar_field":"#1E2021","toolbar_field_text":"#FAFAFC"}}}"""),
            "d",
        ).theme.light
        assertEquals("#131215", hex(dark["ntpBackground"]))
        assertEquals("#171519", hex(dark["popup"]))
        assertEquals("#FBFBFE", hex(dark["popupText"]))
        assertEquals("#B89CFF", hex(dark["fieldBorderFocus"]))
    }

    @Test
    fun `header images, gradients and the translucent toolbar are kept`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val manifest = JSONObject(
            """{"theme":{"colors":{"frame":"#ffc999","toolbar":"hsla(0, 0%, 100%, .76)"},
              "images":{"theme_frame":"header.png","additional_backgrounds":["noodle.svg",{"linear-gradient":"to bottom, #FF6BBA -18%, #FFC999 50%"}]},
              "properties":{"additional_backgrounds_alignment":["left top","right top"],
                            "additional_backgrounds_tiling":["no-repeat","repeat-x"],
                            "additional_backgrounds_size":["auto","auto 144px"]}}}""",
        )
        val files = mapOf("header.png" to png, "noodle.svg" to "<svg/>".toByteArray())
        val converted = ThemeConverter.convert(manifest, "h", files)
        val spec = converted.theme.header.getValue("light")
        assertEquals(0xC1FFFFFFL, spec.toolbar)
        assertEquals(2, spec.layers.size) // the SVG is dropped without a rasterizer
        assertEquals("right top", spec.layers[0].alignment)
        assertEquals("light-0-header.png", spec.layers[0].file)
        assertEquals("repeat-x", spec.layers[1].tiling)
        assertEquals("auto 144px", spec.layers[1].size)
        assertTrue(converted.notes.any { it.contains("SVG") })
        assertTrue(converted.theme.pendingFiles.containsKey("light-0-header.png"))
        assertEquals(spec, converted.theme.header.getValue("dark")) // no dark block: same header

        // With a rasterizer (the bundled-theme generator), the SVG is kept as a PNG at its scale.
        val rasterized = ThemeConverter.convert(manifest, "h", files) { _, _ -> png to 3f }.theme.header.getValue("light")
        assertEquals(3, rasterized.layers.size)
        assertEquals(3f, rasterized.layers[1].pxPerCss)

        // Round trip through the theme JSON.
        val back = KiwiTheme.fromJson("x", converted.theme.toJson(), builtIn = false, fallbackName = "x")
        assertEquals(converted.theme.header, back.header)
    }

    @Test
    fun `css gradients and sizes parse like desktop`() {
        val g = KiwiHeaderLoader.parseGradient("to bottom, #FF6BBA -18.096%, #FFC999 50%", "right top", "repeat-x", null, 144f)!!
        assertEquals(0f, g.dx)
        assertEquals(1f, g.dy)
        assertEquals(-0.18096f, g.stops[0].first, 1e-4f)
        assertEquals(0.5f, g.stops[1].first, 1e-4f)
        val angled = KiwiHeaderLoader.parseGradient("90deg, red, blue, green", "left top", "no-repeat", null, null)!!
        assertEquals(1f, angled.dx, 1e-4f)
        assertEquals(0.5f, angled.stops[1].first, 1e-4f) // unpositioned middle stop is spread evenly
        assertEquals(null to 144f, KiwiHeaderLoader.parseSize("auto 144px"))
        assertEquals(120f to 60f, KiwiHeaderLoader.parseSize("120px 60px"))
        assertEquals(null to null, KiwiHeaderLoader.parseSize("auto"))
    }

    @Test
    fun `focus ring follows desktop, including an explicit transparent one`() {
        // Translucent ring over the popup (the field's color while typing), as measured on desktop for Dreamer.
        val dreamer = ThemeConverter.convert(
            JSONObject("""{"theme":{"colors":{"frame":"#2E1056","toolbar":"#44296B","toolbar_field":"#2E1056",
              "toolbar_field_text":"#FFFFFF","popup":"#24123B","toolbar_field_border_focus":"rgba(0, 221, 255, 0.7)"}}}"""),
            "d",
        ).theme.light
        assertEquals("#0BA0C4", hex(dreamer["fieldBorderFocus"])) // desktop measured #0AA0C4
        // Cascade sets --toolbar-field-focus-border-color: transparent: desktop draws no ring.
        val noRing = ThemeConverter.convert(
            JSONObject("""{"theme":{"colors":{"frame":"#FAFAFC","toolbar_field_text":"#1E2021","toolbar_field_border_focus":"transparent"}}}"""),
            "c",
        ).theme.light
        assertNull(noRing["fieldBorderFocus"])
    }

    @Test
    fun `status bar takes the header color where desktop's tab strip is`() {
        val gradient = KiwiHeaderLoader.parseGradient("to bottom, #FF6BBA -18.096%, #FFC999 50%", "right top", "repeat-x", null, 144f)!!
        val color = KiwiHeaderLoader.statusBarColor(listOf(gradient), androidx.compose.ui.graphics.Color(0xFFFFC999.toInt()))
        // Desktop 159 measured #FF86B0 at the top of Alpenglow's tab strip.
        assertEquals("#FF8AAF", hex(color.toArgb()))
    }

    @Test
    fun `css themes map new tab, panel and tab strip variables`() {
        val css = """
            :root {
              --toolbar-bgcolor: #3E66B6; --toolbox-bgcolor: #14367B;
              --toolbar-field-background-color: #FAFBFF; --toolbar-field-color: #000000;
              --newtab-background-color: #C9D6F3; --arrowpanel-background: #222222; --arrowpanel-color: #EEEEEE;
              --toolbarbutton-icon-fill: #FFFFFF;
            }
        """.trimIndent()
        val (manifest, _) = CssThemeExtractor.toManifest(css, "c")
        val l = ThemeConverter.convert(manifest, "c").theme.light
        assertEquals("#3E66B6", hex(l["surface"]))
        assertEquals("#14367B", hex(l["frame"]))
        assertEquals("#C9D6F3", hex(l["ntpBackground"]))
        assertEquals("#222222", hex(l["popup"]))
        assertEquals("#EEEEEE", hex(l["popupText"]))
        assertEquals("#FFFFFF", hex(l["icons"]))
    }
}
