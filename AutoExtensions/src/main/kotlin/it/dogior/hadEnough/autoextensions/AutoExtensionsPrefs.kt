package it.dogior.hadEnough.autoextensions

import android.content.Context
import android.content.SharedPreferences

object AutoExtensionsPrefs {
    private const val FILE = "autoextensions_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_INCLUDE_NSFW = "include_nsfw"
    private const val KEY_LAST_RUN_AT = "last_run_at"
    private const val KEY_LAST_RUN_SUMMARY = "last_run_summary"

    const val DEFAULT_ENABLED = false
    const val DEFAULT_INCLUDE_NSFW = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isNsfwIncluded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCLUDE_NSFW, DEFAULT_INCLUDE_NSFW)

    fun setNsfwIncluded(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_NSFW, enabled).apply()
    }

    fun lastRunAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_RUN_AT, 0L)

    fun lastRunSummary(context: Context): String? =
        prefs(context).getString(KEY_LAST_RUN_SUMMARY, null)

    fun setLastRun(context: Context, atMillis: Long, summary: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_RUN_AT, atMillis)
            .putString(KEY_LAST_RUN_SUMMARY, summary)
            .apply()
    }
}
