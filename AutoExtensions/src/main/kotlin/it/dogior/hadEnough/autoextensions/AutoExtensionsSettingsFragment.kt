package it.dogior.hadEnough.autoextensions

import android.content.Context
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
import java.text.DateFormat
import java.util.Date

class AutoExtensionsSettingsFragment(private val plugin: AutoExtensionsPlugin) : BottomSheetDialogFragment() {

    private var statusText: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("autoextensions_settings", inflater, container)
        val ctx = requireContext()

        val enabledCard = root.findView<LinearLayout>("ae_enabled_card")
        val enabledToggleHost = root.findView<FrameLayout>("ae_enabled_toggle_host")
        val nsfwCard = root.findView<LinearLayout>("ae_nsfw_card")
        val nsfwToggleHost = root.findView<FrameLayout>("ae_nsfw_toggle_host")
        val statusCard = root.findView<LinearLayout>("ae_status_card")
        val statusTextView = root.findView<TextView>("ae_status_text")
        val checkNowBtn = root.findView<TextView>("ae_check_now")
        val closeBtn = root.findView<TextView>("ae_close")

        enabledCard.background = cardBackground()
        nsfwCard.background = cardBackground()
        statusCard.background = cardBackground()
        checkNowBtn.background = buttonBackground("autoextensions_btn_green", 0x142AC96B, 0x997CFF9D.toInt())
        closeBtn.background = buttonBackground("autoextensions_btn_danger", 0x14FF6B6B, 0x99FF7F7F.toInt())

        statusText = statusTextView
        statusTextView.text = buildStatusText(ctx)

        mountToggle(enabledToggleHost, AutoExtensionsPrefs.isEnabled(ctx)) { checked ->
            AutoExtensionsPrefs.setEnabled(ctx, checked)
        }
        mountToggle(nsfwToggleHost, AutoExtensionsPrefs.isNsfwIncluded(ctx)) { checked ->
            AutoExtensionsPrefs.setNsfwIncluded(ctx, checked)
        }

        checkNowBtn.setOnClickListener {
            statusTextView.text = "Checking…"
            AutoExtensionsWorker.runCheckNow()
        }
        closeBtn.setOnClickListener { dismiss() }

        root
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Exception in AutoExtensions settings onCreateView()", t)
        null
    }

    fun onCheckFinished() {
        val ctx = context ?: return
        statusText?.text = buildStatusText(ctx)
    }

    private fun buildStatusText(ctx: Context): String {
        val summary = AutoExtensionsPrefs.lastRunSummary(ctx) ?: return "No check has run yet."
        val at = AutoExtensionsPrefs.lastRunAt(ctx)
        val whenText = if (at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)) else "?"
        return "Last check ($whenText): $summary"
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

    private fun coloredFallback(fill: Int, radiusDp: Float, strokeColor: Int, strokeDp: Float): Drawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * density
            setStroke((strokeDp * density).toInt(), strokeColor)
        }
    }

    private fun buttonBackground(name: String, fallbackFill: Int, fallbackStroke: Int): Drawable =
        getDrawable(name) ?: coloredFallback(fallbackFill, 8f, fallbackStroke, 2f)

    private fun cardBackground(): Drawable =
        getDrawable("autoextensions_card_bg") ?: coloredFallback(0x14FFFFFF, 16f, 0x1FFFFFFF, 1f)

    companion object {
        private const val TAG = "AutoExtensionsSettings"
    }
}
