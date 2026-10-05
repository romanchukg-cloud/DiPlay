package com.shilapi.xcertplay.airplay

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/** One display advertised to the phone in /info. */
data class AirPlayDisplayConfig(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: AirPlayInsets? = null,
    val safeArea: AirPlayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
    /** Display feature bits; null keeps the main-screen default (high-fidelity touch and knobs). */
    val features: Int? = null,
    /**
     * EXPERIMENT (lab): a second view area, the part of the stream right of this x, as Apple's widescreen
     * profile declares (full 1920x720 plus the right 1280x720). The car switches with updateViewArea.
     */
    val labSplitLeftPixels: Int? = null,
    /**
     * EXPERIMENT (lab): viewAreaStatusBarEdge for every view area, where CarPlay puts its dock. CarPlay
     * Simulator's StatusBarEdge has automatic, bottom and driver; the numbers are being tried.
     */
    val labStatusBarEdge: Int? = null,
    /**
     * EXPERIMENT (lab): two whole-screen view areas that differ only in their dock edge (0 = driver side,
     * 1 = bottom), to move the dock live with updateViewArea instead of reconnecting.
     */
    val labEdgeAreas: Boolean = false,
    /**
     * EXPERIMENT (lab): a square stream with a landscape area (the top) and a portrait area (the left),
     * to turn CarPlay with the screen by updateViewArea instead of reconnecting. Value = the short side.
     */
    val labRotationShortSide: Int? = null,
    /**
     * EXPERIMENT (lab): a second view area, the left part of this width, for the head unit's split screen
     * (DiPlay's window then covers half the screen); the car switches to it without reconnecting.
     */
    val labHalfAreaPixels: Int? = null,
    /** EXPERIMENT (lab): the split-screen area's height; null = the whole height. */
    val labHalfAreaHeightPixels: Int? = null,
    /** EXPERIMENT (lab): the rotation area to start in (0 landscape, 1 portrait). */
    val labRotationInitialArea: Int = 0,
    /**
     * EXPERIMENT (lab): view areas anchored at the canvas's top-left (width x height each), for rotation
     * and the head unit's split screen in one session; the car picks one with updateViewArea.
     */
    val labAreas: List<AirPlayAreaSize>? = null,
    val labInitialArea: Int = 0,
)

/** EXPERIMENT (lab): a view area's size; it starts at the canvas's top edge, [originX] from the left. */
data class AirPlayAreaSize(val width: Int, val height: Int, val originX: Int = 0)

/** One OEM homescreen icon. */
data class AirPlayIcon(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/** Immutable accessory configuration consumed by the AirPlay session server. */
data class AirPlayConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: AirPlayDisplayConfig,
    val cluster: AirPlayDisplayConfig? = null,
    val rightHandDrive: Boolean = false,
    val port: Int = 7000,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    val microphone: Boolean = false,
    val manufacturer: String = "xcertplay",
    val model: String = "xcertplay",
    val oemLabel: String = "xcertplay",
    val icons: List<AirPlayIcon> = emptyList(),
    /** iOS 27 video in car (see [VideoInCar]); video plays only while [VideoInCar.allowed]. */
    val videoInCar: Boolean = false,
    /**
     * EXPERIMENT: offer Enhanced Siri with the button mode only (enhancedSiriInfo) and log what the iPhone
     * sends back (AuxIn/AuxOut stream setups, Siri commands). No audio is provided for it yet.
     */
    val enhancedSiriProbe: Boolean = false,
    /**
     * EXPERIMENT: declare limitedUIElements and send setLimitedUI from the car's gear (limited unless in P),
     * so the iPhone knows when the car is parked. Without it the iPhone treats the car as always moving.
     */
    val limitedUiByGear: Boolean = false,
)
