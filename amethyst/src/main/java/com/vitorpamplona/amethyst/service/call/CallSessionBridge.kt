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
package com.vitorpamplona.amethyst.service.call

import com.vitorpamplona.amethyst.Amethyst
import com.vitorpamplona.amethyst.commons.nipACWebRtcCalls.CallManager
import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.quartz.utils.Log

/**
 * Process-level singleton that exposes the active call state machine to
 * components that live outside the main Compose tree:
 * [com.vitorpamplona.amethyst.ui.call.CallActivity] (its own window),
 * [CallNotificationReceiver] and [CallForegroundService].
 *
 * The [CallManager] itself is owned by the [Account] so it keeps running
 * headlessly after the main Activity — and its Activity-scoped
 * [AccountViewModel] — are destroyed (app swiped from Recents, screen off).
 * This object only carries pointers; it never owns or resets call state.
 *
 * No call controller / session is held here — each [CallActivity]
 * creates and owns its own [com.vitorpamplona.amethyst.ui.call.session.CallSession]
 * whose lifetime is tied to the Activity's lifecycle.
 */
object CallSessionBridge {
    @Volatile
    var account: Account? = null
        private set

    @Volatile
    var accountViewModel: AccountViewModel? = null
        private set

    /**
     * Lazily-built ViewModel used only when the main Compose tree is not
     * mounted (headless call UI). Kept out of [accountViewModel] so
     * [clearAccountViewModel] never drops it.
     */
    @Volatile
    private var headlessViewModel: AccountViewModel? = null

    /**
     * True while a [com.vitorpamplona.amethyst.ui.call.CallActivity] is on
     * screen. That Activity's own [CallSession] owns ringing and the call
     * foreground service, so [HeadlessCallCoordinator] must stand down to
     * avoid a second ringtone and a duplicate service — even when
     * [accountViewModel] is null because the call was cold-started from a
     * notification (its ViewModel is the lazy headless one, not the Activity's).
     * A StateFlow so the coordinator re-evaluates the moment it flips.
     */
    private val _isCallUiMounted = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isCallUiMounted: kotlinx.coroutines.flow.StateFlow<Boolean> = _isCallUiMounted

    fun setCallUiMounted(mounted: Boolean) {
        _isCallUiMounted.value = mounted
    }

    /** The account-owned call manager, if an account is active. */
    val callManager: CallManager?
        get() = account?.callManager

    /** Points the bridge at the active account. Called on login/switch. */
    fun setAccount(account: Account) {
        this.account = account
    }

    /** Registers the Activity-scoped ViewModel for UI lookups. */
    fun setAccountViewModel(accountViewModel: AccountViewModel) {
        this.accountViewModel = accountViewModel
    }

    /**
     * Drops only the ViewModel pointer. Called from
     * [AccountViewModel.onCleared]: the ViewModel is Activity-scoped and dies
     * with the Activity, but the call must survive, so the [account] (and thus
     * [callManager]) is deliberately kept.
     */
    fun clearAccountViewModel() {
        accountViewModel = null
    }

    /** Full teardown on logout / account switch. */
    fun clear() {
        account = null
        accountViewModel = null
        headlessViewModel = null
        _isCallUiMounted.value = false
    }

    /**
     * Last-resort [AccountViewModel] for the call UI when the main Compose
     * tree is not mounted (cold start from an incoming-call notification, or
     * the app was swiped from Recents while ringing). Built lazily from the
     * app-scoped [Account] and the process-level app modules, and cached for
     * the lifetime of that account so re-entering the call reuses it.
     *
     * Returns null if no account is active or the app modules are not ready
     * (e.g. the sandboxed napplet process).
     */
    fun resolveViewModel(): AccountViewModel? {
        accountViewModel?.let { return it }
        val acc = account ?: return null
        headlessViewModel?.let { if (it.account === acc) return it }

        return try {
            val modules = Amethyst.instance
            AccountViewModel(
                account = acc,
                settings = modules.uiState,
                torSettings = modules.torPrefs.value,
                dataSources = modules.sources,
                httpClientBuilder = modules.roleBasedHttpClientBuilder,
                nip05ClientBuilder = { modules.nip05Client },
            ).also { headlessViewModel = it }
        } catch (e: Exception) {
            Log.e("CallSessionBridge") { "Headless AccountViewModel unavailable: ${e.message}" }
            null
        }
    }
}
