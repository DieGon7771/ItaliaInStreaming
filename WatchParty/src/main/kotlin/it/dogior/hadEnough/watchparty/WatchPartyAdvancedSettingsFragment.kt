package it.dogior.hadEnough.watchparty

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig
import kotlin.math.hypot

private const val TAG = "WatchParty"

/** Pagina dedicata aperta dalla riga "Impostazioni" del foglio principale.
 *
 *  [parentSettingsFragment] è passato solo per poter attenuare (dim) anche
 *  IL FOGLIO SOTTOSTANTE (WatchPartySettingsFragment) durante l'editor con
 *  touchpad della posizione icona chat: sono due BottomSheetDialogFragment
 *  distinti, quindi due Window separate, ed entrambe devono farsi
 *  semi-trasparenti insieme per vedere l'icona vera sotto. */
class WatchPartyAdvancedSettingsFragment(
    private val plugin: Plugin,
    private val parentSettingsFragment: WatchPartySettingsFragment? = null,
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

        // larghezza pannello chat in % (default 42, clamp 20-85). Salvata su
        // invio della tastiera o quando il campo perde il focus: mentre si
        // digita non forziamo nulla, così "5" non diventa "50" da sola.
        val chatWidthInput = root.findView<EditText>("wpa_chat_width")
        chatWidthInput.background = getDrawable("outline")
        val savedWidth = CloudStreamApp.getKey<String>("wp_chat_width")?.toIntOrNull()?.coerceIn(20, 85) ?: 42
        chatWidthInput.setText(savedWidth.toString())

        fun saveChatWidth() {
            val v = chatWidthInput.text?.toString()?.toIntOrNull()?.coerceIn(20, 85) ?: 42
            CloudStreamApp.setKey("wp_chat_width", v.toString())
            chatWidthInput.setText(v.toString())
        }
        chatWidthInput.setOnEditorActionListener { _, _, _ ->
            saveChatWidth()
            // false = lascia che sia il sistema a fare l'azione di default per
            // "Fine"/spunta, che include la chiusura della tastiera (esattamente
            // come il campo PIN, che non ha nemmeno bisogno di un listener suo).
            // Con "true" qui, la tastiera restava aperta perché dicevamo ad
            // Android "ho già gestito tutto io".
            false
        }
        chatWidthInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveChatWidth() }

        glow = CloudStreamApp.getKey<String>("wp_chat_glow") == "true"
        glowSwitch.isChecked = glow
        glowSwitch.setOnCheckedChangeListener { _, checked ->
            CloudStreamApp.setKey("wp_chat_glow", if (checked) "true" else "false")
            glow = checked
            previewRefreshers.forEach { it() }
        }

        buildThemeSelector(themeCard)

        // --- Posizione icona chat: touchpad relativo ---
        val positionCard = root.findView<View>("wpa_position_card")
        val positionPad = root.findView<FrameLayout>("wpa_position_pad")
        val positionHint = root.findView<TextView>("wpa_position_hint")
        val positionStatus = root.findView<TextView>("wpa_position_status")
        val positionReset = root.findView<TextView>("wpa_position_reset")

        positionCard.background = getDrawable("outline")
        positionPad.background = getDrawable("outline_blue")
        positionReset.background = getDrawable("outline")

        fun refreshPositionStatus() {
            positionStatus.text = if (WatchPartyOverlay.savedPositionPercent() != null)
                "Custom position saved" else "Using the default position"
        }
        refreshPositionStatus()

        positionReset.setOnClickListener {
            WatchPartyOverlay.resetPositionToDefault()
            refreshPositionStatus()
            showToast("Position reset to default")
        }

        setupPositionPad(positionPad, positionHint) { refreshPositionStatus() }

        root
    } catch (e: Exception) {
        android.util.Log.e(TAG, "💥 ECCEZIONE in WatchPartyAdvancedSettingsFragment", e)
        null
    }

    // -----------------------------------------------------------------
    // Touchpad relativo per la posizione dell'icona chat
    // -----------------------------------------------------------------

    private val positionHandler = Handler(Looper.getMainLooper())
    private var positionPreviewHost: FrameLayout? = null
    private var positionMoveActive = false

    /** Attenua/ripristina insieme sia questo foglio (Impostazioni avanzate)
     *  sia quello sottostante (Watch Party), animando l'alpha dell'intera
     *  Window di ciascun Dialog: così sparisce anche lo scrim scuro di
     *  entrambi i BottomSheetDialog e si vede l'icona vera sotto. Il
     *  touchpad resta comunque cliccabile: l'alpha non disabilita il touch. */
    private fun setSheetsDimmed(dimmed: Boolean) {
        val target = if (dimmed) 0.14f else 1f
        dialog?.window?.decorView?.animate()?.alpha(target)?.setDuration(180)?.start()
        parentSettingsFragment?.dialog?.window?.decorView?.animate()?.alpha(target)?.setDuration(180)?.start()
    }

    /** Crea un'icona "gemella" di quella vera (stesso drawable, stessa
     *  dimensione, stesso sfondo circolare) sopra il decorView
     *  dell'Activity, nella posizione attualmente salvata (o quella di
     *  default). Non riusiamo direttamente WatchPartyOverlay.chatArrowHost
     *  perché quello esiste solo a stanza attiva: l'editor deve funzionare
     *  anche fuori da una stanza, quindi lavoriamo su una copia visiva
     *  identica e scriviamo la posizione finale al rilascio. */
    private fun showPositionPreview(): FrameLayout? {
        val activity = CommonActivity.activity ?: return null
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val density = activity.resources.displayMetrics.density
        val size = (WatchPartyOverlay.CHAT_ICON_SIZE_DP * density).toInt()
        val decorW = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val decorH = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val (xPercent, yPercent) = WatchPartyOverlay.savedPositionPercent()
            ?: WatchPartyOverlay.defaultPositionPercent(decorW, decorH, density)

        val host = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
        }
        val icon = ImageView(activity).apply {
            setImageDrawable(getDrawable("chat_bubble") ?: getDrawable("watchparty_icon"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(12), dp(9))
        }
        host.addView(icon, FrameLayout.LayoutParams(size, size))

        val cx = decorW * xPercent / 100f
        val cy = decorH * yPercent / 100f
        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            marginStart = (cx - size / 2f).toInt().coerceIn(0, (decorW - size).coerceAtLeast(0))
            topMargin = (cy - size / 2f).toInt().coerceIn(0, (decorH - size).coerceAtLeast(0))
        }
        host.tag = floatArrayOf(cx, cy) // centro corrente in px, per la matematica del drag
        return runCatching { decor.addView(host, params) }.map { host }.getOrNull()
    }

    /** Applica uno spostamento RELATIVO (delta) al centro dell'icona anteprima,
     *  clampato dentro lo schermo. Questo è il cuore del comportamento "trackpad":
     *  non conta la posizione assoluta del dito, solo quanto si è mosso. */
    private fun movePositionPreviewBy(dx: Float, dy: Float) {
        val host = positionPreviewHost ?: return
        val activity = CommonActivity.activity ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val params = host.layoutParams as? FrameLayout.LayoutParams ?: return
        val tag = host.tag as? FloatArray ?: floatArrayOf(0f, 0f)
        val size = params.width
        val decorW = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val decorH = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels

        val halfSize = size / 2f
        val cx = (tag[0] + dx).coerceIn(halfSize, (decorW - halfSize).coerceAtLeast(halfSize))
        val cy = (tag[1] + dy).coerceIn(halfSize, (decorH - halfSize).coerceAtLeast(halfSize))
        host.tag = floatArrayOf(cx, cy)

        params.marginStart = (cx - halfSize).toInt()
        params.topMargin = (cy - halfSize).toInt()
        host.layoutParams = params
    }

    /** Salva la posizione finale (in percentuale, come richiesto — non pixel
     *  fissi, per portabilità tra dispositivi) e rimuove l'anteprima. */
    private fun savePositionPreviewAndRemove() {
        val host = positionPreviewHost ?: return
        val activity = CommonActivity.activity
        val decor = activity?.window?.decorView as? ViewGroup
        val tag = host.tag as? FloatArray
        if (activity != null && tag != null) {
            val decorW = decor?.width?.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val decorH = decor?.height?.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val xPercent = (tag[0] / decorW * 100f).coerceIn(0f, 100f)
            val yPercent = (tag[1] / decorH * 100f).coerceIn(0f, 100f)
            WatchPartyOverlay.savePositionPercent(xPercent, yPercent)
        }
        (host.parent as? ViewGroup)?.removeView(host)
        positionPreviewHost = null
    }

    private fun discardPositionPreview() {
        positionPreviewHost?.let { (it.parent as? ViewGroup)?.removeView(it) }
        positionPreviewHost = null
    }

    /** Sensibilità del movimento relativo: quanto si sposta l'icona vera per
     *  ogni pixel di dito sul touchpad. >1 perché il rettangolo è piccolo e
     *  lo schermo è grande — valore di taratura, non un vincolo tecnico. */
    private val positionSensitivity = 2.4f

    @Suppress("ClickableViewAccessibility")
    private fun setupPositionPad(pad: View, hint: TextView, onCommitted: () -> Unit) {
        val touchSlopPx = dp(16)
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f

        val activateRunnable = Runnable {
            positionMoveActive = true
            pad.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            hint.visibility = View.INVISIBLE
            setSheetsDimmed(true)
            positionPreviewHost = showPositionPreview()
        }

        pad.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    positionMoveActive = false
                    downX = event.rawX; downY = event.rawY
                    lastX = event.rawX; lastY = event.rawY
                    positionHandler.postDelayed(activateRunnable, 2000L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!positionMoveActive) {
                        // movimento prima dei 2s = l'utente vuole scrollare, non
                        // attivare la modalità sposta: annulliamo il timer
                        val moved = hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble())
                        if (moved > touchSlopPx) positionHandler.removeCallbacks(activateRunnable)
                        return@setOnTouchListener true
                    }
                    val dx = (event.rawX - lastX) * positionSensitivity
                    val dy = (event.rawY - lastY) * positionSensitivity
                    lastX = event.rawX; lastY = event.rawY
                    movePositionPreviewBy(dx, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    positionHandler.removeCallbacks(activateRunnable)
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (positionMoveActive) {
                        positionMoveActive = false
                        hint.visibility = View.VISIBLE
                        setSheetsDimmed(false)
                        savePositionPreviewAndRemove()
                        onCommitted()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroyView() {
        // rete di sicurezza: se il fragment viene distrutto a metà di un drag
        // (es. utente esce con back/gesture di sistema), non lasciamo le
        // window a metà trasparenza né l'anteprima orfana sullo schermo.
        positionHandler.removeCallbacksAndMessages(null)
        if (positionMoveActive) {
            positionMoveActive = false
            setSheetsDimmed(false)
            discardPositionPreview()
        }
        super.onDestroyView()
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