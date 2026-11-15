package it.dogior.hadEnough.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class UqloadExtractor : ExtractorApi() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.vc"
    override val requiresReferer = true

    private companion object {
        const val TAG = "UqloadExtractor"
        const val UA = WebViewSniffer.UA

        
        val KICK = """
            (function(){try{
              if(window.jwplayer){try{jwplayer().play();}catch(e){}}
              ['#vid_play','#desk','.jw-icon-display','.jw-display-icon-container','.vjs-big-play-button',
               '.plyr__control--overlaid','#play','.play-button','.btn-play','#player'].forEach(function(s){
                document.querySelectorAll(s).forEach(function(e){try{e.click();}catch(x){}});
              });
              document.querySelectorAll('video').forEach(function(v){try{v.muted=true;v.play();}catch(x){}});
            }catch(e){}})();
        """.trimIndent()

        fun isMedia(url: String): Boolean {
            val path = url.lowercase().substringBefore("?")
            if (listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".css", ".js", ".ts", ".m4s", ".vtt")
                    .any { path.endsWith(it) }
            ) return false
            return path.endsWith(".m3u8") || path.endsWith(".mp4")
        }

        fun findInHtml(html: String): List<String> {
            val text = html.replace("\\/", "/")
            val out = LinkedHashSet<String>()
            Regex("""https?://[^"'\s\\<>]+?\.m3u8[^"'\s\\<>]*""").findAll(text).forEach { out += it.value }
            Regex("""(?:sources\s*:\s*\[\s*|file\s*:\s*)["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""")
                .findAll(text).forEach { out += it.groupValues[1] }
            return out.filter { it.startsWith("http") }.toList()
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        extract(url, referer, callback)
    }

    suspend fun extract(url: String, referer: String?, callback: (ExtractorLink) -> Unit): Boolean {
        val pageReferer = referer ?: "https://guardaplay.autos/"
        val host = url.substringAfter("://").substringBefore("/")
        val playerReferer = "https://$host/"

        // 1) lettura diretta: la pagina /e/<codice> e' un form che fa POST su /dl (op=embed)
        val code = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
            .substringBefore(".html").substringAfterLast("-")
        val direct = try {
            val h = mapOf("User-Agent" to UA)
            var links = findInHtml(app.get(url, referer = pageReferer, headers = h).text)
            if (links.isEmpty() && code.isNotBlank()) {
                val html = app.post(
                    "https://$host/dl",
                    data = mapOf(
                        "op" to "embed",
                        "file_code" to code,
                        "auto" to "1",
                        "referer" to pageReferer
                    ),
                    referer = url,
                    headers = h
                ).text
                links = findInHtml(html)
                if (links.isEmpty() && html.contains("eval(function(p,a,c,k,e,d)")) {
                    // player offuscato (p,a,c,k,e,d)
                    val unpacked = try { JsUnpacker(html).unpack() } catch (_: Exception) { null }
                    if (unpacked != null) links = findInHtml(unpacked)
                }
            }
            links
        } catch (e: Exception) {
            Log.w(TAG, "lettura diretta: ${e.message}")
            emptyList()
        }
        Log.d(TAG, "link nella pagina: ${direct.size}")

        // 2) WebView
        val candidates = direct.ifEmpty {
            WebViewSniffer.sniff(
                url = url,
                referer = pageReferer,
                kickJs = KICK,
                accept = ::isMedia,
                allowNavigation = { it.contains("uqload", ignoreCase = true) },
            )
        }.distinct().sortedByDescending { it.contains("master", ignoreCase = true) }
        Log.d(TAG, "candidati: $candidates")

        var any = false
        val headers = mapOf("Referer" to playerReferer, "Origin" to playerReferer.trimEnd('/'), "User-Agent" to UA)
        for (video in candidates) {
            if (video.lowercase().substringBefore("?").endsWith(".m3u8")) {
                try {
                    M3u8Helper.generateM3u8(name, video, playerReferer, headers = headers).forEach {
                        any = true
                        callback(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "m3u8: ${e.message}")
                }
            } else {
                any = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = video,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = playerReferer
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
            }
            if (any) break // il master basta: contiene gia' tutte le qualita'
        }
        return any
    }
}
