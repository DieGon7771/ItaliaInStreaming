package it.dogior.hadEnough.sleepguard

import android.content.Context
import android.content.SharedPreferences

object SleepGuardPrefs {
    private const val FILE = "sleepguard_prefs"

    private const val KEY_INACTIVITY_MINUTES = "inactivity_minutes"
    private const val KEY_EPISODE_THRESHOLD = "episode_threshold"
    private const val KEY_EPISODE_COUNTER_ENABLED = "episode_counter_enabled"
    private const val KEY_VOLUME_KEY_RESET_ENABLED = "volume_key_reset_enabled"

    const val DEFAULT_INACTIVITY_MINUTES = 75
    const val MIN_INACTIVITY_MINUTES = 30
    const val MAX_INACTIVITY_MINUTES = 120

    const val DEFAULT_EPISODE_THRESHOLD = 3
    const val MIN_EPISODE_THRESHOLD = 1
    const val MAX_EPISODE_THRESHOLD = 5

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun inactivityMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INACTIVITY_MINUTES, DEFAULT_INACTIVITY_MINUTES)
            .coerceIn(MIN_INACTIVITY_MINUTES, MAX_INACTIVITY_MINUTES)

    fun setInactivityMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INACTIVITY_MINUTES, minutes).apply()
    }

    fun episodeThreshold(context: Context): Int =
        prefs(context).getInt(KEY_EPISODE_THRESHOLD, DEFAULT_EPISODE_THRESHOLD)

    fun setEpisodeThreshold(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_EPISODE_THRESHOLD, count).apply()
    }

    fun isEpisodeCounterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EPISODE_COUNTER_ENABLED, true)

    fun setEpisodeCounterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EPISODE_COUNTER_ENABLED, enabled).apply()
    }

    fun isVolumeKeyResetEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOLUME_KEY_RESET_ENABLED, true)

    fun setVolumeKeyResetEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOLUME_KEY_RESET_ENABLED, enabled).apply()
    }
}
