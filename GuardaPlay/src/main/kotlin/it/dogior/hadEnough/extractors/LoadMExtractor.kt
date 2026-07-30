package it.dogior.hadEnough.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class LoadMExtractor : ExtractorApi() {
    override val name = "LoadM"
    override val mainUrl = "loadm.cam"
    override val requiresReferer = false

    companion object {
        private const val TAG = "LoadMExtractor"
        private const val TIMEOUT_SECONDS = 30L

        private fun getApplicationContext(): Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
                val activityThread = currentActivityThreadMethod.invoke(null)
                val getApplicationMethod = activityThreadClass.getMethod("getApplication")
                getApplicationMethod.invoke(activityThread) as? Application
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Application context: ${e.message}")
                null
            }
        }

        private fun isVideoUrl(url: String): Boolean {
            val lowerUrl = url.lowercase()
            if (lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") ||
                lowerUrl.contains(".png") || lowerUrl.contains(".webp") ||
                lowerUrl.contains(".gif") || lowerUrl.contains(".svg") ||
                lowerUrl.contains(".css") || lowerUrl.contains(".js") ||
                lowerUrl.contains("favicon") || lowerUrl.contains("poster") ||
                lowerUrl.contains("thumbnail") || lowerUrl.contains("image")) {
                Log.d(TAG, "⛔ isVideoUrl() -> FALSE (img/css/js) per: ${url.take(200)}")
                return false
            }
            val result = lowerUrl.contains("m3u8") || lowerUrl.contains(".mp4")
            Log.d(TAG, if (result) "✅ isVideoUrl() -> TRUE per: ${url.take(200)}" else "⛔ isVideoUrl() -> FALSE (no m3u8/mp4) per: ${url.take(200)}")
            return result
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d(TAG, "🚀 getUrl() chiamato con url: $url")
        Log.d(TAG, "🔗 Referer ricevuto: $referer")

        val videoUrl = extractWithWebView(url)

        if (videoUrl != null) {
            Log.i(TAG, "🎬🎬🎬 VIDEO TROVATO! URL: $videoUrl")
            val cookies = try {
                android.webkit.CookieManager.getInstance().getCookie(videoUrl)
            } catch (_: Exception) { null }
            val headers = mutableMapOf("referer" to "https://loadm.cam/")
            if (!cookies.isNullOrEmpty()) headers["Cookie"] = cookies
            Log.d(TAG, "🍪 Cookies: $cookies")
            M3u8Helper.generateM3u8(
                name, videoUrl, url,
                headers = headers
            ).forEach { link ->
                Log.d(TAG, "📤 Callback inviato: ${link.url}")
                callback(link)
            }
        } else {
            Log.e(TAG, "💀💀💀 extractWithWebView() ha restituito NULL! Nessun video trovato!")
        }
    }

    private suspend fun extractWithWebView(embedUrl: String): String? {
        Log.d(TAG, "🕸️ extractWithWebView() chiamato con embedUrl: $embedUrl")
        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            var found = false
            var requestCount = 0
            var pageLoadCount = 0

            Log.d(TAG, "🔧 Acquisizione context...")
            Handler(Looper.getMainLooper()).post {
                try {
                    val context = getApplicationContext() ?: run {
                        Log.e(TAG, "❌❌❌ getApplicationContext() ha restituito NULL!")
                        continuation.resume(null)
                        return@post
                    }
                    Log.d(TAG, "✅ Context acquisito: $context")

                    @SuppressLint("SetJavaScriptEnabled")
                    val webView = WebView(context)
                    Log.d(TAG, "🌐 WebView creata")

                    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = false
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
                    }
                    Log.d(TAG, "⚙️ WebView settings configurati")

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val reqUrl = request?.url.toString()
                            Log.d(TAG, "🔄 shouldOverrideUrlLoading -> $reqUrl")
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url.toString()
                            requestCount++
                            val method = request?.method ?: "GET"

                            Log.d(TAG, "📡 [#$requestCount] $method -> ${requestUrl.take(300)}")

                            if (!found && isVideoUrl(requestUrl)) {
                                found = true
                                Log.i(TAG, "🎯🎯🎯 TARGET ACQUIRED !!! -> $requestUrl")
                                Log.i(TAG, "🎯🎯🎯 Trovato dopo $requestCount richieste totali")

                                Handler(Looper.getMainLooper()).postDelayed({
                                    webView.stopLoading()
                                    webView.destroy()
                                    latch.countDown()
                                    continuation.resume(requestUrl)
                                }, 2000)
                                return null
                            }

                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            pageLoadCount++
                            Log.d(TAG, "📄 [$pageLoadCount] onPageStarted: $pageUrl")
                        }

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            pageLoadCount++
                            Log.d(TAG, "✅ [$pageLoadCount] onPageFinished: $pageUrl")
                            super.onPageFinished(view, pageUrl)

                            if (pageUrl?.contains("loadm.cam") == true || pageUrl?.contains("loadm.") == true) {
                                Log.d(TAG, "⏳ Attendo 1.5s poi clicco #player-button...")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    Log.d(TAG, "💉 Eseguo evaluateJavascript...")
                                    webView.evaluateJavascript("""
                                        (function() {
                                            var btn = document.getElementById('player-button');
                                            if (btn) { btn.click(); console.log('🖱️ clicked #player-button'); }
                                            else { console.log('⚠️ #player-button non trovato'); }
                                        })();
                                    """.trimIndent(), null)
                                }, 1500)
                            }
                        }

                        override fun onLoadResource(view: WebView?, pageUrl: String?) {
                            Log.d(TAG, "📦 onLoadResource: ${pageUrl?.take(200)}")
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            Log.e(TAG, "❌ onReceivedError: ${error?.description} per ${request?.url?.toString()?.take(200)}")
                        }

                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                            Log.w(TAG, "⚠️ onReceivedHttpError: ${errorResponse?.statusCode} per ${request?.url?.toString()?.take(200)}")
                        }
                    }

                    Log.d(TAG, "🚀 WebView.loadUrl($embedUrl)")
                    webView.loadUrl(embedUrl)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!found) {
                            Log.w(TAG, "⏰⏰⏰ TIMEOUT ($TIMEOUT_SECONDS secondi)! Richieste totali intercettate: $requestCount, pageLoad: $pageLoadCount")
                            Log.w(TAG, "⏰ Nessun link video rilevato, continuazione.resume(null)")
                            webView.stopLoading()
                            webView.destroy()
                            latch.countDown()
                            continuation.resume(null)
                        } else {
                            Log.d(TAG, "✅ Video trovato prima del timeout, tutto ok")
                        }
                    }, TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

                } catch (e: Exception) {
                    Log.e(TAG, "💥💥💥 Errore WebView: ${e.message}")
                    Log.e(TAG, "💥 Stacktrace:", e)
                    continuation.resume(null)
                }
            }

            Log.d(TAG, "⏳ In attesa del latch (${TIMEOUT_SECONDS + 5}s)...")
            latch.await(TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
            Log.d(TAG, "🔓 Latch rilasciato, continuazione completata")
        }
    }
}
