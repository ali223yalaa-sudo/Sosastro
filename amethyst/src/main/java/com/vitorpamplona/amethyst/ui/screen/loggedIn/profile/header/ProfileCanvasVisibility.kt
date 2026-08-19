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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.header

import com.vitorpamplona.amethyst.commons.model.nip01Core.UserInfo
import com.vitorpamplona.quartz.nip01Core.metadata.ProfileCanvas
import com.vitorpamplona.quartz.nip01Core.metadata.ProfileLayoutBlock
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata

/**
 * Only profile elements that currently have something to show.
 */
fun visibleProfileCanvasIds(
    info: UserMetadata,
    identitiesEmpty: Boolean,
    showBadges: Boolean,
    showAppRecommendations: Boolean,
): List<String> {
    val ids = LinkedHashSet<String>()
    ids += ProfileCanvas.AVATAR
    ids += ProfileCanvas.ACTIONS
    ids += ProfileCanvas.FOLLOWERS
    ids += ProfileCanvas.FOLLOWING

    if (!info.bestName().isNullOrBlank()) ids += ProfileLayoutBlock.DISPLAY_NAME.id
    if (!info.profession.isNullOrBlank()) ids += ProfileLayoutBlock.PROFESSION.id
    val displayName = info.bestName()
    if (displayName != info.name && !info.name.isNullOrBlank()) ids += ProfileLayoutBlock.USERNAME.id
    ids += ProfileLayoutBlock.NPUB.id
    ids += ProfileLayoutBlock.NPROFILE.id
    ids += ProfileLayoutBlock.LAST_SEEN.id
    if (!info.nip05.isNullOrBlank()) ids += ProfileLayoutBlock.NIP05.id
    if (!info.website.isNullOrBlank()) ids += ProfileLayoutBlock.WEBSITE.id
    if (!identitiesEmpty) ids += ProfileLayoutBlock.IDENTITIES.id
    if (!info.lud16.isNullOrBlank() || !info.lud06.isNullOrBlank() || !info.clinkOffer.isNullOrBlank()) {
        ids += ProfileLayoutBlock.PAYMENT.id
    }
    if (showBadges) ids += ProfileLayoutBlock.BADGES.id
    if (!info.about.isNullOrBlank()) ids += ProfileLayoutBlock.ABOUT.id

    return ids.toList()
}

fun resolveDisplayCanvas(
    user: UserInfo,
    identitiesEmpty: Boolean,
    showBadges: Boolean,
    showAppRecommendations: Boolean,
): ProfileCanvas {
    val visible =
        visibleProfileCanvasIds(
            info = user.info,
            identitiesEmpty = identitiesEmpty,
            showBadges = showBadges,
            showAppRecommendations = showAppRecommendations,
        )
    return ProfileCanvas.forVisibleElements(user.info.resolvedProfileCanvas(), visible)
}

/**
 * Whether [id] should appear on the live profile.
 * No saved canvas → show defaults. Saved canvas → only listed items (e.g. banner omitted = hidden).
 */
fun ProfileCanvas?.includesLayoutItem(id: String): Boolean = this == null || items.any { it.id == id }

/** Pixel deltas from classic layout slots, derived from a saved [ProfileCanvas]. */
data class ProfileLayoutDelta(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
) {
    fun isIdentity(): Boolean = kotlin.math.abs(x) < 0.5f && kotlin.math.abs(y) < 0.5f && kotlin.math.abs(scale - 1f) < 0.01f
}

fun layoutDeltasFromCanvas(
    canvas: ProfileCanvas?,
    widthPx: Float,
): Map<String, ProfileLayoutDelta> {
    if (canvas == null || widthPx < 1f) return emptyMap()
    return if (canvas.v >= ProfileCanvas.DELTA_VERSION) {
        // v2+: x/y are signed delta÷width, w is scale — exact round-trip with editor.
        canvas.items.associate { item ->
            item.id to
                ProfileLayoutDelta(
                    x = item.x * widthPx,
                    y = item.y * widthPx,
                    scale = item.w.coerceIn(0.5f, 2.5f),
                )
        }
    } else {
        // Legacy v1 absolute coords vs DEFAULT (best-effort).
        val defaults = ProfileCanvas.DEFAULT.items.associateBy { it.id }
        canvas.items.associate { item ->
            val base = defaults[item.id]
            if (base == null || base.w < 0.001f) {
                item.id to ProfileLayoutDelta()
            } else {
                item.id to
                    ProfileLayoutDelta(
                        x = (item.x - base.x) * widthPx,
                        y = (item.y - base.y) * widthPx,
                        scale = (item.w / base.w).coerceIn(0.5f, 2.5f),
                    )
            }
        }
    }
}
