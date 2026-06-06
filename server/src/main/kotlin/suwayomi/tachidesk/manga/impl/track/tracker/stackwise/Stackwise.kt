package suwayomi.tachidesk.manga.impl.track.tracker.stackwise

import suwayomi.tachidesk.manga.impl.track.tracker.DeletableTracker
import suwayomi.tachidesk.manga.impl.track.tracker.Tracker
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.model.TrackSearch
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_COMPLETED
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_DROPPED
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_IN_PROGRESS
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_PAUSED
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SW_PLANNING
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.copyTo
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.toTrackSearch
import suwayomi.tachidesk.server.serverConfig
import java.io.IOException

/**
 * Tracker for [Stackwise](https://github.com/LukeKeller/stackwise_ynh), a self-hosted media
 * tracker. Unlike the cloud trackers, the instance URL is operator-configured via the
 * `server.stackwiseTrackerUrl` setting; authentication uses the user's Stackwise API token,
 * entered as the password on the standard tracker login form.
 */
class Stackwise(
    id: Int,
) : Tracker(id, "Stackwise"),
    DeletableTracker {
    companion object {
        // Scores are decimals out of 10 (Stackwise stores score as a 0-10 decimal).
        private val SCORE_LIST =
            (0..10)
                .flatMap { decimal ->
                    when (decimal) {
                        0 -> listOf("-")
                        10 -> listOf("10.0")
                        else -> (0..9).map { fraction -> "$decimal.$fraction" }
                    }
                }
    }

    private val interceptor by lazy { StackwiseInterceptor(this) }

    private val api by lazy { StackwiseApi(interceptor, client) }

    override val supportsReadingDates: Boolean = true

    override fun getLogo(): String = "/static/tracker/stackwise.png"

    override fun getStatusList(): List<Int> = listOf(SW_IN_PROGRESS, SW_PLANNING, SW_COMPLETED, SW_PAUSED, SW_DROPPED)

    override fun getStatus(status: Int): String? =
        when (status) {
            SW_IN_PROGRESS -> "In progress"
            SW_PLANNING -> "Planning"
            SW_COMPLETED -> "Completed"
            SW_PAUSED -> "Paused"
            SW_DROPPED -> "Dropped"
            else -> null
        }

    override fun getReadingStatus(): Int = SW_IN_PROGRESS

    override fun getRereadingStatus(): Int = -1

    override fun getCompletionStatus(): Int = SW_COMPLETED

    override fun getScoreList(): List<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double = if (index == 0) 0.0 else SCORE_LIST[index].toDouble()

    override fun displayScore(track: Track): String = if (track.score <= 0.0) "-" else track.score.toString()

    override suspend fun update(
        track: Track,
        didReadChapter: Boolean,
    ): Track {
        if (track.status != SW_COMPLETED && didReadChapter) {
            track.status = SW_IN_PROGRESS
        }
        return api.updateEntry(track).copyTo(track)
    }

    override suspend fun bind(
        track: Track,
        hasReadChapters: Boolean,
    ): Track =
        try {
            api.getEntry(track).copyTo(track)
        } catch (_: Exception) {
            track.status = if (hasReadChapters) SW_IN_PROGRESS else SW_PLANNING
            if (hasReadChapters && track.last_chapter_read == 0.0) {
                track.last_chapter_read = 1.0
            }
            api.addEntry(track).copyTo(track)
        }

    override suspend fun search(query: String): List<TrackSearch> = api.search(query).map { it.toTrackSearch(id) }

    override suspend fun refresh(track: Track): Track = api.getEntry(track).copyTo(track)

    override suspend fun delete(track: Track) {
        api.deleteEntry(track)
    }

    override suspend fun login(
        username: String,
        password: String,
    ) {
        if (serverConfig.stackwiseTrackerUrl.value.isBlank()) {
            throw IOException("Set the Stackwise server URL (server.stackwiseTrackerUrl) before logging in.")
        }
        val token = password.ifBlank { throw IOException("A Stackwise API token is required (enter it as the password).") }
        val user = api.getUser(token)
        saveCredentials(user.username.ifBlank { username.ifBlank { name } }, token)
        interceptor.newAuth(token)
    }

    override val isLoggedIn: Boolean
        get() = restoreSession() != null && serverConfig.stackwiseTrackerUrl.value.isNotBlank()

    fun restoreSession(): String? = trackPreferences.getTrackPassword(this)?.ifBlank { null }
}
