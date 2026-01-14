package it.dogior.hadEnough

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import it.dogior.hadEnough.extractors.MaxStreamExtractor
import it.dogior.hadEnough.extractors.StreamTapeExtractor

class OnlineSerieTV : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val tmdbApiKey = BuildConfig.TMDB_API
    private val streamingBaseUrl = "https://onlineserietv.online"

    override val mainPage = mainPageOf(
        "$mainUrl/trending/movie/day?api_key=$tmdbApiKey&language=it&region=IT" to "Film in Tendenza",
        "$mainUrl/trending/tv/day?api_key=$tmdbApiKey&language=it&region=IT" to "Serie TV in Tendenza",
        "$mainUrl/movie/popular?api_key=$tmdbApiKey&language=it&region=IT" to "Film Popolari",
        "$mainUrl/tv/popular?api_key=$tmdbApiKey&language=it&region=IT" to "Serie TV Popolari",
        "$mainUrl/movie/top_rated?api_key=$tmdbApiKey&language=it&region=IT" to "Film Top",
        "$mainUrl/tv/top_rated?api_key=$tmdbApiKey&language=it&region=IT" to "Serie TV Top"
    )

    // 🔍 Cerca su TMDB
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/multi?api_key=$tmdbApiKey&language=it&query=${query.urlEncoded()}"
        val response = app.get(url).parsedSafe<TMDBMultiSearch>()
        
        return response?.results?.mapNotNull { item ->
            when (item.media_type) {
                "movie" -> newMovieSearchResponse(
                    item.title ?: return@mapNotNull null,
                    TMDBData(id = item.id, type = "movie").toJson()
                ) {
                    posterUrl = if (item.poster_path != null) 
                        "https://image.tmdb.org/t/p/w500${item.poster_path}" 
                    else null
                }
                "tv" -> newTvSeriesSearchResponse(
                    item.name ?: return@mapNotNull null,
                    TMDBData(id = item.id, type = "tv").toJson()
                ) {
                    posterUrl = if (item.poster_path != null)
                        "https://image.tmdb.org/t/p/w500${item.poster_path}"
                    else null
                }
                else -> null
            }
        } ?: emptyList()
    }

    // 📺 Carica dettagli da TMDB
    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<TMDBData>(url) ?: throw ErrorLoadingException("Dati non validi")
        
        return if (data.type == "movie") {
            loadMovie(data.id)
        } else {
            loadTvSeries(data.id)
        }
    }

    private suspend fun loadMovie(movieId: Int): LoadResponse {
        val url = "$mainUrl/movie/$movieId?api_key=$tmdbApiKey&language=it&append_to_response=credits,videos,external_ids"
        val movie = app.get(url).parsedSafe<TMDBMovie>() ?: throw ErrorLoadingException("Film non trovato")
        
        // Cerca link streaming
        val streamingLinks = searchStreamingLinks("${movie.title} ${movie.release_date?.substring(0, 4)}", true)
        
        return newMovieLoadResponse(
            movie.title ?: "", 
            StreamingSearchData(
                title = movie.title ?: "",
                year = movie.release_date?.substring(0, 4),
                type = "movie",
                imdbId = movie.external_ids?.imdb_id
            ).toJson(), 
            TvType.Movie
        ) {
            posterUrl = if (movie.poster_path != null)
                "https://image.tmdb.org/t/p/w500${movie.poster_path}"
            else null
            backgroundPosterUrl = if (movie.backdrop_path != null)
                "https://image.tmdb.org/t/p/original${movie.backdrop_path}"
            else null
            plot = movie.overview
            year = movie.release_date?.substring(0, 4)?.toIntOrNull()
            duration = movie.runtime
            tags = movie.genres?.map { it.name }
            addScore(movie.vote_average?.toString())
            
            movie.credits?.cast?.take(10)?.mapNotNull { cast ->
                val actorName = cast.name ?: return@mapNotNull null
                val actorPhoto = if (cast.profile_path != null)
                    "https://image.tmdb.org/t/p/w200${cast.profile_path}"
                else null
                ActorData(Actor(actorName, actorPhoto), cast.character)
            }?.let { actors = it }
        }
    }

    private suspend fun loadTvSeries(tvId: Int): LoadResponse {
        val url = "$mainUrl/tv/$tvId?api_key=$tmdbApiKey&language=it&append_to_response=credits,videos,external_ids"
        val series = app.get(url).parsedSafe<TMDBTvSeries>() ?: throw ErrorLoadingException("Serie non trovata")
        
        // Prendi tutte le stagioni
        val allEpisodes = mutableListOf<Episode>()
        series.seasons?.forEach { season ->
            if (season.season_number ?: 0 > 0) { // Salta stagione 0 (specials)
                val seasonUrl = "$mainUrl/tv/$tvId/season/${season.season_number}?api_key=$tmdbApiKey&language=it"
                val seasonData = app.get(seasonUrl).parsedSafe<TMDBSeason>() ?: return@forEach
                
                seasonData.episodes?.forEach { episode ->
                    allEpisodes.add(
                        newEpisode(StreamingSearchData(
                            title = "${series.name}",
                            year = series.first_air_date?.substring(0, 4),
                            type = "tv",
                            imdbId = series.external_ids?.imdb_id,
                            season = episode.season_number,
                            episode = episode.episode_number
                        ).toJson()) {
                            name = episode.name
                            this.season = episode.season_number
                            this.episode = episode.episode_number
                            posterUrl = if (episode.still_path != null)
                                "https://image.tmdb.org/t/p/w500${episode.still_path}"
                            else null
                            description = episode.overview
                            addDate(episode.air_date)
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(
            series.name ?: "", 
            StreamingSearchData(
                title = series.name ?: "",
                year = series.first_air_date?.substring(0, 4),
                type = "tv",
                imdbId = series.external_ids?.imdb_id
            ).toJson(), 
            TvType.TvSeries, 
            allEpisodes
        ) {
            posterUrl = if (series.poster_path != null)
                "https://image.tmdb.org/t/p/w500${series.poster_path}"
            else null
            backgroundPosterUrl = if (series.backdrop_path != null)
                "https://image.tmdb.org/t/p/original${series.backdrop_path}"
            else null
            plot = series.overview
            year = series.first_air_date?.substring(0, 4)?.toIntOrNull()
            tags = series.genres?.map { it.name }
            addScore(series.vote_average?.toString())
            
            series.credits?.cast?.take(10)?.mapNotNull { cast ->
                val actorName = cast.name ?: return@mapNotNull null
                val actorPhoto = if (cast.profile_path != null)
                    "https://image.tmdb.org/t/p/w200${cast.profile_path}"
                else null
                ActorData(Actor(actorName, actorPhoto), cast.character)
            }?.let { actors = it }
        }
    }

    // 🔗 Cerca link su OnlineSerieTV con fallback
    private suspend fun searchStreamingLinks(query: String, isMovie: Boolean): List<String> {
        return try {
            // Pulizia query per ricerca
            val cleanQuery = cleanSearchQuery(query)
            
            // 1. Cerca sul sito
            val searchUrl = "$streamingBaseUrl/?s=${cleanQuery.urlEncoded()}"
            val searchPage = app.get(searchUrl, timeout = 15000).document
            
            // 2. Estrai primo risultato
            val firstResult = searchPage.selectFirst(".movie a, .post a")?.attr("href")
            if (firstResult == null) {
                Log.w("OnlineSerieTVHybrid", "Nessun risultato per: $cleanQuery")
                return emptyList()
            }
            
            // 3. Vai alla pagina del contenuto
            val contentPage = app.get(firstResult, timeout = 15000).document
            
            // 4. Estrai tutti i link
            contentPage.select("#hostlinks a, .links a, a[href*='uprot'], a[href*='stream']")
                .mapNotNull { it.attr("href").takeIf { href -> href.isNotEmpty() } }
            
        } catch (e: Exception) {
            Log.e("OnlineSerieTVHybrid", "Errore ricerca streaming: ${e.message}")
            emptyList()
        }
    }

    // 🎬 ESTRAZIONE LINK
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val query = parseJson<StreamingSearchData>(data) ?: return false
        
        // Crea query di ricerca
        val searchQuery = if (query.type == "tv" && query.season != null && query.episode != null) {
            "${query.title} stagione ${query.season} episodio ${query.episode}"
        } else {
            "${query.title} ${query.year ?: ""}"
        }.trim()
        
        // Cerca link
        val streamingLinks = searchStreamingLinks(searchQuery, query.type == "movie")
        
        // Processa link
        var foundLinks = false
        streamingLinks.forEach { link ->
            foundLinks = true
            when {
                link.contains("uprot") -> {
                    val bypassed = bypassUprot(link)
                    bypassed?.let { processLink(it, subtitleCallback, callback) }
                }
                else -> processLink(link, subtitleCallback, callback)
            }
        }
        
        return foundLinks
    }

    private suspend fun processLink(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            link.contains("streamtape") -> {
                StreamTapeExtractor().getUrl(link, null, subtitleCallback, callback)
            }
            link.contains("maxstream") || link.contains("msf") || link.contains("mse") -> {
                MaxStreamExtractor().getUrl(link, null, subtitleCallback, callback)
            }
            else -> {
                loadExtractor(link, subtitleCallback, callback)
            }
        }
    }

    // ⚙️ BYPASS UPROT
    private suspend fun bypassUprot(link: String): String? {
        val updatedLink = if ("msf" in link) link.replace("msf", "mse") else link
        return try {
            val response = app.get(updatedLink, timeout = 15000)
            response.document.selectFirst("a")?.attr("href")
        } catch (e: Exception) {
            null
        }
    }

    // 🛠️ UTILITIES
    private fun cleanSearchQuery(query: String): String {
        return query
            .replace(Regex("""[:\-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replace(" ", "+")
    }

    // 📊 CLASSI DATI
    data class TMDBData(val id: Int, val type: String)
    
    data class StreamingSearchData(
        val title: String,
        val year: String? = null,
        val type: String = "movie",
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )
    
    data class TMDBMultiSearch(@JsonProperty("results") val results: List<TMDBSearchResult>)
    
    data class TMDBSearchResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("media_type") val media_type: String,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster_path") val poster_path: String?
    )
    
    data class TMDBMovie(
        @JsonProperty("title") val title: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("vote_average") val vote_average: Float?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("external_ids") val external_ids: TMDBExternalIds?
    )
    
    data class TMDBTvSeries(
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("first_air_date") val first_air_date: String?,
        @JsonProperty("vote_average") val vote_average: Float?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("external_ids") val external_ids: TMDBExternalIds?
    )
    
    data class TMDBExternalIds(
        @JsonProperty("imdb_id") val imdb_id: String?
    )
    
    data class TMDBSeasonInfo(@JsonProperty("season_number") val season_number: Int?)
    
    data class TMDBSeason(@JsonProperty("episodes") val episodes: List<TMDBEpisode>?)
    
    data class TMDBEpisode(
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("air_date") val air_date: String?,
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("season_number") val season_number: Int
    )
    
    data class TMDBGenre(@JsonProperty("name") val name: String)
    
    data class TMDBCast(
        @JsonProperty("name") val name: String?,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )
    
    data class TMDBCredits(@JsonProperty("cast") val cast: List<TMDBCast>?)
}
