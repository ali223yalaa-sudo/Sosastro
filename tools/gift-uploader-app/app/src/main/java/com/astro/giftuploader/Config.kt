package com.astro.giftuploader

import android.content.Context
import android.content.SharedPreferences

/**
 * Connection settings for the gift dashboard's runtime.
 *
 * The dashboard itself (server.py) runs in an OpenHands cloud sandbox. The app
 * is a thin shell: it starts that server through the sandbox's agent-server API
 * and then renders the dashboard's public URL in a WebView. Nothing here is
 * shared with the Astro app — this is a separate, isolated package.
 */
object Config {
    private const val PREFS = "gift_uploader"

    // The sandbox that hosts the dashboard server (agent-server API base).
    const val DEFAULT_SANDBOX_URL = "https://zhsyxfiuhcicjfrr.prod-runtime.all-hands.dev"
    // Session key for the agent-server API (X-Session-API-Key).
    const val DEFAULT_SESSION_KEY = "4f7b74add94747b8be727e5c39fabde4"
    // The public URL the WebView loads (Cloudflare work host in front of the sandbox).
    const val DEFAULT_DASHBOARD_URL = "https://work-1-zhsyxfiuhcicjfrr.prod-runtime.all-hands.dev/"
    const val DEFAULT_PORT = 12000

    // The exact command the dashboard's own docs use to serve it.
    const val START_COMMAND =
        "cd /workspace/astro-backup/tools/gift-dashboard && " +
            "(pgrep -f 'server.py --port 12000' >/dev/null 2>&1 || " +
            "nohup /workspace/venv/bin/python server.py --port 12000 --also-port 12001 " +
            "> /workspace/giftdash.log 2>&1 &) ; sleep 1 ; echo started"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sandboxUrl(ctx: Context): String =
        prefs(ctx).getString("sandbox_url", DEFAULT_SANDBOX_URL) ?: DEFAULT_SANDBOX_URL

    fun sessionKey(ctx: Context): String =
        prefs(ctx).getString("session_key", DEFAULT_SESSION_KEY) ?: DEFAULT_SESSION_KEY

    fun dashboardUrl(ctx: Context): String =
        prefs(ctx).getString("dashboard_url", DEFAULT_DASHBOARD_URL) ?: DEFAULT_DASHBOARD_URL

    fun save(ctx: Context, sandbox: String, key: String, dashboard: String) {
        prefs(ctx).edit()
            .putString("sandbox_url", sandbox.trim().trimEnd('/'))
            .putString("session_key", key.trim())
            .putString("dashboard_url", dashboard.trim())
            .apply()
    }
}
