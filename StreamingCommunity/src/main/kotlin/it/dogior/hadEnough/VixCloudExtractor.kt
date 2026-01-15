package it.dogior.hadEnough

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class VixCloudExtractor : ExtractorApi() {
    override val mainUrl = "vixcloud.co"
    override val name = "VixCloud"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val safeReferer = referer ?: "https://vixcloud.co/"
        val playlistUrl = getPlaylistLink(url, safeReferer)
        
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Cache-Control" to "no-cache",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
            "Referer" to safeReferer,
            "Origin" to "https://vixcloud.co"
        )

        // ⚡ FUNZIONE CHE FUNZIONA PER TUTTE LE VERSIONI
        val link = createCompatibleExtractorLink(
            name = "VixCloud",
            source = "VixCloud",
            url = playlistUrl,
            headers = headers,
            referer = safeReferer
        )
        
        callback.invoke(link)
    }

    // ⭐ NUOVA FUNZIONE: Compatibile con tutte le versioni
    private fun createCompatibleExtractorLink(
        name: String,
        source: String,
        url: String,
        headers: Map<String, String>,
        referer: String
    ): ExtractorLink {
        return try {
            // 1. Prima prova: Nuovo costruttore (Prerelease/4.X)
            // Se questa riga fallisce, va al catch
            ExtractorLink(
                name = name,
                source = source,
                url = url,
                type = ExtractorLinkType.M3U8,
                quality = Qualities.P720.value,
                headers = headers,
                referer = referer
            )
        } catch (e: NoSuchMethodError) {
            // 2. Fallback: API vecchia (Stable/3.X)
            newExtractorLink(
                source = source,
                name = name,
                url = url,
                type = ExtractorLinkType.VIDEO,  // Stable non ha M3U8
                quality = Qualities.P720.value
            ) {
                this.headers = headers
                this.referer = referer
            }
        } catch (e: IllegalArgumentException) {
            // 3. Fallback se M3U8 non esiste
            val linkType = if (ExtractorLinkType.values().any { it.name == "M3U8" }) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            
            newExtractorLink(
                source = source,
                name = name,
                url = url,
                type = linkType,
                quality = Qualities.P720.value
            ) {
                this.headers = headers
                this.referer = referer
            }
        }
    }

    private suspend fun getPlaylistLink(url: String, referer: String): String {
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

        if (script.getBoolean("canPlayFHD")) {
            masterPlaylistUrl += "&h=1"
        }

        return masterPlaylistUrl
    }

    private suspend fun getScript(url: String, referer: String): JSONObject {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Cache-Control" to "no-cache",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
            "Referer" to referer
        )
        
        val iframe = app.get(url, headers = headers, interceptor = CloudflareKiller()).document
        val scripts = iframe.select("script")
        val script = scripts.find { it.data().contains("masterPlaylist") }!!.data().replace("\n", "\t")

        return JSONObject(getSanitisedScript(script))
    }

    private fun getSanitisedScript(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1)

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            val cleaned = value
                .replace(";", "")
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }
        
        return "{\n${jsonObjects.joinToString(",\n")}\n}".replace("'", "\"")
    }
}
