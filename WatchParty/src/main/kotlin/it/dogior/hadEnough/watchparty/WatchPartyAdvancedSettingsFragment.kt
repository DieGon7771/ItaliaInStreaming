package it.dogior.hadEnough.watchparty

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig

private const val TAG = "WatchParty"

/** Pagina dedicata aperta dalla riga "Impostazioni" del foglio principale. */
class WatchPartyAdvancedSettingsFragment(
    private val plugin: Plugin,
) : BottomSheetDialogFragment() {

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("watchparty_settings_advanced", inflater, container)

        val optionsCard = root.findView<View>("wpa_options_card")
        val invisibleButtonSwitch = root.findView<Switch>("wpa_invisible_button")
        val invisibleChatSwitch = root.findView<Switch>("wpa_invisible_chat")
        val themeCard = root.findView<LinearLayout>("wpa_theme_card")
        val glowCard = root.findView<View>("wpa_glow_card")
        val glowSwitch = root.findView<Switch>("wpa_glow")

        optionsCard.background = getDrawable("outline")
        themeCard.background = getDrawable("outline")
        glowCard.background = getDrawable("outline")

        invisibleButtonSwitch.isChecked = CloudStreamApp.getKey<String>("wp_button_invisible") == "true"
        invisibleButtonSwitch.setOnCheckedChangeListener { _, checked ->
            CloudStreamApp.setKey("wp_button_invisible", if (checked) "true" else "false")
        }

        invisibleChatSwitch.isChecked = CloudStreamApp.getKey<String>("wp_chat_invisible") == "true"
        invisibleChatSwitch.setOnCheckedChangeListener { _, checked ->
            CloudStreamApp.setKey("wp_chat_invisible", if (checked) "true" else "false")
        }

        glow = CloudStreamApp.getKey<String>("wp_chat_glow") == "true"
        glowSwitch.isChecked = glow
        glowSwitch.setOnCheckedChangeListener { _, checked ->
            CloudStreamApp.setKey("wp_chat_glow", if (checked) "true" else "false")
            glow = checked
            previewRefreshers.forEach { it() }
        }

        buildThemeSelector(themeCard)

        root
    } catch (e: Exception) {
        android.util.Log.e(TAG, "💥 ECCEZIONE in WatchPartyAdvancedSettingsFragment", e)
        null
    }

    private class ThemeOption(val index: Int, val name: String, val mine: Int, val peer: Int)

    private fun themes(): List<ThemeOption> = listOf(
        ThemeOption(0, "Classic", 0xFF2E7DFF.toInt(), 0xFF37474F.toInt()),
        ThemeOption(1, "Emerald", 0xFF2AC96B.toInt(), 0xFF22403A.toInt()),
        ThemeOption(2, "Twilight", 0xFF8C6BFF.toInt(), 0xFF38314D.toInt()),
        ThemeOption(3, "Amber", 0xFFFFB74D.toInt(), 0xFF4A3B26.toInt()),
        ThemeOption(4, "Strawberry", 0xFFFF4FA3.toInt(), 0xFF4A2F3F.toInt()),
        ThemeOption(5, "Turquoise", 0xFF00BCD4.toInt(), 0xFF1B3B42.toInt()),
    )

    private var selectedIndex: Int = 0
    private var glow: Boolean = false

    private val rowRefreshers = mutableListOf<() -> Unit>()
    private val previewRefreshers = mutableListOf<() -> Unit>()

    /** Anteprima della bolla: piena col colore del tema oppure glow (nero + bordo). */
    private fun bubbleBackground(color: Int): android.graphics.drawable.GradientDrawable =
        if (glow) {
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF000000.toInt())
                setStroke(dp(2), color)
            }
        } else {
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color)
            }
        }

    /** Costruisce una riga con anteprima di due bolle + nome del tema. */
    private fun themeRow(option: ThemeOption, onSelected: (Int) -> Unit): LinearLayout {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { onSelected(option.index) }
        }

        // miniatura bolle (propria + amico)
        val preview = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fun bubble(color: Int, text: String): TextView = TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            background = bubbleBackground(color)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        val mineBubble = bubble(option.mine, "Hi")
        val peerBubble = bubble(option.peer, "Hey!")
        preview.addView(mineBubble)
        preview.addView(peerBubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })

        // al cambio del glow ridisegna le anteprime
        previewRefreshers += {
            mineBubble.background = bubbleBackground(option.mine)
            peerBubble.background = bubbleBackground(option.peer)
        }

        val name = TextView(ctx).apply {
            this.text = option.name
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val check = TextView(ctx).apply {
            text = "✓"
            textSize = 16f
            setTextColor(0xFF2AC96B.toInt())
            visibility = View.INVISIBLE
        }

        row.addView(preview)
        row.addView(name)
        row.addView(check)

        val refresh = {
            val active = option.index == selectedIndex
            name.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            check.visibility = if (active) View.VISIBLE else View.INVISIBLE
            row.background = if (active) {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0x22FFFFFF.toInt())
                }
            } else null
        }
        refresh()
        rowRefreshers += refresh

        return row
    }

    private fun buildThemeSelector(card: LinearLayout) {
        rowRefreshers.clear()
        previewRefreshers.clear()
        selectedIndex = CloudStreamApp.getKey<String>("wp_chat_theme")?.toIntOrNull() ?: 0
        themes().forEach { option ->
            card.addView(
                themeRow(option) { index ->
                    selectedIndex = index
                    CloudStreamApp.setKey("wp_chat_theme", index.toString())
                    rowRefreshers.forEach { it() }
                }
            )
        }
    }
}