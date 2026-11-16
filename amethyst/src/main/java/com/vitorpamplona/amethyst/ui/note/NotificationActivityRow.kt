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
package com.vitorpamplona.amethyst.ui.note

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbol
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.richtext.RichTextParser
import com.vitorpamplona.amethyst.commons.richtext.UrlParser
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.user.observeUserInfo
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.routeFor
import com.vitorpamplona.amethyst.ui.note.elements.TimeAgoStyle
import com.vitorpamplona.amethyst.ui.note.elements.ToggleableTimeAgoText
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.newItemBackgroundColor
import com.vitorpamplona.amethyst.ui.theme.placeholderText
import com.vitorpamplona.quartz.experimental.ephemeralStory.EphemeralStoryEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip68Picture.PictureEvent
import com.vitorpamplona.quartz.nip71Video.VideoEvent
import com.vitorpamplona.quartz.nip92IMeta.imetas
import com.vitorpamplona.quartz.nip94FileMetadata.FileHeaderEvent

private val AvatarSize = 52.dp
private val BadgeSize = 22.dp
private val BadgeIconSize = 13.dp
private val ThumbShape = RoundedCornerShape(14.dp)
private val ThumbSize = 56.dp

/**
 * The kind of activity a row represents. Each carries the glyph and tint stamped on the
 * actor's avatar, so the type of activity reads at a glance, Instagram/Facebook style.
 */
enum class NotificationActivityType(
    val symbol: MaterialSymbol,
    val tint: Color,
) {
    LIKE(MaterialSymbols.Favorite, Color(0xFFED4956)),
    COMMENT(MaterialSymbols.Chat, Color(0xFF3797F0)),
    REPOST(MaterialSymbols.Forward, Color(0xFF1DB954)),
    ZAP(MaterialSymbols.Bolt, Color(0xFFF7931A)),
    GIFT(MaterialSymbols.Star, Color(0xFFB287F5)),
    REACT(MaterialSymbols.AddReaction, Color(0xFF3797F0)),
    FOLLOW(MaterialSymbols.PersonAdd, Color(0xFF3797F0)),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationActivityRow(
    actorNote: Note,
    extraActors: Int,
    createdAt: Long?,
    activityType: NotificationActivityType,
    sentenceRes: Int,
    sentenceOthersRes: Int,
    targetNote: Note,
    accountViewModel: AccountViewModel,
    nav: INav,
    preview: String? = null,
    isUnread: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    WatchAuthorWithBlank(actorNote, accountViewModel = accountViewModel) { author ->
        if (author != null) {
            val userInfo by observeUserInfo(author, accountViewModel)
            val name = userInfo?.info?.bestName() ?: author.toBestDisplayName()
            NotificationActivityRowBody(
                name = name,
                extraActors = extraActors,
                createdAt = createdAt,
                activityType = activityType,
                sentenceRes = sentenceRes,
                sentenceOthersRes = sentenceOthersRes,
                targetNote = targetNote,
                preview = preview,
                isUnread = isUnread,
                onClick = onClick,
                onLongClick = onLongClick,
                avatar = {
                    ClickableUserPicture(
                        baseUser = author,
                        size = AvatarSize,
                        accountViewModel = accountViewModel,
                        onClick = { nav.nav(routeFor(it)) },
                    )
                },
            )
        } else {
            NotificationActivityRowBody(
                name = "…",
                extraActors = extraActors,
                createdAt = createdAt,
                activityType = activityType,
                sentenceRes = sentenceRes,
                sentenceOthersRes = sentenceOthersRes,
                targetNote = targetNote,
                preview = preview,
                isUnread = isUnread,
                onClick = onClick,
                onLongClick = onLongClick,
                avatar = { DisplayBlankAuthor(AvatarSize, accountViewModel = accountViewModel) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotificationActivityRowBody(
    name: String,
    extraActors: Int,
    createdAt: Long?,
    activityType: NotificationActivityType,
    sentenceRes: Int,
    sentenceOthersRes: Int,
    targetNote: Note,
    preview: String?,
    isUnread: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    val sentence =
        if (extraActors > 0) {
            stringRes(sentenceOthersRes, name, extraActors.toString())
        } else {
            stringRes(sentenceRes, name)
        }
    val annotated =
        remember(sentence, name) {
            buildAnnotatedString {
                val idx = sentence.indexOf(name)
                if (idx >= 0 && name.isNotBlank()) {
                    append(sentence.substring(0, idx))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name) }
                    append(sentence.substring(idx + name.length))
                } else {
                    append(sentence)
                }
            }
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (isUnread) MaterialTheme.colorScheme.newItemBackgroundColor else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarWithBadge(activityType) { avatar() }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!preview.isNullOrBlank()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (createdAt != null) {
                ToggleableTimeAgoText(
                    timestamp = createdAt,
                    style = TimeAgoStyle.Short,
                    color = MaterialTheme.colorScheme.placeholderText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        NotificationPostThumb(targetNote)
        if (isUnread) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier =
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun AvatarWithBadge(
    activityType: NotificationActivityType,
    avatar: @Composable () -> Unit,
) {
    Box {
        avatar()
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(BadgeSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(activityType.tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                symbol = activityType.symbol,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(BadgeIconSize),
            )
        }
    }
}

@Composable
private fun NotificationPostThumb(note: Note) {
    val thumb = remember(note.idHex, note.event?.id) { note.notificationThumb() }
    if (thumb == null) return
    Box(
        modifier =
            Modifier
                .size(ThumbSize)
                .clip(ThumbShape)
                .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), ThumbShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = thumb.url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (thumb.isVideo) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    symbol = MaterialSymbols.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private data class NotificationThumb(
    val url: String,
    val isVideo: Boolean,
)

private fun Note.notificationThumb(): NotificationThumb? {
    val event = event ?: return null
    when (event) {
        is PictureEvent -> {
            val imeta = event.imetaTags().firstOrNull() ?: return null
            return NotificationThumb(imeta.url, false)
        }
        is VideoEvent -> {
            val imeta = event.imetaTags().firstOrNull() ?: return null
            val poster = imeta.image.firstOrNull()
            return NotificationThumb(poster ?: imeta.url, true)
        }
        is FileHeaderEvent -> {
            val url = event.urls().firstOrNull() ?: return null
            val mime = event.mimeType()
            val isVid = mime?.startsWith("video/") == true || RichTextParser.isVideoUrl(url)
            val poster = event.image()?.imageUrl
            return NotificationThumb(poster ?: url, isVid)
        }
        is EphemeralStoryEvent -> {
            val url = event.url() ?: return null
            return NotificationThumb(url, event.isVideo())
        }
        is TextNoteEvent -> {
            val imeta = event.imetas().firstOrNull()
            if (imeta != null) {
                val mime = imeta.properties["m"]?.firstOrNull()
                val isVid = mime?.startsWith("video/") == true || RichTextParser.isVideoUrl(imeta.url)
                val poster = imeta.properties["image"]?.firstOrNull()
                return NotificationThumb(poster ?: imeta.url, isVid)
            }
            val urls = UrlParser().parseValidUrls(event.content)
            val media =
                (urls.withScheme + urls.withoutScheme).firstOrNull { RichTextParser.isImageOrVideoUrl(it) }
                    ?: return null
            return NotificationThumb(media, RichTextParser.isVideoUrl(media))
        }
        else -> return null
    }
}
