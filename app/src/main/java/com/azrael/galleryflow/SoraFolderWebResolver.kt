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
                web.settings.userAgentString = HttpClient.USER_AGENT
                web.onResume()
                web.resumeTimers()

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
        private const val TIMEOUT_SECONDS = 45L

        private val RESOLVER_SCRIPT = """
            (function() {
              let finished = false;
              function send(value) {
                if (finished) return;
                finished = true;
                try { window.GalleryFlowBridge.onResolved(String(value || '')); } catch (_) {}
              }

              async function attempt(secondTry) {
                try {
                  if (typeof keyEncrypte === 'undefined' || typeof decryptLink !== 'function') {
                    if (secondTry) {
                      send('ERROR:SoraFolder page scripts did not initialize');
                    } else {
                      setTimeout(function() { attempt(false); }, 250);
                    }
                    return;
                  }

                  const response = await fetch('/file-down', {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                      'Accept': 'application/json, text/plain, */*',
                      'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({key: keyEncrypte})
                  });

                  const text = await response.text();
                  let data;
                  try {
                    data = JSON.parse(text);
                  } catch (_) {
                    if (!secondTry) {
                      setTimeout(function() { attempt(true); }, 10500);
                      return;
                    }
                    send('ERROR:SoraFolder returned non-JSON response: ' + text.slice(0, 160));
                    return;
                  }

                  if (data && data.error) {
                    if (!secondTry) {
                      setTimeout(function() { attempt(true); }, 10500);
                      return;
                    }
                    send('ERROR:' + String(data.error));
                    return;
                  }

                  const encrypted = data && (data.url || data.link || data.download);
                  const direct = decryptLink(encrypted || '');
                  if (typeof direct === 'string' && /^https?:\/\//i.test(direct)) {
                    send(direct);
                    return;
                  }

                  if (!secondTry) {
                    setTimeout(function() { attempt(true); }, 10500);
                    return;
                  }
                  send('ERROR:SoraFolder decryptor returned no HTTP URL');
                } catch (e) {
                  if (!secondTry) {
                    setTimeout(function() { attempt(true); }, 10500);
                    return;
                  }
                  send('ERROR:' + (e && e.message ? e.message : String(e)));
                }
              }

              // Poll until SoraFolder's own obfuscated script has defined its key/decryptor.
              let polls = 0;
              const ready = setInterval(function() {
                polls++;
                if (typeof keyEncrypte !== 'undefined' && typeof decryptLink === 'function') {
                  clearInterval(ready);
                  attempt(false);
                } else if (polls >= 40) {
                  clearInterval(ready);
                  attempt(true);
                }
              }, 250);
            })();
        """.trimIndent()
    }
}
