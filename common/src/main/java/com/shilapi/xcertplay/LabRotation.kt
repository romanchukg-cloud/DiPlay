package com.shilapi.xcertplay

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/** EXPERIMENT (lab): how large a square stream the head unit's decoder takes, for rotation by view areas. */
object LabRotation {
    private val CANDIDATES = listOf(2560, 2304, 2048, 1920, 1600)

    /** BYD's custom key (305), which rotates the screen on our Tang; told when it passes on to BYD. */
    const val ROTATE_KEY = 305

    /** The running host's hook: the screen has started to turn (the motor takes about 3 s). */
    @Volatile var onRotateKey: (() -> Unit)? = null

    /** How long after the key to move CarPlay to the new orientation (lab setting, default 1.5 s). */
    fun leadMillis(context: android.content.Context): Long =
        context.getSharedPreferences("diplay_lab_split_screen", android.content.Context.MODE_PRIVATE)
            .getInt("rotation_lead_millis", 1_500).toLong()

    fun squareSide(longSide: Int, hevc: Boolean): Int {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        } }.getOrDefault(emptyList())
        val side = CANDIDATES.filter { it <= longSide }.firstOrNull { candidate ->
            decoders.any { info ->
                runCatching { info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(candidate, candidate) == true }
                    .getOrDefault(false)
            }
        } ?: 1600
        Log.i("DiPlay-Rotation", "square side $side for long side $longSide hevc=$hevc decoders=${decoders.map { it.name }}")
        return side
    }
}
