@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.os.*
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.*

private const val TAG = "SyncStream"

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    companion object {
        @Volatile
        internal var activePlugin: Plugin? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    private val pollDelay = 5_000L

    private var lastResumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null

    private var counter = 0

    private var pullCounter = 0

    /**
     * Il primo restore deve completare prima di iniziare a fare backup, altrimenti
     * un dispositivo appena registrato potrebbe sovrascrivere il cloud con dati vuoti.
     */
    @Volatile
    private var initialSyncDone = false

    @Volatile
    private var runnableStarted = false

    /** Evita cicli sovrapposti quando una chiamata di rete dura più di un tick. */
    @Volatile
    private var cycleRunning = false

    private val runnable = object : Runnable {
        override fun run() {
            try {
                if (!cycleRunning) {
                    cycleRunning = true
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            performCycle()
                        } catch (e: Exception) {
                            Log.w(TAG, "🔄 runnable error: ${e.message}")
                        } finally {
                            cycleRunning = false
                        }
                    }
                }
                handler.postDelayed(this, pollDelay)
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Un ciclo di polling:
     * 1) backup dei dati locali se cambiati o se è ora del backup forzato (60s);
     * 2) pull periodico opzionale (solo se "Aggiornamento automatico" è attivo).
     */
    private suspend fun performCycle() {
        val currentResumeWatching = getResumeWatching()
        val changed = currentResumeWatching != lastResumeWatching
        if (changed) {
            lastResumeWatching = currentResumeWatching
            counter = 0
        }
        counter++
        if (changed || counter >= 12) {
            counter = 0
            performBackup()
        }
        performPullIfEnabled()
    }

    private suspend fun performBackup() {
        if (!initialSyncDone) return
        if (getKey<String>("backup_device") != "true") return
        if (!ApiUtils.isLoggedIn()) return
        try {
            val backup = BackupUtils.getBackup(context, getResumeWatching()) ?: return
            val envelope = SyncEnvelope(System.currentTimeMillis(), backup)
            Log.i(TAG, "📤 invio backup...")
            val result = ApiUtils.syncThisDevice(envelope.toJson())
            if (result.first) {
                setKey("sync_last_restore_at", envelope.updatedAt)
                Log.i(TAG, "📤 backup inviato ✅ (${envelope.updatedAt})")
            } else {
                Log.w(TAG, "📤 backup FALLITO ❌: ${result.second}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "📤 backup error: ${e.message}")
        }
    }

    /**
     * Pull periodico opzionale: ogni `auto_pull_seconds` scarica il backup dal
     * cloud e, se più recente del locale, lo applica (con reconcile delle
     * cancellazioni). Attivo solo con la preferenza "Aggiornamento automatico".
     */
    private suspend fun performPullIfEnabled() {
        if (getKey<String>("restore_device") != "true") return
        if ((getKey<Boolean>("auto_pull_enabled") ?: false) == false) return
        pullCounter++
        val interval = getKey<Long>("auto_pull_seconds") ?: 30_000L
        val ticks = (interval / pollDelay).toInt().coerceAtLeast(1)
        if (pullCounter < ticks) return
        pullCounter = 0
        try {
            if (!ApiUtils.isLoggedIn()) return
            Log.i(TAG, "📥 pull periodico...")
            val devices = ApiUtils.fetchDevices() ?: return
            val node = devices.firstOrNull() ?: return
            val lastRestore = getKey<Long>("sync_last_restore_at") ?: 0L
            if (node.updatedAt <= lastRestore) {
                Log.i(TAG, "📥 nessun cambiamento da scaricare")
                return
            }
            if (ApiUtils.restoreFromDevice(context, node)) {
                handler.post { MainActivity.bookmarksUpdatedEvent(true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "📥 pull error: ${e.message}")
        }
    }

    private fun backupDevice(unused: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            performBackup()
        }
    }

    private fun ensureRunnableRunning() {
        if (runnableStarted) return
        runnableStarted = true
        handler.post(runnable)
    }

    /**
     * Sincronizzazione iniziale: fetch dei nodi e restore non distruttivo del backup
     * più recente, fuori dal main thread. Imposta initialSyncDone al termine.
     */
    private fun performInitialSync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!ApiUtils.isLoggedIn()) return@launch
                val devices = ApiUtils.fetchDevices()
                val node = devices?.firstOrNull()
                if (node != null) {
                    setKey("sync_item_id", node.itemId ?: "")
                    setKey("sync_device_id", node.deviceId ?: "")
                    if (getKey<String>("restore_device") == "true") {
                        if (ApiUtils.restoreFromDevice(context, node)) {
                            handler.post { MainActivity.bookmarksUpdatedEvent(true) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚡ initial sync error: ${e.message}")
            } finally {
                initialSyncDone = true
                if (ApiUtils.isLoggedIn()) {
                    Log.i(TAG, "⚡ sync iniziale completata")
                    handler.post { ensureRunnableRunning() }
                }
            }
        }
    }

    override fun load(context: Context) {
        val packageName = context.packageName
        setKey("device_id", getDeviceId(packageName, context))
        MainActivity.bookmarksUpdatedEvent += ::backupDevice
        MainActivity.afterPluginsLoadedEvent += ::backupDevice
        MainActivity.mainPluginsLoadedEvent += ::backupDevice
        MainActivity.reloadHomeEvent += ::backupDevice
        MainActivity.reloadAccountEvent += ::backupDevice
        performInitialSync()
    }

    init {
        this.openSettings = {
            try {
                activePlugin = this
                val activity = it as? AppCompatActivity
                if (activity != null) {
                    val frag = SyncSettingsFragment()
                    frag.show(activity.supportFragmentManager, "Github")
                }
            } catch (e: Exception) {
            }
        }
    }

    /** Chiamato dopo un login riuscito: sblocca i backup e avvia il polling. */
    fun onLoginCompleted() {
        initialSyncDone = true
        handler.post { ensureRunnableRunning() }
    }
}
