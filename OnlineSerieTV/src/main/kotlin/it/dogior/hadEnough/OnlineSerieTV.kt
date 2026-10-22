package it.dogior.hadEnough

import com.lagradost.api.Log
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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.SocketTimeoutException

class OnlineSerieTV : MainAPI() {
    override var mainUrl = "https://onlineserietv.lol"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries,
        TvType.Cartoon, TvType.Anime, TvType.AnimeMovie, TvType.Documentary
    )
    override var lang = "it"
    override val hasMainPage = true

    private suspend fun cachedGet(
        url: String,
        cacheKey: String,
        profile: OnlineSerieTVCache.CacheProfile
    ): Document {
        OnlineSerieTVCache.get(cacheKey)?.let { return Jsoup.parse(it) }
        val doc = app.get(url, timeout = 15000).document
        OnlineSerieTVCache.put(cacheKey, doc.html(), profile)
        return doc
    }

    override val mainPage = mainPageOf(
//        mainUrl to "Top 10 Film",
//        mainUrl to "Top 10 Serie TV",
        "$mainUrl/movies/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/" to "Serie TV: Ultime aggiunte",

        "$mainUrl/serie-tv-generi/animazione/" to "Serie TV: Animazione",
        "$mainUrl/film-generi/animazione/" to "Film: Animazione",

//        "$mainUrl/serie-tv-generi/documentario/" to "Serie TV: Documentario",
//        "$mainUrl/film-generi/documentario/" to "Film: Documentario",

//        "$mainUrl/serie-tv-generi/action-adventure/" to "Serie TV: Azione e Avventura",
//        "$mainUrl/film-generi/avventura/" to "Film: Avventura",
        "$mainUrl/film-generi/azione/" to "Film: Azione",
//        "$mainUrl/film-generi/supereroi/" to "Film: Supereroi",

        "$mainUrl/serie-tv-generi/sci-fi-fantasy/" to "Serie TV: Fantascienza e Fantasy",
//        "$mainUrl/film-generi/fantascienza/" to "Film: Fantascienza",
//        "$mainUrl/film-generi/fantasy/" to "Film: Fantasy",

//        "$mainUrl/serie-tv-generi/dramma/" to "Serie TV: Dramma",
//        "$mainUrl/film-generi/drammatico/" to "Film: Dramma",
//        "$mainUrl/film-generi/sentimentale/" to "Film: Sentimentale",

//        "$mainUrl/serie-tv-generi/commedia/" to "Serie TV: Commedia",
//        "$mainUrl/film-generi/commedia/" to "Film: Commedia",

//        "$mainUrl/serie-tv-generi/crime/" to "Serie TV: Crime",
//        "$mainUrl/serie-tv-generi/mistero/" to "Serie TV: Mistero",

//        "$mainUrl/serie-tv-generi/war-politics/" to "Serie TV: Guerra e Politica",
        "$mainUrl/film-generi/horror/" to "Film: Horror",
//        "$mainUrl/film-generi/thriller/" to "Film: Thriller",

//        "$mainUrl/serie-tv-generi/reality/" to "Serie TV: Reality",

        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val cacheKey = "OSV:PAGE:${request.data}"
        val doc = try {
            cachedGet(request.data, cacheKey, OnlineSerieTVCache.CacheProfile.PAGE)
        } catch (e: Exception) {
            OnlineSerieTVCache.get(cacheKey, allowExpired = true)?.let { Jsoup.parse(it) }
                ?: return null
        }
        val searchResponses = getItems(request.name, doc)
        return newHomePageResponse(HomePageList(request.name, searchResponses), false)
    }

    private suspend fun getItems(section: String, page: Document): List<SearchResponse> {
        val searchResponses = when (section) {
            "Film: Ultimi aggiunti", "Serie TV: Ultime aggiunte" -> {
                val itemGrid = page.selectFirst(".wp-block-uagb-post-grid")!!
                val items = itemGrid.select(".uagb-post__inner-wrap")
                items.map {
                    val itemTag = it.select(".uagb-post__title > a")
                    val title = itemTag.text().trim().replace(Regex("""\d{4}$"""), "")
                    val url = itemTag.attr("href")
                    val poster = it.select(".uagb-post__image > a > img").attr("src")

                    newTvSeriesSearchResponse(title, url) {
                        this.posterUrl = poster
                    }
                }
            }

            "Top 10 Film", "Top 10 Serie TV" -> {
                val sidebar = page.selectFirst(".sidebar_right")!!
                val bothTop10 = sidebar.select(".links")
                val currentTop10 = if (section == "Top 10 Film") {
                    bothTop10.last()
                } else {
                    bothTop10.first()
                }
                val items = currentTop10?.select(".scrolling > li")
                items?.amap {
                    val title = it.select("a").text().trim().replace(Regex("""\d{4}$"""), "")
                    val url = it.select("a").attr("href")

                    val showPage = try {
                        app.get(url).document
                    } catch (e: SocketTimeoutException) {
                        null
                    }
                    val poster = showPage?.select(".imgs > img:nth-child(1)")?.attr("src")
                    newTvSeriesSearchResponse(title, url) {
                        this.posterUrl = poster
                    }
                } ?: emptyList()
            }

            "Film: Avventura",
            "Film: Azione",
            "Film: Animazione",
            "Film: Commedia",
            "Film: Documentario",
            "Film: Dramma",
            "Film: Horror",
            "Film: Thriller",
            "Film: Fantascienza",
            "Film: Fantasy",
            "Film: Supereroi",
            "Film: Sentimentale",
            "Serie TV: Azione e Avventura",
            "Serie TV: Fantascienza e Fantasy",
            "Serie TV: Dramma",
            "Serie TV: Crime",
            "Serie TV: Mistero",
            "Serie TV: Commedia",
            "Serie TV: Reality",
            "Serie TV: Guerra e Politica",
            "Serie TV: Documentario",
            "Serie TV: Animazione" -> {
                val itemGrid = page.selectFirst("#box_movies")!!
                val items = itemGrid.select(".movie")
                items.map {
                    it.toSearchResponse()
                }
            }

            else -> emptyList()
        }
        return searchResponses
    }


    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select("h2").text().trim().replace(Regex("""\d{4}$"""), "")
        val url = this.select("a").attr("href")
        val poster = this.select("img").attr("src")
        return newTvSeriesSearchResponse(title, url) {
            this.posterUrl = poster
        }
    }

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val cacheKey = "OSV:SEARCH:$query"
        val page = cachedGet(url, cacheKey, OnlineSerieTVCache.CacheProfile.SEARCH)
        val itemGrid = page.selectFirst("#box_movies")!!
        val items = itemGrid.select(".movie")
        val searchResponses = items.map {
            it.toSearchResponse()
        }
        return searchResponses
    }

    override suspend fun load(url: String): LoadResponse {
        val cacheKey = "OSV:DETAIL:$url"
        val response = cachedGet(url, cacheKey, OnlineSerieTVCache.CacheProfile.DETAIL)
        val dati = response.selectFirst(".headingder")!!
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
            val plot = response.selectFirst("b:contains(Trama)")?.nextElementSibling()?.text()?.trim() ?: ""
            newMovieLoadResponse(title, url, TvType.Movie, streamUrl) {
                addPoster(poster)
                addScore(rating)
                this.duration = duration.toIntOrNull()
                this.year = year.toIntOrNull()
                this.tags = genres.split(",")
                this.plot = plot
            }
        } else {
            val episodes = getEpisodes(response)
            val plot = response.selectFirst("b:contains(Trama)")?.nextElementSibling()?.text()?.trim() ?: ""
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                addPoster(poster)
                addScore(rating)
                this.year = year.toIntOrNull()
                this.tags = genres.split(",")
                this.plot = plot
            }
        }
    }

    private fun getEpisodes(page: Document): List<Episode> {
        val table = page.selectFirst("#hostlinks > table:nth-child(1)")!!
        var season: Int? = 1
        val rows = table.select("tr")
        val episodes: List<Episode> = rows.mapNotNull {
            if (it.childrenSize() == 0) {
                null
            } else if (it.childrenSize() == 1) {
                val seasonText =
                    it.select("td:nth-child(1)").text().substringBefore("- Episodi disponibi")
                season = Regex("""\d+""").find(seasonText)?.value?.toInt()
                null
            } else {
                val title = it.select("td:nth-child(1)").text()
                val links = it.select("a").map { a -> "\"${a.attr("href")}\"" }
                newEpisode("$links") {
                    this.season = season
                    this.episode = title.substringAfter("x").substringBefore(" ").toIntOrNull()
                }
            }
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links = runCatching { parseJson<List<String>>(data) }.getOrNull() ?: return false
        links.forEach { raw ->
            val link = raw.trim()
            if (!link.contains("uprot")) return@forEach
            if (link.contains("msd") || link.contains("fxf")) return@forEach

            val m3u8 = UprotResolver.resolve(link)
            if (m3u8 == null) {
                Log.e("OnlineSerieTV", "resolve failed: $link")
                return@forEach
            }
            callback.invoke(
                newExtractorLink(
                    source = "MaxStream",
                    name = "MaxStream",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://maxstream.video/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}