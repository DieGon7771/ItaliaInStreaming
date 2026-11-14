package it.dogior.hadEnough

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NuvioPlugin : Plugin() {
    override fun load(context: Context) {
        val manager = NuvioRepoManager(context)
        registerMainAPI(NuvioProvider(manager))

        openSettings = { ctx ->
            NuvioSettings(ctx, manager, resources).show()
        }
    }
}
