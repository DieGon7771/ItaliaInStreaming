package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager

@CloudstreamPlugin
class UltimaPlugin : Plugin() {
    var activity: AppCompatActivity? = null
    
    override fun load(context: Context) {
        activity = context as AppCompatActivity
        
        // Registra solo l'API principale Ultima
        registerMainAPI(Ultima(this))

        // NOTA: Ho rimosso completamente il blocco dei meta provider
        // perché hai detto di aver eliminato quei file
        
        openSettings = { ctx ->
            val act = ctx as? AppCompatActivity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                val frag = UltimaSettings(this)
                frag.show(act.supportFragmentManager, "UltimaSettingsDialog")
            } else {
                Log.e("Plugin", "Activity is not valid anymore, cannot show settings dialog")
            }
        }
    }

    fun reload() {
        val pluginData = PluginManager.getPluginsOnline().find { it.internalName.contains("Ultima") }
        if (pluginData == null) {
            afterPluginsLoadedEvent.invoke(true)
        }
    }
}
