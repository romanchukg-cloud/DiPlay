package com.shilapi.xcertplay

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/** EXPERIMENT (lab): how large a square stream the head unit's decoder takes, for rotation by view areas. */
object LabRotation {
    private val CANDIDATES = listOf(2560, 2304, 2048, 1920, 1600)

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
