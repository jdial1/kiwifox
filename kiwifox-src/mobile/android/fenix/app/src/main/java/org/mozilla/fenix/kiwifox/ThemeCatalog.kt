/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

/**
 * The most popular r/FirefoxCSS themes, by GitHub stars on 2026-11-04, pointing straight at
 * their repositories. Reddit is deliberately not crawled (its bot check blocks app traffic);
 * these links are fixed here and refreshed with the pipeline.
 *
 * All of them are userChrome.css themes. Only those whose toolbar colors are literal `:root`
 * values convert; the others take their colors from an AMO theme or browser state and the
 * importer says so when "Add" is pressed.
 */
object ThemeCatalog {
    /**
     * @property repoUrl The link the importer resolves (a GitHub repository or an AMO page); also the row's key.
     * @property stars GitHub stars, for userChrome.css themes.
     * @property users Average daily users on addons.mozilla.org, for AMO themes.
     */
    class Entry(
        val name: String,
        val author: String,
        val repoUrl: String,
        val description: String,
        val stars: Int = 0,
        val users: Int = 0,
    )

    /**
     * Popular addons.mozilla.org static themes that convert completely (no images, readable URL
     * text, visible address field) and look distinct from each other and from the bundled themes.
     * Picked 2026-11-10 from the 100 most-used AMO themes by `tools/amo_survey.py` and
     * `tools/pick_distinct.py` (CIE dE >= 25 on bar and field colors, in order of daily users).
     * Mozilla's Nova and Profiles themes rank higher but are near-stock tints or image-based.
     * These have no separate dark palette, so they look the same in dark mode, as on desktop.
     */
    /**
     * Popular addons.mozilla.org themes whose look is artwork: header images, animated images (APNG, GIF)
     * and window-wide gradients, drawn across the status bar and toolbar as desktop draws them across the
     * tab strip and toolbar. Rendered and compared with desktop Firefox 159 on 2026-11-11.
     */
    val AMO_IMAGE_ENTRIES: List<Entry> = listOf(
        Entry("Ash", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/nova-ash/", "Firefox's Nova theme: a soft window-wide gradient", users = 319372),
        Entry("Dark space", "Nicothin", "https://addons.mozilla.org/en-US/firefox/addon/nicothin-space/", "Animated starfield (GIF)", users = 98209),
        Entry("Spirited Away (Animated)", "Max", "https://addons.mozilla.org/en-US/firefox/addon/spirited-away-animated/", "Animated Haku over a teal sky (APNG)", users = 28373),
        Entry("Praise the sun (animated)", "anon", "https://addons.mozilla.org/en-US/firefox/addon/praisethesun/", "Animated knights at both ends of the bar (APNG)", users = 29830),
        Entry("Nyan Cat (Animated)", "Jnue", "https://addons.mozilla.org/en-US/firefox/addon/nyan-cat-animated/", "Nyan Cat and rainbow over a starfield", users = 27450),
        Entry("Blue Nebula Space Theme", "eartharoid", "https://addons.mozilla.org/en-US/firefox/addon/blue-nebula-space-theme/", "Nebula photo across the whole header", users = 16351),
    )

    val AMO_ENTRIES: List<Entry> = listOf(
        Entry("Activist – Balanced", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/activist-balanced_/", "Mid blue bar, white address field", users = 316320),
        Entry("Visionary – Balanced", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/visionary-balanced/", "Fresh green bar, mint-white field", users = 196904),
        Entry("Expressionist – Balanced", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/expressionist-balanced/", "Butter yellow bar, cream field", users = 103127),
        Entry("Innovator – Balanced", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/innovator-balanced/", "Soft peach bar and field", users = 86019),
        Entry("Playmaker – Balanced", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/playmaker-balanced/", "Coral red bar, blush field", users = 81906),
        Entry("Dreamer – Bold", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/dreamer-bold/", "Deep purple, white text", users = 54865),
        Entry("Cheers – Soft", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/cheers-soft/", "Pale gold bar, saffron field", users = 45848),
        Entry("Visionary – Bold", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/visionary-bold/", "Forest green, white text", users = 37011),
        Entry("Playmaker – Bold", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/playmaker-bold/", "Maroon, white text", users = 27452),
        Entry("Cheers – Bold", "Firefox", "https://addons.mozilla.org/en-US/firefox/addon/cheers-bold/", "Charcoal with gold text", users = 21395),
    )

    val ENTRIES: List<Entry> = listOf(
        Entry("Firefox-UI-Fix (Lepton)", "black7375", "https://github.com/black7375/Firefox-UI-Fix", "Respects Proton UI and aims to improve it", 5925),
        Entry("Firefox GNOME theme", "rafaelmardojai", "https://github.com/rafaelmardojai/firefox-gnome-theme", "A GNOME theme for Firefox", 3929),
        Entry("ShyFox", "Naezr", "https://github.com/Naezr/ShyFox", "Hides the entire browser interface in the window border", 2123),
        Entry("MaterialFox", "muckSponge", "https://github.com/muckSponge/MaterialFox", "A Material Design-inspired userChrome.css theme", 1957),
        Entry("SimpleFox", "migueravila", "https://github.com/migueravila/simplefox", "Minimalist and keyboard centered", 1861),
        Entry("Firefox-Mod-Blur", "datguypiko", "https://github.com/datguypiko/Firefox-Mod-Blur", "Dark theme, more compact, modular, blur", 1758),
        Entry("ArcWTF", "KiKaraage", "https://github.com/KiKaraage/ArcWTF", "Brings the Arc Browser look to Firefox", 1717),
        Entry("Cascade", "cascadefox", "https://github.com/cascadefox/cascade", "A responsive one-line CSS theme", 1604),
        Entry("WaveFox", "QNetITQ", "https://github.com/QNetITQ/WaveFox", "CSS theme for manual customization", 1254),
        Entry("FF-ULTIMA", "soulhotel", "https://github.com/soulhotel/FF-ULTIMA", "The Firefox theme with too many options", 1247),
    )
}
