package app.podara.desktop

import app.podara.util.MediaKeyAction
import app.podara.util.WindowsMediaKeys
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end check of the global media-key hook: install the hook, inject a
 * VK_MEDIA_PLAY_PAUSE press with keybd_event and make sure the callback fires.
 */
class MediaKeyHookTest {

    private interface KeyApi : Library {
        fun keybd_event(bVk: Byte, bScan: Byte, dwFlags: Int, dwExtraInfo: Pointer?)
    }

    @Test
    fun injectedMediaKeyReachesHook() {
        Assume.assumeTrue(
            "media keys are Windows-only",
            System.getProperty("os.name").orEmpty().startsWith("Windows")
        )
        val received = LinkedBlockingQueue<MediaKeyAction>()
        val hook = WindowsMediaKeys(
            onAction = { received.add(it) },
            shouldIntercept = { true }
        )
        hook.start()
        Thread.sleep(400)
        // No interactive desktop (CI / service session): the hook cannot be installed,
        // so there is nothing to assert.
        Assume.assumeTrue("global keyboard hook unavailable in this session", hook.isRunning)

        val api = Native.load("user32", KeyApi::class.java)
        val vkPlayPause: Byte = 0xB3.toByte()
        api.keybd_event(vkPlayPause, 0, 0, null)
        api.keybd_event(vkPlayPause, 0, 2, null) // KEYEVENTF_KEYUP

        val action = received.poll(3, TimeUnit.SECONDS)
        hook.stop()
        assertEquals(MediaKeyAction.PLAY_PAUSE, action)
    }
}
