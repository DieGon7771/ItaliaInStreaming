package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import it.dogior.hadEnough.extractors.DroploadExtractor
import it.dogior.hadEnough.extractors.MySupervideoExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AltaDefinizione : MainAPI() {
    override var mainUrl = "https://altadefinizionez.lat"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/ultimi-film/" to "Ultimi Film",
        "$mainUrl/cinema/" to "Al Cinema",
        "$mainUrl/piu-visti/" to "Più Visti",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/avventura/" to "Avventura",
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/biografico/" to "Biografico",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/crime/" to "Crime",
        "$mainUrl/documentario/" to "Documentario",
        "$mainUrl/drammatico/" to "Drammatico",
        "$mainUrl/fantascienza/" to "Fantascienza",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/guerra/" to "Guerra",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/western/" to "Western"
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
        
        // NUOVO: Estrai la DURATA dal meta-list e converti in Int
        val durationString = doc.selectFirst(".meta.movie_entry-info .meta-list")?.let { metaList ->
            metaList.select("span").find { span -> 
                span.text().contains("min") 
            }?.text()?.trim()
        }
        
        // Converti "105 min" in 105
        val duration = durationString?.let {
            it.substringBefore(" min").trim().toIntOrNull()
        }
        
        // NUOVO: Estrai l'ANNO
        val year = details.find { it.text().contains("Anno:", ignoreCase = true) }
            ?.text()?.substringAfter("Anno:")?.trim()?.toIntOrNull()
        
        val genres = details.find { it.text().contains("Genere:", ignoreCase = true) }
            ?.select("a")?.map { it.text() } ?: emptyList()
        
        val actors = details.find { it.text().contains("Cast:", ignoreCase = true) }
            ?.select("a")?.map { ActorData(Actor(it.text())) } ?: emptyList()
        
        // MODIFICA: Rileva meglio se è una serie TV
        val isSeries = url.contains("/serie-tv/") || 
                      doc.select(".series-select, .dropdown.seasons, .dropdown.episodes, .dropdown.mirrors").isNotEmpty()
        
        return if (isSeries) {
            // Carica serie TV
            val episodes = getEpisodes(doc)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year  // <-- ANNO AGGIUNTO
                this.actors = actors
                addScore(rating)
            }
        } else {
            // Carica film
            val mirrors = extractMovieMirrors(doc)
            newMovieLoadResponse(title, url, TvType.Movie, mirrors) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.tags = genres
                this.year = year  // <-- ANNO AGGIUNTO
                this.duration = duration  // <-- DURATA AGGIUNTA (ora è Int?)
                this.actors = actors
                addScore(rating)
            }
        }
    }

    private suspend fun extractMovieMirrors(doc: Document): List<String> {
        val mirrors = mutableListOf<String>()
        
        val iframeSrc = doc.select("#player1 iframe, .player iframe, iframe[src*='mostraguarda']").attr("src")
        if (iframeSrc.isNotBlank()) {
            mirrors.add(fixUrl(iframeSrc))
        }
        
        doc.select("a[data-link], button[data-link], .mirror-link, .player-option").forEach {
            val link = it.attr("data-link").ifBlank { it.attr("href") }
            if (link.isNotBlank() && !link.contains("javascript:")) {
                mirrors.add(fixUrl(link))
            }
        }
        
        if (mirrors.isEmpty() || mirrors.all { it.contains("mostraguarda") }) {
            val mostraGuardaLink = if (mirrors.isNotEmpty()) mirrors.first() else iframeSrc
            if (mostraGuardaLink.contains("mostraguarda")) {
                try {
                    val mostraGuarda = app.get(mostraGuardaLink).document
                    val playerMirrors = mostraGuarda.select("ul._player-mirrors > li").mapNotNull {
                        val link = it.attr("data-link")
                        if (link.contains("mostraguarda")) null else fixUrl(link)
                    }
                    return playerMirrors
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return mirrors.distinct()
    }

    // FUNZIONE PER ESTRARRE EPISODI
    private fun getEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Ottieni l'immagine principale della serie
        val seriesPoster = doc.selectFirst("img.layer-image.lazy, img[data-src]")?.attr("data-src") ?: 
                       doc.selectFirst("img.layer-image.lazy, img[data-src]")?.attr("src")
        
        // Trova tutti i dropdown per le stagioni
        val seasonItems = doc.select("div.dropdown.seasons .dropdown-menu span[data-season]")
        
        if (seasonItems.isNotEmpty()) {
            // Per ogni stagione
            seasonItems.forEach { seasonItem ->
                val seasonNum = seasonItem.attr("data-season").toIntOrNull() ?: 1
                
                // Trova il dropdown episodes corrispondente a questa stagione
                val episodeContainer = doc.selectFirst("div.dropdown.episodes[data-season=\"$seasonNum\"]")
                
                if (episodeContainer != null) {
                    // Trova tutti gli episodi per questa stagione
                    val episodeItems = episodeContainer.select("span[data-episode]")
                    
                    episodeItems.forEach { episodeItem ->
                        val episodeData = episodeItem.attr("data-episode")
                        val parts = episodeData.split("-")
                        val episodeNum = parts.getOrNull(1)?.toIntOrNull()
                        
                        // Nome dell'episodio
                        val episodeName = episodeItem.text().trim()
                        
                        // Trova i mirror per questo episodio
                        val mirrorContainer = doc.selectFirst("div.dropdown.mirrors[data-season=\"$seasonNum\"][data-episode=\"$episodeData\"]")
                        
                        if (mirrorContainer != null) {
                            val mirrorItems = mirrorContainer.select("span[data-link]")
                            val mirrors = mirrorItems.mapNotNull { 
                                val link = it.attr("data-link")
                                if (link.isNotBlank()) link else null
                            }.distinct()
                            
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
                    }
                }
            }
        } else {
            // Struttura alternativa
            val allMirrorContainers = doc.select("div.dropdown.mirrors[data-season][data-episode]")
            
            allMirrorContainers.forEach { container ->
                val seasonNum = container.attr("data-season").toIntOrNull() ?: 1
                val episodeData = container.attr("data-episode")
                val parts = episodeData.split("-")
                val episodeNum = parts.getOrNull(1)?.toIntOrNull()
                
                // Trova il nome dell'episodio
                val episodeItem = doc.selectFirst("div.dropdown.episodes[data-season=\"$seasonNum\"] span[data-episode=\"$episodeData\"]")
                val episodeName = episodeItem?.text()?.trim() ?: "Episodio $episodeNum"
                
                val mirrorItems = container.select("span[data-link]")
                val mirrors = mirrorItems.mapNotNull { 
                    val link = it.attr("data-link")
                    if (link.isNotBlank()) link else null
                }.distinct()
                
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
        }
        
        // Se non trova episodi con il metodo sopra, prova con una struttura più semplice
        if (episodes.isEmpty()) {
            val simpleMirrors = doc.select("div.dropdown.mirrors")
            simpleMirrors.forEachIndexed { index, container ->
                val mirrorItems = container.select("span[data-link]")
                val mirrors = mirrorItems.mapNotNull { 
                    val link = it.attr("data-link")
                    if (link.isNotBlank()) link else null
                }.distinct()
                
                if (mirrors.isNotEmpty()) {
                    episodes.add(
                        newEpisode(mirrors) {
                            this.season = 1
                            this.episode = index + 1
                            this.name = "Episodio ${index + 1}"
                            this.description = "Stagione 1, Episodio ${index + 1}"
                            this.posterUrl = fixUrlNull(seriesPoster)
                        }
                    )
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
        
        links.forEach { link ->
            when {
                link.contains("dropload.tv") -> {
                    DroploadExtractor().getUrl(link, null, subtitleCallback, callback)
                }
                link.contains("supervideo.tv") || link.contains("mysupervideo") -> {
                    MySupervideoExtractor().getUrl(link, null, subtitleCallback, callback)
                }
            }
        }
        
        return true
    }
}
