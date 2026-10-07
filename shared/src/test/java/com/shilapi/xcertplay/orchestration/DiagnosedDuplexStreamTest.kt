package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosedDuplexStreamTest {
    private class RecordingStream : BlockingDuplexByteStream {
        val sent = mutableListOf<ByteArray>()
        val replies = ArrayDeque<ByteArray?>()
        var closed = false
        var failNextSend = false

        override fun send(data: ByteArray) {
            if (failNextSend) throw IOException("link down")
            sent += data
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = replies.removeFirst()

        override fun close() {
            closed = true
        }
    }

    @Test fun writesPassThroughAsOneCallWithoutChunking() {
        val link = RecordingStream()
        val lines = mutableListOf<String>()
        val stream = DiagnosedDuplexStream(link, ConnectionIoDiagnostics(lines::add) { 0 }) { 0 }
        val payload = ByteArray(5_000) { it.toByte() }

        stream.send(payload)

        assertEquals(1, link.sent.size)
        assertArrayEquals(payload, link.sent.single())
        assertTrue(lines.single().contains("operation=WRITE result=COMPLETED"))
    }

    @Test fun readOutcomesAreClassifiedAndReturnedUnchanged() {
        val link = RecordingStream()
        val lines = mutableListOf<String>()
        var now = 0L
        val stream = DiagnosedDuplexStream(link, ConnectionIoDiagnostics(lines::add) { now }) { now }
        link.replies += byteArrayOf(1, 2, 3)
        link.replies += null
        link.replies += ByteArray(0)

        now = 0
        assertArrayEquals(byteArrayOf(1, 2, 3), stream.recv(64, 100))
        now = 30_000_000_000L
        assertEquals(null, stream.recv(64, 100))
        now = 60_000_000_000L
        assertEquals(0, stream.recv(64, 100)!!.size)
        stream.close()

        assertTrue(link.closed)
        assertTrue(lines.any { it.contains("result=COMPLETED") })
        assertTrue(lines.any { it.contains("result=TIMED_OUT") })
        assertTrue(lines.last().contains("readCalls=3 writeCalls=0 readTimeouts=1 ends=1 failures=0"))
    }

    @Test fun failedWritesAreRecordedAndRethrown() {
        val link = RecordingStream().apply { failNextSend = true }
        val lines = mutableListOf<String>()
        val stream = DiagnosedDuplexStream(link, ConnectionIoDiagnostics(lines::add) { 0 }) { 0 }

        val error = runCatching { stream.send(byteArrayOf(9)) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(lines.single().contains("operation=WRITE result=FAILED"))
    }

    @Test fun theWiredBoundSplitsWritesInto256ByteSendsAndLogsOnlyHeaders() {
        val link = RecordingStream()
        val wire = mutableListOf<String>()
        val stream = DiagnosedDuplexStream(link, ConnectionIoDiagnostics({}) { 0 }, maxWriteBytes = 256, wireLog = wire::add) { 0 }
        // An iAP2 link packet: FF 5A, length 600, flags, seq, ack, session.
        val payload = ByteArray(600) { it.toByte() }.also {
            it[0] = 0xff.toByte(); it[1] = 0x5a; it[2] = 0x02; it[3] = 0x58.toByte(); it[4] = 0x40; it[5] = 7; it[6] = 6; it[7] = 1
        }

        stream.send(payload)

        assertEquals(listOf(256, 256, 88), link.sent.map { it.size })
        assertArrayEquals(payload, link.sent.reduce { acc, bytes -> acc + bytes })
        assertEquals(
            listOf(
                "wired link TX begin bytes=600 length=600 flags=64 seq=7 ack=6 session=1",
                "wired link TX completed bytes=600",
            ),
            wire,
        )
        link.replies += byteArrayOf(1, 2, 3)
        stream.recv(16, 10)
        assertEquals("wired link RX bytes=3", wire.last())
    }
}
