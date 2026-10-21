package it.dogior.hadEnough.sleepguard

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource

private const val TAG = "SleepGuardWatcher"
private const val TICK_MS = 1000L

object SleepGuardWatcher {

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private var hostPlugin: SleepGuardPlugin? = null

    private var currentFragment: FullScreenPlayer? = null
    private var overlayView: View? = null

    private var lastInteractionAt = 0L
    private var episodesSinceInteraction = 0
    private var lastSeenTitle: String? = null
    private var hostTitleViewId: Int = 0
    private var hostTitleViewIdResolved = false

    private var ourKeyListener: ((Pair<KeyEvent?, Boolean>) -> Boolean)? = null
    private var originalKeyListener: ((Pair<KeyEvent?, Boolean>) -> Boolean)? = null

    private var popupShowing = false

    fun start(plugin: SleepGuardPlugin) {
        hostPlugin = plugin
        if (started) return
        started = true
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { heartbeat() }.onFailure { Log.e(TAG, "heartbeat() failed", it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun heartbeat() {
        val fragment = currentPlayerFragment()

        if (fragment == null) {
            detach()
            return
        }

        if (fragment !== currentFragment) {
            attach(fragment)
        }

        if (popupShowing) return

        val ctx = fragment.requireContext()

        if (SleepGuardPrefs.isVolumeKeyResetEnabled(ctx)) {
            ensureKeyListenerChained()
        } else {
            restoreKeyListenerIfOurs()
        }

        if (SleepGuardPrefs.isEpisodeCounterEnabled(ctx)) {
            pollEpisodeTitle(fragment)
        }

        val elapsedMinutes = (System.currentTimeMillis() - lastInteractionAt) / 60_000.0
        val timeTriggered = elapsedMinutes >= SleepGuardPrefs.inactivityMinutes(ctx)
        val episodeTriggered = SleepGuardPrefs.isEpisodeCounterEnabled(ctx) &&
            episodesSinceInteraction >= SleepGuardPrefs.episodeThreshold(ctx)

        if (timeTriggered || episodeTriggered) {
            triggerPopup(fragment)
        }
    }

    private fun currentPlayerFragment(): GeneratorPlayer? = runCatching {
        val activity = CommonActivity.activity as? MainActivity ?: return@runCatching null
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return@runCatching null
        val top: Fragment? = navHost.childFragmentManager.fragments.lastOrNull()
        top as? GeneratorPlayer
    }.getOrNull()

    private fun attach(fragment: FullScreenPlayer) {
        detach()
        currentFragment = fragment
        lastInteractionAt = System.currentTimeMillis()
        episodesSinceInteraction = 0
        lastSeenTitle = null
        attachTouchOverlay(fragment)
    }

    private fun detach() {
        if (currentFragment == null) return
        removeTouchOverlay()
        restoreKeyListenerIfOurs()
        currentFragment = null
        popupShowing = false
    }

    private fun attachTouchOverlay(fragment: FullScreenPlayer) {
        val root = fragment.view as? ViewGroup ?: return
        val overlay = FrameLayout(root.context).apply {
            isClickable = false
            isFocusable = false
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    lastInteractionAt = System.currentTimeMillis()
                }
                false
            }
        }
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlayView = overlay
    }

    private fun removeTouchOverlay() {
        val overlay = overlayView ?: return
        runCatching { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        overlayView = null
    }

    // CommonActivity.keyEventListener is a single shared slot: always chain, never overwrite
    private fun ensureKeyListenerChained() {
        if (CommonActivity.keyEventListener === ourKeyListener) return
        originalKeyListener = CommonActivity.keyEventListener
        val wrapper: (Pair<KeyEvent?, Boolean>) -> Boolean = { pair ->
            val (event, _) = pair
            if (event != null && isRelevantKey(event.keyCode)) {
                lastInteractionAt = System.currentTimeMillis()
            }
            originalKeyListener?.invoke(pair) ?: false
        }
        ourKeyListener = wrapper
        CommonActivity.keyEventListener = wrapper
    }

    private fun restoreKeyListenerIfOurs() {
        if (CommonActivity.keyEventListener === ourKeyListener) {
            CommonActivity.keyEventListener = originalKeyListener
        }
        ourKeyListener = null
        originalKeyListener = null
    }

    private fun isRelevantKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_BACK -> true
        else -> false
    }

    private fun pollEpisodeTitle(fragment: FullScreenPlayer) {
        val root = fragment.view ?: return
        if (!hostTitleViewIdResolved) {
            hostTitleViewId = runCatching {
                fragment.requireContext().resources.getIdentifier(
                    "player_video_title", "id", fragment.requireContext().packageName
                )
            }.getOrDefault(0)
            hostTitleViewIdResolved = true
        }
        if (hostTitleViewId == 0) return
        val titleView = root.findViewById<TextView>(hostTitleViewId) ?: return
        val text = titleView.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (lastSeenTitle == null) {
            lastSeenTitle = text
            return
        }
        if (text != lastSeenTitle) {
            lastSeenTitle = text
            episodesSinceInteraction++
        }
    }

    private fun triggerPopup(fragment: FullScreenPlayer) {
        if (popupShowing) return
        val plugin = hostPlugin ?: return
        popupShowing = true

        runCatching { fragment.player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync) }
            .onFailure { Log.e(TAG, "Could not pause the player", it) }

        val popup = SleepGuardPopupFragment(
            plugin = plugin,
            onYes = {
                popupShowing = false
                lastInteractionAt = System.currentTimeMillis()
                episodesSinceInteraction = 0
                lastSeenTitle = null
                runCatching { fragment.player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync) }
                    .onFailure { Log.e(TAG, "Could not resume the player", it) }
            },
            onNo = {
                // Same as the system Back button: closes only the player
                popupShowing = false
                runCatching {
                    val activity = fragment.activity
                    val dispatcher = activity?.onBackPressedDispatcher
                    if (dispatcher != null && dispatcher.hasEnabledCallbacks()) {
                        dispatcher.onBackPressed()
                    } else {
                        activity?.finish()
                    }
                }.onFailure {
                    Log.w(TAG, "Could not close the player, falling back to finishing the activity", it)
                    runCatching { fragment.activity?.finish() }
                }
            },
        )

        runCatching {
            popup.show(fragment.parentFragmentManager, "SleepGuardPopup")
        }.onFailure {
            Log.e(TAG, "Could not show the SleepGuard popup", it)
            popupShowing = false
        }
    }
}
