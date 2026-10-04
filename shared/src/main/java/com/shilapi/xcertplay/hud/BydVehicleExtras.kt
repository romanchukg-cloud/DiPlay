package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.LabVehicleExtras
import com.shilapi.xcertplay.transport.LabVehicleExtrasSource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENT (lab): the car signals the iPhone asks for in StartVehicleStatusUpdates, read every second
 * through the adb shell (autoservice getInt, device/fid from the car's BYDAutoFeatureIds). Checked on our
 * Tang (CAN-FD): outside temperature (AC 1000) in °C; front wiper level (wiper 1046) 1 off, 10 wiping;
 * emergency alarm (bodywork 1001) 1 off, 2 hazard on. ABS/TCS active (ADAS 1038) read 0 at rest.
 */
internal object BydVehicleExtras : LabVehicleExtrasSource {
    private const val TAG = "DiPlay-BYD-Extras"
    private const val READ_MILLIS = 1_000L
    private const val IDLE_MILLIS = 2 * 60_000L
    private const val OUTSIDE_TEMPERATURE = "service call autoservice 5 i32 1000 i32 1077936184"
    private const val FRONT_WIPER_LEVEL = "service call autoservice 5 i32 1046 i32 321912848"
    private const val EMERGENCY_ALARM = "service call autoservice 5 i32 1001 i32 692060190"
    private const val ABS_ACTIVE = "service call autoservice 5 i32 1038 i32 304087077"
    private const val TCS_ACTIVE = "service call autoservice 5 i32 1038 i32 304087094"
    private const val WIPER_OFF = 1
    private const val ALARM_ON = 2

    private val shell = BydAdbShell(TAG)
    @Volatile private var context: Context? = null
    @Volatile private var latest: LabVehicleExtras? = null
    @Volatile private var askedMillis = 0L
    private var started = false
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-extras").apply { isDaemon = true } }

    @Synchronized
    fun start(appContext: Context) {
        context = appContext.applicationContext
        askedMillis = SystemClock.elapsedRealtime()
        if (started) return
        started = true
        executor.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS)
    }

    override fun snapshot(): LabVehicleExtras? {
        askedMillis = SystemClock.elapsedRealtime()
        return latest
    }

    private fun poll() {
        val app = context ?: return
        if (SystemClock.elapsedRealtime() - askedMillis > IDLE_MILLIS) return shell.close()
        fun read(command: String) = BydParcel.value(shell.run(app, command))
        val next = LabVehicleExtras(
            outsideC = read(OUTSIDE_TEMPERATURE)?.takeIf { it in -60..80 },
            wipersOn = read(FRONT_WIPER_LEVEL)?.takeIf { it in 0..20 }?.let { it != WIPER_OFF },
            hazard = read(EMERGENCY_ALARM) == ALARM_ON,
            abs = read(ABS_ACTIVE) == 1,
            tractionLoss = read(TCS_ACTIVE) == 1,
        )
        if (next != latest) Log.i(TAG, "car: $next")
        latest = next
    }
}
