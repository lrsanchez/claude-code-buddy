package com.claude.buddy.haptics

// ── Haptic events ─────────────────────────────────────────────────────────────

enum class HapticEvent {
    APPROVE, DENY, LEVEL_UP
}

// ── Waveform primitives ───────────────────────────────────────────────────────

data class HapticPulse(
    val durationMs: Long,
    val amplitude: Int,
    val gapAfterMs: Long = 60L,
)

data class HapticPattern(
    val event: HapticEvent,
    val pulses: List<HapticPulse>,
) {
    val totalDurationMs: Long get() = pulses.sumOf { it.durationMs + it.gapAfterMs }
}

// ── Pattern definitions ───────────────────────────────────────────────────────

object HapticPatterns {
    fun forEvent(event: HapticEvent): HapticPattern = when (event) {
        HapticEvent.APPROVE -> HapticPattern(
            HapticEvent.APPROVE,
            listOf(
                HapticPulse(durationMs = 35L, amplitude = 128, gapAfterMs = 60L),
                HapticPulse(durationMs = 70L, amplitude = 200, gapAfterMs = 0L),
            ),
        )
        HapticEvent.DENY -> HapticPattern(
            HapticEvent.DENY,
            listOf(
                HapticPulse(durationMs = 180L, amplitude = 255, gapAfterMs = 0L),
            ),
        )
        HapticEvent.LEVEL_UP -> HapticPattern(
            HapticEvent.LEVEL_UP,
            listOf(
                HapticPulse(durationMs = 50L, amplitude = 90,  gapAfterMs = 70L),
                HapticPulse(durationMs = 50L, amplitude = 150, gapAfterMs = 70L),
                HapticPulse(durationMs = 90L, amplitude = 255, gapAfterMs = 0L),
            ),
        )
    }
}
