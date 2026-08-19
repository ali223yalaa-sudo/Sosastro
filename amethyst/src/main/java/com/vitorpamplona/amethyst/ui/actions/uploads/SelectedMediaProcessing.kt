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

import com.vitorpamplona.amethyst.service.uploads.AstroMediaAnalysisClient
import com.vitorpamplona.amethyst.service.uploads.UploadOrchestrator
import com.vitorpamplona.amethyst.service.uploads.UploadingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BasketAnalysisStatus {
    Idle,
    Analyzing,
    Analyzed,
    Failed,
}

class SelectedMediaProcessing(
    val media: SelectedMedia,
    val orchestrator: UploadOrchestrator = UploadOrchestrator(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _analysisStatus = MutableStateFlow(BasketAnalysisStatus.Idle)
    val analysisStatus: StateFlow<BasketAnalysisStatus> = _analysisStatus.asStateFlow()

    init {
        // Real analysis only after blossom/NIP-96 URL exists — never fake "Analyzed".
        scope.launch {
            orchestrator.progressState.collect { state ->
                when (state) {
                    is UploadingState.Ready -> {
                        // Selected but not uploaded yet — no badge (Idle).
                        if (_analysisStatus.value == BasketAnalysisStatus.Analyzing) {
                            // keep analyzing if a prior request is in flight
                        } else if (_analysisStatus.value != BasketAnalysisStatus.Analyzed &&
                            _analysisStatus.value != BasketAnalysisStatus.Failed
                        ) {
                            _analysisStatus.value = BasketAnalysisStatus.Idle
                        }
                    }
                    is UploadingState.Compressing,
                    is UploadingState.Uploading,
                    is UploadingState.ServerProcessing,
                    is UploadingState.Downloading,
                    is UploadingState.Hashing,
                    -> {
                        _analysisStatus.value = BasketAnalysisStatus.Analyzing
                    }
                    is UploadingState.Error -> {
                        // Upload failed — analysis cannot run.
                        _analysisStatus.value = BasketAnalysisStatus.Failed
                    }
                    is UploadingState.Finished -> {
                        _analysisStatus.value = BasketAnalysisStatus.Analyzing
                        val url =
                            when (val result = state.result) {
                                is UploadOrchestrator.OrchestratorResult.ServerResult -> result.url
                                is UploadOrchestrator.OrchestratorResult.NIP95Result -> null
                            }
                        if (url.isNullOrBlank()) {
                            _analysisStatus.value = BasketAnalysisStatus.Failed
                            return@collect
                        }
                        val resp =
                            AstroMediaAnalysisClient.analyze(
                                mediaUrl = url,
                                mime = media.mimeType,
                                caption = null,
                            )
                        _analysisStatus.value =
                            when (resp.status) {
                                "done", "done_degraded" -> BasketAnalysisStatus.Analyzed
                                "pending" -> BasketAnalysisStatus.Analyzing
                                else -> BasketAnalysisStatus.Failed
                            }
                    }
                }
            }
        }
    }
}
