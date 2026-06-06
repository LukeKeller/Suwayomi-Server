package suwayomi.tachidesk.manga.impl.track.tracker.stackwise

import okhttp3.Interceptor
import okhttp3.Response
import suwayomi.tachidesk.server.generated.BuildConfig
import java.io.IOException

class StackwiseInterceptor(
    stackwise: Stackwise,
) : Interceptor {
    private var token: String? = stackwise.restoreSession()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val token = token ?: throw IOException("Not authenticated with Stackwise")

        val authRequest =
            originalRequest
                .newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .header("User-Agent", "Suwayomi ${BuildConfig.VERSION} (${BuildConfig.REVISION})")
                .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(token: String?) {
        this.token = token
    }
}
