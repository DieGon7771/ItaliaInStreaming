package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import androidx.core.content.edit

class AltaDefinizioneSettings(
    private val plugin: AltaDefinizionePlugin,
    private val sharedPref: SharedPreferences?,
) : BottomSheetDialogFragment() {
    private var currentVersion: String = sharedPref?.getString("site_version", "v1") ?: "v1"
    private var currentVersionPosition: Int = sharedPref?.getInt("versionPosition", 0) ?: 0

    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
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
        val layoutId = plugin.resources?.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it) ?: return null, container, false)
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Inizializza le view
        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw") ?: "AltaDefinizione"
        
        val labelTw: TextView? = view.findViewByName("label")
        labelTw?.text = getString("label") ?: "Versione Sito"

        // Configura lo spinner delle versioni
        val versionDropdown: Spinner? = view.findViewByName("version_spinner")
        
        val versions = arrayOf("v1", "v2")
        val versionNames = arrayOf(
            getString("v1") ?: "Versione Nuova",
            getString("v2") ?: "Versione Vecchia"
        )
        
        versionDropdown?.adapter = ArrayAdapter(
            requireContext(), 
            android.R.layout.simple_spinner_dropdown_item, 
            versionNames
        )
        versionDropdown?.setSelection(currentVersionPosition)

        versionDropdown?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                currentVersion = versions[position]
                currentVersionPosition = position
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
            }
        }

        // Pulsante salva
        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        saveBtn?.setOnClickListener {
            sharedPref?.edit {
                putString("site_version", currentVersion)
                putInt("versionPosition", currentVersionPosition)
            }
            showToast("Impostazioni salvate. Riavvia Cloudstream per applicare le modifiche")
            dismiss()
        }
    }
}
