package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors

/** What the dashboard's call card shows; [activeSinceMillis] is wall-clock time, set once the call is answered. */
internal data class ClusterCall(val name: String, val phase: Phase, val activeSinceMillis: Long?) {
    enum class Phase { RINGING, DIALING, ACTIVE }
}

/**
 * The CarPlay call for the dashboard, from iAP2 CallStateUpdate (0x4155): remote ID (0), display name (1),
 * status (2), direction (3) and call UUID (4). Each update describes one call and carries only what the
 * iPhone has; Disconnected removes the call, and an update without a UUID (sent when the session starts)
 * means there is none. An answered call wins over a ringing one, and that over one being dialled.
 */
internal class ClusterCallState(private val clock: () -> Long) {
    private class Call(var name: String = "", var status: Int = STATUS_DISCONNECTED, var activeSince: Long? = null)

    private val calls = LinkedHashMap<String, Call>()

    /** Updates the calls; returns what the dashboard should show now, null for no call. */
    fun accept(frame: Iap2Frame): ClusterCall? {
        if (frame.messageId != CALL_STATE_UPDATE) return current()
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return current()
        val status = runCatching { body.optionalU8(STATUS) }.getOrNull()
        val uuid = runCatching { body.optionalString(UUID) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (uuid == null) {
            if (status == null || status == STATUS_DISCONNECTED) calls.clear()
            return current()
        }
        if (status == STATUS_DISCONNECTED || status == STATUS_DISCONNECTING) {
            calls.remove(uuid)
            return current()
        }
        val call = calls.getOrPut(uuid) { Call() }
        val displayName = runCatching { body.optionalString(DISPLAY_NAME) }.getOrNull()?.trim().orEmpty()
        val remoteId = runCatching { body.optionalString(REMOTE_ID) }.getOrNull()?.trim().orEmpty()
        when {
            displayName.isNotEmpty() -> call.name = displayName
            remoteId.isNotEmpty() && call.name.isEmpty() -> call.name = remoteId
        }
        if (status != null) {
            call.status = status
            if ((status == STATUS_ACTIVE || status == STATUS_HELD) && call.activeSince == null) call.activeSince = clock()
        }
        return current()
    }

    fun current(): ClusterCall? {
        val shown = calls.values.firstOrNull { it.activeSince != null }
            ?: calls.values.firstOrNull { it.status == STATUS_RINGING }
            ?: calls.values.firstOrNull { it.status == STATUS_SENDING || it.status == STATUS_CONNECTING }
            ?: return null
        val phase = when {
            shown.activeSince != null -> ClusterCall.Phase.ACTIVE
            shown.status == STATUS_RINGING -> ClusterCall.Phase.RINGING
            else -> ClusterCall.Phase.DIALING
        }
        return ClusterCall(text(shown.name), phase, shown.activeSince)
    }

    /** The session ended: forget every call. */
    fun clear() = calls.clear()

    companion object {
        const val CALL_STATE_UPDATE = 0x4155
        private const val REMOTE_ID = 0
        private const val DISPLAY_NAME = 1
        private const val STATUS = 2
        private const val UUID = 4
        private const val STATUS_DISCONNECTED = 0
        private const val STATUS_SENDING = 1
        private const val STATUS_RINGING = 2
        private const val STATUS_CONNECTING = 3
        private const val STATUS_ACTIVE = 4
        private const val STATUS_HELD = 5
        private const val STATUS_DISCONNECTING = 6

        /** The dashboard's call name takes 60 bytes of UTF-16LE, as BYD's own CarPlay sends it. */
        const val MAX_NAME_BYTES = 60

        fun text(name: String): String {
            var end = name.length
            while (name.substring(0, end).toByteArray(Charsets.UTF_16LE).size > MAX_NAME_BYTES) {
                end--
                if (end > 0 && Character.isLowSurrogate(name[end])) end--
            }
            return name.substring(0, end)
        }
    }
}

/**
 * Optional, needs ADB over network: shows the CarPlay call (name and, once answered, its length) on the
 * dashboard, as BYD's own CarPlay does. Apps cannot write it, so DiPlay runs [BydClusterCallTool] from its
 * own APK under the head unit's adb shell, like [BydClusterSong]. While a call is answered the tool keeps
 * running on a shell connection of its own and sends the length every second; closing that connection
 * (the call ended, or DiPlay is gone) makes it take the call off the dashboard.
 */
internal object BydClusterCall {
    private const val TAG = "DiPlay-BYD-Call"

    private val shell = BydAdbShell(TAG)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "diplay-cluster-call").apply { isDaemon = true } }
    private val state = ClusterCallState(System::currentTimeMillis) // guards wanted too
    @Volatile private var context: Context? = null
    private var wanted: ClusterCall? = null
    private var shown: ClusterCall? = null // writer thread
    @Volatile private var timer: LocalAdb? = null // the connection the answered call's tool runs on

    fun attach(appContext: Context) {
        context = appContext.applicationContext
    }

    /** CallStateUpdate frames; calls are followed even while the setting is off, so one can show at once. */
    fun onFrame(frame: Iap2Frame) {
        val app = context ?: return
        val call = synchronized(state) {
            val previous = state.current()
            state.accept(frame).also { if (it == previous) return }
        }
        if (BydOutputSettings.clusterCall(app)) post(app, call)
    }

    /** Whether the iPhone reports a CarPlay call that is ringing, being dialled or answered. */
    fun hasCall(): Boolean = synchronized(state) { state.current() != null }

    /** The setting changed: show the current call now, or take DiPlay's call off the dashboard. */
    fun settingChanged(enabled: Boolean) {
        val app = context ?: return
        post(app, if (enabled) synchronized(state) { state.current() } else null)
    }

    /** The session ended: forget the calls and take DiPlay's call off the dashboard. */
    fun end() {
        val app = context ?: return
        synchronized(state) { state.clear() }
        post(app, null)
    }

    private fun post(app: Context, call: ClusterCall?) {
        synchronized(state) { wanted = call }
        writer.execute { write(app) }
    }

    private fun write(app: Context) {
        // Only the newest call matters; older queued ones are skipped.
        val call = synchronized(state) { wanted }
        if (call == shown) return
        stopTimer()
        // Leftovers from an earlier run; a separate command, so the pattern cannot match the next tool's shell.
        shell.run(app, "pkill -f '[B]ydClusterCallTool'")
        val apk = app.applicationInfo.sourceDir
        val tool = "CLASSPATH=$apk app_process /system/bin ${BydClusterCallTool::class.java.name}"
        if (call == null) {
            if (succeeded(shell.run(app, "$tool end"))) shown = null
            return
        }
        val name = Base64.encodeToString(call.name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val phase = call.phase.name.lowercase()
        val ok = if (call.activeSinceMillis == null) {
            succeeded(shell.run(app, "$tool show $phase $name - -"))
        } else {
            startTimer(app, "$tool show $phase $name ${call.activeSinceMillis} ${android.os.Process.myPid()}")
            true
        }
        if (ok) {
            if (shown == null) Log.i(TAG, "call on the dashboard")
            shown = call
        }
    }

    /** Runs the answered call's tool on a connection of its own until [stopTimer] closes it. */
    private fun startTimer(app: Context, command: String) {
        val client = LocalAdb(AdbKeys.load(app))
        timer = client
        Thread({
            val ran = client.stream(command) {}
            if (!ran && timer === client) Log.w(TAG, "call length stopped: ADB link failed")
        }, "diplay-call-length").apply { isDaemon = true; start() }
    }

    private fun stopTimer() {
        timer?.close()
        timer = null
    }

    private fun succeeded(output: String?): Boolean {
        output ?: return false
        val failed = output.lineSequence().map { it.trim() }.filter { it.contains('=') }
            .any { line -> line.substringAfter('=').trim().toIntOrNull() != 0 }
        if (failed) Log.w(TAG, "dashboard write failed: ${output.trim().take(160)}")
        return !failed
    }
}

/**
 * Runs under the head unit's adb shell through app_process, not in DiPlay: tells the car about the call the way
 * BYD's own CarPlay does: the call state (BYDAutoSettingDevice setCallState, setBTCallState), then the
 * dashboard (BYDAutoInstrumentDevice sendCallInfo, sendCallState, sendCallTime).
 * Arguments: `show <ringing|dialing|active> <base64 UTF-8 name> <answered at, epoch ms, or -> <DiPlay pid or ->`
 * or `end`.
 * With an answer time it stays running and sends the call length every second until the shell connection
 * closes (its output fails), it is stopped or the DiPlay process is gone; then it ends the call on the
 * dashboard. Prints "name=result"; 0 is success.
 */
object BydClusterCallTool {
    private const val STATE_CALL = 1
    private const val STATE_NO_CALL = 2
    private const val IN_CALL = 1
    private const val NOT_IN_CALL = 0
    private const val BT_ENDED = 5
    private const val TIME_UNKNOWN = 255
    private const val MAX_HOURS = 99

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (error: Throwable) {
            println("call=ERR ${describe(error)}")
        } finally {
            // ActivityThread leaves threads behind; without this the shell command would not return.
            System.exit(0)
        }
    }

    private fun run(args: Array<String>) {
        val device = device("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        val setting = device("android.hardware.bydauto.setting.BYDAutoSettingDevice")
        val sendState = device.javaClass.getMethod("sendCallState", Int::class.java)
        val setCallState = setting.javaClass.getMethod("setCallState", Int::class.java)
        val setBtCallState = setting.javaClass.getMethod("setBTCallState", Int::class.java)
        fun end() {
            println("call=${setCallState.invoke(setting, NOT_IN_CALL)}")
            println("bt=${setBtCallState.invoke(setting, BT_ENDED)}")
            println("state=${sendState.invoke(device, STATE_NO_CALL)}")
        }
        if (args.getOrNull(0) != "show") {
            end()
            return
        }
        val sendInfo = device.javaClass.getMethod("sendCallInfo", ByteArray::class.java)
        val sendTime = device.javaClass.getMethod("sendCallTime", Int::class.java, Int::class.java, Int::class.java)
        // BYD's Bluetooth call states: 1 incoming, 2 outgoing, 3 in a call.
        val btState = when (args.getOrNull(1)) {
            "ringing" -> 1
            "dialing" -> 2
            else -> 3
        }
        val name = String(java.util.Base64.getDecoder().decode(args.getOrNull(2) ?: ""), Charsets.UTF_8)
        val bytes = ClusterCallState.text(name).toByteArray(Charsets.UTF_16LE)
        println("call=${setCallState.invoke(setting, IN_CALL)}")
        println("bt=${setBtCallState.invoke(setting, btState)}")
        println("info=${sendInfo.invoke(device, bytes)}")
        println("state=${sendState.invoke(device, STATE_CALL)}")
        val since = args.getOrNull(3)?.toLongOrNull()
        if (since == null) {
            println("time=${sendTime.invoke(device, TIME_UNKNOWN, TIME_UNKNOWN, TIME_UNKNOWN)}")
            return
        }
        val diplay = args.getOrNull(4)?.let { File("/proc/$it") }
        while (diplay == null || diplay.exists()) {
            val seconds = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
            val hours = (seconds / 3600).toInt()
            if (hours > MAX_HOURS) break
            sendTime.invoke(device, hours, (seconds / 60 % 60).toInt(), (seconds % 60).toInt())
            // A closed shell connection shows up as an output error: DiPlay ended the call or is gone.
            println("time=$seconds")
            if (System.out.checkError()) break
            Thread.sleep(1000 - (System.currentTimeMillis() - since) % 1000)
        }
        // The call ended, DiPlay is gone, or the call is implausibly long: do not leave it on the dashboard.
        end()
    }

    private var systemContext: Any? = null

    @SuppressLint("PrivateApi")
    private fun device(className: String): Any {
        val context = systemContext ?: run {
            runCatching { android.os.Looper.prepareMainLooper() }
            val thread = Class.forName("android.app.ActivityThread")
            val main = thread.getMethod("systemMain").invoke(null)
            thread.getMethod("getSystemContext").invoke(main).also { systemContext = it }
        }
        val deviceClass = Class.forName(className)
        // getInstance checks the BYDAUTO_* permission on the caller's side only; autoservice itself
        // accepts the shell user, so build the device the way getInstance does.
        return try {
            deviceClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (_: InvocationTargetException) {
            deviceClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        }
    }

    private fun describe(error: Throwable): String {
        val cause = error.cause ?: error
        return cause.javaClass.name + (cause.message?.let { ": $it" } ?: "")
    }
}
