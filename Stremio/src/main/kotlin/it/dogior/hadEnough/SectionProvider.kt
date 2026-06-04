package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixTitle
import com.lagradost.cloudstream3.imdbUrlToIdNullable
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import it.dogior.hadEnough.SubsExtractors.invokeOpenSubs
import it.dogior.hadEnough.SubsExtractors.invokeWatchsomuch
import org.json.JSONObject
import java.net.URLEncoder

class SectionProvider(
    override var mainUrl: String,
    override var name: String,
    private val config: SectionConfig
) : MainAPI() {
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true

    override val mainPage: List<MainPageData>
        get() = if (catalogUrl != null) {
            mainPageOf("placeholder://catalog" to name)
        } else {
            mainPageOf(
                "$TMDB_API/trending/all/day?api_key=$API_KEY&region=IT&language=it" to labelTrending,
                "$TMDB_API/movie/popular?api_key=$API_KEY&region=IT&language=it" to labelPopularMovies,
                "$TMDB_API/tv/popular?api_key=$API_KEY&region=IT&language=it" to labelPopularTv
            )
        }

    companion object {
        var labelTrending = "Trending"
        var labelPopularMovies = "Film Popolari"
        var labelPopularTv = "Serie TV Popolari"
        var upcomingTag = " [UPCOMING]"

        const val TRACKER_LIST_URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
        const val CINEMETA_URL = "https://v3-cinemeta.strem.io"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val API_KEY = BuildConfig.TMDB_API3

        fun getType(t: String?): TvType = when (t) {
            "movie" -> TvType.Movie
            else -> TvType.TvSeries
        }

        fun getStatus(t: String?): ShowStatus = when (t) {
            "Returning Series" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

        private fun getImageUrlCtx(link: String?): String? {
            if (link == null) return null
            return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
        }
    }

    private val catalogUrl: String? get() = config.catalogUrl?.let { it.fixSourceUrl().ifEmpty { null } }

    // ── CATALOG MODE (catalogUrl != null) ──

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catUrl = catalogUrl
        if (catUrl != null) return getCatalogMainPage(catUrl, page, request)
        return getTmdbMainPage(page, request)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val catUrl = catalogUrl
        if (catUrl != null) {
            try {
                val manifest = app.get("$catUrl/manifest.json").parsedSafe<Manifest>()
                if (manifest != null) {
                    val supportedCatalogs = manifest.catalogs.filter { it.supportsSearch() }
                    val addonResults = supportedCatalogs.amap { catalog ->
                        catalog.search(query, catUrl, this)
                    }.flatten().distinctBy { it.url }
                    if (addonResults.isNotEmpty()) return addonResults
                }
            } catch (_: Exception) {}
            return searchTmdb(query, 1)?.items ?: emptyList()
        }
        return searchTmdb(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (catalogUrl != null) {
            if (page == 1) return search(query).toNewSearchResponseList()
            return null
        }
        return searchTmdb(query, page)
    }

    override suspend fun load(url: String): LoadResponse {
        val catUrl = catalogUrl
        if (catUrl != null) {
            try {
                return loadFromCatalog(catUrl, url)
            } catch (_: Exception) {
                val regex = Regex("/meta/([^/]+)/([^/.]+)")
                val match = regex.find(url) ?: throw RuntimeException("Cannot load: $url")
                val type = match.groupValues[1]
                val id = match.groupValues[2]
                val catUrlFixed = if ((type == "movie" || type == "series") && isImdborTmdb(id))
                    CINEMETA_URL else catUrl
                val encodedId = URLEncoder.encode(id, "UTF-8")
                val response = app.get("$catUrlFixed/meta/$type/$encodedId.json")
                    .parsedSafe<CatalogResponse>()
                val entry = response?.meta ?: response?.metas?.firstOrNull { it.id == id }
                    ?: response?.metas?.firstOrNull()
                    ?: throw RuntimeException("Failed to load meta")
                return entry.toLoadResponse(this, id)
            }
        }
        return loadFromTmdb(url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)

        // parallel: catalog stream + each configured stream addon + subs
        runAllAsync(
            {
                if (catalogUrl != null) {
                    invokeCatalogStream(catalogUrl!!, loadData, subtitleCallback, callback)
                }
            },
            {
                config.streamAddons.amap { addon ->
                    invokeStreamAddon(addon, loadData, subtitleCallback, callback)
                }
            },
            {
                invokeWatchsomuch(imdbIdFromLoad(loadData), loadData.season, loadData.episode, subtitleCallback)
            },
            {
                invokeOpenSubs(imdbIdFromLoad(loadData), loadData.season, loadData.episode, subtitleCallback)
            }
        )

        return true
    }

    // ── private helpers ──

    private suspend fun getCatalogMainPage(catUrl: String, page: Int, request: MainPageRequest): HomePageResponse {
        val manifest = app.get("$catUrl/manifest.json").parsedSafe<Manifest>()
        val lists = mutableListOf<HomePageList>()
        manifest?.catalogs?.filter { !it.isSearchRequired() }?.amap { catalog ->
            catalog.toHomePageList(catUrl, this).let {
                if (it.list.isNotEmpty()) lists.add(it)
            }
        }
        return newHomePageResponse(lists, false)
    }

    private suspend fun loadFromCatalog(catUrl: String, url: String): LoadResponse {
        val res: CatalogEntry = if (url.startsWith("{")) {
            parseJson(url)
        } else {
            val json = app.get(url).text
            val metaJson = JSONObject(json).getJSONObject("meta").toString()
            parseJson(metaJson)
        }

        val catUrlFixed = if ((res.type == "movie" || res.type == "series") && isImdborTmdb(res.id))
            CINEMETA_URL else catUrl

        val encodedId = URLEncoder.encode(res.id, "UTF-8")
        val response = app.get("$catUrlFixed/meta/${res.type}/$encodedId.json")
            .parsedSafe<CatalogResponse>()
            ?: throw RuntimeException("Failed to load meta")

        val entry = response.meta ?: response.metas?.firstOrNull { it.id == res.id }
            ?: response.metas?.firstOrNull()
            ?: throw RuntimeException("Meta not found")

        return entry.toLoadResponse(this, res.id)
    }

    private suspend fun invokeCatalogStream(
        catUrl: String,
        loadData: LoadData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val encodedId = URLEncoder.encode(loadData.id, "UTF-8")
        val request = app.get(
            "$catUrl/stream/${loadData.type}/$encodedId.json",
            timeout = 120L
        )
        if (request.isSuccessful) {
            val res = request.parsedSafe<StreamsResponse>()
            res?.streams?.forEach { stream ->
                stream.runCallback(TRACKER_LIST_URL, subtitleCallback, callback)
            }
        }
    }

    private suspend fun invokeStreamAddon(
        addon: StreamAddonConfig,
        loadData: LoadData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (addon.url.isBlank()) return
        val fixUrl = addon.url.fixSourceUrl()
        val imdbId = imdbIdFromLoad(loadData) ?: return
        val url = if (loadData.season == null) {
            "$fixUrl/stream/movie/$imdbId.json"
        } else {
            "$fixUrl/stream/series/$imdbId:${loadData.season}:${loadData.episode}.json"
        }
        try {
            val res = app.get(url, timeout = 120L).parsedSafe<StreamsResponse>()
            res?.streams?.forEach { stream ->
                stream.runCallback(TRACKER_LIST_URL, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    private fun imdbIdFromLoad(loadData: LoadData): String? {
        return loadData.imdbId ?: loadData.id?.takeIf { it.startsWith("tt") }
    }

    private fun isImdborTmdb(url: String?): Boolean {
        return imdbUrlToIdNullable(url) != null || url?.startsWith("tmdb:") == true
    }

    // ── TMDB MODE (no catalogUrl) ──

    private suspend fun getTmdbMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adultQuery = if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669|190370"
        val type = if (request.data.contains("/movie")) "movie" else "tv"

        val home = app.get("${request.data}$adultQuery&page=$page")
            .parsedSafe<TmdbResults>()?.results?.mapNotNull { media ->
                media.toSearchResponse(this, type)
            } ?: throw ErrorLoadingException("Invalid Json response")

        return newHomePageResponse(request.name, home)
    }

    private suspend fun searchTmdb(query: String, page: Int): SearchResponseList? {
        return app.get(
            "$TMDB_API/search/multi?api_key=$API_KEY&language=it&query=$query&page=$page&include_adult=${settingsForProvider.enableAdult}"
        ).parsedSafe<TmdbResults>()?.results?.mapNotNull { media ->
            media.toSearchResponse(this)
        }?.toNewSearchResponseList()
    }

    private suspend fun loadFromTmdb(url: String): LoadResponse {
        val data = parseJson<TmdbData>(url)
        val type = getType(data.type)

        val res = app.get(
            if (type == TvType.Movie) {
                "$TMDB_API/movie/${data.id}?api_key=$API_KEY&language=it&append_to_response=keywords,credits,external_ids,videos,recommendations"
            } else {
                "$TMDB_API/tv/${data.id}?api_key=$API_KEY&language=it&append_to_response=keywords,credits,external_ids,videos,recommendations"
            }
        ).parsedSafe<MediaDetail>() ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: throw ErrorLoadingException("No title found")
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }
        val isAnime = genres?.contains("Animation") == true && (res.original_language == "zh" || res.original_language == "ja")
        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(cast.name ?: cast.originalName ?: return@mapNotNull null, getImageUrl(cast.profilePath)),
                roleString = cast.character
            )
        } ?: throw ErrorLoadingException("No cast found")
        val recommendations = res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse(this) }
        val trailer = res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }?.randomOrNull()

        return if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            res.seasons?.forEach { season ->
                val seasonUrl = "$TMDB_API/tv/${data.id}/season/${season.seasonNumber}?api_key=$API_KEY&language=it"
                app.get(seasonUrl).parsedSafe<MediaDetailEpisodes>()?.episodes?.forEach { eps ->
                    episodes.add(
                        newEpisode(LoadData(
                            id = res.external_ids?.imdb_id,
                            season = eps.seasonNumber,
                            episode = eps.episodeNumber
                        ).toJson()) {
                            this.name = eps.name + if (isUpcoming(eps.airDate)) upcomingTag else ""
                            this.season = eps.seasonNumber
                            this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath)
                            this.score = Score.from10(eps.voteAverage)
                            this.description = eps.overview
                            this.addDate(eps.airDate)
                        }
                    )
                }
            }
            newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: (genres ?: emptyList())
                this.score = Score.from10(res.vote_average.toString())
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, LoadData(id = res.external_ids?.imdb_id).toJson()) {
                this.posterUrl = poster
                this.comingSoon = isUpcoming(releaseDate)
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: (genres ?: emptyList())
                this.score = Score.from10(res.vote_average.toString())
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        }
    }

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
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
        fun toSearchResponse(provider: SectionProvider, type: String? = null): SearchResponse? {
            return provider.newMovieSearchResponse(
                title ?: name ?: originalTitle ?: return null,
                TmdbData(id = id, type = mediaType ?: type).toJson(),
                TvType.Movie,
            ) { this.posterUrl = getImageUrlCtx(posterPath) }
        }
    }

    data class Genres(@JsonProperty("id") val id: Int? = null, @JsonProperty("name") val name: String? = null)
    data class Keywords(@JsonProperty("id") val id: Int? = null, @JsonProperty("name") val name: String? = null)
    data class KeywordResults(
        @JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )
    data class TmdbSeasons(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )
    data class TmdbCast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )
    data class TmdbEpisodes(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )
    data class MediaDetailEpisodes(@JsonProperty("episodes") val episodes: ArrayList<TmdbEpisodes>? = arrayListOf())
    data class Trailers(@JsonProperty("key") val key: String? = null)
    data class ResultsTrailer(@JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf())
    data class ExternalIds(@JsonProperty("imdb_id") val imdb_id: String? = null, @JsonProperty("tvdb_id") val tvdb_id: String? = null)
    data class TmdbCredits(@JsonProperty("cast") val cast: ArrayList<TmdbCast>? = arrayListOf())
    data class ResultsRecommendations(@JsonProperty("results") val results: ArrayList<TmdbMedia>? = arrayListOf())
    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val vote_average: Any? = null,
        @JsonProperty("original_language") val original_language: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: KeywordResults? = null,
        @JsonProperty("last_episode_to_air") val last_episode_to_air: LastEpisodeToAir? = null,
        @JsonProperty("seasons") val seasons: ArrayList<TmdbSeasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("external_ids") val external_ids: ExternalIds? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
    )
    data class LastEpisodeToAir(
        @JsonProperty("episode_number") val episode_number: Int? = null,
        @JsonProperty("season_number") val season_number: Int? = null,
    )

}

// ── Catalog data classes ──

data class Manifest(val catalogs: List<Catalog>)
data class Extra(val name: String? = null, @JsonProperty("isRequired") val isRequired: Boolean? = null)
data class Catalog(
    var name: String?,
    val id: String,
    val type: String?,
    val types: MutableList<String> = mutableListOf(),
    val extra: List<Extra>? = null,
    val extraSupported: List<String>? = null
) {
    init { if (type != null) types.add(type) }

    fun isSearchRequired(): Boolean {
        return extra?.any { it.name == "search" && it.isRequired == true } == true
    }

    fun supportsSearch(): Boolean {
        val hasSearchInExtra = extra?.any { it.name == "search" } == true
        val hasSearchInExtraSupported = extraSupported?.contains("search") == true
        return hasSearchInExtra || hasSearchInExtraSupported
    }

    suspend fun search(query: String, catUrl: String, provider: SectionProvider): List<SearchResponse> {
        val entries = mutableListOf<SearchResponse>()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        types.forEach { type ->
            val res = app.get("$catUrl/catalog/${type}/$id/search=$encodedQuery.json", timeout = 120L)
                .parsedSafe<CatalogResponse>()
            res?.metas?.forEach { entry ->
                entries.add(entry.toSearchResponse(provider))
            }
        }
        return entries
    }

    suspend fun toHomePageList(catUrl: String, provider: SectionProvider): HomePageList {
        val entries = mutableListOf<SearchResponse>()
        types.forEach { type ->
            val res = app.get("$catUrl/catalog/${type}/$id.json", timeout = 120L)
                .parsedSafe<CatalogResponse>()
            res?.metas?.forEach { entry ->
                entries.add(entry.toSearchResponse(provider))
            }
        }
        return HomePageList(name ?: id, entries)
    }
}

data class CatalogResponse(val metas: List<CatalogEntry>?, val meta: CatalogEntry?)
data class Trailer(val source: String?, val type: String?)
data class CatalogEntry(
    @JsonProperty("name") val name: String,
    @JsonProperty("id") val id: String,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("background") val background: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("imdbRating") val imdbRating: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("videos") val videos: List<CatalogVideo>?,
    @JsonProperty("genre") val genre: List<String>?,
    @JsonProperty("genres") val genres: List<String> = emptyList(),
    @JsonProperty("cast") val cast: List<String> = emptyList(),
    @JsonProperty("trailers") val trailersSources: List<Trailer> = emptyList(),
    @JsonProperty("year") val yearNum: String? = null
) {
    fun toSearchResponse(provider: SectionProvider): SearchResponse {
        return provider.newMovieSearchResponse(fixTitle(name), this.toJson(), TvType.Others) {
            posterUrl = poster
        }
    }

    suspend fun toLoadResponse(provider: SectionProvider, imdbId: String?): LoadResponse {
        if (videos.isNullOrEmpty()) {
            return provider.newMovieLoadResponse(
                name, "${provider.mainUrl}/meta/${type}/${id}.json", TvType.Movie,
                LoadData(type, id, imdbId = imdbId, year = yearNum?.toIntOrNull())
            ) {
                posterUrl = poster; backgroundPosterUrl = background
                score = Score.from10(imdbRating); plot = description; year = yearNum?.toIntOrNull()
                tags = genre ?: genres; addActors(cast)
                addTrailer(trailersSources.map { "https://www.youtube.com/watch?v=${it.source}" })
                addImdbId(imdbId)
            }
        } else {
            return provider.newTvSeriesLoadResponse(
                name, "${provider.mainUrl}/meta/${type}/${id}.json", TvType.TvSeries,
                videos.map { it.toEpisode(provider, type, imdbId) }
            ) {
                posterUrl = poster; backgroundPosterUrl = background
                score = Score.from10(imdbRating); plot = description; year = yearNum?.toIntOrNull()
                tags = genre ?: genres; addActors(cast)
                addTrailer(trailersSources.map { "https://www.youtube.com/watch?v=${it.source}" }?.randomOrNull())
                addImdbId(imdbId)
            }
        }
    }
}

data class CatalogVideo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("season") val seasonNumber: Int? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("description") val description: String? = null,
) {
    fun toEpisode(provider: SectionProvider, type: String?, imdbId: String?): Episode {
        return provider.newEpisode(LoadData(type, id, seasonNumber, episode ?: number, imdbId)) {
            this.name = this@CatalogVideo.name ?: title
            this.posterUrl = thumbnail
            this.description = overview ?: this@CatalogVideo.description
            this.season = seasonNumber
            this.episode = this@CatalogVideo.episode ?: number
        }
    }
}
