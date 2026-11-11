package com.neilturner.aerialviews.providers.custom

import com.neilturner.aerialviews.data.network.JsonHelper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Clerk device authorization; credentials are returned to the caller, never logged. */
internal class KrillDeviceAuth(
    client: OkHttpClient,
    issuer: String,
    private val clientId: String,
) {
    // Never forward device codes, refresh tokens, or client requests through redirects.
    private val client =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val issuerUrl = issuer.toHttpUrl()

    init {
        require(issuerUrl.scheme == "https" || issuerUrl.host == "localhost") { "Issuer must use HTTPS" }
        require(issuerUrl.username.isEmpty() && issuerUrl.password.isEmpty()) { "Invalid issuer" }
        require(clientId.isNotBlank()) { "OAuth client ID is required" }
    }

    @Serializable
    private data class Discovery(
        @SerialName("device_authorization_endpoint") val deviceEndpoint: String,
        @SerialName("token_endpoint") val tokenEndpoint: String,
    )

    @Serializable
    data class DeviceCode(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
        @SerialName("expires_in") val expiresIn: Int,
        val interval: Int = 5,
    ) {
        override fun toString(): String = "DeviceCode(userCode=$userCode, expiresIn=$expiresIn, interval=$interval)"
    }

    @Serializable
    data class Tokens(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int,
        @SerialName("token_type") val tokenType: String,
    ) {
        override fun toString(): String = "Tokens(expiresIn=$expiresIn, tokenType=$tokenType)"
    }

    @Serializable
    private data class OAuthError(
        val error: String,
    )

    sealed interface PollResult {
        data class Authorized(
            val tokens: Tokens,
        ) : PollResult

        data object Pending : PollResult

        data object SlowDown : PollResult

        data class Rejected(
            val error: String,
        ) : PollResult
    }

    private var discovery: Discovery? = null

    private fun endpoint(url: String): HttpUrl {
        val result = url.toHttpUrl()
        require(result.scheme == issuerUrl.scheme && result.host == issuerUrl.host && result.port == issuerUrl.port) {
            "OAuth endpoint is outside the issuer origin"
        }
        require(result.username.isEmpty() && result.password.isEmpty() && result.fragment == null) { "Invalid OAuth endpoint" }
        return result
    }

    private fun discover(): Discovery {
        discovery?.let { return it }
        val url =
            issuerUrl
                .newBuilder()
                .encodedPath("/.well-known/oauth-authorization-server")
                .query(null)
                .fragment(null)
                .build()
        val result =
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("OAuth discovery HTTP ${response.code}")
                JsonHelper.json.decodeFromString<Discovery>(response.body.string())
            }
        endpoint(result.deviceEndpoint)
        endpoint(result.tokenEndpoint)
        discovery = result
        return result
    }

    fun start(): DeviceCode {
        val body =
            FormBody
                .Builder()
                .add("client_id", clientId)
                .add("scope", "openid email offline_access")
                .build()
        val request =
            Request
                .Builder()
                .url(endpoint(discover().deviceEndpoint))
                .post(body)
                .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Device authorization HTTP ${response.code}")
            JsonHelper.json.decodeFromString<DeviceCode>(response.body.string()).also {
                require(it.deviceCode.isNotBlank() && it.userCode.isNotBlank() && it.expiresIn > 0 && it.interval > 0) {
                    "Invalid device authorization response"
                }
                val verification = it.verificationUri.toHttpUrl()
                require(
                    (verification.scheme == "https" || verification.host == "localhost") &&
                        verification.username.isEmpty() && verification.password.isEmpty(),
                ) { "Invalid verification URL" }
                it.verificationUriComplete?.toHttpUrl()?.let { complete ->
                    require(
                        (complete.scheme == "https" || complete.host == "localhost") &&
                            complete.host == verification.host && complete.port == verification.port &&
                            complete.username.isEmpty() && complete.password.isEmpty(),
                    ) { "Invalid complete verification URL" }
                }
            }
        }
    }

    fun poll(deviceCode: String): PollResult =
        tokenRequest(
            FormBody
                .Builder()
                .add("client_id", clientId)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", deviceCode)
                .build(),
        )

    fun refresh(refreshToken: String): PollResult =
        tokenRequest(
            FormBody
                .Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build(),
        )

    private fun tokenRequest(body: FormBody): PollResult {
        val request =
            Request
                .Builder()
                .url(endpoint(discover().tokenEndpoint))
                .post(body)
                .build()
        return client.newCall(request).execute().use { response ->
            val data = response.body.string()
            if (response.isSuccessful) {
                val tokens = JsonHelper.json.decodeFromString<Tokens>(data)
                require(tokens.accessToken.isNotBlank() && tokens.expiresIn > 0 && tokens.tokenType.equals("bearer", true)) {
                    "Invalid OAuth token response"
                }
                PollResult.Authorized(tokens)
            } else {
                val error = runCatching { JsonHelper.json.decodeFromString<OAuthError>(data).error }.getOrDefault("http_${response.code}")
                when (error) {
                    "authorization_pending" -> PollResult.Pending
                    "slow_down" -> PollResult.SlowDown
                    else -> PollResult.Rejected(error)
                }
            }
        }
    }
}
