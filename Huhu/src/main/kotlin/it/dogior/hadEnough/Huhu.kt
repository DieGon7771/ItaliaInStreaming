package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class Huhu(domain: String, private val countries: Map<String, Boolean>, language: String) : MainAPI() {
    override var mainUrl = "https://$domain"
    override var name = "Huhu"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = language
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    private suspend fun getChannels(): List<Channel> {
        val enabledCountries = countries.filter { it.value }.keys.toList()
        val allChannels = mutableListOf<Channel>()
        val headers = mapOf("Content-Type" to "application/json")

        for (country in enabledCountries) {
            var cursor: Long? = null
            do {
                val body = mapOf(
                    "catalogId" to "iptv",
                    "search" to "",
                    "sort" to "trending",
                    "filter" to mapOf("group" to country),
                    "cursor" to cursor
                )
                val response = app.post("$mainUrl/mediaurl-catalog.json", headers = headers, json = body)
                val catalog = parseJson<CatalogResponse>(response.body.string())
                allChannels.addAll(catalog.items.map { item ->
                    Channel(
                        id = item.ids.id,
                        name = item.name,
                        url = item.url,
                        group = item.group
                    )
                })
                cursor = catalog.nextCursor
            } while (cursor != null)
        }

        return allChannels
    }

    companion object {
        var channels = emptyList<Channel>()

        @Suppress("ConstPropertyName")
        const val posterUrl =
            "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/Huhu/tv.png"
    }

    fun Channel.toSearchResponse(): LiveSearchResponse {
        return newLiveSearchResponse(
            name,
            this.toJson(),
            TvType.Live
        ) {
            posterUrl = Huhu.posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (channels.isEmpty()) {
            channels = getChannels()
        }
        val sections =
            channels.groupBy { it.group ?: "Unknown" }.map {
                HomePageList(
                    it.key,
                    it.value.map { channel -> channel.toSearchResponse() },
                    false
                )
            }.sortedBy { it.name }

        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (channels.isEmpty()) {
            channels = getChannels()
        }
        val matches = channels.filter { channel ->
            query.lowercase().replace(" ", "") in
                    channel.name.lowercase().replace(" ", "")
        }
        return matches.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = parseJson<Channel>(url)

        val resolveBody = mapOf("url" to channel.url)
        val reqHeaders = mapOf("Content-Type" to "application/json")
        val response = app.post("$mainUrl/mediaurl-resolve.json", headers = reqHeaders, json = resolveBody)
        val resolved = parseJson<List<ResolveResponse>>(response.body.string()).first()

        return newLiveStreamLoadResponse(
            channel.name,
            url,
            resolved.url
        ) {
            posterUrl = Companion.posterUrl
            tags = listOf(channel.group ?: "")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val streamUrl = try {
            val channel = parseJson<Channel>(data)
            val resolveBody = mapOf("url" to channel.url)
            val reqHeaders = mapOf("Content-Type" to "application/json")
            val response = app.post("$mainUrl/mediaurl-resolve.json", headers = reqHeaders, json = resolveBody)
            parseJson<List<ResolveResponse>>(response.body.string()).first().url
        } catch (e: Exception) {
            data
        }

        callback(
            newExtractorLink(
                this.name,
                this.name,
                streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to mainUrl
                )
            }
        )
        return true
    }

    // --- API response models ---

    data class CatalogItem(
        val type: String,
        val ids: Ids,
        val url: String,
        val name: String,
        @JsonProperty("group") val group: String?
    )

    data class Ids(val id: String)

    data class CatalogResponse(
        val items: List<CatalogItem>,
        val nextCursor: Long?,
        val features: Features?
    )

    data class Features(val filter: List<Filter>)

    data class Filter(
        val id: String,
        val name: String,
        val values: List<String>
    )

    data class ResolveResponse(
        val id: String,
        val name: String,
        val url: String
    )

    data class Channel(
        val id: String,
        val name: String,
        val url: String,
        @JsonProperty("group") val group: String?
    )
}
