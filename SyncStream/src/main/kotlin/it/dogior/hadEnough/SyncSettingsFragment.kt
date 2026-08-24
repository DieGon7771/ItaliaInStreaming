@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.*

private const val TAG = "SyncStream"

abstract class SyncBaseSettingsFragment : BottomSheetDialogFragment() {

    protected val plugin: Plugin
        get() = SyncPlugin.activePlugin ?: error("Plugin not available")

    protected val res
        get() = plugin.resources ?: error("Resources not available")

    protected abstract val layoutName: String

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = res.getIdentifier(layoutName, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(layoutId), container, false)
    }

    protected fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id)
    }

    protected fun getDrawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(res, it, null) }
    }

    protected fun View.applyOutlineBackground() {
        this.background = getDrawable("outline")
    }

    protected fun setupSaveButton(view: View, onClick: () -> Unit) {
        val saveBtn: ImageButton? = view.findView("save_btn")
        saveBtn?.applyOutlineBackground()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))
        saveBtn?.setOnClickListener { onClick() }
    }
}

class SyncSettingsFragment : SyncBaseSettingsFragment() {

    override val layoutName: String = "settings"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val status = when {
            !ApiUtils.isLoggedIn() -> "Non configurato"
            getKey<String>("backup_device") == "true" && getKey<String>("restore_device") == "true" -> "Backup e ripristino attivi"
            getKey<String>("backup_device") == "true" -> "Backup attivo"
            getKey<String>("restore_device") == "true" -> "Ripristino attivo"
            else -> "Configurato"
        }
        view.findView<TextView>("header_status").text = status

        view.findView<View>("login_card").applyOutlineBackground()
        view.findView<View>("prefs_card").applyOutlineBackground()
        view.findView<View>("guide_card").applyOutlineBackground()

        view.findView<View>("login_card").setOnClickListener {
            SyncLoginFragment().show(requireActivity().supportFragmentManager, "Login")
        }

        view.findView<View>("guide_card").setOnClickListener {
            val url = "https://github.com/DieGon7771/ItaliaInStreaming/blob/master/guide/README_SyncStream.md"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        view.findView<View>("prefs_card").setOnClickListener {
            SyncPrefsFragment().show(requireActivity().supportFragmentManager, "Prefs")
        }
    }
}

class SyncPrefsFragment : SyncBaseSettingsFragment() {

    override val layoutName: String = "prefs"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findView<View>("prefs_body_card").applyOutlineBackground()

        val intervals = listOf(10_000L, 15_000L, 30_000L, 60_000L)
        val autoPullSwitch = view.findView<Switch>("auto_pull_switch")
        val prefsOptions = view.findView<View>("auto_pull_options")

        fun refreshPrefsUI() {
            val enabled = getKey<Boolean>("auto_pull_enabled") ?: false
            autoPullSwitch.isChecked = enabled
            prefsOptions.visibility = if (enabled) View.VISIBLE else View.GONE
            val current = getKey<Long>("auto_pull_seconds") ?: 30_000L
            intervals.forEach { sec ->
                view.findView<TextView>("auto_pull_${sec / 1000}_check").text =
                    if (sec == current) "✓" else ""
            }
        }

        refreshPrefsUI()

        autoPullSwitch.setOnCheckedChangeListener { _, isChecked ->
            setKey("auto_pull_enabled", isChecked)
            prefsOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        intervals.forEach { sec ->
            view.findView<View>("auto_pull_${sec / 1000}_row").setOnClickListener {
                setKey("auto_pull_seconds", sec)
                refreshPrefsUI()
            }
        }

        val closeBtn = view.findView<TextView>("close_btn")
        closeBtn.background = getDrawable("outline")
        closeBtn.setOnClickListener { dismiss() }
    }
}

class SyncLoginFragment : SyncBaseSettingsFragment() {

    override val layoutName: String = "login"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findView<View>("creds_card").applyOutlineBackground()

        val tokenInput = view.findView<EditText>("token")
        tokenInput.setText(getKey<String>("sync_token"))
        val prNumInput = view.findView<EditText>("project_num")
        prNumInput.setText(getKey<String>("sync_project_num"))
        val backupSwitch = view.findView<Switch>("backup_device")
        backupSwitch.text = "Backup dei dati sul cloud"
        backupSwitch.isChecked = getKey<String>("backup_device") == "true"
        val restoreSwitch = view.findView<Switch>("restore_device")
        restoreSwitch.text = "Recupera dati dal cloud"
        restoreSwitch.isChecked = getKey<String>("restore_device") == "true"

        setupSaveButton(view) {
            save(tokenInput, prNumInput, backupSwitch, restoreSwitch)
        }

        val resetBtn = view.findView<TextView>("reset_btn")
        val dangerDrawable = getDrawable("outline_danger")
        if (dangerDrawable != null) resetBtn.background = dangerDrawable else resetBtn.applyOutlineBackground()
        resetBtn.setTextColor(Color.parseColor("#FFFF7F7F"))
        resetBtn.setOnClickListener {
            setKey("sync_token", "")
            setKey("sync_project_num", "")
            showToast("Credentials removed")
            dismiss()
        }
    }

    private fun save(
        tokenInput: EditText,
        prNumInput: EditText,
        backupSwitch: Switch,
        restoreSwitch: Switch
    ) {
        val token = tokenInput.text.trim().toString()
        val prNum = prNumInput.text.toString()
        if (token.isEmpty() || prNum.isEmpty()) {
            showToast("Please fill in all information")
            return
        }
        setKey("sync_token", token)
        setKey("sync_project_num", prNum)
        setKey("backup_device", "${backupSwitch.isChecked}")
        setKey("restore_device", "${restoreSwitch.isChecked}")

        val ctx = requireContext()
        val loadingDialog = AlertDialog.Builder(ctx)
            .setView(inflateLoading())
            .setCancelable(false)
            .create()
        loadingDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ApiUtils.syncProjectDetails(ctx)
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    if (result?.first == true) {
                        (plugin as? SyncPlugin)?.onLoginCompleted()
                        showToast(result.second)
                        dismiss()
                    } else {
                        showToast(result?.second ?: "Errore di sincronizzazione")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    showToast("Error syncing: ${e.message}")
                }
            }
        }
    }

    private fun inflateLoading(): View {
        val id = res.getIdentifier("loading", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return LayoutInflater.from(requireContext()).inflate(res.getLayout(id), null, false)
    }
}
