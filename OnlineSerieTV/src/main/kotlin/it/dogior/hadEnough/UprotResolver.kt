package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object UprotResolver {
    private const val WORKER_URL = "https://upcaptcha-vision.diegon7771.workers.dev"
    private const val WORKER_KEY = "df07388fea754e54b0e19a1ab7dc994ef0af7cc2627845ce"
    private const val UP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
    private const val UP_COOKIE2 = "ccd14f7d7ee32ff386eefcfda920a476"
    private const val TAG = "Uprot"

    data class UpCell(val idx: Int, val src: String)
    data class UpCaptcha(val field: String, val target: String, val cells: List<UpCell>)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .build()
    }

    private class Jar {
        val map = linkedMapOf("cookie2" to UP_COOKIE2, "uprot_session_pxx" to "1")
        fun header(): String = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        fun merge(setCookies: List<String>) {
            setCookies.forEach { raw ->
                val first = raw.substringBefore(';')
                val name = first.substringBefore('=', "").trim()
                val value = first.substringAfter('=', "").trim()
                if (name.isNotEmpty()) map[name] = value
            }
        }
    }

    private val fieldRe = Regex("""name=["'](upcapt_[A-Za-z0-9]+)["']""")
    private val cellStartRe = Regex(
        """<[^>]+class=["'][^"']*upcaptcha-cell[^"']*["'][^>]*data-idx=""",
        RegexOption.IGNORE_CASE
    )
    private val strongRe = Regex("""<strong>([^<]{1,40})</strong>""")
    private val cellRe = Regex(
        """<[^>]+class=["'][^"']*upcaptcha-cell[^"']*["'][^>]*data-idx=["'](\d+)["'][^>]*>[\s\S]*?<img[^>]+src=["'](data:image/(?:jpeg|png);base64,[A-Za-z0-9+/=]+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val commentRe = Regex("""<!--[\s\S]*?-->""")
    private val hiddenDivRe = Regex(
        """<div[^>]*style=["'][^"']*display\s*:\s*none[^"']*["'][^>]*>[\s\S]*?</div>""",
        RegexOption.IGNORE_CASE
    )
    private val buttokRe = Regex(
        """href=["'](https?://[^"'/]+/uprot[a-z]{0,4}/[^/"']+/[^/"']+/?)[\"'][^>]*>\s*<button""",
        RegexOption.IGNORE_CASE
    )
    private val msPlayerRe = Regex(
        """^https?://(?:www\.)?maxstream\.video/(?!uprot)[A-Za-z0-9]+/[A-Za-z0-9]+/?$""",
        RegexOption.IGNORE_CASE
    )
    private val rawHexRe = Regex("""rawHex\s*=\s*["']([a-fA-F0-9]{20,8192})["']""")
    private val vidRe1 = Regex("""/freeswatcee/([A-Za-z0-9]+)""")
    private val vidRe2 = Regex("""/videostreax/[A-Za-z0-9]+/([A-Za-z0-9]+)""")
    private val uprotKeyRe = Regex(
        """^https?://uprot\.net/(ms(?:fi?|ei)/[A-Za-z0-9+/=_.-]{4,300}?)/?$""",
        RegexOption.IGNORE_CASE
    )
    private val evalRe = Regex(
        """eval\(function\(p,a,c,k,e,d\)[\s\S]*?\}\([\s\S]*?\.split\(['"]\|['"]\)[\s\S]*?\)\s*\)"""
    )
    private val numEntityRe = Regex("""&#(\d+);""")
    private val hexEntityRe = Regex("""&#x([0-9a-fA-F]+);""")

    fun uprotKey(link: String): String? =
        uprotKeyRe.find(link.trim())?.groupValues?.get(1)

    private fun decodeEntities(input: String): String {
        var text = numEntityRe.replace(input) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        text = hexEntityRe.replace(text) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        return text
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }

    fun parseUpCaptcha(html: String): UpCaptcha? {
        val field = fieldRe.find(html)?.groupValues?.get(1) ?: return null
        val first = cellStartRe.find(html)?.range?.first ?: return null
        val before = html.substring(maxOf(0, first - 4000), first)
        val target = strongRe.findAll(before).lastOrNull()
            ?.groupValues?.get(1)
            ?.let { decodeEntities(it).trim() }
            ?: return null
        val cells = cellRe.findAll(html).mapNotNull { m ->
            val idx = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            UpCell(idx, m.groupValues[2])
        }.toList()
        if (cells.size < 2) return null
        return UpCaptcha(field, target, cells)
    }

    private suspend fun upPost(link: String, body: String, jar: Jar): Pair<String, String?> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(link)
                .header("User-Agent", UP_UA)
                .header("Origin", "https://uprot.net")
                .header("Referer", "https://uprot.net/")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", jar.header())
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                jar.merge(response.headers.values("Set-Cookie"))
                val html = response.body?.string().orEmpty()
                Pair(html, response.header("Location"))
            }
        }

    private suspend fun solveUpCaptcha(cap: UpCaptcha): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("target", cap.target)
            put("images", JSONArray(cap.cells.map { it.src }))
        }
        val request = Request.Builder()
            .url("$WORKER_URL/solve")
            .header("x-worker-auth", WORKER_KEY)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "worker ${response.code}: ${text.take(200)}")
                return@use null
            }
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return@use null
            val array = obj.optJSONArray("indices") ?: return@use null
            val picked = (0 until array.length())
                .mapNotNull { i -> array.optInt(i, -1).takeIf { it in cap.cells.indices } }
                .distinct()
                .sorted()
            if (picked.isEmpty()) return@use null
            picked.joinToString(",") { cap.cells[it].idx.toString() }
        }
    }

    private fun findButtok(html: String): String? {
        val clean = hiddenDivRe.replace(commentRe.replace(html, ""), "")
        return buttokRe.find(clean)?.groupValues?.get(1)
    }

    private suspend fun attempt(
        link: String,
        html: String,
        jar: Jar,
        left: Int,
        lastLocation: String?
    ): String? {
        findButtok(html)?.let { return it }
        val location = lastLocation
        if (!location.isNullOrBlank() && location.contains("/uprot")) {
            val request = Request.Builder()
                .url(absolute(location, link))
                .header("User-Agent", UP_UA)
                .header("Referer", link)
                .header("Cookie", jar.header())
                .get()
                .build()
            val extra = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    jar.merge(response.headers.values("Set-Cookie"))
                    response.body?.string().orEmpty()
                }
            }
            findButtok(extra)?.let { return it }
        }
        val cap = parseUpCaptcha(html) ?: return null
        if (!jar.map.containsKey("PHPSESSID") || left <= 0) return null
        val answer = solveUpCaptcha(cap) ?: return null
        val form = "${URLEncoder.encode(cap.field, "UTF-8")}=${URLEncoder.encode(answer, "UTF-8")}"
        val (nextHtml, nextLocation) = upPost(link, form, jar)
        return attempt(link, nextHtml, jar, left - 1, nextLocation)
    }

    private suspend fun unlockUprot(link: String): String? {
        val jar = Jar()
        val (html, location) = upPost(link, "", jar)
        return attempt(link, html, jar, 3, location)
    }

    private fun absolute(location: String, base: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) return location
        if (location.startsWith("//")) return "https:$location"
        val origin = Regex("""^https?://[^/]+""").find(base)?.value ?: ""
        return origin + (if (location.startsWith("/")) "" else "/") + location
    }

    private fun vidFrom(url: String?): String? {
        if (url == null) return null
        return vidRe1.find(url)?.groupValues?.get(1)
            ?: vidRe2.find(url)?.groupValues?.get(1)
    }

    private fun playerFromHex(body: String): String? {
        val hex = rawHexRe.find(body)?.groupValues?.get(1) ?: return null
        val decoded = hex.chunked(2)
            .mapNotNull { it.toIntOrNull(16)?.toChar() }
            .joinToString("")
            .reversed()
        return decoded.takeIf { Regex("""^https?://[^\s"'<>]+$""").matches(it) }
    }

    private suspend fun maxstreamPlayerUrl(uprotsUrl: String): String? = withContext(Dispatchers.IO) {
        var vid: String? = null
        fun guess(): String? = vid?.let { "https://maxstream.video/emilpy0n/$it" }

        fun hop(url: String, referer: String, left: Int): String? {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UP_UA)
                .header("Referer", referer)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val location = response.header("Location")
                val body = response.body?.string().orEmpty()
                if (vid == null) {
                    vid = vidFrom(url) ?: vidFrom(response.request.url.toString()) ?: vidFrom(location)
                }
                playerFromHex(body)?.let { return it }
                val next = location?.let { absolute(it, url) } ?: ""
                if (vid == null && msPlayerRe.matches(next)) return next
                if (vid == null && msPlayerRe.matches(response.request.url.toString())) {
                    return response.request.url.toString()
                }
                if (next.isNotEmpty() && left > 0) return hop(next, url, left - 1)
                return guess()
            }
        }

        runCatching { hop(uprotsUrl, "https://maxstream.video/", 1) }.getOrNull() ?: guess()
    }

    private suspend fun maxstreamM3u8(playerUrl: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(playerUrl)
            .header("User-Agent", UP_UA)
            .header("Referer", "https://maxstream.video/")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            var text = response.body?.string().orEmpty()
            if (text.isEmpty()) return@use null
            evalRe.findAll(text).forEach { match ->
                val unpacked = runCatching { getAndUnpack(match.value) }.getOrNull()
                if (!unpacked.isNullOrBlank()) text += "\n$unpacked"
            }
            Regex("""src\s*:\s*["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)
                ?: Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""")
                    .find(text)?.groupValues?.get(1)
        }
    }

    private suspend fun cachedPlayer(key: String?): String? = withContext(Dispatchers.IO) {
        if (key.isNullOrBlank()) return@withContext null
        val request = Request.Builder()
            .url("$WORKER_URL/player?k=${URLEncoder.encode(key, "UTF-8")}")
            .header("x-worker-auth", WORKER_KEY)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val player = runCatching { JSONObject(text).optString("player", "") }.getOrNull().orEmpty()
            player.takeIf { msPlayerRe.matches(it) }
        }
    }

    private suspend fun storePlayer(key: String?, player: String): Unit = withContext(Dispatchers.IO) {
        if (key.isNullOrBlank()) return@withContext
        val match = Regex(
            """^https?://(?:www\.)?maxstream\.video/([A-Za-z0-9]+)/([A-Za-z0-9]+)/?$""",
            RegexOption.IGNORE_CASE
        ).find(player) ?: return@withContext
        val canonical = "https://maxstream.video/${match.groupValues[1]}/${match.groupValues[2]}"
        val payload = JSONObject().apply {
            put("k", key)
            put("player", canonical)
        }
        val request = Request.Builder()
            .url("$WORKER_URL/player")
            .header("x-worker-auth", WORKER_KEY)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { it.body?.close() } }
        Unit
    }

    suspend fun resolve(link: String): String? {
        val key = uprotKey(link)
        cachedPlayer(key)?.let { player ->
            maxstreamM3u8(player)?.let { return it }
        }

        val uprots = unlockUprot(link)
        if (uprots == null) {
            Log.e(TAG, "unlock failed")
            return null
        }
        val player = maxstreamPlayerUrl(uprots)
        if (player == null) {
            Log.e(TAG, "player not found")
            return null
        }
        val m3u8 = maxstreamM3u8(player)
        if (m3u8 == null) {
            Log.e(TAG, "m3u8 not found")
            return null
        }
        storePlayer(key, player)
        return m3u8
    }
}
