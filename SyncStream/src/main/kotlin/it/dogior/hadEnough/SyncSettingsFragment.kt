@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.graphics.Typeface
import android.view.Gravity
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.app
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

    protected fun focusBackground(): Drawable {
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(0x22FFFFFF)
            setStroke(dp(2), 0x88FFFFFF.toInt())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    protected fun makeSwitch(ctx: Context): CompoundButton {
        return try {
            Class.forName("com.google.android.material.materialswitch.MaterialSwitch")
                .getConstructor(Context::class.java).newInstance(ctx) as CompoundButton
        } catch (e: Throwable) {
            Switch(ctx)
        }
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    protected fun addSwitchRow(parent: LinearLayout, label: String, initial: Boolean, onChange: (Boolean) -> Unit): CompoundButton {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = focusBackground()
        }
        val tv = TextView(ctx).apply {
            text = label
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = makeSwitch(ctx).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(tv)
        row.addView(sw)
        row.setOnClickListener { if (sw.isEnabled) sw.toggle() }
        parent.addView(row)
        return sw
    }

    protected fun addCategoryTable(parent: LinearLayout) {
        val ctx = parent.context
        val title = TextView(ctx).apply {
            text = "Cosa sincronizzare"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(14), dp(4), dp(2))
        }
        parent.addView(title)
        fun head(t: String, w: Float, g: Int) = TextView(ctx).apply {
            text = t; textSize = 12f; alpha = 0.7f; gravity = g
            layoutParams = LinearLayout.LayoutParams(if (w == 0f) dp(64) else 0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(0))
            addView(head("", 1f, Gravity.START))
            addView(head("Backup", 0f, Gravity.CENTER))
            addView(head("Ripristino", 0f, Gravity.CENTER))
        }
        parent.addView(header)
        val names = mapOf(
            BackupUtils.CAT_RESUME to "Continua a guardare e progressi",
            BackupUtils.CAT_LIBRARY to "Preferiti e liste",
            BackupUtils.CAT_SEARCH to "Cronologia ricerche",
            BackupUtils.CAT_SUBS to "Impostazioni sottotitoli",
            BackupUtils.CAT_SETTINGS to "Impostazioni dell'app",
            BackupUtils.CAT_DOWNLOADS to "Download (sperimentale)",
            BackupUtils.CAT_EXT to "Repository"
        )
        val warnings = mapOf(
            BackupUtils.CAT_DOWNLOADS to "Sincronizza solo l'elenco dei download, non i file video. Sugli altri dispositivi possono comparire download senza file."
        )
        BackupUtils.ALL_CATS.forEach { cat ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            row.addView(TextView(ctx).apply {
                text = names.getValue(cat); textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            listOf(true, false).forEach { isBackup ->
                val box = FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT) }
                val sw = makeSwitch(ctx).apply {
                    isChecked = BackupUtils.catEnabled(cat, isBackup)
                    background = focusBackground()
                    var silent = false
                    setOnCheckedChangeListener { b, c ->
                        if (silent) return@setOnCheckedChangeListener
                        if (c && cat in BackupUtils.EXPERIMENTAL_CATS) {
                            AlertDialog.Builder(ctx)
                                .setTitle("Funzione sperimentale")
                                .setMessage("Questa opzione è instabile e ancora in prova.\n\n${warnings[cat]}\n\nVuoi attivarla?")
                                .setPositiveButton("Attiva") { _, _ -> BackupUtils.setCat(cat, isBackup, true) }
                                .setNegativeButton("Annulla") { _, _ -> silent = true; b.isChecked = false; silent = false }
                                .setCancelable(false)
                                .show()
                        } else BackupUtils.setCat(cat, isBackup, c)
                    }
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                }
                box.addView(sw)
                row.addView(box)
            }
            parent.addView(row)
        }
    }

    protected fun addAutoUpdate(parent: LinearLayout) {
        val ctx = parent.context
        val intervals = listOf(20_000L, 30_000L, 60_000L)
        val options = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), dp(4)) }
        val chips = intervals.map { ms ->
            TextView(ctx).apply {
                text = if (ms >= 60_000L) "1 min" else "${ms / 1000} sec"
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            }
        }
        fun refresh() {
            var cur = getKey<Long>("auto_pull_seconds") ?: 30_000L
            if (cur !in intervals) { cur = 20_000L; setKey("auto_pull_seconds", cur) }
            chips.forEachIndexed { i, tv ->
                tv.background = getDrawable("outline")
                tv.alpha = if (intervals[i] == cur) 1f else 0.45f
                tv.setTypeface(null, if (intervals[i] == cur) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        chips.forEachIndexed { i, tv ->
            tv.setOnClickListener {
                if (!tv.isEnabled) return@setOnClickListener
                setKey("auto_pull_seconds", intervals[i]); refresh()
            }
            options.addView(tv)
        }
        refresh()
        val on = getKey<Boolean>("auto_pull_enabled") ?: false
        options.visibility = if (on) View.VISIBLE else View.GONE
        addSwitchRow(parent, "Aggiornamento automatico", on) {
            setKey("auto_pull_enabled", it)
            options.visibility = if (it) View.VISIBLE else View.GONE
        }
        parent.addView(options)
    }

    protected fun disableAll(v: View) {
        v.alpha = 0.4f
        lock(v)
    }

    private fun lock(v: View) {
        v.isEnabled = false
        v.isClickable = false
        v.isFocusable = false
        if (v is ViewGroup) for (i in 0 until v.childCount) lock(v.getChildAt(i))
    }

    protected fun showDisabledBanner(view: View, text: String) {
        val b = view.findView<TextView>("disabled_banner")
        b.text = text
        b.visibility = View.VISIBLE
        b.applyOutlineBackground()
    }

    protected fun setupSaveButton(view: View, onClick: () -> Unit) {
        val saveBtn: ImageButton? = view.findView("save_btn")
        saveBtn?.applyOutlineBackground()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))
        saveBtn?.setOnClickListener { onClick() }
    }
}

class SyncSettingsFragment : SyncBaseSettingsFragment() {

    companion object {
        private const val GUIDE_URL =
            "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/guide/README_SyncStream.md"
        private var guideCache: String? = null
    }

    override val layoutName: String = "settings"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val driveOn = ApiUtils.isLoggedIn() && ApiUtils.isDrive()
        val githubOn = ApiUtils.isLoggedIn() && !ApiUtils.isDrive()

        view.findView<TextView>("header_status").text = when {
            driveOn -> "Sincronizzazione attiva con Google Drive"
            githubOn -> "Sincronizzazione attiva con GitHub"
            else -> "Scegli dove sincronizzare"
        }

        val driveCard = view.findView<View>("drive_card")
        val githubCard = view.findView<View>("github_card")
        val guideCard = view.findView<View>("guide_card")
        driveCard.applyOutlineBackground()
        githubCard.applyOutlineBackground()
        guideCard.applyOutlineBackground()

        view.findView<ImageView>("drive_icon_view").setImageDrawable(getDrawable("drive_icon"))
        val ghIcon = view.findView<ImageView>("github_icon_view")
        ghIcon.setImageDrawable(getDrawable("github_icon"))
        ghIcon.setColorFilter(view.findView<TextView>("github_title").currentTextColor)

        view.findView<TextView>("drive_summary").text = when {
            driveOn -> "Collegato · tocca per gestire"
            githubOn -> "Disattivato: esci da GitHub per usarlo"
            else -> "Backup sul tuo Google Drive, senza token"
        }
        view.findView<TextView>("github_summary").text = when {
            githubOn -> "Collegato · tocca per gestire"
            driveOn -> "Disattivato: esci da Google Drive per usarlo"
            else -> "Backup tramite progetto GitHub (token)"
        }
        if (githubOn) driveCard.alpha = 0.4f
        if (driveOn) githubCard.alpha = 0.4f

        val fm = requireActivity().supportFragmentManager
        driveCard.setOnClickListener { SyncDriveFragment().show(fm, "Drive") }
        githubCard.setOnClickListener { SyncLoginFragment().show(fm, "Login") }
        guideCard.setOnClickListener { showGuide() }
    }

    private fun cleanInline(line: String): String = line
        .replace(Regex("\\*\\*(.+?)\\*\\*")) { it.groupValues[1] }
        .replace(Regex("`([^`]+)`")) { it.groupValues[1] }
        .replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)")) { "${it.groupValues[1]} (${it.groupValues[2]})" }

    private fun renderGuide(markdown: String): CharSequence {
        val out = SpannableStringBuilder()
        val heading = Regex("^#{1,6}\\s+")
        var lastBlank = true

        markdown.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("```") -> Unit
                line.isEmpty() || line == "---" || line == "***" -> {
                    if (!lastBlank) {
                        out.append('\n')
                        lastBlank = true
                    }
                }
                heading.containsMatchIn(line) -> {
                    if (!lastBlank) out.append('\n')
                    val start = out.length
                    out.append(cleanInline(line.replace(heading, "")))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1.15f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.append('\n')
                    lastBlank = false
                }
                else -> {
                    val body = cleanInline(line)
                    if (body.startsWith("- ") || body.startsWith("* ")) {
                        out.append("• ").append(body.substring(2))
                    } else {
                        out.append(body)
                    }
                    out.append('\n')
                    lastBlank = false
                }
            }
        }
        return out
    }

    private fun showGuide() {
        val ctx = requireContext()
        val text = TextView(ctx).apply {
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        }
        val scroll = ScrollView(ctx).apply {
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.62f).toInt()
            )
        }
        scroll.addView(
            text,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scroll.setOnKeyListener { _, keyCode, event ->
            val dir = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> 0
            }
            if (event.action != KeyEvent.ACTION_DOWN || dir == 0) return@setOnKeyListener false
            if (!scroll.canScrollVertically(dir)) return@setOnKeyListener false
            scroll.smoothScrollBy(0, dir * dp(140))
            true
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(scroll)
        }

        val cached = guideCache
        text.text = cached?.let { renderGuide(it) } ?: "Caricamento guida…"

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Guida rapida")
            .setView(content)
            .setPositiveButton("Chiudi", null)
            .create()
        dialog.show()
        scroll.nextFocusDownId = android.R.id.button1

        if (cached != null) return

        CoroutineScope(Dispatchers.IO).launch {
            val body = try {
                app.get(GUIDE_URL).text
            } catch (e: Exception) {
                Log.e(TAG, "guida: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) {
                if (body.isNullOrBlank()) {
                    text.text = "Impossibile scaricare la guida. Controlla la connessione e tocca di nuovo la card."
                } else {
                    guideCache = body
                    text.text = renderGuide(body)
                }
            }
        }
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
        val deviceBox = view.findView<LinearLayout>("device_switch_container")
        var backupOn = getKey<String>("backup_device") == "true"
        var restoreOn = getKey<String>("restore_device") == "true"
        addSwitchRow(deviceBox, "Backup su GitHub (invia i dati di questo dispositivo)", backupOn) { backupOn = it }
        addSwitchRow(deviceBox, "Ripristino da GitHub (ricevi i dati degli altri dispositivi)", restoreOn) { restoreOn = it }

        val resetBtn = view.findView<TextView>("reset_btn")
        val dangerDrawable = getDrawable("outline_danger")
        if (dangerDrawable != null) resetBtn.background = dangerDrawable else resetBtn.applyOutlineBackground()
        resetBtn.setTextColor(Color.parseColor("#FFFF7F7F"))
        resetBtn.visibility = if (canResetGithub()) View.VISIBLE else View.GONE
        resetBtn.setOnClickListener {
            setKey("sync_token", "")
            setKey("sync_project_num", "")
            setKey("sync_project_id", "")
            showToast("Disconnesso da GitHub")
            dismiss()
        }

        setupSaveButton(view) {
            save(tokenInput, prNumInput, backupOn, restoreOn)
            resetBtn.visibility = if (canResetGithub()) View.VISIBLE else View.GONE
        }

        val extra = view.findView<LinearLayout>("github_extra")
        extra.applyOutlineBackground()
        addCategoryTable(extra)
        addAutoUpdate(extra)

        if (ApiUtils.isLoggedIn() && ApiUtils.isDrive()) {
            showDisabledBanner(view, "Stai usando Google Drive. Per usare GitHub esci prima da Google Drive.")
            val root = view as ViewGroup
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                if (c.id != res.getIdentifier("disabled_banner", "id", BuildConfig.LIBRARY_PACKAGE_NAME)) disableAll(c)
            }
        }
    }

    private fun canResetGithub(): Boolean {
        val token = getKey<String>("sync_token")
        val num = getKey<String>("sync_project_num")
        if (token.isNullOrEmpty() || num.isNullOrEmpty()) return false
        return !(ApiUtils.isLoggedIn() && ApiUtils.isDrive())
    }

    private fun save(
        tokenInput: EditText,
        prNumInput: EditText,
        backupOn: Boolean,
        restoreOn: Boolean
    ) {
        val token = tokenInput.text.trim().toString()
        val prNum = prNumInput.text.toString()
        if (token.isEmpty() || prNum.isEmpty()) {
            showToast("Inserisci token e numero del progetto")
            return
        }
        setKey("sync_provider", "github")
        setKey("sync_token", token)
        setKey("sync_project_num", prNum)
        setKey("backup_device", "$backupOn")
        setKey("restore_device", "$restoreOn")

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
                    showToast("Errore di sincronizzazione: ${e.message}")
                }
            }
        }
    }

    private fun inflateLoading(): View {
        val id = res.getIdentifier("loading", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return LayoutInflater.from(requireContext()).inflate(res.getLayout(id), null, false)
    }
}

class SyncDriveFragment : SyncBaseSettingsFragment() {

    override val layoutName: String = "drive"

    private var loginJob: Job? = null

    override fun onDestroyView() {
        loginJob?.cancel()
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findView<View>("drive_card").applyOutlineBackground()
        val statusTv = view.findView<TextView>("drive_status")
        val loginBtn = view.findView<LinearLayout>("drive_login_btn")
        val loginText = view.findView<TextView>("drive_login_text")
        val loginIcon = view.findView<ImageView>("drive_login_icon")
        val box = view.findView<LinearLayout>("drive_switch_container")
        loginIcon.setImageDrawable(getDrawable("google_icon"))

        var backupOn = (getKey<String>("backup_device") ?: "true") == "true"
        var restoreOn = (getKey<String>("restore_device") ?: "true") == "true"

        addSwitchRow(box, "Backup su Drive (invia i dati di questo dispositivo)", backupOn) { backupOn = it }
        addSwitchRow(box, "Ripristino da Drive (ricevi i dati degli altri dispositivi)", restoreOn) { restoreOn = it }
        addCategoryTable(box)
        addAutoUpdate(box)

        val resetBtn = view.findView<TextView>("reset_btn")
        val dangerDrawable = getDrawable("outline_danger")
        if (dangerDrawable != null) resetBtn.background = dangerDrawable else resetBtn.applyOutlineBackground()
        resetBtn.setTextColor(Color.parseColor("#FFFF7F7F"))
        resetBtn.setOnClickListener {
            GoogleDriveApi.logout()
            showToast("Disconnesso da Google Drive")
            dismiss()
        }

        fun refresh() {
            val logged = ApiUtils.isDrive() && GoogleDriveApi.isLoggedIn()
            statusTv.text = when {
                !GoogleDriveApi.isConfigured() -> "Google Drive non è configurato in questa versione del plugin"
                logged -> "Collegato a Google Drive"
                else -> "Non collegato"
            }
            loginText.text = if (logged) "ACCEDI DI NUOVO" else "ACCEDI CON GOOGLE"
            resetBtn.visibility = if (logged) View.VISIBLE else View.GONE
        }
        refresh()

        loginBtn.applyOutlineBackground()
        fun saveAll() {
            setKey("backup_device", "$backupOn")
            setKey("restore_device", "$restoreOn")
        }
        loginBtn.setOnClickListener { saveAll(); startLogin(backupOn, restoreOn) { refresh() } }

        setupSaveButton(view) {
            saveAll()
            val logged = ApiUtils.isDrive() && GoogleDriveApi.isLoggedIn()
            showToast(if (logged) "Impostazioni salvate" else "Salvato. Ora accedi con Google")
            if (logged) dismiss()
        }

        if (ApiUtils.isLoggedIn() && !ApiUtils.isDrive()) {
            showDisabledBanner(view, "Stai usando GitHub. Per usare Google Drive esci prima da GitHub.")
            val root = view as ViewGroup
            val bannerId = res.getIdentifier("disabled_banner", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                if (c.id != bannerId) disableAll(c)
            }
        }
    }

    private fun startLogin(backup: Boolean, restore: Boolean, onDone: () -> Unit) {
        if (!GoogleDriveApi.isConfigured()) {
            showToast("Google Drive non è configurato in questa versione del plugin")
            return
        }
        val ctx = requireContext()
        loginJob?.cancel()
        loginJob = CoroutineScope(Dispatchers.IO).launch {
            val code = GoogleDriveApi.requestDeviceCode()
            if (code == null) {
                withContext(Dispatchers.Main) { showToast("Google ha risposto: ${GoogleDriveApi.lastError ?: "errore sconosciuto"}") }
                return@launch
            }
            val url = code.verificationUrl ?: "https://www.google.com/device"
            val userCode = code.userCode ?: ""

            var dialog: AlertDialog? = null
            withContext(Dispatchers.Main) {
                val d = AlertDialog.Builder(ctx)
                    .setTitle("Accedi con Google")
                    .setMessage(
                        "1. Apri $url (anche da un altro dispositivo)\n" +
                            "2. Inserisci questo codice:\n\n$userCode\n\n" +
                            "3. Accedi con il tuo account Google e consenti l'accesso.\n\n" +
                            "In attesa dell'autorizzazione..."
                    )
                    .setPositiveButton("Apri link", null)
                    .setNeutralButton("Copia codice", null)
                    .setNegativeButton("Annulla") { _, _ -> loginJob?.cancel() }
                    .setCancelable(false)
                    .create()
                d.show()
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        showToast("Apri $url dal browser")
                    }
                }
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Codice Google", userCode))
                    showToast("Codice copiato")
                }
                dialog = d
            }

            val auth = try {
                GoogleDriveApi.pollToken(code)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { dialog?.dismiss() }
            }
            if (!auth.first) {
                withContext(Dispatchers.Main) { showToast(auth.second ?: "Accesso non riuscito") }
                return@launch
            }

            setKey("sync_provider", "gdrive")
            setKey("backup_device", "$backup")
            setKey("restore_device", "$restore")
            val result = try {
                ApiUtils.syncDriveDetails(ctx)
            } catch (e: Exception) {
                false to "Errore: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                if (result.first) {
                    (plugin as? SyncPlugin)?.onLoginCompleted()
                    showToast(result.second)
                    dismiss()
                } else {
                    showToast(result.second ?: "Errore di sincronizzazione")
                    onDone()
                }
            }
        }
    }
}
