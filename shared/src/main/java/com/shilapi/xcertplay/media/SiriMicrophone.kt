package com.shilapi.xcertplay.media

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        /**
         * [CALL] with the echo canceller only: the noise suppressor gates the pauses between words to
         * about -80 dBFS, which may end Siri's listening early.
         */
        CALL_NO_NS,
    }

    /** Whether [mode] captures on the call path (VOICE_COMMUNICATION, echo canceller, call audio mode). */
    fun callPath(mode: Mode?): Boolean = mode == Mode.CALL || mode == Mode.CALL_NO_NS

    const val GAIN = 4
    private const val PREFS = "diplay"
    private const val KEY = "lab_siri_microphone"

    @Volatile var mode = Mode.RECOGNITION
        private set

    /** EXPERIMENT (lab): where the last microphone streams are kept as raw PCM, to hear what Siri gets. */
    @Volatile private var recordDir: File? = null
    private const val RECORDINGS_KEPT = 6

    /**
     * A new raw 16-bit PCM file for a [audioType] stream at [sampleRate] in the app's private files
     * (mic-lab), keeping only the latest few; null when it cannot be opened.
     */
    fun openRecording(audioType: String, sampleRate: Int, channels: Int): OutputStream? = runCatching {
        val dir = recordDir ?: return null
        dir.listFiles { file -> file.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() }
            ?.drop(RECORDINGS_KEPT - 1)?.forEach { it.delete() }
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        FileOutputStream(File(dir, "mic-$audioType-${sampleRate}hz-${channels}ch-$stamp.pcm")).buffered()
    }.getOrNull()

    fun load(context: Context): Mode {
        recordDir = File(context.filesDir, "mic-lab").apply { mkdirs() }
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
