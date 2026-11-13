/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.storage.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import mozilla.components.concept.storage.VisitType
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** KiwiFox: imported visits land in the real places database with their original dates, once. */
@RunWith(AndroidJUnit4::class)
class KiwiHistoryImportTest {
    private lateinit var history: PlacesHistoryStorage

    // 2026-11-01T00:00:00Z
    private val t0 = 1_793_491_200_000L

    private val visits = listOf(
        KiwiImportedVisit("https://a.example/", "A", t0 + 3000, VisitType.TYPED),
        KiwiImportedVisit("https://b.example/page?q=1", "B", t0 + 2000, VisitType.LINK),
        KiwiImportedVisit("https://a.example/", null, t0 + 1000, VisitType.LINK),
    )

    @Before
    fun setup() = runTest {
        history = PlacesHistoryStorage(testContext, mock())
        history.deleteEverything()
    }

    @After
    @Suppress("DEPRECATION")
    fun cleanup() = runTest {
        history.cleanup()
    }

    @Test
    fun `visits keep their dates, titles and types`() = runTest {
        assertEquals(KiwiImportCounts(added = 3, duplicates = 0, failed = 0), history.kiwiImportVisits(visits))

        val stored = history.getDetailedVisits(0).sortedByDescending { it.visitTime }
        assertEquals(listOf(t0 + 3000, t0 + 2000, t0 + 1000), stored.map { it.visitTime })
        assertEquals(listOf("https://a.example/", "https://b.example/page?q=1", "https://a.example/"), stored.map { it.url })
        // The title-less older visit did not wipe the page's title.
        assertEquals(listOf("A", "B", "A"), stored.map { it.title })
        assertEquals(listOf(VisitType.TYPED, VisitType.LINK, VisitType.LINK), stored.map { it.visitType })
    }

    @Test
    fun `importing again adds nothing`() = runTest {
        history.kiwiImportVisits(visits)
        val extra = KiwiImportedVisit("https://c.example/", "C", t0 + 2500, VisitType.LINK)
        assertEquals(KiwiImportCounts(added = 1, duplicates = 3, failed = 0), history.kiwiImportVisits(visits + extra))
        assertEquals(4, history.getDetailedVisits(0).size)
    }

    @Test
    fun `a repeated visit inside one batch is stored once, a bad url is counted`() = runTest {
        val batch = listOf(visits[0], visits[0], KiwiImportedVisit("not a url", null, t0, VisitType.LINK))
        assertEquals(KiwiImportCounts(added = 1, duplicates = 1, failed = 1), history.kiwiImportVisits(batch))
        assertEquals(1, history.getDetailedVisits(0).size)
    }

    @Test
    fun `empty batch`() = runTest {
        assertEquals(KiwiImportCounts(0, 0, 0), history.kiwiImportVisits(emptyList()))
    }
}
