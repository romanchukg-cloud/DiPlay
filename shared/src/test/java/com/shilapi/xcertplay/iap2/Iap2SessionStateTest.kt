package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class Iap2SessionStateTest {
    /** A link that never answers, so the session never becomes ready. */
    private class SilentStream : BlockingDuplexByteStream {
        @Volatile private var closed = false
        override fun send(data: ByteArray) = Unit
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (closed) return ByteArray(0)
            Thread.sleep(minOf(timeoutMillis, 5L))
            return null
        }
        override fun close() {
            closed = true
        }
    }

    @Test fun stateLinesReachTheStateLogWhileFrameTracesStayOff() {
        val state = CopyOnWriteArrayList<String>()
        val traces = CopyOnWriteArrayList<String>()
        val session = Iap2Session.open(
            SilentStream(),
            traceContext = "test",
            onTrace = traces::add,
            traceFrames = false,
            onState = state::add,
        )

        assertFalse(session.awaitReady(50))
        session.close()

        assertEquals(listOf("IAP2 READY [test] ready=false", "IAP2 CLOSE [test]"), state.toList())
        assertTrue("frame traces stay off", traces.isEmpty())
    }
}
