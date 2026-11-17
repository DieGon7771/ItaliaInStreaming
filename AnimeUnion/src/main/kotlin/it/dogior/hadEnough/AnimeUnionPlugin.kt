package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeUnionPlugin : Plugin() {
    companion object {
        const val PREFS = "AnimeUnion"
        const val KEY_LANG_TAGS = "show_lang_tags"
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // attivo di default
        val showTags = sharedPref.getBoolean(KEY_LANG_TAGS, true)

        registerMainAPI(AnimeUnion(showTags))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            AnimeUnionSettings(this, sharedPref).show(activity.supportFragmentManager, "AnimeUnionSettings")
        }
    }
}
