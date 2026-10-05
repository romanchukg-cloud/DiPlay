package com.shilapi.xcertplay.media

import android.content.Context

/**
 * EXPERIMENT (lab): which BYD volume slider Siri and CarPlay's navigation prompts follow. BYD's audio
 * policy picks a stream by its own content types: NAVI (6) with USAGE_MEDIA plays on the Navi slider,
 * BTTS (10) on the Voice slider (as BYD's own navigation and voice do); USAGE_VOICE_COMMUNICATION plays
 * on the Call slider. By default Siri (USAGE_ASSISTANT) and navigation guidance follow the Media slider.
 * [AndroidMediaSink] reads the choices when a stream starts.
 */
object BydAudioChannels {
    enum class Siri { MEDIA, CALL, VOICE }
    enum class Navigation { MEDIA, NAVI }

    const val CONTENT_TYPE_NAVI = 6
    const val CONTENT_TYPE_BTTS = 10
    private const val PREFS = "diplay"
    private const val KEY_SIRI = "lab_byd_siri_output"
    private const val KEY_NAVIGATION = "lab_byd_navigation_output"

    @Volatile var siri = Siri.CALL
        private set
    @Volatile var navigation = Navigation.NAVI
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        siri = Siri.entries.firstOrNull { it.name == prefs.getString(KEY_SIRI, null) } ?: Siri.CALL
        navigation = Navigation.entries.firstOrNull { it.name == prefs.getString(KEY_NAVIGATION, null) } ?: Navigation.NAVI
    }

    /** The next choice, saved; it applies from the next Siri stream. */
    fun cycleSiri(context: Context): Siri {
        load(context)
        val next = Siri.entries[(siri.ordinal + 1) % Siri.entries.size]
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SIRI, next.name).apply()
        siri = next
        return next
    }

    /** The next choice, saved; it applies from the next navigation prompt stream. */
    fun cycleNavigation(context: Context): Navigation {
        load(context)
        val next = Navigation.entries[(navigation.ordinal + 1) % Navigation.entries.size]
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAVIGATION, next.name).apply()
        navigation = next
        return next
    }
}
