package it.dogior.hadEnough.watchparty

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig

/**
 * Aggiunge un piccolo FAB sopra il decorView dell'activity, visibile solo
 * mentre la schermata del player è aperta. Non tocca il layout XML del
 * player (che è interno all'app): si limita ad appoggiarsi sopra, come
 * farebbe una libreria di overlay/tutorial.
 *
 * Il controllo "sono nella schermata player?" avviene via polling
 * (PlayerAccess.isPlayerScreenActive) perché non esiste un evento pubblico
 * per l'apertura/chiusura del player. Lo stesso polling rileva anche
 * quando l'utente ESCE dal player con una stanza attiva, per chiuderla.
 *
 * Quando una stanza è attiva mostra anche una freccia a sinistra (centro
 * verticale) che apre un pannello di chat laterale fino a ~metà schermo.
 * Un pallino rosso sulla freccia avvisa di messaggi non letti.
 */
class WatchPartyOverlay(
    private val plugin: Plugin,
    private val manager: WatchPartyManager,
    private val onClick: () -> Unit,
) {

    companion object {
        private const val KEY_POS_X = "wp_chat_icon_pos_x" // percent (0-100), centro icona, X
        private const val KEY_POS_Y = "wp_chat_icon_pos_y" // percent (0-100), centro icona, Y
        const val CHAT_ICON_SIZE_DP = 40

        /** Posizione salvata (percentuale del centro dell'icona sullo schermo).
         *  null = nessuna posizione custom, si usa quella di default (bordo
         *  sinistro, centro verticale — comportamento storico del plugin). */
        fun savedPositionPercent(): Pair<Float, Float>? {
            val x = CloudStreamApp.getKey<String>(KEY_POS_X)?.toFloatOrNull()
            val y = CloudStreamApp.getKey<String>(KEY_POS_Y)?.toFloatOrNull()
            return if (x != null && y != null) x to y else null
        }

        fun savePositionPercent(xPercent: Float, yPercent: Float) {
            CloudStreamApp.setKey(KEY_POS_X, xPercent.coerceIn(0f, 100f).toString())
            CloudStreamApp.setKey(KEY_POS_Y, yPercent.coerceIn(0f, 100f).toString())
        }

        fun resetPositionToDefault() {
            CloudStreamApp.setKey(KEY_POS_X, "")
            CloudStreamApp.setKey(KEY_POS_Y, "")
        }

        /** Equivalente in percentuale della vecchia posizione fissa (bordo
         *  sinistro + 8dp, centro verticale): usato sia come fallback quando
         *  non c'è una posizione custom, sia come punto di partenza
         *  dell'editor con il touchpad. */
        fun defaultPositionPercent(decorWidthPx: Int, decorHeightPx: Int, density: Float): Pair<Float, Float> {
            val marginStartPx = 8 * density
            val iconSizePx = CHAT_ICON_SIZE_DP * density
            val cx = marginStartPx + iconSizePx / 2f
            val xPercent = if (decorWidthPx > 0) (cx / decorWidthPx * 100f) else 5f
            return xPercent.coerceIn(0f, 100f) to 50f
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var fab: FloatingActionButton? = null
    private var spinner: ProgressBar? = null
    private var attachedActivity: Activity? = null
    private var running = false
    private var wasPlayerActive = false

    // --- chat ---
    private var chatArrowHost: FrameLayout? = null
    private var chatArrowImage: ImageView? = null
    private var chatUnreadDot: TextView? = null
    // contatore non letti: mostrato come numero dentro il pallino rosso
    private var chatUnreadCount = 0
    private var chatRoot: FrameLayout? = null
    private var chatPanel: LinearLayout? = null
    private var chatScrollView: ScrollView? = null
    private var chatMessages: LinearLayout? = null
    private var chatInput: EditText? = null
    private var chatSendIcon: ImageView? = null
    private var chatPanelOpen = false

    // bolle create, per poter ricolorare TUTTI i messaggi quando cambia il tema
    private val bubbleRefs = mutableListOf<Pair<TextView, Boolean>>()
    // ultimo mittente della bolla precedente, per raggruppare i messaggi consecutivi
    private var lastGroupSender: String? = null
    // cache dei pref per applicare i cambiamenti anche a chat già costruita
    private var lastThemeIndex = -1
    private var lastGlow: Boolean? = null
    private var lastChatInvisible: Boolean? = null
    private var lastChatWidth = -1
    private var lastPosKey: String? = null

    /** Calcola i LayoutParams del "pomello" chat in base alla posizione
     *  salvata (percentuale del centro), o quella di default se non c'è
     *  nulla di custom. Ancorato TOP|START: i margini sono le coordinate
     *  effettive dell'angolo in alto a sinistra dell'icona. */
    private fun buildHostParams(activity: Activity, size: Int): FrameLayout.LayoutParams {
        val decorView = activity.window?.decorView
        val decorW = decorView?.width?.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val decorH = decorView?.height?.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val (xPercent, yPercent) = savedPositionPercent()
            ?: defaultPositionPercent(decorW, decorH, activity.resources.displayMetrics.density)
        val cx = decorW * xPercent / 100f
        val cy = decorH * yPercent / 100f
        val left = (cx - size / 2f).coerceIn(0f, (decorW - size).coerceAtLeast(0).toFloat())
        val top = (cy - size / 2f).coerceIn(0f, (decorH - size).coerceAtLeast(0).toFloat())
        return FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = left.toInt()
            topMargin = top.toInt()
        }
    }

    private class ChatTheme(val mineBubble: Int, val peerBubble: Int, val accent: Int)

    private fun chatTheme(): ChatTheme {
        val index = CloudStreamApp.getKey<String>("wp_chat_theme")?.toIntOrNull() ?: 0
        return when (index) {
            1 -> ChatTheme(0xFF2AC96B.toInt(), 0xFF22403A.toInt(), 0xFF2AC96B.toInt())   // Smeraldo
            2 -> ChatTheme(0xFF8C6BFF.toInt(), 0xFF38314D.toInt(), 0xFF8C6BFF.toInt())   // Crepuscolo
            3 -> ChatTheme(0xFFFFB74D.toInt(), 0xFF4A3B26.toInt(), 0xFFFFB74D.toInt())   // Ambra
            4 -> ChatTheme(0xFFFF4FA3.toInt(), 0xFF4A2F3F.toInt(), 0xFFFF4FA3.toInt())   // Fragola
            5 -> ChatTheme(0xFF00BCD4.toInt(), 0xFF1B3B42.toInt(), 0xFF00BCD4.toInt())   // Turchese
            else -> ChatTheme(0xFF2E7DFF.toInt(), 0xFF37474F.toInt(), 0xFF2E7DFF.toInt()) // Classico
        }
    }

    private fun isGlow(): Boolean =
        CloudStreamApp.getKey<String>("wp_chat_glow") == "true"

    /** Sfondo della bolla: pieno con il colore del tema, oppure "glow" =
     *  nero puro dentro con bordo del colore del tema. */
    private fun bubbleBackground(activity: Activity, mine: Boolean, theme: ChatTheme): GradientDrawable {
        val color = if (mine) theme.mineBubble else theme.peerBubble
        return if (isGlow()) {
            GradientDrawable().apply {
                cornerRadius = dp(activity, 14).toFloat()
                setColor(0xFF000000.toInt())
                setStroke(dp(activity, 2), color)
            }
        } else {
            GradientDrawable().apply {
                cornerRadius = dp(activity, 14).toFloat()
                setColor(color)
            }
        }
    }

    private fun isButtonInvisible(): Boolean =
        CloudStreamApp.getKey<String>("wp_button_invisible") == "true"

    private fun isChatInvisible(): Boolean =
        CloudStreamApp.getKey<String>("wp_chat_invisible") == "true"

    /** Larghezza del pannello chat in % dello schermo (default 42, clamp 20–85). */
    private fun chatWidthPercent(): Int =
        CloudStreamApp.getKey<String>("wp_chat_width")?.toIntOrNull()?.coerceIn(20, 85) ?: 42

    /** Applica tema + visibilità icona chat. Chiamato ad ogni tick: così i
     *  cambi fatti dalle impostazioni valgono anche a chat già costruita. */
    private fun applyChatPrefs() {
        val themeIndex = CloudStreamApp.getKey<String>("wp_chat_theme")?.toIntOrNull() ?: 0
        val glow = isGlow()
        if (themeIndex != lastThemeIndex || glow != lastGlow) {
            lastThemeIndex = themeIndex
            lastGlow = glow
            repaintTheme()
        }
        val invisible = isChatInvisible()
        if (invisible != lastChatInvisible) {
            lastChatInvisible = invisible
            val image = chatArrowImage
            val host = chatArrowHost
            if (image != null && host != null) {
                if (invisible) {
                    image.alpha = 0f
                    host.background = null
                } else {
                    image.alpha = 1f
                    host.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x99000000.toInt())
                    }
                }
            }
        }
        // larghezza pannello: cambia anche a chat già costruita (pref impostata
        // nelle Impostazioni avanzate mentre si guarda il video)
        val width = chatWidthPercent()
        if (width != lastChatWidth) {
            lastChatWidth = width
            val panel = chatPanel ?: return
            val params = panel.layoutParams as? FrameLayout.LayoutParams ?: return
            val screenW = CommonActivity.activity?.window?.decorView?.width
                ?: (panel.context.resources.displayMetrics.widthPixels)
            params.width = screenW * width / 100
            panel.layoutParams = params
        }
        // posizione icona: cambia anche a icona già costruita (salvata
        // dall'editor col touchpad mentre la stanza è già attiva)
        val pos = savedPositionPercent()
        val posKey = pos?.let { "${it.first},${it.second}" } ?: "default"
        if (posKey != lastPosKey) {
            lastPosKey = posKey
            val host = chatArrowHost ?: return
            val activity = CommonActivity.activity ?: return
            val size = dp(activity, CHAT_ICON_SIZE_DP)
            host.layoutParams = buildHostParams(activity, size)
            host.requestLayout()
        }
    }

    /** Ridisegna tutte le bolle già mostrate col tema (e stile glow) corrente. */
    private fun repaintTheme() {
        val theme = chatTheme()
        val activity = CommonActivity.activity ?: return
        for ((bubble, mine) in bubbleRefs) {
            bubble.background = bubbleBackground(activity, mine, theme)
        }
        chatSendIcon?.colorFilter = android.graphics.PorterDuffColorFilter(
            theme.accent, android.graphics.PorterDuff.Mode.SRC_IN
        )
    }

    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sync()
            handler.postDelayed(this, 700L)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
        manager.onBufferingGateChanged = { show -> handler.post { setSpinnerVisible(show) } }
        manager.onChatMessage = { sender, text -> handler.post { onChatReceived(sender, text) } }
        manager.onSystemMessage = { text -> handler.post { appendSystemBubble(text) } }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        removeFab()
        removeSpinner()
        removeChat()
    }

    private fun sync() {
        val activity = CommonActivity.activity
        if (activity == null || activity.isFinishing) {
            removeFab()
            removeChat()
            return
        }
        val shouldShow = PlayerAccess.isPlayerScreenActive()

        // l'utente ha appena chiuso il player mentre la stanza era attiva: la chiudiamo
        if (wasPlayerActive && !shouldShow && manager.role != WatchPartyManager.Role.IDLE) {
            android.util.Log.d("WatchParty", "🚪 Player chiuso con stanza attiva, esco dalla stanza")
            manager.leaveRoom()
        }
        wasPlayerActive = shouldShow

        val inRoom = manager.role != WatchPartyManager.Role.IDLE

        if (shouldShow && (fab == null || attachedActivity !== activity)) {
            android.util.Log.d("WatchParty", "➕ WatchPartyOverlay: schermata player rilevata, aggiungo il FAB")
            removeFab()
            addFab(activity)
        } else if (!shouldShow && fab != null) {
            android.util.Log.d("WatchParty", "➖ WatchPartyOverlay: schermata player chiusa, rimuovo il FAB")
            removeFab()
            removeSpinner()
        } else if (fab != null) {
            fab?.let { updateVisibility(it) }
        }

        // chat: visibile solo con stanza attiva E player aperto
        if (shouldShow && inRoom && (chatArrowHost == null || chatArrowHost!!.parent !== activity.window?.decorView)) {
            removeChat()
            addChat(activity)
        } else if ((!shouldShow || !inRoom) && chatArrowHost != null) {
            removeChat()
        }

        // applica i pref della chat anche se è già stata costruita (es. tema
        // cambiato dalle impostazioni, o toggle "icona invisibile" attivato dopo)
        applyChatPrefs()
    }

    // ---------------------------------------------------------------------
    // FAB
    // ---------------------------------------------------------------------

    private fun addFab(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val iconDrawable = runCatching {
            val res = plugin.resources ?: return@runCatching null
            val id = res.getIdentifier("watchparty_icon", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
            if (id != 0) res.getDrawable(id, null) else null
        }.getOrNull()
        val button = FloatingActionButton(activity).apply {
            if (iconDrawable != null) setImageDrawable(iconDrawable)
            else setImageResource(android.R.drawable.ic_menu_share)
            setOnClickListener { onClick() }
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(activity, 20)
            bottomMargin = dp(activity, 90) // sopra la barra di controllo del player
        }
        runCatching { decor.addView(button, params) }.onSuccess {
            fab = button
            attachedActivity = activity
            updateVisibility(button)
        }
    }

    private fun updateVisibility(button: FloatingActionButton) {
        val invisible = isButtonInvisible()
        if (invisible) {
            android.util.Log.d("WatchParty", "🙈 WatchPartyOverlay: pulsante impostato INVISIBILE (wp_button_invisible=true) — resta cliccabile ma non si vede")
        }
        button.alpha = if (invisible) 0f else 1f
    }

    private fun removeFab() {
        val button = fab ?: return
        val parent = button.parent as? ViewGroup
        runCatching { parent?.removeView(button) }
        fab = null
        attachedActivity = null
    }

    // ---------------------------------------------------------------------
    // Rotellina di attesa
    // ---------------------------------------------------------------------

    private fun setSpinnerVisible(show: Boolean) {
        val activity = CommonActivity.activity ?: return
        if (show) {
            if (spinner != null) return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val bar = ProgressBar(activity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF4FC3F7.toInt())
            }
            val size = dp(activity, 42)
            // rotellina del plugin: azzurra e leggermente sopra quella nativa
            // del player (al centro), per non confonderle
            val height = decor.height
            val params = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (height / 2) + dp(activity, 24)
            }
            runCatching { decor.addView(bar, params) }.onSuccess { spinner = bar }
        } else {
            removeSpinner()
        }
    }

    private fun removeSpinner() {
        val bar = spinner ?: return
        val parent = bar.parent as? ViewGroup
        runCatching { parent?.removeView(bar) }
        spinner = null
    }

    // ---------------------------------------------------------------------
    // Chat
    // ---------------------------------------------------------------------

    private fun addChat(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        chatPanelOpen = false

        // ---- pomello/freccia, posizione custom (o default: sinistra, centro verticale) ----
        val host = FrameLayout(activity).apply { isClickable = true; isFocusable = true }
        val size = dp(activity, CHAT_ICON_SIZE_DP)
        val arrowImage = ImageView(activity).apply {
            setImageDrawable(getDrawable("chat_bubble") ?: getDrawable("watchparty_icon"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(activity, 9), dp(activity, 9), dp(activity, 12), dp(activity, 9))
        }
        val hostParams = buildHostParams(activity, size)
        // sfondo circolare semitrasparente
        host.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x99000000.toInt())
        }
        if (isChatInvisible()) {
            // freccetta invisibile ma resta cliccabile/attivabile; il pallino
            // rosso dei nuovi messaggi resta comunque visibile
            arrowImage.alpha = 0f
            host.background = null
        }
        host.setOnClickListener { openChat() }
        host.addView(arrowImage, FrameLayout.LayoutParams(size, size))
        chatArrowImage = arrowImage

        val dot = TextView(activity).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE53935.toInt())
            }
            minWidth = dp(activity, 20)
            minHeight = dp(activity, 20)
            setPadding(dp(activity, 5), 0, dp(activity, 5), 0)
            visibility = View.GONE
        }
        val dotSize = dp(activity, 20)
        val dotParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dotSize).apply {
            gravity = Gravity.END or Gravity.TOP
        }
        host.addView(dot, dotParams)

        // ---- pannello laterale (nascosto a sinistra) + zona "tap fuori" ----
        val root = FrameLayout(activity).apply { visibility = View.GONE }

        val outsideCatcher = View(activity)
        root.addView(outsideCatcher, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val panel = run {
            // larghezza regolabile (% dello schermo, default 42)
            val percent = chatWidthPercent()
            val screenW = activity.window?.decorView?.width
                ?: activity.resources.displayMetrics.widthPixels
            val w = screenW * percent / 100
            val p = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFF000000.toInt())
                    cornerRadius = dp(activity, 16).toFloat()
                }
                setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            }

            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(activity).apply {
                text = "Chat"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val closeBtn = TextView(activity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 4), dp(activity, 4))
                isClickable = true
                isFocusable = true
                setOnClickListener { closeChat() }
            }
            header.addView(title)
            header.addView(closeBtn)
            p.addView(header)

            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val messages = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(messages)
            p.addView(scroll)

            // riga input racchiusa in una card come quelle delle impostazioni
            val theme = chatTheme()
            val inputRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable("outline") ?: GradientDrawable().apply {
                    setColor(0x12FFFFFF.toInt())
                    cornerRadius = dp(activity, 16).toFloat()
                }
                setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 4), dp(activity, 4))
            }
            val input = EditText(activity).apply {
                hint = "Write a message…"
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(0xB3FFFFFF.toInt())
                setSingleLine(true)
                maxLines = 1
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // "FINE"/invio della tastiera NON deve risalire all'activity come
                // KEYCODE_ENTER (che in CloudStream toggla play/pausa): lo consumiamo
                // qui e lo trasformiamo in invio del messaggio.
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
                    ) {
                        sendChat()
                        true
                    } else {
                        false
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            chatInput = input
            val sendBtn = ImageView(activity).apply {
                setImageDrawable(getDrawable("send_icon"))
                colorFilter = android.graphics.PorterDuffColorFilter(theme.accent, android.graphics.PorterDuff.Mode.SRC_IN)
                isClickable = true
                // NON focusable: se rubasse il focus all'EditText, la tastiera si
                // chiuderebbe con un ENTER fantasma che toggla play/pausa in CloudStream
                isFocusable = false
                val s = dp(activity, 36)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setPadding(dp(activity, 7), dp(activity, 7), dp(activity, 7), dp(activity, 7))
                setOnClickListener { sendChat() }
            }
            chatSendIcon = sendBtn
            inputRow.addView(input)
            inputRow.addView(sendBtn)
            val inputRowCardParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 8) }
            p.addView(inputRow, inputRowCardParams)

            root.addView(p, FrameLayout.LayoutParams(
                w,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.FILL_VERTICAL,
            ))
            p
        }

        outsideCatcher.setOnClickListener { closeChat() }

        runCatching { decor.addView(host, hostParams) }.onSuccess {
            chatArrowHost = host
            chatUnreadDot = dot
        }
        runCatching { decor.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )) }.onSuccess {
            chatRoot = root
            chatPanel = panel
            val scroll = panel.getChildAt(1) as ScrollView
            chatScrollView = scroll
            chatMessages = scroll.getChildAt(0) as LinearLayout
        }
    }

    private fun openChat() {
        val root = chatRoot ?: return
        val panel = chatPanel ?: return
        val host = chatArrowHost ?: return
        chatPanelOpen = true
        host.visibility = View.GONE // la freccia sparisce quando si apre
        root.visibility = View.VISIBLE
        chatUnreadDot?.visibility = View.GONE
        chatUnreadCount = 0
        panel.visibility = View.VISIBLE
        panel.translationX = -panel.width.toFloat()
        panel.animate().translationX(0f).setDuration(220).start()
        scrollToBottom()
    }

    private fun closeChat() {
        val root = chatRoot ?: return
        val panel = chatPanel ?: return
        val host = chatArrowHost ?: return
        if (!chatPanelOpen) return
        chatPanelOpen = false
        panel.animate().translationX(-panel.width.toFloat()).setDuration(220)
            .withEndAction {
                root.visibility = View.GONE
                panel.visibility = View.GONE
                host.visibility = View.VISIBLE // riappare la freccia
            }.start()
    }

    private fun toggleChat() {
        if (chatPanelOpen) closeChat() else openChat()
    }

    private fun onChatReceived(sender: String, text: String) {
        if (chatRoot == null) return
        appendLocalBubble(sender, text, mine = false)
        bumpUnread()
    }

    /** Messaggio/notifica arrivato a chat chiusa: aggiorna il badge non letti.
     *  Funziona per i messaggi normali e per le bolle di sistema (entrati/usciti/kick/lucchetto). */
    private fun bumpUnread() {
        if (chatPanelOpen) return
        chatUnreadCount++
        val dot = chatUnreadDot ?: return
        dot.text = if (chatUnreadCount > 99) "99+" else chatUnreadCount.toString()
        dot.visibility = View.VISIBLE
    }

    private fun sendChat() {
        val input = chatInput ?: return
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val me = manager.localDisplayName()
        manager.sendChatMessage(text)
        // The other person receives the text over the socket; we show it locally right away
        appendLocalBubble(me, text, mine = true)
        input.setText("")
        // chiude la tastiera in sicurezza via InputMethodManager: nessun key event
        // che risalirebbe al player (KEYCODE_ENTER toggla play/pausa in CloudStream)
        runCatching {
            val imm = input.context.getSystemService(Activity.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    private fun appendLocalBubble(sender: String, text: String, mine: Boolean) {
        val list = chatMessages ?: return
        val activity = CommonActivity.activity ?: return
        val theme = chatTheme()
        // stesso mittente di fila: niente nome ripetuto e bolle più vicine
        val grouped = sender == lastGroupSender
        lastGroupSender = sender
        // distanza uniforme tra messaggi di fila: bottom SEMPRE 2dp, top 5dp
        // solo quando il nome sta sopra (nuovo gruppo). Così gap 1ª-2ª e
        // 2ª-3ª sono identici (2+2=4dp).
        val topPad = if (grouped) dp(activity, 2) else dp(activity, 5)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, topPad, 0, dp(activity, 2))
        }
        if (!grouped) {
            val nameView = TextView(activity).apply {
                this.text = sender
                textSize = 11f
                setTextColor(0x99FFFFFF.toInt())
                setPadding(dp(activity, 6), 0, dp(activity, 6), dp(activity, 2))
            }
            row.addView(nameView)
        }
        val bubble = TextView(activity).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            maxWidth = (list.width * 0.78f).toInt().coerceAtLeast(dp(activity, 120))
            background = bubbleBackground(activity, mine, theme)
            setPadding(dp(activity, 10), dp(activity, 7), dp(activity, 10), dp(activity, 7))
        }
        row.addView(bubble)
        list.addView(row)
        bubbleRefs += bubble to mine
        scrollToBottom()
    }

    /** Bolla di sistema: centrata, piccola e in grigio fisso. NON entra in bubbleRefs,
     *  quindi il repaint del tema non la tocca e non ha colore "mio/altrui". */
    private fun appendSystemBubble(text: String) {
        val list = chatMessages ?: return
        val activity = CommonActivity.activity ?: return
        // una bolla di sistema spezza la sequenza: il prossimo messaggio
        // torna a mostrare il nome del mittente
        lastGroupSender = null
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(activity, 4), 0, dp(activity, 2))
        }
        val bubble = TextView(activity).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFF8A8A8A.toInt())
            maxWidth = (list.width * 0.9f).toInt()
            setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3))
        }
        row.addView(bubble)
        list.addView(row)
        scrollToBottom()
        bumpUnread()
    }

    private fun scrollToBottom() {
        val scroll = chatScrollView ?: return
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun removeChat() {
        chatPanelOpen = false
        chatArrowHost?.let { (it.parent as? ViewGroup)?.removeView(it) }
        chatRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }
        chatArrowHost = null
        chatArrowImage = null
        chatUnreadDot = null
        chatUnreadCount = 0
        chatRoot = null
        chatPanel = null
        chatScrollView = null
        chatMessages = null
        chatInput = null
        chatSendIcon = null
        bubbleRefs.clear()
        lastGroupSender = null
        lastThemeIndex = -1
        lastGlow = null
        lastChatInvisible = null
        lastChatWidth = -1
        lastPosKey = null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}