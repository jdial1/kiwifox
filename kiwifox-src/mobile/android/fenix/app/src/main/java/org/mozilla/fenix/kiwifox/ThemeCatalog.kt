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
    class Entry(
        val name: String,
        val author: String,
        val repoUrl: String,
        val description: String,
        val stars: Int,
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
