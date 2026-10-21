package it.dogior.hadEnough.autoextensions

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.plugins.RepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AutoExtensions"

private const val INITIAL_DELAY_MS = 8_000L

private data class TaskResult(val summary: String, val changed: Int)

// Only uses public PluginManager/RepositoryManager API: the host's ___DO_NOT_CALL_FROM_A_PLUGIN_ functions are
// guarded against plugin use and are deliberately never called.
object AutoExtensionsWorker {
    private val handler = Handler(Looper.getMainLooper())
    private var hostPlugin: AutoExtensionsPlugin? = null
    private var isRunning = false

    fun scheduleInitialCheck(plugin: AutoExtensionsPlugin) {
        hostPlugin = plugin
        handler.postDelayed({ runCheck(isAutomatic = true) }, INITIAL_DELAY_MS)
    }

    fun runCheckNow() {
        runCheck(isAutomatic = false)
    }

    private fun runCheck(isAutomatic: Boolean) {
        runTask(isAutomatic, startMessage = null) { activity, context ->
            performCheck(activity, context)
        }
    }

    private fun runTask(
        isAutomatic: Boolean,
        startMessage: String?,
        block: suspend (Activity, Context) -> TaskResult,
    ) {
        if (isRunning) {
            if (!isAutomatic) {
                CommonActivity.activity?.let { showToast(it, "Another task is already running", Toast.LENGTH_SHORT) }
            }
            return
        }

        val activity = CommonActivity.activity
        if (activity == null) {
            Log.w(TAG, "No activity available yet, skipping task")
            return
        }
        val context = activity.applicationContext

        if (isAutomatic && !AutoExtensionsPrefs.isEnabled(context)) return

        isRunning = true
        if (startMessage != null) {
            runCatching { showToast(activity, startMessage, Toast.LENGTH_SHORT) }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { block(activity, context) }
            isRunning = false

            val taskResult = result.getOrElse { t ->
                Log.e(TAG, "Task failed", t)
                TaskResult("Failed: ${t.javaClass.simpleName}", 0)
            }
            AutoExtensionsPrefs.setLastRun(context, System.currentTimeMillis(), taskResult.summary)

            withContext(Dispatchers.Main) {
                hostPlugin?.onCheckFinished()
                if (!isAutomatic || taskResult.changed > 0) {
                    runCatching { showToast(activity, taskResult.summary, Toast.LENGTH_LONG) }
                }
            }
        }
    }

    private suspend fun loadAllRepoPlugins(): Pair<Int, List<PluginWrapper>> {
        val repos = (RepositoryManager.getRepositories().toList() + RepositoryManager.PREBUILT_REPOSITORIES.toList())
            .distinctBy { it.url }
        val plugins = repos.flatMap { repo ->
            runCatching { RepositoryManager.getRepoPlugins(repo) }.getOrNull().orEmpty()
        }.distinctBy { it.plugin.url }
        return repos.size to plugins
    }

    private suspend fun performCheck(activity: Activity, context: Context): TaskResult {
        val includeNsfw = AutoExtensionsPrefs.isNsfwIncluded(context)
        val (repoCount, allPlugins) = loadAllRepoPlugins()

        val missing = allPlugins.filter { wrapper ->
            val plugin = wrapper.plugin
            if (plugin.url.isBlank()) return@filter false
            if (wrapper.repositoryData.url.isBlank()) return@filter false
            if (plugin.status == PROVIDER_STATUS_DOWN) return@filter false
            if (!includeNsfw && plugin.tvTypes?.contains("NSFW") == true) return@filter false

            val path = PluginManager.getPluginPath(context, plugin.internalName, wrapper.repositoryData.url)
            !path.exists()
        }

        var downloaded = 0
        for (wrapper in missing) {
            val plugin = wrapper.plugin
            val ok = runCatching {
                PluginManager.downloadPlugin(
                    activity,
                    plugin.url,
                    plugin.fileHash,
                    plugin.internalName,
                    wrapper.repositoryData.url,
                    true,
                )
            }.getOrDefault(false)
            if (ok) downloaded++
        }

        return TaskResult(
            "Checked ${allPlugins.size} extensions across $repoCount repositories, $downloaded new downloaded.",
            downloaded,
        )
    }

}
