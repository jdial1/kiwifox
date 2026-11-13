/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import mozilla.components.browser.storage.sync.KiwiImportCounts
import mozilla.components.browser.storage.sync.KiwiImportedVisit
import mozilla.components.concept.storage.VisitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** History import: Takeout JSON, Takeout zip and a Chromium History database, through the whole pipeline. */
@RunWith(RobolectricTestRunner::class)
class ChromeHistoryImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    // 2026-11-01T00:00:00Z, and "now" a day later.
    private val t0 = 1_793_491_200_000L
    private val now = t0 + 86_400_000L

    private val takeout = """
        {
          "Browser History": [
            {"favicon_url": "https://a.example/f.ico", "page_transition": "TYPED", "title": "A new",
             "url": "https://a.example/", "client_id": "x", "time_usec": ${(t0 + 3000) * 1000}},
            {"page_transition": "LINK", "title": "B", "url": "https://b.example/page?q=1",
             "time_usec": "${(t0 + 2000) * 1000}", "ptoken": {"nested": [1, 2, {"k": null}]}},
            {"page_transition": "AUTO_SUBFRAME", "title": "frame", "url": "https://ads.example/frame",
             "time_usec": ${(t0 + 1500) * 1000}},
            {"page_transition": "LINK", "title": "A old", "url": "https://a.example/", "time_usec": ${(t0 + 1000) * 1000}},
            {"page_transition": "LINK", "title": "settings", "url": "chrome://settings/", "time_usec": ${(t0 + 900) * 1000}},
            {"page_transition": "RELOAD", "title": null, "url": "http://c.example/", "time_usec": ${(t0 + 800) * 1000}},
            {"page_transition": "LINK", "title": "no date", "url": "https://d.example/"},
            {"page_transition": "LINK", "title": "future", "url": "https://e.example/", "time_usec": ${(now + 10 * 86_400_000L) * 1000}}
          ],
          "Something Else": {"a": [1, 2, 3]}
        }
    """.trimIndent()

    private class Sink {
        val visits = ArrayList<KiwiImportedVisit>()
        val batches = ArrayList<Int>()
        suspend fun accept(batch: List<KiwiImportedVisit>): KiwiImportCounts {
            batches += batch.size
            visits += batch
            return KiwiImportCounts(batch.size, 0, 0)
        }
    }

    private fun import(bytes: ByteArray, sink: Sink) = runBlocking {
        KiwiHistoryImporter.importStream(ByteArrayInputStream(bytes), folder.root, now, sink::accept)
    }

    private fun assertTakeoutVisits(sink: Sink) {
        assertEquals(
            listOf(
                KiwiImportedVisit("https://a.example/", "A new", t0 + 3000, VisitType.TYPED),
                KiwiImportedVisit("https://b.example/page?q=1", "B", t0 + 2000, VisitType.LINK),
                // An older visit of a page already seen does not carry its (older) title.
                KiwiImportedVisit("https://a.example/", null, t0 + 1000, VisitType.LINK),
                KiwiImportedVisit("http://c.example/", null, t0 + 800, VisitType.RELOAD),
            ),
            sink.visits,
        )
    }

    @Test
    fun `takeout json keeps dated web page visits and skips the rest`() {
        val sink = Sink()
        val summary = import(takeout.toByteArray(), sink)
        assertEquals(KiwiHistoryImporter.Summary(read = 8, added = 4, duplicates = 0, skipped = 4, failed = 0), summary)
        assertTakeoutVisits(sink)
    }

    @Test
    fun `bare array, BOM and legacy file are read`() {
        val bare = "﻿[{\"url\": \"https://a.example/\", \"title\": \"A\", \"time_usec\": ${t0 * 1000}}]"
        val sink = Sink()
        val summary = import(bare.toByteArray(), sink)
        assertEquals(1, summary.added)
        assertEquals(listOf(KiwiImportedVisit("https://a.example/", "A", t0, VisitType.LINK)), sink.visits)
    }

    @Test
    fun `json without a history array imports nothing`() {
        val sink = Sink()
        val summary = import("{\"Autofill\": [{\"url\": \"https://a.example/\"}]}".toByteArray(), sink)
        assertEquals(KiwiHistoryImporter.Summary(0, 0, 0, 0, 0), summary)
        assertTrue(sink.visits.isEmpty())
        assertEquals("No browsing history found in that file.", describe(summary))
    }

    @Test
    fun `takeout zip is read without extracting, whatever the history file is called`() {
        for (name in listOf("Takeout/Chrome/History.json", "Takeout/Chrome/BrowserHistory.json", "Takeout/Chrome/Verlauf.json")) {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("Takeout/archive_browser.html")); zip.write("<html/>".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("Takeout/Chrome/Autofill.json")); zip.write("{\"Autofill\": []}".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry(name)); zip.write(takeout.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("Takeout/Chrome/Extensions.json")); zip.write("{\"Extensions\": []}".toByteArray()); zip.closeEntry()
            }
            val sink = Sink()
            val summary = import(bytes.toByteArray(), sink)
            assertEquals(name, 4, summary.added)
            assertTakeoutVisits(sink)
        }
    }

    @Test
    fun `chromium History database is read newest first with its 1601 epoch`() {
        val file = folder.newFile("History")
        file.delete()
        val webkit = { ms: Long -> ms * 1000 + 11_644_473_600_000_000L }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE urls(id INTEGER PRIMARY KEY, url LONGVARCHAR, title LONGVARCHAR, visit_count INTEGER)")
            db.execSQL("CREATE TABLE visits(id INTEGER PRIMARY KEY, url INTEGER NOT NULL, visit_time INTEGER NOT NULL, transition INTEGER DEFAULT 0 NOT NULL)")
            db.execSQL("INSERT INTO urls VALUES (1, 'https://a.example/', 'A', 2), (2, 'https://b.example/', 'B', 1), (3, 'edge://settings/', 'S', 1)")
            // transition: core type in the low byte, qualifier flags above (0x30000000 = chain start + end).
            db.execSQL("INSERT INTO visits VALUES (1, 1, ${webkit(t0 + 1000)}, ${0x30000000L or 0L})")
            db.execSQL("INSERT INTO visits VALUES (2, 2, ${webkit(t0 + 2000)}, ${0x30000000L or 3L})")
            db.execSQL("INSERT INTO visits VALUES (3, 1, ${webkit(t0 + 3000)}, ${0x30000000L or 1L})")
            db.execSQL("INSERT INTO visits VALUES (4, 3, ${webkit(t0 + 4000)}, ${0x30000000L or 0L})")
        }
        val bytes = file.readBytes()
        val sink = Sink()
        val summary = import(bytes, sink)
        assertEquals(KiwiHistoryImporter.Summary(read = 4, added = 2, duplicates = 0, skipped = 2, failed = 0), summary)
        assertEquals(
            listOf(
                KiwiImportedVisit("https://a.example/", "A", t0 + 3000, VisitType.TYPED),
                KiwiImportedVisit("https://a.example/", null, t0 + 1000, VisitType.LINK),
            ),
            sink.visits,
        )
        assertFalse("temporary database copy is removed", folder.root.resolve("kiwifox-history-import.db").exists())
    }

    @Test
    fun `large exports go to the store in batches`() {
        val json = buildString {
            append("{\"Browser History\": [")
            for (i in 0 until 1001) {
                if (i > 0) append(',')
                append("{\"url\": \"https://site.example/$i\", \"title\": \"T$i\", \"time_usec\": ${(t0 - i) * 1000}}")
            }
            append("]}")
        }
        val sink = Sink()
        val summary = import(json.toByteArray(), sink)
        assertEquals(1001, summary.added)
        assertEquals(listOf(200, 200, 200, 200, 200, 1), sink.batches)
    }

    @Test
    fun `truncated file fails instead of reporting success`() {
        val sink = Sink()
        val broken = takeout.substring(0, takeout.length / 2).toByteArray()
        assertTrue(runCatching { import(broken, sink) }.isFailure)
    }

    @Test
    fun `transition mapping`() {
        assertEquals(VisitType.TYPED, ChromeHistory.takeoutType("typed"))
        assertEquals(VisitType.BOOKMARK, ChromeHistory.takeoutType("AUTO_BOOKMARK"))
        assertEquals(VisitType.LINK, ChromeHistory.takeoutType(null))
        assertEquals(VisitType.LINK, ChromeHistory.takeoutType("FORM_SUBMIT"))
        assertNull(ChromeHistory.takeoutType("MANUAL_SUBFRAME"))
        assertEquals(VisitType.RELOAD, ChromeHistory.databaseType(0x30000008L))
        assertEquals(VisitType.BOOKMARK, ChromeHistory.databaseType(2L))
        assertNull(ChromeHistory.databaseType(0x30000004L))
        // Chrome stores the flags as a signed 32-bit value: a server redirect sets the top bit.
        assertEquals(VisitType.LINK, ChromeHistory.databaseType(-0x80000000L))
    }
}
