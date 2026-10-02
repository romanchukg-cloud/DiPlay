package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydClusterCallTest {
    private var now = 1_000L
    private val state = ClusterCallState { now }

    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(ClusterCallState.CALL_STATE_UPDATE, block)

    @Test
    fun incomingCallRingsThenRunsFromTheAnswer() {
        // The session starts with a status-only update: no call.
        assertNull(state.accept(update { u8(2, 0) }))

        assertEquals(ClusterCall("Anna", ClusterCall.Phase.RINGING, null),
            state.accept(update { string(0, "+380 00 000 0000"); string(1, "Anna"); u8(2, 2); u8(3, 1); string(4, "A") }))

        now = 5_000L
        assertEquals(ClusterCall("Anna", ClusterCall.Phase.ACTIVE, 5_000L), state.accept(update { u8(2, 4); string(4, "A") }))
        // Held keeps the answer time, so the length keeps counting.
        now = 9_000L
        assertEquals(ClusterCall("Anna", ClusterCall.Phase.ACTIVE, 5_000L), state.accept(update { u8(2, 5); string(4, "A") }))

        assertNull(state.accept(update { u8(2, 6); string(4, "A") }))
    }

    @Test
    fun outgoingCallShowsTheNumberWithoutAName() {
        assertEquals(ClusterCall("+380 00 000 0000", ClusterCall.Phase.DIALING, null),
            state.accept(update { string(0, "+380 00 000 0000"); u8(2, 1); u8(3, 2); string(4, "B") }))
        assertEquals(ClusterCall.Phase.DIALING, state.accept(update { u8(2, 3); string(4, "B") })?.phase)
    }

    @Test
    fun answeredCallWinsOverASecondRingingCall() {
        state.accept(update { string(1, "Anna"); u8(2, 4); string(4, "A") })
        assertEquals("Anna", state.accept(update { string(1, "Bob"); u8(2, 2); string(4, "B") })?.name)

        // The first call ends: the second one shows.
        assertEquals(ClusterCall("Bob", ClusterCall.Phase.RINGING, null), state.accept(update { u8(2, 0); string(4, "A") }))
    }

    @Test
    fun sessionResetAndOtherMessagesClearOrKeep() {
        state.accept(update { string(1, "Anna"); u8(2, 2); string(4, "A") })
        assertEquals("Anna", state.accept(Iap2Messages.buildRaw(0x5001) { u8(2, 0) })?.name)
        assertNull(state.accept(update { u8(2, 0) }))

        state.accept(update { string(1, "Anna"); u8(2, 2); string(4, "A") })
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun nameFitsTheDashboard() {
        val long = ClusterCallState.text("Олександра Костянтинівна Шевченко-Франко")
        assertTrue(long.toByteArray(Charsets.UTF_16LE).size <= ClusterCallState.MAX_NAME_BYTES)
        assertEquals(30, long.length)

        val emoji = ClusterCallState.text("a" + "🙂".repeat(20))
        assertTrue(emoji.toByteArray(Charsets.UTF_16LE).size <= ClusterCallState.MAX_NAME_BYTES)
        assertTrue(!Character.isHighSurrogate(emoji.last()))
    }
}
