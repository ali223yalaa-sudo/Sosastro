package com.astro.giftuploader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the sandbox's agent-server API to start (or wake) the dashboard
 * server, and probes the public URL to know when it is actually serving.
 *
 * All calls are blocking; callers run them off the main thread.
 */
object ServerControl {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** POST a bash command to the sandbox; returns the command's stdout. */
    fun runCommand(sandboxUrl: String, sessionKey: String, command: String): String {
        val body = JSONObject().put("command", command).toString().toRequestBody(JSON)
        val req =
            Request.Builder()
                .url("${sandboxUrl.trimEnd('/')}/api/bash/execute_bash_command")
                .addHeader("X-Session-API-Key", sessionKey)
                .post(body)
                .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) error("agent-server HTTP ${resp.code}: ${text.take(300)}")
            return JSONObject(text).optString("stdout", "")
        }
    }

    /** GET the dashboard URL; returns the HTTP status, or -1 when unreachable. */
    fun probe(dashboardUrl: String): Int {
        val req = Request.Builder().url(dashboardUrl).get().build()
        return try {
            client.newCall(req).execute().use { it.code }
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Start the server if it is down, then wait for the public URL to answer.
     * `log` receives short human-readable progress lines.
     */
    fun ensureRunning(ctx: android.content.Context, log: (String) -> Unit): Boolean {
        val sandbox = Config.sandboxUrl(ctx)
        val key = Config.sessionKey(ctx)
        val dashboard = Config.dashboardUrl(ctx)

        if (probe(dashboard) in 200..399) {
            log("الخادم يعمل بالفعل ✓")
            return true
        }

        log("إيقاظ الخادم في السحابة…")
        val out = runCommand(sandbox, key, Config.START_COMMAND)
        log("تم إرسال أمر التشغيل" + if (out.isNotBlank()) " (${out.trim().take(60)})" else "")

        repeat(15) { i ->
            Thread.sleep(2000)
            val code = probe(dashboard)
            if (code in 200..399) {
                log("الخادم جاهز ✓ (HTTP $code)")
                return true
            }
            log("في انتظار الجهوزية… ${i + 1}/15")
        }
        log("لم يستجب الخادم بعد — تحقّق من رابط اللوحة في الإعدادات")
        return false
    }
}
