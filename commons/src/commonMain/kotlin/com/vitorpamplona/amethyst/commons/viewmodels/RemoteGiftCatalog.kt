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
package com.vitorpamplona.amethyst.commons.viewmodels

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.JsonMapper
import kotlinx.serialization.Serializable

/**
 * The operator-editable gift catalog served from the web dashboard.
 *
 * A gift's art (SVGA clip, icon, sound) is fetched and cached on the
 * device, so publishing here changes what every client shows on its
 * next launch without shipping a new APK. [version] is bumped by the
 * dashboard on every save; the client only re-applies a payload whose
 * version it has not seen, so an unchanged catalog costs one small
 * request and nothing else.
 *
 * Unknown fields are ignored and missing ones fall back to the bundled
 * catalog, so an older client degrades gracefully against a newer
 * dashboard and vice versa.
 */
@Immutable
@Serializable
data class RemoteGiftCatalog(
    /** Monotonic payload revision; the client skips equal/older. */
    val version: Int = 0,
    /**
     * Gifts, in panel order. An entry whose [RemoteGiftSku.id] matches
     * a bundled gift overrides it; a new id adds a gift.
     */
    val gifts: List<RemoteGiftSku> = emptyList(),
) {
    /**
     * Validate and project to the runtime [RoomGiftSku] shape, dropping
     * anything a client could not render (blank id, unknown tier,
     * negative price, or a non-http asset URL). A malformed entry is
     * skipped rather than failing the whole payload, so one bad row in
     * the dashboard cannot black out the catalog.
     *
     * [baseUrl] is the catalog's own URL; a relative asset path in the
     * payload (as the dashboard writes) is resolved against it, so the
     * operator never has to hardcode the host into every row.
     */
    fun toSkus(baseUrl: String? = null): List<RoomGiftSku> {
        val base = baseDirOf(baseUrl)
        return gifts.mapNotNull { gift ->
            val id = gift.id.trim()
            if (id.isEmpty() || id.length > 64) return@mapNotNull null
            if (gift.tier !in 1..4) return@mapNotNull null
            if (gift.price < 0) return@mapNotNull null
            RoomGiftSku(
                id = id,
                emoji = gift.emoji.ifBlank { "🎁" },
                labelAr = gift.labelAr.ifBlank { id },
                labelEn = gift.labelEn.ifBlank { id },
                tier = gift.tier,
                value = gift.price,
                svga = gift.svga.safeAsset(base),
                icon = gift.icon.safeAsset(base),
                sound = gift.sound.safeAsset(base),
                scale = gift.scale?.coerceIn(0.4f, 1.2f),
                remote = true,
            )
        }
    }

    companion object {
        /** Parse a payload, or `null` when it is not valid JSON of this shape. */
        fun parseOrNull(text: String): RemoteGiftCatalog? = runCatching { JsonMapper.fromJson<RemoteGiftCatalog>(text) }.getOrNull()
    }
}

/** Everything up to and including the last `/` of a URL, or `null`. */
private fun baseDirOf(url: String?): String? {
    val v = url?.trim().orEmpty()
    val i = v.lastIndexOf('/')
    return if (i > "https://".length) v.substring(0, i + 1) else null
}

/** One gift as authored in the dashboard. */
@Immutable
@Serializable
data class RemoteGiftSku(
    val id: String,
    val emoji: String = "🎁",
    val labelAr: String = "",
    val labelEn: String = "",
    /** 1 = everyday, 2 = premium, 3 = luxe, 4 = legendary. */
    val tier: Int = 1,
    /** Display price in stars; presentation only. */
    val price: Int = 1,
    /** Absolute `https://` URL of the SVGA clip, or a bundled asset path. */
    val svga: String? = null,
    /** Absolute `https://` URL of the 256² PNG icon, or a bundled asset path. */
    val icon: String? = null,
    /** Absolute `https://` URL of the sound file, or a `res/raw` name. */
    val sound: String? = null,
    /** Fraction of the screen the clip fills; `null` means 1.0. */
    val scale: Float? = null,
)

/**
 * Accept an absolute `http(s)://` URL, a relative asset path the
 * dashboard wrote (resolved against the catalog's own directory), or a
 * bare `res/raw` name; reject anything else (a `javascript:` or `file:`
 * URL from a compromised dashboard must never reach the loader).
 */
private fun String?.safeAsset(base: String? = null): String? {
    val v = this?.trim().orEmpty()
    if (v.isEmpty()) return null
    return when {
        v.startsWith("https://") || v.startsWith("http://") -> v
        // A relative path is only trusted when we know the catalog's own
        // origin. The charset guard rejects a `scheme:` smuggled in (a
        // `javascript:` payload has no `://` and would otherwise be
        // concatenated onto the base as if it were a path).
        base != null && RELATIVE_ASSET.matches(v) -> base + v
        v.startsWith("gifts/") && v.endsWith(".svga") -> v
        v.startsWith("gifts/icons/") && v.endsWith(".png") -> v
        v.startsWith("gift_") && !v.contains('/') && !v.contains('.') -> v
        else -> null
    }
}

/** A bare relative path: no scheme, no host, no traversal, no `:`. */
private val RELATIVE_ASSET = Regex("^[A-Za-z0-9][A-Za-z0-9_./-]*$")
