package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import it.dogior.hadEnough.extractors.MaxStreamExtractor
import it.dogior.hadEnough.extractors.StreamTapeExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OnlineSerieTV : MainAPI() {
    override var mainUrl = "https://onlineserietv.online"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries,
        TvType.Cartoon, TvType.Anime, TvType.AnimeMovie, TvType.Documentary
    )
    override var lang = "it"
    override val hasMainPage = true

    // Headers per bypassare Cloudflare
    private val cloudflareHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Cache-Control" to "max-age=0"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/" to "Serie TV: Ultime aggiunte",
        "$mainUrl/serie-tv-generi/animazione/" to "Serie TV: Animazione",
        "$mainUrl/film-generi/animazione/" to "Film: Animazione",
        "$mainUrl/serie-tv-generi/documentario/" to "Serie TV: Documentario",
        "$mainUrl/film-generi/documentario/" to "Film: Documentario",
        "$mainUrl/serie-tv-generi/action-adventure/" to "Serie TV: Azione e Avventura",
        "$mainUrl/film-generi/avventura/" to "Film: Avventura",
        "$mainUrl/film-generi/azione/" to "Film: Azione",
        "$mainUrl/film-generi/supereroi/" to "Film: Supereroi",
        "$mainUrl/serie-tv-generi/sci-fi-fantasy/" to "Serie TV: Fantascienza e Fantasy",
        "$mainUrl/film-generi/fantascienza/" to "Film: Fantascienza",
        "$mainUrl/film-generi/fantasy/" to "Film: Fantasy",
        "$mainUrl/serie-tv-generi/dramma/" to "Serie TV: Dramma",
        "$mainUrl/film-generi/drammatico/" to "Film: Dramma",
        "$mainUrl/film-generi/sentimentale/" to "Film: Sentimentale",
        "$mainUrl/serie-tv-generi/commedia/" to "Serie TV: Commedia",
        "$mainUrl/film-generi/commedia/" to "Film: Commedia",
        "$mainUrl/serie-tv-generi/crime/" to "Serie TV: Crime",
        "$mainUrl/serie-tv-generi/mistero/" to "Serie TV: Mistero",
        "$mainUrl/serie-tv-generi/war-politics/" to "Serie TV: Guerra e Politica",
        "$mainUrl/film-generi/horror/" to "Film: Horror",
        "$mainUrl/film-generi/thriller/" to "Film: Thriller",
        "$mainUrl/serie-tv-generi/reality/" to "Serie TV: Reality",
    )

    // Funzione helper per fare richieste con Cloudflare bypass
    private suspend fun safeGet(url: String): Document? {
        return try {
            // Prova con headers Cloudflare
            val response = app.get(url, headers = cloudflareHeaders, timeout = 30000)
            
            // Se la risposta contiene "Cloudflare" o "challenge", ritenta
            val text = response.text
            if (text.contains("cloudflare") || text.contains("challenge") || 
                text.contains("security check") || response.code == 403) {
                Log.d("OnlineSerieTV", "Cloudflare rilevato, ritento...")
                // Ritenta con più delay (semplice sleep)
                kotlinx.coroutines.delay(2000)
                app.get(url, headers = cloudflareHeaders, timeout = 30000).document
            } else {
                response.document
            }
        } catch (e: Exception) {
            Log.e("OnlineSerieTV", "Errore nel fetch di $url: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val response = safeGet(request.data) ?: return null
        val searchResponses = getItems(request.name, response)
        return newHomePageResponse(HomePageList(request.name, searchResponses), false)
    }

    private suspend fun getItems(section: String, page: Document): List<SearchResponse> {
        return try {
            when (section) {
                "Film: Ultimi aggiunti", "Serie TV: Ultime aggiunte" -> {
                    val itemGrid = page.selectFirst(".wp-block-uagb-post-grid") ?: return emptyList()
                    val items = itemGrid.select(".uagb-post__inner-wrap")
                    items.mapNotNull {
                        val itemTag = it.select(".uagb-post__title > a").firstOrNull()
                        val title = itemTag?.text()?.trim()?.replace(Regex("""\d{4}$"""), "") ?: return@mapNotNull null
                        val url = itemTag.attr("href")
                        val poster = it.select(".uagb-post__image > a > img").attr("src")
                        
                        newTvSeriesSearchResponse(title, url) {
                            this.posterUrl = poster.ifEmpty { null }
                        }
                    }
                }
                
                else -> {
                    val itemGrid = page.selectFirst("#box_movies") ?: return emptyList()
                    val items = itemGrid.select(".movie")
                    items.mapNotNull {
                        val title = it.select("h2").text().trim().replace(Regex("""\d{4}$"""), "")
                        val url = it.select("a").attr("href")
                        val poster = it.select("img").attr("src")
                        
                        if (title.isNotEmpty() && url.isNotEmpty()) {
                            newTvSeriesSearchResponse(title, url) {
                                this.posterUrl = poster.ifEmpty { null }
                            }
                        } else {
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OnlineSerieTV", "Errore in getItems: ${e.message}")
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val page = safeGet(url) ?: return emptyList()
        
        val itemGrid = page.selectFirst("#box_movies") ?: return emptyList()
        val items = itemGrid.select(".movie")
        
        return items.mapNotNull {
            val title = it.select("h2").text().trim().replace(Regex("""\d{4}$"""), "")
            val url = it.select("a").attr("href")
            val poster = it.select("img").attr("src")
            
            if (title.isNotEmpty() && url.isNotEmpty()) {
                newTvSeriesSearchResponse(title, url) {
                    this.posterUrl = poster.ifEmpty { null }
                }
            } else {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = safeGet(url) ?: throw Exception("Impossibile caricare la pagina")
        
        val dati = response.selectFirst(".headingder") ?: throw Exception("Struttura pagina cambiata")
        val poster = dati.select(".imgs > img").attr("src").replace(Regex("""-\d+x\d+"""), "")
        val title = dati.select(".dataplus > div:nth-child(1) > h1").text().trim()
            .replace(Regex("""\d{4}$"""), "")
        val rating = dati.select(".stars > span:nth-child(3)").text().trim().removeSuffix("/10")
        val genres = dati.select(".stars > span:nth-child(6) > i:nth-child(1)").text().trim()
        val year = dati.select(".stars > span:nth-child(8) > i:nth-child(1)").text().trim()
        val duration = dati.select(".stars > span:nth-child(10) > i:nth-child(1)").text()
            .removeSuffix(" minuti")
        val isMovie = url.contains("/film/")

        return if (isMovie) {
            val streamUrl = response.select("#hostlinks").select("a").map { it.attr("href") }
            val plot = response.select(".post > p:nth-child(16)").text().trim()
            
            newMovieLoadResponse(title, url, TvType.Movie, streamUrl) {
                addPoster(poster)
                addScore(rating)
                this.duration = duration.toIntOrNull()
                this.year = year.toIntOrNull()
                this.tags = genres.split(",").map { it.trim() }
                this.plot = plot
            }
        } else {
            val episodes = getEpisodes(response)
            val plot = response.select(".post > p:nth-child(17)").text().trim()
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                addPoster(poster)
                addScore(rating)
                this.year = year.toIntOrNull()
                this.tags = genres.split(",").map { it.trim() }
                this.plot = plot
            }
        }
    }

    private fun getEpisodes(page: Document): List<Episode> {
        val table = page.selectFirst("#hostlinks > table:nth-child(1)") ?: return emptyList()
        var season: Int? = 1
        val rows = table.select("tr")
        
        return rows.mapNotNull {
            when {
                it.childrenSize() == 0 -> null
                it.childrenSize() == 1 -> {
                    val seasonText = it.select("td:nth-child(1)").text()
                    season = Regex("""\d+""").find(seasonText)?.value?.toInt()
                    null
                }
                else -> {
                    val title = it.select("td:nth-child(1)").text()
                    val links = it.select("a").map { a -> "\"${a.attr("href")}\"" }
                    newEpisode(links.toString()) {
                        this.name = title
                        this.season = season
                        this.episode = title.substringAfter("x").substringBefore(" ").toIntOrNull()
                    }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("OnlineSerieTV:Links", "Data: $data")
        val links = parseJson<List<String>>(data) ?: return false
        
        links.forEach { link ->
            if (link.contains("uprot")) {
                val url = bypassUprot(link)
                Log.d("OnlineSerieTV:Links", "Bypassed Url: $url")
                url?.let {
                    when {
                        it.contains("streamtape") -> {
                            StreamTapeExtractor().getUrl(it, null, subtitleCallback, callback)
                        }
                        it.contains("maxstream") || it.contains("msf") || it.contains("mse") -> {
                            MaxStreamExtractor().getUrl(it, null, subtitleCallback, callback)
                        }
                        else -> {
                            loadExtractor(it, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
        return links.isNotEmpty()
    }

    private suspend fun bypassUprot(link: String): String? {
        val updatedLink = if ("msf" in link) link.replace("msf", "mse") else link
        
        return try {
            val response = app.get(updatedLink, headers = cloudflareHeaders, timeout = 15000)
            val document = response.document
            document.selectFirst("a")?.attr("href")
        } catch (e: Exception) {
            Log.e("OnlineSerieTV", "Errore bypassUprot: ${e.message}")
            null
        }
    }
}
