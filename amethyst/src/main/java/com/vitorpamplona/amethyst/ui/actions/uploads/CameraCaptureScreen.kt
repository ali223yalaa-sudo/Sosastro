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
package com.vitorpamplona.amethyst.ui.actions.uploads

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.commons.icons.symbols.Icon
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbol
import com.vitorpamplona.amethyst.commons.icons.symbols.MaterialSymbols
import com.vitorpamplona.amethyst.ui.stringRes
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Photo or video capture mode inside [CameraCaptureScreen]. */
enum class CaptureMode { PHOTO, VIDEO }

/** Flash behaviour for photo capture. */
private enum class FlashMode(
    val symbol: MaterialSymbol,
    val labelRes: Int,
) {
    OFF(MaterialSymbols.FlashOff, R.string.camera_flash_off),
    ON(MaterialSymbols.FlashOn, R.string.camera_flash_on),
    AUTO(MaterialSymbols.FlashAuto, R.string.camera_flash_auto),
    ;

    fun next(): FlashMode = entries[(ordinal + 1) % entries.size]
}

private const val MAX_VIDEO_DURATION_MS = 60_000L
private val TIMER_OPTIONS = listOf(0, 3, 10)

/**
 * A full-screen CameraX capture surface used by the create flow. It offers:
 * live preview, photo and video modes, front/back switching, flash (photo) and
 * torch (video), a countdown timer, and a shutter button. Captures are written to
 * the app's external files dir and returned as [SelectedMedia] through
 * [onMediaCaptured] so they feed the same composer the gallery does.
 *
 * Camera hardware is bound in a [LaunchedEffect] keyed on the lens and mode and
 * released in a [DisposableEffect] so the lens is never left claimed after the
 * screen leaves composition.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraCaptureScreen(
    initialMode: CaptureMode = CaptureMode.PHOTO,
    onMediaCaptured: (ImmutableList<SelectedMedia>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mode by remember { mutableStateOf(initialMode) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flash by remember { mutableStateOf(FlashMode.OFF) }
    var timerSeconds by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordStartMs by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val audioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    if (!cameraPermission.status.isGranted) {
        CameraPermissionGate(
            onClose = onClose,
            onRequest = { cameraPermission.launchPermissionRequest() },
        )
        return
    }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }

    LaunchedEffect(lensFacing, mode, cameraPermission.status.isGranted) {
        val provider = context.cameraProvider()
        val selector =
            CameraSelector
                .Builder()
                .requireLensFacing(lensFacing)
                .build()

        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

        val ic =
            ImageCapture
                .Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        val vc = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            if (mode == CaptureMode.PHOTO) {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, ic)
            } else {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, vc)
            }
            imageCapture = ic
            videoCapture = vc
        } catch (e: Exception) {
            imageCapture = null
            videoCapture = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            elapsedMs = SystemClock.elapsedRealtime() - recordStartMs
            if (elapsedMs >= MAX_VIDEO_DURATION_MS) {
                recording?.stop()
                break
            }
            delay(100)
        }
    }

    fun capturePhoto() {
        val ic = imageCapture ?: return
        ic.flashMode =
            when (flash) {
                FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            }
        busy = true
        val file = newOutputFile(context, "JPEG", ".jpg", Environment.DIRECTORY_PICTURES)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    busy = false
                    onMediaCaptured(persistentListOf(SelectedMedia(uriFor(context, file), "image/jpeg")))
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                }
            },
        )
    }

    fun toggleRecording() {
        if (isRecording) {
            recording?.stop()
            return
        }
        val vc = videoCapture ?: return
        val file = newOutputFile(context, "MP4", ".mp4", Environment.DIRECTORY_MOVIES)
        val options = FileOutputOptions.Builder(file).build()

        var pending = vc.output.prepareRecording(context, options)
        if (audioPermission.status.isGranted) {
            pending = pending.withAudioEnabled()
        }

        recording =
            pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        recordStartMs = SystemClock.elapsedRealtime()
                        elapsedMs = 0
                    }

                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        recording = null
                        val uri = event.outputResults.outputUri
                        if (event.error == VideoRecordEvent.Finalize.ERROR_NONE && uri != Uri.EMPTY) {
                            onMediaCaptured(persistentListOf(SelectedMedia(uri, "video/mp4")))
                        }
                    }
                }
            }
    }

    fun onShutter() {
        if (busy) return
        if (mode == CaptureMode.VIDEO) {
            toggleRecording()
            return
        }
        if (timerSeconds == 0) {
            capturePhoto()
        } else {
            scope.launch {
                for (i in timerSeconds downTo 1) {
                    countdown = i
                    delay(1000)
                }
                countdown = null
                capturePhoto()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // Top controls
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(MaterialSymbols.Close, stringRes(R.string.create_close), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { timerSeconds = TIMER_OPTIONS[(TIMER_OPTIONS.indexOf(timerSeconds) + 1) % TIMER_OPTIONS.size] }) {
                Text(
                    text = if (timerSeconds == 0) stringRes(R.string.camera_timer) else "${timerSeconds}s",
                    color = if (timerSeconds == 0) Color.White else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = { flash = flash.next() }) {
                Icon(flash.symbol, stringRes(flash.labelRes), tint = Color.White)
            }
            IconButton(
                onClick = {
                    lensFacing =
                        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                },
            ) {
                Icon(MaterialSymbols.Cameraswitch, stringRes(R.string.camera_switch_lens), tint = Color.White)
            }
        }

        countdown?.let { value ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = value.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .background(Color(0x99000000), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(Color.Red, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(
                    text = formatDuration(elapsedMs),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Bottom controls
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringRes(R.string.camera_mode_photo),
                    color = if (mode == CaptureMode.PHOTO) Color.White else Color(0x88FFFFFF),
                    fontWeight = if (mode == CaptureMode.PHOTO) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { if (!isRecording) mode = CaptureMode.PHOTO },
                )
                Text(
                    text = stringRes(R.string.camera_mode_video),
                    color = if (mode == CaptureMode.VIDEO) Color.White else Color(0x88FFFFFF),
                    fontWeight = if (mode == CaptureMode.VIDEO) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { if (!isRecording) mode = CaptureMode.VIDEO },
                )
            }

            Spacer(Modifier.height(20.dp))

            ShutterButton(
                isVideo = mode == CaptureMode.VIDEO,
                isRecording = isRecording,
                enabled = !busy,
                onClick = { onShutter() },
            )
        }

        if (busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun ShutterButton(
    isVideo: Boolean,
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(76.dp)
                .border(4.dp, Color.White, CircleShape)
                .padding(6.dp)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        color = if (isRecording) Color.Red else Color.White,
                        shape = if (isVideo) CircleShape else CircleShape,
                    ),
        )
    }
}

@Composable
private fun CameraPermissionGate(
    onClose: () -> Unit,
    onRequest: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringRes(R.string.camera_permission_needed),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringRes(R.string.create_close),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onClose() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringRes(R.string.camera_shutter),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onRequest() },
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun newOutputFile(
    context: Context,
    prefix: String,
    extension: String,
    storageDir: String,
): File {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val dir = context.getExternalFilesDir(storageDir) ?: context.cacheDir
    return File.createTempFile("${prefix}_${timeStamp}_", extension, dir)
}

private fun uriFor(
    context: Context,
    file: File,
): Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

private suspend fun Context.cameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
