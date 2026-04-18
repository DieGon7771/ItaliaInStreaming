package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "https://vixsrc.to"
    override val name = "StreamingCommunity - VixSrc"
    override val requiresReferer = true
    private val TAG = "VixSrcExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
        val seasonEpisode = Regex("""/tv/\d+/(\d+)/(\d+)""").find(url)
        val season = seasonEpisode?.groupValues?.get(1)
        val episode = seasonEpisode?.groupValues?.get(2)
        
        if (tmdbId == null) {
            Log.e(TAG, "Cannot extract TMDB ID from URL")
            return
        }
        
        val apiUrl = if (season != null && episode != null) {
            "$mainUrl/api/tv/$tmdbId/$season/$episode?lang=it"
        } else {
            "$mainUrl/api/movie/$tmdbId?lang=it"
        }
        
        Log.d(TAG, "API call: $apiUrl")
        
        try {
            val apiResponse = app.get(apiUrl, referer = mainUrl)
            val srcPattern = Regex(""""src"\s*:\s*"([^"]+)"""")
            val srcMatch = srcPattern.find(apiResponse.text)
            
            if (srcMatch != null) {
                var embedUrl = srcMatch.groupValues[1].replace("\\/", "/")
                if (!embedUrl.startsWith("http")) {
                    embedUrl = "$mainUrl$embedUrl"
                }
                Log.d(TAG, "Embed URL extracted, delegating to loadExtractor")
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                return
            } else {
                Log.w(TAG, "Src not found in API response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "API request failed: ${e.message}")
        }
        
        Log.d(TAG, "Falling back to direct URL")
        loadExtractor(url, mainUrl, subtitleCallback, callback)
    }
}