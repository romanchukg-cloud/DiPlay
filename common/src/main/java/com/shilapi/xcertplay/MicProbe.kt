package com.shilapi.xcertplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * EXPERIMENT (lab only): can DiPlay hear the car's microphone while CarPlay runs, as a local "Hey Siri"
 * detector would need? Started by the user from the settings; tries three capture sources for a few
 * seconds each and reports only numbers: level per second, silenced, effects, route and format.
 * No audio is kept, written or sent anywhere.
 */
internal object MicProbe {
    private const val TAG = "DiPlay-MicProbe"
    private const val RATE = 16_000
    private const val SECONDS_PER_SOURCE = 10
    private const val READS_PER_SECOND = 10

    @Volatile private var running = false

    /** Runs the probe on a thread of its own; [done] gets the summary, on that thread. */
    fun start(context: Context, done: (String) -> Unit): Boolean {
        if (running) return false
        running = true
        val app = context.applicationContext
        Thread({
            val summary = try {
                run(app)
            } catch (error: Exception) {
                "failed: ${error.javaClass.simpleName}"
            } finally {
                running = false
            }
            Log.i(TAG, "summary\n$summary")
            done(summary)
        }, "diplay-mic-probe").start()
        return true
    }

    private fun run(context: Context): String {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "no RECORD_AUDIO permission"
        }
        val audio = context.getSystemService(AudioManager::class.java)
        Log.i(TAG, "start mode=${audio.mode} other recordings: ${others(audio, -1)}")
        return listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "voice_recognition",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "voice_communication",
            MediaRecorder.AudioSource.MIC to "mic",
        ).joinToString("\n") { (source, name) -> probe(audio, source, name) }
    }

    @SuppressLint("MissingPermission") // checked in run()
    private fun probe(audio: AudioManager, source: Int, name: String): String {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return "$name: no buffer size ($minBuffer)"
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 4, RATE))
                .build()
        } catch (error: Exception) {
            return "$name: cannot create (${error.javaClass.simpleName})"
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return "$name: not initialized"
        }
        val session = record.audioSessionId
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(session) }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(session) }.getOrNull() else null
        val levels = mutableListOf<Int>()
        var peak = 0
        var zeroReads = 0
        var silenced: Boolean? = null
        var timestamped = false
        try {
            record.startRecording()
            val buffer = ShortArray(RATE / READS_PER_SECOND)
            repeat(SECONDS_PER_SOURCE) {
                var sumSquares = 0.0
                var samples = 0
                repeat(READS_PER_SECOND) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) return@repeat
                    var allZero = true
                    for (i in 0 until read) {
                        val v = buffer[i].toInt()
                        if (v != 0) allZero = false
                        val a = kotlin.math.abs(v)
                        if (a > peak) peak = a
                        sumSquares += v.toDouble() * v
                    }
                    if (allZero) zeroReads++
                    samples += read
                }
                val rms = if (samples > 0) sqrt(sumSquares / samples) else 0.0
                levels += if (rms > 0) (20 * log10(rms / Short.MAX_VALUE)).toInt() else -99
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                silenced = audio.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == session }?.isClientSilenced
            }
            timestamped = record.getTimestamp(AudioTimestamp(), AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
        } finally {
            runCatching { record.stop() }
        }
        val route = record.routedDevice?.let { "${it.type}/${it.productName}" } ?: "none"
        val summary = "$name: dBFS/s=$levels peak=$peak zeroReads=$zeroReads/${SECONDS_PER_SOURCE * READS_PER_SECOND} " +
            "silenced=$silenced rate=${record.sampleRate} channels=${record.channelCount} route=$route " +
            "aec=${aec?.enabled} ns=${ns?.enabled} timestamps=$timestamped others=${others(audio, session)}"
        Log.i(TAG, summary)
        aec?.release()
        ns?.release()
        record.release()
        return summary
    }

    /** Other recordings the system reports: source, silenced and format, never who. */
    private fun others(audio: AudioManager, ownSession: Int): String =
        audio.activeRecordingConfigurations.filter { it.clientAudioSessionId != ownSession }.joinToString(prefix = "[", postfix = "]") {
            val muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.isClientSilenced.toString() else "?"
            "source=${it.clientAudioSource} silenced=$muted " +
                "${it.format.sampleRate}Hz/${it.format.channelCount}ch"
        }
}
