package com.shilapi.xcertplay.diagnostics

import android.util.Log

/**
 * Logcat policy for connection diagnostics.
 *
 * Head units commonly run with ADB over the network enabled and ship system apps holding
 * `READ_LOGS`, so logcat is treated like an exported diagnostic report: it receives the same
 * redacted lines, and protocol traces (iAP2 frames, AirPlay headers and bodies, USBMUX frame
 * headers) are not written at all.
 *
 * A developer can opt in on their own device with
 *
 *     adb shell setprop log.tag.xcertplay-usb VERBOSE
 *
 * While that property is set, [traceEnabled] is true: traces are written at VERBOSE and
 * ordinary lines are written without redaction. Clearing the property restores the default.
 * The switch follows [Log.isLoggable], so a device-wide VERBOSE log level (`log.tag` or its
 * `persist.` form) turns it on too, and anyone with ADB access can set it.
 */
object DiagnosticLogging {
    const val TAG = "xcertplay-usb"

    @Volatile
    private var logUnavailable = false

    /** True only after the developer enabled VERBOSE logging for [TAG] on this device. */
    val traceEnabled: Boolean
        get() {
            if (logUnavailable) return false
            return try {
                Log.isLoggable(TAG, Log.VERBOSE)
            } catch (_: RuntimeException) {
                // android.jar stubs in plain JVM unit tests throw instead of answering.
                logUnavailable = true
                false
            }
        }

    /** Writes a state-transition line, redacted unless [traceEnabled]. Dropped lines stay out of logcat. */
    fun info(message: String, tag: String = TAG) {
        if (traceEnabled) {
            Log.i(tag, message)
            return
        }
        DiagnosticRedactor.redact(message)?.let { Log.i(tag, it) }
    }

    /**
     * Like [info] at WARN level. Exception messages can name the iPhone, its Bluetooth address or the
     * hotspot, so unless [traceEnabled] only the classes of the exception and its causes are written.
     */
    fun warn(message: String, error: Throwable? = null, tag: String = TAG) {
        if (traceEnabled) {
            if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
            return
        }
        val line = DiagnosticRedactor.redact(message) ?: return
        Log.w(tag, if (error != null) "$line: ${exceptionClasses(error)}" else line)
    }

    /** The exception's class and its causes' classes, without their messages. */
    fun exceptionClasses(error: Throwable): String =
        generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSES)
            .joinToString(" <- ") { it.javaClass.simpleName.ifEmpty { it.javaClass.name } }

    /** Writes a protocol trace only when [traceEnabled]. */
    fun trace(message: String, tag: String = TAG) {
        if (traceEnabled) Log.v(tag, message)
    }

    /** Builds and writes a protocol trace only when [traceEnabled], so hot paths skip the formatting. */
    inline fun trace(tag: String = TAG, message: () -> String) {
        if (traceEnabled) Log.v(tag, message())
    }

    private const val MAX_CAUSES = 4
}
