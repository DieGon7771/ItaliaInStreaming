package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

const val TAG = "AnimeUnity"

class AnimeUnity : MainAPI() {
    override var mainUrl = Companion.mainUrl
    override var name = Companion.name
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true

    companion object {
        @Suppress("ConstPropertyName")
        const val mainUrl = "https://animeunity.cx"
        var name = "AnimeUnity"

        private const val PAGE_SIZE = 30
        private const val SECTION_LATEST = "latest"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
    }

    override val mainPage = mainPageOf(
        SECTION_LATEST to "Ultimi Episodi",
        "ongoing" to "In Corso",
        "popular" to "Popolari",
        "best" to "I migliori",
        "upcoming" to "In Arrivo",
    )

    private suspend fun httpGet(url: String) = app.get(
        url,
        referer = "$mainUrl/",
        headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
        )
    )

    private suspend fun pageProps(url: String): AuPageProps {
        val raw = httpGet(url).document.selectFirst("[data-page]")
            ?.attr("data-page")
            ?.takeIf { it.isNotBlank() }
            ?: error("AnimeUnity: dati della pagina non trovati")
        return parseJson<AuPageData>(raw).props
            ?: error("AnimeUnity: props della pagina non trovate")
    }

    private fun normalizeUrl(url: String): String {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return url
        return if (path.startsWith("/anime/")) mainUrl + path else url
    }

    private fun parseVideoUrl(url: String): Triple<Int, String, Int>? {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return null
        val match = Regex("""^/anime/(\d+)-([^/]+)/(\d+)/?$""").find(path) ?: return null
        val animeId = match.groupValues[1].toIntOrNull() ?: return null
        val slug = match.groupValues[2]
        val videoId = match.groupValues[3].toIntOrNull() ?: return null
        return Triple(animeId, slug, videoId)
    }

    private fun languageLabel(language: String?): String? = when (language?.lowercase()) {
        "ita" -> "VixCloud - Doppiato"
        "jpn" -> "VixCloud - Sottotitolato"
        else -> null
    }

    private fun animeTitle(anime: AuAnime): String =
        anime.titleIt?.takeIf { it.isNotBlank() }
            ?: anime.title?.takeIf { it.isNotBlank() }
            ?: "AnimeUnity"

    private fun animeTvType(anime: AuAnime): TvType = when (anime.type?.lowercase()) {
        "movie" -> TvType.AnimeMovie
        "ova", "ona", "special" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun isDubbed(anime: AuAnime): Boolean =
        anime.variants.orEmpty().any { it.language.equals("ita", true) }

    private fun isSubbed(anime: AuAnime): Boolean =
        anime.variants.orEmpty().any { it.language.equals("jpn", true) }

    private fun parseEpisodeNumber(number: String): Double? =
        number.replace(',', '.').toDoubleOrNull()

    private fun cardOf(anime: AuAnime): AnimeSearchResponse? {
        val id = anime.id ?: return null
        val slug = anime.slug?.takeIf { it.isNotBlank() } ?: return null
        val title = animeTitle(anime)

        return newAnimeSearchResponse(title, "$mainUrl/anime/$id-$slug", animeTvType(anime)).apply {
            addPoster(anime.images?.poster)
            val dubbed = isDubbed(anime)
            val subbed = isSubbed(anime)
            if (dubbed || subbed) {
                addDubStatus(dubExist = dubbed, subExist = subbed)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (cards, hasNext) = try {
            if (request.data == SECTION_LATEST) {
                latestEpisodes(page)
            } else {
                archiveSection(request.data, page)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage(${request.name}): ${e.message}")
            emptyList<SearchResponse>() to false
        }

        return newHomePageResponse(
            HomePageList(request.name, cards, false),
            hasNext
        )
    }

    private suspend fun archiveSection(
        section: String,
        page: Int,
    ): Pair<List<SearchResponse>, Boolean> {
        val filter = when (section) {
            "ongoing" -> "&status=ongoing"
            "upcoming" -> "&status=upcoming"
            "popular" -> "&sort_by=members"
            "best" -> "&sort_by=score"
            else -> ""
        }
        val url = "$mainUrl/api/archive?page=$page&per_page=$PAGE_SIZE$filter"
        val result = parseJson<AuArchivePage>(httpGet(url).text)

        val cards = result.data.orEmpty()
            .distinctBy { it.id }
            .mapNotNull { cardOf(it) }

        return cards to (result.nextPageUrl != null)
    }

    private suspend fun latestEpisodes(page: Int): Pair<List<SearchResponse>, Boolean> {
        val url = "$mainUrl/api/home/latest-episodes?page=$page"
        val result = parseJson<AuLatestEpisodePage>(httpGet(url).text)

        val cards = result.data.orEmpty()
            .mapNotNull { it.episode?.anime }
            .distinctBy { it.id }
            .mapNotNull { cardOf(it) }

        return cards to (result.nextPageUrl != null)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        return try {
            val url = "$mainUrl/api/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}"
            parseJson<AuArchivePage>(httpGet(url).text).data.orEmpty()
                .distinctBy { it.id }
                .mapNotNull { cardOf(it) }
        } catch (e: Exception) {
            Log.e(TAG, "search($query): ${e.message}")
            emptyList()
        }
    }

    private suspend fun loadEpisodes(animeId: Int): List<AuEpisodeDto> {
        val episodes = mutableListOf<AuEpisodeDto>()
        var start = 1

        while (true) {
            val url = "$mainUrl/api/anime/$animeId/episodes?start=$start"
            val page = parseJson<AuEpisodesPage>(httpGet(url).text)
            val batch = page.episodes.orEmpty()
            if (batch.isEmpty()) break

            episodes += batch
            val step = page.tabSize?.takeIf { it > 0 } ?: 120
            start += step
            if (start > (page.total ?: 0)) break
        }

        return episodes.distinctBy { it.id }
    }

    private val episodesCache = java.util.concurrent.ConcurrentHashMap<Int, List<AuEpisodeDto>>()

    private suspend fun episodesFor(animeId: Int): List<AuEpisodeDto> {
        episodesCache[animeId]?.let { return it }
        return loadEpisodes(animeId).also { episodesCache[animeId] = it }
    }

    private fun buildEpisodeList(
        animeUrl: String,
        episodes: List<AuEpisodeDto>,
    ): List<Episode> {
        val byNumber = LinkedHashMap<String, MutableList<AuVideo>>()

        episodes.forEach { episode ->
            val number = episode.number?.trim().orEmpty()
            if (number.isEmpty()) return@forEach

            val videos = episode.videos.orEmpty()
                .filter { it.id != null && !it.isHidden && languageLabel(it.language) != null }
            if (videos.isNotEmpty()) byNumber.getOrPut(number) { mutableListOf() }.addAll(videos)
        }

        return byNumber.entries
            .sortedBy { (number, _) -> parseEpisodeNumber(number) ?: Double.MAX_VALUE }
            .mapNotNull { (number, videos) ->
                val video = videos.firstOrNull { it.language.equals("jpn", true) }
                    ?: videos.firstOrNull { it.language.equals("ita", true) }
                    ?: videos.first()
                val videoId = video.id ?: return@mapNotNull null

                newEpisode("$animeUrl/$videoId") {
                    this.episode = number.toIntOrNull()
                    if (this.episode == null) this.name = "Episodio $number"
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = normalizeUrl(url)
        val props = pageProps(pageUrl)
        val anime = props.anime ?: error("AnimeUnity: scheda anime non trovata")
        val animeId = anime.id ?: error("AnimeUnity: id anime mancante")
        val animeUrl = "$mainUrl/anime/$animeId-${anime.slug.orEmpty()}"

        val allEpisodes = episodesFor(animeId)
        val episodeList = buildEpisodeList(animeUrl, allEpisodes)
        val hasSub = allEpisodes.any { episode ->
            episode.videos.orEmpty().any { it.language.equals("jpn", true) && !it.isHidden }
        }
        val hasDub = allEpisodes.any { episode ->
            episode.videos.orEmpty().any { it.language.equals("ita", true) && !it.isHidden }
        }
        if (episodeList.isEmpty()) Log.w(TAG, "nessun episodio trovato per $animeUrl")

        return newAnimeLoadResponse(animeTitle(anime), animeUrl, animeTvType(anime)) {
            posterUrl = anime.images?.poster
                ?: runCatching { anime.anilistId?.let { getAniListPoster(it) } }.getOrNull()
            backgroundPosterUrl = anime.images?.banner
            year = anime.year
            plot = anime.plot

            addScore(anime.score)
            anime.episodeRuntime?.takeIf { it > 0 }?.let { addDuration("$it minuti") }
            anime.anilistId?.takeIf { it > 0 }?.let { addAniListId(it) }
            anime.malId?.takeIf { it > 0 }?.let { addMalId(it) }

            showStatus = when (anime.defaultStatus?.lowercase()) {
                "completed" -> ShowStatus.Completed
                "ongoing" -> ShowStatus.Ongoing
                else -> null
            }
            comingSoon = anime.defaultStatus.equals("upcoming", true)

            val audioTag = when {
                hasSub && hasDub -> "\uD83C\uDDEE\uD83C\uDDF9  Italiano / \uD83C\uDDEF\uD83C\uDDF5  Giapponese"
                hasDub -> "\uD83C\uDDEE\uD83C\uDDF9  Italiano"
                else -> "\uD83C\uDDEF\uD83C\uDDF5  Giapponese"
            }
            tags = listOf(audioTag) + anime.genres.orEmpty().mapNotNull { it.name }

            if (episodeList.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodeList)

            recommendations = props.relatedAnimes.orEmpty()
                .filter { it.id != animeId }
                .distinctBy { it.id }
                .mapNotNull { cardOf(it) }
        }
    }

    private suspend fun getAniListPoster(anilistId: Int): String? {
        val query = """
            query (${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    coverImage {
                        large
                        medium
                        extraLarge
                    }
                }
            }
        """.trimIndent()

        val body = mapOf(
            "query" to query,
            "variables" to """{"id":$anilistId}"""
        )
        val response = app.post("https://graphql.anilist.co", data = body)
        val coverImage = parseJson<AnilistResponse>(response.text).data?.media?.coverImage

        return coverImage?.large ?: coverImage?.extraLarge ?: coverImage?.medium
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = normalizeUrl(data)
        val parsed = parseVideoUrl(pageUrl) ?: return false
        val (animeId, slug, videoId) = parsed
        val animeUrl = "$mainUrl/anime/$animeId-$slug"

        val videos = try {
            episodesFor(animeId)
                .firstOrNull { episode -> episode.videos.orEmpty().any { it.id == videoId } }
                ?.videos.orEmpty()
                ?.filter { it.id != null && !it.isHidden && languageLabel(it.language) != null }
                ?.distinctBy { it.id }
                ?: return false
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks($pageUrl): ${e.message}")
            return false
        }

        var found = false
        for (video in videos) {
            val label = languageLabel(video.language) ?: continue
            val id = video.id ?: continue
            val embedUrl = try {
                pageProps("$animeUrl/$id").embedUrl?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(TAG, "embedUrl($id): ${e.message}")
                null
            } ?: continue

            try {
                VixCloudExtractor(label).getUrl(
                    url = embedUrl,
                    referer = "$mainUrl/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                found = true
            } catch (e: Exception) {
                Log.e(TAG, "VixCloud($embedUrl): ${e.message}")
            }
        }

        return found
    }
}
