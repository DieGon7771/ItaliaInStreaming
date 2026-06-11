package it.dogior.hadEnough.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class FlexyExtractor : ExtractorApi() {
    override var name = "Flexy"
    override var mainUrl = "https://flexy.stream/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("Flexy", "🟦 getUrl() INIZIO")
        Log.d("Flexy", "🟦 URL: $url")

        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 30_000L
            )
            Log.d("Flexy", "🟡 WebViewResolver in corso...")
            val response = app.get(url, referer = url, interceptor = resolver)
            val m3u8Url = response.url
            Log.d("Flexy", "🟡 URL intercettato: $m3u8Url")

            if (m3u8Url.isNotEmpty() && m3u8Url.contains(".m3u8")) {
                Log.d("Flexy", "✅✅✅ M3U8: $m3u8Url")
                M3u8Helper.generateM3u8(
                    name, m3u8Url, url,
                    headers = mapOf("referer" to "https://flexy.stream/")
                ).forEach(callback)
                Log.d("Flexy", "🎉 Done!")
            } else {
                Log.e("Flexy", "❌ M3U8 non intercettato")
            }
        } catch (e: Exception) {
            Log.e("Flexy", "❌ Errore: ${e.message}")
        }
    }
}
