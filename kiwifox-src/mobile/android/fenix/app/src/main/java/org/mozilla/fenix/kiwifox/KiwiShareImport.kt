/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.storage.sync.KiwiImportedVisit
import mozilla.components.browser.storage.sync.kiwiImportVisits
import mozilla.components.concept.bookmark.parser.BookmarksFileParser
import mozilla.components.concept.bookmarks.file.BookmarksFileImporter
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.fetch.isSuccess
import mozilla.components.lib.bookmark.parser.jsoup.jsoupParser
import mozilla.components.lib.bookmarks.file.htmlImporter
import mozilla.components.lib.password.parser.csv.CsvPasswordsFileParser
import mozilla.appservices.places.BookmarkRoot
import org.mozilla.fenix.ext.components
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Import from Chrome (Wi-Fi)": pulls the encrypted .kfex bundle from the LAN media share (built by
 * browser-export/export-chrome-to-kiwifox.ps1), decrypts it in-app ([KiwiCrypto]), and imports whatever it
 * holds - bookmarks, history and passwords - reusing Firefox's own importers plus KiwiFox's history importer.
 * Runs in its own scope so leaving the screen doesn't cancel it.
 */
object KiwiShareImport {
    const val PREFS = "kiwifox_share"
    const val KEY_URL = "share_url"
    const val DEFAULT_URL = "http://192.168.1.183:8092/browser-export/chrome-export.kfex"

    data class Summary(
        val bookmarks: Int? = null,
        val historyAdded: Int? = null,
        val historyDuplicates: Int? = null,
        val passwords: Int? = null,
        val passwordNote: String? = null,
    )

    sealed interface State {
        data object Idle : State
        data class Running(val step: String) : State
        data class Done(val summary: Summary) : State
        data class Failed(val message: String) : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun savedUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun saveUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, url.trim()).apply()
    }

    fun start(context: Context, url: String, passphrase: String) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        saveUrl(app, url)
        state = State.Running("Connecting…")
        job = scope.launch {
            state = try {
                State.Done(run(app, url.trim(), passphrase))
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun run(app: Context, url: String, passphrase: String): Summary {
        state = State.Running("Downloading bundle…")
        val bytes = download(app, url)

        state = State.Running("Decrypting…")
        val entries = KiwiCrypto.openKfexEntries(bytes, passphrase)
        if (entries.isEmpty()) throw IllegalStateException("The bundle was empty.")

        val cache = File(app.cacheDir, "kiwifox-share").apply { mkdirs() }
        try {
            var summary = Summary()

            entries["bookmarks.html"]?.let { data ->
                state = State.Running("Importing bookmarks…")
                val f = File(cache, "bookmarks.html").apply { writeBytes(data) }
                val importer = BookmarksFileImporter.htmlImporter(
                    context = app,
                    parentGuid = BookmarkRoot.Mobile.id,
                    parser = BookmarksFileParser.jsoupParser(rootFolderName = "Chrome import"),
                    inserter = app.components.core.bookmarksStorage,
                )
                val r = importer.importBookmarksFromUri(Uri.fromFile(f))
                summary = summary.copy(bookmarks = r.getOrThrow().count)
            }

            entries["History"]?.let { data ->
                state = State.Running("Importing history…")
                val storage = app.components.core.historyStorage
                val result = KiwiHistoryImporter.importStream(
                    input = ByteArrayInputStream(data),
                    cacheDir = cache,
                    now = System.currentTimeMillis(),
                    sink = { batch: List<KiwiImportedVisit> -> storage.kiwiImportVisits(batch) },
                )
                summary = summary.copy(historyAdded = result.added, historyDuplicates = result.duplicates)
            }

            entries["passwords.csv"]?.let { data ->
                state = State.Running("Importing passwords…")
                val parsed = CsvPasswordsFileParser().parse(ByteArrayInputStream(data)).getOrThrow()
                // Chrome's CSV carries neither formActionOrigin nor httpRealm, and a login with neither is
                // rejected as invalid. Mark these as form logins (empty formActionOrigin = "any action",
                // Firefox's own convention for imported web logins).
                val logins = parsed.logins.map {
                    if (it.httpRealm.isNullOrEmpty() && it.formActionOrigin.isNullOrEmpty()) {
                        it.copy(formActionOrigin = "")
                    } else {
                        it
                    }
                }
                val results = app.components.core.passwordsStorage.addMany(logins)
                val ok = results.count { it.isSuccess }
                val rejected = results.size - ok
                val firstErr = results.firstOrNull { it.isFailure }?.exceptionOrNull()
                summary = summary.copy(
                    passwords = ok,
                    passwordNote = if (rejected > 0 && firstErr != null) {
                        "$rejected skipped (${firstErr.javaClass.simpleName}: ${firstErr.message})"
                    } else {
                        null
                    },
                )
            }

            return summary
        } finally {
            cache.listFiles()?.forEach { it.delete() } // plaintext bookmarks/history/passwords - wipe
        }
    }

    private fun download(app: Context, url: String): ByteArray {
        val response = app.components.core.client.fetch(
            Request(
                url = url,
                method = Request.Method.GET,
                connectTimeout = Pair(30, TimeUnit.SECONDS),
                readTimeout = Pair(120, TimeUnit.SECONDS),
                useCaches = false,
            ),
        )
        response.use {
            if (!it.isSuccess) throw IllegalStateException("Share returned HTTP ${it.status}. Check the URL and that the PC share is reachable.")
            return it.body.useStream { s -> s.readBytes() }
        }
    }
}
