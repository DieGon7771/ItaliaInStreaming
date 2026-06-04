package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import org.json.JSONArray
import org.json.JSONObject

class SettingsSectionsFragment(
    private val plugin: StremioXPlugin,
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {
    private val PREF_KEY_SECTIONS = "stremio_sections"
    private val PREF_KEY_OLD = "stremio_saved_links"

    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    private val sections = mutableListOf<SectionConfig>()

    private fun str(name: String, default: String, vararg args: Any?): String {
        val id = res.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        val template = if (id != 0) res.getString(id) else default
        return if (args.isNotEmpty()) String.format(template, *args) else template
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = res.getIdentifier("settings_sections", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(layoutId), container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        migrateOldFormat()

        sections.clear()
        sections.addAll(loadSections())
        rebuildRows(view)

        val addBtn = view.findViewByName<View>("add_section_btn")
        addBtn?.let { btn ->
            val greenDrawable = getDrawable("outline_green")
            if (greenDrawable != null) btn.background = greenDrawable
            else applyOutlineBackground(btn)
            btn.setOnClickListener { showAddDialog(view) }
        }

        setupSaveButton(view)
    }

    private fun setupSaveButton(view: View) {
        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        if (saveBtn == null) return
        saveBtn.background = getDrawable("outline")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.setOnClickListener {
            saveSections(sections)
            Toast.makeText(requireContext(), str("sections_saved", "Sezioni salvate"), Toast.LENGTH_SHORT).show()
            AlertDialog.Builder(requireContext())
                .setTitle(str("restart_title", "Riavvia applicazione"))
                .setMessage(str("restart_message", "Riavviare per applicare le modifiche?"))
                .setPositiveButton(str("restart_positive", "Riavvia")) { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton(str("restart_negative", "Più tardi"), null)
                .show()
        }
    }

    // ── migration from old format ──

    private fun migrateOldFormat() {
        val oldJson = sharedPref.getString(PREF_KEY_OLD, null) ?: return
        val newJson = sharedPref.getString(PREF_KEY_SECTIONS, null)
        if (newJson != null) return

        val oldArr = try { JSONArray(oldJson) } catch (_: Exception) { return }
        val migrated = mutableListOf<SectionConfig>()

        for (i in 0 until oldArr.length()) {
            val obj = oldArr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val link = obj.optString("link", "")
            val type = obj.optString("type", "StremioX")
            if (link.isBlank()) continue

            val sectionName = name.ifBlank { str("migration_section_name", "Sezione %d", migrated.size + 1) }
            migrated.add(
                SectionConfig(
                    id = System.currentTimeMillis() + i,
                    name = sectionName,
                    catalogUrl = if (type == "StremioC") link else null,
                    streamAddons = if (type == "StremioX") {
                        listOf(StreamAddonConfig(
                            id = System.currentTimeMillis() + i,
                            name = name.ifBlank { str("migration_addon_name", "Stream") },
                            url = link,
                            type = "https"
                        ))
                    } else emptyList()
                )
            )
        }

        if (migrated.isNotEmpty()) {
            saveSections(migrated)
            sharedPref.edit { remove(PREF_KEY_OLD) }
            Log.d("SettingsSectionsFragment", "Migrated ${migrated.size} old links to sections")
        }
    }

    // ── persist sections ──

    private fun loadSections(): List<SectionConfig> {
        val json = sharedPref.getString(PREF_KEY_SECTIONS, null) ?: return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val list = mutableListOf<SectionConfig>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val addonsArr = obj.optJSONArray("streamAddons")
            val addons = mutableListOf<StreamAddonConfig>()
            if (addonsArr != null) {
                for (j in 0 until addonsArr.length()) {
                    val ao = addonsArr.optJSONObject(j) ?: continue
                    addons.add(StreamAddonConfig(
                        id = ao.optLong("id", System.currentTimeMillis()),
                        name = ao.optString("name", ""),
                        url = ao.optString("url", ""),
                        type = ao.optString("type", "https")
                    ))
                }
            }
            list.add(SectionConfig(
                id = obj.optLong("id", System.currentTimeMillis()),
                name = obj.optString("name", ""),
                catalogUrl = obj.optString("catalogUrl", "").ifEmpty { null },
                streamAddons = addons
            ))
        }
        return list
    }

    private fun saveSections(list: List<SectionConfig>) {
        val arr = JSONArray()
        for (s in list) {
            val addonsArr = JSONArray()
            for (a in s.streamAddons) {
                addonsArr.put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("url", a.url)
                    put("type", a.type)
                })
            }
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("catalogUrl", s.catalogUrl ?: "")
                put("streamAddons", addonsArr)
            })
        }
        sharedPref.edit { putString(PREF_KEY_SECTIONS, arr.toString()) }
    }

    // ── build UI rows ──

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun rebuildRows(view: View) {
        val container: LinearLayout? = view.findViewByName("sections_container")
        container?.removeAllViews()

        for (section in sections) {
            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(10) }
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
                background = getDrawable("outline")
            }

            // header: name + count
            val headerRow = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dpToPx(44)
            }

            val nameText = TextView(requireContext()).apply {
                text = section.name
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dpToPx(8), 0, dpToPx(8), 0)
            }
            headerRow.addView(nameText)

            val addonCount = TextView(requireContext()).apply {
                text = str("addon_count", "%d/5 addon", section.streamAddons.size)
                textSize = 12f
                setPadding(dpToPx(8), 0, dpToPx(8), 0)
            }
            headerRow.addView(addonCount)
            row.addView(headerRow)

            // subheader: catalog type
            val catalogType = TextView(requireContext()).apply {
                text = if (section.catalogUrl != null) str("catalog_custom", "Catalogo") else str("catalog_tmdb", "Catalogo: TMDB")
                textSize = 12f
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), 0)
            }
            row.addView(catalogType)

            // action buttons
            val actionsRow = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(10) }
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }

            val editBtn = TextView(requireContext()).apply {
                text = str("edit_btn", "MODIFICA")
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(36), 1f).apply {
                    rightMargin = dpToPx(4)
                }
                val greenDrawable = getDrawable("outline_green")
                if (greenDrawable != null) background = greenDrawable
                else applyOutlineBackground(this)
                setTextColor(Color.parseColor("#997CFF9D"))
                setOnClickListener { showEditDialog(requireView(), section) }
            }
            actionsRow.addView(editBtn)

            val deleteBtn = TextView(requireContext()).apply {
                text = str("delete_btn", "RIMUOVI")
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(36), 1f).apply {
                    leftMargin = dpToPx(4)
                }
                val dangerDrawable = getDrawable("outline_danger")
                if (dangerDrawable != null) background = dangerDrawable
                else applyOutlineBackground(this)
                setTextColor(Color.parseColor("#FFFF7F7F"))
                setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle(str("delete_section_title", "Rimuovi sezione"))
                        .setMessage(str("delete_section_message", "Rimuovere \"%s\"?", section.name))
                        .setPositiveButton(str("delete_confirm", "Rimuovi")) { _, _ ->
                            sections.remove(section)
                            rebuildRows(requireView())
                        }
                        .setNegativeButton(str("cancel", "Annulla"), null)
                        .show()
                }
            }
            actionsRow.addView(deleteBtn)
            row.addView(actionsRow)

            container?.addView(row)
        }
    }

    // ── shared section editor (programmatic, like StreamITA) ──

    private fun showSectionEditor(view: View, existing: SectionConfig?) {
        val ctx = context ?: return
        val outer = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16))
        }

        val titleLabel = TextView(ctx).apply {
            text = str("editor_name_label", "Nome sezione")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        layout.addView(titleLabel)

        val nameInput = EditText(ctx).apply {
            hint = str("editor_name_hint", "Es. Film, Serie TV\u2026")
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.name ?: "")
            setPadding(0, dpToPx(8), 0, dpToPx(16))
        }
        layout.addView(nameInput)

        val catalogLabel = TextView(ctx).apply {
            text = str("editor_catalog_label", "URL catalogo (opzionale \u2014 vuoto = TMDB)")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        layout.addView(catalogLabel)

        val catalogInput = EditText(ctx).apply {
            hint = str("editor_catalog_hint", "https://v3-cinemeta.strem.io")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.catalogUrl ?: "")
        }
        layout.addView(catalogInput)

        val addonsLabel = TextView(ctx).apply {
            text = str("editor_addons_label", "Stream addon (massimo 5)")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dpToPx(16), 0, dpToPx(8))
        }
        layout.addView(addonsLabel)

        val addonSlots = mutableListOf<Pair<EditText, EditText>>()
        val existingAddons = existing?.streamAddons ?: emptyList()
        for (i in 0 until 5) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                background = getDrawable("outline")
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dpToPx(8)
                layoutParams = lp
            }

            val label = TextView(ctx).apply {
                text = str("editor_addon_label", "Addon #%d", i + 1)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }
            row.addView(label)

            val etName = EditText(ctx).apply {
                hint = str("editor_addon_name_hint", "Nome")
                inputType = InputType.TYPE_CLASS_TEXT
                if (i < existingAddons.size) setText(existingAddons[i].name)
            }
            row.addView(etName)

            val etUrl = EditText(ctx).apply {
                hint = str("editor_addon_url_hint", "https://example.com (manifest.json)")
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                if (i < existingAddons.size) setText(existingAddons[i].url)
            }
            row.addView(etUrl)

            addonSlots.add(etName to etUrl)
            layout.addView(row)
        }

        outer.addView(layout)

        val title = if (existing != null) str("editor_title_edit", "Modifica Sezione") else str("editor_title_new", "Nuova Sezione")
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(outer)
            .setPositiveButton(str("editor_ok", "OK"), null)
            .setNegativeButton(str("editor_cancel", "Annulla"), null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = nameInput.text.toString().trim()
                        val catalogUrl = catalogInput.text.toString().trim().ifBlank { null }
                        if (name.isEmpty()) {
                            Toast.makeText(ctx, str("editor_name_required", "Inserisci un nome"), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val addons = mutableListOf<StreamAddonConfig>()
                        for ((idx, pair) in addonSlots.withIndex()) {
                            val aname = pair.first.text.toString().trim()
                            val aurl = pair.second.text.toString().trim()
                            if (aurl.isNotBlank()) {
                                addons.add(StreamAddonConfig(
                                    id = if (existing != null && idx < existingAddons.size) existingAddons[idx].id
                                          else System.currentTimeMillis() + idx,
                                    name = aname.ifBlank { str("editor_addon_default_name", "Addon %d", idx + 1) },
                                    url = aurl,
                                    type = "https"
                                ))
                            }
                        }

                        if (existing != null) {
                            val idx = sections.indexOfFirst { it.id == existing.id }
                            if (idx >= 0) {
                                sections[idx] = existing.copy(
                                    name = name,
                                    catalogUrl = catalogUrl,
                                    streamAddons = addons
                                )
                            }
                        } else {
                            sections.add(SectionConfig(
                                id = System.currentTimeMillis(),
                                name = name,
                                catalogUrl = catalogUrl,
                                streamAddons = addons
                            ))
                        }
                        rebuildRows(view)
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun showAddDialog(view: View) = showSectionEditor(view, null)
    private fun showEditDialog(view: View, section: SectionConfig) = showSectionEditor(view, section)

    // ── helpers ──

    private fun <T : View> View.findViewByName(name: String): T? {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { findViewById(it) }
    }

    private fun getDrawable(name: String): android.graphics.drawable.Drawable? {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { androidx.core.content.res.ResourcesCompat.getDrawable(res, it, null) }
    }

    private fun applyOutlineBackground(view: View) {
        view.background = getDrawable("outline")
    }

    private fun dpToPx(dp: Int): Int =
        (dp * requireContext().resources.displayMetrics.density).toInt()

    private fun restartApp() {
        val context = requireContext().applicationContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
