package com.shilapi.xcertplay.diagnostics

import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DiagnosticLoggingTest {
    @Before fun resetLogs() {
        ShadowLog.clear()
        ShadowLog.setLoggable(DiagnosticLogging.TAG, Log.INFO)
    }

    @After fun restoreLevel() {
        ShadowLog.setLoggable(DiagnosticLogging.TAG, Log.INFO)
    }

    @Test fun defaultLogcatCarriesOnlyRedactedStateLines() {
        assertFalse(DiagnosticLogging.traceEnabled)

        DiagnosticLogging.info("AirPlay session active peer=192.168.49.12 mac=aa:bb:cc:dd:ee:ff")
        DiagnosticLogging.info("hotspot passphrase=hunter2")
        DiagnosticLogging.info("TRACE airplay control rx bodyHex=00ff")
        DiagnosticLogging.trace("IAP2 TX [wired] 0x4301 CarPlayStartSession passphrase=hunter2")
        DiagnosticLogging.trace { "usbmux rx proto=6 length=20" }
        DiagnosticLogging.warn("wired bring-up failed token=abc", IllegalStateException("boom"))

        val logged = ShadowLog.getLogsForTag(DiagnosticLogging.TAG)
        assertEquals(1, logged.size)
        assertEquals(Log.INFO, logged.single().type)
        assertEquals("AirPlay session active peer=[ip] mac=[address]", logged.single().msg)
    }

    @Test fun verboseTagOptsIntoRawLinesAndTraces() {
        ShadowLog.setLoggable(DiagnosticLogging.TAG, Log.VERBOSE)
        assertTrue(DiagnosticLogging.traceEnabled)

        DiagnosticLogging.info("AirPlay session active peer=192.168.49.12")
        DiagnosticLogging.trace("IAP2 TX [wired] 0x4301 CarPlayStartSession")
        DiagnosticLogging.trace { "usbmux rx proto=6 length=20" }
        DiagnosticLogging.warn("wired bring-up failed token=abc")

        val logged = ShadowLog.getLogsForTag(DiagnosticLogging.TAG)
        assertEquals(
            listOf(
                Log.INFO to "AirPlay session active peer=192.168.49.12",
                Log.VERBOSE to "IAP2 TX [wired] 0x4301 CarPlayStartSession",
                Log.VERBOSE to "usbmux rx proto=6 length=20",
                Log.WARN to "wired bring-up failed token=abc",
            ),
            logged.map { it.type to it.msg },
        )
    }

    @Test fun traceBuildersAreNotEvaluatedWhileTracingIsOff() {
        var built = 0
        DiagnosticLogging.trace { built++; "never" }
        assertEquals(0, built)

        ShadowLog.setLoggable(DiagnosticLogging.TAG, Log.VERBOSE)
        DiagnosticLogging.trace { built++; "once" }
        assertEquals(1, built)
    }

    @Test fun warningsKeepExceptionMessagesOutOfDefaultLogcat() {
        val cause = java.io.IOException("connecting RFCOMM to AA:BB:CC:DD:EE:FF timed out")
        DiagnosticLogging.warn(
            "wireless bring-up failed",
            IllegalStateException("Multiple connected iPhones found: Owner's iPhone (AA:BB:CC:DD:EE:FF)", cause),
        )

        val logged = ShadowLog.getLogsForTag(DiagnosticLogging.TAG).single()
        assertEquals(Log.WARN, logged.type)
        assertEquals("wireless bring-up failed: IllegalStateException <- IOException", logged.msg)
        assertNull("the exception and its message are not written", logged.throwable)
    }

    @Test fun withTracingOnWarningsKeepTheirException() {
        ShadowLog.setLoggable(DiagnosticLogging.TAG, Log.VERBOSE)
        val error = IllegalStateException("boom")
        DiagnosticLogging.warn("wired bring-up failed", error)

        val logged = ShadowLog.getLogsForTag(DiagnosticLogging.TAG).single()
        assertEquals("wired bring-up failed", logged.msg)
        assertSame(error, logged.throwable)
    }
}
