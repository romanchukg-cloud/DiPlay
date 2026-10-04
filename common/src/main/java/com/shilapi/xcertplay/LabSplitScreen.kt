package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * EXPERIMENT (lab): CarPlay on the right two thirds of the screen through a second view area, switched
 * live with updateViewArea. The switch declares the area (applies on reconnect); from the Mac:
 * `adb shell am broadcast -a com.shilapi.xcertplay.lab.VIEW_AREA -p com.shihab.diplay.hudtest --ei index 1`
 * (0 = whole screen). The receiver needs DUMP, so only the adb shell can send it.
 */
object LabSplitScreen {
    private const val PREFS = "diplay_lab_split_screen"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** EXPERIMENT: viewAreaStatusBarEdge to declare, or null to leave it to the iPhone. */
    fun statusBarEdge(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("status_bar_edge", -1).takeIf { it >= 0 }

    fun setStatusBarEdge(context: Context, edge: Int?) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("status_bar_edge", edge ?: -1).apply()

    /** Where the right-hand area starts: a third of the stream, kept even for the encoder. */
    fun leftPixels(widthPixels: Int): Int = (widthPixels / 3) and 1.inv()
}

class LabViewAreaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val index = intent.getIntExtra("index", 0)
        val millis = intent.getIntExtra("millis", -1).takeIf { it >= 0 }
        val adjacent = intent.getStringExtra("adjacent")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        val sent = CarPlayBackgroundSession.snapshot()?.controller?.labViewArea(index, millis, adjacent)
        Log.i("DiPlay-SplitLab", "view area $index millis=$millis adjacent=$adjacent requested=$sent")
    }
}
