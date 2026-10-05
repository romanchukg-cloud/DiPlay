package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayAreaSize
import kotlin.math.abs
import kotlin.math.ln

/** EXPERIMENT (lab): which declared view area suits a window. */
object LabAreas {
    /** What a declared area is for. */
    enum class Kind(val portrait: Boolean, val split: Boolean, val manual: Boolean = false) {
        LANDSCAPE(false, false), PORTRAIT(true, false), LANDSCAPE_SPLIT(false, true), PORTRAIT_SPLIT(true, true),
        /** The right two thirds beside DiPlay's side panel; only the panel's button picks it. */
        SIDE_PANEL(false, false, manual = true),
        /** On the portrait screen: the top two thirds above DiPlay's panel. */
        SIDE_PANEL_PORTRAIT(true, false, manual = true),
    }

    /** A window more than this far (as an aspect ratio) from every area needs a reconnect instead. */
    private const val MAX_ASPECT_MISMATCH = 1.25

    /** The area whose shape is closest to [width] x [height], or null when none is close enough. */
    fun closest(areas: List<AirPlayAreaSize>, width: Int, height: Int): Int? =
        closestOf(areas, areas.indices.toList(), width, height)

    /**
     * The area for this window: among those of the screen's orientation and split state when [kinds] has
     * any, otherwise among all; null when none has about the window's shape.
     */
    fun pick(areas: List<AirPlayAreaSize>, kinds: List<Kind>?, width: Int, height: Int, portrait: Boolean, split: Boolean): Int? {
        val automatic = areas.indices.filter { kinds?.getOrNull(it)?.manual != true }
        val matching = automatic.filter { kinds?.getOrNull(it)?.let { k -> k.portrait == portrait && k.split == split } == true }
        return closestOf(areas, matching, width, height) ?: closestOf(areas, automatic, width, height)
    }

    private fun closestOf(areas: List<AirPlayAreaSize>, candidates: List<Int>, width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || candidates.isEmpty()) return null
        val window = width.toDouble() / height
        fun mismatch(index: Int) = abs(ln(areas[index].width.toDouble() / areas[index].height / window))
        val best = candidates.minByOrNull(::mismatch) ?: return null
        return best.takeIf { mismatch(it) <= ln(MAX_ASPECT_MISMATCH) }
    }
}
