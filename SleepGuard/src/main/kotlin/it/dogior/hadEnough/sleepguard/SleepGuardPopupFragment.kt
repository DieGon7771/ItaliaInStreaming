package it.dogior.hadEnough.sleepguard

import android.app.Dialog
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.StateSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.DialogFragment
import it.dogior.hadEnough.BuildConfig

class SleepGuardPopupFragment(
    private val plugin: SleepGuardPlugin,
    private val onYes: () -> Unit,
    private val onNo: () -> Unit,
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val dialog = Dialog(requireContext())
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("sleepguard_popup", inflater, container)

        val card = root.findView<ViewGroup>("sg_popup_card")
        val yesBtn = root.findView<TextView>("sg_popup_yes")
        val noBtn = root.findView<TextView>("sg_popup_no")

        card.background = cardBackground()
        yesBtn.background = focusableBackground(normal = 0xFF3B65F5.toInt(), focused = 0xFF5B82FF.toInt(), radiusDp = 14f)
        noBtn.background = focusableBackground(normal = 0x12FFFFFF, focused = 0x24FFFFFF, radiusDp = 14f, strokeColor = 0x33FFFFFF)

        yesBtn.setOnClickListener {
            runCatching { dismissAllowingStateLoss() }
            onYes()
        }
        noBtn.setOnClickListener {
            runCatching { dismissAllowingStateLoss() }
            onNo()
        }

        yesBtn.post { runCatching { yesBtn.requestFocus() } }

        root
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Exception in SleepGuard popup onCreateView()", t)
        null
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(0xCC000000.toInt()) })
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

    private fun coloredFallback(fill: Int, radiusDp: Float, strokeColor: Int? = null): Drawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * density
            strokeColor?.let { setStroke((1 * density).toInt(), it) }
        }
    }

    private fun focusableBackground(normal: Int, focused: Int, radiusDp: Float, strokeColor: Int? = null): Drawable {
        val density = resources.displayMetrics.density
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
            strokeColor?.let { setStroke((1 * density).toInt(), it) }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(focused))
            addState(intArrayOf(android.R.attr.state_pressed), shape(focused))
            addState(StateSet.WILD_CARD, shape(normal))
        }
    }

    private fun cardBackground(): Drawable =
        getDrawable("sleepguard_card_bg") ?: coloredFallback(0xFF15161F.toInt(), radiusDp = 20f, strokeColor = 0x33FFFFFF)

    companion object {
        private const val TAG = "SleepGuardPopup"
    }
}
