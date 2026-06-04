package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import org.json.JSONArray
import org.json.JSONObject

@CloudstreamPlugin
class StremioXPlugin : Plugin() {
    private val PREF_FILE = "StremioX"
    private val PREF_KEY_SECTIONS = "stremio_sections"
    private val PREF_KEY_OLD = "stremio_saved_links"

    private fun str(name: String, default: String, vararg args: Any?): String {
        val pkg = BuildConfig.LIBRARY_PACKAGE_NAME
        val res = resources
        val id = res?.getIdentifier(name, "string", pkg) ?: 0
        val template = if (id != 0) res!!.getString(id) else default
        return if (args.isNotEmpty()) String.format(template, *args) else template
    }

    override fun load(context: Context) {
        migrateOldFormat(context)
        // Load localized labels into SectionProvider
        SectionProvider.labelTrending = str("tmdb_trending", "Trending")
        SectionProvider.labelPopularMovies = str("tmdb_popular_movies", "Film Popolari")
        SectionProvider.labelPopularTv = str("tmdb_popular_tv", "Serie TV Popolari")
        SectionProvider.upcomingTag = str("upcoming_tag", " [UPCOMING]")
        reload(context)
        val activity = context as? AppCompatActivity
        openSettings = {
            val frag = SettingsSectionsFragment(
                this,
                context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            )
            activity?.supportFragmentManager?.let { fm -> frag.show(fm, "Settings") }
        }
    }

    private fun migrateOldFormat(context: Context) {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val oldJson = prefs.getString(PREF_KEY_OLD, null) ?: return
        val newJson = prefs.getString(PREF_KEY_SECTIONS, null)
        if (newJson != null) return

        val oldArr = try { JSONArray(oldJson) } catch (_: Exception) { return }
        val migrated = mutableListOf<SectionConfig>()

        for (i in 0 until oldArr.length()) {
            val obj = oldArr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val link = obj.optString("link", "")
            val type = obj.optString("type", "StremioX")
            if (link.isBlank()) continue

            val sectionName = name.ifBlank { str("migration_section_name", "Sezione %d", migrated.size + 1) }
            migrated.add(
                SectionConfig(
                    id = System.currentTimeMillis() + i,
                    name = sectionName,
                    catalogUrl = if (type == "StremioC") link else null,
                    streamAddons = if (type == "StremioX") {
                        listOf(StreamAddonConfig(
                            id = System.currentTimeMillis() + i,
                            name = name.ifBlank { str("migration_addon_name", "Stream") },
                            url = link,
                            type = "https"
                        ))
                    } else emptyList()
                )
            )
        }

        if (migrated.isNotEmpty()) {
            saveSections(prefs, migrated)
            prefs.edit().remove(PREF_KEY_OLD).apply()
            Log.d("StremioXPlugin", "Migrated ${migrated.size} old links to sections")
        }
    }

    private fun saveSections(prefs: android.content.SharedPreferences, list: List<SectionConfig>) {
        val arr = JSONArray()
        for (s in list) {
            val addonsArr = JSONArray()
            for (a in s.streamAddons) {
                addonsArr.put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("url", a.url)
                    put("type", a.type)
                })
            }
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("catalogUrl", s.catalogUrl ?: "")
                put("streamAddons", addonsArr)
            })
        }
        prefs.edit().putString(PREF_KEY_SECTIONS, arr.toString()).apply()
    }

    fun reload(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val sections = loadSections(prefs)

            for (section in sections) {
                val mainUrl = section.catalogUrl ?: ""
                val provider = SectionProvider(
                    mainUrl = mainUrl,
                    name = section.name,
                    config = section
                )
                try {
                    registerMainAPI(provider)
                } catch (e: Throwable) {
                    Log.w("StremioXPlugin", "Failed to register section '${section.name}': ${e.message}")
                }
            }

            try {
                MainActivity.afterPluginsLoadedEvent.invoke(true)
            } catch (e: Throwable) {
                Log.w("StremioXPlugin", "afterPluginsLoaded invoke failed ${e.message}")
            }
        } catch (e: Throwable) {
            Log.e("StremioXPlugin", "reload error ${e.message}")
        }
    }

    private fun loadSections(prefs: android.content.SharedPreferences): List<SectionConfig> {
        val json = prefs.getString(PREF_KEY_SECTIONS, null) ?: return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val list = mutableListOf<SectionConfig>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val addonsArr = obj.optJSONArray("streamAddons")
            val addons = mutableListOf<StreamAddonConfig>()
            if (addonsArr != null) {
                for (j in 0 until addonsArr.length()) {
                    val ao = addonsArr.optJSONObject(j) ?: continue
                    addons.add(StreamAddonConfig(
                        id = ao.optLong("id", System.currentTimeMillis()),
                        name = ao.optString("name", ""),
                        url = ao.optString("url", ""),
                        type = ao.optString("type", "https")
                    ))
                }
            }
            list.add(SectionConfig(
                id = obj.optLong("id", System.currentTimeMillis()),
                name = obj.optString("name", ""),
                catalogUrl = obj.optString("catalogUrl", "").ifEmpty { null },
                streamAddons = addons
            ))
        }
        return list
    }
}
