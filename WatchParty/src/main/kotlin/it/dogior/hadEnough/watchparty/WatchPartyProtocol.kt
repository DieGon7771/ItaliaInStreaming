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
 * Unico envelope scambiato sul canale WebSocket. Il relay non lo interpreta:
 * si limita a inoltrarlo all'altro peer nella stessa stanza (vedi WatchPartyServer/worker.js).
 *
 * type possibili (vedi WatchPartyManager.handleRemoteMessage):
 *  - "PEER_JOINED" / "PEER_LEFT" — generati dal server, non dal client
 *  - "HELLO"                     — scambio del nome visualizzato
 *  - "SYNC_REQUEST" / "SYNC_STATE" — sincronizzazione periodica (host → guest)
 *  - "FORCE_SYNC"                — risync esplicito dal pulsante "Risincronizza ora"
 *  - "PLAY" / "PAUSE" / "SEEK"   — comandi di riproduzione (SEEK avvia il gate sincronizzato)
 *  - "READY"                     — un lato ha finito di caricare dopo un seek
 *  - "CHAT"                      — messaggio della chat (name, text)
 *  - "EPISODE_HINT" / "NEXT_EPISODE" — cambio episodio: notifica morbida / comando
 *  - "PERMISSIONS"               — permessi che l'host imposta per il guest
 *  - "LEAVE_ROOM"
 */
@Serializable
data class WatchPartyMessage(
    val type: String,
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
