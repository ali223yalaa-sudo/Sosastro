/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.amethyst.service.call

import com.vitorpamplona.quartz.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

/**
 * Fetches TURN/STUN credentials for the built-in relay from Metered at call
 * time so that region changes and credential rotation are picked up without an
 * app update.  The credential-scoped API key is safe to ship in the client (it
 * can only mint TURN credentials, never account data), but the result is cached
 * for [CACHE_TTL_MS] to avoid a network round-trip on every call.
 *
 * When the request fails the caller falls back to the static entries in
 * [IceServerConfig.defaultTurnServers], so a network hiccup degrades gracefully
 * instead of disabling relayed calls entirely.
 */
object MeteredTurnProvider {
    private const val TAG = "MeteredTurnProvider"

    private const val CREDENTIALS_URL =
        "https://amethyst-ludo.metered.live/api/v1/turn/credentials" +
            "?apiKey=e83a90b3d28e4038919c45e37f93e647be82"

    /** Metered credentials are long-lived; refresh a few times a day at most. */
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** Small dedicated client: the fetch is one GET and must not stall a call. */
    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()

    @Serializable
    private data class IceServerDto(
        val urls: String,
        val username: String? = null,
        val credential: String? = null,
    )

    private val mutex = Mutex()

    @Volatile private var cached: List<PeerConnection.IceServer>? = null

    @Volatile private var cachedAt = 0L

    /**
     * Returns the Metered ICE servers, served from cache when fresh.  Returns
     * `null` when neither a fresh fetch nor a stale cache is available.
     */
    suspend fun fetch(): List<PeerConnection.IceServer>? =
        mutex.withLock {
            val now = System.currentTimeMillis()
            val fresh = cached
            if (fresh != null && now - cachedAt < CACHE_TTL_MS) return fresh

            val fetched = fetchRemote()
            if (fetched != null) {
                cached = fetched
                cachedAt = now
                return fetched
            }
            // Stale cache beats no TURN at all.
            fresh
        }

    private suspend fun fetchRemote(): List<PeerConnection.IceServer>? =
        try {
            val request =
                Request
                    .Builder()
                    .url(CREDENTIALS_URL)
                    .get()
                    .build()
            httpClient.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG) { "Metered credential request failed: HTTP ${response.code}" }
                    return@use null
                }
                val servers = parse(response.body.string())
                if (servers.isEmpty()) {
                    Log.w(TAG) { "Metered credential response had no usable ICE servers" }
                    null
                } else {
                    Log.d(TAG) { "Fetched ${servers.size} ICE servers from Metered" }
                    servers
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG) { "Metered credential fetch error: ${e.message}" }
            null
        }

    private fun parse(body: String): List<PeerConnection.IceServer> =
        runCatching { json.decodeFromString<List<IceServerDto>>(body) }
            .getOrElse {
                Log.w(TAG) { "Could not parse Metered credential response: ${it.message}" }
                return emptyList()
            }.mapNotNull { dto ->
                runCatching {
                    val builder = PeerConnection.IceServer.builder(dto.urls)
                    dto.username?.let { builder.setUsername(it) }
                    dto.credential?.let { builder.setPassword(it) }
                    builder.createIceServer()
                }.getOrNull()
            }
}
