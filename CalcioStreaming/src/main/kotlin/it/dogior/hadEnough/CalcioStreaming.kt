package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import kotlin.io.encoding.Base64

class CalcioStreaming : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://angolo.direttecommunity.online"
    override var name = "CalcioStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    val cfKiller = CloudflareKiller()

    companion object {
        private const val TAG = "CalcioStreaming"
        private const val EVENTS_PATH = "/api/events.php"
        private const val CACHE_DURATION_MS = 60_000L

        private var cachedEvents: List<CalcioEvent> = emptyList()
        private var cachedAt = 0L

        private val ZICO_SOURCES_REGEX = Regex("""ZT_SOURCES\s*=\s*(\[[\s\S]*?])\s*;""")
    }

    /** A directly playable link plus the referer its CDN expects. */
    data class Link(
        val name: String,
        val url: String,
        val ref: String
    )

    /* ─── Catalogue ─────────────────────────────────────────────────────────── */

    private suspend fun getEvents(forceRefresh: Boolean = false): List<CalcioEvent> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedEvents.isNotEmpty() && now - cachedAt < CACHE_DURATION_MS) {
            return cachedEvents
        }

        val body = app.get("$mainUrl$EVENTS_PATH", referer = "$mainUrl/").text
        val events = parseJson<EventsResponse>(body).events
        if (events.isNotEmpty()) {
            cachedEvents = events
            cachedAt = now
        }
        return events
    }

    private fun eventUrl(id: String) = "$mainUrl/?event=$id"

    private fun eventIdFromUrl(url: String) = url.substringAfter("?event=", "").substringBefore("&")

    private suspend fun findEvent(url: String): CalcioEvent? {
        val id = eventIdFromUrl(url)
        if (id.isBlank()) return null
        return getEvents().firstOrNull { it.id == id }
            ?: getEvents(forceRefresh = true).firstOrNull { it.id == id }
    }

    private fun isToday(ts: Long?): Boolean {
        if (ts == null) return false
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }

    /** "2026-08-23T20:30:00+02:00" -> "20:30" */
    private fun CalcioEvent.clockTime() =
        startTime?.substringAfter("T", "")?.take(5)?.takeIf { it.length == 5 }

    /** Titolo pulito: "Home VS Away" */
    private fun CalcioEvent.displayTitle() =
        listOfNotNull(homeTeam, awayTeam).joinToString(" VS ").ifBlank { id }

    /** Mappa lo sport al poster orizzontale su GitHub (raw). */
    private fun CalcioEvent.sportPosterUrl(): String = when (sport?.lowercase(Locale.ROOT)) {
        "soccer", "calcio", "football" -> "https://raw.githubusercontent.com/Foxu89/ItaliaInStreaming/master/CalcioStreaming/src/main/res/drawable-nodpi/sport_calcio.png"
        "basketball", "basket" -> "https://raw.githubusercontent.com/Foxu89/ItaliaInStreaming/master/CalcioStreaming/src/main/res/drawable-nodpi/sport_basket.png"
        "tennis" -> "https://raw.githubusercontent.com/Foxu89/ItaliaInStreaming/master/CalcioStreaming/src/main/res/drawable-nodpi/sport_tennis.png"
        else -> "https://raw.githubusercontent.com/Foxu89/ItaliaInStreaming/master/CalcioStreaming/src/main/res/drawable-nodpi/sport_default.png"
    }

    private suspend fun CalcioEvent.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(displayTitle(), eventUrl(id), TvType.Live) {
            this.posterUrl = sportPosterUrl()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = getEvents()
        if (events.isEmpty()) throw ErrorLoadingException("Nessun evento disponibile")

        val sections = listOf(
            "🔴 In Diretta" to events.filter { it.status == "live" },
            "Oggi" to events.filter { it.status == "scheduled" && isToday(it.startTs) },
            "Domani" to events.filter { it.status == "scheduled" && !isToday(it.startTs) }
        ).mapNotNull { (sectionName, sectionEvents) ->
            if (sectionEvents.isEmpty()) return@mapNotNull null
            HomePageList(
                sectionName,
                sectionEvents.sortedBy { it.startTs ?: 0L }.map { it.toSearchResponse() },
                isHorizontalImages = true
            )
        }

        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()

        return getEvents().filter { event ->
            listOfNotNull(event.title, event.league, event.homeTeam, event.awayTeam, event.sport)
                .any { it.lowercase(Locale.ROOT).contains(q) }
        }.sortedBy { it.startTs ?: 0L }.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val event = findEvent(url) ?: throw ErrorLoadingException("Evento non disponibile")

        val timeStr = event.clockTime() ?: "??:??"
        val liveMarker = if (event.status == "live") " 🔴" else ""
        val description = "Inizio $timeStr$liveMarker"

        return newLiveStreamLoadResponse(
            name = event.displayTitle(),
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = event.sportPosterUrl()
            this.plot = description
            event.league?.takeIf { it.isNotBlank() }?.let {
                this.tags = listOf(it)
            }
        }
    }

    /* ─── Universal Embed Resolver ─────────────────────────────────────────────── */

    /**
     * Risolve un URL embed generico provando multiple strategie in ordine.
     * Restituisce Pair<m3u8Url, referer> o null.
     */
    private suspend fun resolveEmbedUrl(url: String, referer: String): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null

        var currentUrl = url
        var currentReferer = referer
        val visited = mutableSetOf<String>()

        for (hop in 0 until 6) {
            if (currentUrl in visited) return null
            visited.add(currentUrl)

            val html = try {
                app.get(currentUrl, referer = currentReferer, interceptor = cfKiller).text
            } catch (e: Exception) {
                Log.w(TAG, "Fetch failed for $currentUrl: ${e.message}")
                return null
            }

            // ─── Strategia 1: Direct m3u8 in HTML ───
            val directM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
            if (directM3u8 != null) {
                Log.d(TAG, "Found direct m3u8 at hop $hop: $currentUrl")
                return directM3u8 to currentUrl
            }

            // ─── Strategia 2: Clappr atob (bestembeds.buzz) ───
            val atobMatch = Regex("""source:\s*atob\(["']([^"']+)["']\)""").find(html)
            if (atobMatch != null) {
                try {
                    val encoded = atobMatch.groupValues[1]
                    val decoded = Base64.decode(encoded).toString(StandardCharsets.UTF_8)
                    if (decoded.contains(".m3u8")) {
                        Log.d(TAG, "Found Clappr atob m3u8 at hop $hop")
                        return decoded to currentUrl
                    }
                } catch (_: Exception) {}
            }

            // ─── Strategia 3: Clappr direct source ───
            val clapprDirect = Regex("""source:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
            if (clapprDirect != null && clapprDirect.contains(".m3u8")) {
                Log.d(TAG, "Found Clappr direct m3u8 at hop $hop")
                return clapprDirect to currentUrl
            }

            // ─── Strategia 4: ZT_SOURCES (zicotv classic) ───
            val ztMatch = ZICO_SOURCES_REGEX.find(html)
            if (ztMatch != null) {
                try {
                    val raw = ztMatch.groupValues[1]
                    val sources = parseJson<List<ZicoSource>>(raw)
                    val m3u8 = sources.firstOrNull { it.url.contains(".m3u8") }?.url
                    if (m3u8 != null) {
                        val origin = currentUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: currentUrl
                        Log.d(TAG, "Found ZT_SOURCES m3u8 at hop $hop")
                        return m3u8 to origin
                    }
                } catch (_: Exception) {}
            }

            // ─── Strategia 5: window._econfig (sportsonline) ───
            val econfigMatch = Regex("""window\._econfig\s*=\s*['"]([^'"]+)['"]""").find(html)
            if (econfigMatch != null) {
                val decoded = try {
                    val encoded = econfigMatch.groupValues[1]
                    val padded = encoded + "=".repeat((-encoded.length % 4 + 4) % 4)
                    val decodedConfig = Base64.decode(padded).toString(StandardCharsets.ISO_8859_1)
                    val partOrder = listOf(2, 0, 3, 1)
                    val partLen = (decodedConfig.length + 3) / 4
                    val parts = mutableListOf<String>()
                    var offset = 0
                    repeat(4) {
                        val part = decodedConfig.substring(offset, minOf(offset + partLen, decodedConfig.length))
                        offset += partLen
                        parts.add(part.take(3) + part.drop(4))
                    }
                    val decodedParts = Array(4) { "" }
                    parts.forEachIndexed { idx, part ->
                        val padded = part + "=".repeat((-part.length % 4 + 4) % 4)
                        decodedParts[listOf(2, 0, 3, 1)[idx]] = Base64.decode(padded).toString(StandardCharsets.ISO_8859_1)
                    }
                    val joined = decodedParts.joinToString("")
                    val json = Base64.decode(joined + "=".repeat((-joined.length % 4 + 4) % 4)).toString(StandardCharsets.UTF_8)
                    JSONObject(json).optString("stream_url_nop2p").takeIf { it.isNotBlank() }
                        ?: JSONObject(json).optString("stream_url").takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }
                if (decoded != null && decoded.isNotBlank()) {
                    Log.d(TAG, "Found _econfig m3u8 at hop $hop")
                    return decoded to currentUrl
                }
            }

            // ─── Strategia 6: JSON in script tags ───
            val soup = org.jsoup.Jsoup.parse(html)
            for (script in soup.select("script")) {
                script.data()?.let { data ->
                    if (data.trim().startsWith("{")) {
                        try {
                            val json = JSONObject(data)
                            fun findM3u8(obj: Any?): String? = when (obj) {
                                is JSONObject -> obj.keys().asSequence().mapNotNull { findM3u8(obj.get(it)) }.firstOrNull()
                                is JSONArray -> (0 until obj.length()).asSequence().mapNotNull { findM3u8(obj.get(it)) }.firstOrNull()
                                is String -> if (obj.contains(".m3u8")) obj else null
                                else -> null
                            }
                            findM3u8(json)?.let { m3u8 ->
                                Log.d(TAG, "Found JSON m3u8 at hop $hop")
                                return@resolveEmbedUrl m3u8 to currentUrl
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            // ─── Strategia 7: video/source tags ───
            soup.select("video, source").forEach { tag ->
                tag.attr("src")?.takeIf { it.contains(".m3u8") }?.let { m3u8 ->
                    Log.d(TAG, "Found video/source tag m3u8 at hop $hop")
                    return@resolveEmbedUrl m3u8 to currentUrl
                }
            }

            // ─── Segue iframe chain ───
            val iframe = soup.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
            if (iframe != null) {
                val next = absolutize(currentUrl, iframe)
                if (next !in visited) {
                    currentUrl = next
                    currentReferer = currentUrl
                    continue
                }
            }

            break
        }

        Log.w(TAG, "No m3u8 found for $url after iframe chain")
        return null
    }

    /** Risolve un singolo stream provando l'universal resolver. */
    private suspend fun resolveStream(stream: CalcioStream): List<Link> {
        val name = stream.displayName()
        return try {
            val ref = stream.url.substringBefore("channels")
                .takeIf { it.isNotBlank() } ?: stream.url
            val resolved = resolveEmbedUrl(stream.url, ref)
            if (resolved != null) {
                listOf(Link(name, resolved.first, resolved.second))
            } else {
                Log.w(TAG, "Failed to resolve: ${stream.url}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve ${stream.url}: ${e.message}")
            emptyList()
        }
    }

    private fun CalcioStream.displayName(): String {
        val base = label?.takeIf { it.isNotBlank() }
            ?: source?.takeIf { it.isNotBlank() }
            ?: "Stream"
        val language = lang?.takeIf { it.isNotBlank() && !it.equals("multi", true) }
        return if (language == null) base else "$base [${language.uppercase(Locale.ROOT)}]"
    }

    /** Risolve un relativo iframe src contro la pagina che lo contiene. */
    private fun absolutize(base: String, link: String): String = when {
        link.startsWith("http", ignoreCase = true) -> link
        link.startsWith("//") -> "https:$link"
        else -> base.toHttpUrlOrNull()?.resolve(link)?.toString() ?: link
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val event = findEvent(data) ?: return false

        val links = event.streams.amap { resolveStream(it) }.flatten()
        links.forEach { link ->
            Log.d(TAG, link.toString())
            callback(
                newExtractorLink(
                    source = this.name,
                    name = link.name,
                    url = link.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = link.ref
                }
            )
        }
        return links.isNotEmpty()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = cfKiller.intercept(chain)
                return response
            }
        }
    }
}