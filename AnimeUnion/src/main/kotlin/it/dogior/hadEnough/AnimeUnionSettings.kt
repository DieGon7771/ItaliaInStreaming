package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

class AnimeUnionSettings(
    private val plugin: AnimeUnionPlugin,
    private val sharedPref: SharedPreferences?,
) : BottomSheetDialogFragment() {

    private var showTags: Boolean = sharedPref?.getBoolean(AnimeUnionPlugin.KEY_LANG_TAGS, false) ?: false

    private fun View.makeTvCompatible() {
        setPadding(paddingLeft + 10, paddingTop + 10, paddingRight + 10, paddingBottom + 10)
        background = getDrawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    @SuppressLint("DiscouragedApi")
    private fun getString(name: String): String? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) res.getString(id) else null
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val res = plugin.resources ?: return null
        val layoutId = res.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (layoutId != 0) inflater.inflate(res.getLayout(layoutId), container, false) else null
    }

    private fun makeSwitch(checked: Boolean): CompoundButton {
        val ctx = requireContext()
        val sw: CompoundButton = try {
            com.google.android.material.materialswitch.MaterialSwitch(ctx)
        } catch (_: Throwable) {
            android.widget.Switch(ctx)
        }
        sw.isChecked = checked
        return sw
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<TextView>("header_tw")?.text = getString("header_tw")
        view.findViewByName<TextView>("tags_label")?.text = getString("tags_label")
        view.findViewByName<TextView>("tags_description")?.text = getString("tags_description")

        val container: FrameLayout? = view.findViewByName("tags_switch_container")
        val sw = makeSwitch(showTags)
        sw.setOnCheckedChangeListener { _, isChecked -> showTags = isChecked }
        container?.addView(sw)

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))
        saveBtn?.setOnClickListener {
            sharedPref?.edit {
                putBoolean(AnimeUnionPlugin.KEY_LANG_TAGS, showTags)
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Salva e riavvia")
                .setMessage("Impostazioni salvate. Vuoi riavviare l'app per applicarle?")
                .setPositiveButton("Sì") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { _, _ ->
                    showToast("Impostazioni salvate. Riavvia l'app per applicarle.")
                    dismiss()
                }
                .show()
        }
    }

    private fun restartApp() {
        try {
            val context = requireContext().applicationContext
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            if (componentName != null) {
                context.startActivity(Intent.makeRestartActivityTask(componentName))
                Runtime.getRuntime().exit(0)
            } else {
                showToast("Impossibile riavviare l'app")
            }
        } catch (e: Exception) {
            showToast("Errore nel riavvio: ${e.message}")
        }
    }
}
