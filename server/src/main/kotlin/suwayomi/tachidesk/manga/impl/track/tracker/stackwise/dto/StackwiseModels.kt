package suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.model.TrackSearch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Status codes used locally by the Stackwise tracker. They are translated to and from
 * Stackwise's textual `Status` choices (app/models.py: Status) on the wire, so the Stackwise
 * side stores its native values and Suwayomi keeps integer status ids like every other tracker.
 */
const val SW_IN_PROGRESS = 1
const val SW_PLANNING = 2
const val SW_COMPLETED = 3
const val SW_PAUSED = 4
const val SW_DROPPED = 5

// Stackwise `Status` text choices.
private const val STACKWISE_IN_PROGRESS = "In progress"
private const val STACKWISE_PLANNING = "Planning"
private const val STACKWISE_COMPLETED = "Completed"
private const val STACKWISE_PAUSED = "Paused"
private const val STACKWISE_DROPPED = "Dropped"

/** Local status id -> Stackwise `Status` string. Unknown ids fall back to "In progress". */
internal fun statusToStackwise(status: Int): String =
    when (status) {
        SW_PLANNING -> STACKWISE_PLANNING
        SW_COMPLETED -> STACKWISE_COMPLETED
        SW_PAUSED -> STACKWISE_PAUSED
        SW_DROPPED -> STACKWISE_DROPPED
        else -> STACKWISE_IN_PROGRESS
    }

/** Stackwise `Status` string -> local status id. Unknown values fall back to in-progress. */
internal fun statusFromStackwise(status: String): Int =
    when (status) {
        STACKWISE_PLANNING -> SW_PLANNING
        STACKWISE_COMPLETED -> SW_COMPLETED
        STACKWISE_PAUSED -> SW_PAUSED
        STACKWISE_DROPPED -> SW_DROPPED
        else -> SW_IN_PROGRESS
    }

/** Epoch millis -> `yyyy-MM-dd` (UTC), or null when unset (<= 0). */
internal fun millisToStackwiseDate(millis: Long): String? =
    if (millis <= 0L) {
        null
    } else {
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
    }

/** `yyyy-MM-dd` -> epoch millis at UTC midnight, or 0 when null/blank/unparseable. */
internal fun stackwiseDateToMillis(date: String?): Long {
    if (date.isNullOrBlank()) return 0L
    return try {
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }
}

@Serializable
data class SWUser(
    val username: String = "",
)

@Serializable
data class SWSearchResult(
    val results: List<SWManga> = emptyList(),
)

@Serializable
data class SWManga(
    val id: Long,
    val title: String,
    @SerialName("totalChapters")
    val totalChapters: Int = 0,
    @SerialName("coverUrl")
    val coverUrl: String = "",
    val url: String = "",
    val summary: String = "",
    @SerialName("publishingStatus")
    val publishingStatus: String = "",
    @SerialName("startDate")
    val startDate: String = "",
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
)

@Serializable
data class SWTrackEntry(
    val id: Long,
    val title: String = "",
    val status: String = STACKWISE_IN_PROGRESS,
    val progress: Int = 0,
    val score: Double = 0.0,
    @SerialName("totalChapters")
    val totalChapters: Int = 0,
    @SerialName("startDate")
    val startDate: String? = null,
    @SerialName("endDate")
    val endDate: String? = null,
    val url: String = "",
)

fun SWManga.toTrackSearch(trackerId: Int): TrackSearch =
    TrackSearch.create(trackerId).also {
        it.remote_id = id
        it.title = title
        it.total_chapters = totalChapters
        it.cover_url = coverUrl
        it.summary = summary
        it.publishing_status = publishingStatus
        it.start_date = startDate
        it.authors = authors
        it.artists = artists
        it.tracking_url = url
    }

fun SWTrackEntry.copyTo(track: Track): Track {
    track.remote_id = id
    if (title.isNotBlank()) track.title = title
    track.status = statusFromStackwise(status)
    track.last_chapter_read = progress.toDouble()
    if (totalChapters > 0) track.total_chapters = totalChapters
    track.score = score
    track.started_reading_date = stackwiseDateToMillis(startDate)
    track.finished_reading_date = stackwiseDateToMillis(endDate)
    if (url.isNotBlank()) track.tracking_url = url
    return track
}
