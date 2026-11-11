package com.neilturner.aerialviews.providers.custom

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Attach credentials only to the selected library's frame image endpoint. */
internal class KrillImageAuthInterceptor(
    private val credential: () -> Pair<String, String>?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val parts = request.url.pathSegments
        val isFramePath =
            parts.size == 5 && parts[0] == "api" && parts[1] == "frame" &&
                parts[2] == "media" && parts[3].isNotEmpty() && parts[4] == "image"
        if (!isFramePath) return chain.proceed(request)
        val (origin, token) = credential() ?: return chain.proceed(request)
        val base = origin.toHttpUrl()
        val target = request.url
        val isImage = isFramePath && target.scheme == base.scheme && target.host == base.host && target.port == base.port
        if (!isImage) return chain.proceed(request)
        val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        if (response.isRedirect) {
            response.close()
            throw IOException("Krill image redirects are not allowed")
        }
        return response
    }
}
