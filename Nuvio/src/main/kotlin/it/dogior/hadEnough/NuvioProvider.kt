package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class NuvioProvider(private val manager: NuvioRepoManager) : MainAPI() {
    override var mainUrl = "https://www.themoviedb.org"
    override var name = "Nuvio"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$TMDB_API/trending/all/day?api_key=$API_KEY&region=IT&language=it" to "Di tendenza",
        "$TMDB_API/movie/popular?api_key=$API_KEY&region=IT&language=it" to "Film popolari",
        "$TMDB_API/tv/popular?api_key=$API_KEY&region=IT&language=it" to "Serie TV popolari",
    )

    companion object {
        private const val TAG = "NuvioProvider"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val API_KEY = BuildConfig.TMDB_API3
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/original"

        private fun imageUrl(path: String?): String? {
            if (path == null) return null
            return if (path.startsWith("/")) "$IMAGE_BASE$path" else path
        }
    }

    // ── catalogue ──

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adultQuery =
            if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669"
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get("${request.data}$adultQuery&page=$page")
            .parsedSafe<TmdbResults>()?.results?.mapNotNull { it.toSearchResponse(this, type) }
            ?: throw ErrorLoadingException("Risposta TMDB non valida")
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        searchTmdb(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? =
        searchTmdb(query, page)

    private suspend fun searchTmdb(query: String, page: Int): SearchResponseList? {
        val q = URLEncoder.encode(query, "UTF-8")
        return app.get(
            "$TMDB_API/search/multi?api_key=$API_KEY&language=it&query=$q&page=$page" +
                    "&include_adult=${settingsForProvider.enableAdult}"
        ).parsedSafe<TmdbResults>()?.results
            ?.filter { it.mediaType == null || it.mediaType == "movie" || it.mediaType == "tv" }
            ?.mapNotNull { it.toSearchResponse(this) }
            ?.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<TmdbData>(url)
        val isMovie = data.type == "movie"
        val kind = if (isMovie) "movie" else "tv"

        val res = app.get(
            "$TMDB_API/$kind/${data.id}?api_key=$API_KEY&language=it" +
                    "&append_to_response=keywords,credits,external_ids,videos,recommendations"
        ).parsedSafe<MediaDetail>() ?: throw ErrorLoadingException("Risposta TMDB non valida")

        val title = res.title ?: res.name ?: throw ErrorLoadingException("Titolo non trovato")
        val poster = imageUrl(res.posterPath)
        val backdrop = imageUrl(res.backdropPath)
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }
        val isAnime = genres?.contains("Animation") == true &&
                (res.originalLanguage == "ja" || res.originalLanguage == "zh")
        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name }.orEmpty() }
        val actors = res.credits?.cast?.mapNotNull { c ->
            ActorData(
                Actor(c.name ?: c.originalName ?: return@mapNotNull null, imageUrl(c.profilePath)),
                roleString = c.character
            )
        }
        val recommendations = res.recommendations?.results?.mapNotNull { it.toSearchResponse(this) }
        val trailer = res.videos?.results?.mapNotNull { it.key }
            ?.map { "https://www.youtube.com/watch?v=$it" }?.randomOrNull()
        val tags = keywords.ifEmpty { genres ?: emptyList() }

        if (isMovie) {
            return newMovieLoadResponse(
                title, url, TvType.Movie,
                NuvioLoadData(tmdbId = data.id.toString(), mediaType = "movie").toJson()
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = tags
                this.score = Score.from10(res.voteAverage?.toString())
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.externalIds?.imdbId)
            }
        }

        val seasons = res.seasons.orEmpty().filter { (it.seasonNumber ?: 0) > 0 }
        val episodes = seasons.amap { season ->
            val seasonUrl = "$TMDB_API/tv/${data.id}/season/${season.seasonNumber}" +
                    "?api_key=$API_KEY&language=it"
            app.get(seasonUrl).parsedSafe<SeasonDetail>()?.episodes.orEmpty().map { ep ->
                newEpisode(
                    NuvioLoadData(
                        tmdbId = data.id.toString(),
                        mediaType = "tv",
                        season = ep.seasonNumber,
                        episode = ep.episodeNumber
                    ).toJson()
                ) {
                    this.name = ep.name
                    this.season = ep.seasonNumber
                    this.episode = ep.episodeNumber
                    this.posterUrl = imageUrl(ep.stillPath)
                    this.score = Score.from10(ep.voteAverage?.toString())
                    this.description = ep.overview
                    this.addDate(ep.airDate)
                }
            }
        }.flatten()

        return newTvSeriesLoadResponse(
            title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = res.overview
            this.tags = tags
            this.score = Score.from10(res.voteAverage?.toString())
            this.showStatus =
                if (res.status == "Returning Series") ShowStatus.Ongoing else ShowStatus.Completed
            this.recommendations = recommendations
            this.actors = actors
            addTrailer(trailer)
            addTMDbId(data.id.toString())
            addImdbId(res.externalIds?.imdbId)
        }
    }

    // ── streams ──

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = parseJson<NuvioLoadData>(data)
        val tmdbId = load.tmdbId ?: return false
        val mediaType = load.mediaType ?: return false

        val targets = manager.enabledScrapers().filter { (_, s) -> s.supports(mediaType) }
        if (targets.isEmpty()) return false

        val timeoutMs = manager.timeoutSeconds * 1000L
        val found = AtomicInteger(0)

        targets.amap { (repo, scraper) ->
            val code = manager.readScript(repo, scraper) ?: return@amap
            val result = withContext(Dispatchers.IO) {
                NuvioJsEngine.run(
                    scraperName = scraper.name,
                    code = code,
                    settingsJson = manager.settingsFor(repo, scraper),
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    season = load.season,
                    episode = load.episode,
                    timeoutMs = timeoutMs,
                    client = app.baseClient,
                )
            }
            if (result.error != null) Log.d(TAG, "${scraper.name}: ${result.error}")
            val json = result.json ?: return@amap
            try {
                found.addAndGet(emitStreams(json, scraper, subtitleCallback, callback))
            } catch (e: Exception) {
                Log.d(TAG, "${scraper.name}: risposta non leggibile (${e.message})")
            }
            Unit
        }
        return found.get() > 0
    }

    private suspend fun emitStreams(
        json: String,
        scraper: NuvioScraper,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val arr = JSONArray(json)
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            var url = o.str("url")
            if (url == null) {
                val hash = o.str("infoHash")
                if (hash != null) url = "magnet:?xt=urn:btih:$hash"
            }
            if (url == null) continue

            val title = o.str("title")
            val displayName = o.str("name") ?: title?.lineSequence()?.firstOrNull() ?: scraper.name
            val behavior = o.optJSONObject("behaviorHints")
            val headers = o.optJSONObject("headers")?.toStringMap()
                ?: behavior?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.toStringMap()
                ?: behavior?.optJSONObject("headers")?.toStringMap()
                ?: emptyMap()

            val headerMap = LinkedHashMap<String, String>(headers)
            if (headerMap.keys.none { it.equals("user-agent", true) }) headerMap["User-Agent"] = DEFAULT_UA
            val referer = headerMap.entries.firstOrNull { it.key.equals("referer", true) }?.value ?: ""

            val declaredType = o.str("type")?.lowercase()
            val linkType: ExtractorLinkType? = when {
                declaredType == "hls" || url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                declaredType == "dash" || url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                else -> INFER_TYPE
            }

            callback.invoke(
                newExtractorLink(scraper.name, displayName, url, linkType) {
                    this.quality = qualityValue(o.str("quality"), "$displayName ${title ?: ""}")
                    this.headers = headerMap
                    this.referer = referer
                }
            )
            count++

            val subs = o.optJSONArray("subtitles")
            if (subs != null) {
                for (j in 0 until subs.length()) {
                    val s = subs.optJSONObject(j) ?: continue
                    val subUrl = s.str("url") ?: s.str("uri") ?: continue
                    val code = s.str("language") ?: s.str("lang")
                    val label = s.str("name") ?: s.str("title")
                        ?: code?.let { SubtitleHelper.fromTagToEnglishLanguageName(it) } ?: code ?: "Sub"
                    subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                }
            }
        }
        return count
    }

    private val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun qualityValue(quality: String?, fallbackText: String): Int {
        val t = "${quality ?: ""} $fallbackText".lowercase()
        return when {
            Regex("2160|\\b4k\\b|\\buhd\\b").containsMatchIn(t) -> Qualities.P2160.value
            Regex("1440").containsMatchIn(t) -> Qualities.P1440.value
            Regex("1080|\\bfhd\\b").containsMatchIn(t) -> Qualities.P1080.value
            Regex("720|\\bhd\\b").containsMatchIn(t) -> Qualities.P720.value
            Regex("480").containsMatchIn(t) -> Qualities.P480.value
            Regex("360").containsMatchIn(t) -> Qualities.P360.value
            Regex("240").containsMatchIn(t) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").trim().ifEmpty { null }
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in keys()) {
            if (!isNull(k)) out[k] = optString(k)
        }
        return out
    }

    // ── TMDB data classes ──

    data class TmdbData(val id: Int? = null, val type: String? = null)

    data class TmdbResults(@JsonProperty("results") val results: ArrayList<TmdbMedia>? = arrayListOf())

    data class TmdbMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
    ) {
        fun toSearchResponse(provider: NuvioProvider, type: String? = null): SearchResponse? {
            val kind = mediaType ?: type
            return provider.newMovieSearchResponse(
                title ?: name ?: originalTitle ?: return null,
                TmdbData(id = id, type = kind).toJson(),
                if (kind == "movie") TvType.Movie else TvType.TvSeries,
            ) { this.posterUrl = imageUrl(posterPath) }
        }
    }

    data class Genre(@JsonProperty("id") val id: Int? = null, @JsonProperty("name") val name: String? = null)
    data class Keyword(@JsonProperty("id") val id: Int? = null, @JsonProperty("name") val name: String? = null)
    data class KeywordResults(
        @JsonProperty("results") val results: ArrayList<Keyword>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: ArrayList<Keyword>? = arrayListOf(),
    )

    data class TmdbSeason(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class TmdbCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class SeasonDetail(@JsonProperty("episodes") val episodes: ArrayList<TmdbEpisode>? = arrayListOf())
    data class Trailer(@JsonProperty("key") val key: String? = null)
    data class TrailerResults(@JsonProperty("results") val results: ArrayList<Trailer>? = arrayListOf())
    data class ExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)
    data class Credits(@JsonProperty("cast") val cast: ArrayList<TmdbCast>? = arrayListOf())
    data class Recommendations(@JsonProperty("results") val results: ArrayList<TmdbMedia>? = arrayListOf())

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genre>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: KeywordResults? = null,
        @JsonProperty("seasons") val seasons: ArrayList<TmdbSeason>? = arrayListOf(),
        @JsonProperty("videos") val videos: TrailerResults? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: Recommendations? = null,
    )
}
