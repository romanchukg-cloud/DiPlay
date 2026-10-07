package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayTraceBodyTest {
    @Test fun bodiesCarryingIap2PacketsAreNotDumpedEvenInTraces() {
        // A command plist whose data holds an iAP2 packet (FF 5A ...), e.g. a location or call update.
        val tunnel = byteArrayOf(0x62, 0x70, 0x6c, 0xff.toByte(), 0x5a, 0x00, 0x10, 0x40)
        assertTrue(carriesIap2Packet(tunnel))
        assertEquals("body=8B withheld (iAP2 data; see the IAP2 frame trace)", traceBody(tunnel))
    }

    @Test fun otherBodiesAreStillDumpedForProtocolWork() {
        val plain = byteArrayOf(0x62, 0x70, 0x6c, 0x69, 0x73, 0x74)
        assertFalse(carriesIap2Packet(plain))
        assertTrue(traceBody(plain).startsWith("bodyHex="))
        assertFalse(carriesIap2Packet(byteArrayOf(0xff.toByte()))) // a lone FF at the end is not a marker
    }
}
