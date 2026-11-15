package it.dogior.hadEnough.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class LoadMExtractor : ExtractorApi() {
    override val name = "Mload"
    override val mainUrl = "https://mload.cam"
    override val requiresReferer = true

    private companion object {
        const val TAG = "LoadMExtractor"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val html = try {
            app.get(
                url,
                referer = referer ?: "https://guardaplay.autos/",
                headers = mapOf("User-Agent" to UA)
            ).text
        } catch (e: Exception) {
            Log.e(TAG, "pagina player: ${e.message}")
            return
        }

        val datas = Regex("""datas\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(html)?.groupValues?.get(1)
        if (datas == null) {
            Log.e(TAG, "datas non trovato (${html.length} byte): ${html.take(200)}")
            return
        }

        val sources = try {
            AbyssProxy.parseDatas(datas)
        } catch (e: Exception) {
            Log.e(TAG, "decifratura fallita: ${e.message}")
            return
        }
        Log.d(TAG, "sorgenti: ${sources.map { "${it.label}/${it.codec}@${it.domain}" }}")

        val usable = sources
            .filter { it.codec.equals("h264", true) }
            .ifEmpty { sources.filter { !it.codec.equals("av1", true) } }
            .let { list -> list.filter { it.label != "origin" }.ifEmpty { list } }
            .distinctBy { it.label }

        usable.forEach { s ->
            val local = AbyssProxy.register(s)
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ${s.label}".trim(),
                    url = local,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = ""
                    this.quality = s.label.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                }
            )
        }
    }
}
