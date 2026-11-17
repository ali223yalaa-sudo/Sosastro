package com.astro.giftuploader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * A standalone, isolated gift uploader.
 *
 * It is deliberately NOT part of the Astro app: its own application id
 * (`com.astro.giftuploader`), its own launcher icon, and no shared code or
 * preferences. Its only job is to (1) start the gift dashboard server with a
 * single button, even when the server is stopped, and (2) show the dashboard
 * link so it can be opened in Chrome (or copied to another device) and used
 * there. A built-in WebView is offered as a convenience, but the link is the
 * primary output.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var urlView: TextView

    // WebView does not open the system picker for <input type=file> by itself.
    // The dashboard's "choose file" control is exactly that, so we must hold
    // the callback across the picker round-trip or the tap silently does
    // nothing and the gallery never appears.
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var filePicker: ActivityResultLauncher<Intent>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        status = findViewById(R.id.status)
        startBtn = findViewById(R.id.startBtn)
        urlView = findViewById(R.id.url)

        filePicker =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val callback = filePathCallback
                filePathCallback = null
                if (callback == null) return@registerForActivityResult

                val data = result.data
                val uris: Array<Uri>? =
                    when {
                        result.resultCode != Activity.RESULT_OK || data == null -> null
                        data.clipData != null -> {
                            val clip = data.clipData!!
                            Array(clip.itemCount) { clip.getItemAt(it).uri }
                        }
                        data.data != null -> arrayOf(data.data!!)
                        else -> null
                    }
                callback.onReceiveValue(uris)
            }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // The operator is used to the dashboard's two-column desktop grid,
            // so lay the page out at desktop width, then let pinch-zoom scale it
            // to whatever the phone's screen needs.
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "$userAgentString AstroGiftUploader/Desktop"
        }

        web.webViewClient =
            object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        showOffline(error.description?.toString() ?: "تعذّر الاتصال")
                    }
                }
            }

        web.webChromeClient =
            object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    return try {
                        filePicker.launch(buildPickerIntent(params))
                        true
                    } catch (e: Exception) {
                        filePathCallback = null
                        Toast.makeText(this@MainActivity, "تعذّر فتح المعرض: ${e.message}", Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }

        findViewById<Button>(R.id.reloadBtn).setOnClickListener { loadDashboard() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.copyBtn).setOnClickListener { copyLink() }
        findViewById<Button>(R.id.openBtn).setOnClickListener { openInChrome() }
        startBtn.setOnClickListener { startServer() }

        urlView.text = Config.dashboardUrl(this)

        // Start the server on launch, then show the dashboard. The button stays
        // available so a stopped server is one tap away from running again.
        startServer()
    }

    /**
     * Build the gallery picker. The dashboard accepts video and animated
     * images, so offer both; `EXTRA_MIME_TYPES` keeps the picker from hiding
     * videos the way a single narrow type would.
     */
    private fun buildPickerIntent(params: WebChromeClient.FileChooserParams): Intent {
        val accept = params.acceptTypes.filter { it.isNotBlank() && it != "*/*" }
        val intent =
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (accept.isEmpty()) "*/*" else accept.first()
                if (accept.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accept.toTypedArray())
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        return Intent.createChooser(intent, "اختر فيديو الهدية")
    }

    private fun loadDashboard() {
        val url = Config.dashboardUrl(this)
        urlView.text = url
        status.visibility = View.VISIBLE
        status.text = "جارٍ تحميل اللوحة…\n$url"
        web.loadUrl(url)
    }

    /** Copy the dashboard link so it can be pasted into Chrome on this or any device. */
    private fun copyLink() {
        val url = Config.dashboardUrl(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("gift dashboard", url))
        Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
    }

    /** Open the dashboard in Chrome; fall back to the default browser when absent. */
    private fun openInChrome() {
        val url = Config.dashboardUrl(this)
        val chrome = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome")
        val generic = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(chrome)
        } catch (e: Exception) {
            startActivity(Intent.createChooser(generic, "افتح الرابط في المتصفح"))
        }
    }

    private fun showOffline(detail: String) {
        runOnUiThread {
            status.visibility = View.VISIBLE
            status.text = "الخادم متوقف — اضغط «تشغيل الخادم» لإعادة تشغيله.\n$detail"
        }
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
                        runOnUiThread {
                            lines.append(line).append('\n')
                            status.text = lines.toString()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        lines.append("خطأ: ${e.message}").append('\n')
                        status.text = lines.toString()
                    }
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
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }

        fun field(
            label: String,
            value: String,
        ): EditText {
            box.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value)
                box.addView(this)
            }
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
