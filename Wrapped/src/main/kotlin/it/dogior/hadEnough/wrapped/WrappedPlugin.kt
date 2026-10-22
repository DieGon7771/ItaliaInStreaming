package it.dogior.hadEnough.wrapped

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val TAG = "CloudStreamWrapped"

@CloudstreamPlugin
class WrappedPlugin : Plugin() {

    override fun load(context: Context) {
        this.openSettings = { openWrappedSheet() }
    }

    private fun openWrappedSheet() = runCatching {
        val activity = CommonActivity.activity as? FragmentActivity
        if (activity == null) {
            val msg = "Can't open CloudStream Wrapped here (unsupported activity type)"
            Log.e(TAG, "openWrappedSheet(): $msg")
            showToast(msg, Toast.LENGTH_LONG)
            return@runCatching
        }
        WrappedFragment(this).show(activity.supportFragmentManager, "CloudStreamWrapped")
    }.onFailure {
        Log.e(TAG, "openWrappedSheet() failed", it)
        showToast("Can't open CloudStream Wrapped: ${it.javaClass.simpleName}", Toast.LENGTH_LONG)
    }
}
