package app.podara.player

import app.podara.util.Logger
import app.podara.util.MediaKeyAction
import app.podara.util.Settings
import app.podara.util.WindowsMediaKeys
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

private const val TAG = "MediaKeyController"

/**
 * Bridges hardware media keys to [MediaPlayerState].
 *
 * All player mutations are marshalled onto the AWT event thread (the Compose UI thread)
 * because the hook callback arrives on its own native thread and Compose state must not
 * be mutated mid-recomposition from arbitrary threads.
 *
 * Interception policy:
 *  - transport keys (play/pause, next, previous, stop) are consumed whenever the feature
 *    is enabled, matching every other desktop music player;
 *  - volume keys are consumed only while something is loaded, so that an idle Podara
 *    never steals the system volume keys from the OS.
 */
class MediaKeyController(
    private val playerState: MediaPlayerState
) {
    private val hook = WindowsMediaKeys(
        onAction = { action -> dispatch(action) },
        shouldIntercept = ::shouldIntercept
    )
    private val started = AtomicBoolean(false)

    fun start() {
        if (!Settings.isMediaKeysEnabled()) {
            Logger.i(TAG, "Media keys disabled in settings — hook not installed")
            return
        }
        if (started.getAndSet(true)) return
        try {
            hook.start()
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to start media key hook", e)
            started.set(false)
        }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        try {
            hook.stop()
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to stop media key hook", e)
        }
    }

    fun restart() {
        stop()
        start()
    }

    /** Applies the current setting without tearing down an already-correct hook. */
    fun syncEnabled() {
        val wanted = Settings.isMediaKeysEnabled()
        if (wanted && !started.get()) start()
        else if (!wanted && started.get()) stop()
    }

    private fun shouldIntercept(action: MediaKeyAction): Boolean {
        if (!Settings.isMediaKeysEnabled()) return false
        return when (action) {
            MediaKeyAction.VOLUME_UP, MediaKeyAction.VOLUME_DOWN, MediaKeyAction.MUTE ->
                Settings.isVolumeKeysEnabled() && playerState.currentUrl != null
            else -> true
        }
    }

    private fun dispatch(action: MediaKeyAction) {
        Logger.d(TAG, "Media key: $action")
        SwingUtilities.invokeLater {
            try {
                handle(action)
            } catch (e: Throwable) {
                Logger.e(TAG, "Failed to handle media key $action", e)
            }
        }
    }

    private fun handle(action: MediaKeyAction) {
        when (action) {
            MediaKeyAction.PLAY_PAUSE -> togglePlayback()
            MediaKeyAction.NEXT -> playerState.playNext()
            MediaKeyAction.PREVIOUS -> playerState.playPrevious()
            MediaKeyAction.STOP -> playerState.pause()
            MediaKeyAction.VOLUME_UP -> changeVolume(+VOLUME_STEP)
            MediaKeyAction.VOLUME_DOWN -> changeVolume(-VOLUME_STEP)
            MediaKeyAction.MUTE -> playerState.toggleMute()
        }
    }

    private fun togglePlayback() {
        // Nothing loaded yet (fresh start, or queue restored but never played):
        // start from the queue head instead of doing nothing.
        if (playerState.currentUrl == null) {
            if (playerState.queue.isNotEmpty()) {
                val index = playerState.queueIndex.coerceAtLeast(0)
                playerState.playFromQueue(index)
            }
            return
        }
        playerState.togglePlayPause()
    }

    private fun changeVolume(delta: Int) {
        if (playerState.currentUrl == null) return
        playerState.changeVolume((playerState.volume + delta).coerceIn(0, 100))
    }

    private companion object {
        const val VOLUME_STEP = 5
    }
}
