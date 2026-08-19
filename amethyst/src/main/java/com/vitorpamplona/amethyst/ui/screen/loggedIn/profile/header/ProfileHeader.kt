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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.commons.model.nip01Core.UserInfo
import com.vitorpamplona.amethyst.commons.richtext.RichTextParser
import com.vitorpamplona.amethyst.commons.ui.components.LoadingAnimation
import com.vitorpamplona.amethyst.model.User
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.user.observeUserContactCardsFollowerCount
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.user.observeUserFollowingCount
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.user.observeUserInfo
import com.vitorpamplona.amethyst.service.relayClient.reqCommand.user.observeUserPicture
import com.vitorpamplona.amethyst.ui.actions.NewUserMetadataViewModel
import com.vitorpamplona.amethyst.ui.actions.uploads.GallerySelectSingle
import com.vitorpamplona.amethyst.ui.components.ZoomableImageDialog
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.note.ClickableUserPicture
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.followers.dal.UserProfileFollowersUserFeedViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.header.apps.UserAppRecommendationsFeedViewModel
import com.vitorpamplona.amethyst.ui.screen.loggedIn.profile.header.identity.UserExternalIdentitiesViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.Size35dp
import com.vitorpamplona.amethyst.ui.theme.SpacedBy3dp
import com.vitorpamplona.amethyst.ui.theme.userProfileBorderModifier
import com.vitorpamplona.quartz.nip01Core.metadata.ProfileCanvas
import com.vitorpamplona.quartz.nip01Core.metadata.ProfileCanvasItem
import com.vitorpamplona.quartz.nip01Core.metadata.ProfileLayoutBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Live profile uses the classic Amethyst structure (RTL-aware).
 * Saved canvas is applied as pixel deltas on top of that structure.
 * Editor mirrors the same structure and direction as the live profile.
 */
@Composable
fun ProfileHeader(
    baseUser: User,
    appRecommendations: UserAppRecommendationsFeedViewModel,
    externalIdentities: UserExternalIdentitiesViewModel,
    nav: INav,
    accountViewModel: AccountViewModel,
    followersFeedViewModel: UserProfileFollowersUserFeedViewModel,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
) {
    val userState by observeUserInfo(baseUser, accountViewModel)
    val identities by externalIdentities.identities.collectAsStateWithLifecycle()
    val ui = accountViewModel.settings.uiSettingsFlow
    val showBadges by ui.showProfileBadges.collectAsStateWithLifecycle()
    val showAppRecommendations by ui.showProfileAppRecommendations.collectAsStateWithLifecycle()

    val isMe by remember(accountViewModel, baseUser) {
        derivedStateOf { accountViewModel.userProfile() == baseUser }
    }

    var showEditor by remember { mutableStateOf(false) }

    val startLayoutEdit: (() -> Unit)? =
        if (isMe) {
            { showEditor = true }
        } else {
            null
        }

    // Always render a header shell so tabs are not stuck alone while kind:0 loads (or is missing).
    val savedCanvas = userState?.info?.resolvedProfileCanvas()
    val editorItemIds =
        remember(userState, identities, showBadges, showAppRecommendations) {
            val user = userState ?: return@remember emptyList()
            resolveDisplayCanvas(
                user = user,
                identitiesEmpty = identities.isEmpty() && user.identities.isEmpty(),
                showBadges = showBadges,
                showAppRecommendations = showAppRecommendations,
            ).items.map { it.id }
        }

    ClassicProfileHeader(
        baseUser = baseUser,
        appRecommendations = appRecommendations,
        externalIdentities = externalIdentities,
        nav = nav,
        accountViewModel = accountViewModel,
        followersFeedViewModel = followersFeedViewModel,
        onFollowersClick = onFollowersClick,
        onFollowingClick = onFollowingClick,
        onEditLayout = startLayoutEdit,
        savedCanvas = savedCanvas,
    )

    if (showEditor) {
        val editorUser = userState
        if (editorUser != null) {
            ProfileLayoutEditorDialog(
                editorItemIds = editorItemIds,
                savedCanvas = savedCanvas,
                baseUser = baseUser,
                user = editorUser,
                appRecommendations = appRecommendations,
                externalIdentities = externalIdentities,
                nav = nav,
                accountViewModel = accountViewModel,
                onDismiss = { showEditor = false },
                onPublished = { showEditor = false },
            )
        } else {
            Dialog(onDismissRequest = { showEditor = false }) {
                Surface(shape = CircleShape) {
                    Box(Modifier.padding(24.dp)) {
                        LoadingAnimation()
                    }
                }
            }
        }
    }
}

private fun Modifier.withLayoutDelta(delta: ProfileLayoutDelta?): Modifier {
    if (delta == null || delta.isIdentity()) return this
    return this
        .absoluteOffset { IntOffset(delta.x.roundToInt(), delta.y.roundToInt()) }
        .graphicsLayer {
            scaleX = delta.scale
            scaleY = delta.scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
}

/** Soft ceiling so items cannot be dragged far above their classic slot (editor + live). */
private fun clampDeltaY(
    id: String,
    y: Float,
    density: Density,
): Float {
    val minY =
        when (id) {
            ProfileCanvas.BANNER -> 0f
            else -> -with(density) { 48.dp.toPx() }
        }
    return y.coerceAtLeast(minY)
}

@Composable
private fun ProfileLayoutEditorDialog(
    editorItemIds: List<String>,
    savedCanvas: ProfileCanvas?,
    baseUser: User,
    user: UserInfo,
    appRecommendations: UserAppRecommendationsFeedViewModel,
    externalIdentities: UserExternalIdentitiesViewModel,
    nav: INav,
    accountViewModel: AccountViewModel,
    onDismiss: () -> Unit,
    onPublished: () -> Unit,
) {
    val offsetX = remember { mutableStateMapOf<String, Float>() }
    val offsetY = remember { mutableStateMapOf<String, Float>() }
    val scale = remember { mutableStateMapOf<String, Float>() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var bannerHidden by remember {
        mutableStateOf(savedCanvas?.includesLayoutItem(ProfileCanvas.BANNER) != true)
    }
    val widthPxState = remember { mutableFloatStateOf(0f) }
    var publishing by remember { mutableStateOf(false) }
    var seeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(editorItemIds, savedCanvas, widthPxState.floatValue) {
        val w = widthPxState.floatValue
        if (w < 100f) return@LaunchedEffect
        if (seeded) return@LaunchedEffect
        val deltas = layoutDeltasFromCanvas(savedCanvas, w)
        editorItemIds.forEach { id ->
            val d = deltas[id] ?: ProfileLayoutDelta()
            offsetX[id] = d.x
            offsetY[id] = d.y
            scale[id] = d.scale
        }
        bannerHidden = savedCanvas?.includesLayoutItem(ProfileCanvas.BANNER) != true
        seeded = true
    }

    fun buildCanvas(): ProfileCanvas {
        // v2: store exact pixel deltas as fractions of the same full width used on live profile.
        // Omitting banner from items hides it on the live profile after publish.
        val designW = widthPxState.floatValue.coerceAtLeast(1f)
        val items =
            editorItemIds
                .filter { id -> !(bannerHidden && id == ProfileCanvas.BANNER) }
                .map { id ->
                    val s = (scale[id] ?: 1f).coerceIn(0.5f, 2.5f)
                    val ox = offsetX[id] ?: 0f
                    val oy = offsetY[id] ?: 0f
                    ProfileCanvasItem(
                        id = id,
                        x = ox / designW,
                        y = oy / designW,
                        w = s,
                        h = s,
                    ).clampedDelta()
                }
        return ProfileCanvas(v = ProfileCanvas.DELTA_VERSION, items = items)
    }

    Dialog(
        onDismissRequest = { if (!publishing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringRes(R.string.profile_canvas_editing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            editorItemIds.forEach { id ->
                                offsetX[id] = 0f
                                offsetY[id] = 0f
                                scale[id] = 1f
                            }
                            bannerHidden = true
                            selectedId = null
                        },
                        enabled = !publishing,
                    ) {
                        Text(stringRes(R.string.profile_canvas_reset))
                    }
                    TextButton(onClick = onDismiss, enabled = !publishing) {
                        Text(stringRes(R.string.cancel))
                    }
                    Button(
                        enabled = !publishing,
                        onClick = {
                            if (publishing) return@Button
                            publishing = true
                            val canvas = buildCanvas()
                            accountViewModel.launchSigner {
                                val event =
                                    accountViewModel.account.userMetadata.sendNewUserMetadata(
                                        profileCanvas = ProfileCanvas.serialize(canvas),
                                    )
                                accountViewModel.account.sendLiterallyEverywhere(event)
                                accountViewModel.toastManager.toast(
                                    R.string.profile_canvas_published_title,
                                    R.string.profile_canvas_published,
                                )
                                withContext(Dispatchers.Main) {
                                    publishing = false
                                    onPublished()
                                }
                            }
                            scope.launch {
                                kotlinx.coroutines.delay(8_000)
                                if (publishing) publishing = false
                            }
                        },
                    ) {
                        Text(stringRes(R.string.profile_canvas_publish))
                    }
                }

                Text(
                    text = stringRes(R.string.profile_canvas_editor_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                when {
                    bannerHidden -> {
                        TextButton(
                            onClick = {
                                bannerHidden = false
                                offsetX[ProfileCanvas.BANNER] = 0f
                                offsetY[ProfileCanvas.BANNER] = 0f
                                scale[ProfileCanvas.BANNER] = 1f
                                selectedId = ProfileCanvas.BANNER
                            },
                            enabled = !publishing,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text(stringRes(R.string.profile_canvas_restore_banner))
                        }
                    }
                    selectedId == ProfileCanvas.BANNER -> {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringRes(R.string.profile_canvas_banner_selected),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    bannerHidden = true
                                    selectedId = null
                                },
                                enabled = !publishing,
                            ) {
                                Text(stringRes(R.string.profile_canvas_remove_banner))
                            }
                        }
                    }
                }

                // Identical structure to ClassicProfileHeader so slots match 1:1 after save.
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    SideEffect {
                        widthPxState.floatValue = widthPx
                    }

                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 12.dp, bottom = 8.dp),
                    ) {
                        if (!bannerHidden) {
                            EditorDraggable(
                                id = ProfileCanvas.BANNER,
                                selectedId = selectedId,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                scale = scale,
                                onSelect = { selectedId = it },
                            ) {
                                DrawBanner(baseUser, accountViewModel)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EditorDraggable(
                                id = ProfileCanvas.AVATAR,
                                selectedId = selectedId,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                scale = scale,
                                onSelect = { selectedId = it },
                            ) {
                                ZoomableUserPicture(
                                    baseUser = baseUser,
                                    accountViewModel = accountViewModel,
                                    size = 88.dp,
                                )
                            }

                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                EditorDraggable(
                                    id = ProfileCanvas.FOLLOWERS,
                                    selectedId = selectedId,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                    scale = scale,
                                    onSelect = { selectedId = it },
                                ) {
                                    InstagramProfileStat(
                                        value = "—",
                                        label = stringRes(R.string.followers).trim(),
                                        onClick = {},
                                    )
                                }
                                EditorDraggable(
                                    id = ProfileCanvas.FOLLOWING,
                                    selectedId = selectedId,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                    scale = scale,
                                    onSelect = { selectedId = it },
                                ) {
                                    InstagramProfileStat(
                                        value = "—",
                                        label = stringRes(R.string.following).trim(),
                                        onClick = {},
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalArrangement = SpacedBy3dp,
                        ) {
                            editorItemIds
                                .filter {
                                    it != ProfileCanvas.BANNER &&
                                        it != ProfileCanvas.AVATAR &&
                                        it != ProfileCanvas.ACTIONS &&
                                        it != ProfileCanvas.FOLLOWERS &&
                                        it != ProfileCanvas.FOLLOWING
                                }.forEach { id ->
                                    val block = ProfileLayoutBlock.entries.firstOrNull { it.id == id }
                                    if (block != null) {
                                        EditorDraggable(
                                            id = id,
                                            selectedId = selectedId,
                                            offsetX = offsetX,
                                            offsetY = offsetY,
                                            scale = scale,
                                            onSelect = { selectedId = it },
                                        ) {
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

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EditorDraggable(
                                id = ProfileCanvas.ACTIONS,
                                selectedId = selectedId,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                scale = scale,
                                onSelect = { selectedId = it },
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .height(Size35dp)
                                            .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ProfileActions(
                                        baseUser = baseUser,
                                        accountViewModel = accountViewModel,
                                        nav = nav,
                                        onEditLayout = null,
                                    )
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorDraggable(
    id: String,
    selectedId: String?,
    offsetX: MutableMap<String, Float>,
    offsetY: MutableMap<String, Float>,
    scale: MutableMap<String, Float>,
    onSelect: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val selected = selectedId == id
    val density = LocalDensity.current
    val ox = offsetX[id] ?: 0f
    val oy = clampDeltaY(id, offsetY[id] ?: 0f, density)
    val sc = (scale[id] ?: 1f).coerceIn(0.5f, 2.5f)

    LaunchedEffect(id, density) {
        val cur = offsetY[id] ?: 0f
        val clamped = clampDeltaY(id, cur, density)
        if (cur != clamped) offsetY[id] = clamped
    }

    Box(
        modifier =
            Modifier
                .zIndex(if (selected) 2f else 1f)
                .absoluteOffset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                .graphicsLayer {
                    scaleX = sc
                    scaleY = sc
                    transformOrigin = TransformOrigin(0f, 0f)
                }.then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier
                    },
                ),
    ) {
        content()
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(selected, id, density) {
                    if (selected) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            offsetX[id] = (offsetX[id] ?: 0f) + pan.x
                            offsetY[id] =
                                clampDeltaY(id, (offsetY[id] ?: 0f) + pan.y, density)
                            scale[id] = ((scale[id] ?: 1f) * zoom).coerceIn(0.5f, 2.5f)
                        }
                    } else {
                        detectTapGestures { onSelect(id) }
                    }
                },
        )
    }
}

@Composable
private fun ClassicProfileHeader(
    baseUser: User,
    appRecommendations: UserAppRecommendationsFeedViewModel,
    externalIdentities: UserExternalIdentitiesViewModel,
    nav: INav,
    accountViewModel: AccountViewModel,
    followersFeedViewModel: UserProfileFollowersUserFeedViewModel,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    onEditLayout: (() -> Unit)? = null,
    savedCanvas: ProfileCanvas? = null,
) {
    val userState by observeUserInfo(baseUser, accountViewModel)
    val nip85FollowerCount by observeUserContactCardsFollowerCount(baseUser, accountViewModel)
    val localFollowerCount by followersFeedViewModel.followerCount.collectAsStateWithLifecycle()
    val followerCount =
        if (nip85FollowerCount != "--") {
            nip85FollowerCount
        } else {
            localFollowerCount.toString()
        }
    val followingCount by observeUserFollowingCount(baseUser, accountViewModel)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val deltas = remember(savedCanvas, widthPx) { layoutDeltasFromCanvas(savedCanvas, widthPx) }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.withLayoutDelta(deltas[ProfileCanvas.AVATAR])) {
                    ProfilePictureWithUploadOverlay(
                        baseUser = baseUser,
                        accountViewModel = accountViewModel,
                        size = 88.dp,
                    )
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.withLayoutDelta(deltas[ProfileCanvas.FOLLOWERS])) {
                        InstagramProfileStat(
                            value = followerCount,
                            label = stringRes(R.string.followers).trim(),
                            onClick = onFollowersClick,
                        )
                    }
                    Box(modifier = Modifier.withLayoutDelta(deltas[ProfileCanvas.FOLLOWING])) {
                        InstagramProfileStat(
                            value = followingCount.toString(),
                            label = stringRes(R.string.following).trim(),
                            onClick = onFollowingClick,
                        )
                    }
                }
            }

            val user = userState
            if (user != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = SpacedBy3dp) {
                    listOf(
                        ProfileLayoutBlock.DISPLAY_NAME,
                        ProfileLayoutBlock.USERNAME,
                        ProfileLayoutBlock.NPUB,
                        ProfileLayoutBlock.PROFESSION,
                        ProfileLayoutBlock.NIP05,
                        ProfileLayoutBlock.WEBSITE,
                        ProfileLayoutBlock.ABOUT,
                    ).forEach { block ->
                        Box(modifier = Modifier.withLayoutDelta(deltas[block.id])) {
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
            } else {
                Text(
                    text = baseUser.toBestDisplayName(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .withLayoutDelta(deltas[ProfileCanvas.ACTIONS]),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileActions(baseUser, accountViewModel, nav, onEditLayout = onEditLayout)
            }

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun InstagramProfileStat(
    value: String,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ZoomableUserPicture(
    baseUser: User,
    accountViewModel: AccountViewModel,
    size: Dp,
) {
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var zoomImageUrl by remember { mutableStateOf<String?>(null) }
    var sourceBounds by remember { mutableStateOf<Rect?>(null) }

    ClickableUserPicture(
        baseUser = baseUser,
        accountViewModel = accountViewModel,
        size = size,
        modifier =
            MaterialTheme.colorScheme.userProfileBorderModifier
                .onGloballyPositioned { sourceBounds = it.boundsInWindow() },
        onClick = {
            val pic = baseUser.profilePicture()
            if (pic != null) {
                zoomImageUrl = pic
            }
        },
        onLongClick = {
            baseUser.profilePicture()?.let { it1 ->
                scope.launch {
                    val clipData = ClipData.newPlainText("profile picture url", it1)
                    clipboardManager.setClipEntry(ClipEntry(clipData))
                }
            }
        },
    )

    zoomImageUrl?.let {
        ZoomableImageDialog(
            imageUrl = RichTextParser.parseImageOrVideo(it),
            sourceBounds = sourceBounds,
            onDismiss = { zoomImageUrl = null },
            accountViewModel = accountViewModel,
        )
    }
}

@Composable
fun ProfilePictureWithUploadOverlay(
    baseUser: User,
    accountViewModel: AccountViewModel,
    size: Dp,
) {
    val isMe by remember(accountViewModel) { derivedStateOf { accountViewModel.userProfile() == baseUser } }
    val picture by observeUserPicture(baseUser, accountViewModel)

    Box(contentAlignment = Alignment.Center) {
        ZoomableUserPicture(
            baseUser = baseUser,
            accountViewModel = accountViewModel,
            size = size,
        )
        if (isMe && picture.isNullOrBlank()) {
            ProfilePictureUploadButton(accountViewModel, size)
        }
    }
}

@Composable
private fun ProfilePictureUploadButton(
    accountViewModel: AccountViewModel,
    size: Dp,
) {
    val postViewModel: NewUserMetadataViewModel = viewModel()
    postViewModel.init(accountViewModel)
    val context = LocalContext.current

    var showGallerySelect by remember { mutableStateOf(false) }
    if (showGallerySelect) {
        GallerySelectSingle(
            onImageUri = { media ->
                showGallerySelect = false
                if (media != null) {
                    postViewModel.uploadPictureAndSave(media, context, accountViewModel.toastManager::toast)
                }
            },
        )
    }

    if (postViewModel.isUploadingImageForPicture) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            LoadingAnimation()
        }
    } else {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .clickable { showGallerySelect = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                symbol = MaterialSymbols.AddPhotoAlternate,
                contentDescription = stringRes(R.string.upload_image),
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
