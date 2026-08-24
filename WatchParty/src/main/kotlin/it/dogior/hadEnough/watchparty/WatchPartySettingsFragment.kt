package it.dogior.hadEnough.watchparty

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig

private const val TAG = "WatchParty"

class WatchPartySettingsFragment(
    private val plugin: Plugin,
    private val manager: WatchPartyManager,
) : BottomSheetDialogFragment() {

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    /** Stesso pattern di StreamITA: risoluzione a runtime dei drawable del plugin per nome. */
    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: run {
            android.util.Log.e(TAG, "❌ getDrawable('$name'): plugin.resources è null")
            return null
        }
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) {
            android.util.Log.e(TAG, "❌ getDrawable('$name'): risorsa non trovata (id=0) — controlla che il file esista in res/drawable/$name.xml")
            return null
        }
        return ResourcesCompat.getDrawable(res, id, null)
    }

    private fun View.applyOutlineBackground() {
        background = getDrawable("outline") ?: coloredFallback(0x12FFFFFF.toInt(), 0x99FFFFFF.toInt())
    }

    private fun View.applyBlueBackground() {
        background = getDrawable("outline_blue") ?: coloredFallback(0x143B65F5, 0x997C93FF.toInt())
    }

    private fun View.applyGreenBackground() {
        background = getDrawable("outline_green") ?: coloredFallback(0x142AC96B, 0x997CFF9D.toInt())
    }

    private fun View.applyDangerBackground() {
        background = getDrawable("outline_danger") ?: coloredFallback(0x14FF6B6B, 0x99FF7F7F.toInt())
    }

    /** Rete di sicurezza: se il drawable del plugin non si carica per qualche motivo,
     * costruiamo comunque il colore giusto via codice invece di lasciare il tema di
     * default dell'app (che è blu — era la causa del pulsante "Esci" apparso blu). */
    private fun coloredFallback(fill: Int, stroke: Int): Drawable {
        android.util.Log.e(TAG, "⚠️ Uso il fallback colorato via codice (il drawable del plugin non si è caricato)")
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 8 * resources.displayMetrics.density
            setColor(fill)
            setStroke((2 * resources.displayMetrics.density).toInt(), stroke)
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        android.util.Log.d(TAG, "📄 onCreateView() inizio")

        val root = getLayout("watchparty_settings", inflater, container)

        val statusCard = root.findView<View>("wp_status_card")
        val status = root.findView<TextView>("wp_status")
        val statusDot = root.findView<View>("wp_status_dot")
        val participantsContainer = root.findView<ViewGroup>("wp_participants_container")

        val pinCard = root.findView<View>("wp_pin_card")
        val pinDisplay = root.findView<TextView>("wp_pin_display")
        val copyPinBtn = root.findView<ImageButton>("wp_copy_pin")

        val joinCard = root.findView<View>("wp_join_card")
        val createBtn = root.findView<TextView>("wp_create")
        val pinInput = root.findView<EditText>("wp_pin_input")
        val joinBtn = root.findView<TextView>("wp_join")

        val activeRoomCard = root.findView<View>("wp_active_room_card")
        val episodeRow = root.findView<View>("wp_episode_row")
        val lockBtn = root.findView<TextView>("wp_lock_room")
        val nextEpisodeBtn = root.findView<TextView>("wp_next_episode")
        val leaveBtn = root.findView<TextView>("wp_leave")
        val resyncBtn = root.findView<TextView>("wp_resync")

        val settingsCard = root.findView<View>("wp_settings_card")

        // --- Stile "card" identico a StreamITA ---
        statusCard.applyOutlineBackground()
        pinCard.applyOutlineBackground()
        joinCard.applyOutlineBackground()
        activeRoomCard.applyOutlineBackground()
        createBtn.applyGreenBackground()   // creare una stanza = azione positiva
        joinBtn.applyBlueBackground()
        resyncBtn.applyBlueBackground()
        leaveBtn.applyDangerBackground()   // azione distruttiva, rossa
        nextEpisodeBtn.applyOutlineBackground()
        lockBtn.applyOutlineBackground()
        copyPinBtn.applyOutlineBackground()
        copyPinBtn.setImageDrawable(getDrawable("copy_icon"))
        settingsCard.applyOutlineBackground()

        settingsCard.setOnClickListener {
            WatchPartyAdvancedSettingsFragment(plugin, this@WatchPartySettingsFragment)
                .show(parentFragmentManager, "WatchPartyAdvancedSettings")
        }

        // --- Editor permessi ospite (solo host) ---
        fun showPermissionsEditor() {
            val ctx = root.context
            val pad = (20 * resources.displayMetrics.density).toInt()
            val container = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }

            fun permissionRow(title: String, checked: Boolean): android.widget.Switch {
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, (10 * resources.displayMetrics.density).toInt())
                }
                val label = TextView(ctx).apply {
                    text = title
                    textSize = 14f
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val switch = android.widget.Switch(ctx).apply { isChecked = checked }
                row.addView(label)
                row.addView(switch)
                container.addView(row)
                return switch
            }

            val current = manager.guestPermissions
            val playPauseSwitch = permissionRow("Can play/pause", current.canPlayPause)
            val seekSwitch = permissionRow("Can seek", current.canSeek)
            val nextEpisodeSwitch = permissionRow("Can change episode", current.canNextEpisode)

            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("Permissions for guests")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    manager.sendPermissionsToGuest(
                        ParticipantPermissions(
                            canPlayPause = playPauseSwitch.isChecked,
                            canSeek = seekSwitch.isChecked,
                            canNextEpisode = nextEpisodeSwitch.isChecked,
                        )
                    )
                    showToast("Permissions updated")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // --- Card partecipanti: cliccabile SOLO dall'host, SOLO sulla card dell'ospite ---
        fun buildParticipantCard(label: String, editable: Boolean, cid: String? = null): View {
            val row = android.widget.LinearLayout(root.context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                val marginPx = (6 * resources.displayMetrics.density).toInt()
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = marginPx; bottomMargin = marginPx }
                background = getDrawable("outline") ?: coloredFallback(0x12FFFFFF.toInt(), 0x88FFFFFF.toInt())
            }
            // Kick: solo l'host lo vede, a sinistra del nome
            if (editable && cid != null) {
                val kick = TextView(root.context).apply {
                    text = "✕"
                    textSize = 16f
                    setTextColor(0xFFFF6B6B.toInt())
                    setPadding(0, 0, (12 * resources.displayMetrics.density).toInt(), 0)
                    isClickable = true
                    isFocusable = true
                }
                kick.setOnClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(root.context)
                        .setTitle("Kick participant")
                        .setMessage("Remove ${label.replace(" (Host)", "")} from the room?")
                        .setPositiveButton("Kick") { _, _ ->
                            manager.kickParticipant(cid)
                            showToast("Participant kicked")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                row.addView(kick)
            }
            val nameView = TextView(root.context).apply {
                text = label
                textSize = 14f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameView)
            if (editable) {
                val hint = TextView(root.context).apply {
                    text = "Permissions"
                    textSize = 12f
                    alpha = 0.7f
                    setTextColor(0xFF7C93FF.toInt())
                }
                row.addView(hint)
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { showPermissionsEditor() }
                // long-press = promuovi a host (come il kick, con conferma)
                row.setOnLongClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(root.context)
                        .setTitle("Promote participant")
                        .setMessage("Make ${label.replace(" (Host)", "")} the new host?")
                        .setPositiveButton("Promote") { _, _ ->
                            cid?.let {
                                manager.promoteParticipant(it)
                                showToast("Host changed")
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }
            return row
        }

        fun refreshParticipants() {
            participantsContainer.removeAllViews()
            if (manager.role == WatchPartyManager.Role.IDLE) {
                participantsContainer.visibility = View.GONE
                return
            }
            participantsContainer.visibility = View.VISIBLE
            val me = manager.localDisplayName()
            val meLabel = if (manager.role == WatchPartyManager.Role.HOST) "$me (Host, You)" else "$me (You)"
            participantsContainer.addView(buildParticipantCard(meLabel, editable = false))

            val roster = manager.participants
            if (roster.isEmpty()) {
                participantsContainer.addView(
                    buildParticipantCard("Waiting for participants…", editable = false)
                )
                return
            }
            // editabile solo se IO sono host (i permessi si impostano sugli ospiti)
            val editable = manager.role == WatchPartyManager.Role.HOST
            for (p in roster) {
                val isPeerHost = p.cid == manager.currentHostCid
                val label = if (isPeerHost) "${p.name} (Host)" else p.name
                participantsContainer.addView(buildParticipantCard(label, editable = editable, cid = p.cid))
            }
        }

        fun refreshUiForActiveRoom() {
            val inRoom = manager.role != WatchPartyManager.Role.IDLE
            val isHost = manager.role == WatchPartyManager.Role.HOST
            joinCard.visibility = if (inRoom) View.GONE else View.VISIBLE
            activeRoomCard.visibility = if (inRoom) View.VISIBLE else View.GONE
            // episodi: visibili a tutti in stanza, ma il guest solo se l'host
            // glielo consente (canNextEpisode)
            val canEpisode = isHost || manager.myPermissions.canNextEpisode
            episodeRow.visibility = if (inRoom && canEpisode) View.VISIBLE else View.GONE
            // lucchetto: solo l'host, icona aggiornata sullo stato reale della stanza
            lockBtn.visibility = if (inRoom && isHost) View.VISIBLE else View.GONE
            lockBtn.text = if (manager.roomLocked) "🔒" else "🔓"
            pinCard.visibility = if (inRoom && isHost) View.VISIBLE else View.GONE
            if (inRoom && isHost) {
                pinDisplay.text = manager.currentPin
            }
            refreshParticipants()
        }

        fun updateStatusDot(state: WatchPartyManager.ConnectionState) {
            val res = when (state) {
                WatchPartyManager.ConnectionState.CONNESSO ->
                    if (manager.peerPresent) android.R.drawable.presence_online
                    else android.R.drawable.presence_away
                WatchPartyManager.ConnectionState.CONNESSIONE_IN_CORSO,
                WatchPartyManager.ConnectionState.RICONNESSIONE_IN_CORSO -> android.R.drawable.presence_away
                WatchPartyManager.ConnectionState.DISCONNESSO -> android.R.drawable.presence_offline
            }
            statusDot.setBackgroundResource(res)
        }
        updateStatusDot(manager.connectionState)

        if (!PlayerAccess.isPlayerScreenActive()) {
            status.text = "Open a video first, then come back here to create or join a room."
        }

        manager.onStatusText = { text -> activity?.runOnUiThread { status.text = text } }
        manager.onPeerConnected = { _ -> activity?.runOnUiThread { updateStatusDot(manager.connectionState) } }
        manager.onConnectionStateChanged = { state -> activity?.runOnUiThread { updateStatusDot(state) } }
        manager.onParticipantsChanged = {
            activity?.runOnUiThread {
                refreshUiForActiveRoom()
                refreshParticipants()
                updateStatusDot(manager.connectionState)
            }
        }

        createBtn.setOnClickListener {
            if (!PlayerAccess.isPlayerScreenActive()) {
                showToast("Open a video first")
                return@setOnClickListener
            }
            val pin = manager.createRoom()
            pinDisplay.text = pin
            status.text = "Share this PIN with your friends. They need to open the same video."
            refreshUiForActiveRoom()
        }

        copyPinBtn.setOnClickListener {
            val pin = manager.currentPin ?: return@setOnClickListener
            val clipboard = root.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Watch Party PIN", pin))
            showToast("PIN copied")
        }

        joinBtn.setOnClickListener {
            val pin = pinInput.text?.toString()?.trim().orEmpty()
            if (pin.length < 4) {
                showToast("Enter a valid PIN")
                return@setOnClickListener
            }
            if (!PlayerAccess.isPlayerScreenActive()) {
                showToast("Open the same video as your friends first")
                return@setOnClickListener
            }
            manager.joinRoom(pin)
            status.text = "Connecting to room $pin…"
            refreshUiForActiveRoom()
        }

        leaveBtn.setOnClickListener {
            manager.leaveRoom()
            dismiss()
        }

        resyncBtn.setOnClickListener {
            manager.requestResyncNow()
            showToast("Resync sent")
        }

        nextEpisodeBtn.setOnClickListener { manager.goToNextEpisode() }

        lockBtn.setOnClickListener {
            val target = !manager.roomLocked
            manager.setRoomLock(target)
            lockBtn.text = if (target) "🔒" else "🔓"
            showToast(if (target) "Room locked" else "Room unlocked")
        }

        refreshUiForActiveRoom()

        // Riga di stato sul consenso privacy, aggiunta a runtime (root è un
        // NestedScrollView con un solo figlio diretto: si aggiunge al
        // contenitore interno, non alla radice, altrimenti crash).
        val consentLabel = TextView(root.context).apply {
            textSize = 11f
            alpha = 0.5f
            val date = WatchPartyConsent.acceptedAtLabel()
            text = if (date != null) "Relay terms accepted on $date"
            else "Relay terms not accepted yet"
            gravity = android.view.Gravity.CENTER
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val innerContainer = (root as? ViewGroup)?.getChildAt(0) as? ViewGroup
        innerContainer?.addView(consentLabel)

        android.util.Log.d(TAG, "🏁 onCreateView() completato")
        root
    } catch (e: Exception) {
        android.util.Log.e(TAG, "💥 ECCEZIONE in onCreateView()", e)
        null
    }
}
