package it.dogior.hadEnough.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class MaxStreamExtractor : ExtractorApi() {
    override var name = "MaxStream"
    override var mainUrl = "https://maxstream.video/"
    override val requiresReferer = false

    private val cfClient by lazy {
        app.baseClient.newBuilder()
            .addInterceptor(CloudflareKiller())
            .build()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("MaxStream", "🟦 getUrl() INIZIO")
        Log.d("MaxStream", "🟦 URL ricevuto: $url")

        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Referer", url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            Log.d("MaxStream", "🟡 Fetch URL con CloudflareKiller...")
            val response = cfClient.newCall(request).execute()
            val finalUrl = response.request.url.toString()
            val html = response.body?.string() ?: ""
            response.close()

            Log.d("MaxStream", "🟡 URL finale: $finalUrl")
            Log.d("MaxStream", "🟡 HTML ricevuto, lunghezza: ${html.length}")

            val m3u8Match = Regex("""src:\s*"([^"]+master\.m3u8[^"]*)""").find(html)
            val m3u8Url = m3u8Match?.groupValues?.get(1)

            if (m3u8Url == null) {
                Log.e("MaxStream", "❌ M3U8 non trovato nell'HTML!")
                Log.d("MaxStream", "🔍 master.m3u8 presente? ${html.contains("master.m3u8")}")
                Log.d("MaxStream", "🔍 sources presente? ${html.contains("sources")}")
                return
            }

            Log.d("MaxStream", "✅✅✅ M3U8: $m3u8Url")
            M3u8Helper.generateM3u8(
                name, m3u8Url, finalUrl,
                headers = mapOf("referer" to "https://maxstream.video/")
            ).forEach(callback)
            Log.d("MaxStream", "🎉 Done!")
        } catch (e: Exception) {
            Log.e("MaxStream", "❌ Errore: ${e.message}")
        }
    }
}
