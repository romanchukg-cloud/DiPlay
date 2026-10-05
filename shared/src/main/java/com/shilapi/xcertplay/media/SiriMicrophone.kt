package com.shilapi.xcertplay.media

import android.content.Context

/**
 * EXPERIMENT (lab): which capture path feeds Siri. On a DiLink 5.0 Tang the voice-recognition input
 * was 15-20 dB quieter than the voice-communication one, and Siri needed a raised voice.
 * [MicrophoneUplink] reads [mode] when a Siri stream starts and logs the level Siri gets.
 */
object SiriMicrophone {
    enum class Mode {
        /** VOICE_RECOGNITION as is (DiPlay's default). */
        RECOGNITION,
        /** VOICE_RECOGNITION raised by [GAIN] (+12 dB), clipped at full scale. */
        RECOGNITION_GAIN,
        /** VOICE_COMMUNICATION with the platform echo canceller and noise suppressor, as calls use. */
        CALL,
        /** [CALL] raised by [GAIN] (+12 dB), clipped at full scale. */
        CALL_GAIN,
    }

    /** Whether [mode] captures on the call path (VOICE_COMMUNICATION, echo canceller, call audio mode). */
    fun callPath(mode: Mode?): Boolean = mode == Mode.CALL || mode == Mode.CALL_GAIN

    const val GAIN = 4
    private const val PREFS = "diplay"
    private const val KEY = "lab_siri_microphone"

    @Volatile var mode = Mode.RECOGNITION
        private set

    fun load(context: Context): Mode {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode = Mode.entries.firstOrNull { it.name == stored } ?: Mode.RECOGNITION
        return mode
    }

    /** The next mode, saved; it applies from the next time Siri listens. */
    fun cycle(context: Context): Mode {
        val next = Mode.entries[(load(context).ordinal + 1) % Mode.entries.size]
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.name).apply()
        mode = next
        return next
    }
}
