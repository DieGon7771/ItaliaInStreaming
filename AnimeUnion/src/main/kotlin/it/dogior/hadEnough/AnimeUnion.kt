package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeUnion(private val showLangTags: Boolean = false) : MainAPI() {
    override var mainUrl = "https://animeunion.tv"
    override var name = "AnimeUnion"
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "Ultimi episodi" to "Ultimi episodi rilasciati",
        "viste oggi" to "Più viste oggi",
        "Nuove serie" to "Nuove serie aggiunte",
    )

    private val tag = "AnimeUnion"
    private val cfKiller by lazy { CloudflareKiller() }
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    private data class Stream(
        val url: String,
        val quality: String,
        val language: String,
        val server: String,
    )

    // ---------------------------------------------------------------- rete

    private suspend fun fetch(url: String, referer: String? = null) = app.get(
        url, referer = referer, headers = mapOf("User-Agent" to ua)
    ).let { r ->
        if (r.code == 403 || r.code == 503) {
            app.get(url, referer = referer, headers = mapOf("User-Agent" to ua), interceptor = cfKiller)
        } else r
    }

    @Volatile
    private var homeCache: Pair<Long, Document>? = null

    private suspend fun homeDoc(): Document {
        val c = homeCache
        if (c != null && System.currentTimeMillis() - c.first < 60_000) return c.second
        val d = Jsoup.parse(fetch("$mainUrl/").text, mainUrl)
        homeCache = System.currentTimeMillis() to d
        return d
    }

    // ---------------------------------------------------------------- liste

    private fun typeOf(text: String?): TvType {
        val t = text?.lowercase().orEmpty()
        return when {
            "movie" in t || "film" in t -> TvType.AnimeMovie
            "ova" in t || "ona" in t || "special" in t -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun parseCards(root: Element): List<SearchResponse> {
        val seen = HashSet<String>()
        return root.select("a[href^=\"/anime/\"]").mapNotNull { a ->
            val href = a.attr("href")
            if (href.startsWith("/anime/genere/")) return@mapNotNull null
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val slug = href.removePrefix("/anime/").substringBefore("?").substringBefore("/")
            if (slug.isBlank() || !seen.add(slug)) return@mapNotNull null
            val ps = a.select("p")
            val title = ps.firstOrNull()?.text()?.takeIf { it.isNotBlank() }
                ?: img.attr("alt").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            newAnimeSearchResponse(title, "$mainUrl/anime/$slug", typeOf(ps.getOrNull(1)?.text())) {
                this.posterUrl = img.attr("src").takeIf { it.isNotBlank() }
                if (showLangTags) {
                    val titles = a.select("[title]").map { it.attr("title") }
                    val dub = titles.any { it.contains("doppiato", true) }
                    val sub = titles.any { it.contains("sottotitoli", true) }
                    if (dub || sub) addDubStatus(dubExist = dub, subExist = sub)
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = try {
            val doc = homeDoc()
            val section = doc.select("section").firstOrNull {
                it.selectFirst("h2")?.text()?.contains(request.data, true) == true
            }
            section?.let { parseCards(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.e(tag, "home: ${e.message}")
            emptyList()
        }
        return newHomePageResponse(HomePageList(request.name, list, false), false)
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    override suspend fun search(query: String): List<SearchResponse> {
        val words = norm(query).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        for (param in listOf("q", "search", "query")) {
            val results = try {
                parseCards(Jsoup.parse(fetch("$mainUrl/anime?$param=$q").text, mainUrl))
            } catch (e: Exception) {
                Log.w(tag, "search $param: ${e.message}")
                emptyList()
            }
            val filtered = results.filter { r ->
                val hay = norm(r.name + " " + r.url.substringAfterLast("/").replace("-", " "))
                words.all { it in hay }
            }
            if (filtered.isNotEmpty()) return filtered
        }
        return emptyList()
    }

    // ---------------------------------------------------------------- scheda

    private fun str(seg: String, key: String): String? =
        Regex("\\b$key:\"((?:[^\"\\\\]|\\\\.)*)\"").find(seg)?.groupValues?.get(1)

    private fun num(seg: String, key: String): Int? =
        Regex("\\b$key:(\\d+)").find(seg)?.groupValues?.get(1)?.toIntOrNull()

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.substringBefore("?")
        val html = fetch(pageUrl, referer = "$mainUrl/").text
        val doc = Jsoup.parse(html, mainUrl)
        val slug = pageUrl.substringAfter("/anime/").substringBefore("/")

        val seg = html.substringAfter("anime:{id:\"", "").substringBefore("genres:[")

        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: str(seg, "titleIta") ?: str(seg, "title") ?: slug
        val type = str(seg, "type")
        val tvType = when (type) {
            "TV" -> TvType.Anime
            "MOVIE" -> TvType.AnimeMovie
            else -> TvType.OVA
        }

        val langs = Regex("availableLanguages:\\[([^\\]]*)\\]").find(html)?.groupValues?.get(1).orEmpty()

        // episodi: link `/anime/<slug>/<n>` (non quelli delle card consigliate, che hanno class "group")
        val epLinks = doc.select("a[href^=\"/anime/$slug/\"]")
            .filter { !it.hasClass("group") && it.selectFirst("img") == null }
            .distinctBy { it.attr("href") }

        val episodes = epLinks.map { a ->
            val href = a.attr("href").substringBefore("?")
            val n = a.selectFirst("span.text-primary")?.text()?.trim()?.toIntOrNull()
                ?: Regex("(\\d+)").find(href.substringAfterLast("/"))?.groupValues?.get(1)?.toIntOrNull()
            newEpisode("$mainUrl$href") {
                this.episode = n
                this.name = a.selectFirst("div.flex-1 span")?.text()
                    ?.replace(Regex("\\s*\\d+\\s*$"), "")?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "Episodio"
            }
        }

        val related = parseCards(doc).filter { !it.url.endsWith("/anime/$slug") }

        return newAnimeLoadResponse(title, pageUrl, tvType) {
            this.posterUrl = str(seg, "coverImage")
            this.backgroundPosterUrl = str(seg, "bannerImage")
            this.year = num(seg, "seasonYear")
            this.plot = doc.selectFirst("p.whitespace-pre-line")?.text()
            val genres = doc.select("a[href^=\"/anime/genere/\"]").map { it.text() }
            this.tags = if (showLangTags) {
                listOfNotNull(
                    "Sottotitolato".takeIf { "SUB_ITA" in langs },
                    "Doppiato".takeIf { "DUB_ITA" in langs },
                ) + genres
            } else genres
            this.showStatus = when (str(seg, "status")) {
                "ONGOING" -> ShowStatus.Ongoing
                "COMPLETED" -> ShowStatus.Completed
                else -> null
            }
            this.recommendations = related

            doc.selectFirst("span.text-accent")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { addScore(it) }
            num(seg, "episodeDuration")?.let { addDuration("$it minuti") }
            num(seg, "anilistId")?.let { addAniListId(it) }
            num(seg, "malId")?.let { addMalId(it) }
            str(seg, "trailerUrl")?.takeIf { it.isNotBlank() }?.let { addTrailer(it) }

            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------- video

    /** Legge `streamLinks:[{...},{...}]` dai dati incorporati nella pagina dell'episodio. */
    private fun parseStreams(html: String): List<Stream> {
        val start = html.indexOf("streamLinks:[")
        if (start < 0) return emptyList()
        val body = html.substring(start + "streamLinks:[".length).substringBefore("]")
        return body.split("},{").mapNotNull { obj ->
            if (obj.contains("isActive:false")) return@mapNotNull null
            val u = str(obj, "url")?.replace("\\u002F", "/")?.replace("\\/", "/") ?: return@mapNotNull null
            Stream(
                url = u,
                quality = str(obj, "quality").orEmpty(),
                language = str(obj, "language").orEmpty(),
                server = str(obj, "server").orEmpty(),
            )
        }
    }

    private fun qualityValue(q: String): Int {
        Regex("(\\d{3,4})P", RegexOption.IGNORE_CASE).find(q)?.let { return it.groupValues[1].toInt() }
        val u = q.uppercase()
        return when {
            "4K" in u -> 2160
            "2K" in u -> 1440
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(q: String): String =
        q.removePrefix("Q_").replace("_PLUS", "+").replace("_", " ")
            .lowercase().replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() } ?: ""

    private fun langLabel(l: String) = when (l) {
        "SUB_ITA" -> "Sub ITA"
        "DUB_ITA" -> "Dub ITA"
        else -> l.replace("_", " ")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = data.substringBefore("?")
        val html = fetch(pageUrl, referer = "$mainUrl/").text

        var streams = parseStreams(html)
        if (streams.isEmpty()) {
            // tag <video src="..."> della pagina
            Jsoup.parse(html, mainUrl).selectFirst("video[src]")?.attr("src")?.takeIf { it.startsWith("http") }
                ?.let { streams = listOf(Stream(it, "", "", "")) }
        }
        Log.d(tag, "stream trovati: ${streams.size}")

        val chosen = streams.sortedBy { if (it.language == "SUB_ITA") 0 else 1 }
        val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to ua)

        var any = false
        for (s in chosen.distinctBy { it.url }) {
            val label = listOf(name, langLabel(s.language), qualityLabel(s.quality))
                .filter { it.isNotBlank() }.joinToString(" ")
            val path = s.url.lowercase().substringBefore("?")
            if (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mkv")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = s.url,
                        type = if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityValue(s.quality)
                        this.headers = headers
                    }
                )
                any = true
            } else {
                if (loadExtractor(s.url, "$mainUrl/", subtitleCallback, callback)) any = true
            }
        }
        return any
    }
}
