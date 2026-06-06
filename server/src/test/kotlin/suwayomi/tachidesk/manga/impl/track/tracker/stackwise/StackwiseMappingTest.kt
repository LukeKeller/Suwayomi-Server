package suwayomi.tachidesk.manga.impl.track.tracker.stackwise

import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SWManga
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SWTrackEntry
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_COMPLETED
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_DROPPED
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_IN_PROGRESS
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_PAUSED
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_PLANNING
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.copyTo
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.millisToStackwiseDate
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.stackwiseDateToMillis
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.statusFromStackwise
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.statusToStackwise
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.toTrackSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StackwiseMappingTest {
    @Test
    fun statusRoundTripsThroughStackwiseStrings() {
        listOf(SW_IN_PROGRESS, SW_PLANNING, SW_COMPLETED, SW_PAUSED, SW_DROPPED).forEach { id ->
            assertEquals(id, statusFromStackwise(statusToStackwise(id)))
        }
    }

    @Test
    fun statusMapsToStackwiseNativeLabels() {
        assertEquals("In progress", statusToStackwise(SW_IN_PROGRESS))
        assertEquals("Planning", statusToStackwise(SW_PLANNING))
        assertEquals("Completed", statusToStackwise(SW_COMPLETED))
        assertEquals("Paused", statusToStackwise(SW_PAUSED))
        assertEquals("Dropped", statusToStackwise(SW_DROPPED))
    }

    @Test
    fun unknownStatusFallsBackToInProgress() {
        assertEquals(SW_IN_PROGRESS, statusFromStackwise("something-unmapped"))
        assertEquals("In progress", statusToStackwise(9999))
    }

    @Test
    fun dateConversionRoundTrips() {
        val millis = stackwiseDateToMillis("2024-03-15")
        assertEquals("2024-03-15", millisToStackwiseDate(millis))
    }

    @Test
    fun unsetOrInvalidDatesMapToZeroAndNull() {
        assertEquals(0L, stackwiseDateToMillis(null))
        assertEquals(0L, stackwiseDateToMillis(""))
        assertEquals(0L, stackwiseDateToMillis("not-a-date"))
        assertNull(millisToStackwiseDate(0L))
        assertNull(millisToStackwiseDate(-5L))
    }

    @Test
    fun searchResultMapsToTrackSearch() {
        val ts =
            SWManga(
                id = 42,
                title = "Berserk",
                totalChapters = 364,
                coverUrl = "https://sw/cover.jpg",
                url = "https://sw/manga/42",
                summary = "summary",
                publishingStatus = "Releasing",
                startDate = "1989-08-25",
                authors = listOf("Kentaro Miura"),
            ).toTrackSearch(TRACKER_ID)

        assertEquals(TRACKER_ID, ts.tracker_id)
        assertEquals(42L, ts.remote_id)
        assertEquals("Berserk", ts.title)
        assertEquals(364, ts.total_chapters)
        assertEquals("https://sw/cover.jpg", ts.cover_url)
        assertEquals("https://sw/manga/42", ts.tracking_url)
        assertEquals(listOf("Kentaro Miura"), ts.authors)
    }

    @Test
    fun entryCopiesOntoTrackWithStatusAndDates() {
        val track = Track.create(TRACKER_ID)
        SWTrackEntry(
            id = 42,
            title = "Berserk",
            status = "Completed",
            progress = 100,
            score = 9.5,
            totalChapters = 364,
            startDate = "2020-01-01",
            endDate = "2021-02-03",
            url = "https://sw/manga/42",
        ).copyTo(track)

        assertEquals(42L, track.remote_id)
        assertEquals("Berserk", track.title)
        assertEquals(SW_COMPLETED, track.status)
        assertEquals(100.0, track.last_chapter_read)
        assertEquals(364, track.total_chapters)
        assertEquals(9.5, track.score)
        assertEquals("https://sw/manga/42", track.tracking_url)
        assertEquals(stackwiseDateToMillis("2020-01-01"), track.started_reading_date)
        assertEquals(stackwiseDateToMillis("2021-02-03"), track.finished_reading_date)
    }

    private companion object {
        const val TRACKER_ID = 10
    }
}
