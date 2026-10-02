package com.shilapi.xcertplay.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.Iap2LocationMessages
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** One GPS sample for the heading: course over ground and speed, as Android reports them. */
data class CourseSample(val timeMillis: Long, val courseDegrees: Double?, val speedMetersPerSecond: Double?)

/**
 * Which way the car points, for the iPhone's `$GPHDT`, including while it stands still.
 *
 * Moving fast enough, the GPS course gives the heading. Below that speed (parking, standing) the GPS
 * course is unreliable or missing, so turns are followed with the head unit's gyroscope from the last
 * good course. The result is kept across trips: the car usually still points the same way when it
 * starts again.
 *
 * Reversing fast, the GPS course points backwards. A course about opposite to the tracked heading is
 * therefore taken as reversing, unless it lasts so long that the tracked heading must have been wrong.
 */
class HeadingTracker(initialDegrees: Double? = null) {
    enum class Source { STORED, GPS, GYRO }

    /** How a GPS sample was used. */
    enum class CourseUse { IGNORED, FORWARD, REVERSING }

    var degrees: Double? = initialDegrees?.let(::normalize)
        private set
    var source: Source = Source.STORED
        private set
    private var oppositeFixes = 0

    fun onCourse(sample: CourseSample?): CourseUse {
        val course = sample?.courseDegrees?.takeIf { it.isFinite() } ?: return CourseUse.IGNORED
        val speed = sample.speedMetersPerSecond?.takeIf { it.isFinite() } ?: return CourseUse.IGNORED
        if (speed < MIN_RELIABLE_SPEED_MPS) return CourseUse.IGNORED
        val current = degrees
        if (current != null && abs(difference(current, course)) > OPPOSITE_DEGREES) {
            oppositeFixes++
            if (oppositeFixes < REVERSING_FIXES_MAX) {
                degrees = normalize(course + 180.0)
                source = Source.GPS
                return CourseUse.REVERSING
            }
        } else {
            oppositeFixes = 0
        }
        degrees = normalize(course)
        source = Source.GPS
        return CourseUse.FORWARD
    }

    /** The car turned by [counterClockwiseDegrees] seen from above, measured by the gyroscope. */
    fun onTurn(counterClockwiseDegrees: Double) {
        val current = degrees ?: return
        if (!counterClockwiseDegrees.isFinite()) return
        degrees = normalize(current - counterClockwiseDegrees)
        source = Source.GYRO
    }

    companion object {
        /** About 10 km/h: slower, the GPS course wanders. */
        const val MIN_RELIABLE_SPEED_MPS = 2.8

        /** A course this far from the heading is taken as reversing. */
        const val OPPOSITE_DEGREES = 135.0

        /** Nobody reverses this many seconds above 10 km/h: then the heading itself was wrong. */
        const val REVERSING_FIXES_MAX = 10

        fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

        /** The signed smallest angle from [from] to [to], -180..180. */
        fun difference(from: Double, to: Double): Double = normalize(to - from + 180.0) - 180.0
    }
}

/** NMEA `$GPHDT`: the vehicle's true heading in degrees. */
object HdtEncoder {
    fun encode(degrees: Double): String {
        // Round first, so 359.96° is sent as 0.0, not 360.0.
        val tenths = Math.round(HeadingTracker.normalize(degrees) * 10).toInt() % 3600
        val body = "GPHDT," + String.format(Locale.US, "%.1f", tenths / 10.0) + ",T"
        var checksum = 0
        for (character in body) checksum = checksum xor character.code
        return "$$body*" + String.format(Locale.US, "%02X", checksum) + "\r\n"
    }
}

/**
 * The [HeadingTracker] fed by the head unit's gyroscope.
 *
 * Turns are rotation about the vertical, taken from a real accelerometer: on BYD DiLink the default
 * accelerometer is a constant stand-in, and Android's gravity sensor is built from it. The gyroscope
 * on the same chip as that accelerometer turns the heading; every gyroscope is also measured and
 * compared with the GPS course in the journal, to tell which one is right. Each gyroscope's bias is
 * learned while the car stands still.
 *
 * On the tested BYD the chip gyroscope also reads a steady extra rotation (4–6°/s, either way)
 * whenever the car moves, even straight ahead, and it differs from drive to drive. So each drive
 * learns its own offset from straight stretches against the GPS course; until then turns made while
 * moving are not followed (the GPS course takes over above 10 km/h), and afterwards the offset is
 * taken off, including while parking. The source listens to GPS itself, because the iPhone does not
 * ask for location on every connection. Bias and heading are saved every few seconds, because the
 * head unit sleeps without warning when the car is switched off.
 */
class CarHeadingSource(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val lock = Any()
    private val tracker = HeadingTracker(
        prefs.getFloat(KEY_DEGREES, Float.NaN).toDouble().takeIf { it.isFinite() },
    )

    /** One gyroscope: its learned bias and what it measured since the last drive report. */
    private class Gyro(val sensor: Sensor) {
        val bias = DoubleArray(3)
        var biasSamples = 0
        var biasLogged = false
        var lastNanos = 0L
        val axes = DoubleArray(3) // degrees per axis in the report window
        var clockwise = 0.0 // degrees about the vertical in the report window
    }

    // Sensor thread and update() both use these, under lock.
    private val up = DoubleArray(3)
    private var upSamples = 0
    private val gyros = LinkedHashMap<Sensor, Gyro>()
    private var primary: Gyro? = null
    private var primaryRawClockwise = 0.0 // degrees in the report window, without the moving offset
    private var movingOffset = prefs.getFloat(KEY_MOVING_OFFSET, Float.NaN).toDouble() // clockwise °/s, NaN = unknown
    @Volatile private var stationary = false
    @Volatile private var moving = false
    @Volatile private var lastSampleAt = 0L // elapsedRealtime of the last GPS sample with a speed
    @Volatile private var learnedThisDrive = false
    private val locations = app.getSystemService(LocationManager::class.java)
    @Volatile var hasOwnGps = false
        private set
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            update(
                CourseSample(
                    timeMillis = location.time,
                    courseDegrees = location.bearing.takeIf { location.hasBearing() }?.toDouble(),
                    speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() }?.toDouble(),
                ),
            )
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    // update() only.
    private var savedDegrees: Double? = tracker.degrees
    private var savedAt = 0L
    private var loggedDegrees: Double? = null
    private var lastForwardAt = 0L
    private var reversingLogged = false
    private var windowStartAt = 0L
    private var windowCourse: Double? = null
    private var windowSpeedSum = 0.0
    private var windowSpeedCount = 0
    private var previousWindowStraight = false
    private var thread: HandlerThread? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            synchronized(lock) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> onAcceleration(event.values)
                    Sensor.TYPE_GYROSCOPE -> gyros[event.sensor]?.let { onRotation(it, event) }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun onAcceleration(values: FloatArray) {
        val length = sqrt((values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble())
        // Braking and cornering add to gravity; only near-1 g samples say where "up" is.
        if (abs(length - SensorManager.GRAVITY_EARTH) > 1.0) return
        val weight = if (upSamples < 20) 1.0 / (upSamples + 1) else UP_SMOOTHING
        for (i in 0..2) up[i] += (values[i] / length - up[i]) * weight
        upSamples++
        if (upSamples == 50) note("up from accelerometer ${vector(up)}")
    }

    private fun onRotation(gyro: Gyro, event: SensorEvent) {
        val previous = gyro.lastNanos
        gyro.lastNanos = event.timestamp
        if (previous == 0L) return
        val seconds = (event.timestamp - previous) / 1e9
        if (seconds <= 0 || seconds > 0.5) return
        if (stationary) {
            gyro.biasSamples++
            val weight = if (gyro.biasSamples < 50) 1.0 / gyro.biasSamples else BIAS_SMOOTHING
            for (i in 0..2) gyro.bias[i] += (event.values[i] - gyro.bias[i]) * weight
            if (gyro.biasSamples == 200 && !gyro.biasLogged) {
                gyro.biasLogged = true
                note("bias ${gyro.sensor.name} ${vector(DoubleArray(3) { Math.toDegrees(gyro.bias[it]) })}°/s")
                if (gyro === primary) saveBias(gyro)
            }
        }
        val biasKnown = gyro.biasSamples >= 50
        val rate = DoubleArray(3) { event.values[it] - if (biasKnown) gyro.bias[it] else 0.0 }
        for (i in 0..2) gyro.axes[i] += Math.toDegrees(rate[i]) * seconds
        if (upSamples < 20) return
        // Rotation about "up" (right-hand rule) is counter-clockwise seen from above.
        val clockwise = -Math.toDegrees(rate[0] * up[0] + rate[1] * up[1] + rate[2] * up[2])
        gyro.clockwise += clockwise * seconds
        if (gyro !== primary) return
        primaryRawClockwise += clockwise * seconds
        // Before the bias is known a parked car would seem to turn.
        if (!biasKnown) return
        // Without a fresh GPS sample it is unknown whether the car moves, so whether to take off
        // the moving offset; and moving before this drive's offset is learned, turns are unknown.
        if (SystemClock.elapsedRealtime() - lastSampleAt > SAMPLE_FRESH_MILLIS) return
        val turning = when {
            stationary -> clockwise
            moving && learnedThisDrive -> clockwise - movingOffset
            else -> return
        }
        if (abs(turning) >= GYRO_DEADBAND_DEG_PER_S) tracker.onTurn(-turning * seconds)
    }

    fun start() {
        if (thread != null) return
        val manager = sensors ?: return
        val accelerometer = manager.getSensorList(Sensor.TYPE_ACCELEROMETER).firstOrNull(::isChip)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscopes = manager.getSensorList(Sensor.TYPE_GYROSCOPE)
        val main = gyroscopes.firstOrNull(::isChip) ?: manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        note("start stored=${format(synchronized(lock) { tracker.degrees })} accel=${accelerometer?.name} " +
            "gyros=${gyroscopes.map { it.name }} turns=${main?.name}")
        if (accelerometer == null || main == null) return
        val worker = HandlerThread("diplay-heading").apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        synchronized(lock) {
            gyros.clear()
            gyroscopes.forEach { gyros[it] = Gyro(it) }
            primary = gyros[main]
            upSamples = 0
            primary?.let(::loadBias)
            learnedThisDrive = false
            lastSampleAt = 0L
        }
        note("last drive's moving offset ${String.format(Locale.US, "%.2f", movingOffset)}°/s (relearned each drive)")
        manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME, handler)
        gyroscopes.forEach { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        listenToGps(worker)
    }

    // The heading needs GPS on every drive, also when the iPhone does not ask for location.
    @SuppressLint("MissingPermission")
    private fun listenToGps(worker: HandlerThread) {
        val manager = locations ?: return
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            note("no location permission: heading follows only the iPhone's location requests")
            return
        }
        hasOwnGps = runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MILLIS, 0f, locationListener, worker.looper)
        }.isSuccess
        note("own gps ${if (hasOwnGps) "on" else "failed"}")
    }

    /** The heading now, for the iPhone. */
    fun current(): Double? = synchronized(lock) { tracker.degrees }

    fun stop() {
        sensors?.unregisterListener(listener)
        if (hasOwnGps) runCatching { locations?.removeUpdates(locationListener) }
        hasOwnGps = false
        thread?.quitSafely()
        thread = null
        save(force = true)
        synchronized(lock) { primary?.takeIf { it.biasSamples >= 200 }?.let(::saveBias) }
        val (degrees, source) = synchronized(lock) { tracker.degrees to tracker.source }
        note("stop heading=${format(degrees)} from ${source.name.lowercase()}")
    }

    /** Called about once a second with the latest GPS sample; returns the current heading. */
    fun update(sample: CourseSample?): Double? {
        val now = SystemClock.elapsedRealtime()
        val speed = sample?.speedMetersPerSecond?.takeIf { it.isFinite() }
        stationary = speed != null && speed < STATIONARY_SPEED_MPS
        moving = speed != null && speed >= STATIONARY_SPEED_MPS
        if (speed != null) lastSampleAt = now
        val (before, beforeSource) = synchronized(lock) { tracker.degrees to tracker.source }
        val (use, degrees) = synchronized(lock) { tracker.onCourse(sample) to tracker.degrees }
        when (use) {
            HeadingTracker.CourseUse.FORWARD -> {
                reversingLogged = false
                if (now - lastForwardAt > TAKEOVER_GAP_MILLIS && degrees != null) {
                    // How far the stored or gyro-followed heading was off when the GPS course took over.
                    val off = before?.let { HeadingTracker.difference(it, degrees) }
                    note("gps course ${format(degrees)} took over from ${beforeSource.name.lowercase()} ${format(before)}" +
                        " (off by ${off?.let { String.format(Locale.US, "%.0f°", it) } ?: "?"})")
                    loggedDegrees = degrees
                }
                lastForwardAt = now
            }
            HeadingTracker.CourseUse.REVERSING -> if (!reversingLogged) {
                reversingLogged = true
                note("reversing: gps course ${format(sample?.courseDegrees)}, heading ${format(degrees)}")
            }
            HeadingTracker.CourseUse.IGNORED -> Unit
        }
        if (use == HeadingTracker.CourseUse.IGNORED && degrees != null &&
            (loggedDegrees == null || abs(HeadingTracker.difference(loggedDegrees!!, degrees)) > LOG_STEP_DEGREES)
        ) {
            note("heading ${format(degrees)} from ${synchronized(lock) { tracker.source }.name.lowercase()}")
            loggedDegrees = degrees
        }
        report(now, use, sample?.courseDegrees, speed)
        save(force = false)
        return degrees
    }

    /**
     * Every few seconds of forward driving: the GPS turn next to each gyroscope's turn. On a straight
     * stretch the difference is the gyroscope's moving offset, which is learned here.
     */
    private fun report(now: Long, use: HeadingTracker.CourseUse, course: Double?, speed: Double?) {
        if (use != HeadingTracker.CourseUse.FORWARD || course == null) {
            windowCourse = null
            previousWindowStraight = false
            synchronized(lock) { resetWindows() }
            return
        }
        val start = windowCourse
        if (start == null) {
            windowCourse = course
            windowStartAt = now
            windowSpeedSum = 0.0
            windowSpeedCount = 0
            synchronized(lock) { resetWindows() }
            return
        }
        speed?.let { windowSpeedSum += it; windowSpeedCount++ }
        if (now - windowStartAt < REPORT_MILLIS) return
        val seconds = (now - windowStartAt) / 1000.0
        val gps = HeadingTracker.difference(start, course)
        val kmh = if (windowSpeedCount > 0) windowSpeedSum / windowSpeedCount * 3.6 else 0.0
        val (measured, raw) = synchronized(lock) {
            val line = gyros.values.joinToString(" | ") { "${it.sensor.name} ${signed(it.clockwise)} xyz ${vector(it.axes)}" }
            val raw = primaryRawClockwise
            resetWindows()
            line to raw
        }
        // Only clearly straight stretches teach the offset: in and after turns the GPS course lags
        // behind the car. Two straight windows in a row, fast enough for a steady GPS course.
        val straight = abs(gps) <= STRAIGHT_DEGREES && kmh >= LEARN_MIN_KMH
        val learned = if (straight && previousWindowStraight) {
            val offset = (raw - gps) / seconds
            // The offset changes from drive to drive: the first learned window of a drive replaces it.
            movingOffset = if (learnedThisDrive) movingOffset + (offset - movingOffset) * OFFSET_SMOOTHING else offset
            if (!learnedThisDrive) note("this drive's moving offset learned: ${String.format(Locale.US, "%.2f", offset)}°/s")
            learnedThisDrive = true
            prefs.edit().putFloat(KEY_MOVING_OFFSET, movingOffset.toFloat()).apply()
            " learned ${String.format(Locale.US, "%.2f", offset)}°/s"
        } else ""
        previousWindowStraight = straight
        note("drive ${seconds.toInt()}s ${String.format(Locale.US, "%.0f", kmh)} km/h gps ${signed(gps)} | $measured" +
            " | offset ${String.format(Locale.US, "%.2f", movingOffset)}°/s$learned")
        windowCourse = course
        windowStartAt = now
        windowSpeedSum = 0.0
        windowSpeedCount = 0
    }

    private fun resetWindows() {
        primaryRawClockwise = 0.0
        gyros.values.forEach { gyro ->
            gyro.clockwise = 0.0
            gyro.axes.fill(0.0)
        }
    }

    private fun saveBias(gyro: Gyro) {
        prefs.edit()
            .putString(KEY_BIAS_SENSOR, gyro.sensor.name)
            .putFloat(KEY_BIAS_X, gyro.bias[0].toFloat())
            .putFloat(KEY_BIAS_Y, gyro.bias[1].toFloat())
            .putFloat(KEY_BIAS_Z, gyro.bias[2].toFloat())
            .apply()
    }

    // The last trip's bias, so turns count from the first second; it is relearned while parked.
    private fun loadBias(gyro: Gyro) {
        if (prefs.getString(KEY_BIAS_SENSOR, null) != gyro.sensor.name) return
        gyro.bias[0] = prefs.getFloat(KEY_BIAS_X, 0f).toDouble()
        gyro.bias[1] = prefs.getFloat(KEY_BIAS_Y, 0f).toDouble()
        gyro.bias[2] = prefs.getFloat(KEY_BIAS_Z, 0f).toDouble()
        gyro.biasSamples = 50
    }

    private fun save(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val degrees = synchronized(lock) { tracker.degrees } ?: return
        if (!force && now - savedAt < SAVE_INTERVAL_MILLIS) return
        val saved = savedDegrees
        if (saved != null && abs(HeadingTracker.difference(saved, degrees)) < 0.5) return
        prefs.edit().putFloat(KEY_DEGREES, degrees.toFloat()).putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply()
        savedDegrees = degrees
        savedAt = now
    }

    /** Logs [line] and keeps it in a small journal file, readable after the trip with run-as. */
    fun note(line: String) {
        Log.i(TAG, line)
        runCatching {
            val file = File(app.filesDir, JOURNAL)
            if (file.length() > JOURNAL_MAX_BYTES) {
                val keep = file.readLines().takeLast(JOURNAL_KEEP_LINES)
                file.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("$time $line\n")
        }
    }

    private fun format(degrees: Double?) = degrees?.let { String.format(Locale.US, "%.0f°", it) } ?: "none"

    private fun signed(degrees: Double) = String.format(Locale.US, "%+.0f°", degrees)

    private fun vector(values: DoubleArray) =
        values.joinToString(",", "(", ")") { String.format(Locale.US, "%.2f", it) }

    // A real sensor chip, not the stand-in BYD's default accelerometer is.
    private fun isChip(sensor: Sensor) = sensor.name.lowercase().let { "iner" in it || "icm" in it }

    companion object {
        private const val TAG = "DiPlay-Heading"
        private const val PREFS = "diplay_car_heading"
        private const val KEY_DEGREES = "degrees"
        private const val KEY_SAVED_AT = "saved_at"
        // v2: the first lab build learned from turns; its value is not reused.
        private const val KEY_MOVING_OFFSET = "moving_offset_v2"
        private const val SAMPLE_FRESH_MILLIS = 3_000L
        private const val GPS_INTERVAL_MILLIS = 1_000L
        private const val LEARN_MIN_KMH = 20.0
        private const val KEY_BIAS_SENSOR = "bias_sensor"
        private const val KEY_BIAS_X = "bias_x"
        private const val KEY_BIAS_Y = "bias_y"
        private const val KEY_BIAS_Z = "bias_z"
        private const val STRAIGHT_DEGREES = 5.0
        private const val OFFSET_SMOOTHING = 0.3
        private const val GYRO_DEADBAND_DEG_PER_S = 0.3
        private const val STATIONARY_SPEED_MPS = 0.3
        private const val UP_SMOOTHING = 0.01
        private const val BIAS_SMOOTHING = 0.005
        private const val SAVE_INTERVAL_MILLIS = 5_000L
        private const val TAKEOVER_GAP_MILLIS = 5_000L
        private const val REPORT_MILLIS = 5_000L
        private const val LOG_STEP_DEGREES = 10.0
        private const val JOURNAL = "heading-journal.txt"
        private const val JOURNAL_MAX_BYTES = 128 * 1024L
        private const val JOURNAL_KEEP_LINES = 800
    }
}

/**
 * Adds `$GPHDT` with the car's heading when the iPhone asked for vehicle heading (0xFFFA id 7). The
 * heading is followed whether or not the iPhone asked, so it is ready for the next trip.
 */
class VehicleHeadingLocationProvider(
    private val position: Iap2LocationProvider,
    private val heading: CarHeadingSource,
    private val latestCourse: () -> CourseSample?,
) : Iap2LocationProvider {
    @Volatile private var headingRequested = false
    private var firstSentLogged = false

    override fun onRequested(components: Set<Int>) {
        headingRequested = Iap2LocationMessages.VEHICLE_HEADING_DATA in components
        heading.note("iPhone asked for heading: $headingRequested components=$components")
        position.onRequested(components)
    }

    override fun start(): Boolean {
        heading.start()
        return position.start() || headingRequested
    }

    override fun stop() {
        heading.stop()
        position.stop()
    }

    override fun latestNmea(): String? {
        val degrees = if (heading.hasOwnGps) heading.current() else heading.update(latestCourse())
        val fix = position.latestNmea()
        val hdt = if (headingRequested) degrees?.let(HdtEncoder::encode) else null
        if (hdt != null && !firstSentLogged) {
            firstSentLogged = true
            heading.note("sent ${hdt.trim()}")
        }
        return if (fix == null) hdt else fix + hdt.orEmpty()
    }
}
