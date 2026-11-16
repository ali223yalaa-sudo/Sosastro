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
import com.vitorpamplona.amethyst.commons.viewmodels.RemoteGiftCatalog
import com.vitorpamplona.amethyst.commons.viewmodels.RoomGiftCatalog
import com.vitorpamplona.amethyst.model.privacyOptions.IRoleBasedHttpClientBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Fetches the operator-editable gift catalog and installs it over the
 * bundled one.
 *
 * Two-tier resilience: the last payload that parsed is cached on disk
 * and re-applied on launch before any network call, so a cold start
 * with no connectivity still shows whatever the dashboard last
 * published; the bundled catalog is the final fallback. A fetch that
 * fails, times out or returns junk leaves the cached layer untouched.
 *
 * The URL is a build constant, so this is a single small GET per
 * launch. Nothing here is user-specific — the payload is public art
 * metadata and carries no credentials.
 */
object RemoteGiftStore {
    private const val TAG = "RemoteGiftStore"
    private const val CACHE_FILE = "gifts.json"
    private const val CACHE_DIR = "gift-catalog"

    /**
     * Absolute URL of the published catalog. Blank disables the remote
     * layer entirely, which is how the F-Droid build stays hermetic:
     * nothing is fetched unless a distribution explicitly opts in.
     */
    var catalogUrl: String = ""

    private fun cacheFile(context: Context): File = File(File(context.filesDir, CACHE_DIR).apply { mkdirs() }, CACHE_FILE)

    /**
     * Apply the on-disk payload, then refresh from the network. Safe to
     * call once per process; the in-memory catalog is a Compose state
     * map, so a successful refresh recomposes the panel and the overlay
     * without any further wiring.
     */
    suspend fun load(
        context: Context,
        clientBuilder: IRoleBasedHttpClientBuilder,
    ) {
        withContext(Dispatchers.IO) {
            applyCached(context)
            if (catalogUrl.isBlank()) return@withContext
            runCatching { fetch(context, clientBuilder) }
                .onFailure { Log.w(TAG, "gift catalog refresh failed", it) }
        }
    }

    private fun applyCached(context: Context) {
        runCatching {
            val file = cacheFile(context)
            if (!file.exists() || file.length() == 0L) return
            RemoteGiftCatalog.parseOrNull(file.readText())?.let { RoomGiftCatalog.setRemote(it.toSkus(catalogUrl)) }
        }.onFailure { Log.w(TAG, "cached gift catalog unreadable", it) }
    }

    private fun fetch(
        context: Context,
        clientBuilder: IRoleBasedHttpClientBuilder,
    ) {
        val url = catalogUrl
        val client = clientBuilder.okHttpClientForPreview(url)
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "gift catalog HTTP ${resp.code}")
                return
            }
            val body = resp.body.string()
            val catalog = RemoteGiftCatalog.parseOrNull(body) ?: return
            // Write only after a successful parse, so a truncated or
            // garbled response never replaces a good cache.
            cacheFile(context).writeText(body)
            RoomGiftCatalog.setRemote(catalog.toSkus(url))
        }
    }
}
