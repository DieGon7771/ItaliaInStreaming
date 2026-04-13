package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.parser.Parser

class StreamingCommunity(
    override var lang: String = "it",
    private val showLogo: Boolean = true
) : MainAPI() {
    override var mainUrl = Companion.mainUrl + lang
    override var name = Companion.name
    override var supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary)
    override val hasMainPage = true

    companion object {
        private var inertiaVersion = ""
        private val headers = mapOf(
            "Cookie" to "",
            "X-Inertia" to true.toString(),
            "X-Inertia-Version" to inertiaVersion,
            "X-Requested-With" to "XMLHttpRequest",
        ).toMutableMap()
        val mainUrl = "https://streamingunity.biz/"
        var name = "StreamingCommunity"
        val TAG = "SCommunity"
    }

    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val tmdbApiKey = BuildConfig.TMDB_API
    
    private val tmdbHeaders = mapOf(
        "Authorization" to "Bearer $tmdbApiKey",
        "Accept" to "application/json"
    )

    override val mainPage = mainPageOf("home" to "Home")

    private fun isHtmlPayload(payload: String): Boolean {
        val trimmed = payload.trimStart()
        return trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)
    }

    private fun extractInertiaPageJson(html: String): String? {
        val dataPageRaw = org.jsoup.Jsoup.parse(html).selectFirst("#app")?.attr("data-page")
        if (dataPageRaw.isNullOrBlank()) return null
        return Parser.unescapeEntities(dataPageRaw, true)
    }

    private fun parseInertiaPayload(payload: String, logContext: String): InertiaResponse? {
        if (payload.isBlank()) {
            Log.e(TAG, "$logContext: empty payload")
            return null
        }
        if (isHtmlPayload(payload)) {
            Log.e(TAG, "$logContext: expected JSON but received HTML payload")
            return null
        }
        return runCatching { parseJson<InertiaResponse>(payload) }
            .onFailure { Log.e(TAG, "$logContext: invalid JSON payload - ${it.message}") }
            .getOrNull()
    }

    private fun parseBrowseTitles(payload: String, logContext: String): List<Title> {
        val jsonPayload = if (isHtmlPayload(payload)) {
            Log.e(TAG, "$logContext: received HTML payload, attempting embedded data-page fallback")
            extractInertiaPageJson(payload) ?: return emptyList()
        } else {
            payload
        }

        val result = parseInertiaPayload(jsonPayload, logContext) ?: return emptyList()
        return result.props.titles ?: emptyList()
    }

    private fun parseHomeSections(payload: String): List<HomePageList> {
        val jsonPayload = if (isHtmlPayload(payload)) {
            extractInertiaPageJson(payload)
        } else {
            payload
        } ?: return emptyList()

        val result = parseInertiaPayload(jsonPayload, "Homepage") ?: return emptyList()
        return result.props.sliders
            ?.mapNotNull { slider ->
                val items = searchResponseBuilder(slider.titles)
                if (items.isEmpty()) return@mapNotNull null
                HomePageList(
                    name = slider.label.ifBlank { slider.name },
                    list = items,
                    isHorizontalImages = false
                )
            }.orEmpty()
    }

    private fun parseSliderFetchSections(payload: String): List<HomePageList> {
        if (payload.isBlank()) return emptyList()
        val trimmedPayload = payload.trimStart()
        if (trimmedPayload.startsWith("{") || trimmedPayload.contains("\"message\"")) {
            Log.e(
                TAG,
                "Sliders fetch: received error object instead of slider array: ${payload.take(300)}"
            )
            return emptyList()
        }
        if (isHtmlPayload(payload)) {
            Log.e(TAG, "Sliders fetch: expected JSON array but received HTML payload")
            return emptyList()
        }

        val sliders = runCatching { parseJson<List<Slider>>(payload) }
            .onFailure { Log.e(TAG, "Sliders fetch: invalid JSON payload - ${it.message}") }
            .getOrNull()
            ?: return emptyList()

        return sliders.mapNotNull { slider ->
            val items = searchResponseBuilder(slider.titles)
            if (items.isEmpty()) return@mapNotNull null
            HomePageList(
                name = slider.label.ifBlank { slider.name },
                list = items,
                isHorizontalImages = false
            )
        }
    }

    private suspend fun setupHeaders() {
        val response = app.get("$mainUrl/archive")
        val cookies = response.cookies
        headers["Cookie"] = cookies.map { it.key + "=" + it.value }.joinToString(separator = "; ")
        val page = response.document
        val inertiaPageObject = page.select("#app").attr("data-page")
        inertiaVersion = inertiaPageObject
            .substringAfter("\"version\":\"")
            .substringBefore("\"")
        headers["X-Inertia-Version"] = inertiaVersion
    }

    private fun getSliderFetchHeaders(): Map<String, String> {
        return mapOf(
            "Cookie" to (headers["Cookie"] ?: ""),
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/",
            "Accept" to "application/json, text/plain, */*",
            "Origin" to Companion.mainUrl.removeSuffix("/")
        )
    }

    private fun searchResponseBuilder(listJson: List<Title>): List<SearchResponse> {
        val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
        val list: List<SearchResponse> =
            listJson.filter { it.type == "movie" || it.type == "tv" }.map { title ->
                val url = "$mainUrl/titles/${title.id}-${title.slug}"

                if (title.type == "tv") {
                    newTvSeriesSearchResponse(title.name, url) {
                        posterUrl = "https://cdn.${domain}/images/" + title.getPoster()
                    }
                } else {
                    newMovieSearchResponse(title.name, url) {
                        posterUrl = "https://cdn.$domain/images/" + title.getPoster()
                    }
                }
            }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val homePayload = app.get("$mainUrl/").body.string()
        val homepageSections = parseHomeSections(homePayload)

        if (headers["Cookie"].isNullOrEmpty()) {
            setupHeaders()
        }

        val lazyResponse = app.post(
            "${Companion.mainUrl}api/sliders/fetch",
            data = mapOf("lang" to lang),
            headers = getSliderFetchHeaders()
        )
        val lazyPayload = lazyResponse.body.string()
        Log.d(TAG, "Slider fetch status=${lazyResponse.code}")
        Log.d(TAG, "Slider fetch preview=${lazyPayload.take(500)}")

        val lazySections = parseSliderFetchSections(lazyPayload)
        if (lazySections.isEmpty()) {
            Log.d(TAG, "Lazy slider fetch returned no sections")
        }

        return newHomePageResponse(homepageSections + lazySections, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search"
        val response = app.get(url, params = mapOf("q" to query)).body.string()
        val titles = parseBrowseTitles(response, "Search")
        return searchResponseBuilder(titles)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val params = mutableMapOf("q" to query)
        if (page > 1) params["page"] = page.toString()
        val response = app.get("$mainUrl/search", params = params).body.string()
        val titles = parseBrowseTitles(response, "Search page=$page")
        val items = searchResponseBuilder(titles)
        val hasNext = items.isNotEmpty() && items.size >= 60
        return newSearchResponseList(items, hasNext = hasNext)
    }

    private suspend fun getPoster(title: TitleProp): String? {
        if (title.tmdbId != null) {
            val tmdbUrl = "https://www.themoviedb.org/${title.type}/${title.tmdbId}"
            val resp = app.get(tmdbUrl).document
            val img = resp.select("img.poster.w-full").attr("srcset").split(", ").last()
            return img
        } else {
            val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
            return title.getBackgroundImageId().let { "https://cdn.$domain/images/$it" }
        }
    }

    
    private suspend fun fetchTmdbLogoUrl(
        type: TvType,
        tmdbId: Int?,
        appLangCode: String?
    ): String? {
        if (tmdbId == null) return null
        
        return try {
            val appLang = appLangCode?.substringBefore("-")?.lowercase()
            val url = if (type == TvType.Movie) {
                "$tmdbAPI/movie/$tmdbId/images"
            } else {
                "$tmdbAPI/tv/$tmdbId/images"
            }
            
            
            val response = app.get(url, headers = tmdbHeaders)
            if (!response.isSuccessful) {
                Log.d(TAG, "TMDB API error: ${response.code}")
                return null
            }
            
            val jsonText = response.body?.string() ?: return null
            val json = JSONObject(jsonText)
            val logos = json.optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null
            
            fun logoUrlAt(i: Int): String {
                val logo = logos.getJSONObject(i)
                val filePath = logo.optString("file_path", "")
                return "https://image.tmdb.org/t/p/w500$filePath"
            }
            
            fun isSvg(i: Int): Boolean {
                val logo = logos.getJSONObject(i)
                val filePath = logo.optString("file_path", "")
                return filePath.endsWith(".svg", ignoreCase = true)
            }
            
            if (!appLang.isNullOrBlank()) {
                var svgFallback: String? = null
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    if (logo.optString("iso_639_1") == appLang) {
                        if (isSvg(i)) {
                            if (svgFallback == null) svgFallback = logoUrlAt(i)
                        } else {
                            return logoUrlAt(i)
                        }
                    }
                }
                if (svgFallback != null) return svgFallback
            }
            
            var enSvgFallback: String? = null
            for (i in 0 until logos.length()) {
                val logo = logos.getJSONObject(i)
                if (logo.optString("iso_639_1") == "en") {
                    if (isSvg(i)) {
                        if (enSvgFallback == null) enSvgFallback = logoUrlAt(i)
                    } else {
                        return logoUrlAt(i)
                    }
                }
            }
            if (enSvgFallback != null) return enSvgFallback
            
            for (i in 0 until logos.length()) {
                if (!isSvg(i)) {
                    return logoUrlAt(i)
                }
            }
            
            if (logos.length() > 0) logoUrlAt(0) else null
        } catch (e: Exception) {
            Log.d(TAG, "Error fetching TMDB logo: ${e.message}")
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val actualUrl = getActualUrl(url)
        if (headers["Cookie"].isNullOrEmpty()) {
            setupHeaders()
        }
        val response = app.get(actualUrl, headers = headers)
        val responseBody = response.body.string()

        val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
        val props = parseJson<InertiaResponse>(responseBody).props
        val title = props.title!!
        val genres = title.genres.map { it.name.capitalize() }
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val related = props.sliders?.getOrNull(0)
        val trailers = title.trailers?.mapNotNull { it.getYoutubeUrl() }
        val poster = getPoster(title)

        val logoUrl = if (showLogo && title.tmdbId != null) {
            val type = if (title.type == "tv") TvType.TvSeries else TvType.Movie
            fetchTmdbLogoUrl(
                type = type,
                tmdbId = title.tmdbId,
                appLangCode = lang
            )
        } else null

        if (title.type == "tv") {
            val episodes: List<Episode> = getEpisodes(props)

            val tvShow = newTvSeriesLoadResponse(
                title.name,
                actualUrl,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = "https://cdn.$domain/images/$it" }

                if (logoUrl != null) {
                    this.logoUrl = logoUrl
                }
                this.tags = genres
                this.episodes = episodes
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }

            }
            return tvShow
        } else {
            val data = LoadData(
                "$mainUrl/iframe/${title.id}&canPlayFHD=1",
                "movie",
                title.tmdbId
            )
            val movie = newMovieLoadResponse(
                title.name,
                actualUrl,
                TvType.Movie,
                dataUrl = data.toJson()
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = "https://cdn.$domain/images/$it" }

                if (logoUrl != null) {
                    this.logoUrl = logoUrl
                }
                this.tags = genres
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)

                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }

                title.runtime?.let { this.duration = it }
                if (trailers != null) {
                    if (trailers.isNotEmpty()) {
                        addTrailer(trailers)
                    }
                }
            }
            return movie
        }
    }

    private fun getActualUrl(url: String) =
        if (!url.contains(mainUrl)) {
            val replacingValue =
                if (url.contains("/it/") || url.contains("/en/")) mainUrl.toHttpUrl().host else mainUrl.toHttpUrl().host + "/$lang"
            val actualUrl = url.replace(url.toHttpUrl().host, replacingValue)

            Log.d("$TAG:UrlFix", "Old: $url\nNew: $actualUrl")
            actualUrl
        } else {
            url
        }

    private suspend fun getEpisodes(props: Props): List<Episode> {
        val episodeList = mutableListOf<Episode>()
        val title = props.title

        title?.seasons?.forEach { season ->
            val responseEpisodes = emptyList<it.dogior.hadEnough.Episode>().toMutableList()
            if (season.id == props.loadedSeason!!.id) {
                responseEpisodes.addAll(props.loadedSeason.episodes!!)
            } else {
                if (inertiaVersion == "") {
                    setupHeaders()
                }
                val url = "$mainUrl/titles/${title.id}-${title.slug}/season-${season.number}"
                val obj =
                    parseJson<InertiaResponse>(app.get(url, headers = headers).body.string())
                responseEpisodes.addAll(obj.props.loadedSeason?.episodes!!)
            }
            responseEpisodes.forEach { ep ->

                val loadData = LoadData(
                    "$mainUrl/iframe/${title.id}?episode_id=${ep.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    seasonNumber = season.number,
                    episodeNumber = ep.number)
                episodeList.add(
                    newEpisode(loadData.toJson()) {
                        this.name = ep.name
                        this.posterUrl = props.cdnUrl + "/images/" + ep.getCover()
                        this.description = ep.plot
                        this.episode = ep.number
                        this.season = season.number
                        this.runTime = ep.duration
                    }
                )
            }
        }

        return episodeList
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Load Data : $data")
        if (data.isEmpty()) return false
        val loadData = parseJson<LoadData>(data)

        val response = app.get(loadData.url).document
        val iframeSrc = response.select("iframe").attr("src")

        VixCloudExtractor().getUrl(
            url = iframeSrc,
            referer = mainUrl.substringBeforeLast("it"),
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        val vixsrcUrl = if(loadData.type == "movie"){
            "https://vixsrc.to/movie/${loadData.tmdbId}"
        } else{
            "https://vixsrc.to/tv/${loadData.tmdbId}/${loadData.seasonNumber}/${loadData.episodeNumber}"
        }

        VixSrcExtractor().getUrl(
            url = vixsrcUrl,
            referer = "https://vixsrc.to/",
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        return true
    }
}
