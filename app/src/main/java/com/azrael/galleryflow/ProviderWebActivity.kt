package com.azrael.galleryflow

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ProviderWebActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 18, 22)
        window.navigationBarColor = Color.rgb(17, 18, 22)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            text = "Provider session"
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = HttpClient.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    status.text = when {
                        url.isNullOrBlank() -> "Provider session"
                        url.contains("/wap/outlogin", ignoreCase = true) -> "TeraBox sign-in"
                        url.contains("terabox.com", ignoreCase = true) -> "TeraBox web session active"
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

        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") }
            ?: TERABOX_LOGIN_URL
        webView.loadUrl(url)
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
    }
}
