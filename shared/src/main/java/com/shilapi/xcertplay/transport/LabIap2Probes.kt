package com.shilapi.xcertplay.transport

import android.content.Context
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * EXPERIMENT (lab): does the iPhone use more of what a car can declare? Each probe is a separate
 * switch, off by default, because the iPhone may reject an identification it does not like (then
 * CarPlay does not start until the switch is off again). Formats are from Apple's iAP2 message spec
 * in Xcode's CarPlay Simulator (iap2messages-internal.i2mspecarchive, read as data).
 *
 * - [VEHICLE_EXTRAS]: VehicleStatusComponent (21) also declares OutsideTemperature (4),
 *   InsideTemperature (5), WiperStatus (17), BarometricPressure (18), Alerts (19) and
 *   PassengerSeatStatus (20); StartVehicleStatusUpdates (0xA100) shows which the iPhone wants.
 * - [ROAD_OBJECTS]: RoadObjectDetectionComponent (33) with road signs, lanes and objects; does the
 *   iPhone send StartRoadObjectDetectionUpdates (0x0D00)?
 * - [APP_DISCOVERY]: the car sends StartAppDiscoveryUpdates (0xAD00) for all CarPlay apps with 90 px
 *   icons; what comes back in AppDiscoveryUpdate (0xAD01)?
 */
object LabIap2Probes {
    const val VEHICLE_EXTRAS = "vehicle_extras"
    const val ROAD_OBJECTS = "road_objects"
    const val APP_DISCOVERY = "app_discovery"

    const val START_ROAD_OBJECT_DETECTION = 0x0D00
    const val ROAD_OBJECT_DETECTION_UPDATE = 0x0D01
    const val STOP_ROAD_OBJECT_DETECTION = 0x0D02
    const val START_APP_DISCOVERY = 0xAD00
    const val APP_DISCOVERY_UPDATE = 0xAD01
    const val STOP_APP_DISCOVERY = 0xAD02
    const val REQUEST_APP_ICONS = 0xAD03
    const val APP_ICON = 0xAD04

    private const val PREFS = "diplay_lab_iap2_probes"

    fun enabled(context: Context, probe: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(probe, false)

    fun setEnabled(context: Context, probe: String, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(probe, enabled).apply()

    /** Extra status fields inside group 21. */
    fun Iap2BodyBuilder.vehicleStatusExtras() {
        void(4) // OutsideTemperature
        void(5) // InsideTemperature
        void(17) // WiperStatus
        void(18) // BarometricPressure
        void(19) // Alerts
        void(20) // PassengerSeatStatus
    }

    /** IdentificationInformation param 33. */
    fun Iap2BodyBuilder.roadObjectDetectionComponent() {
        group(33) {
            u16(0, 5) // Identifier
            string(1, "Camera") // Name
            u8(2, 1) // SupportedRoadObjectDetectionType: Road Sign
            u8(2, 2) // Road Lane
            u8(2, 3) // Road Object
        }
    }

    fun startAppDiscovery(): Iap2Frame = Iap2Messages.buildRaw(START_APP_DISCOVERY) {
        group(0) { void(0) } // CarPlayAppCategories: AllCarPlayApps
        u16(2, 90) // CarPlayAppIconSize, px
    }

    /** The messages a probe makes the iPhone send, logged in full. */
    val LOGGED = setOf(
        Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES,
        START_ROAD_OBJECT_DETECTION, STOP_ROAD_OBJECT_DETECTION,
        APP_DISCOVERY_UPDATE, APP_ICON,
    )

    fun describe(frame: Iap2Frame): String = "LAB-PROBE " + Iap2FrameFormatter.format(Iap2TraceDirection.RX, "probe", frame)
}
