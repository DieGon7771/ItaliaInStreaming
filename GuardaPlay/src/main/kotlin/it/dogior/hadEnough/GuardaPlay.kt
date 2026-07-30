package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.extractors.LoadMExtractor
import org.jsoup.Jsoup

class GuardaPlay : MainAPI() {
    override var mainUrl = "https://guardaplay.store"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie, TvType.Cartoon, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Film: Ultimi",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/avventura/" to "Avventura",
        "$mainUrl/category/commedia/" to "Commedia",
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/fantascienza/" to "Fantascienza",
        "$mainUrl/category/fantasy/" to "Fantasy",
        "$mainUrl/category/thriller/" to "Thriller",
        "$mainUrl/category/documentario/" to "Documentario",
        "$mainUrl/category/famiglia/" to "Famiglia",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(request.data).document
        val items = doc.select("li.post-lst > li, ul.post-lst > li")
        val homes = items.mapNotNull { li ->
            val art = li.selectFirst("article") ?: return@mapNotNull null
            val title = art.select(".entry-title").text().trim()
            val url = li.select("a.lnk-blk").attr("href")
            val poster = art.select(".post-thumbnail img").attr("src")
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            newTvSeriesSearchResponse(title, url) {
                this.posterUrl = fixPoster(poster)
            }
        }
        return newHomePageResponse(HomePageList(request.name, homes), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        val items = doc.select("li.post-lst > li, ul.post-lst > li")
        return items.mapNotNull { li ->
            val art = li.selectFirst("article") ?: return@mapNotNull null
            val title = art.select(".entry-title").text().trim()
            val url = li.select("a.lnk-blk").attr("href")
            val poster = art.select(".post-thumbnail img").attr("src")
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            newTvSeriesSearchResponse(title, url) {
                this.posterUrl = fixPoster(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("GuardaPlay", "📥 load() chiamata con URL: $url")
        val doc = app.get(url).document
        val title = doc.select("article.post.single .entry-title, h1.entry-title").text().trim()
        Log.d("GuardaPlay", "📌 Titolo estratto: \"$title\"")
        val poster = doc.select(".post-thumbnail img").attr("src")
        Log.d("GuardaPlay", "🖼️ Poster URL: $poster")
        val year = doc.select(".year").text().trim()
        val rating = doc.select(".vote .num").text().trim()
        val plot = doc.select(".description p").text().trim()
        val genres = doc.select(".genres a").eachText()
        val durationText = doc.select(".duration").text().trim()

        Log.d("GuardaPlay", "🔍 Cerco iframe in #options-0...")
        val embedIframe = doc.selectFirst("#options-0 iframe")
        val embedUrl = embedIframe?.attr("src")?.replace("&#038;", "&") ?: run {
            Log.e("GuardaPlay", "❌ Nessun iframe trovato in #options-0! Selettore #options-0 iframe = ${doc.select("#options-0 iframe").size}")
            Log.d("GuardaPlay", "📄 HTML snippet #options-0: ${doc.select("#options-0").outerHtml().take(500)}")
            ""
        }
        Log.d("GuardaPlay", "🔗 Embed URL dopo replace: $embedUrl")
        val streamUrl = if (embedUrl.isNotEmpty()) listOf(embedUrl) else emptyList()

        val durationMinutes = parseDuration(durationText)

        return newMovieLoadResponse(title, url, TvType.Movie, streamUrl) {
            this.posterUrl = fixPoster(poster)
            this.year = year.toIntOrNull()
            this.plot = plot
            this.tags = genres
            addScore(rating)
            this.duration = durationMinutes
        }
    }

    private fun fixPoster(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        return url
    }

    private fun parseDuration(text: String): Int? {
        val regex = Regex("""(?:(\d+)h\s*)?(?:(\d+)m)?""")
        val match = regex.find(text.trim()) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        return hours * 60 + minutes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("GuardaPlay", "🧪 loadLinks() chiamata con data: $data")

        val embedUrl = try {
            com.lagradost.cloudstream3.utils.AppUtils.parseJson<List<String>>(data).firstOrNull()
        } catch (_: Exception) {
            data
        } ?: return false

        Log.d("GuardaPlay", "🔗 Embed URL estratto: $embedUrl")

        try {
            val cfClient = app.baseClient.newBuilder()
                .addInterceptor(CloudflareKiller())
                .build()

            val request = okhttp3.Request.Builder()
                .url(embedUrl)
                .header("Referer", mainUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            Log.d("GuardaPlay", "🌐 Fetch embed con CloudflareKiller...")
            val response = cfClient.newCall(request).execute()
            val html = response.body?.string() ?: ""
            val finalUrl = response.request.url.toString()
            response.close()

            Log.d("GuardaPlay", "🔗 Final URL: $finalUrl")
            Log.d("GuardaPlay", "📄 HTML ricevuto, lunghezza: ${html.length}")

            val doc = Jsoup.parse(html)
            val loadmUrl = doc.selectFirst("iframe")?.attr("src") ?: run {
                Log.e("GuardaPlay", "❌ iframe non trovato! HTML: ${html.take(500)}")
                return false
            }
            Log.d("GuardaPlay", "✅ Iframe loadm trovato: $loadmUrl")

            LoadMExtractor().getUrl(loadmUrl, embedUrl, subtitleCallback, callback)
            Log.d("GuardaPlay", "✅ LoadMExtractor completato")
        } catch (e: Exception) {
            Log.e("GuardaPlay", "❌ Errore loadLinks: ${e.message}")
            return false
        }
        return true
    }
}
