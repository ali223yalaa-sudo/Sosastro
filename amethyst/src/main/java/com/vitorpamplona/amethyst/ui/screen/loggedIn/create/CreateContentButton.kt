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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.create

import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.ui.actions.ComposeContentType
import com.vitorpamplona.amethyst.ui.actions.NewMediaModel
import com.vitorpamplona.amethyst.ui.actions.NewMediaView
import com.vitorpamplona.amethyst.ui.actions.uploads.CameraCaptureScreen
import com.vitorpamplona.amethyst.ui.actions.uploads.GallerySelect
import com.vitorpamplona.amethyst.ui.actions.uploads.SelectedMedia
import com.vitorpamplona.amethyst.ui.navigation.navs.INav
import com.vitorpamplona.amethyst.ui.navigation.routes.Route
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.stringRes
import com.vitorpamplona.amethyst.ui.theme.Size22Modifier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The small "+" affordance that now lives in the Home top bar. Tapping it opens the
 * [CreateContentSheet]; whichever entry point the user picks is routed to the real
 * publishing flow:
 *
 * - Post / Reel / Story / Instant open the media picker and then the shared composer,
 *   pre-set to the matching [ComposeContentType].
 * - Live opens the HLS broadcast setup.
 * - Text post opens the short-note composer.
 * - Drafts opens the drafts list.
 *
 * The gallery and the (system) camera both feed the same [NewMediaView] composer, so
 * there is one editor regardless of where the media came from.
 */
@Composable
fun CreateContentButton(
    accountViewModel: AccountViewModel,
    nav: INav,
) {
    var showSheet by remember { mutableStateOf(false) }
    var wantsGallery by remember { mutableStateOf(false) }
    var wantsCamera by remember { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf(ComposeContentType.FEED) }
    var pickedURIs by remember { mutableStateOf<ImmutableList<SelectedMedia>>(persistentListOf()) }
    var editedURIs by remember { mutableStateOf<ImmutableList<SelectedMedia>>(persistentListOf()) }

    val postViewModel: NewMediaModel = viewModel()

    IconButton(onClick = { showSheet = true }) {
        Icon(
            symbol = MaterialSymbols.Add,
            contentDescription = stringRes(R.string.create_new_post),
            modifier = Size22Modifier,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (showSheet) {
        CreateContentSheet(
            onDismiss = { showSheet = false },
            onPickType = { type ->
                showSheet = false
                when (type) {
                    CreateContentType.LIVE -> nav.newStack(Route.NewHlsVideo)
                    CreateContentType.POST -> {
                        pendingType = ComposeContentType.FEED
                        wantsGallery = true
                    }
                    CreateContentType.REEL -> {
                        pendingType = ComposeContentType.REEL
                        wantsGallery = true
                    }
                    CreateContentType.STORY -> {
                        pendingType = ComposeContentType.STORY
                        wantsGallery = true
                    }
                    CreateContentType.INSTANT -> {
                        pendingType = ComposeContentType.INSTANT
                        wantsGallery = true
                    }
                }
            },
            onCamera = {
                showSheet = false
                pendingType = ComposeContentType.FEED
                wantsCamera = true
            },
            onGallery = {
                showSheet = false
                pendingType = ComposeContentType.FEED
                wantsGallery = true
            },
            onTextPost = {
                showSheet = false
                nav.newStack(Route.NewShortNote())
            },
            onDrafts = {
                showSheet = false
                nav.newStack(Route.Drafts)
            },
        )
    }

    if (wantsGallery) {
        GallerySelect(
            onImageUri = { uri ->
                wantsGallery = false
                pickedURIs = uri
            },
        )
    }

    if (wantsCamera) {
        FullScreenDialog(onDismiss = { wantsCamera = false }) {
            CameraCaptureScreen(
                onMediaCaptured = { uri ->
                    wantsCamera = false
                    pickedURIs = uri
                },
                onClose = { wantsCamera = false },
            )
        }
    }

    if (pickedURIs.isNotEmpty()) {
        FullScreenDialog(onDismiss = { pickedURIs = persistentListOf() }) {
            MediaEditorScreen(
                uris = pickedURIs,
                onBack = { pickedURIs = persistentListOf() },
                onDone = { edited ->
                    editedURIs = edited
                    pickedURIs = persistentListOf()
                },
            )
        }
    }

    if (editedURIs.isNotEmpty()) {
        postViewModel.contentType = pendingType
        NewMediaView(
            uris = editedURIs,
            onClose = { editedURIs = persistentListOf() },
            postViewModel = postViewModel,
            accountViewModel = accountViewModel,
            nav = nav,
        )
    }
}

/** A borderless full-screen dialog used to host the camera and editor surfaces. */
@Composable
private fun FullScreenDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        content()
    }
}
