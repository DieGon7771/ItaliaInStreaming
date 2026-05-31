package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.SubtitleFile

class AnimeSaturnExtractor : ExtractorApi() {
    override val name = "AnimeSaturn"
    override val mainUrl = "https://www.animesaturn.cx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val timeout = 60L

        Log.i(TAG, "🍿 getUrl INIZIO → url=$url referer=$referer")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Referer" to url
        )

        try {
            Log.i(TAG, "📥 1) Scarico pagina episodio: $url")
            val episodeDoc = app.get(url, timeout = timeout).document
            Log.i(TAG, "📄 1a) Pagina episodio scaricata, title=${episodeDoc.title()}")

            val watchLink = episodeDoc.select("a[href*='/watch?file=']").attr("href")
            Log.i(TAG, "🔗 2) watchLink trovato: '$watchLink'")
            if (watchLink.isBlank()) {
                Log.w(TAG, "❌ 2b) watchLink VUOTO! Nessun link /watch?file= trovato")
                return
            }

            val baseWatchUrl = fixUrl(watchLink)
            Log.i(TAG, "🔗 2c) baseWatchUrl = $baseWatchUrl")

            // ── Main: video source ──
            try {
                Log.i(TAG, "📥 3) MAIN → fetch watch: $baseWatchUrl")
                Log.i(TAG, "📥 3a) headers = $headers")
                val playerDoc = app.get(baseWatchUrl, headers = headers, timeout = timeout).document
                Log.i(TAG, "📄 3b) Pagina watch scaricata, title=${playerDoc.title()}")
                Log.i(TAG, "📄 3c) HTML primi 500 char: ${playerDoc.html().take(500)}")

                val videoUrl = playerDoc.select("video source").attr("src")
                Log.i(TAG, "🎞️ 4) MAIN → video source trovato: '$videoUrl'")
                if (videoUrl.isNotBlank()) {
                    Log.i(TAG, "✅ 4a) MAIN → Invio callback: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "AnimeSaturn",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = 1080
                            this.referer = baseWatchUrl
                        }
                    )
                    Log.i(TAG, "✅ 4b) MAIN → Callback inviato con successo")
                } else {
                    Log.w(TAG, "⚠️ 4c) MAIN → video source NON TROVATO! Controllo HTML...")
                    // Prova con body.string() e regex diretto su mp4
                    val body = app.get(baseWatchUrl, headers = headers, timeout = timeout).body.string()
                    Log.i(TAG, "📄 4d) Body primi 500 char: ${body.take(500)}")
                    val mp4Url = Regex("""src="([^"]+\.mp4)"""").find(body)?.groupValues?.get(1)
                    Log.i(TAG, "🎞️ 4e) MAIN → regex mp4: '$mp4Url'")
                    if (mp4Url != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "AnimeSaturn",
                                url = mp4Url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = 1080
                                this.referer = baseWatchUrl
                            }
                        )
                        Log.i(TAG, "✅ 4f) MAIN → Callback da regex inviato: $mp4Url")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 MAIN → eccezione: ${e.message}")
                Log.e(TAG, "💥 MAIN → stack: ${e.stackTraceToString()}")
            }

            // ── Alt: jwplayer file ──
            try {
                val altUrl = "$baseWatchUrl&s=alt"
                val altHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Referer" to baseWatchUrl
                )
                Log.i(TAG, "📥 5) ALT → fetch alt: $altUrl")
                Log.i(TAG, "📥 5a) ALT → Referer=$baseWatchUrl")
                val altDoc = app.get(altUrl, headers = altHeaders, timeout = timeout).document
                Log.i(TAG, "📄 5b) ALT → pagina scaricata, title=${altDoc.title()}")

                val scriptCount = altDoc.select("script").size
                Log.i(TAG, "📄 5c) ALT → ${scriptCount} script trovati")

                var altVideoUrl: String? = altDoc.select("script").mapNotNull { script ->
                    val html = script.html()
                    if (html.contains("file:")) {
                        Log.i(TAG, "🔍 5d) ALT → script contiene 'file:': ${html.take(300)}")
                    }
                    Regex("""file:\s*"([^"]+)""").find(html)?.groupValues?.get(1)
                }.firstOrNull()

                Log.i(TAG, "🎞️ 6) ALT → jwplayer video url: '$altVideoUrl'")

                if (altVideoUrl.isNullOrBlank()) {
                    Log.w(TAG, "⚠️ 6a) ALT → jwplayer fallito! Provo body.string() + regex...")
                    val rawBody = app.get(altUrl, headers = altHeaders, timeout = timeout).body.string()
                    Log.i(TAG, "📄 6b) ALT → body primi 500: ${rawBody.take(500)}")
                    altVideoUrl = Regex("""file:\s*"([^"]+)""").find(rawBody)?.groupValues?.get(1)
                    Log.i(TAG, "🎞️ 6c) ALT → regex su body: '$altVideoUrl'")
                }

                if (!altVideoUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "AnimeSaturn (Alt)",
                            url = altVideoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = 1080
                            this.referer = altUrl
                        }
                    )
                    Log.i(TAG, "✅ 6d) ALT → Callback inviato: $altVideoUrl")
                } else {
                    Log.w(TAG, "❌ 6e) ALT → NESSUN video trovato!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 ALT → eccezione: ${e.message}")
                Log.e(TAG, "💥 ALT → stack: ${e.stackTraceToString()}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "💥 getUrl → eccezione generale: ${e.message}")
            Log.e(TAG, "💥 getUrl → stack: ${e.stackTraceToString()}")
        }

        Log.i(TAG, "🏁 getUrl FINE")
    }
}
