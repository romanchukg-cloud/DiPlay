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
class LabBufferedAudioProbe internal constructor(
    private val log: (String) -> Unit,
    /** The streamConnectionID from the stream's SETUP, echoed in /feedback like the other audio streams. */
    val streamConnectionId: Any? = null,
) : AutoCloseable {
    /** RTP timestamp of the first frame and when it arrived: the probe pretends to play from there. */
    @Volatile var firstTimestamp: Long? = null
        private set
    @Volatile var firstNs: Long = 0L
        private set
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
                if (frames == 1L && body.size >= 8) {
                    firstTimestamp = ((body[4].toLong() and 0xff) shl 24) or ((body[5].toLong() and 0xff) shl 16) or
                        ((body[6].toLong() and 0xff) shl 8) or (body[7].toLong() and 0xff)
                    firstNs = System.nanoTime()
                }
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

    /** The anchor: RTP sample [anchorRtp] plays at [anchorNtp] (NTP64 on the iPhone's synced clock). */
    @Volatile var anchorRtp: Long? = null
        private set
    @Volatile var anchorNtp: java.math.BigInteger? = null
        private set
    @Volatile var rate: Int = 0
        private set

    /** SETRATE: start (rate 1) at [rtpTime] a little after [nowNtp], or pause (rate 0) where playback is. */
    fun setRate(rtpTime: Long?, newRate: Int, nowNtp: java.math.BigInteger) {
        if (newRate > 0) {
            anchorRtp = rtpTime ?: playbackSample(nowNtp) ?: firstTimestamp
            anchorNtp = nowNtp.add(java.math.BigInteger.valueOf(PRETEND_LATENCY_MS.toLong()).shiftLeft(32)
                .divide(java.math.BigInteger.valueOf(1000)))
        } else {
            playbackSample(nowNtp)?.let { anchorRtp = it }
            anchorNtp = nowNtp
        }
        rate = newRate
    }

    /** The sample the pretended playback has reached at [nowNtp], or null before an anchor. */
    fun playbackSample(nowNtp: java.math.BigInteger): Long? {
        val rtp = anchorRtp ?: return null
        val at = anchorNtp ?: return null
        if (rate == 0) return rtp
        val elapsed = maxOf(0.0, nowNtp.subtract(at).toDouble() / 4294967296.0)
        return (rtp + Math.round(elapsed * SAMPLE_RATE)) and 0xffff_ffffL
    }

    /** The anchor as SETRATE and GETANCHOR return it (networkTimeFrac as a 64-bit fraction, as in AirPlay 2). */
    fun anchorPlist(epochVariant: Int = 0): Map<String, Any?>? {
        val rtp = anchorRtp ?: return null
        val at = anchorNtp ?: return null
        // Variant 1: seconds on the AirTunes (1970) epoch rather than NTP's 1900.
        val secs = at.shiftRight(32).toLong() - if (epochVariant == 1) NTP_UNIX_OFFSET else 0L
        return linkedMapOf(
            "rtpTime" to rtp,
            "networkTimeSecs" to secs,
            "networkTimeFrac" to at.and(java.math.BigInteger.valueOf(0xffff_ffffL)).shiftLeft(32),
            "rate" to rate,
        )
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
        const val MODE_SESSION = 8
        private const val KEY_INFO = "lab_main_buffered_info"

        private const val KEY_TYPE = "lab_main_buffered_type"
        private const val KEY_FORMAT = "lab_main_buffered_format"

        /** What mainBufferedInfo holds: bits 1 bufferSizeMs, 2 audioBufferSize, 4 its own audioFormats entry. */
        fun infoVariant(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INFO, 0)

        /** The stream type tried for buffered audio (103 as in AirPlay 2; 104/105 are the other free numbers). */
        fun streamType(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TYPE, STREAM_TYPE)

        /** The audioOutputFormats bits offered for it (AAC-LC 48 kHz stereo by default). */
        fun format(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_FORMAT, 0x800000L)

        fun formatEntry(type: Int, format: Long): Map<String, Any?> =
            linkedMapOf("type" to type, "audioType" to "media", "audioOutputFormats" to format)

        fun info(variant: Int, type: Int, format: Long): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
            if (variant and 1 != 0) put("bufferSizeMs", 120_000)
            if (variant and 2 != 0) put("audioBufferSize", AUDIO_BUFFER_BYTES)
            if (variant and 4 != 0) put("audioFormats", listOf(formatEntry(type, format)))
        }

        /** Stream types the probe accepts while the experiment is on. */
        val CANDIDATE_TYPES = setOf(103, 104, 105)
        private const val DEFAULT_MODE = MODE_INFO
        const val STREAM_TYPE = 103
        const val SAMPLE_RATE = 48_000
        private const val NTP_UNIX_OFFSET = 2_208_988_800L
        private const val KEY_EPOCH = "lab_main_buffered_epoch"

        fun epochVariant(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_EPOCH, 1)
        /** How far behind arrival the pretended playback runs, like a receiver's output latency. */
        private const val PRETEND_LATENCY_MS = 500
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
