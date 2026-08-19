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
package com.vitorpamplona.quartz.nip01Core.metadata

import com.vitorpamplona.quartz.nip01Core.core.JsonMapper
import kotlinx.serialization.Serializable

/**
 * Free-form profile header canvas. Coordinates are fractions of the canvas width
 * (so layouts scale across screen sizes). Height of the canvas is
 * [ProfileCanvas.heightUnits] × width.
 *
 * Only elements that exist on the profile should be stored and shown — empty
 * blocks are not part of the canvas.
 */
@Serializable
data class ProfileCanvasItem(
    val id: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    /** Absolute canvas placement (legacy v1). */
    fun clamped(): ProfileCanvasItem {
        val nw = w.coerceIn(MIN_SIZE, 1f)
        val nh = h.coerceIn(MIN_SIZE, 2.5f)
        return copy(
            x = x.coerceIn(0f, (1f - nw).coerceAtLeast(0f)),
            y = y.coerceAtLeast(0f),
            w = nw,
            h = nh,
        )
    }

    /**
     * Delta from the classic layout slot (v2+).
     * [x]/[y] = offset ÷ canvas width (signed). [w]/[h] = uniform scale.
     */
    fun clampedDelta(): ProfileCanvasItem =
        copy(
            x = x.coerceIn(-3f, 3f),
            y = y.coerceIn(-3f, 3f),
            w = w.coerceIn(0.5f, 2.5f),
            h = h.coerceIn(0.5f, 2.5f),
        )

    companion object {
        const val MIN_SIZE = 0.08f
    }
}

@Serializable
data class ProfileCanvas(
    val v: Int = 1,
    val items: List<ProfileCanvasItem> = emptyList(),
) {
    fun heightUnits(): Float = items.maxOfOrNull { it.y + it.h }?.coerceAtLeast(0.5f) ?: 1f

    fun withItem(updated: ProfileCanvasItem): ProfileCanvas {
        val clamped = if (v >= DELTA_VERSION) updated.clampedDelta() else updated.clamped()
        return copy(items = items.map { if (it.id == clamped.id) clamped else it })
    }

    companion object {
        const val BANNER = "banner"
        const val AVATAR = "avatar"
        const val ACTIONS = "actions"
        const val FOLLOWERS = "followers"
        const val FOLLOWING = "following"

        /** v2+ stores signed deltas from classic slots, not absolute canvas coords. */
        const val DELTA_VERSION = 2

        /** Approximate classic Amethyst header, in width-fraction units (legacy v1). */
        val DEFAULT: ProfileCanvas =
            ProfileCanvas(
                items =
                    listOf(
                        ProfileCanvasItem(BANNER, 0f, 0f, 1f, 0.42f),
                        ProfileCanvasItem(AVATAR, 0.03f, 0.28f, 0.28f, 0.28f),
                        ProfileCanvasItem(FOLLOWERS, 0.40f, 0.32f, 0.28f, 0.12f),
                        ProfileCanvasItem(FOLLOWING, 0.70f, 0.32f, 0.28f, 0.12f),
                        ProfileCanvasItem(ACTIONS, 0.40f, 0.48f, 0.57f, 0.10f),
                        ProfileCanvasItem(ProfileLayoutBlock.DISPLAY_NAME.id, 0.03f, 0.58f, 0.94f, 0.09f),
                        ProfileCanvasItem(ProfileLayoutBlock.PROFESSION.id, 0.03f, 0.67f, 0.94f, 0.05f),
                        ProfileCanvasItem(ProfileLayoutBlock.USERNAME.id, 0.03f, 0.72f, 0.94f, 0.05f),
                        ProfileCanvasItem(ProfileLayoutBlock.NPUB.id, 0.03f, 0.77f, 0.94f, 0.05f),
                        ProfileCanvasItem(ProfileLayoutBlock.NPROFILE.id, 0.03f, 0.82f, 0.94f, 0.06f),
                        ProfileCanvasItem(ProfileLayoutBlock.LAST_SEEN.id, 0.03f, 0.88f, 0.94f, 0.05f),
                        ProfileCanvasItem(ProfileLayoutBlock.NIP05.id, 0.03f, 0.93f, 0.94f, 0.05f),
                        ProfileCanvasItem(ProfileLayoutBlock.WEBSITE.id, 0.03f, 0.98f, 0.94f, 0.05f),
                        ProfileCanvasItem(ProfileLayoutBlock.IDENTITIES.id, 0.03f, 1.03f, 0.94f, 0.08f),
                        ProfileCanvasItem(ProfileLayoutBlock.PAYMENT.id, 0.03f, 1.11f, 0.94f, 0.08f),
                        ProfileCanvasItem(ProfileLayoutBlock.BADGES.id, 0.03f, 1.19f, 0.94f, 0.10f),
                        ProfileCanvasItem(ProfileLayoutBlock.ABOUT.id, 0.03f, 1.29f, 0.94f, 0.18f),
                        ProfileCanvasItem(ProfileLayoutBlock.APP_RECOMMENDATIONS.id, 0.03f, 1.47f, 0.94f, 0.12f),
                    ),
            )

        private val defaultById = DEFAULT.items.associateBy { it.id }

        fun parse(raw: String?): ProfileCanvas? {
            if (raw.isNullOrBlank()) return null
            return try {
                val parsed = JsonMapper.fromJson<ProfileCanvas>(raw)
                val version = parsed.v.coerceAtLeast(1)
                ProfileCanvas(
                    v = version,
                    items =
                        parsed.items
                            .map { if (version >= DELTA_VERSION) it.clampedDelta() else it.clamped() }
                            .distinctBy { it.id },
                )
            } catch (_: Exception) {
                null
            }
        }

        fun serialize(canvas: ProfileCanvas): String = JsonMapper.toJson(canvas)

        /**
         * Build a canvas that only contains [visibleIds], keeping saved positions when present.
         */
        fun forVisibleElements(
            saved: ProfileCanvas?,
            visibleIds: Collection<String>,
        ): ProfileCanvas {
            val savedById = saved?.items?.associateBy { it.id }.orEmpty()
            val version = saved?.v?.coerceAtLeast(1) ?: 1
            val items =
                visibleIds.mapNotNull { id ->
                    val item = savedById[id] ?: defaultById[id] ?: return@mapNotNull null
                    if (version >= DELTA_VERSION && savedById[id] != null) {
                        item.clampedDelta()
                    } else if (version >= DELTA_VERSION) {
                        // No saved delta — identity delta at classic slot.
                        ProfileCanvasItem(id, 0f, 0f, 1f, 1f)
                    } else {
                        item.clamped()
                    }
                }
            return ProfileCanvas(v = version, items = items)
        }
    }
}
