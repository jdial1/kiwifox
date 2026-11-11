/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import java.io.File

/** Local clones of userChrome.css repositories, read exactly as [ThemeImporter] reads them from GitHub. */
internal object CatalogTestRepos {
    private fun notGit(f: File) = !f.path.contains("${File.separator}.git${File.separator}")

    /** Repository directories under KIWIFOX_CATALOG_DIR, or empty when it is unset. */
    fun repos(): List<File> {
        val dir = System.getenv("KIWIFOX_CATALOG_DIR") ?: return emptyList()
        return File(dir).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
    }

    /** The CSS desktop loads (userChrome.css + imports, via [ThemeImporter.repoCss]) and what was read. */
    fun css(repo: File): Pair<String, String> {
        val files = repo.walkTopDown().filter { it.isFile && it.extension == "css" && notGit(it) }
            .associateBy { it.relativeTo(repo).path.replace('\\', '/') }
        return ThemeImporter.repoCss(files.keys) { files[it]?.readText() }
    }

    /** The shallowest user.js, as the importer picks it. */
    fun userJs(repo: File): String? =
        repo.walkTopDown().filter { it.isFile && it.name == "user.js" && notGit(it) }
            .minByOrNull { it.relativeTo(repo).path.count { c -> c == File.separatorChar } }?.readText()
}
