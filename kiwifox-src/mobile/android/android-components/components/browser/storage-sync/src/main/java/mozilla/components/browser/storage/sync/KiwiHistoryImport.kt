/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.storage.sync

import kotlinx.coroutines.withContext
import mozilla.appservices.places.uniffi.VisitObservation
import mozilla.components.concept.storage.VisitType

/**
 * KiwiFox: one visit taken from another browser's history export. [time] is Unix milliseconds.
 * [title] is only sent when non-null, so callers pass it once per page (with the newest visit).
 */
data class KiwiImportedVisit(val url: String, val title: String?, val time: Long, val type: VisitType)

/** KiwiFox: what [kiwiImportVisits] did with a batch. */
data class KiwiImportCounts(val added: Int, val duplicates: Int, val failed: Int)

/**
 * KiwiFox: records visits with their original dates. [PlacesHistoryStorage.recordVisit] always stamps
 * "now", and the connection that accepts a date is internal to this module, hence this file.
 *
 * A visit already stored with the same URL and millisecond is skipped, so importing the same file twice
 * (or an overlapping one) does not double the history. Visits are written as local ones, which lets
 * Firefox sync upload them like any other visit.
 */
suspend fun PlacesHistoryStorage.kiwiImportVisits(visits: List<KiwiImportedVisit>): KiwiImportCounts {
    if (visits.isEmpty()) return KiwiImportCounts(0, 0, 0)
    val known = HashSet<Pair<String, Long>>()
    getDetailedVisits(visits.minOf { it.time } - 1, visits.maxOf { it.time } + 1)
        .mapTo(known) { it.url to it.visitTime }
    var added = 0
    var duplicates = 0
    var failed = 0
    withContext(writeScope.coroutineContext) {
        val writer = places.writer()
        for (visit in visits) {
            if (!known.add(visit.url to visit.time)) {
                duplicates++
                continue
            }
            @Suppress("TooGenericExceptionCaught") // a URL places refuses must not stop the import
            try {
                writer.noteObservation(
                    VisitObservation(
                        url = visit.url,
                        title = visit.title,
                        visitType = visit.type.into(),
                        at = visit.time,
                    ),
                )
                added++
            } catch (e: Exception) {
                logger.debug("kiwiImportVisits: visit refused: ${e.javaClass.simpleName}")
                failed++
            }
        }
    }
    return KiwiImportCounts(added, duplicates, failed)
}
