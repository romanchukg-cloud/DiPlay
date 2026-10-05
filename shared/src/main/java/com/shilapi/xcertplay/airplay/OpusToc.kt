package com.shilapi.xcertplay.airplay

/**
 * EXPERIMENT (lab): reads an Opus packet's TOC byte (RFC 6716, section 3.1) to tell how much audio a
 * packet holds, to compare with the RTP timestamp step on each CarPlay stream.
 */
internal object OpusToc {
    /** The audio duration of [packet] in microseconds, or null when it cannot be read. */
    fun durationMicros(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xff
        val config = toc shr 3
        val frameMicros = when {
            config < 12 -> intArrayOf(10_000, 20_000, 40_000, 60_000)[config % 4]
            config < 16 -> intArrayOf(10_000, 20_000)[config % 2]
            else -> intArrayOf(2_500, 5_000, 10_000, 20_000)[config % 4]
        }
        val frames = when (toc and 0x3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3f else return null
        }
        return frameMicros * frames
    }

    fun describe(packet: ByteArray): String =
        "toc=0x%02x audioMs=%s".format(packet.firstOrNull()?.toInt()?.and(0xff) ?: 0,
            durationMicros(packet)?.let { "%.1f".format(java.util.Locale.US, it / 1000.0) } ?: "?")
}
