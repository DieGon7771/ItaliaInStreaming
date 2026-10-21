package it.dogior.hadEnough.autoextensions

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val TAG = "AutoExtensions"

@CloudstreamPlugin
class AutoExtensionsPlugin : Plugin() {

    private var settingsFragment: AutoExtensionsSettingsFragment? = null

    override fun load(context: Context) {
        this.openSettings = { openSettingsSheet() }
        AutoExtensionsWorker.scheduleInitialCheck(this)
    }

    fun onCheckFinished() {
        settingsFragment?.onCheckFinished()
    }

    private fun openSettingsSheet() = runCatching {
        val activity = CommonActivity.activity as? FragmentActivity
        if (activity == null) {
            val msg = "Can't open AutoExtensions settings here (unsupported activity type)"
            Log.e(TAG, "openSettingsSheet(): $msg")
            showToast(msg, Toast.LENGTH_LONG)
            return@runCatching
        }
        val fragment = AutoExtensionsSettingsFragment(this)
        settingsFragment = fragment
        fragment.show(activity.supportFragmentManager, "AutoExtensions")
    }.onFailure {
        Log.e(TAG, "openSettingsSheet(): unexpected exception", it)
        showToast("Can't open AutoExtensions settings: ${it.javaClass.simpleName}", Toast.LENGTH_LONG)
    }
}
