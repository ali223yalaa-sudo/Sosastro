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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import coil3.compose.AsyncImage
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbol
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.ui.actions.uploads.SelectedMedia
import com.vitorpamplona.amethyst.ui.stringRes
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas as ComposeCanvas

/** Crop aspect presets offered by the editor. */
private enum class CropAspect(
    val ratio: Float?,
    val label: String,
) {
    ORIGINAL(null, "Original"),
    SQUARE(1f, "1:1"),
    PORTRAIT(4f / 5f, "4:5"),
    WIDE(16f / 9f, "16:9"),
    REEL(9f / 16f, "9:16"),
    ;

    fun next(): CropAspect = entries[(ordinal + 1) % entries.size]
}

/** Colour-matrix presets applied to photos. */
private enum class PhotoFilter(
    val label: String,
    val matrix: FloatArray?,
) {
    NONE("Normal", null),
    WARM(
        "Warm",
        floatArrayOf(
            1.10f,
            0f,
            0f,
            0f,
            12f,
            0f,
            1.02f,
            0f,
            0f,
            4f,
            0f,
            0f,
            0.92f,
            0f,
            -6f,
            0f,
            0f,
            0f,
            1f,
            0f,
        ),
    ),
    COOL(
        "Cool",
        floatArrayOf(
            0.92f,
            0f,
            0f,
            0f,
            -6f,
            0f,
            1.0f,
            0f,
            0f,
            2f,
            0f,
            0f,
            1.12f,
            0f,
            12f,
            0f,
            0f,
            0f,
            1f,
            0f,
        ),
    ),
    MONO(
        "Mono",
        floatArrayOf(
            0.33f,
            0.33f,
            0.33f,
            0f,
            0f,
            0.33f,
            0.33f,
            0.33f,
            0f,
            0f,
            0.33f,
            0.33f,
            0.33f,
            0f,
            0f,
            0f,
            0f,
            0f,
            1f,
            0f,
        ),
    ),
    FADE(
        "Fade",
        floatArrayOf(
            0.9f,
            0f,
            0f,
            0f,
            24f,
            0f,
            0.9f,
            0f,
            0f,
            24f,
            0f,
            0f,
            0.9f,
            0f,
            24f,
            0f,
            0f,
            0f,
            0.85f,
            0f,
        ),
    ),
    ;

    fun next(): PhotoFilter = entries[(ordinal + 1) % entries.size]
}

private data class TextOverlay(
    val id: Int,
    val text: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val sizeSp: Int,
)

/** Per-image edit description; copied on each change so Compose sees a new value. */
private data class EditState(
    val rotation: Int = 0,
    val flipped: Boolean = false,
    val aspect: CropAspect = CropAspect.ORIGINAL,
    val filter: PhotoFilter = PhotoFilter.NONE,
    val overlays: List<TextOverlay> = emptyList(),
    val nextId: Int = 1,
)

private val OVERLAY_COLORS =
    listOf(
        0xFFFFFFFF.toInt(),
        0xFF000000.toInt(),
        0xFFFF4D4D.toInt(),
        0xFF4D9EFF.toInt(),
        0xFFFFD400.toInt(),
        0xFF4DE04D.toInt(),
    )

/**
 * Instagram-style editor step between capture/pick and publish. Works on a single
 * media item at a time; when several are selected the user pages between them with
 * the thumbnail strip and each keeps its own rotation, crop, filter and text
 * overlays. Photos are rendered to a new JPEG on "next"; videos pass through
 * unchanged (trimming lives in the publish step).
 */
@Composable
fun MediaEditorScreen(
    uris: ImmutableList<SelectedMedia>,
    onBack: () -> Unit,
    onDone: (ImmutableList<SelectedMedia>) -> Unit,
) {
    val context = LocalContext.current
    var index by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var states by remember { mutableStateOf(uris.map { EditState() }) }
    val scope = rememberCoroutineScope()

    val current = uris.getOrNull(index) ?: return
    val state = states.getOrNull(index) ?: EditState()

    fun update(transform: (EditState) -> EditState) {
        states = states.mapIndexed { i, s -> if (i == index) transform(s) else s }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (current.isVideo() == true) {
            AsyncImage(
                model = current.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(vertical = 96.dp),
            )
        } else {
            EditorCanvas(
                media = current,
                state = state,
                onOverlayMoved = { id, offset ->
                    update { s ->
                        s.copy(overlays = s.overlays.map { if (it.id == id) it.copy(x = offset.x, y = offset.y) else it })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(MaterialSymbols.Close, stringRes(R.string.create_close), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !exporting,
                onClick = {
                    exporting = true
                    val snapshot = states
                    scope.launch {
                        val result =
                            uris
                                .mapIndexed { i, media ->
                                    val s = snapshot.getOrNull(i) ?: EditState()
                                    if (media.isImage() == true) renderEdited(context, media, s) ?: media else media
                                }.toImmutableList()
                        exporting = false
                        onDone(result)
                    }
                },
            ) {
                if (exporting) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(stringRes(R.string.camera_next))
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(Color(0xCC000000))
                    .padding(vertical = 12.dp),
        ) {
            if (uris.size > 1) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(uris.toList()) { i, media ->
                        AsyncImage(
                            model = media.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        width = if (i == index) 2.dp else 0.dp,
                                        color = if (i == index) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(6.dp),
                                    ).padding(2.dp)
                                    .pointerInput(i) { detectTapGestures { index = i } },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (current.isImage() == true) {
                ToolRow(
                    state = state,
                    onRotate = { update { it.copy(rotation = (it.rotation + 90) % 360) } },
                    onFlip = { update { it.copy(flipped = !it.flipped) } },
                    onCrop = { update { it.copy(aspect = it.aspect.next()) } },
                    onFilter = { update { it.copy(filter = it.filter.next()) } },
                    onAddText = {
                        update { s ->
                            s.copy(
                                overlays =
                                    s.overlays +
                                        TextOverlay(
                                            id = s.nextId,
                                            text = "",
                                            x = 0.4f,
                                            y = 0.4f,
                                            color = OVERLAY_COLORS.first(),
                                            sizeSp = 32,
                                        ),
                                nextId = s.nextId + 1,
                            )
                        }
                    },
                    onReset = { update { EditState() } },
                )

                val editable = state.overlays.lastOrNull()
                if (editable != null) {
                    TextInputBar(
                        overlay = editable,
                        onTextChange = { t -> update { s -> s.copy(overlays = s.overlays.map { if (it.id == editable.id) it.copy(text = t) else it }) } },
                        onColor = { c -> update { s -> s.copy(overlays = s.overlays.map { if (it.id == editable.id) it.copy(color = c) else it }) } },
                        onSize = { sz -> update { s -> s.copy(overlays = s.overlays.map { if (it.id == editable.id) it.copy(sizeSp = sz) else it }) } },
                        onDelete = { update { s -> s.copy(overlays = s.overlays.filterNot { it.id == editable.id }) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorCanvas(
    media: SelectedMedia,
    state: EditState,
    onOverlayMoved: (Int, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(media.uri) { mutableStateOf<Bitmap?>(null) }
    var boxWidth by remember { mutableStateOf(1) }
    var boxHeight by remember { mutableStateOf(1) }

    LaunchedEffect(media.uri) {
        bitmap = withContext(Dispatchers.IO) { decodeSampled(context, media.uri, 2048) }
    }

    val bmp = bitmap

    Box(modifier = modifier.padding(vertical = 96.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .then(
                            if (state.aspect.ratio != null) {
                                Modifier.aspectRatio(state.aspect.ratio)
                            } else {
                                Modifier.fillMaxSize()
                            },
                        ),
            ) {
                if (bmp != null) {
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        boxWidth = size.width.toInt()
                        boxHeight = size.height.toInt()
                        drawIntoCanvas { canvas ->
                            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                            state.filter.matrix?.let {
                                paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(it))
                            }
                            val dst = fitRect(bmp.width, bmp.height, size.width, size.height, state.rotation)
                            val m = Matrix()
                            m.postRotate(state.rotation.toFloat())
                            if (state.flipped) m.postScale(-1f, 1f)
                            val rotated =
                                if (state.rotation != 0 || state.flipped) {
                                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                                } else {
                                    bmp
                                }
                            canvas.nativeCanvas.drawBitmap(rotated, null, dst, paint)
                            if (rotated !== bmp) rotated.recycle()
                        }
                    }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }

                state.overlays.forEach { overlay ->
                    if (boxWidth <= 1 || boxHeight <= 1) return@forEach
                    Box(
                        modifier =
                            Modifier
                                .offset {
                                    IntOffset(
                                        (overlay.x * boxWidth).roundToInt(),
                                        (overlay.y * boxHeight).roundToInt(),
                                    )
                                }.pointerInput(overlay.id) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        val nx = (overlay.x + drag.x / boxWidth).coerceIn(0f, 1f)
                                        val ny = (overlay.y + drag.y / boxHeight).coerceIn(0f, 1f)
                                        onOverlayMoved(overlay.id, Offset(nx, ny))
                                    }
                                },
                    ) {
                        Text(
                            text = overlay.text.ifBlank { "…" },
                            color = Color(overlay.color),
                            fontSize = overlay.sizeSp.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(
    state: EditState,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    onCrop: () -> Unit,
    onFilter: () -> Unit,
    onAddText: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(MaterialSymbols.Crop, stringRes(R.string.create_tool_crop), state.aspect.label, onCrop)
        ToolButton(MaterialSymbols.Rotate90DegreesCw, stringRes(R.string.create_tool_rotate), null, onRotate)
        ToolButton(MaterialSymbols.Flip, stringRes(R.string.create_tool_flip), null, onFlip)
        ToolButton(MaterialSymbols.Palette, stringRes(R.string.create_tool_filter), state.filter.label, onFilter)
        ToolButton(MaterialSymbols.TextFields, stringRes(R.string.create_tool_text), null, onAddText)
        ToolButton(MaterialSymbols.Refresh, stringRes(R.string.create_tool_reset), null, onReset)
    }
}

@Composable
private fun ToolButton(
    symbol: MaterialSymbol,
    label: String,
    value: String?,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(symbol, label, tint = Color.White)
        }
        Text(text = value ?: label, color = Color(0xAAFFFFFF), fontSize = 11.sp)
    }
}

@Composable
private fun TextInputBar(
    overlay: TextOverlay,
    onTextChange: (String) -> Unit,
    onColor: (Int) -> Unit,
    onSize: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = overlay.text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringRes(R.string.create_text_hint)) },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OVERLAY_COLORS.forEach { c ->
                Box(
                    modifier =
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(
                                width = if (c == overlay.color) 2.dp else 1.dp,
                                color = Color.White,
                                shape = CircleShape,
                            ).pointerInput(c) { detectTapGestures { onColor(c) } },
                )
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onSize((overlay.sizeSp - 4).coerceAtLeast(12)) }) {
                Icon(MaterialSymbols.Remove, stringRes(R.string.create_text_smaller), tint = Color.White)
            }
            IconButton(onClick = { onSize((overlay.sizeSp + 4).coerceAtMost(96)) }) {
                Icon(MaterialSymbols.Add, stringRes(R.string.create_text_bigger), tint = Color.White)
            }
            IconButton(onClick = onDelete) {
                Icon(MaterialSymbols.Delete, stringRes(R.string.create_text_delete), tint = Color.White)
            }
        }
    }
}

/**
 * Renders [media] with [state] applied (rotation, flip, crop, colour filter) plus the
 * text overlays, writing the result to a cache JPEG and returning it as a new
 * [SelectedMedia]. Returns null if decoding or encoding fails so the caller falls back
 * to the original.
 */
private suspend fun renderEdited(
    context: Context,
    media: SelectedMedia,
    state: EditState,
): SelectedMedia? =
    withContext(Dispatchers.IO) {
        try {
            val source = decodeSampled(context, media.uri, 2048) ?: return@withContext null

            val transform = Matrix()
            transform.postRotate(state.rotation.toFloat())
            if (state.flipped) transform.postScale(-1f, 1f)
            var working =
                if (state.rotation != 0 || state.flipped) {
                    Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
                } else {
                    source
                }

            state.aspect.ratio?.let { working = cropToAspect(working, it) }

            val output = createBitmap(working.width, working.height)
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            state.filter.matrix?.let { paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(it)) }
            canvas.drawBitmap(working, 0f, 0f, paint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
            val scale = working.width / 1080f
            state.overlays.forEach { overlay ->
                if (overlay.text.isBlank()) return@forEach
                textPaint.color = overlay.color
                textPaint.textSize = overlay.sizeSp * 3f * scale
                canvas.drawText(overlay.text, overlay.x * working.width, overlay.y * working.height, textPaint)
            }

            val dir = File(context.cacheDir, "edited").apply { mkdirs() }
            val file = File(dir, "edit_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { output.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (working !== source) working.recycle()
            source.recycle()

            SelectedMedia(
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file),
                "image/jpeg",
            )
        } catch (e: Exception) {
            null
        }
    }

private fun cropToAspect(
    source: Bitmap,
    ratio: Float,
): Bitmap {
    val current = source.width.toFloat() / source.height
    return if (abs(current - ratio) < 0.01f) {
        source
    } else if (current > ratio) {
        val w = (source.height * ratio).roundToInt()
        Bitmap.createBitmap(source, (source.width - w) / 2, 0, w, source.height)
    } else {
        val h = (source.width / ratio).roundToInt()
        Bitmap.createBitmap(source, 0, (source.height - h) / 2, source.width, h)
    }
}

/** Draws a bitmap centred inside a box of [boxW]×[boxH] preserving aspect for [rotation]. */
private fun fitRect(
    bmpW: Int,
    bmpH: Int,
    boxW: Float,
    boxH: Float,
    rotation: Int,
): RectF {
    val swapped = rotation % 180 != 0
    val w = if (swapped) bmpH.toFloat() else bmpW.toFloat()
    val h = if (swapped) bmpW.toFloat() else bmpH.toFloat()
    val scale = minOf(boxW / w, boxH / h)
    val dw = w * scale
    val dh = h * scale
    val left = (boxW - dw) / 2f
    val top = (boxH - dh) / 2f
    return RectF(left, top, left + dw, top + dh)
}

private fun decodeSampled(
    context: Context,
    uri: Uri,
    maxDimension: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}
