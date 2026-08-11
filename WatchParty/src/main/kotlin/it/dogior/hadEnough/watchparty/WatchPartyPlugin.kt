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
        Log.d(TAG, "🔌 WatchPartyPlugin.load() chiamato")
        overlay = WatchPartyOverlay(plugin = this, manager = manager, onClick = {
            Log.d(TAG, "👆 FAB del player cliccato")
            openSettingsSheet()
        })
        overlay.start()
        WatchPartyConsent.attach()
    }

    override fun beforeUnload() {
        Log.d(TAG, "🔻 WatchPartyPlugin.beforeUnload()")
        overlay.stop()
        manager.release()
    }

    private fun openSettingsSheet() {
        val rawActivity = CommonActivity.activity
        Log.d(TAG, "🔧 openSettingsSheet(): CommonActivity.activity = ${rawActivity?.let { it::class.java.name } ?: "null"}")
        val activity = rawActivity as? AppCompatActivity
        if (activity == null) {
            Log.e(TAG, "❌ openSettingsSheet(): il cast ad AppCompatActivity è fallito, esco senza fare nulla (era questo il bug del 'non succede niente'?)")
            return
        }
        Log.d(TAG, "📄 openSettingsSheet(): apro la BottomSheetDialogFragment")
        WatchPartySettingsFragment(this, manager).show(activity.supportFragmentManager, "WatchParty")
    }

    init {
        // Chiamato quando l'utente apre le impostazioni del plugin dalla
        // schermata Estensioni: stesso ingresso usato dal FAB sopra il player.
        this.openSettings = {
            Log.d(TAG, "👆 'Impostazioni' aperte dalla lista Estensioni")
            openSettingsSheet()
        }
    }
}
