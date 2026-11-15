package it.dogior.hadEnough.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.coroutines.resume

object WebViewSniffer {
    private const val TAG = "WebViewSniffer"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    private fun appContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("getApplication").invoke(thread) as? Application
    } catch (e: Exception) {
        Log.e(TAG, "context: ${e.message}")
        null
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(
        url: String,
        referer: String,
        kickJs: String,
        accept: (String) -> Boolean,
        allowNavigation: (String) -> Boolean,
        timeoutMs: Long = 25_000L,
        graceMs: Long = 2_500L,
    ): List<String> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val ctx = appContext()
            if (ctx == null) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            val found = Collections.synchronizedList(mutableListOf<String>())
            val handler = Handler(Looper.getMainLooper())
            var done = false
            var graceStarted = false
            var kicksScheduled = false
            var webView: WebView? = null

            fun finish() {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {
                }
                if (cont.isActive) cont.resume(found.toList())
            }

            try {
                val wv = WebView(ctx)
                webView = wv
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    javaScriptCanOpenWindowsAutomatically = false
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = UA
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString().orEmpty()
                        val ok = allowNavigation(target)
                        Log.d(TAG, "navigazione ${if (ok) "consentita" else "bloccata"}: $target")
                        return !ok
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?, request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val u = request?.url?.toString() ?: return null
                        if (accept(u)) {
                            Log.d(TAG, "trovato: ${u.take(200)}")
                            found.add(u)
                            handler.post {
                                if (!graceStarted && !done) {
                                    graceStarted = true
                                    handler.postDelayed({ finish() }, graceMs)
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        if (kicksScheduled) return
                        kicksScheduled = true
                        listOf(1000L, 2500L, 4500L, 7000L, 10000L, 14000L).forEach { d ->
                            handler.postDelayed({
                                if (!done) view?.evaluateJavascript(kickJs, null)
                            }, d)
                        }
                    }
                }

                handler.postDelayed({
                    Log.w(TAG, "timeout, raccolti ${found.size}")
                    finish()
                }, timeoutMs)

                cont.invokeOnCancellation { handler.post { finish() } }
                wv.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) {
                Log.e(TAG, "WebView: ${e.message}")
                finish()
            }
        }
    }
}
