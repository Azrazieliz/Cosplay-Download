package com.azrael.galleryflow

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SoraFolderWebResolver(private val context: Context) {
    private class Bridge(
        private val callback: (String) -> Unit
    ) {
        @JavascriptInterface
        fun onResolved(value: String?) {
            callback(value.orEmpty())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(url: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw AdapterException("SoraFolder resolver cannot block the Android main thread.")
        }

        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        val error = AtomicReference<String?>(null)
        val completed = AtomicBoolean(false)
        val webRef = AtomicReference<WebView?>(null)
        val main = Handler(Looper.getMainLooper())

        fun finish(value: String) {
            if (!completed.compareAndSet(false, true)) return
            if (value.startsWith("http://") || value.startsWith("https://")) {
                result.set(value)
            } else {
                error.set(value.removePrefix("ERROR:").ifBlank { "SoraFolder returned no direct URL." })
            }
            latch.countDown()
        }

        main.post {
            try {
                val web = WebView(context)
                webRef.set(web)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.databaseEnabled = true
                web.settings.javaScriptCanOpenWindowsAutomatically = false
                web.settings.setSupportMultipleWindows(false)

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(web, true)
                }

                web.addJavascriptInterface(Bridge(::finish), BRIDGE)
                var injected = false
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        if (injected || pageUrl.isNullOrBlank() || !pageUrl.contains("sorafolder.com")) return
                        injected = true
                        view?.evaluateJavascript(RESOLVER_SCRIPT, null)
                    }
                }
                web.loadUrl(url)
            } catch (t: Throwable) {
                finish("ERROR:" + (t.message ?: t.javaClass.simpleName))
            }
        }

        val signalled = try {
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        main.post {
            webRef.getAndSet(null)?.let { web ->
                runCatching { web.removeJavascriptInterface(BRIDGE) }
                runCatching { web.stopLoading() }
                runCatching { web.destroy() }
            }
        }

        if (!signalled) {
            throw InteractiveProviderRequiredException(
                "SoraFolder",
                url,
                "SoraFolder timed download did not resolve within " + TIMEOUT_SECONDS + "s."
            )
        }

        result.get()?.let { return it }
        throw InteractiveProviderRequiredException(
            "SoraFolder",
            url,
            error.get() ?: "SoraFolder did not return a downloadable file URL."
        )
    }

    companion object {
        private const val BRIDGE = "GalleryFlowBridge"
        private const val TIMEOUT_SECONDS = 40L

        private val RESOLVER_SCRIPT = """
            (function() {
              function send(value) {
                try { window.GalleryFlowBridge.onResolved(String(value || '')); } catch (_) {}
              }
              async function resolveGalleryFlowDownload() {
                try {
                  if (typeof keyEncrypte === 'undefined') {
                    send('ERROR:SoraFolder key is unavailable');
                    return;
                  }
                  if (typeof decryptLink !== 'function') {
                    send('ERROR:SoraFolder decryptor is unavailable');
                    return;
                  }
                  const response = await fetch('/file-down', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({key: keyEncrypte})
                  });
                  const data = await response.json();
                  if (data && data.error) {
                    send('ERROR:' + String(data.error));
                    return;
                  }
                  const direct = decryptLink(data && data.url ? data.url : '');
                  send(direct);
                } catch (e) {
                  send('ERROR:' + (e && e.message ? e.message : String(e)));
                }
              }
              setTimeout(resolveGalleryFlowDownload, 10500);
            })();
        """.trimIndent()
    }
}
