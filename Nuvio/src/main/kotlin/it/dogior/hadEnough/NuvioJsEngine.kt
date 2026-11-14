package it.dogior.hadEnough

import com.lagradost.api.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.typedarrays.NativeArrayBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "NuvioJs"

internal class NuvioHttpResult(
    val status: Int,
    val statusText: String,
    val url: String,
    val headers: Map<String, String>,
    val text: String,
    val bytes: ByteArray,
)

internal object NuvioHttp {
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun execute(
        base: OkHttpClient,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        followRedirects: Boolean,
    ): NuvioHttpResult {
        val client = base.newBuilder()
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        val builder = Request.Builder().url(url)
        var hasUa = false
        var contentType: String? = null
        for ((k, v) in headers) {
            if (k.equals("user-agent", true)) hasUa = true
            if (k.equals("content-type", true)) contentType = v
            if (k.equals("content-length", true) || k.equals("host", true)) continue
            try {
                builder.header(k, v)
            } catch (_: IllegalArgumentException) {
                // header value not allowed by OkHttp: skip it
            }
        }
        if (!hasUa) builder.header("User-Agent", DEFAULT_UA)

        val m = method.uppercase()
        val requestBody = if (m == "GET" || m == "HEAD") null
        else (body ?: "").toRequestBody(contentType?.toMediaTypeOrNull())
        builder.method(m, requestBody)

        client.newCall(builder.build()).execute().use { resp ->
            val bodyObj = resp.body
            val charset = bodyObj?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val bytes = bodyObj?.bytes() ?: ByteArray(0)
            val hdrs = LinkedHashMap<String, String>()
            for (name in resp.headers.names()) {
                hdrs[name.lowercase()] = resp.headers.values(name).joinToString(", ")
            }
            return NuvioHttpResult(
                status = resp.code,
                statusText = resp.message,
                url = resp.request.url.toString(),
                headers = hdrs,
                text = String(bytes, charset),
                bytes = bytes,
            )
        }
    }
}

private class NuvioNativeFn(
    private val fnName: String,
    private val body: (Context, Array<Any?>) -> Any?,
) : BaseFunction() {
    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? =
        body(cx, args)

    override fun getFunctionName(): String = fnName
}

private class NuvioTimeoutError : Error("timeout")


object NuvioJsEngine {
    class Result(val json: String?, val error: String?)

    private val transpiled = ConcurrentHashMap<Int, String>()

    private val httpPool = Executors.newCachedThreadPool { r ->
        Thread(r, "nuvio-http").apply { isDaemon = true }
    }

    
    fun run(
        scraperName: String,
        code: String,
        settingsJson: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        timeoutMs: Long,
        client: OkHttpClient,
    ): Result {
        val args = org.json.JSONArray().put(tmdbId).put(mediaType)
            .put(season ?: org.json.JSONObject.NULL).put(episode ?: org.json.JSONObject.NULL)
        return execute(scraperName, code, settingsJson, "getStreams", args.toString(), timeoutMs, client)
    }

    
    fun settingsBlueprint(scraperName: String, code: String, settingsJson: String, client: OkHttpClient): Result =
        execute(scraperName, code, settingsJson, "onSettings", "[]", 20_000L, client)

    
    fun execute(
        scraperName: String,
        code: String,
        settingsJson: String,
        fnName: String,
        argsJson: String,
        timeoutMs: Long,
        client: OkHttpClient,
    ): Result {
        var deadline = System.nanoTime() + timeoutMs * 1_000_000L

        val factory = object : ContextFactory() {
            override fun makeContext(): Context {
                val cx = super.makeContext()
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                cx.instructionObserverThreshold = 20_000
                return cx
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (System.nanoTime() > deadline) throw NuvioTimeoutError()
            }
        }

        val cx = factory.enterContext()
        try {
            
            val scope: ScriptableObject = cx.initSafeStandardObjects()

            val completions = LinkedBlockingQueue<() -> Unit>()
            val pending = AtomicInteger(0)
            val bodies = ConcurrentHashMap<Int, ByteArray>()

            fun define(name: String, body: (Context, Array<Any?>) -> Any?) {
                val fn = NuvioNativeFn(name, body)
                fn.parentScope = scope
                fn.prototype = ScriptableObject.getFunctionPrototype(scope)
                ScriptableObject.putProperty(scope, name, fn)
            }

            fun callJs(name: String, vararg args: Any?): Any? {
                val f = ScriptableObject.getProperty(scope, name) as Function
                return f.call(cx, scope, scope, arrayOf(*args))
            }

            define("__nativeLog") { _, a ->
                val msg = "[$scraperName] " + Context.toString(a.getOrNull(1))
                Log.d(TAG, msg)
                Undefined.instance
            }

            val dom = NuvioDom()
            define("__domCall") { _, a ->
                try {
                    dom.call(
                        Context.toString(a.getOrNull(0)),
                        Context.toString(a.getOrNull(1)),
                        a.getOrNull(2)?.takeIf { it !== Undefined.instance }?.let { Context.toString(it) },
                        a.getOrNull(3)?.takeIf { it !== Undefined.instance }?.let { Context.toString(it) },
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "[$scraperName] dom: ${e.message}")
                    null
                }
            }
            define("__cryptoHash") { _, a ->
                NuvioCrypto.hash(Context.toString(a.getOrNull(0)), Context.toString(a.getOrNull(1)))
            }
            define("__cryptoHmac") { _, a ->
                NuvioCrypto.hmac(Context.toString(a.getOrNull(0)), Context.toString(a.getOrNull(1)), Context.toString(a.getOrNull(2)))
            }
            define("__cryptoCipher") { _, a ->
                NuvioCrypto.cipher(
                    Context.toString(a.getOrNull(0)), Context.toString(a.getOrNull(1)), Context.toString(a.getOrNull(2)),
                    Context.toString(a.getOrNull(3)), Context.toString(a.getOrNull(4)), Context.toString(a.getOrNull(5)),
                    Context.toString(a.getOrNull(6)),
                )
            }
            define("__cryptoRandom") { _, a ->
                NuvioCrypto.random(Context.toNumber(a.getOrNull(0)).toInt())
            }

            define("__nativeBytes") { c, a ->
                val id = Context.toNumber(a.getOrNull(0)).toInt()
                val bytes = bodies[id] ?: ByteArray(0)
                try {
                    val buf = c.newObject(scope, "ArrayBuffer", arrayOf<Any>(bytes.size)) as NativeArrayBuffer
                    System.arraycopy(bytes, 0, buf.buffer, 0, bytes.size)
                    buf
                } catch (e: Exception) {
                    Undefined.instance
                }
            }

            define("__nativeFetch") { _, a ->
                val id = Context.toNumber(a.getOrNull(0)).toInt()
                val url = Context.toString(a.getOrNull(1))
                val method = Context.toString(a.getOrNull(2))
                val headers = parseHeaders(Context.toString(a.getOrNull(3)))
                val bodyArg = a.getOrNull(4)
                val body = if (bodyArg == null || bodyArg === Undefined.instance) null
                else Context.toString(bodyArg)
                val redirect = a.getOrNull(5)?.let { Context.toString(it) } ?: "follow"

                pending.incrementAndGet()
                httpPool.execute {
                    var res: NuvioHttpResult? = null
                    var err: String? = null
                    try {
                        res = NuvioHttp.execute(client, url, method, headers, body, redirect != "manual")
                    } catch (t: Throwable) {
                        err = t.message ?: t.javaClass.simpleName
                    }
                    val result = res
                    val failure = err
                    completions.put {
                        pending.decrementAndGet()
                        if (result != null) {
                            bodies[id] = result.bytes
                            callJs(
                                "__fetchDone", id, result.status, result.statusText, result.url,
                                JSONObject(result.headers as Map<*, *>).toString(), result.text, null
                            )
                        } else {
                            callJs("__fetchDone", id, 0, "", url, "{}", "", failure ?: "errore di rete")
                        }
                    }
                }
                Undefined.instance
            }

            cx.evaluateString(scope, NUVIO_PRELUDE, "nuvio-prelude", 1, null)

            val settings = try { JSONObject(settingsJson).toString() } catch (_: Exception) { "{}" }
            cx.evaluateString(scope, "globalThis.SCRAPER_SETTINGS = $settings;", "nuvio-settings", 1, null)

            
            val compiled = try {
                cx.compileString(code, scraperName, 1, null)
            } catch (e: RhinoException) {
                val rewritten = transpiled.getOrPut(code.hashCode()) {
                    Context.toString(callJs("__nvTranspile", code))
                }
                cx.compileString(rewritten, scraperName, 1, null)
            }
            compiled.exec(cx, scope)
            
            deadline = System.nanoTime() + timeoutMs * 1_000_000L
            callJs("__startCall", fnName, argsJson)

            
            var timedOut = false
            while (true) {
                callJs("__drain")
                if (callJs("__isDone") == true) break
                callJs("__runTimers")
                if (callJs("__isDone") == true) break

                var progressed = false
                while (true) {
                    val c = completions.poll() ?: break
                    c()
                    callJs("__drain")
                    progressed = true
                }
                if (callJs("__isDone") == true) break

                val nextDelay = Context.toNumber(callJs("__nextDelay")).toLong()
                if (pending.get() == 0 && nextDelay < 0 && !progressed) break // nothing can ever resolve it

                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left <= 0) {
                    timedOut = true
                    break
                }
                var wait = minOf(left, 200L)
                if (nextDelay in 0 until wait) wait = nextDelay
                if (!progressed && wait > 0) {
                    val c = completions.poll(wait, TimeUnit.MILLISECONDS)
                    if (c != null) {
                        c()
                        callJs("__drain")
                    }
                }
            }

            val done = callJs("__isDone") == true
            val json = callJs("__getJson")?.let { Context.toString(it) }
            val error = callJs("__getError")?.let { Context.toString(it) }
            return when {
                timedOut -> Result(null, "timeout")
                !done -> Result(null, "lo scraper non ha restituito un risultato")
                else -> Result(json, error)
            }
        } catch (_: NuvioTimeoutError) {
            return Result(null, "timeout")
        } catch (e: RhinoException) {
            return Result(null, "${e.details()} (riga ${e.lineNumber()})")
        } catch (t: Throwable) {
            return Result(null, t.message ?: t.javaClass.simpleName)
        } finally {
            Context.exit()
        }
    }

    private fun parseHeaders(json: String): Map<String, String> {
        return try {
            val o = JSONObject(json)
            val out = LinkedHashMap<String, String>()
            for (k in o.keys()) out[k] = o.optString(k)
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
