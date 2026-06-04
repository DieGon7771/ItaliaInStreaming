package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.sequences.forEach

// ── Section data model ──

data class StreamAddonConfig(
    val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val url: String = "",
    val type: String = "https"
)

data class SectionConfig(
    val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val catalogUrl: String? = null,
    val streamAddons: List<StreamAddonConfig> = emptyList()
)

// ── Stream data classes shared across providers ──

data class Subtitle(
    val url: String?,
    val lang: String?,
    val id: String?,
)

data class ProxyHeaders(
    val request: Map<String, String>?,
)

data class BehaviorHints(
    val proxyHeaders: ProxyHeaders?,
    val headers: Map<String, String>?,
)

data class Stream(
    val name: String?,
    val title: String?,
    val url: String?,
    val description: String?,
    val ytId: String?,
    val externalUrl: String?,
    val behaviorHints: BehaviorHints?,
    val infoHash: String?,
    val sources: List<String> = emptyList(),
    val subtitles: List<Subtitle> = emptyList()
) {
    suspend fun runCallback(
        trackersUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url != null) {
            callback.invoke(
                newExtractorLink(
                    name ?: "",
                    fixSourceName(name, title),
                    url,
                    INFER_TYPE,
                ) {
                    this.quality = getQuality(listOf(description, title, name))
                    this.headers = behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf()
                }
            )
            subtitles.map { sub ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        SubtitleHelper.fromTagToEnglishLanguageName(sub.lang ?: "") ?: sub.lang
                        ?: "",
                        sub.url ?: return@map
                    )
                )
            }
        }
        if (ytId != null) {
            loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
        }
        if (externalUrl != null) {
            loadExtractor(externalUrl, subtitleCallback, callback)
        }
        if (infoHash != null) {
            val resp = app.get(trackersUrl).text
            val otherTrackers = resp
                .split("\n")
                .filterIndexed { i, _ -> i % 2 == 0 }
                .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }
            val sourceTrackers = sources
                .filter { it.startsWith("tracker:") }
                .map { it.removePrefix("tracker:") }
                .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }
            val magnet = "magnet:?xt=urn:btih:${infoHash}${sourceTrackers}${otherTrackers}"
            callback.invoke(
                newExtractorLink(
                    name ?: "",
                    title ?: name ?: "",
                    magnet,
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

data class StreamsResponse(val streams: List<Stream>)

data class LoadData(
    val type: String? = null,
    val id: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
    val year: Int? = null
)

fun String.fixSourceUrl(): String {
    return this.replace("/manifest.json", "").replace("stremio://", "https://")
}

fun fixSourceName(name: String?, title: String?): String {
    return when {
        name?.contains("[RD+]", true) == true -> "[RD+] $title"
        name?.contains("[RD download]", true) == true -> "[RD download] $title"
        !name.isNullOrEmpty() && !title.isNullOrEmpty() -> "$name $title"
        else -> title ?: name ?: ""
    }
}

fun getQuality(qualities: List<String?>): Int {
    fun String.getQuality(): String? {
        return Regex("(\\d{3,4}[pP])").find(this)?.groupValues?.getOrNull(1)
    }
    val quality = qualities.firstNotNullOfOrNull { it?.getQuality() }
    return getQualityFromName(quality)
}

fun getEpisodeSlug(
    season: Int? = null,
    episode: Int? = null,
): Pair<String, String> {
    return if (season == null && episode == null) {
        "" to ""
    } else {
        (if (season!! < 10) "0$season" else "$season") to (if (episode!! < 10) "0$episode" else "$episode")
    }
}

fun isUpcoming(dateString: String?): Boolean {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
        unixTimeMS < dateTime
    } catch (t: Throwable) {
        logError(t)
        false
    }

}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

data class TorrentioResponse(
    @SerializedName("streams") val streams: List<TorrentioStream> = emptyList()
)

data class TorrentioStream(
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("infoHash") val infoHash: String? = null,
    @SerializedName("fileIdx") val fileIdx: Int? = null
)


suspend fun generateMagnetLink(
    trackerUrls: List<String>,
    hash: String?,
): String {
    require(hash?.isNotBlank() == true)

    val trackers = mutableSetOf<String>()

    trackerUrls.forEach { url ->
        try {
            val response = app.get(url)
            response.text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { trackers.add(it) }
        } catch (_: Exception) {
            // ignore bad sources
        }
    }

    return buildString {
        append("magnet:?xt=urn:btih:").append(hash)

        if (hash.isNotBlank()) {
            append("&dn=")
            append(URLEncoder.encode(hash, StandardCharsets.UTF_8.name()))
        }

        trackers
            .take(10) // practical limit
            .forEach { tracker ->
                append("&tr=")
                append(URLEncoder.encode(tracker, StandardCharsets.UTF_8.name()))
            }
    }
}
