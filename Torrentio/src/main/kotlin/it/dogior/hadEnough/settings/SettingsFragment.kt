package it.dogior.hadEnough.settings

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import it.dogior.hadEnough.BuildConfig

class SettingsFragment(
    plugin: com.lagradost.cloudstream3.plugins.Plugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {

    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id)
    }

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null)
    }

    private fun View?.makeTvCompatible() {
        if (this == null) return
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (outlineId != 0) {
            val drawable = res.getDrawable(outlineId, null)
            if (drawable != null) background = drawable
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = getLayout("torrentio_settings", inflater, container)

        val debridSpinner = root.findView<Spinner>("debrid_provider_spinner")
        val debridProviders = listOf("None", "RealDebrid", "Premiumize", "TorBox")
        debridSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, debridProviders).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        val savedDebrid = sharedPref.getString("debrid_provider", null)
        if (savedDebrid != null) {
            val pos = debridProviders.indexOf(savedDebrid)
            if (pos >= 0) debridSpinner.setSelection(pos)
        }
        debridSpinner.makeTvCompatible()

        val debridKeyInput = root.findView<EditText>("debrid_key_input")
        debridKeyInput.setText(sharedPref.getString("debrid_key", ""))
        debridKeyInput.makeTvCompatible()

        val saveBtn = root.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.makeTvCompatible()
        saveBtn.setOnClickListener {
            sharedPref.edit {
                putString("debrid_provider", debridSpinner.selectedItem?.toString() ?: "")
                putString("debrid_key", debridKeyInput.text.toString())
            }

            AlertDialog.Builder(requireContext())
                .setTitle("Riavvia applicazione")
                .setMessage("Impostazioni salvate. Vuoi riavviare l'applicazione ora per applicare subito le modifiche?")
                .setPositiveButton("Riavvia") { _, _ ->
                    showToast("Saved and Restarting...")
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("Più tardi") { dialog, _ ->
                    showToast("Saved. Restart later to apply changes.")
                    dialog.dismiss()
                    dismiss()
                }
                .show()
        }

        val resetBtn = root.findView<View>("delete_img")
        resetBtn.background = getDrawable("outline_danger")
        resetBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset")
                .setMessage("Questo cancellerà tutte le impostazioni salvate.")
                .setPositiveButton("Reset") { _, _ ->
                    sharedPref.edit(commit = true) { clear() }
                    debridSpinner.setSelection(0, false)
                    debridKeyInput.text.clear()
                    restartApp()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        return root
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
