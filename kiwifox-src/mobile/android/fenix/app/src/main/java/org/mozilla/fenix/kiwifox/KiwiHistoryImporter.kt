/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import mozilla.components.browser.storage.sync.KiwiImportCounts
import mozilla.components.browser.storage.sync.KiwiImportedVisit
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.storage.sync.kiwiImportVisits
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * Imports another browser's history file (see [ChromeHistory] for the formats) into Firefox's history, with
 * the original visit dates. Runs in its own scope so leaving the screen does not stop it; [state] is what
 * the screen shows. Importing the same file again adds nothing (see `kiwiImportVisits`).
 */
internal object KiwiHistoryImporter {
    const val SETTINGS_KEY = "kiwifox_history_import"

    /** [skipped]: not a web page visit (frames, non-http addresses, no usable date). */
    data class Summary(val read: Int, val added: Int, val duplicates: Int, val skipped: Int, val failed: Int)

    sealed interface State {
        data object Idle : State
        data class Running(val read: Int, val added: Int) : State
        data class Done(val summary: Summary) : State
        data class Failed(val message: String) : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    private const val BATCH = 200
    private const val MAX_URL_LENGTH = 65_536 // places' URL_LENGTH_MAX
    private const val MAX_TITLE_LENGTH = 4_096 // places' TITLE_LENGTH_MAX
    private const val CLOCK_SLACK_MS = 24 * 60 * 60 * 1000L
    private const val TEMP_DB = "kiwifox-history-import.db"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context, uri: Uri, storage: PlacesHistoryStorage) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        state = State.Running(0, 0)
        job = scope.launch {
            state = try {
                val input = app.contentResolver.openInputStream(uri) ?: error("could not open the selected file")
                val summary = input.use {
                    importStream(it, app.cacheDir, System.currentTimeMillis(), storage::kiwiImportVisits) { read, added ->
                        state = State.Running(read, added)
                    }
                }
                State.Done(summary)
            } catch (e: CancellationException) {
                State.Failed("Stopped. The visits imported so far are kept; importing the file again skips them.")
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /**
     * Reads [input] in whichever format it is and hands validated visits to [sink] in batches.
     * [cacheDir] holds the temporary copy a SQLite database needs (deleted afterwards).
     */
    suspend fun importStream(
        input: InputStream,
        cacheDir: File,
        now: Long,
        sink: suspend (List<KiwiImportedVisit>) -> KiwiImportCounts,
        onProgress: (read: Int, added: Int) -> Unit = { _, _ -> },
    ): Summary {
        val buffered = BufferedInputStream(input)
        return when (ChromeHistory.sniff(buffered)) {
            ChromeHistory.Format.JSON -> drain(ChromeHistory.readJson(buffered), now, sink, onProgress)
            ChromeHistory.Format.ZIP -> drain(ChromeHistory.readZip(buffered), now, sink, onProgress)
            ChromeHistory.Format.SQLITE -> {
                val file = File(cacheDir, TEMP_DB)
                try {
                    file.outputStream().use { buffered.copyTo(it) }
                    // Read-write on our own copy: SQLite refuses a WAL-mode database opened read-only.
                    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                        drain(ChromeHistory.readDatabase(db), now, sink, onProgress)
                    }
                } finally {
                    SQLiteDatabase.deleteDatabase(file)
                }
            }
        }
    }

    private suspend fun drain(
        records: Sequence<ChromeHistory.Record>,
        now: Long,
        sink: suspend (List<KiwiImportedVisit>) -> KiwiImportCounts,
        onProgress: (Int, Int) -> Unit,
    ): Summary {
        var read = 0
        var added = 0
        var duplicates = 0
        var skipped = 0
        var failed = 0
        val titled = HashSet<String>()
        val batch = ArrayList<KiwiImportedVisit>(BATCH)

        suspend fun flush() {
            currentCoroutineContext().ensureActive()
            if (batch.isNotEmpty()) {
                val counts = sink(batch.toList())
                added += counts.added
                duplicates += counts.duplicates
                failed += counts.failed
                batch.clear()
            }
            onProgress(read, added)
        }

        for (record in records) {
            read++
            val visit = validate(record, now)
            if (visit == null) {
                skipped++
            } else {
                // Exports list the newest visit first: only that one carries the page title, so an older
                // visit's title never replaces a newer one.
                batch += if (titled.add(visit.url)) visit else visit.copy(title = null)
            }
            if (batch.size >= BATCH || read % BATCH == 0) flush()
        }
        flush()
        return Summary(read, added, duplicates, skipped, failed)
    }

    /** A record becomes a visit only if it is a dated http(s) page load. */
    fun validate(record: ChromeHistory.Record, now: Long): KiwiImportedVisit? {
        val url = record.url?.trim().orEmpty()
        val web = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        val time = record.timeMs ?: 0L
        val type = record.type
        if (!web || url.length > MAX_URL_LENGTH || time <= 0L || time > now + CLOCK_SLACK_MS || type == null) return null
        val title = record.title?.trim()?.take(MAX_TITLE_LENGTH)?.ifEmpty { null }
        return KiwiImportedVisit(url, title, time, type)
    }
}
