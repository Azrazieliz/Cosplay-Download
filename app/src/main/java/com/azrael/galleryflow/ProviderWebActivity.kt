package com.azrael.galleryflow

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URI
import java.util.Locale

class ProviderWebActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var waitingForGoogleReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 18, 22)
        window.navigationBarColor = Color.rgb(17, 18, 22)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            text = "TeraBox session"
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = settings.userAgentString + " GalleryFlow/0.2.2"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    return handleExternalAuth(url)
                }

                @Deprecated("Deprecated in Android")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    handleExternalAuth(url.orEmpty())

                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    status.text = when {
                        url.isNullOrBlank() -> "TeraBox session"
                        url.contains("/wap/outlogin", ignoreCase = true) ->
                            "TeraBox sign-in • Google opens in your browser"
                        url.contains("terabox.com", ignoreCase = true) ->
                            "TeraBox session active"
                        else -> "Provider page"
                    }
                }
            }

            webChromeClient = WebChromeClient()
            setDownloadListener(providerDownloadListener())
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 18, 22))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        if (savedInstanceState == null) {
            val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") }
                ?: TERABOX_LOGIN_URL
            webView.loadUrl(url)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForGoogleReturn && ::webView.isInitialized) {
            waitingForGoogleReturn = false
            status.text = "Checking TeraBox session…"
            webView.postDelayed({
                CookieManager.getInstance().flush()
                webView.loadUrl(TERABOX_HOME_URL)
            }, 500L)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun handleExternalAuth(url: String): Boolean {
        if (url.isBlank()) return false

        if (url.startsWith("intent://", ignoreCase = true)) {
            return runCatching {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                startActivity(intent)
                true
            }.getOrDefault(false)
        }

        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        val isGoogleAuth =
            host == "accounts.google.com" ||
                host.endsWith(".accounts.google.com") ||
                host == "oauth2.googleapis.com" ||
                host.endsWith(".googleusercontent.com")

        if (!isGoogleAuth) return false

        waitingForGoogleReturn = true
        status.text = "Finish Google sign-in in your browser, then return to GalleryFlow"
        return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        }.getOrElse {
            waitingForGoogleReturn = false
            Toast.makeText(this, "No browser available for Google sign-in", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun providerDownloadListener() = DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        runCatching {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", userAgent ?: HttpClient.USER_AGENT)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Cosplay/GalleryFlow/provider_downloads/" + safeFileName(filename)
                )
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
        }.onSuccess {
            Toast.makeText(
                this,
                "Provider download queued in Downloads/Cosplay/GalleryFlow/provider_downloads",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: "Could not queue provider download", Toast.LENGTH_LONG).show()
        }
    }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(160)
        .ifBlank { "download.bin" }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URL = "url"
        const val TERABOX_LOGIN_URL = "https://www.terabox.com/wap/outlogin"
        const val TERABOX_HOME_URL = "https://www.terabox.com/"
    }
}
