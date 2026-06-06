package suwayomi.tachidesk.manga.impl.track.tracker.stackwise

import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SWManga
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SWSearchResult
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SWTrackEntry
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.SWUser
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.millisToStackwiseDate
import suwayomi.tachidesk.manga.impl.track.tracker.stackwise.dto.statusToStackwise
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.net.URLEncoder

class StackwiseApi(
    interceptor: StackwiseInterceptor,
    private val client: OkHttpClient,
) {
    private val json: Json by injectLazy()

    private val authClient by lazy {
        client
            .newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    private fun baseUrl(): String {
        val url = serverConfig.stackwiseTrackerUrl.value.trim().trimEnd('/')
        if (url.isEmpty()) {
            throw IOException("Stackwise server URL is not configured. Set 'server.stackwiseTrackerUrl'.")
        }
        return url
    }

    /** Validates [token] against the configured instance and returns the owning user. */
    suspend fun getUser(token: String): SWUser =
        with(json) {
            client
                .newCall(
                    GET(
                        url = "${baseUrl()}/api/v1/tracker/me",
                        headers = Headers.headersOf("Authorization", "Bearer $token"),
                    ),
                ).awaitSuccess()
                .parseAs<SWUser>()
        }

    suspend fun search(query: String): List<SWManga> =
        with(json) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            authClient
                .newCall(GET("${baseUrl()}/api/v1/tracker/manga/search?query=$encoded"))
                .awaitSuccess()
                .parseAs<SWSearchResult>()
                .results
        }

    suspend fun getEntry(track: Track): SWTrackEntry =
        with(json) {
            authClient
                .newCall(GET("${baseUrl()}/api/v1/tracker/manga/${track.remote_id}"))
                .awaitSuccess()
                .parseAs<SWTrackEntry>()
        }

    suspend fun addEntry(track: Track): SWTrackEntry =
        with(json) {
            authClient
                .newCall(
                    POST(
                        url = "${baseUrl()}/api/v1/tracker/manga/${track.remote_id}",
                        body = track.toRequestBody(),
                    ),
                ).awaitSuccess()
                .parseAs<SWTrackEntry>()
        }

    suspend fun updateEntry(track: Track): SWTrackEntry =
        with(json) {
            authClient
                .newCall(
                    PUT(
                        url = "${baseUrl()}/api/v1/tracker/manga/${track.remote_id}",
                        body = track.toRequestBody(),
                    ),
                ).awaitSuccess()
                .parseAs<SWTrackEntry>()
        }

    suspend fun deleteEntry(track: Track) {
        authClient
            .newCall(DELETE("${baseUrl()}/api/v1/tracker/manga/${track.remote_id}"))
            .awaitSuccess()
    }

    private fun Track.toRequestBody() =
        buildJsonObject {
            put("status", statusToStackwise(status))
            put("progress", last_chapter_read.toInt())
            // A score of 0 means "no rating"; send null so Stackwise preserves None.
            put("score", score.takeIf { it > 0.0 })
            put("startDate", millisToStackwiseDate(started_reading_date))
            put("endDate", millisToStackwiseDate(finished_reading_date))
        }.toString().toRequestBody(CONTENT_TYPE)

    companion object {
        private val CONTENT_TYPE = "application/json".toMediaType()
    }
}
