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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.shorts.datasource

import com.vitorpamplona.amethyst.commons.richtext.RichTextParser
import com.vitorpamplona.amethyst.ui.screen.loggedIn.video.dal.SupportedContent
import com.vitorpamplona.quartz.experimental.ephemeralStory.EphemeralStoryEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip71Video.VideoEvent
import com.vitorpamplona.quartz.nip71Video.VideoHorizontalEvent
import com.vitorpamplona.quartz.nip71Video.VideoNormalEvent
import com.vitorpamplona.quartz.nip71Video.VideoShortEvent
import com.vitorpamplona.quartz.nip71Video.VideoVerticalEvent
import com.vitorpamplona.quartz.nip92IMeta.imetas
import com.vitorpamplona.quartz.nip94FileMetadata.tags.DimensionTag

/**
 * Reels = videos from Stories (NIP-71) + video posts (kind:1 with video imeta/URL).
 * Pictures stay in Stories only.
 */
val ShortsVideoKinds =
    listOf(
        VideoNormalEvent.KIND,
        VideoShortEvent.KIND,
        VideoHorizontalEvent.KIND,
        VideoVerticalEvent.KIND,
    )

/** Relay REQ kinds: Stories videos + post notes (client filters notes down to video-only). */
val ShortsRelayKinds = ShortsVideoKinds + TextNoteEvent.KIND

val ShortsVideoKindsStr = ShortsVideoKinds.map { it.toString() }

private val SHORTS_VIDEO_MIME_TYPES =
    setOf(
        "video/mp4",
        "video/mpeg",
        "video/webm",
        "application/vnd.apple.mpegurl",
        "application/x-mpegURL",
        "application/vnd.apple.mpegurl",
        "hls",
    )

val shortsVideoSupport =
    SupportedContent(
        blockedUrls = listOf("youtu.be", "youtube.com"),
        mimeTypes = SHORTS_VIDEO_MIME_TYPES,
        supportedFileExtensions = RichTextParser.videoExtensions.toSet(),
    )

data class ReelVideoMeta(
    val url: String,
    val mimeType: String?,
    val hash: String? = null,
    val blurhash: String? = null,
    val thumbhash: String? = null,
    val dim: DimensionTag? = null,
    val artworkUri: String? = null,
    val description: String? = null,
)

fun Event.isReelPlayableVideo(): Boolean = firstReelVideoMeta() != null

fun Event.firstReelVideoMeta(): ReelVideoMeta? {
    when (this) {
        is VideoEvent -> {
            for (imeta in imetaTags()) {
                if (isReelVideoCandidate(imeta.url, imeta.mimeType)) {
                    return ReelVideoMeta(
                        url = imeta.url,
                        mimeType = imeta.mimeType,
                        hash = imeta.hash,
                        blurhash = imeta.blurhash,
                        thumbhash = imeta.thumbhash,
                        dim = imeta.dimension,
                        artworkUri = imeta.image.firstOrNull(),
                        description = content.ifEmpty { null } ?: imeta.alt,
                    )
                }
            }
            return null
        }
        is TextNoteEvent -> {
            for (imeta in imetas()) {
                val mime = imeta.properties["m"]?.firstOrNull()
                if (isReelVideoCandidate(imeta.url, mime)) {
                    return ReelVideoMeta(
                        url = imeta.url,
                        mimeType = mime,
                        hash = imeta.properties["x"]?.firstOrNull(),
                        blurhash = imeta.properties["blurhash"]?.firstOrNull(),
                        thumbhash = imeta.properties["thumbhash"]?.firstOrNull(),
                        dim = imeta.properties["dim"]?.firstOrNull()?.let { DimensionTag.parse(it) },
                        artworkUri = imeta.properties["image"]?.firstOrNull(),
                        description = content.ifBlank { null },
                    )
                }
            }
            // Fallback: bare video URL in content (no imeta).
            val urlMatch = Regex("""https?://\S+""").findAll(content)
            for (match in urlMatch) {
                val url = match.value.trimEnd(')', ']', '.', ',', '"', '\'')
                if (isReelVideoCandidate(url, null)) {
                    return ReelVideoMeta(url = url, mimeType = null, description = content.ifBlank { null })
                }
            }
            return null
        }
        is EphemeralStoryEvent -> {
            if (!isVideo()) return null
            val mediaUrl = url() ?: return null
            return ReelVideoMeta(
                url = mediaUrl,
                mimeType = mimeType(),
                hash = hash(),
                description = content.ifBlank { null },
            )
        }
        else -> return null
    }
}

fun Event.reelPrimaryVideoUrl(): String? = firstReelVideoMeta()?.url

private fun isReelVideoCandidate(
    url: String,
    mimeType: String?,
): Boolean {
    if (RichTextParser.isImageUrl(url) || mimeType?.startsWith("image/") == true) return false
    if (mimeType != null &&
        (
            mimeType.startsWith("video/") ||
                mimeType.contains("mpegurl", ignoreCase = true) ||
                mimeType.equals("hls", ignoreCase = true)
        )
    ) {
        return shortsVideoSupport.blockedUrls.none { url.contains(it) }
    }
    return shortsVideoSupport.acceptableUrl(url, mimeType) &&
        (RichTextParser.isVideoUrl(url) || mimeType?.startsWith("video/") == true)
}
