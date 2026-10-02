package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Finds the wheel's phone-button presses in `getevent -q` output (lines like `0001 010a 00000001`). Output
 * arrives in chunks, so a partial last line is kept for the next one.
 */
internal class CallKeyEvents {
    private val partial = StringBuilder()

    /** How many presses of the phone button [chunk] completes. */
    fun presses(chunk: String): Int {
        partial.append(chunk)
        var count = 0
        while (true) {
            val end = partial.indexOf("\n")
            if (end < 0) break
            val line = partial.substring(0, end).trim()
            partial.delete(0, end + 1)
            if (line == PRESS) count++
        }
        if (partial.length > MAX_PARTIAL) partial.setLength(0)
        return count
    }

    companion object {
        /** EV_KEY, scan code 0x10a (Android KEYCODE 313, BYD's dial/answer key), down. */
        const val PRESS = "0001 010a 00000001"
        private const val MAX_PARTIAL = 256
    }
}

/**
 * Optional, needs ADB over network: the steering wheel's phone button for CarPlay calls. BYD's window
 * manager always takes that key (KEYCODE 313) and opens its own phone screen; apps never see it, and the
 * global firmware's hand-over to CarPlay does not exist on this one. So while a CarPlay session runs, DiPlay
 * reads the wheel's input device ("simulate-keys") through a shell connection of its own and reports each
 * press; what a press does is up to the session (answer or end a CarPlay call).
 */
internal object BydCallKey {
    private const val TAG = "DiPlay-BYD-CallKey"
    private const val RETRY_MILLIS = 15_000L

    // Finds the wheel's input device by name, so its event number does not matter. The shell may not read
    // sysfs device names, so the name comes from getevent itself.
    private const val COMMAND = "d=\$(getevent -pl 2>/dev/null | awk '/^add device/ {d=\$4} " +
        "/name:/ && /\"simulate-keys\"/ {print d; exit}'); [ -n \"\$d\" ] && exec getevent -q \$d"

    @Volatile private var app: Context? = null
    @Volatile private var onPress: (() -> Unit)? = null
    @Volatile private var running = false
    @Volatile private var adb: LocalAdb? = null
    private var reader: Thread? = null

    /** A CarPlay session started; reads the button if the setting is on. */
    @Synchronized
    fun start(context: Context, pressed: () -> Unit) {
        app = context.applicationContext
        onPress = pressed
        if (BydOutputSettings.callKey(context)) startReader()
    }

    /** The session ended. */
    @Synchronized
    fun stop() {
        onPress = null
        stopReader()
    }

    /** The setting changed: applies at once while a session runs. */
    @Synchronized
    fun settingChanged(enabled: Boolean) {
        if (enabled && onPress != null) startReader() else stopReader()
    }

    private fun startReader() {
        if (running) return
        val context = app ?: return
        running = true
        reader = Thread({ read(context) }, "diplay-call-key").apply { isDaemon = true; start() }
    }

    private fun stopReader() {
        running = false
        adb?.close()
        reader?.interrupt()
        reader = null
    }

    private fun read(context: Context) {
        var logged = false
        while (running) {
            val client = LocalAdb(AdbKeys.load(context))
            adb = client
            val access = client.connect(mayAsk = false)
            if (access == LocalAdb.Access.READY) {
                if (!logged) Log.i(TAG, "reading the wheel's phone button")
                logged = true
                val events = CallKeyEvents()
                client.stream(COMMAND) { chunk ->
                    repeat(events.presses(chunk)) {
                        Log.i(TAG, "phone button")
                        onPress?.invoke()
                    }
                }
            } else if (!logged) {
                Log.w(TAG, "ADB access $access")
            }
            client.close()
            if (!running) break
            try {
                Thread.sleep(RETRY_MILLIS)
            } catch (_: InterruptedException) {
                break
            }
        }
        adb = null
    }
}
