package com.astro.giftuploader

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * A standalone, isolated gift uploader.
 *
 * It is deliberately NOT part of the Astro app: its own application id
 * (`com.astro.giftuploader`), its own launcher icon, and no shared code or
 * preferences. Its only job is to (1) start the gift dashboard server in the
 * cloud sandbox with a single button, even when the server is stopped, and
 * (2) render that dashboard so gifts can be uploaded right away.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var startBtn: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        status = findViewById(R.id.status)
        startBtn = findViewById(R.id.startBtn)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()

        findViewById<Button>(R.id.reloadBtn).setOnClickListener { loadDashboard() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { showSettings() }
        startBtn.setOnClickListener { startServer() }

        startServer()
    }

    private fun loadDashboard() {
        val url = Config.dashboardUrl(this)
        status.text = "جارٍ تحميل اللوحة…\n$url"
        web.loadUrl(url)
    }

    private fun startServer() {
        startBtn.isEnabled = false
        status.visibility = View.VISIBLE
        status.text = "بدء التشغيل…"
        thread {
            val lines = StringBuilder()
            val ok =
                try {
                    ServerControl.ensureRunning(this) { line ->
                        runOnUiThread { lines.append(line).append('\n'); status.text = lines.toString() }
                    }
                } catch (e: Exception) {
                    runOnUiThread { lines.append("خطأ: ${e.message}").append('\n'); status.text = lines.toString() }
                    false
                }
            runOnUiThread {
                startBtn.isEnabled = true
                if (ok) {
                    loadDashboard()
                    status.postDelayed({ status.visibility = View.GONE }, 2500)
                }
            }
        }
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply { text = label })
            return EditText(this).apply { setText(value); box.addView(this) }
        }

        val sandbox = field("رابط الساندبوكس (agent-server):", Config.sandboxUrl(this))
        val key = field("مفتاح الجلسة (session key):", Config.sessionKey(this))
        val dash = field("رابط اللوحة (dashboard URL):", Config.dashboardUrl(this))

        AlertDialog.Builder(this)
            .setTitle("إعدادات الخادم")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                Config.save(this, sandbox.text.toString(), key.text.toString(), dash.text.toString())
                Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
                startServer()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
