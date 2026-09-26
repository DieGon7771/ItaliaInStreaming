package it.dogior.hadEnough.watchparty

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.lagradost.cloudstream3.CommonActivity
import java.lang.ref.WeakReference

/**
 * Bridge per Nuvio Enhanced (build "Android Full").
 *
 * FONTE PRIMARIA: androidx.media3.ui.PlayerView. Nuvio usa Media3/ExoPlayer
 * per riprodurre: a differenza delle classi interne di Nuvio (rinominate da
 * R8 in release) o di Material (assente, essendo un'app Compose/Material3),
 * Media3 è una libreria AndroidX che Nuvio porta davvero con sé — cercarla
 * nell'albero delle view funziona senza passare da notifiche/permessi/
 * servizi in foreground (che su alcuni dispositivi, es. MIUI, falliscono in
 * silenzio: vedi i due fallback sotto, tenuti solo come rete di sicurezza).
 *
 * PlayerView.getPlayer() dà accesso diretto al Player reale: play/pause/
 * seekTo/isPlaying/currentPosition sono chiamate dirette, non un proxy.
 *
 * Limiti noti:
 *  - nessun "prossimo episodio" (nextEpisode ritorna sempre false): Player
 *    non ha un concetto di episodio, solo di traccia/posizione;
 *  - Nuvio ha anche un motore libmpv alternativo (per contenuti che
 *    ExoPlayer non gestisce bene) che non è una PlayerView: lì il bridge
 *    cade sui due fallback sotto, finora inaffidabili sul dispositivo di
 *    test — da verificare quando càpita un contenuto su quell'engine.
 */
class NuvioPlaybackBridge : WatchPartyPlaybackBridge {

    // --- Fonte primaria: PlayerView nell'albero delle view -------------

    private var cachedPlayerViewRef: WeakReference<PlayerView>? = null

    private fun findPlayerView(): PlayerView? {
        cachedPlayerViewRef?.get()?.let { cached ->
            if (cached.isShown && cached.player != null) return cached
        }
        val activity = CommonActivity.activity ?: return null
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val found = runCatching { searchPlayerView(decor) }.getOrNull()
        cachedPlayerViewRef = found?.let { WeakReference(it) }
        return found
    }

    private fun searchPlayerView(view: View): PlayerView? {
        if (view is PlayerView && view.player != null && view.isShown) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                searchPlayerView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun player(): Player? = findPlayerView()?.player

    // --- Fallback secondario: MediaSession via notifica -----------------
    // Rete di sicurezza per l'engine mpv (senza PlayerView). Sul dispositivo
    // di test la notifica "now playing" non parte mai, quindi oggi questo
    // ramo raramente aiuta — ma non fa danno, resta gratis.

    private val knownNotificationId = 0x4E55
    private var cachedToken: MediaSession.Token? = null
    private var cachedController: MediaController? = null

    private fun controller(): MediaController? {
        val activity = CommonActivity.activity ?: return null
        val notificationManager = runCatching {
            activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull() ?: return null
        val active = runCatching { notificationManager.activeNotifications }.getOrNull()
        if (active.isNullOrEmpty()) return null
        val statusBarNotification = active.firstOrNull { it.id == knownNotificationId }
            ?: active.firstOrNull { it.notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true }
            ?: return null
        val token = runCatching {
            @Suppress("DEPRECATION")
            statusBarNotification.notification.extras
                ?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        }.getOrNull() ?: return null
        if (token != cachedToken || cachedController == null) {
            cachedController = runCatching { MediaController(activity, token) }.getOrNull()
            cachedToken = token
        }
        return cachedController
    }

    // --- Fallback terziario: FLAG_KEEP_SCREEN_ON -------------------------
    // Solo per isPlayerScreenActive(). Sul dispositivo di test è risultato
    // false anche durante la riproduzione, quindi poco affidabile — tenuto
    // comunque, costa zero.

    private fun windowKeepsScreenOn(): Boolean = runCatching {
        val flags = CommonActivity.activity?.window?.attributes?.flags ?: return false
        (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    }.getOrDefault(false)

    // --- API pubblica ------------------------------------------------

    override fun isPlayerScreenActive(): Boolean =
        player() != null || controller() != null || windowKeepsScreenOn()

    override fun getIsPlaying(): Boolean {
        player()?.let { return it.isPlaying }
        val state = controller()?.playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    override fun getPosition(): Long? {
        player()?.let { return it.currentPosition }
        return controller()?.playbackState?.position
    }

    override fun seekTo(positionMs: Long) {
        player()?.let { it.seekTo(positionMs); return }
        controller()?.transportControls?.seekTo(positionMs)
    }

    override fun play() {
        player()?.let { it.play(); return }
        controller()?.transportControls?.play()
    }

    override fun pause() {
        player()?.let { it.pause(); return }
        controller()?.transportControls?.pause()
    }

    /** Né Player né la MediaSession di Nuvio espongono un concetto di "episodio": non supportato. */
    override fun nextEpisode(localUserAction: Boolean): Boolean = false

    override fun debugSnapshot(): String {
        val activity = CommonActivity.activity ?: return "activity=NO"
        val hasPlayerView = player() != null
        val nm = runCatching {
            activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
        val notifCount = runCatching { nm?.activeNotifications?.size }.getOrNull() ?: -1
        val keepScreenOn = windowKeepsScreenOn()
        val hasController = controller() != null
        // Corto apposta: deve stare leggibile in un Toast.
        return "pv=$hasPlayerView notif=$notifCount kso=$keepScreenOn ctrl=$hasController active=${isPlayerScreenActive()}"
    }
}
