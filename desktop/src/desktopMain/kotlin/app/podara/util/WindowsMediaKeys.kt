package app.podara.util

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicBoolean

/** Actions a hardware media key can trigger. */
enum class MediaKeyAction {
    PLAY_PAUSE, NEXT, PREVIOUS, STOP, VOLUME_UP, VOLUME_DOWN, MUTE
}

/**
 * System-wide media-key listener for Windows.
 *
 * Windows delivers media keys (play/pause, next, previous, stop, volume up/down/mute)
 * as plain key presses to the foreground window. Compose Desktop / AWT never turns
 * them into `KeyEvent`s, so a Compose `onKeyEvent` handler can never see them — that
 * is why the app appeared to ignore media keys entirely.
 *
 * The only reliable way to get them (focused or not, exactly like other desktop music
 * players) is a low-level keyboard hook (`WH_KEYBOARD_LL`). Windows requires the
 * installing thread to own a message pump, so the hook lives on its own daemon thread
 * that runs `GetMessage`/`DispatchMessage` until [stop] is called.
 *
 * The callback runs on that thread and must return fast — the OS silently drops hooks
 * whose callback blocks for too long. Handlers are expected to hand work off to the UI
 * thread; this class never blocks.
 */
class WindowsMediaKeys(
    private val onAction: (MediaKeyAction) -> Unit,
    private val shouldIntercept: (MediaKeyAction) -> Boolean = { true }
) {
    private interface Win32 : StdCallLibrary {
        fun SetWindowsHookExW(idHook: Int, lpfn: LowLevelKeyboardProc?, hMod: Pointer?, dwThreadId: Int): Pointer?
        fun UnhookWindowsHookEx(hhk: Pointer?): Boolean
        fun CallNextHookEx(hhk: Pointer?, nCode: Int, wParam: Pointer?, lParam: Pointer?): Long
        fun GetMessageW(lpMsg: Pointer, hWnd: Pointer?, wMsgFilterMin: Int, wMsgFilterMax: Int): Int
        fun TranslateMessage(lpMsg: Pointer): Boolean
        fun DispatchMessageW(lpMsg: Pointer): Long
        fun PostThreadMessageW(idThread: Int, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
    }

    private interface Kernel32 : Library {
        fun GetCurrentThreadId(): Int
    }

    private interface LowLevelKeyboardProc : StdCallCallback {
        fun callback(nCode: Int, wParam: Pointer?, lParam: Pointer?): Long
    }

    private companion object {
        const val WH_KEYBOARD_LL = 13

        const val WM_KEYDOWN = 0x0100
        const val WM_SYSKEYDOWN = 0x0104
        const val WM_QUIT = 0x0012

        // KBDLLHOOKSTRUCT: vkCode is the first field; only that offset is needed here.
        const val OFFSET_VK_CODE = 0L

        const val VK_VOLUME_MUTE = 0xAD
        const val VK_VOLUME_DOWN = 0xAE
        const val VK_VOLUME_UP = 0xAF
        const val VK_MEDIA_NEXT_TRACK = 0xB0
        const val VK_MEDIA_PREV_TRACK = 0xB1
        const val VK_MEDIA_STOP = 0xB2
        const val VK_MEDIA_PLAY_PAUSE = 0xB3

        // Windows sends key repeats while a key is held; toggle-style actions must not flap.
        const val REPEAT_DEBOUNCE_MS = 350L

        const val MSG_BUFFER_SIZE = 96L

        val user32: Win32 by lazy { Native.load("user32", Win32::class.java) }
        val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

        fun actionForVirtualKey(vkCode: Int): MediaKeyAction? = when (vkCode) {
            VK_MEDIA_PLAY_PAUSE -> MediaKeyAction.PLAY_PAUSE
            VK_MEDIA_NEXT_TRACK -> MediaKeyAction.NEXT
            VK_MEDIA_PREV_TRACK -> MediaKeyAction.PREVIOUS
            VK_MEDIA_STOP -> MediaKeyAction.STOP
            VK_VOLUME_UP -> MediaKeyAction.VOLUME_UP
            VK_VOLUME_DOWN -> MediaKeyAction.VOLUME_DOWN
            VK_VOLUME_MUTE -> MediaKeyAction.MUTE
            else -> null
        }
    }

    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    @Volatile private var proc: LowLevelKeyboardProc? = null
    @Volatile private var hookHandle: Pointer? = null
    @Volatile private var hookThreadId: Int = 0
    private val lastTriggerAt = LongArray(0x100)

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get() && thread?.isAlive == true) return
        running.set(true)
        thread = Thread({ pumpMessages() }, "podara-media-keys").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val tid = hookThreadId
        if (tid != 0) {
            try {
                user32.PostThreadMessageW(tid, WM_QUIT, null, null)
            } catch (e: Throwable) {
                Logger.w("MediaKeys", "Failed to post WM_QUIT: ${e.message}")
            }
        }
        hookThreadId = 0
    }

    private fun pumpMessages() {
        hookThreadId = kernel32.GetCurrentThreadId()

        val callback = object : LowLevelKeyboardProc {
            override fun callback(nCode: Int, wParam: Pointer?, lParam: Pointer?): Long {
                if (nCode >= 0 && wParam != null && lParam != null) {
                    // WPARAM is a pointer-sized integer, not an address: read its value,
                    // never dereference it.
                    val message = Pointer.nativeValue(wParam).toInt()
                    if (message == WM_KEYDOWN || message == WM_SYSKEYDOWN) {
                        val action = actionForVirtualKey(lParam.getInt(OFFSET_VK_CODE))
                        if (action != null && !isRepeat(action)) {
                            val intercept = try {
                                shouldIntercept(action)
                            } catch (e: Throwable) {
                                Logger.e("MediaKeys", "shouldIntercept failed", e)
                                false
                            }
                            if (intercept) {
                                try {
                                    onAction(action)
                                } catch (e: Throwable) {
                                    Logger.e("MediaKeys", "onAction failed", e)
                                }
                                // Swallow the key so the OS / other players don't also react.
                                return 1L
                            }
                        }
                    }
                }
                return user32.CallNextHookEx(null, nCode, wParam, lParam)
            }
        }
        proc = callback

        val handle = try {
            user32.SetWindowsHookExW(WH_KEYBOARD_LL, callback, null, 0)
        } catch (e: Throwable) {
            Logger.e("MediaKeys", "SetWindowsHookEx failed", e)
            null
        }

        if (handle == null) {
            Logger.e("MediaKeys", "Keyboard hook could not be installed; media keys disabled")
            running.set(false)
            hookThreadId = 0
            return
        }
        hookHandle = handle
        Logger.i("MediaKeys", "Global media-key hook installed")

        val msg = Memory(MSG_BUFFER_SIZE)
        while (running.get()) {
            val result = user32.GetMessageW(msg, null, 0, 0)
            if (result <= 0) break
            user32.TranslateMessage(msg)
            user32.DispatchMessageW(msg)
        }

        try {
            user32.UnhookWindowsHookEx(hookHandle)
        } catch (e: Throwable) {
            Logger.w("MediaKeys", "Unhook failed: ${e.message}")
        }
        hookHandle = null
        proc = null
        running.set(false)
        hookThreadId = 0
        Logger.i("MediaKeys", "Global media-key hook removed")
    }

    /**
     * Suppresses auto-repeat for toggle-style keys. The very first press of a held
     * key always wins; repeats arrive every ~30 ms and are dropped.
     */
    private fun isRepeat(action: MediaKeyAction): Boolean {
        if (action != MediaKeyAction.PLAY_PAUSE && action != MediaKeyAction.MUTE &&
            action != MediaKeyAction.STOP
        ) return false
        val now = System.currentTimeMillis()
        val index = action.ordinal
        val last = lastTriggerAt[index]
        if (now - last < REPEAT_DEBOUNCE_MS) return true
        lastTriggerAt[index] = now
        return false
    }
}
