package it.dogior.hadEnough.watchparty

import android.content.Context
import android.util.Log
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

    private fun openSettingsSheet() = runCatching {
        // FragmentActivity, non AppCompatActivity: supportFragmentManager è già
        // dichiarato lì. AppCompatActivity (androidx.appcompat.app) non è
        // risolvibile dal classloader del plugin su alcune build/dispositivi
        // Nuvio (causava NoClassDefFoundError anche solo per fare "as?"),
        // mentre androidx.fragment.* lo è sempre: è la stessa libreria da cui
        // viene DialogFragment, già usato ovunque nel plugin senza problemi.
        val activity = CommonActivity.activity as? androidx.fragment.app.FragmentActivity
        if (activity == null) {
            Log.e(TAG, "openSettingsSheet(): CommonActivity.activity non è una FragmentActivity, impossibile aprire il foglio impostazioni")
            return@runCatching
        }
        if (WatchPartyPlayback.isCloudStreamHost) {
            WatchPartySettingsFragmentCloudStream(this, manager).show(activity.supportFragmentManager, "WatchParty")
        } else {
            WatchPartySettingsFragmentNuvio(this, manager).show(activity.supportFragmentManager, "WatchParty")
        }
    }.onFailure {
        Log.e(TAG, "openSettingsSheet(): eccezione inattesa, impossibile aprire il foglio impostazioni", it)
    }

    init {
        // Chiamato quando l'utente apre le impostazioni del plugin dalla
        // schermata Estensioni: stesso ingresso usato dal FAB sopra il player.
        this.openSettings = {
            openSettingsSheet()
        }
    }
}
