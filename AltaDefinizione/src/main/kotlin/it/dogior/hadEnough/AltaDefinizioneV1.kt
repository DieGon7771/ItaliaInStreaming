package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import it.dogior.hadEnough.extractors.DroploadExtractor
import it.dogior.hadEnough.extractors.MySupervideoExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AltaDefinizioneV1 : MainAPI() {
    override var mainUrl = "https://altadefinizionez.skin"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    private val timeout = 60

    override val mainPage = mainPageOf(
        "$mainUrl/cinema/" to "Al Cinema",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/avventura/" to "Avventura",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/drammatico/" to "Drammatico",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/fantascienza/" to "Fantascienza",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/crime/" to "Crime",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/famiglia/" to "Famiglia",
        "$mainUrl/western/" to "Western",
        "$mainUrl/documentario/" to "Documentario"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, timeout = timeout).document
        
        val items = doc.select("#dle-content > .col").mapNotNull {
            it.toSearchResponse()
        }
        
        val hasNext = doc.select("a[rel=next]").isNotEmpty()
        
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        val titleElement = this.selectFirst(".movie-title a") ?: return null
        val title = titleElement.text().trim()
        if (title.isBlank()) return null
        
        val href = fixUrl(titleElement.attr("href"))
        if (href.isBlank()) return null
        
        val imgElement = this.selectFirst("img.layer-image.lazy")
        val poster = imgElement?.attr("data-src")
        
        val ratingElement = this.selectFirst(".label.rate.small")
        val rating = ratingElement?.text()
        
        val isSeries = this.selectFirst(".label.episode") != null
        
        return newMovieSearchResponse(title, href) {
            this.posterUrl = fixUrlNull(poster)
            this.score = Score.from(rating, 10)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(searchUrl, timeout = timeout).document
        return doc.select("#dle-content > .col").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = timeout).document
        
        val content = doc.selectFirst("#dle-content, main, .container") ?: return null
        
        val title = doc.selectFirst("h1, .movie_entry-title, .movie-title")?.text() ?: "Sconosciuto"
        
        val posterImg = content.selectFirst("img.layer-image.lazy, img[data-src]")
        val poster = posterImg?.attr("data-src") ?: posterImg?.attr("src")
        
        val plot = doc.selectFirst(".movie_entry-plot, #sfull, .plot, .description, .synopsis")?.text()
        
        val detailsContainer = content.selectFirst(".movie_entry-details, .details, .info, #details")
        val details = detailsContainer?.select("li") ?: emptyList()
        
        val duration = doc.selectFirst(".meta.movie_entry-info .meta-list span:contains(min)")?.text()
            ?.substringBefore(" min")?.trim()?.toIntOrNull()
        
        val year = details.find { it.text().contains("Anno:", true) }
            ?.text()?.substringAfter("Anno:")?.trim()?.toIntOrNull()
        
        val genres = details.find { it.text().contains("Genere:", true) }
            ?.select("a")?.map { it.text() } ?: emptyList()
        
        val isSeries = url.contains("/serie-tv/") || 
                      doc.select(".dropdown.seasons, .dropdown.episodes").isNotEmpty()
        
        return if (isSeries) {
            val episodes = getEpisodes(doc)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                addScore("")
            }
        } else {
            val mirrors = extractMovieMirrors(doc)
            newMovieLoadResponse(title, url, TvType.Movie, mirrors) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                this.duration = duration
                addScore("")
            }
        }
    }

    private suspend fun extractMovieMirrors(doc: Document): List<String> {
        val mirrors = mutableListOf<String>()
        
        doc.select("iframe[src*='mostraguarda']").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) mirrors.add(fixUrl(src))
        }
        
        if (mirrors.isEmpty()) {
            doc.select("a.buttona_stream[href]").forEach {
                val href = it.attr("href")
                if (href.isNotBlank()) mirrors.add(fixUrl(href))
            }
        }
        
        return mirrors.distinct()
    }

    private fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seriesPoster = doc.selectFirst("img.layer-image.lazy, img[data-src]")?.attr("data-src")
        
        doc.select("div.dropdown.mirrors span[data-link]").forEach { mirror ->
            val link = mirror.attr("data-link")
            if (link.isNotBlank()) {
                episodes.add(
                    newEpisode(link) {
                        this.name = "Episodio"
                        this.posterUrl = fixUrlNull(seriesPoster)
                    }
                )
            }
        }
        
        return episodes.distinctBy { it.data }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseJson<List<String>>(data)
        if (links.isEmpty()) return false
        
        links.forEach { link ->
            when {
                link.contains("mostraguarda") -> {
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
                link.contains("dropload.pro") -> {
                    DroploadExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                }
                link.contains("supervideo.cc") -> {
                    MySupervideoExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                }
                else -> {
                    try {
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                    } catch (_: Exception) { }
                }
            }
        }
        
        return true
    }
}
