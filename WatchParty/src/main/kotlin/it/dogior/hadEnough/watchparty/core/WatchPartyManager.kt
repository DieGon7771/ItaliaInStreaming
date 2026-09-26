package it.dogior.hadEnough.watchparty

import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.CloudStreamApp
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
 * Stato locale rilevato via POLLING (non un listener sul player interno,
 * un plugin non può agganciarsi al suo event bus). Prevenzione loop: un
 * comando remoto applicato marca `lastRemoteCommandMs`, il polling ignora
 * le variazioni entro ECHO_WINDOW_MS da quel momento.
 *
 * Multi-peer: il server traccia cid/seq/hostCid; alla promozione di un
 * nuovo host (HOST_CHANGED) ci si allinea al ruolo indicato.
 *
 * LIMITE NOTO: il cambio episodio non è propagabile automaticamente
 * (nessun host espone un metodo pubblico per caricare un URL): si invia
 * solo "EPISODE_HINT" col titolo, l'utente cambia a mano.
 *
 * Il player si raggiunge sempre tramite WatchPartyPlayback (vedi
 * WatchPartyPlaybackBridge.kt): questa classe non conosce mai
 * IPlayer/CSPlayerEvent direttamente.
 */
class WatchPartyManager {

    enum class Role { IDLE, HOST, GUEST }

    /** Stato di connessione al relay, indipendente da "gli altri sono nella stanza". */
    enum class ConnectionState { DISCONNESSO, CONNESSIONE_IN_CORSO, CONNESSO, RICONNESSIONE_IN_CORSO }

    companion object {
        const val MAX_PARTICIPANTS = 5
        /** Leggibile/scrivibile da Impostazioni avanzate ("Sync polling"); riletta ad ogni ciclo. */
        fun pollIntervalMs(): Long =
            CloudStreamApp.getKey<String>("wp_poll_interval_ms")?.toLongOrNull()?.coerceIn(50L, 400L) ?: 200L
        private const val ECHO_WINDOW_MS = 500L // copre solo il tick subito dopo un comando remoto
        private const val SEEK_JUMP_THRESHOLD_MS = 1200L
        private const val RESYNC_THRESHOLD_MS = 1500L
        private const val HEARTBEAT_INTERVAL_MS = 6000L
        private const val SEEK_SEND_DEBOUNCE_MS = 220L
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 15000L
        private const val GATE_SAFETY_TIMEOUT_MS = 9000L
        private const val LOCAL_SEEK_READY_MS = 900L // buffering presunto dopo un seek prima di mandare READY

        /** Endpoint del relay, iniettato a build-time dal secret WATCHPARTY_RELAY (GitHub Actions). */
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

    /** cid entrati (PEER_JOINED) di cui non ho ancora il nome: consumato al primo
     *  HELLO, dove emetto la bolla "X entered the room" (solo per chi era già dentro). */
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

    /**
     * Nome del profilo locale attivo, o "Guest" se non trovato.
     * CloudStream: DataStoreHelper. Nuvio: NuvioProfileName (reflection,
     * DataStoreHelper non esiste su quell'host) — dettagli lì.
     */
    fun localDisplayName(): String {
        val cloudStreamName = runCatching {
            val account = DataStoreHelper.accounts.find { it.keyIndex == DataStoreHelper.selectedKeyIndex }
            account?.name?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (cloudStreamName != null) return cloudStreamName

        NuvioProfileName.currentName()?.let { return it }

        return "Guest"
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
        peerPresent = false

        socket = WatchPartySocket(
            baseWsUrl = relayUrl,
            clientId = myClientId,
            onOpen = {
                mainHandler.post {
                    reconnectAttempt = 0
                    connectionState = ConnectionState.CONNESSO
                    onStatusText?.invoke("Connected to the server, waiting for other participants…")
                    socket?.send(WatchPartyMessage(type = "HELLO", name = localDisplayName()))
                    if (role == Role.GUEST) socket?.send("SYNC_REQUEST")
                }
            },
            onMessage = { msg -> mainHandler.post { handleRemoteMessage(msg) } },
            onClosed = { code, reason ->
                mainHandler.post {
                    if (reason == "kicked") {
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
                        409 -> { // stanza piena, non ha senso riprovare
                            shouldStayConnected = false
                            onStatusText?.invoke("The room is full (max $MAX_PARTICIPANTS people)")
                        }
                        403 -> { // stanza bloccata dall'host, non ha senso riprovare
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
                delay(pollIntervalMs())
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
        if (!WatchPartyPlayback.isPlayerScreenActive()) return

        val playing = WatchPartyPlayback.getIsPlaying()
        val position = WatchPartyPlayback.getPosition() ?: return

        // ignora solo il tick subito dopo un comando applicato da remoto
        val withinEchoWindow = System.currentTimeMillis() - lastRemoteCommandMs < ECHO_WINDOW_MS

        val prevPlaying = lastKnownPlaying
        val prevPosition = lastKnownPosition
        lastKnownPlaying = playing
        lastKnownPosition = position

        if (withinEchoWindow) return
        if (prevPlaying == null) return // primo tick, solo inizializza

        // seek: salto di posizione più grande di quanto spiegherebbe il solo
        // scorrere del tempo tra un tick e l'altro
        val expectedDrift = pollIntervalMs() + 400L
        if (abs(position - prevPosition) > expectedDrift.coerceAtLeast(SEEK_JUMP_THRESHOLD_MS)) {
            if (role == Role.GUEST && !myPermissions.canSeek) {
                revertUnauthorizedLocalChange(prevPosition, prevPlaying)
                return
            }
            // piccolo debounce: se l'utente sta per premere play subito dopo il seek,
            // rileggiamo lo stato al momento dell'invio invece di fidarci di quello
            // letto nell'istante esatto del salto
            scope.launch {
                delay(SEEK_SEND_DEBOUNCE_MS)
                withContext(Dispatchers.Main) {
                    if (!WatchPartyPlayback.isPlayerScreenActive()) return@withContext
                    val finalPos = WatchPartyPlayback.getPosition() ?: position
                    val finalPlaying = WatchPartyPlayback.getIsPlaying()
                    lastKnownPosition = finalPos
                    lastKnownPlaying = finalPlaying
                    socket?.send(WatchPartyMessage(type = "SEEK", position = finalPos, playing = finalPlaying))
                    beginSeekGate(finalPos, finalPlaying) // anche io aspetto il gate
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
        if (!WatchPartyPlayback.isPlayerScreenActive()) return
        lastRemoteCommandMs = System.currentTimeMillis() // riusa la finestra anti-eco per non ri-rilevarlo
        WatchPartyPlayback.seekTo(pos)
        if (playing != null) {
            if (playing) WatchPartyPlayback.play() else WatchPartyPlayback.pause()
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
                if (!WatchPartyPlayback.isPlayerScreenActive()) return@applyRemote
                val current = WatchPartyPlayback.getPosition() ?: 0L
                // heartbeat periodico: correggi solo se lo scarto è reale, niente
                // seek continui che darebbero fastidio durante la visione normale
                msg.position?.let {
                    if (abs(current - it) > RESYNC_THRESHOLD_MS) WatchPartyPlayback.seekTo(it)
                }
                if (msg.playing != null) {
                    if (msg.playing) WatchPartyPlayback.play() else WatchPartyPlayback.pause()
                }
            }

            "FORCE_SYNC" -> {
                // richiesta esplicita dell'utente: applica sempre (a differenza di
                // SYNC_STATE), passando dallo stesso gate di un SEEK organico per
                // evitare che ognuno riparta per conto suo
                val pos = msg.position ?: return
                val playing = msg.playing ?: true
                lastRemoteCommandMs = System.currentTimeMillis()
                mainHandler.post { beginSeekGate(pos, playing) }
            }

            "PLAY" -> applyRemote {
                WatchPartyPlayback.play()
            }

            "PAUSE" -> applyRemote {
                WatchPartyPlayback.pause()
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
                // Su host che non supportano il comando (es. Nuvio Enhanced) questo
                // no-op silenziosamente: nextEpisode() ritorna false, nessun crash.
                WatchPartyPlayback.nextEpisode(localUserAction = false)
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
     * Forza un resync SEMPRE applicato (a differenza dell'heartbeat, che
     * corregge solo se lo scarto è reale) — visibile all'altra parte anche
     * quando non c'era nulla da correggere.
     */
    fun requestResyncNow() {
        if (!WatchPartyPlayback.isPlayerScreenActive()) return
        val position = WatchPartyPlayback.getPosition() ?: 0L
        val playing = WatchPartyPlayback.getIsPlaying()
        socket?.send(
            WatchPartyMessage(
                type = "FORCE_SYNC",
                position = position,
                playing = playing,
            )
        )
        beginSeekGate(position, playing) // anche chi lo richiede passa dal gate
        onStatusText?.invoke("Resync sent to all participants")
    }

    // ---------------------------------------------------------------------
    // Gate di attesa sincronizzata dopo un seek: tutti in pausa con
    // rotellina finché non sono davvero pronti, poi ripartono insieme. Il
    // lato locale manda READY dopo LOCAL_SEEK_READY_MS, il resolve avviene
    // quando tutti gli altri sono pronti (o dopo il timeout di sicurezza).
    // Un nuovo seek durante l'attesa invalida quella vecchia (gateGeneration).
    // ---------------------------------------------------------------------

    private fun beginSeekGate(targetPos: Long, expectedPlaying: Boolean) {
        if (!WatchPartyPlayback.isPlayerScreenActive()) return
        gateActive = true
        gateGeneration++
        val myGen = gateGeneration
        gateExpectedPlaying = expectedPlaying
        localReady = false
        remoteReadyCount = 0
        onBufferingGateChanged?.invoke(true)

        lastRemoteCommandMs = System.currentTimeMillis()
        WatchPartyPlayback.seekTo(targetPos)
        WatchPartyPlayback.pause() // una sola pausa pulita, niente doppia transizione play→pausa→play

        // il locale si considera "pronto" dopo un piccolo buffering, poi avvisa
        // gli altri; il resolve aspetta comunque i loro READY
        mainHandler.postDelayed({
            if (gateActive && gateGeneration == myGen) {
                localReady = true
                socket?.send(WatchPartyMessage(type = "READY"))
                maybeResolveGate(myGen)
            }
        }, LOCAL_SEEK_READY_MS)

        // rete di sicurezza: se un READY va perso, non restare bloccati
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
        if (gateExpectedPlaying) WatchPartyPlayback.play() else WatchPartyPlayback.pause()
    }

    private fun sendSyncState() {
        if (!WatchPartyPlayback.isPlayerScreenActive()) return
        socket?.send(
            WatchPartyMessage(
                type = "SYNC_STATE",
                position = WatchPartyPlayback.getPosition() ?: 0L,
                playing = WatchPartyPlayback.getIsPlaying(),
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
        val handled = WatchPartyPlayback.nextEpisode(localUserAction = true)
        if (!handled) {
            // host che non espone un comando "prossimo episodio" (es. Nuvio via
            // MediaSession): nessun effetto locale, ma niente crash e niente da propagare
            onStatusText?.invoke("This host doesn't support changing episode from here")
            return
        }
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
