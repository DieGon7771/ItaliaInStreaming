@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.os.*
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAccounts
import com.lagradost.cloudstream3.utils.DataStoreHelper.deleteAllResumeStateIds
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private val handler = Handler(Looper.getMainLooper())

    private var lastResumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null

    private var counter = 0
    
    private val runnable = object : Runnable {
        override fun run() {
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val currentResumeWatching = getResumeWatching()
                    if (currentResumeWatching != lastResumeWatching) {
                        counter = 0
                        backupDevice(true)
                        lastResumeWatching = currentResumeWatching
                    }
                    counter++
                    if (counter >= 12) {
                        counter = 0
                        backupDevice(true)
                    }
                }
                handler.postDelayed(this, 5000)
            } catch (e: Exception) {
            }
        }
    }

    private fun backupDevice(unused: Boolean) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                if (getKey<String>("backup_device") == "true") {
                    val data = BackupUtils.getBackup(context, getResumeWatching())?.toJson() ?: ""
                    ApiUtils.syncThisDevice(data)
                }
            }
        } catch (e: Exception) {
        }
    }

    override fun load(context: Context) {
        val packageName = context.packageName
        setKey("device_id", getDeviceId(packageName, context))
        runBlocking {
            val devices: List<SyncDevice>? = ApiUtils.fetchDevices()
            if (devices?.isEmpty() == false && devices?.size ?: 0 > 0) {
                if (getKey<String>("restore_device") == "true") {
                    val firstDevice: SyncDevice = devices!!.first()
                    if (firstDevice.name != getKey<String>("device_id")) {
                        val accounts = getAccounts(context)
                        accounts.forEach {
                            removeKeys("${it.keyIndex}/result_watch_state")
                            removeKeys("${it.keyIndex}/result_watch_state_data")
                            removeKeys("${it.keyIndex}/result_favorites_state_data")
                            removeKeys("${it.keyIndex}/search_history")
                        }
                        if (firstDevice.syncedData?.contains("\"${DataStoreHelper.currentAccount}/result_resume_watching") == true) {
                            deleteAllResumeStateIds()
                        }
                        val restoredValue = mapper.readValue<BackupFile>(firstDevice.syncedData ?: "")
                        BackupUtils.restore(context, restoredValue, true, true)
                        MainActivity.bookmarksUpdatedEvent(true)
                    }
                }
                handler.post(runnable)
            }
        }
        MainActivity.bookmarksUpdatedEvent += ::backupDevice
        MainActivity.afterPluginsLoadedEvent += ::backupDevice
        MainActivity.mainPluginsLoadedEvent += ::backupDevice
        MainActivity.reloadHomeEvent += ::backupDevice
        MainActivity.reloadAccountEvent += ::backupDevice
    }

    init {
        this.openSettings = {
            try {
                val activity = it as? AppCompatActivity
                if (activity != null) {
                    val frag = SyncSettingsFragment(this)
                    frag.show(activity.supportFragmentManager, "Github")
                }
            } catch (e: Exception) {
            }
        }
    }
}
