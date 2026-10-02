package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class CallKeyEventsTest {
    @Test
    fun countsPhoneButtonPressesOnly() {
        val events = CallKeyEvents()
        // As getevent -q prints them for the wheel: down, sync, up, sync.
        assertEquals(1, events.presses("0001 010a 00000001\n0000 0000 00000000\n0001 010a 00000000\n0000 0000 00000000\n"))
        // Another wheel key (the voice button) is not the phone button.
        assertEquals(0, events.presses("0001 0122 00000001\n0001 0122 00000000\n"))
    }

    @Test
    fun aLineSplitAcrossChunksStillCounts() {
        val events = CallKeyEvents()
        assertEquals(0, events.presses("0001 01"))
        assertEquals(1, events.presses("0a 00000001\r\n0001 010a 00000000\n"))
    }
}
