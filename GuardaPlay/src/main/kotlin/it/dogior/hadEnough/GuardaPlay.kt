package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import it.dogior.hadEnough.extractors.LoadMExtractor
import it.dogior.hadEnough.extractors.UqloadExtractor
import it.dogior.hadEnough.extractors.VixSrcExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.util.concurrent.atomic.AtomicBoolean

class GuardaPlay : MainAPI() {
    override var mainUrl = "https://guardaplay.autos"
    override var name = "GuardaPlay"
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
   //     "$mainUrl/" to "Ultimi aggiunti",
        "$mainUrl/movies/" to "Film",
        "$mainUrl/series/" to "Serie TV",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/avventura/" to "Avventura",
        "$mainUrl/category/commedia/" to "Commedia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/dramma/" to "Dramma",
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/fantascienza/" to "Fantascienza",
        "$mainUrl/category/fantasy/" to "Fantasy",
        "$mainUrl/category/thriller/" to "Thriller",
        "$mainUrl/category/documentario/" to "Documentario",
        "$mainUrl/category/famiglia/" to "Famiglia",
        "$mainUrl/category/reality/" to "Reality",
   //     "$mainUrl/category/kids/" to "Kids",
    )

    private val cfKiller by lazy { CloudflareKiller() }
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    private data class Embed(val name: String, val url: String)

    // ---------------------------------------------------------------- rete

    private suspend fun fetch(url: String, referer: String? = null) = app.get(
        url,
        referer = referer ?: mainUrl,
        headers = mapOf("User-Agent" to ua)
    ).let {
        if (it.code == 403 || it.code == 503) {
            app.get(
                url,
                referer = referer ?: mainUrl,
                headers = mapOf("User-Agent" to ua),
                interceptor = cfKiller
            )
        } else it
    }

    private suspend fun getDoc(url: String): Document {
        val r = fetch(url)
        return Jsoup.parse(r.text, url)
    }

    // ------------------------------------------------------------- helpers

    private fun absUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> mainUrl.trimEnd('/') + url
        else -> mainUrl.trimEnd('/') + "/" + url
    }

    private fun fixPoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val h = Regex("""(\d+)\s*h""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(\d+)\s*m""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (h * 60 + m).takeIf { it > 0 }
    }

    private fun toSearch(li: org.jsoup.nodes.Element): SearchResponse? {
        val href = li.selectFirst("a.lnk-blk")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        if ("/episodes/" in href) return null
        val title = li.selectFirst(".entry-title")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val poster = fixPoster(li.selectFirst(".post-thumbnail img")?.attr("src"))
        val year = li.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
        return if ("/series/" in href) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun parseList(doc: Document): List<SearchResponse> =
        doc.select("ul.post-lst > li").mapNotNull { toSearch(it) }

    // ---------------------------------------------------------- home/search

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val items = try {
            parseList(getDoc(url))
        } catch (e: Exception) {
            Log.e("GuardaPlay", "getMainPage($url): ${e.message}")
            emptyList()
        }
        return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val r = app.get(
            mainUrl,
            params = mapOf("s" to query),
            referer = mainUrl,
            headers = mapOf("User-Agent" to ua)
        )
        return parseList(Jsoup.parse(r.text, mainUrl))
    }

    // ----------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDoc(url)
        val art = doc.selectFirst("article.post.single") ?: return null
        val title = art.selectFirst(".entry-title")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        val poster = fixPoster(art.selectFirst(".post-thumbnail img")?.attr("src"))
        val backdrop = fixPoster(doc.selectFirst(".bghd img")?.attr("src"))
        val year = art.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
        val rating = art.selectFirst(".vote .num")?.text()?.trim()
        val genres = art.select(".genres a").eachText()
        val duration = parseDuration(art.selectFirst(".duration")?.text())
        val plot = art.select(".description p").eachText().joinToString("\n").ifBlank {
            art.selectFirst(".description")?.text()
                ?.replace(Regex("""^Guarda .*? Streaming Gratis:\s*"""), "")?.trim()
        }
        val actors = art.select(".cast-lst li")
            .firstOrNull { it.selectFirst("span")?.text()?.trim().equals("Cast", true) }
            ?.select("a")?.eachText().orEmpty()
        val trailer = doc.selectFirst("#mdl-trailer iframe")?.attr("src")?.takeIf { it.isNotBlank() }

        return if ("/series/" in url) {
            val episodes = loadEpisodes(doc, url, poster)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = genres
                addScore(rating)
                if (actors.isNotEmpty()) addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        } else {
            // data = URL della pagina: le fonti si leggono in loadLinks
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration
                addScore(rating)
                if (actors.isNotEmpty()) addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }
    }

    // ------------------------------------------------------------- episodi

    private fun parseEpisodes(lis: Elements, poster: String?, seasonHint: Int?): List<Episode> =
        lis.mapNotNull { li ->
            val href = li.selectFirst("a.lnk-blk")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val m = Regex("""(\d+)x(\d+)""").find(li.selectFirst(".num-epi")?.text().orEmpty())
                ?: Regex("""(\d+)x(\d+)/?$""").find(href)
            val season = m?.groupValues?.get(1)?.toIntOrNull() ?: seasonHint
            val ep = m?.groupValues?.get(2)?.toIntOrNull() ?: return@mapNotNull null
            newEpisode(href) {
                this.name = "Episodio $ep"
                this.season = season
                this.episode = ep
                this.posterUrl = poster
            }
        }

    private suspend fun loadEpisodes(doc: Document, seriesUrl: String, poster: String?): List<Episode> {
        val seasons = doc.select("li.sel-temp a[data-season]").mapNotNull { a ->
            a.attr("data-season").toIntOrNull()?.let { it to a.attr("data-post") }
        }.distinct()

        val inPage = parseEpisodes(doc.select("#episode_by_temp li"), poster, null)
        if (seasons.isEmpty()) return inPage

        val current = doc.selectFirst(".choose-season .n_s")?.text()?.trim()?.toIntOrNull()
            ?: inPage.firstOrNull()?.season ?: seasons.first().first

        val all = mutableListOf<Episode>()
        all += inPage
        seasons.filter { it.first != current }.forEach { (s, post) ->
            all += fetchSeason(post, s, seriesUrl, inPage.firstOrNull()?.data, poster)
        }
        return all.distinctBy { (it.season ?: 0) to (it.episode ?: 0) }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    private suspend fun fetchSeason(
        post: String,
        season: Int,
        seriesUrl: String,
        sampleEpisodeUrl: String?,
        poster: String?
    ): List<Episode> {
        val variants = listOf(
            mapOf("action" to "action_select_season", "post" to post, "season" to season.toString()),
            mapOf("action" to "action_select_season", "post_id" to post, "season" to season.toString()),
        )
        for (form in variants) {
            val html = try {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = form,
                    referer = seriesUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest", "User-Agent" to ua)
                ).text
            } catch (e: Exception) {
                Log.e("GuardaPlay", "ajax stagione $season: ${e.message}")
                null
            } ?: continue
            val eps = parseEpisodes(Jsoup.parseBodyFragment(html, mainUrl).select("li"), poster, season)
            if (eps.isNotEmpty()) {
                Log.d("GuardaPlay", "stagione $season via ajax: ${eps.size} episodi")
                return eps
            }
        }

        // Fallback: /episodes/<slug>-<S>x<E>/
        val prefix = sampleEpisodeUrl?.let { Regex("""^(.*-)\d+x\d+/?$""").find(it)?.groupValues?.get(1) }
            ?: return emptyList()
        val out = mutableListOf<Episode>()
        for (e in 1..60) {
            val u = "$prefix${season}x$e/"
            val ok = try {
                val r = fetch(u, seriesUrl)
                r.code == 200 && r.url.contains("/episodes/") && r.url.contains("${season}x$e")
            } catch (_: Exception) {
                false
            }
            if (!ok) break
            out += newEpisode(u) {
                this.name = "Episodio $e"
                this.season = season
                this.episode = e
                this.posterUrl = poster
            }
        }
        Log.d("GuardaPlay", "stagione $season via probe: ${out.size} episodi")
        return out
    }

    // ---------------------------------------------------------------- fonti

    private fun extractEmbeds(doc: Document): List<Embed> {
        val names = doc.select("ul.aa-tbs-video a.btn").associate { a ->
            a.attr("href").removePrefix("#") to
                (a.selectFirst(".server")?.text()?.replace(Regex("""\s+"""), " ")?.trim() ?: "")
        }
        return doc.select("#aa-options div.video").mapNotNull { div ->
            val f = div.selectFirst("iframe") ?: return@mapNotNull null
            val src = f.attr("data-src").ifBlank { f.attr("src") }.trim()
            if (src.isBlank()) return@mapNotNull null
            Embed(names[div.id()].orEmpty().ifBlank { "Server" }, absUrl(src))
        }
    }

    /** Apre la pagina ?trembed=N&trid=..., trova il vero player e lo passa all'estrattore giusto. */
    private suspend fun resolveEmbed(
        e: Embed,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val r = fetch(e.url, pageUrl)
        val html = r.text
        Log.d("GuardaPlay", "embed ${e.name} ${e.url} -> ${r.url} (${r.code}, ${html.length} bytes)")

        val finalHost = r.url.substringAfter("://").substringBefore("/")
        val mainHost = mainUrl.substringAfter("://").substringBefore("/")
        val target = if (finalHost != mainHost) {
            r.url // redirect diretto sul player
        } else {
            val doc = Jsoup.parse(html, e.url)
            doc.selectFirst("iframe[src]")?.attr("abs:src")?.takeIf { it.startsWith("http") }
                ?: Regex("""(?:src|location(?:\.href)?|href)\s*[=:]\s*["'](https?:[^"']+)""")
                    .find(html)?.groupValues?.get(1)
        }
        if (target == null) {
            Log.e("GuardaPlay", "nessun player trovato in ${e.url}: ${html.take(300)}")
            return
        }
        Log.d("GuardaPlay", "player: $target")

        val t = target.lowercase()
        val n = e.name.lowercase()
        when {
            "loadm" in t || "mload" in t || "abyss" in t || "mload" in n ->
                LoadMExtractor().getUrl(target, "$mainUrl/", subtitleCallback, callback)
            "uqload" in t || "uqload" in n -> {
                
                if (!UqloadExtractor().extract(target, "$mainUrl/", callback)) {
                    loadExtractor(target, e.url, subtitleCallback, callback)
                }
            }
            "vixsrc" in t -> VixSrcExtractor().getUrl(target, mainUrl, subtitleCallback, callback)
            else -> loadExtractor(target, e.url, subtitleCallback, callback)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var pageUrl = data
        val embeds: List<Embed> = when {
            
            data.trim().startsWith("[") -> {
                pageUrl = mainUrl
                try {
                    parseJson<List<String>>(data).map { Embed("Server", it) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            "trembed=" in data -> {
                pageUrl = mainUrl
                listOf(Embed("Server", data))
            }
            else -> extractEmbeds(getDoc(data))
        }
        if (embeds.isEmpty()) {
            Log.e("GuardaPlay", "nessuna fonte in $data")
            return false
        }

        val found = AtomicBoolean(false)
        val cb: (ExtractorLink) -> Unit = { link ->
            found.set(true)
            callback(link)
        }
        embeds.amap { e ->
            try {
                resolveEmbed(e, pageUrl, subtitleCallback, cb)
            } catch (ex: Exception) {
                Log.e("GuardaPlay", "embed ${e.name}: ${ex.message}")
            }
        }
        return found.get()
    }
}
