package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayAreaSize
import kotlin.math.abs
import kotlin.math.ln

/** EXPERIMENT (lab): which declared view area suits a window. */
object LabAreas {
    /** A window more than this far (as an aspect ratio) from every area needs a reconnect instead. */
    private const val MAX_ASPECT_MISMATCH = 1.25

    /** The area whose shape is closest to [width] x [height], or null when none is close enough. */
    fun closest(areas: List<AirPlayAreaSize>, width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || areas.isEmpty()) return null
        val window = width.toDouble() / height
        val best = areas.indices.minByOrNull { abs(ln(areas[it].width.toDouble() / areas[it].height / window)) } ?: return null
        val area = areas[best]
        return best.takeIf { abs(ln(area.width.toDouble() / area.height / window)) <= ln(MAX_ASPECT_MISMATCH) }
    }
}
