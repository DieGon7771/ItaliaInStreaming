package it.dogior.hadEnough.watchparty

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
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
 * Usa android.app.AlertDialog (non più MaterialAlertDialogBuilder): quella
 * dipendeva da com.google.android.material, che non è detto sia presente a
 * runtime su ogni host dei plugin CloudStream (es. Nuvio Enhanced, dove
 * causava un NoClassDefFoundError qui). Un po' meno "vestito" nello stile,
 * ma funziona ovunque senza bisogno di rilevare l'host.
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
        setKey(KEY_ACCEPTED, true)
        setKey(KEY_ACCEPTED_AT, System.currentTimeMillis())
        running = false
        handler.removeCallbacks(tick)
    }

    /** Chiamata una volta sola da WatchPartyPlugin.load(). */
    fun attach() {
        if (hasAccepted()) return
        if (running) return
        running = true
        handler.post(tick)
    }

    private fun showIfNeeded() {
        if (hasAccepted() || shownThisSession) {
            running = false
            handler.removeCallbacks(tick)
            return
        }
        val activity = CommonActivity.activity ?: return
        shownThisSession = true
        running = false
        handler.removeCallbacks(tick)
        try {
            show(activity)
        } catch (e: Throwable) {
            // Throwable e non solo Exception: un NoClassDefFoundError (classe
            // mancante a runtime, es. libreria non presente sull'host) è un
            // Error, non un'Exception — un catch (e: Exception) qui non lo
            // avrebbe intercettato, lasciando il popup crashare comunque.
            Log.e(TAG, "Eccezione mentre costruivo il popup di consenso", e)
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

        val dialog = newAlertDialogBuilder(context)
            .setTitle("Privacy & Sync Notes")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Accept", null) // listener sotto, per poterlo disabilitare all'inizio
            .create()

        styleAsWatchPartyPanel(
            dialog,
            android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF000000.toInt())
                cornerRadius = dp(context, 16).toFloat()
            },
        )

        dialog.setOnShowListener {
            val acceptBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            acceptBtn.isEnabled = false
            checkBox.setOnCheckedChangeListener { _, checked ->
                acceptBtn.isEnabled = checked
            }
            acceptBtn.setOnClickListener {
                setAccepted()
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
