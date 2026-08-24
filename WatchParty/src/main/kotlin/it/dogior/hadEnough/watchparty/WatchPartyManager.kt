package it.dogior.hadEnough.watchparty

import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.IPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.utils.DataStoreHelper
import it.dogior.hadEnough.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/** Permessi applicati a un ospite. L'host ha sempre controllo completo. */
data class ParticipantPermissions(
    val canPlayPause: Boolean = true,
    val canSeek: Boolean = true,
    val canNextEpisode: Boolean = true,
)

/** Partecipante visibile all'UI. seq = ordine di ingresso (1 = primo entrato, cioè l'host originale). */
data class WatchPartyParticipant(
    val cid: String,
    val name: String,
    val seq: Int,
)

/**
 * Gestisce una Watch Party fino a 5 utenti su un canale WebSocket di relay.
 *
 * A differenza della versione "fork" (che agganciava un listener diretto
 * sul player interno), qui lo stato locale viene rilevato via POLLING
 * pubblico (player.getPosition()/getIsPlaying()), perché un plugin non può
 * registrarsi sull'event bus interno di PlayerView senza modificare l'app.
 *
 * Prevenzione loop: quando applichiamo un comando remoto (seekTo/handleEvent
 * con source = Sync) marchiamo `lastRemoteCommandMs`. Il polling ignora ogni
 * variazione di stato avvenuta entro ECHO_WINDOW_MS da quel momento, così
 * non la re-invia agli altri peer.
 *
 * Multi-peer: il server traccia identità (cid), ordine di ingresso (seq) e chi
 * è l'host (hostCid). Quando l'host esce, il server promuove il più "vecchio"
 * tra i rimanenti e lo comunica con hostCid: qui ci allineiamo al ruolo indicato.
 *
 * LIMITE NOTO: il cambio di episodio/sorgente non viene propagato
 * automaticamente (IPlayer non espone un metodo pubblico per caricare un
 * nuovo URL). Viene solo inviata una notifica "EPISODE_HINT" col titolo,
 * gli altri utenti devono cambiare episodio manualmente.
 */
class WatchPartyManager {

    enum class Role { IDLE, HOST, GUEST }

    /** Stato di connessione al relay, indipendente da "gli altri sono nella stanza". */
    enum class ConnectionState { DISCONNESSO, CONNESSIONE_IN_CORSO, CONNESSO, RICONNESSIONE_IN_CORSO }

    companion object {
        const val MAX_PARTICIPANTS = 5
        private const val POLL_INTERVAL_MS = 200L
        // Basta coprire il primo tick dopo aver applicato un comando remoto:
        // da lì in poi lastKnownPosition riflette già il nuovo valore, quindi
        // non serve una finestra lunga (era 900ms, bloccava click legittimi).
        private const val ECHO_WINDOW_MS = 500L
        private const val SEEK_JUMP_THRESHOLD_MS = 1200L
        private const val RESYNC_THRESHOLD_MS = 1500L
        private const val HEARTBEAT_INTERVAL_MS = 6000L
        private const val SEEK_SEND_DEBOUNCE_MS = 220L
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 15000L
        private const val GATE_SAFETY_TIMEOUT_MS = 9000L
        // tempo di buffering presunto dopo un seek: scaduto questo, il lato
        // locale si dichiara pronto (READY) e il resolve aspetta gli altri peer
        private const val LOCAL_SEEK_READY_MS = 900L

        /** Endpoint del server di relay. Vedi WatchPartyServer/ per l'implementazione di riferimento.
         *  Il valore viene iniettato a build-time dal secret WATCHPARTY_RELAY (GitHub Actions), così
         *  l'URL non compare nel sorgente. */
        val DEFAULT_RELAY_URL: String = BuildConfig.WATCHPARTY_RELAY
    }

    /** Identità stabile di QUESTO client: sopravvive ai reconnect, il server la usa per roster e host migration. */
    val myClientId: String = UUID.randomUUID().toString()

    var role: Role = Role.IDLE
        private set
    var currentPin: String? = null
        private set
    var connectionState: ConnectionState = ConnectionState.DISCONNESSO
        private set(value) {
            field = value
            mainHandler.post { onConnectionStateChanged?.invoke(value) }
        }

    /** True quando c'è ALMENO un altro utente in stanza, non solo "io sono connesso al relay". */
    var peerPresent: Boolean = false
        private set

    /** Ordine di ingresso assegnatomi dal server (ROOM_STATE). 1 = primo entrato (host). */
    var mySeq: Int? = null
        private set

    /** cid dell'host corrente, secondo il server (host migration). */
    var currentHostCid: String? = null
        private set

    /** Roster degli ALTRI partecipanti, ordinati per ingresso. */
    val participants: List<WatchPartyParticipant>
        get() = participantsMap.values.sortedBy { it.seq }.map {
            WatchPartyParticipant(it.cid, it.name, it.seq)
        }

    private val participantsMap = LinkedHashMap<String, Participant>()

    /** cid che hanno appena fatto PEER_JOINED ma di cui non ho ancora il nome (HELLO).
     *  Consumato al primo HELLO: è lì che emetto la bolla "X entered the room",
     *  solo per chi era GIÀ in stanza. Chi era già dentro prima di me arriva via
     *  ROOM_STATE e non va annunciato. */
    private val pendingJoinCids = mutableSetOf<String>()

    /** Dato interno di roster: seq non è aggiornato ai reconnect, resta l'ordine originale. */
    private class Participant(val cid: String, val seq: Int, var name: String)

    /**
     * Permessi APPLICATI A ME. Rilevanti solo se sono Guest — l'host ha
     * sempre controllo completo, quindi qui resta sempre il default (tutto true).
     */
    var myPermissions: ParticipantPermissions = ParticipantPermissions()
        private set

    /** Copia locale di ciò che l'host ha impostato per il guest, per mostrarlo nell'editor. */
    var guestPermissions: ParticipantPermissions = ParticipantPermissions()
        private set

    var onStatusText: ((String) -> Unit)? = null
    var onPeerConnected: ((Boolean) -> Unit)? = null
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onEpisodeHint: ((String) -> Unit)? = null
    var onParticipantsChanged: (() -> Unit)? = null
    /** true = mostra la rotellina di attesa sincronizzata, false = nascondila. */
    var onBufferingGateChanged: ((Boolean) -> Unit)? = null
    /** Messaggio della chat ricevuto da un altro partecipante: (nome mittente, testo). */
    var onChatMessage: ((sender: String, text: String) -> Unit)? = null
    /** Notifica di sistema da mostrare come bolla in chat ("X joined", "X left"). */
    var onSystemMessage: ((text: String) -> Unit)? = null

    /** True se l'host ha bloccato nuovi ingressi (lucchetto stanza). */
    var roomLocked: Boolean = false
        private set

    val isConnected: Boolean get() = socket?.isOpen == true

    /** Nome del profilo CloudStream locale attivo, o "Guest" se non trovato. */
    fun localDisplayName(): String {
        val account = DataStoreHelper.accounts.find { it.keyIndex == DataStoreHelper.selectedKeyIndex }
        return account?.name?.takeIf { it.isNotBlank() } ?: "Guest"
    }

    private var socket: WatchPartySocket? = null
    private var relayUrl: String = DEFAULT_RELAY_URL

    // false quando l'utente esce volontariamente (leaveRoom/release): in quel
    // caso NON dobbiamo riconnetterci. true finché la stanza è "voluta" attiva.
    private var shouldStayConnected = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile private var lastRemoteCommandMs = 0L

    // ultimo stato locale noto, per rilevare le transizioni via polling
    private var lastKnownPlaying: Boolean? = null
    private var lastKnownPosition: Long = 0L

    // --- gate di attesa sincronizzata dopo un seek ---
    private var gateActive = false
    private var gateGeneration = 0
    private var gateExpectedPlaying = true
    private var localReady = false
    private var remoteReadyCount = 0

    fun createRoom(relayUrl: String = DEFAULT_RELAY_URL): String {
        this.relayUrl = relayUrl
        val pin = (100000..999999).random(Random(System.nanoTime())).toString()
        role = Role.HOST
        currentPin = pin
        shouldStayConnected = true
        reconnectAttempt = 0
        connectSocket(pin)
        startPolling()
        startHeartbeat()
        return pin
    }

    fun joinRoom(pin: String, relayUrl: String = DEFAULT_RELAY_URL) {
        this.relayUrl = relayUrl
        role = Role.GUEST
        currentPin = pin
        shouldStayConnected = true
        reconnectAttempt = 0
        connectSocket(pin)
        startPolling()
        startHeartbeat()
    }

    fun leaveRoom() {
        shouldStayConnected = false
        if (isConnected) socket?.send("LEAVE_ROOM")
        release()
    }

    fun release() {
        shouldStayConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        pollJob?.cancel()
        pollJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close()
        socket = null
        role = Role.IDLE
        currentPin = null
        lastKnownPlaying = null
        connectionState = ConnectionState.DISCONNESSO
        peerPresent = false
        mySeq = null
        currentHostCid = null
        roomLocked = false
        participantsMap.clear()
        myPermissions = ParticipantPermissions()
        guestPermissions = ParticipantPermissions()
        gateActive = false
        gateGeneration++
        pendingJoinCids.clear()
        onBufferingGateChanged?.invoke(false)
        onConnectionStateChanged?.invoke(connectionState)
        onParticipantsChanged?.invoke()
    }

    // ---------------------------------------------------------------------
    // Connessione + riconnessione automatica
    // ---------------------------------------------------------------------

    private fun connectSocket(pin: String) {
        connectionState = if (reconnectAttempt > 0) ConnectionState.RICONNESSIONE_IN_CORSO
        else ConnectionState.CONNESSIONE_IN_CORSO
        peerPresent = false // non sappiamo ancora se c'è qualcun altro, lo scopriremo dai messaggi

        socket = WatchPartySocket(
            baseWsUrl = relayUrl,
            clientId = myClientId,
            onOpen = {
                mainHandler.post {
                    reconnectAttempt = 0
                    connectionState = ConnectionState.CONNESSO
                    onStatusText?.invoke("Connected to the server, waiting for other participants…")
                    // annuncia il nostro nome; chi è già in stanza risponderà a sua volta
                    // (vedi handleRemoteMessage) e sapremo che c'è
                    socket?.send(WatchPartyMessage(type = "HELLO", name = localDisplayName()))
                    if (role == Role.GUEST) socket?.send("SYNC_REQUEST")
                }
            },
            onMessage = { msg -> mainHandler.post { handleRemoteMessage(msg) } },
            onClosed = { code, reason ->
                mainHandler.post {
                    if (reason == "kicked") {
                        // espulsi dall'host: niente riconnessione, si esce dalla stanza
                        onStatusText?.invoke("You were kicked by the host")
                        shouldStayConnected = false
                        release()
                        return@post
                    }
                    onStatusText?.invoke("Connection closed")
                    peerPresent = false
                    scheduleReconnect()
                }
            },
            onFailure = { t, response ->
                mainHandler.post {
                    when (response?.code) {
                        409 -> {
                            // stanza piena: riprovare non serve, fermiamo il loop di reconnect
                            shouldStayConnected = false
                            onStatusText?.invoke("The room is full (max $MAX_PARTICIPANTS people)")
                        }
                        403 -> {
                            // stanza bloccata dall'host: fermiamo il loop di reconnect
                            shouldStayConnected = false
                            onStatusText?.invoke("The room is locked by the host")
                        }
                        else -> onStatusText?.invoke("Connection error: ${t.message}")
                    }
                    peerPresent = false
                    scheduleReconnect()
                }
            },
        ).also { it.connect(pin) }
    }

    /** Riprova con backoff esponenziale (1s, 2s, 4s, 8s… fino a un tetto di 15s). */
    private fun scheduleReconnect() {
        if (!shouldStayConnected) return // uscita volontaria, non riconnettere
        val pin = currentPin ?: return

        connectionState = ConnectionState.RICONNESSIONE_IN_CORSO
        reconnectAttempt++
        val delayMs = (RECONNECT_BASE_DELAY_MS * (1 shl (reconnectAttempt - 1).coerceAtMost(4)))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)

        onStatusText?.invoke("Connection lost, retrying in ${delayMs / 1000}s… (attempt $reconnectAttempt)")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!shouldStayConnected) return@launch
            withContext(Dispatchers.Main) { connectSocket(pin) }
        }
    }

    // ---------------------------------------------------------------------
    // Polling dello stato locale (sostituisce l'hook sul player)
    // ---------------------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                withContext(Dispatchers.Main) { pollLocalPlayer() }
            }
        }
    }

    /** Correzione periodica leggera del drift, senza pausa forzata: solo se lo scarto è reale. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (role == Role.HOST) withContext(Dispatchers.Main) { sendSyncState() }
            }
        }
    }

    private fun pollLocalPlayer() {
        if (role == Role.IDLE || !isConnected) return
        val player = PlayerAccess.currentPlayer() ?: return

        val playing = player.getIsPlaying()
        val position = player.getPosition() ?: return

        // ignora SOLO il tick immediatamente successivo a un comando che abbiamo
        // applicato noi da remoto (evita di rimandarlo indietro come se fosse
        // un'azione dell'utente locale). Non blocca i tick successivi.
        val withinEchoWindow = System.currentTimeMillis() - lastRemoteCommandMs < ECHO_WINDOW_MS

        val prevPlaying = lastKnownPlaying
        val prevPosition = lastKnownPosition
        lastKnownPlaying = playing
        lastKnownPosition = position

        if (withinEchoWindow) return
        if (prevPlaying == null) return // primo tick, solo inizializza

        // seek: salto di posizione più grande di quanto ci si aspetterebbe dal solo
        // scorrere del tempo tra un tick e l'altro. Ogni tick è valutato in modo
        // indipendente: click ravvicinati ma su tick diversi vengono inviati tutti.
        val expectedDrift = POLL_INTERVAL_MS + 400L
        if (abs(position - prevPosition) > expectedDrift.coerceAtLeast(SEEK_JUMP_THRESHOLD_MS)) {
            if (role == Role.GUEST && !myPermissions.canSeek) {
                revertUnauthorizedLocalChange(prevPosition, prevPlaying)
                return
            }
            // Piccolo debounce: se l'utente ha appena fatto seek e sta per premere
            // play (o l'ha già premuto un istante prima), aspettiamo un tick in più
            // e rileggiamo lo stato al momento dell'invio, invece di fidarci dello
            // stato letto nell'istante esatto del salto. Evita di spedire un SEEK
            // con playing=false quando in realtà l'utente ha già premuto play.
            scope.launch {
                delay(SEEK_SEND_DEBOUNCE_MS)
                withContext(Dispatchers.Main) {
                    val p = PlayerAccess.currentPlayer() ?: return@withContext
                    val finalPos = p.getPosition() ?: position
                    val finalPlaying = p.getIsPlaying()
                    lastKnownPosition = finalPos
                    lastKnownPlaying = finalPlaying
                    socket?.send(WatchPartyMessage(type = "SEEK", position = finalPos, playing = finalPlaying))
                    // anche IO aspetto il gate: niente più "chi carica prima riparte prima"
                    beginSeekGate(finalPos, finalPlaying)
                }
            }
            return
        }

        // play/pausa
        if (playing != prevPlaying) {
            if (role == Role.GUEST && !myPermissions.canPlayPause) {
                revertUnauthorizedLocalChange(prevPosition, prevPlaying)
                return
            }
            socket?.send(if (playing) "PLAY" else "PAUSE")
        }
    }

    /** Annulla localmente un'azione per cui l'host non ha dato il permesso, senza inviarla. */
    private fun revertUnauthorizedLocalChange(pos: Long, playing: Boolean?) {
        val player = PlayerAccess.currentPlayer() ?: return
        lastRemoteCommandMs = System.currentTimeMillis() // riusa la finestra anti-eco per non ri-rilevarlo
        player.seekTo(pos, PlayerEventSource.Sync)
        if (playing != null) {
            player.handleEvent(if (playing) CSPlayerEvent.Play else CSPlayerEvent.Pause, PlayerEventSource.Sync)
        }
        onStatusText?.invoke("The host doesn't allow this action")
    }

    // ---------------------------------------------------------------------
    // Messaggi in arrivo
    // ---------------------------------------------------------------------

    private fun handleRemoteMessage(msg: WatchPartyMessage) {
        when (msg.type) {
            // ---- messaggi del server: roster + host migration ----

            "ROOM_STATE" -> {
                // arrivato subito dopo la connessione: conosce già tutti (incluso me)
                msg.roster?.forEach { peer ->
                    if (peer.cid != myClientId) upsertParticipant(peer.cid, peer.seq, null)
                }
                mySeq = msg.roster?.firstOrNull { it.cid == myClientId }?.seq
                msg.hostCid?.let { currentHostCid = it }
                msg.locked?.let { roomLocked = it }
                applyRoleFromHost()
                onStatusText?.invoke(
                    if (participantsMap.isEmpty()) "Connected, waiting for other participants…"
                    else "Connected, ${participantsMap.size} participant(s) in the room"
                )
                syncParticipants()
            }

            "PEER_JOINED" -> {
                msg.cid?.let {
                    upsertParticipant(it, msg.seq ?: Int.MAX_VALUE, null)
                    pendingJoinCids += it
                }
                msg.hostCid?.let { currentHostCid = it }
                applyRoleFromHost()
                onStatusText?.invoke("New participant connected, sending my name…")
                socket?.send(WatchPartyMessage(type = "HELLO", name = localDisplayName()))
                if (role == Role.HOST) sendSyncState()
                syncParticipants()
            }

            "PEER_LEFT" -> {
                // leggo il nome PRIMA di rimuoverlo, per la bolla di sistema
                val who = msg.cid?.let { participantsMap[it]?.name ?: "Participant" }
                msg.cid?.let { participantsMap.remove(it) }
                msg.hostCid?.let { currentHostCid = it }
                applyRoleFromHost()
                onStatusText?.invoke(
                    if (participantsMap.isEmpty()) "The room is now empty"
                    else "A participant left the room"
                )
                who?.let {
                    // kicked=true se il server l'ha chiuso per KICK dell'host
                    onSystemMessage?.invoke(
                        if (msg.kicked == true) "$it was kicked by the host"
                        else "$it left the room"
                    )
                }
                syncParticipants()
                // se l'ex host è uscito e ora sono io l'host, riallineo subito tutti
                if (role == Role.HOST) sendSyncState()
            }

            "LOCK_STATE" -> {
                roomLocked = msg.locked ?: false
                onSystemMessage?.invoke(
                    if (roomLocked) "The host locked the room"
                    else "The host unlocked the room"
                )
                onStatusText?.invoke(
                    if (roomLocked) "The room is now locked, no new participants"
                    else "The room is now unlocked"
                )
                onParticipantsChanged?.invoke()
            }

            "HOST_CHANGED" -> {
                val h = msg.hostCid ?: return
                val promotedName = participantsMap[h]?.name ?: "Participant"
                currentHostCid = h
                applyRoleFromHost()
                onSystemMessage?.invoke(
                    if (h == myClientId) "You are now the host of the room"
                    else "$promotedName is now the host"
                )
                onParticipantsChanged?.invoke()
            }

            // ---- messaggi degli altri client ----

            "HELLO" -> {
                val name = msg.name?.takeIf { it.isNotBlank() } ?: "Participant"
                val cid = msg.cid
                if (cid == null) {
                    // client "vecchio" senza cid: un solo peer, teniamolo per compatibilità
                    if (participantsMap.isEmpty()) upsertParticipant("legacy", 0, name)
                } else if (cid != myClientId) {
                    // bolla di sistema solo se questo cid è entrato DOPO di me
                    // (segnato in PEER_JOINED); chi c'era già non va annunciato
                    if (pendingJoinCids.remove(cid)) {
                        onSystemMessage?.invoke("$name joined the room")
                    }
                    upsertParticipant(cid, msg.seq ?: Int.MAX_VALUE, name)
                }
                onStatusText?.invoke("Participant connected: $name")
                syncParticipants()
                // se sono host e avevo già impostato dei permessi, li rimando ora
                // che qualcuno si è (ri)connesso, altrimenti li perderebbe al reconnect
                if (role == Role.HOST && guestPermissions != ParticipantPermissions()) {
                    sendPermissionsToGuest(guestPermissions)
                }
            }

            "PERMISSIONS" -> {
                if (role == Role.GUEST) {
                    myPermissions = ParticipantPermissions(
                        canPlayPause = msg.canPlayPause ?: true,
                        canSeek = msg.canSeek ?: true,
                        canNextEpisode = msg.canNextEpisode ?: true,
                    )
                    onStatusText?.invoke("The host updated your permissions")
                    onParticipantsChanged?.invoke()
                }
            }

            "SYNC_REQUEST" -> if (role == Role.HOST) sendSyncState()

            "SYNC_STATE" -> applyRemote {
                val player = PlayerAccess.currentPlayer() ?: return@applyRemote
                val current = player.getPosition() ?: 0L
                // heartbeat periodico: correggi solo se lo scarto è reale, niente
                // seek continui che darebbero fastidio durante la visione normale
                msg.position?.let {
                    if (abs(current - it) > RESYNC_THRESHOLD_MS) player.seekTo(it, PlayerEventSource.Sync)
                }
                if (msg.playing != null) {
                    player.handleEvent(
                        if (msg.playing) CSPlayerEvent.Play else CSPlayerEvent.Pause,
                        PlayerEventSource.Sync
                    )
                }
            }

            "FORCE_SYNC" -> {
                // richiesta ESPLICITA dell'utente (pulsante "Risincronizza ora"):
                // applica sempre, a differenza di SYNC_STATE che corregge solo se lo
                // scarto supera la soglia. Passa dal MEDESIMO gate di un SEEK organico
                // (non più un seekTo+Play "a freddo"): altrimenti si ripresenta esattamente
                // il problema "chi carica prima riparte prima" che il gate risolve per i
                // seek normali — con più ospiti ognuno ripartirebbe per conto suo.
                val pos = msg.position ?: return
                val playing = msg.playing ?: true
                lastRemoteCommandMs = System.currentTimeMillis()
                mainHandler.post { beginSeekGate(pos, playing) }
            }

            "PLAY" -> applyRemote {
                PlayerAccess.currentPlayer()?.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync)
            }

            "PAUSE" -> applyRemote {
                PlayerAccess.currentPlayer()?.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync)
            }

            "SEEK" -> {
                val pos = msg.position ?: return
                val playing = msg.playing ?: true
                lastRemoteCommandMs = System.currentTimeMillis()
                mainHandler.post { beginSeekGate(pos, playing) }
            }

            "READY" -> mainHandler.post { onRemoteReady() }

            "CHAT" -> {
                val text = msg.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
                onChatMessage?.invoke(msg.name?.takeIf { it.isNotBlank() } ?: "Participant", text)
            }

            "EPISODE_HINT" -> msg.title?.let { onEpisodeHint?.invoke(it) }

            "NEXT_EPISODE" -> applyRemote {
                PlayerAccess.currentPlayer()?.handleEvent(CSPlayerEvent.NextEpisode, PlayerEventSource.Sync)
            }
        }
    }

    /** Aggiunge o aggiorna un partecipante. Il seq resta quello originale anche se il client si riconnette. */
    private fun upsertParticipant(cid: String, seq: Int, name: String?) {
        val existing = participantsMap[cid]
        if (existing != null) {
            if (!name.isNullOrBlank()) existing.name = name
        } else {
            participantsMap[cid] = Participant(cid, seq, name ?: "Participant")
        }
    }

    /** Riallinea il ruolo (HOST/GUEST) a quello indicato dal server (hostCid). */
    private fun applyRoleFromHost() {
        if (role == Role.IDLE) return
        val h = currentHostCid ?: return
        val shouldBeHost = h == myClientId
        if (shouldBeHost && role != Role.HOST) {
            role = Role.HOST
            // l'host ha sempre pieno controllo: resetta i permessi di default
            myPermissions = ParticipantPermissions()
            guestPermissions = ParticipantPermissions()
            onStatusText?.invoke("You are now the host of the room")
            // i permessi del vecchio host NON sopravvivono al cambio: li rimando
            // di default a tutti, così anche i guest tornano ai valori iniziali
            sendPermissionsToGuest(guestPermissions)
            sendSyncState()
        } else if (!shouldBeHost && role == Role.HOST) {
            role = Role.GUEST
            onStatusText?.invoke("You are now a participant")
        }
    }

    /** Aggiorna peerPresent e notifica UI ogni volta che cambia il roster. */
    private fun syncParticipants() {
        val present = participantsMap.isNotEmpty()
        if (present != peerPresent) {
            peerPresent = present
            mainHandler.post { onPeerConnected?.invoke(present) }
        }
        mainHandler.post { onParticipantsChanged?.invoke() }
    }

    /**
     * Rete di sicurezza manuale: forza un resync immediato e SEMPRE applicato
     * (a differenza dell'heartbeat automatico, che corregge solo se lo scarto
     * è reale). Un pulsante che "a volte non fa nulla" confonde: questo ha
     * sempre un effetto visibile dall'altra parte.
     */
    fun requestResyncNow() {
        val player = PlayerAccess.currentPlayer() ?: return
        val position = player.getPosition() ?: 0L
        val playing = player.getIsPlaying()
        socket?.send(
            WatchPartyMessage(
                type = "FORCE_SYNC",
                position = position,
                playing = playing,
            )
        )
        // Anche chi lo richiede passa dal gate, come per un SEEK organico ("anche
        // IO aspetto il gate"): così nessuno riparte prima degli altri. È un seek
        // sulla propria posizione attuale (di fatto un no-op), ma serve a tenere
        // tutti sincronizzati sulla stessa pausa/attesa/ripartenza.
        beginSeekGate(position, playing)
        onStatusText?.invoke("Resync sent to all participants")
    }

    // ---------------------------------------------------------------------
    // Gate di attesa sincronizzata dopo un seek: tutti in pausa con
    // rotellina finché non sono davvero pronti, poi ripartono insieme.
    // Una sola pausa pulita (niente più doppio play→pausa→play che
    // "lampeggiava" ad ogni seek) + attesa a tempo su TUTTI i lati: il lato
    // locale manda READY dopo LOCAL_SEEK_READY_MS, il resolve avviene quando
    // tutti gli altri partecipanti sono pronti (o dopo il timeout di sicurezza).
    // Un nuovo seek durante l'attesa invalida quella vecchia (gateGeneration)
    // e ne parte una nuova pulita.
    // ---------------------------------------------------------------------

    private fun beginSeekGate(targetPos: Long, expectedPlaying: Boolean) {
        val player = PlayerAccess.currentPlayer() ?: return
        gateActive = true
        gateGeneration++
        val myGen = gateGeneration
        gateExpectedPlaying = expectedPlaying
        localReady = false
        remoteReadyCount = 0
        onBufferingGateChanged?.invoke(true)

        lastRemoteCommandMs = System.currentTimeMillis()
        player.seekTo(targetPos, PlayerEventSource.Sync)
        // una sola pausa pulita: niente più doppia transizione play→pausa→play
        // che faceva "lampeggiare" il player ad ogni seek
        player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync)

        // il lato locale si considera "pronto" dopo un piccolo tempo di buffering,
        // poi avvisa gli altri. Il resolve aspetta comunque i loro READY.
        mainHandler.postDelayed({
            if (gateActive && gateGeneration == myGen) {
                localReady = true
                socket?.send(WatchPartyMessage(type = "READY"))
                maybeResolveGate(myGen)
            }
        }, LOCAL_SEEK_READY_MS)

        // rete di sicurezza: se un messaggio "READY" si perde, non restare bloccati
        mainHandler.postDelayed({
            if (gateActive && gateGeneration == myGen) resolveGate(myGen)
        }, GATE_SAFETY_TIMEOUT_MS)
    }

    private fun onRemoteReady() {
        remoteReadyCount++
        maybeResolveGate(gateGeneration)
    }

    private fun maybeResolveGate(gen: Int) {
        if (gen != gateGeneration || !gateActive) return
        if (!localReady) return
        // da soli basta il locale; con altri, servono i READY di tutti
        if (participantsMap.isEmpty() || remoteReadyCount >= participantsMap.size) {
            resolveGate(gen)
        }
    }

    private fun resolveGate(gen: Int) {
        if (gen != gateGeneration || !gateActive) return
        gateActive = false
        onBufferingGateChanged?.invoke(false)
        lastRemoteCommandMs = System.currentTimeMillis()
        PlayerAccess.currentPlayer()?.handleEvent(
            if (gateExpectedPlaying) CSPlayerEvent.Play else CSPlayerEvent.Pause,
            PlayerEventSource.Sync
        )
    }

    private fun sendSyncState() {
        val player = PlayerAccess.currentPlayer() ?: return
        socket?.send(
            WatchPartyMessage(
                type = "SYNC_STATE",
                position = player.getPosition() ?: 0L,
                playing = player.getIsPlaying(),
            )
        )
    }

    /** Notifica "morbida": l'host segnala un cambio episodio, il guest deve cambiarlo a mano. */
    fun notifyEpisodeChanged(title: String) {
        if (role != Role.HOST || !isConnected) return
        socket?.send(WatchPartyMessage(type = "EPISODE_HINT", title = title))
    }

    /**
     * Cambia episodio sul proprio player e lo propaga agli altri.
     * Può essere chiamato da entrambi, MA il guest solo se l'host glielo
     * consente (canNextEpisode); senza permesso non fa nulla.
     */
    fun goToNextEpisode() {
        if (role == Role.GUEST && !myPermissions.canNextEpisode) {
            onStatusText?.invoke("The host doesn't allow you to change episodes")
            return
        }
        PlayerAccess.currentPlayer()?.handleEvent(CSPlayerEvent.NextEpisode, PlayerEventSource.UI)
        socket?.send("NEXT_EPISODE")
    }

    /** Invia un messaggio della chat agli altri partecipanti. No-op se non siamo in stanza. */
    fun sendChatMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || role == Role.IDLE || !isConnected) return
        socket?.send(
            WatchPartyMessage(
                type = "CHAT",
                name = localDisplayName(),
                text = clean,
            )
        )
    }

    /** Solo l'host può chiamarlo: imposta cosa può fare il guest. */
    fun sendPermissionsToGuest(permissions: ParticipantPermissions) {
        if (role != Role.HOST) return
        guestPermissions = permissions
        socket?.send(
            WatchPartyMessage(
                type = "PERMISSIONS",
                canPlayPause = permissions.canPlayPause,
                canSeek = permissions.canSeek,
                canNextEpisode = permissions.canNextEpisode,
            )
        )
    }

    /** Solo l'host: blocca/sblocca i nuovi ingressi (lucchetto stanza). Il server lo salva e fa da arbitro. */
    fun setRoomLock(locked: Boolean) {
        if (role != Role.HOST) return
        roomLocked = locked
        socket?.send(WatchPartyMessage(type = "LOCK", locked = locked))
        onParticipantsChanged?.invoke()
    }

    /** Solo l'host: espelle un partecipante. Il server chiude il socket del targetCid. */
    fun kickParticipant(cid: String) {
        if (role != Role.HOST) return
        socket?.send(WatchPartyMessage(type = "KICK", targetCid = cid))
    }

    /** Solo l'host: promuove un altro partecipante a host. Il server salva
     *  l'override e lo comunica a tutti con HOST_CHANGED. */
    fun promoteParticipant(cid: String) {
        if (role != Role.HOST) return
        socket?.send(WatchPartyMessage(type = "PROMOTE", targetCid = cid))
    }

    private fun applyRemote(block: () -> Unit) {
        lastRemoteCommandMs = System.currentTimeMillis()
        mainHandler.post(block)
    }
}
