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

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.model.nip01Core.UserInfo
import com.vitorpamplona.amethyst.commons.model.nip05DnsIdentifiers.Nip05State
import com.vitorpamplona.amethyst.model.User
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.user.observeUserInfo
import com.vitorpamplona.amethyst.ui.components.CreateTextWithEmoji
import com.vitorpamplona.amethyst.ui.components.TranslatableRichTextViewer
import com.vitorpamplona.amethyst.ui.components.util.LongPressCopyText
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.note.DrawPlayName
import com.vitorpamplona.amethyst.ui.note.ObserveAndRenderNIP05VerifiedSymbol
import com.vitorpamplona.amethyst.ui.note.lastSeenSentence
import com.vitorpamplona.amethyst.ui.painterRes
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.header.apps.UserAppRecommendationsFeedViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.header.badges.DisplayBadges
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.header.identity.UserExternalIdentitiesViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.stars.GiftSupportSheet
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.Size15Modifier
import com.vitorpamplona.amethyst.ui.theme.SpacedBy3dp
import com.vitorpamplona.amethyst.ui.theme.SpacedBy5dp
import com.vitorpamplona.amethyst.ui.theme.StdHorzSpacer
import com.vitorpamplona.amethyst.ui.theme.placeholderText
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.ProfileLayoutBlock
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip39ExtIdentities.GitHubIdentity
import com.vitorpamplona.quartz.nip39ExtIdentities.IdentityClaimTag
import com.vitorpamplona.quartz.nip39ExtIdentities.MastodonIdentity
import com.vitorpamplona.quartz.nip39ExtIdentities.TelegramIdentity
import com.vitorpamplona.quartz.nip39ExtIdentities.TwitterIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon as M3Icon

private const val IDENTITY_ICON_CACHE_KEY = 0

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrawAdditionalInfo(
    baseUser: User,
    appRecommendations: UserAppRecommendationsFeedViewModel,
    externalIdentities: UserExternalIdentitiesViewModel,
    accountViewModel: AccountViewModel,
    nav: INav,
    wrapItem: @Composable (blockId: String, content: @Composable () -> Unit) -> Unit =
        { _, content -> content() },
) {
    val userState by observeUserInfo(baseUser, accountViewModel)
    val user = userState
    if (user == null) {
        // Metadata not loaded yet — still show a name so the header keeps height for scroll.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = SpacedBy3dp) {
            Text(
                text = baseUser.toBestDisplayName(),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        return
    }
    val layout = remember(user.info.profileLayout) { user.info.resolvedProfileLayout() }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = SpacedBy3dp) {
        layout.forEach { block ->
            wrapItem(block.id) {
                RenderProfileLayoutBlock(
                    block = block,
                    baseUser = baseUser,
                    user = user,
                    appRecommendations = appRecommendations,
                    externalIdentities = externalIdentities,
                    accountViewModel = accountViewModel,
                    nav = nav,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenderProfileLayoutBlock(
    block: ProfileLayoutBlock,
    baseUser: User,
    user: UserInfo,
    appRecommendations: UserAppRecommendationsFeedViewModel,
    externalIdentities: UserExternalIdentitiesViewModel,
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val identities by externalIdentities.identities.collectAsStateWithLifecycle()
    val displayName = user.info.bestName()

    val ui = accountViewModel.settings.uiSettingsFlow
    val showBadges by ui.showProfileBadges.collectAsStateWithLifecycle()

    when (block) {
        ProfileLayoutBlock.DISPLAY_NAME -> {
            if (displayName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 7.dp),
                ) {
                    CreateTextWithEmoji(
                        text = displayName,
                        tags = user.tags,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                    Spacer(StdHorzSpacer)
                    user.info.pronouns?.let {
                        Text(
                            text = "($it)",
                            color = MaterialTheme.colorScheme.placeholderText,
                            fontSize = 14.sp,
                        )
                        Spacer(StdHorzSpacer)
                    }

                    DrawPlayName(displayName)
                }
            }
        }

        ProfileLayoutBlock.PROFESSION -> {
            user.info.profession?.takeIf { it.isNotBlank() }?.let { profession ->
                Text(
                    color = MaterialTheme.colorScheme.placeholderText,
                    text = profession,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ProfileLayoutBlock.USERNAME -> {
            if (displayName != user.info.name && !user.info.name.isNullOrBlank()) {
                Text(
                    color = MaterialTheme.colorScheme.placeholderText,
                    text = "@" + user.info.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ProfileLayoutBlock.NPUB -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = baseUser.pubkeyDisplayHex(),
                    color = MaterialTheme.colorScheme.placeholderText,
                    maxLines = 1,
                )

                IconButton(
                    modifier = Modifier.size(23.dp).padding(start = 5.dp),
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("npub", baseUser.pubkeyNpub())))
                        }
                    },
                ) {
                    Icon(
                        symbol = MaterialSymbols.ContentCopy,
                        contentDescription = stringRes(id = R.string.copy_npub_to_clipboard),
                        modifier = Size15Modifier,
                        tint = MaterialTheme.colorScheme.placeholderText,
                    )
                }

                IconButton(
                    modifier = Modifier.size(23.dp),
                    onClick = { nav.nav(Route.QRDisplay(baseUser.pubkeyHex)) },
                ) {
                    M3Icon(
                        painter = painterRes(R.drawable.ic_qrcode, 1),
                        contentDescription = stringRes(id = R.string.show_nprofile_as_a_qr_code),
                        modifier = Size15Modifier,
                        tint = MaterialTheme.colorScheme.placeholderText,
                    )
                }
            }
        }

        ProfileLayoutBlock.NPROFILE -> {
            // Hidden from UI: npub is the single public identifier shown to users.
            // nprofile remains in saved layout order for compatibility with profile editor.
        }

        ProfileLayoutBlock.LAST_SEEN -> {
            DisplayLastSeen(baseUser, accountViewModel)
        }

        ProfileLayoutBlock.NIP05 -> {
            DisplayNip05ProfileStatus(baseUser, accountViewModel)
        }

        ProfileLayoutBlock.WEBSITE -> {
            val website = user.info.website
            if (!website.isNullOrEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        tint = MaterialTheme.colorScheme.placeholderText,
                        symbol = MaterialSymbols.Link,
                        contentDescription = stringRes(R.string.website),
                        modifier = Modifier.size(18.dp),
                    )

                    LongPressCopyText(
                        displayText = website.removePrefix("https://").removePrefix("http://").removeSuffix("/"),
                        copyValue = website,
                        onClick = {
                            runCatching {
                                if (website.contains("://")) {
                                    uri.openUri(website)
                                } else {
                                    uri.openUri("http://$website")
                                }
                            }
                        },
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 1.dp, horizontal = 5.dp),
                    )
                }
            }
        }

        ProfileLayoutBlock.IDENTITIES -> {
            val displayIdentities = identities.ifEmpty { user.identities }
            if (displayIdentities.isNotEmpty()) {
                displayIdentities.forEach { identity: IdentityClaimTag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        M3Icon(
                            tint = Color.Unspecified,
                            painter = painterRes(resourceId = getIdentityClaimIcon(identity), IDENTITY_ICON_CACHE_KEY),
                            contentDescription = stringRes(getIdentityClaimDescription(identity)),
                            modifier = Modifier.size(18.dp),
                        )

                        LongPressCopyText(
                            displayText = identity.identity,
                            copyValue = identity.identity,
                            onClick = { runCatching { uri.openUri(identity.toProofUrl()) } },
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 5.dp),
                        )
                    }
                }
            }
        }

        ProfileLayoutBlock.PAYMENT -> {
            val isSelf = baseUser.pubkeyHex == accountViewModel.userProfile().pubkeyHex
            if (!isSelf) {
                var openGifts by remember { mutableStateOf(false) }
                Button(onClick = { openGifts = true }) {
                    Icon(
                        symbol = MaterialSymbols.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(StdHorzSpacer)
                    Text(stringRes(R.string.stars_gift_send))
                }
                if (openGifts) {
                    GiftSupportSheet(
                        accountViewModel = accountViewModel,
                        toPubkey = baseUser.pubkeyHex,
                        noteId = null,
                        onDismiss = { openGifts = false },
                        onOpenStore = {
                            openGifts = false
                            nav.nav(Route.Wallet)
                        },
                    )
                }
            }
        }

        ProfileLayoutBlock.BADGES -> {
            if (showBadges) {
                DisplayBadges(baseUser, accountViewModel, nav)
            }
        }

        ProfileLayoutBlock.ABOUT -> {
            user.info.about?.let { about ->
                Row(
                    modifier = Modifier.padding(top = 10.dp, bottom = 5.dp),
                ) {
                    val defaultBackground = MaterialTheme.colorScheme.background
                    val background = remember { mutableStateOf(defaultBackground) }

                    TranslatableRichTextViewer(
                        content = about,
                        canPreview = false,
                        quotesLeft = 1,
                        tags = user.tags,
                        backgroundColor = background,
                        id = about,
                        accountViewModel = accountViewModel,
                        nav = nav,
                    )
                }
            }
        }

        ProfileLayoutBlock.APP_RECOMMENDATIONS -> {
            // Hidden: Instagram-style profiles do not show app recommendations.
        }
    }
}

@Composable
fun DisplayLastSeen(
    user: User,
    accountViewModel: AccountViewModel,
) {
    val lastSeenFlow =
        remember {
            accountViewModel.account.cache
                .observeLatestEvent<Event>(Filter(authors = listOf(user.pubkeyHex)))
                .map {
                    it?.createdAt
                }.flowOn(Dispatchers.IO)
        }

    val lastSeen by lastSeenFlow.collectAsStateWithLifecycle(null)

    lastSeen?.let { timestamp ->
        val context = LocalContext.current
        Text(
            text = lastSeenSentence(timestamp, context),
            color = MaterialTheme.colorScheme.placeholderText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DisplayNip05ProfileStatus(
    user: User,
    accountViewModel: AccountViewModel,
) {
    val nip05StateMetadata by user.nip05State().flow.collectAsStateWithLifecycle()

    when (val nip05State = nip05StateMetadata) {
        is Nip05State.Exists -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = SpacedBy5dp) {
                ObserveAndRenderNIP05VerifiedSymbol(nip05State, 2, Size15Modifier, accountViewModel)

                val uri = LocalUriHandler.current
                val displayValue = nip05State.nip05.toDisplayValue()

                LongPressCopyText(
                    displayText = displayValue,
                    copyValue = displayValue,
                    onClick = {
                        runCatching { uri.openUri("https://${nip05State.nip05.domain}") }
                    },
                    softWrap = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp, bottom = 1.dp),
                )
            }
        }

        else -> { }
    }
}

fun getIdentityClaimIcon(identity: IdentityClaimTag): Int =
    when (identity) {
        is TwitterIdentity -> R.drawable.x
        is TelegramIdentity -> R.drawable.telegram
        is MastodonIdentity -> R.drawable.mastodon
        is GitHubIdentity -> R.drawable.github
        else -> R.drawable.github
    }

fun getIdentityClaimDescription(identity: IdentityClaimTag): Int =
    when (identity) {
        is TwitterIdentity -> R.string.twitter
        is TelegramIdentity -> R.string.telegram
        is MastodonIdentity -> R.string.mastodon
        is GitHubIdentity -> R.string.github
        else -> R.string.github
    }
