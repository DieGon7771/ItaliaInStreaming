package it.dogior.hadEnough

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class NuvioSettings(
    private val activityCtx: Context,
    private val manager: NuvioRepoManager,
    private val pluginResources: android.content.res.Resources? = null,
) {
    private var uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    
    private var ctx: Context = activityCtx
    private var sheetInflater: android.view.LayoutInflater? = null
    private var closeAction: (() -> Unit)? = null
    private var scrollTop: () -> Unit = {}
    private lateinit var container: LinearLayout
    private var busy = false

    
    private var openRepoId: String? = null
    private var query = ""

    
    fun show() {
        val act = activityCtx as? androidx.fragment.app.FragmentActivity
        if (act != null) {
            NuvioSheet(this).show(act.supportFragmentManager, "NuvioSettings")
        } else {
            showAsDialog()
        }
    }

    
    internal fun buildRoot(themedCtx: Context, inflater: android.view.LayoutInflater, dismiss: () -> Unit): View {
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        ctx = themedCtx
        sheetInflater = inflater
        closeAction = dismiss
        openRepoId = null
        query = ""

        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val nested = androidx.core.widget.NestedScrollView(ctx).apply {
            isFillViewport = true
            addView(container)
        }
        scrollTop = { nested.post { nested.scrollTo(0, 0) } }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(TextView(ctx).apply {
            text = "Nuvio"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })
        root.addView(nested)
        render()
        return root
    }

    internal fun release() {
        uiScope.cancel()
        sheetInflater = null
        closeAction = null
    }

    
    internal fun onBack(): Boolean {
        if (openRepoId == null) return false
        openRepoId = null
        render()
        return true
    }

    private fun showAsDialog() {
        ctx = activityCtx
        val scroll = ScrollView(ctx).apply { isFillViewport = false }
        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        scroll.addView(container)
        scrollTop = { scroll.post { scroll.scrollTo(0, 0) } }
        closeAction = null

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Nuvio")
            .setView(scroll)
            .setPositiveButton("Chiudi", null)
            .create()
        dialog.setOnDismissListener { uiScope.cancel() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) onBack() else false
        }

        render()
        dialog.show()
    }

    private fun render() {
        container.removeAllViews()
        val repo = openRepoId?.let { id -> manager.repos().firstOrNull { it.id == id } }
        if (repo == null) {
            openRepoId = null
            renderMain()
        } else {
            renderRepo(repo)
        }
        closeAction?.let { close -> container.addView(button("CHIUDI", GRAY) { close() }) }
        scrollTop()
    }

    // ── main screen ──

    private fun renderMain() {
        container.addView(label("Repository di scraper Nuvio", 17f, bold = true))
        container.addView(
            label(
                "Incolla il link di un repository Nuvio (GitHub o raw).",
                12f, alpha = 0.65f
            )
        )

        val urlInput = EditText(ctx).apply {
            hint = "https://github.com/.../manifest.json"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        container.addView(urlInput)
        container.addView(button("AGGIUNGI REPOSITORY", GREEN) {
            val text = urlInput.text.toString().trim()
            if (text.isEmpty()) toast("Inserisci un link")
            else runSync("Download degli scraper in corso…") { manager.addRepo(text) }
        })

        val repos = manager.repos()
        if (repos.isEmpty()) {
            container.addView(label("Nessun repository aggiunto.", 13f, alpha = 0.65f, topDp = 12))
        }
        repos.forEach { container.addView(repoCard(it)) }

        container.addView(label("Impostazioni generali", 17f, bold = true, topDp = 22))

        container.addView(label("Timeout per scraper (secondi)", 13f, bold = true, topDp = 8))
        container.addView(
            label(
                "Dopo questo tempo lo scraper viene interrotto. Consigliato: 45",
                12f, alpha = 0.65f
            )
        )
        val timeoutInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(manager.timeoutSeconds.toString())
            setSingleLine(true)
        }
        container.addView(timeoutInput)

        container.addView(label("Valori globali (JSON)", 13f, bold = true, topDp = 12))
        container.addView(
            label(
                "Passati a tutti gli scraper come SCRAPER_SETTINGS. Di solito lascia {}.",
                12f, alpha = 0.65f
            )
        )
        val settingsInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(manager.scraperSettingsJson)
        }
        container.addView(settingsInput)

        container.addView(button("SALVA IMPOSTAZIONI", GREEN) {
            val seconds = timeoutInput.text.toString().toIntOrNull()
            if (seconds == null) {
                toast("Timeout non valido")
                return@button
            }
            val raw = settingsInput.text.toString().trim().ifEmpty { "{}" }
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                toast("Il JSON non e' valido")
                return@button
            }
            manager.timeoutSeconds = seconds
            manager.scraperSettingsJson = raw
            toast("Impostazioni salvate")
        })
    }

    private fun repoCard(repo: NuvioRepo): View {
        val enabled = manager.isRepoEnabled(repo.id)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = focusBg(GRAY, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val active = repo.scrapers.count { manager.isEnabled(repo, it) }
        val title = buildString {
            append(repo.name)
            if (repo.version.isNotEmpty()) append("  v").append(repo.version)
        }
        texts.addView(label(title, 15f, bold = true, topDp = 0))
        texts.addView(label(repo.url, 11f, alpha = 0.5f, topDp = 2))
        texts.addView(
            label(
                if (enabled) "$active/${repo.scrapers.size} scraper attivi" else "Repository disattivato",
                12f, alpha = 0.8f, topDp = 2
            )
        )
        head.addView(texts)

        val sw = toggle(enabled)
        head.addView(sw)
        card.addView(head)

        card.isFocusable = true
        card.isClickable = true
        card.setOnClickListener {
            manager.setRepoEnabled(repo.id, !enabled)
            render()
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        row.addView(small("SCRAPER", GREEN) {
            query = ""
            openRepoId = repo.id
            render()
        })
        row.addView(small("AGGIORNA", GREEN) {
            runSync("Aggiornamento in corso…") { manager.refreshRepo(repo.id) }
        })
        row.addView(small("RIMUOVI", RED) {
            AlertDialog.Builder(ctx)
                .setTitle("Rimuovi repository")
                .setMessage("Rimuovere \"${repo.name}\" e i suoi scraper?")
                .setPositiveButton("Rimuovi") { _, _ ->
                    manager.removeRepo(repo.id)
                    render()
                }
                .setNegativeButton("Annulla", null)
                .show()
        })
        card.addView(row)
        return card
    }

    // ── repository screen, one card per scraper ──

    private fun renderRepo(repo: NuvioRepo) {
        val repoOn = manager.isRepoEnabled(repo.id)
        container.addView(label(repo.name, 18f, bold = true, topDp = 4))
        if (repo.description.isNotEmpty()) container.addView(label(repo.description, 12f, alpha = 0.65f))
        if (!repoOn) {
            container.addView(
                label(
                    "Repository disattivato: i suoi scraper non vengono usati.",
                    12f, alpha = 0.9f, topDp = 6
                ).apply { setTextColor(ORANGE) }
            )
        }

        val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val counter = label("", 12f, alpha = 0.7f, topDp = 8)

        fun fill() {
            holder.removeAllViews()
            val q = query.trim().lowercase()
            val list = repo.scrapers.filter {
                q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
            counter.text = "${repo.scrapers.count { manager.isEnabled(repo, it) }}/${repo.scrapers.size} scraper attivi" +
                    if (q.isNotEmpty()) " · ${list.size} trovati" else ""
            if (list.isEmpty()) holder.addView(label("Nessuno scraper trovato.", 13f, alpha = 0.65f, topDp = 10))
            list.forEach { s -> holder.addView(scraperCard(repo, s) { fill() }) }
        }

        val search = EditText(ctx).apply {
            hint = "Cerca scraper…"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    fill()
                }
            })
        }
        container.addView(search)

        val bulk = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        bulk.addView(small("ATTIVA TUTTI", GREEN) { manager.setAllEnabled(repo, true); fill() })
        bulk.addView(small("DISATTIVA TUTTI", RED) { manager.setAllEnabled(repo, false); fill() })
        container.addView(bulk)
        container.addView(counter)
        container.addView(holder)
        fill()
    }

    private fun scraperCard(repo: NuvioRepo, s: NuvioScraper, onChanged: () -> Unit): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = focusBg(GRAY, 12)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(logoView(s))

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        }
        val title = buildString {
            append(s.name)
            if (s.version.isNotEmpty()) append("  v").append(s.version)
        }
        texts.addView(label(title, 15f, bold = true, topDp = 0))
        if (s.author.isNotEmpty()) texts.addView(label("di ${s.author}", 11f, alpha = 0.5f, topDp = 1))
        top.addView(texts)

        if (manager.hasSettings(repo, s)) {
            top.addView(TextView(ctx).apply {
                text = "⚙"
                textSize = 22f
                gravity = Gravity.CENTER
                background = roundBg()
                isFocusable = true
                isClickable = true
                contentDescription = "Impostazioni di ${s.name}"
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) }
                setOnClickListener { openScraperSettings(repo, s) }
            })
        }

        val sw = toggle(manager.isEnabled(repo, s))
        top.addView(sw)
        card.addView(top)

        if (s.description.isNotEmpty()) {
            card.addView(label(s.description, 12f, alpha = 0.75f, topDp = 6).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        val meta = buildList {
            s.types.forEach { add(if (it.equals("movie", true)) "FILM" else if (it.equals("tv", true)) "SERIE" else it.uppercase()) }
            s.languages.forEach { add(it.uppercase()) }
            s.formats.forEach { add(it.uppercase()) }
        }
        if (meta.isNotEmpty()) card.addView(label(meta.joinToString("  ·  "), 11f, alpha = 0.55f, topDp = 4))
        if (s.limited) {
            card.addView(
                label("LIMITATO" + if (s.notes.isNotEmpty()) ": ${s.notes}" else "", 11f, topDp = 4)
                    .apply { setTextColor(ORANGE) }
            )
        } else if (s.notes.isNotEmpty()) {
            card.addView(label(s.notes, 11f, alpha = 0.55f, topDp = 4))
        }

        card.setOnClickListener {
            val now = !manager.isEnabled(repo, s)
            manager.setEnabled(repo, s, now)
            sw.isChecked = now
            onChanged()
        }

        return card
    }

    // ── logo ──

    private fun logoView(s: NuvioScraper): View {
        val size = dp(44)
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(12) }
        }
        if (s.logo.startsWith("http")) {
            val cached = logoCache[s.logo]
            if (cached != null) {
                img.setImageBitmap(cached)
                img.visibility = View.VISIBLE
            } else {
                uiScope.launch {
                    val bmp = withContext(Dispatchers.IO) { downloadBitmap(s.logo) }
                    if (bmp != null) {
                        logoCache[s.logo] = bmp
                        img.setImageBitmap(bmp)
                        img.visibility = View.VISIBLE
                    }
                }
            }
        }
        return img
    }

    private fun downloadBitmap(url: String): Bitmap? = try {
        app.baseClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null
            else r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    } catch (_: Exception) {
        null
    }

    // ── per-scraper settings form (built from the scraper's onSettings()) ──

    private fun openScraperSettings(repo: NuvioRepo, s: NuvioScraper) {
        val cached = manager.blueprint(repo, s)
        if (cached != null) {
            showSettingsForm(repo, s, cached)
            return
        }
        toast("Lettura delle impostazioni…")
        uiScope.launch {
            val res = withContext(Dispatchers.IO) { manager.loadBlueprint(repo, s) }
            val bp = res.items
            if (bp == null) {
                AlertDialog.Builder(ctx)
                    .setTitle("${s.name} · impostazioni")
                    .setMessage("Impossibile leggere le impostazioni di questo scraper.\n\nMotivo: ${res.error ?: "sconosciuto"}")
                    .setPositiveButton("OK", null)
                    .show()
            } else showSettingsForm(repo, s, bp)
        }
    }

    private fun showSettingsForm(repo: NuvioRepo, s: NuvioScraper, blueprint: JSONArray) {
        val values = manager.scraperValues(repo, s)
        val readers = mutableListOf<() -> Unit>()

        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val sc = ScrollView(ctx).apply { addView(form) }

        fun current(item: JSONObject, key: String): Any? =
            if (values.has(key)) values.get(key)
            else if (item.has("defaultValue") && !item.isNull("defaultValue")) item.get("defaultValue") else null

        for (i in 0 until blueprint.length()) {
            val item = blueprint.optJSONObject(i) ?: continue
            val type = item.optString("type")
            val key = item.optString("key")
            val lbl = item.optString("label")
            val desc = item.optString("description")

            when (type) {
                "header" -> form.addView(label(lbl, 15f, bold = true, topDp = 14))
                "info" -> form.addView(label(lbl.ifEmpty { desc }, 12f, alpha = 0.7f, topDp = 6))
                "text" -> {
                    if (key.isEmpty()) continue
                    form.addView(label(lbl, 13f, bold = true, topDp = 10))
                    if (desc.isNotEmpty()) form.addView(label(desc, 11f, alpha = 0.6f))
                    val input = EditText(ctx).apply {
                        hint = item.optString("placeholder")
                        setSingleLine(true)
                        inputType = if (item.optBoolean("isPassword", false))
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT
                        setText(current(item, key)?.toString() ?: "")
                    }
                    form.addView(input)
                    readers.add { values.put(key, input.text.toString()) }
                }
                "toggle" -> {
                    if (key.isEmpty()) continue
                    var state = when (val c = current(item, key)) {
                        is Boolean -> c
                        is String -> c.equals("true", true)
                        else -> false
                    }
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = focusBg(GRAY, 10)
                        isFocusable = true
                        isClickable = true
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(8) }
                    }
                    val t = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    t.addView(label(lbl, 13f, bold = true, topDp = 0))
                    if (desc.isNotEmpty()) t.addView(label(desc, 11f, alpha = 0.6f, topDp = 1))
                    row.addView(t)
                    val sw = toggle(state)
                    row.addView(sw)
                    row.setOnClickListener { state = !state; sw.isChecked = state }
                    form.addView(row)
                    readers.add { values.put(key, state) }
                }
                "select" -> {
                    if (key.isEmpty()) continue
                    val opts = parseOptions(item.optJSONArray("options"))
                    if (opts.isEmpty()) continue
                    var selected = current(item, key)?.toString() ?: opts.first().second
                    form.addView(label(lbl, 13f, bold = true, topDp = 10))
                    if (desc.isNotEmpty()) form.addView(label(desc, 11f, alpha = 0.6f))
                    lateinit var btn: TextView
                    fun caption() = (opts.firstOrNull { it.second == selected }?.first ?: selected) + "   ▾"
                    btn = button(caption(), GREEN) {
                        val labels = opts.map { it.first }.toTypedArray()
                        val idx = opts.indexOfFirst { it.second == selected }.coerceAtLeast(0)
                        AlertDialog.Builder(ctx)
                            .setTitle(lbl)
                            .setSingleChoiceItems(labels, idx) { d, which ->
                                selected = opts[which].second
                                btn.text = caption()
                                d.dismiss()
                            }
                            .setNegativeButton("Annulla", null)
                            .show()
                    }
                    form.addView(btn)
                    readers.add { values.put(key, selected) }
                }
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle("${s.name} · impostazioni")
            .setView(sc)
            .setPositiveButton("Salva") { _, _ ->
                readers.forEach { it() }
                manager.setScraperValues(repo, s, values)
                toast("Impostazioni salvate")
            }
            .setNeutralButton("Ripristina") { _, _ ->
                manager.setScraperValues(repo, s, JSONObject())
                toast("Valori predefiniti ripristinati")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    /** Options can be plain strings or objects like {label, value}. */
    private fun parseOptions(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.opt(i)
            if (o is JSONObject) {
                val value = o.opt("value")?.toString() ?: o.optString("id")
                val lb = o.optString("label").ifEmpty { o.optString("name").ifEmpty { value } }
                out.add(lb to value)
            } else if (o != null && o != JSONObject.NULL) {
                out.add(o.toString() to o.toString())
            }
        }
        return out
    }

    // ── sync ──

    private fun runSync(message: String, action: suspend () -> NuvioSyncReport) {
        if (busy) return
        busy = true
        toast(message)
        uiScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { action() }
                val msg = buildString {
                    append("${report.repo.name}: ${report.downloaded} scraper scaricati")
                    if (report.failed.isNotEmpty()) {
                        append(", non riusciti: ").append(report.failed.joinToString(", "))
                    }
                }
                toast(msg, long = true)
            } catch (e: Exception) {
                toast("Errore: ${e.message ?: e.javaClass.simpleName}", long = true)
            } finally {
                busy = false
                render()
            }
        }
    }

    // ── small UI helpers ──

    private fun label(
        text: String,
        size: Float,
        bold: Boolean = false,
        alpha: Float = 1f,
        topDp: Int = 4,
    ) = TextView(ctx).apply {
        this.text = text
        textSize = size
        this.alpha = alpha
        if (bold) setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color)
        background = focusBg(color, 8)
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun small(text: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color)
        background = focusBg(color, 8)
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        }
        setOnClickListener { onClick() }
    }

    private fun box(color: Int, fillAlpha: Int, strokeAlpha: Int, strokeDp: Int, radiusDp: Int) =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.argb(fillAlpha, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(
                dp(strokeDp),
                Color.argb(strokeAlpha, Color.red(color), Color.green(color), Color.blue(color))
            )
        }

    private fun focusBg(color: Int, radiusDp: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), box(FOCUS, 70, 255, 4, radiusDp))
        addState(intArrayOf(android.R.attr.state_pressed), box(color, 70, 255, 2, radiusDp))
        addState(intArrayOf(), box(color, 20, 110, 2, radiusDp))
    }

    private fun toggle(checked: Boolean): android.widget.CompoundButton {
        var sw: android.widget.CompoundButton? = null
        try {
            sw = com.google.android.material.materialswitch.MaterialSwitch(ctx)
        } catch (_: Throwable) {
        }
        val res = pluginResources
        if (sw == null && res != null) {
            try {
                val id = res.getIdentifier("nuvio_switch", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
                if (id != 0) {
                    sw = (sheetInflater ?: android.view.LayoutInflater.from(ctx)).inflate(res.getLayout(id), null, false)
                        as? android.widget.CompoundButton
                }
            } catch (_: Exception) {
            }
        }
        val s = sw ?: android.widget.Switch(ctx).apply {
            minimumHeight = 0
            showText = false
            switchMinWidth = dp(28)
        }
        s.isChecked = checked
        s.isFocusable = false
        s.isClickable = false
        s.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return s
    }

    private fun roundBg() = StateListDrawable().apply {
        fun circle(fill: Int, stroke: Int, strokeDp: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }
        addState(intArrayOf(android.R.attr.state_focused), circle(Color.argb(80, 255, 255, 255), FOCUS, 3))
        addState(intArrayOf(android.R.attr.state_pressed), circle(Color.argb(90, 255, 255, 255), 0, 0))
        addState(intArrayOf(), circle(Color.argb(45, 255, 255, 255), 0, 0))
    }

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        val GREEN: Int = Color.parseColor("#7CFF9D")
        val RED: Int = Color.parseColor("#FF7F7F")
        val GRAY: Int = Color.parseColor("#FFFFFF")
        val ORANGE: Int = Color.parseColor("#FFB74D")
        val FOCUS: Int = Color.parseColor("#FFEB3B")
        val logoCache = HashMap<String, Bitmap>()
    }
}
