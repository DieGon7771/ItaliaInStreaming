package it.dogior.hadEnough.watchparty

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val TAG = "WatchParty"

/**
 * Plugin sperimentale di riproduzione sincronizzata (Watch Party).
 *
 * Si basa su percorsi pubblici ma non ufficialmente garantiti dall'API dei
 * plugin CloudStream (vedi PlayerAccess.kt). Può smettere di funzionare
 * dopo un aggiornamento dell'app: in quel caso il polling ritorna
 * semplicemente null e il plugin resta inerte, senza crashare l'app.
 */
@CloudstreamPlugin
class WatchPartyPlugin : Plugin() {

    private val manager = WatchPartyManager()
    private lateinit var overlay: WatchPartyOverlay

    override fun load(context: Context) {
        // Nuvio Enhanced rifiuta i plugin che non registrano almeno un
        // MainAPI (vedi WatchPartyNuvioProvider). Su CloudStream vero non
        // serve e non lo registriamo, per non sporcare la lista fonti.
        if (!WatchPartyPlayback.isCloudStreamHost) {
            registerMainAPI(WatchPartyNuvioProvider())
        }

        overlay = WatchPartyOverlay(plugin = this, manager = manager, onClick = {
            openSettingsSheet()
        })
        overlay.start()
        WatchPartyConsent.attach()
    }

    override fun beforeUnload() {
        overlay.stop()
        manager.release()
    }

    private fun openSettingsSheet() {
        val rawActivity = CommonActivity.activity
        val activity = rawActivity as? AppCompatActivity
        if (activity == null) {
            Log.e(TAG, "openSettingsSheet(): CommonActivity.activity non è un AppCompatActivity, impossibile aprire il foglio impostazioni")
            return
        }
        if (WatchPartyPlayback.isCloudStreamHost) {
            WatchPartySettingsFragmentCloudStream(this, manager).show(activity.supportFragmentManager, "WatchParty")
        } else {
            WatchPartySettingsFragmentNuvio(this, manager).show(activity.supportFragmentManager, "WatchParty")
        }
    }

    init {
        // Chiamato quando l'utente apre le impostazioni del plugin dalla
        // schermata Estensioni: stesso ingresso usato dal FAB sopra il player.
        this.openSettings = {
            openSettingsSheet()
        }
    }
}
