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
package com.vitorpamplona.amethyst.service.playback.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import com.vitorpamplona.amethyst.service.playback.composable.controls.ApplyInitialVideoQuality
import com.vitorpamplona.amethyst.service.playback.composable.controls.VideoQualityPolicy
import com.vitorpamplona.amethyst.service.playback.composable.mainVideo.VideoPlayerActiveMutex
import com.vitorpamplona.amethyst.service.playback.composable.mediaitem.GetMediaItem
import com.vitorpamplona.amethyst.service.playback.pip.BackgroundMedia
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.quartz.nip94FileMetadata.tags.DimensionTag

val DEFAULT_MUTED_SETTING = mutableStateOf(true)

@Composable
fun VideoViewInner(
    videoUri: String,
    mimeType: String?,
    aspectRatio: Float? = null,
    title: String? = null,
    thumb: VideoThumb? = null,
    showControls: Boolean = true,
    contentScale: ContentScale,
    borderModifier: Modifier,
    waveform: WaveformData? = null,
    artworkUri: String? = null,
    authorName: String? = null,
    nostrUriCallback: String? = null,
    automaticallyStartPlayback: Boolean,
    isLiveStream: Boolean = false,
    controllerVisible: MutableState<Boolean> = mutableStateOf(false),
    onZoom: (() -> Unit)? = null,
    hasBlurhash: Boolean = false,
    isFullscreen: Boolean = false,
    blurhash: String? = null,
    dim: DimensionTag? = null,
    hash: String? = null,
    thumbhash: String? = null,
    accountViewModel: AccountViewModel,
    reelsGestures: Boolean = false,
    startUnmuted: Boolean = false,
    onDoubleTapAction: (() -> Unit)? = null,
    onPlaybackReady: (() -> Unit)? = null,
    onTapAction: (() -> Unit)? = null,
) {
    // Global mute: the shared setting drives every player, so unmuting one video unmutes
    // them all and muting one keeps the rest silent. Read reactively (not remembered) so a
    // change from the persistent bottom-right button or the top-bar button is picked up here.
    val muted = DEFAULT_MUTED_SETTING.value

    // The proxy port is decided once per video URI; recomputing on every recomposition does
    // pointless work (the result is anyway dropped because GetMediaItem.remember is keyed on the
    // URI alone, so the cached MediaItemData is locked in on the first frame).
    val proxyPort = remember(videoUri) { accountViewModel.httpClientBuilder.proxyPortForVideo(videoUri) }

    GetMediaItem(
        videoUri = videoUri,
        title = title,
        artworkUri = artworkUri,
        authorName = authorName,
        callbackUri = nostrUriCallback,
        mimeType = mimeType,
        aspectRatio = aspectRatio,
        proxyPort = proxyPort,
        keepPlaying = true,
        waveformData = waveform,
        isLiveStream = isLiveStream,
        blurhash = blurhash,
        dim = dim,
        hash = hash,
        thumbhash = thumbhash,
    ) { mediaItem ->
        GetVideoController(
            mediaItem = mediaItem,
            muted = muted,
        ) { controller ->
            if (onPlaybackReady != null) {
                DisposableEffect(controller, onPlaybackReady) {
                    val player = controller.controller
                    val listener =
                        object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                                    onPlaybackReady()
                                }
                            }
                        }
                    if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED) {
                        onPlaybackReady()
                    }
                    player.addListener(listener)
                    onDispose { player.removeListener(listener) }
                }
            }
            if (reelsGestures) {
                // Active reel page: follow the global mute setting. Inactive / off-tab:
                // mute + pause so HorizontalPager neighbors (e.g. Videos tab) never keep audio.
                LaunchedEffect(controller, startUnmuted, muted) {
                    if (startUnmuted) {
                        controller.controller.volume = if (muted) 0f else 1f
                    } else {
                        controller.controller.volume = 0f
                        if (controller.controller.isPlaying) {
                            controller.controller.pause()
                        }
                    }
                }
            } else {
                // Feed videos: keep every visible player in step with the shared setting, so
                // unmuting one unmutes the others and muting one silences them all.
                LaunchedEffect(controller, muted) {
                    if (!BackgroundMedia.isPlaying()) {
                        controller.controller.volume = if (muted) 0f else 1f
                    }
                }
            }
            ApplyInitialVideoQuality(
                player = controller.controller,
                policy = if (isFullscreen) VideoQualityPolicy.AUTO else VideoQualityPolicy.LOWEST,
            )
            VideoPlayerActiveMutex(controller) { videoModifier, isClosestToTheCenterOfTheScreen ->
                ControlWhenPlayerIsActive(
                    controller,
                    automaticallyStartPlayback = automaticallyStartPlayback && (!reelsGestures || startUnmuted),
                    isClosestToTheCenterOfTheScreen,
                    forceUnmuted = false,
                )
                RenderVideoPlayer(
                    mediaItem = mediaItem,
                    controllerState = controller,
                    thumbData = thumb,
                    showControls = showControls,
                    contentScale = contentScale,
                    borderModifier = borderModifier,
                    videoModifier = videoModifier,
                    controllerVisible = controllerVisible,
                    onDialog = onZoom,
                    hasBlurhash = hasBlurhash,
                    isFullscreen = isFullscreen,
                    accountViewModel = accountViewModel,
                    reelsGestures = reelsGestures,
                    onDoubleTapAction = onDoubleTapAction,
                    onTapAction = onTapAction,
                )
            }
        }
    }
}
