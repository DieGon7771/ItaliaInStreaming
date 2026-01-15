package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class VixCloudExtractor : ExtractorApi() {
    override val mainUrl = "vixcloud.co"
    override val name = "VixCloud"
    override val requiresReferer = false
    val TAG = "VixCloudExtractor"
    private var referer: String? = null
    private val h = mutableMapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        this.referer = referer
        Log.d(TAG, "REFERER: $referer  URL: $url")
        
        try {
            val playlistUrl = getPlaylistLink(url, referer)
            Log.w(TAG, "FINAL URL: $playlistUrl")

            // 🔧 DETERMINA TIPO LINK (M3U8 per streaming, VIDEO per download se possibile)
            val linkType = if (playlistUrl.contains(".m3u8")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = "VixCloud",
                    name = "Streaming Community - VixCloud",
                    url = playlistUrl,
                    type = linkType
                ) {
                    this.headers = h
                    this.referer = referer ?: ""
                    // 🔧 IMPORTANTE per download M3U8
                    this.isM3u8 = playlistUrl.contains(".m3u8")
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Errore VixCloudExtractor: ${e.message}", e)
            // Non bloccare, lascia che VixSrc provi
        }
    }

    private suspend fun getPlaylistLink(url: String, referer: String?): String {
        Log.d(TAG, "Item url: $url")

        val script = getScript(url, referer)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val masterPlaylistParams = masterPlaylist.getJSONObject("params")
        val token = masterPlaylistParams.getString("token")
        val expires = masterPlaylistParams.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")

        var masterPlaylistUrl: String
        val params = "token=${token}&expires=${expires}"
        masterPlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&$params"
        } else {
            "${playlistUrl}?$params"
        }
        Log.d(TAG, "masterPlaylistUrl: $masterPlaylistUrl")

        if (script.getBoolean("canPlayFHD")) {
            masterPlaylistUrl += "&h=1"
        }

        Log.d(TAG, "Master Playlist URL: $masterPlaylistUrl")
        return masterPlaylistUrl
    }

    private suspend fun getScript(url: String, referer: String?): JSONObject {
        Log.d(TAG, "Item url: $url")

        // 🔧 AGGIUNGI REFERER AGLI HEADERS
        val headers = h.toMutableMap()
        referer?.let { headers["Referer"] = it }

        val iframe = app.get(url, headers = headers, interceptor = CloudflareKiller()).document
        Log.d(TAG, iframe.toString())

        val scripts = iframe.select("script")
        val script =
            scripts.find { it.data().contains("masterPlaylist") }!!.data().replace("\n", "\t")

        val scriptJson = getSanitisedScript(script)
        Log.d(TAG, "Script Json: $scriptJson")
        return JSONObject(scriptJson)
    }

    private fun getSanitisedScript(script: String): String {
        // Split by top-level assignments like window.xxx =
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1) // first split part is empty before first assignment

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            // Clean up the value
            val cleaned = value
                .replace(";", "")
                // Quote keys only inside objects
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                // Remove trailing commas before } or ]
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }
        val finalObject =
            "{\n${jsonObjects.joinToString(",\n")}\n}"
                .replace("'", "\"")

        return finalObject
    }
}
