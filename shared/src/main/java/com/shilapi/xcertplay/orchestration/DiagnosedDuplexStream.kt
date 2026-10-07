package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

/**
 * Passes every byte through unchanged and records scalar timings in [ConnectionIoDiagnostics].
 *
 * Writes larger than [maxWriteBytes] are split into consecutive sends of at most that size; the
 * wired carkit path keeps the 256-byte bound it used while diagnosing a stalled certificate
 * transfer. With [wireLog], each write and read also logs a packet-header summary (sizes and
 * iAP2 link header fields, never payload bytes).
 */
internal class DiagnosedDuplexStream(
    private val delegate: BlockingDuplexByteStream,
    private val io: ConnectionIoDiagnostics,
    private val maxWriteBytes: Int = Int.MAX_VALUE,
    private val wireLog: ((String) -> Unit)? = null,
    private val clockNanos: () -> Long = System::nanoTime,
) : BlockingDuplexByteStream {
    init {
        require(maxWriteBytes > 0) { "maxWriteBytes must be positive" }
    }

    override fun send(data: ByteArray) {
        val started = clockNanos()
        var result = ConnectionIoDiagnostics.Result.FAILED
        try {
            wireLog?.invoke("wired link TX begin ${wireSummary(data)}")
            if (data.size <= maxWriteBytes) {
                delegate.send(data)
            } else {
                for (offset in data.indices step maxWriteBytes) {
                    delegate.send(data.copyOfRange(offset, minOf(offset + maxWriteBytes, data.size)))
                }
            }
            wireLog?.invoke("wired link TX completed bytes=${data.size}")
            result = ConnectionIoDiagnostics.Result.COMPLETED
        } finally {
            io.record(ConnectionIoDiagnostics.Operation.WRITE, result, elapsedMillis(started))
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        val started = clockNanos()
        var result = ConnectionIoDiagnostics.Result.FAILED
        try {
            val bytes = delegate.recv(maxBytes, timeoutMillis)
            result = when {
                bytes == null -> ConnectionIoDiagnostics.Result.TIMED_OUT
                bytes.isEmpty() -> ConnectionIoDiagnostics.Result.ENDED
                else -> ConnectionIoDiagnostics.Result.COMPLETED
            }
            if (bytes != null) wireLog?.invoke("wired link RX ${wireSummary(bytes)}")
            return bytes
        } finally {
            io.record(ConnectionIoDiagnostics.Operation.READ, result, elapsedMillis(started))
        }
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            io.finish()
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((clockNanos() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    internal companion object {
        /** Packet headers only, never certificate or challenge data. */
        fun wireSummary(bytes: ByteArray): String {
            if (bytes.size < 9 || bytes[0].toInt() and 0xff != 0xff ||
                bytes[1].toInt() and 0xff != 0x5a) return "bytes=${bytes.size}"
            fun value(index: Int) = bytes[index].toInt() and 0xff
            return "bytes=${bytes.size} length=${(value(2) shl 8) or value(3)} " +
                "flags=${value(4)} seq=${value(5)} ack=${value(6)} session=${value(7)}"
        }
    }
}
