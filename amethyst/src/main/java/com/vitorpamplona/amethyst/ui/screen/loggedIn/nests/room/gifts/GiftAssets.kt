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
package com.vitorpamplona.amethyst.ui.screen.loggedIn.nests.room.gifts

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.vitorpamplona.amethyst.model.privacyOptions.IRoleBasedHttpClientBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decode a gift icon once and cache it in composition. The catalog
 * stores either a bundled icon path or an absolute URL;
 * a URL is downloaded once into the app cache and then decoded from
 * disk. Returns `null` while the asset resolves or if it is missing,
 * so callers can fall back to the emoji glyph.
 */
@Composable
fun rememberGiftIcon(
    iconPath: String?,
    clientBuilder: IRoleBasedHttpClientBuilder? = null,
): ImageBitmap? {
    if (iconPath == null) return null
    val context = LocalContext.current
    val source = giftAssetOf(iconPath)
    val icon by produceState<ImageBitmap?>(null, iconPath) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    when (source) {
                        is GiftAsset.Bundled ->
                            context.assets.open(source.path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                        is GiftAsset.Remote ->
                            clientBuilder
                                ?.let { GiftAssetDownloader.localFileOrNull(context, source.url, it, ".png") }
                                ?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
                        null -> null
                    }
                }.getOrNull()
            }
    }
    return icon
}

/**
 * The real per-gift icon (rendered from upstream Twemoji vectors),
 * falling back to the emoji glyph until the asset is ready so the cell
 * never flashes empty.
 */
@Composable
fun GiftIcon(
    iconPath: String?,
    emoji: String,
    size: Dp,
    emojiSize: Int,
    modifier: Modifier = Modifier,
    clientBuilder: IRoleBasedHttpClientBuilder? = null,
) {
    val icon = rememberGiftIcon(iconPath, clientBuilder)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        } else {
            Text(text = emoji, fontSize = emojiSize.sp)
        }
    }
}

/**
 * Plays each gift's own sound effect once when its burst starts.
 *
 * A bundled `res/raw` clip goes through [SoundPool] so it is decoded
 * once and replayed with no per-play latency. A remote URL is
 * downloaded to the app cache on first use and then played with
 * [MediaPlayer], which — unlike SoundPool — accepts an arbitrary file
 * rather than only a packaged resource. Sonification usage means the
 * system media volume and mute still apply.
 */
class GiftSounds(
    private val context: Context,
    private val clientBuilder: IRoleBasedHttpClientBuilder?,
) {
    private val pool =
        SoundPool
            .Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()
    private val loaded = HashMap<String, Int>()

    // Remote clips are played by MediaPlayer; keep one per burst and
    // release the previous so a busy room does not leak players.
    private var player: MediaPlayer? = null

    private fun idFor(name: String): Int {
        loaded[name]?.let { return it }
        val res = context.resources.getIdentifier(name, "raw", context.packageName)
        if (res == 0) return 0
        val id = pool.load(context, res, 1)
        loaded[name] = id
        return id
    }

    /** Decode a bundled clip ahead of first use so the first gift is not silent. */
    fun preload(name: String?) {
        if (name != null && giftAssetOf(name) is GiftAsset.Bundled) idFor(name)
    }

    suspend fun play(name: String?) {
        if (name == null) return
        when (val asset = giftAssetOf(name)) {
            is GiftAsset.Bundled -> {
                val id = idFor(asset.path)
                if (id != 0) pool.play(id, 1f, 1f, 1, 0, 1f)
            }
            is GiftAsset.Remote -> playRemote(asset.url)
            null -> Unit
        }
    }

    private suspend fun playRemote(url: String) {
        // Download (or reuse the cached copy) off the main thread, then
        // hand the local path to MediaPlayer, which needs a real file.
        val builder = clientBuilder ?: return
        val file = GiftAssetDownloader.localFileOrNull(context, url, builder, ".wav") ?: return
        player?.runCatching { release() }
        player =
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { it.release() }
                    prepare()
                    start()
                }
            }.getOrNull()
    }

    fun release() {
        pool.release()
        player?.runCatching { release() }
        player = null
    }
}

@Composable
fun rememberGiftSounds(clientBuilder: IRoleBasedHttpClientBuilder?): GiftSounds {
    val context = LocalContext.current
    val sounds = remember(clientBuilder) { GiftSounds(context, clientBuilder) }
    DisposableEffect(sounds) {
        onDispose { sounds.release() }
    }
    return sounds
}
