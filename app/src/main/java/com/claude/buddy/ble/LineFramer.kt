package com.claude.buddy.ble

/**
 * Accumulates raw bytes across multiple BLE writes and emits complete UTF-8 lines on '\n'.
 * Not thread-safe — call from a single-threaded coroutine or with external sync.
 */
class LineFramer {
    private val buffer = StringBuilder()

    fun feed(bytes: ByteArray): List<String> {
        buffer.append(String(bytes, Charsets.UTF_8))
        val lines = mutableListOf<String>()
        var idx: Int
        while (buffer.indexOf('\n').also { idx = it } != -1) {
            val line = buffer.substring(0, idx).trim()
            buffer.delete(0, idx + 1)
            if (line.isNotEmpty()) lines += line
        }
        // Guard against a runaway buffer (>64 KB with no newline = malformed)
        if (buffer.length > 65536) buffer.clear()
        return lines
    }

    fun reset() = buffer.clear()
}
