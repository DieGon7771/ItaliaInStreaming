package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class AltaDefinizionePlugin : Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("altadefinizione_prefs", Context.MODE_PRIVATE)
        val version = sharedPref.getString("site_version", "v1") ?: "v1"
        
        // Registra la versione corretta
        when (version) {
            "v2" -> registerMainAPI(AltaDefinizioneV2())
            else -> registerMainAPI(AltaDefinizioneV1())
        }

        // Abilita le impostazioni (ICONA INGRANAGGIO)
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = AltaDefinizioneSettings(this, sharedPref)
            frag.show(activity.supportFragmentManager, "AltadefinizioneSettings")
        }
    }
}
