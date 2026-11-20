package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs

data class Editor(
    val editor : SharedPreferences.Editor
) {
    fun<T> setKeyRaw(path: String, value: T) {
        @Suppress("UNCHECKED_CAST")
        if (isStringSet(value)) {
            editor.putStringSet(path, value as Set<String>)
        } else {
            when (value) {
                is Boolean -> editor.putBoolean(path, value)
                is Int -> editor.putInt(path, value)
                is String -> editor.putString(path, value)
                is Float -> editor.putFloat(path, value)
                is Long -> editor.putLong(path, value)
            }
        }
    }

    private fun isStringSet(value: Any?) : Boolean {
        if (value is Set<*>) {
            return value.filterIsInstance<String>().size == value.size
        }
        return false
    }

    fun apply() {
        editor.apply()
        System.gc()
    }
}

object BackupUtils {
    val nonTransferableKeys = listOf(
        "anilist_unixtime",
        "anilist_token",
        "anilist_user",
        "anilist_cached_list",
        "anilist_accounts",
        "anilist_active",
        "mal_user",
        "mal_cached_list",
        "mal_unixtime",
        "mal_refresh_token",
        "mal_token",
        "mal_accounts",
        "mal_active",
        "simkl_token",
        "simkl_user",
        "simkl_cached_list",
        "simkl_cached_time",
        "simkl_accounts",
        "simkl_active",
        "SIMKL_API_CACHE",
        "ANIWAVE_SIMKL_SYNC",
        "open_subtitles_user",
        "opensubtitles_accounts",
        "opensubtitles_active",
        "subdl_user",
        "subdl_accounts",
        "subdl_active",
        "biometric_key",
        "nginx_user",
        "download_path_key",
        "download_path_key_visual",
        "backup_path_key",
        "backup_dir_path_key",
        "PLUGINS_KEY",
        "PLUGINS_KEY_LOCAL",
        "cs3-votes",
        "last_sync_api",
        "last_click_action",
        "last_opened_id",
        "library_folder",
        "result_resume_watching_migrated",
        "jsdelivr_proxy_key",
        "fshare_setup",
        "fshare_token",
        "bluphim_token",
        "device_id",
        "sync_token",
        "sync_provider",
        "gdrive_",
        "sync_project_num",
        "sync_project_id",
        "sync_item_id",
        "sync_device_id",
        "restore_device",
        "backup_device",
        "sync_last_restore_at",
        "auto_pull_enabled",
        "auto_pull_seconds",
        "sync_cat_",
        "download_resume",
        "download_q_resume",
        "prerelease_update",
        "data_store_helper/account_key_index",
        "VERSION_NAME",
        "FILES_TO_DELETE_KEY",
        "HAS_DONE_SETUP",
        "ULTIMA_CURRENT_META_PROVIDERS",
        "ULTIMA_EXTENSIONS_LIST",
        "ULTIMA_CURRENT_MEDIA_PROVIDERS",
        "ULTIMA_WATCH_SYNC_CREDS",
        "used_fstream_providers_v3",
        "fstream_version",
    )

    fun String.isResume(): Boolean {
        return !nonTransferableKeys.any { this.contains(it) }
    }

    fun String.isBackup(resumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null): Boolean {
        var check = !nonTransferableKeys.any { this.contains(it) }
        if (check) {
            if (this.contains("download_header_cache")) {
                val id = this.split("/").getOrNull(1)?.toIntOrNull()
                check = id?.let { intId ->
                    resumeWatching?.any { if (it.parentId != null) it.parentId == intId else it.id == intId } == true
                } ?: false
            } else if (this.contains("video_pos_dur")) {
                val id = this.split("/").getOrNull(2)?.toIntOrNull()
                check = id?.let { intId ->
                    resumeWatching?.any { it.id == intId } == true
                } ?: false
            } else if (this.contains("result_season") || this.contains("result_dub") || this.contains("result_episode")) {
                val id = this.split("/").getOrNull(2)?.toIntOrNull()
                check = id?.let { intId ->
                    resumeWatching?.any { it.parentId == intId } == true
                } ?: false
            }
        }
        
        return check
    }

    const val CAT_SETTINGS = "settings"
    const val CAT_RESUME = "resume"
    const val CAT_LIBRARY = "library"
    const val CAT_SEARCH = "search"
    const val CAT_SUBS = "subtitles"
    const val CAT_DOWNLOADS = "downloads"
    const val CAT_EXT = "extensions"
    val ALL_CATS = listOf(CAT_RESUME, CAT_LIBRARY, CAT_SEARCH, CAT_SUBS, CAT_SETTINGS, CAT_DOWNLOADS, CAT_EXT)

    val EXPERIMENTAL_CATS = setOf(CAT_DOWNLOADS)

    fun catEnabled(cat: String, backup: Boolean): Boolean =
        getKey<Boolean>("sync_cat_${if (backup) "b" else "r"}_$cat")
            ?: getKey<Boolean>("sync_cat_$cat")
            ?: (cat != CAT_DOWNLOADS)

    fun setCat(cat: String, backup: Boolean, v: Boolean) =
        setKey("sync_cat_${if (backup) "b" else "r"}_$cat", v)

    fun enabledCats(backup: Boolean = true): List<String> = ALL_CATS.filter { catEnabled(it, backup) }

    private val resumeMarkers = listOf(
        "video_pos_dur", "resume_watching", "result_season", "result_dub",
        "result_episode", "download_header_cache"
    )

    private val subsMarkers = listOf("subtitle", "subs_", "sub_")

    fun catOf(key: String, isSettings: Boolean): String = when {
        subsMarkers.any { key.contains(it, ignoreCase = true) } -> CAT_SUBS
        isSettings -> CAT_SETTINGS
        key.contains("download_info") || key.contains("download_episode_cache") -> CAT_DOWNLOADS
        key.contains("REPOSITORIES_KEY") -> CAT_EXT
        key.contains("search_history") -> CAT_SEARCH
        resumeMarkers.any { key.contains(it) } -> CAT_RESUME
        else -> CAT_LIBRARY
    }

    private fun filterCats(m: Map<String, Any?>, isSettings: Boolean, cats: Collection<String>) =
        m.filter { catOf(it.key, isSettings) in cats }

    private fun BackupVars.pick(f: (Map<String, Any?>) -> Map<String, Any?>): BackupVars {
        @Suppress("UNCHECKED_CAST")
        return BackupVars(
            bool?.let { f(it) as Map<String, Boolean> },
            int?.let { f(it) as Map<String, Int> },
            string?.let { f(it) as Map<String, String> },
            float?.let { f(it) as Map<String, Float> },
            long?.let { f(it) as Map<String, Long> },
            stringSet?.let { f(it) as Map<String, Set<String>?> },
        )
    }

    private fun BackupVars.plus(o: BackupVars) = BackupVars(
        (bool.orEmpty() + o.bool.orEmpty()),
        (int.orEmpty() + o.int.orEmpty()),
        (string.orEmpty() + o.string.orEmpty()),
        (float.orEmpty() + o.float.orEmpty()),
        (long.orEmpty() + o.long.orEmpty()),
        (stringSet.orEmpty() + o.stringSet.orEmpty()),
    )

    fun carryOver(local: BackupFile, cloud: BackupFile?, cats: Collection<String>): BackupFile {
        if (cloud == null) return local
        val off = ALL_CATS.filter { it !in cats }
        if (off.isEmpty()) return local
        return BackupFile(
            local.datastore.plus(cloud.datastore.pick { filterCats(it, false, off) }),
            local.settings.plus(cloud.settings.pick { filterCats(it, true, off) })
        )
    }

    fun getBackup(context: Context?, resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?): BackupFile? {
        if (context == null) return null
        val cats = enabledCats()

        val allData = context.getSharedPrefs().all.filter {
            it.key.isBackup(resumeWatching) && catOf(it.key, false) in cats
        }
        val allSettings = context.getDefaultSharedPrefs().all.filter {
            it.key.isBackup(resumeWatching) && catOf(it.key, true) in cats
        }

        val allDataSorted = BackupVars(
            allData.filter { it.value is Boolean } as? Map<String, Boolean>,
            allData.filter { it.value is Int } as? Map<String, Int>,
            allData.filter { it.value is String } as? Map<String, String>,
            allData.filter { it.value is Float } as? Map<String, Float>,
            allData.filter { it.value is Long } as? Map<String, Long>,
            allData.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>
        )

        val allSettingsSorted = BackupVars(
            allSettings.filter { it.value is Boolean } as? Map<String, Boolean>,
            allSettings.filter { it.value is Int } as? Map<String, Int>,
            allSettings.filter { it.value is String } as? Map<String, String>,
            allSettings.filter { it.value is Float } as? Map<String, Float>,
            allSettings.filter { it.value is Long } as? Map<String, Long>,
            allSettings.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>
        )

        return BackupFile(
            allDataSorted,
            allSettingsSorted
        )
    }

    fun editor(context : Context, isEditingAppSettings: Boolean = false) : Editor {
        val editor: SharedPreferences.Editor = if (isEditingAppSettings) context.getDefaultSharedPrefs().edit() else context.getSharedPrefs().edit()
        return Editor(editor)
    }

    fun <T> Context.restoreMap(
        map: Map<String, T>?,
        isEditingAppSettings: Boolean = false
    ) {
        val editor2 = editor(this, isEditingAppSettings)
        map?.forEach {
            if (it.key.isResume()) {
                editor2.setKeyRaw(it.key, it.value)
            }
        }
        editor2.apply()
    }

    fun restore(
        context: Context?,
        backupFile: BackupFile,
        restoreSettings: Boolean,
        restoreDataStore: Boolean
    ) {
        if (context == null) return
        val cats = enabledCats(false)
        val backupFile = backupFile.let { b ->
            BackupFile(
                b.datastore.pick { filterCats(it, false, cats) },
                b.settings.pick { filterCats(it, true, cats) }
            )
        }
        if (restoreSettings) {
            context.restoreMap(backupFile.settings.bool, true)
            context.restoreMap(backupFile.settings.int, true)
            context.restoreMap(backupFile.settings.string, true)
            context.restoreMap(backupFile.settings.float, true)
            context.restoreMap(backupFile.settings.long, true)
            context.restoreMap(backupFile.settings.stringSet, true)
        }
        if (restoreDataStore) {
            context.restoreMap(backupFile.datastore.bool)
            context.restoreMap(backupFile.datastore.int)
            context.restoreMap(backupFile.datastore.string)
            context.restoreMap(backupFile.datastore.float)
            context.restoreMap(backupFile.datastore.long)
            context.restoreMap(backupFile.datastore.stringSet)
        }
    }
}
