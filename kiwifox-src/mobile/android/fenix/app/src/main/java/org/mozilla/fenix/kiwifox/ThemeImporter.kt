/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.net.Uri
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Header
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Turns a link or a file into a [KiwiTheme].
 *
 * Links understood:
 * - addons.mozilla.org theme pages (resolved through the AMO API to the `.xpi`)
 * - direct `.xpi` / `.zip` / `manifest.json` / `.css` URLs
 * - reddit.com links are refused: Reddit's bot check blocks app traffic, so the popular
 *   r/FirefoxCSS themes are listed in [ThemeCatalog] with their GitHub links instead
 * - github.com repositories or files: every `.css` file is read and run through [CssThemeExtractor];
 *   a `manifest.json` with a `theme` key is preferred when the repository ships one
 * - color.firefox.com share links are refused with instructions (the payload is LZMA-compressed;
 *   export the theme from Firefox Color and import the file instead)
 */
class ThemeImporter(private val client: Client) {
    class Imported(val theme: KiwiTheme, val notes: List<String>)

    private val userAgent = "KiwiFox/1.0 (Firefox for Android theme importer)"

    @Throws(ThemeImportException::class)
    fun importUrl(input: String): Imported {
        val imported = importUrlInternal(input, 0)
        return Imported(imported.theme.copy(source = input.trim()), imported.notes)
    }

    private fun importUrlInternal(input: String, depth: Int): Imported {
        val url = input.trim()
        if (depth > MAX_REDIRECT_DEPTH) throw ThemeImportException("too many link hops")
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: throw ThemeImportException("not a URL: $url")
        val host = uri.host?.lowercase() ?: throw ThemeImportException("not a URL: $url")
        val path = uri.path ?: ""
        return when {
            host.endsWith("addons.mozilla.org") -> importAmo(path)
            host == "color.firefox.com" -> throw ThemeImportException(
                "Firefox Color share links are compressed in a format this app cannot read. In Firefox Color, " +
                    "use Export and import the downloaded file here.",
            )
            host.endsWith("reddit.com") || host == "redd.it" -> throw ThemeImportException(
                "Reddit links cannot be fetched from the app. Pick the theme from the r/FirefoxCSS list, " +
                    "or paste the theme's GitHub link from the post.",
            )
            host == "github.com" -> importGithub(path, depth)
            host == "raw.githubusercontent.com" || path.endsWith(".css") -> fromCss(fetchText(url), nameFromUrl(url))
            path.endsWith(".xpi") || path.endsWith(".zip") -> fromZip(fetchBytes(url), nameFromUrl(url))
            path.endsWith(".json") -> fromManifestText(fetchText(url), nameFromUrl(url))
            else -> fromBytes(fetchBytes(url), nameFromUrl(url))
        }
    }

    /** Imports a file picked by the user: `.xpi`, `.zip`, `manifest.json`, a KiwiFox theme JSON, or `.css`. */
    @Throws(ThemeImportException::class)
    fun importFile(bytes: ByteArray, displayName: String): Imported {
        val imported = fromBytes(bytes, displayName.substringBeforeLast('.'))
        return Imported(imported.theme.copy(source = displayName), imported.notes)
    }

    // ---- sources -------------------------------------------------------------------------------

    private fun importAmo(path: String): Imported {
        val slug = Regex("/addon/([^/]+)/?").find(path)?.groupValues?.get(1)
            ?: throw ThemeImportException("not an add-on page: addons.mozilla.org$path")
        val api = JSONObject(fetchText("https://services.addons.mozilla.org/api/v5/addons/addon/$slug/?lang=en-US"))
        val type = api.optString("type")
        if (type != "statictheme") throw ThemeImportException("'$slug' is a $type add-on, not a theme")
        val file = api.optJSONObject("current_version")?.optJSONObject("file")
            ?: throw ThemeImportException("AMO lists no downloadable file for '$slug'")
        val names = api.optJSONObject("name")
        var name = names?.optString("en-US").orEmpty()
        if (name.isBlank() && names != null) {
            val firstKey = names.keys().asSequence().firstOrNull()
            name = if (firstKey != null) names.optString(firstKey) else ""
        }
        return fromZip(fetchBytes(file.getString("url")), name.ifBlank { slug })
    }

    @Suppress("ReturnCount")
    private fun importGithub(path: String, depth: Int): Imported {
        val parts = path.trim('/').split('/')
        if (parts.size < 2) throw ThemeImportException("GitHub link must point at a repository")
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        var ref = "HEAD"
        var subPath = ""
        if (parts.size >= 4 && (parts[2] == "tree" || parts[2] == "blob")) {
            ref = parts[3]
            subPath = parts.drop(4).joinToString("/")
        }
        if (parts.getOrNull(2) == "blob" && subPath.isNotEmpty() && !subPath.endsWith("/")) {
            // A single file: css, manifest or theme json.
            val raw = "https://raw.githubusercontent.com/$owner/$repo/$ref/$subPath"
            return when {
                subPath.endsWith(".css") -> fromCss(fetchText(raw), repo)
                subPath.endsWith(".json") -> fromManifestText(fetchText(raw), repo)
                subPath.endsWith(".zip") || subPath.endsWith(".xpi") -> fromZip(fetchBytes(raw), repo)
                else -> throw ThemeImportException("unsupported file type: $subPath")
            }
        }
        val tree = JSONObject(fetchText("https://api.github.com/repos/$owner/$repo/git/trees/$ref?recursive=1", accept = "application/vnd.github+json"))
        val entries = tree.optJSONArray("tree") ?: throw ThemeImportException("GitHub returned no file list for $owner/$repo")
        val files = ArrayList<String>()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.optString("type") != "blob") continue
            val p = e.optString("path")
            if (subPath.isNotEmpty() && !p.startsWith("$subPath/")) continue
            files.add(p)
        }
        val resolvedRef = tree.optString("sha").ifBlank { ref }
        fun raw(p: String) = "https://raw.githubusercontent.com/$owner/$repo/$resolvedRef/$p"

        // A repository that ships a static theme manifest is a desktop theme: use that directly.
        files.firstOrNull { it.endsWith("manifest.json") && it.count { c -> c == '/' } <= 1 }?.let { manifestPath ->
            val manifest = runCatching { JSONObject(fetchText(raw(manifestPath))) }.getOrNull()
            if (manifest != null && manifest.has("theme")) return fromManifest(manifest, repo)
        }

        val cssFiles = files.filter { it.endsWith(".css") }.sortedBy { if (it.endsWith("userChrome.css")) 0 else 1 }.take(MAX_CSS_FILES)
        if (cssFiles.isEmpty()) throw ThemeImportException("$owner/$repo contains no .css files or theme manifest")
        val css = StringBuilder()
        for (p in cssFiles) {
            css.append("\n/* $p */\n").append(fetchText(raw(p)))
            if (css.length > MAX_CSS_BYTES) break
        }
        val imported = fromCss(css.toString(), repo)
        return Imported(imported.theme, listOf("read ${cssFiles.size} css file(s) from $owner/$repo") + imported.notes)
    }

    // ---- formats -------------------------------------------------------------------------------

    private fun fromBytes(bytes: ByteArray, name: String): Imported {
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return fromZip(bytes, name)
        val text = String(bytes, Charsets.UTF_8)
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("{") -> fromManifestText(text, name)
            trimmed.contains(":root") || trimmed.contains("--") -> fromCss(text, name)
            else -> throw ThemeImportException("unrecognized file: expected a .xpi/.zip theme, a manifest.json, a KiwiFox theme JSON or a userChrome.css")
        }
    }

    private fun fromZip(bytes: ByteArray, name: String): Imported {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "manifest.json" || entry.name.endsWith("/manifest.json")) {
                    return fromManifestText(zip.readBytes().toString(Charsets.UTF_8), name)
                }
            }
        }
        throw ThemeImportException("archive has no manifest.json; this is not a Firefox theme package")
    }

    private fun fromManifestText(text: String, name: String): Imported {
        val json = runCatching { JSONObject(text) }.getOrElse { throw ThemeImportException("not valid JSON") }
        // A KiwiFox theme file exported by this app or by the desktop pipeline.
        if (!json.has("theme") && json.has("light") && json.has("dark")) {
            val theme = KiwiTheme.fromJson(id = "", text = text, builtIn = false, fallbackName = name)
            if (theme.light.isEmpty()) throw ThemeImportException("KiwiFox theme file has no light colors")
            return Imported(theme, emptyList())
        }
        return fromManifest(json, name)
    }

    private fun fromManifest(manifest: JSONObject, name: String): Imported {
        val converted = ThemeConverter.convert(manifest, name)
        return Imported(converted.theme, converted.notes)
    }

    private fun fromCss(css: String, name: String): Imported {
        val (manifest, notes) = CssThemeExtractor.toManifest(css, name)
        val converted = ThemeConverter.convert(manifest, name)
        return Imported(converted.theme, notes + converted.notes)
    }

    // ---- network -------------------------------------------------------------------------------

    private fun fetchText(url: String, accept: String = "*/*"): String = String(fetchBytes(url, accept), Charsets.UTF_8)

    private fun fetchBytes(url: String, accept: String = "*/*"): ByteArray {
        val request = Request(
            url = url,
            headers = MutableHeaders(listOf(Header("User-Agent", userAgent), Header("Accept", accept))),
            connectTimeout = Pair(15L, TimeUnit.SECONDS),
            readTimeout = Pair(30L, TimeUnit.SECONDS),
            cookiePolicy = Request.CookiePolicy.OMIT,
        )
        val response = try {
            client.fetch(request)
        } catch (e: IOException) {
            throw ThemeImportException("could not download $url (${e.message ?: "network error"})")
        }
        response.use {
            if (it.status !in 200..299) throw ThemeImportException("HTTP ${it.status} for $url")
            return it.body.useStream { stream -> readLimited(stream) }
        }
    }

    private fun readLimited(stream: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_DOWNLOAD_BYTES) throw ThemeImportException("download larger than ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB; not a theme")
        }
        return out.toByteArray()
    }

    private fun nameFromUrl(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.').ifBlank { "imported theme" }

    companion object {
        private const val MAX_REDIRECT_DEPTH = 3
        private const val MAX_CSS_FILES = 40
        private const val MAX_CSS_BYTES = 2 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
    }
}
