package it.dogior.hadEnough.watchparty

import kotlinx.serialization.Serializable

/**
 * Informazioni sulla sorgente in riproduzione, condivise da Host a Guest
 * quando la stanza viene creata o quando l'host cambia episodio/mirror.
 */
@Serializable
data class MediaInfo(
    val url: String,
    val title: String? = null,
    val position: Long = 0L,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val quality: Int = 0,
    val type: String? = null,
)

/**
 * Un solo partecipante della stanza, così come lo conosce il server.
 * seq = ordine di ingresso (1 = primo entrato, cioè l'host originale).
 */
@Serializable
data class PeerInfo(
    val cid: String,
    val seq: Int,
)

/**
 * Unico envelope scambiato sul canale WebSocket. Il relay non lo interpreta:
 * si limita a inoltrarlo agli altri peer nella stessa stanza (vedi WatchPartyServer/worker.js).
 *
 * Stanza fino a 5 utenti. Ogni client ha un cid (UUID) stabile: il server lo usa
 * per tracciare roster e ordine di ingresso, e per l'host migration.
 *
 * type possibili (vedi WatchPartyManager.handleRemoteMessage):
 *  - "ROOM_STATE"                — al nuovo entrato: count, hostCid, roster (incluso sé stesso) e stato lucchetto
 *  - "PEER_JOINED" / "PEER_LEFT" — generati dal server con cid/seq/count/hostCid; PEER_LEFT porta anche kicked=true quando è un'espulsione
 *  - "LOCK" / "LOCK_STATE"       — lucchetto stanza: l'host lo imposta (server), lo stato torna via LOCK_STATE
 *  - "KICK"                      — espulsione: l'host indica targetCid, il server chiude il socket target
 *  - "PROMOTE" / "HOST_CHANGED"  — promozione manuale: l'host indica targetCid, il server comunica il nuovo hostCid
 *  - "HELLO"                     — scambio del nome visualizzato (porta il cid)
 *  - "SYNC_REQUEST" / "SYNC_STATE" — sincronizzazione periodica (host → guest)
 *  - "FORCE_SYNC"                — risync esplicito dal pulsante "Risincronizza ora"
 *  - "PLAY" / "PAUSE" / "SEEK"   — comandi di riproduzione (SEEK avvia il gate sincronizzato)
 *  - "READY"                     — un lato ha finito di caricare dopo un seek
 *  - "CHAT"                      — messaggio della chat (name, text)
 *  - "EPISODE_HINT" / "NEXT_EPISODE" — cambio episodio: notifica morbida / comando
 *  - "PERMISSIONS"               — permessi che l'host imposta per i guest
 *  - "LEAVE_ROOM"
 */
@Serializable
data class WatchPartyMessage(
    val type: String,
    /** Identità del mittente. Iniettata dal WatchPartySocket su ogni invio; i messaggi del server la portano per roster/migration. */
    val cid: String? = null,
    /** Ordine di ingresso (solo nei messaggi del server: ROOM_STATE / PEER_JOINED / PEER_LEFT). */
    val seq: Int? = null,
    /** Numero totale di client in stanza (incluso il mittente del messaggio), solo messaggi del server. */
    val count: Int? = null,
    /** cid dell'host corrente, presente nei messaggi del server per l'host migration. */
    val hostCid: String? = null,
    /** Roster completo con i seq di ingresso, solo in ROOM_STATE. */
    val roster: List<PeerInfo>? = null,
    /** Stato del lucchetto stanza (LOCK / LOCK_STATE / ROOM_STATE). */
    val locked: Boolean? = null,
    /** Destinatario di un KICK: il server chiude il socket con questo cid. */
    val targetCid: String? = null,
    /** In PEER_LEFT: true se il partecipante è stato espulso dall'host (KICK), non è uscito da solo. */
    val kicked: Boolean? = null,
    val position: Long? = null,
    val playing: Boolean? = null,
    val url: String? = null,
    val title: String? = null,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val quality: Int = 0,
    val name: String? = null,
    val text: String? = null,
    val canPlayPause: Boolean? = null,
    val canSeek: Boolean? = null,
    val canNextEpisode: Boolean? = null,
)
