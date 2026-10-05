package com.shilapi.xcertplay.airplay

import android.content.Context
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EXPERIMENT (lab): reconnaissance for CarPlay's main buffered audio (WWDC23 "Enhanced buffering"):
 * accepts the iPhone's buffered music connection and only logs what arrives (frame sizes, the first
 * bytes, totals). Nothing is decoded or played, so music is silent while the iPhone uses this path.
 */
class LabBufferedAudioProbe internal constructor(private val log: (String) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0)
    private var client: Socket? = null

    val port: Int get() = server.localPort

    init {
        Thread({ run() }, "lab-buffered-audio").apply { isDaemon = true; start() }
    }

    private fun run() {
        try {
            val socket = server.accept().also { client = it }
            log("Buffered probe: connection from ${socket.inetAddress?.hostAddress}")
            val input = DataInputStream(socket.getInputStream().buffered())
            var frames = 0L
            var bytes = 0L
            var lastReport = System.nanoTime()
            val started = lastReport
            while (!closed.get()) {
                val length = input.readUnsignedShort()
                val body = ByteArray(maxOf(0, length - 2))
                input.readFully(body)
                frames++
                bytes += length
                if (frames <= FIRST_FRAMES) {
                    val seq = if (body.size >= 4) ((body[2].toInt() and 0xff) shl 8) or (body[3].toInt() and 0xff) else -1
                    val ts = if (body.size >= 8) ((body[4].toLong() and 0xff) shl 24) or ((body[5].toLong() and 0xff) shl 16) or
                        ((body[6].toLong() and 0xff) shl 8) or (body[7].toLong() and 0xff) else -1
                    log("Buffered probe: frame=$frames length=$length seq=$seq ts=$ts head=${body.copyOf(minOf(body.size, 16)).toHexString()}")
                }
                val now = System.nanoTime()
                if (now - lastReport >= REPORT_NS) {
                    val seconds = (now - started) / 1e9
                    log("Buffered probe: frames=$frames bytes=$bytes after %.1f s (%.0f kbit/s)".format(java.util.Locale.US,
                        seconds, bytes * 8 / 1000.0 / seconds))
                    lastReport = now
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) log("Buffered probe: ended ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client?.close() }
        runCatching { server.close() }
    }

    companion object {
        private const val FIRST_FRAMES = 12
        private const val REPORT_NS = 5_000_000_000L
        private const val PREFS = "diplay"
        private const val KEY = "lab_main_buffered"
        private const val KEY_MODE = "lab_main_buffered_mode"
        /** Parts of the offer, to find which one the iPhone accepts: the feature bit, mainBufferedInfo, the 103 format. */
        const val MODE_FEATURE = 1
        const val MODE_INFO = 2
        const val MODE_FORMAT = 4
        private const val DEFAULT_MODE = MODE_INFO
        const val STREAM_TYPE = 103
        const val AUDIO_BUFFER_BYTES = 8 * 1024 * 1024
        /** AirPlay 2's "supports buffered audio" feature bit. */
        const val FEATURE_BIT = 1L shl 40

        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        /** The parts offered while enabled (0 when off). */
        fun mode(context: Context): Int = if (!enabled(context)) 0 else
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, DEFAULT_MODE)

        fun setEnabled(context: Context, on: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

        private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
    }
}
