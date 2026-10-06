package com.claude.buddy.haptics

// ── Sink ──────────────────────────────────────────────────────────────────────

interface HapticSink {
    /** Play a pattern. Returns true if a vibration was actually performed, false if not. */
    fun play(pattern: HapticPattern): Boolean
}

// ── Controller ────────────────────────────────────────────────────────────────

class HapticsController(private val sink: HapticSink?) {
    fun play(event: HapticEvent): Boolean {
        if (sink == null) return false
        return sink.play(HapticPatterns.forEvent(event))
    }
}
