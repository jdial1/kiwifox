/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import mozilla.components.concept.storage.VisitType
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Readers for the browsing history other browsers can hand over. Google gives no other browser access to
 * Chrome sync, so the way in is a file:
 *
 *  - Google Takeout's Chrome export: `History.json` (older exports: `BrowserHistory.json`), or the Takeout
 *    .zip itself. This is the synced history of the Google account.
 *  - The `History` SQLite database from a desktop profile folder of Chrome, Edge, Brave, Opera or Vivaldi
 *    (they share the format). This is that one profile's local history.
 *
 * Everything is read as a stream: a Takeout history can be tens of megabytes.
 */
internal object ChromeHistory {

    /** One exported visit, not yet validated. [type] is null for visits that are not page loads (frames). */
    class Record(val url: String?, val title: String?, val timeMs: Long?, val type: VisitType?)

    enum class Format { JSON, ZIP, SQLITE }

    private const val SNIFF_BYTES = 16
    private const val SQLITE_MAGIC = "SQLite format 3"
    private const val TAKEOUT_ARRAY = "Browser History"

    /** Microseconds between 1601-01-01 (Chrome's database epoch) and 1970-01-01. */
    private const val WEBKIT_EPOCH_OFFSET_USEC = 11_644_473_600_000_000L
    private const val TRANSITION_CORE_MASK = 0xFF

    /** Looks at the first bytes of [input], which must support mark/reset, and leaves it at the start. */
    fun sniff(input: InputStream): Format {
        input.mark(SNIFF_BYTES)
        val head = ByteArray(SNIFF_BYTES)
        var n = 0
        while (n < SNIFF_BYTES) {
            val r = input.read(head, n, SNIFF_BYTES - n)
            if (r < 0) break
            n += r
        }
        input.reset()
        return when {
            n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Format.ZIP
            String(head, 0, n, Charsets.ISO_8859_1).startsWith(SQLITE_MAGIC) -> Format.SQLITE
            else -> Format.JSON
        }
    }

    /**
     * Takeout JSON: `{"Browser History": [{"url", "title", "time_usec", "page_transition", ...}, ...]}`.
     * A bare array of such objects is accepted too. A file without that array yields nothing.
     */
    fun readJson(input: InputStream): Sequence<Record> = sequence {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        reader.isLenient = true
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> readArray(reader)
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == TAKEOUT_ARRAY && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        readArray(reader)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
            else -> Unit
        }
    }

    private suspend fun SequenceScope<Record>.readArray(reader: JsonReader) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) yield(readRecord(reader)) else reader.skipValue()
        }
        reader.endArray()
    }

    private fun readRecord(reader: JsonReader): Record {
        var url: String? = null
        var title: String? = null
        var usec: String? = null
        var transition: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "url" -> url = scalar(reader)
                "title" -> title = scalar(reader)
                "time_usec" -> usec = scalar(reader)
                "page_transition" -> transition = scalar(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val micros = usec?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
        return Record(url, title, micros?.div(MICROS_PER_MILLI), takeoutType(transition))
    }

    private fun scalar(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        else -> {
            reader.skipValue()
            null
        }
    }

    /**
     * A Takeout archive: every `.json` in it that could be the Chrome history (by name, or by sitting in the
     * Chrome folder, whose file names Google translates) is read; files without the history array yield nothing.
     */
    fun readZip(input: InputStream): Sequence<Record> = sequence {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.replace('\\', '/')
            val base = name.substringAfterLast('/')
            val candidate = !entry.isDirectory && base.endsWith(".json", ignoreCase = true) &&
                (base.contains("history", ignoreCase = true) || name.contains("/Chrome/", ignoreCase = true))
            if (candidate) {
                // JsonReader must not close the archive when it is done with one entry.
                yieldAll(readJson(object : FilterInputStream(zip) { override fun close() = Unit }))
            }
        }
    }

    /** The `urls` + `visits` tables of a Chromium `History` database, newest visit first. */
    fun readDatabase(db: SQLiteDatabase): Sequence<Record> = sequence {
        db.rawQuery(
            "SELECT u.url, u.title, v.visit_time, v.transition FROM visits v JOIN urls u ON u.id = v.url " +
                "ORDER BY v.visit_time DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                yield(
                    Record(
                        url = cursor.getString(0),
                        title = cursor.getString(1),
                        timeMs = (cursor.getLong(2) - WEBKIT_EPOCH_OFFSET_USEC) / MICROS_PER_MILLI,
                        type = databaseType(cursor.getLong(3)),
                    ),
                )
            }
        }
    }

    /** Takeout's `page_transition` names (Chrome's ui::PageTransition). Unknown names count as link clicks. */
    fun takeoutType(transition: String?): VisitType? = when (transition?.uppercase()) {
        "TYPED" -> VisitType.TYPED
        "AUTO_BOOKMARK" -> VisitType.BOOKMARK
        "RELOAD" -> VisitType.RELOAD
        "AUTO_SUBFRAME", "MANUAL_SUBFRAME" -> null
        else -> VisitType.LINK
    }

    /** The database's `transition` column: the low byte is the same enum, the rest are qualifier flags. */
    @Suppress("MagicNumber")
    fun databaseType(transition: Long): VisitType? = when ((transition and TRANSITION_CORE_MASK.toLong()).toInt()) {
        1 -> VisitType.TYPED
        2 -> VisitType.BOOKMARK
        3, 4 -> null
        8 -> VisitType.RELOAD
        else -> VisitType.LINK
    }

    private const val MICROS_PER_MILLI = 1000L
}
