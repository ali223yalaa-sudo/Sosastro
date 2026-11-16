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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.gifts

import android.content.Context
import android.util.Log
import com.vitorpamplona.amethyst.model.privacyOptions.IRoleBasedHttpClientBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Where a gift asset lives. The catalog stores one string per asset and
 * this is the single place that decides whether it is a bundled file or
 * a remote URL, so the panel, the overlay and the sound pool never
 * branch on it themselves.
 */
sealed interface GiftAsset {
    data class Bundled(
        val path: String,
    ) : GiftAsset

    data class Remote(
        val url: String,
    ) : GiftAsset
}

fun giftAssetOf(raw: String?): GiftAsset? {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty()) return null
    return if (v.startsWith("https://") || v.startsWith("http://")) GiftAsset.Remote(v) else GiftAsset.Bundled(v)
}

/**
 * Downloads a gift asset to the app cache and returns the local file,
 * reusing an existing copy keyed by the URL's SHA-256. Mirrors
 * `RoomFontLoader`'s scheme: the URL is the cache key, so a dashboard
 * republish to the same URL is a cache hit and a new URL is a fresh
 * download — which is why the dashboard writes content-addressed
 * filenames.
 *
 * Returns `null` on any failure so the caller falls back to the
 * bundled asset or the emoji glyph rather than showing a broken burst.
 */
object GiftAssetDownloader {
    private const val TAG = "GiftAssetDownloader"
    private const val CACHE_DIR = "gift-assets"

    suspend fun localFileOrNull(
        context: Context,
        url: String,
        clientBuilder: IRoleBasedHttpClientBuilder,
        extension: String,
    ): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
                val file = File(cacheDir, hashName(url) + extension)
                if (file.exists() && file.length() > 0L) return@runCatching file

                // Download to a temp file and rename, so a killed
                // process never leaves a half-written asset that the
                // next launch would treat as a cache hit.
                val tmp = File(cacheDir, file.name + ".part")
                val client = clientBuilder.okHttpClientForImage(url)
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "HTTP ${resp.code} for $url")
                        return@runCatching null
                    }
                    tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return@runCatching null
                }
                file.takeIf { it.length() > 0L }
            }.getOrNull()
        }

    private fun hashName(url: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) append(String.format("%02x", b))
        }
    }
}
