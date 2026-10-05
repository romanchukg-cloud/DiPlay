package com.shilapi.xcertplay.airplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.DataInputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EXPERIMENT (lab): CarPlay's main buffered audio (WWDC23 "Enhanced buffering"). The iPhone sends music
 * ahead of time over TCP (here up to about two minutes), and the car answers SETRATE/GETANCHOR with the
 * time a given RTP sample plays. Frames are decrypted with the stream's shk, kept in memory, decoded
 * (AAC-LC 48 kHz stereo) and played through an AudioTrack, whose blocking writes keep real time.
 */
class LabBufferedAudioProbe internal constructor(
    private val log: (String) -> Unit,
    /** The streamConnectionID from the stream's SETUP, echoed in /feedback like the other audio streams. */
    val streamConnectionId: Any? = null,
    /** The stream key (shk) from the SETUP; without it frames are only counted. */
    private val key: ByteArray? = null,
) : AutoCloseable {
    private class Frame(val timestamp: Long, val payload: ByteArray)

    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0)
    private var client: Socket? = null
    private val frames = LinkedBlockingDeque<Frame>()
    private val lock = Object()

    val port: Int get() = server.localPort

    /** RTP timestamp of the first frame received. */
    @Volatile var firstTimestamp: Long? = null
        private set

    /** The anchor: RTP sample [anchorRtp] plays at [anchorNtp] (NTP64 on the iPhone's synced clock). */
    @Volatile var anchorRtp: Long? = null
        private set
    @Volatile var anchorNtp: BigInteger? = null
        private set
    @Volatile var rate: Int = 0
        private set

    // Player state, changed by control requests and applied on the player thread.
    @Volatile private var flushUntil: Long? = null
    @Volatile private var resetPending = false
    @Volatile private var playStartTimestamp: Long? = null
    @Volatile private var track: AudioTrack? = null

    init {
        Thread({ receive() }, "lab-buffered-rx").apply { isDaemon = true; start() }
        if (key != null) Thread({ play() }, "lab-buffered-play").apply { isDaemon = true; start() }
    }

    private fun receive() {
        try {
            val socket = server.accept().also { client = it }
            log("Buffered: connection from ${socket.inetAddress?.hostAddress}")
            val input = DataInputStream(socket.getInputStream().buffered())
            var count = 0L
            var bytes = 0L
            var failures = 0
            var lastReport = System.nanoTime()
            val started = lastReport
            while (!closed.get()) {
                val length = input.readUnsignedShort()
                val body = ByteArray(maxOf(0, length - 2))
                input.readFully(body)
                count++
                bytes += length
                if (body.size < RTP_HEADER + TAG_AND_NONCE) continue
                val timestamp = u32(body, 4)
                if (count == 1L) firstTimestamp = timestamp
                val payload = key?.let { decrypt(it, body) }
                if (key != null && payload == null && failures++ < 3) log("Buffered: frame $count did not decrypt")
                if (payload != null) {
                    if (count <= 2) log("Buffered: frame $count ts=$timestamp payload=${payload.size} bytes head=" +
                        payload.copyOf(minOf(4, payload.size)).joinToString("") { "%02x".format(it) })
                    frames.offer(Frame(timestamp, payload))
                }
                val now = System.nanoTime()
                if (now - lastReport >= REPORT_NS) {
                    val seconds = (now - started) / 1e9
                    log("Buffered: frames=$count bytes=$bytes queued=${frames.size} (%.0f s of audio) after %.1f s"
                        .format(java.util.Locale.US, frames.size * SAMPLES_PER_FRAME / SAMPLE_RATE.toDouble(), seconds))
                    lastReport = now
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) log("Buffered: ended ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** ChaCha20-Poly1305 as in AirPlay 2 buffered audio: RTP header, ciphertext and tag, an 8-byte nonce. */
    private fun decrypt(key: ByteArray, body: ByteArray): ByteArray? = runCatching {
        val sealedEnd = body.size - NONCE_BYTES
        val nonce = ByteArray(12).also { body.copyInto(it, 4, sealedEnd, body.size) }
        AirPlayCrypto.chachaOpen(key, nonce, body.copyOfRange(RTP_HEADER, sealedEnd), body.copyOfRange(4, RTP_HEADER))
    }.getOrNull()

    private fun play() {
        var codec: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        var played = 0L
        try {
            while (!closed.get()) {
                if (resetPending) {
                    resetPending = false
                    runCatching { track?.pause(); track?.flush() }
                    runCatching { codec?.flush() }
                    playStartTimestamp = null
                }
                if (rate == 0) {
                    runCatching { if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) track?.pause() }
                    synchronized(lock) { lock.wait(50) }
                    continue
                }
                val frame = frames.poll(50, TimeUnit.MILLISECONDS) ?: continue
                val until = flushUntil
                if (until != null && before(frame.timestamp, until)) continue
                val decoder = codec ?: openDecoder(frame.payload).also { codec = it } ?: return
                val input = decoder.dequeueInputBuffer(20_000)
                if (input >= 0) {
                    decoder.getInputBuffer(input)?.apply { clear(); put(frame.payload) }
                    decoder.queueInputBuffer(input, 0, frame.payload.size, 0, 0)
                }
                if (playStartTimestamp == null) {
                    playStartTimestamp = frame.timestamp
                    runCatching { track?.flush() }
                }
                while (true) {
                    val output = decoder.dequeueOutputBuffer(info, 0)
                    if (output < 0) break
                    val pcm = decoder.getOutputBuffer(output)
                    if (pcm != null && info.size > 0) {
                        val out = track ?: openTrack().also { track = it }
                        pcm.position(info.offset); pcm.limit(info.offset + info.size)
                        if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
                        out.write(pcm, info.size, AudioTrack.WRITE_BLOCKING)
                        if (played++ == 0L) log("Buffered: playback started")
                    }
                    decoder.releaseOutputBuffer(output, false)
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) log("Buffered: playback ended ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { track?.stop(); track?.release() }
            track = null
        }
    }

    private fun openDecoder(first: ByteArray): MediaCodec? = runCatching {
        // An ADTS frame starts with the 0xFFF sync word; otherwise the payload is a raw AAC access unit.
        val adts = first.size > 1 && (first[0].toInt() and 0xff) == 0xff && (first[1].toInt() and 0xf0) == 0xf0
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            if (adts) setInteger(MediaFormat.KEY_IS_ADTS, 1)
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x11, 0x90.toByte()))) // AAC-LC, 48 kHz, stereo
        }
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(format, null, null, 0); start() }
            .also { log("Buffered: AAC decoder ready adts=$adts") }
    }.onFailure { log("Buffered: decoder failed ${it.message}") }.getOrNull()

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min * 2, SAMPLE_RATE * 4 * TRACK_BUFFER_MS / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** SETRATE / SETRATEANCHORTIME: start (rate 1) at [rtpTime] a little after [nowNtp], or pause (rate 0). */
    fun setRate(rtpTime: Long?, newRate: Int, nowNtp: BigInteger) {
        if (newRate > 0) {
            val start = rtpTime ?: playbackSample(nowNtp) ?: firstTimestamp
            if (rtpTime != null) {
                flushUntil = rtpTime
                if (playStartTimestamp != null && playStartTimestamp != rtpTime) resetPending = true
            }
            anchorRtp = start
            anchorNtp = nowNtp.add(BigInteger.valueOf(START_LATENCY_MS.toLong()).shiftLeft(32).divide(BigInteger.valueOf(1000)))
        } else {
            playbackSample(nowNtp)?.let { anchorRtp = it }
            anchorNtp = nowNtp
        }
        rate = newRate
        synchronized(lock) { lock.notifyAll() }
    }

    /** FLUSHBUFFERED: drop what was sent before [untilTimestamp] and restart the output there. */
    fun flush(untilTimestamp: Long?) {
        if (untilTimestamp != null) {
            flushUntil = untilTimestamp
            frames.removeIf { before(it.timestamp, untilTimestamp) }
        }
        resetPending = true
    }

    /** The sample now playing: from the AudioTrack's head once playing, else from the anchor. */
    fun playbackSample(nowNtp: BigInteger): Long? {
        val start = playStartTimestamp
        val out = track
        if (start != null && out != null && out.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return (start + (out.playbackHeadPosition.toLong() and 0xffff_ffffL)) and 0xffff_ffffL
        }
        val rtp = anchorRtp ?: return null
        val at = anchorNtp ?: return null
        if (rate == 0) return rtp
        val elapsed = maxOf(0.0, nowNtp.subtract(at).toDouble() / 4294967296.0)
        return (rtp + Math.round(elapsed * SAMPLE_RATE)) and 0xffff_ffffL
    }

    /** The anchor as SETRATE and GETANCHOR return it (networkTimeFrac as a 64-bit fraction, as in AirPlay 2). */
    fun anchorPlist(epochVariant: Int = 1): Map<String, Any?>? {
        val rtp = anchorRtp ?: return null
        val at = anchorNtp ?: return null
        // Variant 1: seconds on the 1970 epoch, which the iPhone's timing clock uses; 0: NTP's 1900.
        val secs = at.shiftRight(32).toLong() - if (epochVariant == 1) NTP_UNIX_OFFSET else 0L
        return linkedMapOf(
            "rtpTime" to rtp,
            "networkTimeSecs" to secs,
            "networkTimeFrac" to at.and(BigInteger.valueOf(0xffff_ffffL)).shiftLeft(32),
            "rate" to rate,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client?.close() }
        runCatching { server.close() }
        synchronized(lock) { lock.notifyAll() }
    }

    companion object {
        private const val RTP_HEADER = 12
        private const val NONCE_BYTES = 8
        private const val TAG_AND_NONCE = 16 + NONCE_BYTES
        private const val SAMPLES_PER_FRAME = 1024
        private const val REPORT_NS = 5_000_000_000L
        private const val TRACK_BUFFER_MS = 300
        /** How long after SETRATE the requested sample plays: decoder and AudioTrack start-up. */
        private const val START_LATENCY_MS = 300
        private const val NTP_UNIX_OFFSET = 2_208_988_800L
        private const val PREFS = "diplay"
        private const val KEY = "lab_main_buffered"
        private const val KEY_MODE = "lab_main_buffered_mode"
        private const val KEY_INFO = "lab_main_buffered_info"
        private const val KEY_TYPE = "lab_main_buffered_type"
        private const val KEY_FORMAT = "lab_main_buffered_format"
        private const val KEY_EPOCH = "lab_main_buffered_epoch"
        /** Parts of the offer: the feature bit, mainBufferedInfo, the 103 format, the session feature. */
        const val MODE_FEATURE = 1
        const val MODE_INFO = 2
        const val MODE_FORMAT = 4
        const val MODE_SESSION = 8
        private const val DEFAULT_MODE = MODE_INFO or MODE_FORMAT or MODE_SESSION
        const val STREAM_TYPE = 103
        const val SAMPLE_RATE = 48_000
        const val AUDIO_BUFFER_BYTES = 8 * 1024 * 1024
        /** AirPlay 2's "supports buffered audio" feature bit. */
        const val FEATURE_BIT = 1L shl 40
        /** Stream types the receiver accepts while the experiment is on. */
        val CANDIDATE_TYPES = setOf(103, 104, 105)

        private fun u32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xff) shl 24) or ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or (bytes[offset + 3].toLong() and 0xff)

        /** Whether RTP timestamp [a] comes before [b], across the 32-bit wrap. */
        private fun before(a: Long, b: Long): Boolean = ((a - b) and 0xffff_ffffL) >= 0x8000_0000L

        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setEnabled(context: Context, on: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

        /** The parts offered while enabled (0 when off). */
        fun mode(context: Context): Int = if (!enabled(context)) 0 else
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, DEFAULT_MODE)

        /** What mainBufferedInfo holds: bits 1 bufferSizeMs, 2 audioBufferSize, 4 its own audioFormats entry. */
        fun infoVariant(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INFO, 0)

        fun streamType(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TYPE, STREAM_TYPE)

        fun format(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_FORMAT, 0x800000L)

        fun epochVariant(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_EPOCH, 1)

        fun formatEntry(type: Int, format: Long): Map<String, Any?> =
            linkedMapOf("type" to type, "audioType" to "media", "audioOutputFormats" to format)

        fun info(variant: Int, type: Int, format: Long): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
            if (variant and 1 != 0) put("bufferSizeMs", 120_000)
            if (variant and 2 != 0) put("audioBufferSize", AUDIO_BUFFER_BYTES)
            if (variant and 4 != 0) put("audioFormats", listOf(formatEntry(type, format)))
        }
    }
}
