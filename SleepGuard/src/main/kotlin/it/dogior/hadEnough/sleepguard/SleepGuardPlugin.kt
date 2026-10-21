package it.dogior.hadEnough.sleepguard

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val TAG = "SleepGuard"

@CloudstreamPlugin
class SleepGuardPlugin : Plugin() {

    override fun load(context: Context) {
        this.openSettings = { openSettingsSheet() }
        SleepGuardWatcher.start(this)
    }

    private fun openSettingsSheet() = runCatching {
        val activity = CommonActivity.activity as? FragmentActivity
        if (activity == null) {
            val msg = "Can't open SleepGuard settings here (unsupported activity type)"
            Log.e(TAG, "openSettingsSheet(): $msg")
            showToast(msg, Toast.LENGTH_LONG)
            return@runCatching
        }
        SleepGuardSettingsFragment(this).show(activity.supportFragmentManager, "SleepGuard")
    }.onFailure {
        Log.e(TAG, "openSettingsSheet(): unexpected exception", it)
        showToast("Can't open SleepGuard settings: ${it.javaClass.simpleName}", Toast.LENGTH_LONG)
    }
}
