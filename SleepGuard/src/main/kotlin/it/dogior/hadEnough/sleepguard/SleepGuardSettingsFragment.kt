package it.dogior.hadEnough.sleepguard

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import it.dogior.hadEnough.BuildConfig

class SleepGuardSettingsFragment(private val plugin: SleepGuardPlugin) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("sleepguard_settings", inflater, container)
        val ctx = requireContext()

        val timerCard = root.findView<LinearLayout>("sg_timer_card")
        val timerValue = root.findView<TextView>("sg_timer_value")
        val timerSeekHost = root.findView<FrameLayout>("sg_timer_seek_host")

        val episodeCard = root.findView<LinearLayout>("sg_episode_card")
        val episodeToggleHost = root.findView<FrameLayout>("sg_episode_toggle_host")
        val episodeValue = root.findView<TextView>("sg_episode_value")
        val episodeSeekHost = root.findView<FrameLayout>("sg_episode_seek_host")

        val volumeCard = root.findView<LinearLayout>("sg_volume_card")
        val volumeToggleHost = root.findView<FrameLayout>("sg_volume_toggle_host")

        val closeBtn = root.findView<TextView>("sg_close")

        timerCard.background = cardBackground()
        episodeCard.background = cardBackground()
        volumeCard.background = cardBackground()
        closeBtn.background = getDrawable("sleepguard_btn_danger") ?: coloredFallback(0x14FF6B6B, radiusDp = 8f, strokeColor = 0x99FF7F7F.toInt(), strokeDp = 2f)

        val minMin = SleepGuardPrefs.MIN_INACTIVITY_MINUTES
        val maxMin = SleepGuardPrefs.MAX_INACTIVITY_MINUTES
        val minuteSteps = (minMin..maxMin step 5).toList()
        val currentMin = SleepGuardPrefs.inactivityMinutes(ctx)
        timerValue.text = "$currentMin min"
        mountSlider(timerSeekHost, minuteSteps, currentMin) { minutes ->
            timerValue.text = "$minutes min"
            SleepGuardPrefs.setInactivityMinutes(ctx, minutes)
        }

        val minEp = SleepGuardPrefs.MIN_EPISODE_THRESHOLD
        val maxEp = SleepGuardPrefs.MAX_EPISODE_THRESHOLD
        val episodeSteps = (minEp..maxEp).toList()
        val currentEp = SleepGuardPrefs.episodeThreshold(ctx)
        episodeValue.text = "$currentEp episodes"
        var episodeEnabled = SleepGuardPrefs.isEpisodeCounterEnabled(ctx)
        fun refreshEpisodeRowEnabled() {
            episodeSeekHost.isEnabled = episodeEnabled
            episodeSeekHost.alpha = if (episodeEnabled) 1f else 0.4f
            episodeValue.alpha = if (episodeEnabled) 1f else 0.4f
        }
        refreshEpisodeRowEnabled()
        mountToggle(episodeToggleHost, episodeEnabled) { checked ->
            episodeEnabled = checked
            SleepGuardPrefs.setEpisodeCounterEnabled(ctx, checked)
            refreshEpisodeRowEnabled()
        }
        mountSlider(episodeSeekHost, episodeSteps, currentEp) { count ->
            episodeValue.text = "$count episodes"
            SleepGuardPrefs.setEpisodeThreshold(ctx, count)
        }

        mountToggle(volumeToggleHost, SleepGuardPrefs.isVolumeKeyResetEnabled(ctx)) { checked ->
            SleepGuardPrefs.setVolumeKeyResetEnabled(ctx, checked)
        }

        closeBtn.setOnClickListener { dismiss() }

        root
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Exception in SleepGuard settings onCreateView()", t)
        null
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun <T : View> View.findView(name: String): T {
        val res = plugin.resources ?: resources
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        @Suppress("UNCHECKED_CAST")
        return findViewById<View>(id) as T
    }

    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull()
    }

    private fun coloredFallback(fill: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 1f): Drawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * density
            strokeColor?.let { setStroke((strokeDp * density).toInt(), it) }
        }
    }

    private fun cardBackground(): Drawable =
        getDrawable("sleepguard_card_bg") ?: coloredFallback(0x14FFFFFF, radiusDp = 16f, strokeColor = 0x1FFFFFFF)

    companion object {
        private const val TAG = "SleepGuardSettings"
    }
}
