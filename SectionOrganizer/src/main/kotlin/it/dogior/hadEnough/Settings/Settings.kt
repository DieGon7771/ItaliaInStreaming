package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import androidx.core.net.toUri

class UltimaSettings(val plugin: UltimaPlugin) : BottomSheetDialogFragment() {
    private val sm = UltimaStorageManager
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private val packageName = "it.dogior.hadEnough"

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", packageName)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", packageName)
        return res.getDrawable(id, null) ?: throw Exception("Unable to find drawable $name")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", packageName)
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", packageName)
        this.background = res.getDrawable(outlineId, null)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val settings = getLayout("settings", inflater, container)

        val saveBtn = settings.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.makeTvCompatible()
        saveBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Riavvio necessario")
                .setMessage("Modifiche salvate. Vuoi riavviare l'app per applicarle?")
                .setPositiveButton("Sì") { _, _ ->
                    sm.save()
                    showToast("Salvato. Riavvio...")
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { dialog, _ ->
                    sm.save()
                    showToast("Salvato. Riavvia dopo per applicare le modifiche.")
                    dialog.dismiss()
                    dismiss()
                }.show()
        }

        val configBtn = settings.findView<ImageView>("config_img")
        configBtn.setImageDrawable(getDrawable("edit_icon"))
        configBtn.makeTvCompatible()
        configBtn.setOnClickListener {
            UltimaConfigureExtensions(plugin).show(
                activity?.supportFragmentManager ?: throw Exception("Impossibile aprire le impostazioni"),
                ""
            )
            dismiss()
        }

        val reorderBtn = settings.findView<ImageView>("reorder_img")
        reorderBtn.setImageDrawable(getDrawable("edit_icon"))
        reorderBtn.makeTvCompatible()
        reorderBtn.setOnClickListener {
            UltimaReorder(plugin).show(
                activity?.supportFragmentManager ?: throw Exception("Impossibile aprire le impostazioni"),
                ""
            )
            dismiss()
        }

        val guideIcon = settings.findView<ImageView>("guide_icon")
        guideIcon.setImageDrawable(getDrawable("ic_eye"))
        guideIcon.makeTvCompatible()
        guideIcon.setOnClickListener {
            val url = "https://github.com/DieGon7771/ItaliaInStreaming/blob/master/guide/README_SectionOrganizer.md"
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        val deleteBtn = settings.findView<TextView>("delete_img")
        deleteBtn.text = "Reset"
        deleteBtn.makeTvCompatible()
        deleteBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset SectionOrganizer")
                .setMessage("Questo cancellerà tutte le sezioni selezionate.")
                .setPositiveButton("Reset") { _, _ ->
                    sm.deleteAllData()
                    sm.save()
                    showToast("Sezioni cancellate")
                    dismiss()
                }
                .setNegativeButton("Annulla", null)
                .show()
                .setDefaultFocus()
        }

        return settings
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.component?.let {
            context.startActivity(Intent.makeRestartActivityTask(it))
            Runtime.getRuntime().exit(0)
        }
    }
}
