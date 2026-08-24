package it.dogior.hadEnough.watchparty

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "WatchParty"

/**
 * Popup informativo mostrato una sola volta: spiega che i comandi di
 * riproduzione (play/pausa/posizione) passano attraverso un relay esterno
 * (il Cloudflare Worker) per essere inoltrati agli altri utenti della stanza.
 *
 * Usa MaterialAlertDialogBuilder (non il semplice AlertDialog) apposta:
 * eredita automaticamente lo stile Material dell'app — angoli arrotondati,
 * colori del tema — senza bisogno di forzare colori a mano.
 */
object WatchPartyConsent {

    private const val KEY_ACCEPTED = "wp_privacy_accepted"
    private const val KEY_ACCEPTED_AT = "wp_privacy_accepted_at"

    private var shownThisSession = false
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            showIfNeeded()
            handler.postDelayed(this, 1000L)
        }
    }

    fun hasAccepted(): Boolean = getKey<Boolean>(KEY_ACCEPTED) == true

    /** Data leggibile dell'accettazione, per mostrarla nelle impostazioni del plugin. */
    fun acceptedAtLabel(): String? {
        val millis = getKey<Long>(KEY_ACCEPTED_AT) ?: return null
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    private fun setAccepted() {
        Log.d(TAG, "✅ WatchPartyConsent: utente ha accettato, salvo la preferenza")
        setKey(KEY_ACCEPTED, true)
        setKey(KEY_ACCEPTED_AT, System.currentTimeMillis())
        running = false
        handler.removeCallbacks(tick)
    }

    /** Chiamata una volta sola da WatchPartyPlugin.load(). */
    fun attach() {
        Log.d(TAG, "🚀 WatchPartyConsent.attach() chiamata da load() del plugin")
        if (hasAccepted()) {
            Log.d(TAG, "⏭️ WatchPartyConsent: già accettato in passato (${acceptedAtLabel()}), popup non necessario")
            return
        }
        if (running) return
        running = true
        Log.d(TAG, "⏱️ WatchPartyConsent: avvio il controllo periodico (ogni 1s) per mostrare il popup")
        handler.post(tick)
    }

    private fun showIfNeeded() {
        if (hasAccepted() || shownThisSession) {
            Log.d(TAG, "⏹️ WatchPartyConsent: fermo il controllo (accettato=${hasAccepted()}, mostrato=$shownThisSession)")
            running = false
            handler.removeCallbacks(tick)
            return
        }
        val activity = CommonActivity.activity
        if (activity == null) {
            Log.d(TAG, "⌛ WatchPartyConsent: CommonActivity.activity è ancora null, riprovo tra 1s")
            return
        }
        Log.d(TAG, "🎬 WatchPartyConsent: activity trovata (${activity::class.java.simpleName}), mostro il popup ORA")
        shownThisSession = true
        running = false
        handler.removeCallbacks(tick)
        try {
            show(activity)
        } catch (e: Exception) {
            Log.e(TAG, "💥 WatchPartyConsent: ECCEZIONE mentre costruivo il popup", e)
            shownThisSession = false // ritenta al prossimo giro se qualcosa è andato storto
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun show(context: Context) {
        val hPad = dp(context, 24)
        val vPad = dp(context, 8)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hPad, vPad, hPad, 0)
        }

        val messageView = TextView(context).apply {
            text = "Watch Party uses a lightweight relay server solely to pass real-time " +
                "playback commands (play, pause, seek), chat messages, and the room " +
                "PIN between connected devices. No audio or video streams pass " +
                "through the server: media is loaded directly on your device. All " +
                "data, including chat messages, is processed strictly in memory for " +
                "real-time routing. No chat history, logs, or personal data are " +
                "recorded or stored on the server. Once delivered, messages leave " +
                "no trace."
            textSize = 12f
            setLineSpacing(dp(context, 2).toFloat(), 1f)
        }

        val checkBox = CheckBox(context).apply {
            text = "I have read and accept"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 16), 0, dp(context, 4))
        }

        container.addView(messageView)
        container.addView(checkBox)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Privacy & Sync Notes")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Accept", null) // listener sotto, per poterlo disabilitare all'inizio
            .create()

        dialog.setOnShowListener {
            Log.d(TAG, "👀 WatchPartyConsent: popup effettivamente visibile a schermo (onShow)")
            val acceptBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            acceptBtn.isEnabled = false
            checkBox.setOnCheckedChangeListener { _, checked ->
                Log.d(TAG, "☑️ WatchPartyConsent: checkbox = $checked")
                acceptBtn.isEnabled = checked
            }
            acceptBtn.setOnClickListener {
                Log.d(TAG, "🖱️ WatchPartyConsent: pulsante Accetto premuto")
                setAccepted()
                dialog.dismiss()
            }
        }

        dialog.show()
        Log.d(TAG, "📤 WatchPartyConsent: dialog.show() chiamato")
    }
}
