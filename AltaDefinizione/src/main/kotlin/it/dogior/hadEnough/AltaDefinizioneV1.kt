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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/cinema/" to "Cinema",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/miniserie-tv/" to "Miniserie TV", 
        "$mainUrl/tv-show/" to "Programmi TV",
        "$mainUrl/sitcom/" to "Serie Comiche",
        "$mainUrl/soap-opera/" to "Telenovelas",
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
        "$mainUrl/giallo/" to "Giallo",
        "$mainUrl/poliziesco/" to "Poliziesco",
        "$mainUrl/spionaggio/" to "Spionaggio",
        "$mainUrl/guerra/" to "Guerra",
        "$mainUrl/western/" to "Western",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/sentimentale/" to "Sentimentale",
        "$mainUrl/famiglia/" to "Famiglia",
        "$mainUrl/musical/" to "Musical",
        "$mainUrl/musicale/" to "Musicale",
        "$mainUrl/storico-streaming/" to "Storico",
        "$mainUrl/biografico/" to "Biografico",
        "$mainUrl/fantastico/" to "Fantastico",
        "$mainUrl/documentario/" to "Documentario",
        "$mainUrl/reality/" to "Reality Show",
        "$mainUrl/talk-show/" to "Talk Show",
        "$mainUrl/talent-show/" to "Talent Show",
        "$mainUrl/intrattenimento/" to "Intrattenimento",
        "$mainUrl/sportivo/" to "Sportivo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        
        val items = doc.select("#dle-content > .col").mapNotNull {
            it.toSearchResponse()
        }
        
        val pagination = doc.select("div.pagin > a").last()?.text()?.toIntOrNull()
        val hasNext = page < (pagination ?: 0) || doc.select("a[rel=next]").isNotEmpty()
        
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
        
        val fullTitle = if (isSeries) {
            val episode = this.selectFirst(".label.episode")?.text()
            if (episode != null) "$title ($episode)" else title
        } else {
            title
        }
        
        return newMovieSearchResponse(fullTitle, href) {
            this.posterUrl = fixUrlNull(poster)
            this.score = Score.from(rating, 10)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(searchUrl).document
        
        return doc.select("#dle-content > .col").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val content = doc.selectFirst("#dle-content") 
            ?: doc.selectFirst("main")
            ?: doc.selectFirst(".container")
            ?: return null
        
        val title = doc.selectFirst("h1, .movie_entry-title, .movie-title")?.text() 
            ?: "Sconosciuto"
        
        val posterImg = content.selectFirst("img.layer-image.lazy, img[data-src]")
        val poster = posterImg?.attr("data-src") ?: posterImg?.attr("src")
        
        val plot = doc.selectFirst(".movie_entry-plot, #sfull, .plot, .description, .synopsis")?.text()
        
        val rating = content.selectFirst(".label.rate, .rateIMDB, .imdb-rate, .rating")?.text()
            ?.substringAfter("IMDb: ")?.substringBefore(" ") ?: ""
        
        val detailsContainer = content.selectFirst(".movie_entry-details, .details, .info, #details")
        val details = detailsContainer?.select("li") ?: emptyList()
        
        val durationString = doc.selectFirst(".meta.movie_entry-info .meta-list")?.let { metaList ->
            metaList.select("span").find { span -> 
                span.text().contains("min") 
            }?.text()?.trim()
        }
        
        val duration = durationString?.let {
            it.substringBefore(" min").trim().toIntOrNull()
        }
        
        val year = details.find { it.text().contains("Anno:", ignoreCase = true) }
            ?.text()?.substringAfter("Anno:")?.trim()?.toIntOrNull()
        
        val genres = details.find { it.text().contains("Genere:", ignoreCase = true) }
            ?.select("a")?.map { it.text() } ?: emptyList()
        
        val actors = details.find { it.text().contains("Cast:", ignoreCase = true) }
            ?.select("a")?.map { ActorData(Actor(it.text())) } ?: emptyList()
        
        val isSeries = url.contains("/serie-tv/") || 
                      doc.select(".series-select, .dropdown.seasons, .dropdown.episodes, .dropdown.mirrors").isNotEmpty()
        
        return if (isSeries) {
            val episodes = getEpisodes(doc)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                this.actors = actors
                addScore(rating)
            }
        } else {
            val mirrors = extractMovieMirrors(doc)
            newMovieLoadResponse(title, url, TvType.Movie, mirrors) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year
                this.duration = duration
                this.actors = actors
                addScore(rating)
            }
        }
    }

    private suspend fun extractMovieMirrors(doc: Document): List<String> {
        val mirrors = mutableListOf<String>()
        
        // CERCA NEI DATA-LINK (questo è il metodo CORRETTO)
        doc.select("span[data-link], button[data-link]").forEach {
            val link = it.attr("data-link")
            if (link.isNotBlank() && !link.contains("javascript:")) {
                mirrors.add(fixUrl(link))
            }
        }
        
        // Se non trova nei dropdown, cerca nei down-episode (per film)
        if (mirrors.isEmpty()) {
            doc.select(".down-episode a[href]").forEach {
                val link = it.attr("href")
                if (link.isNotBlank() && !link.contains("javascript:")) {
                    mirrors.add(fixUrl(link))
                }
            }
        }
        
        return mirrors.distinct()
    }

    private fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seriesPoster = doc.selectFirst("img.layer-image.lazy, img[data-src]")?.attr("data-src") ?: 
                       doc.selectFirst("img.layer-image.lazy, img[data-src]")?.attr("src")
        
        // Cerca tutte le stagioni
        val seasonItems = doc.select("div.dropdown.seasons .dropdown-menu span[data-season]")
        
        // Se non trova stagioni, crea una stagione 1 fittizia
        val seasons = if (seasonItems.isNotEmpty()) {
            seasonItems.map { it.attr("data-season").toIntOrNull() ?: 1 }.distinct().sorted()
        } else {
            listOf(1)
        }
        
        seasons.forEach { seasonNum ->
            // Trova il container degli episodi per questa stagione
            val episodeContainer = doc.selectFirst("div.dropdown.episodes[data-season=\"$seasonNum\"]")
            
            if (episodeContainer != null) {
                val episodeItems = episodeContainer.select("span[data-episode]")
                
                episodeItems.forEach { episodeItem ->
                    val episodeData = episodeItem.attr("data-episode")
                    val parts = episodeData.split("-")
                    val episodeNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    val episodeName = episodeItem.text().trim()
                    
                    // Cerca i mirror per questo episodio
                    val mirrorContainer = doc.selectFirst("div.dropdown.mirrors[data-season=\"$seasonNum\"][data-episode=\"$episodeData\"]")
                    
                    val mirrors = if (mirrorContainer != null) {
                        // Prendi i link dai data-link
                        mirrorContainer.select("span[data-link]").mapNotNull { 
                            val link = it.attr("data-link")
                            if (link.isNotBlank()) link else null
                        }.distinct()
                    } else {
                        // Cerca nel modal download
                        val episodePattern = "${seasonNum}x${episodeNum}"
                        doc.select(".down-episode").find { container ->
                            container.text().contains(episodePattern)
                        }?.select("a[href]")?.mapNotNull { 
                            val link = it.attr("href")
                            if (link.isNotBlank()) link else null
                        } ?: emptyList()
                    }
                    
                    if (mirrors.isNotEmpty()) {
                        episodes.add(
                            newEpisode(mirrors) {
                                this.season = seasonNum
                                this.episode = episodeNum
                                this.name = episodeName
                                this.description = "Stagione $seasonNum • $episodeName"
                                this.posterUrl = fixUrlNull(seriesPoster)
                            }
                        )
                    }
                }
            } else {
                // Se non trova episodi organizzati, cerca tutti i mirror
                val allMirrors = doc.select("div.dropdown.mirrors[data-season=\"$seasonNum\"] span[data-link]")
                    .mapNotNull { it.attr("data-link").takeIf { link -> link.isNotBlank() } }
                    .distinct()
                
                if (allMirrors.isNotEmpty()) {
                    // Crea episodi fittizi
                    allMirrors.forEachIndexed { index, link ->
                        episodes.add(
                            newEpisode(listOf(link)) {
                                this.season = seasonNum
                                this.episode = index + 1
                                this.name = "Episodio ${index + 1}"
                                this.description = "Stagione $seasonNum, Episodio ${index + 1}"
                                this.posterUrl = fixUrlNull(seriesPoster)
                            }
                        )
                    }
                }
            }
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseJson<List<String>>(data)
        
        if (links.isEmpty()) {
            return false
        }
        
        var found = false
        
        links.forEach { link ->
            when {
                // ATTENZIONE: I player sono scambiati!
                // "supervideo" punta a dropload.pro
                // "dropload" punta a supervideo.cc
                link.contains("dropload.pro") -> {
                    DroploadExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    found = true
                }
                link.contains("supervideo.cc") -> {
                    MySupervideoExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    found = true
                }
                else -> {
                    try {
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                        found = true
                    } catch (e: Exception) {
                        // Ignora
                    }
                }
            }
        }
        
        return found
    }
}
