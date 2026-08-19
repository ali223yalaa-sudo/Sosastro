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
package com.vitorpamplona.amethyst.ui.layouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.vitorpamplona.amethyst.commons.ui.layouts.DisappearingBarNestedScroll
import com.vitorpamplona.amethyst.commons.ui.layouts.DisappearingBarState
import com.vitorpamplona.amethyst.commons.ui.layouts.LocalDisappearingBarState
import com.vitorpamplona.amethyst.commons.ui.layouts.LocalDisappearingScaffoldPadding
import com.vitorpamplona.amethyst.commons.ui.layouts.rememberDisappearingBarState
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.theme.DividerThickness

private enum class DisappearingSlot { Top, Bottom, Content, Fab }

private val FabEdgePadding = 16.dp

@Composable
fun DisappearingScaffold(
    isInvertedLayout: Boolean,
    topBar: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    floatingButton: (@Composable () -> Unit)? = null,
    accountViewModel: AccountViewModel,
    isActive: () -> Boolean = { true },
    allowBarHide: Boolean = true,
    mainContent: @Composable (padding: PaddingValues) -> Unit,
) {
    val state = rememberDisappearingBarState()

    // Hold the latest values in state so the NSC's captured lambda stays fresh across
    // recompositions without rebuilding the NSC itself.
    val latestIsActive by rememberUpdatedState(isActive)
    val latestAllowBarHide by rememberUpdatedState(allowBarHide)
    val latestAccountViewModel by rememberUpdatedState(accountViewModel)

    val connection =
        remember(state, isInvertedLayout) {
            DisappearingBarNestedScroll(
                state = state,
                canScroll = {
                    latestAllowBarHide &&
                        latestIsActive() &&
                        latestAccountViewModel.settings.isImmersiveScrollingActive()
                },
                reverseLayout = isInvertedLayout,
                hideBottomBar = { false },
            )
        }

    // When bars are pinned, skip attaching the nested-scroll connection entirely.
    // The outer Surface provides the Material container color + onBackground as
    // LocalContentColor, matching M3 Scaffold's behaviour (without it, default text
    // color falls back to Color.Black and is invisible on the dark theme).
    val baseModifier =
        if (allowBarHide) {
            Modifier.imePadding().nestedScroll(connection)
        } else {
            Modifier.imePadding()
        }
    val rootModifier =
        baseModifier
            .let { if (topBar == null) it.statusBarsPadding() else it }
            .let { if (bottomBar == null) it.navigationBarsPadding() else it }

    Surface(
        modifier = rootModifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        ScaffoldLayout(
            state = state,
            topBar = topBar,
            bottomBar = bottomBar,
            floatingButton = floatingButton,
            mainContent = mainContent,
        )
    }
}

@Composable
private fun ScaffoldLayout(
    state: DisappearingBarState,
    topBar: (@Composable () -> Unit)?,
    bottomBar: (@Composable () -> Unit)?,
    floatingButton: (@Composable () -> Unit)?,
    mainContent: @Composable (padding: PaddingValues) -> Unit,
) {
    val navBarInsets = WindowInsets.navigationBars
    val imeInsets = WindowInsets.ime
    SubcomposeLayout { constraints ->
        val layoutWidth = constraints.maxWidth
        val layoutHeight = constraints.maxHeight
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val topPlaceable =
            topBar?.let { bar ->
                subcompose(DisappearingSlot.Top) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationY = state.topHeightOffset
                                }.background(MaterialTheme.colorScheme.background),
                    ) {
                        bar()
                        HorizontalDivider(thickness = DividerThickness)
                    }
                }.firstOrNull()?.measure(looseConstraints)
            }
        val topHeight = topPlaceable?.height ?: 0

        val bottomPlaceable =
            bottomBar?.let { bar ->
                subcompose(DisappearingSlot.Bottom) {
                    Column(
                        modifier =
                            Modifier.graphicsLayer {
                                translationY = -state.bottomHeightOffset
                            },
                    ) {
                        bar()
                    }
                }.firstOrNull()?.measure(looseConstraints)
            }
        // When the bar lambda is provided but its content emits nothing (e.g. AppBottomBar
        // hides itself on canPop entries, or while the keyboard is up), reserve the
        // system-nav-bar inset so the FAB and content stay clear of the navigation bar instead
        // of sliding under it. Subtract the IME inset: the root imePadding has already lifted the
        // whole scaffold above the keyboard, and WindowInsets.ime spans the nav-bar band, so
        // reserving the full nav bar on top of that would double-count and strand a gap above the
        // keyboard (the `bottomBar == null` branch gets this for free via navigationBarsPadding,
        // which excludes the consumed IME inset). The `bottomBar == null` case is on rootModifier.
        val bottomHeight =
            (bottomPlaceable?.height ?: 0).let { measured ->
                if (bottomBar != null && measured == 0) {
                    (navBarInsets.getBottom(this) - imeInsets.getBottom(this)).coerceAtLeast(0)
                } else {
                    measured
                }
            }

        // Publish the measured limits so the nested-scroll connection can clamp correctly.
        state.topHeightLimit = topHeight.toFloat()
        state.bottomHeightLimit = bottomHeight.toFloat()

        // Always reserve the full bar height. Shrinking this with topHeightOffset double-counts
        // list scroll and slides the first post under the still-visible stories/top bar.
        // Returning to item 0 is handled by WatchRevealChromeAtFeedTop.syncTopChromeToList().
        val contentPadding =
            PaddingValues(
                top = topHeight.toDp(),
                bottom = bottomHeight.toDp(),
            )

        val contentPlaceable =
            subcompose(DisappearingSlot.Content) {
                CompositionLocalProvider(
                    LocalDisappearingScaffoldPadding provides contentPadding,
                    LocalDisappearingBarState provides state,
                ) {
                    mainContent(contentPadding)
                }
            }.firstOrNull()?.measure(
                Constraints.fixed(layoutWidth, layoutHeight),
            )

        val fabPlaceable =
            floatingButton?.let { fab ->
                subcompose(DisappearingSlot.Fab) {
                    FloatingButtonHolder(state = state) { fab() }
                }.firstOrNull()?.measure(looseConstraints)
            }

        val fabEdgePx = FabEdgePadding.roundToPx()

        layout(layoutWidth, layoutHeight) {
            contentPlaceable?.place(0, 0)
            topPlaceable?.place(0, 0)
            bottomPlaceable?.place(0, layoutHeight - bottomHeight)
            if (fabPlaceable != null) {
                val x = layoutWidth - fabPlaceable.width - fabEdgePx
                val yBase = layoutHeight - bottomHeight - fabPlaceable.height - fabEdgePx
                fabPlaceable.place(x, yBase)
            }
        }
    }
}

/**
 * Holds the floating button. Scales / fades it with the bottom-bar collapse fraction and
 * rides along with the bar via graphicsLayer, so the layout pass is untouched while
 * the bar animates.
 */
@Composable
private fun FloatingButtonHolder(
    state: DisappearingBarState,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier.graphicsLayer {
                val visible = (1f - state.bottomCollapsedFraction).coerceAtLeast(0.001f)
                scaleX = visible
                scaleY = visible
                alpha = visible
                translationY = -state.bottomHeightOffset
            },
    ) {
        content()
    }
}

/** Unlocks top chrome only at the first feed item; glues the bar 1:1 to that item's scroll. */
@Composable
fun WatchRevealChromeAtFeedTop(listState: LazyListState) {
    val barState = LocalDisappearingBarState.current ?: return
    DisposableEffect(barState) {
        barState.listDrivesTopChrome = true
        onDispose { barState.listDrivesTopChrome = false }
    }
    LaunchedEffect(listState, barState) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                barState.topHeightLimit,
            )
        }.collect { (index, offset, _) ->
            barState.syncTopChromeToList(index, offset)
        }
    }
}
