package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.settings.SettingsFragment

@CloudstreamPlugin
class TorrentioPlugin: Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("Torrentio", Context.MODE_PRIVATE)
        registerMainAPI(Torrentio(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = SettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
