package com.shilapi.xcertplay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Lab: whether a BYD home screen (the normal home, map home or MyCar) is in front, from the newest
 * resumed activity in the owner's Usage Access events. Overlays and panels are not activities, so
 * they leave the answer as it is.
 */
internal class HomeScreenMonitor(context: Context, private val onChange: (Boolean) -> Unit) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var executor: ScheduledExecutorService? = null
    // Poller thread only.
    private var since = 0L
    private var newestTime = 0L
    private var newestPackage: String? = null
    @Volatile private var reported: Boolean? = null

    val running: Boolean get() = executor != null

    /** Main thread. */
    fun start() {
        if (executor != null) return
        since = System.currentTimeMillis() - FIRST_LOOK_BACK_MILLIS
        newestTime = 0L
        newestPackage = null
        reported = null
        executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-home-monitor").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::poll, 0, POLL_MILLIS, TimeUnit.MILLISECONDS) }
    }

    /** Main thread. */
    fun stop() {
        executor?.shutdownNow()
        executor = null
        main.removeCallbacksAndMessages(null)
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        val events = runCatching { context.getSystemService(UsageStatsManager::class.java).queryEvents(since, now) }
            .getOrNull() ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= newestTime) {
                newestTime = event.timeStamp
                newestPackage = event.packageName
            }
        }
        // Overlap, because events can arrive a little late.
        since = (now - OVERLAP_MILLIS).coerceAtLeast(since)
        val visible = newestPackage in HOME_PACKAGES
        if (visible != reported) {
            reported = visible
            main.post { if (executor != null) onChange(visible) }
        }
    }

    companion object {
        private const val POLL_MILLIS = 500L
        private const val OVERLAP_MILLIS = 2_000L
        private const val FIRST_LOOK_BACK_MILLIS = 10 * 60_000L

        // BYD's home list (Launcher3 HomeHelper): MyCar, the normal home, and the map home.
        val HOME_PACKAGES = setOf("com.android.launcher3", "com.byd.launchermap", "com.byd.naviauto", "com.byd.mycar")

        fun hasAccess(context: Context): Boolean = DiLink51ClusterMonitor.hasAccess(context)
    }
}
