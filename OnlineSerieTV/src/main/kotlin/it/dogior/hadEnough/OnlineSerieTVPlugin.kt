package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import java.io.File

@CloudstreamPlugin
class OnlineSerieTVPlugin: Plugin() {
    override fun load(context: Context) {
        OnlineSerieTVCache.setDiskDirectory(
            File(context.cacheDir, "onlineserietv_cache")
        )
        registerMainAPI(OnlineSerieTV())
    }
}