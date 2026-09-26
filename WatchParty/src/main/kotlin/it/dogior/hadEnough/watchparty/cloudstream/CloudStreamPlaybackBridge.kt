package it.dogior.hadEnough.watchparty

import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.PlayerEventSource

/**
 * Bridge per CloudStream vero (o un fork che porta con sé le classi
 * com.lagradost.cloudstream3.ui.player.*). Comportamento identico a quello
 * che il plugin aveva prima di questo bridge: si appoggia semplicemente a
 * PlayerAccess/IPlayer, invariati.
 *
 * Questo file è l'UNICO che referenzia i tipi IPlayer/CSPlayerEvent/
 * PlayerEventSource: su un host dove queste classi non esistono (es. Nuvio
 * Enhanced) questa classe non viene mai istanziata (vedi WatchPartyPlayback
 * .detectHostBridge), quindi non viene mai verificata/caricata e non causa
 * crash — coerente con com'era già progettato PlayerAccess.kt.
 */
class CloudStreamPlaybackBridge : WatchPartyPlaybackBridge {

    override fun isPlayerScreenActive(): Boolean = PlayerAccess.isPlayerScreenActive()

    override fun getIsPlaying(): Boolean = PlayerAccess.currentPlayer()?.getIsPlaying() ?: false

    override fun getPosition(): Long? = PlayerAccess.currentPlayer()?.getPosition()

    override fun seekTo(positionMs: Long) {
        PlayerAccess.currentPlayer()?.seekTo(positionMs, PlayerEventSource.Sync)
    }

    override fun play() {
        PlayerAccess.currentPlayer()?.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync)
    }

    override fun pause() {
        PlayerAccess.currentPlayer()?.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync)
    }

    override fun nextEpisode(localUserAction: Boolean): Boolean {
        val player = PlayerAccess.currentPlayer() ?: return false
        val source = if (localUserAction) PlayerEventSource.UI else PlayerEventSource.Sync
        player.handleEvent(CSPlayerEvent.NextEpisode, source)
        return true
    }

    override fun debugSnapshot(): String =
        "CloudStream: player=${PlayerAccess.currentPlayer() != null}"
}
